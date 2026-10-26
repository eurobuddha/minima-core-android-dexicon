package com.eurobuddha.dexicon.ui;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import java.util.List;

/** Two columns side by side on a wide screen (the open Fold, a tablet), one column on a phone. The views are the
 *  same either way: only their parent changes, so state, listeners and text survive a fold or unfold. */
public final class Wide {
    private Wide() {}

    public static final int MIN_DP = 600;

    public static boolean on(Context c) { return c.getResources().getConfiguration().screenWidthDp >= MIN_DP; }

    /** Lay `cards` (phone order) into `host`: stacked, or split into a left and right column when wide.
     *  `left` holds the cards that go in the left column; the rest go right, keeping their order. */
    public static void arrange(LinearLayout host, List<View> cards, List<View> left) {
        for (View v : cards) if (v.getParent() instanceof ViewGroup) ((ViewGroup) v.getParent()).removeView(v);
        host.removeAllViews();
        if (!on(host.getContext())) {
            host.setOrientation(LinearLayout.VERTICAL);
            for (View v : cards) host.addView(v);
            return;
        }
        host.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout l = column(host.getContext()), r = column(host.getContext());
        int gap = Math.round(12 * host.getResources().getDisplayMetrics().density);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        lp.rightMargin = gap / 2;
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        rp.leftMargin = gap / 2;
        host.addView(l, lp); host.addView(r, rp);
        for (View v : cards) (left.contains(v) ? l : r).addView(v);
    }

    private static LinearLayout column(Context c) {
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        return col;
    }
}
