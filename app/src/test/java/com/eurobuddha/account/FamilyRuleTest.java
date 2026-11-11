package com.eurobuddha.account;

import static org.junit.Assert.*;

import org.junit.Test;

/** Owner, 11 Nov 2026: AtomiX + PandaPools, or Dexicon — never both; two builds of one app never together. */
public class FamilyRuleTest {
    static final String D = "com.eurobuddha.dexicon", DS = "com.eurobuddha.dexicon.stock",
            A = "com.eurobuddha.atomix", AS = "com.eurobuddha.atomix.stock",
            P = "com.eurobuddha.pandapools", PS = "com.eurobuddha.pandapools.stock";

    @Test public void atomixAndPandaPoolsRunTogether() {
        assertFalse(Family.conflicts(A, P)); assertFalse(Family.conflicts(P, A));
        assertFalse(Family.conflicts(AS, PS)); assertFalse(Family.conflicts(A, PS));
    }

    @Test public void dexiconExcludesThePair() {
        for (String x : new String[]{A, AS, P, PS}) {
            assertTrue(D + " vs " + x, Family.conflicts(D, x)); assertTrue(x + " vs " + D, Family.conflicts(x, D));
            assertTrue(Family.conflicts(DS, x));
        }
    }

    @Test public void twoBuildsOfOneAppShareASlotSoTheyConflict() {
        assertTrue(Family.conflicts(D, DS)); assertTrue(Family.conflicts(A, AS)); assertTrue(Family.conflicts(P, PS));
    }

    @Test public void selfAndStrangersNeverConflict() {
        assertFalse(Family.conflicts(A, A)); assertFalse(Family.conflicts(A, "com.whatsapp")); assertFalse(Family.conflicts("org.minima.core", D));
    }
}
