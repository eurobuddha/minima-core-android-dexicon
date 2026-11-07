// VENDORED VERBATIM from apks/pandapools 0.9.87 app/src/main/java/com/eurobuddha/pandapools/PoolRefresher.java by scripts/vendor-pandapools.sh — do not edit here; re-vendor.
package com.eurobuddha.pandapools;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * KEEP-FRESH scheduler. A pool's reserve coins fall out of the ~1700-block cascade into the megammr-only archive
 * after ~a day; a light (non-megammr) node then can't see them, so cross-device discovery drops the pool. This
 * periodically RECREATES its own pools' reserves in place (owner grow-in-place, same amounts, {@link PoolManager#refresh})
 * BEFORE they age out — new young coin IDs → back in the cascade → every light node sees them again. Fully
 * decentralized: each owner keeps its own pools alive; no server, no megammr.
 *
 * OWNER-ONLY (unlike the node-wide beacon re-announce): refresh spends the covenant coins and needs {@code $OPK},
 * so it only ever touches pools this node owns. It re-announces the beacon too (a refresh posts one), so it
 * subsumes re-announce for own pools; the {@link ReAnnouncer} still gossips OTHER pools' faded beacons.
 */
public class PoolRefresher {

    public interface Listener { void onRefreshed(int posted); }

    /** Refresh a reserve coin once it is older than this, so its reserves stay YOUNG enough to sit inside every
     *  node's unpruned window — not just the ~1700-block cascade edge. Lowered 1200 → 900 because a freshly-resynced
     *  node's window can be well under 1200 blocks (~1059 observed), so at 1200 a pool's reserves aged out on that
     *  node BEFORE keep-fresh ever refreshed them → it vanished from that node (cross-device divergence). 900 keeps
     *  reserves young enough for a short window with margin, still leaves a big gap to the cascade edge, and a fresh
     *  refresh also re-posts the beacon so a maintained pool's beacon stays well inside REANNOUNCE_DEPTH too. */
    public static final int REFRESH_BLOCKS = 900;
    /** Per-run cap so a node with many pools doesn't fire a burst of PoW at once; later runs cover the rest. */
    private static final int MAX_PER_RUN = 8;
    /** A refresh takes ~a block or two to confirm; until then the reserves still read as old, so a second trigger
     *  (foreground + background overlapping) would refresh the SAME pool again — one wins at consensus, the other
     *  wastes PoW. Skip a pool refreshed within this window. Far shorter than the next legitimate refresh (~16h), so
     *  it never blocks a real re-refresh. Process-scoped (cleared on death, by when the refresh has long confirmed). */
    private static final long RECENT_TTL_MS = 5 * 60 * 1000L;
    private static final Map<String, Long> RECENT = new ConcurrentHashMap<>();

    private final NodeApi node;
    private final PoolManager mgr;

    public PoolRefresher(NodeApi node) { this.node = node; this.mgr = new PoolManager(node); }

    /**
     * Foreground: given the funded pools this node OWNS (already scanned, so {@link Pool#reserveBlock} is set) and
     * the current chain tip, recreate the ones whose reserves are aging past {@link #REFRESH_BLOCKS}. An unknown
     * reserve age ({@code reserveBlock<=0}, e.g. a coin JSON without {@code created}) is treated as aging — better
     * to refresh than to let a pool silently fall out of the cascade.
     */
    private void finishScan(Context ctx, ScanObserver observer, List<Pool> mine, List<Pool> recipes, int tip, Listener cb) {
        List<Pool> funded = new ArrayList<>(mine);
        if (observer != null) { try { observer.onScanned(funded, recipes, tip); } catch (Throwable ignored) {} }
        refreshAging(funded, tip, cb);
    }

    public void refreshAging(List<Pool> ownFunded, int chainBlock, Listener cb) {
        long now = System.currentTimeMillis();
        List<Pool> aging = new ArrayList<>();
        if (ownFunded != null)
            for (Pool p : ownFunded)
                if (ReserveRecovery.completeReserves(p) && !p.signingStateUnverified && p.address != null && !refreshedRecently(p.address, now)
                        && (p.reserveBlock <= 0 || p.reserveAge(chainBlock) > REFRESH_BLOCKS)) aging.add(p);
        post(aging, cb);
    }

