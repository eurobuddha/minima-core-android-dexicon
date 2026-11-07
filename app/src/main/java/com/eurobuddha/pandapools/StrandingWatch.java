// VENDORED VERBATIM from apks/pandapools 0.9.86 app/src/main/java/com/eurobuddha/pandapools/StrandingWatch.java by scripts/vendor-pandapools.sh — do not edit here; re-vendor.
package com.eurobuddha.pandapools;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

import androidx.core.app.NotificationCompat;

import java.util.List;
import java.util.Locale;

/**
 * Warns the owner, out loud, before a pool ages out of the chain's recent window.
 *
 * This is the root cause of every recovery problem in this app. A pool's reserve coins must be recreated roughly
 * twice a day; when they are not, they fall into the megammr-only archive and every light node — the owner's own
 * included — goes blind to them. MY LP then shows "No pools yet" and there is nothing to withdraw. Everything
 * else PandaPools does about recovery is cleanup after this has already happened.
 *
 * Until now the only warning was an amber line inside a pool's card, which the user sees only by opening the app
 * and scrolling to it — and the population that needs the warning is precisely the one whose app has not been
 * running. So it is a notification, on its own high-importance channel.
 *
 * Two audiences, two actions:
 *   a pool that simply has not been refreshed can be fixed in place, so it is offered Re-publish;
 *   a signing-quarantined pool can NEVER self-heal, because keep-fresh refuses to sign for it, so it is offered
 *   Withdraw instead. Telling that user to Re-publish would be telling them to press a button that cannot work.
 */
final class StrandingWatch {

    /** How close to stranding a pool is. */
    enum Level {
        NONE,
        /** Keep-fresh should have run by REFRESH_BLOCKS and demonstrably has not. */
        NOTICE,
        /** Past the discovery scan depth — other nodes can no longer find this pool at all. */
        WARN,
        /** Approaching the cascade edge. After this the reserves need an archive to recover. */
        URGENT,
    }

    /** The cascade length: past this, reserves live only in the megammr archive. */
    static final int CASCADE_BLOCKS = 1700;

    /** Matches the point MyLpView already calls "others may not see this pool now". */
    static final int NOTICE_AT = PoolRefresher.REFRESH_BLOCKS + 300;          // 1200
    /** Past the registry scan depth, so discovery has dropped it. */
    static final int WARN_AT = PoolCovenant.SENTINEL_SCAN_DEPTH;              // 1500
    /** Close enough to the edge that the next missed window costs an archive lookup. */
    static final int URGENT_AT = CASCADE_BLOCKS - 50;                          // 1650

    /** Don't re-notify at an unchanged level more often than this. */
    static final long QUIET_MS = 24 * 60 * 60 * 1000L;

    private static final String PREFS = "pandapools_recovery";
    private static final String CH_STRAND = "pp_stranding";
    private static final int BASE_ID = 7302;

    private StrandingWatch() {}

    /** Pure: how urgent a reserve age is. Unknown age (0) is NOT treated as urgent — it is simply unknown. */
    static Level levelFor(int reserveAge) {
        if (reserveAge <= 0) return Level.NONE;
        if (reserveAge > URGENT_AT) return Level.URGENT;
        if (reserveAge > WARN_AT) return Level.WARN;
        if (reserveAge > NOTICE_AT) return Level.NOTICE;
        return Level.NONE;
    }

    /**
     * Pure: whether to raise a notification now, given the level last notified and when.
     * Escalation is immediate; an unchanged level waits out the quiet window; a recovery clears.
     */
    static boolean shouldNotify(Level now, Level lastNotified, long lastAt, long nowMs) {
        if (now == Level.NONE) return false;
        if (lastNotified == Level.NONE) return true;
        if (now.ordinal() > lastNotified.ordinal()) return true;                 // got worse — say so at once
        if (now.ordinal() < lastNotified.ordinal()) return false;                // improving — don't nag
        return nowMs - lastAt > QUIET_MS;
    }

    /**
     * Evaluate one keep-alive pass. {@code ownFunded} carries live reserve ages; {@code allRecipes} is every
     * owned recipe, so a pool that has vanished from the scan entirely is not silently ignored.
     */
    static void evaluate(Context ctx, List<Pool> ownFunded, List<Pool> allRecipes, int tip) {
        if (ctx == null || tip <= 0) return;
        long now = System.currentTimeMillis();
        for (Pool p : ownFunded) {
            if (p == null || p.address == null) continue;
            Level level = levelFor(p.reserveAge(tip));
            if (level == Level.NONE) { clear(ctx, p.address); continue; }
            Level last = lastLevel(ctx, p.address);
            if (!shouldNotify(level, last, lastAt(ctx, p.address), now)) continue;
            boolean quarantined = p.signingStateUnverified || OwnPoolStore.confirmationFailed(p.opk);
            notify(ctx, p, level, quarantined, p.reserveAge(tip));
            record(ctx, p.address, level, now);
        }
    }

