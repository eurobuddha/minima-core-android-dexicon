// VENDORED VERBATIM from apks/atomix 0.1.82 app/src/main/java/com/eurobuddha/atomix/eth/EthSend.java by scripts/vendor-atomix.sh — do not edit here; re-vendor.
package com.eurobuddha.atomix.eth;

import org.web3j.abi.FunctionEncoder;
import org.web3j.abi.datatypes.Address;
import org.web3j.abi.datatypes.Bool;
import org.web3j.abi.datatypes.Function;
import org.web3j.abi.datatypes.generated.Uint256;
import org.web3j.crypto.Credentials;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.Collections;

/**
 * Manual wallet send (ETH value transfer + ERC20 USDT transfer) — the parity twin of the MDS build's
 * lib/wallet.js (user decision 2026-07-19: Send lives in BOTH apps). Every check runs on RAW integer
 * balances — the UI's rounded display strings must never gate a fund movement. Broadcasts go through
 * {@link EthTx#send} so a manual send shares the SAME per-address nonce serializer as the swap engine's
 * HTLC operations and can never nonce-clash a settlement.
 */
public final class EthSend {

    /** Fixed intrinsic gas of a plain value transfer. */
    public static final BigInteger GAS_ETH = BigInteger.valueOf(21_000);
    /** Same safety limit the engine uses for its ERC20 approve; unused gas refunds. */
    public static final BigInteger GAS_ERC20 = BigInteger.valueOf(100_000);

    private EthSend() {}

    public static boolean isEthAddr(String a) {
        return a != null && a.trim().matches("0x[0-9a-fA-F]{40}");
    }

    public static boolean validDec(String s) {
        return s != null && s.trim().matches("[0-9]+(\\.[0-9]+)?");
    }

    /** Decimal string → raw integer at {@code decimals}, truncating extra precision (never rounds UP a spend). */
    public static BigInteger parseUnits(String s, int decimals) {
        return new BigDecimal(s.trim()).movePointRight(decimals).toBigInteger();
    }

    /** A modest priority tip added to the base-fee floor so a tx still confirms without over-pricing. */
    public static final BigInteger PRIORITY_TIP_WEI = BigInteger.valueOf(200_000_000L);   // 0.2 gwei

    /** MA-6: the gas price a send will ACTUALLY broadcast at — a small headroom over eth_gasPrice, floored at
     *  base fee + a 0.2 gwei tip. Pure and network-free, so this ONE formula backs both the UI's reserve/validation
     *  and {@link EthTx#send}'s build; passing the current baseFee (rpc.baseFeePerGasOrZero()) stops the UI
     *  under-reserving when eth_gasPrice sits below the floor. baseFee 0 ⇒ just the headroom (the pre-floor case).
     *  The old floor was 2× base fee; combined with a fixed 500k gas limit it forced a near-empty node-derived
     *  wallet to pre-hold ~0.0005 ETH for a ~0.00005 ETH op, so new users' swaps silently mutual-refunded. The
     *  floor is now base+12.5%+tip (~realistic), which with the right-sized EthHtlc limits cuts the reserve ~4×. */
    public static BigInteger effectiveGasPriceWei(BigInteger gasPriceWei, BigInteger baseFeeWei) {
        BigInteger gp = gasPriceWei.multiply(BigInteger.valueOf(9)).divide(BigInteger.valueOf(8));   // +12.5% headroom
        if (baseFeeWei != null && baseFeeWei.signum() > 0) {
            BigInteger floor = baseFeeWei.multiply(BigInteger.valueOf(9)).divide(BigInteger.valueOf(8))
                    .add(PRIORITY_TIP_WEI);                                                            // base+12.5% + tip
            if (gp.compareTo(floor) < 0) gp = floor;
        }
        return gp;
    }

    /** The wei to RESERVE for one send's gas, at the SAME effective price EthTx will broadcast at. */
    public static BigInteger gasReserveWei(BigInteger gasPriceWei, BigInteger baseFeeWei, BigInteger gasLimit) {
        return gasLimit.multiply(effectiveGasPriceWei(gasPriceWei, baseFeeWei));
    }

    /** Max spendable ETH after reserving gas for the send itself (floors at zero). */
    public static BigInteger maxEthSendWei(BigInteger balanceWei, BigInteger gasPriceWei, BigInteger baseFeeWei) {
        BigInteger m = balanceWei.subtract(gasReserveWei(gasPriceWei, baseFeeWei, GAS_ETH));
        return m.signum() > 0 ? m : BigInteger.ZERO;
    }

    /**
     * Validate a send BEFORE building it. {@code eth} selects ETH vs USDT; balances are RAW (wei / 6dp units).
     * {@code baseFeeWei} lets the reserve match EthTx's broadcast price exactly (MA-6). @return null when OK.
     */
    public static String checkSend(boolean eth, String to, String amountStr, BigInteger ethWei,
                                   BigInteger usdtRaw, int usdtDecimals, BigInteger gasPriceWei, BigInteger baseFeeWei) {
        if (!isEthAddr(to)) return "Enter a valid Ethereum address (0x + 40 hex characters).";
        if (!validDec(amountStr)) return "Enter a plain decimal amount.";
        if (eth) {
            BigInteger wei = parseUnits(amountStr, 18);
            if (wei.signum() <= 0) return "Enter an amount above zero.";
            if (wei.add(gasReserveWei(gasPriceWei, baseFeeWei, GAS_ETH)).compareTo(ethWei) > 0)
                return "Not enough ETH for that amount plus network gas — try Max.";
            return null;
        }
        BigInteger raw = parseUnits(amountStr, usdtDecimals);
        if (raw.signum() <= 0) return "Enter an amount above zero.";
        if (raw.compareTo(usdtRaw) > 0) return "That is more USDT than this wallet holds.";
        if (gasReserveWei(gasPriceWei, baseFeeWei, GAS_ERC20).compareTo(ethWei) > 0)
            return "Sending USDT needs a little ETH for gas — fund the wallet with ETH first.";
        return null;
    }

    /** ERC20 transfer(to, amount) calldata — split out so the encoding is unit-testable without a network. */
    public static String buildTransferData(String to, BigInteger raw) {
        Function fn = new Function("transfer",
                Arrays.asList(new Address(to.trim()), new Uint256(raw)),
                Collections.singletonList(new org.web3j.abi.TypeReference<Bool>() {}));
        return FunctionEncoder.encode(fn);
    }

    /** Broadcast a plain ETH transfer. Returns the tx hash. Call off the main thread. */
    public static String sendEth(EthRpc rpc, Credentials creds, long chainId, String to, BigInteger wei) throws Exception {
        return EthTx.send(rpc, creds, chainId, to.trim(), "", wei, GAS_ETH);
    }

    /** Broadcast an ERC20 transfer. Returns the tx hash. Call off the main thread. */
    public static String sendErc20(EthRpc rpc, Credentials creds, long chainId, String token, String to, BigInteger raw) throws Exception {
        return EthTx.send(rpc, creds, chainId, token, buildTransferData(to, raw), BigInteger.ZERO, GAS_ERC20);
    }
}
