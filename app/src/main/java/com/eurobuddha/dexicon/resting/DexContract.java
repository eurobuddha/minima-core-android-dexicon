package com.eurobuddha.dexicon.resting;
/* Vendored from apks/pandadex app/src/main/java/com/eurobuddha/pandadex/DexContract.java (PandaDEX 0.4.23).
 * Keep as close to verbatim as possible; only the package, the NodeApi/SignGate imports and the minimal Dexicon
 * glue noted inline differ. Per the family naming rule the Minima dollar token is MxUSD in identifiers too
 * (PandaDEX MXUSD_ID/MXUSD_DP/*Mxusd are MXUSD_ID/MXUSD_DP/*Mxusd here). Re-copy rather than hand-edit when PandaDEX changes. */


import com.eurobuddha.dexicon.NodeApi;
import org.json.JSONObject;

/**
 * The FROZEN V5 partial-fill order-book covenant. Proven on-chain 2026-07-27 — see
 * contract/RESULTS.md (Phase A 134/134 math vectors, Phase B full lifecycle incl. buy-side
 * token leg, adversary 9/9 rejected). DO NOT EDIT THE SCRIPT — the address derives from it.
 *
 * State ports:
 *   0 ownerPk · 1 wantAddr · 2 wantAmt · 3 wantTok · 4 orderId · 5 side("0" buy/"1" sell)
 *   · 6 price (DISPLAY ONLY — taker-writable on remainders, never trust; derive from 2 and
 *   the coin amount) · 7 GTC ("1"/"0") · 8 minRemainder (contract-enforced dust floor).
 *
 * Spend paths: owner atomic re-lock (edit/renew, state-pinned except port 2/6) — owner
 * cancel refund — third-party expiry sweep (@COINAGE > EXPIRY_BLOCKS) — full fill (pay port 2 to
 * port 1) — partial fill (remainder at output @INPUT+1, cross-multiplied pro-rata payment).
 */
public final class DexContract {

    private DexContract() {}

    /** MxUSD — the only non-Minima token the book accepts (whitelisted in-script). */
    public static final String MXUSD_ID =
            "0x7D39745FBD29049BE29850B55A18BF550E4D442F930F86266E34193D89042A90";

    /**
     * THE VISIBILITY CEILING. A light node's `coins` search walks TREE NODES, and the tree is
     * trimmed at MINIMA_CASCADE_START_DEPTH = 1024 (GlobalParams.java:48). So no client can
     * see — let alone spend — a coin older than ~1024 blocks (~14h at 50s blocks) without an
     * archive/MegaMMR node. Every constant below is derived from that hard ceiling.
     *
     * The first cut of this contract used EXPIRY 1500 > 1024, which meant an order could age
     * out of visibility while still "live": un-cancellable, un-renewable, and NOT sweepable
     * by anyone (the expiry branch also needs the coin to be visible). The solo-node proofs
     * missed it because TestParams trims at 32 and the test expiry was 20 — the inequality
     * held by accident. Do not raise EXPIRY_BLOCKS above HORIZON_BLOCKS.
     */
    public static final int HORIZON_BLOCKS = 1024;

    /** Order lifetime in blocks (~8.3h) — comfortably inside the horizon, leaving a ~6h
     *  window in which a lapsed order is still visible and can be swept home by anyone. */
    public static final int EXPIRY_BLOCKS = 600;
    /** Renew a GTC order once it's this old (~2.8h) — a ~5.5h grace before expiry, so a
     *  phone that misses several renewal windows still recovers. */
    public static final int RENEW_AT = 200;
    /** Book scan depth: under the node's 1024-block trim (asking for more just wastes work). */
    public static final int SCAN_DEPTH = 1000;

