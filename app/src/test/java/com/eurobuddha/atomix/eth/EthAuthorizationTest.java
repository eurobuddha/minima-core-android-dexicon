// VENDORED VERBATIM from apks/atomix 0.1.83 app/src/test/java/com/eurobuddha/atomix/eth/EthAuthorizationTest.java by scripts/vendor-atomix.sh — do not edit here; re-vendor.
package com.eurobuddha.atomix.eth;

import java.math.BigInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import org.json.JSONArray;
import org.junit.*;
import org.mockito.MockedStatic;
import org.web3j.crypto.*;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Synthetic key 1, mocked RPC; tests the real existing serializer without any network or wallet. */
public class EthAuthorizationTest {
    static final Credentials FIXTURE=Credentials.create("01");
    static final String TO="0x"+"12".repeat(20), HASH="0x"+"34".repeat(32);
    EthRpc rpc;
    @Before public void setup() throws Exception {
        EthTx.resetAll();rpc=mock(EthRpc.class);
        when(rpc.callStr(eq("eth_gasPrice"),any(JSONArray.class))).thenReturn("0x3b9aca00");
        when(rpc.baseFeePerGasOrZero()).thenReturn(BigInteger.ZERO);
        when(rpc.getTransactionCount(anyString())).thenReturn(BigInteger.valueOf(4));
        JournalFixture.install();JournalFixture.wire(rpc);
    }
    String send(BooleanSupplier guard) throws Exception {
        return EthTx.send(rpc,FIXTURE,1,TO,"0x",BigInteger.ONE,BigInteger.valueOf(21000),guard);
    }
    static void cancelled(Throwing operation) throws Exception {
        try{operation.run();fail("Obsolete operation must be cancelled");}
        catch(IllegalStateException expected){assertEquals("Wallet session changed. Review again.",expected.getMessage());}
    }
    interface Throwing {void run() throws Exception;}
    @Test public void missingThrowingAndFalseGuardsNeverReachRpc() throws Exception {
        for(BooleanSupplier guard:new BooleanSupplier[]{null,()->false,()->{throw new IllegalStateException("fixture");}})cancelled(()->send(guard));
        verifyNoInteractions(rpc);
    }
    @Test public void invalidationDuringFeeOrNonceReadsCannotSignOrBroadcast() throws Exception {
        for(int boundary=0;boundary<3;boundary++) {
            setup();AtomicBoolean allowed=new AtomicBoolean(true);
            if(boundary==0)when(rpc.callStr(eq("eth_gasPrice"),any())).thenAnswer(i->{allowed.set(false);return "0x3b9aca00";});
            if(boundary==1)when(rpc.baseFeePerGasOrZero()).thenAnswer(i->{allowed.set(false);return BigInteger.ZERO;});
            if(boundary==2)when(rpc.getTransactionCount(anyString())).thenAnswer(i->{allowed.set(false);return BigInteger.valueOf(4);});
            try(MockedStatic<TransactionEncoder> encoder=mockStatic(TransactionEncoder.class)) {
                cancelled(()->send(allowed::get));encoder.verifyNoInteractions();verify(rpc,never()).sendRawTransaction(anyString());
            }
        }
    }
    @Test public void cancellationAfterSigningDoesNotSendOrAdvanceNonce() throws Exception {
        AtomicBoolean allowed=new AtomicBoolean(true);
        try(MockedStatic<TransactionEncoder> encoder=mockStatic(TransactionEncoder.class,CALLS_REAL_METHODS)) {
            encoder.when(()->TransactionEncoder.signMessage(any(RawTransaction.class),eq(1L),eq(FIXTURE)))
                    .thenAnswer(i->{byte[] signed=(byte[])i.callRealMethod();allowed.set(false);return signed;});
            cancelled(()->send(allowed::get));verify(rpc,never()).sendRawTransaction(anyString());
        }
        when(rpc.sendRawTransaction(anyString())).thenAnswer(i->{assertEquals(BigInteger.valueOf(4),TransactionDecoder.decode(i.getArgument(0)).getNonce());return JournalFixture.accept(i.getArgument(0));});
        assertEquals(send(()->true),JournalFixture.lastHash);
    }
    @Test public void dispatchedReceiptSurvivesLaterInvalidationAndNonceAdvancesOnce() throws Exception {
        AtomicBoolean allowed=new AtomicBoolean(true);java.util.List<BigInteger> nonces=new java.util.ArrayList<>();
        when(rpc.sendRawTransaction(anyString())).thenAnswer(i->{nonces.add(TransactionDecoder.decode(i.getArgument(0)).getNonce());allowed.set(false);return JournalFixture.accept(i.getArgument(0));});
        assertEquals(send(allowed::get),JournalFixture.lastHash);assertEquals(send(()->true),JournalFixture.lastHash);
        assertEquals(java.util.Arrays.asList(BigInteger.valueOf(4),BigInteger.valueOf(4)),nonces);
    }
    @Test public void guardedHtlcApprovalsAndLocksShareTheBoundaryButRecoveryRemainsAvailable() throws Exception {
        EthHtlc h=new EthHtlc(rpc,FIXTURE,EthNet.MAINNET,()->false);String token=EthNet.MAINNET.token("USDT").address;
        cancelled(()->h.approve(token,BigInteger.ONE));
        cancelled(()->h.newContract(HASH,TO,HASH,BigInteger.valueOf(1900000000),token,BigInteger.ONE,BigInteger.ONE,false));
        verifyNoInteractions(rpc);
        assertEquals(h.refund(HASH),JournalFixture.lastHash);assertEquals(h.withdraw(HASH,HASH),JournalFixture.lastHash);
        verify(rpc,times(2)).sendRawTransaction(anyString());
    }
    @Test public void unchangedLockKeepsTheExistingCalldataAndFeePolicy() throws Exception {
        EthHtlc h=new EthHtlc(rpc,FIXTURE,EthNet.MAINNET,()->true);String token=EthNet.MAINNET.token("USDT").address;
        when(rpc.sendRawTransaction(anyString())).thenAnswer(i->{RawTransaction raw=TransactionDecoder.decode(i.getArgument(0));
            assertEquals(EthNet.MAINNET.htlc.toLowerCase(),raw.getTo().toLowerCase());assertEquals(EthHtlc.GAS_LOCK,raw.getGasLimit());
            assertEquals(EthSend.effectiveGasPriceWei(BigInteger.valueOf(1000000000),BigInteger.ZERO),raw.getGasPrice());
            assertTrue(raw.getData().contains(TO.substring(2)));return JournalFixture.accept(i.getArgument(0));});
        assertEquals(h.newContract(HASH,TO,HASH,BigInteger.valueOf(1900000000),token,BigInteger.ONE,BigInteger.TEN,false),JournalFixture.lastHash);
    }
}
