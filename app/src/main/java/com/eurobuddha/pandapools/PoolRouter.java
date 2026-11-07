// VENDORED VERBATIM from apks/pandapools 0.9.86 app/src/main/java/com/eurobuddha/pandapools/PoolRouter.java by scripts/vendor-pandapools.sh — do not edit here; re-vendor.
package com.eurobuddha.pandapools;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * The multi-pool router: splits one trade across every pool for a pair by constant-product
 * WATER-FILLING — each incremental slice goes to whichever pool currently offers the best marginal
 * price, so the aggregate execution is optimal across whatever prices exist. For equally-priced pools
 * this reduces to a split proportional to reserves, and N equal pools then behave EXACTLY like one pool
 * of the summed reserves (zero fragmentation cost). Each pool's slice is quoted through
 * {@link VirtualCurve} so every leg is grain-correct and pool-favourable; the transaction builder just
 * sums them.
 *
 * A single transaction spends + recreates every used pool at once (proven in the phase-B 3-pool routed
 * swap), capped at {@link #MAX_POOLS} legs for the 64KB TxPoW ceiling; deeper-liquidity pools are
 * preferred when more exist, and the shortfall is surfaced.
 */
public final class PoolRouter {

    private static final MathContext MC = new MathContext(30, RoundingMode.DOWN);
    /** Max pools in one routed transaction. Each adds 2 covenant inputs (script + MMR proof, ~2.5KB each)
     *  + 2 outputs; 6 keeps the tx comfortably under the 64KB TxPoW ceiling with room for fragmented
     *  funding. The phase-B suite proved 3-pool routing on-chain; raise this once ≥6 is validated live. */
    public static final int MAX_POOLS = 6;
    /** Water-filling granularity — more steps = finer optimum, still trivial for ≤8 pools on-device. */
    private static final int STEPS = 128;

    private PoolRouter() {}

    public static final class Alloc {
        public final Pool pool;
        public final VirtualCurve.Quote quote;
        Alloc(Pool p, VirtualCurve.Quote q) { pool = p; quote = q; }
    }

    public static final class Route {
        public final List<Alloc> allocs = new ArrayList<>();
        public final List<String> pairAddresses = new ArrayList<>();  // ALL funded pool addresses for the pair (for funding exclusion)
        public BigDecimal totalIn = BigDecimal.ZERO;    // sum of the (grain-clamped) inputs actually used
        public BigDecimal totalOut = BigDecimal.ZERO;   // aggregate proceeds
        public BigDecimal spotBefore = BigDecimal.ZERO; // aggregate token/MINIMA before
        public BigDecimal effPrice = BigDecimal.ZERO;   // realised out/in (direction-aware, token per MINIMA)
        public int poolsAvailable = 0;                  // funded pools for the pair
        public int poolsUsed = 0;                       // pools the trade actually touches
        public boolean capped = false;                  // more pools existed than MAX_POOLS
        public boolean ok = false;
    }

    /**
     * Route {@code totalIn} across {@code pairPools} (all must share the same token). {@code minimaToToken}
     * = pay MINIMA get token, else pay token get MINIMA.
     */
    public static Route route(List<Pool> pairPools, boolean minimaToToken, BigDecimal totalIn) {
        Route r = new Route();
        if (pairPools == null || totalIn == null || totalIn.signum() <= 0) return r;

        List<Pool> pools = new ArrayList<>();
        for (Pool p : pairPools) if (p.funded()) {
            pools.add(p);
            if (p.address != null) r.pairAddresses.add(p.address);   // every pair pool, even if not routed/capped
        }
        r.poolsAvailable = pools.size();
        if (pools.isEmpty()) return r;

        // prefer the deepest pools when more than MAX_POOLS exist
        if (pools.size() > MAX_POOLS) {
            pools.sort(Comparator.comparing((Pool p) -> p.reserveM).reversed());
            pools = new ArrayList<>(pools.subList(0, MAX_POOLS));
            r.capped = true;
        }
        r.spotBefore = VirtualCurve.aggregatePrice(pools);

        int n = pools.size();
        BigDecimal[] alloc = new BigDecimal[n];
        BigDecimal[] curOut = new BigDecimal[n];
        for (int i = 0; i < n; i++) { alloc[i] = BigDecimal.ZERO; curOut[i] = BigDecimal.ZERO; }

        BigDecimal chunk = totalIn.divide(new BigDecimal(STEPS), MC);
        if (chunk.signum() <= 0) return r;

        BigDecimal placed = BigDecimal.ZERO;
        for (int s = 0; s < STEPS; s++) {
            int best = -1;
            BigDecimal bestGain = BigDecimal.ZERO;
            for (int i = 0; i < n; i++) {
                BigDecimal trial = alloc[i].add(chunk);
                VirtualCurve.Quote q = quote(pools.get(i), minimaToToken, trial);
                if (!q.ok) continue;
                BigDecimal gain = q.outAmount.subtract(curOut[i]);   // marginal proceeds from this slice
                if (gain.compareTo(bestGain) > 0) { best = i; bestGain = gain; }
            }
            if (best < 0) break;   // no pool can absorb another slice profitably
            alloc[best] = alloc[best].add(chunk);
            placed = placed.add(chunk);
            VirtualCurve.Quote qb = quote(pools.get(best), minimaToToken, alloc[best]);
            curOut[best] = qb.ok ? qb.outAmount : curOut[best];
        }
        // assign any rounding residual to the deepest allocated pool so sum(alloc) == totalIn
        BigDecimal residual = totalIn.subtract(placed);
        if (residual.signum() > 0) {
            int deepest = -1; BigDecimal max = BigDecimal.valueOf(-1);
            for (int i = 0; i < n; i++) if (alloc[i].compareTo(max) > 0) { max = alloc[i]; deepest = i; }
            if (deepest >= 0) alloc[deepest] = alloc[deepest].add(residual);
        }

        // final per-pool quotes → the allocations the transaction will use
        for (int i = 0; i < n; i++) {
            if (alloc[i].signum() <= 0) continue;
            VirtualCurve.Quote q = quote(pools.get(i), minimaToToken, alloc[i]);
            if (!q.ok) continue;
            r.allocs.add(new Alloc(pools.get(i), q));
            r.totalIn = r.totalIn.add(q.inAmount);
            r.totalOut = r.totalOut.add(q.outAmount);
        }
        r.poolsUsed = r.allocs.size();
        if (r.poolsUsed == 0 || r.totalOut.signum() <= 0) return r;
        r.effPrice = minimaToToken ? r.totalOut.divide(r.totalIn, MC) : r.totalIn.divide(r.totalOut, MC);
        r.ok = true;
        return r;
    }

    private static VirtualCurve.Quote quote(Pool p, boolean minimaToToken, BigDecimal in) {
        return minimaToToken ? VirtualCurve.quoteMtoT(p, in) : VirtualCurve.quoteTtoM(p, in);
    }

    /** Total MINIMA-side depth of the pools that would be routed (for the aggregate-depth display). */
    public static BigDecimal aggregateDepth(List<Pool> pairPools) {
        List<Pool> pools = new ArrayList<>();
        for (Pool p : pairPools) if (p.funded()) pools.add(p);
        if (pools.size() > MAX_POOLS) {
            pools.sort(Comparator.comparing((Pool p) -> p.reserveM).reversed());
            pools = pools.subList(0, MAX_POOLS);
        }
        return VirtualCurve.totalMinima(pools);
    }

    /** Group funded pools by their token id (each group is one routable pair). */
    public static List<List<Pool>> byToken(List<Pool> pools) {
        List<String> order = new ArrayList<>();
        List<List<Pool>> groups = new ArrayList<>();
        for (Pool p : pools) {
            if (!p.funded()) continue;
            int idx = order.indexOf(p.tok);
            if (idx < 0) { order.add(p.tok); List<Pool> g = new ArrayList<>(); g.add(p); groups.add(g); }
            else groups.get(idx).add(p);
        }
        // deepest pair first
        List<Integer> idxs = new ArrayList<>();
        for (int i = 0; i < groups.size(); i++) idxs.add(i);
        Collections.sort(idxs, (a, b) -> VirtualCurve.totalMinima(groups.get(b))
                .compareTo(VirtualCurve.totalMinima(groups.get(a))));
        List<List<Pool>> sorted = new ArrayList<>();
        for (int i : idxs) sorted.add(groups.get(i));
        return sorted;
    }
}
