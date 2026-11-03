package com.eurobuddha.dexicon.maker;

import com.eurobuddha.dexicon.ActivityLog;
import com.eurobuddha.dexicon.DexDb;
import com.eurobuddha.dexicon.NodeApi;
import com.eurobuddha.dexicon.fill.FillPlan;
import com.eurobuddha.dexicon.fill.Tokens;
import com.eurobuddha.dexicon.resting.CompositeRouter;
import com.eurobuddha.dexicon.resting.DexContract;
import com.eurobuddha.dexicon.resting.DexTxn;
import com.eurobuddha.dexicon.resting.Pool;
import com.eurobuddha.dexicon.resting.PoolLiquidityRepository;
import com.eurobuddha.dexicon.resting.PriceMath;

import org.json.JSONArray;
import org.json.JSONObject;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Auto-cross (owner 2026-10-29): when PandaPools moves past my own FAST ladder — its selling price above my best ask,
 * or its buying price below my best bid — my node trades the pool back to that rung, sized to the rungs it moved past.
 * One pool-only PandaDEX composite transaction (CompositeRouter + DexTxn, the BOOK/POOL route minus the book): it pins
 * the new reserves, so a pool that moved first rejects it whole. The coins it spends may back the crossed rungs — those
 * rungs were the ones filled — and the ladder's "backing coin gone" check refills them on its next pass.
 */
public final class AutoCross {
    public static final String RECENT = "mm_cross", POOL_STATUS = "mm_pool", PENDING = "mm_cross_pending", REVIEW = "mm_review";
    static final BigDecimal KEEP = new BigDecimal("0.995"), MIN_MINIMA = BigDecimal.ONE;
    static final long HOUR_MS = 3_600_000L, FAIL_GAP_MS = 60_000L;
    private static final MathContext MC = new MathContext(40, RoundingMode.HALF_UP);

    /** A cross to make: {@code sell} MINIMA into the pool (else buy), stopping at {@code limit}, at most {@code want}. */
    public static final class Decision {
        public final boolean sell; public final BigDecimal limit, want, gapPct; public final CompositeRouter.Plan plan;
        /** The backing coins of the crossed rungs, best price first — the cross spends these (they are what fills). */
        public final List<String> coins;
        Decision(boolean sell, BigDecimal limit, BigDecimal want, BigDecimal gapPct, CompositeRouter.Plan plan, List<String> coins) {
            this.sell = sell; this.limit = limit; this.want = want; this.gapPct = gapPct; this.plan = plan; this.coins = coins;
        }
        public String line() {
            return "pool " + gapPct.setScale(2, RoundingMode.HALF_UP).toPlainString() + "% " + (sell ? "above your best ask" : "below your best bid")
                    + " — " + (sell ? "sell " : "buy ") + PriceMath.fmt(plan.totalMinima) + " MINIMA @ avg " + plan.effectivePrice.stripTrailingZeros().toPlainString();
        }
    }

