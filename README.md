# FadCam Studio

**Professional Android multi-camera production, recording and streaming suite.**

FadCam Studio extends the FadCam camera engine with a broadcast-style production room for camera switching, program/preview monitoring, recording, graphics, audio controls, replay and remote production workflows.

## 📱 Verified Studio APK

### ⬇️ Get the latest verified FadCam Studio APK

**[Open the latest verified release](https://github.com/Trendyzima/FadCamvideo/releases)**

**[Open GitHub Actions verification](https://github.com/Trendyzima/FadCamvideo/actions/workflows/build-debug-apk.yml)**

The APK is published as a GitHub Release **only after every verification gate passes**. If the release list is temporarily empty, do **not** install an older debug/beta APK; open the Actions page and wait for the latest `Build and Verify FadCam Studio APK` run to finish successfully. The verified APK is published immediately after the green verification run.

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

- Preview / Program production workflow
- Camera 1 rear-camera input
- Camera 2 front-camera input
- Expandable multi-camera switcher architecture
- CUT / FADE / MIX transitions
- Recording through FadCam's recording engine
- Graphics and lower-third workflow bridged into the recording pipeline
- Local HLS production output
- RTMP / RTMPS output architecture
- YouTube, Facebook, Twitch and custom RTMP destinations
- Android Keystore protected stream keys
- Audio input selection
- Replay of recorded media
- Remote production controls

## 🌐 Streaming

The Studio uses FadCam's local HLS production path and an FFmpeg RTMP/RTMPS bridge for external destinations. Stream keys are protected with Android Keystore storage.

Use the RTMPS server URL and stream key supplied by the selected platform's live-control page.

## 🧪 Verification-first development

Every production change is gated by `.github/workflows/build-debug-apk.yml`. A failed unit test, Studio lint audit, build, APK integrity check, package check, release-signature check or alignment check blocks APK publication.

Verification is serialized per branch so stale builds cannot overwrite the rolling verified release.

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

## License

See `LICENSE` for the project's license.
