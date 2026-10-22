package com.eurobuddha.dexicon.maker;

import com.eurobuddha.dexicon.DexDb;
import com.eurobuddha.dexicon.fill.FillPlan;
import com.eurobuddha.dexicon.fill.Tokens;

import org.json.JSONObject;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Keeps the market-maker ladder live as FAST orders, from the maker service (runs with the app closed, like
 * AtomiX's SwapService peg watcher). One pass every 15 s, or at once after SAVE / STOP:
 *   off → cancel every ladder order;  pegged and no usable MEXC price → withdraw (cancel all) until it is back;
 *   otherwise create missing rungs, edit drifted / part-filled ones (an edit is instant: a higher seq on the relays),
 *   cancel rungs no longer wanted. A rung that cannot be backed because the wallet is one big coin triggers ONE
 *   self-send split into rung-sized coins (all coins stay yours; the rungs go live a block later).
 * Ladder order ids live in DexDb meta "mm_ids" ({rung key: orderId}); manual orders are never touched.
 */
public final class LadderRunner {
    public static final String CONFIG = "mm_config", IDS = "mm_ids", STATUS = "mm_status", DIRTY = "mm_dirty";
    static final long PASS_GAP_MS = 15_000, SPLIT_GAP_MS = 180_000;

    public interface Log { void line(String s); }

    private final MakerEngine engine;
    private final DexDb db;
    private final Log log;
    private long lastPassMs;
    private final Map<String, Long> lastSplit = new HashMap<>();

    public LadderRunner(MakerEngine engine, DexDb db, Log log) { this.engine = engine; this.db = db; this.log = log; }

    public void pass(Runnable done) {
        long now = System.currentTimeMillis();
        boolean dirty = !db.meta(DIRTY, "").isEmpty();
        Ladder.Config c = Ladder.Config.fromJson(db.meta(CONFIG, ""));
        if (c.enabled && c.peg) MexcFeed.refreshAsync();
        if (!dirty && now - lastPassMs < PASS_GAP_MS) { done.run(); return; }
        lastPassMs = now;
        if (dirty) db.setMeta(DIRTY, "");
        JSONObject ids = ids();
        if (!c.enabled) { cancelAll(ids, "OFF", done); return; }
        if (c.peg && MexcFeed.unusable()) { cancelAll(ids, "WITHDRAWN — no usable MEXC price (" + MexcFeed.describe() + "). Re-quotes when the feed is back.", done); return; }
        double widen = c.peg ? MexcFeed.widen() : 1;
        List<Ladder.Rung> want = Ladder.desired(c, c.peg ? MexcFeed.mid() : 0, widen);
        reconcile(c, want, ids, 0, new ArrayList<>(), new ArrayList<>(), done, widen);
    }

    private JSONObject ids() { try { return new JSONObject(db.meta(IDS, "{}")); } catch (Exception e) { return new JSONObject(); } }

    private void reconcile(Ladder.Config c, List<Ladder.Rung> want, JSONObject ids, int i, List<String> errors, List<Ladder.Rung> unbacked, Runnable done, double widen) {
        if (i >= want.size()) { finish(c, want, ids, errors, unbacked, done, widen); return; }
        Ladder.Rung r = want.get(i);
        Runnable next = () -> reconcile(c, want, ids, i + 1, errors, unbacked, done, widen);
        MyOrder o = db.myOrder(ids.optString(r.key, ""));
        String price = FillPlan.plain(r.price), amount = FillPlan.plain(r.amount);
        if (o == null || !o.live() || !o.side.equals(r.side)) {
            if (o != null && o.live()) { engine.cancel(o.orderId, (e, x) -> {}); }
            engine.create(r.side, price, amount, (err, made) -> {
                if (err != null) { errors.add(r.key + ": " + err); if (err.startsWith("not enough free")) unbacked.add(r); }
                else put(ids, r.key, made.orderId);
                next.run();
            });
            return;
        }
        if (Ladder.needsEdit(r, FillPlan.d(o.price), FillPlan.d(o.remaining), c.peg, c.reprice)) {
            engine.edit(o.orderId, price, amount, (err, x) -> {
                if (err != null) { errors.add(r.key + ": " + err); if (err.startsWith("not enough free")) unbacked.add(r); }
                next.run();
            });
            return;
        }
        next.run();
    }

    private void finish(Ladder.Config c, List<Ladder.Rung> want, JSONObject ids, List<String> errors, List<Ladder.Rung> unbacked, Runnable done, double widen) {
        // rungs no longer wanted (fewer levels, a side switched off) → cancel
        List<String> drop = new ArrayList<>();
        for (Iterator<String> it = ids.keys(); it.hasNext(); ) { String k = it.next(); boolean keep = false; for (Ladder.Rung r : want) if (r.key.equals(k)) keep = true; if (!keep) drop.add(k); }
        for (String k : drop) { MyOrder o = db.myOrder(ids.optString(k)); if (o != null && o.live()) engine.cancel(o.orderId, (e, x) -> {}); ids.remove(k); }
        db.setMeta(IDS, ids.toString());
        int live = 0, asks = 0, bids = 0;
        for (Ladder.Rung r : want) { MyOrder o = db.myOrder(ids.optString(r.key, "")); if (o != null && o.live()) { live++; if ("sell".equals(r.side)) asks++; else bids++; } }
        StringBuilder s = new StringBuilder();
        s.append(live > 0 ? "LIVE · " + asks + " ask" + (asks == 1 ? "" : "s") + " · " + bids + " bid" + (bids == 1 ? "" : "s") : "NOT LIVE");
        if (c.peg) s.append(widen > 1.0001 ? " · MEXC feed STALE, spread ×" + new BigDecimal(widen).setScale(1, RoundingMode.HALF_UP).toPlainString() : " · tracking MEXC");
        for (String e : errors) s.append("\n").append(e);
        splitIfShort(unbacked, s, () -> { setStatus(s.toString()); done.run(); });
    }

    /** One self-send per short token, at most every 3 minutes: rung-sized coins so each rung has its own backing. */
    private void splitIfShort(List<Ladder.Rung> unbacked, StringBuilder s, Runnable done) {
        List<Ladder.Rung> asks = new ArrayList<>(), bids = new ArrayList<>();
        for (Ladder.Rung r : unbacked) ("sell".equals(r.side) ? asks : bids).add(r);
        splitSide(asks, true, s, () -> splitSide(bids, false, s, done));
    }
    private void splitSide(List<Ladder.Rung> rungs, boolean asks, StringBuilder s, Runnable done) {
        if (rungs.isEmpty()) { done.run(); return; }
        String tok = asks ? Tokens.MINIMA : Tokens.MXUSD(), name = asks ? "MINIMA" : "MxUSD";
        long now = System.currentTimeMillis();
        Long last = lastSplit.get(tok);
        if (last != null && now - last < SPLIT_GAP_MS) { s.append("\nsplitting ").append(name).append(" coins for the ladder — rungs go live after the split confirms"); done.run(); return; }
        BigDecimal per = BigDecimal.ZERO;
        for (Ladder.Rung r : rungs) {
            BigDecimal give = asks ? r.amount : FillPlan.up8(r.amount.multiply(r.price, FillPlan.MC));
            if (give.compareTo(per) > 0) per = give;
        }
        lastSplit.put(tok, now);
        final BigDecimal fPer = per;
        engine.split(tok, rungs.size(), per, (err, txn) -> {
            if (err != null) { s.append("\ncannot split ").append(name).append(" for the ladder: ").append(err); lastSplit.remove(tok); }
            else { s.append("\nsplit ").append(name).append(" into ").append(rungs.size()).append(" × ").append(FillPlan.plain(fPer)).append(" — rungs go live after one block"); log.line("ladder split " + name + " " + rungs.size() + " × " + FillPlan.plain(fPer)); }
            done.run();
        });
    }

    private void cancelAll(JSONObject ids, String status, Runnable done) {
        List<String> keys = new ArrayList<>(); for (Iterator<String> it = ids.keys(); it.hasNext(); ) keys.add(it.next());
        cancelNext(keys, 0, ids, () -> { db.setMeta(IDS, ids.toString()); setStatus(status); done.run(); });
    }
    private void cancelNext(List<String> keys, int i, JSONObject ids, Runnable done) {
        if (i >= keys.size()) { done.run(); return; }
        String k = keys.get(i);
        MyOrder o = db.myOrder(ids.optString(k));
        ids.remove(k);
        if (o == null || !o.live()) { cancelNext(keys, i + 1, ids, done); return; }
        engine.cancel(o.orderId, (e, x) -> { if (e != null) log.line("ladder cancel " + k + " failed: " + e); cancelNext(keys, i + 1, ids, done); });
    }

    private void setStatus(String s) {
        if (!s.equals(db.meta(STATUS, ""))) log.line("ladder: " + s.replace('\n', ' '));
        db.setMeta(STATUS, s);
    }

    private static void put(JSONObject j, String k, String v) { try { j.put(k, v); } catch (Exception ignored) {} }
}
