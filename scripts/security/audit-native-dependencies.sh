#!/usr/bin/env bash
set -euo pipefail

report="${1:-native-dependency-audit.txt}"
: > "$report"

section() {
  printf '\n## %s\n' "$1" | tee -a "$report"
}

section "Native dependency inventory"
grep -RInE --exclude-dir=.git --exclude='*.lock' \
  'ffmpeg-kit|RootEncoder|opencv|tensorflow|\.aar|\.so|jniLibs|ndkVersion' \
  app/build.gradle.kts gradle/libs.versions.toml app/src/main 2>/dev/null | tee -a "$report" || true

section "Known-risk findings"
if test -f app/libs/ffmpeg-kit-full-6.0-2.LTS.aar || grep -q 'ffmpeg-kit-full-6\.0-2\.LTS' app/build.gradle.kts 2>/dev/null; then
  echo "HIGH: bundled FFmpegKit 6.0-2.LTS is retired/upstream-unmaintained and is the highest-risk native dependency." | tee -a "$report"
  echo "ACTION: migrate to a maintained FFmpeg/FFmpegKit build after verifying ABI and runtime compatibility." | tee -a "$report"
else
  echo "OK: retired FFmpegKit 6.0-2.LTS bundle not detected." | tee -a "$report"
fi

opencv_version="$(awk -F'"' '/^opencvAndroid = / {print $2}' gradle/libs.versions.toml 2>/dev/null || true)"
if [[ -n "$opencv_version" ]]; then
  echo "INFO: OpenCV Android pinned at $opencv_version; compare against the current upstream release before the next native refresh." | tee -a "$report"
fi

rootencoder_version="$(grep -oE 'RootEncoder:library:[0-9.]+' app/build.gradle.kts 2>/dev/null | tail -1 | cut -d: -f3 || true)"
if [[ -n "$rootencoder_version" ]]; then
  echo "INFO: RootEncoder pinned at $rootencoder_version; verify release/security history before upgrading." | tee -a "$report"
fi

section "APK-native audit (when an APK exists)"
apk="$(find app/build/outputs/apk -type f -name '*.apk' 2>/dev/null | head -1 || true)"
if [[ -n "$apk" ]]; then
  echo "APK: $apk" | tee -a "$report"
  unzip -l "$apk" | grep -E 'lib/[^/]+/.*\.so$' | tee -a "$report" || true
else
  echo "No APK present; source-level audit only." | tee -a "$report"
fi

section "Policy"
echo "Report-only: no native library or ABI is silently changed by this audit." | tee -a "$report"
echo "Native upgrades require codec, JNI, ABI, and 16-KB-page compatibility validation." | tee -a "$report"

echo "Audit written to $report"
