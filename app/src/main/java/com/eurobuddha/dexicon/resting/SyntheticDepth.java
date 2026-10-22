package com.eurobuddha.dexicon.resting;
/* Vendored from apks/pandadex app/src/main/java/com/eurobuddha/pandadex/SyntheticDepth.java (PandaDEX 0.4.23).
 * Keep as close to verbatim as possible; only the package, the NodeApi/SignGate imports and the minimal Dexicon
 * glue noted inline differ. Per the family naming rule the Minima dollar token is MxUSD in identifiers too
 * (PandaDEX MXUSD_ID/MXUSD_DP/*Mxusd are MXUSD_ID/MXUSD_DP/*Mxusd here). Re-copy rather than hand-edit when PandaDEX changes. */


import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/** Samples PandaPools liquidity into labelled order-book depth bands. */
public final class SyntheticDepth {

    private static final BigDecimal DEFAULT_TICK = new BigDecimal("0.00001");
    private static final BigDecimal DISPLAY_DEPTH_CUT = new BigDecimal("0.00001");
    private static final int SOLVE_ITERATIONS = 8;
    private static final int CAP_ITERATIONS = 8;
    private static final BigDecimal ROUTER_SLICES = new BigDecimal("128");

    public static final class Row {
        public final BigDecimal price;
        public final BigDecimal poolMinima;
        Row(BigDecimal price, BigDecimal poolMinima) { this.price = price; this.poolMinima = poolMinima; }
    }

    private SyntheticDepth() {}

    public static List<Row> sample(List<Pool> pools, boolean askSide, BigDecimal tick, int rows) {
        List<Pool> pair = new ArrayList<>();
        if (pools != null) for (Pool p : pools)
            if (p.funded() && DexContract.MXUSD_ID.equalsIgnoreCase(p.tok)) pair.add(p);
        if (pair.isEmpty() || rows <= 0) return new ArrayList<>();
        BigDecimal px = VirtualCurve.aggregatePrice(pair);
        if (px.signum() <= 0) return new ArrayList<>();
        BigDecimal cap = VirtualCurve.totalMinima(pair).multiply(new BigDecimal("0.5"));
        if (cap.signum() <= 0) return new ArrayList<>();
        BigDecimal quantum = tick == null ? DEFAULT_TICK : tick;
        List<Row> out = new ArrayList<>();
        BigDecimal prev = BigDecimal.ZERO;
        for (int i = 1; i <= rows; i++) {
            BigDecimal boundary = askSide ? px.add(quantum.multiply(new BigDecimal(i)))
                    : px.subtract(quantum.multiply(new BigDecimal(i)));
            BigDecimal bucket = bucketPrice(boundary, askSide, quantum);
            if (bucket.signum() <= 0) break;
            BigDecimal cum = solveToMarginalBoundary(pair, askSide, boundary, cap);
            cum = capToExecutable(pair, askSide, cum, bucket);
            BigDecimal band = cum.subtract(prev);
            if (band.signum() <= 0) continue;
            out.add(new Row(bucket, band));
            prev = cum;
            if (prev.compareTo(cap) >= 0) break;
        }
        return enforceExecutable(pair, askSide, out);
    }

    private static List<Row> enforceExecutable(List<Pool> pools, boolean askSide, List<Row> rows) {
        List<Row> safe = new ArrayList<>();
        BigDecimal displayed = BigDecimal.ZERO;
        BigDecimal safeDisplayed = BigDecimal.ZERO;
        for (Row row : rows) {
            displayed = displayed.add(row.poolMinima);
            BigDecimal capped = conservativeExecutable(pools, askSide, displayed, row.price);
            BigDecimal band = capped.subtract(safeDisplayed);
            if (band.signum() <= 0) continue;
            safe.add(new Row(row.price, band));
            safeDisplayed = capped;
        }
        return safe;
    }

    private static BigDecimal solveToMarginalBoundary(List<Pool> pools, boolean askSide,
                                                      BigDecimal boundary, BigDecimal cap) {
        BigDecimal lo = BigDecimal.ZERO;
        BigDecimal hi = cap;
        for (int i = 0; i < SOLVE_ITERATIONS; i++) {
            BigDecimal mid = lo.add(hi).divide(new BigDecimal(2), PriceMath.MINIMA_DP, RoundingMode.HALF_UP);
            BigDecimal marginal = marginalPrice(pools, askSide, mid);
            if (marginal == null) { hi = mid; continue; }
            int cmp = marginal.compareTo(boundary);
            if (askSide ? cmp <= 0 : cmp >= 0) lo = mid; else hi = mid;
        }
        return lo;
    }

    private static BigDecimal capToExecutable(List<Pool> pools, boolean askSide,
                                              BigDecimal amount, BigDecimal limitPrice) {
        if (amount.signum() <= 0) return amount;
        return conservativeExecutable(pools, askSide, amount, limitPrice);
    }

    private static BigDecimal conservativeExecutable(List<Pool> pools, boolean askSide,
                                                     BigDecimal amount, BigDecimal limitPrice) {
        BigDecimal candidate = amount.subtract(DISPLAY_DEPTH_CUT).max(BigDecimal.ZERO);
        for (int i = 0; i < CAP_ITERATIONS && candidate.signum() > 0; i++) {
            CompositeRouter.Plan p = CompositeRouter.plan(new ArrayList<>(), pools, askSide,
                    candidate, limitPrice, 0);
            if (p.totalMinima.compareTo(candidate) >= 0) return candidate;
            candidate = p.totalMinima.min(candidate).subtract(DISPLAY_DEPTH_CUT).max(BigDecimal.ZERO);
        }
        return BigDecimal.ZERO;
    }

    private static BigDecimal marginalPrice(List<Pool> pools, boolean askSide, BigDecimal amount) {
        if (amount == null || amount.signum() <= 0) return null;
        BigDecimal epsilon = amount.divide(ROUTER_SLICES, PriceMath.MINIMA_DP, RoundingMode.UP);
        if (epsilon.signum() <= 0) return null;
        BigDecimal beforeAmt = amount.subtract(epsilon).max(BigDecimal.ZERO);
        PoolRouter.Route before = beforeAmt.signum() > 0
                ? (askSide ? PoolRouter.routeExactMinimaOut(pools, beforeAmt) : PoolRouter.route(pools, true, beforeAmt))
                : null;
        PoolRouter.Route after = askSide
                ? PoolRouter.routeExactMinimaOut(pools, amount)
                : PoolRouter.route(pools, true, amount);
        if (after == null || !after.ok) return null;
        BigDecimal prevM = before != null && before.ok ? (askSide ? before.totalOut : before.totalIn) : BigDecimal.ZERO;
        BigDecimal prevT = before != null && before.ok ? (askSide ? before.totalIn : before.totalOut) : BigDecimal.ZERO;
        BigDecimal curM = askSide ? after.totalOut : after.totalIn;
        BigDecimal curT = askSide ? after.totalIn : after.totalOut;
        BigDecimal dm = curM.subtract(prevM);
        BigDecimal dt = curT.subtract(prevT);
        if (dm.signum() <= 0 || dt.signum() <= 0) return null;
        return dt.divide(dm, PriceMath.PRICE_DP, RoundingMode.HALF_UP);
    }

    private static BigDecimal bucketPrice(BigDecimal price, boolean askSide, BigDecimal tick) {
        if (price == null || price.signum() <= 0 || tick == null || tick.signum() <= 0) return BigDecimal.ZERO;
        RoundingMode mode = askSide ? RoundingMode.CEILING : RoundingMode.FLOOR;
        return price.divide(tick, 0, mode).multiply(tick);
    }
}
