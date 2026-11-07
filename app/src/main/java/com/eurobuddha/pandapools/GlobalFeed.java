// VENDORED VERBATIM from apks/pandapools 0.9.83 app/src/main/java/com/eurobuddha/pandapools/GlobalFeed.java by scripts/vendor-pandapools.sh — do not edit here; re-vendor.
package com.eurobuddha.pandapools;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A LIVE, tracking-independent feed of the FULL public lifecycle across every discovered pool — including
 * other people's actions. It works by snapshotting each pool's reserves each scan and reading the reserve
 * movement:
 *   • one reserve up + the other down  → SWAP     (the constant-product signature)
 *   • both reserves up                 → ADD      (liquidity added)
 *   • both reserves down               → WITHDRAW (liquidity removed)
 *   • a brand-new address appears      → CREATE   (a new pool)
 *   • an address disappears            → WITHDRAW (pool closed / fully drained)
 * Lets an LP watch volume, fees and activity accrue even from actions they didn't make.
 *
 * FLAP-SAFE: discovery can transiently drop a live pool from a single scan (e.g. a 256KB-capped reply on a
 * light node). So an address that vanishes is NOT closed immediately — it's kept for a short grace ({@link
 * #MISS_CLOSE} consecutive misses) before a WITHDRAW/close is emitted, and a pool that reappears within the
 * grace is never mistaken for a fresh CREATE. The first scan of a session reseeds silently (no phantom
 * events diffed against a stale pre-restart snapshot).
 *
 * Captures from when the app first sees a pool onward (no deep historical backfill). Purely local, capped,
 * best-effort.
 */
public final class GlobalFeed {

    public static final String SWAP = "SWAP", CREATE = "CREATE", ADD = "ADD", WITHDRAW = "WITHDRAW";

    private static final String PREFS = "pandapools_global";
    private static final String KEY_SNAP = "snap";     // address -> "m|t|miss|label"
    private static final String KEY_FEED = "feed";     // recent events
    private static final int MAX = 100;
    private static final int MISS_CLOSE = 2;            // consecutive scans an address must be absent before a close
    private static final MathContext MC = new MathContext(20, RoundingMode.DOWN);
    // false until the first ingest of THIS process — the very first scan suppresses CREATE (we can't tell a
    // genuinely-new pool from a not-yet-persisted one on scan 1). Resets on restart.
    private static boolean primed = false;
    // Addresses seen at least once THIS process. A pool's FIRST sighting this session is only ever reseeded,
    // never diffed — so a pool carried over in the persisted snapshot from a previous session is never diffed
    // against stale pre-restart reserves (which would emit a phantom event). Only a later sighting, whose
    // previous snapshot is guaranteed to be from this session, produces a swap/add/withdraw. Resets on restart.
    private static final Set<String> sessionSeen = java.util.Collections.synchronizedSet(new HashSet<>());

    private GlobalFeed() {}

    public static final class Event {
        public final String kind, pool, tokenLabel;
        public final boolean minimaIn;   // SWAP only: true = someone sold MINIMA (MINIMA in, token out)
        public final BigDecimal minimaAmt, tokenAmt, price;   // price meaningful for SWAP only
        public final long ts;
        Event(String kind, String pool, String tokenLabel, boolean minimaIn, BigDecimal m, BigDecimal t, BigDecimal price, long ts) {
            this.kind=kind; this.pool=pool; this.tokenLabel=tokenLabel; this.minimaIn=minimaIn;
            this.minimaAmt=m; this.tokenAmt=t; this.price=price; this.ts=ts;
        }
    }

