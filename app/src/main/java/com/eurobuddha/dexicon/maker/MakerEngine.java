package com.eurobuddha.dexicon.maker;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.eurobuddha.dexicon.NodeApi;
import com.eurobuddha.dexicon.SignGate;
import com.eurobuddha.dexicon.envelope.Envelopes;
import com.eurobuddha.dexicon.fill.Coin;
import com.eurobuddha.dexicon.fill.FillPlan;
import com.eurobuddha.dexicon.fill.Tokens;
import com.eurobuddha.dexicon.identity.DexIdentity;
import com.eurobuddha.dexicon.identity.Hex;
import com.eurobuddha.dexicon.relay.RelayClient;
import com.eurobuddha.dexicon.relay.RelayStore;
import com.eurobuddha.dexicon.DexDb;

import org.json.JSONObject;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

/**
 * MY orders — port of mds/dexicon/lib/maker.js: create / edit / cancel / afterFill / refresh (with the coin-age relock).
 * Backing coins are selected from the wallet's state-free, unspent coins at SIMPLE addresses of the token the order
 * gives away (sell → MINIMA, buy → MxUSD). Node calls go through {@link NodeApi} (main thread); relay publishes run
 * on a worker and call back on the main thread.
 */
public final class MakerEngine {

    public interface Done { void done(String err); }
    public interface OrderDone { void done(String err, MyOrder order); }
    public interface RefreshDone { void done(String err, int republished, String relockTxpowid); }
    public interface CoinsCb { void done(String err, List<Coin> coins); }

    public static final long EXPIRY_SECONDS = 23 * 3600;
    public static final int MAX_COINS = 32;
    private static final Pattern SIMPLE = Pattern.compile("^RETURN SIGNEDBY\\(0x[0-9A-Fa-f]{64}\\)$");
    private static final Pattern DECIMAL = Pattern.compile("^(0|[1-9][0-9]{0,11})(\\.[0-9]{1,8})?$");

    private final Context ctx;
    private final NodeApi node;
    public NodeApi node() { return node; }
    private final DexDb db;
    private final RelayClient relay;
    private final DexIdentity me;
    private final Handler main = new Handler(Looper.getMainLooper());
    // Several workers, so a ladder's orders publish side by side instead of queueing behind each other.
    private final ExecutorService net = Executors.newFixedThreadPool(8, r -> { Thread t = new Thread(r, "dexicon-maker-net"); t.setDaemon(true); return t; });

    public MakerEngine(Context ctx, NodeApi node, DexDb db, RelayClient relay, DexIdentity me) {
        this.ctx = ctx.getApplicationContext(); this.node = node; this.db = db; this.relay = relay; this.me = me;
    }

    public static long now() { return System.currentTimeMillis() / 1000; }
    static long nextSeq(MyOrder o) { long s = now(); return s > o.seq ? s : o.seq + 1; }

    // ---- wallet ----

    /** address(lower) → simple script text, from the node's `scripts` list. */
    public static void simpleScripts(NodeApi node, java.util.function.BiConsumer<String, Map<String, String>> cb) {
        node.cmdR("scripts", new NodeApi.Cb() {
            public void onResult(JSONObject j) {
                Map<String, String> map = new HashMap<>();
                for (JSONObject r : NodeApi.rows(j)) {
                    String a = r.optString("address", ""), s = r.optString("script", "");
                    if (!a.isEmpty() && SIMPLE.matcher(s).matches()) map.put(a.toLowerCase(Locale.ROOT), s);
                }
                cb.accept(null, map);
            }
            public void onError(String m) { cb.accept(m, null); }
        });
    }

    /** Spendable, state-free, unspent coins of one token at SIMPLE addresses (each with .script attached). */
    public static void walletCoins(NodeApi node, String tokenid, CoinsCb cb) {
        simpleScripts(node, (e0, scripts) -> {
            if (e0 != null) { cb.done(e0, null); return; }
            node.cmdR("coins relevant:true tokenid:" + tokenid, new NodeApi.Cb() {
                public void onResult(JSONObject j) {
                    List<Coin> out = new ArrayList<>();
                    for (JSONObject r : NodeApi.rows(j)) {
                        Coin c = Coin.of(r);
                        if (!c.spendable() || !c.sameToken(tokenid)) continue;
                        String sc = scripts.get(c.address.toLowerCase(Locale.ROOT));
                        if (sc == null) continue;
                        c.script = sc; out.add(c);
                    }
                    cb.done(null, out);
                }
                public void onError(String m) { cb.done(m, null); }
            });
        });
    }

