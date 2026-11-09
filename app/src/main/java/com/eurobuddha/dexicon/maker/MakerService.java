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
import com.eurobuddha.dexicon.identity.SeedSource;
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
 *   boot      identity from seedrandom (SeedSource: the phrase never enters the app; retried while unpaired/locked)
 *   tick/10s  ONE bounded inbox long-poll (worker thread), then on the main thread: for each sealed `take` verify
 *             the taker's transaction against MY order, co-sign, post, reply `taken`/`refused`, republish the
 *             remainder; `taken`/`refused` replies to MY pending takes are recorded as fills
 *   tick/30s  settlement tracker: a fill is done once one of its output coins exists on chain
 *   tick/10s  ACTIVITY tracker (ActivityLog.track) while any trade row can still change: tip, inputs, output coin
 *   tick/60s  maker refresh: re-back orders whose coins moved, relock stale coins, renew expiry
 *   tick/5min ② POOLS keep-alive (vendored PandaPools, DexiconPools.keepAlivePass): recreate my aging pool reserves,
 *             stranding alarm, re-announce, finish collections — runs while the trading gate is shut too (settlement)
 * A Doze-proof exact alarm ({@link HeartbeatReceiver}) drives a pass when the Handler loop stalls.
 */
public class MakerService extends Service {

    private static final String CH_FG = "dexicon_maker";
    private static final int FG_ID = 7601;
    public static final String ACTION_HEARTBEAT = "com.eurobuddha.dexicon.HEARTBEAT";
    /** SAVE / STOP on the market maker: reconcile the ladder NOW, not after the inbox long-poll comes back. */
    public static final String ACTION_LADDER = "com.eurobuddha.dexicon.LADDER";
    /** Local broadcast (setPackage) telling the Activity that orders / fills changed. */
    public static final String ACTION_CHANGED = "com.eurobuddha.dexicon.CHANGED";
    private static final long TICK_MS = 10_000, HOLD_SECONDS = 8, REREGISTER_GAP_MS = 30_000, LOCK_MS = 180_000;

