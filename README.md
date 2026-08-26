# FadCam Studio

**Professional Android multi-camera production, recording and streaming suite.**

FadCam Studio extends the FadCam camera engine with a broadcast-style production room for camera switching, program/preview monitoring, recording, graphics, audio controls, replay and remote production workflows.

## 📱 Verified Studio APK

### ⬇️ Get the latest verified FadCam Studio APK

**[Download the latest verified Studio APK](https://github.com/Trendyzima/FadCamvideo/releases/download/studio-latest/FadCam-Studio-release-universal.apk)**

**[Download the SHA-256 checksum](https://github.com/Trendyzima/FadCamvideo/releases/download/studio-latest/SHA256SUMS.txt)**

**[Open the verified Studio release page](https://github.com/Trendyzima/FadCamvideo/releases/tag/studio-latest)**

The APK above is the rolling verified release from `master`. The current build was compiled, unit-tested, signed, package-checked, ZIP-alignment-checked and integrity-verified by GitHub Actions before publication. The APK is non-debuggable and uses the normal `com.fadcam` application ID.

**Verified delivery currently published:**
- Release tag: `studio-latest`
- Verified master commit: `acb346a4f2b2dcd12b7f85b54bff73b572597d0e`
- Version: `4.0.0` (`versionCode 52`)
- APK: `FadCam-Studio-release-universal.apk`
- APK SHA-256: `01eadd836f7b4cd80a2211596064ceabde305460d169c8baaee2cca6afb15a01`
- Release status: **Verified prerelease**

### Build verification completed

- Gradle task discovery: **PASS**
- Release unit tests: **PASS**
- `assembleDefaultRelease`: **PASS**
- Universal APK selection: **PASS**
- Package ID `com.fadcam`: **PASS**
- Non-debuggable APK: **PASS**
- APK Signature Scheme v2: **PASS**
- ZIP alignment: **PASS**
- APK container integrity: **PASS**
- `classes.dex` present: **PASS**
- SHA-256 checksum generated: **PASS**

The APK is distributed as a GitHub Release asset rather than committed to Git, avoiding GitHub's 100 MB repository file limit.

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
