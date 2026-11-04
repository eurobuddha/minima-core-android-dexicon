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
import com.eurobuddha.atomix.swap.OtcBook;
import com.eurobuddha.atomix.swap.OtcController;
import com.eurobuddha.atomix.swap.OtcDb;
import com.eurobuddha.atomix.swap.OtcMessage;
import com.eurobuddha.atomix.swap.OtcOffer;
import com.eurobuddha.atomix.swap.PriceOracle;
import com.eurobuddha.atomix.swap.PublishGate;
import com.eurobuddha.dexicon.Notifier;
import com.eurobuddha.dexicon.TradingGate;
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
 * The DOORS host — Dexicon-owned FULL AtomiX host. It wires the vendored AtomiX engine exactly as AtomiX's own
 * SwapService does (ensureDerivations: node-derived ETH key via {@code seedrandom modifier:ethbridge}, never persisted;
 * the persisted swap identity {@code swap_addr/swap_pk} adopted, never self-healed; the node's 64 keys for refunds;
 * IdentityWatch HALT on mismatch), as the taker (① Swap) AND the maker (① Market / OTC): the responder armed with
 * {@link PriceOracle#armSafe}, auto-republish, the price-oracle peg and its withdraw tombstone, OTC republish and
 * responder, the book-presence belt — SwapService's maker, ported. While {@link TradingGate#blocked} (AtomiX or
 * PandaPools present) it publishes and arms NOTHING; settlement continues. The comms identity stays Dexicon's own
 * ({@link com.eurobuddha.dexicon.identity.SeedSource}: the app never reads the seed phrase).
 *
 * ONE process-wide instance (Activity and MakerService share it), so there is one engine, one SwapDb, one nonce journal
 * — AtomiX runs two engines and guards them with statics; Dexicon simply doesn't. {@link MakerService} drives
 * {@link #poll()} every ~90 s with the app closed (claims, ETH withdraws, refunds, forwards); the DOORS tab drives it
 * too while open.
 *
 * ONE AtomiX market at a time, exactly as in AtomiX (owner, 3 Nov 2026): {@link #market()} is USDT ⇄ MxUSD or
 * USDT ⇄ MINIMA, persisted in this host's prefs under AtomiX's own key ({@link TradingContext#PREF_KEY}). Swaps, the
 * book scan, the maker and OTC follow it; {@link #switchMarket} is AtomiX's doSwitchCurrency (park the leaving market's
 * config, tombstone a live market, quiesce, restore the arriving market's config). Settlement is market-agnostic (claims
 * and refunds read each coin's own token; SwapEngine.expectedTokenId attributes by the swap's recorded label), so one
 * poll settles swaps of both markets.
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
    private final OtcDb otcDb;
    private OtcController otc;   // rebuilt on every market selection: the vendored controller pins TradingContext.active() at construction
    private CommsScanner otcScanner;
    private TradingContext otcScannerMarket;
    private boolean incomingLoaded;
    private int orderMisses, otcMisses, polls;
    private long lastReassertMs;
    /** AtomiX MainActivity.REPUBLISH_INTERVAL_MS: keep a published order / OTC offer live + fresh. */
    public static final long REPUBLISH_INTERVAL_MS = 30 * 60_000;

    private TradingContext market;
    /** Why this launch stayed in MINIMA mode (owner Q1, 4 Nov 2026), or null. Shown as a banner on ①. */
    private String launchNote;
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
            @Override public void notify(String title, String body) { Notifier.alert(app, mx("AtomiX · " + title), mx(body)); }
            @Override public void onSwapsChanged() { changed(); }
        });
        rpc = new EthRpc(net.defaultRpc);
        engine.setNetwork(rpc, net);
        // One trading app per phone, inside the engine itself: while the gate is shut (or the market is mid-switch)
        // SwapEngine.halted() refuses every NEW lock — responder legs, publishes, initiator starts, OTC execution —
        // at the moment it would lock. Claims, refunds, withdraws and deals already locked are untouched.
        SwapEngine.setHostGate(() -> TradingGate.blocked(app) || switching);
        PriceOracle.init(prefs);   // the persisted last-good stamp — the withdraw clock survives restarts
        engine.setNoticeSink((to, hh, st, why, mkt) -> {
            TradingContext m = mkt == null ? market : mkt;
            CryptoProvider cp = crypto.get(m); CommsIdentity id = ids.get(m);
            if (cp != null && id != null) SwapTake.notice(node, cp, to, id.publicId(), hh, st, why, m, SwapTake.QUIET);
        });
        otcDb = new OtcDb(app);
        engine.setOtcDb(otcDb);
        for (TradingContext m : MARKETS) { books.put(m, Collections.emptyMap()); coinBal.put(m, null); }
        market = TradingContext.load(prefs);   // AtomiX's own key + default (MxUSD)
        select(market);
        otc = newOtc();   // AFTER the saved market is active (OtcController captures TradingContext.active())
    }

    /** A controller for the market that is active NOW (the vendored OtcController captures it at construction). */
    private OtcController newOtc() {
        return new OtcController(node, otcDb, engine, new OtcController.Ui() {
            @Override public void onDealsChanged() { changed(); }
            @Override public void note(String title, String body) { Notifier.alert(app, mx("AtomiX OTC · " + title), mx(body)); }
        });
    }

    /** The Minima dollar token is MxUSD in everything Dexicon shows (the vendored engine still says mxUSDT). */
    public static String mx(String s) { return s == null ? null : s.replace("mxUSDT", "MxUSD"); }

    /** Tell the Activity (its ACTION_CHANGED receiver repaints) that door state moved. */
    void changed() { try { app.sendBroadcast(new Intent(MakerService.ACTION_CHANGED).setPackage(app.getPackageName())); } catch (Exception ignored) {} }

    /** The engine's market selection — TradingContext (AtomiX's own API) + the Minima token. */
    private void select(TradingContext m) { TradingContext.setActive(m, null); minima.setActiveToken(m.tokenId); }

    // ------------------------------------------------------------------ the one active market (AtomiX's currency switch)

    public TradingContext market() { return market; }
    /** A ⇄ switch is under way (between the call and the flip, up to ~4 s): nothing may be saved, published or agreed,
     *  or a ladder priced for one coin lands on the other (~200× mispriced). */
    private volatile boolean switching;   // read by the engine's host gate on its own threads
    public boolean switching() { return switching; }
    public static final String SWITCHING = "Switching markets — try again in a moment.";
    /** This host's prefs — AtomiX's keys for the ACTIVE market (order_config, auto_publish, peg_*, otc_*). */
    public SharedPreferences prefs() { return prefs; }
    /** AtomiX's Minima side (its consolidate runs here, under the engine's wallet lock). */
    public MinimaHtlc minima() { return minima; }
    public String launchNote() { return market == TradingContext.MINIMA ? launchNote : null; }

    /** Is a swap of {@code m} (either role) still in flight — anything left to claim or refund? */
    public boolean inFlight(TradingContext m) {
        for (SwapDb.Swap s : db.allSwaps())
            if (!com.eurobuddha.atomix.swap.SwapVisibility.isTerminal(s) && TradingContext.forSwap(s.sellToken, s.buyToken) == m) return true;
        return false;
    }

    /** An OTC deal of {@code m} that has not finished (OtcController settles a deal only while its market is active). */
    public boolean otcOpen(TradingContext m) {
        for (OtcDb.Deal d : otcDb.allDeals()) if (!OtcController.isTerminal(d.status) && d.market() == m) return true;
        return false;
    }

    /** An OTC deal of {@code m} at the point where leaving its market would strand it: AGREED (an instigator locks leg 1
     *  next), or EXECUTING with its swap still STARTED or with no swap row yet (an LP has not locked its leg: otcLpDeal
     *  locks only while the deal is in the active market) — the vendored controller drives those steps only while its
     *  market is active. */
    public boolean otcMidExecution(TradingContext m) {
        if (!otcOpen(m)) return false;
        for (OtcDb.Deal d : otcDb.allDeals()) {
            if (d.market() != m || OtcController.isTerminal(d.status)) continue;
            if (OtcDb.ST_AGREED.equals(d.status)) return true;
            if (OtcDb.ST_EXECUTING.equals(d.status)) {
                String st = d.hash == null ? null : engine.swapStatus(d.hash);
                if (st == null || SwapDb.ST_STARTED.equals(st)) return true;
            }
        }
        return false;
    }
    public static final String OTC_MID_EXECUTION = "An OTC deal in this market is being executed (agreed, or its first lock is waiting for the "
            + "other side). Switching now would stop it from progressing — switch once it is locked on both sides.";

    // A withdrawal (tombstone) of a market sent but not yet confirmed: the order is still on the book until it lands — or
    // until it ages off on its own: the book scan reads SCAN_DEPTH (72) blocks back from the tip, and the last live publish
    // was never later than the marker. Aged out by CHAIN TIME (the tip at marking + 72 + 12 blocks of slack); wall time
    // (~70 min) only when no tip was known at marking or is known now.
    static final long WITHDRAW_MARKER_MS = 70 * 60_000;
    static final int WITHDRAW_MARKER_BLOCKS = SwapOrderBook.SCAN_DEPTH + 12;
    /** The Minima tip this host last saw (0 = unknown); tests replace it. */
    java.util.function.IntSupplier tip = this::lastMinimaBlock;
    private void markWithdrawal(TradingContext m) {
        prefs.edit().putBoolean("withdraw_pending_" + m.key, true).putLong("withdraw_pending_at_" + m.key, System.currentTimeMillis())
                .putInt("withdraw_pending_tip_" + m.key, Math.max(0, tip.getAsInt())).apply();
    }
    private void withdrawalLanded(TradingContext m) {
        prefs.edit().remove("withdraw_pending_" + m.key).remove("withdraw_pending_at_" + m.key).remove("withdraw_pending_tip_" + m.key).apply();
    }
    /** A withdrawal of {@code m} is unconfirmed and its order may still be on the book (aged out 84 blocks after it was
     *  set, or ~70 min when the tip is unknown). */
    public boolean withdrawalPending(TradingContext m) {
        if (!prefs.getBoolean("withdraw_pending_" + m.key, false)) return false;
        long at = prefs.getLong("withdraw_pending_at_" + m.key, 0), now = System.currentTimeMillis();
        if (at == 0) { prefs.edit().putLong("withdraw_pending_at_" + m.key, now).apply(); return true; }   // a pre-0.1.78 marker: its clock starts now
        int markTip = prefs.getInt("withdraw_pending_tip_" + m.key, 0), nowTip = tip.getAsInt();
        if (markTip > 0 && nowTip > 0) { if (nowTip < markTip + WITHDRAW_MARKER_BLOCKS) return true; }
        else if (now - at < WITHDRAW_MARKER_MS) return true;
        withdrawalLanded(m);   // the order has aged off the book by itself
        return false;
    }

    /** Re-send the ACTIVE market's unconfirmed withdrawal (each pass) until it lands; a live re-publish supersedes it.
     *  Only the active market: the vendored Order and SwapOrderBook are bound to TradingContext.active() (an order built
     *  now is the active market's and posts to the active book), so another market's tombstone cannot be sent from here —
     *  its marker is kept and re-sent in-market once that market is active again, or ages out. */
    void flushWithdrawal() {
        if (switching || !tombstones.ready(ids.get(market)) || !withdrawalPending(market)) return;
        if (prefs.getBoolean("auto_publish", false)) { withdrawalLanded(market); return; }
        if (!PublishGate.tryAcquire(PublishGate.ORDER)) return;
        final TradingContext m = market;
        tombstones.send(ids.get(m), tombstone(), new CommsTransport.SendCb() {
            @Override public void onSent(String txpowid) { PublishGate.release(PublishGate.ORDER); withdrawalLanded(m); changed(); }
            @Override public void onFailed(String message) { PublishGate.release(PublishGate.ORDER); }
        });
    }

    /** The ACTIVE market has a maker side configured or kept live: a liquid saved order (it arms the responder), the
     *  order kept published, or an OTC offer. Read from the active prefs — they are always the active market's. */
    public boolean makerLive() {
        boolean liquid = false;
        try { liquid = Order.fromConfigJson(prefs.getString("order_config", "")).hasLiquidity(); } catch (Exception ignored) {}
        return liquid || prefs.getBoolean("auto_publish", false) || prefs.getBoolean("otc_enable", false) || prefs.getBoolean("otc_auto", false);
    }

    /**
     * LAUNCH RULE (owner Q1, 4 Nov 2026): every launch opens in MxUSD mode — EXCEPT while a USDT ⇄ MINIMA swap is in
     * flight or the MINIMA maker market is live: then it stays in MINIMA mode with a banner saying why. A live market is
     * never flipped silently. Returns the banner text, or null.
     */
    public String applyLaunchRule() {
        launchNote = null;
        if (market != TradingContext.MINIMA) return null;
        // an open OTC deal counts as in flight (the engine settles OTC legs only in the ACTIVE market), and so does a
        // withdrawal whose tombstone has not landed (the order is still on the book until it does)
        boolean swap = inFlight(TradingContext.MINIMA) || otcOpen(TradingContext.MINIMA), live = makerLive() || withdrawalPending(TradingContext.MINIMA);
        if (swap || live) {
            launchNote = "Opened in MINIMA mode: " + (swap && live ? "a USDT ⇄ MINIMA swap or OTC deal is in flight and your MINIMA market is live"
                    : swap ? "a USDT ⇄ MINIMA swap or OTC deal is in flight" : "your MINIMA market is live or its withdrawal has not landed yet")
                    + ". Dexicon never switches a live market by itself. Use the ⇄ pill to go back to MxUSD when you are ready.";
            return launchNote;
        }
        if (busy || scanning) return null;   // never re-select under a running take or scan
        switchMarket(TradingContext.MXUSDT, null);   // nothing live: config parked, MxUSD config restored
        return null;
    }

    /**
     * AtomiX's doSwitchCurrency (apks/atomix MainActivity), on this host's prefs. Returns an error, or null when the
     * switch is under way; {@code done} runs once it has happened.
     *
     * FUND-SAFETY (AtomiX's words): the priced market state (ladder, peg config, oracle price) is NOT per-currency, so
     * the market is QUIESCED before flipping — otherwise the previous currency's ladder/price would bleed into the new
     * currency's book at a ~100–200× mispriced rate. After a switch the market is OFF unless the arriving currency has a
     * parked config of its own. A live leaving market is tombstoned first (current identity, current sentinel).
     */
    public String switchMarket(final TradingContext target, final Runnable done) {
        if (switching) return SWITCHING;
        if (target == market) { if (done != null) done.run(); return null; }
        if (busy || scanning) return "An AtomiX swap is starting or the book is being read — try again in a moment.";
        if (otcMidExecution(market)) return OTC_MID_EXECUTION;
        final TradingContext current = market;
        switching = true;
        com.eurobuddha.atomix.swap.PublishGate.bumpGen();   // void queued publications NOW, before the tombstone (and again at the flip)
        final boolean[] ran = {false};
        final Runnable[] watchdog = new Runnable[1];
        final Runnable proceed = () -> {
            if (ran[0]) return; ran[0] = true;
            if (watchdog[0] != null) h.removeCallbacks(watchdog[0]);
            snapshotMarket(prefs, current.key);   // the leaving config as it is NOW (nothing could change it while switching)
            com.eurobuddha.atomix.swap.PublishGate.bumpGen();   // queued publications of the leaving market are void
            market = target;
            TradingContext.setActive(target, prefs);
            select(target);
            otc = newOtc();   // the old controller still pins the leaving market
            prefs.edit().remove("order_config").putBoolean("auto_publish", false)
                    .putBoolean(com.eurobuddha.atomix.swap.PriceOracle.P_ENABLE, false)
                    .putBoolean(com.eurobuddha.atomix.swap.PriceOracle.P_WITHDRAWN, true)
                    .remove("last_publish_ok").putBoolean("otc_enable", false).putBoolean("otc_auto", false)
                    .remove("otc_last_publish_ok").apply();
            com.eurobuddha.atomix.swap.PriceOracle.resetForSwitch(prefs);
            try { engine.setMyOrder(new Order()); } catch (Exception ignored) {}   // disarm now
            restoreMarket(prefs, target.key);
            for (TradingContext m : MARKETS) books.put(m, Collections.emptyMap());
            otcScanner = null; otcScannerMarket = null; orderMisses = 0; otcMisses = 0;
            launchNote = null;
            dropIncoming(current);
            switching = false;
            changed();
            if (done != null) done.run();
        };
        watchdog[0] = proceed;
        CommsIdentity id = ids.get(current);
        if (makerLive() && tombstones.ready(id)) {
            try {
                Order o = Order.fromConfigJson(prefs.getString("order_config", ""));
                for (Order.Pair p : o.pairs.values()) p.enable = false;
                o.minimaPublicKey = prefs.getString("swap_pk", "");
                o.ethAddress = wallet.address();
                markWithdrawal(current);
                tombstones.send(id, o, new CommsTransport.SendCb() {
                    @Override public void onSent(String txpowid) { withdrawalLanded(current); h.post(proceed); }
                    @Override public void onFailed(String message) { h.post(proceed); }   // it ages off the book (~1 h)
                });
                h.postDelayed(proceed, 4000);   // never block the switch on a hung post
            } catch (Exception e) { proceed.run(); }
        } else proceed.run();
        return null;
    }

    /** The switch's tombstone path: can the leaving market be signed for, and send it. A field only so a test can
     *  stand in for the chain (the real one is AtomiX's SwapOrderBook.publish). */
    interface Tombstones { boolean ready(CommsIdentity id); void send(CommsIdentity id, Order o, CommsTransport.SendCb cb); }
    Tombstones tombstones = new Tombstones() {
        @Override public boolean ready(CommsIdentity id) { return id != null && minima.ready() && wallet.ready(); }
        @Override public void send(CommsIdentity id, Order o, CommsTransport.SendCb cb) { SwapOrderBook.publish(node, ls(), id, o, cb); }
    };
    SwapDb swapDb() { return db; }
    /** AtomiX's Market history for the ACTIVE market (the engine's MarketCollector records prints network-wide). */
    public List<SwapDb.MarketTrade> executedTrades(int n) { return db.executedTrades(n, market.tokenId); }
    public List<SwapDb.MarketTrade> recentTrades(int n) { return db.recentTrades(n, market.tokenId); }
    OtcDb otcDb() { return otcDb; }

    // Per-currency market memory (AtomiX MainActivity.MKT_STR / MKT_BOOL): the active prefs are always the active
    // currency's; on a switch the leaving config is PARKED under its own key and the arriving one RESTORED. Config only —
    // runtime/price state starts fresh so the peg re-fetches the target currency's own price.
    private static final String[] MKT_STR = { "order_config", com.eurobuddha.atomix.swap.PriceOracle.P_STEP, com.eurobuddha.atomix.swap.PriceOracle.P_SIZE,
            com.eurobuddha.atomix.swap.PriceOracle.P_ASK_SIZE, com.eurobuddha.atomix.swap.PriceOracle.P_BID_SIZE, com.eurobuddha.atomix.swap.PriceOracle.P_BIAS,
            com.eurobuddha.atomix.swap.PriceOracle.P_REPRICE, com.eurobuddha.atomix.swap.PriceOracle.P_LEVELS, "otc_sell_size", "otc_buy_size" };
    private static final String[] MKT_BOOL = { "auto_publish", com.eurobuddha.atomix.swap.PriceOracle.P_ENABLE, "otc_enable", "otc_auto" };

    static void snapshotMarket(SharedPreferences prefs, String curKey) {
        try {
            JSONObject j = new JSONObject();
            for (String k : MKT_STR) { String v = prefs.getString(k, null); if (v != null) j.put(k, v); }
            for (String k : MKT_BOOL) j.put(k, prefs.getBoolean(k, false));
            prefs.edit().putString("market_" + curKey, j.toString()).apply();
        } catch (Exception ignored) {}
    }

    static void restoreMarket(SharedPreferences prefs, String targetKey) {
        String raw = prefs.getString("market_" + targetKey, null);
        if (raw == null) return;
        SharedPreferences.Editor e = prefs.edit();
        try {
            JSONObject j = new JSONObject(raw);
            for (String k : MKT_STR) if (j.has(k)) e.putString(k, j.getString(k));
            for (String k : MKT_BOOL) if (j.has(k)) e.putBoolean(k, j.getBoolean(k));
            e.putBoolean(com.eurobuddha.atomix.swap.PriceOracle.P_WITHDRAWN, false).putBoolean(com.eurobuddha.atomix.swap.PriceOracle.P_TOMBSTONED, false);
        } catch (Exception ignored) {}
        e.apply();
    }

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
                @Override public void ok(String address) { walletDeriving = false; stepDone(); }
                @Override public void err(String msg) { walletDeriving = false; bootNote = "ETH key: " + mx(msg); }   // never log key material
            });
        }
        if (!minima.ready() && !minimaSettingUp) {
            minimaSettingUp = true;
            minima.setup(prefs.getString("swap_addr", ""), prefs.getString("swap_pk", ""), new MinimaHtlc.SetupCb() {
                @Override public void ok(String a, String pk) {
                    minimaSettingUp = false;
                    prefs.edit().putString("swap_addr", a).putString("swap_pk", pk).apply();
                    engine.setMyMinimaPk(pk);
                    stepDone();
                }
                @Override public void err(String m) { minimaSettingUp = false; bootNote = "Minima swap key: " + mx(m); }
            });
        }
        if (minima.ready() && !keysLoaded && !keysLoading) {
            keysLoading = true;
            minima.loadMyKeys(new MinimaHtlc.KeysCb() {
                @Override public void ok(java.util.Set<String> keys) { keysLoading = false; keysLoaded = !keys.isEmpty(); engine.setMyPubkeys(keys); if (keysLoaded) stepDone(); }
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
    /** A boot step finished: start the next one NOW instead of on the next ~90 s pass (four dependent steps used to
     *  take minutes on a first start), and read the book the moment everything is ready. */
    private void stepDone() {
        ensure();
        if (ready() && !bookReadAfterBoot) { bookReadAfterBoot = true; scanBooks(this::changed); readBalances(this::changed); }
        changed();
    }
    private boolean bookReadAfterBoot;

    private void deriveIdentities() {
        identityDeriving = true;
        // one-way input (SeedSource: the phrase never enters the app). A swap already in flight keeps the old (vault seed)
        // identity it was opened with until it settles — CLAUDE.md: never change identity under a live swap.
        boolean legacy = !prefs.getBoolean("door_ids_v2", false) && !db.activeHashes().isEmpty();
        com.eurobuddha.dexicon.identity.SeedSource.Cb got = new com.eurobuddha.dexicon.identity.SeedSource.Cb() {
            @Override public void got(String ikm) {
                if (!legacy) prefs.edit().putBoolean("door_ids_v2", true).apply();
                io.execute(() -> {
                    try {
                        byte[] seed = ikm.startsWith("0x") ? Hex.from(ikm) : ikm.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                        Map<TradingContext, CommsIdentity> got = new EnumMap<>(TradingContext.class);
                        for (TradingContext m : MARKETS) got.put(m, CommsIdentity.fromSeed(ls(), seed, m.hkdfContext));
                        h.post(() -> {
                            ids.putAll(got);
                            for (TradingContext m : MARKETS) crypto.put(m, new LocalEcCryptoProvider(ls(), got.get(m)));
                            identityDeriving = false; stepDone();
                        });
                    } catch (Exception e) { h.post(() -> { identityDeriving = false; bootNote = "Identity derive failed"; }); }
                });
            }
            @Override public void failed(String m) { identityDeriving = false; bootNote = "Identity: " + mx(m); }
        };
        if (legacy) com.eurobuddha.dexicon.identity.SeedSource.legacy(node, got);
        else com.eurobuddha.dexicon.identity.SeedSource.derive(node, com.eurobuddha.dexicon.identity.SeedSource.DOORS, got);
    }

    public boolean ready() { return wallet.ready() && minima.ready() && keysLoaded && ids.size() == MARKETS.length; }
    public String bootNote() { return ready() ? "" : bootNote; }
    public String ethAddress() { return wallet.address(); }
    /** AtomiX's Wallet (send / export key) works on the same node-derived wallet and connection the swaps use. */
    public EthWallet wallet() { return wallet; }
    public EthRpc rpc() { return rpc; }
    public String myPublicId(TradingContext m) { CommsIdentity i = ids.get(m); return i == null ? null : i.publicId(); }
    public Map<String, Order> book(TradingContext m) { return books.get(m); }
    public String coinBalance(TradingContext m) { return coinBal.get(m); }
    public boolean busy() { return busy; }

    // ------------------------------------------------------------------ background pass (MakerService ~90 s, DOORS tab)

    /** One settlement pass: boot steps, then the engine's poll (claims / withdraws / refunds), maker notices, forwards.
     *  With the node unpaired (or not booted yet) it falls back to {@link #pollEthOnly}: an ETH refund needs no node. */
    private long lastPollMs;
    public void poll() {
        long now = System.currentTimeMillis();
        if (now - lastPollMs < 30_000) return;   // SwapService.tickPass's overlap guard: DOORS tab + MakerService both drive this
        lastPollMs = now;
        ensure();
        if (!dnode.isEnabled() || !ready()) { engine.pollEthOnly(); return; }
        // SwapService.tickPass, minus its FOREGROUND stand-down: Dexicon has ONE engine. Mid-sweep the sweep owns the
        // ETH nonce and the coin selection, so nothing else may submit.
        if (!com.eurobuddha.atomix.MainActivity.SWEEP_ACTIVE) {
            loadIncoming();
            armMaker();   // re-arm the responder guard from the PERSISTED config on every pass, before poll()
            withdrawOnConflict();
            boolean live = prefs.getBoolean("auto_publish", false);
            if (prefs.getBoolean(PriceOracle.P_ENABLE, false) && live) { PriceOracle.refreshAsync(); maybePegWithdraw(); }
            engine.poll();
            flushWithdrawal();   // a withdrawal, allowed even while gated
            if (!gated()) {
                maybeAutoRepublish();
                maybeRepublishOtc();
                if (live) engine.maintainLadderCoins(true);
            }
        }
        discover();
        if (polls++ % 10 == 0 && (makerLive() || prefs.contains("last_publish_ok"))) reconcileBookPresence();   // the belt, ~15 min
        processForwards();
    }

    /** SwapService's ~30 s discovery tick: buy requests to my market and OTC messages, so a taker is seen in about a
     *  block instead of a 90 s poll. MakerService drives it between polls. */
    public void discover() {
        if (!dnode.isEnabled() || !ready()) return;
        if (!com.eurobuddha.atomix.MainActivity.SWEEP_ACTIVE) { armMaker(); withdrawOnConflict(); }   // the gate / halt as of NOW
        scanTake();
        scanOtc();
    }

    /** Settlement with no Minima node: ETH refunds and withdraws of door swaps (the engine checks each ETH contract). */
    public void pollEthOnly() { ensure(); engine.pollEthOnly(); }

    /** Ask for a refund of my lock now; the answer (why not yet, or "refunding") comes back on the main thread. */
    public void refundNow(String hash, java.util.function.Consumer<String> answer) { engine.refundNow(hash, answer); }
    /** AtomiX checkNow: advance the swap and run the engine's live inspection of both chains (report on the main thread). */
    public void inspect(String hash, java.util.function.Consumer<java.util.List<String>> report) { engine.poll(); engine.inspect(hash, report::accept); }
    public int lastMinimaBlock() { return engine.lastMinimaBlock(); }

    /** The market's TAKE channel carries buyers' sealed hashlocks to a maker and makers' "retrying / declined" notices
     *  to a taker. Scanned for the ACTIVE market while my maker side is configured, and for any market while a swap I
     *  started is still waiting for its maker. */
    private void scanTake() {
        boolean waiting = false, maker = makerLive();
        for (SwapDb.Swap s : swaps()) if (SwapDb.ST_STARTED.equals(s.status)) { waiting = true; break; }
        for (TradingContext m : MARKETS) {
            if (!waiting && !(maker && m == market)) continue;
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

    /** Accepted only when sealed to MY id for that market by the sender it names (AtomiX routeTakeRequest). A notice is
     *  informational (the engine checks it is the maker I notified); a buy request is recorded for the ACTIVE market
     *  only, and the responder guard ({@link #armMaker}) decides whether anything locks. */
    boolean routeNotice(TradingContext m, Opened opened) {
        try {
            JSONObject j = new JSONObject(new String(opened.plaintext, java.nio.charset.StandardCharsets.UTF_8));
            String to = j.optString("to", ""), from = j.optString("from", ""), hash = j.optString("hash", ""), me = myPublicId(m);
            if (hash.isEmpty() || me == null || !me.equals(to)) return false;
            if (opened.fromPublicId == null || !opened.fromPublicId.equals(from)) return false;
            if (j.has("notice")) return engine.onMakerNotice(hash, from, j.optString("notice"), j.optString("reason"));
            if (m != market || switching) return false;
            java.util.Set<String> set = incomingHashlocks();
            boolean isNew = true;
            for (String e : set) if (hashOf(e).equals(hash)) { isNew = false; break; }
            if (isNew) { set.add(hash + "|" + market.key); prefs.edit().putString("incoming_hashlocks", android.text.TextUtils.join(",", set)).apply(); }
            engine.addIncomingHashlock(hash, from);
            if (isNew && !com.eurobuddha.atomix.MainActivity.SWEEP_ACTIVE) { armMaker(); engine.checkBuyNow(hash); }   // act now, on the guard as of now
            if (isNew && !gated() && !IdentityWatch.halted())   // gated / halted: the guard declines it, so promise no lock
                Notifier.alert(app, "AtomiX · Buy request received", "A buyer wants your " + DoorPlan.coin(market) + " — finding their USDT lock, then locking.");
            return isNew;
        } catch (Exception e) { return false; }
    }

    /** incoming_hashlocks entries are "hash|market" (0.1.67); a bare hash is from before the tag. */
    private static String hashOf(String e) { int i = e.indexOf('|'); return i < 0 ? e : e.substring(0, i); }
    private static String marketOf(String e) { int i = e.indexOf('|'); return i < 0 ? "" : e.substring(i + 1); }

    /** The leaving market's persisted buy requests are dropped, so a restart never re-arms them under the other market
     *  (untagged legacy ones too: their market is unknown). The engine's in-memory set has no remove API — see report. */
    private void dropIncoming(TradingContext leaving) {
        java.util.Set<String> set = incomingHashlocks();
        boolean changed = false;
        for (java.util.Iterator<String> it = set.iterator(); it.hasNext(); ) {
            String k = marketOf(it.next());
            if (k.isEmpty() || k.equals(leaving.key)) { it.remove(); changed = true; }
        }
        if (changed) prefs.edit().putString("incoming_hashlocks", android.text.TextUtils.join(",", set)).apply();
    }

    private java.util.Set<String> incomingHashlocks() {
        java.util.Set<String> set = new java.util.HashSet<>();
        for (String x : prefs.getString("incoming_hashlocks", "").split(",")) if (!x.isEmpty()) set.add(x);
        return set;
    }

    /** AtomiX loadIncoming: re-arm persisted handshakes once per process, pruning the ones that fully resolved. */
    private void loadIncoming() {
        if (incomingLoaded) return;
        incomingLoaded = true;
        java.util.Set<String> set = incomingHashlocks();
        boolean pruned = false;
        for (java.util.Iterator<String> it = set.iterator(); it.hasNext(); ) {
            String e = it.next(), x = hashOf(e), k = marketOf(e);
            SwapDb.Swap s = db.getSwap(x);
            if (s != null && com.eurobuddha.atomix.swap.SwapVisibility.isTerminal(s)) { it.remove(); pruned = true; }
            else if (k.equals(market.key)) engine.addIncomingHashlock(x);   // only the ACTIVE market's; untagged = market unknown
        }
        if (pruned) prefs.edit().putString("incoming_hashlocks", android.text.TextUtils.join(",", set)).apply();
    }

    // ------------------------------------------------------------------ the maker (AtomiX SwapService, ported)

    /** One trading app per phone: while AtomiX / PandaPools is present Dexicon publishes and arms NOTHING. The same rule
     *  is wired into the engine (SwapEngine.setHostGate in the constructor), so a lock that would start after the gate
     *  shut — e.g. a local OTC Accept whose ACCEPT confirms late — is refused where it would lock. */
    public boolean gated() { return TradingGate.blocked(app); }

    public Order loadOrder() { return Order.fromConfigJson(prefs.getString("order_config", "")); }

    /** My Minima swap key (the persisted, never self-healed {@code swap_pk}), once the HTLC side is set up. */
    String myPk() { String pk = prefs.getString("swap_pk", ""); return minima.ready() && !pk.isEmpty() ? pk : null; }

    /** Everything a publish signs with is ready: the active market's comms identity, the swap key, the ETH address. */
    boolean makerReady() { return ids.get(market) != null && myPk() != null && wallet.ready(); }

    /** The responder guard: AtomiX's armSafe order (empty while a pegged order is withdrawn) — or EMPTY while gated,
     *  halted, or not kept published (auto_publish off: a saved, unpublished, unpegged ladder must never answer a take at
     *  stale prices), so every take declines. */
    public Order armMaker() {
        Order o = gated() || IdentityWatch.halted() || !prefs.getBoolean("auto_publish", false) ? new Order() : PriceOracle.armSafe(loadOrder(), prefs);
        engine.setMyOrder(o);
        return o;
    }

    /** The Activity saw the gate open or shut: re-arm the responder guard and withdraw a live market NOW, not next pass. */
    public void gateChanged() {
        if (com.eurobuddha.atomix.MainActivity.SWEEP_ACTIVE) return;
        armMaker();
        withdrawOnConflict();
    }

    /**
     * AtomiX or PandaPools was FOUND (TradingGate.conflict — never a merely unread MiniHub): my live ① market is withdrawn
     * AT ONCE — guard disarmed, auto-publish off, tombstoned now (the belt's hourly cap bypassed for this one) — and my OTC
     * offer too, so no buyer locks USDT for 2 h against a maker that will decline. The user places them again once
     * trading is back. An unconfirmed tombstone is re-sent by {@link #flushWithdrawal} every pass.
     */
    void withdrawOnConflict() {
        if (!TradingGate.conflict(app) || switching) return;
        boolean otcOn = prefs.getBoolean("otc_enable", false) || prefs.getBoolean("otc_auto", false);
        boolean marketOn = prefs.getBoolean("auto_publish", false) && tombstones.ready(ids.get(market));
        if (!otcOn && !marketOn) return;
        // ONE generation bump for both withdrawals, before either is sent: a second bump (the old withdrawOtcOffer after the
        // tombstone) voided the tombstone already in flight.
        if (otcOn) prefs.edit().putBoolean("otc_enable", false).putBoolean("otc_auto", false).apply();
        if (marketOn) {
            prefs.edit().putBoolean("auto_publish", false).apply();   // kill-safe first
            Order off = loadOrder();          // persisted disabled, exactly as withdrawOrder: a reopened gate re-arms nothing
            for (Order.Pair p : off.pairs.values()) p.enable = false;
            saveOrder(off);
        }
        PublishGate.bumpGen();                // an in-flight publish must not re-list either
        if (otcOn) sendOtcWithdrawal(s -> {});
        if (!marketOn) return;
        engine.setMyOrder(new Order());       // disarm now, even if the tombstone fails
        final TradingContext m0 = market;
        markWithdrawal(m0);
        lastReassertMs = System.currentTimeMillis();   // this is the belt's tombstone, sent now
        if (PublishGate.tryAcquire(PublishGate.ORDER))   // one ORDER publish at a time: flushWithdrawal cannot duplicate it
            tombstones.send(ids.get(m0), tombstone(), new CommsTransport.SendCb() {
                @Override public void onSent(String txpowid) { PublishGate.release(PublishGate.ORDER); withdrawalLanded(m0); changed(); }
                @Override public void onFailed(String message) { PublishGate.release(PublishGate.ORDER); /* flushWithdrawal re-sends it */ }
            });
        // else a (now void) publish still holds the slot: the marker is set, flushWithdrawal sends it next pass
        Notifier.alert(app, "AtomiX · market withdrawn", "AtomiX or PandaPools is on this phone, so your " + DoorPlan.coin(m0)
                + " market was taken off the book. Place it again once trading is back.");
    }

    /** What a confirmed publish may arm: the order, unless the gate shut or the keys were lost while it was in flight. */
    private Order armable(Order o) { return gated() || IdentityWatch.halted() ? new Order() : o; }

    /** The saved order with my identities attached and every pair disabled: a tombstone (freshest-per-signer wins). */
    Order tombstone() {
        Order o = loadOrder();
        for (Order.Pair p : o.pairs.values()) p.enable = false;
        o.minimaPublicKey = myPk(); o.ethAddress = wallet.address();
        return o;
    }

    private double usdtAvail() { try { return Double.parseDouble(prefs.getString("usdt_avail", "0")); } catch (Exception e) { return 0; } }

    /** SwapService.maybeAutoRepublish: keep the published order live and its size current (~30 min, fresh sendable),
     *  or NOW when the pegged price moved past the threshold / the feed just recovered. Runs off {@code last_publish_ok}
     *  (stamped ONLY on a confirmed publish), so a failed attempt retries on the next pass. */
    private void maybeAutoRepublish() {
        if (switching || IdentityWatch.halted() || gated() || !makerReady()) return;
        if (!prefs.getBoolean("auto_publish", false)) return;
        long okStamp = prefs.getLong("last_publish_ok", 0);
        boolean reprice = PriceOracle.shouldReprice(prefs, okStamp);
        long interval = orderMisses >= 2 ? 60 * 60_000 : REPUBLISH_INTERVAL_MS;   // belt throttle while publishes don't land
        if (!reprice && System.currentTimeMillis() - okStamp < interval) return;
        final Order o = loadOrder();
        o.minimaPublicKey = myPk(); o.ethAddress = wallet.address(); o.usdtAvail = usdtAvail();
        boolean anyEnabled = false;
        for (Order.Pair p : o.pairs.values()) if (p.enable) { anyEnabled = true; break; }
        if (!anyEnabled) return;
        if (!PublishGate.tryAcquire(PublishGate.ORDER)) return;
        final boolean restored = prefs.getBoolean(PriceOracle.P_WITHDRAWN, false);
        final int peg = PriceOracle.applyPeg(o, prefs);
        if (peg == PriceOracle.PEG_STALE) { PublishGate.release(PublishGate.ORDER); return; }   // no usable price — withdraw runs on the pass
        final boolean wide = peg == PriceOracle.PEG_WIDE;
        final double pegMid = PriceOracle.appliedMid();
        final long gen = PublishGate.gen();
        final CommsIdentity id = ids.get(market);
        engine.ensureLadderCoins(o, !com.eurobuddha.atomix.MainActivity.SWEEP_ACTIVE, () -> {
            // a withdraw/edit/tombstone during the coin reads must not be out-freshened on-chain by this stale ladder
            if (gen != PublishGate.gen() || gated()) { PublishGate.release(PublishGate.ORDER); return; }
            engine.publishFresh(ls(), id, o, gen, new CommsTransport.SendCb() {
                @Override public void onSent(String txpowid) {
                    if (gen != PublishGate.gen()) { PublishGate.release(PublishGate.ORDER); return; }
                    prefs.edit().putLong("last_publish_ok", System.currentTimeMillis()).apply();
                    if (peg == PriceOracle.PEG_APPLIED || wide) PriceOracle.commitPeg(prefs, pegMid, wide);   // baseline moves only on a CONFIRMED publish
                    engine.setMyOrder(armable(o));
                    PublishGate.release(PublishGate.ORDER);
                    changed();
                    if (restored && peg == PriceOracle.PEG_APPLIED && o.hasLiquidity())
                        Notifier.alert(app, "AtomiX · " + PriceOracle.source() + " feed recovered", "Your pegged " + DoorPlan.coin(market) + " order is live again.");
                }
                @Override public void onFailed(String message) { PublishGate.release(PublishGate.ORDER); }   // retried next pass
            });
        });
    }

    // ------------------------------------------------------------------ ① Market's own actions (AtomiX MainActivity)

    /** One status line back to the page (AtomiX's orderStatus), already in Dexicon's words (MxUSD). */
    public interface Status { void say(String s); }

    /** Why nothing may be published right now, or null. */
    private String makerBlock() {
        if (switching) return SWITCHING;
        if (gated()) return "One trading app per phone: AtomiX or PandaPools is present, so Dexicon publishes nothing.";
        if (IdentityWatch.halted()) return "Halted: this node no longer owns the keys AtomiX uses here.";
        if (!ready() || !makerReady()) return "Still connecting to your node… " + bootNote();
        return null;
    }

    /** Save the editor's order and arm the responder guard with it at once (AtomiX saveOrder). */
    public boolean saveOrder(Order o) {
        if (switching) return false;
        prefs.edit().putString("order_config", Order.toConfigJson(o)).apply();
        armMaker();
        return true;
    }

    /** AtomiX publishOrder: a pegged order grabs a live price first so a tapped Publish never quotes a stale one. */
    public void publishOrder(Status st) {
        String no = makerBlock();
        if (no != null) { st.say(no); return; }
        if (prefs.getBoolean(PriceOracle.P_ENABLE, false) && !PriceOracle.fresh()) {
            st.say("Fetching " + PriceOracle.source() + " price…");
            final TradingContext m = market;
            final long generation = PublishGate.gen();
            io.execute(() -> { PriceOracle.refreshSync(); h.post(() -> {
                if (m != market || generation != PublishGate.gen()) { st.say(SwapOrderBook.MARKET_ERROR); return; }
                publishOrderNow(st);
            }); });
            return;
        }
        publishOrderNow(st);
    }

    private void publishOrderNow(Status st) {
        String no = makerBlock();
        if (no != null) { st.say(no); return; }
        final Order o = loadOrder();
        o.minimaPublicKey = myPk(); o.ethAddress = wallet.address(); o.usdtAvail = usdtAvail();
        boolean any = false;
        for (Order.Pair p : o.pairs.values()) if (p.enable) { any = true; break; }
        if (!any) { st.say("Enable your order in the editor first."); return; }
        final int peg = PriceOracle.applyPeg(o, prefs);
        if (peg == PriceOracle.PEG_STALE) { st.say("✗ " + PriceOracle.source() + " price unavailable — can't peg yet (no price)"); return; }
        final boolean wide = peg == PriceOracle.PEG_WIDE;
        final double pegMid = PriceOracle.appliedMid();
        PublishGate.bumpGen();   // manual supersedes auto
        final long gen = PublishGate.gen();
        st.say("Publishing your order…");
        final CommsIdentity id = ids.get(market);
        engine.ensureLadderCoins(o, !com.eurobuddha.atomix.MainActivity.SWEEP_ACTIVE, () -> {
            String late = makerBlock();   // the gate may have shut (or the keys gone) during the coin reads
            if (late != null) { st.say(late); return; }
            engine.publishFresh(ls(), id, o, gen, new CommsTransport.SendCb() {
            @Override public void onSent(String txpowid) {
                if (gen != PublishGate.gen()) return;
                // opt in to keep it live + fresh ONLY once it is on the book (a failed or voided publish leaves it off)
                prefs.edit().putLong("last_publish_ok", System.currentTimeMillis()).putBoolean("auto_publish", true).apply();
                if (peg == PriceOracle.PEG_APPLIED || wide) PriceOracle.commitPeg(prefs, pegMid, wide);
                engine.setMyOrder(armable(o));
                st.say(!o.hasLiquidity() ? "✓ Order withdrawn — removed from the book" : wide
                        ? "✓ Order live — pegged to " + PriceOracle.source() + " (feed stale — defensive spread; auto-tightens when it recovers)"
                        : peg == PriceOracle.PEG_APPLIED ? "✓ Order live — pegged to " + PriceOracle.source() + ", auto-reprices"
                        : "✓ Order published — auto-refreshes every 30 min");
                changed();
                h.postDelayed(() -> scanBooks(DoorHost.this::changed), 2000);
            }
            @Override public void onFailed(String message) { st.say("Publish failed: " + mx(message)); }
            });
        });
    }

    /** AtomiX pushOrderEdit: a saved edit reaches the book NOW. A liquid order re-publishes; an emptied/disabled one goes
     *  out as a TOMBSTONE and stops auto-publishing. Withdrawing is allowed even while gated. */
    public void pushOrderEdit(final Order o, Status st) {
        if (switching) { st.say(SWITCHING); return; }
        final boolean live = o.hasLiquidity();
        if (!live) prefs.edit().putBoolean("auto_publish", false).apply();   // first, kill-safe
        if (live) { String no = makerBlock(); if (no != null) { st.say("Saved. " + no); return; } }
        else if (!makerReady()) { st.say("Saved and switched off. The withdrawal goes out once the node is connected."); return; }
        o.minimaPublicKey = myPk(); o.ethAddress = wallet.address(); o.usdtAvail = usdtAvail();
        final int peg = live ? PriceOracle.applyPeg(o, prefs) : PriceOracle.PEG_OFF;
        if (peg == PriceOracle.PEG_STALE) { st.say("Saved. " + PriceOracle.source() + " price unavailable — publishes when a price arrives."); return; }
        final boolean wide = peg == PriceOracle.PEG_WIDE;
        final double pegMid = PriceOracle.appliedMid();
        PublishGate.bumpGen();
        final long gen = PublishGate.gen();
        st.say(live ? "Updating your order…" : "Withdrawing your order…");
        final CommsIdentity id = ids.get(market);
        final TradingContext m0 = market;
        if (!live) markWithdrawal(m0);
        final CommsTransport.SendCb cb = new CommsTransport.SendCb() {
            @Override public void onSent(String txpowid) {
                if (gen != PublishGate.gen()) return;
                prefs.edit().putLong("last_publish_ok", System.currentTimeMillis()).apply();
                if (peg == PriceOracle.PEG_APPLIED || wide) PriceOracle.commitPeg(prefs, pegMid, wide);
                engine.setMyOrder(live ? armable(o) : new Order());
                if (!live) withdrawalLanded(m0);
                st.say(live ? "✓ Order updated" : "✓ Order withdrawn — removed from the book");
                changed();
                h.postDelayed(() -> scanBooks(DoorHost.this::changed), 2000);
            }
            @Override public void onFailed(String message) { st.say((live ? "Update" : "Withdraw") + " failed: " + mx(message)); }
        };
        if (live) engine.ensureLadderCoins(o, !com.eurobuddha.atomix.MainActivity.SWEEP_ACTIVE, () -> {
            String late = makerBlock();   // the gate may have shut during the coin reads: a live order is not published
            if (late != null) { st.say("Saved. " + late); return; }
            engine.publishFresh(ls(), id, o, gen, cb);
        });
        else SwapOrderBook.publish(node, ls(), id, o, cb);   // tombstone: no coins/balance read needed
    }

    /** Withdraw my market: the saved order disabled, armed empty at once, tombstoned on the book. */
    public void withdrawOrder(Status st) {
        if (switching) { st.say(SWITCHING); return; }
        Order o = loadOrder();
        for (Order.Pair p : o.pairs.values()) p.enable = false;
        saveOrder(o);
        pushOrderEdit(o, st);
    }

    // ------------------------------------------------------------------ ① OTC's own actions (AtomiX MainActivity)

    private Map<String, OtcOffer> otcBoard = Collections.emptyMap();
    public Map<String, OtcOffer> otcBoard() { return otcBoard; }
    public OtcController otc() { return otc; }
    public List<OtcDb.Deal> otcDeals() { return otcDb.allDeals(); }

    /** The active market's OTC availability board, my own offer left out (AtomiX scanOtc). Also drives the receiver. */
    public void scanOtcBoard(Runnable done) {
        if (!ready() || !makerReady()) { if (done != null) done.run(); return; }
        final TradingContext m = market;
        final String me = "0x" + Hex.to(ids.get(m).signPk);
        OtcBook.scan(node, ls(), book -> {
            if (m == market) { book.remove(me); otcBoard = new LinkedHashMap<>(book); }
            if (done != null) done.run();
        }, err -> { if (done != null) done.run(); });
        scanOtc();
    }

    /** AtomiX publishOtcOffer: go live (or update) for OTC with these sizes; the background keeps it on the board. */
    public void publishOtcOffer(double sell, double buy, Status st) {
        if (switching) { st.say(SWITCHING); return; }
        if (!Double.isFinite(sell) || !Double.isFinite(buy) || sell < 0 || buy < 0 || (sell <= 0 && buy <= 0)) { st.say("Enter a sell and/or buy size"); return; }
        String no = makerBlock();
        if (no != null) { st.say(no); return; }
        PublishGate.bumpGen();   // new OTC terms invalidate queued publications
        prefs.edit().putString("otc_sell_size", sell > 0 ? plain(sell) : "").putString("otc_buy_size", buy > 0 ? plain(buy) : "")
                .putBoolean("otc_enable", true).putBoolean("otc_auto", true).apply();
        if (!PublishGate.tryAcquire(PublishGate.OTC)) {
            // a keep-alive (old sizes) is in flight: zero the stamp so the next pass republishes the NEW sizes
            prefs.edit().putLong("otc_last_publish_ok", 0).apply();
            st.say("Publishing busy — your sizes go live shortly");
            return;
        }
        final long gen = PublishGate.gen();
        OtcBook.publish(node, ls(), ids.get(market), otcOffer(true, sell, buy), new CommsTransport.SendCb() {
            @Override public void onSent(String txpowid) {
                PublishGate.release(PublishGate.OTC);
                if (gen != PublishGate.gen()) return;
                prefs.edit().putLong("otc_last_publish_ok", System.currentTimeMillis()).apply();
                st.say("✓ You're live for OTC");
                changed();
            }
            @Override public void onFailed(String message) { PublishGate.release(PublishGate.OTC); st.say("OTC publish failed: " + mx(message)); }
        });
    }

    /** AtomiX withdrawOtcOffer: off at once, then an empty offer on the board. Allowed even while gated. */
    public void withdrawOtcOffer(Status st) {
        if (switching) { st.say(SWITCHING); return; }
        PublishGate.bumpGen();
        prefs.edit().putBoolean("otc_enable", false).putBoolean("otc_auto", false).apply();
        sendOtcWithdrawal(st);
    }

    /** The empty offer on the board, under the current generation (the caller bumped it and switched OTC off). */
    private void sendOtcWithdrawal(Status st) {
        if (!makerReady()) { st.say("OTC switched off."); return; }
        OtcBook.publish(node, ls(), ids.get(market), otcOffer(false, 0, 0), new CommsTransport.SendCb() {
            @Override public void onSent(String txpowid) { st.say("✓ OTC offer withdrawn"); changed(); }
            @Override public void onFailed(String message) { st.say("OTC switched off; the withdrawal did not post (" + mx(message) + ") — it ages off the board."); }
        });
    }

    /** AtomiX otcCheckFunds: can I fund the leg I would lock (my role + the deal's side)? null = OK or unknown. */
    public String otcCheckFunds(String role, String side, String amount, String price) {
        if (!OtcController.validTerms(amount, price)) return "Enter amount + price";
        double amt = num(amount), pr = num(price);
        boolean iLockCoin = (OtcDb.ROLE_INSTIGATOR.equals(role) && OtcOffer.LP_BUYS_MINIMA.equals(side)) || (OtcDb.ROLE_LP.equals(role) && OtcOffer.LP_SELLS_MINIMA.equals(side));
        if (iLockCoin) {
            String have = coinBal.get(market);
            if (have == null) return null;   // unknown → the on-chain lock is the gate
            if (amt > num(have) + 1e-9) return "Not enough " + DoorPlan.coin(market) + " — need " + plain(amt) + ", have " + have;
        } else {
            if (usdtRaw == null) return null;
            double have = new BigDecimal(usdtRaw, 6).doubleValue(), need = amt * pr;
            if (need > have + 1e-9) return "Not enough USDT — need " + plain(need) + ", have " + plain(have);
        }
        return null;
    }

    /** Plain decimals for OTC sizes/terms (AtomiX trim / trimSig). */
    public static String plain(double v) { return BigDecimal.valueOf(v).stripTrailingZeros().toPlainString(); }
    public static String sig(double v) { return Double.isFinite(v) ? new BigDecimal(v, new java.math.MathContext(10)).stripTrailingZeros().toPlainString() : ""; }

    /** SwapService.maybeRepublishOtc: the OTC offer ages off the board (~1 h) exactly like the order. */
    private void maybeRepublishOtc() {
        if (switching || IdentityWatch.halted() || gated() || !makerReady()) return;
        if (!prefs.getBoolean("otc_auto", false)) return;
        long okStamp = prefs.getLong("otc_last_publish_ok", 0);
        long interval = otcMisses >= 2 ? 60 * 60_000 : REPUBLISH_INTERVAL_MS;
        if (System.currentTimeMillis() - okStamp < interval) return;
        if (!PublishGate.tryAcquire(PublishGate.OTC)) return;
        final long gen = PublishGate.gen();
        OtcOffer o = otcOffer(true, num(prefs.getString("otc_sell_size", "0")), num(prefs.getString("otc_buy_size", "0")));
        OtcBook.publish(node, ls(), ids.get(market), o, new CommsTransport.SendCb() {
            @Override public void onSent(String txpowid) {
                PublishGate.release(PublishGate.OTC);
                if (gen != PublishGate.gen()) return;   // a user's new-sizes tap must not be over-stamped by this keep-alive
                prefs.edit().putLong("otc_last_publish_ok", System.currentTimeMillis()).apply();
            }
            @Override public void onFailed(String message) { PublishGate.release(PublishGate.OTC); }
        });
    }

    OtcOffer otcOffer(boolean enable, double sell, double buy) {
        OtcOffer o = new OtcOffer();
        o.sellSize = enable ? Math.max(0, sell) : 0; o.buySize = enable ? Math.max(0, buy) : 0; o.enable = enable;
        o.minimaPublicKey = myPk(); o.ethAddress = wallet.address(); o.commsPublicId = ids.get(market).publicId();
        return o;
    }
    static double num(String s) { try { double d = Double.parseDouble(s); return Double.isFinite(d) ? d : 0; } catch (Exception e) { return 0; } }

    /** SwapService.maybePegWithdraw — FUND-SAFETY, last resort: only when there is NO usable price at all and the feed has
     *  been down past the window, tombstone the order and DISARM the guard. With a last-good mid the peg stays live at a
     *  widened defensive spread instead. auto_publish stays on either way. A withdrawal is allowed even while gated. */
    private void maybePegWithdraw() {
        if (switching || !makerReady()) return;
        final boolean withdrawn = prefs.getBoolean(PriceOracle.P_WITHDRAWN, false);
        if (withdrawn && prefs.getBoolean(PriceOracle.P_TOMBSTONED, false)) return;   // tombstone confirmed — done
        if (PriceOracle.mid() > 0 && !PriceOracle.feedDownPastCeiling()) return;
        if (!withdrawn && !PriceOracle.feedDownPastLimit()) return;
        final boolean first = !withdrawn;
        if (first) prefs.edit().putBoolean(PriceOracle.P_WITHDRAWN, true).putBoolean(PriceOracle.P_TOMBSTONED, false).apply();   // kill-safe
        PublishGate.bumpGen();            // an in-flight auto publish must not re-arm the ladder
        engine.setMyOrder(new Order());   // disarm NOW, even if the publish fails
        final TradingContext m0 = market;
        markWithdrawal(m0);
        SwapOrderBook.publish(node, ls(), ids.get(market), tombstone(), new CommsTransport.SendCb() {
            @Override public void onSent(String txpowid) { prefs.edit().putBoolean(PriceOracle.P_TOMBSTONED, true).apply(); withdrawalLanded(m0); changed(); }
            @Override public void onFailed(String message) { /* retried next pass */ }
        });
        if (first) Notifier.alert(app, "AtomiX · " + PriceOracle.source() + " price feed lost",
                "Your pegged " + DoorPlan.coin(market) + " order was withdrawn for safety. It re-publishes automatically when the feed recovers.");
    }

    /** OTC negotiation receiver (SwapService.scanOtc): deals progress with the app closed. Scanned while my offer is on
     *  or a deal is still open. The offer is never advertised as enabled while gated. */
    private void scanOtc() {
        if (!makerReady()) return;
        CommsIdentity id = ids.get(market);
        otc.setSelf(crypto.get(market), id, myPk(), wallet.address());
        otc.setMyOffer(prefs.getBoolean("otc_enable", false) && !gated() && !IdentityWatch.halted(),
                num(prefs.getString("otc_sell_size", "0")), num(prefs.getString("otc_buy_size", "0")));
        boolean open = prefs.getBoolean("otc_enable", false);
        if (!open) for (OtcDb.Deal d : otcDb.allDeals()) if (!OtcController.isTerminal(d.status)) { open = true; break; }
        if (!open) return;
        if (otcScanner == null || otcScannerMarket != market) {
            otcScannerMarket = market;
            otcScanner = new CommsScanner(node, crypto.get(market), new CommsScanner.MetaStore() {
                @Override public String getMeta(String k, String def) { return prefs.getString("otcmeta_" + k, def); }
                @Override public void setMeta(String k, String v) { prefs.edit().putString("otcmeta_" + k, v).apply(); }
            }, OtcMessage.address(), this::routeOtc, (ok, n) -> {});
        }
        otcScanner.scan(0);
        otc.expireStale(System.currentTimeMillis());
    }

    /** OTC routing (SwapService's otc::route) — except, while gated or halted, a message that STARTS execution (an ACCEPT,
     *  on which an instigator locks leg 1; an EXECUTE, on which an LP locks its counter-leg) is not routed. Deals already
     *  EXECUTING with a hash keep settling (the engine's poll, not these messages, does that). */
    boolean routeOtc(String coinid, Opened opened, JSONObject coin) {
        if ((gated() || IdentityWatch.halted()) && startsExecution(opened)) return false;
        return otc.route(coinid, opened, coin);
    }

    static boolean startsExecution(Opened opened) {
        OtcMessage m = opened == null ? null : OtcMessage.fromWire(opened.plaintext);
        return m != null && (OtcMessage.ACCEPT.equals(m.type) || OtcMessage.EXECUTE.equals(m.type));
    }

    /**
     * SwapService.reconcileBookPresence — the book-presence belt. The ok-stamp can lie (a vault-locked node answers
     * pending, an accepted txn is orphaned): if my freshest coin is missing/stale while the stamp claims a recent
     * success, ZERO the stamp so the next pass republishes. Inversely, while the market is cancelled/withdrawn and my
     * freshest coin still shows liquidity, re-assert the tombstone.
     */
    private void reconcileBookPresence() {
        if (!makerReady()) return;
        final String me = "0x" + Hex.to(ids.get(market).signPk);
        final long genAtScan = PublishGate.gen();
        final TradingContext m = market;
        SwapOrderBook.scan(node, ls(), book -> {
            if (m != market) return;
            Order mine = book.get(me);
            boolean autoPub = prefs.getBoolean("auto_publish", false), withdrawn = prefs.getBoolean(PriceOracle.P_WITHDRAWN, false);
            if (autoPub && !withdrawn && !TradingGate.conflict(app)) checkPresence(mine == null ? 0 : mine.ts, "last_publish_ok", true);
            else if (mine != null && mine.hasLiquidity() && genAtScan == PublishGate.gen()) reassertTombstone();
        }, err -> {});
        if (prefs.getBoolean("otc_auto", false)) OtcBook.scan(node, ls(), book -> {
            if (m != market) return;
            OtcOffer mine = book.get(me);
            checkPresence(mine == null ? 0 : mine.ts, "otc_last_publish_ok", false);
        }, err -> {});
    }

    private void reassertTombstone() {
        if (!makerReady() || com.eurobuddha.atomix.MainActivity.SWEEP_ACTIVE) return;
        long now = System.currentTimeMillis();
        if (now - lastReassertMs < 60 * 60_000) return;   // hourly cap
        lastReassertMs = now;
        if (!PublishGate.tryAcquire(PublishGate.ORDER)) return;
        SwapOrderBook.publish(node, ls(), ids.get(market), tombstone(), new CommsTransport.SendCb() {
            @Override public void onSent(String txpowid) { PublishGate.release(PublishGate.ORDER); }
            @Override public void onFailed(String message) { PublishGate.release(PublishGate.ORDER); }
        });
    }

    private void checkPresence(long coinTs, String stampKey, boolean isOrder) {
        long now = System.currentTimeMillis(), stamp = prefs.getLong(stampKey, 0);
        if (stamp == 0 || now - stamp >= REPUBLISH_INTERVAL_MS) return;   // republish already due
        if (now - stamp < 5 * 60_000) return;   // a just-confirmed publish takes ~1 block to appear
        boolean present = coinTs > 0 && now - coinTs < REPUBLISH_INTERVAL_MS + 10 * 60_000;
        if (present) { if (isOrder) orderMisses = 0; else otcMisses = 0; return; }
        int misses = isOrder ? ++orderMisses : ++otcMisses;
        prefs.edit().putLong(stampKey, 0).apply();   // force a republish on the next pass
        String c = DoorPlan.coin(market);
        if (misses == 2) Notifier.alert(app, "AtomiX · " + c + (isOrder ? " market" : " OTC offer") + " isn't landing on-chain",
                "Your " + c + (isOrder ? " market updates" : " OTC offer") + " may be stuck in the node's Pending list — is the vault locked? Open your Minima node and check Pending.");
    }

    // ------------------------------------------------------------------ reads

    /** The ACTIVE market's AtomiX book. */
    public void scanBooks(Runnable done) {
        if (scanning || busy || !ready()) { if (done != null) done.run(); return; }
        scanning = true;
        final TradingContext m = market;
        SwapOrderBook.scan(node, ls(), book -> { if (m == market) books.put(m, new LinkedHashMap<>(book)); scanning = false; if (done != null) done.run(); },
                err -> { scanning = false; if (done != null) done.run(); });
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
            h.post(() -> {
                ethWei = fe; usdtRaw = fu; gasWei = fg; baseFeeWei = fb;
                if (fu != null) prefs.edit().putString("usdt_avail", new BigDecimal(fu, 6).toPlainString()).apply();   // the background republish's size
                if (done != null) done.run();
            });
        });
    }
    /** Wei to reserve for {@code gasLimit} at the price EthTx will actually broadcast at; null when gas is unreadable. */
    public BigInteger gasReserve(BigInteger gasLimit) {
        return gasWei == null || gasWei.signum() <= 0 ? null : EthSend.gasReserveWei(gasWei, baseFeeWei, gasLimit);
    }

    // ------------------------------------------------------------------ door swaps (SwapDb is the source of truth)

    /** Every swap this node started (INITIATOR), newest first. */
    /** Every AtomiX swap on this node: mine as taker AND my market's fills as maker (AtomiX mySwapsList shows both; this
     *  was taker-only from phase 1, before Dexicon could make a market, so maker fills were never shown — 0.1.96). */
    public List<SwapDb.Swap> swaps() { return new ArrayList<>(db.allSwaps()); }

    /** AtomiX dismissedSwaps (same pref key): cards the user hid from the LIVE lists. UI only — the engine keeps driving
     *  the swap, so funds actually locked on-chain still auto-refund at their timelock. History still lists them. */
    public java.util.Set<String> dismissed() {
        java.util.Set<String> set = new java.util.HashSet<>();
        for (String h : prefs.getString("dismissed_swaps", "").split(",")) if (!h.isEmpty()) set.add(h);
        return set;
    }
    public void dismiss(String hash) {
        java.util.Set<String> set = dismissed(); set.add(hash);
        prefs.edit().putString("dismissed_swaps", android.text.TextUtils.join(",", set)).apply();
    }
    /** Every swap the live lists show (Swap, Activity): all but the ones the user hid. */
    public List<SwapDb.Swap> liveListSwaps() {
        java.util.Set<String> hidden = dismissed();
        List<SwapDb.Swap> out = new ArrayList<>();
        for (SwapDb.Swap s : db.allSwaps()) if (!hidden.contains(s.hash)) out.add(s);
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
        if (switching) return SWITCHING;
        if (!ready()) return "AtomiX not ready — " + bootNote();
        if (IdentityWatch.halted()) return "Halted: this node no longer owns the keys AtomiX uses here.";
        if (gated()) return "Trading is off: one trading app per phone.";
        if (busy || scanning) return "An AtomiX swap is already starting — wait for it to finish.";
        if (plan == null || plan.legs.isEmpty()) return "Nothing to fill.";
        if (market != this.market) return "The market was switched — review the swap again.";
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
        if (gated()) { endTake(pr, "Stopped at part " + (i + 1) + ": trading is off (one trading app per phone). Nothing more was started.", true); return; }
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
                            @Override public void onFailed(String message) { Notifier.alert(app, "AtomiX", "USDT locked, but telling the maker failed (" + mx(message) + ") — swap " + hash); }
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
                if (busy) endTake(pr, "AtomiX stopped at part " + (i + 1) + "/" + total + ": " + mx(msg), true);
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
            signedSend(node, "send amount:" + amt.stripTrailingZeros().toPlainString() + " address:" + to + " tokenid:" + token, done);
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
    interface Done { void run(String err, String tx, boolean uncertain); }

    /** The door forward's `send`. It signs on the node, so it runs behind SignGate like every other signer (CommsTransport,
     *  MinimaHtlc) — now that fill co-signs run concurrently — freeing the gate first thing in both callbacks; and through
     *  the comms shim, so its node-picked inputs pass the pool owner-key guard. */
    static void signedSend(NodeApi node, String cmd, Done done) {
        com.eurobuddha.comms.SignGate.submit(gate -> node.cmd(cmd, new NodeApi.Cb() {
            @Override public void onResult(JSONObject j) {
                gate.free();
                if (j.optBoolean("status", false)) { JSONObject r = j.optJSONObject("response"); done.run(null, r == null ? "" : r.optString("txpowid", ""), false); return; }
                String e = j.optString("error", "");
                if (e.isEmpty()) e = j.optString("message", "");
                if (e.isEmpty()) { Object r = j.opt("response"); e = r == null ? "command failed" : String.valueOf(r); }
                done.run(e, null, false);
            }
            @Override public void onError(String m) {
                gate.free();
                // A lost reply to a `send` may still have gone through — never resend that automatically. A pool owner-coin
                // guard refusal never reached the node: never uncertain, whatever its words.
                boolean unknown = m != null && !com.eurobuddha.pandapools.DexiconPools.refusal(m) && (m.contains("may still complete") || m.contains("didn't respond") || m.contains("Outcome is unknown"));
                done.run(m, null, unknown);
            }
        }));
    }
}
