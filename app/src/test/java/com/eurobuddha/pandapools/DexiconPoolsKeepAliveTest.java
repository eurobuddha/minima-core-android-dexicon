package com.eurobuddha.pandapools;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import android.content.Context;
import android.content.pm.PackageInfo;

import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * DEXICON-OWNED: ② Pools under Dexicon's rules.
 *  - The trading gate shut by a CONFLICT (standalone PandaPools found): no new pool, no swap — the keep-alive still runs.
 *  - Moving a live pool: restored pools wait (AWAITING) while PandaPools keeps them; PandaPools' OK hands exactly the named
 *    pools over; a refusal moves a pool back only when its "kept_by_dexicon" list leaves that pool out.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class DexiconPoolsKeepAliveTest {
    private Context ctx;
    private com.eurobuddha.dexicon.NodeApi dex;
    private final List<String> sent = new ArrayList<>();
    private static final String A = "0x" + String.join("", Collections.nCopies(64, "A")), B = "0x" + String.join("", Collections.nCopies(64, "B"));

    @Before public void setUp() {
        ctx = RuntimeEnvironment.getApplication();
        ctx.getSharedPreferences("dexicon_pools_handover", Context.MODE_PRIVATE).edit().clear().commit();
        com.eurobuddha.dexicon.TradingGate.record(ctx, new org.json.JSONArray());   // MiniHub read once: no MiniDapp
        DexiconPools.resetPassClock();
        dex = mock(com.eurobuddha.dexicon.NodeApi.class);
        when(dex.context()).thenReturn(ctx);
        doAnswer(inv -> {
            BooleanSupplier guard = inv.getArgument(1);
            if (guard.getAsBoolean()) sent.add(inv.getArgument(0));
            return null;   // the node never answers in this test
        }).when(dex).cmd(anyString(), any(BooleanSupplier.class), anyLong(), any(com.eurobuddha.dexicon.NodeApi.Cb.class));
    }

    private void installPandaPools(boolean on) {
        if (on) {
            PackageInfo pi = new PackageInfo(); pi.packageName = com.eurobuddha.dexicon.TradingGate.PANDAPOOLS;
            Shadows.shadowOf(ctx.getPackageManager()).installPackage(pi);
        } else Shadows.shadowOf(ctx.getPackageManager()).removePackage(com.eurobuddha.dexicon.TradingGate.PANDAPOOLS);
    }

    private static Pool pool(String address) {
        Pool p = new Pool();
        p.address = address;
        p.opk = "0x" + String.join("", Collections.nCopies(64, "b"));
        p.oadr = "0x" + String.join("", Collections.nCopies(64, "c"));
        p.tok = "0x" + String.join("", Collections.nCopies(64, "d"));
        p.kmin = "100";
        p.covenantScript = "RETURN TRUE";
        return p;
    }

    @Test public void aConflictStopsCreateAndSwapButNotTheKeepAlive() {
        installPandaPools(true);
        assertTrue(com.eurobuddha.dexicon.TradingGate.conflict(ctx));
        NodeApi n = new NodeApi(dex);
        final String[] failed = new String[1];
        new PoolManager(n).createPool(B, 8, BigDecimal.ONE, BigDecimal.ONE, new PoolManager.CreateResult() {
            public void onCreated(Pool pool, String txpowid) { fail("created under the gate"); }
            public void onFailed(String message) { failed[0] = message; }
        });
        assertEquals(Handover.BLOCKED, failed[0]);
        PoolRouter.Route route = new PoolRouter.Route();
        route.ok = true;
        PoolRouter.Alloc alloc = new PoolRouter.Alloc(pool(A), null);
        route.allocs.add(alloc);
        new PoolTxn(n).swap(route, true, new PoolTxn.Result() {
            public void onPosted(String txpowid) { fail("swapped under the gate"); }
            public void onFailed(String message) { failed[0] = "swap: " + message; }
        });
        assertEquals("swap: " + Handover.BLOCKED, failed[0]);
        assertTrue("nothing reached the node", sent.isEmpty());

        assertTrue(OwnPoolStore.recordDurably(ctx, pool(A)));
        assertTrue("the keep-alive is settlement: it runs under the gate", DexiconPools.keepAlivePass(ctx, dex));
        assertEquals("block", sent.get(0));
        assertFalse("at most once per 5 min", DexiconPools.keepAlivePass(ctx, dex));
    }

    @Test public void restoredPoolsWaitUntilPandaPoolsHandsThemOver() {
        installPandaPools(true);
        assertTrue(DexiconPools.markAwaiting(ctx, Arrays.asList(A, B)));
        assertTrue("PandaPools still keeps it", DexiconPools.keptElsewhere(ctx, A.toLowerCase()));
        assertTrue(Handover.keepable(ctx, Arrays.asList(pool(A), pool(B))).isEmpty());

        // Dexicon's keeper refuses to refresh or re-announce a pool another app keeps (vendored PoolManager guard).
        final String[] why = new String[1];
        new PoolManager(new NodeApi(dex)).reannounce(pool(A), new PoolManager.Result() {
            public void onPosted(String txpowid) { fail(); }
            public void onFailed(String message) { why[0] = message; }
        });
        assertEquals(PoolManager.KEPT_BY_DEXICON, why[0]);

        assertTrue(DexiconPools.onHandoverOk(ctx, new String[]{A}));
        assertFalse("handed over: Dexicon keeps it", DexiconPools.keptElsewhere(ctx, A));
        assertTrue("not named: still awaiting", DexiconPools.keptElsewhere(ctx, B));

        assertTrue("a refusal without the list changes nothing", DexiconPools.onHandoverRefused(ctx, null).isEmpty());
        assertTrue("PandaPools still leaves A alone", DexiconPools.onHandoverRefused(ctx, new String[]{A.toLowerCase()}).isEmpty());
        assertFalse(DexiconPools.keptElsewhere(ctx, A));

        assertEquals("taken back: A is absent from kept_by_dexicon", Collections.singletonList(A.toLowerCase()), DexiconPools.onHandoverRefused(ctx, new String[0]));
        assertTrue("A waits again", DexiconPools.keptElsewhere(ctx, A));

        installPandaPools(false);
        assertFalse("PandaPools is gone: nothing else can keep it, so Dexicon does", DexiconPools.keptElsewhere(ctx, A));
    }

    @Test public void aPoolCreatedInDexiconIsNeverMovedByARefusal() {
        installPandaPools(true);
        DexiconPools.onHandoverRefused(ctx, new String[0]);
        assertFalse(DexiconPools.keptElsewhere(ctx, A));
    }

    @Test public void heldAddressesNameEveryRecipeInFull() {
        OwnPoolStore.recordDurably(ctx, pool(A));
        assertTrue(Arrays.asList(DexiconPools.heldAddresses(ctx)).contains(A.toLowerCase()));
    }

    @Test public void recipeAddressesReadABackup() throws Exception {
        String json = new JSONObject().put("pandapools_backup", 1).put("pools", new org.json.JSONArray()
                .put(new JSONObject().put("address", A)).put(new JSONObject().put("address", B))).toString();
        assertEquals(Arrays.asList(A.toLowerCase(), B.toLowerCase()), DexiconPools.recipeAddresses(json));
        assertTrue(DexiconPools.recipeAddresses("not json").isEmpty());
    }
}
