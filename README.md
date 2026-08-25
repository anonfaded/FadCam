# FadCam Studio

**Professional Android multi-camera production, recording and streaming suite.**

FadCam Studio extends the FadCam camera engine with a broadcast-style production room for camera switching, program/preview monitoring, recording, graphics, audio controls, replay and remote production workflows.

## 📱 Verified Studio APK

### ⬇️ Get the latest verified FadCam Studio APK

**[Download the latest verified Studio APK](https://github.com/Trendyzima/FadCamvideo/releases/download/studio-latest/FadCam-Studio-release-universal.apk)**

**[Download the SHA-256 checksum](https://github.com/Trendyzima/FadCamvideo/releases/download/studio-latest/SHA256SUMS.txt)**

**[Open GitHub Actions verification](https://github.com/Trendyzima/FadCamvideo/actions/workflows/build-debug-apk.yml)**

The APK link above is populated only after the `Build and Verify FadCam Studio APK` workflow completes every blocking gate successfully. The rolling `studio-latest` release is replaced only by a verified build from the current `master` commit.

**Current production-room release:** the master branch now includes the verified TV production switcher refinements, **STREAMING REVIEW** program-monitor workflow, YouTube/Facebook/Twitch/Custom RTMP destinations, protected stream keys, RTMP/RTMPS validation, recording safety gates and GO LIVE/STOP LIVE controls. The APK link above is intentionally rolling so the next successful master verification automatically becomes the downloadable release.

The delivery build is **non-debuggable** and uses the normal `com.fadcam` application ID. This replaces the previous `com.fadcam.beta` debug package.

### Verification gates

The publication gate verifies:

- JVM unit tests
- Studio-specific lint audit
- Release APK compilation
- APK ZIP/container integrity
- AndroidManifest.xml and classes.dex presence
- Package ID: `com.fadcam`
- Release signature verification
- Rejection of the Android debug certificate
- 16 KB ZIP alignment
- Removal of legacy high-risk declarations from the Studio release manifest
- Debug-package marker absence
- SHA-256 checksum generation

> **CI signing note:** device-verification builds use a dedicated non-debug release key generated only on the ephemeral GitHub runner. That key is not stored in the repository. Before public distribution or Play Store submission, configure a protected production signing key and use Google Play App Signing where appropriate.

## 🎬 Studio capabilities

- Hidden, expandable TV production control room opened from the existing SCENES entry
- Persistent Preview / Program production workflow
- Multiview source bank for cameras, video, graphics, program and preview
- Camera 1 rear-camera input
- Camera 2 front-camera input
- Expandable multi-camera switcher architecture
- CUT / TAKE / AUTO switching with persisted transition duration
- CUT / DISSOLVE / FADE / WIPE transition selection
- Recording through FadCam's recording engine
- Graphics and lower-third workflow bridged into the recording pipeline
- Local HLS production output
- RTMP / RTMPS output architecture
- Dedicated LIVE OUTPUT panel inside the production control room
- Dedicated **STREAMING REVIEW** panel that previews the actual local HLS PROGRAM feed used by the RTMP bridge before GO LIVE
- Preflight checks for recording state, RTMP/RTMPS destination validity and protected stream-key readiness
- YouTube, Facebook, Twitch and Custom RTMP destinations
- Other RTMP-compatible destinations through a custom server URL
- Android Keystore protected stream keys
- GO LIVE / STOP LIVE controls with a recording safety gate
- RTMP/RTMPS server URL validation before saving a destination
- Audio input selection
- Replay of recorded media
- Remote production controls

## 💾 Recording storage destinations

Recording storage is now a live, verified three-option system:

- **Phone local storage** — records to FadCam's normal local destination.
- **SD card / memory card** — choose a writable folder on a removable SD volume through Android's Storage Access Framework. The permission is persisted and revalidated before use.
- **USB external storage** — choose a writable folder on a connected removable USB/OTG storage volume through the same secure picker. The app starts the picker on the best matching USB volume when Android exposes one and rejects obvious SD/USB mismatches.

The selected destination is stored as the active recording destination and is consumed by the existing camera, dual-camera and screen-recording storage pipeline. Switching destinations clears the storage-space cache and refreshes the Records/Home views.

Android does not expose a universal `isUsb` versus `isSd` flag across all manufacturers, so the implementation combines `StorageVolume.isRemovable()`, mounted-volume information, volume descriptions and the user's explicit destination choice rather than relying on a fragile filesystem-path guess. Android's Storage Access Framework is used so the app does not need broad `MANAGE_EXTERNAL_STORAGE` access.

## 🧰 Production tools

- Thermal Guardian — live battery-temperature monitoring with a configurable recording safety cutoff
- Audio Vision — microphone threshold detection with automatic recording start
- Scheduled Recording — one-shot exact alarms with automatic recording stop
- Profiles — save/load/delete producer recording presets
- Sound Meter — live microphone dB meter
- Sensor Dashboard — accelerometer, magnetometer, gyroscope and step-sensor availability
- Speedometer — GPS speed in km/h
- Clinometer — live device tilt angle
- Compass — sensor-fused heading
- Pedometer — hardware step counter
- Metal Detector — magnetic-field strength meter
- Parking Marker — save the current GPS position and open navigation
- QR Generator — generate production links/text as QR codes

The rolling APK link above is updated only by the verification workflow after the release build, tests, lint, integrity, signature and alignment gates pass.

## 🌐 Streaming

The Studio uses FadCam's local HLS production path and an FFmpeg RTMP/RTMPS bridge for external destinations. Stream keys are protected with Android Keystore storage.

From the hidden production room, tap **LIVE / STREAM** to open **LIVE OUTPUT • SOCIAL DESTINATIONS**. Select YouTube, Facebook, Twitch or Custom RTMP, confirm the RTMP/RTMPS server URL, enter the platform stream key, save the destination, then start RECORD.

Before **GO LIVE**, use **STREAMING REVIEW → REVIEW PROGRAM**. This starts the local HLS production output and displays the same PROGRAM feed that the RTMP bridge will consume. The panel also shows a preflight checklist for recording, destination URL and protected stream-key readiness. Only after the producer has reviewed the program should **GO LIVE** be pressed. **STOP LIVE** terminates the RTMP bridge and cleans up the review pipeline.

Use the RTMPS server URL and stream key supplied by the selected platform's live-control page. Never commit or share stream keys. The built-in presets configure the server URL; they do not pretend to perform platform-specific API authentication.

## 🧪 Verification-first development

Every production change is gated by `.github/workflows/build-debug-apk.yml`. A failed unit test, Studio lint audit, build, APK integrity check, package check, release-signature check or alignment check blocks APK publication.

The production streaming controller has unit coverage for RTMP/RTMPS destination validation. The Studio release workflow also compiles the streaming review UI and runs the full release/lint/package/signature/alignment gates. Verification is serialized per branch so stale builds cannot overwrite the rolling verified release.

## 🛠️ Build locally

```bash
git clone https://github.com/Trendyzima/FadCamvideo.git
cd FadCamvideo
git clone --depth 1 https://github.com/anonfaded/media3-patched.git /tmp/media3-patched
./gradlew test
./gradlew :app:lintDefaultRelease
./gradlew :app:assembleDefaultRelease
```

For a local release APK, provide `KEYSTORE_FILE`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, and `KEY_PASSWORD` in `local.properties`. The release build no longer falls back to the Android debug keystore.

## ⚠️ Installation / Play Protect

The previous Studio download was a debug/beta APK. The delivery pipeline now produces a **non-debuggable release APK** and no longer signs the release with the Android debug keystore. The Studio release manifest also removes the legacy accessibility screenshot service, broad storage/battery special access, and the system-wide overlay permission because those are not required by the Studio production room.

Play Protect still performs its own independent scan of sideloaded applications and may block an app downloaded from a browser or file manager if it classifies the app as unverified and sensitive. For production distribution, use a trusted channel such as Google Play and a protected production signing key.
