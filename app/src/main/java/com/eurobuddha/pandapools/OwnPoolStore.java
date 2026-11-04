// VENDORED VERBATIM from apks/pandapools 0.9.82 app/src/main/java/com/eurobuddha/pandapools/OwnPoolStore.java by scripts/vendor-pandapools.sh — do not edit here; re-vendor.
package com.eurobuddha.pandapools;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The durable, node-INDEPENDENT record of the pools this install OWNS — the "recipe" recovery needs to
 * regenerate the covenant, re-track it, and reclaim funds on ANY node (a re-synced node, a factory-reset
 * phone, a brand-new device). {@link LpStore} keeps only display stats (opening reserves / fee baseline);
 * this keeps what actually matters for getting a pool back.
 *
 * Persisted per pool (keyed by covenant address): the covenant SCRIPT (authoritative — the node re-tracks
 * with {@code newscript trackall script:<this>}) plus its params (opk/oadr/tok/decimals/kmin) and the
 * derived addresses. The owner key (opk) is seed-derived and the covenant embeds {@code SIGNEDBY(opk)}, so
 * spending also requires the matching wallet's current one-time-signature state. A seed and recipe alone
 * do not provide that state. A missing recipe falls back to normal beacon/tracked-contract discovery.
 */
public final class OwnPoolStore {

    private static final java.util.concurrent.atomic.AtomicLong SIGNING_REVISION = new java.util.concurrent.atomic.AtomicLong();
    static long signingRevision() { return SIGNING_REVISION.get(); }
    static void signingStateChanged() { SIGNING_REVISION.incrementAndGet(); }

    private static final String PREFS = "pandapools_ownpools";
    private static final java.util.Set<String> FAILED_CONFIRMATIONS = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private OwnPoolStore() {}

    /** Persist (or refresh) the recipe for an owned pool. Idempotent by covenant address. */
    public static void record(Context c, Pool p) { recordDurably(c, p); }

    /** Must succeed before funding a new covenant; survives process death during posting. */
    public static synchronized boolean recordDurably(Context c, Pool p) {
        if (c == null || p == null || p.address == null || p.address.isEmpty()) return false;
        // need at least the params to be able to reconstruct/verify the covenant
        if (isEmpty(p.opk) || isEmpty(p.oadr) || isEmpty(p.tok) || isEmpty(p.kmin)) return false;
        try {
            String previous = prefs(c).getString(key(p.address), "");
            JSONObject o = mergeRecord(p, previous);
            if (signingMetadataChanged(previous, o)) signingStateChanged();
            return prefs(c).edit().putString(key(p.address), o.toString()).commit();
        } catch (Exception invalid) { return false; }
    }

    static JSONObject mergeRecord(Pool p, String previous) throws org.json.JSONException {
        JSONObject o = new JSONObject();
        o.put("addr", p.address);                 // original-case derived address (hex is case-insensitive)
        o.put("mx", nz(p.mxaddress));
        o.put("opk", p.opk);
        o.put("oadr", p.oadr);
        o.put("tok", p.tok);
        o.put("dec", p.tokDecimals);
        o.put("kmin", p.kmin);
        o.put("kidx", p.kidx);   // $OPK's derivation index (-1 unknown) — exact owner-key recovery
        int floor = p.minimumOwnerUses;
        boolean hold = p.signingStateUnverified || (previous.isEmpty() && !p.newlyCreatedWithCurrentOwnerState);
        if (!previous.isEmpty()) {
            JSONObject old = new JSONObject(previous);
            floor = Math.max(floor, old.optInt("opkuses", -1));
            hold |= old.optBoolean("signing_unverified", true);
            if (p.kidx < 0) o.put("kidx", old.optInt("kidx", -1));
        }
        if (floor >= 0) o.put("opkuses", floor);
        o.put("signing_unverified", hold);
        // Carry the retired flag across every re-record. Only setRetired changes it, so a routine refresh
        // can neither resurrect a closed pool's card nor hide a live one.
        boolean retired = p.retired;
        if (!previous.isEmpty()) retired |= new JSONObject(previous).optBoolean("retired", false);
        if (retired) o.put("retired", true);
        if (FundingCoins.hex(p.coinidM) && FundingCoins.hex(p.coinidT)) {
            o.put("last_coinid_m", p.coinidM).put("last_coinid_t", p.coinidT);
        } else if (!previous.isEmpty()) {
            JSONObject old = new JSONObject(previous);
            o.put("last_coinid_m", old.optString("last_coinid_m", ""));
            o.put("last_coinid_t", old.optString("last_coinid_t", ""));
        }
        // authoritative covenant script for re-tracking; reconstruct from params if the caller didn't
        // carry it (exact only for this app's fee — legacy-fee pools should always carry their own script)
        String script = (!isEmpty(p.covenantScript)) ? p.covenantScript : reconstruct(p);
        o.put("script", nz(script));
        return o;
    }

