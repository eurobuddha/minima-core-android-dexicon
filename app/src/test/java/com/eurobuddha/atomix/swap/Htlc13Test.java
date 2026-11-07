// VENDORED VERBATIM from apks/atomix 0.2.8 app/src/test/java/com/eurobuddha/atomix/swap/Htlc13Test.java by scripts/vendor-atomix.sh — do not edit here; re-vendor.
package com.eurobuddha.atomix.swap;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import com.eurobuddha.comms.NodeApi;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * HTLC v1.3 (review 7 Nov): v1.2 accepts a secret of any length, the Ethereum vault only 32 bytes. v1.3 adds one line.
 * The maker's counter-leg of a USDT-paid take locks at v1.3 for v1.3 takers; every scan covers both addresses; the order
 * hides v1.3-only asks from older clients by moving the ask balance to bal.m13.
 * (Script → address was checked on a node: runscript gives {@link MinimaHtlc#HTLC_ADDRESS_V13}; 32-byte secret claims,
 * 31 and 40 are refused; 0.2.7 adds the HEX type check — a 32-character STRING secret, ASCII or 48-byte UTF-8, is refused,
 * where v1.3 as first written let it through. New address re-derived on the node.)
 */
public class Htlc13Test {
    private final List<String> commands = new ArrayList<>();
    private MinimaHtlc htlc;
    private JSONObject coinsReply;

    @Before public void setUp() {
        com.eurobuddha.comms.SignGate.resetForTest();
        SwapEngine.HTLC13_TAKERS.clear();
        NodeApi node = mock(NodeApi.class);
        doAnswer(inv -> {
            String c = inv.getArgument(0); NodeApi.Cb cb = inv.getArgument(1); commands.add(c);
            if (c.startsWith("coins ")) { cb.onResult(coinsReply); return null; }
            JSONObject r = new JSONObject().put("status", true).put("response", new JSONObject()
                    .put("txpowid", "0xT").put("miniaddress", "MxME").put("publickey", "0x0B")
                    .put("valid", new JSONObject().put("basic", true).put("mmrproofs", true).put("scripts", 1))
                    .put("validamounts", true).put("allsignaturesvalid", true).put("validtransaction", true));
            cb.onResult(r); return null;
        }).when(node).cmd(anyString(), any(NodeApi.Cb.class));
        htlc = new MinimaHtlc(node);
        htlc.setup("MxME", "0x0B", new MinimaHtlc.SetupCb() {
            public void ok(String a, String p) { }
            public void err(String m) { throw new AssertionError(m); }
        });
    }
    @After public void after() { SwapEngine.HTLC13_TAKERS.clear(); }

    private static MinimaHtlc.PostCb post() {
        return new MinimaHtlc.PostCb() { public void ok(String t) { } public void err(String m) { throw new AssertionError(m); } };
    }

    @Test public void v13IsV12PlusTheLengthCheckOnly() {
        String v12 = MinimaHtlc.HTLC_SCRIPT.replace("LET version=1.2 ", "");
        String v13 = MinimaHtlc.HTLC_SCRIPT_V13.replace("LET version=1.3 ", "").replace("ASSERT HEX(secret) EQ secret ASSERT LEN(secret) EQ 32 ", "");
        assertEquals(v12, v13);
        assertTrue(MinimaHtlc.HTLC_SCRIPT_V13.indexOf("ASSERT LEN(secret) EQ 32") < MinimaHtlc.HTLC_SCRIPT_V13.indexOf("SHA2(secret)"));
        // 0.2.7: LEN counts a STRING's characters, SHA2 its UTF-8 bytes — the HEX type check must come first
        assertTrue(MinimaHtlc.HTLC_SCRIPT_V13.contains("ASSERT HEX(secret) EQ secret ASSERT LEN(secret) EQ 32 "));
        assertTrue("the refund branch runs before the new check",
                MinimaHtlc.HTLC_SCRIPT_V13.indexOf("SIGNEDBY(owner) AND (@BLOCK GT timelock)") < MinimaHtlc.HTLC_SCRIPT_V13.indexOf("LEN(secret)"));
    }

    @Test public void setupRegistersBothScripts() {
        assertEquals(2, commands.stream().filter(c -> c.startsWith("newscript ")).count());
        assertTrue(commands.get(0).contains(MinimaHtlc.HTLC_SCRIPT));
        assertTrue(commands.get(1).contains(MinimaHtlc.HTLC_SCRIPT_V13));
    }

    @Test public void counterLegLocksAtV13OnlyWhenAsked() {
        commands.clear();
        htlc.lockFromCoins(Collections.singletonList("0xC1"), "2", "1", "1", "minima", "0x0C", "0xAB", "0xCD", 100, "FALSE", null, true, post());
        assertTrue(commands.stream().anyMatch(c -> c.startsWith("txnoutput ") && c.contains("address:" + MinimaHtlc.HTLC_ADDRESS_V13) && c.contains("storestate:true")));
        assertFalse(commands.stream().anyMatch(c -> c.contains("address:" + MinimaHtlc.HTLC_ADDRESS + " ")));
        commands.clear();
        htlc.lockFromCoins(Collections.singletonList("0xC1"), "2", "1", "1", "minima", "0x0C", "0xAB", "0xCD", 100, "FALSE", post());
        assertTrue(commands.stream().anyMatch(c -> c.startsWith("txnoutput ") && c.contains("address:" + MinimaHtlc.HTLC_ADDRESS + " ")));
    }

    @Test public void settlementScansReadBothAddressesAndMergeTheRows() throws Exception {
        coinsReply = new JSONObject().put("status", true).put("response", new JSONArray().put(new JSONObject().put("coinid", "0x01")));
        commands.clear();
        final JSONArray[] got = {null};
        htlc.scanHtlcByHash("0xAB", 1, 256, a -> got[0] = a, e -> fail(e));
        assertEquals("one row per address", 2, got[0].length());
        for (String a : MinimaHtlc.HTLC_ADDRESSES) {
            assertTrue(commands.contains("coinnotify action:add address:" + a));
            assertTrue(commands.stream().anyMatch(c -> c.startsWith("coins ") && c.contains("address:" + a + " ")));
        }
        commands.clear(); got[0] = null;
        htlc.findMyLockByHash("0xAB", a -> got[0] = a, e -> fail(e));
        assertEquals(2, got[0].length());
        commands.clear(); got[0] = null;
        htlc.scanHtlcByHashDeep("0xAB", 2, 4096, a -> got[0] = a, e -> fail(e));
        assertEquals(2, got[0].length());
        assertTrue(commands.stream().filter(c -> c.startsWith("coins ")).allMatch(c -> c.contains("megammr:true")));
    }

    @Test public void findMyLockStillFailsClosedOnABadReply() throws Exception {
        coinsReply = new JSONObject().put("status", true).put("response", new JSONObject());
        final String[] err = {null};
        htlc.findMyLockByHash("0xAB", a -> fail("a non-list reply must never read as 'no lock'"), e -> err[0] = e);
        assertNotNull(err[0]);
    }

    @Test public void v13AsksAreHiddenFromOlderClients() throws Exception {
        Order o = new Order(); o.minimaPublicKey = "0x0B"; o.ethAddress = "0xAB"; o.minimaAvail = 50;
        Order.Pair p = new Order.Pair(true, 1.01, 0.99, 1); p.asks.add(new Order.Level(1.01, 10)); o.pairs.put("USDT", p);
        o.asksNeedHtlc13 = true;
        JSONObject wire = o.toJson();
        assertEquals(0, wire.getJSONObject("bal").getDouble("m"), 0);
        assertEquals(50, wire.getJSONObject("bal").getDouble("m13"), 0);
        Order seen = Order.fromJson(wire);
        assertTrue(seen.asksNeedHtlc13);
        assertEquals(50, seen.minimaAvail, 0);
        assertFalse(seen.pairs.get("USDT").asks.isEmpty());
        // an older client ignores m13 — read the same wire without it
        JSONObject old = new JSONObject(wire.toString()); old.getJSONObject("bal").remove("m13");
        assertTrue("no advertised ask funds = no asks shown", Order.fromJson(old).pairs.get("USDT").asks.isEmpty());
    }

    @Test public void handshakeCapabilityIsRememberedAndBounded() throws Exception {
        assertTrue(SwapTake.says13(new JSONObject().put("htlc", "1.3")));
        assertFalse(SwapTake.says13(new JSONObject()));
        String s = "";
        for (int i = 0; i < 300; i++) s = SwapTake.remember13(s, String.format("0x%064x", i));
        assertEquals(256, SwapTake.parse13(s).size());
        assertTrue(SwapTake.parse13(s).contains(MinimaHtlc.normKey(String.format("0x%064x", 299))));
        assertFalse(SwapTake.parse13("0xAB,not hex, tokenid:0x00").contains("NOT HEX"));
        SwapEngine.takerUnderstands13("0xab");
        assertTrue(SwapEngine.takerHas13("0xAB"));
        assertFalse(SwapEngine.takerHas13("0xCD"));
    }
}
