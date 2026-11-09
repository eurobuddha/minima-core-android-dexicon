package com.eurobuddha.dexicon.account;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import com.eurobuddha.dexicon.NodeApi;

import org.json.JSONObject;

import java.util.Arrays;
import java.util.HashSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The process-wide Trading Account: one {@link VirtualWallet} on one worker thread. {@link NodeApi} hands it every
 * wallet / txn verb; it answers locally and reaches the node only through {@link NodeApi#cmdDirect} (reads, newscript,
 * coinexport, and the single signed {@code postfrom}). Signing happens on this ONE thread in this ONE process, so a
 * one-time key leaf can never be handed out twice (the in-app replacement for fork commit
 * 26cc3983495acafcb1b99f10c744dc506e254b07).
 */
public final class Account {

    private static final String PREFS = "dexicon_account_state";
    private static volatile Account instance;
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "dexicon-account"); t.setDaemon(true); return t; });
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private final VirtualWallet wallet;
    private Context app;
    private SharedPreferences sp;
    private String seenKey;
    private long scannedAt;
    private final AccountKeys keys;
    private volatile long trackedAt;
    private static final long RETRACK_MS = 10 * 60_000L;   // a companion can downgrade our script rows (newscript replaces)
    /** The NodeApi of the call being handled: screens come and go, the account outlives them. Worker-thread only. */
    private volatile NodeApi current;

    private Account(Context c, String secret, String mode, int restoreFloor, NodeApi node) {
        Context app = c.getApplicationContext();
        SharedPreferences sp = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        final String[] fp = {""};   // filled once the keys exist: the cache below is keyed by the seed's fingerprint
        AccountKeys.PubCache cache = new AccountKeys.PubCache() {
            private String key(int i) { return "pub" + AccountKeys.KEYS_PER_LEVEL + "x" + AccountKeys.LEVELS + "_" + fp[0] + "_" + i; }
            public String get(int i) { return fp[0].isEmpty() ? null : sp.getString(key(i), null); }
            public void put(int i, String p) { if (!fp[0].isEmpty()) sp.edit().putString(key(i), p).commit(); }
        };
        KeyUses uses = new PrefsKeyUses(app);
        keys = AccountVault.MODE_NODE.equals(mode) ? AccountKeys.fromBase(secret, uses, cache) : AccountKeys.fromPhrase(secret, uses, cache);
        keys.setRestoreFloor(restoreFloor);
        fp[0] = keys.fingerprint();
        final String countKey = "keys_" + fp[0];
        // 0.2.98 / 0.2.99 kept the key count under "keys": migrate it ONLY when it provably belongs to this seed (its
        // cached key 0 is this account's key 0), else an upgraded account would lose every address past index 0.
        final AccountKeys k0 = keys;
        migrateKeyCount(sp, countKey, () -> k0.publicKey(0));
        final String ownKey = "own_outs_" + fp[0];
        VirtualWallet.Store store = new VirtualWallet.Store() {
            public int keyCount() { return sp.getInt(countKey, 0); }
            public void setKeyCount(int n) { if (!sp.edit().putInt(countKey, n).commit()) throw new IllegalStateException("could not save key count"); }
            public void ownOutputs(java.util.Collection<String> ids) { sp.edit().putString(ownKey, VirtualWallet.boundedAdd(sp.getString(ownKey, "[]"), ids)).commit(); }
            public boolean isOwnOutput(String id) { return sp.getString(ownKey, "").toLowerCase(java.util.Locale.ROOT).contains("\"" + id.toLowerCase(java.util.Locale.ROOT) + "\""); }
        };
        this.app = app; this.sp = sp; this.seenKey = "seen_coins_" + fp[0];
        current = node;
        wallet = new VirtualWallet(cmd -> blocking(current, cmd), keys, store,
                new HashSet<>(Arrays.asList(com.eurobuddha.atomix.swap.MinimaHtlc.HTLC_ADDRESSES)));
    }

    /** The account, once set up on this phone; null until then. */
    public static Account get(Context c, NodeApi node) {
        Account a = instance;
        if (a != null) return a;
        synchronized (Account.class) {
            if (instance != null) return instance;
            AccountVault v = new AccountVault(c);
            if (!v.exists()) return null;
            if (System.currentTimeMillis() - lockedAt < 5_000L) return null;   // backoff: never decrypt on every command
            String secret;
            try { secret = v.secret(); }
            catch (AccountVault.Unavailable later) { lockedAt = System.currentTimeMillis(); return null; }   // retry later, delete nothing
            catch (AccountVault.Lost lost) { v.quarantine(); return null; }    // key gone for good: setup offers RESTORE
            v.markCreated();
            instance = new Account(c, secret, v.mode(), v.restoreFloor(), node);
            v.recordIdentity(instance.keys.fingerprint(), v.mode());
            return instance;
        }
    }

    public static boolean exists(Context c) { return instance != null || new AccountVault(c).exists(); }

    private static volatile java.util.Set<String> knownAddresses;   // null until the worker has listed them
    private static volatile long lockedAt;

    /** A {@code coins} read that names no account address and is not wallet-scoped (a coinid, a state search, a foreign
     *  address): straight to the node on the caller's thread — it must never wait behind a post on the account worker. */
    public static boolean chainRead(String command) {
        if (!command.trim().toLowerCase(java.util.Locale.ROOT).startsWith("coins")) return false;
        try {
            org.minima.utils.json.JSONObject p = org.minima.system.commands.CommandRunner.getRunner().getCommand(command).getParams();
            if (p.containsKey("relevant") || p.containsKey("sendable")) return false;
            Object a = p.get("address");
            if (a == null) return p.containsKey("coinid") || p.containsKey("state") || p.containsKey("tokenid");
            java.util.Set<String> mine = knownAddresses;
            if (mine == null) return false;                                    // not known yet: the account decides
            return !mine.contains(VirtualWallet.norm(String.valueOf(a)));      // foreign (book, pool, HTLC): straight through
        } catch (RuntimeException unparsable) { return false; }
    }

    /** 0.2.98 / 0.2.99 stored the key count under "keys" (and key i's public key under "pub128x4_i"). Move it to the
     *  fingerprint-keyed slot ONLY when the old key 0 is this account's key 0 — never hand one seed's count to another. */
    static void migrateKeyCount(SharedPreferences sp, String countKey, java.util.function.Supplier<String> myPub0) {
        if (!sp.contains("keys")) return;                       // nothing to migrate: never build key 0 here (main thread)
        String legacyPub0 = sp.getString("pub" + AccountKeys.KEYS_PER_LEVEL + "x" + AccountKeys.LEVELS + "_0", "");
        if (legacyPub0.isEmpty() || !legacyPub0.equalsIgnoreCase(myPub0.get())) return;
        // max: 0.3.0 already created the slot as 1 while "keys" held the real count (0.2.99 -> 0.3.0 -> 0.3.1)
        int merged = Math.max(sp.getInt(countKey, 0), sp.getInt("keys", 1));
        sp.edit().putInt(countKey, merged).remove("keys").commit();
    }

    /** A plain {@code coins} read (no relevant / sendable): with no account loaded there are no account addresses, so
     *  it can only be a chain read (the book, pools, the HTLC market feed) — the market screens work before setup. */
    public static boolean plainRead(String command) {
        if (!command.trim().toLowerCase(java.util.Locale.ROOT).startsWith("coins")) return false;
        try {
            org.minima.utils.json.JSONObject p = org.minima.system.commands.CommandRunner.getRunner().getCommand(command).getParams();
            return !p.containsKey("relevant") && !p.containsKey("sendable")
                    && (p.containsKey("address") || p.containsKey("coinid") || p.containsKey("state") || p.containsKey("tokenid"));
        } catch (RuntimeException unparsable) { return false; }
    }

    /** The fingerprint a typed phrase / node base would give: a restore must match the lost account's. */
    public static String fingerprintOfPhrase(String phrase) { return AccountKeys.fromPhrase(phrase, NO_USES, null).fingerprint(); }
    public static String fingerprintOfBase(String base) { return AccountKeys.fromBase(base, NO_USES, null).fingerprint(); }
    private static final KeyUses NO_USES = new KeyUses() {
        public int currentUses(int k) { return 0; }
        public int reserveNextUse(int k) { throw new IllegalStateException("fingerprint only"); }
        public void recordExternalUses(int k, int n) { }
        public java.util.Map<Integer, Integer> snapshotAllUses() { return java.util.Collections.emptyMap(); }
    };

    /** The user chose RESTORE for a vault the keystore will not unlock: move it aside (kept), so setup can restore. */
    public static void quarantineForRestore(Context c) { synchronized (Account.class) { if (instance == null) new AccountVault(c).quarantine(); } }

    /** A Trading Account was created on this phone at some point — from then on Dexicon never falls back to the node's
     *  own keys, even while the account itself is locked or being restored. */
    public static boolean everCreated(Context c) { return new AccountVault(c).everCreated(); }

    /** Run {@code command} on the account worker. Delivers on the main thread. A null local answer = pass through. */
    public void submit(NodeApi node, String command, java.util.function.BooleanSupplier guard, long timeoutMs, NodeApi.Cb cb) {
        final long queued = System.currentTimeMillis();
        final long patience = timeoutMs > 0 ? timeoutMs : 180_000L;
        WORKER.execute(() -> {
            JSONObject reply = null; String error = null; boolean passThrough = false;
            try {
                if (System.currentTimeMillis() - queued > patience) error = "The Trading Account was busy too long; nothing was sent. Try again.";
                else {   // the guard is evaluated exactly once, inside the wallet (it may have side effects)
                    current = node;
                    long now = System.currentTimeMillis();
                    if (now - trackedAt > RETRACK_MS) {
                        try {
                            wallet.ensureTracked(); trackedAt = now;
                            java.util.Set<String> k = new java.util.HashSet<>();
                            for (String a : wallet.accountAddresses()) k.add(VirtualWallet.norm(a));
                            knownAddresses = k;
                        }
                        catch (Exception later) { android.util.Log.w("Dexicon", "account re-track deferred: " + later); }   // never fails a claim
                    }
                    if (knownAddresses == null) refreshKnown();
                    reply = wallet.handle(command, guard == null ? null : () -> onMain(guard));
                    if (command.trim().startsWith("newaddress")) refreshKnown();
                    if (command.trim().startsWith("balance") && reply != null) scanIncoming();
                    passThrough = reply == null;
                }
            } catch (Exception e) { error = e.getMessage() == null ? e.toString() : e.getMessage(); }
            final JSONObject r = reply; final String err = error;
            if (passThrough) {
                // the wallet already evaluated the guard (exactly once) before passing the command through
                MAIN.post(() -> node.cmdDirect(command, null, timeoutMs, new NodeApi.Cb() {
                    public void onResult(JSONObject j) { noticeLocks(command, j); if (cb != null) cb.onResult(j); }
                    public void onError(String m) { if (cb != null) cb.onError(m); }
                }));
                return;
            }
            MAIN.post(() -> { if (cb == null) return; if (err != null) cb.onError(err); else cb.onResult(r); });
        });
    }

    /** Every 30 s at most, on the worker (after a balance read): account coins never seen before that are NOT outputs
     *  of a transaction this account signed arrived from outside — one "Received" row each in ACTIVITY. The first scan
     *  only records a baseline (no backfill: earlier coins can't be told apart). Best effort: never fails the caller. */
    private void scanIncoming() {
        long now = System.currentTimeMillis();
        if (now - scannedAt < 30_000L || app == null) return;
        scannedAt = now;
        try {
            JSONObject r = wallet.handle("coins relevant:true");
            if (r == null || !r.optBoolean("status")) return;
            org.json.JSONArray rows = r.optJSONArray("response");
            if (rows == null) return;
            String prev = sp.getString(seenKey, null);
            java.util.List<String> ids = new java.util.ArrayList<>();
            for (int i = 0; i < rows.length(); i++) ids.add(VirtualWallet.norm(rows.getJSONObject(i).optString("coinid")));
            sp.edit().putString(seenKey, VirtualWallet.boundedAdd(prev == null ? "[]" : prev, ids)).commit();
            if (prev == null) return;
            String seen = prev.toLowerCase(java.util.Locale.ROOT);
            com.eurobuddha.dexicon.DexDb db = null;
            for (int i = 0; i < rows.length(); i++) {
                JSONObject c = rows.getJSONObject(i);
                String id = VirtualWallet.norm(c.optString("coinid"));
                if (id.isEmpty() || seen.contains("\"" + id.toLowerCase(java.util.Locale.ROOT) + "\"") || wallet.isOwnOutput(id)) continue;
                if (db == null) db = new com.eurobuddha.dexicon.DexDb(app);
                String tok = c.optString("tokenid");
                String name = "0x00".equals(tok) ? "MINIMA" : tok.equalsIgnoreCase(com.eurobuddha.dexicon.fill.Tokens.MXUSD()) ? "MxUSD" : tok;
                com.eurobuddha.dexicon.ActivityLog.add(db, new JSONObject().put("id", "recv_" + id).put("kind", "received")
                        .put("amount", c.optString("tokenamount", c.optString("amount"))).put("token", name).put("tokenid", tok)
                        .put("to", c.optString("address")).put("ts", now).put("stage", com.eurobuddha.dexicon.ActivityLog.POSTED)
                        .put("block", c.optLong("created", 0)).put("outs", new org.json.JSONArray().put(c.optString("coinid"))));
            }
        } catch (Exception later) { android.util.Log.w("Dexicon", "incoming scan deferred: " + later); }
    }

    /** The account's receive address (index 0), full 0x and Mx forms: [0x, Mx]. Computed on the worker. */
    public void receiveAddress(java.util.function.Consumer<String[]> got) {
        WORKER.execute(() -> { String[] a = {keys.address(0), keys.miniAddress(0)}; MAIN.post(() -> got.accept(a)); });
    }

    private void refreshKnown() {
        java.util.Set<String> k = new java.util.HashSet<>();
        for (String a : wallet.accountAddresses()) k.add(VirtualWallet.norm(a));
        knownAddresses = k;
    }

    /** A chain read of a swap-lock address has just returned: look for locks addressed to us, off the caller's thread. */
    public void noticeLocks(String command, JSONObject reply) {
        if (reply == null || !command.contains("address:")) return;
        boolean htlc = false;
        for (String h : com.eurobuddha.atomix.swap.MinimaHtlc.HTLC_ADDRESSES) if (command.contains(h)) htlc = true;
        if (!htlc) return;
        WORKER.execute(() -> { try { wallet.noticeLocksToUs(reply); } catch (RuntimeException ignored) { } });
    }

    /** Engine guards read UI-thread state: evaluate them there, as they always were. */
    private static boolean onMain(java.util.function.BooleanSupplier guard) {
        if (Looper.myLooper() == Looper.getMainLooper()) return guard.getAsBoolean();
        CountDownLatch done = new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicBoolean out = new java.util.concurrent.atomic.AtomicBoolean(false);
        // 0 = waiting, 1 = the main thread took it, 2 = we gave up. A guard with side effects (PandaPools Write.admit)
        // runs only if it claims the slot first, so it can never run AFTER we reported a refusal.
        java.util.concurrent.atomic.AtomicInteger slot = new java.util.concurrent.atomic.AtomicInteger(0);
        MAIN.post(() -> { try { if (slot.compareAndSet(0, 1)) out.set(guard.getAsBoolean()); } catch (RuntimeException e) { out.set(false); } finally { done.countDown(); } });
        try {
            if (done.await(30, TimeUnit.SECONDS) || !slot.compareAndSet(0, 2)) { done.await(); return out.get(); }
            return false;
        } catch (InterruptedException e) { slot.compareAndSet(0, 2); return false; }
    }

    /** One node call from the worker thread, waiting for the reply (never on the main thread). */
    private static JSONObject blocking(NodeApi node, String cmd) throws Exception {
        if (Looper.myLooper() == Looper.getMainLooper()) throw new IllegalStateException("account node call on the main thread");
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<JSONObject> out = new AtomicReference<>();
        AtomicReference<String> err = new AtomicReference<>();
        java.util.concurrent.atomic.AtomicBoolean abandoned = new java.util.concurrent.atomic.AtomicBoolean(false);
        // a command still queued when we stop waiting is never sent: the caller has already been told it failed
        MAIN.post(() -> node.cmdDirect(cmd, () -> !abandoned.get(), 0, new NodeApi.Cb() {
            public void onResult(JSONObject j) { out.set(j); done.countDown(); }
            public void onError(String m) { err.set(m == null ? "node error" : m); done.countDown(); }
        }));
        if (!done.await(200, TimeUnit.SECONDS)) { abandoned.set(true); throw new IllegalStateException("The node did not answer " + cmd.split(" ", 2)[0]); }
        if (err.get() != null) throw new IllegalStateException(err.get());
        return out.get();
    }
}
