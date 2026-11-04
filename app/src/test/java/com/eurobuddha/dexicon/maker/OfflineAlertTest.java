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
}
