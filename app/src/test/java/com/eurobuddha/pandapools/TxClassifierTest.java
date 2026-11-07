// VENDORED VERBATIM from apks/pandapools 0.9.89 app/src/test/java/com/eurobuddha/pandapools/TxClassifierTest.java by scripts/vendor-pandapools.sh — do not edit here; re-vendor.
package com.eurobuddha.pandapools;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Classification is what turns raw history into an accountable transaction, so every shape a PandaPools
 * action can take is pinned here — especially the two that are easy to get wrong: a keep-fresh must not be
 * booked as liquidity movement, and a migrate must not be confused with a refresh.
 */
public class TxClassifierTest {

    private static final String POOL_A = "0xAAA1";
    private static final String POOL_B = "0xBBB2";
    private static final String MINE   = "MxMINE";
    private static final String TOK    = "0x7D39745FBD29049BE29850B55A18BF550E4D442F930F86266E34193D89042A90";
    private static final String DUST   = "0.000000001";

    /** Stands in for PandaAddrBook without needing Android. */
    private static TxClassifier.Addrs addrs(String... known) {
        final Set<String> set = new HashSet<>();
        for (String k : known) set.add(k.toLowerCase());
        return coinsJson -> {
            if (coinsJson == null || coinsJson.isEmpty()) return null;
            try {
                JSONArray a = new JSONArray(coinsJson);
                for (int i = 0; i < a.length(); i++) {
                    String addr = a.getJSONObject(i).optString("addr", "");
                    if (!addr.isEmpty() && set.contains(addr.toLowerCase())) return addr;
                }
            } catch (Exception ignore) {}
            return null;
        };
    }

    // ---- fixture builders ----

    private static String coins(String... triples) {   // addr, amount, tokenid, ...
        JSONArray a = new JSONArray();
        try {
            for (int i = 0; i < triples.length; i += 3) {
                JSONObject o = new JSONObject();
                o.put("addr", triples[i]);
                o.put("amount", triples[i + 1]);
                o.put("tokenid", triples[i + 2]);
                a.put(o);
            }
        } catch (Exception e) { throw new RuntimeException(e); }
        return a.toString();
    }

    private static String deltas(String... pairs) {     // tokenid, signedAmount, ...
        JSONObject o = new JSONObject();
        try { for (int i = 0; i < pairs.length; i += 2) o.put(pairs[i], pairs[i + 1]); }
        catch (Exception e) { throw new RuntimeException(e); }
        return o.toString();
    }

    private static HistoryEntry entry(String direction, String deltas, String inputs, String outputs) {
        HistoryEntry e = new HistoryEntry();
        e.txpowid = "0xTX"; e.block = 100; e.timemilli = 1_700_000_000_000L;
        e.direction = direction; e.incoming = "received".equals(direction);
        e.deltas = deltas; e.inputs = inputs; e.outputs = outputs;
        e.tokenid = "0x00"; e.tokenName = "Minima"; e.amount = "0";
        return e;
    }

    // ---- the headline case ----

    @Test public void poolCreateReportsBothLegsAndOpeningPrice() {
        // 168,000 MINIMA + 800 mxUSDT paid into a brand-new pool.
        HistoryEntry e = entry("sent",
                deltas("0x00", "-168000.000000001", TOK, "-800"),
                coins(MINE, "200000", "0x00", MINE, "800", TOK),
                coins(POOL_A, "168000", "0x00", POOL_A, "800", TOK,
                      "MxSENTINEL", DUST, "0x00", MINE, "31999.999999999", "0x00"));

        TxClassifier.Tx t = TxClassifier.classify(e, addrs(POOL_A));

        assertEquals(TxClassifier.POOL_CREATE, t.type);
        assertEquals(POOL_A, t.poolAddress);
        assertEquals(TOK, t.tokenid);
        // both sides of what went in, exactly as they left the wallet
        assertEquals(new BigDecimal("-168000.000000001"), t.minimaDelta);
        assertEquals(new BigDecimal("-800"), t.tokenDelta);
        // opening price implied by the two legs: 800 / 168000.000000001
        assertNotNull(t.impliedPrice);
        assertEquals(0, t.impliedPrice.compareTo(
                new BigDecimal("800").divide(new BigDecimal("168000.000000001"),
                        new java.math.MathContext(20, java.math.RoundingMode.HALF_UP))));
        assertTrue(t.isLiquidityMove());
    }

