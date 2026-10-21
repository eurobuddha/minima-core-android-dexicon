package com.eurobuddha.dexicon.maker;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import androidx.core.content.ContextCompat;

/**
 * Doze-proof keep-alive heartbeat (PandaDEX / minimaSwap stack). A foreground service keeps the process resident
 * but does NOT exempt a Handler loop from Doze; an exact allow-while-idle alarm is the only wake that fires reliably.
 * Each firing chains the next one and drives one {@link MakerService} pass.
 */
public class HeartbeatReceiver extends BroadcastReceiver {

    private static final long INTERVAL_MS = 15 * 60_000;
    private static final int REQUEST_CODE = 71;

    @Override public void onReceive(Context ctx, Intent intent) {
        schedule(ctx);   // chain the next one FIRST — a crash below must not end the heartbeat
        try { ContextCompat.startForegroundService(ctx, new Intent(ctx, MakerService.class).setAction(MakerService.ACTION_HEARTBEAT)); }
        catch (Exception ignored) { }
    }

    public static void schedule(Context ctx) {
        try {
            AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
            if (am == null) return;
            PendingIntent pi = PendingIntent.getBroadcast(ctx, REQUEST_CODE, new Intent(ctx, HeartbeatReceiver.class),
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            long at = android.os.SystemClock.elapsedRealtime() + INTERVAL_MS;
            if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi);
            else am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi);
        } catch (Exception ignored) {}
    }
}
