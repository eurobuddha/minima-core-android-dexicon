// VENDORED VERBATIM from apks/atomix 0.1.95 app/src/main/java/com/eurobuddha/atomix/swap/OtcBook.java by scripts/vendor-atomix.sh — do not edit here; re-vendor.
package com.eurobuddha.atomix.swap;

import com.goterl.lazysodium.LazySodium;
import com.goterl.lazysodium.interfaces.Sign;

import org.json.JSONArray;
import org.json.JSONObject;
import com.eurobuddha.comms.CommsIdentity;
import com.eurobuddha.comms.CommsTransport;
import com.eurobuddha.atomix.TradingContext;
import com.eurobuddha.comms.Hex;
import com.eurobuddha.comms.NodeApi;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * The OTC availability board — the {@link SwapOrderBook} pattern applied to OTC LP offers. Each LP
 * broadcasts availability (side + size + enable) as signed coin-state at the shared {@link #address()}: the
 * offer JSON in state[99], the LP's Ed25519 public key in state[1], a signature over the JSON in state[2].
 * Peers scan, verify the signature with libsodium, and keep the freshest offer per signer. A disabled
 * (empty) offer is a tombstone that withdraws the LP from the board.
 */
public final class OtcBook {

    /** "USDTSWAPOTCB" in hex — the OTC availability board (distinct from order book / take / OTC chat). */
    public static String address() { return TradingContext.active().otcBoardAddr; }

    /** ~1 hour of Minima blocks — offers older than this are treated as stale. */
    public static final int SCAN_DEPTH = (60 * 60) / MinimaHtlc.MINIMA_BLOCK_TIME;

    private OtcBook() {}

    /** Sign {@code offer} with my comms key and broadcast it to the board. */
    public static void publish(NodeApi node, LazySodium ls, CommsIdentity id, OtcOffer offer, CommsTransport.SendCb cb) {
        final TradingContext market = TradingContext.active();
        final long generation = PublishGate.gen();
        final java.util.function.BooleanSupplier current = () -> market == TradingContext.active() && generation == PublishGate.gen();
        try {
            offer.ts = System.currentTimeMillis();
            offer.commsPublicId = id.publicId();
            byte[] msg = offer.toJson().toString().getBytes(StandardCharsets.UTF_8);
            byte[] sig = new byte[Sign.BYTES];
            if (!ls.cryptoSignDetached(sig, msg, msg.length, id.signSk)) { cb.onFailed("sign failed"); return; }
            JSONObject extra = new JSONObject();
            extra.put("1", "0x" + Hex.to(id.signPk));
            extra.put("2", "0x" + Hex.to(sig));
            CommsTransport.postBlob(node, market.otcBoardAddr, CommsTransport.MESSAGE_AMOUNT, CommsTransport.NATIVE,
                    Hex.to(msg), extra, current, new CommsTransport.SendCb() {
                        @Override public void onSent(String txpowid) {
                            if (!current.getAsBoolean()) { cb.onFailed(NodeApi.ERR_COMMAND_CHANGED); return; }
                            cb.onSent(txpowid);
                        }
                        @Override public void onFailed(String message) { cb.onFailed(message); }
                    });
        } catch (Exception e) {
            cb.onFailed("otc publish: " + e.getMessage());
        }
    }

    /** Bounded one-shot scan of the board; verifies signatures and returns freshest-per-signer. */
    public static void scan(NodeApi node, LazySodium ls, Consumer<Map<String, OtcOffer>> ok, Consumer<String> err) {
        final TradingContext market = TradingContext.active();
        node.cmd("coinnotify action:add address:" + market.otcBoardAddr, new NodeApi.Cb() {
            @Override public void onResult(JSONObject j) { doScan(node, ls, market, ok, err); }
            @Override public void onError(String m) { doScan(node, ls, market, ok, err); }   // proceed regardless
        });
    }

    private static void doScan(NodeApi node, LazySodium ls, TradingContext market, Consumer<Map<String, OtcOffer>> ok, Consumer<String> err) {
        if (market != TradingContext.active()) { err.accept(OtcController.MARKET_ERROR); return; }
        node.cmd("coins simplestate:true order:desc depth:" + SCAN_DEPTH + " address:" + market.otcBoardAddr, new NodeApi.Cb() {
            @Override public void onResult(JSONObject j) {
                if (market != TradingContext.active()) { err.accept(OtcController.MARKET_ERROR); return; }
                Object resp = j.opt("response");
                JSONArray coins = resp instanceof JSONArray ? (JSONArray) resp : new JSONArray();
                Map<String, OtcOffer> book = new LinkedHashMap<>();
                for (int i = 0; i < coins.length(); i++) {
                    OtcOffer o = verifyCoin(ls, coins.optJSONObject(i));
                    if (o == null) continue;
                    o.market = market;
                    OtcOffer prev = book.get(o.signerPk);
                    if (prev == null || o.ts > prev.ts) book.put(o.signerPk, o);
                }
                if (market != TradingContext.active()) { err.accept(OtcController.MARKET_ERROR); return; }
                ok.accept(book);
            }
            @Override public void onError(String m) { err.accept(m); }
        });
    }

    /** Parse + verify a single offer coin. Returns null if missing fields or the signature is invalid. */
    public static OtcOffer verifyCoin(LazySodium ls, JSONObject coin) {
        if (coin == null) return null;
        try {
            String offerHex = stateVal(coin, 99), pkHex = stateVal(coin, 1), sigHex = stateVal(coin, 2);
            if (offerHex == null || pkHex == null || sigHex == null) return null;
            byte[] msg = Hex.from(offerHex), pk = Hex.from(pkHex), sig = Hex.from(sigHex);
            if (sig.length != Sign.BYTES || pk.length != Sign.PUBLICKEYBYTES) return null;
            if (!ls.cryptoSignVerifyDetached(sig, msg, msg.length, pk)) return null;
            OtcOffer o = OtcOffer.fromJson(new JSONObject(new String(msg, StandardCharsets.UTF_8)));
            o.signerPk = "0x" + Hex.to(pk);
            o.coinid = coin.optString("coinid", "");
            return o;
        } catch (Exception e) {
            return null;
        }
    }

    /** Read a coin state value by port, handling both [{port,data}] arrays and simplestate maps. */
    private static String stateVal(JSONObject coin, int port) {
        JSONObject sm = coin.optJSONObject("state");
        if (sm != null) { String v = sm.optString(String.valueOf(port), null); return v == null || v.isEmpty() ? null : v; }
        JSONArray sa = coin.optJSONArray("state");
        if (sa != null) for (int i = 0; i < sa.length(); i++) {
            JSONObject e = sa.optJSONObject(i);
            if (e != null && e.optInt("port", -1) == port) { String d = e.optString("data", ""); return d.isEmpty() ? null : d; }
        }
        return null;
    }
}
