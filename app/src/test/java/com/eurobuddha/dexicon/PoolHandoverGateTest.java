package com.eurobuddha.dexicon;

import static org.junit.Assert.*;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.view.View;

import com.eurobuddha.pandapools.DexiconPools;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;

import java.util.Arrays;
import java.util.List;

/**
 * Moving a live pool from standalone PandaPools: restore → AWAITING (not kept) → "Hand over" names the pools Dexicon holds
 * → PandaPools' OK with "pools" → Dexicon keeps exactly those → a refusal whose "kept_by_dexicon" leaves one out → AWAITING again.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class PoolHandoverGateTest {
    private static final String A = "0x" + "a1".repeat(32), B = "0x" + "b2".repeat(32);
    private android.app.Application app;

    @Before public void setUp() {
        app = RuntimeEnvironment.getApplication();
        Shadows.shadowOf(app).setComponentNameAndServiceForBindService(new android.content.ComponentName(app, NodeTransportService.class),
                new android.os.Messenger(new android.os.Handler(android.os.Looper.getMainLooper(), m -> true)).getBinder());
        PackageInfo pi = new PackageInfo(); pi.packageName = TradingGate.PANDAPOOLS; pi.setLongVersionCode(980);
        Shadows.shadowOf(app.getPackageManager()).installPackage(pi);
    }

    private static Intent answer(boolean ok) {
        Intent d = new Intent().putExtra("app", TradingGate.PANDAPOOLS);
        if (ok) d.putExtra("handover", true).putExtra("version", 980);
        return d;
    }

    @Test public void restoreWaitsThenHandOverMovesExactlyTheNamedPools() {
        MainActivity a = DoorsTabSmokeTest.launch();
        View root = a.getWindow().getDecorView();
        assertNotNull("the gate offers the restore", DoorsTabSmokeTest.find(root, "1 · Restore your PandaPools backup here"));
        assertNotNull(DoorsTabSmokeTest.find(root, "2 · Hand over in PandaPools"));

        // what a restore leaves behind: every recipe AWAITING, so Dexicon's keeper leaves it to PandaPools
        assertTrue(DexiconPools.markAwaiting(a, Arrays.asList(A, B)));
        assertTrue(DexiconPools.keptElsewhere(a, A) && DexiconPools.keptElsewhere(a, B));
        a.recheckGate();
        assertNotNull("listed in full on the gate", PoolsRoomSmokeTest.containing(root, A));

        a.startHandover(TradingGate.PANDAPOOLS, "PandaPools", false);
        Intent asked = Shadows.shadowOf(a).getNextStartedActivityForResult().intent;
        assertEquals(TradingGate.ACTION_HANDOVER, asked.getAction());
        assertNotNull("Dexicon names the pools it holds", asked.getStringArrayExtra(DexiconPools.EXTRA_DEXICON_POOLS));

        a.onHandover(Activity.RESULT_OK, answer(true).putExtra(DexiconPools.EXTRA_POOLS, new String[]{A}));
        assertFalse("handed over: Dexicon keeps A now", DexiconPools.keptElsewhere(a, A));
        assertTrue("B was not named: still PandaPools'", DexiconPools.keptElsewhere(a, B));

        a.onHandover(Activity.RESULT_CANCELED, answer(false).putExtra("reason", "A swap is still in progress."));
        assertFalse("a refusal without kept_by_dexicon changes nothing", DexiconPools.keptElsewhere(a, A));

        a.onHandover(Activity.RESULT_CANCELED, answer(false).putExtra(DexiconPools.EXTRA_KEPT, new String[]{A}));
        assertFalse("PandaPools still leaves A alone", DexiconPools.keptElsewhere(a, A));

        a.onHandover(Activity.RESULT_CANCELED, answer(false).putExtra(DexiconPools.EXTRA_KEPT, new String[0]));
        assertTrue("taken back: A waits again", DexiconPools.keptElsewhere(a, A));
    }

    @Test public void anOkForAnotherVersionHandsNothingOver() {
        MainActivity a = DoorsTabSmokeTest.launch();
        DexiconPools.markAwaiting(a, List.of(A));
        a.onHandover(Activity.RESULT_OK, new Intent().putExtra("app", TradingGate.PANDAPOOLS).putExtra("handover", true).putExtra("version", 1)
                .putExtra(DexiconPools.EXTRA_POOLS, new String[]{A}));
        assertTrue(DexiconPools.keptElsewhere(a, A));
    }

    /** A restore marks every recipe AWAITING before PandaPools' restore records any of them (legacy JSON backup path). */
    @Test public void restoreMarksEveryRecipeAwaitingFirst() throws Exception {
        MainActivity a = DoorsTabSmokeTest.launch();
        java.io.File f = java.io.File.createTempFile("ppbackup", ".json");
        java.nio.file.Files.write(f.toPath(), new org.json.JSONObject().put("pandapools_backup", 1).put("pools", new org.json.JSONArray()
                .put(new org.json.JSONObject().put("address", A)).put(new org.json.JSONObject().put("address", B))).toString().getBytes());
        final String[] last = {""};
        DexiconPools.restoreBackup(a, android.net.Uri.fromFile(f), line -> last[0] = line, (r, t, addrs, report) -> last[0] = "done");
        for (int i = 0; i < 50 && !(last[0].startsWith("Restoring") || last[0].equals("done")); i++) {
            Thread.sleep(50);
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        }
        assertTrue(last[0], last[0].startsWith("Restoring") || last[0].equals("done"));
        assertTrue(DexiconPools.keptElsewhere(a, A));
        assertTrue(DexiconPools.keptElsewhere(a, B));
    }
}
