package com.eurobuddha.dexicon;

import static org.junit.Assert.*;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.TimeZone;

public class WalletActivityTest {
    static final String TO = "MxG08565QAWJ980T4BSUEEJ2WQ8VSZ808JA1HWT4R9U9AWNATFK2UY4MYFHY3EW";

    @Test public void aWithdrawRowShowsTheExactAmountAndTheFullAddress() throws Exception {
        JSONObject a = new JSONObject().put("id", "wal_1").put("kind", "withdraw").put("amount", "3580.8").put("token", "MINIMA")
                .put("to", TO).put("ts", 1L).put("stage", ActivityLog.POSTED).put("block", 2411563L)
                .put("txpowid", "0x0000181FDE1A2F39DAAC4512854C8AD861C700A5346064D81B83DF4ABDF525E4")
                .put("outs", new JSONArray().put("0xBAFCE30B00EF9BEF6D8140E6ACCD1E0F6814B167631C6F436829BC3F1F76CFFA"));
        Ledger.Item it = Ledger.activity(a, 2411570L, 2L, TimeZone.getTimeZone("UTC"));
        assertEquals("Withdrew 3580.8 MINIMA", it.title);
        assertEquals("CONFIRMED", it.chip);
        boolean full = false;
        for (Ledger.Proof p : it.proof) if (p.value.equals(TO)) full = true;
        assertTrue("the destination is listed in full (RULE 1)", full);
    }

    @Test public void aReceivedRowKeepsEveryDigit() throws Exception {
        JSONObject a = new JSONObject().put("id", "recv_x").put("kind", "received").put("amount", "0.03656040477").put("token", "MINIMA")
                .put("ts", 1L).put("stage", ActivityLog.POSTED).put("block", 2411563L);
        Ledger.Item it = Ledger.activity(a, 2411564L, 2L, TimeZone.getTimeZone("UTC"));
        assertEquals("Received 0.03656040477 MINIMA", it.title);
        assertEquals("IN BLOCK", it.chip);
    }

    @Test public void theSeenSetIsBoundedAndKeepsNewest() {
        java.util.List<String> ids = new java.util.ArrayList<>();
        for (int i = 0; i < 2100; i++) ids.add("C" + i);
        String json = com.eurobuddha.dexicon.account.AccountTestAccess.boundedAdd("[]", ids);
        assertTrue(json.contains("\"C2099\""));
        assertFalse(json.contains("\"C0\""));
    }

    @Test public void aWalletRowIsNeverLandedOrDroppedByGuessFromSpentInputs() throws Exception {
        JSONObject a = new JSONObject().put("id", "wal_x").put("kind", "withdraw").put("stage", ActivityLog.POSTED).put("ts", 1L).put("postBlock", 100L);
        ActivityLog.observe(a, 105L, true, 0L, 2L);          // inputs gone (it landed and moved on, or a later post reused them)
        assertEquals(0L, a.optLong("block", 0));
        assertEquals(ActivityLog.POSTED, a.optString("stage"));
        assertTrue(a.optBoolean("inputsGone"));
        ActivityLog.observe(a, 100L + ActivityLog.DROP_BLOCKS + 1, false, 0L, 3L);
        assertEquals("never 'dropped / not mined' once its coins are spent", ActivityLog.POSTED, a.optString("stage"));
        ActivityLog.observe(a, 170L, false, 0L, 2L + ActivityLog.REVIVE_MS);
        assertEquals(ActivityLog.LOST, a.optString("stage"));
        assertTrue(a.optString("reason").contains("check the recipient"));

        JSONObject b = new JSONObject().put("id", "wal_y").put("kind", "withdraw").put("stage", ActivityLog.POSTED).put("ts", 1L).put("postBlock", 100L);
        ActivityLog.observe(b, 100L + ActivityLog.DROP_BLOCKS + 1, false, 0L, 3L);   // inputs still unspent, nothing on chain
        assertEquals(ActivityLog.DROPPED, b.optString("stage"));
        ActivityLog.observe(b, 170L, false, 160L, 4L);       // a late sighting of its output revives it
        assertEquals(160L, b.optLong("block"));
    }
}
