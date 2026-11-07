package com.eurobuddha.dexicon.resting;

import static org.junit.Assert.*;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.math.BigDecimal;
import java.util.*;

/** Review 7 Nov (port of pandadex-mds 0.4.25): new orders use the V6 covenant; V5 orders are still served at their own
 *  address. The V6 literal below is pandadex-mds covenant.js PandaDEX.script with the MxUSD token id inlined
 *  (the -test-chain-proven order2.txt), so any drift from the MiniDapp is caught here. */
public class V6CovenantTest {

    static final String ORDER2 =
            "LET u=0x7D39745FBD29049BE29850B55A18BF550E4D442F930F86266E34193D89042A90 LET o=PREVSTATE(0) LET a=PREVSTATE(1) LET k=PREVSTATE(3) LET m=PREVSTATE(8) IF (@TOKENID NEQ 0x00 AND @TOKENID NEQ u) OR (k NEQ 0x00 AND k NEQ u) THEN RETURN FALSE ENDIF IF SIGNEDBY(o) THEN IF GETOUTADDR(@INPUT) EQ @ADDRESS THEN IF SAMESTATE(0 1) AND SAMESTATE(3 5) AND SAMESTATE(7 8) AND STATE(2) GT 0 AND VERIFYOUT(@INPUT @ADDRESS @AMOUNT @TOKENID TRUE) THEN RETURN TRUE ENDIF ENDIF IF VERIFYOUT(@INPUT a @AMOUNT @TOKENID FALSE) THEN RETURN TRUE ENDIF ENDIF IF @COINAGE GT 600 THEN RETURN VERIFYOUT(@INPUT a @AMOUNT @TOKENID FALSE) ENDIF LET w=PREVSTATE(2) LET r=0 IF @TOTOUT GT @INPUT+1 THEN IF GETOUTADDR(@INPUT+1) EQ @ADDRESS THEN LET r=GETOUTAMT(@INPUT+1) ENDIF ENDIF IF r EQ 0 THEN RETURN VERIFYOUT(@INPUT a w k FALSE) ENDIF RETURN r LT @AMOUNT AND r GTE m AND VERIFYOUT(@INPUT+1 @ADDRESS r @TOKENID TRUE) AND STATE(0) EQ o AND STATE(1) EQ a AND STATE(3) EQ k AND STATE(4) EQ PREVSTATE(4) AND STATE(5) EQ PREVSTATE(5) AND STATE(7) EQ PREVSTATE(7) AND STATE(8) EQ m AND STATE(2)*@AMOUNT GTE w*r AND STATE(2) LTE w AND GETOUTADDR(@INPUT) EQ a AND GETOUTTOK(@INPUT) EQ k AND GETOUTAMT(@INPUT)*@AMOUNT GTE w*(@AMOUNT-r)";
    static final String PAYOUT = "0xBB11223344556677889900AABBCCDDEEFF00112233445566778899AABBCCDDEE";

    static JSONObject coin(String id, Object address) throws Exception {
        JSONObject c = new JSONObject().put("coinid", id).put("tokenid", "0x00").put("amount", "100").put("created", 10)
                .put("state", new JSONObject().put("0", "0x0A0A").put("1", PAYOUT).put("2", "1")
                        .put("3", DexContract.MXUSD_ID).put("4", "0xAB12").put("5", "1").put("7", "1").put("8", "1"));
        if (address != null) c.put("address", address);
        return c;
    }

    @Test public void v6ScriptIsByteExactWithTheMiniDapp() {
        assertEquals(ORDER2, DexContract.SCRIPT_V6);
        assertEquals(1195, DexContract.SCRIPT_V6.length());
        assertTrue(DexContract.SCRIPT_V6.contains("STATE(0) EQ o AND STATE(1) EQ a AND STATE(3) EQ k AND STATE(4) EQ PREVSTATE(4)"));
    }

    @Test public void addressConstants() {
        assertEquals("0xDCF621472F6A09D5978D24DEF133B756DCED87067FCA692C21374EC38EF2D80A", DexContract.ADDR_V6);
        assertEquals("MxG086SUZGKEBRA17APF394RRZJ7DQMRJMZE1JVP9KWZ89N9R1ZTSMZ1B2518MQ", DexContract.ADDR_V6_MX);
        assertEquals("0x2D43279DD85DABCA3EA90C9997DAB9169D8B7A0E8CB594236AF44542489774A5", DexContract.ADDR_V5);
        assertArrayEquals(new String[]{DexContract.ADDR_V6, DexContract.ADDR_V5}, DexContract.BOOK_ADDRS);
        assertEquals(DexContract.ADDR_V6, DexContract.orderAddr(DexContract.ADDR_V6_MX));
        assertEquals(DexContract.ADDR_V5, DexContract.orderAddr(DexContract.ADDR_V5.toLowerCase()));
        assertNull(DexContract.orderAddr(DexContract.ADDR_V4));
    }

