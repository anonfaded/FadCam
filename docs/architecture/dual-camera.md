# Dual Camera Recording — Architecture Reference

**Status:** Implemented and operational  
**Tech Stack:** Media3 Camera2 API, OpenGL ES 2.0 compositor, Android MVVM  
**Minimum Android:** API 21 (device support for concurrent cameras varies; API 30+ has strict checks)

---

## Quick Reference: File Locations

### Core Classes
- **DualCameraCapability.java** — Runtime device capability checker (concurrent camera support)  
  📍 `app/src/main/java/com/fadcam/dualcam/DualCameraCapability.java`
- **DualCameraConfig.java** — Configuration (PiP position, size, primary camera selection)  
  📍 `app/src/main/java/com/fadcam/dualcam/DualCameraConfig.java`
- **DualCameraState.java** — State enum (DISABLED, INITIALIZING, PREVIEW_ONLY, RECORDING, PAUSED, ERROR)  
  📍 `app/src/main/java/com/fadcam/dualcam/DualCameraState.java`

### Service & Recording
- **DualCameraRecordingService.java** — Foreground service managing two CameraDevice instances  
  📍 `app/src/main/java/com/fadcam/dualcam/service/DualCameraRecordingService.java`

### Pipeline & Compositing
- **DualCameraPipeline.java** — Orchestrates encoding: receives two camera surfaces, outputs single MP4  
  📍 `app/src/main/java/com/fadcam/dualcam/pipeline/DualCameraPipeline.java`
- **DualCameraCompositor.java** — OpenGL ES 2.0 compositor with PiP rendering, rounded corners, border  
  📍 `app/src/main/java/com/fadcam/dualcam/pipeline/DualCameraCompositor.java`

### UI & Helpers
- **DualCameraSettingsFragment.java** — Bottom sheet for PiP configuration (position, size, border)  
  📍 `app/src/main/java/com/fadcam/dualcam/ui/DualCameraSettingsFragment.java`
- **DualCameraInfoBottomSheet.java** — Info/help sheet for dual camera mode  
  📍 `app/src/main/java/com/fadcam/dualcam/ui/DualCameraInfoBottomSheet.java`
- **DualCameraToggleHelper.java** — Utility for enabling/disabling dual camera mode  
  📍 `app/src/main/java/com/fadcam/dualcam/ui/DualCameraToggleHelper.java`

### Configuration & Constants
- **Constants.java** — Intent actions and broadcast actions for dual camera  
  📍 `app/src/main/java/com/fadcam/Constants.java` (lines 436–460)

---

## Architecture Overview

```
User Interaction (HomeFragment / Settings)
  │
  ├─ Check: DualCameraCapability.isSupported()
  │          └─ Uses getConcurrentCameraIds() (API 30+) + fallback to manual detection
  │
  └─ Route Recording:
     ├─ Single Camera → RecordingService (existing, unchanged)
     └─ Dual Camera → DualCameraRecordingService
        │
        ├─ Open both CameraDevice (front + back)
        │
        ├─ Create DualCameraPipeline
        │  │
        │  ├─ DualCameraCompositor (OpenGL thread)
        │  │  ├─ Primary SurfaceTexture (full-screen)
        │  │  ├─ Secondary SurfaceTexture (PiP corner)
        │  │  └─ Render to encoderInputSurface
        │  │
        │  ├─ MediaCodec (video encoder)
        │  ├─ AudioRecord + AudioEncoder (audio)
        │  └─ FragmentedMp4Muxer → MP4 file
        │
        └─ Broadcast: RECORDING_STARTED, PAUSED, RESUMED, STOPPED, ERROR, SWAPPED
```

---

## Core Components

### 1. DualCameraCapability — Device Support Detection

**Checks if device can record from front + back simultaneously.**

```java
DualCameraCapability capability = new DualCameraCapability(context);
boolean isSupported = capability.isSupported();  // Cached after first call

if (!isSupported) {
    String reason = capability.getUnsupportedReason();
    // e.g., "Your device does not support simultaneous front and back cameras"
}

// Optional: get concurrent IDs if API 30+ confirms them
String frontId = capability.getConcurrentFrontCameraId();
String backId = capability.getConcurrentBackCameraId();
boolean apiConfirmed = capability.isConcurrentApiConfirmed();
```

**Implementation details:**
- API 30+ (Android 11): Uses strict `CameraManager.getConcurrentCameraIds()` if available
- API 21–29: Falls back to manual detection (finds first FRONT and first BACK camera)
- Returns `false` if either front or back is missing
- Results cached in volatile fields for thread safety

---

### 2. DualCameraConfig — Recording Configuration

**Immutable configuration for PiP layout and primary camera selection.**

```java
// Values
config.getPipPosition()      // TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT
config.getPipSize()          // SMALL (20%), MEDIUM (30%), LARGE (40%)
config.getPrimaryCamera()    // BACK or FRONT
config.isShowPipBorder()     // true/false
config.isRoundPipCorners()   // true/false
config.getPipMarginDp()      // margins between PiP and edge
```

**Builder pattern:**

```java
DualCameraConfig config = new DualCameraConfig.Builder()
    .pipPosition(DualCameraConfig.PipPosition.BOTTOM_RIGHT)
    .pipSize(DualCameraConfig.PipSize.MEDIUM)
    .primaryCamera(DualCameraConfig.PrimaryCamera.BACK)
    .showPipBorder(true)
    .roundPipCorners(true)
    .build();

// Or use defaults
DualCameraConfig config = DualCameraConfig.defaultConfig();
```

