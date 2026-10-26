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
    @Test public void doorsTabRendersBothRoutes() {
        // no node in a JVM test: the IPC transport's bindService must not deliver a null binder
        android.app.Application app = org.robolectric.RuntimeEnvironment.getApplication();
        org.robolectric.Shadows.shadowOf(app).setComponentNameAndServiceForBindService(new android.content.ComponentName(app, NodeTransportService.class),
                new android.os.Messenger(new android.os.Handler(android.os.Looper.getMainLooper(), m -> true)).getBinder());
        MainActivity a = Robolectric.buildActivity(MainActivity.class).setup().get();
        View root = a.getWindow().getDecorView();
        for (String tab : new String[]{"TRADE", "ACTIVITY", "ORDERS", "HISTORY", "ASSETS", "ATOMIX", "RELAYS"}) assertNotNull(tab, find(root, tab));
        find(root, "ATOMIX").performClick();
        assertNotNull("ENTRY pane title", find(root, "ENTRY — USDT → MxUSD or MINIMA"));
        assertNotNull("MxUSD route card", find(root, "● USDT → MxUSD"));
        assertNotNull("MINIMA route card", find(root, "○ USDT → MINIMA"));
        ((View) find(root, "○ USDT → MINIMA").getParent()).performClick();
        assertNotNull("picking a route moves the selection", find(root, "● USDT → MINIMA"));
        find(root, "EXIT").performClick();
        assertNotNull(find(root, "○ MxUSD → USDT"));
        assertNotNull(find(root, "AMOUNT · MINIMA"));
        find(root, "ACTIVITY").performClick();
    }

    /** ACTIVITY / HISTORY / ASSETS render from real rows; a finished line opens to its proof; tapping an id copies it in FULL. */
    @Test public void ledgerTabsRenderFoldAndCopyFullIds() throws Exception {
        android.app.Application app = org.robolectric.RuntimeEnvironment.getApplication();
        org.robolectric.Shadows.shadowOf(app).setComponentNameAndServiceForBindService(new android.content.ComponentName(app, NodeTransportService.class),
                new android.os.Messenger(new android.os.Handler(android.os.Looper.getMainLooper(), m -> true)).getBinder());
        MainActivity a = Robolectric.buildActivity(MainActivity.class).setup().get();
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

        find(root, "ATOMIX").performClick();
        assertNotNull(find(root, "YOUR ETHEREUM WALLET"));
        assertNotNull(find(root, "How this works ▸"));
    }
}
