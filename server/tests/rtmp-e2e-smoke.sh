#!/usr/bin/env bash
set -Eeuo pipefail

COMPOSE=(docker compose --env-file server/.env.example -f server/docker-compose.yml)
PATH_NAME="fadcam"
RTMP_URL="rtmp://127.0.0.1:1935/${PATH_NAME}"
HLS_ROOT_URL="http://127.0.0.1:8888/${PATH_NAME}/"
HLS_URL="${HLS_ROOT_URL}index.m3u8"

PUBLISHER_LOG=""
PUBLISHER_PID=""
MASTER_PLAYLIST=""
MEDIA_PLAYLIST=""
SEGMENT_FILE=""
PROBE_LOG=""
DECODE_LOG=""
HLS_COOKIE_JAR=""
CLEANING_UP=false

log() { printf '%s\n' "$*"; }
fail() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }

publisher_state() {
  [[ -n "${PUBLISHER_PID}" ]] || return 1
  ps -p "${PUBLISHER_PID}" -o stat= 2>/dev/null | awk 'NR==1 {print; exit}'
}

publisher_alive() {
  local state
  state="$(publisher_state || true)"
  [[ -n "${state}" && "${state}" != Z* && "${state}" != *Z* ]]
}

publisher_exit_code() {
  local rc=0
  wait "${PUBLISHER_PID}" || rc=$?
  printf '%s\n' "${rc}"
}

print_forensics() {
  log "=== Publisher forensics ===" >&2
  if [[ -n "${PUBLISHER_PID}" ]]; then
    ps -p "${PUBLISHER_PID}" -o pid=,ppid=,stat=,etime=,cmd= >&2 2>/dev/null || true
  fi
  if [[ -n "${PUBLISHER_LOG}" && -s "${PUBLISHER_LOG}" ]]; then
    tail -n 200 "${PUBLISHER_LOG}" >&2 || true
  fi
  log "=== MediaMTX forensics ===" >&2
  "${COMPOSE[@]}" logs --no-color mediamtx 2>/dev/null | tail -n 320 >&2 || true
}

cleanup() {
  [[ "${CLEANING_UP}" == true ]] && return 0
  CLEANING_UP=true
  if [[ -n "${PUBLISHER_PID}" ]] && publisher_alive; then
    kill -TERM "${PUBLISHER_PID}" >/dev/null 2>&1 || true
    wait "${PUBLISHER_PID}" >/dev/null 2>&1 || true
  fi
  print_forensics
  rm -f "${PUBLISHER_LOG}" "${MASTER_PLAYLIST}" "${MEDIA_PLAYLIST}" "${SEGMENT_FILE}" "${PROBE_LOG}" "${DECODE_LOG}" "${HLS_COOKIE_JAR}" 2>/dev/null || true
  "${COMPOSE[@]}" down --remove-orphans >/dev/null 2>&1 || true
}
trap cleanup EXIT INT TERM

require_commands() {
  local name
  for name in ffmpeg ffprobe curl awk grep sed timeout ps; do
    command -v "${name}" >/dev/null 2>&1 || fail "${name} is required."
  done
}

media_mtx_running() {
  "${COMPOSE[@]}" ps --status running --services 2>/dev/null | grep -qx 'mediamtx'
}

wait_for_tcp() {
  local host="$1" port="$2" timeout_seconds="$3"
  local attempt
  for ((attempt=1; attempt<=timeout_seconds; attempt++)); do
    if timeout 2 bash -c "exec 3<>/dev/tcp/${host}/${port}" >/dev/null 2>&1; then
      log "PASS: MediaMTX RTMP listener ready after ${attempt}s."
      return 0
    fi
    media_mtx_running || fail "MediaMTX stopped while waiting for RTMP listener."
    sleep 1
  done
  fail "MediaMTX RTMP listener did not become ready within ${timeout_seconds}s."
}

wait_for_rtmp_ingest() {
  local timeout_seconds=20
  local attempt
  log "Waiting for RTMP ingest..."
  for ((attempt=1; attempt<=timeout_seconds; attempt++)); do
    publisher_alive || {
      log "Publisher state: $(publisher_state || echo '<exited>')" >&2
      local rc
      rc="$(publisher_exit_code)"
      fail "Deterministic publisher exited before RTMP ingest (exit ${rc})."
    }
    if "${COMPOSE[@]}" logs --no-color mediamtx 2>/dev/null | grep -q "is publishing to path '${PATH_NAME}'"; then
      log "PASS: RTMP connection/path online."
      return 0
    fi
    sleep 1
  done
  fail "MediaMTX did not confirm RTMP ingest within ${timeout_seconds}s."
}

