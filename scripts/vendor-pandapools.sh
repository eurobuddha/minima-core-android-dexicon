#!/usr/bin/env bash
# Re-vendor the PandaPools Android ENGINE and its pool screens VERBATIM from ../pandapools into app/src/main/java, under
# PandaPools' own package (com.eurobuddha.pandapools.*). Each Java copy gets ONE header line naming its source and the
# PandaPools version; the rest of the file is PandaPools' bytes. Layouts, the test resource and app/libs/minima.jar are
# copied byte-for-byte (an XML/JSON file has no room for a header). `--check` fails on any drift (run it before every
# commit that touches app/src).
#
# NOT vendored — Dexicon-owned shims in com.eurobuddha.pandapools, because Dexicon has its own transport and host:
#   NodeApi.java               adapter onto com.eurobuddha.dexicon.NodeApi, keeping PandaPools' write-safety journal
#   MainActivity.java          the PandaPools host surface (node(), pools(), pickers …) that Dexicon's MainActivity extends
#   PoolKeepAliveService.java  the stranding notification's Re-publish action, forwarded to Dexicon's MakerService tick
#   Handover.java              PandaPools' create/swap gate, answered by Dexicon's TradingGate + PandaPools' custody provider (DexiconPools)
#   Design.java  BuildConfig.java  R.java   PandaPools' tokens / build constants / resource ids, mapped onto Dexicon's
#   DexiconPools.java          the one public door Dexicon's own code (com.eurobuddha.dexicon.*) uses into this engine
#   tests DexiconPoolsKeepAliveTest / DexiconNodeApiShimTest / OwnerCoinGuardTest  Dexicon's own tests of those shims
# --check also FAILS on any other file in that package (main or test) that carries no vendored header.
# NOT vendored at all: BootReceiver, HeartbeatReceiver, PoolWatchWorker (MakerService already keeps the process alive),
# MainPager, NodeTransport(+Service), HandoverActivity, WalletView, Identicon (PandaPools' own app shell / wallet tab).
set -euo pipefail
cd "$(dirname "$0")/.."
ROOT=../pandapools/app/src
SRC=$ROOT/main/java
DST=app/src/main/java
P=com/eurobuddha/pandapools
FILES=""
for c in ActivityLog ActivityTimeline ActivityView ArchiveNode BackupCheck BackupState BackupVault BaseView BlockOwnerRecovery Cmd \
  CmdChain Coin CoinLock CollectConfirmation CollectionEvidence CollectSweeper CombinedBackupUi ExitAuthority ExitStore ExitTicket \
  ExportWriter FundingCoins GlobalFeed HistoryDb HistoryEntry HistorySync IconResolver ImageLoader KeyUses LpStore MarketPrice \
  MegaArchive MegaArchiveProofs MyLpView NetFetch NodeBackupCapture OwnerKeyRecovery OwnPoolStore PandaAddrBook PendingCollect Pool \
  PoolActivity PoolBackupBundle PoolBook PoolCalc PoolCalcDialog PoolCovenant PoolHistorySync PoolManager PoolRefresher PoolRepository \
  PoolRouter PoolStatement PoolsView PoolTxn PriceOracle ReAnnouncer ReceiptRecovery Recovery ReserveRecovery RestoreExit SerialQueue \
  StrandingWatch SwapView TokenBalance TokenMeta TrackHygiene TxClassifier TxPost Ui Util VirtualCurve WalletTools WebValidate; do
  FILES="$FILES $P/$c.java"
done
# The Maxima wire codecs PandaPools' ReceiptRecovery reuses (its own copy, under their own package).
for c in msg/Magic msg/TxHeader codec/MiniData codec/MiniByte codec/Hex codec/Reads codec/MiniNumber codec/Streamable; do
  FILES="$FILES com/eurobuddha/maxima/core/$c.java"
