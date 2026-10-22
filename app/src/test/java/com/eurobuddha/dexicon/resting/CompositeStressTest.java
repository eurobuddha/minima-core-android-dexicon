package com.eurobuddha.dexicon.resting;
/* Ported from apks/pandadex app/src/test/java/com/eurobuddha/pandadex/CompositeStressTest.java (PandaDEX 0.4.23). */


import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/** Fixed-seed stress coverage for composite liquidity: broad enough to catch regressions,
 * deterministic enough to run on every release build. */
public class CompositeStressTest {

    private static final String PAYOUT =
            "0xBB11223344556677889900AABBCCDDEEFF00112233445566778899AABBCCDDEE";
    private static final String ME =
            "0xCC11223344556677889900AABBCCDDEEFF00112233445566778899AABBCCDDEE";

    @Test public void poolRoutesNeverOverClaimCapacityOrBreakInvariant() {
        Random rnd = new Random(31);
        for (int caseNo = 0; caseNo < 120; caseNo++) {
            List<Pool> pools = pools(rnd, 1 + rnd.nextInt(8), caseNo);
            BigDecimal reserves = VirtualCurve.totalMinima(pools);
            BigDecimal sellIn = reserves.multiply(new BigDecimal(5 + rnd.nextInt(300)))
                    .divide(new BigDecimal("10000"), PriceMath.MINIMA_DP, RoundingMode.DOWN);
            PoolRouter.Route sell = PoolRouter.route(pools, true, sellIn);
            if (sell.ok) assertPoolRoute(sell, true);

            BigDecimal buyOut = reserves.multiply(new BigDecimal(5 + rnd.nextInt(200)))
                    .divide(new BigDecimal("10000"), PriceMath.MINIMA_DP, RoundingMode.DOWN);
            PoolRouter.Route buy = PoolRouter.routeExactMinimaOut(pools, buyOut);
            if (buy.ok) {
                assertPoolRoute(buy, false);
                assertTrue("exact-output route must not underfill", buy.totalOut.compareTo(buyOut) >= 0);
            }

            PoolRouter.Route impossible = PoolRouter.routeExactMinimaOut(pools, reserves);
            assertFalse("cannot receive the whole aggregate reserve", impossible.ok);
            assertTrue(impossible.allocs.isEmpty());
        }
    }

    @Test public void compositePlansRespectLimitsCapsAndSourceEvidence() {
        Random rnd = new Random(77);
        for (int caseNo = 0; caseNo < 80; caseNo++) {
            boolean takerBuys = rnd.nextBoolean();
            List<Order5> book = orders(rnd, caseNo, takerBuys);
            List<Pool> pools = pools(rnd, 1 + rnd.nextInt(8), 1000 + caseNo);
            BigDecimal want = bd(5 + rnd.nextInt(220));
            BigDecimal limit = takerBuys ? new BigDecimal("0.06000000") : new BigDecimal("0.00000100");
            CompositeRouter.Plan p = CompositeRouter.plan(book, pools, takerBuys, want, limit, 200);

            assertTrue("capacity budget", p.capacityUnits() <= CompositeRouter.MAX_CAPACITY_UNITS);
            assertTrue("order cap", p.orderTakes.size() <= SweepPlanner.MAX_ORDERS);
            assertTrue("total + rest covers request", p.totalMinima.add(p.unfilledMinima).compareTo(want) >= 0);
            if (p.totalMinima.signum() > 0) {
                int cmp = p.effectivePrice.compareTo(limit);
                assertTrue("effective limit side", takerBuys ? cmp <= 0 : cmp >= 0);
                if (p.worstMarginalPrice.signum() > 0) {
                    int wcmp = p.worstMarginalPrice.compareTo(limit);
                    assertTrue("marginal limit side", takerBuys ? wcmp <= 0 : wcmp >= 0);
                }
            }
            assertOrderTakes(p, takerBuys, limit);
            assertSourceEvidence(p);
        }
    }

