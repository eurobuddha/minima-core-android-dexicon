package com.eurobuddha.pandapools;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

import androidx.core.app.NotificationCompat;

/**
 * DEXICON-OWNED SHIM (not vendored). PandaPools' keep-alive work runs in Dexicon's ONE foreground service
 * ({@code com.eurobuddha.dexicon.maker.MakerService}). This class exists only because the vendored stranding notification
 * aims its "Re-publish" action at {@code PoolKeepAliveService} as a foreground-service PendingIntent: it starts, shows its
 * notice for the instant Android requires, hands the address to MakerService, and stops.
 */
public class PoolKeepAliveService extends Service {
    public static final String ACTION_REPUBLISH = "com.eurobuddha.pandapools.REPUBLISH";
    public static final String EXTRA_ADDRESS = "address";
    /** What MakerService is asked to do (same address extra). */
    public static final String ACTION_DEXICON_REPUBLISH = "com.eurobuddha.dexicon.POOL_REPUBLISH";
    private static final String CH = "pp_republish";
    private static final int FG_ID = 7303;

    @Override public IBinder onBind(Intent i) { return null; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        try {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (Build.VERSION.SDK_INT >= 26 && nm != null) nm.createNotificationChannel(new NotificationChannel(CH, "Pool re-publish", NotificationManager.IMPORTANCE_LOW));
            Notification n = new NotificationCompat.Builder(this, CH).setContentTitle("Dexicon").setContentText("Re-publishing your pool…")
                    .setSmallIcon(android.R.drawable.stat_sys_upload).build();
            if (Build.VERSION.SDK_INT >= 34) startForeground(FG_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            else if (Build.VERSION.SDK_INT >= 29) startForeground(FG_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            else startForeground(FG_ID, n);
        } catch (Exception refused) { /* still forward below */ }
        if (intent != null && ACTION_REPUBLISH.equals(intent.getAction())) {
            try {
                androidx.core.content.ContextCompat.startForegroundService(this, new Intent().setClassName(this, "com.eurobuddha.dexicon.maker.MakerService")
                        .setAction(ACTION_DEXICON_REPUBLISH).putExtra(EXTRA_ADDRESS, intent.getStringExtra(EXTRA_ADDRESS)));
            } catch (Exception ignored) { }
        }
        try { if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE); else stopForeground(true); } catch (Exception ignored) { }
        stopSelf(startId);
        return START_NOT_STICKY;
    }
}
