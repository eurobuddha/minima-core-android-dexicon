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

    /** 0.2.19 (owner: parity): ② is PandaPools — its five tabs on the bar, its own screens, nothing of Dexicon's added. */
    @Test public void poolsRoomIsPandaPoolsFiveTabs() {
        MainActivity a = DoorsTabSmokeTest.launch();
        View root = a.getWindow().getDecorView();
        DoorsTabSmokeTest.find(root, DoorsTabSmokeTest.room(1)).performClick();
        for (String t : new String[]{"Swap", "Pools", "My LP", "Wallet", "Activity"}) assertNotNull("PandaPools' " + t + " tab", shownTab(root, t));
        assertNull("its own Wallet and Activity — not Dexicon's shared tabs", shownTab(root, "Assets"));
        assertNull(shownTab(root, "Settings"));
        assertTrue("opens on SWAP, as PandaPools", a.findViewById(R.id.swapAmount).isShown());
        assertNull("AtomiX's ⇄ pill is not PandaPools'", PoolsRoomSmokeTest.containing(root, "⇄  MxUSD") != null && PoolsRoomSmokeTest.containing(root, "⇄  MxUSD").isShown() ? "shown" : null);
        assertNull("no Dexicon additions", containing(root, "Skip — go to the exchange"));
        assertNull(containing(root, "Create a pool (advanced)"));
        assertNull(containing(root, "WHAT A POOL IS"));
        ((View) shownTab(root, "Pools").getParent()).performClick();
        assertTrue("PandaPools' pool list", a.findViewById(R.id.poolList).isShown());
        assertEquals(com.eurobuddha.pandapools.MainActivity.TAB_POOLS, a.currentTab());
    }

    @Test public void swapPageIsPandaPoolsSwap() {
        MainActivity a = DoorsTabSmokeTest.launch();
        View root = a.getWindow().getDecorView();
        DoorsTabSmokeTest.find(root, DoorsTabSmokeTest.room(1)).performClick();
        ((View) shownTab(root, "Swap").getParent()).performClick();
        assertTrue("SwapView's pay field", a.findViewById(R.id.swapAmount).isShown());
        assertTrue(a.findViewById(R.id.swapFlip).isShown());
        assertTrue(a.findViewById(R.id.swapBtn).isShown());
        View list = a.findViewById(R.id.poolList);
        assertTrue("the pools list is a different page", list == null || !list.isShown());
        a.goToTab(com.eurobuddha.pandapools.MainActivity.TAB_POOLS);   // PandaPools' own jumps land on its own pages
        assertTrue(a.findViewById(R.id.poolList).isShown());
        a.goToTab(com.eurobuddha.pandapools.MainActivity.TAB_ACTIVITY);
        assertEquals(com.eurobuddha.pandapools.MainActivity.TAB_ACTIVITY, a.currentTab());
        a.goToTab(com.eurobuddha.pandapools.MainActivity.TAB_WALLET);
        assertEquals(com.eurobuddha.pandapools.MainActivity.TAB_WALLET, a.currentTab());
    }

    @Test public void theHostServesThePoolEngine() {
        MainActivity a = DoorsTabSmokeTest.launch();
        assertNotNull(a.node());
        assertNotNull(a.pools());
        assertNotNull(a.history());
    }

    @Test public void myLpIsPandaPoolsMyLpWithItsCreateGate() {
        MainActivity a = DoorsTabSmokeTest.launch();
        View root = a.getWindow().getDecorView();
        DoorsTabSmokeTest.find(root, DoorsTabSmokeTest.room(1)).performClick();
        ((View) shownTab(root, "My LP").getParent()).performClick();
        assertTrue(a.findViewById(R.id.lpCreateBtn).isShown());
        assertTrue(a.findViewById(R.id.lpRecoveryBtn).isShown());
        assertEquals(com.eurobuddha.pandapools.MainActivity.TAB_MYLP, a.currentTab());
        a.findViewById(R.id.lpCreateBtn).performClick();
        android.app.Dialog d = org.robolectric.shadows.ShadowDialog.getLatestDialog();
        assertNotNull("create opens PandaPools' obligations first", d);
        assertNotNull(containing(d.getWindow().getDecorView(), "Two things this pool will need from you"));
    }

    /** 0.1.98 (PandaPools MainActivity.onCreate): pool dialogs follow Dexicon's ☀/☾, not the phone's day/night. */
    @Test public void dialogChromeFollowsTheThemeToggle() {
        MainActivity a = DoorsTabSmokeTest.launch();
        int want = Design.isDark() ? androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES : androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO;
        assertEquals(want, a.getDelegate().getLocalNightMode());
        View pill = containing(a.getWindow().getDecorView(), Design.isDark() ? "☀" : "☾");
        if (pill == null) pill = containing(a.getWindow().getDecorView(), "☀");
        assertNotNull("the theme pill", pill);
        pill.performClick();
        int now = Design.isDark() ? androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES : androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO;
        assertNotEquals(want, now);
        assertEquals("the toggle moves the dialogs with it", now, a.getDelegate().getLocalNightMode());
        pill = containing(a.getWindow().getDecorView(), "☀"); if (pill == null) pill = containing(a.getWindow().getDecorView(), "☾");
        pill.performClick();   // leave the palette as found
    }

    /** 0.1.99 (PandaPools re-shows its tab on resume): back from the backup file picker, My pools polls again. */
    @Test public void resumingOnMyPoolsRestartsItsRefresh() throws Exception {
        com.eurobuddha.pandapools.DexiconPools.custodyExecutorForTest(Runnable::run);
        TradingGate.record(org.robolectric.RuntimeEnvironment.getApplication(), new org.json.JSONArray());
        org.robolectric.android.controller.ActivityController<MainActivity> c = org.robolectric.Robolectric.buildActivity(MainActivity.class).setup();
        MainActivity a = c.get();
        a.showTab(MainActivity.TAB_PMINE);
        java.lang.reflect.Field lpF = MainActivity.class.getDeclaredField("pMyLp"); lpF.setAccessible(true);
        Object lp = lpF.get(a);
        java.lang.reflect.Field stopped = lp.getClass().getDeclaredField("stopped"); stopped.setAccessible(true);
        c.pause().stop();
        assertTrue("backgrounded: the pool views stop", (boolean) stopped.get(lp));
        c.restart().start().resume();
        assertFalse("resumed on My pools: it runs again", (boolean) stopped.get(lp));
    }

    /** 0.2.40 (owner): ② Wallet has a "Consolidate MxUSD coins" beside PandaPools' MINIMA one — `consolidate tokenid:<MxUSD>`. */
    @Test public void walletConsolidatesMxUsd() {
        MainActivity a = DoorsTabSmokeTest.launch();
        a.mxusdCoins = 10;
        try { java.lang.reflect.Field pf = MainActivity.class.getDeclaredField("paired"); pf.setAccessible(true); pf.set(a, true); }
        catch (Exception e) { throw new AssertionError(e); }
        a.showTab(MainActivity.TAB_PWALLET);
        try {   // no node here: draw PandaPools' wallet box the way a balance reply does, twice (it rebuilds on every render)
            java.lang.reflect.Field wf = MainActivity.class.getDeclaredField("pWallet"); wf.setAccessible(true);
            Object w = wf.get(a);
            java.lang.reflect.Method rc = w.getClass().getDeclaredMethod("renderCards"); rc.setAccessible(true);
            rc.invoke(w); rc.invoke(w);
        } catch (Exception e) { throw new AssertionError(e); }
        View root = a.getWindow().getDecorView();
        TextView link = shownTab(root, "Consolidate MxUSD coins");
        assertNotNull("on ② Wallet", link);
        TextView minima = shownTab(root, "Consolidate MINIMA coins");
        assertNotNull(minima);
        android.view.ViewGroup box = (android.view.ViewGroup) minima.getParent();
        assertSame("inside the AVAILABLE TO POOL box", box, link.getParent());
        assertEquals("directly under the MINIMA one", box.indexOfChild(minima) + 1, box.indexOfChild(link));
        assertEquals("same size", minima.getTextSize(), link.getTextSize(), 0.01f);
        assertEquals("same font", minima.getTypeface(), link.getTypeface());
        assertEquals("same colour", minima.getCurrentTextColor(), link.getCurrentTextColor());
        link.performClick();
        android.app.Dialog d = org.robolectric.shadows.ShadowDialog.getLatestDialog();
        assertNotNull(d);
        assertNotNull("the node's own command, the token id in full", containing(d.getWindow().getDecorView(), "consolidate tokenid:" + com.eurobuddha.dexicon.fill.Tokens.MXUSD()));
    }
}
