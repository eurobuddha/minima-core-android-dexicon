// VENDORED VERBATIM from apks/pandapools 0.9.90 app/src/test/java/com/eurobuddha/pandapools/PoolHistorySyncTest.java by scripts/vendor-pandapools.sh — do not edit here; re-vendor.
package com.eurobuddha.pandapools;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class PoolHistorySyncTest {
    private JSONObject tx(String side, String address) throws Exception {
        JSONObject coin = new JSONObject().put("address", address).put("amount", "42").put("tokenid", "0x00");
        return new JSONObject().put("txpowid", "0x1234")
                .put("header", new JSONObject().put("block", 500).put("timemilli", 123456789))
                .put("body", new JSONObject().put("txn", new JSONObject().put(side, new JSONArray().put(coin))));
    }
    @Test public void spentPoolIsFoundEvenWhenWithdrawalHasNoPoolOutput() throws Exception {
        assertTrue(PoolHistorySync.touches(tx("inputs", "0xABCD"), "0xabcd"));
        assertTrue(PoolHistorySync.touches(tx("outputs", "0xabcd"), "0xABCD"));
    }
    @Test public void unrelatedOrIncompleteReplyCannotBecomePoolEvidence() throws Exception {
        assertFalse(PoolHistorySync.touches(tx("inputs", "0xabcd"), "0x1234"));
        assertFalse(PoolHistorySync.touches(new JSONObject(), "0xabcd"));
        assertFalse(PoolHistorySync.touches(null, "0xabcd"));
        assertFalse(PoolHistorySync.touches(tx("inputs", "0xabcd"), ""));
    }
    @Test public void publicTransactionRetainsHeaderTimeWithoutInventingWalletDeltaOrProof() throws Exception {
        HistoryEntry row = HistoryEntry.from(tx("inputs", "0xabcd"), null);
        assertEquals(123456789, row.timemilli);
        assertEquals(500, row.block);
        assertEquals("0x1234", row.txpowid);
        assertTrue(row.deltaMap().isEmpty());
        assertEquals("0", row.amount);
        assertEquals(-1, row.verifiedDepth);
        assertEquals(0, row.verifiedAt);
    }
}
