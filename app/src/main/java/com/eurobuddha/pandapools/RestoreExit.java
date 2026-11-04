// VENDORED VERBATIM from apks/pandapools 0.9.81 app/src/main/java/com/eurobuddha/pandapools/RestoreExit.java by scripts/vendor-pandapools.sh — do not edit here; re-vendor.
package com.eurobuddha.pandapools;

import android.content.Context;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Opt-in, bounded exit for matched restored keys. It never establishes prior signing history.
 * One close may be attempted per pool; confirmed payouts get one collection attempt. */
final class RestoreExit {

    /** Per run, so a restore of many pools cannot fire a long unattended burst of signing. */
    static final int MAX_PER_RUN = 8;

    interface Cb {
        void onProgress(String line);
        void onDone(int withdrawn, int skipped);
    }

    private final Context ctx;
    private final NodeApi node;
    private final PoolManager mgr;
    private int chainBlock;
    private final boolean existingWallet;

    RestoreExit(Context ctx, NodeApi node) { this(ctx, node, false); }
    RestoreExit(Context ctx, NodeApi node, boolean existingWallet) {
        this.ctx = ctx; this.node = node; this.mgr = new PoolManager(node); this.existingWallet = existingWallet;
    }

    /** Why a pool will not be withdrawn automatically, or null when it will be. */
    static String refusal(Context ctx, Pool p, Integer nodeUses, int capacity, int chainBlock) {
        return refusal(ctx, p, nodeUses, capacity, chainBlock, false);
    }
    static String refusal(Context ctx, Pool p, Integer nodeUses, int capacity, int chainBlock, boolean existingWallet) {
        if (p == null) return "no pool";
        if (!ExitStore.unattempted(ctx, p.address))
            return "a recovery withdrawal was already attempted. Check Activity and recovery status; it will not be signed again automatically.";
        if (!ExitStore.withinLifetimeCap(ctx, p.address))
            return "this pool has already used its automatic-withdrawal attempts. Withdraw it yourself from MY LP.";
        if (!OwnerKeyRecovery.baseSigningAllowed(p, nodeUses, capacity)) {
            if (nodeUses == null) return "this node could not read the owner key's signature counter.";
            if (nodeUses >= capacity) return "the owner key has spent all "
                    + String.format(java.util.Locale.US, "%,d", capacity) + " of its one-time signatures.";
            if (nodeUses < p.minimumOwnerUses)
                return "this node reports " + nodeUses + " signatures used but the recipe recorded "
                        + p.minimumOwnerUses + ". Signing would reuse one. Restore the newest matching wallet backup.";
            return "the owner key is unavailable on this node.";
        }
        if (p.minimumOwnerUses < 0)
            return "this backup recorded no signature count, so nothing can detect a counter that has gone "
                    + "backwards. PandaPools will not sign for it automatically.";
        // Recent swaps do not imply a lost wallet. This mode requires explicit preserved-state attestation;
        // known counter floors, missing counters, exhaustion, live reserves and attempt limits still apply.
        if (existingWallet) return null;
        int age = p.reserveAge(chainBlock);
        if (age <= 0)
            return "the age of this pool's reserves is unknown, so PandaPools cannot tell whether another device "
                    + "is still running this wallet.";
        if (age <= PoolRefresher.REFRESH_BLOCKS)
            return "these reserves were recreated " + age + " blocks ago, possibly by swaps or another device. "
                    + "Stop the other device before recovery. This bounded recovery requires older reserves.";
        return null;
    }

    /**
     * Withdraw each verified pool, in series. Pools past {@link #MAX_PER_RUN} keep their recipes and are reported,
     * never silently dropped.
     */
    void run(List<Pool> verified, int chainBlock, Cb cb) {
        this.chainBlock = chainBlock;
        List<Pool> queue = new ArrayList<>();
        if (verified != null) for (Pool p : verified) if (p != null && p.address != null) queue.add(p);
        if (queue.isEmpty()) { cb.onDone(0, 0); return; }
        if (queue.size() > MAX_PER_RUN) {
            cb.onProgress("Withdrawing the first " + MAX_PER_RUN + " of " + queue.size()
                    + " pools now; the rest keep their recipes and can be withdrawn from MY LP.");
            queue = new ArrayList<>(queue.subList(0, MAX_PER_RUN));
        }
        step(queue, 0, new int[]{0, 0}, cb);
    }

    private void step(List<Pool> queue, int i, int[] tally, Cb cb) {
        if (i >= queue.size()) { cb.onDone(tally[0], tally[1]); return; }
        Pool p = queue.get(i);
        // Read the counter fresh for THIS pool, immediately before deciding. A counter read minutes ago is not
        // evidence about the key now.
        node.cmd("keys action:list publickey:" + p.opk, new NodeApi.Cb() {
            @Override public void onResult(JSONObject reply) {
                Integer uses = KeyUses.extractUses(reply, p.opk);
                decide(queue, i, p, uses, KeyUses.capacityOf(reply, p.opk), tally, cb);
            }
            @Override public void onError(String error) {
                skip(queue, i, p, "the owner key could not be read: " + error, tally, cb);
            }
        });
    }

