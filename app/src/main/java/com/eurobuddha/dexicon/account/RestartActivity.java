package com.eurobuddha.dexicon.account;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

/**
 * Restarts Dexicon once, after the Trading Account is created, so every engine (doors, maker, pools) boots on the
 * account. Runs in its OWN process (":phoenix"): started from the foreground activity, it ends the main process, then
 * relaunches the main activity — so the relaunch is never lost with the process it replaces.
 */
public final class RestartActivity extends Activity {
    public static final String EXTRA_PID = "pid";

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        int pid = getIntent().getIntExtra(EXTRA_PID, -1);
        if (pid > 0 && pid != android.os.Process.myPid()) android.os.Process.killProcess(pid);
        startActivity(new Intent(this, com.eurobuddha.dexicon.MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        finish();
        android.os.Process.killProcess(android.os.Process.myPid());
    }

    /** Called from the foreground main activity. */
    public static void restart(Activity from) {
        from.startActivity(new Intent(from, RestartActivity.class).putExtra(EXTRA_PID, android.os.Process.myPid())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }
}
