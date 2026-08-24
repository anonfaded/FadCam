# FadCam Studio Verification

- [x] Inspect CI workflow and cancellation behavior
- [x] Trace the failing unit test to the current implementation
- [x] Update the stale RecordingToggleActivity test
- [x] Verify RecordingToggleGuard logic in terminal
- [x] Verify debug package metadata expectation is `com.fadcam.beta`
- [x] Verify Gradle/AGP/Java configuration compatibility by source inspection
- [x] Ensure CI keeps unit tests as a blocking gate
- [x] Ensure CI does not cancel verification runs
- [ ] Confirm a fresh GitHub Actions run is green
- [ ] Confirm APK artifact exists
- [ ] Confirm APK container/manifest/classes.dex checks pass
- [ ] Install the verified APK on a physical Android device
- [ ] Verify Studio recording, graphics encoding, and RTMP output on device
