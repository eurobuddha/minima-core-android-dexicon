// VENDORED VERBATIM from apks/pandapools 0.9.86 app/src/main/java/com/eurobuddha/pandapools/PendingCollect.java by scripts/vendor-pandapools.sh — do not edit here; re-vendor.
package com.eurobuddha.pandapools;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Owner payout addresses ($OADR) that still hold funds, tracked until they are actually empty.
 *
 * WHY THIS EXISTS. A close can only pay to $OADR — the covenant pins it. $OADR is a {@code newaddress}
 * key (index >= 64), and a seed-only restore rebuilds ONLY the 64 default keys, so funds left there are
 * invisible and unspendable to a seed-restored wallet. The second hop
 * ({@link PoolManager#forwardOwnerFunds}) exists solely to move them into the default-64 addresses a
 * seed always reproduces.
 *
 * That hop used to be driven by {@code MyLpView.collectAfterClose(oadr, 8)}: eight tries, twenty
 * seconds apart, only while its screen was alive. It gave up after ~160 s — and the coins are not even
 * spendable until three blocks after the close confirms ({@code coinage:3}, ~150 s at 50 s blocks), so
 * the budget was marginal by construction. Worse, one forward that moved *some* coins reported success
 * and stopped the loop.
 *
 * It cost real funds. A pool closed 2026-09-14 paid 585050.29830926161 MINIMA and 2934.95626348 MxUSD
 * to one $OADR; the forward ran while only the MINIMA leg met its filter, moved that, and never came
 * back. The MxUSD is still sitting at
 * {@code 0x0A10CC6952D404ED3DF2C9B3221AE622F35CF788CFBD4EED0E486D9F98CD6675}, and the device that held
 * the owner key has since died — so it cannot be signed for at all.
 *
 * So the goal is now the DESTINATION, not an attempt: an entry lives here until $OADR is empty, retried
 * by the keep-alive pass (which survives app death and Doze), and an address that cannot be signed for
 * is surfaced loudly instead of being retried in silence forever.
 */
final class PendingCollect {

    private static final String PREFS = "pandapools_recovery";
    private static final String PREFIX = "collect_";

    /** What is holding a payout address open. */
    enum Status {
        /** Funds are there and this wallet should be able to move them — keep retrying. */
        RETRYING,
        /** Funds are there and this wallet CANNOT sign for the address. Retrying will never help. */
        STRANDED,
    }

    private PendingCollect() {}

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static String key(String oadr) {
        return PREFIX + (oadr == null ? "" : oadr.toLowerCase(Locale.ROOT));
    }

    /** One tracked payout address. */
    static final class Entry {
        String raw;
        boolean legacy = true;
        final java.util.LinkedHashMap<String,String> transactions = new java.util.LinkedHashMap<>();
        String oadr;
        String status;
        String lastError;
        int attempts;
        long addedAt, lastTryAt;

        Status statusOf() {
            try { return Status.valueOf(status); } catch (Exception unknown) { return Status.RETRYING; }
        }
    }

    /**
     * Start tracking a payout address. Called the moment a close is posted — BEFORE any forward is
     * attempted — so a crash between the close and the forward still leaves the job recorded.
     * {@code commit()} for that reason: a queue entry that never reached disk is a silently dropped job.
     */
    static synchronized boolean add(Context c, String oadr) {
        if (c == null || !FundingCoins.hex(oadr)) return false;
        if (get(c, oadr) != null) return true;                      // already tracked
        try {
            JSONObject o = new JSONObject()
                    .put("oadr", oadr)
                    .put("status", Status.RETRYING.name())
                    .put("attempts", 0)
                    .put("added_at", System.currentTimeMillis())
                    .put("last_try_at", 0)
                    .put("last_error", "");
            return prefs(c).edit().putString(key(oadr), o.toString()).commit();
        } catch (Exception invalid) { return false; }
    }

    static Entry get(Context c, String oadr) {
        if (c == null || oadr == null) return null;
        String raw = prefs(c).getString(key(oadr), null);
        if (raw == null) return null;
        try {
            JSONObject o = new JSONObject(raw);
            Entry e = new Entry();
            e.raw = raw;
            e.oadr = o.optString("oadr", oadr);
            if (!FundingCoins.hex(e.oadr) || !e.oadr.equalsIgnoreCase(oadr)) return null;
            org.json.JSONArray receipts = o.optJSONArray("transactions");
            e.legacy = !Integer.valueOf(1).equals(o.opt("format")) || !Boolean.FALSE.equals(o.opt("legacy"));
            if (receipts == null || receipts.length() == 0) e.legacy = true;
            if (receipts != null) for (int i = 0; i < receipts.length(); i++) {
                JSONObject receipt = receipts.optJSONObject(i);
                String transaction = receipt == null ? "" : receipt.optString("transaction", "");
                String mined = receipt == null ? "" : receipt.optString("mined", "");
                if (!CollectionEvidence.id(transaction) || (!mined.isEmpty() && !CollectionEvidence.id(mined))
                        || e.transactions.put(transaction.toLowerCase(Locale.ROOT), mined) != null) e.legacy = true;
            }
            e.status = o.optString("status", Status.RETRYING.name());
            e.lastError = o.optString("last_error", "");
            e.attempts = o.optInt("attempts", 0);
            e.addedAt = o.optLong("added_at", 0);
            e.lastTryAt = o.optLong("last_try_at", 0);
            return e;
        } catch (Exception invalid) { return null; }
    }

    static List<Entry> all(Context c) {
        List<Entry> out = new ArrayList<>();
        if (c == null) return out;
        for (String k : prefs(c).getAll().keySet()) {
            if (!k.startsWith(PREFIX)) continue;
            Entry e = get(c, k.substring(PREFIX.length()));
            if (e != null) out.add(e);
        }
        return out;
    }

    /** Record an attempt and its outcome. Never removes the entry — only {@link #clear} does that. */
    static synchronized void attempted(Context c, String oadr, Status status, String error) {
        Entry e = get(c, oadr);
        if (c == null || e == null) return;
        try {
            JSONObject o = new JSONObject(e.raw)
                    .put("oadr", e.oadr)
                    .put("status", status.name())
                    .put("attempts", e.attempts == Integer.MAX_VALUE ? e.attempts : e.attempts + 1)
                    .put("added_at", e.addedAt)
                    .put("last_try_at", System.currentTimeMillis())
                    .put("last_error", error == null ? "" : error);
            save(c, oadr, e.raw, o.toString());
        } catch (Exception ignore) { /* the next pass re-reads and retries anyway */ }
    }

    /** Clear only the exact evidence snapshot checked by the current chain/address callbacks. */
    static synchronized boolean clear(Context c, Entry expected) {
        if (c == null || expected == null || expected.legacy || expected.transactions.isEmpty()) return false;
        for (String mined : expected.transactions.values()) if (!CollectionEvidence.id(mined)) return false;
        return save(c, expected.oadr, expected.raw, null);
    }

    /** Commit a change only while its previous snapshot is current. Restore memory on failed commit. */
    private static boolean save(Context c, String address, String before, String after) {
        SharedPreferences store = prefs(c);
        if (!java.util.Objects.equals(before, store.getString(key(address), null))) return false;
        try {
            SharedPreferences.Editor edit = store.edit();
            boolean committed = (after == null ? edit.remove(key(address)) : edit.putString(key(address), after)).commit();
            if (committed) return true;
        } catch (RuntimeException failed) { /* Keep the old job in memory even when commit failed. */ }
        try {
            SharedPreferences.Editor rollback = store.edit();
            if (before == null) rollback.remove(key(address)); else rollback.putString(key(address), before);
            rollback.commit();
        } catch (RuntimeException ignored) { /* The caller must treat this operation as failed. */ }
        return false;
    }

    /** Durable barrier before txnpost. The transaction hash survives the miner changing the TxPoW ID. */
    static synchronized boolean track(Context c, String address, String transaction) {
        if (c == null || !FundingCoins.hex(address) || !CollectionEvidence.id(transaction)) return false;
        try {
            String raw = prefs(c).getString(key(address), null);
            Entry entry = get(c, address);
            if (raw != null && entry == null) return false;
            JSONObject value = raw == null ? new JSONObject().put("oadr", address)
                    .put("status", Status.RETRYING.name()).put("attempts", 0)
                    .put("added_at", System.currentTimeMillis()).put("last_try_at", 0).put("last_error", "")
                    : new JSONObject(raw);
            java.util.LinkedHashMap<String,String> transactions = entry == null
                    ? new java.util.LinkedHashMap<>() : entry.transactions;
            String id = transaction.toLowerCase(Locale.ROOT);
            if (!transactions.containsKey(id)) transactions.put(id, "");
            value.put("format", 1).put("legacy", entry != null && entry.legacy)
                    .put("transactions", receipts(transactions));
            return save(c, address, raw, value.toString());
        } catch (Exception invalid) { return false; }
    }
    private static org.json.JSONArray receipts(java.util.Map<String,String> values) throws org.json.JSONException {
        org.json.JSONArray result = new org.json.JSONArray();
        for (java.util.Map.Entry<String,String> value : values.entrySet())
            result.put(new JSONObject().put("transaction", value.getKey()).put("mined", value.getValue()));
        return result;
    }

    /** Matching an immutable transaction hash identifies a candidate; fresh inclusion is still required. */
    static synchronized void observe(Context c, JSONObject txpow) {
        if (txpow == null || !Boolean.TRUE.equals(txpow.opt("istransaction"))) return;
        String transaction = ActivityLog.transactionId(txpow).toLowerCase(Locale.ROOT);
        String mined = txpow.optString("txpowid", "");
        if (!CollectionEvidence.id(transaction) || !CollectionEvidence.id(mined)) return;
        for (Entry entry : all(c)) {
            if (!entry.transactions.containsKey(transaction) || mined.equals(entry.transactions.get(transaction))) continue;
            try {
                entry.transactions.put(transaction, mined);
                JSONObject value = new JSONObject(entry.raw).put("transactions", receipts(entry.transactions));
                save(c, entry.oadr, entry.raw, value.toString());
            } catch (Exception invalid) { /* Leave the job unresolved. */ }
        }
    }
    static int scanOffset(Context c) {
        try { return Math.max(0, prefs(c).getInt("collection_history_offset", 0)); }
        catch (RuntimeException invalid) { return 0; }
    }
    static void scanOffset(Context c, int offset) {
        prefs(c).edit().putInt("collection_history_offset", offset).apply();
    }

    /** Addresses the user needs to know about: funds present that this wallet cannot move. */
    static List<Entry> stranded(Context c) {
        List<Entry> out = new ArrayList<>();
        for (Entry e : all(c)) if (e.statusOf() == Status.STRANDED) out.add(e);
        return out;
    }
}
