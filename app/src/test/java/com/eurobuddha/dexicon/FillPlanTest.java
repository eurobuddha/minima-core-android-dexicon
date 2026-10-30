package com.eurobuddha.dexicon;

import static org.junit.Assert.*;

import com.eurobuddha.dexicon.fill.Coin;
import com.eurobuddha.dexicon.fill.FillPlan;
import com.eurobuddha.dexicon.fill.Tokens;

import org.junit.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Port of mds/dexicon/test/fill.test.js: taker plan and maker verification, rounding in the maker's favour. */
public class FillPlanTest {
    static final String MADDR = TestIds.hex("aa", 32), TADDR = TestIds.hex("bb", 32), MCOINADDR = TestIds.hex("cc", 32);
    static final List<String> SCRIPTS = Arrays.asList("RETURN SIGNEDBY(" + TestIds.hex("dd", 32) + ")");

    static Coin coin(String id, String tok, String value, String addr) { return new Coin(TestIds.hex(id, 32), addr, tok, new BigDecimal(value), false, false, 0); }
    static List<String> ids(List<Coin> cs) { List<String> l = new ArrayList<>(); for (Coin c : cs) l.add(c.coinid); return l; }
    static FillPlan.Order order(String side, String price, String amount, List<Coin> coins) { return new FillPlan.Order(side, price, amount, MADDR, ids(coins), SCRIPTS); }
    static List<Coin> cat(List<Coin>... ls) { List<Coin> o = new ArrayList<>(); for (List<Coin> l : ls) o.addAll(l); return o; }

    /** The txnview `transaction` shape the maker will see. */
    static List<Coin> viewInputs(FillPlan.Plan p, List<Coin> all) { List<Coin> o = new ArrayList<>(); for (String id : p.inputs) for (Coin c : all) if (c.coinid.equals(id)) o.add(c); return o; }
    static List<Coin> viewOutputs(FillPlan.Plan p) { List<Coin> o = new ArrayList<>(); for (FillPlan.Output x : p.outputs) o.add(new Coin("0x00", x.address, x.tokenid, new BigDecimal(x.amount), false, false, 0)); return o; }
    static List<Coin> viewOutputs(List<FillPlan.Output> outs) { List<Coin> o = new ArrayList<>(); for (FillPlan.Output x : outs) o.add(new Coin("0x00", x.address, x.tokenid, new BigDecimal(x.amount), false, false, 0)); return o; }
    static FillPlan.Output out(String addr, String amt, String tok) { return new FillPlan.Output(addr, amt, tok); }

    final List<Coin> mSell = Arrays.asList(coin("01", Tokens.MINIMA, "60", MCOINADDR), coin("02", Tokens.MINIMA, "50", MCOINADDR));
    final List<Coin> tUsd = Arrays.asList(coin("11", Tokens.MXUSD(), "0.3", TADDR), coin("12", Tokens.MXUSD(), "0.2", TADDR));
    final FillPlan.Order oS = order("sell", "0.0123", "100", mSell);

    @Test public void sellOrderPlanAndVerify() {
        FillPlan.Plan p = FillPlan.plan(oS, "33.333333339", mSell, tUsd, TADDR);
        assertEquals("filled quantised to 8dp", "33.33333333", p.filled);
        assertEquals("taker pays MxUSD rounded UP", "0.41", p.takerGives);   // 33.33333333*0.0123 = 0.409999999959 → up
        assertEquals("33.33333333", p.makerGives);
        assertEquals("maker coin picked largest-first", mSell.get(0).coinid, p.inputs.get(0));
        List<String> outs = new ArrayList<>();
        for (FillPlan.Output o : p.outputs) outs.add((Tokens.isMinima(o.tokenid) ? "M:" : "U:") + o.amount + "@" + o.address.substring(0, 6));
        assertEquals(Arrays.asList("M:33.33333333@0xbbbb", "M:26.66666667@0xcccc", "U:0.41@0xaaaa", "U:0.09@0xbbbb"), outs);   // 0.3 can't cover 0.41 → both coins, change 0.09
        assertEquals("0.09", p.takerChange);
        assertEquals(1, p.scripts.size());
        FillPlan.Verdict v = FillPlan.verifyAsMaker(viewInputs(p, cat(mSell, tUsd)), viewOutputs(p), false, oS, oS.coins, "100", Arrays.asList(MCOINADDR, MADDR));
        assertTrue("maker accepts its own plan: " + v.reason, v.ok);
        assertEquals("33.33333333", v.filled);
        assertEquals("0.41", v.received);
    }

