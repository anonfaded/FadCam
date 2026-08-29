#!/usr/bin/env bash
set -Eeuo pipefail

COMPOSE=(docker compose --env-file server/.env.example -f server/docker-compose.yml)
PATH_NAME="fadcam"
RTMP_URL="rtmp://127.0.0.1:1935/${PATH_NAME}"
HLS_PAGE_URL="http://127.0.0.1:8888/${PATH_NAME}"
HLS_URL="${HLS_PAGE_URL}/index.m3u8"
HLS_COOKIE_JAR=""
PUBLISHER_PID=""
HLS_FILE=""
STREAM_FILE=""
SEGMENT_FILE=""
PUBLISHER_LOG=""
CLEANING_UP=false

cleanup() {
  [[ "${CLEANING_UP}" == true ]] && return 0
  CLEANING_UP=true

  if [[ -n "${PUBLISHER_PID}" ]] && kill -0 "${PUBLISHER_PID}" >/dev/null 2>&1; then
    kill -TERM "${PUBLISHER_PID}" >/dev/null 2>&1 || true
    wait "${PUBLISHER_PID}" >/dev/null 2>&1 || true
  fi

  if [[ -n "${PUBLISHER_LOG}" && -s "${PUBLISHER_LOG}" ]]; then
    echo "Publisher log:" >&2
    tail -n 120 "${PUBLISHER_LOG}" >&2 || true
  fi

  echo "MediaMTX logs:" >&2
  "${COMPOSE[@]}" logs --no-color mediamtx 2>/dev/null | tail -n 200 >&2 || true

  rm -f "${HLS_FILE}" "${STREAM_FILE}" "${SEGMENT_FILE}" "${PUBLISHER_LOG}" "${HLS_COOKIE_JAR}" 2>/dev/null || true
  "${COMPOSE[@]}" down --remove-orphans >/dev/null 2>&1 || true
}
trap cleanup EXIT INT TERM

fail() {
  echo "ERROR: $*" >&2
  exit 1
}

assert_publisher_alive() {
  [[ -n "${PUBLISHER_PID}" ]] || fail "Publisher PID is not set."
  if ! kill -0 "${PUBLISHER_PID}" >/dev/null 2>&1; then
    local rc=0
    wait "${PUBLISHER_PID}" || rc=$?
    if [[ -s "${PUBLISHER_LOG}" ]]; then
      echo "Publisher log:" >&2
      tail -n 120 "${PUBLISHER_LOG}" >&2 || true
    fi
    fail "Deterministic RTMP publisher is no longer alive (exit code ${rc})."
  fi
}

wait_for_tcp() {
  local host="$1" port="$2" timeout_seconds="$3"
  for ((attempt=1; attempt<=timeout_seconds; attempt++)); do
    if (echo >/dev/tcp/${host}/${port}) >/dev/null 2>&1; then
      echo "TCP ${host}:${port} is ready after ${attempt}s."
      return 0
    fi
    if ! "${COMPOSE[@]}" ps --status running --services | grep -qx 'mediamtx'; then
      "${COMPOSE[@]}" ps >&2 || true
      "${COMPOSE[@]}" logs --no-color mediamtx >&2 || true
      fail "MediaMTX container stopped while waiting for ${host}:${port}."
    fi
    sleep 1
  done
  fail "TCP ${host}:${port} did not become ready within ${timeout_seconds} seconds."
}

fetch_hls_playlist() {
  # MediaMTX's cookieCheck flow is a stateful redirect. Follow redirects and
  # persist the cookie instead of creating a fresh HLS session on every poll.
  curl --connect-timeout 2 --max-time 5 -fsSL \
    -c "${HLS_COOKIE_JAR}" -b "${HLS_COOKIE_JAR}" \
    "${HLS_URL}?cookieCheck=1" -o "${HLS_FILE}"
}

wait_for_hls_playlist() {
  local timeout_seconds="$1"
  for ((attempt=1; attempt<=timeout_seconds; attempt++)); do
    assert_publisher_alive
    if fetch_hls_playlist 2>/dev/null && grep -q '^#EXTM3U' "${HLS_FILE}"; then
      echo "HLS playlist ready after ${attempt}s."
      return 0
    fi
    sleep 1
  done
  fail "MediaMTX did not expose a valid HLS playlist within ${timeout_seconds}s."
}

