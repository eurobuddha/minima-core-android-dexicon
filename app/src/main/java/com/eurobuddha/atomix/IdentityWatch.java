// VENDORED VERBATIM from apks/atomix 0.1.83 app/src/main/java/com/eurobuddha/atomix/IdentityWatch.java by scripts/vendor-atomix.sh — do not edit here; re-vendor.
package com.eurobuddha.atomix;

import android.content.SharedPreferences;
import android.os.Handler;

import com.eurobuddha.atomix.eth.EthWallet;
import com.eurobuddha.atomix.swap.MinimaHtlc;
import com.eurobuddha.atomix.swap.SwapEngine;
import com.eurobuddha.comms.NodeApi;

/**
 * Does this app's key material still belong to the node it is talking to?
 *
 * BOTH of AtomiX's identities are established once per PROCESS and were never re-checked:
 *   • the Minima swap identity is persisted (swap_addr/swap_pk) and only verified inside MinimaHtlc.setup(),
 *   • the ETH key is derived from the node seed (seedrandom modifier:ethbridge) but only when creds==null.
 * So a node reseed WHILE THE APP RUNS left both stale until something restarted the process — and the
 * foreground service is designed to live for days. That is exactly how a real user traded for ~10 hours on
 * an ETH wallet their node no longer owned, after the 0.1.17 incident had already cost 7.86 USDT the same
 * way. Detection therefore cannot live at startup only; it has to keep asking.
 *
 * There is deliberately NO self-heal here (user decision, 0.1.19). Silently re-picking an identity hides the
 * fact that funds are sitting on a key the node can no longer derive. Instead we HALT and make the user
 * rescue-then-reinstall, which is the one reset that provably puts every piece back in line.
 *
 * "Cannot verify" is never "mismatch": an empty key list (node busy/locked) or a failed seedrandom leaves the
 * verdict untouched, so a flaky node can never halt a healthy app.
 */
public final class IdentityWatch {

    private IdentityWatch() {}

    /** Re-check cadence while running. Both probes are cheap node reads, but they share the node's single
     *  command thread with settlement, so this stays well clear of the poll loop. */
    private static final long CHECK_INTERVAL_MS = 5 * 60 * 1000L;

    private static volatile boolean minimaMismatch, ethMismatch;
    private static volatile String orphanedPk = "", staleEth = "", nodeEth = "";
    private static volatile long lastCheckMs;
    private static volatile boolean minimaInFlight, ethInFlight, alarmRaised;

    /** True when either identity provably does not belong to the node → no new liabilities anywhere. */
    public static boolean halted() { return minimaMismatch || ethMismatch; }

    public static boolean minimaMismatch() { return minimaMismatch; }
    public static boolean ethMismatch() { return ethMismatch; }
    /** The published Minima key the node does not own (empty unless minimaMismatch). */
    public static String orphanedPk() { return orphanedPk; }
    /** The ETH address this app is USING (holds any funds), vs what the node's seed derives now. */
    public static String staleEth() { return staleEth; }
    public static String nodeEth() { return nodeEth; }

    /** Force the next check() to run now — used on a pairing transition, the likeliest reseed moment. */
    public static void forceNext() { lastCheckMs = 0; }

    /**
     * Run both probes if the throttle allows. Safe to call from every watcher tick in either host; the
     * verdict is process-static so the fg activity and the bg service always agree.
     */
    public static void check(NodeApi node, EthWallet wallet, MinimaHtlc minima,
                             SharedPreferences prefs, Handler ui, SwapEngine.Notifier notifier) {
        if (node == null || wallet == null || minima == null) return;
        long now = System.currentTimeMillis();
        if (now - lastCheckMs < CHECK_INTERVAL_MS) return;
        lastCheckMs = now;
        checkMinima(minima, prefs, notifier);
        checkEth(node, wallet, ui, notifier);
    }

