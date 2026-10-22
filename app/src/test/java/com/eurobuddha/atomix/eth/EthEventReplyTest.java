// VENDORED VERBATIM from apks/atomix 0.1.82 app/src/test/java/com/eurobuddha/atomix/eth/EthEventReplyTest.java by scripts/vendor-atomix.sh — do not edit here; re-vendor.
package com.eurobuddha.atomix.eth;

import java.io.IOException;
import java.math.BigInteger;
import org.json.*;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.web3j.crypto.Credentials;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Reuse the ABI-encoded event fixture and nonce RPC fixtures; no live node, wallet or sends. */
public class EthEventReplyTest {
    static final Credentials KEY = Credentials.create("01");
    static final String OWNER = "0x" + "12".repeat(20), HASH = "0x" + "34".repeat(32);
    final EthRpc rpc = mock(EthRpc.class);
    final EthHtlc htlc = new EthHtlc(rpc, KEY, EthNet.MAINNET);
    static JSONObject event() throws Exception {
        return new JSONObject().put("address", EthNet.MAINNET.htlc).put("removed", false)
            .put("blockNumber", "0x10").put("transactionHash", HASH)
            .put("topics", new JSONArray().put(EthHtlc.NEW_TOPIC).put(HASH)
                    .put(EthHtlc.padAddr(OWNER)).put(EthHtlc.padAddr(KEY.getAddress())))
            .put("data", EthEncodingTest.encodeNewData(HASH, OWNER, BigInteger.ONE, BigInteger.TEN,
                    HASH, BigInteger.valueOf(1900000000), false));
    }
    void rejected(JSONObject event) throws Exception {
        assertNull(EthHtlc.parseNew(event));
        when(rpc.getLogs(any())).thenReturn(new JSONArray().put(event));
        assertThrows(Exception.class, () -> htlc.contractsAsReceiver(BigInteger.ZERO, BigInteger.valueOf(20)));
    }
    @Test public void validBatchAndExplicitEmptyBatchRemainReadable() throws Exception {
        JSONObject e = event();
        when(rpc.getLogs(any())).thenReturn(new JSONArray().put(e));
        assertEquals(1, htlc.contractsAsReceiver(BigInteger.valueOf(16), BigInteger.valueOf(16)).size());
        EthHtlc.Contract c = EthHtlc.parseNew(e);
        assertNotNull(c);assertEquals(KEY.getAddress(), c.receiver);assertEquals(BigInteger.ONE, c.amount);
        when(rpc.getLogs(any())).thenReturn(new JSONArray());
        assertTrue(htlc.contractsAsReceiver(BigInteger.ZERO, BigInteger.valueOf(20)).isEmpty());
    }
    @Test public void missingRequiredFieldsAreNotSkipped() throws Exception {
        for (String field : new String[]{"address", "removed", "blockNumber", "transactionHash", "topics", "data"}) {
            JSONObject e=event();e.remove(field);rejected(e);
        }
    }
    @Test public void removedOrUncertainEventsCannotTriggerAResponse() throws Exception {
        for (Object value : new Object[]{true, "false", 0, JSONObject.NULL}) rejected(event().put("removed", value));
    }
    @Test public void topicsMustMatchTheEventAndCanonicalAddressEncoding() throws Exception {
        for (int i=0;i<4;i++) {
            JSONObject e=event();e.getJSONArray("topics").put(i, "0x");rejected(e);
        }
        JSONObject e=event();e.getJSONArray("topics").put(0,HASH);rejected(e);
        e=event();e.getJSONArray("topics").put(HASH);rejected(e);
        for (int i : new int[]{2,3}) {
            e=event();String topic=e.getJSONArray("topics").getString(i);
            e.getJSONArray("topics").put(i,"0x1"+topic.substring(3));rejected(e);
        }
    }
    @Test public void malformedDataAndNonBooleanWordsAreRejected() throws Exception {
        String data=event().getString("data");
        for (String bad : new String[]{"0x", data.substring(0,data.length()-2), data+"00", "0xgg"+data.substring(4),
                data.substring(0,data.length()-1)+"2", data.substring(0,66)+"1"+data.substring(67)}) {
            rejected(event().put("data",bad));
        }
    }
    @Test public void heightAndTimelockCannotOverflowIntoDifferentValues() throws Exception {
        for (Object height : new Object[]{16, "0x", "0x010", "0x8000000000000000", "0x10000000000000000"}) {
            rejected(event().put("blockNumber",height));
        }
        String data=EthEncodingTest.encodeNewData(HASH,OWNER,BigInteger.ONE,BigInteger.TEN,HASH,BigInteger.ONE.shiftLeft(63),false);
        rejected(event().put("data",data));
    }
    @Test public void malformedSecondEventRejectsTheWholeBatch() throws Exception {
        when(rpc.getLogs(any())).thenReturn(new JSONArray().put(event()).put(new JSONObject()));
        assertThrows(Exception.class, () -> htlc.contractsAsReceiver(BigInteger.ZERO, BigInteger.valueOf(20)));
    }
    @Test public void unrelatedVaultRecipientOrHeightCannotEnterTheDiscoveryBatch() throws Exception {
        for (int fault=0;fault<4;fault++) {
            JSONObject e=event();
            if(fault==0)e.put("address",OWNER);
            if(fault==1)e.getJSONArray("topics").put(3,EthHtlc.padAddr(OWNER));
            if(fault==2)e.put("blockNumber","0x9");
            if(fault==3)e.put("blockNumber","0x15");
            when(rpc.getLogs(any())).thenReturn(new JSONArray().put(e));
            assertThrows(Exception.class, () -> htlc.contractsAsReceiver(BigInteger.TEN, BigInteger.valueOf(20)));
        }
    }
    @Test public void malformedRpcLogsAreNotAnEmptySuccessfulScan() throws Exception {
        EthRpc read=spy(new EthRpc("https://fixture.invalid"));
        for(Object bad : new Object[]{null,JSONObject.NULL,new JSONObject(),"[]",0,new JSONArray().put("bad")}) {
            doReturn(bad).when(read).call(eq("eth_getLogs"),any());
            assertThrows(IOException.class, () -> read.getLogs(new JSONObject()));
        }
        doReturn(new JSONArray()).when(read).call(eq("eth_getLogs"),any());
        assertEquals(0,read.getLogs(new JSONObject()).length());
    }
    @Test public void malformedLogRpcResponseUsesExistingFallback() throws Exception {
        try(MockedStatic<EthRpc> transport=mockStatic(EthRpc.class,CALLS_REAL_METHODS)) {
            java.net.HttpURLConnection bad=EthNonceTest.response("{\"result\":null}");
            java.net.HttpURLConnection good=EthNonceTest.response("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":[]}");
            java.util.concurrent.atomic.AtomicInteger attempts=new java.util.concurrent.atomic.AtomicInteger();
            transport.when(()->EthRpc.openConnection(anyString())).thenAnswer(inv->{
                int count=attempts.getAndIncrement();if(count>1)throw new AssertionError("Unexpected RPC attempt");return count==0?bad:good;
            });
            assertEquals(0,new EthRpc("https://fixture.invalid").getLogs(new JSONObject()).length());
            assertEquals(2,attempts.get());verify(bad).disconnect();verify(good).disconnect();
        }
    }
}
