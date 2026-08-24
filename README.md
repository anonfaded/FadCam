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
- APK signature verification
- 16 KB ZIP alignment
- Debug-package marker absence
- SHA-256 checksum generation

> **Signing note:** CI currently has a temporary release-signing fallback so the verified APK can be produced for device testing when the protected production keystore is not configured. Before public distribution or Play Store submission, configure a protected release keystore and remove the CI fallback.

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
./gradlew :app:lintDefaultRelease
./gradlew :app:assembleDefaultRelease
```

## ⚠️ Installation / Play Protect

The previous Studio download was a debug/beta APK. The new delivery pipeline produces a **non-debuggable release APK** instead. Play Protect still performs its own independent scan of sideloaded applications and can block apps it considers risky, especially when they request sensitive permissions. Google recommends using only permissions necessary for the app's core functionality and distributing through a trusted channel such as Google Play for production releases.

The Studio release manifest removes the legacy accessibility screenshot service plus broad storage and battery special-access declarations from the Studio APK. Camera, microphone, networking and required foreground-service capabilities remain because they are core to recording and live production.

## License

See `LICENSE` for the project's license.
