package com.eurobuddha.dexicon;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import androidx.core.app.NotificationCompat;

/** Fill / refusal alerts, postable from the Activity OR the service (PandaDEX Notifier). */
public final class Notifier {
    private static final String CH_ALERT = "dexicon_alert";
    private static int nextId = 3100;
    private Notifier() {}

    public static void ensureChannels(Context c) {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm != null) nm.createNotificationChannel(new NotificationChannel(CH_ALERT, "Trade alerts", NotificationManager.IMPORTANCE_DEFAULT));
    }

    public static void alert(Context c, String title, String body) {
        try {
            ensureChannels(c);
            NotificationManager nm = c.getSystemService(NotificationManager.class);
            if (nm == null) return;
            PendingIntent pi = PendingIntent.getActivity(c, 31, new Intent(c, MainActivity.class), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            Notification n = new NotificationCompat.Builder(c, CH_ALERT)
                    .setContentTitle(title).setContentText(body)
                    .setStyle(new NotificationCompat.BigTextStyle().bigText(body))
                    .setSmallIcon(android.R.drawable.stat_sys_upload_done)
                    .setContentIntent(pi).setAutoCancel(true).build();
            nm.notify(nextId++, n);
        } catch (Exception ignored) {}
    }
}
