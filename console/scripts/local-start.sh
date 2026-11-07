#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."

if ! podman container exists spc-db; then
  echo "No existe el contenedor spc-db. Revisa el README local." >&2
  exit 1
fi
if [ "$(podman inspect -f '{{.State.Running}}' spc-db 2>/dev/null || echo false)" != "true" ]; then
  podman start spc-db >/dev/null
fi
for _ in $(seq 1 30); do
  podman exec spc-db pg_isready -U spc -d spc >/dev/null 2>&1 && break
  sleep 1
done

( npm run dev > secrets/dev.log 2>&1 ) &
echo $! > secrets/dev.pid
for _ in $(seq 1 60); do
  curl -fsS http://127.0.0.1:4000/api/console/health >/dev/null 2>&1 && break
  sleep 1
done
echo "Panel: http://localhost:3000/login"
echo "Log:   console/secrets/dev.log"
