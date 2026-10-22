// VENDORED VERBATIM from apks/atomix 0.1.83 app/src/main/java/com/eurobuddha/atomix/swap/SwapTake.java by scripts/vendor-atomix.sh — do not edit here; re-vendor.
package com.eurobuddha.atomix.swap;

import org.json.JSONObject;
import com.eurobuddha.comms.CommsTransport;
import com.eurobuddha.atomix.TradingContext;
import com.eurobuddha.comms.CryptoProvider;
import com.eurobuddha.comms.NodeApi;

import java.nio.charset.StandardCharsets;

/**
 * The buy-mxUSDT handshake channel. When a taker buys mxUSDT it locks USDT first (generating the
 * hashlock), then seals {@code {to, from, hash}} to the maker's comms identity and posts it to {@link
 * #address()}. The maker scans this address, opens the sealed message, and uses the hashlock to find the
 * USDT lock by deterministic contractId = sha256(hash) via getContract — no eth_getLogs, so it works on
 * free RPCs. The hashlock is NOT secret (it's already on-chain in the HTLC); the preimage is never sent.
 */
public final class SwapTake {

    /** "USDTSWAPTAKE" in hex — a sentinel address distinct from the order book + Mail channels. */
    public static String address() { return TradingContext.active().takeAddr; }

    private SwapTake() {}

    /** Taker → maker: seal the hashlock to the maker's publicId and post it to the take channel. */
    public static void send(NodeApi node, CryptoProvider crypto, String makerPublicId, String myPublicId,
                            String hash, CommsTransport.SendCb cb) {
        send(node, crypto, makerPublicId, myPublicId, hash, TradingContext.active(), cb);
    }
    /** A completed lock must notify its original market even if selection has since changed. */
    public static void send(NodeApi node, CryptoProvider crypto, String makerPublicId, String myPublicId,
                            String hash, TradingContext market, CommsTransport.SendCb cb) {
        if (market == null) { cb.onFailed(SwapOrderBook.MARKET_ERROR); return; }
        final String destination = market.takeAddr;
        try {
            JSONObject j = new JSONObject().put("to", makerPublicId).put("from", myPublicId).put("hash", hash);
            String blob = crypto.seal(makerPublicId, j.toString().getBytes(StandardCharsets.UTF_8));
            CommsTransport.postBlob(node, destination, CommsTransport.MESSAGE_AMOUNT, CommsTransport.NATIVE, blob, null, cb);
        } catch (Exception e) {
            cb.onFailed("take send: " + e.getMessage());
        }
    }
}
