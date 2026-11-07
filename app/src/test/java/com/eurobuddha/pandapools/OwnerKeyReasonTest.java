// VENDORED VERBATIM from apks/pandapools 0.9.84 app/src/test/java/com/eurobuddha/pandapools/OwnerKeyReasonTest.java by scripts/vendor-pandapools.sh — do not edit here; re-vendor.
package com.eurobuddha.pandapools;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/**
 * Why an owner key cannot sign, and what the user is told about it.
 *
 * Six different situations used to share one message that named only the first — so a user whose recipe was
 * merely imported was told to "restore the matching MinimaCore wallet backup", which is the seed-only restore
 * that CAUSES key reuse. The classification is a pure function precisely so each branch can be pinned here.
 */
public class OwnerKeyReasonTest {

    private static Pool recipe(int floor, boolean quarantined) {
        Pool p = new Pool();
        p.opk = "0x" + String.join("", java.util.Collections.nCopies(64, "a"));
        p.address = "0x" + String.join("", java.util.Collections.nCopies(64, "b"));
        p.minimumOwnerUses = floor;
        p.signingStateUnverified = quarantined;
        return p;
    }

    // ---- classification ----

    @Test public void anUnreadableReplyIsNeverReportedAsAnAbsentKey() {
        // We know nothing about the wallet, so we must not conclude the key belongs to another seed.
        assertEquals(OwnerKeyRecovery.Reason.NODE_UNREADABLE,
                OwnerKeyRecovery.classify(false, false, null, 262144, recipe(10, true), false));
    }

    @Test public void aPresentButUnparsableCountIsUnknownNotZero() {
        // Reading an unparsable counter as 0 would resume signing at the first leaf — the leak itself.
        assertEquals(OwnerKeyRecovery.Reason.NODE_UNREADABLE,
                OwnerKeyRecovery.classify(true, true, null, 262144, recipe(10, true), false));
    }

    @Test public void aReadableReplyWithoutTheKeyIsAbsent() {
        assertEquals(OwnerKeyRecovery.Reason.KEY_ABSENT,
                OwnerKeyRecovery.classify(true, false, null, 262144, recipe(10, true), false));
    }

    @Test public void anExhaustedKeyOutranksAbsenceAndQuarantine() {
        assertEquals(OwnerKeyRecovery.Reason.KEY_EXHAUSTED,
                OwnerKeyRecovery.classify(true, true, 262144, 262144, recipe(10, true), true));
    }

    @Test public void aCounterBelowTheRecordedFloorOutranksQuarantine() {
        // The incident shape: recipe recorded 590, the restored node's key reads 46.
        assertEquals(OwnerKeyRecovery.Reason.COUNTER_REGRESSED,
                OwnerKeyRecovery.classify(true, true, 46, 262144, recipe(590, true), false));
    }

    @Test public void anUnknownFloorCannotProduceACounterRegression() {
        // minimumOwnerUses defaults to -1. `uses >= -1` is always true, so a recipe with no recorded count
        // gives NO regression signal at all — the quarantine is the only thing still holding.
        assertEquals(OwnerKeyRecovery.Reason.SIGNING_QUARANTINED,
                OwnerKeyRecovery.classify(true, true, 0, 262144, recipe(-1, true), false));
    }

    @Test public void aFailedConfirmationIsReportedAsSuch() {
        assertEquals(OwnerKeyRecovery.Reason.CONFIRMATION_UNSAVED,
                OwnerKeyRecovery.classify(true, true, 900, 262144, recipe(10, true), true));
    }

    @Test public void aPlainImportedRecipeIsQuarantined() {
        assertEquals(OwnerKeyRecovery.Reason.SIGNING_QUARANTINED,
                OwnerKeyRecovery.classify(true, true, 900, 262144, recipe(10, true), false));
    }

    // ---- messages ----

    @Test public void theNodeUnreadableMessageNeverAdvisesAWalletRestore() {
        // The whole point of splitting the message: telling a user with an unreachable node to restore a
        // wallet backup pushes them toward the seed restore that causes key reuse.
        String m = blocked(OwnerKeyRecovery.Reason.NODE_UNREADABLE, null, -1).message();
        assertFalse(m, m.toLowerCase().contains("restore"));
        assertTrue(m, m.contains("Nothing was posted"));
    }

    @Test public void theQuarantineMessageWarnsAgainstASeedOnlyRestore() {
        String m = blocked(OwnerKeyRecovery.Reason.SIGNING_QUARANTINED, 900, 10).message();
        assertTrue(m, m.contains("seed phrase"));
        assertTrue(m, m.contains("NOT enough"));
    }

    @Test public void theRegressionMessageQuotesBothCountsInFull() {
        String m = blocked(OwnerKeyRecovery.Reason.COUNTER_REGRESSED, 46, 590).message();
        assertTrue(m, m.contains("46"));
        assertTrue(m, m.contains("590"));
    }

