package com.eurobuddha.dexicon;

import android.annotation.SuppressLint;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.math.BigDecimal;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** HISTORY tab: every fill with net totals and the CSV export (stable column names, see TradeExport). */
@SuppressLint("ViewConstructor")
public final class HistoryTab extends LinearLayout {

    private final MainActivity act;
    private final LinearLayout rows;
    private final SimpleDateFormat fmt = new SimpleDateFormat("dd MMM HH:mm", Locale.US);

    public HistoryTab(MainActivity act) {
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

    public void render() {
        rows.removeAllViews();
        List<JSONObject> fills = act.db().fills(500);
        LinearLayout summary = card();
        summary.addView(tv("MY FILLS", 10f, Design.DIM(), Design.sansBold()));
        BigDecimal netM = BigDecimal.ZERO, netU = BigDecimal.ZERO; int done = 0;
        for (JSONObject f : fills) {
            if (!"done".equals(f.optString("status"))) continue;
            done++;
            boolean buy = "buy".equals(f.optString("side"));
            BigDecimal m = Util.dec(f.optString("filled", "0"));
            boolean maker = "maker".equals(f.optString("role"));
            BigDecimal u = maker ? Util.dec(buy ? f.optString("paid", "0") : f.optString("received", "0")) : Util.dec(buy ? f.optString("gave", "0") : f.optString("got", "0"));
            netM = netM.add(buy ? m : m.negate()); netU = netU.add(buy ? u.negate() : u);
        }
        summary.addView(tv(fills.isEmpty() ? "No fills yet." : "Rows " + fills.size() + " (" + done + " done)   Net " + Util.fmt(netM) + " MINIMA   " + Util.fmt(netU) + " MxUSD", 10.5f, Design.TEXT(), Design.mono()));
        summary.addView(tv("Net totals count settled fills only. The txpowid is the pre-mining id; settlement is proven by the output coins.", 9.5f, Design.DIM2(), Design.sans()));
        TextView export = tv("EXPORT CSV", 10.5f, Design.ACCENT(), Design.mono());
        export.setGravity(Gravity.CENTER); export.setPadding(0, Design.dp(getContext(), 9), 0, Design.dp(getContext(), 9));
        export.setBackground(Design.stroked(getContext(), Design.SURFACE2(), 10));
        export.setOnClickListener(v -> act.exportCsv());
        Design.pressable(export);
        LayoutParams el = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT); el.topMargin = Design.dp(getContext(), 8);
        summary.addView(export, el);

        for (JSONObject f : fills) {
            LinearLayout c = card();
            boolean buy = "buy".equals(f.optString("side"));
            LinearLayout top = new LinearLayout(getContext());
            top.setGravity(Gravity.CENTER_VERTICAL);
            top.addView(tv((buy ? "BUY " : "SELL ") + f.optString("filled"), 11f, buy ? Design.IN() : Design.RED(), Design.mono()), new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
            TextView price = tv(Util.fmtPrice(Util.dec(f.optString("price", "0"))), 11f, Design.TEXT(), Design.mono()); price.setGravity(Gravity.END);
            top.addView(price);
            c.addView(top);
            c.addView(tv(fmt.format(new Date(f.optLong("ts", 0) * 1000)) + "   " + f.optString("role", "").toUpperCase(Locale.ROOT) + "   " + f.optString("status", "").toUpperCase(Locale.ROOT), 9.5f, Design.DIM(), Design.mono()));
            TextView tx = tv("txpow " + f.optString("txpowid"), 8.5f, Design.DIM2(), Design.mono());
            tx.setOnClickListener(v -> act.copy("txpow", f.optString("txpowid")));
            c.addView(tx);
            JSONArray coins = f.optJSONArray("coins");
            if (coins != null) for (int i = 0; i < coins.length(); i++) {
                final String coin = coins.optString(i);
                TextView ct = tv("coin " + coin, 8.5f, Design.DIM2(), Design.mono());
                ct.setOnClickListener(v -> act.copy("coin", coin));
                c.addView(ct);
            }
        }
    }
}
