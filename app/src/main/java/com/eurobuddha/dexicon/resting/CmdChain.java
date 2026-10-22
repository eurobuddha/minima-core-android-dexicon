package com.eurobuddha.dexicon.resting;
/* Vendored from apks/pandadex app/src/main/java/com/eurobuddha/pandadex/CmdChain.java (PandaDEX 0.4.23).
 * Keep as close to verbatim as possible; only the package, the NodeApi/SignGate imports and the minimal Dexicon
 * glue noted inline differ. Per the family naming rule the Minima dollar token is MxUSD in identifiers too
 * (PandaDEX MXUSD_ID/MXUSD_DP/*Mxusd are MXUSD_ID/MXUSD_DP/*Mxusd here). Re-copy rather than hand-edit when PandaDEX changes. */


import com.eurobuddha.dexicon.NodeApi;

import org.json.JSONObject;

import java.util.List;

/**
 * Runs a list of node commands sequentially over {@link NodeApi}, aborting on the first failure
 * (status:false or transport error). This is the native equivalent of the dapp's deeply-nested
 * MDS.cmd callbacks. On failure it fires an optional cleanup command (e.g. txndelete) so a
 * half-built transaction doesn't get stuck.
 */
public final class CmdChain {

    public interface Done {
        void ok(JSONObject last);
        void fail(String message);
    }

    private CmdChain() {}

    public static void run(NodeApi node, List<String> cmds, String cleanupOnFail, Done done) {
        run(node::cmd, cmds, cleanupOnFail, done);
    }

    static void run(FundingCoins.Command node, List<String> cmds, String cleanupOnFail, Done done) {
        step(node, cmds, 0, cleanupOnFail, done);
    }

    private static void step(FundingCoins.Command node, List<String> cmds, int i, String cleanup, Done done) {
        if (i >= cmds.size()) { done.ok(null); return; }
        final boolean last = (i == cmds.size() - 1);
        node.run(cmds.get(i), new NodeApi.Cb() {
            @Override public void onResult(JSONObject json) {
                if (!json.optBoolean("status", false)) {
                    fail(node, cleanup, done, shortCmd(cmds.get(i)) + " failed"
                            + errSuffix(json));
                    return;
                }
                if (last) { done.ok(json); return; }
                step(node, cmds, i + 1, cleanup, done);
            }
            @Override public void onError(String message) {
                fail(node, cleanup, done, message);
            }
        });
    }

    private static void fail(FundingCoins.Command node, String cleanup, Done done, String msg) {
        // Dexicon rule: every txn error path runs txndelete (PandaDEX skips it after an interrupted write).
        if (cleanup != null && !cleanup.isEmpty()) {
            node.run(cleanup, new NodeApi.Cb() {
                @Override public void onResult(JSONObject json) {}
                @Override public void onError(String message) {}
            });
        }
        done.fail(msg);
    }

    private static String errSuffix(JSONObject json) {
        String e = json.optString("error", "");
        return e.isEmpty() ? "" : " : " + e;
    }

    private static String shortCmd(String c) {
        int sp = c.indexOf(' ');
        return sp < 0 ? c : c.substring(0, sp);
    }
}
