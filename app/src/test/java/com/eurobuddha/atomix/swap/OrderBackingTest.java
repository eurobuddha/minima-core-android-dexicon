// VENDORED VERBATIM from apks/atomix 0.1.83 app/src/test/java/com/eurobuddha/atomix/swap/OrderBackingTest.java by scripts/vendor-atomix.sh — do not edit here; re-vendor.
package com.eurobuddha.atomix.swap;

import com.eurobuddha.atomix.TradingContext;
import com.eurobuddha.comms.*;
import com.goterl.lazysodium.LazySodium;
import org.json.*;
import org.junit.*;
import org.mockito.MockedStatic;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Real final publisher and signed payload, synthetic identity and mocked transport only. */
public class OrderBackingTest {
    TradingContext saved;NodeApi node;LazySodium sodium;NodeApi.Cb read;CommsTransport.SendCb result;
    CommsIdentity identity;String signed;
    @Before public void setup(){
        saved=TradingContext.active();TradingContext.setActive(TradingContext.MINIMA,null);
        node=mock(NodeApi.class);sodium=mock(LazySodium.class);result=mock(CommsTransport.SendCb.class);
        identity=CommsIdentity.fromKeys(new byte[32],new byte[32],new byte[32],new byte[64]);
        doAnswer(i->{read=i.getArgument(1);return null;}).when(node).cmd(anyString(),any());
        when(sodium.cryptoSignDetached(any(),any(),anyLong(),any())).thenAnswer(i->{signed=new String((byte[])i.getArgument(1),StandardCharsets.UTF_8);return true;});
    }
    @After public void restore(){TradingContext.setActive(saved,null);}
    static Order order(double amount){Order o=new Order();o.minimaAvail=5000;o.pairs.put("USDT",new Order.Pair(true,1.25,0,1));o.pairs.get("USDT").asks.add(new Order.Level(1.25,amount));return o;}
    JSONObject balance(String amount)throws Exception{return new JSONObject().put("status",true).put("response",new JSONArray().put(new JSONObject().put("tokenid",TradingContext.active().tokenId).put("coins","1").put("sendable",amount)));}
    JSONObject free(String amount)throws Exception {
        JSONObject coin=new JSONObject().put("coinid","0x"+"12".repeat(32)).put("tokenid",TradingContext.active().tokenId)
            .put("amount",amount).put("tokenamount",amount);
        return new JSONObject().put("status",true).put("response",new JSONArray().put(coin));
    }
    Order publish(Order o,JSONObject reply)throws Exception {
        String amount="0";if(reply!=null&&reply.optJSONArray("response")!=null&&reply.optJSONArray("response").optJSONObject(0)!=null)amount=reply.optJSONArray("response").optJSONObject(0).optString("sendable","0");
        return publish(o,reply,free(amount));
    }
    Order publish(Order o,JSONObject reply,JSONObject coins)throws Exception {
        try(MockedStatic<CommsTransport> transport=mockStatic(CommsTransport.class)){
            SwapOrderBook.publishFresh(node,sodium,identity,o,result);NodeApi.Cb balanceRead=read;read.onResult(reply);if(read!=balanceRead)read.onResult(coins);
            if(signed==null){transport.verifyNoInteractions();return null;}
            transport.verify(()->CommsTransport.postBlob(eq(node),eq(TradingContext.active().orderBookAddr),eq(CommsTransport.MESSAGE_AMOUNT),eq(CommsTransport.NATIVE),eq(Hex.to(signed.getBytes(StandardCharsets.UTF_8))),any(),any(),any()));
            return Order.fromJson(new JSONObject(signed));
        }
    }
    @Test public void exhaustedFiveThousandOrderPublishesOnlyAWithdrawal()throws Exception {
        Order sent=publish(order(5000),balance("0"));assertNotNull(sent);assertEquals(0,sent.minimaAvail,0);
        assertTrue(sent.effectiveAsks("USDT").isEmpty());assertFalse(sent.hasLiquidity());
        assertEquals(0,new JSONObject(signed).getJSONObject("pairs").getJSONObject("USDT").getDouble("buy"),0);
    }
    @Test public void absentTokenIsAValidZeroBalance()throws Exception {assertFalse(publish(order(5000),new JSONObject().put("status",true).put("response",new JSONArray())).hasLiquidity());}
    @Test public void fundedLevelIsKeptAndPostageReserved()throws Exception {
        Order sent=publish(order(5000),balance("5000.000000001"));assertEquals(5000,sent.minimaAvail,0);assertEquals(5000,sent.effectiveAsks("USDT").get(0).amount,0);
    }
    @Test public void insufficientOrPostageOnlyBalanceCannotBackASingleLevel()throws Exception {
        for(String amount:new String[]{"4999","5000","0.000000001"}){signed=null;assertTrue(publish(order(5000),balance(amount)).effectiveAsks("USDT").isEmpty());}
    }
    @Test public void cumulativePrefixUsesExactDecimalsAndClearsLegacyFallback() {
        Order o=order(0.1);Order.Pair p=o.pairs.get("USDT");p.min=0;p.asks.add(new Order.Level(2,0.2));
        o.clampAsksToBalance(new BigDecimal("0.3"));assertEquals(2,p.asks.size());
        o.clampAsksToBalance(new BigDecimal("0.2999999999"));assertEquals(1,p.asks.size());
        o.clampAsksToBalance(BigDecimal.ZERO);assertEquals(0,p.buy,0);assertTrue(o.effectiveAsks("USDT").isEmpty());
    }
    @Test public void zeroSellFundsKeepAnIndependentlyFundedBuySide()throws Exception {
        Order o=order(5000);o.usdtAvail=100;o.pairs.get("USDT").bids.add(new Order.Level(1,50));
        Order sent=publish(o,balance("0"));assertTrue(sent.effectiveAsks("USDT").isEmpty());assertEquals(1,sent.effectiveBids("USDT").size());
    }
    @Test public void legacyScalarOrdersCannotResurrectZeroOrBelowMinimumAsks()throws Exception {
        for(String amount:new String[]{"0","0.5"}) {Order o=order(5000);o.pairs.get("USDT").asks.clear();assertTrue(publish(o,balance(amount)).effectiveAsks("USDT").isEmpty());}
    }
    @Test public void oldSignedPhantomLadderIsNotLiveToUpdatedReaders()throws Exception {
        Order o=order(5000);o.minimaAvail=0;Order read=Order.fromJson(o.toJson());assertFalse(SwapOrderBook.makerLive(read,"USDT",false));assertFalse(read.hasLiquidity());
    }
    @Test public void errorOrMalformedBalanceCannotSignOrRefreshStaleFunds()throws Exception {
        try(MockedStatic<CommsTransport> transport=mockStatic(CommsTransport.class)) {
            SwapOrderBook.publishFresh(node,sodium,identity,order(5000),result);read.onError("offline");
            verify(result).onFailed(SwapOrderBook.BALANCE_ERROR);verifyNoInteractions(sodium);transport.verifyNoInteractions();
        }
        List<JSONObject> invalid=new ArrayList<>(Arrays.asList(null,new JSONObject(),balance("NaN"),balance("Infinity"),balance("-1"),balance("1e3")));
        invalid.add(balance("5").put("status","true"));
        JSONObject wrong=balance("5");wrong.getJSONArray("response").getJSONObject(0).put("tokenid","0xwrong");invalid.add(wrong);
        JSONObject missing=balance("5");missing.getJSONArray("response").getJSONObject(0).remove("sendable");invalid.add(missing);
        for(JSONObject reply:invalid){clearInvocations(result);assertNull(publish(order(5000),reply));verify(result).onFailed(SwapOrderBook.BALANCE_ERROR);}
        verifyNoInteractions(sodium);
    }
    @Test public void coinsAlreadyInMiningOrMempoolCannotBackARefresh()throws Exception {
        JSONObject none=new JSONObject().put("status",true).put("response",new JSONArray());
        assertFalse(publish(order(5000),balance("5001"),none).hasLiquidity());
        assertFalse(publish(order(5000),balance("5001"),free("3000")).hasLiquidity());
        verify(node,times(2)).cmd(eq("coins relevant:true sendable:true tokenid:0x00 coinage:1 checkmempool:true"),any());
    }
    @Test public void colouredTokenBackingUsesTokenAmountsAndNoNativePostageDeduction()throws Exception {
        TradingContext.setActive(TradingContext.MXUSDT,null);
        JSONObject coins=free("5000");coins.getJSONArray("response").getJSONObject(0).put("amount","0.000000005");
        assertEquals(5000,publish(order(5000),balance("5000"),coins).effectiveAsks("USDT").get(0).amount,0);
    }
    @Test public void invalidCoinListsCannotSignOrRefresh()throws Exception {
        List<JSONObject> invalid=new ArrayList<>(Arrays.asList(null,new JSONObject(),free("NaN"),free("0"),free("-1")));
        JSONObject wrong=free("5001");wrong.getJSONArray("response").getJSONObject(0).put("tokenid","0xwrong");invalid.add(wrong);
        JSONObject dup=free("2501");dup.getJSONArray("response").put(dup.getJSONArray("response").getJSONObject(0));invalid.add(dup);
        for(JSONObject coins:invalid){clearInvocations(result);assertNull(publish(order(5000),balance("5001"),coins));verify(result).onFailed(SwapOrderBook.BALANCE_ERROR);}
        verifyNoInteractions(sodium);
    }
    @Test public void oversizedCoinWalletIsRejectedBeforeFetchingBodies()throws Exception {
        JSONObject reply=balance("5001");reply.getJSONArray("response").getJSONObject(0).put("coins",String.valueOf(MinimaHtlc.maxSafeCoinRows(TradingContext.active().tokenId)+1));
        assertNull(publish(order(5000),reply));verify(node,times(1)).cmd(anyString(),any());verifyNoInteractions(sodium);
    }
    @Test public void coinReadErrorOrMarketSwitchCannotPublish()throws Exception {
        try(MockedStatic<CommsTransport> transport=mockStatic(CommsTransport.class)) {
            SwapOrderBook.publishFresh(node,sodium,identity,order(5000),result);read.onResult(balance("5001"));read.onError("offline");
            verify(result).onFailed(SwapOrderBook.BALANCE_ERROR);verifyNoInteractions(sodium);transport.verifyNoInteractions();
            clearInvocations(result);SwapOrderBook.publishFresh(node,sodium,identity,order(5000),result);read.onResult(balance("5001"));
            JSONObject coins=free("5001");TradingContext.setActive(TradingContext.MXUSDT,null);read.onResult(coins);
            verify(result).onFailed(anyString());verifyNoInteractions(sodium);transport.verifyNoInteractions();
        }
    }
    @Test public void editOrWithdrawalDuringCoinReadCannotPublishTheOldOrder()throws Exception {
        try(MockedStatic<CommsTransport> transport=mockStatic(CommsTransport.class)) {
            SwapOrderBook.publishFresh(node,sodium,identity,order(5000),result);read.onResult(balance("5001"));
            PublishGate.bumpGen();read.onResult(free("5001"));
            verify(result).onFailed(anyString());verifyNoInteractions(sodium);transport.verifyNoInteractions();
        }
    }
    @Test public void freshConfigurationCanResumeAfterFundingReturns()throws Exception {
        String saved=Order.toConfigJson(order(5000));assertTrue(publish(Order.fromConfigJson(saved),balance("0")).effectiveAsks("USDT").isEmpty());
        Order resumed=publish(Order.fromConfigJson(saved),balance("5000.000000001"));assertEquals(5000,resumed.effectiveAsks("USDT").get(0).amount,0);
        assertEquals(5000,Order.fromConfigJson(saved).pairs.get("USDT").asks.get(0).amount,0);
    }
}
