// VENDORED VERBATIM from apks/pandapools 0.10.0 app/src/main/java/com/eurobuddha/pandapools/ClosedPoolCheck.java by scripts/vendor-pandapools.sh — do not edit here; re-vendor.
package com.eurobuddha.pandapools;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;

/**
 * Is a closed ("retired") pool really empty? A close or migrate can be posted and never land, so a retired recipe may
 * still guard a live pool. Each one must read EMPTY on the node (after a re-track: a resynced node lists only coins of
 * scripts it tracks) AND on the unpruned MegaMMR. A pool that passes both is marked {@link Pool#drained}; anything
 * else is reported to {@link Failed} with the full pool address. Used by the hand-over and by the Trading Account
 * offer (a node-key recipe holds the offer back until it is proven empty) — here and in Dexicon (vendored).
 */
final class ClosedPoolCheck {
    private ClosedPoolCheck() {}

    static final String LABEL = "Closed pool is empty";
    static final String HOLDS_FUNDS = "A closed pool still holds funds. Withdraw it on the My Pools tab first.";

    /** One pool NOT proven empty: what to show, why, and "Pool: <full address>". */
    interface Failed { void add(String label, String reason, String detail); }

    /** Checks {@code retired} one by one, reporting each pool not proven empty; then {@code done}. */
    static void run(Context c, NodeApi node, List<Pool> retired, Failed checks, Runnable done) {
        step(c.getApplicationContext(), node, retired, 0, checks, done);
    }

    private static void step(Context c, NodeApi node, List<Pool> retired, int i, Failed checks, Runnable done) {
        if (i == retired.size() || node == null) {
            if (i < retired.size()) checks.add(LABEL, unverified("screen closed"), "");
            done.run(); return;
        }
        Pool p = retired.get(i);
        String detail = "Pool: " + p.address;
        Runnable next = () -> step(c, node, retired, i + 1, checks, done);
        if (p.covenantScript == null || p.covenantScript.isEmpty()) {
            checks.add(LABEL, unverified("no covenant script to re-track"), detail);
            next.run(); return;
        }
        PoolManager.retrack(node, p, new NodeApi.Cb() {
            @Override public void onResult(JSONObject t) {
                if (!TxPost.truthy(t, "status")) {
                    checks.add(LABEL, unverified("the node refused to re-track it"), detail);
                    next.run(); return;
                }
                // No coin-id hints: a pool with more than SAFE_COINS coins must read as "unverified", never as empty.
                ReserveRecovery.readCoins(node::cmd, p.address, false, FundingCoins.SAFE_COINS, null, null, new NodeApi.Cb() {
                    @Override public void onResult(JSONObject j) {
                        JSONArray rows = FundingCoins.rows(j);
                        if (rows == null) { checks.add(LABEL, unverified("unreadable reply"), detail); next.run(); }
                        else if (rows.length() > 0) { checks.add(LABEL, HOLDS_FUNDS, detail); next.run(); }
                        else megaEmpty(c, p, detail, checks, next);   // empty here: the unpruned MegaMMR must agree
                    }
                    @Override public void onError(String m) {
                        checks.add(LABEL, unverified(m), detail);
                        next.run();
                    }
                });
            }
            @Override public void onError(String m) {
                checks.add(LABEL, unverified("re-track failed: " + m), detail);
                next.run();
            }
        });
    }

    /**
     * Second opinion on a closed pool the local node reads empty: the unpruned MegaMMR (eurobuddha.com:9001,
     * the only unpruned one), through ReserveRecovery's existing mega path. A failed read or any unspent coin
     * there = "couldn't confirm" (blocks). Both empty = the recipe is marked drained.
     */
    private static void megaEmpty(Context c, Pool p, String detail, Failed checks, Runnable next) {
        ReserveRecovery.readCoins(ArchiveNode.configured(c), p.address, true, 512, null, null, new NodeApi.Cb() {
            @Override public void onResult(JSONObject j) {
                JSONArray rows = FundingCoins.rows(j);
                int unspent = 0;
                if (rows != null) for (int k = 0; k < rows.length(); k++) {
                    JSONObject coin = rows.optJSONObject(k);
                    if (coin == null || !coin.optBoolean("spent", false)) unspent++;
                }
                if (rows == null) checks.add(LABEL, unverifiedMega("unreadable reply"), detail);
                else if (unspent > 0) checks.add(LABEL,
                        unverifiedMega("it lists " + unspent + " coin(s) there while your node reads none"), detail);
                else OwnPoolStore.setDrained(c, p.address);
                next.run();
            }
            @Override public void onError(String m) {
                checks.add(LABEL, unverifiedMega(m), detail);
                next.run();
            }
        });
    }

    private static String unverifiedMega(String why) {
        return "Couldn't confirm on the unpruned MegaMMR (" + MegaArchive.ENDPOINT + ") that a closed pool is empty ("
                + why + "). Check your internet connection, then try again.";
    }

    private static String unverified(String why) {
        return "Couldn't confirm on your node that a closed pool is empty (" + why + "). Make sure Minima Core is "
                + "running and PandaPools is enabled in it, then try again.";
    }
}
