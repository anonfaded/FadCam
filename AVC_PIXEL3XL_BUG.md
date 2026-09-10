# AVC Corruption on Google Pixel 3 XL — Full Context & Analysis

> Live document for the device-specific AVC bug. Add new findings here as they
> land; this is the single source of truth for future debugging sessions.

## TL;DR

On a **Pixel 3 XL** (QCOM SD845), **H.264/AVC recordings contain only a handful
of video frames** (0.5–2 fps) while audio is complete. Files are structurally
valid MP4s, but playback is "a few frames, then broken" — the built-in player
throws `Invalid NAL length`, VLC shows a few frames with fine audio.
**HEVC on the same device is fine. Everything on Pixel 7 Pro is fine.**

- Reported symptom: "correct file size, won't play, few frames, audio fine".
- Mid-recording playback WORKS (sparse frames play + scrub). After STOP it
  appears corrupted — the finalization is NOT the root cause; the video data
  itself is missing.

## Reproduction

1. Pixel 3 XL, local (streaming server OFF), video codec = AVC.
2. Record ~10s at any resolution/bitrate.
3. Result: video track has 5–14 samples at ~2s PTS gaps; audio complete.
4. Same device with HEVC: fine. Streaming server ON (CBR 5Mbps): fine (28fps observed).
5. Pixel 7 Pro (c2.exynos.h264.encoder, 4K60 VBR): fine — ~120 video
   samples per ~2s fragment (`[AVCC-CONV] fragment#1 track1=64 track2=120`).

## Evidence files analyzed

| File | Video samples | Video duration | Audio samples | Notes |
|---|---|---|---|---|
| `Stream_20260909_005100_pixel3xl.mp4` | 5 (7.3KB) | 6.17s (~2s gaps) | 352 (7.5s) | ffmpeg decodes clean; NAL chain valid |
| `FadCam_20260909_153515_pixel3xl-Video2.mp4` | 14 (617KB) | 24.59s (~2s gaps) | 1152 (24.6s) | 0.57 fps; structure valid; sample #1 = SPS+PPS+IDR in-band |
| P7Pro fragment (log) | 120 per 2s fragment | — | 64 per fragment | healthy reference |

Both 3XL files: `ffmpeg -v error` decodes with **zero errors**; stsz sizes match
the actual AVCC NAL boundaries (chain-walked by hand). The MP4 is honest — the
video track genuinely contains almost no frames.

## Architecture (how a recording is built)

```
Camera2 ──frames──▶ SurfaceTexture (cameraInputSurface)
                        │ onFrameAvailable
                        ▼
GLWatermarkRenderer.renderFrame()   [GL render thread]
   ├─ renderToEncoderInternal(): wait≤17ms for new frame → EGL draw → encoder surface
   │     (frame.wait budget, "SLOW renderToEncoder" >80ms warning)
   └─ renderToPreview()
                        │ encoder input surface
                        ▼
MediaCodec (c2.qti.avc.encoder on 3XL) — emits ANNEX-B samples
                        │ drain thread (non-blocking, 2ms loop)
                        ▼
FragmentedMp4MuxerWrapper (app) ──▶ media3-patched FragmentedMp4Writer
   ├─ processTrack(): per-sample Annex-B → AVCC conversion
   │     · zero-length conversion results are DROPPED (audio-safe)
   │     · post-conversion sizes recorded for the moov (commit 4db7c12)
   └─ writes fMP4 fragments (moof/mdat, ~1.5–2s each) to the final file
                        │ stop
                        ▼
performHybridFinalization(): append full moov (stsz/stco/stts from fragment
trun bookkeeping), patch `free` → `mdat` header. No media bytes are copied.
```

The final file is a "hybrid" MP4: ftyp + real moov + one big mdat that is the
fragment media data in place.

## Where the patched media3 lives

- **`/Users/faded/Documents/repos/github/media3-patched`** — one folder back from
  this repo. The app consumes it via composite build (`includeBuild`).
- Relevant writer: `libraries/muxer/src/main/java/androidx/media3/muxer/FragmentedMp4Writer.java`
- Relevant converter: `libraries/muxer/src/main/java/androidx/media3/muxer/AnnexBToAvccConverter.java`
- Relevant detection: `libraries/muxer/src/main/java/androidx/media3/muxer/AnnexBUtils.java`
  (`doesSampleContainAnnexBNalUnits` → true for H264 AND H265)

### media3-patched fix history (relevant commits)

| Commit | What |
|---|---|
| `3bc7112` | AVCC conversion diagnostics + sample framing audit tooling |
| `8fa5bf2` | Correct Annex-B detection; skip re-finalization of finalized files |
| `e077bcf` | Drop zero-length samples during AVCC conversion (was corrupting trun) |
| `4db7c12` | Record **post-conversion** sample sizes for the appended moov |
| `da12703` | Build moof/mdat on drain thread to prevent AVCC sample corruption |
| `277b940` | 32-bit chunk offsets when possible |
| `3a9f183` | Hybrid MP4 finalization (OBS-style) |
| `45d5ab5`/`d3e37f6`/`83c684c` | Abandoned-file finalizer (OEM kills) |

### FadCam repo AVC/Samsung fix history

