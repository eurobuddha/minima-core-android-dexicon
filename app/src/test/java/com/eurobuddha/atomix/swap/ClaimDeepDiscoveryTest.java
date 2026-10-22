// VENDORED VERBATIM from apks/atomix 0.1.83 app/src/test/java/com/eurobuddha/atomix/swap/ClaimDeepDiscoveryTest.java by scripts/vendor-atomix.sh — do not edit here; re-vendor.
package com.eurobuddha.atomix.swap;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
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
 * STEP: the claim-side twin of RefundRetryTest's discovery fault — four real swaps, 509 MINIMA each,
 * frozen in CLAIMING with the secret KNOWN and the counter-coins fully locked on-chain.
 *
 * The counterparty claims my ETH leg on their own schedule, and only that reveals the secret. By the time
 * the app learned it, the counter-coins were older than HTLC_SCAN_DEPTH (256), so the per-hash claim scan
 * — a `coins depth:` walk-back from the tip — returned nothing, forever. checkCanSwapCoin never ran, no
 * error was ever logged (an empty scan is not an error), and the swaps could not move: not COMPLETE (never
 * claimed), not refundable (my ETH leg was legitimately withdrawn by the counterparty).
 *
 * 0.1.15 moved the refund sweep onto scanHtlcByHashDeep for exactly this reason; the claim path is now on
 * the same deep scan.
 */
public class ClaimDeepDiscoveryTest {

    private static final String HASH  = "0x" + "55".repeat(32);
    private static final String MY_PK = "0xBBBB";

    private SwapEngine engine;
    private SwapDb db;
    private MinimaHtlc minima;

    @Before public void setUp() {
        SwapEngine.clearRetryMarkersForTest();
        EthWallet wallet = mock(EthWallet.class);
        when(wallet.address()).thenReturn("0x2222222222222222222222222222222222222222");
        minima = mock(MinimaHtlc.class);
        db = mock(SwapDb.class);
        engine = new SwapEngine(mock(NodeApi.class), minima, db,
                wallet, mock(Handler.class), mock(SwapEngine.Notifier.class));
        engine.setNetwork(new EthRpc(EthNet.MAINNET.defaultRpc), EthNet.MAINNET);
        engine.setMyMinimaPk(MY_PK);
        engine.setMyPubkeys(Collections.singleton(MY_PK.toLowerCase()));
    }

    /** A responder maker-buy row: my leg is the ETH one, the counter MINIMA leg is owed to me. */
    private SwapDb.Swap claimableSwap(String status) {
        SwapDb.Swap s = new SwapDb.Swap();
        s.hash = HASH; s.status = status; s.myLegIsMinima = false;
        return s;
    }

    @Test public void claimDiscoveryUsesTheDeepScanNotTheShallowWindow() {
        when(db.allSwaps()).thenReturn(Collections.singletonList(claimableSwap(SwapDb.ST_CLAIMING)));
        when(db.getSecret(HASH)).thenReturn("0x" + "AA".repeat(32));   // secret harvested — I can claim
        when(db.haveCollect(HASH)).thenReturn(false);

        engine.runMinimaChecks(2245573);

        // The counter-coin may be ARBITRARILY old when the secret finally arrives, so discovery must go
        // through the MegaMMR-backed deep scan — the shallow per-hash scan is what froze the real swaps.
        verify(minima, times(1)).scanHtlcByHashDeep(eq(HASH), anyInt(),
                eq(SwapEngine.REFUND_SCAN_DEPTH), any(), any());
    }

    @Test public void noSecretMeansNoClaimScan() {
        when(db.allSwaps()).thenReturn(Collections.singletonList(claimableSwap(SwapDb.ST_LOCKED)));
        when(db.getSecret(HASH)).thenReturn(null);

        engine.runMinimaChecks(2245573);

        verify(minima, never()).scanHtlcByHashDeep(eq(HASH), anyInt(), anyInt(), any(), any());
    }

