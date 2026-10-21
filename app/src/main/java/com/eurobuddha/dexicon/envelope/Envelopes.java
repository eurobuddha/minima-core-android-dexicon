package com.eurobuddha.dexicon.envelope;

import com.eurobuddha.dexicon.identity.DexIdentity;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds SIGNED envelopes (SPEC §3) — the producer side of {@link Envelope#parse}: canonical JSON (sorted keys, no
 * whitespace, sig omitted) → Ed25519 detached signature → the final JSON with "sig" in sorted position, so the raw
 * bytes are deterministic and byte-identical to mds/dexicon/lib/envelope.js sign(). Reference vector:
 * spec/vectors/order-v1-vector.json (VectorTest).
 */
public final class Envelopes {
    private Envelopes() {}

    public static String canonical(Map<String, Object> fields) { return Json.canonicalWithout(fields, "sig"); }

    public static String sign(DexIdentity me, Map<String, Object> fields) {
        String canon = canonical(fields);
        byte[] sig = me.sign(canon.getBytes(StandardCharsets.UTF_8));
        Map<String, Object> withSig = new LinkedHashMap<>(fields);
        withSig.put("sig", "0x" + com.eurobuddha.dexicon.identity.Hex.to(sig));
        return Json.canonicalWithout(withSig, null);
    }

    /** Order envelope. amount "0" = cancel. */
    public static String order(DexIdentity me, String market, String side, String price, String amount, List<String> coins,
                               List<String> scripts, String address, String orderId, long seq, long expiry) {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("v", 1L); f.put("t", "order"); f.put("market", market); f.put("side", side); f.put("price", price);
        f.put("amount", amount); f.put("coins", new ArrayList<Object>(coins)); f.put("scripts", new ArrayList<Object>(scripts == null ? new ArrayList<String>() : scripts));
        f.put("address", address); f.put("orderId", orderId); f.put("seq", seq); f.put("expiry", expiry);
        f.put("pubId", me.publicId()); f.put("signPk", me.signPkHex());
        return sign(me, f);
    }

    /** Msg envelope carrying a sealed blob (bare hex) to a publicId. */
    public static String msg(DexIdentity me, String toPublicId, String blobHex, long expiry) {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("v", 1L); f.put("t", "msg"); f.put("to", toPublicId); f.put("from", me.publicId()); f.put("blob", blobHex); f.put("expiry", expiry);
        return sign(me, f);
    }
}
