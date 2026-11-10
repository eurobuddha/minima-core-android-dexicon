// VENDORED VERBATIM from apks/pandapools 0.10.9 app/src/test/java/com/eurobuddha/pandapools/PoolActivityTest.java by scripts/vendor-pandapools.sh — do not edit here; re-vendor.
package com.eurobuddha.pandapools;

import org.json.*;
import org.junit.Test;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.Assert.*;

public class PoolActivityTest {
    private static final String POOL = "0xaaa1", TOKEN = "0xbb01";
    private static final TxClassifier.Addrs ADDRS = coins -> {
        try {
            JSONArray a = new JSONArray(coins);
            for (int i = 0; i < a.length(); i++) if (POOL.equalsIgnoreCase(a.getJSONObject(i).optString("addr"))) return POOL;
        } catch (Exception ignored) { }
        return null;
    };
    private String coins(String m, String t) throws Exception {
        return new JSONArray().put(new JSONObject().put("addr", POOL).put("amount", m).put("tokenid", "0x00"))
                .put(new JSONObject().put("addr", POOL).put("amount", t).put("tokenid", TOKEN)).toString();
    }
    private HistoryEntry tx(String input, String output) {
        HistoryEntry e = new HistoryEntry(); e.inputs = input; e.outputs = output;
        e.deltas = "{}"; e.direction = "self"; e.txpowid = "0x1234"; e.timemilli = 1000; return e;
    }
    @Test public void withdrawalUsesActualSpentReservesAndHeaderTimeOnBothPhones() throws Exception {
        HistoryEntry s23 = tx(coins("175157.93892489164", "784.52331493"), "[]");
        HistoryEntry fold = tx(s23.inputs, s23.outputs); s23.syncedAt = 5000; fold.syncedAt = 900000;
        PoolActivity.Event a = PoolActivity.from(s23, ADDRS).get(0), b = PoolActivity.from(fold, ADDRS).get(0);
        assertEquals(GlobalFeed.WITHDRAW, a.kind);
        assertEquals(new BigDecimal("784.52331493"), a.token);
        assertEquals(a.transaction.timemilli, b.transaction.timemilli);
        assertEquals(1000, b.transaction.timemilli); assertEquals(s23.txpowid, b.transaction.txpowid);
    }
    @Test public void strangersSwapIsDerivedFromPoolCoinsDespiteZeroWalletDelta() throws Exception {
        PoolActivity.Event e = PoolActivity.from(tx(coins("1000", "5"), coins("1010", "4.95")), ADDRS).get(0);
        assertEquals(GlobalFeed.SWAP, e.kind); assertTrue(e.minimaIn);
        assertEquals(new BigDecimal("10"), e.minima); assertEquals(new BigDecimal("0.05"), e.token);
    }
    @Test public void creationAndAdditionRemainSeparateAndMaintenanceProducesNoTrade() throws Exception {
        assertEquals(GlobalFeed.CREATE, PoolActivity.from(tx("[]", coins("1000", "5")), ADDRS).get(0).kind);
        assertEquals(GlobalFeed.ADD, PoolActivity.from(tx(coins("1000", "5"), coins("2000", "10")), ADDRS).get(0).kind);
        assertTrue(PoolActivity.from(tx(coins("1000", "5"), coins("1000", "5")), ADDRS).isEmpty());
    }
    @Test public void transactionPresenceDoesNotInventConfirmationProof() throws Exception {
        PoolActivity.Event e = PoolActivity.from(tx(coins("1000", "5"), "[]"), ADDRS).get(0);
        assertEquals(-1, e.transaction.verifiedDepth); assertEquals(0, e.transaction.verifiedAt);
    }
}
