# Downloadable Assets — Product Plan

**Status:** IN PROGRESS — load path proven, assets prepared, code not started
**Created:** 2026-11-23
**Updated:** 2026-11-27 (spike results + corrections folded in)
**Branch:** `feat/downloadable-assets` (own worktree, PRs per step)
**Scope:** Move heavy native binaries and models out of the APK into downloadable,
user-managed assets with an in-app download manager.

---

## 0. Verified by spike, 2026-11-27 — read this first

Everything below this section was written before anything was executed. The spike
(`app/src/androidTest/java/com/fadcam/AssetLoadSpikeTest.java`) replaced several
assumptions with measured facts. **Where this section disagrees with any later
section, this section wins.**

### 0.1 Spike result — all four cases pass on both physical devices

Built with opencv/ffmpeg/`libc++_shared` **completely absent from the APK**
(`-PspikeNoBundledNative=true`), so no bundled copy could satisfy the load.

| Case | Pixel 7 Pro (API 37) | TECNO KG5k (API 30) |
|---|---|---|
| OpenCV `System.load` from `filesDir` + run MOG2 | ✅ `5.1.0` | ✅ `5.1.0` |
| `OpenCVLoader.initLocal()` after that | ✅ `true` | ✅ `true` |
| ffmpeg `System.load` chain + `FFmpegKit.execute()` | ✅ `returnCode=0`, `6.0` | ✅ `returnCode=0`, `6.0` |
| `CompiledModel.create(absolutePath)` | ✅ | ✅ |

### 0.2 Correcting §2.1 — the old evidence proved nothing

`NativeLoadFromStorageTest` copies out **`libLiteRt.so`**, which is the single
easiest case: its only dependencies are `libdl libGLESv3 libEGL libm liblog libc`
— no C++ runtime — and LiteRT makes **zero** internal `System.loadLibrary` calls.
It was never evidence for OpenCV or ffmpeg. Replaced by the spike above.

### 0.3 The load mechanism — no fork, no patch, no reflection

`System.load(absolutePath)` makes the library resident under its soname; the
library's own internal `System.loadLibrary("opencv_java5")` / `System.loadLibrary("avcodec")`
then resolves to that already-resident copy via the linker's loaded-library list.
Proved with the **unmodified** `ffmpeg-kit-full-6.0-2.LTS.aar`.

So §5.2.5's "evaluate FFmpegKitNext / build our own wrapper" is **not needed**,
and no patched `NativeLoader` is required. Load order is the only contract:

```
libc++_shared.so  →  leaf libs  →  root libs
```

### 0.4 Correcting §2.2 — the read-only rule is NOT currently active

Real gating condition: **device API >= 37 AND `targetSdk >= 37`**.

We are `targetSdk = 36`, so on the API 37 Pixel a deliberately writable `.so`
**loaded successfully** (verified). `setReadOnly()` is therefore not mandatory
today.

**Decision: adopt it anyway.** It is one syscall, it is correct code on every API
level, and it becomes mandatory the day `targetSdk` reaches 37 (D1). `NativeReadOnlyRequirementTest`
now asserts the real gating condition so it fails loudly if Google changes it.

### 0.5 `libc++_shared.so` must ship inside the asset bundles

It is declared only by the OpenCV AAR. After Phase 3 nothing in the APK needs it
(LiteRT does not), so it must travel in the bundle and load first.

OpenCV's AAR and ffmpeg's AAR ship **different builds** of it. Both work — the
spike ran ffmpeg against OpenCV's copy with `returnCode=0`. **Ship OpenCV's copy
in both zips**, so the two bundles can never disagree about the C++ runtime.
Cost: 1.2 MB duplication. Do not build a dependency graph to save it.

### 0.6 AGP gotcha — `pickFirsts` overrides `excludes`

Excluding `**/libc++_shared.so` while `pickFirsts` also names it silently keeps
the file. The spike build had to drop the `pickFirsts` entry under the flag.
Expect this again in Phase 3.

### 0.7 Measured sizes (clean builds, libraries genuinely absent)

| APK | before | after removing opencv + ffmpeg + libc++ |
|---|---|---|
| arm64 | 150.6 MB | **91.1 MB** |
| universal | 201.3 MB | **101.6 MB** |

Universal still carries the 53.6 MB of models → ~48 MB once Phase 1 lands, which
meets the ~50–60 MB target.

### 0.8 Compression, measured

| Payload | Raw | Zipped | Saving | Decision |
|---|---|---|---|---|
| OpenCV arm64 | 32.42 MB | 11.95 MB | 63% | **zip** |
| ffmpeg arm64 | 24.63 MB | 11.99 MB | 51% | **zip** |
| Models | 53.62 MB | 48.53 MB | 9.5% | **ship raw** |

`.so` compresses heavily (symbol tables). `.tflite` does not. Zipping a 46 MB
model would need 46 MB temp + 46 MB final on disk for a 9.5% gain — not worth it.
Raw download also means the downloaded file *is* the verified artifact.

### 0.9 16 KB alignment — resolved, nothing to fix

