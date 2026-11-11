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

    /** Manifest {@code <meta-data>} every leaf-share-aware build declares: its share (AccountKeys SLOT_*). A build
     *  without it (any AtomiX / PandaPools from before 11 Nov 2026) may sign ANY leaf, so it never runs alongside. */
    public static final String META_LEAF_SLOT = "com.eurobuddha.account.leafSlot";

    static String kindOf(String pkg) { for (String[] a : APPS) if (a[0].equals(pkg)) return a[1]; return null; }

    /** The one place a share is defined: Dexicon 0, AtomiX 1, PandaPools 2; -1 = not a family app. */
    public static int slotOf(String pkg) {
        String k = kindOf(pkg);
        return "D".equals(k) ? AccountKeys.SLOT_DEXICON : "A".equals(k) ? AccountKeys.SLOT_ATOMIX
                : "P".equals(k) ? AccountKeys.SLOT_PANDAPOOLS : -1;
    }

    /** True when {@code mine} and {@code other} may not hold the account on one phone: Dexicon against AtomiX or
     *  PandaPools; two builds of the same app (same share); or a sibling that does not declare its own correct share
     *  ({@code otherDeclaredSlot} from its manifest, -1 when absent) — an older build would sign any leaf. */
    static boolean conflicts(String mine, String other, int otherDeclaredSlot) {
        String a = kindOf(mine), b = kindOf(other);
        if (a == null || b == null || mine.equals(other)) return false;
        if (a.equals(b)) return true;                       // a release and its test build: same share
        if ("D".equals(a) || "D".equals(b)) return true;    // Dexicon versus the AtomiX + PandaPools pair
        return otherDeclaredSlot != slotOf(other);          // AtomiX with PandaPools: only when both are share-aware
    }

    /** The name of an installed app this one may not share the phone with, or null. */
    public static String otherInstalled(Context c) {
        String me = c.getPackageName();
        PackageManager pm = c.getPackageManager();
        for (String[] a : APPS) {
            if (a[0].equals(me) || kindOf(me) == null) continue;
            // MATCH_UNINSTALLED_PACKAGES: an app removed with its data KEPT (`uninstall -k`, archiving) still holds its
            // vault and would hold the account again on reinstall — it counts as installed.
            android.content.pm.ApplicationInfo ai;
            try { ai = pm.getApplicationInfo(a[0], PackageManager.GET_META_DATA | PackageManager.MATCH_UNINSTALLED_PACKAGES); }
            catch (PackageManager.NameNotFoundException absent) { continue; }
            int declared = ai.metaData == null ? -1 : ai.metaData.getInt(META_LEAF_SLOT, -1);
            if (conflicts(me, a[0], declared)) return a[2];
        }
        return null;
    }
}
