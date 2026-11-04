package com.eurobuddha.dexicon.doors;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import android.app.Application;
import android.os.Looper;

import com.eurobuddha.atomix.TradingContext;
import com.eurobuddha.atomix.swap.Order;
import com.eurobuddha.atomix.swap.PublishGate;
import com.eurobuddha.atomix.swap.SwapOrderBook;
import com.eurobuddha.comms.CommsIdentity;
import com.eurobuddha.comms.CommsTransport;
import com.eurobuddha.comms.NodeApi;
import com.goterl.lazysodium.LazySodium;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/** 0.1.78 (review 1): an unconfirmed withdrawal is re-sent ONLY in its own market. The tombstone goes through the REAL
 *  SwapOrderBook.publish, whose market check refuses any order not of the active market ("Market changed") — a mock
 *  LazySodium then makes a passing order stop at "sign failed", before anything reaches the node. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class DoorHostWithdrawalTest {
    private DoorHost host;
    private DoorHost.Tombstones real;
    private java.util.function.IntSupplier realTip;
    private final List<Order> sent = new ArrayList<>();
    private final List<String> outcome = new ArrayList<>();
    private final LazySodium ls = mock(LazySodium.class);
    private final CommsIdentity id = mock(CommsIdentity.class);

    @Before public void up() {
        Application app = RuntimeEnvironment.getApplication();
        com.eurobuddha.dexicon.TradingGate.record(app, new org.json.JSONArray());
        host = DoorHost.get(app);
        real = host.tombstones;
        realTip = host.tip;
        idle(5);
        host.switchMarket(TradingContext.MXUSDT, null);
        idle(5);
        quiet();
        PublishGate.release(PublishGate.ORDER);
        when(id.publicId()).thenReturn("me");
        host.tombstones = new DoorHost.Tombstones() {
            @Override public boolean ready(CommsIdentity i) { return true; }
            @Override public void send(CommsIdentity i, Order o, CommsTransport.SendCb cb) {
                sent.add(o);
                SwapOrderBook.publish(mock(NodeApi.class), ls, id, o, new CommsTransport.SendCb() {
                    @Override public void onSent(String txpowid) { outcome.add("sent"); cb.onSent(txpowid); }
                    @Override public void onFailed(String message) { outcome.add(message); cb.onFailed(message); }
                });
            }
        };
    }
    @After public void down() { host.tip = realTip; host.tombstones = real; host.switchMarket(TradingContext.MXUSDT, null); idle(5); quiet(); }

    private void quiet() {
        host.prefs().edit().remove("order_config").remove("market_mxusdt").remove("market_minima")
                .remove("withdraw_pending_mxusdt").remove("withdraw_pending_minima").remove("withdraw_pending_at_mxusdt").remove("withdraw_pending_at_minima").remove("withdraw_pending_tip_mxusdt").remove("withdraw_pending_tip_minima")
                .putBoolean("auto_publish", false).putBoolean("otc_enable", false).putBoolean("otc_auto", false).commit();
    }
    private static void idle(int s) { Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(s)); }
    private void pending(TradingContext m, long ageMs) {
        host.prefs().edit().putBoolean("withdraw_pending_" + m.key, true).putLong("withdraw_pending_at_" + m.key, System.currentTimeMillis() - ageMs).commit();
    }

    @Test public void anotherMarketsWithdrawalWaitsForItsOwnMarket() {
        pending(TradingContext.MINIMA, 60_000);   // a switch away from MINIMA whose tombstone never confirmed
        host.flushWithdrawal();
        assertTrue("nothing is posted into the MxUSD book under the MINIMA marker", sent.isEmpty());
        assertTrue("the MINIMA marker is kept", host.withdrawalPending(TradingContext.MINIMA));

        assertNull(host.switchMarket(TradingContext.MINIMA, null));
        idle(5);
        assertEquals(TradingContext.MINIMA, host.market());
        assertNotNull("the launch rule still sees the un-aged marker", host.applyLaunchRule());
        host.flushWithdrawal();
        assertEquals("re-tombstoned in its own market", 1, sent.size());
        assertEquals(TradingContext.MINIMA, sent.get(0).market());
        assertFalse(sent.get(0).hasLiquidity());
        assertEquals("passes the real market check (stops only at the mock signer)", "sign failed", outcome.get(0));
        assertTrue("not confirmed: still pending", host.withdrawalPending(TradingContext.MINIMA));
    }

    @Test public void anOrderOfAnotherMarketFailsTheRealCheck() {
        Order mxusd = host.tombstone();   // built while MxUSD is active: bound to the MxUSD market
        assertNull(host.switchMarket(TradingContext.MINIMA, null));
        idle(5);
        host.tombstones.send(id, mxusd, new CommsTransport.SendCb() {
            @Override public void onSent(String txpowid) {}
            @Override public void onFailed(String message) {}
        });
        assertEquals(SwapOrderBook.MARKET_ERROR, outcome.get(0));
    }

    @Test public void theMarkerAgesOutWithTheOrder() {
        pending(TradingContext.MINIMA, DoorHost.WITHDRAW_MARKER_MS + 60_000);
        assertFalse(host.withdrawalPending(TradingContext.MINIMA));
        assertFalse(host.prefs().contains("withdraw_pending_minima"));
        pending(TradingContext.MINIMA, DoorHost.WITHDRAW_MARKER_MS - 60_000);
        assertTrue(host.withdrawalPending(TradingContext.MINIMA));
    }

    /** 0.1.81: with a known tip the marker ages out by chain time — 72 scanned blocks + 12 — whatever the wall clock says. */
    @Test public void theMarkerAgesOutByChainTimeWhenTheTipIsKnown() {
        host.tip = () -> 1000;
        host.prefs().edit().putBoolean("withdraw_pending_minima", true).putLong("withdraw_pending_at_minima", System.currentTimeMillis() - DoorHost.WITHDRAW_MARKER_MS - 60_000)
                .putInt("withdraw_pending_tip_minima", 1000).commit();
        assertTrue("70 min of wall time, but no blocks yet: still on the book", host.withdrawalPending(TradingContext.MINIMA));
        host.tip = () -> 1000 + SwapOrderBook.SCAN_DEPTH + 11;
        assertTrue(host.withdrawalPending(TradingContext.MINIMA));
        host.tip = () -> 1000 + SwapOrderBook.SCAN_DEPTH + 12;
        assertFalse("aged off the scanned window", host.withdrawalPending(TradingContext.MINIMA));
        assertFalse(host.prefs().contains("withdraw_pending_tip_minima"));
    }

    @Test public void aPre078MarkerStartsItsClockOnFirstSight() {
        host.prefs().edit().putBoolean("withdraw_pending_minima", true).commit();
        assertTrue(host.withdrawalPending(TradingContext.MINIMA));
        assertTrue(host.prefs().getLong("withdraw_pending_at_minima", 0) > 0);
    }
}
