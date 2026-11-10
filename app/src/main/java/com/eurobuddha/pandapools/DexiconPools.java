package com.eurobuddha.pandapools;

import android.app.NotificationManager;
import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * DEXICON-OWNED (not vendored): the ONE public door from Dexicon's own code (com.eurobuddha.dexicon.*) into the vendored
 * PandaPools engine. Everything here only calls the engine's own entry points; nothing keeps a second pool store.
 *
 * <b>Keep-alive</b> ({@link #keepAlivePass}, {@link #republish}): PandaPools' PoolKeepAliveService pass — recreate my aging
 * reserves before the cascade edge, raise the stranding alarm, re-announce faded beacons, finish collections — run from
 * Dexicon's one foreground service (MakerService). It is settlement: it runs even while the trading gate is shut.
 *
 * <b>Custody — who keeps each pool</b> (0.1.74; replaces the AWAITING/HANDED inference): Dexicon never infers it. While
 * standalone PandaPools is installed, the ONLY source is PandaPools' own custody signal, the exported provider
 * {@code content://com.eurobuddha.pandapools.handover/state} (PandaPools 0.9.81+; it answers only Dexicon signed with the
 * same key): one row per recipe PandaPools holds — active AND retired since PandaPools 0.9.82 — {@code kept_here} or
 * {@code handed_over}. A pool PandaPools lists
 * as kept_here is kept elsewhere; any other pool (handed over, absent, PandaPools reinstalled with nothing) is Dexicon's.
 * Unreadable (an older PandaPools, null, an error, a provider that is not PandaPools' own) → EVERY pool counts as kept
 * elsewhere: nobody signs from Dexicon, and the gate card says to update PandaPools. PandaPools not installed → Dexicon
 * keeps every recipe it holds. The read is cached with its time and NEVER taken on the main thread: re-read in the
 * background at every keep-alive pass start (the pass runs once it lands) and every ~2 s while that pass runs
 * ({@link #keepCustodyWarm}), before each owner-signing step when over
 * ~5 s old ({@link #keptElsewhereToSign}: off the main thread it re-reads; on it, it kicks a background read and counts
 * the pool as kept elsewhere for that step), on onResume, and whenever a screen finds the cache over a minute old. The
 * hand-over intent (dexicon_pools / pools /
 * kept_by_dexicon) is only the permission to uninstall; custody never comes from it.
 */
public final class DexiconPools {
    private DexiconPools() {}

    public static final String EXTRA_DEXICON_POOLS = "dexicon_pools", EXTRA_POOLS = "pools", EXTRA_KEPT = "kept_by_dexicon";
    public static final String CUSTODY_AUTHORITY = "com.eurobuddha.pandapools.handover", KEPT_HERE = "kept_here";
    static final android.net.Uri CUSTODY_URI = android.net.Uri.parse("content://" + CUSTODY_AUTHORITY + "/state");
    /** The retired AWAITING/HANDED hand-over sets (≤0.1.73). Never read; {@link #readCustody} deletes them. */
    private static final String PREFS = "dexicon_pools_handover", AWAITING = "awaiting", HANDED = "handed";
    static final long PASS_GAP_MS = 5 * 60_000, CUSTODY_MAX_AGE_MS = 60_000;
    private static long lastPassMs;

    private static SharedPreferences prefs(Context c) { return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE); }
    private static String low(String a) { return a == null ? "" : a.trim().toLowerCase(Locale.ROOT); }

    // ---- custody (PandaPools' provider) ----

    /** One custody read. {@code keptHere} null = PandaPools is installed but could not be read. */
    public static final class Custody {
        public final boolean installed;
        public final Set<String> keptHere;
        public final long at;
        Custody(boolean installed, Set<String> keptHere, long at) { this.installed = installed; this.keptHere = keptHere; this.at = at; }
        public boolean unreadable() { return installed && keptHere == null; }
    }
    private static volatile Custody custody;

    private static boolean pandaPoolsInstalled(Context c) {
        return com.eurobuddha.dexicon.TradingGate.installed(c, com.eurobuddha.dexicon.TradingGate.PANDAPOOLS);
    }

    /** Re-read PandaPools' custody signal now and cache it (keep-alive pass start, before an owner-signing step, and in
     *  the background for the UI). A read never replaces one that STARTED later (reads may overlap across threads). */
    public static Custody readCustody(Context c) {
        Context ctx = c.getApplicationContext();
        long start = System.currentTimeMillis();
        boolean installed = pandaPoolsInstalled(ctx);
        Custody k = new Custody(installed, installed ? queryKeptHere(ctx) : null, start);
        synchronized (DexiconPools.class) { if (custody == null || custody.at <= start) custody = k; else k = custody; }
        try { if (prefs(ctx).contains(AWAITING) || prefs(ctx).contains(HANDED)) prefs(ctx).edit().remove(AWAITING).remove(HANDED).apply(); }
        catch (RuntimeException ignored) { /* the retired sets are never read anyway */ }
        return k;
    }

    /** The cached read, re-read when missing, over a minute old, or taken before PandaPools was (un)installed. */
    public static Custody custody(Context c) { return custody(c, CUSTODY_MAX_AGE_MS); }
    private static Custody custody(Context c, long maxAgeMs) {
        Custody k = custody;
        if (stale(c, k, maxAgeMs)) k = readCustody(c);
        return k;
    }
    private static boolean stale(Context c, Custody k, long maxAgeMs) {
        return k == null || k.installed != pandaPoolsInstalled(c) || System.currentTimeMillis() - k.at > maxAgeMs;
    }

    /** Before an owner-signing step (refresh, re-announce, sweep, Re-publish): custody at most this old. A take-back in
     *  PandaPools between the pass start and the signature must stop the signature. */
    static final long CUSTODY_SIGN_MAX_AGE_MS = 5_000;

    /** As {@link #keptElsewhere}, on a read at most ~5 s old — for the step that is about to sign with a pool owner key.
     *  Never a provider read on the main thread: there, an older read kicks a background re-read and FAILS CLOSED (kept
     *  elsewhere) for this step; the next pass signs on the fresh read. */
    public static boolean keptElsewhereToSign(Context c, String address) {
        if (c == null || address == null) return false;
        if (!pandaPoolsInstalled(c)) return false;
        Custody k = custody;
        if (stale(c, k, CUSTODY_SIGN_MAX_AGE_MS)) {
            if (onMainThread()) { refreshInBackground(c, null); return true; }
            k = readCustody(c);
        }
        return k.keptHere == null || k.keptHere.contains(low(address));
    }
    private static boolean onMainThread() { return android.os.Looper.myLooper() == android.os.Looper.getMainLooper(); }

    // ---- custody for the UI: never a provider read on the main thread ----

    private static java.util.concurrent.Executor custodyIo = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "dexicon-custody"); t.setDaemon(true); return t;
    });
    /** The callbacks waiting on the one background read in flight (guarded by itself); null = no read in flight. */
    private static final Object READ_LOCK = new Object();
    private static List<Runnable> waiting;
    /** Tests run the background read inline. */
    public static void custodyExecutorForTest(java.util.concurrent.Executor e) { custodyIo = e; }

    /** Re-read custody OFF the main thread; {@code onMain} (may be null) follows on the main thread with the result cached. */
    public static void readCustodyAsync(Context c, Runnable onMain) {
        final Context ctx = c.getApplicationContext();
        final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
        custodyIo.execute(() -> {
            try { readCustody(ctx); } catch (RuntimeException ignored) { /* unread: the cache stays as it was */ }
            if (onMain != null) main.post(onMain);
        });
    }

    /** UI: the cached custody, never read on the calling thread (null until the first read). When missing or stale, ONE
     *  background read runs and {@code onFresh} follows on the main thread. */
    public static Custody custodyForUi(Context c, Runnable onFresh) {
        Custody k = custody;
        if (stale(c, k, CUSTODY_MAX_AGE_MS)) refreshInBackground(c, onFresh);
        return k;
    }

    /** At most ONE background read in flight; every caller's {@code onFresh} (may be null) runs on the main thread once it lands. */
    private static void refreshInBackground(Context c, Runnable onFresh) {
        boolean start;
        synchronized (READ_LOCK) {
            start = waiting == null;
            if (start) waiting = new ArrayList<>();
            if (onFresh != null) waiting.add(onFresh);
        }
        if (!start) return;
        final Context ctx = c.getApplicationContext();
        final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
        custodyIo.execute(() -> {
            try { readCustody(ctx); } catch (RuntimeException ignored) { /* unread: the cache stays as it was */ }
            finally {   // even an Error must hand off: a stuck `waiting` would disable every later background read
                List<Runnable> run;
                synchronized (READ_LOCK) { run = waiting; waiting = null; }   // a later caller starts a new read
                if (run != null) for (Runnable r : run) main.post(r);
            }
        });
    }

    /** True when an owner-signing step may decide on the cached read without re-reading (PandaPools not installed, or a
     *  read under ~5 s old) — e.g. while a keep-alive pass keeps it warm. */
    public static boolean custodyFreshToSign(Context c) {
        return c != null && (!pandaPoolsInstalled(c) || !stale(c, custody, CUSTODY_SIGN_MAX_AGE_MS));
    }

    /** Run {@code r} on the custody thread (never the main thread). */
    static void offMain(Runnable r) { custodyIo.execute(r); }

    /** Pool {@code address} is kept elsewhere per read {@code k} (no read yet = kept elsewhere, fail closed). */
    private static boolean keptBy(Context c, Custody k, String address) {
        if (!pandaPoolsInstalled(c)) return false;
        return k == null || k.keptHere == null || k.keptHere.contains(low(address));
    }
    /** UI: PandaPools is installed and this read could not tell which pools it keeps. */
    public static boolean unreadableForUi(Context c, Custody k) { return k != null && pandaPoolsInstalled(c) && k.unreadable(); }
    /** UI: the held pools this read says another app keeps (none shown until the first read lands). */
    public static List<String> keptElsewhereForUi(Context c, Custody k) {
        List<String> out = new ArrayList<>();
        if (k == null || !pandaPoolsInstalled(c)) return out;
        for (String a : heldAddresses(c)) if (k.keptHere == null || k.keptHere.contains(a)) out.add(a);
        java.util.Collections.sort(out);
        return out;
    }

    /** Lower-case addresses PandaPools says it keeps, or null when its provider cannot be read or is not PandaPools' own. */
    static Set<String> queryKeptHere(Context c) {
        String pp = com.eurobuddha.dexicon.TradingGate.PANDAPOOLS;
        try {
            android.content.pm.PackageManager pm = c.getPackageManager();
            android.content.pm.ProviderInfo info = pm.resolveContentProvider(CUSTODY_AUTHORITY, 0);
            if (info == null || !pp.equals(info.packageName)) return null;   // an older PandaPools, or someone else's provider
            if (pm.checkSignatures(pp, c.getPackageName()) != android.content.pm.PackageManager.SIGNATURE_MATCH) return null;
            try (android.database.Cursor cur = c.getContentResolver().query(CUSTODY_URI, null, null, null, null)) {
                if (cur == null) return null;
                int a = cur.getColumnIndex("address"), s = cur.getColumnIndex("state");
                if (a < 0 || s < 0) return null;
                Set<String> out = new HashSet<>();
                while (cur.moveToNext()) if (KEPT_HERE.equals(cur.getString(s)) && !low(cur.getString(a)).isEmpty()) out.add(low(cur.getString(a)));
                return out;
            }
        } catch (RuntimeException unreadable) { return null; }
    }

    /** True when Dexicon must not sign for this pool: standalone PandaPools keeps it, or PandaPools is installed and its
     *  custody signal cannot be read (then every pool is treated as kept there). */
    public static boolean keptElsewhere(Context c, String address) {
        if (c == null || address == null) return false;
        if (!pandaPoolsInstalled(c)) return false;
        Custody k = custody(c);
        return k.keptHere == null || k.keptHere.contains(low(address));
    }

    /** PandaPools is installed and cannot tell Dexicon which pools it keeps: nothing is signed from here; update it. */
    public static boolean custodyUnreadable(Context c) { return pandaPoolsInstalled(c) && custody(c).unreadable(); }

    /** Every covenant address Dexicon holds a recipe for — the "dexicon_pools" extra of the hand-over request. */
    public static String[] heldAddresses(Context c) {
        List<String> out = new ArrayList<>();
        for (Pool p : OwnPoolStore.active(c)) if (p.address != null && !low(p.address).isEmpty() && !out.contains(low(p.address))) out.add(low(p.address));
        return out.toArray(new String[0]);
    }

    /** Pool owner payout addresses (lower-case, full) of every recipe here: a coin there signs with the pool's one-time owner
     *  key, so no Dexicon trade, order or self-send may select it. FAILS CLOSED: null when the recipes cannot be read (or
     *  there is no context) — the caller must then refuse the selection / send, never treat it as "no owner coins". */
    public static Set<String> ownerAddresses(Context c) {
        if (c == null) return null;
        try { return OwnPoolStore.ownerAddresses(c.getApplicationContext()); } catch (RuntimeException unreadable) { return null; }
    }
    public static final String OWNERS_UNREADABLE = "Could not read your pool recipes, so a pool owner-key coin cannot be ruled out. Nothing was sent.";

    /** {@code coins} reply rows minus every row at a pool owner address; null when the owner addresses cannot be read. */
    public static org.json.JSONObject withoutOwnerCoins(Context c, org.json.JSONObject reply) {
        Set<String> owners = ownerAddresses(c);
        if (owners == null) return null;
        Object r = reply == null ? null : reply.opt("response");
        if (owners.isEmpty() || !(r instanceof org.json.JSONArray)) return reply;
        try {
            org.json.JSONArray in = (org.json.JSONArray) r, out = new org.json.JSONArray();
            for (int i = 0; i < in.length(); i++) {
                org.json.JSONObject row = in.optJSONObject(i);
                if (row == null || !owners.contains(low(row.optString("address", "")))) out.put(in.get(i));
            }
            return new org.json.JSONObject(reply.toString()).put("response", out);
        } catch (org.json.JSONException e) { return null; }
    }

    /** Every refusal of {@link #guardNodePickedSpend} starts with this: nothing reached the node, so a refusal is never an
     *  uncertain write (whatever words follow, e.g. a coin read that "didn't respond"). */
    public static final String REFUSED = "Nothing was sent: ";
    public static boolean refusal(String message) { return message != null && (message.startsWith(REFUSED) || message.equals(OWNERS_UNREADABLE)); }

    /**
     * Before a NODE-PICKED spend of {@code tokenid} (send / consolidate / split: the node chooses the inputs, so it may take a
     * coin at a pool owner address and sign with that pool's one-time owner key). Reads each owner address on its own
     * ({@code address:} — bounded, never the whole fragmented wallet). Owner decision (0.1.83): a coin there blocks the
     * spend only when signing with it could harm its pool — (a) PandaPools' own OwnerKeyRecovery.checkConsolidation says
     * that pool's owner key is NOT safe to sign with, or (b) that pool is kept elsewhere (standalone PandaPools keeps it,
     * or its custody is unreadable). Otherwise the spend goes ahead: dust anyone sends to a public $OADR no longer blocks
     * comms posts, HTLC locks, splits or door forwards. Nothing is queued for collection here (an evidence-less
     * PendingCollect entry would block that address's real collections). {@code cb} gets null (go ahead)
     * or why nothing was sent (starting {@link #REFUSED}, or {@link #OWNERS_UNREADABLE}). Fails closed: unreadable
     * recipes, coins, keys or custody refuse.
     */
    public static void guardNodePickedSpend(com.eurobuddha.dexicon.NodeApi dex, String tokenid, java.util.function.Consumer<String> cb) {
        final Context ctx = dex == null ? null : dex.context();
        final Set<String> owners = ownerAddresses(ctx);
        if (owners == null) { cb.accept(OWNERS_UNREADABLE); return; }
        guardOwner(dex, tokenid, new ArrayList<>(owners), 0, new ArrayList<>(), cb);
    }

    private static void guardOwner(com.eurobuddha.dexicon.NodeApi dex, String tokenid, List<String> owners, int i, List<String> found, java.util.function.Consumer<String> cb) {
        if (i >= owners.size()) { judgeOwnerCoins(dex, found, cb); return; }
        final String oadr = owners.get(i);
        dex.cmd("coins relevant:true sendable:true tokenid:" + tokenid + " address:" + oadr, new com.eurobuddha.dexicon.NodeApi.Cb() {
            @Override public void onResult(org.json.JSONObject j) {
                if (!j.optBoolean("status", false)) { cb.accept(REFUSED + "could not read your wallet coins, so a pool owner-key coin cannot be ruled out."); return; }
                for (org.json.JSONObject row : com.eurobuddha.dexicon.NodeApi.rows(j))
                    if (oadr.equals(low(row.optString("address", "")))) { found.add(oadr); break; }
                guardOwner(dex, tokenid, owners, i + 1, found, cb);
            }
            @Override public void onError(String m) { cb.accept(REFUSED + "could not read your wallet coins (" + m + ")."); }
        });
    }

    private static final String OWNER_COINS = "the node would choose the coins itself, and your wallet holds coins of this token at the pool owner address\n";

    /** Why a node-picked spend was refused because of a pool Dexicon does not (or cannot confirm it does) keep. A stale
     *  custody read only asks for a retry (the retry re-reads it); the other two point to PandaPools. Full addresses. */
    static String keptElsewhereRefusal(boolean unsure, Custody k, String oadr, String poolAddress) {
        String head = REFUSED + OWNER_COINS + low(oadr) + "\nof the pool\n" + low(poolAddress) + "\n";
        if (unsure) return head + "whose keeper Dexicon could not confirm just now, so Dexicon did not sign with its owner key. Try again in a moment.";
        return head + (k == null || k.keptHere == null ? "which PandaPools may still keep (it is installed and could not say which pools it keeps)"
                                                       : "which standalone PandaPools still keeps")
                + ", so Dexicon never signs with its owner key. Collect them in PandaPools (or hand the pool over), then try again.";
    }

    /** Coins of the token sit at the owner addresses {@code found}: refuse only if a pool there is kept elsewhere (or its
     *  custody is unknown or stale) or its owner key is not safe to sign with. Nothing is queued here: a PendingCollect
     *  entry created without evidence parses as legacy forever and would block that address's real collections. */
    private static void judgeOwnerCoins(com.eurobuddha.dexicon.NodeApi dex, List<String> found, java.util.function.Consumer<String> cb) {
        if (found.isEmpty()) { cb.accept(null); return; }
        final Context ctx = dex.context().getApplicationContext();
        final java.util.function.Supplier<List<Pool>> pools = () -> {
            List<Pool> out = new ArrayList<>();
            for (Pool p : OwnPoolStore.all(ctx)) if (p.oadr != null && found.contains(low(p.oadr))) out.add(p);
            return out;
        };
        Runnable judge = () -> {
            Custody k = custody;
            boolean unsure = pandaPoolsInstalled(ctx) && stale(ctx, k, CUSTODY_SIGN_MAX_AGE_MS);   // a failed read leaves an old cache
            for (Pool p : pools.get())
                if (unsure || keptBy(ctx, k, p.address)) { cb.accept(keptElsewhereRefusal(unsure, k, p.oadr, p.address)); return; }
            // PandaPools' check passes a failed `keys` read's message straight through; a null/empty one would read as
            // "safe" — so every error carries text here (fail closed).
            final NodeApi keysNode = new NodeApi(dex);
            FundingCoins.Command keysRead = (command, kcb) -> keysNode.cmd(command, new NodeApi.Cb() {
                public void onResult(org.json.JSONObject reply) { kcb.onResult(reply); }
                public void onError(String e) { kcb.onError(e == null || e.trim().isEmpty() ? "Could not verify current wallet keys (no answer)." : e); }
            });
            OwnerKeyRecovery.checkConsolidation(keysRead, pools, why -> {
                if (why == null) { cb.accept(null); return; }
                cb.accept(REFUSED + OWNER_COINS + String.join("\n", found) + "\nand that pool's owner key is not safe to sign with right now. "
                        + "Confirm the pool's signing state on ② My pools (or collect them there), then try again.\n\n" + why);
            });
        };
        if (!pandaPoolsInstalled(ctx)) judge.run();
        else readCustodyAsync(ctx, judge);   // custody read off the main thread, then judged on it
    }

    /** Hold these wallet coins in PandaPools' own CoinLock (its ~3 min selection reservation), so ② pool coin selection
     *  (keep-fresh, re-announce, create, swap) skips them: a live ③ order's backing coins (MakerService re-holds them every
     *  tick) and the inputs a Dexicon trade just selected. Dexicon's resting CoinLock (PandaDEX's copy) is separate.
     *  A coin PandaPools' own selection already reserves ({@link #poolReserved}) is NOT marked — a fresh Dexicon mark
     *  would hide that reservation — and is returned: the caller must treat it as unavailable. */
    public static Set<String> holdCoins(Collection<String> coinIds) {
        Set<String> taken = new HashSet<>();
        if (coinIds == null || coinIds.isEmpty()) return taken;
        List<Coin> coins = new ArrayList<>();
        long now = System.currentTimeMillis();
        HELD.values().removeIf(at -> now - at >= HELD_MS);
        for (String id : coinIds) {
            if (id == null || id.isEmpty()) continue;
            if (poolReserved(id)) { taken.add(id); continue; }
            Coin c = new Coin(); c.coinid = id; coins.add(c); HELD.put(low(id), now);
        }
        CoinLock.reserve(coins);
        return taken;
    }

    /** Coins Dexicon itself held in PandaPools' CoinLock (live-order backing, trade inputs) and when. */
    private static final java.util.Map<String, Long> HELD = new java.util.concurrent.ConcurrentHashMap<>();
    /** SHORTER than CoinLock's own ~3 min reservation (its private TTL_MS): once Dexicon's hold mark lapses, a reservation
     *  still standing was renewed by PandaPools itself (e.g. an owner-signed refresh), so it counts. MakerService re-holds
     *  a live order's backing every ~10 s tick, well inside this window. */
    static final long HELD_MS = 150_000;
    static int heldCount() { return HELD.size(); }
    /** For tests: make Dexicon's hold marks {@code ms} older. */
    static void ageHeld(long ms) { HELD.replaceAll((id, at) -> at - ms); }

    /** The CoinLock is one-way no more: true when PANDAPOOLS' own selection holds this coin (a pool create / refresh /
     *  swap in flight, or posted), so no Dexicon selection may take it. A reservation Dexicon made itself
     *  ({@link #holdCoins}: a live order's backing, a trade's inputs) does not count — the maker must still see its own
     *  backing coins. */
    public static boolean poolReserved(String coinId) {
        if (coinId == null || !CoinLock.isReserved(coinId)) return false;
        Long at = HELD.get(low(coinId));
        if (at == null) return true;
        if (System.currentTimeMillis() - at < HELD_MS) return false;
        HELD.remove(low(coinId), at);
        return true;
    }

    /** A {@code coins} reply minus the rows {@link #poolReserved}; anything else is returned as is. */
    public static org.json.JSONObject withoutPoolReserved(org.json.JSONObject reply) {
        Object r = reply == null ? null : reply.opt("response");
        if (!(r instanceof org.json.JSONArray)) return reply;
        try {
            org.json.JSONArray in = (org.json.JSONArray) r, out = new org.json.JSONArray();
            for (int i = 0; i < in.length(); i++) {
                org.json.JSONObject row = in.optJSONObject(i);
                if (row == null || !poolReserved(row.optString("coinid", null))) out.put(in.get(i));
            }
            return out.length() == in.length() ? reply : new org.json.JSONObject(reply.toString()).put("response", out);
        } catch (org.json.JSONException e) { return reply; }
    }

    static final String KEPT_ELSEWHERE_COLLECT = "PandaPools still keeps this pool, so Dexicon leaves its payout address alone.";

    /**
     * A collection sweep forwards owner-payout coins (owner key), PER ENTRY: the vendored CollectSweeper takes no filter
     * and works its whole queue, but every step of an entry names that entry's owner address on the node (balance /
     * checkaddress / the forward), so the connection it is given refuses any command naming the owner address of a pool
     * kept elsewhere (custody at most ~5 s old; unreadable = every pool kept elsewhere). That entry stays queued as
     * RETRYING; every other entry — a Dexicon-kept pool's pending payout — is still forwarded.
     */
    /*  The refuse decision runs OFF the main thread (the shim calls it on the custody thread, then posts the command or
     *  the refusal to the main thread), so an older read is re-read instead of failing closed. */
    static NodeApi sweepNode(Context c, com.eurobuddha.dexicon.NodeApi dex) {
        final Context ctx = c.getApplicationContext();
        return new NodeApi(dex, command -> {
            String cmd = low(command);
            for (Pool p : OwnPoolStore.all(ctx))
                if (p.oadr != null && !low(p.oadr).isEmpty() && cmd.contains(low(p.oadr)) && keptElsewhereToSign(ctx, p.address)) return KEPT_ELSEWHERE_COLLECT;
            return null;
        });
    }

    // ---- restore a PandaPools backup (the gate's "Restore your PandaPools backup here") ----

    public interface RestoreDone { void done(int restored, int total, List<String> addresses, String report); }

    /** The recipe addresses in a PandaPools backup JSON (empty if unreadable). */
    static List<String> recipeAddresses(String json) {
        List<String> out = new ArrayList<>();
        try {
            org.json.JSONArray pools = new org.json.JSONObject(json).optJSONArray("pools");
            if (pools != null) for (int i = 0; i < pools.length(); i++) {
                org.json.JSONObject p = pools.optJSONObject(i);
                String a = p == null ? "" : low(p.optString("address", ""));
                if (!a.isEmpty() && !out.contains(a)) out.add(a);
            }
        } catch (Exception unreadable) { /* empty */ }
        return out;
    }

    /**
     * Opens a PandaPools backup with PandaPools' own verify (password, authenticated bundle, valid recipes; CombinedBackupUi)
     * and restores it with PandaPools' own reserve-check restore (Recovery.restore: re-track, import proofs, record the
     * recipe; signing stays paused, nothing is signed or withdrawn). Custody is read first, so a pool PandaPools still keeps
     * is left to it from the moment its recipe lands here.
     */
    public static void restoreBackup(MainActivity act, android.net.Uri uri, java.util.function.Consumer<String> status, RestoreDone done) {
        CombinedBackupUi.open(act, uri, json -> {
            List<String> addresses = recipeAddresses(json);
            if (addresses.isEmpty()) { done.done(0, 0, addresses, "That file holds no pool recipe."); return; }
            readCustodyAsync(act, () -> restoreRead(act, json, addresses, status, done));   // custody first, read off the main thread
        }, status);
    }

    private static void restoreRead(MainActivity act, String json, List<String> addresses, java.util.function.Consumer<String> status, RestoreDone done) {
        NodeApi n = act.node();
        if (n == null) { done.done(0, addresses.size(), addresses, "Connect to your node first. Nothing restored."); return; }
        final StringBuilder log = new StringBuilder();
        status.accept("Restoring pools…");
        new Recovery(n).restore(act, json, act.chainBlock(), false, false, new Recovery.RestoreCb() {
            @Override public void onProgress(String line) { log.append(line).append('\n'); status.accept("Restoring… " + line); }
            @Override public void onDone(int restored, int total) {
                act.runOnUiThread(() -> { if (act.pools() != null) act.pools().refresh(); done.done(restored, total, addresses, log.toString().trim()); });
            }
        });
    }

    // ---- keep-alive (MakerService tick) ----

    /** One PandaPools keep-alive pass (PoolKeepAliveService.keepAlivePass), at most once per 5 min; false if not run. */
    public static boolean keepAlivePass(Context context, com.eurobuddha.dexicon.NodeApi dex) {
        final Context ctx = context.getApplicationContext();
        long now = System.currentTimeMillis();
        if (now - lastPassMs < PASS_GAP_MS) return false;                // overlap guard
        if (OwnPoolStore.all(ctx).isEmpty()) return false;              // LP nodes only
        lastPassMs = now;
        // a take-back stops Dexicon's keeper in this pass: custody is re-read OFF the main thread, then the pass runs
        readCustodyAsync(ctx, () -> runPass(ctx, dex));
        return true;
    }

    private static void runPass(Context ctx, com.eurobuddha.dexicon.NodeApi dex) {
        final NodeApi n = new NodeApi(dex);
        final Runnable stopWarm = keepCustodyWarm(ctx);
        StrandingWatch.createChannel(ctx);
        try {
            new PoolRefresher(n).refreshAgingFromScan(ctx,
                    (ownFunded, allRecipes, tip) -> StrandingWatch.evaluate(ctx, ownFunded, allRecipes, tip),
                    r -> new ReAnnouncer(n).refreshFadedFromScan(p -> {
                        new CollectSweeper(ctx, sweepNode(ctx, dex)).run((cleared, pending, stranded) -> stopWarm.run());
                    }));
        } catch (Throwable ignored) { stopWarm.run(); }
    }

    /** While a pass runs, re-read custody OFF the main thread every ~2 s, so its later owner-signing steps (refresh after
     *  serial reserve reads, re-announce after the book scan, the sweep) — which check custody on the main thread — find a
     *  read under ~5 s old instead of failing closed every pass. Stops at the pass's last step, at most after ~2 min.
     *  Returns the stop. */
    static final long WARM_EVERY_MS = 2_000, WARM_MAX_MS = 120_000;
    static Runnable keepCustodyWarm(Context ctx) {
        final java.util.concurrent.atomic.AtomicBoolean on = new java.util.concurrent.atomic.AtomicBoolean(true);
        final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
        main.postDelayed(new Runnable() {
            int left = (int) (WARM_MAX_MS / WARM_EVERY_MS);
            @Override public void run() {
                if (!on.get() || left-- <= 0) return;
                try { custodyIo.execute(() -> { try { readCustody(ctx); } catch (RuntimeException ignored) { } }); }
                catch (RuntimeException ignored) { /* rejected: steps fail closed as before */ }
                main.postDelayed(this, WARM_EVERY_MS);
            }
        }, WARM_EVERY_MS);
        return () -> on.set(false);
    }

    /** The stranding notification's Re-publish action (PoolKeepAliveService.republishOne): every signing guard still applies.
     *  Returns true once the request is finished with — it ran (its reserves were read and the refresh attempted), or
     *  there is no recipe for that pool here to ever run it — and false while a guard holds it back (kept elsewhere):
     *  the caller keeps it queued. */
    public static boolean republish(Context context, com.eurobuddha.dexicon.NodeApi dex, String address) {
        if (address == null || address.isEmpty()) return true;
        final Context ctx = context.getApplicationContext();
        Pool target = null;
        for (Pool p : OwnPoolStore.all(ctx)) if (address.equalsIgnoreCase(p.address)) { target = p; break; }
        if (target == null) return true;
        if (keptElsewhereToSign(ctx, target.address)) return false;
        final Pool pool = target;
        final NodeApi n = new NodeApi(dex);
        PoolRefresher.readLiveReserves(n, pool, found -> {
            if (!found || !ReserveRecovery.completeReserves(pool)) return;
            new PoolManager(n).refresh(pool, new PoolManager.Result() {
                @Override public void onPosted(String txpowid) {
                    NotificationManager nm = ctx.getSystemService(NotificationManager.class);
                    if (nm != null) try { nm.cancel(StrandingWatch.idFor(pool.address)); } catch (Exception ignored) { }
                }
                @Override public void onFailed(String message) { /* the next pass re-evaluates and re-warns */ }
            });
        });
        return true;
    }

    /**
     * After a hand-over: PandaPools' own "Confirm current wallet signing state" (MyLpView.confirmSigningState), for the owner
     * keys of exactly the handed-over pools. Restored pools carry signing paused; until the user attests, keep-fresh will
     * not sign for them (the stranding alarm says so). Nothing is confirmed without that explicit tap.
     */
    public static void offerSigningConfirmation(MainActivity act, List<String> handed, Runnable done) {
        Set<String> want = new HashSet<>();
        for (String a : handed) want.add(low(a));
        List<String> keys = new ArrayList<>();
        StringBuilder pools = new StringBuilder();
        for (Pool p : OwnPoolStore.all(act)) {
            if (p.address == null || !want.contains(low(p.address))) continue;
            if (!(p.signingStateUnverified || OwnPoolStore.confirmationFailed(p.opk))) continue;
            if (p.opk != null && !keys.contains(p.opk)) keys.add(p.opk);
            pools.append("\nPool: ").append(p.address);
        }
        if (keys.isEmpty() || act.node() == null || BuildConfig.VERSION_NAME.endsWith("-diagnostic")) { done.run(); return; }
        new android.app.AlertDialog.Builder(act).setTitle("Dexicon keeps your pools now")
                .setMessage("PandaPools has stopped keeping them. Restored pools start with owner signing paused, so Dexicon cannot renew "
                        + "their reserves until you confirm this wallet's signing state.\n\nOnly continue if this node holds the latest complete "
                        + "wallet signing state and every other copy of this wallet has stopped signing (PandaPools has: it handed these over). "
                        + "A seed, old backup, or counter above the recipe's recorded count cannot prove this.\n\nConfirming enables owner "
                        + "actions and automatic pool refresh for:\n" + String.join("\n", keys) + "\n" + pools)
                .setNegativeButton("Keep signing paused", (d, w) -> {
                    new android.app.AlertDialog.Builder(act).setTitle("Signing remains paused")
                            .setMessage("Confirm later on ② My pools → Back up / restore pools → Confirm wallet signing state. Until then "
                                    + "nothing renews these pools.").setPositiveButton("OK", null).show();
                    done.run();
                })
                .setPositiveButton("I have verified this", (d, w) -> confirmKeys(act, keys, 0, new StringBuilder(), done))
                .setCancelable(false).show();
    }

    private static void confirmKeys(MainActivity act, List<String> keys, int i, StringBuilder failed, Runnable done) {
        if (i >= keys.size()) {
            if (failed.length() > 0) new android.app.AlertDialog.Builder(act).setTitle("Signing remains paused")
                    .setMessage("The owner key is missing, exhausted, below a recorded count, or the confirmation could not be saved:\n" + failed)
                    .setPositiveButton("OK", null).show();
            if (act.pools() != null) act.pools().refresh();
            done.run();
            return;
        }
        final String key = keys.get(i);
        act.node().cmd("keys action:list publickey:" + key, new NodeApi.Cb() {
            public void onResult(org.json.JSONObject j) {
                Integer uses = KeyUses.extractUses(j, key);
                if (uses == null || !OwnPoolStore.acknowledgeSigningState(act, key, uses)) failed.append(key).append('\n');
                confirmKeys(act, keys, i + 1, failed, done);
            }
            public void onError(String m) { failed.append(key).append('\n'); confirmKeys(act, keys, i + 1, failed, done); }
        });
    }

    // ---- ② My pools / ASSETS helpers ----

    /** A PandaPools fund write lost its reply: pool signing is paused until the user resolves it. */
    public static boolean interruptedWrite(MainActivity act) { return act.node() != null && act.node().hasInterruptedWrite(); }

    /** PandaPools' own "Resolve interrupted write" review (WalletTools). */
    public static void resolveInterruptedWrite(MainActivity act) { new WalletTools(act).resolveInterruptedWrite(); }

    /** Pools Dexicon holds a recipe for that it must leave alone right now (full lower-case covenant addresses). */
    public static List<String> keptElsewhereNow(Context c) {
        List<String> out = new ArrayList<>();
        for (String a : heldAddresses(c)) if (keptElsewhere(c, a)) out.add(a);
        java.util.Collections.sort(out);
        return out;
    }

    /** Pools of mine (recipes) — for Dexicon's own screens, e.g. "you host N pools". */
    public static int ownPoolCount(Context c) { return OwnPoolStore.active(c).size(); }

    /** For tests: allow the next keep-alive pass at once. */
    static void resetPassClock() { lastPassMs = 0; }
    /** For tests: make the cached custody read {@code ms} older. */
    public static synchronized void ageCustody(long ms) { Custody k = custody; if (k != null) custody = new Custody(k.installed, k.keptHere, k.at - ms); }

    private static volatile long provedAt;

    /** Closed node-key pools holding the account offer back: prove them empty (PandaPools' ClosedPoolCheck: the node AND
     *  the unpruned MegaMMR), at most every 10 minutes; {@code done} runs on the main thread when a check finished. Only
     *  when every undrained recipe is a CLOSED one (a live pool must be closed first). {@code done} gets, per pool not proven
     *  empty, the reason and "Pool: <full address>". */
    public static void proveClosedPools(Context c, NodeApi pools, java.util.function.Consumer<List<String>> done) {
        long now = System.currentTimeMillis();
        if (pools == null || now - provedAt < 10 * 60_000L) return;
        List<Pool> closed = new ArrayList<>();
        for (Pool p : OwnPoolStore.undrained(OwnPoolStore.all(c))) { if (!p.retired) return; closed.add(p); }
        if (closed.isEmpty()) return;
        provedAt = now;
        android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
        List<String> failed = new ArrayList<>();
        ClosedPoolCheck.run(c, pools, closed, (l, r, d) -> failed.add(r + (d.isEmpty() ? "" : "\n" + d)), () -> main.post(() -> done.accept(failed)));
    }

    /** Pool work still tied to keys that are not yet settled, as PandaPools' hand-over judges it: a close / migrate not
     *  yet confirmed on chain, a payout still to collect, a withdrawal not settled (Dexicon's LegacyPositions). */
    public static String poolWorkOpen(android.content.Context c) {
        StringBuilder b = new StringBuilder();
        if (ActivityLog.hasPending(c, 0, ActivityLog.CLOSE) || ActivityLog.hasPending(c, 0, ActivityLog.MIGRATE)) b.append("a pool close not yet confirmed");
        // a STRANDED payout (no wallet here can sign its address) is left out: the node cannot settle it either
        boolean payouts = false;
        for (PendingCollect.Entry e : PendingCollect.all(c)) if (e.statusOf() != PendingCollect.Status.STRANDED) payouts = true;
        if (payouts) b.append(b.length() > 0 ? ", " : "").append("a pool payout to collect");
        for (ExitStore.Record r : ExitStore.all(c)) {   // terminal = DONE / SKIPPED / FAILED as stored (PandaPools Handover.exitSettled)
            boolean settled = ExitStore.State.DONE.name().equals(r.state) || ExitStore.State.SKIPPED.name().equals(r.state)
                    || ExitStore.State.FAILED.name().equals(r.state);
            if (!settled) { b.append(b.length() > 0 ? ", " : "").append("a pool withdrawal in progress"); break; }
        }
        return b.length() == 0 ? null : b.toString();
    }
}