    /** A decoded snapshot entry for one pool address. */
    private static final class Snap {
        final BigDecimal m, t; final String label; final int miss;
        Snap(BigDecimal m, BigDecimal t, String label, int miss) { this.m=m; this.t=t; this.label=label; this.miss=miss; }
        String enc() { return m.toPlainString() + "|" + t.toPlainString() + "|" + miss + "|" + safe(label); }
        static Snap dec(String s) {
            String[] p = s.split("\\|", 4);
            BigDecimal m = bd(p[0]), t = p.length > 1 ? bd(p[1]) : BigDecimal.ZERO;
            int miss = p.length > 2 ? intOr(p[2]) : 0;
            String label = p.length > 3 ? p[3] : "";
            return new Snap(m, t, label, miss);
        }
        private static String safe(String l) { return l == null ? "" : l.replace('|', '/'); }
        private static int intOr(String s) { try { return Integer.parseInt(s); } catch (Exception e) { return 0; } }
    }

    /** Ingest a fresh scan: detect lifecycle events vs the last snapshot, append them, update the snapshot. */
    public static synchronized void ingest(Context c, List<Pool> pools) {
        if (c == null || pools == null) return;
        boolean firstScan = !primed;
        primed = true;
        Map<String, String> snap = loadSnap(c);
        List<Event> feed = loadFeed(c);
        Set<String> seen = new HashSet<>();
        long now = System.currentTimeMillis();

        for (Pool p : pools) {
            if (p == null || p.address == null || !p.funded()) continue;
            String addr = p.address.toLowerCase();
            seen.add(addr);
            String prevEnc = snap.get(addr);
            String label = p.tokenLabel();
            boolean firstSeenThisSession = sessionSeen.add(addr);
            snap.put(addr, new Snap(p.reserveM, p.reserveT, label, 0).enc());   // fresh reading, miss reset
            if (firstSeenThisSession) {
                // First sighting this process — never diff against a snapshot that may predate a restart. A
                // pool with no persisted snapshot that appears AFTER the first scan is genuinely new → CREATE;
                // everything else (the first scan, or a pool carried over from a previous session) reseeds
                // silently.
                if (!firstScan && prevEnc == null)
                    feed.add(0, new Event(CREATE, addr, label, true, p.reserveM, p.reserveT, BigDecimal.ZERO, now));
                continue;
            }
            Snap prev = Snap.dec(prevEnc);                     // prevEnc is from THIS session → safe to diff
            int cm = p.reserveM.compareTo(prev.m), ct = p.reserveT.compareTo(prev.t);
            if (cm > 0 && ct < 0) {                           // SWAP — MINIMA in, token out
                BigDecimal dm = p.reserveM.subtract(prev.m), dt = prev.t.subtract(p.reserveT);
                feed.add(0, new Event(SWAP, addr, label, true, dm, dt, price(dt, dm), now));
            } else if (cm < 0 && ct > 0) {                    // SWAP — token in, MINIMA out
                BigDecimal dm = prev.m.subtract(p.reserveM), dt = p.reserveT.subtract(prev.t);
                feed.add(0, new Event(SWAP, addr, label, false, dm, dt, price(dt, dm), now));
            } else if (cm > 0 && ct > 0) {                    // ADD — both reserves up
                feed.add(0, new Event(ADD, addr, label, true, p.reserveM.subtract(prev.m), p.reserveT.subtract(prev.t), BigDecimal.ZERO, now));
            } else if (cm < 0 && ct < 0) {                    // WITHDRAW (partial) — both reserves down
                feed.add(0, new Event(WITHDRAW, addr, label, false, prev.m.subtract(p.reserveM), prev.t.subtract(p.reserveT), BigDecimal.ZERO, now));
            }
            // both unchanged: nothing
        }

        // Addresses in the snapshot but absent from this scan → a real close (reserves fully drained). A
        // once-seen pool is track-on-discovery tracked (PoolBook.done), so it keeps being found via the
        // tracked-contract source even after its beacon prunes — a vanish is a genuine drain, not a
        // beacon-fade. Still require a short grace (a transient per-pool reserve-fetch error), and SKIP the
        // pass entirely on an all-empty scan so a systemic hiccup can never mass-emit phantom closes.
        //
        // On the FIRST scan of the process we do NOTHING to stale entries — no wipe, no close. The main loop
        // already emitted nothing (the firstScan guard above), so a persisted pool that's merely absent from
        // this first (possibly empty/partial — node not yet connected, or a 256KB-capped reply) scan is left
        // intact: it either reappears next scan (miss stays 0) or ages out via the normal grace. Wiping here
        // would make every surviving pool look brand-new next scan → a phantom CREATE storm.
        if (!firstScan && !seen.isEmpty()) {
            Iterator<Map.Entry<String, String>> it = snap.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<String, String> en = it.next();
                if (seen.contains(en.getKey())) continue;     // still live this scan
                Snap s = Snap.dec(en.getValue());
                if (s.miss + 1 >= MISS_CLOSE) {               // absent past the grace → closed / fully drained
                    feed.add(0, new Event(WITHDRAW, en.getKey(), s.label, false, s.m, s.t, BigDecimal.ZERO, now));
                    it.remove();
                } else {
                    en.setValue(new Snap(s.m, s.t, s.label, s.miss + 1).enc());   // grace: keep one more scan
                }
            }
        }

