package com.eurobuddha.dexicon.resting;
/* Vendored from apks/pandadex app/src/main/java/com/eurobuddha/pandadex/CompositeRouter.java (PandaDEX 0.4.23).
 * Keep as close to verbatim as possible; only the package, the NodeApi/SignGate imports and the minimal Dexicon
 * glue noted inline differ. Per the family naming rule the Minima dollar token is MxUSD in identifiers too
 * (PandaDEX MXUSD_ID/MXUSD_DP/*Mxusd are MXUSD_ID/MXUSD_DP/*Mxusd here). Re-copy rather than hand-edit when PandaDEX changes. */


import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Best-price blended router across V5 order coins and PandaPools reserves. */
public final class CompositeRouter {

    private static final int SLICES = 128;
    public static final int MAX_CAPACITY_UNITS = 12;
    private static final MathContext MC = new MathContext(40, RoundingMode.DOWN);

    public static final class Plan {
        public final List<SweepPlanner.Take> orderTakes = new ArrayList<>();
        public PoolRouter.Route poolRoute;
        public BigDecimal totalMinima = BigDecimal.ZERO;
        public BigDecimal totalMxusd = BigDecimal.ZERO;
        public BigDecimal effectivePrice = BigDecimal.ZERO;
        public BigDecimal orderMinima = BigDecimal.ZERO;
        public BigDecimal orderMxusd = BigDecimal.ZERO;
        public BigDecimal poolMinima = BigDecimal.ZERO;
        public BigDecimal poolMxusd = BigDecimal.ZERO;
        public BigDecimal unfilledMinima = BigDecimal.ZERO;
        public BigDecimal worstMarginalPrice = BigDecimal.ZERO;
        public final Set<String> sourceCoinIds = new LinkedHashSet<>();

        public boolean isEmpty() { return orderTakes.isEmpty() && (poolRoute == null || !poolRoute.ok); }
        public int poolCount() { return poolRoute == null ? 0 : poolRoute.poolsUsed; }
        public int capacityUnits() { return 2 * poolCount() + orderTakes.size(); }
    }

    private CompositeRouter() {}

    public static Plan plan(Collection<Order5> book, List<Pool> pools, boolean takerBuys,
                            BigDecimal wantMinima, BigDecimal limitPrice, long chainBlock) {
        return planInternal(book, filterPair(pools), takerBuys, wantMinima, limitPrice, chainBlock);
    }

    private static Plan planInternal(Collection<Order5> book, List<Pool> pools, boolean takerBuys,
                                     BigDecimal wantMinima, BigDecimal limitPrice, long chainBlock) {
        Plan p = routeOnce(book, pools, takerBuys, wantMinima, limitPrice, chainBlock);
        while (p.capacityUnits() > MAX_CAPACITY_UNITS && p.poolRoute != null && p.poolRoute.poolsUsed > 0) {
            Pool drop = smallestPool(p.poolRoute, takerBuys);
            if (drop == null) break;
            List<Pool> reduced = new ArrayList<>();
            for (Pool pool : pools) if (pool != drop) reduced.add(pool);
            if (reduced.size() == pools.size()) break;
            pools = reduced;
            p = routeOnce(book, pools, takerBuys, wantMinima, limitPrice, chainBlock);
        }
        return p;
    }

