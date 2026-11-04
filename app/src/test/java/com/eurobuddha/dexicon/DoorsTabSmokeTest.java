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
    static String room(int r) { return MainActivity.ROOMS[r] + "\n" + MainActivity.ROOM_SUBS[r] + (r == 0 ? "MxUSD" : ""); }

    @Test public void doorsTabRendersBothRoutes() {
        // no node in a JVM test: the IPC transport's bindService must not deliver a null binder
        android.app.Application app = org.robolectric.RuntimeEnvironment.getApplication();
        org.robolectric.Shadows.shadowOf(app).setComponentNameAndServiceForBindService(new android.content.ComponentName(app, NodeTransportService.class),
                new android.os.Messenger(new android.os.Handler(android.os.Looper.getMainLooper(), m -> true)).getBinder());
        MainActivity a = DoorsTabSmokeTest.launch();
        View root = a.getWindow().getDecorView();
        for (String tab : new String[]{"TRADE", "ACTIVITY", "ORDERS", "HISTORY", "ASSETS", "SETTINGS", room(0), room(1), room(2)}) assertNotNull(tab, find(root, tab));
        assertTrue("every launch opens on ① Dollars in", find(root, "YOUR ETHEREUM WALLET").isShown());
        assertNotNull("① explains why it comes first", find(root, "WHY THIS STEP  ▾"));
        find(root, room(1)).performClick();
        assertTrue("② is optional: Skip is the main button", find(root, "Skip — go to the exchange →").isShown());
        find(root, "Skip — go to the exchange →").performClick();
        assertTrue("Skip lands on ③", find(root, "TRADE").isShown());
        find(root, room(0)).performClick();
        assertNotNull("ENTRY pane title", find(root, "ENTRY — USDT → MxUSD"));
        assertNotNull("the active market's route card", find(root, "● USDT → MxUSD"));
        assertNull("one market at a time — never both side by side", find(root, "○ USDT → MINIMA"));
        find(root, "EXIT").performClick();
        assertNotNull(find(root, "● MxUSD → USDT"));
        assertNotNull(find(root, "AMOUNT · MxUSD"));
        find(root, "ACTIVITY").performClick();
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

        find(root, "ACTIVITY").performClick();
        assertNotNull(find(root, "Activity"));
        assertNotNull(find(root, "Buy MINIMA"));
        assertNotNull(find(root, "2728.1472894674 MINIMA @ 0.003792827565"));
        TextView id = find(root, tx);
        assertNotNull("the full txpow id is in the (folded) proof", id);
        ((View) find(root, "Buy MINIMA").getParent().getParent().getParent()).performClick();   // open the finished line
        id.performClick();
        android.content.ClipboardManager cm = (android.content.ClipboardManager) a.getSystemService(android.content.Context.CLIPBOARD_SERVICE);
        assertEquals("tap copies the COMPLETE id", tx, cm.getPrimaryClip().getItemAt(0).getText().toString());

        find(root, "HISTORY").performClick();
        assertNotNull(find(root, "NET · SETTLED TRADES"));
        assertNotNull(find(root, "+2728.1472894674 MINIMA"));
        assertNotNull(find(root, "−10.34739224 MxUSD"));
        assertNotNull(find(root, "Bought"));
        assertNotNull(find(root, "Coins you received (1) ▸"));

        find(root, "ASSETS").performClick();
        assertNotNull(find(root, "YOUR TRADING ID"));
        assertNotNull(find(root, "RECEIVE MINIMA OR MxUSD"));

        find(root, room(0)).performClick();
        assertNotNull(find(root, "YOUR ETHEREUM WALLET"));
        assertNotNull(find(root, "How this works ▸"));
    }
}
