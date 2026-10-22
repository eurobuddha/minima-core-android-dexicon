// VENDORED VERBATIM from apks/atomix 0.1.82 app/src/main/java/com/eurobuddha/atomix/eth/PendingTokenDebits.java by scripts/vendor-atomix.sh — do not edit here; re-vendor.
package com.eurobuddha.atomix.eth;

import com.eurobuddha.minimaapi.ethereum.EthereumTransactions;
import java.io.IOException;
import java.math.BigInteger;
import java.util.List;
import org.web3j.crypto.Hash;

/** Decode the existing EthSend/EthHtlc ABI intents; one saved intent per account nonce. */
final class PendingTokenDebits {
    private static final String TRANSFER=selector("transfer(address,uint256)"),
        APPROVE=selector("approve(address,uint256)"),TRANSFER_FROM=selector("transferFrom(address,address,uint256)"),
        LOCK=selector("newContract(bytes32,address,bytes32,uint256,address,uint256,uint256,bool)"),
        WITHDRAW=selector("withdraw(bytes32,bytes32)"),REFUND=selector("refund(bytes32)");
    private static String selector(String signature){return Hash.sha3String(signature).substring(0,10);}
    static BigInteger total(List<EthereumTransactions.PendingIntent> intents,BigInteger minedNonce,String owner,String token,String htlc)throws IOException{
        if(minedNonce==null||minedNonce.signum()<0||minedNonce.bitLength()>64)throw new IOException("Invalid mined Ethereum nonce");
        BigInteger result=BigInteger.ZERO;
        for(EthereumTransactions.PendingIntent intent:intents){
            // Already reflected in the balance at this exact block, regardless of which replacement won.
            if(intent.nonce.compareTo(minedNonce)<0)continue;
            String data=intent.data;
            if("0x".equals(data))continue; // Native ETH transfer does not debit ERC20 units.
            if(data.startsWith(TRANSFER)){
                words(data,2);String recipient=address(data,0);
                if(intent.to.equalsIgnoreCase(token)&&!recipient.equalsIgnoreCase(owner))result=result.add(number(data,1));
            }else if(data.startsWith(APPROVE)){words(data,2);address(data,0);}
            else if(data.startsWith(TRANSFER_FROM)){
                words(data,3);String from=address(data,0),to=address(data,1);
                if(intent.to.equalsIgnoreCase(token)&&from.equalsIgnoreCase(owner)&&!to.equalsIgnoreCase(owner))result=result.add(number(data,2));
            }else if(intent.to.equalsIgnoreCase(htlc)&&data.startsWith(LOCK)){
                words(data,8);address(data,1);String lockedToken=address(data,4);
                if(number(data,7).compareTo(BigInteger.ONE)>0)throw new IOException("Invalid saved HTLC boolean");
                if(lockedToken.equalsIgnoreCase(token))result=result.add(number(data,5));
            }else if(intent.to.equalsIgnoreCase(htlc)&&data.startsWith(WITHDRAW)){words(data,2);}
            else if(intent.to.equalsIgnoreCase(htlc)&&data.startsWith(REFUND)){words(data,1);}
            else throw new IOException("Cannot determine the token debit of a saved Ethereum transaction");
        }
        return result;
    }
    private static void words(String data,int count)throws IOException{
        if(data==null||!data.matches("0x[0-9a-f]+")||data.length()!=10+64*count)throw new IOException("Invalid saved Ethereum calldata");
    }
    private static BigInteger number(String data,int index){return new BigInteger(data.substring(10+64*index,10+64*(index+1)),16);}
    private static String address(String data,int index)throws IOException{
        String word=data.substring(10+64*index,10+64*(index+1));
        if(!word.substring(0,24).equals("0".repeat(24)))throw new IOException("Invalid saved Ethereum ABI address");
        return "0x"+word.substring(24);
    }
}
