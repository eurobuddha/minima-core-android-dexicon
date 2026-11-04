package com.eurobuddha.dexicon;

import android.annotation.SuppressLint;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.eurobuddha.atomix.swap.SwapDb;
import com.eurobuddha.dexicon.doors.DoorHost;
import com.eurobuddha.dexicon.ui.LedgerView;
import com.eurobuddha.dexicon.ui.Ui;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.TimeZone;

/**
 * ACTIVITY tab: a rolling log of every trade this device made or co-signed and every AtomiX swap (see {@link ActivityLog},
 * {@link Ledger}). Live work shows its steps as they happen; finished work folds to one headline and opens to "What
 * happened" + the full proof. Rows are updated in place, keyed by id, so the door swap's 1 s ticker and any text
 * selection survive the 5 s refresh. Every identifier is printed in full and copies in full on tap.
 */
@SuppressLint("ViewConstructor")
public final class ActivityTab extends LinearLayout {

    private final MainActivity act;
    private final LinearLayout rows;
    private final TextView empty;
    private DoorsTab.Swaps hooks;
    private final TextView mineTab, marketTab;
    private final LinearLayout marketBox;
    private boolean market;
    private String marketSig = "";

    public ActivityTab(MainActivity act) {
        super(act);
        this.act = act;
        setOrientation(VERTICAL);
        int pad = Design.dp(act, 12);
        setPadding(pad, pad, pad, pad);
        Ui.titleRow(this, "Activity", "info_activity",
                "Trades and swaps roll in here step by step while they happen. When one finishes it folds to a single line — tap any line "
                + "to see every step and the full on-chain proof. A trade is confirmed (done) after " + ActivityLog.CONFIRM_BLOCKS + " blocks. "
                + "The txpow id is the pre-mining id; inclusion is proven by an output coin. Ids are always shown in full; tap one to copy it.");
        // AtomiX's Activity row: My activity / Market history, and "⬇ Export CSV" with the swap history
        LinearLayout pills = new LinearLayout(act); pills.setGravity(android.view.Gravity.CENTER_VERTICAL);
        mineTab = Design.pill(act, "My activity", Design.SURFACE2(), Design.DIM());
        mineTab.setOnClickListener(v -> { market = false; render(); }); Design.pressable(mineTab);
        marketTab = Design.pill(act, "Market history", Design.SURFACE2(), Design.DIM());
        marketTab.setOnClickListener(v -> { market = true; render(); }); Design.pressable(marketTab);
        TextView exportSwaps = Design.pill(act, "⬇ Export CSV", Design.SURFACE2(), Design.DIM());
        exportSwaps.setContentDescription("Export all your AtomiX swaps as CSV");
        exportSwaps.setOnClickListener(v -> act.exportAtomixCsv()); Design.pressable(exportSwaps);
        for (TextView p : new TextView[]{mineTab, marketTab, exportSwaps}) {
            LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            pl.rightMargin = Design.dp(act, 8); pills.addView(p, pl);
        }
        LinearLayout.LayoutParams xl = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        xl.topMargin = Design.dp(act, 4); xl.bottomMargin = Design.dp(act, 4);
        android.widget.HorizontalScrollView pillScroll = new android.widget.HorizontalScrollView(act);   // the cover screen never clips Export
        pillScroll.setHorizontalScrollBarEnabled(false);
        pillScroll.addView(pills);
        addView(pillScroll, xl);
        marketBox = new LinearLayout(act); marketBox.setOrientation(VERTICAL); marketBox.setVisibility(GONE);
        addView(marketBox);
        empty = Ui.para(act, "No trades yet. Every BUY/SELL you make, every fill your node co-signs and every AtomiX swap shows its live progress here.");
        addView(empty, Ui.wide(act, 8, 8));
        rows = new LinearLayout(act); rows.setOrientation(VERTICAL);
        addView(rows);
    }

    public void render() {
        mineTab.setBackground(Design.roundBg(act, market ? Design.SURFACE2() : Design.ACCENT(), 14)); mineTab.setTextColor(market ? Design.DIM() : Design.ON_ACCENT());
        marketTab.setBackground(Design.roundBg(act, market ? Design.ACCENT() : Design.SURFACE2(), 14)); marketTab.setTextColor(market ? Design.ON_ACCENT() : Design.DIM());
        marketBox.setVisibility(market ? VISIBLE : GONE);
        rows.setVisibility(market ? GONE : VISIBLE);
        if (market) { empty.setVisibility(GONE); marketView(); return; }
        List<JSONObject> list = ActivityLog.list(act.db());
        DoorHost doors = DoorHost.get(act);
        if (hooks == null) hooks = new DoorsTab.Swaps(act, doors);
        List<SwapDb.Swap> swaps = doors.liveListSwaps();   // History keeps the hidden ones
        empty.setVisibility(list.isEmpty() && swaps.isEmpty() ? VISIBLE : GONE);
        long cb = act.tip(), now = System.currentTimeMillis();
        TimeZone tz = TimeZone.getDefault();
        List<Ledger.Item> items = new ArrayList<>();
        for (JSONObject a : list) items.add(Ledger.activity(a, cb, now, tz));
        for (SwapDb.Swap s : swaps) { Ledger.Item it = hooks.item(s); it.hideable = true; items.add(it); }
        LedgerView.sync(act, rows, Ledger.arrange(items, true, now, tz), "act", hooks);
    }

