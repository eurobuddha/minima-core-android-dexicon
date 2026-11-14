package com.eurobuddha.dexicon.resting;

import static org.junit.Assert.*;

import org.json.JSONObject;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;

/** Owner cancel (ported from PandaDEX DexTxn.cancel): the whole coin to its payout address, signed by its owner key. */
public class DexTxnCancelTest {
    private static final String COIN = "0x5A11223344556677889900AABBCCDDEEFF00112233445566778899AABBCCDDEE";
    private static final String OWNER = "0xAA11223344556677889900AABBCCDDEEFF00112233445566778899AABBCCDDEE";
    private static final String PAYOUT = "0xBB11223344556677889900AABBCCDDEEFF00112233445566778899AABBCCDDEE";

    private static Order5 order(String tokenid, String amount) throws Exception {
        JSONObject c = new JSONObject().put("coinid", COIN).put("amount", amount).put("tokenamount", amount)
                .put("tokenid", tokenid).put("created", 100);
        boolean sell = Util.MINIMA_TOKENID.equals(tokenid);
        JSONObject st = new JSONObject().put("0", OWNER).put("1", PAYOUT).put("2", "5")
                .put("3", sell ? DexContract.MXUSD_ID : Util.MINIMA_TOKENID).put("4", "0x01").put("5", sell ? "1" : "0")
                .put("7", "1").put("8", "1");
        return Order5.from(c.put("state", st));
    }

    @Test public void aSellReturnsTheWholeMinimaCoinToItsPayoutAddress() throws Exception {
        List<String> s = DexTxn.cancelSteps(order(Util.MINIMA_TOKENID, "12.5"), "cancel_1");
        assertEquals(Arrays.asList(
                "txncreate id:cancel_1",
                "txninput id:cancel_1 coinid:" + COIN,
                "txnoutput id:cancel_1 amount:12.5 address:" + PAYOUT + " storestate:false",
                "txnsign id:cancel_1 publickey:" + OWNER,
                "txnbasics id:cancel_1"), s);
    }

    @Test public void aBuyReturnsTheMxusdWithItsTokenId() throws Exception {
        List<String> s = DexTxn.cancelSteps(order(DexContract.MXUSD_ID, "3"), "cancel_2");
        assertEquals("txnoutput id:cancel_2 amount:3 address:" + PAYOUT + " tokenid:" + DexContract.MXUSD_ID + " storestate:false", s.get(2));
    }

    @Test public void nothingIsSignedForMissingOrder() { assertNull(DexTxn.cancelSteps(null, "x")); }
}
