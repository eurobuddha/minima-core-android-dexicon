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

    @Test public void oneTapOnePageTwoFingersTwoPages() {
        android.app.Application app = RuntimeEnvironment.getApplication();
        org.robolectric.Shadows.shadowOf(app).setComponentNameAndServiceForBindService(new android.content.ComponentName(app, NodeTransportService.class),
                new android.os.Messenger(new android.os.Handler(android.os.Looper.getMainLooper(), m -> true)).getBinder());
        MainActivity a = Robolectric.buildActivity(MainActivity.class).setup().get();
        View root = a.getWindow().getDecorView();
        View activity = DoorsTabSmokeTest.find(root, "ACTIVITY"), history = DoorsTabSmokeTest.find(root, "HISTORY"), assets = DoorsTabSmokeTest.find(root, "ASSETS");

        touch(activity, MotionEvent.ACTION_DOWN);           // hold ACTIVITY…
        touch(history, MotionEvent.ACTION_DOWN);            // …and put a second finger on HISTORY
        touch(history, MotionEvent.ACTION_UP);
        touch(activity, MotionEvent.ACTION_UP);
        assertTrue("left page", DoorsTabSmokeTest.find(root, "Activity").isShown());
        assertTrue("right page", DoorsTabSmokeTest.find(root, "History").isShown());

        touch(assets, MotionEvent.ACTION_DOWN); assets.performClick(); touch(assets, MotionEvent.ACTION_CANCEL);   // a fresh single tap: one page alone
        assertFalse(DoorsTabSmokeTest.find(root, "Activity") != null && DoorsTabSmokeTest.find(root, "Activity").isShown());
        assertFalse(DoorsTabSmokeTest.find(root, "History") != null && DoorsTabSmokeTest.find(root, "History").isShown());

        a.showPages(MainActivity.TAB_ACTIVITY, MainActivity.TAB_HISTORY);
        RuntimeEnvironment.setQualifiers("w411dp-h800dp");   // fold
        a.onConfigurationChanged(a.getResources().getConfiguration());
        assertTrue(DoorsTabSmokeTest.find(root, "Activity").isShown());
        assertNull("folded: the second page is closed", DoorsTabSmokeTest.find(root, "History"));
    }
}
