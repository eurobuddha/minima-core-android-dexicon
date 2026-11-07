// VENDORED VERBATIM from apks/pandapools 0.9.90 app/src/test/java/com/eurobuddha/pandapools/RestoreExitRefusalTest.java by scripts/vendor-pandapools.sh — do not edit here; re-vendor.
package com.eurobuddha.pandapools;

import org.junit.Test;

import java.math.BigDecimal;
import java.util.Collections;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * When the automatic withdrawal refuses to sign, and what it says.
 *
 * The auto-withdrawal cannot make signing safe — a signature the original device made that never reached the
 * chain is invisible to everything here. What it can do is refuse every case where a reuse is DETECTABLE, and
 * those cases are the subject of this file. The refusal reasons are user-facing strings, so they are checked for
 * the numbers and full identifiers a person needs in order to act.
 *
 * {@code refusal} takes a null Context, which reads as "no prior attempts recorded" — that is the ordinary
 * first-attempt path, and keeps these tests free of Android.
 */
public class RestoreExitRefusalTest {

    private static String h(String c) { return "0x" + String.join("", Collections.nCopies(64, c)); }

    private static Pool pool() {
        Pool p = new Pool();
        p.address = h("4"); p.opk = h("1"); p.oadr = h("2"); p.tok = h("3"); p.tokDecimals = 8;
        p.coinidM = h("a"); p.coinidT = h("b");
        p.reserveM = new BigDecimal("487.0099300309"); p.reserveT = new BigDecimal("1.84920223");
        p.minimumOwnerUses = 590;
        p.reserveBlock = 1000;
        return p;
    }

    private static final int AGED = 1000 + PoolRefresher.REFRESH_BLOCKS + 1;

    @Test public void anAgedPoolWithAKnownFloorAndAHealthyCounterIsWithdrawn() {
        assertNull(RestoreExit.refusal(null, pool(), 846, 262144, AGED));
    }

    // ---- the incident shape ----

    @Test public void aCounterBelowTheRecordedFloorRefusesAndQuotesBothNumbers() {
        // Astra2: the backup recorded 590 uses; the restored phone's node reported 46.
        String why = RestoreExit.refusal(null, pool(), 46, 262144, AGED);
        assertNotNull(why);
        assertTrue(why, why.contains("46"));
        assertTrue(why, why.contains("590"));
        assertTrue(why, why.contains("reuse"));
    }

    @Test public void aRecipeWithNoRecordedCounterRefuses() {
        // minimumOwnerUses defaults to -1, and `uses >= -1` is trivially true — so a backup that recorded no
        // count offers NO regression signal. A seed-restored wallet reading 0 would otherwise sail straight
        // through, which is precisely the accident this whole release exists to prevent.
        Pool p = pool();
        p.minimumOwnerUses = -1;
        String why = RestoreExit.refusal(null, p, 0, 262144, AGED);
        assertNotNull("a recipe with no recorded count must never be auto-withdrawn", why);
        assertTrue(why, why.contains("no signature count"));
    }

    // ---- another device still holds this key ----

    @Test public void youngReservesRefuseBecauseAnotherDeviceIsSigningThisKey() {
        // Only $OPK can recreate the reserves, so young reserves mean a second live copy of the wallet. Two
        // nodes signing one key is a guaranteed reuse, not a speculative one.
        String why = RestoreExit.refusal(null, pool(), 846, 262144, 1000 + 10);
        assertNotNull(why);
        assertTrue(why, why.contains("another device"));
        assertTrue(why, why.contains("10 blocks ago"));
        assertTrue("it must tell them what to do", why.contains("Stop the other device"));
    }

    @Test public void anUnknownReserveAgeRefuses() {
        // PoolRefresher treats an unknown age as "aging" because a needless refresh is harmless. For a SPEND the
        // asymmetry reverses, so unknown must fail closed here.
        Pool p = pool();
        p.reserveBlock = 0;
        assertNotNull(RestoreExit.refusal(null, p, 846, 262144, AGED));
    }

    @Test public void ageExactlyAtTheRefreshThresholdIsStillTooYoung() {
        assertNotNull(RestoreExit.refusal(null, pool(), 846, 262144, 1000 + PoolRefresher.REFRESH_BLOCKS));
        assertNull(RestoreExit.refusal(null, pool(), 846, 262144, 1000 + PoolRefresher.REFRESH_BLOCKS + 1));
    }

    // ---- the key itself ----

    @Test public void anUnreadableCounterRefusesAndIsNotTreatedAsZero() {
        String why = RestoreExit.refusal(null, pool(), null, 262144, AGED);
        assertNotNull(why);
        assertTrue(why, why.contains("could not read"));
    }

    @Test public void anExhaustedKeyRefuses() {
        String why = RestoreExit.refusal(null, pool(), 262144, 262144, AGED);
        assertNotNull(why);
        assertTrue(why, why.contains("262,144"));
    }

    @Test public void aNullPoolRefuses() {
        assertNotNull(RestoreExit.refusal(null, null, 846, 262144, AGED));
    }

    // ---- wording ----

    @Test public void noRefusalClaimsAnythingIsSafe() {
        for (Integer uses : new Integer[]{ null, 0, 46, 262144 }) {
            String why = RestoreExit.refusal(null, pool(), uses, 262144, 1000 + 10);
            if (why == null) continue;
            String low = why.toLowerCase();
            assertFalse(why, low.contains("is safe"));
            assertFalse(why, low.contains("safe to"));
        }
    }

    @Test public void noRefusalTruncatesAnIdentifier() {
        for (Integer uses : new Integer[]{ null, 46, 262144 }) {
            String why = RestoreExit.refusal(null, pool(), uses, 262144, 1000 + 10);
            if (why != null) assertFalse(why, why.contains("…"));
        }
    }

    @Test public void theRunCapIsBoundedSoNothingSignsUnattendedForLong() {
        assertTrue(RestoreExit.MAX_PER_RUN > 0 && RestoreExit.MAX_PER_RUN <= 8);
    }
}
