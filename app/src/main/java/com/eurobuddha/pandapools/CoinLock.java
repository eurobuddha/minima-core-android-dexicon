// VENDORED VERBATIM from apks/pandapools 0.9.83 app/src/main/java/com/eurobuddha/pandapools/CoinLock.java by scripts/vendor-pandapools.sh — do not edit here; re-vendor.
package com.eurobuddha.pandapools;

import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Process-wide reservation of the wallet coins an in-flight transaction intends to spend.
 *
 * Several paths fan out on purpose — {@link PoolRefresher} posts up to 8 refreshes in one loop,
 * {@link ReAnnouncer} the same, {@code PoolManager.sweepOwnerFunds} one per owner address — and
 * {@link PoolManager#selectCoins} sorts largest-first and stops at the first coin that covers the
 * amount needed. For a refresh that amount is beacon dust, so the single largest wallet coin always
 * satisfies it and EVERY parallel builder picked the same coin, at the same address, owned by the same
 * key. Seven of them then double-spent and failed, and all of them signed.
 *
 * {@link PoolTxn} already had this idea, but as an instance field on a class only the Swap tab
 * constructs — so it protected swaps from each other and nothing else. This is the shared version that
 * {@link PoolManager} consults too.
 *
 * Selection reservations expire. Submitted inputs stay reserved until their outcome is resolved;
 * an elapsed timer never authorizes a duplicate spend.
 */
public final class CoinLock {

    /** ~3–4 blocks. Long enough to cover build + sign + post, short enough that a lost transaction
     *  frees its coin within a few minutes. */
    private static final long TTL_MS = 3 * 60 * 1000L;

    private static final Map<String, Long> RESERVED = new ConcurrentHashMap<>();
    private static final Set<String> QUEUED = new HashSet<>();

    static void checkAvailable(FundingCoins.Command command,List<String> ids,int at,java.util.function.Consumer<Boolean> cb) {
        if(at>=ids.size()){cb.accept(true);return;}
        String id=ids.get(at);
        command.run("coins checkmempool:true coinid:"+id,new NodeApi.Cb(){
            public void onResult(org.json.JSONObject r){
                org.json.JSONArray rows=FundingCoins.rows(r);
                org.json.JSONObject coin=rows!=null&&rows.length()==1?rows.optJSONObject(0):null;
                if(r==null||!Boolean.TRUE.equals(r.opt("status"))||Boolean.TRUE.equals(r.opt("pending"))||coin==null
                        ||!id.equalsIgnoreCase(coin.optString("coinid"))||!Boolean.FALSE.equals(coin.opt("spent"))){cb.accept(false);return;}
                checkAvailable(command,ids,at+1,cb);
            }
            public void onError(String message){cb.accept(false);}
        });
    }

    private CoinLock() {}
    // Posted inputs are one-use UTXOs. Never release them on a timer or a view/process restart.
    // Once confirmed, selection naturally uses the NEW output IDs. An uncertain post remains held.
    private static final Map<String, String> POSTED = new HashMap<>();   // coin id -> the transaction handle that claimed it
    private static android.content.SharedPreferences postingStore;
    static synchronized boolean loadPosted(android.content.Context context) {
        try {
            if(context==null)return false;
            postingStore=context.getApplicationContext().getSharedPreferences("pool_posted_inputs_v1",android.content.Context.MODE_PRIVATE);
            for(Map.Entry<String,?> e:postingStore.getAll().entrySet())POSTED.put(key(e.getKey()),String.valueOf(e.getValue()));
            return true;
        }catch(RuntimeException unreadable){return false;}
    }
    static synchronized boolean rememberPosting(List<String> ids,String transaction) {
        if(postingStore==null)return false;
        // Integrated tools share this preferences object. Re-read under that shared lock at the post boundary.
        synchronized(postingStore){
            try {
                Map<String,?> current=postingStore.getAll();
                for(String id:ids)if(current.containsKey(key(id)))return false;
                return rememberPosting(ids,transaction,()->{
                    android.content.SharedPreferences.Editor edit=postingStore.edit();
                    for(String id:ids)edit.putString(key(id),transaction);
                    return edit.commit();
                });
            }catch(RuntimeException unreadable){return false;}
        }
    }
    static synchronized boolean rememberPosting(List<String> ids,java.util.function.BooleanSupplier persist) {
        return rememberPosting(ids,"",persist);
    }
    static synchronized boolean rememberPosting(List<String> ids,String transaction,java.util.function.BooleanSupplier persist) {
        for(String id:ids)if(id==null||POSTED.containsKey(key(id)))return false;
        // Retain the process latch even if durable storage fails; nothing may post on that failure.
        for(String id:ids)POSTED.put(key(id),transaction==null?"":transaction);
        try{return persist.getAsBoolean();}catch(RuntimeException failure){return false;}
    }
    /** Free this transaction's claims. ONLY for a definite "nothing was broadcast": the node answered txnpost with
     *  status:false (not pending), or the claim could not be saved before txnpost. An unanswered, pending or
     *  uncertain post stays held. Claims made by any other transaction are never touched. */
    public static synchronized void forgetPosting(List<String> ids,String transaction) {
        if(ids==null||transaction==null||transaction.isEmpty())return;
        for(String id:ids)if(id!=null&&transaction.equals(POSTED.get(key(id))))POSTED.remove(key(id));
        if(postingStore==null)return;
        synchronized(postingStore){
            try {
                Map<String,?> current=postingStore.getAll();
                android.content.SharedPreferences.Editor edit=postingStore.edit();
                for(String id:ids)if(id!=null&&transaction.equals(current.get(key(id))))edit.remove(key(id));
                edit.commit();
            }catch(RuntimeException ignored){}
        }
    }


    public static synchronized boolean isReserved(String coinid) {
        if (coinid == null) return false;
        if (POSTED.containsKey(key(coinid)) || QUEUED.contains(key(coinid))) return true;
        Long at = RESERVED.get(key(coinid));
        return at != null && (System.currentTimeMillis() - at) < TTL_MS;
    }

    public static void reserve(List<Coin> coins) {
        long now = System.currentTimeMillis();
        for (Coin c : coins) if (c != null && c.coinid != null) RESERVED.put(key(c.coinid), now);
    }

    public static void release(List<Coin> coins) {
        for (Coin c : coins) if (c != null && c.coinid != null) RESERVED.remove(key(c.coinid));
    }

    /** Claim all inputs atomically before queuing. Selection reservations may expire; a queued
     * or signing chain must retain its inputs until its callback completes. */
    static synchronized boolean claimInputs(List<String> ids) {
        Set<String> unique = new HashSet<>();
        for (String id : ids)
            if (id == null || !unique.add(key(id)) || QUEUED.contains(key(id)) || POSTED.containsKey(key(id))) return false;
        QUEUED.addAll(unique);
        return true;
    }

    static synchronized void finishInputs(List<String> ids) {
        long now = System.currentTimeMillis();
        for (String id : ids) {
            QUEUED.remove(key(id));
            // Give the node's mempool view time to catch up after a successful/ambiguous post.
            RESERVED.put(key(id), now);
        }
    }

    private static String key(String id) { return id.toLowerCase(Locale.ROOT); }

    /** Drop entries past their TTL. Token-agnostic on purpose: a scan only sees one token's coins, so
     *  pruning against "what I can see" would wrongly free the other side's live reservations. */
    public static void prune() {
        long now = System.currentTimeMillis();
        RESERVED.entrySet().removeIf(e -> now - e.getValue() >= TTL_MS);
    }
}
