package com.eurobuddha.dexicon.maker;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

import androidx.core.app.NotificationCompat;

import com.eurobuddha.dexicon.ActivityLog;
import com.eurobuddha.dexicon.DexDb;
import com.eurobuddha.dexicon.MainActivity;
import com.eurobuddha.dexicon.NodeApi;
import com.eurobuddha.dexicon.Notifier;
import com.eurobuddha.dexicon.envelope.Envelope;
import com.eurobuddha.dexicon.envelope.Envelopes;
import com.eurobuddha.dexicon.fill.FillPlan;
import com.eurobuddha.dexicon.fill.FillTxn;
import com.eurobuddha.dexicon.fill.Tokens;
import com.eurobuddha.dexicon.identity.DexIdentity;
import com.eurobuddha.dexicon.identity.Sodium;
import com.eurobuddha.dexicon.relay.HttpTransport;
import com.eurobuddha.dexicon.relay.RelayClient;
import com.eurobuddha.dexicon.relay.RelayStore;

import com.goterl.lazysodium.LazySodium;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The Dexicon background engine — port of mds/dexicon/service.js onto the AtomiX SwapService / PandaDEX
 * DexKeepAliveService foreground-service stack. It is the ONLY inbox consumer and the MAKER side of every fill:
 *   boot      identity from the node seed (vault action:seed; retried while the node is unpaired/locked)
 *   tick/10s  ONE bounded inbox long-poll (worker thread), then on the main thread: for each sealed `take` verify
 *             the taker's transaction against MY order, co-sign, post, reply `taken`/`refused`, republish the
 *             remainder; `taken`/`refused` replies to MY pending takes are recorded as fills
 *   tick/30s  settlement tracker: a fill is done once one of its output coins exists on chain
 *   tick/10s  ACTIVITY tracker (ActivityLog.track) while any trade row can still change: tip, inputs, output coin
 *   tick/60s  maker refresh: re-back orders whose coins moved, relock stale coins, renew expiry
 * A Doze-proof exact alarm ({@link HeartbeatReceiver}) drives a pass when the Handler loop stalls.
 */
public class MakerService extends Service {

    private static final String CH_FG = "dexicon_maker";
    private static final int FG_ID = 7601;
    public static final String ACTION_HEARTBEAT = "com.eurobuddha.dexicon.HEARTBEAT";
    /** Local broadcast (setPackage) telling the Activity that orders / fills changed. */
    public static final String ACTION_CHANGED = "com.eurobuddha.dexicon.CHANGED";
    private static final long TICK_MS = 10_000, HOLD_SECONDS = 8, REREGISTER_GAP_MS = 30_000, LOCK_MS = 180_000;

