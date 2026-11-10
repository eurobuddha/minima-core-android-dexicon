// VENDORED VERBATIM from apks/pandapools 0.9.97 app/src/main/java/com/eurobuddha/pandapools/PoolManager.java by scripts/vendor-pandapools.sh — do not edit here; re-vendor.
package com.eurobuddha.pandapools;

import org.json.JSONArray;
import org.json.JSONObject;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The liquidity-provider (owner) transactions: CREATE a pool, ADD liquidity (grow-in-place), MIGRATE
 * (reset the KMIN floor at a fresh address), and CLOSE (sweep reserves back to the owner). All owner
 * paths are gated by the covenant's {@code SIGNEDBY($OPK)} branch, so only the node that minted the
 * pool's owner key can manage it. Transaction shapes mirror the proven {@code phaseB.py} lifecycle and
 * {@code phaseC_close.py} exactly.
 *
 * Create is ONE atomic transaction: it funds both reserve coins at the covenant address AND posts the
 * registry announce beacon (a dust coin at the shared sentinel carrying the pool params in state) so the
 * pool is discoverable, with change returned — no coin-lock races between separate sends.
 */
public class PoolManager {

    public interface Result { void onPosted(String txpowid); void onFailed(String message); }
    public interface CreateResult { void onCreated(Pool pool, String txpowid); void onFailed(String message); }
    public interface ForwardResult { void onForwarded(String txpowid, int coins); void onNothing(); void onFailed(String message); }
    public interface SweepResult { void onSwept(int addressesForwarded, int coins, String error); }

    /** The discovery beacon carries only dust — it is a rendezvous coin, not spendable liquidity.
     *  Public because {@link TxClassifier} identifies a keep-alive/re-announce transaction by this exact
     *  beacon amount (the sentinel is stored in its Mx form in history, so the amount is the reliable tell). */
    public static final BigDecimal ANNOUNCE_DUST = new BigDecimal("0.000000001");
    /** Grow-in-place is capped at 2×KMIN: beyond that a forged-dust raid could in theory profit, so we
     *  force a MIGRATE (which resets KMIN to the new product) instead. */
    private static final BigDecimal GROW_CAP_MULT = new BigDecimal("2");

    /** Keep-fresh and re-announce both route through here: a pool Dexicon keeps is never touched again. */
    static final String KEPT_BY_DEXICON = "Dexicon keeps this pool now.";

    private final NodeApi node;

    public PoolManager(NodeApi node) { this.node = node; }

    // ===================================================================== CREATE

    /** {@code done}, ending the hand-over RUNNING mark ({@link Handover#begin}/{@link Handover#mark}) exactly once. */
    static CreateResult ending(CreateResult done) {
        final java.util.concurrent.atomic.AtomicBoolean ended = new java.util.concurrent.atomic.AtomicBoolean();
        return new CreateResult() {
            @Override public void onCreated(Pool pool, String txpowid) {
                try { done.onCreated(pool, txpowid); } finally { if (!ended.getAndSet(true)) Handover.end(); }
            }
            @Override public void onFailed(String message) {
                try { done.onFailed(message); } finally { if (!ended.getAndSet(true)) Handover.end(); }
            }
        };
    }

    static Result ending(Result done) {
        final java.util.concurrent.atomic.AtomicBoolean ended = new java.util.concurrent.atomic.AtomicBoolean();
        return new Result() {
            @Override public void onPosted(String txpowid) {
                try { done.onPosted(txpowid); } finally { if (!ended.getAndSet(true)) Handover.end(); }
            }
            @Override public void onFailed(String message) {
                try { done.onFailed(message); } finally { if (!ended.getAndSet(true)) Handover.end(); }
            }
        };
    }

    /** Plain wording for a RuntimeException thrown before the operation answered (its mark still ends once). */
    static String unexpected(RuntimeException e) {
        return "Something went wrong preparing this (" + e.getClass().getSimpleName()
                + (e.getMessage() == null ? "" : ": " + e.getMessage()) + "). Check My Pools before trying again.";
    }

    /** (Re-)register an own pool's covenant so the node tracks its coins. Idempotent. */
    static void retrack(NodeApi node, Pool p, NodeApi.Cb cb) {
        node.cmd("newscript trackall:true script:" + Util.scriptArg(p.covenantScript), cb);
    }

    /** Create a MINIMA/token pool seeded with x0 MINIMA and y0 token. Mints a fresh owner key+address. */
    public void createPool(String tokenid, int tokDecimals, BigDecimal x0, BigDecimal y0, CreateResult done) {
        if (!Handover.begin(node.context())) { done.onFailed(Handover.BLOCKED); return; }
        // Marked running until the result is delivered, so a hand-over refuses while this create can still
        // record a recipe or post.
        final CreateResult cb = ending(done);
        try {
            if (!BackupState.combinedUpToDate(node.context())) {
                cb.onFailed("BACKUP_REQUIRED: save and verify a password-encrypted pool + node backup before creating another pool. Recovery and withdrawal remain available."); return;
            }
            if (!FundingCoins.hex(tokenid) || Util.MINIMA_TOKENID.equalsIgnoreCase(tokenid)
                    || tokDecimals < 0 || tokDecimals > 44) { cb.onFailed("Invalid paired token or precision"); return; }
            if (x0 == null || y0 == null || x0.signum() <= 0 || y0.signum() <= 0) {
                cb.onFailed("both reserves must be greater than zero"); return;
            }
            final BigDecimal y0c = y0.setScale(tokDecimals, RoundingMode.DOWN);   // reserve must be an achievable coin amount
            if (y0c.signum() <= 0) { cb.onFailed("token amount is below the token's smallest unit"); return; }
            if (!PoolCovenant.sizeOk(x0, y0c)) {
                cb.onFailed("x0 × y0 must stay under 2^64 — use smaller reserves (or split into several pools)"); return;
            }
            final String kmin = PoolCovenant.kmin(x0, y0c);

            // 1. mint the owner identity (payout address + its public key = $OADR / $OPK)
            node.cmd("newaddress", new NodeApi.Cb() {
                @Override public void onResult(JSONObject j) {
                    JSONObject r = j.optJSONObject("response");
                    String oadr = r != null ? r.optString("address", "") : "";
                    String opk  = r != null ? r.optString("publickey", "") : "";
                    if (!TxPost.truthy(j, "status") || !FundingCoins.hex(oadr) || !FundingCoins.hex(opk)) {
                        cb.onFailed("could not mint an owner key"); return;
                    }
                    // the reply's `total` is the key count AFTER this mint → this key's derivation index
                    final int newTotal = r.optInt("total", 0);
                    final int kidx = newTotal > 0 ? newTotal - 1 : -1;

                    final String script = PoolCovenant.script(opk, oadr, tokenid, kmin);
                    // 2. derive the canonical covenant address the same way discovery will (node runscript)
                    deriveAddress(script, new AddrCb() {
                        @Override public void ok(String address, String mx) {
                            // 3. register so the node tracks + can later spend the pool coins
                            node.cmd("newscript trackall:true script:" + Util.scriptArg(script), new NodeApi.Cb() {
                                @Override public void onResult(JSONObject nj) {
                                    if (TxPost.truthy(nj, "status")) fundAndPost();
                                    else cb.onFailed("Could not register the recovery covenant. Nothing was posted.");
                                }
                                @Override public void onError(String m) { cb.onFailed(m); }
                                private void fundAndPost() {
                                    Pool p = new Pool();
                                    p.newlyCreatedWithCurrentOwnerState = true;
                                    p.address = address; p.mxaddress = mx; p.opk = opk; p.oadr = oadr;
                                    p.kidx = kidx;
                                    p.tok = tokenid; p.kmin = kmin; p.tokDecimals = tokDecimals;
                                    p.covenantScript = script;   // exact covenant → OwnPoolStore recipe for recovery
                                    p.reserveM = x0; p.reserveT = y0c;
                                    buildCreate(p, x0, y0c, tokenid, cb);
                                }
                            });
                        }
                        @Override public void fail(String m) { cb.onFailed("address derivation failed: " + m); }
                    });
                }
                @Override public void onError(String m) { cb.onFailed(m); }
            });
        } catch (RuntimeException e) { cb.onFailed(unexpected(e)); }
    }

