package com.eurobuddha.account;

import android.content.Context;
import android.content.pm.PackageManager;

/**
 * The trading family that shares ONE Trading Account (the same 24 words give the same keys in each): Dexicon, AtomiX,
 * PandaPools. Owner, 10 Nov: they are mutually exclusive on a phone. Two installed apps holding the same keys would keep
 * separate one-time-signature counters and reuse leaves, so an account is only ever CREATED or RESTORED while no other
 * family app is installed ({@link AccountSetup} refuses otherwise). The app already holding the account keeps settling
 * what it has open (claims and refunds must never be stranded); new trading is stopped by each app's own hand-over gate.
 *
 * <p>Each app lists every package below in its manifest {@code <queries>} so it can see the others (Android 11+).
 */
public final class Family {
    private Family() {}

    /** Package -> name shown to the user. Test builds (".stock") count as the same app. */
    static final String[][] APPS = {
            {"com.eurobuddha.dexicon", "Dexicon"}, {"com.eurobuddha.dexicon.stock", "Dexicon (test build)"},
            {"com.eurobuddha.atomix", "AtomiX"}, {"com.eurobuddha.atomix.stock", "AtomiX (test build)"},
            {"com.eurobuddha.pandapools", "PandaPools"}, {"com.eurobuddha.pandapools.stock", "PandaPools (test build)"},
    };

    /** The name of another installed family app, or null when this app is the only one. */
    public static String otherInstalled(Context c) {
        String me = c.getPackageName();
        PackageManager pm = c.getPackageManager();
        for (String[] a : APPS) {
            if (a[0].equals(me)) continue;
            try { pm.getPackageInfo(a[0], 0); return a[1]; } catch (PackageManager.NameNotFoundException absent) { }
        }
        return null;
    }
}
