package com.eurobuddha.dexicon;

import android.app.Activity;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.eurobuddha.minimaapi.MinimaAPIListener;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Thin wrapper around the Minima Core native IPC SDK — ported from apks/pandadex NodeApi (lean: no interrupted-write
 * ledger, no wallet-generation session; Dexicon's writes are txnsign/txnpost on temp ids that are always txndelete'd).
 *
 * - Holds the single transport (which auto-registers this app with the node).
 * - Runs node commands and delivers the result back ON THE MAIN THREAD, through one process-wide serial queue,
 *   with a timeout so a missing/stopped node surfaces an error instead of hanging.
 * - Detects the "app not enabled in Minima Core" reply and routes it to a pairing listener.
 */
public class NodeApi {

    public interface Cb {
        void onResult(JSONObject json);
        void onError(String message);
    }
    public interface PairingListener { void onEnabled(boolean enabled); }

    public static final String ERR_NOT_ENABLED = "NOT_ENABLED";
    /** The node dropped an over-256KB reply (it returns a stub instead of the data). */
    public static final String ERR_TOO_LONG = "TOO_LONG";

    private static final long READ_TIMEOUT_MS = 30000;
    private static final long WRITE_TIMEOUT_MS = 180000;   // sign + proof-of-work + post is slow on mobile

    static boolean writesFunds(String command) {
        String verb = command == null ? "" : command.trim().split("\\s+", 2)[0];
        return verb.equals("sign") || verb.equals("txnsign") || verb.equals("txnpost") || verb.equals("send")
                || verb.equals("consolidate") || verb.equals("tokencreate");
    }
    private static long timeoutFor(String command) {
        String c = command == null ? "" : command.trim();
        return (writesFunds(c) || c.startsWith("txnbasics") || c.startsWith("txnimport")) ? WRITE_TIMEOUT_MS : READ_TIMEOUT_MS;
    }

    private NodeTransport mApi;
    private final Handler mMain = new Handler(Looper.getMainLooper());
    private final PairingListener mPairing;
    private final Context mContext;
    private final java.util.HashSet<Runnable> mPending = new java.util.HashSet<>();
    private boolean mReleased = false, destroyRequested = false;
    private Boolean enabled;
    /** True once a node has REPLIED to a register (even "not enabled"); false while nothing has answered. A reply
     *  timeout and a real "disabled" used to look identical to the screen (live on the Fold 2026-10-22). */
    private boolean answered;
    public boolean answered() { return answered; }
    private long lastOk;
    private int consecutiveTimeouts, queuedCommands;
    private static final SerialQueue IPC_QUEUE = new SerialQueue();

    public NodeApi(Context ctx, PairingListener pairing) {
        mContext = ctx;
        mPairing = pairing;
        mApi = new NodeTransport(ctx, zResponse -> {
            final boolean en = zResponse.optBoolean("enabled", false), ok = zResponse.optBoolean("status", false);
            mMain.post(() -> { if (!dead()) { answered = ok; noteEnabled(en); } });
        });
    }

    /** The installed Minima node app this companion pairs with: [package, label]; first found wins. */
    public static String[] nodeApp(Context c) {
        String[][] apps = {{"com.eurobuddha.pandamonium", "Pandamonium"}, {"com.eurobuddha.minimablock", "MinimaBlock"}, {"com.eurobuddha.minimacore", "Minima Core"}};
        for (String[] a : apps) {
            try { c.getPackageManager().getPackageInfo(a[0], 0); return a; } catch (Exception ignored) {}
        }
        return new String[]{"", "your Minima node app"};
    }

    public boolean isEnabled() { return Boolean.TRUE.equals(enabled); }
    public long lastOkMs() { return lastOk; }
    Context context() { return mContext.getApplicationContext(); }

    private void noteEnabled(boolean value) {
        boolean changed = enabled == null || enabled != value;
        enabled = value;
        if (changed && !dead() && mPairing != null) mPairing.onEnabled(value);
    }

    /** Re-send REGISTER (the node may have been restarted). */
    public void reRegister() {
        if (mReleased || destroyRequested || queuedCommands != 0) return;
        enabled = null;
        mApi.onDestroy();
        mApi = new NodeTransport(mContext, reply -> { answered = reply.optBoolean("status", false); noteEnabled(reply.optBoolean("enabled", false)); });
        cmd("__register__", new Cb() {
            public void onResult(JSONObject reply) { answered = true; noteEnabled(reply.optBoolean("enabled", false)); }
            public void onError(String message) { answered = !(message != null && (message.contains("No Minima node replied") || message.contains("Node discovery"))) && answered; noteEnabled(false); }
        });
    }

    private boolean dead() {
        return mContext instanceof Activity && (((Activity) mContext).isFinishing() || ((Activity) mContext).isDestroyed());
    }

    private static boolean isTooLong(JSONObject j) {
        if (j == null || j.optBoolean("status", true)) return false;
        Object r = j.opt("response");
        if (!(r instanceof String)) return false;
        String s = ((String) r).toLowerCase();
        return s.contains("too long") || s.contains("max(256000)");
    }

    public void cmd(String command, Cb cb) { cmd(command, null, cb); }

