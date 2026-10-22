package com.eurobuddha.dexicon.resting;
/* Vendored from apks/pandadex app/src/main/java/com/eurobuddha/pandadex/Order5.java (PandaDEX 0.4.23).
 * Keep as close to verbatim as possible; only the package, the NodeApi/SignGate imports and the minimal Dexicon
 * glue noted inline differ. Per the family naming rule the Minima dollar token is MxUSD in identifiers too
 * (PandaDEX MXUSD_ID/MXUSD_DP/*Mxusd are MXUSD_ID/MXUSD_DP/*Mxusd here). Re-copy rather than hand-edit when PandaDEX changes. */


import org.json.JSONArray;
import org.json.JSONObject;

import java.math.BigDecimal;
import java.util.Set;

/**
 * One live order coin on the V5 book. Parsed defensively from a `coins` row: token coin
 * VALUE is `tokenamount` (raw `amount` is the sub-grain coloured underlying — reading it is
 * a fund bug, see usdtSwap lesson); price is ALWAYS derived from port 2 / locked amount
 * (port 6 is display-only and taker-writable on remainders).
 */
public final class Order5 {

    public final String coinid;
    public final String ownerPk;      // port 0
    public final String wantAddr;     // port 1
    public final BigDecimal wantAmt;  // port 2
    public final String wantTok;      // port 3 (0x00 or MxUSD)
    public final String orderId;      // port 4
    public final boolean sell;        // port 5 "1" = sell (MINIMA locked)
    public final boolean gtc;         // port 7
    public final BigDecimal minRem;   // port 8
    public final BigDecimal locked;   // tokenamount||amount of the coin
    public final String lockedTok;    // coin tokenid
    public final long created;        // block the coin was created in
    private String sourceJson;       // exact parsed coin, reused by durable recovery
    private boolean relevant;         // node-side ownership belt (coins relevant:true)

    private Order5(String coinid, String ownerPk, String wantAddr, BigDecimal wantAmt,
                   String wantTok, String orderId, boolean sell, boolean gtc,
                   BigDecimal minRem, BigDecimal locked, String lockedTok, long created) {
        this.coinid = coinid;
        this.ownerPk = ownerPk;
        this.wantAddr = wantAddr;
        this.wantAmt = wantAmt;
        this.wantTok = wantTok;
        this.orderId = orderId;
        this.sell = sell;
        this.gtc = gtc;
        this.minRem = minRem;
        this.locked = locked;
        this.lockedTok = lockedTok;
        this.created = created;
    }

