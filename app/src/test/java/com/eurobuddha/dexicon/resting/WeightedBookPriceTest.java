package com.eurobuddha.dexicon.resting;
/* Ported from apks/pandadex app/src/test/java/com/eurobuddha/pandadex/WeightedBookPriceTest.java (PandaDEX 0.4.23). */

import java.math.BigDecimal;
import org.junit.Test;
import static org.junit.Assert.*;
public class WeightedBookPriceTest {
    private static BigDecimal n(String value) { return new BigDecimal(value); }
    @Test public void equalSizesGiveTheUsersQuotedSpreadAverage() {
        assertEquals("0.004410", PriceMath.fmtPrice(PriceMath.weightedBookPrice(n("0.00439"),n("100"),n("0.00443"),n("100"))));
    }
    @Test public void moreBidSizeWeightsTowardBidNotTheOppositeSide() {
        assertEquals("0.004400", PriceMath.fmtPrice(PriceMath.weightedBookPrice(n("0.00439"),n("300"),n("0.00443"),n("100"))));
        assertEquals("0.004420", PriceMath.fmtPrice(PriceMath.weightedBookPrice(n("0.00439"),n("100"),n("0.00443"),n("300"))));
    }
    @Test public void combinedLimitAndPoolSizeCountsOnce() {
        BigDecimal bidSize=n("100").add(n("200")), askSize=n("60").add(n("40"));
        assertEquals(0,n("0.0044").compareTo(PriceMath.weightedBookPrice(n("0.00439"),bidSize,n("0.00443"),askSize)));
    }
    @Test public void oneSideUsesThatSideAndEmptyDepthStaysUnknown() {
        assertEquals(0,n("0.00443").compareTo(PriceMath.weightedBookPrice(null,null,n("0.00443"),n("10"))));
        assertEquals(0,n("0.00439").compareTo(PriceMath.weightedBookPrice(n("0.00439"),n("10"),null,null)));
        assertNull(PriceMath.weightedBookPrice(null,null,null,null));
        assertNull(PriceMath.weightedBookPrice(n("0"),n("100"),n("1"),n("0")));
    }
    @Test public void sizesDoNotLosePrecisionBeforeFinalDivision() {
        BigDecimal bid=n("0.004391234567"), ask=n("0.004439876543"), size=n("0.00000001");
        assertEquals(0,bid.add(ask).divide(n("2"),PriceMath.PRICE_DP,java.math.RoundingMode.HALF_UP)
                .compareTo(PriceMath.weightedBookPrice(bid,size,ask,size)));
    }
}
