package com.eurobuddha.dexicon.doors;

import static org.junit.Assert.*;

import android.app.Application;
import android.content.pm.PackageInfo;

import com.eurobuddha.atomix.TradingContext;
import com.eurobuddha.atomix.swap.Order;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/** The maker side of the one engine: the responder guard arms the saved order — and NOTHING while the trading gate is shut. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class DoorHostMakerTest {
    private DoorHost host;
    private Application app;

    @Before public void up() {
        app = RuntimeEnvironment.getApplication();
        host = DoorHost.get(app);
        com.eurobuddha.dexicon.TradingGate.record(app, new org.json.JSONArray());   // the MiniHub was read: neither app
        host.switchMarket(TradingContext.MXUSDT, null);
        Order o = Order.fromConfigJson("");
        Order.Pair p = o.pairs.values().iterator().next();
        p.enable = true;
        p.asks.add(new Order.Level(1.02, 25));
        p.bids.add(new Order.Level(0.98, 25));
        Order.sanitize(p);
        host.prefs().edit().putString("order_config", Order.toConfigJson(o)).commit();
    }
    @After public void down() { host.prefs().edit().remove("order_config").putBoolean("auto_publish", false).commit(); }

    @Test public void armsTheSavedOrder() {
        assertFalse(host.gated());
        assertTrue("the responder guard carries my ladder", host.armMaker().hasLiquidity());
        assertTrue("a saved liquid order counts as a live maker market", host.makerLive());
    }

    @Test public void armsNothingWhileGated() {
        PackageInfo pi = new PackageInfo();
        pi.packageName = com.eurobuddha.dexicon.TradingGate.ATOMIX;
        org.robolectric.Shadows.shadowOf(app.getPackageManager()).installPackage(pi);
        assertTrue(host.gated());
        assertFalse("gated: every take declines", host.armMaker().hasLiquidity());
    }

    @Test public void tombstoneCarriesNoLiquidity() {
        Order t = host.tombstone();
        assertFalse(t.hasLiquidity());
        for (Order.Pair p : t.pairs.values()) assertFalse(p.enable);
    }

    @Test public void dollarTokenIsAlwaysMxUsd() {
        assertEquals("Your MxUSD order", DoorHost.mx("Your mxUSDT order"));
    }
}
