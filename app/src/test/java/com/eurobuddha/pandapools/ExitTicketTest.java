// VENDORED VERBATIM from apks/pandapools 0.9.80 app/src/test/java/com/eurobuddha/pandapools/ExitTicketTest.java by scripts/vendor-pandapools.sh — do not edit here; re-vendor.
package com.eurobuddha.pandapools;

import org.junit.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The anti-widening suite: an exit ticket must authorise EXACTLY the close it was issued for, and nothing else.
 *
 * This is the load-bearing test file for the restore auto-withdraw. The exception waives one condition
 * ({@code signingStateUnverified}) for one transaction shape, and the whole safety argument is that the shape
 * cannot be stretched. So the tests here are written as attacks: take a legitimate close, change one thing, and
 * require a refusal. Several of them feed in the verbatim command lists that keep-fresh, migrate and deposit
 * build, because "the exception cannot become a general signing unlock" is exactly the claim that matters.
 *
 * The covenant already bounds the damage — its SIGNEDBY($OPK) branch can only exit to $OADR or grow in place, so
 * a widened exception cannot steal funds. What it could do is spend extra one-time signatures on a key whose
 * history is unproven, which is the Winternitz reuse hazard the quarantine exists for. Hence a shape, a quota,
 * and this file.
 */
public class ExitTicketTest {

    private static String h(String c) { return "0x" + String.join("", Collections.nCopies(64, c)); }

    private static final String OPK = h("1"), OADR = h("2"), TOK = h("3"), ADDR = h("4");
    private static final String COIN_M = h("a"), COIN_T = h("b"), OTHER = h("c");
    private static final String AMT_M = "487.0099300309", AMT_T = "1.84920223";
    private static final String TXID = "ppclose_7";

    private static Pool pool() {
        Pool p = new Pool();
        p.address = ADDR; p.opk = OPK; p.oadr = OADR; p.tok = TOK; p.tokDecimals = 8; p.kmin = "50";
        p.coinidM = COIN_M; p.coinidT = COIN_T;
        p.reserveM = new BigDecimal(AMT_M); p.reserveT = new BigDecimal(AMT_T);
        p.minimumOwnerUses = 590;
        p.reserveBlock = 1000;
        return p;
    }

    private static ExitTicket ticket() {
        return new ExitTicket(OPK, ADDR, OADR, TOK, COIN_M, COIN_T, AMT_M, AMT_T, ExitTicket.Stage.CLOSE);
    }

    /** Exactly what PoolManager.close builds. */
    private static List<String> close() {
        return new ArrayList<>(Arrays.asList(
                "txncreate id:" + TXID,
                "txninput id:" + TXID + " coinid:" + COIN_M,
                "txninput id:" + TXID + " coinid:" + COIN_T,
                "txnoutput id:" + TXID + " amount:" + AMT_M + " address:" + OADR + " storestate:false",
                "txnoutput id:" + TXID + " amount:" + AMT_T + " address:" + OADR + " tokenid:" + TOK + " storestate:false",
                "txnsign id:" + TXID + " publickey:auto",
                "txnsign id:" + TXID + " publickey:" + OPK,
                "txnbasics id:" + TXID));
    }

    // ---- the one thing it must allow ----

    @Test public void permitsExactlyTheCloseItWasIssuedFor() {
        assertTrue(ticket().permits(close(), pool()));
    }

    @Test public void permitsTheSameCloseWithoutTheAutoSignature() {
        List<String> c = close();
        c.remove("txnsign id:" + TXID + " publickey:auto");
        assertTrue(ticket().permits(c, pool()));
    }

    // ---- other transaction shapes ----

    @Test public void refusesAKeepFreshRefreshTransaction() {
        // The verbatim shape PoolManager.refresh builds: recreates the reserves at the SAME covenant address and
        // posts a beacon. This is the proof the exception can never become keep-fresh, which is what would keep
        // a quarantined key signing indefinitely.
        List<String> c = Arrays.asList(
                "txncreate id:ppref_7",
                "txninput id:ppref_7 coinid:" + COIN_M,
                "txninput id:ppref_7 coinid:" + COIN_T,
                "txnoutput id:ppref_7 amount:" + AMT_M + " address:" + ADDR + " storestate:false",
                "txnoutput id:ppref_7 amount:" + AMT_T + " address:" + ADDR + " tokenid:" + TOK + " storestate:false",
                "txnstate id:ppref_7 port:0 value:" + OPK,
                "txnsign id:ppref_7 publickey:" + OPK,
                "txnbasics id:ppref_7");
        assertFalse(ticket().permits(c, pool()));
    }

