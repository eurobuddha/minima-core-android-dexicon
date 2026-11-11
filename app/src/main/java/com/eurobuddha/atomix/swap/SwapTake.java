// VENDORED VERBATIM from apks/atomix 0.2.32 app/src/main/java/com/eurobuddha/atomix/swap/SwapTake.java by scripts/vendor-atomix.sh — do not edit here; re-vendor.
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

    /** Pref holding pending buy requests (hashlocks) for re-arming after a restart. Each entry is
     *  {@code hash@marketKey}: a request arrives on ONE market's take channel and must only ever be re-armed in
     *  that market — an untagged one re-armed after a currency switch was priced against the other market's
     *  ladder. Untagged entries from before 0.1.89 are unattributable and are dropped on the next write. */
    public static final String P_PENDING = "incoming_hashlocks";

    /** HTLC v1.3 (review 7 Nov): a taker's handshake says {@code "htlc":"1.3"} when it can find and claim a lock at the v1.3
     *  address. A maker locks its counter-leg of a USDT-paid take ONLY for such a taker (an older one would never see the
     *  lock and both sides would refund). The hashes are persisted so a restart between handshake and lock keeps it. */
    public static final String HTLC = "1.3";
    public static final String P_HTLC13 = "htlc13_takers";
    public static boolean says13(JSONObject handshake) { return handshake != null && HTLC.equals(handshake.optString("htlc", "")); }
    /** {@code stored} plus {@code hash}, newest last, at most 256 entries. */
    public static String remember13(String stored, String hash) {
        java.util.LinkedHashSet<String> all = parse13(stored);
        all.remove(MinimaHtlc.normKey(hash)); all.add(MinimaHtlc.normKey(hash));
        java.util.List<String> keep = new java.util.ArrayList<>(all);
        return String.join(",", keep.subList(Math.max(0, keep.size() - 256), keep.size()));
    }
    /** Host side of a received handshake: if it says v1.3, persist that and tell the engines. */
    public static void note13(android.content.SharedPreferences prefs, JSONObject handshake, String hash) {
        if (!says13(handshake) || !MinimaHtlc.isHex(hash)) return;
        prefs.edit().putString(P_HTLC13, remember13(prefs.getString(P_HTLC13, ""), hash)).apply();
        SwapEngine.takerUnderstands13(hash);
    }
    /** After a restart: re-arm every persisted v1.3 taker. */
    public static void load13(android.content.SharedPreferences prefs) {
        for (String h : parse13(prefs.getString(P_HTLC13, ""))) SwapEngine.takerUnderstands13(h);
    }
    public static java.util.LinkedHashSet<String> parse13(String stored) {
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
        if (stored != null) for (String e : stored.split(",")) if (MinimaHtlc.isHex(e)) out.add(MinimaHtlc.normKey(e));
        return out;
    }

    /** The pending hashlocks persisted for {@code market}. */
    public static java.util.Set<String> pending(String stored, TradingContext market) {
        java.util.Set<String> out = new java.util.LinkedHashSet<>();
        if (stored == null || market == null) return out;
        String tag = "@" + market.key;
        for (String e : stored.split(","))
            if (e.endsWith(tag) && e.length() > tag.length()) out.add(e.substring(0, e.length() - tag.length()));
        return out;
    }

    /** {@code stored} with {@code add} tagged for {@code market} and {@code remove} (that market's) taken out. */
    public static String edit(String stored, TradingContext market, String add, java.util.Collection<String> remove) {
        java.util.Set<String> all = new java.util.LinkedHashSet<>();
        if (stored != null) for (String e : stored.split(",")) if (e.indexOf('@') > 0) all.add(e);
        if (remove != null) for (String h : remove) all.remove(h + "@" + market.key);
        if (add != null && !add.isEmpty()) all.add(add + "@" + market.key);
        return String.join(",", all);
    }

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
            JSONObject j = new JSONObject().put("to", makerPublicId).put("from", myPublicId).put("hash", hash).put("htlc", HTLC);
            String blob = crypto.seal(makerPublicId, j.toString().getBytes(StandardCharsets.UTF_8));
            CommsTransport.postBlob(node, destination, CommsTransport.MESSAGE_AMOUNT, CommsTransport.NATIVE, blob, null, cb);
        } catch (Exception e) {
            cb.onFailed("take send: " + e.getMessage());
        }
    }

    /** Maker → taker on the same channel: why a buy was not locked ({@code state} = retrying | declined). The
     *  taker accepts it only from the maker it notified, for its own swap; it never moves funds. */
    public static void notice(NodeApi node, CryptoProvider crypto, String takerPublicId, String myPublicId, String hash,
                              String state, String reason, TradingContext market, CommsTransport.SendCb cb) {
        if (market == null) { cb.onFailed(SwapOrderBook.MARKET_ERROR); return; }
        try {
            JSONObject j = new JSONObject().put("to", takerPublicId).put("from", myPublicId).put("hash", hash)
                    .put("notice", state).put("reason", reason);
            String blob = crypto.seal(takerPublicId, j.toString().getBytes(StandardCharsets.UTF_8));
            CommsTransport.postBlob(node, market.takeAddr, CommsTransport.MESSAGE_AMOUNT, CommsTransport.NATIVE, blob, null, cb);
        } catch (Exception e) {
            cb.onFailed("notice send: " + e.getMessage());
        }
    }

    /** Noop callback for fire-and-forget notices. */
    public static final CommsTransport.SendCb QUIET = new CommsTransport.SendCb() {
        @Override public void onSent(String txpowid) { }
        @Override public void onFailed(String message) { com.eurobuddha.atomix.SwapLog.w("notice not sent: " + message); }
    };
}
