#!/usr/bin/env bash
# Builds ../assets-upload/ — the exact files to attach to the fadcam-assets release.
#
# Nothing here compiles or patches a binary. It copies .so files straight out of the
# AARs we already ship, so every byte is identical to what the app runs today. The one
# intentional substitution is documented below.
#
# Re-run this any time; it wipes and rebuilds the output folder.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
OUT="$HERE/../assets-upload"
FFMPEG_AAR="$HERE/../../../../Documents/repos/github/FadCam/app/libs/ffmpeg-kit-full-6.0-2.LTS.aar"
OPENCV_AAR="${HOME}/.gradle/caches/modules-2/files-2.1/org.opencv/opencv/5.1.0/5c029fb7a2a2022b93d94101bfb2711885ad4254/opencv-5.1.0.aar"
MODELS="$HERE/../../../../Documents/repos/github/FadCam/app/src/full/assets/models"

# Resolve paths whether run from the worktree or the main checkout.
[[ -f $FFMPEG_AAR ]] || FFMPEG_AAR="$HERE/../app/libs/ffmpeg-kit-full-6.0-2.LTS.aar"
[[ -d $MODELS ]] || MODELS="$HERE/../app/src/full/assets/models"

EXTRACT="$(mktemp -d)"
trap 'rm -rf "$EXTRACT"' EXIT

echo "ffmpeg AAR: $FFMPEG_AAR"
echo "opencv AAR: $OPENCV_AAR"
echo "models    : $MODELS"
[[ -f $FFMPEG_AAR ]] || { echo "MISSING ffmpeg AAR"; exit 1; }
[[ -f $OPENCV_AAR ]] || { echo "MISSING opencv AAR"; exit 1; }

rm -rf "$OUT"
mkdir -p "$OUT"

unzip -q -o "$FFMPEG_AAR" 'jni/*' -d "$EXTRACT/ffmpeg"
unzip -q -o "$OPENCV_AAR" 'jni/*' -d "$EXTRACT/opencv"

build_bundle() {
  local abi="$1" outfile="$2"
  local stage="$EXTRACT/bundle-$abi"
  mkdir -p "$stage"
  # ffmpeg's own libs, straight out of the AAR.
  cp "$EXTRACT/ffmpeg/jni/$abi"/libav*.so "$stage"/
  cp "$EXTRACT/ffmpeg/jni/$abi"/libsw*.so "$stage"/
  cp "$EXTRACT/ffmpeg/jni/$abi"/libffmpegkit*.so "$stage"/
  # Intentional substitution: OpenCV's libc++_shared.so, not ffmpeg's.
  # The two AARs ship different builds of it. The spike proved ffmpeg runs with the
  # OpenCV build (returnCode=0), and one build shared by both bundles means the two
  # can never disagree about the C++ runtime. Costs 1.2 MB of duplication.
  cp "$EXTRACT/opencv/jni/$abi/libc++_shared.so" "$stage"/
  ( cd "$stage" && zip -9 -q -j "$OUT/$outfile" ./*.so )
  echo "built $outfile ($(du -h "$OUT/$outfile" | cut -f1))"
}

build_opencv() {
  local abi="$1" outfile="$2"
  local stage="$EXTRACT/opencv-$abi"
  mkdir -p "$stage"
  cp "$EXTRACT/opencv/jni/$abi/libopencv_java5.so" "$stage"/
  cp "$EXTRACT/opencv/jni/$abi/libc++_shared.so" "$stage"/
  ( cd "$stage" && zip -9 -q -j "$OUT/$outfile" ./*.so )
  echo "built $outfile ($(du -h "$OUT/$outfile" | cut -f1))"
}

build_opencv    arm64-v8a   opencv-5.1.0-arm64-v8a.zip
build_opencv    armeabi-v7a opencv-5.1.0-armeabi-v7a.zip
build_bundle    arm64-v8a   ffmpeg-kit-full-6.0.2-lts-arm64-v8a.zip
build_bundle    armeabi-v7a ffmpeg-kit-full-6.0.2-lts-armeabi-v7a.zip

# Models ship raw: .tflite compresses only ~9.5%, which is not worth making the user
# hold a 48 MB temp file and then a 48 MB copy while unzipping.
cp "$MODELS/rfdetr_nano_graph_a.tflite" "$OUT/rfdetr-nano-v1.0-graph-a.tflite"
cp "$MODELS/rfdetr_nano_graph_b.tflite" "$OUT/rfdetr-nano-v1.0-graph-b.tflite"

# License texts that travel with ffmpeg-kit, unmodified.
mkdir -p "$EXTRACT/licenses"
unzip -q -o "$FFMPEG_AAR" 'res/raw/*' -d "$EXTRACT/lr"
cp "$EXTRACT"/lr/res/raw/*.txt "$EXTRACT/licenses"/
( cd "$EXTRACT/licenses" && zip -9 -q "$OUT/LICENSES.zip" ./*.txt )

echo
echo "--- $OUT ---"
ls -la "$OUT" | awk 'NR>3 {printf "  %-52s %7.2f MB\n", $9, $5/1048576}'
