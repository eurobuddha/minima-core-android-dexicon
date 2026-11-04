package com.eurobuddha.dexicon;

import static org.junit.Assert.*;

import android.view.View;

import androidx.appcompat.app.AlertDialog;

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
import org.robolectric.shadows.ShadowDialog;

/** The ⇄ pill names the ONE active AtomiX market, switches it AtomiX's way, and every launch opens in MxUSD unless MINIMA is live. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class CcySwitchTest {
    private DoorHost host;

    @Before public void up() {
        android.app.Application app = RuntimeEnvironment.getApplication();
        org.robolectric.Shadows.shadowOf(app).setComponentNameAndServiceForBindService(new android.content.ComponentName(app, NodeTransportService.class),
                new android.os.Messenger(new android.os.Handler(android.os.Looper.getMainLooper(), m -> true)).getBinder());
        host = DoorHost.get(app);
        quiet();
        assertNull(host.switchMarket(TradingContext.MXUSDT, null));
    }
    @After public void down() { quiet(); host.switchMarket(TradingContext.MXUSDT, null); }
    private void quiet() { host.prefs().edit().remove("market_mxusdt").remove("market_minima").remove("order_config").putBoolean("auto_publish", false).putBoolean("otc_enable", false).putBoolean("otc_auto", false).commit(); }

    private MainActivity launch() { return DoorsTabSmokeTest.launch(); }

    @Test public void pillSwitchesTheOneMarket() {
        MainActivity a = launch();
        View root = a.getWindow().getDecorView();
        assertNotNull("the pill names the active market", DoorsTabSmokeTest.find(root, "⇄  MxUSD"));
        assertNotNull("① names it too", DoorsTabSmokeTest.find(root, "Swap MxUSD ⇄ USDT"));
        DoorsTabSmokeTest.find(root, "⇄  MxUSD").performClick();
        AlertDialog d = (AlertDialog) ShadowDialog.getLatestDialog();
        assertTrue(d.isShowing());
        d.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        assertEquals(TradingContext.MINIMA, host.market());
        assertEquals("AtomiX's own key persists the choice", "minima", host.prefs().getString(TradingContext.PREF_KEY, ""));
        assertNotNull(DoorsTabSmokeTest.find(root, "⇄  MINIMA"));
        
        assertNotNull("① now quotes MINIMA only", DoorsTabSmokeTest.find(root, "Swap MINIMA ⇄ USDT"));
        assertNotNull(DoorsTabSmokeTest.find(root, "Buy MINIMA"));
        assertNull(DoorsTabSmokeTest.find(root, "Buy MxUSD"));
        assertTrue("long-press cycles the glow", DoorsTabSmokeTest.find(root, "⇄  MINIMA").performLongClick());
    }

    @Test public void switchParksAndRestoresEachMarketsConfigAndQuiesces() {
        host.prefs().edit().putString("order_config", "{\"x\":1}").putString("otc_sell_size", "5").commit();
        host.switchMarket(TradingContext.MINIMA, null);
        assertNull("the arriving market starts quiesced", host.prefs().getString("order_config", null));
        assertFalse(host.prefs().getBoolean("auto_publish", true));
        assertTrue(host.prefs().getBoolean(com.eurobuddha.atomix.swap.PriceOracle.P_WITHDRAWN, false));
        host.switchMarket(TradingContext.MXUSDT, null);
        assertEquals("flicking back restores the MxUSD config", "{\"x\":1}", host.prefs().getString("order_config", null));
        assertEquals("5", host.prefs().getString("otc_sell_size", null));
    }

    @Test public void launchOpensInMxUsdWhenNothingIsLive() {
        host.switchMarket(TradingContext.MINIMA, null);
        MainActivity a = launch();
        assertEquals(TradingContext.MXUSDT, host.market());
        assertNotNull(DoorsTabSmokeTest.find(a.getWindow().getDecorView(), "⇄  MxUSD"));
    }

    @Test public void launchStaysInMinimaWithABannerWhileItsMarketIsLive() {
        host.switchMarket(TradingContext.MINIMA, null);
        host.prefs().edit().putBoolean("auto_publish", true).commit();
        MainActivity a = launch();
        View root = a.getWindow().getDecorView();
        assertEquals("a live market is never flipped silently", TradingContext.MINIMA, host.market());
        assertNotNull(DoorsTabSmokeTest.find(root, "⇄  MINIMA"));
        String note = host.launchNote();
        assertNotNull(note);
        assertTrue(note.contains("MINIMA market is live"));
        assertTrue("the banner is on ①", DoorsTabSmokeTest.find(root, note).isShown());
    }
}
