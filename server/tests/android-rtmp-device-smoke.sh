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
HLS_COOKIE_JAR="$(mktemp)"
HLS_PLAYLIST="$(mktemp)"

cleanup() {
  set +e
  if command -v adb >/dev/null 2>&1; then
    adb_target force-stop "${PACKAGE}" >/dev/null 2>&1 || true
    adb_target reverse --remove tcp:1935 >/dev/null 2>&1 || true
  fi
  "${COMPOSE[@]}" logs --no-color mediamtx 2>/dev/null | tail -n 220 >&2 || true
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

refresh_hls_session() {
  # MediaMTX may redirect the first HLS request through its cookie-check
  # endpoint. Establish one client session and reuse its cookie for every
  # playlist/segment/ffprobe request instead of opening a new session per poll.
  curl -fsSL --connect-timeout 3 --max-time 8 \
    -c "${HLS_COOKIE_JAR}" -b "${HLS_COOKIE_JAR}" \
    "${HLS_URL}?cookieCheck=1" -o "${HLS_PLAYLIST}"
  grep -q '^#EXTM3U' "${HLS_PLAYLIST}"
}

cookie_header() {
  awk 'BEGIN{ORS=""} $0 !~ /^#/ && NF>=7 { printf "%s=%s; ", $6, $7 }' "${HLS_COOKIE_JAR}"
}

require_commands

DEVICE_LINE="$(adb_target devices | awk 'NR>1 && $2=="device" {print; exit}')"
[[ -n "${DEVICE_LINE}" ]] || { echo "ERROR: no authorized Android device is connected." >&2; exit 1; }

echo "Starting MediaMTX..."
"${COMPOSE[@]}" --profile tv up -d mediamtx
wait_for "MediaMTX RTMP listener" "timeout 2 bash -c 'exec 3<>/dev/tcp/127.0.0.1/1935'"

# Route the phone's loopback TCP port to the host MediaMTX instance. This makes
# rtmp://127.0.0.1:1935/fadcam on the phone deterministic and requires no LAN setup.
adb_target reverse tcp:1935 tcp:1935

# Build and install the debug variant that registers RtmpPublisherService via
# the debug manifest. The production manifest is intentionally untouched until
# the physical-device gate proves the publisher lifecycle.
echo "Building debug APK..."
(cd "${ROOT_DIR}" && ./gradlew :app:assembleDefaultDebug)
APK="$(find "${ROOT_DIR}/app/build/outputs/apk" -type f -name '*.apk' | sort | tail -n 1)"
[[ -n "${APK}" ]] || { echo "ERROR: debug APK was not produced." >&2; exit 1; }
adb_target install -r "${APK}" >/dev/null

# Debuggable builds allow the local device gate to grant the two capture
# permissions non-interactively. The service is still started from the visible
# RecordingStartActivity, satisfying Android's while-in-use FGS restriction.
adb_target shell pm grant "${PACKAGE}" android.permission.CAMERA || true
adb_target shell pm grant "${PACKAGE}" android.permission.RECORD_AUDIO || true

echo "Starting physical Android camera + microphone RTMP publisher..."
adb_target shell am start -n "${PACKAGE}/.RecordingStartActivity" \
  -a "com.fadcam.streaming.START_RTMP" \
  --es "com.fadcam.streaming.EXTRA_ENDPOINT" "${RTMP_ENDPOINT}" >/dev/null

wait_for "MediaMTX RTMP path online" \
  "curl -fsS '${API_URL}' | grep -q '\"name\":\"fadcam\"'"

wait_for "HLS muxer" \
  "curl -fsS 'http://127.0.0.1:9998/metrics?type=hls_muxers&path=fadcam' | grep -q 'hls_muxers'"

wait_for "HLS playlist" "refresh_hls_session"

COOKIE="$(cookie_header)"
[[ -n "${COOKIE}" ]] || { echo "ERROR: MediaMTX HLS cookie session was not established." >&2; exit 1; }

# ffprobe is the authoritative media assertion: it must see both tracks from
# the phone, not merely a syntactically valid playlist.
VIDEO_CODEC="$(ffprobe -v error -headers "Cookie: ${COOKIE}\r\n" -select_streams v:0 -show_entries stream=codec_name -of default=nw=1:nk=1 "${HLS_URL}" 2>/dev/null | head -n 1)"
AUDIO_CODEC="$(ffprobe -v error -headers "Cookie: ${COOKIE}\r\n" -select_streams a:0 -show_entries stream=codec_name -of default=nw=1:nk=1 "${HLS_URL}" 2>/dev/null | head -n 1)"
[[ "${VIDEO_CODEC}" == "h264" ]] || { echo "ERROR: Android video codec was '${VIDEO_CODEC}', expected h264." >&2; exit 1; }
[[ "${AUDIO_CODEC}" == "aac" ]] || { echo "ERROR: Android audio codec was '${AUDIO_CODEC}', expected aac." >&2; exit 1; }
echo "PASS: physical Android camera produced H.264."
echo "PASS: physical Android microphone produced AAC."

# Decode a short window from the actual phone stream. This proves the complete
# camera -> encoder -> RTMP -> MediaMTX -> HLS -> decoder path.
timeout 15 ffmpeg -hide_banner -loglevel error \
  -headers "Cookie: ${COOKIE}\r\n" \
  -i "${HLS_URL}" -t 5 -map 0:v:0 -map 0:a:0 -f null - >/dev/null

echo "PASS: physical Android H.264/AAC stream decoded through HLS."

# Keep the phone publisher alive while validating a second playlist/media read.
sleep 3
refresh_hls_session
curl -fsS "${API_URL}" | grep -q '"name":"fadcam"'
echo "PASS: physical Android publisher remained online."

echo "ANDROID CAMERA -> H.264 + AAC -> RTMP -> MediaMTX -> HLS PASS"
echo "Physical-device streaming core is proven."
