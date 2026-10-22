package com.eurobuddha.dexicon;

import static org.junit.Assert.*;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;

import org.junit.Test;

public class OrderPreviewTest {
    static BigDecimal d(String s) { return new BigDecimal(s); }
    static Funds funds(String m, String u, String mo, String uo) { return new Funds(d(m), d(u), d(mo), d(uo), BigDecimal.ZERO, BigDecimal.ZERO); }

    @Test public void buyCrossesTwoLevelsAndRestsTheRest() {
        OrderPreview p = OrderPreview.of(true, d("0.0037"), d("1500"),
                Arrays.asList(new OrderPreview.Lvl(d("0.003675"), d("1000")), new OrderPreview.Lvl(d("0.00368"), d("300")), new OrderPreview.Lvl(d("0.0038"), d("999"))),
                funds("65", "10", "0", "0"), d("0.0036325"));
        assertEquals(0, d("1300").compareTo(p.fillNow));
        assertEquals(0, d("200").compareTo(p.rest));
        assertEquals(0, d("4.779").compareTo(p.fillCost));                 // 1000*0.003675 + 300*0.00368
        assertEquals(0, d("5.519").compareTo(p.need));                     // + 200 resting at 0.0037
        assertEquals(0, d("1365").compareTo(p.afterMinima));
        assertEquals(0, p.shortfall.signum());
        assertEquals(0, d("0.68").compareTo(p.vsBestPct));                 // 0.0037 vs best ask 0.003675
    }

    @Test public void shortfallIsReportedAgainstFreeNotWallet() {
        // 10 MxUSD in the wallet, 6 already committed to a live bid → only 4 free
        OrderPreview p = OrderPreview.of(true, d("0.004"), d("2000"), Collections.emptyList(), funds("0", "10", "0", "6"), null);
        assertEquals(0, d("8").compareTo(p.need));
        assertEquals(0, d("4").compareTo(p.free));
        assertEquals(0, d("4").compareTo(p.shortfall));
        assertNull(p.avgFill);
    }

    @Test public void sellNeedsMinimaAndReceivesAtBids() {
        OrderPreview p = OrderPreview.of(false, d("0.0035"), d("100"),
                Arrays.asList(new OrderPreview.Lvl(d("0.0036"), d("40")), new OrderPreview.Lvl(d("0.0034"), d("999"))),
                funds("65", "1", "0", "0"), d("0.0036"));
        assertEquals(0, d("40").compareTo(p.fillNow));                     // 0.0034 bid does not cross a 0.0035 ask
        assertEquals(0, d("60").compareTo(p.rest));
        assertEquals(0, d("100").compareTo(p.need));
        assertEquals(0, d("35").compareTo(p.shortfall));                   // 65 MINIMA free
        assertEquals(0, d("25").compareTo(p.afterMinima));
    }

    @Test public void portfolioValueAtMid() {
        Funds f = new Funds(d("65"), d("1.12"), BigDecimal.ZERO, BigDecimal.ZERO, d("1000"), BigDecimal.ZERO);
        assertEquals(0, d("1065").compareTo(f.minimaTotal()));
        assertEquals(0, d("5.02855").compareTo(f.valueAt(d("0.00367"))));   // 1.12 + 1065*0.00367
        assertNull(f.valueAt(null));
    }

    @Test public void balancesShowSixDecimalsTruncated() {
        assertEquals("1065.000207", Util.fmtBal(d("1065.00020733477800000000000000000000099989999449")));
        assertEquals("0.310061", Util.fmtBal(d("0.31006138")));       // truncated, not rounded to .310062
        assertEquals("1000", Util.fmtBal(d("1000")));
        assertEquals("0", Util.fmtBal(d("0.0000001")));
    }
}