done
# PandaPools' own JVM tests for those classes, ported with them (same header rule). HandoverTest / NodeTransportTest test
# classes that are not vendored and are left out.
TESTS=""
for c in ActivityTimelineTest ArchiveHardeningTest BackupCheckTest BackupStateTest BlockOwnerRecoveryTest BoundedRecoveryTest CmdTest \
  CoinLockTest CollectConfirmationTest CollectSweeperTest ExistingWalletRecoveryTest ExitAuthorityTest ExitTicketTest FundingCoinsTest \
  InputAvailabilityTest KeyUsesTest NetFetchTest OwnerKeyReasonTest OwnPoolRecordTest PoolActivityTest PoolBackupBundleTest \
  PoolBookStateTest PoolCalcTest PoolCovenantTest PoolHistorySyncTest PoolRouterTest PoolsReviewedWriteTest PoolStatementTest PoolTest \
  ReceiptRecoveryTest ReserveRecoveryTest RestoreExitRefusalTest SerialQueueTest StrandingWatchTest TestJson TokenBalanceTest \
  TokenMetadataTest TrackHygieneTest TransactionSafetyTest TxClassifierTest UtilTest VirtualCurveTest; do
  TESTS="$TESTS $P/$c.java"
done
# Byte-for-byte (no header): the four pool screens' layouts, the receipt fixture, and the pinned Minima codecs jar.
RAW="main/res/layout/view_mylp.xml:app/src/main/res/layout/view_mylp.xml main/res/layout/view_swap.xml:app/src/main/res/layout/view_swap.xml
main/res/layout/view_pools.xml:app/src/main/res/layout/view_pools.xml main/res/layout/view_activity.xml:app/src/main/res/layout/view_activity.xml
test/resources/receipt-consolidation-header.json:app/src/test/resources/receipt-consolidation-header.json
../libs/minima.jar:app/libs/minima.jar"
VER=$(grep -E 'versionName' ../pandapools/app/build.gradle | head -1 | grep -oE '"[^"]+"' | tr -d '"')
head_for() { echo "// VENDORED VERBATIM from apks/pandapools $VER ${1#../pandapools/}/$2 by scripts/vendor-pandapools.sh — do not edit here; re-vendor."; }
fail=0
MODE="${1:-}"
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
for f in $FILES; do run "$SRC" "$DST" "$f"; done
for f in $TESTS; do run "$ROOT/test/java" app/src/test/java "$f"; done
for pair in $RAW; do
  from="$ROOT/${pair%%:*}"; to="${pair#*:}"
  if [ "$MODE" = "--check" ]; then
    [ -f "$to" ] || { echo "MISSING: $to"; fail=1; continue; }
    cmp -s "$to" "$from" || { echo "DRIFT: $to vs $from"; fail=1; }
  else
    mkdir -p "$(dirname "$to")"; cp "$from" "$to"
  fi
done
if [ "$MODE" = "--check" ]; then
  # Pool recovery reads ONLY the unpruned MegaMMR on eurobuddha.com:9001; the pruned public ones must never be offered.
  pruned=$(grep -rlE "(spartacusrex\.com|megammr\.minima\.global|minimammr\.com):9001" app/src/main || true)
  [ -z "$pruned" ] || { echo "PRUNED MEGAMMR named in: $pruned"; fail=1; }
  grep -q 'HOST = "eurobuddha.com"' "$DST/$P/MegaArchiveProofs.java" || { echo "MegaMMR host is not eurobuddha.com"; fail=1; }
  # Every other file in the PandaPools package is vendored (header) or one of the Dexicon-owned shims / tests named here.
  OWNED=" NodeApi MainActivity PoolKeepAliveService Handover Design BuildConfig R DexiconPools DexiconPoolsKeepAliveTest DexiconNodeApiShimTest OwnerCoinGuardTest "
  for f in "$DST/$P"/*.java app/src/test/java/$P/*.java; do
    [ -f "$f" ] || continue
    head -1 "$f" | grep -q '^// VENDORED VERBATIM from apks/pandapools ' && continue
    n=$(basename "$f" .java)
    case "$OWNED" in *" $n "*) ;; *) echo "UNLISTED (no vendored header, not a Dexicon shim): $f"; fail=1;; esac
  done
  [ $fail = 0 ] && echo "PANDAPOOLS VENDOR PASS — matches apks/pandapools $VER" || { echo "PANDAPOOLS VENDOR FAIL — run scripts/vendor-pandapools.sh"; exit 1; }
else
  echo "vendored apks/pandapools $VER"
fi
