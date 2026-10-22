// VENDORED VERBATIM from apks/atomix 0.1.82 app/src/test/java/com/eurobuddha/comms/WriteSafetyTest.java by scripts/vendor-atomix.sh — do not edit here; re-vendor.
package com.eurobuddha.comms;

import android.content.SharedPreferences;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import java.util.HashMap;
import java.util.Map;
import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

public class WriteSafetyTest {
    private SharedPreferences prefs;
    private SharedPreferences.Editor edit;
    private final Map<String,String> persisted = new HashMap<>();
    @Before public void setup() {
        prefs = mock(SharedPreferences.class); edit = mock(SharedPreferences.Editor.class);
        when(prefs.edit()).thenReturn(edit);
        when(prefs.getString(anyString(), anyString())).thenAnswer(i -> persisted.getOrDefault(i.getArgument(0), i.getArgument(1)));
        when(edit.putString(anyString(), anyString())).thenAnswer(i -> { persisted.put(i.getArgument(0), i.getArgument(1)); return edit; });
        when(edit.remove(anyString())).thenAnswer(i -> { persisted.remove(i.getArgument(0)); return edit; });
        when(edit.putLong(anyString(), anyLong())).thenAnswer(i -> { persisted.put(i.getArgument(0), String.valueOf((long) i.getArgument(1))); return edit; });
        when(prefs.getLong(anyString(), anyLong())).thenAnswer(i ->
                persisted.containsKey(i.getArgument(0)) ? Long.parseLong(persisted.get(i.getArgument(0))) : (long) i.getArgument(1));
        when(edit.commit()).thenReturn(true);
        WriteSafety.resetForTest();
    }
    @Test public void timeoutNeverAuthorizesASecondWrite() {
        assertTrue(WriteSafety.begin(prefs, "a", "send amount:1"));
        WriteSafety.uncertain(prefs, "a");
        assertTrue(WriteSafety.interrupted(prefs));
        assertFalse(WriteSafety.begin(prefs, "b", "send amount:1"));
        assertEquals("a", persisted.get("pending"));
    }
    @Test public void completeLateReplyAllowsRecoveryButCannotClearNewerWrite() {
        assertTrue(WriteSafety.begin(prefs, "a", "send amount:1")); WriteSafety.uncertain(prefs, "a");
        WriteSafety.returned(prefs, "a", true); assertFalse(WriteSafety.interrupted(prefs));
        assertTrue(WriteSafety.begin(prefs, "b", "send amount:1"));
        WriteSafety.returned(prefs, "a", true); assertEquals("b", persisted.get("pending"));
        assertFalse(WriteSafety.acknowledge(prefs));
        WriteSafety.returned(prefs, "b", true);
    }
    @Test public void unknownReplyRetainsPauseUntilExplicitAcknowledgement() {
        assertTrue(WriteSafety.begin(prefs, "a", "send amount:1"));
        WriteSafety.returned(prefs, "a", false);
        assertTrue(WriteSafety.interrupted(prefs));
        assertFalse(WriteSafety.begin(prefs, "b", "send amount:1"));
        assertTrue(WriteSafety.acknowledge(prefs));
        assertTrue(WriteSafety.begin(prefs, "b", "send amount:1")); WriteSafety.returned(prefs, "b", true);
    }
    @Test public void restartedProcessHonorsPersistedMarker() {
        persisted.put("pending", "previous-process");
        assertFalse(WriteSafety.begin(prefs, "new-process", "send amount:1"));
    }
    @Test public void failedPersistenceNeverAuthorizesWrite() {
        when(edit.commit()).thenReturn(false);
        assertFalse(WriteSafety.begin(prefs, "a", "send amount:1"));
    }
    @Test public void allFundCommandsAreClassifiedAndReadsRemainAvailable() {
        for (String s : new String[]{"sign publickey:0xaa", "txnsign id:x", "txnpost id:x", "send amount:1", "consolidate", "tokencreate"}) assertTrue(s, WriteSafety.writesFunds(s));
        for (String s : new String[]{"coins", "balance", "txncheck id:x", "keys", "txnbasics id:x"}) assertFalse(s, WriteSafety.writesFunds(s));
    }
    @Test public void incompleteRepliesAreNotAcknowledgements() throws Exception {
        assertFalse(WriteSafety.completeReply(null));
        assertFalse(WriteSafety.completeReply(new JSONObject().put("response", "unknown")));
        assertFalse(WriteSafety.completeReply(new JSONObject().put("status", "true")));
        assertTrue(WriteSafety.completeReply(new JSONObject().put("status", false)));
        assertFalse(WriteSafety.completeReply(new JSONObject().put("status", true).put("pending", true)));
        assertFalse(WriteSafety.completeReply(new JSONObject().put("status", false).put("response", "Result too long! MAX(256000)")));
    }