- `f2dbd6a4` — QCOM AVC prepend-sps-pps quirk: for codec names containing
  `"qcom"` (OMX-era), sets `KEY_PREPEND_HEADER_TO_SYNC_FRAMES=0` so CSD is
  exposed in the output format (S20 FE SM-G780G / Android 13 fix).
  ⚠️ **Note**: this matches `"qcom"` only — the 3XL's `c2.qti.avc.encoder` does
  NOT match, so on the 3XL prepend stays =1 (in-band SPS/PPS in every IDR,
  visible in `[AVC-TRACE]` sample#1).
- `fec8afd0` / `05560a7f` — SPS/PPS capture from codec config (Annex-B start
  codes preserved) for AVC stream registration.
- `6cb64c64` / `11564aa2` — post-finalize AVC audit + first-5-sample framing trace.
- `e36f1a01` — ParcelFileDescriptor handling for Samsung devices.

## Current hypotheses (ranked)

1. **Encoder/frame starvation (leading)** — the 3XL AVC encoder only receives
   ~0.5fps, while HEVC receives 30fps, on the same camera. Candidates:
   - `renderRunnableQueued` CAS gate stuck after a GL-handler queue purge
     (ACTION_CHANGE_SURFACE / camera reconfigure calls
     `handler.removeCallbacksAndMessages(null)` — if a posted renderRunnable is
     removed, its `finally { renderRunnableQueued.set(false) }` never runs and
     the gate stays true, killing frame posting). **Mitigation shipped:**
     reset the gate next to the queue purge + `[FRAME] render gate busy` diag.
   - SurfaceTexture queue/buffer quirk on 3XL (`onFreeBufferLocked: buffer not
     found in mEglData` seen on BOTH devices, worse on 3XL).
   - c2.qti.avc.encoder-specific: VBR + KEY_PRIORITY=0 + prepend=1 + capture
     rate float — unknown vendor quirk producing ~0.5fps (why server-ON/CBR
     sessions were healthy at 28fps).
2. **Writer back-pressure loop** — eglSwapBuffers blocks while the writer
   flushes each ~2s fragment; render cadence collapses to fragment cadence
   (matches the exact ~2s PTS gaps). Server-ON sessions had tiny fragments
   (5Mbps) so no back-pressure — explains why they were healthy.
3. **In-band SPS/PPS in sample #1** — causes `Invalid NAL length` in the
   built-in (media3) player for the resulting sparse files; secondary symptom,
   not the cause of frame loss (P7Pro files also carry in-band SPS and play).

## Logging catalog (all shipped, no behavior changes)

| Tag | Where | Purpose |
|---|---|---|
| `[FRAME]` / `[FRAME-GAP]` | GLRecordingPipeline | camera frame cadence; gap>500ms flagged |
| `[FRAME] render gate busy` | GLRecordingPipeline | gate-stuck detection |
| `[RENDER-STATS]` | stopRecording | frames, avg/max render ms, wait timeouts |
| `[DRAIN]` | drainEncoder (every 60) | encoder output sample pts/size |
| `[AVC-TRACE]` (first 5) | GLRecordingPipeline | encoder sample framing/flags |
| `[AVC-DIAG]` | media3 writer | converted sample sizes/heads |
| `[AVCC-CONV] fragment#` | media3 writer | per-fragment per-track samples/bytes |
| `[FRAG-TRACK]` | media3 writer | converted/dropped/pre/post bytes |
| `[FRAG-WRITE]` | media3 writer | fragment build time + per-track counts |
| `[SEG-WRITE]` | FragmentedMp4MuxerWrapper | file I/O stalls >300ms |
| `[HYBRID]` | wrapper | audio/video sample totals before finalize |
| `[AVC-AUDIT]` | wrapper | first video sample framing in final file |
| `[REMOTE]` | CloudStatusManager | dashboard recording_toggle arrival |

## Test procedure (Pixel 3 XL, new beta build)

1. Local AVC ~15s → stop.
2. Pull logcat and extract:
   `grep -E "FRAME|RENDER-STATS|DRAIN|AVCC-CONV|FRAG-WRITE|SEG-WRITE|FRAG-TRACK|HYBRID|AVC-AUDIT|SLOW render|gate busy"`
3. Decide from the log:
   - `[FRAME-GAP]` present → camera delivery problem (then compare with HEVC run).
   - `[RENDER-STATS] maxMs` huge → GL/EGL back-pressure.
   - `[FRAG-WRITE] tookMs` / `[SEG-WRITE]` huge → writer/I-O back-pressure.
   - `[AVCC-CONV] fragment# track2=N` small (1–3) with `[FRAME]` at 30fps →
     encoder/drain starvation.
   - `[FRAME] render gate busy` constant + 2s cadence → gate stuck (fix shipped).
4. Repeat the same with HEVC → diff the two runs; the differentiator IS the bug.

## Open questions

- Why does server-ON (CBR 5Mbps) record at 28fps on the 3XL but server-OFF (VBR)
  starves? Bitrate-mode alone shouldn't gate frame delivery — needs the above
  logs on one clean run each.
- Is the ~2s gap EXACTLY fragment cadence? `[FRAG-WRITE]` timestamps will tell.
- Does the gate-reset fix alone restore 30fps? (Hypothesis 1a test.)