    static boolean signingMetadataChanged(String previous, JSONObject current) {
        try {
            if (previous.isEmpty()) return true;
            JSONObject old = new JSONObject(previous);
            return old.optBoolean("signing_unverified", true) != current.optBoolean("signing_unverified", true)
                    || old.optInt("opkuses", -1) != current.optInt("opkuses", -1)
                    || !old.optString("opk").equalsIgnoreCase(current.optString("opk"));
        } catch (Exception e) { return true; }
    }

    public static void remove(Context c, String address) {
        if (c == null || address == null) return;
        prefs(c).edit().remove(key(address)).apply();
    }

    /**
     * Hide (or un-hide) a recipe whose pool was closed or migrated away. It is NEVER deleted: a recipe is
     * the only thing that can reclaim a pool, and a close can be posted and still not land, so this must be
     * reversible. {@link #all} keeps returning retired recipes — backups, re-tracking and key classification
     * all still see them; only the pool lists filter them out via {@link #active}.
     */
    public static synchronized boolean setRetired(Context c, String address, boolean retired) {
        if (c == null || address == null) return false;
        String raw = prefs(c).getString(key(address), null);
        if (raw == null) return false;
        try {
            JSONObject o = new JSONObject(raw);
            if (o.optBoolean("retired", false) == retired) return true;
            if (retired) o.put("retired", true); else o.remove("retired");
            return prefs(c).edit().putString(key(address), o.toString()).commit();
        } catch (Exception invalid) { return false; }
    }

    /** The recipes the pool lists should show: everything except pools closed or migrated away. */
    public static List<Pool> active(Context c) {
        List<Pool> out = new ArrayList<>();
        for (Pool p : all(c)) if (!p.retired) out.add(p);
        return out;
    }

    /** Recipes hidden by {@link #setRetired} — kept so the user can always see and re-open them. */
    public static List<Pool> retired(Context c) {
        List<Pool> out = new ArrayList<>();
        for (Pool p : all(c)) if (p.retired) out.add(p);
        return out;
    }

    /** Every owned pool as a reconstructed {@link Pool} (address + params + covenantScript set; reserves
     *  null until a scan fills them). Order is not significant. */
    public static List<Pool> all(Context c) {
        List<Pool> out = new ArrayList<>();
        if (c == null) return out;
        Map<String, ?> m = prefs(c).getAll();
        for (Map.Entry<String, ?> e : m.entrySet()) {
            Object v = e.getValue();
            if (!(v instanceof String)) continue;
            try {
                JSONObject o = new JSONObject((String) v);
                Pool p = new Pool();
                p.address = o.optString("addr", e.getKey());
                p.mxaddress = o.optString("mx", "");
                p.opk = o.optString("opk", "");
                p.oadr = o.optString("oadr", "");
                p.tok = o.optString("tok", "");
                p.tokDecimals = o.optInt("dec", 8);
                p.kmin = o.optString("kmin", "");
                p.kidx = o.optInt("kidx", -1);
                p.minimumOwnerUses = o.optInt("opkuses", -1);
                p.signingStateUnverified = o.optBoolean("signing_unverified", true);
                p.retired = o.optBoolean("retired", false);
                p.coinidM = o.optString("last_coinid_m", ""); p.coinidT = o.optString("last_coinid_t", "");
                p.covenantScript = o.optString("script", "");
                if (!isEmpty(p.address) && !isEmpty(p.covenantScript)) out.add(p);
            } catch (Exception ignore) {}
        }
        return out;
    }

    /** The stored covenant script for an address (for a targeted re-track), or null. */
    public static String script(Context c, String address) {
        if (c == null || address == null) return null;
        String s = prefs(c).getString(key(address), null);
        if (s == null) return null;
        try {
            String sc = new JSONObject(s).optString("script", "");
            return sc.isEmpty() ? null : sc;
        } catch (Exception e) { return null; }
    }

    // ---- helpers ----

