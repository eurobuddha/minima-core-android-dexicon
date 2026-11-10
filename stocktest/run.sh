#!/bin/sh
# Testnet proof for the Trading Account on UNMODIFIED stock Minima Core (jar 1.1.2.31, upstream c2a54a2e46b2c3a4fd541b80e7d7f5e7afe7d4da).
# Private -test chain, no network. Every node call runs as 0xFF = a non-admin IPC companion, exactly as on a phone.
# Usage: stocktest/run.sh   (from apks/dexicon). Takes ~20 min: the test chain mines slowly and step 5 waits 45 blocks.
set -e
cd "$(dirname "$0")/.."
CORE=../../core/minima-core
W=$(mktemp -d)
if [ -n "$JAR" ]; then cp "$JAR" "$W/minima-stock.jar"; else git -C "$CORE" cat-file -p c2a54a2e46b2c3a4fd541b80e7d7f5e7afe7d4da:jar/minima.jar > "$W/minima-stock.jar"; fi
echo "stock jar sha256: $(shasum -a 256 "$W/minima-stock.jar" | cut -d' ' -f1)"
JSON=$(find ~/.gradle/caches -name 'json-20240303.jar' | head -1)
JH="${JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}"
A=app/src/main/java/com/eurobuddha/account
"$JH/bin/javac" -nowarn -cp "$W/minima-stock.jar:$JSON" -d "$W/cls" $A/KeyUses.java $A/AccountKeys.java $A/VirtualWallet.java stocktest/H1.java
"$JH/bin/java" -cp "$W/minima-stock.jar:$JSON:$W/cls${EXTRA_CP:+:$EXTRA_CP}" H1 "$W/node" | grep -v '^Minima @'
