# FadCam

**Privacy-focused Android multimedia suite for background video recording, dashcam/bodycam use, screen recording, live streaming, and remote camera control — ad-free and open-source.**

[![Android CI](https://github.com/Trendyzima/FadCam/actions/workflows/build-platform-android.yml/badge.svg)](https://github.com/Trendyzima/FadCam/actions/workflows/build-platform-android.yml)

## 📦 APK DOWNLOAD CENTER

> **Public latest CI APKs**
>
> These buttons download the APK files from the public **`latest-ci` GitHub Release**. They do **not** call GitHub's authenticated Actions-artifact API, so clicking them from the README works for public repository visitors.

| Build | Use | Download |
| --- | --- | --- |
| 🟢 **Default Debug APK** | Development, device testing, smoke testing | **[⬇️ Download Debug APK](https://github.com/Trendyzima/FadCam/releases/download/latest-ci/FadCam-debug.apk)** |
| 🔵 **Default Release APK** | Release-build validation / installation testing | **[⬇️ Download Release APK](https://github.com/Trendyzima/FadCam/releases/download/latest-ci/FadCam-release.apk)** |
| 🔐 **SHA-256 checksums** | Verify downloaded APK integrity | **[⬇️ Download SHA256SUMS](https://github.com/Trendyzima/FadCam/releases/download/latest-ci/SHA256SUMS.txt)** |

### 🚀 How the APKs get here

```text
master push
    ↓
FadCam Android + Platform Validation
    ↓
Debug APK ─────────┐
Release APK ───────┼──→ public `latest-ci` GitHub Release
SHA-256 ───────────┘              ↓
                           README download buttons
```

Every successful `master` validation build updates the public `latest-ci` release assets with the newly validated Debug and Release APKs. The APK files themselves are uploaded as normal GitHub Release assets, not exposed through the authenticated Actions artifact API.

### 🔐 Current public build

The `latest-ci` release is automatically updated by CI. Its assets are:

- `FadCam-debug.apk`
- `FadCam-release.apk`
- `SHA256SUMS.txt`

**[🚀 Open Latest CI Release](https://github.com/Trendyzima/FadCam/releases/tag/latest-ci)** · **[🧪 Open Build Pipeline](https://github.com/Trendyzima/FadCam/actions/workflows/build-platform-android.yml)** · **[📦 View Permanent Releases](https://github.com/Trendyzima/FadCam/releases)**

> [!IMPORTANT]
> `latest-ci` is a rolling **pre-release validation channel**, not a versioned production release. The Default Release APK is signed with an ephemeral CI validation key. Use a versioned GitHub Release for permanent/user-facing production builds.

---

FadCam is built around privacy, reliability, and practical mobile multimedia workflows. It can turn an Android device into a flexible recorder, streaming source, or remotely controlled camera while keeping the project open-source and free of advertising.

> [!WARNING]
> **Responsible use:** only record, monitor, stream, or remotely control devices and people when you have the necessary permission and when doing so complies with applicable law. FadCam does not support unauthorized surveillance or privacy violations.

---

## Contents

- [APK Download Center](#-apk-download-center)
- [What FadCam does](#what-fadcam-does)
- [CI and release-grade validation](#ci-and-release-grade-validation)
- [APK build archive](#apk-build-archive)
- [Where to get APK artifacts](#where-to-get-apk-artifacts)
- [Getting released builds](#getting-released-builds)
- [Building locally](#building-locally)
- [Streaming validation](#streaming-validation)
- [Project structure](#project-structure)
- [Development workflow](#development-workflow)
- [Contributing](#contributing)
- [Support](#support)
- [License](#license)

---

## What FadCam does

FadCam is a privacy-focused Android multimedia application with support for:

- Background video recording.
- Dashcam/bodycam-style recording workflows.
- Screen recording and annotation.
- Live streaming over supported networks.
- RTMP publishing and MediaMTX/HLS integration.
- Remote camera control and monitoring workflows.
- Ad-free, open-source development.

The project is intended for legitimate recording, documentation, personal security, content creation, testing, and other authorized uses.

---

## CI and release-grade validation

The repository uses the **FadCam Android + Platform Validation** GitHub Actions workflow as the main release-oriented validation pipeline.

[Open the FadCam Android + Platform Validation workflow](https://github.com/Trendyzima/FadCam/actions/workflows/build-platform-android.yml)

Pull requests targeting `master` and pushes to the configured validation branches run the relevant platform gates.

### Validation gates

| Gate | Purpose |
| --- | --- |
| **Android debug APK** | Runs the default debug unit tests and assembles a debug APK. |
| **Android default release APK** | Assembles the default release APK and verifies its structure, alignment, and signature. |
| **Existing JVM integration/test suite** | Runs the supported default debug and release JVM test suites explicitly. |
| **Server Compose configuration** | Validates `server/docker-compose.yml` with `server/.env.example`. |
| **RTMP → MediaMTX → HLS E2E** | Exercises the deterministic streaming path with the repository smoke-test harness. |
| **Public latest APK release** | Publishes the validated Debug/Release APKs and checksums as public GitHub Release assets after all required Android build jobs succeed. |

### Public APK publishing

For pushes to `master`, the `publish-latest-apks` job waits for both Android APK jobs to pass, downloads their CI artifacts internally, renames the binaries to stable public filenames, generates SHA-256 checksums, and publishes them to the rolling `latest-ci` GitHub Release.

This separation is intentional:

```text
Actions artifact
  = internal CI transport / run-specific artifact

GitHub Release asset
  = public, browser-downloadable APK
```

GitHub's Actions artifact download endpoint is authenticated and therefore must not be used as a direct public README download URL. The README instead points to the public Release asset URLs.

### Release APK validation

The release job:

1. Uses Java 17.
2. Prepares the patched Media3 dependency tree used by the project.
3. Generates an **ephemeral CI-only signing key**.
4. Builds `assembleDefaultRelease`.
5. Checks that APK files exist and are structurally valid.
6. Runs `zipalign` validation.
7. Runs `apksigner verify`.
8. Uploads the validated APK as a GitHub Actions artifact.
9. The public publishing job subsequently exposes the validated binary as a GitHub Release asset.

> [!NOTE]
> The CI release APK is signed with a temporary validation key. It is **not** the production signing identity and should not be confused with a formally published production release.

### Why the integration gate is explicit

The JVM integration/test gate runs:

```bash
./gradlew :app:testDefaultDebugUnitTest :app:testDefaultReleaseUnitTest --stacktrace --no-daemon
```

This keeps CI aligned with the supported Android variants and avoids accidental resolution of unsupported configurations.

The workflow also reports whether an Android `androidTest` source set exists. Device-backed instrumentation is not fabricated when the repository does not currently provide executable instrumentation fixtures.

---

## APK build archive

This section is the project's **single source of truth for where development APKs come from**.

### Public latest APKs

The fastest download location is the top-of-README **APK Download Center**. Those links always target the current assets of the rolling `latest-ci` release:

- [Debug APK](https://github.com/Trendyzima/FadCam/releases/download/latest-ci/FadCam-debug.apk)
- [Release APK](https://github.com/Trendyzima/FadCam/releases/download/latest-ci/FadCam-release.apk)
- [SHA-256 checksums](https://github.com/Trendyzima/FadCam/releases/download/latest-ci/SHA256SUMS.txt)

### Every CI APK generation

Every validation run that successfully builds an APK still publishes the original binary as a GitHub Actions artifact attached to that exact run. The successful `master` pipeline additionally republishes the validated binaries as the public `latest-ci` release assets.

This gives us two traceability paths:

```text
commit → workflow run → Actions artifact
commit → successful master validation → latest-ci public asset
```

The public rolling asset is convenient for installation. The run-specific artifact remains useful when exact historical provenance is required.

**Build archive / run history:**

https://github.com/Trendyzima/FadCam/actions/workflows/build-platform-android.yml

### Artifact naming

| Artifact | Public asset | Variant | Purpose |
| --- | --- | --- | --- |
| `fadcam-default-debug-apk` | `FadCam-debug.apk` | `DefaultDebug` | Development, smoke testing, and device installation. |
| `fadcam-default-release-apk` | `FadCam-release.apk` | `DefaultRelease` | Release-build validation and installation testing. |

### Build record to preserve

When recording a build for testing or reporting, preserve these values:

```text
Commit SHA
Workflow run number
Artifact name
Public release tag
APK variant
SHA-256
```

### Important retention rule

The original Actions artifacts are temporary. The workflow currently retains them for **7 days**. The public `latest-ci` Release assets are the convenient rolling download channel and are replaced by each successful `master` validation run.

For a permanent binary, create a versioned GitHub Release and attach the appropriately production-signed APK.

---

## Where to get APK artifacts

### ⭐ Fastest: download from the top of this README

Use the **APK DOWNLOAD CENTER** at the top of this page. Its Debug and Release buttons point directly to public GitHub Release assets.

### ⭐ Public rolling CI channel

[Open the latest CI release](https://github.com/Trendyzima/FadCam/releases/tag/latest-ci)

The public assets are:

```text
FadCam-debug.apk
FadCam-release.apk
SHA256SUMS.txt
```

### 🔬 Exact historical build artifact

For a specific commit/run:

**GitHub → FadCam → Actions → FadCam Android + Platform Validation → successful run → Artifacts**

[Open the FadCam Actions build archive](https://github.com/Trendyzima/FadCam/actions/workflows/build-platform-android.yml)

### Which APK should I use?

**For normal development/testing:**

```text
FadCam-debug.apk
```

**For testing the release build:**

```text
FadCam-release.apk
```

The release APK is CI-signed with a short-lived validation key. It is suitable for validation, **not** as the project's permanent production APK.

### Actions artifacts vs public rolling release vs permanent release

| | Actions artifact | `latest-ci` release asset | Versioned GitHub Release |
| --- | --- | --- | --- |
| Best for | Exact run/commit testing | Easy public CI installation | User distribution |
| URL | Run-specific/authenticated | Stable public asset URL | Stable versioned URL |
| Retention | 7 days | Rolling | Permanent until removed |
| Signing | CI validation key | CI validation key | Production release key |
| Recommended for users | No | Testing only | Yes |

---

## Getting released builds

### GitHub Releases

https://github.com/Trendyzima/FadCam/releases

### F-Droid

https://f-droid.org/packages/com.fadcam/

### IzzyOnDroid

https://apt.izzysoft.de/packages/com.fadcam

### SourceForge

https://sourceforge.net/projects/fadcam/files/

Use official distribution channels whenever possible and verify the origin of any APK before installation.

---

## Building locally

### Requirements

The current Android build environment expects:

- Java 17.
- Android SDK configured for the project.
- Git.
- A compatible Android/Gradle development environment.

### Clone

```bash
git clone https://github.com/Trendyzima/FadCam.git
cd FadCam
chmod +x ./gradlew
```

### Run default debug unit tests

```bash
./gradlew :app:testDefaultDebugUnitTest
```

### Build the default debug APK

```bash
./gradlew assembleDefaultDebug
```

Output:

```text
app/build/outputs/apk/default/debug/
```

### Build the default release APK

```bash
./gradlew assembleDefaultRelease
```

Output:

```text
app/build/outputs/apk/default/release/
```

For a production release, use the project's real release-signing configuration. Do not reuse the temporary signing setup used by CI validation.

### Run the supported JVM test suites

```bash
./gradlew :app:testDefaultDebugUnitTest :app:testDefaultReleaseUnitTest
```

---

## Streaming validation

FadCam's platform validation includes a deterministic RTMP → MediaMTX → HLS path:

```text
FadCam / RTMP publisher
          │
          ▼
       MediaMTX
          │
          ▼
          HLS
          │
          ▼
     HLS probe/client
```

The server-side configuration lives under `server/`, with the deterministic smoke test at:

```text
server/tests/rtmp-e2e-smoke.sh
```

### Validate Compose locally

```bash
docker compose --env-file server/.env.example -f server/docker-compose.yml config --quiet
```

### Run the RTMP → HLS smoke test

```bash
chmod +x server/tests/rtmp-e2e-smoke.sh
./server/tests/rtmp-e2e-smoke.sh
```

This validates the streaming infrastructure without requiring a physical Android device.

---

## Project structure

```text
FadCam/
├── app/                              # Android application
├── server/                           # Server/deployment configuration
│   └── tests/                        # Streaming integration/smoke tests
├── .github/
│   └── workflows/                    # GitHub Actions validation
├── gradle/                           # Gradle support
├── gradlew                           # Gradle wrapper
├── build.gradle.kts                  # Root Gradle configuration
├── CHANGELOG.md                      # Project change history
├── PRIVACY.md                        # Privacy information
├── LICENSE                           # License
└── README.md                         # Project documentation
```

The exact tree evolves as the project grows.

---

## Development workflow

When making changes, validate them in the same order as the release pipeline whenever practical:

1. Run the relevant unit tests.
2. Build the default debug APK.
3. Run the supported debug/release JVM test suites.
4. Validate Docker Compose when server configuration changes.
5. Run the RTMP → MediaMTX → HLS smoke test when streaming code/infrastructure changes.
6. Build and inspect the default release APK for release-related changes.
7. Push the change and let GitHub Actions validate the complete pipeline.
8. Confirm the public `latest-ci` assets after a successful `master` build when a public APK is needed.
9. Record the commit SHA and workflow run for every APK used in device testing.

### Android variants

Do not assume every flavor/build-type combination is supported. The release-grade CI pipeline deliberately uses the explicit default variants that match the project's dependency graph.

### Patched Media3

CI prepares the patched Media3 source tree from:

https://github.com/anonfaded/media3-patched

Changes affecting Media3 integration should be tested against the same patched dependency setup used by CI.

---

## Responsible use and privacy

FadCam can access sensitive device capabilities such as the camera, microphone, screen, storage, and network. Users should review Android permissions and use the application responsibly.

Do not use FadCam for unauthorized recording, surveillance, interception, or privacy violations. Local laws and consent requirements vary by jurisdiction.

For the project's privacy documentation, see [`PRIVACY.md`](PRIVACY.md).

---

## Contributing

Contributions are welcome, including:

- Android fixes and improvements.
- Streaming and MediaMTX improvements.
- CI/release engineering.
- Test coverage.
- Documentation.
- Performance and reliability work.

Before opening a pull request:

- Keep changes focused.
- Never commit secrets, private credentials, or production signing keys.
- Run the relevant tests locally.
- Validate server configuration for server changes.
- Run the streaming smoke test for RTMP/MediaMTX/HLS changes.
- Update documentation when developer or user workflows change.
- Record the exact commit/run used when testing an APK.
- Treat privacy and responsible use as first-class requirements.

Pull requests targeting `master` are checked by the release-oriented validation workflow.

---

## Support

- **Repository:** https://github.com/Trendyzima/FadCam
- **Issues:** https://github.com/Trendyzima/FadCam/issues
- **Pull requests:** https://github.com/Trendyzima/FadCam/pulls
- **Actions / CI:** https://github.com/Trendyzima/FadCam/actions/workflows/build-platform-android.yml

For project discussion and community support, use the official project channels listed in the repository.

---

## Credits

FadCam uses open-source projects and libraries throughout its Android and streaming stack. See the repository dependency manifests and source attributions for the authoritative third-party component list.

Patched Media3:

https://github.com/anonfaded/media3-patched

---

## License

See [`LICENSE`](LICENSE) for the authoritative license terms.

---

<div align="center">

**FadCam — privacy-focused multimedia tools for Android.**

</div>