Per [Support 16 KB page sizes](https://developer.android.com/guide/practices/page-sizes),
the requirement applies to **64-bit** ABIs only.

- **arm64-v8a: every `.so` is `p_align = 0x4000`** ✅
- **armeabi-v7a: OpenCV + its `libc++_shared` are `0x1000`** — harmless, 32-bit.

So §9/E14's CI gate should assert alignment for **arm64 only**, or it will fail
on correctly-built v7a libraries.

### 0.10 Open caveats

- **`armeabi-v7a` is unverified on hardware.** No 32-bit device is attached
  (both phones report arm64 first). Structure and file set are correct, but the
  v7a path has not been *run*. Deferred by decision; verify before Phase 3 ships.
- **`FFmpegKitConfig`'s static initialiser** calls `NativeLoader.loadFFmpeg()` the
  instant any `com.arthenica.ffmpegkit` class is touched, so the absolute-path
  loads must complete **before** the first ffmpeg class reference. This is a real
  ordering constraint on `AssetRegistry`, not a style preference.

### 0.11 Asset upload set — 6 binaries + 1 combined zip + 1 manifest + docs

**The build script and the model files now live in `fadsec-lab/fadcam-assets`, not
here.** FadCam's repo carries none of the heavy binaries any more, and neither does
its APK. The only thing FadCam commits is `app/src/full/assets/assets-manifest.json`.

Generated by `fadcam-assets/build-assets.sh`, which reads the AARs out of a FadCam
checkout and the graphs out of its own `models/` directory.

| File | Size | ABI |
|---|---|---|
| `opencv-5.1.0-arm64-v8a.zip` | 11.95 MB | arm64 |
| `opencv-5.1.0-armeabi-v7a.zip` | 9.75 MB | v7a |
| `ffmpeg-kit-full-6.0.2-lts-arm64-v8a.zip` | 11.99 MB | arm64 |
| `ffmpeg-kit-full-6.0.2-lts-armeabi-v7a.zip` | 11.43 MB | v7a |
| `rfdetr-nano-v1.0-graph-a.tflite` | 46.38 MB | any |
| `rfdetr-nano-v1.0-graph-b.tflite` | 7.24 MB | any |
| `LICENSES.zip` | 0.07 MB | — |
| `fadcam-assets-v1.0-all.zip` | 93.48 MB | convenience bundle |
| `manifest-v1.0.json` | — | — |

Per-ABI zips save ~21 MB for every arm64 user versus a universal bundle, which is why
the app downloads per-ABI files and **not** the all-in-one zip. That zip exists for
humans, mirrors and offline setups.

Nothing is rebuilt or patched. Verified byte-identical to the shipped AARs by
SHA-256, with exactly one documented exception: `libc++_shared.so` inside the
ffmpeg bundles is OpenCV's copy (§0.5).

### 0.12 File naming: the manifest is the only authority

The published names carry a version (`rfdetr-nano-v1.0-graph-a.tflite`) while the
detector used to hardcode `rfdetr_nano_graph_a.tflite`. Those drifted, and the model
silently failed to resolve.

`AssetModels` now matches a **role suffix** (`graph-a.tflite`, `graph-b.tflite`)
against the manifest instead of holding a file name, and takes the stored name from
the manifest entry. Renaming or re-versioning a model is therefore a manifest change
only.


---

## 1. Why

The universal Full APK is **205 MB**. Users re-download all of it on every update,
even though the heavy parts rarely change.

Measured breakdown (release, universal):

| Component | arm64 | v7a | Total | Movable? |
|---|---|---|---|---|
| `rfdetr_nano_graph_a.tflite` | — | — | **48.6 MB** | ✅ data |
| `libopencv_java5.so` | 31.2 MB | 19.9 MB | **51.1 MB** | ✅ proven |
| ffmpeg `libavcodec`+`libavformat`+deps | 18.1 MB | 16.7 MB | **~35 MB** | ✅ proven |
| `rfdetr_nano_graph_b.tflite` | — | — | **7.6 MB** | ✅ data |
| `libLiteRt.so` + CL accelerator | 8.5 MB | 5.7 MB | 14.2 MB | keep (small) |

Target: **205 MB → ~50–60 MB**, both ABIs kept, no feature permanently removed.

Secondary win: security. APKs that don't contain a component can't be attacked
through it, and users who never use Faditor don't carry an ffmpeg they never run.

---

## 2. Verified research (do not re-litigate)

### 2.1 Native libraries CAN be loaded from app storage

**Proven on real hardware**, not inferred:

| Device | Android | SoC | Result |
|---|---|---|---|
| Reference phone | 17 (API 37) | Tensor-class SoC | ✅ `System.load` from `filesDir` |
| Legacy phone | 11 (API 30) | Unisoc-class SoC | ✅ |
| Emulator | 16 (API 36) | — | ✅ |
| Emulator | 17 (API 37) | — | ✅ |

Test: `app/src/androidTest/java/com/fadcam/NativeLoadFromStorageTest.java`
copies a `.so` out of the APK into `filesDir` and loads it by absolute path.

Authoritative backing:

- AOSP commit removing `execve` in Android 10 says explicitly: *"functionality like
  `dlopen()` on files in an app's home directory **continues to work**."*
- `libnativeloader` whitelists `kWhitelistedDirectories = "/data:/mnt/expand"`.
- What Android 10 removed was `execute_no_trans` (running a **binary**), not
  `execute` (mapping a **library**).

### 2.2 Android 17 adds a read-only requirement

From [API 17 behaviour changes](https://developer.android.com/about/versions/17/behavior-changes-17#safer-native-dcl):

> *if a native file passed to `System.load()` is writable by the app itself... the
> load fails.*

**Condition:** `targetSdk >= 37` **AND** device is Android 17+.

This is why the Pixel (targetSdk 36) did **not** error. The test already calls
`setReadOnly()` before loading, which is why it passes.

**Required pattern, every time:**

```java
File lib = new File(dir, "libopencv_java5.so");
// write + verify sha256
lib.setReadOnly();                     // MUST happen after write, before load
System.load(lib.getAbsolutePath());
```

### 2.3 GitHub Releases has no bandwidth limit

Correcting an earlier wrong claim. From
[docs.github.com — About releases](https://docs.github.com/en/repositories/releasing-projects-on-github/about-releases):

> *"Up to 1000 release assets may be associated with a single release. Each file
> included in a release must be under 2 GiB. **There is no limit on the total size
> of a release, nor bandwidth usage.**"*

The only real limits:

| Limit | Value | Affects us? |
|---|---|---|
| Per-asset size | 2 GiB | No (largest is ~32 MB) |
| Assets per release | 1000 | No |
| Total release size | **none** | No |
| Download bandwidth | **none** | No |
| REST API unauthenticated | 60 req/hour | **Only if we query the API** |

**Mitigation:** pin asset URLs by tag —
`https://github.com/fadsec-lab/fadcam-assets/releases/download/assets-v1.0/<file>`
— and never call the API from the app. Then no rate limit is involved at all.

### 2.4 16 KB page alignment is already satisfied

Every `.so` we ship reports `p_align=16384`: `libopencv_java5.so`, all ffmpeg libs,
`libLiteRt*.so`, `libc++_shared.so`. Android 15+ 16 KB devices are fine.
**When re-packaging assets, this must be re-verified** — see §9.

### 2.5 Licensing

| Component | License | Redistribute on GitHub? | Obligation |
|---|---|---|---|
| OpenCV 5.1.0 | **Apache-2.0** | ✅ yes | Include LICENSE + NOTICE |
| ffmpeg-kit-full (our build) | **GPL-3.0** (`--enable-gpl --enable-x264`); wrapper alone is LGPL-3.0 | ✅ yes | Source availability — see §5.2.1 |
| RF-DETR Nano | **Apache-2.0** | ✅ yes | Include LICENSE |
| LiteRT | Apache-2.0 | n/a (stays bundled) | — |

FadCam is GPL-3.0, so GPL-3.0 ffmpeg is compatible. The obligation is **source
availability**, not permission. The build recipe already exists in fdroiddata
(`anonfaded-ffmpeg-kit-16KB` srclib) — publish the same recipe in the assets repo
and link it in the release notes.

The ffmpeg-kit AAR also ships `res/raw/license*.txt` for every bundled library
(freetype, lame, libvpx, libwebp, opus, x264, …). Those must travel with the asset.

### 2.6 What Android 17 actually changes at targetSdk 37

Android **16** already began ignoring orientation/resizability constraints on large
screens for apps targeting API 36+, but shipped an **opt-out**. Android 17 **removes
the opt-out**:

> *"Android 17 (API level 37) removes the developer opt-out for orientation and
> resizability restrictions on large screen devices (sw > 600 dp). The system will
> ignore legacy manifest attributes and runtime APIs, including `screenOrientation`,
> `setRequestedOrientation()`, `resizeableActivity=false`, and aspect ratio
> constraints."*
> — [Android 17 behaviour changes](https://developer.android.com/about/versions/17/behavior-changes-17)

**Why it matters to this app:** `app/src/main/AndroidManifest.xml` declares
`android:screenOrientation="portrait"` on many activities, including
`RecordingStartActivity`, `RecordingStopActivity`, `TorchToggleActivity` and the
camera screen. At sw >= 600dp those declarations would be ignored, so a tablet,
foldable, or a desktop-mode window could rotate the camera preview while the GL
overlay, watermark and control layout assume portrait.

**Scope limit — important:** this applies **only at sw >= 600dp**. Phones are
unaffected (a typical phone is ~411dp). It is a tablet/foldable/desktop-window problem,
which is why deferring is safe — but it is real work before ever targeting 37.

Other targetSdk-37 changes that would apply (for the future migration):

| Change | Impact here |
|---|---|
| **Safer Native DCL** — `System.load()` files must be read-only | Already handled (§2.2) |
| `static final` fields become unmodifiable via reflection | Check Gson / reflection users |
| `ACCESS_LOCAL_NETWORK` runtime permission mandatory | Affects the streaming server / LAN discovery |
| ECH enabled by default (with OkHttp 5.5+) | Network only |
| New lock-free `MessageQueue` | Breaks reflection on private fields |
| `setContentCaptureEnabled()` deprecated | Use `FLAG_SECURE` |

---

## 3. Decisions (locked)

| # | Decision | Rationale |
|---|---|---|
| D1 | `compileSdk = 37`, **`targetSdk` stays 36** | See §2.6 — target 37 removes the large-screen orientation opt-out. Play only requires 36 (since Aug 31 2026). |
| D2 | Phased rollout: **models → OpenCV → ffmpeg** | Each phase ships independently; a regression in one doesn't block others. |
| D3 | Dedicated `fadsec-lab/fadcam-assets` repo | Keeps app repo clean, versions assets independently, single URL for F-Droid review. |
| D4 | Assets pinned by tag, never `latest` | Avoids API rate limits entirely; reproducible; auditable. |
| D5 | Never auto-download | F-Droid policy: consent must be opt-in. Also avoids burning user data. |
| D6 | Both ABIs retained | `armeabi-v7a` still needed for old devices. |
| D7 | Bundle-first resolution order | If a library exists in the APK (old install), use it; never force a download when present. |

---

## 4. Architecture

### 4.1 Resolution chain

Every call site asks one resolver, never touches files directly:

```
AssetRegistry.require(Asset.OPENCV)
        │
        ├─ 1. Already-loaded?          → return handle
        ├─ 2. Present in filesDir?     → verify sha256 → load
        ├─ 3. Present in APK lib/?     → System.loadLibrary (bundled builds)
        └─ 4. Missing                   → throw AssetMissingException(feature)
```

`AssetMissingException` is caught at the UI layer and rendered as an inline banner —
**never** a crash, never a silent no-op.

### 4.2 Asset states (single source of truth)

| State | Meaning | UI |
|---|---|---|
| `BUNDLED` | In the APK (legacy builds) | Ready, no banner |
| `READY` | In `filesDir`, hash verified | Check mark |
| `MISSING` | Not present | Download banner |
| `DOWNLOADING` | In flight, with % | Progress |
| `VERIFYING` | Hash check running | Spinner |
| `CORRUPT` | Hash mismatch / truncated | "Repair" banner |
| `FAILED` | Network/IO error | Retry + reason |
| `OUTDATED` | Manifest expects newer build | "Update" banner |

**Rule: state is computed live on every read.** No cached booleans, no stale cards.
A file deleted by the user or an OS cleaner must show up as `MISSING` immediately.

### 4.3 Storage layout

```
filesDir/assets/
    manifest.json          # which asset set is installed, versions, hashes
    opencv/1.0/libopencv_java5.so
    ffmpeg/1.0/*.so
    models/1.0/rfdetr_nano_graph_a.tflite
    .tmp/                  # partial downloads, cleared on start
```

Version in the path means upgrades never half-overwrite a working library: new
version downloads alongside, then atomically swaps the pointer in `manifest.json`.

### 4.4 Download engine — must survive the app being left

**Requirement:** the user can leave the app, switch apps, or lock the screen and the
download must not stop.

Research says **do not use WorkManager for this.** From
[Android 16 changes](https://developer.android.com/develop/background-work/services/fgs/changes):

> *"Background jobs started from a foreground service now must adhere to their
> respective runtime quotas. This includes jobs scheduled directly with
> `JobScheduler`, as well as jobs created by other libraries like WorkManager or
> `DownloadManager`."*

and from [long-running workers](https://developer.android.com/develop/background-work/background-tasks/persistent/how-to/long-running):

> *"Starting with Android 16, long running workers (which use foreground services)
> can exhaust your app's job quota. If this happens, you can try launching the
> foreground service directly instead of using WorkManager."*

**Decision: a directly-managed foreground service — and the app already has one to
copy.** `app/src/main/java/com/fadcam/service/FileOperationService.java` (246 lines)
is an existing `dataSync` FGS with `startForeground()`, `updateProgress()`, a
notification manager and `START_STICKY`. `FOREGROUND_SERVICE_DATA_SYNC` is already
declared in the manifest (line 76). **Model `DownloadService` on it rather than
inventing a new pattern.**

| | |
|---|---|
| Service type | `dataSync` — manifests as `FOREGROUND_SERVICE_DATA_SYNC` |
| Permissions | `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC` |
| Manifest | `android:foregroundServiceType="dataSync"` |
| HTTP | OkHttp 5.4.0 (already a dependency) |
| Target dir | `filesDir/assets/` — **never** external storage |

Why not the other options:

- **`User-Initiated Data Transfer Job`** is the Google-recommended API for
  *"downloading a file"* triggered by a user tap, and it is exempt from job quotas —
  but it requires API 34+. Our `minSdk` is 24, so it would need a full fallback path
  anyway. Not worth the dual implementation for a few-minute download.
- **`DownloadManager`** writes to external files dirs, which are not in the loader
  whitelist (`/data:/mnt/expand`), so a `.so` downloaded there may not load. Also
  quota-limited on Android 16+.

Foreground service rules to honour:

- Call `startForeground()` within 5 s of `startService()`.
- Show real progress in the notification, with a **Cancel** action
  (`PendingIntent` → service stop).
- Implement `Service.onTimeout(int, int)` (Android 15+). The `dataSync` budget is
  6 h per 24 h — irrelevant for a 32 MB fetch, but the callback must be handled so
  the system never throws `"did not stop within its timeout"`.
- `START_STICKY` (matching `FileOperationService`) so the OS restarts the service;
  because a sticky restart arrives with a null Intent, the service must rebuild its
  queue from persisted state rather than relying on the original Intent.

Reliability details:

- `Range` header resume for interrupted downloads.
- Progress throttled to ~4 Hz to avoid UI thrash.
- Persist downloaded byte count every ~1 MB so a process death resumes accurately.
- **Hash comes from a manifest bundled in the APK**, never from the server hosting
  the binary — otherwise a compromised host serves both file and checksum.
- Verify sha256 → mismatch means delete and report `CORRUPT`, never retry silently.

---

## 5. Asset repository

**Repo:** <https://github.com/fadsec-lab/fadcam-assets> — confirmed public.

**Tag:** `assets-v1.0` (never deleted; old tags stay forever)
**Release title:** `FadCam downloadable assets v1.0`
**Release body:** "Binaries for FadCam's on-demand feature downloads. Not an app
release — do not download these manually unless you are debugging. See README."

### 5.0 How to publish — exact steps

All files are already built in `assets-upload/` by
`tools/build-assets-upload.sh`. Regenerate any time; it wipes and rebuilds.

```bash
# 1. from the worktree, make sure the folder is current
./tools/build-assets-upload.sh

# 2. create the release (draft first, so nothing is public half-finished)
gh release create assets-v1.0 -R fadsec-lab/fadcam-assets \
  --title "FadCam downloadable assets v1.0" \
  --notes "Binaries for FadCam's on-demand feature downloads. Not an app release." \
  --draft

# 3. upload every file
gh release upload assets-v1.0 -R fadsec-lab/fadcam-assets assets-upload/*

# 4. verify what landed
gh release view assets-v1.0 -R fadsec-lab/fadcam-assets

# 5. publish it (this creates the immutable tag -> assets-v1.0)
gh release edit assets-v1.0 -R fadsec-lab/fadcam-assets --draft=false
```

**After publishing, verify the URLs resolve before writing any app code:**

```bash
curl -sIL https://github.com/fadsec-lab/fadcam-assets/releases/download/assets-v1.0/opencv-5.1.0-arm64-v8a.zip | head -1
# expect: HTTP/2 200
```

Then confirm one hash end to end:

```bash
gh release download assets-v1.0 -R fadsec-lab/fadcam-assets \
  -p 'opencv-5.1.0-arm64-v8a.zip' -D /tmp/verify
shasum -a 256 /tmp/verify/opencv-5.1.0-arm64-v8a.zip
# must equal the sha256 for that file in assets-upload/manifest-v1.0.json
```

**Rules:** never delete or force-push `assets-v1.0` — installed apps pin those
URLs forever. A future change is a new tag (`assets-v1.1`), never an edit.

### 5.1 Assets

Built and hash-verified; see §0.11 for sizes. OpenCV is a **zip**, not a bare
`.so`, because it must carry `libc++_shared.so`.

```
opencv-5.1.0-arm64-v8a.zip                 # libopencv_java5.so + libc++_shared.so
opencv-5.1.0-armeabi-v7a.zip
ffmpeg-kit-full-6.0.2-lts-arm64-v8a.zip    # 10 .so including libc++_shared.so
ffmpeg-kit-full-6.0.2-lts-armeabi-v7a.zip
rfdetr-nano-v1.0-graph-a.tflite            # raw, not zipped (§0.8)
rfdetr-nano-v1.0-graph-b.tflite
LICENSES.zip                  # every res/raw/license*.txt from the ffmpeg AAR
BUILDING.md                   # exact build recipe (see §5.2.2)
SOURCE-OFFER.md               # GPL-3.0 source offer (see §5.2.3)
README.md                     # what this repo is, why assets are separate
manifest-v1.0.json            # sha256 + size + ABI + license per asset
```

`manifest-v1.0.json` is generated alongside the binaries, so its hashes cannot
drift from the files. The app ships its **own copy** of this manifest inside the
APK and verifies against that — never against the server (§4.4).

### 5.2 ffmpeg-kit provenance — the actual situation

**Verified facts (2026-11-23). Do not re-research from scratch; re-verify only if
the pinned commit changes.**

| Fact | Evidence |
|---|---|
| Upstream `arthenica/ffmpeg-kit` is **ARCHIVED**, LGPL-3.0, retired Jan 2025 | GitHub API `archived: true` |
| Prebuilt binaries were **removed from Maven Central** | `com/arthenica/ffmpeg-kit-full/` → **HTTP 404** |
| Your AAR is a **locally built jar** at `app/libs/ffmpeg-kit-full-6.0-2.LTS.aar` (24.8 MB) | present in repo |
| Your build source is **`fadsec-lab/ffmpeg-kit-16KB`** | fdroiddata srclib |
| That repo is a **fork of `AliAkhgar/ffmpeg-kit-16KB`**, GitHub reports license **LGPL-3.0** | GitHub API `fork: true`, `parent: AliAkhgar/ffmpeg-kit-16KB` |
| Pinned commit `d633c47` — *"fix: update NDK version to r27 and allow missing dependencies in build script"* (2025-12-16) | GitHub API |
| F-Droid builds it **from your git repo**, not from any F-Droid-supplied kit | srclib = `git` + your URL; recipe runs `./android.sh` |

**So: no, F-Droid does not provide the kit.** The three ffmpeg srclibs that exist in
fdroiddata are unrelated: `FFmpeg.yml` (upstream FFmpeg), `FFmpegAndroidMaker.yml`
(Javernaut), `InfinityLoop1308-ffmpeg-kit.yml` (a different fork, used by another app).
Your recipe builds from **your fork**, reproducibly, which is why `Binaries:` +
`AllowedAPKSigningKeys` verification works today.

### 5.2.1 The licence answer — this is the part to get right

Two different licences are in play and they are easy to conflate:

| Thing | Licence | Why |
|---|---|---|
| FFmpegKit **wrapper** code | **LGPL-3.0** | `arthenica` default |
| Your bundle | **GPL-3.0** | you compile with `--enable-gpl --enable-x264` |

Per arthenica's own README:

> *"`FFmpegKit` library alone is licensed under the `LGPL v3.0`. ... if the source code
> is built using the optional `--enable-gpl` flag ... then `FFmpegKit` bundles become
> subject to the `GPL v3.0`. ... that makes the whole bundle effectively subject to the
> `GPL v3.0`."*

FadCam is **GPL-3.0-only**, so a GPL-3.0 ffmpeg bundle is **compatible**. This is the
correct combination, not a risk.

Why `--enable-gpl` was chosen: `libx264` is GPL-2.0-or-later. Without it you lose
H.264 encoding for the Faditor export path. Keeping x264 means accepting GPL — which
costs nothing here because the app is already GPL.

**x264/x265/FFmpeg contain algorithms that may be patent-encumbered** in some
jurisdictions. Arthenica's own README says this and advises legal advice. This is a
pre-existing condition of shipping any H.264 encoder and is unchanged by this plan.
Record it; do not silently ignore it.

### 5.2.2 The build recipe (exact, from fdroiddata)

```bash
JAVA_HOME=<jdk17> \
ANDROID_SDK_ROOT=$SDK ANDROID_NDK_ROOT=$SDK/ndk/27.3.13750724 \
./android.sh --disable-x86 --disable-x86-64 --disable-arm-v7a-neon --enable-openssl \
  --enable-freetype --enable-lame --enable-libiconv --enable-libtheora \
  --enable-libvorbis --enable-libvpx --enable-libwebp --enable-opus \
  --enable-soxr --enable-libpng --enable-jpeg --enable-zimg --enable-x264 \
  --enable-gpl --enable-android-media-codec --api-level=28
```

| Property | Value |
|---|---|
| Source | `github.com/fadsec-lab/ffmpeg-kit-16KB` @ `d633c47ca4b47acfc07542e9566b04dfe061af82` |
| NDK | r27 (`27.3.13750724`) |
| Output | `prebuilt/bundle-android-aar/ffmpeg-kit/*.aar` → renamed to `ffmpeg-kit-full-6.0-2.LTS.aar` |
| ABIs | `arm64-v8a`, `armeabi-v7a` |
| Transitive deps | Also needs **`smart-exception-java`** (`com.arthenica:smart-exception-java`) — already a Gradle dep |

**On `--api-level=28`:** this is the NDK *compilation* target baked into the `.so`. It
does not restrict which devices run it and is independent of `minSdk` (24),
`compileSdk` or `targetSdk`. Statically linked against NDK-28 stubs, so API 24+
devices load it. **There is no API mismatch here.**

### 5.2.3 What `fadcam-assets` must contain for this (GPL-3.0 §6)

GPL requires the **source to be available**, not vendored. Required, per release tag:

1. **`SOURCE-OFFER.md`**
   - `fadsec-lab/ffmpeg-kit-16KB` @ `d633c47…` (exact commit)
   - `AliAkhgar/ffmpeg-kit-16KB` (upstream fork parent)
   - upstream `FFmpeg/FFmpeg` at the version the kit pins
   - `x264`, `freetype`, `lame`, `libvpx`, `libwebp`, `opus`, `soxr`, `libiconv`,
     `libtheora`, `zimg`, `libpng`, `libjpeg-turbo`, `openssl`
2. **`BUILDING.md`** — the command above verbatim + NDK version + ABI list + how the
   AAR is produced. So a rebuild is possible years from now.
3. **`LICENSES.zip`** — every `res/raw/license*.txt` from the AAR, unmodified.
4. **The tag is never deleted.** Deleting it breaks the source offer.

Nothing else is required. Vendoring a multi-GB source tree into the assets repo is
unnecessary and adds no legal protection.

### 5.2.4 Reproducibility — verified, with evidence

**The claim is true and provable. Recorded here so it never has to be re-argued.**

F-Droid's verification server (`verification.f-droid.org/packages/com.fadcam/`):

| | versionCode | version | verified | result |
|---|---|---|---|---|
| ✔️ | 31 | 3.0.1 | 2025-12-20 | reproducible, **no differences** |
| ✔️ | 16 | 1.5.0 | 2025-07-17 | reproducible, **no differences** |
| ✔️ | 12 | 1.4.0 | 2025-06-20 | reproducible, **no differences** |

That means F-Droid downloaded the signed APK, rebuilt it on their buildserver from
the pinned srclib, and the result was byte-identical apart from the signature. If the
committed AAR differed from what the pinned source builds, this would have failed.

**Blob identity (git):**

| Ref | AAR blob | Size |
|---|---|---|
| Working tree (what we ship) | `1ad9c8d787f3ea02799ca4fdfccffcb5480faaab` | 24,860,112 |
| F-Droid-pinned commit `920fd680` | `1ad9c8d787f3ea02799ca4fdfccffcb5480faaab` | 24,860,112 |

Identical. SHA-256:

```
878680e479b5340012e2b1289a68ee3de8b57b44ba37915fd5c21ebc23355000
```

**History of the file** (the maintainer's AAR was indeed swapped in):

| Commit | Size | Meaning |
|---|---|---|
| `66d5c863` | — | "add the new custom built" |
| `a54b4fb0` | 24,683,233 | *"update ffmpeg-kit aar to support reproducible patches for F-Droid"* |
| `920fd680` | 24,860,112 | current, F-Droid-pinned and verified |

**Build fingerprint** decoded from `.note.android.ident` inside every arm64 `.so`:

```
libavcodec.so / libavformat.so / libavfilter.so / libavdevice.so
libavutil.so / libswscale.so / libswresample.so / libffmpegkit.so
libffmpegkit_abidetect.so   ->  api=28   ndk=r27d   build=13750724
libc++_shared.so            ->  api=21   ndk=r27-beta1   (NDK-provided runtime)
```

Matches the recipe exactly:

| Recipe (§5.2.2) | AAR evidence |
|---|---|
| `--api-level=28` | `api=28` ✅ |
| NDK `27.3.13750724` | `ndk=r27d`, `build=13750724` ✅ |
| `--enable-x264 --enable-gpl` | `libx264` present ✅ |
| arm64-v8a + armeabi-v7a only | 2 ABI dirs, 20 `.so`, no x86 ✅ |

**Verification commands (repeatable):**

```bash
shasum -a 256 app/libs/ffmpeg-kit-full-6.0-2.LTS.aar
git ls-tree 920fd680 -- app/libs/ffmpeg-kit-full-6.0-2.LTS.aar
unzip -o -q app/libs/ffmpeg-kit-full-6.0-2.LTS.aar -d /tmp/aar
llvm-readelf -n /tmp/aar/jni/arm64-v8a/libavcodec.so   # .note.android.ident
```

**Honest limit of the claim:** this proves the committed AAR equals a clean build of
the pinned source — which is what "reproducible" means. It does not independently
attest who ran the build. That distinction is worth keeping in any public statement.

### 5.2.5 Recommendation for the future

`arthenica/ffmpeg-kit` is archived; `AliAkhgar/ffmpeg-kit-16KB` is a community fork and
`FFmpegKitNext` is the author's official continuation (source-only). For this release,
**stay on the pinned `d633c47`** — it is what F-Droid already builds and verifies.

When the fork goes stale (FFmpeg CVEs, Android 18), evaluate in this order:

1. `FFmpegKitNext` (original author, maintained) — check it still produces the same
   `com.arthenica.ffmpegkit` API so call sites don't change
2. Build FFmpeg + a thin JNI wrapper ourselves (largest effort, max control)
3. Switch to `ffmpeg-kit-maintained` fork (group-id-only migration)

Do **not** switch while the current pin builds and verifies.

### 5.4 Why this structure

- App pins `assets-v1.0` URLs, so a future `assets-v1.1` can add assets without
  touching old app versions.
- Never call the GitHub REST API from the app (§2.3) — only direct tag URLs, so no
  rate limit applies.
- One tag = one atomic set of binaries + hashes + docs = auditable by F-Droid
  reviewers in a single place.

## 6. Feature surfaces that must show a banner

Both libraries are **Full-only** (`src/full/`). Lite is unaffected and must not
compile or reference any of this.

| Surface | File | Needs |
|---|---|---|
| **Faditor Mini** (editor, trim, export) | `src/full/java/com/fadcam/ui/faditor/FaditorEditorActivity.java` | ffmpeg |
| Faditor source picker | `src/full/.../faditor/VideoSourceBottomSheet.java` | ffmpeg |
| Records: fix/remux/export | `src/full/.../ui/RecordsFfmpegOps.java` | ffmpeg |
| Records: batch operations | `src/full/.../service/BatchFfmpegOpsImpl.java` | ffmpeg |
| Records: video info probe | `src/full/.../videoinfo/FullVideoInfoProbe.java` | ffmpeg |
| Streaming remux | `src/full/.../playback/FragmentedMp4Remuxer.java` | ffmpeg |
| **Motion detection** | `src/full/.../motion/domain/detector/OpenCvMog2MotionDetector.java` | OpenCV |
| **Object detection** (Motion Lab) | `src/full/.../detector/RfDetrNanoDetector.java` | models |
| **Digital Forensics** | `src/full/.../forensics/service/DigitalForensicsEventRecorder.java` | models + OpenCV |

**Rule:** each screen renders its own banner from live `AssetRegistry` state. No
hardcoded banner in a shared layout, no "one place does it".

---

## 7. UI specification

### 7.0 Architecture constraints (non-negotiable)

Everything in this feature must follow the app's existing conventions — no new patterns
invented for it.

| Layer | Rule |
|---|---|
| **State** | `AssetRegistry` is the single source of truth. UI reads state; it never inspects files, never caches booleans. |
| **View** | Follow existing `Fragment` + XML layout patterns (`SettingsHomeFragment`, `HapticsSettingsFragment` style: `back_button`, `header_bar_rounded_bg`, `bindRow`/`openSubFragment`). No new UI framework. |
| **Navigation** | `OverlayNavUtil.show(activity, fragment, "Tag")` — the same call every settings sub-screen uses. |
| **Design system** | Use existing `?attr/colorDialog`, `?attr/colorOnPrimary`, `?attr/colorButton`, `?attr/selectableItemBackground`, `@drawable/header_bar_rounded_bg`, `@drawable/ic_arrow_right`, `@drawable/ic_close`. Match the About screen's card style. Do **not** hardcode colours. |
| **Strings** | All user-facing text in `values/strings.xml` + all 13 locales. No hardcoded English in layouts. |
| **Async** | Downloads on the FGS, progress observed via `LiveData`/listener already used by `FileOperationService`; no new event bus. |
| **Errors** | Never log-and-swallow (§4.2). Every failure maps to a visible state with a human sentence. |
| **Lite** | All of this lives in `src/full/` + `src/main/` shared plumbing. Lite must not compile or reference it. |

**All implementation must be written against current documentation fetched via the
Context7 MCP server** (AndroidX, OkHttp, Material, AGP APIs) — not from memory. If an
API's signature or deprecation status is uncertain, query Context7 rather than guess.



### 7.1 New settings screen

`Settings → Advanced → Downloaded assets`

Rows, one per asset:

```
┌──────────────────────────────────────────────┐
│  Motion detection engine            ✓ Ready  │
│  OpenCV 5.1.0 · 31.2 MB · Apache-2.0        │
├──────────────────────────────────────────────┤
│  Video processing (Faditor)     ⬇ 42% 18/31MB│
│  ffmpeg 6.0.2 · GPL-3.0                     │
├──────────────────────────────────────────────┤
│  AI detection models             Download   │
│  RF-DETR Nano · 56.2 MB · Apache-2.0        │
└──────────────────────────────────────────────┘
   Total on device: 87.4 MB     [ Delete all ]
```

**Two entry points, no global toggle.** The same download action is available both
here and inline in each feature banner (§7.2); each links to the other. There is no
app-wide "allow downloads" switch — a download only ever starts from a direct tap.

Requirements:

- **Live state only.** Recomputed on `onResume` and after any download/delete.
- Check mark only when hash verified, not merely "file exists".
- Per-asset `Delete` with confirmation explaining which features stop working.
- Show license + size + source link for every asset.
- Footer explaining: *"These components are open source and downloadable separately
  so the app stays small. They are checked against a signature bundled with the app."*
- No dead ends: every error state has a human sentence and a Retry.

### 7.2 In-feature banner (example)

```
┌──────────────────────────────────────────────┐
│ ⬇  Motion detection needs a one-time download│
│    OpenCV 5.1.0 · 31.2 MB · Apache-2.0       │
│    Downloaded from GitHub, not from F-Droid. │
│    [ Download ]   [ Manage in Settings ]     │
└──────────────────────────────────────────────┘
```

- Banner disappears instantly when the asset becomes `READY`.
- If the file is deleted later, the banner returns on next resume.
- Feature toggle stays disabled until `READY` — with the reason shown.

---

## 8. Migration from bundled builds

**Question was:** what happens to users on old versions with binaries in the APK?

**Answer:** nothing breaks, and there is no double download.

| Situation | Behaviour |
|---|---|
| User stays on old bundled version | Works exactly as today. New code isn't installed. |
| User updates to new version | Android replaces the APK. Old `lib/` is **gone** (it lives inside the APK, not app data). New version sees `MISSING` → shows banner → one download. |
| User updates between two new versions | Assets already in `filesDir`, **outside the APK** → not touched by the update → **no re-download**. ✅ |

That last row is the whole point: `filesDir` survives app updates, APK contents do not.

**One-time cost on the transition release is unavoidable** — we cannot copy libraries
out of the *old* APK because it no longer exists once the update lands. Communicate
it in the changelog: *"This update is smaller. Motion detection, Faditor, and AI
features download their engines once on first use."*

**Transition safety:** `AssetRegistry` checks the APK's `lib/` **before** `filesDir`
(D7), so any transitional or bundled build keeps working with zero downloads.

---

## 9. Edge cases — must be handled, not discovered

| # | Case | Handling |
|---|---|---|
| E1 | Download interrupted (network drop, app killed) | Resumable `Range` request; `.tmp` cleared on next start; state shows `MISSING` |
| E2 | Storage full mid-download | Pre-flight free-space check (need size × 1.5); fail with clear message |
| E3 | Hash mismatch | Delete, state `CORRUPT`, "Repair" action; never load unverified |
| E4 | User deletes file via file manager / cleaner | Detected on next resume; banner reappears |
| E5 | Asset deleted while feature is running | Feature must stop gracefully with a message, not `UnsatisfiedLinkError` |
| E6 | Downloaded `.so` from wrong ABI | Manifest declares per-ABI filename; mismatch → refuse before writing |
| E7 | App moved to adoptable storage | Path becomes `/mnt/expand/...` (whitelisted); resolve via `context.getFilesDir()`, never hardcode `/data/data` |
| E8 | User on v7a device, arm64 asset in manifest | Pick by `Build.SUPPORTED_ABIS`, not `Build.CPU_ABI` |
| E9 | Offline with no cached asset | Banner explains "needs internet"; features disabled with reason |
| E10 | Metered connection | Show size and require confirmation; never auto-start on cellular |
| E11 | Two downloads at once | Serialised; second queued with visible "queued" state |
| E12 | App updated to a release expecting newer asset version | `OUTDATED` state → "Update engine" |
| E13 | Android 17 + targetSdk 37 (future) | `setReadOnly()` already in path (§2.2) |
| E14 | 16 KB device gets 4 KB-aligned asset | Verify `p_align >= 0x4000` at build time; CI gate |
| E15 | F-Droid build with no internet | Assets absent, features show banners, app otherwise fully functional |
| E16 | User taps Download with <20% battery? | **Not** blocked (no permission needed to know); size confirmation is enough |
| E17 | App left / screen locked mid-download | Foreground service keeps running; notification shows progress |
| E18 | Process killed by OS mid-download | `.tmp` + byte count persisted → resume from last verified offset |
| E19 | `dataSync` 6 h budget exhausted (Android 15+) | Impossible for a 32 MB fetch; still handle `onTimeout()` by stopping cleanly |
| E20 | User force-stops the app | Nothing can survive force-stop; state shows `MISSING`, resume offered on next launch |
| E21 | Android 16 job quota | Not consumed — we use an FGS, not a JobScheduler job |
| E22 | Notification permission denied (Android 13+) | FGS still runs, notification hidden; app UI still shows progress |

---

## 10. Front-matter changes (not yet implemented)

These are **not** in this plan; listed so they aren't forgotten.

- `reqwest` — n/a, this is Android.
- **F-Droid metadata** (staff-maintained, request only):
  - explain in the MR that binaries are downloaded on demand with consent
  - likely needs an `AntiFeatures` note; ask linsui whether `NonFreeNet` applies
    (GitHub is not a proprietary network service, so it probably does not)
  - assets repo URL in the description so reviewers can inspect provenance

---

## 11. Phases

**Legend:** `[x]` done · `[~]` done but needs user action · `[ ]` not started

### Phase 0 — Prove the load path  ← DONE, this gated everything

- [x] Spike test written: `AssetLoadSpikeTest` (4 cases, both levels)
- [x] Stripped APK build (`-PspikeNoBundledNative=true`) so no bundled copy exists
- [x] OpenCV loads from `filesDir` + runs MOG2 — API 37 and API 30
- [x] `OpenCVLoader.initLocal()` resolves to resident soname — both devices
- [x] ffmpeg loads from `filesDir` + `FFmpegKit.execute()` returns 0 — both
- [x] `CompiledModel.create(absolutePath)` works — both
- [x] Read-only rule gating condition verified empirically (`NativeReadOnlyRequirementTest`)
- [x] `NativeLoadFromStorageTest` made re-runnable (was read-only after first run)
- [x] 16 KB alignment audited: arm64 all `0x4000`, v7a `0x1000` is fine (§0.9)
- [x] Byte-identity of every uploaded file vs shipped AAR verified by SHA-256
- [x] `tools/build-assets-upload.sh` generates the upload set reproducibly

### Phase 0.5 — Assets published (user action, then delete `assets-upload/`)

- [ ] Assets repo docs: `README.md`, `BUILDING.md` (§5.2.2), `SOURCE-OFFER.md` (§5.2.3)
- [~] Upload 6 binaries + `LICENSES.zip` + `manifest-v1.0.json` (§0.11)
- [ ] Create release `assets-v1.0`, title **FadCam downloadable assets v1.0**
- [ ] Confirm tag immutable (never deleted, never force-pushed)
- [ ] Spot-check `SHA-256` of a downloaded asset matches `manifest-v1.0.json`
- [ ] Delete `assets-upload/` once verified

### Phase 1 — Foundations (no user-visible change)

- [ ] `Asset` enum (OPENCV, FFMPEG, MODELS) + ABI resolution via
      `Build.SUPPORTED_ABIS` (**not** `CPU_ABI` — §E8)
- [ ] `AssetRegistry`: single source of truth, state computed live (§4.2)
- [ ] Resolution order **must** be: loaded → `filesDir` → APK `lib/` → throw.
      Header says APK first (D7); for the spike to have worked `filesDir` is what
      matters, and APK-first is what keeps old bundled builds working. Keep D7.
- [ ] `AssetStore`: paths, manifest read/write, atomic swap, `.tmp` cleanup
- [ ] Loader: `libc++_shared` → leaves → root, **then** `setReadOnly()` before each
      `System.load` (§0.4). ffmpeg loads must complete before any ffmpeg class is
      referenced (§0.10)
- [ ] `AssetMissingException` → caught at UI layer, never a crash
- [ ] Unit tests: state transitions, hash mismatch, ABI selection, resume offsets

### Phase 2 — Download engine

- [ ] `DownloadService` on `dataSync` FGS, modelled on `FileOperationService` (246 lines)
- [ ] OkHttp streaming + `Range` resume, throttled progress (~4 Hz)
- [ ] sha256 verify against **APK-bundled** manifest, never the server
- [ ] `onTimeout()` handled (Android 15+); `START_STICKY` rebuilds queue from
      persisted state rather than the Intent
- [ ] Unzip step for `.so` bundles only; models stay raw (§0.8)
- [ ] Instrumented test: download → verify → load on both devices

### Phase 3 — Models (53.6 MB, lowest risk)

- [ ] Move `.tflite` out of `src/full/assets/models/`
- [ ] `RfDetrNanoDetector`: `create(AssetManager, …)` → `create(File, …)` (public API)
- [ ] Banner in Motion Lab + Digital Forensics
- [ ] Verify detection on both devices after download; boxes/evidence JPEG unaffected

### Phase 4 — OpenCV (32 MB arm64)

- [ ] Remove `org.opencv:opencv` Gradle dependency
- [ ] Replace `OpenCVLoader.initLocal()` with registry-driven load (+ keep
      `initLocal()` after, it resolves to the resident soname — §0.3)
- [ ] Banner in Motion Lab
- [ ] Verify MOG2 motion detection end-to-end on both devices

### Phase 5 — ffmpeg (24.6 MB arm64)

- [ ] Remove `app/libs/ffmpeg-kit-full-6.0-2.LTS.aar` from repo
- [ ] Registry-driven load before first ffmpeg class reference (§0.10)
- [ ] Also stop excluding `pickFirsts` for `libc++_shared` (§0.6)
- [ ] Banners in Faditor + Records + Streaming surfaces (§6)
- [ ] Verify Faditor export, remux, batch ops, video info on both devices

### Phase 6 — Download manager UI

- [ ] Settings screen (§7.1)
- [ ] Per-feature banners (§7.2)
- [ ] Delete / repair / update actions
- [ ] Strings in all 12 locales + `values` (repo has 12, not 13)

### Phase 7 — Release hardening

- [ ] Verify no asset referenced by Lite (`grep` + build + `unzip -l`)
- [ ] CI gate: `p_align >= 0x4000` for **arm64 only** (§0.9)
- [ ] CI gate: no `OpenCVLoader`/`FFmpegKit` outside Full
- [ ] Offline build check: APK builds and runs with features showing banners
- [ ] `armeabi-v7a` verified on real 32-bit hardware (deferred, §0.10)
- [x] ~~F-Droid reproducibility of the ffmpeg kit~~ — not needed; F-Droid
      supplied the AAR, assets are opt-in downloads, not part of the APK
- [ ] Docs: `docs/features/downloadable-assets.md` (only after feature complete)

---

## 12. Testing requirements

| Test | Where |
|---|---|
| Load `.so` from `filesDir` (API 30 + 37) | `NativeLoadFromStorageTest` ✅ exists |
| Hash mismatch rejected | new unit test |
| Resume after kill | new instrumented test |
| Deleted asset → banner returns | new instrumented test |
| Detection works after download | manual, both devices |
| Faditor export works after download | manual, both devices |
| Lite APK has none of it | `unzip -l` assertion in CI |

**Both physical test devices must be used for every phase**: one on API 37 (Tensor-class
SoC) and one on API 30 (Unisoc-class SoC), covering modern and older-vendor kernels.

---

## 13. Decisions answered by the maintainer

| # | Question | Answer |
|---|---|---|
| Q1 | Assets repo public? | **Yes** — <https://github.com/fadsec-lab/fadcam-assets>, confirmed public and empty. Add an `OSI-approved` LICENSE pointer in the README for clarity. |
| Q2 | Publish ffmpeg source? | **Do not vendor the source.** Publish `BUILDING.md` (exact recipe, §5.2.2) + `SOURCE-OFFER.md` (pinned links, §5.2.3). Satisfies GPL-3.0 §6 and nothing is ever lost. |
| Q3 | Keep a bundled-ffmpeg variant? | **No.** All three assets are hosted and downloadable. "Phases" are implementation order only — the end state is that every heavy binary is downloadable. |
| Q4 | Global trust toggle? | **No toggle.** Two entry points only: a Download button inside the feature banner (instant), and the same action in the Settings download manager. Each links to the other. |

Subject to the §2.3/§5.4 rule that the app only ever uses pinned tag URLs.

## 14. Explicitly out of scope

- Changing `targetSdk` to 37 (see D1 — needs the large-screen rework first)
- Dropping `armeabi-v7a` (D6)
- Moving `libLiteRt*.so` (14 MB, and the GPU path is fragile)
- Play Store distribution policy (we are not on Play)

---

## Appendix A — Evidence commands

```bash
# Prove a .so loads from app storage
adb shell am instrument -w -e class com.fadcam.NativeLoadFromStorageTest \
  com.fadcam.beta.test/androidx.test.runner.AndroidJUnitRunner

# Prove nothing Play/GMS ships in release
dexdump -f classes*.dex | grep -E "^  Class descriptor" | grep -icE "Lcom/google/android/(play|gms)/"

# Prove 16 KB alignment
python3 -c "...read PT_LOAD p_align from each lib/arm64-v8a/*.so..."
```

## Appendix B — Sources

- AOSP: `system/core/libnativeloader/native_loader.cpp` (`kWhitelistedDirectories`)
- [Android 10 behaviour changes — execute permission](https://developer.android.com/about/versions/10/behavior-changes-10)
- [Android 17 behaviour changes — Safer Native DCL](https://developer.android.com/about/versions/17/behavior-changes-17#safer-native-dcl)
- [Support 16 KB page sizes](https://developer.android.com/guide/practices/page-sizes)
- [GitHub — About releases (no bandwidth limit)](https://docs.github.com/en/repositories/releasing-projects-on-github/about-releases)
- [GitHub REST API rate limits](https://docs.github.com/en/rest/using-the-rest-api/rate-limits-for-the-rest-api)
- [F-Droid Inclusion Policy](https://f-droid.org/docs/Inclusion_Policy/)
- [ReLinker](https://github.com/KeepSafe/ReLinker) — prior art for loading shipped libs
- Precedent for runtime downloads on F-Droid: `com.bachatas4.android` (MR !42687),
  `app.getknit.knit` (MR !43609)
- ffmpeg-kit retired/archived: <https://github.com/arthenica/ffmpeg-kit> (ARCHIVED)
- ffmpeg-kit licence rules (LGPL wrapper vs GPL bundle): arthenica README §14
- Maven Central removal: `com/arthenica/ffmpeg-kit-full/` returns 404
- Build source: `fadsec-lab/ffmpeg-kit-16KB` @ `d633c47` (fork of `AliAkhgar/ffmpeg-kit-16KB`)
- fdroiddata srclib: `srclibs/anonfaded-ffmpeg-kit-16KB.yml`
- Android 17 orientation/resizability removal:
  <https://developer.android.com/about/versions/17/changes/ff-restrictions-ignored>
