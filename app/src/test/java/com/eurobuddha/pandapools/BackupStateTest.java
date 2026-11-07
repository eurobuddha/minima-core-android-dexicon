// VENDORED VERBATIM from apks/pandapools 0.9.86 app/src/test/java/com/eurobuddha/pandapools/BackupStateTest.java by scripts/vendor-pandapools.sh — do not edit here; re-vendor.
package com.eurobuddha.pandapools;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Whether the exported backup still covers the pools this node owns, and whether an export can be trusted.
 *
 * The on-device recipe store is excluded from cloud backup by design, so the exported file is the only thing
 * that survives a lost phone. Two failure modes are covered here: a backup that predates a pool (the digest),
 * and a backup that was written but is not actually usable (the read-back verification). The second is the
 * dangerous one, because nothing reveals it until a real recovery.
 */
public class BackupStateTest {

    private static String addr(String c) { return "0x" + String.join("", Collections.nCopies(64, c)); }

    private static Pool pool(String addressChar) {
        Pool p = new Pool();
        p.address = addr(addressChar);
        p.opk = "0x" + String.join("", Collections.nCopies(64, "1"));
        p.oadr = "0x" + String.join("", Collections.nCopies(64, "2"));
        p.tok = "0x7D39745FBD29049BE29850B55A18BF550E4D442F930F86266E34193D89042A90";
        p.tokDecimals = 8;
        p.kmin = "50";
        return p;
    }

    /** A backup entry that genuinely re-derives its covenant, as Recovery.validRecipe demands. */
    private static JSONObject entry(Pool p) throws Exception {
        return new JSONObject()
                .put("addr", p.address).put("opk", p.opk).put("oadr", p.oadr).put("tok", p.tok)
                .put("dec", p.tokDecimals).put("kmin", p.kmin)
                .put("script", PoolCovenant.script(p.opk, p.oadr, p.tok, p.kmin));
    }

    private static String file(int version, Pool... pools) throws Exception {
        JSONArray arr = new JSONArray();
        for (Pool p : pools) arr.put(entry(p));
        return new JSONObject().put("pandapools_backup", version).put("pools", arr).toString();
    }

    // ---- the digest ----

    @Test public void theDigestIsOrderIndependentAndCaseInsensitive() {
        Pool a = pool("a"), b = pool("b");
        String one = BackupState.digest(Arrays.asList(a, b));
        String two = BackupState.digest(Arrays.asList(b, a));
        assertEquals("pool order must not change the digest", one, two);

        Pool upper = pool("a");
        upper.address = upper.address.toUpperCase();
        assertEquals("address case must not change the digest",
                BackupState.digest(Collections.singletonList(a)),
                BackupState.digest(Collections.singletonList(upper)));
    }

    @Test public void addingAPoolChangesTheDigest() {
        Pool a = pool("a");
        String before = BackupState.digest(Collections.singletonList(a));
        String after = BackupState.digest(Arrays.asList(a, pool("b")));
        assertNotEquals("a new pool must invalidate an older backup", before, after);
    }

    @Test public void theDigestNeverTruncatesAnAddress() {
        // The canonical string is hashed, so a truncating implementation would still produce a digest — but two
        // pools sharing a prefix would then collide, and a backup covering one would look like it covered both.
        Pool a = pool("a");
        Pool nearlyA = pool("a");
        nearlyA.address = a.address.substring(0, 60) + "beef";
        assertNotEquals(BackupState.digest(Collections.singletonList(a)),
                BackupState.digest(Collections.singletonList(nearlyA)));
    }

    @Test public void anEmptySetHasAStableDigest() {
        assertEquals(BackupState.digest(new ArrayList<>()), BackupState.digest(new ArrayList<>()));
        assertFalse(BackupState.digest(new ArrayList<>()).isEmpty());
    }

    // ---- verifying a written file ----

    @Test public void averifiedExportCoversEveryOwnedPool() throws Exception {
        Pool a = pool("a"), b = pool("b");
        String json = file(Recovery.BACKUP_VERSION, a, b);
        assertNull(BackupState.verifyExport(json, Arrays.asList(a, b),
                json.getBytes(java.nio.charset.StandardCharsets.UTF_8).length));
    }

    @Test public void aFileMissingAnOwnedPoolIsRejectedAndNamesItInFull() throws Exception {
        Pool a = pool("a"), b = pool("b");
        String json = file(Recovery.BACKUP_VERSION, a);
        String problem = BackupState.verifyExport(json, Arrays.asList(a, b), 0);
        assertNotNull(problem);
        assertTrue(problem, problem.contains(b.address));   // full address, so the user can act on it
        assertFalse(problem, problem.contains("…"));
    }

    @Test public void aTruncatedFileIsRejectedOnItsByteCount() throws Exception {
        // The case that motivates reading back at all: a partially-written file can still parse.
        Pool a = pool("a");
        String json = file(Recovery.BACKUP_VERSION, a);
        String problem = BackupState.verifyExport(json, Collections.singletonList(a), json.length() + 40);
        assertNotNull(problem);
        assertTrue(problem, problem.contains("truncated"));
    }

    @Test public void unreadableAndEmptyFilesAreRejected() {
        assertNotNull(BackupState.verifyExport(null, new ArrayList<>(), 0));
        assertNotNull(BackupState.verifyExport("", new ArrayList<>(), 0));
        assertNotNull(BackupState.verifyExport("{not json", new ArrayList<>(), 0));
        assertNotNull(BackupState.verifyExport("{\"pandapools_backup\":3}", new ArrayList<>(), 0));
    }

    @Test public void anUnsupportedVersionIsRejectedInBothDirections() throws Exception {
        Pool a = pool("a");
        assertNotNull(BackupState.verifyExport(file(0, a), Collections.singletonList(a), 0));
        assertNotNull(BackupState.verifyExport(file(Recovery.BACKUP_VERSION + 1, a), Collections.singletonList(a), 0));
    }

    @Test public void anEntryWhoseRecipeDoesNotRederiveIsRejected() throws Exception {
        // A file can name the right address and still be useless if its recipe does not rebuild that covenant.
        Pool a = pool("a");
        JSONObject bad = entry(a).put("kmin", "999999");   // script no longer matches the stated params
        String json = new JSONObject().put("pandapools_backup", Recovery.BACKUP_VERSION)
                .put("pools", new JSONArray().put(bad)).toString();
        String problem = BackupState.verifyExport(json, Collections.singletonList(a), 0);
        assertNotNull(problem);
        assertTrue(problem, problem.contains("not a usable recipe"));
    }

    @Test public void extraPoolsInTheFileAreHarmless() throws Exception {
        // A backup covering more than you currently own is fine — a closed pool keeps its recipe.
        Pool a = pool("a");
        String json = file(Recovery.BACKUP_VERSION, a, pool("b"), pool("c"));
        assertNull(BackupState.verifyExport(json, Collections.singletonList(a), 0));
    }

    @Test public void ownedPoolsWithoutAnAddressAreSkippedRatherThanFailing() throws Exception {
        Pool a = pool("a");
        Pool blank = pool("b");
        blank.address = "";
        List<Pool> owned = Arrays.asList(a, blank);
        assertNull(BackupState.verifyExport(file(Recovery.BACKUP_VERSION, a), owned, 0));
    }
}
