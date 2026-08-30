#!/usr/bin/env bash
set -euo pipefail

# Auth boundary smoke test for the relay configured by the caller.
# CI supplies an isolated local relay endpoint. Never silently fall back to an
# Internet-facing service: that would make validation depend on external state.
BASE_URL="${FADCAM_RELAY_BASE_URL:?FADCAM_RELAY_BASE_URL must be set}"
BASE_URL="${BASE_URL%/}"

CURL_ARGS=(--silent --show-error --max-time 15)
if [[ -n "${FADCAM_RELAY_CA_FILE:-}" ]]; then
  CURL_ARGS+=(--cacert "$FADCAM_RELAY_CA_FILE")
fi

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
    code="$(curl "${CURL_ARGS[@]}" --output /dev/null --write-out '%{http_code}' \
      --request PUT --header 'Content-Type: application/octet-stream' \
      --data-binary '' "$url")"
  else
    code="$(curl "${CURL_ARGS[@]}" --output /dev/null --write-out '%{http_code}' \
      --request "$method" "$url")"
  fi

  case "$code" in
    401|403) echo "PASS: $label rejected unauthenticated request (HTTP $code)" ;;
    405) echo "PASS: $label rejected unsupported unauthenticated method (HTTP 405)" ;;
    *) fail "$label accepted or ambiguously handled unauthenticated request (HTTP $code)" ;;
  esac
}

assert_denied "stream-token issuance" "$BASE_URL/internal/get-stream-token" POST
assert_denied "media upload" "$BASE_URL/upload/00000000-0000-0000-0000-000000000000/relay-auth-test/live.m3u8" PUT

headers="$(curl "${CURL_ARGS[@]}" --dump-header - --output /dev/null \
  "$BASE_URL/internal/get-stream-token" || true)"
if grep -Eiq '^access-control-allow-origin:[[:space:]]*\*' <<<"$headers"; then
  fail "relay exposes wildcard CORS on protected authentication endpoint"
fi

echo "Relay authentication boundary checks passed."
