package com.eurobuddha.dexicon.account;

import static org.junit.Assert.*;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class VirtualWalletTest {

    static final class Store implements VirtualWallet.Store { int n; public int keyCount() { return n; } public void setKeyCount(int x) { n = x; } }

    /** Records every node call; answers scripts/newscript and empty coin lists. */
    static final class FakeNode implements VirtualWallet.Node {
        final List<String> calls = new ArrayList<>();
        public JSONObject call(String cmd) throws Exception {
            calls.add(cmd);
            if (cmd.startsWith("scripts address:")) return new JSONObject().put("status", false).put("error", "not found");
            if (cmd.startsWith("newscript")) return new JSONObject().put("status", true).put("response", new JSONObject());
            if (cmd.startsWith("coins")) return new JSONObject().put("status", true).put("response", new JSONArray());
            if (cmd.equals("block")) return new JSONObject().put("status", true).put("response", new JSONObject().put("block", "100").put("timemilli", String.valueOf(System.currentTimeMillis())));
            if (cmd.startsWith("balance")) return new JSONObject().put("status", true).put("response", new JSONArray());
            return new JSONObject().put("status", false).put("error", "unexpected " + cmd);
        }
    }

    VirtualWallet wallet(FakeNode n) {
        return new VirtualWallet(n, AccountKeys.fromPhrase(AccountKeysTest.PHRASE, new AccountKeysTest.Mem(), null), new Store(), Collections.emptySet());
    }

    @Test public void chainReadsPassThroughUntouched() throws Exception {
        VirtualWallet w = wallet(new FakeNode());
        assertNull(w.handle("block"));
        assertNull(w.handle("coins address:MxG080CRJB1D4NHGRYGNF7Q52FK7023UM3FUUPVD1W1WCQZSA8MDQ25982N842G order:desc depth:64"));
        assertNull(w.handle("newscript script:\"RETURN TRUE\" trackall:false"));
        assertNull(w.handle("coinexport coinid:0x1234"));
    }

    @Test public void walletVerbsAreAnsweredFromTheAccount() throws Exception {
        FakeNode n = new FakeNode();
        VirtualWallet w = wallet(n);
        JSONObject a = w.handle("getaddress");
        assertTrue(a.getBoolean("status"));
        String addr = a.getJSONObject("response").getString("address");
        assertEquals(addr, w.handle("getaddress").getJSONObject("response").getString("address"));   // stable, unlike a node
        JSONObject k = w.handle("keys");
        assertEquals(1, k.getJSONObject("response").getJSONArray("keys").length());
        JSONObject na = w.handle("newaddress");
        assertTrue(na.getBoolean("status"));
        assertEquals(2, na.getJSONObject("response").getInt("total"));
        assertTrue("newaddress registers the script with the node before handing it out",
                n.calls.stream().anyMatch(c -> c.startsWith("newscript trackall:true script:\"RETURN SIGNEDBY(")));
        assertFalse(w.handle("vault action:seed").getBoolean("status"));
        assertFalse(w.handle("txnsign id:x publickey:custom privatekey:0x01 keyuses:1").getBoolean("status"));
        assertNull("cointrack is the node's (ADMIN) — never faked", w.handle("cointrack enable:false coinid:0x1234"));
        assertFalse("an unknown txn verb never reaches the node's shared table", w.handle("txnaddamount id:x amount:1").getBoolean("status"));
    }

    @Test public void txnTableIsLocalAndExportsBytesTheNodeCanImport() throws Exception {
        FakeNode n = new FakeNode();
        VirtualWallet w = wallet(n);
        assertTrue(w.handle("txncreate id:t1").getBoolean("status"));
        assertTrue(w.handle("txnstate id:t1 port:0 value:0x1234").getBoolean("status"));
        assertTrue(w.handle("txnoutput id:t1 amount:1 address:0xFFEEDD9999 storestate:true").getBoolean("status"));
        String data = w.handle("txnexport id:t1").getJSONObject("response").getString("data");
        assertTrue(data.startsWith("0x"));
        JSONObject view = w.handle("txnview data:" + data);
        assertEquals("t1", view.getJSONObject("response").getString("id"));
        assertTrue(w.handle("txnimport id:t2 data:" + data).getBoolean("status"));
        assertEquals(2, w.handle("txnlist").getJSONArray("response").length());
        assertTrue(w.handle("txndelete id:t1").getBoolean("status"));
        assertFalse("nothing about the draft ever reached the node", n.calls.stream().anyMatch(c -> c.startsWith("txn")));
    }

    @Test public void signRefusesAKeyTheAccountDoesNotHold() throws Exception {
        VirtualWallet w = wallet(new FakeNode());
        w.handle("txncreate id:t");
        JSONObject r = w.handle("txnsign id:t publickey:0x" + "11".repeat(32));
        assertFalse(r.getBoolean("status"));
        assertTrue(r.getString("error").contains("Public Key not found"));
    }

    @Test public void balanceWithNoCoinsIsZeroMinima() throws Exception {
        JSONArray b = wallet(new FakeNode()).handle("balance").getJSONArray("response");
        assertEquals(1, b.length());
        assertEquals("0x00", b.getJSONObject(0).getString("tokenid"));
        assertEquals("0", b.getJSONObject(0).getString("sendable"));
    }
}
