// VENDORED VERBATIM from apks/atomix 0.1.84 app/src/test/java/com/eurobuddha/comms/InteropVectorsTest.java by scripts/vendor-atomix.sh — do not edit here; re-vendor.
package com.eurobuddha.comms;

import static org.junit.Assert.*;

import com.goterl.lazysodium.LazySodium;
import com.goterl.lazysodium.LazySodiumJava;
import com.goterl.lazysodium.SodiumJava;

import org.json.JSONObject;
import org.junit.Test;

import java.io.File;
import java.io.FileWriter;
import java.nio.charset.StandardCharsets;

/**
 * INTEROP VECTOR GENERATOR — the byte-truth fixture for the AtomiX MDS port.
 *
 * The MDS (JavaScript) implementation must derive the SAME identities, open the SAME sealed blobs, verify the
 * SAME signatures, and derive the SAME ETH address as this native code, from the same node seed — that is what
 * makes it a real interoperating peer on the shared order books rather than a lookalike. This test emits a JSON
 * vector file that the JS side's harness consumes and asserts byte-equality against (spike 0c/0d, then the
 * phase-7 release gate). It also asserts the native side's own invariants so the fixture can't silently rot.
 *
 * Output: build/interop_vectors.json (also copied by the JS harness into the atomix-mds repo).
 */
public class InteropVectorsTest {

    /** Fixed test seeds exercising BOTH branches of the seed-bytes rule (0x → hex decode, else raw UTF-8). */
    private static final String SEED_UTF8 = "atomix-interop-vector-seed-01";
    private static final String SEED_HEX  = "0x0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF";

    /** Fixed plaintext + message for seal/sign vectors. */
    private static final String PLAINTEXT = "{\"hash\":\"0xC0FFEE\",\"n\":1}";
    private static final String SIGN_MSG  = "atomix-sign-vector";

    /** Same rule as MainActivity/SwapService: 0x → hex bytes, else UTF-8 bytes. */
    private static byte[] seedBytes(String seed) {
        return seed.startsWith("0x") ? Hex.from(seed) : seed.getBytes(StandardCharsets.UTF_8);
    }

    /** Native EthHtlc.b32: left-pad when shorter, keep the LAST 32 bytes when longer. */
    private static byte[] b32(String hex) {
        byte[] b = Hex.from(hex);
        if (b.length == 32) return b;
        byte[] out = new byte[32];
        System.arraycopy(b, Math.max(0, b.length - 32), out, Math.max(0, 32 - b.length), Math.min(32, b.length));
        return out;
    }
    private static String repeat(String s, int n) { StringBuilder b = new StringBuilder(); for (int i = 0; i < n; i++) b.append(s); return b.toString(); }

