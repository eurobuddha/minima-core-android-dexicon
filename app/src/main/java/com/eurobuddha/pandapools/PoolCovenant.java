// VENDORED VERBATIM from apks/pandapools 0.9.83 app/src/main/java/com/eurobuddha/pandapools/PoolCovenant.java by scripts/vendor-pandapools.sh — do not edit here; re-vendor.
package com.eurobuddha.pandapools;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;

/**
 * The PandaPools constant-product AMM covenant (KISS script) + its parameterisation — the Java port of
 * the reference genpool.py. Each pool's params ($OPK owner pubkey, $OADR owner payout addr, $TOK paired
 * tokenid, $KMIN product floor) are hardcoded literals, so every pool has a UNIQUE address = its script
 * hash. Reserves are the two pool coins' amounts (no state). 0.5% fee stays in the pool = the LP reward.
 *
 * Address derivation is a node call (runscript → response.script.address); do it via PoolBook.
 */
public final class PoolCovenant {

    /** MiniNumber magnitude ceiling — a KMIN literal >= this won't compile (script overflow at parse). */
    public static final BigDecimal MININUMBER_MAX = new BigDecimal("18446744073709551615"); // 2^64-1

    /** The covenant template, one line, with $OPK/$OADR/$TOK/$KMIN placeholders. Byte-identical to poolscript.tpl. */
    private static final String TEMPLATE =
        "IF SIGNEDBY($OPK) THEN "
      + "IF VERIFYOUT(@INPUT $OADR @AMOUNT @TOKENID FALSE) THEN RETURN TRUE ENDIF "
      + "RETURN GETOUTADDR(@INPUT) EQ @ADDRESS AND GETOUTTOK(@INPUT) EQ @TOKENID AND GETOUTAMT(@INPUT) GTE @AMOUNT "
      + "ENDIF "
      + "IF @TOKENID EQ 0x00 THEN "
      + "ASSERT @INPUT % 2 EQ 0 LET s=@INPUT+1 "
      + "ASSERT GETINADDR(s) EQ @ADDRESS AND GETINTOK(s) EQ $TOK "
      + "ASSERT GETOUTADDR(s) EQ @ADDRESS AND GETOUTTOK(s) EQ $TOK "
      + "LET x=@AMOUNT LET y=GETINAMT(s) LET nx=GETOUTAMT(@INPUT) LET ny=GETOUTAMT(s) "
      + "ASSERT VERIFYOUT(@INPUT @ADDRESS nx 0x00 FALSE) "
      + "ELSE "
      + "ASSERT @TOKENID EQ $TOK AND @INPUT % 2 EQ 1 LET s=@INPUT-1 "
      + "ASSERT GETINADDR(s) EQ @ADDRESS AND GETINTOK(s) EQ 0x00 "
      + "ASSERT GETOUTADDR(s) EQ @ADDRESS AND GETOUTTOK(s) EQ 0x00 "
      + "LET y=@AMOUNT LET x=GETINAMT(s) LET ny=GETOUTAMT(@INPUT) LET nx=GETOUTAMT(s) "
      + "ASSERT VERIFYOUT(@INPUT @ADDRESS ny $TOK FALSE) "
      + "ENDIF "
      + "LET dx=nx-x LET dy=ny-y LET fx=MAX(dx 0)*5/1000 LET fy=MAX(dy 0)*5/1000 "
      + "RETURN (nx-fx)*(ny-fy) GTE MAX(x*y $KMIN)";

    /** "PANDAPOOLS" in hex — the shared registry sentinel address. */
    public static final String SENTINEL = "0x50414E4441504F4F4C53";

    /**
     * DISCOVERY depth bound (blocks back from tip) for the {@code coins address:SENTINEL} scan. The sentinel is an
     * unspendable address, so a scan must stay bounded — an UNBOUNDED scan of a coinnotify-accumulated pile once
     * returned ~312 KB and overflowed the IPC broadcast (the uncatchable "can't deliver broadcast" crash, see
     * [[minima-ipc-gotchas]]). But that pile came from the {@code coinnotify add} we REMOVED in 0.9.14, so a clean
     * node's sentinel is tiny: measured ~8 KB even UNBOUNDED (13 beacons). So this is now set NEAR the full ~1700
     * cascade so a pool stays discoverable for essentially its whole life (~20h), not just ~5.5h — the fix for
     * cross-device flicker where a beacon aged past a tight window and vanished from non-owner nodes. Still a bound
     * (not unbounded) so a pathological/attacker beacon pile past 1500 blocks is capped. Re-verify the reply stays
     * well under 256 KB on a busy node before raising further.
     */
    public static final int SENTINEL_SCAN_DEPTH = 1500;

