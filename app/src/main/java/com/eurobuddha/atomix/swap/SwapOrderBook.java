// VENDORED VERBATIM from apks/atomix 0.1.83 app/src/main/java/com/eurobuddha/atomix/swap/SwapOrderBook.java by scripts/vendor-atomix.sh — do not edit here; re-vendor.
package com.eurobuddha.atomix.swap;

import com.goterl.lazysodium.LazySodium;
import com.goterl.lazysodium.interfaces.Sign;

import org.json.JSONArray;
import org.json.JSONObject;
import com.eurobuddha.comms.CommsIdentity;
import com.eurobuddha.comms.CommsTransport;
import com.eurobuddha.comms.Hex;
import com.eurobuddha.comms.NodeApi;
import com.eurobuddha.atomix.TradingContext;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * The native order book — the Maxima-free replacement for the bridge's maxsign coin-state book.
 * Orders are broadcast as coin-state at the shared {@link #address()} sentinel: the order JSON in
 * state[99], the maker's Ed25519 public key in state[1], and an Ed25519 signature over the JSON in
 * state[2]. Any peer scans the address, verifies the signature with libsodium (not Maxima maxverify),
 * and builds a local book — freshest order per signer wins.
 */
public final class SwapOrderBook {

    /** The active currency's order-book sentinel (per-currency hex board — MINIMASWAP / USDTSWAP), so AtomiX's
     *  orders land on the SAME shared book the corresponding legacy app used. */
    public static String address() { return TradingContext.active().orderBookAddr; }

    /** ~1 hour of Minima blocks — orders older than this are treated as stale. */
    public static final int SCAN_DEPTH = (60 * 60) / MinimaHtlc.MINIMA_BLOCK_TIME;

    public static final String MARKET_ERROR = "Market changed; refresh and try again.";
    private static boolean current(Order order, long generation) {
        return order != null && order.market != null && order.market == TradingContext.active()
                && generation == PublishGate.gen();
    }
    private SwapOrderBook() {}

    /** Re-read the live SENDABLE Minima balance, stamp it onto {@code base}, then publish — so the advertised
     *  liquidity is never stale (contract-locked coins, e.g. casino, are excluded by the node's `sendable`). */
    public static void publishFresh(NodeApi node, LazySodium ls, CommsIdentity id, Order base, CommsTransport.SendCb cb) {
        publishFresh(node, ls, id, base, PublishGate.gen(), cb);
    }
    /** Keep the caller's generation through ladder preparation and the balance read. */
    public static void publishFresh(NodeApi node, LazySodium ls, CommsIdentity id, Order base, long generation, CommsTransport.SendCb cb) {
        publishFresh(node,ls,id,base,generation,()->true,cb);
    }
    static void publishFresh(NodeApi node, LazySodium ls, CommsIdentity id, Order base, long generation,
                             java.util.function.BooleanSupplier authorized, CommsTransport.SendCb cb) {
        if (!current(base, generation) || !authorized.getAsBoolean()) { cb.onFailed(MARKET_ERROR); return; }
        final String token=TradingContext.active().tokenId;
        node.cmd("balance tokenid:" + token, new NodeApi.Cb() {
            @Override public void onResult(JSONObject j) {
                if (!current(base, generation) || !authorized.getAsBoolean()) { cb.onFailed(MARKET_ERROR); return; }
                final java.math.BigDecimal available;
                try { available=spendableBalance(j,token); requireBoundedCoins(j,token,available); }
                catch(RuntimeException invalid) { cb.onFailed(BALANCE_ERROR);return; }
                if(available.signum()==0) { base.minimaAvail=0; base.clampAsksToBalance(available); publish(node, ls, id, base, generation, authorized, cb); return; }
                node.cmd("coins relevant:true sendable:true tokenid:"+token+" coinage:1 checkmempool:true",new NodeApi.Cb() {
                    @Override public void onResult(JSONObject coins) {
                        if (!current(base, generation) || !authorized.getAsBoolean()) { cb.onFailed(MARKET_ERROR);return; }
                        try {
                            java.math.BigDecimal free=available.min(freeCoinBalance(coins,token));
                            if("0x00".equals(token))free=free.subtract(new java.math.BigDecimal(CommsTransport.MESSAGE_AMOUNT)).max(java.math.BigDecimal.ZERO);
                            base.minimaAvail=free.doubleValue();base.clampAsksToBalance(free);
                        } catch(RuntimeException invalid) { cb.onFailed(BALANCE_ERROR);return; }
                        publish(node, ls, id, base, generation, authorized, cb);
                    }
                    @Override public void onError(String message) { if (!current(base, generation) || !authorized.getAsBoolean()) { cb.onFailed(MARKET_ERROR);return; } cb.onFailed(BALANCE_ERROR); }
                });
            }
            @Override public void onError(String m) { if (!current(base, generation) || !authorized.getAsBoolean()) { cb.onFailed(MARKET_ERROR); return; } cb.onFailed(BALANCE_ERROR); }
        });
    }

    public static final String BALANCE_ERROR = "Could not verify spendable funds. Order was not refreshed.";
    /** Reuse MinimaHtlc.tokenBalance's strict reply shape, requested token and decimal checks. */
    static java.math.BigDecimal spendableBalance(JSONObject reply,String token) {
        try {
            if(reply==null||!Boolean.TRUE.equals(reply.opt("status")))throw new IllegalArgumentException();
            Object response=reply.opt("response");JSONObject row;
            if(response instanceof JSONArray) {
                JSONArray rows=(JSONArray)response;
                if(rows.length()==0)return java.math.BigDecimal.ZERO;
                if(rows.length()!=1)throw new IllegalArgumentException();
                row=rows.getJSONObject(0);
            } else if(response instanceof JSONObject)row=(JSONObject)response;
            else throw new IllegalArgumentException();
            if(!token.equalsIgnoreCase(row.getString("tokenid")))throw new IllegalArgumentException();
            Object raw=row.opt("sendable");
            if(!(raw instanceof String)||((String)raw).length()>128||!((String)raw).matches("[0-9]+(?:\\.[0-9]+)?"))throw new IllegalArgumentException();
            java.math.BigDecimal value=new java.math.BigDecimal((String)raw);
            if(!Double.isFinite(value.doubleValue()))throw new IllegalArgumentException();
            return value;
        } catch(Exception invalid) { throw new IllegalArgumentException(BALANCE_ERROR); }
    }


    /** Same preflight limit as MinimaHtlc.myFreeCoins, before asking a node for coin bodies. */
    private static void requireBoundedCoins(JSONObject reply,String token,java.math.BigDecimal balance) {
        if(balance.signum()==0)return;
        try {
            Object response=reply.get("response");
            JSONObject row=response instanceof JSONArray?((JSONArray)response).getJSONObject(0):(JSONObject)response;
            int count=new java.math.BigDecimal(row.get("coins").toString()).intValueExact();
            if(count<1||count>MinimaHtlc.maxSafeCoinRows(token))throw new IllegalArgumentException();
        }catch(Exception invalid){throw new IllegalArgumentException(BALANCE_ERROR);}
    }
    static java.math.BigDecimal freeCoinBalance(JSONObject reply,String token) {
        try {
            if(reply==null||!Boolean.TRUE.equals(reply.opt("status")))throw new IllegalArgumentException();
            JSONArray coins=reply.getJSONArray("response");
            if(coins.length()>MinimaHtlc.maxSafeCoinRows(token))throw new IllegalArgumentException();
            java.util.Set<String> ids=new java.util.HashSet<>();java.math.BigDecimal sum=java.math.BigDecimal.ZERO;
            for(int i=0;i<coins.length();i++) {
                JSONObject coin=coins.getJSONObject(i);Object rawId=coin.opt("coinid");
                if(!(rawId instanceof String)||!((String)rawId).matches("0x[0-9a-fA-F]{64}")
                        ||new java.math.BigInteger(((String)rawId).substring(2),16).signum()==0
                        ||!ids.add(((String)rawId).toLowerCase(java.util.Locale.ROOT))
                        ||!token.equalsIgnoreCase(coin.getString("tokenid")))throw new IllegalArgumentException();
                Object raw=coin.opt("0x00".equals(token)?"amount":"tokenamount");
                if(!(raw instanceof String)||((String)raw).length()>128||!((String)raw).matches("[0-9]+(?:\\.[0-9]+)?"))throw new IllegalArgumentException();
                java.math.BigDecimal value=new java.math.BigDecimal((String)raw);
                if(value.signum()<=0)throw new IllegalArgumentException();sum=sum.add(value);
            }
            return sum;
        }catch(Exception invalid){throw new IllegalArgumentException(BALANCE_ERROR);}
    }

    /** Sign {@code order} with my comms key and broadcast it to the book. */
    public static void publish(NodeApi node, LazySodium ls, CommsIdentity id, Order order, CommsTransport.SendCb cb) {
        publish(node, ls, id, order, PublishGate.gen(), cb);
    }
    private static void publish(NodeApi node, LazySodium ls, CommsIdentity id, Order order, long generation, CommsTransport.SendCb cb) {
        publish(node,ls,id,order,generation,()->true,cb);
    }
    private static void publish(NodeApi node, LazySodium ls, CommsIdentity id, Order order, long generation,
                                java.util.function.BooleanSupplier authorized, CommsTransport.SendCb cb) {
        if (!current(order, generation) || !authorized.getAsBoolean()) { cb.onFailed(MARKET_ERROR); return; }
        final String destination = order.market.orderBookAddr;
        try {
            order.ts = System.currentTimeMillis();
            order.commsPublicId = id.publicId();
            byte[] msg = order.toJson().toString().getBytes(StandardCharsets.UTF_8);
            byte[] sig = new byte[Sign.BYTES];
            if (!ls.cryptoSignDetached(sig, msg, msg.length, id.signSk)) { cb.onFailed("sign failed"); return; }
            JSONObject extra = new JSONObject();
            extra.put("1", "0x" + Hex.to(id.signPk));
            extra.put("2", "0x" + Hex.to(sig));
            if (!current(order, generation) || !authorized.getAsBoolean()) { cb.onFailed(MARKET_ERROR); return; }
            CommsTransport.postBlob(node, destination, CommsTransport.MESSAGE_AMOUNT, CommsTransport.NATIVE,
                    Hex.to(msg), extra, () -> current(order, generation) && authorized.getAsBoolean(), new CommsTransport.SendCb() {
                        @Override public void onSent(String txpowid) {
                            if (!current(order, generation) || !authorized.getAsBoolean()) { cb.onFailed(MARKET_ERROR); return; }
                            cb.onSent(txpowid);
                        }
                        @Override public void onFailed(String message) { cb.onFailed(message); }
                    });
        } catch (Exception e) {
            cb.onFailed("publish: " + e.getMessage());
        }
    }

    /**
     * Bounded one-shot scan of the order book; verifies signatures and returns freshest-per-signer.
     *
     * The book lives at a SHARED address nobody owns, so Minima returns 0 coins for {@code relevant:false}
     * there until the address is tracked. We therefore {@code coinnotify}-add it first (idempotent), then
     * query BARE (no {@code relevant} flag) — the same pattern the comms scanner uses for the Mail address.
     * The query stays bounded by {@code depth:} (a shared address can be heavy; an unbounded read can crash
     * the node).
     */
    public static void scan(NodeApi node, LazySodium ls, Consumer<Map<String, Order>> ok, Consumer<String> err) {
        final TradingContext market = TradingContext.active();
        node.cmd("coinnotify action:add address:" + market.orderBookAddr, new NodeApi.Cb() {
            @Override public void onResult(JSONObject j) { doScan(node, ls, market, ok, err); }
            @Override public void onError(String m) { doScan(node, ls, market, ok, err); }   // proceed regardless
        });
    }

    private static void doScan(NodeApi node, LazySodium ls, TradingContext market, Consumer<Map<String, Order>> ok, Consumer<String> err) {
        if (market != TradingContext.active()) { err.accept(MARKET_ERROR); return; }
        node.cmd("coins simplestate:true order:desc depth:" + SCAN_DEPTH + " address:" + market.orderBookAddr, new NodeApi.Cb() {
            @Override public void onResult(JSONObject j) {
                if (market != TradingContext.active()) { err.accept(MARKET_ERROR); return; }
                Object resp = j.opt("response");
                JSONArray coins = resp instanceof JSONArray ? (JSONArray) resp : new JSONArray();
                Map<String, Order> book = new LinkedHashMap<>();
                for (int i = 0; i < coins.length(); i++) {
                    JSONObject coin = coins.optJSONObject(i);
                    Order o = verifyCoin(ls, coin);
                    if (o == null) continue;
                    o.market = market;
                    mergeFreshest(book, o);
                }
                if (market != TradingContext.active()) { err.accept(MARKET_ERROR); return; }
                ok.accept(book);
            }
            @Override public void onError(String m) { err.accept(m); }
        });
    }

    /** Freshest-per-signer merge: keep {@code o} only if it's newer (by {@code ts}) than the order already held
     *  for its signer. A withdrawal tombstone (no liquidity) published with the newest ts therefore SUPPRESSES
     *  the maker's older live orders — that is exactly how a maker withdraws from the shared book. */
    static void mergeFreshest(Map<String, Order> book, Order o) {
        Order prev = book.get(o.signerPk);
        if (prev == null || o.ts > prev.ts) book.put(o.signerPk, o);
    }

    /** True iff a maker's CURRENT freshest order still advertises effective liquidity on the side a taker needs
     *  ({@code sellMinima} → the maker's BIDS; a buy → its ASKS). False for a missing or withdrawn/tombstoned
     *  maker. The take-time guard uses this to refuse locking a leg against a maker that has left the book. */
    public static boolean makerLive(Order cur, String sym, boolean sellMinima) {
        if (cur == null) return false;
        return !(sellMinima ? cur.effectiveBids(sym) : cur.effectiveAsks(sym)).isEmpty();
    }

    /** Balance-clamped take cap of a single level, in mxUSDT — how much a taker can actually lift from THIS level
     *  given the maker's advertised balance. A sell (taker sells mxUSDT → maker BUYS with USDT) caps by
     *  {@code usdtAvail/price}; a buy (maker SELLS mxUSDT) caps by {@code minimaAvail}. 0 for a non-positive price.
     *  Used to rank equal-price makers largest-first so a fillable amount uses the fewest swap legs. */
    public static double levelCap(Order o, Order.Level l, boolean sell) {
        if (o == null || l == null || l.price <= 0) return 0;
        return sell ? Math.min(l.amount, o.usdtAvail / l.price) : Math.min(l.amount, o.minimaAvail);
    }

    /** Quantized price key (8dp) so makers quoting the SAME price tie exactly — the basis for ranking equal-price
     *  makers by depth, and it keeps {@link #compareForFill} transitive against float-noise near-ties. */
    public static long pxKey(double price) { return Math.round(price * 1e8); }

    /** Taker's fill order for two book levels: best price first (sell → highest bid, buy → lowest ask), then the
     *  DEEPEST maker first at an EQUAL price so a fillable amount uses the fewest swap legs. Price is never
     *  sacrificed for size. Negative → {@code (oa,la)} ranks before {@code (ob,lb)}. */
    public static int compareForFill(Order oa, Order.Level la, Order ob, Order.Level lb, boolean sell) {
        long ka = la == null ? 0 : pxKey(la.price);   // null-safe like levelCap — a null level ranks worst
        long kb = lb == null ? 0 : pxKey(lb.price);
        int byPrice = sell ? Long.compare(kb, ka) : Long.compare(ka, kb);
        if (byPrice != 0) return byPrice;
        return Double.compare(levelCap(ob, lb, sell), levelCap(oa, la, sell));   // larger cap first
    }

    /** Parse + verify a single order coin. Returns null if missing fields or the signature is invalid. */
    public static Order verifyCoin(LazySodium ls, JSONObject coin) {
        if (coin == null) return null;
        try {
            String orderHex = stateVal(coin, 99);
            String pkHex = stateVal(coin, 1);
            String sigHex = stateVal(coin, 2);
            if (orderHex == null || pkHex == null || sigHex == null) return null;
            byte[] msg = Hex.from(orderHex);
            byte[] pk = Hex.from(pkHex);
            byte[] sig = Hex.from(sigHex);
            if (sig.length != Sign.BYTES || pk.length != Sign.PUBLICKEYBYTES) return null;
            if (!ls.cryptoSignVerifyDetached(sig, msg, msg.length, pk)) return null;
            Order o = Order.fromJson(new JSONObject(new String(msg, StandardCharsets.UTF_8)));
            o.signerPk = "0x" + Hex.to(pk);
            o.coinid = coin.optString("coinid", "");
            return o;
        } catch (Exception e) {
            return null;
        }
    }

    /** Read a coin state value by port, handling both [{port,data}] arrays and simplestate maps. */
    private static String stateVal(JSONObject coin, int port) {
        // simplestate:true → "state" is a JSON object keyed by port string
        JSONObject sm = coin.optJSONObject("state");
        if (sm != null) {
            String v = sm.optString(String.valueOf(port), null);
            return v == null || v.isEmpty() ? null : v;
        }
        JSONArray sa = coin.optJSONArray("state");
        if (sa != null) {
            for (int i = 0; i < sa.length(); i++) {
                JSONObject e = sa.optJSONObject(i);
                if (e != null && e.optInt("port", -1) == port) {
                    String d = e.optString("data", "");
                    return d.isEmpty() ? null : d;
                }
            }
        }
        return null;
    }
}
