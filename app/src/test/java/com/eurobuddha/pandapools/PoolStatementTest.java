// VENDORED VERBATIM from apks/pandapools 0.9.87 app/src/test/java/com/eurobuddha/pandapools/PoolStatementTest.java by scripts/vendor-pandapools.sh — do not edit here; re-vendor.
package com.eurobuddha.pandapools;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The statement is only worth anything if the numbers are right, so the two defects an independent
 * chain-rebuilt analysis exposed are pinned here first: a routed swap must be split across the pools it
 * actually touched, and a token coin's quantity must come from {@code tokenamount}, not {@code amount}.
 */
public class PoolStatementTest {

    private static final String TOK    = "0x7D39745FBD29049BE29850B55A18BF550E4D442F930F86266E34193D89042A90";
    private static final String POOL_A = "0xAAA1";
    private static final String POOL_B = "0xBBB2";
    private static final String POOL_C = "0xCCC3";
    private static final String MINE   = "MxMINE";

    private static TxClassifier.Addrs addrs(final String... known) {
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

    private static String coins(String... triples) {   // addr, amount, tokenid
        JSONArray a = new JSONArray();
        try {
            for (int i = 0; i < triples.length; i += 3) {
                JSONObject o = new JSONObject();
                o.put("addr", triples[i]); o.put("amount", triples[i + 1]); o.put("tokenid", triples[i + 2]);
                a.put(o);
            }
        } catch (Exception e) { throw new RuntimeException(e); }
        return a.toString();
    }

    private static String deltas(String... pairs) {
        JSONObject o = new JSONObject();
        try { for (int i = 0; i < pairs.length; i += 2) o.put(pairs[i], pairs[i + 1]); }
        catch (Exception e) { throw new RuntimeException(e); }
        return o.toString();
    }

    private static HistoryEntry entry(long block, String dir, String d, String in, String out) {
        HistoryEntry e = new HistoryEntry();
        e.txpowid = "0xTX" + block; e.block = block;
        e.timemilli = 1_752_000_000_000L + block * 3_600_000L;
        e.direction = dir; e.incoming = "received".equals(dir);
        e.deltas = d; e.inputs = in; e.outputs = out;
        e.tokenid = "0x00"; e.tokenName = "Minima"; e.amount = "0";
        return e;
    }

    private static PoolStatement.Params params(PoolStatement.PoolInfo... pools) {
        PoolStatement.Params p = new PoolStatement.Params();
        p.addrs = addrs(POOL_A, POOL_B, POOL_C);
        p.exportedAtMs = 1_754_000_000_000L;
        p.backfillDone = true;
        for (PoolStatement.PoolInfo pi : pools) p.pools.add(pi);
        return p;
    }

    private static PoolStatement.PoolInfo pool(String addr, String m, String t) {
        return new PoolStatement.PoolInfo(addr, "mxUSDT", new BigDecimal(m), new BigDecimal(t), false, null);
    }

    // ---- the bug an independent chain rebuild caught ----

    /** One transaction, three pools. Each must get its OWN share, not the whole trade. */
    @Test public void routedSwapIsSplitAcrossEveryPoolItTouched() {
        HistoryEntry e = entry(20, "sent",
                deltas("0x00", "-3000", TOK, "+14.1"),
                coins(POOL_A, "50000", "0x00", POOL_A, "250", TOK,
                      POOL_B, "30000", "0x00", POOL_B, "150", TOK,
                      POOL_C, "20000", "0x00", POOL_C, "100", TOK,
                      MINE,   "3000",  "0x00"),
                coins(POOL_A, "51000", "0x00", POOL_A, "245.1", TOK,
                      POOL_B, "31500", "0x00", POOL_B, "142.6", TOK,
                      POOL_C, "20500", "0x00", POOL_C, "98.2",  TOK,
                      MINE,   "14.1",  TOK));

        Map<String, BigDecimal[]> flows = TxClassifier.poolFlows(e, addrs(POOL_A, POOL_B, POOL_C));

        assertEquals(3, flows.size());
        assertEquals(0, flows.get(POOL_A)[0].compareTo(new BigDecimal("-1000")));
        assertEquals(0, flows.get(POOL_A)[1].compareTo(new BigDecimal("4.9")));
        assertEquals(0, flows.get(POOL_B)[0].compareTo(new BigDecimal("-1500")));
        assertEquals(0, flows.get(POOL_B)[1].compareTo(new BigDecimal("7.4")));
        assertEquals(0, flows.get(POOL_C)[0].compareTo(new BigDecimal("-500")));
        assertEquals(0, flows.get(POOL_C)[1].compareTo(new BigDecimal("1.8")));

        // and the three shares must add back up to the wallet's own movement
        TxClassifier.Tx t = TxClassifier.classify(e, addrs(POOL_A, POOL_B, POOL_C));
        assertTrue(TxClassifier.splitReconciles(t, flows));
    }

    /** The statement for one pool must show only that pool's share of a routed trade. */
    @Test public void statementBooksOnlyThisPoolsShareOfARoutedTrade() {
        PoolStatement.Params p = params(pool(POOL_A, "51000", "245.1"));
        p.rows.add(entry(20, "sent",
                deltas("0x00", "-3000", TOK, "+14.1"),
                coins(POOL_A, "50000", "0x00", POOL_A, "250", TOK,
                      POOL_B, "30000", "0x00", POOL_B, "150", TOK,
                      POOL_C, "20000", "0x00", POOL_C, "100", TOK,
                      MINE,   "3000",  "0x00"),
                coins(POOL_A, "51000", "0x00", POOL_A, "245.1", TOK,
                      POOL_B, "31500", "0x00", POOL_B, "142.6", TOK,
                      POOL_C, "20500", "0x00", POOL_C, "98.2",  TOK,
                      MINE,   "14.1",  TOK)));

        String csv = PoolStatement.build(p).csv;

        assertTrue("this pool's leg", csv.contains("\"-1000\",\"4.9\""));
        assertFalse("must NOT book the whole trade here", csv.contains("\"-3000\""));
        assertTrue("routing is visible", csv.contains("\"3\""));
    }

    @Test public void aSplitThatDoesNotTieIsFlaggedNotBooked() {
        // outputs omit one pool leg, so the shares can't add up to the wallet delta
        HistoryEntry e = entry(21, "sent",
                deltas("0x00", "-3000", TOK, "+14.1"),
                coins(POOL_A, "50000", "0x00", POOL_A, "250", TOK, MINE, "3000", "0x00"),
                coins(POOL_A, "51000", "0x00", POOL_A, "245.1", TOK, MINE, "14.1", TOK));
        TxClassifier.Addrs a = addrs(POOL_A, POOL_B, POOL_C);

        assertFalse(TxClassifier.splitReconciles(TxClassifier.classify(e, a), TxClassifier.poolFlows(e, a)));

        PoolStatement.Params p = params(pool(POOL_A, "51000", "245.1"));
        p.rows.add(e);
        PoolStatement.Report r = PoolStatement.build(p);
        assertEquals(1, r.unreconciled);
        assertTrue(r.csv.contains("does not reconcile"));
    }

    /** `amount` and `tokenamount` both present on a token coin — the quantity is `tokenamount`. */
    @Test public void tokenCoinsStoreTheTokenQuantityNotTheInternalAmount() throws Exception {
        JSONObject out = new JSONObject()
                .put("miniaddress", MINE)
                .put("tokenid", TOK)
                .put("amount", "0.0000000008")     // internal coloured-coin value
                .put("tokenamount", "800");        // the actual quantity
        JSONObject txpow = new JSONObject()
                .put("txpowid", "0xT")
                .put("header", new JSONObject().put("block", 1).put("timemilli", 1))
                .put("body", new JSONObject().put("txn", new JSONObject()
                        .put("inputs", new JSONArray())
                        .put("outputs", new JSONArray().put(out))));

        HistoryEntry e = HistoryEntry.from(txpow, new JSONObject());

        assertTrue("stored the token quantity", e.outputs.contains("800"));
        assertFalse("not the internal amount", e.outputs.contains("0.0000000008"));
    }

    // ---- the headline figures ----

    /**
     * Created with 168,000 MINIMA + 800 mxUSDT; fees have since grown the pool to 180,000 / 752.
     *
     * The two profit figures answer different questions, exactly as the independent chain rebuild framed
     * them: today-vs-held isolates what the pool earned, today-vs-cost includes MINIMA's own price move.
     */
    @Test public void bothProfitFiguresAreReported() {
        PoolStatement.Params p = params(pool(POOL_A, "180000", "752"));
        p.rows.add(entry(10, "sent",
                deltas("0x00", "-168000", TOK, "-800"),
                coins(MINE, "168000", "0x00", MINE, "800", TOK),
                coins(POOL_A, "168000", "0x00", POOL_A, "800", TOK)));

        String csv = PoolStatement.build(p).csv;

        // opening price 800/168000 → 168,000 MINIMA was worth 800, plus the 800 mxUSDT = 1,600
        assertTrue(csv.contains("\"Value at opening price\",\"1600\""));
        // today's price 752/180000 → the same coins held would be 168000*752/180000 + 800 = 1501.866667
        assertTrue(csv.contains("\"Value if you'd simply held those coins\",\"1501.866667\""));
        // the pool itself: 180000*752/180000 + 752 = 1504
        assertTrue(csv.contains("\"Value of the pool today\",\"1504\""));
        assertTrue(csv.contains("\"Pool profit (vs holding)\",\"2.133333\""));
        assertTrue(csv.contains("\"Change in market value\",\"-96\""));
        // and the create is visible with BOTH legs, which is the whole point of the file
        assertTrue(csv.contains("\"Created with\",\"168000\",\"MINIMA\",\"800\",\"mxUSDT\""));
        assertTrue(csv.contains("\"Now in pool\",\"180000\",\"MINIMA\",\"752\",\"mxUSDT\""));
    }

    @Test public void aSwapAgainstYourOwnPoolCostsNothingAtTheMomentYouMakeIt() {
        // sell 1,000 MINIMA for 5 mxUSDT at the pool's own price: equal value exchanged, so the opening-value
        // basis is unmoved by it — only the create contributed.
        PoolStatement.Params p = params(pool(POOL_A, "169000", "795"));
        p.rows.add(entry(10, "sent",
                deltas("0x00", "-168000", TOK, "-800"),
                coins(MINE, "168000", "0x00", MINE, "800", TOK),
                coins(POOL_A, "168000", "0x00", POOL_A, "800", TOK)));
        p.rows.add(entry(11, "sent",
                deltas("0x00", "-1000", TOK, "+5"),
                coins(POOL_A, "168000", "0x00", POOL_A, "800", TOK, MINE, "1000", "0x00"),
                coins(POOL_A, "169000", "0x00", POOL_A, "795", TOK, MINE, "5", TOK)));

        String csv = PoolStatement.build(p).csv;

        assertTrue("create only", csv.contains("\"Value at opening price\",\"1600\""));
        assertTrue("the trade shows up", csv.contains("\"Your trades (net)\",\"-1000\",\"MINIMA\",\"5\",\"mxUSDT\""));
        // out of pocket = 168,000 + 1,000 MINIMA, and 800 − 5 mxUSDT
        assertTrue(csv.contains("\"Net put in\",\"169000\",\"MINIMA\",\"795\",\"mxUSDT\""));
    }

    // ---- what it refuses to state ----

    @Test public void unknownReservesProduceNoFiguresAtAll() {
        PoolStatement.Params p = params(
                new PoolStatement.PoolInfo(POOL_A, "mxUSDT", null, null, false, null));
        p.rows.add(entry(10, "sent",
                deltas("0x00", "-168000", TOK, "-800"),
                coins(MINE, "168000", "0x00", MINE, "800", TOK),
                coins(POOL_A, "168000", "0x00", POOL_A, "800", TOK)));

        String csv = PoolStatement.build(p).csv;

        assertTrue(csv.contains("reserves unavailable"));
        assertFalse(csv.contains("Value of the pool today"));
        assertFalse(csv.contains("Pool profit"));
        // what you put in is still known, and still stated
        assertTrue(csv.contains("\"Created with\",\"168000\",\"MINIMA\",\"800\",\"mxUSDT\""));
    }

    @Test public void otherPeoplesTradesNeverAppearAsOurRows() {
        PoolStatement.Params p = params(pool(POOL_A, "51000", "245.1"));
        p.rows.add(entry(20, "self",
                deltas("0x00", "0", TOK, "0"),
                coins(POOL_A, "50000", "0x00", POOL_A, "250", TOK, "MxTHEM", "1000", "0x00"),
                coins(POOL_A, "51000", "0x00", POOL_A, "245.1", TOK, "MxTHEM", "4.9", TOK)));

        PoolStatement.Report r = PoolStatement.build(p);

        assertEquals(0, r.tradeRows);
        assertTrue("their trading is still reflected in the live reserves",
                r.csv.contains("\"Now in pool\",\"51000\",\"MINIMA\",\"245.1\",\"mxUSDT\""));
    }

    @Test public void theFileSaysWhatItsTradeRowsCover() {
        assertTrue(PoolStatement.build(params()).csv.contains("YOUR transactions only"));
    }

    @Test public void keepAliveIsCountedButMovesNothingInThisPool() {
        // Keep-fresh recreates the reserves untouched and pays its dust to the shared registry, so this
        // pool's flow is zero — it must not become a transaction row full of zeroes.
        PoolStatement.Params p = params(pool(POOL_A, "168000", "800"));
        p.rows.add(entry(10, "sent",
                deltas("0x00", "-168000", TOK, "-800"),
                coins(MINE, "168000", "0x00", MINE, "800", TOK),
                coins(POOL_A, "168000", "0x00", POOL_A, "800", TOK)));
        p.rows.add(entry(11, "sent",
                deltas("0x00", "-0.000000001"),
                coins(POOL_A, "168000", "0x00", POOL_A, "800", TOK, MINE, "10", "0x00"),
                coins(POOL_A, "168000", "0x00", POOL_A, "800", TOK,
                      "MxSENT", "0.000000001", "0x00", MINE, "9.999999999", "0x00")));

        PoolStatement.Report r = PoolStatement.build(p);

        assertEquals("create only", 1, r.tradeRows);
        assertTrue(r.csv.contains("\"Maintenance\",\"1 txns\""));
        assertFalse("no zero-amount row", r.csv.contains("\"POOL_KEEPALIVE\""));
    }

    /** A percentage we generate starts with + or −; defusing our OWN string would render it as '+0.21%. */
    @Test public void ourOwnPercentagesAreNotFormulaEscaped() {
        PoolStatement.Params p = params(pool(POOL_A, "180000", "752"));
        p.rows.add(entry(10, "sent",
                deltas("0x00", "-168000", TOK, "-800"),
                coins(MINE, "168000", "0x00", MINE, "800", TOK),
                coins(POOL_A, "168000", "0x00", POOL_A, "800", TOK)));

        String csv = PoolStatement.build(p).csv;

        assertTrue(csv.contains("\"+0.14%\""));
        assertTrue(csv.contains("\"-6.00%\""));
        assertFalse(csv.contains("\"'+"));
        assertFalse(csv.contains("\"'-"));
    }

    @Test public void textCellsAreDefusedButNumbersStayNumbers() {
        assertEquals("\"'=cmd|'/c calc'!A1\"", PoolStatement.text("=cmd|'/c calc'!A1"));
        assertEquals("\"say \"\"hi\"\"\"", PoolStatement.text("say \"hi\""));
        // the defusal must not touch a negative number — it would stop being one
        assertEquals("\"-1000\"", PoolStatement.num(new BigDecimal("-1000")));
        assertEquals("\"1000\"", PoolStatement.num(new BigDecimal("1E+3")));
        assertEquals("\"\"", PoolStatement.num((BigDecimal) null));
    }
}
