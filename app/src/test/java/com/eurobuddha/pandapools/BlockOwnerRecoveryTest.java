// VENDORED VERBATIM from apks/pandapools 0.10.12 app/src/test/java/com/eurobuddha/pandapools/BlockOwnerRecoveryTest.java by scripts/vendor-pandapools.sh — do not edit here; re-vendor.
package com.eurobuddha.pandapools;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public class BlockOwnerRecoveryTest {
    static final long NOW = 1_800_000_000_000L;
    static final int HEIGHT = 2_400_000;
    static String key(int i) { return String.format(java.util.Locale.ROOT, "0x%064x", i + 1); }
    static JSONObject ok(Object response) throws Exception { return new JSONObject().put("status", true).put("response", response); }
    static JSONObject keyRow(int i, String pk, int uses) throws Exception {
        return new JSONObject().put("publickey", pk).put("modifier", "0x" + Integer.toHexString(i))
                .put("uses", uses).put("size", 128).put("depth", 4);
    }
    static Pool pool(int index) {
        Pool p = new Pool(); p.opk = key(index); p.kidx = index; p.address = key(500 + index);
        p.minimumOwnerUses = HEIGHT - 1000; p.signingStateUnverified = true;
        return p;
    }
    static class Harness implements BlockOwnerRecovery.State {
        final ArrayDeque<Runnable> tasks = new ArrayDeque<>();
        final List<String> calls = new ArrayList<>();
        final List<JSONObject> keys = new ArrayList<>();
        final List<Pool> pools = new ArrayList<>();
        final Map<String, Integer> limits = new HashMap<>();
        BlockOwnerRecovery.Result result;
        int completions, confirms, probes, floor = HEIGHT - 1000;
        long blockTime = NOW;
        boolean classic, unknownMode, locked, duplicate, corruptTotal, foreign, failedCommit,
                failMint, timeoutSign, failSign, pendingSign, badSignature, noCounterAdvance,
                unreadableAfterSign, switchWallet, switchMode, classicOwner, failedLimit, throwCommit, throwLimit;
        Harness(int count, int target) throws Exception {
            for (int i = 0; i < count; i++) keys.add(keyRow(i, key(i), i == target ? HEIGHT - 1000 : 0));
            pools.add(pool(target));
        }
        public int searchLimit(String fp, String owner, int total) {
            if (throwLimit) throw new IllegalStateException("Unreadable preferences");
            if (failedLimit) return -1;
            return limits.computeIfAbsent(fp + owner, ignored -> total + BlockOwnerRecovery.UNKNOWN_INDEX_BUDGET);
        }
        public boolean recordMatch(String owner, int index) {
            confirms++;
            if (throwCommit) throw new IllegalStateException("Unavailable storage");
            if (failedCommit) return false;
            for (Pool p : pools) if (owner.equalsIgnoreCase(p.opk)) { p.kidx = index; p.signingStateUnverified = true; }
            return true;
        }
        void run(String command, NodeApi.Cb cb) {
            calls.add(command);
            tasks.add(() -> {
                try {
                    JSONObject response;
                    if (command.equals(BlockOwnerRecovery.MODE_COMMAND)) {
                        probes++;
                        String pk = unknownMode ? key(900) : classic || (switchMode && probes > 1)
                                ? BlockOwnerRecovery.CLASSIC_PROBE : BlockOwnerRecovery.BLOCK_PROBE;
                        response = ok(new JSONObject().put("publickey", pk));
                    } else if (command.equals("status")) response = ok(new JSONObject().put("locked", locked));
                    else if (command.equals("keys")) {
                        boolean signed = calls.stream().anyMatch(c -> c.startsWith("sign "));
                        if (unreadableAfterSign && signed) { cb.onError("unreadable"); return; }
                        JSONArray rows = new JSONArray();
                        for (JSONObject r : keys) rows.put(new JSONObject(r.toString()));
                        if (classicOwner) for (int i = 0; i < rows.length(); i++)
                            if (rows.getJSONObject(i).getString("publickey").equals(pools.get(0).opk))
                                rows.getJSONObject(i).put("size", 64).put("depth", 3);
                        if (switchWallet && calls.contains("newaddress")) rows.getJSONObject(0).put("publickey", key(999));
                        response = ok(new JSONObject().put("keys", rows).put("total", keys.size() + (corruptTotal ? 1 : 0)));
                    } else if (command.equals("newaddress")) {
                        if (failMint) { cb.onError("locked while generating"); return; }
                        int index = keys.size(); String pk = foreign ? key(index + 1000) : key(index);
                        keys.add(keyRow(index, pk, 0));
                        response = ok(new JSONObject().put("publickey", pk).put("total", keys.size()));
                    } else throw new AssertionError("Diagnostic issued unexpected command " + command);
                    cb.onResult(response);
                    if (duplicate) { cb.onResult(response); cb.onError("late duplicate"); }
                } catch (Exception failure) { throw new AssertionError(failure); }
            });
        }
        void start() {
            result = null;
            new BlockOwnerRecovery(this::run, this, new ArrayList<>(pools), ignored -> {}, r -> { result = r; completions++; }).start();
            int steps = 0;
            while (!tasks.isEmpty()) { assertTrue("unbounded recovery", ++steps < 5000); tasks.remove().run(); }
            assertNotNull(result);
            assertEquals("Diagnostic must not sign", 0, signs());
            assertTrue(calls.stream().allMatch(c -> c.equals(BlockOwnerRecovery.MODE_COMMAND)
                    || c.equals("status") || c.equals("keys") || c.equals("newaddress")));
            for (Pool p : pools) assertTrue("Diagnostic must retain signing hold", p.signingStateUnverified);
        }
        long mints() { return calls.stream().filter("newaddress"::equals).count(); }
        long signs() { return calls.stream().filter(c -> c.startsWith("sign ")).count(); }
        void assertPaused() { assertTrue(result.matched.isEmpty()); assertTrue(pools.get(0).signingStateUnverified); }
    }
    @Test public void missingBlockOwnerIsRegeneratedButSigningRemainsPaused() throws Exception {
        Harness h=new Harness(32,33);h.start();assertEquals(2,h.mints());assertEquals(1,h.confirms);
        assertTrue(h.result.matched(h.pools.get(0)));assertEquals(HEIGHT-1000,h.pools.get(0).minimumOwnerUses);
        assertEquals(0,h.keys.get(33).getInt("uses"));
    }
    @Test public void alreadyHeldKeyIsMatchedWithoutChangingCounter() throws Exception {
        Harness h=new Harness(34,33);h.start();assertEquals(0,h.mints());assertTrue(h.result.matched(h.pools.get(0)));
        assertEquals(HEIGHT-1000,h.keys.get(33).getInt("uses"));
    }
    @Test public void reviewedReusedLeafScenarioNeverIssuesTheProbeSignature() throws Exception {
        Harness h=new Harness(33,32);h.keys.get(32).put("uses",0);h.start();
        assertTrue(h.result.matched(h.pools.get(0)));assertEquals(0,h.keys.get(32).getInt("uses"));
    }
    @Test public void severalPoolsSharingOwnerAreRecordedOnce() throws Exception {
        Harness h=new Harness(32,32);Pool second=pool(32);second.address=key(700);h.pools.add(second);
        h.start();assertEquals(1,h.confirms);assertEquals(1,h.result.matched.size());
    }
    @Test public void conflictingIndexesNeverMint() throws Exception {
        Harness h=new Harness(32,32);Pool second=pool(32);second.kidx=35;h.pools.add(second);
        h.start();h.assertPaused();assertEquals(0,h.mints());
    }
    @Test public void classicModeReportsPausedWithoutGenerating() throws Exception {
        Harness h=new Harness(32,32);h.classic=true;h.start();h.assertPaused();
        assertEquals(BlockOwnerRecovery.Mode.CLASSIC,h.result.mode);assertEquals(1,h.calls.size());assertFalse(h.result.problems.isEmpty());
    }
    @Test public void storedBlockKeyDoesNotOverrideActualClassicMode() throws Exception {
        Harness h=new Harness(33,32);h.classic=true;h.start();h.assertPaused();
    }
    @Test public void unknownModeNeverGenerates() throws Exception {
        Harness h=new Harness(32,32);h.unknownMode=true;h.start();h.assertPaused();assertEquals(1,h.calls.size());
    }
    @Test public void classicOwnerOnBlockNodeIsNotReportedAsRecovered() throws Exception {
        Harness h=new Harness(33,32);h.classicOwner=true;h.start();h.assertPaused();
    }
    @Test public void lockedWalletDoesNotGenerate() throws Exception {
        Harness h=new Harness(32,32);h.locked=true;h.start();h.assertPaused();assertEquals(0,h.mints());
        assertTrue(h.result.problems.values().iterator().next().contains("Unlock"));
    }
    @Test public void foreignSeedStopsAtExactIndexAcrossRetries() throws Exception {
        Harness h=new Harness(32,33);h.foreign=true;h.start();h.assertPaused();assertEquals(2,h.mints());
        h.start();assertEquals(2,h.mints());
    }
    @Test public void missingKeyBelowExistingTotalDoesNotMint() throws Exception {
        Harness h=new Harness(35,32);h.keys.get(32).put("publickey",key(999));h.start();h.assertPaused();assertEquals(0,h.mints());
    }
    @Test public void unknownIndexHasPersistentSearchCeiling() throws Exception {
        Harness h=new Harness(32,32);h.pools.get(0).kidx=-1;h.foreign=true;h.start();h.assertPaused();assertEquals(256,h.mints());
        h.start();assertEquals(256,h.mints());
    }
    @Test public void oldBackupWithoutCounterCanLocateKeyButNotUnlockIt() throws Exception {
        Harness h=new Harness(32,33);h.pools.get(0).kidx=-1;h.pools.get(0).minimumOwnerUses=-1;h.start();
        assertTrue(h.result.matched(h.pools.get(0)));assertEquals(33,h.pools.get(0).kidx);assertEquals(-1,h.pools.get(0).minimumOwnerUses);
    }
    @Test public void backupCounterAheadOfNodeStillStaysHeld() throws Exception {
        Harness h=new Harness(33,32);h.pools.get(0).minimumOwnerUses=HEIGHT+1000;h.start();
        assertTrue(h.result.matched(h.pools.get(0)));assertEquals(HEIGHT+1000,h.pools.get(0).minimumOwnerUses);
    }
    @Test public void failedBudgetPersistenceDoesNotMint() throws Exception {
        Harness h=new Harness(32,33);h.pools.get(0).kidx=-1;h.failedLimit=true;h.start();h.assertPaused();assertEquals(0,h.mints());
    }
    @Test public void absurdIndexDoesNotOverflowOrStartUnboundedHunt() throws Exception {
        for(int index:new int[]{Integer.MAX_VALUE,50000}) {Harness h=new Harness(32,33);h.pools.get(0).kidx=index;h.start();h.assertPaused();assertEquals(0,h.mints());}
    }
    @Test public void unreadableTotalDoesNotLookLikeMissingKeys() throws Exception {
        Harness h=new Harness(32,32);h.corruptTotal=true;h.start();h.assertPaused();assertEquals(0,h.mints());
    }
    @Test public void walletChangeDuringHuntStopsRecovery() throws Exception {
        Harness h=new Harness(32,33);h.switchWallet=true;h.start();h.assertPaused();assertEquals(1,h.mints());
    }
    @Test public void modeChangeStopsRecording() throws Exception {
        Harness h=new Harness(32,32);h.switchMode=true;h.start();h.assertPaused();assertEquals(0,h.confirms);
    }
    @Test public void failedRecordDoesNotReportMatch() throws Exception {
        Harness h=new Harness(33,32);h.failedCommit=true;h.start();h.assertPaused();
    }
    @Test public void storageExceptionsFinishWithoutUnlockingOrHanging() throws Exception {
        Harness h=new Harness(33,32);h.throwCommit=true;h.start();h.assertPaused();assertEquals(1,h.completions);
        Harness search=new Harness(32,33);search.pools.get(0).kidx=-1;search.throwLimit=true;search.start();search.assertPaused();assertEquals(0,search.mints());
    }
    @Test public void failedMintStopsWithoutLooping() throws Exception {
        Harness h=new Harness(32,32);h.failMint=true;h.start();h.assertPaused();assertEquals(1,h.mints());
    }
    @Test public void duplicateCallbacksCannotMintOrRecordTwice() throws Exception {
        Harness h=new Harness(32,32);h.duplicate=true;h.start();assertEquals(1,h.mints());assertEquals(1,h.confirms);assertEquals(1,h.completions);
    }
    @Test public void malformedModeReplyIsUnknown() throws Exception {
        assertEquals(BlockOwnerRecovery.Mode.UNKNOWN,BlockOwnerRecovery.mode(ok(new JSONObject())));
        assertEquals(BlockOwnerRecovery.Mode.UNKNOWN,BlockOwnerRecovery.mode(ok(new JSONObject().put("publickey",BlockOwnerRecovery.BLOCK_PROBE)).put("pending",true)));
    }
}