    /** As {@link #cmd(String, Cb)}, with a guard re-checked when the command reaches the head of the IPC queue: false →
     *  nothing is sent and cb gets an error (the vendored AtomiX engine's "market changed" contract, via comms.NodeApi). */
    public void cmd(String command, java.util.function.BooleanSupplier guard, Cb cb) {
        if (Looper.myLooper() != Looper.getMainLooper()) { mMain.post(() -> cmd(command, guard, cb)); return; }
        String invalid = CommandSafety.failure(command);
        if (invalid != null) { if (cb != null) mMain.post(() -> cb.onError(invalid)); return; }
        if (mReleased) { if (cb != null) mMain.post(() -> cb.onError("Node connection closed.")); return; }
        queuedCommands++;
        IPC_QUEUE.submit(finish -> guarded(guard, command, new Cb() {
            private boolean delivered;
            public void onResult(JSONObject reply) { complete(reply, null); }
            public void onError(String message) { complete(null, message); }
            private void complete(JSONObject reply, String error) {
                if (delivered) return;
                delivered = true;
                try {
                    if (cb != null) { if (error == null) cb.onResult(reply); else cb.onError(error); }
                } catch (RuntimeException callbackFailure) {
                    if (error == null && cb != null) { try { cb.onError("Bad node reply"); } catch (RuntimeException ignored) { } }
                } finally {
                    queuedCommands--;
                    mMain.post(finish);
                    finishDestroy();
                }
            }
        }));
    }

    /** Status-checked variant: ok(reply) only when {@code status:true}; otherwise err(the node's error/message). */
    public void cmdR(String command, Cb cb) {
        cmd(command, new Cb() {
            public void onResult(JSONObject j) {
                if (j.optBoolean("status", false)) { cb.onResult(j); return; }
                String e = j.optString("error", "");
                if (e.isEmpty()) e = j.optString("message", "");
                if (e.isEmpty()) { Object r = j.opt("response"); e = r == null ? "command failed" : String.valueOf(r); }
                cb.onError(e);
            }
            public void onError(String m) { cb.onError(m); }
        });
    }

    /** Run commands in order; stop at the first failure with "verb: reason". */
    public void seq(List<String> cmds, Cb done) {
        final int[] i = {0};
        Runnable[] step = new Runnable[1];
        step[0] = () -> {
            if (i[0] >= cmds.size()) { done.onResult(new JSONObject()); return; }
            final String c = cmds.get(i[0]++);
            cmdR(c, new Cb() {
                public void onResult(JSONObject j) { step[0].run(); }
                public void onError(String m) { done.onError(c.split(" ")[0] + ": " + m); }
            });
        };
        step[0].run();
    }

    /** The reply's {@code response} as a list of objects (an object reply becomes a one-element list). */
    public static List<JSONObject> rows(JSONObject reply) {
        List<JSONObject> out = new ArrayList<>();
        Object r = reply == null ? null : reply.opt("response");
        if (r instanceof JSONArray) { JSONArray a = (JSONArray) r; for (int i = 0; i < a.length(); i++) { JSONObject o = a.optJSONObject(i); if (o != null) out.add(o); } }
        else if (r instanceof JSONObject) out.add((JSONObject) r);
        return out;
    }

    private void guarded(java.util.function.BooleanSupplier guard, String command, Cb cb) {
        boolean ok;
        try { ok = guard == null || guard.getAsBoolean(); } catch (RuntimeException e) { ok = false; }
        if (!ok) { cb.onError("Market changed; refresh and try again. No command was sent."); return; }
        dispatch(command, cb);
    }

    private void dispatch(String command, Cb cb) {
        if (mReleased) { cb.onError("Node connection closed."); return; }
        final boolean funds = writesFunds(command);
        final boolean[] done = {false};
        final Runnable[] ref = new Runnable[1];
        final Runnable timeout = () -> {
            mPending.remove(ref[0]);
            if (done[0]) return;
            done[0] = true;
            if (++consecutiveTimeouts >= 3) noteEnabled(false);
            cb.onError(funds ? "The node has not confirmed the outcome of that write; it may still complete."
                    : "Minima Core didn't respond. Is it installed, running and enabled?");
            finishDestroy();
        };
        ref[0] = timeout;
        mPending.add(timeout);
        mMain.postDelayed(timeout, timeoutFor(command));

        try { mApi.Command(command, new MinimaAPIListener() {
            @Override public void response(JSONObject zResponse) {
                mMain.post(() -> {
                    if (done[0]) return;
                    done[0] = true;
                    mMain.removeCallbacks(timeout);
                    mPending.remove(timeout);
                    try {
                        if (!NodeTransport.isCompleteReply(zResponse)) {
                            cb.onError(zResponse == null ? "Incomplete node reply." : zResponse.optString("transporterror", "Incomplete node reply."));
                            return;
                        }
                        if (!zResponse.optBoolean("enabled", true)) { noteEnabled(false); cb.onError(ERR_NOT_ENABLED); return; }
                        if (isTooLong(zResponse)) { cb.onError(ERR_TOO_LONG); return; }
                        lastOk = System.currentTimeMillis();
                        consecutiveTimeouts = 0;
                        noteEnabled(true);
                        cb.onResult(zResponse);
                    } catch (Throwable t) {
                        try { cb.onError("Bad node reply"); } catch (Throwable ignore) {}
                    } finally { finishDestroy(); }
                });
            }
        }); } catch (RuntimeException dispatchFailure) {
            mMain.removeCallbacks(timeout);
            timeout.run();
        }
    }

    public void onDestroy() { destroyRequested = true; finishDestroy(); }

    private void finishDestroy() {
        if (!destroyRequested || queuedCommands != 0 || !mPending.isEmpty() || mReleased) return;
        mReleased = true;
        if (mApi != null) mApi.onDestroy();
    }
}
