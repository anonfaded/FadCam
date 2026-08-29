#!/usr/bin/env bash
set -Eeuo pipefail

COMPOSE=(docker compose --env-file server/.env.example -f server/docker-compose.yml)
PATH_NAME="fadcam"
RTMP_URL="rtmp://127.0.0.1:1935/${PATH_NAME}"
HLS_BASE_URL="http://127.0.0.1:8888/${PATH_NAME}"
HLS_URL="${HLS_BASE_URL}/index.m3u8"

HLS_FILE=""
HLS_HEADERS=""
HLS_COOKIE_JAR=""
PUBLISHER_LOG=""
PUBLISHER_PID=""
SEGMENT_FILE=""
CLEANING_UP=false

log() { printf '%s\n' "$*"; }
fail() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }

cleanup() {
  [[ "${CLEANING_UP}" == true ]] && return 0
  CLEANING_UP=true

  if [[ -n "${PUBLISHER_PID}" ]] && kill -0 "${PUBLISHER_PID}" >/dev/null 2>&1; then
    kill -TERM "${PUBLISHER_PID}" >/dev/null 2>&1 || true
    wait "${PUBLISHER_PID}" >/dev/null 2>&1 || true
  fi

  if [[ -n "${PUBLISHER_LOG}" && -s "${PUBLISHER_LOG}" ]]; then
    log "Publisher log:"
    tail -n 160 "${PUBLISHER_LOG}" >&2 || true
  fi

  log "MediaMTX logs:"
  "${COMPOSE[@]}" logs --no-color mediamtx 2>/dev/null | tail -n 240 >&2 || true

  rm -f "${HLS_FILE}" "${HLS_HEADERS}" "${HLS_COOKIE_JAR}" "${PUBLISHER_LOG}" "${SEGMENT_FILE}" 2>/dev/null || true
  "${COMPOSE[@]}" down --remove-orphans >/dev/null 2>&1 || true
}
trap cleanup EXIT INT TERM

require_commands() {
  local command_name
  for command_name in ffmpeg ffprobe curl awk grep sed; do
    command -v "${command_name}" >/dev/null 2>&1 || fail "${command_name} is required."
  done
}

publisher_alive() {
  [[ -n "${PUBLISHER_PID}" ]] && kill -0 "${PUBLISHER_PID}" >/dev/null 2>&1
}

assert_publisher_alive() {
  if publisher_alive; then
    return 0
  fi

  local rc=0
  if [[ -n "${PUBLISHER_PID}" ]]; then
    wait "${PUBLISHER_PID}" || rc=$?
  fi
  fail "Deterministic RTMP publisher exited before validation completed (exit ${rc})."
}

media_mtx_running() {
  "${COMPOSE[@]}" ps --status running --services 2>/dev/null | grep -qx 'mediamtx'
}

wait_for_tcp() {
  local host="$1" port="$2" timeout="$3"
  local attempt

  for ((attempt=1; attempt<=timeout; attempt++)); do
    # Do not write bytes to RTMP. A /dev/tcp echo probe is itself an RTMP
    # client and produces misleading "invalid rtmp version" entries in logs.
    if timeout 2 bash -c "exec 3<>/dev/tcp/${host}/${port}" >/dev/null 2>&1; then
      log "PASS: MediaMTX RTMP listener ready after ${attempt}s."
      return 0
    fi
    media_mtx_running || fail "MediaMTX stopped while waiting for RTMP listener."
    sleep 1
  done

  fail "MediaMTX RTMP listener did not become ready within ${timeout}s."
}

http_get_hls() {
  # One stateful HLS client: follow the cookie-check redirect and retain its
  # cookie for every subsequent playlist/media request.
  curl --connect-timeout 2 --max-time 5 -fsS -L \
    -D "${HLS_HEADERS}" \
    -c "${HLS_COOKIE_JAR}" -b "${HLS_COOKIE_JAR}" \
    "${HLS_URL}?cookieCheck=1" -o "${HLS_FILE}"
}

