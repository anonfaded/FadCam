#!/usr/bin/env bash
set -euo pipefail
BASE_URL="${FADCAM_RELAY_BASE_URL:?FADCAM_RELAY_BASE_URL must be set}"
HOSTPORT="${BASE_URL#*://}"; HOSTPORT="${HOSTPORT%%/*}"
HOST="${HOSTPORT%%:*}"; PORT="${HOSTPORT##*:}"; PORT="${PORT:-443}"
CA_ARGS=(); [[ -n "${FADCAM_RELAY_CA_FILE:-}" ]] && CA_ARGS+=("-CAfile" "$FADCAM_RELAY_CA_FILE")
printf 'Checking relay TLS endpoint: %s\n' "$BASE_URL"
printf 'Host: %s\nPort: %s\n' "$HOST" "$PORT"
output="$(openssl s_client -connect "${HOST}:${PORT}" -servername "$HOST" -verify_hostname "$HOST" "${CA_ARGS[@]}" -verify_return_error </dev/null 2>&1 || true)"
printf '%s\n' "$output"
if ! grep -q 'Verify return code: 0 (ok)' <<<"$output"; then
  echo 'ERROR: relay TLS certificate/chain/hostname verification failed.' >&2
  echo 'Do NOT bypass TLS verification to make CI green.' >&2
  exit 60
fi
echo 'Relay TLS certificate, chain, and hostname verification passed.'
