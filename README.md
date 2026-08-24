# FadCam Studio

**Professional Android multi-camera production, recording and streaming suite.**

FadCam Studio extends the FadCam camera engine with a broadcast-style production room for camera switching, program/preview monitoring, recording, graphics, audio controls, replay and remote production workflows.

## 📱 Verified Studio APK

### [⬇️ Download FadCam Studio APK](https://github.com/Trendyzima/FadCamvideo/releases/download/studio-latest/FadCam-Studio-release-universal.apk)

This is the permanent README download location for the latest **verified Studio release APK**. GitHub Actions publishes it only after the verification pipeline passes. **APK only — not a ZIP.**

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

Every production change is gated by `.github/workflows/build-debug-apk.yml`. Verification runs are isolated by run ID so a burst of pushes cannot cancel the exact commit being verified. A failed unit test, Studio lint audit, build, APK integrity check, package check, release-signature check or alignment check blocks APK publication.

The current verification process isolates legacy project-wide lint debt from the Studio-specific gate; existing legacy findings are not allowed to hide Studio errors.

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

The previous Studio download was a debug/beta APK. The new delivery pipeline produces a **non-debuggable release APK** and no longer signs the release with the Android debug keystore. The Studio release manifest also removes the legacy accessibility screenshot service, broad storage/battery special access, and the system-wide overlay permission because those are not required by the Studio production room.

Play Protect still performs its own independent scan of sideloaded applications and may block an app downloaded from a browser or file manager if it classifies the app as unverified and sensitive. Google recommends trusted distribution such as Google Play for production releases. citeturn2search0turn2search5

## License

See `LICENSE` for the project's license.