    /** Distinct address scripts for a set of coin ids (the order's `scripts`). */
    public static List<String> scriptsFor(List<Coin> coins, List<String> ids) {
        Set<String> want = new HashSet<>(); for (String id : ids) want.add(id.toLowerCase(Locale.ROOT));
        Set<String> out = new LinkedHashSet<>();
        for (Coin c : coins) if (want.contains(c.coinid.toLowerCase(Locale.ROOT)) && c.script != null) out.add(c.script);
        return new ArrayList<>(out);
    }

    public static long ageOf(Coin c, long tip) { return (tip > 0 && c.created > 0) ? Math.max(0, tip - c.created) : 0; }
    public static boolean isFresh(Coin c, long tip, int maxAge) { return ageOf(c, tip) <= maxAge; }

    /** Coin ids to back side/price/amount, excluding coins backing other live orders; only FRESH coins when tip is known. */
    public static List<String> backing(String side, String price, String amount, List<Coin> coins, List<String> excludeIds, long tip, int maxAge) {
        BigDecimal give = "sell".equals(side) ? FillPlan.d(amount) : FillPlan.up8(FillPlan.d(amount).multiply(FillPlan.d(price), FillPlan.MC));
        Set<String> ex = new HashSet<>(); for (String id : excludeIds) ex.add(id.toLowerCase(Locale.ROOT));
        List<Coin> free = new ArrayList<>();
        for (Coin c : coins) if (!ex.contains(c.coinid.toLowerCase(Locale.ROOT)) && isFresh(c, tip, maxAge)) free.add(c);
        FillPlan.Pick p = FillPlan.pick(free, give, MAX_COINS);
        if (p == null) throw new IllegalArgumentException("not enough free, fresh " + ("sell".equals(side) ? "MINIMA" : "MxUSD") + " coins to back " + FillPlan.plain(give) + ("sell".equals(side) ? " MINIMA" : " MxUSD"));
        List<String> ids = new ArrayList<>(); for (Coin c : p.coins) ids.add(c.coinid);
        return ids;
    }

    private List<String> coinsBackingOthers(List<MyOrder> list, String exceptId) {
        List<String> ids = new ArrayList<>();
        for (MyOrder o : list) if (o.live() && !o.orderId.equals(exceptId)) ids.addAll(o.coins);
        return ids;
    }

    private void tip(java.util.function.LongConsumer cb) {
        node.cmd("block", new NodeApi.Cb() {
            public void onResult(JSONObject j) { JSONObject r = j.optJSONObject("response"); long t = 0; try { t = Long.parseLong(r == null ? "0" : r.optString("block", "0")); } catch (Exception ignored) {} cb.accept(Math.max(0, t)); }
            public void onError(String m) { cb.accept(0); }
        });
    }

    // ---- market-maker split ----