    private static boolean refreshedRecently(String address, long now) {
        Long t = RECENT.get(address.toLowerCase());
        return t != null && (now - t) < RECENT_TTL_MS;
    }

    /**
     * Background (no UI): read the tip, then build the owned funded-pool list straight from {@link OwnPoolStore}
     * — a per-covenant {@code coins address:<pool>} reserve scan per recipe (~1.6 KB each, safe on any node) —
     * and refresh the aging ones. We deliberately do NOT run a full {@link PoolBook#scan} here: keep-fresh only
     * ever touches OUR pools (it spends covenant coins + needs $OPK), and the sentinel scan is the IPC-overflow
     * risk this release fixes. A stored recipe IS ours, so no sentinel/scripts/keys lookup is needed.
     */
    /** Hands the live owned-pool list and the tip to {@code observer} before refreshing, so a caller can judge
     *  stranding without paying for a second scan. The observer is called on the IPC thread; it must not block. */
    public interface ScanObserver { void onScanned(List<Pool> ownFunded, List<Pool> allRecipes, int tip); }

    public void refreshAgingFromScan(final Context ctx, final Listener cb) {
        refreshAgingFromScan(ctx, null, cb);
    }

    public void refreshAgingFromScan(final Context ctx, final ScanObserver observer, final Listener cb) {
        node.cmd("block", new NodeApi.Cb() {
            @Override public void onResult(JSONObject bj) {
                final int tip = parseBlock(bj);
                // Pools Dexicon keeps are skipped entirely: no refresh and no stranding alarm from here.
                final List<Pool> recipes = Handover.keepable(ctx, OwnPoolStore.all(ctx));
                if (tip <= 0 || recipes.isEmpty()) { cb.onRefreshed(0); return; }
                final List<Pool> mine = Collections.synchronizedList(new ArrayList<>());
                final AtomicInteger pending = new AtomicInteger(recipes.size());
                for (final Pool r : recipes) {
                    if (r == null || r.address == null) {
                        if (pending.decrementAndGet() == 0) finishScan(ctx, observer, mine, recipes, tip, cb);
                        continue;
                    }
                    readLiveReserves(node, r, found -> {
                        if (found && ReserveRecovery.completeReserves(r)) mine.add(r);
                        if (pending.decrementAndGet() == 0) finishScan(ctx, observer, mine, recipes, tip, cb);
                    });
                }
            }
            @Override public void onError(String m) { cb.onRefreshed(0); }
        });
    }

    /** Largest coin per leg at the covenant address = the true reserve (a forged dust coin can't masquerade); also
     *  record the newest kept-coin block so {@link Pool#reserveAge} is right. Mirrors {@link PoolBook}'s fund logic. */
    static boolean fillReserves(Pool p, JSONObject j) {
        p.reserveM = null; p.reserveT = null; p.coinidM = null; p.coinidT = null;
        p.reserveBlock = 0; p.reserveBlockM = 0; p.reserveBlockT = 0;
        JSONArray cs = FundingCoins.rows(j);
        if (cs == null) return false;
        int mBlk = 0, tBlk = 0;
        for (int i = 0; i < cs.length(); i++) {
            JSONObject c = cs.optJSONObject(i);
            if (!reserveCoin(p, c)) continue;
            String tid = c.optString("tokenid", "");
            if ("0x00".equals(tid)) {
                BigDecimal amt = new BigDecimal(c.optString("amount", "0"));
                if (p.reserveM == null || amt.compareTo(p.reserveM) > 0) { p.reserveM = amt; p.coinidM = c.optString("coinid", ""); mBlk = c.optInt("created", 0); }
            } else if (p.tok != null && p.tok.equalsIgnoreCase(tid)) {
                BigDecimal amt = new BigDecimal(c.optString("tokenamount"));
                if (p.reserveT == null || amt.compareTo(p.reserveT) > 0) { p.reserveT = amt; p.coinidT = c.optString("coinid", ""); tBlk = c.optInt("created", 0); }
                p.tokName = Util.tokenName(c.opt("token"), tid);
                p.tokDecimals = Util.tokenDecimals(c.opt("token"));
            }
        }
        p.reserveBlock = Math.max(mBlk, tBlk);
        p.reserveBlockM = mBlk; p.reserveBlockT = tBlk;
        return true;
    }

