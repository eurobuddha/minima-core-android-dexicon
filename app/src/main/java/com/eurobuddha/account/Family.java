package com.eurobuddha.account;

import android.content.Context;
import android.content.pm.PackageManager;

/**
 * The trading family on one phone (owner, 11 Nov 2026): **AtomiX + PandaPools, or Dexicon** — never both.
 * <ul>
 *   <li>AtomiX and PandaPools run side by side and share ONE Trading Account (the same 24 words as Dexicon). They never
 *       block each other: each signs only its own share of every key's one-time leaves (AccountKeys leaf slots), so
 *       their separate counters can never land on the same leaf.</li>
 *   <li>Dexicon (which contains AtomiX and PandaPools) is the alternative to that pair.</li>
 *   <li>Two builds of the SAME app (a release and its ".stock" test build) share a leaf slot, so they are exclusive too.</li>
 * </ul>
 * An account is only CREATED or RESTORED while no conflicting app is installed ({@link AccountSetup} and
 * {@link AccountVault} refuse otherwise). Dexicon's own TradingGate also stops its trading while AtomiX or PandaPools
 * is installed.
 *
 * <p>Each app lists every package below in its manifest {@code <queries>} so it can see the others (Android 11+).
 */
public final class Family {
    private Family() {}

    /** Package -> {app kind, name shown}. Kinds: D = Dexicon, A = AtomiX, P = PandaPools. */
    static final String[][] APPS = {
            {"com.eurobuddha.dexicon", "D", "Dexicon"}, {"com.eurobuddha.dexicon.stock", "D", "Dexicon (test build)"},
            {"com.eurobuddha.atomix", "A", "AtomiX"}, {"com.eurobuddha.atomix.stock", "A", "AtomiX (test build)"},
            {"com.eurobuddha.pandapools", "P", "PandaPools"}, {"com.eurobuddha.pandapools.stock", "P", "PandaPools (test build)"},
    };

    static String kindOf(String pkg) { for (String[] a : APPS) if (a[0].equals(pkg)) return a[1]; return null; }

    /** True when {@code mine} and {@code other} may not hold the account on one phone: Dexicon against AtomiX or
     *  PandaPools, or two builds of the same app (same leaf slot). AtomiX with PandaPools is allowed. */
    static boolean conflicts(String mine, String other) {
        String a = kindOf(mine), b = kindOf(other);
        if (a == null || b == null || mine.equals(other)) return false;
        if (a.equals(b)) return true;                  // a release and its test build: same slot
        return "D".equals(a) || "D".equals(b);         // Dexicon versus the AtomiX + PandaPools pair
    }

    /** The name of an installed app this one may not share the phone with, or null. */
    public static String otherInstalled(Context c) {
        String me = c.getPackageName();
        PackageManager pm = c.getPackageManager();
        for (String[] a : APPS) {
            if (!conflicts(me, a[0])) continue;
            // MATCH_UNINSTALLED_PACKAGES: an app removed with its data KEPT (`uninstall -k`, archiving) still holds its
            // vault and would hold the account again on reinstall — it counts as installed.
            try { pm.getPackageInfo(a[0], PackageManager.MATCH_UNINSTALLED_PACKAGES); return a[2]; } catch (PackageManager.NameNotFoundException absent) { }
        }
        return null;
    }
}
