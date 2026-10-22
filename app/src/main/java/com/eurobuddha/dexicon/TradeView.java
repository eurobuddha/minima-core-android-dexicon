package com.eurobuddha.dexicon;

import android.annotation.SuppressLint;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.eurobuddha.dexicon.envelope.Envelope;
import com.eurobuddha.dexicon.maker.MyOrder;
import com.eurobuddha.dexicon.resting.Order5;
import com.eurobuddha.dexicon.resting.Pool;
import com.eurobuddha.dexicon.resting.PriceMath;
import com.eurobuddha.dexicon.resting.SyntheticDepth;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The spot screen (PandaDEX TradeView): ticker → grouped depth ladder → order panel → my open orders.
 * Inputs are built ONCE and never rebuilt; read-only sections re-render per book update.
 *
 * The ladder merges three sources per price level, tagged like the Dexicon MiniDapp (ui/pd_app.js renderBookRows):
 * FAST (signed relay orders), BOOK (V5 covenant orders) and POOL (PandaPools depth, sampled by SyntheticDepth on a
 * background thread exactly as PandaDEX TradeView does, painting the last completed snapshot).
 */
@SuppressLint("ViewConstructor")
public final class TradeView extends LinearLayout {

    private final MainActivity act;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private TextView midTv, spreadTv, bidsTv, asksTv, syncDot, kindTv;
    private LinearLayout asksBox, bidsBox, groupRow, ordersBox;
    private TextView centerPriceTv;
    private static final BigDecimal[] GROUPS = {null, new BigDecimal("0.0001"), new BigDecimal("0.001"), new BigDecimal("0.01")};   // null = EXACT: no bucketing (owner 2026-10-22)
    /** EXACT mode samples the pool curve every 0.00001 — each sample is a real executable price, not a rounding. */
    private static final BigDecimal SAMPLE_TICK = new BigDecimal("0.00001");
    private static final String[] GROUP_LABELS = {"EXACT", "0.0001", "0.001", "0.01"};
    private static final String PREFS = "dexicon_ui", KEY_GROUP = "ladder_group";
    private int groupIdx = 0;
    private static final int MAX_ROWS = 10;

    private boolean buyMode = true;
    private TextView buyTab, sellTab, totalTv, ctaBtn;
    private EditText priceIn, amountIn;
    private final Runnable totalTask = this::updateTotal;

