package com.eurobuddha.dexicon.resting;
/* Ported from apks/pandadex app/src/test/java/com/eurobuddha/pandadex/PoolCoreTest.java (PandaDEX 0.4.23). */


import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.math.BigDecimal;
import java.util.Arrays;

public class PoolCoreTest {

    private static Pool pool(String m, String t) {
        Pool p = new Pool();
        p.reserveM = new BigDecimal(m);
        p.reserveT = new BigDecimal(t);
        p.tokDecimals = 8;
        p.kmin = "1";
        p.tok = DexContract.MXUSD_ID;
        p.address = "0x" + m.replace(".", "") + t.replace(".", "");
        p.coinidM = p.address + "A";
        p.coinidT = p.address + "B";
        return p;
    }

    @Test public void covenantUsesLiveSentinelDepth() {
        assertEquals("0x50414E4441504F4F4C53", PoolCovenant.SENTINEL);
        assertEquals(1500, PoolCovenant.SENTINEL_SCAN_DEPTH);
    }

    @Test public void kminCanonicalisesTrailingZeros() {
        assertEquals("2", PoolCovenant.kmin(new BigDecimal("20"), new BigDecimal("0.10000000")));
    }

    @Test public void mToTQuoteSatisfiesInvariant() {
        Pool p = pool("1000", "1000");
        VirtualCurve.Quote q = VirtualCurve.quoteMtoT(p, new BigDecimal("10"));
        assertTrue(q.ok);
        BigDecimal lhs = q.newX.subtract(new BigDecimal("0.05")).multiply(q.newY);
        assertTrue(lhs.compareTo(p.reserveM.multiply(p.reserveT)) >= 0);
        assertEquals(0, q.newY.add(q.outAmount).compareTo(p.reserveT));
    }

    @Test public void inverseBuyQuoteReturnsAtLeastRequestedMinima() {
        Pool p = pool("1000", "1000");
        VirtualCurve.Quote q = VirtualCurve.quoteTForMOut(p, new BigDecimal("10"));
        assertTrue(q.ok);
        assertTrue(q.outAmount.compareTo(new BigDecimal("10")) >= 0);
        assertTrue(q.inAmount.stripTrailingZeros().scale() <= 8);
    }

    @Test public void equalPoolsRouteLikeOneDeepPool() {
        PoolRouter.Route r = PoolRouter.route(Arrays.asList(pool("1000000", "1000000"),
                pool("1000000", "1000000"), pool("1000000", "1000000")),
                true, new BigDecimal("30000"));
        VirtualCurve.Quote single = VirtualCurve.quoteMtoT(pool("3000000", "3000000"), new BigDecimal("30000"));
        assertTrue(r.ok);
        BigDecimal rel = r.totalOut.subtract(single.outAmount).abs().divide(single.outAmount,
                new java.math.MathContext(20, java.math.RoundingMode.HALF_UP));
        assertTrue(rel.compareTo(new BigDecimal("0.001")) < 0);
    }

    @Test public void exactMinimaOutCannotSilentlyUnderfillPastAggregateCapacity() {
        PoolRouter.Route r = PoolRouter.routeExactMinimaOut(Arrays.asList(
                pool("1", "1000"),
                pool("1", "1000")
        ), new BigDecimal("2"));
        assertFalse(r.ok);
        assertEquals(0, r.totalOut.compareTo(BigDecimal.ZERO));
        assertTrue(r.allocs.isEmpty());
    }

    @Test public void invalidPoolRouteIsEmpty() {
        assertFalse(PoolRouter.route(null, true, new BigDecimal("1")).ok);
    }
}
