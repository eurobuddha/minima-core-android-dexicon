// VENDORED VERBATIM from apks/pandapools 0.9.97 app/src/main/java/com/eurobuddha/pandapools/ActivityTimeline.java by scripts/vendor-pandapools.sh — do not edit here; re-vendor.
package com.eurobuddha.pandapools;

import java.util.*;
import java.util.function.Predicate;

/** Join existing receipts and node history by exact mined ID, then order by event time.
 * Confirmation state never determines position or whether a receipt is retained. */
final class ActivityTimeline {
    static final class Row {
        final ActivityLog.Entry receipt;
        final HistoryEntry history;
        final long time;
        Row(ActivityLog.Entry receipt, HistoryEntry history) {
            this.receipt = receipt; this.history = history;
            time = history != null && history.timemilli > 0 ? history.timemilli : receipt == null ? 0 : receipt.ts;
        }
    }
    static List<Row> merge(List<ActivityLog.Entry> receipts, List<HistoryEntry> history,
                           Predicate<HistoryEntry> includeHistory) {
        Map<String, HistoryEntry> byId = new HashMap<>();
        for (HistoryEntry h : history) if (h.txpowid != null) byId.put(key(h.txpowid), h);
        Set<String> shown = new HashSet<>();
        List<Row> rows = new ArrayList<>();
        for (ActivityLog.Entry receipt : receipts) {
            HistoryEntry h = receipt.txpowid == null ? null : byId.get(key(receipt.txpowid));
            rows.add(new Row(receipt, h));
            if (h != null) shown.add(key(h.txpowid));
        }
        for (HistoryEntry h : history) if (includeHistory.test(h) && !shown.contains(key(h.txpowid)))
            rows.add(new Row(null, h));
        rows.sort((a, b) -> Long.compare(b.time, a.time));
        return rows;
    }
    private static String key(String id) { return id == null ? "" : id.toLowerCase(Locale.ROOT); }
}
