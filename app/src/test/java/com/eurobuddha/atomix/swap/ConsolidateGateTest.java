// VENDORED VERBATIM from apks/atomix 0.1.82 app/src/test/java/com/eurobuddha/atomix/swap/ConsolidateGateTest.java by scripts/vendor-atomix.sh — do not edit here; re-vendor.
package com.eurobuddha.atomix.swap;

import com.eurobuddha.comms.NodeApi;
import com.eurobuddha.comms.SignGate;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.After;
import org.junit.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

public class ConsolidateGateTest {
    private MinimaHtlc htlc;
    private final List<String> commands = new ArrayList<>();
    @Before public void setup() {
        SignGate.resetForTest(); SwapEngine.endConsolidate();
        NodeApi node = mock(NodeApi.class); htlc = new MinimaHtlc(node);
        doAnswer(inv -> {
            commands.add(inv.getArgument(0));
            ((NodeApi.Cb) inv.getArgument(1)).onResult(new JSONObject().put("status", true)
                    .put("response", new JSONObject().put("txpowid", "0xAABB")));
            return null;
        }).when(node).cmd(anyString(), any(NodeApi.Cb.class));
    }
    @After public void cleanup() { SignGate.resetForTest(); SwapEngine.endConsolidate(); }
    private MinimaHtlc.PostCb cb() { return new MinimaHtlc.PostCb() {
        public void ok(String id) { assertEquals("0xAABB", id); }
        public void err(String error) { fail(error); }
    }; }
    @Test public void exactCommandAndPreview() {
        String command = "consolidate tokenid:" + MinimaHtlc.USDT_TOKENID + " coinage:3 maxcoins:20 maxsigs:5";
        htlc.consolidateCoins(20, cb());
        htlc.previewConsolidate(20, r -> {}, e -> fail(e));
        assertEquals(command, commands.get(0)); assertEquals(command + " dryrun:true", commands.get(1));
    }
    @Test public void consolidationWaitsBehindSigning() {
        SignGate.Release[] held = new SignGate.Release[1];
        SignGate.submit(release -> held[0] = release);
        htlc.consolidateCoins(20, cb()); assertTrue(commands.isEmpty());
        held[0].free(); assertEquals(1, commands.size());
    }
    @Test public void invalidInputCountNeverReachesNode() {
        for (int count : new int[]{0,2,21,Integer.MAX_VALUE})
            htlc.consolidateCoins(count, new MinimaHtlc.PostCb() {
                public void ok(String id) { fail("accepted invalid limit"); }
                public void err(String error) { assertNotNull(error); }
            });
        assertTrue(commands.isEmpty());
    }
    @Test public void processWideReservationIsExclusiveAndReleasable() {
        assertTrue(SwapEngine.beginConsolidate()); assertTrue(SwapEngine.isConsolidating());
        assertFalse(SwapEngine.beginConsolidate()); SwapEngine.endConsolidate();
        assertTrue(SwapEngine.beginConsolidate());
    }
    @Test public void existingResponderReservationBlocksConsolidation() throws Exception {
        java.lang.reflect.Field f = SwapEngine.class.getDeclaredField("CP_LOCKING"); f.setAccessible(true);
        java.util.Map<String,Long> locks = (java.util.Map<String,Long>) f.get(null);
        locks.put("test", 1L);
        try { assertFalse(SwapEngine.beginConsolidate()); }
        finally { locks.remove("test"); }
        assertTrue(SwapEngine.beginConsolidate());
    }
}
