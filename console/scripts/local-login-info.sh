#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
echo "URL:      http://localhost:3000/login"
echo "Usuario:  admin@spc.local"
echo "Password: $(cat secrets/local_admin_password)"
echo "TOTP:     $(./scripts/local-totp.sh)   (o ejecuta scripts/local-totp.sh)"
echo
echo "Alta en app TOTP:"
grep 'otpauth.*admin%40spc.local' secrets/local_seed_output.txt | sed 's/^  //'
