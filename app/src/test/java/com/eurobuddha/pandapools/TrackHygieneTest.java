// VENDORED VERBATIM from apks/pandapools 0.10.9 app/src/test/java/com/eurobuddha/pandapools/TrackHygieneTest.java by scripts/vendor-pandapools.sh — do not edit here; re-vendor.
package com.eurobuddha.pandapools;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Test;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * {@link TrackHygiene} decides which of the node's tracked scripts get demoted/untracked by the launch
 * sweep, and whether the swap path may register a covenant at all. Getting either wrong is fund-adjacent:
 * demoting an OWN pool would (after a node restart) hide the LP's reserves from close/migrate, and a wrong
 * "row exists" answer would let {@code newscript} REPLACE — and downgrade — an owned {@code trackall:true}
 * row. These tests pin the classification and the reply-shape tolerance (the node returns {@code track} as
 * boolean or string, {@code status:false} arrives via the SUCCESS callback).
 */
public class TrackHygieneTest {

    private static final String MY_OPK      = "0xAAAA00000000000000000000000000000000000000000000000000000000AAAA";
    private static final String FOREIGN_OPK = "0xBBBB00000000000000000000000000000000000000000000000000000000BBBB";
    private static final String STORE_OPK   = "0xCCCC00000000000000000000000000000000000000000000000000000000CCCC";
    private static final String OADR        = "0xDDDD00000000000000000000000000000000000000000000000000000000DDDD";
    private static final String TOK         = "0xEEEE00000000000000000000000000000000000000000000000000000000EEEE";

    private static String covenant(String opk) { return PoolCovenant.script(opk, OADR, TOK, "716.20412"); }

    private static JSONObject scriptRow(String address, String script, Object track) throws JSONException {
        return new JSONObject().put("address", address).put("script", script).put("track", track);
    }

    private static Set<String> lower(String... v) {
        Set<String> s = new HashSet<>();
        for (String x : v) s.add(x.toLowerCase());
        return s;
    }

    // ---- covenant recognition ----

    @Test public void recognisesARealCovenantAndRejectsOtherScripts() {
        assertTrue(TrackHygiene.isCovenantScript(covenant(FOREIGN_OPK)));
        assertFalse("simple address script is not a covenant", TrackHygiene.isCovenantScript("RETURN SIGNEDBY(0x1234)"));
        assertFalse(TrackHygiene.isCovenantScript(""));
        assertFalse(TrackHygiene.isCovenantScript(null));
        // one marker alone is not enough
        assertFalse(TrackHygiene.isCovenantScript("RETURN GTE MAX(x*y 5)"));
    }

    @Test public void extractsTheEmbeddedOwnerKey() {
        assertEquals(FOREIGN_OPK, TrackHygiene.opkOf(covenant(FOREIGN_OPK)));
        assertNull(TrackHygiene.opkOf("no key here"));
        assertNull(TrackHygiene.opkOf(null));
    }

    // ---- classification ----

    @Test public void classifiesForeignAndSparesEveryOwnershipRoute() throws JSONException {
        String ownByKey    = covenant(MY_OPK);
        String ownByStore  = covenant(STORE_OPK);
        String foreign     = covenant(FOREIGN_OPK);
        JSONArray reply = new JSONArray()
                .put(scriptRow("0x1111", ownByKey, true))                       // own via keystore → spared
                .put(scriptRow("0x2222", ownByStore, true))                     // own via OwnPoolStore opk → spared
                .put(scriptRow("0x3333", foreign, true))                        // foreign, still tracked
                .put(scriptRow("0x4444", foreign, false))                       // foreign, already demoted
                .put(scriptRow("0x5555", "RETURN SIGNEDBY(0x9999)", true));     // not a covenant → ignored
        List<TrackHygiene.Row> out = TrackHygiene.classifyForeign(
                reply, lower(MY_OPK), Collections.emptySet(), lower(STORE_OPK));
        assertEquals(2, out.size());
        assertEquals("0x3333", out.get(0).address);
        assertTrue(out.get(0).track);
        assertEquals(foreign, out.get(0).script);   // VERBATIM script carried for re-registration
        assertEquals("0x4444", out.get(1).address);
        assertFalse(out.get(1).track);
    }

    @Test public void ownPoolStoreAddressSparesARowEvenWhenTheKeyIsUnknown() throws JSONException {
        // e.g. a just-restored node whose keys haven't regenerated yet, but the recipe knows the address
        JSONArray reply = new JSONArray().put(scriptRow("0xAB12", covenant(FOREIGN_OPK), true));
        List<TrackHygiene.Row> out = TrackHygiene.classifyForeign(
                reply, lower(MY_OPK), lower("0xab12"), Collections.emptySet());
        assertTrue(out.isEmpty());
    }

