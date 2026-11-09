package com.eurobuddha.dexicon.maker;

import static org.junit.Assert.*;

import org.junit.Test;

/** 0.2.5 (AtomiX SwapService.maybeOfflineAlert): unpaired > 3 min with a market on → alert, then hourly, never otherwise. */
public class OfflineAlertTest {
    private static final long T = 1_700_000_000_000L, MIN = 60_000;

    @Test public void alertsOnlyForALiveMarketAfterThreeMinutesThenHourly() {
        assertFalse("paired", MakerService.offlineAlertDue(true, true, T, 0, T + 10 * MIN));
        assertFalse("no market: nothing to lose", MakerService.offlineAlertDue(false, false, T, 0, T + 10 * MIN));
        assertFalse("under 3 min", MakerService.offlineAlertDue(false, true, T, 0, T + 2 * MIN));
        assertTrue("3 min", MakerService.offlineAlertDue(false, true, T, 0, T + 3 * MIN));
        assertFalse("not again within the hour", MakerService.offlineAlertDue(false, true, T, T + 3 * MIN, T + 30 * MIN));
        assertTrue("hourly", MakerService.offlineAlertDue(false, true, T, T + 3 * MIN, T + 63 * MIN));
        assertFalse("never unpaired", MakerService.offlineAlertDue(false, true, 0, 0, T));
    }

    /** 9 Nov: no reply from any price source for 5 min while pegged -> one "check Wi-Fi" alert, then at most hourly. */
    @Test public void netAlertOnlyForAPeggedLadderAfterFiveMinutesThenHourly() {
        long T = 1_000_000_000L, MIN = 60_000;
        assertFalse("not pegged: nothing waits on the feed", MakerService.netAlertDue(false, 30 * MIN, 0, T));
        assertFalse("under 5 min", MakerService.netAlertDue(true, 5 * MIN - 1, 0, T));
        assertTrue(MakerService.netAlertDue(true, 5 * MIN, 0, T));
        assertFalse("not again within the hour", MakerService.netAlertDue(true, 40 * MIN, T, T + 59 * MIN));
        assertTrue("hourly while it lasts", MakerService.netAlertDue(true, 70 * MIN, T, T + 60 * MIN));
        assertFalse("answering again: 0 down", MakerService.netAlertDue(true, 0, 0, T));
    }
}
