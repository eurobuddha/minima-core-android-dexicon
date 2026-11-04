// VENDORED VERBATIM from apks/atomix 0.1.89 app/src/test/java/com/eurobuddha/atomix/HandoverTest.java by scripts/vendor-atomix.sh — do not edit here; re-vendor.
package com.eurobuddha.atomix;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import android.content.SharedPreferences;

import android.os.Handler;

import com.eurobuddha.atomix.eth.EthNet;
import com.eurobuddha.atomix.eth.EthRpc;
import com.eurobuddha.atomix.eth.EthWallet;
import com.eurobuddha.atomix.swap.MinimaHtlc;
import com.eurobuddha.atomix.swap.Order;
import com.eurobuddha.atomix.swap.OtcDb;
import com.eurobuddha.atomix.swap.SwapEngine;
import com.eurobuddha.comms.NodeApi;
import com.eurobuddha.atomix.swap.PriceOracle;
import com.eurobuddha.atomix.swap.SwapDb;

import org.junit.After;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Dexicon handover (0.1.86): Dexicon may only uninstall AtomiX once nothing in either currency is in flight and the
 *  market is withdrawn — and once handed over, AtomiX starts nothing new (Handover.active gates the engine). */
public class HandoverTest {

    private SharedPreferences prefs(final Map<String, Object> m) {
        SharedPreferences p = mock(SharedPreferences.class);
        SharedPreferences.Editor ed = mock(SharedPreferences.Editor.class);
        when(p.getString(anyString(), nullable(String.class))).thenAnswer(i -> {
            Object o = m.get(i.<String>getArgument(0)); return o instanceof String ? o : i.getArgument(1); });
        when(p.getBoolean(anyString(), anyBoolean())).thenAnswer(i -> {
            Object o = m.get(i.<String>getArgument(0)); return o instanceof Boolean ? o : i.getArgument(1); });
        when(p.edit()).thenReturn(ed);
        when(ed.putBoolean(anyString(), anyBoolean())).thenAnswer(i -> { m.put(i.getArgument(0), i.getArgument(1)); return ed; });
        when(ed.putLong(anyString(), anyLong())).thenAnswer(i -> { m.put(i.getArgument(0), i.getArgument(1)); return ed; });
        when(ed.remove(anyString())).thenAnswer(i -> { m.remove(i.<String>getArgument(0)); return ed; });
        when(ed.commit()).thenReturn(true);
        return p;
    }

    private static SwapDb.Swap swap(String sell, String status, String hash) {
        SwapDb.Swap s = new SwapDb.Swap();
        s.hash = hash; s.sellToken = sell; s.buyToken = "USDT"; s.status = status;
        return s;
    }

    private static OtcDb.Deal deal(String status) {
        OtcDb.Deal d = new OtcDb.Deal();
        d.ref = "ab".repeat(16); d.currency = "minima"; d.status = status;
        return d;
    }

    @After public void reset() { Handover.takeBack(prefs(new HashMap<>())); }

    private static SwapDb db(SwapDb.Swap... swaps) {
        SwapDb db = mock(SwapDb.class);
        when(db.allSwaps()).thenReturn(Arrays.asList(swaps));
        return db;
    }

    @Test public void anyNonTerminalSwapInEitherCurrencyBlocks() {
        String h1 = "0x" + "11".repeat(32), h2 = "0x" + "22".repeat(32);
        SwapDb db = db(
                swap("mxUSDT", SwapDb.ST_LOCKED, h1),
                swap("MINIMA", SwapDb.ST_STARTED, h2),
                swap("MINIMA", SwapDb.ST_COMPLETE, "x"),
                swap("mxUSDT", SwapDb.ST_REFUNDED, "y"),
                swap("mxUSDT", SwapDb.ST_ERROR, "z"));
        when(db.haveCollectExpired("z")).thenReturn(true);
        List<String> r = Handover.inFlight(db, Collections.emptyList(), false);
        assertEquals(2, r.size());
        assertTrue(r.get(0).contains("USDT⇄MxUSD") && r.get(0).endsWith(h1));   // full hash, MxUSD naming
        assertTrue(r.get(1).contains("USDT⇄MINIMA") && r.get(1).contains(h2));
    }

    /** CRITICAL (0.1.88): ERROR is the trade outcome, not my funds. Initiator MxUSD→USDT, claim missed, maker refunds
     *  the ETH leg → ERROR while my MxUSD lock still waits ~2h for its own refund from the recorded coin. */
    @Test public void errorRowBlocksUntilMyRefundIsConfirmed() {
        String h = "0x" + "33".repeat(32);
        SwapDb.Swap s = swap("mxUSDT", SwapDb.ST_ERROR, h);
        s.myLegIsMinima = true;
        SwapDb db = db(s);
        List<String> r = Handover.inFlight(db, Collections.emptyList(), false);
        assertEquals(1, r.size());
        assertTrue(r.get(0).contains(h) && r.get(0).contains("not been confirmed refunded"));
        when(db.haveCollectExpired(h)).thenReturn(true);   // EV_EXPIRED: the refund confirmed on chain
        assertTrue(Handover.inFlight(db, Collections.emptyList(), false).isEmpty());
        s.myLegIsMinima = false;                            // an ETH first leg needs the same chain evidence
        assertTrue(Handover.inFlight(db, Collections.emptyList(), false).isEmpty());
        when(db.haveCollectExpired(h)).thenReturn(false);
        assertEquals(1, Handover.inFlight(db, Collections.emptyList(), false).size());
    }