    @Test public void syntheticDepthStressNeverDisplaysMoreThanExecutableLiquidity() {
        Random rnd = new Random(91);
        for (int caseNo = 0; caseNo < 4; caseNo++) {
            boolean ask = rnd.nextBoolean();
            List<Pool> pools = pools(rnd, 1 + rnd.nextInt(6), 2000 + caseNo);
            List<SyntheticDepth.Row> rows = SyntheticDepth.sample(pools, ask, null, 10);
            BigDecimal cumulative = BigDecimal.ZERO;
            BigDecimal previous = null;
            for (SyntheticDepth.Row row : rows) {
                assertTrue(row.poolMinima.signum() > 0);
                if (previous != null) {
                    assertTrue(ask ? row.price.compareTo(previous) > 0
                            : row.price.compareTo(previous) < 0);
                }
                previous = row.price;
                cumulative = cumulative.add(row.poolMinima);
                CompositeRouter.Plan executable = CompositeRouter.plan(new ArrayList<>(), pools,
                        ask, cumulative, row.price, 200);
                assertTrue("displayed row must execute at displayed limit"
                                + " case=" + caseNo
                                + " side=" + (ask ? "ask" : "bid")
                                + " price=" + row.price
                                + " cumulative=" + cumulative
                                + " executable=" + executable.totalMinima,
                        executable.totalMinima.compareTo(cumulative) >= 0);
            }
        }
    }

    @Test public void compositeTransactionLayoutStressKeepsCovenantIndexing() {
        Random rnd = new Random(109);
        for (int caseNo = 0; caseNo < 90; caseNo++) {
            boolean takerBuys = rnd.nextBoolean();
            int poolCount = rnd.nextInt(4);
            int orderCount = Math.min(5, Math.max(0, CompositeRouter.MAX_CAPACITY_UNITS - 2 * poolCount));
            orderCount = rnd.nextInt(orderCount + 1);
            if (poolCount == 0 && orderCount == 0) orderCount = 1;

            CompositeRouter.Plan plan = manualPlan(rnd, caseNo, takerBuys, poolCount, orderCount);
            DexTxn.CompositePrep prep = DexTxn.prepareComposite(plan, takerBuys);
            DexTxn.CompositeBuild b = DexTxn.buildCompositeSteps("stress" + caseNo, ME, plan, prep,
                    takerBuys, funding(prep.payTok, prep.needed.add(BigDecimal.ONE)));

            int idx = 1;
            if (prep.route != null && prep.route.ok) {
                for (PoolRouter.Alloc a : prep.route.allocs) {
                    assertEquals("txninput id:stress" + caseNo + " coinid:" + a.pool.coinidM, b.steps.get(idx++));
                    assertEquals("txninput id:stress" + caseNo + " coinid:" + a.pool.coinidT, b.steps.get(idx++));
                }
            }
            for (SweepPlanner.Take t : plan.orderTakes)
                assertEquals("txninput id:stress" + caseNo + " coinid:" + t.order.coinid, b.steps.get(idx++));
            assertEquals("txninput id:stress" + caseNo + " coinid:0xFUND", b.steps.get(idx++));

            int outIdx = idx;
            int poolOutputs = 2 * plan.poolCount();
            for (int i = 0; i < poolOutputs; i++) assertTrue(b.steps.get(outIdx + i).startsWith("txnoutput "));
            outIdx += poolOutputs;
            for (int i = 0; i < prep.payments.size(); i++) assertTrue(b.steps.get(outIdx + i).startsWith("txnoutput "));
            outIdx += prep.payments.size();
            if (prep.partial != null) {
                assertTrue("partial remainder immediately after its maker payment",
                        b.steps.get(outIdx).contains("address:" + DexContract.ADDR_V5)
                                && b.steps.get(outIdx).contains("storestate:true"));
                outIdx++;
            }
            assertTrue("aggregate taker proceeds after covenant outputs",
                    b.steps.get(outIdx).contains("address:" + ME));
            assertTrue(b.steps.contains("txnsign id:stress" + caseNo + " publickey:auto"));
            assertTrue(b.steps.contains("txnbasics id:stress" + caseNo));
            assertTrue("capacity budget", plan.capacityUnits() <= CompositeRouter.MAX_CAPACITY_UNITS);
        }
    }

