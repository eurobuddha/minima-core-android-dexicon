package com.eurobuddha.dexicon.account;

import android.content.Context;

/**
 * Positions an EARLIER Dexicon signed with the NODE's keys: in-flight AtomiX swaps, live DEX orders, owned PandaPools
 * pools. Only the node can sign them, so while any exists Dexicon keeps the node-key behaviour (ADMIN, as before) and
 * does not offer the Trading Account — owner, 9 Nov: "finish before upgrading"; apks/atomix CLAUDE.md: "drain all
 * in-flight swaps before ANY wallet/mode migration". A fresh install has none.
 */
public final class LegacyPositions {
    private LegacyPositions() {}

    private static volatile String cached;
    private static volatile long cachedAt;

    /** Null when there is nothing to finish; else a one-line description. Cached 30 s (reads three local databases). */
    public static String describe(Context c) {
        if (new com.eurobuddha.account.AccountVault(c).everCreated()) return null;   // the account owns everything from here on (never the node)
        long now = System.currentTimeMillis();
        if (now - cachedAt < 30_000L) return cached;
        String d = scan(c.getApplicationContext(), true);
        cached = d; cachedAt = now;
        return d;
    }

    public static boolean exist(Context c) { return describe(c) != null; }

    /** Open positions whoever signed them (for a LOST account: only its own words may settle them). */
    public static String describeAny(Context c) { return scan(c.getApplicationContext(), false); }

    /** Pools recorded under a Trading Account while none is loaded here (a device transfer, a lost account): only the
     *  SAME words may settle them, so setup must be restore-only (never "Create new"). */
    public static int accountPools(Context c) {
        int n = 0;
        try { for (com.eurobuddha.pandapools.Pool p : com.eurobuddha.pandapools.OwnPoolStore.all(c)) if (p.accountOwned && (!p.retired || !p.drained)) n++; }
        catch (RuntimeException unreadable) { return 0; }
        return n;
    }

    public static void invalidate() { cachedAt = 0; }

    /** {@code nodeKeyOnly}: the node-key gate ("finish before upgrading") skips account-owned recipes; everything else
     *  (a lost account's restore, "Move your Trading Account") must see every pool not proven empty, whoever owns it. */
    private static String scan(Context c, boolean nodeKeyOnly) {
        StringBuilder b = new StringBuilder();
        try {
            com.eurobuddha.atomix.swap.SwapDb db = new com.eurobuddha.atomix.swap.SwapDb(c);
            int swaps = db.activeHashes().size();
            // ERROR swaps of the last 14 days: a Minima lock of mine may still need its owner refund (node key) —
            // SwapEngine.finalizeEthLost marks ERROR while the lock "refunds independently at its own timelock".
            long since = System.currentTimeMillis() - 14L * 24 * 3600_000L;
            for (com.eurobuddha.atomix.swap.SwapDb.Swap sw : db.allSwaps())
                if (com.eurobuddha.atomix.swap.SwapDb.ST_ERROR.equals(sw.status) && sw.created > since) swaps++;
            if (swaps > 0) b.append(swaps).append(swaps == 1 ? " swap in flight" : " swaps in flight");
        } catch (RuntimeException unreadable) { b.append("swap history unreadable"); }
        try {
            int live = 0;
            for (com.eurobuddha.dexicon.maker.MyOrder o : new com.eurobuddha.dexicon.DexDb(c).myOrders()) if (o.live()) live++;
            if (live > 0) b.append(b.length() > 0 ? ", " : "").append(live).append(live == 1 ? " live order" : " live orders");
        } catch (RuntimeException unreadable) { b.append(b.length() > 0 ? ", " : "").append("orders unreadable"); }
        try {
            // every node-key recipe not proven empty: live pools AND closed ones whose close may never have landed
            // (a retired recipe is released only once ClosedPoolCheck reads it empty on the node and the MegaMMR)
            int live = 0, closed = 0;
            for (com.eurobuddha.pandapools.Pool p : com.eurobuddha.pandapools.OwnPoolStore.all(c)) {
                if (nodeKeyOnly && p.accountOwned) continue;
                if (!p.retired) live++; else if (!p.drained) closed++;
            }
            if (live > 0) b.append(b.length() > 0 ? ", " : "").append(live).append(live == 1 ? " pool you own" : " pools you own");
            if (closed > 0) b.append(b.length() > 0 ? ", " : "").append(closed).append(closed == 1 ? " closed pool not yet proven empty" : " closed pools not yet proven empty");
        } catch (RuntimeException unreadable) { b.append(b.length() > 0 ? ", " : "").append("pools unreadable"); }
        try {
            String work = com.eurobuddha.pandapools.DexiconPools.poolWorkOpen(c);   // a close posted but not yet confirmed, …
            if (work != null) b.append(b.length() > 0 ? ", " : "").append(work);
        } catch (RuntimeException unreadable) { b.append(b.length() > 0 ? ", " : "").append("pool work unreadable"); }
        return b.length() == 0 ? null : b.toString();
    }
}
