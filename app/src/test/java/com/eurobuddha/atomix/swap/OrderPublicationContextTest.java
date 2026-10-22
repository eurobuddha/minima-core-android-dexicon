// VENDORED VERBATIM from apks/atomix 0.1.82 app/src/test/java/com/eurobuddha/atomix/swap/OrderPublicationContextTest.java by scripts/vendor-atomix.sh — do not edit here; re-vendor.
package com.eurobuddha.atomix.swap;

import com.eurobuddha.atomix.TradingContext;
import com.eurobuddha.atomix.eth.*;
import com.eurobuddha.comms.*;
import com.goterl.lazysodium.LazySodium;
import org.json.*;
import org.junit.*;
import org.mockito.MockedStatic;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Reuses OTC callback fixtures and the existing synthetic signed order format. */
public class OrderPublicationContextTest {
    private TradingContext saved;
    @Before public void save() { saved = TradingContext.active(); }
    @After public void restore() { TradingContext.setActive(saved, null); }
    private static Order local() {
        Order o = new Order(); o.minimaPublicKey = "0x1234"; o.ethAddress = "0x" + "12".repeat(20);
        o.commsPublicId = "peer"; o.minimaAvail = 5; o.usdtAvail = 10;
        o.pairs.put("USDT", new Order.Pair(true, 2, 1, 0.1)); return o;
    }
    private static JSONObject coin(long timestamp, boolean live) throws Exception {
        Order o = local(); o.ts = timestamp; o.pairs.get("USDT").enable = live;
        return new JSONObject().put("coinid", "fixture-" + timestamp).put("state", new JSONObject()
            .put("99", Hex.to(o.toJson().toString().getBytes(StandardCharsets.UTF_8)))
            .put("1", "0x" + "12".repeat(32)).put("2", "0x" + "34".repeat(64)));
    }
    private static class Calls {
        final NodeApi node = mock(NodeApi.class);
        final LazySodium sodium = mock(LazySodium.class);
        final List<String> commands = new ArrayList<>();
        final List<NodeApi.Cb> callbacks = new ArrayList<>();
        Calls() {
            when(sodium.cryptoSignVerifyDetached(any(byte[].class), any(byte[].class), anyInt(), any(byte[].class))).thenReturn(true);
            when(sodium.cryptoSignDetached(any(byte[].class), any(byte[].class), anyLong(), any(byte[].class))).thenReturn(true);
            doAnswer(i -> { commands.add(i.getArgument(0)); callbacks.add(i.getArgument(1)); return null; }).when(node).cmd(anyString(), any());
        }
    }
    private static final class Scan extends Calls {
        Map<String, Order> book; String error; int deliveries;
        Scan(TradingContext market) { TradingContext.setActive(market, null); SwapOrderBook.scan(node, sodium, b -> { book = b; deliveries++; }, e -> error = e); }
        void registered(boolean ok) { if (ok) callbacks.get(0).onResult(new JSONObject()); else callbacks.get(0).onError("notification unavailable"); }
        void received(JSONObject... coins) throws Exception { callbacks.get(1).onResult(new JSONObject().put("response", new JSONArray(Arrays.asList(coins)))); }
        void rejected() { assertEquals(SwapOrderBook.MARKET_ERROR, error); assertNull(book); assertEquals(0, deliveries); }
    }
    @Test public void bothBoardsKeepTheCapturedMarketAndSignedWire() throws Exception {
        for (TradingContext market : TradingContext.values()) for (boolean notified : new boolean[]{true, false}) {
            Scan s = new Scan(market); s.registered(notified); s.received(coin(1, true));
            assertEquals(Arrays.asList("coinnotify action:add address:" + market.orderBookAddr,
                "coins simplestate:true order:desc depth:" + SwapOrderBook.SCAN_DEPTH + " address:" + market.orderBookAddr), s.commands);
            assertEquals(1, s.deliveries); assertNull(s.error); Order order = s.book.values().iterator().next(); assertSame(market, order.market());
            JSONObject wire = order.toJson(); Set<String> keys = new HashSet<>(); wire.keys().forEachRemaining(keys::add);
            assertEquals(new HashSet<>(Arrays.asList("mpk", "eth", "cid", "ts", "bal", "pairs")), keys);
            assertNull(Order.fromJson(wire.put("market", market.key)).market());
            assertNull(SwapOrderBook.verifyCoin(s.sodium, coin(1, true)).market());
            assertSame(market, Order.fromConfigJson(Order.toConfigJson(order)).market());
        }
    }
    @Test public void switchesAtEveryScanBoundaryPreventDelivery() throws Exception {
        for (TradingContext market : TradingContext.values()) for (int step = 0; step < 4; step++) {
            Scan s = new Scan(market);
            if (step < 2) { TradingContext.setActive(market.other(), null); s.registered(step == 0); assertEquals(1, s.commands.size()); }
            else {
                s.registered(true);
                if (step == 2) TradingContext.setActive(market.other(), null);
                else when(s.sodium.cryptoSignVerifyDetached(any(byte[].class), any(byte[].class), anyInt(), any(byte[].class)))
                    .thenAnswer(i -> { TradingContext.setActive(market.other(), null); return true; });
                s.received(coin(1, true));
            }
            s.rejected();
        }
    }
    @Test public void scanRetainsSignaturesTombstonesAndReadErrors() throws Exception {
        Scan s = new Scan(TradingContext.MINIMA); s.registered(true); s.received(coin(2, false), coin(1, true));
        Order latest = s.book.values().iterator().next(); assertEquals(2, latest.ts); assertFalse(latest.hasLiquidity());
        s = new Scan(TradingContext.MXUSDT); s.registered(false);
        when(s.sodium.cryptoSignVerifyDetached(any(byte[].class), any(byte[].class), anyInt(), any(byte[].class))).thenReturn(false);
        s.received(coin(1, true)); assertTrue(s.book.isEmpty());
        s = new Scan(TradingContext.MINIMA); s.registered(true); s.callbacks.get(1).onError("read failed"); assertEquals("read failed", s.error); assertNull(s.book);
    }
    private static final class Pub extends Calls {
        final Order order = local();
        final CommsIdentity identity = CommsIdentity.fromKeys(new byte[32], new byte[32], new byte[32], new byte[64]);
        final CommsTransport.SendCb result = mock(CommsTransport.SendCb.class);
        void fresh(long gen) { SwapOrderBook.publishFresh(node, sodium, identity, order, gen, result); }
        void balance(boolean ok) throws Exception {
            if (ok) callbacks.get(0).onResult(new JSONObject().put("status",true).put("response", new JSONArray().put(new JSONObject().put("tokenid",TradingContext.active().tokenId).put("coins","1").put("sendable", "12.5"))));
            else callbacks.get(0).onError("balance unavailable");
            if(ok&&callbacks.size()>1)callbacks.get(1).onResult(new JSONObject().put("status",true).put("response",new JSONArray().put(new JSONObject().put("coinid","0x"+"ab".repeat(32)).put("tokenid",TradingContext.active().tokenId).put("amount","12.5").put("tokenamount","12.5"))));
        }
        void reject() { verify(result).onFailed(SwapOrderBook.MARKET_ERROR); verify(result, never()).onSent(anyString()); }
    }
    @Test public void stalePreparationCannotReadBalanceOrSign() {
        for (TradingContext market : TradingContext.values()) for (boolean currency : new boolean[]{true, false}) {
            TradingContext.setActive(market, null); Pub p = new Pub(); long gen = PublishGate.gen();
            if (currency) TradingContext.setActive(market.other(), null); else PublishGate.bumpGen();
            p.fresh(gen); p.reject(); verifyNoInteractions(p.node); verify(p.sodium, never()).cryptoSignDetached(any(), any(), anyLong(), any());
        }
    }
    @Test public void pendingBalanceCannotPublishAfterSwitchEditOrWithdrawal() throws Exception {
        for (TradingContext market : TradingContext.values()) for (boolean currency : new boolean[]{true, false}) for (boolean reply : new boolean[]{true, false}) {
            TradingContext.setActive(market, null); Pub p = new Pub(); p.fresh(PublishGate.gen());
            assertEquals("balance tokenid:" + market.tokenId, p.commands.get(0));
            if (currency) TradingContext.setActive(market.other(), null); else PublishGate.bumpGen();
            try (MockedStatic<CommsTransport> transport = mockStatic(CommsTransport.class)) {
                p.balance(reply); p.reject(); transport.verifyNoInteractions(); assertEquals(5, p.order.minimaAvail, 0);
            }
        }
    }
    @Test public void changesDuringSigningCannotPostTheOlderOrder() {
        for (TradingContext market : TradingContext.values()) for (boolean currency : new boolean[]{true, false}) {
            TradingContext.setActive(market, null); Pub p = new Pub();
            when(p.sodium.cryptoSignDetached(any(byte[].class), any(byte[].class), anyLong(), any(byte[].class))).thenAnswer(i -> {
                if (currency) TradingContext.setActive(market.other(), null); else PublishGate.bumpGen(); return true;
            });
            try (MockedStatic<CommsTransport> transport = mockStatic(CommsTransport.class)) {
                SwapOrderBook.publish(p.node, p.sodium, p.identity, p.order, p.result); p.reject(); transport.verifyNoInteractions();
            }
        }
    }
    @Test public void verifiedPublicationKeepsDestinationAndFailedBalanceCannotRefresh() throws Exception {
        for (TradingContext market : TradingContext.values()) for (boolean reply : new boolean[]{true, false}) {
            TradingContext.setActive(market, null); Pub p = new Pub(); p.fresh(PublishGate.gen());
            try (MockedStatic<CommsTransport> transport = mockStatic(CommsTransport.class)) {
                transport.when(() -> CommsTransport.postBlob(any(), anyString(), anyString(), anyString(), anyString(), any(), any(), any()))
                    .thenAnswer(i -> { assertEquals(market.orderBookAddr, i.getArgument(1)); ((CommsTransport.SendCb)i.getArgument(7)).onSent("fixture-tx"); return null; });
                p.balance(reply); if(reply) { assertEquals(market==TradingContext.MINIMA ? 12.5-0.000000001 : 12.5,p.order.minimaAvail,0); verify(p.result).onSent("fixture-tx"); verify(p.result,never()).onFailed(anyString()); } else { verify(p.result).onFailed(SwapOrderBook.BALANCE_ERROR); transport.verifyNoInteractions(); }
            }
        }
    }
    @Test public void latePublishSuccessCannotStampASupersedingMarketOrEdit() {
        for (TradingContext market : TradingContext.values()) for (boolean currency : new boolean[]{true, false}) {
            TradingContext.setActive(market, null); Pub p = new Pub(); List<CommsTransport.SendCb> pending = new ArrayList<>();
            try (MockedStatic<CommsTransport> transport = mockStatic(CommsTransport.class)) {
                transport.when(() -> CommsTransport.postBlob(any(), anyString(), anyString(), anyString(), anyString(), any(), any(), any()))
                    .thenAnswer(i -> { pending.add(i.getArgument(7)); return null; });
                SwapOrderBook.publish(p.node, p.sodium, p.identity, p.order, p.result); assertEquals(1, pending.size());
                if (currency) TradingContext.setActive(market.other(), null); else PublishGate.bumpGen();
                pending.get(0).onSent("fixture-tx"); p.reject();
            }
        }
    }
    @Test public void unattributedWireOrderCannotBePublished() throws Exception {
        TradingContext.setActive(TradingContext.MINIMA,null); Pub p=new Pub();
        Order wire=Order.fromJson(p.order.toJson().put("market",TradingContext.MINIMA.key));
        SwapOrderBook.publish(p.node,p.sodium,p.identity,wire,p.result);p.reject();verifyNoInteractions(p.node,p.sodium);
    }
}