    private static void assertPoolRoute(PoolRouter.Route r, boolean minimaToToken) {
        assertTrue(r.poolsUsed <= PoolRouter.MAX_POOLS);
        assertEquals(r.poolsUsed, r.allocs.size());
        BigDecimal in = BigDecimal.ZERO;
        BigDecimal out = BigDecimal.ZERO;
        Set<String> pools = new HashSet<>();
        for (PoolRouter.Alloc a : r.allocs) {
            assertNotNull(a.pool.coinidM);
            assertNotNull(a.pool.coinidT);
            assertTrue("pool used once", pools.add(a.pool.address));
            assertTrue(a.quote.ok);
            BigDecimal k0 = a.pool.reserveM.multiply(a.pool.reserveT)
                    .max(Util.decOr(a.pool.kmin, BigDecimal.ZERO));
            BigDecimal k1 = a.quote.newX.multiply(a.quote.newY);
            assertTrue("pool invariant", k1.compareTo(k0.multiply(new BigDecimal("0.999"))) >= 0);
            in = in.add(a.quote.inAmount);
            out = out.add(a.quote.outAmount);
        }
        assertEquals(0, in.compareTo(r.totalIn));
        assertEquals(0, out.compareTo(r.totalOut));
        assertTrue(minimaToToken ? r.effPrice.signum() > 0 : r.effPrice.signum() > 0);
    }

    private static void assertOrderTakes(CompositeRouter.Plan p, boolean takerBuys, BigDecimal limit) {
        boolean seenPartial = false;
        BigDecimal orderMinima = BigDecimal.ZERO;
        for (int i = 0; i < p.orderTakes.size(); i++) {
            SweepPlanner.Take t = p.orderTakes.get(i);
            if (seenPartial) assertTrue("partial order must be final", false);
            if (t.partial) seenPartial = true;
            assertEquals(takerBuys, t.order.sell);
            int cmp = t.order.price().compareTo(limit);
            assertTrue("order respects limit", takerBuys ? cmp <= 0 : cmp >= 0);
            orderMinima = orderMinima.add(t.minima);
            if (t.partial) assertEquals(i, p.orderTakes.size() - 1);
        }
        assertEquals(0, orderMinima.compareTo(p.orderMinima));
    }

    private static void assertSourceEvidence(CompositeRouter.Plan p) {
        Set<String> expected = new HashSet<>();
        for (SweepPlanner.Take t : p.orderTakes) expected.add(t.order.coinid);
        if (p.poolRoute != null && p.poolRoute.ok) {
            for (PoolRouter.Alloc a : p.poolRoute.allocs) {
                expected.add(a.pool.coinidM);
                expected.add(a.pool.coinidT);
            }
        }
        assertEquals(expected, new HashSet<>(p.sourceCoinIds));
    }

    private static CompositeRouter.Plan manualPlan(Random rnd, int caseNo, boolean takerBuys,
                                                   int poolCount, int orderCount) {
        CompositeRouter.Plan plan = new CompositeRouter.Plan();
        if (poolCount > 0) {
            List<Pool> ps = pools(rnd, poolCount, 3000 + caseNo);
            BigDecimal amount = bd(1 + rnd.nextInt(20));
            plan.poolRoute = takerBuys ? PoolRouter.routeExactMinimaOut(ps, amount)
                    : PoolRouter.route(ps, true, amount);
            if (plan.poolRoute != null && plan.poolRoute.ok) {
                plan.poolMinima = takerBuys ? plan.poolRoute.totalOut : plan.poolRoute.totalIn;
                plan.poolMxusd = takerBuys ? plan.poolRoute.totalIn : plan.poolRoute.totalOut;
            }
        }
        List<Order5> os = orders(rnd, 4000 + caseNo, takerBuys);
        for (int i = 0; i < orderCount && i < os.size(); i++) {
            Order5 o = os.get(i);
            BigDecimal take = i == orderCount - 1 && rnd.nextBoolean()
                    ? o.minimaAmount().divide(new BigDecimal("2"), PriceMath.MINIMA_DP, RoundingMode.DOWN)
                    : o.minimaAmount();
            boolean partial = take.compareTo(o.minimaAmount()) < 0;
            plan.orderTakes.add(new SweepPlanner.Take(o, take, partial));
            plan.orderMinima = plan.orderMinima.add(take);
            plan.orderMxusd = plan.orderMxusd.add(take.multiply(o.price()));
        }
        plan.totalMinima = plan.poolMinima.add(plan.orderMinima);
        plan.totalMxusd = plan.poolMxusd.add(plan.orderMxusd);
        return plan;
    }

