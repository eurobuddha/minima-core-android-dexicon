package com.eurobuddha.dexicon.doors;

import com.eurobuddha.atomix.swap.MinimaHtlc;

/**
 * Dexicon-side shim for the one thing host mode's "start fresh" ({@link DoorHost#startFreshIdentity}) needs from the
 * vendored MinimaHtlc that AtomiX itself never does: forget the adopted swap identity so the next {@code setup()} adopts
 * the node's current key. It lived in MinimaHtlc as {@code forgetIdentity()} (Dexicon 0.3.55), which
 * scripts/vendor-atomix.sh would delete on the next re-vendor; here the vendored file stays byte-identical to AtomiX.
 * Same effect as before (both fields null). minifyEnabled is false, so the field names survive the release build;
 * HtlcIdentityTest fails if AtomiX ever renames them.
 */
final class HtlcIdentity {
    private HtlcIdentity() {}

    static void forget(MinimaHtlc m) {
        try {
            for (String f : new String[]{"myAddress", "myPubkey"}) {
                java.lang.reflect.Field x = MinimaHtlc.class.getDeclaredField(f);
                x.setAccessible(true);
                x.set(m, null);
            }
        } catch (ReflectiveOperationException e) { throw new IllegalStateException("MinimaHtlc's identity fields changed: " + e, e); }
    }
}
