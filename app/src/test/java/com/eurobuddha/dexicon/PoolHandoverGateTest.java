package com.eurobuddha.dexicon;

import static org.junit.Assert.*;

import android.app.Activity;
import android.content.Intent;
import android.view.View;

import com.eurobuddha.pandapools.DexiconPools;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;

/**
 * Moving a live pool from standalone PandaPools (0.1.74): custody comes ONLY from PandaPools' provider. The hand-over
 * intent names the pools Dexicon holds and answers OK / refused — permission to uninstall — but never moves custody.
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
        CustodyFake.rows = new String[][]{{A, DexiconPools.KEPT_HERE}, {B, DexiconPools.KEPT_HERE}};
        CustodyFake.install(app, CustodyFake.Install.NEW, 981);
    }
    @After public void down() { CustodyFake.rows = new String[0][]; }

    private static Intent answer(boolean ok) {
        Intent d = new Intent().putExtra("app", TradingGate.PANDAPOOLS);
        if (ok) d.putExtra("handover", true).putExtra("version", 981);
        return d;
    }

    @Test public void custodyFollowsTheProviderNotTheAnswer() {
        MainActivity a = DoorsTabSmokeTest.launch();
        View root = a.getWindow().getDecorView();
        assertNotNull("the gate offers the restore", DoorsTabSmokeTest.find(root, "1 · Restore your PandaPools backup here"));
        assertNotNull(DoorsTabSmokeTest.find(root, "2 · Hand over in PandaPools"));
        assertTrue(DexiconPools.keptElsewhere(a, A) && DexiconPools.keptElsewhere(a, B));

        a.startHandover(TradingGate.PANDAPOOLS, "PandaPools", false);
        Intent asked = Shadows.shadowOf(a).getNextStartedActivityForResult().intent;
        assertEquals(TradingGate.ACTION_HANDOVER, asked.getAction());
        assertNotNull("Dexicon names the pools it holds", asked.getStringArrayExtra(DexiconPools.EXTRA_DEXICON_POOLS));

        // an OK naming A, while PandaPools' own signal still says it keeps A: Dexicon still leaves A alone
        a.onHandover(Activity.RESULT_OK, answer(true).putExtra(DexiconPools.EXTRA_POOLS, new String[]{A}));
        assertTrue("the answer is not custody", DexiconPools.keptElsewhere(a, A));

        CustodyFake.rows = new String[][]{{A, "handed_over"}, {B, DexiconPools.KEPT_HERE}};
        a.onHandover(Activity.RESULT_OK, answer(true).putExtra(DexiconPools.EXTRA_POOLS, new String[]{A}));
        assertFalse("handed_over: Dexicon keeps A", DexiconPools.keptElsewhere(a, A));
        assertTrue(DexiconPools.keptElsewhere(a, B));

        // a take-back: PandaPools keeps A again; a refusal re-reads it at once
        CustodyFake.rows = new String[][]{{A, DexiconPools.KEPT_HERE}};
        a.onHandover(Activity.RESULT_CANCELED, answer(false).putExtra(DexiconPools.EXTRA_KEPT, new String[0]));
        assertTrue(DexiconPools.keptElsewhere(a, A));
        assertFalse("not in PandaPools' list: Dexicon's", DexiconPools.keptElsewhere(a, B));
    }

    @Test public void anUnreadableProviderSignsNothingAndTheGateSaysUpdate() {
        CustodyFake.install(app, CustodyFake.Install.OLD, 980);
        MainActivity a = DoorsTabSmokeTest.launch();
        assertTrue(DexiconPools.custodyUnreadable(a));
        assertTrue("an older PandaPools: every pool is treated as kept there", DexiconPools.keptElsewhere(a, "0x" + "cd".repeat(32)));
        a.recheckGate();
        assertNotNull(PoolsRoomSmokeTest.containing(a.getWindow().getDecorView(), "UPDATE PANDAPOOLS"));
    }

    @Test public void aProviderThatIsNotPandaPoolsOwnIsUnreadable() {
        CustodyFake.rows = new String[0][];
        CustodyFake.install(app, CustodyFake.Install.FOREIGN_SIGNED, 981);
        assertTrue(DexiconPools.custodyUnreadable(app));
        assertTrue(DexiconPools.keptElsewhere(app, A));
        CustodyFake.rows = null;   // PandaPools refused Dexicon (null cursor)
        CustodyFake.install(app, CustodyFake.Install.NEW, 981);
        assertTrue(DexiconPools.custodyUnreadable(app));
    }

    @Test public void reinstalledPandaPoolsWithNothingLeavesEveryPoolToDexicon() {
        CustodyFake.rows = new String[0][];
        CustodyFake.install(app, CustodyFake.Install.NEW, 981);
        assertFalse(DexiconPools.custodyUnreadable(app));
        assertFalse(DexiconPools.keptElsewhere(app, A));
        CustodyFake.install(app, CustodyFake.Install.NONE, 0);
        assertFalse("not installed: Dexicon keeps every recipe", DexiconPools.keptElsewhere(app, A));
    }

    /** The restore reads custody first and never marks anything: a pool PandaPools keeps is left to it from the start. */
    @Test public void aRestoredPoolPandaPoolsKeepsIsLeftToIt() throws Exception {
        MainActivity a = DoorsTabSmokeTest.launch();
        java.io.File f = java.io.File.createTempFile("ppbackup", ".json");
        java.nio.file.Files.write(f.toPath(), new org.json.JSONObject().put("pandapools_backup", 1).put("pools", new org.json.JSONArray()
                .put(new org.json.JSONObject().put("address", A))).toString().getBytes());
        final String[] last = {""};
        DexiconPools.restoreBackup(a, android.net.Uri.fromFile(f), line -> last[0] = line, (r, t, addrs, report) -> last[0] = "done");
        for (int i = 0; i < 50 && !(last[0].startsWith("Restoring") || last[0].equals("done")); i++) {
            Thread.sleep(50);
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        }
        assertTrue(last[0], last[0].startsWith("Restoring") || last[0].equals("done"));
        assertTrue(DexiconPools.keptElsewhere(a, A));
        assertFalse("no AWAITING set is written any more", app.getSharedPreferences("dexicon_pools_handover", 0).contains("awaiting"));
    }
}
