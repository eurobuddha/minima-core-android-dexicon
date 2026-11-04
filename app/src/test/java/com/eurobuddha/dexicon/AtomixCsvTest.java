package com.eurobuddha.dexicon;

import static org.junit.Assert.*;

import com.eurobuddha.atomix.swap.SwapDb;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** 0.2.1: AtomiX's CSV, header byte for byte (dexHistory reads it by column name), MxUSD never mxUSDT. */
public class AtomixCsvTest {
    private static SwapDb.Event ev(String event, String token, String note) {
        SwapDb.Event e = new SwapDb.Event(); e.event = event; e.token = token; e.note = note; return e;
    }

    @Test public void headerIsAtomixsExactly() {
        assertEquals("Date,Role,Direction,Sold Amount,Sold Token,Bought Amount,Bought Token,Price (USDT/MINIMA),Counterparty,Status,Contract Id,Minima Tx,Eth Tx,Currency",
                AtomixCsv.HEADER);
        assertEquals(AtomixCsv.HEADER + "\r\n", AtomixCsv.csv(Collections.emptyList(), h -> null));
    }

    @Test public void aMakerSellOfMxusdIsOneFullRow() {
        SwapDb.Swap s = new SwapDb.Swap();
        s.hash = "0xaa"; s.role = "RESPONDER"; s.direction = "MINIMA_TO_ERC20";
        s.sellToken = "mxUSDT"; s.sellAmount = "5"; s.buyToken = "USDT"; s.buyAmount = "4.99";
        s.status = "COMPLETE"; s.counterparty = "0x" + "cd".repeat(32); s.contractId = "0x" + "ee".repeat(32); s.created = 1_700_000_000_000L;
        String minimaTx = "0x" + "11".repeat(32), ethTx = "0x" + "22".repeat(32);
        List<SwapDb.Event> evs = new ArrayList<>(Arrays.asList(ev(SwapDb.EV_CPSENT, "minima", minimaTx), ev(SwapDb.EV_COLLECT, "ETH:0xabc", ethTx),
                ev(SwapDb.EV_ETH_LEG_REFUNDED, "ETH", "0x" + "33".repeat(32)), ev(SwapDb.EV_COLLECT, "ETH:0xabc", "confirmed on-chain")));
        String row = AtomixCsv.csv(Collections.singletonList(s), h -> evs).split("\r\n")[1];
        assertEquals("\"2023-11-14T22:13:20.000Z\",\"Maker\",\"Sell MxUSD\",\"5\",\"MxUSD\",\"4.99\",\"USDT\",\"0.998\",\"" + s.counterparty + "\",\"complete\",\""
                + s.contractId + "\",\"" + minimaTx + "\",\"" + ethTx + "\",\"MxUSD\"", row);
        assertFalse("RULE 2", row.contains("mxUSDT"));
    }

    @Test public void cellsAreQuotedAndFormulaSafe() {
        assertEquals("\"'=1+1\"", AtomixCsv.cell("=1+1"));
        assertEquals("\"a\"\"b\"", AtomixCsv.cell("a\"b"));
        assertEquals("\"\"", AtomixCsv.cell(null));
    }
}
