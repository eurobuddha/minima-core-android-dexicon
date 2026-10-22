// VENDORED VERBATIM from apks/atomix 0.1.83 app/src/test/java/com/eurobuddha/atomix/swap/IdentityBlockTest.java by scripts/vendor-atomix.sh — do not edit here; re-vendor.
package com.eurobuddha.atomix.swap;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import com.eurobuddha.comms.NodeApi;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;

/**
 * STEP (0.1.19): setup() must REPORT an orphaned identity, never silently replace it.
 *
 * 0.1.18 re-picked a fresh identity here (self-heal). That hid the thing that costs money: coins had been
 * routed to a key the node can no longer derive, and swapping the identity out made the app look healthy
 * while those funds stayed unreachable. Per user decision the app now adopts the saved key and HALTS
 * (IdentityWatch), so settlement of in-flight swaps still signs with the key those swaps were made with,
 * while a rescue + clean reinstall is the only way forward.
 */
public class IdentityBlockTest {

    private static final String OLD_PK = "0x800054A528AC4C1A85E21D88EAA27923C032C317C5542F90A3A66ED0C409CD9D";
    private static final String OLD_ADDR = "MxOLDADDR";

    private MinimaHtlc htlc;
    private JSONArray nodeKeys;
    private int getaddressCalls;

    private NodeApi fakeNode() {
        NodeApi node = org.mockito.Mockito.mock(NodeApi.class);
        org.mockito.Mockito.doAnswer(inv -> {
            String command = inv.getArgument(0);
            NodeApi.Cb cb = inv.getArgument(1);
            JSONObject r = new JSONObject().put("status", true);
            if (command.startsWith("keys")) r.put("response", new JSONObject().put("keys", nodeKeys));
            else if (command.startsWith("getaddress")) {
                getaddressCalls++;
                r.put("response", new JSONObject()
                        .put("miniaddress", "MxNEWADDR" + getaddressCalls)
                        .put("publickey", "0xNEWPK" + getaddressCalls));
            } else r.put("response", new JSONObject());
            cb.onResult(r);
            return null;
        }).when(node).cmd(org.mockito.ArgumentMatchers.anyString(),
                          org.mockito.ArgumentMatchers.any(NodeApi.Cb.class));
        return node;
    }

    @Before public void setUp() {
        htlc = new MinimaHtlc(fakeNode());
        nodeKeys = new JSONArray();
        getaddressCalls = 0;
    }

    private String[] runSetup(String addr, String pk) {
        final String[] out = new String[3];
        htlc.setup(addr, pk, new MinimaHtlc.SetupCb() {
            @Override public void ok(String a, String p) { out[0] = a; out[1] = p; }
            @Override public void err(String m) { out[2] = m; }
        });
        return out;
    }

    @Test public void orphanedIdentityIsKeptAndReported_neverReplaced() throws Exception {
        nodeKeys.put(new JSONObject().put("publickey", "0xSOMEOTHERKEY"));   // populated, ours absent
        String[] r = runSetup(OLD_ADDR, OLD_PK);
        assertEquals("the saved identity is still adopted (in-flight swaps must keep settling)", OLD_ADDR, r[0]);
        assertEquals(OLD_PK, r[1]);
        assertEquals("no self-heal: getaddress must NOT be called", 0, getaddressCalls);
        assertNull(r[2]);
    }

    @Test public void ownedIdentityIsKeptVerbatim() throws Exception {
        nodeKeys.put(new JSONObject().put("publickey", OLD_PK));
        String[] r = runSetup(OLD_ADDR, OLD_PK);
        assertEquals(OLD_ADDR, r[0]);
        assertEquals(OLD_PK, r[1]);
        assertEquals(0, getaddressCalls);
    }

    /** A busy/locked node returns no keys: that is "cannot verify", not "orphaned". */
    @Test public void emptyKeyListKeepsTheSavedIdentity() {
        String[] r = runSetup(OLD_ADDR, OLD_PK);
        assertEquals(OLD_ADDR, r[0]);
        assertEquals(OLD_PK, r[1]);
        assertEquals(0, getaddressCalls);
    }

    /** First run (nothing persisted) still has to pick an identity from the node. */
    @Test public void firstRunPicksAnIdentity() {
        String[] r = runSetup("", "");
        assertEquals("MxNEWADDR1", r[0]);
        assertEquals("0xNEWPK1", r[1]);
        assertEquals(1, getaddressCalls);
    }
}
