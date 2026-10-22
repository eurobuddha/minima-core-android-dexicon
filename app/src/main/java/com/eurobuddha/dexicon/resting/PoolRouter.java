package com.eurobuddha.dexicon.resting;
/* Vendored from apks/pandadex app/src/main/java/com/eurobuddha/pandadex/PoolRouter.java (PandaDEX 0.4.23).
 * Keep as close to verbatim as possible; only the package, the NodeApi/SignGate imports and the minimal Dexicon
 * glue noted inline differ. Per the family naming rule the Minima dollar token is MxUSD in identifiers too
 * (PandaDEX MXUSD_ID/MXUSD_DP/*Mxusd are MXUSD_ID/MXUSD_DP/*Mxusd here). Re-copy rather than hand-edit when PandaDEX changes. */


import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** PandaPools-compatible water-filling router plus exact-MINIMA-out routing for composite buys. */
public final class PoolRouter {

    private static final MathContext MC = new MathContext(30, RoundingMode.DOWN);
    public static final int MAX_POOLS = 6;
    private static final int STEPS = 128;

    private PoolRouter() {}

    public static final class Alloc {
        public final Pool pool;
        public final VirtualCurve.Quote quote;
        Alloc(Pool p, VirtualCurve.Quote q) { pool = p; quote = q; }
    }

    public static final class Route {
        public final List<Alloc> allocs = new ArrayList<>();
        public final List<String> pairAddresses = new ArrayList<>();
        public BigDecimal totalIn = BigDecimal.ZERO;
        public BigDecimal totalOut = BigDecimal.ZERO;
        public BigDecimal spotBefore = BigDecimal.ZERO;
        public BigDecimal effPrice = BigDecimal.ZERO;
        public int poolsAvailable = 0;
        public int poolsUsed = 0;
        public boolean capped = false;
        public boolean ok = false;
    }

    public static Route route(List<Pool> pairPools, boolean minimaToToken, BigDecimal totalIn) {
        return routeInternal(pairPools, minimaToToken, false, totalIn);
    }

    /** Route token input to receive exactly/better than {@code minimaOut} aggregate MINIMA. */
    public static Route routeExactMinimaOut(List<Pool> pairPools, BigDecimal minimaOut) {
        return routeInternal(pairPools, false, true, minimaOut);
    }

    private static Route routeInternal(List<Pool> pairPools, boolean minimaToToken,
                                       boolean exactMinimaOut, BigDecimal total) {
        Route r = new Route();
        if (pairPools == null || total == null || total.signum() <= 0) return r;
        List<Pool> pools = funded(pairPools, r);
        if (pools.isEmpty()) return r;
        if (pools.size() > MAX_POOLS) {
            pools.sort(Comparator.comparing((Pool p) -> p.reserveM).reversed());
            pools = new ArrayList<>(pools.subList(0, MAX_POOLS));
            r.capped = true;
        }
        r.spotBefore = VirtualCurve.aggregatePrice(pools);
        int n = pools.size();
        BigDecimal[] alloc = new BigDecimal[n];
        BigDecimal[] curIn = new BigDecimal[n];
        BigDecimal[] curOut = new BigDecimal[n];
        for (int i = 0; i < n; i++) { alloc[i] = BigDecimal.ZERO; curIn[i] = BigDecimal.ZERO; curOut[i] = BigDecimal.ZERO; }
        BigDecimal chunk = total.divide(new BigDecimal(STEPS), MC);
        if (chunk.signum() <= 0) return r;
        BigDecimal placed = BigDecimal.ZERO;
        for (int s = 0; s < STEPS; s++) {
            int best = -1;
            BigDecimal bestScore = exactMinimaOut ? null : BigDecimal.ZERO;
            for (int i = 0; i < n; i++) {
                BigDecimal trial = alloc[i].add(chunk);
                VirtualCurve.Quote q = exactMinimaOut
                        ? VirtualCurve.quoteTForMOut(pools.get(i), trial)
                        : (minimaToToken ? VirtualCurve.quoteMtoT(pools.get(i), trial) : VirtualCurve.quoteTtoM(pools.get(i), trial));
                if (!q.ok) continue;
                if (exactMinimaOut) {
                    BigDecimal cost = q.inAmount.subtract(curIn[i]);
                    if (cost.signum() <= 0) continue;
                    if (bestScore == null || cost.compareTo(bestScore) < 0) { best = i; bestScore = cost; }
                } else {
                    BigDecimal gain = q.outAmount.subtract(curOut[i]);
                    if (gain.compareTo(bestScore) > 0) { best = i; bestScore = gain; }
                }
            }
            if (best < 0) break;
            alloc[best] = alloc[best].add(chunk);
            placed = placed.add(chunk);
            VirtualCurve.Quote qb = exactMinimaOut
                    ? VirtualCurve.quoteTForMOut(pools.get(best), alloc[best])
                    : (minimaToToken ? VirtualCurve.quoteMtoT(pools.get(best), alloc[best]) : VirtualCurve.quoteTtoM(pools.get(best), alloc[best]));
            if (qb.ok) { curIn[best] = qb.inAmount; curOut[best] = qb.outAmount; }
        }
        BigDecimal residual = total.subtract(placed);
        if (residual.signum() > 0) {
            addResidual(pools, alloc, residual, minimaToToken, exactMinimaOut);
        }
        for (int i = 0; i < n; i++) {
            if (alloc[i].signum() <= 0) continue;
            VirtualCurve.Quote q = exactMinimaOut
                    ? VirtualCurve.quoteTForMOut(pools.get(i), alloc[i])
                    : (minimaToToken ? VirtualCurve.quoteMtoT(pools.get(i), alloc[i]) : VirtualCurve.quoteTtoM(pools.get(i), alloc[i]));
            if (!q.ok) continue;
            r.allocs.add(new Alloc(pools.get(i), q));
            r.totalIn = r.totalIn.add(q.inAmount);
            r.totalOut = r.totalOut.add(q.outAmount);
        }
        r.poolsUsed = r.allocs.size();
        if (r.poolsUsed == 0 || r.totalOut.signum() <= 0) return r;
        if (exactMinimaOut && r.totalOut.compareTo(total) < 0) {
            r.allocs.clear();
            r.totalIn = BigDecimal.ZERO;
            r.totalOut = BigDecimal.ZERO;
            r.poolsUsed = 0;
            return r;
        }
        r.effPrice = (minimaToToken || exactMinimaOut) ? r.totalIn.divide(r.totalOut, MC) : r.totalIn.divide(r.totalOut, MC);
        if (minimaToToken) r.effPrice = r.totalOut.divide(r.totalIn, MC);
        r.ok = true;
        return r;
    }

