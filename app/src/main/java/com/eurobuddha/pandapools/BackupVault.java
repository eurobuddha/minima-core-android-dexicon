// VENDORED VERBATIM from apks/pandapools 0.9.83 app/src/main/java/com/eurobuddha/pandapools/BackupVault.java by scripts/vendor-pandapools.sh — do not edit here; re-vendor.
package com.eurobuddha.pandapools;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * BackupVault — passphrase-based authenticated encryption with a <b>portable, self-describing
 * container</b>. Reuses the apks/wallet vault format for combined pool and node backups.
 * This reader and writer support only the format's fixed KDF work factor; future formats must
 * explicitly negotiate a different work factor rather than accepting an unbounded input.
 *
 * <h3>Scheme</h3>
 * <pre>
 *   masterKey = PBKDF2-HMAC-SHA256(passphrase_utf8, salt, iterations, dkLen=32)
 *   aesKey    = HKDF-Expand-SHA256(masterKey, info, 32)
 *   ciphertext= AES-256-GCM(aesKey, nonce, plaintext, AAD=header)                 // 128-bit tag
 * </pre>
 *
 * <p><b>PBKDF2 is implemented by hand over {@link Mac} HmacSHA256</b> so the derivation is
 * byte-identical on the JVM (unit tests) and on Android — the passphrase is hashed as raw UTF-8
 * bytes, deterministically, everywhere.
 *
 * <h3>Container layout (all big-endian)</h3>
 * <pre>
 *   "MVLT"(4) | version(1) | kdfId(1) | iterations(4) | saltLen(1) | salt | nonceLen(1) | nonce | ct+tag
 * </pre>
 * The entire header is fed to GCM as AAD, so tampering with the KDF parameters, salt or nonce fails
 * authentication (no silent downgrade).
 *
 * <p>Pure Java (JCE only), no Android imports — directly unit-testable off-device.
 */
public final class BackupVault {

    /** Magic marker for a vault container. */
    private static final byte[] MAGIC = new byte[]{'M', 'V', 'L', 'T'};

    /** Container format version. */
    private static final byte VERSION = 1;

    /** KDF id 1 = PBKDF2-HMAC-SHA256 → HKDF-Expand-SHA256. */
    private static final byte KDF_PBKDF2_SHA256 = 1;

    /**
     * Fixed PBKDF2 iteration count shared with the existing MVLT format and JavaScript reader.
     * The authenticated header records this value; unsupported values are rejected.
     */
    public static final int DEFAULT_ITERATIONS = 210_000;

    private static final int SALT_LEN  = 16;   // 128-bit random salt
    private static final int NONCE_LEN = 12;   // 96-bit GCM nonce (recommended)
    private static final int KEY_LEN   = 32;   // AES-256
    private static final int TAG_BITS  = 128;  // GCM tag
    private static final int HASH_LEN  = 32;   // SHA-256 output

    private static final byte[] HKDF_INFO =
            "minima-wallet-vault/aes-256-gcm/v1".getBytes(StandardCharsets.UTF_8);

    private static final SecureRandom RNG = new SecureRandom();

    private BackupVault() {}

    /** Thrown when decryption fails — wrong passphrase, corrupt blob, or tampering. */
    public static final class BadPassphraseException extends GeneralSecurityException {
        public BadPassphraseException(String zMsg) { super(zMsg); }
    }

    // ---------------------------------------------------------------------------------------------
    // Public API
    // ---------------------------------------------------------------------------------------------

    /** Encrypt {@code zPlain} under {@code zPassphrase} at the default iteration count. */
    public static byte[] encrypt(String zPassphrase, byte[] zPlain) throws GeneralSecurityException {
        return encrypt(zPassphrase, zPlain, DEFAULT_ITERATIONS);
    }

    /**
     * Encrypt {@code zPlain} under {@code zPassphrase}, producing a portable self-describing container.
     * A fresh random salt + nonce are generated per call, so two encryptions of the same plaintext
     * differ (and nonce reuse under one key never happens).
     */
    public static byte[] encrypt(String zPassphrase, byte[] zPlain, int zIterations)
            throws GeneralSecurityException {

        if (zIterations != DEFAULT_ITERATIONS)
            throw new GeneralSecurityException("Unsupported backup iteration count");
        byte[] salt  = randomBytes(SALT_LEN);
        byte[] nonce = randomBytes(NONCE_LEN);

        byte[] aesKey = deriveAesKey(zPassphrase, salt, zIterations);
        byte[] header = header(zIterations, salt, nonce);

        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(aesKey, "AES"),
                new GCMParameterSpec(TAG_BITS, nonce));
        cipher.updateAAD(header);
        byte[] ct = cipher.doFinal(zPlain);

