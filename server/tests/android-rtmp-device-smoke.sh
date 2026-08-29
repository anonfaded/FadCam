#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
COMPOSE=(docker compose --env-file "${ROOT_DIR}/server/.env.example" -f "${ROOT_DIR}/server/docker-compose.yml")
PACKAGE="com.fadcam"
RTMP_ENDPOINT="${RTMP_ENDPOINT:-rtmp://127.0.0.1:1935/fadcam}"
HLS_URL="${HLS_URL:-http://127.0.0.1:8888/fadcam/index.m3u8}"
API_URL="http://127.0.0.1:9997/v3/paths/list"
DEVICE_SERIAL="${ANDROID_SERIAL:-}"
TIMEOUT_SECONDS="${ANDROID_RTMP_TIMEOUT:-60}"
SUSTAINED_SECONDS="${ANDROID_RTMP_SUSTAINED_SECONDS:-30}"
LIFECYCLE_CYCLES="${ANDROID_RTMP_LIFECYCLE_CYCLES:-2}"
HLS_COOKIE_JAR="$(mktemp)"
HLS_PLAYLIST="$(mktemp)"

cleanup() {
  set +e
  if command -v adb >/dev/null 2>&1; then
    adb_target force-stop "${PACKAGE}" >/dev/null 2>&1 || true
    adb_target reverse --remove tcp:1935 >/dev/null 2>&1 || true
  fi
  "${COMPOSE[@]}" logs --no-color mediamtx 2>/dev/null | tail -n 260 >&2 || true
  "${COMPOSE[@]}" down --remove-orphans >/dev/null 2>&1 || true
  rm -f "${HLS_COOKIE_JAR}" "${HLS_PLAYLIST}"
}
trap cleanup EXIT INT TERM

adb_target() {
  if [[ -n "${DEVICE_SERIAL}" ]]; then
    adb -s "${DEVICE_SERIAL}" "$@"
  else
    adb "$@"
  fi
}

require_commands() {
  local c
  for c in adb curl ffprobe ffmpeg timeout; do
    command -v "${c}" >/dev/null 2>&1 || { echo "ERROR: ${c} is required." >&2; exit 1; }
  done
}

wait_for() {
  local description="$1"
  local command_string="$2"
  local attempt
  echo "Waiting for ${description}..."
  for ((attempt=1; attempt<=TIMEOUT_SECONDS; attempt++)); do
    if eval "${command_string}" >/dev/null 2>&1; then
      echo "PASS: ${description}."
      return 0
    fi
    sleep 1
  done
  echo "ERROR: ${description} did not become ready within ${TIMEOUT_SECONDS}s." >&2
  return 1
}

wait_for_rtmp_online() {
  wait_for "MediaMTX RTMP path online" "curl -fsS '${API_URL}' | grep -q '\"name\":\"fadcam\"'"
}

wait_for_rtmp_offline() {
  wait_for "MediaMTX RTMP path offline" "! curl -fsS '${API_URL}' | grep -q '\"name\":\"fadcam\"'"
}

refresh_hls_session() {
  rm -f "${HLS_COOKIE_JAR}" "${HLS_PLAYLIST}"
  touch "${HLS_COOKIE_JAR}"
  curl -fsSL --connect-timeout 3 --max-time 8 \
    -c "${HLS_COOKIE_JAR}" -b "${HLS_COOKIE_JAR}" \
    "${HLS_URL}?cookieCheck=1" -o "${HLS_PLAYLIST}"
  grep -q '^#EXTM3U' "${HLS_PLAYLIST}"
}

cookie_header() {
  awk 'BEGIN{ORS=""} /^#HttpOnly_/ { sub(/^#HttpOnly_/, "") } $0 !~ /^#/ && NF>=7 { printf "%s=%s; ", $6, $7 }' "${HLS_COOKIE_JAR}"
}

start_publisher() {
  echo "Starting physical Android camera + microphone RTMP publisher..."
  adb_target shell am start -n "${PACKAGE}/.RecordingStartActivity" \
    -a "com.fadcam.streaming.START_RTMP" \
    --es "com.fadcam.streaming.EXTRA_ENDPOINT" "${RTMP_ENDPOINT}" >/dev/null
}

