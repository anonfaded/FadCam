#!/usr/bin/env bash
set -Eeuo pipefail

COMPOSE=(docker compose --env-file server/.env.example -f server/docker-compose.yml)
PATH_NAME="fadcam"
RTMP_URL="rtmp://127.0.0.1:1935/${PATH_NAME}"
HLS_PAGE_URL="http://127.0.0.1:8888/${PATH_NAME}"
HLS_URL="${HLS_PAGE_URL}/index.m3u8?cookieCheck=1"
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
  "${COMPOSE[@]}" logs --no-color mediamtx 2>/dev/null | tail -n 160 >&2 || true

  rm -f "${HLS_FILE}" "${STREAM_FILE}" "${SEGMENT_FILE}" "${PUBLISHER_LOG}" 2>/dev/null || true
  "${COMPOSE[@]}" down --remove-orphans >/dev/null 2>&1 || true
}
trap cleanup EXIT INT TERM

fail() {
  echo "ERROR: $*" >&2
  exit 1
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

wait_for_hls() {
  local timeout_seconds="$1"
  for ((attempt=1; attempt<=timeout_seconds; attempt++)); do
    if ! kill -0 "${PUBLISHER_PID}" >/dev/null 2>&1; then
      if wait "${PUBLISHER_PID}"; then
        fail "Deterministic RTMP publisher exited before HLS became ready."
      else
        fail "Deterministic RTMP publisher failed before HLS became ready."
      fi
    fi

    if curl --connect-timeout 1 --max-time 3 -fsS "${HLS_URL}" -o "${HLS_FILE}" 2>/dev/null \
      && grep -q '^#EXTM3U' "${HLS_FILE}" \
      && grep -Eq '^#EXTINF:' "${HLS_FILE}"; then
      echo "HLS playlist ready after ${attempt}s."
      return 0
    fi
    sleep 1
  done
  fail "MediaMTX did not expose a usable HLS playlist within ${timeout_seconds}s."
}

wait_for_segment() {
  local timeout_seconds="$1"
  local segment_url
  for ((attempt=1; attempt<=timeout_seconds; attempt++)); do
    segment_url="$(awk '/^[^#].*/ {print; exit}' "${HLS_FILE}" || true)"
    if [[ -n "${segment_url}" ]]; then
      [[ "${segment_url}" =~ ^https?:// ]] || segment_url="${HLS_PAGE_URL}/${segment_url#./}"
      if curl --connect-timeout 1 --max-time 3 -fsS "${segment_url}" -o "${SEGMENT_FILE}" 2>/dev/null \
        && [[ -s "${SEGMENT_FILE}" ]]; then
        echo "HLS media segment is readable after ${attempt}s."
        return 0
      fi
    fi
    sleep 1
    curl --connect-timeout 1 --max-time 3 -fsS "${HLS_URL}" -o "${HLS_FILE}" 2>/dev/null || true
  done
  fail "MediaMTX exposed an HLS playlist but no readable media segment was available."
}

echo "Starting MediaMTX..."
"${COMPOSE[@]}" --profile tv up -d mediamtx

echo "Waiting for MediaMTX RTMP listener readiness..."
wait_for_tcp 127.0.0.1 1935 60
echo "MediaMTX RTMP listener ready."

command -v ffmpeg >/dev/null 2>&1 || fail "ffmpeg is required for the deterministic publisher test."
command -v ffprobe >/dev/null 2>&1 || fail "ffprobe is required for HLS media verification."
command -v curl >/dev/null 2>&1 || fail "curl is required for HLS verification."

HLS_FILE="$(mktemp)"
STREAM_FILE="$(mktemp)"
SEGMENT_FILE="$(mktemp)"
PUBLISHER_LOG="$(mktemp)"

echo "Starting long-lived deterministic H.264/AAC RTMP publisher..."
ffmpeg -hide_banner -loglevel warning \
  -re \
  -f lavfi -i "testsrc2=size=640x360:rate=15" \
  -f lavfi -i "sine=frequency=1000:sample_rate=48000" \
  -map 0:v:0 -map 1:a:0 \
  -c:v libx264 -preset ultrafast -tune zerolatency -pix_fmt yuv420p -g 30 -keyint_min 30 \
  -c:a aac -b:a 128k -ar 48000 -ac 2 \
  -f flv "${RTMP_URL}" >"${PUBLISHER_LOG}" 2>&1 &
PUBLISHER_PID=$!

echo "Waiting for MediaMTX to expose HLS output from the RTMP publisher..."
wait_for_hls 30
echo "RTMP ingest -> HLS output confirmed."

wait_for_segment 15

echo "Verifying codecs..."
ffprobe -v error -read_intervals %+3 -i "${HLS_URL}" \
  -show_entries stream=codec_type,codec_name \
  -of csv=p=0 | tee "${STREAM_FILE}"

grep -q '^video,h264$' "${STREAM_FILE}" || fail "HLS output does not contain H.264 video."
grep -q '^audio,aac$' "${STREAM_FILE}" || fail "HLS output does not contain AAC audio."
echo "PASS: H.264 video track confirmed."
echo "PASS: AAC audio track confirmed."

if ! kill -0 "${PUBLISHER_PID}" >/dev/null 2>&1; then
  wait "${PUBLISHER_PID}" || true
  fail "RTMP publisher terminated during E2E validation; stream was not kept alive."
fi
echo "PASS: deterministic publisher remains alive during validation."

# Refresh the playlist and require it to remain valid while the same publisher is alive.
sleep 2
curl --connect-timeout 1 --max-time 3 -fsS "${HLS_URL}" -o "${HLS_FILE}" || fail "HLS playlist stopped responding while publisher was alive."
grep -q '^#EXTM3U' "${HLS_FILE}" || fail "HLS playlist became invalid during sustained-stream check."
echo "PASS: HLS output remains available during sustained publishing."

echo "Stopping deterministic publisher deliberately..."
kill -TERM "${PUBLISHER_PID}" >/dev/null 2>&1 || true
if wait "${PUBLISHER_PID}"; then
  echo "PASS: publisher stopped cleanly."
else
  publisher_rc=$?
  [[ "${publisher_rc}" -eq 143 ]] || fail "publisher exited unexpectedly with code ${publisher_rc}."
  echo "PASS: publisher stopped on requested SIGTERM."
fi
PUBLISHER_PID=""

echo "PASS: RTMP -> MediaMTX -> HLS end-to-end gate completed."