    /** Reuses PoolBook's address, stateless-coin and human token-amount validation. */
    static boolean reserveCoin(Pool p, JSONObject raw) {
        if (p == null || raw == null || !Boolean.FALSE.equals(raw.opt("spent"))) return false;
        try {
            Coin c = FundingCoins.fundingCoin(raw);
            Object state = raw.opt("state");
            return FundingCoins.hex(c.coinid) && p.address != null && p.address.equalsIgnoreCase(c.address)
                    && ("0x00".equalsIgnoreCase(c.tokenid) || (p.tok != null && p.tok.equalsIgnoreCase(c.tokenid)))
                    && (!(state instanceof JSONArray) || ((JSONArray)state).length() == 0)
                    && (!(state instanceof JSONObject) || ((JSONObject)state).length() == 0);
        } catch (RuntimeException invalid) { return false; }
    }

    /** Callback for {@link #readLiveReserves}: {@code ok} = the covenant address was read AND the
     *  pool is still funded (a live reserve coin present on each leg). */
    public interface LiveReadCb { void done(boolean ok); }

    /**
     * Re-read a SINGLE pool's live reserve coins at its covenant address and overwrite
     * {@code coinidM/coinidT/reserveM/reserveT} with the current largest coin per leg. Call this
     * immediately before an owner transaction (close / add / migrate) so the pool is spent at its
     * CURRENT coin, not a stale snapshot: a counterparty swap — or this node's own keep-fresh
     * ({@link #REFRESH_BLOCKS}) — spends and recreates the reserve coins, so a coin id cached at the
     * last registry scan becomes a SPENT coin and the txn dies at {@code txncheck} with
     * "an input coin was already spent (the pool moved)". Covenant params
     * ({@code opk/oadr/tok/kmin/address}) are invariant across coin moves and are left untouched.
     * The snapshot is cleared first because {@link #fillReserves} only overwrites when the new
     * amount is larger. Runs on the node-callback (main) thread, like every {@link NodeApi} reply.
     */
    public static void readLiveReserves(NodeApi node, final Pool p, final LiveReadCb cb) {
        if (node == null || p == null || p.address == null || p.address.isEmpty()) { cb.done(false); return; }
        if (!FundingCoins.hex(p.coinidM) || !FundingCoins.hex(p.coinidT)) {
            for (Pool saved : OwnPoolStore.all(node.context())) if (p.address.equalsIgnoreCase(saved.address)) {
                p.coinidM = saved.coinidM; p.coinidT = saved.coinidT; break;
            }
        }
        ReserveRecovery.readCoins(node::cmd, p.address, false, FundingCoins.SAFE_COINS, p.coinidM, p.coinidT, new NodeApi.Cb() {
            @Override public void onResult(JSONObject j) {
                boolean found = fillReserves(p, j) && p.funded();
                if (found) OwnPoolStore.rememberReserves(node.context(), p);
                cb.done(found);
            }
            @Override public void onError(String m) { fillReserves(p, null); cb.done(false); }
        });
    }

    private void post(List<Pool> aging, final Listener cb) {
        if (aging.isEmpty()) { cb.onRefreshed(0); return; }
        if (aging.size() > MAX_PER_RUN) { Collections.shuffle(aging); aging = new ArrayList<>(aging.subList(0, MAX_PER_RUN)); }
        final AtomicInteger pending = new AtomicInteger(aging.size());
        final AtomicInteger posted = new AtomicInteger(0);
        for (final Pool p : aging) {
            RECENT.put(p.address.toLowerCase(), System.currentTimeMillis());   // reserve the slot before posting (blocks a concurrent double-fire)
            mgr.refresh(p, new PoolManager.Result() {
                @Override public void onPosted(String txpowid) { posted.incrementAndGet(); tick(); }
                @Override public void onFailed(String message) { RECENT.remove(p.address.toLowerCase()); tick(); }   // failed → allow a retry next cycle
                private void tick() { if (pending.decrementAndGet() == 0) cb.onRefreshed(posted.get()); }
            });
        }
    }

    private static int parseBlock(JSONObject j) {
        try { return Integer.parseInt(j.getJSONObject("response").getString("block")); }
        catch (Exception e) { return 0; }
    }
}
