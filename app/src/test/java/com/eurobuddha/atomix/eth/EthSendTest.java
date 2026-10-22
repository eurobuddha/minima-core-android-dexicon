// VENDORED VERBATIM from apks/atomix 0.1.83 app/src/test/java/com/eurobuddha/atomix/eth/EthSendTest.java by scripts/vendor-atomix.sh — do not edit here; re-vendor.
package com.eurobuddha.atomix.eth;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.math.BigInteger;

/**
 * STEP: the manual wallet Send (parity twin of the MDS build's lib/wallet.js — user decision 2026-07-19).
 * Fund-relevant: validation runs on RAW balances, the gas reserve mirrors EthTx's +20% headroom, and the
 * ERC20 calldata must be an exact transfer(address,uint256) encoding. Mirrors the MDS wallet.test.js cases
 * so BOTH apps enforce identical refusal rules.
 */
public class EthSendTest {

    private static final String TO = "0x2222222222222222222222222222222222222222";
    private static final BigInteger GP = BigInteger.valueOf(1_000_000_000L);          // 1 gwei
    private static final BigInteger ETH_1 = new BigInteger("1000000000000000000");    // 1 ETH
    private static final BigInteger USDT_50 = BigInteger.valueOf(50_000_000L);        // 50 USDT (6dp)

    @Test public void addressValidation() {
        assertTrue(EthSend.isEthAddr(TO));
        assertFalse(EthSend.isEthAddr("0x1234"));
        assertFalse(EthSend.isEthAddr("0x" + "zz".repeat(20)));
        assertFalse(EthSend.isEthAddr(null));
    }

    private static final BigInteger NO_BASE = BigInteger.ZERO;   // baseFee 0 ⇒ just the +12.5% headroom (pre-floor behaviour)

    @Test public void gasMathMirrorsTheSerializerHeadroom() {
        // 0.1.40: headroom is +12.5% (was +20%); the old 2×baseFee floor is gone (it over-reserved ~4× and
        // starved new node-derived wallets — proven live). NO_BASE ⇒ just the +12.5% headroom.
        assertEquals(BigInteger.valueOf(21000L * 1_125_000_000L), EthSend.gasReserveWei(GP, NO_BASE, EthSend.GAS_ETH));
        assertEquals(new BigInteger("100000000000000000").subtract(BigInteger.valueOf(21000L * 1_125_000_000L)),
                EthSend.maxEthSendWei(new BigInteger("100000000000000000"), GP, NO_BASE));
        assertEquals(BigInteger.ZERO, EthSend.maxEthSendWei(BigInteger.valueOf(1000), GP, NO_BASE));
    }

    @Test public void effectiveGasPriceFloorsAtBaseFeePlusTip() {
        // 0.1.40: floor is baseFee+12.5% + a 0.2 gwei tip (was 2×baseFee) — enough to confirm without the ~2×
        // over-pricing that, with a fixed 500k limit, forced a wallet to pre-hold ~0.0005 ETH for a ~0.00005 op.
        BigInteger baseFee = BigInteger.valueOf(5_000_000_000L);                       // 5 gwei
        BigInteger floor   = BigInteger.valueOf(5_625_000_000L + 200_000_000L);        // 5.625 + 0.2 tip = 5.825 gwei
        assertEquals("gp*1.125 (1.125 gwei) is below the base+tip floor → floored",
                floor, EthSend.effectiveGasPriceWei(GP, baseFee));
        assertEquals("no base fee → just +12.5% headroom",
                BigInteger.valueOf(1_125_000_000L), EthSend.effectiveGasPriceWei(GP, NO_BASE));
        // the floor is NO LONGER 2×baseFee (would have been 10 gwei) — the whole point of the fix
        assertTrue("must be well below the old 2×baseFee floor",
                EthSend.effectiveGasPriceWei(GP, baseFee).compareTo(BigInteger.valueOf(10_000_000_000L)) < 0);
        assertEquals(EthSend.GAS_ETH.multiply(floor), EthSend.gasReserveWei(GP, baseFee, EthSend.GAS_ETH));
    }

    @Test public void checkSendRefusalPaths() {
        assertNull(EthSend.checkSend(true, TO, "0.5", ETH_1, USDT_50, 6, GP, NO_BASE));
        assertNotNull(EthSend.checkSend(true, "nope", "0.5", ETH_1, USDT_50, 6, GP, NO_BASE));      // bad address
        assertNotNull(EthSend.checkSend(true, TO, "1e2", ETH_1, USDT_50, 6, GP, NO_BASE));          // not plain decimal
        assertNotNull(EthSend.checkSend(true, TO, "0", ETH_1, USDT_50, 6, GP, NO_BASE));            // zero
        assertNotNull(EthSend.checkSend(true, TO, "1", ETH_1, USDT_50, 6, GP, NO_BASE));            // amount+gas > balance
        assertNull(EthSend.checkSend(false, TO, "25", ETH_1, USDT_50, 6, GP, NO_BASE));
        assertNotNull(EthSend.checkSend(false, TO, "50.000001", ETH_1, USDT_50, 6, GP, NO_BASE));   // over USDT balance
        assertNotNull(EthSend.checkSend(false, TO, "25", BigInteger.ZERO, USDT_50, 6, GP, NO_BASE)); // no gas ETH
    }

    @Test public void parseUnitsTruncatesNeverRoundsUp() {
        assertEquals(new BigInteger("250000000000000000"), EthSend.parseUnits("0.25", 18));
        assertEquals(BigInteger.valueOf(12_500_000), EthSend.parseUnits("12.5", 6));
        assertEquals(BigInteger.valueOf(1_999_999), EthSend.parseUnits("1.9999999", 6));   // 7th dp truncated
    }

    @Test public void transferCalldataIsExact() {
        String data = EthSend.buildTransferData(TO, BigInteger.valueOf(12_500_000));
        assertTrue("selector transfer(address,uint256)", data.startsWith("0xa9059cbb"));
        assertTrue("recipient encoded", data.toLowerCase().contains(TO.substring(2).toLowerCase()));
        assertTrue("12.5 USDT at 6dp encoded", data.contains(Long.toHexString(12_500_000)));
        assertEquals("2 static words after the selector", 2 + 8 + 64 * 2, data.length());
    }
}
