package com.eurobuddha.dexicon.resting;
/* Vendored from apks/pandadex app/src/main/java/com/eurobuddha/pandadex/BookRepository.java (PandaDEX 0.4.23).
 * Keep as close to verbatim as possible; only the package, the NodeApi/SignGate imports and the minimal Dexicon
 * glue noted inline differ. Per the family naming rule the Minima dollar token is MxUSD in identifiers too
 * (PandaDEX MXUSD_ID/MXUSD_DP/*Mxusd are MXUSD_ID/MXUSD_DP/*Mxusd here). Re-copy rather than hand-edit when PandaDEX changes. */


import android.os.Handler;
import android.os.Looper;

import com.eurobuddha.dexicon.NodeApi;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The ONE shared book scan (PoolRepository pattern): single-flight with a coalesced
 * pending-rescan, an in-memory + SQLite cache served instantly to any subscriber, a 4s
 * min-interval throttle protecting the node's single command thread, and the FillTape fed
 * exactly once per good scan. All main-thread (NodeApi marshals callbacks there).
 */
public final class BookRepository {

    public interface Listener {
        void onBook(Map<String, Order5> orders, boolean syncing);
    }

    private static final long MIN_INTERVAL_MS = 4_000;
    /** Consecutive empty scans before we believe the book really is empty. */
    static final int EMPTY_CONFIRM = 3;

    private final NodeApi node;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final List<Listener> listeners = new ArrayList<>();

    private Map<String, Order5> cached = new LinkedHashMap<>();
    private boolean closed, current;
    private boolean haveLive = false;     // a live scan has landed this process
    private boolean scanning = false;
    private boolean pendingRescan = false;
    private long lastScanMs = 0;
    private int emptyScans = 0;
    private long chainBlock = 0;

    /** Dexicon glue: no SQLite snapshot, no FillTape (PandaDEX persists the last-good book and feeds its tape here). */
    public BookRepository(NodeApi node) {
        this.node = node;
    }

    public Map<String, Order5> book() { return cached; }
    public boolean current() { return current; }
    public void close() { closed = true; pendingRescan = false; listeners.clear(); ui.removeCallbacksAndMessages(null); }

    public long chainBlock() { return chainBlock; }

    public void setChainBlock(long b) { chainBlock = b; }

    public void subscribe(Listener l) {
        if (!listeners.contains(l)) listeners.add(l);
        l.onBook(cached, !haveLive);
    }

    public void unsubscribe(Listener l) { listeners.remove(l); }

    /** Serve the cache instantly, then refresh. */
    public void requestNow(Listener l) {
        l.onBook(cached, !haveLive);
        refresh();
    }

    /** Coalesced, throttled refresh — safe to call from anywhere, any number of times. */
    public void refresh() {
        if (closed) return;
        if (scanning) { pendingRescan = true; return; }
        long now = System.currentTimeMillis();
        long wait = lastScanMs + MIN_INTERVAL_MS - now;
        if (wait > 0) {
            if (!pendingRescan) {
                pendingRescan = true;
                ui.postDelayed(() -> { if (pendingRescan && !scanning) { pendingRescan = false; refresh(); } }, wait);
            }
            return;
        }
        scanning = true;
        lastScanMs = now;
        BookScanner.scan(node, (orders, truncated, rawJsons) -> {
            scanning = false;
            if (closed) return;
            // An EMPTY scan is not proof of an empty book — a partial or momentarily-empty
            // reply parses perfectly and is indistinguishable from "everything traded". But
            // DISTRUST MUST EXPIRE: the first cut refused an empty book unconditionally, so
            // once the book legitimately emptied (cancel every order — exactly what a user
            // does) the cache froze permanently. The stale orders stayed on screen forever and
            // cancel rows could never clear, because they clear when the coin leaves the book.
            // Demand repeated confirmation, then believe it.
            // Count ONLY believable scans. A transport failure (timeout while the node grinds
            // PoW, ERR_TOO_LONG, ERR_NOT_ENABLED) also arrives as an empty map — counting
            // those let three failures spend the entire confirmation budget, so the next
            // genuine-looking empty reply was accepted with no confirmation at all, wiping
            // the cache AND the persisted snapshot.
            if (!truncated) {
                if (orders.isEmpty() && !cached.isEmpty()) emptyScans++; else emptyScans = 0;
            }
            boolean believable = believable(truncated, orders.isEmpty(), cached.isEmpty(),
                    emptyScans, haveLive);
            current = believable;
            try {
                if (believable) {
                    cached = orders;
                    haveLive = true;
                }
            } catch (RuntimeException e) {
                current = false;
                believable = false; // A failed durable enqueue must not advance the disk snapshot.
            }
            // truncated → keep last-good cache (Limit lesson); still notify so views show "syncing"
            for (Listener l : new ArrayList<>(listeners)) l.onBook(cached, !believable);
            if (pendingRescan) {
                pendingRescan = false;
                refresh();
            }
        });
    }

    /**
     * Is this scan worth believing — and therefore worth writing through to the cache AND the
     * persisted snapshot? Pure, because this one rule has now been wrong twice: once counting
     * transport failures toward the empty-book budget, and once refusing to persist a
     * confirmed-empty book (which left ghost orders on every cold start).
     *
     * A truncated scan is never believed. An empty scan is believed only after
     * {@link #EMPTY_CONFIRM} consecutive believable empties — unless the cache is already
     * empty, in which case there is nothing to contradict.
     */
    static boolean believable(boolean truncated, boolean ordersEmpty, boolean cachedEmpty,
                              int emptyScans, boolean haveLive) {
        if (truncated) return false;
        // Before the FIRST live scan the cache is a guess loaded from disk, not an observation.
        // The confirmation gate exists to stop one bad read destroying a book we have actually
        // seen — it must not defend a stale snapshot against fresh evidence, or a cold start
        // after cancelling everything paints dead orders until three scans talk it round.
        if (!haveLive) return true;
        boolean suspectEmpty = ordersEmpty && !cachedEmpty && emptyScans < EMPTY_CONFIRM;
        return !suspectEmpty;
    }
}