    // ---- why the pause fired -------------------------------------------------------------
    //
    // The latch outlives the evidence. Live 2026-09-23: it was found hours later with the causing
    // event already rotated out of logcat, and every later refusal logged the SAME sentence as the
    // original failure - so the cause was unrecoverable. It also told the user to restart
    // MinimaCore, which they did twice, for a fault that never reached the node.

    @Test public void aTimeoutIsTheOneCauseThatImplicatesTheNode() {
        assertTrue(WriteSafety.begin(prefs, "a", "send amount:1 address:0x00"));
        WriteSafety.uncertain(prefs, "a");
        String d = WriteSafety.describe(prefs);
        assertTrue(d, d.contains("'send'"));
        assertTrue(d, d.contains("no reply"));
        assertTrue("a timeout is the only case where restarting the node is reasonable advice",
                d.contains("node may have been restarting"));
    }

    @Test public void anIncompleteReplyExplicitlyClearsTheNode() {
        assertTrue(WriteSafety.begin(prefs, "a", "txnpost id:x"));
        WriteSafety.returned(prefs, "a", false);
        String d = WriteSafety.describe(prefs);
        assertTrue(d, d.contains("'txnpost'"));
        assertTrue(d, d.contains("restarting it will not help"));
    }

    /** No reason recorded means nothing lived long enough to record one - the app was killed. */
    @Test public void aSilentDeathIsReportedAsTheAppBeingKilledNotTheNode() {
        assertTrue(WriteSafety.begin(prefs, "a", "send amount:1"));
        // process dies here: "pending" is on disk, the `active` static is gone
        persisted.remove("pending_why");
        WriteSafety.resetForTest();
        String d = WriteSafety.describe(prefs);
        assertTrue(d, d.contains("killed in the background"));
        assertTrue(d, d.contains("node was never at fault"));
    }

    @Test public void aCallbackFailureSaysTheCommandProbablyCompleted() {
        assertTrue(WriteSafety.begin(prefs, "a", "consolidate"));
        WriteSafety.callbackFailed(prefs, "a", "consolidate");
        assertTrue(WriteSafety.describe(prefs).contains("most likely completed"));
    }

    /** The cause where the command most likely DID complete on the node is the one the user most
     *  needs the detail for - so it must carry the verb and the timing, not just an id. */
    @Test public void aCallbackFailureOnAnEmptyLatchStillRecordsWhatAndWhen() {
        WriteSafety.callbackFailed(prefs, "orphan", "txnpost id:x");
        String d = WriteSafety.describe(prefs);
        assertTrue(d, d.contains("'txnpost'"));
        assertTrue("must be able to say how long ago", d.contains("min ago"));
        assertTrue(d, d.contains("most likely completed"));
    }

    @Test public void nothingPendingDescribesNothing() {
        assertNull(WriteSafety.describe(prefs));
    }

    /** A cleared pause must leave no stale reason to be attributed to the NEXT interruption. */
    @Test public void acknowledgingClearsTheRecordedCauseToo() {
        assertTrue(WriteSafety.begin(prefs, "a", "send amount:1"));
        WriteSafety.returned(prefs, "a", false);
        assertTrue(WriteSafety.acknowledge(prefs));
        assertNull(WriteSafety.describe(prefs));
        assertFalse(persisted.containsKey("pending_why"));
        assertFalse(persisted.containsKey("pending_cmd"));
    }
}
