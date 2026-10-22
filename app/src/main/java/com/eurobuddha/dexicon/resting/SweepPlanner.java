package com.eurobuddha.dexicon.resting;
/* Vendored from apks/pandadex app/src/main/java/com/eurobuddha/pandadex/SweepPlanner.java (PandaDEX 0.4.23).
 * Keep as close to verbatim as possible; only the package, the NodeApi/SignGate imports and the minimal Dexicon
 * glue noted inline differ. Per the family naming rule the Minima dollar token is MxUSD in identifiers too
 * (PandaDEX MXUSD_ID/MXUSD_DP/*Mxusd are MXUSD_ID/MXUSD_DP/*Mxusd here). Re-copy rather than hand-edit when PandaDEX changes. */


import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Plans a marketable-limit "sweep": which resting orders to take, best price first,
 * full-filling every order except (at most) the LAST, which may be partial — the V5
 * covenant's one-partial-per-txn shape. Pure math, JVM-tested.
 *
 * A partial take is trimmed so the remainder respects the maker's min-remainder floor
 * (port 8): if the desired take would leave a remainder below the floor, the take is
 * REDUCED to leave exactly the floor (never inflated past what the user asked).
 */
public final class SweepPlanner {

    /** Conservative cap on order coins per sweep txn (PandaPools capped routed legs at 6). */
    public static final int MAX_ORDERS = 5;
    /** Don't touch an order within this many blocks of expiry — it may cross the COINAGE
     *  threshold between planning and mining, which would reject the entire sweep. */
    public static final int EXPIRY_MARGIN = 30;

    public static final class Take {
        public final Order5 order;
        public final BigDecimal minima;   // MINIMA taken from this order
        public final boolean partial;

        Take(Order5 order, BigDecimal minima, boolean partial) {
            this.order = order;
            this.minima = minima;
            this.partial = partial;
        }
    }

    public static final class Plan {
        public final List<Take> takes = new ArrayList<>();
        public BigDecimal totalMinima = BigDecimal.ZERO;
        public BigDecimal totalMxusd = BigDecimal.ZERO;     // maker payments sum (taker cost / proceeds)

        public boolean isEmpty() { return takes.isEmpty(); }
    }

    private SweepPlanner() {}

    /**
     * @param book        live orders
     * @param takerBuys   true = taker wants to BUY MINIMA (consumes SELL orders)
     * @param wantMinima  MINIMA the taker wants to trade
     * @param limitPrice  worst acceptable price (MxUSD per MINIMA); null = pure market
     * @param chainBlock  for expiry filtering
     */
    public static Plan plan(Collection<Order5> book, boolean takerBuys, BigDecimal wantMinima,
                            BigDecimal limitPrice, long chainBlock) {
        List<Order5> side = new ArrayList<>();
        for (Order5 o : book) {
            if (o.sell != takerBuys) continue;            // taker buys → consume sells
            if (!o.fillable()) continue;                  // malformed/hostile order — never touch it
            // @COINAGE is evaluated when the txn MINES, not when we plan: an order close to
            // expiry takes the refund branch by then and takes the whole sweep down with it.
            if (o.age(chainBlock) > DexContract.EXPIRY_BLOCKS - EXPIRY_MARGIN) continue;
            if (limitPrice != null) {
                int cmp = o.price().compareTo(limitPrice);
                if (takerBuys ? cmp > 0 : cmp < 0) continue;   // worse than limit
            }
            side.add(o);
        }
        // best price first: buys want the CHEAPEST sells; sells want the HIGHEST bids
        side.sort((a, b) -> takerBuys ? a.price().compareTo(b.price())
                                      : b.price().compareTo(a.price()));

        Plan plan = new Plan();
        BigDecimal remaining = wantMinima;
        for (Order5 o : side) {
            if (plan.takes.size() >= MAX_ORDERS || remaining.signum() <= 0) break;
            BigDecimal avail = o.minimaAmount();
            if (remaining.compareTo(avail) >= 0) {
                add(plan, new Take(o, avail, false));
                remaining = remaining.subtract(avail);
                continue;
            }
            // last order: partial — respect the maker's min-remainder floor
            BigDecimal take = remaining;
            BigDecimal lockedTake = o.sell ? take
                    : PriceMath.up(take.multiply(o.price(), PriceMath.MC), PriceMath.MXUSD_DP);
            BigDecimal lockedRem = o.locked.subtract(lockedTake);
            if (lockedRem.compareTo(o.minRem) < 0) {
                // shrink the take so the remainder sits exactly at the floor
                BigDecimal allowedLockedTake = o.locked.subtract(o.minRem);
                if (allowedLockedTake.signum() <= 0) break;   // can't touch this order partially
                take = o.sell ? allowedLockedTake
                        : PriceMath.down(allowedLockedTake.divide(
                                o.price().signum() == 0 ? BigDecimal.ONE : o.price(),
                                PriceMath.MINIMA_DP, java.math.RoundingMode.DOWN), PriceMath.MINIMA_DP);
                if (take.signum() <= 0) break;
            }
            add(plan, new Take(o, take, true));
            remaining = remaining.subtract(take);
            break;                                        // only ONE partial per txn
        }
        return plan;
    }

    private static void add(Plan plan, Take t) {
        plan.takes.add(t);
        plan.totalMinima = plan.totalMinima.add(t.minima);
        // maker payment for this take (grain-ceiled — what the taker actually pays/receives)
        Order5 o = t.order;
        BigDecimal lockedTake = !t.partial ? o.locked
                : (o.sell ? t.minima
                          : PriceMath.up(t.minima.multiply(o.price(), PriceMath.MC), PriceMath.MXUSD_DP));
        BigDecimal pay = t.partial
                ? PriceMath.payFor(o.wantAmt, o.locked, lockedTake,
                        o.sell ? PriceMath.MXUSD_DP : PriceMath.MINIMA_DP)
                : o.wantAmt;
        // for sells the payment is MxUSD; for buys (taker selling) the payment is MINIMA —
        // totalMxusd tracks the MxUSD side either way
        plan.totalMxusd = plan.totalMxusd.add(o.sell ? pay : lockedTake);
    }

    /** Volume-weighted average price of the plan. */
    public static BigDecimal avgPrice(Plan p) {
        if (p.totalMinima.signum() == 0) return BigDecimal.ZERO;
        return p.totalMxusd.divide(p.totalMinima, PriceMath.PRICE_DP, java.math.RoundingMode.HALF_UP);
    }
}
