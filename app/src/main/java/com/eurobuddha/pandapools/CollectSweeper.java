// VENDORED VERBATIM from apks/pandapools 0.9.81 app/src/main/java/com/eurobuddha/pandapools/CollectSweeper.java by scripts/vendor-pandapools.sh — do not edit here; re-vendor.
package com.eurobuddha.pandapools;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Finishes the job of getting withdrawn funds off the owner payout addresses, and keeps finishing it
 * until they are actually empty and every journalled withdrawal has fresh chain confirmation.
 *
 * The rule this enforces, which the old retry loop did not: **success is the address being empty, read
 * back from the node — not a forward transaction reporting that it posted.** One forward moves the coins
 * it could see at that moment; coins that were not yet spendable ({@code coinage:3}) are simply not in
 * it, and treating the post as completion is what left 2934.95626348 MxUSD stranded on 2026-09-14 while
 * the MINIMA leg went through. See {@link PendingCollect}.
 *
 * Driven from {@link PoolKeepAliveService}'s keep-alive pass, which already wakes every ~15 min under
 * Doze and survives the app being killed — so retries are effectively unlimited and cost no new
 * scheduling. No attempt cap: giving up is what caused the incident.
 *
 * It also distinguishes the case that actually needs a human. If funds are present but this wallet
 * cannot sign for the address — the owner key belongs to a wallet this node does not have, which is
 * exactly what a seed-only restore produces — then retrying forever in silence is useless. That is
 * recorded as {@link PendingCollect.Status#STRANDED} and surfaced in MY LP.
 */
final class CollectSweeper {

    interface Cb { void onDone(int cleared, int stillPending, int stranded); }

    /** What to do with one tracked payout address. */
    enum Verdict {
        /** The address is empty; the caller must still verify withdrawal evidence before clearing. */
        CLEAR,
        /** Keep the entry and come back next pass. */
        RETRY,
        /** Funds present, this wallet cannot sign. Retrying will never help; tell the user. */
        STRANDED,
    }

    /**
     * The whole fix, as a pure function.
     *
     * {@code remaining} is the coin count at the address (null = could not read); {@code canSign} is
     * whether this wallet can sign for it (null = could not tell). Note what is ABSENT: any input
     * describing whether a forward transaction posted. A forward moves the coins it could see at that
     * moment, so its success says nothing about whether the address is empty — and believing it did is
     * exactly what stranded 2934.95626348 MxUSD. CLEAR is reachable only from a zero read.
     */
    static Verdict decide(Integer remaining, Boolean canSign) {
        if (remaining == null || remaining < 0) return Verdict.RETRY;        // unknown: never conclude anything
        if (remaining == 0) return Verdict.CLEAR;
        if (canSign == null) return Verdict.RETRY;          // unknown is not "unreachable"
        return canSign ? Verdict.RETRY : Verdict.STRANDED;
    }

    private final Context ctx;
    private final NodeApi node;
    private final PoolManager mgr;

    CollectSweeper(Context ctx, NodeApi node) {
        this.ctx = ctx; this.node = node; this.mgr = new PoolManager(node);
    }

    /** Work every tracked payout address, in series. */
    void run(Cb cb) {
        List<PendingCollect.Entry> queue = PendingCollect.all(ctx);
        if (queue.isEmpty()) { cb.onDone(0, 0, 0); return; }
        new CollectConfirmation(ctx, node).scan(() -> step(PendingCollect.all(ctx), 0, new int[]{0, 0, 0}, cb));
    }

    private void step(List<PendingCollect.Entry> queue, int i, int[] tally, Cb cb) {
        if (i >= queue.size()) { cb.onDone(tally[0], tally[1], tally[2]); return; }
        final PendingCollect.Entry e = queue.get(i);
        final String oadr = e.oadr;

        // An empty address may mean its withdrawal has not arrived yet.
        readRemaining(oadr, remaining -> {
            if (decide(remaining, null) == Verdict.CLEAR) {
                new CollectConfirmation(ctx, node).confirmed(e, confirmed -> {
                    if (!confirmed) { pendingEmpty(e, queue, i, tally, cb); return; }
                    // Re-read after confirmation; never clear from an earlier balance snapshot.
                    readRemaining(oadr, latest -> {
                        if (latest != null && latest == 0 && PendingCollect.clear(ctx, e)) {
                            Pool recovered = ExitStore.recoveryPool(ctx, oadr);
                            if (recovered != null) ExitStore.set(ctx, recovered.address, recovered.opk,
                                    ExitStore.State.DONE, "Confirmed collection; payout address empty.", "", 0);
                            tally[0]++;
                            step(queue, i + 1, tally, cb);
                        } else pendingEmpty(e, queue, i, tally, cb);
                    });
                });
                return;
            }
            if (remaining == null) {                       // could not read; leave it and try next pass
                PendingCollect.attempted(ctx, oadr, PendingCollect.Status.RETRYING,
                        "could not read the payout address on this node");
                tally[1]++;
                step(queue, i + 1, tally, cb);
                return;
            }
            // Funds are there. Can this wallet sign for the address at all?
            canSign(oadr, signable -> {
                Verdict v = decide(remaining, signable);
                if (v == Verdict.RETRY && signable == null) {
                    // The node could not tell us. UNKNOWN is not the same as unreachable — declaring funds
                    // stranded on a transient read failure would raise a false alarm about real money.
                    PendingCollect.attempted(ctx, oadr, PendingCollect.Status.RETRYING,
                            "could not check whether this wallet can sign for the payout address");
                    tally[1]++;
                    step(queue, i + 1, tally, cb);
                    return;
                }
                if (v == Verdict.STRANDED) {
                    PendingCollect.attempted(ctx, oadr, PendingCollect.Status.STRANDED,
                            remaining + " coin(s) are at this address and this wallet cannot sign for it. "
                                    + "The owner key belongs to a wallet this node does not hold — a seed on its "
                                    + "own does not reproduce it. Restore the matching complete wallet backup.");
                    tally[2]++;
                    step(queue, i + 1, tally, cb);
                    return;
                }
                mgr.forwardOwnerFunds(oadr, new PoolManager.ForwardResult() {
                    @Override public void onForwarded(String txpowid, int coins) {
                        // Deliberately NOT treated as finished. The next pass re-reads the address; only an
                        // empty read clears the entry. This is the exact assumption that stranded real funds.
                        PendingCollect.attempted(ctx, oadr, PendingCollect.Status.RETRYING, "");
                        tally[1]++;
                        step(queue, i + 1, tally, cb);
                    }
                    @Override public void onNothing() {
                        // Coins present but none spendable yet (coinage:3 after the close). Perfectly normal;
                        // keep the entry and come back.
                        PendingCollect.attempted(ctx, oadr, PendingCollect.Status.RETRYING,
                                "waiting for the withdrawn coins to become spendable");
                        tally[1]++;
                        step(queue, i + 1, tally, cb);
                    }
                    @Override public void onFailed(String message) {
                        PendingCollect.attempted(ctx, oadr, PendingCollect.Status.RETRYING, message);
                        tally[1]++;
                        step(queue, i + 1, tally, cb);
                    }
                });
            });
        });
    }

    private void pendingEmpty(PendingCollect.Entry entry, List<PendingCollect.Entry> queue, int index,
                              int[] tally, Cb cb) {
        PendingCollect.attempted(ctx, entry.oadr, PendingCollect.Status.RETRYING,
                "Waiting for verified withdrawal confirmation and an empty payout address. Older entries without transaction evidence remain unresolved.");
        tally[1]++;
        step(queue, index + 1, tally, cb);
    }

    /** How many coins sit at {@code oadr} right now, or null when the node could not say. */
    private void readRemaining(String oadr, java.util.function.Consumer<Integer> cb) {
        node.cmd("balance address:" + oadr, new NodeApi.Cb() {
            @Override public void onResult(JSONObject reply) {
                cb.accept(CollectionEvidence.remaining(reply));
            }
            @Override public void onError(String m) { cb.accept(null); }
        });
    }

    /**
     * Whether this wallet can sign for the payout address. $OADR is the plain address of the owner key,
     * so the node calling it relevant is exactly the question — and it is the check that would have told
     * the user, immediately, that their funds were unreachable rather than merely delayed.
     */
    private void canSign(String oadr, java.util.function.Consumer<Boolean> cb) {
        node.cmd("checkaddress address:" + oadr, new NodeApi.Cb() {
            @Override public void onResult(JSONObject reply) {
                JSONObject r = reply == null ? null : reply.optJSONObject("response");
                if (!TxPost.truthy(reply, "status") || r == null || !r.has("relevant")) { cb.accept(null); return; }
                Object relevant = r.opt("relevant");
                cb.accept(relevant instanceof Boolean ? (Boolean) relevant : null);
            }
            // null = UNKNOWN, never false. A transient failure must not be reported to the user as
            // "your funds are unreachable".
            @Override public void onError(String m) { cb.accept(null); }
        });
    }

    /** Human-readable summary of what is still outstanding, for MY LP. Empty when nothing is. */
    static List<PendingCollect.Entry> outstanding(Context ctx) {
        return new ArrayList<>(PendingCollect.all(ctx));
    }
}
