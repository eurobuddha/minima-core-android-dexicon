// VENDORED VERBATIM from apks/atomix 0.1.84 app/src/test/java/com/eurobuddha/atomix/swap/CpLockDeclineTest.java by scripts/vendor-atomix.sh — do not edit here; re-vendor.
package com.eurobuddha.atomix.swap;

import android.os.Handler;
import com.eurobuddha.atomix.eth.*;
import com.eurobuddha.comms.NodeApi;
import org.junit.Test;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.math.BigInteger;
import java.util.Map;
import java.util.function.Consumer;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

public class CpLockDeclineTest {
    @Test public void refusalReleasesSlotBeforePersistenceAndThrottlesAcrossDeals() throws Exception {
        SwapEngine.resetFragmentationNoteForTest();
        Handler ui = mock(Handler.class);
        when(ui.post(any())).thenAnswer(inv -> { ((Runnable) inv.getArgument(0)).run(); return true; });
        MinimaHtlc minima = mock(MinimaHtlc.class);
        when(minima.activeToken()).thenReturn(com.eurobuddha.atomix.TradingContext.active().tokenId);
        doAnswer(inv -> { ((Consumer<String>) inv.getArgument(1)).accept("TOO_MANY_COINS: 120 coins, safe limit 53"); return null; })
                .when(minima).myFreeCoins(any(), any());
        SwapDb db = mock(SwapDb.class);
        SwapEngine.Notifier notifier = mock(SwapEngine.Notifier.class);
        SwapEngine engine = new SwapEngine(mock(NodeApi.class), minima, db, mock(EthWallet.class), ui, notifier);
        engine.setNetwork(mock(EthRpc.class), EthNet.MAINNET);
        Order order = new Order(); Order.Pair pair = new Order.Pair(); pair.enable = true; pair.buy = 1;
        order.pairs.put("USDT", pair); engine.setMyOrder(order);
        Method lock = SwapEngine.class.getDeclaredMethod("lockMinimaCounterLeg", EthHtlc.Contract.class, int.class, com.eurobuddha.atomix.TradingContext.class, long.class);
        lock.setAccessible(true);
        Field slots = SwapEngine.class.getDeclaredField("cpInFlight"); slots.setAccessible(true);
        Field global = SwapEngine.class.getDeclaredField("CP_LOCKING"); global.setAccessible(true);
        String first = "0x" + "AB".repeat(32), second = "0x" + "CD".repeat(32);
        try {
            for (String hash : new String[]{first, second}) {
                ((Map<String,String>) slots.get(engine)).put(hash, "");
                ((Map<String,Long>) global.get(null)).put(hash, System.currentTimeMillis()/1000);
                EthHtlc.Contract c = new EthHtlc.Contract();
                c.hashlock = hash; c.requestAmount = BigInteger.TEN.pow(18); c.minimaPublicKey = "0xAABB";
                c.amount = BigInteger.TEN.pow(6); c.tokenContract = EthNet.MAINNET.token("USDT").address;
                lock.invoke(engine, c, 100, com.eurobuddha.atomix.TradingContext.active(), PublishGate.gen());
                assertEquals(0, engine.cpInFlightSizeForTest());
                assertFalse(((Map<?,?>) global.get(null)).containsKey(hash));
            }
            verify(minima, times(2)).myFreeCoins(any(), any());
            verify(db, never()).upsertSwap(any());
            verify(notifier).notify(anyString(), contains("Consolidate"));
            verify(notifier).notify(anyString(), contains(first));
            verifyNoMoreInteractions(notifier);
        } finally { engine.shutdown(); SwapEngine.resetFragmentationNoteForTest(); }
    }
}
