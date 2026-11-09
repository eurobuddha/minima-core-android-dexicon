// VENDORED VERBATIM from apks/atomix 0.2.14 app/src/test/java/com/eurobuddha/atomix/swap/SwapVisibilityTest.java by scripts/vendor-atomix.sh — do not edit here; re-vendor.
package com.eurobuddha.atomix.swap;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.eurobuddha.atomix.TradingContext;

import org.junit.Test;

/**
 * The history scoping rule (0.1.58). AtomiX trades ONE currency at a time over a SHARED swap DB, so before
 * this the Activity tab listed both markets at once — switching to MINIMA still showed the dollar history.
 *
 * <p>The rule under test: a swap's own market always shows it; the OTHER market shows it only while it is
 * still actionable (non-terminal AND inside its refund/claim window), so a recoverable leg can never be
 * hidden while a long-dead row stops squatting in the market it doesn't belong to.
 */
public class SwapVisibilityTest {

    private static final long NOW_MS = 1_757_000_000_000L;   // a fixed "now" — never System.currentTimeMillis()
    private static final long NOW_SECS = NOW_MS / 1000L;
    private static final int TIP = 2_400_000;                // a plausible Minima tip

    /** A MINIMA-market swap: sells the Minima leg for ERC20 USDT. */
    private static SwapDb.Swap minimaSwap(String status, long timelock, boolean myLegIsMinima) {
        SwapDb.Swap s = new SwapDb.Swap();
        s.hash = "0x" + "11".repeat(32);
        s.sellToken = "MINIMA"; s.buyToken = "USDT";
        s.status = status; s.myTimelock = timelock; s.myLegIsMinima = myLegIsMinima;
        return s;
    }

    private static SwapDb.Swap mxusdSwap(String status) {
        SwapDb.Swap s = new SwapDb.Swap();
        s.hash = "0x" + "22".repeat(32);
        s.sellToken = "mxUSDT"; s.buyToken = "USDT";
        s.status = status; s.myTimelock = TIP + 100; s.myLegIsMinima = true;
        return s;
    }

    private static boolean visibleInMxusd(SwapDb.Swap s) {
        return SwapVisibility.visibleIn(s, TradingContext.MXUSDT, TIP, NOW_MS);
    }

    // ---- a swap always shows in its OWN market, whatever its state ----

    @Test public void ownMarketAlwaysShows() {
        assertTrue(visibleInMxusd(mxusdSwap(SwapDb.ST_COMPLETE)));
        assertTrue(visibleInMxusd(mxusdSwap(SwapDb.ST_REFUNDED)));
        assertTrue(visibleInMxusd(mxusdSwap(SwapDb.ST_STARTED)));
    }

    @Test public void unattributableRowAlwaysShows() {
        // Neither leg carries a Minima-side label — a legacy row we cannot place. Fail OPEN.
        SwapDb.Swap s = new SwapDb.Swap();
        s.sellToken = "USDT"; s.buyToken = "WETH"; s.status = SwapDb.ST_COMPLETE;
        assertTrue(visibleInMxusd(s));
    }

    // ---- the other market: finished rows belong to THEIR history ----

    @Test public void otherMarketFinishedRowIsHidden() {
        assertFalse(visibleInMxusd(minimaSwap(SwapDb.ST_COMPLETE, TIP + 100, true)));
        assertFalse(visibleInMxusd(minimaSwap(SwapDb.ST_REFUNDED, TIP + 100, true)));
        assertFalse(visibleInMxusd(minimaSwap(SwapDb.ST_ERROR, TIP + 100, true)));
    }

    // ---- the other market: still-actionable rows pierce the filter (FUND SAFETY) ----

    @Test public void otherMarketLiveRowPiercesTheFilter() {
        // An in-flight MINIMA lock while the user is in the dollar market MUST stay on screen — settlement is
        // currency-agnostic and hiding a claimable or refundable leg could cost real funds.
        assertTrue(visibleInMxusd(minimaSwap(SwapDb.ST_STARTED, TIP + 100, true)));
        assertTrue(visibleInMxusd(minimaSwap(SwapDb.ST_LOCKED, TIP + 1, true)));
    }

    @Test public void otherMarketRowJustPastItsTimelockStillShowsThroughTheGrace() {
        // Its refund is being swept right now; it must not blink off screen mid-confirmation.
        assertTrue(visibleInMxusd(minimaSwap(SwapDb.ST_STARTED, TIP - SwapVisibility.GRACE_BLOCKS + 1, true)));
    }

    @Test public void theAugustLockThatNeverWentTerminalIsHidden() {
        // The real case: a 20-Aug MINIMA lock still sitting at "waiting" because its refund never reconciled
        // into the DB. Thousands of blocks past its timelock, it is not actionable from any screen — so it
        // belongs in the MINIMA history, not pinned to the dollar market forever.
        assertFalse(visibleInMxusd(minimaSwap(SwapDb.ST_STARTED, TIP - 40_000, true)));
    }

    // ---- unit handling: block-denominated vs seconds-denominated legs ----

    @Test public void ethereumLegTimelockIsReadAsSecondsNotBlocks() {
        // myLegIsMinima=false → myTimelock is unix SECONDS. Reading it as a block height would compare an
        // epoch against a block count and call every such swap live forever.
        assertTrue(visibleInMxusd(minimaSwap(SwapDb.ST_LOCKED, NOW_SECS + 600, false)));
        assertFalse(visibleInMxusd(minimaSwap(SwapDb.ST_LOCKED, NOW_SECS - SwapVisibility.GRACE_SECS - 1, false)));
        assertTrue(visibleInMxusd(minimaSwap(SwapDb.ST_LOCKED, NOW_SECS - SwapVisibility.GRACE_SECS + 60, false)));
    }

    // ---- every unknown fails OPEN ----

    @Test public void missingTimelockOrUnknownTipShowsTheRow() {
        assertTrue(visibleInMxusd(minimaSwap(SwapDb.ST_STARTED, 0, true)));            // no recorded deadline
        assertTrue(SwapVisibility.visibleIn(                                           // tip not fetched yet
                minimaSwap(SwapDb.ST_STARTED, TIP - 40_000, true), TradingContext.MXUSDT, 0, NOW_MS));
    }

    @Test public void nullActiveMarketShowsEverything() {
        assertTrue(SwapVisibility.visibleIn(minimaSwap(SwapDb.ST_COMPLETE, TIP - 40_000, true), null, TIP, NOW_MS));
    }
}
