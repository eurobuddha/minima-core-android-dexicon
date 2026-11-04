// VENDORED VERBATIM from apks/pandapools 0.9.82 app/src/test/java/com/eurobuddha/pandapools/ExitAuthorityTest.java by scripts/vendor-pandapools.sh — do not edit here; re-vendor.
package com.eurobuddha.pandapools;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The exemption is a quota of one signature, not a mode.
 *
 * ExitTicketTest proves a ticket cannot authorise the wrong SHAPE. This file proves the other half: that holding
 * a ticket does not become a state you can sign repeatedly from, that nothing survives a process restart, and
 * that the grant is looked up rather than passed in — so no caller can assert its way past the guard.
 */
public class ExitAuthorityTest {

    private static String h(String c) { return "0x" + String.join("", Collections.nCopies(64, c)); }

    private static final String OPK = h("1"), OADR = h("2"), TOK = h("3"), ADDR = h("4");
    private static final String COIN_M = h("a"), COIN_T = h("b");
    private static final String AMT_M = "487.0099300309", AMT_T = "1.84920223";
    private static final String TXID = "ppclose_7";

    private static Pool pool() {
        Pool p = new Pool();
        p.address = ADDR; p.opk = OPK; p.oadr = OADR; p.tok = TOK; p.tokDecimals = 8;
        p.coinidM = COIN_M; p.coinidT = COIN_T;
        p.reserveM = new BigDecimal(AMT_M); p.reserveT = new BigDecimal(AMT_T);
        p.minimumOwnerUses = 590; p.reserveBlock = 1000;
        return p;
    }

    private static ExitTicket ticket(ExitTicket.Stage stage) {
        return new ExitTicket(OPK, ADDR, OADR, TOK, COIN_M, COIN_T, AMT_M, AMT_T, stage);
    }

    private static List<String> close() {
        return new ArrayList<>(Arrays.asList(
                "txncreate id:" + TXID,
                "txninput id:" + TXID + " coinid:" + COIN_M,
                "txninput id:" + TXID + " coinid:" + COIN_T,
                "txnoutput id:" + TXID + " amount:" + AMT_M + " address:" + OADR + " storestate:false",
                "txnoutput id:" + TXID + " amount:" + AMT_T + " address:" + OADR + " tokenid:" + TOK + " storestate:false",
                "txnsign id:" + TXID + " publickey:" + OPK,
                "txnbasics id:" + TXID));
    }

    @Before public void clean() { ExitAuthority.revoke(); }
    @After public void cleanAfter() { ExitAuthority.revoke(); }

    @Test public void withNoGrantEverythingIsRefused() {
        assertNull(ExitAuthority.current());
        assertFalse(ExitAuthority.permits(close(), pool()));
    }

    @Test public void aGrantAdmitsExactlyOneSignature() {
        ExitAuthority.grant(ticket(ExitTicket.Stage.CLOSE));
        assertTrue("the first signature is admitted", ExitAuthority.permits(close(), pool()));
        assertFalse("a second signature on the same grant must be refused",
                ExitAuthority.permits(close(), pool()));
        assertNull("the grant is consumed", ExitAuthority.current());
    }

    @Test public void aRefusedShapeDoesNotConsumeTheGrant() {
        // A refusal must not burn the exemption — otherwise a malformed attempt would strand a legitimate exit.
        ExitAuthority.grant(ticket(ExitTicket.Stage.CLOSE));
        List<String> tampered = close();
        tampered.add("txnoutput id:" + TXID + " amount:1 address:" + h("9") + " storestate:false");
        assertFalse(ExitAuthority.permits(tampered, pool()));
        assertTrue("the grant survives a refusal", ExitAuthority.current() != null);
        assertTrue(ExitAuthority.permits(close(), pool()));
    }

    @Test public void onlyOneGrantExistsAtATime() {
        // Not a map, on purpose: two concurrent exemptions would mean two chains signing owner keys at once.
        ExitAuthority.grant(ticket(ExitTicket.Stage.CLOSE));
        ExitTicket second = new ExitTicket(OPK, h("9"), OADR, TOK, COIN_M, COIN_T, AMT_M, AMT_T, ExitTicket.Stage.CLOSE);
        ExitAuthority.grant(second);
        assertTrue(ExitAuthority.current() == second);
        assertFalse("the replaced grant is gone, not queued", ExitAuthority.permits(close(), pool()));
    }

    @Test public void revokeLeavesNothingBehind() {
        ExitAuthority.grant(ticket(ExitTicket.Stage.CLOSE));
        ExitAuthority.revoke();
        assertNull(ExitAuthority.current());
        assertFalse(ExitAuthority.permits(close(), pool()));
    }

    @Test public void aForwardGrantDoesNotAdmitAClose() {
        ExitAuthority.grant(ticket(ExitTicket.Stage.FORWARD));
        assertFalse(ExitAuthority.permits(close(), pool()));
    }

    @Test public void aGrantForOnePoolNeverAdmitsAnother() {
        // Migrate makes two pools share one $OPK, so authority is keyed on the covenant address.
        ExitAuthority.grant(new ExitTicket(OPK, h("9"), OADR, TOK, COIN_M, COIN_T, AMT_M, AMT_T, ExitTicket.Stage.CLOSE));
        assertFalse(ExitAuthority.permits(close(), pool()));
    }

    @Test public void pendingForIsStatusOnlyAndNeverAuthority() {
        ExitAuthority.grant(ticket(ExitTicket.Stage.CLOSE));
        assertTrue(ExitAuthority.pendingFor(ADDR));
        assertTrue("case-insensitive, like every other address comparison",
                ExitAuthority.pendingFor(ADDR.toUpperCase()));
        assertFalse(ExitAuthority.pendingFor(h("9")));
        assertFalse(ExitAuthority.pendingFor(null));
    }

    @Test public void theQuotaAndLifetimeCapAreBounded() {
        assertTrue(ExitTicket.SIGNATURES_PER_EXIT == 2);
        assertTrue(ExitTicket.MAX_LIFETIME_SIGNATURES >= 2 && ExitTicket.MAX_LIFETIME_SIGNATURES <= 4);
    }
}
