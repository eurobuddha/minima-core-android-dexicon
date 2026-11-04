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
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();   // custody is read in the background, then the pass runs
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
        String oadr = pool(A).oadr;
        assertEquals(DexiconPools.KEPT_ELSEWHERE_COLLECT, sweep("balance address:" + oadr));
        CustodyFake.install((android.app.Application) ctx, CustodyFake.Install.NONE, 0);
        assertNull("PandaPools gone: Dexicon keeps (and sweeps) everything", sweep("balance address:" + oadr));
        assertEquals(1, Handover.keepable(ctx, OwnPoolStore.all(ctx)).size());
    }

    /** What the sweep's node connection answers for one command: the refusal, or null when it went to the node. */
    private String sweep(String command) {
        final String[] err = new String[1];
        int before = sent.size();
        DexiconPools.sweepNode(ctx, dex).cmd(command, new NodeApi.Cb() {
            public void onResult(JSONObject j) {}
            public void onError(String m) { err[0] = m; }
        });
        return sent.size() > before ? null : err[0];
    }

    /** 9 (0.1.79): the sweep is filtered PER ENTRY — a pending payout of the pool Dexicon keeps is still forwarded while
     *  another entry belongs to a pool PandaPools keeps. */
    @Test public void theSweepSkipsOnlyTheEntriesOfPoolsKeptElsewhere() {
        Pool mine = pool(B);
        mine.oadr = "0x" + String.join("", Collections.nCopies(64, "e"));
        CustodyFake.rows = new String[][]{{A.toLowerCase(), DexiconPools.KEPT_HERE}, {B.toLowerCase(), "handed_over"}};
        CustodyFake.install((android.app.Application) ctx, CustodyFake.Install.NEW, 981);
        assertTrue(OwnPoolStore.recordDurably(ctx, pool(A)));
        assertTrue(OwnPoolStore.recordDurably(ctx, mine));
        assertEquals(DexiconPools.KEPT_ELSEWHERE_COLLECT, sweep("checkaddress address:" + pool(A).oadr));
        assertNull("Dexicon's own pool's payout is still forwarded", sweep("balance address:" + mine.oadr));
    }

    /** 6 (0.1.79): an owner-signing step re-reads custody when the pass-start read is over ~5 s old. */
    @Test public void aTakeBackBetweenPassStartAndSigningStopsTheSignature() {
        CustodyFake.rows = new String[][]{{A.toLowerCase(), "handed_over"}};
        CustodyFake.install((android.app.Application) ctx, CustodyFake.Install.NEW, 981);
        assertTrue(OwnPoolStore.recordDurably(ctx, pool(A)));
        assertEquals(1, Handover.keepable(ctx, OwnPoolStore.all(ctx)).size());
        CustodyFake.rows = new String[][]{{A.toLowerCase(), DexiconPools.KEPT_HERE}};
        assertFalse("a read under ~5 s old stands", Handover.keptByDexicon(ctx, A));
        DexiconPools.ageCustody(6_000);
        assertFalse("the UI/generic read keeps its minute-long cache", DexiconPools.keptElsewhere(ctx, A));
        assertTrue("re-read before signing: taken back", Handover.keptByDexicon(ctx, A));
    }

    /** 0.1.81: an owner-signing step on the MAIN thread never queries the provider: with a read over ~5 s old it kicks a
     *  background re-read and fails closed for this step; once that read lands the next step signs on it. */
    @Test public void aSigningStepOnTheMainThreadNeverReadsTheProvider() {
        CustodyFake.rows = new String[][]{{A.toLowerCase(), "handed_over"}};
        CustodyFake.install((android.app.Application) ctx, CustodyFake.Install.NEW, 981);
        assertTrue(OwnPoolStore.recordDurably(ctx, pool(A)));
        final List<Runnable> background = new ArrayList<>();
        DexiconPools.custodyExecutorForTest(background::add);
        try {
            int before = CustodyFake.queries.get();
            assertFalse("a fresh read stands", Handover.keptByDexicon(ctx, A));
            DexiconPools.ageCustody(6_000);
            assertTrue("older than ~5 s on the main thread: kept elsewhere for this step", Handover.keptByDexicon(ctx, A));
            assertTrue(Handover.keepable(ctx, OwnPoolStore.all(ctx)).isEmpty());
            assertEquals("no provider query on the main thread", before, CustodyFake.queries.get());
            assertEquals("ONE background read for both steps", 1, background.size());
            background.remove(0).run();
            assertEquals(before + 1, CustodyFake.queries.get());
            assertFalse("the fresh read lets the next step sign", Handover.keptByDexicon(ctx, A));
        } finally { DexiconPools.custodyExecutorForTest(Runnable::run); }
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
    }

    /** 0.1.81 (5): every screen waiting on the one background read is re-rendered, not only the first. */
    @Test public void everyWaitingScreenIsToldWhenTheReadLands() {
        installPandaPools(true);
        final List<Runnable> background = new ArrayList<>();
        DexiconPools.custodyExecutorForTest(background::add);
        final int[] fresh = new int[1];
        try {
            DexiconPools.ageCustody(120_000);
            DexiconPools.custodyForUi(ctx, () -> fresh[0]++);
            DexiconPools.custodyForUi(ctx, () -> fresh[0]++);
            assertEquals(1, background.size());
            background.remove(0).run();
        } finally { DexiconPools.custodyExecutorForTest(Runnable::run); }
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        assertEquals(2, fresh[0]);
    }

    /** 0.1.81 (2): Dexicon's own hold mark lapses inside PandaPools' ~3 min CoinLock window, so a reservation PandaPools
     *  renewed (e.g. an owner-signed refresh) is seen by Dexicon's selection; lapsed marks are pruned. */
    @Test public void aPoolReservationOutlivingDexiconsHoldIsSeen() {
        assertTrue(DexiconPools.HELD_MS < 3 * 60_000);
        String id = "0x" + String.join("", Collections.nCopies(64, "7")), other = "0x" + String.join("", Collections.nCopies(64, "8"));
        DexiconPools.holdCoins(Collections.singletonList(id));
        assertFalse("Dexicon's own hold is not a pool reservation", DexiconPools.poolReserved(id));
        DexiconPools.ageHeld(DexiconPools.HELD_MS);
        CoinLock.reserve(Collections.singletonList(coin(id)));   // PandaPools re-reserves it for its own selection
        assertTrue(DexiconPools.poolReserved(id));
        DexiconPools.holdCoins(Collections.singletonList(other));
        DexiconPools.ageHeld(DexiconPools.HELD_MS);
        DexiconPools.holdCoins(Collections.singletonList(id));
        assertEquals("the lapsed mark is pruned", 1, DexiconPools.heldCount());
    }
    private static Coin coin(String id) { Coin c = new Coin(); c.coinid = id; return c; }

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
