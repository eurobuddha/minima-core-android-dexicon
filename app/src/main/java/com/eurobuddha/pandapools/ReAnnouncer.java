// VENDORED VERBATIM from apks/pandapools 0.10.9 app/src/main/java/com/eurobuddha/pandapools/ReAnnouncer.java by scripts/vendor-pandapools.sh — do not edit here; re-vendor.
package com.eurobuddha.pandapools;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Layer 5 (network discoverability): keep a live announce beacon in the recent sentinel window for pools,
 * so STRANGERS' fresh nodes keep rediscovering them after the original beacon pruned.
 *
 * GOSSIP: a pool stays discoverable only while a live beacon sits in the recent window, and the beacon is
 * unspendable dust that prunes (~a day). If re-announcing were owner-only, a pool created on a now-offline
 * phone would go dark for everyone. So this re-announces EVERY funded pool the node knows (its own +
 * any it has discovered/tracked), turning discovery into a self-healing mesh: as long as one active node
 * knows a funded pool, its beacon stays alive for new users. Always-on nodes (desktop/MDS) are the anchors.
 * Re-announcing another's pool is safe — {@link PoolManager#reannounce} spends no covenant coin and needs
 * no owner signature; it just posts a dust beacon carrying the pool's public params (which the node holds).
 *
 * It re-announces ONLY a pool whose beacon has actually FADED from the recent window — so beacons never
 * pile up — and shuffles + caps each run so many nodes don't all re-beacon the same pools at once.
 *
 * HARD GUARD: the sentinel scan here NEVER uses {@code megammr:true} — that query (pulling the whole
 * accumulated beacon pile) was the 0.8.10 crash, not the re-announce itself. Recent unpruned window only.
 */
public class ReAnnouncer {

    public interface Listener { void onAnnounced(int posted); }

    /** Per-run cap on how many faded beacons ONE node re-posts. Gossip is collective — shuffle + cap so we
     *  don't all beacon the same pools at once; other nodes and later runs cover the rest. */
    private static final int MAX_PER_RUN = 8;

    // Keys re-announced during THIS process — so a config-change recreate (e.g. theme toggle) can't
    // double-post a beacon before the first one confirms into the sentinel window. RE-ARMED (key removed) in
    // announce() the moment that beacon shows up live in the recent window, so a later re-fade re-announces
    // again (an offline owner's pool survives multiple fade cycles). Cleared entirely on process death.
    private static final Set<String> ANNOUNCED = java.util.Collections.synchronizedSet(new HashSet<>());

    private final NodeApi node;
    private final PoolManager mgr;

    public ReAnnouncer(NodeApi node) {
        this.node = node;
        this.mgr = new PoolManager(node);
    }

    /**
     * For each currently-funded owned pool whose beacon is absent from the recent sentinel window, post one
     * fresh beacon. {@code fundedOwn} are pools with live reserves + full params (from discovery).
     */
    public void refreshFaded(final List<Pool> fundedOwn, final Listener cb) {
        if (fundedOwn == null || fundedOwn.isEmpty()) { cb.onAnnounced(0); return; }
        // PROACTIVE present-check: scan the TIGHTER REANNOUNCE_DEPTH window (not the wider discovery depth), so a
        // beacon counts as "faded" — and gets re-announced — once it is older than REANNOUNCE_DEPTH, i.e. BEFORE
        // it would leave the SENTINEL_SCAN_DEPTH discovery window. That's what keeps a live pool's beacon from ever
        // lapsing on a non-owner node (the cross-device flicker fix). NEVER megammr:true (see class note); still
        // depth-bounded so the reply stays IPC-safe.
        node.cmd("coins simplestate:true order:desc depth:" + PoolCovenant.REANNOUNCE_DEPTH
                + " address:" + PoolCovenant.SENTINEL, new NodeApi.Cb() {
            @Override public void onResult(JSONObject j) { announce(present(j), fundedOwn, cb); }
            @Override public void onError(String m) { cb.onAnnounced(0); }   // can't tell what's live → do nothing
        });
    }

    /**
     * Background entry (no UI state): build the funded owned-pool list straight from {@link OwnPoolStore}
     * — a reserve scan per recipe, no ownership/keys lookup needed (a stored recipe IS ours) — then
     * re-announce the faded ones. Skips recipes with no live reserves (a closed pool must not be announced).
     */
    public void refreshFadedFromStore(final Context ctx, final Listener cb) {
        final List<Pool> recipes = OwnPoolStore.all(ctx);
        if (recipes.isEmpty()) { cb.onAnnounced(0); return; }
        final List<Pool> funded = Collections.synchronizedList(new ArrayList<>());
        final AtomicInteger pending = new AtomicInteger(recipes.size());
        for (final Pool r : recipes) {
            node.cmd("coins address:" + r.address, new NodeApi.Cb() {
                @Override public void onResult(JSONObject j) { fillReserves(r, j); if (r.funded()) funded.add(r); tick(); }
                @Override public void onError(String m) { tick(); }
                private void tick() { if (pending.decrementAndGet() == 0) refreshFaded(new ArrayList<>(funded), cb); }
            });
        }
    }

