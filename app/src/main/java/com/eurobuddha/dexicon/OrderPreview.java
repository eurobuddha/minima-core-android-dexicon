package com.eurobuddha.dexicon;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.List;

/**
 * Live preview of an order while it is typed (owner, 2026-10-22: "this is a money exchange, more information is better
 * than less"). Pure: the ladder's opposite side, the funds and the inputs in, numbers out — so it is unit-tested.
 * The fill is an ESTIMATE from the displayed book (the confirm dialog quotes the exact route); everything not crossing
 * the book rests as the user's FAST order.
 */
public final class OrderPreview {
    /** One opposite-side level at its exact price, best first. */
    public static final class Lvl {
        final BigDecimal price, size;
        public Lvl(BigDecimal price, BigDecimal size) { this.price = price; this.size = size; }
    }

    private static final MathContext MC = new MathContext(64);

    public BigDecimal fillNow = BigDecimal.ZERO, fillCost = BigDecimal.ZERO, rest = BigDecimal.ZERO;
    public BigDecimal avgFill;           // null when nothing crosses
    public BigDecimal need;              // what the order commits on its paying side (buy: MxUSD, sell: MINIMA)
    public BigDecimal free;              // what is free to commit on that side
    public BigDecimal shortfall = BigDecimal.ZERO;
    public BigDecimal afterMinima, afterMxusd;   // available balances right after the immediate fill
    public BigDecimal vsBestPct, vsMidPct;       // the limit price against the best opposite price and the mid
    public BigDecimal best;

    public static OrderPreview of(boolean buy, BigDecimal price, BigDecimal amount, List<Lvl> opposite, Funds f, BigDecimal mid) {
        OrderPreview p = new OrderPreview();
        BigDecimal left = amount;
        for (Lvl l : opposite) {
            if (left.signum() <= 0) break;
            boolean crosses = buy ? l.price.compareTo(price) <= 0 : l.price.compareTo(price) >= 0;
            if (!crosses) break;
            BigDecimal take = left.min(l.size);
            p.fillNow = p.fillNow.add(take);
            p.fillCost = p.fillCost.add(take.multiply(l.price, MC));
            left = left.subtract(take);
        }
        p.rest = left.max(BigDecimal.ZERO);
        if (p.fillNow.signum() > 0) p.avgFill = p.fillCost.divide(p.fillNow, 8, RoundingMode.HALF_UP);
        BigDecimal restCost = p.rest.multiply(price, MC);
        if (buy) {
            p.need = p.fillCost.add(restCost).setScale(8, RoundingMode.UP);
            p.afterMinima = f.minimaAvailable.add(p.fillNow);
            p.afterMxusd = f.mxusdAvailable.subtract(p.fillCost).setScale(8, RoundingMode.UP);
        } else {
            p.need = amount;
            p.afterMinima = f.minimaAvailable.subtract(p.fillNow);
            p.afterMxusd = f.mxusdAvailable.add(p.fillCost).setScale(8, RoundingMode.DOWN);
        }
        p.free = f.freeFor(buy);
        BigDecimal gap = p.need.subtract(p.free);
        p.shortfall = gap.signum() > 0 ? gap : BigDecimal.ZERO;
        p.best = opposite.isEmpty() ? null : opposite.get(0).price;
        if (p.best != null && p.best.signum() > 0) p.vsBestPct = pct(price, p.best);
        if (mid != null && mid.signum() > 0) p.vsMidPct = pct(price, mid);
        return p;
    }

    private static BigDecimal pct(BigDecimal a, BigDecimal b) {
        return a.subtract(b).multiply(BigDecimal.valueOf(100)).divide(b, 2, RoundingMode.HALF_UP);
    }
}
