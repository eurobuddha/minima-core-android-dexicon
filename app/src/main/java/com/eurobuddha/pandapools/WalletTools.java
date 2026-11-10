// VENDORED VERBATIM from apks/pandapools 0.10.9 app/src/main/java/com/eurobuddha/pandapools/WalletTools.java by scripts/vendor-pandapools.sh — do not edit here; re-vendor.
package com.eurobuddha.pandapools;

import org.json.JSONObject;
import java.math.BigDecimal;

/** Wallet tools reuse the node's standard consolidation, as in the UTXO wallet. */
final class WalletTools {
    private final MainActivity act;
    WalletTools(MainActivity act) { this.act = act; }

    void checkNode() {
        act.node().cmd("magic", new NodeApi.Cb() {
            public void onResult(JSONObject reply) {
                try {
                    if (!TxPost.truthy(reply, "status")) { checkLegacyNode(); return; }
                    String limit = reply.getJSONObject("response").getJSONObject("lastblock")
                            .getString("currentmaxtxpowsize");
                    long bytes = new BigDecimal(limit).longValueExact();
                    if (bytes <= 0) throw new IllegalArgumentException();
                    message("MinimaCore replied. Current chain transaction limit: " + bytes
                            + " bytes per TxPoW.\n\nThe node checks the final transaction, including proofs and signatures, "
                            + "before posting. This is separate from Android's reply-delivery limit. No transaction was sent.");
                } catch (Exception invalid) { message("The node replied, but its chain size limit could not be read."); }
            }
            public void onError(String error) { message(error); }
        });
    }

    private void checkLegacyNode() {
        act.node().cmd("block", new NodeApi.Cb() {
            public void onResult(JSONObject reply) {
                JSONObject r = reply == null ? null : reply.optJSONObject("response");
                if (!TxPost.truthy(reply, "status") || r == null) { message("Could not read the node's chain tip."); return; }
                message("MinimaCore is connected at block " + r.optString("block", "unknown")
                        + ". This node does not expose a separate transaction-size query. "
                        + "It checks the complete TxPoW against the live chain limit when posting. "
                        + "If rejected, PandaPools reports the node's actual size and limit. No transaction was sent.");
            }
            public void onError(String error) { message(error); }
        });
    }

    void showConsolidate(String token, String name) {
        if (!FundingCoins.hex(token)) { message("Invalid token ID."); return; }
        new androidx.appcompat.app.AlertDialog.Builder(act, Design.isDark() ? androidx.appcompat.R.style.Theme_AppCompat_Dialog_Alert : androidx.appcompat.R.style.Theme_AppCompat_Light_Dialog_Alert)
                .setTitle("Consolidate " + name)
                .setMessage("Combine your " + name + " wallet coins automatically?\n\n"
                        + "MinimaCore selects the coins using its standard consolidation command. No burn fee.\n\n"
                        + "consolidate tokenid:" + token)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Consolidate", (d, w) -> autoConsolidate(token, name)).show();
    }