    @Test public void unparsableOpkIsNeverTouched() throws JSONException {
        // a string that carries both covenant markers but no SIGNEDBY(0x…) — ownership unprovable → skip
        String odd = "VERIFYOUT(@INPUT @ADDRESS nx 0x00 FALSE) RETURN (nx-fx)*(ny-fy) GTE MAX(x*y 5)";
        assertTrue(TrackHygiene.isCovenantScript(odd));
        JSONArray reply = new JSONArray().put(scriptRow("0x7777", odd, true));
        assertTrue(TrackHygiene.classifyForeign(reply, lower(MY_OPK),
                Collections.emptySet(), Collections.emptySet()).isEmpty());
    }

    @Test public void emptyKeySetMeansZeroClassifications() throws JSONException {
        // fail-safe: a failed/empty `keys` read must produce NO demotions, however foreign the rows look
        JSONArray reply = new JSONArray().put(scriptRow("0x3333", covenant(FOREIGN_OPK), true));
        assertTrue(TrackHygiene.classifyForeign(reply, Collections.emptySet(),
                Collections.emptySet(), Collections.emptySet()).isEmpty());
        assertTrue(TrackHygiene.classifyForeign(reply, null,
                Collections.emptySet(), Collections.emptySet()).isEmpty());
        assertTrue("null reply tolerated", TrackHygiene.classifyForeign(null, lower(MY_OPK),
                Collections.emptySet(), Collections.emptySet()).isEmpty());
    }

    @Test public void trackFlagToleratesBooleanAndStringShapes() throws JSONException {
        String foreign = covenant(FOREIGN_OPK);
        JSONArray reply = new JSONArray()
                .put(scriptRow("0x1000", foreign, "true"))
                .put(scriptRow("0x2000", foreign, "false"))
                .put(scriptRow("0x3000", foreign, Boolean.TRUE))
                .put(scriptRow("0x4000", foreign, 1));
        List<TrackHygiene.Row> out = TrackHygiene.classifyForeign(
                reply, lower(MY_OPK), Collections.emptySet(), Collections.emptySet());
        assertEquals(4, out.size());
        assertTrue(out.get(0).track);
        assertFalse(out.get(1).track);
        assertTrue(out.get(2).track);
        assertTrue(out.get(3).track);
    }

    // ---- coin selection ----

    @Test public void selectsBothLegsAtForeignAddressesOnly() throws JSONException {
        JSONArray coins = new JSONArray()
                .put(new JSONObject().put("coinid", "0xC0DE01").put("address", "0xF00D").put("tokenid", "0x00"))
                .put(new JSONObject().put("coinid", "0xC0DE02").put("address", "0xf00d").put("tokenid", TOK))  // case-folded
                .put(new JSONObject().put("coinid", "0xC0DE03").put("address", "0xMINE").put("tokenid", "0x00"))
                .put(new JSONObject().put("coinid", "").put("address", "0xF00D"));                              // no coinid → skip
        List<String> ids = TrackHygiene.coinsToUntrack(coins, lower("0xF00D"));
        assertEquals(2, ids.size());
        assertEquals("0xC0DE01", ids.get(0));
        assertEquals("0xC0DE02", ids.get(1));
        assertTrue(TrackHygiene.coinsToUntrack(coins, Collections.emptySet()).isEmpty());
        assertTrue(TrackHygiene.coinsToUntrack(null, lower("0xF00D")).isEmpty());
    }

    // ---- swap-path row guard ----

    @Test public void rowExistsRequiresStatusAndAResponseRow() throws JSONException {
        JSONObject row = new JSONObject().put("address", "0x3333").put("track", false);
        assertTrue(TrackHygiene.rowExists(new JSONObject().put("status", true).put("response", row)));
        // array shape tolerated
        assertTrue(TrackHygiene.rowExists(new JSONObject().put("status", true)
                .put("response", new JSONArray().put(row))));
        assertFalse("not-found arrives as status:false via the SUCCESS callback",
                TrackHygiene.rowExists(new JSONObject().put("status", false)
                        .put("error", "Script with that address not found")));
        assertFalse("status:true with no response row is NOT proof of existence",
                TrackHygiene.rowExists(new JSONObject().put("status", true)));
        assertFalse(TrackHygiene.rowExists(new JSONObject().put("status", true)
                .put("response", new JSONArray())));
        assertFalse(TrackHygiene.rowExists(null));
        // string status tolerated (matches TxPost.truthy semantics)
        assertTrue(TrackHygiene.rowExists(new JSONObject().put("status", "true").put("response", row)));
    }

    @Test public void isNotFoundFiresOnlyOnTheAffirmativeNodeError() throws JSONException {
        assertTrue(TrackHygiene.isNotFound(new JSONObject().put("status", false)
                .put("error", "Script with that address not found")));
        assertTrue("case-insensitive", TrackHygiene.isNotFound(new JSONObject().put("status", false)
                .put("error", "NOT FOUND")));
        assertFalse("an ambiguous failure must NOT trigger a write — newscript REPLACES rows",
                TrackHygiene.isNotFound(new JSONObject().put("status", false).put("error", "IPC read timeout")));
        assertFalse(TrackHygiene.isNotFound(new JSONObject().put("status", false)));
        assertFalse("a successful reply is never not-found",
                TrackHygiene.isNotFound(new JSONObject().put("status", true)));
        assertFalse(TrackHygiene.isNotFound(null));
    }
}
