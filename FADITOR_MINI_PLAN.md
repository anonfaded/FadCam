# Faditor Mini — Scalable Video Editor Plan

## Architecture Overview

A scalable, CapCut-style video editor built on **Media3 (ExoPlayer + Transformer)** — Google's official Android editing stack. This architecture ensures:
- **No refactoring needed** as features scale — the same data model supports trim, split, multi-clip, effects, overlays, and audio mixing
- **Battle-tested playback** via ExoPlayer (no custom OpenGL video pipelines)
- **Hardware-accelerated export** via Media3 Transformer (MediaCodec under the hood)
- **Effect parity** — same `Effect` classes work for both real-time preview AND export

### Navigation Architecture Decision

**Separate Activity** (`FaditorEditorActivity`) is used instead of a fragment overlay because:
- **SurfaceView z-ordering** — ExoPlayer's `PlayerView` uses `SurfaceView`, which has special z-order behavior inside overlay `FrameLayout`s
- **Memory isolation** — All 5 tab fragments stay alive via `offscreenPageLimit`. A separate Activity releases all editor resources cleanly on `finish()`
- **Independent lifecycle** — ExoPlayer + Transformer lifecycle management is simpler in a self-contained Activity
- **Orientation support** — Editor can support landscape mode independently
- **Clean result passing** — Activity Result API returns exported file path back to `FaditorMiniFragment`

**Flow:** `FaditorMiniFragment` (tab) → video picker → `FaditorEditorActivity` (full-screen) → finish → back to tab

---

## Technology Stack

| Layer | Technology | Why |
|-------|-----------|-----|
| **Playback/Preview** | Media3 ExoPlayer + PlayerView | Handles seeking, buffering, effects preview. Already in project (v1.8.0) |
| **Export/Processing** | Media3 Transformer | Sole export engine — trim, concat, effects, speed. Hardware-accelerated via MediaCodec |
| **Lossless Trim** | Transformer + `experimentalSetMp4EditListTrimEnabled(true)` | Frame-accurate near-lossless trim — only re-encodes tiny pre-roll before first keyframe, rest is stream-copied. Best quality + precision |
| **Thumbnails** | Media3 FrameExtractor | Extract frames for timeline thumbnails |
| **UI** | Material 3 + Custom Views | Consistent with FadCam design system |
| **Persistence** | JSON files + SharedPreferences | Simple, no Room DB needed initially |

> **Why not FFmpeg for trim?** FFmpeg `-c copy` is truly lossless but can only cut at keyframes (I-frames), not arbitrary frames — trim may be off by 0-2 seconds. For a CapCut-style editor, frame-accurate cutting is essential. Media3 Transformer's edit‑list mode gives near-identical quality with precise frame accuracy. **One pipeline for everything = less code, zero inconsistency.**

### Dependencies to Add

```toml
# gradle/libs.versions.toml - add these entries
media3-transformer = { module = "androidx.media3:media3-transformer", version.ref = "media3" }
media3-effect = { module = "androidx.media3:media3-effect", version.ref = "media3" }
```

---

## Core Data Model (Scalable from Day 1)

This model supports everything from simple trim to multi-clip editing with effects:

```
Project
├── id: String (UUID)
├── name: String
├── createdAt: long
├── lastModified: long
├── timeline: Timeline
│   ├── clips: List<Clip>     ← ordered sequence of clips
│   │   ├── Clip
│   │   │   ├── id: String
│   │   │   ├── sourceUri: Uri          ← original video file
│   │   │   ├── inPointMs: long         ← start position in source
│   │   │   ├── outPointMs: long        ← end position in source
│   │   │   ├── speedMultiplier: float  ← 0.25x to 4x (default 1.0)
│   │   │   └── effects: List<Effect>   ← filters, transforms, etc.
│   │   └── ...more clips
│   └── audioTrack: AudioTrack (future)
└── exportSettings: ExportSettings
    ├── resolution: Resolution
    ├── quality: Quality
    └── format: Format
```

**Why this scales:**
- **MVP (trim):** Single clip with `inPointMs`/`outPointMs`
- **Split:** Splits one clip into two clips at the playhead position
- **Multi-clip:** Add/remove/reorder clips in the list
- **Speed:** Set `speedMultiplier` per clip
- **Effects:** Add `Effect` objects per clip (same classes used by ExoPlayer preview AND Transformer export)
- **Audio:** Add `AudioTrack` alongside video clips

### Mapping to Media3

