// VENDORED VERBATIM from apks/pandapools 0.9.88 app/src/test/java/com/eurobuddha/pandapools/OwnPoolRecordTest.java by scripts/vendor-pandapools.sh — do not edit here; re-vendor.
package com.eurobuddha.pandapools;

import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * What a stored recipe records about provenance and retirement.
 *
 * THE INCIDENT THIS PINS. On 0.9.56 a user with two pools and no recovery problems saw FOUR amber cards:
 * two "Saved pool · reserves unavailable" and two "Owner signing paused". Both causes live in this file's
 * subject, {@link OwnPoolStore#mergeRecord}:
 *
 *   1. Close and migrate kept the old recipe forever (OwnPoolStore.remove() had no callers), so every
 *      pool ever closed left an undismissable card behind. The fix RETIRES rather than deletes, because a
 *      recipe is the only thing that can reclaim a pool and a posted close can still fail to land — so
 *      retirement must survive a re-record and must be reversible.
 *   2. The rediscovered-pool card was blamed on the signing quarantine, and 0.9.58 cleared it at the
 *      backfill. That was WRONG and 0.9.60 reverted it: holding the key is not holding the counter, and a
 *      cleared hold lets keep-fresh sign unattended after a seed restore. The card was confusing because it
 *      was unlabelled and duplicated, which is fixed elsewhere; the quarantine itself was right.
 */
public class OwnPoolRecordTest {

    private static Pool pool() {
        Pool p = new Pool();
        p.address = "0x" + String.join("", java.util.Collections.nCopies(64, "a"));
        p.opk = "0x" + String.join("", java.util.Collections.nCopies(64, "b"));
        p.oadr = "0x" + String.join("", java.util.Collections.nCopies(64, "c"));
        p.tok = "0x" + String.join("", java.util.Collections.nCopies(64, "d"));
        p.kmin = "100";
        p.covenantScript = "RETURN TRUE";
        return p;
    }

    // ---- provenance: the quarantine must not catch your own pool ----

    @Test public void rediscoveringAnOwnedPoolStillQuarantinesIt() throws Exception {
        // 0.9.58 got this WRONG and 0.9.60 reverted it. The backfill asserted provenance because mine(p)
        // proves the pool is ours — but that proves the wallet HOLDS the key, not that it holds the NEWEST
        // COUNTER. A seed restore re-creates $OPK at uses = 0, mine(p) goes true, and a cleared hold lets
        // PoolRefresher sign UNATTENDED at a leaf the dead device already spent. Erring high costs an amber
        // card; erring low costs the pool. Never let the backfill speak for the counter.
        Pool p = pool();                                  // scanner-built: no provenance, floor still -1
        assertTrue("a rediscovered pool must stay held — holding the key is not holding the counter",
                OwnPoolStore.mergeRecord(p, "").optBoolean("signing_unverified", false));
    }

    @Test public void onlyTheCreatingInstallMayClaimCurrentOwnerState() throws Exception {
        // The flag is legitimate in exactly one place: PoolManager, at the moment THIS install posts the
        // create, where the counter provenance is actually known.
        Pool p = pool();
        p.newlyCreatedWithCurrentOwnerState = true;
        assertFalse(OwnPoolStore.mergeRecord(p, "").optBoolean("signing_unverified", true));
    }

    @Test public void anImportedRecipeIsStillQuarantined() throws Exception {
        // The case the rule was written for: a recipe with no provenance, from a restored file.
        assertTrue("an unproven recipe must stay held",
                OwnPoolStore.mergeRecord(pool(), "").optBoolean("signing_unverified", false));
    }

    @Test public void aHoldIsNeverSilentlyLifted() throws Exception {
        // Only the owner's explicit attestation clears a hold; a routine re-record must not.
        Pool p = pool();
        p.newlyCreatedWithCurrentOwnerState = true;
        String held = new JSONObject().put("signing_unverified", true).toString();
        assertTrue("an existing hold outranks any provenance claim",
                OwnPoolStore.mergeRecord(p, held).optBoolean("signing_unverified", false));
    }

    // ---- retirement: hidden, never thrown away ----

    @Test public void retirementSurvivesAReRecord() throws Exception {
        String retired = new JSONObject().put("retired", true).toString();
        assertTrue("a closed pool must not reappear because something refreshed its recipe",
                OwnPoolStore.mergeRecord(pool(), retired).optBoolean("retired", false));
    }

    @Test public void aLivePoolIsNotRetiredByDefault() throws Exception {
        assertFalse(OwnPoolStore.mergeRecord(pool(), "").optBoolean("retired", false));
        assertFalse(OwnPoolStore.mergeRecord(pool(), new JSONObject().toString()).optBoolean("retired", false));
    }

    @Test public void retiringKeepsEverythingNeededToReclaimThePool() throws Exception {
        // The whole point of retiring instead of deleting: the recipe still has to be able to recover the
        // pool, because a close that was posted is not a close that landed.
        Pool p = pool();
        JSONObject o = OwnPoolStore.mergeRecord(p, new JSONObject().put("retired", true).toString());
        assertTrue(o.optBoolean("retired", false));
        for (String field : new String[]{ "addr", "opk", "oadr", "tok", "kmin", "script" }) {
            assertTrue("retiring must not drop " + field, o.optString(field, "").length() > 0);
        }
    }
}