    /** ONE self-send turning coins of {@code tokenid} into {@code parts} coins of {@code per} (+ change), so each
     *  ladder rung gets its own backing coin. Coins backing live orders are never touched, except those of the orders
     *  in {@code own} (the ladder's rungs, re-backed on the next pass). cb(err, txnId). */
    public void split(String tokenid, int parts, BigDecimal per, Set<String> own, java.util.function.BiConsumer<String, String> cb) {
        walletCoins(node, tokenid, (e, coins) -> {
            if (e != null) { cb.accept(e, null); return; }
            Set<String> ex = new HashSet<>();
            for (MyOrder o : db.myOrders()) if (o.live() && !own.contains(o.orderId)) for (String id : o.coins) ex.add(id.toLowerCase(Locale.ROOT));
            List<Coin> free = new ArrayList<>(); for (Coin c : coins) if (!ex.contains(c.coinid.toLowerCase(Locale.ROOT))) free.add(c);
            BigDecimal need = per.multiply(BigDecimal.valueOf(parts));
            FillPlan.Pick p = FillPlan.pick(free, need, MAX_COINS);
            if (p == null) { BigDecimal have = BigDecimal.ZERO; for (Coin c : free) have = have.add(c.value); cb.accept("only " + FillPlan.plain(have) + " free (not backing an order), the ladder needs " + FillPlan.plain(need), null); return; }
            node.cmdR("getaddress", new NodeApi.Cb() {
                public void onResult(JSONObject j) {
                    JSONObject r = j.optJSONObject("response");
                    String addr = r == null ? "" : r.optString("address", "");
                    if (addr.isEmpty()) { cb.accept("getaddress failed", null); return; }
                    SignGate.submit(release -> {
                        String tok = Tokens.isMinima(tokenid) ? "" : " tokenid:" + tokenid;
                        final String id = "sp" + Long.toString(System.currentTimeMillis(), 36);
                        List<String> steps = new ArrayList<>();
                        steps.add("txncreate id:" + id);
                        for (Coin c : p.coins) steps.add("txninput id:" + id + " coinid:" + c.coinid + " scriptmmr:true");
                        for (int i = 0; i < parts; i++) steps.add("txnoutput id:" + id + " amount:" + FillPlan.plain(per) + " address:" + addr + tok);
                        BigDecimal change = p.sum.subtract(need);
                        if (change.signum() > 0) steps.add("txnoutput id:" + id + " amount:" + FillPlan.plain(change) + " address:" + addr + tok);
                        steps.add("txnsign id:" + id + " publickey:auto");
                        steps.add("txnpost id:" + id);
                        node.seq(steps, new NodeApi.Cb() {
                            public void onResult(JSONObject x) { finish(null); }
                            public void onError(String m) { finish(m); }
                            private void finish(String err) {
                                node.cmd("txndelete id:" + id, new NodeApi.Cb() {   // every path deletes the txn
                                    public void onResult(JSONObject x) { release.free(); cb.accept(err, err == null ? id : null); }
                                    public void onError(String m) { release.free(); cb.accept(err, err == null ? id : null); }
                                });
                            }
                        });
                    });
                }
                public void onError(String m) { cb.accept(m, null); }
            });
        });
    }

    // ---- relock ----

    /** ONE transaction spending exactly these coins back to my own address (same token) so they become fresh. */
    public void relock(List<Coin> coins, String toAddress, java.util.function.BiConsumer<String, String> cb) {
        if (coins.isEmpty()) { cb.accept(null, null); return; }
        SignGate.submit(release -> {
            String tok = coins.get(0).tokenid;
            BigDecimal sum = BigDecimal.ZERO; for (Coin c : coins) sum = sum.add(c.value);
            final String id = "rl" + Long.toString(System.currentTimeMillis(), 36);
            List<String> steps = new ArrayList<>();
            steps.add("txncreate id:" + id);
            for (Coin c : coins) steps.add("txninput id:" + id + " coinid:" + c.coinid + " scriptmmr:true");
            steps.add("txnoutput id:" + id + " amount:" + FillPlan.plain(sum) + " address:" + toAddress + (Tokens.isMinima(tok) ? "" : " tokenid:" + tok));
            steps.add("txnsign id:" + id + " publickey:auto");
            steps.add("txnpost id:" + id);
            final String[] txpowid = {null};
            node.seq(steps, new NodeApi.Cb() {
                public void onResult(JSONObject j) { finish(null); }
                public void onError(String m) { finish(m); }
                private void finish(String err) {
                    node.cmd("txndelete id:" + id, new NodeApi.Cb() {
                        public void onResult(JSONObject x) { release.free(); cb.accept(err, err == null ? "relocked-" + id : null); }
                        public void onError(String m) { release.free(); cb.accept(err, err == null ? "relocked-" + id : null); }
                    });
                }
            });
        });
    }

    // ---- publish ----

    public String envelopeFor(MyOrder o) {
        boolean live = o.live();
        return Envelopes.order(me, Tokens.MARKET, o.side, o.price, live ? o.remaining : "0", live ? o.coins : new ArrayList<>(),
                live ? o.scripts : new ArrayList<>(), o.address, o.orderId, o.seq, o.expiry);
    }

