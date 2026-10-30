package com.eurobuddha.dexicon;

import static org.junit.Assert.*;

import com.eurobuddha.dexicon.fill.Tokens;

import org.junit.After;
import org.junit.Test;

/** Security review 30 Oct: the dollar-token override applies only on a proven test chain, and never shows as MxUSD. */
public class TokenOverrideTest {
    static final String TEST_TOKEN = "0x" + "ab".repeat(32);
    @After public void reset() { Tokens.noteBlock(1); Tokens.setMxUsd(""); }

    @Test public void refusedOnMainnetOrUnknownChain() {
        Tokens.noteBlock(2_391_696);
        assertFalse(Tokens.setMxUsd(TEST_TOKEN));
        assertEquals(Tokens.MXUSD_MAINNET, Tokens.MXUSD());
        assertEquals("MxUSD", Tokens.name(Tokens.MXUSD_MAINNET));
    }

    @Test public void allowedOnTestChainButNeverCalledMxUsd() {
        Tokens.noteBlock(1234);
        assertTrue(Tokens.setMxUsd(TEST_TOKEN));
        assertEquals("TEST-USD", Tokens.name(TEST_TOKEN));
        Tokens.noteBlock(1_500_000);   // the node turns out to be on mainnet: the override is dropped at once
        assertEquals(Tokens.MXUSD_MAINNET, Tokens.MXUSD());
    }
}
