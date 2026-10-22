package com.eurobuddha.dexicon;

import android.app.AlertDialog;

import com.eurobuddha.dexicon.fill.Tokens;

import org.json.JSONObject;

import java.math.BigDecimal;

/**
 * ASSETS › CONSOLIDATE / SPLIT — the family's proven wallet tools, not new ones:
 *   consolidate = PandaPools WalletTools.showConsolidate/autoConsolidate (the UTXO wallet's): the node's standard
 *                 {@code consolidate tokenid:<token>} — the node picks the coins; no burn.
 *   split       = PandaDEX SelfSplit: a self-send of the whole sendable amount with {@code split:10}.
 * Both run inside Dexicon's SignGate (one signing at a time) and confirm first. The new coins are spendable once they
 * confirm (a block or so); balances refresh after.
 */
final class WalletTools {
    static final int SPLIT_COUNT = 10;   // PandaDEX SelfSplit.COUNT

    private WalletTools() {}

    static void consolidate(MainActivity act, String tokenid, String name, int coins) {
        if (!act.paired()) { act.toast("Pair with your node first"); return; }
        if (coins < 2) { message(act, "Nothing to consolidate", "Your " + name + " is already in " + coins + (coins == 1 ? " coin." : " coins.")); return; }
        new AlertDialog.Builder(act, Design.dialogTheme()).setTitle("Consolidate " + name)
                .setMessage("Combine your " + coins + " " + name + " wallet coins into fewer, larger ones?\n\n"
                        + "Your node picks the coins with its standard consolidation command. Nothing leaves your wallet and there is no fee.\n\n"
                        + "consolidate tokenid:" + tokenid)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Consolidate", (d, w) -> run(act, "consolidate tokenid:" + tokenid, "Consolidate " + name)).show();
    }

    static void split(MainActivity act, String tokenid, String name, BigDecimal sendable) {
        if (!act.paired()) { act.toast("Pair with your node first"); return; }
        String addr = act.receiveAddress();
        if (addr.isEmpty()) { act.toast("Reading your address — try again in a moment"); return; }
        if (sendable == null || sendable.signum() <= 0) { message(act, "Nothing to split", "No sendable " + name + " right now."); return; }
        String amt = sendable.stripTrailingZeros().toPlainString();
        String cmd = "send address:" + addr + " amount:" + amt + (Tokens.isMinima(tokenid) ? "" : " tokenid:" + tokenid) + " split:" + SPLIT_COUNT;
        new AlertDialog.Builder(act, Design.dialogTheme()).setTitle("Split " + name + " into " + SPLIT_COUNT + " coins")
                .setMessage("Send all " + amt + " sendable " + name + " to yourself as " + SPLIT_COUNT + " equal coins, so several orders and trades "
                        + "can each have their own coin.\n\nThe coins stay in your wallet; they are spendable again once the send confirms.\n\n" + cmd)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Split", (d, w) -> run(act, cmd, "Split " + name)).show();
    }

    private static void run(MainActivity act, String cmd, String label) {
        act.toast(label + " — signing…");
        SignGate.submit(release -> act.nodeApi().cmdR(cmd, new NodeApi.Cb() {
            public void onResult(JSONObject j) {
                release.free();
                JSONObject r = j.optJSONObject("response");
                String id = r == null ? "" : r.optString("txpowid", r.optJSONObject("txpow") == null ? "" : r.optJSONObject("txpow").optString("txpowid", ""));
                act.refreshBalances();
                message(act, label + " submitted", "It is final after the next block; ASSETS updates when it confirms."
                        + (id.isEmpty() ? "" : "\n\ntxpow " + id));
            }
            public void onError(String m) { release.free(); message(act, label + " failed", m); }
        }));
    }

    private static void message(MainActivity act, String title, String text) {
        if (act.isFinishing() || act.isDestroyed()) return;
        AlertDialog d = new AlertDialog.Builder(act, Design.dialogTheme()).setTitle(title).setMessage(text).setPositiveButton("OK", null).create();
        d.show();
        android.widget.TextView tv = d.findViewById(android.R.id.message);
        if (tv != null) tv.setTextIsSelectable(true);   // the full txpowid can be copied
    }
}
