package com.eurobuddha.dexicon.doors;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import com.eurobuddha.atomix.IdentityWatch;
import com.eurobuddha.atomix.TradingContext;
import com.eurobuddha.atomix.eth.EthNet;
import com.eurobuddha.atomix.eth.EthRpc;
import com.eurobuddha.atomix.eth.EthSend;
import com.eurobuddha.atomix.eth.EthTx;
import com.eurobuddha.atomix.eth.EthWallet;
import com.eurobuddha.atomix.swap.MinimaHtlc;
import com.eurobuddha.atomix.swap.Order;
import com.eurobuddha.atomix.swap.SwapDb;
import com.eurobuddha.atomix.swap.SwapEngine;
import com.eurobuddha.atomix.swap.SwapOrderBook;
import com.eurobuddha.atomix.swap.SwapTake;
import com.eurobuddha.comms.CommsIdentity;
import com.eurobuddha.comms.CommsScanner;
import com.eurobuddha.comms.CommsTransport;
import com.eurobuddha.comms.CryptoProvider;
import com.eurobuddha.comms.Hex;
import com.eurobuddha.comms.LocalEcCryptoProvider;
import com.eurobuddha.comms.NodeApi;
import com.eurobuddha.comms.Opened;
import com.eurobuddha.comms.Sodium;
import com.eurobuddha.dexicon.Notifier;
import com.eurobuddha.dexicon.maker.MakerService;
import com.goterl.lazysodium.LazySodium;

