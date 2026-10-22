// VENDORED VERBATIM from apks/atomix 0.1.82 app/src/test/java/com/eurobuddha/atomix/swap/TombstoneTest.java by scripts/vendor-atomix.sh — do not edit here; re-vendor.
package com.eurobuddha.atomix.swap;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * The currency-switch tombstone: disabling every pair on a copy of my published order must yield a valid order
 * with NO liquidity (so freshest-per-signer makes peers DROP my order next block) while KEEPING my identity keys
 * (so peers attribute the withdrawal to me). This is what doSwitchCurrency broadcasts before flipping currency.
 */
public class TombstoneTest {

    @Test public void disablingPairsIsANoLiquidityTombstoneThatKeepsMyKeys() {
        Order o = new Order();
        Order.Pair p = new Order.Pair(true, 1.01, 0.99, 0.01);
        p.asks.add(new Order.Level(1.01, 30));
        p.bids.add(new Order.Level(0.99, 30));
        o.pairs.put(Order.PAIR_TOKENS[0], p);
        o.minimaPublicKey = "0xMYMPK";
        o.ethAddress = "0xMYETH";
        assertTrue("live order has liquidity before the tombstone", o.hasLiquidity());

        // exactly what doSwitchCurrency does to build the tombstone:
        for (Order.Pair pr : o.pairs.values()) pr.enable = false;

        assertFalse("tombstone must carry NO liquidity → peers drop my order", o.hasLiquidity());
        assertEquals("identity keys preserved so the withdrawal is attributed to me", "0xMYMPK", o.minimaPublicKey);
        assertEquals("0xMYETH", o.ethAddress);
    }
}
