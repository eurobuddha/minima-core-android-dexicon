// VENDORED VERBATIM from apks/pandapools 0.9.97 app/src/main/java/com/eurobuddha/pandapools/PoolBook.java by scripts/vendor-pandapools.sh — do not edit here; re-vendor.
package com.eurobuddha.pandapools;

import org.json.JSONArray;
import org.json.JSONObject;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Discovers pools from the shared registry (trust-nothing): scan the PANDAPOOLS sentinel for announce
 * coins, re-derive each pool's covenant script + address from its announce params (never trusting the
 * announce blindly), then scan that derived address for the two reserve coins. A forged announce can at
 * worst point at a real-but-bad pool, which the reserve/price filters drop.
 *
 * Mirrors the SwapOrderBook coinnotify-add + bounded-depth scan pattern, with the Ed25519 order-verify
 * replaced by address re-derivation (the covenant address IS the proof).
 */
public class PoolBook {

    public interface Listener {
        void onPools(List<Pool> pools);
        void onError(String msg);
    }

    private final NodeApi node;

    public PoolBook(NodeApi node) { this.node = node; }

    // Literal extractors for a PandaPools covenant, so a pool can be recovered from the node's TRACKED contract
    // scripts (Source 1 = GTC). A tracked contract never prunes (unlike the sentinel beacon), so pools the node
    // already tracks stay enumerable here regardless of beacon/reserve age — which is why we still read `scripts`.
    private static final Pattern P_OPK  = Pattern.compile("SIGNEDBY\\((0x[0-9A-Fa-f]+)\\)");
    private static final Pattern P_OADR = Pattern.compile("VERIFYOUT\\(@INPUT (0x[0-9A-Fa-f]+) @AMOUNT");
    private static final Pattern P_TOK  = Pattern.compile("GETINTOK\\(s\\) EQ (0x[0-9A-Fa-f]+)");
    private static final Pattern P_KMIN = Pattern.compile("GTE MAX\\(x\\*y ([0-9.]+)\\)");

    /** Scan the registry → discover + fund every pool → callback on the UI thread. */
    public void scan(Listener cb) {
        // Straight to discovery. We deliberately do NOT `coinnotify action:add` the sentinel: coinnotify is a
        // transient NOTIFYCOIN listener (the node's own help: "without adding it to scripts... do this every
        // startup") and PandaPools polls rather than consuming NOTIFYCOIN, so it did nothing useful here — while
        // risking retention of the unspendable sentinel's ever-growing beacon pile. Discovery reads the recent
        // chain directly via the depth-bounded scan below (how the always-on nodes already find pools).
        gatherOwned(cb);
    }

    /** Source 1 (GTC): this node's tracked pool contracts (own + already-discovered). A tracked contract never
     *  prunes, so these pools stay visible + swappable regardless of beacon/reserve age. */
    private void gatherOwned(final Listener cb) {
        final Map<String, String[]> params = new LinkedHashMap<>();   // key opk|tok|kmin -> {opk,oadr,tok,kmin,script}
        node.cmd("scripts", new NodeApi.Cb() {
            @Override public void onResult(JSONObject j) { parseScripts(j, params); gatherRegistry(params, cb); }
            @Override public void onError(String m) { gatherRegistry(params, cb); }
        });
    }

