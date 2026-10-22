package com.eurobuddha.dexicon.resting;
/* Ported from apks/pandadex app/src/test/java/com/eurobuddha/pandadex/FundingCoinsTest.java (PandaDEX 0.4.23). */


import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.Assert.*;

public class FundingCoinsTest {
    private static JSONObject ok(Object response) { return new TestJson().put("status", true).put("response", response); }
    private static JSONObject balance(int n) { return ok(new JSONArray().put(new TestJson().put("tokenid", "0x00").put("coins", n).put("sendable", "100"))); }
    private static JSONObject coin(String id, String address, String amount) {
        return new TestJson().put("coinid", id).put("address", address).put("tokenid", "0x00").put("amount", amount).put("state", new JSONArray());
    }
    private static class Result implements FundingCoins.Done {
        List<JSONObject> coins; String error; BigDecimal sum;
        public void ok(List<JSONObject> c, BigDecimal s) { coins = c; sum = s; CoinLock.release(c); }
        public void fail(String m) { error = m; }
    }
    @Test public void countMustBeKnownExactAndNonnegative() throws Exception {
        assertEquals(8, FundingCoins.coinsFor(balance(8), "0x00"));
        for (Object n : Arrays.asList("garbage", "1.5", -1, "999999999999999")) {
            JSONObject reply = balance(1); reply.getJSONArray("response").getJSONObject(0).put("coins", n);
            assertEquals(-1, FundingCoins.coinsFor(reply, "0x00"));
        }
        assertEquals(-1, FundingCoins.coinsFor(new TestJson().put("status", false).put("response", "Result too long!"), "0x00"));
        assertEquals(0, FundingCoins.coinsFor(ok(new JSONArray()), "0x00"));
        assertFalse(FundingCoins.listIsSafe(-1)); assertTrue(FundingCoins.listIsSafe(8));
        assertFalse(FundingCoins.listIsSafe(9));
    }
    @Test public void smallWalletChoosesLargestStatelessUnlockedCoins() throws Exception {
        Result result = new Result(); List<String> calls = new ArrayList<>();
        JSONArray coins = new JSONArray().put(coin("0xaa", "0x11", "1"))
                .put(coin("0xbb", "0x11", "5"))
                .put(coin("0xcc", "0x22", "100"))
                .put(coin("0xdd", "0x11", "200").put("state", new JSONArray().put(new TestJson().put("port", 1).put("data", "NFT"))));
        FundingCoins.select((cmd, cb) -> {
            calls.add(cmd); cb.onResult(cmd.startsWith("balance") ? balance(4) : ok(coins));
        }, "0x00", new BigDecimal("4"), Collections.singleton("0x22"), 20, result);
        assertNull(result.error); assertEquals("0xbb", result.coins.get(0).optString("coinid")); assertEquals(1, result.coins.size());
        assertTrue(calls.get(1).contains("coinage:3")); assertTrue(calls.get(1).contains("checkmempool:true"));
    }
    @Test public void largeAddressNeverIssuesItsCoinList() throws Exception {
        Result result = new Result(); List<String> calls = new ArrayList<>();
        FundingCoins.select((cmd, cb) -> {
            calls.add(cmd);
            if (cmd.startsWith("balance")) cb.onResult(balance(1000));
            else if (cmd.equals("keys")) cb.onResult(ok(new TestJson().put("keys", new JSONArray().put(new TestJson().put("publickey", "0xaa")))));
            else if (cmd.startsWith("runscript")) cb.onResult(ok(new TestJson().put("parseok", true).put("script", new TestJson().put("address", "0x11"))));
            else fail("Unsafe command: " + cmd);
        }, "0x00", BigDecimal.ONE, null, 20, result);
        assertNull(result.coins); assertTrue(result.error.contains("1000"));
        assertTrue(result.error.contains("Nothing was posted"));
        assertFalse(calls.stream().anyMatch(c -> c.startsWith("coins")));
    }
    @Test public void addressSlicesAccumulateAndAreRecounted() throws Exception {
        Result result = new Result(); Map<String, Integer> counts = new HashMap<>();
        FundingCoins.select((cmd, cb) -> {
            if (cmd.equals("balance tokenid:0x00")) cb.onResult(balance(10));
            else if (cmd.equals("keys")) cb.onResult(ok(new TestJson().put("keys", new JSONArray()
                    .put(new TestJson().put("publickey", "0xaa")).put(new TestJson().put("publickey", "0xbb")))));
            else if (cmd.startsWith("runscript")) cb.onResult(ok(new TestJson().put("parseok", true).put("script", new TestJson().put("address", cmd.contains("0xaa") ? "0x11" : "0x22"))));
            else if (cmd.startsWith("balance")) { counts.merge(cmd, 1, Integer::sum); cb.onResult(balance(5)); }
            else if (cmd.startsWith("coins") && cmd.contains("address:")) {
                boolean first = cmd.endsWith("0x11"); cb.onResult(ok(new JSONArray().put(coin(first ? "0xaa" : "0xbb", first ? "0x11" : "0x22", "3"))));
            } else fail("Unexpected command: " + cmd);
        }, "0x00", new BigDecimal("5"), null, 20, result);
        assertNull(result.error); assertEquals(2, result.coins.size()); assertEquals(new BigDecimal("6"), result.sum);
        assertEquals(Integer.valueOf(2), counts.get("balance tokenid:0x00 address:0x11"));
        assertEquals(Integer.valueOf(2), counts.get("balance tokenid:0x00 address:0x22"));
    }
    @Test public void nodeFailureDoesNotBecomeAnEmptyWallet() throws Exception {
        Result result = new Result();
        FundingCoins.select((cmd, cb) -> cb.onResult(new TestJson().put("status", false).put("response", new JSONArray())), "0x00", BigDecimal.ONE, null, 20, result);
        assertNull(result.coins); assertTrue(result.error.contains("Could not read"));
    }
    @Test public void scaledTokenRequiresHumanAmountAndValidState() throws Exception {
        JSONObject coin = coin("0xaa", "0x11", "0.0001").put("tokenid", "0x12");
        assertThrows(IllegalArgumentException.class, () -> FundingCoins.fundingCoin(coin));
        coin.put("tokenamount", "100"); assertEquals("100", FundingCoins.coinValue(FundingCoins.fundingCoin(coin)).toPlainString());
        coin.put("state", "unexpected");
        assertThrows(IllegalArgumentException.class, () -> FundingCoins.fundingCoin(coin));
    }

