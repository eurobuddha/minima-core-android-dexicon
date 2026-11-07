// VENDORED VERBATIM from apks/pandapools 0.9.86 app/src/test/java/com/eurobuddha/pandapools/ReserveRecoveryTest.java by scripts/vendor-pandapools.sh — do not edit here; re-vendor.
package com.eurobuddha.pandapools;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public class ReserveRecoveryTest {
    static final String ADDRESS = "0xaaaa", TOKEN = "0xbbbb", M = "0x1111", T = "0x2222";
    static JSONObject reply(Object r) throws Exception { return new JSONObject().put("status", true).put("pending", false).put("response", r); }
    static JSONObject coin(String id, String token, String amount) throws Exception {
        JSONObject c = new JSONObject().put("coinid", id).put("address", ADDRESS).put("tokenid", token)
                .put("spent", false).put("state", new JSONArray()).put("created", 100).put("amount", amount);
        if (!token.equals("0x00")) c.put("tokenamount", amount).put("token", new JSONObject().put("decimals", 8));
        return c;
    }
    static Pool pool() { Pool p = new Pool(); p.address = ADDRESS; p.tok = TOKEN; p.kmin = "1"; return p; }

    static class Harness {
        List<String> calls = new ArrayList<>();
        JSONArray visible = new JSONArray();
        boolean rejectImport, badProofAddress, large, archiveDown, duplicate;
        int archiveCount = 2;
        boolean result; int completions; String detail;
        JSONObject cm, ct;
        Harness() throws Exception { cm = coin(M, "0x00", "487.0099300309"); ct = coin(T, TOKEN, "1.84920223"); }
        void run(String q, NodeApi.Cb cb, boolean remote) {
            calls.add((remote ? "archive:" : "local:") + q);
            try {
                JSONObject r;
                if (remote && archiveDown) { cb.onError("offline"); return; }
                if (q.equals("status")) r = reply(new JSONObject().put("megammr", remote));
                else if (q.startsWith("balance ")) r = reply(new JSONArray().put(new JSONObject().put("coins", large ? 999 : remote ? archiveCount : visible.length())));
                else if (q.startsWith("coins coinid:")) {
                    JSONArray found = new JSONArray();
                    for (int i=0;i<visible.length();i++) if (q.endsWith(visible.getJSONObject(i).getString("coinid"))) found.put(visible.getJSONObject(i));
                    r = reply(found);
                }
                else if (q.startsWith("coins ")) r = reply(remote ? new JSONArray().put(cm).put(ct) : visible);
                else if (q.startsWith("coinexport ")) {
                    boolean m = q.endsWith(M);
                    r = reply(new JSONObject().put("data", m ? "0xaabb" : "0xccdd").put("coinproof", new JSONObject().put("coin", m ? cm : ct)));
                } else if (q.startsWith("coincheck ")) {
                    boolean m = q.endsWith("0xaabb");
                    JSONObject c = new JSONObject((m ? cm : ct).toString());
                    if (badProofAddress) c.put("address", "0xffff");
                    r = reply(new JSONObject().put("coin", c).put("valid", !q.endsWith("0xdead")));
                } else if (q.startsWith("coinimport ")) {
                    if (!rejectImport) visible.put(q.endsWith("0xaabb") ? cm : ct);
                    r = reply(new JSONObject()).put("status", !rejectImport);
                } else throw new AssertionError("Unexpected command " + q);
                cb.onResult(r);
                if (duplicate) cb.onResult(r);
            } catch (Exception e) { throw new AssertionError(e); }
        }
        void recover(JSONObject snapshot, boolean remote) {
            new ReserveRecovery((q, cb) -> run(q, cb, false), remote ? (q, cb) -> run(q, cb, true) : null,
                    pool(), snapshot, (ok, message) -> { result = ok; detail = message; completions++; }).start();
            assertEquals(1, completions);
            assertTrue(detail.contains(ADDRESS));
            for (String q : calls) assertFalse(q, q.contains("sign ") || q.contains("txnpost") || q.contains(PoolCovenant.SENTINEL));
        }
    }

    @Test public void liveAddressWinsOverBrokenSnapshotAndNeverContactsArchive() throws Exception {
        Harness h = new Harness(); h.visible.put(h.cm).put(h.ct);
        h.recover(new JSONObject().put("cm", "broken;send amount:10").put("ct", "0xdead"), true);
        assertTrue(h.result); assertEquals(2, h.calls.size());
    }
    @Test public void expiredSnapshotsRecoverWithLiveArchiveCoinIdsAndLocalProofChecks() throws Exception {
        Harness h = new Harness();
        h.recover(new JSONObject().put("cm", "0xdead").put("ct", "0xdead"), true);
        assertTrue(h.result);
        assertTrue(h.calls.contains("archive:coinexport coinid:" + T));
        assertTrue(h.calls.indexOf("local:coincheck data:0xccdd") < h.calls.indexOf("local:coinimport track:true data:0xccdd"));
    }
    @Test public void totalImportFailureCannotReportRecoverySuccess() throws Exception {
        Harness h = new Harness(); h.rejectImport = true; h.recover(null, true);
        assertFalse(h.result); assertTrue(h.detail.contains("Coin import failed"));
    }
    @Test public void validProofForAnotherAddressIsNeverImported() throws Exception {
        Harness h = new Harness(); h.badProofAddress = true; h.recover(null, true);
        assertFalse(h.result); assertFalse(h.calls.stream().anyMatch(q -> q.contains("coinimport")));
    }
    @Test public void archiveOutageRetainsUnresolvedStatus() throws Exception {
        Harness h = new Harness(); h.archiveDown = true; h.recover(null, true);
        assertFalse(h.result); assertTrue(h.detail.contains("offline"));
    }
    @Test public void visibleDustDoesNotHideArchivedReserves() throws Exception {
        Harness h = new Harness();
        h.visible.put(coin("0x3333", "0x00", "0.000001")).put(coin("0x4444", TOKEN, "0.000001"));
        h.recover(null, true);
        assertTrue(h.result); assertTrue(h.calls.contains("archive:coinexport coinid:" + M));
    }
    @Test public void refusesLargeAddressBeforeIssuingCoinList() throws Exception {
        Harness h = new Harness(); h.large = true; h.recover(null, true);
        assertFalse(h.result); assertFalse(h.calls.stream().anyMatch(q -> q.contains(":coins ")));
    }
    @Test public void crowdedPoolUsesArchiveAndIndividualLocalReads() throws Exception {
        Harness h = new Harness(); h.archiveCount = 9;
        for (int i=0;i<9;i++) h.visible.put(coin("0x"+Integer.toHexString(500+i),"0x00","0.000001"));
        h.recover(null, true);
        assertTrue(h.result);
        assertFalse(h.calls.contains("local:coins address:"+ADDRESS));
        assertTrue(h.calls.contains("local:coins coinid:"+M));
        assertTrue(h.calls.contains("local:coins coinid:"+T));
    }
    @Test public void restoredSigningStateCannotBeProvedByExceedingBackupCounter() {
        Pool p = pool(); p.minimumOwnerUses = 590; p.signingStateUnverified = true;
        assertFalse(OwnerKeyRecovery.signingAllowed(p, 846, 262144));
        assertFalse(OwnerKeyRecovery.signingAllowed(p, 590, 262144));
        p.signingStateUnverified = false;
        assertFalse(OwnerKeyRecovery.signingAllowed(p, 589, 262144));
        assertFalse(OwnerKeyRecovery.signingAllowed(p, null, 262144));
        assertFalse(OwnerKeyRecovery.signingAllowed(p, 262144, 262144));
        assertTrue(OwnerKeyRecovery.signingAllowed(p, 900, 262144));
    }
    @Test public void signingBoundarySeesRestoreAfterFundingAndDuringKeyRead() throws Exception {
        for (boolean duringKeys : new boolean[]{false,true}) {
            Pool p = pool(); p.opk = "0x5555"; p.oadr = "0x6666"; p.minimumOwnerUses = 590;
            List<Pool> recipes = new ArrayList<>(); if(duringKeys) recipes.add(p);
            List<String> outcomes = new ArrayList<>();
            NodeApi.Cb[] waiting = new NodeApi.Cb[1];
            JSONObject coinReply = reply(new JSONArray().put(coin(M,"0x00","20").put("address",p.oadr)));
            JSONObject scriptReply = reply(new JSONObject().put("address",p.oadr).put("simple",true).put("publickey",p.opk));
            // A covenant may pay to a different address; auto uses the input's actual simple script key.
            p.oadr = "0x7777";
            JSONObject keyReply = reply(new JSONObject().put("keys",new JSONArray().put(new JSONObject().put("publickey",p.opk).put("uses",846))));
            OwnerKeyRecovery.checkSignature((q,cb)->{
                if(q.startsWith("coins")) {if(duringKeys)cb.onResult(coinReply);else waiting[0]=cb;}
                else if(q.startsWith("scripts address:")) cb.onResult(scriptReply);
                else if(q.equals("keys")) {if(duringKeys)waiting[0]=cb;else cb.onResult(keyReply);}
                else fail("Unexpected signing command: "+q);
            },()->recipes,Collections.singletonList(M),"auto",outcomes::add);
            assertTrue(outcomes.isEmpty());
            p.signingStateUnverified=true;if(!duringKeys)recipes.add(p);
            waiting[0].onResult(duringKeys?keyReply:coinReply);
            assertEquals(1,outcomes.size());assertNotNull(outcomes.get(0));// the refusal must name the ACTUAL reason (imported recipe), not a generic "paused", and must warn
            // against the seed-only restore that causes it
            assertTrue(outcomes.get(0),outcomes.get(0).contains("imported"));assertTrue(outcomes.get(0).contains("seed phrase"));assertTrue(outcomes.get(0).contains("Nothing signed"));
        }
    }
    @Test public void archiveAllowsOnlyExactReadOnlyCoinIdLookup() {
        String id="0x"+String.join("",Collections.nCopies(64,"a"));
        assertTrue(ArchiveNode.allowedCommand("coins coinid:"+id+" megammr:true"));
        assertFalse(ArchiveNode.allowedCommand("coins coinid:"+id+" megammr:true;send amount:1"));
        assertFalse(ArchiveNode.allowedCommand("coinimport data:"+id));
        assertFalse(ArchiveNode.allowedCommand("coins coinid:0xaaaa megammr:true"));
    }
    @Test public void failedConfirmationWriteCannotEnableSigningEvenIfRollbackFails() throws Exception {
        Pool p=pool();p.opk="0x8888";p.oadr="0x9999";p.minimumOwnerUses=590;p.signingStateUnverified=true;
        assertFalse(OwnPoolStore.commitConfirmation(p.opk,()->{p.signingStateUnverified=false;return false;},()->{throw new IllegalStateException("disk failed");}));
        List<String> outcome=new ArrayList<>();
        JSONObject c=reply(new JSONArray().put(coin(M,"0x00","20")));
        JSONObject k=reply(new JSONObject().put("keys",new JSONArray().put(new JSONObject().put("publickey",p.opk).put("uses",846))));
        OwnerKeyRecovery.checkSignature((q,cb)->cb.onResult(q.startsWith("coins ")?c:k),()->Collections.singletonList(p),Collections.singletonList(M),p.opk,outcome::add);
        assertEquals(1,outcome.size());assertTrue(outcome.get(0).contains("paused"));
        assertTrue(OwnPoolStore.commitConfirmation(p.opk,()->true,()->{}));
        assertTrue(OwnerKeyRecovery.signingAllowed(p, 846, 262144));
    }
    @Test public void storedRecipeMergePreservesSigningHoldCounterIndexAndHints() throws Exception {
        Pool p=pool();p.opk="0x5555";p.oadr="0x6666";p.kidx=64;p.minimumOwnerUses=900;p.signingStateUnverified=true;p.coinidM=M;p.coinidT=T;
        JSONObject saved=OwnPoolStore.mergeRecord(p,"");
        Pool older=pool();older.opk=p.opk;older.oadr=p.oadr;older.minimumOwnerUses=590;
        JSONObject merged=OwnPoolStore.mergeRecord(older,saved.toString());
        assertEquals(900,merged.getInt("opkuses"));assertEquals(64,merged.getInt("kidx"));assertTrue(merged.getBoolean("signing_unverified"));
        assertEquals(M,merged.getString("last_coinid_m"));assertEquals(T,merged.getString("last_coinid_t"));
        older.minimumOwnerUses=950;older.coinidM="0x3333";older.coinidT="0x4444";
        JSONObject updated=OwnPoolStore.mergeRecord(older,merged.toString());
        assertEquals(950,updated.getInt("opkuses"));assertEquals(64,updated.getInt("kidx"));assertTrue(updated.getBoolean("signing_unverified"));
        assertEquals("0x3333",updated.getString("last_coinid_m"));
    }
    @Test public void crowdedRecipeWithSavedHintsNeedsNoArchiveOrEmbeddedProofs() throws Exception {
        Harness h=new Harness();h.visible.put(h.cm).put(h.ct);
        for(int i=0;i<7;i++)h.visible.put(coin("0x"+Integer.toHexString(500+i),"0x00","0.000001"));
        Pool p=pool();p.coinidM=M;p.coinidT=T;
        new ReserveRecovery((q,cb)->h.run(q,cb,false),null,p,new JSONObject(),(ok,detail)->{h.result=ok;h.completions++;}).start();
        assertTrue(h.result);assertEquals(1,h.completions);assertFalse(h.calls.stream().anyMatch(q->q.contains("coinimport")||q.startsWith("archive:")));
        assertFalse(h.calls.contains("local:coins address:"+ADDRESS));
    }
    @Test public void duplicateCallbacksCannotImportOrFinishTwice() throws Exception {
        Harness h = new Harness(); h.duplicate = true; h.recover(null, true);
        assertTrue(h.result); assertEquals(2, h.visible.length());
    }
    @Test public void invalidTokenAmountAndFailedReadNeverPreserveCachedReserves() throws Exception {
        Pool p = pool(); JSONObject m = coin(M, "0x00", "20"), t = coin(T, TOKEN, "30");
        assertTrue(PoolRefresher.fillReserves(p, reply(new JSONArray().put(m).put(t)))); assertTrue(p.funded());
        t.remove("tokenamount");
        PoolRefresher.fillReserves(p, reply(new JSONArray().put(m).put(t))); assertFalse(p.funded());
        assertFalse(PoolRefresher.fillReserves(p, new JSONObject().put("status", false))); assertNull(p.reserveM); assertNull(p.coinidT);
    }
    @Test public void backupRejectsWrongExportIdAndFailedResponseEvenWithData() throws Exception {
        JSONObject e = new JSONObject().put("addr", ADDRESS).put("tok", TOKEN);
        JSONObject r = new JSONObject().put("data", "0xaabb").put("coinproof", new JSONObject().put("coin", coin(M,"0x00","20")));
        Recovery.putData(e,"cm",T,"0x00",reply(r)); assertFalse(e.has("cm"));
        Recovery.putData(e,"cm",M,"0x00",reply(r).put("status",false)); assertFalse(e.has("cm"));
        Recovery.putData(e,"cm",M,"0x00",reply(r)); assertEquals("0xaabb",e.getString("cm")); assertEquals(100,e.getInt("cm_created"));
    }
    @Test public void malformedCounterIsUnknownNotZero() throws Exception {
        JSONObject row = new JSONObject().put("publickey",M).put("uses","oops");
        JSONObject j = reply(new JSONObject().put("keys",new JSONArray().put(row)));
        assertNull(KeyUses.extractUses(j,M)); row.put("uses",46); j.put("status",false); assertNull(KeyUses.extractUses(j,M));
    }
    @Test public void legacyAndFirstDiscoveryRecipesStayHeldUntilConfirmed() throws Exception {
        Pool p=pool();p.opk="0x5555";p.oadr="0x6666";
        assertTrue(OwnPoolStore.mergeRecord(p,"").getBoolean("signing_unverified"));
        p.newlyCreatedWithCurrentOwnerState=true;
        JSONObject fresh=OwnPoolStore.mergeRecord(p,"");assertFalse(fresh.getBoolean("signing_unverified"));
        fresh.remove("signing_unverified");assertTrue(OwnPoolStore.mergeRecord(p,fresh.toString()).getBoolean("signing_unverified"));
    }
    @Test public void queuedSigningAndTrackingRejectRecoveryChangesBeforeActualDispatch() {
        SerialQueue q=new SerialQueue();List<Runnable> release=new ArrayList<>();List<Boolean> accepted=new ArrayList<>();
        q.submit(finish->release.add(finish));
        long checked=OwnPoolStore.signingRevision();
        q.submit(finish->{accepted.add(NodeApi.signingRevisionMatches("txnsign id:pool publickey:auto",checked));finish.run();});
        OwnPoolStore.signingStateChanged();release.get(0).run();assertEquals(Collections.singletonList(false),accepted);
        assertFalse(NodeApi.signingRevisionMatches("cointrack enable:false coinid:0x1111",checked));
        assertTrue(NodeApi.signingRevisionMatches("keys",checked));
        assertTrue(NodeApi.signingRevisionMatches("txnsign id:pool publickey:auto",OwnPoolStore.signingRevision()));
    }
    @Test public void consolidationChecksAnOwnerRestoredDuringItsKeyRead() throws Exception {
        NodeApi.Cb[] waiting=new NodeApi.Cb[1];List<Pool> recipes=new ArrayList<>();List<String> result=new ArrayList<>();
        OwnerKeyRecovery.checkConsolidation((q,cb)->waiting[0]=cb,()->recipes,result::add);
        Pool p=pool();p.opk="0x5555";p.signingStateUnverified=true;recipes.add(p);
        waiting[0].onResult(reply(new JSONArray().put(new JSONObject().put("publickey",p.opk).put("uses",846))));
        assertEquals(1,result.size());assertTrue(result.get(0),result.get(0).contains("Nothing consolidated"));assertTrue(result.get(0).contains("imported"));
    }
    @Test public void interruptedWriteClearFailureKeepsLatchAndPendingReplyIsIncomplete() throws Exception {
        assertFalse(NodeApi.commitWriteAcknowledgement(()->false,()->{throw new IllegalStateException("disk");}));
        assertTrue(NodeApi.writeAcknowledgementFailed());
        assertFalse(NodeApi.isCompleteReply(reply(new JSONObject()).put("pending",true)));
        assertTrue(NodeApi.commitWriteAcknowledgement(()->true,()->{}));assertFalse(NodeApi.writeAcknowledgementFailed());
    }

}
