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
    private static final String PREFS = "dexicon_ui", KEY_GROUP = "ladder_group", KEY_FUNDS_BIG = "funds_big";
    private int groupIdx = 0;
    private static final int MAX_ROWS = 10;

    private boolean buyMode = true;
    private TextView buyTab, sellTab, totalTv, ctaBtn, logTv;
    // funds panel + live order preview (owner 2026-10-22: balances must be visible where you trade)
    private TextView fundsMinima, fundsMxusd, fundsValue, fundsTitle, fundsSmall, availTv, warnTv;
    private LinearLayout fundsCols;
    private boolean fundsBig;
    private java.util.List<OrderPreview.Lvl> lastAsks = new ArrayList<>(), lastBids = new ArrayList<>();
    private BigDecimal lastMid;
    private boolean canAfford = true;
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
        buildTicker(); buildFunds(); buildLadder(); buildOrderPanel(); buildOrders();
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

    // ---- funds ----
    /** YOUR FUNDS has two modes, tap the card to switch (remembered): SMALL = available of each coin on one line,
     *  BIG = available / in your orders / settling / total per coin. Owner, 2026-10-22. */
    private void buildFunds() {
        LinearLayout c = card();
        fundsBig = getContext().getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE).getBoolean(KEY_FUNDS_BIG, false);
        LinearLayout top = new LinearLayout(getContext());
        top.setGravity(Gravity.CENTER_VERTICAL);
        fundsTitle = tv("", 10f, Design.DIM(), Design.sansBold());
        top.addView(fundsTitle, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
        fundsValue = tv("", 10f, Design.DIM(), Design.mono());
        top.addView(fundsValue);
        c.addView(top);
        fundsSmall = tv("—", 11f, Design.TEXT(), Design.mono());
        LayoutParams sl = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT); sl.topMargin = dp(4);
        c.addView(fundsSmall, sl);
        fundsCols = new LinearLayout(getContext());
        fundsMinima = tv("MINIMA\n—", 11f, Design.TEXT(), Design.mono());
        fundsMxusd = tv("MxUSD\n—", 11f, Design.TEXT(), Design.mono());
        fundsCols.addView(fundsMinima, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
        fundsCols.addView(fundsMxusd, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
        LayoutParams cl = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT); cl.topMargin = dp(4);
        c.addView(fundsCols, cl);
        c.setOnClickListener(v -> {
            fundsBig = !fundsBig;
            getContext().getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE).edit().putBoolean(KEY_FUNDS_BIG, fundsBig).apply();
            renderFunds();
        });
        Design.pressable(c);
    }

    private void renderFunds() {
        if (fundsMinima == null) return;
        fundsTitle.setText(fundsBig ? "YOUR FUNDS  ▾" : "YOUR FUNDS  ▸");
        fundsSmall.setVisibility(fundsBig ? GONE : VISIBLE);
        fundsCols.setVisibility(fundsBig ? VISIBLE : GONE);
        if (!act.paired()) { fundsSmall.setText("not paired"); fundsMinima.setText("MINIMA\nnot paired"); fundsMxusd.setText("MxUSD\nnot paired"); fundsValue.setText(""); return; }
        Funds f = Funds.of(act);
        fundsSmall.setText(Util.fmtBal(f.minimaAvailable) + " MINIMA  ·  " + Util.fmtBal(f.mxusdAvailable) + " MxUSD  available");
        fundsMinima.setText(fundsBlock("MINIMA", f.minimaAvailable, f.minimaInOrders, f.minimaSettling, f.minimaTotal()));
        fundsMxusd.setText(fundsBlock("MxUSD", f.mxusdAvailable, f.mxusdInOrders, f.mxusdSettling, f.mxusdTotal()));
        BigDecimal v = f.valueAt(lastMid);
        fundsValue.setText(v == null ? "" : "≈ " + Util.fmt(v.setScale(4, RoundingMode.DOWN)) + " MxUSD @ mid " + Util.fmtPrice(lastMid));
    }

    private static String fundsBlock(String name, BigDecimal avail, BigDecimal orders, BigDecimal settling, BigDecimal total) {
        return name + "\n" + Util.fmtBal(avail) + "  available"
                + "\n" + Util.fmtBal(orders) + "  in your orders"
                + "\n+" + Util.fmtBal(settling) + "  settling"
                + "\n" + Util.fmtBal(total) + "  total";
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
     *  when another source shares the level; FAST and BOOK sizes exact (they can be taken in full), POOL bands to two
     *  decimals (sampled depth); one line per source. */
    public static String amountText(BigDecimal fast, BigDecimal book, BigDecimal pool) {
        List<String> parts = new ArrayList<>();
        boolean hasPool = pool != null && pool.signum() > 0;
        if (fast != null && fast.signum() > 0) parts.add("FAST " + fast.stripTrailingZeros().toPlainString());   // exact: a size you can take in full
        if (book != null && book.signum() > 0) parts.add((!parts.isEmpty() || hasPool ? "BOOK " : "") + book.stripTrailingZeros().toPlainString());
        if (hasPool) parts.add("POOL " + PriceMath.fmtDown(pool, 2));
        return parts.isEmpty() ? PriceMath.fmtDown(BigDecimal.ZERO, 2) : String.join("\n", parts);
    }

    /** PandaDEX TradeView.amountText (BOOK + POOL only), kept so its ported test runs unchanged. */
    public static String amountText(BigDecimal book, BigDecimal pool) { return amountText(BigDecimal.ZERO, book, pool); }

    /** One ladder row with a right-anchored translucent depth bar BEHIND the numbers; tap prefills the exact price. */
    private View ladderRow(BigDecimal price, Level lv, float depthFrac, boolean ask, BigDecimal cumulative) {
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
        // a row fills the price AND the exact amount on offer up to it, on the side that takes it; in a grouped view the
        // bucket's boundary price is used, which crosses every order inside the bucket
        BigDecimal at = GROUPS[groupIdx] == null ? prefill : price;
        f.setOnClickListener(v -> { setMode(ask); priceIn.setText(at.stripTrailingZeros().toPlainString()); amountIn.setText(cumulative.stripTrailingZeros().toPlainString()); updateTotal(); });
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
        availTv = tv("Available: —", 11f, Design.ACCENT(), Design.mono());
        availTv.setPadding(0, dp(6), 0, 0);
        availTv.setOnClickListener(v -> applyPercent(100));   // tap = use everything free on this side
        c.addView(availTv);
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
        warnTv = tv("", 11f, Design.RED(), Design.monoBold());
        warnTv.setPadding(0, dp(4), 0, 0);
        c.addView(warnTv);
        c.addView(tv("Crosses a FAST order → the button IS the trade: one click, no dialog, progress in the log below. BOOK/POOL on-chain liquidity still shows a confirm. Nothing crosses → rests on the relays as your FAST order.", 9.5f, Design.DIM2(), Design.sans()));
        ctaBtn = tv("BUY MINIMA", 14f, Design.ON_ACCENT(), Design.sansBold());
        ctaBtn.setGravity(Gravity.CENTER); ctaBtn.setPadding(0, dp(12), 0, dp(12));
        LayoutParams cl = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT); cl.topMargin = dp(8);
        c.addView(ctaBtn, cl);
        ctaBtn.setOnClickListener(v -> submit());
        Design.pressable(ctaBtn);
        logTv = tv("", 10.5f, Design.TEXT(), Design.mono());
        logTv.setPadding(dp(10), dp(8), dp(10), dp(8));
        logTv.setBackground(Design.stroked(getContext(), Design.SURFACE2(), 8));
        logTv.setTextIsSelectable(true);   // long-press to copy the full txpowid
        logTv.setVisibility(GONE);
        LayoutParams ll = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT); ll.topMargin = dp(8);
        c.addView(logTv, ll);
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

    /** Owner 2026-10-22, MinimaXT's slider: 100 % is 100 % of the EXACT order, never a rounded display. When the price
     *  crosses FAST orders the % is of the exact MINIMA they offer at that price (capped by your exact free funds);
     *  otherwise of your exact free funds. Cut DOWN to 8 dp — 100 % of an offer is the offer itself. */
    private void applyPercent(int pct) {
        BigDecimal price = Util.dec(priceIn.getText().toString());
        if (price.signum() <= 0) { act.toast("Enter a price first — or tap a row in the book"); return; }
        BigDecimal free = Funds.of(act).freeFor(buyMode);
        BigDecimal cap = (buyMode ? free.divide(price, 8, RoundingMode.DOWN) : free).setScale(8, RoundingMode.DOWN);
        BigDecimal offer = act.offered(buyMode, price), p = new BigDecimal(pct), amount;
        if (offer.signum() > 0) {
            BigDecimal want = offer.multiply(p).divide(new BigDecimal(100), 8, RoundingMode.DOWN);
            amount = want.min(cap);
            if (cap.compareTo(want) < 0) act.toast("Your free " + (buyMode ? "MxUSD" : "MINIMA") + " covers " + amount.stripTrailingZeros().toPlainString() + " of the " + offer.stripTrailingZeros().toPlainString() + " MINIMA on offer");
        } else amount = cap.multiply(p).divide(new BigDecimal(100), 8, RoundingMode.DOWN);
        amountIn.setText(amount.stripTrailingZeros().toPlainString());
        updateTotal();
    }

    private void updateTotal() {
        if (totalTv == null) return;
        boolean buy = buyMode;
        Funds f = act.paired() ? Funds.of(act) : null;
        if (availTv != null) availTv.setText(f == null ? "Available: — (not paired)"
                : "Available: " + Util.fmtBal(f.freeFor(buy)) + (buy ? " MxUSD" : " MINIMA") + "  ·  tap for max"
                  + "\n(" + Util.fmtBal(f.minimaAvailable) + " MINIMA · " + Util.fmtBal(f.mxusdAvailable) + " MxUSD in wallet)");
        BigDecimal p = Util.dec(priceIn.getText().toString()), a = Util.dec(amountIn.getText().toString());
        canAfford = true;
        if (p.signum() <= 0 || a.signum() <= 0 || f == null) { totalTv.setText("Total: —"); warnTv.setText(""); paintCta(); return; }
        OrderPreview v = OrderPreview.of(buy, p, a, buy ? lastAsks : lastBids, f, lastMid);
        StringBuilder sb = new StringBuilder();
        BigDecimal total = a.multiply(p).setScale(8, buy ? RoundingMode.UP : RoundingMode.DOWN);
        sb.append(buy ? "You pay up to " + Util.fmt(total) + " MxUSD\nYou receive " + Util.fmt(a) + " MINIMA"
                      : "You pay " + Util.fmt(a) + " MINIMA\nYou receive at least " + Util.fmt(total) + " MxUSD");
        if (v.fillNow.signum() > 0)
            sb.append("\nFills now ≈ ").append(Util.fmt(v.fillNow)).append(" MINIMA @ avg ").append(Util.fmtPrice(v.avgFill))
              .append(v.rest.signum() > 0 ? "\nRests " + Util.fmt(v.rest) + " MINIMA as your FAST order @ " + Util.fmtPrice(p) : "");
        else sb.append("\nNothing crosses: all of it rests as your FAST order @ ").append(Util.fmtPrice(p));
        if (v.vsBestPct != null) sb.append("\n").append(signed(v.vsBestPct)).append("% vs best ").append(buy ? "ask " : "bid ").append(Util.fmtPrice(v.best));
        if (v.vsMidPct != null) sb.append("  ·  ").append(signed(v.vsMidPct)).append("% vs mid");
        sb.append("\nAfter the immediate fill: ").append(Util.fmtBal(v.afterMinima)).append(" MINIMA · ").append(Util.fmtBal(v.afterMxusd)).append(" MxUSD");
        totalTv.setText(sb.toString());
        canAfford = v.shortfall.signum() == 0;
        warnTv.setText(canAfford ? "" : "Exceeds your free " + (buy ? "MxUSD" : "MINIMA") + " by " + Util.fmt(v.shortfall)
                + " (free " + Util.fmtBal(v.free) + ")");
        paintCta();
    }

    private static String signed(BigDecimal pct) { return (pct.signum() > 0 ? "+" : "") + pct.toPlainString(); }

    private void paintCta() {
        if (ctaBtn == null) return;
        if (act.taking()) { ctaBtn.setText("TRADING… " + act.takingSecs() + "s"); ctaBtn.setEnabled(false); ctaBtn.setAlpha(0.75f); return; }
        BigDecimal p = Util.dec(priceIn.getText().toString()), a = Util.dec(amountIn.getText().toString());
        com.eurobuddha.dexicon.envelope.Envelope best = p.signum() > 0 && a.signum() > 0 ? act.fastCross(buyMode, p) : null;
        ctaBtn.setText(best != null ? (buyMode ? "BUY " : "SELL ") + Util.fmt(a.min(new BigDecimal(best.amount))) + " MINIMA NOW" : buyMode ? "BUY MINIMA" : "SELL MINIMA");
        boolean ok = canAfford && !act.isBusy();
        ctaBtn.setEnabled(ok); ctaBtn.setAlpha(ok ? 1f : 0.45f);
    }
    void paintCtaPublic() { paintCta(); }
    void paintLog() { if (logTv == null) return; String t = act.takeLog(); logTv.setText(t.trim()); logTv.setVisibility(t.isEmpty() ? GONE : VISIBLE); }
    void clearAmount() { if (amountIn != null) amountIn.setText(""); }

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
        paintCta();
        renderLadder(book);
        renderFunds();
        renderOrders();
        paintLog();
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
            askRows.add(ladderRow(e.getKey(), e.getValue(), askMax.signum() == 0 ? 0 : running.divide(askMax, 4, RoundingMode.HALF_UP).floatValue(), true, running));
        }
        for (int i = askRows.size() - 1; i >= 0; i--) asksBox.addView(askRows.get(i));   // best ask just above centre
        List<Map.Entry<BigDecimal, Level>> bidList = new ArrayList<>(bids.entrySet()).subList(0, Math.min(MAX_ROWS, bids.size()));
        BigDecimal bidMax = BigDecimal.ZERO; for (Map.Entry<BigDecimal, Level> e : bidList) bidMax = bidMax.add(e.getValue().total());
        running = BigDecimal.ZERO;
        for (Map.Entry<BigDecimal, Level> e : bidList) {
            running = running.add(e.getValue().total());
            bidsBox.addView(ladderRow(e.getKey(), e.getValue(), bidMax.signum() == 0 ? 0 : running.divide(bidMax, 4, RoundingMode.HALF_UP).floatValue(), false, running));
        }
        // Ticker = best of all three sources: the exact best price behind the best displayed level on each side.
        BigDecimal bestAsk = askList.isEmpty() ? null : askList.get(0).getValue().exact, bestBid = bidList.isEmpty() ? null : bidList.get(0).getValue().exact;
        BigDecimal mid = bestBid != null && bestAsk != null ? bestBid.add(bestAsk).divide(new BigDecimal(2), 8, RoundingMode.HALF_UP) : bestBid != null ? bestBid : bestAsk;
        lastAsks = new ArrayList<>(); for (Map.Entry<BigDecimal, Level> e : asks.entrySet()) lastAsks.add(new OrderPreview.Lvl(e.getValue().exact != null ? e.getValue().exact : e.getKey(), e.getValue().total()));
        lastBids = new ArrayList<>(); for (Map.Entry<BigDecimal, Level> e : bids.entrySet()) lastBids.add(new OrderPreview.Lvl(e.getValue().exact != null ? e.getValue().exact : e.getKey(), e.getValue().total()));
        lastMid = mid;
        updateTotal();
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