    @Test public void generateVectors() throws Exception {
        LazySodium ls = new LazySodiumJava(new SodiumJava());
        JSONObject out = new JSONObject();

        // ---- identities: both seeds × both legacy contexts ----
        JSONObject ids = new JSONObject();
        for (String seed : new String[]{SEED_UTF8, SEED_HEX}) {
            for (String ctx : new String[]{"usdtswap", "minimaswap"}) {
                CommsIdentity id = CommsIdentity.fromSeed(ls, seedBytes(seed), ctx);
                JSONObject j = new JSONObject();
                j.put("boxPk", Hex.to(id.boxPk));
                j.put("signPk", Hex.to(id.signPk));
                j.put("publicId", id.publicId());
                ids.put(seed + "|" + ctx, j);
            }
        }
        out.put("identities", ids);

        // ---- envelope: a REAL sealed blob (full {f,b,s} construction) the JS side must open + verify ----
        CommsIdentity sender = CommsIdentity.fromSeed(ls, seedBytes(SEED_UTF8), "usdtswap");
        CommsIdentity recip  = CommsIdentity.fromSeed(ls, seedBytes(SEED_HEX), "usdtswap");
        String blob = new LocalEcCryptoProvider(ls, sender)
                .seal(recip.publicId(), PLAINTEXT.getBytes(StandardCharsets.UTF_8));
        assertNotNull("native seal must succeed", blob);
        // native round-trip sanity so the fixture can't rot silently
        Opened opened = new LocalEcCryptoProvider(ls, recip).open(blob);
        assertNotNull(opened);
        assertEquals(PLAINTEXT, new String(opened.plaintext, StandardCharsets.UTF_8));
        assertEquals(sender.publicId(), opened.fromPublicId);
        JSONObject env = new JSONObject();
        env.put("senderPublicId", sender.publicId());
        env.put("recipientPublicId", recip.publicId());
        env.put("plaintext", PLAINTEXT);
        env.put("sealedBlobHex", blob);
        out.put("envelope", env);

        // ---- bare Ed25519 detached signature (the order-book signing primitive) ----
        byte[] msg = SIGN_MSG.getBytes(StandardCharsets.UTF_8);
        byte[] sig = new byte[com.goterl.lazysodium.interfaces.Sign.BYTES];
        assertTrue(ls.cryptoSignDetached(sig, msg, msg.length, sender.signSk));
        assertTrue(ls.cryptoSignVerifyDetached(sig, msg, msg.length, sender.signPk));
        JSONObject sj = new JSONObject();
        sj.put("message", SIGN_MSG);
        sj.put("signerPublicId", sender.publicId());
        sj.put("signPk", Hex.to(sender.signPk));
        sj.put("sigHex", Hex.to(sig));
        out.put("sign", sj);

        // ---- ETH address derivation: seedrandom output (raw 32-byte privkey) → secp256k1 address ----
        // Same call chain as EthWallet: Credentials.create(<seedrandom hex>) → .getAddress().
        String ethPriv = "0x1f2e3d4c5b6a79881f2e3d4c5b6a79881f2e3d4c5b6a79881f2e3d4c5b6a7988";
        org.web3j.crypto.Credentials creds = org.web3j.crypto.Credentials.create(ethPriv);
        JSONObject ej = new JSONObject();
        ej.put("privKey", ethPriv);
        ej.put("address", creds.getAddress());
        // Signed legacy EIP-155 tx vector — the exact EthTx.java signing path (RawTransaction +
        // TransactionEncoder.signMessage(chainId=1)). The JS side must produce these IDENTICAL bytes.
        org.web3j.crypto.RawTransaction raw = org.web3j.crypto.RawTransaction.createTransaction(
                java.math.BigInteger.valueOf(7),                      // nonce
                new java.math.BigInteger("2000000000"),               // gasPrice 2 gwei
                java.math.BigInteger.valueOf(100000),                 // gasLimit
                "0xdac17f958d2ee523a2206206994597c13d831ec7",         // to = USDT
                java.math.BigInteger.ZERO,                            // value
                "0x095ea7b300000000000000000000000067376c3bf3b5a336b14398920cfbc292013718ea"
                + "0000000000000000000000000000000000000000000000000000000000000000");  // approve(htlc, 0)
        byte[] signed = org.web3j.crypto.TransactionEncoder.signMessage(raw, 1L, creds);
        ej.put("tx", new JSONObject()
                .put("nonce", 7).put("gasPriceWei", "2000000000").put("gasLimit", 100000)
                .put("to", "0xdac17f958d2ee523a2206206994597c13d831ec7").put("value", "0")
                .put("data", "0x095ea7b300000000000000000000000067376c3bf3b5a336b14398920cfbc292013718ea"
                        + "0000000000000000000000000000000000000000000000000000000000000000")
                .put("chainId", 1)
                .put("signedHex", "0x" + Hex.to(signed)));
        out.put("eth", ej);

        // ---- ETH ABI vectors (web3j FunctionEncoder) — the JS abi.js must byte-match these ----
        String htlc = "0x67376c3bf3b5a336b14398920cfbc292013718ea";
        String token = "0xdac17f958d2ee523a2206206994597c13d831ec7";
        String senderMinima = "0x" + repeat("11", 32);
        String receiverEth = creds.getAddress();
        String hashlock = "0x" + repeat("22", 32);
        String preimage = "0x" + repeat("33", 32);
        java.math.BigInteger timelock = java.math.BigInteger.valueOf(1800000000L);
        java.math.BigInteger amount = new java.math.BigInteger("5000000");                 // 5 USDT @ 6dp
        java.math.BigInteger requestAmount = new java.math.BigInteger("4950495000000000000"); // 4.950495 @ 18dp

        org.web3j.abi.datatypes.Function fApprove = new org.web3j.abi.datatypes.Function("approve",
                java.util.Arrays.asList(new org.web3j.abi.datatypes.Address(htlc),
                        new org.web3j.abi.datatypes.generated.Uint256(java.math.BigInteger.ZERO)),
                java.util.Collections.emptyList());
        org.web3j.abi.datatypes.Function fNew = new org.web3j.abi.datatypes.Function("newContract",
                java.util.Arrays.asList(
                        new org.web3j.abi.datatypes.generated.Bytes32(b32(senderMinima)),
                        new org.web3j.abi.datatypes.Address(receiverEth),
                        new org.web3j.abi.datatypes.generated.Bytes32(b32(hashlock)),
                        new org.web3j.abi.datatypes.generated.Uint256(timelock),
                        new org.web3j.abi.datatypes.Address(token),
                        new org.web3j.abi.datatypes.generated.Uint256(amount),
                        new org.web3j.abi.datatypes.generated.Uint256(requestAmount),
                        new org.web3j.abi.datatypes.Bool(false)),
                java.util.Collections.emptyList());
        org.web3j.abi.datatypes.Function fWithdraw = new org.web3j.abi.datatypes.Function("withdraw",
                java.util.Arrays.asList(new org.web3j.abi.datatypes.generated.Bytes32(b32(hashlock)),
                        new org.web3j.abi.datatypes.generated.Bytes32(b32(preimage))),
                java.util.Collections.emptyList());

        JSONObject abi = new JSONObject();
        abi.put("htlc", htlc); abi.put("token", token); abi.put("senderMinima", senderMinima);
        abi.put("receiverEth", receiverEth); abi.put("hashlock", hashlock); abi.put("preimage", preimage);
        abi.put("timelock", timelock.toString()); abi.put("amount", amount.toString()); abi.put("requestAmount", requestAmount.toString());
        abi.put("approveData", org.web3j.abi.FunctionEncoder.encode(fApprove));
        abi.put("newContractData", org.web3j.abi.FunctionEncoder.encode(fNew));
        abi.put("withdrawData", org.web3j.abi.FunctionEncoder.encode(fWithdraw));
        abi.put("contractId", "0x" + Hex.to(java.security.MessageDigest.getInstance("SHA-256").digest(b32(hashlock))));
        out.put("abi", abi);

        // ---- write the fixture ----
        File f = new File("build/interop_vectors.json");
        f.getParentFile().mkdirs();
        try (FileWriter w = new FileWriter(f)) { w.write(out.toString(2)); }
        assertTrue(f.length() > 0);
        System.out.println("interop vectors → " + f.getAbsolutePath());
    }
}
