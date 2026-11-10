// VENDORED VERBATIM from apks/pandapools 0.9.94 app/src/main/java/com/eurobuddha/pandapools/Pool.java by scripts/vendor-pandapools.sh — do not edit here; re-vendor.
package com.eurobuddha.pandapools;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;

/**
 * A discovered AMM pool: its derived covenant address + params (from the registry announce) + its two live
 * reserve coins. Reserves are the coin amounts (no state). Price/K are derived, not stored on-chain.
 */
public class Pool {

    public String address;      // derived covenant address (0x)
    public String mxaddress;    // Mx form
    public String opk, oadr, tok, kmin;   // announce params (owner pk, payout addr, tokenid, floor)
    /** For a pool DISCOVERED via the shared registry (not one this node created): its covenant script, so
     *  discovery can `newscript trackall` it once it's confirmed funded — track-on-discovery, which makes a
     *  seen-but-not-created pool stay GTC-visible + swappable on this node forever. Null for own pools. */
    public String covenantScript;
    public String tokName;      // display name of the token side (resolved when reserves are scanned)
    public int tokDecimals = 8; // token's on-chain decimal grain (resolved when reserves are scanned)
    /** $OPK's derivation index (the key's {@code modifier}); -1 unknown. Known only for OWN pools —
     *  captured from {@code newaddress}'s reply at create, or from the node's key row at backup time.
     *  Lets {@link OwnerKeyRecovery} hunt to the exact index, and PROVE a foreign seed with zero mints. */
    public int kidx = -1;
    /** Lower bound from a previously observed key count. Never a claim about later signatures. */
    public int minimumOwnerUses = -1;
    /** Imported recipes cannot establish current wallet signing state. Cleared only by the owner. */
    public boolean signingStateUnverified = false;
    boolean newlyCreatedWithCurrentOwnerState = false;
    /** This pool was closed or migrated away, so its recipe is HIDDEN from the pool lists — never deleted.
     *  A recipe is the only thing that can reclaim a pool, so retiring is reversible by design: if a scan
     *  ever finds the pool live again (a close that never landed), {@link MyLpView} un-retires it. */
    public boolean retired = false;

    /** Display symbol for the token side: its resolved name where we have one, otherwise the FULL tokenid.
     *  Never abbreviated — a shortened tokenid cannot be pasted into an explorer or a support request, and this
     *  label reaches transaction confirmations and the CSV statement. Checking the shared name cache first means
     *  the fallback is rare in practice. */
    public String tokenLabel() {
        if (tokName != null && !tokName.isEmpty() && !tokName.equalsIgnoreCase(tok)) return tokName;
        String cached = Util.tokenNameCached(tok);
        if (cached != null && !cached.isEmpty() && !cached.equalsIgnoreCase(tok)) return cached;
        return tok == null ? "" : tok;
    }

    // live reserves (null until scanned)
    public BigDecimal reserveM;    // MINIMA reserve (x)
    public String coinidM;
    public BigDecimal reserveT;    // token reserve (y, scaled token units)
    public String coinidT;
    /** Block at which the reserve coins were created (the YOUNGER leg — a swap/refresh recreates both, so both
     *  are usually equal; take the max so age reflects the most recent recreate). Set when reserves are scanned;
     *  0 = unknown. Used by the keep-fresh refresher to detect reserves aging toward the cascade edge. */
    public int reserveBlock = 0;
    public int reserveBlockM = 0, reserveBlockT = 0;

    public boolean funded() { return reserveM != null && reserveT != null
            && reserveM.signum() > 0 && reserveT.signum() > 0; }

    /** How many blocks old the reserves are (0 if unknown). A pool must be refreshed before this passes the
     *  cascade length (~1700), or its reserves fall into the megammr-only archive and light nodes lose them. */
    public int reserveAge(int chainBlock) { return reserveBlock > 0 && chainBlock > reserveBlock ? chainBlock - reserveBlock : 0; }

    /** Constant product K = x*y (the current invariant value; grows with each swap's fee). */
    public BigDecimal k() { return funded() ? reserveM.multiply(reserveT) : BigDecimal.ZERO; }

    /** Spot price = y/x = token per MINIMA (the marginal price at the current reserves). */
    public BigDecimal spotPrice() {
        if (!funded()) return BigDecimal.ZERO;
        return reserveT.divide(reserveM, new MathContext(20, RoundingMode.DOWN));
    }

    /** Accrued-fee proxy: K/KMIN - 1 (0 at creation, grows as fees accrue). */
    public BigDecimal feeGrowth() {
        BigDecimal km = Util.decOr(kmin, BigDecimal.ZERO);
        if (km.signum() == 0) return BigDecimal.ZERO;
        return k().divide(km, new MathContext(20, RoundingMode.DOWN)).subtract(BigDecimal.ONE);
    }
}
