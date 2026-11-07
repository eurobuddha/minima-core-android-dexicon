// VENDORED VERBATIM from apks/pandapools 0.9.83 app/src/test/java/com/eurobuddha/pandapools/PoolRouterTest.java by scripts/vendor-pandapools.sh — do not edit here; re-vendor.
package com.eurobuddha.pandapools;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Multi-pool water-filling router: N equal pools must trade like one pool of the summed reserves. */
public class PoolRouterTest {

    private static Pool pool(String m, String t, String tok) {
        Pool p = new Pool();
        p.reserveM = new BigDecimal(m);
        p.reserveT = new BigDecimal(t);
        p.tokDecimals = 8;
        p.kmin = "1";
        p.tok = tok;
        p.address = "0x" + m + t + tok;   // unique-ish
        return p;
    }

    @Test public void threeEqualPoolsTradeLikeOneDeepPool() {
        // 3 pools of 1,000,000 / 1,000,000 vs one pool of 3,000,000 / 3,000,000, same 30,000 MINIMA buy.
        List<Pool> three = Arrays.asList(
                pool("1000000", "1000000", "0xTOK"),
                pool("1000000", "1000000", "0xTOK"),
                pool("1000000", "1000000", "0xTOK"));
        Pool one = pool("3000000", "3000000", "0xTOK");

        BigDecimal in = new BigDecimal("30000");
        PoolRouter.Route r = PoolRouter.route(three, true, in);
        VirtualCurve.Quote single = VirtualCurve.quoteMtoT(one, in);

        assertTrue(r.ok);
        assertTrue(single.ok);
        assertEquals(3, r.poolsUsed);

        // fragmentation cost must be negligible: |split - single| / single < 0.1%
        BigDecimal rel = r.totalOut.subtract(single.outAmount).abs()
                .divide(single.outAmount, new MathContext(20, RoundingMode.HALF_UP));
        assertTrue("water-filling equal pools ~= one deep pool (rel diff " + rel + ")",
                rel.compareTo(new BigDecimal("0.001")) < 0);
    }

    @Test public void routingIsNeverMateriallyWorseThanTheBestSinglePool() {
        // Two pools at DIFFERENT spot prices — the case equal-price splitting can't cover. Optimal water-filling
        // can always at least match putting everything in the best pool, so the routed output must be >= the best
        // single-pool output (allowing a tiny discretisation slack). This catches a router that mis-allocates to
        // the worse pool. (Whether BOTH pools are used depends on trade size; when one price dominates, the
        // optimum correctly uses just that one, so we don't assert a pool count here.)
        Pool a = pool("1000000", "1000000", "0xTOK");     // price 1.0
        Pool b = pool("1000000", "1100000", "0xTOK");     // price 1.1
        BigDecimal in = new BigDecimal("200000");
        PoolRouter.Route routed = PoolRouter.route(Arrays.asList(a, b), true, in);
        BigDecimal best = VirtualCurve.quoteMtoT(a, in).outAmount.max(VirtualCurve.quoteMtoT(b, in).outAmount);
        assertTrue(routed.ok);
        assertTrue("routed " + routed.totalOut + " >= best single " + best + " (within discretisation slack)",
                routed.totalOut.compareTo(best.multiply(new BigDecimal("0.999"))) >= 0);
    }

    @Test public void closePricesUseBothPoolsAndBeatEither() {
        // When two pools are close in price and the trade is large relative to each, the aggregate genuinely
        // beats either alone (less slippage) — and both get used.
        Pool a = pool("1000000", "1000000", "0xTOK");
        Pool b = pool("1000000", "1010000", "0xTOK");
        BigDecimal in = new BigDecimal("300000");
        PoolRouter.Route routed = PoolRouter.route(Arrays.asList(a, b), true, in);
        assertTrue(routed.ok);
        assertEquals("both pools used", 2, routed.poolsUsed);
        BigDecimal best = VirtualCurve.quoteMtoT(a, in).outAmount.max(VirtualCurve.quoteMtoT(b, in).outAmount);
        assertTrue("aggregate beats the best single pool", routed.totalOut.compareTo(best) > 0);
    }

    @Test public void capKeepsTheSixDEEPESTpools() {
        // 8 pools with distinct MINIMA depth 100k..800k; the 6 routed must be the 6 largest (300k..800k).
        List<Pool> pools = new ArrayList<>();
        for (int i = 1; i <= 8; i++) pools.add(pool((i * 100000) + "", (i * 100000) + "", "0xTOK"));
        PoolRouter.Route r = PoolRouter.route(pools, true, new BigDecimal("20000"));
        assertTrue(r.ok);
        assertTrue(r.capped);
        java.util.Set<BigDecimal> routedDepths = new java.util.HashSet<>();
        for (PoolRouter.Alloc a : r.allocs) routedDepths.add(a.pool.reserveM);
        // none of the routed pools may be shallower than the 7th-deepest (200k) — i.e. the two shallowest are excluded
        for (BigDecimal d : routedDepths)
            assertTrue("routed a shallow pool " + d, d.compareTo(new BigDecimal("300000")) >= 0);
    }

    @Test public void routeConservesTheMinimaInput() {
        List<Pool> pools = Arrays.asList(
                pool("500000", "500000", "0xTOK"),
                pool("1500000", "1500000", "0xTOK"));
        BigDecimal in = new BigDecimal("12345");
        PoolRouter.Route r = PoolRouter.route(pools, true, in);
        assertTrue(r.ok);
        // MINIMA side isn't grain-clamped, so the allocations sum EXACTLY to the input
        assertEquals(0, r.totalIn.compareTo(in));
    }

    @Test public void capsAtMaxPoolsAndPrefersTheDeepest() {
        List<Pool> pools = new ArrayList<>();
        for (int i = 1; i <= 8; i++) pools.add(pool((i * 100000) + "", (i * 100000) + "", "0xTOK"));
        PoolRouter.Route r = PoolRouter.route(pools, true, new BigDecimal("5000"));
        assertTrue(r.ok);
        assertEquals(8, r.poolsAvailable);
        assertTrue("only MAX_POOLS legs are used", r.poolsUsed <= PoolRouter.MAX_POOLS);
        assertTrue("capped flag set", r.capped);
        // every pair pool is still recorded for funding-exclusion, even the un-routed ones
        assertEquals(8, r.pairAddresses.size());
    }

    @Test public void byTokenGroupsEachPair() {
        List<Pool> pools = Arrays.asList(
                pool("100", "100", "0xAAA"),
                pool("200", "200", "0xBBB"),
                pool("300", "300", "0xAAA"));
        List<List<Pool>> groups = PoolRouter.byToken(pools);
        assertEquals(2, groups.size());
        // deepest pair first: 0xAAA has 100+300=400 MINIMA depth vs 0xBBB 200
        assertEquals("0xAAA", groups.get(0).get(0).tok);
    }

    @Test public void invalidInputsAreNotOk() {
        assertFalse(PoolRouter.route(null, true, new BigDecimal("10")).ok);
        assertFalse(PoolRouter.route(new ArrayList<>(), true, new BigDecimal("10")).ok);
        assertFalse(PoolRouter.route(Arrays.asList(pool("100", "100", "0xTOK")), true, BigDecimal.ZERO).ok);
        // an unfunded pool contributes nothing
        assertFalse(PoolRouter.route(Arrays.asList(new Pool()), true, new BigDecimal("10")).ok);
    }
}
