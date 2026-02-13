# Motion Lab Roadmap (Hybrid Motion + Person Confirm)

## Objective
Ship a production-ready, open-source, scalable motion-triggered recording system for FadCam with strong accuracy and low power overhead.

- Stage 1: fast motion detection (OpenCV)
- Stage 2: lightweight person confirmation (TFLite)
- Event-based recording lifecycle with debounce/pre-roll/post-roll
- Advanced-only controls for power users (`Advanced > Motion Lab`)

## Scope and Defaults
- Motion Lab is **OFF by default**.
- Initial release is in Advanced settings only.
- Low-FPS fallback is **manual toggle**, not automatic.
- Existing recording, streaming, and storage flows must not regress.

---

## Sprint Board

## Sprint 1 - Foundation + Core Runtime
**Target outcome:** End-to-end event trigger starts/stops recording safely on representative devices.

### 1) Product/Spec Lock
- [ ] Freeze trigger modes: `Any Motion` and `Person Confirmed`.
- [x] Freeze default timings: debounce, cooldown, post-roll, pre-roll.
- [ ] Freeze device-tier defaults (watch/low-end/mid/high).
- [ ] Freeze user-facing copy for Motion Lab rows/helpers.
- [x] Add explicit default values in Motion Lab helper copy.

### 2) Preferences + Interfaces
- [x] Add Motion Lab preference keys in `app/src/main/java/com/fadcam/Constants.java`.
- [x] Add typed getters/setters in `app/src/main/java/com/fadcam/SharedPreferencesManager.java`.
- [x] Add motion package scaffolding:
  - [x] `MotionDetector` interface
  - [x] `MotionSignal` model
  - [x] `MotionStateMachine`
  - [x] `MotionPolicy`
  - [x] `PersonDetector` interface (pluggable)

### 3) Camera Analysis Pipeline
- [x] Add low-res `ImageReader` analysis surface in `RecordingService` capture session.
- [x] Ensure capture session gracefully handles unsupported stream combinations.
- [x] Add safe-mode profile path for watch/API<=29/low-RAM devices.

### 4) Stage-1 Motion Detector (OpenCV)
- [x] Implement frame-difference/background subtraction on Y plane (custom Java detector).
- [x] Add thresholding + minimum area filtering.
- [x] Add optional zones/masks data model (`PREF_MOTION_ZONES_JSON`).
- [x] Add hybrid frame-diff + background-model scoring.
- [x] Add global camera-motion suppression heuristic.
- [x] Add detailed detector metrics (`area/strong/mean/bg/max`) for debug UI.
- [x] Integrate OpenCV MOG2 backend as runtime-selectable backend with fallback.
- [x] Fix `PENDING` trigger-hold behavior to use hysteresis threshold (prevents missed starts).
- [x] Add OpenCV score calibration + asymmetric EMA (faster start, stable stop).
- [x] Tune distant-motion sensitivity (weaker fg threshold, subtle-motion retention, stronger edge boosts).

### 5) State Machine Wiring
- [x] Implement `IDLE -> PENDING -> RECORDING -> POST_ROLL` transitions.
- [x] Wire recording start/stop dispatch via `ServiceStartPolicy` correctly.
- [x] Add no-flap guards (debounce + cooldown + min clip length).

### 6) Sprint 1 Validation
- [ ] Verify no regressions in normal manual recording.
- [ ] Verify service stability during repeated motion events.
- [x] Verify compile: `./gradlew :app:compileDefaultDebugJavaWithJavac -x lint`.
- [ ] Re-test missed-start scenario with live logs after latest trigger-hold fix.

**Owner:** [ ]
**ETA:** [ ]
**Status:** [ ] Not started [x] In progress [ ] Blocked [ ] Done

---

## Sprint 2 - Accuracy Layer + UX
**Target outcome:** Reliable hybrid detection with production-grade control surface.

### 1) Stage-2 Person Confirmation (TFLite)
- [x] Integrate TFLite runtime.
- [x] Add lightweight detector wrapper (`PersonDetectorTflite`).
- [ ] Use open-source INT8 model profile (EfficientDet-Lite0 or SSD MobileNet V2).
- [x] Run ML only during motion windows (throttled cadence).

### 2) Hybrid Trigger Policy
- [x] Implement `Any Motion` mode.
- [x] Implement `Person Confirmed` mode.
- [x] Add confidence threshold and consecutive-hit logic.

