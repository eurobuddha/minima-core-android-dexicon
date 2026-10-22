// VENDORED VERBATIM from apks/atomix 0.1.82 app/src/test/java/com/eurobuddha/atomix/swap/IdentityGuardTest.java by scripts/vendor-atomix.sh — do not edit here; re-vendor.
package com.eurobuddha.atomix.swap;

import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.os.Handler;

import com.eurobuddha.comms.NodeApi;
import com.eurobuddha.atomix.eth.EthNet;
import com.eurobuddha.atomix.eth.EthRpc;
import com.eurobuddha.atomix.eth.EthWallet;

import org.junit.Before;
import org.junit.Test;

import java.util.Collections;

/**
 * STEP: a node reset with a different seed silently orphans the persisted swap identity — the app kept
 * publishing a receiver key the node could not sign for and routing lock-change to an address the wallet
 * no longer owned. Real cost in the field: 2,623.7 MINIMA of change plus 4 unclaimable 509-MINIMA
 * counter-legs, all invisible until a terminal session compared `keys` against the persisted identity.
 * The guard alarms at the ONE point where the persisted key and the node's real key set meet.
 */
public class IdentityGuardTest {

    private static final String MY_PK = "0xBBBB";

    private SwapEngine engine;
    private SwapEngine.Notifier notifier;

    @Before public void setUp() throws Exception {
        SwapEngine.clearRetryMarkersForTest();
        SwapEngine.resetIdentityAlarmForTest();
        com.eurobuddha.atomix.IdentityWatch.resetForTest();
        EthWallet wallet = mock(EthWallet.class);
        when(wallet.address()).thenReturn("0x2222222222222222222222222222222222222222");
        notifier = mock(SwapEngine.Notifier.class);
        engine = new SwapEngine(mock(NodeApi.class), mock(MinimaHtlc.class), mock(SwapDb.class),
                wallet, mock(Handler.class), notifier);
        engine.setNetwork(new EthRpc(EthNet.MAINNET.defaultRpc), EthNet.MAINNET);
        engine.setMyMinimaPk(MY_PK);
    }

    @Test public void orphanedIdentityRaisesTheAlarmOnce() {
        engine.setMyPubkeys(Collections.singleton("AAAA"));      // node keys do NOT include 0xBBBB
        verify(notifier, times(1)).notify(contains("Wallet mismatch"), anyString());
        assertTrue("the verdict must reach IdentityWatch so the UI blocker and every gate agree",
                com.eurobuddha.atomix.IdentityWatch.halted());

        engine.setMyPubkeys(Collections.singleton("AAAA"));      // second engine / re-init: no re-alarm
        verify(notifier, times(1)).notify(anyString(), anyString());
    }

    @Test public void ownedIdentityStaysSilent() {
        engine.setMyPubkeys(Collections.singleton("BBBB"));      // loadMyKeys stores normKey form: no 0x, uppercase
        verify(notifier, never()).notify(anyString(), anyString());
    }

    @Test public void emptyKeySetNeverAlarms() {
        engine.setMyPubkeys(Collections.emptySet());             // keys not loaded yet ≠ keys missing
        verify(notifier, never()).notify(anyString(), anyString());
    }
}
