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
        migrateKeyCount(sp, countKey, keys.publicKey(0));
        VirtualWallet.Store store = new VirtualWallet.Store() {
            public int keyCount() { return sp.getInt(countKey, 0); }
            public void setKeyCount(int n) { if (!sp.edit().putInt(countKey, n).commit()) throw new IllegalStateException("could not save key count"); }
        };
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
    static void migrateKeyCount(SharedPreferences sp, String countKey, String myPub0) {
        String legacyPub0 = sp.getString("pub" + AccountKeys.KEYS_PER_LEVEL + "x" + AccountKeys.LEVELS + "_0", "");
        if (!sp.contains(countKey) && sp.contains("keys") && !legacyPub0.isEmpty() && legacyPub0.equalsIgnoreCase(myPub0))
            sp.edit().putInt(countKey, sp.getInt("keys", 1)).remove("keys").commit();
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
                else if (guard != null && !onMain(guard)) error = "Market changed; refresh and try again. No command was sent.";
                else {
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
                    passThrough = reply == null;
                }
            } catch (Exception e) { error = e.getMessage() == null ? e.toString() : e.getMessage(); }
            final JSONObject r = reply; final String err = error;
            if (passThrough) {
                MAIN.post(() -> node.cmdDirect(command, guard, timeoutMs, new NodeApi.Cb() {
                    public void onResult(JSONObject j) { noticeLocks(command, j); if (cb != null) cb.onResult(j); }
                    public void onError(String m) { if (cb != null) cb.onError(m); }
                }));
                return;
            }
            MAIN.post(() -> { if (cb == null) return; if (err != null) cb.onError(err); else cb.onResult(r); });
        });
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
        MAIN.post(() -> { try { out.set(guard.getAsBoolean()); } catch (RuntimeException e) { out.set(false); } finally { done.countDown(); } });
        try { return done.await(30, TimeUnit.SECONDS) && out.get(); } catch (InterruptedException e) { return false; }
    }

    /** One node call from the worker thread, waiting for the reply (never on the main thread). */
    private static JSONObject blocking(NodeApi node, String cmd) throws Exception {
        if (Looper.myLooper() == Looper.getMainLooper()) throw new IllegalStateException("account node call on the main thread");
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<JSONObject> out = new AtomicReference<>();
        AtomicReference<String> err = new AtomicReference<>();
        MAIN.post(() -> node.cmdDirect(cmd, null, 0, new NodeApi.Cb() {
            public void onResult(JSONObject j) { out.set(j); done.countDown(); }
            public void onError(String m) { err.set(m == null ? "node error" : m); done.countDown(); }
        }));
        if (!done.await(200, TimeUnit.SECONDS)) throw new IllegalStateException("The node did not answer " + cmd.split(" ", 2)[0]);
        if (err.get() != null) throw new IllegalStateException(err.get());
        return out.get();
    }
}
