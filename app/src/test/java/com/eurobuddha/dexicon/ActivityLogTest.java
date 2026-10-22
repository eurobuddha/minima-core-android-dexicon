package com.eurobuddha.dexicon;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.*;

/** Stage machine of the ACTIVITY tab. The first two tests are ported from PandaPools TransactionSafetyTest
 *  (localTimeAndHeightNeverProveInclusion, confirmation wording) onto output-coin evidence. */
public class ActivityLogTest {
    private static final String OUT = "0x" + "AB".repeat(32), IN = "0x" + "CD".repeat(32);

    private static JSONObject posted(String route) throws Exception {
        return new JSONObject().put("id", "x").put("route", route).put("role", "taker").put("side", "buy").put("amount", "1000")
                .put("price", "0.0123").put("ts", 1_000L).put("stage", ActivityLog.POSTED).put("postBlock", 100L).put("tip", 100L)
                .put("outs", new JSONArray().put(OUT)).put("ins", new JSONArray().put(IN));
    }

    @Test public void localTimeAndHeightNeverProveInclusion() throws Exception {
        JSONObject a = posted("POOL");
        assertFalse(ActivityLog.confirmed(a, 999_999));            // a high tip alone proves nothing
        assertEquals(-1, ActivityLog.depth(a, 999_999));
        assertFalse(ActivityLog.observe(a, 150, false, 0, 1_000_000L) && a.optLong("block") > 0);
        assertEquals(0, a.optLong("block"));
        assertTrue(ActivityLog.statusText(a, 150, 0).startsWith("Posted · in the node's mempool, waiting for a block (50 blocks"));
    }

    @Test public void confirmationsCountFromTheOutputCoinsCreatedBlock() throws Exception {
        JSONObject a = posted("BOOK+POOL");
        assertTrue(ActivityLog.observe(a, 101, false, 101, 0));
        assertEquals(101, a.optLong("block"));
        assertEquals("In block 101 · 0 confirmations", ActivityLog.statusText(a, 101, 0));
        assertEquals("IN BLOCK", ActivityLog.chip(a, 101));
        assertEquals("In block 101 · 1 confirmation", ActivityLog.statusText(a, 102, 0));
        assertEquals("In block 101 · 2 confirmations", ActivityLog.statusText(a, 103, 0));
        assertFalse(ActivityLog.confirmed(a, 103));
        assertTrue(ActivityLog.confirmed(a, 104));
        assertEquals("CONFIRMED", ActivityLog.chip(a, 104));
        assertEquals("Confirmed · block 101 · 3 confirmations", ActivityLog.statusText(a, 104, 0));
        // the row's own tip (written by the service) counts even when the screen's block is stale
        a.put("tip", 110L);
        assertEquals("Confirmed · block 101 · 9 confirmations", ActivityLog.statusText(a, 0, 0));
        // a found output is never re-probed: a later "not found" (it was spent onward) changes nothing
        assertFalse(ActivityLog.observe(a, 110, true, 0, 0));
        assertEquals(101, a.optLong("block"));
    }

    @Test public void inputSpentElsewhereDropsAndALateBlockRevives() throws Exception {
        JSONObject a = posted("BOOK");
        assertTrue(ActivityLog.observe(a, 102, true, 0, 0));
        assertEquals(ActivityLog.DROPPED, a.optString("stage"));
        assertEquals("Dropped — an input was spent by another transaction", ActivityLog.statusText(a, 102, 0));
        assertTrue(ActivityLog.watch(a, 102, 1_000L + 60_000L));   // still probed for a while
        assertFalse(ActivityLog.watch(a, 102, 1_000L + ActivityLog.REVIVE_MS + 1));
        assertTrue(ActivityLog.observe(a, 103, false, 103, 0));
        assertEquals(ActivityLog.POSTED, a.optString("stage"));
        assertEquals(103, a.optLong("block"));
    }

    @Test public void notMinedAfterTheWindowIsDropped() throws Exception {
        JSONObject a = posted("POOL");
        assertFalse(ActivityLog.observe(a, 100 + ActivityLog.DROP_BLOCKS, false, 0, 0) && ActivityLog.DROPPED.equals(a.optString("stage")));
        assertTrue(ActivityLog.observe(a, 101 + ActivityLog.DROP_BLOCKS, false, 0, 0));
        assertEquals("Dropped — not mined after " + ActivityLog.DROP_BLOCKS + " blocks", ActivityLog.statusText(a, 0, 0));
        assertTrue(ActivityLog.settled(a, 0));
    }