    @Test public void everyMessageIsDistinctAndNonEmpty() {
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (OwnerKeyRecovery.Reason r : OwnerKeyRecovery.Reason.values()) {
            String m = blocked(r, 46, 590).message();
            assertFalse(r.name(), m == null || m.isEmpty());
            assertTrue(r.name() + " duplicates another reason's wording", seen.add(m));
        }
    }

    @Test public void noMessageClaimsTheKeyIsSafeOrVerified() {
        // Nothing here may imply safety: an undetected reuse is still possible in every case.
        for (OwnerKeyRecovery.Reason r : OwnerKeyRecovery.Reason.values()) {
            String m = blocked(r, 46, 590).message().toLowerCase();
            assertFalse(r.name(), m.contains("is safe"));
            assertFalse(r.name(), m.contains("safe to sign"));
            assertFalse(r.name(), m.contains("unused"));
        }
    }

    @Test public void aMessageNamingAnIdentifierNeverTruncatesIt() {
        String opk = "0x" + String.join("", java.util.Collections.nCopies(64, "a"));
        String addr = "0x" + String.join("", java.util.Collections.nCopies(64, "b"));
        for (OwnerKeyRecovery.Reason r : OwnerKeyRecovery.Reason.values()) {
            String m = blocked(r, 46, 590).message();
            assertFalse(r.name() + " truncated an identifier", m.contains("…"));
            if (m.contains("Owner key:")) assertTrue(r.name(), m.contains(opk));
            if (m.contains("Pool:")) assertTrue(r.name(), m.contains(addr));
        }
    }

    // ---- picking one line out of several blocked keys ----

    @Test public void aRegressedCounterIsSurfacedAheadOfAMereQuarantine() {
        java.util.Map<String, OwnerKeyRecovery.Blocked> many = new java.util.LinkedHashMap<>();
        many.put("a", blocked(OwnerKeyRecovery.Reason.SIGNING_QUARANTINED, 900, 10));
        many.put("b", blocked(OwnerKeyRecovery.Reason.COUNTER_REGRESSED, 46, 590));
        assertEquals(OwnerKeyRecovery.Reason.COUNTER_REGRESSED, OwnerKeyRecovery.worst(many).reason);
    }

    @Test public void severityIsNotTheDeclarationOrder() {
        // A regression must outrank an absent key when summarising, even though KEY_ABSENT is declared first.
        assertTrue(OwnerKeyRecovery.Reason.COUNTER_REGRESSED.severity()
                < OwnerKeyRecovery.Reason.KEY_ABSENT.severity());
        assertNotEquals(OwnerKeyRecovery.Reason.COUNTER_REGRESSED.ordinal(),
                OwnerKeyRecovery.Reason.COUNTER_REGRESSED.severity());
    }

    @Test public void aMissingNodeBlocksEveryKeyAsUnknown() {
        java.util.Map<String, OwnerKeyRecovery.Blocked> m =
                OwnerKeyRecovery.nodeUnavailable(java.util.Arrays.asList("0xAB", "0xCD"));
        assertEquals(2, m.size());
        for (OwnerKeyRecovery.Blocked b : m.values())
            assertEquals(OwnerKeyRecovery.Reason.NODE_UNREADABLE, b.reason);
        assertTrue(m.containsKey("0xab"));   // keys are lowercased so lookups match
    }

    private static OwnerKeyRecovery.Blocked blocked(OwnerKeyRecovery.Reason r, Integer uses, int floor) {
        Pool p = recipe(floor, true);
        java.util.Map<String, OwnerKeyRecovery.Blocked> one = new java.util.LinkedHashMap<>();
        // Build one through the public surface by classifying a crafted case, then swap in the reason we want
        // by constructing directly — Blocked's constructor is package-visible, which this test shares.
        one.put(p.opk, new OwnerKeyRecovery.Blocked(p.opk, r, uses, floor, p.address));
        return one.get(p.opk);
    }

    /** Block-as-key-uses (1.1.2.31+): uses track the chain tip block, capacity is 128^4. A healthy
     *  block-mode key reporting millions of "uses" is NOT exhausted; the same number against a legacy
     *  64x3 capacity is. */
    @org.junit.Test public void blockModeCapacityJudgesExhaustion() {
        org.junit.Assert.assertEquals(OwnerKeyRecovery.Reason.SIGNING_QUARANTINED,
                OwnerKeyRecovery.classify(true, true, 2342219, 268435456, recipe(10, true), false));
        org.junit.Assert.assertEquals(OwnerKeyRecovery.Reason.KEY_EXHAUSTED,
                OwnerKeyRecovery.classify(true, true, 2342219, 262144, recipe(10, true), false));
        org.junit.Assert.assertEquals(OwnerKeyRecovery.Reason.KEY_EXHAUSTED,
                OwnerKeyRecovery.classify(true, true, 268435456, 268435456, recipe(10, true), false));
    }
}