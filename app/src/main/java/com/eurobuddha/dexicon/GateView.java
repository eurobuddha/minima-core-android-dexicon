package com.eurobuddha.dexicon;

import android.content.Intent;
import android.net.Uri;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.eurobuddha.dexicon.ui.Ui;

import java.util.List;

/**
 * "One trading app per phone": shown INSTEAD of every trading screen while AtomiX or PandaPools is on this phone or
 * paired with the node. It says why first, then walks each app out safely. Nothing here moves funds.
 */
final class GateView extends ScrollView {
    private final MainActivity act;
    private final LinearLayout body;

    GateView(MainActivity act) {
        super(act);
        this.act = act;
        setBackgroundColor(Design.BG());
        body = new LinearLayout(act);
        body.setOrientation(LinearLayout.VERTICAL);
        int p = Design.dp(act, 16); body.setPadding(p, p, p, p);
        addView(body);
    }

    void render() {
        body.removeAllViews();
        boolean ax = TradingGate.installed(act, TradingGate.ATOMIX), pp = TradingGate.installed(act, TradingGate.PANDAPOOLS);
        List<String> dapps = TradingGate.miniDapps(act);

        TextView logo = Ui.text(act, "◆ DEXICON  v" + BuildConfig.VERSION_NAME, 13f, Design.DIM(), Design.monoBold());
        body.addView(logo, Ui.wide(act, 0, 10));
        body.addView(Ui.text(act, "One trading app per phone", 24f, Design.TEXT(), Design.sansBold()), Ui.wide(act, 0, 10));

        // The warning: why, before anything to do.
        LinearLayout warn = new LinearLayout(act);
        warn.setOrientation(LinearLayout.VERTICAL);
        warn.setBackground(Design.roundBg(act, Design.ACCENT_SOFT(), 12));
        int wp = Design.dp(act, 14); warn.setPadding(wp, wp, wp, wp);
        warn.setLayoutParams(Ui.wide(act, 0, 12));
        body.addView(warn);
        warn.addView(Ui.text(act, "⚠ Dexicon is switched off: " + found(ax, pp, dapps) + ".", 15f, Design.ACCENT(), Design.sansBold()), Ui.wide(act, 0, 8));
        warn.addView(para("Dexicon already has AtomiX and PandaPools built in. If they run next to it, they share one node and one wallet, and they work against each other:"));
        warn.addView(step("1", "Two AtomiX makers on one wallet.", "Both could offer, and lock, the same coins. A swap could stall halfway and have to wait for its timelock refund."));
        warn.addView(step("2", "Two keepers for the same pool.", "Each one rebuilds the pool's reserves every ~900 blocks, and they would spend the same coins and reject each other's transactions. Traders would see a dead pool."));
        warn.addView(step("3", "Two records of every trade.", "A swap started in one app can't be seen or refunded from the other, so you could lose track of it."));
        warn.addView(para("To keep your funds safe, Dexicon won't trade until the others are gone. Nothing has been touched: your wallet, swaps and pools are exactly as they were. Anything Dexicon already had in flight keeps settling in the background."));

        body.addView(Ui.text(act, "Move to Dexicon. Each remove button unlocks only when nothing you own could be lost.", 12.5f, Design.DIM(), Design.sans()), Ui.wide(act, 4, 12));

        if (ax) appCard("AtomiX", TradingGate.ATOMIX, "finish or refund every swap and withdraw your market");
        if (pp) appCard("PandaPools", TradingGate.PANDAPOOLS, "make sure no pool or withdrawal of yours is still live");
        for (String d : dapps) dappCard(d);

        TextView again = Ui.button(act, "Check again");
        again.setOnClickListener(v -> act.recheckGate());
        body.addView(again, Ui.wide(act, 4, 12));
        body.addView(Ui.text(act, "Prefer the separate apps? Uninstall Dexicon instead. AtomiX and PandaPools stay in the store.", 12f, Design.DIM2(), Design.sans()));
    }

