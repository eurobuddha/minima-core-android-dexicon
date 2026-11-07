package com.eurobuddha.dexicon.fill;

import static org.junit.Assert.*;

import org.json.JSONObject;
import org.junit.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Security review 7 Nov: a hostile FAST order must not get the taker to sign its own coins or replace its own script. */
public class FillTxnGuardTest {
    static String hex(String b) { StringBuilder s = new StringBuilder("0x"); for (int i = 0; i < 32; i++) s.append(b); return s.toString(); }
    static final String MY_PK = hex("d1"), MY_ADDR = hex("a1"), MY_SCRIPT = "RETURN SIGNEDBY(" + MY_PK + ")";
    static final String MAKER_ADDR = hex("c1"), MAKER_SCRIPT = "RETURN SIGNEDBY(" + hex("e1") + ")";

    static JSONObject row(String addr, String script, boolean simple, String pk) throws Exception {
        return new JSONObject().put("address", addr).put("script", script).put("simple", simple).put("publickey", pk);
    }
    static List<JSONObject> wallet() throws Exception {
        // my key row, plus a counterparty script an earlier take registered (newscript: simple false, key 0x00)
        return Arrays.asList(row(MY_ADDR, MY_SCRIPT, true, MY_PK), row(MAKER_ADDR, MAKER_SCRIPT, false, "0x00"));
    }
    static Coin coin(String id, String tok, String v, String addr) { return new Coin(hex(id), addr, tok, new BigDecimal(v), false, false, 0); }
    static FillPlan.Plan plan(Coin makerCoin, String script) {
        FillPlan.Order o = new FillPlan.Order("sell", "0.01", "100", hex("b9"), Arrays.asList(makerCoin.coinid), Arrays.asList(script));
        return FillPlan.plan(o, "50", Arrays.asList(makerCoin), Arrays.asList(coin("11", Tokens.MXUSD(), "5", MY_ADDR)), MY_ADDR);
    }

    @Test public void honestOrderSignsOnlyMyKey() throws Exception {
        List<String> keys = new ArrayList<>();
        assertNull(FillTxn.takerGuard(wallet(), plan(coin("01", Tokens.MINIMA, "60", MAKER_ADDR), MAKER_SCRIPT), keys));
        assertEquals(Arrays.asList(MY_PK), keys);
    }

    @Test public void orderListingMyCoinIsRefused() throws Exception {
        // "maker" coin at my own address, in the token I receive — publickey:auto would have signed it
        FillPlan.Order o = new FillPlan.Order("sell", "0.01", "100", hex("b9"), Arrays.asList(hex("02")), Arrays.asList(MAKER_SCRIPT));
        FillPlan.Plan p = FillPlan.plan(o, "50", Arrays.asList(coin("02", Tokens.MINIMA, "60", hex("a2"))), Arrays.asList(coin("11", Tokens.MXUSD(), "5", MY_ADDR)), MY_ADDR);
        List<JSONObject> w = new ArrayList<>(wallet()); w.add(row(hex("a2"), "RETURN SIGNEDBY(" + hex("d2") + ")", true, hex("d2")));
        assertNotNull(FillTxn.takerGuard(w, p, new ArrayList<>()));
    }

    @Test public void orderListingMyScriptIsRefused() throws Exception {
        assertNotNull(FillTxn.takerGuard(wallet(), plan(coin("01", Tokens.MINIMA, "60", MAKER_ADDR), "  return signedby(" + MY_PK.toLowerCase() + ") "), new ArrayList<>()));
    }

    @Test public void stateIsReadFromTheProofCoinNotTheInputView() throws Exception {
        JSONObject blank = new JSONObject("{\"mmrproofs\":[{\"coin\":{\"coinid\":\"0x01\",\"state\":[]}}]}");
        JSONObject locked = new JSONObject("{\"mmrproofs\":[{\"coin\":{\"coinid\":\"0x01\",\"state\":[]}},{\"coin\":{\"coinid\":\"0x02\",\"state\":[{\"port\":0,\"data\":\"0x01\"}]}}]}");
        assertFalse(FillTxn.proofCarriesState(blank));
        assertTrue(FillTxn.proofCarriesState(locked));
        assertFalse(FillTxn.proofCarriesState(null));
    }

    @Test public void makerCoinAtTheTakersPayingAddressIsRefusedInPlan() {
        FillPlan.Order o = new FillPlan.Order("sell", "0.01", "100", hex("b9"), Arrays.asList(hex("03")), Arrays.asList(MAKER_SCRIPT));
        try { FillPlan.plan(o, "50", Arrays.asList(coin("03", Tokens.MINIMA, "60", MY_ADDR)), Arrays.asList(coin("11", Tokens.MXUSD(), "5", MY_ADDR)), MY_ADDR); fail(); }
        catch (IllegalArgumentException expected) {}
    }
}
