// VENDORED VERBATIM from apks/pandapools 0.9.97 app/src/test/java/com/eurobuddha/pandapools/TransactionSafetyTest.java by scripts/vendor-pandapools.sh — do not edit here; re-vendor.
package com.eurobuddha.pandapools;

import org.json.JSONObject;
import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public class TransactionSafetyTest {
    @Test public void malformedWriteReplyAndAmbiguousOutcomeAreNotSuccessOrFailure() {
        assertFalse(NodeApi.isCompleteReply(null));
        assertFalse(NodeApi.isCompleteReply(new TestJson().put("response", "unknown")));
        assertTrue(NodeApi.isCompleteReply(new TestJson().put("status", false)));
        ActivityLog.Entry e = new ActivityLog.Entry("SWAP", "x", null, 0, 1, true, NodeApi.ERR_WRITE_UNCERTAIN);
        assertFalse(e.failed);
        assertTrue(e.statusText(999999).contains("Outcome unknown"));
    }

    @Test public void transactionIdentityComesFromTheImmutableBodyNotMiningHeader() {
        JSONObject body = new TestJson().put("txn", new TestJson().put("transactionid", "0xaabb"));
        JSONObject tx = new TestJson().put("body", body).put("txpowid", "0x1111");
        assertEquals("0xaabb", ActivityLog.transactionId(tx));
        tx = new TestJson().put("body", body).put("txpowid", "0x2222");
        assertEquals("0xaabb", ActivityLog.transactionId(tx));
        assertEquals("", ActivityLog.transactionId(new TestJson().put("txpowid", "0xaabb")));
    }

    @Test public void recoveryRejectsInjectedFieldsAndMisrepresentedContracts() throws Exception {
        String script = PoolCovenant.script("0xaa", "0xbb", "0xcc", "123");
        JSONObject recipe = new TestJson().put("addr", "0xdd").put("opk", "0xaa").put("oadr", "0xbb")
                .put("tok", "0xcc").put("kmin", "123").put("dec", 8).put("script", script);
        assertTrue(Recovery.validRecipe(recipe));
        recipe.put("oadr", "0xbb; send amount:10");
        assertFalse(Recovery.validRecipe(recipe));
        recipe.put("oadr", "0xbb").put("script", "RETURN TRUE " + script);
        assertFalse(Recovery.validRecipe(recipe));
        recipe.put("script", script).put("dec", "8.5");
        assertFalse(Recovery.validRecipe(recipe));
        recipe.put("dec", 8).put("kmin", "124");
        assertFalse(Recovery.validRecipe(recipe));
    }
    @Test public void postRejectionPreservesActualSizeAndLimit() throws Exception {
        String message = TxPost.postError(new TestJson().put("error", "TxPoW size too large.. 70000/65536"));
        assertTrue(message.contains("70000/65536"));
        assertTrue(message.contains("Nothing was broadcast"));
        assertTrue(message.contains("txnpost"));
    }
    @Test public void allTransactionChecksMustPassBeforePosting() throws Exception {
        JSONObject flags = new TestJson().put("scripts", true).put("basic", true).put("mmrproofs", true);
        JSONObject response = new TestJson().put("valid", flags).put("validamounts", true)
                .put("allsignaturesvalid", true).put("validtransaction", true);
        JSONObject reply = new TestJson().put("status", true).put("response", response);
        assertNull(TxPost.checkFailure(reply));
        for (String key : Arrays.asList("scripts", "basic", "mmrproofs")) {
            flags.remove(key); assertNotNull(TxPost.checkFailure(reply)); flags.put(key, true);
        }
        response.put("allsignaturesvalid", false); assertNotNull(TxPost.checkFailure(reply));
    }
    @Test public void failedKeyReadDoesNotAuthorizeOwnerSpending() throws Exception {
        Set<String> wanted = new HashSet<>(Arrays.asList("0xaa", "0xbb"));
        JSONObject reply = new TestJson().put("status", false).put("response", new org.json.JSONArray());
        assertEquals(wanted, new HashSet<>(OwnerKeyRecovery.unavailableKeys(reply, wanted)));
        reply.put("status", true).put("response", new TestJson().put("keys", new org.json.JSONArray().put(new TestJson().put("publickey", "0xaa"))));
        assertEquals(wanted, new HashSet<>(OwnerKeyRecovery.unavailableKeys(reply, wanted)));
        reply.getJSONObject("response").getJSONArray("keys").getJSONObject(0).put("uses", 10);
        assertEquals(Collections.singletonList("0xbb"), OwnerKeyRecovery.unavailableKeys(reply, wanted));
    }
    @Test public void duplicateCompletionCannotReleaseAnotherChainOrNotifyTwice() throws Exception {
        List<String> events = new ArrayList<>();
        TxPost.Done first = TxPost.gated(new TxPost.Done() {
            public void ok(String id) { events.add("first done"); }
            public void fail(String m) { failTest(); }
            void failTest() { throw new AssertionError("Duplicate failure callback"); }
        });
        TxPost.Done second = TxPost.gated(new TxPost.Done() {
            public void ok(String id) { events.add("second done"); }
            public void fail(String m) { throw new AssertionError(m); }
        });
        TxPost.submit(() -> events.add("first started"));
        TxPost.submit(() -> events.add("second started"));
        assertEquals(Collections.singletonList("first started"), events);
        first.ok("tx"); first.fail("late");
        assertEquals(Arrays.asList("first started", "first done", "second started"), events);
        second.ok("tx2");
    }
    @Test public void allSignatureCommandsAreMarkedAsWrites() throws Exception {
        for (String c : Arrays.asList("sign publickey:0xaa", "txnsign id:x", "txnpost id:x", "send amount:1", "consolidate", "tokencreate"))
            assertTrue(c, NodeApi.writesFunds(c));
        for (String c : Arrays.asList("coins", "balance", "txncheck id:x", "keys", "txnbasics id:x"))
            assertFalse(c, NodeApi.writesFunds(c));
    }
    @Test public void localTimeAndHeightNeverProveInclusion() throws Exception {
        ActivityLog.Entry e = new ActivityLog.Entry("SWAP", "x", "0xaa", 1, 1, false, null);
        assertFalse(e.confirmed(999999));
        assertEquals("Checking on-chain…", e.statusText(999999));
        e.verifiedDepth = 2; assertFalse(e.confirmed(999999));
        e.verifiedDepth = 3; assertTrue(e.confirmed(999999));
        assertEquals("3 confirmations · on-chain", e.statusText(999999));
        e.verifiedDepth = 47;
        assertEquals("47 confirmations · on-chain", e.statusText(999999));
    }
    @Test public void confirmationReplyMustExplicitlyProveInclusion() throws Exception {
        JSONObject r = new TestJson().put("found", true).put("confirmations", "3");
        JSONObject reply = new TestJson().put("status", true).put("response", r);
        assertEquals(3, ActivityLog.confirmationDepth(reply));
        r.put("found", false); assertEquals(-1, ActivityLog.confirmationDepth(reply));
        r.put("found", true).put("confirmations", "1.5"); assertEquals(-1, ActivityLog.confirmationDepth(reply));
        r.put("confirmations", 9); reply.put("status", false); assertEquals(-1, ActivityLog.confirmationDepth(reply));
    }
}
