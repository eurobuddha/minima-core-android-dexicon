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
    static final int TAB_TRADE = 0, TAB_ACTIVITY = 1, TAB_ORDERS = 2, TAB_HISTORY = 3, TAB_ASSETS = 4, TAB_RELAYS = 5;
    private static final String[] TAB_NAMES = {"TRADE", "ACTIVITY", "ORDERS", "HISTORY", "ASSETS", "RELAYS"};

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
    private String receiveAddr = "";
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
    private FrameLayout content;
    private LinearLayout tabBar;
    private TextView pairPill, blockPill, stageTv;
    private int tab = TAB_TRADE;

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
                if (tab == TAB_ACTIVITY && ++liveTicks % 5 == 0) activityTab.render();
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
        changedReceiver = new BroadcastReceiver() { @Override public void onReceive(Context c, Intent i) { settleStage(); repaint(); retryIfDue(); restIfDue(); } };
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
                if (chainBlock != was) refreshResting();   // book + pools follow the chain, once per new block
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
                    if (Tokens.isMinima(tok)) { minimaSendable = Util.dec(b.optString("sendable", "0")); minimaConfirmed = Util.dec(b.optString("confirmed", "0")); }
                    else if (Tokens.isMxUsd(tok)) { mxusdSendable = Util.dec(b.optString("sendable", "0")); mxusdConfirmed = Util.dec(b.optString("confirmed", "0")); }
                }
                balanceAtMs = System.currentTimeMillis();
                repaint();
            }
            public void onError(String m) {}
        });
        if (receiveAddr.isEmpty()) node.cmd("getaddress", new NodeApi.Cb() {
            public void onResult(JSONObject j) { JSONObject r = j.optJSONObject("response"); if (r != null) receiveAddr = r.optString("address", ""); restingTxn.setPayout(receiveAddr); repaint(); }
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
            ui.post(() -> { bookPolling = false; if (closed) return; repaint(); ui.postDelayed(this::bookLoop, changed < 0 ? 2000 : 0); });
        });
    }

    // ---- accessors for the tabs ----
    DexDb db() { return db; }
    RelayClient relay() { return relay; }
    DexIdentity identity() { return me; }
    long chainBlock() { return chainBlock; }
    boolean isBusy() { return busy; }
    boolean paired() { return paired; }
    String receiveAddress() { return receiveAddr; }
    BigDecimal minimaSendable() { return minimaSendable; }
    BigDecimal mxusdSendable() { return mxusdSendable; }
    BigDecimal minimaConfirmed() { return minimaConfirmed; }
    BigDecimal mxusdConfirmed() { return mxusdConfirmed; }
    long balanceAtMs() { return balanceAtMs; }
    List<Envelope> book() { return relay.orders(null); }
    List<MyOrder> myOrders() { return db.myOrders(); }
    boolean isMine(Envelope e) { return me != null && e.signPk.equalsIgnoreCase(me.signPkHex()); }

    void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }
    void setStage(String s) { stage = s == null ? "" : s; repaintHeader(); }
    void copy(String label, String value) {
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

    /** The FAST path (unchanged from 0.1.7): take the best crossing fast order, else rest as a fast order. */
    private void placeFast(boolean buy, BigDecimal price, BigDecimal amount, Envelope best) {
        if (best != null) {
            BigDecimal now = amount.min(new BigDecimal(best.amount)), rest = amount.subtract(now);
            try { db.setMeta("retry", new JSONObject().put("buy", buy).put("price", price.toPlainString()).put("amount", amount.toPlainString()).put("rest", rest.toPlainString()).put("tried", new org.json.JSONArray().put(best.orderId)).toString()); } catch (Exception ignored) {}
            if (rest.signum() > 0) toast("Takes " + now.toPlainString() + " now; the remaining " + rest.toPlainString() + " MINIMA rests as your order once the fill is co-signed");
            previewTake(best, now); return;
        }
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
                .setPositiveButton("Execute", (d, w) -> executeComposite(plan, buy))
                .setNegativeButton("Back", null)
                .show();
    }

    private void executeComposite(CompositeRouter.Plan plan, boolean buy) {
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
                toast(message); refreshResting(); repaint();
            }
        });
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

    void retryIfDue() {
        String due = db.meta("retry_due", "");
        if (due.isEmpty()) return;
        db.setMeta("retry_due", "");
        JSONObject r; try { r = new JSONObject(db.meta("retry", "{}")); } catch (Exception e) { return; }
        if (!r.has("price")) return;
        boolean buy = r.optBoolean("buy"); BigDecimal price = new BigDecimal(r.optString("price")), amount = new BigDecimal(r.optString("amount"));
        org.json.JSONArray tried = r.optJSONArray("tried") == null ? new org.json.JSONArray() : r.optJSONArray("tried");
        java.util.Set<String> skip = new java.util.HashSet<>(); for (int i = 0; i < tried.length(); i++) skip.add(tried.optString(i));
        skip.add(due);
        Envelope next = null;
        for (Envelope e : relay.orders(buy ? "sell" : "buy")) {
            if (isMine(e) || e.coins.isEmpty() || skip.contains(e.orderId)) continue;
            BigDecimal p = new BigDecimal(e.price);
            if (buy ? p.compareTo(price) <= 0 : p.compareTo(price) >= 0) { next = e; break; }
        }
        if (next == null) { setStage("That level was taken and nothing else crosses " + Util.fmt(price) + " — place the order again to rest it."); return; }
        try { tried.put(next.orderId); r.put("tried", tried); db.setMeta("retry", r.toString()); } catch (Exception ignored) {}
        toast("That level was taken — next best is " + next.price + ". Review to retry.");
        previewTake(next, amount.min(new BigDecimal(next.amount)));
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

    /** Taker step 1: re-fetch the maker's coins, read my coins, plan, show the exact exchange, confirm. */
    void previewTake(Envelope order, BigDecimal takeAmount) {
        if (me == null) { toast("Identity not ready"); return; }
        if (busy) { toast("A transaction is in progress"); return; }
        busy = true; setStage("Checking the maker's coins…"); repaint();
        fetchCoins(order.coins, 0, new ArrayList<>(), (err, makerCoins) -> {
            if (err != null) { busy = false; setStage(err); repaint(); return; }
            boolean sell = "sell".equals(order.side);
            MakerEngine.walletCoins(node, sell ? Tokens.MXUSD() : Tokens.MINIMA, (e2, myCoins) -> {
                if (e2 != null) { busy = false; setStage("Cannot read your coins: " + e2); repaint(); return; }
                node.cmd("getaddress", new NodeApi.Cb() {
                    public void onResult(JSONObject j) {
                        JSONObject r = j.optJSONObject("response");
                        String addr = r == null ? "" : r.optString("address", "");
                        FillPlan.Plan plan;
                        try {
                            plan = FillPlan.plan(new FillPlan.Order(order.side, order.price, order.amount, order.address, order.coins, order.scripts), takeAmount.toPlainString(), makerCoins, myCoins, addr);
                        } catch (RuntimeException e) { busy = false; setStage(e.getMessage()); repaint(); return; }
                        busy = false; repaint();
                        confirm((sell ? "Buy " : "Sell ") + plan.filled + " MINIMA @ " + order.price,
                                "You give " + plan.takerGives + (sell ? " MxUSD" : " MINIMA") + " and receive " + plan.makerGives + (sell ? " MINIMA" : " MxUSD")
                                        + " (" + plan.inputs.size() + " inputs, " + plan.outputs.size() + " outputs). The maker's node co-signs; final after one block.\n\nMaker pay-to address:\n" + order.address,
                                () -> confirmTake(order, plan));
                    }
                    public void onError(String m) { busy = false; setStage("getaddress failed: " + m); repaint(); }
                });
            });
        });
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

    /** Taker step 2: build + sign my side, seal the take to the maker, publish to every relay, remember it. */
    private void confirmTake(Envelope order, FillPlan.Plan plan) {
        busy = true; setStage("");
        final String actId = ActivityLog.newId("fast_");
        boolean takerBuys = "sell".equals(order.side);
        try {
            ActivityLog.add(db, new JSONObject().put("id", actId).put("route", "FAST").put("role", "taker").put("side", takerBuys ? "buy" : "sell")
                    .put("amount", plan.filled).put("price", order.price).put("mxusd", takerBuys ? plan.takerGives : plan.makerGives)
                    .put("orderId", order.orderId).put("counterparty", order.address).put("ins", new JSONArray(plan.inputs))
                    .put("ts", System.currentTimeMillis()).put("stage", ActivityLog.BUILDING).put("reason", "Building and signing your side…"));
        } catch (Exception ignored) {}
        liveActId = actId; repaint();
        FillTxn.build(node, plan, (err, hex) -> {
            if (err != null) { busy = false; ActivityLog.update(db, actId, a -> a.put("stage", ActivityLog.FAILED).put("reason", "build failed: " + err)); repaint(); return; }
            try {
                JSONObject inner = new JSONObject().put("k", "take").put("orderId", order.orderId).put("txn", hex).put("taker", me.publicId());
                String blob = me.seal(order.pubId, inner.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                String msg = Envelopes.msg(me, order.pubId, blob, MakerEngine.now() + 300);
                JSONObject pt = new JSONObject().put("order", new JSONObject(order.raw)).put("plan", planJson(plan)).put("sent", System.currentTimeMillis()).put("act", actId);
                db.savePending(order.orderId, pt);
                net.execute(() -> {
                    List<RelayClient.PublishResult> results = relay.publish(msg);
                    int ok = 0; for (RelayClient.PublishResult r : results) if (r.ok) ok++;
                    final int okCount = ok; final String firstErr = results.isEmpty() ? "no relays" : results.get(0).error;
                    ui.post(() -> {
                        busy = false;
                        if (okCount == 0) { db.removePending(order.orderId); ActivityLog.update(db, actId, a -> a.put("stage", ActivityLog.FAILED).put("reason", "no relay accepted the take: " + firstErr)); }
                        else ActivityLog.update(db, actId, a -> {   // a fast maker can answer (via another relay) before the slowest publish returns
                            a.put("relays", okCount).put("sentMs", pt.optLong("sent"));
                            if (ActivityLog.BUILDING.equals(a.optString("stage"))) a.put("stage", ActivityLog.SENT).put("reason", "");
                        });
                        repaint();
                    });
                });
            } catch (Exception e) { busy = false; ActivityLog.update(db, actId, a -> a.put("stage", ActivityLog.FAILED).put("reason", "take failed: " + e.getMessage())); repaint(); }
        });
    }

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
        themePill.setOnClickListener(v -> { Design.toggle(this); if (trade != null) trade.close(); setContentView(buildChrome()); selectTab(tab); });
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

        ScrollView scroller = new ScrollView(this);
        content = new FrameLayout(this);
        scroller.addView(content, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT));
        root.addView(scroller, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        trade = new TradeView(this); activityTab = new ActivityTab(this); ordersTab = new OrdersTab(this); historyTab = new HistoryTab(this); assetsTab = new AssetsTab(this); relaysTab = new RelaysTab(this);
        for (View v : tabViews()) { v.setVisibility(View.GONE); content.addView(v); }

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
            // Six tabs: a narrow phone shrinks the labels to fit (min 7sp); the Fold keeps 10sp.
            androidx.core.widget.TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(t, 7, 10, 1, android.util.TypedValue.COMPLEX_UNIT_SP);
            t.setPadding(0, Design.dp(this, 11), 0, Design.dp(this, 11)); t.setMinHeight(Design.dp(this, 48));
            t.setOnClickListener(v -> selectTab(idx));
            tabBar.addView(t, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        }
        root.addView(tabBar);
        return root;
    }

    private void selectTab(int idx) {
        tab = idx;
        View[] views = tabViews();
        for (int i = 0; i < views.length; i++) views[i].setVisibility(i == idx ? View.VISIBLE : View.GONE);
        for (int i = 0; i < tabBar.getChildCount(); i++) {
            TextView t = (TextView) tabBar.getChildAt(i);
            t.setTextColor(i == idx ? Design.ON_ACCENT() : Design.DIM());
            t.setBackground(i == idx ? Design.roundBg(this, Design.ACCENT(), 10) : null);
        }
        repaint();
    }

    private View[] tabViews() { return new View[]{trade, activityTab, ordersTab, historyTab, assetsTab, relaysTab}; }

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

    void repaint() {
        if (closed || trade == null) return;
        repaintHeader();
        switch (tab) {
            case TAB_TRADE: trade.render(); break;
            case TAB_ACTIVITY: activityTab.render(); break;
            case TAB_ORDERS: ordersTab.render(); break;
            case TAB_HISTORY: historyTab.render(); break;
            case TAB_ASSETS: assetsTab.render(); break;
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
