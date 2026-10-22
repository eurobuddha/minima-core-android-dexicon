package com.eurobuddha.dexicon.resting;
/* Vendored from apks/pandadex app/src/main/java/com/eurobuddha/pandadex/PoolCovenant.java (PandaDEX 0.4.23).
 * Keep as close to verbatim as possible; only the package, the NodeApi/SignGate imports and the minimal Dexicon
 * glue noted inline differ. Per the family naming rule the Minima dollar token is MxUSD in identifiers too
 * (PandaDEX MXUSD_ID/MXUSD_DP/*Mxusd are MXUSD_ID/MXUSD_DP/*Mxusd here). Re-copy rather than hand-edit when PandaDEX changes. */


import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;

/**
 * Vendored from PandaPools' reconciled 0.5% covenant core. Do not change independently of
 * PandaPools: the script text hashes to live pool addresses.
 */
public final class PoolCovenant {

    public static final BigDecimal MININUMBER_MAX = new BigDecimal("18446744073709551615");

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

    public static final String SENTINEL = "0x50414E4441504F4F4C53";
    public static final int SENTINEL_SCAN_DEPTH = 1500;
    public static final int REANNOUNCE_DEPTH = 1000;

    private PoolCovenant() {}

    public static String script(String opk, String oadr, String tok, String kmin) {
        if (!validParams(opk, oadr, tok, kmin)) throw new IllegalArgumentException("Invalid pool parameters");
        return TEMPLATE.replace("$OPK", opk).replace("$OADR", oadr).replace("$TOK", tok).replace("$KMIN", kmin);
    }

    static boolean validParams(String opk, String oadr, String tok, String kmin) {
        if (!FundingCoins.hex(opk) || !FundingCoins.hex(oadr) || !FundingCoins.hex(tok)
                || "0x00".equalsIgnoreCase(tok) || kmin == null || kmin.length() > 80
                || !kmin.matches("[0-9]+(?:\\.[0-9]+)?")) return false;
        BigDecimal k = new BigDecimal(kmin);
        return k.signum() > 0 && k.compareTo(MININUMBER_MAX) < 0;
    }

    public static String kmin(BigDecimal x0, BigDecimal y0) {
        BigDecimal p = x0.multiply(y0);
        if (p.signum() == 0) return "0";
        if (p.compareTo(MININUMBER_MAX) >= 0)
            throw new IllegalArgumentException("x0*y0 >= 2^64; reserves too large for a single pool");
        BigDecimal k = p.round(new MathContext(20, RoundingMode.DOWN)).stripTrailingZeros();
        return k.signum() == 0 ? "0" : k.toPlainString();
    }

    public static boolean sizeOk(BigDecimal x0, BigDecimal y0) {
        return x0.multiply(y0).compareTo(MININUMBER_MAX) < 0;
    }
}
