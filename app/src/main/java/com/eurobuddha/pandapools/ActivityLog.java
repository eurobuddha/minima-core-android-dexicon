// VENDORED VERBATIM from apks/pandapools 0.9.90 app/src/main/java/com/eurobuddha/pandapools/ActivityLog.java by scripts/vendor-pandapools.sh — do not edit here; re-vendor.
package com.eurobuddha.pandapools;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Local submission receipts. Confirmation requires a successful node `txpow onchain` lookup;
 * neither elapsed blocks since submission nor wall-clock time establishes inclusion. */
public final class ActivityLog {

    public static final String CREATE="CREATE", SWAP="SWAP", DEPOSIT="DEPOSIT", MIGRATE="MIGRATE", CLOSE="CLOSE", CONSOLIDATE="CONSOLIDATE";
    /** Node-reported confirmation depth required for a confirmed receipt. */
    public static final int CONFIRM_BLOCKS = 3;

    private static final String PREFS = "pandapools_activity";
    private static final String KEY = "log";
    private static final int MAX = 60;

    private ActivityLog() {}

    public static final class Entry {
        public final String type, summary, failMsg;
        public String txpowid;
        public String originalTxpowid;
        String transactionId = "";
        public final int submitBlock;
        public final long ts;
        public final boolean failed;
        int verifiedDepth = -1;
        long verifiedAt;
        Entry(String type, String summary, String txpowid, int submitBlock, long ts, boolean failed, String failMsg) {
            this.type=type; this.summary=summary; this.txpowid=txpowid; this.originalTxpowid=txpowid; this.submitBlock=submitBlock;
            this.ts=ts; this.failed=failed && !NodeApi.ERR_WRITE_UNCERTAIN.equals(failMsg); this.failMsg=failMsg;
        }
        /** A local submission is never chain-confirmation evidence. The node history is displayed separately. */
        public boolean confirmed(int chainBlock) { return !failed && verifiedDepth >= CONFIRM_BLOCKS; }
        /** Blocks elapsed since submit (clamped ≥0), or -1 if unknown. */
        public int elapsed(int chainBlock) {
            if (chainBlock <= 0 || submitBlock <= 0) return -1;
            return Math.max(0, chainBlock - submitBlock);
        }
        /** Short human status for a chip. */
        public String statusText(int chainBlock) {
            if (failed) return "Failed";
            if (verifiedDepth >= 0) return confirmationText(verifiedDepth);
            if (NodeApi.ERR_WRITE_UNCERTAIN.equals(failMsg)) return "Outcome unknown · check node history";
            if (!FundingCoins.hex(txpowid)) return "No transaction ID saved";
            if (verifiedAt > 0) return transactionId.isEmpty() ? "Not found on this node" : "Awaiting on-chain inclusion";
            return "Checking on-chain…";
        }
    }

    public static void record(Context c, String type, String summary, String txpowid, int submitBlock) {
        Entry e = new Entry(type, summary, txpowid, submitBlock, System.currentTimeMillis(), false, null);
        e.transactionId = transactionFor(c, txpowid);
        add(c, e);
    }
    public static void recordFailed(Context c, String type, String summary, String failMsg) {
        add(c, new Entry(type, summary, null, 0, System.currentTimeMillis(), true, failMsg));
    }

    private static synchronized void add(Context c, Entry e) {
        if (c == null) return;
        List<Entry> all = list(c);
        all.add(0, e);   // newest first
        while (all.size() > MAX) all.remove(all.size() - 1);
        save(c, all);
    }

