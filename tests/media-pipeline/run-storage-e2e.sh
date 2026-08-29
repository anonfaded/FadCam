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

# MediaMTX's official image is intentionally minimal and has no shell/find utility.
# Inspect the shared recording volume from the helper container instead.
for i in {1..45}; do
  if "${COMPOSE[@]}" run --rm -T minio-uploader sh -c 'find /recordings/e2e-test -type f -name "*.mp4" | head -n 1' 2>/dev/null | grep -q .; then break; fi
  sleep 1
done

REC_PATH=$("${COMPOSE[@]}" run --rm -T minio-uploader sh -c 'find /recordings/e2e-test -type f -name "*.mp4" | head -n 1' | tr -d '\r' | head -n 1)
if [[ -z "$REC_PATH" ]]; then
  echo "FAIL: MediaMTX did not finalize an fMP4 recording"
  "${COMPOSE[@]}" logs --no-color mediamtx ffmpeg-publisher
  exit 1
fi

echo "Recorded source: $REC_PATH"

# Process the MediaMTX recording with FFmpeg into a deterministic artifact.
"${COMPOSE[@]}" run --rm -T --entrypoint sh ffmpeg-publisher -c \
  "ffmpeg -y -i '$REC_PATH' -c:v libx264 -preset ultrafast -c:a aac /recordings/processed.mp4"

# Upload the processed artifact through the MinIO client on the Compose network.
"${COMPOSE[@]}" run --rm -T minio-uploader sh -c \
  "mc alias set local http://minio:9000 fad-e2e fad-e2e-password && mc mb --ignore-existing local/$BUCKET && mc cp /recordings/processed.mp4 local/$BUCKET/$OBJECT && mc stat local/$BUCKET/$OBJECT"

# Verify the object through MinIO's API-visible client, including a non-zero size.
STATS=$("${COMPOSE[@]}" run --rm -T minio-uploader sh -c \
  "mc stat --json local/$BUCKET/$OBJECT")
echo "$STATS"
node -e 'const s=JSON.parse(process.argv[1]); if (!s.size || s.size <= 0) process.exit(1); console.log(`PASS: MinIO object size=${s.size}`)' "$STATS"

echo "PASS: FFmpeg -> MediaMTX recording -> FFmpeg processing -> MinIO object assertion"
