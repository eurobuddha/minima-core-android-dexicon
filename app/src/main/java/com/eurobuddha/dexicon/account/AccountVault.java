package com.eurobuddha.dexicon.account;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import org.minima.utils.BIP39;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Where the Trading Account's seed lives: AES-256-GCM under a non-exportable Android Keystore key, in app-private prefs
 * that never leave the phone (allowBackup=false). The 24 words are the user's portable backup; this store is the
 * device copy. Two modes, chosen once at setup (owner, 9 Nov: offer both):
 * <ul>
 *   <li>{@code phrase} — Dexicon's own 24 words. Never needs ADMIN, at any point.</li>
 *   <li>{@code node}   — the node's {@code seedrandom modifier:dexicon-account-v1}, read once with a temporary ADMIN
 *       grant. Restorable from the node's own phrase, but on stock Minima Core any enabled companion can reproduce it
 *       (postfrom escalation), so the setup screen says so and phrase mode is the default.</li>
 * </ul>
 */
public final class AccountVault {

    private static final String PREFS = "dexicon_account_vault", KEY_ALIAS = "dexicon_account_v1";
    private static final String P_MODE = "mode", P_CT = "ct", P_IV = "iv";
    public static final String MODE_PHRASE = "phrase", MODE_NODE = "node";

    private final SharedPreferences prefs;

    public AccountVault(Context c) { prefs = c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE); }

    public boolean exists() { return !prefs.getString(P_CT, "").isEmpty(); }
    public String mode() { return prefs.getString(P_MODE, ""); }

    /** A brand-new 24-word phrase (not yet stored — the user confirms they wrote it down first). */
    public static String newPhrase() { return BIP39.convertWordListToString(BIP39.getNewWordList()); }

    /** Normalise and validate a typed phrase; throws if it is not 24 BIP39 words. */
    public static String cleanPhrase(String typed) {
        String p = BIP39.cleanSeedPhrase(typed == null ? "" : typed);
        if (p.trim().split("\\s+").length != 24) throw new IllegalArgumentException("A Trading Account phrase is 24 words.");
        return p;
    }

    public void storePhrase(String phrase) throws Exception { store(MODE_PHRASE, cleanPhrase(phrase)); }

    public void storeNodeBase(String seedRandomHex) throws Exception {
        String h = seedRandomHex == null ? "" : seedRandomHex.trim();
        if (!h.matches("0x[0-9a-fA-F]{64}")) throw new IllegalArgumentException("The node returned no derived seed.");
        store(MODE_NODE, h);
    }

    /** The stored secret (phrase or base hex). Only {@link Account} reads it, to build the keys. */
    String secret() throws Exception {
        if (!exists()) return null;
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, Base64.decode(prefs.getString(P_IV, ""), Base64.NO_WRAP)));
        return new String(c.doFinal(Base64.decode(prefs.getString(P_CT, ""), Base64.NO_WRAP)), StandardCharsets.UTF_8);
    }

    /** Only for a vault that can no longer be decrypted: forget it so the user can restore from the 24 words. */
    void clearUnreadable() {
        try { secret(); return; } catch (Exception gone) { /* really unreadable */ }
        prefs.edit().clear().commit();
    }

    /** Show the phrase again (phrase mode only) so the user can re-check their backup. */
    public String revealPhrase() throws Exception { return MODE_PHRASE.equals(mode()) ? secret() : null; }

    private void store(String mode, String secret) throws Exception {
        if (exists()) throw new IllegalStateException("A Trading Account already exists on this phone.");
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, key());
        byte[] ct = c.doFinal(secret.getBytes(StandardCharsets.UTF_8));
        boolean ok = prefs.edit().putString(P_MODE, mode)
                .putString(P_IV, Base64.encodeToString(c.getIV(), Base64.NO_WRAP))
                .putString(P_CT, Base64.encodeToString(ct, Base64.NO_WRAP)).commit();
        if (!ok) throw new IllegalStateException("Could not save the Trading Account.");
    }

    private static SecretKey key() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        if (ks.containsAlias(KEY_ALIAS)) return ((KeyStore.SecretKeyEntry) ks.getEntry(KEY_ALIAS, null)).getSecretKey();
        KeyGenerator g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        g.init(new KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build());
        return g.generateKey();
    }
}
