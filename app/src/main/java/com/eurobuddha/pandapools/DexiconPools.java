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
 * <b>Moving a live pool from standalone PandaPools</b>: its verified backup is restored here, and every pool in it is
 * first marked AWAITING HAND-OVER. While standalone PandaPools is installed, Dexicon does NOT keep an awaiting pool (two
 * keepers would rebuild the same reserves against each other). PandaPools' hand-over answers OK only after it has stopped
 * keeping them, with String[] "pools"; exactly those pools then leave AWAITING and Dexicon keeps them. Every refusal
 * carries String[] "kept_by_dexicon" — the pools PandaPools currently leaves alone; a pool Dexicon received by hand-over
 * that is ABSENT from it was taken back, and returns to AWAITING. A refusal without that extra changes nothing.
 * Once standalone PandaPools is gone (uninstalled), nothing else can keep a pool, so AWAITING no longer holds it back.
 */
public final class DexiconPools {
    private DexiconPools() {}

    public static final String EXTRA_DEXICON_POOLS = "dexicon_pools", EXTRA_POOLS = "pools", EXTRA_KEPT = "kept_by_dexicon";
    private static final String PREFS = "dexicon_pools_handover", AWAITING = "awaiting", HANDED = "handed";
    static final long PASS_GAP_MS = 5 * 60_000;
    private static long lastPassMs;

    private static SharedPreferences prefs(Context c) { return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE); }
    private static Set<String> set(Context c, String key) {
        try { Set<String> s = prefs(c).getStringSet(key, null); return s == null ? new HashSet<>() : new HashSet<>(s); }
        catch (RuntimeException unreadable) { return new HashSet<>(); }
    }
    private static String low(String a) { return a == null ? "" : a.trim().toLowerCase(Locale.ROOT); }
    private static Set<String> lows(String[] a) { Set<String> s = new HashSet<>(); if (a != null) for (String x : a) if (x != null && !low(x).isEmpty()) s.add(low(x)); return s; }

    // ---- AWAITING HAND-OVER ----

    /** Lower-cased covenant addresses restored here that standalone PandaPools has not handed over (yet, or again). */
    public static Set<String> awaiting(Context c) { return set(c, AWAITING); }

    /** True when another app still keeps this pool: AWAITING, and standalone PandaPools is still installed. */
    public static boolean keptElsewhere(Context c, String address) {
        if (c == null || address == null) return false;
        return awaiting(c).contains(low(address)) && com.eurobuddha.dexicon.TradingGate.installed(c, com.eurobuddha.dexicon.TradingGate.PANDAPOOLS);
    }

    /** Durable (commit) BEFORE a restore writes any recipe, so no keep-alive pass can treat one as Dexicon's. */
    public static boolean markAwaiting(Context c, Collection<String> addresses) {
        Set<String> s = awaiting(c);
        for (String a : addresses) if (!low(a).isEmpty()) s.add(low(a));
        return prefs(c).edit().putStringSet(AWAITING, s).commit();
    }

    /** Every covenant address Dexicon holds a recipe for — the "dexicon_pools" extra of the hand-over request. */
    public static String[] heldAddresses(Context c) {
        List<String> out = new ArrayList<>();
        for (Pool p : OwnPoolStore.active(c)) if (p.address != null && !low(p.address).isEmpty() && !out.contains(low(p.address))) out.add(low(p.address));
        return out.toArray(new String[0]);
    }

    /** PandaPools said OK and stopped keeping {@code pools}: exactly those leave AWAITING, and Dexicon keeps them. */
    public static boolean onHandoverOk(Context c, String[] pools) {
        Set<String> handed = lows(pools), awaiting = awaiting(c), mine = set(c, HANDED);
        awaiting.removeAll(handed); mine.addAll(handed);
        return prefs(c).edit().putStringSet(AWAITING, awaiting).putStringSet(HANDED, mine).commit();
    }

    /**
     * PandaPools refused. {@code keptByDexicon} null (an older PandaPools, or no answer) → nothing changes. Otherwise a pool
     * Dexicon received by hand-over and PandaPools no longer leaves alone was taken back: it returns to AWAITING.
     * Returns the pools moved back.
     */
    public static List<String> onHandoverRefused(Context c, String[] keptByDexicon) {
        List<String> back = new ArrayList<>();
        if (keptByDexicon == null) return back;
        Set<String> kept = lows(keptByDexicon), awaiting = awaiting(c), mine = set(c, HANDED);
        for (String a : new ArrayList<>(mine)) if (!kept.contains(a)) { mine.remove(a); awaiting.add(a); back.add(a); }
        if (!back.isEmpty()) prefs(c).edit().putStringSet(AWAITING, awaiting).putStringSet(HANDED, mine).commit();
        return back;
    }

    // ---- restore a PandaPools backup (the gate's "Restore your PandaPools backup here") ----

    public interface RestoreDone { void done(int restored, int total, List<String> awaiting, String report); }

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
     * recipe; signing stays paused, nothing is signed or withdrawn). Every pool in it is marked AWAITING first.
     */
    public static void restoreBackup(MainActivity act, android.net.Uri uri, java.util.function.Consumer<String> status, RestoreDone done) {
        CombinedBackupUi.open(act, uri, json -> {
            List<String> addresses = recipeAddresses(json);
            if (addresses.isEmpty()) { done.done(0, 0, addresses, "That file holds no pool recipe."); return; }
            if (!markAwaiting(act, addresses)) { done.done(0, addresses.size(), addresses, "Could not save the hand-over state. Nothing restored."); return; }
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
        final NodeApi n = new NodeApi(dex);
        StrandingWatch.createChannel(ctx);
        try {
            new PoolRefresher(n).refreshAgingFromScan(ctx,
                    (ownFunded, allRecipes, tip) -> StrandingWatch.evaluate(ctx, ownFunded, allRecipes, tip),
                    r -> new ReAnnouncer(n).refreshFadedFromScan(p ->
                            new CollectSweeper(ctx, n).run((cleared, pending, stranded) -> {})));
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

    /** Restored pools standalone PandaPools still keeps (full lower-case covenant addresses). */
    public static List<String> awaitingNow(Context c) {
        List<String> out = new ArrayList<>();
        for (String a : awaiting(c)) if (keptElsewhere(c, a)) out.add(a);
        java.util.Collections.sort(out);
        return out;
    }

    /** Pools of mine (recipes) — for Dexicon's own screens, e.g. "you host N pools". */
    public static int ownPoolCount(Context c) { return OwnPoolStore.active(c).size(); }

    /** For tests: allow the next keep-alive pass at once. */
    static void resetPassClock() { lastPassMs = 0; }
}
