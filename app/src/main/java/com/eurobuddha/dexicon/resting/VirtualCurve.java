package com.eurobuddha.dexicon.resting;
/* Vendored from apks/pandadex app/src/main/java/com/eurobuddha/pandadex/VirtualCurve.java (PandaDEX 0.4.23).
 * Keep as close to verbatim as possible; only the package, the NodeApi/SignGate imports and the minimal Dexicon
 * glue noted inline differ. Per the family naming rule the Minima dollar token is MxUSD in identifiers too
 * (PandaDEX MXUSD_ID/MXUSD_DP/*Mxusd are MXUSD_ID/MXUSD_DP/*Mxusd here). Re-copy rather than hand-edit when PandaDEX changes. */


import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.List;

/** PandaPools-compatible constant-product quote math with pool-favourable rounding. */
public final class VirtualCurve {

    private static final MathContext MC = new MathContext(40, RoundingMode.DOWN);
    private static final BigDecimal FEE_NUM = new BigDecimal("5");
    private static final BigDecimal FEE_DEN = new BigDecimal("1000");
    private static final BigDecimal FEE_KEEP = new BigDecimal("0.995");
    private static final int MINIMA_DP = 11;

    public static class Quote {
        public BigDecimal inAmount;
        public BigDecimal outAmount;
        public BigDecimal newX, newY;
        public BigDecimal spotBefore, spotAfter;
        public BigDecimal effPrice;
        public boolean ok;
    }

    private VirtualCurve() {}

    public static Quote quoteMtoT(Pool p, BigDecimal dx) {
        Quote q = new Quote();
        if (!p.funded() || dx == null || dx.signum() <= 0) return q;
        int dp = p.tokDecimals;
        BigDecimal x = p.reserveM, y = p.reserveT, kmin = Util.decOr(p.kmin, BigDecimal.ZERO);
        BigDecimal nx = x.add(dx);
        BigDecimal fx = dx.multiply(FEE_NUM).divide(FEE_DEN, MC);
        BigDecimal rhs = x.multiply(y).max(kmin);
        BigDecimal ny = rhs.divide(nx.subtract(fx), dp, RoundingMode.UP);
        BigDecimal dy = y.subtract(ny);
        if (dy.signum() <= 0) return q;
        q.inAmount = dx; q.outAmount = dy; q.newX = nx; q.newY = ny;
        q.spotBefore = p.spotPrice(); q.spotAfter = ny.divide(nx, MC);
        q.effPrice = dy.divide(dx, MC);
        q.ok = true;
        return q;
    }

    public static Quote quoteTtoM(Pool p, BigDecimal dyinRaw) {
        Quote q = new Quote();
        if (!p.funded() || dyinRaw == null || dyinRaw.signum() <= 0) return q;
        int dp = p.tokDecimals;
        BigDecimal dyin = dyinRaw.setScale(dp, RoundingMode.DOWN);
        if (dyin.signum() <= 0) return q;
        BigDecimal x = p.reserveM, y = p.reserveT, kmin = Util.decOr(p.kmin, BigDecimal.ZERO);
        BigDecimal ny = y.add(dyin);
        BigDecimal fy = dyin.multiply(FEE_NUM).divide(FEE_DEN, MC);
        BigDecimal rhs = x.multiply(y).max(kmin);
        BigDecimal nx = rhs.divide(ny.subtract(fy), MINIMA_DP, RoundingMode.UP);
        BigDecimal dm = x.subtract(nx);
        if (dm.signum() <= 0) return q;
        q.inAmount = dyin; q.outAmount = dm; q.newX = nx; q.newY = ny;
        q.spotBefore = p.spotPrice(); q.spotAfter = ny.divide(nx, MC);
        q.effPrice = dyin.divide(dm, MC);
        q.ok = true;
        return q;
    }

    /** Inverse buy quote: token input needed to receive at least {@code minimaOut} MINIMA. */
    public static Quote quoteTForMOut(Pool p, BigDecimal minimaOut) {
        Quote q = new Quote();
        if (!p.funded() || minimaOut == null || minimaOut.signum() <= 0 || minimaOut.compareTo(p.reserveM) >= 0) return q;
        BigDecimal x = p.reserveM, y = p.reserveT, rhs = x.multiply(y).max(Util.decOr(p.kmin, BigDecimal.ZERO));
        BigDecimal nxTarget = x.subtract(minimaOut);
        BigDecimal needAfterFee = rhs.divide(nxTarget, MC).subtract(y);
        if (needAfterFee.signum() <= 0) return q;
        BigDecimal dyin = needAfterFee.divide(FEE_KEEP, p.tokDecimals, RoundingMode.UP);
        Quote forward = quoteTtoM(p, dyin);
        if (!forward.ok || forward.outAmount.compareTo(minimaOut) < 0) return q;
        return forward;
    }

    public static BigDecimal aggregatePrice(List<Pool> pools) {
        BigDecimal sumX = BigDecimal.ZERO, sumY = BigDecimal.ZERO;
        for (Pool p : pools) if (p.funded()) { sumX = sumX.add(p.reserveM); sumY = sumY.add(p.reserveT); }
        return sumX.signum() == 0 ? BigDecimal.ZERO : sumY.divide(sumX, MC);
    }

    public static BigDecimal totalMinima(List<Pool> pools) {
        BigDecimal s = BigDecimal.ZERO;
        for (Pool p : pools) if (p.funded()) s = s.add(p.reserveM);
        return s;
    }
}
