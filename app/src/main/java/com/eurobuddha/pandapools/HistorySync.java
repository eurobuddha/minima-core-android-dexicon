// VENDORED VERBATIM from apks/pandapools 0.9.94 app/src/main/java/com/eurobuddha/pandapools/HistorySync.java by scripts/vendor-pandapools.sh — do not edit here; re-vendor.
package com.eurobuddha.pandapools;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * One-TxPoW-at-a-time history sync, adapted from the standalone History app.
 * Small pages reduce legacy Binder exposure but do not bound serialized bytes: even one large
 * transaction may overflow the stock node's inline reply transport before an app callback runs.
 * Adaptive skipping only handles replies/errors actually delivered to the app.
 */
public class HistorySync {

    public interface Listener {
        void onProgress(int totalNew);
        void onDone(int totalNew, boolean ok);
    }

    // Keep the smallest possible page. A count of one is not a byte-size guarantee on legacy IPC.
    private static final int  START_MAX = 1;
    private static final long PAGE_DELAY_MS = 450;
    private static final int  MAX_FETCHES = 600;   // safety cap across pages + retries
    private static final int  MAX_SKIP = 3;        // consecutive max:1 failures before giving up

    private final MainActivity act;
    private final HistoryDb db;
    private final Listener listener;

    private boolean running = false;
    private boolean backfill = false;
    /** v2 token-amount repair: rewrite every row the node still retains, instead of skipping ones we hold. */
    private boolean repair = false;
    private int pageMax = START_MAX;
    private int totalNew = 0;
    private int fetches = 0;
    private int skipFails = 0;
    private boolean hadGap;

    public HistorySync(MainActivity act, HistoryDb db, Listener l) { this.act = act; this.db = db; this.listener = l; }

    public boolean isRunning() { return running; }

    public void start() {
        if (running) return;
        running = true;
        repair = "pending".equals(db.getMeta(HistoryDb.META_REPAIR_V2, ""));
        backfill = repair || !"true".equals(db.getMeta("backfill_done", ""));
        pageMax = START_MAX; totalNew = 0; fetches = 0; skipFails = 0; hadGap = false;
        fetchPage(0);
    }

    private void fetchPage(final int offset) {
        if (++fetches > MAX_FETCHES) { finish(false); return; }
        act.node().cmd("history relevant:true max:" + pageMax + " offset:" + offset, new NodeApi.Cb() {
            @Override public void onResult(JSONObject j) {
                // NodeApi reports pairing transitions after validating a successful reply.
                JSONObject r = j.optJSONObject("response");
                JSONArray txpows = r != null ? r.optJSONArray("txpows") : null;
                if (r == null || txpows == null || !j.optBoolean("status", true)) {
                    overLimit(offset);                    // dropped/over-256KB reply → shrink + retry
                    return;
                }
                skipFails = 0;
                JSONArray details = r.optJSONArray("details");
                int got = txpows.length();
                boolean hitKnown = false;
                int pageNew = 0;
                for (int i = 0; i < got; i++) {
                    JSONObject tx = txpows.optJSONObject(i);
                    JSONObject det = (details != null && i < details.length()) ? details.optJSONObject(i) : null;
                    if (tx == null) continue;
                    ActivityLog.observeHistory(act, tx);
                    HistoryEntry e = HistoryEntry.from(tx, det);
                    if (e.txpowid.isEmpty()) continue;
                    // The repair pass must REWRITE rows it already holds (that is the whole point), so it
                    // can't use insert()'s "was this new" answer — and doesn't need to: it runs in backfill
                    // mode, which pages to the end of history rather than stopping at the first known row.
                    if (repair) { db.upsert(e); pageNew++; }
                    else if (db.insert(e)) pageNew++;
                    else hitKnown = true;
                }
                totalNew += pageNew;
                if (pageNew > 0 && listener != null) listener.onProgress(totalNew);

                if (got < pageMax) { if (!hadGap) markBackfillDone(); finish(!hadGap); return; }
                if (!backfill && hitKnown) { finish(true); return; }                // steady state: caught up
                act.ui().postDelayed(() -> fetchPage(offset + got), PAGE_DELAY_MS); // keep paging
            }
            @Override public void onError(String m) {
                if (NodeApi.ERR_NOT_ENABLED.equals(m)) { finish(false); return; }
                overLimit(offset);                        // dropped oversized reply / timeout → shrink + retry
            }
        });
    }

    /** Page was too big (or dropped): halve and retry the same offset; at max:1, skip one giant txpow. */
    private void overLimit(final int offset) {
        if (pageMax > 1) {
            pageMax = Math.max(1, pageMax / 2);
            act.ui().postDelayed(() -> fetchPage(offset), PAGE_DELAY_MS);
        } else if (++skipFails <= MAX_SKIP) {
            hadGap = true; // Keep repair pending: a skipped transaction is not a completed reconciliation.
            // even a single txpow exceeds 256 KB — skip it so it can't stall the whole sync
            act.ui().postDelayed(() -> fetchPage(offset + 1), PAGE_DELAY_MS);
        } else {
            finish(false);
        }
    }

    /** Reached the end of what the node retains: the backfill (and any repair riding on it) is complete. */
    private void markBackfillDone() {
        if (backfill) db.setMeta("backfill_done", "true");
        if (repair) { db.setMeta(HistoryDb.META_REPAIR_V2, "done"); repair = false; }
    }

    private void finish(boolean ok) {
        running = false;
        if (db.getMeta("first_sync_ts", "").isEmpty())
            db.setMeta("first_sync_ts", String.valueOf(System.currentTimeMillis()));
        db.setMeta("synced_tip_block", String.valueOf(act.chainBlock()));
        db.setMeta("last_sync_ts", String.valueOf(System.currentTimeMillis()));
        if (listener != null) listener.onDone(totalNew, ok);
    }
}
