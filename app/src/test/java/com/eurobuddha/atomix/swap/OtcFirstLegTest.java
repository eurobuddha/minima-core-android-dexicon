// VENDORED VERBATIM from apks/atomix 0.1.83 app/src/test/java/com/eurobuddha/atomix/swap/OtcFirstLegTest.java by scripts/vendor-atomix.sh — do not edit here; re-vendor.
package com.eurobuddha.atomix.swap;

import android.database.sqlite.*;
import android.os.Handler;
import com.eurobuddha.atomix.TradingContext;
import com.eurobuddha.atomix.eth.*;
import java.lang.reflect.Field;
import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.ExecutorService;
import org.junit.*;
import org.junit.runner.RunWith;
import org.mockito.MockedConstruction;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.web3j.crypto.Credentials;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Real controller, engine and SQLite journals; all chain/network calls are mocked. */
@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE)
public class OtcFirstLegTest {
    static final String HASH="0x"+"ab".repeat(32), SECRET="0x"+"cd".repeat(32);
    static class Fixture implements AutoCloseable {
        final OtcConsentTest consent=new OtcConsentTest();
        final MinimaHtlc minima=mock(MinimaHtlc.class);
        final EthWallet wallet=mock(EthWallet.class);final EthRpc rpc=mock(EthRpc.class);
        final Handler ui=mock(Handler.class);final ExecutorService worker=mock(ExecutorService.class);
        final List<Runnable> queued=new ArrayList<>();final List<MinimaHtlc.PostCb> posted=new ArrayList<>();
        final SwapDb swaps;final SQLiteOpenHelper helper;final SwapEngine engine;
        final OtcController controller;final OtcDb.Deal deal;
        Fixture(boolean sell)throws Exception {
            consent.setup();consent.context.deleteDatabase("atomix.db");swaps=new SwapDb(consent.context);
            Field storage=SwapDb.class.getDeclaredField("helper");storage.setAccessible(true);helper=(SQLiteOpenHelper)storage.get(swaps);
            when(wallet.ready()).thenReturn(true);when(minima.ready()).thenReturn(true);
            when(minima.activeToken()).thenReturn(TradingContext.MXUSDT.tokenId);
            when(wallet.address()).thenReturn("0x"+"12".repeat(20));when(wallet.creds()).thenReturn(Credentials.create("01"));
            when(rpc.latestBlockTimestamp()).thenReturn(System.currentTimeMillis()/1000);
            when(ui.post(any())).thenAnswer(i->{((Runnable)i.getArgument(0)).run();return true;});
            doAnswer(i->{((MinimaHtlc.SecretCb)i.getArgument(0)).ok(SECRET,HASH);return null;}).when(minima).generateSecret(any());
            doAnswer(i->{((MinimaHtlc.BlockCb)i.getArgument(0)).ok(100);return null;}).when(minima).currentBlock(any());
            doAnswer(i->{posted.add(i.getArgument(8));return null;}).when(minima).lock(anyString(),anyString(),anyString(),anyString(),anyString(),anyString(),anyInt(),anyString(),any());
            engine=new SwapEngine(consent.node,minima,swaps,wallet,ui,mock(SwapEngine.Notifier.class));
            engine.setMyMinimaPk("0x1234");engine.setNetwork(rpc,EthNet.MAINNET);
            Field io=SwapEngine.class.getDeclaredField("io");io.setAccessible(true);((ExecutorService)io.get(engine)).shutdownNow();io.set(engine,worker);
            doAnswer(i->{queued.add(i.getArgument(0));return null;}).when(worker).execute(any());
            controller=consent.controller(consent.db,engine);
            OtcDb.Deal d=consent.create("first leg",OtcDb.ST_AGREED);
            if(sell){consent.sql("UPDATE otc_deals SET side=? WHERE ref=?",OtcOffer.LP_BUYS_MINIMA,d.ref);d=consent.db.getDeal(d.ref);}deal=d;
        }
        void start(){controller.executeDeal(deal);while(!queued.isEmpty())queued.remove(0).run();}
        void assertDurableHash(){assertEquals(OtcDb.ST_EXECUTING,consent.db.getDeal(deal.ref).status);assertEquals(OtcDb.normHash(HASH),consent.db.getDeal(deal.ref).hash);}
        public void close(){engine.shutdown();helper.close();consent.context.deleteDatabase("atomix.db");consent.cleanup();}
    }
    @Test public void lostMinimaReplyCannotReopenAgreementOrGenerateASecondLock()throws Exception { lostReply(true); }
    @Test public void lostEthereumReplyCannotReopenAgreementOrGenerateASecondLock()throws Exception { lostReply(false); }
    private void lostReply(boolean sell)throws Exception {
        try(Fixture f=new Fixture(sell);
                MockedConstruction<EthHtlc> eth=mockConstruction(EthHtlc.class,(mock,c)->{
                    when(mock.allowance(anyString())).thenReturn(BigInteger.TEN.pow(30));
                    when(mock.newContract(anyString(),anyString(),anyString(),any(),anyString(),any(),any(),anyBoolean()))
                        .thenThrow(new java.io.IOException("public fixture: response lost"));
                })) {
            f.start();if(sell){assertEquals(1,f.posted.size());f.posted.get(0).err("public fixture: response lost");}
            f.assertDurableHash();assertNotNull(f.swaps.getSwap(HASH));assertEquals(SECRET,f.swaps.getSecret(HASH));
            f.consent.db.close();f.consent.db=new OtcDb(f.consent.context);
            f.consent.controller(f.consent.db,f.engine).executeDeal(f.consent.db.getDeal(f.deal.ref));
            verify(f.minima,times(1)).generateSecret(any());f.assertDurableHash();
        }
    }
    @Test public void recoveryIdentityIsDurableBeforeEitherFirstLegIsDispatched()throws Exception {
        for(boolean sell:new boolean[]{true,false})try(Fixture f=new Fixture(sell);
                MockedConstruction<EthHtlc> eth=mockConstruction(EthHtlc.class,(mock,c)->{
                    when(mock.allowance(anyString())).thenReturn(BigInteger.TEN.pow(30));
                    when(mock.newContract(anyString(),anyString(),anyString(),any(),anyString(),any(),any(),anyBoolean()))
                        .thenAnswer(i->{f.assertDurableHash();assertNotNull(f.swaps.getSwap(HASH));return "0x"+"11".repeat(32);});
                })) {
            if(sell)doAnswer(i->{f.assertDurableHash();assertNotNull(f.swaps.getSwap(HASH));((MinimaHtlc.PostCb)i.getArgument(8)).err("fixture cleanup");return null;})
                .when(f.minima).lock(anyString(),anyString(),anyString(),anyString(),anyString(),anyString(),anyInt(),anyString(),any());
            f.start();f.assertDurableHash();
        }
    }
    @Test public void failedDealPersistencePreventsBothFirstLegs()throws Exception {
        for(boolean sell:new boolean[]{true,false})try(Fixture f=new Fixture(sell);
                MockedConstruction<EthHtlc> eth=mockConstruction(EthHtlc.class,(mock,c)->when(mock.allowance(anyString())).thenReturn(BigInteger.TEN.pow(30)))) {
            f.consent.sql("CREATE TRIGGER deny_hash BEFORE UPDATE ON otc_deals WHEN NEW.hash != '' BEGIN SELECT RAISE(ABORT,'public fixture'); END");
            f.start();assertTrue(f.posted.isEmpty());
            if(!sell)verify(eth.constructed().get(0),never()).newContract(anyString(),anyString(),anyString(),any(),anyString(),any(),any(),anyBoolean());
            assertEquals(OtcDb.ST_AGREED,f.consent.db.getDeal(f.deal.ref).status);
        }
    }
    @Test public void missingRecoveryRecordsCannotSubmitOrLeavePartialJournals()throws Exception {
        for(boolean sell:new boolean[]{true,false})for(String table:new String[]{"secrets","myhtlc","swaps"})try(Fixture f=new Fixture(sell);
                MockedConstruction<EthHtlc> eth=mockConstruction(EthHtlc.class,(mock,c)->when(mock.allowance(anyString())).thenReturn(BigInteger.TEN.pow(30)))) {
            f.helper.getWritableDatabase().execSQL("CREATE TRIGGER deny_record BEFORE INSERT ON "+table+" BEGIN SELECT RAISE(IGNORE); END");
            f.start();assertTrue(f.posted.isEmpty());
            if(!sell)verify(eth.constructed().get(0),never()).newContract(anyString(),anyString(),anyString(),any(),anyString(),any(),any(),anyBoolean());
            assertEquals(OtcDb.ST_AGREED,f.consent.db.getDeal(f.deal.ref).status);
            assertNull(f.swaps.getSwap(HASH));assertNull(f.swaps.getSecret(HASH));assertNull(f.swaps.getRequest(HASH));
        }
    }

}
