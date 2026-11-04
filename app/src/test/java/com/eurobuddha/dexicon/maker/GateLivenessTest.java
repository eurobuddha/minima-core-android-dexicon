package com.eurobuddha.dexicon.maker;

import static org.junit.Assert.*;

import android.app.Application;
import android.content.pm.PackageInfo;

import com.eurobuddha.dexicon.DexDb;
import com.eurobuddha.dexicon.TradingGate;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.List;

/** E (0.1.75): the gate is re-checked at the last moment — per take in the maker — and the Activity follows a change the
 *  service broadcasts, without waiting for the MiniHub read (TradingGateTest). */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class GateLivenessTest {

    /** Records replies instead of sending them. */
    public static class Spy extends MakerService {
        final List<JSONObject> replies = new ArrayList<>();
        @Override void reply(String to, JSONObject obj) { replies.add(obj); }
    }

    @Test public void aTakeArrivingAfterTheGateShutIsRefusedPerTake() throws Exception {
        Application app = RuntimeEnvironment.getApplication();
        TradingGate.record(app, new org.json.JSONArray());
        Spy s = Robolectric.buildService(Spy.class).get();
        s.db = new DexDb(s);
        MyOrder o = new MyOrder(); o.orderId = "0x00000000000000e1"; o.side = "sell"; o.price = "0.0035"; o.amount = "100"; o.remaining = "100";
        o.address = "0x" + "c3".repeat(32); o.seq = 5; o.expiry = MakerEngine.now() + 3600; o.coins.add("0x" + "08".repeat(32));
        s.db.saveOrder(o);
        PackageInfo pi = new PackageInfo(); pi.packageName = TradingGate.ATOMIX;
        Shadows.shadowOf(app.getPackageManager()).installPackage(pi);   // shut between the batch check and this take
        final boolean[] next = {false};
        s.handleTake(new JSONObject().put("_from", "0x" + "44".repeat(32)).put("orderId", o.orderId).put("txn", "0xabcd"), () -> next[0] = true);
        assertTrue(next[0]);
        assertEquals(1, s.replies.size());
        assertEquals(MakerService.PAUSED, s.replies.get(0).optString("reason"));
        Shadows.shadowOf(app.getPackageManager()).removePackage(TradingGate.ATOMIX);
    }

    /** J (0.1.76): a stranding notification's Re-publish reaching a service that is not paired yet is kept, not dropped,
     *  and the first paired pass runs (and forgets) it. */
    @Test public void aRepublishBeforePairingIsKeptForTheFirstPairedPass() {
        MakerService s = Robolectric.buildService(MakerService.class).get();
        String pool = "0x" + "f1".repeat(32);
        s.queueRepublish(pool);
        android.content.SharedPreferences p = s.getSharedPreferences(MakerService.PREFS_REPUBLISH, android.content.Context.MODE_PRIVATE);
        assertTrue("persisted in full", p.getStringSet("pending", new java.util.HashSet<>()).contains(pool));
        com.eurobuddha.pandapools.DexiconPools.custodyExecutorForTest(Runnable::run);
        s.runPendingRepublish();   // no recipe for it here: DexiconPools.republish signs nothing
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();   // custody is read first, then the queue drains
        assertFalse(p.contains("pending"));
    }

    /** 9 (0.1.79): a queued Re-publish a guard holds back (PandaPools keeps the pool) stays queued until it can run. */
    @Test public void aRepublishHeldBackByAGuardStaysQueued() {
        Application app = RuntimeEnvironment.getApplication();
        com.eurobuddha.dexicon.CustodyFake.install(app, com.eurobuddha.dexicon.CustodyFake.Install.OLD, 980);   // unreadable: kept elsewhere
        com.eurobuddha.pandapools.Pool pool = new com.eurobuddha.pandapools.Pool();
        pool.address = "0x" + "f2".repeat(32); pool.opk = "0x" + "b9".repeat(32); pool.oadr = "0x" + "c9".repeat(32);
        pool.tok = "0x" + "d9".repeat(32); pool.kmin = "100"; pool.covenantScript = "RETURN TRUE";
        assertTrue(com.eurobuddha.pandapools.OwnPoolStore.recordDurably(app, pool));
        MakerService s = Robolectric.buildService(MakerService.class).get();
        String noRecipe = "0x" + "f3".repeat(32);
        s.queueRepublish(pool.address);
        s.queueRepublish(noRecipe);   // forgotten only if the drain actually ran
        s.runPendingRepublish();
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        android.content.SharedPreferences p = s.getSharedPreferences(MakerService.PREFS_REPUBLISH, android.content.Context.MODE_PRIVATE);
        java.util.Set<String> left = p.getStringSet("pending", new java.util.HashSet<>());
        assertFalse("the drain ran", left.contains(noRecipe));
        assertTrue("not run, so not forgotten", left.contains(pool.address));
        com.eurobuddha.dexicon.CustodyFake.install(app, com.eurobuddha.dexicon.CustodyFake.Install.NONE, 0);
    }

    /** 0.1.83: a held-back Re-publish does not query PandaPools' provider every 10 s tick — a fresh read is reused, an
     *  older one re-read at most once a minute; a new tap re-reads at once. */
    @Test public void aHeldBackRepublishDoesNotQueryTheProviderEveryTick() {
        Application app = RuntimeEnvironment.getApplication();
        com.eurobuddha.pandapools.Pool pool = new com.eurobuddha.pandapools.Pool();
        pool.address = "0x" + "f4".repeat(32); pool.opk = "0x" + "b9".repeat(32); pool.oadr = "0x" + "c9".repeat(32);
        pool.tok = "0x" + "d9".repeat(32); pool.kmin = "100"; pool.covenantScript = "RETURN TRUE";
        com.eurobuddha.dexicon.CustodyFake.rows = new String[][]{{pool.address, com.eurobuddha.pandapools.DexiconPools.KEPT_HERE}};
        com.eurobuddha.dexicon.CustodyFake.install(app, com.eurobuddha.dexicon.CustodyFake.Install.NEW, 982);
        try {
            assertTrue(com.eurobuddha.pandapools.OwnPoolStore.recordDurably(app, pool));
            MakerService s = Robolectric.buildService(MakerService.class).get();
            s.queueRepublish(pool.address);
            int q0 = com.eurobuddha.dexicon.CustodyFake.queries.get();
            s.runPendingRepublish();
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            assertEquals("a fresh read is reused", q0, com.eurobuddha.dexicon.CustodyFake.queries.get());
            com.eurobuddha.pandapools.DexiconPools.ageCustody(6_000);
            s.runPendingRepublish();
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            assertEquals("an older one is re-read once", q0 + 1, com.eurobuddha.dexicon.CustodyFake.queries.get());
            for (int i = 0; i < 5; i++) {
                com.eurobuddha.pandapools.DexiconPools.ageCustody(6_000);
                s.runPendingRepublish();
                org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            }
            assertEquals("not every tick", q0 + 1, com.eurobuddha.dexicon.CustodyFake.queries.get());
            assertTrue("still queued", s.getSharedPreferences(MakerService.PREFS_REPUBLISH, android.content.Context.MODE_PRIVATE)
                    .getStringSet("pending", new java.util.HashSet<>()).contains(pool.address));
            s.queueRepublish(pool.address);
            s.runPendingRepublish();
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            assertEquals("a new tap re-reads at once", q0 + 2, com.eurobuddha.dexicon.CustodyFake.queries.get());
        } finally {
            com.eurobuddha.dexicon.CustodyFake.rows = new String[0][];
            com.eurobuddha.dexicon.CustodyFake.install(app, com.eurobuddha.dexicon.CustodyFake.Install.NONE, 0);
        }
    }
}
