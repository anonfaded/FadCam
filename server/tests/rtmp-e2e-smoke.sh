#!/usr/bin/env bash
set -euo pipefail

COMPOSE=(docker compose --env-file server/.env.example -f server/docker-compose.yml)
PATH_NAME="fadcam-e2e-$(date +%s)"
RTMP_URL="rtmp://127.0.0.1:1935/${PATH_NAME}"
HLS_URL="http://127.0.0.1:8888/${PATH_NAME}/index.m3u8"
API_URL="http://127.0.0.1:9997/v3/paths/list"

cleanup() {
  "${COMPOSE[@]}" stop mediamtx >/dev/null 2>&1 || true
}
trap cleanup EXIT

echo "Starting MediaMTX..."
"${COMPOSE[@]}" --profile tv up -d mediamtx

for _ in {1..30}; do
  if curl -fsS "${API_URL}" >/dev/null 2>&1; then
    break
  fi
  sleep 1
done
curl -fsS "${API_URL}" >/dev/null

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

stream_seen=false
for _ in {1..20}; do
  if curl -fsS "${API_URL}" | grep -q "${PATH_NAME}"; then
    stream_seen=true
    break
  fi
  sleep 1
done

if [[ "${stream_seen}" != true ]]; then
  echo "MediaMTX did not expose the published RTMP path" >&2
  wait "${PUBLISHER_PID}" || true
  exit 1
fi

echo "RTMP ingest confirmed. Waiting for HLS output..."
hls_file="$(mktemp)"
trap 'rm -f "${hls_file}"; cleanup' EXIT

hls_ready=false
for _ in {1..20}; do
  if curl -fsS "${HLS_URL}" -o "${hls_file}" 2>/dev/null && grep -q "#EXTM3U" "${hls_file}"; then
    hls_ready=true
    break
  fi
  sleep 1
done

if [[ "${hls_ready}" != true ]]; then
  echo "MediaMTX did not expose an HLS playlist for the RTMP stream" >&2
  wait "${PUBLISHER_PID}" || true
  exit 1
fi

ffprobe -v error -read_intervals %+5 -i "${HLS_URL}" \
  -show_entries stream=codec_type,codec_name \
  -of csv=p=0 | tee /tmp/fadcam-e2e-streams.txt

grep -q '^video,h264$' /tmp/fadcam-e2e-streams.txt
grep -q '^audio,aac$' /tmp/fadcam-e2e-streams.txt

wait "${PUBLISHER_PID}" || true

echo "PASS: RTMP ingest -> MediaMTX -> HLS contains H.264 video and AAC audio."
