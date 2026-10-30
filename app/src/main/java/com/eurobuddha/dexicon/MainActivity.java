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
public class MainActivity extends AppCompatActivity {

    public static volatile boolean FOREGROUND = false;
    private static final long POLL_MS = 30_000;
    public static final int TAB_TRADE = 0, TAB_ACTIVITY = 1, TAB_ORDERS = 2, TAB_HISTORY = 3, TAB_ASSETS = 4, TAB_DOORS = 5, TAB_RELAYS = 6;
    private static final String[] TAB_NAMES = {"TRADE", "ACTIVITY", "ORDERS", "HISTORY", "ASSETS", "ATOMIX", "RELAYS"};

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

    private TradeView trade;
    private ActivityTab activityTab;
    private OrdersTab ordersTab;
    private HistoryTab historyTab;
    private AssetsTab assetsTab;
    private RelaysTab relaysTab;
    private DoorsTab doorsTab;
    private LinearLayout content;          // the visible page(s): one, or two side by side on a wide screen
    private ScrollView[] panes;
    private LinearLayout tabBar;
    private TextView pairPill, blockPill, stageTv;
    private int tab = TAB_TRADE, tab2 = -1;   // tab2: the right-hand page when two are open side by side (wide only)
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
        setContentView(buildChrome());
        selectTab(tab);

        node = new NodeApi(this, enabled -> {
            paired = enabled; pairingKnown = true;
            if (enabled) onPaired();
            repaint();
        });
        restingRepo = new BookRepository(node);
        poolRepo = new PoolLiquidityRepository(node);
        restingTxn = new DexTxn(node);
        restingRepo.subscribe((orders, syncing) -> repaint());
        poolRepo.subscribe((pools, syncing) -> repaint());
        changedReceiver = new BroadcastReceiver() { @Override public void onReceive(Context c, Intent i) { followTake(); settleStage(); repaint(); retryIfDue(); restIfDue(); warmWallet(); } };
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
    List<Pool> pools() { return poolRepo == null || !restingOn() || !poolRepo.current() ? java.util.Collections.emptyList() : poolRepo.pools(); }

    private void deriveIdentity() {
        if (deriving) return;
        deriving = true;
        node.cmd("vault action:seed", new NodeApi.Cb() {
            public void onResult(JSONObject j) {
                JSONObject r = j.optJSONObject("response");
                String seed = r == null ? "" : r.optString("seed", r.optString("phrase", ""));
                if (seed.isEmpty()) { deriving = false; setStage("Identity: the node returned no seed (vault locked?). Unlock and reopen."); return; }
                net.execute(() -> {
                    try {
                        DexIdentity id = DexIdentity.fromSeed(Sodium.get(), seed);
                        ui.post(() -> { me = id; engine = new MakerEngine(MainActivity.this, node, db, relay, me); deriving = false; repaint(); });
                    } catch (Exception e) { ui.post(() -> { deriving = false; setStage("Identity derivation failed: " + e.getMessage()); }); }
                });
            }
            public void onError(String m) { deriving = false; setStage(NodeApi.ERR_NOT_ENABLED.equals(m) ? "Enable Dexicon in Minima Core → Apps" : "Seed read failed: " + m); }
        });
    }

    private void readBlock() {
        node.cmd("block", new NodeApi.Cb() {
            public void onResult(JSONObject j) {
                JSONObject r = j.optJSONObject("response");
                long was = chainBlock;
                try { chainBlock = Long.parseLong(r.optString("block", "0")); } catch (Exception ignored) {}
                restingRepo.setChainBlock(chainBlock); restingTxn.setChainBlock(chainBlock);
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
    long chainBlock() { return chainBlock; }
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
        if (me == null || engine == null) { toast("Identity not ready — is Dexicon enabled in Minima Core?"); return; }
        if (busy) { toast("A transaction is in progress"); return; }
        final Envelope best = bestFastCross(buy, price);
        final List<Order5> orders = new ArrayList<>(restingBook().values());
        final List<Pool> pools = new ArrayList<>(pools());
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
        new AlertDialog.Builder(this, Design.dialogTheme())
                .setTitle(buy ? "Confirm buy · BOOK/POOL" : "Confirm sell · BOOK/POOL")
                .setMessage(sb.toString())
                .setPositiveButton("Execute", (d, w) -> executeComposite(plan, buy, amount, price, 0))
                .setNegativeButton("Back", null)
                .show();
    }

    private void executeComposite(CompositeRouter.Plan plan, boolean buy, BigDecimal amount, BigDecimal price, int retry) {
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
                SignGate.submit(release -> node.cmdR("consolidate tokenid:" + token, new NodeApi.Cb() {
                    public void onResult(JSONObject x) { release.free(); waitThenRetry(buy, amount, price, token, need, chainBlock, true); }
                    public void onError(String m) { release.free(); setStage(""); toast("Could not consolidate " + name + ": " + m + " — " + why); }
                }));
            }
            public void onError(String m) { toast(why); }
        });
    }