        Arrays.fill(aesKey, (byte) 0);

        ByteArrayOutputStream out = new ByteArrayOutputStream(header.length + ct.length);
        out.write(header, 0, header.length);
        out.write(ct, 0, ct.length);
        return out.toByteArray();
    }

    /**
     * Decrypt a container produced by {@link #encrypt}. Throws {@link BadPassphraseException} on a wrong
     * passphrase or any tampering (GCM authentication failure), never returning garbage plaintext.
     */
    public static byte[] decrypt(String zPassphrase, byte[] zContainer) throws GeneralSecurityException {
        if (zContainer == null || zContainer.length < MAGIC.length + 1 + 1 + 4 + 1 + SALT_LEN + 1 + NONCE_LEN) {
            throw new BadPassphraseException("Vault blob too short / not a vault");
        }
        int p = 0;
        for (byte b : MAGIC) {
            if (zContainer[p++] != b) throw new BadPassphraseException("Not a Minima vault blob (bad magic)");
        }
        byte version = zContainer[p++];
        if (version != VERSION) throw new BadPassphraseException("Unsupported vault version: " + version);
        byte kdf = zContainer[p++];
        if (kdf != KDF_PBKDF2_SHA256) throw new BadPassphraseException("Unsupported KDF id: " + kdf);

        int iterations = ((zContainer[p++] & 0xFF) << 24) | ((zContainer[p++] & 0xFF) << 16)
                | ((zContainer[p++] & 0xFF) << 8) | (zContainer[p++] & 0xFF);
        if (iterations != DEFAULT_ITERATIONS) throw new BadPassphraseException("Invalid iteration count");

        int saltLen = zContainer[p++] & 0xFF;
        if (saltLen < 1 || p + saltLen > zContainer.length) throw new BadPassphraseException("Bad salt length");
        byte[] salt = Arrays.copyOfRange(zContainer, p, p + saltLen);
        p += saltLen;

        int nonceLen = zContainer[p++] & 0xFF;
        if (nonceLen < 1 || p + nonceLen > zContainer.length) throw new BadPassphraseException("Bad nonce length");
        byte[] nonce = Arrays.copyOfRange(zContainer, p, p + nonceLen);
        p += nonceLen;

        byte[] header = Arrays.copyOfRange(zContainer, 0, p);
        byte[] ct = Arrays.copyOfRange(zContainer, p, zContainer.length);

        byte[] aesKey = deriveAesKey(zPassphrase, salt, iterations);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(aesKey, "AES"),
                    new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(header);
            return cipher.doFinal(ct);
        } catch (GeneralSecurityException e) {
            // AEADBadTagException etc → wrong passphrase or tampered blob.
            throw new BadPassphraseException("Wrong passphrase or corrupt vault");
        } finally {
            Arrays.fill(aesKey, (byte) 0);
        }
    }

    /** Stream the same authenticated container without buffering a whole node backup on a phone. */
    public static void encryptStream(String passphrase, java.io.InputStream source, java.io.OutputStream destination,
                                     long maximum) throws GeneralSecurityException, java.io.IOException {
        byte[] salt = randomBytes(SALT_LEN), nonce = randomBytes(NONCE_LEN);
        byte[] key = deriveAesKey(passphrase, salt, DEFAULT_ITERATIONS);
        byte[] prefix = header(DEFAULT_ITERATIONS, salt, nonce);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(prefix); destination.write(prefix);
            byte[] buffer = new byte[65536]; long total = 0; int n;
            while ((n = source.read(buffer)) != -1) {
                total += n; if (total > maximum) throw new java.io.IOException("BACKUP_TOO_LARGE");
                byte[] out = cipher.update(buffer, 0, n); if (out != null) destination.write(out);
            }
            destination.write(cipher.doFinal()); destination.flush();
        } finally { Arrays.fill(key, (byte) 0); }
    }

    /** Only use output after this method returns: doFinal authenticates the entire container. */
    public static void decryptStream(String passphrase, java.io.InputStream source, java.io.OutputStream destination,
                                     long maximum) throws GeneralSecurityException, java.io.IOException {
        java.io.DataInputStream in = new java.io.DataInputStream(source);
        byte[] fixed = new byte[11]; in.readFully(fixed);
        for (int i = 0; i < 4; i++) if (fixed[i] != MAGIC[i]) throw new BadPassphraseException("Not an encrypted PandaPools bundle");
        int rounds = java.nio.ByteBuffer.wrap(fixed, 6, 4).getInt();
        // Current format is fixed. Do not let an unauthenticated header request billions of KDF rounds.
        if (fixed[4] != VERSION || fixed[5] != KDF_PBKDF2_SHA256 || rounds != DEFAULT_ITERATIONS || fixed[10] != SALT_LEN)
            throw new BadPassphraseException("Unsupported encrypted backup format");
        byte[] salt = new byte[SALT_LEN]; in.readFully(salt);
        if (in.readUnsignedByte() != NONCE_LEN) throw new BadPassphraseException("Invalid encrypted backup nonce");
        byte[] nonce = new byte[NONCE_LEN]; in.readFully(nonce);
        byte[] key = deriveAesKey(passphrase, salt, rounds);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(header(rounds, salt, nonce));
            byte[] buffer = new byte[65536]; long total = 0; int n;
            while ((n = in.read(buffer)) != -1) {
                total += n; if (total > maximum + 16) throw new java.io.IOException("BACKUP_TOO_LARGE");
                byte[] out = cipher.update(buffer, 0, n); if (out != null) destination.write(out);
            }
            destination.write(cipher.doFinal()); destination.flush();
        } catch (GeneralSecurityException invalid) {
            throw new BadPassphraseException("Wrong password or damaged backup");
        } finally { Arrays.fill(key, (byte) 0); }
    }

    // ---------------------------------------------------------------------------------------------
    // KDF
    // ---------------------------------------------------------------------------------------------

    /** PBKDF2 master key → HKDF-Expand → 32-byte AES key. */
    private static byte[] deriveAesKey(String zPassphrase, byte[] zSalt, int zIterations)
            throws GeneralSecurityException {
        byte[] pw = (zPassphrase == null ? "" : zPassphrase).getBytes(StandardCharsets.UTF_8);
        byte[] master = pbkdf2HmacSha256(pw, zSalt, zIterations, KEY_LEN);
        Arrays.fill(pw, (byte) 0);
        byte[] key = hkdfExpandSha256(master, HKDF_INFO, KEY_LEN);
        Arrays.fill(master, (byte) 0);
        return key;
    }

    /** Deterministic PBKDF2-HMAC-SHA256 (RFC 2898) over raw UTF-8 password bytes. */
    static byte[] pbkdf2HmacSha256(byte[] zPassword, byte[] zSalt, int zIterations, int zDkLen)
            throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(zPassword, "HmacSHA256"));

        int blocks = (zDkLen + HASH_LEN - 1) / HASH_LEN;
        byte[] out = new byte[blocks * HASH_LEN];

        for (int i = 1; i <= blocks; i++) {
            // U1 = HMAC(pw, salt || INT_32_BE(i))
            mac.update(zSalt);
            mac.update((byte) (i >>> 24));
            mac.update((byte) (i >>> 16));
            mac.update((byte) (i >>> 8));
            mac.update((byte) i);
            byte[] u = mac.doFinal();
            byte[] t = u.clone();
            for (int j = 1; j < zIterations; j++) {
                u = mac.doFinal(u);                 // U_{k} = HMAC(pw, U_{k-1})
                for (int k = 0; k < t.length; k++) t[k] ^= u[k];
            }
            System.arraycopy(t, 0, out, (i - 1) * HASH_LEN, HASH_LEN);
        }
        return Arrays.copyOf(out, zDkLen);
    }

    /** HKDF-Expand (RFC 5869) with SHA-256; {@code zLen} must be ≤ 32 (one output block). */
    static byte[] hkdfExpandSha256(byte[] zPrk, byte[] zInfo, int zLen) throws GeneralSecurityException {
        if (zLen > HASH_LEN) throw new IllegalArgumentException("hkdfExpand len must be <= 32");
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(zPrk, "HmacSHA256"));
        mac.update(zInfo);
        mac.update((byte) 0x01);                    // T(1) counter
        byte[] t = mac.doFinal();
        return Arrays.copyOf(t, zLen);
    }

    // ---------------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------------

    private static byte[] header(int zIterations, byte[] zSalt, byte[] zNonce) {
        ByteArrayOutputStream h = new ByteArrayOutputStream();
        h.write(MAGIC, 0, MAGIC.length);
        h.write(VERSION);
        h.write(KDF_PBKDF2_SHA256);
        h.write((zIterations >>> 24) & 0xFF);
        h.write((zIterations >>> 16) & 0xFF);
        h.write((zIterations >>> 8) & 0xFF);
        h.write(zIterations & 0xFF);
        h.write(zSalt.length & 0xFF);
        h.write(zSalt, 0, zSalt.length);
        h.write(zNonce.length & 0xFF);
        h.write(zNonce, 0, zNonce.length);
        return h.toByteArray();
    }

    private static byte[] randomBytes(int zLen) {
        byte[] b = new byte[zLen];
        RNG.nextBytes(b);
        return b;
    }
}
