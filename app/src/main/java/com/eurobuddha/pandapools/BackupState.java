// VENDORED VERBATIM from apks/pandapools 0.9.83 app/src/main/java/com/eurobuddha/pandapools/BackupState.java by scripts/vendor-pandapools.sh — do not edit here; re-vendor.
package com.eurobuddha.pandapools;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Tracks whether the off-device pool backup still covers every pool this node owns.
 *
 * The on-device recipe store ({@link OwnPoolStore}) is written synchronously before any create or migrate is
 * posted, so a recipe is never lost to a crash. But it is app-private and deliberately excluded from cloud
 * backup — see {@code res/xml/data_extraction_rules.xml}, which allows only a direct device transfer. So it dies
 * with the phone, and the ONLY thing that survives a lost or wiped device is the file the user exported.
 *
 * That file is therefore not optional, and "I exported one once" is not the same as "it covers my pools". A
 * backup taken before a pool was created cannot recover that pool. This class reduces the question to one
 * comparison: a digest over the set of owned covenant addresses, recorded when an export is VERIFIED, against
 * the same digest computed now.
 *
 * Deliberately stored in {@code pandapools_recovery} (the recovery-settings namespace shared with
 * {@link ArchiveNode}) rather than {@code pandapools_ownpools}: {@link OwnPoolStore#all} iterates every key in
 * its file and parses each value as a recipe, so a fund-critical store should not gain non-recipe keys.
 */
final class BackupState {

    private static final String PREFS = "pandapools_recovery";
    private static final String KEY_DIGEST = "backup_exported_digest";
    private static final String KEY_AT = "backup_exported_at";
    private static final String KEY_POOLS = "backup_exported_pools";

    private BackupState() {}
    private static volatile boolean combinedPersistenceFailed;

    /**
     * A digest over the pools a backup must cover. Order-independent (addresses are sorted), case-insensitive,
     * and never truncated. The wire version is folded in, so a future backup-format change invalidates every
     * previously recorded export rather than silently accepting a file the new reader cannot use.
     */
    static String digest(List<Pool> owned) {
        List<String> addrs = new ArrayList<>();
        if (owned != null) for (Pool p : owned)
            if (p != null && p.address != null && !p.address.isEmpty())
                addrs.add(p.address.toLowerCase(Locale.ROOT));
        Collections.sort(addrs);
        StringBuilder canonical = new StringBuilder("pandapools_backup:").append(Recovery.BACKUP_VERSION);
        for (String a : addrs) canonical.append('\n').append(a);
        return sha256(canonical.toString());
    }

    static String digest(Context ctx) {
        return digest(ctx == null ? new ArrayList<>() : OwnPoolStore.all(ctx));
    }

    /** True when the last verified export covers exactly the pools owned now. */
    static boolean upToDate(Context ctx) { return combinedUpToDate(ctx); }

    /** Pool-only exports cannot satisfy the complete recovery-backup requirement. */
    static boolean combinedUpToDate(Context ctx) {
        if (ctx == null || combinedPersistenceFailed) return false;
        try {
            List<Pool> owned = OwnPoolStore.all(ctx);
            if (owned.isEmpty()) return true;
            return digest(owned).equals(ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("combined_backup_digest_v1", ""));
        }catch(RuntimeException unavailable){return false;}
    }
    static boolean recordCombinedExport(Context ctx, List<Pool> owned) {
        if (ctx == null) return false;
        combinedPersistenceFailed=true; // SharedPreferences mutates memory even when commit returns false.
        try {
            boolean saved=ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putString("combined_backup_digest_v1", digest(owned))
                    .putString(KEY_DIGEST, digest(owned)).putLong(KEY_AT,System.currentTimeMillis())
                    .putInt(KEY_POOLS,owned == null ? 0 : owned.size()).commit();
            combinedPersistenceFailed=!saved;return saved;
        }catch(RuntimeException unavailable){return false;}
    }

    /** How many owned pools exist, for the banner's wording. */
    static int ownedCount(Context ctx) {
        return ctx == null ? 0 : OwnPoolStore.all(ctx).size();
    }

    /** Whether any verified export has ever been recorded on this device. */
    static boolean everExported(Context ctx) {
        return ctx != null && !ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_DIGEST, "").isEmpty();
    }

    /**
     * Record that an export was VERIFIED — never call this merely because a write did not throw. Uses
     * {@code commit()} so the state survives process death immediately after an export.
     */
    static boolean recordVerifiedExport(Context ctx, List<Pool> owned) {
        if (ctx == null) return false;
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_DIGEST, digest(owned))
                .putLong(KEY_AT, System.currentTimeMillis())
                .putInt(KEY_POOLS, owned == null ? 0 : owned.size())
                .commit();
    }

    /**
     * Read an exported backup back and prove it can actually recover the pools it was supposed to cover.
     *
     * A write that does not throw is not evidence: a truncated or partially-written file fails only when someone
     * tries to restore it, which is the worst possible moment to find out. So every export is re-read and checked:
     * it parses, its version is one this app reads, and for every owned pool there is an entry whose address
     * matches IN FULL and whose recipe re-derives that covenant ({@link Recovery#validRecipe} re-runs
     * {@link PoolCovenant#matches}). Returns null on success, or the specific failed check.
     */
    static String verifyExport(String readBack, List<Pool> owned, int expectedBytes) {
        if (readBack == null || readBack.isEmpty()) return "the saved file could not be read back";
        if (expectedBytes > 0 && readBack.getBytes(java.nio.charset.StandardCharsets.UTF_8).length != expectedBytes)
            return "the saved file is a different size than what was written — it may be truncated";
        JSONObject root;
        try { root = new JSONObject(readBack); }
        catch (Exception bad) { return "the saved file is not readable JSON"; }
        int version = root.optInt("pandapools_backup", -1);
        if (version < 1 || version > Recovery.BACKUP_VERSION)
            return "the saved file is not a PandaPools backup this version can read";
        JSONArray pools = root.optJSONArray("pools");
        if (pools == null || pools.length() == 0) return "the saved file contains no pools";

        for (Pool p : (owned == null ? new ArrayList<Pool>() : owned)) {
            if (p == null || p.address == null || p.address.isEmpty()) continue;
            boolean found = false;
            for (int i = 0; i < pools.length() && !found; i++) {
                JSONObject e = pools.optJSONObject(i);
                if (e == null) continue;
                if (!p.address.equalsIgnoreCase(e.optString("addr", ""))) continue;
                if (!Recovery.validRecipe(e)) return "the entry for " + p.address + " is not a usable recipe";
                found = true;
            }
            if (!found) return "the saved file does not contain " + p.address;
        }
        return null;
    }

    private static String sha256(String s) {
        try {
            byte[] d = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(d.length * 2);
            for (byte b : d) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (Exception impossible) { return ""; }
    }
}
