package com.eurobuddha.dexicon;

import android.content.Intent;
import android.content.res.Configuration;
import android.os.Bundle;
import android.view.ViewGroup;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;

import com.eurobuddha.dexicon.ui.Screen;

/**
 * Dexicon's launcher Activity: a thin host for the Dexicon {@link MainActivity} screen. It owns the window, puts the screen
 * in its content frame and forwards every lifecycle callback, result and Back press to it. Another app (Pandamonium) can
 * host the same screen in a tab with its own {@link Screen.Host}.
 */
public class DexiconActivity extends AppCompatActivity implements Screen.Host {
    private MainActivity screen;
    private ViewGroup slot;

    /** The screen this Activity hosts. */
    public MainActivity screen() { return screen; }

    @Override public AppCompatActivity activity() { return this; }
    /** AppCompat's content frame: the screen's setContentView lands exactly where the Activity's own would. */
    @Override public ViewGroup slot() {
        if (slot == null) slot = findViewById(android.R.id.content);
        return slot;
    }
    @Override public boolean allowWindowChanges() { return true; }
    @Override public void finishScreen() { finish(); }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        screen = new MainActivity(this);
        screen.create(b);
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() {
                if (screen.back()) return;
                setEnabled(false); getOnBackPressedDispatcher().onBackPressed(); setEnabled(true);
            }
        });
    }
    @Override protected void onStart() { super.onStart(); screen.start(); }
    @Override protected void onResume() { super.onResume(); screen.resume(); }
    @Override protected void onPause() { super.onPause(); screen.pause(); }
    @Override protected void onStop() { super.onStop(); screen.stop(); }
    @Override protected void onDestroy() { screen.destroy(); super.onDestroy(); }
    @Override protected void onSaveInstanceState(Bundle out) { super.onSaveInstanceState(out); screen.saveState(out); }
    @Override protected void onNewIntent(Intent intent) { super.onNewIntent(intent); screen.newIntent(intent); }
    @Override public void onConfigurationChanged(Configuration c) { super.onConfigurationChanged(c); screen.configurationChanged(c); }
    @SuppressWarnings("deprecation")
    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        screen.activityResult(requestCode, resultCode, data);
    }
    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        screen.permissionsResult(requestCode, permissions, results);
    }
}