        while (feed.size() > MAX) feed.remove(feed.size() - 1);
        saveSnap(c, snap);
        saveFeed(c, feed);
    }

    private static BigDecimal price(BigDecimal tok, BigDecimal minima) {
        if (minima == null || minima.signum() == 0) return BigDecimal.ZERO;
        return tok.divide(minima, MC);
    }

    public static synchronized List<Event> list(Context c) { return loadFeed(c); }

    // ---- persistence ----

    private static Map<String, String> loadSnap(Context c) {
        Map<String, String> m = new HashMap<>();
        String s = prefs(c).getString(KEY_SNAP, null);
        if (s == null) return m;
        try {
            JSONObject o = new JSONObject(s);
            for (Iterator<String> it = o.keys(); it.hasNext(); ) { String k = it.next(); m.put(k, o.optString(k)); }
        } catch (Exception ignore) {}
        return m;
    }

    private static void saveSnap(Context c, Map<String, String> m) {
        try {
            JSONObject o = new JSONObject();
            for (Map.Entry<String, String> e : m.entrySet()) o.put(e.getKey(), e.getValue());
            prefs(c).edit().putString(KEY_SNAP, o.toString()).apply();
        } catch (Exception ignore) {}
    }

    private static List<Event> loadFeed(Context c) {
        List<Event> out = new ArrayList<>();
        String s = prefs(c).getString(KEY_FEED, null);
        if (s == null) return out;
        try {
            JSONArray arr = new JSONArray(s);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                out.add(new Event(o.optString("k", SWAP), o.optString("p"), o.optString("tl"), o.optBoolean("mi"),
                        bd(o.optString("m")), bd(o.optString("t")), bd(o.optString("pr")), o.optLong("ts")));
            }
        } catch (Exception ignore) {}
        return out;
    }

    private static void saveFeed(Context c, List<Event> feed) {
        try {
            JSONArray arr = new JSONArray();
            for (Event e : feed) {
                JSONObject o = new JSONObject();
                o.put("k", e.kind); o.put("p", e.pool); o.put("tl", e.tokenLabel); o.put("mi", e.minimaIn);
                o.put("m", e.minimaAmt.toPlainString()); o.put("t", e.tokenAmt.toPlainString());
                o.put("pr", e.price.toPlainString()); o.put("ts", e.ts);
                arr.put(o);
            }
            prefs(c).edit().putString(KEY_FEED, arr.toString()).apply();
        } catch (Exception ignore) {}
    }

    private static BigDecimal bd(String s) { try { return new BigDecimal(s); } catch (Exception e) { return BigDecimal.ZERO; } }
    private static SharedPreferences prefs(Context c) { return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE); }
}
