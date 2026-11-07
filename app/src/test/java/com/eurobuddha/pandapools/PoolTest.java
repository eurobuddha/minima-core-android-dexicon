// VENDORED VERBATIM from apks/pandapools 0.9.87 app/src/test/java/com/eurobuddha/pandapools/PoolTest.java by scripts/vendor-pandapools.sh — do not edit here; re-vendor.
package com.eurobuddha.pandapools;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.math.BigDecimal;

/** The Pool POJO's derived values — funded(), reserveAge() (the keep-fresh trigger), spot price, K, feeGrowth. */
public class PoolTest {

    private static Pool funded() {
        Pool p = new Pool();
        p.reserveM = new BigDecimal("200");
        p.reserveT = new BigDecimal("100");
        p.kmin = "20000";
        p.tokDecimals = 8;
        return p;
    }

    @Test public void fundedRequiresBothLegsPositive() {
        assertFalse(new Pool().funded());
        Pool p = new Pool();
        p.reserveM = new BigDecimal("10");
        assertFalse("one leg only is not funded", p.funded());
        p.reserveT = BigDecimal.ZERO;
        assertFalse("zero leg is not funded", p.funded());
        p.reserveT = new BigDecimal("5");
        assertTrue(p.funded());
    }

    @Test public void reserveAgeIsZeroWhenUnknownOrAhead() {
        Pool p = funded();
        assertEquals("unknown reserveBlock -> age 0", 0, p.reserveAge(100000));
        p.reserveBlock = 100;
        assertEquals("tip below reserveBlock -> age 0 (never negative)", 0, p.reserveAge(50));
        assertEquals(900, p.reserveAge(1000));
    }

    @Test public void reserveAgeCrossesTheRefreshThreshold() {
        Pool p = funded();
        p.reserveBlock = 1000;
        // REFRESH_BLOCKS = 1200: below it = don't refresh, above = refresh (keeps it inside the ~1700 cascade)
        assertTrue(p.reserveAge(1000 + PoolRefresher.REFRESH_BLOCKS - 1) <= PoolRefresher.REFRESH_BLOCKS);
        assertTrue(p.reserveAge(1000 + PoolRefresher.REFRESH_BLOCKS + 1) > PoolRefresher.REFRESH_BLOCKS);
        assertTrue("refresh margin sits safely under the ~1700-block cascade", PoolRefresher.REFRESH_BLOCKS < 1700);
    }

    @Test public void spotPriceIsTokenPerMinima() {
        Pool p = funded();   // 100 token / 200 MINIMA = 0.5
        assertEquals(0, p.spotPrice().compareTo(new BigDecimal("0.5")));
        assertEquals(0, new Pool().spotPrice().compareTo(BigDecimal.ZERO));
    }

    @Test public void kIsTheProduct() {
        Pool p = funded();
        assertEquals(0, p.k().compareTo(new BigDecimal("20000")));   // 200 * 100
        assertEquals(0, new Pool().k().compareTo(BigDecimal.ZERO));
    }

    @Test public void feeGrowthIsZeroAtCreationAndGrowsWithK() {
        Pool p = funded();      // k == kmin == 20000 → feeGrowth 0
        assertEquals(0, p.feeGrowth().compareTo(BigDecimal.ZERO));
        p.reserveT = new BigDecimal("110");   // k = 22000, kmin 20000 → +10%
        assertEquals(0, p.feeGrowth().compareTo(new BigDecimal("0.1")));
    }
}