    /**
     * PROACTIVE re-announce threshold: {@link ReAnnouncer} re-posts a pool's beacon once it is older than this —
     * comfortably BEFORE it would fall out of the {@link #SENTINEL_SCAN_DEPTH} discovery window — so a live pool's
     * beacon never lapses on any node (no discovery flicker). Below the discovery depth with a wide margin, and
     * below keep-fresh's REFRESH_BLOCKS so an owner's own refresh (which posts a fresh beacon) usually pre-empts it.
     *
     * HARD INVARIANT — SENTINEL_SCAN_DEPTH > REANNOUNCE_DEPTH + (beacon confirm-lag, a few blocks). A re-announce
     * fires at age REANNOUNCE_DEPTH and its replacement beacon needs several blocks to confirm into the window; if
     * the discovery depth is not comfortably ABOVE the re-announce threshold, a beacon can leave the discovery
     * window before its replacement lands → the exact cross-device flicker this design removes. 1500 vs 1000 gives
     * ~500 blocks of slack. If IPC exposure ever forces a smaller discovery depth, lower BOTH together (keep
     * discovery ≈ 1.5× re-announce), never just one.
     */
    public static final int REANNOUNCE_DEPTH = 1000;

    private PoolCovenant() {}

    /** Fill the template with a pool's four literals. Returns the one-line KISS script. */
    public static String script(String opk, String oadr, String tok, String kmin) {
        return TEMPLATE
            .replace("$OPK", opk)
            .replace("$OADR", oadr)
            .replace("$TOK", tok)
            .replace("$KMIN", kmin);
    }

    /** A fingerprint is insufficient: an arbitrary contract can contain the same snippets.
     * Compare the entire existing template, allowing its recorded fractional fee on both legs. */
    static boolean matches(String actual, String opk, String oadr, String tok, String kmin) {
        if (actual == null || !validParams(opk, oadr, tok, kmin)) return false;
        String normalized = actual.trim().replaceAll("\\s+", " ");
        java.util.regex.Matcher fee = java.util.regex.Pattern.compile(
                "LET fx=MAX\\(dx 0\\)\\*([0-9]+)/([0-9]+) LET fy=MAX\\(dy 0\\)\\*\\1/\\2 ").matcher(normalized);
        if (!fee.find()) return false;
        BigDecimal numerator = new BigDecimal(fee.group(1)), denominator = new BigDecimal(fee.group(2));
        if (denominator.signum() <= 0 || numerator.compareTo(denominator) >= 0) return false;
        String expected = script(opk, oadr, tok, kmin).replace("*5/1000", "*" + fee.group(1) + "/" + fee.group(2));
        return expected.equals(normalized);
    }

    static boolean validParams(String opk, String oadr, String tok, String kmin) {
        if (!FundingCoins.hex(opk) || !FundingCoins.hex(oadr) || !FundingCoins.hex(tok)
                || "0x00".equalsIgnoreCase(tok) || kmin == null || kmin.length() > 80
                || !kmin.matches("[0-9]+(?:\\.[0-9]+)?")) return false;
        BigDecimal k = new BigDecimal(kmin);
        return k.signum() > 0 && k.compareTo(MININUMBER_MAX) < 0;
    }

    /** KMIN = SIGDIG(20, x0*y0) rounded DOWN — the creation product to 20 sig-figs (matches genpool.py).
     *  stripTrailingZeros is CRITICAL: BigDecimal multiply keeps the operands' scale (a grain-clamped
     *  8-dp token gives e.g. 716.2041200000000), but the node normalizes the announce's stored KMIN to
     *  716.20412. If the covenant literal kept the trailing zeros it would hash to a different address
     *  than discovery re-derives from the announce → the pool becomes invisible. Canonicalise so the
     *  covenant, the announce state, and discovery all agree. */
    public static String kmin(BigDecimal x0, BigDecimal y0) {
        BigDecimal p = x0.multiply(y0);
        if (p.signum() == 0) return "0";
        if (p.compareTo(MININUMBER_MAX) >= 0)
            throw new IllegalArgumentException("x0*y0 >= 2^64; reserves too large for a single pool");
        BigDecimal k = p.round(new MathContext(20, RoundingMode.DOWN)).stripTrailingZeros();
        return k.signum() == 0 ? "0" : k.toPlainString();
    }

    /** True if a pool with these reserves is buildable (KMIN literal compiles). */
    public static boolean sizeOk(BigDecimal x0, BigDecimal y0) {
        return x0.multiply(y0).compareTo(MININUMBER_MAX) < 0;
    }
}
