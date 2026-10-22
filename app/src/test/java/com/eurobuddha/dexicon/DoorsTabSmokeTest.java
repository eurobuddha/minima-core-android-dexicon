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
        for (String tab : new String[]{"TRADE", "ACTIVITY", "ORDERS", "HISTORY", "ASSETS", "DOORS", "RELAYS"}) assertNotNull(tab, find(root, tab));
        find(root, "DOORS").performClick();
        assertNotNull("ENTER pane title", find(root, "ENTER — USDT → MxUSD or MINIMA"));
        assertNotNull("MxUSD route card", find(root, "● USDT → MxUSD"));
        assertNotNull("MINIMA route card", find(root, "○ USDT → MINIMA"));
        ((View) find(root, "○ USDT → MINIMA").getParent()).performClick();
        assertNotNull("picking a route moves the selection", find(root, "● USDT → MINIMA"));
        find(root, "EXIT").performClick();
        assertNotNull(find(root, "○ MxUSD → USDT"));
        assertNotNull(find(root, "AMOUNT · MINIMA"));
        find(root, "ACTIVITY").performClick();
    }
}