resolve_segment_url() {
  local segment="$1"
  if [[ "${segment}" =~ ^https?:// ]]; then
    printf '%s\n' "${segment}"
  elif [[ "${segment}" == /* ]]; then
    printf 'http://127.0.0.1:8888%s\n' "${segment}"
  else
    printf '%s/%s\n' "${HLS_PAGE_URL}" "${segment#./}"
  fi
}

fetch_first_hls_media_object() {
  local object_url
  object_url="$(awk '
    /^[^#[:space:]]/ { print; exit }
  ' "${HLS_FILE}" || true)"

  [[ -n "${object_url}" ]] || return 1
  object_url="$(resolve_segment_url "${object_url}")"

  curl --connect-timeout 2 --max-time 5 -fsSL \
    -c "${HLS_COOKIE_JAR}" -b "${HLS_COOKIE_JAR}" \
    "${object_url}" -o "${SEGMENT_FILE}" \
    && [[ -s "${SEGMENT_FILE}" ]]
}

wait_for_hls_media_object() {
  local timeout_seconds="$1"
  for ((attempt=1; attempt<=timeout_seconds; attempt++)); do
    assert_publisher_alive
    if fetch_first_hls_media_object; then
      echo "HLS media object is readable after ${attempt}s."
      return 0
    fi
    sleep 1
    fetch_hls_playlist 2>/dev/null || true
  done
  fail "HLS playlist was available, but no readable HLS media object was produced."
}

verify_stream_codecs() {
  local probe_input="${HLS_URL}?cookieCheck=1"
  echo "Verifying H.264 + AAC through ffprobe..."

  if ! curl --connect-timeout 2 --max-time 5 -fsSL \
      -c "${HLS_COOKIE_JAR}" -b "${HLS_COOKIE_JAR}" \
      "${probe_input}" -o "${HLS_FILE}"; then
    fail "Unable to refresh HLS playlist before codec verification."
  fi

  if ! ffprobe -v error -read_intervals %+3 \
      -i "${probe_input}" \
      -show_entries stream=codec_type,codec_name \
      -of csv=p=0 | tee "${STREAM_FILE}"; then
    fail "ffprobe could not decode the HLS stream."
  fi

  grep -q '^video,h264$' "${STREAM_FILE}" || fail "HLS output does not contain H.264 video."
  grep -q '^audio,aac$' "${STREAM_FILE}" || fail "HLS output does not contain AAC audio."
  echo "PASS: H.264 video track confirmed."
  echo "PASS: AAC audio track confirmed."
}

verify_sustained_stream() {
  echo "Verifying stream remains online for 5 seconds..."
  for _ in 1 2 3 4 5; do
    assert_publisher_alive
    fetch_hls_playlist || fail "HLS playlist stopped responding while publisher was alive."
    grep -q '^#EXTM3U' "${HLS_FILE}" || fail "HLS playlist became invalid during sustained-stream check."
    sleep 1
  done
  echo "PASS: publisher remains alive during validation."
  echo "PASS: HLS output remains available during sustained publishing."
}

verify_media_cleanup() {
  echo "Waiting for MediaMTX to clean up after publisher shutdown..."
  for _ in 1 2 3 4 5 6 7 8 9 10; do
    if ! curl --connect-timeout 1 --max-time 2 -fsSL \
        -c "${HLS_COOKIE_JAR}" -b "${HLS_COOKIE_JAR}" \
        "${HLS_URL}?cookieCheck=1" -o "${HLS_FILE}" 2>/dev/null; then
      echo "PASS: HLS output is no longer available after publisher stopped."
      return 0
    fi
    sleep 1
  done
  fail "HLS output remained available after publisher shutdown; MediaMTX cleanup did not complete."
}

echo "Starting MediaMTX..."
"${COMPOSE[@]}" --profile tv up -d mediamtx

echo "Waiting for MediaMTX RTMP listener readiness..."
wait_for_tcp 127.0.0.1 1935 60
echo "MediaMTX RTMP listener ready."

afor required_command in ffmpeg ffprobe curl; do
  command -v "${required_command}" >/dev/null 2>&1 || fail "${required_command} is required for the deterministic publisher test."
done

HLS_FILE="$(mktemp)"
STREAM_FILE="$(mktemp)"
SEGMENT_FILE="$(mktemp)"
PUBLISHER_LOG="$(mktemp)"
HLS_COOKIE_JAR="$(mktemp)"

# Keep the synthetic source alive long enough for all assertions. The test is
# intentionally stopped later; it must never end naturally during validation.
echo "Starting long-lived deterministic H.264/AAC RTMP publisher..."
ffmpeg -hide_banner -loglevel warning \
  -re -f lavfi -i "testsrc2=size=640x360:rate=15" \
  -re -f lavfi -i "sine=frequency=1000:sample_rate=48000" \
  -map 0:v:0 -map 1:a:0 \
  -c:v libx264 -preset ultrafast -tune zerolatency -pix_fmt yuv420p \
  -g 30 -keyint_min 30 -sc_threshold 0 \
  -c:a aac -b:a 128k -ar 48000 -ac 2 \
  -f flv "${RTMP_URL}" >"${PUBLISHER_LOG}" 2>&1 &
PUBLISHER_PID=$!

echo "Waiting for RTMP ingest..."
for _ in 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15; do
  assert_publisher_alive
  if "${COMPOSE[@]}" logs --no-color mediamtx 2>/dev/null | grep -q "is publishing to path '${PATH_NAME}'"; then
    echo "PASS: RTMP connection and publisher ingest confirmed."
    break
  fi
  sleep 1
done
"${COMPOSE[@]}" logs --no-color mediamtx 2>/dev/null | grep -q "is publishing to path '${PATH_NAME}'" \
  || fail "MediaMTX did not confirm RTMP publisher ingest within 15s."

wait_for_hls_playlist 30
wait_for_hls_media_object 15
verify_stream_codecs
verify_sustained_stream

echo "Stopping deterministic publisher deliberately..."
assert_publisher_alive
kill -TERM "${PUBLISHER_PID}" >/dev/null 2>&1 || true
publisher_rc=0
wait "${PUBLISHER_PID}" || publisher_rc=$?
PUBLISHER_PID=""

# FFmpeg commonly exits 143 after SIGTERM; some builds return 0 during normal
# protocol shutdown. Any other result is an actual publisher failure.
if [[ "${publisher_rc}" -ne 0 && "${publisher_rc}" -ne 143 ]]; then
  fail "publisher exited unexpectedly with code ${publisher_rc}."
fi
echo "PASS: publisher stopped deliberately (exit ${publisher_rc})."

verify_media_cleanup

echo "PASS: MediaMTX cleanup confirmed."
echo "PASS: RTMP -> MediaMTX -> HLS end-to-end gate completed."
