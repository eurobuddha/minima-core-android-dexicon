package com.eurobuddha.dexicon;

import com.eurobuddha.atomix.TradingContext;
import com.eurobuddha.atomix.swap.SwapEngine;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * AtomiX's wallet lock for every node-picked spend of a coin AtomiX trades (MINIMA, MxUSD): the node chooses the coins,
 * so it must never pick one an AtomiX swap is in the middle of locking — AtomiX's own consolidate takes
 * {@link SwapEngine#beginConsolidate} for exactly this ("coin selection must be excluded in BOTH directions"). Used by
 * Dexicon's CONSOLIDATE / SPLIT, its automatic pre-trade consolidate, and PandaPools' wallet consolidate (code review,
 * 0.2.23). Other tokens are never held up.
 */
public final class WalletLock {
    private WalletLock() {}

    public static final String BUSY = "Wallet busy — an AtomiX swap is locking coins or a consolidation is running. Nothing was sent; try again in a moment.";

    /** The release to call exactly once when the spend's reply (or error) arrives — or null: refused, send nothing. */
    public static Runnable take(String tokenid) {
        if (!atomixCoin(tokenid)) return () -> {};
        if (!SwapEngine.beginConsolidate()) return null;
        AtomicBoolean done = new AtomicBoolean();
        return () -> { if (done.compareAndSet(false, true)) SwapEngine.endConsolidate(); };   // never twice: a second release could drop another holder's lock
    }

    static boolean atomixCoin(String tokenid) {
        String t = tokenid == null || tokenid.trim().isEmpty() ? "0x00" : tokenid.trim();
        for (TradingContext m : TradingContext.values()) if (m.tokenId.equalsIgnoreCase(t)) return true;
        return false;
    }

    /** The tokenid a node command spends ("… tokenid:0x…"); MINIMA when none is named. */
    public static String tokenOf(String command) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("tokenid:(\\S+)").matcher(command == null ? "" : command);
        return m.find() ? m.group(1) : "0x00";
    }
}
