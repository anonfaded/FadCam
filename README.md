# FadCam

**Privacy-focused Android multimedia suite for background video recording, dashcam/bodycam use, screen recording, live streaming, and remote camera control — ad-free and open-source.**

[![Android CI](https://github.com/Trendyzima/FadCam/actions/workflows/build-platform-android.yml/badge.svg)](https://github.com/Trendyzima/FadCam/actions/workflows/build-platform-android.yml)

FadCam is built around privacy, reliability, and practical mobile multimedia workflows. It can turn an Android device into a flexible recorder, streaming source, or remotely controlled camera while keeping the project open-source and free of advertising.

> [!WARNING]
> **Responsible use:** only record, monitor, stream, or remotely control devices and people when you have the necessary permission and when doing so complies with applicable law. FadCam does not support unauthorized surveillance or privacy violations.

---

## Contents

- [What FadCam does](#what-fadcam-does)
- [CI and release-grade validation](#ci-and-release-grade-validation)
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

The CI pipeline intentionally targets the project's supported **default** variants instead of invoking an unrestricted Gradle `test` task that could select an unsupported flavor/build-type combination.

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

> [!NOTE]
> The CI release APK is signed with a temporary validation key. It is **not** the production signing identity and should not be confused with a formally published release APK.

### Why the integration gate is explicit

The JVM integration/test gate runs:

```bash
./gradlew :app:testDefaultDebugUnitTest :app:testDefaultReleaseUnitTest --stacktrace --no-daemon
```

This keeps CI aligned with the supported Android variants and avoids accidental resolution of unsupported configurations.

The workflow also reports whether an Android `androidTest` source set exists. Device-backed instrumentation is not fabricated when the repository does not currently provide executable instrumentation fixtures.

---

## Where to get APK artifacts

### ⭐ Your normal CI APK location

For APKs produced by the engineering/release-validation pipeline, the usual place to get them is **GitHub Actions**:

**GitHub → FadCam → Actions → FadCam Android + Platform Validation → open a successful run → Artifacts**

[Open GitHub Actions](https://github.com/Trendyzima/FadCam/actions/workflows/build-platform-android.yml)

The workflow publishes these artifact packages:

| Artifact | Build | Use |
| --- | --- | --- |
| `fadcam-default-debug-apk` | Default Debug | Development and testing installs. |
| `fadcam-default-release-apk` | Default Release | Release-build validation and testing. |

### How to download one

1. Open the [FadCam Actions workflow](https://github.com/Trendyzima/FadCam/actions/workflows/build-platform-android.yml).
2. Open the successful run for the commit you want.
3. Scroll to the **Artifacts** section.
4. Download `fadcam-default-debug-apk` or `fadcam-default-release-apk`.
5. Extract the ZIP and install the APK on your compatible Android test device/emulator.

### Artifact retention

The current CI workflow retains these APK artifacts for **7 days**. They are therefore intended for short-lived CI validation, not permanent distribution.

If you need a binary that should remain available to users, publish it as a **GitHub Release** instead of relying on an Actions artifact.

### Actions artifacts vs Releases

**Actions artifact**

- Tied to a specific CI run.
- Best for testing a commit or pull request.
- Automatically retained for the configured retention period.
- The CI release APK uses an ephemeral validation signing key.

**GitHub Release asset**

- Intended for permanent user-facing distribution.
- Associated with a version/tag.
- Should use the project's proper production release-signing process.

This distinction is important: **the APK you normally download during development comes from Actions; the APK you distribute permanently should come from a formal release.**

---

## Getting released builds

### GitHub Releases

For permanent, versioned releases:

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
