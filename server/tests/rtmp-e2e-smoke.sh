#!/usr/bin/env bash
set -euo pipefail

COMPOSE=(docker compose --env-file server/.env.example -f server/docker-compose.yml)
PATH_NAME="fadcam"
RTMP_URL="rtmp://127.0.0.1:1935/${PATH_NAME}"
HLS_URL="http://127.0.0.1:8888/${PATH_NAME}/index.m3u8"
API_URL="http://127.0.0.1:9997/v3/paths/list"
PUBLISHER_PID=""
HLS_FILE=""

cleanup() {
  if [[ -n "${PUBLISHER_PID}" ]]; then
    kill "${PUBLISHER_PID}" >/dev/null 2>&1 || true
    wait "${PUBLISHER_PID}" >/dev/null 2>&1 || true
  fi
  [[ -n "${HLS_FILE}" ]] && rm -f "${HLS_FILE}" || true
  "${COMPOSE[@]}" logs --no-color mediamtx 2>/dev/null | tail -n 120 || true
  "${COMPOSE[@]}" down --remove-orphans >/dev/null 2>&1 || true
}
trap cleanup EXIT

echo "Starting MediaMTX..."
"${COMPOSE[@]}" --profile tv up -d mediamtx

echo "Waiting for MediaMTX API readiness..."
ready=false
for attempt in {1..60}; do
  if curl --connect-timeout 1 --max-time 2 -fsS "${API_URL}" >/dev/null 2>&1; then
    ready=true
    echo "MediaMTX API ready after ${attempt}s."
    break
  fi
  sleep 1
done

if [[ "${ready}" != true ]]; then
  echo "ERROR: MediaMTX API did not become ready within 60 seconds." >&2
  echo "Container status:" >&2
  "${COMPOSE[@]}" ps >&2 || true
  echo "MediaMTX logs:" >&2
  "${COMPOSE[@]}" logs --no-color mediamtx >&2 || true
  exit 1
fi

if ! command -v ffmpeg >/dev/null 2>&1; then
  echo "ffmpeg is required for the deterministic publisher test" >&2
  exit 1
fi

if ! command -v ffprobe >/dev/null 2>&1; then
  echo "ffprobe is required for HLS media verification" >&2
  exit 1
fi

echo "Publishing deterministic H.264/AAC RTMP stream to ${RTMP_URL}..."
ffmpeg -hide_banner -loglevel error \
  -re \
  -f lavfi -i "testsrc2=size=640x360:rate=15" \
  -f lavfi -i "sine=frequency=1000:sample_rate=48000" \
  -t 12 \
  -c:v libx264 -preset ultrafast -tune zerolatency -pix_fmt yuv420p -g 30 \
  -c:a aac -b:a 128k -ar 48000 -ac 2 \
  -f flv "${RTMP_URL}" &
PUBLISHER_PID=$!

echo "Waiting for MediaMTX to expose the RTMP path..."
stream_seen=false
for _ in {1..20}; do
  if curl --connect-timeout 1 --max-time 2 -fsS "${API_URL}" | grep -q '"name":"fadcam"'; then
    stream_seen=true
    break
  fi
  sleep 1
done

if [[ "${stream_seen}" != true ]]; then
  echo "ERROR: MediaMTX did not expose the published RTMP path." >&2
  exit 1
fi

echo "RTMP ingest confirmed. Waiting for HLS output..."
HLS_FILE="$(mktemp)"
hls_ready=false
for _ in {1..20}; do
  if curl --connect-timeout 1 --max-time 3 -fsS "${HLS_URL}" -o "${HLS_FILE}" 2>/dev/null && grep -q "#EXTM3U" "${HLS_FILE}"; then
    hls_ready=true
    break
  fi
  sleep 1
done

if [[ "${hls_ready}" != true ]]; then
  echo "ERROR: MediaMTX did not expose an HLS playlist for the RTMP stream." >&2
  exit 1
fi

ffprobe -v error -read_intervals %+5 -i "${HLS_URL}" \
  -show_entries stream=codec_type,codec_name \
  -of csv=p=0 | tee /tmp/fadcam-e2e-streams.txt

grep -q '^video,h264$' /tmp/fadcam-e2e-streams.txt
grep -q '^audio,aac$' /tmp/fadcam-e2e-streams.txt

echo "PASS: RTMP ingest -> MediaMTX -> HLS contains H.264 video and AAC audio."