    // PandaDEX TradeView depth worker: pool depth sampling walks the curve and re-plans through the router, so it
    // never runs on the UI thread; the ladder paints the last completed snapshot for the current key.
    private final ExecutorService depthExec = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "dexicon-depth"); t.setDaemon(true); return t;
    });
    private boolean depthRunning, closed;
    private String requestedDepthKey = "", readyDepthKey = "";
    private List<SyntheticDepth.Row> readyPoolAsks = new ArrayList<>(), readyPoolBids = new ArrayList<>();

    public TradeView(MainActivity act) {
        super(act);
        this.act = act;
        setOrientation(VERTICAL);
        int pad = dp(12);
        setPadding(pad, pad, pad, pad);
        buildTicker(); buildLadder(); buildOrderPanel(); buildOrders();
    }

    private int dp(int v) { return Design.dp(getContext(), v); }

    private TextView tv(String s, float size, int color, Typeface tf) {
        TextView t = new TextView(getContext());
        t.setText(s); t.setTextSize(size); t.setTextColor(color); t.setTypeface(tf);
        return t;
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(getContext());
        c.setOrientation(VERTICAL);
        c.setBackground(Design.card(getContext(), 12));
        int p = dp(12); c.setPadding(p, p, p, p);
        LayoutParams lp = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(10);
        addView(c, lp);
        return c;
    }

    // ---- ticker ----
    private void buildTicker() {
        LinearLayout c = card();
        LinearLayout top = new LinearLayout(getContext());
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.addView(tv("MINIMA / MxUSD", 13f, Design.TEXT(), Design.sansBold()), new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
        syncDot = tv("● no relays", 10f, Design.DIM(), Design.sans());
        top.addView(syncDot);
        c.addView(top);
        LinearLayout mid = new LinearLayout(getContext());
        mid.setGravity(Gravity.CENTER_VERTICAL);
        midTv = tv("—", 26f, Design.TEXT(), Design.monoBold());
        mid.addView(midTv);
        spreadTv = tv("", 12f, Design.DIM(), Design.mono());
        LayoutParams dl = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT); dl.leftMargin = dp(10);
        mid.addView(spreadTv, dl);
        c.addView(mid);
        kindTv = tv("book mid · fast layer", 9.5f, Design.DIM(), Design.sans());
        c.addView(kindTv);
        LinearLayout stats = new LinearLayout(getContext());
        bidsTv = stat(stats, "Bids"); asksTv = stat(stats, "Asks");
        c.addView(stats);
    }

    private TextView stat(LinearLayout row, String label) {
        LinearLayout col = new LinearLayout(getContext());
        col.setOrientation(VERTICAL);
        col.addView(tv(label, 9.5f, Design.DIM2(), Design.sans()));
        TextView v = tv("—", 12f, Design.DIM(), Design.mono());
        col.addView(v);
        row.addView(col, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
        return v;
    }

    // ---- ladder ----
    private void buildLadder() {
        LinearLayout c = card();
        LinearLayout head = new LinearLayout(getContext());
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(tv("ORDER BOOK", 10f, Design.DIM(), Design.sansBold()), new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
        groupIdx = getContext().getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE).getInt(KEY_GROUP, 0);
        if (groupIdx < 0 || groupIdx >= GROUPS.length) groupIdx = 0;
        groupRow = new LinearLayout(getContext());
        for (int i = 0; i < GROUPS.length; i++) {
            final int idx = i;
            TextView g = tv(GROUP_LABELS[i], 10f, Design.DIM(), Design.mono());
            g.setPadding(dp(9), dp(6), dp(9), dp(6));
            g.setOnClickListener(v -> {
                groupIdx = idx;
                getContext().getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE).edit().putInt(KEY_GROUP, idx).apply();
                restyleGroups(); act.repaint();
            });
            Design.pressable(g);
            LayoutParams glp = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT); glp.leftMargin = dp(4);
            groupRow.addView(g, glp);
        }
        head.addView(groupRow);
        restyleGroups();
        c.addView(head);
        LinearLayout legend = new LinearLayout(getContext());
        legend.addView(tv("Price (MxUSD)", 9f, Design.DIM2(), Design.sans()), new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1.2f));
        TextView a = tv("Amount (MINIMA)", 9f, Design.DIM2(), Design.sans()); a.setGravity(Gravity.END);
        legend.addView(a, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
        TextView t2 = tv("Total", 9f, Design.DIM2(), Design.sans()); t2.setGravity(Gravity.END);
        legend.addView(t2, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
        c.addView(legend);
        asksBox = new LinearLayout(getContext()); asksBox.setOrientation(VERTICAL); c.addView(asksBox);
        centerPriceTv = tv("—", 16f, Design.TEXT(), Design.monoBold());
        centerPriceTv.setGravity(Gravity.CENTER); centerPriceTv.setPadding(0, dp(6), 0, dp(6));
        c.addView(centerPriceTv);
        bidsBox = new LinearLayout(getContext()); bidsBox.setOrientation(VERTICAL); c.addView(bidsBox);
    }

    private void restyleGroups() {
        for (int i = 0; i < groupRow.getChildCount(); i++) {
            TextView g = (TextView) groupRow.getChildAt(i);
            boolean on = i == groupIdx;
            g.setTextColor(on ? Design.ON_ACCENT() : Design.DIM());
            g.setBackground(on ? Design.roundBg(getContext(), Design.ACCENT(), 8) : Design.roundBg(getContext(), Design.SURFACE2(), 8));
        }
    }

    /** One ladder level: FAST / BOOK / POOL MINIMA at this grouped price. */
    private static final class Level {
        BigDecimal fast = BigDecimal.ZERO, book = BigDecimal.ZERO, pool = BigDecimal.ZERO, exact;
        boolean mine, unbacked;
        BigDecimal total() { return fast.add(book).add(pool); }
    }

    /** Amount column text, MiniDapp parity (pd_app.js renderBookRows): FAST and POOL are always tagged, BOOK only
     *  when another source shares the level; amounts truncated to two decimals; one line per source. */
    public static String amountText(BigDecimal fast, BigDecimal book, BigDecimal pool) {
        List<String> parts = new ArrayList<>();
        boolean hasPool = pool != null && pool.signum() > 0;
        if (fast != null && fast.signum() > 0) parts.add("FAST " + PriceMath.fmtDown(fast, 2));
        if (book != null && book.signum() > 0) parts.add((!parts.isEmpty() || hasPool ? "BOOK " : "") + PriceMath.fmtDown(book, 2));
        if (hasPool) parts.add("POOL " + PriceMath.fmtDown(pool, 2));
        return parts.isEmpty() ? PriceMath.fmtDown(BigDecimal.ZERO, 2) : String.join("\n", parts);
    }

    /** PandaDEX TradeView.amountText (BOOK + POOL only), kept so its ported test runs unchanged. */
    public static String amountText(BigDecimal book, BigDecimal pool) { return amountText(BigDecimal.ZERO, book, pool); }

    /** One ladder row with a right-anchored translucent depth bar BEHIND the numbers; tap prefills the exact price. */
    private View ladderRow(BigDecimal price, Level lv, float depthFrac, boolean ask) {
        FrameLayout f = new FrameLayout(getContext());
        View bar = new View(getContext());
        bar.setBackgroundColor(ask ? (Design.RED() & 0x00FFFFFF) | 0x22000000 : (Design.IN() & 0x00FFFFFF) | 0x22000000);
        FrameLayout.LayoutParams bl = new FrameLayout.LayoutParams(0, FrameLayout.LayoutParams.MATCH_PARENT);
        bl.gravity = Gravity.END;
        f.addView(bar, bl);
        f.post(() -> { FrameLayout.LayoutParams p = (FrameLayout.LayoutParams) bar.getLayoutParams(); p.width = (int) (f.getWidth() * Math.min(1f, depthFrac)); bar.setLayoutParams(p); });
        LinearLayout row = new LinearLayout(getContext());
        row.setGravity(Gravity.CENTER_VERTICAL); row.setPadding(0, dp(3), 0, dp(3));
        TextView p = tv((lv.mine ? "• " : "") + Util.fmtPrice(lv.exact != null ? lv.exact : price), 11f, ask ? Design.RED() : Design.IN(), Design.mono());   // the real best price in the row, never a rounded bucket
        row.addView(p, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1.2f));
        String amt = amountText(lv.fast, lv.book, lv.pool);
        TextView am = tv(amt, amt.contains("\n") || amt.contains(" ") ? 9.5f : 11f, Design.TEXT(), Design.mono()); am.setGravity(Gravity.END);
        row.addView(am, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
        boolean unbacked = lv.unbacked && lv.book.signum() == 0 && lv.pool.signum() == 0;
        TextView to = tv(unbacked ? "UNBACKED" : Util.fmt(PriceMath.up(price.multiply(lv.total()), 4)), 11f, unbacked ? Design.ACCENT() : Design.DIM(), Design.mono()); to.setGravity(Gravity.END);
        row.addView(to, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
        f.addView(row);
        BigDecimal prefill = lv.exact != null ? lv.exact : price;
        f.setOnClickListener(v -> { priceIn.setText(prefill.stripTrailingZeros().toPlainString()); setMode(ask); });
        Design.pressable(f);
        return f;
    }

    // ---- order panel ----
    private void buildOrderPanel() {
        LinearLayout c = card();
        LinearLayout tabs = new LinearLayout(getContext());
        buyTab = tv("BUY", 13f, Design.ON_ACCENT(), Design.sansBold());
        sellTab = tv("SELL", 13f, Design.DIM(), Design.sansBold());
        for (TextView t : new TextView[]{buyTab, sellTab}) { t.setGravity(Gravity.CENTER); t.setPadding(0, dp(9), 0, dp(9)); tabs.addView(t, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)); }
        buyTab.setOnClickListener(v -> setMode(true));
        sellTab.setOnClickListener(v -> setMode(false));
        c.addView(tabs);
        priceIn = input("Price (MxUSD per MINIMA)");
        amountIn = input("Amount (MINIMA)");
        c.addView(priceIn); c.addView(amountIn);
        LinearLayout pct = new LinearLayout(getContext());
        for (int p : new int[]{25, 50, 75, 100}) {
            TextView b = tv(p + "%", 10f, Design.DIM(), Design.mono());
            b.setGravity(Gravity.CENTER); b.setPadding(0, dp(5), 0, dp(5));
            b.setBackground(Design.stroked(getContext(), Design.SURFACE2(), 8));
            final int fp = p;
            b.setOnClickListener(v -> applyPercent(fp));
            Design.pressable(b);
            LayoutParams lp = new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f); lp.setMargins(dp(2), dp(6), dp(2), dp(2));
            pct.addView(b, lp);
        }
        c.addView(pct);
        totalTv = tv("Total: —", 11f, Design.DIM(), Design.mono());
        totalTv.setPadding(0, dp(6), 0, 0);
        c.addView(totalTv);
        c.addView(tv("Crosses the book → takes the best source for the first level: a FAST relay order (one maker per take, co-signed) or BOOK/POOL on-chain liquidity (one atomic transaction). The confirm dialog says which. Otherwise rests on the relays as your FAST order.", 9.5f, Design.DIM2(), Design.sans()));
        ctaBtn = tv("BUY MINIMA", 14f, Design.ON_ACCENT(), Design.sansBold());
        ctaBtn.setGravity(Gravity.CENTER); ctaBtn.setPadding(0, dp(12), 0, dp(12));
        LayoutParams cl = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT); cl.topMargin = dp(8);
        c.addView(ctaBtn, cl);
        ctaBtn.setOnClickListener(v -> submit());
        Design.pressable(ctaBtn);
        setMode(true);
        TextWatcher w = new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a1, int a2, int a3) {}
            @Override public void onTextChanged(CharSequence s, int a1, int a2, int a3) {}
            @Override public void afterTextChanged(Editable s) { ui.removeCallbacks(totalTask); ui.postDelayed(totalTask, 300); }
        };
        priceIn.addTextChangedListener(w); amountIn.addTextChangedListener(w);
    }

    private EditText input(String hint) {
        EditText e = new EditText(getContext());
        e.setHint(hint); e.setHintTextColor(Design.DIM2()); e.setTextColor(Design.TEXT()); e.setTypeface(Design.mono()); e.setTextSize(13f);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);   // Samsung-safe immediate-commit decimal field
        e.setImeOptions(android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        e.setTransformationMethod(null);
        e.setFilters(new android.text.InputFilter[]{(src, start, end, dest, dstart, dend) -> {
            String value = dest.toString().substring(0, dstart) + src.subSequence(start, end) + dest.toString().substring(dend);
            return value.length() <= 44 && (value.isEmpty() || value.matches("[0-9]*\\.?[0-9]*")) ? null : "";
        }});
        e.setBackground(Design.stroked(getContext(), Design.SURFACE2(), 8));
        e.setPadding(dp(10), dp(9), dp(10), dp(9));
        LayoutParams lp = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT); lp.topMargin = dp(6);
        e.setLayoutParams(lp);
        return e;
    }

    private void setMode(boolean buy) {
        buyMode = buy;
        buyTab.setBackground(Design.roundBg(getContext(), buy ? Design.IN() : Design.SURFACE2(), 10));
        buyTab.setTextColor(buy ? Color.WHITE : Design.DIM());
        sellTab.setBackground(Design.roundBg(getContext(), !buy ? Design.RED() : Design.SURFACE2(), 10));
        sellTab.setTextColor(!buy ? Color.WHITE : Design.DIM());
        ctaBtn.setText(buy ? "BUY MINIMA" : "SELL MINIMA");
        ctaBtn.setBackground(Design.ripple(Design.roundBg(getContext(), buy ? Design.IN() : Design.RED(), 12)));
        updateTotal();
    }

    private void applyPercent(int pct) {
        BigDecimal price = Util.dec(priceIn.getText().toString());
        if (buyMode) {
            if (price.signum() <= 0) return;
            BigDecimal usd = act.mxusdSendable().multiply(new BigDecimal(pct)).divide(new BigDecimal(100), 8, RoundingMode.DOWN);
            amountIn.setText(usd.divide(price, 8, RoundingMode.DOWN).stripTrailingZeros().toPlainString());
        } else {
            amountIn.setText(act.minimaSendable().multiply(new BigDecimal(pct)).divide(new BigDecimal(100), 8, RoundingMode.DOWN).stripTrailingZeros().toPlainString());
        }
        updateTotal();
    }

    private void updateTotal() {
        BigDecimal p = Util.dec(priceIn.getText().toString()), a = Util.dec(amountIn.getText().toString());
        totalTv.setText(p.signum() > 0 && a.signum() > 0 ? "Total: " + Util.fmt(a.multiply(p).setScale(8, RoundingMode.UP)) + " MxUSD" : "Total: —");
    }

    private void submit() {
        BigDecimal price = Util.dec(priceIn.getText().toString()), amount = Util.dec(amountIn.getText().toString());
        if (price.signum() <= 0 || amount.signum() <= 0) { act.toast("Enter a price and amount"); return; }
        if (price.scale() > 8 || amount.scale() > 8) { act.toast("At most 8 decimals"); return; }
        act.placeOrder(buyMode, price, amount);
    }

    // ---- my open orders ----
    private void buildOrders() {
        LinearLayout c = card();
        c.addView(tv("MY OPEN ORDERS", 10f, Design.DIM(), Design.sansBold()));
        ordersBox = new LinearLayout(getContext()); ordersBox.setOrientation(VERTICAL);
        c.addView(ordersBox);
    }

    // ---- render ----
    public void render() {
        List<Envelope> book = act.book();
        int relays = act.relay().relays().size();
        boolean anyOk = false;
        for (com.eurobuddha.dexicon.relay.RelayClient.Status s : act.relay().status().values()) if (s.ok) anyOk = true;
        syncDot.setText(relays == 0 ? "● no relays" : anyOk ? "● live" : "● connecting");
        syncDot.setTextColor(anyOk ? Design.IN() : Design.DIM());
        ctaBtn.setAlpha(act.isBusy() ? 0.5f : 1f); ctaBtn.setEnabled(!act.isBusy());
        renderLadder(book);
        renderOrders();
    }

    private void renderLadder(List<Envelope> book) {
        BigDecimal tick = GROUPS[groupIdx];
        TreeMap<BigDecimal, Level> asks = new TreeMap<>(), bids = new TreeMap<>((a, b) -> b.compareTo(a));
        int fastN = 0, bookN = 0;
        for (Envelope o : book) {
            boolean sell = "sell".equals(o.side);
            BigDecimal p = new BigDecimal(o.price), amt = new BigDecimal(o.amount);
            Level lv = level(sell ? asks : bids, p, tick, sell);
            lv.fast = lv.fast.add(amt);
            if (act.isMine(o)) lv.mine = true;
            if (o.coins.isEmpty()) lv.unbacked = true;
            fastN++;
        }
        long block = act.chainBlock();
        for (Order5 o : act.restingBook().values()) {
            if (o.expired(block)) continue;
            Level lv = level(o.sell ? asks : bids, o.price(), tick, o.sell);
            lv.book = lv.book.add(o.minimaAmount());
            bookN++;
        }
        List<Pool> pools = act.pools();
        String key = depthKey(pools, tick);
        requestDepth(pools, tick, key);
        boolean poolsReady = pools.isEmpty() || key.equals(readyDepthKey);
        if (key.equals(readyDepthKey)) {
            for (SyntheticDepth.Row r : readyPoolAsks) { Level lv = asks.computeIfAbsent(r.price, k -> new Level()); lv.pool = lv.pool.add(r.poolMinima); if (lv.exact == null) lv.exact = r.price; }
            for (SyntheticDepth.Row r : readyPoolBids) { Level lv = bids.computeIfAbsent(r.price, k -> new Level()); lv.pool = lv.pool.add(r.poolMinima); if (lv.exact == null) lv.exact = r.price; }
        }
        asksBox.removeAllViews(); bidsBox.removeAllViews();
        List<Map.Entry<BigDecimal, Level>> askList = new ArrayList<>(asks.entrySet()).subList(0, Math.min(MAX_ROWS, asks.size()));
        BigDecimal askMax = BigDecimal.ZERO; for (Map.Entry<BigDecimal, Level> e : askList) askMax = askMax.add(e.getValue().total());
        BigDecimal running = BigDecimal.ZERO;
        List<View> askRows = new ArrayList<>();
        for (Map.Entry<BigDecimal, Level> e : askList) {
            running = running.add(e.getValue().total());
            askRows.add(ladderRow(e.getKey(), e.getValue(), askMax.signum() == 0 ? 0 : running.divide(askMax, 4, RoundingMode.HALF_UP).floatValue(), true));
        }
        for (int i = askRows.size() - 1; i >= 0; i--) asksBox.addView(askRows.get(i));   // best ask just above centre
        List<Map.Entry<BigDecimal, Level>> bidList = new ArrayList<>(bids.entrySet()).subList(0, Math.min(MAX_ROWS, bids.size()));
        BigDecimal bidMax = BigDecimal.ZERO; for (Map.Entry<BigDecimal, Level> e : bidList) bidMax = bidMax.add(e.getValue().total());
        running = BigDecimal.ZERO;
        for (Map.Entry<BigDecimal, Level> e : bidList) {
            running = running.add(e.getValue().total());
            bidsBox.addView(ladderRow(e.getKey(), e.getValue(), bidMax.signum() == 0 ? 0 : running.divide(bidMax, 4, RoundingMode.HALF_UP).floatValue(), false));
        }
        // Ticker = best of all three sources: the exact best price behind the best displayed level on each side.
        BigDecimal bestAsk = askList.isEmpty() ? null : askList.get(0).getValue().exact, bestBid = bidList.isEmpty() ? null : bidList.get(0).getValue().exact;
        BigDecimal mid = bestBid != null && bestAsk != null ? bestBid.add(bestAsk).divide(new BigDecimal(2), 8, RoundingMode.HALF_UP) : bestBid != null ? bestBid : bestAsk;
        centerPriceTv.setText(mid == null ? "—" : Util.fmtPrice(mid));
        midTv.setText(mid == null ? "—" : Util.fmtPrice(mid));
        spreadTv.setText(bestBid != null && bestAsk != null ? "spread " + Util.fmt(bestAsk.subtract(bestBid)) : "");
        String layers = !act.restingOn() ? " · FAST only (BOOK/POOL need mainnet MxUSD)" : " · FAST + BOOK + POOL" + (poolsReady ? "" : " (pool depth loading)");
        kindTv.setText((mid == null ? "no live orders" : bestBid != null && bestAsk != null ? "mid of best bid / best ask" : bestBid != null ? "best bid only" : "best ask only") + layers);
        bidsTv.setText(fastN + " fast · " + bookN + " book order" + (bookN == 1 ? "" : "s"));
        asksTv.setText(pools.size() + " pool" + (pools.size() == 1 ? "" : "s"));
        if (asks.isEmpty() && bids.isEmpty()) asksBox.addView(tv(act.relay().relays().isEmpty() && !act.restingOn() ? "Add a relay in the RELAYS tab." : "No live orders yet.", 11f, Design.DIM2(), Design.sans()));
    }

    /** Group a price to the tick (asks round up, bids down — PandaDEX levelPrice) and remember the best exact price. */
    private static Level level(TreeMap<BigDecimal, Level> side, BigDecimal p, BigDecimal tick, boolean sell) {
        BigDecimal g = tick == null ? p.stripTrailingZeros() : sell ? p.divide(tick, 0, RoundingMode.CEILING).multiply(tick) : p.divide(tick, 0, RoundingMode.FLOOR).multiply(tick);
        Level lv = side.computeIfAbsent(g, k -> new Level());
        if (lv.exact == null || (sell ? p.compareTo(lv.exact) < 0 : p.compareTo(lv.exact) > 0)) lv.exact = p;
        return lv;
    }

    private void requestDepth(List<Pool> pools, BigDecimal tick, String key) {
        if (closed || depthRunning || key.equals(requestedDepthKey) || key.equals(readyDepthKey)) return;
        depthRunning = true;
        requestedDepthKey = key;
        List<Pool> snapshot = new ArrayList<>(pools);
        depthExec.execute(() -> {
            List<SyntheticDepth.Row> asks = new ArrayList<>(), bids = new ArrayList<>();
            try {
                asks = SyntheticDepth.sample(snapshot, true, tick == null ? SAMPLE_TICK : tick, 10);
                bids = SyntheticDepth.sample(snapshot, false, tick == null ? SAMPLE_TICK : tick, 10);
            } catch (RuntimeException invalid) { /* Invalid depth is never rendered as executable liquidity. */ }
            final List<SyntheticDepth.Row> resultAsks = asks, resultBids = bids;
            ui.post(() -> {
                depthRunning = false;
                if (closed) return;
                readyDepthKey = key;
                readyPoolAsks = resultAsks;
                readyPoolBids = resultBids;
                act.repaint();   // renderLadder schedules at most the latest changed snapshot, never a backlog
            });
        });
    }

    private static String depthKey(List<Pool> pools, BigDecimal tick) {
        StringBuilder sb = new StringBuilder(tick == null ? "exact" : tick.toPlainString());
        if (pools != null) for (Pool p : pools) {
            if (p == null) continue;
            sb.append('|').append(p.address)
              .append('|').append(p.coinidM).append(':').append(p.reserveM)
              .append('|').append(p.coinidT).append(':').append(p.reserveT)
              .append('|').append(p.tok);
        }
        return sb.toString();
    }

    void close() { closed = true; depthExec.shutdownNow(); }

    private void renderOrders() {
        ordersBox.removeAllViews();
        boolean any = false;
        for (MyOrder o : act.myOrders()) {
            if (!o.live()) continue;
            any = true;
            LinearLayout row = new LinearLayout(getContext());
            row.setGravity(Gravity.CENTER_VERTICAL); row.setPadding(0, dp(6), 0, dp(6));
            boolean sell = "sell".equals(o.side);
            row.addView(tv((sell ? "SELL " : "BUY ") + Util.fmt(Util.dec(o.remaining)) + " @ " + Util.fmtPrice(Util.dec(o.price)) + (o.coins.isEmpty() ? "  UNBACKED" : ""),
                    11f, sell ? Design.RED() : Design.IN(), Design.mono()), new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
            TextView edit = tv(" ✎ ", 13f, Design.DIM(), Design.sans()); edit.setOnClickListener(v -> act.editOrder(o)); row.addView(edit);
            TextView cancel = tv(" ✕ ", 13f, Design.RED(), Design.sans()); cancel.setOnClickListener(v -> act.cancelOrder(o)); row.addView(cancel);
            ordersBox.addView(row);
        }
        if (!any) ordersBox.addView(tv("No open orders", 11f, Design.DIM2(), Design.sans()));
    }
}
