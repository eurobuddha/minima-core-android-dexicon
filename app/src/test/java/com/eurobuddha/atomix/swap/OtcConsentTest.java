// VENDORED VERBATIM from apks/atomix 0.1.84 app/src/test/java/com/eurobuddha/atomix/swap/OtcConsentTest.java by scripts/vendor-atomix.sh — do not edit here; re-vendor.
package com.eurobuddha.atomix.swap;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import com.eurobuddha.atomix.TradingContext;
import com.eurobuddha.comms.*;
import java.util.*;
import java.util.concurrent.*;
import org.json.JSONObject;
import org.junit.*;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** NFT TransactionJournalTest's real SQLite fixture, with mocked chains and transport only. */
@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE)
public class OtcConsentTest {
    Context context; OtcDb db, other; TradingContext saved;
    NodeApi node; CryptoProvider crypto; SwapEngine engine; OtcController controller;
    static class Result implements OtcController.SendResult {
        int successes; String error;
        public void ok(){successes++;} public void err(String message){error=message;}
    }
    @Before public void setup() {
        saved=TradingContext.active();TradingContext.setActive(TradingContext.MXUSDT,null);
        context=RuntimeEnvironment.getApplication();context.deleteDatabase("otc.db");
        db=new OtcDb(context);other=new OtcDb(context);
        node=mock(NodeApi.class);crypto=mock(CryptoProvider.class);engine=mock(SwapEngine.class);
        when(crypto.seal(anyString(),any())).thenReturn("public fixture");
        controller=controller(db,engine);
    }
    @After public void cleanup(){db.close();other.close();context.deleteDatabase("otc.db");TradingContext.setActive(saved,null);}
    OtcController controller(OtcDb store,SwapEngine swaps) {
        CommsIdentity identity=mock(CommsIdentity.class);when(identity.publicId()).thenReturn("me");
        OtcController c=new OtcController(node,store,swaps,mock(OtcController.Ui.class));
        c.setSelf(crypto,identity,"0x1234","0x"+"12".repeat(20));return c;
    }
    OtcDb.Deal create(String ref,String status) {
        OtcDb.Deal d=new OtcDb.Deal();d.ref=ref;d.currency="mxusdt";d.role=OtcDb.ROLE_INSTIGATOR;
        d.peerCommsId="peer";d.peerMinimaPk="0x5678";d.peerEthAddr="0x"+"34".repeat(20);
        d.side=OtcOffer.LP_SELLS_MINIMA;d.amount="2";d.price="3";d.status=status;d.whoseTurn=OtcDb.TURN_ME;
        assertTrue(db.createDeal(d));assertTrue(OtcDb.sameSnapshot(d,db.getDeal(ref)));return d;
    }
    void change(OtcDb.Deal before,java.util.function.Consumer<OtcDb.Deal> edit) {
        OtcDb.Deal next=before.copy();edit.accept(next);assertTrue(other.replaceDeal(before,next));
    }
    void sql(String command,Object...args) {
        try(SQLiteDatabase sql=SQLiteDatabase.openDatabase(context.getDatabasePath("otc.db").getPath(),null,SQLiteDatabase.OPEN_READWRITE)){sql.execSQL(command,args);}
    }
    MockedStatic<CommsTransport> hold(List<CommsTransport.SendCb> callbacks) {
        MockedStatic<CommsTransport> transport=mockStatic(CommsTransport.class);
        transport.when(()->CommsTransport.postBlob(eq(node),anyString(),anyString(),anyString(),anyString(),isNull(),any()))
                .thenAnswer(i->{callbacks.add(i.getArgument(6));return null;});return transport;
    }
    OtcMessage message(OtcDb.Deal d,String type) {
        OtcMessage m=new OtcMessage();m.ref=d.ref;m.type=type;m.randomid=UUID.randomUUID().toString();
        m.from="peer";m.to="me";m.side=d.side;m.amount=d.amount;m.price=d.price;
        m.minimaPk=d.peerMinimaPk;m.ethAddr=d.peerEthAddr;m.hash="ab".repeat(32);return m;
    }
    void receive(OtcMessage m){assertTrue(controller.route("public coin",new Opened(true,"peer",m.toWire()),new JSONObject()));}
    SwapEngine.StartCb callback(SwapEngine target) {
        ArgumentCaptor<SwapEngine.StartCb> c=ArgumentCaptor.forClass(SwapEngine.StartCb.class);
        verify(target,atLeastOnce()).executeOtcDeal(any(),c.capture());return c.getValue();
    }
    @Test public void staleButtonsCannotReplaceNewTermsOrTerminalState() {
        int n=0;
        for(String change:new String[]{"terms","rejected","expired","deleted","changed back"})for(int action=0;action<3;action++){
            OtcDb.Deal shown=create("stale"+(n++),OtcDb.ST_COUNTERED);
            if(change.equals("deleted"))assertTrue(other.deleteDeal(shown));
            else change(shown,d->{if(change.equals("rejected"))d.status=OtcDb.ST_REJECTED;else if(change.equals("expired"))d.status=OtcDb.ST_EXPIRED;else d.price="7";});
            if(change.equals("changed back"))change(other.getDeal(shown.ref),d->d.price="3");
            OtcDb.Deal before=db.getDeal(shown.ref);Result result=new Result();
            if(action==0)controller.accept(shown,result);else if(action==1)controller.counter(shown,"8","9",result);else controller.reject(shown,result);
            assertEquals(OtcController.CHANGED_ERROR,result.error);assertEquals(0,result.successes);
            if(before==null)assertNull(db.getDeal(shown.ref));else assertTrue(OtcDb.sameSnapshot(before,db.getDeal(shown.ref)));
            assertEquals("3",shown.price);assertEquals(OtcDb.ST_COUNTERED,shown.status);assertTrue(db.msgs(shown.ref).isEmpty());
        }
        verifyNoInteractions(engine,node,crypto);
    }
    @Test public void everyPersistedConsentFieldIsCheckedEvenWhenTimestampIsUnchanged() {
        String[][] edits={{"amount","8"},{"price","9"},{"role","LP"},{"peercid","other"},{"peermpk","0x9999"},
                {"peereth","0xother"},{"side","BUY"},{"currency","minima"},{"status","EXPIRED"},{"whoseturn","PEER"},{"hash","ab"},{"created","1"}};
        for(int i=0;i<edits.length;i++){
            OtcDb.Deal shown=create("fields"+i,OtcDb.ST_COUNTERED);
            sql("UPDATE otc_deals SET "+edits[i][0]+"=? WHERE ref=?",edits[i][1],shown.ref);
            OtcDb.Deal before=db.getDeal(shown.ref);Result r=new Result();controller.accept(shown,r);
            assertEquals(edits[i][0],OtcController.CHANGED_ERROR,r.error);assertTrue(OtcDb.sameSnapshot(before,db.getDeal(shown.ref)));
        }
        verifyNoInteractions(engine,node,crypto);
    }
    @Test public void duplicateCreationMissingRowsAndStalePruningNeverReplaceHistory() {
        OtcDb.Deal d=create("identity",OtcDb.ST_COUNTERED),duplicate=d.copy();duplicate.price="99";
        assertFalse(other.createDeal(duplicate));assertEquals("3",db.getDeal(d.ref).price);
        change(d,x->x.price="4");assertFalse(other.deleteDeal(d));assertEquals("4",db.getDeal(d.ref).price);
        OtcDb.Deal latest=db.getDeal(d.ref);assertTrue(other.deleteDeal(latest));
        assertFalse(db.replaceDeal(latest,duplicate));assertNull(db.getDeal(d.ref));
        OtcDb.Deal unknown=d.copy();unknown.ref="unknown";unknown.currency=null;assertFalse(db.createDeal(unknown));
    }
    @Test public void acceptedSnapshotIsOwnedAndDuplicateSendCallbacksStartOnlyOnce() {
        List<CommsTransport.SendCb> sends=new ArrayList<>();
        try(MockedStatic<CommsTransport> transport=hold(sends)){
            OtcDb.Deal shown=create("accept",OtcDb.ST_COUNTERED);Result r=new Result();controller.accept(shown,r);
            assertNull(r.error);assertEquals(1,sends.size());assertEquals(OtcDb.ST_COUNTERED,shown.status);
            shown.amount="999";shown.peerEthAddr="replacement";
            sends.get(0).onSent("public txpow");sends.get(0).onSent("duplicate");
            verify(engine,times(1)).executeOtcDeal(argThat(d->d.amount.equals("2")&&d.price.equals("3")&&d.peerEthAddr.equals("0x"+"34".repeat(20))),any());
            assertEquals(OtcDb.ST_EXECUTING,db.getDeal("accept").status);
        }
    }
    @Test public void delayedAcceptCannotExecuteChangedAgreementOrRejection() {
        List<CommsTransport.SendCb> sends=new ArrayList<>();
        try(MockedStatic<CommsTransport> transport=hold(sends)){
            for(boolean reject:new boolean[]{true,false}){
                OtcDb.Deal shown=create("late"+reject,OtcDb.ST_COUNTERED);controller.accept(shown,new Result());
                change(db.getDeal(shown.ref),d->{if(reject)d.status=OtcDb.ST_REJECTED;else d.price="7";});
                OtcDb.Deal before=db.getDeal(shown.ref);sends.get(sends.size()-1).onSent("public txpow");
                assertTrue(OtcDb.sameSnapshot(before,db.getDeal(shown.ref)));
            }
            verifyNoInteractions(engine);
        }
    }
    @Test public void concurrentActivityAndServiceControllersStartExactlyOneEngine()throws Exception {
        OtcDb.Deal agreed=create("race",OtcDb.ST_AGREED);SwapEngine serviceEngine=mock(SwapEngine.class);
        OtcController service=controller(other,serviceEngine);
        ExecutorService workers=Executors.newFixedThreadPool(2);CountDownLatch start=new CountDownLatch(1);
        try {
            Future<?> a=workers.submit(()->{await(start);controller.executeDeal(agreed.copy());});
            Future<?> b=workers.submit(()->{await(start);service.executeDeal(agreed.copy());});
            start.countDown();a.get(10,TimeUnit.SECONDS);b.get(10,TimeUnit.SECONDS);
            long locks=mockingDetails(engine).getInvocations().stream().filter(i->i.getMethod().getName().equals("executeOtcDeal")).count()
                    +mockingDetails(serviceEngine).getInvocations().stream().filter(i->i.getMethod().getName().equals("executeOtcDeal")).count();
            assertEquals(1,locks);assertEquals(OtcDb.ST_EXECUTING,db.getDeal(agreed.ref).status);
        }finally{workers.shutdownNow();}
    }
    static void await(CountDownLatch latch){try{latch.await();}catch(InterruptedException e){Thread.currentThread().interrupt();throw new AssertionError(e);}}
    @Test public void lateExecutionCallbacksCannotChangeAnotherAttemptOrRecreateADeal() {
        List<CommsTransport.SendCb> sends=new ArrayList<>();
        try(MockedStatic<CommsTransport> transport=hold(sends)){
            OtcDb.Deal agreed=create("attempt",OtcDb.ST_AGREED);controller.executeDeal(agreed);
            SwapEngine.StartCb first=callback(engine);first.err("pre-lock failure");
            controller.executeDeal(db.getDeal(agreed.ref));SwapEngine.StartCb second=callback(engine);
            OtcDb.Deal running=db.getDeal(agreed.ref);first.err("late error");first.ok("11".repeat(32));
            assertTrue(OtcDb.sameSnapshot(running,db.getDeal(agreed.ref)));assertTrue(sends.isEmpty());
            assertTrue(second.onPrepared("22".repeat(32)));second.ok("22".repeat(32));assertEquals("22".repeat(32),db.getDeal(agreed.ref).hash);assertEquals(1,sends.size());
            second.err("duplicate error");second.ok("33".repeat(32));assertEquals("22".repeat(32),db.getDeal(agreed.ref).hash);
            assertTrue(other.deleteDeal(db.getDeal(agreed.ref)));second.ok("44".repeat(32));assertNull(db.getDeal(agreed.ref));
        }
    }
    @Test public void preparedHashSurvivesLostCallbackRestartAndRecoveryNotification() {
        List<CommsTransport.SendCb> sends=new ArrayList<>();
        try(MockedStatic<CommsTransport> transport=hold(sends)) {
            OtcDb.Deal agreed=create("lost result",OtcDb.ST_AGREED);controller.executeDeal(agreed);
            SwapEngine.StartCb cb=callback(engine);String hash="ab".repeat(32);
            assertTrue(cb.onPrepared("0x"+hash.toUpperCase(java.util.Locale.ROOT)));
            assertFalse(cb.onPrepared(hash));
            OtcDb.Deal prepared=db.getDeal(agreed.ref);assertEquals(hash,prepared.hash);assertTrue(sends.isEmpty());
            cb.err("response lost");cb.ok(hash);assertTrue(sends.isEmpty());
            db.close();db=new OtcDb(context);controller=controller(db,engine);
            controller.executeDeal(agreed);controller.executeDeal(db.getDeal(agreed.ref));
            verify(engine,times(1)).executeOtcDeal(any(),any());
            when(engine.swapStatus(hash)).thenReturn(SwapDb.ST_STARTED);
            controller.expireStale(System.currentTimeMillis());assertEquals(1,sends.size());
            assertTrue(OtcDb.sameSnapshot(prepared,db.getDeal(agreed.ref)));
            assertEquals(hash,db.msgs(agreed.ref).get(0).hash);
        }
    }
    @Test public void staleOrInvalidPreparationCannotAuthorizeSubmission() {
        OtcDb.Deal d=create("preparation",OtcDb.ST_AGREED);controller.executeDeal(d);
        SwapEngine.StartCb cb=callback(engine);
        assertFalse(cb.onPrepared(null));assertFalse(cb.onPrepared("bad"));
        OtcDb.Deal running=db.getDeal(d.ref);change(running,x->x.status=OtcDb.ST_EXPIRED);
        assertFalse(cb.onPrepared("ab".repeat(32)));cb.err("changed");
        assertEquals(OtcDb.ST_EXPIRED,db.getDeal(d.ref).status);
    }
    @Test public void onlyPreparedMatchingSuccessCanNotifyThePeer() {
        List<CommsTransport.SendCb> sends=new ArrayList<>();
        try(MockedStatic<CommsTransport> transport=hold(sends)) {
            OtcDb.Deal d=create("success",OtcDb.ST_AGREED);controller.executeDeal(d);
            SwapEngine.StartCb cb=callback(engine);cb.ok("ab".repeat(32));assertTrue(sends.isEmpty());
            assertTrue(cb.onPrepared("ab".repeat(32)));cb.ok("cd".repeat(32));assertTrue(sends.isEmpty());
            cb.ok("0x"+"AB".repeat(32));cb.ok("ab".repeat(32));cb.err("duplicate");
            assertEquals(1,sends.size());assertEquals(OtcDb.ST_EXECUTING,db.getDeal(d.ref).status);
        }
    }
    @Test public void inboundAcceptAndExecuteMustMatchStandingTermsAndCounterCannotReopenAgreement() {
        for(String type:new String[]{OtcMessage.ACCEPT,OtcMessage.EXECUTE})for(String field:new String[]{"amount","price","side"}){
            OtcDb.Deal d=create(type+field,type.equals(OtcMessage.ACCEPT)?OtcDb.ST_PROPOSED:OtcDb.ST_AGREED);
            sql("UPDATE otc_deals SET role=?,whoseturn=? WHERE ref=?",type.equals(OtcMessage.EXECUTE)?OtcDb.ROLE_LP:d.role,OtcDb.TURN_PEER,d.ref);
            d=db.getDeal(d.ref);OtcMessage m=message(d,type);
            if(field.equals("amount"))m.amount="9";else if(field.equals("price"))m.price="9";else m.side=OtcOffer.LP_BUYS_MINIMA;
            receive(m);assertTrue(OtcDb.sameSnapshot(d,db.getDeal(d.ref)));
        }
        OtcDb.Deal d=create("closed negotiation",OtcDb.ST_AGREED);change(d,x->x.whoseTurn=OtcDb.TURN_PEER);d=db.getDeal(d.ref);
        OtcMessage counter=message(d,OtcMessage.COUNTER);counter.price="9";receive(counter);assertTrue(OtcDb.sameSnapshot(d,db.getDeal(d.ref)));
        verifyNoInteractions(engine);
    }
    @Test public void matchingInboundAcceptAndExecuteKeepTheExistingOrderIndependentFlow() {
        OtcDb.Deal d=create("peer accept",OtcDb.ST_PROPOSED);change(d,x->x.whoseTurn=OtcDb.TURN_PEER);d=db.getDeal(d.ref);
        receive(message(d,OtcMessage.ACCEPT));verify(engine).executeOtcDeal(argThat(x->x.ref.equals("peer accept")),any());
        for(String status:new String[]{OtcDb.ST_COUNTERED,OtcDb.ST_AGREED}){
            OtcDb.Deal lp=create(status,status);sql("UPDATE otc_deals SET role=?,whoseturn=? WHERE ref=?",OtcDb.ROLE_LP,OtcDb.TURN_PEER,lp.ref);lp=db.getDeal(lp.ref);
            receive(message(lp,OtcMessage.EXECUTE));assertEquals(OtcDb.ST_EXECUTING,db.getDeal(lp.ref).status);assertEquals("ab".repeat(32),db.getDeal(lp.ref).hash);
        }
        verify(engine,times(2)).checkBuyNow("ab".repeat(32));
    }
    @Test public void expiryCannotOverwriteProgressMadeByTheOtherController() {
        OtcDb.Deal d=create("expiry",OtcDb.ST_EXECUTING);change(d,x->x.hash="ab".repeat(32));
        when(engine.swapStatus(anyString())).thenAnswer(i->{change(other.getDeal(d.ref),x->x.status=OtcDb.ST_COMPLETE);return null;});
        controller.expireStale(System.currentTimeMillis()+2*60*60*1000);
        assertEquals(OtcDb.ST_COMPLETE,db.getDeal(d.ref).status);
    }
    @Test public void failedPersistenceDoesNotSendOrExecute() {
        OtcDb.Deal d=create("write failure",OtcDb.ST_COUNTERED);
        sql("CREATE TRIGGER deny_update BEFORE UPDATE ON otc_deals BEGIN SELECT RAISE(ABORT,'public fixture'); END");
        assertThrows(RuntimeException.class,()->controller.accept(d,new Result()));
        assertTrue(OtcDb.sameSnapshot(d,db.getDeal(d.ref)));assertTrue(db.msgs(d.ref).isEmpty());verifyNoInteractions(engine,node,crypto);
    }
    @Test public void freshCounterAndRejectPreserveBothMarketsAndDirections() {
        List<CommsTransport.SendCb> sends=new ArrayList<>();
        try(MockedStatic<CommsTransport> transport=hold(sends)){
            for(TradingContext market:TradingContext.values())for(String side:new String[]{OtcOffer.LP_BUYS_MINIMA,OtcOffer.LP_SELLS_MINIMA}){
                TradingContext.setActive(market,null);controller=controller(db,engine);
                OtcDb.Deal d=create(market.key+side,OtcDb.ST_PROPOSED);
                sql("UPDATE otc_deals SET currency=?,side=? WHERE ref=?",market.key,side,d.ref);d=db.getDeal(d.ref);
                Result counter=new Result();controller.counter(d,"4","5",counter);assertNull(counter.error);
                OtcDb.Deal stored=db.getDeal(d.ref);assertEquals(OtcDb.ST_COUNTERED,stored.status);assertEquals(OtcDb.TURN_PEER,stored.whoseTurn);
                assertEquals("4",stored.amount);assertEquals("5",stored.price);assertEquals("2",d.amount);
                assertEquals(market.key,stored.currency);assertEquals(side,stored.side);assertEquals(OtcMessage.COUNTER,db.msgs(d.ref).get(0).type);
                Result reject=new Result();controller.reject(stored,reject);assertNull(reject.error);assertEquals(OtcDb.ST_REJECTED,db.getDeal(d.ref).status);
            }
            assertEquals(8,sends.size());verifyNoInteractions(engine);
        }
    }
    @Test public void revisionSurvivesReopeningAndPruningRollsBackBothTables() {
        OtcDb.Deal d=create("durable",OtcDb.ST_COUNTERED);
        long future=System.currentTimeMillis()+60_000;sql("UPDATE otc_deals SET updated=? WHERE ref=?",future,d.ref);d=db.getDeal(d.ref);
        change(d,x->x.price="4");assertEquals(future+1,db.getDeal(d.ref).updated);
        db.close();db=new OtcDb(context);assertEquals(future+1,db.getDeal(d.ref).updated);
        OtcDb.Msg message=new OtcDb.Msg();message.randomid="message";message.ref=d.ref;assertTrue(db.addMsg(message));
        sql("CREATE TRIGGER deny_delete BEFORE DELETE ON otc_msgs BEGIN SELECT RAISE(ABORT,'public fixture'); END");
        OtcDb.Deal before=db.getDeal(d.ref);assertThrows(RuntimeException.class,()->other.deleteDeal(before));
        assertTrue(OtcDb.sameSnapshot(before,db.getDeal(d.ref)));assertEquals(1,db.msgs(d.ref).size());
    }
}