    @Test public void sellOrderRefusals() {
        FillPlan.Plan p = FillPlan.plan(oS, "33.333333339", mSell, tUsd, TADDR);
        List<Coin> all = cat(mSell, tUsd);
        List<String> mine = Arrays.asList(MCOINADDR);
        // underpayment
        List<FillPlan.Output> under = new ArrayList<>(p.outputs); under.set(2, out(MADDR, "0.40999999", Tokens.MXUSD()));
        assertFalse(FillPlan.verifyAsMaker(viewInputs(p, all), viewOutputs(under), false, oS, oS.coins, "100", mine).ok);
        // overfill vs remaining
        assertFalse(FillPlan.verifyAsMaker(viewInputs(p, all), viewOutputs(p), false, oS, oS.coins, "30", mine).ok);
        // PandaDEX min-remainder floor: 33.33333333 of 33.5 would leave 0.16666667 → refused; of 34.33333333 → 1 left, fine
        assertTrue(FillPlan.verifyAsMaker(viewInputs(p, all), viewOutputs(p), false, oS, oS.coins, "33.5", mine).reason.contains("minimum remainder"));
        assertTrue(FillPlan.verifyAsMaker(viewInputs(p, all), viewOutputs(p), false, oS, oS.coins, "34.33333333", mine).ok);
        assertTrue(FillPlan.verifyAsMaker(viewInputs(p, all), viewOutputs(p), false, oS, oS.coins, "33.33333333", mine).ok);
        // my unadvertised coin spent
        Coin extra = coin("03", Tokens.MINIMA, "5", MCOINADDR);
        List<Coin> ins = new ArrayList<>(viewInputs(p, all)); ins.add(extra);
        List<FillPlan.Output> foreign = new ArrayList<>(p.outputs); foreign.set(0, out(TADDR, "38.33333333", Tokens.MINIMA));
        assertFalse(FillPlan.verifyAsMaker(ins, viewOutputs(foreign), false, oS, oS.coins, "100", mine).ok);
        // payment to the wrong address / wrong token
        List<FillPlan.Output> wrongAddr = new ArrayList<>(p.outputs); wrongAddr.set(2, out(TADDR, "0.41", Tokens.MXUSD()));
        assertFalse(FillPlan.verifyAsMaker(viewInputs(p, all), viewOutputs(wrongAddr), false, oS, oS.coins, "100", mine).ok);
        List<FillPlan.Output> wrongTok = new ArrayList<>(p.outputs); wrongTok.set(2, out(MADDR, "0.41", TestIds.hex("ee", 32)));
        assertFalse(FillPlan.verifyAsMaker(viewInputs(p, all), viewOutputs(wrongTok), false, oS, oS.coins, "100", mine).ok);
        // maker change redirected to the taker → fill > remaining-at-price
        List<FillPlan.Output> stolen = new ArrayList<>(p.outputs); stolen.set(1, out(TADDR, "26.66666667", Tokens.MINIMA));
        assertFalse(FillPlan.verifyAsMaker(viewInputs(p, all), viewOutputs(stolen), false, oS, oS.coins, "100", mine).ok);
        // transaction state
        assertFalse(FillPlan.verifyAsMaker(viewInputs(p, all), viewOutputs(p), true, oS, oS.coins, "100", mine).ok);
    }

