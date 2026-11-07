// VENDORED VERBATIM from apks/pandapools 0.9.87 app/src/test/java/com/eurobuddha/pandapools/BoundedRecoveryTest.java by scripts/vendor-pandapools.sh — do not edit here; re-vendor.
package com.eurobuddha.pandapools;

import org.json.*;
import org.junit.*;
import org.mockito.MockedStatic;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static com.eurobuddha.pandapools.CollectConfirmationTest.*;

/** Public, synthetic fixtures; no seed or private wallet data. Reuses the collection test harness. */
public class BoundedRecoveryTest {
    CollectConfirmationTest f;
    Pool pool;
    static final String OWNER=id('1'), POOL=id('2'), TOKEN="0x"+"Ab".repeat(32), DEST=id('4');
    static final String CM=id('5'), CT=id('6'), FWD=id('7'), FWD_MINED=id('8');
    List<String> failures;
    AtomicInteger forwarded, nothing;
    PoolManager.ForwardResult result;
    @Before public void setup() {
        f=new CollectConfirmationTest();f.setup();ExitAuthority.revoke();
        Coin m=new Coin();m.coinid=CM;Coin t=new Coin();t.coinid=CT;
        CoinLock.release(Arrays.asList(m,t));
        pool=new Pool();pool.address=POOL;pool.opk=OWNER;pool.oadr=ADDRESS;pool.tok=TOKEN;
        pool.minimumOwnerUses=0;pool.signingStateUnverified=true;
        failures=new ArrayList<>();forwarded=new AtomicInteger();nothing=new AtomicInteger();
        result=new PoolManager.ForwardResult(){
            public void onForwarded(String id,int coins){forwarded.incrementAndGet();}
            public void onNothing(){nothing.incrementAndGet();}
            public void onFailed(String message){failures.add(message);}
        };
    }
    @After public void clear(){ExitAuthority.revoke();}
    void ready() {
        assertTrue(ExitStore.beginClose(f.context,pool));
        assertTrue(ExitStore.set(f.context,POOL,OWNER,ExitStore.State.POSTED,"",MINED,0));
        f.track();PendingCollect.observe(f.context,candidate(TX,MINED));
    }
    JSONObject coins(boolean both) {
        JSONArray coins=new JSONArray().put(object("coinid",CM,"address",ADDRESS,"tokenid","0x00","amount","100","spent",false,"state",new JSONArray()));
        if(both)coins.put(object("coinid",CT,"address",ADDRESS,"tokenid",TOKEN,"tokenamount","2","spent",false,"state",new JSONArray()));
        return object("status",true,"response",coins);
    }
    Map<String,BigDecimal> amounts(){Map<String,BigDecimal> m=new LinkedHashMap<>();m.put("0x00",new BigDecimal("100"));m.put(TOKEN,new BigDecimal("2"));return m;}
    List<String> commands(){return new ArrayList<>(Arrays.asList("txncreate id:ppfwd_test",
            "txninput id:ppfwd_test coinid:"+CM,"txninput id:ppfwd_test coinid:"+CT,
            "txnoutput id:ppfwd_test amount:100 address:"+DEST+" storestate:false",
            "txnoutput id:ppfwd_test amount:2 address:"+DEST+" tokenid:"+TOKEN+" storestate:false",
            "txnsign id:ppfwd_test publickey:"+OWNER,"txnbasics id:ppfwd_test"));}

