package com.eurobuddha.pandapools;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

/**
 * DEXICON-OWNED SHIM (not vendored): the vendored PandaPools engine talks to the node through PandaPools' {@code NodeApi};
 * Dexicon already owns ONE transport — {@link com.eurobuddha.dexicon.NodeApi}: the process-wide IPC queue, the :nodeipc
 * binder transport and one pairing — so this class presents PandaPools' call surface on top of it.
 *
 * KEPT EXACTLY from apks/pandapools NodeApi (its fund-safety contract):
 *  - the interrupted-write journal: before a fund write (sign / txnsign / txnpost / send / consolidate / tokencreate, not
 *    dryrun) is sent, a marker is committed to prefs {@code pandapools_write_safety}; while any marker is pending no new
 *    fund write is sent ({@link #ERR_WRITE_UNCERTAIN}); a marker is cleared only by its OWN complete, in-size reply;
 *  - a timeout, an incomplete / parked / over-size reply, or a callback that throws on a fund write is reported as
 *    {@link #ERR_WRITE_UNCERTAIN} and leaves the marker in place, until the user's reviewed acknowledgement
 *    ({@link #acknowledgeInterruptedWrite});
 *  - the pool signing revision is re-checked when the command reaches the head of the queue;
 *  - the node's admin-gated FILE service ({@link #file}).
 * The journal checks run INSIDE the queue (Dexicon NodeApi's dispatch-time guard), exactly where PandaPools' dispatch ran
 * them. One difference, on the safe side: Dexicon's NodeApi drops a reply that arrives after its timeout, so a write that
 * timed out keeps its marker until the user resolves it (PandaPools would clear it on a late reply).
 */
public class NodeApi {

    public interface Cb {
        void onResult(JSONObject json);
        void onError(String message);
    }

    public interface PairingListener {
        void onEnabled(boolean enabled);
    }

    public static final String ERR_NOT_ENABLED = com.eurobuddha.dexicon.NodeApi.ERR_NOT_ENABLED;
    public static final String ERR_TOO_LONG = com.eurobuddha.dexicon.NodeApi.ERR_TOO_LONG;
    public static final String ERR_WRITE_UNCERTAIN = "The node has not confirmed the outcome of a previous write. "
            + "It may still complete. New pool signing is paused. Check Activity and use ② Pools › My pools → Resolve interrupted write.";
    private static final String WRITE_PREFS = "pandapools_write_safety";
    /** Dexicon NodeApi's own words for "never sent" — the only errors after which a fund write is known not to have run. */
    private static final String CLOSED = "Node connection closed.";
    private static final long WRITE_TIMEOUT_MS = 180000;   // build + proof-of-work + post is slow on mobile

    private static String activeWrite = "";
    private static volatile boolean failedWriteAcknowledgement;

    private final com.eurobuddha.dexicon.NodeApi node;
    private final boolean owned;
    private final Context mContext;
    private final Handler mMain = new Handler(Looper.getMainLooper());

    /** PandaPools' constructor: a private connection (NodeBackupCapture's backup run), closed by {@link #onDestroy}. */
    public NodeApi(Context ctx, PairingListener pairing) {
        mContext = ctx;
        node = new com.eurobuddha.dexicon.NodeApi(ctx, pairing == null ? null : pairing::onEnabled);
        owned = true;
    }

    /** Dexicon's shared connection (the Activity's or MakerService's); {@link #onDestroy} leaves it open. */
    public NodeApi(com.eurobuddha.dexicon.NodeApi shared) { this(shared, null); }

    /** As {@link #NodeApi(com.eurobuddha.dexicon.NodeApi)}; {@code refuse} names commands that must not leave the phone
     *  (null = none): each gets {@code onError(reason)} instead. Dexicon's per-entry filter for the vendored
     *  CollectSweeper, which takes no filter of its own (see DexiconPools.sweepNode). */
    NodeApi(com.eurobuddha.dexicon.NodeApi shared, java.util.function.Function<String, String> refuse) {
        mContext = shared.context();
        node = shared;
        owned = false;
        this.refuse = refuse;
    }
    private java.util.function.Function<String, String> refuse;

    Context context() { return mContext.getApplicationContext(); }

    static boolean writesFunds(String command) {
        String verb = command == null ? "" : command.trim().split("\\s+", 2)[0];
        return verb.equals("sign") || verb.equals("txnsign") || verb.equals("txnpost") || verb.equals("send")
                || verb.equals("consolidate") || verb.equals("tokencreate");
    }
    private android.content.SharedPreferences writePrefs() {
        return mContext.getApplicationContext().getSharedPreferences(WRITE_PREFS, Context.MODE_PRIVATE);
    }
    private boolean hasPendingWrite() { return failedWriteAcknowledgement || !writePrefs().getString("pending", "").isEmpty(); }
    public boolean hasInterruptedWrite() { return hasPendingWrite() && activeWrite.isEmpty(); }
    /** Only invoked after the user's explicit restart-and-reconcile confirmation. */
    public String interruptedWriteId() { return writePrefs().getString("pending", ""); }
    public boolean acknowledgeInterruptedWrite(String expected) {
        String previous = interruptedWriteId();
        String marker = previous.isEmpty() ? java.util.UUID.randomUUID().toString() : previous;
        return acknowledgeReviewedWrite(expected, previous, activeWrite,
                () -> writePrefs().edit().remove("pending").commit(),
                () -> writePrefs().edit().putString("pending", marker).commit());
    }

    /** UI and node callbacks run on the main Looper. Consent applies only to the reviewed marker. */
    static boolean acknowledgeReviewedWrite(String expected, String current, String active,
                                            java.util.function.BooleanSupplier clear, Runnable rollback) {
        if (expected == null || !expected.equals(current) || !active.isEmpty()) return false;
        return commitWriteAcknowledgement(clear, rollback);
    }

