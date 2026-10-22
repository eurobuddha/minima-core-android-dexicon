package com.eurobuddha.dexicon.resting;
/* Vendored from apks/pandadex app/src/main/java/com/eurobuddha/pandadex/CoinLock.java (PandaDEX 0.4.23).
 * Keep as close to verbatim as possible; only the package, the NodeApi/SignGate imports and the minimal Dexicon
 * glue noted inline differ. Per the family naming rule the Minima dollar token is MxUSD in identifiers too
 * (PandaDEX MXUSD_ID/MXUSD_DP/*Mxusd are MXUSD_ID/MXUSD_DP/*Mxusd here). Re-copy rather than hand-edit when PandaDEX changes. */


import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Process-wide input claims reused from PandaPools CoinLock; JSON coin adapters only. */
public final class CoinLock {

    /** ~3–4 blocks. Long enough to cover build + sign + post, short enough that a lost transaction
     *  frees its coin within a few minutes. */
    private static final long TTL_MS = 3 * 60 * 1000L;

    private static final Map<String, Long> RESERVED = new ConcurrentHashMap<>();
    private static final Set<String> QUEUED = new HashSet<>();

    private CoinLock() {}

    public static synchronized boolean isReserved(String coinid) {
        if (coinid == null) return false;
        if (QUEUED.contains(key(coinid))) return true;
        Long at = RESERVED.get(key(coinid));
        return at != null && (System.currentTimeMillis() - at) < TTL_MS;
    }

    public static void reserve(List<org.json.JSONObject> coins) {
        long now = System.currentTimeMillis();
        for (org.json.JSONObject c : coins) if (c != null && c.has("coinid")) RESERVED.put(key(c.optString("coinid")), now);
    }

    public static void release(List<org.json.JSONObject> coins) {
        for (org.json.JSONObject c : coins) if (c != null && c.has("coinid")) RESERVED.remove(key(c.optString("coinid")));
    }

    /** Claim all inputs atomically before queuing. Selection reservations may expire; a queued
     * or signing chain must retain its inputs until its callback completes. */
    static synchronized boolean claimInputs(List<String> ids) {
        Set<String> unique = new HashSet<>();
        for (String id : ids)
            if (id == null || !unique.add(key(id)) || QUEUED.contains(key(id))) return false;
        QUEUED.addAll(unique);
        return true;
    }

    static synchronized void finishInputs(List<String> ids) {
        long now = System.currentTimeMillis();
        for (String id : ids) {
            QUEUED.remove(key(id));
            // Give the node's mempool view time to catch up after a successful/ambiguous post.
            RESERVED.put(key(id), now);
        }
    }

    private static String key(String id) { return id.toLowerCase(Locale.ROOT); }

    /** Drop entries past their TTL. Token-agnostic on purpose: a scan only sees one token's coins, so
     *  pruning against "what I can see" would wrongly free the other side's live reservations. */
    public static void prune() {
        long now = System.currentTimeMillis();
        RESERVED.entrySet().removeIf(e -> now - e.getValue() >= TTL_MS);
    }
}