    @Test public void eachStageIsClaimedOnceAcrossRestartAndAnUnknownWriteNeverRetries(){
        assertTrue(ExitStore.beginClose(f.context,pool));assertFalse(ExitStore.beginClose(f.context,pool));
        assertNotNull(RestoreExit.refusal(f.context,pool,0,268435456,2398700));
        ExitStore.set(f.context,POOL,OWNER,ExitStore.State.POSTED,"",MINED,0);
        assertTrue(ExitStore.beginForward(f.context,pool));assertFalse(ExitStore.beginForward(f.context,pool));
        assertEquals(2,ExitStore.signaturesUsed(f.context,POOL));
        ExitStore.set(f.context,POOL,OWNER,ExitStore.State.UNCERTAIN,"lost reply","",0);
        assertFalse(ExitStore.beginClose(f.context,pool));assertFalse(ExitStore.beginForward(f.context,pool));
        assertEquals(2,ExitStore.signaturesUsed(f.context,POOL));
    }
    @Test public void failedPersistenceOrCorruptHistoryDoesNotAuthorizeASignature(){
        f.commitSucceeds=false;assertFalse(ExitStore.beginClose(f.context,pool));
        f.commitSucceeds=true;assertFalse(ExitStore.beginClose(f.context,pool));
        f.values.put(POOL,"broken json");assertFalse(ExitStore.beginClose(f.context,pool));
    }
    @Test public void forwardTicketPinsAllCommandsAndCannotBecomeAnUnlimitedUnlock(){
        List<String> ids=new ArrayList<>(Arrays.asList(CM,CT));Map<String,BigDecimal> sums=amounts();
        ExitTicket t=ExitTicket.forward(pool,"ppfwd_test",ids,sums,DEST);assertNotNull(t);
        ids.clear();sums.clear();assertTrue(t.permits(commands(),pool));
        List<String> checked=commands();checked.add("txncheck id:ppfwd_test");assertTrue(t.permits(checked,pool));
        for(int i=0;i<commands().size();i++){
            List<String> missing=commands();missing.remove(i);assertFalse(t.permits(missing,pool));
            List<String> changed=commands();changed.set(i,changed.get(i)+" extra:true");assertFalse(t.permits(changed,pool));
        }
        for(String replacement:new String[]{"amount:1 address:"+DEST,"amount:100 address:"+ADDRESS}){
            List<String> changed=commands();changed.set(3,"txnoutput id:ppfwd_test "+replacement+" storestate:false");assertFalse(t.permits(changed,pool));
        }
        assertTrue(ExitAuthority.tryGrant(t));assertFalse(ExitAuthority.tryGrant(t));
        assertTrue(ExitAuthority.permits(checked,pool));assertFalse(ExitAuthority.permits(checked,pool));
        assertTrue(pool.signingStateUnverified);assertFalse(OwnerKeyRecovery.signingAllowed(pool,0,268435456));
    }
    @Test public void badForwardIdentityDestinationOrDuplicatesNeverReceiveATicket(){
        assertNull(ExitTicket.forward(pool,"ppfwd_test",Arrays.asList(CM,CM),amounts(),DEST));
        assertNull(ExitTicket.forward(pool,"ppfwd_test",Arrays.asList(CM,CT),amounts(),ADDRESS));
        assertNull(ExitTicket.forward(pool,"ppclose_test",Arrays.asList(CM,CT),amounts(),DEST));
    }
    @Test public void aLateCallbackCannotRevokeAnotherOperationsTicket(){
        ExitTicket first=ExitTicket.forward(pool,"ppfwd_first",Arrays.asList(CM,CT),amounts(),DEST);
        ExitTicket second=ExitTicket.forward(pool,"ppfwd_second",Arrays.asList(CM,CT),amounts(),DEST);
        assertTrue(ExitAuthority.tryGrant(first));ExitAuthority.revoke(first);
        assertTrue(ExitAuthority.tryGrant(second));ExitAuthority.revoke(first);assertSame(second,ExitAuthority.current());
    }
    @Test public void unconfirmedWithdrawalAndPartialPayoutNeverReachSigning(){
        ready();try(MockedStatic<OwnPoolStore> store=mockStatic(OwnPoolStore.class)){
            store.when(()->OwnPoolStore.all(f.context)).thenReturn(Collections.singletonList(pool));
            new PoolManager(f.node).forwardOwnerFunds(ADDRESS,result);
            assertEquals("txpow onchain:"+MINED,f.commands.get(0));
            f.answer(object("status",true,"response",object("found",false)));
            assertEquals(1,nothing.get());assertEquals(1,f.commands.size());
            new PoolManager(f.node).forwardOwnerFunds(ADDRESS,result);f.answer(inclusion());f.answer(balance(1,1));f.answer(coins(false));
            assertEquals(2,nothing.get());assertEquals(1,ExitStore.signaturesUsed(f.context,POOL));
            assertNull(ExitAuthority.current());assertTrue(failures.isEmpty());
            assertFalse(f.commands.stream().anyMatch(c->c.startsWith("txn")||c.equals("getaddress")));
        }
    }
    @Test public void lostCollectionReplyNeverSignsAgain(){ collectionPipeline(true); }
    @Test public void confirmedCollectionClearsOnlyAfterBothTransactionsConfirm(){ collectionPipeline(false); }
    private void collectionPipeline(boolean lostReply){
        ready();
        try(MockedStatic<OwnPoolStore> store=mockStatic(OwnPoolStore.class);
            MockedStatic<OwnerKeyRecovery> owners=mockStatic(OwnerKeyRecovery.class);
            MockedStatic<CmdChain> chain=mockStatic(CmdChain.class)){
            store.when(()->OwnPoolStore.all(f.context)).thenReturn(Collections.singletonList(pool));
            owners.when(()->OwnerKeyRecovery.ensure(any(android.content.Context.class),eq(f.node),anyList(),anyList(),any(OwnerKeyRecovery.Cb.class)))
                .thenAnswer(i->{assertTrue(ExitAuthority.current().permits(i.getArgument(3),pool));((OwnerKeyRecovery.Cb)i.getArgument(4)).done(Collections.emptyMap());return null;});
            chain.when(()->CmdChain.run(eq(f.node),anyList(),anyString(),any(CmdChain.Done.class))).thenAnswer(i->{
                List<String> commands=i.getArgument(1);
                assertEquals(1,commands.stream().filter(c->c.startsWith("txnsign ")).count());
                assertFalse(commands.stream().anyMatch(c->c.contains("publickey:auto")));
                assertEquals(ExitStore.State.FORWARDING,ExitStore.get(f.context,POOL).stateOf());
                assertTrue(ExitAuthority.permits(commands,pool));
                ((CmdChain.Done)i.getArgument(3)).ok(object("status",true,"response",object("validamounts",true,"allsignaturesvalid",true,"validtransaction",true,"valid",object("mmrproofs",true,"scripts",true,"basic",true))));return null;});
            new PoolManager(f.node).forwardOwnerFunds(ADDRESS,result);f.answer(inclusion());f.answer(balance(1,1));f.answer(coins(true));
            assertEquals("getaddress",f.commands.get(f.commands.size()-1));f.answer(object("status",true,"response",object("address",DEST)));
            String draft=f.commands.get(f.commands.size()-1);assertTrue(draft.startsWith("txnlist id:ppfwd_"));
            assertEquals(1,PendingCollect.get(f.context,ADDRESS).transactions.size());
            String handle=draft.substring("txnlist id:".length()).split(" ")[0];
            f.answer(object("status",true,"response",object("id",handle,"transaction",object("transactionid",FWD))));
            assertEquals("txnpost id:"+handle,f.commands.get(f.commands.size()-1));
            assertEquals(2,PendingCollect.get(f.context,ADDRESS).transactions.size());
            if (lostReply) {
                f.callbacks.remove(0).onError(NodeApi.ERR_WRITE_UNCERTAIN);
                assertEquals(0,forwarded.get());assertEquals(1,failures.size());assertNull(ExitAuthority.current());
                assertEquals(ExitStore.State.UNCERTAIN,ExitStore.get(f.context,POOL).stateOf());
                int count=f.commands.size();new PoolManager(f.node).forwardOwnerFunds(ADDRESS,result);
                assertEquals(count,f.commands.size());assertEquals(2,ExitStore.signaturesUsed(f.context,POOL));
            } else {
                f.answer(object("status",true,"response",object("txpowid",FWD_MINED)));
                f.callbacks.clear(); // txn cleanup's no-op callback
                assertEquals(1,forwarded.get());assertTrue(failures.isEmpty());assertNull(ExitAuthority.current());
                assertEquals(ExitStore.State.FORWARDED,ExitStore.get(f.context,POOL).stateOf());
                assertTrue(pool.signingStateUnverified);
                int[] tally={-1,-1,-1};f.sweep(tally);
                f.answer(page(candidate(TX,MINED),candidate(FWD,FWD_MINED)));f.answer(balance());f.answer(inclusion());
                f.answer(object("status",true,"response",object("found",false)));
                assertEquals(0,tally[0]);assertNotNull(PendingCollect.get(f.context,ADDRESS));
                f.sweep(tally);f.answer(page());f.answer(balance());f.answer(inclusion());f.answer(inclusion());f.answer(balance());
                assertEquals(1,tally[0]);assertNull(PendingCollect.get(f.context,ADDRESS));
                assertEquals(ExitStore.State.DONE,ExitStore.get(f.context,POOL).stateOf());
                assertEquals(2,ExitStore.signaturesUsed(f.context,POOL));
            }
        }
    }
    @Test public void finalBoundaryRequiresAnExactTicketAndStillChecksCurrentKeyState(){
        ExitTicket t=ExitTicket.forward(pool,"ppfwd_test",Arrays.asList(CM,CT),amounts(),DEST);
        assertTrue(ExitAuthority.tryGrant(t));List<String> verdicts=new ArrayList<>();
        OwnerKeyRecovery.checkSignature(f.node::cmd,()->Collections.singletonList(pool),commands(),Arrays.asList(CM,CT),OWNER,verdicts::add);
        f.answer(object("status",true,"response",new JSONArray().put(coins(true).optJSONArray("response").optJSONObject(0))));
        f.answer(object("status",true,"response",new JSONArray().put(coins(true).optJSONArray("response").optJSONObject(1))));
        f.answer(object("status",true,"response",object("keys",new JSONArray().put(object("publickey",OWNER,"uses",0,"size",128,"depth",4)))));
        assertEquals(1,verdicts.size());assertNull(verdicts.get(0));assertNull(ExitAuthority.current());
        assertTrue(pool.signingStateUnverified);
    }
}
