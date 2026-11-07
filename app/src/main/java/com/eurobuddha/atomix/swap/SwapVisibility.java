// VENDORED VERBATIM from apks/atomix 0.2.3 app/src/main/java/com/eurobuddha/atomix/swap/SwapVisibility.java by scripts/vendor-atomix.sh — do not edit here; re-vendor.
package com.eurobuddha.atomix.swap;

import com.eurobuddha.atomix.TradingContext;

/**
 * Which recorded swaps belong on screen in the currently-selected market.
 *
 * <p>AtomiX trades ONE currency at a time, but the swap DB is shared between both — so a history list that
 * simply renders every row shows the dollar market's swaps while you are looking at the Minima market (the
 * defect this class fixes: the combine gave the app two currencies without giving the history reads a scope).
 *
 * <p>The rule is "its own market, always; the other market only while still actionable". A swap in the OTHER
 * currency stays visible across markets while it is non-terminal AND inside its refund/claim window, because
 * settlement is deliberately currency-agnostic ({@link MinimaHtlc}) and hiding a leg you could still recover
 * could cost real funds. Once that window has long closed the row is not actionable from any screen, so it
 * drops back into its own currency's history instead of squatting in the other one forever.
 *
 * <p>Every unknown fails OPEN (shown): an unattributable legacy row, a missing timelock, or an unknown chain
 * tip all render. Showing a row you didn't need costs a line of screen; hiding one you did can cost money.
 *
 * <p>Deliberately Android-free and static so the whole rule is unit-testable (see SwapVisibilityTest).
 */
public final class SwapVisibility {

    /** Grace past a BLOCK-denominated timelock, ~20h at Minima's ~50s blocks. Far longer than the sweep needs
     *  (a ~90s cycle plus 2 confirmations), so a refund landing can never blink the row off screen mid-flight. */
    public static final int GRACE_BLOCKS = 1440;

    /** Grace past a SECONDS-denominated (Ethereum leg) timelock. */
    public static final long GRACE_SECS = 24 * 60 * 60L;

    private SwapVisibility() {}

    /** A finished swap: nothing left to claim or refund. */
    public static boolean isTerminal(SwapDb.Swap s) {
        return s != null && (SwapDb.ST_COMPLETE.equals(s.status)
                || SwapDb.ST_REFUNDED.equals(s.status)
                || SwapDb.ST_ERROR.equals(s.status));
    }

    /**
     * Is my own locked leg still inside its timelock (plus grace)? The units differ by leg and there is no flag
     * in the value itself: {@code myLegIsMinima} means {@code myTimelock} is a Minima BLOCK height, otherwise it
     * is unix SECONDS. Getting this backwards would compare a block count against an epoch, so it is switched
     * explicitly rather than guessed from magnitude.
     */
    public static boolean withinWindow(SwapDb.Swap s, int chainBlock, long nowMs) {
        if (s == null || s.myTimelock <= 0) return true;                    // no known deadline → assume live
        if (s.myLegIsMinima) {
            if (chainBlock <= 0) return true;                               // tip not fetched yet → assume live
            return chainBlock <= s.myTimelock + GRACE_BLOCKS;
        }
        return nowMs / 1000L <= s.myTimelock + GRACE_SECS;
    }

    /** Should this swap appear while {@code active} is the selected market? */
    public static boolean visibleIn(SwapDb.Swap s, TradingContext active, int chainBlock, long nowMs) {
        if (s == null) return false;
        TradingContext own = TradingContext.forSwap(s.sellToken, s.buyToken);
        if (own == null || active == null || own == active) return true;    // its own market, or unattributable
        return !isTerminal(s) && withinWindow(s, chainBlock, nowMs);        // other market → only while actionable
    }
}
