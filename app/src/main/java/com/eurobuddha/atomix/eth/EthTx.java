// VENDORED VERBATIM from apks/atomix 0.1.82 app/src/main/java/com/eurobuddha/atomix/eth/EthTx.java by scripts/vendor-atomix.sh — do not edit here; re-vendor.
package com.eurobuddha.atomix.eth;

import org.json.JSONArray;
import org.web3j.crypto.Credentials;
import org.web3j.crypto.RawTransaction;
import org.web3j.crypto.TransactionEncoder;
import org.web3j.utils.Numeric;

import java.math.BigInteger;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Signs + sends a legacy (EIP-155) Ethereum transaction via our JSON-RPC client. We use legacy
 * gasPrice transactions (accepted on Ethereum mainnet) so we need no Infura gas API / key.
 * Blocking — call off the main thread.
 */
public final class EthTx {

    private static final BigInteger FALLBACK_GAS_PRICE = BigInteger.valueOf(1_000_000_000L); // 1 gwei (only used when eth_gasPrice returns ≤0)

    /** Matches NodeApi's fail-closed BooleanSupplier contract. No signed transaction is sent on refusal. */
    public static void requireAuthorized(java.util.function.BooleanSupplier authorized) {
        boolean allowed;
        try { allowed = authorized != null && authorized.getAsBoolean(); }
        catch (RuntimeException failure) { allowed = false; }
        if (!allowed) throw new IllegalStateException("Wallet session changed. Review again.");
    }

    // Reuse ETH Wallet's hard fee caps (1500 gwei / 1M gas), checked before signing.
    public static final BigInteger MAX_GAS_PRICE = BigInteger.valueOf(1_500_000_000_000L);
    public static final BigInteger MAX_GAS_LIMIT = BigInteger.valueOf(1_000_000);
    public static void requireFees(BigInteger gasPrice, BigInteger gasLimit) {
        if (gasPrice == null || gasPrice.signum() <= 0 || gasPrice.compareTo(MAX_GAS_PRICE) > 0
                || gasLimit == null || gasLimit.signum() <= 0 || gasLimit.compareTo(MAX_GAS_LIMIT) > 0)
            throw new IllegalArgumentException("Ethereum fee is outside the safe range. Review your RPC endpoint.");
    }

    /** Compatibility entry point. Connection changes cannot erase outstanding transactions. */
    public static void resetAll() { /* Durable nonce ownership survives endpoint and account changes. */ }

    public static String send(EthRpc rpc, Credentials creds, long chainId,
                              String to, String data, BigInteger value, BigInteger gasLimit) throws Exception {
        return send(rpc, creds, chainId, to, data, value, gasLimit, () -> true);
    }

    /** The existing nonce lock and fee calculation are unchanged; only unstarted writes can be cancelled. */
    public static String send(EthRpc rpc, Credentials creds, long chainId,
                              String to, String data, BigInteger value, BigInteger gasLimit,
                              java.util.function.BooleanSupplier authorized) throws Exception {
        return sendOperation(rpc,creds,chainId,to,data,value,gasLimit,authorized,"pending");
    }

    public static String sendOperation(EthRpc rpc,Credentials creds,long chainId,String to,String data,
                                       BigInteger value,BigInteger gasLimit,java.util.function.BooleanSupplier authorized,String operation) throws Exception {
        requireAuthorized(authorized);
        BigInteger rawGp = EthRpc.hexToBig(rpc.callStr("eth_gasPrice", new JSONArray()));
        if (rawGp.signum() <= 0) rawGp = FALLBACK_GAS_PRICE;
        BigInteger gasPrice = EthSend.effectiveGasPriceWei(rawGp, rpc.baseFeePerGasOrZero());
        requireFees(gasPrice,gasLimit);
        return submit(rpc,creds,chainId,to,data,value,gasLimit,gasPrice,authorized,operation);
    }

    /** Same explicit-price boundary used by ETH Wallet. Never recompute a reviewed fee. */
    public static String send(EthRpc rpc, Credentials creds, long chainId,
                              String to, String data, BigInteger value, BigInteger gasLimit, BigInteger gasPrice,
                              java.util.function.BooleanSupplier authorized) throws Exception {
        requireAuthorized(authorized);
        requireFees(gasPrice, gasLimit);
        return submit(rpc,creds,chainId,to,data,value,gasLimit,gasPrice,authorized,null);
    }

    private static String submit(EthRpc rpc,Credentials creds,long chainId,String to,String data,BigInteger value,
                                 BigInteger gas,BigInteger price,java.util.function.BooleanSupplier authorized,String operation) throws Exception {
        requireAuthorized(authorized);
        return com.eurobuddha.minimaapi.ethereum.EthereumTransactions.shared().send(
                new com.eurobuddha.minimaapi.ethereum.EthereumTransactions.Rpc() {
                    public BigInteger chain() throws Exception { return EthRpc.transactionCount(rpc.call("eth_chainId",new JSONArray())); }
                    public BigInteger pending(String address) throws Exception { return rpc.getTransactionCount(address); }
                    public String broadcast(String raw) throws Exception { return rpc.sendRawTransaction(raw); }
                    public org.json.JSONObject receipt(String hash) throws Exception { return object(rpc.call("eth_getTransactionReceipt",new JSONArray().put(hash))); }
                    public org.json.JSONObject transaction(String hash) throws Exception { return object(rpc.call("eth_getTransactionByHash",new JSONArray().put(hash))); }
                },creds,chainId,to,data,value,gas,price,operation,()->requireAuthorized(authorized));
    }
    private static org.json.JSONObject object(Object value) throws java.io.IOException {
        if(value==null||value==org.json.JSONObject.NULL)return null;
        if(!(value instanceof org.json.JSONObject))throw new java.io.IOException("Invalid Ethereum transaction lookup");
        return(org.json.JSONObject)value;
    }
}