    /** Publish one order's current state to the relays. cb(err) on the main thread. */
    public void publish(MyOrder o, Done cb) {
        final String raw;
        try { raw = envelopeFor(o); } catch (RuntimeException e) { cb.done(e.getMessage()); return; }
        net.execute(() -> {
            List<RelayClient.PublishResult> results = relay.publish(raw);
            int ok = 0; for (RelayClient.PublishResult r : results) if (r.ok) ok++;
            String err = ok > 0 ? null : "no relay accepted the order" + (results.isEmpty() ? " (no relays)" : ": " + results.get(0).error);
            main.post(() -> cb.done(err));
        });
    }

    // ---- batch (the ladder) ----

    /** One ladder change for {@link #batch}: a new order ({@code orderId} null) or a re-price / re-back of a live one. */
    public static final class Op {
        public final String key, orderId, side, price, amount;
        public Op(String key, String orderId, String side, String price, String amount) {
            this.key = key; this.orderId = orderId; this.side = side; this.price = price; this.amount = amount;
        }
    }
    /** Backing chosen for one op: coin ids, or why there are none. */
    public static final class Alloc { public List<String> coins; public String err; }
    public interface BatchDone { void done(Map<String, String> errors, Map<String, MyOrder> made); }

    /** Back every op from ONE wallet snapshot, in order, never giving a coin to two orders: each op may reuse its own
     *  order's coins, never another live order's or a coin an earlier op just took. Pure (tested). */
    public static List<Alloc> allocate(List<Op> ops, List<Coin> minima, List<Coin> mxusd, List<MyOrder> mine, long tip, int maxAge) {
        Set<String> taken = new HashSet<>();
        Map<String, List<String>> own = new HashMap<>();
        for (MyOrder o : mine) if (o.live()) { for (String id : o.coins) taken.add(id.toLowerCase(Locale.ROOT)); own.put(o.orderId, o.coins); }
        List<Alloc> out = new ArrayList<>();
        for (Op op : ops) {
            Alloc a = new Alloc();
            List<String> mineNow = op.orderId == null || !own.containsKey(op.orderId) ? new ArrayList<>() : own.get(op.orderId);
            Set<String> ownLc = new HashSet<>(); for (String id : mineNow) ownLc.add(id.toLowerCase(Locale.ROOT));
            List<String> excl = new ArrayList<>(); for (String id : taken) if (!ownLc.contains(id)) excl.add(id);
            try {
                a.coins = backing(op.side, op.price, op.amount, "sell".equals(op.side) ? minima : mxusd, excl, tip, maxAge);
                taken.removeAll(ownLc);
                for (String id : a.coins) taken.add(id.toLowerCase(Locale.ROOT));
            } catch (RuntimeException e) { a.err = e.getMessage(); }
            out.add(a);
        }
        return out;
    }

