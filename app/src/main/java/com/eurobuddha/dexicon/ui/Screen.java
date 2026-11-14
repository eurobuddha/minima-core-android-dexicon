package com.eurobuddha.dexicon.ui;

import android.app.Activity;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.res.Configuration;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;

import androidx.activity.result.ActivityResultCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContract;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.lifecycle.Lifecycle;

import java.util.ArrayList;
import java.util.List;

/**
 * A whole-app screen that runs inside a HOST Activity instead of being one: Dexicon's own launcher
 * ({@link com.eurobuddha.dexicon.DexiconActivity}) or a tab of another app (Pandamonium). It is the host Activity as a
 * Context (themes, dialogs, services, preferences all resolve through it) plus the few Activity calls the screen code
 * makes, each routed to the host. The host drives the lifecycle methods below from its own callbacks.
 */
public abstract class Screen extends ContextWrapper {
    public static final int RESULT_OK = Activity.RESULT_OK, RESULT_CANCELED = Activity.RESULT_CANCELED;

    /** What a screen needs from whoever shows it. */
    public interface Host {
        /** The Activity the screen lives in (Context, dialogs, result registry, permissions). */
        AppCompatActivity activity();
        /** Where {@link #setContentView} puts the screen's root view. */
        ViewGroup slot();
        /** Window-level changes (edge-to-edge, day/night of the Activity): false = a tab host keeps its own window as it is. */
        boolean allowWindowChanges();
        /** The screen asked to close: a standalone host finishes its Activity; a tab host may ignore it. */
        void finishScreen();
    }

    private final Host host;
    private boolean finishing, destroyed;
    private final List<ActivityResultLauncher<?>> launchers = new ArrayList<>();
    private int nextKey;

    protected Screen(Host host) {
        super(host.activity());
        this.host = host;
    }

    public final Host host() { return host; }
    public final AppCompatActivity activity() { return host.activity(); }
    /** False when a tab host refuses changes to its own window. */
    protected final boolean windowChangesAllowed() { return host.allowWindowChanges(); }

    // ---- the Activity calls screen code makes, routed to the host ----

    public void setContentView(View v) {
        ViewGroup slot = host.slot();
        slot.removeAllViews();
        slot.addView(v);   // as AppCompat's own setContentView(View): default params = match_parent in a FrameLayout
    }
    public <T extends View> T findViewById(int id) { return host.slot().findViewById(id); }
    public Window getWindow() { return activity().getWindow(); }
    public AppCompatDelegate getDelegate() { return activity().getDelegate(); }
    public Intent getIntent() { return activity().getIntent(); }
    public void runOnUiThread(Runnable r) { activity().runOnUiThread(r); }
    public boolean isFinishing() { return finishing || activity().isFinishing(); }
    public boolean isDestroyed() { return destroyed || activity().isDestroyed(); }
    public void finish() { finishing = true; host.finishScreen(); }
    public void requestPermissions(String[] permissions, int requestCode) { activity().requestPermissions(permissions, requestCode); }
    /** Results come back through the host to {@link #activityResult}. */
    public void startActivityForResult(Intent intent, int requestCode) { activity().startActivityForResult(intent, requestCode); }

    /** The host's result registry. Built in the host's onCreate (standalone): exactly the Activity's own registration.
     *  Built later (a tab opened after the host started): a keyed registration, released when this screen is destroyed. */
    public <I, O> ActivityResultLauncher<I> registerForActivityResult(ActivityResultContract<I, O> contract, ActivityResultCallback<O> callback) {
        AppCompatActivity a = activity();
        ActivityResultLauncher<I> l = a.getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.STARTED)
                ? a.getActivityResultRegistry().register("dexicon_screen_rq#" + nextKey++, contract, callback)
                : a.registerForActivityResult(contract, callback);
        launchers.add(l);
        return l;
    }

    // ---- lifecycle, driven by the host ----

    public final void create(Bundle saved) { onCreate(saved); }
    public final void start() { onStart(); }
    public final void resume() { onResume(); }
    public final void pause() { onPause(); }
    public final void stop() { onStop(); }
    public final void destroy() {
        onDestroy();
        destroyed = true;
        for (ActivityResultLauncher<?> l : launchers) l.unregister();
        launchers.clear();
    }
    public final void saveState(Bundle out) { onSaveInstanceState(out); }
    public final void activityResult(int requestCode, int resultCode, Intent data) { onActivityResult(requestCode, resultCode, data); }
    public final void permissionsResult(int requestCode, String[] permissions, int[] results) { onRequestPermissionsResult(requestCode, permissions, results); }
    public final void newIntent(Intent intent) { onNewIntent(intent); }
    public final void configurationChanged(Configuration c) { onConfigurationChanged(c); }
    /** Back pressed: true = the screen used it; false = the host does its default (leave, or its own navigation). */
    public boolean back() { return false; }

    protected void onCreate(Bundle saved) {}
    protected void onStart() {}
    protected void onResume() {}
    protected void onPause() {}
    protected void onStop() {}
    protected void onDestroy() {}
    protected void onSaveInstanceState(Bundle out) {}
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {}
    protected void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {}
    protected void onNewIntent(Intent intent) {}
    public void onConfigurationChanged(Configuration c) {}
}