    /** The frozen V5 script — byte-exact match of contract/v5script.tpl (mainnet dims). */
    public static final String SCRIPT_V5 =
            "LET u=" + MXUSD_ID + " "
            + "IF (@TOKENID NEQ 0x00 AND @TOKENID NEQ u) OR (PREVSTATE(3) NEQ 0x00 AND PREVSTATE(3) NEQ u) THEN RETURN FALSE ENDIF "
            + "IF SIGNEDBY(PREVSTATE(0)) THEN "
            + "IF GETOUTADDR(@INPUT) EQ @ADDRESS THEN "
            + "IF SAMESTATE(0 1) AND SAMESTATE(3 5) AND SAMESTATE(7 8) AND STATE(2) GT 0 AND VERIFYOUT(@INPUT @ADDRESS @AMOUNT @TOKENID TRUE) THEN RETURN TRUE ENDIF "
            + "ENDIF "
            + "IF VERIFYOUT(@INPUT PREVSTATE(1) @AMOUNT @TOKENID FALSE) THEN RETURN TRUE ENDIF "
            + "ENDIF "
            + "IF @COINAGE GT " + EXPIRY_BLOCKS + " THEN "
            + "RETURN VERIFYOUT(@INPUT PREVSTATE(1) @AMOUNT @TOKENID FALSE) "
            + "ENDIF "
            + "LET w=PREVSTATE(2) "
            + "LET r=0 "
            + "IF @TOTOUT GT @INPUT+1 THEN "
            + "IF GETOUTADDR(@INPUT+1) EQ @ADDRESS THEN "
            + "LET r=GETOUTAMT(@INPUT+1) "
            + "ENDIF "
            + "ENDIF "
            + "IF r EQ 0 THEN "
            + "RETURN VERIFYOUT(@INPUT PREVSTATE(1) w PREVSTATE(3) FALSE) "
            + "ENDIF "
            + "RETURN r LT @AMOUNT AND r GTE PREVSTATE(8) AND VERIFYOUT(@INPUT+1 @ADDRESS r @TOKENID TRUE) AND SAMESTATE(0 1) AND SAMESTATE(3 5) AND SAMESTATE(7 8) AND STATE(2)*@AMOUNT GTE w*r AND STATE(2) LTE w AND GETOUTADDR(@INPUT) EQ PREVSTATE(1) AND GETOUTTOK(@INPUT) EQ PREVSTATE(3) AND GETOUTAMT(@INPUT)*@AMOUNT GTE w*(@AMOUNT-r)";

    /** Pinned book address — derived from SCRIPT_V5 on the solo node and frozen.
     *  (The pre-review EXPIRY-1500 build used 0xCE5A0A3C…; that book is abandoned — it was
     *  never funded on mainnet — because 1500 exceeded the node's 1024-block visibility.) */
    public static final String ADDR_V5 =
            "0x2D43279DD85DABCA3EA90C9997DAB9169D8B7A0E8CB594236AF44542489774A5";
    public static final String ADDR_V5_MX =
            "MxG081D8CJPRM2TYF53TA8CJ6BTYE8MJM5NK3KCMMA26QNK8Y14H5RKKK2B0665";

    // Legacy Limit book (read/fill interop, LATER phase — constants only)
    public static final String ADDR_V4 =
            "0x94F2CB876903FAED64EA4C9C4B7FD602BAC5CD59F9EBC38AB0C4A0F0B346807F";

    public interface Ready {
        void ok();
        void failed(String why);
    }

    /**
     * The casino "headless-reveal" lesson + parseok gate in one place: verify the node has
     * the covenant REGISTERED and PARSED before any funds move. newscript is idempotent;
     * we re-verify via runscript (parseok + derived address must equal the pinned one) and
     * retry the registration once on mismatch of the tracked set.
     */
    public static void ensureScript(NodeApi node, Ready cb) {
        node.cmd("runscript script:" + Util.scriptArg(SCRIPT_V5), new NodeApi.Cb() {
            @Override public void onResult(JSONObject json) {
                JSONObject resp = json.optJSONObject("response");
                boolean parseok = resp != null && resp.optBoolean("parseok", false);
                JSONObject sc = resp == null ? null : resp.optJSONObject("script");
                String addr = sc == null ? "" : sc.optString("address", "");
                if (!parseok || !ADDR_V5.equalsIgnoreCase(addr)) {
                    cb.failed("covenant mismatch (parseok=" + parseok + " addr=" + addr + ")");
                    return;
                }
                // trackall:FALSE is load-bearing, not a tuning knob. With trackall:true the
                // node adds the covenant address to Wallet.mAllTrackedAddress, and
                // TxPoWTreeNode.checkRelevant returns true for ANY coin at a tracked address —
                // so `coins relevant:true` returns the WHOLE book and every stranger's order
                // reads as the user's own (poisoning P&L, notifications, portfolio value, and
                // starving GTC renewal on foreign orders). With trackall:false the node's
                // state-variable relevance scan still matches orders whose port 0 is our
                // pubkey or port 1 our address — exactly the intended ownership belt — and
                // taker fills are unaffected (txnutils.setMMRandScripts resolves the script
                // from the wallet's script table, which `newscript` populates either way).
                node.cmd("newscript trackall:false script:" + Util.scriptArg(SCRIPT_V5), new NodeApi.Cb() {
                    @Override public void onResult(JSONObject j2) {
                        if (j2.optBoolean("status", false)) cb.ok();
                        else cb.failed(j2.optString("error", "newscript failed"));
                    }
                    @Override public void onError(String m) { cb.failed(m); }
                });
            }
            @Override public void onError(String message) { cb.failed(message); }
        });
    }
}
