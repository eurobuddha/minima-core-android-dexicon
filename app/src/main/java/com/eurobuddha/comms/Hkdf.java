// VENDORED VERBATIM from apks/atomix 0.1.83 app/src/main/java/com/eurobuddha/comms/Hkdf.java by scripts/vendor-atomix.sh — do not edit here; re-vendor.
package com.eurobuddha.comms;

import java.nio.charset.StandardCharsets;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * HKDF-SHA256 (RFC 5869) over the platform HMAC — a standard construction, no novel crypto. Used to
 * domain-separate two independent 32-byte sub-seeds (encryption vs signing) from the Minima seed, so
 * the X25519 and Ed25519 identities are deterministic but unrelated.
 */
final class Hkdf {

    static byte[] derive(byte[] ikm, String info, int len) {
        // NI-3: RFC 5869 caps L at 255*HashLen; past it the single-byte counter T(i) wraps and blocks repeat,
        // silently producing weak/duplicate key material. Callers request 32 (one block), so this never fires in
        // practice — but a future caller must fail loudly, not derive a broken key.
        if (len < 0 || len > 255 * 32) throw new IllegalArgumentException("HKDF length out of range: " + len);
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            // Extract: PRK = HMAC(salt=0x00*32, IKM)
            mac.init(new SecretKeySpec(new byte[32], "HmacSHA256"));
            byte[] prk = mac.doFinal(ikm);
            // Expand
            mac.init(new SecretKeySpec(prk, "HmacSHA256"));
            byte[] infoB = info.getBytes(StandardCharsets.UTF_8);
            byte[] okm = new byte[len];
            byte[] t = new byte[0];
            int pos = 0;
            for (int ctr = 1; pos < len; ctr++) {
                mac.update(t);
                mac.update(infoB);
                mac.update((byte) ctr);
                t = mac.doFinal();   // resets the MAC, retains the key
                int n = Math.min(t.length, len - pos);
                System.arraycopy(t, 0, okm, pos, n);
                pos += n;
            }
            return okm;
        } catch (Exception e) {
            throw new RuntimeException("HKDF failed", e);
        }
    }

    private Hkdf() {}
}
