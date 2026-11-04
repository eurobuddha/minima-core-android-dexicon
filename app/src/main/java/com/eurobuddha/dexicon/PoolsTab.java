package com.eurobuddha.dexicon;

import android.annotation.SuppressLint;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.eurobuddha.dexicon.ui.Ui;

/**
 * ② POOLS — optional. Explains what a pool is and that the exchange already trades against every one; "Skip" to ③ is
 * the main button. Swapping with a pool and hosting one (PandaPools' own screens) move in here in phase 3.
 */
@SuppressLint("ViewConstructor")
public final class PoolsTab extends LinearLayout {
    private final MainActivity act;
    private final TextView live;

    public PoolsTab(MainActivity act) {
        super(act);
        this.act = act;
        setOrientation(VERTICAL);
        int pad = Design.dp(act, 12); setPadding(pad, pad, pad, pad);

        Ui.why(this, "WHAT A POOL IS", "A pool holds MINIMA and MxUSD and trades automatically for a 0.5% fee. The exchange (③) already "
                + "trades against every pool, so you never need one of your own to trade.");

        LinearLayout c = Ui.card(this);
        c.addView(Ui.label(act, "LIVE POOLS · MINIMA / MxUSD"));
        live = Ui.text(act, "", 14f, Design.TEXT(), Design.mono());
        c.addView(live, Ui.wide(act, 6, 10));
        TextView skip = Ui.text(act, "Skip — go to the exchange →", 13f, Design.ON_ACCENT(), Design.sansBold());
        skip.setGravity(Gravity.CENTER); int bp = Design.dp(act, 13); skip.setPadding(0, bp, 0, bp);
        skip.setBackground(Design.gradientCta(act)); Design.pressable(skip);
        skip.setOnClickListener(v -> act.showTab(MainActivity.TAB_TRADE));
        c.addView(skip);

        Ui.why(this, "HOSTING A POOL IS A COMMITMENT", "Your phone must stay online, a verified backup is mandatory, and you earn the fees. "
                + "Swapping straight with a pool, and creating your own, move into Dexicon in its next update.");
    }

    public void render() {
        int n = act.pools().size();
        Ui.set(live, n == 0 ? (act.paired() ? "Scanning the chain for pools…" : "Pair Dexicon with your node to see the live pools.") : n + (n == 1 ? " pool is" : " pools are") + " live. ③ trades against all of them.");
    }
}