playlist_has_media() {
  grep -Eq '^[^#[:space:]]+$' "${HLS_FILE}"
}

wait_for_rtmp_ingest() {
  local timeout=20
  local attempt
  log "Waiting for RTMP ingest..."

  for ((attempt=1; attempt<=timeout; attempt++)); do
    assert_publisher_alive
    if "${COMPOSE[@]}" logs --no-color mediamtx 2>/dev/null | grep -q "is publishing to path '${PATH_NAME}'"; then
      log "PASS: RTMP path online and publisher ingest confirmed."
      return 0
    fi
    sleep 1
  done

  fail "MediaMTX did not confirm RTMP publisher ingest within ${timeout}s."
}

wait_for_hls_playlist() {
  local timeout=30
  local attempt
  log "Waiting for HLS playlist..."

  for ((attempt=1; attempt<=timeout; attempt++)); do
    assert_publisher_alive
    if http_get_hls 2>/dev/null && grep -q '^#EXTM3U' "${HLS_FILE}"; then
      log "PASS: HLS playlist available after ${attempt}s."
      return 0
    fi
    sleep 1
  done

  fail "HLS playlist was not available within ${timeout}s."
}

first_media_uri() {
  awk '/^[^#[:space:]]+$/ { print; exit }' "${HLS_FILE}"
}