    /** Parse a `coins` row; returns null for anything malformed (never throws). */
    public static Order5 from(JSONObject coin) {
        try {
            String coinid = coin.optString("coinid", "");
            if (coinid.isEmpty()) return null;
            String[] st = new String[9];
            Object stateObj = coin.opt("state");
            if (stateObj instanceof JSONArray) {
                JSONArray arr = (JSONArray) stateObj;
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject e = arr.optJSONObject(i);
                    if (e == null) continue;
                    int p = e.optInt("port", -1);
                    if (p >= 0 && p < 9) st[p] = e.optString("data", null);
                }
            } else if (stateObj instanceof JSONObject) {   // simplestate:true map form
                JSONObject map = (JSONObject) stateObj;
                for (int p = 0; p < 9; p++) {
                    if (map.has(String.valueOf(p))) st[p] = map.optString(String.valueOf(p), null);
                }
            }
            if (st[0] == null || st[1] == null || st[2] == null || st[4] == null) return null;
            String tokenid = coin.optString("tokenid", "0x00");
            String amt = coin.optString("tokenamount", "");
            if ("0x00".equals(tokenid)) amt = coin.optString("amount", "");
            BigDecimal locked = Util.decOr(amt, null);
            if (!DexTxn.amountOk(locked)) return null;
            BigDecimal want = Util.decOr(st[2], null);
            if (!DexTxn.amountOk(want)) return null;
            if (!("0".equals(st[5]) || "1".equals(st[5])) || !("0".equals(st[7]) || "1".equals(st[7]))) return null;
            boolean sell = "1".equals(st[5]);
            boolean gtc = "1".equals(st[7]);
            BigDecimal minRem = Util.decOr(st[8], null);
            if (minRem == null) return null;
            long created = positiveLong(coin, "created");
            String wantTok = st[3] == null ? "0x00" : st[3];
            Order5 order = new Order5(coinid, st[0], st[1], want, wantTok, st[4], sell, gtc, minRem,
                    locked, tokenid, created);
            order.sourceJson = coin.toString();
            return order;
        } catch (Throwable t) {
            return null;
        }
    }

    String sourceJson() { return sourceJson; }

    /** Inlined from PandaDEX ChainEvidence.positiveLong (0.4.23). */
    static long positiveLong(JSONObject object, String field) {
        if (object == null) return 0;
        try {
            String value = object.get(field).toString();
            if (!value.matches("[0-9]{1,19}")) return 0;
            long number = Long.parseLong(value);
            return number > 0 ? number : 0;
        } catch (Exception invalid) { return 0; }
    }

    public void markRelevant() { relevant = true; }

    /**
     * Chain-derived ownership. The KEY MATCH is authoritative; the node-side relevance flag is
     * only a corroborating belt and can NEVER stand alone — with a trackall-registered script
     * the node marks every coin at the address relevant, which would make every stranger's
     * order read as ours (poisoned P&L, false fill alerts, starved renewals). We register with
     * trackall:false, but a node that tracked the address for any other reason must not be
     * able to mislead us, so the key set is required either way.
     */
    public boolean isMine(Set<String> myKeys) {
        return myKeys != null && myKeys.contains(ownerPk);
    }

    /**
     * Ownership for anything that SPENDS or ATTRIBUTES: spend authority (port 0) plus payout
     * (port 1). Port 0 is public, so the key alone proves only that the coin names me — a
     * stranger can author one. See PandaDEX KeySet#owns. An empty address set cannot establish ownership; wait for a fresh wallet identity.
     */
    public boolean isMine(Set<String> myKeys, Set<String> myAddrs) {
        if (!isMine(myKeys)) return false;
        return myAddrs != null && myAddrs.contains(wantAddr);
    }

    /** True when the node also considers this coin relevant — corroboration for diagnostics. */
    public boolean nodeRelevant() { return relevant; }

    /** MINIMA side of the order (locked for sells, wanted for buys). */
    public BigDecimal minimaAmount() { return sell ? locked : wantAmt; }

    /** MxUSD side of the order (wanted for sells, locked for buys). */
    public BigDecimal mxusdAmount() { return sell ? wantAmt : locked; }

    /** Price in MxUSD per MINIMA — derived from enforced amounts only. */
    public BigDecimal price() {
        return PriceMath.price(mxusdAmount(), minimaAmount().signum() == 0 ? BigDecimal.ONE : minimaAmount());
    }

    /**
     * Is this order safe to include in a sweep? Resting orders are authored by STRANGERS, and
     * a single malformed one silently kills the whole transaction (consensus rejection is
     * silent — the txn posts and simply never mines), so one hostile order could otherwise
     * block every sweep on the book for the price of a dust coin.
     *
     * Rejects:
     *  - a want-token that contradicts the side (a SELL wanting 0x00 renders as the cheapest
     *    ask, gets picked first, and makes the app pay MINIMA it never funded)
     *  - a payout address pointing at the book itself (turns the preceding order's payment
     *    output into a "remainder" and flips that input into the partial branch)
     *  - amounts finer than the on-chain grain (the full-fill VERIFYOUT wants an exact amount
     *    that floors on-chain; a min-remainder with hidden dust can trip the floor by a grain)
     *  - a locked token that contradicts the side.
     */
    public boolean fillable() {
        if (!DexTxn.safeOrder(this)) return false;
        String expectedWant = sell ? DexContract.MXUSD_ID : "0x00";
        if (!expectedWant.equalsIgnoreCase(wantTok)) return false;
        String expectedLocked = sell ? "0x00" : DexContract.MXUSD_ID;
        if (!expectedLocked.equalsIgnoreCase(lockedTok)) return false;
        if (wantAddr == null || !wantAddr.startsWith("0x") || wantAddr.length() != 66) return false;
        if (wantAddr.equalsIgnoreCase(DexContract.ADDR_V5)) return false;
        int wantDp = sell ? PriceMath.MXUSD_DP : PriceMath.MINIMA_DP;
        if (wantAmt.stripTrailingZeros().scale() > wantDp) return false;
        int lockDp = sell ? PriceMath.MINIMA_DP : PriceMath.MXUSD_DP;
        if (minRem.stripTrailingZeros().scale() > lockDp) return false;
        if (minRem.signum() < 0) return false;
        return locked.stripTrailingZeros().scale() <= lockDp;
    }

    public long age(long chainBlock) { return created <= 0 ? 0 : Math.max(0, chainBlock - created); }

    /** A display label cannot treat a missing or earlier tip as an observed age of zero. */
    public String ageLabel(long chainBlock, boolean current) {
        if (created <= 0 || chainBlock <= 0 || chainBlock < created) return "age unavailable";
        return "age " + age(chainBlock) + " blk" + (current ? "" : " at last check");
    }

    public boolean expired(long chainBlock) { return age(chainBlock) > DexContract.EXPIRY_BLOCKS; }

    public boolean renewDue(long chainBlock) { return gtc && age(chainBlock) >= DexContract.RENEW_AT; }
}
