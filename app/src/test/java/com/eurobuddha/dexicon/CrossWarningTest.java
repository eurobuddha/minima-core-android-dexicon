package com.eurobuddha.dexicon;

import static org.junit.Assert.*;

import org.junit.Test;

/** Owner 29 Oct: switching auto-cross on must say exactly what would cross right now, before it does. */
public class CrossWarningTest {
    @Test public void saysWhatWouldCrossNow() {
        String w = MarketMaker.crossWarning("pool 0.85% above your best ask — sell 1500 MINIMA @ avg 0.003169164227 (auto-cross off)", "3");
        assertTrue(w.contains("YES — pool 0.85% above your best ask — sell 1500 MINIMA @ avg 0.003169164227.\n"));
        assertFalse(w.contains("(auto-cross off)"));
        assertTrue(w.contains("Up to 3 trades an hour"));
    }
    @Test public void saysNothingWhenInLine() {
        assertTrue(MarketMaker.crossWarning("pool in line with your ladder", "5").contains("Nothing. The pool is in line"));
    }
    @Test public void saysUnknownBeforeTheLadderRuns() {
        assertTrue(MarketMaker.crossWarning("", "").contains("Not known yet"));
        assertTrue(MarketMaker.crossWarning("waiting for the MEXC price", "").contains("Not known yet"));
    }
}
