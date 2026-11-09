package com.eurobuddha.dexicon.account;

import static org.junit.Assert.*;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class VirtualWalletTest {

    static class Store implements VirtualWallet.Store { int n; public int keyCount() { return n; } public void setKeyCount(int x) { n = x; } }

    /** Records every node call; answers scripts/newscript and empty coin lists. */
    static class FakeNode implements VirtualWallet.Node {
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

    @Test public void anEngineGuardIsEvaluatedExactlyOnce() throws Exception {
        // PandaPools' Write.admit records a pending-write marker and refuses a SECOND call: never ask twice.
        VirtualWallet w = wallet(new FakeNode());
        int[] calls = {0};
        java.util.function.BooleanSupplier once = () -> ++calls[0] == 1;
        assertTrue(w.handle("txncreate id:g", once).getBoolean("status"));
        assertEquals(1, calls[0]);
        calls[0] = 0;
        w.handle("txnpost id:g mine:true", once);                // posts nothing (empty txn) but asks the guard once
        assertEquals(1, calls[0]);
        calls[0] = 0;
        try { w.handle("txncreate id:h", () -> { calls[0]++; return false; }); fail("a refusal must reach onError"); }
        catch (VirtualWallet.ToCaller refused) { assertTrue(refused.getMessage().startsWith("Market changed")); }
        assertEquals(1, calls[0]);
    }

    @Test public void alwaysPostsWithMineFalse() throws Exception {
        // stock Minima Core mines mine:true on its UI thread for up to 120 s: never ask it to
        FakeNode n = new FakeNode();
        VirtualWallet w = wallet(n);
        w.handle("txncreate id:m");
        w.handle("txnpost id:m mine:true");
        w.handle("txnpost id:m");
        List<String> posts = new ArrayList<>();
        for (String c : n.calls) if (c.startsWith("postfrom ")) posts.add(c.substring(c.lastIndexOf(' ') + 1));
        assertEquals(java.util.Arrays.asList("mine:false", "mine:false"), posts);
    }

    @Test public void aPostWithNoAnswerIsUncertainNeverFailed() throws Exception {
        FakeNode n = new FakeNode() {
            @Override public JSONObject call(String cmd) throws Exception {
                if (cmd.startsWith("postfrom")) { calls.add(cmd); throw new IllegalStateException("The node did not answer postfrom"); }
                return super.call(cmd);
            }
        };
        VirtualWallet w = wallet(n);
        w.handle("txncreate id:u");
        try { w.handle("txnpost id:u"); fail("an unanswered post must not read as status:false"); }
        catch (VirtualWallet.ToCaller uncertain) {
            assertTrue(uncertain.getMessage().startsWith("UNCERTAIN"));
            assertTrue("the words DoorHost/engines already treat as outcome-unknown", uncertain.getMessage().contains("may still complete"));
        }
    }

    @Test public void genkeyMatchesTheNodesBlockModeProbe() throws Exception {
        // PandaPools' BlockOwnerRecovery probe: the node in block mode answers BLOCK_PROBE for this public phrase
        JSONObject r = wallet(new FakeNode()).handle("keys action:genkey phrase:\"PandaPools public recovery mode probe v1\"");
        assertTrue(r.toString(), r.getBoolean("status"));
        assertEquals("0x3FFDB1A06A2DCA90BEE0D7C0F5BA62B9AF23BF5D73A23D63555492F88D1E3828", r.getJSONObject("response").getString("publickey"));
        assertFalse(r.getJSONObject("response").has("privatekey"));
    }

    @Test public void keysReportTheNextLeafNotARestoredZero() throws Exception {
        // a phone restored from the words starts its counter at 0 but never signs below the time floor: PandaPools
        // recorded the old phone's count (~the floor) as opkuses, and a lower "uses" reads as a counter regression
        JSONObject k = wallet(new FakeNode()).handle("keys");
        int uses = k.getJSONObject("response").getJSONArray("keys").getJSONObject(0).getInt("uses");
        assertTrue(uses >= AccountKeys.timeFloor(System.currentTimeMillis()) - 1);
    }

    @Test public void txpowOnchainOfAPreMiningIdAnswersWithTheMinedTxpow() throws Exception {
        String pre = "0x" + "AA".repeat(32), mined = "0x0000" + "BB".repeat(30), txnid = "0x" + "CC".repeat(32), out = "0x" + "DD".repeat(32);
        String[] posted = {new JSONObject().put(pre, new JSONObject().put("txnid", txnid).put("outs", new JSONArray().put(out)).put("from", 100).put("next", 100)).toString()};
        Store store = new Store() {
            @Override public String posted() { return posted[0]; }
            @Override public void savePosted(String j) { posted[0] = j; }
        };
        FakeNode n = new FakeNode() {
            @Override public JSONObject call(String cmd) throws Exception {
                calls.add(cmd);
                if (cmd.equals("coins coinid:" + out)) return new JSONObject().put("status", true).put("response", new JSONArray().put(new JSONObject().put("coinid", out).put("created", "105")));
                if (cmd.equals("txpow block:105")) return new JSONObject().put("status", true).put("response", new JSONObject().put("txpowid", "0x" + "EE".repeat(32))
                        .put("body", new JSONObject().put("txn", new JSONObject().put("transactionid", "0x" + "11".repeat(32))).put("txnlist", new JSONArray().put(mined))));
                if (cmd.equals("txpow txpowid:" + mined)) return new JSONObject().put("status", true).put("response", new JSONObject().put("body", new JSONObject().put("txn", new JSONObject().put("transactionid", txnid))));
                if (cmd.equals("txpow onchain:" + mined)) return new JSONObject().put("status", true).put("response", new JSONObject().put("found", true).put("block", "105").put("tip", "110").put("confirmations", "5"));
                return super.call(cmd);
            }
        };
        VirtualWallet w = new VirtualWallet(n, AccountKeys.fromPhrase(AccountKeysTest.PHRASE, new AccountKeysTest.Mem(), null), store, Collections.emptySet());
        JSONObject r = w.handle("txpow onchain:" + pre);
        assertTrue(r.getJSONObject("response").getBoolean("found"));
        assertEquals("5", r.getJSONObject("response").getString("confirmations"));
        assertTrue("remembered", new JSONObject(posted[0]).getJSONObject(pre).getString("mined").equalsIgnoreCase(mined));
        assertNull("a txpow that is not one of our posts goes to the node untouched", w.handle("txpow onchain:0x" + "99".repeat(32)));
    }

    @Test public void keysNeverWriteTheCounterFromAnUncheckedClock() throws Exception {
        AccountKeysTest.Mem mem = new AccountKeysTest.Mem();
        FakeNode skewed = new FakeNode() {
            @Override public JSONObject call(String cmd) throws Exception {
                if (cmd.equals("block")) return new JSONObject().put("status", true).put("response", new JSONObject().put("block", "100")
                        .put("timemilli", String.valueOf(System.currentTimeMillis() - 3 * 3600_000L)));   // chain says 3 h ago
                return super.call(cmd);
            }
        };
        VirtualWallet w = new VirtualWallet(skewed, AccountKeys.fromPhrase(AccountKeysTest.PHRASE, mem, null), new Store(), Collections.emptySet());
        int uses = w.handle("keys").getJSONObject("response").getJSONArray("keys").getJSONObject(0).getInt("uses");
        assertEquals("an unchecked clock reports the stored count and writes nothing", 0, uses);
        assertEquals(0, mem.currentUses(0));
    }
}
