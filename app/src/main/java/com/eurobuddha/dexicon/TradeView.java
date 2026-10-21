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

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The spot screen (PandaDEX TradeView): ticker → grouped depth ladder → order panel → my open orders.
 * Inputs are built ONCE and never rebuilt; read-only sections re-render per book update.
 */
@SuppressLint("ViewConstructor")
public final class TradeView extends LinearLayout {

    private final MainActivity act;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private TextView midTv, spreadTv, bidsTv, asksTv, syncDot, kindTv;
    private LinearLayout asksBox, bidsBox, groupRow, ordersBox;
    private TextView centerPriceTv;
    private static final BigDecimal[] GROUPS = {new BigDecimal("0.00001"), new BigDecimal("0.0001"), new BigDecimal("0.001"), new BigDecimal("0.01")};
    private static final String[] GROUP_LABELS = {"0.00001", "0.0001", "0.001", "0.01"};
    private static final String PREFS = "dexicon_ui", KEY_GROUP = "ladder_group";
    private int groupIdx = 0;
    private static final int MAX_ROWS = 10;

    private boolean buyMode = true;
    private TextView buyTab, sellTab, totalTv, ctaBtn;
    private EditText priceIn, amountIn;
    private final Runnable totalTask = this::updateTotal;

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

    /** One ladder row with a right-anchored translucent depth bar BEHIND the numbers; tap prefills the exact price. */
    private View ladderRow(BigDecimal price, BigDecimal amount, BigDecimal total, float depthFrac, boolean ask, boolean mine, boolean unbacked, BigDecimal exactPrice) {
        FrameLayout f = new FrameLayout(getContext());
        View bar = new View(getContext());
        bar.setBackgroundColor(ask ? (Design.RED() & 0x00FFFFFF) | 0x22000000 : (Design.IN() & 0x00FFFFFF) | 0x22000000);
        FrameLayout.LayoutParams bl = new FrameLayout.LayoutParams(0, FrameLayout.LayoutParams.MATCH_PARENT);
        bl.gravity = Gravity.END;
        f.addView(bar, bl);
        f.post(() -> { FrameLayout.LayoutParams p = (FrameLayout.LayoutParams) bar.getLayoutParams(); p.width = (int) (f.getWidth() * Math.min(1f, depthFrac)); bar.setLayoutParams(p); });
        LinearLayout row = new LinearLayout(getContext());
        row.setGravity(Gravity.CENTER_VERTICAL); row.setPadding(0, dp(3), 0, dp(3));
        TextView p = tv((mine ? "• " : "") + Util.fmtPrice(price), 11f, ask ? Design.RED() : Design.IN(), Design.mono());
        row.addView(p, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1.2f));
        TextView am = tv(Util.fmtDown(amount, 2), 11f, Design.TEXT(), Design.mono()); am.setGravity(Gravity.END);
        row.addView(am, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
        TextView to = tv(unbacked ? "UNBACKED" : Util.fmt(total.setScale(4, RoundingMode.HALF_UP)), 11f, unbacked ? Design.ACCENT() : Design.DIM(), Design.mono()); to.setGravity(Gravity.END);
        row.addView(to, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
        f.addView(row);
        BigDecimal prefill = exactPrice != null ? exactPrice : price;
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
        c.addView(tv("Crosses the book → takes the best order (one maker per take, co-signed, final after one block). Otherwise rests on the relays as your order.", 9.5f, Design.DIM2(), Design.sans()));
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
        TreeMap<BigDecimal, BigDecimal> asks = new TreeMap<>(), bids = new TreeMap<>((a, b) -> b.compareTo(a));
        TreeMap<BigDecimal, Boolean> mineAt = new TreeMap<>(), unbackedAt = new TreeMap<>();
        TreeMap<BigDecimal, BigDecimal> exactAt = new TreeMap<>();
        int nb = 0, na = 0;
        BigDecimal bestBid = null, bestAsk = null;
        for (Envelope o : book) {
            boolean sell = "sell".equals(o.side);
            BigDecimal p = new BigDecimal(o.price), amt = new BigDecimal(o.amount);
            BigDecimal g = sell ? p.divide(tick, 0, RoundingMode.CEILING).multiply(tick) : p.divide(tick, 0, RoundingMode.FLOOR).multiply(tick);
            (sell ? asks : bids).merge(g, amt, BigDecimal::add);
            if (act.isMine(o)) mineAt.put(g, true);
            if (o.coins.isEmpty()) unbackedAt.put(g, true);
            exactAt.merge(g, p, (x, y) -> sell ? x.min(y) : x.max(y));
            if (sell) { na++; if (bestAsk == null || p.compareTo(bestAsk) < 0) bestAsk = p; } else { nb++; if (bestBid == null || p.compareTo(bestBid) > 0) bestBid = p; }
        }
        asksBox.removeAllViews(); bidsBox.removeAllViews();
        List<Map.Entry<BigDecimal, BigDecimal>> askList = new ArrayList<>(asks.entrySet()).subList(0, Math.min(MAX_ROWS, asks.size()));
        BigDecimal askMax = BigDecimal.ZERO; for (Map.Entry<BigDecimal, BigDecimal> e : askList) askMax = askMax.add(e.getValue());
        BigDecimal running = BigDecimal.ZERO;
        List<View> askRows = new ArrayList<>();
        for (Map.Entry<BigDecimal, BigDecimal> e : askList) {
            running = running.add(e.getValue());
            askRows.add(ladderRow(e.getKey(), e.getValue(), e.getKey().multiply(e.getValue()), askMax.signum() == 0 ? 0 : running.divide(askMax, 4, RoundingMode.HALF_UP).floatValue(),
                    true, mineAt.containsKey(e.getKey()), unbackedAt.containsKey(e.getKey()), exactAt.get(e.getKey())));
        }
        for (int i = askRows.size() - 1; i >= 0; i--) asksBox.addView(askRows.get(i));   // best ask just above centre
        List<Map.Entry<BigDecimal, BigDecimal>> bidList = new ArrayList<>(bids.entrySet()).subList(0, Math.min(MAX_ROWS, bids.size()));
        BigDecimal bidMax = BigDecimal.ZERO; for (Map.Entry<BigDecimal, BigDecimal> e : bidList) bidMax = bidMax.add(e.getValue());
        running = BigDecimal.ZERO;
        for (Map.Entry<BigDecimal, BigDecimal> e : bidList) {
            running = running.add(e.getValue());
            bidsBox.addView(ladderRow(e.getKey(), e.getValue(), e.getKey().multiply(e.getValue()), bidMax.signum() == 0 ? 0 : running.divide(bidMax, 4, RoundingMode.HALF_UP).floatValue(),
                    false, mineAt.containsKey(e.getKey()), unbackedAt.containsKey(e.getKey()), exactAt.get(e.getKey())));
        }
        BigDecimal mid = bestBid != null && bestAsk != null ? bestBid.add(bestAsk).divide(new BigDecimal(2), 8, RoundingMode.HALF_UP) : bestBid != null ? bestBid : bestAsk;
        centerPriceTv.setText(mid == null ? "—" : Util.fmtPrice(mid));
        midTv.setText(mid == null ? "—" : Util.fmtPrice(mid));
        spreadTv.setText(bestBid != null && bestAsk != null ? "spread " + Util.fmt(bestAsk.subtract(bestBid)) : "");
        kindTv.setText(mid == null ? "no live orders on the relays" : bestBid != null && bestAsk != null ? "mid of best bid / best ask" : bestBid != null ? "best bid only" : "best ask only");
        bidsTv.setText(nb + " order" + (nb == 1 ? "" : "s")); asksTv.setText(na + " order" + (na == 1 ? "" : "s"));
        if (book.isEmpty()) asksBox.addView(tv(act.relay().relays().isEmpty() ? "Add a relay in the RELAYS tab." : "No live orders yet.", 11f, Design.DIM2(), Design.sans()));
    }

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
