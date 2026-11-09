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

    private Account(Context c, String secret, String mode, NodeApi node) {
        Context app = c.getApplicationContext();
        SharedPreferences sp = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        AccountKeys.PubCache cache = new AccountKeys.PubCache() {
            // keyed by tree shape: a cached key of another shape must never be mistaken for this one
            public String get(int i) { return sp.getString("pub" + AccountKeys.KEYS_PER_LEVEL + "x" + AccountKeys.LEVELS + "_" + i, null); }
            public void put(int i, String p) { sp.edit().putString("pub" + AccountKeys.KEYS_PER_LEVEL + "x" + AccountKeys.LEVELS + "_" + i, p).commit(); }
        };
        KeyUses uses = new PrefsKeyUses(app);
        keys = AccountVault.MODE_NODE.equals(mode) ? AccountKeys.fromBase(secret, uses, cache) : AccountKeys.fromPhrase(secret, uses, cache);
        VirtualWallet.Store store = new VirtualWallet.Store() {
            public int keyCount() { return sp.getInt("keys", 0); }
            public void setKeyCount(int n) { if (!sp.edit().putInt("keys", n).commit()) throw new IllegalStateException("could not save key count"); }
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
            String secret;
            try { secret = v.secret(); } catch (Exception unreadable) { unreadable(c); return null; }
            instance = new Account(c, secret, v.mode(), node);
            return instance;
        }
    }

    public static boolean exists(Context c) { return instance != null || new AccountVault(c).exists(); }

    /** The vault exists but cannot be decrypted (the keystore key is gone, e.g. after a device restore): clear it so the
     *  setup screen offers RESTORE 24 WORDS. The time floor keeps a restore from ever reusing a leaf. */
    private static void unreadable(Context c) { new AccountVault(c).clearUnreadable(); }

    /** Run {@code command} on the account worker. Delivers on the main thread. A null local answer = pass through. */
    public void submit(NodeApi node, String command, java.util.function.BooleanSupplier guard, long timeoutMs, NodeApi.Cb cb) {
        final long queued = System.currentTimeMillis();
        final long patience = timeoutMs > 0 ? timeoutMs : 180_000L;
        WORKER.execute(() -> {
            JSONObject reply = null; String error = null; boolean passThrough = false;
            try {
                if (System.currentTimeMillis() - queued > patience) error = "The Trading Account was busy too long; nothing was sent. Try again.";
                else if (guard != null && !guard.getAsBoolean()) error = "Market changed; refresh and try again. No command was sent.";
                else {
                    current = node;
                    long now = System.currentTimeMillis();
                    if (now - trackedAt > RETRACK_MS) { wallet.ensureTracked(); trackedAt = now; }
                    reply = wallet.handle(command, guard);
                    passThrough = reply == null;
                }
            } catch (Exception e) { error = e.getMessage() == null ? e.toString() : e.getMessage(); }
            final JSONObject r = reply; final String err = error;
            if (passThrough) { MAIN.post(() -> node.cmdDirect(command, guard, timeoutMs, cb)); return; }
            MAIN.post(() -> { if (cb == null) return; if (err != null) cb.onError(err); else cb.onResult(r); });
        });
    }

    /** The account's receive address (index 0), full 0x and Mx forms: [0x, Mx]. Computed on the worker. */
    public void receiveAddress(java.util.function.Consumer<String[]> got) {
        WORKER.execute(() -> { String[] a = {keys.address(0), keys.miniAddress(0)}; MAIN.post(() -> got.accept(a)); });
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