    private void buildCreate(Pool p, BigDecimal x0, BigDecimal y0, String tokenid, CreateResult cb) {
        final BigDecimal minimaNeed = x0.add(ANNOUNCE_DUST);
        // select MINIMA (reserve + beacon dust), then token (reserve)
        selectCoins(Util.MINIMA_TOKENID, minimaNeed, p.address, p.oadr, new SelCb() {
            @Override public void ok(List<Coin> mfunds, BigDecimal msum) {
                selectCoins(tokenid, y0, p.address, p.oadr, new SelCb() {
                    @Override public void ok(List<Coin> tfunds, BigDecimal tsum) {
                        // Last moment to stop: nothing is posted yet (the recipe is always recorded BEFORE the
                        // post, so a pool can never exist without its recipe).
                        if (Handover.active(node.context())) {
                            CoinLock.release(mfunds); CoinLock.release(tfunds);
                            cb.onFailed(Handover.BLOCKED + " Nothing was posted."); return;
                        }
                        if (!OwnPoolStore.recordDurably(node.context(), p)) {
                            CoinLock.release(mfunds); CoinLock.release(tfunds);
                            cb.onFailed("Could not save the pool recovery recipe. Nothing was posted."); return;
                        }
                        String txid = "ppcreate_" + tag();
                        String tokArg = " tokenid:" + tokenid;
                        List<String> cmds = new ArrayList<>();
                        cmds.add("txncreate id:" + txid);
                        for (Coin c : mfunds) cmds.add("txninput id:" + txid + " coinid:" + c.coinid);
                        for (Coin c : tfunds) cmds.add("txninput id:" + txid + " coinid:" + c.coinid);
                        // reserves at the covenant address (no state — Variant U)
                        cmds.add("txnoutput id:" + txid + " amount:" + amt(x0) + " address:" + p.address + " storestate:false");
                        cmds.add("txnoutput id:" + txid + " amount:" + amt(y0) + " address:" + p.address + tokArg + " storestate:false");
                        // the discovery beacon — dust at the sentinel, params in state (storestate:true)
                        cmds.add("txnoutput id:" + txid + " amount:" + amt(ANNOUNCE_DUST) + " address:" + PoolCovenant.SENTINEL + " storestate:true");
                        // change goes to the funding coins' own (non-owner) address, NOT $OADR — otherwise the
                        // owner's spare MINIMA piles up at $OADR, and funding a later self-swap from there would
                        // sign with $OPK and trip the covenant's owner branch.
                        String mChg = mfunds.isEmpty() ? p.oadr : mfunds.get(0).address;
                        String tChg = tfunds.isEmpty() ? p.oadr : tfunds.get(0).address;
                        BigDecimal mchange = msum.subtract(minimaNeed);
                        if (mchange.signum() > 0)
                            cmds.add("txnoutput id:" + txid + " amount:" + amt(mchange) + " address:" + mChg + " storestate:false");
                        BigDecimal tchange = tsum.subtract(y0);
                        if (tchange.signum() > 0)
                            cmds.add("txnoutput id:" + txid + " amount:" + amt(tchange) + " address:" + tChg + tokArg + " storestate:false");
                        addAnnounceState(cmds, txid, p);
                        cmds.add("txnsign id:" + txid + " publickey:auto");
                        cmds.add("txnbasics id:" + txid);
                        TxPost.checkThenPost(node, txid, cmds, new TxPost.Done() {
                            @Override public void ok(String txpowid) { cb.onCreated(p, txpowid); }
                            @Override public void fail(String message) { cb.onFailed(message); }
                        });
                    }
                    @Override public void fail(String message) { CoinLock.release(mfunds); cb.onFailed(message); }
                });
            }
            @Override public void fail(String message) { cb.onFailed(message); }
        });
    }

    // ===================================================================== ADD LIQUIDITY (grow-in-place)

