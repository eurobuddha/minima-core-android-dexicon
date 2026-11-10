package com.eurobuddha.account;

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

    /** A node with coins already at the account's addresses, of which it holds none (tracking only follows NEW coins). */
    static class NodeWithOldCoins extends FakeNode {
        int coinsAt = 3; boolean alreadyTracked;
        @Override public JSONObject call(String cmd) throws Exception {
            if (cmd.startsWith("scripts address:") && alreadyTracked) {
                calls.add(cmd);
                return new JSONObject().put("status", true).put("response", new JSONObject().put("address", cmd.substring(16).trim()).put("track", true));
            }
            if (cmd.startsWith("balance address:")) {
                calls.add(cmd);
                return new JSONObject().put("status", true).put("response", new JSONArray().put(new JSONObject().put("coins", String.valueOf(coinsAt))));
            }
            if (cmd.startsWith("coins address:")) {
                calls.add(cmd);
                String a = cmd.substring("coins address:".length()).trim();
                return new JSONObject().put("status", true).put("response", new JSONArray()
                        .put(new JSONObject().put("coinid", "0xAA").put("address", a).put("spent", false))
                        .put(new JSONObject().put("coinid", "0xBB").put("address", a).put("spent", true))
                        .put(new JSONObject().put("coinid", "0xCC").put("address", "0x1234").put("spent", false)));
            }
            if (cmd.startsWith("coinexport")) { calls.add(cmd); return new JSONObject().put("status", true).put("response", new JSONObject().put("data", "0xDA7A")); }
            if (cmd.startsWith("coinimport")) { calls.add(cmd); return new JSONObject().put("status", true); }
            return super.call(cmd);
        }
    }

    /** 10 Nov, on the Fold: the account moved to a node that had not tracked it read 0. Its coins already there are imported
     *  with their proofs — also when the node already tracks the address (it was registered before the coins arrived). */
    @Test public void coinsAlreadyAtTheAccountAreImportedEvenOnATrackedAddress() throws Exception {
        NodeWithOldCoins n = new NodeWithOldCoins(); n.alreadyTracked = true;
        VirtualWallet w = wallet(n);
        w.ensureTracked();
        assertTrue(n.calls.contains("coinexport coinid:0xAA"));
        assertTrue(n.calls.contains("coinimport track:true data:0xDA7A"));
        assertFalse("a spent coin is not imported", n.calls.contains("coinexport coinid:0xBB"));
        assertFalse("a coin at another address is not imported", n.calls.contains("coinexport coinid:0xCC"));
        int reads = (int) n.calls.stream().filter(c -> c.startsWith("coins address:")).count();
        w.ensureTracked();
        assertEquals("a clean pass is not repeated in this process", reads, n.calls.stream().filter(c -> c.startsWith("coins address:")).count());
    }

    @Test public void aDustFloodedAddressIsNeverReadWhole() throws Exception {
        NodeWithOldCoins n = new NodeWithOldCoins(); n.coinsAt = VirtualWallet.MAX_COIN_ROWS + 1;
        wallet(n).ensureTracked();
        assertFalse("the row read is refused before it can reach the IPC limit", n.calls.stream().anyMatch(c -> c.startsWith("coins address:")));
    }

    /** 10 Nov, on the Fold: a lock to us, imported AFTER it was spent, stayed in Minima Core's balance as unspent for good
     *  (3.08677 MxUSD, coin 0x698A7B79A4C4D86F02D41BCDF010B4BDDA7A2EC9B882375FFDFCDD45C478269D). A lock is imported only
     *  while the node says it is unspent. */
    @Test public void aSpentLockIsNeverImported() throws Exception {
        final boolean[] spentOnNode = {false};
        FakeNode n = new FakeNode() {
            @Override public JSONObject call(String cmd) throws Exception {
                if (cmd.startsWith("coins coinid:")) { calls.add(cmd); return new JSONObject().put("status", true).put("response", new JSONArray().put(new JSONObject().put("spent", spentOnNode[0]))); }
                if (cmd.startsWith("coinexport")) { calls.add(cmd); return new JSONObject().put("status", true).put("response", new JSONObject().put("data", "0xDA7A")); }
                if (cmd.startsWith("coinimport")) { calls.add(cmd); return new JSONObject().put("status", true); }
                if (cmd.equals("block")) return new JSONObject().put("status", true).put("response", new JSONObject().put("block", "5000"));
                return super.call(cmd);
            }
        };
        VirtualWallet w = wallet(n);
        String myKey = w.handle("getaddress").getJSONObject("response").getString("publickey");
        JSONObject lock = new JSONObject().put("coinid", "0x698A").put("created", "3000").put("state", new JSONArray().put(new JSONObject().put("port", 4).put("data", myKey)));
        w.noticeLocksToUs(new JSONObject().put("response", new JSONArray().put(new JSONObject(lock.toString()).put("spent", true))));
        assertFalse("a row already marked spent", n.calls.stream().anyMatch(c -> c.startsWith("coinimport")));
        spentOnNode[0] = true;
        w.noticeLocksToUs(new JSONObject().put("response", new JSONArray().put(new JSONObject(lock.toString()).put("spent", false))));
        assertFalse("the node says it is spent now", n.calls.stream().anyMatch(c -> c.startsWith("coinimport")));
        spentOnNode[0] = false;
        w.noticeLocksToUs(new JSONObject().put("response", new JSONArray().put(new JSONObject(lock.toString()).put("spent", false))));
        assertTrue("an unspent lock to us is tracked at first sight (a late claim needs its proof past the tree)",
                n.calls.stream().anyMatch(c -> c.startsWith("coinimport")));
        assertTrue("the unspent check includes the mempool", n.calls.stream().anyMatch(c -> c.equals("coins coinid:0x698A checkmempool:true")));
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

    @Test public void txpowOnchainOfAPreMiningIdIsProvenByItsExactOutput() throws Exception {
        String pre = "0x" + "AA".repeat(32), txnid = "0x" + "CC".repeat(32), out = "0x" + "DD".repeat(32), mine = "0x" + "12".repeat(32), blockid = "0x0000" + "BB".repeat(30);
        JSONObject o = new JSONObject().put("coinid", out).put("address", mine).put("amount", "3.08667").put("tokenid", "0x00");
        String old = "0x" + "77".repeat(32);   // a 0.3.11-format record (outs as plain ids): never proof
        String[] posted = {new JSONObject().put(pre, new JSONObject().put("txnid", txnid).put("outs", new JSONArray().put(o)))
                .put(old, new JSONObject().put("txnid", txnid).put("outs", new JSONArray().put(out))).toString()};
        Store store = new Store() {
            @Override public String posted() { return posted[0]; }
            @Override public boolean savePosted(String j) { posted[0] = j; return true; }
        };
        String[] coinAddress = {"0x" + "99".repeat(32)};   // first: same coin id, DIFFERENT address (another spend of the input)
        long[] tip = {106};
        int[] blockFetches = {0};
        FakeNode n = new FakeNode() {
            @Override public JSONObject call(String cmd) throws Exception {
                calls.add(cmd);
                if (cmd.equals("coins coinid:" + out)) return new JSONObject().put("status", true).put("response", new JSONArray().put(new JSONObject()
                        .put("coinid", out).put("address", coinAddress[0]).put("amount", "3.08667").put("tokenid", "0x00").put("created", "105")));
                if (cmd.startsWith("txpow inblock:")) return new JSONObject().put("status", true).put("response", new JSONObject().put("txns", new JSONArray()));   // shallow: empty, as on a real node
                if (cmd.equals("txpow block:105")) { blockFetches[0]++; return new JSONObject().put("status", true).put("response", new JSONObject().put("txpowid", blockid).put("header", new JSONObject().put("block", "105"))); }
                if (cmd.equals("block")) return new JSONObject().put("status", true).put("response", new JSONObject().put("block", String.valueOf(tip[0])).put("timemilli", String.valueOf(System.currentTimeMillis())));
                return super.call(cmd);
            }
        };
        VirtualWallet w = new VirtualWallet(n, AccountKeys.fromPhrase(AccountKeysTest.PHRASE, new AccountKeysTest.Mem(), null), store, Collections.emptySet());
        assertTrue("routed: our id", VirtualWallet.answersTxpow("txpow onchain:" + pre));
        assertFalse("not routed: a foreign id", VirtualWallet.answersTxpow("txpow onchain:0x" + "99".repeat(32)));
        assertFalse(VirtualWallet.answersTxpow("txpow txpowid:" + pre));
        assertFalse("a coin with our id but another address proves nothing", w.handle("txpow onchain:" + pre).getJSONObject("response").getBoolean("found"));
        coinAddress[0] = mine;
        assertFalse("1 deep: not yet", w.handle("txpow onchain:" + pre).getJSONObject("response").getBoolean("found"));
        tip[0] = 108;
        JSONObject r = w.handle("txpow onchain:" + pre).getJSONObject("response");
        assertTrue(r.getBoolean("found"));
        assertEquals("105", r.getString("block"));
        assertEquals(blockid, r.getString("blockid"));
        assertEquals("3", r.getString("confirmations"));
        coinAddress[0] = "0x" + "99".repeat(32);   // spent onward / gone: a final answer stays
        tip[0] = 120;
        assertEquals("15", w.handle("txpow onchain:" + pre).getJSONObject("response").getString("confirmations"));
        assertEquals("the block txpow is fetched once", 1, blockFetches[0]);
        for (String c : n.calls) assertFalse("never a txpow body by id", c.startsWith("txpow txpowid:"));
        assertFalse("old-format record: no proof", w.handle("txpow onchain:" + old).getJSONObject("response").getBoolean("found"));
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

    /** Stands in for the process dying mid-call: an Error no catch (Exception) intercepts. */
    static final class Died extends Error { }

    /** A store + node pair for resolver tests: outputs/blocks are scripted per test. */
    static final class ChainSim {
        final String[] posted = {"{}"};
        final java.util.Map<String, JSONObject> coins = new java.util.HashMap<>();
        final java.util.Map<Long, String> blockIds = new java.util.HashMap<>();
        long tip = 100; int blockFetches; boolean failFetch, kill;
        final Store store = new Store() {
            @Override public String posted() { return posted[0]; }
            @Override public boolean savePosted(String j) { posted[0] = j; return true; }
        };
        final FakeNode node = new FakeNode() {
            @Override public JSONObject call(String cmd) throws Exception {
                calls.add(cmd);
                if (cmd.startsWith("coins coinid:")) { JSONObject c = coins.get(cmd.substring(13)); return new JSONObject().put("status", true).put("response", c == null ? new JSONArray() : new JSONArray().put(c)); }
                if (cmd.startsWith("txpow inblock:")) return new JSONObject().put("status", true).put("response", new JSONObject().put("txns", new JSONArray()));
                if (cmd.startsWith("txpow block:")) {
                    blockFetches++;
                    if (kill) throw new Died();
                    if (failFetch) throw new IllegalStateException("The node did not answer txpow");
                    long b = Long.parseLong(cmd.substring(12));
                    return new JSONObject().put("status", true).put("response", new JSONObject().put("txpowid", blockIds.get(b)).put("header", new JSONObject().put("block", String.valueOf(b))));
                }
                if (cmd.equals("block")) return new JSONObject().put("status", true).put("response", new JSONObject().put("block", String.valueOf(tip)).put("timemilli", String.valueOf(System.currentTimeMillis())));
                return super.call(cmd);
            }
        };
        VirtualWallet wallet() { return new VirtualWallet(node, AccountKeys.fromPhrase(AccountKeysTest.PHRASE, new AccountKeysTest.Mem(), null), store, Collections.emptySet()); }
        static JSONObject out(String id, String addr) throws Exception { return new JSONObject().put("coinid", id).put("address", addr).put("amount", "1").put("tokenid", "0x00"); }
        void post(String pre, JSONArray outs, JSONArray ins) throws Exception {
            JSONObject all = new JSONObject(posted[0]);
            all.put(pre, new JSONObject().put("txnid", "0x" + "CC".repeat(32)).put("outs", outs).put("ins", ins));
            posted[0] = all.toString();
        }
    }

    @Test public void aFailedBlockFetchRetriesLaterAndAKillingOneIsGivenUpNotLooped() throws Exception {
        ChainSim c = new ChainSim();
        String pre = "0x" + "A1".repeat(32), out = "0x" + "D1".repeat(32), me = "0x" + "12".repeat(32), bid = "0x0000" + "B1".repeat(30);
        c.post(pre, new JSONArray().put(ChainSim.out(out, me)), new JSONArray());
        c.coins.put(out, ChainSim.out(out, me).put("created", "100"));
        c.blockIds.put(100L, bid);
        VirtualWallet w = c.wallet();
        c.tip = 103; c.failFetch = true;
        assertFalse(w.handle("txpow onchain:" + pre).getJSONObject("response").getBoolean("found"));
        assertFalse("backoff: not refetched on the very next call", w.handle("txpow onchain:" + pre).getJSONObject("response").getBoolean("found"));
        assertEquals(1, c.blockFetches);
        c.tip = 110; c.failFetch = false;
        assertTrue("a later retry succeeds: never stuck forever", w.handle("txpow onchain:" + pre).getJSONObject("response").getBoolean("found"));

        // a fetch that never returns (the process dies mid-call): its committed mark is found on the next entry
        ChainSim d = new ChainSim();
        String pre2 = "0x" + "A2".repeat(32), out2 = "0x" + "D2".repeat(32);
        d.post(pre2, new JSONArray().put(ChainSim.out(out2, me)), new JSONArray());
        d.coins.put(out2, ChainSim.out(out2, me).put("created", "100"));
        d.blockIds.put(100L, bid);
        d.kill = true;
        for (int k = 0; k < VirtualWallet.MAX_DEATHS + 2; k++) {
            d.tip = 103 + 100L * k;                        // past any backoff
            try { d.wallet().handle("txpow onchain:" + pre2); } catch (Died died) { }
        }
        assertEquals("exactly MAX_DEATHS fetches were ever attempted", VirtualWallet.MAX_DEATHS, d.blockFetches);
        d.kill = false; d.tip += 100;
        assertFalse(d.wallet().handle("txpow onchain:" + pre2).getJSONObject("response").getBoolean("found"));
        assertEquals("given up after repeated deaths: no further fetch", VirtualWallet.MAX_DEATHS, d.blockFetches);
    }

    @Test public void anOutputSpentOnwardByOurOwnProvenPostIsProven() throws Exception {
        ChainSim c = new ChainSim();
        String claim = "0x" + "A3".repeat(32), spend = "0x" + "A4".repeat(32), cOut = "0x" + "D3".repeat(32), sOut = "0x" + "D4".repeat(32), me = "0x" + "12".repeat(32);
        c.post(claim, new JSONArray().put(ChainSim.out(cOut, me)), new JSONArray());
        c.post(spend, new JSONArray().put(ChainSim.out(sOut, me)), new JSONArray().put(ChainSim.out(cOut, me)));   // our consolidate spent the claim's output
        c.coins.put(sOut, ChainSim.out(sOut, me).put("created", "102"));                       // the claim's own output is gone (never seen)
        c.blockIds.put(102L, "0x0000" + "B2".repeat(30));
        c.tip = 110;
        VirtualWallet w = c.wallet();
        assertTrue("the spender is proven", w.handle("txpow onchain:" + spend).getJSONObject("response").getBoolean("found"));
        JSONObject r = w.handle("txpow onchain:" + claim).getJSONObject("response");
        assertTrue("so the claim it spent landed", r.getBoolean("found"));
        assertEquals("reported at the spender's block (never more confirmations than proven)", "102", r.getString("block"));
    }

    @Test public void aSpenderOfASameIdCoinAtAnotherAddressProvesNothing() throws Exception {
        // A never mined; B reused A's first input, so B's output 0 has A's output id X at another address. Our proven
        // post D spends B's X. A must stay unproven.
        ChainSim c = new ChainSim();
        String a = "0x" + "A5".repeat(32), d = "0x" + "A6".repeat(32), x = "0x" + "D5".repeat(32), dOut = "0x" + "D6".repeat(32);
        String me = "0x" + "12".repeat(32), other = "0x" + "34".repeat(32);
        c.post(a, new JSONArray().put(ChainSim.out(x, me)), new JSONArray());
        c.post(d, new JSONArray().put(ChainSim.out(dOut, me)), new JSONArray().put(ChainSim.out(x, other)));
        c.coins.put(dOut, ChainSim.out(dOut, me).put("created", "102"));
        c.blockIds.put(102L, "0x0000" + "B6".repeat(30));
        c.tip = 110;
        VirtualWallet w = c.wallet();
        assertTrue(w.handle("txpow onchain:" + d).getJSONObject("response").getBoolean("found"));
        assertFalse(w.handle("txpow onchain:" + a).getJSONObject("response").getBoolean("found"));
    }

    // ---------------------------------------------------------------- MegaMMR archive pass (coins older than the node)

    static org.minima.objects.CoinProof archived(String coinid, String address, long proofBlock) {
        org.minima.objects.Coin c = new org.minima.objects.Coin(new org.minima.objects.base.MiniData(coinid),
                new org.minima.objects.base.MiniData(address), new org.minima.objects.base.MiniNumber("5"), new org.minima.objects.base.MiniData("0x00"));
        return new org.minima.objects.CoinProof(c, new org.minima.objects.mmr.MMRProof(new org.minima.objects.base.MiniNumber(proofBlock)));
    }

    /** Verifies proofs unless the coin is in {@code spentSince} (coincheck refuses a coin spent after the proof block);
     *  tracks 0x11 already. */
    static class ArchiveNode extends FakeNode {
        final java.util.Set<String> spentSince = new java.util.HashSet<>();
        boolean synced = true;
        @Override public JSONObject call(String cmd) throws Exception {
            if (cmd.equals("scripts")) { calls.add(cmd); return new JSONObject().put("status", true).put("response", new JSONArray()
                    .put(new JSONObject().put("address", "0xB0").put("simple", true))
                    .put(new JSONObject().put("address", "0xA0").put("simple", true))); }
            if (cmd.startsWith("coins relevant:true coinid:0x11")) { calls.add(cmd);
                return new JSONObject().put("status", true).put("response", new JSONArray().put(new JSONObject().put("coinid", "0x11"))); }
            if (cmd.startsWith("coincheck data:")) { calls.add(cmd);
                String id = org.minima.objects.CoinProof.convertMiniDataVersion(new org.minima.objects.base.MiniData(cmd.substring(15)))
                        .getCoin().getCoinID().to0xString();
                return new JSONObject().put("status", true).put("response", new JSONObject().put("valid", synced && !spentSince.contains(id))); }
            if (cmd.startsWith("coinimport")) { calls.add(cmd); return new JSONObject().put("status", true); }
            return super.call(cmd);
        }
        long imports() { return calls.stream().filter(c -> c.startsWith("coinimport track:true data:")).count(); }
    }

    static class ArchiveStore extends Store {
        final java.util.Map<String, Integer> done = new java.util.HashMap<>();
        public int archivedKeys(String id) { return done.getOrDefault(id, 0); }
        public void setArchivedKeys(String id, int n) { done.put(id, n); }
    }

    /** The archive's proofs sit 256 blocks below its tip (coinexport), so they are never "fresh" — the node's coincheck
     *  is the gate. Tracked, foreign-address and spent-since coins are not imported. */
    @Test public void archivePassImportsOnlyVerifiedUnheldCoinsAtAccountAddresses() throws Exception {
        ArchiveNode n = new ArchiveNode(); ArchiveStore st = new ArchiveStore(); st.n = 1;
        AccountKeys k = AccountKeys.fromPhrase(AccountKeysTest.PHRASE, new AccountKeysTest.Mem(), null);
        VirtualWallet w = new VirtualWallet(n, k, st, Collections.emptySet());
        String a = k.address(0);
        String id = w.archiveWanted();
        assertEquals("node id = its lowest own simple address", VirtualWallet.norm("0xA0"), id);
        n.spentSince.add("0x22");
        assertTrue(w.importArchived(java.util.Arrays.asList(
                archived("0x11", a, 100 - 256),        // already tracked: skipped
                archived("0x33", "0x" + "12".repeat(32), 100 - 256),   // not an account address: skipped
                archived("0x44", a, 100 - 256))));     // imported
        assertEquals(1, n.imports());
        assertFalse("spent since the proof: coincheck refuses it, not imported",
                w.importArchived(Collections.singletonList(archived("0x22", a, 100 - 256))));
        assertEquals(1, n.imports());
        w.markArchived(id, 1);
        assertNull("covered", w.archiveWanted());
        st.n = 2;
        assertEquals("a new address after the pass re-opens it", id, w.archiveWanted());
    }

    @Test public void unverifiableProofLeavesThePassIncomplete() throws Exception {
        ArchiveNode n = new ArchiveNode(); n.synced = false;
        AccountKeys k = AccountKeys.fromPhrase(AccountKeysTest.PHRASE, new AccountKeysTest.Mem(), null);
        VirtualWallet w = new VirtualWallet(n, k, new ArchiveStore(), Collections.emptySet());
        assertFalse(w.importArchived(Collections.singletonList(archived("0x44", k.address(0), 100 - 256))));
        assertEquals("the node could not verify it (behind the archive)", 0, n.imports());
    }
}
