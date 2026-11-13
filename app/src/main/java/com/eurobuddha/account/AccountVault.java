package com.eurobuddha.account;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyPermanentlyInvalidatedException;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import org.minima.utils.BIP39;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.AEADBadTagException;
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
 * The ciphertext is NEVER deleted on an error: only a provably lost or invalidated Keystore key moves it to a
 * quarantine file (so the user can restore from the words). A durable marker records that an account was ever created
 * here, so Dexicon never falls back to the node's own keys afterwards.
 */
public final class AccountVault {

    private static final String PREFS = "dexicon_account_vault", QUARANTINE = "dexicon_account_vault_quarantine",
            MARKER = "dexicon_account_marker", KEY_ALIAS = "dexicon_account_v1";
    private static final String P_MODE = "mode", P_CT = "ct", P_IV = "iv", P_FLOOR = "restore_floor", P_CREATED = "created", P_ALIAS = "alias";
    public static final String MODE_PHRASE = "phrase", MODE_NODE = "node";
    /** 13 Nov 2026: a free-form phrase (not 24 list words), RESTORE only - stored trimmed, never cleaned, behind
     *  {@link #ANY_PREFIX}: an older app reads the secret as a 24-word phrase and its BIP39.cleanSeedPhrase always throws on
     *  "#any#" (no word starts with '#') - loud, never another account's keys (review L2: a bare 12-word phrase would
     *  have cleaned to a different account silently). */
    public static final String MODE_ANY = "any";
    static final String ANY_PREFIX = "#any# ";

    /** The phrase inside a stored MODE_ANY secret. */
    static String anyPhraseOf(String secret) {
        return secret != null && secret.startsWith(ANY_PREFIX) ? secret.substring(ANY_PREFIX.length()) : secret;
    }

    private final SharedPreferences prefs, marker;
    private final Context app;

    public AccountVault(Context c) {
        app = c.getApplicationContext();
        prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        marker = app.getSharedPreferences(MARKER, Context.MODE_PRIVATE);
    }

    public boolean exists() { return !prefs.getString(P_CT, "").isEmpty(); }
    public String mode() { return prefs.getString(P_MODE, ""); }
    /** The identity of the account this phone held (fingerprint + mode), kept in the never-cleared marker: a restore after
     *  a loss must be THE SAME account, or its open positions would be stranded. */
    void recordIdentity(String fingerprint, String mode) {
        if (!fingerprint.equals(marker.getString("fp", "")) || !mode.equals(marker.getString("mode", "")))
            marker.edit().putString("fp", fingerprint).putString("mode", mode).commit();
    }
    public String lostFingerprint() { return marker.getString("fp", ""); }

    /** Every account fingerprint this phone has seen: the marker's, else the key-count slots 0.3.0+ wrote
     *  ("keys_<fp>" in dexicon_account_state) — so a vault lost before its identity was recorded still restores only
     *  as itself. Empty = no record at all. */
    public java.util.Set<String> knownFingerprints() {
        java.util.Set<String> out = new java.util.HashSet<>();
        String m = lostFingerprint();
        if (!m.isEmpty()) { out.add(m.toLowerCase(java.util.Locale.ROOT)); return out; }
        for (String k : app.getSharedPreferences("dexicon_account_state", Context.MODE_PRIVATE).getAll().keySet())
            if (k.startsWith("keys_") && k.length() > 5) out.add(k.substring(5).toLowerCase(java.util.Locale.ROOT));
        return out;
    }
    public String lostMode() { return marker.getString("mode", ""); }

    /** Record the durable marker for a vault that predates it (called after a successful open). */
    void markCreated() { if (marker.getLong(P_CREATED, 0) <= 0) marker.edit().putLong(P_CREATED, System.currentTimeMillis()).commit(); }

    /** A Trading Account was created on this phone at some point (never cleared, not even by a quarantine). */
    public boolean everCreated() { return exists() || marker.getLong(P_CREATED, 0) > 0; }
    /** The minimum leaf for every key: set when the account was restored or node-derived; 0 for a brand-new phrase. */
    public int restoreFloor() { return prefs.getInt(P_FLOOR, 0); }

    /** A brand-new 24-word phrase (not yet stored — the user confirms they wrote it down first). */
    public static String newPhrase() { return BIP39.convertWordListToString(BIP39.getNewWordList()); }

    /** Normalise and validate a typed phrase; throws if it is not 24 BIP39 words. */
    public static String cleanPhrase(String typed) {
        // BIP39.cleanSeedPhrase splits on spaces only: a line break, number or comma glued two words into one "unknown
        // word". Anything that is not a letter separates words (no BIP39 word has anything else in it).
        String p = BIP39.cleanSeedPhrase(typed == null ? "" : typed.replaceAll("[^A-Za-z]+", " ").trim());
        if (p.trim().split("\\s+").length != 24) throw new IllegalArgumentException("A Trading Account phrase is 24 words.");
        return p;
    }

    /** A NEW phrase (never signed anywhere): no restore floor. */
    public void storeNewPhrase(String phrase) throws Exception { store(MODE_PHRASE, cleanPhrase(phrase), 0); }

    /** A RESTORED phrase: may have signed on another install, so every key starts at the restore floor. */
    public void storeRestoredPhrase(String phrase) throws Exception {
        store(MODE_PHRASE, cleanPhrase(phrase), AccountKeys.restoreFloor(System.currentTimeMillis()));
    }

