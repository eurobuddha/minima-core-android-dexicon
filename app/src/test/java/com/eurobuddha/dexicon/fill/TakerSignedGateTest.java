package com.eurobuddha.dexicon.fill;

import static org.junit.Assert.*;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.*;

/** Review 7 Nov (H-1): the maker never co-signs a take its taker has not signed (each try cost a one-time key use). */
public class TakerSignedGateTest {
    static final String MINE = "0x" + "aa".repeat(32), THEIRS = "0x" + "bb".repeat(32), PK = "0x" + "CC".repeat(32);
    static JSONObject view(String takerScript) throws Exception {
        return new JSONObject()
                .put("transaction", new JSONObject().put("inputs", new JSONArray()
                        .put(new JSONObject().put("coinid", "0x01").put("address", MINE))
                        .put(new JSONObject().put("coinid", "0x02").put("address", THEIRS))))
                .put("witness", new JSONObject().put("scripts", new JSONArray()
                        .put(new JSONObject().put("address", MINE).put("script", "RETURN SIGNEDBY(0x" + "DD".repeat(32) + ")"))
                        .put(new JSONObject().put("address", THEIRS).put("script", takerScript))));
    }
    static JSONObject pre(JSONObject... sigs) throws Exception {
        JSONArray a = new JSONArray(); for (JSONObject s : sigs) a.put(s);
        return new JSONObject().put("valid", new JSONObject().put("signatures", a));
    }
    static JSONObject sig(String pk, boolean ok) throws Exception { return new JSONObject().put("publickey", pk).put("valid", ok); }
    static final Set<String> WALLET = Collections.singleton(MINE);

    @Test public void aSignedPlainTakerInputIsAuthorised() throws Exception {
        List<String> coins = new ArrayList<>();
        assertNull(FillTxn.takerAuthorised(view("RETURN SIGNEDBY(" + PK + ")"), pre(sig(PK, true)), WALLET, coins));
        assertEquals(Collections.singletonList("0x02"), coins);
    }
    @Test public void anUnsignedTakeIsRefusedBeforeISign() throws Exception {
        assertNotNull(FillTxn.takerAuthorised(view("RETURN SIGNEDBY(" + PK + ")"), pre(), WALLET, new ArrayList<>()));
    }
    @Test public void anInvalidSignatureIsRefused() throws Exception {
        assertNotNull(FillTxn.takerAuthorised(view("RETURN SIGNEDBY(" + PK + ")"), pre(sig(PK, false)), WALLET, new ArrayList<>()));
    }
    @Test public void aTakerInputUnderAnyOtherScriptIsRefused() throws Exception {
        assertNotNull(FillTxn.takerAuthorised(view("RETURN TRUE"), pre(sig(PK, true)), WALLET, new ArrayList<>()));
    }
    @Test public void aSignatureByAnotherKeyDoesNotCount() throws Exception {
        assertNotNull(FillTxn.takerAuthorised(view("RETURN SIGNEDBY(" + PK + ")"), pre(sig("0x" + "EE".repeat(32), true)), WALLET, new ArrayList<>()));
    }
}