    /** Owner-signed deposit: reserves grow at the SAME address. Capped at 2×KMIN (else migrate). */
    public void deposit(Pool p, BigDecimal addM, BigDecimal addT, Result done) {
        // The owner's own funds: never refused for the hand-over, but marked running so a hand-over waits for it.
        Handover.mark();
        final Result cb = ending(done);
        try {
            if (!p.funded()) { cb.onFailed("pool has no live reserves"); return; }
            if (addM == null || addT == null || addM.signum() < 0 || addT.signum() < 0
                    || (addM.signum() == 0 && addT.signum() == 0)) { cb.onFailed("enter an amount to add"); return; }
            final BigDecimal addTc = addT.setScale(p.tokDecimals, RoundingMode.DOWN);
            final BigDecimal newX = p.reserveM.add(addM);
            final BigDecimal newY = p.reserveT.add(addTc);
            BigDecimal cap = Util.decOr(p.kmin, BigDecimal.ZERO).multiply(GROW_CAP_MULT);
            if (cap.signum() > 0 && newX.multiply(newY).compareTo(cap) > 0) {
                cb.onFailed("this deposit would push K past 2×KMIN — use Migrate to add liquidity and reset the floor");
                return;
            }
            final String tok = p.tok, tokArg = " tokenid:" + tok;
            selectCoins(Util.MINIMA_TOKENID, addM, p.address, p.oadr, new SelCb() {
                @Override public void ok(List<Coin> mfunds, BigDecimal msum) {
                    selectCoins(tok, addTc, p.address, p.oadr, new SelCb() {
                        @Override public void ok(List<Coin> tfunds, BigDecimal tsum) {
                            String txid = "ppdep_" + tag();
                            List<String> cmds = new ArrayList<>();
                            cmds.add("txncreate id:" + txid);
                            cmds.add("txninput id:" + txid + " coinid:" + p.coinidM);   // 0 pool MINIMA
                            cmds.add("txninput id:" + txid + " coinid:" + p.coinidT);   // 1 pool token
                            for (Coin c : mfunds) cmds.add("txninput id:" + txid + " coinid:" + c.coinid);
                            for (Coin c : tfunds) cmds.add("txninput id:" + txid + " coinid:" + c.coinid);
                            // outputs 0/1 recreate the grown reserves at the SAME address (owner grow branch)
                            cmds.add("txnoutput id:" + txid + " amount:" + amt(newX) + " address:" + p.address + " storestate:false");
                            cmds.add("txnoutput id:" + txid + " amount:" + amt(newY) + " address:" + p.address + tokArg + " storestate:false");
                            String mChg = mfunds.isEmpty() ? p.oadr : mfunds.get(0).address;
                            String tChg = tfunds.isEmpty() ? p.oadr : tfunds.get(0).address;
                            BigDecimal mchange = msum.subtract(addM);
                            if (mchange.signum() > 0)
                                cmds.add("txnoutput id:" + txid + " amount:" + amt(mchange) + " address:" + mChg + " storestate:false");
                            BigDecimal tchange = tsum.subtract(addTc);
                            if (tchange.signum() > 0)
                                cmds.add("txnoutput id:" + txid + " amount:" + amt(tchange) + " address:" + tChg + tokArg + " storestate:false");
                            ownerSignPost(txid, p.opk, cmds, cb);
                        }
                        @Override public void fail(String message) { CoinLock.release(mfunds); cb.onFailed(message); }
                    });
                }
                @Override public void fail(String message) { cb.onFailed(message); }
            });
        } catch (RuntimeException e) { cb.onFailed(unexpected(e)); }
    }

    // ===================================================================== MIGRATE (reset floor, new addr)

    /** Owner-signed migrate: old reserves sweep to $OADR, a fresh pool of (newX,newY) is created at a new
     *  address with KMIN reset to the new product, and a new announce beacon is posted. */
    public void migrate(Pool p, BigDecimal newX, BigDecimal newY, CreateResult done) {
        // A migrate creates a NEW pool (a new recipe): refused once handed over, marked running until it answers.
        if (!Handover.begin(node.context())) { done.onFailed(Handover.BLOCKED); return; }
        final CreateResult cb = ending(done);
        try {
            if (!BackupState.combinedUpToDate(node.context())) {
                cb.onFailed("BACKUP_REQUIRED: save and verify a password-encrypted pool + node backup before creating another pool. Recovery and withdrawal remain available."); return;
            }
            if (!p.funded()) { cb.onFailed("pool has no live reserves"); return; }
            if (newX == null || newY == null || newX.signum() <= 0 || newY.signum() <= 0) { cb.onFailed("enter the new reserves"); return; }
            final BigDecimal newYc = newY.setScale(p.tokDecimals, RoundingMode.DOWN);
            if (newYc.signum() <= 0) { cb.onFailed("new token reserve below the token grain"); return; }
            if (!PoolCovenant.sizeOk(newX, newYc)) { cb.onFailed("new reserves too large — x×y must stay under 2^64"); return; }
            final String kmin2 = PoolCovenant.kmin(newX, newYc);
            final String script2 = PoolCovenant.script(p.opk, p.oadr, p.tok, kmin2);
            deriveAddress(script2, new AddrCb() {
                @Override public void ok(String a2, String mx2) {
                    if (a2.equalsIgnoreCase(p.address)) {
                        cb.onFailed("these reserves give the same pool — change the amounts, or use Add to grow in place");
                        return;
                    }
                    node.cmd("newscript trackall:true script:" + Util.scriptArg(script2), new NodeApi.Cb() {
                        @Override public void onResult(JSONObject nj) {
                            if (TxPost.truthy(nj, "status")) go();
                            else cb.onFailed("Could not register the recovery covenant. Nothing was posted.");
                        }
                        @Override public void onError(String m) { cb.onFailed(m); }
                        private void go() {
                            Pool np = new Pool();
                            np.newlyCreatedWithCurrentOwnerState = true;
                            np.address = a2; np.mxaddress = mx2; np.opk = p.opk; np.oadr = p.oadr;
                            np.kidx = p.kidx;   // same owner key, same derivation index
                            np.tok = p.tok; np.kmin = kmin2; np.tokDecimals = p.tokDecimals;
                            np.covenantScript = script2;   // exact covenant → OwnPoolStore recipe for recovery
                            np.reserveM = newX; np.reserveT = newYc;
                            buildMigrate(p, np, newX, newYc, cb);
                        }
                    });
                }
                @Override public void fail(String m) { cb.onFailed("new address derivation failed: " + m); }
            });
        } catch (RuntimeException e) { cb.onFailed(unexpected(e)); }
    }

