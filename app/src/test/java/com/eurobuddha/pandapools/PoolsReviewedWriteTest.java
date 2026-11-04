// VENDORED VERBATIM from apks/pandapools 0.9.80 app/src/test/java/com/eurobuddha/pandapools/PoolsReviewedWriteTest.java by scripts/vendor-pandapools.sh — do not edit here; re-vendor.
package com.eurobuddha.pandapools;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

/** AtomiX's reviewed-marker contract using PandaPools' existing clear/rollback seam. */
public class PoolsReviewedWriteTest {
    @Before public void setup() { reset(); }
    @After public void reset() { NodeApi.commitWriteAcknowledgement(() -> true, () -> {}); }

    @Test public void matchingIdleReviewClearsExactlyOnce() {
        AtomicInteger clears = new AtomicInteger();
        assertTrue(NodeApi.acknowledgeReviewedWrite("reviewed", "reviewed", "",
                () -> { clears.incrementAndGet(); return true; }, () -> fail("unexpected rollback")));
        assertEquals(1, clears.get());
        assertFalse(NodeApi.writeAcknowledgementFailed());
    }

    @Test public void oldReviewCannotClearNewMarker() {
        assertRejected("old", "new", "");
        assertRejected("old", "", "");
        assertRejected("", "new", "");
    }

    @Test public void activeWriteRejectsEvenMatchingReview() {
        assertRejected("reviewed", "reviewed", "active");
    }

    @Test public void missingConsentRejectsEvenEmptyMarker() {
        assertRejected(null, "", "");
        assertRejected(null, "reviewed", "");
    }

    @Test public void rejectedReviewCannotClearAnExistingFailedSaveLatch() {
        assertFalse(NodeApi.commitWriteAcknowledgement(() -> false, () -> {}));
        assertRejected("old", "new", "");
        assertTrue(NodeApi.writeAcknowledgementFailed());
        assertTrue(NodeApi.acknowledgeReviewedWrite("new", "new", "", () -> true, () -> fail("rollback")));
        assertFalse(NodeApi.writeAcknowledgementFailed());
    }

    @Test public void failedClearRestoresMarkerAndThrowingRollbackKeepsPause() {
        AtomicInteger rollbacks = new AtomicInteger();
        assertFalse(NodeApi.acknowledgeReviewedWrite("reviewed", "reviewed", "", () -> false,
                () -> { rollbacks.incrementAndGet(); throw new IllegalStateException("disk"); }));
        assertEquals(1, rollbacks.get());
        assertTrue(NodeApi.writeAcknowledgementFailed());
        assertFalse(NodeApi.acknowledgeReviewedWrite("", "", "",
                () -> { throw new IllegalStateException("disk"); }, () -> rollbacks.incrementAndGet()));
        assertEquals(2, rollbacks.get());
        assertTrue(NodeApi.writeAcknowledgementFailed());
        assertTrue(NodeApi.acknowledgeReviewedWrite("", "", "", () -> true, () -> fail("rollback")));
        assertFalse(NodeApi.writeAcknowledgementFailed());
    }

    private void assertRejected(String expected, String current, String active) {
        assertFalse(NodeApi.acknowledgeReviewedWrite(expected, current, active,
                () -> { fail("must not clear unreviewed evidence"); return true; },
                () -> fail("must not rewrite unreviewed evidence")));
    }
}
