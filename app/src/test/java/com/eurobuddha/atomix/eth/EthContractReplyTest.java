// VENDORED VERBATIM from apks/atomix 0.1.83 app/src/test/java/com/eurobuddha/atomix/eth/EthContractReplyTest.java by scripts/vendor-atomix.sh — do not edit here; re-vendor.
package com.eurobuddha.atomix.eth;

import org.junit.Test;
import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

public class EthContractReplyTest {
    private final EthRpc rpc = mock(EthRpc.class);
    private final EthHtlc htlc = new EthHtlc(rpc, null, EthNet.MAINNET);
    private static final String CID = "0x" + "11".repeat(32);
    @Test public void malformedContractRepliesAreErrorsRatherThanMissingLegs() throws Exception {
        for (String r : new String[]{null, "", "0x", "0x00", "0x" + "00".repeat(383), "0x" + "gg".repeat(384)}) {
            when(rpc.ethCall(anyString(), anyString())).thenReturn(r);
            try { htlc.getContract(CID); fail("Malformed reply accepted"); } catch (Exception expected) { }
        }
    }
    @Test public void fullZeroTupleMeansContractAbsent() throws Exception {
        when(rpc.ethCall(anyString(), anyString())).thenReturn("0x" + "00".repeat(384));
        assertNull(htlc.getContract(CID));
    }
    @Test public void malformedBooleansAreNeverClaimedOrRefundedEvidence() throws Exception {
        for (String r : new String[]{null, "0x", "0x0", "0x" + "0".repeat(63) + "2"}) {
            when(rpc.ethCall(anyString(), anyString())).thenReturn(r);
            try { htlc.canCollect(CID); fail("Malformed boolean accepted"); } catch (Exception expected) { }
        }
        for (int v = 0; v < 2; v++) {
            when(rpc.ethCall(anyString(), anyString())).thenReturn("0x" + "0".repeat(63) + v);
            assertEquals(v == 1, htlc.canCollect(CID));
        }
    }
    @Test public void invalidWithdrawnFlagNeverCreatesAContractState() throws Exception {
        String reply = "0x" + "0".repeat(8 * 64 + 63) + "2" + "0".repeat(3 * 64);
        when(rpc.ethCall(anyString(), anyString())).thenReturn(reply);
        try { htlc.getContract(CID); fail("Invalid state accepted"); } catch (Exception expected) { }
    }
    @Test public void timelockConversionPreservesValueAndRejectsOverflow() throws Exception {
        for (java.math.BigInteger timelock : new java.math.BigInteger[]{java.math.BigInteger.valueOf(1800000000L), java.math.BigInteger.ONE.shiftLeft(63)}) {
            String[] words = new String[12];
            java.util.Arrays.fill(words, "0".repeat(64));
            words[0] = "0".repeat(63) + "1"; // non-zero owner, existing contract
            words[7] = String.format("%064x", timelock);
            when(rpc.ethCall(anyString(), anyString())).thenReturn("0x" + String.join("", words));
            if (timelock.bitLength() <= 63) assertEquals(timelock.longValue(), htlc.getContract(CID).timelock);
            else { try { htlc.getContract(CID); fail("Overflow accepted"); } catch (Exception expected) {} }
        }
    }
}
