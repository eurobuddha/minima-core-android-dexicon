package com.eurobuddha.dexicon.relay;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** The persisted relay list (shared by the Activity and the MakerService) and which entries were discovered. */
public final class RelayStore {
    private RelayStore() {}
    private static final String PREFS = "dexicon";
    private static SharedPreferences p(Context c) { return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE); }

    public static List<String> load(Context c) { return list(p(c).getString("relays", "[]")); }
    public static Set<String> discovered(Context c) { return new LinkedHashSet<>(list(p(c).getString("relays_discovered", "[]"))); }

    public static void save(Context c, List<String> relays, Set<String> discovered) {
        p(c).edit().putString("relays", new JSONArray(relays).toString())
                .putString("relays_discovered", new JSONArray(new ArrayList<>(discovered)).toString()).apply();
    }

    public static void add(Context c, String url, boolean discoveredOne) {
        String u = RelayClient.clean(url);
        if (u == null) return;
        List<String> l = load(c); Set<String> d = discovered(c);
        if (!l.contains(u)) l.add(u);
        if (discoveredOne) d.add(u);
        save(c, l, d);
    }

    public static void remove(Context c, String url) {
        List<String> l = load(c); Set<String> d = discovered(c);
        l.remove(url); d.remove(url);
        save(c, l, d);
    }

    /** DEV override of the dollar token (empty = mainnet MxUSD). */
    public static String mxusdOverride(Context c) { return p(c).getString("mxusd_tokenid", ""); }
    public static void setMxusdOverride(Context c, String tok) { p(c).edit().putString("mxusd_tokenid", tok == null ? "" : tok.trim()).apply(); }
    /** Backing coins older than this many blocks are re-locked (maker.js COIN_MAX_AGE); 0 = default 500. */
    public static int coinMaxAge(Context c) { int v = p(c).getInt("coin_max_age", 0); return v > 0 ? v : 500; }
    public static void setCoinMaxAge(Context c, int v) { p(c).edit().putInt("coin_max_age", v).apply(); }

    private static List<String> list(String json) {
        List<String> out = new ArrayList<>();
        try { JSONArray a = new JSONArray(json); for (int i = 0; i < a.length(); i++) { String s = a.optString(i, ""); if (!s.isEmpty()) out.add(s); } } catch (Exception ignored) {}
        return out;
    }
}
