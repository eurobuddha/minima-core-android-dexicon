// VENDORED VERBATIM from apks/atomix 0.2.35 app/src/test/java/com/eurobuddha/atomix/IdentityWatchTest.java by scripts/vendor-atomix.sh — do not edit here; re-vendor.
package com.eurobuddha.atomix;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.eurobuddha.atomix.eth.EthWallet;
import com.eurobuddha.atomix.swap.MinimaHtlc;
import com.eurobuddha.atomix.swap.SwapEngine;
import com.eurobuddha.comms.NodeApi;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * STEP (0.1.19): the check that has to keep running.
 *
 * Both identities were established once per PROCESS and never re-validated, so when a user reseeded their
 * node at 00:00 the still-running app traded on the old ETH wallet until ~10:00 — after the same class of
 * mismatch had already cost 7.86 USDT. These tests pin the two things that make the halt trustworthy:
 * it fires on a real mismatch, and it NEVER fires when the node merely failed to answer.
 */
public class IdentityWatchTest {

    private static final String MY_PK   = "0xBBBB";
    private static final String MY_ETH  = "0xdc0d39006df40f0766d468869b0fbec65d6c0b53";
    private static final String NEW_ETH = "0x765a3381e33a09ee3109f089a4944dd947d3f792";

    private NodeApi node;
    private EthWallet wallet;
    private MinimaHtlc minima;
    private SwapEngine.Notifier notifier;
    private JSONArray nodeKeys;
    private String probeAddr;          // what the node's seed derives now
    private String probeErr;           // set instead to simulate a node that won't answer
    /** Queued raw `keys` replies, one per read; once empty, every read is a complete reply of nodeKeys. */
    private final java.util.Deque<JSONObject> keyReplies = new java.util.ArrayDeque<>();
    /** Queued seedrandom-derived addresses, one per probe; once empty, every probe answers probeAddr. */
    private final java.util.Deque<String> probeQueue = new java.util.ArrayDeque<>();

    /** The verdict is process-static; halted() now also refuses initiator starts, so never leak it. */
    @After public void clearVerdict() { IdentityWatch.resetForTest(); }

    @Before public void setUp() {
        IdentityWatch.resetForTest();
        node = mock(NodeApi.class);
        notifier = mock(SwapEngine.Notifier.class);
        nodeKeys = new JSONArray();
        probeAddr = MY_ETH; probeErr = null;

        wallet = mock(EthWallet.class);
        when(wallet.address()).thenReturn(MY_ETH);
        when(wallet.isImported()).thenReturn(false);
        // probeNodeAddress is the seam: answer with probeAddr / probeErr
        org.mockito.Mockito.doAnswer(inv -> {
            EthWallet.Cb cb = inv.getArgument(2);
            String q = probeQueue.poll();
            if (probeErr != null) cb.err(probeErr); else cb.ok(q != null ? q : probeAddr);
            return null;
        }).when(wallet).probeNodeAddress(any(), any(), any(EthWallet.Cb.class));

        // The REAL loadMyKeys parse over a mocked node, so completeness is judged from the raw reply.
        org.mockito.Mockito.doAnswer(inv -> {
            NodeApi.Cb cb = inv.getArgument(1);
            JSONObject q = keyReplies.poll();
            cb.onResult(q != null ? q : keysReply(nodeKeys, nodeKeys.length()));
            return null;
        }).when(node).cmd(eq("keys"), any(NodeApi.Cb.class));
        minima = spy(new MinimaHtlc(node));
        doReturn(MY_PK).when(minima).myPubkey();
    }

    /** A node `keys` reply: {status, response:{keys, total}}; total null = the node sent none. */
    private static JSONObject keysReply(Object keys, Integer total) throws Exception {
        JSONObject resp = new JSONObject().put("keys", keys);
        if (total != null) resp.put("total", total);
        return new JSONObject().put("status", true).put("response", resp);
    }

