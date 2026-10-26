package com.eurobuddha.dexicon.maker;

import static org.junit.Assert.*;

import com.eurobuddha.dexicon.fill.Coin;

import org.junit.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** The ladder's one-shot batch: every rung backed from ONE wallet snapshot, no coin ever given to two orders. */
public class BatchAllocateTest {
    static final String MINIMA = "0x00", ADDR = "0xAA";
    static Coin coin(String id, String amt) { return new Coin(id, ADDR, MINIMA, new BigDecimal(amt), false, false, 100); }
    static MyOrder live(String id, String... coins) { MyOrder o = new MyOrder(); o.orderId = id; o.side = "sell"; o.coins = new ArrayList<>(Arrays.asList(coins)); return o; }

    @Test public void tenRungsGetTenDistinctCoinsInOnePass() {
        List<Coin> minima = new ArrayList<>();
        for (int i = 0; i < 10; i++) minima.add(coin("0x" + i, "1200"));
        List<MakerEngine.Op> ops = new ArrayList<>();
        for (int i = 0; i < 5; i++) ops.add(new MakerEngine.Op("A" + i, null, "sell", "0.0034", "1200"));
        List<MakerEngine.Alloc> a = MakerEngine.allocate(ops, minima, new ArrayList<>(), new ArrayList<>(), 110, 500);
        Set<String> used = new HashSet<>();
        for (MakerEngine.Alloc x : a) { assertNull(x.err); for (String id : x.coins) assertTrue("coin used twice: " + id, used.add(id)); }
        assertEquals(5, used.size());
    }

    @Test public void anEditMayKeepItsOwnCoinButNeverTakesAnotherOrders() {
        List<Coin> minima = Arrays.asList(coin("0x1", "1200"), coin("0x2", "1200"));
        List<MyOrder> mine = Arrays.asList(live("0xA", "0x1"), live("0xB", "0x2"));
        // re-price A: it can re-use 0x1, but 0x2 belongs to B
        List<MakerEngine.Alloc> a = MakerEngine.allocate(Arrays.asList(new MakerEngine.Op("A1", "0xA", "sell", "0.0035", "1200")), minima, new ArrayList<>(), mine, 110, 500);
        assertEquals(Arrays.asList("0x1"), a.get(0).coins);
        // a NEW rung has nothing left: both coins back live orders
        a = MakerEngine.allocate(Arrays.asList(new MakerEngine.Op("A3", null, "sell", "0.0035", "1200")), minima, new ArrayList<>(), mine, 110, 500);
        assertNotNull(a.get(0).err);
        assertTrue(a.get(0).err.startsWith("not enough free"));
    }

    @Test public void editAndNewRungsShareTheSnapshotWithoutOverlap() {
        List<Coin> minima = Arrays.asList(coin("0x1", "1200"), coin("0x2", "1200"));
        List<MyOrder> mine = Arrays.asList(live("0xA", "0x1"));
        List<MakerEngine.Alloc> a = MakerEngine.allocate(Arrays.asList(
                new MakerEngine.Op("A1", "0xA", "sell", "0.0035", "1200"),
                new MakerEngine.Op("A2", null, "sell", "0.0036", "1200"),
                new MakerEngine.Op("A3", null, "sell", "0.0037", "1200")), minima, new ArrayList<>(), mine, 110, 500);
        assertNull(a.get(0).err); assertNull(a.get(1).err);
        Set<String> all = new HashSet<>(a.get(0).coins); for (String id : a.get(1).coins) assertTrue("shared coin " + id, all.add(id));
        assertNotNull("no coin left for the third rung", a.get(2).err);
    }
}