    @Test public void refusesAMigrateTransaction() {
        List<String> c = Arrays.asList(
                "txncreate id:ppmig_7",
                "txninput id:ppmig_7 coinid:" + COIN_M,
                "txninput id:ppmig_7 coinid:" + COIN_T,
                "txninput id:ppmig_7 coinid:" + OTHER,
                "txnoutput id:ppmig_7 amount:" + AMT_M + " address:" + OADR + " storestate:false",
                "txnoutput id:ppmig_7 amount:" + AMT_T + " address:" + OADR + " tokenid:" + TOK + " storestate:false",
                "txnoutput id:ppmig_7 amount:1 address:" + h("9") + " storestate:false",
                "txnsign id:ppmig_7 publickey:" + OPK,
                "txnbasics id:ppmig_7");
        assertFalse(ticket().permits(c, pool()));
    }

    @Test public void refusesAnAddLiquidityTransaction() {
        List<String> c = Arrays.asList(
                "txncreate id:ppadd_7",
                "txninput id:ppadd_7 coinid:" + COIN_M,
                "txninput id:ppadd_7 coinid:" + COIN_T,
                "txninput id:ppadd_7 coinid:" + OTHER,
                "txnoutput id:ppadd_7 amount:999 address:" + ADDR + " storestate:false",
                "txnoutput id:ppadd_7 amount:999 address:" + ADDR + " tokenid:" + TOK + " storestate:false",
                "txnsign id:ppadd_7 publickey:" + OPK,
                "txnbasics id:ppadd_7");
        assertFalse(ticket().permits(c, pool()));
    }

    @Test public void refusesASplicedSendOrConsolidate() {
        for (String extra : new String[]{
                "send amount:1 address:" + h("9"),
                "consolidate tokenid:0x00",
                "txnpost id:" + TXID,
                "sign publickey:" + OPK + " data:0x00" }) {
            List<String> c = close();
            c.add(extra);
            assertFalse(extra, ticket().permits(c, pool()));
        }
    }

    // ---- one-thing-changed attacks on a legitimate close ----

    @Test public void refusesAThirdInput() {
        List<String> c = close();
        c.add(2, "txninput id:" + TXID + " coinid:" + OTHER);
        assertFalse(ticket().permits(c, pool()));
    }

    @Test public void refusesADuplicatedInput() {
        List<String> c = close();
        c.add(2, "txninput id:" + TXID + " coinid:" + COIN_M);
        assertFalse(ticket().permits(c, pool()));
    }

    @Test public void refusesAThirdOutput() {
        List<String> c = close();
        c.add("txnoutput id:" + TXID + " amount:1 address:" + OADR + " storestate:false");
        assertFalse(ticket().permits(c, pool()));
    }

    @Test public void refusesAnOutputToAnyAddressOtherThanTheRecipeOadr() {
        List<String> c = close();
        c.set(3, "txnoutput id:" + TXID + " amount:" + AMT_M + " address:" + h("9") + " storestate:false");
        assertFalse(ticket().permits(c, pool()));
    }

    @Test public void refusesADifferentAmountOnEitherLeg() {
        List<String> a = close();
        a.set(3, "txnoutput id:" + TXID + " amount:1 address:" + OADR + " storestate:false");
        assertFalse(ticket().permits(a, pool()));

        List<String> b = close();
        b.set(4, "txnoutput id:" + TXID + " amount:1 address:" + OADR + " tokenid:" + TOK + " storestate:false");
        assertFalse(ticket().permits(b, pool()));
    }

    @Test public void refusesASwappedOrMissingTokenLeg() {
        List<String> wrongToken = close();
        wrongToken.set(4, "txnoutput id:" + TXID + " amount:" + AMT_T + " address:" + OADR + " tokenid:" + h("9") + " storestate:false");
        assertFalse(ticket().permits(wrongToken, pool()));

        List<String> twoMinima = close();
        twoMinima.set(4, "txnoutput id:" + TXID + " amount:" + AMT_T + " address:" + OADR + " storestate:false");
        assertFalse(ticket().permits(twoMinima, pool()));
    }

