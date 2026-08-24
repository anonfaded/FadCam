# FadCam Studio

**Professional Android multi-camera production, recording and streaming suite.**

FadCam Studio extends the FadCam camera engine with a broadcast-style production room for camera switching, program/preview monitoring, recording, graphics, audio controls, replay and remote production workflows.

## 📱 Verified Studio APK

### [⬇️ Download FadCam Studio APK](https://github.com/Trendyzima/FadCamvideo/releases/download/studio-latest/FadCam-Studio-debug-universal.apk)

This is the permanent README download location for the latest **verified Studio APK**. GitHub Actions publishes the APK only after the verification pipeline passes. **APK only — not a ZIP.**

**Verification status:** the latest master verification pipeline must pass all blocking gates before `studio-latest` is published.

The publication gate verifies:

- JVM unit tests
- Studio-specific lint audit
- Debug APK compilation
- APK ZIP/container integrity
- AndroidManifest.xml and classes.dex presence
- Package ID: `com.fadcam.beta`
- APK signature verification
- 16 KB ZIP alignment
- SHA-256 checksum generation

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

Every production change is gated by `.github/workflows/build-debug-apk.yml`. Verification runs are intentionally not cancelled by later pushes. A failed unit test, Studio lint audit, build, APK integrity check, package check, signature check or alignment check blocks APK publication.

The current verification process isolates legacy project-wide lint debt from the Studio-specific gate; existing legacy findings are not allowed to hide Studio errors.

## 🛠️ Build locally

```bash
git clone https://github.com/Trendyzima/FadCamvideo.git
cd FadCamvideo
git clone --depth 1 https://github.com/anonfaded/media3-patched.git /tmp/media3-patched
./gradlew test
./gradlew :app:lintDefaultDebug
./gradlew :app:assembleDefaultDebug
```

## ⚠️ Debug APK notice

The Studio download is a debug/beta build for testing the production-room implementation. Android/Play Protect may apply additional checks to sideloaded debug applications. Do not disable device security protections merely to install an APK.

## License

See `LICENSE` for the project's license.
