// VENDORED VERBATIM from apks/atomix 0.1.83 app/src/test/java/com/eurobuddha/atomix/eth/PendingTokenDebitsTest.java by scripts/vendor-atomix.sh — do not edit here; re-vendor.
package com.eurobuddha.atomix.eth;
import com.eurobuddha.minimaapi.ethereum.EthereumTransactions;
import java.math.BigInteger;
import java.util.*;
import org.json.JSONObject;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.web3j.crypto.Credentials;
import org.web3j.crypto.Hash;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/** Actual existing EthSend/EthHtlc encoders; public fixture credentials and fake RPC only. */
public class PendingTokenDebitsTest {
    final Credentials owner=Credentials.create("01");
    final String token=EthNet.MAINNET.token("USDT").address,htlc=EthNet.MAINNET.htlc,other="0x"+"44".repeat(20);
    final Map<String,String> saved=new HashMap<>();
    final EthereumTransactions journal=new EthereumTransactions(new EthereumTransactions.Store(){
        public String read(String key){return saved.getOrDefault(key,"[]");}public void write(String key,String value){saved.put(key,value);}
    });
    final EthereumTransactions.Rpc rpc=new EthereumTransactions.Rpc(){
        public BigInteger chain(){return BigInteger.ONE;}public BigInteger pending(String address){return BigInteger.ZERO;}
        public String broadcast(String raw){return Hash.sha3(raw);}public JSONObject receipt(String hash){return null;}public JSONObject transaction(String hash){return null;}
    };
    void add(String destination,String data,String operation,BigInteger price)throws Exception{
        journal.send(rpc,owner,1,destination,data,BigInteger.ZERO,BigInteger.valueOf(400000),price,operation,()->{});
    }
    void add(String destination,String data)throws Exception{add(destination,data,null,BigInteger.ONE);}
    BigInteger debit()throws Exception{return PendingTokenDebits.total(journal.fundingSnapshot(1,owner.getAddress()).intents,BigInteger.ZERO,owner.getAddress(),token,htlc);}
    String lock(String asset,BigInteger amount)throws Exception{
        try(MockedStatic<EthTx> tx=mockStatic(EthTx.class,i->i.getArgument(4))){
            return new EthHtlc(mock(EthRpc.class),owner,EthNet.MAINNET).newContract("0x"+"11".repeat(32),other,"0x"+"22".repeat(32),BigInteger.valueOf(999999),asset,amount,BigInteger.ONE,false);
        }
    }
    @Test public void actualHtlcLockEncodingReservesOnlyTheLockedTokenAmount()throws Exception{
        add(htlc,lock(token,BigInteger.valueOf(5000)));assertEquals(BigInteger.valueOf(5000),debit());
        add(htlc,lock(other,BigInteger.valueOf(9000)));assertEquals(BigInteger.valueOf(5000),debit());
    }
    @Test public void actualTransferEncodingReservesDifferentNoncesCumulatively()throws Exception{
        add(token,EthSend.buildTransferData(other,BigInteger.valueOf(1000)));add(token,EthSend.buildTransferData(other,BigInteger.valueOf(2000)));
        assertEquals(BigInteger.valueOf(3000),debit());
    }
    @Test public void replacementFeeDoesNotDoubleTheDebit()throws Exception{
        String data=lock(token,BigInteger.valueOf(5000));add(htlc,data,"lock:fixture",BigInteger.ONE);add(htlc,data,"lock:fixture",BigInteger.TWO);
        assertEquals(BigInteger.valueOf(5000),debit());
    }
    @Test public void selfAndOtherTokenTransfersDoNotReserveUsdt()throws Exception{
        add(token,EthSend.buildTransferData(owner.getAddress(),BigInteger.valueOf(5000)));add(other,EthSend.buildTransferData(other,BigInteger.valueOf(9000)));
        assertEquals(BigInteger.ZERO,debit());
    }
    @Test public void actualApprovalWithdrawAndRefundDoNotPretendToBeDebitsOrCredits()throws Exception{
        String approve,withdraw,refund;
        try(MockedStatic<EthTx> tx=mockStatic(EthTx.class,i->i.getArgument(4))){
            EthHtlc client=new EthHtlc(mock(EthRpc.class),owner,EthNet.MAINNET);
            approve=client.approve(token,BigInteger.TEN.pow(60));withdraw=client.withdraw("0x"+"11".repeat(32),"0x"+"22".repeat(32));refund=client.refund("0x"+"11".repeat(32));
        }
        add(token,approve);add(htlc,withdraw);add(htlc,refund);assertEquals(BigInteger.ZERO,debit());
    }
    @Test public void foreignContractCannotImpersonateTheConfiguredHtlc()throws Exception{
        add(other,lock(token,BigInteger.ONE));assertThrows(java.io.IOException.class,this::debit);
    }
    @Test public void malformedKnownAbiCannotBeInterpretedAsZero()throws Exception{
        String data=EthSend.buildTransferData(other,BigInteger.ONE)+"00";add(token,data);assertThrows(java.io.IOException.class,this::debit);
    }
    @Test public void noncanonicalAddressPaddingIsRejected()throws Exception{
        String data=EthSend.buildTransferData(other,BigInteger.ONE);data=data.substring(0,10)+"1"+data.substring(11);add(token,data);assertThrows(java.io.IOException.class,this::debit);
    }
    @Test public void receiptStatusDoesNotDecideWhetherABalanceAlreadyIncludesTheSpend()throws Exception{
        add(token,EthSend.buildTransferData(other,BigInteger.TEN));
        assertEquals(BigInteger.ZERO,PendingTokenDebits.total(journal.fundingSnapshot(1,owner.getAddress()).intents,BigInteger.ONE,owner.getAddress(),token,htlc));
    }
}
