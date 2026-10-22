package com.eurobuddha.dexicon.resting;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.math.BigDecimal;

public class SourcePickTest {
    private static CompositeRouter.Plan plan(String minima, String eff) {
        CompositeRouter.Plan p = new CompositeRouter.Plan();
        p.poolRoute = new PoolRouter.Route(); p.poolRoute.ok = true;   // non-empty
        p.totalMinima = new BigDecimal(minima); p.effectivePrice = new BigDecimal(eff);
        return p;
    }
    private static final BigDecimal Q = new BigDecimal("10");

    @Test public void cheaperRestingWinsABuyOnlyIfItFillsTheWholeLevel() {
        assertTrue(SourcePick.restingFirst(plan("10", "0.040"), Q, new BigDecimal("0.041"), true));
        assertFalse("partial resting fill loses", SourcePick.restingFirst(plan("9", "0.040"), Q, new BigDecimal("0.041"), true));
        assertFalse("tie goes to FAST", SourcePick.restingFirst(plan("10", "0.041"), Q, new BigDecimal("0.041"), true));
    }

    @Test public void sellWantsTheHigherPrice() {
        assertTrue(SourcePick.restingFirst(plan("10", "0.042"), Q, new BigDecimal("0.041"), false));
        assertFalse(SourcePick.restingFirst(plan("10", "0.040"), Q, new BigDecimal("0.041"), false));
    }

    @Test public void noFastLevelMeansAnyRestingPlan() {
        assertTrue(SourcePick.restingFirst(plan("1", "0.05"), Q, null, true));
        assertFalse(SourcePick.restingFirst(new CompositeRouter.Plan(), Q, null, true));
    }
}