### 3) Motion Lab Settings UI
- [x] Add entry row in `AdvancedSettingsFragment`.
- [x] Create `MotionLabSettingsFragment` and layout.
- [x] Add controls:
  - [x] Enable Motion Lab
  - [x] Trigger mode
  - [x] Sensitivity
  - [x] Analysis FPS
  - [x] Debounce
  - [x] Post-roll
  - [x] Pre-roll seconds
  - [x] Low-FPS fallback toggle + target FPS
  - [x] Auto torch on motion toggle (pause=off, resume=on)
- [x] Add explanatory helper text per setting.
- [x] Add live debug card with state/score/threshold/action/person.
- [x] Add live analyzer-frame preview in debug card.
- [x] Add `Copy Debug Snapshot` action.
- [x] Make Analysis FPS configurable with numeric input (1-15).

### 4) Diagnostics + Telemetry (local)
- [x] Add structured logs: motion score, threshold crosses, state transitions.
- [x] Add smoothed score + hysteresis threshold logs.
- [x] Add debug counters: trigger count, suppress count, clip count.
- [x] Add compact diagnostics summary for bug reports.
- [x] Add throttled debug broadcast payload for live settings screen.

### 5) Sprint 2 Validation
- [ ] False positive test (static scene with lighting changes).
- [ ] Missed event test (human walk-through day/night).
- [ ] Thermal/battery sanity check on mid-tier device.

**Owner:** [ ]
**ETA:** [ ]
**Status:** [ ] Not started [ ] In progress [ ] Blocked [ ] Done

---

## Sprint 3 - Hardening + Rollout
**Target outcome:** Stable production release with clear guardrails and docs.

### 1) Compatibility Hardening
- [x] Safe-mode defaults for watch/low-RAM/API<=29.
- [x] Conservative decode/analysis sizes in safe mode.
- [ ] Graceful disable path when device cannot support analysis surface.

### 2) Recording and Storage Integrity
- [ ] Validate segment splitting still works under motion-triggered starts/stops.
- [ ] Validate file finalization across rapid event bursts.
- [ ] Validate records indexing and visibility for all created clips.

### 3) UX and Recovery
- [ ] Add clear user state messaging: Idle / Motion / Recording / Post-roll.
- [ ] Add fallback notices when low-FPS mode is in effect.
- [ ] Add reset-to-recommended defaults action in Motion Lab.

### 4) Test Matrix + Soak
- [ ] Watch/low-end device run.
- [ ] Mid-range phone run.
- [ ] Flagship run.
- [ ] Long-run soak test (8h+).
- [ ] Reboot/restart persistence test.

### 5) Release
- [ ] Keep feature default OFF at release.
- [ ] Add release notes and known limitations.
- [ ] Prepare v1.1 tuning list from diagnostics.

**Owner:** [ ]
**ETA:** [ ]
**Status:** [ ] Not started [x] In progress [ ] Blocked [ ] Done

---

## Dependencies (Open Source Only)
- [x] OpenCV Android SDK (BSD-3-Clause)
- [x] TensorFlow Lite runtime (Apache-2.0)
- [x] Open-source INT8 detection model with vetted license

## Model Download Links
- TF Hub page: https://tfhub.dev/tensorflow/lite-model/efficientdet/lite0/detection/metadata/1
- Direct TFLite download: https://tfhub.dev/tensorflow/lite-model/efficientdet/lite0/detection/metadata/1?lite-format=tflite
- Local expected path: `app/src/main/assets/models/person_detector.tflite`

## Risk Register
- [ ] Camera stream combination incompatibility on specific devices
  - Mitigation: capability probe + graceful fallback
- [ ] False positives from shadows/flicker
  - Mitigation: zones, min area, hysteresis, optional person confirm
- [ ] Thermal drain on weak devices
  - Mitigation: safe mode, lower analysis FPS, throttled ML cadence
- [ ] Foreground/background restrictions across Android versions
  - Mitigation: strict `ServiceStartPolicy` action routing and eligibility checks

## Done Definition
- [ ] No regressions in baseline recording flow
- [ ] Motion-triggered capture stable on all target tiers
- [ ] Crash-free in soak tests
- [ ] All Motion Lab settings persisted and functional
- [ ] Documentation and troubleshooting notes complete
