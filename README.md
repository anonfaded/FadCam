# FadCam Studio

**Professional Android multi-camera production, recording and streaming suite.**

FadCam Studio extends the FadCam camera engine with a broadcast-style production room for camera switching, program/preview monitoring, recording, graphics, audio controls, replay and remote production workflows.

## 📱 Verified Studio APK

### ⬇️ Get the latest verified FadCam Studio APK

**[Download the latest verified Studio APK](https://github.com/Trendyzima/FadCamvideo/releases/download/studio-latest/FadCam-Studio-release-universal.apk)**

**[Download the SHA-256 checksum](https://github.com/Trendyzima/FadCamvideo/releases/download/studio-latest/SHA256SUMS.txt)**

**[Open GitHub Actions verification](https://github.com/Trendyzima/FadCamvideo/actions/workflows/build-debug-apk.yml)**

The APK link above is a rolling release. It is replaced only after the current `master` commit passes the complete release verification workflow, including unit tests, release compilation, Studio lint audit, APK integrity, package/signature/alignment and manifest-hardening checks. The delivery APK is non-debuggable and uses the normal `com.fadcam` application ID.

**Verified delivery currently published:**
- Release tag: `studio-latest`
- Verified code target: `4735028c1e6ad93ce5ccff7fe61363e2b9b81d84`
- APK SHA-256: `fa15b4847611d20303c3e24b3af9c4cf7476ee5ecc67853d317c17d7f191b1cb`
- APK: `FadCam-Studio-release-universal.apk`

The latest README refresh is documentation-only and does not change the APK code. The download link above therefore remains the verified Studio build containing the TV production cockpit, streaming review and social-destination features.

## 🎬 Studio capabilities

- Hidden expandable TV production control room opened from the existing SCENES entry.
- Persistent Preview / Program workflow and multiview source bank.
- CUT / TAKE / AUTO switching with persisted transition timing.
- CUT / DISSOLVE / FADE / WIPE transition selection.
- Camera, video, graphics, Program and Preview source surfaces.
- Recording through FadCam's recording engine.
- Graphics and lower-third workflow bridged into the recording pipeline.
- Local HLS production output and RTMP / RTMPS output architecture.
- Dedicated LIVE OUTPUT and **STREAMING REVIEW** surfaces.
- YouTube, Facebook, Twitch and Custom RTMP destinations.
- Android Keystore-protected stream keys and RTMP/RTMPS URL validation.
- GO LIVE / STOP LIVE controls with a recording safety gate.
- Audio input selection, replay and remote production controls.

## 🎛️ Producer Cockpit — hidden broadcast drawer

The production room contains a subtle **⋮ cockpit control** in the header. Tap it when the producer needs the deeper control surface; the cockpit opens as a right-side drawer so the normal production UI stays clean.

The cockpit provides:

- **PROGRAM / PREVIEW monitors** with live tally emphasis.
- **Scene/source bank** for Camera, Video, Duet PiP, Duet Split, Commentary and Interview.
- **CUT • INSTANT** for the fastest synchronous Preview → Program switch path.
- AUTO switching and configurable transition speed.
- CUT, MIX, FADE, WIPE and FLASH transition modes in the cockpit control bus.
- **Virtual audio mixer** with Camera 1, Camera 2, Media and Master faders.
- Broadcast output status and a direct bridge into the verified social-destination panel.
- Producer safety/health indicators for recording, program state, destination and switch path.

The cockpit uses the same production state and verified RTMP service as the existing control room. It does not fabricate platform APIs or claim hardware capabilities that the Android device does not expose.

## 🌐 Streaming Review & Social Live

The Studio uses FadCam's local HLS production path and an FFmpeg RTMP/RTMPS bridge for external destinations. Stream keys are protected with Android Keystore storage.

From the production room, **LIVE / STREAM** opens **LIVE OUTPUT • SOCIAL DESTINATIONS**. Select YouTube, Facebook, Twitch or Custom RTMP, confirm the RTMP/RTMPS server URL, enter the platform stream key, save the destination, then start RECORD.

Before **GO LIVE**, use **STREAMING REVIEW → REVIEW PROGRAM**. This previews the local PROGRAM feed that the RTMP bridge consumes. The panel provides preflight checks for recording, destination URL and protected stream-key readiness. Only after review should GO LIVE be pressed.

Use the RTMPS server URL and stream key supplied by the selected platform's live-control page. Never commit or share stream keys. The built-in presets configure server URLs; they do not pretend to perform platform-specific API authentication.

## 💾 Recording storage destinations

Recording storage is a verified three-option system:

- **Phone local storage** — FadCam's normal local destination.
- **SD card / memory card** — writable removable folder through Android Storage Access Framework with persisted permission validation.
- **USB external storage** — writable removable USB/OTG folder through the same secure picker.

The selected destination is consumed by the existing camera, dual-camera and screen-recording pipeline. Storage-space caches are cleared when the destination changes. Android's Storage Access Framework is used instead of broad `MANAGE_EXTERNAL_STORAGE` access.

## 🧰 Production tools

- Thermal Guardian
- Audio Vision
- Scheduled Recording
- Producer Profiles
- Sound Meter
- Sensor Dashboard
- GPS Speedometer
- Clinometer
- Compass
- Pedometer
- Metal Detector
- Parking Marker
- QR Generator

## 🧪 Verification-first development

The release gate verifies:

- JVM unit tests, including the producer cockpit state tests.
- Studio lint audit.
- Release APK compilation.
- APK ZIP/container integrity.
- AndroidManifest.xml and classes.dex presence.
- Package ID `com.fadcam`.
- Release signature and rejection of the Android debug certificate.
- 16 KB ZIP alignment.
- Manifest hardening and removal of legacy high-risk declarations.
- SHA-256 checksum generation.

The dedicated producer-cockpit verification workflow also checks the cockpit source invariants and performs a clean signed release build, lint and APK-container verification using an ephemeral CI signing key.

> **CI signing note:** CI keys are generated only on ephemeral GitHub runners and are not stored in the repository. Configure a protected production signing key and Google Play App Signing before public store distribution.

## 🛠️ Build locally

```bash
git clone https://github.com/Trendyzima/FadCamvideo.git
cd FadCamvideo
git clone --depth 1 https://github.com/anonfaded/media3-patched.git /tmp/media3-patched
./gradlew test
./gradlew :app:lintDefaultRelease
./gradlew :app:assembleDefaultRelease
```

For a local release APK, provide `KEYSTORE_FILE`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, and `KEY_PASSWORD` in `local.properties`. The release build does not fall back to the Android debug keystore.

## ⚠️ Installation / Play Protect

Play Protect independently scans sideloaded applications. For production distribution, use a trusted channel such as Google Play and a protected production signing key.