    @Test public void refusesAnyStateOutput() {
        List<String> c = close();
        c.add("txnstate id:" + TXID + " port:0 value:" + OPK);
        assertFalse(ticket().permits(c, pool()));
    }

    @Test public void refusesAStoredStateOutput() {
        List<String> c = close();
        c.set(3, "txnoutput id:" + TXID + " amount:" + AMT_M + " address:" + OADR + " storestate:true");
        assertFalse(ticket().permits(c, pool()));
    }

    @Test public void refusesASecondOwnerSignature() {
        List<String> c = close();
        c.add("txnsign id:" + TXID + " publickey:" + OPK);
        assertFalse(ticket().permits(c, pool()));
    }

    @Test public void refusesAThirdPartySigner() {
        List<String> c = close();
        c.set(6, "txnsign id:" + TXID + " publickey:" + h("9"));
        assertFalse(ticket().permits(c, pool()));
    }

    @Test public void refusesATxidWithoutThePpclosePrefix() {
        List<String> c = new ArrayList<>();
        for (String s : close()) c.add(s.replace(TXID, "ppref_7"));
        assertFalse(ticket().permits(c, pool()));
    }

    @Test public void refusesCommandsSplicedAcrossTwoOpenTransactions() {
        List<String> c = close();
        c.set(2, "txninput id:ppclose_OTHER coinid:" + COIN_T);
        assertFalse(ticket().permits(c, pool()));
    }

    @Test public void refusesADuplicatedParameterRatherThanResolvingIt() {
        // Cmd.param refuses a duplicate instead of taking the first or last. Two guard layers used to disagree
        // on exactly this, which would authorise one coin and verify another.
        List<String> c = close();
        c.set(1, "txninput id:" + TXID + " coinid:" + COIN_M + " coinid:" + OTHER);
        assertFalse(ticket().permits(c, pool()));
    }

    @Test public void refusesWhenTheLiveCoinsNoLongerMatchTheTicket() {
        // The pool moved between issue and signing (a swap, or this node's own keep-fresh).
        ExitTicket stale = new ExitTicket(OPK, ADDR, OADR, TOK, h("e"), h("f"), AMT_M, AMT_T, ExitTicket.Stage.CLOSE);
        assertFalse(stale.permits(close(), pool()));
    }

    @Test public void refusesACloseOfADifferentPool() {
        Pool other = pool();
        other.address = h("9");
        assertFalse(ticket().permits(close(), other));
    }

    @Test public void refusesWhenTheTicketIsForAnotherOwnerKey() {
        // A sibling pool shares an $OPK after a migrate; authority is keyed on the ADDRESS, not the key.
        Pool sibling = pool();
        sibling.address = h("9");
        ExitTicket forSibling = new ExitTicket(OPK, h("9"), OADR, TOK, COIN_M, COIN_T, AMT_M, AMT_T, ExitTicket.Stage.CLOSE);
        assertFalse("a ticket for the sibling must not admit this pool's close",
                forSibling.permits(close(), pool()));
    }

    @Test public void refusesAnEmptyOrMalformedList() {
        assertFalse(ticket().permits(null, pool()));
        assertFalse(ticket().permits(new ArrayList<>(), pool()));
        assertFalse(ticket().permits(Collections.singletonList("garbage"), pool()));
        assertFalse(ticket().permits(close(), null));
    }

    @Test public void aForwardStageTicketDoesNotAdmitAClose() {
        ExitTicket fwd = new ExitTicket(OPK, ADDR, OADR, TOK, COIN_M, COIN_T, AMT_M, AMT_T, ExitTicket.Stage.FORWARD);
        assertFalse(fwd.permits(close(), pool()));
    }

    // Eligibility (lifetime cap, signing floor, reserve age) is enforced by RestoreExit.refusal
    // and pinned by RestoreExitRefusalTest; the pure ExitTicket.eligible mirror was removed in
    // 0.9.63 (production-dead and already drifted - it never learned the lifetime cap).

    @Test public void theQuotaIsTwoSignaturesWithAHardLifetimeCap() {
        assertTrue(ExitTicket.SIGNATURES_PER_EXIT == 2);
        assertTrue(ExitTicket.MAX_LIFETIME_SIGNATURES >= ExitTicket.SIGNATURES_PER_EXIT);
        assertTrue("the cap must stay small enough that re-issuing is never an open unlock",
                ExitTicket.MAX_LIFETIME_SIGNATURES <= 4);
    }
}
