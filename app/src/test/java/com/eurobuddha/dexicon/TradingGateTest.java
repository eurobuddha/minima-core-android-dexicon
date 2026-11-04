package com.eurobuddha.dexicon;

import static org.junit.Assert.*;

import android.content.pm.PackageInfo;
import android.view.View;

import org.json.JSONArray;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;

/** One trading app per phone: AtomiX / PandaPools present → the gate replaces every trading screen; gone → trading is back. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class TradingGateTest {

    @Test public void atomixInstalledBlocksTradingUntilRemoved() {
        android.app.Application app = RuntimeEnvironment.getApplication();
        Shadows.shadowOf(app).setComponentNameAndServiceForBindService(new android.content.ComponentName(app, NodeTransportService.class),
                new android.os.Messenger(new android.os.Handler(android.os.Looper.getMainLooper(), m -> true)).getBinder());
        PackageInfo pi = new PackageInfo(); pi.packageName = TradingGate.ATOMIX; pi.versionName = "0.1.86";
        Shadows.shadowOf(app.getPackageManager()).installPackage(pi);

        MainActivity a = Robolectric.buildActivity(MainActivity.class).setup().get();
        View root = a.getWindow().getDecorView();
        assertNotNull("gate shown", DoorsTabSmokeTest.find(root, "One trading app per phone"));
        assertNull("no trading tabs behind it", DoorsTabSmokeTest.find(root, "TRADE"));
        View remove = DoorsTabSmokeTest.find(root, "2 · Remove AtomiX");
        assertNotNull(remove);
        assertFalse("remove stays locked until AtomiX hands over", remove.isEnabled());

        Shadows.shadowOf(app.getPackageManager()).removePackage(TradingGate.ATOMIX);
        a.recheckGate();
        root = a.getWindow().getDecorView();
        assertNull(DoorsTabSmokeTest.find(root, "One trading app per phone"));
        assertNotNull("trading is back", DoorsTabSmokeTest.find(root, "TRADE"));
    }

    @Test public void miniHubMatchesOnlyTheTwoAppsByExactName() throws Exception {
        JSONArray list = new JSONArray("[{\"uid\":\"0xAA\",\"conf\":{\"name\":\"AtomiX\"}},{\"uid\":\"0xBB\",\"conf\":{\"name\":\"pandapools\"}},"
                + "{\"uid\":\"0xCC\",\"conf\":{\"name\":\"AtomiX Explorer\"}},{\"uid\":\"0xDD\"}]");
        assertEquals("AtomiX|0xAA\nPandaPools|0xBB\n", TradingGate.match(list));
    }

    @Test public void handOverExpires() {
        long now = 1_000_000_000L;
        assertTrue(TradingGate.fresh(now - 60_000, now));
        assertFalse(TradingGate.fresh(now - TradingGate.HANDOVER_VALID_MS - 1, now));
        assertFalse(TradingGate.fresh(0, now));
        assertFalse("clock moved back", TradingGate.fresh(now + 5_000, now));
    }
}
