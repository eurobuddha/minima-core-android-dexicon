// VENDORED VERBATIM from apks/atomix 0.1.84 app/src/test/java/com/eurobuddha/atomix/swap/OrderBookTombstoneTest.java by scripts/vendor-atomix.sh — do not edit here; re-vendor.
package com.eurobuddha.atomix.swap;

import static org.junit.Assert.*;

import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The "phantom market" fix: a maker that withdraws publishes a no-liquidity tombstone as its FRESHEST order, and
 * the taker must honor it — both when aggregating the book (freshest-per-signer) and at the moment of locking a
 * leg (the take-time guard). Proven bug: the S23 traded against a Z Fold order whose freshest on-chain order was
 * an en=false tombstone, because these paths weren't being exercised on a fresh book.
 */
public class OrderBookTombstoneTest {

    private static final String SYM = Order.PAIR_TOKENS[0];

    /** An order for {@code signer} at timestamp {@code ts}; enabled = live ladder, disabled = tombstone (the
     *  disabled order still CARRIES the ladder arrays, exactly like the on-chain withdrawal coin). */
    private Order order(String signer, long ts, boolean enabled) {
        Order o = new Order();
        Order.Pair p = new Order.Pair(enabled, 1.01, 0.99, 0.01);
        p.asks.add(new Order.Level(1.01, 30));
        p.bids.add(new Order.Level(0.99, 30));
        o.pairs.put(SYM, p);
        o.signerPk = signer;
        o.ts = ts;
        o.minimaPublicKey = "0x11AA22BB";
        o.ethAddress = "0x33CC44DD";
        return o;
    }

    @Test public void effectiveLevelsHonorEnable() {
        Order live = order("0xAA", 1, true);
        assertFalse(live.effectiveBids(SYM).isEmpty());
        assertFalse(live.effectiveAsks(SYM).isEmpty());
        assertTrue(live.hasLiquidity());

        Order tomb = order("0xAA", 1, false);   // same ladder data, pair disabled
        assertTrue("a disabled pair advertises no bids", tomb.effectiveBids(SYM).isEmpty());
        assertTrue("a disabled pair advertises no asks", tomb.effectiveAsks(SYM).isEmpty());
        assertFalse(tomb.hasLiquidity());
    }

    @Test public void freshestTombstoneSuppressesOlderLiveOrders() {
        Map<String, Order> book = new LinkedHashMap<>();
        // coins arrive order:desc (newest first): tombstone (ts=200, disabled) then older live orders
        SwapOrderBook.mergeFreshest(book, order("0xAA", 200, false));
        SwapOrderBook.mergeFreshest(book, order("0xAA", 100, true));
        SwapOrderBook.mergeFreshest(book, order("0xAA", 50, true));

        Order kept = book.get("0xAA");
        assertNotNull(kept);
        assertEquals("freshest (highest ts) wins", 200L, kept.ts);
        assertFalse("the maker's freshest order is a tombstone → no liquidity", kept.hasLiquidity());
    }

    @Test public void freshestMergeRanksByTsNotInsertionOrder() {
        // even if the live order is merged AFTER the newer tombstone, ts decides — not arrival order
        Map<String, Order> book = new LinkedHashMap<>();
        SwapOrderBook.mergeFreshest(book, order("0xAA", 100, true));    // live first
        SwapOrderBook.mergeFreshest(book, order("0xAA", 200, false));   // then the newer tombstone
        assertFalse(book.get("0xAA").hasLiquidity());
    }

    @Test public void distinctSignersAreKeptSeparately() {
        Map<String, Order> book = new LinkedHashMap<>();
        SwapOrderBook.mergeFreshest(book, order("0xAA", 200, false));   // maker A withdrew
        SwapOrderBook.mergeFreshest(book, order("0xBB", 100, true));    // maker B still live
        assertFalse("A is tombstoned", book.get("0xAA").hasLiquidity());
        assertTrue("B untouched by A's tombstone", book.get("0xBB").hasLiquidity());
    }

    @Test public void guardDoesNotFalseRejectLegacyScalarOnlyMaker() {
        // A pre-ladder maker advertises via the legacy buy/sell scalars with EMPTY bids/asks lists. effectiveBids/
        // Asks synthesize a level from those scalars, so such a live maker must still pass the take-time guard.
        Order legacy = new Order();
        legacy.pairs.put(SYM, new Order.Pair(true, 1.01, 0.99, 0.01));   // enabled, buy/sell set, no ladder rows
        legacy.signerPk = "0xCC";
        legacy.ts = 1;
        assertTrue("legacy scalar-only maker has liquidity", legacy.hasLiquidity());
        assertTrue("must not false-reject a legacy SELL-side maker", SwapOrderBook.makerLive(legacy, SYM, true));
        assertTrue("must not false-reject a legacy BUY-side maker", SwapOrderBook.makerLive(legacy, SYM, false));
    }

    @Test public void takeTimeGuardRefusesWithdrawnOrMissingMaker() {
        Order live = order("0xAA", 1, true);
        assertTrue("sell → checks the maker's bids", SwapOrderBook.makerLive(live, SYM, true));
        assertTrue("buy → checks the maker's asks", SwapOrderBook.makerLive(live, SYM, false));

        Order tomb = order("0xAA", 1, false);
        assertFalse("never lock a SELL leg against a tombstoned maker", SwapOrderBook.makerLive(tomb, SYM, true));
        assertFalse("never lock a BUY leg against a tombstoned maker", SwapOrderBook.makerLive(tomb, SYM, false));
        assertFalse("never lock against a maker absent from the book", SwapOrderBook.makerLive(null, SYM, true));
    }
}
