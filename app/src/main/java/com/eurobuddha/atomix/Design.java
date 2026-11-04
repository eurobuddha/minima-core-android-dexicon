package com.eurobuddha.atomix;

/**
 * DEXICON-OWNED SHIM (not vendored): the palette the vendored AtomiX MarketChartView draws with, from Dexicon's Design.
 * ACCENT follows the active AtomiX market as AtomiX's own theme does: Tether green on MxUSD, Minima orange on MINIMA.
 */
public final class Design {
    private Design() {}
    public static int ACCENT() { return TradingContext.active() == TradingContext.MXUSDT ? 0xFF26A17B : com.eurobuddha.dexicon.Design.ACCENT(); }
    public static int DIM() { return com.eurobuddha.dexicon.Design.DIM(); }
    public static int DIM2() { return com.eurobuddha.dexicon.Design.DIM2(); }
}
