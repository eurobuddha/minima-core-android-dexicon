// VENDORED VERBATIM from apks/atomix 0.1.84 app/src/main/java/com/eurobuddha/atomix/TradingContext.java by scripts/vendor-atomix.sh — do not edit here; re-vendor.
package com.eurobuddha.atomix;

import android.content.SharedPreferences;

import com.eurobuddha.atomix.swap.MinimaHtlc;

/**
 * The active trading currency's COMPLETE configuration — the single knob that turns AtomiX between its two
 * markets. Exactly ONE context is active at a time (the user "publishes markets in one currency"): it drives
 * the traded Minima-leg token, the ask-ladder grain, the isolated order-book / OTC / take sentinels, the
 * pricing model, the coin labels and the whole visual identity (accent, gradient, on-accent ink).
 *
 * <p>Interop with the existing minimaSwap / usdtSwap user bases is preserved by the SENTINEL ADDRESSES (each
 * currency keeps the exact hex board its old app used), NOT by identity — the comms identity is advertised
 * per-order, so one AtomiX identity serves both currencies.
 *
 * <p>Background SETTLEMENT is deliberately currency-agnostic (see {@link MinimaHtlc}): switching the active
 * currency never strands an in-flight swap in the other one — claim/refund read each coin's own token.
 */
public enum TradingContext {

    /** Native MINIMA ↔ ERC20 (minimaSwap's market). Orange identity, MEXC MINIMA/USDT pricing. */
    MINIMA(
            "minima", "MINIMA",
            MinimaHtlc.MINIMA_TOKENID,
            /* pricingParity */ false,
            /* orderBook */ "0x4D494E494D4153574150",              // "MINIMASWAP"
            /* otcBoard  */ "0x4D494E494D41535741504F544342",      // "MINIMASWAPOTCB"
            /* otcChat   */ "0x4D494E494D41535741504F544343",      // "MINIMASWAPOTCC"
            /* take      */ "0x4D494E494D415357415054414B45",      // "MINIMASWAPTAKE"
            /* accent       */ 0xFFF7931A,                          // Minima orange
            /* accentSoftDk */ 0x33F7931A, /* accentSoftLt */ 0x1FF7931A,
            /* gradStart    */ 0xFFFFAB3D,
            /* onAccentDk    */ 0xFF0B0D12, /* onAccentLt   */ 0xFF201400,  // dark ink on orange
            /* hkdfContext  */ "minimaswap"                                 // == legacy minimaSwap comms identity
    ),

    /** Bridged native-USDT (mxUSDT) ↔ ERC20 USDT (usdtSwap's market). Tether-green identity, parity pricing. */
    MXUSDT(
            "mxusdt", "mxUSDT",
            MinimaHtlc.USDT_TOKENID,
            /* pricingParity */ true,
            /* orderBook */ "0x5553445453574150",                  // "USDTSWAP"
            /* otcBoard  */ "0x55534454535741504F544342",          // "USDTSWAPOTCB"
            /* otcChat   */ "0x55534454535741504F544343",          // "USDTSWAPOTCC"
            /* take      */ "0x555344545357415054414B45",          // "USDTSWAPTAKE"
            /* accent       */ 0xFF26A17B,                          // Tether green
            /* accentSoftDk */ 0x3326A17B, /* accentSoftLt */ 0x1F26A17B,
            /* gradStart    */ 0xFF3DBF93,
            /* onAccentDk    */ 0xFFFFFFFF, /* onAccentLt   */ 0xFFFFFFFF,  // white ink on green (both modes)
            /* hkdfContext  */ "usdtswap"                                   // == legacy usdtSwap comms identity
    );

    public final String key;            // persistence + logs
    public final String coinLabel;      // display: "MINIMA" / "mxUSDT"
    public final String tokenId;        // the traded Minima-leg token
    public final boolean pricingParity; // true → parity 1.0 model; false → MEXC feed
    public final String orderBookAddr, otcBoardAddr, otcChatAddr, takeAddr;
    public final int accent, accentSoftDark, accentSoftLight, gradStart, onAccentDark, onAccentLight;
    /** HKDF domain for the comms identity — MUST equal the legacy per-currency app's context ("usdtswap" /
     *  "minimaswap") so AtomiX derives the SAME box/sign keys usdtSwap/minimaSwap did. Handshakes are sealed to
     *  the maker's identity FROM the order, so AtomiX can only service an order it can decrypt for — i.e. one
     *  published under the identity AtomiX itself holds. Matching the legacy context rejoins the whole existing
     *  order-book ecosystem (a single "atomix" identity orphaned it — the combine regression). */
    public final String hkdfContext;

