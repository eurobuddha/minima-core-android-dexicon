// VENDORED VERBATIM from apks/pandapools 0.9.86 app/src/main/java/com/eurobuddha/pandapools/Util.java by scripts/vendor-pandapools.sh — do not edit here; re-vendor.
package com.eurobuddha.pandapools;

import org.json.JSONObject;

import java.math.BigDecimal;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class Util {

    public static final String MINIMA_TOKENID = "0x00";

    /** tokenid → resolved display name (ticker/name), learned from every coin/pool scan. Lets the
     *  Activity history name BOTH legs of a swap (e.g. "USDT") even for a swap that came from another
     *  client and so has no local ActivityLog entry to borrow the label from. */
    private static final Map<String, String> TOKEN_NAMES = new ConcurrentHashMap<>();

    private Util() {}

    /** The cached display name for a tokenid, or null if we've never resolved one. Minima → "Minima". */
    public static String tokenNameCached(String tokenid) {
        if (isMinima(tokenid)) return "Minima";
        return tokenid == null ? null : TOKEN_NAMES.get(tokenid);
    }

    /** A Minima address: 0x + exactly 64 hex (32-byte script hash), or Mx + 40–118 alnum.
     *  Matches the dapp's validateAddress; a pre-filter before the node's checkaddress. */
    public static boolean isValidAddress(String a) {
        return a != null && a.matches("^(0x[0-9a-fA-F]{64}|Mx[A-Za-z0-9]{40,118})$");
    }

    /** Number of significant decimal places in an amount (0 for integers). */
    public static int decimalPlaces(BigDecimal bd) {
        return Math.max(0, bd.stripTrailingZeros().scale());
    }

    public static boolean isMinima(String tokenid) {
        return tokenid == null || MINIMA_TOKENID.equals(tokenid);
    }

    /**
     * Minima "token name" can be a plain string, or a JSON object {name:..,url:..},
     * or (for raw coin entries) nested. Pull a human-readable name out of whatever we get.
     */
    public static String tokenName(Object token, String tokenid) {
        if (isMinima(tokenid)) return "Minima";
        String n = resolveTokenName(token);
        // cache any REAL name we resolve so the history renderer can name this token later
        if (n != null && !n.isEmpty() && !n.equals("Token") && tokenid != null) TOKEN_NAMES.put(tokenid, n);
        return n == null || n.isEmpty() ? "Token" : n;
    }

    private static String resolveTokenName(Object token) {
        TokenMeta meta = TokenMeta.parse(token, "non-minima");
        return meta.ticker.isEmpty() ? meta.name : meta.ticker;
    }

    /**
     * The token's on-chain decimal grain. Minima floors every stored token amount to the token's
     * precision (10^-decimals), so any output amount MUST be quantized to this grain or the covenant's
     * exact-VERIFYOUT / product-invariant check reads a floored value and rejects the spend. MINIMA (0x00)
     * is full 44-dp precision. Defaults to 8 (mxUSDT) if the metadata is missing; clamped to [0,44].
     */
    public static int tokenDecimals(Object token) {
        JSONObject t = TokenMeta.asObject(token);
        if (t != null) {
            if (t.has("decimals")) return clampDecimals(t.optInt("decimals", 8));
            JSONObject inner = TokenMeta.asObject(t.opt("token"));
            if (inner != null && inner.has("decimals")) return clampDecimals(inner.optInt("decimals", 8));
        }
        return 8;
    }

    private static int clampDecimals(int d) { return d < 0 ? 0 : Math.min(d, 44); }

    /** Pull a txpowid out of a posted-transaction response, falling back to the given id. */
    public static String extractTxpowid(JSONObject json, String fallback) {
        JSONObject resp = json.optJSONObject("response");
        if (resp != null) {
            String t = resp.optString("txpowid", "");
            if (t.isEmpty()) {
                JSONObject txp = resp.optJSONObject("txpow");
                if (txp != null) t = txp.optString("txpowid", "");
            }
            if (!t.isEmpty()) return t;
        }
        return fallback;
    }

    /**
     * Quote a KISS script for a command param (newscript/runscript) WITHOUT escaping forward slashes.
     * org.json's {@code JSONObject.quote} escapes {@code /} to {@code \/}, which the KISS VM parser then
     * REJECTS — e.g. the covenant's {@code *3/1000} fee becomes {@code *3\/1000} → MinimaParseException →
     * the pool address can't be derived (breaks BOTH create and discovery). KISS scripts contain no
     * double-quote or backslash, but we escape those defensively anyway; we deliberately leave {@code /}.
     */
    public static String scriptArg(String script) {
        StringBuilder b = new StringBuilder("\"");
        for (int i = 0; i < script.length(); i++) {
            char c = script.charAt(i);
            if (c == '"' || c == '\\') b.append('\\');
            b.append(c);
        }
        return b.append('"').toString();
    }

    /** Parse a decimal string, returning {@code fallback} on null/blank/malformed input (never throws). */
    public static BigDecimal decOr(String s, BigDecimal fallback) {
        if (s == null || s.trim().isEmpty()) return fallback;
        try { return new BigDecimal(s.trim()); } catch (NumberFormatException e) { return fallback; }
    }

    /** Trim trailing zeros from a decimal amount string for tidy display. */
    public static String tidyAmount(String amt) {
        if (amt == null || amt.isEmpty()) return "0";
        if (!amt.contains(".")) return amt;
        String s = amt.replaceAll("0+$", "");
        if (s.endsWith(".")) s = s.substring(0, s.length() - 1);
        return s.isEmpty() ? "0" : s;
    }
}
