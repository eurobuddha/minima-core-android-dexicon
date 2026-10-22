package com.eurobuddha.dexicon.doors;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.SharedPreferences;

import com.eurobuddha.atomix.swap.SwapDb;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.util.ArrayList;
import java.util.List;

/** Port of mds/dexicon test/door.test.js for the Java door glue: naming, tx ids, the forward queue's state machine. */
@RunWith(RobolectricTestRunner.class)
public class DoorForwardsTest {
    static final String MX = "MxG080CRJB1D4NHGRYGNF7Q52FK7023UM3FUUPVD1W1WCQZSA8MDQ25982N842G", ETH = "0xdAC17F958D2ee523a2206206994597C13D831ec7";
    static final String H1 = "0x" + "11".repeat(32), H2 = "0x" + "22".repeat(32);

    SharedPreferences prefs() {
        SharedPreferences p = RuntimeEnvironment.getApplication().getSharedPreferences("door_test", Context.MODE_PRIVATE);
        p.edit().clear().commit();
        return p;
    }
    static SwapDb.Event ev(String token, String note) { SwapDb.Event e = new SwapDb.Event(); e.token = token; e.note = note; return e; }

    @Test public void namingIsMxUsdNeverTheLegacyLabel() {
        assertEquals("MxUSD", DoorForwards.label("mxUSDT"));
        assertEquals("MxUSD", DoorForwards.label("0x7D39745FBD29049BE29850B55A18BF550E4D442F930F86266E34193D89042A90"));
        assertEquals("MINIMA", DoorForwards.label("0x00"));
        assertEquals("USDT", DoorForwards.label("ETH:0xdac17f958d2ee523a2206206994597c13d831ec7"));
    }

    @Test public void addressGatesRefuseInjection() {
        assertTrue(DoorForwards.isMxAddr(MX));
        assertFalse(DoorForwards.isMxAddr(MX + " amount:1"));
        assertTrue(DoorForwards.isEthAddr(ETH));
        assertFalse(DoorForwards.isEthAddr("0xdac1"));
    }

    @Test public void txIdsSplitPerChainFullAndDeduped() {
        List<SwapDb.Event> evs = new ArrayList<>();
        evs.add(ev("ETH:0xdac17f958d2ee523a2206206994597c13d831ec7", H1));
        evs.add(ev("minima", H2)); evs.add(ev("0x00", H2)); evs.add(ev("ETH", "confirmed on-chain"));
        List<List<String>> ids = DoorForwards.txIds(evs);
        assertEquals(java.util.Collections.singletonList(H1), ids.get(0));
        assertEquals(java.util.Collections.singletonList(H2), ids.get(1));
    }

    @Test public void statusNamesTheMinimaNotifyOutput() {
        SwapDb.Swap s = new SwapDb.Swap(); s.sellToken = "USDT"; s.sellAmount = "1"; s.buyToken = "MINIMA"; s.buyAmount = "47"; s.status = SwapDb.ST_COMPLETE;
        assertTrue(DoorForwards.statusText(s, null).contains("0.0001 MINIMA"));
        s.buyToken = "mxUSDT";
        assertFalse(DoorForwards.statusText(s, null).contains("mxUSDT"));
    }

    @Test public void forwardQueueLifecycle() throws Exception {
        SharedPreferences p = prefs();
        assertFalse("bad Mx address refused", DoorForwards.queue(p, H1, "minima", "bad"));
        assertFalse("an Mx address is not an ETH destination", DoorForwards.queue(p, H1, "usdt", MX));
        assertTrue(DoorForwards.queue(p, H1, "minima", MX));
        assertTrue(DoorForwards.queue(p, H2, "usdt", ETH));
        assertEquals(2, DoorForwards.list(p).size());
        JSONObject f = DoorForwards.get(p, H1);
        assertEquals(H1, f.getString("swap"));
        assertEquals("wait", DoorForwards.decide(f, SwapDb.ST_STARTED));
        assertEquals("send", DoorForwards.decide(f, SwapDb.ST_COMPLETE));
        assertEquals("void", DoorForwards.decide(f, SwapDb.ST_REFUNDED));

        DoorForwards.outcome(f, "Insufficient funds", null, false);
        assertEquals(DoorForwards.PENDING, f.getString("status"));
        f.put("attempts", DoorForwards.MAX_ATTEMPTS - 1);
        DoorForwards.outcome(f, "Insufficient funds", null, false);
        assertEquals(DoorForwards.FAILED, f.getString("status"));
        DoorForwards.save(p, f);
        DoorForwards.retry(p, H1);
        assertEquals(DoorForwards.PENDING, DoorForwards.get(p, H1).getString("status"));

        JSONObject g = DoorForwards.get(p, H2);
        DoorForwards.outcome(g, "The node has not confirmed the outcome of that write; it may still complete.", null, true);
        assertEquals("a lost reply is never retried automatically", DoorForwards.UNCERTAIN, g.getString("status"));
        assertEquals("wait", DoorForwards.decide(g, SwapDb.ST_COMPLETE));

        JSONObject h = DoorForwards.get(p, H2);
        DoorForwards.outcome(h, null, H1, false);
        assertEquals(DoorForwards.SENT, h.getString("status"));
        assertEquals(H1, h.getString("tx"));
    }
}
