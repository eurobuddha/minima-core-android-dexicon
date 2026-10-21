package com.eurobuddha.dexicon.maker;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import androidx.core.content.ContextCompat;

/** Re-arm the maker engine after a reboot or an APK update, so resting orders keep being co-signed. */
public class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context ctx, Intent intent) {
        HeartbeatReceiver.schedule(ctx);
        try { ContextCompat.startForegroundService(ctx, new Intent(ctx, MakerService.class)); } catch (Exception ignored) { }
    }
}