    /** Poll once a block (≤ 10) until the token is spendable again (and, after a consolidation, settled), then re-plan the
     *  same trade at the same limit and execute it — no second confirmation: the user already confirmed this trade. */
    private void waitThenRetry(boolean buy, BigDecimal amount, BigDecimal price, String token, BigDecimal need, long startBlock, boolean consolidated) {
        ui.postDelayed(() -> {
            if (closed) return;
            if (chainBlock - startBlock > 10) { setStage(""); toast("Gave up waiting for the wallet after 10 blocks — try the trade again"); return; }
            node.cmd("balance tokenid:" + token, new NodeApi.Cb() {
                public void onResult(JSONObject j) {
                    BigDecimal sendable = BigDecimal.ZERO, unconf = BigDecimal.ZERO;
                    for (JSONObject b : NodeApi.rows(j)) if (token.equalsIgnoreCase(b.optString("tokenid"))) { sendable = Util.dec(b.optString("sendable", "0")); unconf = Util.dec(b.optString("unconfirmed", "0")); }
                    boolean ready = sendable.compareTo(need) >= 0 && (!consolidated || unconf.signum() == 0);
                    if (!ready || busy) { waitThenRetry(buy, amount, price, token, need, startBlock, consolidated); return; }
                    final List<Order5> orders = new ArrayList<>(restingBook().values());
                    final List<Pool> pools = new ArrayList<>(pools());
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
        new AlertDialog.Builder(this, Design.dialogTheme()).setTitle("Edit order").setView(box)
                .setPositiveButton("Update", (d, w) -> {
                    busy = true; repaint();
                    if (engine == null) { busy = false; toast("Not paired — try again once PAIRED"); repaint(); return; }
                    engine.edit(o.orderId, price.getText().toString().trim(), amount.getText().toString().trim(), (err, x) -> { busy = false; setStage(err != null ? "Edit failed: " + err : "Order updated"); repaint(); });
                }).setNegativeButton("Back", null).show();
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
                        .put("maker", order.pubId).put("outs", new JSONArray());
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
                                logStep("No answer from the maker in 60s. The take stays open for 5 minutes and settles here if the maker co-signs.");
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
                logStep("  maker refused: " + why + (why.matches("(?i).*(taken|not live|advertised|spent|visible|cover).*") ? " — next order" : ""));
                if (why.matches("(?i).*(taken|not live|advertised|spent|visible|cover).*")) { r.act = null; nextOrder(); } else endRun();
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

    private static JSONObject planJson(FillPlan.Plan p) throws Exception {
        return new JSONObject().put("filled", p.filled).put("takerGives", p.takerGives).put("makerGives", p.makerGives).put("payToken", p.payToken).put("recvToken", p.recvToken).put("inputs", new JSONArray(p.inputs));
    }

    void exportCsv() {
        pendingCsv = TradeExport.csv(db.fills(5000));
        saveCsv.launch("dexicon-fills-" + System.currentTimeMillis() / 1000 + ".csv");
    }

    private void confirm(String title, String body, Runnable yes) {
        new AlertDialog.Builder(this, Design.dialogTheme()).setTitle(title).setMessage(body)
                .setPositiveButton("Confirm", (d, w) -> yes.run()).setNegativeButton("Back", null).show();
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
        themePill.setOnClickListener(v -> { Design.toggle(this); if (trade != null) trade.close(); setContentView(buildChrome()); showPages(tab, tab2); });
        Design.pressable(themePill);
        header.addView(themePill);
        root.addView(header);

        LinearLayout nodeRow = new LinearLayout(this);
        nodeRow.setGravity(Gravity.CENTER_VERTICAL);
        nodeRow.setPadding(pad, 0, pad, Design.dp(this, 6));
        pairPill = Design.pill(this, "CONNECTING…", Design.SURFACE2(), Design.DIM());
        pairPill.setOnClickListener(v -> {   // not paired: one tap to the node app (its Apps screen holds the toggle)
            if (paired) return;
            String pkg = NodeApi.nodeApp(this)[0];
            android.content.Intent open = pkg.isEmpty() ? null : getPackageManager().getLaunchIntentForPackage(pkg);
            if (open != null) startActivity(open); else toast("Install or start your Minima node app first");
        });
        nodeRow.addView(pairPill, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        blockPill = Design.pill(this, "# —", Design.SURFACE2(), Design.DIM());
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        bp.leftMargin = Design.dp(this, 6);
        nodeRow.addView(blockPill, bp);
        root.addView(nodeRow);

        content = new LinearLayout(this);
        root.addView(content, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        trade = new TradeView(this); activityTab = new ActivityTab(this); ordersTab = new OrdersTab(this); historyTab = new HistoryTab(this); assetsTab = new AssetsTab(this); doorsTab = new DoorsTab(this); relaysTab = new RelaysTab(this);
        View[] tv = tabViews();
        panes = new ScrollView[tv.length];   // each page scrolls on its own, so two side by side never drag each other
        for (int i = 0; i < tv.length; i++) {
            panes[i] = new ScrollView(this);
            panes[i].addView(tv[i], new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT));
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

        tabBar = new LinearLayout(this);
        tabBar.setBackgroundColor(Design.SURFACE());
        for (int i = 0; i < TAB_NAMES.length; i++) {
            final int idx = i;
            TextView t = new TextView(this);
            t.setText(TAB_NAMES[i]); t.setTextSize(10f); t.setTypeface(Design.sansBold()); t.setGravity(Gravity.CENTER); t.setMaxLines(1);
            // Seven tabs: a narrow phone (and the Fold's cover screen) shrinks the labels to fit (min 6sp); wide screens keep 10sp.
            androidx.core.widget.TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(t, 6, 10, 1, android.util.TypedValue.COMPLEX_UNIT_SP);
            t.setPadding(0, Design.dp(this, 11), 0, Design.dp(this, 11)); t.setMinHeight(Design.dp(this, 48));
            // Wide screen (owner 2026-10-26): one tap = that page alone; a second finger on another tab while one is held
            // (or both tapped together) = the two pages side by side, first touched on the left.
            t.setOnTouchListener((v, e) -> {
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
            });
            t.setOnClickListener(v -> { if (!pairMade) selectTab(idx); });
            tabBar.addView(t, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        }
        root.addView(tabBar);
        return root;
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
        for (int i = 0; i < tabBar.getChildCount(); i++) {
            TextView t = (TextView) tabBar.getChildAt(i);
            boolean on = i == left || i == right;
            t.setTextColor(on ? Design.ON_ACCENT() : Design.DIM());
            t.setBackground(on ? Design.roundBg(this, Design.ACCENT(), 10) : null);
        }
        repaint();
    }

    /** Folding the phone closes the second page. */
    @Override public void onConfigurationChanged(android.content.res.Configuration c) {
        super.onConfigurationChanged(c);
        if (tabBar != null && tab2 >= 0 && !com.eurobuddha.dexicon.ui.Wide.on(this)) selectTab(tab);
    }

    private View[] tabViews() { return new View[]{trade, activityTab, ordersTab, historyTab, assetsTab, doorsTab, relaysTab}; }

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
                : !paired ? (node != null && node.answered() ? "ENABLE DEXICON IN " + nodeName.toUpperCase(java.util.Locale.ROOT) + " → APPS  ▸"
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
            default: relaysTab.render();
        }
    }

    @Override protected void onResume() {
        super.onResume();
        FOREGROUND = true;
        if (node != null && !paired) node.reRegister();   // back from the node app's Apps screen: re-ask at once
        applySettings();
        bookLoop();
        repaint();
    }

    @Override protected void onPause() { super.onPause(); FOREGROUND = false; }

    @Override protected void onDestroy() {
        closed = true;
        ui.removeCallbacksAndMessages(null);
        if (changedReceiver != null) try { unregisterReceiver(changedReceiver); } catch (Exception ignored) {}
        if (nodeReceiver != null) try { unregisterReceiver(nodeReceiver); } catch (Exception ignored) {}
        if (trade != null) trade.close();
        if (restingRepo != null) restingRepo.close();
        if (node != null) node.onDestroy();
        net.shutdownNow();
        quoteExec.shutdownNow();
        super.onDestroy();
    }
}
