package com.eurobuddha.dexicon.doors;

import com.eurobuddha.atomix.TradingContext;
import com.eurobuddha.atomix.swap.MinimaHtlc;
import com.eurobuddha.atomix.swap.Order;
import com.eurobuddha.atomix.swap.SwapOrderBook;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The DOORS quote: AtomiX's taker amount math + market-sweep planner, PORTED (not vendored) from apks/atomix 0.1.82
 * MainActivity — computeUsdt / computeMinima / ceilUsdt / legMinima / bestMakers / aggSide / buildSweepPlan /
 * sweepDepthMinima — because in AtomiX they are private members of a 4,500-line Activity. The arithmetic is unchanged
 * line for line (6dp grain, SELL floor / BUY ceil, per-maker running balances, sub-min legs skipped, slippage anchored
 * on the first leg taken); only the book and "my identity" are parameters instead of Activity fields. The MiniDapp's
 * twin is mds/dexicon lib/door.js planRoute (over atomix-mds swapplan.js — itself a port of the same Java).
 *
 * {@link #route} quotes ONE AtomiX market for the DOORS route cards; the DOORS tab calls it for both markets
 * (MxUSD = {@link TradingContext#MXUSDT}, MINIMA = {@link TradingContext#MINIMA}) and shows them side by side.
 */
public final class DoorPlan {
    private DoorPlan() {}

    public static final String SYM = "USDT";
    /** BUY slippage cap — the MiniDapp DOORS value (2 %), so both apps quote the same route identically. */
    public static final double BUY_SLIPPAGE = 0.02;
    private static final double EPS = 1e-9, GRAIN = 1e-6;

    /** Display name of a market's Minima-side coin. The dollar token is MxUSD — never the engine's legacy label. */
    public static String coin(TradingContext m) { return m == TradingContext.MINIMA ? "MINIMA" : "MxUSD"; }

    // ------------------------------------------------------------------ amount math (AtomiX MainActivity)

    static String tidy(BigDecimal v) { return v.stripTrailingZeros().toPlainString(); }
    static double parseD(String s, double def) { try { return Double.parseDouble(s.trim()); } catch (Exception e) { return def; } }

    /** A positive amount that survives the 6dp trade grain. */
    public static boolean positiveSwapAmount(String raw) {
        if (raw == null || !raw.trim().matches("[0-9]+(\\.[0-9]+)?")) return false;
        double value = parseD(raw, Double.NaN);
        return Double.isFinite(value) && value > 0 && new BigDecimal(MinimaHtlc.grain(raw.trim())).signum() > 0;
    }
    /** USDT received selling {@code minima} at {@code price} = floor6(minima × price). */
    public static String computeUsdt(String minimaAmount, double price) {
        if (!positiveSwapAmount(minimaAmount) || !Double.isFinite(price) || price <= 0) return null;
        try {
            BigDecimal m = new BigDecimal(MinimaHtlc.grain(minimaAmount.trim()));
            BigDecimal usdt = m.multiply(BigDecimal.valueOf(price)).setScale(6, RoundingMode.DOWN);
            if (usdt.signum() <= 0 || !Double.isFinite(usdt.doubleValue())) return null;
            return tidy(usdt);
        } catch (Exception e) { return null; }
    }
    /** Coin received spending {@code usdt} at {@code price} = floor6(usdt ÷ price). */
    public static String computeMinima(String usdtAmount, double price) {
        if (!positiveSwapAmount(usdtAmount) || !Double.isFinite(price) || price <= 0) return null;
        try {
            BigDecimal u = new BigDecimal(MinimaHtlc.grain(usdtAmount.trim()));
            BigDecimal m = u.divide(BigDecimal.valueOf(price), 6, RoundingMode.DOWN);
            if (m.signum() <= 0 || !Double.isFinite(m.doubleValue())) return null;
            return tidy(m);
        } catch (Exception e) { return null; }
    }
    /** Round a coin quantity DOWN to the 6dp grain (never over a level's cap). */
    public static String legMinima(double v) { return tidy(BigDecimal.valueOf(v).setScale(6, RoundingMode.DOWN)); }
    /** USDT locked for a BUY leg = ceil6(minima × price), exact from the minima STRING. */
    public static String ceilUsdt(String minima, double price) {
        if (!positiveSwapAmount(minima) || !Double.isFinite(price) || price <= 0) return null;
        try {
            BigDecimal u = new BigDecimal(minima.trim()).multiply(BigDecimal.valueOf(price)).setScale(6, RoundingMode.UP);
            if (u.signum() <= 0 || !Double.isFinite(u.doubleValue())) return null;
            String s = tidy(u);
            return (s.isEmpty() || "0".equals(s)) ? null : s;
        } catch (Exception e) { return null; }
    }

    // ------------------------------------------------------------------ book reading (AtomiX MainActivity)

    static boolean isMine(Order o, String myPublicId) {
        return o != null && myPublicId != null && o.commsPublicId != null && o.commsPublicId.equals(myPublicId);
    }

    /** Best non-own bid/ask level; equal price → deepest maker (SwapOrderBook.compareForFill). */
    public static final class Best {
        public Order bidMaker, askMaker; public Order.Level bidLevel, askLevel;
        public double bestBid, bestAsk, bidCap, askCap;
    }
    public static Best bestMakers(Map<String, Order> book, String myPublicId) {
        Best r = new Best();
        for (Order o : book.values()) {
            if (isMine(o, myPublicId)) continue;
            for (Order.Level l : o.effectiveBids(SYM)) {
                double cap = SwapOrderBook.levelCap(o, l, true);
                if (cap <= 0) continue;
                if (r.bidMaker == null || SwapOrderBook.compareForFill(o, l, r.bidMaker, r.bidLevel, true) < 0) {
                    r.bestBid = l.price; r.bidMaker = o; r.bidLevel = l; r.bidCap = cap;
                }
            }
            for (Order.Level l : o.effectiveAsks(SYM)) {
                double cap = SwapOrderBook.levelCap(o, l, false);
                if (cap <= 0) continue;
                if (r.askMaker == null || SwapOrderBook.compareForFill(o, l, r.askMaker, r.askLevel, false) < 0) {
                    r.bestAsk = l.price; r.askMaker = o; r.askLevel = l; r.askCap = cap;
                }
            }
        }
        if (r.askMaker == null) r.bestAsk = 0;
        return r;
    }
    static List<Object[]> aggSide(Map<String, Order> book, boolean sell, String myPublicId) {
        List<Object[]> agg = new ArrayList<>();
        for (Order o : book.values()) {
            if (isMine(o, myPublicId)) continue;
            for (Order.Level l : (sell ? o.effectiveBids(SYM) : o.effectiveAsks(SYM))) agg.add(new Object[]{o, l});
        }
        Collections.sort(agg, (a, b) -> SwapOrderBook.compareForFill((Order) a[0], (Order.Level) a[1], (Order) b[0], (Order.Level) b[1], sell));
        return agg;
    }
    /** Makers (not me) with a fillable level on the side a taker needs. */
    public static int liveMakers(Map<String, Order> book, boolean sell, String myPublicId) {
        int n = 0;
        for (Order o : book.values()) {
            if (isMine(o, myPublicId)) continue;
            for (Order.Level l : (sell ? o.effectiveBids(SYM) : o.effectiveAsks(SYM)))
                if (SwapOrderBook.levelCap(o, l, sell) > 0) { n++; break; }
        }
        return n;
    }

    // ------------------------------------------------------------------ sweep plan (AtomiX MainActivity)

    public static final class Leg {
        public final Order maker; public final double price; public final String minima, usdt; public final double minimaD, usdtD;
        Leg(Order maker, double price, String minima, String usdt) {
            this.maker = maker; this.price = price; this.minima = minima; this.usdt = usdt;
            this.minimaD = parseD(minima, 0); this.usdtD = parseD(usdt, 0);
        }
    }
    public static final class Plan {
        public final boolean sell; public final List<Leg> legs = new ArrayList<>();
        public double target, filledMinima, totalUsdt, avgPrice, worstPrice, slippagePct;
        public boolean partial; public String stopReason = "filled";   // filled | leg-cap | book-exhausted | below-min | slippage
        Plan(boolean sell) { this.sell = sell; }
    }

    public static Plan buildSweepPlan(Map<String, Order> book, boolean sell, String targetStr, double slippage, String myPublicId) {
        Plan plan = new Plan(sell);
        if (!positiveSwapAmount(targetStr) || !Double.isFinite(slippage) || slippage < 0 || slippage > 0.5) { plan.stopReason = "book-exhausted"; return plan; }
        plan.slippagePct = slippage * 100;
        double target = parseD(targetStr, 0);
        plan.target = target;
        if (target <= 0) { plan.stopReason = "book-exhausted"; return plan; }
        final double dust = Math.max(EPS, target * 1e-4);
        Map<Order, Double> remUsdt = new HashMap<>(), remMinima = new HashMap<>();
        double remaining = target, bestPrice = 0;
        boolean skippedForMin = false;
        for (Object[] row : aggSide(book, sell, myPublicId)) {
            if (plan.legs.size() >= Order.MAX_LEVELS) { plan.stopReason = "leg-cap"; plan.partial = true; break; }
            if (remaining <= dust) break;
            Order maker = (Order) row[0];
            Order.Level lvl = (Order.Level) row[1];
            double price = lvl.price;
            if (!Double.isFinite(price) || price <= 0) continue;
            if (!sell && bestPrice > 0 && slippage > 0 && price > bestPrice * (1 + slippage) + EPS) { plan.stopReason = "slippage"; plan.partial = true; break; }
            Order.Pair pr = maker.pairs.get(SYM);
            double mn = pr != null ? pr.min : 0;
            double capMinima;
            if (sell) { double ru = remUsdt.containsKey(maker) ? remUsdt.get(maker) : maker.usdtAvail; capMinima = Math.min(lvl.amount, ru / price); }
            else { double rm = remMinima.containsKey(maker) ? remMinima.get(maker) : maker.minimaAvail; capMinima = Math.min(lvl.amount, rm); }
            if (!Double.isFinite(capMinima) || capMinima <= EPS) continue;
            double takeMinima = Math.min(capMinima, remaining);
            if (takeMinima <= EPS) continue;
            if (mn > 0 && takeMinima < mn - GRAIN) { skippedForMin = true; continue; }
            String minimaStr = legMinima(takeMinima);
            if (parseD(minimaStr, 0) <= EPS) continue;
            String usdtStr = sell ? computeUsdt(minimaStr, price) : ceilUsdt(minimaStr, price);
            if (usdtStr == null) continue;
            Leg leg = new Leg(maker, price, minimaStr, usdtStr);
            if (leg.minimaD <= EPS || leg.usdtD <= EPS) continue;
            if (mn > 0 && leg.minimaD < mn - EPS) { skippedForMin = true; continue; }
            if (!Double.isFinite(plan.filledMinima + leg.minimaD) || !Double.isFinite(plan.totalUsdt + leg.usdtD)) { plan.partial = true; break; }
            if (bestPrice <= 0) bestPrice = price;
            plan.legs.add(leg);
            plan.filledMinima += leg.minimaD;
            plan.totalUsdt += leg.usdtD;
            plan.worstPrice = price;
            if (sell) remUsdt.put(maker, (remUsdt.containsKey(maker) ? remUsdt.get(maker) : maker.usdtAvail) - leg.usdtD);
            else remMinima.put(maker, (remMinima.containsKey(maker) ? remMinima.get(maker) : maker.minimaAvail) - leg.minimaD);
            remaining -= leg.minimaD;
        }
        if (remaining > dust) plan.partial = true;
        if (plan.legs.isEmpty() && skippedForMin && "filled".equals(plan.stopReason)) plan.stopReason = "below-min";
        if (plan.partial && "filled".equals(plan.stopReason)) plan.stopReason = "book-exhausted";
        plan.avgPrice = plan.filledMinima > 0 ? plan.totalUsdt / plan.filledMinima : 0;
        return plan;
    }
    public static double sweepDepthMinima(Map<String, Order> book, boolean sell, String myPublicId) {
        return buildSweepPlan(book, sell, "1000000000", sell ? 0 : BUY_SLIPPAGE, myPublicId).filledMinima;
    }

    // ------------------------------------------------------------------ one route card (Dexicon; = lib/door.js planRoute)

    public static final class Route {
        public final TradingContext market; public final String coin; public final boolean sell;
        public Plan plan; public String err; public double best, depth; public int makers;
        Route(TradingContext market, boolean sell) { this.market = market; this.coin = coin(market); this.sell = sell; }
    }
    /**
     * Quote one market for the amount typed. SELL: {@code amt} of the route coin straight into the sweep; BUY: the USDT
     * typed is converted at the best ask into a coin target, planned within {@link #BUY_SLIPPAGE}, and shaved once if
     * ceil-rounding would overshoot what was typed (the MiniDapp DOORS rule).
     */
    public static Route route(Map<String, Order> book, TradingContext market, boolean sell, String amt, String myPublicId) {
        if (book == null) book = Collections.emptyMap();
        Route r = new Route(market, sell);
        Best q = bestMakers(book, myPublicId);
        r.best = sell ? q.bestBid : q.bestAsk;
        r.depth = sweepDepthMinima(book, sell, myPublicId);
        r.makers = liveMakers(book, sell, myPublicId);
        if (amt == null || amt.trim().isEmpty()) return r;
        if (!positiveSwapAmount(amt)) { r.err = "Enter a valid amount."; return r; }
        if (!(r.best > 0)) { r.err = "No AtomiX maker is " + (sell ? "buying " : "selling ") + r.coin + " right now."; return r; }
        double n = parseD(amt, 0);
        Plan p;
        if (sell) p = buildSweepPlan(book, true, amt.trim(), 0, myPublicId);
        else {
            String target = computeMinima(amt.trim(), q.bestAsk);
            if (target == null) { r.err = "Amount too small."; return r; }
            p = buildSweepPlan(book, false, target, BUY_SLIPPAGE, myPublicId);
            if (!p.legs.isEmpty() && p.totalUsdt > n + EPS) {
                String less = legMinima(parseD(target, 0) - (p.totalUsdt - n) / q.bestAsk - 1e-6);
                p = buildSweepPlan(book, false, less, BUY_SLIPPAGE, myPublicId);
            }
        }
        if (p.legs.isEmpty()) { r.err = "below-min".equals(p.stopReason) ? "Below the makers’ minimum trade size." : "No liquidity to fill that right now."; return r; }
        r.plan = p;
        return r;
    }
}
