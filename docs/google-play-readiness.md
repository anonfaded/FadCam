# FadCam Google Play / Android Distribution Readiness

This document separates **things enforced by the build** from **things that only the developer can complete in Google Play Console / Android Developer Console**. A green CI build is not a Google Play approval and cannot guarantee that Play Protect will never warn about a particular APK.

## Build-enforced controls

- `compileSdk = 36` and `targetSdk = 36`.
- Public `release` builds are non-debuggable and minified.
- Public release builds use `app/src/release/AndroidManifest.xml` to remove broad storage access and battery-optimization special access that are not required for the core recording path.
- `MANAGE_EXTERNAL_STORAGE` is rejected by the Play-ready release gate.
- `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` is rejected by the Play-ready release gate.
- Release APKs are checked with `zipalign` and `apksigner`.
- Release APK target SDK is checked as API 36.
- A signed Android App Bundle (`.aab`) is built for Google Play.
- A stable release keystore is required by `.github/workflows/play-release-readiness.yml`; ephemeral CI keys are not accepted by that workflow.

## Google Play Console actions still required

### 1. Developer identity and package registration

Verify the developer identity and register `com.fadcam` in the Play Console / Android Developer Console. Google says package registration and verified developer identity are part of the Android developer verification rollout; check the Play Console status before distributing outside Play. Keep the same package name and signing ownership throughout the product lifetime.

### 2. Play App Signing

Use **Play App Signing** for the Play-distributed AAB. Keep the upload key separate from Google's Play app-signing key. Never commit either private key to the repository.

### 3. Privacy policy

Publish a real, current privacy policy at a stable HTTPS URL and link it both in Play Console and inside the app. The policy must describe camera, microphone, location, local media/files, network/streaming, diagnostics/analytics, third-party SDKs, retention, sharing, security, and user rights according to the app's actual behavior. Do not copy a generic policy without auditing the code and SDKs.

### 4. Data safety

Complete Play Console's Data safety form from the actual shipped behavior, including data handled by third-party SDKs. Re-check the declaration whenever a feature, SDK, telemetry path, cloud service, or streaming behavior changes.

### 5. Permissions

Keep only permissions required by the user-facing core functionality. The release manifest intentionally removes broad all-files access and battery-optimization special access. If a future feature genuinely requires a restricted permission, stop and complete the corresponding Play declaration/review rather than adding it casually.

The camera/microphone and applicable foreground-service permissions remain because recording/streaming is core functionality. Declare the foreground-service types and provide the required Play Console descriptions for the actual service behavior.

### 6. Target audience, content rating and ads

Complete the target-audience declaration, content-rating questionnaire, and ads declaration in Play Console. These are legal/policy declarations and cannot be truthfully generated from Gradle alone.

### 7. App access / reviewer instructions

If any feature requires credentials, special setup, a physical camera, network endpoint, or other reviewer-only preparation, provide complete reviewer instructions in Play Console. Do not hide restricted functionality from review.

### 8. Account deletion

If FadCam ever creates user accounts, provide an accessible account-deletion path and delete the associated user data according to the User Data policy. If there is no account system, do not invent one in the declaration.

### 9. Sensitive permissions and special access

`MANAGE_EXTERNAL_STORAGE`, `REQUEST_INSTALL_PACKAGES`, broad package visibility, SMS/call-log access, accessibility APIs, VPN APIs, and similar high-risk capabilities require special policy treatment. FadCam's public release gate currently rejects the first two if they appear in the merged release manifest. Any future addition must be justified against the current Play policy before merging.

### 10. Release tracks

Use this progression:

1. Internal testing
2. Closed testing
3. Open testing when appropriate
4. Production

Use the Play-generated APKs for real-device validation. Do not treat a GitHub Actions APK as equivalent to the APK Google Play generates from the AAB.

## Direct distribution / Play Protect

A signed APK can still be warned about or blocked by Play Protect. Play Protect is a device-side safety system, not a compile-time certification. The safest distribution path for general users is a Google Play release using Play App Signing.

For direct/off-Play distribution, complete Android developer verification and package registration requirements as they roll out, and distribute a stable-signed release rather than a debug or ephemeral-CI APK.

## Required GitHub secrets for the Play-ready workflow

Configure these repository/environment secrets before running `.github/workflows/play-release-readiness.yml`:

- `FADCAM_RELEASE_KEYSTORE_B64`
- `FADCAM_RELEASE_STORE_PASSWORD`
- `FADCAM_RELEASE_KEY_ALIAS`
- `FADCAM_RELEASE_KEY_PASSWORD`

The keystore should be a long-lived release/upload key controlled by the project owner. Never print it, commit it, or put its passwords in source control.

## Release acceptance criteria

A release is considered **engineering-ready** only when:

- JVM release tests pass.
- Release APK and AAB build successfully.
- Release manifest contains no prohibited broad-storage/battery permissions.
- APK is non-debuggable.
- APK is zip-aligned and signature-valid.
- Target SDK is 36 or higher.
- AAB is signed by the stable upload key.
- Physical-device recording, camera, microphone, background/foreground, and streaming smoke tests pass.

A release is considered **Google-Play-ready** only after the developer has also completed the Console-side declarations, identity/package registration, Play App Signing, privacy policy, Data safety, content rating, target audience, ads declaration, reviewer access instructions, and any required permission/foreground-service declarations.
