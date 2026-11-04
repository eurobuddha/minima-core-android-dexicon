package com.eurobuddha.dexicon;

import com.eurobuddha.atomix.TradingContext;
import com.eurobuddha.atomix.swap.SwapDb;
import com.eurobuddha.dexicon.doors.DoorPlan;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

/**
 * AtomiX's trade export (apks/atomix MainActivity.doExportCsv): ALL my AtomiX swaps, maker and taker, one row each,
 * with the on-chain leg tx ids joined from the events log. The header is AtomiX's, byte for byte — the reconciliation
 * ledger (tools/dexHistory/atomix_ledger.py) reads these columns BY NAME, "Price (USDT/MINIMA)" included; columns are
 * only ever appended. One difference, RULE 2: the Minima dollar is written MxUSD, never mxUSDT (the ledger tests a
 * token cell only for "USDT", and itself renames mxUSDT to MxUSD).
 */
final class AtomixCsv {
    private AtomixCsv() {}

    static final String HEADER = "Date,Role,Direction,Sold Amount,Sold Token,Bought Amount,Bought Token,Price (USDT/MINIMA),Counterparty,Status,Contract Id,Minima Tx,Eth Tx,Currency";
    static final String FILE = "atomix-trades.csv";

    static String csv(List<SwapDb.Swap> all, Function<String, List<SwapDb.Event>> events) {
        SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ENGLISH);
        fmt.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
        StringBuilder sb = new StringBuilder(HEADER).append("\r\n");
        for (SwapDb.Swap s : all) {
            List<SwapDb.Event> ev = events.apply(s.hash);
            if (ev == null) ev = new ArrayList<>();
            String[] cells = {
                s.created > 0 ? fmt.format(new Date(s.created)) : "", role(s.role), direction(s),
                s.sellAmount, token(s.sellToken), s.buyAmount, token(s.buyToken), price(s),
                s.counterparty, s.status == null ? "" : s.status.toLowerCase(Locale.ENGLISH),
                s.contractId, legTx(ev, true), legTx(ev, false), currency(s)
            };
            for (int i = 0; i < cells.length; i++) { if (i > 0) sb.append(','); sb.append(cell(cells[i])); }
            sb.append("\r\n");
        }
        return sb.toString();
    }

    static String role(String r) { return "RESPONDER".equals(r) ? "Maker" : "INITIATOR".equals(r) ? "Taker" : r == null ? "" : r; }

    /** The row's own Minima-leg coin, MxUSD or MINIMA. "" when unattributable. */
    private static String coin(SwapDb.Swap s) {
        TradingContext c = TradingContext.forSwap(s.sellToken, s.buyToken);
        return c == null ? null : DoorPlan.coin(c);
    }
    static String currency(SwapDb.Swap s) { String c = coin(s); return c == null ? "" : c; }

    /** "Sell <coin>" / "Buy <coin>": the direction constants are shared by both markets. */
    static String direction(SwapDb.Swap s) {
        String c = coin(s); if (c == null) c = "MINIMA";
        if ("MINIMA_TO_ERC20".equals(s.direction)) return "Sell " + c;
        if ("ERC20_TO_MINIMA".equals(s.direction)) return "Buy " + c;
        return s.direction == null ? "" : s.direction;
    }

    /** A raw tokenid or an old label becomes MINIMA / MxUSD; an Ethereum symbol ("USDT") stays exactly as written. */
    static String token(String t) {
        if (t == null) return "";
        String l = t.toLowerCase(Locale.ENGLISH);
        if (l.equals("0x00")) return "MINIMA";
        if (l.contains("7d39745")) return "MxUSD";
        TradingContext c = TradingContext.forCoinLabel(t);
        return c == null ? (l.equals("mxusd") ? "MxUSD" : t) : DoorPlan.coin(c);
    }

    /** USDT per coin, comparable across both directions; blank if a leg amount is missing or zero. */
    static String price(SwapDb.Swap s) {
        double sell, buy;
        try { sell = Double.parseDouble(s.sellAmount); buy = Double.parseDouble(s.buyAmount); } catch (Exception e) { return ""; }
        if (!(sell > 0) || !(buy > 0)) return "";
        double p = "MINIMA_TO_ERC20".equals(s.direction) ? buy / sell : "ERC20_TO_MINIMA".equals(s.direction) ? sell / buy : 0;
        if (!(p > 0)) return "";
        return new java.math.BigDecimal(p).round(new java.math.MathContext(8)).stripTrailingZeros().toPlainString();
    }

    /** The real on-chain tx ids of one leg, ";"-joined: the Minima leg logs token "minima", the Ethereum leg "ETH:<addr>".
     *  A refunded-leg contract READ is never a tx (AtomiX 0.1.96). */
    static String legTx(List<SwapDb.Event> ev, boolean minimaLeg) {
        List<String> seen = new ArrayList<>();
        for (SwapDb.Event e : ev) {
            if (SwapDb.EV_ETH_LEG_REFUNDED.equals(e.event)) continue;
            boolean match = minimaLeg ? "minima".equals(e.token) : e.token != null && e.token.startsWith("ETH");
            if (match && e.note != null && e.note.matches("0x[0-9a-fA-F]{16,}") && !seen.contains(e.note)) seen.add(e.note);
        }
        return String.join(";", seen);
    }

    /** Always double-quoted; a leading = + - @ TAB CR is neutralised with ' (spreadsheet formula injection). */
    static String cell(String s) {
        if (s == null) s = "";
        if (!s.isEmpty() && "=+-@\t\r".indexOf(s.charAt(0)) >= 0) s = "'" + s;
        return "\"" + s.replace("\"", "\"\"") + "\"";
    }
}
