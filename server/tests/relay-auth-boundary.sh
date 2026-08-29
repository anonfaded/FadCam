#!/usr/bin/env bash
set -euo pipefail

# Auth boundary smoke test for the Internet-facing relay.
# This deliberately sends NO real credentials. A secure relay must reject
# device-token issuance and media uploads before inspecting application data.

BASE_URL="${FADCAM_RELAY_BASE_URL:-https://live.fadseclab.com:8443}"
BASE_URL="${BASE_URL%/}"

fail() {
  echo "ERROR: $*" >&2
  exit 1
}

assert_denied() {
  local label="$1"
  local url="$2"
  local method="${3:-GET}"
  local code

  if [[ "$method" == "PUT" ]]; then
    code="$(curl --silent --show-error --output /dev/null --write-out '%{http_code}' \
      --request PUT --header 'Content-Type: application/octet-stream' \
      --data-binary '' --max-time 15 "$url")"
  else
    code="$(curl --silent --show-error --output /dev/null --write-out '%{http_code}' \
      --request "$method" --max-time 15 "$url")"
  fi

  case "$code" in
    401|403) echo "PASS: $label rejected unauthenticated request (HTTP $code)" ;;
    *) fail "$label accepted or ambiguously handled unauthenticated request (HTTP $code)" ;;
  esac
}

# The relay must never mint a device stream token without the device credential.
assert_denied "stream-token issuance" "$BASE_URL/internal/get-stream-token" POST

# Uploads must be bearer-authenticated. Use syntactically valid placeholder IDs
# so a missing credential is the first authorization decision being tested.
assert_denied "media upload" "$BASE_URL/upload/00000000-0000-0000-0000-000000000000/relay-auth-test/live.m3u8" PUT

# Protected endpoints must not opt into wildcard browser access. A wildcard
# CORS policy would make accidental credential exposure much easier.
headers="$(curl --silent --show-error --dump-header - --output /dev/null --max-time 15 \
  "$BASE_URL/internal/get-stream-token" || true)"
if grep -Eiq '^access-control-allow-origin:[[:space:]]*\*' <<<"$headers"; then
  fail "relay exposes wildcard CORS on protected authentication endpoint"
fi

echo "Relay authentication boundary checks passed."
