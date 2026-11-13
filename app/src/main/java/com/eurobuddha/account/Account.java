package com.eurobuddha.account;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.util.HashSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The process-wide Trading Account: one {@link VirtualWallet} on one worker thread. The app's node layer hands it every
 * wallet / txn verb ({@link #route}); it answers locally and reaches the node only through {@link Transport#cmdDirect}
 * (reads, newscript, coinexport, and the single signed {@code postfrom}). Signing happens on this ONE thread in this
 * ONE process, so a one-time key leaf can never be handed out twice (the in-app replacement for fork commit
 * 26cc3983495acafcb1b99f10c744dc506e254b07).
 *
 * <p>Shared by Dexicon, AtomiX and PandaPools (vendored from Dexicon, the single source). The same 24 words give the
 * same keys in all three (domain {@link AccountKeys#DOMAIN}). A phone runs AtomiX + PandaPools side by side, or Dexicon
 * (see {@link Family}); apps holding the same words each sign only their own leaf share (AccountKeys leaf slots), so
 * their separate counters can never land on the same one-time leaf. The prefs and Keystore names
 * keep their original "dexicon_" spelling so existing vaults open unchanged.
 */
public final class Account {

    /** What each app supplies once, before the first {@link #get}. */
    public static final class Config {
        public final String appName;
        /** Contract addresses whose stateful outputs carry the owner port, and whose reads are watched for locks to us. */
        public final java.util.Set<String> contracts;
        /** Where incoming coins are recorded (wallet history); null = not recorded. */
        public final Incoming incoming;
        /** Dialog styling for {@link AccountSetup}; null = platform defaults. */
        public final AccountSetup.Look look;
        /** Extra warning on the setup screen (e.g. funds left at an old node-derived wallet), or null. */
        public final java.util.function.Function<android.app.Activity, String> setupNotice;
        /** This app's share of every key's one-time leaves ({@link AccountKeys#SLOT_DEXICON}, {@code SLOT_ATOMIX},
         *  {@code SLOT_PANDAPOOLS}). REQUIRED: two apps holding the same words must never share a slot. */
        public final int leafSlot;
        public Config(String appName, java.util.Collection<String> contracts, Incoming incoming, AccountSetup.Look look,
                      java.util.function.Function<android.app.Activity, String> setupNotice, int leafSlot) {
            if (leafSlot < 0 || leafSlot >= AccountKeys.LEAF_SLOTS) throw new IllegalArgumentException("leafSlot must be 0.." + (AccountKeys.LEAF_SLOTS - 1));
            this.appName = appName;
            this.contracts = java.util.Collections.unmodifiableSet(new HashSet<>(contracts == null ? java.util.Collections.<String>emptySet() : contracts));
            this.incoming = incoming; this.look = look; this.setupNotice = setupNotice; this.leafSlot = leafSlot;
        }
    }

    /** New account coins of one token that no transaction of this account created (a deposit, or an order fill). */
    public interface Incoming {
        void received(Context app, String tokenid, java.math.BigDecimal amount, org.json.JSONArray coins, long block,
                      java.util.List<String> addresses, long nowMs);
    }

    private static volatile Config config = new Config("Trading Account", null, null, null, null, 3);   // never signs: get() refuses until configure()
    private static volatile boolean configured;
    /** Must run before the first {@link #get}: the wallet caches the contract set when it is built (owner port 254 keeps
     *  locks relevant), so an unconfigured account would silently lose it. {@link #get} refuses until then. */
    public static void configure(Config c) { if (c != null) { config = c; configured = true; } }
    public static Config config() { return config; }

    /** Where a command goes when the app may hold a Trading Account — each app's node layer asks this first. */
    public enum Route {
        /** Straight to the node, as before. */ NODE,
        /** Straight to the node, then {@link #noticeLocks} on the reply. */ NODE_THEN_NOTICE,
        /** {@link #submit} it to the account. */ ACCOUNT,
        /** A wallet verb with no account loaded: the app refuses it (or, with legacy node-key positions, sends it). */ NO_ACCOUNT
    }

    public static Route route(Context c, Transport t, String command) {
        String verb = command.trim().split("\\s+", 2)[0].toLowerCase(java.util.Locale.ROOT);
        if ("txpow".equals(verb)) {   // loads the account (its known post ids) before deciding
            Account a = get(c, t);
            return a != null && VirtualWallet.answersTxpow(command) ? Route.ACCOUNT : Route.NODE;
        }
        if (!VirtualWallet.handles(verb)) return Route.NODE;
        Account a = get(c, t);
        // No account here yet: a coins read that names an address or a coin can only be about a contract or someone
        // else's coin (there are no account addresses) — a plain chain read. Before 0.3.30 it was refused, so AtomiX /
        // PandaPools opened before account setup showed an empty market (on the Fold, 10 Nov: every pool's reserve read
        // `coins relevant:true address:<pool>` refused -> "No live pools"). A bare wallet read stays refused.
        if (a == null && namesCoinOrAddress(command)) return Route.NODE;
        if (chainRead(command) || (a == null && plainRead(command))) return a == null ? Route.NODE : Route.NODE_THEN_NOTICE;
        return a != null ? Route.ACCOUNT : Route.NO_ACCOUNT;
    }

    private static final String PREFS = "dexicon_account_state";
    private static volatile Account instance;
    /** The MegaMMR download (~5.6 MB, up to 90 s) runs here, never on the signing worker. */
    private static final ExecutorService ARCHIVE = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "account-archive"); t.setDaemon(true); return t; });
    private static final long ARCHIVE_RETRY_MS = 30 * 60_000L;
    private volatile long archiveTriedAt;
    private volatile int archiveAttempts;   // per process: a download that keeps failing, or a refused proof, waits for the next start
    // ponytail: a reply that fails every time still costs up to 2 × 5.6 MB per app start; persist the count if seen.
    private static final int ARCHIVE_MAX_ATTEMPTS = 2;
    private final java.util.concurrent.atomic.AtomicBoolean archiveBusy = new java.util.concurrent.atomic.AtomicBoolean();
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "trading-account"); t.setDaemon(true); return t; });
    private static volatile Handler main;
    /** Created on first use, so loading this class never needs Android's main Looper (JVM unit tests load it). */
    private static Handler main() { Handler h = main; if (h == null) main = h = new Handler(Looper.getMainLooper()); return h; }

    private final VirtualWallet wallet;
    private Context app;
    private SharedPreferences sp;
    private String seenKey;
    private long scannedAt;
    private java.util.LinkedHashSet<String> seen;   // loaded once; null until the first scan
    private boolean baseline;
    private final AccountKeys keys;
    private volatile long trackedAt;
    private static final long RETRACK_MS = 10 * 60_000L;   // a companion can downgrade our script rows (newscript replaces)
    /** The transport of the call being handled: screens come and go, the account outlives them. Worker-thread only. */
    private volatile Transport current;

    private Account(Context c, String secret, String mode, int restoreFloor, Transport node) {
        Context app = c.getApplicationContext();
        SharedPreferences sp = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        final String[] fp = {""};   // filled once the keys exist: the cache below is keyed by the seed's fingerprint
        AccountKeys.PubCache cache = new AccountKeys.PubCache() {
            private String key(int i) { return "pub" + AccountKeys.KEYS_PER_LEVEL + "x" + AccountKeys.LEVELS + "_" + fp[0] + "_" + i; }
            public String get(int i) { return fp[0].isEmpty() ? null : sp.getString(key(i), null); }
            public void put(int i, String p) { if (!fp[0].isEmpty()) sp.edit().putString(key(i), p).commit(); }
        };
        KeyUses uses = new PrefsKeyUses(app);
        keys = AccountVault.MODE_NODE.equals(mode) ? AccountKeys.fromBase(secret, uses, cache)
                : AccountVault.MODE_ANY.equals(mode) ? AccountKeys.fromAnyPhrase(secret, uses, cache)
                : AccountKeys.fromPhrase(secret, uses, cache);
        keys.setRestoreFloor(restoreFloor);
        // the share is defined ONCE, by the app's package (Family): a Config that names another app's share is refused
        int ownSlot = Family.slotOf(app.getPackageName());
        if (ownSlot >= 0 && ownSlot != config.leafSlot)
            throw new IllegalStateException("leaf share " + config.leafSlot + " configured, but " + app.getPackageName() + " signs share " + ownSlot);
        keys.setLeafSlot(config.leafSlot);
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
            private java.util.LinkedHashSet<String> own;   // loaded once, persisted on change
            private java.util.LinkedHashSet<String> own() {
                if (own == null) {
                    own = new java.util.LinkedHashSet<>();
                    try { org.json.JSONArray a = new org.json.JSONArray(sp.getString(ownKey, "[]")); for (int i = 0; i < a.length(); i++) own.add(a.getString(i)); } catch (Exception fresh) { }
                }
                return own;
            }
            public synchronized void ownOutputs(java.util.Collection<String> ids) {
                if (!own().addAll(ids)) return;
                sp.edit().putString(ownKey, VirtualWallet.boundedAdd("[]", own)).apply();
                if (own.size() > 2000) { java.util.Iterator<String> it = own.iterator(); while (own.size() > 2000) { it.next(); it.remove(); } }
            }
            public synchronized boolean isOwnOutput(String id) { return own().contains(id); }
            public String posted() { return sp.getString("posted_" + fp[0], "{}"); }
            public boolean savePosted(String json) { return sp.edit().putString("posted_" + fp[0], json).commit(); }
            public int archivedKeys(String nodeId) { return sp.getInt("archived_" + fp[0] + "_" + nodeId.toLowerCase(java.util.Locale.ROOT), 0); }
            public void setArchivedKeys(String nodeId, int n) { sp.edit().putInt("archived_" + fp[0] + "_" + nodeId.toLowerCase(java.util.Locale.ROOT), n).apply(); }
        };
        this.app = app; this.sp = sp; this.seenKey = "seen_coins_" + fp[0];
        current = node;
        wallet = new VirtualWallet(cmd -> blocking(current, cmd), keys, store, config.contracts);
    }

    /** The account, once set up on this phone; null until then. */
    public static Account get(Context c, Transport node) {
        Account a = instance;
        if (a != null) return a;
        if (!configured) throw new IllegalStateException("Account.configure(...) must run before the Trading Account is opened");
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
    /** A coins read that names an address or a coin id, or a balance read that names an address (any relevant: /
     *  sendable: flags aside): with no account these can only be about a contract or someone else's coins. PandaPools
     *  counts a pool's coins with `balance address:<pool>` before reading them (ReserveRecovery.readCoins). */
    static boolean namesCoinOrAddress(String command) {
        String verb = command.trim().split("\\s+", 2)[0].toLowerCase(java.util.Locale.ROOT);
        if (!verb.equals("coins") && !verb.equals("balance")) return false;
        try {
            org.minima.utils.json.JSONObject p = org.minima.system.commands.CommandRunner.getRunner().getCommand(command).getParams();
            return p.containsKey("address") || (verb.equals("coins") && p.containsKey("coinid"));
        } catch (RuntimeException unparsable) { return false; }
    }

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
    public static String fingerprintOfAnyPhrase(String phrase) { return AccountKeys.fromAnyPhrase(phrase, NO_USES, null).fingerprint(); }
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
    public void submit(Transport node, String command, java.util.function.BooleanSupplier guard, long timeoutMs, Transport.Reply cb) {
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
                            archivePass();
                        }
                        catch (Exception later) { android.util.Log.w(config.appName, "account re-track deferred: " + later); }   // never fails a claim
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
                main().post(() -> node.cmdDirect(command, null, timeoutMs, new Transport.Reply() {
                    public void onResult(JSONObject j) { noticeLocks(command, j); if (cb != null) cb.onResult(j); }
                    public void onError(String m) { if (cb != null) cb.onError(m); }
                }));
                return;
            }
            main().post(() -> { if (cb == null) return; if (err != null) cb.onError(err); else cb.onResult(r); });
        });
    }

    /** Every 30 s at most, on the worker (after a balance read): account coins never seen before that are NOT outputs
     *  of a transaction this account signed arrived from outside. One ACTIVITY row per token per scan (amounts summed
     *  exactly, every coin listed) — a dust flood can never bury the trade rows (wallet rows have their own cap). The
     *  rows are written BEFORE the seen set is saved, so a failure retries instead of losing a deposit. The first scan
     *  is a baseline only (no backfill). Best effort: never fails the caller. */
    /** On the worker: coins older than this node's tracking of the account are fetched from the MegaMMR archive once
     *  per node and key count (VirtualWallet.archiveWanted / importArchived). The ~5.6 MB download runs on ARCHIVE; the
     *  proofs come back to the worker in batches of ARCHIVE_BATCH so signing interleaves. At most ARCHIVE_MAX_ATTEMPTS per
     *  process, ARCHIVE_RETRY_MS apart — never a download loop. Any Throwable (a hostile reply can force an OOM) ends the
     *  attempt; it never reaches the thread's uncaught handler. */
    private void archivePass() {
        long now = System.currentTimeMillis();
        if (archiveAttempts >= ARCHIVE_MAX_ATTEMPTS || now - archiveTriedAt < ARCHIVE_RETRY_MS || !archiveBusy.compareAndSet(false, true)) return;
        try {
            String nodeId = wallet.archiveWanted();
            if (nodeId == null) { archiveBusy.set(false); return; }
            archiveTriedAt = now; archiveAttempts++;
            java.util.List<String> addresses = wallet.accountAddresses();
            ARCHIVE.execute(() -> {
                try {
                    java.util.List<org.minima.objects.CoinProof> proofs = ArchiveProofs.fetch(addresses);
                    importBatch(nodeId, addresses.size(), proofs, 0, true);
                } catch (Throwable e) { android.util.Log.w(config.appName, "archive fetch deferred: " + e); archiveBusy.set(false); }
            });
        } catch (Throwable e) { archiveBusy.set(false); android.util.Log.w(config.appName, "archive check deferred: " + e); }
    }

    private void importBatch(String nodeId, int keyCount, java.util.List<org.minima.objects.CoinProof> proofs, int from, boolean clean) {
        WORKER.execute(() -> {
            boolean ok = clean;
            try {
                int to = Math.min(proofs.size(), from + VirtualWallet.ARCHIVE_BATCH);
                ok &= wallet.importArchived(proofs.subList(from, to), line -> android.util.Log.i(config.appName, "archive: " + line));
                if (to < proofs.size()) { importBatch(nodeId, keyCount, proofs, to, ok); return; }
                if (ok) wallet.markArchived(nodeId, keyCount);
                android.util.Log.i(config.appName, "archive pass over " + proofs.size() + " coin(s) " + (ok ? "complete" : "incomplete, retried later"));
            } catch (Throwable e) { android.util.Log.w(config.appName, "archive import deferred: " + e); }
            archiveBusy.set(false);
        });
    }

    private void scanIncoming() {
        long now = System.currentTimeMillis();
        if (now - scannedAt < 30_000L || app == null) return;
        scannedAt = now;
        try {
            JSONObject r = wallet.handle("coins relevant:true");
            if (r == null || !r.optBoolean("status")) return;
            org.json.JSONArray rows = r.optJSONArray("response");
            if (rows == null) return;
            wallet.noteCoins(rows);   // our posts' outputs, seen with their blocks (txpow onchain: evidence)
            if (seen == null) {
                String prev = sp.getString(seenKey, null);
                seen = new java.util.LinkedHashSet<>();
                if (prev != null) { org.json.JSONArray a = new org.json.JSONArray(prev); for (int i = 0; i < a.length(); i++) seen.add(a.getString(i)); }
                else baseline = true;
            }
            java.util.List<String> ids = new java.util.ArrayList<>();
            java.util.Map<String, java.math.BigDecimal> sum = new java.util.LinkedHashMap<>();
            java.util.Map<String, org.json.JSONArray> coins = new java.util.LinkedHashMap<>();
            java.util.Map<String, Long> block = new java.util.HashMap<>();
            java.util.Map<String, java.util.LinkedHashSet<String>> tos = new java.util.HashMap<>();
            for (int i = 0; i < rows.length(); i++) {
                JSONObject c = rows.getJSONObject(i);
                String id = VirtualWallet.norm(c.optString("coinid"));
                if (id.isEmpty()) continue;
                ids.add(id);
                if (baseline || seen.contains(id) || wallet.isOwnOutput(id)) continue;
                String tok = c.optString("tokenid");
                sum.merge(tok, new java.math.BigDecimal(c.optString("tokenamount", c.optString("amount", "0"))), java.math.BigDecimal::add);
                coins.computeIfAbsent(tok, x -> new org.json.JSONArray()).put(c.optString("coinid"));
                block.merge(tok, c.optLong("created", 0), Math::max);
                tos.computeIfAbsent(tok, x -> new java.util.LinkedHashSet<>()).add(c.optString("address"));
            }
            Incoming in = config.incoming;
            if (!sum.isEmpty() && in != null)   // the app records them (rows written BEFORE the seen set is saved)
                for (java.util.Map.Entry<String, java.math.BigDecimal> e : sum.entrySet())
                    in.received(app, e.getKey(), e.getValue(), coins.get(e.getKey()), block.get(e.getKey()),
                            new java.util.ArrayList<>(tos.get(e.getKey())), now);
            seen.addAll(ids);
            baseline = false;
            // spent coins never come back: evict only ids NOT in this scan, so a wallet larger than the cap never rotates
            // its own coins back in as phantom "received" rows
            int cap = Math.max(2000, 2 * ids.size());
            if (seen.size() > cap) {
                java.util.Set<String> current = new java.util.HashSet<>(ids);
                java.util.Iterator<String> it = seen.iterator();
                while (seen.size() > cap && it.hasNext()) if (!current.contains(it.next())) it.remove();
            }
            sp.edit().putString(seenKey, new org.json.JSONArray(new java.util.ArrayList<>(seen)).toString()).apply();
        } catch (Exception later) { android.util.Log.w(config.appName, "incoming scan deferred: " + later); }
    }

    /** The account's receive address (index 0), full 0x and Mx forms: [0x, Mx]. Computed on the worker. */
    public void receiveAddress(java.util.function.Consumer<String[]> got) {
        WORKER.execute(() -> { String[] a = {keys.address(0), keys.miniAddress(0)}; main().post(() -> got.accept(a)); });
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
        for (String h : config.contracts) if (command.contains(h)) htlc = true;
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
        main().post(() -> { try { if (slot.compareAndSet(0, 1)) out.set(guard.getAsBoolean()); } catch (RuntimeException e) { out.set(false); } finally { done.countDown(); } });
        try {
            if (done.await(30, TimeUnit.SECONDS) || !slot.compareAndSet(0, 2)) { done.await(); return out.get(); }
            return false;
        } catch (InterruptedException e) { slot.compareAndSet(0, 2); return false; }
    }

    /** One node call from the worker thread, waiting for the reply (never on the main thread). */
    private static JSONObject blocking(Transport node, String cmd) throws Exception {
        if (Looper.myLooper() == Looper.getMainLooper()) throw new IllegalStateException("account node call on the main thread");
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<JSONObject> out = new AtomicReference<>();
        AtomicReference<String> err = new AtomicReference<>();
        java.util.concurrent.atomic.AtomicBoolean abandoned = new java.util.concurrent.atomic.AtomicBoolean(false);
        // a command still queued when we stop waiting is never sent: the caller has already been told it failed
        main().post(() -> node.cmdDirect(cmd, () -> !abandoned.get(), 0, new Transport.Reply() {
            public void onResult(JSONObject j) { out.set(j); done.countDown(); }
            public void onError(String m) { err.set(m == null ? "node error" : m); done.countDown(); }
        }));
        if (!done.await(200, TimeUnit.SECONDS)) { abandoned.set(true); throw new IllegalStateException("The node did not answer " + cmd.split(" ", 2)[0]); }
        if (err.get() != null) throw new IllegalStateException(err.get());
        return out.get();
    }
}