fetch_hls_once() {
  local url="$1" output="$2"
  # MediaMTX 1.18+ associates HLS playlist/segment requests with a reader
  # session cookie. Keep one cookie jar for the complete master -> child ->
  # media-object transaction; the session query parameter in child URIs is not
  # a substitute for the HTTP cookie.
  curl --connect-timeout 3 --max-time 10 -fsS -L \
    -c "${HLS_COOKIE_JAR}" -b "${HLS_COOKIE_JAR}" \
    "${url}" -o "${output}"
}

resolve_hls_uri() {
  local uri="$1"
  case "${uri}" in
    http://*|https://*) printf '%s\n' "${uri}" ;;
    /*) printf 'http://127.0.0.1:8888%s\n' "${uri}" ;;
    *) printf '%s%s\n' "${HLS_ROOT_URL}" "${uri#./}" ;;
  esac
}

# MediaMTX emits child playlists both as bare URI lines and as URI attributes
# on EXT-X-MEDIA/EXT-X-STREAM-INF records. Extract the attribute value instead
# of passing the whole EXT-X-MEDIA record to curl.
first_child_playlist_uri() {
  awk '
    /\.m3u8/ {
      if (match($0, /URI="[^"]+\.m3u8[^"]*"/)) {
        value=substr($0, RSTART+5, RLENGTH-6)
        print value
        exit
      }
      if ($0 !~ /^#/) {
        print $0
        exit
      }
    }
  ' "${MASTER_PLAYLIST}"
}

first_media_uri() {
  awk '/^[^#[:space:]]+$/ { print; exit }' "${MEDIA_PLAYLIST}"
}

verify_hls_playlist_and_media_object() {
  log "Fetching HLS master playlist once..."
  fetch_hls_once "${HLS_URL}" "${MASTER_PLAYLIST}" \
    || fail "HLS master playlist request failed."
  grep -q '^#EXTM3U' "${MASTER_PLAYLIST}" \
    || fail "HLS endpoint returned data, but not an HLS playlist."
  log "PASS: HLS master playlist exists."

  local child child_url
  child="$(first_child_playlist_uri || true)"
  [[ -n "${child}" ]] || fail "HLS master playlist contains no media playlist URI."
  child_url="$(resolve_hls_uri "${child}")"
  log "Fetching HLS media playlist once: ${child}"
  fetch_hls_once "${child_url}" "${MEDIA_PLAYLIST}" \
    || fail "Referenced HLS media playlist could not be fetched."
  grep -q '^#EXTM3U' "${MEDIA_PLAYLIST}" \
    || fail "Referenced HLS media playlist is invalid."
  log "PASS: HLS media playlist exists."

  local uri url
  uri="$(first_media_uri || true)"
  [[ -n "${uri}" ]] || fail "HLS media playlist contains no media object URI."
  url="$(resolve_hls_uri "${uri}")"
  log "Fetching first HLS media object: ${uri}"
  fetch_hls_once "${url}" "${SEGMENT_FILE}" \
    || fail "Referenced HLS media object could not be fetched."
  [[ -s "${SEGMENT_FILE}" ]] || fail "Referenced HLS media object is empty."
  log "PASS: HLS media object exists and is non-empty."
}

verify_codecs() {
  log "Probing HLS stream codecs with ffprobe..."
  : >"${PROBE_LOG}"
  local streams
  streams="$(timeout 15 ffprobe -v error -rw_timeout 10000000 \
    -show_entries stream=codec_type,codec_name \
    -of csv=p=0 "${HLS_URL}" 2>"${PROBE_LOG}" || true)"

  printf '%s\n' "${streams}" | grep -Eq '(^|,)h264(,|$)' || {
    log "ffprobe streams: ${streams:-<none>}" >&2
    cat "${PROBE_LOG}" >&2 || true
    fail "HLS video codec H.264 was not found."
  }
  printf '%s\n' "${streams}" | grep -Eq '(^|,)video(,|$)' || {
    log "ffprobe streams: ${streams:-<none>}" >&2
    cat "${PROBE_LOG}" >&2 || true
    fail "HLS video track was not found."
  }
  printf '%s\n' "${streams}" | grep -Eq '(^|,)aac(,|$)' || {
    log "ffprobe streams: ${streams:-<none>}" >&2
    cat "${PROBE_LOG}" >&2 || true
    fail "HLS audio codec AAC was not found."
  }
  printf '%s\n' "${streams}" | grep -Eq '(^|,)audio(,|$)' || {
    log "ffprobe streams: ${streams:-<none>}" >&2
    cat "${PROBE_LOG}" >&2 || true
    fail "HLS audio track was not found."
  }
  log "PASS: H.264 track present."
  log "PASS: AAC track present."
}

verify_decode_and_sustain() {
  log "Decoding HLS continuously for 8s; this is the sustained-stream assertion."
  : >"${DECODE_LOG}"
  timeout 15 ffmpeg -hide_banner -loglevel error \
    -i "${HLS_URL}" -t 8 -map 0:v:0 -map 0:a:0 -f null - \
    >/dev/null 2>"${DECODE_LOG}" &
  local decoder_pid=$!

  sleep 4
  publisher_alive || {
    log "Publisher state at sustained check: $(publisher_state || echo '<exited>')" >&2
    cat "${DECODE_LOG}" >&2 || true
    fail "Publisher stopped during sustained HLS validation."
  }
  if ! "${COMPOSE[@]}" logs --no-color mediamtx 2>/dev/null | grep -q "is publishing to path '${PATH_NAME}'"; then
    cat "${DECODE_LOG}" >&2 || true
    fail "MediaMTX no longer reports the RTMP publisher online during sustained validation."
  fi
  log "PASS: Publisher remains alive during sustained HLS validation."
  log "PASS: MediaMTX still reports the RTMP path online."

  local rc=0
  wait "${decoder_pid}" || rc=$?
  if [[ "${rc}" -ne 0 ]]; then
    cat "${DECODE_LOG}" >&2 || true
    fail "HLS media could not be decoded continuously (ffmpeg exit ${rc})."
  fi
  log "PASS: HLS media decoded successfully for 8s."
}

stop_publisher_and_verify_cleanup() {
  log "Stopping deterministic publisher deliberately..."
  publisher_alive || fail "Publisher was not alive before deliberate shutdown."
  kill -TERM "${PUBLISHER_PID}" >/dev/null 2>&1 || true
  local rc=0
  wait "${PUBLISHER_PID}" || rc=$?
  PUBLISHER_PID=""
  log "PASS: Publisher stopped deliberately (wait status ${rc})."

  local timeout_seconds=12 attempt
  log "Waiting for MediaMTX HLS muxer cleanup..."
  for ((attempt=1; attempt<=timeout_seconds; attempt++)); do
    if "${COMPOSE[@]}" logs --no-color mediamtx 2>/dev/null | grep -q "muxer ${PATH_NAME}] destroyed"; then
      log "PASS: MediaMTX cleanup confirmed."
      return 0
    fi
    sleep 1
  done
  fail "MediaMTX did not clean up the HLS muxer within ${timeout_seconds}s."
}

require_commands

log "Starting MediaMTX..."
"${COMPOSE[@]}" --profile tv up -d mediamtx
wait_for_tcp 127.0.0.1 1935 60

PUBLISHER_LOG="$(mktemp)"
MASTER_PLAYLIST="$(mktemp)"
MEDIA_PLAYLIST="$(mktemp)"
SEGMENT_FILE="$(mktemp)"
PROBE_LOG="$(mktemp)"
DECODE_LOG="$(mktemp)"
HLS_COOKIE_JAR="$(mktemp)"
: >"${HLS_COOKIE_JAR}"

log "Starting long-lived deterministic H.264/AAC RTMP publisher..."
ffmpeg -hide_banner -loglevel warning \
  -re -f lavfi -i "testsrc2=size=640x360:rate=15" \
  -re -f lavfi -i "sine=frequency=1000:sample_rate=48000" \
  -map 0:v:0 -map 1:a:0 \
  -c:v libx264 -preset ultrafast -tune zerolatency -pix_fmt yuv420p \
  -g 30 -keyint_min 30 -sc_threshold 0 \
  -c:a aac -b:a 128k -ar 48000 -ac 2 \
  -flvflags no_duration_filesize \
  -f flv "${RTMP_URL}" >"${PUBLISHER_LOG}" 2>&1 &
PUBLISHER_PID=$!

wait_for_rtmp_ingest
publisher_alive || fail "Publisher died immediately after RTMP ingest."
log "PASS: FFmpeg process alive."

log "Waiting for HLS muxer to be created by MediaMTX..."
for ((i=1; i<=15; i++)); do
  if "${COMPOSE[@]}" logs --no-color mediamtx 2>/dev/null | grep -q "muxer ${PATH_NAME}] created automatically"; then
    log "PASS: HLS muxer created."
    break
  fi
  publisher_alive || fail "Publisher died while waiting for HLS muxer."
  [[ "${i}" -eq 15 ]] && fail "MediaMTX did not create the HLS muxer within 15s."
  sleep 1
done

verify_hls_playlist_and_media_object
verify_codecs
verify_decode_and_sustain
stop_publisher_and_verify_cleanup

log "PASS: RTMP -> MediaMTX -> HLS end-to-end gate completed."
