package com.eurobuddha.dexicon;

import static org.junit.Assert.*;

import android.view.View;
import android.widget.EditText;

import com.eurobuddha.atomix.TradingContext;
import com.eurobuddha.dexicon.doors.DoorHost;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/** ① OTC builds (no node), is the third page under ①, explains itself, and refuses to go live without a node. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class OtcTabSmokeTest {
    private DoorHost host;

    @Before public void up() {
        android.app.Application app = RuntimeEnvironment.getApplication();
        org.robolectric.Shadows.shadowOf(app).setComponentNameAndServiceForBindService(new android.content.ComponentName(app, NodeTransportService.class),
                new android.os.Messenger(new android.os.Handler(android.os.Looper.getMainLooper(), m -> true)).getBinder());
        host = DoorHost.get(app);
        host.switchMarket(TradingContext.MXUSDT, null);
        down();
    }
    @After public void down() { host.prefs().edit().putBoolean("otc_enable", false).putBoolean("otc_auto", false).remove("otc_sell_size").remove("otc_buy_size").commit(); }

    @Test public void otcPageRendersAndStaysOffWithoutANode() {
        MainActivity a = Robolectric.buildActivity(MainActivity.class).setup().get();
        View root = a.getWindow().getDecorView();
        DoorsTabSmokeTest.find(root, "OTC").performClick();
        assertTrue(DoorsTabSmokeTest.find(root, "OTC — A PRIVATE DEAL  ▾").isShown());
        assertNotNull(DoorsTabSmokeTest.find(root, "MY AVAILABILITY"));
        assertNotNull(DoorsTabSmokeTest.find(root, "LIQUIDITY PROVIDERS"));
        assertNotNull(DoorsTabSmokeTest.find(root, "MY DEALS"));
        assertNotNull(DoorsTabSmokeTest.find(root, "No OTC deals yet."));
        assertNotNull(DoorsTabSmokeTest.find(root, "○ Off"));

        DoorsTabSmokeTest.find(root, "Go live").performClick();
        assertNotNull("no sizes → asked for them", DoorsTabSmokeTest.find(root, "Enter a sell and/or buy size"));
        EditText sell = (EditText) ((android.view.ViewGroup) DoorsTabSmokeTest.find(root, "I SELL up to").getParent()).getChildAt(1);
        assertEquals("max MxUSD (blank = off)", sell.getHint().toString());
        sell.setText("50");
        DoorsTabSmokeTest.find(root, "Go live").performClick();
        assertFalse("no node: nothing goes live", host.prefs().getBoolean("otc_enable", false));
    }
}