---

### 3. DualCameraState — State Machine

```
DISABLED
  ↓ (user enables dual mode)
INITIALIZING (opening both cameras)
  ↓
PREVIEW_ONLY (cameras open, not recording)
  ├─ ↓ (user taps record)
  │ RECORDING
  │ ├─ ↓ (user pauses)
  │ ├ PAUSED
  │ │ ├─ ↓ (user resumes)
  │ │ └ RECORDING
  │ ├─ ↓ (user stops)
  │ └ DISABLED
  │
  ├─ ↓ (camera error)
  └ ERROR (user action or fallback to single camera)
```

---

### 4. DualCameraRecordingService — Service Layer

**Foreground service managing two `CameraDevice` instances simultaneously.**

**Intent Actions:**
- `Constants.INTENT_ACTION_START_DUAL_RECORDING` — Start dual camera recording
- `Constants.INTENT_ACTION_STOP_DUAL_RECORDING` — Stop and finalize MP4
- `Constants.INTENT_ACTION_PAUSE_DUAL_RECORDING` — Pause encoding
- `Constants.INTENT_ACTION_RESUME_DUAL_RECORDING` — Resume after pause
- `Constants.INTENT_ACTION_SWAP_DUAL_CAMERAS` — Swap primary/PiP assignment
- `Constants.INTENT_ACTION_UPDATE_PIP_CONFIG` — Change PiP position/size on-the-fly

**Broadcasts (LocalBroadcastManager):**
- `BROADCAST_ON_DUAL_RECORDING_STARTED` — Recording began
- `BROADCAST_ON_DUAL_RECORDING_STOPPED` — Recording stopped and file saved
- `BROADCAST_ON_DUAL_RECORDING_PAUSED` — Encoding paused
- `BROADCAST_ON_DUAL_RECORDING_RESUMED` — Encoding resumed
- `BROADCAST_ON_DUAL_CAMERA_ERROR` — Critical error (e.g., both cameras failed)
- `BROADCAST_ON_DUAL_CAMERAS_SWAPPED` — Primary/PiP cameras swapped

**Key fields:**
```java
private CameraDevice primaryCameraDevice;
private CameraDevice secondaryCameraDevice;
private CameraCaptureSession primarySession;
private CameraCaptureSession secondarySession;
private DualCameraPipeline pipeline;
private DualCameraState state;
private DualCameraConfig config;
```

**Lifecycle:**
1. `onCreate()` — Initialize CameraManager, background thread
2. `onStartCommand(intent)` — Route intent to action handler
3. `openPrimaryCameraAndSession()` → `openSecondaryCameraAndSession()` → `createPipeline()`
4. `startRecording()` → Broadcasts `RECORDING_STARTED`
5. `stopRecording()` → Broadcasts `RECORDING_STOPPED`
6. `onDestroy()` → Close sessions, cameras, release pipeline

---

## Integration with Existing Systems

### Single-Camera Recording (Unchanged)

Existing code continues to use `RecordingService` for single camera:
- **Entry point:** `HomeFragment.startRecording()`
- **Service:** `RecordingService` (all existing logic preserved)
- **Pipeline:** `GLRecordingPipeline` (single camera input)
- **Notification:** Reuses same channel for consistent UX

### Dual-Camera Recording (New)

Parallel path routes to `DualCameraRecordingService`:
- **Entry point:** `HomeFragment.startDualCameraRecording()` (calls toggle logic)
- **Service:** `DualCameraRecordingService` (manages two cameras)
- **Pipeline:** `DualCameraPipeline` (two camera inputs → PiP composition)
- **Notification:** Reuses same notification channel

**No modifications to single-camera recording code.**

---

## SharedPreferences Keys for Dual Camera

| Key | Type | Default | Usage |
|-----|------|---------|-------|
| `dual_camera_enabled` | boolean | `false` | Toggle for dual camera mode |
| `dual_camera_pip_position` | String (enum) | `BOTTOM_RIGHT` | PiP corner position |
| `dual_camera_pip_size` | String (enum) | `MEDIUM` | PiP relative size |
| `dual_camera_primary` | String (enum) | `BACK` | Primary camera (BACK or FRONT) |
| `dual_camera_show_border` | boolean | `true` | Draw border around PiP |
| `dual_camera_round_corners` | boolean | `true` | Rounded corners on PiP |

---

## Error Handling

**Device doesn't support concurrent cameras:**
- `DualCameraCapability.isSupported()` returns `false`
- UI shows user-friendly message (e.g., "Your device doesn't support dual camera")
- Falls back to single-camera mode

**Service-level camera errors:**
- Service broadcasts `BROADCAST_ON_DUAL_CAMERA_ERROR`
- Error code and description included in intent extras
- UI shows error dialog with fallback option
- Can retry or switch to single-camera

**Encoding/muxing failures:**
- Pipeline detects error (MediaCodec, buffer issues, disk full)
- Broadcasts error with error code
- MP4 may be partial or corrupt; user can re-record

---

## References

- [Android Camera2 API](https://developer.android.com/reference/android/hardware/camera2)
- [Concurrent Camera IDs (API 30+)](https://developer.android.com/reference/android/hardware/camera2/CameraManager#getConcurrentCameraIds())
- [MediaCodec and Surface](https://developer.android.com/reference/android/media/MediaCodec)
- [OpenGL ES 2.0 Programming Guide](https://www.khronos.org/opengl/wiki/OpenGL_ES)
