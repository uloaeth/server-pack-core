#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
EMAIL="${1:-admin@spc.local}"
SECRET="$(grep "totp ${EMAIL} " secrets/local_seed_output.txt | awk '{print $NF}')"
[ -n "$SECRET" ] || { echo "Cuenta desconocida: $EMAIL" >&2; exit 1; }
node -e "console.log(require('otplib').authenticator.generate(process.argv[1]))" "$SECRET"
