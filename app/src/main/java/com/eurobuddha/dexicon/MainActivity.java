package com.eurobuddha.dexicon;

import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.eurobuddha.dexicon.envelope.Envelope;
import com.eurobuddha.dexicon.envelope.Envelopes;
import com.eurobuddha.dexicon.fill.Coin;
import com.eurobuddha.dexicon.fill.FillPlan;
import com.eurobuddha.dexicon.fill.FillTxn;
import com.eurobuddha.dexicon.fill.Tokens;
import com.eurobuddha.dexicon.identity.DexIdentity;
import com.eurobuddha.dexicon.identity.SeedSource;
import com.eurobuddha.dexicon.identity.Sodium;
import com.eurobuddha.dexicon.maker.MakerEngine;
import com.eurobuddha.dexicon.maker.MakerService;
import com.eurobuddha.dexicon.maker.MyOrder;
import com.eurobuddha.dexicon.relay.HttpTransport;
import com.eurobuddha.dexicon.relay.RelayClient;
import com.eurobuddha.dexicon.relay.RelayStore;
import com.eurobuddha.dexicon.resting.BookRepository;
import com.eurobuddha.dexicon.resting.CompositeRouter;
import com.eurobuddha.dexicon.resting.DexContract;
import com.eurobuddha.dexicon.resting.DexTxn;
import com.eurobuddha.dexicon.resting.Order5;
import com.eurobuddha.dexicon.resting.Pool;
import com.eurobuddha.dexicon.resting.PoolLiquidityRepository;
import com.eurobuddha.dexicon.resting.PoolRouter;
import com.eurobuddha.dexicon.resting.PriceMath;
import com.eurobuddha.dexicon.resting.SourcePick;
import com.eurobuddha.dexicon.resting.SweepPlanner;

import org.json.JSONArray;
import org.json.JSONObject;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Dexicon host (PandaDEX MainActivity shape): first paint from SQLite before the node exists, our own 30 s balance
 * clock, a continuous relay long-poll for the book while foreground, immediate repaint after every action, only the
 * visible tab renders. The maker side lives in {@link MakerService}; this Activity is the TAKER and the order desk.
 */
public class MainActivity extends com.eurobuddha.pandapools.MainActivity {