    /** Security review 30 Oct: a taker paid MxUSD to my pay-to address, then listed that coin (now mine) as its own input
     *  to pay for a second take. My pay-to address counts as mine even when the cached address list lacks it. */
    @Test public void myPayToCoinCannotPassAsTheTakers() {
        FillPlan.Plan p = FillPlan.plan(oS, "33.333333339", mSell, tUsd, TADDR);
        Coin paid = coin("21", Tokens.MXUSD(), "0.41", MADDR);   // the earlier payment, now my coin
        List<Coin> ins = new ArrayList<>(viewInputs(p, mSell)); ins.add(paid);
        List<FillPlan.Output> outs = new ArrayList<>(p.outputs); outs.remove(3); outs.remove(2);   // the taker's own coins dropped
        outs.add(out(MADDR, "0.41", Tokens.MXUSD()));
        FillPlan.Verdict v = FillPlan.verifyAsMaker(ins, viewOutputs(outs), false, oS, oS.coins, "100", Arrays.asList(MCOINADDR));
        assertFalse(v.ok);
        assertTrue(v.reason, v.reason.contains("not advertised"));
    }

    @Test public void makerSignsWithTheOrdersOwnKeysOnly() {
        assertEquals(Arrays.asList(TestIds.hex("dd", 32)), com.eurobuddha.dexicon.fill.FillTxn.signKeys(Arrays.asList(SCRIPTS.get(0), SCRIPTS.get(0), "RETURN TRUE", null)));
        assertTrue(com.eurobuddha.dexicon.fill.FillTxn.signKeys(Arrays.asList("RETURN SIGNEDBY(0x00) AND TRUE")).isEmpty());
    }

    @Test public void buyOrder() {
        List<Coin> mUsd = Arrays.asList(coin("21", Tokens.MXUSD(), "2", MCOINADDR), coin("22", Tokens.MXUSD(), "1.5", MCOINADDR));
        List<Coin> tMin = Arrays.asList(coin("31", Tokens.MINIMA, "7", TADDR), coin("32", Tokens.MINIMA, "4", TADDR));
        FillPlan.Order oB = order("buy", "0.015", "200", mUsd);
        FillPlan.Plan p = FillPlan.plan(oB, "10.000000009", mUsd, tMin, TADDR);
        assertEquals("10", p.filled);
        assertEquals("maker pays MxUSD rounded DOWN", "0.15", p.makerGives);
        assertEquals("10", p.takerGives);
        assertEquals("1", p.takerChange);
        List<String> mine = Arrays.asList(MCOINADDR);
        FillPlan.Verdict v = FillPlan.verifyAsMaker(viewInputs(p, cat(mUsd, tMin)), viewOutputs(p), false, oB, oB.coins, "200", mine);
        assertTrue(v.ok); assertEquals("maker filled = MINIMA received", "10", v.filled);
        List<FillPlan.Output> overpay = new ArrayList<>(p.outputs); overpay.set(0, out(TADDR, "0.15000001", Tokens.MXUSD())); overpay.set(1, out(MCOINADDR, "1.84999999", Tokens.MXUSD()));
        assertFalse("paying above price refused", FillPlan.verifyAsMaker(viewInputs(p, cat(mUsd, tMin)), viewOutputs(overpay), false, oB, oB.coins, "200", mine).ok);
        assertFalse("overfill refused", FillPlan.verifyAsMaker(viewInputs(p, cat(mUsd, tMin)), viewOutputs(p), false, oB, oB.coins, "5", mine).ok);
    }

    @Test public void fatCoinChangeIsExact() {
        List<Coin> fat = Arrays.asList(coin("05", Tokens.MINIMA, "999998999.999999999999999999999999999999", MCOINADDR));
        FillPlan.Order oF = order("sell", "0.5", "10", fat);
        FillPlan.Plan p = FillPlan.plan(oF, "3", fat, Arrays.asList(coin("13", Tokens.MXUSD(), "5", TADDR)), TADDR);
        assertEquals("999998996.999999999999999999999999999999", p.outputs.get(1).amount);
        assertTrue(new BigDecimal(p.outputs.get(1).amount).compareTo(new BigDecimal("999998999.999999999999999999999999999999").subtract(new BigDecimal(3))) <= 0);
    }