    private void decide(List<Pool> queue, int i, Pool p, Integer uses, int capacity, int[] tally, Cb cb) {
        String why = refusal(ctx, p, uses, capacity, currentBlock(), existingWallet);
        if (why != null) { skip(queue, i, p, why, tally, cb); return; }

        // Re-read the LIVE covenant coins before building, the same rule every other owner transaction follows:
        // a swap (or this node's own keep-fresh) can spend and recreate the reserves between the reserve
        // verification and here, and a close built on a stale snapshot dies at txncheck. Doing this before the
        // ticket is issued also means the ticket pins the coins that will actually be spent.
        PoolRefresher.readLiveReserves(node, p, fresh -> {
            if (!fresh) {
                skip(queue, i, p, "this pool's current reserves could not be read, so nothing was posted.", tally, cb);
                return;
            }
            // The pool may have moved under us, which changes what "withdraw everything" means. Re-check.
            String again = refusal(ctx, p, uses, capacity, currentBlock(), existingWallet);
            if (again != null) { skip(queue, i, p, again, tally, cb); return; }
            post(queue, i, p, uses, tally, cb);
        });
    }

    private void post(List<Pool> queue, int i, Pool p, Integer uses, int[] tally, Cb cb) {

        // Record the observed count BEFORE signing, so a crash between signing and posting still leaves the floor
        // at or above what was spent. Erring high costs one leaf and blocks future signing until a proper wallet
        // restore; erring low permits a reuse.
        if (!OwnPoolStore.raiseUseFloor(ctx, p.opk, uses)) {
            skip(queue, i, p, "could not record the owner key's signature count on this device.", tally, cb); return;
        }
        // Mark POSTING before the post, and treat a failed write as a refusal: without a durable marker a crash
        // mid-post would look untried on relaunch, and the retry would double-post against the same coins.
        ExitTicket ticket = new ExitTicket(p.opk, p.address, p.oadr, p.tok,
                p.coinidM, p.coinidT, PoolManager.amt(p.reserveM), PoolManager.amt(p.reserveT), ExitTicket.Stage.CLOSE);
        if (!ExitAuthority.tryGrant(ticket)) {
            skip(queue, i, p, "another recovery transaction is in progress; try again after it finishes.", tally, cb); return;
        }
        if (!ExitStore.beginClose(ctx, p)) {
            ExitAuthority.revoke(ticket);
            skip(queue, i, p, "could not record the withdrawal on this device, so nothing was posted.", tally, cb); return;
        }

        cb.onProgress("Withdrawing " + p.address + " …");
        mgr.close(p, new PoolManager.Result() {
            @Override public void onPosted(String txpowid) {
                ExitAuthority.revoke(ticket);
                ExitStore.set(ctx, p.address, p.opk, ExitStore.State.POSTED, "", txpowid, 0);
                LpStore.remove(ctx, p.address);
                ActivityLog.record(ctx, ActivityLog.CLOSE,
                        "Automatic withdrawal after restore  ·  " + p.address, txpowid, currentBlock());
                tally[0]++;
                cb.onProgress("Withdrawal submitted: " + txpowid + ". Collection waits for chain confirmation.");
                // PoolManager.close journals the exact withdrawal before broadcasting. The durable sweeper
                // waits for confirmation and then uses a fresh, single-use collection ticket.
                step(queue, i + 1, tally, cb);
            }
            @Override public void onFailed(String message) {
                ExitAuthority.revoke(ticket);
                boolean uncertain = message != null && message.contains(NodeApi.ERR_WRITE_UNCERTAIN);
                ExitStore.set(ctx, p.address, p.opk,
                        uncertain ? ExitStore.State.UNCERTAIN : ExitStore.State.FAILED, message, "", 0);
                tally[1]++;
                cb.onProgress((uncertain ? "Withdrawal status UNKNOWN for " : "Could not withdraw ")
                        + p.address + ": " + message);
                step(queue, i + 1, tally, cb);
            }
        });
    }

    private void skip(List<Pool> queue, int i, Pool p, String why, int[] tally, Cb cb) {
        if (ExitStore.unattempted(ctx, p.address))
            ExitStore.set(ctx, p.address, p.opk, ExitStore.State.SKIPPED, why, "", 0);
        tally[1]++;
        cb.onProgress("NOT withdrawn — " + p.address + ": " + why);
        step(queue, i + 1, tally, cb);
    }

    private int currentBlock() { return chainBlock; }
}