    /** Pure. The cross my live rungs call for against these pools, or null when the pool is in line (or it would not pay). */
    public static Decision decide(List<Pool> pools, List<MyOrder> rungs, double mexcMid) {
        if (!(mexcMid > 0) || pools == null || rungs == null) return null;
        BigDecimal hi = null, lo = null;
        for (Pool p : pools) {
            if (!p.funded() || !DexContract.MXUSD_ID.equalsIgnoreCase(p.tok)) continue;
            BigDecimal s = p.spotPrice();
            if (hi == null || s.compareTo(hi) > 0) hi = s;
            if (lo == null || s.compareTo(lo) < 0) lo = s;
        }
        if (hi == null) return null;
        BigDecimal sellAt = hi.multiply(KEEP, MC), buyAt = lo.divide(KEEP, MC);   // what the pool pays / charges after its 0.5 % fee
        BigDecimal bestAsk = null, bestBid = null, askWant = BigDecimal.ZERO, bidCash = BigDecimal.ZERO;
        List<MyOrder> asks = new ArrayList<>(), bids = new ArrayList<>();
        for (MyOrder o : rungs) {
            if (!o.live() || o.coins.isEmpty()) continue;   // an unbacked rung is not on offer: never cross what the ladder does not hold
            BigDecimal px = FillPlan.d(o.price), rem = FillPlan.d(o.remaining);
            if ("sell".equals(o.side) && px.compareTo(sellAt) < 0) { askWant = askWant.add(rem); asks.add(o); if (bestAsk == null || px.compareTo(bestAsk) < 0) bestAsk = px; }
            if ("buy".equals(o.side) && px.compareTo(buyAt) > 0) { bidCash = bidCash.add(px.multiply(rem, MC)); bids.add(o); if (bestBid == null || px.compareTo(bestBid) > 0) bestBid = px; }
        }
        BigDecimal sellGap = bestAsk == null ? null : sellAt.divide(bestAsk, MC).subtract(BigDecimal.ONE);
        BigDecimal buyGap = bestBid == null ? null : bestBid.divide(buyAt, MC).subtract(BigDecimal.ONE);
        if (sellGap == null && buyGap == null) return null;
        boolean sell = buyGap == null || (sellGap != null && sellGap.compareTo(buyGap) >= 0);   // the side further out of line
        // a buy pays at most bestBid per MINIMA, and the crossed bids' coins hold Σ price × remaining: size it so they always cover it
        BigDecimal limit = sell ? bestAsk : bestBid, want = sell ? askWant : bidCash.divide(bestBid, 8, RoundingMode.DOWN);
        CompositeRouter.Plan plan = CompositeRouter.plan(new ArrayList<>(), pools, !sell, want, limit, 0);
        if (plan.isEmpty() || plan.poolMinima.compareTo(MIN_MINIMA) < 0) return null;
        BigDecimal eff = plan.effectivePrice, mexc = BigDecimal.valueOf(mexcMid);
        if (sell ? eff.compareTo(limit) < 0 || eff.compareTo(mexc) <= 0 : eff.compareTo(limit) > 0 || eff.compareTo(mexc) >= 0) return null;
        List<MyOrder> crossed = sell ? asks : bids;
        crossed.sort((a, b) -> sell ? FillPlan.d(a.price).compareTo(FillPlan.d(b.price)) : FillPlan.d(b.price).compareTo(FillPlan.d(a.price)));
        List<String> coins = new ArrayList<>();
        for (MyOrder o : crossed) for (String id : o.coins) if (!coins.contains(id)) coins.add(id);
        return new Decision(sell, limit, want, (sell ? sellGap : buyGap).multiply(BigDecimal.valueOf(100)), plan, coins);
    }

    // ---- the runner (MakerService, after each ladder pass) ----

    private final NodeApi node;
    private final DexDb db;
    private final PoolLiquidityRepository pools;
    private final DexTxn txn;
    private final Runnable changed;
    private boolean inFlight, crossed;
    private long failedMs;

    public AutoCross(NodeApi node, DexDb db, Runnable changed) {
        this.node = node; this.db = db; this.changed = changed;
        pools = new PoolLiquidityRepository(node);
        txn = new DexTxn(node);
    }

    /** One look at the pool; crosses when switched on and every guard passes. {@code done} runs when it is settled. */
    public void run(Runnable done) {
        crossed = false;
        Ladder.Config c = Ladder.Config.fromJson(db.meta(LadderRunner.CONFIG, ""));
        boolean mainnet = Tokens.MXUSD_MAINNET.equalsIgnoreCase(Tokens.MXUSD());   // PandaPools exists for the mainnet MxUSD only
        if (!c.enabled || !mainnet) { db.setMeta(POOL_STATUS, ""); done.run(); return; }
        if (!inFlight && !previousSettled()) { db.setMeta(REVIEW,"1"); db.setMeta(POOL_STATUS,"waiting for the previous cross to confirm. Its outcome is unknown; tap REVIEW to check it.");done.run();return; }
        db.setMeta(REVIEW,"");
        pools.refresh();
        if (!pools.current()) { done.run(); return; }
        Decision d = decide(pools.pools(), rungs(), MexcFeed.mid());
        long now = System.currentTimeMillis();
        List<Long> recent = recent(now);
        int cap = perHour(c);
        String why = !c.autoCross ? " (auto-cross off)" : !MexcFeed.fresh() ? " (waiting: MEXC price not fresh)"
                : recent.size() >= cap ? " (waiting: " + cap + " crosses this hour)" : now - failedMs < FAIL_GAP_MS ? " (retrying shortly)" : "";
        db.setMeta(POOL_STATUS, !(MexcFeed.mid() > 0) ? "waiting for the MEXC price" : d == null ? "pool in line with your ladder" : d.line() + why);
        if (d == null || !why.isEmpty() || inFlight) { done.run(); return; }
        inFlight = true;
        node.cmd("getaddress", new NodeApi.Cb() {
            public void onResult(JSONObject j) {
                JSONObject r = j.optJSONObject("response");
                txn.setPayout(r == null ? "" : r.optString("address", ""));
                if (txn.hexAddr().isEmpty()) { fail("no payout address", null, done); return; }
                execute(d, recent, done);
            }
            public void onError(String m) { fail("getaddress: " + m, null, done); }
        });
    }