    private static List<Pool> pools(Random rnd, int n, int offset) {
        List<Pool> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            BigDecimal reserveM = bd(400 + rnd.nextInt(4000));
            BigDecimal price = new BigDecimal(3 + rnd.nextInt(35))
                    .divide(new BigDecimal("1000"), PriceMath.PRICE_DP, RoundingMode.DOWN);
            BigDecimal reserveT = reserveM.multiply(price).setScale(8, RoundingMode.DOWN);
            Pool p = new Pool();
            p.reserveM = reserveM;
            p.reserveT = reserveT;
            p.tokDecimals = 8;
            p.kmin = "1";
            p.tok = DexContract.MXUSD_ID;
            p.address = "0xP" + offset + "_" + i;
            p.oadr = PAYOUT;
            p.opk = "0xAA";
            p.coinidM = p.address + "_M";
            p.coinidT = p.address + "_T";
            out.add(p);
        }
        return out;
    }

    private static List<Order5> orders(Random rnd, int offset, boolean takerBuys) {
        List<Order5> out = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            BigDecimal minima = bd(2 + rnd.nextInt(60));
            BigDecimal price = new BigDecimal(4 + rnd.nextInt(40))
                    .divide(new BigDecimal("1000"), PriceMath.PRICE_DP, RoundingMode.DOWN);
            Order5 candidate = order(String.format("0x%08X%02X", offset, i), takerBuys, minima, price);
            assertNotNull(candidate);
            assertTrue(candidate.fillable());
            out.add(candidate);
        }
        return out;
    }

    private static Order5 order(String coinid, boolean sell, BigDecimal minima, BigDecimal price) {
        try {
            JSONObject c = new JSONObject();
            c.put("coinid", coinid);
            c.put("tokenid", sell ? Util.MINIMA_TOKENID : DexContract.MXUSD_ID);
            BigDecimal locked = sell ? minima : minima.multiply(price).setScale(8, RoundingMode.UP);
            c.put("amount", locked.toPlainString());
            c.put("tokenamount", locked.toPlainString());
            c.put("created", 100);
            JSONObject st = new JSONObject();
            st.put("0", "0xAA");
            st.put("1", PAYOUT);
            st.put("2", sell ? locked.multiply(price).toPlainString() : minima.toPlainString());
            st.put("3", sell ? DexContract.MXUSD_ID : Util.MINIMA_TOKENID);
            st.put("4", coinid);
            st.put("5", sell ? "1" : "0");
            st.put("7", "1");
            st.put("8", sell ? "1" : "0.0001");
            c.put("state", st);
            return Order5.from(c);
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    private static List<JSONObject> funding(String tokenid, BigDecimal amount) {
        try {
            JSONObject c = new JSONObject();
            c.put("coinid", "0xFUND");
            c.put("amount", amount.toPlainString());
            c.put("tokenamount", amount.toPlainString());
            c.put("tokenid", tokenid);
            c.put("address", ME);
            List<JSONObject> out = new ArrayList<>();
            out.add(c);
            return out;
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    private static BigDecimal bd(int v) {
        return new BigDecimal(v).setScale(PriceMath.MINIMA_DP, RoundingMode.DOWN);
    }
}
