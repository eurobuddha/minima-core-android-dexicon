// VENDORED VERBATIM from apks/atomix 0.2.5 app/src/test/java/com/eurobuddha/atomix/swap/PegLevelsTest.java by scripts/vendor-atomix.sh — do not edit here; re-vendor.
package com.eurobuddha.atomix.swap;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import android.content.SharedPreferences;

import org.junit.After;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

/**
 * The ladder-levels fix (user bug: the peg always regenerated 6 tranches, overriding a hand-set 1-level order).
 * applyPeg must generate exactly P_LEVELS tranches per side (clamped 1..MAX_LEVELS; default MAX_LEVELS).
 */
public class PegLevelsTest {

    /** A prefs mock backed by a map; a fresh oracle price of 1.0 is loaded via PriceOracle.init(). */
    private SharedPreferences prefsWith(String levels) {
        Map<String, Object> v = new HashMap<>();
        v.put(PriceOracle.P_ENABLE, Boolean.TRUE);
        v.put(PriceOracle.P_STEP, "1");
        v.put(PriceOracle.P_SIZE, "30");
        v.put(PriceOracle.P_LAST_OK, System.currentTimeMillis());   // fresh
        v.put(PriceOracle.P_LAST_PRICE, "1.0");
        if (levels != null) v.put(PriceOracle.P_LEVELS, levels);

        SharedPreferences p = mock(SharedPreferences.class);
        SharedPreferences.Editor ed = mock(SharedPreferences.Editor.class);
        when(p.edit()).thenReturn(ed);
        when(ed.putString(anyString(), nullable(String.class))).thenReturn(ed);
        when(ed.putBoolean(anyString(), anyBoolean())).thenReturn(ed);
        when(ed.putLong(anyString(), anyLong())).thenReturn(ed);
        when(ed.remove(anyString())).thenReturn(ed);
        when(p.getBoolean(anyString(), anyBoolean())).thenAnswer(i -> {
            Object o = v.get(i.<String>getArgument(0)); return o instanceof Boolean ? o : i.getArgument(1);
        });
        when(p.getString(anyString(), nullable(String.class))).thenAnswer(i -> {
            Object o = v.get(i.<String>getArgument(0)); return o instanceof String ? o : i.getArgument(1);
        });
        when(p.getLong(anyString(), anyLong())).thenAnswer(i -> {
            Object o = v.get(i.<String>getArgument(0)); return o instanceof Long ? o : i.getArgument(1);
        });
        return p;
    }

    private int pegLevels(String levels) {
        SharedPreferences prefs = prefsWith(levels);
        PriceOracle.resetForSwitch(prefs);   // zero the shared static price first
        PriceOracle.init(prefs);             // load a fresh 1.0 mid
        Order o = new Order();
        o.pairs.put(Order.PAIR_TOKENS[0], new Order.Pair(true, 0, 0, 0));
        int r = PriceOracle.applyPeg(o, prefs);
        assertTrue("peg should apply (got " + r + ")", r == PriceOracle.PEG_APPLIED || r == PriceOracle.PEG_WIDE);
        Order.Pair p = o.pairs.get(Order.PAIR_TOKENS[0]);
        assertEquals("symmetric ladder", p.asks.size(), p.bids.size());
        return p.asks.size();
    }

    @Test public void oneLevel()    { assertEquals(1, pegLevels("1")); }
    @Test public void threeLevels() { assertEquals(3, pegLevels("3")); }
    @Test public void defaultsToOneWhenUnset() { assertEquals(1, pegLevels(null)); }
    @Test public void clampsAboveMax() { assertEquals(Order.MAX_LEVELS, pegLevels("99")); }
    @Test public void clampsBelowOne() { assertEquals(1, pegLevels("0")); }

    @After public void resetStatic() {
        // Leave the process-wide oracle price zeroed so this suite can't affect others.
        SharedPreferences p = mock(SharedPreferences.class);
        SharedPreferences.Editor ed = mock(SharedPreferences.Editor.class);
        when(p.edit()).thenReturn(ed);
        when(ed.remove(anyString())).thenReturn(ed);
        PriceOracle.resetForSwitch(p);
    }
}
