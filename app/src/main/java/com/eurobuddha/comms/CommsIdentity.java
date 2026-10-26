// VENDORED VERBATIM from apks/atomix 0.1.84 app/src/main/java/com/eurobuddha/comms/CommsIdentity.java by scripts/vendor-atomix.sh — do not edit here; re-vendor.
package com.eurobuddha.comms;

import com.goterl.lazysodium.LazySodium;
import com.goterl.lazysodium.interfaces.Box;
import com.goterl.lazysodium.interfaces.Sign;

import java.util.Arrays;

/**
 * The native-Minima equivalent of a Maxima {@code mxpublickey}: a pair of keypairs the app owns,
 * deterministically derived from the Minima node seed. X25519 is the encryption key (people seal
 * messages to it); Ed25519 is the signing key (people verify messages came from it).
 *
 * Because it's derived from the seed via {@link Hkdf}, the SAME identity reappears on any device that
 * re-derives from the same seed — recoverable, and consistent across every native app that uses this
 * module (the Mail app today, a native miniMall/pocketShop tomorrow).
 *
 * Published identity = {@code 0x + boxPublicKey + signPublicKey} (64 bytes).
 */
public final class CommsIdentity {

    public final byte[] boxPk, boxSk;     // X25519 — encryption
    public final byte[] signPk, signSk;   // Ed25519 — signing

    private CommsIdentity(byte[] boxPk, byte[] boxSk, byte[] signPk, byte[] signSk) {
        this.boxPk = boxPk; this.boxSk = boxSk; this.signPk = signPk; this.signSk = signSk;
    }

    /**
     * Deterministically derive the identity from the Minima seed bytes, under a per-app HKDF {@code context}
     * (e.g. "usdtswap" / "minimaswap"). The context is what makes the same seed yield a DIFFERENT identity than
     * Mail ("minima-comms") or miniMall ("minimerch") — one node runs all, no key clash. CRITICAL for AtomiX:
     * handshakes are sealed to the maker's identity FROM the order, so to service an order published by usdtSwap /
     * minimaSwap (the whole existing book) AtomiX must derive the SAME identity — i.e. pass the SAME per-currency
     * context those apps used. A single custom context isolates the app from the entire ecosystem.
     */
    public static CommsIdentity fromSeed(LazySodium ls, byte[] minimaSeed, String context) {
        byte[] boxSeed  = Hkdf.derive(minimaSeed, context + "-box-v1",  Box.SEEDBYTES);
        byte[] signSeed = Hkdf.derive(minimaSeed, context + "-sign-v1", Sign.SEEDBYTES);

        byte[] boxPk = new byte[Box.PUBLICKEYBYTES], boxSk = new byte[Box.SECRETKEYBYTES];
        byte[] signPk = new byte[Sign.PUBLICKEYBYTES], signSk = new byte[Sign.SECRETKEYBYTES];
        if (!ls.cryptoBoxSeedKeypair(boxPk, boxSk, boxSeed)) throw new RuntimeException("box seed keypair failed");
        if (!ls.cryptoSignSeedKeypair(signPk, signSk, signSeed)) throw new RuntimeException("sign seed keypair failed");
        return new CommsIdentity(boxPk, boxSk, signPk, signSk);
    }

    /** Reconstruct an identity from raw keys — used when restoring a backup (no seed needed). */
    public static CommsIdentity fromKeys(byte[] boxPk, byte[] boxSk, byte[] signPk, byte[] signSk) {
        return new CommsIdentity(boxPk, boxSk, signPk, signSk);
    }

    /** boxPk || signPk, hex with 0x prefix. This is what you publish / add to contacts / show as a QR. */
    public String publicId() { return "0x" + Hex.to(boxPk) + Hex.to(signPk); }

    /** A published id is well-formed iff it decodes to exactly boxPk(32)+signPk(32) bytes. */
    public static boolean isValidPublicId(String publicId) {
        try {
            return Hex.from(publicId).length == Box.PUBLICKEYBYTES + Sign.PUBLICKEYBYTES;
        } catch (Exception e) {
            return false;
        }
    }

    static byte[] boxPkOf(String publicId) {
        return Arrays.copyOfRange(Hex.from(publicId), 0, Box.PUBLICKEYBYTES);
    }

    static byte[] signPkOf(String publicId) {
        byte[] b = Hex.from(publicId);
        return Arrays.copyOfRange(b, Box.PUBLICKEYBYTES, Box.PUBLICKEYBYTES + Sign.PUBLICKEYBYTES);
    }
}