    /** Finding 4 (not auto-cleared — no chain read can prove a lost lock absent): the screen says exactly why. */
    @Test public void unacknowledgedStartedRowSaysWhyItBlocks() {
        String h = "0x" + "44".repeat(32);
        SwapDb db = db(swap("MINIMA", SwapDb.ST_STARTED, h));
        String r = Handover.inFlight(db, Collections.emptyList(), false).get(0);
        assertTrue(r, r.contains(h) && r.contains("never confirmed as sent"));
        when(db.hasEvent(h, SwapDb.EV_STARTED)).thenReturn(true);
        assertTrue(Handover.inFlight(db, Collections.emptyList(), false).get(0).endsWith(h));
    }

    @Test public void anInitiatorStartInProgressBlocks() {
        List<String> r = Handover.inFlight(db(), Collections.emptyList(), true);
        assertEquals(1, r.size());
        assertTrue(r.get(0).contains("starting"));
    }

    @Test public void openOtcDealBlocksFinishedDoesNot() {
        assertEquals(1, Handover.inFlight(db(), Arrays.asList(
                deal(OtcDb.ST_AGREED), deal(OtcDb.ST_COMPLETE), deal(OtcDb.ST_EXPIRED), deal(OtcDb.ST_REJECTED)), false).size());
        assertTrue(Handover.inFlight(db(), new ArrayList<>(), false).isEmpty());
    }

    /** MAJOR (0.1.88): the entry check passed, then the handover quiesced AtomiX during the node round-trips — the
     *  secret must not be written (and no lock posted), and the engine must report "starting" until it answers. */
    @Test public void handoverDuringInitiatorStartWritesNoSecret() {
        TradingContext saved = TradingContext.active();
        TradingContext.setActive(TradingContext.MXUSDT, null);
        MinimaHtlc minima = mock(MinimaHtlc.class);
        when(minima.ready()).thenReturn(true);
        when(minima.activeToken()).thenReturn(TradingContext.MXUSDT.tokenId);
        EthWallet wallet = mock(EthWallet.class);
        when(wallet.ready()).thenReturn(true);
        SwapDb swaps = mock(SwapDb.class);
        SwapEngine e = new SwapEngine(mock(NodeApi.class), minima, swaps, wallet, mock(Handler.class), mock(SwapEngine.Notifier.class));
        e.setMyMinimaPk("0x1234");
        e.setNetwork(mock(EthRpc.class), EthNet.MAINNET);
        final boolean[] startingSeen = {false};
        doAnswer(i -> { ((MinimaHtlc.SecretCb) i.getArgument(0)).ok("public fixture", "hash fixture"); return null; })
                .when(minima).generateSecret(any());
        doAnswer(i -> {
            startingSeen[0] = SwapEngine.initiatorStarting();
            Handover.withdraw(prefs(new HashMap<>()));          // Dexicon's handover lands mid-start
            ((MinimaHtlc.BlockCb) i.getArgument(0)).ok(100);
            return null;
        }).when(minima).currentBlock(any());
        Order maker = new Order();
        maker.minimaPublicKey = "0x5678"; maker.ethAddress = "0x" + "12".repeat(20);
        final String[] err = {null};
        try {
            e.startMinimaToErc20(maker, "2", "USDT", "6", true, new SwapEngine.StartCb() {
                public void ok(String h) { fail("a lock was posted after the handover"); }
                public void err(String m) { err[0] = m; }
            });
            assertTrue("busy while the start is between check and write", startingSeen[0]);
            assertTrue(err[0], err[0] != null && err[0].contains("Handed over"));
            verify(swaps, never()).prepareInitiator(any(), any(), any(), any());
            verify(minima, never()).lock(any(), any(), any(), any(), any(), any(), anyInt(), any(), any());
            assertFalse("busy cleared once the start answered", SwapEngine.initiatorStarting());
        } finally { e.shutdown(); TradingContext.setActive(saved, null); }
    }

    @Test public void withdrawQuiescesMarketAndFlagsPendingPosts() {
        Map<String, Object> m = new HashMap<>();
        m.put("auto_publish", true); m.put("otc_enable", true); m.put("otc_auto", true);
        SharedPreferences p = prefs(m);
        assertEquals(2, Handover.marketLive(p).size());
        assertFalse(Handover.active());

        Handover.withdraw(p);
        assertTrue("new swaps stop at once", Handover.active());
        assertEquals(false, m.get("auto_publish"));
        assertEquals(false, m.get("otc_enable"));
        assertEquals(false, m.get("otc_auto"));
        assertEquals(true, m.get(PriceOracle.P_WITHDRAWN));
        // Not done until SwapService has the tombstone + OTC withdrawal on the wire.
        assertEquals(2, Handover.marketLive(p).size());
        m.put(Handover.P_TOMB_PENDING, false); m.put(Handover.P_OTC_PENDING, false);
        assertTrue(Handover.marketLive(p).isEmpty());
        assertFalse(Handover.done(p));
    }

    @Test public void noLiveMarketNeedsNoPost() {
        Map<String, Object> m = new HashMap<>();
        SharedPreferences p = prefs(m);
        Handover.withdraw(p);
        assertTrue(Handover.marketLive(p).isEmpty());
    }

    @Test public void doneSurvivesReloadAndTakeBackClears() {
        Map<String, Object> m = new HashMap<>();
        SharedPreferences p = prefs(m);
        Handover.setDone(p, 123L);
        Handover.takeBack(prefs(new HashMap<>()));   // a different store — clear the static only
        Handover.load(p);
        assertTrue(Handover.active());
        assertTrue(Handover.done(p));
        Handover.takeBack(p);
        assertFalse(Handover.active());
        Handover.load(p);
        assertFalse(Handover.active());
    }
}
