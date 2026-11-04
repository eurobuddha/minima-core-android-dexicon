// VENDORED VERBATIM from apks/atomix 0.1.87 app/src/test/java/com/eurobuddha/atomix/HandoverTest.java by scripts/vendor-atomix.sh — do not edit here; re-vendor.
package com.eurobuddha.atomix;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import android.content.SharedPreferences;

import com.eurobuddha.atomix.swap.OtcDb;
import com.eurobuddha.atomix.swap.PriceOracle;
import com.eurobuddha.atomix.swap.SwapDb;

import org.junit.After;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Dexicon handover (0.1.86): Dexicon may only uninstall AtomiX once nothing in either currency is in flight and the
 *  market is withdrawn — and once handed over, AtomiX starts nothing new (Handover.active gates the engine). */
public class HandoverTest {

    private SharedPreferences prefs(final Map<String, Object> m) {
        SharedPreferences p = mock(SharedPreferences.class);
        SharedPreferences.Editor ed = mock(SharedPreferences.Editor.class);
        when(p.getString(anyString(), nullable(String.class))).thenAnswer(i -> {
            Object o = m.get(i.<String>getArgument(0)); return o instanceof String ? o : i.getArgument(1); });
        when(p.getBoolean(anyString(), anyBoolean())).thenAnswer(i -> {
            Object o = m.get(i.<String>getArgument(0)); return o instanceof Boolean ? o : i.getArgument(1); });
        when(p.edit()).thenReturn(ed);
        when(ed.putBoolean(anyString(), anyBoolean())).thenAnswer(i -> { m.put(i.getArgument(0), i.getArgument(1)); return ed; });
        when(ed.putLong(anyString(), anyLong())).thenAnswer(i -> { m.put(i.getArgument(0), i.getArgument(1)); return ed; });
        when(ed.remove(anyString())).thenAnswer(i -> { m.remove(i.<String>getArgument(0)); return ed; });
        when(ed.commit()).thenReturn(true);
        return p;
    }

    private static SwapDb.Swap swap(String sell, String status, String hash) {
        SwapDb.Swap s = new SwapDb.Swap();
        s.hash = hash; s.sellToken = sell; s.buyToken = "USDT"; s.status = status;
        return s;
    }

    private static OtcDb.Deal deal(String status) {
        OtcDb.Deal d = new OtcDb.Deal();
        d.ref = "ab".repeat(16); d.currency = "minima"; d.status = status;
        return d;
    }

    @After public void reset() { Handover.takeBack(prefs(new HashMap<>())); }

    @Test public void anyNonTerminalSwapInEitherCurrencyBlocks() {
        String h1 = "0x" + "11".repeat(32), h2 = "0x" + "22".repeat(32);
        List<String> r = Handover.inFlight(Arrays.asList(
                swap("mxUSDT", SwapDb.ST_LOCKED, h1),
                swap("MINIMA", SwapDb.ST_STARTED, h2),
                swap("MINIMA", SwapDb.ST_COMPLETE, "x"),
                swap("mxUSDT", SwapDb.ST_REFUNDED, "y"),
                swap("mxUSDT", SwapDb.ST_ERROR, "z")), Collections.emptyList());
        assertEquals(2, r.size());
        assertTrue(r.get(0).contains("USDT⇄MxUSD") && r.get(0).endsWith(h1));   // full hash, MxUSD naming
        assertTrue(r.get(1).contains("USDT⇄MINIMA") && r.get(1).contains(h2));
    }

    @Test public void openOtcDealBlocksFinishedDoesNot() {
        assertEquals(1, Handover.inFlight(Collections.emptyList(), Arrays.asList(
                deal(OtcDb.ST_AGREED), deal(OtcDb.ST_COMPLETE), deal(OtcDb.ST_EXPIRED), deal(OtcDb.ST_REJECTED))).size());
        assertTrue(Handover.inFlight(new ArrayList<>(), new ArrayList<>()).isEmpty());
    }

    @Test public void withdrawQuiescesMarketAndFlagsPendingPosts() {
        Map<String, Object> m = new HashMap<>();
        m.put("auto_publish", true); m.put("otc_enable", true); m.put("otc_auto", true);
        SharedPreferences p = prefs(m);
        assertEquals(2, Handover.marketLive(p).size());
        assertFalse(Handover.active());

        Handover.withdraw(p);
        assertTrue("new swaps stop at once", Handover.active());
        assertEquals(false, m.get("auto_publish"));
        assertEquals(false, m.get("otc_enable"));
        assertEquals(false, m.get("otc_auto"));
        assertEquals(true, m.get(PriceOracle.P_WITHDRAWN));
        // Not done until SwapService has the tombstone + OTC withdrawal on the wire.
        assertEquals(2, Handover.marketLive(p).size());
        m.put(Handover.P_TOMB_PENDING, false); m.put(Handover.P_OTC_PENDING, false);
        assertTrue(Handover.marketLive(p).isEmpty());
        assertFalse(Handover.done(p));
    }

    @Test public void noLiveMarketNeedsNoPost() {
        Map<String, Object> m = new HashMap<>();
        SharedPreferences p = prefs(m);
        Handover.withdraw(p);
        assertTrue(Handover.marketLive(p).isEmpty());
    }

    @Test public void doneSurvivesReloadAndTakeBackClears() {
        Map<String, Object> m = new HashMap<>();
        SharedPreferences p = prefs(m);
        Handover.setDone(p, 123L);
        Handover.takeBack(prefs(new HashMap<>()));   // a different store — clear the static only
        Handover.load(p);
        assertTrue(Handover.active());
        assertTrue(Handover.done(p));
        Handover.takeBack(p);
        assertFalse(Handover.active());
        Handover.load(p);
        assertFalse(Handover.active());
    }
}
