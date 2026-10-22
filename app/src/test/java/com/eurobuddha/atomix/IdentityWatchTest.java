// VENDORED VERBATIM from apks/atomix 0.1.83 app/src/test/java/com/eurobuddha/atomix/IdentityWatchTest.java by scripts/vendor-atomix.sh — do not edit here; re-vendor.
package com.eurobuddha.atomix;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.eurobuddha.atomix.eth.EthWallet;
import com.eurobuddha.atomix.swap.MinimaHtlc;
import com.eurobuddha.atomix.swap.SwapEngine;
import com.eurobuddha.comms.NodeApi;

import org.json.JSONArray;
import org.json.JSONObject;
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
            if (probeErr != null) cb.err(probeErr); else cb.ok(probeAddr);
            return null;
        }).when(wallet).probeNodeAddress(any(), any(), any(EthWallet.Cb.class));

        minima = mock(MinimaHtlc.class);
        when(minima.myPubkey()).thenReturn(MY_PK);
        org.mockito.Mockito.doAnswer(inv -> {
            MinimaHtlc.KeysCb cb = inv.getArgument(0);
            java.util.Set<String> out = new java.util.HashSet<>();
            for (int i = 0; i < nodeKeys.length(); i++)
                out.add(MinimaHtlc.normKey(nodeKeys.optJSONObject(i).optString("publickey")));
            cb.ok(out);
            return null;
        }).when(minima).loadMyKeys(any(MinimaHtlc.KeysCb.class));
    }

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

    @Test public void fgLineNamesWhatBroke() {
        IdentityWatch.setMismatchForTest(false, true);
        assertTrue(IdentityWatch.fgLine().contains("ETH wallet"));
        IdentityWatch.setMismatchForTest(true, false);
        assertTrue(IdentityWatch.fgLine().contains("Minima key"));
    }
}
