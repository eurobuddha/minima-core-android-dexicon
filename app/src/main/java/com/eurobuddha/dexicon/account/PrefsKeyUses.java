package com.eurobuddha.dexicon.account;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * SharedPreferences-backed {@link KeyUses} for M0.
 *
 * <p>Implements the SAFETY CONTRACT from {@link KeyUses}:
 * <ul>
 *   <li><b>Durable reserve-before-sign.</b> {@link #reserveNextUse(int)} writes {@code n+1} with
 *       {@link SharedPreferences.Editor#commit()} (synchronous — returns only after the write is
 *       persisted) to BOTH mirrors before returning {@code n}. If either commit fails it throws.</li>
 *   <li><b>Redundancy + MAX read.</b> The count is mirrored to two separate prefs files and every
 *       read reconciles to the MAX, so a partial restore or a torn write can only ever raise the
 *       counter, never lower it.</li>
 * </ul>
 *
 * <p>App-private SharedPreferences survive app upgrades automatically. Seed-derived encryption of
 * this blob and the portable/manual backup path are deferred to M3 per the plan; the shape here
 * (two mirrors, MAX-on-read, commit-before-return) is the durable foundation those build on.
 *
 * <p>NOTE: this class touches the Android framework, so pure-JVM unit tests use a lightweight
 * in-memory {@link KeyUses} instead; the safety logic that tests exercise lives in
 * {@link AccountKeys#sign}.
 */
public class PrefsKeyUses implements KeyUses {

    private static final String FILE_A = "account_keyuses_a";
    private static final String FILE_B = "account_keyuses_b";
    private static final String KEY_PREFIX = "uses_";

    private final SharedPreferences mMirrorA;
    private final SharedPreferences mMirrorB;

    public PrefsKeyUses(Context zContext) {
        Context app = zContext.getApplicationContext();
        mMirrorA = app.getSharedPreferences(FILE_A, Context.MODE_PRIVATE);
        mMirrorB = app.getSharedPreferences(FILE_B, Context.MODE_PRIVATE);
    }

    private static String key(int zIndex) {
        return KEY_PREFIX + zIndex;
    }

    @Override
    public synchronized int currentUses(int zKeyIndex) {
        int a = mMirrorA.getInt(key(zKeyIndex), 0);
        int b = mMirrorB.getInt(key(zKeyIndex), 0);
        return Math.max(a, b);
    }

    @Override
    public synchronized int reserveNextUse(int zKeyIndex) {
        int n = currentUses(zKeyIndex);
        int next = n + 1;

        // Durably persist the advance to BOTH mirrors BEFORE returning the leaf to sign.
        // commit() is synchronous and returns success/failure; if it fails we throw so no
        // signature is ever produced against an unpersisted advance.
        boolean okA = mMirrorA.edit().putInt(key(zKeyIndex), next).commit();
        boolean okB = mMirrorB.edit().putInt(key(zKeyIndex), next).commit();
        if (!okA || !okB) {
            throw new IllegalStateException(
                "KeyUses: failed to durably persist uses advance for key " + zKeyIndex
                    + " (mirrorA=" + okA + ", mirrorB=" + okB + ") — refusing to sign");
        }
        return n;
    }

    @Override
    public synchronized java.util.Map<Integer, Integer> snapshotAllUses() {
        // Union the key indices recorded in EITHER mirror, then read each via the MAX-on-read path so
        // the returned value is the reconciled count. A torn/partial mirror can only add an index or
        // raise a value — never drop one — matching the store's raise-never-lower invariant.
        java.util.LinkedHashMap<Integer, Integer> out = new java.util.LinkedHashMap<>();
        java.util.TreeSet<Integer> indices = new java.util.TreeSet<>();
        collectIndices(mMirrorA, indices);
        collectIndices(mMirrorB, indices);
        for (int idx : indices) {
            out.put(idx, currentUses(idx));
        }
        return out;
    }

    private static void collectIndices(SharedPreferences zPrefs, java.util.Set<Integer> zInto) {
        for (String k : zPrefs.getAll().keySet()) {
            if (k != null && k.startsWith(KEY_PREFIX)) {
                try {
                    zInto.add(Integer.parseInt(k.substring(KEY_PREFIX.length())));
                } catch (NumberFormatException ignore) { /* not a uses_<n> entry */ }
            }
        }
    }

    /**
     * WALLET RESET ONLY — durably erase BOTH mirrors. This is the one deliberate exception to the
     * raise-never-lower invariant: the counters belong to the seed being erased, and carrying them
     * to a NEW seed would burn its leaves and misreport "Signatures used". Only call AFTER the
     * vault blob is deleted (the account vault), never from any signing flow. If the SAME
     * seed is later re-imported bare, the untrusted-import fail-safe blocks signing until the user
     * attests a count — exactly as on a fresh install.
     */
    public synchronized void clearAllForWalletReset() {
        boolean okA = mMirrorA.edit().clear().commit();
        boolean okB = mMirrorB.edit().clear().commit();
        if (!okA || !okB) {
            throw new IllegalStateException("KeyUses: failed to durably clear mirrors on wallet reset");
        }
    }

    @Override
    public synchronized void recordExternalUses(int zKeyIndex, int zUses) {
        int merged = Math.max(currentUses(zKeyIndex), zUses);
        boolean okA = mMirrorA.edit().putInt(key(zKeyIndex), merged).commit();
        boolean okB = mMirrorB.edit().putInt(key(zKeyIndex), merged).commit();
        if (!okA || !okB) {
            throw new IllegalStateException(
                "KeyUses: failed to durably record external uses for key " + zKeyIndex);
        }
    }
}