    private static Plan routeOnce(Collection<Order5> book, List<Pool> pools, boolean takerBuys,
                                  BigDecimal wantMinima, BigDecimal limitPrice, long chainBlock) {
        Plan plan = new Plan();
        if (wantMinima == null || wantMinima.signum() <= 0) return plan;
        List<Order5> orders = orders(book, takerBuys, limitPrice, chainBlock);
        BigDecimal chunk = wantMinima.divide(new BigDecimal(SLICES), PriceMath.MINIMA_DP, RoundingMode.UP);
        BigDecimal remaining = wantMinima;
        BigDecimal poolTarget = BigDecimal.ZERO;
        PoolRouter.Route currentRoute = null;
        int oi = 0;
        BigDecimal takenFromOrder = BigDecimal.ZERO;

        while (remaining.signum() > 0) {
            BigDecimal step = remaining.min(chunk);
            OrderChoice oc = orderChoice(orders, oi, takenFromOrder, step, remaining, takerBuys);
            BigDecimal orderPrice = oc == null ? null : oc.mxusd.divide(oc.minima, MC);
            PoolChoice pc = poolChoice(pools, takerBuys, poolTarget, step, currentRoute);
            BigDecimal poolPrice = pc == null ? null : pc.marginalMxusd.divide(pc.marginalMinima, MC);
            if (poolPrice != null && limitPrice != null) {
                int cmp = poolPrice.compareTo(limitPrice);
                if (takerBuys ? cmp > 0 : cmp < 0) pc = null;
            }
            boolean usePool = better(takerBuys, orderPrice, poolPrice);
            if (usePool && pc != null) {
                poolTarget = poolTarget.add(pc.marginalMinima);
                currentRoute = pc.route;
                plan.worstMarginalPrice = worst(plan.worstMarginalPrice, poolPrice, takerBuys);
                remaining = remaining.subtract(pc.marginalMinima);
                continue;
            }
            if (oc == null) break;
            takenFromOrder = takenFromOrder.add(oc.minima);
            Order5 cur = orders.get(oi);
            BigDecimal avail = cur.minimaAmount();
            if (takenFromOrder.compareTo(avail) >= 0) {
                addOrder(plan, cur, avail, false);
                oi++;
                takenFromOrder = BigDecimal.ZERO;
            }
            plan.worstMarginalPrice = worst(plan.worstMarginalPrice, orderPrice, takerBuys);
            remaining = remaining.subtract(oc.minima);
        }
        if (takenFromOrder.signum() > 0 && oi < orders.size()) addOrder(plan, orders.get(oi), takenFromOrder, true);
        if (currentRoute != null && currentRoute.ok) {
            plan.poolRoute = currentRoute;
            plan.poolMinima = takerBuys ? currentRoute.totalOut : currentRoute.totalIn;
            plan.poolMxusd = takerBuys ? currentRoute.totalIn : currentRoute.totalOut;
            for (PoolRouter.Alloc a : currentRoute.allocs) {
                plan.sourceCoinIds.add(a.pool.coinidM);
                plan.sourceCoinIds.add(a.pool.coinidT);
            }
        }
        plan.totalMinima = plan.orderMinima.add(plan.poolMinima);
        plan.totalMxusd = plan.orderMxusd.add(plan.poolMxusd);
        plan.unfilledMinima = wantMinima.subtract(plan.totalMinima).max(BigDecimal.ZERO);
        if (plan.totalMinima.signum() > 0)
            plan.effectivePrice = plan.totalMxusd.divide(plan.totalMinima, PriceMath.PRICE_DP, RoundingMode.HALF_UP);
        return plan;
    }

    private static boolean better(boolean takerBuys, BigDecimal orderPrice, BigDecimal poolPrice) {
        if (poolPrice == null) return false;
        if (orderPrice == null) return true;
        return takerBuys ? poolPrice.compareTo(orderPrice) < 0 : poolPrice.compareTo(orderPrice) > 0;
    }

    private static BigDecimal worst(BigDecimal cur, BigDecimal px, boolean takerBuys) {
        if (px == null) return cur;
        if (cur == null || cur.signum() == 0) return px;
        return takerBuys ? cur.max(px) : cur.min(px);
    }

    private static final class OrderChoice {
        BigDecimal minima, mxusd;
    }

    private static OrderChoice orderChoice(List<Order5> orders, int idx, BigDecimal already,
                                           BigDecimal step, BigDecimal requestRemaining, boolean takerBuys) {
        if (idx >= orders.size()) return null;
        Order5 o = orders.get(idx);
        BigDecimal avail = o.minimaAmount();
        BigDecimal remAvail = avail.subtract(already);
        if (remAvail.signum() <= 0) return null;
        BigDecimal take = requestRemaining.compareTo(remAvail) >= 0 ? remAvail : remAvail.min(step);
        BigDecimal left = remAvail.subtract(take);
        if (left.signum() > 0) {
            BigDecimal lockedLeft = o.sell ? left : PriceMath.up(left.multiply(o.price(), MC), PriceMath.MXUSD_DP);
            if (lockedLeft.compareTo(o.minRem) < 0) {
                BigDecimal maxPartialLocked = o.locked.subtract(o.minRem);
                BigDecimal maxPartialMinima = o.sell ? maxPartialLocked
                        : PriceMath.down(maxPartialLocked.divide(o.price(), PriceMath.MINIMA_DP, RoundingMode.DOWN), PriceMath.MINIMA_DP);
                take = maxPartialMinima.subtract(already);
                if (take.signum() <= 0) return null;
            }
        }
        OrderChoice c = new OrderChoice();
        c.minima = take;
        c.mxusd = PriceMath.up(take.multiply(o.price(), MC), PriceMath.MXUSD_DP);
        return c;
    }

