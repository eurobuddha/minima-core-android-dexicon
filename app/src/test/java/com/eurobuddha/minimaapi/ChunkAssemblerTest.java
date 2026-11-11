package com.eurobuddha.minimaapi;

import static org.junit.Assert.*;

import org.junit.Test;

/** Minima Core 1.8+ chunked replies, put back together with hard bounds (identical test in every minimaapi copy). */
public class ChunkAssemblerTest {
    static final long T = 1_000_000L;

    @Test public void inOrderOutOfOrderAndRepeatsGiveTheWholeReply() throws Exception {
        ChunkAssembler a = new ChunkAssembler();
        assertNull(a.add("r", 3, 2, "C", T));
        assertNull(a.add("r", 3, 0, "A", T));
        assertNull("a repeated piece is ignored", a.add("r", 3, 0, "X", T));
        assertEquals("ABC", a.add("r", 3, 1, "B", T));
        assertEquals("finished replies are not kept", 0, a.pending(T));
        assertEquals("one piece is a whole reply", "{}", new ChunkAssembler().add("s", 1, 0, "{}", T));
    }

    @Test public void outOfBoundsChangedCountAndOversizeAreRefused() throws Exception {
        ChunkAssembler a = new ChunkAssembler();
        for (int[] bad : new int[][]{{0, 0}, {3, 3}, {3, -1}, {ChunkAssembler.MAX_CHUNKS + 1, 0}}) {
            try { a.add("r", bad[0], bad[1], "x", T); fail("accepted size " + bad[0] + " pos " + bad[1]); } catch (ChunkAssembler.Refused expected) { }
        }
        a.add("c", 3, 0, "x", T);
        try { a.add("c", 4, 1, "x", T); fail("count change accepted"); } catch (ChunkAssembler.Refused expected) { }
        assertEquals("a refused reply is discarded", 0, a.pending(T));
        String big = new String(new char[64 * 1024]);
        try { for (int i = 0; i < ChunkAssembler.MAX_CHUNKS; i++) a.add("big", ChunkAssembler.MAX_CHUNKS, i, big + "!", T); fail("over 4M accepted"); }
        catch (ChunkAssembler.Refused expected) { assertTrue(expected.getMessage().contains("exceeds")); }
        try { a.add(null, 1, 0, "x", T); fail("null id accepted"); } catch (ChunkAssembler.Refused expected) { }
    }

    @Test public void anUnfinishedReplyIsDroppedAfterTheAbandonTime() throws Exception {
        ChunkAssembler a = new ChunkAssembler();
        a.add("r", 2, 0, "A", T);
        assertEquals(1, a.pending(T + ChunkAssembler.ABANDON_MS));
        assertEquals(0, a.pending(T + ChunkAssembler.ABANDON_MS + 1));
        assertNull("a late piece starts over, never completes the old reply", a.add("r", 2, 1, "B", T + ChunkAssembler.ABANDON_MS + 2));
    }
}