    /** Confirmed, or ended (FAILED / REFUSED / LOST / DROPPED) without ever reaching txnpost. mayHavePosted is set durably
     *  just before txnpost and cleared only when the node definitely refused it (DexTxn.Result.onRejected). */
    static boolean safeToRelease(JSONObject receipt) {
        return receipt!=null&&(ActivityLog.confirmed(receipt,0)
                ||(ActivityLog.failedTerminal(receipt)&&!receipt.optBoolean("mayHavePosted",true)));
    }
    // ---------------------------------------------------------------- REVIEW (the owner's way out; never automatic)

    /** One look at a stuck cross: each of its own coins on the node, and what that means. */
    public static final class Review {
        public final JSONObject receipt; public final List<String[]> coins = new ArrayList<>();   // {coinid, state}
        public boolean safe, unspent; public String text = "";
        Review(JSONObject receipt) { this.receipt = receipt; }
    }
    /** unspent = unspent and in no pending transaction; mempool = a pending transaction spends it; spent = the node
     *  returns it in neither lookup; unknown = no usable answer. */
    static void coinState(FundingCommand cmd, String id, java.util.function.Consumer<String> cb) {
        if (id == null || !id.matches("0x[0-9a-fA-F]{64}")) { cb.accept("unknown"); return; }
        cmd.run("coins checkmempool:true coinid:" + id, a -> {
            String x = one(a, id);
            if (x == null) { cb.accept("unknown"); return; }
            if (x.equals("live")) { cb.accept("unspent"); return; }
            cmd.run("coins coinid:" + id, b -> { String y = one(b, id); cb.accept(y == null ? "unknown" : y.equals("live") ? "mempool" : "spent"); });
        });
    }
    private static String one(JSONObject r, String id) {
        if (r == null || !Boolean.TRUE.equals(r.opt("status")) || Boolean.TRUE.equals(r.opt("pending")) || r.optJSONArray("response") == null) return null;
        JSONArray rows = r.optJSONArray("response");
        JSONObject c = rows.length() == 1 ? rows.optJSONObject(0) : null;
        return c != null && id.equalsIgnoreCase(c.optString("coinid")) && Boolean.FALSE.equals(c.opt("spent")) ? "live" : "none";
    }
    /** Reply-or-null adapter over NodeApi, so the decision logic is testable without a node. */
    public interface FundingCommand { void run(String command, java.util.function.Consumer<JSONObject> reply); }
    public static FundingCommand of(NodeApi node) {
        return (c, reply) -> node.cmd(c, new NodeApi.Cb() { public void onResult(JSONObject j) { reply.accept(j); } public void onError(String m) { reply.accept(null); } });
    }
    static void verdict(Review r) {
        boolean any = !r.coins.isEmpty(), unknown = false, mempool = false, allUnspent = any;
        for (String[] c : r.coins) { unknown |= c[1].equals("unknown"); mempool |= c[1].equals("mempool"); allUnspent &= c[1].equals("unspent"); }
        if (!any) { r.safe = true; r.text = "No coins were recorded for it, so your node cannot check it. Release only after checking HISTORY and ASSETS."; }
        else if (unknown) { r.safe = false; r.text = "Your node could not answer for every coin. Try again in a minute."; }
        else if (mempool) { r.safe = false; r.text = "It is still in the mempool. Wait for a block; it settles by itself."; }
        else if (allUnspent) { r.safe = true; r.unspent = true; r.text = "None of its coins were spent and none are pending: it never went through. Releasing frees them for trading again."; }
        else { r.safe = true; r.text = "Its coins were spent, so it went through or was replaced. Releasing lets auto-cross continue; check HISTORY for the result."; }
    }
    /** cb(null) when nothing is waiting. Runs on the node's reply thread. */
    public static void review(FundingCommand cmd, DexDb db, java.util.function.Consumer<Review> cb) {
        String id = db.meta(PENDING, "");
        JSONObject receipt = id.isEmpty() ? null : db.activity(id);
        if (receipt == null || safeToRelease(receipt)) { cb.accept(null); return; }
        Review r = new Review(receipt);
        JSONArray own = receipt.optJSONArray("own") != null && receipt.optJSONArray("own").length() > 0 ? receipt.optJSONArray("own") : receipt.optJSONArray("ins");
        List<String> ids = new ArrayList<>();
        for (int i = 0; own != null && i < own.length(); i++) ids.add(own.optString(i));
        states(cmd, ids, 0, r, () -> { verdict(r); cb.accept(r); });
    }
    private static void states(FundingCommand cmd, List<String> ids, int at, Review r, Runnable done) {
        if (at >= ids.size()) { done.run(); return; }
        coinState(cmd, ids.get(at), st -> { r.coins.add(new String[]{ids.get(at), st}); states(cmd, ids, at + 1, r, done); });
    }
    /** The owner pressed RELEASE on a fresh review. Returns null on success, else why not. */
    public static String release(DexDb db, Review r) {
        if (r == null || !r.safe) return r == null ? "Nothing is waiting any more." : r.text;
        String id = r.receipt.optString("id");
        if (!id.equals(db.meta(PENDING, ""))) return "Something else changed. Review it again.";
        ActivityLog.update(db, id, a -> {
            a.put("released", true).put("reason", "Auto-cross: released by you after review — " + r.text);
            if (r.unspent) a.put("stage", ActivityLog.FAILED).put("mayHavePosted", false);
        });
        if (r.unspent && !r.receipt.optString("handle").isEmpty()) {   // only that transaction's claims, and only its unspent coins
            List<String> all = new ArrayList<>();
            for (String k : new String[]{"ins", "own"}) { JSONArray a = r.receipt.optJSONArray(k); for (int i = 0; a != null && i < a.length(); i++) all.add(a.optString(i)); }
            com.eurobuddha.dexicon.resting.CoinLock.forgetPosting(all, r.receipt.optString("handle"));
        }
        db.setMeta(PENDING, ""); db.setMeta(REVIEW, "");
        return db.meta(PENDING, "missing").isEmpty() ? null : "Could not clear the record. Nothing changed.";
    }