    private void buildMigrate(Pool p, Pool np, BigDecimal newX, BigDecimal newY, CreateResult cb) {
        final String tok = p.tok, tokArg = " tokenid:" + tok;
        final BigDecimal oldX = p.reserveM, oldY = p.reserveT;
        final BigDecimal minimaNeed = newX.add(ANNOUNCE_DUST);   // new reserve + the new beacon dust
        selectCoins(Util.MINIMA_TOKENID, minimaNeed, p.address, p.oadr, new SelCb() {
            @Override public void ok(List<Coin> mfunds, BigDecimal msum) {
                selectCoins(tok, newY, p.address, p.oadr, new SelCb() {
                    @Override public void ok(List<Coin> tfunds, BigDecimal tsum) {
                        // Last moment to stop, as for a create: nothing is posted and no new recipe recorded yet.
                        if (Handover.active(node.context())) {
                            CoinLock.release(mfunds); CoinLock.release(tfunds);
                            cb.onFailed(Handover.BLOCKED + " Nothing was posted."); return;
                        }
                        if (!OwnPoolStore.recordDurably(node.context(), np)) {
                            CoinLock.release(mfunds); CoinLock.release(tfunds);
                            cb.onFailed("Could not save the pool recovery recipe. Nothing was posted."); return;
                        }
                        String txid = "ppmig_" + tag();
                        List<String> cmds = new ArrayList<>();
                        cmds.add("txncreate id:" + txid);
                        cmds.add("txninput id:" + txid + " coinid:" + p.coinidM);   // 0 old pool MINIMA -> owner exit
                        cmds.add("txninput id:" + txid + " coinid:" + p.coinidT);   // 1 old pool token  -> owner exit
                        for (Coin c : mfunds) cmds.add("txninput id:" + txid + " coinid:" + c.coinid);
                        for (Coin c : tfunds) cmds.add("txninput id:" + txid + " coinid:" + c.coinid);
                        // outputs 0/1 = owner exit of the OLD reserves to $OADR (pinned by VERIFYOUT @INPUT)
                        cmds.add("txnoutput id:" + txid + " amount:" + amt(oldX) + " address:" + p.oadr + " storestate:false");
                        cmds.add("txnoutput id:" + txid + " amount:" + amt(oldY) + " address:" + p.oadr + tokArg + " storestate:false");
                        // outputs 2/3 = the NEW pool reserves at the new address
                        cmds.add("txnoutput id:" + txid + " amount:" + amt(newX) + " address:" + np.address + " storestate:false");
                        cmds.add("txnoutput id:" + txid + " amount:" + amt(newY) + " address:" + np.address + tokArg + " storestate:false");
                        // the new discovery beacon
                        cmds.add("txnoutput id:" + txid + " amount:" + amt(ANNOUNCE_DUST) + " address:" + PoolCovenant.SENTINEL + " storestate:true");
                        String mChg = mfunds.isEmpty() ? p.oadr : mfunds.get(0).address;
                        String tChg = tfunds.isEmpty() ? p.oadr : tfunds.get(0).address;
                        BigDecimal mchange = msum.subtract(minimaNeed);
                        if (mchange.signum() > 0)
                            cmds.add("txnoutput id:" + txid + " amount:" + amt(mchange) + " address:" + mChg + " storestate:false");
                        BigDecimal tchange = tsum.subtract(newY);
                        if (tchange.signum() > 0)
                            cmds.add("txnoutput id:" + txid + " amount:" + amt(tchange) + " address:" + tChg + tokArg + " storestate:false");
                        addAnnounceState(cmds, txid, np);
                        final String payoutAddress = p.oadr;
                        ownerSignPost(txid, p.opk, cmds,
                                (post, fail) -> CollectConfirmation.beforePost(node, txid, payoutAddress, post, fail), new Result() {
                            @Override public void onPosted(String txpowid) { cb.onCreated(np, txpowid); }
                            @Override public void onFailed(String message) { cb.onFailed(message); }
                        });
                    }
                    @Override public void fail(String message) { CoinLock.release(mfunds); cb.onFailed(message); }
                });
            }
            @Override public void fail(String message) { cb.onFailed(message); }
        });
    }

    // ===================================================================== CLOSE (owner sweep)

    /** Owner-signed close: sweep both reserves back to $OADR. */
    public void close(Pool p, Result done) {
        // The owner's own funds: never refused for the hand-over, but marked running so a hand-over waits for it.
        Handover.mark();
        final Result cb = ending(done);
        try {
            if (!p.funded()) { cb.onFailed("pool has no live reserves to withdraw"); return; }
            final String tokArg = " tokenid:" + p.tok;
            String txid = "ppclose_" + tag();
            List<String> cmds = new ArrayList<>();
            cmds.add("txncreate id:" + txid);
            cmds.add("txninput id:" + txid + " coinid:" + p.coinidM);   // 0 -> owner exit
            cmds.add("txninput id:" + txid + " coinid:" + p.coinidT);   // 1 -> owner exit
            cmds.add("txnoutput id:" + txid + " amount:" + amt(p.reserveM) + " address:" + p.oadr + " storestate:false");
            cmds.add("txnoutput id:" + txid + " amount:" + amt(p.reserveT) + " address:" + p.oadr + tokArg + " storestate:false");
            final String payoutAddress = p.oadr;
            ownerSignPost(txid, p.opk, cmds, false,
                    (post, fail) -> CollectConfirmation.beforePost(node, txid, payoutAddress, post, fail), cb);
        } catch (RuntimeException e) { cb.onFailed(unexpected(e)); }
    }

    // ===================================================================== FORWARD TO DEFAULT WALLET

    /**
     * Forward every sendable coin sitting at a pool owner address ($OADR) onward to a fresh DEFAULT-64
     * wallet address ({@code getaddress}). The covenant pins the owner exit to $OADR, so a close can only
     * land funds there; this second hop moves them into the 64 seed-derived default addresses that a
     * seed-only restore always regenerates — so withdrawn funds are never stranded at a {@code newaddress}
     * ($OADR) that a bare seed restore wouldn't reproduce. $OADR is {@code RETURN SIGNEDBY($OPK)} and the
     * node holds $OPK, so {@code txnsign auto} covers it. Full amounts (zero burn, like the app's other txns);
     * whole coins move, so token grain is exact. No-op ({@code onNothing}) if nothing sendable is there yet
     * (e.g. a just-posted close still confirming) — callers retry.
     */
    public void forwardOwnerFunds(final String oadr, final ForwardResult cb) {
        if (!FundingCoins.hex(oadr)) { cb.onFailed("Invalid owner address."); return; }
        Pool recovery = ExitStore.outstandingRecovery(node.context(), oadr);   // review 7 Nov (R2-2)
        if (recovery != null) { forwardRecovery(recovery, cb); return; }
        List<String> owners = new ArrayList<>();
        for (Pool p : OwnPoolStore.all(node.context())) if (oadr.equalsIgnoreCase(p.oadr)) owners.add(p.opk);
        OwnerKeyRecovery.ensure(node.context(), node, owners, blocked -> {
            if (!blocked.isEmpty()) {
                OwnerKeyRecovery.Blocked worst = OwnerKeyRecovery.worst(blocked);
                cb.onFailed("Nothing collected.\n\n" + worst.message()); return;
            }
            forwardCheckedOwnerFunds(oadr, cb);
        });
    }

