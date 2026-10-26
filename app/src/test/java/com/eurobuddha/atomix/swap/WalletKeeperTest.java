// VENDORED VERBATIM from apks/atomix 0.1.84 app/src/test/java/com/eurobuddha/atomix/swap/WalletKeeperTest.java by scripts/vendor-atomix.sh — do not edit here; re-vendor.
package com.eurobuddha.atomix.swap;

import org.junit.Test;
import static org.junit.Assert.*;

/** Wallet keeper: merge before the safe coin read refuses (live 2026-10-26: 56 MxUSD coins declined every buy). */
public class WalletKeeperTest {
    @Test public void mergesAFragmentedTokenWallet() {
        assertEquals("merge", SwapEngine.keepPlan(56, "21615.6", "0", false));
        assertEquals("merge", SwapEngine.keepPlan(31, "500", "0", false));
        assertNull(SwapEngine.keepPlan(30, "500", "0", false));
    }
    @Test public void minimaHasItsOwnHigherBand() {
        assertNull(SwapEngine.keepPlan(120, "900000", "0", true));
        assertEquals("merge", SwapEngine.keepPlan(121, "900000", "0", true));
    }
    @Test public void splitsASingleCoinButNeverDust() {
        assertEquals("split", SwapEngine.keepPlan(1, "500", "0", false));
        assertNull(SwapEngine.keepPlan(2, "500", "0", false));
        assertNull(SwapEngine.keepPlan(1, "9", "0", false));
        assertNull(SwapEngine.keepPlan(0, "0", "0", false));
    }
    @Test public void neverActsWhileAnythingIsSettling() {
        assertNull(SwapEngine.keepPlan(56, "500", "0.5", false));
        assertNull(SwapEngine.keepPlan(1, "500", "1", false));
    }
    @Test public void malformedBalanceDoesNothing() {
        assertNull(SwapEngine.keepPlan(56, "x", "0", false));
    }
    @Test public void splitStaysBelowTheMergeBand() {
        assertTrue(SwapEngine.KEEP_SPLIT_INTO < SwapEngine.KEEP_MERGE_ABOVE);
        assertTrue(SwapEngine.KEEP_SPLIT_INTO >= SwapEngine.KEEP_SPLIT_BELOW);
    }
}
