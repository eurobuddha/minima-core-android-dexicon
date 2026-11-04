package com.eurobuddha.pandapools;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import android.content.Context;
import android.content.pm.PackageInfo;

import com.eurobuddha.dexicon.CustodyFake;

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
 *  - Custody comes only from PandaPools' provider (0.1.74): kept_here → left alone; handed_over / absent → Dexicon's;
 *    unreadable → nobody signs from Dexicon; a take-back stops Dexicon's keeper within one pass.
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
        CustodyFake.rows = new String[0][];
        CustodyFake.install((android.app.Application) RuntimeEnvironment.getApplication(), CustodyFake.Install.NONE, 0);
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
        CustodyFake.install((android.app.Application) ctx, on ? CustodyFake.Install.NEW : CustodyFake.Install.NONE, 981);
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

    /** A recipe written by ANY path (MyLpView backfill, the ② restore menu, Recovery) while PandaPools keeps the pool:
     *  Dexicon's keeper leaves it alone — no AWAITING mark is needed, the provider says so. */
    @Test public void aBackfilledPoolPandaPoolsKeepsIsNotKeptHere() {
        CustodyFake.rows = new String[][]{{A.toLowerCase(), DexiconPools.KEPT_HERE}, {B.toLowerCase(), "handed_over"}};
        CustodyFake.install((android.app.Application) ctx, CustodyFake.Install.NEW, 981);
        assertTrue(OwnPoolStore.recordDurably(ctx, pool(A)));
        assertTrue(DexiconPools.keptElsewhere(ctx, A));
        assertFalse("handed_over: Dexicon keeps it", DexiconPools.keptElsewhere(ctx, B));
        assertEquals(1, Handover.keepable(ctx, Arrays.asList(pool(A), pool(B))).size());
        final String[] why = new String[2];
        new PoolManager(new NodeApi(dex)).reannounce(pool(A), new PoolManager.Result() {
            public void onPosted(String txpowid) { fail(); }
            public void onFailed(String message) { why[0] = message; }
        });
        new PoolManager(new NodeApi(dex)).refresh(pool(A), new PoolManager.Result() {
            public void onPosted(String txpowid) { fail(); }
            public void onFailed(String message) { why[1] = message; }
        });
        assertEquals(PoolManager.KEPT_BY_DEXICON, why[0]);
        assertEquals(PoolManager.KEPT_BY_DEXICON, why[1]);
        assertTrue("nothing reached the node", sent.isEmpty());
    }

    /** PandaPools takes a pool back: the very next keep-alive pass re-reads custody before it refreshes anything. */
    @Test public void aTakeBackFlipsWithinOnePass() {
        CustodyFake.rows = new String[][]{{A.toLowerCase(), "handed_over"}};
        CustodyFake.install((android.app.Application) ctx, CustodyFake.Install.NEW, 981);
        assertTrue(OwnPoolStore.recordDurably(ctx, pool(A)));
        assertFalse(DexiconPools.keptElsewhere(ctx, A));
        CustodyFake.rows = new String[][]{{A.toLowerCase(), DexiconPools.KEPT_HERE}};
        assertFalse("the cached read stands between passes", DexiconPools.keptElsewhere(ctx, A));
        assertTrue(DexiconPools.keepAlivePass(ctx, dex));
        assertTrue("taken back: Dexicon stops within this pass", DexiconPools.keptElsewhere(ctx, A));
        assertTrue(Handover.keepable(ctx, OwnPoolStore.all(ctx)).isEmpty());
    }

    /** An older PandaPools (no provider): every pool counts as kept there and no owner sweep runs from Dexicon. */
    @Test public void anUnreadableProviderStopsEveryOwnerSignature() {
        CustodyFake.install((android.app.Application) ctx, CustodyFake.Install.OLD, 980);
        assertTrue(OwnPoolStore.recordDurably(ctx, pool(A)));
        assertTrue(DexiconPools.custodyUnreadable(ctx));
        assertTrue(Handover.keepable(ctx, OwnPoolStore.all(ctx)).isEmpty());
        assertFalse(DexiconPools.sweepAllowed(ctx));
        CustodyFake.install((android.app.Application) ctx, CustodyFake.Install.NONE, 0);
        assertTrue("PandaPools gone: Dexicon keeps (and sweeps) everything", DexiconPools.sweepAllowed(ctx));
        assertEquals(1, Handover.keepable(ctx, OwnPoolStore.all(ctx)).size());
    }

    /** B: every pool owner payout address, in full and lower-case, for Dexicon's coin selection to skip. */
    @Test public void ownerAddressesNameEveryRecipesPayoutAddress() {
        assertTrue(OwnPoolStore.recordDurably(ctx, pool(A)));
        assertTrue(DexiconPools.ownerAddresses(ctx).contains(pool(A).oadr.toLowerCase()));
        assertNull("fails closed: no context, no answer", DexiconPools.ownerAddresses(null));
    }

    /** C: coins Dexicon holds (order backing, a trade's inputs) are reserved in PandaPools' own CoinLock. */
    @Test public void heldCoinsAreSkippedByPoolSelection() {
        String id = "0x" + String.join("", Collections.nCopies(64, "e"));
        assertFalse(CoinLock.isReserved(id));
        DexiconPools.holdCoins(Collections.singletonList(id));
        assertTrue(CoinLock.isReserved(id));
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
