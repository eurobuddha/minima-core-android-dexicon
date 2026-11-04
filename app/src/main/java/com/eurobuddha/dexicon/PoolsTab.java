package com.eurobuddha.dexicon;

import android.annotation.SuppressLint;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.eurobuddha.dexicon.ui.Ui;

/**
 * ② POOLS — optional. Explains what a pool is and that the exchange already trades against every one; "Skip" to ③ is
 * the main button. Below it, PandaPools' own live pool list (its PoolsView, vendored) and the commitment of hosting one.
 */
@SuppressLint("ViewConstructor")
public final class PoolsTab extends LinearLayout {
    private final MainActivity act;
    private final TextView live, yours;
    private final com.eurobuddha.pandapools.PoolsView list;

    public PoolsTab(MainActivity act, com.eurobuddha.pandapools.PoolsView list) {
        super(act);
        this.act = act;
        this.list = list;
        setOrientation(VERTICAL);
        int pad = Design.dp(act, 12); setPadding(pad, pad, pad, pad);

        Ui.why(this, "WHAT A POOL IS", "A pool holds MINIMA and MxUSD and trades automatically for a 0.5% fee that goes to whoever hosts "
                + "it. The exchange (③) already trades against every pool, so you never need a pool to trade. Most people skip this step.");

        LinearLayout c = Ui.card(this);
        c.addView(Ui.label(act, "LIVE POOLS · MINIMA / MxUSD"));
        live = Ui.text(act, "", 14f, Design.TEXT(), Design.mono());
        c.addView(live, Ui.wide(act, 6, 6));
        yours = Ui.text(act, "", 13f, Design.DIM(), Design.sans());
        c.addView(yours, Ui.wide(act, 0, 10));
        TextView skip = Ui.text(act, "Skip — go to the exchange →", 13f, Design.ON_ACCENT(), Design.sansBold());
        skip.setGravity(Gravity.CENTER); int bp = Design.dp(act, 13); skip.setPadding(0, bp, 0, bp);
        skip.setBackground(Design.gradientCta(act)); Design.pressable(skip);
        skip.setOnClickListener(v -> act.showTab(MainActivity.TAB_TRADE));
        c.addView(skip);

        addView(list.getRoot(), new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        Ui.why(this, "HOSTING ONE IS A COMMITMENT", "• Your phone must stay online. Pool coins are renewed about twice a day.\n"
                + "• A pool left dark for about 1,700 blocks recovers only from an unpruned MegaMMR. Dexicon has eurobuddha.com's built in; "
                + "the other public MegaMMRs are pruned and can't help.\n"
                + "• A verified backup file is mandatory before the pool goes live.\n"
                + "• You earn the fee on every trade through your pool.");
    }

    void shown() { list.onShown(); }

    public void render() {
        int n = act.restingPools().size();
        Ui.set(live, n == 0 ? (act.paired() ? "Scanning the chain for pools…" : "Pair Dexicon with your node to see the live pools.") : n + (n == 1 ? " pool is" : " pools are") + " live. ③ trades against all of them.");
        int mine = com.eurobuddha.pandapools.DexiconPools.ownPoolCount(act);
        Ui.set(yours, mine == 0 ? "YOUR POOLS · You don't host a pool. That's fine: the exchange uses all the pools." : "YOUR POOLS · You host " + mine + (mine == 1 ? " pool." : " pools."));
    }
}