    private static void addOrder(Plan p, Order5 o, BigDecimal take, boolean partial) {
        SweepPlanner.Take t = new SweepPlanner.Take(o, take, partial);
        p.orderTakes.add(t);
        p.orderMinima = p.orderMinima.add(take);
        BigDecimal lockedTake = !partial ? o.locked
                : (o.sell ? take : PriceMath.up(take.multiply(o.price(), MC), PriceMath.MXUSD_DP));
        BigDecimal pay = partial
                ? PriceMath.payFor(o.wantAmt, o.locked, lockedTake, o.sell ? PriceMath.MXUSD_DP : PriceMath.MINIMA_DP)
                : o.wantAmt;
        p.orderMxusd = p.orderMxusd.add(o.sell ? pay : lockedTake);
        p.sourceCoinIds.add(o.coinid);
    }

    private static final class PoolChoice {
        PoolRouter.Route route;
        BigDecimal marginalMinima, marginalMxusd;
    }

    private static PoolChoice poolChoice(List<Pool> pools, boolean takerBuys, BigDecimal currentMinima,
                                         BigDecimal step, PoolRouter.Route before) {
        if (pools.isEmpty()) return null;
        PoolRouter.Route after = takerBuys ? PoolRouter.routeExactMinimaOut(pools, currentMinima.add(step))
                : PoolRouter.route(pools, true, currentMinima.add(step));
        if (after == null || !after.ok) return null;
        PoolChoice c = new PoolChoice();
        c.route = after;
        if (takerBuys) {
            c.marginalMinima = after.totalOut.subtract(before != null && before.ok ? before.totalOut : BigDecimal.ZERO);
            c.marginalMxusd = after.totalIn.subtract(before != null && before.ok ? before.totalIn : BigDecimal.ZERO);
        } else {
            c.marginalMinima = after.totalIn.subtract(before != null && before.ok ? before.totalIn : BigDecimal.ZERO);
            c.marginalMxusd = after.totalOut.subtract(before != null && before.ok ? before.totalOut : BigDecimal.ZERO);
        }
        if (c.marginalMinima.signum() <= 0 || c.marginalMxusd.signum() <= 0) return null;
        return c;
    }

    private static List<Order5> orders(Collection<Order5> book, boolean takerBuys, BigDecimal limitPrice, long chainBlock) {
        List<Order5> side = new ArrayList<>();
        if (book != null) for (Order5 o : book) {
            if (o.sell != takerBuys) continue;
            if (!o.fillable()) continue;
            if (o.age(chainBlock) > DexContract.EXPIRY_BLOCKS - SweepPlanner.EXPIRY_MARGIN) continue;
            if (limitPrice != null) {
                int cmp = o.price().compareTo(limitPrice);
                if (takerBuys ? cmp > 0 : cmp < 0) continue;
            }
            side.add(o);
        }
        side.sort((a, b) -> takerBuys ? a.price().compareTo(b.price()) : b.price().compareTo(a.price()));
        if (side.size() > SweepPlanner.MAX_ORDERS) return new ArrayList<>(side.subList(0, SweepPlanner.MAX_ORDERS));
        return side;
    }

    private static List<Pool> filterPair(List<Pool> pools) {
        List<Pool> out = new ArrayList<>();
        if (pools != null) for (Pool p : pools)
            if (p.funded() && DexContract.MXUSD_ID.equalsIgnoreCase(p.tok)) out.add(p);
        return out;
    }

    private static Pool smallestPool(PoolRouter.Route route, boolean takerBuys) {
        PoolRouter.Alloc best = null;
        for (PoolRouter.Alloc a : route.allocs)
            if (best == null || poolMinima(a, takerBuys).compareTo(poolMinima(best, takerBuys)) < 0) best = a;
        return best == null ? null : best.pool;
    }

    private static BigDecimal poolMinima(PoolRouter.Alloc a, boolean takerBuys) {
        return takerBuys ? a.quote.outAmount : a.quote.inAmount;
    }
}
