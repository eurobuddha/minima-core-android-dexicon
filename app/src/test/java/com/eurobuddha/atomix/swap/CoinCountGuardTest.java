// VENDORED VERBATIM from apks/atomix 0.1.82 app/src/test/java/com/eurobuddha/atomix/swap/CoinCountGuardTest.java by scripts/vendor-atomix.sh — do not edit here; re-vendor.
package com.eurobuddha.atomix.swap;

import com.eurobuddha.comms.NodeApi;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

public class CoinCountGuardTest {
    private MinimaHtlc htlc;
    private final List<String> commands = new ArrayList<>();
    private JSONObject reply;
    private String error;
    private int successes;

    @Before public void setup() throws Exception {
        NodeApi node = mock(NodeApi.class);
        htlc = new MinimaHtlc(node);
        reply = balance("12.0", false);
        doAnswer(inv -> {
            String c = inv.getArgument(0);
            commands.add(c);
            NodeApi.Cb cb = inv.getArgument(1);
            cb.onResult(c.startsWith("balance ") ? reply
                    : new JSONObject().put("status", true).put("response", new JSONArray()));
            return null;
        }).when(node).cmd(anyString(), any(NodeApi.Cb.class));
    }
    private JSONObject balance(String count, boolean array) throws Exception {
        JSONObject row = new JSONObject().put("coins", count).put("sendable", "10.25")
                .put("confirmed", "11.25").put("unconfirmed", "1");
        return new JSONObject().put("status", true).put("response", array ? new JSONArray().put(row) : row);
    }
    private void read(boolean relevant) {
        if (relevant) htlc.myRelevantCoins(a -> successes++, e -> error = e);
        else htlc.myFreeCoins(a -> successes++, e -> error = e);
    }
    private void refused() {
        assertNotNull(error); assertEquals(0, successes);
        assertEquals(1, commands.size()); assertTrue(commands.get(0).startsWith("balance "));
    }
    @Test public void fragmentedWalletNeverRequestsCoins() throws Exception {
        reply = balance("120", true); read(false); refused();
        assertTrue(error.startsWith(MinimaHtlc.ERR_TOO_MANY_COINS));
    }
    @Test public void diagnosticIsAlsoGuarded() throws Exception {
        reply = balance("54", false); read(true); refused();
    }
    @Test public void safeCountUsesExactFreeQuery() throws Exception {
        reply = balance("53.0", true); read(false);
        assertNull(error); assertEquals(1, successes);
        assertEquals("coins relevant:true sendable:true tokenid:" + MinimaHtlc.USDT_TOKENID + " coinage:1 checkmempool:true", commands.get(1));
    }
    @Test public void diagnosticUsesCompactState() {
        read(true); assertNull(error);
        assertEquals("coins relevant:true tokenid:" + MinimaHtlc.USDT_TOKENID + " simplestate:true", commands.get(1));
    }
    @Test public void bothBalanceShapesParseIdentically() throws Exception {
        for (boolean array : new boolean[]{false, true}) {
            reply = balance("12.0", array);
            htlc.tokenBalance(b -> { assertEquals(12, b.coins); assertEquals("10.25", b.sendable); }, e -> fail(e));
        }
    }
    @Test public void failedBalanceNeverRequestsCoins() throws Exception {
        reply = new JSONObject().put("status", false).put("error", "busy"); read(false); refused();
    }
    @Test public void missingCountNeverAuthorisesRead() throws Exception {
        reply.getJSONObject("response").remove("coins"); read(false); refused();
    }
    @Test public void invalidCountsFailClosed() throws Exception {
        for (String count : new String[]{"-1", "1.5", "NaN", "2147483648"}) {
            commands.clear(); error = null; reply = balance(count, false); read(false); refused();
        }
    }
    @Test public void emptyBalanceIsZero() throws Exception {
        reply.put("response", new JSONArray()); read(false); assertNull(error); assertEquals(1, successes);
    }
    @Test public void balanceIsNeverCached() throws Exception {
        read(false); commands.clear(); successes = 0;
        reply = balance("120", true); read(false); refused();
    }
    @Test public void nativeBudgetIsDistinct() throws Exception {
        htlc.setActiveToken(MinimaHtlc.MINIMA_TOKENID);
        reply = balance("200", true); read(false); assertEquals(1, successes);
        commands.clear(); successes = 0; reply = balance("201", true); read(false); refused();
    }
    @Test public void budgetStaysBelowObservedFatalParcel() {
        assertTrue(MinimaHtlc.PARCEL_CHAR_BUDGET * 2L < 270864L);
        assertEquals(53, MinimaHtlc.maxSafeCoinRows(MinimaHtlc.USDT_TOKENID));
        assertEquals(200, MinimaHtlc.maxSafeCoinRows(MinimaHtlc.MINIMA_TOKENID));
        assertTrue(MinimaHtlc.maxSafeCoinRows(MinimaHtlc.USDT_TOKENID) >= SwapEngine.MAX_LOCK_COINS);
    }
}
