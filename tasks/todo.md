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

## Pro / Pro+ / Lab — unlocked implementation
- [x] Remove the payment/coming-soon gate from the feature center
- [x] Implement persistent custom app name for activity titles
- [x] Implement private local storage for user-uploaded branding icon
- [x] Implement a supported home-screen shortcut using the uploaded icon and custom name
- [x] Never persist the cloud password
- [x] Implement FadDrive connection test over WebDAV
- [x] Implement FadDrive bulk video upload with progress callbacks
- [x] Accept existing WebDAV FadCam collections (HTTP 405/409)
- [x] Reject unsafe WebDAV URLs containing embedded credentials, query strings, or fragments
- [x] Add unit tests for branding sanitization
- [x] Add unit tests for WebDAV URL/path validation
- [ ] Run clean release build and unit tests
- [ ] Inspect release lint/R8 diagnostics
- [ ] Verify APK contains the new branding and FadDrive classes
- [ ] Install on a physical device and test custom icon/name, shortcut, WebDAV connection, and a real upload
- [ ] Publish only after all blocking CI gates are green

## Scope note
Android does not allow a packaged application's manifest launcher icon or application label to be replaced arbitrarily at runtime. The unlocked custom-branding implementation therefore uses persistent in-app activity naming plus an Android-supported dynamic home-screen shortcut for arbitrary uploaded icon art. This is verified behavior rather than a fake runtime manifest change.