    private final Handler h = new Handler(Looper.getMainLooper());
    private final ExecutorService net = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "dexicon-svc-net"); t.setDaemon(true); return t; });
    private LazySodium ls;
    private NodeApi node;
    private DexDb db;
    private RelayClient relay;
    private DexIdentity me;
    private MakerEngine engine;
    private boolean started, paired, deriving, ticking;
    private long lastRegisterMs, ticks;
    private final Map<String, Long> coinLocks = new HashMap<>();   // coinid → unix ms the lock expires (3 min)
    private final Set<String> myAddresses = new HashSet<>();
    private String fgText = "";

    @Override public IBinder onBind(Intent i) { return null; }

    @Override public void onCreate() {
        super.onCreate();
        createChannel();
        if (!startForegroundCompat()) { stopSelf(); return; }
        ls = Sodium.get();
        db = new DexDb(this);
        relay = new RelayClient(new HttpTransport());
        node = new NodeApi(getApplicationContext(), en -> { paired = en; if (en) deriveIdentity(); });
        lastRegisterMs = System.currentTimeMillis();
        HeartbeatReceiver.schedule(this);
        started = true;
        h.post(tick);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (started && intent != null && ACTION_HEARTBEAT.equals(intent.getAction())) { acquireTimedWakelock(); h.removeCallbacks(tick); h.post(tick); }
        return START_STICKY;
    }

    private void log(String s) { android.util.Log.i("Dexicon", s); }
    private void changed() { sendBroadcast(new Intent(ACTION_CHANGED).setPackage(getPackageName())); }

    /** Identity from the node seed (vault action:seed). Derived on a worker; deterministic, so the Activity's copy matches. */
    private void deriveIdentity() {
        if (me != null || deriving || node == null) return;
        deriving = true;
        node.cmd("vault action:seed", new NodeApi.Cb() {
            public void onResult(JSONObject j) {
                JSONObject r = j.optJSONObject("response");
                String seed = r == null ? "" : r.optString("seed", r.optString("phrase", ""));
                if (seed.isEmpty()) { deriving = false; log("boot waiting: no seed (vault locked / WRITE not granted?)"); return; }
                net.execute(() -> {
                    try {
                        DexIdentity id = DexIdentity.fromSeed(ls, seed);
                        h.post(() -> { me = id; engine = new MakerEngine(MakerService.this, node, db, relay, me); deriving = false; db.setMeta("publicId", me.publicId()); log("booted — publicId " + me.publicId()); refreshAddresses(null); });
                    } catch (Exception e) { h.post(() -> { deriving = false; log("identity derive failed: " + e); }); }
                });
            }
            public void onError(String m) { deriving = false; log("seed read: " + m); }
        });
    }

    /** Every address that holds one of my coins + the default address: "is this input mine?" for the verifier. */
    private void refreshAddresses(Runnable then) {
        node.cmd("coins relevant:true", new NodeApi.Cb() {
            public void onResult(JSONObject j) { for (JSONObject c : NodeApi.rows(j)) { String a = c.optString("address", ""); if (!a.isEmpty()) myAddresses.add(a.toLowerCase(Locale.ROOT)); } def(); }
            public void onError(String m) { def(); }
            private void def() {
                node.cmd("getaddress", new NodeApi.Cb() {
                    public void onResult(JSONObject j) { JSONObject r = j.optJSONObject("response"); if (r != null && !r.optString("address", "").isEmpty()) myAddresses.add(r.optString("address").toLowerCase(Locale.ROOT)); if (then != null) then.run(); }
                    public void onError(String m) { if (then != null) then.run(); }
                });
            }
        });
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            h.removeCallbacks(this);
            h.postDelayed(this, TICK_MS);
            if (!started || ticking) return;
            if (!paired) {
                long now = System.currentTimeMillis();
                if (now - lastRegisterMs >= REREGISTER_GAP_MS) { lastRegisterMs = now; node.reRegister(); }
                return;
            }
            ActivityLog.track(node, db, MakerService.this::changed);   // BOOK/POOL rows need no relay or identity
            if (me == null) { deriveIdentity(); return; }
            List<String> relays = RelayStore.load(MakerService.this);
            Tokens.setMxUsd(RelayStore.mxusdOverride(MakerService.this));
            relay.setRelays(relays);
            updateForeground(relays.size());
            if (relays.isEmpty()) return;
            ticking = true; ticks++;
            final String pk = me.publicId();
            net.execute(() -> {
                List<Envelope> msgs = relay.pollInbox(pk, (int) HOLD_SECONDS);
                h.post(() -> onInbox(msgs));
            });
        }
    };

    private void onInbox(List<Envelope> msgs) {
        List<JSONObject> takes = new ArrayList<>();
        for (Envelope m : msgs) {
            DexIdentity.Opened o = me.open(m.blob);
            if (o == null) continue;
            JSONObject inner; try { inner = new JSONObject(o.text()); } catch (Exception e) { continue; }
            String k = inner.optString("k", "");
            try { inner.put("_from", o.fromPublicId); } catch (Exception ignored) {}
            if ("take".equals(k)) takes.add(inner);
            else if ("taken".equals(k) || "refused".equals(k)) onReply(inner);
        }
        processTakes(takes, 0, () -> {
            if (ticks % 3 == 0) settleFills();
            if (ticks % 6 == 0) refreshAddresses(() -> engine.refresh((e, n, relocked) -> {
                if (e != null) log("refresh failed: " + e);
                else if (n > 0 || relocked != null) { log("refresh republished " + n + " order(s)" + (relocked != null ? "; relocked stale backing coins " + relocked : "")); changed(); }
                ticking = false;
            }));
            else ticking = false;
        });
    }

    /** A maker answered one of MY takes (app.js inboxLoop). */
    private void onReply(JSONObject inner) {
        String orderId = inner.optString("orderId", "");
        JSONObject pt = db.pending(orderId);
        if (pt == null) return;
        final String actId = pt.optString("act", "");
        final long answeredMs = System.currentTimeMillis();
        if ("taken".equals(inner.optString("k"))) {
            db.setMeta("rest_due", orderId);
            ActivityLog.update(db, actId, a -> a.put("stage", ActivityLog.COSIGNED).put("reason", "")
                    .put("cosignMs", answeredMs - pt.optLong("sent", answeredMs)).put("txpowid", inner.optString("txpowid"))
                    .put("outs", inner.optJSONArray("coins") == null ? new JSONArray() : inner.optJSONArray("coins"))
                    .put("amount", inner.optString("filled", a.optString("amount"))));   // the Activity rests any unfilled remainder of the limit behind this take
            try {
                JSONObject order = pt.getJSONObject("order"), plan = pt.getJSONObject("plan");
                JSONObject f = new JSONObject().put("role", "taker").put("orderId", orderId)
                        .put("side", "sell".equals(order.optString("side")) ? "buy" : "sell").put("price", order.optString("price"))
                        .put("filled", inner.optString("filled", plan.optString("filled"))).put("gave", plan.optString("takerGives")).put("got", plan.optString("makerGives"))
                        .put("txpowid", inner.optString("txpowid")).put("coins", inner.optJSONArray("coins") == null ? new JSONArray() : inner.optJSONArray("coins"))
                        .put("counterparty", order.optString("address")).put("ts", MakerEngine.now()).put("status", "settling");
                if (db.saveFill(f)) Notifier.alert(this, "Filled", ("sell".equals(order.optString("side")) ? "Bought " : "Sold ") + f.optString("filled") + " MINIMA @ " + order.optString("price") + " MxUSD (settling)");
            } catch (Exception ignored) {}
        } else {
            String reason = inner.optString("reason", "refused");
            db.setMeta("last_refusal", reason);
            ActivityLog.update(db, actId, a -> a.put("stage", ActivityLog.REFUSED).put("reason", reason).put("cosignMs", answeredMs - pt.optLong("sent", answeredMs)));
            if (reason.matches("(?i).*(taken|not live|advertised|spent|visible|cover).*")) db.setMeta("retry_due", orderId);   // the Activity re-plans at the next level
            Notifier.alert(this, "Take refused", "The maker refused: " + reason);
        }
        db.removePending(orderId);
        changed();
    }

    private void processTakes(List<JSONObject> takes, int i, Runnable done) {
        if (i >= takes.size()) { done.run(); return; }
        handleTake(takes.get(i), () -> processTakes(takes, i + 1, done));
    }

    private void reply(String toPublicId, JSONObject obj) {
        try {
            String blob = me.seal(toPublicId, obj.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            String raw = Envelopes.msg(me, toPublicId, blob, MakerEngine.now() + 300);
            net.execute(() -> relay.publish(raw));
        } catch (Exception e) { log("reply failed: " + e); }
    }

    private boolean lockCoins(List<String> ids) {
        long t = System.currentTimeMillis();
        for (String id : ids) { Long u = coinLocks.get(id); if (u != null && u > t) return false; }
        for (String id : ids) coinLocks.put(id, t + LOCK_MS);
        return true;
    }
    private void unlockCoins(List<String> ids) { for (String id : ids) coinLocks.remove(id); }

    private void handleTake(JSONObject take, Runnable next) {
        final String taker = take.optString("_from", ""), orderId = take.optString("orderId", ""), hex = take.optString("txn", "");
        if (taker.isEmpty() || hex.isEmpty() || orderId.isEmpty()) { next.run(); return; }
        final MyOrder o = db.myOrder(orderId);
        if (o == null || !o.live()) { reply(taker, refused(orderId, "order not live")); next.run(); return; }
        if (!lockCoins(o.coins)) { reply(taker, refused(orderId, "taken")); next.run(); return; }
        log("take received for " + orderId + " from " + taker + " (" + hex.length() / 2 + " bytes)");
        final long receivedMs = System.currentTimeMillis();
        final List<String> spentByMe = new ArrayList<>(), allIns = new ArrayList<>();
        FillTxn.cosign(node, hex, (ins, outs, hasState) -> {
            FillPlan.Verdict v = FillPlan.verifyAsMaker(ins, outs, hasState, o.asFillOrder(), o.coins, o.remaining, new ArrayList<>(myAddresses));
            if (v.ok) for (com.eurobuddha.dexicon.fill.Coin c : ins) { if (o.coins.contains(c.coinid)) spentByMe.add(c.coinid); allIns.add(c.coinid); }
            return v;
        }, (err, txpowid, verdict, outCoins) -> {
            if (err != null) {
                unlockCoins(o.coins);
                log("take for " + orderId + " refused: " + err);
                reply(taker, refused(orderId, err.replaceFirst("^refused: ", "")));
                next.run(); return;
            }
            log("FILLED " + orderId + " " + verdict.filled + " MINIMA → txpow " + txpowid);
            try {
                JSONObject f = new JSONObject().put("role", "maker").put("orderId", orderId).put("side", o.side).put("price", o.price)
                        .put("filled", verdict.filled).put("received", verdict.received).put("paid", verdict.paid).put("txpowid", txpowid)
                        .put("coins", new JSONArray(outCoins)).put("counterparty", taker).put("ts", MakerEngine.now()).put("status", "settling");
                db.saveFill(f);
                ActivityLog.add(db, new JSONObject().put("id", "maker_" + txpowid).put("route", "FAST").put("role", "maker").put("side", o.side)
                        .put("amount", verdict.filled).put("price", o.price).put("mxusd", "sell".equals(o.side) ? verdict.received : verdict.paid)
                        .put("orderId", orderId).put("counterparty", taker).put("txpowid", txpowid).put("outs", new JSONArray(outCoins)).put("ins", new JSONArray(allIns))
                        .put("ts", receivedMs).put("cosignMs", System.currentTimeMillis() - receivedMs).put("stage", ActivityLog.COSIGNED));
                reply(taker, new JSONObject().put("k", "taken").put("orderId", orderId).put("txpowid", txpowid).put("filled", verdict.filled).put("coins", new JSONArray(outCoins)));
            } catch (Exception ignored) {}
            Notifier.alert(this, "Order filled", ("sell".equals(o.side) ? "Sold " : "Bought ") + verdict.filled + " MINIMA @ " + o.price + " MxUSD (settling)");
            engine.afterFill(orderId, verdict.filled, spentByMe, (e2, upd) -> {
                unlockCoins(o.coins);
                if (e2 != null) log("afterFill: " + e2);
                changed();
                next.run();
            });
        });
    }

    private static JSONObject refused(String orderId, String reason) {
        try { return new JSONObject().put("k", "refused").put("orderId", orderId).put("reason", reason); } catch (Exception e) { return new JSONObject(); }
    }

    /** Settlement tracker: a fill is done once one of its output coins exists on chain (`coins coinid:`). The txpowid
     *  from txnpost is the pre-mining id and never matches the mined block, so it is display-only. */
    private void settleFills() {
        List<JSONObject> fills = db.fills(200);
        settleNext(fills, 0);
    }
    private void settleNext(List<JSONObject> fills, int i) {
        if (i >= fills.size()) return;
        JSONObject f = fills.get(i);
        JSONArray coins = f.optJSONArray("coins");
        String probe = coins != null && coins.length() > 0 ? coins.optString(0, "") : "";
        if (!"settling".equals(f.optString("status")) || probe.isEmpty()) { settleNext(fills, i + 1); return; }
        node.cmd("coins coinid:" + probe, new NodeApi.Cb() {
            public void onResult(JSONObject j) {
                List<JSONObject> rows = NodeApi.rows(j);
                JSONObject c = j.optBoolean("status", false) && !rows.isEmpty() ? rows.get(0) : null;
                try {
                    if (c != null && !c.optString("coinid", "").isEmpty()) { f.put("status", "done"); f.put("block", c.optString("created", "")); db.saveFill(f); changed(); }
                    else if (MakerEngine.now() - f.optLong("ts", 0) > 2 * 3600) { f.put("status", "lost"); db.saveFill(f); changed(); }   // dropped txn (double-spent coin, reorg)
                } catch (Exception ignored) {}
                settleNext(fills, i + 1);
            }
            public void onError(String m) { settleNext(fills, i + 1); }
        });
    }

    // ---- foreground plumbing (PandaDEX DexKeepAliveService) ----

    private void updateForeground(int relays) {
        int live = 0; for (MyOrder o : db.myOrders()) if (o.live()) live++;
        String text = (me == null ? "Deriving identity…" : live + " live order" + (live == 1 ? "" : "s")) + " · " + relays + " relay" + (relays == 1 ? "" : "s");
        if (text.equals(fgText)) return;
        fgText = text;
        try { NotificationManager nm = getSystemService(NotificationManager.class); if (nm != null) nm.notify(FG_ID, fgNotification(text)); } catch (Exception ignored) {}
    }

    private void acquireTimedWakelock() {
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm == null) return;
            PowerManager.WakeLock wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "dexicon:heartbeat");
            wl.setReferenceCounted(false);
            wl.acquire(3 * 60_000);
        } catch (Exception ignored) {}
    }

    @Override public void onTaskRemoved(Intent rootIntent) {
        try { HeartbeatReceiver.schedule(getApplicationContext()); } catch (Exception ignored) {}
        try {
            AlarmManager am = (AlarmManager) getSystemService(ALARM_SERVICE);
            PendingIntent pi = PendingIntent.getForegroundService(getApplicationContext(), 21,
                    new Intent(getApplicationContext(), MakerService.class), PendingIntent.FLAG_ONE_SHOT | PendingIntent.FLAG_IMMUTABLE);
            if (am != null) am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, android.os.SystemClock.elapsedRealtime() + 2000, pi);
        } catch (Exception ignored) {}
        super.onTaskRemoved(rootIntent);
    }

    @Override public void onDestroy() {
        started = false;
        h.removeCallbacksAndMessages(null);
        super.onDestroy();
        if (node != null) { try { node.onDestroy(); } catch (Exception ignored) {} node = null; }
        net.shutdownNow();
    }

    @Override public void onTimeout(int startId) { stopSelf(); }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(new NotificationChannel(CH_FG, "Maker engine", NotificationManager.IMPORTANCE_LOW));
            Notifier.ensureChannels(this);
        }
    }

    private Notification fgNotification(String text) {
        PendingIntent pi = PendingIntent.getActivity(this, 24, new Intent(this, MainActivity.class), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Builder(this, CH_FG)
                .setContentTitle("Dexicon maker")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_sys_upload_done)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
    }

    private boolean startForegroundCompat() {
        try {
            Notification n = fgNotification("Co-signing fills of your resting orders");
            if (Build.VERSION.SDK_INT >= 34) startForeground(FG_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            else if (Build.VERSION.SDK_INT >= 29) startForeground(FG_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            else startForeground(FG_ID, n);
            return true;
        } catch (Exception e) { return false; }
    }
}
