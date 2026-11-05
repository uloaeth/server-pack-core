#!/bin/sh
# Regenerate both VBS helpers (modern and legacy) and run them under Wine cscript.
# A VBScript compilation error fails the check; Wine's WMI stubs are tolerated.
set -eu
ROOT=$(cd "$(dirname "$0")/.." && pwd)
JAVA_HOME=${JAVA_HOME:-$ROOT/.jdk21}
OUT=$(mktemp -d)
trap 'rm -rf "$OUT"' EXIT
mkdir -p "$OUT/classes"
"$JAVA_HOME/bin/javac" -cp "$ROOT/agent/build/classes/java/main" -d "$OUT/classes" "$ROOT/tools/VbsDump.java"
"$JAVA_HOME/bin/java" -Dspc.watchdogSec=0 -cp "$ROOT/agent/build/classes/java/main:$OUT/classes" \
	com.serverpackcore.agent.VbsDump "$OUT/run"
status=0
for file in "$OUT"/run/*/*.vbs; do
	timeout 20 wine cscript //nologo "$file" > "$OUT/log" 2>&1 || true
	if grep -aqE 'Microsoft VBScript (compilation|runtime) error' "$OUT/log"; then
		if grep -aq 'Microsoft VBScript compilation error' "$OUT/log"; then
			echo "FAIL $file"
			grep -a 'Microsoft VBScript' "$OUT/log"
			status=1
		else
			echo "ok (Wine runtime stub) $file"
		fi
	else
		echo "ok $file"
	fi
done
exit "$status"