verify_hls() {
  wait_for "HLS muxer" \
    "curl -fsS 'http://127.0.0.1:9998/metrics?type=hls_muxers&path=fadcam' | grep -q 'hls_muxers'"
  wait_for "HLS playlist" "refresh_hls_session"

  COOKIE="$(cookie_header)"
  [[ -n "${COOKIE}" ]] || { echo "ERROR: MediaMTX HLS cookie session was not established." >&2; exit 1; }
  echo "PASS: MediaMTX HLS cookie session established."

  VIDEO_CODEC="$(ffprobe -v error -rw_timeout 5000000 \
    -cookies "${COOKIE}" \
    -select_streams v:0 -show_entries stream=codec_name \
    -of default=nw=1:nk=1 "${HLS_URL}" 2>/dev/null | head -n 1 || true)"
  AUDIO_CODEC="$(ffprobe -v error -rw_timeout 5000000 \
    -cookies "${COOKIE}" \
    -select_streams a:0 -show_entries stream=codec_name \
    -of default=nw=1:nk=1 "${HLS_URL}" 2>/dev/null | head -n 1 || true)"
  [[ "${VIDEO_CODEC}" == "h264" ]] || { echo "ERROR: Android video codec was '${VIDEO_CODEC}', expected h264." >&2; exit 1; }
  [[ "${AUDIO_CODEC}" == "aac" ]] || { echo "ERROR: Android audio codec was '${AUDIO_CODEC}', expected aac." >&2; exit 1; }
  echo "PASS: physical Android camera produced H.264."
  echo "PASS: physical Android microphone produced AAC."

  timeout 15 ffmpeg -hide_banner -loglevel error \
    -cookies "${COOKIE}" \
    -i "${HLS_URL}" -t 5 -map 0:v:0 -map 0:a:0 -f null - >/dev/null
  echo "PASS: physical Android H.264/AAC stream decoded through HLS."
}

require_commands

DEVICE_LINE="$(adb_target devices | awk 'NR>1 && $2=="device" {print; exit}')"
[[ -n "${DEVICE_LINE}" ]] || { echo "ERROR: no authorized Android device is connected." >&2; exit 1; }

if ! [[ "${LIFECYCLE_CYCLES}" =~ ^[0-9]+$ ]] || (( LIFECYCLE_CYCLES < 1 )); then
  echo "ERROR: ANDROID_RTMP_LIFECYCLE_CYCLES must be a positive integer." >&2
  exit 1
fi
if ! [[ "${SUSTAINED_SECONDS}" =~ ^[0-9]+$ ]] || (( SUSTAINED_SECONDS < 5 )); then
  echo "ERROR: ANDROID_RTMP_SUSTAINED_SECONDS must be an integer >= 5." >&2
  exit 1
fi

echo "Starting MediaMTX..."
"${COMPOSE[@]}" --profile tv up -d mediamtx
wait_for "MediaMTX RTMP listener" "timeout 2 bash -c 'exec 3<>/dev/tcp/127.0.0.1/1935'"
adb_target reverse tcp:1935 tcp:1935

echo "Building debug APK..."
(cd "${ROOT_DIR}" && ./gradlew :app:assembleDefaultDebug)
APK="$(find "${ROOT_DIR}/app/build/outputs/apk" -type f -name '*.apk' | sort | tail -n 1)"
[[ -n "${APK}" ]] || { echo "ERROR: debug APK was not produced." >&2; exit 1; }
adb_target install -r "${APK}" >/dev/null
adb_target shell pm grant "${PACKAGE}" android.permission.CAMERA || true
adb_target shell pm grant "${PACKAGE}" android.permission.RECORD_AUDIO || true

start_publisher
wait_for_rtmp_online
verify_hls

for ((cycle=1; cycle<=LIFECYCLE_CYCLES; cycle++)); do
  echo "Lifecycle torture cycle ${cycle}/${LIFECYCLE_CYCLES}: stopping Android publisher..."
  adb_target shell am force-stop "${PACKAGE}"
  wait_for_rtmp_offline
  echo "PASS: Android publisher stopped cleanly."

  start_publisher
  wait_for_rtmp_online
  verify_hls
  echo "PASS: Android publisher restarted successfully (cycle ${cycle})."
done

echo "Running sustained ${SUSTAINED_SECONDS}s physical Android H.264/AAC validation..."
COOKIE="$(cookie_header)"
timeout "$((SUSTAINED_SECONDS + 10))" ffmpeg -hide_banner -loglevel error \
  -cookies "${COOKIE}" \
  -i "${HLS_URL}" -t "${SUSTAINED_SECONDS}" -map 0:v:0 -map 0:a:0 -f null - >/dev/null
echo "PASS: physical Android publisher sustained HLS decode for ${SUSTAINED_SECONDS}s."

echo "PASS: physical Android lifecycle torture + sustained streaming gate completed."
echo "ANDROID CAMERA -> H.264 + AAC -> RTMP -> MediaMTX -> HLS PASS"