    /** A receipt auto-cross wrote. Manual POOL trades share the route and the "slow_" id, never this flag or reason. */
    static boolean ownReceipt(JSONObject row) {
        return row.optBoolean("auto",false)||row.optString("reason").startsWith("Auto-cross");
    }
    private boolean previousSettled() {
        String pending=db.meta("mm_cross_pending","");
        if(pending.isEmpty()) {
            // Adopt pre-upgrade pending pool receipts; absence of a fresh marker is not settlement evidence.
            for(JSONObject row:ActivityLog.list(db))if("POOL".equals(row.optString("route"))&&ownReceipt(row)&&!row.optBoolean("released",false)
                    &&!ActivityLog.confirmed(row,0)&&(ActivityLog.POSTED.equals(row.optString("stage"))||ActivityLog.BUILDING.equals(row.optString("stage")))){
                pending=row.optString("id");db.setMeta("mm_cross_pending",pending);break;
            }
        }
        if(pending.isEmpty())return true;
        if(!safeToRelease(db.activity(pending)))return false;
        db.setMeta("mm_cross_pending","");return db.meta("mm_cross_pending","missing").isEmpty();
    }
    private boolean stillEnabled(Decision decision) {
        Ladder.Config current=Ladder.Config.fromJson(db.meta(LadderRunner.CONFIG,""));
        if(!current.enabled||!current.autoCross||!MexcFeed.fresh())return false;
        List<String> live=new ArrayList<>();for(MyOrder order:rungs())if(order.live())live.addAll(order.coins);
        return live.containsAll(decision.coins);
    }