    /** S10 regression, second half: stock MiniNumber balances and coin amounts carry up to 64
     * significant digits and 44 decimal places. 0.4.16 taught the display path that; the funding
     * path still used the stricter order parser and refused to trade. */
    @Test public void stockPrecisionBalancesAndCoinsFundATrade() throws Exception {
        String dp44 = "12345678901234567890123456789012345678901234"; // 44 decimal places
        String sendable = "1234." + dp44;                             // 48 significant digits
        String coinAmount = "617." + dp44;                            // 47 significant digits
        Result result = new Result();
        FundingCoins.select((cmd, cb) -> {
            if (cmd.equals("balance tokenid:0x00")) cb.onResult(balance(10));
            else if (cmd.equals("keys")) cb.onResult(ok(new TestJson().put("keys",
                    new JSONArray().put(new TestJson().put("publickey", "0xaa")))));
            else if (cmd.startsWith("runscript")) cb.onResult(ok(new TestJson().put("parseok", true)
                    .put("script", new TestJson().put("address", "0x11"))));
            else if (cmd.startsWith("balance")) cb.onResult(ok(new JSONArray().put(new TestJson()
                    .put("tokenid", "0x00").put("coins", 3).put("sendable", sendable))));
            else if (cmd.startsWith("coins")) cb.onResult(ok(new JSONArray().put(coin("0xaa", "0x11", coinAmount))));
            else fail("Unexpected command: " + cmd);
        }, "0x00", BigDecimal.ONE, null, 20, result);
        assertNull(result.error);
        assertEquals(1, result.coins.size());
        assertEquals(new BigDecimal(coinAmount), result.sum);
        assertEquals(new BigDecimal(coinAmount), FundingCoins.coinValue(result.coins.get(0)));
        // The neighbour state must NOT move: order/transaction amounts keep the 44-digit limit.
        assertFalse(DexTxn.amountOk(new BigDecimal(sendable)));
        assertTrue(DexTxn.amountOk(new BigDecimal("1.5")));
    }
}
