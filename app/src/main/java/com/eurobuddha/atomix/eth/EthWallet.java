// VENDORED VERBATIM from apks/atomix 0.1.83 app/src/main/java/com/eurobuddha/atomix/eth/EthWallet.java by scripts/vendor-atomix.sh — do not edit here; re-vendor.
package com.eurobuddha.atomix.eth;

import android.os.Handler;

import org.json.JSONObject;
import com.eurobuddha.comms.NodeApi;
import org.web3j.abi.FunctionEncoder;
import org.web3j.abi.FunctionReturnDecoder;
import org.web3j.abi.TypeReference;
import org.web3j.abi.datatypes.Address;
import org.web3j.abi.datatypes.Function;
import org.web3j.abi.datatypes.Type;
import org.web3j.abi.datatypes.generated.Uint256;
import org.web3j.crypto.Credentials;

import java.io.IOException;
import java.math.BigInteger;
import java.util.Collections;
import java.util.List;

/**
 * The in-app Ethereum wallet. The key is derived from the Minima node seed via
 * {@code seedrandom modifier:ethbridge} — the SAME derivation the bridge MiniDapp uses, so the same
 * node yields the same ETH address (interop). The deterministic key is re-derived per session and
 * never persisted; the node's seed phrase is the only backup. An explicit imported key is also supported.
 */
public final class EthWallet {

    public interface Cb { void ok(String address); void err(String msg); }

    private volatile Credentials creds;
    private volatile boolean imported;   // true if the key was imported rather than seed-derived

    public boolean ready() { return creds != null; }
    public boolean isImported() { return imported; }
    public Credentials creds() { return creds; }
    public String address() { return creds == null ? null : creds.getAddress(); }

    /** The raw ETH private key as 0x + 64 hex (32-byte, zero-padded), or null if no wallet. Used for
     *  recovery / import elsewhere. Works for both seed-derived and imported keys (both populate creds). */
    public String privateKeyHex() {
        if (creds == null) return null;
        return org.web3j.utils.Numeric.toHexStringWithPrefixZeroPadded(creds.getEcKeyPair().getPrivateKey(), 64);
    }

    /** Validate before constructing or publishing credentials. Reuses ETH Wallet's exact 32-byte check. */
    private static Credentials nodeCredentials(JSONObject reply) {
        if (reply == null || !Boolean.TRUE.equals(reply.opt("status"))) throw new IllegalArgumentException();
        JSONObject response = reply.optJSONObject("response");
        Object raw = response == null ? null : response.opt("seedrandom");
        if (!(raw instanceof String)) throw new IllegalArgumentException();
        String hex = ((String) raw).trim();
        if (hex.startsWith("0x") || hex.startsWith("0X")) hex = hex.substring(2);
        if (!hex.matches("[0-9a-fA-F]{64}")) throw new IllegalArgumentException();
        BigInteger scalar = new BigInteger(hex, 16);
        if (scalar.signum() <= 0 || scalar.compareTo(org.web3j.crypto.Sign.CURVE_PARAMS.getN()) >= 0)
            throw new IllegalArgumentException();
        return Credentials.create("0x" + hex);
    }

    /** Derive without allowing a malformed response or exception to expose or replace a key. */
    public void deriveFromNode(NodeApi node, final Handler ui, final Cb cb) {
        node.cmd("seedrandom modifier:ethbridge", new NodeApi.Cb() {
            @Override public void onResult(JSONObject reply) {
                final Credentials candidate;
                final String address;
                try { candidate = nodeCredentials(reply); address = candidate.getAddress(); }
                catch (Exception invalid) {
                    post(ui, () -> cb.err("Node returned an invalid Ethereum key. Not using it."));
                    return;
                }
                creds = candidate;
                imported = false;
                post(ui, () -> cb.ok(address));
            }
            @Override public void onError(String ignored) {
                post(ui, () -> cb.err("Ethereum key derivation failed. Retry when the node is ready."));
            }
        });
    }

    /** Read the node's current address without replacing the in-use wallet or echoing secret data. */
    public void probeNodeAddress(NodeApi node, final Handler ui, final Cb cb) {
        node.cmd("seedrandom modifier:ethbridge", new NodeApi.Cb() {
            @Override public void onResult(JSONObject reply) {
                final String address;
                try { address = nodeCredentials(reply).getAddress(); }
                catch (Exception invalid) { post(ui, () -> cb.err("probe failed")); return; }
                post(ui, () -> cb.ok(address));
            }
            @Override public void onError(String ignored) { post(ui, () -> cb.err("probe failed")); }
        });
    }

    // MA-7: importKey() deleted (dead code — no callers). It took a raw hex private key with no length/format
    // validation, an unaudited key-import surface. isImported() stays (IdentityWatch reads it): with no importer,
    // `imported` is always false, which correctly means "this node's ETH key is always seed-derived".

    public void clear() { creds = null; imported = false; }

    // ---- balances (synchronous; call off the main thread) ----

    public BigInteger ethBalanceWei(EthRpc rpc) throws IOException {
        if (creds == null) return BigInteger.ZERO;
        return rpc.getBalance(creds.getAddress());
    }

