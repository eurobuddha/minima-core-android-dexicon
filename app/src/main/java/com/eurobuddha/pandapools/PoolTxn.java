// VENDORED VERBATIM from apks/pandapools 0.9.83 app/src/main/java/com/eurobuddha/pandapools/PoolTxn.java by scripts/vendor-pandapools.sh — do not edit here; re-vendor.
package com.eurobuddha.pandapools;

import org.json.JSONArray;
import org.json.JSONObject;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Builds, signs and posts a constant-product swap against a single {@link Pool}, mirroring the proven
 * mainnet {@code phaseC_swap.py} command-for-command so on-chain behaviour is identical.
 *
 * The pool is a PAIR of coins at the same covenant address: the MINIMA leg and the token leg. The
 * covenant's swap branch keys everything off input parity — MINIMA leg at an EVEN input, token leg at
 * the ODD sibling — and pins each recreated reserve with {@code VERIFYOUT(@INPUT ...)}. So the txn
 * layout is fixed:
 *   input  0 = pool MINIMA coin (even)      output 0 = recreated MINIMA reserve (nx) @ pool addr
 *   input  1 = pool token coin  (odd)       output 1 = recreated token reserve   (ny) @ pool addr
 *   input 2+ = taker funding (plain wallet) output 2 = taker's proceeds; output 3 = taker's change
 * Amounts conserve exactly per token (zero burn), matching the mainnet run. The covenant needs no
 * signature (anyone-can-spend swap branch); {@code txnsign auto} only signs the taker's funding coins.
 */
public class PoolTxn {

    public interface Result { void onPosted(String txpowid); void onFailed(String message); }

    private final NodeApi node;

    public PoolTxn(NodeApi node) { this.node = node; }

    // Delegated to the process-wide CoinLock so swaps and the owner/keep-alive transactions in
    // PoolManager coordinate. This map was an INSTANCE field on a class only SwapView constructs, so it
    // protected swaps from each other and nothing else — meanwhile PoolRefresher's 8 parallel refreshes
    // were all selecting the same coin.
    private boolean isReserved(String coinid) { return CoinLock.isReserved(coinid); }
    private void reserve(List<Coin> funds) { CoinLock.reserve(funds); }
    private void release(List<Coin> funds) { CoinLock.release(funds); }

    /**
     * Swap against one pool. {@code minimaToToken=true}: put in {@code amountIn} MINIMA, receive token.
     * {@code false}: put in {@code amountIn} token, receive MINIMA. The quote is (re)computed here against
     * the pool's scanned reserves; if the reserves have since moved on-chain the txn simply fails the
     * invariant and is rejected — harmless, the caller retries after a rescan.
     */
    /**
     * Execute a routed swap: ONE transaction that spends + recreates every pool in the route (each pool's
     * MINIMA leg at an even input, token leg at the odd sibling, outputs mirrored — the covenant pins each
     * pool by @INPUT arithmetic, so N pools coexist in one tx; proven in the phase-B 3-pool routed swap),
     * funded from the wallet, with the aggregate proceeds paid to the taker and change returned.
     */
    public void swap(final PoolRouter.Route route, final boolean minimaToToken, final Result done) {
        if (route == null || !route.ok || route.allocs.isEmpty()) { done.onFailed("no route — trade too small for the pools"); return; }
        if (!Handover.begin(node.context())) { done.onFailed(Handover.BLOCKED); return; }
        // Marked running until the result is delivered (a hand-over refuses meanwhile).
        final java.util.concurrent.atomic.AtomicBoolean ended = new java.util.concurrent.atomic.AtomicBoolean();
        final Result cb = new Result() {
            @Override public void onPosted(String txpowid) {
                try { done.onPosted(txpowid); } finally { if (!ended.getAndSet(true)) Handover.end(); }
            }
            @Override public void onFailed(String message) {
                try { done.onFailed(message); } finally { if (!ended.getAndSet(true)) Handover.end(); }
            }
        };
        final String tok = route.allocs.get(0).pool.tok;
        final String fundTok = minimaToToken ? Util.MINIMA_TOKENID : tok;

        // exclude EVERY pool address for the pair (not just the routed ones) so a same-token pool leg can
        // never be pulled in as funding — belt-and-braces on top of sendable:true. ALSO exclude each
        // routed pool's OWNER payout address (OADR): funding a swap from an owner coin makes txnsign:auto
        // sign with $OPK, which flips the covenant into its owner branch and rejects the (reserve-shrinking)
        // swap. Critical for an LP swapping against their own pool from the same node.
        Set<String> exclude = new HashSet<>();
        for (String addr : route.pairAddresses) if (addr != null) exclude.add(addr.toLowerCase());
        for (PoolRouter.Alloc a : route.allocs) {
            if (a.pool.address != null) exclude.add(a.pool.address.toLowerCase());
            if (a.pool.oadr != null) exclude.add(a.pool.oadr.toLowerCase());
        }

        findFunding(fundTok, route.totalIn, exclude, new FundCb() {
            @Override public void onFunds(List<Coin> funds, BigDecimal sum) {
                reserve(funds);
                ensureTrackedAll(route.allocs, 0, () -> buildRouted(route, minimaToToken, tok, funds, sum, cb));
            }
            @Override public void onFailure(String message) { cb.onFailed(message); }
        });
    }

