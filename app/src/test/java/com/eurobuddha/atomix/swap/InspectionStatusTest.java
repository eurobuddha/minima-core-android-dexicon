// VENDORED VERBATIM from apks/atomix 0.1.84 app/src/test/java/com/eurobuddha/atomix/swap/InspectionStatusTest.java by scripts/vendor-atomix.sh — do not edit here; re-vendor.
package com.eurobuddha.atomix.swap;
import com.eurobuddha.atomix.eth.EthHtlc;
import org.junit.Test;
import static org.junit.Assert.*;
public class InspectionStatusTest {
    @Test public void openEthereumLegWithoutSecretDoesNotPromiseCollection() {
        String state = SwapEngine.ethClaimStatus(new EthHtlc.Contract(), false);
        assertEquals("locked; waiting for the secret", state); assertFalse(state.contains("claimable"));
    }
    /** PostCb.err may follow a broadcast whose reply was lost; no recorded id proves no outcome. */
    @Test public void missingLocalAcknowledgementDoesNotProveNoFundsLocked() {
        String none = SwapEngine.recordedTxnSummary(0);
        assertTrue(none.contains("NONE"));
        assertTrue(none.contains("broadcast may have completed"));
        assertTrue(none.contains("Check the on-chain state"));
        assertFalse(none.contains("Nothing is locked"));
        assertFalse(none.contains("nothing needs refunding"));
        assertFalse(none.contains("never posted"));
    }

    @Test public void recordedTransactionsAreCountedNotSilentlyDropped() {
        assertTrue(SwapEngine.recordedTxnSummary(2).contains("2"));
        assertTrue(SwapEngine.recordedTxnSummary(1).contains("listed above"));
    }

    @Test public void secretAvailabilityDoesNotOverrideTerminalState() {
        EthHtlc.Contract c = new EthHtlc.Contract(); c.refunded = true;
        assertEquals("refunded", SwapEngine.ethClaimStatus(c, true));
        c.refunded = false; c.withdrawn = true;
        assertEquals("withdrawn (complete)", SwapEngine.ethClaimStatus(c, true));
    }
}
