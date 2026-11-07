// VENDORED VERBATIM from apks/pandapools 0.9.86 app/src/test/java/com/eurobuddha/pandapools/PoolCovenantTest.java by scripts/vendor-pandapools.sh — do not edit here; re-vendor.
package com.eurobuddha.pandapools;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.math.BigDecimal;

/**
 * Covenant generation + KMIN canonicalisation. Fund-critical: the covenant string is hashed to the pool
 * ADDRESS, so any drift here strands funds / makes pools undiscoverable. These lock the template, the fee,
 * the KMIN canonical form, and the overflow guard against silent regression.
 */
public class PoolCovenantTest {
    @Test public void fullContractValidationRejectsFingerprintSpoofAndRetainsRecordedFee() {
        String current = PoolCovenant.script(OPK, OADR, TOK, KMIN);
        assertTrue(PoolCovenant.matches(current, OPK, OADR, TOK, KMIN));
        assertTrue(PoolCovenant.matches(current.replace("*5/1000", "*3/1000"), OPK, OADR, TOK, KMIN));
        assertFalse(PoolCovenant.matches("RETURN TRUE " + current, OPK, OADR, TOK, KMIN));
        assertFalse(PoolCovenant.matches(current.replace("GTE MAX(x*y", "LTE MAX(x*y"), OPK, OADR, TOK, KMIN));
        assertFalse(PoolCovenant.matches(current.replace("*5/1000", "*5/0"), OPK, OADR, TOK, KMIN));
        assertFalse(PoolCovenant.matches(current, OPK, "0xaabb", TOK, KMIN));
    }

    // A real mainnet pool's params observed on-device (MINIMA/mxUSDT).
    private static final String OPK  = "0xFC8E05CACC5D101B79A13635B81854607A9B485C1AC19D95D7AA1B3A6C952A11";
    private static final String OADR = "0xA050F76F4B1F92AC1DC0D8863827598BF4F30A66281FFE5C839404BEB561A021";
    private static final String TOK  = "0x7D39745FBD29049BE29850B55A18BF550E4D442F930F86266E34193D89042A90";
    private static final String KMIN = "186.915888";

    @Test public void scriptSubstitutesEveryPlaceholder() {
        String s = PoolCovenant.script(OPK, OADR, TOK, KMIN);
        assertFalse("no $OPK left",  s.contains("$OPK"));
        assertFalse("no $OADR left", s.contains("$OADR"));
        assertFalse("no $TOK left",  s.contains("$TOK"));
        assertFalse("no $KMIN left", s.contains("$KMIN"));
        assertFalse("no stray placeholder", s.contains("$"));
    }

    @Test public void scriptKeepsThe05PercentFeeWithRealSlash() {
        String s = PoolCovenant.script(OPK, OADR, TOK, KMIN);
        assertTrue("0.5% fee on the MINIMA leg", s.contains("*5/1000"));
        // the fund-stranding bug is a BACKSLASH before the slash; the covenant text itself must never contain it
        assertFalse("covenant must not contain an escaped slash", s.contains("\\/"));
    }

