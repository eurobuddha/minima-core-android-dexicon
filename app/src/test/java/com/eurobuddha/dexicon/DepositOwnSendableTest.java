package com.eurobuddha.dexicon;

import static org.junit.Assert.*;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

/** 10 Nov, on the Fold: Minima Core's Send failed (and crashed on its empty error) above its OWN coins, while its total
 *  also counted the Trading Account's. Deposit shows the node's own `sendable` for the token, exactly as written. */
public class DepositOwnSendableTest {
    private static final String MXUSD = "0x7D39745FBD29049BE29850B55A18BF550E4D442F930F86266E34193D89042A90";

    @Test public void readsTheNodesOwnSendableForTheToken() throws Exception {
        JSONObject reply = new JSONObject().put("status", true).put("response", new JSONArray()
                .put(new JSONObject().put("tokenid", "0x00").put("confirmed", "6580.8").put("sendable", "3500.8"))
                .put(new JSONObject().put("tokenid", MXUSD).put("confirmed", "5.38092474").put("sendable", "0")));
        assertEquals("3500.8", WalletTools.nodeSendable(reply, "0x00"));
        assertEquals("0", WalletTools.nodeSendable(reply, MXUSD.toLowerCase()));
    }

    @Test public void aTokenTheNodeDoesNotHoldIsZeroAndAFailedReadIsUnknown() throws Exception {
        assertEquals("0", WalletTools.nodeSendable(new JSONObject().put("response", new JSONArray()), MXUSD));
        assertNull(WalletTools.nodeSendable(new JSONObject().put("status", false), MXUSD));
    }
}