    /** UTXO WalletTools.autoConsolidate, enclosed in PandaPools' shared signing gate. */
    private void autoConsolidate(String token, String name) {
        TxPost.Done done = TxPost.gated(new TxPost.Done() {
            public void ok(String id) {
                ActivityLog.record(act, ActivityLog.CONSOLIDATE, "Consolidate " + name + " coins", id, act.chainBlock());
                message("Consolidation submitted. Activity will show its on-chain confirmations.");
            }
            public void fail(String error) {
                ActivityLog.recordFailed(act, ActivityLog.CONSOLIDATE, "Consolidate " + name + " coins", error);
                message(error);
            }
        });
        TxPost.submit(() -> {
            OwnerKeyRecovery.checkConsolidation(act.node()::cmd, () -> OwnPoolStore.all(act), error -> {
                if (error != null) { done.fail(error); return; }
                act.node().cmd("consolidate tokenid:" + token, new NodeApi.Cb() {
                    public void onResult(JSONObject reply) {
                        if (!TxPost.truthy(reply, "status")) { done.fail(nodeError(reply)); return; }
                        String id = Util.extractTxpowid(reply, "");
                        ActivityLog.rememberSubmission(act, reply, id);
                        done.ok(id);
                    }
                    public void onError(String error) { done.fail(error); }
                });
            });
        });
    }
    /** Withdraw from the Trading Account to the node's OWN wallet (its real getaddress, read straight from the node).
     *  Mx addresses only: the checksum catches a pasted token / coin id, which a bare 0x string would burn to. */
    void showWithdraw(java.util.List<TokenBalance> balances) {
        java.util.List<TokenBalance> some = new java.util.ArrayList<>();
        for (TokenBalance b : balances) try { if (new java.math.BigDecimal(b.sendable).signum() > 0) some.add(b); } catch (Exception ignored) { }
        if (some.isEmpty()) { message("Nothing sendable to withdraw right now."); return; }
        String[] names = new String[some.size()];
        for (int i = 0; i < names.length; i++) names[i] = label(some.get(i)) + "  (" + some.get(i).sendable + " sendable)";
        new androidx.appcompat.app.AlertDialog.Builder(act, theme()).setTitle("Withdraw which token?")
                .setItems(names, (d, i) -> act.node().cmdDirect("getaddress", null, 0, new com.eurobuddha.account.Transport.Reply() {
                    public void onResult(JSONObject j) { JSONObject r = j.optJSONObject("response"); withdrawForm(some.get(i), r == null ? "" : r.optString("miniaddress", "")); }
                    public void onError(String m) { withdrawForm(some.get(i), ""); }
                })).setNegativeButton("Cancel", null).show();
    }

    private void withdrawForm(TokenBalance b, String nodeAddr) {
        if (act.isFinishing() || act.isDestroyed()) return;
        android.widget.LinearLayout box = new android.widget.LinearLayout(act); box.setOrientation(android.widget.LinearLayout.VERTICAL);
        int p = Math.round(20 * act.getResources().getDisplayMetrics().density); box.setPadding(p, p / 2, p, 0);
        android.widget.EditText amt = new android.widget.EditText(act);
        amt.setHint("Amount (max " + b.sendable + ")");
        amt.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        android.widget.EditText to = new android.widget.EditText(act);
        to.setHint("Minima address (Mx…)"); to.setText(nodeAddr); to.setTypeface(android.graphics.Typeface.MONOSPACE);
        box.addView(amt); box.addView(to);
        new androidx.appcompat.app.AlertDialog.Builder(act, theme()).setTitle("Withdraw " + b.name).setView(box)
                .setMessage(nodeAddr.isEmpty() ? null : "Pre-filled with your node's own wallet address.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Review", (d, w) -> {
                    java.math.BigDecimal a, max;
                    String typed = amt.getText().toString().trim();
                    if (!typed.matches("[0-9]+(\\.[0-9]+)?")) { message("Enter the amount as plain digits, e.g. 10 or 0.5."); return; }
                    try { a = new java.math.BigDecimal(typed); max = new java.math.BigDecimal(b.sendable); }
                    catch (Exception e) { message("Enter an amount."); return; }
                    String addr = to.getText().toString().trim();
                    if (a.signum() <= 0 || a.compareTo(max) > 0) { message("The amount must be above 0 and at most " + b.sendable + "."); return; }
                    if (!validMx(addr)) { message("That is not a valid Minima address (Mx…)."); return; }
                    String amount = a.stripTrailingZeros().toPlainString();
                    new androidx.appcompat.app.AlertDialog.Builder(act, theme()).setTitle("Withdraw " + amount + " " + label(b) + "?")
                            .setMessage("To:\n" + addr + "\n\nSigned by your Trading Account and posted through your node.")
                            .setNegativeButton("Cancel", null)
                            .setPositiveButton("Withdraw", (x, y) -> withdraw(b.tokenid, label(b), amount, addr)).show();
                }).show();
    }

    /** MINIMA by name; any other token by name AND its full token id (two tokens can share a name). */
    private static String label(TokenBalance b) { return b.isMinima() ? "MINIMA" : b.name + " (" + b.tokenid + ")"; }

