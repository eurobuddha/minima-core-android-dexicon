package com.eurobuddha.dexicon;

import static org.junit.Assert.*;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

@RunWith(RobolectricTestRunner.class)
public class WalletRowCapTest {
    @Test public void walletRowsNeverEvictALiveTradeRow() throws Exception {
        DexDb db = new DexDb(RuntimeEnvironment.getApplication());
        ActivityLog.add(db, new JSONObject().put("id", "fast_live").put("route", "FAST").put("stage", ActivityLog.SENT).put("ts", 1L));
        for (int i = 0; i < 100; i++)   // a dust flood: 100 incoming rows, all newer than the live take
            ActivityLog.addWallet(db, new JSONObject().put("id", "recv_" + i).put("kind", "received").put("amount", "0.000000001")
                    .put("token", "MINIMA").put("stage", ActivityLog.POSTED).put("block", 5L).put("ts", 1000L + i));
        assertNotNull("the in-flight take's row survives", db.activity("fast_live"));
        assertNull("wallet rows keep only their own newest 60", db.activity("recv_0"));
        assertNotNull(db.activity("recv_99"));
        ActivityLog.add(db, new JSONObject().put("id", "slow_1").put("route", "POOL").put("stage", ActivityLog.POSTED).put("ts", 5000L));
        assertNotNull("a trade insert never trims wallet rows", db.activity("recv_99"));
    }
}
