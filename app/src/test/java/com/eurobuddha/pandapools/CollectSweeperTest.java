// VENDORED VERBATIM from apks/pandapools 0.9.80 app/src/test/java/com/eurobuddha/pandapools/CollectSweeperTest.java by scripts/vendor-pandapools.sh — do not edit here; re-vendor.
package com.eurobuddha.pandapools;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/**
 * Getting withdrawn funds off a pool's owner payout address, and not stopping early.
 *
 * THE INCIDENT THIS PINS. Pool
 * MxG081BJGW3KQ0PBPHCS841CC5RA7YKRVEQUE93Z353MT4FBTJ2101S1423Z1ET closed on 2026-09-14, paying
 * 585050.29830926161 MINIMA and 2934.95626348 MxUSD to a single $OADR
 * (0x0A10CC6952D404ED3DF2C9B3221AE622F35CF788CFBD4EED0E486D9F98CD6675). The forward ran while only the
 * MINIMA leg met its `coinage:3 sendable:true` filter, moved that, reported success — and the retry loop
 * stopped. The MxUSD is still there, and the device holding the owner key has since died, so it cannot
 * be signed for at all.
 *
 * Two mistakes caused it, and both are what these tests forbid:
 *   1. treating a forward that POSTED as the job being finished, when it only moved what it could see;
 *   2. giving up at all — 8 tries, 20 s apart, only while one screen was alive.
 *
 * So: CLEAR is reachable only from an empty read, and nothing about a forward's outcome can reach it.
 */
public class CollectSweeperTest {

    // ---- the incident, reproduced ----

    @Test public void aForwardThatMovedOnlyOneLegDoesNotFinishTheJob() {
        // One coin (the token) is still at the address, and this wallet can sign for it. The previous
        // implementation stopped here because its forward had reported success. It must retry instead.
        assertEquals(CollectSweeper.Verdict.RETRY, CollectSweeper.decide(1, true));
    }

    @Test public void onlyAnEmptyAddressEverFinishesTheJob() {
        assertEquals(CollectSweeper.Verdict.CLEAR, CollectSweeper.decide(0, true));
        assertEquals(CollectSweeper.Verdict.CLEAR, CollectSweeper.decide(0, false));
        assertEquals(CollectSweeper.Verdict.CLEAR, CollectSweeper.decide(0, null));
    }

    @Test public void noRemainingCoinCountCanEverBeMistakenForDone() {
        // The heart of the fix: whatever else is true, coins present means not finished.
        for (int remaining = 1; remaining <= 50; remaining++) {
            for (Boolean sign : new Boolean[]{ Boolean.TRUE, Boolean.FALSE, null }) {
                assertNotEquals("remaining=" + remaining + " canSign=" + sign,
                        CollectSweeper.Verdict.CLEAR, CollectSweeper.decide(remaining, sign));
            }
        }
    }

    // ---- unknown is never a conclusion ----

    @Test public void anUnreadableAddressIsRetriedNotConcluded() {
        // A node that cannot answer must not produce "done" (losing the job) or "stranded" (a false alarm
        // about real money).
        assertEquals(CollectSweeper.Verdict.RETRY, CollectSweeper.decide(null, true));
        assertEquals(CollectSweeper.Verdict.RETRY, CollectSweeper.decide(null, false));
        assertEquals(CollectSweeper.Verdict.RETRY, CollectSweeper.decide(null, null));
    }

    @Test public void anUnknownSigningAnswerIsRetriedNotReportedAsStranded() {
        assertEquals(CollectSweeper.Verdict.RETRY, CollectSweeper.decide(2, null));
    }

    // ---- the case that needed a human, and never got one ----

    @Test public void fundsPresentAndUnsignableIsReportedNotRetriedInSilence() {
        // Exactly the end state of the incident: the coin is there and no wallet here can sign for it.
        // Retrying forever tells the user nothing; this is what surfaces it.
        assertEquals(CollectSweeper.Verdict.STRANDED, CollectSweeper.decide(1, false));
    }

    @Test public void strandedNeverAppliesToAnEmptyAddress() {
        assertNotEquals(CollectSweeper.Verdict.STRANDED, CollectSweeper.decide(0, false));
    }

    // ---- the rule takes no input from a forward's outcome ----

    @Test public void theDecisionCannotSeeWhetherAForwardPosted() throws Exception {
        // Structural, not behavioural: if a future edit threads a "forward succeeded" flag into this
        // decision, the original bug becomes expressible again. decide() must depend only on what is
        // actually at the address.
        java.lang.reflect.Method m = CollectSweeper.class.getDeclaredMethod(
                "decide", Integer.class, Boolean.class);
        assertEquals("decide() must take exactly (remaining, canSign)", 2, m.getParameterCount());
        assertTrue(java.lang.reflect.Modifier.isStatic(m.getModifiers()));
    }
}
