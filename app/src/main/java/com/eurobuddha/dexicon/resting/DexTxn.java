package com.eurobuddha.dexicon.resting;
/* Vendored from apks/pandadex app/src/main/java/com/eurobuddha/pandadex/DexTxn.java (PandaDEX 0.4.23).
 * Keep as close to verbatim as possible; only the package, the NodeApi/SignGate imports and the minimal Dexicon
 * glue noted inline differ (0.1.9: Result.onPostReply for the ACTIVITY tab). Per the family naming rule the Minima dollar token is MxUSD in identifiers too
 * (PandaDEX MXUSD_ID/MXUSD_DP/*Mxusd are MXUSD_ID/MXUSD_DP/*Mxusd here). Re-copy rather than hand-edit when PandaDEX changes. */


import com.eurobuddha.dexicon.CommandSafety;
import com.eurobuddha.dexicon.NodeApi;
import com.eurobuddha.dexicon.SignGate;

import org.json.JSONObject;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Every transaction the app posts — mirrors the constructions PROVEN in contract/phaseB.py.
 * Fund-safety rails (non-negotiable, from the sibling apps' hard lessons):
 *
 *  - txncheck GATE before every txnpost: proceed only when valid.scripts && validamounts &&
 *    mmrproofs are ALL true (top-level `scripts` is a COUNT, never a verdict).
 *  - token coin VALUE = tokenamount (never raw amount).
 *  - all outgoing amounts pre-quantized via PriceMath (maker-favored UP, taker-facing DOWN).
 *  - inflight funding-coin reservation across posted-but-unconfirmed txns.
 *  - state values are hex/numbers, ports set individually (txnstate id: port: value:).
 *  - order coin(s) are inputs 0..k-1; payments are outputs 0..k-1; the single partial (if
 *    any) is the LAST order input with its remainder at output k (covenant shape).
 */
public class DexTxn {

    public interface Result {
        /** Presentation only; never authorizes signing or submission. */
        default void onProgress(String message) {}
        /** Persist intent before signing. Returning false prevents submission. */
        default boolean onPrepared(String transactionHandle) { return true; }
        /** Persist the transition before asking the node to post. */
        default boolean beforePost() { return true; }
        /** Dexicon glue (ACTIVITY tab): the raw txnpost reply, just before onPosted — its body.txn carries the
         *  output coin ids the activity tracker settles by (the txpowid is the pre-mining id). */
        default void onPostReply(JSONObject reply) {}
        void onPosted(String txpowid);
        void onFailed(String message);
    }

    private final NodeApi node;
    private String myHexAddr = "";
    /** Retained host API. Claims are process-wide and cannot be pruned by a token scan. */
    private long chainBlock;
    public void setChainBlock(long b) { chainBlock = b; CoinLock.prune(); }

    public DexTxn(NodeApi node) { this.node = node; }

    /** Dexicon glue (PandaDEX KeySet supplies this there): the wallet address that receives proceeds and change. */
    public void setPayout(String hexAddr) { myHexAddr = FundingCoins.hex(hexAddr) ? hexAddr : ""; }

    public String hexAddr() { return myHexAddr; }

    /** Execute a blended order-book + PandaPools fill in one atomic transaction. */
    public void fillComposite(CompositeRouter.Plan plan, boolean takerBuys, Result cb) { fillComposite(plan, takerBuys, null, cb); }

    /** Dexicon glue (auto-cross): when {@code fundIds} is given, ONLY those coins fund it — the backing coins of my own
     *  crossed rungs (which the wallet scan cannot reach once the ladder split them into one big address group). */
    public void fillComposite(CompositeRouter.Plan plan, boolean takerBuys, List<String> fundIds, Result cb) {
        final FundingCoins.Command commands = node::cmd;
        final String payoutAddress = myHexAddr;
        if (plan == null || plan.isEmpty()) { cb.onFailed("Nothing to fill"); return; }
        if (!FundingCoins.hex(payoutAddress)) { cb.onFailed("Still reading your wallet address — try again in a moment. Nothing was sent."); return; }
        for (SweepPlanner.Take t : plan.orderTakes) {
            if (t == null || !safeOrder(t.order) || !t.order.fillable()) {
                cb.onFailed("Invalid order data. Refresh the book before trading."); return;
            }
        }
        CompositePrep prep = prepareComposite(plan, takerBuys);

        java.util.HashSet<String> exclude = new java.util.HashSet<>();
        if (prep.route != null) {
            for (String a : prep.route.pairAddresses) if (a != null) exclude.add(a.toLowerCase());
            for (PoolRouter.Alloc a : prep.route.allocs) {
                if (a.pool.address != null) exclude.add(a.pool.address.toLowerCase());
                if (a.pool.oadr != null) exclude.add(a.pool.oadr.toLowerCase());
            }
        }
        progress(cb, "Selecting wallet coins");
        CoinsCb chosen = coins -> {
            if (coins == null) {
                String why = takeFundError();
                cb.onFailed(why != null ? why : "Insufficient funds for trade");
                return;
            }
            progress(cb, "Wallet coins selected · checking pool access");
            ensureTrackedPools(prep.route == null ? new ArrayList<>() : prep.route.allocs, 0,
                    () -> buildComposite(commands, plan, prep, takerBuys, coins, payoutAddress, cb), message -> {
                        CoinLock.release(coins); cb.onFailed(message);
                    });
        };
        if (fundIds == null) { findCoins(prep.payTok, prep.needed, exclude, 8, chosen); return; }
        presetCoins(prep.payTok, prep.needed, fundIds, 0, new ArrayList<>(), BigDecimal.ZERO, coins -> {   // these coins only, never the wider wallet
            if (coins == null) lastFundError = "the crossed rungs' own coins no longer cover it. Nothing was posted.";
            chosen.found(coins);
        });
    }

    /** My own unspent, stateless coins of {@code token} named by {@code ids}, reserved, if they cover {@code need}; else null. */
    private void presetCoins(String token, BigDecimal need, List<String> ids, int i, List<JSONObject> got, BigDecimal sum, CoinsCb cb) {
        if (ids == null || ids.isEmpty()) { cb.found(null); return; }
        if (sum.compareTo(need) >= 0 || i >= ids.size() || got.size() >= 8) {
            android.util.Log.i("Dexicon", "auto-cross own coins: " + got.size() + " of " + ids.size() + " = " + sum.toPlainString() + " for " + need.toPlainString());
            if (sum.compareTo(need) < 0) { cb.found(null); return; }
            CoinLock.reserve(got); cb.found(got); return;
        }
        String id = ids.get(i);
        if (!FundingCoins.hex(id) || CoinLock.isReserved(id)) { presetCoins(token, need, ids, i + 1, got, sum, cb); return; }
        node.cmd("coins relevant:true sendable:true coinid:" + id, new NodeApi.Cb() {
            public void onResult(JSONObject j) {
                org.json.JSONArray arr = FundingCoins.rows(j);
                JSONObject c = arr == null || arr.length() != 1 ? null : arr.optJSONObject(0);
                Object st = c == null ? null : c.opt("state");
                boolean ok = c != null && token.equalsIgnoreCase(c.optString("tokenid")) && id.equalsIgnoreCase(c.optString("coinid"))
                        && !c.optBoolean("spent", false)
                        && !(st instanceof org.json.JSONArray && ((org.json.JSONArray) st).length() > 0) && !(st instanceof JSONObject && ((JSONObject) st).length() > 0);
                BigDecimal v = ok ? coinValue(c) : null;
                if (v == null) android.util.Log.i("Dexicon", "auto-cross coin " + id + " not usable: " + String.valueOf(j).substring(0, Math.min(600, String.valueOf(j).length())));
                if (v != null && v.signum() > 0) { got.add(c); presetCoins(token, need, ids, i + 1, got, sum.add(v), cb); }
                else presetCoins(token, need, ids, i + 1, got, sum, cb);
            }
            public void onError(String m) { android.util.Log.i("Dexicon", "auto-cross coin " + id + ": " + m); presetCoins(token, need, ids, i + 1, got, sum, cb); }
        });
    }

    static final class CompositePrep {
        PoolRouter.Route route;
        String payTok;
        BigDecimal needed = BigDecimal.ZERO;
        List<String[]> payments = new ArrayList<>();
        SweepPlanner.Take partial;
        BigDecimal partialRem, partialNewWant;
    }

    static CompositePrep prepareComposite(CompositeRouter.Plan plan, boolean takerBuys) {
        CompositePrep prep = new CompositePrep();
        prep.payTok = takerBuys ? DexContract.MXUSD_ID : Util.MINIMA_TOKENID;
        for (SweepPlanner.Take t : plan.orderTakes) {
            Order5 o = t.order;
            BigDecimal lockedTake = !t.partial ? o.locked
                    : (o.sell ? t.minima
                    : PriceMath.up(t.minima.multiply(o.price(), PriceMath.MC), PriceMath.MXUSD_DP));
            BigDecimal pay = t.partial
                    ? PriceMath.payFor(o.wantAmt, o.locked, lockedTake,
                    o.sell ? PriceMath.MXUSD_DP : PriceMath.MINIMA_DP)
                    : o.wantAmt;
            prep.payments.add(new String[]{pay.toPlainString(), o.wantAddr, o.wantTok});
            prep.needed = prep.needed.add(pay);
            if (t.partial) {
                prep.partial = t;
                prep.partialRem = o.locked.subtract(lockedTake);
                prep.partialNewWant = PriceMath.newWantFor(o.wantAmt, o.locked, prep.partialRem,
                        o.sell ? PriceMath.MXUSD_DP : PriceMath.MINIMA_DP);
            }
        }
        prep.route = plan.poolRoute;
        if (prep.route != null && prep.route.ok) prep.needed = prep.needed.add(prep.route.totalIn);
        return prep;
    }

    private void buildComposite(FundingCoins.Command commands, CompositeRouter.Plan plan, CompositePrep prep, boolean takerBuys,
                                List<JSONObject> coins, String payoutAddress, Result cb) {
        CompositeBuild built = buildCompositeSteps("combo_" + System.nanoTime(), payoutAddress, plan, prep, takerBuys, coins);
        postGated(commands, built.txid, built.steps, built.fundIds, cb);
    }

    static final class CompositeBuild {
        String txid;
        List<String> steps = new ArrayList<>();
        List<String> fundIds = new ArrayList<>();
    }

    static CompositeBuild buildCompositeSteps(String txid, String payoutAddress, CompositeRouter.Plan plan,
                                              CompositePrep prep, boolean takerBuys,
                                              List<JSONObject> coins) {
        PoolRouter.Route route = prep.route;
        BigDecimal fundTotal = BigDecimal.ZERO;
        for (JSONObject c : coins) fundTotal = fundTotal.add(coinValue(c));
        CompositeBuild out = new CompositeBuild();
        out.txid = txid;
        List<String> steps = out.steps;
        steps.add("txncreate id:" + txid);
        if (route != null && route.ok) {
            for (PoolRouter.Alloc a : route.allocs) {
                steps.add("txninput id:" + txid + " coinid:" + a.pool.coinidM);
                steps.add("txninput id:" + txid + " coinid:" + a.pool.coinidT);
            }
        }
        for (SweepPlanner.Take t : plan.orderTakes) steps.add("txninput id:" + txid + " coinid:" + t.order.coinid);
        for (JSONObject c : coins) {
            steps.add("txninput id:" + txid + " coinid:" + c.optString("coinid"));
            out.fundIds.add(c.optString("coinid"));
        }
        if (route != null && route.ok) {
            for (PoolRouter.Alloc a : route.allocs) {
                steps.add("txnoutput id:" + txid + " amount:" + amt(a.quote.newX)
                        + " address:" + a.pool.address + " storestate:false");
                steps.add("txnoutput id:" + txid + " amount:" + amt(a.quote.newY)
                        + " address:" + a.pool.address + " tokenid:" + a.pool.tok + " storestate:false");
            }
        }
        for (String[] p : prep.payments) {
            steps.add("txnoutput id:" + txid + " amount:" + p[0] + " address:" + p[1]
                    + (Util.MINIMA_TOKENID.equals(p[2]) ? "" : " tokenid:" + p[2]) + " storestate:false");
        }
        if (prep.partial != null) {
            Order5 o = prep.partial.order;
            steps.add("txnoutput id:" + txid + " amount:" + prep.partialRem.toPlainString()
                    + " address:" + DexContract.ADDR_V5
                    + (Util.MINIMA_TOKENID.equals(o.lockedTok) ? "" : " tokenid:" + o.lockedTok)
                    + " storestate:true");
        }
        String proceedsTok = takerBuys ? Util.MINIMA_TOKENID : DexContract.MXUSD_ID;
        BigDecimal proceeds = takerBuys ? plan.totalMinima : plan.totalMxusd;
        steps.add("txnoutput id:" + txid + " amount:" + amt(proceeds)
                + " address:" + payoutAddress
                + (Util.MINIMA_TOKENID.equals(proceedsTok) ? "" : " tokenid:" + proceedsTok)
                + " storestate:false");
        BigDecimal change = fundTotal.subtract(prep.needed);
        if (change.signum() > 0) {
            steps.add("txnoutput id:" + txid + " amount:" + amt(change)
                    + " address:" + payoutAddress
                    + (Util.MINIMA_TOKENID.equals(prep.payTok) ? "" : " tokenid:" + prep.payTok) + " storestate:false");
        }
        if (prep.partial != null) {
            Order5 o = prep.partial.order;
            steps.add(stateStep(txid, 0, o.ownerPk));
            steps.add(stateStep(txid, 1, o.wantAddr));
            steps.add(stateStep(txid, 2, prep.partialNewWant.toPlainString()));
            steps.add(stateStep(txid, 3, o.wantTok));
            steps.add(stateStep(txid, 4, o.orderId));
            steps.add(stateStep(txid, 5, o.sell ? "1" : "0"));
            steps.add(stateStep(txid, 6, PriceMath.price(prep.partialNewWant, prep.partialRem).toPlainString()));
            steps.add(stateStep(txid, 7, o.gtc ? "1" : "0"));
            steps.add(stateStep(txid, 8, o.minRem.toPlainString()));
        }
        steps.add("txnsign id:" + txid + " publickey:auto");
        steps.add("txnbasics id:" + txid);
        return out;
    }

    private void ensureTrackedPools(List<PoolRouter.Alloc> allocs, int i, Runnable then, java.util.function.Consumer<String> fail) {
        if (i >= allocs.size()) { then.run(); return; }
        Pool p = allocs.get(i).pool;
        if (!PoolCovenant.validParams(p.opk, p.oadr, p.tok, p.kmin) || !FundingCoins.hex(p.address)) {
            fail.accept("Invalid pool recipe. Nothing was signed."); return;
        }
        String script = PoolCovenant.script(p.opk, p.oadr, p.tok, p.kmin);
        if (p.covenantScript != null && !p.covenantScript.isEmpty() && !script.equals(p.covenantScript)) {
            fail.accept("Pool covenant does not match its recipe. Nothing was signed."); return;
        }
        // Keep pool scripts known for transaction construction without making every reserve
        // coin at that address look wallet-owned/relevant. This is the same load-bearing
        // trackall:false rule as the V5 order covenant.
        node.cmd(poolScriptRegisterCommand(p, script), new NodeApi.Cb() {
            @Override public void onResult(JSONObject j) {
                JSONObject r = j == null ? null : j.optJSONObject("response");
                if (!TxValidation.truthy(j, "status") || r == null || !p.address.equalsIgnoreCase(r.optString("address"))) {
                    fail.accept("Pool registration did not verify. Nothing was signed."); return;
                }
                ensureTrackedPools(allocs, i + 1, then, fail);
            }
            @Override public void onError(String m) { fail.accept(m); }
        });
    }

    static String poolScriptRegisterCommand(Pool p, String script) {
        return "newscript trackall:false script:" + Util.scriptArg(script);
    }

    private static String stateStep(String txid, int port, String value) {
        if (!FundingCoins.hex(value) && (value == null || value.length() > 100
                || !value.matches("[0-9]+(?:\\.[0-9]+)?")))
            throw new IllegalArgumentException("Invalid transaction state literal");
        return "txnstate id:" + txid + " port:" + port + " value:" + value;
    }

    private static String amt(BigDecimal b) { return b.stripTrailingZeros().toPlainString(); }

    // ------------------------------------------------------------------ plumbing

    /**
     * txncheck gate → txnpost → txndelete. Reserves funding coins for the txn's lifetime.
     *
     * Runs behind {@link SignGate}: this app must never have two signing chains in flight at once.
     * Signing one key concurrently makes the node issue the SAME one-time leaf for two different
     * transactions, which leaks that leaf's private key — confirmed on a live node, 7 of 64 keys.
     */
    private void postGated(FundingCoins.Command commands, String txid, List<String> steps, List<String> fundIds, Result cb) {
        List<String> inputs = new ArrayList<>();
        for (String command : steps) {
            String invalid = CommandSafety.failure(command);
            if (invalid != null) { cb.onFailed(invalid); return; }
            if (command.startsWith("txninput ")) {
                String id = "";
                for (String part : command.split("\\s+")) if (part.startsWith("coinid:")) id = part.substring(7);
                if (!FundingCoins.hex(id)) { cb.onFailed("Invalid transaction input. Nothing was signed."); return; }
                inputs.add(id);
            }
        }
        if (inputs.isEmpty() || inputs.size() > FundingCoins.MAX_INPUTS) {
            cb.onFailed("Too many transaction inputs. Consolidate funding or use fewer orders/pools."); return;
        }
        if (!cb.onPrepared(txid)) { cb.onFailed("Could not save transaction intent. Nothing was signed."); return; }
        if (!CoinLock.claimInputs(inputs)) {
            cb.onFailed("An input is already in another queued transaction or appears twice. Wait and refresh."); return;
        }
        progress(cb, "Recovery receipt saved · waiting for signing slot");
        SignGate.submit(gate -> {
            progress(cb, "Building transaction");
            final Result gated = new Result() {
                private boolean completed;
                private void finish(String id, String error) {
                    if (completed) return;
                    completed = true;
                    CoinLock.finishInputs(inputs);
                    try { if (error == null) cb.onPosted(id); else cb.onFailed(error); }
                    finally { gate.free(); }
                }
                public void onProgress(String message) { progress(cb, message); }
                public boolean beforePost() { return cb.beforePost(); }
                public void onPostReply(JSONObject reply) { try { cb.onPostReply(reply); } catch (RuntimeException ignored) {} }   // Dexicon glue
                public void onPosted(String id) { finish(id, null); }
                public void onFailed(String message) { finish(null, message); }
            };
            FundingCoins.Command narrated = (command, callback) -> {
                if (command.startsWith("txnsign ")) progress(gated, "Signing transaction");
                commands.run(command, callback);
            };
            CmdChain.run(narrated, new ArrayList<>(steps), "txndelete id:" + txid, new CmdChain.Done() {
                public void ok(JSONObject last) { checkAndPost(commands, txid, gated); }
                public void fail(String message) { gated.onFailed(message); }
            });
        });
    }

    private void checkAndPost(FundingCoins.Command commands, String txid, Result cb) {
        progress(cb, "Checking scripts, amounts and input proofs");
        commands.run("txncheck id:" + txid, new NodeApi.Cb() {
            public void onResult(JSONObject checked) {
                        String invalid = TxValidation.checkFailure(checked);
                if (invalid != null) {
                    commands.run("txndelete id:" + txid, null); cb.onFailed(invalid); return;
                }
                if (!cb.beforePost()) {
                    commands.run("txndelete id:" + txid, null);
                    cb.onFailed("Could not save submission intent. The transaction was not posted."); return;
                }
                // txnexport is NOT the serialized TxPoW, and returning its hex over IPC is costly.
                // The stock node applies the chain's actual TxPoW size limit at txnpost.
                progress(cb, "Checks passed · submitting to your node");
                commands.run("txnpost id:" + txid, new NodeApi.Cb() {
                    public void onResult(JSONObject reply) {
                        if (reply.optBoolean("pending", false)) {
                            cb.onFailed("Awaiting node approval; this transaction has not been submitted."); return;
                        }
                        commands.run("txndelete id:" + txid, null);
                        if (reply.optBoolean("status", false)) { cb.onPostReply(reply); cb.onPosted(Util.extractTxpowid(reply, txid)); }   // Dexicon glue: onPostReply
                        else cb.onFailed(TxValidation.postError(reply));
                    }
                    public void onError(String message) {
                        // Dexicon rule: every txn error path runs txndelete (harmless after an ambiguous post).
                        commands.run("txndelete id:" + txid, null);
                        cb.onFailed(message);
                    }
                });
            }
            public void onError(String message) {
                commands.run("txndelete id:" + txid, null); cb.onFailed(message);
            }
        });
    }

    static void progress(Result callback, String message) {
        try { callback.onProgress(message); }
        catch (RuntimeException ignored) { /* A presentation failure must not interrupt a funds operation. */ }
    }

    static boolean amountOk(BigDecimal value) {
        return value != null && value.signum() > 0 && value.compareTo(PriceMath.MAX_ORDER) <= 0
                && Math.abs((long)value.scale()) <= 44 && value.precision() <= 44;
    }

    static boolean safeOrder(Order5 o) {
        return o != null && FundingCoins.hex(o.coinid) && FundingCoins.hex(o.ownerPk)
                && FundingCoins.hex(o.wantAddr) && FundingCoins.hex(o.wantTok)
                && FundingCoins.hex(o.orderId) && FundingCoins.hex(o.lockedTok)
                && amountOk(o.locked) && amountOk(o.wantAmt) && o.minRem != null
                && o.minRem.signum() >= 0 && o.minRem.scale() <= 44 && o.minRem.precision() <= 44;
    }

    public interface CoinsCb { void found(List<JSONObject> coins); }

    private static BigDecimal coinValue(JSONObject c) { return FundingCoins.coinValue(c); }

    public void findCoins(String tokenid, BigDecimal need, CoinsCb cb) {
        findCoins(tokenid, need, java.util.Collections.emptySet(), 8, cb);
    }

    public void findCoins(String tokenid, BigDecimal need, java.util.Set<String> excludeAddrsLower,
                          int maxInputs, CoinsCb cb) {
        lastFundError = null;
        FundingCoins.select(node::cmd, tokenid, need, excludeAddrsLower, maxInputs, new FundingCoins.Done() {
            public void ok(List<JSONObject> coins, BigDecimal sum) { cb.found(coins); }
            public void fail(String message) { lastFundError = message; cb.found(null); }
        });
    }

    static final class FundingPick {
        final List<JSONObject> coins;
        final java.util.Set<String> presentCoinIds;
        final String error;

        FundingPick(List<JSONObject> coins, java.util.Set<String> presentCoinIds, String error) {
            this.coins = coins;
            this.presentCoinIds = presentCoinIds;
            this.error = error;
        }
    }

    static FundingPick selectFundingCoins(Object resp, BigDecimal need, java.util.Set<String> excludeAddrsLower,
                                          int maxInputs, java.util.Set<String> inflightIds) {
        java.util.Set<String> present = new java.util.LinkedHashSet<>();
        if (!(resp instanceof org.json.JSONArray)) return new FundingPick(null, present, null);
        org.json.JSONArray arr = (org.json.JSONArray) resp;
        List<JSONObject> candidates = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject c = arr.optJSONObject(i);
            if (c == null) continue;
            String coinid = c.optString("coinid");
            present.add(coinid);
            try { FundingCoins.fundingCoin(c); }
            catch (IllegalArgumentException invalid) { return new FundingPick(null, present, invalid.getMessage()); }
            if (c.optBoolean("spent", false)) continue;
            Object st = c.opt("state");
            boolean hasState = st instanceof org.json.JSONArray
                    ? ((org.json.JSONArray) st).length() > 0
                    : st instanceof JSONObject && ((JSONObject) st).length() > 0;
            if (hasState) continue;
            String addr = c.optString("address", "");
            if (addr != null && excludeAddrsLower != null && excludeAddrsLower.contains(addr.toLowerCase())) continue;
            if (inflightIds != null && inflightIds.contains(coinid)) continue;
            candidates.add(c);
        }
        candidates.sort((a, b) -> coinValue(b).compareTo(coinValue(a)));
        List<JSONObject> pick = new ArrayList<>();
        BigDecimal sum = BigDecimal.ZERO;
        for (JSONObject c : candidates) {
            pick.add(c);
            sum = sum.add(coinValue(c));
            if (pick.size() > maxInputs) {
                return new FundingPick(null, present,
                        "Wallet funding needs more than " + maxInputs + " inputs — consolidate coins and retry.");
            }
            if (sum.compareTo(need) >= 0) return new FundingPick(pick, present, null);
        }
        return new FundingPick(null, present, null);
    }

    /** Set when the last findCoins failure had a specific cause worth showing the user. */
    private volatile String lastFundError;

    public String takeFundError() {
        String e = lastFundError;
        lastFundError = null;
        return e;
    }
}
