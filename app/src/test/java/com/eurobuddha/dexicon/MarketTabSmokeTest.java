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
        MainActivity a = DoorsTabSmokeTest.launch();
        View root = a.getWindow().getDecorView();
        assertTrue("① shows its pages on the bottom bar", DoorsTabSmokeTest.find(root, "Swap").isShown());
        DoorsTabSmokeTest.tab(root, "Market").performClick();
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

    @Test public void anEditorOpenedForOneMarketNeverSavesIntoTheOther() {
        MainActivity a = DoorsTabSmokeTest.launch();
        View root = a.getWindow().getDecorView();
        DoorsTabSmokeTest.tab(root, "Market").performClick();
        DoorsTabSmokeTest.find(root, "Edit my order").performClick();
        AlertDialog d = (AlertDialog) ShadowDialog.getLatestDialog();
        android.view.ViewGroup a1 = (android.view.ViewGroup) DoorsTabSmokeTest.find(d.getWindow().getDecorView(), "A1").getParent();
        ((android.widget.EditText) a1.getChildAt(1)).setText("1.02");
        ((android.widget.EditText) a1.getChildAt(2)).setText("25");
        host.switchMarket(TradingContext.MINIMA, null);   // a MxUSD ladder must not land on MINIMA (~200x mispriced)
        try {
            d.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            assertFalse("refused and closed", d.isShowing());
            assertFalse("nothing saved into MINIMA", host.loadOrder().hasLiquidity());
        } finally { host.switchMarket(TradingContext.MXUSDT, null); }
    }

    /** 0.2.3 (AtomiX takeOrderDialog): tap a book level to take it — the maker in full, and AtomiX's own guards. */
    @Test public void tappingALevelOpensAtomixsTakeDialog() throws Exception {
        android.app.Application app = org.robolectric.RuntimeEnvironment.getApplication();
        org.robolectric.Shadows.shadowOf(app).setComponentNameAndServiceForBindService(new android.content.ComponentName(app, NodeTransportService.class),
                new android.os.Messenger(new android.os.Handler(android.os.Looper.getMainLooper(), m -> true)).getBinder());
        MainActivity a = DoorsTabSmokeTest.launch();
        com.eurobuddha.dexicon.doors.DoorHost host = com.eurobuddha.dexicon.doors.DoorHost.get(app);
        Order o = new Order();
        o.signerPk = "0x" + "a1".repeat(32); o.commsPublicId = "0x" + "b2".repeat(64); o.minimaAvail = 100; o.usdtAvail = 0; o.ts = System.currentTimeMillis();
        Order.Pair p = new Order.Pair(true, 1.01, 0, 2); p.asks.add(new Order.Level(1.01, 50)); Order.sanitize(p);
        o.pairs.put("USDT", p);
        java.lang.reflect.Field f = com.eurobuddha.dexicon.doors.DoorHost.class.getDeclaredField("books"); f.setAccessible(true);
        @SuppressWarnings("unchecked") java.util.Map<TradingContext, java.util.Map<String, Order>> books = (java.util.Map<TradingContext, java.util.Map<String, Order>>) f.get(host);
        java.util.Map<String, Order> before = books.get(host.market());
        try {
            books.put(host.market(), java.util.Collections.singletonMap(o.signerPk, o));
            View root = a.getWindow().getDecorView();
            DoorsTabSmokeTest.tab(root, "Market").performClick();
            View level = null;
            for (View v : all(root)) if (("Buy " + com.eurobuddha.dexicon.doors.DoorPlan.coin(host.market()) + " at 1.01 USDT").equals(String.valueOf(v.getContentDescription()))) level = v;
            assertNotNull("an ask level is tappable", level);
            level.performClick();
            androidx.appcompat.app.AlertDialog d = (androidx.appcompat.app.AlertDialog) org.robolectric.shadows.ShadowDialog.getLatestDialog();
            assertTrue(d.isShowing());
            View dv = d.getWindow().getDecorView();
            assertNotNull("the maker's key in full", PoolsRoomSmokeTest.containing(dv, o.signerPk));
            assertNotNull(PoolsRoomSmokeTest.containing(dv, "This level: up to 50"));
            assertNotNull(PoolsRoomSmokeTest.containing(dv, "Min: 2"));
            android.widget.EditText amt = null;
            for (View v : all(dv)) if (v instanceof android.widget.EditText) amt = (android.widget.EditText) v;
            amt.setText("1");
            d.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).performClick();
            assertNotNull("below the maker's minimum: refused before anything locks", PoolsRoomSmokeTest.containing(dv, "Below the maker's minimum"));
            amt.setText("60");
            d.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).performClick();
            assertNotNull(PoolsRoomSmokeTest.containing(dv, "This level takes up to 50"));
            assertTrue("still open: nothing started", d.isShowing());
        } finally { books.put(host.market(), before); }
    }

    private static java.util.List<View> all(View v) {
        java.util.List<View> out = new java.util.ArrayList<>(); out.add(v);
        if (v instanceof android.view.ViewGroup) for (int i = 0; i < ((android.view.ViewGroup) v).getChildCount(); i++) out.addAll(all(((android.view.ViewGroup) v).getChildAt(i)));
        return out;
    }

    /** 0.2.7 (AtomiX Wallet): too many coins to read → buys are declined; Market says so and offers AtomiX's consolidate. */
    @Test public void tooManyCoinsWarnsTheMakerOnMarket() {
        android.app.Application app = org.robolectric.RuntimeEnvironment.getApplication();
        org.robolectric.Shadows.shadowOf(app).setComponentNameAndServiceForBindService(new android.content.ComponentName(app, NodeTransportService.class),
                new android.os.Messenger(new android.os.Handler(android.os.Looper.getMainLooper(), m -> true)).getBinder());
        TradingContext m = DoorHost.get(app).market();
        int cap = com.eurobuddha.atomix.swap.MinimaHtlc.maxSafeCoinRows(m.tokenId);
        assertNull(AtomixConsolidate.warning(m, cap));
        assertTrue(AtomixConsolidate.warning(m, cap + 1).contains("AtomiX buys are being declined"));
        MainActivity a = DoorsTabSmokeTest.launch();
        if (m == TradingContext.MINIMA) a.minimaCoins = cap + 5; else a.mxusdCoins = cap + 5;
        View root = a.getWindow().getDecorView();
        DoorsTabSmokeTest.tab(root, "Market").performClick();
        assertNotNull("the maker is told, where the market is run", PoolsRoomSmokeTest.containing(root, "AtomiX buys are being declined"));
    }
}