    /** n well-formed keys, none of them ours (+ ours when withMine). */
    private static JSONArray keys(int n, boolean withMine) throws Exception {
        JSONArray a = new JSONArray();
        for (int i = 1; i <= n; i++) a.put(new JSONObject().put("publickey", "0x" + String.format("%064X", i)));
        if (withMine) a.put(new JSONObject().put("publickey", MY_PK));
        return a;
    }

    private int keysReads() { return org.mockito.Mockito.mockingDetails(node).getInvocations().size(); }

    private void check() { IdentityWatch.check(node, wallet, minima, null, null, notifier); }

    @Test public void matchingKeysDoNotHalt() throws Exception {
        nodeKeys.put(new JSONObject().put("publickey", MY_PK));
        check();
        assertFalse(IdentityWatch.halted());
    }

    @Test public void staleEthWalletHalts() throws Exception {
        nodeKeys.put(new JSONObject().put("publickey", MY_PK));
        probeAddr = NEW_ETH;                       // node reseeded under us
        check();
        assertTrue(IdentityWatch.halted());
        assertTrue(IdentityWatch.ethMismatch());
        assertFalse(IdentityWatch.minimaMismatch());
        assertEquals(MY_ETH, IdentityWatch.staleEth());
        assertEquals(NEW_ETH, IdentityWatch.nodeEth());
    }

    @Test public void orphanedMinimaKeyHalts() throws Exception {
        nodeKeys.put(new JSONObject().put("publickey", "0xAAAA"));   // populated, ours absent
        check();
        assertTrue(IdentityWatch.halted());
        assertTrue(IdentityWatch.minimaMismatch());
        assertEquals(MY_PK, IdentityWatch.orphanedPk());
    }

    /** The whole reason a flaky node must never halt a healthy app. */
    @Test public void unverifiableNodeNeverHalts() {
        nodeKeys = new JSONArray();     // no keys → cannot verify Minima
        probeErr = "node busy";         // seedrandom failed → cannot verify ETH
        check();
        assertFalse(IdentityWatch.halted());
    }

    @Test public void alarmIsRaisedOncePerTransition() {
        probeAddr = NEW_ETH;
        check();
        IdentityWatch.forceNext();
        check();
        org.mockito.Mockito.verify(notifier, org.mockito.Mockito.times(1))
                .notify(anyString(), anyString());
    }

    @Test public void checksAreThrottled() {
        check();                                  // runs
        probeAddr = NEW_ETH;                      // a reseed lands immediately after
        check();                                  // inside the window → must NOT re-probe
        assertFalse(IdentityWatch.halted());
        IdentityWatch.forceNext();
        check();                                  // window forced open → now it sees it
        assertTrue(IdentityWatch.halted());
    }

    /** An imported (bring-your-own) key is the user's deliberate choice, not a node derivation. */
    @Test public void importedKeyIsNeverJudged() {
        when(wallet.isImported()).thenReturn(true);
        probeAddr = NEW_ETH;
        check();
        assertFalse(IdentityWatch.ethMismatch());
    }

    // ---- completeness: a halt never rests on a partial / malformed key list ----

    /** The minimaCore Desktop false halt: the node owns the key (80 keys), but the reply carried only 40. */
    @Test public void incompleteKeyListNeverHalts() throws Exception {
        keyReplies.add(keysReply(keys(40, false), 80));
        check();
        assertFalse(IdentityWatch.halted());
        assertEquals("an incomplete read is not a verdict, so it is not re-read either", 1, keysReads());
    }

    @Test public void malformedKeysRepliesNeverHalt() throws Exception {
        JSONArray noPk = keys(3, false).put(new JSONObject().put("pubkey", MY_PK));     // an element without publickey
        JSONArray strings = new JSONArray().put("0xAAAA").put("0xCCCC");              // not objects
        JSONObject[] bad = {
                keysReply(noPk, null),
                keysReply(strings, null),
                keysReply(new JSONObject().put("publickey", "0xAAAA"), 1),               // keys is not an array
                keysReply(keys(3, false), null).put("response", "keys"),                 // response is a string
                keysReply(keys(3, false), null).put("response", new JSONObject().put("keys", keys(3, false)).put("total", "eighty")),
        };
        for (JSONObject reply : bad) {
            IdentityWatch.resetForTest();
            keyReplies.add(reply);
            check();
            assertFalse(reply.toString(), IdentityWatch.halted());
        }
    }

