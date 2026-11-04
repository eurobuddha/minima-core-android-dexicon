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
        assertNotNull("② has its own sub-tabs", shownTab(root, "Swap"));
        assertNotNull(shownTab(root, "Pools"));
    }

    @Test public void swapPageIsPandaPoolsSwap() {
        MainActivity a = DoorsTabSmokeTest.launch();
        View root = a.getWindow().getDecorView();
        DoorsTabSmokeTest.find(root, DoorsTabSmokeTest.room(1)).performClick();
        ((View) shownTab(root, "Swap").getParent()).performClick();
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

    @Test public void myPoolsIsPandaPoolsMyLpWithItsCreateGate() {
        MainActivity a = DoorsTabSmokeTest.launch();
        View root = a.getWindow().getDecorView();
        DoorsTabSmokeTest.find(root, DoorsTabSmokeTest.room(1)).performClick();
        DoorsTabSmokeTest.find(root, "Create a pool (advanced)").performClick();
        assertTrue("lands on My pools", a.findViewById(R.id.lpCreateBtn).isShown());
        assertTrue(a.findViewById(R.id.lpRecoveryBtn).isShown());
        assertNotNull("PandaPools' pool activity", a.findViewById(R.id.actContainer));
        assertNotNull(containing(root, "KEEPING YOUR POOL ALIVE"));
        assertNotNull(containing(root, "eurobuddha.com:9001 · unpruned MegaMMR"));
        assertEquals(com.eurobuddha.pandapools.MainActivity.TAB_ACTIVITY, a.currentTab());
        a.findViewById(R.id.lpCreateBtn).performClick();
        android.app.Dialog d = org.robolectric.shadows.ShadowDialog.getLatestDialog();
        assertNotNull("create opens PandaPools' obligations first", d);
        assertNotNull(containing(d.getWindow().getDecorView(), "Two things this pool will need from you"));
    }

    @Test public void poolsPandaPoolsKeepsAreListedInFullOnMyPools() {
        android.app.Application app = org.robolectric.RuntimeEnvironment.getApplication();
        String pool = "0x" + "ab".repeat(32);
        com.eurobuddha.pandapools.Pool p = new com.eurobuddha.pandapools.Pool();
        p.address = pool; p.opk = "0x" + "b0".repeat(32); p.oadr = "0x" + "c0".repeat(32); p.tok = "0x" + "d0".repeat(32);
        p.kmin = "100"; p.covenantScript = "RETURN TRUE";
        assertTrue(com.eurobuddha.pandapools.OwnPoolStore.recordDurably(app, p));
        MainActivity a = DoorsTabSmokeTest.launch();
        CustodyFake.rows = new String[][]{{pool, com.eurobuddha.pandapools.DexiconPools.KEPT_HERE}};
        CustodyFake.install(app, CustodyFake.Install.NEW, 981);   // after launch: the card, not the gate
        a.showTab(MainActivity.TAB_PMINE);
        View root = a.getWindow().getDecorView();
        assertNotNull(containing(root, "KEPT BY PANDAPOOLS"));
        assertNotNull("the full address", containing(root, pool));
        CustodyFake.rows = new String[0][];
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
}
