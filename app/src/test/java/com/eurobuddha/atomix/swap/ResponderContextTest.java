// VENDORED VERBATIM from apks/atomix 0.1.84 app/src/test/java/com/eurobuddha/atomix/swap/ResponderContextTest.java by scripts/vendor-atomix.sh — do not edit here; re-vendor.
package com.eurobuddha.atomix.swap;

import android.os.Handler;
import com.eurobuddha.atomix.TradingContext;
import com.eurobuddha.atomix.eth.*;
import com.eurobuddha.comms.NodeApi;
import java.lang.reflect.*;
import java.math.BigInteger;
import java.util.*;
import java.util.function.*;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE)
public class ResponderContextTest {
    TradingContext saved;
    static final String HASH="0x"+"AB".repeat(32);
    @Before public void before() throws Exception {saved=TradingContext.active();clear();}
    @After public void after() throws Exception {clear();TradingContext.setActive(saved,null);}
    static Map map(String name) throws Exception {Field f=SwapEngine.class.getDeclaredField(name);f.setAccessible(true);return (Map)f.get(null);}
    static void clear() throws Exception {for(String k:new String[]{"CP_LOCKING","cpInFlight","cpLockSince","cpAttempts"})map(k).clear();}
    static final class Fixture implements AutoCloseable {
        final TradingContext market;
        final Handler ui=mock(Handler.class);final MinimaHtlc minima=mock(MinimaHtlc.class);final SwapDb db=mock(SwapDb.class);
        final SwapEngine.Notifier notifier=mock(SwapEngine.Notifier.class);final EthWallet wallet=mock(EthWallet.class);
        final List<Runnable> queued=new ArrayList<>();final List<Consumer<JSONArray>> reads=new ArrayList<>();
        final List<Consumer<String>> errors=new ArrayList<>();final List<MinimaHtlc.PostCb> posts=new ArrayList<>();
        final List<BooleanSupplier> guards=new ArrayList<>();final List<SwapDb.Swap> rows=new ArrayList<>();
        final SwapEngine engine;final EthHtlc.Contract c=new EthHtlc.Contract();final Order order=new Order();
        Fixture(TradingContext market) throws Exception {
            this.market=market;TradingContext.setActive(market,null);order.market=market;
            when(ui.post(any())).thenAnswer(i->{queued.add(i.getArgument(0));return true;});
            when(minima.activeToken()).thenReturn(market.tokenId);when(wallet.address()).thenReturn("0x"+"CD".repeat(20));
            doAnswer(i->{reads.add(i.getArgument(0));errors.add(i.getArgument(1));return null;}).when(minima).myFreeCoins(any(),any());
            doAnswer(i->{guards.add(i.getArgument(10));posts.add(i.getArgument(11));return null;}).when(minima)
                .lockFromCoins(anyList(),anyString(),anyString(),anyString(),anyString(),anyString(),anyString(),anyString(),anyInt(),anyString(),any(),any());
            doAnswer(i->{rows.add(i.getArgument(0));return null;}).when(db).upsertSwap(any());
            engine=new SwapEngine(mock(NodeApi.class),minima,db,wallet,ui,notifier);engine.setNetwork(mock(EthRpc.class),EthNet.MAINNET);
            Order.Pair pair=new Order.Pair();pair.enable=true;pair.buy=1;order.pairs.put("USDT",pair);engine.setMyOrder(order);
            c.hashlock=HASH;c.minimaPublicKey="0x1234";c.tokenContract=EthNet.MAINNET.token("USDT").address;
            c.requestAmount=BigInteger.TEN.pow(18);c.amount=BigInteger.TEN.pow(6);c.contractId="0x5678";
        }
        void start() throws Exception {start(market,PublishGate.gen());}
        void start(TradingContext original,long generation) throws Exception {
            map("CP_LOCKING").put(HASH,System.currentTimeMillis()/1000);map("cpInFlight").put(HASH,"");
            Method lock=SwapEngine.class.getDeclaredMethod("lockMinimaCounterLeg",EthHtlc.Contract.class,int.class,TradingContext.class,long.class);
            lock.setAccessible(true);lock.invoke(engine,c,100,original,generation);
        }
        void runUi(){queued.remove(0).run();}
        void coins(int index) throws Exception {reads.get(index).accept(new JSONArray().put(new JSONObject().put("coinid","0xA1").put("amount","2").put("tokenamount","2")));}
        void change(String cause) {
            switch(cause) {
                case "market":TradingContext.setActive(market.other(),null);break;
                case "generation":PublishGate.bumpGen();break;
                case "shutdown":engine.shutdown();break;
                case "token":when(minima.activeToken()).thenReturn(market.other().tokenId);break;
                case "terms":order.pairs.get("USDT").buy=10;break;
            }
        }
        public void close(){engine.shutdown();}
    }
    @Test public void queuedAndDelayedReadsStopAfterContextChanges() throws Exception {
        for(TradingContext market:TradingContext.values())for(String cause:new String[]{"market","generation","shutdown","token","terms"})for(boolean delayed:new boolean[]{false,true}) {
            clear();try(Fixture f=new Fixture(market)) {
                f.start();if(delayed)f.runUi();f.change(cause);if(delayed)f.coins(0);else f.runUi();
                assertTrue(f.rows.isEmpty());assertTrue(f.posts.isEmpty());assertTrue(map("cpInFlight").isEmpty());
                if(!delayed)assertTrue(f.reads.isEmpty());
            }
        }
    }
    @Test public void admissionRetainsItsOriginalMarketAndGeneration() throws Exception {
        for(boolean generation:new boolean[]{false,true})try(Fixture f=new Fixture(TradingContext.MINIMA)) {
            long gen=PublishGate.gen();if(generation)PublishGate.bumpGen();else TradingContext.setActive(TradingContext.MXUSDT,null);
            f.start(TradingContext.MINIMA,gen);assertTrue(f.queued.isEmpty());assertTrue(f.posts.isEmpty());assertTrue(map("cpInFlight").isEmpty());
        }
    }
    @Test public void unchangedResponsesPersistOriginalMarketBeforeLockAndPreserveReceipt() throws Exception {
        for(TradingContext market:TradingContext.values()) {
            clear();try(Fixture f=new Fixture(market)) {
                f.start();f.runUi();f.coins(0);assertEquals(1,f.rows.size());assertEquals(market.coinLabel,f.rows.get(0).sellToken);
                assertTrue(f.guards.get(0).getAsBoolean());f.change("market");assertFalse(f.guards.get(0).getAsBoolean());
                f.posts.get(0).ok("0x"+"44".repeat(32));verify(f.notifier).notify(eq("Locked your "+market.coinLabel),anyString());
                verify(f.db).logEvent(eq(HASH),eq(SwapDb.EV_CPSENT),eq("minima"),eq("1"),anyString());
            }
        }
    }
    @Test public void oldReadAndErrorCannotReleaseOrOverwriteNewReservation() throws Exception {
        try(Fixture f=new Fixture(TradingContext.MINIMA)) {
            f.start();f.runUi();clear();f.start();f.runUi();Object owner=map("cpAttempts").get(HASH);
            f.coins(0);f.errors.get(0).accept("late failure");assertSame(owner,map("cpAttempts").get(HASH));assertTrue(f.rows.isEmpty());
            f.coins(1);assertEquals(1,f.rows.size());assertEquals(1,f.posts.size());
        }
    }
    @Test public void stoppedHandlerReleasesReservationWithoutReadingCoins() throws Exception {
        try(Fixture f=new Fixture(TradingContext.MINIMA)) {
            when(f.ui.post(any())).thenReturn(false);f.start();assertTrue(f.reads.isEmpty());assertTrue(map("cpInFlight").isEmpty());
        }
    }
    @Test public void persistedOtcConsentIsRecheckedBeforeSigning() throws Exception {
        for(boolean delayed:new boolean[]{false,true}) {
            clear();try(Fixture f=new Fixture(TradingContext.MINIMA)) {
                OtcDb store=mock(OtcDb.class);OtcDb.Deal deal=new OtcDb.Deal();deal.currency=f.market.key;
                deal.role=OtcDb.ROLE_LP;deal.status=OtcDb.ST_AGREED;deal.side=OtcOffer.LP_SELLS_MINIMA;
                deal.amount="1";deal.price="1";deal.peerMinimaPk=f.c.minimaPublicKey;deal.hash=HASH;
                when(store.dealByHash(HASH)).thenReturn(deal);f.engine.setOtcDb(store);f.c.otc=true;f.c.receiver=f.wallet.address();
                f.start();f.runUi();if(!delayed)f.coins(0);
                deal.status=OtcDb.ST_REJECTED;
                if(delayed){f.coins(0);assertTrue(f.rows.isEmpty());assertTrue(f.posts.isEmpty());}
                else assertFalse(f.guards.get(0).getAsBoolean());
            }
        }
    }
    @Test public void failedPersistenceNeverQueuesSigning() throws Exception {
        try(Fixture f=new Fixture(TradingContext.MINIMA)) {
            doThrow(new android.database.sqlite.SQLiteException("fixture full")).when(f.db).upsertSwap(any());
            f.start();f.runUi();f.coins(0);assertTrue(f.posts.isEmpty());assertTrue(map("cpInFlight").isEmpty());
        }
    }
    @Test public void changedTermsAfterReservationCannotAuthorizeQueuedSigning() throws Exception {
        try(Fixture f=new Fixture(TradingContext.MINIMA)) {
            f.start();f.runUi();f.coins(0);assertTrue(f.guards.get(0).getAsBoolean());
            f.change("terms");assertFalse(f.guards.get(0).getAsBoolean());
        }
    }
    @Test public void uncertainPostKeepsRowAndKnownBuildFailureCanReleaseIt() throws Exception {
        for(String failure:new String[]{"POSTED:reply unknown","Market changed; refresh and try again."}) {
            clear();try(Fixture f=new Fixture(TradingContext.MINIMA)) {
                f.start();f.runUi();f.coins(0);f.posts.get(0).err(failure);
                if(failure.startsWith("POSTED:"))verify(f.db,never()).deleteSwap(anyString());else verify(f.db).deleteSwap(HASH);
                assertTrue(map("cpInFlight").isEmpty());
            }
        }
    }
    @Test public void lateBuildFailureCannotDeleteNewAttemptRow() throws Exception {
        try(Fixture f=new Fixture(TradingContext.MINIMA)) {
            f.start();f.runUi();f.coins(0);clear();f.start();f.runUi();f.coins(1);Object owner=map("cpAttempts").get(HASH);
            f.posts.get(0).err("old failure");verify(f.db,never()).deleteSwap(anyString());assertSame(owner,map("cpAttempts").get(HASH));
        }
    }
}