    private void parseScripts(JSONObject j, Map<String, String[]> params) {
        JSONArray arr = j.optJSONArray("response");
        if (arr == null) return;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject s = arr.optJSONObject(i);
            String sc = s == null ? "" : s.optString("script", "");
            if (!sc.contains("VERIFYOUT(@INPUT @ADDRESS") || !sc.contains("GTE MAX(x*y")) continue;   // a PandaPools covenant
            String opk = group(P_OPK, sc), oadr = group(P_OADR, sc), tok = group(P_TOK, sc), kmin = group(P_KMIN, sc);
            if (!PoolCovenant.matches(sc, opk, oadr, tok, kmin)) continue;
            // carry the ACTUAL tracked covenant script (any fee) — derivePools runscripts it to confirm it
            // compiles and to get its address; lowercase dedup key so Source 1 ∪ Source 2 collapse.
            params.putIfAbsent(ReAnnouncer.key(opk, oadr, tok, kmin), new String[]{opk, oadr, tok, kmin, sc});
        }
    }

    /** Source 2: the shared registry (announce beacons) — discovers OTHER creators' fresh pools by scanning
     *  the sentinel address's recent chaintree window. HARD-BOUNDED by {@link PoolCovenant#SENTINEL_SCAN_DEPTH}:
     *  an unbounded scan of the ever-growing sentinel pile overflows the IPC broadcast and force-kills the app. */
    private void gatherRegistry(final Map<String, String[]> params, final Listener cb) {
        node.cmd("coins simplestate:true order:desc depth:" + PoolCovenant.SENTINEL_SCAN_DEPTH
                + " address:" + PoolCovenant.SENTINEL, new NodeApi.Cb() {
            @Override public void onResult(JSONObject j) {
                Object resp = j.opt("response");
                JSONArray coins = resp instanceof JSONArray ? (JSONArray) resp : new JSONArray();
                for (int i = 0; i < coins.length(); i++) {
                    JSONObject c = coins.optJSONObject(i);
                    String tok = state(c, 2), oadr = state(c, 3), opk = state(c, 4), kmin = state(c, 5);
                    if (!PoolCovenant.validParams(opk, oadr, tok, kmin)) continue;
                    params.putIfAbsent(ReAnnouncer.key(opk, oadr, tok, kmin), new String[]{opk, oadr, tok, kmin});
                }
                finishScan(params, cb);
            }
            @Override public void onError(String m) { finishScan(params, cb); }   // still show owned pools
        });
    }

    private void finishScan(Map<String, String[]> params, Listener cb) {
        if (params.isEmpty()) { cb.onPools(new ArrayList<>()); return; }
        derivePools(new ArrayList<>(params.values()), cb);
    }

    private static String group(Pattern p, String s) {
        Matcher m = p.matcher(s);
        return m.find() ? m.group(1) : null;
    }

    /** For each announce, re-derive the pool address via runscript, then scan its reserves. */
    private void derivePools(List<String[]> params, Listener cb) {
        List<Pool> pools = new ArrayList<>();
        AtomicInteger pending = new AtomicInteger(params.size());
        for (String[] p : params) {
            final String opk = p[0], oadr = p[1], tok = p[2], kmin = p[3];
            final String tracked = p.length > 4 ? p[4] : null;   // the ACTUAL tracked covenant script (any fee), if known
            String script;
            if (tracked != null && !tracked.isEmpty()) {
                script = tracked;
            } else {
                try { script = PoolCovenant.script(opk, oadr, tok, kmin); }
                catch (Exception e) { if (pending.decrementAndGet() == 0) fund(pools, cb); continue; }
            }
            final String fscript = script;
            node.cmd("runscript script:" + jsonStr(script), new NodeApi.Cb() {
                @Override public void onResult(JSONObject j) {
                    try {
                        JSONObject resp = j.getJSONObject("response");
                        // ONLY surface a covenant that actually compiles. A non-parsing script can never
                        // execute → its coins are permanently unspendable, so it must not appear as a live,
                        // closeable or routable pool (it would just make every swap through it fail).
                        if (TxPost.truthy(j, "status") && TxPost.truthy(resp, "parseok")) {
                            JSONObject sc = resp.getJSONObject("script");
                            Pool pool = new Pool();
                            pool.opk = opk; pool.oadr = oadr; pool.tok = tok; pool.kmin = kmin;
                            pool.address = sc.getString("address");
                            pool.mxaddress = sc.optString("mxaddress", "");
                            // carry the reconstructed covenant for a registry-discovered pool so MyLpView can
                            // stamp an OwnPoolStore recipe if it turns out to be ours (backfill on discovery).
                            pool.covenantScript = fscript;
                            synchronized (pools) { pools.add(pool); }
                        }
                    } catch (Exception ignore) {}
                    if (pending.decrementAndGet() == 0) fund(pools, cb);
                }
                @Override public void onError(String m) { if (pending.decrementAndGet() == 0) fund(pools, cb); }
            });
        }
    }

    /** Scan each derived pool address for its two reserve coins (largest per leg = the real reserve). */
    private void fund(List<Pool> pools, Listener cb) {
        if (pools.isEmpty()) { cb.onPools(pools); return; }
        AtomicInteger pending = new AtomicInteger(pools.size());
        for (Pool pool : pools) {
            PoolRefresher.readLiveReserves(node, pool, found -> {
                if (pending.decrementAndGet() == 0) done(pools, cb);
            });
        }
    }

    private void done(List<Pool> pools, Listener cb) {
        List<Pool> funded = new ArrayList<>();
        java.util.HashSet<String> seenAddr = new java.util.HashSet<>();
        for (Pool p : pools) {
            if (!p.funded()) continue;
            // backstop the key-level dedup: if two params ever derive the SAME covenant address, keep one —
            // a duplicate would double-count depth and (worse) let buildRouted add the same coinid twice.
            if (p.address != null && !seenAddr.add(p.address.toLowerCase())) continue;
            funded.add(p);
            // NOTE: track-on-discovery REMOVED. It used to `newscript trackall` every discovered pool, growing the
            // node's tracked set — and hence the `scripts` reply (Source 1) — WITHOUT BOUND, until that reply
            // eventually overflows the IPC broadcast and force-kills the app (see [[minima-ipc-gotchas]]). Pools
            // already tracked stay tracked (so current GTC visibility is unchanged); newly-seen pools stay
            // discoverable via their fresh Source-2 beacon, kept alive by keep-fresh + the gossip mesh. This
            // freezes `scripts` growth without dropping any currently-visible pool. covenantScript is retained on
            // the Pool so MyLpView can backfill an OwnPoolStore recovery recipe if the pool turns out to be ours.
        }
        cb.onPools(funded);
    }

    // ---- helpers ----
    /** Read a beacon coin's state port (map or array form). Package-visible so {@link ReAnnouncer} can
     *  read the sentinel beacons with the same logic. */
    static String state(JSONObject coin, int port) {
        if (coin == null) return null;
        JSONObject sm = coin.optJSONObject("state");
        if (sm != null) { String v = sm.optString(String.valueOf(port), ""); return v.isEmpty() ? null : v; }
        JSONArray sa = coin.optJSONArray("state");
        if (sa != null) for (int i = 0; i < sa.length(); i++) {
            JSONObject e = sa.optJSONObject(i);
            if (e != null && e.optInt("port", -1) == port) {
                String d = e.optString("data", ""); return d.isEmpty() ? null : d;
            }
        }
        return null;
    }

    /** JSON-quote a script for the runscript command param. */
    static String jsonStr(String s) {
        return Util.scriptArg(s);
    }
}
