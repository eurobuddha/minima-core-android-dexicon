// VENDORED VERBATIM from apks/pandapools 0.9.88 app/src/main/java/com/eurobuddha/pandapools/ExitTicket.java by scripts/vendor-pandapools.sh — do not edit here; re-vendor.
package com.eurobuddha.pandapools;

import java.util.List;
import java.util.Locale;

/** A single-use exemption for an exact close or collection transaction; never a wallet unlock.
 * Unknown prior signature use remains a risk even for a disposable wallet. */
final class ExitTicket {

    /** Which of the two permitted signatures this ticket is for. */
    enum Stage {
        /** Spend both covenant reserve coins to the recipe's $OADR. */
        CLOSE,
        /** Move what landed at $OADR onward to a node-supplied default-64 wallet address. */
        FORWARD
    }

    /** Two leaves per exit: the close, plus the forward hop ($OADR is RETURN SIGNEDBY($OPK)). */
    static final int SIGNATURES_PER_EXIT = 2;

    /** Hard lifetime cap per covenant address, so re-issuing after failures can never become an open unlock. */
    static final int MAX_LIFETIME_SIGNATURES = 4;

    final String opk;
    final String address;      // covenant address
    final String oadr;         // owner payout address the covenant pins
    final String tok;          // token id of the non-MINIMA leg
    final String coinidM;
    final String coinidT;
    final String amountM;      // exact amt() strings, as the close will emit them
    final String amountT;
    final Stage stage;
    private List<String> forwardCommands;
    private String forwardCheck;

    /** Pin the independently validated payout inputs, full token sums and node wallet destination. */
    static ExitTicket forward(Pool p, String txid, List<String> coins,
                              java.util.Map<String, java.math.BigDecimal> amounts, String destination) {
        if (p == null || !FundingCoins.hex(p.opk) || !FundingCoins.hex(p.address)
                || !FundingCoins.hex(p.oadr) || !FundingCoins.hex(destination)
                || destination.equalsIgnoreCase(p.oadr) || !txid.matches("ppfwd_[a-zA-Z0-9_]+")
                || coins == null || coins.isEmpty() || coins.size() > FundingCoins.SAFE_COINS
                || amounts == null || amounts.isEmpty()) return null;
        java.util.Set<String> seen = new java.util.HashSet<>();
        List<String> expected = new java.util.ArrayList<>();
        expected.add("txncreate id:" + txid);
        for (String coin : coins) {
            if (!FundingCoins.hex(coin) || !seen.add(coin.toLowerCase(Locale.ROOT))) return null;
            expected.add("txninput id:" + txid + " coinid:" + coin);
        }
        for (java.util.Map.Entry<String, java.math.BigDecimal> amount : amounts.entrySet()) {
            if (!FundingCoins.hex(amount.getKey()) || amount.getValue() == null || amount.getValue().signum() <= 0) return null;
            String token = "0x00".equals(amount.getKey()) ? "" : " tokenid:" + amount.getKey();
            expected.add("txnoutput id:" + txid + " amount:" + PoolManager.amt(amount.getValue())
                    + " address:" + destination + token + " storestate:false");
        }
        expected.add("txnsign id:" + txid + " publickey:" + p.opk);
        expected.add("txnbasics id:" + txid);
        ExitTicket t = new ExitTicket(p.opk, p.address, p.oadr, p.tok, "", "", "", "", Stage.FORWARD);
        t.forwardCommands = java.util.Collections.unmodifiableList(expected);
        t.forwardCheck = "txncheck id:" + txid;
        return t;
    }

    ExitTicket(String opk, String address, String oadr, String tok,
               String coinidM, String coinidT, String amountM, String amountT, Stage stage) {
        this.opk = lower(opk); this.address = lower(address); this.oadr = lower(oadr); this.tok = lower(tok);
        this.coinidM = lower(coinidM); this.coinidT = lower(coinidT);
        this.amountM = amountM; this.amountT = amountT; this.stage = stage;
    }

