# Digital Forensics Phase Log

## Current Status
- Active phase: **Hard-cut runtime integration + evidence persistence**
- Last updated: 2026-02-15

## Hard-Cut Runtime Integration (2026-02-15)
### Completed
- [x] Reworked detector input path to copy YUV frame buffers before downstream inference usage
- [x] Updated `RecordingService` heartbeat to push full detection lists to forensics recorder
- [x] Replaced stop-only event creation with real-time multi-event aggregation in `DigitalForensicsEventRecorder`
- [x] Added persistent snapshot evidence writes during recording (`files/FadCam/Forensics/Snapshots/...`)
- [x] Added new Room entity/DAO: `AiEventSnapshotEntity`, `AiEventSnapshotDao`
- [x] Extended `AiEventEntity` with lifecycle + alert fields for future push/Discord pipeline
- [x] Updated timeline DAO projection and filtering contract for new fields
- [x] Updated timeline adapter to render snapshot-backed strips (not hardcoded 3-frame only)
- [x] Added media-missing UX guardrails in event click handlers
- [x] Refactored Lab root UI to events-first and added top-right sidebar menu
- [x] Added `LabSidebarFragment` and drawer layout for Insights/admin routes
- [x] Preview overlay now renders in preview content viewport bounds to reduce box distortion from letterbox scaling

### Verification
- [x] `./gradlew :app:compileDefaultDebugJavaWithJavac -x lint` passes after integration

## Lab Tab / Forensic Intelligence Migration (2026-02-14)
### Completed
- [x] Added bottom navigation 6th item (`Lab`)
- [x] Added tab-root fragment: `ForensicIntelligenceFragment`
- [x] Added top title: `Forensic Intelligence`
- [x] Embedded Events + Insights as first-class Lab sections via internal tabs
- [x] Updated main pager wiring (`ViewPagerAdapter` item count `6`, position `5` mapped to Lab)
- [x] Updated `MainActivity` nav selection/page sync for `navigation_lab`
- [x] Removed Settings-side Events/Insights rows from Digital Forensics settings screen
- [x] Standardized timeline subtitle to include explicit local date/time from DB (`detected_at_epoch_ms`)
- [x] Added persistent frame-strip scroll hint in event cards
- [x] Updated forensics DB policy to latest clean schema path (`version=4`, destructive reset allowed)

### Notes
- Date/time persistence already existed in schema (`ai_event.detected_at_epoch_ms`, media/link timestamps); this phase focused on consistent UI exposure and canonical Lab access path.
- Settings remains control-only; browsing and intelligence review now lives in Lab.

## Hard Cutover - Single EfficientDet (2026-02-13)
### Completed
- [x] Added `EfficientDetLite1Detector` as unified detector path
- [x] Rewired `RecordingService` to use EfficientDet only
- [x] Removed legacy classes: `PersonDetector`, `NoOpPersonDetector`, `TflitePersonDetector`
- [x] Removed legacy model asset: `assets/models/person_detector.tflite`
- [x] Unified event typing from detector outputs (`PERSON`, `VEHICLE`, `PET`, `OBJECT`)
- [x] Removed per-class event toggles from Digital Forensics settings UI (always-on multi-class detection)
- [x] Removed Motion Lab trigger mode selector from UI (single trigger mode path)
- [x] Updated forensics event recorder to persist detector bbox directly
- [x] Updated preview overlay payload to use detector bbox + orientation + front-camera mirroring
- [x] Kept AI overlay preview-only (not encoded in recording path)
- [x] Added RGB YUV_420_888 preprocessing for EfficientDet input (replaces grayscale-only feed)
- [x] Switched inference runtime to TFLite Task Vision ObjectDetector (uses model metadata labels; removed manual labels file path)
- [x] Added exact class-name + confidence overlay labels (`<class> <percent>%`)
- [x] Added independent watermark + overlay rendering so both stay visible in preview
- [x] Added dynamic subtype chips in Events Timeline (from stored class names)
- [x] Removed Daily Summary / Heatmap toggles from DF settings (always-on behavior)

### Findings
- Single-model architecture now powers MotionLab person confirmation and Digital Forensics event classes.
- Overlay geometry no longer relies on synthetic area-based square boxes.
- Any detector initialization failure now hard-stops service startup (no legacy fallback path).

## Phase 0 - UX Shell + Navigation
### Completed
- [x] Added **Digital Forensics** row in `fragment_settings_advanced.xml`
- [x] Wired navigation from `AdvancedSettingsFragment` to `DigitalForensicsSettingsFragment`
- [x] Added `DigitalForensicsSettingsFragment` screen with:
  - [x] Master enable toggle
  - [x] Event class toggles (person/vehicle/pet)
  - [x] Dangerous object toggle
  - [x] Overlay toggle
  - [x] Daily summary toggle
  - [x] Heatmap toggle
  - [x] Discord alerts Coming Soon row