    static boolean validMx(String a) {
        if (a == null || !a.startsWith("Mx")) return false;
        try { return org.minima.objects.Address.convertMinimaAddress(a).getLength() == 32; } catch (RuntimeException bad) { return false; }
    }

    private void withdraw(String token, String name, String amount, String addr) {
        String summary = "Withdraw " + amount + " " + name + " to " + addr;
        TxPost.Done done = TxPost.gated(new TxPost.Done() {
            public void ok(String id) { ActivityLog.record(act, ActivityLog.WITHDRAW, summary, id, act.chainBlock()); message("Withdrawal submitted. Activity shows its on-chain confirmations."); }
            public void fail(String error) { ActivityLog.recordFailed(act, ActivityLog.WITHDRAW, summary, error); message(error); }
        });
        TxPost.submit(() -> OwnerKeyRecovery.checkConsolidation(act.node()::cmd, () -> OwnPoolStore.all(act), error -> {
            // a send picks coins like consolidate does: never a pool owner coin it must not sign (the same guard)
            if (error != null) { done.fail(error); return; }
            act.node().cmd("send address:" + addr + " amount:" + amount + (Util.MINIMA_TOKENID.equals(token) ? "" : " tokenid:" + token), new NodeApi.Cb() {
                public void onResult(JSONObject reply) {
                    if (!TxPost.truthy(reply, "status")) { done.fail(nodeError(reply)); return; }
                    String id = Util.extractTxpowid(reply, "");
                    ActivityLog.rememberSubmission(act, reply, id);
                    done.ok(id);
                }
                public void onError(String error) { done.fail(error); }
            });
        }));
    }

    private int theme() { return Design.isDark() ? androidx.appcompat.R.style.Theme_AppCompat_Dialog_Alert : androidx.appcompat.R.style.Theme_AppCompat_Light_Dialog_Alert; }

    void resolveInterruptedWrite() {
        if (!act.node().hasInterruptedWrite()) { message("There is no interrupted write to resolve."); return; }
        final String reviewed = act.node().interruptedWriteId();
        new androidx.appcompat.app.AlertDialog.Builder(act, Design.isDark() ? androidx.appcompat.R.style.Theme_AppCompat_Dialog_Alert : androidx.appcompat.R.style.Theme_AppCompat_Light_Dialog_Alert).setTitle("Resolve interrupted write")
                .setMessage("A node write lost its reply. It may have completed. First restart MinimaCore to stop any old command, "
                        + "then check your transactions and balances. Clearing this safety pause does not undo or retry any transaction.")
                .setNegativeButton("Keep paused", null)
                .setPositiveButton("I restarted and checked", (d, w) -> {
                    if (!act.node().acknowledgeInterruptedWrite(reviewed)) { message("A write is still active or the safety state could not be saved. Keep paused."); return; }
                    // review 7 Nov (R2-3): free inputs an uncertain post held, where the node proves they were never posted. Load
                    // the shared store first: it also holds a host's resting-order claims (Dexicon), not only this process's
                    CoinLock.loadPosted(act);
                    CoinLock.releaseProvablyUnposted(act.node()::cmd, n -> act.runOnUiThread(() -> message("Safety pause cleared. "
                            + (n > 0 ? n + " coin" + (n == 1 ? " that was" : "s that were") + " held by the interrupted transaction (still unspent, not in the mempool) can be used again. " : "")
                            + "Review your balances before starting a new transaction.")));
                }).show();
    }
    private static String nodeError(JSONObject j) { return j.optString("error", j.optString("message", "No coins could be combined.")); }
    private void message(String text) {
        if (!act.isFinishing() && !act.isDestroyed()) new androidx.appcompat.app.AlertDialog.Builder(act, Design.isDark() ? androidx.appcompat.R.style.Theme_AppCompat_Dialog_Alert : androidx.appcompat.R.style.Theme_AppCompat_Light_Dialog_Alert).setTitle("Wallet").setMessage(text).setPositiveButton("OK", null).show();
    }
}
