# Releasing FadCam and FadCam Lite

Checklist for shipping either app. Facts were verified against the code with
fdroidserver 2.4.6.

## Version model

All version keys live in `app/build.gradle.kts`.

| App | Keys | Example |
|---|---|---|
| Full | `versionCode` / `versionName` (defaultConfig) | `55` / `4.0.0` |
| Lite | `liteVersionCode` / `liteVersionName` | `2` / `1.0.0-beta1` |

Lite's beta number is **part of the version name** — Lite releases are the betas.
Full instead uses `DEBUG_VERSION_SUFFIX` (`-beta10.7.2`) on its debug build type.

That suffix is stripped per-variant for Lite debug builds. Without the strip, Lite
debug became `1.0.0-beta1-beta10.7.2`; the updater's parser cannot match that, so
it fell back to the plain-version pattern and read the app as stable `1.0.0`,
which meant beta updates were never offered.

### Shipping a stable after a beta

Remove the suffix from the version name and bump the code, since the beta already
consumed the previous one:

```kotlin
val liteVersionCode = 3
val liteVersionName = "1.0.0"
```

## Update-check mechanics

`UpdateCheckService.parseFeed()` classifies releases by **tag name**, not by the
GitHub prerelease flag:

```java
Pattern.compile("<id>[^<]*/(v[^<]+)</id>")
...
if (ver.contains("beta")) -> betaVer else -> stableVer
```

Consequences:

- The tag **must start with `v`**. `lite-v1.0.0-beta1` does not match at all.
- `v1.0.0-beta1` is treated as a beta regardless of the prerelease checkbox.
- A **draft** release never appears in `releases.atom`, so the updater sees
  nothing. Publish it.

`isNewerThan()` comparison, verified by replaying its logic:

| installed | repo has | update offered |
|---|---|---|
| `1.0.0-beta1` | `1.0.0-beta1` | no |
| `1.0.0-beta1` | `1.0.0-beta2` | yes |
| `1.0.0` (stable) | `1.0.0-beta1` | no |
| `1.0.0-beta1` | `1.0.0` | yes |

A stable install never receives a beta, and a beta install is told when the
stable release lands.

## Two tag namespaces

| Repo | Tag | Read by |
|---|---|---|
| `FadCam-Lite` (releases) | `v1.0.0-beta1` | the app's updater |
| `FadCam` main (source) | `lite-v1.0.0` | F-Droid |

The `lite-v` prefix is needed **only** in the main repo, where Lite shares a tag
namespace with Full's `^v[\d.]+$` pattern. A bare `v1.0.0` tag there would make
Full's F-Droid update check treat a Lite commit as a Full release. The releases
repo has no Full app, so plain `v...` is correct there — and required by the
updater's regex.

## Beta tags never reach F-Droid

Both check patterns reject `-beta`:

| Tag | Full `^v[\d.]+$` | Lite `^lite-v[\d.]+$` |
|---|---|---|
| `v4.0.0` | matches | no |
| `v4.0.0-beta10.6` | no | no |
| `lite-v1.0.0` | no | matches |
| `lite-v1.0.0-beta1` | no | no |

So a beta tag cannot trigger an F-Droid build or an update-check bump, on either
app. Beta tags in the main repo are harmless — they can be pushed or skipped
entirely; nothing depends on them.

## Shipping a Lite beta

1. Set `liteVersionName = "1.0.0-betaN"`, bump `liteVersionCode`.
2. `./gradlew :app:assembleLiteRelease`
3. Create the GitHub release in **`FadCam-Lite`**:
   - tag `v1.0.0-betaN` (starts with `v`)
   - **not a draft**; prerelease checked
   - attach `FadCam_lite_v1.0.0-betaN-universal.apk`
4. No tag is needed in the main repo for a beta.

## Shipping Lite stable

1. Drop the beta suffix from `liteVersionName` and bump `liteVersionCode`.
2. Build and release `v1.0.0` in `FadCam-Lite`.
3. Tag **`lite-v1.0.0`** in the main repo — the ref F-Droid builds from.
4. Send F-Droid the Lite metadata, with the version fields matching that tag.

## Shipping Full

Do **not** tag `v4.0.0` until Full actually ships. F-Droid only needs a commit SHA
to test-build, so the metadata review can start before the tag exists.

## Signing

Every variant — release and debug, both apps — is signed with the release
keystore from `local.properties` (`KEYSTORE_FILE`, `KEYSTORE_PASSWORD`,
`KEY_ALIAS`, `KEY_PASSWORD`).

There is intentionally **no fallback**. If `local.properties` or the keystore is
missing, the build fails at configuration time instead of quietly producing an
unsigned or debug-signed APK that cannot replace the previous install.

Because the debug variants keep the `.beta` application-id suffix
(`com.fadcam.beta`, `com.fadcam.lite.beta`), a beta install and a stable install
are separate apps on the device and can coexist.

## F-Droid builds from the tag, not from master

The metadata pins an exact `commit:` SHA. Later commits on master — including
version bumps — have no effect on what F-Droid builds.

Two rules follow:

- The version in `build.gradle.kts` **at the tagged commit** must match the tag,
  because the update check reads it from that tree.
- `UpdateCheckMode: Tags` only scans tags. Untagged master commits are ignored, so
  bumping a version on master without tagging does nothing until a matching tag
  is pushed.

## F-Droid specifics

- F-Droid builds from source. The `FadCam-Lite` repo only hosts release assets.
- Full's recipe needs the signing `prebuild` sed removed and the `output` glob
  updated before 4.0.0, because signing is now unconditional and the APK was
  renamed.
- Lite must not inherit Full's Fastlane listing: Lite metadata lives at
  `src/lite/fastlane/metadata/android/`, which F-Droid scans per flavor.
