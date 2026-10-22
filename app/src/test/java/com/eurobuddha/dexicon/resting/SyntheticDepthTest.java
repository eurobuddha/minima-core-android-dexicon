package com.eurobuddha.dexicon.resting;
/* Ported from apks/pandadex app/src/test/java/com/eurobuddha/pandadex/SyntheticDepthTest.java (PandaDEX 0.4.23). */


import com.eurobuddha.dexicon.TradeView;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;

public class SyntheticDepthTest {

    private static Pool pool() {
        Pool p = new Pool();
        p.reserveM = new BigDecimal("1000");
        p.reserveT = new BigDecimal("10");
        p.tokDecimals = 8;
        p.kmin = "1";
        p.tok = DexContract.MXUSD_ID;
        p.address = "0xPOOL";
        p.coinidM = "0xM";
        p.coinidT = "0xT";
        return p;
    }

    @Test public void askPricesIncreaseAndRowsArePoolLabelled() {
        List<SyntheticDepth.Row> rows = SyntheticDepth.sample(Arrays.asList(pool()), true,
                new BigDecimal("0.001"), 5);
        assertTrue(rows.size() > 0);
        for (int i = 1; i < rows.size(); i++) {
            assertTrue(rows.get(i).price.compareTo(rows.get(i - 1).price) > 0);
        }
        for (SyntheticDepth.Row r : rows) assertTrue(r.poolMinima.signum() > 0);
    }

    @Test public void bidPricesDecreaseAndRowsArePoolLabelled() {
        List<SyntheticDepth.Row> rows = SyntheticDepth.sample(Arrays.asList(pool()), false,
                new BigDecimal("0.001"), 5);
        assertTrue(rows.size() > 0);
        for (int i = 1; i < rows.size(); i++) {
            assertTrue(rows.get(i).price.compareTo(rows.get(i - 1).price) < 0);
        }
        for (SyntheticDepth.Row r : rows) assertTrue(r.poolMinima.signum() > 0);
    }

    @Test public void ignoresNonMxusdPools() {
        Pool p = pool();
        p.tok = "0xOTHER";
        assertEquals(0, SyntheticDepth.sample(Arrays.asList(p), true, null, 5).size());
    }

    @Test public void nullTickFallsBackToFiveDecimalDisplayResolution() {
        List<SyntheticDepth.Row> rows = SyntheticDepth.sample(Arrays.asList(pool()), true, null, 10);
        assertTrue(rows.size() > 0);
        for (SyntheticDepth.Row row : rows) {
            BigDecimal tick = new BigDecimal("0.00001");
            assertEquals(0, row.price.remainder(tick).compareTo(BigDecimal.ZERO));
        }
    }

    @Test public void displayedAskDepthDoesNotExceedExecutableLimitDepth() {
        Pool p = pool();
        List<SyntheticDepth.Row> rows = SyntheticDepth.sample(Arrays.asList(p), true,
                new BigDecimal("0.00001"), 10);
        BigDecimal cumulative = BigDecimal.ZERO;
        for (SyntheticDepth.Row row : rows) {
            cumulative = cumulative.add(row.poolMinima);
            CompositeRouter.Plan executable = CompositeRouter.plan(new java.util.ArrayList<>(),
                    Arrays.asList(p), true, cumulative, row.price, 200);
            assertTrue("displayed pool ask must be executable at its displayed limit"
                            + " price=" + row.price + " cumulative=" + cumulative
                            + " executable=" + executable.totalMinima,
                    executable.totalMinima.compareTo(cumulative) >= 0);
        }
    }

    @Test public void fragmentedAskDepthDoesNotExceedExecutableLimitDepth() {
        Pool a = pool();
        Pool b = pool();
        b.address = "0xPOOL2";
        b.coinidM = "0xM2";
        b.coinidT = "0xT2";
        b.reserveM = new BigDecimal("2500");
        b.reserveT = new BigDecimal("10");
        List<Pool> pools = Arrays.asList(a, b);
        List<SyntheticDepth.Row> rows = SyntheticDepth.sample(pools, true,
                new BigDecimal("0.00001"), 10);
        BigDecimal cumulative = BigDecimal.ZERO;
        for (SyntheticDepth.Row row : rows) {
            cumulative = cumulative.add(row.poolMinima);
            CompositeRouter.Plan executable = CompositeRouter.plan(new java.util.ArrayList<>(),
                    pools, true, cumulative, row.price, 200);
            assertTrue("fragmented pool ask must be executable at displayed limit"
                            + " price=" + row.price + " cumulative=" + cumulative
                            + " executable=" + executable.totalMinima,
                    executable.totalMinima.compareTo(cumulative) >= 0);
        }
    }

    @Test public void amountTextOmitsZeroBookLabelForPoolOnlyRows() {
        assertEquals("POOL 12.34", TradeView.amountText(BigDecimal.ZERO, new BigDecimal("12.34")));
        assertEquals("5", TradeView.amountText(new BigDecimal("5"), BigDecimal.ZERO));   // BOOK sizes exact (owner 2026-10-22: never a rounded size)
    }

    @Test public void amountTextTruncatesPoolBandsButShowsOrdersExact() {
        assertEquals("POOL 12.34", TradeView.amountText(BigDecimal.ZERO, new BigDecimal("12.349")));
        assertEquals("FAST 455.78231293", TradeView.amountText(new BigDecimal("455.78231293"), BigDecimal.ZERO, BigDecimal.ZERO));
        assertEquals("BOOK 5.679\nPOOL 1.23",
                TradeView.amountText(new BigDecimal("5.679"), new BigDecimal("1.239")));
    }
}
