package com.eurobuddha.dexicon;

import static org.junit.Assert.*;

import org.json.JSONObject;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

/** Owner 7 Nov: makers ranked by how fast they co-sign my takes; a slow one is marked, a recovered one forgiven. */
public class MakerSpeedTest {
    static final String PK = "0xAB000000000000000000000000000000000000000000000000000000000000CD";

    @Test public void averageMovesHalfwayAndIsCaseBlind() throws Exception {
        JSONObject m = new JSONObject();
        MakerSpeed.recordIn(m, PK, 30_000);                         // no answer in the sweep's wait
        MakerSpeed.recordIn(m, PK.toLowerCase(), 2_000);
        assertEquals(16_000, m.getJSONObject(PK.toLowerCase()).getLong("avg"));
        Map<String, Long> s = new HashMap<>(); s.put(PK.toLowerCase(), 16_000L);
        assertTrue(MakerSpeed.slow(s, PK));
        s.put(PK.toLowerCase(), 9_000L);                            // one more 2 s answer: (16+2)/2
        assertFalse(MakerSpeed.slow(s, PK));
        assertEquals(MakerSpeed.UNKNOWN_MS, MakerSpeed.avg(s, "0x01"));
        assertFalse("an unseen maker is never marked slow", MakerSpeed.slow(s, "0x01"));
    }

    @Test public void keepsAtMostMaxMakers() {
        JSONObject m = new JSONObject();
        for (int i = 0; i < MakerSpeed.MAX_MAKERS + 5; i++) MakerSpeed.recordIn(m, "0x" + i, 1000);
        assertEquals(MakerSpeed.MAX_MAKERS, m.length());
    }
}