```java
// Each Clip maps to:
MediaItem mediaItem = new MediaItem.Builder()
    .setUri(clip.getSourceUri())
    .setClippingConfiguration(new ClippingConfiguration.Builder()
        .setStartPositionMs(clip.getInPointMs())
        .setEndPositionMs(clip.getOutPointMs())
        .build())
    .build();

EditedMediaItem editedItem = new EditedMediaItem.Builder(mediaItem)
    .setEffects(clip.getMedia3Effects())  // Same Effect objects!
    .build();

// Timeline maps to:
EditedMediaItemSequence sequence = new EditedMediaItemSequence.Builder(trackTypes)
    .addItems(editedItem1, editedItem2, ...)
    .build();

Composition composition = new Composition.Builder(sequence).build();

// Preview: ExoPlayer plays the MediaItems with effects
// Export: Transformer exports the Composition
```

---

## Module Structure

```
com.fadcam.ui.faditor/
├── FaditorEditorActivity.java          ← Full-screen editor Activity
├── model/
│   ├── Clip.java                     ← Single video segment
│   ├── Timeline.java                 ← Ordered list of clips
│   ├── FaditorProject.java           ← Project wrapper
│   └── ExportSettings.java           ← Export configuration
├── player/
│   └── FaditorPlayerManager.java     ← ExoPlayer setup & lifecycle
├── timeline/
│   └── TimelineView.java             ← Custom timeline widget with trim handles
├── export/
│   └── ExportManager.java            ← Transformer orchestration + lossless trim
├── project/
│   ├── ProjectStorage.java           ← JSON persistence (Gson, auto-save)
│   └── ProjectBrowserAdapter.java    ← RecyclerView adapter (Phase 4)
└── util/
    └── TimeFormatter.java            ← Time display formatting

com.fadcam.ui/
└── FaditorMiniFragment.java          ← Tab entry point (video picker → launches editor)
```

**Each file stays under 300 lines. No monoliths.**

---

## Implementation Phases

### Phase 1 — MVP: Single Video Trim (Core Foundation)

**Goal:** Pick a video → preview with ExoPlayer → set trim points → export trimmed clip

#### Tasks

- [x] 1. **Add Media3 Transformer dependency**
  - Added `media3-transformer` and `media3-effect` to `libs.versions.toml` and `app/build.gradle.kts`

- [x] 2. **Create data models**
  - `Clip.java` — sourceUri, inPointMs, outPointMs, speedMultiplier
  - `Timeline.java` — List<Clip>, methods: `addClip()`, `removeClip()`, `getTotalDurationMs()`
  - `FaditorProject.java` — wraps Timeline + metadata
  - `ExportSettings.java` — resolution, quality, format enums

