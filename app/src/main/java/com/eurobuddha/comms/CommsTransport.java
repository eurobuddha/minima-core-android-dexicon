// VENDORED VERBATIM from apks/atomix 0.1.82 app/src/main/java/com/eurobuddha/comms/CommsTransport.java by scripts/vendor-atomix.sh — do not edit here; re-vendor.
package com.eurobuddha.comms;

import org.json.JSONObject;

/**
 * miniMall transport (Maxima-free). Two coin shapes:
 *   1. MESSAGE — a sealed order/status/chat blob in state[99], posted as a 1-nano coin to the shared
 *      MINIMERCH address. Everyone monitors it; only the recipient's box key opens it (privacy by encryption).
 *   2. PAYMENT — a real value send to the vendor's receiving address, with the order ref stamped into
 *      state[1] so the inbox can match a payment to its order deterministically.
 * The node is pure transport — it never sees plaintext.
 */
public final class CommsTransport {

    /** "MINIMERCH" in hex — the one shared address every shop's messages go to + monitor. */
    public static final String MINIMERCH_ADDRESS = "0x4D494E494D45524348";

    /** 1 nano-Minima — effectively free, still a clean valid coin carrying a message. */
    public static final String MESSAGE_AMOUNT = "0.000000001";

    /** Supported pay tokens (same as miniMall). */
    public static final String NATIVE = "0x00";
    public static final String USDT   = "0x7D39745FBD29049BE29850B55A18BF550E4D442F930F86266E34193D89042A90";

    public interface SendCb {
        void onSent(String txpowid);
        void onFailed(String message);
    }

    /** Seal a wire payload to a recipient's box key and post it (1 nano) to the shared MINIMERCH address. */
    public static void sendMessage(NodeApi node, CryptoProvider crypto, String toPublicId, byte[] wire, SendCb cb) {
        final String blob;
        try {
            blob = crypto.seal(toPublicId, wire);
        } catch (Exception e) {
            cb.onFailed("encrypt failed: " + e.getMessage());
            return;
        }
        postBlob(node, MINIMERCH_ADDRESS, MESSAGE_AMOUNT, NATIVE, blob, null, cb);
    }

    // MA-9: sendPayment() deleted (dead code — no callers app-wide; inherited from the miniMall port). It built a
    // node `send` command from unvalidated vendorAddress/amount/tokenid, a latent command-injection surface.

    /** Post a (sealed) blob into state[99] at an address with an amount + tokenid (+ optional extra state). */
    public static void postBlob(NodeApi node, String address, String amount, String tokenid,
                                String blobHex, JSONObject extraState, SendCb cb) {
        postBlob(node, address, amount, tokenid, blobHex, extraState, () -> true, cb);
    }
    /** Publication guard is retained through the signing queue and the shared node worker. */
    public static void postBlob(NodeApi node, String address, String amount, String tokenid,
                                String blobHex, JSONObject extraState, java.util.function.BooleanSupplier authorized, SendCb cb) {
        try {
            // MA-9: every value below is interpolated into the `send` command — reject anything that isn't a
            // clean hex address/token/blob or a plain decimal amount, so no value can inject a command parameter.
            if (!isCleanHex(address)) { cb.onFailed("bad post address"); return; }
            if (!isDecimal(amount))   { cb.onFailed("bad post amount"); return; }
            if (!isCleanHex(tokenid)) { cb.onFailed("bad post tokenid"); return; }
            if (!isCleanHex(blobHex)) { cb.onFailed("bad post blob"); return; }
            // MI-5: clone rather than mutate the caller's JSONObject (adding "99" in place surprised a reused extraState).
            JSONObject state = extraState != null ? new JSONObject(extraState.toString()) : new JSONObject();
            // Review MINOR: every extraState VALUE is serialized into the flat `send` command too, so validate
            // them as hex like address/tokenid/blob — the one interpolated field the MA-9 hardening had missed.
            for (java.util.Iterator<String> it = state.keys(); it.hasNext(); ) {
                Object v = state.get(it.next());
                if (!(v instanceof String) || !isCleanHex((String) v)) { cb.onFailed("bad extra state value"); return; }
            }
            state.put("99", "0x" + blobHex);   // hex-typed state value, read back the same way
            post(node, "send amount:" + amount + " address:" + address + " tokenid:" + tokenid + " state:" + state, authorized, cb);
        } catch (Exception e) {
            cb.onFailed(e.getMessage());
        }
    }

    /** True iff {@code s} is a non-empty hex string (optional 0x prefix) — see {@link CommsScanner#isCleanHex}. */
    private static boolean isCleanHex(String s) { return CommsScanner.isCleanHex(s); }

    /** True iff {@code s} is a plain non-negative decimal amount (no space/letter that could inject a parameter). */
    private static boolean isDecimal(String s) { return s != null && s.trim().matches("[0-9]+(\\.[0-9]+)?"); }

    /**
     * Behind {@link SignGate}: `send` signs internally, so it burns a one-time key leaf exactly like a
     * txnsign sequence does. These publishes are the app's highest-frequency signer — one per order
     * publish, OTC publish and tombstone — so leaving them ungated would have defeated the gate.
     */
    private static void post(NodeApi node, String cmd, java.util.function.BooleanSupplier authorized, SendCb cb) {
        SignGate.submit(gate -> {
            if (!NodeApi.authorized(authorized)) {
                gate.free(); cb.onFailed(NodeApi.ERR_COMMAND_CHANGED); return;
            }
            node.cmd(cmd, authorized, new NodeApi.Cb() {
            @Override public void onResult(JSONObject j) {
                gate.free();
                // NodeApi already holds pending/incomplete writes for review; never report them sent.
                if (j == null || !WriteSafety.completeReply(cmd, j)) { cb.onFailed(NodeApi.ERR_WRITE_UNCERTAIN); return; }
                if (Boolean.TRUE.equals(j.opt("status"))) {
                    cb.onSent(WriteSafety.sendReceipt(j));
                } else {
                    cb.onFailed(j.optString("error", "the node rejected the send"));
                }
            }
            @Override public void onError(String message) { gate.free(); cb.onFailed(message); }
            });
        });
    }

    private CommsTransport() {}
}
