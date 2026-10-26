// VENDORED VERBATIM from apks/atomix 0.1.84 app/src/test/java/com/eurobuddha/atomix/swap/HtlcTxnConstructionTest.java by scripts/vendor-atomix.sh — do not edit here; re-vendor.
package com.eurobuddha.atomix.swap;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import com.eurobuddha.comms.NodeApi;

import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * STEP: lock / claim / refund transaction construction — the exact node commands that move mxUSDT.
 *
 * There is no live node here: {@link NodeApi} is Mockito-doubled to (a) record every command string the
 * {@link MinimaHtlc} issues and (b) reply success synchronously, so the whole txn sequence is built and
 * captured on the test thread. We then assert the commands byte-for-byte on the fund-critical details:
 *
 *   - LOCK writes all seven HTLC state ports and grains the amount to 6dp;
 *   - CLAIM forces the 0.0001 notify coin to output 0 (the script's {@code VERIFYOUT(@INPUT 0xFFEEDD9999…)}),
 *     computes change from the coin's REAL value ({@code tokenamount}, not the ~1e-36 underlying), reveals
 *     the secret at state 100–103, and signs with the receiver key; and
 *   - REFUND returns the WHOLE locked value to the owner and signs with the owner key.
 *
 * The recurring invariant every assertion serves: token-in == token-out. Nothing is ever burned or stranded.
 */
public class HtlcTxnConstructionTest {

    private static final String HTLC_ADDR = MinimaHtlc.HTLC_ADDRESS;
    private static final String TOKEN = MinimaHtlc.USDT_TOKENID;
    private static final String NOTIFY = MinimaHtlc.NOTIFY;
    private static final String MY_ADDR = "MxMYADDR";
    private static final String MY_PK = "0x0BC0FFEE";

    private JSONObject checkReply;
    private NodeApi node;
    private MinimaHtlc htlc;
    private final List<String> commands = new ArrayList<>();

    @Before public void setUp() throws Exception {
        checkReply = validCheck();
        com.eurobuddha.comms.SignGate.resetForTest();   // lock/claim/refund route through the shared static gate
        node = mock(NodeApi.class);
        // Record each command and reply with a generic success carrying everything any step reads back.
        doAnswer(inv -> {
            String command = inv.getArgument(0);
            NodeApi.Cb cb = inv.getArgument(1);
            commands.add(command);
            JSONObject resp = new JSONObject();
            resp.put("status", true);
            resp.put("response", new JSONObject()
                    .put("txpowid", "0xTXPOW")
                    .put("miniaddress", MY_ADDR)
                    .put("publickey", MY_PK));
            cb.onResult(command.startsWith("txncheck ") ? checkReply : resp);
            return null;
        }).when(node).cmd(anyString(), any(NodeApi.Cb.class));

        htlc = new MinimaHtlc(node);
        // Reuse a persisted identity (non-null saved args) so setup only registers the script — no getaddress.
        final boolean[] ok = {false};
        htlc.setup(MY_ADDR, MY_PK, new MinimaHtlc.SetupCb() {
            @Override public void ok(String a, String p) { ok[0] = true; }
            @Override public void err(String m) { throw new AssertionError("setup failed: " + m); }
        });
        assertTrue("wallet identity must be ready after setup", ok[0]);
        commands.clear();   // drop the newscript command; each test asserts only its own sequence
    }

    private static JSONObject validCheck() throws Exception {
        return new JSONObject().put("status", true).put("response", new JSONObject()
                .put("valid", new JSONObject().put("basic", true).put("mmrproofs", true).put("scripts", 1))
                .put("validamounts", true).put("allsignaturesvalid", true).put("validtransaction", true));
    }

    private void checkedOperation(int kind, MinimaHtlc.PostCb cb) throws Exception {
        JSONObject coin = new JSONObject().put("coinid", "0xC1").put("tokenid", TOKEN).put("tokenamount", "1")
                .put("state", new JSONObject().put("0", MY_PK).put("4", MY_PK));
        if (kind == 0) htlc.lockFromCoins(java.util.Collections.singletonList("0xC1"), "1", "1", "1", "minima",
                MY_PK, "0xAB", "0xCD", 987654, "FALSE", cb);
        else if (kind == 1) htlc.claim(coin, "0xAB", "0xCD", cb);
        else htlc.refund(coin, cb);
    }

    @Test public void everyExplicitTransactionValidatesBeforePostingWithoutDuplicateProofs() throws Exception {
        for (int kind = 0; kind < 3; kind++) {
            commands.clear(); checkedOperation(kind, post());
            int sign = -1, basics = -1, check = -1, post = -1;
            for (int i = 0; i < commands.size(); i++) {
                String c = commands.get(i);
                if (c.startsWith("txnsign ")) sign = i;
                if (c.startsWith("txnbasics ")) basics = i;
                if (c.startsWith("txncheck ")) check = i;
                if (c.startsWith("txnpost ")) {
                    post = i; assertFalse(c.contains("auto:true")); assertTrue(c.contains("mine:true"));
                }
            }
            assertTrue(sign >= 0 && basics > sign && check > basics && post > check);
            assertEquals(1, count(commands, "txnbasics"));
        }
    }

    @Test public void missingOrRejectedValidationNeverPostsAnyExplicitTransaction() throws Exception {
        for (int kind = 0; kind < 3; kind++) {
            for (String flag : new String[]{"basic", "mmrproofs", "scripts", "validamounts", "allsignaturesvalid", "validtransaction"}) {
                for (Object value : new Object[]{false, JSONObject.NULL}) {
                    checkReply = validCheck();
                    JSONObject response = checkReply.getJSONObject("response");
                    JSONObject target = response.getJSONObject("valid").has(flag) ? response.getJSONObject("valid") : response;
                    target.put(flag, value);
                    commands.clear(); final boolean[] failed = {false};
                    checkedOperation(kind, new MinimaHtlc.PostCb() {
                        public void ok(String id) { throw new AssertionError("Invalid transaction posted"); }
                        public void err(String msg) { failed[0] = true; assertTrue(msg.contains("Nothing was posted")); }
                    });
                    assertTrue(failed[0]); assertEquals(0, count(commands, "txnpost"));
                }
            }
        }
    }

    // ---- LOCK ----

    @Test public void lockWritesAllSevenStatePortsAndGrainsAmount() throws Exception {
        htlc.lock("0.15", "150000", TOKEN_ERC20, "0xFEEDFACE", "0xBEEFCAFE",
                "0xDEADBEEF", 987654, "FALSE", post());

        String send = only(commands, "send");
        assertTrue("locks at the shared HTLC address", send.contains("address:" + HTLC_ADDR));
        assertTrue("locks the mxUSDT token, never native 0x00", send.contains("tokenid:" + TOKEN));
        assertTrue("amount is present and grained", send.contains("amount:0.15 "));

        JSONObject st = stateOf(send);
        assertEquals("port0 = owner (my refund key)", MY_PK, st.optString("0"));
        assertEquals("port1 = requested ERC20 amount", "150000", st.optString("1"));
        assertEquals("port2 = [reqToken]", "[" + TOKEN_ERC20 + "]", st.optString("2"));
        assertEquals("port3 = absolute timelock block", "987654", st.optString("3"));
        assertEquals("port4 = counterparty (who claims with the secret)", "0xFEEDFACE", st.optString("4"));
        assertEquals("port5 = hashlock", "0xDEADBEEF", st.optString("5"));
        assertEquals("port6 = owner ETH key", "0xBEEFCAFE", st.optString("6"));
        assertEquals("port7 = otc flag", "FALSE", st.optString("7"));
    }

    @Test public void lockGrainsAnOverPreciseAmountDown() throws Exception {
        htlc.lock("0.123456789", "1", TOKEN_ERC20, "0xAB", "0xCD", "0xEF", 1, "FALSE", post());
        String send = only(commands, "send");
        assertTrue("over-precise amount must be quantized DOWN to 6dp before it reaches the node",
                send.contains("amount:0.123456 "));
        assertFalse(send.contains("0.123456789"));
    }

    // ---- CLAIM ----

    @Test public void claimForcesNotifyFirstAndComputesChangeFromTokenAmount() throws Exception {
        // An mxUSDT HTLC coin. The decoy `amount` is a PLAUSIBLE non-tiny value (0.05) on purpose: if
        // coinAmount() regressed to read `amount` instead of `tokenamount`, change would be 0.0499 — still a
        // positive 2nd output — so the OUTPUT-COUNT check would NOT catch it. That forces the VALUE assertion
        // below (change == 0.1499) to be the load-bearing proof of the coloured-token fix.
        JSONObject coin = new JSONObject()
                .put("coinid", "0xC01DCA5E")
                .put("tokenid", TOKEN)
                .put("amount", "0.05")          // decoy underlying-carrier value — must be IGNORED
                .put("tokenamount", "0.15")     // the real coloured-token value — must be USED
                .put("state", new JSONObject().put("0", "0x0FFEC0DE").put("4", MY_PK));

        htlc.claim(coin, "0xDEADBEEF", "0xCAFEBABE", post());

        // the coin is consumed, not some other coin
        assertTrue("exactly one txncreate", count(commands, "txncreate") == 1);
        assertTrue("must input the HTLC coin by its own coinid", contains(commands, "txninput", "coinid:0xC01DCA5E"));

        List<String> outs = allContaining(commands, "txnoutput");
        assertEquals("claim emits exactly two outputs: notify + change", 2, outs.size());

        String notify = outs.get(0);
        String change = outs.get(1);
        assertTrue("output 0 MUST be the 0.0001 notify to " + NOTIFY + " (script VERIFYOUT @INPUT)",
                notify.contains("address:" + NOTIFY) && notify.contains("amount:0.0001 "));
        assertTrue("notify carries the coin's own token", notify.contains("tokenid:" + TOKEN));

        assertTrue("change goes back to me", change.contains("address:" + MY_ADDR));
        assertTrue("change = tokenamount(0.15) - 0.0001 notify = 0.1499 — proves the coloured-token VALUE "
                        + "was read (tokenamount), not the decoy `amount` (0.05, which would give 0.0499)",
                change.contains("amount:0.1499 "));

        // token-in == token-out, read from the ACTUAL captured outputs: notify + change == the coin's value.
        assertEquals("no burn: sum of outputs must equal the locked tokenamount",
                0, amountOf(notify).add(amountOf(change)).compareTo(new BigDecimal("0.15")));

        assertTrue("reveal the secret at state 100", contains(commands, "txnstate", "port:100 value:0xCAFEBABE"));
        assertTrue("echo the hashlock at state 101", contains(commands, "txnstate", "port:101 value:0xDEADBEEF"));
        assertTrue("owner echoed at state 102", contains(commands, "txnstate", "port:102 value:[0x0FFEC0DE]"));
        assertTrue("receiver echoed at state 103", contains(commands, "txnstate", "port:103 value:[" + MY_PK + "]"));
        assertTrue("must sign with the receiver key the script requires",
                contains(commands, "txnsign", "publickey:" + MY_PK));
        // Without the broadcast the secret is never revealed and the coin is trapped — assert it happens.
        assertTrue("must actually broadcast (txnpost) and clean up (txndelete)",
                contains(commands, "txnpost", "txndelete:true"));
    }

    @Test public void claimOfDustOnlyCoinEmitsNoChangeOutput() throws Exception {
        // A coin worth exactly the notify (0.0001) leaves no positive change → only the notify output.
        JSONObject coin = new JSONObject()
                .put("coinid", "0xD057").put("tokenid", TOKEN).put("tokenamount", "0.0001")
                .put("state", new JSONObject().put("0", "0x0FFEC0DE").put("4", MY_PK));
        htlc.claim(coin, "0xDEADBEEF", "0xCAFEBABE", post());
        List<String> outs = allContaining(commands, "txnoutput");
        assertEquals("only the notify output for a dust-only lock", 1, outs.size());
        assertTrue(outs.get(0).contains("address:" + NOTIFY));
    }

    // ---- REFUND ----

    @Test public void refundReturnsWholeValueToOwnerAndSignsAsOwner() throws Exception {
        JSONObject coin = new JSONObject()
                .put("coinid", "0xC01DCA5E")
                .put("tokenid", TOKEN)
                .put("amount", "0.000000000000000000000000000000000001")
                .put("tokenamount", "0.15")
                .put("state", new JSONObject().put("0", "0x0FFEC0DE").put("4", "0x50AD"));

        htlc.refund(coin, post());

        assertTrue("exactly one txncreate", count(commands, "txncreate") == 1);
        assertTrue("must input the HTLC coin by its own coinid", contains(commands, "txninput", "coinid:0xC01DCA5E"));

        List<String> outs = allContaining(commands, "txnoutput");
        assertEquals("refund is a single output — the whole coin back to me", 1, outs.size());
        assertTrue("refund returns the FULL tokenamount (no grain, no burn)",
                outs.get(0).contains("amount:0.15 ") && outs.get(0).contains("address:" + MY_ADDR));
        assertEquals("output value == the coin's whole value", 0,
                amountOf(outs.get(0)).compareTo(new BigDecimal("0.15")));
        assertTrue("refund uses the coin's own token", outs.get(0).contains("tokenid:" + TOKEN));
        assertTrue("must sign with the owner key from state[0] (the script's refund signer)",
                contains(commands, "txnsign", "publickey:0x0FFEC0DE"));
        assertTrue("must actually broadcast (txnpost) and clean up (txndelete)",
                contains(commands, "txnpost", "txndelete:true"));
    }

    // ---- LOCK-FROM-COINS (the responder's REAL mxUSDT counter-leg lock) ----

    @Test public void lockFromCoinsPinsCoinsBuildsStateAndConservesValue() throws Exception {
        // The ERC20->mxUSDT responder locks YOUR mxUSDT via this path (pinned coins, explicit change), NOT the
        // send-path lock(). It was previously untested despite being a primary fund path. Two coins totalling
        // 0.30 back a 0.123456789 lock; the >6dp amount must grain DOWN and the remainder return as change.
        // reqToken is the protocol's currency-agnostic literal "minima" here (NOT a hex token address) — this is
        // exactly what SwapEngine.lockMinimaCounterLeg passes, and the value that must NOT be rejected as non-hex.
        List<String> coinids = java.util.Arrays.asList("0xC1", "0xC2");
        htlc.lockFromCoins(coinids, "0.30", "0.123456789", "150000", "minima",
                "0xFEEDFACE", "0xBEEFCAFE", "0xDEADBEEF", 987654, "FALSE", post());

        assertTrue("exactly one txncreate", count(commands, "txncreate") == 1);
        assertTrue("pins the first coin", contains(commands, "txninput", "coinid:0xC1"));
        assertTrue("pins the second coin (combined to fund a larger deal)", contains(commands, "txninput", "coinid:0xC2"));

        // all seven HTLC state ports, exactly as the send-path lock() writes them
        assertTrue(contains(commands, "txnstate", "port:0 value:" + MY_PK));
        assertTrue(contains(commands, "txnstate", "port:1 value:150000"));
        assertTrue(contains(commands, "txnstate", "port:2 value:[minima]"));   // the mxUSDT leg's currency-agnostic marker
        assertTrue(contains(commands, "txnstate", "port:3 value:987654"));
        assertTrue(contains(commands, "txnstate", "port:4 value:0xFEEDFACE"));
        assertTrue(contains(commands, "txnstate", "port:5 value:0xDEADBEEF"));
        assertTrue(contains(commands, "txnstate", "port:6 value:0xBEEFCAFE"));
        assertTrue(contains(commands, "txnstate", "port:7 value:FALSE"));

        List<String> outs = allContaining(commands, "txnoutput");
        assertEquals("HTLC output + change", 2, outs.size());
        String htlcOut = outs.get(0), change = outs.get(1);
        assertTrue("HTLC output is the grained amount, held at the HTLC address, keeping state",
                htlcOut.contains("amount:0.123456 ") && htlcOut.contains("address:" + HTLC_ADDR)
                        && htlcOut.contains("tokenid:" + TOKEN) && htlcOut.contains("storestate:true"));
        assertTrue("change = totalSelected(0.30) - grained lock(0.123456) = 0.176544, back to me, no state",
                change.contains("amount:0.176544 ") && change.contains("address:" + MY_ADDR)
                        && change.contains("storestate:false"));

        // no burn: locked + change == the coins we spent
        assertEquals("locked + change == totalSelected", 0,
                amountOf(htlcOut).add(amountOf(change)).compareTo(new BigDecimal("0.30")));

        assertTrue("responder signs with auto (whichever default keys own the pinned coins)",
                contains(commands, "txnsign", "publickey:auto"));
        assertTrue("must broadcast and clean up", contains(commands, "txnpost", "txndelete:true"));
    }

    // ---- fail-safe: malformed / wrong-token coins ----

    @Test public void claimFailsSafeOnMissingCoinid() throws Exception {
        // A coin with no coinid must be REFUSED before any command is built — never a spend with an empty input.
        JSONObject coin = new JSONObject().put("tokenid", TOKEN).put("tokenamount", "0.15")
                .put("state", new JSONObject().put("0", "0x0FFEC0DE").put("4", MY_PK));
        final boolean[] erred = {false};
        htlc.claim(coin, "0xDEADBEEF", "0xCAFEBABE", new MinimaHtlc.PostCb() {
            @Override public void ok(String txpowid) { throw new AssertionError("must not build a spend"); }
            @Override public void err(String msg) { erred[0] = true; }
        });
        assertTrue("claim must call err on a coinid-less coin", erred[0]);
        assertTrue("no transaction may be built", commands.isEmpty());
    }

    @Test public void refundFailsSafeOnMissingCoinid() throws Exception {
        JSONObject coin = new JSONObject().put("tokenid", TOKEN).put("tokenamount", "0.15")
                .put("state", new JSONObject().put("0", "0x0FFEC0DE"));
        final boolean[] erred = {false};
        htlc.refund(coin, new MinimaHtlc.PostCb() {
            @Override public void ok(String txpowid) { throw new AssertionError("must not build a spend"); }
            @Override public void err(String msg) { erred[0] = true; }
        });
        assertTrue("refund must call err on a coinid-less coin", erred[0]);
        assertTrue("no transaction may be built", commands.isEmpty());
    }

    @Test public void claimUsesTheCoinsOwnTokenForEveryOutput() throws Exception {
        // Both the notify and the change output must carry the SAME token as the input coin — a token mismatch
        // between input and outputs would fail the script's VERIFYOUT(@TOKENID) or move the wrong asset.
        JSONObject coin = new JSONObject().put("coinid", "0xC01DCA5E").put("tokenid", TOKEN).put("tokenamount", "0.15")
                .put("state", new JSONObject().put("0", "0x0FFEC0DE").put("4", MY_PK));
        htlc.claim(coin, "0xDEADBEEF", "0xCAFEBABE", post());
        for (String out : allContaining(commands, "txnoutput"))
            assertTrue("every claim output carries the coin's token: " + out, out.contains("tokenid:" + TOKEN));
    }

    // ---- MA-19: command-injection defence (a space or non-hex byte in a peer/on-chain value would inject
    //      an extra parameter into a fund-moving node command) ----

    @Test public void lockRejectsCounterpartyKeyCarryingAnInjectedParameter() throws Exception {
        // A hostile maker's order-book mpk is signed (authorship) but never format-checked, so it can carry a
        // space. If it reached the `send`, "0xAA tokenid:0x00" would redirect the lock's token. Must be refused.
        final boolean[] erred = {false};
        htlc.lock("0.15", "150000", TOKEN_ERC20, "0xAA tokenid:0x00", "0xBEEFCAFE",
                "0xDEADBEEF", 1, "FALSE", errCb(erred));
        assertTrue("lock must call err on a counterparty key with a space", erred[0]);
        assertTrue("no command may be built", commands.isEmpty());
    }

    @Test public void lockRejectsNonHexHashlock() throws Exception {
        final boolean[] erred = {false};
        htlc.lock("0.15", "150000", TOKEN_ERC20, "0xFEEDFACE", "0xBEEFCAFE",
                "not-a-hash", 1, "FALSE", errCb(erred));
        assertTrue("lock must call err on a non-hex hashlock", erred[0]);
        assertTrue("no command may be built", commands.isEmpty());
    }

    @Test public void claimRejectsCoinStateCarryingAnInjectedParameter() throws Exception {
        // owner/receiver come from a coin at the anyone-can-write shared HTLC address. An owner value with a
        // space would inject into txnstate port:102 (and STRING(owner) in the script). Must be refused.
        JSONObject coin = new JSONObject()
                .put("coinid", "0xC01DCA5E").put("tokenid", TOKEN).put("tokenamount", "0.15")
                .put("state", new JSONObject().put("0", "0x0FFEC0DE mine:true").put("4", MY_PK));
        final boolean[] erred = {false};
        htlc.claim(coin, "0xDEADBEEF", "0xCAFEBABE", errCb(erred));
        assertTrue("claim must call err on an injected owner state", erred[0]);
        assertTrue("no command may be built", commands.isEmpty());
    }

    @Test public void refundRejectsCoinTokenidCarryingAnInjectedParameter() throws Exception {
        JSONObject coin = new JSONObject()
                .put("coinid", "0xC01DCA5E").put("tokenid", "0x00 address:MxEVIL").put("tokenamount", "0.15")
                .put("state", new JSONObject().put("0", "0x0FFEC0DE"));
        final boolean[] erred = {false};
        htlc.refund(coin, errCb(erred));
        assertTrue("refund must call err on an injected tokenid", erred[0]);
        assertTrue("no command may be built", commands.isEmpty());
    }

    @Test public void refundRejectsNonDecimalCoinAmount() throws Exception {
        // coinAmount() reads the coin's own (attacker-writable) tokenamount; a space there would inject into
        // the txnoutput amount: field. Must be refused before any command is built.
        JSONObject coin = new JSONObject()
                .put("coinid", "0xC01DCA5E").put("tokenid", TOKEN).put("tokenamount", "0.15 address:MxEVIL")
                .put("state", new JSONObject().put("0", "0x0FFEC0DE"));
        final boolean[] erred = {false};
        htlc.refund(coin, errCb(erred));
        assertTrue("refund must call err on a non-decimal coin amount", erred[0]);
        assertTrue("no command may be built", commands.isEmpty());
    }

    // ---- M1: the fund-locking `send` must serialise through SignGate like claim/refund ----

    @Test public void lockSerialisesThroughSignGate() throws Exception {
        // A node that CAPTURES the `send` callback instead of firing it, so the first lock stays in-flight in
        // the gate. Setup commands (newscript/keys) fire immediately so the wallet is ready.
        com.eurobuddha.comms.SignGate.resetForTest();
        final List<String> cmds = new ArrayList<>();
        final List<NodeApi.Cb> pendingSends = new ArrayList<>();
        NodeApi n2 = mock(NodeApi.class);
        doAnswer(inv -> {
            String c = inv.getArgument(0);
            NodeApi.Cb cb = inv.getArgument(1);
            cmds.add(c);
            if (c.startsWith("send")) { pendingSends.add(cb); return null; }   // capture, keep the gate busy
            cb.onResult(new JSONObject().put("status", true).put("response",
                    new JSONObject().put("miniaddress", MY_ADDR).put("publickey", MY_PK)));   // setup fires now
            return null;
        }).when(n2).cmd(anyString(), any(NodeApi.Cb.class));

        MinimaHtlc h2 = new MinimaHtlc(n2);
        h2.setup(MY_ADDR, MY_PK, new MinimaHtlc.SetupCb() {
            @Override public void ok(String a, String p) {} @Override public void err(String m) { throw new AssertionError(m); }
        });

        h2.lock("0.15", "150000", TOKEN_ERC20, "0xFEEDFACE", "0xBEEFCAFE", "0xDEADBEEF", 1, "FALSE", post());
        h2.lock("0.16", "150000", TOKEN_ERC20, "0xFEEDFACE", "0xBEEFCAFE", "0xDEADBEEF", 1, "FALSE", post());
        assertEquals("the second lock's send must be GATED until the first frees", 1, countStartsWith(cmds, "send"));

        // free the first lock's send → the gate advances → the second lock's send is now issued
        pendingSends.get(0).onResult(new JSONObject().put("status", true).put("response", new JSONObject().put("txpowid", "0xTX")));
        assertEquals("first freed → second lock's send issued", 2, countStartsWith(cmds, "send"));
        pendingSends.get(1).onResult(new JSONObject().put("status", true).put("response", new JSONObject().put("txpowid", "0xTX")));
    }

    private static int countStartsWith(List<String> cmds, String verb) {
        int n = 0; for (String c : cmds) if (c.startsWith(verb)) n++; return n;
    }

    // ---- helpers ----

    private static final String TOKEN_ERC20 = "0xdac17f958d2ee523a2206206994597c13d831ec7";

    /** A PostCb that records an err() and fails the test if a command is instead built (ok()). */
    private static MinimaHtlc.PostCb errCb(final boolean[] erred) {
        return new MinimaHtlc.PostCb() {
            @Override public void ok(String txpowid) { throw new AssertionError("must not build a spend"); }
            @Override public void err(String msg) { erred[0] = true; }
        };
    }

    private MinimaHtlc.PostCb post() {
        return new MinimaHtlc.PostCb() {
            @Override public void ok(String txpowid) { assertNotNull(txpowid); }
            @Override public void err(String msg) { throw new AssertionError("unexpected err: " + msg); }
        };
    }

    /** The single command starting with {@code verb} (fails if not exactly one). */
    private static String only(List<String> cmds, String verb) {
        String found = null;
        for (String c : cmds) if (c.startsWith(verb)) {
            if (found != null) throw new AssertionError("more than one '" + verb + "' command");
            found = c;
        }
        assertNotNull("no '" + verb + "' command issued", found);
        return found;
    }

    /** All commands whose text contains {@code needle}, in issue order (e.g. the txnoutputs of a claim). */
    private static List<String> allContaining(List<String> cmds, String needle) {
        List<String> out = new ArrayList<>();
        for (String c : cmds) if (c.contains(needle)) out.add(c);
        return out;
    }

    private static boolean contains(List<String> cmds, String verb, String needle) {
        for (String c : cmds) if (c.startsWith(verb) && c.contains(needle)) return true;
        return false;
    }

    private static int count(List<String> cmds, String verb) {
        int n = 0;
        for (String c : cmds) if (c.startsWith(verb)) n++;
        return n;
    }

    /** The numeric amount from a {@code txnoutput …} command — the value between {@code amount:} and the next
     *  space. Reading the ACTUAL captured amount (rather than a literal) is what makes the no-burn checks real. */
    private static BigDecimal amountOf(String txnoutput) {
        int s = txnoutput.indexOf("amount:") + "amount:".length();
        int e = txnoutput.indexOf(' ', s);
        return new BigDecimal(txnoutput.substring(s, e));
    }

    /** Pull the {@code state:{…}} JSON object out of a lock's send command. */
    private static JSONObject stateOf(String send) throws Exception {
        int s = send.indexOf("state:") + "state:".length();
        int e = send.indexOf(" tokenid:", s);
        return new JSONObject(send.substring(s, e));
    }
}