    public static synchronized List<Entry> list(Context c) {
        List<Entry> out = new ArrayList<>();
        if (c == null) return out;
        String s = prefs(c).getString(KEY, null);
        if (s == null) return out;
        try {
            JSONArray arr = new JSONArray(s);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                out.add(new Entry(
                        o.optString("ty", ""), o.optString("s", ""), o.has("tx") ? o.optString("tx") : null,
                        o.optInt("b", 0), o.optLong("t", 0), o.optBoolean("f", false),
                        o.has("fm") ? o.optString("fm") : null));
                out.get(out.size() - 1).verifiedDepth = o.optInt("vd", -1);
                out.get(out.size() - 1).verifiedAt = o.optLong("va", 0);
                out.get(out.size() - 1).transactionId = o.optString("tn", "");
                out.get(out.size() - 1).originalTxpowid = o.optString("original", out.get(out.size() - 1).txpowid);
            }
        } catch (Exception ignore) {}
        return out;
    }

    /** The most recent {@code n} entries (newest first) — for the inline strip. */
    public static List<Entry> recent(Context c, int n) {
        List<Entry> all = list(c);
        return all.size() <= n ? all : new ArrayList<>(all.subList(0, n));
    }

    /** True if any non-failed entry has not yet reached CONFIRM_BLOCKS — drives the fast self-poll. */
    public static boolean hasPending(Context c, int chainBlock) { return hasPending(c, chainBlock, null); }

    /** The same rule restricted to one entry type ({@code null} = any). */
    static boolean hasPending(Context c, int chainBlock, String type) {
        for (Entry e : list(c)) if ((type == null || type.equals(e.type)) && !e.failed && !e.confirmed(chainBlock)
                && System.currentTimeMillis() - e.ts < 10 * 60_000L) return true;
        return false;
    }

    private static void save(Context c, List<Entry> all) {
        try {
            JSONArray arr = new JSONArray();
            for (Entry e : all) {
                JSONObject o = new JSONObject();
                o.put("ty", e.type); o.put("s", e.summary);
                if (e.txpowid != null) o.put("tx", e.txpowid);
                if (e.originalTxpowid != null) o.put("original", e.originalTxpowid);
                o.put("vd", e.verifiedDepth); o.put("tn", e.transactionId); o.put("va", e.verifiedAt);
                o.put("b", e.submitBlock); o.put("t", e.ts); o.put("f", e.failed);
                if (e.failMsg != null) o.put("fm", e.failMsg);
                arr.put(o);
            }
            prefs(c).edit().putString(KEY, arr.toString()).apply();
        } catch (Exception ignore) {}
    }

    /** The async miner changes TxPoW id. Match the immutable transaction id from the node's JSON,
     * then verify the mined TxPoW on-chain. UTXO wallet's input/output resolver supplied the workflow;
     * exact transaction-id equality also rules out a conflicting spend to the same output address. */
    static String transactionId(JSONObject txpow) {
        JSONObject body = txpow == null ? null : txpow.optJSONObject("body");
        JSONObject txn = body == null ? null : body.optJSONObject("txn");
        String id = txn == null ? "" : txn.optString("transactionid", "");
        return FundingCoins.hex(id) ? id : "";
    }
    static synchronized void rememberSubmission(Context ctx, JSONObject reply, String postedId) {
        JSONObject txpow = reply == null ? null : reply.optJSONObject("response");
        if (txpow != null && txpow.optJSONObject("txpow") != null) txpow = txpow.optJSONObject("txpow");
        String id = transactionId(txpow);
        if (id.isEmpty()) return;
        try {
            JSONArray previous = new JSONArray(prefs(ctx).getString("postids", "[]"));
            JSONArray next = new JSONArray().put(new JSONObject().put("p", postedId).put("t", id));
            for (int i = 0; i < previous.length() && i < MAX - 1; i++) next.put(previous.get(i));
            prefs(ctx).edit().putString("postids", next.toString()).commit();
        } catch (Exception ignored) { }
    }
    private static String transactionFor(Context ctx, String postedId) {
        if (ctx == null || postedId == null) return "";
        try {
            JSONArray ids = new JSONArray(prefs(ctx).getString("postids", "[]"));
            for (int i = 0; i < ids.length(); i++) {
                JSONObject row = ids.getJSONObject(i);
                if (postedId.equals(row.optString("p"))) return row.optString("t", "");
            }
        } catch (Exception ignored) { }
        return "";
    }
    static synchronized void observeHistory(Context ctx, JSONObject txpow) {
        String transaction = transactionId(txpow);
        String minedId = txpow == null ? "" : txpow.optString("txpowid", "");
        if (transaction.isEmpty() || !FundingCoins.hex(minedId)) return;
        List<Entry> entries = list(ctx); boolean changed = false;
        for (Entry e : entries) if (!e.failed && transaction.equalsIgnoreCase(e.transactionId)
                && !minedId.equalsIgnoreCase(e.txpowid)) {
            e.txpowid = minedId; e.verifiedDepth = -1; changed = true;
        }
        if (changed) save(ctx, entries);
        JSONObject header = txpow.optJSONObject("header");
        long time = header == null ? 0 : header.optLong("timemilli", 0);
        boolean candidate = false;
        for (Entry e : entries) if (!e.failed && e.transactionId.isEmpty()
                && FundingCoins.hex(e.originalTxpowid) && time > 0
                && Math.abs(e.ts - time) <= 5 * 60_000L) { candidate = true; break; }
        if (candidate) {
            Context app = ctx.getApplicationContext();
            // Time only narrows candidates; matching requires the exact reconstructed SHA3 header ID.
            recovery.execute(() -> {
                String original = ReceiptRecovery.submittedId(txpow);
                if (!original.isEmpty()) applyRecovered(app, original, minedId, transaction);
            });
        }
    }

    private static final java.util.concurrent.ExecutorService recovery =
            java.util.concurrent.Executors.newSingleThreadExecutor();

    private static synchronized void applyRecovered(Context ctx, String original, String mined, String transaction) {
        List<Entry> entries = list(ctx); boolean changed = false;
        for (Entry e : entries) if (!e.failed && original.equalsIgnoreCase(e.originalTxpowid)
                && !mined.equalsIgnoreCase(e.txpowid)) {
            e.txpowid = mined; e.transactionId = transaction;
            e.verifiedDepth = -1; e.verifiedAt = 0; changed = true;
        }
        if (changed) {
            save(ctx, entries);
            android.util.Log.i("PandaPoolsChain", "Recovered receipt=" + original + " mined=" + mined);
        }
    }

    /** Reconcile receipts with history fetched before the receipt existed (UTXO stored resolver). */
    private static synchronized void resolveStored(Context ctx, HistoryDb db) {
        List<Entry> entries = list(ctx); boolean changed = false;
        for (Entry e : entries) {
            if (e.failed) continue;
            if (e.transactionId.isEmpty()) e.transactionId = transactionFor(ctx, e.txpowid);
            String mined = db.minedIdFor(e.transactionId);
            if (!mined.isEmpty() && !mined.equalsIgnoreCase(e.txpowid)) {
                e.txpowid = mined; e.verifiedDepth = -1; e.verifiedAt = 0; changed = true;
            }
        }
        if (changed) save(ctx, entries);
    }

    static String confirmationText(int depth) {
        if (depth < 0) return "Not found on-chain";
        return depth + (depth == 1 ? " confirmation" : " confirmations") + " · on-chain";
    }

    private static boolean checking;
    private static int cursor, historyCursor, publicCursor;
    private static int visibleCursor;
    private static List<String> visibleIds = new ArrayList<>();
    private static long lastCheck;
    private static String checkError = "";

    static String checkStatus() {
        if (!checkError.isEmpty()) return "Node check failed: " + checkError;
        return checking ? "Checking confirmations with the node…" : "";
    }

    /** The active Activity view supplies its displayed rows so these receive prompt proof checks. */
    static void visibleTransactions(List<String> ids) {
        java.util.LinkedHashSet<String> unique = new java.util.LinkedHashSet<>();
        for (String id : ids) if (FundingCoins.hex(id)) unique.add(id);
        visibleIds = new ArrayList<>(unique);
        if (visibleCursor >= visibleIds.size()) visibleCursor = 0;
    }

    /** Small, serial node lookups for receipts AND cached history. Never estimate depth from time. */
    static void verify(Context ctx, NodeApi node, Runnable done) {
        long now = android.os.SystemClock.elapsedRealtime();
        if (checking || (lastCheck != 0 && now - lastCheck < 10_000)) { done.run(); return; }
        Context app = ctx.getApplicationContext();
        HistoryDb db = new HistoryDb(app);
        resolveStored(app, db);
        List<String> localIds = new ArrayList<>();
        for (Entry e : list(app)) if (!e.failed && FundingCoins.hex(e.txpowid)) localIds.add(e.txpowid);
        java.util.LinkedHashSet<String> batch = new java.util.LinkedHashSet<>();
        for (int i = 0; i < Math.min(8, visibleIds.size()); i++)
            batch.add(visibleIds.get((visibleCursor + i) % visibleIds.size()));
        if (!visibleIds.isEmpty()) visibleCursor = (visibleCursor + Math.min(8, visibleIds.size())) % visibleIds.size();
        for (int i = 0; i < Math.min(8, localIds.size()); i++)
            batch.add(localIds.get((cursor + i) % localIds.size()));
        if (!localIds.isEmpty()) cursor = (cursor + Math.min(8, localIds.size())) % localIds.size();
        // Rotate across the entire store, not only its first 150 rows. Otherwise older visible
        // transactions can say "checking" forever without ever entering a verification batch.
        int count = db.count();
        if (historyCursor >= count) historyCursor = 0;
        for (HistoryEntry row : db.list(3, 0, null)) batch.add(row.txpowid);
        List<HistoryEntry> history = db.list(8, historyCursor, null);
        for (HistoryEntry row : history) batch.add(row.txpowid);
        historyCursor = count == 0 ? 0 : (historyCursor + history.size()) % count;
        int publicCount = db.publicCount();
        if (publicCursor >= publicCount) publicCursor = 0;
        for (HistoryEntry row : db.publicList(3, 0)) batch.add(row.txpowid);
        List<HistoryEntry> publicRows = db.publicList(8, publicCursor);
        for (HistoryEntry row : publicRows) batch.add(row.txpowid);
        publicCursor = publicCount == 0 ? 0 : (publicCursor + publicRows.size()) % publicCount;
        batch.removeIf(id -> !FundingCoins.hex(id));
        if (batch.isEmpty()) { db.close(); done.run(); return; }
        checking = true; checkError = ""; lastCheck = now;
        verifyNext(app, node, db, new ArrayList<>(batch), 0, () -> {
            db.close(); checking = false; done.run();
        });
    }
    private static void verifyNext(Context ctx, NodeApi node, HistoryDb db, List<String> batch, int i, Runnable done) {
        if (i == batch.size()) { done.run(); return; }
        String id = batch.get(i);
        node.cmd("txpow onchain:" + id, new NodeApi.Cb() {
            public void onResult(JSONObject reply) {
                JSONObject r = reply == null ? null : reply.optJSONObject("response");
                if (TxPost.truthy(reply, "status") && r != null && r.has("found")) {
                    int depth = confirmationDepth(reply); long at = System.currentTimeMillis();
                    setDepth(ctx, id, depth, at); db.setConfirmation(id, depth, at);
                    android.util.Log.d("PandaPoolsChain", "txpow=" + id + " found=" + r.opt("found")
                            + " confirmations=" + r.opt("confirmations") + " block=" + r.opt("block")
                            + " tip=" + r.opt("tip"));
                } else checkError = "No confirmation evidence returned.";
                verifyNext(ctx, node, db, batch, i + 1, done);
            }
            public void onError(String message) {
                checkError = message == null ? "Node unavailable." : message;
                // Avoid a timeout for every remaining row when the transport/node is unavailable.
                done.run();
            }
        });
    }
    static int confirmationDepth(JSONObject reply) {
        JSONObject r = reply == null ? null : reply.optJSONObject("response");
        if (!TxPost.truthy(reply, "status") || !TxPost.truthy(r, "found")) return -1;
        try {
            int depth = new java.math.BigDecimal(r.get("confirmations").toString()).intValueExact();
            return depth < 0 ? -1 : depth;
        } catch (Exception invalid) { return -1; }
    }
    private static synchronized void setDepth(Context ctx, String id, int depth, long at) {
        List<Entry> entries = list(ctx);
        for (Entry e : entries) if (id.equalsIgnoreCase(e.txpowid)) { e.verifiedDepth = depth; e.verifiedAt = at; }
        save(ctx, entries);
    }

    private static SharedPreferences prefs(Context c) { return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE); }
}