    private void execute(Decision d, List<Long> recent, Runnable done) {
        if(!stillEnabled(d)){fail("auto-cross was disabled or its orders changed",null,done);return;}
        final CompositeRouter.Plan plan = d.plan;
        final String actId = ActivityLog.newId("slow_");
        final JSONObject[] posted = {new JSONObject()};
        final BigDecimal edge = plan.effectivePrice.subtract(BigDecimal.valueOf(MexcFeed.mid())).abs().multiply(plan.totalMinima).setScale(8, RoundingMode.DOWN);
        try {
            ActivityLog.add(db, new JSONObject().put("id", actId).put("route", "POOL").put("role", "taker").put("side", d.sell ? "sell" : "buy")
                    .put("amount", PriceMath.fmt(plan.totalMinima)).put("price", plan.effectivePrice.toPlainString()).put("mxusd", PriceMath.fmt(plan.totalMxusd))
                    .put("ts", System.currentTimeMillis()).put("stage", ActivityLog.BUILDING).put("mayHavePosted",false).put("auto",true)
                    .put("own", new JSONArray(d.coins))
                    .put("reason", "Auto-cross: " + d.line() + " · +" + edge.toPlainString() + " MxUSD vs MEXC"));
        } catch (Exception ignored) {}
        db.setMeta("mm_cross_pending",actId);
        if(!actId.equals(db.meta("mm_cross_pending",""))||db.activity(actId)==null){fail("could not save pending trade state",actId,done);return;}
        changed.run();
        txn.fillComposite(plan, !d.sell, d.coins, new DexTxn.Result() {
            @Override public boolean onPrepared(String handle) {   // the transaction handle CoinLock claims its inputs under
                ActivityLog.update(db,actId,a->a.put("handle",handle));
                JSONObject saved=db.activity(actId);
                return saved!=null&&handle.equals(saved.optString("handle"));
            }
            @Override public boolean beforeSign() { return stillEnabled(d); }
            @Override public void onRejected() { ActivityLog.update(db,actId,a->a.put("mayHavePosted",false)); }
            @Override public boolean beforePost() {
                if(!stillEnabled(d))return false;
                ActivityLog.update(db,actId,a->a.put("mayHavePosted",true));
                JSONObject saved=db.activity(actId);
                return saved!=null&&saved.optBoolean("mayHavePosted",false);
            }
            @Override public void onProgress(String message) { ActivityLog.update(db, actId, a -> a.put("reason", "Auto-cross: " + message)); }
            @Override public void onPostReply(JSONObject reply) { posted[0] = ActivityLog.fromPostReply(reply, txn.hexAddr()); }
            @Override public void onPosted(String txpowid) {
                JSONObject ids = posted[0];
                JSONArray outs = ids.optJSONArray("outs") == null ? new JSONArray() : ids.optJSONArray("outs");
                ActivityLog.update(db, actId, a -> a.put("stage", ActivityLog.POSTED).put("reason", "Auto-cross into PandaPools · +" + edge.toPlainString() + " MxUSD vs MEXC")
                        .put("txpowid", txpowid).put("txnid", ids.optString("txnid")).put("outs", outs)
                        .put("ins", ids.optJSONArray("ins") == null ? new JSONArray() : ids.optJSONArray("ins")).put("postedMs", System.currentTimeMillis()));
                try {   // a taker fill against the pool, exactly like a BOOK/POOL trade: HISTORY, the CSV, settled by output coin
                    db.saveFill(new JSONObject().put("role", "taker").put("orderId", "").put("side", d.sell ? "sell" : "buy")
                            .put("price", plan.effectivePrice.toPlainString()).put("filled", PriceMath.fmt(plan.totalMinima))
                            .put("gave", PriceMath.fmt(d.sell ? plan.totalMinima : plan.totalMxusd)).put("got", PriceMath.fmt(d.sell ? plan.totalMxusd : plan.totalMinima))
                            .put("txpowid", txpowid).put("coins", outs).put("counterparty", "POOL · auto-cross").put("ts", MakerEngine.now()).put("status", "settling"));
                } catch (Exception ignored) {}
                recent.add(System.currentTimeMillis());
                db.setMeta(RECENT, new JSONArray(recent).toString());
                inFlight = false; crossed = true;
                changed.run();   // MakerService runs a ladder pass straight after, re-backing the rungs whose coins this spent
                done.run();
            }
            @Override public void onFailed(String message) { fail(message, actId, done); }
        });
    }

    /** The last run() posted a cross: its rungs' coins are spent, so the ladder must re-back them before anything else. */
    public boolean posted() { return crossed; }

    private void fail(String message, String actId, Runnable done) {
        inFlight = false; failedMs = System.currentTimeMillis();
        if (actId != null) ActivityLog.update(db, actId, a -> a.put("stage", ActivityLog.FAILED).put("reason", "Auto-cross: " + message));
        android.util.Log.i("Dexicon", "auto-cross failed: " + message);
        changed.run();
        done.run();
    }

    private List<MyOrder> rungs() {
        List<MyOrder> out = new ArrayList<>();
        try {
            JSONObject ids = new JSONObject(db.meta(LadderRunner.IDS, "{}"));
            for (Iterator<String> it = ids.keys(); it.hasNext(); ) { MyOrder o = db.myOrder(ids.optString(it.next())); if (o != null) out.add(o); }
        } catch (Exception ignored) {}
        return out;
    }

    private List<Long> recent(long now) {
        List<Long> out = new ArrayList<>();
        try { JSONArray a = new JSONArray(db.meta(RECENT, "[]")); for (int i = 0; i < a.length(); i++) if (now - a.getLong(i) < HOUR_MS) out.add(a.getLong(i)); }
        catch (Exception ignored) {}
        return out;
    }

    static int perHour(Ladder.Config c) {
        try { return Math.max(0, Integer.parseInt(c.crossPerHour.trim())); } catch (Exception e) { return 3; }
    }
}
