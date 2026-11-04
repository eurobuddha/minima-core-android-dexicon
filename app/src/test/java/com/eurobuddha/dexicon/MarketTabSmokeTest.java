package com.eurobuddha.dexicon;

import static org.junit.Assert.*;

import android.view.View;

import androidx.appcompat.app.AlertDialog;

import com.eurobuddha.atomix.TradingContext;
import com.eurobuddha.atomix.swap.Order;
import com.eurobuddha.dexicon.doors.DoorHost;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowDialog;

/** ① MARKET builds (no node, no ETH RPC), sits beside SWAP under ①, explains itself, and its editor saves a ladder. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class MarketTabSmokeTest {
    private DoorHost host;

    @Before public void up() {
        android.app.Application app = RuntimeEnvironment.getApplication();
        org.robolectric.Shadows.shadowOf(app).setComponentNameAndServiceForBindService(new android.content.ComponentName(app, NodeTransportService.class),
                new android.os.Messenger(new android.os.Handler(android.os.Looper.getMainLooper(), m -> true)).getBinder());
        host = DoorHost.get(app);
        host.switchMarket(TradingContext.MXUSDT, null);
        host.prefs().edit().remove("order_config").putBoolean("auto_publish", false).commit();
    }
    @After public void down() { host.prefs().edit().remove("order_config").putBoolean("auto_publish", false).commit(); }

    @Test public void marketPageRendersAndEditorSaves() {
        MainActivity a = Robolectric.buildActivity(MainActivity.class).setup().get();
        View root = a.getWindow().getDecorView();
        assertTrue("① shows its pages in the sub row", DoorsTabSmokeTest.find(root, "SWAP").isShown());
        DoorsTabSmokeTest.find(root, "MARKET").performClick();
        assertTrue(DoorsTabSmokeTest.find(root, "YOUR AtomiX MARKET  ▾").isShown());
        assertNotNull(DoorsTabSmokeTest.find(root, "YOUR OFFER · USDT ⇄ MxUSD"));
        assertNotNull(DoorsTabSmokeTest.find(root, "Off — edit your order, then Publish."));
        assertNotNull(DoorsTabSmokeTest.find(root, "Order book  ·  0 live"));

        DoorsTabSmokeTest.find(root, "Publish").performClick();
        assertTrue("no node: nothing is published", DoorsTabSmokeTest.find(root, "Publish").isShown());
        assertFalse(host.prefs().getBoolean("auto_publish", false));

        DoorsTabSmokeTest.find(root, "Edit my order").performClick();
        AlertDialog d = (AlertDialog) ShadowDialog.getLatestDialog();
        View dv = d.getWindow().getDecorView();
        assertNotNull(DoorsTabSmokeTest.find(dv, "ASKS — you SELL MxUSD (higher price)"));
        android.view.ViewGroup a1 = (android.view.ViewGroup) DoorsTabSmokeTest.find(dv, "A1").getParent();
        ((android.widget.EditText) a1.getChildAt(1)).setText("1.02");
        ((android.widget.EditText) a1.getChildAt(2)).setText("25");
        d.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        Order saved = host.loadOrder();
        Order.Pair p = saved.pairs.values().iterator().next();
        assertEquals(1, p.asks.size());
        assertEquals(1.02, p.asks.get(0).price, 1e-12);
        assertEquals(25, p.asks.get(0).amount, 1e-12);
    }
}
