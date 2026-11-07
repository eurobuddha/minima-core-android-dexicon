// VENDORED VERBATIM from apks/pandapools 0.9.83 app/src/test/java/com/eurobuddha/pandapools/StrandingWatchTest.java by scripts/vendor-pandapools.sh — do not edit here; re-vendor.
package com.eurobuddha.pandapools;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/**
 * When a pool is warned about, and how often.
 *
 * Stranding is the root cause of every recovery problem in this app: reserves that are not recreated fall into
 * the megammr-only archive and the pool disappears from every light node, including its owner's. The thresholds
 * and the rate limit are pure functions so the escalation can be pinned without an Android runtime.
 *
 * The balance being struck: warn early enough to be actionable, but never so often that the notification becomes
 * something the user swipes away by reflex — because this is the one warning they cannot afford to ignore.
 */
public class StrandingWatchTest {

    private static final long NOW = 1_800_000_000_000L;

    // ---- thresholds ----

    @Test public void aFreshlyRefreshedPoolIsNotWarnedAbout() {
        assertEquals(StrandingWatch.Level.NONE, StrandingWatch.levelFor(1));
        assertEquals(StrandingWatch.Level.NONE, StrandingWatch.levelFor(PoolRefresher.REFRESH_BLOCKS));
    }

    @Test public void anUnknownAgeIsNotTreatedAsUrgent() {
        // reserveAge() returns 0 for "unknown". Escalating on that would warn about healthy pools whose age we
        // simply have not read yet, which is how a warning gets trained out of a user.
        assertEquals(StrandingWatch.Level.NONE, StrandingWatch.levelFor(0));
        assertEquals(StrandingWatch.Level.NONE, StrandingWatch.levelFor(-5));
    }

    @Test public void noticeOnlyOnceKeepFreshHasDemonstrablyFailed() {
        // Keep-fresh runs at REFRESH_BLOCKS, and a refresh takes a block or two to confirm, so the first warning
        // waits until well past that — otherwise it would fire on the normal cadence.
        assertEquals(StrandingWatch.Level.NONE, StrandingWatch.levelFor(StrandingWatch.NOTICE_AT));
        assertEquals(StrandingWatch.Level.NOTICE, StrandingWatch.levelFor(StrandingWatch.NOTICE_AT + 1));
        assertTrue("the first warning must come after keep-fresh should have run",
                StrandingWatch.NOTICE_AT > PoolRefresher.REFRESH_BLOCKS);
    }

    @Test public void warnOnceDiscoveryHasDroppedThePool() {
        assertEquals(StrandingWatch.Level.NOTICE, StrandingWatch.levelFor(StrandingWatch.WARN_AT));
        assertEquals(StrandingWatch.Level.WARN, StrandingWatch.levelFor(StrandingWatch.WARN_AT + 1));
        assertEquals("the warn point is the registry scan depth, past which nobody can find the pool",
                PoolCovenant.SENTINEL_SCAN_DEPTH, StrandingWatch.WARN_AT);
    }

    @Test public void urgentBeforeTheCascadeEdgeNotAfterIt() {
        assertEquals(StrandingWatch.Level.WARN, StrandingWatch.levelFor(StrandingWatch.URGENT_AT));
        assertEquals(StrandingWatch.Level.URGENT, StrandingWatch.levelFor(StrandingWatch.URGENT_AT + 1));
        assertTrue("urgent must arrive with room to act, not once the coins have already aged out",
                StrandingWatch.URGENT_AT < StrandingWatch.CASCADE_BLOCKS);
    }

    @Test public void theThresholdsAreStrictlyOrdered() {
        assertTrue(PoolRefresher.REFRESH_BLOCKS < StrandingWatch.NOTICE_AT);
        assertTrue(StrandingWatch.NOTICE_AT < StrandingWatch.WARN_AT);
        assertTrue(StrandingWatch.WARN_AT < StrandingWatch.URGENT_AT);
        assertTrue(StrandingWatch.URGENT_AT < StrandingWatch.CASCADE_BLOCKS);
    }

    // ---- rate limiting ----

    @Test public void theFirstWarningAtAnyLevelIsRaisedImmediately() {
        assertTrue(StrandingWatch.shouldNotify(StrandingWatch.Level.NOTICE, StrandingWatch.Level.NONE, 0, NOW));
        assertTrue(StrandingWatch.shouldNotify(StrandingWatch.Level.URGENT, StrandingWatch.Level.NONE, 0, NOW));
    }

    @Test public void gettingWorseIsAlwaysAnnouncedAtOnce() {
        assertTrue(StrandingWatch.shouldNotify(StrandingWatch.Level.WARN, StrandingWatch.Level.NOTICE, NOW, NOW));
        assertTrue(StrandingWatch.shouldNotify(StrandingWatch.Level.URGENT, StrandingWatch.Level.WARN, NOW, NOW));
    }

    @Test public void anUnchangedLevelWaitsOutTheQuietWindow() {
        assertFalse(StrandingWatch.shouldNotify(StrandingWatch.Level.WARN, StrandingWatch.Level.WARN, NOW, NOW + 60_000));
        assertFalse(StrandingWatch.shouldNotify(StrandingWatch.Level.WARN, StrandingWatch.Level.WARN,
                NOW, NOW + StrandingWatch.QUIET_MS));
        assertTrue(StrandingWatch.shouldNotify(StrandingWatch.Level.WARN, StrandingWatch.Level.WARN,
                NOW, NOW + StrandingWatch.QUIET_MS + 1));
    }

    @Test public void improvingNeverNags() {
        // A pool being refreshed back toward health must not produce a fresh alarm on the way down.
        assertFalse(StrandingWatch.shouldNotify(StrandingWatch.Level.NOTICE, StrandingWatch.Level.URGENT, NOW, NOW));
        assertFalse(StrandingWatch.shouldNotify(StrandingWatch.Level.WARN, StrandingWatch.Level.URGENT,
                NOW, NOW + StrandingWatch.QUIET_MS * 5));
    }

    @Test public void aHealedPoolIsNeverNotifiedAbout() {
        assertFalse(StrandingWatch.shouldNotify(StrandingWatch.Level.NONE, StrandingWatch.Level.URGENT, 0, NOW));
        assertFalse(StrandingWatch.shouldNotify(StrandingWatch.Level.NONE, StrandingWatch.Level.NONE, 0, NOW));
    }

    @Test public void theQuietWindowIsLongEnoughToNotBecomeNoise() {
        assertTrue("at most one repeat per day at an unchanged level",
                StrandingWatch.QUIET_MS >= 12 * 60 * 60 * 1000L);
    }

    // ---- identity ----

    @Test public void eachPoolGetsItsOwnNotificationId() {
        String a = "0x" + String.join("", java.util.Collections.nCopies(64, "a"));
        String b = "0x" + String.join("", java.util.Collections.nCopies(64, "b"));
        assertNotEquals("one pool's warning must not replace another's",
                StrandingWatch.idFor(a), StrandingWatch.idFor(b));
        assertEquals("the id must be stable so a repeat replaces its own earlier warning",
                StrandingWatch.idFor(a), StrandingWatch.idFor(a));
        assertEquals("addresses compare case-insensitively everywhere else too",
                StrandingWatch.idFor(a), StrandingWatch.idFor(a.toUpperCase()));
        assertTrue(StrandingWatch.idFor(null) > 0);
    }
}