    /** AtomiX marketView: the ACTIVE market's prints only — chart of executed trades, last price, filled / open counts,
     *  then the recent prints (when, price, size, filled / cancelled / open). History builds from when it was first seen. */
    private void marketView() {
        DoorHost h = DoorHost.get(act);
        String ccy = com.eurobuddha.dexicon.doors.DoorPlan.coin(h.market());
        List<SwapDb.MarketTrade> chartData = h.executedTrades(200), recent = h.recentTrades(50);
        StringBuilder sig = new StringBuilder(ccy).append(chartData.size()).append('|').append(recent.size());
        for (SwapDb.MarketTrade t : recent) sig.append(t.coinid).append(t.status);
        if (sig.toString().equals(marketSig) && marketBox.getChildCount() > 0) return;   // unchanged: no redraw, no flicker
        marketSig = sig.toString();
        marketBox.removeAllViews();
        com.eurobuddha.atomix.MarketChartView chart = new com.eurobuddha.atomix.MarketChartView(act);
        chart.setBackground(Design.card(act, 14));
        chart.setData(chartData);
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Design.dp(act, 180)); cl.topMargin = Design.dp(act, 6);
        marketBox.addView(chart, cl);
        int ex = 0, op = 0;
        for (SwapDb.MarketTrade t : recent) { if (SwapDb.MT_EXECUTED.equals(t.status)) ex++; else if (SwapDb.MT_OPEN.equals(t.status)) op++; }
        String last = chartData.isEmpty() ? "—" : px(chartData.get(chartData.size() - 1).price);
        TextView stats = Ui.text(act, "last " + last + " USDT/" + ccy + "  ·  " + ex + " filled · " + op + " open  ·  price only (buy/sell not shown)", 11f, Design.DIM2(), Design.sans());
        stats.setPadding(Design.dp(act, 2), Design.dp(act, 8), 0, Design.dp(act, 6));
        marketBox.addView(stats);
        if (recent.isEmpty()) {
            marketBox.addView(Ui.para(act, "No market trades observed yet. AtomiX records " + ccy + " swaps network-wide as they happen — it can't backfill, so history builds from now on."));
            return;
        }
        for (SwapDb.MarketTrade t : recent) {
            LinearLayout r = new LinearLayout(act); r.setGravity(android.view.Gravity.CENTER_VERTICAL);
            r.setBackground(Design.card(act, 12)); r.setPadding(Design.dp(act, 12), Design.dp(act, 9), Design.dp(act, 12), Design.dp(act, 9));
            r.addView(Ui.text(act, android.text.format.DateUtils.getRelativeTimeSpanString(t.observedAt, System.currentTimeMillis(), android.text.format.DateUtils.MINUTE_IN_MILLIS).toString(),
                    11f, Design.DIM2(), Design.sans()), new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            r.addView(Ui.text(act, px(t.price), 14f, Design.TEXT(), Design.mono()));
            TextView size = Ui.text(act, "  " + t.sizeMinima + " " + ccy, 12f, Design.DIM(), Design.mono()); size.setPadding(Design.dp(act, 8), 0, Design.dp(act, 8), 0);
            r.addView(size);
            boolean exec = SwapDb.MT_EXECUTED.equals(t.status), ref = SwapDb.MT_REFUNDED.equals(t.status);
            r.addView(Design.pill(act, exec ? "filled" : ref ? "cancelled" : "open", exec ? Design.IN() : ref ? Design.RED() : Design.SURFACE2(), exec || ref ? Design.ON_ACCENT() : Design.DIM()));
            LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT); rl.topMargin = Design.dp(act, 6);
            marketBox.addView(r, rl);
        }
    }
    private static String px(double v) { return Double.isFinite(v) && v > 0 ? new java.math.BigDecimal(v).round(new java.math.MathContext(6)).stripTrailingZeros().toPlainString() : "—"; }
}
