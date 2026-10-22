package com.eurobuddha.dexicon.resting;
/* Ported from apks/pandadex app/src/test/java/com/eurobuddha/pandadex/DexTxnCompositeLayoutTest.java (PandaDEX 0.4.23). */


import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import java.math.BigDecimal;
import java.util.Arrays;

public class DexTxnCompositeLayoutTest {

    private static final String PAYOUT =
            "0xBB11223344556677889900AABBCCDDEEFF00112233445566778899AABBCCDDEE";
    private static final String ME =
            "0xCC11223344556677889900AABBCCDDEEFF00112233445566778899AABBCCDDEE";

    private static Order5 sell(String coinid, String minima, String price) {
        try {
            BigDecimal m = new BigDecimal(minima);
            BigDecimal p = new BigDecimal(price);
            JSONObject c = new JSONObject();
            c.put("coinid", coinid);
            c.put("amount", minima);
            c.put("tokenid", Util.MINIMA_TOKENID);
            c.put("created", 100);
            JSONObject st = new JSONObject();
            st.put("0", "0xAA");
            st.put("1", PAYOUT);
            st.put("2", m.multiply(p).toPlainString());
            st.put("3", DexContract.MXUSD_ID);
            st.put("4", "0x" + coinid.substring(2));
            st.put("5", "1");
            st.put("7", "1");
            st.put("8", "1");
            c.put("state", st);
            return Order5.from(c);
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    private static Pool pool() {
        Pool p = new Pool();
        p.reserveM = new BigDecimal("1000");
        p.reserveT = new BigDecimal("10");
        p.tokDecimals = 8;
        p.kmin = "1";
        p.tok = DexContract.MXUSD_ID;
        p.address = "0x1111111111111111111111111111111111111111111111111111111111111111";
        p.oadr = PAYOUT;
        p.opk = "0xOPK";
        p.coinidM = "0xPOOLM";
        p.coinidT = "0xPOOLT";
        return p;
    }

    private static JSONObject fund(String coinid, String amount, String tokenid) {
        try {
            JSONObject c = new JSONObject();
            c.put("coinid", coinid);
            c.put("amount", amount);
            c.put("tokenamount", amount);
            c.put("tokenid", tokenid);
            c.put("address", ME);
            return c;
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    @Test public void compositeBuyLayoutKeepsPoolParityAndShiftedOrderOutputs() {
        Order5 full = sell("0xC1", "5", "0.01");
        Order5 part = sell("0xC2", "10", "0.01");
        PoolRouter.Route route = PoolRouter.routeExactMinimaOut(Arrays.asList(pool()), new BigDecimal("2"));
        CompositeRouter.Plan plan = new CompositeRouter.Plan();
        plan.poolRoute = route;
        plan.poolMinima = route.totalOut;
        plan.poolMxusd = route.totalIn;
        plan.orderTakes.add(new SweepPlanner.Take(full, new BigDecimal("5"), false));
        plan.orderTakes.add(new SweepPlanner.Take(part, new BigDecimal("4"), true));
        plan.orderMinima = new BigDecimal("9");
        plan.orderMxusd = new BigDecimal("0.09");
        plan.totalMinima = plan.poolMinima.add(plan.orderMinima);
        plan.totalMxusd = plan.poolMxusd.add(plan.orderMxusd);

        DexTxn.CompositePrep prep = DexTxn.prepareComposite(plan, true);
        DexTxn.CompositeBuild b = DexTxn.buildCompositeSteps("tx", ME, plan, prep, true,
                Arrays.asList(fund("0xFUND", "1", DexContract.MXUSD_ID)));

        assertEquals("txninput id:tx coinid:0xPOOLM", b.steps.get(1));
        assertEquals("txninput id:tx coinid:0xPOOLT", b.steps.get(2));
        assertEquals("txninput id:tx coinid:0xC1", b.steps.get(3));
        assertEquals("txninput id:tx coinid:0xC2", b.steps.get(4));
        assertEquals("txninput id:tx coinid:0xFUND", b.steps.get(5));

        assertTrue(b.steps.get(6).contains("address:" + route.allocs.get(0).pool.address));
        assertTrue(b.steps.get(7).contains(" tokenid:" + DexContract.MXUSD_ID));
        assertTrue("first order payment is output index 2", b.steps.get(8).contains("amount:0.05"));
        assertTrue("partial order payment is output index 3", b.steps.get(9).contains("amount:0.04"));
        assertTrue("partial remainder immediately follows payment",
                b.steps.get(10).contains("address:" + DexContract.ADDR_V5)
                        && b.steps.get(10).contains("storestate:true"));
        assertTrue("aggregate MINIMA proceeds after all covenant outputs",
                b.steps.get(11).contains("address:" + ME) && !b.steps.get(11).contains(" tokenid:"));
        assertTrue(b.steps.contains("txnsign id:tx publickey:auto"));
        assertTrue(b.steps.contains("txnbasics id:tx"));
    }

    @Test public void compositeSellPaysMinimaAndReceivesMxusd() {
        PoolRouter.Route route = PoolRouter.route(Arrays.asList(pool()), true, new BigDecimal("2"));
        CompositeRouter.Plan plan = new CompositeRouter.Plan();
        plan.poolRoute = route;
        plan.poolMinima = route.totalIn;
        plan.poolMxusd = route.totalOut;
        plan.totalMinima = route.totalIn;
        plan.totalMxusd = route.totalOut;
        DexTxn.CompositePrep prep = DexTxn.prepareComposite(plan, false);
        DexTxn.CompositeBuild b = DexTxn.buildCompositeSteps("tx", ME, plan, prep, false,
                Arrays.asList(fund("0xFUND", "3", Util.MINIMA_TOKENID)));

        assertEquals(Util.MINIMA_TOKENID, prep.payTok);
        assertTrue("MxUSD proceeds are token output to taker",
                b.steps.get(6).contains("address:" + ME) && b.steps.get(6).contains(" tokenid:" + DexContract.MXUSD_ID));
        assertTrue("MINIMA change remains native",
                b.steps.get(7).contains("address:" + ME) && !b.steps.get(7).contains(" tokenid:"));
    }

    @Test public void poolScriptRegistrationDoesNotTrackReserveAddressesAsWalletOwned() {
        String cmd = DexTxn.poolScriptRegisterCommand(pool(), "RETURN TRUE");
        assertTrue(cmd.startsWith("newscript trackall:false script:"));
    }
}