    private static String found(boolean ax, boolean pp, List<String> dapps) {
        String apps = ax && pp ? "AtomiX and PandaPools are" : ax ? "AtomiX is" : pp ? "PandaPools is" : "";
        String s = apps.isEmpty() ? "" : apps + " also on this phone";
        if (!dapps.isEmpty()) {
            StringBuilder names = new StringBuilder();
            for (String d : dapps) { String n = d.split("\\|", 2)[0]; if (names.indexOf(n) < 0) names.append(names.length() == 0 ? "" : " and ").append(n); }
            s += (s.isEmpty() ? "" : ", and ") + "the " + names + " MiniDapp" + (names.indexOf(" and ") >= 0 ? "s are" : " is") + " paired with your node";
        }
        return s;
    }

    private void appCard(String name, String pkg, String what) {
        LinearLayout c = Ui.card(body);
        boolean ok = TradingGate.handedOver(act, pkg);
        LinearLayout head = new LinearLayout(act); head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(Ui.text(act, name + " app", 16f, Design.TEXT(), Design.sansBold()), new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        head.addView(Ui.text(act, ok ? "READY TO REMOVE" : "1 STEP LEFT", 10.5f, ok ? Design.IN() : Design.ACCENT(), Design.sansBold()));
        c.addView(head, Ui.wide(act, 0, 8));
        c.addView(check(ok, ok ? name + " confirmed nothing of yours is left in it." : "Step 1: in " + name + ", " + what + ". Its \"Hand over to Dexicon\" step checks this for you."));
        TextView hand = Ui.button(act, ok ? "Hand over again" : "1 · Hand over in " + name);
        hand.setOnClickListener(v -> act.startHandover(pkg, name));
        c.addView(hand, Ui.wide(act, 6, 6));
        TextView rm = Ui.button(act, "2 · Remove " + name);
        rm.setEnabled(ok); rm.setAlpha(ok ? 1f : 0.4f);
        rm.setOnClickListener(v -> {
            if (!TradingGate.handedOver(act, pkg)) { act.toast("Hand over in " + name + " first"); render(); return; }
            act.startActivity(new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + pkg)));
        });
        c.addView(rm, Ui.wide(act, 0, 0));
    }

    private void dappCard(String row) {
        String[] p = row.split("\\|", 2);
        String name = p[0], uid = p.length > 1 ? p[1] : "";
        LinearLayout c = Ui.card(body);
        c.addView(Ui.text(act, name + " MiniDapp on your node", 16f, Design.TEXT(), Design.sansBold()), Ui.wide(act, 0, 8));
        c.addView(para("Same rule for the MiniDapp. Dexicon won't remove it for you, because removing it deletes its data. Open it in your node's MiniHub, "
                + ("AtomiX".equals(name) ? "finish or refund every swap and withdraw your market" : "make sure no pool or withdrawal of yours is still live")
                + ", then remove it there and tap Check again."));
        if (!uid.isEmpty()) c.addView(Ui.evidence(act, "MiniDapp uid", uid));
    }

    private TextView para(String s) { TextView t = Ui.para(act, s); t.setTextColor(Design.TEXT()); t.setLayoutParams(Ui.wide(act, 0, 8)); return t; }

    private LinearLayout step(String n, String bold, String rest) {
        LinearLayout r = new LinearLayout(act);
        r.addView(Ui.text(act, n + "  ", 13f, Design.ACCENT(), Design.monoBold()));
        TextView t = Ui.para(act, bold + " " + rest); t.setTextColor(Design.TEXT());
        r.addView(t, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        r.setLayoutParams(Ui.wide(act, 0, 8));
        return r;
    }

    private TextView check(boolean ok, String s) {
        TextView t = Ui.para(act, (ok ? "✓  " : "○  ") + s);
        t.setTextColor(ok ? Design.IN() : Design.TEXT());
        return t;
    }
}
