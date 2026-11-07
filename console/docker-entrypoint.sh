#!/bin/sh
set -eu

role="${ROLE:-all}"

if [ -n "${DATABASE_URL_FILE:-}" ]; then
  export DATABASE_URL="$(cat "$DATABASE_URL_FILE")"
fi

if [ "${MIGRATE:-1}" = "1" ]; then
  npx prisma migrate deploy
fi

if [ "${SEED:-0}" = "1" ]; then
  node dist/prisma/seed.js
fi

if [ "$role" = "seed" ]; then
  exit 0
fi

shutdown() {
  kill -TERM ${api:-} ${web:-} ${worker:-} 2>/dev/null || true
  wait || true
}
trap shutdown INT TERM

if [ "$role" = "api" ] || [ "$role" = "all" ]; then
  node dist/server/index.js &
  api=$!
fi
if [ "$role" = "worker" ]; then
  node dist/server/worker.js &
  worker=$!
fi
if [ "$role" = "web" ] || [ "$role" = "all" ]; then
  HOSTNAME=0.0.0.0 PORT=3000 node .next/standalone/server.js &
  web=$!
fi

wait
