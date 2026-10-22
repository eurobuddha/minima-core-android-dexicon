package com.eurobuddha.dexicon.resting;
/* Ported from apks/pandadex app/src/test/java/com/eurobuddha/pandadex/DexTxnFundingTest.java (PandaDEX 0.4.23). */


import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.HashSet;

public class DexTxnFundingTest {

    private static JSONObject coin(String coinid, String amount, String tokenid, String address) {
        try {
            JSONObject c = new JSONObject();
            c.put("coinid", coinid);
            c.put("amount", amount);
            c.put("tokenamount", amount);
            c.put("tokenid", tokenid);
            c.put("address", address);
            return c;
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    @Test public void fundingSelectionExcludesPoolAndOwnerPayoutAddresses() {
        String poolAddr = "0x1111111111111111111111111111111111111111111111111111111111111111";
        String ownerAddr = "0x2222222222222222222222222222222222222222222222222222222222222222";
        String walletAddr = "0x3333333333333333333333333333333333333333333333333333333333333333";
        JSONArray rows = new JSONArray()
                .put(coin("0xPOOL", "100", Util.MINIMA_TOKENID, poolAddr))
                .put(coin("0xOWNER", "100", Util.MINIMA_TOKENID, ownerAddr))
                .put(coin("0xWALLET", "5", Util.MINIMA_TOKENID, walletAddr));

        DexTxn.FundingPick pick = DexTxn.selectFundingCoins(rows, new BigDecimal("4"),
                new HashSet<>(Arrays.asList(poolAddr.toLowerCase(), ownerAddr.toLowerCase())),
                8, new HashSet<>());

        assertNull(pick.error);
        assertEquals(1, pick.coins.size());
        assertEquals("0xWALLET", pick.coins.get(0).optString("coinid"));
        assertTrue(pick.presentCoinIds.contains("0xPOOL"));
        assertTrue(pick.presentCoinIds.contains("0xOWNER"));
    }

    @Test public void fundingSelectionRejectsMoreThanCompositeInputLimit() {
        JSONArray rows = new JSONArray();
        for (int i = 0; i < 9; i++) {
            rows.put(coin("0xF" + i, "1", DexContract.MXUSD_ID,
                    "0x44" + i + "444444444444444444444444444444444444444444444444444444444444"));
        }

        DexTxn.FundingPick pick = DexTxn.selectFundingCoins(rows, new BigDecimal("9"),
                new HashSet<>(), 8, new HashSet<>());

        assertNull(pick.coins);
        assertEquals("Wallet funding needs more than 8 inputs — consolidate coins and retry.", pick.error);
    }
}