    @Test public void planFailures() {
        thr("exceeds", () -> FillPlan.plan(oS, "101", mSell, tUsd, TADDR));
        thr("maker coins", () -> FillPlan.plan(oS, "100", Arrays.asList(mSell.get(1)), tUsd, TADDR));
        thr("your wallet", () -> FillPlan.plan(oS, "100", mSell, Arrays.asList(tUsd.get(1)), TADDR));
        thr("not MINIMA", () -> FillPlan.plan(oS, "1", Arrays.asList(coin("21", Tokens.MXUSD(), "2", MCOINADDR)), tUsd, TADDR));
        thr("positive", () -> FillPlan.plan(oS, "0", mSell, tUsd, TADDR));
    }
    private static void thr(String want, Runnable r) {
        try { r.run(); fail("no throw: " + want); } catch (IllegalArgumentException e) { assertTrue(e.getMessage(), e.getMessage().contains(want)); }
    }

    @Test public void concurrentTakersSpreadAcrossBackingCoins() {
        List<Coin> coins = Arrays.asList(coin("a1", Tokens.MINIMA, "40", MCOINADDR), coin("a2", Tokens.MINIMA, "30", MCOINADDR), coin("a3", Tokens.MINIMA, "20", MCOINADDR), coin("a4", Tokens.MINIMA, "3", MCOINADDR));
        List<Coin> taker = Arrays.asList(coin("b1", Tokens.MXUSD(), "100", TADDR));
        FillPlan.Order o = order("sell", "0.5", "90", coins);
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (int k = 16; k < 80; k++) {
            FillPlan.Plan p = FillPlan.plan(o, "10", coins, taker, TestIds.hex(Integer.toHexString(k), 32));
            assertEquals(2, p.inputs.size()); seen.add(p.inputs.get(0));
        }
        assertTrue("64 takers hit more than one backing coin", seen.size() > 1);
        assertFalse("never the 3-coin", seen.contains(coins.get(3).coinid));
        assertEquals(FillPlan.plan(o, "10", coins, taker, TestIds.hex("11", 32)).inputs.get(0), FillPlan.plan(o, "10", coins, taker, TestIds.hex("11", 32)).inputs.get(0));
        FillPlan.Plan big = FillPlan.plan(o, "85", coins, taker, TestIds.hex("11", 32));
        assertEquals(Arrays.asList(coins.get(0).coinid, coins.get(1).coinid, coins.get(2).coinid), big.inputs.subList(0, 3));
        // byte-for-byte the same hash as fill.js spreadIndex
        assertEquals(2, FillPlan.spreadIndex("0xabc", 3) == FillPlan.spreadIndex("0xABC", 3) ? 2 : -1);
    }

    @Test public void pandadexMinimumRemainderFloor() {
        assertEquals("455.78231293", FillPlan.floorTake(new BigDecimal("500"), new BigDecimal("455.78231293")).toPlainString());
        assertEquals("454.78231293", FillPlan.floorTake(new BigDecimal("455.78"), new BigDecimal("455.78231293")).toPlainString());   // shrunk to leave 1
        assertEquals("100", FillPlan.floorTake(new BigDecimal("100"), new BigDecimal("455.78")).toPlainString());
        assertEquals(0, FillPlan.floorTake(new BigDecimal("0.2"), new BigDecimal("0.5")).signum());
        assertTrue(FillPlan.dust(new BigDecimal("0.00231293"), new BigDecimal("1000")));
        assertFalse(FillPlan.dust(new BigDecimal("1"), new BigDecimal("1000")));
        assertFalse(FillPlan.dust(new BigDecimal("0.5"), new BigDecimal("0.5")));
    }
}
