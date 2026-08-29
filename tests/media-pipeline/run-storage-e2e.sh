#!/usr/bin/env bash
set -euo pipefail

COMPOSE=(docker compose -f deployment/docker-compose.e2e.yml)
STREAM=e2e-test
BUCKET=fad-e2e
OBJECT=recordings/${STREAM}/processed.mp4
API_USER=api-e2e
API_PASSWORD=api-e2e-pass
API_URL=http://localhost:9997
MINIO_HOST=http://fad-e2e:fad-e2e-password@minio:9000

mc() {
  "${COMPOSE[@]}" run --rm -T -e "MC_HOST_e2e=${MINIO_HOST}" \
    --entrypoint mc minio-uploader "$@"
}

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
  # In CI the workflow's dedicated Collect logs step must inspect the live
  # containers after a failure. Local runs still clean up automatically.
  if [[ ${KEEP_E2E_STACK:-0} != 1 && ${E2E_CI:-0} != 1 ]]; then
    "${COMPOSE[@]}" down -v --remove-orphans || true
  fi
}
trap cleanup EXIT

"${COMPOSE[@]}" up -d --build mediamtx gateway minio

ready=0
for i in {1..60}; do
  if curl -fsS http://localhost:8081/health >/dev/null \
    && curl -fsS -u "${API_USER}:${API_PASSWORD}" "${API_URL}/v3/paths/list" >/dev/null \
    && curl -fsS http://localhost:9000/minio/health/live >/dev/null \
    && mc ls e2e >/dev/null 2>&1; then
    ready=1
    break
  fi
  sleep 1
done

if [[ $ready != 1 ]]; then
  echo 'FAIL: media stack did not become ready'
  echo 'FAIL: MediaMTX API authentication, Gateway, or MinIO S3 readiness did not succeed'
  collect_logs
  exit 1
fi

"${COMPOSE[@]}" up -d ffmpeg-publisher

# Verify the live publisher through the same path-list endpoint used by the
# gateway. This avoids treating the per-path GET endpoint as the discovery
# contract and prevents a transient GET 404 from masking a healthy stream.
stream_ready=0
for i in {1..45}; do
  if curl -fsS -u "${API_USER}:${API_PASSWORD}" "${API_URL}/v3/paths/list" \
    | node -e 'let s=""; process.stdin.on("data",d=>s+=d).on("end",()=>{const j=JSON.parse(s); process.exit(j.items?.some(x=>x?.name==="e2e-test")?0:1)})'; then
    stream_ready=1
    break
  fi
  sleep 1
done

if [[ $stream_ready != 1 ]]; then
  echo "FAIL: MediaMTX did not expose '${STREAM}' after publisher startup"
  collect_logs
  exit 1
fi

node tests/media-pipeline/test.mjs

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

"${COMPOSE[@]}" run --rm -T --entrypoint ffmpeg ffmpeg-publisher \
  -y -i "$REC_PATH" -c:v libx264 -preset ultrafast -c:a aac /recordings/processed.mp4

mc mb --ignore-existing "e2e/${BUCKET}"
mc cp /recordings/processed.mp4 "e2e/${BUCKET}/${OBJECT}"

STATS=$(mc stat --json "e2e/${BUCKET}/${OBJECT}")
echo "$STATS"
node -e 'const s=JSON.parse(process.argv[1]); if (!s.size || s.size <= 0) process.exit(1); console.log(`PASS: MinIO object size=${s.size}`)' "$STATS"

echo 'PASS: FFmpeg -> MediaMTX recording -> FFmpeg processing -> MinIO object assertion'
