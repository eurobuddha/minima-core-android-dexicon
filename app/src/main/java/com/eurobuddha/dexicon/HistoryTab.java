package com.eurobuddha.dexicon;

import android.annotation.SuppressLint;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.eurobuddha.atomix.swap.SwapDb;
import com.eurobuddha.dexicon.doors.DoorHost;
import com.eurobuddha.dexicon.ui.LedgerView;
import com.eurobuddha.dexicon.ui.Ui;

import org.json.JSONObject;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

/** HISTORY tab: the permanent ledger — every fill and every finished AtomiX swap, one line each, grouped by day, with the
 *  net of settled trades and the CSV export (stable column names, see TradeExport). Tap a line for its facts and proof. */
@SuppressLint("ViewConstructor")
public final class HistoryTab extends LinearLayout {

    private final MainActivity act;
    private final LinearLayout rows;
    private final TextView netM, netU, counts;
    private DoorsTab.Swaps hooks;

    public HistoryTab(MainActivity act) {
        super(act);
        this.act = act;
        setOrientation(VERTICAL);
        int pad = Design.dp(act, 12);
        setPadding(pad, pad, pad, pad);
        Ui.titleRow(this, "History", "info_history",
                "Every trade this node made and every AtomiX swap it finished, newest first. One line each — tap a line for the amounts, "
                + "block and the full on-chain proof. Net counts settled trades only. The txpow id is the pre-mining id; settlement is proven "
                + "by the output coins. Export CSV saves every fill for your accounts.");
        LinearLayout sum = Ui.card(this);
        LinearLayout row = new LinearLayout(act); row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout net = new LinearLayout(act); net.setOrientation(VERTICAL);
        net.addView(Ui.label(act, "NET · SETTLED TRADES"));
        netM = Ui.text(act, "", 13f, Design.TEXT(), Design.mono());
        netU = Ui.text(act, "", 13f, Design.TEXT(), Design.mono());
        counts = Ui.text(act, "", 11.5f, Design.DIM2(), Design.sans());
        net.addView(netM, Ui.wide(act, 4, 0)); net.addView(netU); net.addView(counts, Ui.wide(act, 2, 0));
        row.addView(net, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
        TextView export = Ui.button(act, "Export CSV");
        export.setOnClickListener(v -> act.exportCsv());
        row.addView(export);
        sum.addView(row);
        rows = new LinearLayout(act); rows.setOrientation(VERTICAL);
        addView(rows);
    }

    private static String signed(BigDecimal v) { return (v.signum() > 0 ? "+" : v.signum() < 0 ? "−" : "") + Util.fmt(v.abs()); }

    public void render() {
        List<JSONObject> fills = act.db().fills(500);
        BigDecimal m = BigDecimal.ZERO, u = BigDecimal.ZERO; int done = 0;
        for (JSONObject f : fills) {
            if (!"done".equals(f.optString("status"))) continue;
            done++;
            boolean buy = "buy".equals(f.optString("side"));
            BigDecimal mm = Util.dec(f.optString("filled", "0"));
            boolean maker = "maker".equals(f.optString("role"));
            BigDecimal uu = maker ? Util.dec(buy ? f.optString("paid", "0") : f.optString("received", "0")) : Util.dec(buy ? f.optString("gave", "0") : f.optString("got", "0"));
            m = m.add(buy ? mm : mm.negate()); u = u.add(buy ? uu.negate() : uu);
        }
        Ui.set(netM, signed(m) + " MINIMA");
        Ui.set(netU, signed(u) + " MxUSD");
        netM.setTextColor(m.signum() < 0 ? Design.RED() : m.signum() > 0 ? Design.IN() : Design.TEXT());
        netU.setTextColor(u.signum() < 0 ? Design.RED() : u.signum() > 0 ? Design.IN() : Design.TEXT());
        Ui.set(counts, fills.isEmpty() ? "No fills yet." : fills.size() + (fills.size() == 1 ? " fill · " : " fills · ") + done + " settled");

        TimeZone tz = TimeZone.getDefault();
        Map<String, JSONObject> actByTx = new HashMap<>();
        for (JSONObject a : ActivityLog.list(act.db())) if (!a.optString("txpowid").isEmpty()) actByTx.put(a.optString("txpowid"), a);
        List<Ledger.Item> items = new ArrayList<>();
        for (JSONObject f : fills) items.add(Ledger.fill(f, actByTx.get(f.optString("txpowid")), tz));
        DoorHost doors = DoorHost.get(act);
        if (hooks == null) hooks = new DoorsTab.Swaps(act, doors);
        for (SwapDb.Swap s : doors.swaps()) {
            String st = s.status == null ? "" : s.status;
            if (SwapDb.ST_COMPLETE.equals(st) || SwapDb.ST_REFUNDED.equals(st) || SwapDb.ST_ERROR.equals(st)) items.add(Ledger.asHistory(hooks.item(s), s, tz));
        }
        LedgerView.sync(act, rows, Ledger.arrange(items, false, System.currentTimeMillis(), tz), "hist", hooks);
    }
}
