// VENDORED VERBATIM from apks/atomix 0.1.83 app/src/test/java/com/eurobuddha/atomix/eth/ReviewedEthSendTest.java by scripts/vendor-atomix.sh — do not edit here; re-vendor.
package com.eurobuddha.atomix.eth;

import java.math.BigInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.*;
import org.mockito.MockedStatic;
import org.web3j.crypto.*;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Reuses EthAuthorizationTest's real encoder / synthetic key / mocked RPC boundary. */
public class ReviewedEthSendTest {
    static final Credentials KEY=Credentials.create("01");
    static final String TO="0x"+"12".repeat(20),HASH="0x"+"34".repeat(32);
    EthRpc rpc;
    @Before public void setup()throws Exception {EthTx.resetAll();rpc=mock(EthRpc.class);when(rpc.getTransactionCount(anyString())).thenReturn(BigInteger.ONE);JournalFixture.install();JournalFixture.wire(rpc);}
    String send(BigInteger price,BigInteger gas,java.util.function.BooleanSupplier guard)throws Exception {return EthTx.send(rpc,KEY,1L,TO,"",BigInteger.ONE,gas,price,guard);}
    @Test public void invalidFeeCannotTouchRpcOrEncoder()throws Exception {
        try(MockedStatic<TransactionEncoder> encoder=mockStatic(TransactionEncoder.class)) {
            for(BigInteger price:new BigInteger[]{null,BigInteger.ZERO,BigInteger.valueOf(-1),EthTx.MAX_GAS_PRICE.add(BigInteger.ONE)})
                assertThrows(IllegalArgumentException.class,()->send(price,EthSend.GAS_ETH,()->true));
            for(BigInteger gas:new BigInteger[]{null,BigInteger.ZERO,BigInteger.valueOf(-1),EthTx.MAX_GAS_LIMIT.add(BigInteger.ONE)})
                assertThrows(IllegalArgumentException.class,()->send(BigInteger.ONE,gas,()->true));
            encoder.verifyNoInteractions();verifyNoInteractions(rpc);
        }
    }
    @Test public void deniedOrThrowingContextCannotReadOrSign()throws Exception {
        for(java.util.function.BooleanSupplier guard:new java.util.function.BooleanSupplier[]{null,()->false,()->{throw new IllegalStateException();}})
            assertThrows(IllegalStateException.class,()->send(BigInteger.ONE,EthSend.GAS_ETH,guard));
        verifyNoInteractions(rpc);
    }
    @Test public void nonceReplyCannotReviveRevokedConsent()throws Exception {
        AtomicBoolean current=new AtomicBoolean(true);
        when(rpc.getTransactionCount(anyString())).thenAnswer(inv->{current.set(false);return BigInteger.ONE;});
        try(MockedStatic<TransactionEncoder> encoder=mockStatic(TransactionEncoder.class)) {
            assertThrows(IllegalStateException.class,()->send(BigInteger.ONE,EthSend.GAS_ETH,current::get));encoder.verifyNoInteractions();
        }
        verify(rpc,never()).sendRawTransaction(anyString());
    }
    @Test public void revocationAfterSigningCannotBroadcast()throws Exception {
        AtomicBoolean current=new AtomicBoolean(true);
        try(MockedStatic<TransactionEncoder> encoder=mockStatic(TransactionEncoder.class,CALLS_REAL_METHODS)) {
            encoder.when(()->TransactionEncoder.signMessage(any(RawTransaction.class),eq(1L),eq(KEY))).thenAnswer(inv->{byte[] bytes=(byte[])inv.callRealMethod();current.set(false);return bytes;});
            assertThrows(IllegalStateException.class,()->send(BigInteger.ONE,EthSend.GAS_ETH,current::get));
        }
        verify(rpc,never()).sendRawTransaction(anyString());
    }
    @Test public void explicitPriceIsNeverRequoted()throws Exception {
        when(rpc.sendRawTransaction(anyString())).thenAnswer(inv->{assertEquals(BigInteger.valueOf(12345),TransactionDecoder.decode(inv.getArgument(0)).getGasPrice());return JournalFixture.accept(inv.getArgument(0));});
        assertEquals(send(BigInteger.valueOf(12345),EthSend.GAS_ETH,()->true),JournalFixture.lastHash);
        verify(rpc,never()).callStr(anyString(),any());verify(rpc,never()).baseFeePerGasOrZero();
    }
}
