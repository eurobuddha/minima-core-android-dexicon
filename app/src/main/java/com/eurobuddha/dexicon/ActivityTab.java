package com.eurobuddha.dexicon;

import android.annotation.SuppressLint;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.eurobuddha.atomix.swap.SwapDb;
import com.eurobuddha.dexicon.doors.DoorHost;
import com.eurobuddha.dexicon.ui.LedgerView;
import com.eurobuddha.dexicon.ui.Ui;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.TimeZone;

/**
 * ACTIVITY tab: a rolling log of every trade this device made or co-signed and every AtomiX swap (see {@link ActivityLog},
 * {@link Ledger}). Live work shows its steps as they happen; finished work folds to one headline and opens to "What
 * happened" + the full proof. Rows are updated in place, keyed by id, so the door swap's 1 s ticker and any text
 * selection survive the 5 s refresh. Every identifier is printed in full and copies in full on tap.
 */
@SuppressLint("ViewConstructor")
public final class ActivityTab extends LinearLayout {

    private final MainActivity act;
    private final LinearLayout rows;
    private final TextView empty;
    private DoorsTab.Swaps hooks;

    public ActivityTab(MainActivity act) {
        super(act);
        this.act = act;
        setOrientation(VERTICAL);
        int pad = Design.dp(act, 12);
        setPadding(pad, pad, pad, pad);
        Ui.titleRow(this, "Activity", "info_activity",
                "Trades and swaps roll in here step by step while they happen. When one finishes it folds to a single line — tap any line "
                + "to see every step and the full on-chain proof. A trade is confirmed (done) after " + ActivityLog.CONFIRM_BLOCKS + " blocks. "
                + "The txpow id is the pre-mining id; inclusion is proven by an output coin. Ids are always shown in full; tap one to copy it.");
        empty = Ui.para(act, "No trades yet. Every BUY/SELL you make, every fill your node co-signs and every AtomiX swap shows its live progress here.");
        addView(empty, Ui.wide(act, 8, 8));
        rows = new LinearLayout(act); rows.setOrientation(VERTICAL);
        addView(rows);
    }

    public void render() {
        List<JSONObject> list = ActivityLog.list(act.db());
        DoorHost doors = DoorHost.get(act);
        if (hooks == null) hooks = new DoorsTab.Swaps(act, doors);
        List<SwapDb.Swap> swaps = doors.swaps();
        empty.setVisibility(list.isEmpty() && swaps.isEmpty() ? VISIBLE : GONE);
        long cb = act.chainBlock(), now = System.currentTimeMillis();
        TimeZone tz = TimeZone.getDefault();
        List<Ledger.Item> items = new ArrayList<>();
        for (JSONObject a : list) items.add(Ledger.activity(a, cb, now, tz));
        for (SwapDb.Swap s : swaps) items.add(hooks.item(s));
        LedgerView.sync(act, rows, Ledger.arrange(items, true, now, tz), "act", hooks);
    }
}
