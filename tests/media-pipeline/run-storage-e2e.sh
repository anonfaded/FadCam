#!/usr/bin/env bash
set -euo pipefail

COMPOSE=(docker compose -f deployment/docker-compose.e2e.yml)
STREAM=e2e-test
BUCKET=fad-e2e
OBJECT=recordings/${STREAM}/processed.mp4

collect_logs() {
  echo '--- container status ---'
  "${COMPOSE[@]}" ps -a || true
  echo '--- MediaMTX logs ---'
  "${COMPOSE[@]}" logs --no-color mediamtx || true
  echo '--- Gateway logs ---'
  "${COMPOSE[@]}" logs --no-color gateway || true
  echo '--- FFmpeg publisher logs ---'
  "${COMPOSE[@]}" logs --no-color ffmpeg-publisher || true
  echo '--- MinIO logs ---'
  "${COMPOSE[@]}" logs --no-color minio || true
}

cleanup() {
  if [[ ${KEEP_E2E_STACK:-0} != 1 ]]; then
    "${COMPOSE[@]}" down -v --remove-orphans || true
  fi
}
trap cleanup EXIT

# Start the infrastructure first. The publisher is deliberately started only
# after every dependency is reachable, eliminating a startup race that could
# consume the short synthetic stream before the test begins discovery.
"${COMPOSE[@]}" up -d --build mediamtx gateway minio

ready=0
for i in {1..45}; do
  if curl -fsS http://localhost:8081/health >/dev/null \
    && curl -fsS -u 'any:' http://localhost:9997/v3/paths/list >/dev/null \
    && curl -fsS http://localhost:9000/minio/health/live >/dev/null; then
    ready=1
    break
  fi
  sleep 1
done

if [[ $ready != 1 ]]; then
  echo 'FAIL: media stack did not become ready'
  collect_logs
  exit 1
fi

# Keep the source alive long enough for service discovery and diagnostics.
"${COMPOSE[@]}" up -d ffmpeg-publisher

node tests/media-pipeline/test.mjs

# MediaMTX's official image is intentionally minimal and has no shell/find utility.
# Inspect the shared recording volume from the MinIO helper container.
REC_PATH=''
for i in {1..120}; do
  REC_PATH=$("${COMPOSE[@]}" run --rm -T --entrypoint /bin/sh minio-uploader -c \
    'find /recordings/e2e-test -type f -name "*.mp4" | head -n 1' 2>/dev/null | tr -d '\r' | head -n 1 || true)
  if [[ -n "$REC_PATH" ]]; then break; fi
  sleep 1
done

if [[ -z "$REC_PATH" ]]; then
  echo 'FAIL: MediaMTX did not finalize an fMP4 recording'
  collect_logs
  exit 1
fi

echo "Recorded source: $REC_PATH"

# Stop the finite publisher so MediaMTX finalizes the current recording segment.
"${COMPOSE[@]}" stop ffmpeg-publisher

# Process the real MediaMTX recording with FFmpeg into a deterministic artifact.
"${COMPOSE[@]}" run --rm -T --entrypoint sh ffmpeg-publisher -c \
  "ffmpeg -y -i '$REC_PATH' -c:v libx264 -preset ultrafast -c:a aac /recordings/processed.mp4"

# Upload the processed artifact through the MinIO client on the Compose network.
"${COMPOSE[@]}" run --rm -T --entrypoint /bin/sh minio-uploader -c \
  "mc alias set local http://minio:9000 fad-e2e fad-e2e-password && mc mb --ignore-existing local/$BUCKET && mc cp /recordings/processed.mp4 local/$BUCKET/$OBJECT && mc stat local/$BUCKET/$OBJECT"

# Verify the object through MinIO's client, including a non-zero size.
STATS=$("${COMPOSE[@]}" run --rm -T --entrypoint /bin/sh minio-uploader -c \
  "mc stat --json local/$BUCKET/$OBJECT")
echo "$STATS"
node -e 'const s=JSON.parse(process.argv[1]); if (!s.size || s.size <= 0) process.exit(1); console.log(`PASS: MinIO object size=${s.size}`)' "$STATS"

echo 'PASS: FFmpeg -> MediaMTX recording -> FFmpeg processing -> MinIO object assertion'