    /** The whole ladder in one go (owner 2026-10-26, like the minimaXT overlay's single book update): ONE address, ONE
     *  tip, backing planned in memory from the wallet snapshot the caller already read, every envelope signed, then all
     *  published at once. Edits are saved whatever the relays said (as {@link #edit}); creates only when one accepted. */
    public void batch(List<Op> ops, List<Coin> minima, List<Coin> mxusd, BatchDone cb) {
        Map<String, String> errors = new HashMap<>();
        Map<String, MyOrder> made = new HashMap<>();
        if (ops.isEmpty()) { cb.done(errors, made); return; }
        boolean anyCreate = false; for (Op op : ops) if (op.orderId == null) anyCreate = true;
        java.util.function.Consumer<String> go = addr -> tip(t -> {
            List<Op> norm = new ArrayList<>();
            for (Op op : ops) {
                try {
                    String price = FillPlan.plain(FillPlan.d(op.price)), amount = FillPlan.plain(FillPlan.down8(FillPlan.d(op.amount)));
                    if (!DECIMAL.matcher(price).matches() || FillPlan.d(price).signum() <= 0 || FillPlan.d(amount).signum() <= 0) throw new IllegalArgumentException("price and amount must be positive");
                    norm.add(new Op(op.key, op.orderId, op.side, price, amount));
                } catch (RuntimeException e) { errors.put(op.key, "bad price or amount"); }
            }
            List<Alloc> alloc = allocate(norm, minima, mxusd, db.myOrders(), t, RelayStore.coinMaxAge(ctx));
            List<MyOrder> send = new ArrayList<>(); List<Op> sendOps = new ArrayList<>();
            for (int i = 0; i < norm.size(); i++) {
                Op op = norm.get(i); Alloc a = alloc.get(i);
                if (a.err != null) { errors.put(op.key, a.err); continue; }
                MyOrder o;
                if (op.orderId == null) {
                    if (addr == null || addr.isEmpty()) { errors.put(op.key, "getaddress failed"); continue; }
                    o = new MyOrder();
                    byte[] id = new byte[8]; new SecureRandom().nextBytes(id);
                    o.orderId = "0x" + Hex.to(id); o.side = op.side; o.address = addr; o.status = "live"; o.created = now(); o.seq = now();
                } else {
                    o = db.myOrder(op.orderId);
                    if (o == null || !o.live()) { errors.put(op.key, "order is not live"); continue; }
                    o.seq = nextSeq(o);
                }
                o.price = op.price; o.amount = op.amount; o.remaining = op.amount; o.coins = a.coins;
                o.scripts = scriptsFor("sell".equals(op.side) ? minima : mxusd, a.coins); o.expiry = now() + EXPIRY_SECONDS;
                send.add(o); sendOps.add(op);
            }
            if (send.isEmpty()) { cb.done(errors, made); return; }
            final int[] left = {send.size()};
            for (int i = 0; i < send.size(); i++) {
                final MyOrder o = send.get(i); final Op op = sendOps.get(i);
                publish(o, e -> {   // all in flight together; each answers on the main thread
                    if (op.orderId != null || e == null) db.saveOrder(o);
                    if (e != null) errors.put(op.key, e); else made.put(op.key, o);
                    if (--left[0] == 0) cb.done(errors, made);
                });
            }
        });
        if (!anyCreate) { go.accept(null); return; }
        node.cmdR("getaddress", new NodeApi.Cb() {
            public void onResult(JSONObject j) { JSONObject r = j.optJSONObject("response"); go.accept(r == null ? "" : r.optString("address", "")); }
            public void onError(String m) { go.accept(""); }
        });
    }

    // ---- create / cancel / edit / afterFill ----

    public void create(String side, String priceIn, String amountIn, OrderDone cb) {
        if (!"buy".equals(side) && !"sell".equals(side)) { cb.done("side must be buy or sell", null); return; }
        final String price, amount;
        try { price = FillPlan.plain(FillPlan.d(priceIn)); amount = FillPlan.plain(FillPlan.down8(FillPlan.d(amountIn))); }
        catch (RuntimeException e) { cb.done("bad price or amount", null); return; }
        if (!DECIMAL.matcher(price).matches() || FillPlan.d(price).signum() <= 0) { cb.done("price must be a positive decimal with at most 8 dp", null); return; }
        if (FillPlan.d(amount).signum() <= 0) { cb.done("amount must be positive", null); return; }
        List<MyOrder> list = db.myOrders();
        node.cmdR("getaddress", new NodeApi.Cb() {
            public void onResult(JSONObject j) {
                JSONObject r = j.optJSONObject("response");
                String addr = r == null ? "" : r.optString("address", "");
                if (addr.isEmpty()) { cb.done("getaddress failed", null); return; }
                walletCoins(node, "sell".equals(side) ? Tokens.MINIMA : Tokens.MXUSD(), (e2, coins) -> {
                    if (e2 != null) { cb.done(e2, null); return; }
                    tip(t -> {
                        MyOrder o = new MyOrder();
                        try { o.coins = backing(side, price, amount, coins, coinsBackingOthers(list, null), t, RelayStore.coinMaxAge(ctx)); }
                        catch (RuntimeException e) { cb.done(e.getMessage(), null); return; }
                        byte[] id = new byte[8]; new SecureRandom().nextBytes(id);
                        o.orderId = "0x" + Hex.to(id); o.side = side; o.price = price; o.amount = amount; o.remaining = amount;
                        o.scripts = scriptsFor(coins, o.coins); o.address = addr; o.seq = now(); o.expiry = now() + EXPIRY_SECONDS; o.status = "live"; o.created = now();
                        publish(o, e3 -> { if (e3 != null) { cb.done(e3, null); return; } db.saveOrder(o); cb.done(null, o); });
                    });
                });
            }
            public void onError(String m) { cb.done(m, null); }
        });
    }

