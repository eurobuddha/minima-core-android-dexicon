// VENDORED VERBATIM from apks/atomix 0.1.82 app/src/test/java/com/eurobuddha/atomix/swap/LadderCoinsBalanceTest.java by scripts/vendor-atomix.sh — do not edit here; re-vendor.
package com.eurobuddha.atomix.swap;
import android.os.Handler;
import com.eurobuddha.atomix.eth.EthWallet;
import com.eurobuddha.comms.NodeApi;
import org.junit.Test;
import java.util.function.Consumer;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

public class LadderCoinsBalanceTest {
    @Test public void trimsCumulativePrefixFromSendableBalance() { check(false, 2); }
    @Test public void failureTrimsToOneAndStillPublishesExactlyOnce() { check(true, 1); }
    private void check(boolean failRead, int expected) {
        MinimaHtlc minima = mock(MinimaHtlc.class);
        doAnswer(inv -> {
            if (failRead) ((Consumer<String>) inv.getArgument(1)).accept("unavailable");
            else ((Consumer<MinimaHtlc.TokenBalance>) inv.getArgument(0)).accept(new MinimaHtlc.TokenBalance(120,"25","30","5"));
            return null;
        }).when(minima).tokenBalance(any(), any());
        SwapEngine engine = new SwapEngine(mock(NodeApi.class), minima, mock(SwapDb.class), mock(EthWallet.class), mock(Handler.class), mock(SwapEngine.Notifier.class));
        Order order = new Order(); Order.Pair pair = new Order.Pair(true,0,0,1);
        pair.asks.add(new Order.Level(1,10)); pair.asks.add(new Order.Level(2,10)); pair.asks.add(new Order.Level(3,10));
        order.pairs.put("USDT",pair); int[] published = {0};
        try {
            engine.ensureLadderCoins(order,false,()->published[0]++);
            assertEquals(expected, order.effectiveAsks("USDT").size()); assertEquals(1,published[0]);
            verify(minima,never()).myFreeCoins(any(),any());
        } finally { engine.shutdown(); }
    }
}
