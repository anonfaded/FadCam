#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${FADCAM_RELAY_BASE_URL:?FADCAM_RELAY_BASE_URL must be set}"
HOSTPORT="${BASE_URL#*://}"
HOSTPORT="${HOSTPORT%%/*}"
HOST="${HOSTPORT%%:*}"
PORT="${HOSTPORT##*:}"
PORT="${PORT:-443}"

printf 'Checking relay TLS endpoint: %s\n' "$BASE_URL"
printf 'Host: %s\nPort: %s\n' "$HOST" "$PORT"

# Deliberately use normal certificate verification. Never add --insecure here.
if ! openssl s_client -connect "${HOST}:${PORT}" -servername "$HOST" -verify_hostname "$HOST" -verify_return_error </dev/null 2>&1 | tee /tmp/fadcam-relay-tls.txt | grep -q 'Verify return code: 0 (ok)'; then
  echo 'ERROR: relay TLS certificate/chain/hostname verification failed.' >&2
  echo 'The authenticated relay test is intentionally blocked until the live endpoint presents a valid certificate.' >&2
  echo 'Do NOT bypass TLS verification to make CI green.' >&2
  exit 60
fi

echo 'Relay TLS certificate, chain, and hostname verification passed.'
