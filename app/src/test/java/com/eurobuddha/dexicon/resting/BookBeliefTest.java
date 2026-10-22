package com.eurobuddha.dexicon.resting;
/* Ported from apks/pandadex app/src/test/java/com/eurobuddha/pandadex/BookBeliefTest.java (PandaDEX 0.4.23). */


import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * When is a scan worth believing?
 *
 * This single rule has been wrong twice, both times with visible consequences: once counting
 * transport failures toward the empty-book budget (which let three timeouts spend the whole
 * confirmation allowance, wiping the cache on the next empty-looking reply), and once refusing
 * to persist a confirmed-empty book (which left dead orders painting on every cold start after
 * the user cancelled everything). It is pure now, and pinned here.
 */
public class BookBeliefTest {

    @Test public void aTruncatedScanIsNeverBelieved() {
        // a failed/partial read says nothing about the book — believing it would delete a
        // perfectly good cache
        assertFalse(BookRepository.believable(true, true, false, 99, true));
        assertFalse("not even a non-empty one", BookRepository.believable(true, false, false, 0, true));
        assertFalse("nor on a cold start", BookRepository.believable(true, true, false, 0, false));
    }

    @Test public void aNormalScanIsBelievedImmediately() {
        assertTrue(BookRepository.believable(false, false, false, 0, true));
    }

    @Test public void aLoneEmptyScanIsNotEnoughToEmptyTheBook() {
        // one empty reply is indistinguishable from a momentary bad read
        assertFalse(BookRepository.believable(false, true, false, 1, true));
        assertFalse(BookRepository.believable(false, true, false, 2, true));
    }

    @Test public void aConfirmedEmptyBookIsBelieved() {
        // ...but distrust must expire, or cancelling every order freezes the cache forever
        assertTrue(BookRepository.believable(false, true, false, BookRepository.EMPTY_CONFIRM, true));
        assertTrue(BookRepository.believable(false, true, false, BookRepository.EMPTY_CONFIRM + 1, true));
    }

    @Test public void anEmptyScanAgainstAnEmptyCacheNeedsNoConfirmation() {
        // nothing to contradict — this is the steady state of an empty book, and demanding
        // confirmation here would mean never writing the empty snapshot at all
        assertTrue(BookRepository.believable(false, true, true, 0, true));
    }

    @Test public void theFirstLiveScanBeatsTheSnapshotOutright() {
        // The cache at cold start is a GUESS restored from disk. Making fresh evidence win
        // three confirmations against it is what left ghost orders on screen for ten seconds
        // after the user had cancelled everything — the gate is meant to protect a book we
        // have actually observed, not a stale file.
        assertTrue("empty scan, stale non-empty snapshot, nothing observed yet",
                BookRepository.believable(false, true, false, 0, false));
    }
}