    private static void checkMinima(final MinimaHtlc minima, final SharedPreferences prefs,
                                    final SwapEngine.Notifier notifier) {
        // Compare the key we actually PUBLISH (what counterparties lock to), falling back to the persisted
        // one before setup() has run.
        final String mine = minima.myPubkey() != null && !minima.myPubkey().isEmpty()
                ? minima.myPubkey()
                : (prefs == null ? "" : prefs.getString("swap_pk", ""));
        if (mine == null || mine.isEmpty() || minimaInFlight) return;
        minimaInFlight = true;
        minima.loadMyKeys(new MinimaHtlc.KeysCb() {
            @Override public void ok(java.util.Set<String> keys) {
                minimaInFlight = false;
                if (keys == null || keys.isEmpty()) return;             // cannot verify — never halt on this
                boolean owned = keys.contains(MinimaHtlc.normKey(mine));
                minimaMismatch = !owned;
                orphanedPk = owned ? "" : mine;
                raiseOrClear(notifier);
            }
            @Override public void err(String m) { minimaInFlight = false; }   // cannot verify
        });
    }

    private static void checkEth(NodeApi node, final EthWallet wallet, Handler ui,
                                 final SwapEngine.Notifier notifier) {
        final String have = wallet.address();
        if (have == null || wallet.isImported() || ethInFlight) return;   // an imported key is the user's own choice
        ethInFlight = true;
        wallet.probeNodeAddress(node, ui, new EthWallet.Cb() {
            @Override public void ok(String nodeAddr) {
                ethInFlight = false;
                if (nodeAddr == null || nodeAddr.isEmpty()) return;     // cannot verify
                boolean same = nodeAddr.equalsIgnoreCase(have);
                ethMismatch = !same;
                staleEth = same ? "" : have;
                nodeEth = same ? "" : nodeAddr;
                raiseOrClear(notifier);
            }
            @Override public void err(String m) { ethInFlight = false; }   // node busy / not write-enabled
        });
    }

    /** One alarm per transition into mismatch — not one per check, and it re-arms if the state clears. */
    private static void raiseOrClear(SwapEngine.Notifier notifier) {
        if (!halted()) { alarmRaised = false; return; }
        if (alarmRaised) return;
        alarmRaised = true;
        SwapLog.w("IDENTITY MISMATCH — halting new trades."
                + (minimaMismatch ? " Minima key not owned by node: " + orphanedPk + "." : "")
                + (ethMismatch ? " ETH wallet in use " + staleEth + " but node derives " + nodeEth + "." : ""));
        if (notifier != null) notifier.notify("Wallet mismatch — AtomiX halted",
                "Your node's seed no longer matches this app. Trading is stopped. Open AtomiX to rescue funds, "
                + "then reinstall AND clear app storage — a reinstall alone can restore the old keys from a "
                + "phone/cloud backup.");
    }

    /** Adopt a verdict discovered elsewhere: SwapEngine.setMyPubkeys already compares the published key
     *  against the node's live key set on every load, so it can find an orphan before our own cadence does. */
    public static void noteMinimaOrphaned(String pk, SwapEngine.Notifier notifier) {
        minimaMismatch = true;
        orphanedPk = pk == null ? "" : pk;
        raiseOrClear(notifier);
    }

    /** One-line summary for the foreground-service notification. */
    public static String fgLine() {
        if (!halted()) return "";
        if (minimaMismatch && ethMismatch) return "HALTED — Minima key + ETH wallet do not match the node";
        return minimaMismatch ? "HALTED — Minima key not owned by this node"
                              : "HALTED — ETH wallet does not match the node seed";
    }

    // ---- test seams (public only so tests in sibling packages can reach them; never called from app code) ----
    public static void resetForTest() {
        minimaMismatch = false; ethMismatch = false;
        orphanedPk = ""; staleEth = ""; nodeEth = "";
        lastCheckMs = 0; minimaInFlight = false; ethInFlight = false; alarmRaised = false;
    }
    public static void setMismatchForTest(boolean minima, boolean eth) { minimaMismatch = minima; ethMismatch = eth; }
}