    @Test public void fastTakeStagesSentCosignedSettlingDone() throws Exception {
        JSONObject a = new JSONObject().put("id", "f").put("route", "FAST").put("role", "taker").put("side", "sell").put("amount", "5")
                .put("price", "0.01").put("ts", 0L).put("stage", ActivityLog.SENT).put("relays", 3).put("sentMs", 10_000L);
        assertEquals("Take sent to 3 relays · waiting for the maker to co-sign (4s)", ActivityLog.statusText(a, 0, 14_000L));
        a.put("stage", ActivityLog.COSIGNED).put("cosignMs", 3_240L).put("outs", new JSONArray().put(OUT));
        assertEquals("CO-SIGNED", ActivityLog.chip(a, 0));
        assertTrue(ActivityLog.observe(a, 500, false, 0, 20_000L));      // first tip seen becomes the post block
        assertEquals(500, a.optLong("postBlock"));
        assertEquals("Maker co-signed in 3.2s · in the node's mempool, waiting for a block", ActivityLog.statusText(a, 500, 0));
        assertTrue(ActivityLog.observe(a, 501, false, 501, 20_000L));
        assertEquals("SETTLING", ActivityLog.chip(a, 502));
        assertEquals("Settling · in block 501 · 1 confirmation", ActivityLog.statusText(a, 502, 0));
        assertEquals("DONE", ActivityLog.chip(a, 504));
        assertEquals("Done · block 501 · 3 confirmations", ActivityLog.statusText(a, 504, 0));
    }

    @Test public void makerSideNamesTheTakerInFull() throws Exception {
        String taker = "Mx" + "Q".repeat(60);
        JSONObject a = new JSONObject().put("route", "FAST").put("role", "maker").put("stage", ActivityLog.COSIGNED)
                .put("cosignMs", 800L).put("counterparty", taker);
        assertTrue(ActivityLog.statusText(a, 0, 0).startsWith("Co-signed for " + taker + " in 0.8s"));
    }

    @Test public void unansweredTakeIsLostAndBuildingInterruptedIsLost() throws Exception {
        JSONObject a = new JSONObject().put("route", "FAST").put("stage", ActivityLog.SENT).put("sentMs", 0L);
        assertFalse(ActivityLog.observe(a, 1, false, 0, ActivityLog.LOST_MS) && ActivityLog.LOST.equals(a.optString("stage")));
        assertTrue(ActivityLog.observe(a, 1, false, 0, ActivityLog.LOST_MS + 1));
        assertEquals(ActivityLog.LOST, a.optString("stage"));
        assertTrue(ActivityLog.statusText(a, 0, 0).startsWith("Lost — no answer from the maker"));
        JSONObject b = new JSONObject().put("route", "POOL").put("stage", ActivityLog.BUILDING).put("ts", 0L);
        assertTrue(ActivityLog.observe(b, 1, false, 0, ActivityLog.LOST_MS + 1));
        assertEquals(ActivityLog.LOST, b.optString("stage"));
    }

    @Test public void refusedAndFailedAreTerminalWithTheirReason() throws Exception {
        JSONObject a = new JSONObject().put("route", "FAST").put("stage", ActivityLog.REFUSED).put("reason", "taken");
        assertEquals("REFUSED", ActivityLog.chip(a, 0));
        assertEquals("Refused by the maker — taken", ActivityLog.statusText(a, 0, 0));
        assertTrue(ActivityLog.settled(a, 0));
        assertFalse(ActivityLog.hasPending(Collections.singletonList(a), 0, 0));
        assertFalse(ActivityLog.observe(a, 99, true, 99, 0) && a.optLong("block") > 0);
    }

    @Test public void hasPendingOnlyWhileSomethingCanChange() throws Exception {
        JSONObject done = posted("BOOK").put("block", 10L).put("tip", 13L), live = posted("POOL");
        assertFalse(ActivityLog.hasPending(Collections.singletonList(done), 0, 0));
        assertTrue(ActivityLog.hasPending(Arrays.asList(done, live), 0, 0));
    }

    @Test public void postReplyYieldsFullOutputIdsWithMyOutputFirst() throws Exception {
        String mine = "0x" + "11".repeat(32), pool = "0x" + "22".repeat(32);
        String outPool = "0x" + "01".repeat(32), outMine = "0x" + "02".repeat(32), txn = "0x" + "EE".repeat(32);
        JSONObject reply = new JSONObject().put("status", true).put("response", new JSONObject().put("txpowid", "0x99")
                .put("body", new JSONObject().put("txn", new JSONObject().put("transactionid", txn)
                        .put("inputs", new JSONArray().put(new JSONObject().put("coinid", IN)))
                        .put("outputs", new JSONArray().put(new JSONObject().put("coinid", outPool).put("address", pool))
                                .put(new JSONObject().put("coinid", outMine).put("address", mine))))));
        JSONObject ids = ActivityLog.fromPostReply(reply, mine);
        assertEquals(txn, ids.optString("txnid"));
        assertEquals(outMine, ids.getJSONArray("outs").getString(0));   // never shortened
        assertEquals(outPool, ids.getJSONArray("outs").getString(1));
        assertEquals(IN, ids.getJSONArray("ins").getString(0));
        assertEquals(0, ActivityLog.fromPostReply(new JSONObject(), mine).getJSONArray("outs").length());
    }
}
