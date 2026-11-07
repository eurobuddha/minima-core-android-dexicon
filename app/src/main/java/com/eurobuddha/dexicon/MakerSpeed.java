package com.eurobuddha.dexicon;

import org.json.JSONObject;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;

/**
 * Owner 7 Nov: how fast each maker (by order signPk) has co-signed MY takes — a running average, so a sweep tries the
 * quickest maker first among orders at the same price, and the book marks makers that answer slowly. Never reorders
 * across prices: the sweep stays best-price-first. A take with no answer in the sweep's wait counts as that wait.
 */
public final class MakerSpeed {
    private MakerSpeed() {}
    static final String KEY = "maker_speed";
    /** At or above this average a maker is shown as slow. */
    public static final long SLOW_MS = 15_000;
    /** A maker never seen yet sorts as if it took this long (after known-fast, before known-slow). */
    public static final long UNKNOWN_MS = 5_000;
    static final int MAX_MAKERS = 200;

    public static void record(DexDb db, String signPk, long ms) {
        if (db == null || signPk == null || ms < 0) return;
        JSONObject m;
        try { m = new JSONObject(db.meta(KEY, "{}")); } catch (Exception e) { m = new JSONObject(); }
        db.setMeta(KEY, recordIn(m, signPk, ms).toString());
    }

    public static Map<String, Long> load(DexDb db) {
        Map<String, Long> out = new HashMap<>();
        if (db == null) return out;
        try {
            JSONObject m = new JSONObject(db.meta(KEY, "{}"));
            for (Iterator<String> it = m.keys(); it.hasNext(); ) { String k = it.next(); out.put(k, m.getJSONObject(k).optLong("avg")); }
        } catch (Exception ignored) {}
        return out;
    }

    public static long avg(Map<String, Long> speeds, String signPk) {
        Long v = signPk == null ? null : speeds.get(signPk.toLowerCase(Locale.ROOT));
        return v == null ? UNKNOWN_MS : v;
    }

    public static boolean slow(Map<String, Long> speeds, String signPk) {
        Long v = signPk == null ? null : speeds.get(signPk.toLowerCase(Locale.ROOT));
        return v != null && v >= SLOW_MS;
    }

    /** Pure: the average moves halfway to each new answer time (a maker that wakes up is forgiven within a few takes). */
    static JSONObject recordIn(JSONObject m, String signPk, long ms) {
        try {
            String k = signPk.toLowerCase(Locale.ROOT);
            JSONObject e = m.optJSONObject(k);
            long avg = e == null ? ms : (e.optLong("avg") + ms) / 2;
            m.put(k, new JSONObject().put("avg", avg).put("n", e == null ? 1 : e.optInt("n") + 1).put("t", System.currentTimeMillis()));
            while (m.length() > MAX_MAKERS) {   // drop the longest-unseen maker
                String oldest = null; long ot = Long.MAX_VALUE;
                for (Iterator<String> it = m.keys(); it.hasNext(); ) { String x = it.next(); long t = m.getJSONObject(x).optLong("t"); if (t < ot) { ot = t; oldest = x; } }
                m.remove(oldest);
            }
        } catch (Exception ignored) {}
        return m;
    }
}
