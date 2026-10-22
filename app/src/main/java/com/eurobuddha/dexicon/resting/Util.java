package com.eurobuddha.dexicon.resting;
/* Vendored from apks/pandadex app/src/main/java/com/eurobuddha/pandadex/Util.java (PandaDEX 0.4.23).
 * Keep as close to verbatim as possible; only the package, the NodeApi/SignGate imports and the minimal Dexicon
 * glue noted inline differ. Per the family naming rule the Minima dollar token is MxUSD in identifiers too
 * (PandaDEX MXUSD_ID/MXUSD_DP/*Mxusd are MXUSD_ID/MXUSD_DP/*Mxusd here). Re-copy rather than hand-edit when PandaDEX changes. */


import org.json.JSONObject;

import java.math.BigDecimal;

public final class Util {

    public static final String MINIMA_TOKENID = "0x00";

    private Util() {}

    public static boolean isMinima(String tokenid) {
        return tokenid == null || MINIMA_TOKENID.equals(tokenid);
    }


    /**
     * Quote a KISS script for embedding in a node command. NEVER use JSONObject.quote for this:
     * it escapes '/' as '\/', which the node stores verbatim, making the covenant unparseable —
     * coins at such an address are PERMANENTLY unspendable (confirmed mainnet loss 2026-07-14).
     * Only '"' and '\' are escaped here.
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

    /** Trim trailing zeros from a decimal amount string for tidy display. */
    public static String tidyAmount(String amt) {
        if (amt == null || amt.isEmpty()) return "0";
        if (!amt.contains(".")) return amt;
        String s = amt.replaceAll("0+$", "");
        if (s.endsWith(".")) s = s.substring(0, s.length() - 1);
        return s.isEmpty() ? "0" : s;
    }

    /** Format a number to a tidy Minima amount (drops trailing zeros, like the dapp's miniNum). */
    public static String miniNum(BigDecimal v) {
        if (v == null) return "0";
        return tidyAmount(v.stripTrailingZeros().toPlainString());
    }

    /** Parse a possibly-empty amount string to BigDecimal, defaulting to zero. */
    public static BigDecimal dec(String s) {
        try {
            if (s == null || s.length() > 100 || s.isEmpty()) return BigDecimal.ZERO;
            String t = s.trim();
            // Tolerate a locale comma decimal without mangling a grouping comma: if a '.' is present it's
            // the decimal and commas are grouping (strip them); otherwise a lone ',' IS the decimal.
            t = (t.indexOf('.') >= 0) ? t.replace(",", "") : t.replace(',', '.');
            return decOr(t, BigDecimal.ZERO);
        } catch (Exception e) { return BigDecimal.ZERO; }
    }

    /** Parse a decimal string, returning {@code fallback} on null/blank/malformed input (never throws). */
    public static BigDecimal decOr(String s, BigDecimal fallback) {
        return boundedDecimal(s, fallback, 44);
    }

    /** Stock MiniNumber balances AND coin amounts allow 64 significant digits, including 44
     * decimal places. Every amount the node reports is parsed here; order/transaction amounts the
     * app builds keep the stricter 44-digit decOr/DexTxn.amountOk limit. */
    static BigDecimal balanceDecimal(Object raw) {
        if (!(raw instanceof String) && !(raw instanceof Number)) return null;
        return boundedDecimal(raw.toString(), null, 64);
    }

    private static BigDecimal boundedDecimal(String s, BigDecimal fallback, int precision) {
        if (s == null || s.length() > 100 || s.trim().isEmpty()) return fallback;
        try {
            BigDecimal value = new BigDecimal(s.trim());
            return Math.abs((long) value.scale()) <= 44 && value.precision() <= precision ? value : fallback;
        } catch (NumberFormatException e) { return fallback; }
    }
}