- [x] 3. **Build FaditorMiniFragment (entry point)**
  - Replaced placeholder with video picker button (Material 3 style)
  - "Select Video" launches system file picker (ACTION_OPEN_DOCUMENT, type video/*)
  - On video selected → launches FaditorEditorActivity with the URI
  - Uses ActivityResultLauncher for both picker and editor results
  - Takes persistable URI permission for cross-Activity access

- [x] 4. **Build FaditorEditorActivity (editor screen)**
  - Layout: PlayerView (top) + TimelineView (middle) + Toolbar (bottom)
  - Separate Activity — automatically hides bottom navigation (full-screen editing)
  - Close button → finish Activity → return to FaditorMiniFragment tab
  - Dark theme (black background, StatusBar and NavigationBar matched)
  - Keeps screen on during editing (FLAG_KEEP_SCREEN_ON)
  - Registered in AndroidManifest.xml

- [x] 5. **Implement FaditorPlayerManager**
  - Initialize ExoPlayer with the clip's MediaItem + ClippingConfiguration
  - Bind to PlayerView for playback controls
  - Expose: `play()`, `pause()`, `seekTo(ms)`, `getCurrentPosition()`, `getDuration()`
  - Handle lifecycle via DefaultLifecycleObserver (auto-pause, release on destroy)
  - Supports trim bounds update without full reload

- [x] 6. **Build TimelineView (custom View)**
  - Track with active (green) region and dimmed excluded regions
  - Left/right trim handles (draggable with touch handling)
  - Playhead indicator synced with ExoPlayer position (orange-red line + circle)
  - TrimChangeListener for real-time + finished callbacks
  - PlayheadChangeListener for seek-on-tap
  - Prevents handle overlap (min gap enforcement)

- [x] 7. **Build EditorToolbar**
  - For MVP: "Trim" tool label (active by default, Material Icons font)
  - Export button in top bar (Material 3 TonalButton)
  - Close button in top bar

- [x] 8. **Implement ExportManager**
  - Uses Media3 Transformer exclusively (single pipeline for everything)
  - Simple trim: `experimentalSetTrimOptimizationEnabled(true)` — near-lossless, frame-accurate
  - ExportListener interface for started/progress/completed/error callbacks
  - Save to DCIM/FadCam directory with "Faditor_" prefix + timestamp
  - Cancel support during export
  - Builds Composition from Timeline clips (scalable to multi-clip)

- [x] 9. **Export progress UI**
  - ProgressComponent overlay during export (Material 3 LinearProgressIndicator)
  - Cancel button with fade animation
  - Success → Toast notification
  - Error → Toast with message

- [x] 10. **String resources**
  - All user-facing text in `strings.xml` with `faditor_` prefix
  - Includes: editor title, play/pause, export, cancel, progress, errors

- [ ] 11. **TimelineView thumbnail strip** (enhancement)
  - Extract frames using FrameExtractor / MediaMetadataRetriever
  - Replace solid color track with actual video thumbnails

- [ ] 12. **Discard confirmation dialog**
  - Show "Discard changes?" dialog on close if trim points changed from original

- [x] 13. **Project persistence (ProjectStorage)**
  - JSON serialization via Gson with custom adapters for Uri, FaditorProject, Clip
  - Auto-save on trim finish (debounced 3s) and on pause/close
  - Save to `files/faditor/projects/{projectId}/project.json`
  - Load/list/delete projects API
  - `ProjectSummary` lightweight class for listing without full deserialization

- [x] 14. **FaditorMini home page redesign**
  - Hero section with icon, tagline, and full-width "New Project" button
  - Recent Projects section (auto-populated from ProjectStorage, up to 5)
  - Feature capability cards grid (Trim, Export, Preview, Privacy)
  - ScrollView for full content, consistent header bar with other tabs
  - Version info footer

- [x] 15. **Editor UI Material Design polish**
  - Custom `Theme.FadCam.FaditorEditor` dark theme (Material3.Dark.NoActionBar)
  - `fitsSystemWindows="true"` — status bar no longer overlaps content
  - Material Icons font for all buttons (close, play/pause, trim, export movie icon)
  - Dark surface colors (#0D0D0D bg, #1A1A1A surfaces, #141414 toolbar)
  - Proper spacing and elevation hierarchy

---

### Phase 2 — Split & Multi-Clip

**Goal:** Split video at playhead, manage multiple clips, reorder

#### Tasks

- [ ] 11. **Split tool**
  - Split current clip at playhead position → creates 2 clips
  - Timeline updates to show both clips
  - Each clip independently trimmable

- [ ] 12. **Multi-clip timeline**
  - TimelineView shows multiple clip thumbnails in sequence
  - Tap a clip to select it (highlight border)
  - Delete selected clip (with undo)
  - Drag to reorder clips (long-press + drag)

- [ ] 13. **Multi-clip playback**
  - ExoPlayer plays clips in sequence using `ConcatenatingMediaSource2`
  - Each clip respects its own in/out points via ClippingConfiguration
  - Playhead spans full composed timeline

- [ ] 14. **Multi-clip export**
  - Transformer exports `Composition` with all clips as `EditedMediaItemSequence`
  - Proper ordering and trimming preserved

---

### Phase 3 — Speed Control & Undo/Redo

**Goal:** Per-clip speed, undo/redo stack

- [ ] 15. **Speed tool**
  - Speed selector: 0.25x, 0.5x, 0.75x, 1x, 1.5x, 2x, 3x, 4x
  - Applies to selected clip
  - Preview reflects speed change via `ExoPlayer.setPlaybackSpeed()` for single clip
  - Export uses `SpeedChangeEffect` from Media3

- [ ] 16. **Undo/redo**
  - Command pattern: each edit operation recorded as undoable action
  - Undo button in toolbar
  - Stack of: trim, split, delete, reorder, speed change operations

---

### Phase 4 — Project Management

**Goal:** Save/load projects, project browser

- [ ] 17. **Project persistence**
  - Save project as JSON to `app-specific-storage/faditor/projects/{id}/project.json`
  - Auto-save on edit (debounced, 5-second delay)
  - Save on navigate away

- [ ] 18. **Project browser**
  - FaditorMiniFragment becomes grid of saved projects
  - Each card: thumbnail + title + duration + last modified
  - Create new project button
  - Long-press: rename, delete, duplicate

- [ ] 19. **Auto-save**
  - Using Handler with 5-second debounce
  - Save-on-exit guaranteed
  - Recovery: detect unsaved state on app restart

---

### Phase 5 — Effects & Filters

**Goal:** Visual effects that work in both preview and export

- [ ] 20. **Built-in effects using Media3 Effect API**
  - Brightness/Contrast adjustment (`RgbMatrix`)
  - Color filters (grayscale, sepia, warm, cool) (`ColorLut`)
  - Blur effect (`GaussianBlur`)
  - Rotation/Scale (`ScaleAndRotateTransformation`)
  - Crop (`Crop`)

- [ ] 21. **Effects panel UI**
  - Bottom sheet with effect thumbnails
  - Preview effect on selected clip instantly via `ExoPlayer.setVideoEffects()`
  - Same Effect objects used for export — zero inconsistency

---

### Phase 6 — Text Overlays

- [ ] 22. **Text overlay**
  - Add text at specific time range
  - Font, size, color, position
  - Uses `TextOverlay` from Media3 effect API
  - Preview via ExoPlayer, export via Transformer

---

### Phase 7 — Audio

- [ ] 23. **Audio controls**
  - Mute original audio per clip
  - Volume adjustment per clip
  - Add background music track (separate `EditedMediaItemSequence`)
  - Audio ducking (lower music volume when original audio active)

---

### Phase 8 — Transitions (Future)

- [ ] 24. **Transitions between clips**
  - Crossfade, fade to black, wipe
  - Custom transition shaders via `GlEffect`

---

## Key Architecture Decisions

### Why ExoPlayer for preview (not custom OpenGL)?
- ExoPlayer handles frame-accurate seeking, buffering, format support, and surface rendering **out of the box**
- `PlayerView` gives free play/pause/seek UI
- `setVideoEffects()` previews the exact same effects used during export
- No synchronization bugs — ExoPlayer manages its own timing

### Why Media3 Transformer for export (sole pipeline)?
- **One engine for everything** — trim, split, multi-clip, effects, speed, audio mixing
- Same `Effect` classes as ExoPlayer — **what you see in preview = what you get in export**
- Hardware-accelerated via MediaCodec (GPU encoding)
- `experimentalSetMp4EditListTrimEnabled(true)` gives near-lossless quality for simple trims — only re-encodes a tiny pre-roll before the first keyframe, rest is stream-copied
- Frame-accurate cutting (unlike FFmpeg `-c copy` which can only cut at keyframes)
- Handles multi-clip `Composition` natively
- No FFmpeg export path = simpler code, one set of bugs to fix, zero inconsistency

### Why this model scales without refactoring?
- Adding split = splitting one Clip into two Clips in the same List<Clip>
- Adding effects = adding Effect objects to Clip.effects (same API preview + export)
- Adding multi-clip = the List<Clip> already supports it
- Adding audio = add AudioTrack to Timeline (new EditedMediaItemSequence)
- Adding speed = set Clip.speedMultiplier (maps to SpeedChangeEffect)

### File size discipline
- Every class < 300 lines
- If bigger → extract component
- FaditorEditorFragment orchestrates, components do the work
- No "Fix Start/Fix End" hack blocks — clean architecture from the start

---

## Quick Reference: Media3 API Mapping

| Faditor Concept | Media3 Class |
|----------------|-------------|
| Video clip | `MediaItem` + `ClippingConfiguration` |
| Clip with effects | `EditedMediaItem` + `Effects` |
| Multi-clip sequence | `EditedMediaItemSequence` |
| Full timeline | `Composition` |
| Speed change | `SpeedChangeEffect` |
| Color filter | `RgbMatrix`, `ColorLut` |
| Rotation/Scale | `ScaleAndRotateTransformation` |
| Text overlay | `TextOverlay` |
| Export | `Transformer.start(composition, outputPath)` |
| Preview effects | `ExoPlayer.setVideoEffects(effects)` |
| Frame extraction | `FrameExtractor` |
| Near-lossless trim | `experimentalSetMp4EditListTrimEnabled(true)` — frame-accurate, stream-copies most of the video |

---

## What to Do with PR #185

**Close it.** The custom OpenGL video pipeline, duplicate player components, 2000+ line monolithic files, and 21 hack blocks make it unsuitable as a base. The data models and UI layouts are too tightly coupled to the broken architecture to salvage.

We start clean on `master`, building on proven Media3 APIs that Google designed exactly for this use case.