    /** The deep scan is heavy and the node has ONE command thread: unthrottled per-poll scans for 4 pending
     *  claims built a backlog that outlived every callback timeout (the real 4-swap freeze got WORSE before it
     *  got better). One scan per cycle, one scan per hash per retry window, round-robin. */
    @Test public void deepScansAreRoundRobinAndWindowGated() {
        String hash2 = "0x" + "66".repeat(32);
        SwapDb.Swap a = claimableSwap(SwapDb.ST_CLAIMING);
        SwapDb.Swap b = claimableSwap(SwapDb.ST_CLAIMING); b.hash = hash2;
        when(db.allSwaps()).thenReturn(java.util.Arrays.asList(a, b));
        when(db.getSecret(any())).thenReturn("0x" + "AA".repeat(32));
        when(db.haveCollect(any())).thenReturn(false);

        engine.runMinimaChecks(2245573);   // cycle 1 → scans HASH only (one per cycle)
        verify(minima, times(1)).scanHtlcByHashDeep(eq(HASH), anyInt(), anyInt(), any(), any());
        verify(minima, never()).scanHtlcByHashDeep(eq(hash2), anyInt(), anyInt(), any(), any());

        engine.runMinimaChecks(2245574);   // cycle 2 → HASH still in-window, hash2's turn
        verify(minima, times(1)).scanHtlcByHashDeep(eq(HASH), anyInt(), anyInt(), any(), any());
        verify(minima, times(1)).scanHtlcByHashDeep(eq(hash2), anyInt(), anyInt(), any(), any());

        engine.runMinimaChecks(2245575);   // cycle 3 → both in-window: no scan at all
        verify(minima, times(1)).scanHtlcByHashDeep(eq(HASH), anyInt(), anyInt(), any(), any());
        verify(minima, times(1)).scanHtlcByHashDeep(eq(hash2), anyInt(), anyInt(), any(), any());

        SwapEngine.ageRetryMarkerForTest("claimScan:" + HASH, 10_000);
        engine.runMinimaChecks(2245576);   // window elapsed → HASH re-fires on its own
        verify(minima, times(2)).scanHtlcByHashDeep(eq(HASH), anyInt(), anyInt(), any(), any());
    }

    @Test public void terminalAndCollectedSwapsAreNotScanned() {
        SwapDb.Swap done = claimableSwap(SwapDb.ST_COMPLETE);
        SwapDb.Swap collected = claimableSwap(SwapDb.ST_CLAIMING);
        when(db.allSwaps()).thenReturn(java.util.Arrays.asList(done, collected));
        when(db.getSecret(HASH)).thenReturn("0x" + "AA".repeat(32));
        when(db.haveCollect(HASH)).thenReturn(true);   // already collected — nothing left to discover

        engine.runMinimaChecks(2245573);

        verify(minima, never()).scanHtlcByHashDeep(eq(HASH), anyInt(), anyInt(), any(), any());
    }

    /** 0.1.41 zombie-scan reaper: an ERC20→mxUSDT INITIATOR whose own ETH leg is already past its refund window
     *  can never still claim the shorter mxUSDT counter-leg. If its ETH refund never confirmed on-chain the row
     *  stays non-terminal forever and claimScan polls the dead hash every cycle — the real S23 symptom. Retire it. */
    @Test public void expiredInitiatorErc20ToMinimaIsReaped() {
        SwapDb.Swap s = claimableSwap(SwapDb.ST_CLAIMING);
        s.role = "INITIATOR"; s.direction = "ERC20_TO_MINIMA"; s.myTimelock = 1;   // unix 1 → long past
        when(db.allSwaps()).thenReturn(Collections.singletonList(s));
        when(db.getSecret(HASH)).thenReturn("0x" + "AA".repeat(32));
        when(db.haveCollect(HASH)).thenReturn(false);

        engine.runMinimaChecks(2245573);

        verify(minima, never()).scanHtlcByHashDeep(eq(HASH), anyInt(), anyInt(), any(), any());
    }

    /** The reaper must be SCOPED: a MINIMA_TO_ERC20 RESPONDER is also myLegIsMinima=false, but its ETH leg is the
     *  SHORT one while the mxUSDT first-leg it still claims after harvesting stays live ~2h — a blanket cutoff on
     *  myTimelock would strand that claim. Past its ETH myTimelock, it must STILL be scanned. */
    @Test public void expiredResponderMinimaToErc20StillScans() {
        SwapDb.Swap s = claimableSwap(SwapDb.ST_CLAIMING);
        s.role = "RESPONDER"; s.direction = "MINIMA_TO_ERC20"; s.myTimelock = 1;   // ETH leg expired, mxUSDT claim not
        when(db.allSwaps()).thenReturn(Collections.singletonList(s));
        when(db.getSecret(HASH)).thenReturn("0x" + "AA".repeat(32));
        when(db.haveCollect(HASH)).thenReturn(false);

        engine.runMinimaChecks(2245573);

        verify(minima, times(1)).scanHtlcByHashDeep(eq(HASH), anyInt(),
                eq(SwapEngine.REFUND_SCAN_DEPTH), any(), any());
    }
}
