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
    public static boolean setMxUsd(String tokenid) {
        if (tokenid == null || tokenid.trim().isEmpty()) { mxusd = MXUSD_MAINNET; return true; }
        if (!tokenid.matches("^0x[0-9a-fA-F]{64}$")) return false;
        mxusd = tokenid; return true;
    }
    public static boolean isMinima(String tok) { return tok == null || "0x00".equalsIgnoreCase(tok); }
    public static boolean isMxUsd(String tok) { return tok != null && tok.toLowerCase(Locale.ROOT).equals(mxusd.toLowerCase(Locale.ROOT)); }
    public static String name(String tok) { return isMinima(tok) ? "MINIMA" : isMxUsd(tok) ? "MxUSD" : tok; }
}
