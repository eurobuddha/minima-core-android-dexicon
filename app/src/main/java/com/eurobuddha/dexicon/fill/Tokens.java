package com.eurobuddha.dexicon.fill;

import java.util.Locale;

/** The pair. The Minima dollar token is always called MxUSD. */
public final class Tokens {
    private Tokens() {}
    public static final String MINIMA = "0x00";
    public static final String MXUSD_MAINNET = "0x7D39745FBD29049BE29850B55A18BF550E4D442F930F86266E34193D89042A90";
    public static final String MARKET = "MINIMA/MxUSD";
    /** Dev/test chains have no mainnet MxUSD: the dollar leg can be pointed at a test token (Settings). Never on mainnet. */
    private static volatile String mxusd = MXUSD_MAINNET;

    public static String MXUSD() { return mxusd; }

    /** Security review 30 Oct: the override made the maker accept ANY token as "MxUSD" — on mainnet, a worthless one
     *  shown under the real name. It now applies only once the chain is PROVEN a test chain, and never shows as MxUSD.
     *  ponytail: "test chain" = tip below 1 000 000 (mainnet passed 2.39 M in Oct 2026; a fresh test chain needs well
     *  over a year to reach it) — the node exposes no network flag; switch to one if it ever does. */
    public static final long MAINNET_MIN_BLOCK = 1_000_000L;
    private static volatile long tip;
    public static void noteBlock(long block) { if (block > 0) { tip = block; if (block >= MAINNET_MIN_BLOCK) mxusd = MXUSD_MAINNET; } }
    public static boolean testChainProven() { return tip > 0 && tip < MAINNET_MIN_BLOCK; }
    public static boolean overridden() { return !mxusd.equalsIgnoreCase(MXUSD_MAINNET); }

    public static boolean setMxUsd(String tokenid) {
        if (tokenid == null || tokenid.trim().isEmpty()) { mxusd = MXUSD_MAINNET; return true; }
        if (!tokenid.matches("^0x[0-9a-fA-F]{64}$") || !testChainProven()) { mxusd = MXUSD_MAINNET; return false; }
        mxusd = tokenid; return true;
    }
    public static boolean isMinima(String tok) { return tok == null || "0x00".equalsIgnoreCase(tok); }
    public static boolean isMxUsd(String tok) { return tok != null && tok.toLowerCase(Locale.ROOT).equals(mxusd.toLowerCase(Locale.ROOT)); }
    public static String name(String tok) { return isMinima(tok) ? "MINIMA" : isMxUsd(tok) ? (overridden() ? "TEST-USD" : "MxUSD") : tok; }
}
