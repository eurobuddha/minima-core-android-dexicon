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

    private boolean running, again;
    /** This pass follows a SAVE: re-price every rung to the new settings, not only those past the reprice %. */
    private boolean saved;
    private final List<Runnable> waiting = new ArrayList<>();
    public boolean running() { return running; }
    /** Run {@code r} once the current pass has finished (a take must never be co-signed while the ladder re-backs). */
    public void afterPass(Runnable r) { if (running) waiting.add(r); else r.run(); }

    /** One pass. Never two at once: a pass asked for while one runs (a SAVE / STOP kick) runs straight after it. */
    public void pass(Runnable done) {
        if (running) { again = true; done.run(); return; }
        running = true;
        passOnce(() -> {
            running = false;
            List<Runnable> w = new ArrayList<>(waiting); waiting.clear();
            for (Runnable r : w) r.run();
            if (again && !running) { again = false; pass(() -> {}); }
            done.run();
        });
    }

    private void passOnce(Runnable done) {
        long now = System.currentTimeMillis();
        boolean dirty = !db.meta(DIRTY, "").isEmpty();
        Ladder.Config c = Ladder.Config.fromJson(db.meta(CONFIG, ""));
        if (c.enabled && c.peg) MexcFeed.refreshAsync();
        if (c.enabled && c.peg && MexcFeed.firstFetchPending()) { done.run(); return; }   // keep the ladder until the first price lands
        if (!dirty && now - lastPassMs < PASS_GAP_MS) { done.run(); return; }
        lastPassMs = now;
        if (dirty) db.setMeta(DIRTY, "");
        saved = dirty;
        JSONObject ids = ids();
        if (!c.enabled) { cancelAll(ids, "OFF", done); return; }
        if (c.peg && MexcFeed.unusable()) { cancelAll(ids, "WITHDRAWN — no usable MEXC price (" + MexcFeed.describe() + "). Re-quotes when the feed is back.", done); return; }
        double widen = c.peg ? MexcFeed.widen() : 1;
        List<Ladder.Rung> want = Ladder.desired(c, c.peg ? MexcFeed.mid() : 0, widen);
        MakerEngine.walletCoins(engine.node(), Tokens.MINIMA, (ea, mc) -> MakerEngine.walletCoins(engine.node(), Tokens.MXUSD(), (eb, uc) -> {
            if (ea != null || eb != null) {   // no wallet snapshot: nothing can be backed this pass; keep what is live
                List<String> errs = new ArrayList<>(); errs.add("wallet read failed: " + (ea != null ? ea : eb));
                finish(c, want, ids, errs, new ArrayList<>(), done, widen);
                return;
            }
            java.util.Set<String> inWallet = new java.util.HashSet<>();
            for (com.eurobuddha.dexicon.fill.Coin x : mc) inWallet.add(x.coinid.toLowerCase(java.util.Locale.ROOT));
            for (com.eurobuddha.dexicon.fill.Coin x : uc) inWallet.add(x.coinid.toLowerCase(java.util.Locale.ROOT));
            reconcile(c, want, ids, done, widen, inWallet, mc, uc);
        }));
    }

    private JSONObject ids() { try { return new JSONObject(db.meta(IDS, "{}")); } catch (Exception e) { return new JSONObject(); } }

    /** Diff the wanted rungs against the live ones, then send every create / edit as ONE batch (one wallet read, all
     *  published at once). Edits are in place (a higher seq), so a re-price never leaves a gap on the book. */
    private void reconcile(Ladder.Config c, List<Ladder.Rung> want, JSONObject ids, Runnable done, double widen, java.util.Set<String> inWallet,
                           List<com.eurobuddha.dexicon.fill.Coin> minima, List<com.eurobuddha.dexicon.fill.Coin> mxusd) {
        List<MakerEngine.Op> ops = new ArrayList<>();
        Map<String, Ladder.Rung> byKey = new HashMap<>();
        for (Ladder.Rung r : want) {
            byKey.put(r.key, r);
            MyOrder o = db.myOrder(ids.optString(r.key, ""));
            String price = FillPlan.plain(r.price), amount = FillPlan.plain(r.amount);
            if (o == null || !o.live() || !o.side.equals(r.side)) {
                if (o != null && o.live()) engine.cancel(o.orderId, (e, x) -> {});
                ops.add(new MakerEngine.Op(r.key, null, r.side, price, amount));
                continue;
            }
            // a backing coin left the wallet (a split, a fill or an auto-cross spent it) → re-back now. An order the engine
            // already republished with NO coins (nothing fresh to back it) is gone too, or it would never re-back or split.
            boolean gone = o.coins.isEmpty();
            for (String id : o.coins) if (!inWallet.contains(id.toLowerCase(java.util.Locale.ROOT))) gone = true;
            if (gone || Ladder.needsEdit(r, FillPlan.d(o.price), FillPlan.d(o.remaining), c.peg && !saved, c.reprice))
                ops.add(new MakerEngine.Op(r.key, o.orderId, r.side, price, amount));
        }
        engine.batch(ops, minima, mxusd, (errs, made) -> {
            List<String> errors = new ArrayList<>(); List<Ladder.Rung> unbacked = new ArrayList<>();
            for (MakerEngine.Op op : ops) {
                MyOrder m = made.get(op.key);
                if (m != null && op.orderId == null) put(ids, op.key, m.orderId);
                String err = errs.get(op.key);
                if (err != null) { errors.add(op.key + ": " + err); if (err.startsWith("not enough free")) unbacked.add(byKey.get(op.key)); }
            }
            finish(c, want, ids, errors, unbacked, done, widen);
        });
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
        splitIfShort(want, ids, unbacked, s, () -> { setStatus(s.toString()); done.run(); });
    }

    /** A side with an unbackable rung is re-cut into one coin per rung of that side (the ladder's own rung coins
     *  included — manual orders' coins never), at most every 3 minutes per token. */
    private void splitIfShort(List<Ladder.Rung> want, JSONObject ids, List<Ladder.Rung> unbacked, StringBuilder s, Runnable done) {
        java.util.Set<String> own = new java.util.HashSet<>();
        for (Iterator<String> it = ids.keys(); it.hasNext(); ) own.add(ids.optString(it.next()));
        boolean shortAsks = false, shortBids = false;
        for (Ladder.Rung r : unbacked) { if ("sell".equals(r.side)) shortAsks = true; else shortBids = true; }
        List<Ladder.Rung> asks = new ArrayList<>(), bids = new ArrayList<>();
        for (Ladder.Rung r : want) { if ("sell".equals(r.side)) { if (shortAsks) asks.add(r); } else if (shortBids) bids.add(r); }
        splitSide(asks, true, own, s, () -> splitSide(bids, false, own, s, done));
    }
    private void splitSide(List<Ladder.Rung> rungs, boolean asks, java.util.Set<String> own, StringBuilder s, Runnable done) {
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
        engine.split(tok, rungs.size(), per, own, (err, txn) -> {
            if (err != null) { s.append("\ncannot split ").append(name).append(" for the ladder: ").append(err); lastSplit.remove(tok); }
            else { s.append("\nsplit ").append(name).append(" into ").append(rungs.size()).append(" × ").append(FillPlan.plain(fPer)).append(" — rungs go live after one block"); log.line("ladder split " + name + " " + rungs.size() + " × " + FillPlan.plain(fPer)); }
            done.run();
        });
    }

    /** Cancel every ladder order at once (each publish runs in parallel), then report. */
    private void cancelAll(JSONObject ids, String status, Runnable done) {
        List<String> keys = new ArrayList<>(); for (Iterator<String> it = ids.keys(); it.hasNext(); ) keys.add(it.next());
        List<MyOrder> live = new ArrayList<>();
        for (String k : keys) { MyOrder o = db.myOrder(ids.optString(k)); ids.remove(k); if (o != null && o.live()) live.add(o); }
        Runnable end = () -> { db.setMeta(IDS, ids.toString()); setStatus(status); done.run(); };
        if (live.isEmpty()) { end.run(); return; }
        final int[] left = {live.size()};
        for (MyOrder o : live) engine.cancel(o.orderId, (e, x) -> { if (e != null) log.line("ladder cancel " + o.orderId + " failed: " + e); if (--left[0] == 0) end.run(); });
    }

    private void setStatus(String s) {
        if (!s.equals(db.meta(STATUS, ""))) log.line("ladder: " + s.replace('\n', ' '));
        db.setMeta(STATUS, s);
    }

    private static void put(JSONObject j, String k, String v) { try { j.put(k, v); } catch (Exception ignored) {} }
}