    @Test public void swapCarriesTheExecutedPrice() {
        HistoryEntry e = entry("sent",
                deltas("0x00", "-1000", TOK, "+4.75"),
                coins(POOL_A, "50000", "0x00", POOL_A, "250", TOK, MINE, "1000", "0x00"),
                coins(POOL_A, "51000", "0x00", POOL_A, "245.25", TOK, MINE, "4.75", TOK));

        TxClassifier.Tx t = TxClassifier.classify(e, addrs(POOL_A));

        assertEquals(TxClassifier.SWAP, t.type);
        // 4.75 token for 1000 MINIMA = 0.00475 token per MINIMA
        assertEquals(0, t.impliedPrice.compareTo(new BigDecimal("0.00475")));
        assertFalse(t.isLiquidityMove());
    }

    @Test public void depositGrowsThePoolInPlace() {
        HistoryEntry e = entry("sent",
                deltas("0x00", "-500", TOK, "-2.5"),
                coins(POOL_A, "1000", "0x00", POOL_A, "5", TOK, MINE, "500", "0x00", MINE, "2.5", TOK),
                coins(POOL_A, "1500", "0x00", POOL_A, "7.5", TOK));
        assertEquals(TxClassifier.POOL_DEPOSIT, TxClassifier.classify(e, addrs(POOL_A)).type);
    }

    @Test public void withdrawSpendsThePoolAndDoesNotRecreateIt() {
        HistoryEntry e = entry("received",
                deltas("0x00", "+1500", TOK, "+7.5"),
                coins(POOL_A, "1500", "0x00", POOL_A, "7.5", TOK),
                coins(MINE, "1500", "0x00", MINE, "7.5", TOK));
        TxClassifier.Tx t = TxClassifier.classify(e, addrs(POOL_A));
        assertEquals(TxClassifier.POOL_WITHDRAW, t.type);
        assertEquals(POOL_A, t.poolAddress);
    }

    // ---- the two ordering traps ----

    @Test public void keepFreshIsNotBookedAsLiquidityMovement() {
        // Reserves recreated identically at the SAME address; only beacon dust leaves the wallet.
        HistoryEntry e = entry("sent",
                deltas("0x00", "-" + DUST),
                coins(POOL_A, "1000", "0x00", POOL_A, "5", TOK, MINE, "10", "0x00"),
                coins(POOL_A, "1000", "0x00", POOL_A, "5", TOK,
                      "MxSENTINEL", DUST, "0x00", MINE, "9.999999999", "0x00"));

        TxClassifier.Tx t = TxClassifier.classify(e, addrs(POOL_A));

        assertEquals(TxClassifier.POOL_KEEPALIVE, t.type);
        assertFalse("maintenance is not liquidity", t.isLiquidityMove());
    }

    @Test public void bareReannounceIsAlsoKeepAlive() {
        // No pool coin at all — just dust to the sentinel and change back.
        HistoryEntry e = entry("sent",
                deltas("0x00", "-" + DUST),
                coins(MINE, "10", "0x00"),
                coins("MxSENTINEL", DUST, "0x00", MINE, "9.999999999", "0x00"));
        assertEquals(TxClassifier.POOL_KEEPALIVE, TxClassifier.classify(e, addrs(POOL_A)).type);
    }

    @Test public void migrateIsToldFromRefreshByTheAddressChanging() {
        HistoryEntry e = entry("self",
                deltas("0x00", "0", TOK, "0"),
                coins(POOL_A, "1000", "0x00", POOL_A, "5", TOK),
                coins(POOL_B, "1000", "0x00", POOL_B, "5", TOK, "MxSENTINEL", DUST, "0x00"));

        // Same fixture but recreated where it stood would be a keep-fresh, not a migrate.
        assertEquals(TxClassifier.POOL_MIGRATE, TxClassifier.classify(e, addrs(POOL_A, POOL_B)).type);
    }

