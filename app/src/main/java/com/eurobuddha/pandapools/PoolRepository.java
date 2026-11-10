// VENDORED VERBATIM from apks/pandapools 0.10.9 app/src/main/java/com/eurobuddha/pandapools/PoolRepository.java by scripts/vendor-pandapools.sh — do not edit here; re-vendor.
package com.eurobuddha.pandapools;

import java.util.ArrayList;
import java.util.List;

/**
 * ONE shared pool scanner for the whole app. Every tab used to own its own {@link PoolBook} and scan the
 * registry independently, so a single new block fired four identical scans in parallel (the "thundering
 * herd") — redundant node load and, on an uncapped core, four simultaneous near-256KB replies overflowing
 * the IPC. This wraps a single {@code PoolBook} and multicasts one scan's result to every subscribed tab.
 *
 * SINGLE-FLIGHT: while a scan is in flight, further {@link #refresh()} calls just flag a follow-up instead
 * of starting a parallel scan — so N tabs asking at once collapse to one scan. A {@code pendingRescan}
 * flag guarantees a refresh requested DURING a scan (e.g. right after posting a swap) still produces one
 * fresh follow-up, so reserves are never left stale.
 *
 * All methods run on the main thread ({@link NodeApi} funnels every node callback to main), so the
 * listener list and flags need no locking.
 */
public class PoolRepository {

    private final PoolBook book;
    private final List<PoolBook.Listener> listeners = new ArrayList<>();
    private List<Pool> cached;          // last successful scan result (null until the first completes)
    private boolean scanning = false;
    private boolean pendingRescan = false;

    public PoolRepository(NodeApi node) { this.book = new PoolBook(node); }

    /** Register a listener for every future scan result (idempotent). Does not itself trigger a scan. */
    public void subscribe(PoolBook.Listener l) {
        if (l != null && !listeners.contains(l)) listeners.add(l);
    }

    public void unsubscribe(PoolBook.Listener l) { listeners.remove(l); }

    /** Serve the cached pools to this listener immediately (instant repaint on tab-open) then kick a scan. */
    public void requestNow(PoolBook.Listener l) {
        subscribe(l);
        if (cached != null && l != null) l.onPools(cached);
        refresh();
    }

    /** Kick a registry scan. Single-flight: a scan already running just queues one follow-up. */
    public void refresh() {
        if (scanning) { pendingRescan = true; return; }
        scanning = true;
        pendingRescan = false;
        book.scan(new PoolBook.Listener() {
            @Override public void onPools(List<Pool> pools) {
                scanning = false;
                cached = pools;
                for (PoolBook.Listener l : new ArrayList<>(listeners)) l.onPools(pools);
                if (pendingRescan) refresh();
            }
            @Override public void onError(String msg) {
                scanning = false;
                for (PoolBook.Listener l : new ArrayList<>(listeners)) l.onError(msg);
                if (pendingRescan) refresh();
            }
        });
    }
}
