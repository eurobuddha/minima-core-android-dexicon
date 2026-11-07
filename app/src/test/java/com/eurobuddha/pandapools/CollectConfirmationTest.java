// VENDORED VERBATIM from apks/pandapools 0.9.90 app/src/test/java/com/eurobuddha/pandapools/CollectConfirmationTest.java by scripts/vendor-pandapools.sh — do not edit here; re-vendor.
package com.eurobuddha.pandapools;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.*;
import org.junit.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** The real collection queue and callbacks, with public node fixtures and in-memory preferences. */
public class CollectConfirmationTest {
    Context context; NodeApi node; SharedPreferences prefs;
    Map<String,Object> values;
    List<String> commands; List<NodeApi.Cb> callbacks;
    List<String> preflight;
    boolean commitSucceeds;
    static final String ADDRESS=id('a'), TX=id('b'), MINED=id('c');
    static String id(char c) { return "0x"+String.valueOf(c).repeat(64); }
    static JSONObject object(Object... values) {
        JSONObject result=new JSONObject();
        try { for(int i=0;i<values.length;i+=2)result.put((String)values[i],values[i+1]); }
        catch(JSONException e){throw new AssertionError(e);}return result;
    }
    static JSONObject balance(Object... counts) {
        JSONArray rows=new JSONArray();for(Object count:counts)rows.put(object("coins",count));
        return object("status",true,"response",rows);
    }
    static JSONObject candidate(String tx,String mined) {return object("istransaction",true,"txpowid",mined,"body",object("txn",object("transactionid",tx)));}
    static JSONObject page(JSONObject... txs) {JSONArray list=new JSONArray();for(JSONObject tx:txs)list.put(tx);return object("status",true,"response",object("txpows",list));}
    static JSONObject inclusion() {return object("status",true,"response",object("found",true,"blockid",id('d'),"block","100","tip","103","confirmations","3"));}
    @Before public void setup() {
        // Each fixture is a fresh process; production deliberately has no reset/unlock API.
        try{for(String field:new String[]{"POSTED","QUEUED","RESERVED"}){java.lang.reflect.Field f=CoinLock.class.getDeclaredField(field);f.setAccessible(true);Object v=f.get(null);if(v instanceof Map)((Map<?,?>)v).clear();else ((Set<?>)v).clear();}}catch(Exception e){throw new AssertionError(e);}
        context=mock(Context.class);node=mock(NodeApi.class);prefs=mock(SharedPreferences.class);
        when(context.getApplicationContext()).thenReturn(context);preflight=new ArrayList<>();
        values=new HashMap<>();commands=new ArrayList<>();callbacks=new ArrayList<>();commitSucceeds=true;
        when(context.getSharedPreferences(anyString(),anyInt())).thenReturn(prefs);when(node.context()).thenReturn(context);
        when(prefs.getString(anyString(),nullable(String.class))).thenAnswer(i->values.getOrDefault(i.getArgument(0),i.getArgument(1)));
        when(prefs.getInt(anyString(),anyInt())).thenAnswer(i->values.getOrDefault(i.getArgument(0),i.getArgument(1)));
        when(prefs.getAll()).thenAnswer(i->new HashMap<>(values));
        when(prefs.edit()).thenAnswer(i->{
            SharedPreferences.Editor edit=mock(SharedPreferences.Editor.class);Map<String,Object> pending=new HashMap<>();
            when(edit.putString(anyString(),anyString())).thenAnswer(c->{pending.put(c.getArgument(0),c.getArgument(1));return edit;});
            when(edit.putInt(anyString(),anyInt())).thenAnswer(c->{pending.put(c.getArgument(0),c.getArgument(1));return edit;});
            when(edit.remove(anyString())).thenAnswer(c->{pending.put(c.getArgument(0),null);return edit;});
            Runnable apply=()->pending.forEach((k,v)->{if(v==null)values.remove(k);else values.put(k,v);});
            when(edit.commit()).thenAnswer(c->{apply.run();return commitSucceeds;});
            doAnswer(c->{apply.run();return null;}).when(edit).apply();return edit;
        });
        doAnswer(i->{String command=i.getArgument(0);
            if(command.startsWith("coins checkmempool:true coinid:")){
                preflight.add(command);String coinid=command.substring("coins checkmempool:true coinid:".length());
                ((NodeApi.Cb)i.getArgument(1)).onResult(object("status",true,"response",new JSONArray().put(object("coinid",coinid,"spent",false))));return null;
            }
            commands.add(command);callbacks.add(i.getArgument(1));return null;})
                .when(node).cmd(anyString(),any(NodeApi.Cb.class));
    }
    void answer(JSONObject value){assertFalse("No pending node callback",callbacks.isEmpty());callbacks.remove(0).onResult(value);}
    void track(){assertTrue(PendingCollect.track(context,ADDRESS,TX));}
    void sweep(int[] result){new CollectSweeper(context,node).run((a,b,c)->{result[0]=a;result[1]=b;result[2]=c;});}