    // ---- build + post ------------------------------------------------------

    private void buildRouted(PoolRouter.Route route, boolean minimaToToken, String tok,
                             List<Coin> funds, BigDecimal sum, Result cb) {
        final String txid = "ppswap_" + tag();
        final String tokArg = " tokenid:" + tok;
        node.cmd("getaddress", new NodeApi.Cb() {
            @Override public void onResult(JSONObject j) {
                String taddr = j.optJSONObject("response") != null
                        ? j.optJSONObject("response").optString("address", "") : "";
                if (!TxPost.truthy(j, "status") || !FundingCoins.hex(taddr)) { release(funds); cb.onFailed("could not get a payout address"); return; }

                List<String> cmds = new ArrayList<>();
                cmds.add("txncreate id:" + txid);
                // interleaved pool inputs: pool k's MINIMA leg at index 2k (even), token leg at 2k+1 (odd)
                for (PoolRouter.Alloc a : route.allocs) {
                    cmds.add("txninput id:" + txid + " coinid:" + a.pool.coinidM);
                    cmds.add("txninput id:" + txid + " coinid:" + a.pool.coinidT);
                }
                for (Coin f : funds) cmds.add("txninput id:" + txid + " coinid:" + f.coinid);

                // interleaved recreated reserves, index-matched to the pool inputs (covenant VERIFYOUT @INPUT)
                for (PoolRouter.Alloc a : route.allocs) {
                    cmds.add("txnoutput id:" + txid + " amount:" + amt(a.quote.newX) + " address:" + a.pool.address + " storestate:false");
                    cmds.add("txnoutput id:" + txid + " amount:" + amt(a.quote.newY) + " address:" + a.pool.address + tokArg + " storestate:false");
                }

                BigDecimal change = sum.subtract(route.totalIn);
                if (minimaToToken) {
                    // aggregate token proceeds to the taker; MINIMA change back
                    cmds.add("txnoutput id:" + txid + " amount:" + amt(route.totalOut) + " address:" + taddr + tokArg + " storestate:false");
                    if (change.signum() > 0)
                        cmds.add("txnoutput id:" + txid + " amount:" + amt(change) + " address:" + taddr + " storestate:false");
                } else {
                    // aggregate MINIMA proceeds to the taker; token change back (a token can't be burned →
                    // output ANY positive change; it is grain-aligned so never sub-grain)
                    cmds.add("txnoutput id:" + txid + " amount:" + amt(route.totalOut) + " address:" + taddr + " storestate:false");
                    if (change.signum() > 0)
                        cmds.add("txnoutput id:" + txid + " amount:" + amt(change) + " address:" + taddr + tokArg + " storestate:false");
                }

                cmds.add("txnsign id:" + txid + " publickey:auto");   // signs the funding coins; pool legs spend via covenant
                cmds.add("txnbasics id:" + txid);
                TxPost.checkThenPost(node, txid, cmds, new TxPost.Done() {
                    @Override public void ok(String txpowid) { cb.onPosted(txpowid); }
                    @Override public void fail(String message) { release(funds); cb.onFailed(message); }
                });
            }
            @Override public void onError(String m) {
                release(funds);   // release reservation if we can't even get a payout address
                cb.onFailed(m);
            }
        });
    }