    /**
     * Raise the recorded use floor for every recipe sharing an owner key. RAISE ONLY, and it never touches
     * {@code signing_unverified} — this records what we have observed, it does not confer permission.
     *
     * Called before an exit signature so that even a crash between signing and posting leaves the floor at or
     * above what was spent. Erring high costs one leaf of 262,144 and blocks signing until a proper wallet
     * restore; erring low permits a reuse. The asymmetry is entirely one way, so this only ever goes up.
     */
    static synchronized boolean raiseUseFloor(Context c, String opk, Integer observed) {
        if (c == null || opk == null || observed == null || observed < 0 || observed >= KeyUses.MAX_TREE_USES) return false;
        SharedPreferences.Editor edit = prefs(c).edit();
        boolean any = false;
        try {
            for (Pool p : all(c)) {
                if (!opk.equalsIgnoreCase(p.opk)) continue;
                if (observed <= p.minimumOwnerUses) continue;               // already at or above — nothing to do
                JSONObject o = new JSONObject(prefs(c).getString(key(p.address), ""));
                o.put("opkuses", observed);
                edit.putString(key(p.address), o.toString());
                any = true;
            }
            return !any || edit.commit();
        } catch (Exception invalid) { return false; }
    }

    /** User attestation only. Diagnostic key recovery must never clear a restored-key hold. */
    public static synchronized boolean acknowledgeSigningState(Context c, String opk, int uses) {
        // Diagnostic installs must not turn a successful identity match into signing authority,
        // including through the existing manual confirmation button.
        if (BuildConfig.VERSION_NAME.endsWith("-diagnostic")) return false;
        if (c == null || opk == null || uses < 0 || uses >= KeyUses.MAX_TREE_USES) return false;
        SharedPreferences.Editor edit = prefs(c).edit();
        SharedPreferences.Editor rollback = prefs(c).edit();
        boolean found = false;
        try {
            for (Pool p : all(c)) if (opk.equalsIgnoreCase(p.opk)) {
                if (uses < p.minimumOwnerUses) return false;
                JSONObject o = new JSONObject(prefs(c).getString(key(p.address), ""));
                JSONObject held = new JSONObject(o.toString()).put("signing_unverified", true);
                rollback.putString(key(p.address), held.toString());
                o.put("signing_unverified", false).put("opkuses", uses);
                edit.putString(key(p.address), o.toString()); found = true;
            }
            return found && commitConfirmation(opk, edit::commit, rollback::commit);
        } catch (Exception invalid) { return false; }
    }

    static boolean confirmationFailed(String opk) {
        return opk != null && FAILED_CONFIRMATIONS.contains(opk.toLowerCase(java.util.Locale.ROOT));
    }

    /** commit(false) has already changed SharedPreferences memory. Never interpret it as a rollback. */
    static boolean commitConfirmation(String opk, java.util.function.BooleanSupplier save, Runnable rollback) {
        String key = opk.toLowerCase(java.util.Locale.ROOT);
        signingStateChanged();
        FAILED_CONFIRMATIONS.add(key);
        try {
            if (save.getAsBoolean()) { FAILED_CONFIRMATIONS.remove(key); return true; }
        } catch (Exception failed) { /* restore a hold below */ }
        try { rollback.run(); } catch (Exception failed) { /* process latch remains closed */ }
        return false;
    }

    static java.util.Set<String> ownerAddresses(Context c) {
        java.util.Set<String> out = new java.util.HashSet<>();
        for (Pool p : all(c)) if (!isEmpty(p.oadr)) out.add(p.oadr.toLowerCase(java.util.Locale.ROOT));
        return out;
    }

    /** Refresh only coin-ID hints: preserve the exact recipe, derivation index and signing hold. */
    static synchronized boolean rememberReserves(Context c, Pool p) {
        if (c == null || !ReserveRecovery.completeReserves(p)) return false;
        String previous = prefs(c).getString(key(p.address), "");
        if (previous.isEmpty()) return true; // third-party pool: no owned recipe to update
        try {
            JSONObject o = new JSONObject(previous);
            if (p.coinidM.equalsIgnoreCase(o.optString("last_coinid_m")) && p.coinidT.equalsIgnoreCase(o.optString("last_coinid_t"))) return true;
            o.put("last_coinid_m", p.coinidM).put("last_coinid_t", p.coinidT);
            return prefs(c).edit().putString(key(p.address), o.toString()).commit();
        } catch (Exception invalid) { return false; }
    }

    private static String reconstruct(Pool p) {
        try { return PoolCovenant.script(p.opk, p.oadr, p.tok, p.kmin); } catch (Exception e) { return ""; }
    }
    private static boolean isEmpty(String s) { return s == null || s.isEmpty(); }
    private static String nz(String s) { return s == null ? "" : s; }
    private static SharedPreferences prefs(Context c) { return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE); }
    private static String key(String address) { return address.toLowerCase(); }
}
