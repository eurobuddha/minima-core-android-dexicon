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
                .setPositiveButton("Consolidate", (d, w) -> com.eurobuddha.pandapools.DexiconPools.guardNodePickedSpend(act.nodeApi(), tokenid, error -> {
                    // the one node-picked-spend guard: never let the node pick a pool owner coin it must not sign
                    if (error != null) message(act, "Nothing consolidated", error);
                    else run(act, tokenid, "consolidate tokenid:" + tokenid, "Consolidate " + name, walletRow("consolidate", "", name, ""));
                })).show();
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
                .setPositiveButton("Split", (d, w) -> com.eurobuddha.pandapools.DexiconPools.guardNodePickedSpend(act.nodeApi(), tokenid, error -> {
                    // a self-send lets the node pick the coins too: the same guard applies
                    if (error != null) message(act, "Nothing split", error);
                    else run(act, tokenid, cmd, "Split " + name, walletRow("split", amt, name, ""));
                })).show();
    }

    /** DEPOSIT: the Trading Account's address, in full, copyable — fund it from any Minima wallet (Minima Core › Send). */
    static void deposit(MainActivity act, String name) {
        String addr = act.receiveAddress();
        if (addr.isEmpty()) { act.toast("Reading your address — try again in a moment"); return; }
        AlertDialog d = new AlertDialog.Builder(act, Design.dialogTheme()).setTitle("Deposit " + name)
                .setMessage("Send " + name + " to your Trading Account from any Minima wallet (Minima Core › Send):\n\n" + addr
                        + "\n\nIt is available to trade after 3 confirmations.")
                .setPositiveButton("Copy address", (x, w) -> act.copy("Trading Account address", addr))
                .setNegativeButton("Close", null).create();
        d.show();
        android.widget.TextView tv = d.findViewById(android.R.id.message);
        if (tv != null) tv.setTextIsSelectable(true);
    }

    /** WITHDRAW: send from the Trading Account to any address — by default back to the node's OWN wallet (its real
     *  getaddress, straight from the node). Signed by the account; the address is shown in full before anything moves. */
    static void withdraw(MainActivity act, String tokenid, String name, BigDecimal sendable) {
        if (!act.paired()) { act.toast("Pair with your node first"); return; }
        if (sendable == null || sendable.signum() <= 0) { message(act, "Nothing to withdraw", "No sendable " + name + " right now."); return; }
        act.nodeApi().cmdDirect("getaddress", null, 0, new NodeApi.Cb() {
            public void onResult(JSONObject j) { JSONObject r = j.optJSONObject("response"); form(r == null ? "" : r.optString("miniaddress", "")); }
            public void onError(String m) { form(""); }
            private void form(String nodeAddr) {
                if (act.isFinishing() || act.isDestroyed()) return;
                android.widget.LinearLayout box = new android.widget.LinearLayout(act); box.setOrientation(android.widget.LinearLayout.VERTICAL);
                int p = Design.dp(act, 20); box.setPadding(p, Design.dp(act, 8), p, 0);
                android.widget.EditText amt = new android.widget.EditText(act);
                amt.setHint("Amount (max " + sendable.stripTrailingZeros().toPlainString() + ")"); amt.setTextColor(Design.TEXT());
                amt.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
                android.widget.EditText to = new android.widget.EditText(act);
                to.setHint("Minima address (Mx…)"); to.setText(nodeAddr); to.setTextColor(Design.TEXT()); to.setTypeface(Design.mono());
                box.addView(amt); box.addView(to);
                android.widget.TextView note = new android.widget.TextView(act);
                note.setText(nodeAddr.isEmpty() ? "" : "Pre-filled with your node's own wallet address."); note.setTextColor(Design.DIM());
                box.addView(note);
                new AlertDialog.Builder(act, Design.dialogTheme()).setTitle("Withdraw " + name).setView(box)
                        .setNegativeButton("Cancel", null)
                        .setPositiveButton("Review", (d, w) -> {
                            BigDecimal a; try { a = new BigDecimal(amt.getText().toString().trim()); } catch (Exception e) { act.toast("Enter an amount"); return; }
                            String addr = to.getText().toString().trim();
                            if (a.signum() <= 0 || a.compareTo(sendable) > 0) { act.toast("Amount must be between 0 and " + sendable.stripTrailingZeros().toPlainString()); return; }
                            // Mx only: its checksum catches a pasted token/coin/txpow id, which a bare 0x string would burn to
                            if (!validMx(addr)) { act.toast("That is not a valid Minima address (Mx…)"); return; }
                            String cmd = "send address:" + addr + " amount:" + a.stripTrailingZeros().toPlainString() + (Tokens.isMinima(tokenid) ? "" : " tokenid:" + tokenid);
                            AlertDialog c = new AlertDialog.Builder(act, Design.dialogTheme()).setTitle("Withdraw " + a.stripTrailingZeros().toPlainString() + " " + name + "?")
                                    .setMessage("To:\n" + addr + "\n\nSigned by your Trading Account and posted through your node.")
                                    .setNegativeButton("Cancel", null)
                                    .setPositiveButton("Withdraw", (x, y) -> com.eurobuddha.pandapools.DexiconPools.guardNodePickedSpend(act.nodeApi(), tokenid, error -> {
                                        // send picks the coins: never a pool owner coin it must not sign (same guard as Split)
                                        if (error != null) message(act, "Nothing withdrawn", error);
                                        else run(act, tokenid, cmd, "Withdraw " + name, walletRow("withdraw", a.stripTrailingZeros().toPlainString(), name, addr));
                                    })).create();
                            c.show();
                            android.widget.TextView tv = c.findViewById(android.R.id.message);
                            if (tv != null) tv.setTextIsSelectable(true);
                        }).show();
            }
        });
    }

    static boolean validMx(String a) {
        if (a == null || !a.startsWith("Mx")) return false;
        try { return org.minima.objects.Address.convertMinimaAddress(a).getLength() == 32; } catch (RuntimeException bad) { return false; }
    }

    /** An ACTIVITY row for a wallet action (kind withdraw / consolidate / split); filled in when the post returns. */
    static JSONObject walletRow(String kind, String amount, String token, String to) {
        JSONObject r = new JSONObject();
        try { r.put("kind", kind).put("amount", amount).put("token", token).put("to", to); } catch (Exception ignored) { }
        return r;
    }

    private static void run(MainActivity act, String tokenid, String cmd, String label, JSONObject row) {
        act.toast(label + " — signing…");
        SignGate.submit(release -> {
            // AtomiX's wallet lock only now, at the front of the signing queue: never held while waiting behind other signers
            Runnable unlock = WalletLock.take(tokenid);   // the node picks the coins: never one an AtomiX swap is locking
            if (unlock == null) { release.free(); message(act, "Nothing sent", WalletLock.BUSY); return; }
            act.nodeApi().cmdR(cmd, new NodeApi.Cb() {
            public void onResult(JSONObject j) {
                release.free(); unlock.run();
                JSONObject r = j.optJSONObject("response");
                String id = r == null ? "" : r.optString("txpowid", r.optJSONObject("txpow") == null ? "" : r.optJSONObject("txpow").optString("txpowid", ""));
                if (row != null && j.optBoolean("status", false)) {   // the wallet history row (ACTIVITY), tracked to its block like a trade
                    try {
                        long now = System.currentTimeMillis();
                        JSONObject ids = ActivityLog.fromPostReply(j, act.receiveHex());   // our change first: we can always see it
                        ActivityLog.addWallet(act.db(), row.put("id", ActivityLog.newId("wal_")).put("ts", now).put("postedMs", now)
                                .put("stage", ActivityLog.POSTED).put("txpowid", id).put("txnid", ids.optString("txnid"))
                                .put("outs", ids.optJSONArray("outs")).put("ins", ids.optJSONArray("ins")).put("tokenid", tokenid));
                    } catch (Exception ignored) { }
                }
                act.refreshBalances();
                message(act, label + " submitted", "It is final after the next block; ASSETS updates when it confirms."
                        + (id.isEmpty() ? "" : "\n\ntxpow " + id));
            }
            public void onError(String m) {
                release.free(); unlock.run();
                if (row != null && m != null && m.startsWith("UNCERTAIN")) {
                    // it may have landed: keep a row, tracked by the coins it creates (named in full in the message)
                    try {
                        long now = System.currentTimeMillis();
                        org.json.JSONArray outs = new org.json.JSONArray();
                        int at = m.indexOf(" creates coins ");
                        if (at > 0) for (java.util.regex.Matcher x = java.util.regex.Pattern.compile("0x[0-9A-Fa-f]{64}").matcher(m.substring(at)); x.find(); ) outs.put(x.group());
                        org.json.JSONArray ins = new org.json.JSONArray();
                        int sp = m.indexOf(" spends coins ");
                        if (sp > 0) {
                            org.json.JSONArray only = new org.json.JSONArray();   // "creates" stops where "spends" begins
                            for (int k = 0; k < outs.length(); k++) if (m.indexOf(outs.getString(k)) < sp) only.put(outs.getString(k));
                            outs = only;
                            for (java.util.regex.Matcher x = java.util.regex.Pattern.compile("0x[0-9A-Fa-f]{64}").matcher(m.substring(sp)); x.find(); ) ins.put(x.group());
                        }
                        java.util.regex.Matcher t = java.util.regex.Pattern.compile("Transaction (0x[0-9A-Fa-f]{64})").matcher(m);
                        ActivityLog.addWallet(act.db(), row.put("id", ActivityLog.newId("wal_")).put("ts", now).put("postedMs", now)
                                .put("stage", ActivityLog.POSTED).put("txnid", t.find() ? t.group(1) : "").put("outs", outs).put("ins", ins).put("uncertain", true)
                                .put("tokenid", tokenid).put("reason", "the node did not confirm it — outcome unknown"));
                        act.refreshBalances();
                    } catch (Exception ignored) { }
                    message(act, label + " — outcome unknown", m);
                    return;
                }
                message(act, label + " failed", m);
            }
            });
        });
    }

    private static void message(MainActivity act, String title, String text) {
        if (act.isFinishing() || act.isDestroyed()) return;
        AlertDialog d = new AlertDialog.Builder(act, Design.dialogTheme()).setTitle(title).setMessage(text).setPositiveButton("OK", null).create();
        d.show();
        android.widget.TextView tv = d.findViewById(android.R.id.message);
        if (tv != null) tv.setTextIsSelectable(true);   // the full txpowid can be copied
    }
}
