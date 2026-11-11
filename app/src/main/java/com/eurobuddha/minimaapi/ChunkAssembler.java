package com.eurobuddha.minimaapi;

import java.util.HashMap;
import java.util.Map;

/**
 * Reassembles a reply that Minima Core 1.8+ sends in pieces (upstream minima-core-android 2bb5f8dab27, 10 Nov 2026): a
 * reply over 64K characters arrives as {@code <prefix>.RESPONSE_CHUNKED} broadcasts carrying {@code RESPONSE_CHUNKED_SIZE}
 * (how many) and {@code RESPONSE_CHUNKED_POS} (which), so no single broadcast can overflow the parcel and kill this app.
 *
 * <p>Untrusted input (an exported receiver; the caller has already checked the pairing secret and that the reply is one
 * we asked for), so it is bounded: at most {@link #MAX_CHUNKS} pieces and {@link #MAX_CHARS} characters per reply, a
 * position must be inside the declared count, the count may not change, a repeated piece is ignored (first one wins),
 * and an unfinished reply is dropped after {@link #ABANDON_MS}. Identical file in every minimaapi copy.
 */
final class ChunkAssembler {
    static final int MAX_CHUNKS = 64;                 // 64 × 64K = 4M characters
    static final long MAX_CHARS = 4L * 1024 * 1024;
    static final long ABANDON_MS = 60_000L;

    private static final class Parts {
        final String[] parts; final long started; int have; long chars;
        Parts(int size, long now) { parts = new String[size]; started = now; }
    }

    private final Map<String, Parts> open = new HashMap<>();

    /** Thrown when a piece breaks the rules; the reply is then discarded. */
    static final class Refused extends Exception { Refused(String why) { super(why); } }

    /** Adds one piece. Returns the whole reply when the last piece arrives, else null (more to come, or a repeat). */
    synchronized String add(String id, int size, int pos, String part, long now) throws Refused {
        prune(now);
        if (id == null || part == null) throw new Refused("chunk without id or data");
        if (size < 1 || size > MAX_CHUNKS || pos < 0 || pos >= size) { open.remove(id); throw new Refused("chunk " + pos + " of " + size + " is out of bounds"); }
        Parts p = open.get(id);
        if (p == null) { p = new Parts(size, now); open.put(id, p); }
        else if (p.parts.length != size) { open.remove(id); throw new Refused("chunk count changed from " + p.parts.length + " to " + size); }
        if (p.parts[pos] != null) return null;
        if (p.chars + part.length() > MAX_CHARS) { open.remove(id); throw new Refused("reply exceeds " + MAX_CHARS + " characters"); }
        p.parts[pos] = part; p.have++; p.chars += part.length();
        if (p.have < size) return null;
        open.remove(id);
        StringBuilder all = new StringBuilder((int) p.chars);
        for (String s : p.parts) all.append(s);
        return all.toString();
    }

    synchronized void forget(String id) { if (id != null) open.remove(id); }
    synchronized void clear() { open.clear(); }
    synchronized int pending(long now) { prune(now); return open.size(); }

    private void prune(long now) { open.values().removeIf(p -> now - p.started > ABANDON_MS); }
}
