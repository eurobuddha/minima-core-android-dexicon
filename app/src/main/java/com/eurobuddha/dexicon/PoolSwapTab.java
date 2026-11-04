package com.eurobuddha.dexicon;

import android.annotation.SuppressLint;
import android.widget.LinearLayout;

import com.eurobuddha.dexicon.ui.Ui;

/**
 * ② SWAP — swap straight against the pools, exactly as in PandaPools: its own SwapView (vendored, scripts/vendor-pandapools.sh)
 * with pay / receive, flip, pool price against MEXC, price impact, the 0.50% fee, routing across the pools in one
 * transaction, the on-chain rejection warning and recent activity. Dexicon only adds the explainer around it.
 */
@SuppressLint("ViewConstructor")
public final class PoolSwapTab extends LinearLayout {
    private final com.eurobuddha.pandapools.SwapView swap;

    public PoolSwapTab(MainActivity act, com.eurobuddha.pandapools.SwapView swap) {
        super(act);
        this.swap = swap;
        setOrientation(VERTICAL);
        int pad = Design.dp(act, 12); setPadding(pad, pad, pad, 0);
        Ui.why(this, "SWAP WITH THE POOLS", "The simple way to trade: you swap straight against the pools at their price, in one on-chain "
                + "transaction. No order book and no waiting for a match. Exactly as in PandaPools. Want limit orders or the fast book "
                + "instead? That's ③ Exchange, which also draws on these same pools.");
        addView(swap.getRoot(), new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        Ui.why(this, "HOW IT WORKS", "1  Dexicon finds the live pools and splits your trade across them for the best price.\n"
                + "2  You pay a 0.50% fee, kept by the pools' liquidity providers.\n"
                + "3  If a pool moves before your swap confirms, the swap is rejected and you keep your funds.");
    }

    void shown() { swap.onShown(); }
}
