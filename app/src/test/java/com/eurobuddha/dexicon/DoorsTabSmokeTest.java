package com.eurobuddha.dexicon;

import static org.junit.Assert.*;

import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** The DOORS tab builds and renders (no node, no ETH RPC) and the bottom bar carries all seven tabs. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class DoorsTabSmokeTest {
    static TextView find(View v, String text) {
        if (v instanceof TextView && text.equals(((TextView) v).getText().toString())) return (TextView) v;
        if (v instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) v).getChildCount(); i++) { TextView t = find(((ViewGroup) v).getChildAt(i), text); if (t != null) return t; }
        return null;
    }
    /** The trading screens, as on a phone whose node's MiniHub has been read once and held neither app. */
    static MainActivity launch() {
        com.eurobuddha.pandapools.DexiconPools.custodyExecutorForTest(Runnable::run);   // the background custody read, inline
        TradingGate.record(org.robolectric.RuntimeEnvironment.getApplication(), new org.json.JSONArray());
        return org.robolectric.Robolectric.buildActivity(MainActivity.class).setup().get();
    }
    static String room(int r) { return MainActivity.ROOMS[r]; }
    /** A bottom-bar tab: the cell (icon over label) that takes the tap. */
    static View tab(View root, String name) { TextView t = find(root, name); return t == null ? null : (View) t.getParent(); }

    @Test public void doorsTabRendersBothRoutes() {
        // no node in a JVM test: the IPC transport's bindService must not deliver a null binder
        android.app.Application app = org.robolectric.RuntimeEnvironment.getApplication();
        org.robolectric.Shadows.shadowOf(app).setComponentNameAndServiceForBindService(new android.content.ComponentName(app, NodeTransportService.class),
                new android.os.Messenger(new android.os.Handler(android.os.Looper.getMainLooper(), m -> true)).getBinder());
        MainActivity a = DoorsTabSmokeTest.launch();
        View root = a.getWindow().getDecorView();
        for (String tab : new String[]{"Trade", "Activity", "Orders", "History", "Assets", "Settings", "Market", "OTC", "My pools", room(0), room(1), room(2)}) assertNotNull(tab, find(root, tab));
        assertTrue("every launch opens on ① Swap, the form first", find(root, "Swap MxUSD ⇄ USDT").isShown());
        assertTrue("six tabs: the room's three pages and the shared three", find(root, "Swap").isShown() && find(root, "Market").isShown() && find(root, "OTC").isShown()
                && find(root, "Activity").isShown() && find(root, "Assets").isShown() && find(root, "Settings").isShown());
        assertFalse("other rooms' pages are not on the bar", find(root, "Trade").isShown());
        assertNotNull("① explains why it comes first, folded to one line", find(root, "WHY THIS STEP  ▸"));
        find(root, room(1)).performClick();
        assertTrue("② is optional: Skip is the main button", find(root, "Skip — go to the exchange →").isShown());
        find(root, "Skip — go to the exchange →").performClick();
        assertTrue("Skip lands on ③", find(root, "Trade").isShown());
        find(root, room(0)).performClick();
        // 0.1.93: AtomiX's own Swap tab — header, Sell / Buy toggle, both shown; one market at a time
        assertTrue("AtomiX's header", find(root, "Swap MxUSD ⇄ USDT").isShown());
        assertTrue("Buy is there", find(root, "Buy MxUSD").isShown());
        assertTrue("and so is the way back to USDT", find(root, "Sell MxUSD").isShown());
        assertNull("never both markets", find(root, "Buy MINIMA"));
        TextView setup = find(root, "Node not connected") != null ? find(root, "Node not connected") : find(root, "Setting up AtomiX…");
        assertNotNull("before AtomiX is ready it says so — never \"no maker selling\"", setup);
        assertTrue(setup.isShown());
        find(root, "Sell MxUSD").performClick();
        assertTrue(find(root, "Sell MxUSD").isShown());
        assertNull("the old door panes are gone", find(root, "EXIT"));
        tab(root, "Activity").performClick();
    }

    /** ACTIVITY / HISTORY / ASSETS render from real rows; a finished line opens to its proof; tapping an id copies it in FULL. */
    @Test public void ledgerTabsRenderFoldAndCopyFullIds() throws Exception {
        android.app.Application app = org.robolectric.RuntimeEnvironment.getApplication();
        org.robolectric.Shadows.shadowOf(app).setComponentNameAndServiceForBindService(new android.content.ComponentName(app, NodeTransportService.class),
                new android.os.Messenger(new android.os.Handler(android.os.Looper.getMainLooper(), m -> true)).getBinder());
        MainActivity a = DoorsTabSmokeTest.launch();
        String tx = "0x" + "0A5F".repeat(16), coin = "0x" + "9955".repeat(16);
        long now = System.currentTimeMillis();
        ActivityLog.add(a.db(), new org.json.JSONObject().put("id", "slow_x").put("route", "POOL").put("role", "taker").put("side", "buy").put("amount", "2728.1472894674")
                .put("price", "0.003792827565").put("mxusd", "10.34739224").put("ts", now - 600_000).put("stage", ActivityLog.FAILED).put("reason", "test").put("txpowid", tx)
                .put("outs", new org.json.JSONArray().put(coin)));
        a.db().saveFill(new org.json.JSONObject().put("role", "taker").put("orderId", "").put("side", "buy").put("price", "0.003792827565").put("filled", "2728.1472894674")
                .put("gave", "10.34739224").put("txpowid", tx).put("coins", new org.json.JSONArray().put(coin)).put("counterparty", "POOL").put("ts", now / 1000 - 600).put("status", "done").put("block", "2382347"));
        View root = a.getWindow().getDecorView();

        tab(root, "Activity").performClick();
        assertNotNull(find(root, "Activity"));
        assertNotNull(find(root, "Buy MINIMA"));
        assertNotNull(find(root, "2728.1472894674 MINIMA @ 0.003792827565"));
        TextView id = find(root, tx);
        assertNotNull("the full txpow id is in the (folded) proof", id);
        ((View) find(root, "Buy MINIMA").getParent().getParent().getParent()).performClick();   // open the finished line
        id.performClick();
        android.content.ClipboardManager cm = (android.content.ClipboardManager) a.getSystemService(android.content.Context.CLIPBOARD_SERVICE);
        assertEquals("tap copies the COMPLETE id", tx, cm.getPrimaryClip().getItemAt(0).getText().toString());

        tab(root, "History").performClick();
        assertNotNull(find(root, "NET · SETTLED TRADES"));
        assertNotNull(find(root, "+2728.1472894674 MINIMA"));
        assertNotNull(find(root, "−10.34739224 MxUSD"));
        assertNotNull(find(root, "Bought"));
        assertNotNull(find(root, "Coins you received (1) ▸"));

        tab(root, "Assets").performClick();
        assertNotNull(find(root, "YOUR TRADING ID"));
        assertNotNull("PandaPools' wallet tab lives on Assets (0.2.0)", find(root, "WALLET · ALL TOKENS"));
        assertTrue("its own content, hosted in the page (one scroll)", a.findViewById(R.id.balancesContainer).isShown());
        assertFalse("not inside a second ScrollView", a.findViewById(R.id.balancesContainer).getParent() instanceof android.widget.ScrollView);

        find(root, room(0)).performClick();
        assertNotNull(find(root, "Swap MxUSD ⇄ USDT"));
    }

    /** 0.1.92: MINIMA shows at most 12 decimals, cut and never rounded. */
    @Test public void minimaIsCutToTwelveDecimalsNeverRounded() {
        assertEquals("891.250628510678", DoorsTab.minima12("891.25062851067800000000000000000000099989999449"));
        assertEquals("0.999999999999", DoorsTab.minima12("0.9999999999999999"));
        assertEquals("5", DoorsTab.minima12("5"));
        assertEquals("—", DoorsTab.minima12(null));
    }

    /** 0.1.93: AtomiX's buy slippage — default 4.2 %, its own pref, bounded 0.1–50 %. */
    @Test public void buySlippageIsAtomixs() {
        android.app.Application app = org.robolectric.RuntimeEnvironment.getApplication();
        org.robolectric.Shadows.shadowOf(app).setComponentNameAndServiceForBindService(new android.content.ComponentName(app, NodeTransportService.class),
                new android.os.Messenger(new android.os.Handler(android.os.Looper.getMainLooper(), m -> true)).getBinder());
        MainActivity a = DoorsTabSmokeTest.launch();
        DoorsTab d = new DoorsTab(a);
        assertEquals(0.042, d.slippage(), 1e-9);
        a.getSharedPreferences("atomix_swap_ui", android.content.Context.MODE_PRIVATE).edit().putFloat("buy_slippage_pct", 2f).apply();
        assertEquals(0.02, d.slippage(), 1e-9);
        a.getSharedPreferences("atomix_swap_ui", android.content.Context.MODE_PRIVATE).edit().putFloat("buy_slippage_pct", 99f).apply();
        assertEquals("out of range falls back to AtomiX's default", 0.042, d.slippage(), 1e-9);
    }
}
