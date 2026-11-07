// VENDORED VERBATIM from apks/atomix 0.2.4 app/src/test/java/com/eurobuddha/atomix/swap/PriceModelGuardTest.java by scripts/vendor-atomix.sh — do not edit here; re-vendor.
package com.eurobuddha.atomix.swap;

import static org.junit.Assert.*;

import com.eurobuddha.atomix.TradingContext;

import org.junit.After;
import org.junit.Test;

/**
 * The currency-switch fund guard (PriceOracle.staleForActive), used at the fetchMexc/fetchParity commit points.
 * A price fetch dispatched for one currency must be DROPPED if the user switched currency while it was in flight,
 * otherwise its mid (MINIMA ~0.02 vs mxUSDT parity 1.0) would be stamped into the shared price static that the
 * OTHER currency's peg reads → a ~50× mispriced auto-fill. There is one parity currency and one MEXC currency,
 * so the price model uniquely identifies the currency.
 */
public class PriceModelGuardTest {

    @After public void restoreDefault() { TradingContext.setActive(TradingContext.MXUSDT, null); }

    @Test public void minimaFetchDroppedWhenActiveIsParity() {
        TradingContext.setActive(TradingContext.MXUSDT, null);      // parity currency active
        assertTrue("a MINIMA (non-parity) result is stale under mxUSDT → drop", PriceOracle.staleForActive(false));
        assertFalse("a parity result matches mxUSDT → keep", PriceOracle.staleForActive(true));
    }

    @Test public void parityFetchDroppedWhenActiveIsMinima() {
        TradingContext.setActive(TradingContext.MINIMA, null);      // MEXC currency active
        assertTrue("a parity result is stale under MINIMA → drop", PriceOracle.staleForActive(true));
        assertFalse("a MINIMA result matches → keep", PriceOracle.staleForActive(false));
    }
}
