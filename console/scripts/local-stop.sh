#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."

if [ -f secrets/dev.pid ]; then
  kill -- -"$(cat secrets/dev.pid)" 2>/dev/null || true
  rm -f secrets/dev.pid
fi
pkill -f "next dev -p 3000" 2>/dev/null || true
pkill -f "tsx watch server/index.ts" 2>/dev/null || true
podman stop spc-db >/dev/null 2>&1 || true
echo "Panel y base de datos detenidos."
