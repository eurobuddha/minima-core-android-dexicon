package com.eurobuddha.dexicon;

import static org.junit.Assert.*;

import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** ② Pools: PandaPools' own screens (vendored) build and render inside Dexicon's room, with Dexicon's explainers. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class PoolsRoomSmokeTest {
    static TextView containing(View v, String text) {
        if (v instanceof TextView && ((TextView) v).getText().toString().contains(text)) return (TextView) v;
        if (v instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) v).getChildCount(); i++) { TextView t = containing(((ViewGroup) v).getChildAt(i), text); if (t != null) return t; }
        return null;
    }
    /** The visible tab button with this exact label (① and ② both have a SWAP page). */
    static TextView shownTab(View v, String text) {
        if (v instanceof TextView && text.equals(((TextView) v).getText().toString()) && v.isShown()) return (TextView) v;
        if (v instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) v).getChildCount(); i++) { TextView t = shownTab(((ViewGroup) v).getChildAt(i), text); if (t != null) return t; }
        return null;
    }

    @Before public void noNode() {
        android.app.Application app = org.robolectric.RuntimeEnvironment.getApplication();
        org.robolectric.Shadows.shadowOf(app).setComponentNameAndServiceForBindService(new android.content.ComponentName(app, NodeTransportService.class),
                new android.os.Messenger(new android.os.Handler(android.os.Looper.getMainLooper(), m -> true)).getBinder());
    }

    @Test public void poolsPageShowsPandaPoolsListAndKeepsSkipFirst() {
        MainActivity a = DoorsTabSmokeTest.launch();
        View root = a.getWindow().getDecorView();
        DoorsTabSmokeTest.find(root, DoorsTabSmokeTest.room(1)).performClick();
        assertTrue("Skip stays the main ② button", DoorsTabSmokeTest.find(root, "Skip — go to the exchange →").isShown());
        assertTrue("PandaPools' live pool list", a.findViewById(R.id.poolList).isShown());
        assertNotNull(containing(root, "unpruned MegaMMR"));
        assertNotNull(containing(root, "You don't host a pool"));
        assertNotNull("② has its own sub-tabs", shownTab(root, "SWAP"));
        assertNotNull(shownTab(root, "POOLS"));
    }

    @Test public void swapPageIsPandaPoolsSwap() {
        MainActivity a = DoorsTabSmokeTest.launch();
        View root = a.getWindow().getDecorView();
        DoorsTabSmokeTest.find(root, DoorsTabSmokeTest.room(1)).performClick();
        shownTab(root, "SWAP").performClick();
        assertTrue("SwapView's pay field", a.findViewById(R.id.swapAmount).isShown());
        assertTrue(a.findViewById(R.id.swapFlip).isShown());
        assertTrue(a.findViewById(R.id.swapBtn).isShown());
        assertTrue(containing(root, "SWAP WITH THE POOLS").isShown());
        assertNotNull(containing(root, "0.50% fee"));
        View list = a.findViewById(R.id.poolList);
        assertTrue("the pools list is a different page", list == null || !list.isShown());
        // PandaPools' "See all → Activity" style jumps land on Dexicon's ② pages
        a.goToTab(com.eurobuddha.pandapools.MainActivity.TAB_POOLS);
        assertTrue(a.findViewById(R.id.poolList).isShown());
        assertEquals(com.eurobuddha.pandapools.MainActivity.TAB_POOLS, a.currentTab());
    }

    @Test public void theHostServesThePoolEngine() {
        MainActivity a = DoorsTabSmokeTest.launch();
        assertNotNull(a.node());
        assertNotNull(a.pools());
        assertNotNull(a.history());
    }
}