    public static volatile boolean FOREGROUND = false;
    private static final long POLL_MS = 30_000;
    public static final int TAB_TRADE = 0, TAB_ACTIVITY = 1, TAB_ORDERS = 2, TAB_HISTORY = 3, TAB_ASSETS = 4, TAB_DOORS = 5, TAB_RELAYS = 6, TAB_POOLS = 7, TAB_MARKET = 8, TAB_OTC = 9, TAB_PSWAP = 10, TAB_PMINE = 11;
    /** The journey (owner, 3 Nov 2026): ① dollars in (AtomiX) → ② pools (optional) → ③ exchange, picked in a one-line
     *  switcher at the top. ONE bottom bar, AtomiX's own (icon over label): the room's three pages, then ACTIVITY /
     *  ASSETS / SETTINGS shared by all three — six tabs, nothing to tap above AND below (owner, 4 Nov). */
    static final String[] ROOMS = {"① Dollars in", "② Pools", "③ Exchange"};
    static final int[][] ROOM_PAGES = {{TAB_DOORS, TAB_MARKET, TAB_OTC}, {TAB_PSWAP, TAB_POOLS, TAB_PMINE}, {TAB_TRADE, TAB_ORDERS, TAB_HISTORY}};
    private static final int[] SHARED = {TAB_ACTIVITY, TAB_ASSETS, TAB_RELAYS};
    static final String[] TAB_NAMES = {"Trade", "Activity", "Orders", "History", "Assets", "Swap", "Settings", "Pools", "Market", "OTC", "Swap", "My pools"};
    private static final int[] TAB_ICONS = {R.drawable.ic_tab_trade, R.drawable.ic_tab_activity, R.drawable.ic_tab_orders, R.drawable.ic_tab_history,
            R.drawable.ic_tab_wallet, R.drawable.ic_tab_swap, R.drawable.ic_tab_settings, R.drawable.ic_tab_pools, R.drawable.ic_tab_market,
            R.drawable.ic_tab_otc, R.drawable.ic_tab_swap, R.drawable.ic_tab_mypools};

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService net = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "dexicon-ui-net"); t.setDaemon(true); return t; });
    /** PandaDEX TradeView quoteExec: composite routing is CPU work, never on the UI thread. */
    private final ExecutorService quoteExec = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "dexicon-quote"); t.setDaemon(true); return t; });

    // RESTING layer (vendored PandaDEX 0.4.23): V5 covenant book + PandaPools, taken through DexTxn.fillComposite.
    private BookRepository restingRepo;
    private PoolLiquidityRepository poolRepo;
    private DexTxn restingTxn;
    private boolean v5Ready, v5Registering;

    private NodeApi node;
    private DexDb db;
    private RelayClient relay;
    private DexIdentity me;
    private MakerEngine engine;
    private boolean paired, pairingKnown, deriving, bookPolling, busy, closed;
    private long chainBlock;
    private String receiveAddr = "", receiveMx = "";   // hex = the resting-layer payout; Mx = what the user sees and shares (PandaDEX: miniaddress, falling back to hex)
    private BigDecimal minimaSendable = BigDecimal.ZERO, mxusdSendable = BigDecimal.ZERO, minimaConfirmed = BigDecimal.ZERO, mxusdConfirmed = BigDecimal.ZERO;
    private long balanceAtMs;
    private String stage = "";
    /** The ACTIVITY row the TRADE stage line follows live (null = plain stage text only). */
    private String liveActId;
    private BroadcastReceiver changedReceiver, nodeReceiver;
    private ActivityResultLauncher<String> saveCsv;
    private String pendingCsv;
    /** One trading app per phone: the gate replaces every trading screen while AtomiX / PandaPools is present. */
    private View chromeRoot;
    private GateView gate;
    private Boolean gateShown;
    private ActivityResultLauncher<Intent> handover;
    /** The hand-over asked for (persisted across process death: the answer can come back to a new process). */
    String handoverPkg, handoverName;
    private boolean handoverRemove;
    private long handoverAt;
    /** Bumped each time the gate shuts: an automatic retry captured before it never fires after it. */
    private int gateGen;
    /** Open trade / market dialogs: dismissed when the gate shuts or the market switches under them. */
    private final List<android.app.Dialog> dialogs = new ArrayList<>();

    private TradeView trade;
    private ActivityTab activityTab;
    private OrdersTab ordersTab;
    private HistoryTab historyTab;
    private AssetsTab assetsTab;
    private RelaysTab relaysTab;
    private DoorsTab doorsTab;
    private PoolsTab poolsTab;
    private PoolSwapTab poolSwapTab;
    private MyPoolsTab myPoolsTab;     // ② My pools: PandaPools' MyLpView + ActivityView (vendored)   // ② Swap: PandaPools' SwapView (vendored), framed by Dexicon's explainer
    private MarketTab marketTab;
    private OtcTab otcTab;
    private LinearLayout content;          // the visible page(s): one, or two side by side on a wide screen
    private ScrollView[] panes;
    private LinearLayout tabBar, rail;
    private final LinearLayout[] tabCell = new LinearLayout[TAB_NAMES.length];
    private final android.widget.ImageView[] tabIcon = new android.widget.ImageView[TAB_NAMES.length];
    private final TextView[] tabBtn = new TextView[TAB_NAMES.length];
    private final TextView[] roomBtn = new TextView[3];
    private int room = 0;
    private final int[] roomLast = {TAB_DOORS, TAB_POOLS, TAB_TRADE};
    private TextView pairPill, blockPill, stageTv, ccyPill;
    /** The ⇄ pill's glow (AtomiX startCcyGlow): an Animator is not a Handler callback, so it is cancelled explicitly. */
    private android.animation.ValueAnimator ccyGlowAnim;
    private int tab = TAB_DOORS, tab2 = -1;   // every launch opens on ① (owner, 4 Nov 2026)   // tab2: the right-hand page when two are open side by side (wide only)
    private int tabsDown; private int heldTab = -1; private boolean pairMade;

    private final Runnable pollTask = new Runnable() {
        @Override public void run() {
            if (paired) { readBalances(); readBlock(); }
            // Not yet enabled in Minima Core → Apps: ask again every tick, so enabling it there pairs this screen
            // without a restart (the maker service already re-registers; the Activity did not — seen on-device 2026-10-21).
            else if (node != null && pairingKnown) { node.reRegister(); repaintHeader(); }
            ui.postDelayed(this, paired ? POLL_MS : 3_000);   // unpaired: notice the node's Apps toggle within seconds
        }
    };

    /** Seconds-level clock for live rows ("waiting for the maker (12s)"): the header every second while a trade is
     *  followed, the ACTIVITY tab every 5 s while it is open. Block-level changes arrive via ACTION_CHANGED. */
    private int liveTicks;
    private final Runnable liveTask = new Runnable() {
        @Override public void run() {
            if (FOREGROUND) {
                if (liveActId != null) repaintHeader();
                followTake();
                if (run != null && trade != null) trade.paintCtaPublic();
                if ((tab == TAB_ACTIVITY || tab2 == TAB_ACTIVITY) && ++liveTicks % 5 == 0) activityTab.render();
            }
            ui.postDelayed(this, 1000);
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Design.load(this);
        nightModeFollowsDesign();
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        db = new DexDb(this);
        relay = new RelayClient(new HttpTransport());
        applySettings();
        saveCsv = registerForActivityResult(new ActivityResultContracts.CreateDocument("text/csv"), uri -> {
            if (uri == null || pendingCsv == null) return;
            try (java.io.OutputStream o = getContentResolver().openOutputStream(uri)) { o.write(pendingCsv.getBytes(java.nio.charset.StandardCharsets.UTF_8)); toast("CSV saved"); }
            catch (Exception e) { toast("Could not save: " + e.getMessage()); }
            pendingCsv = null;
        });
        handover = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), r -> onHandover(r.getResultCode(), r.getData()));
        if (savedInstanceState != null) {
            handoverPkg = TradingGate.gatedApp(savedInstanceState.getString("ho_pkg"));
            handoverName = savedInstanceState.getString("ho_name"); handoverRemove = savedInstanceState.getBoolean("ho_remove");
            handoverAt = savedInstanceState.getLong("ho_at");
        }
        com.eurobuddha.dexicon.doors.DoorHost.get(this).applyLaunchRule();   // MxUSD every launch, unless MINIMA is live (Q1)
        // The node first: ② Pools' vendored PandaPools screens capture the pool engine (on this ONE connection) when built.
        node = new NodeApi(this, enabled -> {
            paired = enabled; pairingKnown = true;
            if (enabled) onPaired();
            repaint();
            applyGate();   // the "Checking your node…" screen says what is needed while unpaired
        });
        restingRepo = new BookRepository(node);
        poolRepo = new PoolLiquidityRepository(node);
        restingTxn = new DexTxn(node);
        restingRepo.subscribe((orders, syncing) -> repaint());
        poolRepo.subscribe((pools, syncing) -> repaint());
        initPools(node);
        chromeRoot = buildChrome();
        // AtomiX: Back from any other page returns to Swap; from Swap it leaves as usual.
        getOnBackPressedDispatcher().addCallback(this, new androidx.activity.OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() {
                if (!Boolean.TRUE.equals(gateShown) && (tab != TAB_DOORS || tab2 >= 0)) { selectTab(TAB_DOORS); return; }
                setEnabled(false); getOnBackPressedDispatcher().onBackPressed(); setEnabled(true);
            }
        });
        selectTab(tab);
        if (getIntent() != null && getIntent().getIntExtra(EXTRA_POOLS_PAGE, -1) >= 0) goToTab(getIntent().getIntExtra(EXTRA_POOLS_PAGE, -1));   // a pool notification
        applyGate();

        changedReceiver = new BroadcastReceiver() { @Override public void onReceive(Context c, Intent i) { applyGate(); followTake(); settleStage(); repaint(); retryIfDue(); restIfDue(); warmWallet(); } };
        ContextCompat.registerReceiver(this, changedReceiver, new IntentFilter(MakerService.ACTION_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED);
        nodeReceiver = new BroadcastReceiver() { @Override public void onReceive(Context c, Intent i) { if (paired) { readBalances(); readBlock(); } } };
        ContextCompat.registerReceiver(this, nodeReceiver, new IntentFilter(NodeTransportService.EVENT_ACTION), ContextCompat.RECEIVER_NOT_EXPORTED);
        if (android.os.Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 91);
        try { ContextCompat.startForegroundService(this, new Intent(this, MakerService.class)); } catch (Exception ignored) {}
        ui.postDelayed(pollTask, 3_000);
        ui.postDelayed(liveTask, 1000);
    }

    /** Relay list + dev token override are shared prefs; re-read whenever they may have changed. */
    void applySettings() {
        relay.setRelays(RelayStore.load(this));
        Tokens.setMxUsd(RelayStore.mxusdOverride(this));
    }

    private void onPaired() {
        TradingGate.checkMiniHub(this, node, this::applyGate);
        ensureKeepAlive();   // weekly battery-exemption ask for pool hosts and AtomiX makers (no-op otherwise)
        poolsPaired();   // ② Pools: PandaPools' boot (sentinel untrack, re-track my pools, foreign-tracking hygiene)
        readBalances(); readBlock();
        if (me == null) deriveIdentity();
        refreshResting();
    }

    /** BOOK/POOL use the mainnet MxUSD id baked into the V5 covenant and the pool scan; a dev dollar-token
     *  override (Settings) means there is no such liquidity on this chain, so the layer stays off. */
    boolean restingOn() { return Tokens.MXUSD_MAINNET.equalsIgnoreCase(Tokens.MXUSD()); }

    /** PandaDEX MainActivity order: verify + register the V5 covenant (trackall:false) once, then scan book and pools.
     *  Both repositories are single-flight and throttled (4 s book, 8 s pools), so calling this per block is safe. */
    private void refreshResting() {
        if (!paired || !restingOn() || restingRepo == null) return;
        if (!v5Ready) {
            if (v5Registering) return;
            v5Registering = true;
            DexContract.ensureScript(node, new DexContract.Ready() {
                @Override public void ok() { v5Registering = false; v5Ready = true; refreshResting(); }
                @Override public void failed(String why) { v5Registering = false; setStage("BOOK layer unavailable: " + why); }
            });
            return;
        }
        restingRepo.refresh();
        poolRepo.refresh();
    }

    Map<String, Order5> restingBook() { return restingRepo == null || !restingOn() ? java.util.Collections.emptyMap() : restingRepo.book(); }
    /** PandaDEX MainActivity.pools(): only a current scan is tradeable liquidity. */
    List<Pool> restingPools() { return poolRepo == null || !restingOn() || !poolRepo.current() ? java.util.Collections.emptyList() : poolRepo.pools(); }

    private void deriveIdentity() {
        if (deriving) return;
        deriving = true;
        SeedSource.derive(node, SeedSource.DEX, new SeedSource.Cb() {   // one-way: the node's phrase never enters the app
            public void got(String seed) {
                net.execute(() -> {
                    try {
                        DexIdentity id = DexIdentity.fromSeed(Sodium.get(), seed);
                        ui.post(() -> { me = id; engine = new MakerEngine(MainActivity.this, node, db, relay, me); deriving = false; repaint(); });
                    } catch (Exception e) { ui.post(() -> { deriving = false; setStage("Identity derivation failed: " + e.getMessage()); }); }
                });
            }
            public void failed(String m) { deriving = false; setStage(NodeApi.ERR_NOT_ENABLED.equals(m) ? "Enable Dexicon in Minima Core → Apps" : NodeApi.ERR_NOT_ADMIN.equals(m) ? "Dexicon needs ADMIN in Minima Core → Apps. Nothing runs without it." : "Identity: " + m + ". Unlock and reopen."); }
        });
    }

    private void readBlock() {
        node.cmd("block", new NodeApi.Cb() {
            public void onResult(JSONObject j) {
                JSONObject r = j.optJSONObject("response");
                long was = chainBlock;
                try { chainBlock = Long.parseLong(r.optString("block", "0")); } catch (Exception ignored) {}
                Tokens.noteBlock(chainBlock); Tokens.setMxUsd(RelayStore.mxusdOverride(MainActivity.this));   // the override only on a proven test chain
                restingRepo.setChainBlock(chainBlock); restingTxn.setChainBlock(chainBlock);
                poolsBlock(chainBlock);   // ② Pools: confirmations + one shared pool scan per new block (PandaPools pollBlock)
                if (chainBlock != was) { refreshResting(); warmMakers(); warmWallet(); }   // book + pools follow the chain, once per new block
                repaintHeader();
            }
            public void onError(String m) {}
        });
    }

    private void readBalances() {
        node.cmd("balance", new NodeApi.Cb() {
            public void onResult(JSONObject j) {
                for (JSONObject b : NodeApi.rows(j)) {
                    String tok = b.optString("tokenid", "");
                    if (Tokens.isMinima(tok)) { minimaSendable = Util.dec(b.optString("sendable", "0")); minimaConfirmed = Util.dec(b.optString("confirmed", "0")); minimaCoins = b.optInt("coins", 0); }
                    else if (Tokens.isMxUsd(tok)) { mxusdSendable = Util.dec(b.optString("sendable", "0")); mxusdConfirmed = Util.dec(b.optString("confirmed", "0")); mxusdCoins = b.optInt("coins", 0); }
                }
                balanceAtMs = System.currentTimeMillis();
                repaint();
            }
            public void onError(String m) {}
        });
        if (receiveAddr.isEmpty()) node.cmd("getaddress", new NodeApi.Cb() {
            public void onResult(JSONObject j) { JSONObject r = j.optJSONObject("response"); if (r != null) { receiveAddr = r.optString("address", ""); receiveMx = r.optString("miniaddress", receiveAddr); } restingTxn.setPayout(receiveAddr); repaint(); }
            public void onError(String m) {}
        });
    }

    // ---- book long-poll (foreground only) ----
    private void bookLoop() {
        if (closed || !FOREGROUND || bookPolling) return;
        if (relay.relays().isEmpty()) { ui.postDelayed(this::bookLoop, 3000); return; }
        bookPolling = true;
        net.execute(() -> {
            int changed = relay.pollBook(25);
            ui.post(() -> { bookPolling = false; if (closed) return; repaint(); if (changed > 0) warmMakers(); ui.postDelayed(this::bookLoop, changed < 0 ? 2000 : 0); });
        });
    }

    // ---- accessors for the tabs ----
    DexDb db() { return db; }
    RelayClient relay() { return relay; }
    DexIdentity identity() { return me; }
    long tip() { return chainBlock; }
    boolean isBusy() { return busy; }
    boolean paired() { return paired; }
    int minimaCoins, mxusdCoins;   // wallet coin counts from `balance` (ASSETS › consolidate / split)
    NodeApi nodeApi() { return node; }
    void refreshBalances() { if (node != null) readBalances(); }
    String receiveAddress() { return receiveMx.isEmpty() ? receiveAddr : receiveMx; }
    BigDecimal minimaSendable() { return minimaSendable; }
    BigDecimal mxusdSendable() { return mxusdSendable; }
    BigDecimal minimaConfirmed() { return minimaConfirmed; }
    BigDecimal mxusdConfirmed() { return mxusdConfirmed; }
    long balanceAtMs() { return balanceAtMs; }
    List<Envelope> book() { return relay.orders(null); }
    List<MyOrder> myOrders() { return db.myOrders(); }
    boolean isMine(Envelope e) { return me != null && e.signPk.equalsIgnoreCase(me.signPkHex()); }

    public void toast(String s) { Toast.makeText(this, here(s), Toast.LENGTH_SHORT).show(); }
    /** The vendored PandaDEX layer (verbatim) tells you to consolidate "in MinimaCore"; Dexicon has it on ASSETS. */
    static String here(String s) {
        return s == null ? null : s.replace("consolidate this token in MinimaCore", "consolidate this token on ASSETS › CONSOLIDATE")
                .replace("Use MinimaCore consolidation", "Use ASSETS › CONSOLIDATE").replace("Consolidate funding", "Consolidate on ASSETS")
                .replace("consolidate coins and retry", "consolidate on ASSETS and retry");
    }
    void setStage(String s) { stage = s == null ? "" : here(s); repaintHeader(); }
    public void copy(String label, String value) {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null || value == null || value.isEmpty()) return;
        cm.setPrimaryClip(ClipData.newPlainText(label, value));   // the COMPLETE value, never an abbreviation
        toast(label + " copied");
    }

    /** The node app (its Apps screen holds Dexicon's toggle). */
    void openNodeApp() {
        String pkg = NodeApi.nodeApp(this)[0];
        android.content.Intent open = pkg.isEmpty() ? null : getPackageManager().getLaunchIntentForPackage(pkg);
        if (open != null) startActivity(open); else toast("Install or start your Minima node app first");
    }

    /** One trading app per phone: every confirmed or automatic trade asks here first (MakerEngine asks again). */
    boolean gateRefuses() {
        if (!TradingGate.blocked(this)) return false;
        toast("Trading is off: one trading app per phone (see the first screen)");
        return true;
    }

    /** Shows {@code d} and remembers it, so the gate or a market switch can close it under the user. */
    public <T extends android.app.Dialog> T track(T d) {
        for (java.util.Iterator<android.app.Dialog> it = dialogs.iterator(); it.hasNext(); ) if (!it.next().isShowing()) it.remove();
        dialogs.add(d);
        return d;
    }
    public void dismissDialogs() { for (android.app.Dialog d : dialogs) if (d.isShowing()) d.dismiss(); dialogs.clear(); }

    // ---- order desk ----

    /** Best FAST order on the opposite side that a limit at {@code price} crosses (never mine, never unbacked). */
    private Envelope bestFastCross(boolean buy, BigDecimal price) {
        for (Envelope e : relay.orders(buy ? "sell" : "buy")) {
            if (isMine(e) || e.coins.isEmpty()) continue;
            BigDecimal p = new BigDecimal(e.price);
            if (buy ? p.compareTo(price) <= 0 : p.compareTo(price) >= 0) return e;
        }
        return null;
    }

    /**
     * BUY/SELL from the panel. A crossing order takes the best SINGLE source for the first level (see
     * {@link SourcePick}): a FAST relay order through the two-party co-signed take, or BOOK/POOL liquidity through
     * PandaDEX's composite transaction (quoted off the UI thread, exactly as PandaDEX TradeView.submit). Nothing
     * crosses → the order rests on the relays as a FAST order.
     */
    void placeOrder(boolean buy, BigDecimal price, BigDecimal amount) {
        if (gateRefuses()) return;
        if (me == null || engine == null) { toast("Identity not ready — is Dexicon enabled in Minima Core?"); return; }
        if (busy) { toast("A transaction is in progress"); return; }
        final Envelope best = bestFastCross(buy, price);
        final List<Order5> orders = new ArrayList<>(restingBook().values());
        final List<Pool> pools = new ArrayList<>(restingPools());
        if (orders.isEmpty() && pools.isEmpty()) { placeFast(buy, price, amount, best); return; }
        final long block = chainBlock;
        final BigDecimal fastQty = best == null ? null : amount.min(new BigDecimal(best.amount));
        busy = true; setStage("Calculating quote…"); repaint();
        quoteExec.execute(() -> {
            CompositeRouter.Plan full = null, head = null;
            String error = null;
            try {
                full = CompositeRouter.plan(orders, pools, buy, amount, price, block);
                head = fastQty == null ? full : CompositeRouter.plan(orders, pools, buy, fastQty, price, block);
            } catch (RuntimeException invalid) { error = "Could not calculate this quote. Refresh and check the amounts."; }
            final CompositeRouter.Plan fFull = full, fHead = head; final String fError = error;
            ui.post(() -> {
                if (closed) return;
                busy = false; setStage(""); repaint();
                if (fError != null) { toast(fError); return; }
                if (SourcePick.restingFirst(fHead, fastQty, best == null ? null : new BigDecimal(best.price), buy))
                    confirmComposite(fFull, buy, amount, price, best);
                else placeFast(buy, price, amount, best);
            });
        });
    }

    /** The FAST path: a crossing fast order is taken at once (one click, no dialog), else the order rests as a fast order. */
    private void placeFast(boolean buy, BigDecimal price, BigDecimal amount, Envelope best) {
        if (best != null) { executeTake(buy, price, amount); return; }
        confirm((buy ? "Buy " : "Sell ") + Util.fmt(amount) + " MINIMA @ " + Util.fmt(price) + " MxUSD",
                "Rests on the relays as your order. Your wallet coins back it; the maker engine co-signs fills while the phone is in your pocket.",
                () -> {
                    if (gateRefuses()) return;
                    if (engine == null) { toast("Not paired — try again once PAIRED"); return; }
                    busy = true; setStage("Publishing order…"); repaint();
                    engine.create(buy ? "buy" : "sell", price.toPlainString(), amount.toPlainString(), (err, o) -> {
                        busy = false;
                        setStage(err != null ? "Order failed: " + err : "Order published — id " + o.orderId);
                        repaint();
                    });
                });
    }

    /** PandaDEX MainActivity.confirmComposite: list every BOOK and POOL part, effective and worst price, then DexTxn. */
    private void confirmComposite(CompositeRouter.Plan plan, boolean buy, BigDecimal amount, BigDecimal price, Envelope fastBest) {
        BigDecimal rest = amount.subtract(plan.totalMinima).max(BigDecimal.ZERO);
        StringBuilder sb = new StringBuilder();
        sb.append(buy ? "Buying " : "Selling ").append(PriceMath.fmt(plan.totalMinima)).append(" MINIMA now from on-chain liquidity\n");
        sb.append("Effective ").append(PriceMath.fmtPrice(plan.effectivePrice)).append("  ·  ").append(PriceMath.fmt(plan.totalMxusd)).append(" MxUSD\n");
        if (plan.orderMinima.signum() > 0) {
            sb.append("BOOK ").append(PriceMath.fmt(plan.orderMinima)).append(" MINIMA / ").append(PriceMath.fmt(plan.orderMxusd)).append(" MxUSD across ")
              .append(plan.orderTakes.size()).append(plan.orderTakes.size() == 1 ? " order\n" : " orders\n");
        }
        if (plan.poolMinima.signum() > 0) {
            sb.append("POOL ").append(PriceMath.fmt(plan.poolMinima)).append(" MINIMA / ").append(PriceMath.fmt(plan.poolMxusd)).append(" MxUSD across ")
              .append(plan.poolCount()).append(plan.poolCount() == 1 ? " pool\n" : " pools\n");
            String impact = poolImpact(plan);
            if (!impact.isEmpty()) sb.append("Price impact ").append(impact).append("\n");
        }
        if (plan.worstMarginalPrice.signum() > 0) sb.append("Worst marginal ").append(PriceMath.fmtPrice(plan.worstMarginalPrice)).append("\n");
        for (SweepPlanner.Take t : plan.orderTakes) {
            sb.append("  • BOOK ").append(PriceMath.fmt(t.minima)).append(" @ ").append(PriceMath.fmtPrice(t.order.price())).append(t.partial ? "  (partial)" : "")
              .append("\n     order coin ").append(t.order.coinid).append("\n");
        }
        if (plan.poolRoute != null) for (PoolRouter.Alloc a : plan.poolRoute.allocs) {
            BigDecimal m = buy ? a.quote.outAmount : a.quote.inAmount, u = buy ? a.quote.inAmount : a.quote.outAmount;
            sb.append("  • POOL ").append(PriceMath.fmt(m)).append(" MINIMA / ").append(PriceMath.fmt(u)).append(" MxUSD")
              .append("\n     pool ").append(a.pool.address).append("\n");
        }
        if (fastBest != null) sb.append("\nBeats the best FAST ").append(buy ? "ask" : "bid").append(" @ ").append(fastBest.price).append(" for this size; FAST is not used in this trade.\n");
        if (rest.signum() > 0) sb.append("\nThe remaining ").append(PriceMath.fmt(rest)).append(" MINIMA is NOT traded or rested by this transaction — place it again once it confirms.\n");
        sb.append("\nOne atomic transaction, signed by your node; final after one block.");
        track(new AlertDialog.Builder(this, Design.dialogTheme())
                .setTitle(buy ? "Confirm buy · BOOK/POOL" : "Confirm sell · BOOK/POOL")
                .setMessage(sb.toString())
                .setPositiveButton("Execute", (d, w) -> executeComposite(plan, buy, amount, price, 0))
                .setNegativeButton("Back", null)
                .show());
    }

    private void executeComposite(CompositeRouter.Plan plan, boolean buy, BigDecimal amount, BigDecimal price, int retry) {
        if (gateRefuses()) { setStage(""); return; }
        if (busy) { toast("A transaction is in progress"); return; }
        busy = true;
        final String route = plan.orderMinima.signum() > 0 && plan.poolMinima.signum() > 0 ? "BOOK+POOL" : plan.poolMinima.signum() > 0 ? "POOL" : "BOOK";
        final String actId = ActivityLog.newId("slow_");
        final JSONObject[] posted = {new JSONObject()};
        try {
            ActivityLog.add(db, new JSONObject().put("id", actId).put("route", route).put("role", "taker").put("side", buy ? "buy" : "sell")
                    .put("amount", PriceMath.fmt(plan.totalMinima)).put("price", plan.effectivePrice.toPlainString()).put("mxusd", PriceMath.fmt(plan.totalMxusd))
                    .put("ts", System.currentTimeMillis()).put("stage", ActivityLog.BUILDING).put("reason", "Selecting wallet coins…"));
        } catch (Exception ignored) {}
        liveActId = actId;
        setStage((buy ? "Buy" : "Sell") + " accepted · selecting wallet coins for " + plan.poolCount() + " pool(s) and " + plan.orderTakes.size() + " book order(s)");
        repaint();
        restingTxn.fillComposite(plan, buy, new DexTxn.Result() {
            @Override public void onProgress(String message) {
                ActivityLog.update(db, actId, a -> a.put("reason", message));
                setStage("");
            }
            @Override public void onPostReply(JSONObject reply) { posted[0] = ActivityLog.fromPostReply(reply, restingTxn.hexAddr()); }
            @Override public void onPosted(String txpowid) {
                busy = false;
                JSONObject ids = posted[0];
                ActivityLog.update(db, actId, a -> a.put("stage", ActivityLog.POSTED).put("reason", "").put("txpowid", txpowid)
                        .put("txnid", ids.optString("txnid")).put("outs", ids.optJSONArray("outs") == null ? new JSONArray() : ids.optJSONArray("outs"))
                        .put("ins", ids.optJSONArray("ins") == null ? new JSONArray() : ids.optJSONArray("ins"))
                        .put("postBlock", chainBlock).put("tip", chainBlock).put("postedMs", System.currentTimeMillis()));
                // BOOK/POOL fills join HISTORY and the CSV like FAST fills; MakerService.settleFills settles them by output coin.
                try {
                    db.saveFill(new JSONObject().put("role", "taker").put("orderId", "").put("side", buy ? "buy" : "sell")
                            .put("price", plan.effectivePrice.toPlainString()).put("filled", PriceMath.fmt(plan.totalMinima))
                            .put("gave", PriceMath.fmt(buy ? plan.totalMxusd : plan.totalMinima)).put("got", PriceMath.fmt(buy ? plan.totalMinima : plan.totalMxusd))
                            .put("txpowid", txpowid).put("coins", ids.optJSONArray("outs") == null ? new JSONArray() : ids.optJSONArray("outs"))
                            .put("counterparty", route).put("ts", MakerEngine.now()).put("status", "settling"));
                } catch (Exception ignored) {}
                setStage("");
                refreshResting(); readBalances(); repaint();
            }
            @Override public void onFailed(String message) {
                busy = false;
                ActivityLog.update(db, actId, a -> a.put("stage", ActivityLog.FAILED).put("reason", message));
                setStage("");
                refreshResting(); repaint();
                // A DEX does the wallet work itself: a funding failure is recovered (wait for coins to confirm, or
                // consolidate the token) and the SAME trade is re-planned within the same limit and retried once.
                if (retry == 0 && message != null && (message.startsWith("Wallet has") || message.contains("Too many transaction inputs")
                        || message.contains("needs more than") || message.contains("TxPoW size") || message.contains("too large")))
                    autoFund(buy, amount, price, buy ? Tokens.MXUSD() : Tokens.MINIMA, buy ? plan.totalMxusd : plan.totalMinima, message);
                else toast(message);
            }
        });
    }

    /** Recover a pool/book trade's funding by itself, then retry it once (owner 2026-10-22: "Where is the automation?"):
     *  coins still confirming → wait for them; enough sendable but in too many / unusable coins → `consolidate` (the
     *  family's PandaPools/UTXO tool) and wait for it; genuinely short → say so plainly. Waits at most 10 blocks. */
    private void autoFund(boolean buy, BigDecimal amount, BigDecimal price, String token, BigDecimal need, String why) {
        String name = Tokens.isMinima(token) ? "MINIMA" : "MxUSD";
        node.cmd("balance tokenid:" + token, new NodeApi.Cb() {
            public void onResult(JSONObject j) {
                BigDecimal sendable = BigDecimal.ZERO, total = BigDecimal.ZERO; int coins = 0;
                for (JSONObject b : NodeApi.rows(j)) if (token.equalsIgnoreCase(b.optString("tokenid"))) {
                    sendable = Util.dec(b.optString("sendable", "0")); coins = b.optInt("coins", 0);
                    total = Util.dec(b.optString("confirmed", "0")).add(Util.dec(b.optString("unconfirmed", "0")));
                }
                if (total.compareTo(need) < 0) { toast("Not enough " + name + ": this trade needs " + PriceMath.fmt(need) + ", your wallet holds " + PriceMath.fmt(total)); return; }
                if (sendable.compareTo(need) < 0) {
                    setStage("Waiting for your " + name + " to confirm (" + PriceMath.fmt(sendable) + " of " + PriceMath.fmt(need) + " spendable) — the trade retries by itself");
                    waitThenRetry(buy, amount, price, token, need, chainBlock, false);
                    return;
                }
                final int before = coins;
                setStage("Your " + name + " is spread over " + coins + " coins — consolidating it, then the trade retries by itself");
                // the one node-picked-spend guard (as ASSETS › CONSOLIDATE): the node picks the coins
                com.eurobuddha.pandapools.DexiconPools.guardNodePickedSpend(node, token, blocked -> {
                    if (blocked != null) { setStage(""); explain("Nothing consolidated", blocked + "\n\n" + why); return; }
                    SignGate.submit(release -> node.cmdR("consolidate tokenid:" + token, new NodeApi.Cb() {
                        public void onResult(JSONObject x) { release.free(); waitThenRetry(buy, amount, price, token, need, chainBlock, true); }
                        public void onError(String m) { release.free(); setStage(""); toast("Could not consolidate " + name + ": " + m + " — " + why); }
                    }));
                });
            }
            public void onError(String m) { toast(why); }
        });
    }

    /** Poll once a block (≤ 10) until the token is spendable again (and, after a consolidation, settled), then re-plan the
     *  same trade at the same limit and execute it — no second confirmation: the user already confirmed this trade. */
    private void waitThenRetry(boolean buy, BigDecimal amount, BigDecimal price, String token, BigDecimal need, long startBlock, boolean consolidated) {
        final int gen = gateGen;
        ui.postDelayed(() -> {
            if (closed) return;
            if (gen != gateGen || TradingGate.blocked(this)) { setStage(""); return; }   // the gate shut while it waited: nothing re-executes
            if (chainBlock - startBlock > 10) { setStage(""); toast("Gave up waiting for the wallet after 10 blocks — try the trade again"); return; }
            node.cmd("balance tokenid:" + token, new NodeApi.Cb() {
                public void onResult(JSONObject j) {
                    BigDecimal sendable = BigDecimal.ZERO, unconf = BigDecimal.ZERO;
                    for (JSONObject b : NodeApi.rows(j)) if (token.equalsIgnoreCase(b.optString("tokenid"))) { sendable = Util.dec(b.optString("sendable", "0")); unconf = Util.dec(b.optString("unconfirmed", "0")); }
                    boolean ready = sendable.compareTo(need) >= 0 && (!consolidated || unconf.signum() == 0);
                    if (!ready || busy) { waitThenRetry(buy, amount, price, token, need, startBlock, consolidated); return; }
                    final List<Order5> orders = new ArrayList<>(restingBook().values());
                    final List<Pool> pools = new ArrayList<>(restingPools());
                    CompositeRouter.Plan again;
                    try { again = CompositeRouter.plan(orders, pools, buy, amount, price, chainBlock); } catch (RuntimeException e) { again = null; }
                    if (again == null || again.isEmpty()) { setStage(""); toast("The liquidity moved past your limit while the wallet settled — nothing was traded"); return; }
                    setStage("Wallet ready — retrying your " + (buy ? "buy" : "sell"));
                    executeComposite(again, buy, amount, price, 1);
                }
                public void onError(String m) { waitThenRetry(buy, amount, price, token, need, startBlock, consolidated); }
            });
        }, 15_000);
    }

    /** PandaDEX MainActivity.poolImpact. */
    private static String poolImpact(CompositeRouter.Plan plan) {
        if (plan == null || plan.poolRoute == null || !plan.poolRoute.ok
                || plan.poolRoute.spotBefore.signum() <= 0 || plan.effectivePrice.signum() <= 0) return "";
        BigDecimal pct = plan.effectivePrice.subtract(plan.poolRoute.spotBefore).abs()
                .multiply(new BigDecimal("100"), PriceMath.MC)
                .divide(plan.poolRoute.spotBefore, 2, java.math.RoundingMode.HALF_UP);
        return pct.toPlainString() + "%";
    }

    /** Mirror of the MiniDapp's retryNextLevel: a take from the order form that lost the race re-plans the same limit
     *  against the next best fast level (never the same order twice) and asks once more. */
    /** The service answers takes; the screen only said "waiting for the maker" — and kept saying it after the fill
     *  landed (seen on-device 2026-10-21). When no take is pending any more, name the newest fill instead. */
    void settleStage() {
        if (!stage.startsWith("Take sent")) return;   // pre-0.1.9 text; takes are now followed live via liveActId
        if (!db.pendings().isEmpty()) return;
        java.util.List<org.json.JSONObject> fills = db.fills(1);
        String last = db.meta("last_refusal", "");
        if (!fills.isEmpty() && fills.get(0).optLong("ts", 0) * 1000 > System.currentTimeMillis() - 600_000L)
            setStage("Filled " + fills.get(0).optString("filled") + " MINIMA @ " + fills.get(0).optString("price") + " — " + fills.get(0).optString("status", "settling") + " (HISTORY)");
        else if (!last.isEmpty()) setStage("Maker refused: " + last);
        else setStage("");
    }

    /** Mirror of the MiniDapp: a crossing order that was only partly filled by the best resting order rests the
     *  remainder as a fast order of our own, once the maker has co-signed (rest_due is set by the service on `taken`). */
    void restIfDue() {
        String due = db.meta("rest_due", "");
        if (due.isEmpty() || engine == null || busy) return;
        db.setMeta("rest_due", "");
        if (TradingGate.blocked(this)) { db.setMeta("retry", ""); return; }   // never rest a remainder while the gate is shut
        JSONObject r; try { r = new JSONObject(db.meta("retry", "{}")); } catch (Exception e) { return; }
        BigDecimal rest; try { rest = new BigDecimal(r.optString("rest", "0")); } catch (Exception e) { return; }
        org.json.JSONArray tried = r.optJSONArray("tried");
        boolean ours = false; for (int i = 0; tried != null && i < tried.length(); i++) if (due.equals(tried.optString(i))) ours = true;
        if (!ours || rest.signum() <= 0) return;
        db.setMeta("retry", "");
        boolean buy = r.optBoolean("buy"); String price = r.optString("price");
        busy = true; setStage("Resting the remaining " + rest.toPlainString() + " MINIMA @ " + price + " as your order…"); repaint();
        engine.create(buy ? "buy" : "sell", price, rest.toPlainString(), (err, o) -> {
            busy = false;
            setStage(err != null ? "Remainder not rested: " + err : "Remaining " + rest.toPlainString() + " MINIMA rests as order " + o.orderId);
            repaint();
        });
    }

    /** The service flags a lost race with retry_due. A live one-click run moves on by itself (followTake); a take
     *  orphaned by an app restart is only reported — nothing trades without a click. */
    void retryIfDue() {
        String due = db.meta("retry_due", "");
        if (due.isEmpty()) return;
        db.setMeta("retry_due", "");
        if (run == null) setStage("Your take of order " + due + " lost the race (" + db.meta("last_refusal", "taken") + "). Nothing was traded.");
    }

    void cancelOrder(MyOrder o) {
        if (engine == null) { toast("Not paired yet — enable Dexicon in your node app (Apps), unlock it, then try again"); return; }
        confirm("Cancel order " + o.orderId + "?", (o.side.equals("sell") ? "SELL " : "BUY ") + Util.fmt(Util.dec(o.remaining)) + " MINIMA @ " + o.price, () -> {
            busy = true; repaint();
            if (engine == null) { busy = false; toast("Not paired — try again once PAIRED"); repaint(); return; }
            engine.cancel(o.orderId, (err, x) -> { busy = false; setStage(err != null ? "Cancel failed: " + err : "Order cancelled"); repaint(); });
        });
    }

    void editOrder(MyOrder o) {
        if (engine == null) { toast("Not paired yet — enable Dexicon in your node app (Apps), unlock it, then try again"); return; }
        LinearLayout box = new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL);
        int p = Design.dp(this, 16); box.setPadding(p, p, p, 0);
        EditText price = new EditText(this); price.setHint("Price (MxUSD per MINIMA)"); price.setText(o.price);
        EditText amount = new EditText(this); amount.setHint("Amount (MINIMA)"); amount.setText(o.remaining);
        box.addView(price); box.addView(amount);
        track(new AlertDialog.Builder(this, Design.dialogTheme()).setTitle("Edit order").setView(box)
                .setPositiveButton("Update", (d, w) -> {
                    if (gateRefuses()) return;
                    busy = true; repaint();
                    if (engine == null) { busy = false; toast("Not paired — try again once PAIRED"); repaint(); return; }
                    engine.edit(o.orderId, price.getText().toString().trim(), amount.getText().toString().trim(), (err, x) -> { busy = false; setStage(err != null ? "Edit failed: " + err : "Order updated"); repaint(); });
                }).setNegativeButton("Back", null).show());
    }

    private interface CoinsCb { void done(String err, List<Coin> coins); }
    private void fetchCoins(List<String> ids, int i, List<Coin> out, CoinsCb cb) {
        if (i >= ids.size()) { cb.done(null, out); return; }
        String id = ids.get(i);
        node.cmdR("coins coinid:" + id, new NodeApi.Cb() {
            public void onResult(JSONObject j) {
                List<JSONObject> rows = NodeApi.rows(j);
                if (rows.isEmpty() || rows.get(0).optString("coinid", "").isEmpty()) { cb.done("Maker coin " + id + " not found (spent or not yet visible)", null); return; }
                Coin c = Coin.of(rows.get(0));
                if (c.spent) { cb.done("Maker coin " + id + " is already spent", null); return; }
                out.add(c); fetchCoins(ids, i + 1, out, cb);
            }
            public void onError(String m) { cb.done("Cannot fetch maker coin " + id + ": " + m, null); }
        });
    }

    // ---- the one-click FAST take (MinimaXT's startTrade): coins fetched ahead, the button locks, a step log ----
    private final java.util.Map<String, Coin> makerCoinCache = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.Map<String, List<Coin>> walletCache = new java.util.HashMap<>();
    private String cachedAddr;
    private boolean warmingMakers;
    private final StringBuilder takeLog = new StringBuilder();
    private String followAct, followStatus;
    private static final class TakeRun {
        final long t0 = System.currentTimeMillis(); boolean buy; BigDecimal price, amount;
        final java.util.Set<String> tried = new java.util.HashSet<>(); String act, orderId; Envelope order;
    }
    private TakeRun run;

    boolean taking() { return run != null; }
    long takingSecs() { return run == null ? 0 : (System.currentTimeMillis() - run.t0) / 1000; }
    String takeLog() { return takeLog.toString(); }
    Envelope fastCross(boolean buy, BigDecimal price) { return bestFastCross(buy, price); }
    /** Exact MINIMA the crossing FAST orders offer at {@code price} (not mine, backed). */
    BigDecimal offered(boolean buy, BigDecimal price) {
        BigDecimal t = BigDecimal.ZERO;
        for (Envelope e : relay.orders(buy ? "sell" : "buy")) {
            if (isMine(e) || e.coins.isEmpty()) continue;
            BigDecimal p = new BigDecimal(e.price);
            if (buy ? p.compareTo(price) <= 0 : p.compareTo(price) >= 0) t = t.add(new BigDecimal(e.amount));
        }
        return t;
    }

    private void logStep(String msg) {
        String t = run != null ? String.format(java.util.Locale.ROOT, "+%.1fs  ", (System.currentTimeMillis() - run.t0) / 1000.0)
                               : new java.text.SimpleDateFormat("HH:mm:ss  ", java.util.Locale.ROOT).format(new java.util.Date());
        takeLog.append(t).append(msg).append('\n');
        if (trade != null) trade.paintLog();
    }

    /** Wallet coins of both tokens + the receive address, ready before any click. */
    void warmWallet() {
        if (node == null || !paired) return;
        for (String tok : new String[]{Tokens.MINIMA, Tokens.MXUSD()})
            MakerEngine.walletCoins(node, tok, (e, coins) -> { if (e == null) walletCache.put(tok, coins); });
        node.cmd("getaddress", new NodeApi.Cb() {
            public void onResult(JSONObject j) { JSONObject r = j.optJSONObject("response"); if (r != null && !r.optString("address", "").isEmpty()) cachedAddr = r.optString("address"); }
            public void onError(String m) {}
        });
    }

    /** The backing coins of the best few FAST orders on each side, fetched in the background; spent ones dropped. */
    void warmMakers() {
        if (node == null || !paired || warmingMakers) return;
        List<String> ids = new ArrayList<>();
        for (String side : new String[]{"sell", "buy"}) {
            int n = 0;
            for (Envelope e : relay.orders(side)) {
                if (isMine(e) || e.coins.isEmpty()) continue;
                for (String id : e.coins) if (!ids.contains(id)) ids.add(id);
                if (++n >= 6) break;
            }
        }
        warmingMakers = true;
        warmStep(ids, 0);
    }
    private void warmStep(List<String> ids, int i) {
        if (i >= ids.size() || closed) { warmingMakers = false; return; }
        String id = ids.get(i);
        node.cmdR("coins coinid:" + id, new NodeApi.Cb() {
            public void onResult(JSONObject j) {
                List<JSONObject> rows = NodeApi.rows(j);
                Coin c = rows.isEmpty() ? null : Coin.of(rows.get(0));
                if (c == null || c.coinid.isEmpty() || c.spent) makerCoinCache.remove(id); else makerCoinCache.put(id, c);
                warmStep(ids, i + 1);
            }
            public void onError(String m) { warmStep(ids, i + 1); }
        });
    }

    /** THE click: walk every crossing FAST order best-first; a refusal moves to the next one by itself. */
    private void executeTake(boolean buy, BigDecimal price, BigDecimal amount) {
        if (run != null) return;
        takeLog.setLength(0); followAct = null;
        run = new TakeRun(); run.buy = buy; run.price = price; run.amount = amount;
        logStep((buy ? "BUY " : "SELL ") + amount.toPlainString() + " MINIMA @ " + price.toPlainString() + " (limit)");
        repaint();
        nextOrder();
    }

    private void nextOrder() {
        TakeRun r = run; if (r == null) return;
        if (TradingGate.blocked(this)) { logStep("Stopped: trading is off (one trading app per phone). Nothing more was sent."); endRun(); return; }
        List<Envelope> hits = new ArrayList<>();
        for (Envelope e : relay.orders(r.buy ? "sell" : "buy")) {
            if (isMine(e) || e.coins.isEmpty() || r.tried.contains(e.orderId)) continue;
            BigDecimal p = new BigDecimal(e.price);
            if (r.buy ? p.compareTo(r.price) <= 0 : p.compareTo(r.price) >= 0) hits.add(e);
        }
        if (hits.isEmpty()) {
            logStep(r.tried.isEmpty() ? "Nothing crosses any more. Place the order again to rest it." : "No other FAST order crosses " + r.price.toPlainString() + ". Nothing was traded and no order was placed.");
            endRun(); return;
        }
        if (r.tried.isEmpty()) logStep("Found " + hits.size() + " FAST order" + (hits.size() == 1 ? "" : "s") + " at or better than your price");
        tryOrder(hits.get(0));
    }

    private void tryOrder(Envelope o) {
        TakeRun r = run;
        // PandaDEX's min-remainder floor: never leave under 1 MINIMA on the maker's order — shrink the take to leave
        // exactly 1 (what you asked beyond that rests as yours); an order that can't be part-taken is skipped
        BigDecimal now = FillPlan.floorTake(r.amount.min(new BigDecimal(o.amount)), new BigDecimal(o.amount)), rest = r.amount.subtract(now);
        r.tried.add(o.orderId);
        if (now.signum() <= 0) { logStep("Order " + o.orderId + ": a partial take would leave under " + FillPlan.MIN_REM.toPlainString() + " MINIMA — next order"); nextOrder(); return; }
        if (now.compareTo(r.amount.min(new BigDecimal(o.amount))) < 0) logStep("  taking " + now.toPlainString() + " so " + FillPlan.MIN_REM.toPlainString() + " MINIMA stays on the maker's order (minimum remainder)");
        logStep("Order " + o.orderId + " @ " + o.price + " · " + o.amount + " MINIMA · maker " + o.address);
        List<Coin> have = new ArrayList<>(); List<String> miss = new ArrayList<>();
        for (String id : o.coins) { Coin c = makerCoinCache.get(id); if (c != null) have.add(c); else miss.add(id); }
        fetchCoins(miss, 0, new ArrayList<>(), (err, got) -> {
            if (run != r) return;
            if (err != null) { logStep("  maker coins: " + err + " — next order"); nextOrder(); return; }
            for (Coin c : got) makerCoinCache.put(c.coinid, c);
            List<Coin> mc = new ArrayList<>(have); mc.addAll(got);
            logStep("  maker coins " + (miss.isEmpty() ? "ready" : "fetched") + " (" + mc.size() + ")");
            boolean sell = "sell".equals(o.side);
            String tok = sell ? Tokens.MXUSD() : Tokens.MINIMA;
            withWallet(tok, (e2, myCoins, addr) -> {
                if (run != r) return;
                if (e2 != null) { logStep("  " + e2); endRun(); return; }
                FillPlan.Plan plan;
                try { plan = FillPlan.plan(new FillPlan.Order(o.side, o.price, o.amount, o.address, o.coins, o.scripts), now.toPlainString(), mc, myCoins, addr); }
                catch (RuntimeException e) { logStep("  " + e.getMessage() + " — next order"); nextOrder(); return; }
                logStep("  you give " + plan.takerGives + (sell ? " MxUSD" : " MINIMA") + ", you get " + plan.makerGives + (sell ? " MINIMA" : " MxUSD"));
                try { db.setMeta("retry", new JSONObject().put("buy", r.buy).put("price", r.price.toPlainString()).put("amount", r.amount.toPlainString()).put("rest", rest.toPlainString()).put("tried", new JSONArray().put(o.orderId)).toString()); } catch (Exception ignored) {}
                sendTake(o, plan);
            });
        });
    }

    private interface WalletCb { void done(String err, List<Coin> coins, String addr); }
    private void withWallet(String tok, WalletCb cb) {
        List<Coin> c = walletCache.get(tok);
        if (c != null && cachedAddr != null) { cb.done(null, c, cachedAddr); return; }
        MakerEngine.walletCoins(node, tok, (e, coins) -> {
            if (e != null) { cb.done("Cannot read your coins: " + e, null, null); return; }
            walletCache.put(tok, coins);
            node.cmd("getaddress", new NodeApi.Cb() {
                public void onResult(JSONObject j) { JSONObject x = j.optJSONObject("response"); cachedAddr = x == null ? null : x.optString("address", null); if (cachedAddr == null || cachedAddr.isEmpty()) cb.done("getaddress failed", null, null); else cb.done(null, coins, cachedAddr); }
                public void onError(String m) { cb.done("getaddress failed: " + m, null, null); }
            });
        });
    }

    /** Build + sign my side, seal the take to the maker, publish to every relay. */
    private void sendTake(Envelope order, FillPlan.Plan plan) {
        final TakeRun r = run;
        if (r == null) return;
        if (TradingGate.blocked(this)) { logStep("Stopped: trading is off (one trading app per phone). Nothing was sent."); endRun(); return; }
        busy = true;
        final String actId = ActivityLog.newId("fast_");
        final long t = System.currentTimeMillis();
        boolean takerBuys = "sell".equals(order.side);
        r.act = actId; r.orderId = order.orderId; r.order = order;
        try {
            ActivityLog.add(db, new JSONObject().put("id", actId).put("route", "FAST").put("role", "taker").put("side", takerBuys ? "buy" : "sell")
                    .put("amount", plan.filled).put("price", order.price).put("mxusd", takerBuys ? plan.takerGives : plan.makerGives)
                    .put("orderId", order.orderId).put("counterparty", order.address).put("ins", new JSONArray(plan.inputs))
                    .put("ts", System.currentTimeMillis()).put("stage", ActivityLog.BUILDING).put("reason", "Building and signing your side…"));
        } catch (Exception ignored) {}
        liveActId = actId;
        logStep("  signing your side…");
        FillTxn.build(node, plan, (err, hex) -> {
            if (err != null) { busy = false; ActivityLog.update(db, actId, a -> a.put("stage", ActivityLog.FAILED).put("reason", "build failed: " + err)); logStep("  build failed: " + err); endRun(); return; }
            logStep(String.format(java.util.Locale.ROOT, "  signed in %.1fs", (System.currentTimeMillis() - t) / 1000.0));
            walletCache.remove(takerBuys ? Tokens.MXUSD() : Tokens.MINIMA); warmWallet();   // those coins are in flight now
            try {
                JSONObject inner = new JSONObject().put("k", "take").put("orderId", order.orderId).put("txn", hex).put("taker", me.publicId());
                String blob = me.seal(order.pubId, inner.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                String msg = Envelopes.msg(me, order.pubId, blob, MakerEngine.now() + 300);
                JSONObject pt = new JSONObject().put("order", new JSONObject(order.raw)).put("plan", planJson(plan)).put("sent", System.currentTimeMillis()).put("act", actId)
                        .put("maker", order.pubId).put("outs", new JSONArray()).put("mine", mineIns(plan, order));
                db.savePending(order.orderId, pt);
                // my own record of the outputs (the maker's signature adds witness only, so these ids are final): never the maker's word
                node.cmdR("txnview data:" + hex, new NodeApi.Cb() {
                    public void onResult(JSONObject v) {
                        JSONObject r = v.optJSONObject("response"), t = r == null ? null : r.optJSONObject("transaction");
                        JSONArray outs = t == null ? null : t.optJSONArray("outputs"), ids = new JSONArray();
                        if (outs != null) for (int i = 0; i < outs.length(); i++) { JSONObject c = outs.optJSONObject(i); String id = c == null ? "" : c.optString("coinid", ""); if (id.matches("0x[0-9A-Fa-f]{64}")) ids.put(id); }
                        JSONObject cur = db.pending(order.orderId);
                        if (cur != null) try { db.savePending(order.orderId, cur.put("outs", ids)); } catch (Exception ignored) {}
                    }
                    public void onError(String m) {}
                });
                net.execute(() -> {
                    List<RelayClient.PublishResult> results = relay.publish(msg);
                    int ok = 0; for (RelayClient.PublishResult x : results) if (x.ok) ok++;
                    final int okCount = ok, total = results.size(); final String firstErr = results.isEmpty() ? "no relays" : results.get(0).error;
                    ui.post(() -> {
                        busy = false;
                        if (okCount == 0) {
                            db.removePending(order.orderId); ActivityLog.update(db, actId, a -> a.put("stage", ActivityLog.FAILED).put("reason", "no relay accepted the take: " + firstErr));
                            logStep("  no relay accepted the take: " + firstErr); endRun(); return;
                        }
                        ActivityLog.update(db, actId, a -> {   // a fast maker can answer (via another relay) before the slowest publish returns
                            a.put("relays", okCount).put("sentMs", pt.optLong("sent"));
                            if (ActivityLog.BUILDING.equals(a.optString("stage"))) a.put("stage", ActivityLog.SENT).put("reason", "");
                        });
                        if (run == r && r.act.equals(actId)) logStep("  sent to " + okCount + "/" + total + " relays · waiting for the maker's node to co-sign…");
                        followTake();
                        ui.postDelayed(() -> {
                            if (run == r && actId.equals(r.act) && db.pending(order.orderId) != null) {
                                logStep("No answer from the maker in 60s. If it has not co-signed within 5 minutes, your node spends one of this take's coins back to you, which voids it.");
                                endRun();
                            }
                        }, 60_000);
                        repaint();
                    });
                });
            } catch (Exception e) { busy = false; ActivityLog.update(db, actId, a -> a.put("stage", ActivityLog.FAILED).put("reason", "take failed: " + e.getMessage())); logStep("  take failed: " + e.getMessage()); endRun(); }
        });
    }

    /** Reads the service's answer off the ACTIVITY row (co-signed / refused) and, after a co-sign, writes the block and
     *  confirmations into the same log. Runs on every service broadcast and every second. */
    void followTake() {
        if (db == null) return;
        TakeRun r = run;
        if (r != null && r.act != null) {
            JSONObject a = db.activity(r.act);
            String st = a == null ? "" : a.optString("stage");
            if (ActivityLog.COSIGNED.equals(st)) {
                logStep(String.format(java.util.Locale.ROOT, "Maker co-signed in %.1fs", a.optLong("cosignMs") / 1000.0));
                logStep("TRADE DONE: " + a.optString("amount") + " MINIMA @ " + a.optString("price") + " · txpow " + a.optString("txpowid"));
                logStep("Settling — final after one block.");
                followAct = r.act; followStatus = null;
                if (trade != null) trade.clearAmount();
                endRun();
            } else if (ActivityLog.REFUSED.equals(st)) {
                String why = a.optString("reason");
                for (String id : r.order.coins) makerCoinCache.remove(id);
                db.setMeta("retry_due", "");
                logStep("  maker refused: " + why + (why.matches(MakerService.MOVE_ON) ? " — next order" : ""));
                if (why.matches(MakerService.MOVE_ON)) { r.act = null; nextOrder(); } else endRun();
            } else if (ActivityLog.FAILED.equals(st) || ActivityLog.LOST.equals(st)) {
                logStep("  " + a.optString("reason")); endRun();
            }
        }
        if (run == null && followAct != null) {
            JSONObject a = db.activity(followAct);
            if (a == null) { followAct = null; return; }
            String s = ActivityLog.statusText(a, chainBlock, System.currentTimeMillis());
            if (followStatus == null) followStatus = s;          // the co-sign line is already in the log
            else if (!s.equals(followStatus)) { followStatus = s; logStep(s); }
            if (ActivityLog.settled(a, chainBlock) || ActivityLog.failedTerminal(a)) followAct = null;
        }
    }

    private void endRun() { run = null; busy = false; repaint(); }

    /** My own coins in a take (its inputs the maker did not advertise) — the service spends one to void a stale take. */
    private static JSONArray mineIns(FillPlan.Plan p, Envelope order) {
        JSONArray out = new JSONArray();
        for (String id : p.inputs) { boolean theirs = false; for (String m : order.coins) if (m.equalsIgnoreCase(id)) theirs = true; if (!theirs) out.put(id); }
        return out;
    }

    private static JSONObject planJson(FillPlan.Plan p) throws Exception {
        return new JSONObject().put("filled", p.filled).put("takerGives", p.takerGives).put("makerGives", p.makerGives).put("payToken", p.payToken).put("recvToken", p.recvToken).put("inputs", new JSONArray(p.inputs));
    }

    void exportCsv() {
        pendingCsv = TradeExport.csv(db.fills(5000));
        saveCsv.launch("dexicon-fills-" + System.currentTimeMillis() / 1000 + ".csv");
    }

    /** AtomiX's "⬇ Export CSV": every AtomiX swap, maker and taker, in AtomiX's own columns (dexHistory reads them). */
    void exportAtomixCsv() {
        com.eurobuddha.dexicon.doors.DoorHost h = com.eurobuddha.dexicon.doors.DoorHost.get(this);
        java.util.List<com.eurobuddha.atomix.swap.SwapDb.Swap> all = h.swaps();
        if (all.isEmpty()) { toast("No swaps to export"); return; }
        pendingCsv = AtomixCsv.csv(all, h::events);
        saveCsv.launch(AtomixCsv.FILE);
    }

    private void confirm(String title, String body, Runnable yes) {
        track(new AlertDialog.Builder(this, Design.dialogTheme()).setTitle(title).setMessage(body)
                .setPositiveButton("Confirm", (d, w) -> yes.run()).setNegativeButton("Back", null).show());
    }

    // ---- chrome ----

    private View buildChrome() {
        int pad = Design.dp(this, 12);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Design.BG());
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            androidx.core.graphics.Insets bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars() | androidx.core.view.WindowInsetsCompat.Type.displayCutout());
            androidx.core.graphics.Insets ime = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime());
            v.setPadding(bars.left, bars.top, bars.right, Math.max(bars.bottom, ime.bottom));
            return insets;
        });

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(pad, pad, pad, Design.dp(this, 6));
        TextView logo = new TextView(this);
        logo.setText("◆ DEXICON"); logo.setTextColor(Design.TEXT()); logo.setTypeface(Design.monoBold()); logo.setTextSize(15f);
        header.addView(logo, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        TextView verPill = Design.pill(this, "v" + BuildConfig.VERSION_NAME, Design.SURFACE2(), Design.DIM2());
        LinearLayout.LayoutParams vp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        vp.rightMargin = Design.dp(this, 6);
        header.addView(verPill, vp);
        TextView themePill = Design.pill(this, Design.isDark() ? "☀" : "☾", Design.SURFACE2(), Design.DIM());
        themePill.setMinWidth(Design.dp(this, 48)); themePill.setMinHeight(Design.dp(this, 48));
        themePill.setOnClickListener(v -> { if (poolsBusy()) { toast("Finish your pool transaction first, then switch theme."); return; } stopCcyGlow(); Design.toggle(this); nightModeFollowsDesign(); if (trade != null) trade.close(); chromeRoot = buildChrome(); setContentView(chromeRoot); showPages(tab, tab2); });
        Design.pressable(themePill);
        header.addView(themePill);
        root.addView(header);

        LinearLayout nodeRow = new LinearLayout(this);
        nodeRow.setGravity(Gravity.CENTER_VERTICAL);
        nodeRow.setPadding(pad, 0, pad, Design.dp(this, 6));
        pairPill = Design.pill(this, "CONNECTING…", Design.SURFACE2(), Design.DIM());
        pairPill.setOnClickListener(v -> { if (!paired) openNodeApp(); });   // not paired: one tap to the node app (its Apps screen holds the toggle)
        nodeRow.addView(pairPill, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        // The AtomiX currency switch (apks/atomix buildHeader): names the ACTIVE market; tap to switch, long-press cycles the glow.
        ccyPill = Design.pill(this, "", Design.SURFACE2(), Design.TEXT());
        ccyPill.setTextSize(12f); ccyPill.setTypeface(Design.sansBold());
        ccyPill.setMinHeight(Design.dp(this, 48));
        ccyPill.setOnClickListener(v -> switchCurrencyDialog());
        ccyPill.setOnLongClickListener(v -> {
            Design.Glow g = Design.cycleGlow(this);
            toast("Pill glow: " + g.name().toLowerCase(java.util.Locale.ROOT));
            startCcyGlow();
            return true;
        });
        Design.pressable(ccyPill);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        cp.leftMargin = Design.dp(this, 6);
        nodeRow.addView(ccyPill, cp);
        blockPill = Design.pill(this, "# —", Design.SURFACE2(), Design.DIM());
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        bp.leftMargin = Design.dp(this, 6);
        nodeRow.addView(blockPill, bp);
        root.addView(nodeRow);

        // One slim segmented row (was two-line 48 dp pills plus a second row of page pills): the room, nothing else.
        rail = new LinearLayout(this);
        rail.setPadding(pad, 0, pad, Design.dp(this, 6));
        for (int r = 0; r < 3; r++) {
            final int rr = r;
            TextView b = new TextView(this);
            b.setText(roomLabel(r)); b.setTextSize(12f); b.setTypeface(Design.sansBold()); b.setGravity(Gravity.CENTER);
            b.setMaxLines(1); b.setMinHeight(Design.dp(this, 36)); b.setPadding(Design.dp(this, 4), 0, Design.dp(this, 4), 0);
            androidx.core.widget.TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(b, 8, 12, 1, android.util.TypedValue.COMPLEX_UNIT_SP);
            // two-finger side by side includes ① and ②: a room button pairs as its room's current page
            b.setOnClickListener(v -> { if (!pairMade) selectTab(roomLast[rr]); });
            Design.pressable(b, (v, e) -> pairTouch(e, roomLast[rr]));
            LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            if (r > 0) rp.leftMargin = Design.dp(this, 6);
            rail.addView(b, rp);
            roomBtn[r] = b;
        }
        root.addView(rail);

        content = new LinearLayout(this);
        root.addView(content, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        trade = new TradeView(this); activityTab = new ActivityTab(this); ordersTab = new OrdersTab(this); historyTab = new HistoryTab(this); doorsTab = new DoorsTab(this); relaysTab = new RelaysTab(this); poolsDestroy(); assetsTab = new AssetsTab(this, track(new com.eurobuddha.pandapools.WalletView(this))); poolSwapTab = new PoolSwapTab(this, track(new com.eurobuddha.pandapools.SwapView(this))); poolsTab = new PoolsTab(this, track(new com.eurobuddha.pandapools.PoolsView(this))); myPoolsTab = new MyPoolsTab(this, track(new com.eurobuddha.pandapools.MyLpView(this)), track(new com.eurobuddha.pandapools.ActivityView(this))); marketTab = new MarketTab(this); otcTab = new OtcTab(this);
        View[] tv = tabViews();
        panes = new ScrollView[tv.length];   // each page scrolls on its own, so two side by side never drag each other
        for (int i = 0; i < tv.length; i++) {
            ScrollView pane = panes[i] = new ScrollView(this);
            pane.addView(tv[i], new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT));
            // A focused input pulls its ScrollView back to it on every relayout (a live swap list repainting below it made
            // the page jump, owner 4 Nov). The pane itself can hold focus, and an input scrolled off screen hands it over.
            pane.setFocusableInTouchMode(true);
            pane.setDescendantFocusability(android.view.ViewGroup.FOCUS_BEFORE_DESCENDANTS);
            pane.setOnScrollChangeListener((v, x, y, ox, oy) -> {
                View f = pane.findFocus();
                if (f instanceof android.widget.EditText && !f.getGlobalVisibleRect(new android.graphics.Rect())) {
                    ((android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(f.getWindowToken(), 0);
                    pane.requestFocus();
                }
            });
        }

        stageTv = new TextView(this);
        stageTv.setTextSize(10f); stageTv.setTypeface(Design.mono()); stageTv.setTextColor(Design.TEXT());
        stageTv.setPadding(pad, Design.dp(this, 6), pad, Design.dp(this, 6));
        stageTv.setBackgroundColor(Design.SURFACE2());
        stageTv.setVisibility(View.GONE);
        // Tap → ACTIVITY while a trade is being followed live; long-press always copies the full line.
        stageTv.setOnClickListener(v -> { if (liveActId != null) selectTab(TAB_ACTIVITY); else copy("Status", stageTv.getText().toString()); });
        stageTv.setOnLongClickListener(v -> { copy("Status", stageTv.getText().toString()); return true; });
        root.addView(stageTv);

        // AtomiX's bottom bar (apks/atomix buildTabBar): icon over label, a soft accent tile on the selected tab. The
        // room's three pages first, then the three shared pages; other rooms' pages are hidden, so it always shows six.
        tabBar = new LinearLayout(this);
        tabBar.setBackgroundColor(Design.SURFACE());
        tabBar.setPadding(Design.dp(this, 6), Design.dp(this, 7), Design.dp(this, 6), Design.dp(this, 9));
        java.util.List<Integer> order = new java.util.ArrayList<>();
        for (int[] pages : ROOM_PAGES) for (int t : pages) order.add(t);
        for (int t : SHARED) order.add(t);
        for (int idx : order) {
            LinearLayout cell = new LinearLayout(this);
            cell.setOrientation(LinearLayout.VERTICAL); cell.setGravity(Gravity.CENTER);
            cell.setPadding(0, Design.dp(this, 6), 0, Design.dp(this, 5)); cell.setMinimumHeight(Design.dp(this, 48));
            android.widget.ImageView ic = new android.widget.ImageView(this);
            ic.setImageResource(TAB_ICONS[idx]);
            cell.addView(ic, new LinearLayout.LayoutParams(Design.dp(this, 22), Design.dp(this, 22)));
            TextView t = new TextView(this);
            t.setText(TAB_NAMES[idx]); t.setTextSize(10.5f); t.setTypeface(Design.sans()); t.setGravity(Gravity.CENTER); t.setMaxLines(1);
            t.setPadding(0, Design.dp(this, 4), 0, 0);
            // the Fold's cover screen with six tabs: labels shrink to fit rather than wrap
            androidx.core.widget.TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(t, 7, 11, 1, android.util.TypedValue.COMPLEX_UNIT_SP);
            cell.addView(t, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
            cell.setContentDescription(TAB_NAMES[idx]);
            // Wide screen (owner 2026-10-26): one tap = that page alone; a second finger on another tab while one is held
            // (or both tapped together) = the two pages side by side, first touched on the left.
            cell.setOnClickListener(v -> { if (!pairMade) selectTab(idx); });
            Design.pressable(cell, (v, e) -> pairTouch(e, idx));   // the press feel AND the pairing (pressable owns the touch listener)
            tabCell[idx] = cell; tabIcon[idx] = ic; tabBtn[idx] = t;
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            lp.leftMargin = Design.dp(this, 3); lp.rightMargin = Design.dp(this, 3);
            tabBar.addView(cell, lp);
        }
        root.addView(tabBar);
        paintCcy();
        return root;
    }

    /** Hold one page button and touch another: the two pages side by side (wide screens), first touched on the left. */
    private boolean pairTouch(android.view.MotionEvent e, int idx) {
        int a = e.getActionMasked();
        if (a == android.view.MotionEvent.ACTION_DOWN) {
            if (tabsDown == 0) pairMade = false;
            tabsDown++;
            if (heldTab >= 0 && heldTab != idx && com.eurobuddha.dexicon.ui.Wide.on(this)) { pairMade = true; showPages(heldTab, idx); }
            else if (heldTab < 0) heldTab = idx;
        } else if (a == android.view.MotionEvent.ACTION_UP || a == android.view.MotionEvent.ACTION_CANCEL) {
            tabsDown = Math.max(0, tabsDown - 1);
            if (heldTab == idx || tabsDown == 0) heldTab = -1;
        }
        return false;
    }

    // ---- the one active AtomiX market (⇄ pill) ----

    String roomLabel(int r) {
        return ROOMS[r];
    }

    /** Label, accessibility text, room subtitle and glow for the active market. */
    private void paintCcy() {
        if (ccyPill == null) return;
        com.eurobuddha.atomix.TradingContext m = com.eurobuddha.dexicon.doors.DoorHost.get(this).market();
        String c = com.eurobuddha.dexicon.doors.DoorPlan.coin(m), o = com.eurobuddha.dexicon.doors.DoorPlan.coin(m.other());
        ccyPill.setText("⇄  " + c);
        ccyPill.setContentDescription("Trading " + c + ". Tap to switch to " + o + ".");
        if (roomBtn[0] != null) roomBtn[0].setText(roomLabel(0));
        startCcyGlow();
    }

    /** (Re)start the pill's glow, cancelling any previous one (AtomiX startCcyGlow). Never animates a hidden pill. */
    private void startCcyGlow() {
        stopCcyGlow();
        if (ccyPill == null || !FOREGROUND || Boolean.TRUE.equals(gateShown)) return;   // never under the gate
        com.eurobuddha.atomix.TradingContext m = com.eurobuddha.dexicon.doors.DoorHost.get(this).market();
        ccyGlowAnim = Design.glow(ccyPill, Design.ccyRing(this, DoorsTab.soft(m), m.other().accent, m == com.eurobuddha.atomix.TradingContext.MINIMA), Design.glowLevel(this));
    }

    private void stopCcyGlow() { if (ccyGlowAnim != null) { ccyGlowAnim.cancel(); ccyGlowAnim = null; } }

    /** AtomiX switchCurrencyDialog, worded for Dexicon's ① room. */
    private void switchCurrencyDialog() {
        com.eurobuddha.dexicon.doors.DoorHost host = com.eurobuddha.dexicon.doors.DoorHost.get(this);
        final com.eurobuddha.atomix.TradingContext target = host.market().other();
        String to = com.eurobuddha.dexicon.doors.DoorPlan.coin(target), from = com.eurobuddha.dexicon.doors.DoorPlan.coin(host.market());
        track(new AlertDialog.Builder(this, Design.dialogTheme())
                .setTitle("Switch to " + to + "?")
                .setMessage("AtomiX publishes and market-makes in ONE currency at a time. Switching to " + to
                        + " re-themes ① Dollars in and moves your swaps, order/market-making and OTC to the " + to + " book.\n\nYour current "
                        + from + " orders simply stop being maintained (a live market is withdrawn from the book). Any in-flight swaps in "
                        + "either currency keep settling safely in the background — switching never strands them.")
                .setPositiveButton("Switch to " + to, (d, w) -> {
                    if (host.makerLive()) setStage("Withdrawing your " + from + " market…");   // AtomiX shows the wait too
                    String err = host.switchMarket(target, () -> {
                        dismissDialogs();   // an editor / offer opened for the leaving market must not save into the new one
                        setStage(""); paintCcy(); repaint(); toast("Now on the " + to + " market");
                    });
                    if (err != null) { setStage(""); toast(err); }
                })
                .setNegativeButton("Cancel", null)
                .show());
    }

    /** Switch to a tab (e.g. ATOMIX → "Finished swaps are in History ›"). */
    public void showTab(int idx) { selectTab(idx); }

    private void selectTab(int idx) { showPages(idx, -1); }

    /** Show page {@code left} alone, or with {@code right} beside it (wide screens only). */
    void showPages(int left, int right) {
        if (right == left || !com.eurobuddha.dexicon.ui.Wide.on(this)) right = -1;
        tab = left; tab2 = right;
        content.removeAllViews();
        int gap = Design.dp(this, 1);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f);
        content.addView(panes[left], lp);
        if (right >= 0) {
            LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f);
            rp.leftMargin = gap;
            content.addView(panes[right], rp);
        }
        View[] views = tabViews();
        for (int i = 0; i < views.length; i++) views[i].setVisibility(i == left || i == right ? View.VISIBLE : View.GONE);
        trade.setSolo(right < 0);
        for (int t : new int[]{left, right}) poolPageShown(t);   // a ② page coming into view refreshes like a PandaPools tab
        int r = roomOf(left);
        if (r >= 0) { room = r; roomLast[r] = left; }
        for (int i = 0; i < tabCell.length; i++) {
            if (tabCell[i] == null) continue;
            boolean on = i == left || i == right;
            tabCell[i].setBackground(Design.roundBg(this, on ? Design.ACCENT_SOFT() : 0x00000000, 14));
            tabIcon[i].setColorFilter(on ? Design.TEXT() : Design.DIM());
            tabBtn[i].setTextColor(on ? Design.TEXT() : Design.DIM());
            tabBtn[i].setTypeface(on ? Design.sansBold() : Design.sans());
            tabCell[i].setSelected(on);
            if (!isShared(i)) tabCell[i].setVisibility(roomOf(i) == room ? View.VISIBLE : View.GONE);
        }
        for (int i = 0; i < 3; i++) {
            boolean on = i == room;
            roomBtn[i].setTextColor(on ? Design.ON_ACCENT() : Design.DIM());
            roomBtn[i].setBackground(on ? Design.roundBg(this, Design.ACCENT(), 10) : Design.stroked(this, Design.SURFACE2(), 10));
        }
        repaint();
    }

    /** Folding the phone closes the second page. */
    @Override public void onConfigurationChanged(android.content.res.Configuration c) {
        super.onConfigurationChanged(c);
        if (tabBar != null && tab2 >= 0 && !com.eurobuddha.dexicon.ui.Wide.on(this)) selectTab(tab);
    }

    private View[] tabViews() { return new View[]{trade, activityTab, ordersTab, historyTab, assetsTab, doorsTab, relaysTab, poolsTab, marketTab, otcTab, poolSwapTab, myPoolsTab}; }

    /** A vendored PandaPools screen joins this Activity's pool-screen fan-out (new block, stop, destroy). */
    private <V extends com.eurobuddha.pandapools.BaseView> V track(V v) { poolViews.add(v); return v; }

    /** PandaPools' own tab numbers (the vendored screens ask for and jump to these), as Dexicon's ② pages. */
    private static final int PP_SWAP = com.eurobuddha.pandapools.MainActivity.TAB_SWAP, PP_POOLS = com.eurobuddha.pandapools.MainActivity.TAB_POOLS,
            PP_MYLP = com.eurobuddha.pandapools.MainActivity.TAB_MYLP, PP_ACTIVITY = com.eurobuddha.pandapools.MainActivity.TAB_ACTIVITY;

    @Override public int currentTab() {
        // its only reader is PandaPools' ActivityView ("am I on screen?"): My pools on EITHER side of a two-page Fold
        // view wins, or a pool swap on the left would stop the activity under My pools on the right from refreshing
        if (tab == TAB_PMINE || tab2 == TAB_PMINE) return PP_ACTIVITY;   // PandaPools' pool activity sits under My pools
        for (int t : new int[]{tab, tab2}) {
            if (t == TAB_PSWAP) return PP_SWAP;
            if (t == TAB_POOLS) return PP_POOLS;
            if (t == TAB_PMINE) return PP_ACTIVITY;   // PandaPools' pool activity sits under My pools
        }
        return -1;
    }

    @Override public void goToTab(int index) {
        if (index == PP_SWAP) selectTab(TAB_PSWAP);
        else if (index == PP_POOLS) selectTab(TAB_POOLS);
        else if (index == PP_MYLP || index == PP_ACTIVITY) selectTab(TAB_PMINE);
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        int page = intent == null ? -1 : intent.getIntExtra(EXTRA_POOLS_PAGE, -1);
        if (page >= 0 && tabBar != null) goToTab(page);
    }

    static boolean isShared(int t) { for (int s : SHARED) if (s == t) return true; return false; }
    static int roomOf(int t) { for (int r = 0; r < ROOM_PAGES.length; r++) for (int p : ROOM_PAGES[r]) if (p == t) return r; return -1; }

    /** The live line for the followed ACTIVITY row; null once it has been settled for two minutes. */
    private String liveLine() {
        if (liveActId == null || db == null) return null;
        JSONObject a = db.activity(liveActId);
        if (a == null) { liveActId = null; return null; }
        long now = System.currentTimeMillis();
        if (ActivityLog.settled(a, chainBlock)) {
            if (!a.has("settledMs")) ActivityLog.update(db, liveActId, x -> x.put("settledMs", now));
            else if (now - a.optLong("settledMs") > 120_000) { liveActId = null; return null; }
        }
        return (a.optString("side").equals("buy") ? "BUY " : "SELL ") + a.optString("amount") + " MINIMA · " + a.optString("route") + " · "
                + ActivityLog.statusText(a, chainBlock, now) + "   ▸ ACTIVITY";
    }

    void repaintHeader() {
        if (pairPill == null) return;
        String nodeName = NodeApi.nodeApp(this)[1];
        String ps = !pairingKnown ? "CONNECTING TO " + nodeName.toUpperCase(java.util.Locale.ROOT) + "…"
                : !paired ? (node != null && node.needsAdmin() ? "GIVE DEXICON ADMIN IN " + nodeName.toUpperCase(java.util.Locale.ROOT) + " → APPS  ▸"
                           : node != null && node.answered() ? "ENABLE DEXICON IN " + nodeName.toUpperCase(java.util.Locale.ROOT) + " → APPS  ▸"
                                                            : "WAITING FOR " + nodeName.toUpperCase(java.util.Locale.ROOT) + " — OPEN / UNLOCK IT  ▸")
                : me == null ? "PAIRED · deriving identity" : "PAIRED";
        pairPill.setText(ps);
        pairPill.setTextColor(paired ? Design.IN() : Design.DIM());
        blockPill.setText("# " + (chainBlock > 0 ? String.valueOf(chainBlock) : "—"));
        String live = liveLine();
        String line = live == null ? stage : stage.isEmpty() ? live : stage + "\n" + live;
        stageTv.setText(line);
        stageTv.setVisibility(line.isEmpty() ? View.GONE : View.VISIBLE);
    }

    public void repaint() {
        if (closed || trade == null) return;
        repaintHeader();
        render(tab);
        if (tab2 >= 0) render(tab2);
    }

    private void render(int t) {
        switch (t) {
            case TAB_TRADE: trade.render(); break;
            case TAB_ACTIVITY: activityTab.render(); break;
            case TAB_ORDERS: ordersTab.render(); break;
            case TAB_HISTORY: historyTab.render(); break;
            case TAB_ASSETS: assetsTab.render(); break;
            case TAB_DOORS: doorsTab.render(); break;
            case TAB_POOLS: poolsTab.render(); break;
            case TAB_PSWAP: break;   // PandaPools' SwapView repaints itself
            case TAB_PMINE: myPoolsTab.render(); break;
            case TAB_MARKET: marketTab.render(); break;
            case TAB_OTC: otcTab.render(); break;
            default: relaysTab.render();
        }
    }

    /** PandaPools MainActivity.onCreate: AlertDialog chrome follows the SYSTEM day/night unless the Activity's local night
     *  mode is bound to the palette — on a light phone with Dexicon dark, the vendored pool dialogs (create, add,
     *  migrate, backup password) painted white with white text. Dexicon handles uiMode itself (manifest), so no recreate. */
    private void nightModeFollowsDesign() {
        getDelegate().setLocalNightMode(Design.isDark() ? androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES : androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO);
    }

    private void poolPageShown(int t) {
        if (t == TAB_ASSETS) assetsTab.shown();   // PandaPools' wallet lives on ASSETS
        if (t == TAB_PSWAP) poolSwapTab.shown();
        if (t == TAB_POOLS) poolsTab.shown();
        if (t == TAB_PMINE) myPoolsTab.shown();
    }

    @Override protected void onResume() {
        super.onResume();
        // PandaPools re-shows its visible tab on resume: onStop stopped the pool views, and coming back from the forced
        // backup's file picker must restart My pools' fast "confirming" poll, not wait for a page switch.
        if (chromeRoot != null) { poolPageShown(tab); if (tab2 >= 0) poolPageShown(tab2); }
        FOREGROUND = true;
        startCcyGlow();   // the pill survived the pause; re-arm exactly one animator on it
        if (node != null && !paired) node.reRegister();   // back from the node app's Apps screen: re-ask at once
        applySettings();
        // who keeps each pool, as of now — read off the main thread; the gate / ② My pools repaint with the answer
        com.eurobuddha.pandapools.DexiconPools.readCustodyAsync(this, () -> { if (!isFinishing() && !isDestroyed()) { if (gate != null) gate.render(); repaint(); } });
        recheckGate();
        bookLoop();
        repaint();
    }

    // ---- one trading app per phone ----

    /** Shows the gate instead of the trading screens while AtomiX or PandaPools is present (or the node's MiniHub has
     *  not been read yet), and the screens once both are gone. Shutting stops every trade this screen could still fire. */
    void applyGate() {
        if (closed || chromeRoot == null) return;
        boolean blocked = TradingGate.blocked(this);
        if (lastBlocked == null || lastBlocked != blocked) {   // the gate moved: the DOORS responder guard and a live ① market follow NOW
            lastBlocked = blocked;
            try { com.eurobuddha.dexicon.doors.DoorHost.get(this).gateChanged(); } catch (RuntimeException e) { android.util.Log.w("Dexicon", "gate → doors: " + e); }
        }
        if (blocked) {
            if (!Boolean.TRUE.equals(gateShown)) { haltTrading(); gate = new GateView(this); setContentView(gate); gateShown = true; }
            gate.render();
        } else if (!Boolean.FALSE.equals(gateShown)) {
            gate = null; gateShown = false;
            setContentView(chromeRoot);
            startCcyGlow();
            repaint();
        }
    }

    /** The gate shut: end a one-click run, forget a pending remainder or retry, close confirm / edit dialogs. */
    private void haltTrading() {
        gateGen++;
        if (run != null) endRun();
        if (db != null) { db.setMeta("rest_due", ""); db.setMeta("retry", ""); db.setMeta("retry_due", ""); }
        dismissDialogs();
        stopCcyGlow();
    }

    private Boolean lastBlocked;

    /** Apply what is known NOW (an app just installed / removed), then re-read the MiniHub and apply again. */
    void recheckGate() { applyGate(); if (node != null && paired) TradingGate.checkMiniHub(this, node, this::applyGate); }

    /** Opens the app's own "Hand over to Dexicon" step; it answers only to Dexicon (it checks the calling package).
     *  {@code remove}: on a FRESH yes for the installed version, uninstall at once — nothing is stored to replay. */
    void startHandover(String pkg, String name, boolean remove) {
        long now = System.currentTimeMillis();
        if (handoverPkg != null && now - handoverAt < 60_000) return;   // a double tap opens it once
        handoverPkg = pkg; handoverName = name; handoverRemove = remove; handoverAt = now;
        Intent ask = new Intent(TradingGate.ACTION_HANDOVER).setPackage(pkg);
        // PandaPools hands a live pool over only once Dexicon holds its recipe: name every covenant address held here, in full.
        if (TradingGate.PANDAPOOLS.equals(pkg)) ask.putExtra(com.eurobuddha.pandapools.DexiconPools.EXTRA_DEXICON_POOLS, com.eurobuddha.pandapools.DexiconPools.heldAddresses(this));
        try { handover.launch(ask); }
        catch (android.content.ActivityNotFoundException e) {
            handoverPkg = null;
            explain(name + " needs an update", "This version of " + name + " has no \"Hand over to Dexicon\" step yet. Update " + name + " from the store, then try again.");
        }
    }

    void onHandover(int code, Intent d) {
        String pkg = handoverPkg, name = handoverName;
        boolean remove = handoverRemove;
        handoverPkg = null; handoverName = null; handoverRemove = false;
        if (pkg == null) { pkg = TradingGate.gatedApp(d == null ? null : d.getStringExtra("app")); remove = false; }   // lost: answer names it
        if (pkg == null) { applyGate(); return; }
        if (name == null) name = TradingGate.ATOMIX.equals(pkg) ? "AtomiX" : "PandaPools";
        long ver = TradingGate.versionOf(this, pkg);
        if (TradingGate.validHandover(code == RESULT_OK, d, pkg, ver) && TradingGate.sameSigner(this, pkg)) {
            TradingGate.noteAnswer(this, pkg, ver);
            if (TradingGate.PANDAPOOLS.equals(pkg)) poolsHandedOver(d.getStringArrayExtra(com.eurobuddha.pandapools.DexiconPools.EXTRA_POOLS));
            if (remove) startActivity(new Intent(Intent.ACTION_DELETE, android.net.Uri.parse("package:" + pkg)));
            else toast(name + " handed over. You can remove it now.");
        } else {
            TradingGate.noteAnswer(this, pkg, -2);   // the card follows the LAST answer
            String why = d == null ? null : d.getStringExtra("reason");
            why = why == null || why.isEmpty() ? name + " did not confirm the hand-over, so nothing changed." : why;
            if (TradingGate.PANDAPOOLS.equals(pkg)) {
                // Custody comes only from PandaPools' own provider: re-read it (off the main thread) and say which pools
                // PandaPools keeps now.
                final String said = why, who = name;
                com.eurobuddha.pandapools.DexiconPools.readCustodyAsync(this, () -> {
                    if (isFinishing() || isDestroyed()) return;
                    List<String> there = com.eurobuddha.pandapools.DexiconPools.keptElsewhereNow(this);
                    explain(who + " is not ready to remove", there.isEmpty() ? said : said + "\n\nDexicon leaves " + (there.size() == 1 ? "this pool" : "these pools")
                            + " to PandaPools for now:\n" + String.join("\n", there));
                });
            } else explain(name + " is not ready to remove", why);
        }
        applyGate();
    }

    // ---- ② moving a live pool from standalone PandaPools ----

    /** The gate's PandaPools card: last restore result (full addresses, selectable). */
    String poolRestoreStatus = "";

    /** "Restore your PandaPools backup here": PandaPools' own verify + restore. A pool PandaPools still keeps is left to it. */
    void restorePandaPoolsBackup() {
        pickOpenFile(uri -> {
            if (uri == null) return;
            com.eurobuddha.pandapools.DexiconPools.restoreBackup(this, uri, line -> { poolRestoreStatus = line; if (gate != null) gate.render(); },
                    (restored, total, addresses, report) -> {
                        poolRestoreStatus = total == 0 ? report : restored + " of " + total + " pool(s) restored with verified reserves. PandaPools keeps "
                                + "them until you hand over; Dexicon starts keeping each one only once PandaPools says it has stopped.\n\n" + report;
                        if (gate != null) gate.render();
                        repaint();
                    });
        });
    }

    /** PandaPools said OK. Custody is NOT taken from the answer — only from PandaPools' provider, re-read now. Of the named
     *  pools, those PandaPools no longer keeps come back with signing paused (PandaPools' rule); the user attests the
     *  wallet's signing state once, here, before keep-fresh signs for them. */
    private void poolsHandedOver(String[] pools) {
        if (pools == null || pools.length == 0) return;
        com.eurobuddha.pandapools.DexiconPools.readCustodyAsync(this, () -> {   // the provider read runs off the main thread
            if (isFinishing() || isDestroyed()) return;
            List<String> mine = new ArrayList<>();
            for (String p : pools) if (p != null && !com.eurobuddha.pandapools.DexiconPools.keptElsewhere(this, p)) mine.add(p);
            if (mine.isEmpty()) return;
            com.eurobuddha.pandapools.DexiconPools.offerSigningConfirmation(this, mine, this::repaint);
        });
    }

    @Override protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putString("ho_pkg", handoverPkg); out.putString("ho_name", handoverName); out.putBoolean("ho_remove", handoverRemove); out.putLong("ho_at", handoverAt);
    }

    private void explain(String title, String body) {
        new AlertDialog.Builder(this, Design.dialogTheme()).setTitle(title).setMessage(body).setPositiveButton("OK", null).show();
    }

    @Override protected void onPause() { super.onPause(); FOREGROUND = false; stopCcyGlow(); }

    @Override protected void onStop() { super.onStop(); poolsStop(); }   // PandaPools: backgrounded → views cancel their polls   // never animate a pill nobody sees

    @Override protected void onDestroy() {
        closed = true;
        ui.removeCallbacksAndMessages(null);
        stopCcyGlow(); ccyPill = null;
        if (changedReceiver != null) try { unregisterReceiver(changedReceiver); } catch (Exception ignored) {}
        if (nodeReceiver != null) try { unregisterReceiver(nodeReceiver); } catch (Exception ignored) {}
        if (trade != null) trade.close();
        if (restingRepo != null) restingRepo.close();
        poolsClose();
        if (node != null) node.onDestroy();
        net.shutdownNow();
        quoteExec.shutdownNow();
        super.onDestroy();
    }
}
