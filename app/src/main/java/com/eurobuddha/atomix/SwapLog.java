// VENDORED VERBATIM from apks/atomix 0.1.82 app/src/main/java/com/eurobuddha/atomix/SwapLog.java by scripts/vendor-atomix.sh — do not edit here; re-vendor.
package com.eurobuddha.atomix;

import android.util.Log;

/**
 * Publish/pairing observability. The app previously had ZERO logging, so a blocked or failed market
 * republish left no on-device trace (the 2026-07-07 outage had to be diagnosed from chain data alone).
 * Grep on-device with: adb logcat -s SwapPub
 *
 * HARD RULE: never log the vault seed, identity key material or signatures — booleans, lengths and
 * txpowids only.
 */
public final class SwapLog {

    public static final String TAG = "SwapPub";

    /** Last message per skip-key, so per-tick gate skips log only when the reason CHANGES. */
    private static final java.util.HashMap<String, String> LAST = new java.util.HashMap<>();

    private SwapLog() {}

    public static void d(String m) { try { Log.d(TAG, m); } catch (RuntimeException ignore) { /* JVM unit tests: android.util.Log not mocked */ } }

    public static void w(String m) { try { Log.w(TAG, m); } catch (RuntimeException ignore) { /* JVM unit tests: android.util.Log not mocked */ } }

    /** For gates evaluated every ~90s: logs {@code key: m} only when {@code m} differs from the last one. */
    public static synchronized void skip(String key, String m) {
        String prev = LAST.put(key, m);
        if (!m.equals(prev)) Log.d(TAG, key + ": " + m);
    }
}