    static boolean commitWriteAcknowledgement(java.util.function.BooleanSupplier clear, Runnable rollback) {
        failedWriteAcknowledgement = true;
        try { if (clear.getAsBoolean()) { failedWriteAcknowledgement = false; return true; } }
        catch (Exception e) { /* keep process latch */ }
        try { rollback.run(); } catch (Exception e) { /* keep process latch */ }
        return false;
    }
    static boolean writeAcknowledgementFailed() { return failedWriteAcknowledgement; }

    /** PandaPools' long-running verbs (Dexicon's defaults would give `backup` and `newaddress` only 30 s). */
    static long timeoutFor(String command) {
        String c = command == null ? "" : command.trim();
        if (c.startsWith("backup ") || writesFunds(c) || c.equals("newaddress") || c.equals(BlockOwnerRecovery.MODE_COMMAND) || c.startsWith("txnsign")
                || c.startsWith("txnpost") || c.startsWith("tokencreate") || c.startsWith("txnbasics")) return WRITE_TIMEOUT_MS;
        return 0;   // Dexicon NodeApi's default for the verb
    }

    static boolean signingRevisionMatches(String command, long revision) {
        boolean sensitive = writesFunds(command) || command.startsWith("newscript trackall:false ")
                || command.startsWith("cointrack enable:false ");
        return !sensitive || revision == OwnPoolStore.signingRevision();
    }

    static boolean isCompleteReply(JSONObject reply) {
        return reply != null && !TxPost.truthy(reply, "pending") && (reply.opt("status") instanceof Boolean
                || Boolean.FALSE.equals(reply.opt("enabled")));
    }

    /** One fund write's journal state, from the head of the queue to its answer. */
    private final class Write {
        final String command, writeId = java.util.UUID.randomUUID().toString();
        final boolean funds;
        final long signingRevision;
        boolean dispatched;
        String refusal;
        Write(String command) {
            this.command = command;
            this.funds = writesFunds(command) && !command.contains("dryrun:true");
            this.signingRevision = OwnPoolStore.signingRevision();
        }
        /** Runs when the command reaches the head of the IPC queue (PandaPools' dispatch()); false = nothing is sent. */
        boolean admit() {
            if (!signingRevisionMatches(command, signingRevision)) { refusal = "Pool signing state changed while this command was queued. Nothing sent; review recovery state and retry."; return false; }
            if (funds) {
                if (hasPendingWrite()) { refusal = ERR_WRITE_UNCERTAIN; return false; }
                // Persist BEFORE dispatch: process death must not silently permit a second signing request.
                if (!writePrefs().edit().putString("pending", writeId).commit()) { refusal = "Could not save the transaction safety marker. Nothing was sent."; return false; }
                activeWrite = writeId;
            }
            dispatched = true;
            return true;
        }
        void settled(boolean complete) {
            if (!funds || !dispatched) return;
            if (writeId.equals(activeWrite)) activeWrite = "";
            if (complete && writeId.equals(writePrefs().getString("pending", ""))) writePrefs().edit().remove("pending").commit();
        }
        void uncertain() {
            if (writeId.equals(activeWrite)) activeWrite = "";
        }
    }

    public void cmd(String command, Cb cb) {
        if (Looper.myLooper() != Looper.getMainLooper()) { mMain.post(() -> cmd(command, cb)); return; }
        if (command == null || command.trim().isEmpty()) { if (cb != null) cb.onError("Empty node command."); return; }
        String refused = refuse == null ? null : refuse.apply(command);
        if (refused != null) { if (cb != null) cb.onError(refused); return; }
        final Write w = new Write(command);
        node.cmd(command, w::admit, timeoutFor(command), new com.eurobuddha.dexicon.NodeApi.Cb() {
            @Override public void onResult(JSONObject reply) {
                // Dexicon's NodeApi delivers only complete, enabled, not-parked, in-size replies here.
                w.settled(isCompleteReply(reply));
                if (cb == null) return;
                try { cb.onResult(reply); }
                catch (RuntimeException callbackFailure) {
                    // A posted write must never become a safe-to-retry failure.
                    if (w.funds) writePrefs().edit().putString("pending", java.util.UUID.randomUUID().toString()).commit();
                    try { cb.onError(w.funds ? ERR_WRITE_UNCERTAIN : "Bad node reply"); } catch (RuntimeException ignored) { }
                }
            }
            @Override public void onError(String message) {
                String m = message;
                if (!w.dispatched) m = w.refusal != null ? w.refusal : message;                    // never left the phone
                else if (ERR_NOT_ENABLED.equals(message)) w.settled(true);                       // the node answered: not run
                else if (w.funds && CLOSED.equals(message)) { w.settled(true); }                 // closed before sending
                else if (w.funds) { w.uncertain(); m = ERR_WRITE_UNCERTAIN; }                     // may have run: keep the marker
                if (cb != null) try { cb.onError(m); } catch (RuntimeException ignored) { }
            }
        });
    }

    /** Existing admin-gated FILE service; bytes travel through content URIs, never Binder payloads. */
    public void file(String action, String path, String newPath, android.net.Uri uri, Cb cb) {
        node.file(action, path, newPath, uri, new com.eurobuddha.dexicon.NodeApi.Cb() {
            @Override public void onResult(JSONObject json) { cb.onResult(json); }
            @Override public void onError(String message) { cb.onError(message); }
        });
    }

    /** An in-flight chain still owns the connection until its final callback (PandaPools' rule): Dexicon's NodeApi
     *  releases only once its queue has drained, and a shared connection stays with its owner. */
    public void onDestroy() { if (owned) node.onDestroy(); }
}