    /** Resumes only an explicitly accepted recovery, after its journalled withdrawal confirms. */
    private void forwardRecovery(Pool p, ForwardResult cb) {
        ExitStore.Record record = ExitStore.get(node.context(), p.address);
        if (record == null || record.stateOf() != ExitStore.State.POSTED || record.signatures != 1
                || !p.opk.equalsIgnoreCase(record.opk)) {
            cb.onFailed("Recovery collection is already submitted or needs review. No extra signature was made."); return;
        }
        new CollectConfirmation(node.context(), node).confirmed(PendingCollect.get(node.context(), p.oadr), confirmed -> {
            if (!confirmed) { cb.onNothing(); return; }
            forwardCheckedOwnerFunds(p.oadr, p, cb);
        });
    }

    private void forwardCheckedOwnerFunds(final String oadr, final ForwardResult cb) {
        forwardCheckedOwnerFunds(oadr, null, cb);
    }

    private void forwardCheckedOwnerFunds(final String oadr, final Pool recovery, final ForwardResult cb) {
        node.cmd("balance address:" + oadr, new NodeApi.Cb() {
            public void onResult(JSONObject reply) {
                JSONArray rows = FundingCoins.rows(reply);
                int count = 0;
                if (rows == null) { cb.onFailed("Could not count owner-address coins."); return; }
                try {
                    for (int i = 0; i < rows.length(); i++) {
                        int n = new BigDecimal(rows.getJSONObject(i).get("coins").toString()).intValueExact();
                        if (n < 0) throw new IllegalArgumentException();
                        count = Math.addExact(count, n);
                    }
                } catch (Exception invalid) { cb.onFailed("Invalid owner-address coin count."); return; }
                String limit = ownerCoinsLimit(count, recovery != null);
                if (limit == null) {
                    cb.onFailed("Owner address has " + count + " coins. Recovery collection is paused because this node cannot safely list that group."); return;
                }
                if (limit.isEmpty()) { forwardSmallAddress(oadr, recovery, count, "", false, cb); return; }
                // Batch (D-3-1): `max:` caps the node's newest-first walk BEFORE its other filters and before mine, so 8 fresh
                // junk coins (foreign token, or carrying state) could fill every window. Ask for one owned token at a time
                // (MINIMA, then each pool token at this payout address, in turn) and forward coins that carry state too
                // (a RETURN SIGNEDBY input ignores state; the outputs are storestate:false).
                forwardSmallAddress(oadr, null, -1, " tokenid:" + batchToken(oadr) + limit, true, cb);
            }
            public void onError(String error) { cb.onFailed(error); }
        });
    }

    /** D-3: coins forwarded per pass when the payout address holds more than a list can safely carry. */
    static final int MAX_FORWARD_BATCH = 8;

    /** Review 7 Nov (D-3): anyone can send dust to the public payout address. Up to SAFE_COINS: list them all ("").
     *  More: forward a bounded batch (" max:8" - never a reply big enough to crash a pre-1.6.9 node); every collect
     *  pass moves the next batch home, so dust can delay but no longer block collection (a node without `max:` refuses
     *  the command and collection pauses as before). The recovery continuation needs ALL payout coins at once: null =
     *  paused (resolve by hand). */
    static String ownerCoinsLimit(int count, boolean recovery) {
        if (FundingCoins.listIsSafe(count)) return "";
        return recovery ? null : " max:" + MAX_FORWARD_BATCH;
    }

    /** One turn counter PER payout address: a shared counter let a fixed sweep order starve one address of a token. */
    private static final java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicInteger> BATCH_TURN =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** The owned tokens a payout address can hold - MINIMA and each own pool's token - taken in turn, one per pass. */
    String batchToken(String oadr) {
        List<String> toks = new ArrayList<>();
        toks.add("0x00");
        for (Pool p : OwnPoolStore.all(node.context()))
            if (oadr.equalsIgnoreCase(p.oadr) && FundingCoins.hex(p.tok) && !toks.contains(p.tok.toLowerCase(java.util.Locale.ROOT)))
                toks.add(p.tok.toLowerCase(java.util.Locale.ROOT));
        java.util.concurrent.atomic.AtomicInteger turn = BATCH_TURN.computeIfAbsent(oadr.toLowerCase(java.util.Locale.ROOT),
                k -> new java.util.concurrent.atomic.AtomicInteger());
        return toks.get(Math.floorMod(turn.getAndIncrement(), toks.size()));
    }

