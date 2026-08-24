# FadCam Studio Verification

## CI/build recovery
- [x] Inspect CI workflow and cancellation behavior
- [x] Trace the failing unit test to the current implementation
- [x] Update the stale RecordingToggleActivity test
- [x] Verify RecordingToggleGuard logic in terminal
- [x] Verify debug package metadata expectation is `com.fadcam.beta`
- [x] Verify Gradle/AGP/Java configuration compatibility by source inspection
- [x] Ensure CI keeps unit tests as a blocking gate
- [x] Ensure CI does not cancel verification runs
- [x] Remove debug-keystore signing fallback
- [x] Generate an isolated non-debug CI release key and validate the signing task before compilation
- [x] Remove legacy high-risk release manifest declarations
- [x] Make APK verification use the installed Android build-tools version instead of a hard-coded path
- [x] Upgrade GitHub Actions checkout/artifact actions to current Node 24-compatible releases
- [x] Harden rolling `studio-latest` release/tag cleanup against stale-tag races
- [x] Upload the verified APK artifact before release publication
- [x] Prevent stale master runs from racing the rolling `studio-latest` release
- [x] Re-read the committed workflow after the fixes and verify all blocking gates are present
- [ ] Confirm the fresh GitHub Actions run is green
- [ ] Confirm APK artifact exists
- [ ] Confirm APK container/manifest/classes.dex checks pass
- [ ] Confirm APK signature and 16 KB alignment checks pass
- [ ] Confirm README APK URL resolves to the newly verified asset
- [ ] Install the verified APK on a physical Android device
- [ ] Verify Studio recording, graphics encoding, and RTMP output on device
