package com.eurobuddha.dexicon;

import static org.junit.Assert.*;

import android.app.Activity;
import android.app.Application;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.view.View;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

import org.json.JSONArray;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowDialog;

/** One trading app per phone: AtomiX / PandaPools present (or the MiniHub never read) → the gate replaces every trading
 *  screen; gone → trading is back. Removing an app needs a FRESH hand-over answer, never a stored one. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class TradingGateTest {
    private Application app;

    @Before public void up() {
        app = RuntimeEnvironment.getApplication();
        Shadows.shadowOf(app).setComponentNameAndServiceForBindService(new android.content.ComponentName(app, NodeTransportService.class),
                new android.os.Messenger(new android.os.Handler(android.os.Looper.getMainLooper(), m -> true)).getBinder());
    }

    private void installAtomix(int versionCode) { installAtomix(versionCode, "0a0b0c"); }

    /** AtomiX signed with {@code signer}; Dexicon itself is signed with "0a0b0c" (the family key). */
    private void installAtomix(int versionCode, String signer) {
        try {
            PackageInfo self = app.getPackageManager().getPackageInfo(app.getPackageName(), 0);
            self.signatures = new android.content.pm.Signature[]{new android.content.pm.Signature("0a0b0c")};
            Shadows.shadowOf(app.getPackageManager()).installPackage(self);
        } catch (Exception e) { throw new RuntimeException(e); }
        PackageInfo pi = new PackageInfo(); pi.packageName = TradingGate.ATOMIX; pi.versionName = "0.1.86";
        pi.versionCode = versionCode; pi.setLongVersionCode(versionCode);
        pi.signatures = new android.content.pm.Signature[]{new android.content.pm.Signature(signer)};
        Shadows.shadowOf(app.getPackageManager()).installPackage(pi);
    }

    private static Intent answer(boolean ok, String pkg, int version) {
        return new Intent().putExtra("handover", ok).putExtra("app", pkg).putExtra("version", version);
    }

    @Test public void atomixInstalledBlocksTradingUntilRemoved() {
        TradingGate.record(app, new JSONArray());
        installAtomix(186);
        MainActivity a = Robolectric.buildActivity(MainActivity.class).setup().get();
        View root = a.getWindow().getDecorView();
        assertNotNull("gate shown", DoorsTabSmokeTest.find(root, "One trading app per phone"));
        assertNull("no trading tabs behind it", DoorsTabSmokeTest.find(root, "TRADE"));
        View remove = DoorsTabSmokeTest.find(root, "2 · Remove AtomiX");
        assertNotNull(remove);
        assertFalse("remove stays locked until AtomiX hands over", remove.isEnabled());

        Shadows.shadowOf(app.getPackageManager()).removePackage(TradingGate.ATOMIX);
        a.recheckGate();
        a.showTab(MainActivity.TAB_TRADE);   // ③
        root = a.getWindow().getDecorView();
        assertNull(DoorsTabSmokeTest.find(root, "One trading app per phone"));
        TextView trade = DoorsTabSmokeTest.find(root, "TRADE");
        assertNotNull(trade);
        assertTrue("trading is back, and visible", trade.isShown());
    }

    @Test public void failsClosedUntilTheMiniHubHasBeenReadOnce() throws Exception {
        assertFalse(TradingGate.miniHubChecked(app));
        assertTrue("never read: blocked", TradingGate.blocked(app));
        assertFalse("but nothing was FOUND, so nothing is withdrawn", TradingGate.conflict(app));
        MainActivity a = Robolectric.buildActivity(MainActivity.class).setup().get();
        View root = a.getWindow().getDecorView();
        assertNotNull("a short checking state, not the trading chrome", DoorsTabSmokeTest.find(root, "Checking your node…"));
        assertNull(DoorsTabSmokeTest.find(root, "TRADE"));

        TradingGate.record(app, null);
        assertTrue("a reply without a list proves nothing", TradingGate.blocked(app));
        TradingGate.record(app, new JSONArray("[{\"uid\":\"0xCC\",\"conf\":{\"name\":\"Wallet\"}}]"));
        assertFalse(TradingGate.blocked(app));
        a.recheckGate();
        assertNotNull(DoorsTabSmokeTest.find(a.getWindow().getDecorView(), "TRADE"));

        TradingGate.record(app, new JSONArray("[{\"uid\":\"0xAA\",\"conf\":{\"name\":\"AtomiX\"}}]"));
        assertTrue("the MiniHub list alone blocks", TradingGate.blocked(app));
        assertTrue(TradingGate.conflict(app));
        a.recheckGate();
        root = a.getWindow().getDecorView();
        assertNotNull(DoorsTabSmokeTest.find(root, "AtomiX MiniDapp on your node"));
        assertNull(DoorsTabSmokeTest.find(root, "TRADE"));
    }

    @Test public void miniHubMatchesOnlyTheTwoAppsByExactName() throws Exception {
        JSONArray list = new JSONArray("[{\"uid\":\"0xAA\",\"conf\":{\"name\":\"AtomiX\"}},{\"uid\":\"0xBB\",\"conf\":{\"name\":\"pandapools\"}},"
                + "{\"uid\":\"0xCC\",\"conf\":{\"name\":\"AtomiX Explorer\"}},{\"uid\":\"0xDD\"}]");
        assertEquals("AtomiX|0xAA\nPandaPools|0xBB\n", TradingGate.match(list));
    }

    private MainActivity gated() {
        TradingGate.record(app, new JSONArray());
        installAtomix(186);
        return Robolectric.buildActivity(MainActivity.class).setup().get();
    }

    /** The next started activity, skipping the notification-permission prompt onCreate asks for. */
    private static Intent next(Activity a) {
        Intent i;
        do i = Shadows.shadowOf(a).getNextStartedActivity(); while (i != null && "android.content.pm.action.REQUEST_PERMISSIONS".equals(i.getAction()));
        return i;
    }

    @Test public void removeAsksAFreshHandOverThenUninstallsOnAValidYes() {
        MainActivity a = gated();
        a.startHandover(TradingGate.ATOMIX, "AtomiX", false);
        assertEquals(TradingGate.ACTION_HANDOVER, next(a).getAction());
        a.onHandover(Activity.RESULT_OK, answer(true, TradingGate.ATOMIX, 186));
        assertNull("step 1 alone never uninstalls", next(a));
        assertTrue(TradingGate.lastAnswerOk(app, TradingGate.ATOMIX));

        View rm = DoorsTabSmokeTest.find(a.getWindow().getDecorView(), "2 · Remove AtomiX");
        assertTrue(rm.isEnabled());
        rm.performClick();
        Intent asked = next(a);
        assertEquals("Remove asks again: no stored OK", TradingGate.ACTION_HANDOVER, asked.getAction());
        assertEquals(TradingGate.ATOMIX, asked.getPackage());
        a.onHandover(Activity.RESULT_OK, answer(true, TradingGate.ATOMIX, 186));
        Intent del = next(a);
        assertEquals(Intent.ACTION_DELETE, del.getAction());
        assertEquals("package:" + TradingGate.ATOMIX, del.getDataString());
    }

    @Test public void doubleTapOpensTheHandOverOnce() {
        MainActivity a = gated();
        a.startHandover(TradingGate.ATOMIX, "AtomiX", true);
        a.startHandover(TradingGate.ATOMIX, "AtomiX", true);
        assertNotNull(next(a));
        assertNull(next(a));
    }

    @Test public void wrongAppWrongVersionOrCancelNeverUninstalls() {
        MainActivity a = gated();
        a.startHandover(TradingGate.ATOMIX, "AtomiX", true); next(a);
        a.onHandover(Activity.RESULT_OK, answer(true, TradingGate.PANDAPOOLS, 186));
        assertNull("another app's yes", next(a));
        assertFalse(TradingGate.lastAnswerOk(app, TradingGate.ATOMIX));

        a.startHandover(TradingGate.ATOMIX, "AtomiX", true); next(a);
        a.onHandover(Activity.RESULT_OK, answer(true, TradingGate.ATOMIX, 185));
        assertNull("a yes from another version", next(a));

        a.startHandover(TradingGate.ATOMIX, "AtomiX", true); next(a);
        a.onHandover(Activity.RESULT_CANCELED, new Intent().putExtra("reason", "2 swaps are still in flight"));
        assertNull(next(a));
        AlertDialog d = (AlertDialog) ShadowDialog.getLatestDialog();
        assertTrue(d.isShowing());
        assertEquals("2 swaps are still in flight", ((TextView) d.findViewById(android.R.id.message)).getText().toString());
    }

    @Test public void aLostPendingAppFallsBackToTheAnswerButNeverUninstalls() {
        MainActivity a = gated();
        assertNull(a.handoverPkg);
        a.onHandover(Activity.RESULT_OK, answer(true, "com.evil.app", 186));
        assertNull("only AtomiX / PandaPools may be named", next(a));
        a.onHandover(Activity.RESULT_OK, answer(true, TradingGate.ATOMIX, 186));
        assertNull("process death lost the Remove intent: no uninstall", next(a));
        assertTrue("but the answer is shown on the card", TradingGate.lastAnswerOk(app, TradingGate.ATOMIX));
    }

    /** L (0.1.76): a valid-looking yes from an app of the right name and version but ANOTHER signer is never believed. */
    @Test public void aYesFromAnotherSignerIsIgnored() {
        TradingGate.record(app, new JSONArray());
        installAtomix(186, "0d0e0f");
        MainActivity a = Robolectric.buildActivity(MainActivity.class).setup().get();
        a.startHandover(TradingGate.ATOMIX, "AtomiX", true); next(a);
        a.onHandover(Activity.RESULT_OK, answer(true, TradingGate.ATOMIX, 186));
        assertNull("no uninstall", next(a));
        assertFalse(TradingGate.lastAnswerOk(app, TradingGate.ATOMIX));
    }

    /** E (0.1.75): MakerService's change broadcast applies the gate at once — no wait for a MiniHub read. */
    @Test public void theActivityFollowsAGateChangeTheServiceBroadcasts() {
        MainActivity a = DoorsTabSmokeTest.launch();
        assertNull(PoolsRoomSmokeTest.containing(a.getWindow().getDecorView(), "One trading app per phone"));
        PackageInfo pi = new PackageInfo(); pi.packageName = TradingGate.ATOMIX;
        Shadows.shadowOf(app.getPackageManager()).installPackage(pi);
        a.sendBroadcast(new Intent(com.eurobuddha.dexicon.maker.MakerService.ACTION_CHANGED).setPackage(a.getPackageName()));
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        assertNotNull(PoolsRoomSmokeTest.containing(a.getWindow().getDecorView(), "One trading app per phone"));
        assertNotNull("the gate says what was withdrawn", PoolsRoomSmokeTest.containing(a.getWindow().getDecorView(), "were withdrawn, so no one can hit them"));
        Shadows.shadowOf(app.getPackageManager()).removePackage(TradingGate.ATOMIX);
    }
}
