package com.eurobuddha.pandapools;

import android.content.Context;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * DEXICON-OWNED SHIM (not vendored). In standalone PandaPools this class is its side of the hand-over to Dexicon; the
 * vendored engine asks it two questions, and inside Dexicon they are answered by Dexicon's own rules:
 *
 *  1. {@link #begin} / {@link #active}: may a NEW pool be created or a pool SWAP be sent? Not while Dexicon's trading gate
 *     is shut (one trading app per phone — {@link com.eurobuddha.dexicon.TradingGate#blocked}). Keeping existing pools
 *     alive, recovery, withdrawals and collections never pass through here, so they always continue.
 *  2. {@link #keptByDexicon} / {@link #keepable}: in PandaPools these name the pools Dexicon now keeps, which PandaPools'
 *     keeper must leave alone. Read from Dexicon's side the rule is the same — "another app keeps this pool": a pool
 *     restored from a PandaPools backup stays AWAITING HAND-OVER ({@link DexiconPools#awaiting}) while standalone
 *     PandaPools is still installed and has not handed it over, and Dexicon's keeper (keep-fresh, re-announce, stranding
 *     alarm) leaves it alone. Two keepers must never rebuild the same pool's reserves.
 */
final class Handover {
    private Handover() {}

    static final String BLOCKED = "Trading is off: one trading app per phone (see the first screen). Nothing new is created "
            + "or swapped; your pools are still kept alive, and recovery and withdrawals still work.";

    private static final AtomicInteger RUNNING = new AtomicInteger();

    /** Start a pool create or swap; false (nothing marked) while the trading gate is shut. A true return needs one {@link #end}. */
    static boolean begin(Context c) {
        RUNNING.incrementAndGet();
        if (active(c)) { RUNNING.decrementAndGet(); return false; }
        return true;
    }

    static void end() { RUNNING.decrementAndGet(); }

    static boolean busy() { return RUNNING.get() > 0; }

    /** The gate is shut: no new pool, no swap (re-checked at the last moment before a create posts). */
    static boolean active(Context c) {
        try { return c != null && com.eurobuddha.dexicon.TradingGate.blocked(c); }
        catch (RuntimeException unreadable) { return true; }
    }

    /** Another app (standalone PandaPools) still keeps this pool: Dexicon must not refresh or re-announce it. */
    static boolean keptByDexicon(Context c, String address) { return DexiconPools.keptElsewhere(c, address); }

    /** {@code pools} minus the ones another app still keeps. */
    static List<Pool> keepable(Context c, List<Pool> pools) {
        List<Pool> out = new ArrayList<>();
        for (Pool p : pools) if (p != null && !DexiconPools.keptElsewhere(c, p.address)) out.add(p);
        return out;
    }
}