    @Test public void scriptIsExactlyTheExpectedCovenant() {
        // Byte-for-byte reconstruction of the covenant for the real params above (matches the on-device script).
        String expected =
            "IF SIGNEDBY(" + OPK + ") THEN "
          + "IF VERIFYOUT(@INPUT " + OADR + " @AMOUNT @TOKENID FALSE) THEN RETURN TRUE ENDIF "
          + "RETURN GETOUTADDR(@INPUT) EQ @ADDRESS AND GETOUTTOK(@INPUT) EQ @TOKENID AND GETOUTAMT(@INPUT) GTE @AMOUNT "
          + "ENDIF "
          + "IF @TOKENID EQ 0x00 THEN "
          + "ASSERT @INPUT % 2 EQ 0 LET s=@INPUT+1 "
          + "ASSERT GETINADDR(s) EQ @ADDRESS AND GETINTOK(s) EQ " + TOK + " "
          + "ASSERT GETOUTADDR(s) EQ @ADDRESS AND GETOUTTOK(s) EQ " + TOK + " "
          + "LET x=@AMOUNT LET y=GETINAMT(s) LET nx=GETOUTAMT(@INPUT) LET ny=GETOUTAMT(s) "
          + "ASSERT VERIFYOUT(@INPUT @ADDRESS nx 0x00 FALSE) "
          + "ELSE "
          + "ASSERT @TOKENID EQ " + TOK + " AND @INPUT % 2 EQ 1 LET s=@INPUT-1 "
          + "ASSERT GETINADDR(s) EQ @ADDRESS AND GETINTOK(s) EQ 0x00 "
          + "ASSERT GETOUTADDR(s) EQ @ADDRESS AND GETOUTTOK(s) EQ 0x00 "
          + "LET y=@AMOUNT LET x=GETINAMT(s) LET ny=GETOUTAMT(@INPUT) LET nx=GETOUTAMT(s) "
          + "ASSERT VERIFYOUT(@INPUT @ADDRESS ny " + TOK + " FALSE) "
          + "ENDIF "
          + "LET dx=nx-x LET dy=ny-y LET fx=MAX(dx 0)*5/1000 LET fy=MAX(dy 0)*5/1000 "
          + "RETURN (nx-fx)*(ny-fy) GTE MAX(x*y " + KMIN + ")";
        assertEquals(expected, PoolCovenant.script(OPK, OADR, TOK, KMIN));
    }

    @Test public void kminStripsTrailingZeros() {
        // 20 * 0.1 = 2.0 → canonical "2" (the node normalises the announce's KMIN; a covenant that kept "2.0"
        // would hash to a different address than discovery re-derives → invisible pool).
        assertEquals("2", PoolCovenant.kmin(new BigDecimal("20"), new BigDecimal("0.1")));
        assertEquals("1", PoolCovenant.kmin(new BigDecimal("1"), new BigDecimal("1")));
        assertEquals("0", PoolCovenant.kmin(new BigDecimal("0"), new BigDecimal("5")));
    }

    @Test public void kminRoundsDownTo20SigFigsAndIsCanonical() {
        String k = PoolCovenant.kmin(new BigDecimal("20"), new BigDecimal("0.11282051")); // = 2.2564102
        assertEquals("2.2564102", k);
        // canonical == its own stripTrailingZeros round-trip
        assertEquals(new BigDecimal(k).stripTrailingZeros(), new BigDecimal(k));
    }

    @Test public void kminRoundsDownNeverUp() {
        // a product with >20 sig-figs must round DOWN (pool-favourable), never up
        BigDecimal x = new BigDecimal("123456789.123456789");
        BigDecimal y = new BigDecimal("9.87654321");
        BigDecimal exact = x.multiply(y);
        BigDecimal k = new BigDecimal(PoolCovenant.kmin(x, y));
        assertTrue("KMIN <= exact product (rounded down)", k.compareTo(exact) <= 0);
    }

    @Test public void sizeOkGuardsTheOverflowCeiling() {
        assertTrue(PoolCovenant.sizeOk(new BigDecimal("1000000000"), new BigDecimal("1000000000"))); // 1e18 < 2^64
        assertFalse(PoolCovenant.sizeOk(new BigDecimal("5000000000"), new BigDecimal("5000000000"))); // 2.5e19 > 2^64
    }

    @Test public void kminThrowsWhenProductOverflows() {
        try {
            PoolCovenant.kmin(new BigDecimal("5000000000"), new BigDecimal("5000000000"));
            fail("expected overflow guard to throw");
        } catch (IllegalArgumentException expected) { /* ok */ }
    }

    @Test public void constantsAreLocked() {
        // Guard the two values that, if changed, either strand funds (sentinel) or reintroduce the IPC crash (depth).
        assertEquals("0x50414E4441504F4F4C53", PoolCovenant.SENTINEL);
        // 400 until 0.9.15, which widened the discovery window to 1500 to stop pools flickering out of
        // view on non-owner nodes. Hard invariant: SENTINEL_SCAN_DEPTH > REANNOUNCE_DEPTH + confirm-lag.
        assertEquals(1500, PoolCovenant.SENTINEL_SCAN_DEPTH);
        assertTrue(PoolCovenant.SENTINEL_SCAN_DEPTH > PoolCovenant.REANNOUNCE_DEPTH);
        assertEquals(new BigDecimal("18446744073709551615"), PoolCovenant.MININUMBER_MAX);
    }
}
