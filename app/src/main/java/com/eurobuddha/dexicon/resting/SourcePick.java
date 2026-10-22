package com.eurobuddha.dexicon.resting;

import java.math.BigDecimal;

/**
 * Dexicon glue (not vendored): which source takes the FIRST level of a crossing order.
 *
 * FAST (a signed relay order, filled by a two-party co-signed txn) and BOOK/POOL (V5 covenant orders and PandaPools
 * reserves, filled by one atomic PandaDEX composite txn) cannot share a transaction, so a take goes to ONE source.
 * The fair comparison is price for the same quantity: the FAST level offers {@code fastQty} at exactly
 * {@code fastPrice}; the composite router is asked for that same quantity, and wins only if it fills ALL of it at a
 * strictly better effective price (lower for a buy, higher for a sell). Ties go to FAST (no pool fee, no on-chain
 * slippage, maker co-signs).
 * ponytail: single-source routing; a mixed FAST+BOOK+POOL sweep needs two txns and a partial-failure story.
 */
public final class SourcePick {
    private SourcePick() {}

    public static boolean restingFirst(CompositeRouter.Plan head, BigDecimal fastQty, BigDecimal fastPrice, boolean buy) {
        if (head == null || head.isEmpty()) return false;
        if (fastPrice == null) return true;
        if (head.totalMinima.compareTo(fastQty) < 0) return false;
        int cmp = head.effectivePrice.compareTo(fastPrice);
        return buy ? cmp < 0 : cmp > 0;
    }
}
