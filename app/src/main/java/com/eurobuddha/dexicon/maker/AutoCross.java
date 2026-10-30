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
    public static final String RECENT = "mm_cross", POOL_STATUS = "mm_pool";
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

    private void execute(Decision d, List<Long> recent, Runnable done) {
        final CompositeRouter.Plan plan = d.plan;
        final String actId = ActivityLog.newId("slow_");
        final JSONObject[] posted = {new JSONObject()};
        final BigDecimal edge = plan.effectivePrice.subtract(BigDecimal.valueOf(MexcFeed.mid())).abs().multiply(plan.totalMinima).setScale(8, RoundingMode.DOWN);
        try {
            ActivityLog.add(db, new JSONObject().put("id", actId).put("route", "POOL").put("role", "taker").put("side", d.sell ? "sell" : "buy")
                    .put("amount", PriceMath.fmt(plan.totalMinima)).put("price", plan.effectivePrice.toPlainString()).put("mxusd", PriceMath.fmt(plan.totalMxusd))
                    .put("ts", System.currentTimeMillis()).put("stage", ActivityLog.BUILDING)
                    .put("reason", "Auto-cross: " + d.line() + " · +" + edge.toPlainString() + " MxUSD vs MEXC"));
        } catch (Exception ignored) {}
        changed.run();
        txn.fillComposite(plan, !d.sell, d.coins, new DexTxn.Result() {
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