resolve_media_url() {
  local uri="$1"
  case "${uri}" in
    http://*|https://*) printf '%s\n' "${uri}" ;;
    /*) printf 'http://127.0.0.1:8888%s\n' "${uri}" ;;
    *) printf '%s/%s\n' "${HLS_BASE_URL}" "${uri#./}" ;;
  esac
}

fetch_first_media_object() {
  local uri url
  uri="$(first_media_uri || true)"
  [[ -n "${uri}" ]] || return 1
  url="$(resolve_media_url "${uri}")"

  curl --connect-timeout 2 --max-time 5 -fsS -L \
    -c "${HLS_COOKIE_JAR}" -b "${HLS_COOKIE_JAR}" \
    "${url}" -o "${SEGMENT_FILE}" \
    && [[ -s "${SEGMENT_FILE}" ]]
}

wait_for_media_object() {
  local timeout=20
  local attempt
  log "Waiting for HLS media object..."

  for ((attempt=1; attempt<=timeout; attempt++)); do
    assert_publisher_alive
    if fetch_first_media_object; then
      log "PASS: HLS media object is readable after ${attempt}s."
      return 0
    fi
    http_get_hls 2>/dev/null || true
    sleep 1
  done

  fail "HLS playlist exists, but no readable HLS media object was produced."
}

cookie_header_for_ffprobe() {
  awk 'BEGIN{ORS=""} $0 !~ /^#/ && NF>=7 { printf "%s=%s; ", $6, $7 }' "${HLS_COOKIE_JAR}"
}

verify_codecs_and_decode() {
  local cookie_header
  cookie_header="$(cookie_header_for_ffprobe)"
  log "Verifying H.264 + AAC by decoding the live HLS stream with ffprobe..."

  local probe_output
  probe_output="$(mktemp)"
  if ! ffprobe -v error \
      -rw_timeout 5000000 \
      -headers "Cookie: ${cookie_header}\r\n" \
      -read_intervals '%+3' \
      -show_entries stream=codec_type,codec_name,width,height,profile \
      -of csv=p=0 \
      "${HLS_URL}" >"${probe_output}" 2>"${probe_output}.err"; then
    log "ffprobe stderr:"
    cat "${probe_output}.err" >&2 || true
    rm -f "${probe_output}" "${probe_output}.err"
    fail "ffprobe could not read/decode the HLS stream."
  fi

  cat "${probe_output}"
  grep -Eq '^video,h264,' "${probe_output}" || {
    cat "${probe_output}.err" >&2 2>/dev/null || true
    rm -f "${probe_output}" "${probe_output}.err"
    fail "Decoded HLS stream does not contain H.264 video."
  }
  grep -Eq '^audio,aac,' "${probe_output}" || {
    rm -f "${probe_output}" "${probe_output}.err"
    fail "Decoded HLS stream does not contain AAC audio."
  }

  log "PASS: H.264 video decoded."
  log "PASS: AAC audio decoded."
  rm -f "${probe_output}" "${probe_output}.err"
}

verify_sustained_stream() {
  local seconds=5
  local attempt
  log "Verifying sustained publishing for ${seconds}s..."

  for ((attempt=1; attempt<=seconds; attempt++)); do
    assert_publisher_alive
    http_get_hls >/dev/null 2>&1 || fail "HLS stopped responding while publisher remained alive (second ${attempt})."
    grep -q '^#EXTM3U' "${HLS_FILE}" || fail "HLS playlist became invalid during sustained-stream check."
    sleep 1
  done

  log "PASS: publisher remained alive during sustained validation."
  log "PASS: HLS remained available during sustained publishing."
}

verify_cleanup() {
  local timeout=12
  local attempt
  log "Stopping publisher deliberately..."

  assert_publisher_alive
  kill -TERM "${PUBLISHER_PID}" >/dev/null 2>&1 || true

  local rc=0
  wait "${PUBLISHER_PID}" || rc=$?
  PUBLISHER_PID=""

  if [[ "${rc}" -ne 0 && "${rc}" -ne 143 ]]; then
    fail "Publisher failed during deliberate shutdown (exit ${rc})."
  fi
  log "PASS: publisher stopped deliberately (exit ${rc})."

  log "Waiting for MediaMTX to remove the HLS muxer..."
  for ((attempt=1; attempt<=timeout; attempt++)); do
    if ! "${COMPOSE[@]}" logs --no-color mediamtx 2>/dev/null | grep -q "muxer ${PATH_NAME}"; then
      log "PASS: MediaMTX muxer cleanup confirmed."
      return 0
    fi
    if "${COMPOSE[@]}" logs --no-color mediamtx 2>/dev/null | grep -q "muxer ${PATH_NAME}] destroyed"; then
      log "PASS: MediaMTX muxer cleanup confirmed."
      return 0
    fi
    sleep 1
  done

  fail "MediaMTX did not report HLS muxer cleanup within ${timeout}s."
}

require_commands

log "Starting MediaMTX..."
"${COMPOSE[@]}" --profile tv up -d mediamtx
wait_for_tcp 127.0.0.1 1935 60

HLS_FILE="$(mktemp)"
HLS_HEADERS="$(mktemp)"
HLS_COOKIE_JAR="$(mktemp)"
PUBLISHER_LOG="$(mktemp)"
SEGMENT_FILE="$(mktemp)"

log "Starting long-lived deterministic H.264/AAC RTMP publisher..."
ffmpeg -hide_banner -loglevel warning \
  -re -f lavfi -i "testsrc2=size=640x360:rate=15" \
  -re -f lavfi -i "sine=frequency=1000:sample_rate=48000" \
  -map 0:v:0 -map 1:a:0 \
  -c:v libx264 -preset ultrafast -tune zerolatency -pix_fmt yuv420p \
  -g 30 -keyint_min 30 -sc_threshold 0 \
  -c:a aac -b:a 128k -ar 48000 -ac 2 \
  -f flv "${RTMP_URL}" >"${PUBLISHER_LOG}" 2>&1 &
PUBLISHER_PID=$!

wait_for_rtmp_ingest
assert_publisher_alive
log "PASS: FFmpeg publisher process alive."

wait_for_hls_playlist
wait_for_media_object
verify_codecs_and_decode
verify_sustained_stream
verify_cleanup

log "PASS: RTMP -> MediaMTX -> HLS end-to-end gate completed."