    private void forwardSmallAddress(String oadr, Pool recovery, int expected, String limit, boolean allowState, ForwardResult cb) {
        node.cmd("coins relevant:true sendable:true checkmempool:true coinage:3 address:" + oadr + limit, new NodeApi.Cb() {
            @Override public void onResult(JSONObject j) {
                JSONArray arr = FundingCoins.rows(j);
                if (arr == null) { cb.onFailed("Could not read owner-address coins."); return; }
                if (arr.length() == 0) { cb.onNothing(); return; }
                // The bounded continuation must wait for ALL payout coins; never spend its only attempt
                // on the first leg to mature while the other is still confirming.
                if (recovery != null && arr.length() != expected) { cb.onNothing(); return; }
                final List<String> coinids = new ArrayList<>();
                final Map<String, BigDecimal> byTok = new LinkedHashMap<>();   // tokenid -> summed human amount
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject c = arr.optJSONObject(i);
                    if (c == null || c.optBoolean("spent", false)) continue;
                    Coin coin;
                    try { coin = FundingCoins.fundingCoin(c); }
                    catch (IllegalArgumentException invalid) { cb.onFailed(invalid.getMessage()); return; }
                    Object state = c.opt("state");
                    if (!allowState && ((state instanceof JSONArray && ((JSONArray)state).length() > 0)
                            || (state instanceof JSONObject && ((JSONObject)state).length() > 0))) continue;
                    String tid = coin.tokenid.toLowerCase(java.util.Locale.ROOT), cid = coin.coinid;
                    if (!FundingCoins.hex(cid) || !oadr.equalsIgnoreCase(coin.address)) {
                        cb.onFailed("Unexpected owner-address coin."); return;
                    }
                    if (CoinLock.isReserved(cid)) continue;
                    BigDecimal a = new BigDecimal(coin.amount);
                    coinids.add(cid);
                    BigDecimal prev = byTok.get(tid);
                    byTok.put(tid, prev == null ? a : prev.add(a));
                }
                if (coinids.isEmpty() || (recovery != null && coinids.size() != expected)) { cb.onNothing(); return; }
                if (recovery != null && (!byTok.containsKey("0x00") || !byTok.containsKey(recovery.tok.toLowerCase(java.util.Locale.ROOT)))) { cb.onNothing(); return; }
                // fresh DEFAULT-64 wallet address to receive (getaddress, not newaddress)
                node.cmd("getaddress", new NodeApi.Cb() {
                    @Override public void onResult(JSONObject aj) {
                        JSONObject r = aj.optJSONObject("response");
                        String dest = r != null ? r.optString("address", "") : "";
                        if (!TxPost.truthy(aj, "status") || !FundingCoins.hex(dest)) { cb.onFailed("could not get a wallet address"); return; }
                        String txid = "ppfwd_" + tag();
                        List<String> cmds = new ArrayList<>();
                        cmds.add("txncreate id:" + txid);
                        for (String cid : coinids) cmds.add("txninput id:" + txid + " coinid:" + cid);
                        for (Map.Entry<String, BigDecimal> en : byTok.entrySet()) {
                            String tokArg = "0x00".equals(en.getKey()) ? "" : " tokenid:" + en.getKey();
                            cmds.add("txnoutput id:" + txid + " amount:" + amt(en.getValue()) + " address:" + dest + tokArg + " storestate:false");
                        }
                        cmds.add("txnsign id:" + txid + " publickey:" + (recovery == null ? "auto" : recovery.opk));
                        cmds.add("txnbasics id:" + txid);
                        final int n = coinids.size();
                        List<String> ownerKeys = new ArrayList<>();
                        for (Pool p : OwnPoolStore.all(node.context())) if (oadr.equalsIgnoreCase(p.oadr)) ownerKeys.add(p.opk);
                        ExitTicket ticket = recovery == null ? null : ExitTicket.forward(recovery, txid, coinids, byTok, dest);
                        if (recovery != null) {
                            if (!ExitAuthority.tryGrant(ticket)) { cb.onFailed("Recovery collection is busy or invalid; nothing signed."); return; }
                            if (!ExitStore.beginForward(node.context(), recovery)) {
                                ExitAuthority.revoke(ticket);
                                cb.onFailed("Could not record a new collection attempt; nothing signed."); return;
                            }
                        }
                        TxPost.checkThenPost(node, txid, cmds, ownerKeys,
                                recovery == null ? null : (post, fail) -> CollectConfirmation.beforePost(node, txid, oadr, post, fail),
                                new TxPost.Done() {
                            @Override public void ok(String txpowid) {
                                if (ticket != null) {
                                    ExitAuthority.revoke(ticket);
                                    ExitStore.set(node.context(), recovery.address, recovery.opk, ExitStore.State.FORWARDED,
                                            "Collection submitted; waiting for confirmation.", "", 0);
                                }
                                cb.onForwarded(txpowid, n);
                            }
                            @Override public void fail(String message) {
                                if (ticket != null) {
                                    ExitAuthority.revoke(ticket);
                                    ExitStore.set(node.context(), recovery.address, recovery.opk, ExitStore.State.UNCERTAIN,
                                            "Collection stopped; check Activity before any retry. " + message, "", 0);
                                }
                                cb.onFailed(message);
                            }
                        });
                    }
                    @Override public void onError(String m) { cb.onFailed(m); }
                });
            }
            @Override public void onError(String m) { cb.onFailed(m); }
        });
    }

    /**
     * "Collect to wallet" sweep: forward stranded funds from a set of owner addresses to default-64 wallet
     * addresses. Rescues funds that closed pools left at $OADR before this fix (and any close whose forward
     * didn't complete). Best-effort per address; reports totals. Dedups addresses (migrate shares $OADR).
     */
    public void sweepOwnerFunds(final List<String> oadrs, final SweepResult cb) {
        final LinkedHashSet<String> uniq = new LinkedHashSet<>();
        for (String a : oadrs) if (a != null && !a.isEmpty()) uniq.add(a);
        if (uniq.isEmpty()) { cb.onSwept(0, 0, ""); return; }
        final AtomicInteger pending = new AtomicInteger(uniq.size());
        final AtomicInteger addrs = new AtomicInteger(0);
        final AtomicInteger coins = new AtomicInteger(0);
        final java.util.concurrent.atomic.AtomicReference<String> error = new java.util.concurrent.atomic.AtomicReference<>("");
        for (String oadr : uniq) {
            forwardOwnerFunds(oadr, new ForwardResult() {
                @Override public void onForwarded(String txpowid, int n) { addrs.incrementAndGet(); coins.addAndGet(n); tick(); }
                @Override public void onNothing() { tick(); }
                @Override public void onFailed(String message) { error.compareAndSet("", message); tick(); }
                private void tick() { if (pending.decrementAndGet() == 0) cb.onSwept(addrs.get(), coins.get(), error.get()); }
            });
        }
    }

    // ===================================================================== RE-ANNOUNCE (Layer 5)

    /**
     * Post ONE fresh announce beacon for an existing pool so fresh nodes keep rediscovering it after the
     * original beacon pruned. This spends NO covenant coin — it's a plain owner-wallet send of the dust
     * beacon to the SENTINEL (with the pool params in state), change back. No owner signature needed (the
     * covenant isn't touched); adds no spend authority. Callers only fire this for a pool whose beacon has
     * actually FADED from the recent sentinel window, so beacons don't pile up.
     */
    public void reannounce(final Pool p, final Result cb) {
        if (p != null && Handover.keptByDexicon(node.context(), p.address)) { cb.onFailed(KEPT_BY_DEXICON); return; }
        if (p == null || p.address == null || isEmpty(p.opk) || isEmpty(p.tok) || isEmpty(p.oadr) || isEmpty(p.kmin)) {
            cb.onFailed("incomplete pool record"); return;
        }
        selectCoins(Util.MINIMA_TOKENID, ANNOUNCE_DUST, p.address, p.oadr, new SelCb() {
            @Override public void ok(List<Coin> mfunds, BigDecimal msum) {
                String txid = "ppann_" + tag();
                List<String> cmds = new ArrayList<>();
                cmds.add("txncreate id:" + txid);
                for (Coin c : mfunds) cmds.add("txninput id:" + txid + " coinid:" + c.coinid);
                cmds.add("txnoutput id:" + txid + " amount:" + amt(ANNOUNCE_DUST) + " address:" + PoolCovenant.SENTINEL + " storestate:true");
                String chg = mfunds.isEmpty() ? p.oadr : mfunds.get(0).address;
                BigDecimal change = msum.subtract(ANNOUNCE_DUST);
                if (change.signum() > 0)
                    cmds.add("txnoutput id:" + txid + " amount:" + amt(change) + " address:" + chg + " storestate:false");
                addAnnounceState(cmds, txid, p);
                cmds.add("txnsign id:" + txid + " publickey:auto");   // only the funding coins are signed; no covenant coin, no $OPK
                cmds.add("txnbasics id:" + txid);
                TxPost.checkThenPost(node, txid, cmds, new TxPost.Done() {
                    @Override public void ok(String txpowid) { cb.onPosted(txpowid); }
                    @Override public void fail(String message) { cb.onFailed(message); }
                });
            }
            @Override public void fail(String message) { cb.onFailed(message); }
        });
    }

    // ===================================================================== REFRESH (keep fresh in the cascade)

    /**
     * Owner-signed KEEP-FRESH: recreate the pool's two reserve coins IN PLACE — spend {@code coinidM}/{@code coinidT}
     * and re-output the SAME amounts to the SAME covenant address (the covenant's owner grow-in-place branch),
     * plus a fresh announce beacon — one transaction. New coin IDs (young → back inside the ~1700-block cascade →
     * visible again to every light node's {@code coins address:X}, so cross-device discovery + swap routing work
     * without megammr or any server), but the address, reserves, KMIN and identity are unchanged and NOTHING is
     * burned (MINIMA: reserveM+funding in = reserveM+dust+change out; token: reserveT in = reserveT out).
     *
     * This is a {@code deposit(0,0)} + beacon. It spends the covenant coins so it needs {@code $OPK} — owner-only,
     * unlike the node-wide beacon re-announce. The covenant's {@code GETOUTAMT GTE @AMOUNT} guard makes a mis-built
     * refresh fail CLOSED (a reserve can only be recreated at the same address for >= its amount), so it can never
     * lose or move funds. Callers fire it only when a pool's reserves are aging toward the cascade edge.
     *
     * Age is NOT reset by this: My LP age is {@code chainBlock - LpStore.block} (local, address-keyed), and this
     * does not touch LpStore — so the displayed age keeps counting from the original create block.
     */
    public void refresh(final Pool p, final Result cb) {
        if (p != null && Handover.keptByDexicon(node.context(), p.address)) { cb.onFailed(KEPT_BY_DEXICON); return; }
        if (p == null || !p.funded() || isEmpty(p.opk) || isEmpty(p.address) || isEmpty(p.tok) || isEmpty(p.oadr)
                || isEmpty(p.coinidM) || isEmpty(p.coinidT)) { cb.onFailed("incomplete pool record"); return; }
        final String tokArg = " tokenid:" + p.tok;
        // a tiny MINIMA funding coin for the beacon dust (excludes the pool's own address — never a pool coin)
        selectCoins(Util.MINIMA_TOKENID, ANNOUNCE_DUST, p.address, p.oadr, new SelCb() {
            @Override public void ok(List<Coin> mfunds, BigDecimal msum) {
                String txid = "pprefresh_" + tag();
                List<String> cmds = new ArrayList<>();
                cmds.add("txncreate id:" + txid);
                cmds.add("txninput id:" + txid + " coinid:" + p.coinidM);   // 0 pool MINIMA (even)
                cmds.add("txninput id:" + txid + " coinid:" + p.coinidT);   // 1 pool token  (odd)
                for (Coin c : mfunds) cmds.add("txninput id:" + txid + " coinid:" + c.coinid);
                // outputs 0/1 recreate the SAME reserves at the SAME address (owner grow branch; GTE holds at equality)
                cmds.add("txnoutput id:" + txid + " amount:" + amt(p.reserveM) + " address:" + p.address + " storestate:false");
                cmds.add("txnoutput id:" + txid + " amount:" + amt(p.reserveT) + " address:" + p.address + tokArg + " storestate:false");
                // fresh discovery beacon (dust at the sentinel, params in state)
                cmds.add("txnoutput id:" + txid + " amount:" + amt(ANNOUNCE_DUST) + " address:" + PoolCovenant.SENTINEL + " storestate:true");
                // MINIMA change to the funding coin's own (non-owner) address — NOT $OADR (see buildCreate rationale)
                String chg = mfunds.isEmpty() ? p.oadr : mfunds.get(0).address;
                BigDecimal change = msum.subtract(ANNOUNCE_DUST);
                if (change.signum() > 0)
                    cmds.add("txnoutput id:" + txid + " amount:" + amt(change) + " address:" + chg + " storestate:false");
                addAnnounceState(cmds, txid, p);
                ownerSignPost(txid, p.opk, cmds, cb);   // txnsign auto (funding) + txnsign $OPK (owner grow branch)
            }
            @Override public void fail(String message) { cb.onFailed(message); }
        });
    }

    private static boolean isEmpty(String s) { return s == null || s.isEmpty(); }

    // ===================================================================== helpers

    private void ownerSignPost(String txid, String opk, List<String> cmds, Result cb) {
        ownerSignPost(txid, opk, cmds, null, cb);
    }

    private void ownerSignPost(String txid, String opk, List<String> cmds, TxPost.BeforePost beforePost, Result cb) {
        ownerSignPost(txid, opk, cmds, true, beforePost, cb);
    }

    private void ownerSignPost(String txid, String opk, List<String> cmds, boolean fundingSignature, TxPost.BeforePost beforePost, Result cb) {
        // Append the signing lines BEFORE the pre-flight, so the pre-flight judges the transaction that will
        // actually run. A check against a half-built list cannot evaluate an exit exemption — whose whole
        // premise is verifying the real command list — and would refuse a permitted close.
        if (fundingSignature) cmds.add("txnsign id:" + txid + " publickey:auto"); // funding coins only
        cmds.add("txnsign id:" + txid + " publickey:" + opk);      // the owner signature the covenant requires
        cmds.add("txnbasics id:" + txid);
        OwnerKeyRecovery.ensure(node.context(), node, java.util.Collections.singletonList(opk), cmds, blocked -> {
            if (!blocked.isEmpty()) {
                cb.onFailed("Nothing was posted.\n\n" + OwnerKeyRecovery.worst(blocked).message()); return;
            }
            TxPost.checkThenPost(node, txid, cmds, java.util.Collections.emptyList(), beforePost, new TxPost.Done() {
                @Override public void ok(String txpowid) { cb.onPosted(txpowid); }
                @Override public void fail(String message) { cb.onFailed(message); }
            });
        });
    }

    /** Append the registry announce beacon's state ports (matches phaseB: 0 reclaim pk · 1 version ·
     *  2 tokenid · 3 $OADR · 4 $OPK · 5 $KMIN). The node's {@code txnstate} sets ONE port at a time
     *  ({@code txnstate id: port: value:}) — it does NOT accept a state JSON blob (that's only the
     *  {@code send} command). Version "PP1" is sent as hex, not a bare string. All values are hex or a
     *  plain number, so no quoting is needed. */
    private static void addAnnounceState(List<String> cmds, String txid, Pool p) {
        String ppver = "0x" + toHex("PP1");
        cmds.add("txnstate id:" + txid + " port:0 value:" + p.opk);    // reclaim key (the owner)
        cmds.add("txnstate id:" + txid + " port:1 value:" + ppver);    // version
        cmds.add("txnstate id:" + txid + " port:2 value:" + p.tok);    // tokenid
        cmds.add("txnstate id:" + txid + " port:3 value:" + p.oadr);   // owner payout addr
        cmds.add("txnstate id:" + txid + " port:4 value:" + p.opk);    // owner pubkey
        cmds.add("txnstate id:" + txid + " port:5 value:" + p.kmin);   // product floor
    }

    private static String toHex(String s) {
        StringBuilder b = new StringBuilder();
        for (byte c : s.getBytes()) b.append(String.format("%02X", c));
        return b.toString();
    }

    private interface AddrCb { void ok(String address, String mx); void fail(String message); }

    /**
     * Derive the covenant address via the node's own runscript AND enforce the fund-safety pre-flight
     * guard: the covenant MUST parse ({@code parseok=true}). If it doesn't (e.g. a bad quoting/escaping
     * regression), we ABORT here — before any funds are committed — because a coin at a non-parsing
     * script's address is unspendable by anyone, forever. The known template also provides an owner-close branch. Recovery still requires
     * the matching owner key and current wallet signing state; parsing alone cannot establish that.
     */
    private void deriveAddress(String script, AddrCb cb) {
        node.cmd("runscript script:" + Util.scriptArg(script), new NodeApi.Cb() {
            @Override public void onResult(JSONObject j) {
                JSONObject resp = j.optJSONObject("response");
                boolean parseok = TxPost.truthy(resp, "parseok");
                JSONObject sc = resp != null ? resp.optJSONObject("script") : null;
                String a = sc != null ? sc.optString("address", "") : "";
                String mx = sc != null ? sc.optString("mxaddress", "") : "";
                if (!TxPost.truthy(j, "status") || !FundingCoins.hex(a)) { cb.fail("could not derive the pool address"); return; }
                if (!parseok) { cb.fail("the pool covenant failed to compile (parse error) — aborted before "
                        + "any funds moved, to protect your coins"); return; }
                cb.ok(a, mx);
            }
            @Override public void onError(String m) { cb.fail(m); }
        });
    }

    private interface SelCb { void ok(List<Coin> coins, BigDecimal sum); void fail(String message); }

    /**
     * Plain sendable wallet coins for a token, largest-first, summing to at least {@code need}.
     *
     * Coins at {@code excludeAddress} (the pool's own covenant address) are never selected — belt-and-
     * braces on top of {@code sendable:true}, so a pool leg can never be pulled in twice as "funding".
     *
     * Also excludes {@code ownerAddress} ($OADR). Funding from an owner coin makes {@code txnsign auto}
     * sign with $OPK, which flips the covenant into its owner branch — and {@code ownerSignPost} then
     * signs with $OPK a SECOND time, burning two key uses for one action. {@link PoolTxn#swap} already
     * excluded it and explains why; this had not.
     *
     * And it skips coins another in-flight transaction has reserved ({@link CoinLock}). Without that,
     * the deliberate fan-outs (up to 8 parallel refreshes / re-announces) all take the same largest
     * coin, because for beacon dust the first coin always covers {@code need}.
     */
    private void selectCoins(String tokenid, BigDecimal need, String excludeAddress, String ownerAddress, SelCb cb) {
        java.util.Set<String> exclude = new java.util.HashSet<>();
        exclude.addAll(OwnPoolStore.ownerAddresses(node.context()));
        if (excludeAddress != null) exclude.add(excludeAddress);
        if (ownerAddress != null) exclude.add(ownerAddress);
        FundingCoins.select(node::cmd, tokenid, need, exclude, new FundingCoins.Done() {
            @Override public void ok(List<Coin> coins, BigDecimal sum) { cb.ok(coins, sum); }
            @Override public void fail(String message) { cb.fail(message); }
        });
    }

    /** Package-visible: RestoreExit builds an exit ticket carrying the EXACT amount strings the close will
     *  emit, and a ticket whose amounts were formatted differently would refuse its own transaction. */
    static String amt(BigDecimal b) { return b.stripTrailingZeros().toPlainString(); }

    private static String tag() {
        return System.currentTimeMillis() + "_" + Integer.toHexString((int) (System.nanoTime() & 0xffffff));
    }
}
