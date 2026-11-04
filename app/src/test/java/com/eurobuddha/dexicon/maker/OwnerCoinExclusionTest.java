package com.eurobuddha.dexicon.maker;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import android.app.Application;

import com.eurobuddha.dexicon.NodeApi;
import com.eurobuddha.dexicon.fill.Coin;
import com.eurobuddha.dexicon.resting.DexTxn;
import com.eurobuddha.pandapools.OwnPoolStore;
import com.eurobuddha.pandapools.Pool;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.math.BigDecimal;
import java.util.List;

/** B (0.1.74): a coin at a ② pool's owner payout address signs with the pool's one-time owner key, so no Dexicon coin
 *  selection — the maker's backing / split / relock (walletCoins) or a trade's funding (DexTxn) — may pick it. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class OwnerCoinExclusionTest {
    private static final String MINIMA = "0x00";
    private static final String OWNER = "0x" + "c1".repeat(32), WALLET = "0x" + "c2".repeat(32);
    private Application app;
    private NodeApi node;

    @Before public void up() throws Exception {
        app = RuntimeEnvironment.getApplication();
        Pool p = new Pool();
        p.address = "0x" + "a9".repeat(32); p.opk = "0x" + "b9".repeat(32); p.oadr = OWNER; p.tok = "0x" + "d9".repeat(32);
        p.kmin = "100"; p.covenantScript = "RETURN TRUE";
        assertTrue(OwnPoolStore.recordDurably(app, p));
        node = mock(NodeApi.class);
        when(node.context()).thenReturn(app);
        JSONArray coins = new JSONArray()
                .put(coin("0x" + "01".repeat(32), OWNER, "500"))     // the biggest coin — largest-first would take it
                .put(coin("0x" + "02".repeat(32), WALLET, "20"));
        JSONObject scripts = new JSONObject().put("status", true).put("response", new JSONArray()
                .put(new JSONObject().put("address", OWNER).put("script", "RETURN SIGNEDBY(" + p.opk + ")"))
                .put(new JSONObject().put("address", WALLET).put("script", "RETURN SIGNEDBY(0x" + "e9".repeat(32) + ")")));
        doAnswer(inv -> {
            String c = inv.getArgument(0); NodeApi.Cb cb = inv.getArgument(1);
            if (c.equals("scripts")) cb.onResult(scripts);
            else cb.onResult(new JSONObject().put("status", true).put("response", coins));
            return null;
        }).when(node).cmdR(anyString(), any(NodeApi.Cb.class));
        doAnswer(inv -> {
            String c = inv.getArgument(0); NodeApi.Cb cb = inv.getArgument(1);
            if (c.startsWith("balance")) cb.onResult(new JSONObject().put("status", true).put("response", new JSONArray()
                    .put(new JSONObject().put("tokenid", MINIMA).put("coins", 2))));
            else cb.onResult(new JSONObject().put("status", true).put("response", coins));
            return null;
        }).when(node).cmd(anyString(), any(NodeApi.Cb.class));
    }

    private static JSONObject coin(String id, String address, String amount) throws Exception {
        return new JSONObject().put("coinid", id).put("address", address).put("tokenid", MINIMA).put("amount", amount)
                .put("spent", false).put("state", new JSONArray()).put("created", "1");
    }

    @Test public void theMakerNeverBacksAnOrderWithAPoolOwnerCoin() {
        final List<Coin>[] got = new List[1];
        MakerEngine.walletCoins(node, MINIMA, (e, coins) -> got[0] = coins);
        assertEquals(1, got[0].size());
        assertEquals(WALLET, got[0].get(0).address);
    }

    @Test public void aTradeNeverFundsItselfWithAPoolOwnerCoin() {
        final List<JSONObject>[] got = new List[1];
        new DexTxn(node).findCoins(MINIMA, new BigDecimal("10"), coins -> got[0] = coins);
        assertNotNull(got[0]);
        assertEquals(1, got[0].size());
        assertEquals(WALLET, got[0].get(0).optString("address"));
    }

    /** 5 (0.1.79): the CoinLock is two-way — a coin a ② pool selection holds in PandaPools' CoinLock is skipped by the
     *  maker and by a trade's funding; a coin Dexicon itself held there (a live order's backing) is still its own. */
    @Test public void aCoinAPoolSelectionHoldsIsNeverPickedByDexicon() {
        com.eurobuddha.pandapools.Coin held = new com.eurobuddha.pandapools.Coin(); held.coinid = "0x" + "02".repeat(32);
        com.eurobuddha.pandapools.CoinLock.reserve(java.util.Collections.singletonList(held));   // PandaPools' own selection
        try {
            final List<Coin>[] got = new List[1];
            MakerEngine.walletCoins(node, MINIMA, (e, coins) -> got[0] = coins);
            assertTrue("the maker skips it", got[0].isEmpty());
            final List<JSONObject>[] fund = new List[]{java.util.Collections.emptyList()};
            new DexTxn(node).findCoins(MINIMA, new BigDecimal("10"), coins -> fund[0] = coins);
            assertNull("a trade does not fund itself with it", fund[0]);
            // 0.1.83: Dexicon cannot take it over with a fresh hold mark — the hold reports it and it stays skipped
            assertEquals(java.util.Collections.singleton(held.coinid), com.eurobuddha.pandapools.DexiconPools.holdCoins(java.util.Collections.singletonList(held.coinid)));
            MakerEngine.walletCoins(node, MINIMA, (e, coins) -> got[0] = coins);
            assertTrue("still the pool's", got[0].isEmpty());
        } finally { com.eurobuddha.pandapools.CoinLock.release(java.util.Collections.singletonList(held)); }
        com.eurobuddha.pandapools.DexiconPools.holdCoins(java.util.Collections.singletonList(held.coinid));   // Dexicon's own hold first
        final List<Coin>[] mine = new List[1];
        MakerEngine.walletCoins(node, MINIMA, (e, coins) -> mine[0] = coins);
        assertEquals("Dexicon's own backing coin stays visible to the maker", 1, mine[0].size());
    }

    /** C: a live ③ order's backing coins are held in PandaPools' CoinLock every tick, so ② pool refresh never picks one. */
    @Test public void liveOrderBackingCoinsAreHeldFromPoolSelection() {
        MakerService s = org.robolectric.Robolectric.buildService(MakerService.class).get();
        s.db = new com.eurobuddha.dexicon.DexDb(s);
        String coinId = "0x" + "07".repeat(32);
        MyOrder o = new MyOrder(); o.orderId = "0x00000000000000c7"; o.side = "sell"; o.price = "0.0035"; o.amount = "100"; o.remaining = "100";
        o.address = WALLET; o.seq = 5; o.expiry = MakerEngine.now() + 3600; o.coins.add(coinId);
        s.db.saveOrder(o);
        assertTrue(o.live());
        assertFalse(com.eurobuddha.pandapools.CoinLock.isReserved(coinId));
        s.holdOrderCoins();
        assertTrue(com.eurobuddha.pandapools.CoinLock.isReserved(coinId));
    }

    /** 0.1.83 (4b): the backing coins are re-held every tick even while the relay chain runs (`ticking`). */
    @Test public void backingCoinsAreReHeldWhileTheRelayChainRuns() {
        MakerService s = org.robolectric.Robolectric.buildService(MakerService.class).get();
        s.db = new com.eurobuddha.dexicon.DexDb(s);
        String coinId = "0x" + "0a".repeat(32);
        MyOrder o = new MyOrder(); o.orderId = "0x00000000000000ca"; o.side = "sell"; o.price = "0.0035"; o.amount = "100"; o.remaining = "100";
        o.address = WALLET; o.seq = 5; o.expiry = MakerEngine.now() + 3600; o.coins.add(coinId);
        s.db.saveOrder(o);
        s.started = true; s.ticking = true;
        assertFalse(com.eurobuddha.pandapools.CoinLock.isReserved(coinId));
        s.tick.run();
        assertTrue(com.eurobuddha.pandapools.CoinLock.isReserved(coinId));
        s.started = false;
    }
}
