package com.eurobuddha.account;

import static org.junit.Assert.*;

import org.junit.Test;

/** Owner, 11 Nov 2026: AtomiX + PandaPools, or Dexicon — never both; two builds of one app never together; and a
 *  sibling runs alongside only when it declares its own correct leaf share (an older build signs any leaf). */
public class FamilyRuleTest {
    static final String D = "com.eurobuddha.dexicon", DS = "com.eurobuddha.dexicon.stock",
            A = "com.eurobuddha.atomix", AS = "com.eurobuddha.atomix.stock",
            P = "com.eurobuddha.pandapools", PS = "com.eurobuddha.pandapools.stock";
    static final int SA = AccountKeys.SLOT_ATOMIX, SP = AccountKeys.SLOT_PANDAPOOLS, NONE = -1;

    @Test public void shareAwareAtomixAndPandaPoolsRunTogether() {
        assertFalse(Family.conflicts(A, P, SP)); assertFalse(Family.conflicts(P, A, SA));
        assertFalse(Family.conflicts(AS, PS, SP)); assertFalse(Family.conflicts(A, PS, SP));
    }

    /** Review, 11 Nov: an AtomiX/PandaPools from before leaf shares signs any leaf — never alongside a share-aware one. */
    @Test public void aSiblingWithoutItsOwnDeclaredShareConflicts() {
        assertTrue("old PandaPools (no share declared)", Family.conflicts(A, P, NONE));
        assertTrue("old AtomiX (no share declared)", Family.conflicts(P, A, NONE));
        assertTrue("declares AtomiX's share", Family.conflicts(A, P, SA));
        assertTrue("declares Dexicon's share", Family.conflicts(P, A, AccountKeys.SLOT_DEXICON));
    }

    @Test public void dexiconExcludesThePairWhateverItDeclares() {
        for (String x : new String[]{A, AS, P, PS}) {
            assertTrue(D + " vs " + x, Family.conflicts(D, x, Family.slotOf(x)));
            assertTrue(x + " vs " + D, Family.conflicts(x, D, AccountKeys.SLOT_DEXICON));
            assertTrue(Family.conflicts(DS, x, Family.slotOf(x)));
        }
    }

    @Test public void twoBuildsOfOneAppShareASlotSoTheyConflict() {
        assertTrue(Family.conflicts(D, DS, 0)); assertTrue(Family.conflicts(A, AS, SA)); assertTrue(Family.conflicts(P, PS, SP));
    }

    @Test public void selfAndStrangersNeverConflict() {
        assertFalse(Family.conflicts(A, A, SA)); assertFalse(Family.conflicts(A, "com.whatsapp", NONE));
        assertFalse(Family.conflicts("org.minima.core", D, 0));
    }

    @Test public void theShareIsDefinedByThePackage() {
        assertEquals(AccountKeys.SLOT_DEXICON, Family.slotOf(DS));
        assertEquals(SA, Family.slotOf(A)); assertEquals(SA, Family.slotOf(AS));
        assertEquals(SP, Family.slotOf(P)); assertEquals(-1, Family.slotOf("com.whatsapp"));
    }
}
