package com.eurobuddha.dexicon.doors;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import android.app.Application;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.os.Looper;

import com.eurobuddha.atomix.TradingContext;
import com.eurobuddha.atomix.swap.OtcDb;
import com.eurobuddha.atomix.swap.OtcMessage;
import com.eurobuddha.atomix.swap.OtcOffer;
import com.eurobuddha.atomix.swap.Order;
import com.eurobuddha.atomix.swap.PublishGate;
import com.eurobuddha.comms.CommsIdentity;
import com.eurobuddha.comms.CommsTransport;
import com.eurobuddha.comms.CryptoProvider;
import com.eurobuddha.comms.Opened;
import com.eurobuddha.dexicon.TradingGate;

import org.json.JSONObject;
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

/** 0.1.75: the gate withdraws a live ① market at once (D), OTC follows the active market (F) and starts no execution while
 *  gated (G), and the ⇄ switch voids queued publications before its tombstone (H). */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class DoorHostGateTest {
    private Application app;
    private DoorHost host;
    private DoorHost.Tombstones real;
    private final List<Order> sent = new ArrayList<>();
    private final List<CommsTransport.SendCb> cbs = new ArrayList<>();

    @Before public void up() {
        app = RuntimeEnvironment.getApplication();
        // no node in a JVM test: the IPC transport's bindService must not deliver a null binder (as the other smoke tests do)
        Shadows.shadowOf(app).setComponentNameAndServiceForBindService(new android.content.ComponentName(app, com.eurobuddha.dexicon.NodeTransportService.class),
                new android.os.Messenger(new android.os.Handler(Looper.getMainLooper(), m -> true)).getBinder());
        TradingGate.record(app, new org.json.JSONArray());
        host = DoorHost.get(app);
        real = host.tombstones;
        idle(5);
        host.switchMarket(TradingContext.MXUSDT, null);
        idle(5);
        quiet();
        host.tombstones = new DoorHost.Tombstones() {
            @Override public boolean ready(CommsIdentity id) { return true; }
            @Override public void send(CommsIdentity id, Order o, CommsTransport.SendCb cb) { sent.add(o); cbs.add(cb); }
        };
    }
    @After public void down() {
        Shadows.shadowOf(app.getPackageManager()).removePackage(TradingGate.ATOMIX);
        host.tombstones = real; host.switchMarket(TradingContext.MXUSDT, null); idle(5); quiet();
    }

    private void quiet() {
        host.prefs().edit().remove("order_config").remove("market_mxusdt").remove("market_minima").remove("withdraw_pending_mxusdt").remove("withdraw_pending_minima")
                .putBoolean("auto_publish", false).putBoolean("otc_enable", false).putBoolean("otc_auto", false).commit();
    }
    private static void idle(int s) { Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(s)); }
    private void installAtomiX() { PackageInfo pi = new PackageInfo(); pi.packageName = TradingGate.ATOMIX; Shadows.shadowOf(app.getPackageManager()).installPackage(pi); }

    private static Order liquid() {
        Order o = Order.fromConfigJson("");
        Order.Pair p = o.pairs.values().iterator().next();
        p.enable = true; p.asks.add(new Order.Level(1.02, 25)); p.bids.add(new Order.Level(0.98, 25));
        Order.sanitize(p);
        return o;
    }

    @Test public void aFoundAppWithdrawsTheLiveMarketAtOnce() {
        host.prefs().edit().putString("order_config", Order.toConfigJson(liquid())).putBoolean("auto_publish", true).commit();
        host.withdrawOnConflict();
        assertTrue("no conflict: nothing withdrawn", sent.isEmpty());
        installAtomiX();
        long gen0 = PublishGate.gen();
        host.gateChanged();
        assertEquals("tombstoned now, not by the hourly belt", 1, sent.size());
        assertFalse(sent.get(0).hasLiquidity());
        assertFalse("kept off until the user places it again", host.prefs().getBoolean("auto_publish", true));
        assertTrue(PublishGate.gen() > gen0);
        assertFalse("the guard is empty", host.armMaker().hasLiquidity());
        assertTrue(host.withdrawalPending(TradingContext.MXUSDT));
        cbs.get(0).onSent("0x01");
        assertFalse(host.withdrawalPending(TradingContext.MXUSDT));
        host.gateChanged();
        assertEquals("once", 1, sent.size());
        Order saved = Order.fromConfigJson(host.prefs().getString("order_config", ""));
        assertFalse("persisted DISABLED, exactly as withdrawOrder", saved.hasLiquidity());
        for (Order.Pair p : saved.pairs.values()) { assertFalse(p.enable); assertFalse("the levels are kept to place again", p.asks.isEmpty()); }
        Shadows.shadowOf(app.getPackageManager()).removePackage(TradingGate.ATOMIX);
        assertFalse(TradingGate.blocked(app));
        assertFalse("the reopened gate arms nothing stale", host.armMaker().hasLiquidity());
    }

    @Test public void anUnpublishedLadderNeverArms() {
        host.prefs().edit().putString("order_config", Order.toConfigJson(liquid())).putBoolean("auto_publish", false).commit();
        assertFalse("auto_publish off: every take declines", host.armMaker().hasLiquidity());
        host.prefs().edit().putBoolean("auto_publish", true).commit();
        assertTrue(host.armMaker().hasLiquidity());
    }

    /** The gate also lives INSIDE the engine (SwapEngine.setHostGate): no new lock anywhere while blocked or mid-switch. */
    @Test public void theEngineItselfHaltsWhileGated() {
        org.junit.Assume.assumeFalse(com.eurobuddha.atomix.IdentityWatch.halted());
        assertFalse("open gate: the engine may lock", com.eurobuddha.atomix.swap.SwapEngine.halted());
        installAtomiX();
        assertTrue(TradingGate.blocked(app));
        assertTrue("found app: the engine refuses every new lock", com.eurobuddha.atomix.swap.SwapEngine.halted());
        Shadows.shadowOf(app.getPackageManager()).removePackage(TradingGate.ATOMIX);
        assertFalse("gone again: the engine may lock", com.eurobuddha.atomix.swap.SwapEngine.halted());
    }

    @Test public void anUnreadMiniHubWithdrawsNothing() {
        app.getSharedPreferences("dexicon_gate", Context.MODE_PRIVATE).edit().clear().commit();
        assertTrue(TradingGate.blocked(app) && !TradingGate.conflict(app));
        host.prefs().edit().putString("order_config", Order.toConfigJson(liquid())).putBoolean("auto_publish", true).commit();
        host.gateChanged();
        assertTrue(sent.isEmpty());
        assertTrue(host.prefs().getBoolean("auto_publish", false));
        TradingGate.record(app, new org.json.JSONArray());
    }

    @Test public void otcFollowsTheMarketAfterASwitch() {
        assertNull(host.switchMarket(TradingContext.MINIMA, null));
        idle(5);
        assertEquals(TradingContext.MINIMA, host.market());
        CommsIdentity me = mock(CommsIdentity.class); when(me.publicId()).thenReturn("me");
        host.otc().setSelf(mock(CryptoProvider.class), me, "0x1234", "0x" + "34".repeat(20));
        assertTrue("the controller is the MINIMA market's", host.otc().ready());
        host.otc().setMyOffer(true, 100, 100);
        OtcMessage m = new OtcMessage();
        m.type = OtcMessage.PROPOSE; m.ref = "gate-test-propose"; m.randomid = "gate-test-r1"; m.from = "peer"; m.to = "me";
        m.side = OtcOffer.LP_SELLS_MINIMA; m.amount = "2"; m.price = "3"; m.minimaPk = "0x5678"; m.ethAddr = "0x" + "56".repeat(20);
        assertTrue("a PROPOSE routes in MINIMA mode", host.routeOtc("coin", new Opened(true, "peer", m.toWire()), new JSONObject()));
        OtcDb.Deal d = host.otcDb().getDeal("gate-test-propose");
        assertEquals(TradingContext.MINIMA.key, d.currency);
        OtcDb.Deal done = d.copy(); done.status = OtcDb.ST_REJECTED;
        assertTrue(host.otcDb().replaceDeal(d, done));
    }

    @Test public void gatedOtcStartsNoExecution() {
        OtcMessage m = new OtcMessage();
        m.ref = "gate-test-deal"; m.randomid = "gate-test-r2"; m.from = "peer"; m.to = "me";
        for (String t : new String[]{OtcMessage.ACCEPT, OtcMessage.EXECUTE}) { m.type = t; assertTrue(t, DoorHost.startsExecution(new Opened(true, "peer", m.toWire()))); }
        for (String t : new String[]{OtcMessage.PROPOSE, OtcMessage.COUNTER, OtcMessage.REJECT}) { m.type = t; assertFalse(t, DoorHost.startsExecution(new Opened(true, "peer", m.toWire()))); }
        installAtomiX();
        m.type = OtcMessage.ACCEPT;
        assertFalse("gated: an ACCEPT is not routed", host.routeOtc("coin", new Opened(true, "peer", m.toWire()), new JSONObject()));
    }

    /** 7a (0.1.79): OTC and market are withdrawn under ONE generation (a second bump voided the tombstone in flight), and
     *  the tombstone holds the ORDER slot, so flushWithdrawal cannot send a duplicate in the same pass. */
    @Test public void theGateWithdrawsOtcAndMarketUnderOneGeneration() {
        PublishGate.release(PublishGate.ORDER);
        host.prefs().edit().putString("order_config", Order.toConfigJson(liquid())).putBoolean("auto_publish", true)
                .putBoolean("otc_enable", true).putBoolean("otc_auto", true).commit();
        installAtomiX();
        long gen0 = PublishGate.gen();
        host.gateChanged();
        assertEquals("one bump for both", gen0 + 1, PublishGate.gen());
        assertFalse(host.prefs().getBoolean("otc_enable", true) || host.prefs().getBoolean("otc_auto", true));
        assertEquals(1, sent.size());
        host.flushWithdrawal();
        assertEquals("the in-flight tombstone holds the ORDER slot: no duplicate", 1, sent.size());
        cbs.get(0).onFailed("lost");
        host.flushWithdrawal();
        assertEquals("unconfirmed: re-sent on the next pass", 2, sent.size());
        cbs.get(1).onSent("0x02");
        assertFalse(host.withdrawalPending(TradingContext.MXUSDT));
    }

    /** 7a: a (voided) publish still holding the ORDER slot: the marker is set, and the next pass sends it. */
    @Test public void aBusyOrderSlotDefersTheTombstoneToTheNextPass() {
        host.prefs().edit().putString("order_config", Order.toConfigJson(liquid())).putBoolean("auto_publish", true).commit();
        assertTrue(PublishGate.tryAcquire(PublishGate.ORDER));
        installAtomiX();
        host.gateChanged();
        assertTrue(sent.isEmpty());
        assertTrue(host.withdrawalPending(TradingContext.MXUSDT));
        PublishGate.release(PublishGate.ORDER);
        host.flushWithdrawal();
        assertEquals(1, sent.size());
        cbs.get(0).onSent("0x03");
    }

    /** 7d: no ⇄ switch while an OTC deal of this market is agreed or its first lock waits — and the user is told why. */
    @Test public void theSwitchWaitsForAnOtcDealMidExecution() {
        OtcDb.Deal d = new OtcDb.Deal();
        d.ref = "gate-test-agreed"; d.role = OtcDb.ROLE_INSTIGATOR; d.peerCommsId = "peer"; d.side = OtcOffer.LP_SELLS_MINIMA;
        d.amount = "2"; d.price = "1"; d.status = OtcDb.ST_AGREED; d.whoseTurn = OtcDb.TURN_ME; d.currency = TradingContext.MXUSDT.key;
        assertTrue(host.otcDb().createDeal(d));
        try {
            assertEquals(DoorHost.OTC_MID_EXECUTION, host.switchMarket(TradingContext.MINIMA, null));
            assertEquals(TradingContext.MXUSDT, host.market());
        } finally {
            OtcDb.Deal done = host.otcDb().getDeal(d.ref).copy(); done.status = OtcDb.ST_REJECTED;
            assertTrue(host.otcDb().replaceDeal(host.otcDb().getDeal(d.ref), done));
        }
        assertNull(host.switchMarket(TradingContext.MINIMA, null));
        idle(5);
    }

    /** 0.1.81: an LP's deal is EXECUTING before the LP has locked — there is no swap row yet (status null). The switch
     *  must wait, or otcLpDeal (active market only) never locks the LP's leg. */
    @Test public void theSwitchWaitsForAnLpWhoseLegIsNotLockedYet() {
        OtcDb.Deal d = new OtcDb.Deal();
        d.ref = "gate-test-lp-executing"; d.role = OtcDb.ROLE_LP; d.peerCommsId = "peer"; d.side = OtcOffer.LP_SELLS_MINIMA;
        d.amount = "2"; d.price = "1"; d.status = OtcDb.ST_EXECUTING; d.whoseTurn = OtcDb.TURN_ME; d.currency = TradingContext.MXUSDT.key;
        d.hash = "0x" + "5a".repeat(32);   // the instigator's hashlock; no swap row of mine under it yet
        assertTrue(host.otcDb().createDeal(d));
        try {
            assertTrue(host.otcMidExecution(TradingContext.MXUSDT));
            assertEquals(DoorHost.OTC_MID_EXECUTION, host.switchMarket(TradingContext.MINIMA, null));
            assertEquals(TradingContext.MXUSDT, host.market());
        } finally {
            OtcDb.Deal done = host.otcDb().getDeal(d.ref).copy(); done.status = OtcDb.ST_REJECTED;
            assertTrue(host.otcDb().replaceDeal(host.otcDb().getDeal(d.ref), done));
        }
        assertFalse(host.otcMidExecution(TradingContext.MXUSDT));
    }

    @Test public void theSwitchVoidsQueuedPublicationsBeforeItsTombstone() {
        host.prefs().edit().putString("order_config", Order.toConfigJson(liquid())).putBoolean("auto_publish", true).commit();
        long gen0 = PublishGate.gen();
        assertNull(host.switchMarket(TradingContext.MINIMA, null));
        assertTrue(host.switching());
        assertTrue("bumped at the start, while the tombstone is still in flight", PublishGate.gen() > gen0);
        assertEquals(1, sent.size());
        final String[] said = new String[1];
        host.withdrawOtcOffer(m -> said[0] = m);
        assertEquals("7b: nothing is withdrawn mid-switch (its bump would void the tombstone)", DoorHost.SWITCHING, said[0]);
        idle(5);
    }
}
