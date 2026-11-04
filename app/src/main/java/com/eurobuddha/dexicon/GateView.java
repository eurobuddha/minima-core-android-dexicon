package com.eurobuddha.dexicon;

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
        // edge-to-edge window (setDecorFitsSystemWindows(false)): pad for the bars exactly as the trading chrome does
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(this, (v, insets) -> {
            androidx.core.graphics.Insets bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars() | androidx.core.view.WindowInsetsCompat.Type.displayCutout());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });
    }

    void render() {
        body.removeAllViews();
        if (!TradingGate.conflict(act)) { renderChecking(); return; }
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
        warn.addView(para("To keep your funds safe, Dexicon won't trade until the others are gone. Your open ③ orders and your ① AtomiX market "
                + "(and OTC offer) were withdrawn, so no one can hit them while trading is off: place them again when trading is back. "
                + "Your wallet, swaps and pools are untouched, and anything Dexicon already had in flight keeps settling in the background."));

        body.addView(Ui.text(act, "Move to Dexicon. Each remove button unlocks only when nothing you own could be lost.", 12.5f, Design.DIM(), Design.sans()), Ui.wide(act, 4, 12));

        if (ax) appCard("AtomiX", TradingGate.ATOMIX, "finish or refund every swap and withdraw your market");
        if (pp) appCard("PandaPools", TradingGate.PANDAPOOLS, "make sure no withdrawal or payout of yours is still pending. A live pool moves with you: restore its backup here first");
        for (String d : dapps) dappCard(d);

        TextView again = Ui.button(act, "Check again");
        again.setOnClickListener(v -> act.recheckGate());
        body.addView(again, Ui.wide(act, 4, 12));
        body.addView(Ui.text(act, "Prefer the separate apps? Uninstall Dexicon instead. AtomiX and PandaPools stay in the store.", 12f, Design.DIM2(), Design.sans()));
    }

    /** Nothing found, but the node's MiniHub has never been read on this install: say what is needed, plainly. */
    private void renderChecking() {
        body.addView(Ui.text(act, "◆ DEXICON  v" + BuildConfig.VERSION_NAME, 13f, Design.DIM(), Design.monoBold()), Ui.wide(act, 0, 10));
        body.addView(Ui.text(act, "Checking your node…", 24f, Design.TEXT(), Design.sansBold()), Ui.wide(act, 0, 10));
        String node = NodeApi.nodeApp(act)[1];
        if (act.paired()) {
            body.addView(para("Before trading opens, Dexicon reads your node's MiniHub once to make sure the AtomiX and PandaPools MiniDapps are "
                    + "not paired with it (one trading app per node). This takes a moment."));
        } else {
            body.addView(para("Dexicon needs your node first. Open " + node + " → Apps, enable Dexicon and give it ADMIN. Dexicon then checks "
                    + "that the AtomiX and PandaPools MiniDapps are not paired with your node, and trading opens."));
            TextView open = Ui.button(act, "Open " + node);
            open.setOnClickListener(v -> act.openNodeApp());
            body.addView(open, Ui.wide(act, 4, 8));
        }
        body.addView(para("Nothing is traded or published until then. Anything already in flight keeps settling in the background."));
        TextView again = Ui.button(act, "Check again");
        again.setOnClickListener(v -> act.recheckGate());
        body.addView(again, Ui.wide(act, 4, 12));
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
        boolean ok = TradingGate.lastAnswerOk(act, pkg);   // information from the last answer only; Remove asks again
        LinearLayout head = new LinearLayout(act); head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(Ui.text(act, name + " app", 16f, Design.TEXT(), Design.sansBold()), new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        head.addView(Ui.text(act, ok ? "HANDED OVER" : "1 STEP LEFT", 10.5f, ok ? Design.IN() : Design.ACCENT(), Design.sansBold()));
        c.addView(head, Ui.wide(act, 0, 8));
        c.addView(check(ok, ok ? name + " last said nothing of yours is left in it. Remove asks it once more, then uninstalls."
                : "Step 1: in " + name + ", " + what + ". Its \"Hand over to Dexicon\" step checks this for you."));
        boolean pools = TradingGate.PANDAPOOLS.equals(pkg);
        if (pools) poolSteps(c);
        TextView hand = Ui.button(act, (pools ? "2" : "1") + " · Hand over in " + name);
        hand.setOnClickListener(v -> act.startHandover(pkg, name, false));
        c.addView(hand, Ui.wide(act, 6, 6));
        TextView rm = Ui.button(act, (pools ? "3" : "2") + " · Remove " + name);
        rm.setEnabled(ok); rm.setAlpha(ok ? 1f : 0.4f);
        rm.setOnClickListener(v -> act.startHandover(pkg, name, true));   // a FRESH answer, never a stored one
        c.addView(rm, Ui.wide(act, 0, 0));
    }

    /** PandaPools only: a live pool moves to Dexicon through PandaPools' own verified backup. Dexicon leaves every pool
     *  PandaPools says it keeps (its custody provider) to PandaPools until it hands it over. Render never reads the provider:
     *  it shows the cached read (onResume and the keep-alive refresh it); a stale cache is re-read in the background and
     *  the card re-renders with the result. */
    private void poolSteps(LinearLayout c) {
        com.eurobuddha.pandapools.DexiconPools.Custody k = com.eurobuddha.pandapools.DexiconPools.custodyForUi(act, () -> { if (isAttachedToWindow()) render(); });
        c.addView(para("Host a pool? Restore your PandaPools backup here first. Dexicon records your pools but leaves them to PandaPools "
                + "until PandaPools hands them over: two keepers would fight over the same reserves. Then hand over; PandaPools stops keeping "
                + "exactly the pools Dexicon now holds, and Dexicon starts."));
        TextView restore = Ui.button(act, "1 · Restore your PandaPools backup here");
        restore.setOnClickListener(v -> act.restorePandaPoolsBackup());
        c.addView(restore, Ui.wide(act, 4, 4));
        if (!act.poolRestoreStatus.isEmpty()) {
            TextView st = Ui.para(act, act.poolRestoreStatus);
            st.setTextIsSelectable(true);
            c.addView(st, Ui.wide(act, 0, 6));
        }
        if (com.eurobuddha.pandapools.DexiconPools.unreadableForUi(act, k)) {
            c.addView(Ui.text(act, "UPDATE PANDAPOOLS", 12f, Design.RED(), Design.sansBold()), Ui.wide(act, 4, 2));
            c.addView(para("This PandaPools cannot tell Dexicon which pools it keeps, so Dexicon signs for none of your pools (no "
                    + "keep-fresh, re-announce or payout sweep) until it can. Update PandaPools from the store, then tap Check again."));
        }
        java.util.List<String> waiting = com.eurobuddha.pandapools.DexiconPools.keptElsewhereForUi(act, k);
        if (!waiting.isEmpty()) {
            c.addView(Ui.text(act, "KEPT BY PANDAPOOLS · Dexicon leaves " + (waiting.size() == 1 ? "this pool" : "these " + waiting.size() + " pools") + " alone",
                    12f, Design.ACCENT(), Design.sansBold()), Ui.wide(act, 4, 2));
            for (String a : waiting) c.addView(Ui.evidence(act, "Pool", a));
        }
    }

    private void dappCard(String row) {
        String[] p = row.split("\\|", 2);
        String name = p[0], uid = p.length > 1 ? p[1] : "";
        LinearLayout c = Ui.card(body);
        c.addView(Ui.text(act, name + " MiniDapp on your node", 16f, Design.TEXT(), Design.sansBold()), Ui.wide(act, 0, 8));
        c.addView(para("Same rule for the MiniDapp. Dexicon can't see inside it and won't remove it for you: removing a MiniDapp deletes its "
                + "data, including any swap or pool record that exists only there. Open it in your node's MiniHub and check, one by one:"));
        if ("AtomiX".equals(name)) {
            c.addView(step("1", "Every swap is finished.", "Each one shows complete or refunded; none is still locked, waiting for its counterparty or waiting for a refund timelock."));
            c.addView(step("2", "Your market is withdrawn.", "Your order is withdrawn from the book and auto-publish is off, so no taker can lock against it."));
            c.addView(step("3", "OTC is off.", "Your OTC offer is withdrawn and no OTC deal is still open or executing."));
        } else {
            c.addView(step("1", "No pool of yours is live.", "Every pool you created or added to is withdrawn and its coins are back in your wallet."));
            c.addView(step("2", "No withdrawal is pending.", "Nothing is still waiting to confirm on chain."));
        }
        c.addView(para("Only then remove it there, and tap Check again here."));
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