    /** ERC20 balanceOf(owner) raw (un-scaled by decimals). */
    public BigInteger erc20BalanceRaw(EthRpc rpc, String tokenAddress) throws IOException {
        if (creds == null) return BigInteger.ZERO;
        return erc20BalanceRaw(rpc, tokenAddress, creds.getAddress());
    }

    public static BigInteger erc20BalanceRaw(EthRpc rpc, String tokenAddress, String owner) throws IOException {
        Function fn = new Function("balanceOf",
                Collections.singletonList(new Address(owner)),
                Collections.singletonList(new TypeReference<Uint256>() {}));
        String data = FunctionEncoder.encode(fn);
        String ret = rpc.ethCall(tokenAddress, data);
        if (ret == null || ret.length() < 3) return BigInteger.ZERO;
        // MA-8 (+review): guard the decode AND the cast — malformed contract return data would otherwise throw
        // an unchecked NumberFormatException/ClassCastException that crashes this background balance read
        // (declared only IOException). A balance that can't be read reads as ZERO.
        try {
            List<Type> out = FunctionReturnDecoder.decode(ret, fn.getOutputParameters());
            if (out.isEmpty()) return BigInteger.ZERO;
            Object v = out.get(0).getValue();
            return v instanceof BigInteger ? (BigInteger) v : BigInteger.ZERO;
        } catch (RuntimeException e) {
            return BigInteger.ZERO;
        }
    }

    /** Account balance and nonce share one checked block; pending data can only lower funding. */
    public static BigInteger erc20AvailableRaw(EthRpc rpc, EthNet network, String token, String owner,
            com.eurobuddha.minimaapi.ethereum.EthereumTransactions.FundingSnapshot snapshot) throws IOException {
        if(rpc==null||network==null||owner==null||!owner.matches("0x[0-9a-fA-F]{40}")
                ||token==null||!token.matches("0x[0-9a-fA-F]{40}")||snapshot==null)throw new IOException("Invalid Ethereum balance context");
        if(!BigInteger.valueOf(network.chainId).equals(EthRpc.transactionCount(rpc.call("eth_chainId",new org.json.JSONArray()))))
            throw new IOException("Ethereum RPC is connected to a different chain");
        Object rawBlock=rpc.call("eth_getBlockByNumber",new org.json.JSONArray().put("latest").put(false));
        if(!(rawBlock instanceof JSONObject))throw new IOException("Missing Ethereum balance block");
        JSONObject block=(JSONObject)rawBlock;
        String height=block.optString("number"),hash=block.optString("hash");
        EthRpc.transactionCount(height);
        if(!hash.matches("0x[0-9a-fA-F]{64}")||new BigInteger(hash.substring(2),16).signum()==0)throw new IOException("Invalid Ethereum balance block");
        BigInteger minedNonce=EthRpc.transactionCount(rpc.call("eth_getTransactionCount",new org.json.JSONArray().put(owner).put(height)));
        BigInteger reserved=PendingTokenDebits.total(snapshot.intents,minedNonce,owner,token,network.htlc);
        Function fn=new Function("balanceOf",Collections.singletonList(new Address(owner)),Collections.singletonList(new TypeReference<Uint256>() {}));
        BigInteger available=null;
        for(String tag:new String[]{height,"pending"}) {
            Object result;
            try {
                JSONObject tx=new JSONObject().put("to",token).put("data",FunctionEncoder.encode(fn));
                result=rpc.call("eth_call",new org.json.JSONArray().put(tx).put(tag));
            }catch(org.json.JSONException invalid){throw new IOException("Invalid Ethereum balance request",invalid);}
            if(!(result instanceof String)||!((String)result).matches("0[xX][0-9a-fA-F]{64}"))throw new IOException("Invalid Ethereum balance reply");
            BigInteger balance=new BigInteger(((String)result).substring(2),16);
            // Deduct local reservations from mined state only. Pending may already include the debit.
            available=available==null?balance.subtract(reserved).max(BigInteger.ZERO):available.min(balance);
        }
        Object current=rpc.call("eth_getBlockByNumber",new org.json.JSONArray().put(height).put(false));
        if(!(current instanceof JSONObject)||!hash.equalsIgnoreCase(((JSONObject)current).optString("hash"))
                ||!height.equals(((JSONObject)current).optString("number"))||!snapshot.unchanged())
            throw new IOException("Ethereum funding changed during review");
        return available;
    }

    /** Format a raw integer amount with the given decimals to a trimmed decimal string. */
    public static String format(BigInteger raw, int decimals, int maxFrac) {
        if (raw == null) return "0";
        java.math.BigDecimal v = new java.math.BigDecimal(raw, decimals);
        java.math.BigDecimal r = v.setScale(maxFrac, java.math.RoundingMode.DOWN).stripTrailingZeros();
        String s = r.toPlainString();
        return s.isEmpty() ? "0" : s;
    }

    private static void post(Handler ui, Runnable r) { if (ui != null) ui.post(r); else r.run(); }
}