    TradingContext(String key, String coinLabel, String tokenId, boolean pricingParity,
                   String orderBookAddr, String otcBoardAddr, String otcChatAddr, String takeAddr,
                   int accent, int accentSoftDark, int accentSoftLight, int gradStart,
                   int onAccentDark, int onAccentLight, String hkdfContext) {
        this.key = key; this.coinLabel = coinLabel; this.tokenId = tokenId; this.pricingParity = pricingParity;
        this.orderBookAddr = orderBookAddr; this.otcBoardAddr = otcBoardAddr;
        this.otcChatAddr = otcChatAddr; this.takeAddr = takeAddr;
        this.accent = accent; this.accentSoftDark = accentSoftDark; this.accentSoftLight = accentSoftLight;
        this.gradStart = gradStart; this.onAccentDark = onAccentDark; this.onAccentLight = onAccentLight;
        this.hkdfContext = hkdfContext;
    }

    // ---- the single active selection ----

    public static final String PREF_KEY = "trading_currency";
    private static volatile TradingContext ACTIVE = MXUSDT;   // default; overridden by load() at launch

    /** The currency AtomiX is currently publishing/market-making/displaying in. Never null. */
    public static TradingContext active() { return ACTIVE; }

    /** Restore the persisted selection at launch. Safe to call before any UI. */
    public static TradingContext load(SharedPreferences prefs) {
        String k = prefs == null ? null : prefs.getString(PREF_KEY, MXUSDT.key);
        ACTIVE = MINIMA.key.equals(k) ? MINIMA : MXUSDT;
        return ACTIVE;
    }

    /** Persist + activate a new currency. Caller re-initialises the trading UI/scanners + re-themes. */
    public static void setActive(TradingContext ctx, SharedPreferences prefs) {
        if (ctx == null) return;
        ACTIVE = ctx;
        if (prefs != null) prefs.edit().putString(PREF_KEY, ctx.key).apply();
    }

    /** Resolve the persisted market key without guessing from the current selection. */
    public static TradingContext forKey(String key) {
        for (TradingContext context : values()) if (context.key.equals(key)) return context;
        return null;
    }

    public TradingContext other() { return this == MINIMA ? MXUSDT : MINIMA; }

    /** Display label for a specific token id — used by SETTLEMENT notifications, which can be for a swap in
     *  EITHER currency regardless of the active one. Falls back to the active label for an unknown token. */
    public static String labelFor(String tokenid) {
        if (tokenid != null) for (TradingContext c : values()) if (c.tokenId.equalsIgnoreCase(tokenid)) return c.coinLabel;
        return active().coinLabel;
    }

    // ---- attributing a RECORDED swap to its market (history scoping) ----

    /**
     * The market a Minima-side coin LABEL belongs to, or null for an Ethereum-leg symbol ("USDT", "WETH", …)
     * or an unrecognised legacy label.
     *
     * <p>Swap rows persist the label that was current when they were written ({@link #coinLabel}, stamped by
     * SwapEngine.baseSwap), so this accepts the dollar token's ALIASES too: the Minima dollar token is properly
     * MxUSD, and rows already on disk carry "mxUSDT". Matching both means a later label rename can never orphan
     * existing history.
     *
     * <p>Deliberately does NOT match the bare "USDT" — that is the ERC20 leg's symbol on EVERY swap in BOTH
     * markets, so matching it would attribute every row to the dollar market.
     */
    public static TradingContext forCoinLabel(String label) {
        if (label == null) return null;
        String l = label.trim();
        if (l.isEmpty()) return null;
        for (TradingContext c : values()) if (c.coinLabel.equalsIgnoreCase(l)) return c;
        if (l.equalsIgnoreCase("mxUSD") || l.equalsIgnoreCase("mxUSDT")) return MXUSDT;
        if (l.equalsIgnoreCase("MINIMA")) return MINIMA;
        return null;
    }

    /** The market a recorded swap belongs to — whichever of its two legs carries a Minima-side label. Returns
     *  null when neither does (an unattributable legacy row); callers MUST show such a row, never hide it. */
    public static TradingContext forSwap(String sellToken, String buyToken) {
        TradingContext c = forCoinLabel(sellToken);
        return c != null ? c : forCoinLabel(buyToken);
    }
}