- [x] Added preference keys in `Constants.java`
- [x] Added preference getters/setters in `SharedPreferencesManager.java`
- [x] Added strings for Digital Forensics UI
- [x] Added roadmap files:
  - [x] `DIGITAL_FORENSICS_ROADMAP.md`
  - [x] `DIGITAL_FORENSICS_PHASE_LOG.md`

### Findings
- Current implementation is UI + preference shell only (expected for Phase 0).
- Motion Lab remains independent and unaffected.
- Discord integration intentionally placeholder-only.

### Open items before Phase 1
- [ ] Define Room package location and naming conventions
- [ ] Freeze migration policy (versioning + fallback)
- [ ] Finalize exact fingerprint sampling algorithm
- [ ] Finalize visual fingerprint extraction strategy

## Phase 1 - DB & Identity Foundation
### Completed (baseline implementation)
- [x] Added Room dependencies and local DB module (`digital_forensics.db`)
- [x] Added entities: `media_asset`, `ai_event`, `integrity_link_log`, `sync_queue`
- [x] Added DAOs and `ForensicsDatabase` singleton
- [x] Implemented exact fingerprint baseline (sampled SHA-256)
- [x] Implemented visual fingerprint baseline (keyframe aHash)
- [x] Implemented relink engine with exact/probable policy
- [x] Added non-blocking Records pipeline indexing hook
- [x] Persisted `link_status` for future UI badges

### Findings
- Room schema is in place and compile-valid.
- Records loading remains non-blocking; indexing runs on a separate single-thread executor.
- Current visual fingerprint is a lightweight baseline; future phases can improve robustness for heavy re-encodes.

### Next (Phase 2 prep)
- [x] Emit structured AI events into `ai_event` from analysis pipeline (Motion START/STOP)
- [x] Add thumbnail references for events (timeline reference string)
- [ ] Add event retention policy and cleanup strategy

## Phase 2 - Event Capture Integration
### Completed (baseline implementation)
- [x] Added `DigitalForensicsEventRecorder` async writer
- [x] Hooked Motion Lab transition actions to event recorder in `RecordingService`
- [x] Persisted PERSON-class events with confidence/priority to `ai_event`
- [x] Ensured media linkage by creating/finding `media_asset` rows before event write
- [x] Added flush on service stop to avoid dangling active events

### Findings
- Baseline events are transition-based (session segments of detected motion), not per-frame spam.
- Event writes run off main thread and did not alter motion recording control logic.
- `thumbnail_ref` currently stores timeline reference (`uri#t=...`) as lightweight placeholder.

## Phase 3 - Events UX
### Completed
- [x] Added Events Timeline screen (`ForensicsEventsFragment`) with list rendering
- [x] Added filters (event type + high confidence, with recent-window filter)
- [x] Added click-to-open video at event timestamp (seek extra) and open paused to preserve context
- [x] Added link quality badge rendering in timeline rows
- [x] Added Records sidebar entry to open Events Timeline
- [x] Added per-event proof thumbnail extraction in list rows
- [x] Removed conflicting checked icon on High-confidence chip

## Phase 4 - Insights + Overlay
### Completed
- [x] Added Activity Insights screen (`ForensicsInsightsFragment`)
- [x] Added daily summary counts for last 24h
- [x] Added heatmap rendering from event bbox centers
- [x] Tuned heatmap dot radius/opacity and added legend text for readability
- [x] Removed AI event label injection from video watermark text

### Pending
- [ ] Validate class balance with real-world scenes and tune confidence thresholds for low-light false positives

## Phase 3.5 - Reliability Fixes (new)
### Completed
- [x] Added motion centroid extraction in both detectors (`OpenCvMog2MotionDetector`, `FrameDiffMotionDetector`)
- [x] Propagated centroid debug data through `RecordingService` into forensics events
- [x] Added forensics heartbeat updates while recording, so long events can be promoted from MOTION -> PERSON
- [x] Tightened forensics capture gating to active detector support (person/motion path)

## Phase 5 - Hardening
### Completed
- [x] Added DB migration `1 -> 2` for event metadata evolution (`detected_at_epoch_ms`)
- [x] Added relink guard to avoid weak probable matching without usable fingerprints
- [x] Kept heavy work async (indexing + event writes) to preserve recording pipeline responsiveness
- [x] Re-verified compile after all phase integrations

## Notes
- Keep all heavy compute asynchronous.
- Preserve existing Records performance characteristics.
- Do not couple Digital Forensics toggles to Motion Lab control flow in this phase.
