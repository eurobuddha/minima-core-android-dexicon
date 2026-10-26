#!/usr/bin/env bash
# Re-vendor the AtomiX Android TAKER engine (the DOORS: USDT <-> MxUSD / MINIMA via HTLCs on both chains) VERBATIM from
# ../atomix into app/src/main/java, under AtomiX's own packages (com.eurobuddha.atomix.*, com.eurobuddha.comms.*,
# com.eurobuddha.minimaapi.ethereum). Each copy gets ONE header line naming its source and the AtomiX version; the rest
# of the file is AtomiX's bytes. `--check` fails on any drift (run it before every commit that touches app/src).
#
# NOT vendored — Dexicon-owned shims, because Dexicon has its own transport and host:
#   com/eurobuddha/comms/NodeApi.java      adapter onto com.eurobuddha.dexicon.NodeApi (one IPC queue, one pairing)
#   com/eurobuddha/atomix/MainActivity.java  host flag only (SWEEP_ACTIVE) that SwapEngine reads — not an Activity
# The Ethereum RPC endpoint list therefore lives ONLY in the vendored EthRpc.java / EthNet.java — AtomiX's bytes —
# so Dexicon is never a fifth divergent copy (see CLAUDE.md).
set -euo pipefail
cd "$(dirname "$0")/.."
SRC=../atomix/app/src/main/java
DST=app/src/main/java
FILES="com/eurobuddha/atomix/TradingContext.java com/eurobuddha/atomix/SwapLog.java com/eurobuddha/atomix/IdentityWatch.java
com/eurobuddha/atomix/swap/MinimaHtlc.java com/eurobuddha/atomix/swap/Order.java com/eurobuddha/atomix/swap/SwapDb.java
com/eurobuddha/atomix/swap/SwapEngine.java com/eurobuddha/atomix/swap/SwapOrderBook.java com/eurobuddha/atomix/swap/SwapTake.java
com/eurobuddha/atomix/swap/PublishGate.java com/eurobuddha/atomix/swap/MarketCollector.java com/eurobuddha/atomix/swap/OtcDb.java
com/eurobuddha/atomix/swap/OtcController.java com/eurobuddha/atomix/swap/OtcOffer.java com/eurobuddha/atomix/swap/OtcMessage.java
com/eurobuddha/atomix/eth/EthHtlc.java com/eurobuddha/atomix/eth/EthNet.java com/eurobuddha/atomix/eth/EthRpc.java
com/eurobuddha/atomix/eth/EthSend.java com/eurobuddha/atomix/eth/EthTx.java com/eurobuddha/atomix/eth/EthWallet.java
com/eurobuddha/atomix/eth/PendingTokenDebits.java com/eurobuddha/minimaapi/ethereum/EthereumTransactions.java
com/eurobuddha/comms/CommsIdentity.java com/eurobuddha/comms/CommsTransport.java com/eurobuddha/comms/CommsScanner.java
com/eurobuddha/comms/CryptoProvider.java com/eurobuddha/comms/LocalEcCryptoProvider.java com/eurobuddha/comms/Hex.java
com/eurobuddha/comms/Hkdf.java com/eurobuddha/comms/Sodium.java com/eurobuddha/comms/Opened.java com/eurobuddha/comms/WriteSafety.java
com/eurobuddha/comms/SignGate.java"
# AtomiX's own JVM tests for those classes, ported with them (same header rule). Tests of classes NOT vendored
# (PriceOracle, SwapVisibility, OtcBook, the AtomiX NodeApi/publish queue, the wallet journal UI) are left out.
TESTS="com/eurobuddha/atomix/AtomixKeyBoundaryTest.java com/eurobuddha/atomix/eth/EthAuthorizationTest.java com/eurobuddha/atomix/eth/EthContractReplyTest.java com/eurobuddha/atomix/eth/EthEncodingTest.java com/eurobuddha/atomix/eth/EthEventReplyTest.java com/eurobuddha/atomix/eth/EthNonceTest.java com/eurobuddha/atomix/eth/EthSendTest.java com/eurobuddha/atomix/eth/JournalFixture.java com/eurobuddha/atomix/eth/PendingTokenDebitsTest.java com/eurobuddha/atomix/eth/ReviewedEthSendTest.java com/eurobuddha/atomix/IdentityWatchTest.java com/eurobuddha/atomix/swap/AmountGrainTest.java com/eurobuddha/atomix/swap/BidBackingTest.java com/eurobuddha/atomix/swap/ClaimDeepDiscoveryTest.java com/eurobuddha/atomix/swap/CoinCountGuardTest.java com/eurobuddha/atomix/swap/ConsolidateGateTest.java com/eurobuddha/atomix/swap/CpLockDeclineTest.java com/eurobuddha/atomix/swap/EthereumCounterRetryTest.java com/eurobuddha/atomix/swap/EthResponderContextTest.java com/eurobuddha/atomix/swap/GasPromptTest.java com/eurobuddha/atomix/swap/GateTest.java com/eurobuddha/atomix/swap/HtlcTxnConstructionTest.java com/eurobuddha/atomix/swap/IdentityBlockTest.java com/eurobuddha/atomix/swap/IdentityGuardTest.java com/eurobuddha/atomix/swap/InspectionStatusTest.java com/eurobuddha/atomix/swap/LadderCoinsBalanceTest.java com/eurobuddha/atomix/swap/MinimaReceiptTest.java com/eurobuddha/atomix/swap/MismatchPoisonTest.java com/eurobuddha/atomix/swap/OrderBackingTest.java com/eurobuddha/atomix/swap/OrderBookTest.java com/eurobuddha/atomix/swap/OrderBookTombstoneTest.java com/eurobuddha/atomix/swap/OrderMarketContextTest.java com/eurobuddha/atomix/swap/OrderPublicationContextTest.java com/eurobuddha/atomix/swap/OtcAmountsTest.java com/eurobuddha/atomix/swap/OtcConsentTest.java com/eurobuddha/atomix/swap/OtcCurrencyGuardTest.java com/eurobuddha/atomix/swap/OtcFirstLegTest.java com/eurobuddha/atomix/swap/RefundRetryTest.java com/eurobuddha/atomix/swap/ResponderContextTest.java com/eurobuddha/atomix/swap/RoutingOrderTest.java com/eurobuddha/atomix/swap/SwapOrderBookTest.java com/eurobuddha/atomix/swap/SwapPersistenceTest.java com/eurobuddha/atomix/swap/SwapStatusVisibilityTest.java com/eurobuddha/atomix/swap/TimelockSafetyTest.java com/eurobuddha/atomix/swap/TombstoneTest.java com/eurobuddha/atomix/swap/WalletKeeperTest.java com/eurobuddha/atomix/TradingContextTest.java com/eurobuddha/comms/CommsScannerTest.java com/eurobuddha/comms/CommsSwapFlowTest.java com/eurobuddha/comms/HexTest.java com/eurobuddha/comms/HkdfTest.java com/eurobuddha/comms/InteropVectorsTest.java com/eurobuddha/comms/SignGateTest.java com/eurobuddha/comms/WriteAcknowledgementTest.java com/eurobuddha/comms/WriteSafetyTest.java com/eurobuddha/minimaapi/ethereum/EthereumFundingSnapshotTest.java com/eurobuddha/minimaapi/ethereum/EthereumTransactionsTest.java"
VER=$(grep -E 'versionName' ../atomix/app/build.gradle | head -1 | grep -oE '"[^"]+"' | tr -d '"')
head_for() { echo "// VENDORED VERBATIM from apks/atomix $VER ${1#../atomix/}/$2 by scripts/vendor-atomix.sh — do not edit here; re-vendor."; }
fail=0
run() {  # $1 = source root, $2 = destination root, $3 = file
  local f="$3"
  if [ "$MODE" = "--check" ]; then
    [ -f "$2/$f" ] || { echo "MISSING: $f"; fail=1; return; }
    tail -n +2 "$2/$f" | cmp -s - "$1/$f" || { echo "DRIFT: $2/$f vs $1/$f"; fail=1; }
  else
    mkdir -p "$2/$(dirname "$f")"
    { head_for "$1" "$f"; cat "$1/$f"; } > "$2/$f"
  fi
}
MODE="${1:-}"
for f in $FILES; do run "$SRC" "$DST" "$f"; done
for f in $TESTS; do run ../atomix/app/src/test/java app/src/test/java "$f"; done
if [ "${1:-}" = "--check" ]; then
  # the ETH RPC endpoint list may exist only in the vendored AtomiX files
  extra=$(grep -rl "ethereum-rpc.publicnode.com" app/src/main/java | grep -vE 'com/eurobuddha/atomix/eth/(EthRpc|EthNet)\.java$' || true)
  [ -z "$extra" ] || { echo "RPC LIST COPY outside the vendored AtomiX eth/: $extra"; fail=1; }
  [ $fail = 0 ] && echo "ATOMIX VENDOR PASS — matches apks/atomix $VER" || { echo "ATOMIX VENDOR FAIL — run scripts/vendor-atomix.sh"; exit 1; }
else
  echo "vendored apks/atomix $VER"
fi