    @Test public void notOwnedOnceThenOwnedDoesNotHalt() throws Exception {
        keyReplies.add(keysReply(keys(80, false), 80));                 // complete, ours absent
        keyReplies.add(keysReply(keys(79, true), 80));                  // immediate re-read: ours present
        check();
        assertFalse(IdentityWatch.halted());
        assertEquals(2, keysReads());
    }

    @Test public void notOwnedOnceThenUnverifiableDoesNotHalt() throws Exception {
        keyReplies.add(keysReply(keys(80, false), 80));
        keyReplies.add(keysReply(keys(40, false), 80));                 // second read incomplete: cannot verify
        check();
        assertFalse(IdentityWatch.halted());
    }

    @Test public void twoCompleteReadsWithoutTheKeyHalt() throws Exception {
        keyReplies.add(keysReply(keys(80, false), 80));
        keyReplies.add(keysReply(keys(80, false), 80));
        check();
        assertTrue(IdentityWatch.minimaMismatch());
        assertEquals(MY_PK, IdentityWatch.orphanedPk());
        assertEquals(2, keysReads());
    }

    @Test public void oneCompleteOwnedReadClearsAHalt() throws Exception {
        nodeKeys = keys(80, false);
        check();
        assertTrue(IdentityWatch.minimaMismatch());
        nodeKeys = keys(79, true);
        IdentityWatch.forceNext();
        check();
        assertFalse(IdentityWatch.halted());
    }

    /** SwapEngine.setMyPubkeys only SUSPECTS an orphan from a list it cannot prove complete. */
    @Test public void anOrphanSuspicionIsVerifiedBeforeHalting() throws Exception {
        keyReplies.add(keysReply(keys(40, false), 80));
        IdentityWatch.noteMinimaOrphaned(minima, MY_PK, notifier);
        assertFalse(IdentityWatch.halted());
        nodeKeys = keys(80, false);
        IdentityWatch.noteMinimaOrphaned(minima, MY_PK, notifier);
        assertTrue(IdentityWatch.minimaMismatch());
    }

    // ---- ETH: one differing derivation is re-derived, never halted on ----

    @Test public void ethDiffersOnceThenMatchesDoesNotHalt() throws Exception {
        nodeKeys.put(new JSONObject().put("publickey", MY_PK));
        probeQueue.add(NEW_ETH);                                         // first derivation differs, then MY_ETH
        check();
        assertFalse(IdentityWatch.halted());
        verify(wallet, times(2)).probeNodeAddress(any(), any(), any(EthWallet.Cb.class));
    }

    @Test public void ethDiffersTwiceHalts() throws Exception {
        nodeKeys.put(new JSONObject().put("publickey", MY_PK));
        probeQueue.add(NEW_ETH); probeQueue.add(NEW_ETH);
        check();
        assertTrue(IdentityWatch.ethMismatch());
        assertEquals(MY_ETH, IdentityWatch.staleEth());
        assertEquals(NEW_ETH, IdentityWatch.nodeEth());
        verify(wallet, times(2)).probeNodeAddress(any(), any(), any(EthWallet.Cb.class));
    }

    @Test public void ethTwoDifferentAnswersDoNotHalt() throws Exception {
        nodeKeys.put(new JSONObject().put("publickey", MY_PK));
        probeQueue.add(NEW_ETH); probeQueue.add("0x1111111111111111111111111111111111111111");
        check();
        assertFalse(IdentityWatch.halted());
    }

    @Test public void fgLineNamesWhatBroke() {
        IdentityWatch.setMismatchForTest(false, true);
        assertTrue(IdentityWatch.fgLine().contains("ETH wallet"));
        IdentityWatch.setMismatchForTest(true, false);
        assertTrue(IdentityWatch.fgLine().contains("Minima key"));
    }
}
