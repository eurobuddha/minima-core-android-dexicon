package com.eurobuddha.dexicon;

import static org.junit.Assert.*;

import android.view.View;
import android.view.Window;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;

import com.eurobuddha.dexicon.ui.Screen;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;

/** 0.3.52: the Dexicon screen runs in ANY host — here a plain AppCompatActivity with its own layout and a FrameLayout slot
 *  (what a tab of Pandamonium would be), not DexiconActivity. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class ScreenHostTest {

    /** A tab host: its own window and layout; the screen gets only the slot. */
    static final class TabHost implements Screen.Host {
        final AppCompatActivity act;
        final FrameLayout slot;
        final TextView ownLabel;
        final boolean allowWindow;
        int finishAsks;
        TabHost(boolean allowWindow) {
            this.allowWindow = allowWindow;
            act = Robolectric.buildActivity(AppCompatActivity.class).setup().get();   // already RESUMED: the tab opens late
            LinearLayout root = new LinearLayout(act);
            root.setOrientation(LinearLayout.VERTICAL);
            ownLabel = new TextView(act); ownLabel.setText("HOST TAB BAR");
            slot = new FrameLayout(act);
            root.addView(ownLabel);
            root.addView(slot, new LinearLayout.LayoutParams(-1, 0, 1f));
            act.setContentView(root);
        }
        @Override public AppCompatActivity activity() { return act; }
        @Override public FrameLayout slot() { return slot; }
        @Override public boolean allowWindowChanges() { return allowWindow; }
        @Override public void finishScreen() { finishAsks++; }
    }

    @Before public void noNode() {
        android.app.Application app = org.robolectric.RuntimeEnvironment.getApplication();
        Shadows.shadowOf(app).setComponentNameAndServiceForBindService(new android.content.ComponentName(app, NodeTransportService.class),
                new android.os.Messenger(new android.os.Handler(android.os.Looper.getMainLooper(), m -> true)).getBinder());
        com.eurobuddha.pandapools.DexiconPools.custodyExecutorForTest(Runnable::run);
        TradingGate.record(app, new org.json.JSONArray());
    }

    /** Everything about the host's window the screen could change. */
    private static String windowState(AppCompatActivity a) throws Exception {
        Window w = a.getWindow();
        android.view.WindowManager.LayoutParams lp = w.getAttributes();
        return "flags=" + lp.flags + " soft=" + lp.softInputMode + " cutout=" + lp.layoutInDisplayCutoutMode
                + " sysui=" + w.getDecorView().getSystemUiVisibility() + " status=" + w.getStatusBarColor() + " nav=" + w.getNavigationBarColor()
                + " decorFits=" + decorFits(w) + " night=" + a.getDelegate().getLocalNightMode();
    }

    private static boolean decorFits(Window w) throws Exception {
        for (Class<?> c = w.getClass(); c != null; c = c.getSuperclass()) {
            try { java.lang.reflect.Field f = c.getDeclaredField("mDecorFitsSystemWindows"); f.setAccessible(true); return f.getBoolean(w); }
            catch (NoSuchFieldException next) { /* up the hierarchy */ }
        }
        throw new AssertionError("PhoneWindow.mDecorFitsSystemWindows not found: the window check would prove nothing");
    }

    @Test public void runsInATabHostThatRefusesWindowChanges() throws Exception {
        TabHost host = new TabHost(false);
        String before = windowState(host.act);
        MainActivity s = new MainActivity(host);

        s.create(null); s.start(); s.resume();
        assertEquals("the chrome is the slot's one child", 1, host.slot.getChildCount());
        assertNotNull("the room switcher is in the slot", DoorsTabSmokeTest.find(host.slot, DoorsTabSmokeTest.room(0)));
        assertNotNull("the bottom bar is in the slot", DoorsTabSmokeTest.find(host.slot, "Settings"));
        assertSame("the host's own layout is untouched", host.ownLabel, DoorsTabSmokeTest.find(host.act.getWindow().getDecorView(), "HOST TAB BAR"));
        assertTrue(MainActivity.FOREGROUND);
        assertEquals("nothing touched the host's window", before, windowState(host.act));

        // the theme toggle rebuilds the chrome through the slot, still without touching the window
        View pill = PoolsRoomSmokeTest.containing(host.slot, "☀");
        if (pill == null) pill = PoolsRoomSmokeTest.containing(host.slot, "☾");
        assertNotNull(pill);
        pill.performClick();
        assertEquals(1, host.slot.getChildCount());
        assertEquals("the toggle left the host's window alone", before, windowState(host.act));
        pill = PoolsRoomSmokeTest.containing(host.slot, "☀");
        if (pill == null) pill = PoolsRoomSmokeTest.containing(host.slot, "☾");
        pill.performClick();   // back to the palette the other tests expect

        // Back: another page returns to ① first; on ① the host decides
        s.showTab(MainActivity.TAB_TRADE);
        assertTrue(s.back());
        assertFalse(s.back());

        // a result launcher registered after the host STARTED still launches through the host
        s.startHandover(TradingGate.ATOMIX, "AtomiX", false);
        assertNotNull(Shadows.shadowOf(host.act).getNextStartedActivityForResult());

        s.pause();
        assertFalse(MainActivity.FOREGROUND);
        s.stop();
        s.destroy();
        assertTrue("the screen is gone", s.isDestroyed());
        assertFalse("the host Activity lives on", host.act.isDestroyed() || host.act.isFinishing());
        assertEquals(0, host.finishAsks);
        assertEquals(before, windowState(host.act));
    }

    /** Control: the same checks see a change when the host allows it (DexiconActivity's edge-to-edge + day/night). */
    @Test public void aHostThatAllowsWindowChangesGetsThem() throws Exception {
        TabHost host = new TabHost(true);
        String before = windowState(host.act);
        MainActivity s = new MainActivity(host);
        s.create(null); s.start(); s.resume();
        assertFalse("edge-to-edge applied", decorFits(host.act.getWindow()));
        assertNotEquals(AppCompatDelegate.MODE_NIGHT_UNSPECIFIED, host.act.getDelegate().getLocalNightMode());
        assertNotEquals(before, windowState(host.act));
        s.pause(); s.stop(); s.destroy();
    }
}
