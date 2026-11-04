// VENDORED VERBATIM from apks/pandapools 0.9.80 app/src/test/java/com/eurobuddha/pandapools/PoolCalcTest.java by scripts/vendor-pandapools.sh — do not edit here; re-vendor.
package com.eurobuddha.pandapools;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** The what-if calculator: the constant-product identities for the 100,000 MINIMA / 500 mxUSD reference pool. */
public class PoolCalcTest {

    private static final double X0 = 100000, Y0 = 500, P0 = 0.005;

    @Test public void entryReproducesTheInputs() {
        PoolCalc.Result r = PoolCalc.compute(X0, Y0, P0, 0.5, 0);
        assertEquals(50_000_000.0, r.k, 1e-6);
        assertEquals(P0, r.entryPrice, 1e-15);
        assertEquals(X0, r.minima, 1e-6);
        assertEquals(Y0, r.token, 1e-9);
        assertEquals(200.0, r.ratio, 1e-9);
        assertEquals(1000.0, r.value, 1e-9);
        assertEquals(0.0, r.vsHoldPct, 1e-9);
        assertEquals(0.0, r.kGrowthPct, 1e-9);
        assertEquals(0.0, r.breakEvenVolume, 1e-9);
    }

    @Test public void fourTimesPriceHalvesTheMinimaAndDoublesTheToken() {
        PoolCalc.Result r = PoolCalc.compute(X0, Y0, P0 * 4, 0.5, 0);
        assertEquals(50000.0, r.minima, 1e-6);
        assertEquals(1000.0, r.token, 1e-6);
        assertEquals(50.0, r.ratio, 1e-9);        // 1 / 0.02
        assertEquals(2000.0, r.value, 1e-6);
        assertEquals(2500.0, r.hold, 1e-9);
        assertEquals(-20.0, r.vsHoldPct, 1e-9);
        assertEquals(4.0, r.move, 1e-12);
    }

    @Test public void tenPercentLeftAtAHundredTimesEitherWay() {
        PoolCalc.Result up = PoolCalc.compute(X0, Y0, P0 * 100, 0.5, 0);
        assertEquals(10000.0, up.minima, 1e-6);        // 90 % of the MINIMA is gone at 0.5
        assertEquals(5000.0, up.token, 1e-6);
        PoolCalc.Result dn = PoolCalc.compute(X0, Y0, P0 / 100, 0.5, 0);
        assertEquals(1_000_000.0, dn.minima, 1e-3);    // 90 % of the mxUSD is gone at 0.00005
        assertEquals(50.0, dn.token, 1e-9);
    }

    @Test public void feesAddExactlyTheirValueAtTheCurrentPrice() {
        // 20,000 of volume at 0.5 % keeps 100 mxUSD: pool value = 1,000 + 100, split half/half
        PoolCalc.Result r = PoolCalc.compute(X0, Y0, P0, 0.5, 20000);
        assertEquals(100.0, r.feesKept, 1e-9);
        assertEquals(1100.0, r.value, 1e-9);
        assertEquals(550.0, r.token, 1e-9);
        assertEquals(110000.0, r.minima, 1e-6);
        assertEquals(200.0, r.ratio, 1e-9);             // the ratio never depends on K
        assertEquals(21.0, r.kGrowthPct, 1e-9);         // (1.1)² − 1
        assertEquals(10.0, r.vsHoldPct, 1e-9);
    }

    @Test public void breakEvenVolumeOffsetsTheDivergenceLoss() {
        PoolCalc.Result r = PoolCalc.compute(X0, Y0, P0 * 4, 0.5, 0);
        // loss vs holding = 2,500 − 2,000 = 500 mxUSD → 100,000 of volume at 0.5 %
        assertEquals(100000.0, r.breakEvenVolume, 1e-6);
        PoolCalc.Result at = PoolCalc.compute(X0, Y0, P0 * 4, 0.5, r.breakEvenVolume);
        assertEquals(0.0, at.vsHoldPct, 1e-9);
    }

    @Test public void zeroFeeHasNoBreakEven() {
        PoolCalc.Result r = PoolCalc.compute(X0, Y0, P0 * 2, 0, 5000);
        assertTrue(Double.isNaN(r.breakEvenVolume));
        assertEquals(0.0, r.feesKept, 1e-12);
    }

    @Test public void rejectsNonPositiveInputs() {
        assertNull(PoolCalc.compute(0, Y0, P0, 0.5, 0));
        assertNull(PoolCalc.compute(X0, -1, P0, 0.5, 0));
        assertNull(PoolCalc.compute(X0, Y0, 0, 0.5, 0));
        assertNull(PoolCalc.compute(X0, Y0, Double.NaN, 0.5, 0));
    }

    @Test public void formattersNeverUseExponentsOrTruncate() {
        assertEquals("50,000,000", PoolCalc.fmt(50_000_000));
        assertEquals("316,228", PoolCalc.fmt(316227.766));
        assertEquals("158.11", PoolCalc.fmt(158.1139));
        assertEquals("500", PoolCalc.fmt(500));
        assertEquals("52.5", PoolCalc.fmt(52.5));
        assertEquals("-16", PoolCalc.fmt(-16.0));
        assertEquals("0.0316", PoolCalc.fmt(0.031623));
        assertEquals("0.00000005", PoolCalc.fmt(0.00000005));
        assertEquals("0.005", PoolCalc.fmtPrice(0.005));
        assertEquals("0.0000005", PoolCalc.fmtPrice(0.0000005));
        assertEquals("50", PoolCalc.fmtPrice(50));
        assertEquals("×4", PoolCalc.fmtMove(4));
        assertEquals("÷10", PoolCalc.fmtMove(0.1));
        assertEquals("×1", PoolCalc.fmtMove(1));
    }
}