    /** Register every pool's covenant on the node before spending (sequential, best-effort), then continue. */
    private void ensureTrackedAll(List<PoolRouter.Alloc> allocs, int i, Runnable then) {
        if (i >= allocs.size()) { then.run(); return; }
        ensureTracked(allocs.get(i).pool, () -> ensureTrackedAll(allocs, i + 1, then));
    }

    /**
     * Make sure the node can attach this pool's covenant + MMR proof at {@code txnbasics} time (the
     * "Script Missing" trap) WITHOUT adopting the pool's reserves into the wallet balance. A row already
     * on the node — ANY track value — is left untouched: {@code newscript} REPLACES an existing row, so
     * re-registering here would downgrade an LP's own {@code trackall:true} row. Only an AFFIRMATIVE
     * "not found" reply ({@code {"status":false}} + the node's not-found error, via the SUCCESS callback)
     * registers the covenant with {@code trackall:false}: txnbasics reads the script table regardless of
     * the track flag, and track:false keeps a foreign pool's locked reserves out of {@code balance}. Any
     * AMBIGUOUS failure (read timeout, IPC overflow) writes NOTHING — a blind newscript could replace a
     * row we merely failed to read; if the row genuinely doesn't exist, txnbasics fails closed, nothing
     * posts, and the user retries. A wrong track:false that slips through anyway is healed by the flows
     * that re-assert trackall:true: create, migrate, Restore, and the launch re-track (retrackOwnPools).
     */
    private void ensureTracked(Pool p, Runnable then) {
        node.cmd("scripts address:" + p.address, new NodeApi.Cb() {
            @Override public void onResult(JSONObject j) {
                if (TrackHygiene.rowExists(j)) { then.run(); return; }
                if (TrackHygiene.isNotFound(j)) { registerUntracked(p, then); return; }
                then.run();   // ambiguous failure → no write (see javadoc)
            }
            @Override public void onError(String m) { then.run(); }   // transport failure → no write
        });
    }

    private void registerUntracked(Pool p, Runnable then) {
        String script = (p.covenantScript != null && !p.covenantScript.isEmpty())
                ? p.covenantScript   // the ACTUAL covenant when known (a legacy-fee pool reconstructs differently)
                : PoolCovenant.script(p.opk, p.oadr, p.tok, p.kmin);
        node.cmd("newscript trackall:false script:" + Util.scriptArg(script), new NodeApi.Cb() {
            @Override public void onResult(JSONObject j) { then.run(); }
            @Override public void onError(String m) { then.run(); }
        });
    }

    // ---- funding coin selection (port of the limit app's findCoins) --------

    private interface FundCb { void onFunds(List<Coin> funds, BigDecimal sum); void onFailure(String message); }

    private void findFunding(String tokenid, BigDecimal need, Set<String> excludeAddrsLower, FundCb cb) {
        Set<String> excluded = new java.util.HashSet<>(OwnPoolStore.ownerAddresses(node.context()));
        if (excludeAddrsLower != null) excluded.addAll(excludeAddrsLower);
        FundingCoins.select(node::cmd, tokenid, need, excluded, new FundingCoins.Done() {
            @Override public void ok(List<Coin> coins, BigDecimal sum) { cb.onFunds(coins, sum); }
            @Override public void fail(String message) { cb.onFailure(message); }
        });
    }

    // ---- helpers -----------------------------------------------------------

    /** Plain-decimal string the node accepts (never scientific notation). */
    private static String amt(BigDecimal b) { return b.stripTrailingZeros().toPlainString(); }

    private static String tag() {
        return System.currentTimeMillis() + "_" + Integer.toHexString((int) (System.nanoTime() & 0xffffff));
    }
}
