#!/usr/bin/env bash
set -euo pipefail

COMPOSE=(docker compose -f deployment/docker-compose.e2e.yml)
STREAM=e2e-test
BUCKET=fad-e2e
OBJECT=recordings/${STREAM}/processed.mp4

cleanup() {
  "${COMPOSE[@]}" down -v --remove-orphans
}
trap cleanup EXIT

"${COMPOSE[@]}" up -d --build mediamtx gateway minio
"${COMPOSE[@]}" up -d ffmpeg-publisher

for i in {1..30}; do
  if curl -fsS http://localhost:8081/health >/dev/null && curl -fsS http://localhost:9997/v3/paths/list >/dev/null && curl -fsS http://localhost:9000/minio/health/live >/dev/null; then break; fi
  sleep 1
done

node tests/media-pipeline/test.mjs

# Give MediaMTX time to finalize the fMP4 recording after the 20-second publisher exits.
for i in {1..30}; do
  if docker run --rm --network host -v "$(pwd)/tests/media-pipeline:/work:ro" alpine:3.20 sh -c 'test -d /work' >/dev/null 2>&1; then :; fi
  if "${COMPOSE[@]}" exec -T mediamtx sh -c 'find /recordings/e2e-test -type f -name "*.mp4" | head -n 1' 2>/dev/null | grep -q .; then break; fi
  sleep 1
done

REC_PATH=$("${COMPOSE[@]}" exec -T mediamtx sh -c 'find /recordings/e2e-test -type f -name "*.mp4" | head -n 1' | tr -d '\r' | head -n 1)
if [[ -z "$REC_PATH" ]]; then
  echo "FAIL: MediaMTX did not finalize an fMP4 recording"
  "${COMPOSE[@]}" logs --no-color mediamtx ffmpeg-publisher
  exit 1
fi

echo "Recorded source: $REC_PATH"

# FFmpeg processes the MediaMTX-produced recording into a deterministic MP4 artifact.
"${COMPOSE[@]}" run --rm -T --entrypoint ffmpeg ffmpeg-publisher \
  -y -i "$REC_PATH" -c:v libx264 -preset ultrafast -c:a aac /recordings/processed.mp4

# The recorder volume is shared with a one-shot MinIO client. Create bucket and upload.
docker run --rm --network "$(basename "$(pwd)")_default" \
  -v recordings:/recordings:ro \
  minio/mc:latest sh -c \
  'mc alias set local http://minio:9000 fad-e2e fad-e2e-password && mc mb --ignore-existing local/'"$BUCKET"' && mc cp /recordings/processed.mp4 local/'"$BUCKET"'/'"$OBJECT"' && mc stat local/'"$BUCKET"'/'"$OBJECT"''

echo "PASS: FFmpeg -> MediaMTX recording -> FFmpeg processing -> MinIO object assertion"
