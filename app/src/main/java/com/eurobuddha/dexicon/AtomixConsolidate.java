package com.eurobuddha.dexicon;

import android.app.AlertDialog;
import android.widget.TextView;

import com.eurobuddha.atomix.IdentityWatch;
import com.eurobuddha.atomix.TradingContext;
import com.eurobuddha.atomix.swap.MinimaHtlc;
import com.eurobuddha.atomix.swap.SwapEngine;
import com.eurobuddha.dexicon.doors.DoorHost;
import com.eurobuddha.dexicon.doors.DoorPlan;
import com.eurobuddha.dexicon.ui.Ui;

/**
 * AtomiX's Consolidate for the ACTIVE market's coin (apks/atomix MainActivity consolidateDialog / consolidateOnce): one
 * self-send merging up to 20 spendable coins into 2, under the engine's wallet lock (never while a swap is locking a
 * coin), Preview without signing, and a persisted 3-block wait so a lost reply never offers an immediate repeat. A maker
 * whose wallet holds more coins than AtomiX can read declines every buy — this is the cure.
 */
final class AtomixConsolidate {
    private AtomixConsolidate() {}

    /** The red warning AtomiX shows when the coin count blocks buys (null when it doesn't). */
    static String warning(TradingContext m, int coins) {
        return coins > MinimaHtlc.maxSafeCoinRows(m.tokenId) ? coins + " " + DoorPlan.coin(m) + " coins — AtomiX buys are being declined. Consolidate." : null;
    }

    static void dialog(MainActivity act) {
        DoorHost host = DoorHost.get(act);
        final String token = host.market().tokenId;
        host.minima().tokenBalance(b -> {
            if (act.isFinishing() || act.isDestroyed() || !token.equals(host.market().tokenId)) return;
            int cap = MinimaHtlc.maxSafeCoinRows(token);
            int runs = (int) Math.max(0L, ((long) b.coins - cap + 17L) / 18L);
            String label = DoorPlan.coin(host.market());
            act.track(new AlertDialog.Builder(act, Design.dialogTheme()).setTitle("Consolidate " + label)
                    .setMessage(b.coins + " wallet coins · read limit " + cap + ".\n\n"
                            + (runs > 0 ? "At least about " + runs + " runs to reach the limit. " : "Your wallet is within the read limit. ")
                            + "Each run asks the node to merge up to 20 spendable coins into 2, with at most 5 signing addresses. Locked coins cannot be merged.\n\n"
                            + "Run once signs one self-send on your node and consumes one-time signing keys. Preview does not sign. "
                            + "Wait for new coins to age 3 blocks before running again. No automatic repeats.")
                    .setNeutralButton("Preview", (d, w) -> once(act, token, true))
                    .setPositiveButton("Run once", (d, w) -> once(act, token, false))
                    .setNegativeButton("Cancel", null).show());
        }, e -> text(act, "Consolidate", e));
    }

    private static void once(MainActivity act, String token, boolean preview) {
        DoorHost host = DoorHost.get(act);
        if (!token.equals(host.market().tokenId)) { act.toast("Trading currency changed; reopen Consolidate"); return; }
        if (IdentityWatch.halted()) { act.toast("Halted: AtomiX's keys don't match your node"); return; }
        if (!SwapEngine.beginConsolidate()) { act.toast("Wallet busy — wait for the current lock or consolidation"); return; }
        final MinimaHtlc target = host.minima();
        java.util.function.Consumer<String> fail = e -> { SwapEngine.endConsolidate(); if (!act.isFinishing() && !act.isDestroyed()) text(act, "Consolidate", e); };
        target.tokenBalance(b -> {
            if (!token.equals(host.market().tokenId)) { fail.accept("Trading currency changed; reopen Consolidate"); return; }
            if (b.coins < 3) { fail.accept("At least 3 spendable coins are needed."); return; }
            target.hasPendingMinima(pending -> {
                if (pending) { fail.accept("Coins are still confirming. Wait for 3 confirmations before another run."); return; }
                target.currentBlock(new MinimaHtlc.BlockCb() {
                    public void err(String e) { fail.accept(e); }
                    public void ok(int block) {
                        if (!token.equals(host.market().tokenId)) { fail.accept("Trading currency changed; reopen Consolidate"); return; }
                        if (block <= 0) { fail.accept("Could not read the current block"); return; }
                        int waitUntil = host.prefs().getInt("consolidate_until_" + token, 0);
                        if (!preview && block < waitUntil) { fail.accept("Coins: " + b.coins + " · waiting for new coins to age 3 blocks. Retry at block " + waitUntil + "."); return; }
                        if (preview) {
                            target.previewConsolidate(20, r -> { SwapEngine.endConsolidate(); text(act, "Consolidation preview — no transaction sent", r.toString()); }, fail);
                            return;
                        }
                        host.prefs().edit().putInt("consolidate_until_" + token, block + 4).apply();   // before submission: a lost reply must not offer a repeat
                        target.consolidateCoins(20, new MinimaHtlc.PostCb() {
                            public void err(String e) { fail.accept("Submission could not be confirmed: " + e + "\nCheck your balance before retrying."); }
                            public void ok(String txpowid) {
                                SwapEngine.endConsolidate();
                                target.tokenBalance(after -> {
                                    if (!act.isFinishing() && !act.isDestroyed()) text(act, "Consolidation submitted", "Coins: " + b.coins + " → " + after.coins
                                            + " · waiting for new coins to age 3 blocks.\n\nTransaction: " + txpowid);
                                    act.repaint();
                                }, e -> text(act, "Consolidation submitted", "Refresh your balance after confirmation.\nTransaction: " + txpowid));
                            }
                        });
                    }
                });
            }, fail);
        }, fail);
    }

    private static void text(MainActivity act, String title, String body) {
        TextView t = Ui.text(act, body, 13f, Design.TEXT(), Design.sans());
        t.setTextIsSelectable(true);
        int p = Design.dp(act, 20); t.setPadding(p, Design.dp(act, 12), p, Design.dp(act, 4));
        android.widget.ScrollView sv = new android.widget.ScrollView(act); sv.addView(t);
        act.track(new AlertDialog.Builder(act, Design.dialogTheme()).setTitle(title).setView(sv).setPositiveButton("OK", null).show());
    }
}