    /* Eligibility is enforced, with per-condition messages, by RestoreExit.refusal(...) -
     * lifetime cap, baseSigningAllowed, a KNOWN use floor, and aged reserves - and pinned by
     * RestoreExitRefusalTest. A pure boolean mirror of it used to live here; it had already
     * drifted (it never learned the lifetime cap) and nothing in production called it, so it
     * was removed rather than left to drift further (0.9.63). */

    /** Whether this ticket authorises exactly the transaction {@code cmds} builds, for pool {@code p}. */
    boolean permits(List<String> cmds, Pool p) {
        if (cmds == null || cmds.isEmpty() || p == null) return false;
        if (!lower(p.address).equals(address) || !lower(p.opk).equals(opk)) return false;
        if (!lower(p.oadr).equals(oadr)) return false;
        if (stage == Stage.CLOSE) return permitsClose(cmds);
        if (forwardCommands == null) return false;
        if (cmds.equals(forwardCommands)) return true;
        return cmds.size() == forwardCommands.size() + 1
                && forwardCheck.equals(cmds.get(cmds.size() - 1))
                && cmds.subList(0, cmds.size() - 1).equals(forwardCommands);
    }

    /**
     * The close shape, verified against the command list itself:
     * one {@code ppclose_} transaction, exactly the two reserve coins in, exactly two outputs to $OADR with the
     * exact amounts, one owner signature, no state output, and nothing else of any kind.
     */
    boolean permitsClose(List<String> cmds) {
        String txid = null;
        int inputs = 0, outputs = 0, ownerSigns = 0, autoSigns = 0, creates = 0, basics = 0, checks = 0;
        boolean sawM = false, sawT = false, outM = false, outT = false;

        for (String c : cmds) {
            String verb = Cmd.verb(c);
            if (verb == null) return false;
            String id = Cmd.param(c, "id");
            if (id == null) return false;                             // every command must name exactly one txn
            if (txid == null) txid = id;
            else if (!txid.equals(id)) return false;                   // no splicing across two open transactions

            switch (verb) {
                case "txncreate":
                    creates++;
                    if (!id.startsWith("ppclose_")) return false;
                    break;
                case "txninput": {
                    inputs++;
                    String coin = lower(Cmd.param(c, "coinid"));
                    if (coin == null) return false;
                    if (coin.equals(coinidM) && !sawM) sawM = true;
                    else if (coin.equals(coinidT) && !sawT) sawT = true;
                    else return false;                                 // a third input, or a duplicate
                    break;
                }
                case "txnoutput": {
                    outputs++;
                    String addr = lower(Cmd.param(c, "address"));
                    String amount = Cmd.param(c, "amount");
                    String tokenid = Cmd.param(c, "tokenid");
                    String storestate = Cmd.param(c, "storestate");
                    if (addr == null || amount == null) return false;
                    if (!oadr.equals(addr)) return false;              // the covenant pins this; so do we
                    if (!"false".equals(storestate)) return false;     // no state riding along
                    if (tokenid == null) {                             // the MINIMA leg
                        if (outM || !amount.equals(amountM)) return false;
                        outM = true;
                    } else {                                           // the token leg
                        if (outT || !tok.equals(lower(tokenid)) || !amount.equals(amountT)) return false;
                        outT = true;
                    }
                    break;
                }
                case "txnsign": {
                    String key = Cmd.param(c, "publickey");
                    if (key == null) return false;
                    if ("auto".equals(key)) autoSigns++;
                    else if (opk.equals(lower(key))) ownerSigns++;
                    else return false;                                 // never a third party's key
                    break;
                }
                case "txnbasics": basics++; break;
                case "txncheck": checks++; break;
                default: return false;                                 // txnstate, txnpost, send, anything else
            }
        }
        return creates == 1 && inputs == 2 && sawM && sawT
                && outputs == 2 && outM && outT
                && ownerSigns == 1 && autoSigns <= 1
                && basics <= 1 && checks <= 1;
    }

    private static String lower(String s) { return s == null ? null : s.toLowerCase(Locale.ROOT); }
}
