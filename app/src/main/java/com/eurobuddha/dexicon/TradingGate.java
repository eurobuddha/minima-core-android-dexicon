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
 * lives only inside it (no swap in flight, market withdrawn, no live pool). That confirmation is short-lived and tied
 * to the exact installed version, so it cannot be replayed after the app was used again or updated.
 */
public final class TradingGate {
    private TradingGate() {}

    public static final String ATOMIX = "com.eurobuddha.atomix", PANDAPOOLS = "com.eurobuddha.pandapools";
    public static final String ACTION_HANDOVER = "com.eurobuddha.dexicon.action.HANDOVER";
    /** The MiniDapp names (dapp.conf "name") that count as the same apps. */
    static final String[] MINIDAPPS = {"AtomiX", "PandaPools"};
    static final long HANDOVER_VALID_MS = 15 * 60_000L;
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

    public static boolean blocked(Context c) {
        return installed(c, ATOMIX) || installed(c, PANDAPOOLS) || !miniDapps(c).isEmpty();
    }

    /** Re-reads the node's MiniHub (`mds`, needs the ADMIN Dexicon already requires). A failed read keeps the last answer. */
    public static void checkMiniHub(Context c, NodeApi node, Runnable done) {
        if (node == null) { if (done != null) done.run(); return; }
        node.cmd("mds", new NodeApi.Cb() {
            public void onResult(JSONObject j) {
                JSONObject r = j.optJSONObject("response");
                JSONArray list = r == null ? null : r.optJSONArray("minidapps");
                if (list != null) prefs(c).edit().putString("minidapps", match(list)).apply();
                if (done != null) done.run();
            }
            public void onError(String m) { if (done != null) done.run(); }
        });
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

    /** The app's own hand-over step said yes, for this installed version. */
    static void noteHandover(Context c, String pkg, long version) {
        prefs(c).edit().putLong("ho_ts_" + pkg, System.currentTimeMillis()).putLong("ho_ver_" + pkg, version).apply();
    }

    static boolean handedOver(Context c, String pkg) {
        long ts = prefs(c).getLong("ho_ts_" + pkg, 0), ver = prefs(c).getLong("ho_ver_" + pkg, -2);
        return fresh(ts, System.currentTimeMillis()) && ver == versionOf(c, pkg);
    }

    static boolean fresh(long ts, long now) { return ts > 0 && now - ts >= 0 && now - ts <= HANDOVER_VALID_MS; }
}
