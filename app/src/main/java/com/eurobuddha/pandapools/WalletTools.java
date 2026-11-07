// VENDORED VERBATIM from apks/pandapools 0.9.83 app/src/main/java/com/eurobuddha/pandapools/WalletTools.java by scripts/vendor-pandapools.sh — do not edit here; re-vendor.
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
    void resolveInterruptedWrite() {
        if (!act.node().hasInterruptedWrite()) { message("There is no interrupted write to resolve."); return; }
        final String reviewed = act.node().interruptedWriteId();
        new androidx.appcompat.app.AlertDialog.Builder(act, Design.isDark() ? androidx.appcompat.R.style.Theme_AppCompat_Dialog_Alert : androidx.appcompat.R.style.Theme_AppCompat_Light_Dialog_Alert).setTitle("Resolve interrupted write")
                .setMessage("A node write lost its reply. It may have completed. First restart MinimaCore to stop any old command, "
                        + "then check your transactions and balances. Clearing this safety pause does not undo or retry any transaction.")
                .setNegativeButton("Keep paused", null)
                .setPositiveButton("I restarted and checked", (d, w) -> { message(act.node().acknowledgeInterruptedWrite(reviewed) ? "Safety pause cleared. Review your balances before starting a new transaction." : "A write is still active or the safety state could not be saved. Keep paused."); }).show();
    }
    private static String nodeError(JSONObject j) { return j.optString("error", j.optString("message", "No coins could be combined.")); }
    private void message(String text) {
        if (!act.isFinishing() && !act.isDestroyed()) new androidx.appcompat.app.AlertDialog.Builder(act, Design.isDark() ? androidx.appcompat.R.style.Theme_AppCompat_Dialog_Alert : androidx.appcompat.R.style.Theme_AppCompat_Light_Dialog_Alert).setTitle("Wallet").setMessage(text).setPositiveButton("OK", null).show();
    }
}
