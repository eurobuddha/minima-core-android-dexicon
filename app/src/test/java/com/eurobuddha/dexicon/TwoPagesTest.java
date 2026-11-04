package com.eurobuddha.dexicon;

import static org.junit.Assert.*;

import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/** Wide screen: one tap = one page; a second finger on another tab while one is held = both side by side; folding drops the second. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, qualifiers = "w933dp-h704dp")
public class TwoPagesTest {
    static void touch(View v, int action) {
        long t = SystemClock.uptimeMillis();
        MotionEvent e = MotionEvent.obtain(t, t, action, 5, 5, 0);
        v.dispatchTouchEvent(e); e.recycle();
    }

    /** A page's own text — never the bottom-bar label of the same name (a tab cell carries its name as contentDescription). */
    static View page(View v, String text) {
        if (v instanceof android.widget.TextView && text.equals(((android.widget.TextView) v).getText().toString())
                && !(v.getParent() instanceof View && text.equals(String.valueOf(((View) v.getParent()).getContentDescription())))) return v;
        if (v instanceof android.view.ViewGroup) for (int i = 0; i < ((android.view.ViewGroup) v).getChildCount(); i++) { View t = page(((android.view.ViewGroup) v).getChildAt(i), text); if (t != null) return t; }
        return null;
    }

    @Test public void oneTapOnePageTwoFingersTwoPages() {
        android.app.Application app = RuntimeEnvironment.getApplication();
        org.robolectric.Shadows.shadowOf(app).setComponentNameAndServiceForBindService(new android.content.ComponentName(app, NodeTransportService.class),
                new android.os.Messenger(new android.os.Handler(android.os.Looper.getMainLooper(), m -> true)).getBinder());
        MainActivity a = DoorsTabSmokeTest.launch();
        View root = a.getWindow().getDecorView();
        DoorsTabSmokeTest.find(root, DoorsTabSmokeTest.room(2)).performClick();   // ③ EXCHANGE: its pages show in the row under the rail
        View activity = DoorsTabSmokeTest.tab(root, "Activity"), history = DoorsTabSmokeTest.tab(root, "History"), assets = DoorsTabSmokeTest.tab(root, "Assets");

        touch(activity, MotionEvent.ACTION_DOWN);           // hold ACTIVITY…
        touch(history, MotionEvent.ACTION_DOWN);            // …and put a second finger on HISTORY
        touch(history, MotionEvent.ACTION_UP);
        touch(activity, MotionEvent.ACTION_UP);
        assertTrue("left page", page(root, "Activity").isShown());
        assertTrue("right page", page(root, "History").isShown());

        touch(assets, MotionEvent.ACTION_DOWN); assets.performClick(); touch(assets, MotionEvent.ACTION_CANCEL);   // a fresh single tap: one page alone
        assertFalse(page(root, "Activity") != null && page(root, "Activity").isShown());
        assertFalse(page(root, "History") != null && page(root, "History").isShown());

        a.showPages(MainActivity.TAB_ACTIVITY, MainActivity.TAB_HISTORY);
        RuntimeEnvironment.setQualifiers("w411dp-h800dp");   // fold
        a.onConfigurationChanged(a.getResources().getConfiguration());
        assertTrue(page(root, "Activity").isShown());
        assertNull("folded: the second page is closed", page(root, "History"));
    }

    @Test public void aRoomButtonPairsAsItsPage() {
        android.app.Application app = RuntimeEnvironment.getApplication();
        org.robolectric.Shadows.shadowOf(app).setComponentNameAndServiceForBindService(new android.content.ComponentName(app, NodeTransportService.class),
                new android.os.Messenger(new android.os.Handler(android.os.Looper.getMainLooper(), m -> true)).getBinder());
        MainActivity a = DoorsTabSmokeTest.launch();
        View root = a.getWindow().getDecorView();
        View pools = DoorsTabSmokeTest.find(root, DoorsTabSmokeTest.room(1)), activity = DoorsTabSmokeTest.tab(root, "Activity");
        touch(pools, MotionEvent.ACTION_DOWN);              // hold ② (a one-page room: no button of its own in the row)…
        touch(activity, MotionEvent.ACTION_DOWN);           // …and put a second finger on ACTIVITY
        touch(activity, MotionEvent.ACTION_UP);
        touch(pools, MotionEvent.ACTION_UP);
        View poolsPage = DoorsTabSmokeTest.find(root, "WHAT A POOL IS  ▾");
        if (poolsPage == null) poolsPage = DoorsTabSmokeTest.find(root, "WHAT A POOL IS  ▸");
        assertTrue("left: ② POOLS", poolsPage.isShown());
        assertTrue("right: ACTIVITY", page(root, "Activity").isShown());
    }
}
