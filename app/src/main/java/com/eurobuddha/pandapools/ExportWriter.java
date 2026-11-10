// VENDORED VERBATIM from apks/pandapools 0.9.94 app/src/main/java/com/eurobuddha/pandapools/ExportWriter.java by scripts/vendor-pandapools.sh — do not edit here; re-vendor.
package com.eurobuddha.pandapools;

import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import java.io.OutputStream;
import java.math.BigDecimal;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Runs the pool statement: reads the local history off the UI thread, builds the CSV via
 * {@link PoolStatement}, and hands it back for saving or sharing.
 *
 * The live pool reserves are passed IN by the caller rather than re-scanned here. {@link MyLpView} already
 * holds the owned pools from the shared scan, so using its list means the statement and the on-screen pool
 * card cannot disagree — they are literally the same numbers.
 *
 * A pool the caller owns but that is absent from the live scan is either closed (its last recorded event
 * spent it out, so its reserves are exactly zero — knowledge, not assumption) or simply not seen this scan,
 * in which case the statement says the reserves are unavailable instead of inventing them.
 */
public final class ExportWriter {

    public interface Cb {
        void onDone(String csv, String filename, PoolStatement.Report report);
        void onError(String message);
    }

    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "pandapools-export"); t.setDaemon(true); return t;
    });
    private static final Handler UI = new Handler(Looper.getMainLooper());

    private ExportWriter() {}

    /**
     * Build the statement. The callback fires on the UI thread.
     *
     * @param livePools the pools this node owns, with reserves from the latest scan
     */
    public static void run(final MainActivity act, final PandaAddrBook addrs,
                           final List<Pool> livePools, final Cb cb) {
        final List<Pool> live = new ArrayList<>(livePools);
        EXEC.execute(() -> {
            try {
                PoolStatement.Params p = new PoolStatement.Params();
                p.addrs = addrs;
                p.exportedAtMs = System.currentTimeMillis();
                try {
                    p.appVersion = act.getPackageManager().getPackageInfo(act.getPackageName(), 0).versionName;
                } catch (Exception ignore) {}

                HistoryDb db = act.history();
                p.rows = db.listChronological(0, Long.MAX_VALUE);
                p.backfillDone = "true".equals(db.getMeta("backfill_done", ""));

                Set<String> seen = new HashSet<>();
                for (Pool pool : live) {
                    if (pool == null || pool.address == null) continue;
                    seen.add(pool.address.toLowerCase());
                    p.pools.add(new PoolStatement.PoolInfo(pool.address, pool.tokenLabel(),
                            pool.reserveM, pool.reserveT, false, null));
                }
                // Pools we own that the scan didn't return — closed, or just not seen this time.
                for (Pool recipe : OwnPoolStore.all(act)) {
                    if (recipe.address == null || seen.contains(recipe.address.toLowerCase())) continue;
                    Closure c = closure(p.rows, addrs, recipe.address);
                    p.pools.add(new PoolStatement.PoolInfo(recipe.address, label(recipe),
                            c.closed ? BigDecimal.ZERO : null,
                            c.closed ? BigDecimal.ZERO : null,
                            c.closed, c.migratedTo));
                }

                PoolStatement.Report rep = PoolStatement.build(p);
                String name = filename(p.exportedAtMs);
                UI.post(() -> cb.onDone(rep.csv, name, rep));
            } catch (Throwable t) {
                String msg = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
                UI.post(() -> cb.onError(msg));
            }
        });
    }

    private static final class Closure { boolean closed; String migratedTo; }

    /**
     * Was this pool spent out? Only if the LAST thing our history records for it withdrew or migrated it.
     * Anything else (or no record at all) leaves the reserves unknown rather than assumed-zero.
     */
    private static Closure closure(List<HistoryEntry> rows, TxClassifier.Addrs addrs, String address) {
        Closure c = new Closure();
        for (HistoryEntry e : rows) {                       // chronological, so the last match wins
            TxClassifier.Tx t = TxClassifier.classify(e, addrs);
            if (t.poolAddress == null || !t.poolAddress.equalsIgnoreCase(address)) continue;
            if (TxClassifier.POOL_KEEPALIVE.equals(t.type)) continue;
            boolean out = TxClassifier.POOL_WITHDRAW.equals(t.type) || TxClassifier.POOL_MIGRATE.equals(t.type);
            c.closed = out;
            c.migratedTo = null;
            if (TxClassifier.POOL_MIGRATE.equals(t.type)) {
                // the migrate's other pool address is where the position went
                for (String other : TxClassifier.poolFlows(e, addrs).keySet())
                    if (!other.equalsIgnoreCase(address)) c.migratedTo = other;
            }
        }
        return c;
    }

    private static String label(Pool recipe) {
        String cached = Util.tokenNameCached(recipe.tok);
        if (cached != null && !cached.isEmpty()) return cached;
        return recipe.tok;        // an identifier, not a guessed name
    }

    static String filename(long ms) {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd", Locale.ENGLISH);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return "pandapools-statement-" + f.format(new Date(ms)) + ".csv";
    }

    /** Write the statement to a SAF-picked destination. False if the stream couldn't be opened. */
    public static boolean writeTo(MainActivity act, Uri uri, String csv) {
        try (OutputStream os = act.getContentResolver().openOutputStream(uri, "w")) {
            if (os == null) return false;
            os.write(csv.getBytes("UTF-8"));
            return true;
        } catch (Exception e) { return false; }
    }

    /** Stage the statement in the app's cache so it can be handed to another app via FileProvider. */
    public static java.io.File stageForShare(MainActivity act, String name, String csv) {
        try {
            java.io.File dir = new java.io.File(act.getCacheDir(), "export");
            if (!dir.exists() && !dir.mkdirs()) return null;
            java.io.File[] old = dir.listFiles();     // one staged export at a time
            if (old != null) for (java.io.File f : old) f.delete();
            java.io.File out = new java.io.File(dir, name);
            try (java.io.FileOutputStream fos = new java.io.FileOutputStream(out)) {
                fos.write(csv.getBytes("UTF-8"));
            }
            return out;
        } catch (Exception e) { return null; }
    }

    /** One line for the UI describing what was produced. */
    public static String describe(PoolStatement.Report r) {
        String s = r.poolCount + " pool" + (r.poolCount == 1 ? "" : "s") + " · " + r.tradeRows + " transactions";
        if (r.unreconciled > 0) s += " · " + r.unreconciled + " flagged";
        return s;
    }
}