    @Test public void emptyBeforeWithdrawalArrivesIsRetainedAcrossRestart() {
        track();int[] result={-1,-1,-1};sweep(result);answer(page());answer(balance());
        assertArrayEquals(new int[]{0,1,0},result);assertNotNull(PendingCollect.get(context,ADDRESS));
        commands.clear();sweep(result);answer(page());answer(balance());
        assertArrayEquals(new int[]{0,1,0},result);assertFalse(commands.stream().anyMatch(c->c.startsWith("txn")));
    }
    @Test public void exactMinedIdentityAndFreshConfirmationThenEmptyClears() {
        track();int[] result={-1,-1,-1};sweep(result);
        answer(page(candidate(TX,MINED)));answer(balance());
        assertEquals("txpow onchain:"+MINED,commands.get(commands.size()-1));answer(inclusion());
        assertNotNull(PendingCollect.get(context,ADDRESS));answer(balance());
        assertArrayEquals(new int[]{1,0,0},result);assertNull(PendingCollect.get(context,ADDRESS));
    }
    @Test public void partialCollectionOrUnknownSigningNeverCompletes() {
        track();int[] result={-1,-1,-1};sweep(result);answer(page(candidate(TX,MINED)));answer(balance(1));
        answer(object("status",true,"response",object("relevant","not-a-boolean")));
        assertArrayEquals(new int[]{0,1,0},result);assertNotNull(PendingCollect.get(context,ADDRESS));
        assertEquals(3,commands.size());
    }
    @Test public void newerWithdrawalCannotBeClearedByOlderEmptyCallback() {
        track();int[] result={-1,-1,-1};sweep(result);answer(page(candidate(TX,MINED)));answer(balance());answer(inclusion());
        assertTrue(PendingCollect.track(context,ADDRESS,id('e')));answer(balance());
        assertArrayEquals(new int[]{0,1,0},result);assertEquals(2,PendingCollect.get(context,ADDRESS).transactions.size());
    }
    @Test public void everyWithdrawalMustConfirmEvenWhenOneAlreadyDid() {
        track();assertTrue(PendingCollect.track(context,ADDRESS,id('e')));int[] result={-1,-1,-1};sweep(result);
        answer(page(candidate(TX,MINED),candidate(id('e'),id('f'))));answer(balance());answer(inclusion());
        answer(object("status",true,"response",object("found",false)));
        assertArrayEquals(new int[]{0,1,0},result);assertNotNull(PendingCollect.get(context,ADDRESS));
    }
    @Test public void changedBalanceAfterConfirmationPreventsClear() {
        track();int[] result={-1,-1,-1};sweep(result);answer(page(candidate(TX,MINED)));answer(balance());answer(inclusion());answer(balance(1));
        assertArrayEquals(new int[]{0,1,0},result);assertNotNull(PendingCollect.get(context,ADDRESS));
    }
    @Test public void missingWrongOrReorgedReceiptNeverClears() {
        track();int[] result={-1,-1,-1};sweep(result);answer(page(candidate(id('e'),MINED)));answer(balance());
        assertArrayEquals(new int[]{0,1,0},result);
        sweep(result);answer(page(candidate(TX,MINED)));answer(balance());answer(object("status",true,"response",object("found",false)));
        assertArrayEquals(new int[]{0,1,0},result);assertNotNull(PendingCollect.get(context,ADDRESS));
    }
    @Test public void legacyEmptyEntryIsNotAssumedToHaveReceivedItsPayout() {
        assertTrue(PendingCollect.add(context,ADDRESS));int[] result={-1,-1,-1};sweep(result);answer(page(candidate(TX,MINED)));answer(balance());
        assertArrayEquals(new int[]{0,1,0},result);assertTrue(PendingCollect.get(context,ADDRESS).legacy);
        assertTrue(PendingCollect.track(context,ADDRESS,TX));assertTrue(PendingCollect.get(context,ADDRESS).legacy);
    }
    @Test public void failedClearRestoresJobInMemoryAndDoesNotReportCompletion() {
        track();int[] result={-1,-1,-1};sweep(result);answer(page(candidate(TX,MINED)));answer(balance());answer(inclusion());
        commitSucceeds=false;answer(balance());assertArrayEquals(new int[]{0,1,0},result);assertNotNull(PendingCollect.get(context,ADDRESS));
    }
    @Test public void broadcastWaitsForTheDraftIdentityToBeDurablySaved() {
        AtomicInteger posts=new AtomicInteger();List<String> failures=new ArrayList<>();
        CollectConfirmation.beforePost(node,"ppclose_test",ADDRESS,posts::incrementAndGet,failures::add);
        assertEquals(0,posts.get());assertEquals("txnlist id:ppclose_test transactiononly:true",commands.get(0));
        answer(object("status",true,"response",object("id","ppclose_test","transaction",object("transactionid",TX))));
        assertEquals(1,posts.get());assertTrue(failures.isEmpty());assertTrue(PendingCollect.get(context,ADDRESS).transactions.containsKey(TX));
    }
    @Test public void failedDurableSaveOrWrongDraftPreventsBroadcast() {
        AtomicInteger posts=new AtomicInteger();List<String> failures=new ArrayList<>();commitSucceeds=false;
        CollectConfirmation.beforePost(node,"ppclose_test",ADDRESS,posts::incrementAndGet,failures::add);
        answer(object("status",true,"response",object("id","ppclose_test","transaction",object("transactionid",TX))));
        assertEquals(0,posts.get());assertEquals(1,failures.size());assertNull(PendingCollect.get(context,ADDRESS));
        commitSucceeds=true;CollectConfirmation.beforePost(node,"ppclose_test",ADDRESS,posts::incrementAndGet,failures::add);
        answer(object("status",true,"response",object("id","other","transaction",object("transactionid",TX))));
        assertEquals(0,posts.get());assertEquals(2,failures.size());assertNull(PendingCollect.get(context,ADDRESS));
    }
    @Test public void unavailablePreferencesPreventBroadcastWithoutLosingTheCallback() {
        when(prefs.getString(anyString(),nullable(String.class))).thenThrow(new IllegalStateException("unavailable"));
        AtomicInteger posts=new AtomicInteger();List<String> failures=new ArrayList<>();
        CollectConfirmation.beforePost(node,"ppclose_test",ADDRESS,posts::incrementAndGet,failures::add);
        answer(object("status",true,"response",object("id","ppclose_test","transaction",object("transactionid",TX))));
        assertEquals(0,posts.get());assertEquals(1,failures.size());
    }
    @Test public void actualClosePipelineJournalsBeforePostAndRetainsUnknownOutcome() {
        try (org.mockito.MockedStatic<OwnerKeyRecovery> owners=mockStatic(OwnerKeyRecovery.class);
             org.mockito.MockedStatic<CmdChain> chain=mockStatic(CmdChain.class)) {
            owners.when(()->OwnerKeyRecovery.ensure(any(Context.class),eq(node),anyList(),anyList(),any(OwnerKeyRecovery.Cb.class)))
                    .thenAnswer(i->{((OwnerKeyRecovery.Cb)i.getArgument(4)).done(Collections.emptyMap());return null;});
            chain.when(()->CmdChain.run(eq(node),anyList(),anyString(),any(CmdChain.Done.class))).thenAnswer(i->{
                ((CmdChain.Done)i.getArgument(3)).ok(object("status",true,"response",object("validamounts",true,
                        "allsignaturesvalid",true,"validtransaction",true,"valid",object("mmrproofs",true,"scripts",true,"basic",true))));
                return null;
            });
            Pool pool=new Pool();pool.oadr=ADDRESS;pool.opk=id('1');pool.tok=id('2');pool.coinidM=id('3');pool.coinidT=id('4');
            pool.reserveM=new java.math.BigDecimal("100");pool.reserveT=new java.math.BigDecimal("2");
            List<String> failures=new ArrayList<>();new PoolManager(node).close(pool,new PoolManager.Result(){
                public void onPosted(String id){fail("uncertain outcome must not report posted");}
                public void onFailed(String error){failures.add(error);}
            });
            assertEquals(2,preflight.size());assertTrue(preflight.get(0).endsWith(pool.coinidM));assertTrue(preflight.get(1).endsWith(pool.coinidT));
            assertEquals(1,commands.size());assertTrue(commands.get(0).startsWith("txnlist id:ppclose_"));
            String handle=commands.get(0).substring("txnlist id:".length()).split(" ")[0];
            assertNull(PendingCollect.get(context,ADDRESS));
            answer(object("status",true,"response",object("id",handle,"transaction",object("transactionid",TX))));
            assertEquals("txnpost id:"+handle,commands.get(1));assertNotNull(PendingCollect.get(context,ADDRESS));
            callbacks.remove(0).onError(NodeApi.ERR_WRITE_UNCERTAIN);
            assertEquals(Collections.singletonList(NodeApi.ERR_WRITE_UNCERTAIN),failures);
            assertNotNull(PendingCollect.get(context,ADDRESS));assertEquals(2,commands.size());
            // Preserve the existing mempool catch-up reservation after an ambiguous post.
            assertTrue(CoinLock.isReserved(pool.coinidM));assertTrue(CoinLock.isReserved(pool.coinidT));
        }
    }
    @Test public void malformedBalancesAreUnknownRatherThanEmpty() {
        for(JSONObject reply:new JSONObject[]{null,object(),object("status",false,"response",new JSONArray()),
                balance(-1),balance("1.5"),balance("2147483648"),balance(2147483647,1),balance(-1,1),
                object("status",true,"response",new JSONArray().put(JSONObject.NULL)),object("status",true,"response",new JSONArray().put(object()))})
            assertNull(CollectionEvidence.remaining(reply));
        assertEquals(Integer.valueOf(0),CollectionEvidence.remaining(balance()));
        assertEquals(Integer.valueOf(3),CollectionEvidence.remaining(balance(1,"2")));
    }
    @Test public void confirmationRequiresCompleteConsistentChainEvidence()throws Exception {
        assertTrue(CollectionEvidence.confirmed(inclusion()));
        for(String key:new String[]{"found","blockid","block","tip","confirmations"}) {
            JSONObject reply=inclusion();reply.getJSONObject("response").remove(key);assertFalse(CollectionEvidence.confirmed(reply));
        }
        for(Object depth:new Object[]{3,"2","3.0","-3",true,"3;send"}) {
            JSONObject reply=inclusion();reply.getJSONObject("response").put("confirmations",depth);assertFalse(CollectionEvidence.confirmed(reply));
        }
        JSONObject reply=inclusion();reply.getJSONObject("response").put("tip","104");assertFalse(CollectionEvidence.confirmed(reply));
    }
    @Test public void historyScanIsBoundedAndResumesAfterRestart() {
        track();AtomicInteger done=new AtomicInteger();new CollectConfirmation(context,node).scan(done::incrementAndGet);
        JSONObject[] irrelevant=new JSONObject[8];Arrays.fill(irrelevant,object("istransaction",false));
        for(int i=0;i<4;i++)answer(page(irrelevant));assertEquals(1,done.get());assertEquals(32,PendingCollect.scanOffset(context));
        new CollectConfirmation(context,node).scan(done::incrementAndGet);assertEquals("history relevant:true max:8 offset:32",commands.get(4));
        answer(page());assertEquals(0,PendingCollect.scanOffset(context));assertEquals(2,done.get());
    }
}
