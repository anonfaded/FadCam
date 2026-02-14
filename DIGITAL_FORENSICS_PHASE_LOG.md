# Digital Forensics Phase Log

## Current Status
- Active phase: **Phase 5 (Hard cutover + detector unification)**
- Last updated: 2026-02-13

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
- [ ] Live preview bounding-box overlay renderer (toggle exists, render pipeline not yet implemented)
- [ ] Vehicle/Pet/Dangerous event capture models (settings rows are present, runtime model integration pending)

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
