package com.eurobuddha.dexicon;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * One trading app per phone (owner, 3 Nov 2026). Dexicon contains AtomiX and PandaPools, so it refuses to trade while
 * either is on this phone (the APK) or paired with this node (the MiniDapp in its MiniHub). Run side by side they would
 * be two makers on one wallet and two keepers rebuilding the same pool, working against each other.
 *
 * An app is removed only after it confirms, through its own "Hand over to Dexicon" step, that nothing of the user's
 * lives only inside it (no swap in flight, market withdrawn, no live pool). Nothing is stored: "Remove" asks the app
 * again and uninstalls only on that fresh answer, tied to the exact installed version (0.1.66).
 *
 * The MiniHub half FAILS CLOSED: until one `mds` read has succeeded on this install, Dexicon does not know whether the
 * MiniDapps are paired, so it is {@link #blocked} (an upgrader from 0.1.58 starts with no list at all).
 */
public final class TradingGate {
    private TradingGate() {}

    public static final String ATOMIX = "com.eurobuddha.atomix", PANDAPOOLS = "com.eurobuddha.pandapools";
    public static final String ACTION_HANDOVER = "com.eurobuddha.dexicon.action.HANDOVER";
    /** The MiniDapp names (dapp.conf "name") that count as the same apps. */
    static final String[] MINIDAPPS = {"AtomiX", "PandaPools"};
    private static final String PREFS = "dexicon_gate";

    private static SharedPreferences prefs(Context c) { return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE); }

    public static boolean installed(Context c, String pkg) { return versionOf(c, pkg) >= 0; }

    static long versionOf(Context c, String pkg) {
        try {
            android.content.pm.PackageInfo pi = c.getPackageManager().getPackageInfo(pkg, 0);
            return android.os.Build.VERSION.SDK_INT >= 28 ? pi.getLongVersionCode() : pi.versionCode;
        } catch (PackageManager.NameNotFoundException e) { return -1; }
    }

    /** "name|uid" of each AtomiX / PandaPools MiniDapp last seen in the node's MiniHub (see {@link #checkMiniHub}). */
    public static List<String> miniDapps(Context c) {
        List<String> out = new ArrayList<>();
        for (String s : prefs(c).getString("minidapps", "").split("\n")) if (!s.isEmpty()) out.add(s);
        return out;
    }

    /** Trading is off: one of the apps is here, or the MiniHub has never been read (fail closed). */
    public static boolean blocked(Context c) { return conflict(c) || !miniHubChecked(c); }

    /** One of the apps was actually FOUND (not just "not checked yet"): the maker withdraws its live orders. */
    public static boolean conflict(Context c) {
        return installed(c, ATOMIX) || installed(c, PANDAPOOLS) || !miniDapps(c).isEmpty();
    }

    /** At least one MiniHub read has succeeded on this install. */
    public static boolean miniHubChecked(Context c) { return prefs(c).getLong("minihub_ok", 0) > 0; }

    /** Re-reads the node's MiniHub (`mds`, needs the ADMIN Dexicon already requires). A failed read keeps the last answer
     *  (and, before the first success, keeps trading off). The Activity and MakerService both call it. */
    public static void checkMiniHub(Context c, NodeApi node, Runnable done) {
        if (node == null) { if (done != null) done.run(); return; }
        node.cmd("mds", new NodeApi.Cb() {
            public void onResult(JSONObject j) {
                JSONObject r = j.optJSONObject("response");
                if (j.optBoolean("status", true)) record(c, r == null ? null : r.optJSONArray("minidapps"));
                if (done != null) done.run();
            }
            public void onError(String m) { if (done != null) done.run(); }
        });
    }

    /** A successful MiniHub read: the matching rows, and the "checked" marker. A reply without a list proves nothing. */
    public static void record(Context c, JSONArray list) {
        if (list == null) return;
        prefs(c).edit().putString("minidapps", match(list)).putLong("minihub_ok", System.currentTimeMillis()).apply();
    }

    /** The matching rows of an `mds` minidapps list, one "name|uid" per line. */
    static String match(JSONArray list) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < list.length(); i++) {
            JSONObject d = list.optJSONObject(i);
            if (d == null) continue;
            JSONObject conf = d.optJSONObject("conf");
            String name = conf == null ? "" : conf.optString("name", "").trim();
            for (String want : MINIDAPPS) if (want.toLowerCase(Locale.ROOT).equals(name.toLowerCase(Locale.ROOT)))
                sb.append(want).append('|').append(d.optString("uid", "")).append('\n');
        }
        return sb.toString();
    }

    /** The app's own hand-over step said yes, just now, for exactly the installed version of exactly the app asked. */
    static boolean validHandover(boolean ok, android.content.Intent d, String pkg, long installedVersion) {
        return ok && d != null && pkg != null && installedVersion >= 0 && d.getBooleanExtra("handover", false)
                && pkg.equals(d.getStringExtra("app")) && d.getIntExtra("version", -1) == installedVersion;
    }

    /** The last answer, for the card only (never a permission): the version that last said yes. */
    static void noteAnswer(Context c, String pkg, long version) { prefs(c).edit().putLong("ho_ver_" + pkg, version).apply(); }
    static boolean lastAnswerOk(Context c, String pkg) { long v = versionOf(c, pkg); return v >= 0 && prefs(c).getLong("ho_ver_" + pkg, -2) == v; }

    /** Only the two apps Dexicon gates may be named by a hand-over answer. */
    static String gatedApp(String pkg) { return ATOMIX.equals(pkg) || PANDAPOOLS.equals(pkg) ? pkg : null; }
}
