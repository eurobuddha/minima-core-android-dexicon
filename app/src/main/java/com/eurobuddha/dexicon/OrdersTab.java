package com.eurobuddha.dexicon;

import android.annotation.SuppressLint;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.eurobuddha.dexicon.maker.MyOrder;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** ORDERS tab: OPEN (my resting orders + takes awaiting a maker) / MY FILLS (settling / done / lost badges). */
@SuppressLint("ViewConstructor")
public final class OrdersTab extends LinearLayout {

    private final MainActivity act;
    private final LinearLayout seg, body;
    private final SimpleDateFormat fmt = new SimpleDateFormat("dd MMM HH:mm", Locale.US);
    private int sel = 0;

    public OrdersTab(MainActivity act) {
        super(act);
        this.act = act;
        setOrientation(VERTICAL);
        int pad = Design.dp(act, 12);
        setPadding(pad, pad, pad, pad);
        seg = new LinearLayout(act);
        String[] names = {"OPEN", "MY FILLS"};
        for (int i = 0; i < names.length; i++) {
            final int idx = i;
            TextView t = new TextView(act);
            t.setText(names[i]); t.setTextSize(11f); t.setTypeface(Design.sansBold()); t.setGravity(Gravity.CENTER);
            t.setPadding(0, Design.dp(act, 8), 0, Design.dp(act, 8));
            t.setOnClickListener(v -> { sel = idx; restyle(); render(); });
            seg.addView(t, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
        }
        addView(seg);
        body = new LinearLayout(act); body.setOrientation(VERTICAL); body.setPadding(0, Design.dp(act, 10), 0, 0);
        addView(body);
        restyle();
    }

    private void restyle() {
        for (int i = 0; i < seg.getChildCount(); i++) {
            TextView t = (TextView) seg.getChildAt(i);
            t.setTextColor(i == sel ? Design.ON_ACCENT() : Design.DIM());
            t.setBackground(i == sel ? Design.roundBg(getContext(), Design.ACCENT(), 10) : Design.roundBg(getContext(), Design.SURFACE2(), 10));
        }
    }

    private TextView line(String s, int color, float size) {
        TextView t = new TextView(getContext());
        t.setText(s); t.setTextColor(color); t.setTypeface(Design.mono()); t.setTextSize(size);
        return t;
    }

    /** A full identifier line: never truncated, tap copies the complete value. */
    TextView id(String label, String value) {
        TextView t = line(label + " " + value, Design.DIM2(), 8.5f);
        t.setOnClickListener(v -> act.copy(label, value));
        return t;
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(getContext());
        c.setOrientation(VERTICAL);
        c.setBackground(Design.card(getContext(), 10));
        int p = Design.dp(getContext(), 10); c.setPadding(p, p, p, p);
        LayoutParams lp = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT); lp.bottomMargin = Design.dp(getContext(), 8);
        body.addView(c, lp);
        return c;
    }

    public void render() {
        body.removeAllViews();
        if (sel == 0) renderOpen(); else renderFills();
    }

    private void renderOpen() {
        boolean any = false;
        for (JSONObject p : act.db().pendings()) {
            any = true;
            JSONObject order = p.optJSONObject("order"), plan = p.optJSONObject("plan");
            LinearLayout c = card();
            c.setAlpha(0.8f);
            String side = order != null && "sell".equals(order.optString("side")) ? "BUY " : "SELL ";
            c.addView(line(side + (plan == null ? "" : plan.optString("filled")) + " MINIMA @ " + (order == null ? "" : order.optString("price")) + "  ·  TAKE SENT", Design.ACCENT(), 12f));
            c.addView(line("waiting for the maker to co-sign · sent " + Util.ago(System.currentTimeMillis() - p.optLong("sent", 0)), Design.DIM2(), 9.5f));
            if (order != null) c.addView(id("order", order.optString("orderId")));
        }
        for (MyOrder o : act.myOrders()) {
            if (!o.live()) continue;
            any = true;
            LinearLayout c = card();
            LinearLayout top = new LinearLayout(getContext());
            top.setGravity(Gravity.CENTER_VERTICAL);
            boolean sell = "sell".equals(o.side);
            top.addView(line((sell ? "SELL " : "BUY ") + Util.fmt(Util.dec(o.remaining)) + " MINIMA @ " + Util.fmtPrice(Util.dec(o.price)), sell ? Design.RED() : Design.IN(), 12f),
                    new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
            TextView edit = line(" ✎ ", Design.DIM(), 14f); edit.setOnClickListener(v -> act.editOrder(o)); top.addView(edit);
            TextView cancel = line(" ✕ ", Design.RED(), 14f); cancel.setOnClickListener(v -> act.cancelOrder(o)); top.addView(cancel);
            c.addView(top);
            long left = o.expiry - System.currentTimeMillis() / 1000;
            c.addView(line("of " + Util.fmt(Util.dec(o.amount)) + "  ·  " + o.coins.size() + " backing coin" + (o.coins.size() == 1 ? "" : "s") + (o.coins.isEmpty() ? " (UNBACKED — takers skip it until the next refresh)" : "")
                    + "  ·  expires in " + Math.max(0, left / 3600) + "h  ·  seq " + o.seq, Design.DIM2(), 9.5f));
            c.addView(id("order", o.orderId));
            c.addView(id("pay-to", o.address));
        }
        if (!any) body.addView(line("No open orders", Design.DIM2(), 11f));
    }

    private void renderFills() {
        List<JSONObject> fills = act.db().fills(200);
        if (fills.isEmpty()) { body.addView(line("No fills yet", Design.DIM2(), 11f)); return; }
        for (JSONObject f : fills) {
            LinearLayout c = card();
            LinearLayout top = new LinearLayout(getContext());
            top.setGravity(Gravity.CENTER_VERTICAL);
            boolean buy = "buy".equals(f.optString("side"));
            top.addView(line((buy ? "BUY " : "SELL ") + f.optString("filled") + " MINIMA @ " + f.optString("price"), buy ? Design.IN() : Design.RED(), 12f),
                    new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
            String st = f.optString("status", "");
            TextView badge = Design.pill(getContext(), st.toUpperCase(Locale.ROOT), "done".equals(st) ? Design.IN() : "lost".equals(st) ? Design.RED() : Design.ACCENT(), Design.ON_ACCENT());
            top.addView(badge);
            c.addView(top);
            c.addView(line(fmt.format(new Date(f.optLong("ts", 0) * 1000)) + "   " + f.optString("role", "").toUpperCase(Locale.ROOT)
                    + ("done".equals(st) ? "   block " + f.optString("block", "") : "lost".equals(st) ? "   not in a block after 2h — coins were not spent" : "   waiting for a block"), Design.DIM(), 9.5f));
            c.addView(id("txpow", f.optString("txpowid")));
            c.addView(id("order", f.optString("orderId")));
            c.addView(id("counterparty", f.optString("counterparty")));
        }
    }
}
