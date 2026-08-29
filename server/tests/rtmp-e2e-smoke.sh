#!/usr/bin/env bash
set -euo pipefail

COMPOSE=(docker compose --env-file server/.env.example -f server/docker-compose.yml)
PATH_NAME="fadcam"
RTMP_URL="rtmp://127.0.0.1:1935/${PATH_NAME}"
HLS_PAGE_URL="http://127.0.0.1:8888/${PATH_NAME}"
HLS_URL="${HLS_PAGE_URL}/index.m3u8"
PUBLISHER_PID=""
HLS_FILE=""
STREAM_FILE=""

cleanup() {
  if [[ -n "${PUBLISHER_PID}" ]]; then
    kill "${PUBLISHER_PID}" >/dev/null 2>&1 || true
    wait "${PUBLISHER_PID}" >/dev/null 2>&1 || true
  fi
  [[ -n "${HLS_FILE}" ]] && rm -f "${HLS_FILE}" || true
  [[ -n "${STREAM_FILE}" ]] && rm -f "${STREAM_FILE}" || true
  "${COMPOSE[@]}" logs --no-color mediamtx 2>/dev/null | tail -n 160 || true
  "${COMPOSE[@]}" down --remove-orphans >/dev/null 2>&1 || true
}
trap cleanup EXIT

wait_for_tcp() {
  local host="$1"
  local port="$2"
  local timeout_seconds="$3"

  for ((attempt=1; attempt<=timeout_seconds; attempt++)); do
    if (echo >/dev/tcp/${host}/${port}) >/dev/null 2>&1; then
      echo "TCP ${host}:${port} is ready after ${attempt}s."
      return 0
    fi

    if ! "${COMPOSE[@]}" ps --status running --services | grep -qx 'mediamtx'; then
      echo "ERROR: MediaMTX container stopped while waiting for ${host}:${port}." >&2
      "${COMPOSE[@]}" ps >&2 || true
      "${COMPOSE[@]}" logs --no-color mediamtx >&2 || true
      return 1
    fi

    sleep 1
  done

  echo "ERROR: TCP ${host}:${port} did not become ready within ${timeout_seconds} seconds." >&2
  "${COMPOSE[@]}" ps >&2 || true
  "${COMPOSE[@]}" logs --no-color mediamtx >&2 || true
  return 1
}

echo "Starting MediaMTX..."
"${COMPOSE[@]}" --profile tv up -d mediamtx

echo "Waiting for MediaMTX RTMP listener readiness..."
wait_for_tcp 127.0.0.1 1935 60
echo "MediaMTX RTMP listener ready."

command -v ffmpeg >/dev/null 2>&1 || { echo "ffmpeg is required for the deterministic publisher test" >&2; exit 1; }
command -v ffprobe >/dev/null 2>&1 || { echo "ffprobe is required for HLS media verification" >&2; exit 1; }

# Keep the source alive long enough for the HLS muxer to create its initial
# init segment and media segments. HLS is segment based, so a very short
# publisher can disappear before a reader ever gets a stable playlist.
echo "Publishing deterministic H.264/AAC RTMP stream to ${RTMP_URL}..."
ffmpeg -hide_banner -loglevel error \
  -re \
  -f lavfi -i "testsrc2=size=640x360:rate=15" \
  -f lavfi -i "sine=frequency=1000:sample_rate=48000" \
  -t 30 \
  -c:v libx264 -preset ultrafast -tune zerolatency -pix_fmt yuv420p -g 30 \
  -c:a aac -b:a 128k -ar 48000 -ac 2 \
  -f flv "${RTMP_URL}" &
PUBLISHER_PID=$!

HLS_FILE="$(mktemp)"
STREAM_FILE="$(mktemp)"
hls_ready=false

echo "Waiting for MediaMTX to expose HLS output from the RTMP publisher..."
for attempt in {1..25}; do
  if ! "${COMPOSE[@]}" ps --status running --services | grep -qx 'mediamtx'; then
    echo "ERROR: MediaMTX stopped while waiting for HLS output." >&2
    exit 1
  fi

  # The HTML endpoint and playlist are both valid MediaMTX HLS read paths.
  # Probe the page first because it proves the HLS server can route the path;
  # then require the actual playlist used by FFmpeg/players.
  if curl --connect-timeout 1 --max-time 3 -fsS "${HLS_PAGE_URL}" -o /dev/null 2>/dev/null \
      && curl --connect-timeout 1 --max-time 3 -fsS "${HLS_URL}" -o "${HLS_FILE}" 2>/dev/null \
      && grep -q '^#EXTM3U' "${HLS_FILE}"; then
    hls_ready=true
    echo "HLS playlist ready after ${attempt}s."
    break
  fi

  sleep 1
done

if [[ "${hls_ready}" != true ]]; then
  echo "ERROR: MediaMTX did not expose a usable HLS playlist for the RTMP stream." >&2
  echo "HLS page status/body:" >&2
  curl -sS -D - --max-time 3 "${HLS_PAGE_URL}" -o /tmp/fadcam-hls-page.html >&2 || true
  echo "HLS playlist status/body:" >&2
  curl -sS -D - --max-time 3 "${HLS_URL}" -o /tmp/fadcam-hls-playlist.m3u8 >&2 || true
  echo "MediaMTX status:" >&2
  "${COMPOSE[@]}" ps >&2 || true
  exit 1
fi

echo "RTMP ingest -> HLS output confirmed. Verifying codecs..."
ffprobe -v error -read_intervals %+5 -i "${HLS_URL}" \
  -show_entries stream=codec_type,codec_name \
  -of csv=p=0 | tee "${STREAM_FILE}"

grep -q '^video,h264$' "${STREAM_FILE}"
grep -q '^audio,aac$' "${STREAM_FILE}"

echo "PASS: RTMP ingest -> MediaMTX -> HLS contains H.264 video and AAC audio."