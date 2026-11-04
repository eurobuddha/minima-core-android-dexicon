package com.eurobuddha.dexicon.doors;

import static org.junit.Assert.*;

import android.app.Application;
import android.os.Looper;

import com.eurobuddha.atomix.TradingContext;
import com.eurobuddha.atomix.swap.OtcDb;
import com.eurobuddha.atomix.swap.Order;
import com.eurobuddha.atomix.swap.PublishGate;
import com.eurobuddha.atomix.swap.SwapDb;
import com.eurobuddha.comms.CommsIdentity;
import com.eurobuddha.comms.CommsTransport;

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

/** The ⇄ switch with a live, signable market: tombstone first, flip after (or after the 4 s watchdog), and NOTHING may be
 *  saved, published or switched again in between. The launch rule keeps MINIMA while anything of it is in flight. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class DoorHostSwitchTest {
    private DoorHost host;
    private final List<Order> sent = new ArrayList<>();
    private final List<CommsTransport.SendCb> cbs = new ArrayList<>();
    private DoorHost.Tombstones real;

    @Before public void up() {
        Application app = RuntimeEnvironment.getApplication();
        com.eurobuddha.dexicon.TradingGate.record(app, new org.json.JSONArray());
        host = DoorHost.get(app);
        real = host.tombstones;
        idle(5);
        host.switchMarket(TradingContext.MXUSDT, null);
        quiet();
        host.tombstones = new DoorHost.Tombstones() {
            @Override public boolean ready(CommsIdentity id) { return true; }
            @Override public void send(CommsIdentity id, Order o, CommsTransport.SendCb cb) { sent.add(o); cbs.add(cb); }
        };
    }
    @After public void down() { idle(5); host.tombstones = real; host.switchMarket(TradingContext.MXUSDT, null); idle(5); quiet(); }

    private void quiet() {
        host.prefs().edit().remove("order_config").remove("market_mxusdt").remove("market_minima").remove("withdraw_pending_mxusdt").remove("withdraw_pending_minima")
                .putBoolean("auto_publish", false).putBoolean("otc_enable", false).putBoolean("otc_auto", false).commit();
    }
    private static void idle(int s) { Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(s)); }

    private Order liquid() {
        Order o = Order.fromConfigJson("");
        Order.Pair p = o.pairs.values().iterator().next();
        p.enable = true; p.asks.add(new Order.Level(1.02, 25)); p.bids.add(new Order.Level(0.98, 25));
        Order.sanitize(p);
        return o;
    }

    @Test public void tombstoneThenFlipAndNothingSlipsInBetween() {
        host.prefs().edit().putString("order_config", Order.toConfigJson(liquid())).putBoolean("auto_publish", true).commit();
        long gen0 = PublishGate.gen();
        final boolean[] done = {false};
        assertNull(host.switchMarket(TradingContext.MINIMA, () -> done[0] = true));

        assertTrue(host.switching());
        assertEquals("not flipped before the tombstone", TradingContext.MXUSDT, host.market());
        assertEquals(1, sent.size());
        assertFalse("the tombstone carries no liquidity", sent.get(0).hasLiquidity());
        assertTrue(host.withdrawalPending(TradingContext.MXUSDT));

        assertEquals(DoorHost.SWITCHING, host.switchMarket(TradingContext.MXUSDT, null));
        assertFalse("the editor cannot save mid-switch", host.saveOrder(liquid()));
        final String[] said = {null};
        host.publishOrder(s -> said[0] = s);
        assertEquals(DoorHost.SWITCHING, said[0]);
        host.pushOrderEdit(liquid(), s -> said[0] = s + "!");
        assertEquals(DoorHost.SWITCHING + "!", said[0]);
        host.publishOtcOffer(5, 5, s -> said[0] = s + "?");
        assertEquals(DoorHost.SWITCHING + "?", said[0]);
        assertNotNull(host.take(TradingContext.MXUSDT, null, null, null));

        cbs.get(0).onSent("0x01");
        idle(1);
        assertTrue(done[0]);
        assertFalse(host.switching());
        assertEquals(TradingContext.MINIMA, host.market());
        assertTrue("queued publications of the leaving market are void", PublishGate.gen() > gen0);
        assertNull("the arriving market starts quiesced (disarmed)", host.prefs().getString("order_config", null));
        assertFalse(host.prefs().getBoolean("auto_publish", true));
        assertFalse("its withdrawal landed", host.withdrawalPending(TradingContext.MXUSDT));
        assertFalse("the guard is empty", host.armMaker().hasLiquidity());
        assertTrue("the leaving config is parked as it was", host.prefs().getString("market_mxusdt", "").contains("auto_publish\":true"));
    }

    @Test public void aHungTombstoneStillFlipsAfterTheWatchdog() {
        host.prefs().edit().putString("order_config", Order.toConfigJson(liquid())).commit();
        assertNull(host.switchMarket(TradingContext.MINIMA, null));
        idle(3);
        assertTrue(host.switching());
        idle(2);
        assertFalse(host.switching());
        assertEquals(TradingContext.MINIMA, host.market());
        assertTrue("its withdrawal never confirmed", host.withdrawalPending(TradingContext.MXUSDT));
    }

    @Test public void launchKeepsMinimaWhileItsSwapOrOtcDealOrWithdrawalIsInFlight() {
        assertNull(host.switchMarket(TradingContext.MINIMA, null));
        SwapDb.Swap s = new SwapDb.Swap();
        s.hash = "ab".repeat(32); s.role = "RESPONDER"; s.sellToken = TradingContext.MINIMA.coinLabel; s.buyToken = "USDT";
        s.status = SwapDb.ST_STARTED; s.sellAmount = "10"; s.buyAmount = "0.05";
        host.swapDb().upsertSwap(s);
        assertNotNull(host.applyLaunchRule());
        assertEquals(TradingContext.MINIMA, host.market());
        s.status = SwapDb.ST_COMPLETE; host.swapDb().upsertSwap(s);

        OtcDb.Deal d = new OtcDb.Deal();
        d.ref = "deal-1"; d.role = OtcDb.ROLE_LP; d.side = com.eurobuddha.atomix.swap.OtcOffer.LP_SELLS_MINIMA; d.amount = "10"; d.price = "0.005";
        d.status = OtcDb.ST_PROPOSED; d.whoseTurn = OtcDb.TURN_ME; d.currency = TradingContext.MINIMA.key;
        assertTrue(host.otcDb().createDeal(d));
        String note = host.applyLaunchRule();
        assertTrue(note, note.contains("OTC deal is in flight"));
        assertEquals(TradingContext.MINIMA, host.market());
        OtcDb.Deal done = d.copy(); done.status = OtcDb.ST_REJECTED;
        assertTrue(host.otcDb().replaceDeal(d, done));

        host.prefs().edit().putBoolean("withdraw_pending_minima", true).commit();
        assertTrue(host.applyLaunchRule().contains("withdrawal has not landed"));
        assertEquals(TradingContext.MINIMA, host.market());

        host.prefs().edit().remove("withdraw_pending_minima").commit();
        assertNull(host.applyLaunchRule());
        assertEquals("nothing left in flight: MxUSD", TradingContext.MXUSDT, host.market());
    }
}
