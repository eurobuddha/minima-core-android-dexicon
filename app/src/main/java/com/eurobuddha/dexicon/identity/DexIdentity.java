package com.eurobuddha.dexicon.identity;

import com.goterl.lazysodium.LazySodium;
import com.goterl.lazysodium.interfaces.Box;
import com.goterl.lazysodium.interfaces.Sign;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * The Dexicon identity (SPEC §2): AtomiX's CommsIdentity + LocalEcCryptoProvider merged, derived from the node seed
 * with HKDF context {@link #CONTEXT} ("decks" — a protocol constant from the working title; changing it re-derives
 * every identity). Byte-identical to mds/dexicon/lib/identity.js:
 *
 *   boxSeed  = HKDF(seed, context + "-box-v1", 32)  → X25519 keypair (sealed boxes)
 *   signSeed = HKDF(seed, context + "-sign-v1", 32) → Ed25519 keypair (envelope signatures)
 *   publicId = "0x" + boxPk + signPk
 *
 * Seal: sig = ed25519(myPublicIdBytes || plaintext); payload {f, b, s} (bare hex); blob = crypto_box_seal(payload).
 * Ported from apks/atomix/app/src/main/java/com/eurobuddha/comms/{CommsIdentity,LocalEcCryptoProvider}.java.
 */
public final class DexIdentity {

    public static final String CONTEXT = "decks";

    public final byte[] boxPk, boxSk, signPk, signSk;
    private final LazySodium ls;

    private DexIdentity(LazySodium ls, byte[] boxPk, byte[] boxSk, byte[] signPk, byte[] signSk) {
        this.ls = ls; this.boxPk = boxPk; this.boxSk = boxSk; this.signPk = signPk; this.signSk = signSk;
    }

    /** Native seed-bytes rule (AtomiX): "0x…" → hex-decode, else raw UTF-8 bytes. */
    public static byte[] seedBytes(String seed) {
        return (seed.startsWith("0x") || seed.startsWith("0X")) ? Hex.from(seed) : seed.getBytes(StandardCharsets.UTF_8);
    }

    public static DexIdentity fromSeed(LazySodium ls, byte[] seed, String context) {
        byte[] boxSeed = Hkdf.derive(seed, context + "-box-v1", Box.SEEDBYTES);
        byte[] signSeed = Hkdf.derive(seed, context + "-sign-v1", Sign.SEEDBYTES);
        byte[] boxPk = new byte[Box.PUBLICKEYBYTES], boxSk = new byte[Box.SECRETKEYBYTES];
        byte[] signPk = new byte[Sign.PUBLICKEYBYTES], signSk = new byte[Sign.SECRETKEYBYTES];
        if (!ls.cryptoBoxSeedKeypair(boxPk, boxSk, boxSeed)) throw new RuntimeException("box seed keypair failed");
        if (!ls.cryptoSignSeedKeypair(signPk, signSk, signSeed)) throw new RuntimeException("sign seed keypair failed");
        java.util.Arrays.fill(boxSeed, (byte) 0); java.util.Arrays.fill(signSeed, (byte) 0);   // the keypairs hold what is needed
        return new DexIdentity(ls, boxPk, boxSk, signPk, signSk);
    }

    public static DexIdentity fromSeed(LazySodium ls, String seed) { return fromSeed(ls, seedBytes(seed), CONTEXT); }

    public String publicId() { return "0x" + Hex.to(boxPk) + Hex.to(signPk); }
    public String signPkHex() { return "0x" + Hex.to(signPk); }

    public static boolean isValidPublicId(String id) {
        try { return Hex.from(id).length == Box.PUBLICKEYBYTES + Sign.PUBLICKEYBYTES; } catch (Exception e) { return false; }
    }
    /** Canonical lowercase 0x form so case variants of one key never split a string-keyed map. */
    public static String canonicalId(String id) { return "0x" + Hex.to(Hex.from(id)); }
    static byte[] boxPkOf(String id) { return Arrays.copyOfRange(Hex.from(id), 0, Box.PUBLICKEYBYTES); }
    static byte[] signPkOf(String id) { byte[] b = Hex.from(id); return Arrays.copyOfRange(b, Box.PUBLICKEYBYTES, Box.PUBLICKEYBYTES + Sign.PUBLICKEYBYTES); }

    /** Detached Ed25519 signature over {@code msg} (the envelope's canonical JSON bytes). */
    public byte[] sign(byte[] msg) {
        byte[] sig = new byte[Sign.BYTES];
        if (!ls.cryptoSignDetached(sig, msg, msg.length, signSk)) throw new RuntimeException("sign failed");
        return sig;
    }

    /** seal(toPublicId, plaintext) → bare lowercase hex blob, exactly as identity.js seal(). */
    public String seal(String toPublicId, byte[] plaintext) {
        if (!isValidPublicId(toPublicId)) throw new IllegalArgumentException("seal: bad recipient publicId");
        try {
            String myId = publicId();
            byte[] sig = sign(concat(Hex.from(myId), plaintext));
            JSONObject payload = new JSONObject();
            payload.put("f", myId);
            payload.put("b", Hex.to(plaintext));
            payload.put("s", Hex.to(sig));
            byte[] payloadBytes = payload.toString().getBytes(StandardCharsets.UTF_8);
            byte[] cipher = new byte[payloadBytes.length + Box.SEALBYTES];
            if (!ls.cryptoBoxSeal(cipher, payloadBytes, payloadBytes.length, boxPkOf(toPublicId))) throw new RuntimeException("seal failed");
            return Hex.to(cipher);
        } catch (Exception e) { throw new RuntimeException("seal error: " + e.getMessage(), e); }
    }

    public static final class Opened {
        public final String fromPublicId; public final byte[] plaintext;
        Opened(String from, byte[] plaintext) { this.fromPublicId = from; this.plaintext = plaintext; }
        public String text() { return new String(plaintext, StandardCharsets.UTF_8); }
    }

    /** Open a sealed blob: null unless it was sealed to ME and the inner sender signature verifies (the only
     *  thing a caller may trust — identity.js openValid()). */
    public Opened open(String blobHex) {
        try {
            byte[] cipher = Hex.from(blobHex);
            if (cipher.length <= Box.SEALBYTES) return null;
            byte[] payloadBytes = new byte[cipher.length - Box.SEALBYTES];
            if (!ls.cryptoBoxSealOpen(payloadBytes, cipher, cipher.length, boxPk, boxSk)) return null;
            JSONObject payload = new JSONObject(new String(payloadBytes, StandardCharsets.UTF_8));
            String from = payload.optString("f", "");
            if (!isValidPublicId(from)) return null;
            from = canonicalId(from);
            byte[] body = Hex.from(payload.optString("b", ""));
            byte[] sig = Hex.from(payload.optString("s", ""));
            if (sig.length != Sign.BYTES) return null;
            byte[] signed = concat(Hex.from(from), body);
            return ls.cryptoSignVerifyDetached(sig, signed, signed.length, signPkOf(from)) ? new Opened(from, body) : null;
        } catch (Exception e) { return null; }
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] r = new byte[a.length + b.length];
        System.arraycopy(a, 0, r, 0, a.length);
        System.arraycopy(b, 0, r, a.length, b.length);
        return r;
    }
}
