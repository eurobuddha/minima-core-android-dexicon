// VENDORED VERBATIM from apks/atomix 0.2.35 app/src/main/java/com/eurobuddha/atomix/IdentityWatch.java by scripts/vendor-atomix.sh — do not edit here; re-vendor.
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
 * "Cannot verify" is never "mismatch": an empty or INCOMPLETE key list (fewer keys than the node's own total, or
 * an element that did not parse) or a failed seedrandom leaves the verdict untouched, so a flaky node can never
 * halt a healthy app. And no halt rests on one read: a "not owned" / "different ETH address" verdict is re-read
 * once at once, and only a second identical verdict halts. Clearing needs one good read.
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
    /** Log-only evidence for the halt line (never a private key or seedrandom output). */
    private static volatile String lastPk = "", lastEthHave = "", lastEthNode = "";
    private static volatile int lastKeysRead = -1;
    private static volatile long lastKeysTotal = -1;

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
        readMinima(minima, mine, notifier, false);
    }

    /** One {@code keys} read. Not owned on a COMPLETE read → exactly one immediate re-read ({@code confirming});
     *  only a second complete read that also lacks the key halts. */
    private static void readMinima(final MinimaHtlc minima, final String mine,
                                   final SwapEngine.Notifier notifier, final boolean confirming) {
        lastPk = mine;
        minima.loadMyKeys(new MinimaHtlc.KeysCb() {
            @Override public void ok(java.util.Set<String> keys) { read(keys, false, -1, -1); }   // no completeness verdict
            @Override public void read(java.util.Set<String> keys, boolean complete, int count, long total) {
                if (keys == null || keys.isEmpty() || !complete) { minimaInFlight = false; return; }   // cannot verify
                lastKeysRead = count; lastKeysTotal = total;
                boolean owned = keys.contains(MinimaHtlc.normKey(mine));
                if (!owned && !confirming) { readMinima(minima, mine, notifier, true); return; }
                minimaInFlight = false;
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
        lastEthHave = have;
        probeEth(node, wallet, have, ui, notifier, null);
    }

    /** One derivation. A different address → exactly one immediate re-derivation ({@code first} = the first
     *  answer); only the SAME different address twice halts. Two different answers = cannot verify. */
    private static void probeEth(final NodeApi node, final EthWallet wallet, final String have, final Handler ui,
                                 final SwapEngine.Notifier notifier, final String first) {
        wallet.probeNodeAddress(node, ui, new EthWallet.Cb() {
            @Override public void ok(String nodeAddr) {
                if (nodeAddr == null || nodeAddr.isEmpty()) { ethInFlight = false; return; }   // cannot verify
                lastEthNode = nodeAddr;
                boolean same = nodeAddr.equalsIgnoreCase(have);
                if (!same && first == null) { probeEth(node, wallet, have, ui, notifier, nodeAddr); return; }
                ethInFlight = false;
                if (!same && !nodeAddr.equalsIgnoreCase(first)) return;  // two different answers: cannot verify
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
                + (ethMismatch ? " ETH wallet in use " + staleEth + " but node derives " + nodeEth + "." : "")
                + " Evidence: published Minima key " + lastPk + ", node keys read " + lastKeysRead
                + " of node total " + lastKeysTotal + "; ETH wallet in use " + lastEthHave
                + ", node derives " + lastEthNode + ".");
        if (notifier != null) notifier.notify("Wallet mismatch — AtomiX halted",
                "Your node's seed no longer matches this app. Trading is stopped. Open AtomiX to rescue funds, "
                + "then reinstall AND clear app storage — a reinstall alone can restore the old keys from a "
                + "phone/cloud backup.");
    }

    /** A suspicion from elsewhere: SwapEngine.setMyPubkeys compares the published key against a key list it
     *  cannot prove complete. Never halt on that — run the same complete-and-confirmed reads as the cadence. */
    public static void noteMinimaOrphaned(MinimaHtlc minima, String pk, SwapEngine.Notifier notifier) {
        if (minima == null || pk == null || pk.isEmpty() || minimaInFlight) return;
        minimaInFlight = true;
        readMinima(minima, pk, notifier, false);
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
        lastPk = ""; lastEthHave = ""; lastEthNode = ""; lastKeysRead = -1; lastKeysTotal = -1;
    }
    public static void setMismatchForTest(boolean minima, boolean eth) { minimaMismatch = minima; ethMismatch = eth; }
}