    /** Cancel: amount 0 with a higher seq, publish, mark cancelled. */
    public void cancel(String orderId, OrderDone cb) {
        MyOrder o = db.myOrder(orderId);
        if (o == null) { cb.done("unknown order", null); return; }
        o.status = "cancelled"; o.seq = nextSeq(o); o.expiry = now() + 600;
        // "stale seq" = the relay already holds this order at the same or a newer seq — a cancel sent twice in one second.
        publish(o, e -> { db.saveOrder(o); cb.done(e != null && e.endsWith("stale seq") ? null : e, o); });
    }

    /** Edit price and/or amount (null = keep): re-select backing, higher seq, publish. */
    public void edit(String orderId, String priceIn, String amountIn, OrderDone cb) {
        MyOrder o = db.myOrder(orderId);
        if (o == null || !o.live()) { cb.done("order is not live", null); return; }
        final String price = priceIn != null ? FillPlan.plain(FillPlan.d(priceIn)) : o.price;
        final String amount = amountIn != null ? FillPlan.plain(FillPlan.down8(FillPlan.d(amountIn))) : o.remaining;
        if (!DECIMAL.matcher(price).matches() || FillPlan.d(price).signum() <= 0 || FillPlan.d(amount).signum() <= 0) { cb.done("price and amount must be positive", null); return; }
        List<MyOrder> list = db.myOrders();
        walletCoins(node, "sell".equals(o.side) ? Tokens.MINIMA : Tokens.MXUSD(), (e2, coins) -> {
            if (e2 != null) { cb.done(e2, null); return; }
            tip(t -> {
                try { o.coins = backing(o.side, price, amount, coins, coinsBackingOthers(list, orderId), t, RelayStore.coinMaxAge(ctx)); }
                catch (RuntimeException e) { cb.done(e.getMessage(), null); return; }
                o.price = price; o.amount = amount; o.remaining = amount; o.scripts = scriptsFor(coins, o.coins); o.seq = nextSeq(o); o.expiry = now() + EXPIRY_SECONDS;
                publish(o, e3 -> { db.saveOrder(o); cb.done(e3, o); });
            });
        });
    }

    /** After a fill I signed: reduce remaining, drop the spent coins, republish or mark filled. */
    public void afterFill(String orderId, String filled, List<String> spentCoinIds, OrderDone cb) {
        MyOrder o = db.myOrder(orderId);
        if (o == null) { cb.done("unknown order", null); return; }
        BigDecimal rem = FillPlan.d(o.remaining).subtract(FillPlan.d(filled));
        Set<String> spent = new HashSet<>(); for (String s : spentCoinIds) spent.add(s.toLowerCase(Locale.ROOT));
        List<String> keep = new ArrayList<>(); for (String c : o.coins) if (!spent.contains(c.toLowerCase(Locale.ROOT))) keep.add(c);
        o.coins = keep;
        o.remaining = rem.signum() > 0 ? FillPlan.plain(rem) : "0";
        if (rem.signum() <= 0 || FillPlan.dust(rem, FillPlan.d(o.amount))) { o.status = "filled"; o.expiry = now() + 600; }   // dust left over closes the order
        o.seq = nextSeq(o);
        publish(o, e -> { db.saveOrder(o); cb.done(e, o); });
    }

    // ---- refresh (service) ----

    /**
     * For every live order: make sure its coins still exist unspent, are FRESH and cover the remaining amount
     * (re-select if not), re-lock stale backing coins by a self-send, and republish when coins changed or expiry
     * is within 2 hours. cb(err, republishedCount, relockTxpowid).
     */
    public void refresh(RefreshDone cb) {
        List<MyOrder> list = db.myOrders();
        List<MyOrder> live = new ArrayList<>(); for (MyOrder o : list) if (o.live()) live.add(o);
        if (live.isEmpty()) { cb.done(null, 0, null); return; }
        final int maxAge = RelayStore.coinMaxAge(ctx);
        walletCoins(node, Tokens.MINIMA, (eA, minima) -> {
            if (eA != null) { cb.done(eA, 0, null); return; }
            walletCoins(node, Tokens.MXUSD(), (eB, mxusd) -> {
                if (eB != null) { cb.done(eB, 0, null); return; }
                tip(t -> node.cmd("getaddress", new NodeApi.Cb() {
                    public void onResult(JSONObject j) { JSONObject r = j.optJSONObject("response"); relockStale(list, live, minima, mxusd, t, maxAge, r == null ? "" : r.optString("address", ""), cb); }
                    public void onError(String m) { relockStale(list, live, minima, mxusd, t, maxAge, "", cb); }
                }));
            });
        });
    }

