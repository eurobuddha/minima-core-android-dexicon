// VENDORED VERBATIM from apks/atomix 0.1.82 app/src/test/java/com/eurobuddha/atomix/swap/EthResponderContextTest.java by scripts/vendor-atomix.sh — do not edit here; re-vendor.
package com.eurobuddha.atomix.swap;

import android.os.Handler;
import com.eurobuddha.atomix.TradingContext;
import com.eurobuddha.atomix.eth.*;
import com.eurobuddha.comms.NodeApi;
import java.lang.reflect.*;
import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.function.BooleanSupplier;
import org.json.*;
import org.junit.*;
import org.mockito.MockedConstruction;
import org.web3j.crypto.Credentials;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

public class EthResponderContextTest {
    static final String HASH="0x"+"AB".repeat(32), PK="0x"+"CD".repeat(32), PEER="0x"+"12".repeat(20), TX="0x"+"34".repeat(32);
    TradingContext saved;
    @Before public void before(){saved=TradingContext.active();}
    @After public void after(){TradingContext.setActive(saved,null);}
    static final class Fixture implements AutoCloseable {
        final TradingContext market;final MinimaHtlc minima=mock(MinimaHtlc.class);final SwapDb db=mock(SwapDb.class);
        final EthWallet wallet=mock(EthWallet.class);final EthRpc rpc=mock(EthRpc.class);final Handler ui=mock(Handler.class);
        final SwapEngine engine;final Order order=new Order();final JSONObject coin;final List<Runnable> queue=new ArrayList<>();
        final ExecutorService worker=mock(ExecutorService.class);final List<SwapDb.Swap> rows=new ArrayList<>();
        final String token=EthNet.MAINNET.token("USDT").address;
        Fixture(TradingContext market) throws Exception {
            this.market=market;TradingContext.setActive(market,null);order.market=market;
            when(minima.activeToken()).thenReturn(market.tokenId);when(wallet.address()).thenReturn(PEER);
            when(wallet.creds()).thenReturn(Credentials.create("01"));when(wallet.ethBalanceWei(any())).thenReturn(BigInteger.TEN.pow(18));
            when(rpc.callStr(eq("eth_gasPrice"),any())).thenReturn("0x3b9aca00");when(rpc.baseFeePerGasOrZero()).thenReturn(BigInteger.ZERO);
            when(rpc.latestBlockTimestamp()).thenReturn(System.currentTimeMillis()/1000);
            when(ui.post(any())).thenAnswer(i->{((Runnable)i.getArgument(0)).run();return true;});
            engine=new SwapEngine(mock(NodeApi.class),minima,db,wallet,ui,mock(SwapEngine.Notifier.class));engine.setNetwork(rpc,EthNet.MAINNET);engine.setMyMinimaPk(PK);
            Field f=SwapEngine.class.getDeclaredField("io");f.setAccessible(true);((ExecutorService)f.get(engine)).shutdownNow();f.set(engine,worker);
            doAnswer(i->{queue.add(i.getArgument(0));return null;}).when(worker).execute(any());
            doAnswer(i->{when(worker.isShutdown()).thenReturn(true);List<Runnable> dropped=new ArrayList<>(queue);queue.clear();return dropped;}).when(worker).shutdownNow();
            doAnswer(i->{rows.add(i.getArgument(0));return null;}).when(db).upsertSwap(any());
            Order.Pair pair=new Order.Pair();pair.enable=true;pair.sell=1;order.pairs.put("USDT",pair);engine.setMyOrder(order);
            coin=new JSONObject().put("tokenid",market.tokenId).put("amount","1").put("tokenamount","1")
                .put("state",new JSONObject().put("0","0xEF").put("1","1").put("2",token).put("3","1000").put("4",PK).put("5",HASH).put("6",PEER).put("7","FALSE"));
        }
        void change(String cause) {
            switch(cause){
                case "market":TradingContext.setActive(market.other(),null);break;
                case "generation":PublishGate.bumpGen();break;
                case "shutdown":engine.shutdown();break;
                case "token":when(minima.activeToken()).thenReturn(market.other().tokenId);break;
                case "terms":order.pairs.get("USDT").sell=0.1;break;
                case "key":engine.setMyMinimaPk("0xAB");break;
                case "network":engine.setNetwork(mock(EthRpc.class),EthNet.MAINNET);break;
            }
        }
        void start(){engine.checkCanSwapCoin(coin,100);assertEquals(1,queue.size());}
        void run(){queue.remove(0).run();}
        public void close(){engine.shutdown();}
    }
    @Test public void supersededQueuedResponderCannotReadApprovePersistOrLock() throws Exception {
        for(TradingContext market:TradingContext.values())for(String cause:new String[]{"market","generation","shutdown","token","terms","key","network"}) {
            try(Fixture f=new Fixture(market);MockedConstruction<EthHtlc> h=mockConstruction(EthHtlc.class)) {
                f.start();f.change(cause);if(!f.queue.isEmpty())f.run();assertTrue(h.constructed().isEmpty());assertTrue(f.rows.isEmpty());
            }
        }
    }
    @Test public void queuedCoinIsDetachedFromTheScanResult() throws Exception {
        try(Fixture f=new Fixture(TradingContext.MINIMA);MockedConstruction<EthHtlc> h=mockConstruction(EthHtlc.class,(eth,c)->{
            when(eth.allowance(anyString())).thenReturn(BigInteger.TEN.pow(30));when(eth.newContract(anyString(),anyString(),anyString(),any(),anyString(),any(),any(),anyBoolean())).thenReturn(TX);
        })) {
            f.start();f.coin.put("amount","99").put("tokenamount","99");f.coin.getJSONObject("state").put("1","77");f.run();
            assertEquals(1,f.rows.size());assertEquals("1",f.rows.get(0).buyAmount);assertEquals("1",f.rows.get(0).sellAmount);
            verify(h.constructed().get(0)).newContract(eq(PK),eq(PEER),eq(HASH),any(),eq(f.token),eq(BigInteger.TEN.pow(6)),eq(BigInteger.TEN.pow(18)),eq(false));
        }
    }
    @Test public void changedContextDuringRpcCannotPersistOrBroadcast() throws Exception {
        for(String cause:new String[]{"market","generation","shutdown","terms"})try(Fixture f=new Fixture(TradingContext.MINIMA);
            MockedConstruction<EthHtlc> h=mockConstruction(EthHtlc.class,(eth,c)->{
                when(eth.allowance(anyString())).thenAnswer(i->{f.change(cause);return BigInteger.TEN.pow(30);});
            })) {
            f.start();f.run();assertTrue(f.rows.isEmpty());verify(h.constructed().get(0),never()).newContract(anyString(),anyString(),anyString(),any(),anyString(),any(),any(),anyBoolean());
        }
    }
    @Test public void guardReachesApproveAndLockAndReceiptsKeepOriginalMarket() throws Exception {
        for(TradingContext market:TradingContext.values())try(Fixture f=new Fixture(market)) {
            List<BooleanSupplier> guards=new ArrayList<>();
            try(MockedConstruction<EthHtlc> h=mockConstruction(EthHtlc.class,(eth,c)->{
                assertEquals(4,c.arguments().size());guards.add((BooleanSupplier)c.arguments().get(3));
                when(eth.allowance(anyString())).thenReturn(BigInteger.TEN.pow(30));
                when(eth.newContract(anyString(),anyString(),anyString(),any(),anyString(),any(),any(),anyBoolean())).thenAnswer(i->{
                    assertTrue(guards.get(0).getAsBoolean());assertEquals(1,f.rows.size());f.change("market");return TX;});
            })) {
                f.start();f.run();assertEquals(market.coinLabel,f.rows.get(0).buyToken);assertFalse(guards.get(0).getAsBoolean());
                verify(f.db).logEvent(eq(HASH),eq(SwapDb.EV_CPSENT),eq("ETH:"+f.token),eq("1"),eq(TX));
            }
        }
    }
    @Test public void shutdownReleasesQueuedMarkerAndCancelsOnlyOnce() throws Exception {
        Runnable obsolete;
        try(Fixture first=new Fixture(TradingContext.MINIMA);MockedConstruction<EthHtlc> h=mockConstruction(EthHtlc.class)) {
            first.start();obsolete=first.queue.get(0);first.engine.shutdown();obsolete.run();assertTrue(h.constructed().isEmpty());
            try(Fixture next=new Fixture(TradingContext.MINIMA)) {
                next.start();assertEquals(1,next.queue.size());obsolete.run();
                // A second obsolete cancellation must not release the newer host's marker.
                first.engine.shutdown();next.engine.checkCanSwapCoin(next.coin,100);assertEquals(1,next.queue.size());
            }
        }
    }
    @Test public void persistedOtcRejectionInvalidatesApprovalAndLockGuard() throws Exception {
        try(Fixture f=new Fixture(TradingContext.MINIMA)) {
            OtcDb store=mock(OtcDb.class);OtcDb.Deal deal=new OtcDb.Deal();deal.currency=f.market.key;
            deal.role=OtcDb.ROLE_LP;deal.status=OtcDb.ST_AGREED;deal.side=OtcOffer.LP_BUYS_MINIMA;
            deal.amount="1";deal.price="1";deal.peerEthAddr=PEER;deal.hash=HASH;
            when(store.dealByHash(HASH)).thenReturn(deal);f.engine.setOtcDb(store);f.coin.getJSONObject("state").put("7","TRUE");
            List<BooleanSupplier> guards=new ArrayList<>();
            try(MockedConstruction<EthHtlc> h=mockConstruction(EthHtlc.class,(eth,c)->{
                guards.add((BooleanSupplier)c.arguments().get(3));
                when(eth.allowance(anyString())).thenAnswer(i->{assertTrue(guards.get(0).getAsBoolean());deal.status=OtcDb.ST_REJECTED;return BigInteger.TEN.pow(30);});
            })) {
                f.start();f.run();assertFalse(guards.get(0).getAsBoolean());assertTrue(f.rows.isEmpty());
                verify(h.constructed().get(0),never()).newContract(anyString(),anyString(),anyString(),any(),anyString(),any(),any(),anyBoolean());
            }
        }
    }
    @Test public void persistenceFailurePreventsContractBroadcast() throws Exception {
        try(Fixture f=new Fixture(TradingContext.MINIMA);MockedConstruction<EthHtlc> h=mockConstruction(EthHtlc.class,(eth,c)->{
            when(eth.allowance(anyString())).thenReturn(BigInteger.TEN.pow(30));
        })) {
            doThrow(new IllegalStateException("fixture full")).when(f.db).upsertSwap(any());f.start();f.run();
            verify(h.constructed().get(0),never()).newContract(anyString(),anyString(),anyString(),any(),anyString(),any(),any(),anyBoolean());
        }
    }
}