    // ---- notification ----

    static void createChannel(Context ctx) {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        if (nm == null) return;
        // A SEPARATE channel from the ongoing keep-alive notice, which is IMPORTANCE_LOW and so never sounds and
        // never appears as a heads-up. A warning posted there would be invisible to exactly the user this is for.
        // Two channels also let someone silence the permanent notification without silencing the alarm.
        NotificationChannel ch = new NotificationChannel(CH_STRAND, "Pool going dark",
                NotificationManager.IMPORTANCE_HIGH);
        ch.setDescription("Warns you before one of your pools ages out of the chain's recent window.");
        nm.createNotificationChannel(ch);
    }

    private static void notify(Context ctx, Pool p, Level level, boolean quarantined, int age) {
        createChannel(ctx);
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        if (nm == null) return;

        String title = level == Level.URGENT ? "Pool about to become unrecoverable"
                : level == Level.WARN ? "Others can no longer find your pool"
                : "Your pool needs PandaPools open";

        String body;
        if (quarantined) {
            body = "This pool's reserves are " + age + " blocks old and PandaPools cannot refresh them: its "
                    + "signing state has never been confirmed, so nothing will sign for it automatically. It "
                    + "cannot recover on its own. Withdraw it, or confirm the matching wallet's signing state.";
        } else if (level == Level.URGENT) {
            body = "Reserves are " + age + " blocks old. Past about " + CASCADE_BLOCKS + " they leave the chain's "
                    + "recent window and can only be recovered through a MegaMMR archive. Re-publish now.";
        } else if (level == Level.WARN) {
            body = "Reserves are " + age + " blocks old, so other wallets have stopped finding this pool and it "
                    + "is earning nothing. Re-publish to bring it back.";
        } else {
            body = "Reserves are " + age + " blocks old; they should be refreshed about every "
                    + PoolRefresher.REFRESH_BLOCKS + " blocks. PandaPools has not been able to run. Open it, or "
                    + "exclude it from battery optimisation.";
        }
        // No estimated value anywhere in here: a figure PandaPools cannot read from chain has no business in a
        // notification about losing access to funds.

        PendingIntent open = PendingIntent.getActivity(ctx, idFor(p.address),
                new Intent(ctx, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        NotificationCompat.Builder b = new NotificationCompat.Builder(ctx, CH_STRAND)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(body + "\n\n" + p.address))
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(open);

        if (!quarantined) {
            Intent republish = new Intent(ctx, PoolKeepAliveService.class)
                    .setAction(PoolKeepAliveService.ACTION_REPUBLISH)
                    .putExtra(PoolKeepAliveService.EXTRA_ADDRESS, p.address);
            PendingIntent pi = PendingIntent.getForegroundService(ctx, idFor(p.address) + 1, republish,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            b.addAction(0, "Re-publish", pi);
        } else {
            b.addAction(0, "Open PandaPools", open);
        }
        try { nm.notify(idFor(p.address), b.build()); } catch (Exception blocked) { /* permission refused */ }
    }

    private static void clear(Context ctx, String address) {
        if (lastLevel(ctx, address) == Level.NONE) return;
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        if (nm != null) try { nm.cancel(idFor(address)); } catch (Exception ignored) {}
        prefs(ctx).edit().remove(levelKey(address)).remove(atKey(address)).apply();
    }

    /** Stable per-address notification id, so one pool never collapses another's warning. */
    static int idFor(String address) {
        return BASE_ID + Math.abs((address == null ? "" : address.toLowerCase(Locale.ROOT)).hashCode() % 1000) * 2;
    }

    // ---- escalation state ----

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
    private static String levelKey(String a) { return "strand_level_" + (a == null ? "" : a.toLowerCase(Locale.ROOT)); }
    private static String atKey(String a) { return "strand_at_" + (a == null ? "" : a.toLowerCase(Locale.ROOT)); }

    static Level lastLevel(Context c, String address) {
        try { return Level.valueOf(prefs(c).getString(levelKey(address), Level.NONE.name())); }
        catch (Exception unknown) { return Level.NONE; }
    }
    static long lastAt(Context c, String address) { return prefs(c).getLong(atKey(address), 0); }

    static void record(Context c, String address, Level level, long at) {
        prefs(c).edit().putString(levelKey(address), level.name()).putLong(atKey(address), at).apply();
    }
}
