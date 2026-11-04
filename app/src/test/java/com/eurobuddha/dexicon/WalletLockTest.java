package com.eurobuddha.dexicon;

import static org.junit.Assert.*;

import com.eurobuddha.atomix.swap.SwapEngine;

import org.junit.Test;

/** 0.2.23: node-picked spends of AtomiX's coins take its wallet lock, released exactly once; other tokens never wait. */
public class WalletLockTest {
    @Test public void atomixCoinsTakeTheLockOthersDoNot() {
        Runnable a = WalletLock.take("0x00");
        assertNotNull(a);
        assertNull("held: a second spend of an AtomiX coin is refused", WalletLock.take(com.eurobuddha.atomix.TradingContext.MXUSDT.tokenId));
        assertNotNull("an NFT never waits for a swap", WalletLock.take("0x" + "12".repeat(32)));
        a.run(); a.run();   // released once — the second call must not drop someone else's lock
        assertTrue(SwapEngine.beginConsolidate());
        assertNull("still held by the new owner after the double release", WalletLock.take("0x00"));
        SwapEngine.endConsolidate();
        Runnable b = WalletLock.take("0x00"); assertNotNull(b); b.run();
    }

    @Test public void tokenOfReadsTheCommand() {
        assertEquals("0xab", WalletLock.tokenOf("consolidate tokenid:0xab"));
        assertEquals("0x00", WalletLock.tokenOf("consolidate"));
    }
}