import org.json.JSONArray;
import org.json.JSONObject;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The DOORS host — Dexicon-owned, the Java twin of mds/dexicon doors/ax_service.js + ui/doors.js's take path. It wires
 * the vendored AtomiX engine exactly as AtomiX's own SwapService does (ensureDerivations: node-derived ETH key via
 * {@code seedrandom modifier:ethbridge}, never persisted; the persisted swap identity {@code swap_addr/swap_pk} adopted,
 * never self-healed; the node's 64 keys for refunds; IdentityWatch HALT on mismatch) but as the TAKER only: no order is
 * ever armed ({@code setMyOrder} never called → every responder path declines), no OTC, no publishing.
 *
 * ONE process-wide instance (Activity and MakerService share it), so there is one engine, one SwapDb, one nonce journal
 * — AtomiX runs two engines and guards them with statics; Dexicon simply doesn't. {@link MakerService} drives
 * {@link #poll()} every ~90 s with the app closed (claims, ETH withdraws, refunds, forwards); the DOORS tab drives it
 * too while open.
 *
 * BOTH AtomiX markets: the engine takes in {@link TradingContext#active()} only (and MinimaHtlc's active token), and its
 * book scan aborts if the selection changes mid-scan. So {@link #scanBooks} scans the MxUSD market then the MINIMA
 * market, each under its own selection, one after the other; {@link #take} selects the route's market and holds it — no
 * scan, no second take — until every part has called back. Settlement is market-agnostic (claims and refunds read each
 * coin's own token; SwapEngine.expectedTokenId attributes by the swap's recorded label), so one poll settles both.
 */
public final class DoorHost {

    private static DoorHost INSTANCE;
    public static synchronized DoorHost get(Context c) {
        if (INSTANCE == null) INSTANCE = new DoorHost(c.getApplicationContext());
        return INSTANCE;
    }

    public interface Progress { void stage(String s); void done(String message, boolean error); }

    public static final TradingContext[] MARKETS = {TradingContext.MXUSDT, TradingContext.MINIMA};
    private static final long REREGISTER_MS = 30_000, LEG_WATCHDOG_MS = 4 * 60_000, CONFIRM_EVERY_MS = 5_000;
    private static final int CONFIRM_TRIES = 24;

    private final Context app;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "dexicon-doors"); t.setDaemon(true); return t; });
    final SharedPreferences prefs;
    private final com.eurobuddha.dexicon.NodeApi dnode;
    private final NodeApi node;
    private LazySodium ls;   // loaded on first use (native libsodium), not at construction
    private LazySodium ls() { if (ls == null) ls = Sodium.get(); return ls; }
    private final MinimaHtlc minima;
    private final SwapDb db;
    private final EthWallet wallet = new EthWallet();
    private final SwapEngine engine;
    public final EthNet net = EthNet.MAINNET;
    private final EthRpc rpc;
    private final Map<TradingContext, CommsIdentity> ids = new EnumMap<>(TradingContext.class);
    private final Map<TradingContext, CryptoProvider> crypto = new EnumMap<>(TradingContext.class);
    private final Map<TradingContext, Map<String, Order>> books = new EnumMap<>(TradingContext.class);
    private final Map<TradingContext, String> coinBal = new EnumMap<>(TradingContext.class);
    private final Map<TradingContext, CommsScanner> noticeScanners = new EnumMap<>(TradingContext.class);

    private boolean walletDeriving, minimaSettingUp, keysLoaded, keysLoading, identityDeriving, scanning, busy, forwarding;
    private long lastRegisterMs;
    private String bootNote = "Waiting for the node…";
    /** Ethereum reads (null = unreadable — every fund check fails CLOSED on null). */
    public volatile BigInteger ethWei, usdtRaw, gasWei, baseFeeWei;

    private DoorHost(Context app) {
        this.app = app;
        com.eurobuddha.minimaapi.ethereum.EthereumTransactions.initialize(app);
        prefs = app.getSharedPreferences(DoorForwards.PREFS, Context.MODE_PRIVATE);
        dnode = new com.eurobuddha.dexicon.NodeApi(app, en -> { if (en) { IdentityWatch.forceNext(); ensure(); } changed(); });
        lastRegisterMs = System.currentTimeMillis();
        node = new NodeApi(dnode);
        minima = new MinimaHtlc(node);
        db = new SwapDb(app);
        engine = new SwapEngine(node, minima, db, wallet, h, new SwapEngine.Notifier() {
            @Override public void notify(String title, String body) { Notifier.alert(app, "AtomiX · " + title, body.replace("mxUSDT", "MxUSD")); }
            @Override public void onSwapsChanged() { changed(); }
        });
        rpc = new EthRpc(net.defaultRpc);
        engine.setNetwork(rpc, net);
        for (TradingContext m : MARKETS) { books.put(m, Collections.emptyMap()); coinBal.put(m, null); }
        select(TradingContext.MXUSDT);
    }

    /** Tell the Activity (its ACTION_CHANGED receiver repaints) that door state moved. */
    void changed() { try { app.sendBroadcast(new Intent(MakerService.ACTION_CHANGED).setPackage(app.getPackageName())); } catch (Exception ignored) {} }

    /** The engine's market selection — TradingContext (AtomiX's own API, not persisted here) + the Minima token. */
    private void select(TradingContext m) { TradingContext.setActive(m, null); minima.setActiveToken(m.tokenId); }

    // ------------------------------------------------------------------ boot (AtomiX SwapService.ensureDerivations)

    /** Idempotent, retrying bootstrap; every step clears its in-flight flag on failure and retries next pass. */
    void ensure() {
        if (!dnode.isEnabled()) {
            long now = System.currentTimeMillis();
            if (now - lastRegisterMs >= REREGISTER_MS) { lastRegisterMs = now; dnode.reRegister(); }
        }
        if (!wallet.ready() && !walletDeriving) {
            walletDeriving = true;
            wallet.deriveFromNode(node, h, new EthWallet.Cb() {
                @Override public void ok(String address) { walletDeriving = false; changed(); }
                @Override public void err(String msg) { walletDeriving = false; bootNote = "ETH key: " + msg; }   // never log key material
            });
        }
        if (!minima.ready() && !minimaSettingUp) {
            minimaSettingUp = true;
            minima.setup(prefs.getString("swap_addr", ""), prefs.getString("swap_pk", ""), new MinimaHtlc.SetupCb() {
                @Override public void ok(String a, String pk) {
                    minimaSettingUp = false;
                    prefs.edit().putString("swap_addr", a).putString("swap_pk", pk).apply();
                    engine.setMyMinimaPk(pk);
                    changed();
                }
                @Override public void err(String m) { minimaSettingUp = false; bootNote = "Minima swap key: " + m; }
            });
        }
        if (minima.ready() && !keysLoaded && !keysLoading) {
            keysLoading = true;
            minima.loadMyKeys(new MinimaHtlc.KeysCb() {
                @Override public void ok(java.util.Set<String> keys) { keysLoading = false; keysLoaded = !keys.isEmpty(); engine.setMyPubkeys(keys); }
                @Override public void err(String m) { keysLoading = false; }
            });
        }
        if (ids.size() < MARKETS.length && !identityDeriving) deriveIdentities();
        if (wallet.ready() && minima.ready()) IdentityWatch.check(node, wallet, minima, prefs, h, new SwapEngine.Notifier() {
            @Override public void notify(String title, String body) { Notifier.alert(app, "AtomiX HALTED", "This node's seed no longer matches the keys AtomiX uses. New AtomiX swaps are stopped; in-flight swaps keep settling."); }
            @Override public void onSwapsChanged() { changed(); }
        });
    }

    /** AtomiX's per-market comms identities (HKDF "usdtswap" / "minimaswap") — they seal the buy handshake to a maker. */
    private void deriveIdentities() {
        identityDeriving = true;
        node.cmd("vault action:seed", new NodeApi.Cb() {
            @Override public void onResult(JSONObject j) {
                JSONObject r = j.optJSONObject("response");
                final String ikm = r == null ? "" : r.optString("seed", r.optString("phrase", ""));
                if (ikm.isEmpty()) { identityDeriving = false; bootNote = "Node seed unreadable (vault locked?)"; return; }
                io.execute(() -> {
                    try {
                        byte[] seed = ikm.startsWith("0x") ? Hex.from(ikm) : ikm.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                        Map<TradingContext, CommsIdentity> got = new EnumMap<>(TradingContext.class);
                        for (TradingContext m : MARKETS) got.put(m, CommsIdentity.fromSeed(ls(), seed, m.hkdfContext));
                        h.post(() -> {
                            ids.putAll(got);
                            for (TradingContext m : MARKETS) crypto.put(m, new LocalEcCryptoProvider(ls(), got.get(m)));
                            identityDeriving = false; changed();
                        });
                    } catch (Exception e) { h.post(() -> { identityDeriving = false; bootNote = "Identity derive failed"; }); }
                });
            }
            @Override public void onError(String m) { identityDeriving = false; bootNote = "Seed read: " + m; }
        });
    }

    public boolean ready() { return wallet.ready() && minima.ready() && keysLoaded && ids.size() == MARKETS.length; }
    public String bootNote() { return ready() ? "" : bootNote; }
    public String ethAddress() { return wallet.address(); }
    public String myPublicId(TradingContext m) { CommsIdentity i = ids.get(m); return i == null ? null : i.publicId(); }
    public Map<String, Order> book(TradingContext m) { return books.get(m); }
    public String coinBalance(TradingContext m) { return coinBal.get(m); }
    public boolean busy() { return busy; }

    // ------------------------------------------------------------------ background pass (MakerService ~90 s, DOORS tab)

    /** One settlement pass: boot steps, then the engine's poll (claims / withdraws / refunds), maker notices, forwards.
     *  With the node unpaired (or not booted yet) it falls back to {@link #pollEthOnly}: an ETH refund needs no node. */
    public void poll() {
        ensure();
        if (!dnode.isEnabled() || !ready()) { engine.pollEthOnly(); return; }
        if (!com.eurobuddha.atomix.MainActivity.SWEEP_ACTIVE) engine.poll();
        scanNotices();
        processForwards();
    }

    /** Settlement with no Minima node: ETH refunds and withdraws of door swaps (the engine checks each ETH contract). */
    public void pollEthOnly() { ensure(); engine.pollEthOnly(); }

    /** Ask for a refund of my lock now; the answer (why not yet, or "refunding") comes back on the main thread. */
    public void refundNow(String hash, java.util.function.Consumer<String> answer) { engine.refundNow(hash, answer); }
    public int lastMinimaBlock() { return engine.lastMinimaBlock(); }

    /** Makers seal a "retrying / declined" notice to us on the market's TAKE channel. Dexicon is a taker only, so it
     *  scans that channel just while a swap it started is still waiting for its maker. */
    private void scanNotices() {
        boolean waiting = false;
        for (SwapDb.Swap s : swaps()) if (SwapDb.ST_STARTED.equals(s.status)) { waiting = true; break; }
        if (!waiting) return;
        for (TradingContext m : MARKETS) {
            CryptoProvider cp = crypto.get(m);
            if (cp == null) continue;
            CommsScanner sc = noticeScanners.get(m);
            if (sc == null) {
                sc = new CommsScanner(node, cp, new CommsScanner.MetaStore() {   // own bookmarks, namespaced per address by the scanner
                    @Override public String getMeta(String k, String def) { return prefs.getString("noticemeta_" + k, def); }
                    @Override public void setMeta(String k, String v) { prefs.edit().putString("noticemeta_" + k, v).apply(); }
                }, m.takeAddr, (cid, opened, coin) -> routeNotice(m, opened), (ok, n) -> {});
                noticeScanners.put(m, sc);
            }
            sc.scan(0);
        }
    }

    /** A notice is accepted only when sealed to MY id for that market by the sender it names (the engine further
     *  checks it is the maker I notified, for a swap I started). Informational only — it never moves funds. */
    boolean routeNotice(TradingContext m, Opened opened) {
        try {
            JSONObject j = new JSONObject(new String(opened.plaintext, java.nio.charset.StandardCharsets.UTF_8));
            if (!j.has("notice")) return false;
            String to = j.optString("to", ""), from = j.optString("from", ""), hash = j.optString("hash", ""), me = myPublicId(m);
            if (hash.isEmpty() || me == null || !me.equals(to)) return false;
            if (opened.fromPublicId == null || !opened.fromPublicId.equals(from)) return false;
            return engine.onMakerNotice(hash, from, j.optString("notice"), j.optString("reason"));
        } catch (Exception e) { return false; }
    }

    // ------------------------------------------------------------------ reads

    /** Both AtomiX books, one after the other, each under its own market selection. */
    public void scanBooks(Runnable done) {
        if (scanning || busy || !ready()) { if (done != null) done.run(); return; }
        scanning = true;
        final TradingContext home = TradingContext.active();
        scanOne(0, () -> { select(home); scanning = false; if (done != null) done.run(); });
    }
    private void scanOne(int i, Runnable done) {
        if (i >= MARKETS.length) { done.run(); return; }
        final TradingContext m = MARKETS[i];
        select(m);
        SwapOrderBook.scan(node, ls(), book -> { books.put(m, new LinkedHashMap<>(book)); scanOne(i + 1, done); }, err -> scanOne(i + 1, done));
    }

    /** ETH, USDT and gas from the ETH RPC (worker thread), MxUSD + MINIMA sendable from the node. */
    public void readBalances(Runnable done) {
        for (TradingContext m : MARKETS) dnode.cmd("balance tokenid:" + m.tokenId, new com.eurobuddha.dexicon.NodeApi.Cb() {
            public void onResult(JSONObject j) {
                List<JSONObject> rows = com.eurobuddha.dexicon.NodeApi.rows(j);
                coinBal.put(m, !j.optBoolean("status") ? null : rows.isEmpty() ? "0" : rows.get(0).optString("sendable", null));
                if (done != null) done.run();
            }
            public void onError(String e) { coinBal.put(m, null); if (done != null) done.run(); }
        });
        final String addr = wallet.address();
        if (addr == null) return;
        io.execute(() -> {
            BigInteger e = null, u = null, g = null, b = null;
            try { e = rpc.getBalance(addr); } catch (Exception ignored) {}
            try { u = EthWallet.erc20BalanceRaw(rpc, net.token("USDT").address, addr); } catch (Exception ignored) {}
            try { g = EthRpc.hexToBig(rpc.callStr("eth_gasPrice", new JSONArray())); } catch (Exception ignored) {}
            try { b = rpc.baseFeePerGasOrZero(); } catch (Exception ignored) {}
            final BigInteger fe = e, fu = u, fg = g, fb = b;
            h.post(() -> { ethWei = fe; usdtRaw = fu; gasWei = fg; baseFeeWei = fb; if (done != null) done.run(); });
        });
    }
    /** Wei to reserve for {@code gasLimit} at the price EthTx will actually broadcast at; null when gas is unreadable. */
    public BigInteger gasReserve(BigInteger gasLimit) {
        return gasWei == null || gasWei.signum() <= 0 ? null : EthSend.gasReserveWei(gasWei, baseFeeWei, gasLimit);
    }

    // ------------------------------------------------------------------ door swaps (SwapDb is the source of truth)

    /** Every swap this node started (INITIATOR), newest first. */
    public List<SwapDb.Swap> swaps() {
        List<SwapDb.Swap> out = new ArrayList<>();
        for (SwapDb.Swap s : db.allSwaps()) if ("INITIATOR".equals(s.role)) out.add(s);
        return out;
    }
    public List<SwapDb.Event> events(String hash) { return db.getEvents(hash); }
    public JSONObject forward(String hash) { return DoorForwards.get(prefs, hash); }
    public void retryForward(String hash) { DoorForwards.retry(prefs, hash); changed(); }

    // ------------------------------------------------------------------ the take (AtomiX MainActivity startSweep/fireSweepLeg)

    private Runnable watchdog;

    /**
     * Run a door: every leg of {@code plan} against {@code market}, best price first. BUY legs (USDT locks) fire back to
     * back — EthTx serialises their nonces — and each seals our hashlock to its maker over the market's TAKE channel;
     * SELL legs (Minima-coin locks) wait for the previous lock to confirm on chain so coin selection cannot collide.
     * A forward is queued per part when {@code to} is set. Returns an error message, or null when started.
     */
    public String take(TradingContext market, DoorPlan.Plan plan, String to, Progress pr) {
        if (!ready()) return "AtomiX not ready — " + bootNote();
        if (IdentityWatch.halted()) return "Halted: this node no longer owns the keys AtomiX uses here.";
        if (busy || scanning) return "An AtomiX swap is already starting — wait for it to finish.";
        if (plan == null || plan.legs.isEmpty()) return "Nothing to fill.";
        if (to != null && !to.trim().isEmpty() && (plan.sell ? !DoorForwards.isEthAddr(to) : !DoorForwards.isMxAddr(to))) return "Bad destination address.";
        busy = true;
        select(market);
        com.eurobuddha.atomix.MainActivity.SWEEP_ACTIVE = plan.sell;
        fireLeg(market, plan, to == null ? "" : to.trim(), 0, 0, pr);
        return null;
    }

    private void endTake(Progress pr, String msg, boolean error) {
        busy = false;
        com.eurobuddha.atomix.MainActivity.SWEEP_ACTIVE = false;
        if (watchdog != null) { h.removeCallbacks(watchdog); watchdog = null; }
        changed();
        if (pr != null) pr.done(msg, error);
    }

    private void arm(Runnable r) { if (watchdog != null) h.removeCallbacks(watchdog); watchdog = r; h.postDelayed(r, LEG_WATCHDOG_MS); }

    private void fireLeg(TradingContext market, DoorPlan.Plan plan, String to, int i, int ok, Progress pr) {
        final int total = plan.legs.size();
        if (i >= total) { endTake(pr, "AtomiX: " + ok + "/" + total + " part" + (total == 1 ? "" : "s") + (plan.sell ? " locked" : " sent") + " — settlement continues in the background.", false); return; }
        final DoorPlan.Leg leg = plan.legs.get(i);
        final String coin = DoorPlan.coin(market);
        if (!SwapOrderBook.makerLive(books.get(market).get(leg.maker.signerPk), DoorPlan.SYM, plan.sell)) {
            endTake(pr, "Stopped at part " + (i + 1) + ": that maker just withdrawn from the " + coin + " market — refresh and try again.", true); return;
        }
        if (pr != null) pr.stage("AtomiX · locking part " + (i + 1) + "/" + total + " (" + coin + " market)…");
        final boolean[] answered = {false};
        arm(() -> { if (!answered[0] && busy) endTake(pr, "AtomiX stalled at part " + (i + 1) + " (no response) — stopped. Locked parts continue; unmatched parts refund.", true); });
        SwapEngine.StartCb cb = new SwapEngine.StartCb() {
            @Override public void ok(String hash) {
                if (answered[0]) return;
                answered[0] = true;
                if (!to.isEmpty()) DoorForwards.queue(prefs, hash, plan.sell ? "usdt" : "minima", to);
                changed();
                if (!plan.sell) {
                    // Hand the maker our hashlock (sealed to its comms id) so it can find the USDT lock — essential, not cosmetic.
                    if (leg.maker.commsPublicId != null && !leg.maker.commsPublicId.isEmpty()) {
                        engine.noteTakeSent(hash, leg.maker.commsPublicId);   // the only sender whose notice we accept
                        SwapTake.send(node, crypto.get(market), leg.maker.commsPublicId, ids.get(market).publicId(), hash, market, new CommsTransport.SendCb() {
                            @Override public void onSent(String txpowid) {}
                            @Override public void onFailed(String message) { Notifier.alert(app, "AtomiX", "USDT locked, but telling the maker failed (" + message + ") — swap " + hash); }
                        });
                    }
                    if (busy) fireLeg(market, plan, to, i + 1, ok + 1, pr);   // a stalled-then-late leg still notifies its maker, but starts nothing new
                    return;
                }
                if (!busy) return;
                if (pr != null) pr.stage("AtomiX · part " + (i + 1) + "/" + total + " lock sent — confirming on chain before the next part…");
                awaitConfirm(hash, 0, pr, i + 1, onChain -> {
                    if (onChain) fireLeg(market, plan, to, i + 1, ok + 1, pr);
                    else endTake(pr, "Part " + (i + 1) + "'s lock did not confirm in time; no further parts started. It refunds at its timelock if unmatched.", true);
                });
            }
            @Override public void err(String msg) {
                if (answered[0]) return;
                answered[0] = true;
                if (busy) endTake(pr, "AtomiX stopped at part " + (i + 1) + "/" + total + ": " + msg, true);
            }
        };
        if (plan.sell) engine.startMinimaToErc20(leg.maker, leg.minima, DoorPlan.SYM, leg.usdt, cb);
        else engine.startErc20ToMinima(leg.maker, DoorPlan.SYM, leg.usdt, leg.minima, cb);
    }

    private interface Confirmed { void done(boolean onChain); }
    /** Poll until MY lock is on chain (coinage 1 spends its inputs, so the next part cannot re-select them). Each
     *  round re-arms the stall watchdog: a confirm callback that never returns still releases the take. */
    private void awaitConfirm(String hash, int attempt, Progress pr, int part, Confirmed cb) {
        final boolean[] back = {false};
        arm(() -> { if (!back[0] && busy) endTake(pr, "AtomiX stalled confirming part " + part + " — stopped. Locked parts continue; unmatched parts refund.", true); });
        engine.confirmMyLock(hash, true, onChain -> {
            if (back[0] || !busy) return;
            back[0] = true;
            if (onChain) cb.done(true);
            else if (attempt >= CONFIRM_TRIES) cb.done(false);
            else h.postDelayed(() -> awaitConfirm(hash, attempt + 1, pr, part, cb), CONFIRM_EVERY_MS);
        });
    }

    // ------------------------------------------------------------------ forwards (lib/door.js processForwards)

    private void processForwards() {
        if (forwarding || com.eurobuddha.atomix.MainActivity.SWEEP_ACTIVE) return;
        List<JSONObject> due = new ArrayList<>();
        for (JSONObject f : DoorForwards.list(prefs)) {
            if (!DoorForwards.PENDING.equals(f.optString("status"))) continue;
            SwapDb.Swap s = db.getSwap(f.optString("swap"));
            String act = DoorForwards.decide(f, s == null ? null : s.status);
            if ("void".equals(act)) { try { f.put("status", DoorForwards.VOID); } catch (Exception ignored) {} DoorForwards.save(prefs, f); changed(); }
            else if ("send".equals(act)) due.add(f);
        }
        if (due.isEmpty()) return;
        forwarding = true;
        forwardNext(due, 0);
    }
    private void forwardNext(List<JSONObject> due, int i) {
        if (i >= due.size()) { forwarding = false; return; }
        final JSONObject f = due.get(i);
        SwapDb.Swap s = db.getSwap(f.optString("swap"));
        final String to = f.optString("to");
        Done done = (err, tx, uncertain) -> {
            DoorForwards.outcome(f, err, tx, uncertain);
            DoorForwards.save(prefs, f);
            if (err == null) Notifier.alert(app, "AtomiX forward sent", f.optString("amount") + " " + DoorForwards.label("minima".equals(f.optString("kind")) ? f.optString("coin") : "USDT") + " → " + to + " · tx " + tx);
            changed();
            forwardNext(due, i + 1);
        };
        if (s == null) { done.run("swap record missing", null, false); return; }
        if ("minima".equals(f.optString("kind"))) {
            if (!DoorForwards.isMxAddr(to)) { done.run("bad address", null, false); return; }
            TradingContext c = TradingContext.forCoinLabel(s.buyToken);
            String token = c != null ? c.tokenId : DoorForwards.MXUSD;
            BigDecimal amt;
            try { amt = new BigDecimal(s.buyAmount).subtract(new BigDecimal(DoorForwards.NOTIFY_AMOUNT)); } catch (Exception e) { done.run("bad amount", null, false); return; }
            if (amt.signum() <= 0) { done.run("nothing to forward", null, false); return; }
            try { f.put("amount", amt.stripTrailingZeros().toPlainString()).put("coin", token); } catch (Exception ignored) {}
            dnode.cmdR("send amount:" + amt.stripTrailingZeros().toPlainString() + " address:" + to + " tokenid:" + token, new com.eurobuddha.dexicon.NodeApi.Cb() {
                public void onResult(JSONObject j) { JSONObject r = j.optJSONObject("response"); done.run(null, r == null ? "" : r.optString("txpowid", ""), false); }
                public void onError(String m) {
                    // A lost reply to a `send` may still have gone through — never resend that automatically.
                    boolean unknown = m != null && (m.contains("may still complete") || m.contains("didn't respond") || m.contains("Outcome is unknown"));
                    done.run(m, null, unknown);
                }
            });
        } else {
            if (!DoorForwards.isEthAddr(to) || !wallet.ready()) { done.run("ETH side not ready", null, false); return; }
            final String amount = s.buyAmount, hash = f.optString("hash");
            try { f.put("amount", amount); } catch (Exception ignored) {}
            final EthNet.Token usdt = net.token("USDT");
            io.execute(() -> {
                try {
                    BigInteger raw = EthSend.parseUnits(amount, usdt.decimals);
                    // operation key "doorfwd:<hash>": a retry after a lost broadcast reply returns the SAME transaction
                    // from the durable nonce journal instead of sending a second transfer.
                    String tx = EthTx.sendOperation(rpc, wallet.creds(), net.chainId, usdt.address, EthSend.buildTransferData(to, raw),
                            BigInteger.ZERO, EthSend.GAS_ERC20, () -> true, "doorfwd:" + hash);
                    h.post(() -> done.run(null, tx, false));
                } catch (Exception e) { String m = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(); h.post(() -> done.run(m, null, false)); }
            });
        }
    }
    private interface Done { void run(String err, String tx, boolean uncertain); }
}