    @Test public void strangersSwapOnOurPoolIsExcludedFromOurPL() {
        HistoryEntry e = entry("self",
                deltas("0x00", "0", TOK, "0"),
                coins(POOL_A, "1000", "0x00", POOL_A, "5", TOK, "MxTHEM", "10", "0x00"),
                coins(POOL_A, "1010", "0x00", POOL_A, "4.95", TOK, "MxTHEM", "0.05", TOK));

        TxClassifier.Tx t = TxClassifier.classify(e, addrs(POOL_A));

        assertEquals(TxClassifier.OTHER_PARTY_SWAP, t.type);
        assertFalse(t.movedOurFunds());
    }

    // ---- ordinary wallet movement ----

    @Test public void plainTransfersAndReshufflesAreClassified() {
        HistoryEntry out = entry("sent", deltas("0x00", "-5"),
                coins(MINE, "10", "0x00"), coins("MxOTHER", "5", "0x00", MINE, "5", "0x00"));
        assertEquals(TxClassifier.TRANSFER_OUT, TxClassifier.classify(out, addrs(POOL_A)).type);

        HistoryEntry in = entry("received", deltas("0x00", "+5"),
                coins("MxOTHER", "10", "0x00"), coins(MINE, "5", "0x00", "MxOTHER", "5", "0x00"));
        assertEquals(TxClassifier.TRANSFER_IN, TxClassifier.classify(in, addrs(POOL_A)).type);

        HistoryEntry split = entry("self", deltas("0x00", "0"),
                coins(MINE, "10", "0x00"),
                coins(MINE, "3", "0x00", MINE, "3", "0x00", MINE, "4", "0x00"));
        assertEquals(TxClassifier.SELF_SPLIT, TxClassifier.classify(split, addrs(POOL_A)).type);

        HistoryEntry cons = entry("self", deltas("0x00", "0"),
                coins(MINE, "3", "0x00", MINE, "3", "0x00", MINE, "4", "0x00"),
                coins(MINE, "10", "0x00"));
        assertEquals(TxClassifier.SELF_CONSOLIDATION, TxClassifier.classify(cons, addrs(POOL_A)).type);
    }

    // ---- HistoryEntry primitives the classifier leans on ----

    @Test public void legsOnlyMatchAnExactlyTwoSidedTransaction() {
        assertNotNull(entry("sent", deltas("0x00", "-10", TOK, "+2"), "[]", "[]").legs());
        assertNull("one-sided", entry("sent", deltas("0x00", "-10"), "[]", "[]").legs());
        assertNull("both same sign", entry("sent", deltas("0x00", "-10", TOK, "-2"), "[]", "[]").legs());
        assertNull("three tokens", entry("sent",
                deltas("0x00", "-10", TOK, "+2", "0xCCC", "+1"), "[]", "[]").legs());
        // a zero leg doesn't count towards the two
        assertNull(entry("sent", deltas("0x00", "-10", TOK, "0"), "[]", "[]").legs());
    }

    @Test public void burnIsInputsMinusOutputsInMinimaOnly() {
        HistoryEntry burned = entry("sent", deltas("0x00", "-10"),
                coins(MINE, "10", "0x00"), coins("MxOTHER", "9", "0x00"));
        assertEquals(0, burned.burn().compareTo(new BigDecimal("1")));

        HistoryEntry clean = entry("sent", deltas("0x00", "-10"),
                coins(MINE, "10", "0x00"), coins("MxOTHER", "5", "0x00", MINE, "5", "0x00"));
        assertEquals(0, clean.burn().compareTo(BigDecimal.ZERO));

        // a token-only movement never reads as a MINIMA burn
        HistoryEntry tokenOnly = entry("sent", deltas(TOK, "-5"),
                coins(MINE, "5", TOK), coins("MxOTHER", "5", TOK));
        assertEquals(0, tokenOnly.burn().compareTo(BigDecimal.ZERO));

        // never negative, even if outputs somehow exceed inputs
        HistoryEntry odd = entry("sent", deltas("0x00", "0"),
                coins(MINE, "1", "0x00"), coins(MINE, "5", "0x00"));
        assertEquals(0, odd.burn().compareTo(BigDecimal.ZERO));
    }

    @Test public void deltaMapCarriesEveryToken() {
        HistoryEntry e = entry("sent", deltas("0x00", "-10", TOK, "+2"), "[]", "[]");
        assertEquals(new HashSet<>(Arrays.asList("0x00", TOK)), e.deltaMap().keySet());
        assertEquals(0, e.deltaMap().get(TOK).compareTo(new BigDecimal("2")));
    }
}