    private static void addResidual(List<Pool> pools, BigDecimal[] alloc, BigDecimal residual,
                                    boolean minimaToToken, boolean exactMinimaOut) {
        int best = -1;
        BigDecimal bestScore = null;
        for (int i = 0; i < pools.size(); i++) {
            BigDecimal trialAmount = alloc[i].add(residual);
            VirtualCurve.Quote trial = exactMinimaOut
                    ? VirtualCurve.quoteTForMOut(pools.get(i), trialAmount)
                    : (minimaToToken ? VirtualCurve.quoteMtoT(pools.get(i), trialAmount) : VirtualCurve.quoteTtoM(pools.get(i), trialAmount));
            if (!trial.ok) continue;

            BigDecimal previousIn = BigDecimal.ZERO;
            BigDecimal previousOut = BigDecimal.ZERO;
            if (alloc[i].signum() > 0) {
                VirtualCurve.Quote previous = exactMinimaOut
                        ? VirtualCurve.quoteTForMOut(pools.get(i), alloc[i])
                        : (minimaToToken ? VirtualCurve.quoteMtoT(pools.get(i), alloc[i]) : VirtualCurve.quoteTtoM(pools.get(i), alloc[i]));
                if (!previous.ok) continue;
                previousIn = previous.inAmount;
                previousOut = previous.outAmount;
            }

            BigDecimal score = exactMinimaOut ? trial.inAmount.subtract(previousIn) : trial.outAmount.subtract(previousOut);
            if (best < 0
                    || (exactMinimaOut && score.compareTo(bestScore) < 0)
                    || (!exactMinimaOut && score.compareTo(bestScore) > 0)) {
                best = i;
                bestScore = score;
            }
        }
        if (best >= 0) alloc[best] = alloc[best].add(residual);
    }

    private static List<Pool> funded(List<Pool> pairPools, Route r) {
        List<Pool> pools = new ArrayList<>();
        for (Pool p : pairPools) if (p.funded()) {
            pools.add(p);
            if (p.address != null) r.pairAddresses.add(p.address);
        }
        r.poolsAvailable = pools.size();
        return pools;
    }

    public static BigDecimal aggregateDepth(List<Pool> pairPools) {
        List<Pool> pools = new ArrayList<>();
        for (Pool p : pairPools) if (p.funded()) pools.add(p);
        if (pools.size() > MAX_POOLS) {
            pools.sort(Comparator.comparing((Pool p) -> p.reserveM).reversed());
            pools = pools.subList(0, MAX_POOLS);
        }
        return VirtualCurve.totalMinima(pools);
    }

    public static List<List<Pool>> byToken(List<Pool> pools) {
        List<String> order = new ArrayList<>();
        List<List<Pool>> groups = new ArrayList<>();
        for (Pool p : pools) {
            if (!p.funded()) continue;
            int idx = order.indexOf(p.tok);
            if (idx < 0) { order.add(p.tok); List<Pool> g = new ArrayList<>(); g.add(p); groups.add(g); }
            else groups.get(idx).add(p);
        }
        List<Integer> idxs = new ArrayList<>();
        for (int i = 0; i < groups.size(); i++) idxs.add(i);
        Collections.sort(idxs, (a, b) -> VirtualCurve.totalMinima(groups.get(b)).compareTo(VirtualCurve.totalMinima(groups.get(a))));
        List<List<Pool>> sorted = new ArrayList<>();
        for (int i : idxs) sorted.add(groups.get(i));
        return sorted;
    }
}