    /** 1. stale coins backing live orders → one relock txn per token (they come back fresh after a block). */
    private void relockStale(List<MyOrder> list, List<MyOrder> live, List<Coin> minima, List<Coin> mxusd, long tip, int maxAge, String myAddr, RefreshDone cb) {
        Map<String, List<Coin>> byToken = new HashMap<>();
        Set<String> seen = new HashSet<>();
        for (MyOrder o : live) for (Coin c : ("sell".equals(o.side) ? minima : mxusd))
            if (o.coins.contains(c.coinid) && !isFresh(c, tip, maxAge) && seen.add(c.coinid)) byToken.computeIfAbsent(c.tokenid, k -> new ArrayList<>()).add(c);
        List<List<Coin>> groups = new ArrayList<>(byToken.values());
        final String[] relocked = {null};
        final int[] gi = {0};
        Runnable[] next = new Runnable[1];
        next[0] = () -> {
            if (gi[0] >= groups.size() || myAddr.isEmpty()) { republish(list, live, minima, mxusd, tip, maxAge, relocked[0], cb); return; }
            relock(groups.get(gi[0]++), myAddr, (eR, txp) -> { if (eR == null && txp != null) relocked[0] = txp; next[0].run(); });
        };
        next[0].run();
    }

    /** 2. per live order: re-back if its coins moved / went stale / no longer cover; republish when needed. */
    private void republish(List<MyOrder> list, List<MyOrder> live, List<Coin> minima, List<Coin> mxusd, long tip, int maxAge, String relocked, RefreshDone cb) {
        final int[] i = {0}, n = {0};
        Runnable[] step = new Runnable[1];
        step[0] = () -> {
            if (i[0] >= live.size()) { cb.done(null, n[0], relocked); return; }
            MyOrder o = live.get(i[0]++);
            if (FillPlan.dust(FillPlan.d(o.remaining), FillPlan.d(o.amount))) {   // a dust remainder from before the floor: close it
                o.status = "filled"; o.seq = nextSeq(o); o.expiry = now() + 600;
                publish(o, e -> { db.saveOrder(o); n[0]++; step[0].run(); });
                return;
            }
            List<Coin> wallet = "sell".equals(o.side) ? minima : mxusd;
            Set<String> fresh = new HashSet<>();
            for (Coin c : wallet) if (isFresh(c, tip, maxAge)) fresh.add(c.coinid.toLowerCase(Locale.ROOT));
            boolean stillThere = true;
            for (String c : o.coins) if (!fresh.contains(c.toLowerCase(Locale.ROOT))) stillThere = false;
            List<Coin> backingFresh = new ArrayList<>();
            for (Coin c : wallet) if (o.coins.contains(c.coinid) && isFresh(c, tip, maxAge)) backingFresh.add(c);
            BigDecimal need = "sell".equals(o.side) ? FillPlan.d(o.remaining) : FillPlan.up8(FillPlan.d(o.remaining).multiply(FillPlan.d(o.price), FillPlan.MC));
            boolean covers = FillPlan.pick(backingFresh, need, MAX_COINS) != null;
            boolean needRepub = !stillThere || !covers || (o.expiry - now() < 2 * 3600);
            if (!stillThere || !covers) {
                try { o.coins = backing(o.side, o.price, o.remaining, wallet, coinsBackingOthers(list, o.orderId), tip, maxAge); o.scripts = scriptsFor(wallet, o.coins); }
                catch (RuntimeException e) {
                    // Nothing fresh to back it with right now (e.g. the relock is still confirming): keep the order
                    // but publish it with NO coins — takers skip it; the next refresh re-backs it.
                    o.coins = new ArrayList<>(); o.scripts = new ArrayList<>();
                }
            }
            if (!needRepub) { step[0].run(); return; }
            o.seq = nextSeq(o); o.expiry = now() + EXPIRY_SECONDS;
            publish(o, e -> { db.saveOrder(o); n[0]++; step[0].run(); });
        };
        step[0].run();
    }
}