    private final Handler h = new Handler(Looper.getMainLooper());
    private final ExecutorService net = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "dexicon-svc-net"); t.setDaemon(true); return t; });
    private LazySodium ls;
    NodeApi node;
    DexDb db;
    RelayClient relay;
    DexIdentity me;
    MakerEngine engine;
    private LadderRunner ladder;   // the fast market maker (ORDERS › MARKET MAKER)
    private AutoCross cross;       // trades PandaPools back to the ladder's rungs
    boolean started, ticking;   // package-private for tests
    private boolean paired, deriving;
    private long lastRegisterMs, ticks, doorTicks, lastHubMs, lastRefreshMs;
    /** The order refresh (and so the heartbeat republish) runs at most this often while awake. */
    private static final long REFRESH_GAP_MS = 60_000;
    /** The trading gate's MiniHub half is re-read here too, so it holds with the Activity closed (an upgrade restarts
     *  only this service). */
    static final long HUB_EVERY_MS = 5 * 60_000;
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
        unpairedSince = System.currentTimeMillis();
        node = new NodeApi(getApplicationContext(), en -> {
            paired = en;
            if (en) { lastHubMs = 0; deriveIdentity(); unpairedSince = 0; lastOfflineAlertMs = 0; cancelOfflineAlert(); }
            else if (unpairedSince == 0) unpairedSince = System.currentTimeMillis();
        });
        lastRegisterMs = System.currentTimeMillis();
        HeartbeatReceiver.schedule(this);
        started = true;
        h.post(tick);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (started && intent != null && ACTION_HEARTBEAT.equals(intent.getAction())) { acquireTimedWakelock(); h.removeCallbacks(tick); h.post(tick); }
        if (started && intent != null && ACTION_LADDER.equals(intent.getAction())) h.post(this::kickLadder);
        // ② Pools: a stranding notification's Re-publish (relayed by the pandapools.PoolKeepAliveService shim)
        if (started && intent != null && com.eurobuddha.pandapools.PoolKeepAliveService.ACTION_DEXICON_REPUBLISH.equals(intent.getAction())) {
            final String address = intent.getStringExtra(com.eurobuddha.pandapools.PoolKeepAliveService.EXTRA_ADDRESS);
            acquireTimedWakelock();
            // Persisted first: a just-(re)created service is not paired yet, and the tap must not be lost — the first
            // paired tick runs it (runPendingRepublish).
            queueRepublish(address);
            h.post(() -> { if (paired) runPendingRepublish(); });
        }
        return START_STICKY;
    }

    private void log(String s) { android.util.Log.i("Dexicon", s); }
    private void changed() { sendBroadcast(new Intent(ACTION_CHANGED).setPackage(getPackageName())); }

    /** Identity from a one-way node derivation (SeedSource: the phrase never enters the app). Deterministic, so the
     *  Activity's copy matches. */
    private void deriveIdentity() {
        if (me != null || deriving || node == null) return;
        deriving = true;
        SeedSource.derive(node, SeedSource.DEX, new SeedSource.Cb() {
            public void got(String seed) {
                net.execute(() -> {
                    try { DexIdentity id = DexIdentity.fromSeed(ls, seed); h.post(() -> migrateThenBoot(id)); }
                    catch (Exception e) { h.post(() -> { deriving = false; log("identity derive failed: " + e); }); }
                });
            }
            public void failed(String why) { deriving = false; log("boot waiting: " + why); }
        });
    }

    /** One time: orders the old (vault-seed) identity signed are cancelled with that identity, so no taker keeps messaging
     *  a key nobody answers; then the new identity boots and the ladder reposts. The vault is never read again. */
    private void migrateThenBoot(DexIdentity id) {
        String old = db.meta("publicId", "");
        List<MyOrder> live = new ArrayList<>(); for (MyOrder o : db.myOrders()) if (o.live()) live.add(o);
        if (old.isEmpty() || old.equalsIgnoreCase(id.publicId()) || live.isEmpty()) { boot(id); return; }
        log("identity moves off the seed phrase — retiring " + live.size() + " order(s) signed by " + old);
        SeedSource.legacy(node, new SeedSource.Cb() {
            public void got(String seed) {
                net.execute(() -> {
                    DexIdentity was; try { was = DexIdentity.fromSeed(ls, seed); } catch (Exception e) { was = null; }
                    final DexIdentity w = was;
                    h.post(() -> {
                        if (w == null || !old.equalsIgnoreCase(w.publicId())) { boot(id); return; }
                        MakerEngine retire = new MakerEngine(MakerService.this, node, db, relay, w);
                        cancelEach(retire, live, 0, () -> boot(id));
                    });
                });
            }
            public void failed(String why) { log("old orders not cancelled (" + why + ") — they expire on their own"); boot(id); }
        });
    }

    private void cancelEach(MakerEngine e, List<MyOrder> os, int i, Runnable done) {
        if (i >= os.size()) { done.run(); return; }
        e.cancel(os.get(i).orderId, (err, o) -> h.post(() -> cancelEach(e, os, i + 1, done)));
    }

    private void boot(DexIdentity id) {
        me = id; engine = new MakerEngine(MakerService.this, node, db, relay, me); ladder = new LadderRunner(engine, db, line -> { log(line); changed(); });
        cross = new AutoCross(node, db, MakerService.this::changed); deriving = false; db.setMeta("publicId", me.publicId());
        log("booted — publicId " + me.publicId()); refreshAddresses(null);
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

    final Runnable tick = new Runnable() {
        @Override public void run() {
            h.removeCallbacks(this);
            h.postDelayed(this, TICK_MS);
            if (!started) return;
            // DOORS settlement (AtomiX taker engine): claims, ETH withdraws, refunds and forwards, ~every 90 s, app closed or
            // not — and paired or not: unpaired it still refunds/withdraws the ETH legs, which need only Ethereum.
            // Between polls, AtomiX's ~30 s maker discovery (buy requests to my market, OTC messages).
            long door = doorTicks++;
            if (door % 9 == 0) try {
                com.eurobuddha.dexicon.doors.DoorHost dh = com.eurobuddha.dexicon.doors.DoorHost.get(MakerService.this);
                if (paired) dh.poll(); else dh.pollEthOnly();
            } catch (RuntimeException e) { log("doors poll: " + e); }
            else if (door % 3 == 0 && paired) try { com.eurobuddha.dexicon.doors.DoorHost.get(MakerService.this).discover(); }
            catch (RuntimeException e) { log("doors discover: " + e); }
            try { maybeOfflineAlert(); } catch (RuntimeException e) { log("offline alert: " + e); }
            try { maybeNetAlert(); } catch (RuntimeException e) { log("net alert: " + e); }
            // The MiniHub re-read (the trading gate's MiniDapp half) never waits behind a slow relay chain either.
            long nowMs = System.currentTimeMillis();
            if (paired && nowMs - lastHubMs >= HUB_EVERY_MS) { lastHubMs = nowMs; com.eurobuddha.dexicon.TradingGate.checkMiniHub(MakerService.this, node, MakerService.this::changed); }
            // ② pool coin selection must never pick a live ③ order's backing coin: re-held every tick, even while the relay
            // chain runs (a long chain must not let the hold marks lapse).
            holdOrderCoins();
            // Only the relay chain waits on `ticking`: door settlement above never stalls behind a slow inbox/take chain.
            if (ticking) return;
            if (!paired) {
                long now = System.currentTimeMillis();
                if (now - lastRegisterMs >= REREGISTER_GAP_MS) { lastRegisterMs = now; node.reRegister(); }
                return;
            }
            ActivityLog.track(node, db, MakerService.this::changed);   // BOOK/POOL rows need no relay or identity
            runPendingRepublish();
            // ② Pools: PandaPools' keep-alive (keep-fresh, stranding alarm, re-announce, collections), at most every 5 min.
            // Settlement, so it runs even while the trading gate is shut; it needs no relay or identity.
            try { com.eurobuddha.pandapools.DexiconPools.keepAlivePass(MakerService.this, node); }
            catch (RuntimeException e) { log("pool keep-alive: " + e); }
            if (me == null) { deriveIdentity(); return; }
            List<String> relays = RelayStore.load(MakerService.this);
            Tokens.setMxUsd(RelayStore.mxusdOverride(MakerService.this));
            relay.setRelays(relays);
            updateForeground(relays.size());
            if (relays.isEmpty()) return;
            ticking = true; ticks++;
            final String pk = me.publicId();
            net.execute(() -> {
                long t0 = System.currentTimeMillis();
                List<Envelope> msgs = relay.pollInbox(pk, (int) HOLD_SECONDS);
                // A relay that answers instantly with nothing is failing: wait for the next tick instead of spinning.
                final boolean spin = msgs.isEmpty() && System.currentTimeMillis() - t0 < 1000;
                h.post(() -> { againNow = !spin; onInbox(msgs); });
            });
        }
    };

    static final String PREFS_REPUBLISH = "dexicon_pool_republish";

    /** A stranding notification's Re-publish, kept until a paired pass runs it. */
    void queueRepublish(String address) {
        if (address == null || address.trim().isEmpty()) return;
        android.content.SharedPreferences p = getSharedPreferences(PREFS_REPUBLISH, MODE_PRIVATE);
        Set<String> s = new HashSet<>(p.getStringSet("pending", new HashSet<>()));
        s.add(address.trim());
        p.edit().putStringSet("pending", s).commit();
        lastRepublishReadMs = 0;   // a new tap runs on a fresh read at once
    }

    /** Paired: run every queued Re-publish; every signing guard of DexiconPools.republish still applies. An address is
     *  forgotten only once its republish actually ran (or has no recipe here) — one a guard held back stays queued. */
    void runPendingRepublish() {
        Set<String> s = getSharedPreferences(PREFS_REPUBLISH, MODE_PRIVATE).getStringSet("pending", null);
        if (s == null || s.isEmpty()) return;
        // the owner-signing guard needs a read under ~5 s old: reuse one (e.g. a keep-alive pass keeps it warm) ...
        if (com.eurobuddha.pandapools.DexiconPools.custodyFreshToSign(this)) { drainRepublish(); return; }
        // ... else re-read OFF the main thread (on it an older read fails closed), at most once a minute while a
        // held-back republish stays queued — not a provider query every 10 s tick
        long now = System.currentTimeMillis();
        if (now - lastRepublishReadMs < REPUBLISH_READ_GAP_MS) return;
        lastRepublishReadMs = now;
        com.eurobuddha.pandapools.DexiconPools.readCustodyAsync(this, this::drainRepublish);
    }
    static final long REPUBLISH_READ_GAP_MS = 60_000;
    private long lastRepublishReadMs;

    private void drainRepublish() {
        android.content.SharedPreferences p = getSharedPreferences(PREFS_REPUBLISH, MODE_PRIVATE);
        Set<String> s = p.getStringSet("pending", null);
        if (s == null || s.isEmpty()) return;
        Set<String> keep = new HashSet<>();
        for (String a : new ArrayList<>(s)) if (!com.eurobuddha.pandapools.DexiconPools.republish(this, node, a)) keep.add(a);
        if (keep.isEmpty()) p.edit().remove("pending").commit(); else p.edit().putStringSet("pending", keep).commit();
    }

    /** Re-hold (every tick, inside PandaPools' ~3 min reservation) the coins backing my live ③ orders in PandaPools' CoinLock. */
    void holdOrderCoins() {
        List<String> ids = new ArrayList<>();
        try { for (MyOrder o : db.myOrders()) if (o.live()) ids.addAll(o.coins); } catch (RuntimeException unreadable) { return; }
        java.util.Set<String> taken = com.eurobuddha.pandapools.DexiconPools.holdCoins(ids);
        // a ② pool selection reserved it first: not this order's to hold; the order's coin refresh finds it spent / re-backs
        if (!taken.isEmpty()) log("order backing coin reserved by a pool transaction: " + String.join(" ", taken));
    }

    /** The inbox is a CONTINUOUS long-poll (like the MiniDapp's inboxLoop): a take or a maker's answer is handled the
     *  moment it lands, not at the next 10 s tick. TICK_MS stays as the watchdog and the pace when relays fail. */
    private boolean againNow;
    private void tickDone() { ticking = false; chainBusy = false; if (againNow) h.post(tick); }

    /** Takes are being co-signed or the coin refresh runs: the ladder must wait for them (its pass follows in the same
     *  chain); otherwise it runs right now. */
    private boolean chainBusy;
    /** A posted cross spent rung coins: re-back those rungs now so they are not advertised with spent coins until the next tick. */
    private void afterCross(Runnable next) { if (cross.posted()) { ladder.soon(); ladder.pass(next); } else next.run(); }

    private void kickLadder() { if (ladder != null && paired && !chainBusy && !com.eurobuddha.dexicon.TradingGate.blocked(this)) ladder.pass(this::changed); }

    static final long SYNC_GAP_MS = 60_000;
    private long lastSyncMs;
    private void syncMaybe(boolean sync, java.util.function.Consumer<MakerEngine.SyncReport> cb) {
        if (sync) engine.syncRelays(cb); else cb.accept(new MakerEngine.SyncReport());
    }

    static final int MAX_TAKES_PER_CYCLE = 16, MAX_TAKES_PER_SENDER = 2;

    private void onInbox(List<Envelope> msgs) {
        if (ladder != null && ladder.running()) { ladder.afterPass(() -> onInbox(msgs)); return; }   // a kicked pass is mid-flight
        chainBusy = true;
        List<JSONObject> takes = new ArrayList<>();
        java.util.Map<String, Integer> perSender = new java.util.HashMap<>();
        for (Envelope m : msgs) {
            DexIdentity.Opened o = me.open(m.blob);
            if (o == null) continue;
            JSONObject inner; try { inner = new JSONObject(o.text()); } catch (Exception e) { continue; }
            String k = inner.optString("k", "");
            try { inner.put("_from", o.fromPublicId); } catch (Exception ignored) {}
            if ("take".equals(k)) {   // flood limits (review 30 Oct): a few takes per sender, a bounded batch, per cycle
                String from = o.fromPublicId == null ? "" : o.fromPublicId.toLowerCase(java.util.Locale.ROOT);
                Integer seen = perSender.get(from);
                if (takes.size() < MAX_TAKES_PER_CYCLE && (seen == null || seen < MAX_TAKES_PER_SENDER)) { takes.add(inner); perSender.put(from, seen == null ? 1 : seen + 1); }
            }
            else if ("taken".equals(k) || "refused".equals(k)) onReply(inner);
        }
        if (com.eurobuddha.dexicon.TradingGate.blocked(this)) { pausedPass(takes); return; }
        processTakes(takes, 0, () -> {
            if (ticks % 3 == 0) { settleFills(); voidStaleTakes(); }
            // Owner 7 Nov (FAST speed): the relay check reads EVERY relay's whole book one after another, and nothing
            // reads the inbox meanwhile — a take (or a maker's answer to my take) waited behind it each cycle. It is a
            // repair sweep: once a minute, or straight after a take, is enough.
            long nowSync = System.currentTimeMillis();
            boolean sync = !takes.isEmpty() || nowSync - lastSyncMs >= SYNC_GAP_MS;
            if (sync) lastSyncMs = nowSync;
            ladder.pass(() -> cross.run(() -> afterCross(() -> syncMaybe(sync, rep -> {   // then every relay must match this phone (no cancelled order left live anywhere)
                for (String l : rep.lines) log(l);
                if (rep.fixed > 0) changed();
                // by the clock, not the tick count: in Doze one tick runs per 15-min heartbeat, and every one must refresh
                if (System.currentTimeMillis() - lastRefreshMs >= REFRESH_GAP_MS) { lastRefreshMs = System.currentTimeMillis(); refreshAddresses(() -> engine.refresh((e, n, relocked) -> {
                    if (e != null) log("refresh failed: " + e);
                    else if (n > 0 || relocked != null) { log("refresh republished " + n + " order(s)" + (relocked != null ? "; relocked stale backing coins " + relocked : "")); changed(); }
                    tickDone();
                })); }
                else tickDone();
            }))));
        });
    }

    /** Why a take is refused while the gate is shut. It must match {@link #MOVE_ON}, so a taker's one-click run moves to
     *  the next order instead of stopping (and voiding a coin) on my paused one. */
    static final String PAUSED = "not live (maker paused)";
    /** A refusal the taker moves past to the next level (MainActivity.followTake, onReply). */
    public static final String MOVE_ON = "(?i).*(taken|not live|advertised|spent|visible|cover).*";

    /**
     * One trading app per phone, gate shut: no new trade (every take refused, no ladder, no auto-cross, no republish).
     * When an app was actually FOUND my live orders are WITHDRAWN (a cancel each, then the relay sweep) — a withdrawal,
     * not a trade — so no taker keeps hitting them for up to {@link MakerEngine#EXPIRY_SECONDS}. While the MiniHub is
     * merely unread nothing is withdrawn, takes are only refused until the first read. Settlement continues.
     */
    void pausedPass(List<JSONObject> takes) {
        for (JSONObject t : takes) reply(t.optString("_from", ""), refused(t.optString("orderId", ""), PAUSED));
        if (ticks % 3 == 0) { settleFills(); voidStaleTakes(); }
        if (!com.eurobuddha.dexicon.TradingGate.conflict(this)) { tickDone(); return; }
        List<MyOrder> live = new ArrayList<>();
        for (MyOrder o : db.myOrders()) if (o.live()) live.add(o);
        if (!live.isEmpty()) log("trading gate shut: withdrawing " + live.size() + " live order(s)");
        cancelEach(engine, live, 0, () -> engine.syncRelays(rep -> {
            for (String l : rep.lines) log(l);
            if (!live.isEmpty() || rep.fixed > 0) changed();
            tickDone();
        }));
    }

    /** A maker answered one of MY takes (app.js inboxLoop). */
    private void onReply(JSONObject inner) {
        String orderId = inner.optString("orderId", "");
        JSONObject pt = db.pending(orderId);
        if (pt == null || pt.optBoolean("taken", false)) return;   // answered already; the void pass settles it
        // security review 30 Oct: only this order's maker may answer for it, with a real txpowid; the coin ids I track
        // are my own (recorded from my half at send time), never the ones the reply names
        String maker = pt.optString("maker", pt.optJSONObject("order") == null ? "" : pt.optJSONObject("order").optString("pubId", ""));
        try { if (maker.isEmpty() || !DexIdentity.canonicalId(maker).equalsIgnoreCase(DexIdentity.canonicalId(inner.optString("_from", "")))) return; }
        catch (RuntimeException e) { return; }
        if ("taken".equals(inner.optString("k")) && !inner.optString("txpowid").matches("0x[0-9A-Fa-f]{64}")) return;
        try {
            inner.put("coins", pt.optJSONArray("outs") == null ? new JSONArray() : pt.optJSONArray("outs"));
            if (!inner.optString("filled").matches("[0-9]+(\\.[0-9]+)?")) inner.remove("filled");
        } catch (Exception ignored) {}
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
            if (reason.matches(MOVE_ON)) db.setMeta("retry_due", orderId);   // the Activity re-plans at the next level
            Notifier.alert(this, "Take refused", "The maker refused: " + reason);
            voidTake(pt);   // a refusal is not proof: the maker still holds my signed half until one of its coins moves
            changed();
            return;
        }
        // Security review 7 Nov (HIGH): "taken" is the maker's word, not proof. A maker could answer "taken" with any
        // txpowid, never post, and keep my signed half as a free option. The take stays pending: voidStaleTakes later
        // finds my coin spent (the fill really landed → done) or unspent (never posted → spent back to me, voided).
        try { db.savePending(orderId, pt.put("taken", true)); } catch (Exception ignored) {}
        changed();
    }

    /** Security review 30 Oct (HIGH): a signed take has no expiry — a maker could sit on it and post it hours later, only if
     *  the price moved its way. A take unanswered for VOID_AFTER_MS (or refused) is voided: one of MY coins in it is spent
     *  back to me (MakerEngine.relock), so the half I signed can never be posted. */
    static final long VOID_AFTER_MS = 5 * 60_000L;
    private final java.util.Set<String> voiding = new java.util.HashSet<>();

    private void voidStaleTakes() {
        long now = System.currentTimeMillis();
        for (JSONObject p : db.pendings()) if (now - p.optLong("sent", now) > VOID_AFTER_MS) voidTake(p);
    }

    private void voidTake(JSONObject pt) {
        JSONObject order = pt.optJSONObject("order");
        final String orderId = order == null ? "" : order.optString("orderId", ""), actId = pt.optString("act", "");
        JSONArray mine = pt.optJSONArray("mine");
        if (orderId.isEmpty() || engine == null || !voiding.add(orderId)) return;
        final String coin = mine == null || mine.length() == 0 ? "" : mine.optString(0, "");
        if (!coin.matches("0x[0-9A-Fa-f]{64}")) { voiding.remove(orderId); db.removePending(orderId); changed(); return; }   // pre-0.1.51 take: nothing recorded to spend
        // review 7 Nov (R3-4): only an ON-CHAIN spend settles the take. A spend seen only in the mempool may never mine
        // (the maker would keep its option), so then wait and look again next pass; relock only a coin unspent everywhere.
        node.cmd("coins relevant:true coinid:" + coin, new NodeApi.Cb() {
            public void onResult(JSONObject onChain) {
                if (NodeApi.rows(onChain).isEmpty()) { done("Your coin " + coin + " in this take is now spent on chain — voided back to you, or the maker posted the take; check HISTORY."); return; }
                node.cmd("coins relevant:true checkmempool:true coinid:" + coin, new NodeApi.Cb() {
            public void onResult(JSONObject j) {
                List<JSONObject> rows = NodeApi.rows(j);
                if (rows.isEmpty()) { voiding.remove(orderId); return; }   // spent in the mempool only: wait for it to mine or drop
                com.eurobuddha.dexicon.fill.Coin c = com.eurobuddha.dexicon.fill.Coin.of(rows.get(0));
                node.cmd("getaddress", new NodeApi.Cb() {
                    public void onResult(JSONObject a) {
                        JSONObject r = a.optJSONObject("response"); String to = r == null ? "" : r.optString("address", "");
                        if (to.isEmpty()) { voiding.remove(orderId); return; }
                        engine.relock(java.util.Collections.singletonList(c), to, (err, tx) -> h.post(() -> {
                            voiding.remove(orderId);
                            if (err != null) { log("void take " + orderId + " failed: " + err + " — retrying"); return; }
                            // review 7 Nov (A-2, mirror of MDS 0.0.78): keep the record until the spend is ON CHAIN - a posted
                            // relock can still be dropped; the next pass then finds the coin spent and closes it
                            log("void take " + orderId + ": relock of " + coin + " posted; settles when it is on chain");
                        }));
                    }
                    public void onError(String m) { voiding.remove(orderId); }
                });
            }
            public void onError(String m) { voiding.remove(orderId); }
                });
            }
            public void onError(String m) { voiding.remove(orderId); }
            private void done(String note) {
                ActivityLog.update(db, actId, a -> { if (!ActivityLog.COSIGNED.equals(a.optString("stage"))) a.put("reason", note); });
                log("take " + orderId + ": " + note); db.removePending(orderId); voiding.remove(orderId); changed();
            }
        });
    }

    private void processTakes(List<JSONObject> takes, int i, Runnable done) {
        if (i >= takes.size()) { done.run(); return; }
        handleTake(takes.get(i), () -> processTakes(takes, i + 1, done));
    }

    void reply(String toPublicId, JSONObject obj) {
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

    void handleTake(JSONObject take, Runnable next) {
        final String taker = take.optString("_from", ""), orderId = take.optString("orderId", ""), hex = take.optString("txn", "");
        // a peer's txn goes into txnview/txnimport: bounded hex only (security review 30 Oct)
        if (taker.isEmpty() || orderId.isEmpty() || !hex.matches("(0x)?[0-9A-Fa-f]{2,200000}")) { next.run(); return; }
        if (com.eurobuddha.dexicon.TradingGate.blocked(this)) { reply(taker, refused(orderId, PAUSED)); next.run(); return; }   // the gate shut mid-batch
        final MyOrder o = db.myOrder(orderId);
        if (o == null || !o.live()) { reply(taker, refused(orderId, "order not live")); next.run(); return; }
        if (!lockCoins(o.coins)) { reply(taker, refused(orderId, "taken")); next.run(); return; }
        log("take received for " + orderId + " from " + taker + " (" + hex.length() / 2 + " bytes)");
        final long receivedMs = System.currentTimeMillis();
        final List<String> spentByMe = new ArrayList<>(), allIns = new ArrayList<>();
        FillTxn.cosign(node, hex, FillTxn.signKeys(o.scripts), (ins, outs, hasState, wallet) -> {
            List<String> mine = new ArrayList<>(myAddresses); mine.addAll(wallet);   // fresh, complete: no coin of mine passes as the taker's
            FillPlan.Verdict v = FillPlan.verifyAsMaker(ins, outs, hasState, o.asFillOrder(), o.coins, o.remaining, mine);
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

    static JSONObject refused(String orderId, String reason) {
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

    // ----- AtomiX SwapService.maybeOfflineAlert: a published AtomiX market with the node gone -----
    private static final String CH_OFFLINE = "dexicon_offline";
    private static final int OFFLINE_ID = 7602;
    static final long OFFLINE_ALERT_AFTER_MS = 3 * 60_000, OFFLINE_REALERT_MS = 60 * 60_000;
    private long unpairedSince, lastOfflineAlertMs;

    /** Unpaired more than 3 min with an AtomiX market or OTC offer on → HIGH-priority alert, re-posted hourly until the
     *  node returns. The tap opens the node app (a service can't launch another app's Activity; a notification can). */
    private void maybeOfflineAlert() {
        long now = System.currentTimeMillis();
        if (!offlineAlertDue(paired, com.eurobuddha.dexicon.doors.DoorHost.get(this).marketPublished(), unpairedSince, lastOfflineAlertMs, now)) return;
        lastOfflineAlertMs = now;
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm == null) return;
        Intent launch = com.eurobuddha.minimaapi.MinimaAPI.launchNode(this);
        android.app.PendingIntent pi = android.app.PendingIntent.getActivity(this, 13, launch != null ? launch : new Intent(this, com.eurobuddha.dexicon.MainActivity.class),
                android.app.PendingIntent.FLAG_UPDATE_CURRENT | android.app.PendingIntent.FLAG_IMMUTABLE);
        String body = "Your Minima node isn't running — your published AtomiX market will drop off the book. Tap to open it.";
        try {
            nm.notify(OFFLINE_ID, new NotificationCompat.Builder(this, CH_OFFLINE).setContentTitle("Market offline").setContentText(body)
                    .setStyle(new NotificationCompat.BigTextStyle().bigText(body)).setSmallIcon(android.R.drawable.stat_notify_error)
                    .setPriority(NotificationCompat.PRIORITY_HIGH).setContentIntent(pi).setAutoCancel(true).build());
        } catch (SecurityException noPermission) { /* notifications off: the in-app status still says so */ }
        log("market offline alert (unpaired " + (now - unpairedSince) / 60_000 + " min)");
    }
    static boolean offlineAlertDue(boolean paired, boolean marketLive, long unpairedSince, long lastAlertMs, long now) {
        return !paired && marketLive && unpairedSince != 0 && now - unpairedSince >= OFFLINE_ALERT_AFTER_MS
                && (lastAlertMs == 0 || now - lastAlertMs >= OFFLINE_REALERT_MS);
    }
    private void cancelOfflineAlert() {
        try { NotificationManager nm = getSystemService(NotificationManager.class); if (nm != null) nm.cancel(OFFLINE_ID); } catch (Exception ignored) {}
    }

    // ----- 9 Nov: the price feed gets no reply at all while the phone says it is online -> "reset Wi-Fi" -----
    private static final String CH_NET = "dexicon_net";
    private static final int NET_ID = 7603;
    static final long NET_ALERT_AFTER_MS = 5 * 60_000, NET_REALERT_MS = 60 * 60_000;
    private long lastNetAlertMs;
    private boolean netAlertShown;

    /** A pegged ladder whose price feed has had NO reply from MEXC or CoinGecko for 5 min: almost always this phone's own
     *  connection (the S10+'s Wi-Fi was losing 20-30 % of packets and the ladder sat withdrawn for hours). Android lets no
     *  app toggle Wi-Fi, so a HIGH alert opens the system Internet/Wi-Fi panel instead; re-posted hourly, cleared on recovery. */
    private void maybeNetAlert() {
        Ladder.Config c = Ladder.Config.fromJson(db.meta(LadderRunner.CONFIG, ""));
        long now = System.currentTimeMillis(), down = MexcFeed.unreachableForMs();
        if (netAlertShown && down == 0) { netAlertShown = false; lastNetAlertMs = 0; cancelNetAlert(); return; }
        if (!netAlertDue(c.enabled && c.peg, down, lastNetAlertMs, now)) return;
        lastNetAlertMs = now; netAlertShown = true;
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm == null) return;
        Intent panel = Build.VERSION.SDK_INT >= 29 ? new Intent(android.provider.Settings.Panel.ACTION_INTERNET_CONNECTIVITY)
                : new Intent(android.provider.Settings.ACTION_WIFI_SETTINGS);
        PendingIntent pi = PendingIntent.getActivity(this, 14, panel.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        String body = "No reply from MEXC or CoinGecko for " + down / 60_000 + " min, so your pegged ladder is withdrawn. This is "
                + "usually this phone's own connection dropping packets. Tap to turn Wi-Fi off and on.";
        try {
            nm.notify(NET_ID, new NotificationCompat.Builder(this, CH_NET).setContentTitle("No price: check this phone's Wi-Fi").setContentText(body)
                    .setStyle(new NotificationCompat.BigTextStyle().bigText(body)).setSmallIcon(android.R.drawable.stat_notify_error)
                    .setPriority(NotificationCompat.PRIORITY_HIGH).setContentIntent(pi).setAutoCancel(true).build());
        } catch (SecurityException noPermission) { /* notifications off: the maker dialog still shows the error */ }
        log("net alert: price feed unreachable " + down / 60_000 + " min");
    }
    static boolean netAlertDue(boolean pegged, long downMs, long lastAlertMs, long now) {
        return pegged && downMs >= NET_ALERT_AFTER_MS && (lastAlertMs == 0 || now - lastAlertMs >= NET_REALERT_MS);
    }
    private void cancelNetAlert() {
        try { NotificationManager nm = getSystemService(NotificationManager.class); if (nm != null) nm.cancel(NET_ID); } catch (Exception ignored) {}
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(new NotificationChannel(CH_FG, "Maker engine", NotificationManager.IMPORTANCE_LOW));
            if (nm != null) nm.createNotificationChannel(new NotificationChannel(CH_OFFLINE, "Market offline", NotificationManager.IMPORTANCE_HIGH));
            if (nm != null) nm.createNotificationChannel(new NotificationChannel(CH_NET, "No price feed", NotificationManager.IMPORTANCE_HIGH));
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
