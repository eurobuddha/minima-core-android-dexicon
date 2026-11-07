// VENDORED VERBATIM from apks/pandapools 0.9.89 app/src/main/java/com/eurobuddha/pandapools/TokenBalance.java by scripts/vendor-pandapools.sh — do not edit here; re-vendor.
package com.eurobuddha.pandapools;

import org.json.JSONObject;

/** One row of the node's "balance" command: an aggregate per token, with full metadata.
 *  Ported from the utxoWallet. `sendable` is the amount free OUTSIDE any contract (i.e. not locked in a
 *  pool) — exactly the "available to pool" figure the Wallet tab surfaces. */
public class TokenBalance {

    public String tokenid;
    public TokenMeta meta;
    public String name;          // convenience = meta.name
    public String confirmed;
    public String unconfirmed;
    public String sendable;
    public String total;
    public int coins;

    public static TokenBalance from(JSONObject b) {
        TokenBalance t = new TokenBalance();
        t.tokenid     = b.optString("tokenid", Util.MINIMA_TOKENID);
        t.meta        = TokenMeta.parse(b.opt("token"), t.tokenid);
        t.name        = t.meta.name;
        t.confirmed   = b.optString("confirmed", "0");
        t.unconfirmed = b.optString("unconfirmed", "0");
        t.sendable    = b.optString("sendable", t.confirmed);
        t.total       = b.optString("total", t.confirmed);
        t.coins       = b.optInt("coins", 0);
        // balance entries sometimes carry decimals at the top level
        if (t.meta.decimals.isEmpty()) t.meta.decimals = b.optString("decimals", "");
        return t;
    }

    /**
     * Contract-locked = {@code confirmed − sendable}.
     *
     * {@code sendable} counts only simple-address coins; {@code confirmed} counts every confirmed coin
     * INCLUDING those locked in a contract. In PandaPools a pool's reserves sit at its covenant address, so
     * the difference IS the liquidity you have committed. It is derived, not node-supplied — which is why
     * it is always shown with a "≈".
     *
     * Returns {@code "—"} if either side won't parse, matching AtomiX rather than implying a real zero.
     */
    public String locked() {
        try {
            java.math.BigDecimal l = new java.math.BigDecimal(confirmed).subtract(new java.math.BigDecimal(sendable));
            return Util.tidyAmount((l.signum() > 0 ? l : java.math.BigDecimal.ZERO).toPlainString());
        } catch (Exception e) { return "—"; }
    }

    /**
     * The one-line balance breakdown — a port of AtomiX's {@code minimaBreakdown()}, whose comment is the
     * whole reason it exists: "so the displayed numbers are never a black box".
     *
     * Every figure is shown unconditionally, zeros included. Hiding a zero is what made the old display
     * unreadable: on a node whose funds are all in a pool you saw a headline 0 and no explanation.
     */
    public String breakdown(long lastUpdateMs, long nowMs) {
        String ago = lastUpdateMs <= 0 ? "never" : Math.max(0, (nowMs - lastUpdateMs) / 1000) + "s ago";
        return "confirmed " + Util.tidyAmount(confirmed)
             + "  ·  locked ≈ " + locked()
             + "  ·  unconfirmed " + Util.tidyAmount(unconfirmed)
             + "  ·  " + coins + " coins"
             + "  ·  updated " + ago
             + "  ·  tap for coins";
    }

    public boolean isMinima() { return Util.isMinima(tokenid); }

    public boolean hasIcon() { return meta != null && meta.iconUrl != null && !meta.iconUrl.isEmpty(); }

    /** An NFT: a non-Minima token of total supply 1 with no fractional decimals. */
    public boolean isNft() {
        if (isMinima()) return false;
        String d = meta == null ? "" : meta.decimals;
        boolean whole = d.isEmpty() || d.equals("0");
        return whole && "1".equals(total == null ? "" : total.trim());
    }
}
