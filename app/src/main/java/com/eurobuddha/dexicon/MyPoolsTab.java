package com.eurobuddha.dexicon;

import android.annotation.SuppressLint;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.eurobuddha.dexicon.ui.Ui;
import com.eurobuddha.pandapools.DexiconPools;

import java.util.List;

/**
 * ② MY POOLS — PandaPools' own My LP screen (MyLpView, vendored) in full: create (obligations ▸ token ▸ amounts ▸ confirm
 * ▸ the forced, verified backup that cannot be skipped), the backup card, the recovery menu, deposit / withdraw / close /
 * migrate / collect and the stranding warnings; then PandaPools' pool activity (ActivityView). Dexicon adds the keep-alive
 * explainer, the "Resolve interrupted write" step PandaPools keeps on its Wallet tab, and the AWAITING HAND-OVER card for
 * pools restored from standalone PandaPools that it still keeps.
 */
@SuppressLint("ViewConstructor")
public final class MyPoolsTab extends LinearLayout {
    private final MainActivity act;
    private final com.eurobuddha.pandapools.MyLpView mine;
    private final com.eurobuddha.pandapools.ActivityView activity;
    private final LinearLayout notices;

    public MyPoolsTab(MainActivity act, com.eurobuddha.pandapools.MyLpView mine, com.eurobuddha.pandapools.ActivityView activity) {
        super(act);
        this.act = act; this.mine = mine; this.activity = activity;
        setOrientation(VERTICAL);
        int pad = Design.dp(act, 12); setPadding(pad, pad, pad, pad);
        Ui.why(this, "KEEPING YOUR POOL ALIVE", "Dexicon's background service renews every pool you host before its coins age out (about "
                + "twice a day), re-announces it to other wallets and finishes withdrawals, even with the app closed. Keep the phone "
                + "online and exclude Dexicon from battery optimisation. A pool left dark for about 1,700 blocks recovers only from an "
                + "unpruned MegaMMR: eurobuddha.com:9001 is built in.");
        notices = new LinearLayout(act);
        notices.setOrientation(VERTICAL);
        addView(notices);
        addView(mine.getRoot(), new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        addView(Ui.label(act, "POOL ACTIVITY"), Ui.wide(act, 12, 4));
        addView(activity.getRoot(), new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
    }

    void shown() { mine.onShown(); activity.onShown(); render(); }

    public void render() {
        notices.removeAllViews();
        LinearLayout c = Ui.card(notices);
        c.addView(Ui.label(act, "KEEPER"));
        c.addView(Ui.text(act, "Battery optimisation: " + (act.isBatteryExempt() ? "off (good)" : "ON — Android may stop the keeper overnight")
                + "\nRecovery source: eurobuddha.com:9001 · unpruned MegaMMR", 13f, Design.TEXT(), Design.sans()), Ui.wide(act, 6, 0));
        if (!act.isBatteryExempt()) {
            TextView ask = Ui.button(act, "Exclude Dexicon from battery optimisation");
            ask.setOnClickListener(v -> act.requestBatteryExemptionNow());
            c.addView(ask, Ui.wide(act, 8, 0));
        }
        if (DexiconPools.interruptedWrite(act)) {
            LinearLayout w = Ui.card(notices);
            w.addView(Ui.text(act, "Pool signing is paused", 15f, Design.RED(), Design.sansBold()));
            w.addView(Ui.para(act, "A pool transaction lost the node's reply. It may still have completed, so nothing new is signed until "
                    + "you check. Restart your node, check Activity and your balances, then resolve it here."), Ui.wide(act, 6, 6));
            TextView fix = Ui.button(act, "Resolve interrupted write");
            fix.setOnClickListener(v -> { DexiconPools.resolveInterruptedWrite(act); });
            w.addView(fix);
        }
        List<String> waiting = DexiconPools.keptElsewhereNow(act);
        if (!waiting.isEmpty()) {
            LinearLayout a = Ui.card(notices);
            boolean unreadable = DexiconPools.custodyUnreadable(act);
            a.addView(Ui.text(act, unreadable ? "UPDATE PANDAPOOLS" : "KEPT BY PANDAPOOLS", 13f, unreadable ? Design.RED() : Design.ACCENT(), Design.sansBold()));
            a.addView(Ui.para(act, unreadable
                    ? "Standalone PandaPools is installed but cannot tell Dexicon which pools it keeps, so Dexicon signs for none of these "
                    + "until you update PandaPools (two keepers would fight over the same reserves)."
                    : "Standalone PandaPools keeps " + (waiting.size() == 1 ? "this pool" : "these pools") + ", so Dexicon leaves "
                    + (waiting.size() == 1 ? "it" : "them") + " alone until PandaPools hands " + (waiting.size() == 1 ? "it" : "them")
                    + " over (two keepers would fight over the same reserves)."), Ui.wide(act, 6, 6));
            for (String p : waiting) a.addView(Ui.evidence(act, "Pool", p));
        }
    }
}
