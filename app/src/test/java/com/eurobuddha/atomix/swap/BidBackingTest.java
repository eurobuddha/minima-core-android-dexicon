// VENDORED VERBATIM from apks/atomix 0.1.82 app/src/test/java/com/eurobuddha/atomix/swap/BidBackingTest.java by scripts/vendor-atomix.sh — do not edit here; re-vendor.
package com.eurobuddha.atomix.swap;

import android.os.Handler;
import com.eurobuddha.minimaapi.ethereum.EthereumTransactions;
import com.eurobuddha.atomix.TradingContext;
import com.eurobuddha.atomix.eth.*;
import com.eurobuddha.comms.*;
import com.goterl.lazysodium.LazySodium;
import java.lang.reflect.Field;
import java.math.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.function.BooleanSupplier;
import org.json.*;
import org.junit.*;
import org.mockito.MockedStatic;
import org.web3j.crypto.Credentials;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Actual engine/publisher and signed payload; queued mock worker and transport, synthetic credentials. */
public class BidBackingTest {
    TradingContext saved;
    @Before public void before(){saved=TradingContext.active();TradingContext.setActive(TradingContext.MINIMA,null);}
    @After public void after(){TradingContext.setActive(saved,null);}
    static Order order(){Order o=new Order();o.usdtAvail=5000;o.pairs.put("USDT",new Order.Pair(true,0,1,1));o.pairs.get("USDT").bids.add(new Order.Level(1,5000));return o;}
    static String word(long raw){return "0x"+String.format("%064x",raw);}
    static final class Fixture implements AutoCloseable {
        NodeApi node=mock(NodeApi.class);EthWallet wallet=mock(EthWallet.class);EthRpc rpc=mock(EthRpc.class);
        Handler ui=mock(Handler.class);ExecutorService worker=mock(ExecutorService.class);List<Runnable> queue=new ArrayList<>();
        LazySodium sodium=mock(LazySodium.class);CommsTransport.SendCb cb=mock(CommsTransport.SendCb.class);
        CommsIdentity identity=CommsIdentity.fromKeys(new byte[32],new byte[32],new byte[32],new byte[64]);
        Credentials owner=Credentials.create("01");
        final Map<String,String> records=new HashMap<>();
        final EthereumTransactions journal=new EthereumTransactions(new EthereumTransactions.Store(){
            public String read(String key){return records.getOrDefault(key,"[]");}
            public void write(String key,String value){records.put(key,value);}
        });
        final Field shared=EthereumTransactions.class.getDeclaredField("shared");
        final Object previous;
        BigInteger minedNonce=BigInteger.ZERO;
        String blockHash="0x"+"ab".repeat(32);
        SwapEngine engine;Order o=order();NodeApi.Cb balance;String signed;
        Fixture()throws Exception {
            shared.setAccessible(true);previous=shared.get(null);shared.set(null,journal);
            when(wallet.creds()).thenReturn(owner);o.ethAddress=owner.getAddress();o.minimaPublicKey="0xAA";
            when(ui.post(any())).thenAnswer(i->{((Runnable)i.getArgument(0)).run();return true;});
            engine=new SwapEngine(node,mock(MinimaHtlc.class),mock(SwapDb.class),wallet,ui,mock(SwapEngine.Notifier.class));
            engine.setNetwork(rpc,EthNet.MAINNET);engine.setMyMinimaPk(o.minimaPublicKey);
            Field field=SwapEngine.class.getDeclaredField("io");field.setAccessible(true);((ExecutorService)field.get(engine)).shutdownNow();field.set(engine,worker);
            doAnswer(i->{queue.add(i.getArgument(0));return null;}).when(worker).execute(any());
            doAnswer(i->{balance=i.getArgument(1);return null;}).when(node).cmd(anyString(),any());
            when(sodium.cryptoSignDetached(any(),any(),anyLong(),any())).thenAnswer(i->{signed=new String((byte[])i.getArgument(1),StandardCharsets.UTF_8);return true;});
            funds(5_000_000_000L,5_000_000_000L);
        }
        void funds(long latest,long pending)throws Exception {
            doAnswer(i->{
                if("eth_chainId".equals(i.getArgument(0)))return "0x1";
                JSONArray args=i.getArgument(1);
                if("eth_getBlockByNumber".equals(i.getArgument(0)))return new JSONObject().put("number","0x64").put("hash",blockHash);
                if("eth_getTransactionCount".equals(i.getArgument(0))){assertEquals("0x64",args.getString(1));return "0x"+minedNonce.toString(16);}
                assertEquals("eth_call",i.getArgument(0));assertEquals(EthNet.MAINNET.token("USDT").address,args.getJSONObject(0).getString("to"));
                String data=args.getJSONObject(0).getString("data");assertTrue(data.startsWith("0x70a08231"));assertTrue(data.endsWith(owner.getAddress().substring(2)));
                return word("pending".equals(args.getString(1))?pending:latest);
            }).when(rpc).call(anyString(),any());
        }
        void start(){engine.publishFresh(sodium,identity,o,PublishGate.gen(),cb);}
        void run(){queue.remove(0).run();}
        void zeroMinima()throws Exception {assertNotNull(balance);balance.onResult(new JSONObject().put("status",true).put("response",new JSONArray()));}
        void change(String reason){
            switch(reason){
                case "market":TradingContext.setActive(TradingContext.MXUSDT,null);break;
                case "generation":PublishGate.bumpGen();break;
                case "wallet":when(wallet.creds()).thenReturn(Credentials.create("02"));break;
                case "rpc":engine.setNetwork(mock(EthRpc.class),EthNet.MAINNET);break;
                case "key":engine.setMyMinimaPk("0xBB");break;
                case "shutdown":when(worker.isShutdown()).thenReturn(true);break;
            }
        }
        void saveSend(String to,String data,String operation,boolean unknown)throws Exception {
            EthereumTransactions.Rpc transport=new EthereumTransactions.Rpc(){
                public BigInteger chain(){return BigInteger.ONE;}
                public BigInteger pending(String address){return BigInteger.valueOf(4);}
                public String broadcast(String raw)throws Exception{if(unknown)throw new java.io.IOException("lost response");return org.web3j.crypto.Hash.sha3(raw);}
                public JSONObject receipt(String hash){return null;}
                public JSONObject transaction(String hash){return null;}
            };
            try {journal.send(transport,owner,1,to,data,BigInteger.ZERO,BigInteger.valueOf(400000),BigInteger.valueOf(1000000000),operation,()->{});}
            catch(EthereumTransactions.Uncertain expected){if(!unknown)throw expected;}
        }
        public void close(){engine.shutdown();try{shared.set(null,previous);}catch(Exception error){throw new AssertionError(error);}}
    }
    @Test public void exhaustedAndPendingSpentBidsCannotBeRefreshedFromCachedBalance()throws Exception {
        for(long[] funds:new long[][]{{0,0},{5_000_000_000L,0},{0,5_000_000_000L}})
        try(Fixture f=new Fixture();MockedStatic<CommsTransport> t=mockStatic(CommsTransport.class)) {
            f.funds(funds[0],funds[1]);f.start();assertNull(f.balance);f.run();f.zeroMinima();
            assertNotNull(f.signed);Order sent=Order.fromJson(new JSONObject(f.signed));assertFalse(sent.hasLiquidity());
            assertEquals(0,sent.usdtAvail,0);assertEquals(0,new JSONObject(f.signed).getJSONObject("pairs").getJSONObject("USDT").getDouble("sell"),0);
        }
    }
    @Test public void realFreshBalanceReplacesCacheAndPreservesFundedBid()throws Exception {
        try(Fixture f=new Fixture();MockedStatic<CommsTransport> t=mockStatic(CommsTransport.class)) {
            f.o.usdtAvail=0;f.start();f.run();f.zeroMinima();Order sent=Order.fromJson(new JSONObject(f.signed));
            assertEquals(5000,sent.usdtAvail,0);assertEquals(1,sent.effectiveBids("USDT").size());verify(f.rpc,times(6)).call(anyString(),any());
        }
    }
    @Test public void malformedOrWrongChainReplyCannotSignOrPublish()throws Exception {
        for(Object reply:new Object[]{null,"0x","0x0","0x"+"g".repeat(64),word(1)+"00",BigInteger.ZERO})
        try(Fixture f=new Fixture();MockedStatic<CommsTransport> t=mockStatic(CommsTransport.class)) {
            when(f.rpc.call(eq("eth_call"),any())).thenReturn(reply);f.start();f.run();
            verify(f.cb).onFailed(SwapOrderBook.BALANCE_ERROR);verifyNoInteractions(f.node,f.sodium);t.verifyNoInteractions();
        }
        try(Fixture f=new Fixture()) {
            when(f.rpc.call(eq("eth_chainId"),any())).thenReturn("0x2");f.start();f.run();verify(f.cb).onFailed(SwapOrderBook.BALANCE_ERROR);verifyNoInteractions(f.node,f.sodium);
        }
    }
    @Test public void networkErrorCannotReuseCachedFunds()throws Exception {
        try(Fixture f=new Fixture()) {
            when(f.rpc.call(eq("eth_call"),any())).thenThrow(new java.io.IOException("offline"));f.start();f.run();
            verify(f.cb).onFailed(SwapOrderBook.BALANCE_ERROR);verifyNoInteractions(f.node,f.sodium);
        }
    }
    @Test public void queuedContextChangesCancelBeforeAnyReads()throws Exception {
        for(String reason:new String[]{"market","generation","wallet","rpc","key","shutdown"}) {
            TradingContext.setActive(TradingContext.MINIMA,null);
            try(Fixture f=new Fixture()){f.start();f.change(reason);f.run();verify(f.cb).onFailed(SwapOrderBook.MARKET_ERROR);verifyNoInteractions(f.node,f.rpc,f.sodium);}
        }
    }
    @Test public void contextChangesDuringMinimaReadCannotSign()throws Exception {
        for(String reason:new String[]{"market","generation","wallet","rpc","key","shutdown"}) {
            TradingContext.setActive(TradingContext.MINIMA,null);
            try(Fixture f=new Fixture();MockedStatic<CommsTransport> t=mockStatic(CommsTransport.class)) {
                f.start();f.run();f.change(reason);f.zeroMinima();verify(f.cb).onFailed(SwapOrderBook.MARKET_ERROR);verifyNoInteractions(f.sodium);t.verifyNoInteractions();
            }
        }
    }
    @Test public void authorizationReachesDelayedPosting()throws Exception {
        try(Fixture f=new Fixture();MockedStatic<CommsTransport> t=mockStatic(CommsTransport.class)) {
            final BooleanSupplier[] gate=new BooleanSupplier[1];
            t.when(()->CommsTransport.postBlob(any(),anyString(),anyString(),anyString(),anyString(),any(),any(),any())).thenAnswer(i->{gate[0]=i.getArgument(6);return null;});
            f.start();f.run();f.zeroMinima();assertNotNull(gate[0]);assertTrue(gate[0].getAsBoolean());f.change("wallet");assertFalse(gate[0].getAsBoolean());
        }
    }
    @Test public void askOnlyPublicationDoesNotDependOnEthereumAvailability()throws Exception {
        try(Fixture f=new Fixture();MockedStatic<CommsTransport> t=mockStatic(CommsTransport.class)) {
            f.o.pairs.get("USDT").bids.clear();f.o.pairs.get("USDT").sell=0;f.o.pairs.get("USDT").buy=1;
            f.start();assertTrue(f.queue.isEmpty());f.zeroMinima();assertNotNull(f.signed);verifyNoInteractions(f.rpc);assertEquals(0,f.o.usdtAvail,0);
        }
    }
    @Test public void decimalCostClampsCumulativeBestPricePrefixAndClearsScalar()throws Exception {
        Order o=order();Order.Pair p=o.pairs.get("USDT");p.bids.clear();p.min=0;p.bids.add(new Order.Level(2,0.1));p.bids.add(new Order.Level(1,0.1));
        o.clampBidsToBalance(new BigDecimal("0.3"));assertEquals(2,p.bids.size());
        o.clampBidsToBalance(new BigDecimal("0.299999999999"));assertEquals(1,p.bids.size());
        o.clampBidsToBalance(BigDecimal.ZERO);assertEquals(0,p.sell,0);assertTrue(o.effectiveBids("USDT").isEmpty());
    }
    @Test public void readersRejectOldUnbackedLaddersAndScalarOrders()throws Exception {
        for(boolean ladder:new boolean[]{true,false})for(double balance:new double[]{0,0.5}) {
            Order o=order();o.usdtAvail=balance;if(!ladder)o.pairs.get("USDT").bids.clear();
            assertFalse(SwapOrderBook.makerLive(Order.fromJson(o.toJson()),"USDT",true));
        }
    }
    @Test public void configurationRetainsDepthForFutureFunding()throws Exception {
        Order o=order();String saved=Order.toConfigJson(o);o.clampBidsToBalance(BigDecimal.ZERO);
        Order restored=Order.fromConfigJson(saved);restored.clampBidsToBalance(new BigDecimal("5000"));assertEquals(1,restored.effectiveBids("USDT").size());
    }
    @Test public void locallyUnknownTransferCannotBackARefreshedBid()throws Exception {
        try(Fixture f=new Fixture();MockedStatic<CommsTransport> t=mockStatic(CommsTransport.class)){
            f.saveSend(EthNet.MAINNET.token("USDT").address,EthSend.buildTransferData("0x"+"44".repeat(20),BigInteger.valueOf(5_000_000_000L)),null,true);
            f.start();f.run();f.zeroMinima();assertEquals(0,Order.fromJson(new JSONObject(f.signed)).usdtAvail,0);assertFalse(Order.fromJson(new JSONObject(f.signed)).hasLiquidity());
        }
    }
    @Test public void localDebitAndRpcPendingDebitAreNotCountedTwice()throws Exception {
        try(Fixture f=new Fixture();MockedStatic<CommsTransport> t=mockStatic(CommsTransport.class)){
            f.funds(5_000_000_000L,4_000_000_000L);f.o.pairs.get("USDT").bids.get(0).amount=4000;
            f.saveSend(EthNet.MAINNET.token("USDT").address,EthSend.buildTransferData("0x"+"44".repeat(20),BigInteger.valueOf(1_000_000_000L)),null,true);
            f.start();f.run();f.zeroMinima();assertEquals(4000,Order.fromJson(new JSONObject(f.signed)).usdtAvail,0);assertTrue(Order.fromJson(new JSONObject(f.signed)).hasLiquidity());
        }
    }
    @Test public void minedNonceExcludesSpendsAlreadyReflectedInExactBlockBalance()throws Exception {
        try(Fixture f=new Fixture();MockedStatic<CommsTransport> t=mockStatic(CommsTransport.class)){
            f.funds(4_000_000_000L,4_000_000_000L);f.minedNonce=BigInteger.valueOf(5);f.o.pairs.get("USDT").bids.get(0).amount=4000;
            f.saveSend(EthNet.MAINNET.token("USDT").address,EthSend.buildTransferData("0x"+"44".repeat(20),BigInteger.valueOf(1_000_000_000L)),null,true);
            f.start();f.run();f.zeroMinima();assertEquals(4000,Order.fromJson(new JSONObject(f.signed)).usdtAvail,0);
        }
    }
    @Test public void newLocalSendInvalidatesMinimaReadAndDelayedPostGuard()throws Exception {
        for(boolean afterSigning:new boolean[]{false,true})
        try(Fixture f=new Fixture();MockedStatic<CommsTransport> t=mockStatic(CommsTransport.class)){
            final BooleanSupplier[] gate=new BooleanSupplier[1];
            t.when(()->CommsTransport.postBlob(any(),anyString(),anyString(),anyString(),anyString(),any(),any(),any())).thenAnswer(i->{gate[0]=i.getArgument(6);return null;});
            f.start();f.run();if(afterSigning)f.zeroMinima();
            f.saveSend(EthNet.MAINNET.token("USDT").address,EthSend.buildTransferData("0x"+"44".repeat(20),BigInteger.ONE),null,false);
            if(afterSigning){assertNotNull(gate[0]);assertFalse(gate[0].getAsBoolean());}
            else{f.zeroMinima();assertNull(f.signed);verify(f.cb).onFailed(SwapOrderBook.MARKET_ERROR);}
        }
    }
    @Test public void changedBalanceBlockCannotSignAnOrder()throws Exception {
        try(Fixture f=new Fixture()){
            doAnswer(i->{JSONArray params=i.getArgument(1);return new JSONObject().put("number","0x64").put("hash","latest".equals(params.getString(0))?f.blockHash:"0x"+"cd".repeat(32));}).when(f.rpc).call(eq("eth_getBlockByNumber"),any());
            f.start();f.run();assertNull(f.balance);verify(f.cb).onFailed(SwapOrderBook.BALANCE_ERROR);
        }
    }
    @Test public void unknownSavedCalldataCannotFallBackToCachedFunds()throws Exception {
        try(Fixture f=new Fixture()){
            f.saveSend("0x"+"44".repeat(20),"0x12345678",null,true);f.start();f.run();assertNull(f.balance);verify(f.cb).onFailed(SwapOrderBook.BALANCE_ERROR);
        }
    }

    @Test public void malformedAnchoredBlockAndNonceCannotSignOrPublish()throws Exception {
        for(Object block:new Object[]{null,"bad",new JSONObject(),new JSONObject().put("number","0x64").put("hash","0x"+"00".repeat(32)),new JSONObject().put("number",100).put("hash","0x"+"ab".repeat(32))})
        try(Fixture f=new Fixture()){
            doReturn(block).when(f.rpc).call(eq("eth_getBlockByNumber"),any());f.start();f.run();verify(f.cb).onFailed(SwapOrderBook.BALANCE_ERROR);verifyNoInteractions(f.node,f.sodium);
        }
        try(Fixture f=new Fixture()){
            doReturn("0x10000000000000000").when(f.rpc).call(eq("eth_getTransactionCount"),any());f.start();f.run();verify(f.cb).onFailed(SwapOrderBook.BALANCE_ERROR);verifyNoInteractions(f.node,f.sodium);
        }
    }

}
