# Downloadable assets

Heavy engines and models are not in the APK. They are downloaded on demand, verified,
and loaded from app-private storage. This page covers how that works, why, and what to
know before changing it.

## Why

The universal APK was 205 MB. Most of it was three payloads that rarely change and that
many users never need:

| Payload | arm64 size | Used by |
|---|---|---|
| `libopencv_java5.so` | 31.2 MB | Motion Lab motion detection |
| ffmpeg-kit (9 `.so`) | 23.4 MB | Faditor, Records batch merge, video info, player |
| `rfdetr_nano_graph_{a,b}.tflite` | 53.6 MB | Motion Lab object filtering, Digital Forensics |

Moving them out took the universal APK to **43.6 MB** and the arm64 APK from 150.6 MB to
**37.5 MB**. An update now re-downloads only the app, not 150 MB of unchanged binaries.

Only LiteRT stays bundled: it is small and its GPU path is fragile enough that moving it
would risk more than it saves.

## Where the files live

Published at [fadsec-lab/fadcam-assets](https://github.com/fadsec-lab/fadcam-assets) under
an immutable tag. The app pins `assets-v1.0`; that tag is never edited or deleted, because
installed copies refer to those exact URLs. Any change is a new tag.

Per-ABI files, not one universal bundle, so a phone never downloads code it cannot run.
The models ship **raw** (they compress ~9.5%, not worth a 46 MB temp copy) and the `.so`
bundles ship **zipped** (50-63% smaller).

The asset repo also carries an all-in-one zip for humans and offline mirrors. The app
never downloads it.

## How loading works

`System.load(absolutePath)` makes a library resident under its soname. The libraries' own
internal `System.loadLibrary("avcodec")` / `System.loadLibrary("opencv_java5")` calls then
resolve to that resident copy. So no patched loader and no fork are needed.

Order is a hard requirement, not a preference:

```
libc++_shared.so  ->  leaves  ->  roots
```

For ffmpeg, the loads must complete **before the first `com.arthenica.ffmpegkit` class is
touched**, because `FFmpegKitConfig`'s static initialiser calls `NativeLoader.loadFFmpeg()`
immediately. Touch an ffmpeg class first and you get:

```
dlopen failed: library "libffmpegkit_abidetect.so" not found
```

That is why every ffmpeg entry point calls `AssetFfmpeg.ensureLoaded()` first.

`setReadOnly()` runs before every load. It is not required at `targetSdk` 36 (verified: a
writable library still loads on API 37) but it is the documented rule at 37+, and one
syscall is not worth re-litigating later.

## Integrity

The manifest ships **inside the APK** as `app/src/full/assets/assets-manifest.json`. It is
never fetched, so a compromised host cannot serve both a malicious binary and a matching
checksum.

A zip install writes a marker containing the verified archive hash **last**, so a crash
mid-unzip leaves the asset looking uninstalled rather than installed-but-broken.

## Offline install

`AssetImporter` accepts any file the user supplies, hashes it, and installs it only if the
bytes match a manifest entry. The file is identified by hash across every asset and ABI, so
the user never has to know that OpenCV is a zip and the models are raw. A genuine file for
the wrong ABI is named explicitly rather than installed.

This is what makes the feature usable with no internet at all.

## Adding or changing an asset

1. Add the entry to `assets-manifest.json` in the app, with `sha256`, `size`, `abi`,
   `license` and `installedSize`. `installedSize` is what it occupies unpacked, which is
   much larger than the download for a compressed archive.
2. Publish the file in the assets repo under a **new** tag.
3. Update `baseUrl` in the manifest to the new tag.
4. Verify: `shasum -a 256 <file>` must match the manifest, and the URL must return 200.

Never reuse a tag. Never edit a published file.

## States

`AssetRegistry.state()` recomputes on every read; nothing caches a boolean, so a file
deleted by a cleaner shows up as missing immediately.

| State | Meaning | UI |
|---|---|---|
| `BUNDLED` | ships in the APK | "Included in the app" |
| `READY` | installed and intact | green check, "Installed" |
| `DOWNLOADING` | in flight | Material 3 ring with live % |
| `MISSING` | not installed | accent "Download" |
| `CORRUPT` | files missing or damaged | amber "Download again" |
| `FAILED` / `OUTDATED` | retry / newer set expected | "Retry" |

There is no repair routine, which is why the label is "Download again" and not "Repair".

## No automatic downloads

Downloads start only from a tap. Two tests enforce it:

- `downloadsOnlyStartFromAClick` — every `AssetDownloadService.start` must be inside a
  click listener, or the enclosing method must carry a `// download-entry-point` marker.
- `everyAnnotatedEntryPointIsClicked` — each marked method must be provably invoked from a
  click listener.

A third test forbids hardcoded sizes in the UI, because download and installed size differ
per ABI and a literal would eventually be wrong on some device.

## Missing assets must never crash

Every call site that touches an engine guards it and degrades:

| Call site | Behaviour when missing |
|---|---|
| `RecordsFfmpegOps.probePath` | returns -1 (duration unknown); runs per visible row |
| `FragmentedMp4Remuxer` | remux no-ops, playback continues without precise seeking |
| `BatchFfmpegOpsImpl` | that task fails, the batch continues |
| `FaditorMiniFragment` | explains and opens the download sheet instead of launching |
| `FaditorEditorActivity` | explains and finishes, if reached anyway |
| `FullVideoInfoProbe` | returns null, sheet falls back to MediaMetadataRetriever |
| `OpenCvMog2MotionDetector` | throws on purpose; `RecordingService` catches and disables motion for the session |

Audit after touching any of them:

```bash
for f in $(grep -rl ensureLoaded app/src --include=*.java); do
  grep -B3 ensureLoaded "$f" | grep -qE "catch \(|try \{" || echo "UNGUARDED $f"
done
```

## Lite

Everything lives in `src/full/java/com/fadcam/assets/`. Lite compiles none of it and its
APK contains zero references to the package, OpenCV or ffmpeg-kit. Where a shared fragment
needs a banner, it is resolved reflectively so Lite still links.

## Licensing

OpenCV and RF-DETR are Apache-2.0. ffmpeg-kit is **GPL-3.0** because it is built with
`--enable-gpl --enable-x264`, which is compatible with FadCam's GPL-3.0. The
source-availability obligation is met by the assets repo's `SOURCE-OFFER.md`; the build
recipe is in `BUILDING.md` there.

Every `.so` in the published bundles is byte-identical to the ones the app shipped before,
verified by SHA-256, with one documented exception: `libc++_shared.so` in the ffmpeg bundle
is OpenCV's build, so both bundles share one C++ runtime.

## Gotchas

- `packaging { jniLibs { pickFirsts } }` **overrides** `excludes` for the same path. A
  library can be excluded and still be packaged if it is also picked first.
- ffmpeg-kit reports its package label as `min` because a build-config string comes back
  empty in this AAR. It is a labelling bug, not a reduced build: `-encoders` reports
  `libx264`, `libmp3lame`, `libvorbis`, `libopus`, `libvpx`, and every `.so` is identical to
  the full AAR. Assert on capability, never on the label.
- An asset with more than one manifest entry (the models have two graphs) must install as a
  set. Installing per entry clears the directory between them and deletes what was just
  written — that bug shipped once and broke the models.
