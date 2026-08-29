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

"${COMPOSE[@]}" up -d ffmpeg-publisher
node tests/media-pipeline/test.mjs

# Alpine is the filesystem inspection helper. The MinIO mc image is intentionally
# a client image and must not be assumed to contain /bin/sh, find, or head.
REC_PATH=''
for i in {1..120}; do
  REC_PATH=$("${COMPOSE[@]}" run --rm -T recording-inspector 2>/dev/null \
    | awk -v stream="$STREAM" '$0 ~ ("/recordings/" stream "/") && $0 ~ /\.mp4$/ {print; exit}' \
    | tr -d '\r' | head -n 1 || true)
  if [[ -n "$REC_PATH" ]]; then break; fi
  sleep 1
done

if [[ -z "$REC_PATH" ]]; then
  echo 'FAIL: MediaMTX did not finalize an fMP4 recording'
  collect_logs
  exit 1
fi

echo "Recorded source: $REC_PATH"

"${COMPOSE[@]}" stop ffmpeg-publisher

# Use the FFmpeg container only as an FFmpeg executable; override its entrypoint
# to avoid relying on a shell in the image.
"${COMPOSE[@]}" run --rm -T --entrypoint ffmpeg ffmpeg-publisher \
  -y -i "$REC_PATH" -c:v libx264 -preset ultrafast -c:a aac /recordings/processed.mp4

# The mc image provides the mc executable directly; no shell is required.
"${COMPOSE[@]}" run --rm -T --entrypoint mc minio-uploader \
  alias set local http://minio:9000 fad-e2e fad-e2e-password
"${COMPOSE[@]}" run --rm -T --entrypoint mc minio-uploader \
  mb --ignore-existing local/$BUCKET
"${COMPOSE[@]}" run --rm -T --entrypoint mc minio-uploader \
  cp /recordings/processed.mp4 local/$BUCKET/$OBJECT

STATS=$("${COMPOSE[@]}" run --rm -T --entrypoint mc minio-uploader \
  stat --json local/$BUCKET/$OBJECT)
echo "$STATS"
node -e 'const s=JSON.parse(process.argv[1]); if (!s.size || s.size <= 0) process.exit(1); console.log(`PASS: MinIO object size=${s.size}`)' "$STATS"

echo 'PASS: FFmpeg -> MediaMTX recording -> FFmpeg processing -> MinIO object assertion'