    /** A RESTORED free-form phrase (owner: restore only, with a warning): exactly as made, trimmed; restore floor. */
    public void storeRestoredAnyPhrase(String phrase) throws Exception {
        String why = WordEntry.anyPhraseProblem(phrase, false);
        if (why != null) throw new IllegalArgumentException(why);
        store(MODE_ANY, ANY_PREFIX + phrase.trim(), AccountKeys.restoreFloor(System.currentTimeMillis()));
    }

    public void storeNodeBase(String seedRandomHex) throws Exception {
        String h = seedRandomHex == null ? "" : seedRandomHex.trim();
        if (!h.matches("0x[0-9a-fA-F]{64}")) throw new IllegalArgumentException("The node returned no derived seed.");
        store(MODE_NODE, h, AccountKeys.restoreFloor(System.currentTimeMillis()));
    }

    /** Thrown when the vault can not be read RIGHT NOW (Keystore busy after boot, …): retry later, never delete. */
    public static final class Unavailable extends Exception { Unavailable(Throwable t) { super("The Trading Account is temporarily locked: " + t.getMessage(), t); } }
    /** Thrown when the vault can NEVER be read again on this phone (the Keystore key is gone or invalidated). */
    public static final class Lost extends Exception { Lost(String why) { super(why); } }

    /** The stored secret (phrase or base hex). Only {@link Account} reads it, to build the keys. */
    String secret() throws Unavailable, Lost {
        if (!exists()) return null;
        SecretKey k;
        try {
            KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
            ks.load(null);
            String alias = prefs.getString(P_ALIAS, KEY_ALIAS);
            if (!ks.containsAlias(alias)) throw new Lost("The phone's keystore no longer holds the Trading Account key.");
            k = ((KeyStore.SecretKeyEntry) ks.getEntry(alias, null)).getSecretKey();
        } catch (Lost l) { throw l; }
        catch (Exception transientFailure) { throw new Unavailable(transientFailure); }
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, k, new GCMParameterSpec(128, Base64.decode(prefs.getString(P_IV, ""), Base64.NO_WRAP)));
            return new String(c.doFinal(Base64.decode(prefs.getString(P_CT, ""), Base64.NO_WRAP)), StandardCharsets.UTF_8);
        } catch (KeyPermanentlyInvalidatedException | AEADBadTagException gone) {
            throw new Lost("The Trading Account key on this phone was invalidated.");
        } catch (Exception transientFailure) { throw new Unavailable(transientFailure); }
    }

    /** Only after {@link Lost}: move the ciphertext aside (kept, never deleted) so setup offers RESTORE 24 WORDS. */
    void quarantine() {
        markCreated();   // a vault that predates the marker must keep it after quarantine
        if (marker.getString("mode", "").isEmpty() && !prefs.getString(P_MODE, "").isEmpty())
            marker.edit().putString("mode", prefs.getString(P_MODE, "")).commit();   // so a node-mode account restores as one
        SharedPreferences q = app.getSharedPreferences(QUARANTINE, Context.MODE_PRIVATE);
        String t = "_" + System.currentTimeMillis();
        q.edit().putString(P_MODE + t, prefs.getString(P_MODE, "")).putString(P_CT + t, prefs.getString(P_CT, ""))
                .putString(P_IV + t, prefs.getString(P_IV, "")).putString(P_ALIAS + t, prefs.getString(P_ALIAS, KEY_ALIAS))
                .putInt(P_FLOOR + t, prefs.getInt(P_FLOOR, 0)).commit();
        if (!q.getString(P_CT + t, "").equals(prefs.getString(P_CT, ""))) return;   // the copy did not land: keep the vault
        prefs.edit().remove(P_CT).remove(P_IV).remove(P_MODE).remove(P_ALIAS).remove(P_FLOOR).commit();
    }

    /** Show the phrase again (phrase mode only) so the user can re-check their backup. */
    public String revealPhrase() throws Exception {
        return MODE_PHRASE.equals(mode()) ? secret() : MODE_ANY.equals(mode()) ? anyPhraseOf(secret()) : null;
    }

    private void store(String mode, String secret, int floor) throws Exception {
        if (exists()) throw new IllegalStateException("A Trading Account already exists on this phone.");
        // re-checked HERE, at the write (a setup dialog may have stayed open while another family app was installed)
        String other = Family.otherInstalled(app);
        if (other != null) throw new IllegalStateException(other + " is installed: a phone runs either Dexicon, or AtomiX and PandaPools — not both. Nothing was saved.");
        String alias = KEY_ALIAS + "_" + System.currentTimeMillis();   // a fresh key per vault: a quarantined copy keeps its own
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, newKey(alias));
        byte[] ct = c.doFinal(secret.getBytes(StandardCharsets.UTF_8));
        boolean ok = prefs.edit().putString(P_MODE, mode).putInt(P_FLOOR, floor).putString(P_ALIAS, alias)
                .putString(P_IV, Base64.encodeToString(c.getIV(), Base64.NO_WRAP))
                .putString(P_CT, Base64.encodeToString(ct, Base64.NO_WRAP)).commit();
        if (!ok) throw new IllegalStateException("Could not save the Trading Account.");
        markCreated();
    }

    /** Keys are created only while storing a NEW vault — reading never creates one (a missing key = Lost). */
    private static SecretKey newKey(String alias) throws Exception {
        KeyGenerator g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        g.init(new KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build());
        return g.generateKey();
    }
}
