// VENDORED VERBATIM from apks/pandapools 0.9.86 app/src/test/java/com/eurobuddha/pandapools/ActivityTimelineTest.java by scripts/vendor-pandapools.sh — do not edit here; re-vendor.
package com.eurobuddha.pandapools;

import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public class ActivityTimelineTest {
    private ActivityLog.Entry receipt(String id, long time, boolean failed, int depth) {
        ActivityLog.Entry e = new ActivityLog.Entry("SWAP", "same amount", id, 1, time, failed, null);
        e.verifiedDepth = depth; return e;
    }
    private HistoryEntry history(String id, long time) {
        HistoryEntry h = new HistoryEntry(); h.txpowid = id; h.timemilli = time; return h;
    }
    @Test public void oldUnmatchedReceiptsDoNotPushConfirmedTransactionsOffTheTop() {
        ActivityLog.Entry old = receipt("0x01", 1, false, -1), latest = receipt("0x02", 100, false, 80);
        List<ActivityTimeline.Row> rows = ActivityTimeline.merge(Arrays.asList(old, latest), Collections.emptyList(), h -> true);
        assertSame(latest, rows.get(0).receipt); assertSame(old, rows.get(1).receipt);
    }
    @Test public void exactMinedIdJoinsWithoutLosingTheReceiptAndUsesTransactionTime() {
        ActivityLog.Entry r = receipt("0xAB", 900, false, 9);
        HistoryEntry h = history("0xab", 100); h.syncedAt = 99999;
        List<ActivityTimeline.Row> rows = ActivityTimeline.merge(Collections.singletonList(r), Collections.singletonList(h), n -> true);
        assertEquals(1, rows.size()); assertSame(r, rows.get(0).receipt);
        assertSame(h, rows.get(0).history); assertEquals(100, rows.get(0).time);
    }
    @Test public void equalAmountAndTimeDoNotMergeDifferentTransactions() {
        List<ActivityTimeline.Row> rows = ActivityTimeline.merge(Collections.singletonList(receipt("0x01", 100, false, -1)),
                Collections.singletonList(history("0x02", 100)), h -> true);
        assertEquals(2, rows.size());
    }
    @Test public void retainsFailuresAndHistoryBeyondFormer150RowLimit() {
        List<HistoryEntry> history = new ArrayList<>();
        for (int i = 0; i < 200; i++) history.add(history("0x" + i, i));
        List<ActivityTimeline.Row> rows = ActivityTimeline.merge(Collections.singletonList(receipt(null, 0, true, -1)), history, h -> true);
        assertEquals(201, rows.size());
        assertEquals(1, rows.stream().filter(r -> r.receipt != null && r.receipt.failed).count());
    }
}
