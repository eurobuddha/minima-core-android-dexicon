// VENDORED VERBATIM from apks/atomix 0.2.32 app/src/test/java/com/eurobuddha/atomix/swap/ReceiverAddressPortTest.java by scripts/vendor-atomix.sh — do not edit here; re-vendor.
package com.eurobuddha.atomix.swap;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import com.eurobuddha.comms.NodeApi;

import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/** Every new lock carries the receiver's address at port 253 (so the receiver's node tracks the lock and sees its
 *  spend); a node that cannot say leaves the lock exactly as before. */
public class ReceiverAddressPortTest {
    static final String RECEIVER_PK = "0x6FFE1A8B2B1944C71A4A51B331F1C8857AC33781E1A02E78A6DAF9467054316D";
    // what the node's runscript returns for RETURN SIGNEDBY(RECEIVER_PK) (dev node, 11 Nov 2026)
    static final String RECEIVER_ADDR = "0xD49510A2F1D9FD73E9BB558A446A3FDC6663F876C9A13D4821F09CAFC805BAC9";
    // runscript's RAW-script address for the same key in lowercase (dev node, 11 Nov 2026) — tracked by nobody
    static final String RAW_LOWERCASE_ADDR = "0xA2AA7F6B374028A97CC36E18057582C1A784DD87911F4B212EE1C71DEB1AE885";
    private Runnable onRunscript;

    private final List<String> commands = new ArrayList<>();
    private MinimaHtlc htlc;
    private String runscriptAddress;

    @Before public void setUp() {
        com.eurobuddha.comms.SignGate.resetForTest();
        NodeApi node = mock(NodeApi.class);
        doAnswer(inv -> {
            String c = inv.getArgument(0); NodeApi.Cb cb = inv.getArgument(1);
            commands.add(c);
            JSONObject resp = new JSONObject().put("txpowid", "0xTXPOW").put("miniaddress", "MxMYADDR").put("publickey", "0x0BC0FFEE");
            if (c.startsWith("runscript ") && runscriptAddress != null) {
                // like the node: "clean" is the cleaned (hex uppercased) script's address; "script" the raw text's
                boolean lower = c.contains(RECEIVER_PK.toLowerCase().substring(2));
                resp.put("script", new JSONObject().put("address", lower ? RAW_LOWERCASE_ADDR : runscriptAddress));
                resp.put("clean", new JSONObject().put("address", runscriptAddress));
                if (onRunscript != null) onRunscript.run();
            }
            JSONObject reply = new JSONObject().put("status", true).put("response", resp);
            if (c.startsWith("txncheck ")) reply = new JSONObject().put("status", true).put("response", new JSONObject()
                    .put("valid", new JSONObject().put("basic", true).put("mmrproofs", true).put("scripts", 1))
                    .put("validamounts", true).put("allsignaturesvalid", true).put("validtransaction", true));
            cb.onResult(reply);
            return null;
        }).when(node).cmd(anyString(), any(NodeApi.Cb.class));
        htlc = new MinimaHtlc(node);
        htlc.setup("MxMYADDR", "0x0BC0FFEE", new MinimaHtlc.SetupCb() {
            public void ok(String a, String p) {}
            public void err(String m) { throw new AssertionError(m); }
        });
        commands.clear();
    }

    static MinimaHtlc.PostCb post() {
        return new MinimaHtlc.PostCb() { public void ok(String t) {} public void err(String m) { throw new AssertionError(m); } };
    }

    @Test public void sendLockCarriesTheReceiverAddress() throws Exception {
        runscriptAddress = RECEIVER_ADDR;
        htlc.lock("0.15", "150000", "0xA0B86991C6218B36C1D19D4A2E9EB0CE3606EB48", RECEIVER_PK, "0xBEEFCAFE", "0xDEADBEEF", 987654, "FALSE", post());
        assertEquals("runscript script:\"RETURN SIGNEDBY(" + RECEIVER_PK + ")\"", commands.get(0));
        String send = commands.stream().filter(c -> c.startsWith("send ")).findFirst().get();
        JSONObject state = new JSONObject(send.substring(send.indexOf("state:") + 6, send.indexOf(" tokenid:")));
        assertEquals(RECEIVER_ADDR, state.getString("253"));
        assertEquals(RECEIVER_PK, state.getString("4"));
    }

    @Test public void coinLockCarriesTheReceiverAddress() {
        runscriptAddress = RECEIVER_ADDR;
        htlc.lockFromCoins(java.util.Collections.singletonList("0xC1"), "2", "1", "1", "minima", RECEIVER_PK, "0xAB", "0xCD", 987654, "FALSE", post());
        assertTrue(commands.stream().anyMatch(c -> c.endsWith(" port:253 value:" + RECEIVER_ADDR)));
        assertTrue("the lock output is unchanged", commands.stream().anyMatch(c -> c.startsWith("txnoutput ") && c.contains("amount:1 address:" + MinimaHtlc.HTLC_ADDRESS)));
    }

    /** The counter-leg receiver key comes off the Ethereum contract in lowercase: port 253 must still be the address the
     *  receiver's node tracks (the cleaned script's), not the raw text's. */
    @Test public void aLowercaseReceiverKeyStillGivesTheTrackedAddress() {
        runscriptAddress = RECEIVER_ADDR;
        String lower = "0x" + RECEIVER_PK.substring(2).toLowerCase();
        htlc.lockFromCoins(java.util.Collections.singletonList("0xC1"), "2", "1", "1", "minima", lower, "0xAB", "0xCD", 987654, "FALSE", post());
        assertTrue(commands.stream().anyMatch(c -> c.endsWith(" port:253 value:" + RECEIVER_ADDR)));
        assertFalse(commands.stream().anyMatch(c -> c.contains(RAW_LOWERCASE_ADDR)));
    }

    /** A host that switches market while the lookup is in flight: the lock is refused, never sent in the other token. */
    @Test public void aMarketSwitchDuringTheLookupRefusesTheLock() {
        runscriptAddress = RECEIVER_ADDR;
        htlc.setActiveToken("0x00");
        onRunscript = () -> htlc.setActiveToken(MinimaHtlc.USDT_TOKENID);
        final String[] err = {null};
        htlc.lock("0.15", "150000", "0xA0B86991C6218B36C1D19D4A2E9EB0CE3606EB48", RECEIVER_PK, "0xBEEFCAFE", "0xDEADBEEF", 987654, "FALSE",
                new MinimaHtlc.PostCb() { public void ok(String t) { throw new AssertionError("sent " + t); } public void err(String m) { err[0] = m; } });
        assertEquals(NodeApi.ERR_COMMAND_CHANGED, err[0]);
        assertFalse(commands.stream().anyMatch(c -> c.startsWith("send ")));
    }

    @Test public void aNodeThatCannotSayLeavesTheLockAsBefore() throws Exception {
        runscriptAddress = "not-an-address";
        htlc.lock("0.15", "150000", "0xA0B86991C6218B36C1D19D4A2E9EB0CE3606EB48", RECEIVER_PK, "0xBEEFCAFE", "0xDEADBEEF", 987654, "FALSE", post());
        String send = commands.stream().filter(c -> c.startsWith("send ")).findFirst().get();
        assertFalse(new JSONObject(send.substring(send.indexOf("state:") + 6, send.indexOf(" tokenid:"))).has("253"));
        commands.clear();
        htlc.lockFromCoins(java.util.Collections.singletonList("0xC1"), "2", "1", "1", "minima", RECEIVER_PK, "0xAB", "0xCD", 987654, "FALSE", post());
        assertFalse(commands.stream().anyMatch(c -> c.contains(" port:253 ")));
    }
}