    /**
     * GOSSIP entry (background + foreground): run a full registry scan (Source-1 OwnPoolStore ∪ Source-2 live
     * beacons), then re-announce EVERY funded pool whose beacon has faded — not just this node's own. This
     * is the self-healing mesh: any node that has discovered a pool helps keep it alive, so a pool created
     * on a now-offline phone stays findable while it holds reserves and at least one active node knows it.
     * {@link PoolBook#scan} returns the funded, deduped pool set; {@link #refreshFaded} filters to faded.
     */
    public void refreshFadedFromScan(final Listener cb) {
        new PoolBook(node).scan(new PoolBook.Listener() {
            @Override public void onPools(List<Pool> funded) { refreshFaded(funded, cb); }
            @Override public void onError(String msg) { cb.onAnnounced(0); }
        });
    }

    /** Largest coin per leg at the covenant address = the true reserve (dust can't masquerade). Mirrors
     *  PoolBook.fund so a store-built pool matches a discovery-built one. */
    private static void fillReserves(Pool p, JSONObject j) {
        Object resp = j.opt("response");
        JSONArray cs = resp instanceof JSONArray ? (JSONArray) resp : new JSONArray();
        for (int i = 0; i < cs.length(); i++) {
            JSONObject c = cs.optJSONObject(i);
            if (c == null || c.optBoolean("spent", false)) continue;
            String tid = c.optString("tokenid", "");
            if ("0x00".equals(tid)) {
                BigDecimal amt = new BigDecimal(c.optString("amount", "0"));
                if (p.reserveM == null || amt.compareTo(p.reserveM) > 0) { p.reserveM = amt; p.coinidM = c.optString("coinid", ""); }
            } else if (p.tok != null && p.tok.equalsIgnoreCase(tid)) {
                BigDecimal amt = new BigDecimal(c.optString("tokenamount", c.optString("amount", "0")));
                if (p.reserveT == null || amt.compareTo(p.reserveT) > 0) { p.reserveT = amt; p.coinidT = c.optString("coinid", ""); }
            }
        }
    }

    /** The set of pool keys (opk|tok|kmin) that currently have a live beacon in the recent window. */
    private static Set<String> present(JSONObject j) {
        Set<String> keys = new HashSet<>();
        Object resp = j.opt("response");
        JSONArray coins = resp instanceof JSONArray ? (JSONArray) resp : new JSONArray();
        for (int i = 0; i < coins.length(); i++) {
            JSONObject c = coins.optJSONObject(i);
            if (c == null) continue;
            // full beacon identity incl. oadr (port 3) — a forged beacon with your public opk/tok/kmin but a
            // bogus oadr must NOT be mistaken for your live beacon (it would suppress your re-announce and
            // point fresh nodes at a wrong-address covenant). Strict parity with PoolBook.gatherRegistry.
            String tok = PoolBook.state(c, 2), oadr = PoolBook.state(c, 3), opk = PoolBook.state(c, 4), kmin = PoolBook.state(c, 5);
            if (tok != null && oadr != null && opk != null && kmin != null) keys.add(key(opk, oadr, tok, kmin));
        }
        return keys;
    }

    private void announce(Set<String> present, List<Pool> fundedOwn, final Listener cb) {
        List<Pool> faded = new ArrayList<>();
        for (Pool p : fundedOwn) {
            if (p == null || p.opk == null || p.oadr == null || p.tok == null || p.kmin == null) continue;
            String k = key(p.opk, p.oadr, p.tok, p.kmin);
            if (present.contains(k)) {
                // Beacon is live in the recent window again → RE-ARM: drop the process-dedup guard so that when this
                // beacon fades AGAIN (~20h later) we re-announce it, keeping an offline owner's pool alive across
                // multiple fade cycles for the whole life of this (long-lived) foreground process. Without this the
                // static set is sticky and a discovered pool is re-announced at most ONCE per process, letting its
                // beacon go permanently dark on a later re-fade. Mirrors MDS service.js `delete ANN_SVC[abk]`.
                ANNOUNCED.remove(k);
                continue;
            }
            if (ANNOUNCED.contains(k)) continue;   // already re-posted this process, beacon not yet confirmed into window
            faded.add(p);
        }
        if (faded.isEmpty()) { cb.onAnnounced(0); return; }

        // Gossip is collective: shuffle + cap per run so many nodes don't all re-beacon the same faded pools
        // at once. Whatever this run skips, another node or a later run covers.
        if (faded.size() > MAX_PER_RUN) {
            Collections.shuffle(faded);
            faded = new ArrayList<>(faded.subList(0, MAX_PER_RUN));
        }

        final AtomicInteger pending = new AtomicInteger(faded.size());
        final AtomicInteger posted = new AtomicInteger(0);
        for (Pool p : faded) {
            final String k = key(p.opk, p.oadr, p.tok, p.kmin);
            mgr.reannounce(p, new PoolManager.Result() {
                @Override public void onPosted(String txpowid) { ANNOUNCED.add(k); posted.incrementAndGet(); tick(); }
                @Override public void onFailed(String message) { tick(); }
                private void tick() { if (pending.decrementAndGet() == 0) cb.onAnnounced(posted.get()); }
            });
        }
    }

    static String key(String opk, String oadr, String tok, String kmin) {
        return (opk + "|" + oadr + "|" + tok + "|" + kmin).toLowerCase();
    }
}