    @Test public void anOrderRemembersItsCovenantAndRejectsAnyOtherAddress() throws Exception {
        assertEquals(DexContract.ADDR_V6, Order5.from(coin("0x01", DexContract.ADDR_V6)).addr);
        assertEquals(DexContract.ADDR_V5, Order5.from(coin("0x02", DexContract.ADDR_V5)).addr);
        assertEquals(DexContract.ADDR_V6, Order5.from(coin("0x03", DexContract.ADDR_V6.toLowerCase())).addr);
        assertEquals(DexContract.ADDR_V6, Order5.from(coin("0x04", null).put("miniaddress", DexContract.ADDR_V6_MX)).addr);
        assertEquals("a stored row with no address is a V5 order", DexContract.ADDR_V5, Order5.from(coin("0x05", null)).addr);
        assertNull(Order5.from(coin("0x06", "0x1234")));
        assertNull(Order5.from(coin("0x07", DexContract.ADDR_V4)));
        // a payout address at EITHER covenant is unfillable
        for (String a : DexContract.BOOK_ADDRS) {
            JSONObject c = coin("0x08", DexContract.ADDR_V6); c.getJSONObject("state").put("1", a);
            assertFalse(Order5.from(c).fillable());
        }
        assertTrue(Order5.from(coin("0x09", DexContract.ADDR_V6)).fillable());
    }

    @Test public void theBookScansBothCovenants() throws Exception {
        List<String> asked = new ArrayList<>();
        Map<String, Order5>[] book = new Map[1];
        BookScanner.scan((cmd, cb) -> {
            asked.add(cmd);
            try {
                JSONArray rows = new JSONArray();
                if (cmd.contains(DexContract.ADDR_V6) && cmd.contains("coinage:0 ")) rows.put(coin("0x66", DexContract.ADDR_V6));
                if (cmd.contains(DexContract.ADDR_V5) && cmd.contains("coinage:0 ")) rows.put(coin("0x55", DexContract.ADDR_V5));
                cb.onResult(new JSONObject().put("status", true).put("response", rows));
            } catch (Exception e) { throw new RuntimeException(e); }
        }, (orders, truncated, raw) -> { assertFalse(truncated); book[0] = orders; });
        assertTrue(asked.stream().anyMatch(c -> c.contains("address:" + DexContract.ADDR_V6)));
        assertTrue(asked.stream().anyMatch(c -> c.contains("address:" + DexContract.ADDR_V5)));
        assertNotNull(book[0]);
        assertEquals(DexContract.ADDR_V6, book[0].get("0x66").addr);
        assertEquals(DexContract.ADDR_V5, book[0].get("0x55").addr);
    }

    @Test public void startupVerifiesV6AndRegistersBoth() throws Exception {
        List<String> asked = new ArrayList<>();
        boolean[] ok = {false};
        DexContract.ensureScript((cmd, cb) -> {
            asked.add(cmd);
            try {
                if (cmd.startsWith("runscript ")) cb.onResult(new JSONObject().put("status", true).put("response",
                        new JSONObject().put("parseok", true).put("script", new JSONObject().put("address", DexContract.ADDR_V6))));
                else cb.onResult(new JSONObject().put("status", true));
            } catch (Exception e) { throw new RuntimeException(e); }
        }, new DexContract.Ready() { public void ok() { ok[0] = true; } public void failed(String why) { fail(why); } });
        assertTrue(ok[0]);
        assertEquals("runscript script:" + Util.scriptArg(DexContract.SCRIPT_V6), asked.get(0));
        assertEquals("newscript trackall:false script:" + Util.scriptArg(DexContract.SCRIPT_V6), asked.get(1));
        assertEquals("newscript trackall:false script:" + Util.scriptArg(DexContract.SCRIPT_V5), asked.get(2));

        String[] why = {null};
        DexContract.ensureScript((cmd, cb) -> {
            try { cb.onResult(new JSONObject().put("status", true).put("response",
                    new JSONObject().put("parseok", true).put("script", new JSONObject().put("address", DexContract.ADDR_V5)))); }
            catch (Exception e) { throw new RuntimeException(e); }
        }, new DexContract.Ready() { public void ok() { fail("V5 address must not pass the V6 check"); } public void failed(String w) { why[0] = w; } });
        assertNotNull(why[0]);
    }

    @Test public void aPartialFillRemainderStaysAtTheOrdersOwnAddress() throws Exception {
        for (String a : DexContract.BOOK_ADDRS) {
            Order5 o = Order5.from(coin("0x0" + a.charAt(3), a));
            assertEquals("txnoutput id:f amount:40 address:" + a + " storestate:true",
                    DexTxn.covenantOutput("f", o, new BigDecimal("40")));
        }
    }

}
