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
 * same key): one row per ACTIVE recipe PandaPools holds, {@code kept_here} or {@code handed_over}. A pool PandaPools lists
 * as kept_here is kept elsewhere; any other pool (handed over, absent, PandaPools reinstalled with nothing) is Dexicon's.
 * Unreadable (an older PandaPools, null, an error, a provider that is not PandaPools' own) → EVERY pool counts as kept
 * elsewhere: nobody signs from Dexicon, and the gate card says to update PandaPools. PandaPools not installed → Dexicon
 * keeps every recipe it holds. The read is cached with its time and refreshed on every keep-alive pass, onResume and
 * gate render (and whenever the cache is over a minute old). The hand-over intent (dexicon_pools / pools /
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

    /** Re-read PandaPools' custody signal now and cache it (keep-alive pass, onResume, gate render). */
    public static Custody readCustody(Context c) {
        Context ctx = c.getApplicationContext();
        boolean installed = pandaPoolsInstalled(ctx);
        Custody k = new Custody(installed, installed ? queryKeptHere(ctx) : null, System.currentTimeMillis());
        custody = k;
        try { if (prefs(ctx).contains(AWAITING) || prefs(ctx).contains(HANDED)) prefs(ctx).edit().remove(AWAITING).remove(HANDED).apply(); }
        catch (RuntimeException ignored) { /* the retired sets are never read anyway */ }
        return k;
    }

    /** The cached read, re-read when missing, over a minute old, or taken before PandaPools was (un)installed. */
    public static Custody custody(Context c) {
        Custody k = custody;
        if (k == null || k.installed != pandaPoolsInstalled(c) || System.currentTimeMillis() - k.at > CUSTODY_MAX_AGE_MS) k = readCustody(c);
        return k;
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
     *  key, so no Dexicon trade, order or self-send may select it. */
    public static Set<String> ownerAddresses(Context c) {
        if (c == null) return new HashSet<>();
        try { return OwnPoolStore.ownerAddresses(c.getApplicationContext()); } catch (RuntimeException unreadable) { return new HashSet<>(); }
    }

    /** Hold these wallet coins in PandaPools' own CoinLock (its ~3 min selection reservation), so ② pool coin selection
     *  (keep-fresh, re-announce, create, swap) skips them: a live ③ order's backing coins (MakerService re-holds them every
     *  tick) and the inputs a Dexicon trade just selected. Dexicon's resting CoinLock (PandaDEX's copy) is separate. */
    public static void holdCoins(Collection<String> coinIds) {
        if (coinIds == null || coinIds.isEmpty()) return;
        List<Coin> coins = new ArrayList<>();
        for (String id : coinIds) if (id != null && !id.isEmpty()) { Coin c = new Coin(); c.coinid = id; coins.add(c); }
        CoinLock.reserve(coins);
    }

    /** A collection sweep forwards owner-payout coins (owner key). Not while custody is unreadable, nor while any pending
     *  collection belongs to a pool kept elsewhere (CollectSweeper is vendored and sweeps its whole queue). */
    static boolean sweepAllowed(Context c) {
        if (custodyUnreadable(c)) return false;
        for (PendingCollect.Entry e : PendingCollect.all(c))
            for (Pool p : OwnPoolStore.all(c))
                if (p.oadr != null && p.oadr.equalsIgnoreCase(e.oadr) && keptElsewhere(c, p.address)) return false;
        return true;
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
            readCustody(act);
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
        }, status);
    }

    // ---- keep-alive (MakerService tick) ----

    /** One PandaPools keep-alive pass (PoolKeepAliveService.keepAlivePass), at most once per 5 min; false if not run. */
    public static boolean keepAlivePass(Context context, com.eurobuddha.dexicon.NodeApi dex) {
        final Context ctx = context.getApplicationContext();
        long now = System.currentTimeMillis();
        if (now - lastPassMs < PASS_GAP_MS) return false;                // overlap guard
        if (OwnPoolStore.all(ctx).isEmpty()) return false;              // LP nodes only
        lastPassMs = now;
        readCustody(ctx);                                               // a take-back stops Dexicon's keeper in this pass
        final NodeApi n = new NodeApi(dex);
        StrandingWatch.createChannel(ctx);
        try {
            new PoolRefresher(n).refreshAgingFromScan(ctx,
                    (ownFunded, allRecipes, tip) -> StrandingWatch.evaluate(ctx, ownFunded, allRecipes, tip),
                    r -> new ReAnnouncer(n).refreshFadedFromScan(p -> {
                        if (sweepAllowed(ctx)) new CollectSweeper(ctx, n).run((cleared, pending, stranded) -> {});
                    }));
        } catch (Throwable ignored) { }
        return true;
    }

    /** The stranding notification's Re-publish action (PoolKeepAliveService.republishOne): every signing guard still applies. */
    public static void republish(Context context, com.eurobuddha.dexicon.NodeApi dex, String address) {
        if (address == null || address.isEmpty()) return;
        final Context ctx = context.getApplicationContext();
        Pool target = null;
        for (Pool p : OwnPoolStore.all(ctx)) if (address.equalsIgnoreCase(p.address)) { target = p; break; }
        if (target == null || keptElsewhere(ctx, target.address)) return;
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

    /** PandaPools' consolidation guard: the node's consolidate picks its own coins, so it must not run while the wallet
     *  holds a pool owner key that cannot sign. {@code cb} gets null (go ahead) or the reason nothing may be consolidated. */
    public static void checkConsolidation(MainActivity act, java.util.function.Consumer<String> cb) {
        if (act.node() == null) { cb.accept(null); return; }
        OwnerKeyRecovery.checkConsolidation(act.node()::cmd, () -> OwnPoolStore.all(act), cb);
    }

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
}
