// VENDORED VERBATIM from apks/pandapools 0.9.81 app/src/main/java/com/eurobuddha/pandapools/TxClassifier.java by scripts/vendor-pandapools.sh — do not edit here; re-vendor.
package com.eurobuddha.pandapools;

import org.json.JSONArray;
import org.json.JSONObject;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Turns a raw {@link HistoryEntry} into an accounting-shaped transaction: what kind of PandaPools action it
 * was, which pool it touched, how much of each leg moved, and the price that implies.
 *
 * Everything is derived from data already stored on-device — the per-token wallet {@code deltas} and the
 * coin arrays. Nothing is estimated and no node call is made, so an export is reproducible offline and
 * gives the same answer every time.
 *
 * {@link ActivityLog} deliberately is NOT used as the source of truth: it holds only a prose summary (no
 * tokenid, no leg amounts), is capped at 60 entries, and is device-local. The transaction's own shape is
 * both more complete and more reliable.
 *
 * ORDER MATTERS in {@link #classify}. Two traps:
 *   • A migrate and a keep-fresh both spend AND recreate pool coins. They are told apart by ADDRESS —
 *     a refresh recreates at the SAME covenant address, a migrate exits the old one and creates a NEW one.
 *   • A keep-fresh moves no token and only dust MINIMA, so testing it before the deposit/withdraw rules
 *     stops it being booked as liquidity movement (it is neither — it is a maintenance transaction).
 */
public final class TxClassifier {

    /** Lookup of "does this coin array touch a pool covenant address, and which one". Implemented by
     *  {@link PandaAddrBook}; a lambda in tests. */
    public interface Addrs { String hitAddress(String coinsJson); }

    // ---- types ----
    public static final String SWAP               = "SWAP";
    public static final String POOL_CREATE        = "POOL_CREATE";
    public static final String POOL_DEPOSIT       = "POOL_DEPOSIT";
    public static final String POOL_WITHDRAW      = "POOL_WITHDRAW";
    public static final String POOL_MIGRATE       = "POOL_MIGRATE";
    public static final String POOL_KEEPALIVE     = "POOL_KEEPALIVE";
    public static final String OTHER_PARTY_SWAP   = "OTHER_PARTY_SWAP";
    public static final String SELF_SPLIT         = "SELF_SPLIT";
    public static final String SELF_CONSOLIDATION = "SELF_CONSOLIDATION";
    public static final String SELF               = "SELF";
    public static final String TRANSFER_IN        = "TRANSFER_IN";
    public static final String TRANSFER_OUT       = "TRANSFER_OUT";

    /** Reported prices only — never a fund-critical computation, so plain HALF_UP at 20 significant
     *  figures (the pool's own {@code spotPrice} grain) rather than the pool-favourable DOWN used on-chain. */
    private static final MathContext MC = new MathContext(20, RoundingMode.HALF_UP);

    private TxClassifier() {}

    /** One classified transaction. Amounts are signed from the WALLET's point of view: negative left the
     *  wallet, positive arrived. */
    public static final class Tx {
        public final HistoryEntry entry;
        public final String type;
        public final String poolAddress;      // covenant address touched, or null
        public final String tokenid;          // the non-MINIMA leg's tokenid, or null
        public final BigDecimal minimaDelta;  // signed
        public final BigDecimal tokenDelta;   // signed
        public final BigDecimal impliedPrice; // token per MINIMA, or null when a leg is zero
        public final BigDecimal burn;         // MINIMA burned by this tx (>= 0)

        Tx(HistoryEntry e, String type, String poolAddress, String tokenid,
           BigDecimal minimaDelta, BigDecimal tokenDelta, BigDecimal impliedPrice, BigDecimal burn) {
            this.entry = e; this.type = type; this.poolAddress = poolAddress; this.tokenid = tokenid;
            this.minimaDelta = minimaDelta; this.tokenDelta = tokenDelta;
            this.impliedPrice = impliedPrice; this.burn = burn;
        }

        /** A PandaPools action, as opposed to an ordinary wallet movement. */
        public boolean isPandaPools() {
            return SWAP.equals(type) || POOL_CREATE.equals(type) || POOL_DEPOSIT.equals(type)
                || POOL_WITHDRAW.equals(type) || POOL_MIGRATE.equals(type) || POOL_KEEPALIVE.equals(type);
        }

        /** Liquidity actually entering or leaving one of our positions (excludes maintenance + swaps). */
        public boolean isLiquidityMove() {
            return POOL_CREATE.equals(type) || POOL_DEPOSIT.equals(type)
                || POOL_WITHDRAW.equals(type) || POOL_MIGRATE.equals(type);
        }

        /** Someone else's trade against a pool we track: it is in the node's relevant history but moved
         *  none of our funds, so it must not appear in our P/L. */
        public boolean movedOurFunds() {
            return minimaDelta.signum() != 0 || tokenDelta.signum() != 0;
        }
    }

    public static Tx classify(HistoryEntry e, Addrs addrs) {
        Map<String, BigDecimal> d = e.deltaMap();

        BigDecimal dM = d.getOrDefault(Util.MINIMA_TOKENID, BigDecimal.ZERO);
        // the token leg = the largest |net move| that isn't MINIMA (a PandaPools tx has at most one)
        String tokenid = null;
        BigDecimal dT = BigDecimal.ZERO;
        for (Map.Entry<String, BigDecimal> en : d.entrySet()) {
            if (Util.isMinima(en.getKey())) continue;
            if (en.getValue().abs().compareTo(dT.abs()) > 0) { dT = en.getValue(); tokenid = en.getKey(); }
        }

        String inPool = addrs.hitAddress(e.inputs);     // a pool coin was SPENT
        String outPool = addrs.hitAddress(e.outputs);   // a pool coin was CREATED
        BigDecimal burn = e.burn();
        BigDecimal price = impliedPrice(dM, dT);

        String type = type(e, inPool, outPool, dM, dT);
        String pool = outPool != null ? outPool : inPool;
        return new Tx(e, type, pool, tokenid, dM, dT, price, burn);
    }

    private static String type(HistoryEntry e, String inPool, String outPool, BigDecimal dM, BigDecimal dT) {
        boolean touchesPool = inPool != null || outPool != null;

        // A pool that was spent at one address and recreated at ANOTHER is a migrate — and only the owner
        // can do that. It is checked first because a same-size migrate nets to zero for the wallet, which
        // would otherwise be mistaken for a stranger's trade by the rule below.
        if (inPool != null && outPool != null && !inPool.equalsIgnoreCase(outPool)) return POOL_MIGRATE;

        // A trade by someone else against a pool we track: relevant to the node (we hold the covenant
        // script) but zero net effect on us. Must be excluded from P/L.
        if (touchesPool && dM.signum() == 0 && dT.signum() == 0) return OTHER_PARTY_SWAP;

        if (inPool != null && outPool != null) {
            // Rebuilt in place with no token movement and only beacon dust in MINIMA → keep-fresh.
            if (isKeepAlive(e, dM, dT)) return POOL_KEEPALIVE;
            if (e.legs() != null) return SWAP;                                  // two opposite legs
            if (dM.signum() < 0 && dT.signum() < 0) return POOL_DEPOSIT;        // grow in place
            if (dM.signum() > 0 && dT.signum() > 0) return POOL_WITHDRAW;
            return SWAP;
        }
        if (outPool != null) return POOL_CREATE;      // pool coins appeared, none were spent
        if (inPool != null) return POOL_WITHDRAW;     // pool coins spent, none recreated → close

        // No pool coin at all. A bare re-announce still pays beacon dust to the sentinel.
        if (isKeepAlive(e, dM, dT)) return POOL_KEEPALIVE;

        if ("self".equals(e.direction)) {
            if (e.isSplit()) return SELF_SPLIT;
            if (e.isConsolidation()) return SELF_CONSOLIDATION;
            return SELF;
        }
        return e.incoming ? TRANSFER_IN : TRANSFER_OUT;
    }

    /**
     * A keep-fresh / re-announce: no token moved, MINIMA moved outward by no more than the beacon dust plus
     * whatever was burned, and the transaction actually pays a beacon.
     *
     * The beacon is identified by its exact {@link PoolManager#ANNOUNCE_DUST} amount rather than by the
     * sentinel address, because {@link HistoryEntry} stores a coin's {@code miniaddress} (Mx) form in
     * preference to the raw hex, and only the hex sentinel is known as a constant.
     */
    private static boolean isKeepAlive(HistoryEntry e, BigDecimal dM, BigDecimal dT) {
        if (dT.signum() != 0) return false;               // any token movement is real liquidity
        if (dM.signum() > 0) return false;                // maintenance never pays us
        if (!hasBeaconOutput(e.outputs)) return false;
        BigDecimal spent = dM.abs();
        return spent.compareTo(PoolManager.ANNOUNCE_DUST.add(e.burn())) <= 0;
    }

    /** True if any output is a MINIMA coin of exactly the beacon dust amount. */
    private static boolean hasBeaconOutput(String coinsJson) {
        if (coinsJson == null || coinsJson.isEmpty()) return false;
        try {
            JSONArray a = new JSONArray(coinsJson);
            for (int i = 0; i < a.length(); i++) {
                JSONObject c = a.optJSONObject(i);
                if (c == null) continue;
                if (!Util.isMinima(c.optString("tokenid", "0x00"))) continue;
                BigDecimal amt = Util.decOr(c.optString("amount", ""), null);
                if (amt != null && amt.compareTo(PoolManager.ANNOUNCE_DUST) == 0) return true;
            }
        } catch (Exception ignore) {}
        return false;
    }

    // ---- per-pool attribution -------------------------------------------------------------------

    /**
     * OUR share of this transaction, split across EVERY pool it touched.
     *
     * A routed swap is ONE transaction spanning up to {@code PoolRouter.MAX_POOLS} pools — each pool's
     * MINIMA leg at an even input and its token leg at the odd sibling, all recreated as outputs (see
     * {@link PoolTxn}). Booking the whole transaction against a single address, as a naive read of
     * {@link Tx#poolAddress} would, credits one pool with a trade that was split across three.
     *
     * Each pool's reserve change is measurable directly from the stored coin arrays:
     * <pre>reserveChange_P = Σ(outputs at P) − Σ(inputs at P)</pre>
     * and for OUR transactions the wallet is the counterparty to every pool touched, so our flow into pool
     * P is exactly {@code −reserveChange_P}. That holds uniformly for create, deposit, swap, withdraw and
     * routed multi-pool swaps — the split falls out of the arithmetic rather than being apportioned.
     *
     * It must NOT be applied to a stranger's trade: their swap moves reserves while our flow is zero.
     * Callers gate on {@link Tx#movedOurFunds()}.
     *
     * @return address (original case, as stored on the coin) → {minimaFlow, tokenFlow}, signed from the
     *         wallet's point of view. Empty when no pool was touched.
     */
    public static Map<String, BigDecimal[]> poolFlows(HistoryEntry e, Addrs addrs) {
        Map<String, BigDecimal[]> byPool = new LinkedHashMap<>();
        accumulate(byPool, e.outputs, addrs, false);   // outputs ADD to reserves
        accumulate(byPool, e.inputs, addrs, true);     // inputs REMOVE from them
        // reserveChange -> our flow: negate
        for (BigDecimal[] v : byPool.values()) { v[0] = v[0].negate(); v[1] = v[1].negate(); }
        return byPool;
    }

    private static void accumulate(Map<String, BigDecimal[]> byPool, String coinsJson, Addrs addrs, boolean subtract) {
        if (coinsJson == null || coinsJson.isEmpty()) return;
        try {
            JSONArray a = new JSONArray(coinsJson);
            for (int i = 0; i < a.length(); i++) {
                JSONObject c = a.optJSONObject(i);
                if (c == null) continue;
                String addr = c.optString("addr", "");
                if (addr.isEmpty()) continue;
                // Ask the address book about this one coin, so we learn WHICH pool it belongs to rather
                // than just whether the transaction touched some pool.
                String pool = addrs.hitAddress(new JSONArray().put(c).toString());
                if (pool == null) continue;
                BigDecimal amt = Util.decOr(c.optString("amount", ""), null);
                if (amt == null) continue;
                if (subtract) amt = amt.negate();
                BigDecimal[] v = byPool.computeIfAbsent(key(byPool, pool), k -> new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO});
                boolean minima = Util.isMinima(c.optString("tokenid", "0x00"));
                v[minima ? 0 : 1] = v[minima ? 0 : 1].add(amt);
            }
        } catch (Exception ignore) {}
    }

    /** Reuse the casing already recorded for this pool so hex and Mx forms of one address don't split. */
    private static String key(Map<String, BigDecimal[]> byPool, String addr) {
        for (String k : byPool.keySet()) if (k.equalsIgnoreCase(addr)) return k;
        return addr;
    }

    /**
     * Does the per-pool split account for the whole transaction? Σ of our per-pool flows must equal the
     * wallet's own net movement, give or take the MINIMA burned (which is paid to no pool).
     *
     * This is an equation, not a heuristic, so it is checked rather than assumed: a transaction that fails
     * it is reported and left out of the totals instead of being mis-booked.
     */
    public static boolean splitReconciles(Tx t, Map<String, BigDecimal[]> flows) {
        BigDecimal m = BigDecimal.ZERO, tok = BigDecimal.ZERO;
        for (BigDecimal[] v : flows.values()) { m = m.add(v[0]); tok = tok.add(v[1]); }
        // token side must match exactly; MINIMA may differ by the burn (and by beacon dust paid to the
        // sentinel, which is not a pool address and so is never in `flows`).
        if (tok.compareTo(t.tokenDelta) != 0) return false;
        BigDecimal slack = t.burn.add(PoolManager.ANNOUNCE_DUST);
        return m.subtract(t.minimaDelta).abs().compareTo(slack) <= 0;
    }

    /** Token per MINIMA implied by the two legs of this transaction, or null when either leg is zero.
     *  For a swap this is the price actually executed; for a create or deposit it is the price the
     *  position was opened at. Both come straight from the on-chain amounts. */
    private static BigDecimal impliedPrice(BigDecimal dM, BigDecimal dT) {
        if (dM == null || dT == null || dM.signum() == 0 || dT.signum() == 0) return null;
        return dT.abs().divide(dM.abs(), MC);
    }
}
