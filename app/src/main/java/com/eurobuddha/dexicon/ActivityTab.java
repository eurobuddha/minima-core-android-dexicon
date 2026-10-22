package com.eurobuddha.dexicon;

import android.annotation.SuppressLint;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * ACTIVITY tab: every trade this device made or co-signed, newest first, with its live stage (see {@link ActivityLog}).
 * Card + status chip ported from PandaPools ActivityView.pendingCard / statusChip (0.9.67) onto Dexicon's Design.
 * Every identifier is printed in full and copies in full on tap.
 */
@SuppressLint("ViewConstructor")
public final class ActivityTab extends LinearLayout {

    private final MainActivity act;
    private final LinearLayout rows;
    private final SimpleDateFormat fmt = new SimpleDateFormat("dd MMM HH:mm:ss", Locale.US);

    public ActivityTab(MainActivity act) {
        super(act);
        this.act = act;
        setOrientation(VERTICAL);
        int pad = Design.dp(act, 12);
        setPadding(pad, pad, pad, pad);
        rows = new LinearLayout(act); rows.setOrientation(VERTICAL);
        addView(rows);
    }

    private TextView tv(String s, float size, int color, android.graphics.Typeface tf) {
        TextView t = new TextView(getContext());
        t.setText(s); t.setTextSize(size); t.setTextColor(color); t.setTypeface(tf);
        return t;
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(getContext());
        c.setOrientation(VERTICAL);
        c.setBackground(Design.card(getContext(), 10));
        int p = Design.dp(getContext(), 10); c.setPadding(p, p, p, p);
        LayoutParams lp = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT); lp.bottomMargin = Design.dp(getContext(), 9);
        rows.addView(c, lp);
        return c;
    }

    /** PandaPools ActivityView.statusChip. */
    private TextView chip(String s, int color) {
        TextView t = tv(s, 9.5f, color, Design.sansBold());
        int h = Design.dp(getContext(), 8), v = Design.dp(getContext(), 3);
        t.setPadding(h, v, h, v);
        GradientDrawable g = new GradientDrawable();
        g.setColor((color & 0x00FFFFFF) | 0x1E000000); g.setCornerRadius(Design.dp(getContext(), 100));
        g.setStroke(Math.max(1, Design.dp(getContext(), 1)), (color & 0x00FFFFFF) | 0x78000000);
        t.setBackground(g);
        return t;
    }

    /** A full identifier on its own line; tap copies the complete value. */
    private void ident(LinearLayout c, String label, String value) {
        if (value == null || value.isEmpty()) return;
        TextView t = tv(label + " " + value, 8.5f, Design.DIM2(), Design.mono());
        t.setOnClickListener(v -> act.copy(label, value));
        c.addView(t);
    }

    private static long ts(Object o) { return o instanceof JSONObject ? ((JSONObject) o).optLong("ts") : ((com.eurobuddha.atomix.swap.SwapDb.Swap) o).created; }

    public void render() {
        rows.removeAllViews();
        List<JSONObject> list = ActivityLog.list(act.db());
        long cb = act.chainBlock(), now = System.currentTimeMillis();
        LinearLayout head = card();
        head.addView(tv("ACTIVITY · NEWEST FIRST", 10f, Design.DIM(), Design.sansBold()));
        head.addView(tv(list.isEmpty() ? "No trades yet. Every BUY/SELL you make, every fill your node co-signs and every DOORS swap shows its live progress here."
                : "Confirmed / done after " + ActivityLog.CONFIRM_BLOCKS + " blocks. The txpowid is the pre-mining id; inclusion is proven by an output coin. Tap any id to copy it in full.",
                9.5f, Design.DIM2(), Design.sans()));
        // DOORS swaps (AtomiX taker, both markets) join the timeline by time, each with its own stage and full tx ids.
        com.eurobuddha.dexicon.doors.DoorHost doors = com.eurobuddha.dexicon.doors.DoorHost.get(act);
        java.util.List<Object> items = new java.util.ArrayList<>(list);
        items.addAll(doors.swaps());
        java.util.Collections.sort(items, (x, y) -> Long.compare(ts(y), ts(x)));
        for (Object item : items) {
            if (item instanceof com.eurobuddha.atomix.swap.SwapDb.Swap) { rows.addView(DoorsTab.swapCard(act, doors, (com.eurobuddha.atomix.swap.SwapDb.Swap) item)); continue; }
            JSONObject a = (JSONObject) item;
            LinearLayout c = card();
            boolean buy = "buy".equals(a.optString("side"));
            LinearLayout top = new LinearLayout(getContext());
            top.setGravity(Gravity.CENTER_VERTICAL);
            top.addView(tv((buy ? "BUY " : "SELL ") + a.optString("amount") + " MINIMA @ " + Util.fmtPrice(Util.dec(a.optString("price"))),
                    11f, buy ? Design.IN() : Design.RED(), Design.monoBold()), new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
            int tone = ActivityLog.failedTerminal(a) ? Design.RED() : ActivityLog.confirmed(a, cb) ? Design.IN() : Design.ACCENT();
            top.addView(chip(ActivityLog.chip(a, cb), tone));
            c.addView(top);
            String role = "maker".equals(a.optString("role")) ? "MAKER" : "TAKER";
            String mx = a.optString("mxusd", "");
            c.addView(tv(a.optString("route") + " · " + role + (mx.isEmpty() ? "" : " · " + mx + " MxUSD") + " · " + fmt.format(new Date(a.optLong("ts")))
                    + " · " + Util.ago(now - a.optLong("ts")).replace(" ago", "") + " elapsed", 9.5f, Design.DIM(), Design.mono()));
            c.addView(tv(ActivityLog.statusText(a, cb, now), 10.5f, Design.TEXT(), Design.mono()));
            long post = a.optLong("postBlock", 0), block = a.optLong("block", 0);
            if (post > 0 || block > 0)
                c.addView(tv((post > 0 ? "posted at block " + post : "") + (block > 0 ? (post > 0 ? " · " : "") + "in block " + block + " · "
                        + ActivityLog.confirmationText(ActivityLog.depth(a, cb)) : ""), 9.5f, Design.DIM(), Design.mono()));
            ident(c, "txpow", a.optString("txpowid"));
            ident(c, "transaction", a.optString("txnid"));
            JSONArray outs = a.optJSONArray("outs");
            for (int i = 0; outs != null && i < outs.length(); i++) ident(c, "coin", outs.optString(i));
            ident(c, "order", a.optString("orderId"));
            ident(c, "maker".equals(a.optString("role")) ? "taker" : "maker", "FAST".equals(a.optString("route")) ? a.optString("counterparty") : "");
        }
    }
}
