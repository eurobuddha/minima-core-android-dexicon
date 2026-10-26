// VENDORED VERBATIM from apks/atomix 0.1.84 app/src/test/java/com/eurobuddha/atomix/TradingContextTest.java by scripts/vendor-atomix.sh — do not edit here; re-vendor.
package com.eurobuddha.atomix;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import android.content.SharedPreferences;

import com.eurobuddha.atomix.swap.MinimaHtlc;

import org.junit.Test;

/**
 * The combine's fund-safety config: the two currencies must map to DISTINCT tokens + isolated sentinels, the
 * per-token label must resolve settlement notifications correctly, and the persisted selection must round-trip.
 */
public class TradingContextTest {

    @Test public void tokensAreTheTwoSupportedAssets() {
        assertEquals("0x00", TradingContext.MINIMA.tokenId);
        assertEquals(MinimaHtlc.USDT_TOKENID, TradingContext.MXUSDT.tokenId);
        assertNotEquals(TradingContext.MINIMA.tokenId, TradingContext.MXUSDT.tokenId);
    }

    @Test public void sentinelsAreIsolatedPerCurrency() {
        // Each currency keeps its OWN order-book / OTC / take boards so AtomiX's orders land on the same shared
        // book the corresponding legacy app used, and the two markets never cross-contaminate.
        assertNotEquals(TradingContext.MINIMA.orderBookAddr, TradingContext.MXUSDT.orderBookAddr);
        assertNotEquals(TradingContext.MINIMA.otcBoardAddr,  TradingContext.MXUSDT.otcBoardAddr);
        assertNotEquals(TradingContext.MINIMA.otcChatAddr,   TradingContext.MXUSDT.otcChatAddr);
        assertNotEquals(TradingContext.MINIMA.takeAddr,      TradingContext.MXUSDT.takeAddr);
        // the legacy hex boards, verbatim (interop):
        assertEquals("0x4D494E494D4153574150", TradingContext.MINIMA.orderBookAddr); // "MINIMASWAP"
        assertEquals("0x5553445453574150",     TradingContext.MXUSDT.orderBookAddr); // "USDTSWAP"
    }

    @Test public void pricingModelFollowsCurrency() {
        assertFalse("MINIMA pegs to MEXC", TradingContext.MINIMA.pricingParity);
        assertTrue("mxUSDT is parity to ERC20 USDT", TradingContext.MXUSDT.pricingParity);
    }

    @Test public void themeIdentityIsDistinct() {
        assertNotEquals(TradingContext.MINIMA.accent, TradingContext.MXUSDT.accent);
        assertEquals(0xFFF7931A, TradingContext.MINIMA.accent);   // Minima orange
        assertEquals(0xFF26A17B, TradingContext.MXUSDT.accent);   // Tether green
    }

    @Test public void labelForResolvesEachTokenForSettlementNotifications() {
        assertEquals("MINIMA", TradingContext.labelFor("0x00"));
        assertEquals("mxUSDT", TradingContext.labelFor(MinimaHtlc.USDT_TOKENID));
        assertEquals("mxUSDT", TradingContext.labelFor(MinimaHtlc.USDT_TOKENID.toLowerCase()));
    }

    @Test public void otherFlipsBetweenTheTwo() {
        assertEquals(TradingContext.MXUSDT, TradingContext.MINIMA.other());
        assertEquals(TradingContext.MINIMA, TradingContext.MXUSDT.other());
    }

    @Test public void selectionPersistsAndRoundTrips() {
        SharedPreferences prefs = mock(SharedPreferences.class);
        SharedPreferences.Editor ed = mock(SharedPreferences.Editor.class);
        when(prefs.edit()).thenReturn(ed);
        when(ed.putString(anyString(), anyString())).thenReturn(ed);

        TradingContext.setActive(TradingContext.MINIMA, prefs);
        assertEquals(TradingContext.MINIMA, TradingContext.active());
        verify(ed).putString(TradingContext.PREF_KEY, "minima");

        when(prefs.getString(eq(TradingContext.PREF_KEY), anyString())).thenReturn("minima");
        assertEquals(TradingContext.MINIMA, TradingContext.load(prefs));
        when(prefs.getString(eq(TradingContext.PREF_KEY), anyString())).thenReturn("mxusdt");
        assertEquals(TradingContext.MXUSDT, TradingContext.load(prefs));

        TradingContext.setActive(TradingContext.MXUSDT, prefs);   // restore default for other tests
    }

    // ---- attributing a recorded swap to its market (history scoping, 0.1.58) ----

    @Test public void coinLabelsMapBackToTheirMarket() {
        assertSame(TradingContext.MINIMA, TradingContext.forCoinLabel("MINIMA"));
        assertSame(TradingContext.MXUSDT, TradingContext.forCoinLabel("mxUSDT"));
        // The dollar token is properly MxUSD; rows on disk say "mxUSDT". BOTH must attribute, so a later
        // label rename can never orphan the history already written under the old spelling.
        assertSame(TradingContext.MXUSDT, TradingContext.forCoinLabel("MxUSD"));
        assertSame(TradingContext.MINIMA, TradingContext.forCoinLabel("  minima  "));
    }

    @Test public void ethereumLegSymbolsAreNotAMarket() {
        // "USDT" is the ERC20 leg of EVERY swap in BOTH markets — matching it would attribute every row to
        // the dollar market and re-create the bug this map exists to fix.
        assertNull(TradingContext.forCoinLabel("USDT"));
        assertNull(TradingContext.forCoinLabel("WETH"));
        assertNull(TradingContext.forCoinLabel(""));
        assertNull(TradingContext.forCoinLabel(null));
    }

    @Test public void forSwapReadsWhicheverLegIsTheMinimaOne() {
        // The two real shapes: sell the Minima leg, or buy it.
        assertSame(TradingContext.MINIMA, TradingContext.forSwap("MINIMA", "USDT"));
        assertSame(TradingContext.MXUSDT, TradingContext.forSwap("USDT", "mxUSDT"));
        assertSame(TradingContext.MXUSDT, TradingContext.forSwap("mxUSDT", "USDT"));
        assertSame(TradingContext.MINIMA, TradingContext.forSwap("USDT", "MINIMA"));
        // Neither leg attributable → null, and callers must SHOW such a row rather than hide it.
        assertNull(TradingContext.forSwap("USDT", "WETH"));
        assertNull(TradingContext.forSwap(null, null));
    }
}
