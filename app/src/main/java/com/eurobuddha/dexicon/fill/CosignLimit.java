package com.eurobuddha.dexicon.fill;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;

/**
 * Review 23 Nov 2026: every co-sign spends one-time leaves, and a taker can send takes that fail only after the maker
 * signed (a post refused for size, a race to the mempool). Co-sign attempts are limited per taker and per order in a
 * sliding window, so one counterparty cannot wear the order keys down. In memory: a restart forgets the window.
 */
public final class CosignLimit {
    public static final long WINDOW_MS = 10 * 60_000L;
    public static final int PER_TAKER = 6, PER_ORDER = 12;
    private final Map<String, ArrayDeque<Long>> byTaker = new HashMap<>(), byOrder = new HashMap<>();

    /** True (and the attempt counted) when {@code taker} may have {@code orderId} co-signed now. */
    public synchronized boolean admit(String taker, String orderId, long now) {
        ArrayDeque<Long> t = recent(byTaker, taker, now), o = recent(byOrder, orderId, now);
        if (t.size() >= PER_TAKER || o.size() >= PER_ORDER) return false;
        t.addLast(now); o.addLast(now);
        return true;
    }

    private static ArrayDeque<Long> recent(Map<String, ArrayDeque<Long>> m, String key, long now) {
        m.values().removeIf(d -> { while (!d.isEmpty() && now - d.peekFirst() >= WINDOW_MS) d.pollFirst(); return d.isEmpty(); });
        return m.computeIfAbsent(key, x -> new ArrayDeque<>());
    }
}
