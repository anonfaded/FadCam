# FadCam Dual Camera Feature - Implementation Plan

---

## 🔬 Codebase Research — Current Camera Architecture

> Research completed to understand existing camera recording architecture before implementing dual camera.

### Research Checklist

- [x] **RecordingService.java** — Camera opening, recording start/stop, camera switching
- [x] **GLRecordingPipeline.java** — Constructor parameters, surface chain, encoding
- [x] **VideoSettingsFragment.java** — Camera mode selection UI, resolution/FPS picker
- [x] **SharedPreferencesManager.java** — Camera-related getters/setters/keys
- [x] **Constants.java** — Camera-related constants, intent actions, broadcast actions
- [x] **RecordingState.java** — All recording states
- [x] **HomeFragment.java** — Preview management, camera switch button, broadcast receivers
- [x] **CameraType.java** — Enum definition
- [x] **fragment_home.xml** — Preview area layout structure
- [x] **DeviceHelper.java** — Device capability detection (no dual camera code)
- [x] **Searched for `getConcurrentCameraIds` / `FEATURE_CAMERA_CONCURRENT`** — Not found anywhere in codebase

---

### A. CameraType Enum

**File**: `app/src/main/java/com/fadcam/CameraType.java`

```java
public enum CameraType implements Serializable {
    FRONT(1),  // maps to camera ID 1
    BACK(0);   // maps to camera ID 0
    // toString() returns enum name() → "FRONT" or "BACK"
}
```

---

### B. RecordingState Enum

**File**: `app/src/main/java/com/fadcam/RecordingState.java`

```
STARTING → IN_PROGRESS → PAUSED → NONE
                       ↘ WAITING_FOR_CAMERA (camera interrupted, rendering black frames)
```

---

### C. SharedPreferences Keys (Camera-Related)

| Key | Constant | Type | Default |
|-----|----------|------|---------|
| `"camera_selection"` | `PREF_CAMERA_SELECTION` | String (enum name) | `"BACK"` |
| `"selected_back_camera_id"` | `PREF_SELECTED_BACK_CAMERA_ID` | String | `"0"` |
| `"video_resolution_width"` | `PREF_VIDEO_RESOLUTION_WIDTH` | int | 1920 |
| `"video_resolution_height"` | `PREF_VIDEO_RESOLUTION_HEIGHT` | int | 1080 |
| `"video_frame_rate_front"` | `PREF_VIDEO_FRAME_RATE_FRONT` | int | 30 |
| `"video_frame_rate_back"` | `PREF_VIDEO_FRAME_RATE_BACK` | int | 30 |
| `"zoom_ratio_front"` | `PREF_ZOOM_RATIO_FRONT` | float | 1.0f |
| `"zoom_ratio_back"` | `PREF_ZOOM_RATIO_BACK` | float | 1.0f (0.5f for wide-angle) |
| `"video_codec"` | `PREF_VIDEO_CODEC` | String | `"HEVC"` |
| `"video_bitrate"` | `PREF_VIDEO_BITRATE` | int | 8,000,000 |
| `"isPreviewEnabled"` | `PREF_IS_PREVIEW_ENABLED` | boolean | true |

**SharedPreferencesManager camera methods**:
- `getCameraSelection()` → `CameraType`
- `getCameraResolution()` → `Size`
- `getSpecificVideoFrameRate(CameraType)` → per-camera FPS
- `getSpecificZoomRatio(CameraType)` → per-camera zoom
- `getSelectedBackCameraId()` → physical camera ID string for back lens
- `getVideoCodec()` → `VideoCodec` enum

---

### D. RecordingService — Camera Opening

**File**: `app/src/main/java/com/fadcam/services/RecordingService.java` (4387 lines)

**Key fields**:
```java
private CameraDevice cameraDevice;          // Single camera device
private CameraCaptureSession captureSession;
private CaptureRequest.Builder captureRequestBuilder;
private CameraCharacteristics currentCameraCharacteristics;
private Surface previewSurface;             // From UI TextureView
private CameraManager cameraManager;
private Handler backgroundHandler;
private GLRecordingPipeline glRecordingPipeline;
private volatile boolean isSwitchingCamera = false;
```

**`openCamera()` (line 1478)** flow:
1. Reads `CameraType` from `sharedPreferencesManager.getCameraSelection()`
2. Lists all camera IDs via `cameraManager.getCameraIdList()`
3. On Android P+: also enumerates **physical camera IDs** via `getPhysicalCameraIds()`
4. **FRONT**: finds first camera with `LENS_FACING_FRONT`
5. **BACK**: reads `sharedPreferencesManager.getSelectedBackCameraId()` for preferred lens, validates, falls back to `DEFAULT_BACK_CAMERA_ID` ("0")
6. Calls `cameraManager.openCamera(id, cameraStateCallback, backgroundHandler)` with 3 retries (2s delay)

**`cameraStateCallback` (line 1749)**:
- `onOpened()` → sets `cameraDevice`, checks `pendingStartRecording` → `attemptStartRecordingIfReady()`, handles camera switch (PAUSED + isSwitchingCamera)
- `onDisconnected()` → closes camera, switches to black frame rendering if was recording
- `onError()` → handles ERROR_CAMERA_IN_USE, ERROR_MAX_CAMERAS_IN_USE, etc.

---

### E. RecordingService — Recording Start

**`startRecording()` (line 3801)** flow:
1. Validates state = `STARTING`
2. Creates `WatermarkInfoProvider` (closure over settings)
3. Reads resolution, orientation, codec, bitrate, FPS from preferences
4. Gets `sensorOrientation` from `CameraCharacteristics`
5. Creates output file (internal path or SAF FileDescriptor)
6. **Constructs `GLRecordingPipeline`** with all parameters
7. Calls `glRecordingPipeline.prepareSurfaces()` — encoder + GL renderer + camera input surface
8. Calls `createCameraPreviewSession()` — creates Camera2 session using GL surface only

---

### F. GLRecordingPipeline Construction

**File**: `app/src/main/java/com/fadcam/opengl/GLRecordingPipeline.java` (2585 lines)

**Constructor (internal storage, line 236)**:
```java
GLRecordingPipeline(
    Context context,
    WatermarkInfoProvider watermarkInfoProvider,
    int videoWidth, int videoHeight,
    int videoFramerate,
    String outputFilePath,           // or FileDescriptor for SAF
    long maxFileSizeBytes,           // 0 = no splitting
    int segmentNumber,               // always starts at 1
    SegmentCallback segmentCallback,
    Surface previewSurface,          // nullable
    String orientation,              // "portrait" / "landscape"
    int sensorOrientation,           // 0/90/180/270
    VideoCodec videoCodec,           // AVC or HEVC
    Float latitude, Float longitude  // nullable location
)
```

**Surface chain**:
```
Camera2 → cameraInputSurface (SurfaceTexture via GLWatermarkRenderer)
    → GL renders watermark overlay
    → encoderInputSurface (MediaCodec)
    → FragmentedMp4MuxerWrapper → MP4 file
    → also renders to previewSurface (if set) for UI preview
```

**Key methods**:
- `prepareSurfaces()` — creates encoder, GL renderer, EGL context, camera input surface
- `startRecording()` → begins encoding + render loop
- `stopRecording()` → stops encoding, releases resources
- `pauseRecording()` / `resumeRecording()` — with timestamp tracking for seamless pause
- `prepareCameraSwitch()` — sets timestamp adjustment flags before live switch
- `getCameraInputSurface()` → returns `cameraInputSurface` for Camera2 session
- `setPreviewSurface(Surface)` → debounced, applied on GL thread

---

### G. Camera Preview Session

**`createCameraPreviewSession()` (line 2029)** in RecordingService:
1. Gets GL pipeline's camera input surface via `glRecordingPipeline.getCameraInputSurface()`
2. **Only the GL surface** is added to Camera2 session outputs (preview is rendered by GL pipeline, NOT added to Camera2 session)
3. Determines standard vs high-speed session (≥60fps)
4. Samsung: forced to standard session (no HSR)
5. Creates session → in `onConfigured()` starts repeating request + calls `glRecordingPipeline.startRecording()`

---

### H. Live Camera Switch During Recording

**`switchCameraLive(CameraType)` (line 1192)** — 6-phase approach:

| Phase | Action |
|-------|--------|
| **0** | `glRecordingPipeline.prepareCameraSwitch()` — timestamp flags |
| **1** | `pauseRecording()` — pauses pipeline |
| **2** | `drainEncoderBeforeCameraSwitch(200ms)` — drains encoder |
| **3** | `closeCameraResourcesForSwitch()` — closes session + camera device |
| **4** | Updates `PREF_CAMERA_SELECTION` to new type in SharedPreferences |
| **5** | `openCamera()` — opens new camera (reads updated pref) |
| **6** | `resumeRecording()` — resumes pipeline |

Pipeline continues encoding; timestamps adjusted for pause duration. On failure → recovery by reopening original camera. On catastrophic failure → stops recording.

**Broadcasts**: `BROADCAST_ON_CAMERA_SWITCH_STARTED`, `_COMPLETE`, `_FAILED`

---

### I. HomeFragment — Preview & Camera Switch

**File**: `app/src/main/java/com/fadcam/ui/HomeFragment.java` (9073 lines)

**Preview**:
- `TextureView textureView` (line 170) — live camera preview
- `Surface textureViewSurface` (line 275) — created from `textureView.getSurfaceTexture()`
- Surface passed to RecordingService via Intent extra `"SURFACE"` on start
- Updated via `INTENT_ACTION_CHANGE_SURFACE` during recording

**Camera switch button**: `buttonCamSwitch` (line 204, mapped to `R.id.buttonCamSwitch`)

**`switchCamera()` (line 6910)**:
- **Not recording**: updates `PREF_CAMERA_SELECTION` preference directly
- **Recording**: sends `INTENT_ACTION_SWITCH_CAMERA` intent with `INTENT_EXTRA_CAMERA_TYPE_SWITCH` to RecordingService

**Broadcast receivers** (line 2355): `broadcastOnCameraSwitchStarted`, `_Complete`, `_Failed` — registered via `LocalBroadcastManager`

---

### J. Layout — Preview Area

**File**: `app/src/main/res/layout/fragment_home.xml`

```
cardPreview (CardView, fills space between cards and controls)
└── FrameLayout
    ├── ivBubbleBackground (ImageView, decorative)
    ├── ivCameraIconPreview (ImageView, CCTV placeholder)
    ├── textureView (TextureView, live camera preview)  ← MAIN PREVIEW
    ├── tvPreviewPlaceholder (TextView, hidden)
    └── tvPreviewHint (TextView, "Long press to enable preview")

layoutControls (LinearLayout, horizontal, bottom)
├── buttonTorchSwitch (MaterialButton, 48dp)
├── buttonStartStop (MaterialButton, start/stop)
├── buttonPauseResume (MaterialButton, 48dp)
└── buttonCamSwitch (MaterialButton, 48dp)  ← CAMERA SWITCH
```

---

### K. VideoSettingsFragment — Camera Selection UI

**File**: `app/src/main/java/com/fadcam/ui/VideoSettingsFragment.java` (1768 lines)

**Camera type picker** (`showCameraBottomSheet()`, line 294):
- Uses `PickerBottomSheetFragment` bottom sheet
- Options: `CameraType.FRONT.toString()` ("FRONT"), `CameraType.BACK.toString()` ("BACK")
- Saves via `prefs.sharedPreferences.edit().putString(Constants.PREF_CAMERA_SELECTION, sel)`

**Lens picker** (`showLensBottomSheet()`, line 321):
- Only visible when `CameraType.BACK` and multiple back cameras detected
- Lists physical camera IDs with display names (Main, Wide, Telephoto)
- Saves via `prefs.setSelectedBackCameraId(sel)`

**Per-camera settings**: FPS, resolution, zoom are stored per camera with caches

---

### L. Existing Dual Camera / Concurrent Camera Code

- **`getConcurrentCameraIds`**: ❌ Not found anywhere in codebase
- **`FEATURE_CAMERA_CONCURRENT`**: ❌ Not found anywhere in codebase
- **DeviceHelper.java**: Only has `isSamsung()`, `isGoogle()`, `isHighEndDevice()`, `isInternetAvailable()` — **no dual camera capability detection**
- **All dual camera references** exist only in this planning document

---

### M. Key Architecture Facts for Dual Camera Implementation

| Aspect | Current State | Dual Camera Impact |
|--------|---------------|-------------------|
| Camera device | Single `cameraDevice` field | Need two `CameraDevice` instances |
| Camera session | Single `captureSession` | Need two `CameraCaptureSession` instances |
| GL pipeline | Single `GLRecordingPipeline` with one camera input surface | Need compositor that accepts two camera inputs |
| Preview | Single `TextureView` in `cardPreview` | Need split/PiP preview layout |
| Recording output | Single encoder → single MP4 | Keep single output (PiP composited by GL) |
| Camera switch | Sequential close→open approach | May need to swap primary/secondary assignment |
| Preferences | `PREF_CAMERA_SELECTION` = FRONT or BACK | Need new dual mode prefs |
| Constants | No dual camera constants | Need new intent/broadcast actions |

---

## 📋 Overview

This document outlines a **modular, non-breaking** approach to adding dual camera (Picture-in-Picture) recording to FadCam. The design prioritizes:

1. **Zero impact on existing single-camera code** - All new code in separate files
2. **Smooth user experience** - Seamless toggle, intuitive controls
3. **Clean architecture** - MVVM patterns, clear separation of concerns
4. **Device compatibility** - Graceful fallback for unsupported devices

---

## 🏗️ Architecture Overview

```
┌─────────────────────────────────────────────────────────────────────┐
│                         UI LAYER                                     │
│  ┌─────────────────┐  ┌─────────────────┐  ┌─────────────────────┐ │
│  │  HomeFragment   │  │ DualCameraView  │  │ DualCameraSettings │ │
│  │ (toggle button) │  │   (PiP layout)  │  │    BottomSheet     │ │
│  └────────┬────────┘  └────────┬────────┘  └─────────────────────┘ │
│           │                    │                                     │
└───────────┼────────────────────┼─────────────────────────────────────┘
            │                    │
┌───────────┼────────────────────┼─────────────────────────────────────┐
│           ▼                    ▼         VIEWMODEL LAYER             │
│  ┌─────────────────────────────────────────────────────────────────┐ │
│  │              DualCameraViewModel                                │ │
│  │  - Mode (single/dual)                                           │ │
│  │  - PiP position (corner selection)                              │ │
│  │  - Primary camera (front/back)                                  │ │
│  │  - Recording state coordination                                 │ │
│  └─────────────────────────────────────────────────────────────────┘ │
└──────────────────────────────────┬───────────────────────────────────┘
                                   │
┌──────────────────────────────────┼───────────────────────────────────┐
│                                  ▼         SERVICE LAYER             │
│  ┌─────────────────────────────────────────────────────────────────┐ │
│  │  RecordingService (EXISTING - unchanged)                        │ │
│  │  - Single camera recording                                      │ │
│  │  - All existing functionality preserved                         │ │
│  └─────────────────────────────────────────────────────────────────┘ │
│                                                                       │
│  ┌─────────────────────────────────────────────────────────────────┐ │
│  │  DualCameraRecordingService (NEW)                               │ │
│  │  - Two CameraDevice instances                                   │ │
│  │  - Two CameraCaptureSession instances                           │ │
│  │  - Delegates to DualCameraPipeline                              │ │
│  └─────────────────────────────────────────────────────────────────┘ │
└──────────────────────────────────┬───────────────────────────────────┘
                                   │
┌──────────────────────────────────┼───────────────────────────────────┐
│                                  ▼         PIPELINE LAYER            │
│  ┌─────────────────────────────────────────────────────────────────┐ │
│  │  GLRecordingPipeline (EXISTING - unchanged)                     │ │
│  └─────────────────────────────────────────────────────────────────┘ │
│                                                                       │
│  ┌─────────────────────────────────────────────────────────────────┐ │
│  │  DualCameraPipeline (NEW)                                       │ │
│  │  - Two SurfaceTexture inputs (camera1, camera2)                 │ │
│  │  - DualCameraCompositor (OpenGL shader-based PiP)               │ │
│  │  - Single MediaCodec encoder output                             │ │
│  │  - Reuses FragmentedMp4MuxerWrapper                             │ │
│  └─────────────────────────────────────────────────────────────────┘ │
│                                                                       │
│  ┌─────────────────────────────────────────────────────────────────┐ │
│  │  DualCameraCompositor (NEW - OpenGL)                            │ │
│  │  - Composites two camera textures into single frame             │ │
│  │  - PiP position/size configurable                               │ │
│  │  - Rounded corners for secondary camera                         │ │
│  │  - Optional border/shadow effects                               │ │
│  └─────────────────────────────────────────────────────────────────┘ │
└──────────────────────────────────────────────────────────────────────┘
```

---

## 📁 New Files Structure

```
app/src/main/java/com/fadcam/
├── dualcam/                          # NEW PACKAGE - All dual camera code
│   ├── DualCameraCapability.java     # Device compatibility checker
│   ├── DualCameraConfig.java         # Configuration data class
│   ├── DualCameraState.java          # State enum for dual camera
│   │
│   ├── service/
│   │   └── DualCameraRecordingService.java    # Separate service for dual camera
│   │
│   ├── pipeline/
│   │   ├── DualCameraPipeline.java            # Dual camera encoding pipeline
│   │   └── DualCameraCompositor.java          # OpenGL PiP compositor
│   │
│   ├── ui/
│   │   ├── DualCameraPreviewView.java         # Custom view for dual preview
│   │   ├── DualCameraToggleHelper.java        # Helper for mode switching
│   │   └── DualCameraSettingsBottomSheet.java # PiP position, size settings
│   │
│   └── viewmodel/
│       └── DualCameraViewModel.java            # MVVM ViewModel
│
├── res/
│   ├── layout/
│   │   └── fragment_dual_camera_settings.xml
│   └── values/
│       └── dualcam_strings.xml
```

---

## 📋 Detailed Implementation Plan

### Implementation Progress

- [x] 🔬 Phase 0: Codebase Research — Understand existing camera architecture
- [x] 🧱 Phase 1: Foundation — DualCameraCapability, Config, State classes + Constants + SharedPrefs methods
- [x] ⚙️ Phase 2: Service Layer — DualCameraRecordingService
- [x] 🎨 Phase 3: OpenGL Compositor — DualCameraCompositor with PiP rendering
- [x] 📱 Phase 4: UI Integration — Toggle, settings, preview, ViewModel
- [x] 🔗 Phase 5: Recording Flow Integration — Route to correct service
- [ ] 🧪 Phase 6: Testing & Polish — Edge cases, optimization

---

### Phase 1: Foundation (Week 1)
**Goal**: Device capability detection and basic infrastructure

#### 1.1 DualCameraCapability.java
```java
package com.fadcam.dualcam;

import android.content.Context;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.os.Build;

/**
 * Checks if device supports concurrent front+back camera operation.
 * Does NOT modify any existing code.
 */
public class DualCameraCapability {
    
    private final Context context;
    private Boolean cachedSupport = null;
    
    public DualCameraCapability(Context context) {
        this.context = context.getApplicationContext();
    }
    
    /**
     * Checks if device supports concurrent dual camera recording.
     * Requirements:
     * - Android 11+ (API 30) for ConcurrentCameraIds API
     * - Both front and back cameras available
     * - Hardware support for concurrent streams
     */
    public boolean isSupported() {
        if (cachedSupport != null) return cachedSupport;
        
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            cachedSupport = false;
            return false;
        }
        
        try {
            CameraManager cameraManager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
            if (cameraManager == null) {
                cachedSupport = false;
                return false;
            }
            
            // Check for concurrent camera support (Android 11+)
            Set<Set<String>> concurrentCameraSets = cameraManager.getConcurrentCameraIds();
            if (concurrentCameraSets.isEmpty()) {
                cachedSupport = false;
                return false;
            }
            
            // Find a set that contains both front and back cameras
            String frontId = findCameraId(cameraManager, CameraCharacteristics.LENS_FACING_FRONT);
            String backId = findCameraId(cameraManager, CameraCharacteristics.LENS_FACING_BACK);
            
            if (frontId == null || backId == null) {
                cachedSupport = false;
                return false;
            }
            
            for (Set<String> cameraSet : concurrentCameraSets) {
                if (cameraSet.contains(frontId) && cameraSet.contains(backId)) {
                    cachedSupport = true;
                    return true;
                }
            }
            
            cachedSupport = false;
            return false;
        } catch (CameraAccessException e) {
            cachedSupport = false;
            return false;
        }
    }
    
    private String findCameraId(CameraManager manager, int lensFacing) throws CameraAccessException {
        for (String id : manager.getCameraIdList()) {
            CameraCharacteristics chars = manager.getCameraCharacteristics(id);
            Integer facing = chars.get(CameraCharacteristics.LENS_FACING);
            if (facing != null && facing == lensFacing) {
                return id;
            }
        }
        return null;
    }
    
    /**
     * Returns user-friendly reason if dual camera not supported.
     */
    public String getUnsupportedReason() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return "Dual camera requires Android 11 or newer";
        }
        // Add more specific reasons as needed
        return "Your device does not support simultaneous front and back cameras";
    }
}
```

#### 1.2 DualCameraConfig.java
```java
package com.fadcam.dualcam;

import java.io.Serializable;

/**
 * Configuration for dual camera recording.
 * Immutable data class.
 */
public class DualCameraConfig implements Serializable {
    
    public enum PipPosition {
        TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT
    }
    
    public enum PipSize {
        SMALL(0.20f),   // 20% of screen width
        MEDIUM(0.30f),  // 30% of screen width
        LARGE(0.40f);   // 40% of screen width
        
        public final float ratio;
        PipSize(float ratio) { this.ratio = ratio; }
    }
    
    public enum PrimaryCamera {
        BACK,   // Back camera is main, front is PiP
        FRONT   // Front camera is main, back is PiP
    }
    
    private final PipPosition pipPosition;
    private final PipSize pipSize;
    private final PrimaryCamera primaryCamera;
    private final boolean showPipBorder;
    private final boolean roundPipCorners;
    
    public DualCameraConfig(
            PipPosition pipPosition,
            PipSize pipSize,
            PrimaryCamera primaryCamera,
            boolean showPipBorder,
            boolean roundPipCorners) {
        this.pipPosition = pipPosition;
        this.pipSize = pipSize;
        this.primaryCamera = primaryCamera;
        this.showPipBorder = showPipBorder;
        this.roundPipCorners = roundPipCorners;
    }
    
    // Default configuration
    public static DualCameraConfig defaultConfig() {
        return new DualCameraConfig(
            PipPosition.BOTTOM_RIGHT,
            PipSize.MEDIUM,
            PrimaryCamera.BACK,
            true,
            true
        );
    }
    
    // Getters
    public PipPosition getPipPosition() { return pipPosition; }
    public PipSize getPipSize() { return pipSize; }
    public PrimaryCamera getPrimaryCamera() { return primaryCamera; }
    public boolean isShowPipBorder() { return showPipBorder; }
    public boolean isRoundPipCorners() { return roundPipCorners; }
    
    // Builder pattern for easy modification
    public static class Builder {
        private PipPosition pipPosition = PipPosition.BOTTOM_RIGHT;
        private PipSize pipSize = PipSize.MEDIUM;
        private PrimaryCamera primaryCamera = PrimaryCamera.BACK;
        private boolean showPipBorder = true;
        private boolean roundPipCorners = true;
        
        public Builder pipPosition(PipPosition pos) { this.pipPosition = pos; return this; }
        public Builder pipSize(PipSize size) { this.pipSize = size; return this; }
        public Builder primaryCamera(PrimaryCamera cam) { this.primaryCamera = cam; return this; }
        public Builder showPipBorder(boolean show) { this.showPipBorder = show; return this; }
        public Builder roundPipCorners(boolean round) { this.roundPipCorners = round; return this; }
        
        public DualCameraConfig build() {
            return new DualCameraConfig(pipPosition, pipSize, primaryCamera, showPipBorder, roundPipCorners);
        }
    }
}
```

#### 1.3 DualCameraState.java
```java
package com.fadcam.dualcam;

import java.io.Serializable;

/**
 * Recording state for dual camera mode.
 * Separate from RecordingState to avoid coupling.
 */
public enum DualCameraState implements Serializable {
    DISABLED,           // Dual camera mode is off, using single camera
    INITIALIZING,       // Opening both cameras
    PREVIEW_ONLY,       // Both cameras open, showing preview, not recording
    RECORDING,          // Both cameras recording to single output
    PAUSED,             // Recording paused
    ERROR               // Error state, requires user action
}
```

---

### Phase 2: Service Layer (Week 2)
**Goal**: Separate DualCameraRecordingService that manages two cameras

#### 2.1 Key Design Decisions

| Aspect | Decision | Rationale |
|--------|----------|-----------|
| Service | New `DualCameraRecordingService` | Don't pollute existing RecordingService |
| State | Separate `DualCameraState` enum | Independent state machine |
| Preferences | New keys in SharedPreferencesManager | Additive, no breaking changes |
| Notifications | Reuse existing channel | Consistent UX |

#### 2.2 DualCameraRecordingService.java (Outline)

```java
package com.fadcam.dualcam.service;

/**
 * Service for dual camera recording.
 * Manages two CameraDevice instances simultaneously.
 * 
 * Intent Actions:
 * - ACTION_START_DUAL_RECORDING
 * - ACTION_STOP_DUAL_RECORDING
 * - ACTION_SWAP_CAMERAS (swap primary/pip)
 * - ACTION_CHANGE_PIP_POSITION
 */
public class DualCameraRecordingService extends Service {
    
    // Two camera instances
    private CameraDevice primaryCameraDevice;
    private CameraDevice secondaryCameraDevice;
    
    // Two capture sessions
    private CameraCaptureSession primarySession;
    private CameraCaptureSession secondarySession;
    
    // Single pipeline that composites both
    private DualCameraPipeline dualPipeline;
    
    // State
    private DualCameraState state = DualCameraState.DISABLED;
    
    // Configuration
    private DualCameraConfig config;
    
    @Override
    public void onCreate() {
        // Initialize CameraManager, background thread, etc.
        // Similar to RecordingService but for dual camera
    }
    
    private void openBothCameras() {
        // Open primary camera first
        // On success, open secondary camera
        // On both success, create DualCameraPipeline
    }
    
    private void startDualRecording() {
        // Create DualCameraPipeline with both camera surfaces
        // Start encoding
    }
    
    public void swapCameras() {
        // Hot-swap primary and secondary without stopping recording
        // Update config.primaryCamera
        // Update DualCameraPipeline swap flag
    }
}
```

---

### Phase 3: OpenGL Compositor (Week 3)
**Goal**: DualCameraCompositor that renders PiP layout

#### 3.1 DualCameraCompositor.java (Outline)

```java
package com.fadcam.dualcam.pipeline;

/**
 * OpenGL compositor that combines two camera textures into one frame.
 * 
 * Features:
 * - Primary camera fills entire frame
 * - Secondary camera in configurable corner (PiP)
 * - Rounded corners on PiP (optional)
 * - Border/shadow on PiP (optional)
 * - Watermark overlay (reuses GLWatermarkRenderer concepts)
 */
public class DualCameraCompositor {
    
    // Two OES textures for camera inputs
    private int primaryOesTextureId;
    private int secondaryOesTextureId;
    
    // Two SurfaceTextures
    private SurfaceTexture primarySurfaceTexture;
    private SurfaceTexture secondarySurfaceTexture;
    
    // Shader program for composition
    private int compositorProgram;
    
    // Configuration
    private DualCameraConfig config;
    
    // PiP geometry (calculated from config)
    private float pipLeft, pipTop, pipWidth, pipHeight;
    
    /**
     * Renders both cameras to encoder surface.
     * Called from render loop.
     */
    public void render() {
        // 1. Draw primary camera full-screen
        drawFullScreen(primaryOesTextureId, primarySurfaceTexture.getTransformMatrix());
        
        // 2. Draw secondary camera in PiP position
        drawPip(secondaryOesTextureId, secondarySurfaceTexture.getTransformMatrix());
        
        // 3. Draw PiP border if enabled
        if (config.isShowPipBorder()) {
            drawPipBorder();
        }
    }
    
    private void drawPip(int textureId, float[] transformMatrix) {
        // Apply rounded corners via shader if enabled
        // Position based on config.pipPosition and config.pipSize
    }
}
```

#### 3.2 Shader for Rounded Corners (Fragment Shader Excerpt)

```glsl
// Fragment shader for PiP with rounded corners
precision mediump float;

varying vec2 vTexCoord;
uniform samplerExternalOES uTexture;
uniform vec2 uPipCenter;      // Center of PiP in normalized coords
uniform vec2 uPipSize;        // Size of PiP
uniform float uCornerRadius;  // Radius for rounded corners

void main() {
    // Calculate distance from PiP corners
    vec2 pipCoord = (gl_FragCoord.xy - uPipCenter) / uPipSize;
    
    // Rounded corner check
    float cornerDist = length(max(abs(pipCoord) - (vec2(0.5) - uCornerRadius), 0.0));
    if (cornerDist > uCornerRadius) {
        discard; // Outside rounded corner
    }
    
    gl_FragColor = texture2D(uTexture, vTexCoord);
}
```

---

### Phase 4: UI Integration (Week 4)
**Goal**: Toggle button, settings, preview

#### 4.1 HomeFragment Integration (Minimal Changes)

Add to existing HomeFragment.java:

```java
// In HomeFragment class - ADD these lines, don't modify existing code

// Check if dual camera is available (lazy init)
private DualCameraCapability dualCameraCapability;

private boolean isDualCameraSupported() {
    if (dualCameraCapability == null) {
        dualCameraCapability = new DualCameraCapability(requireContext());
    }
    return dualCameraCapability.isSupported();
}

// Called from layout or options menu to toggle dual camera mode
private void toggleDualCameraMode() {
    if (!isDualCameraSupported()) {
        Toast.makeText(getContext(), 
            dualCameraCapability.getUnsupportedReason(), 
            Toast.LENGTH_LONG).show();
        return;
    }
    
    boolean currentlyDual = sharedPreferencesManager.isDualCameraModeEnabled();
    sharedPreferencesManager.setDualCameraModeEnabled(!currentlyDual);
    
    // Update UI
    updateCameraToggleButtonVisibility();
    
    if (!currentlyDual) {
        // Switching TO dual mode - show settings
        new DualCameraSettingsBottomSheet().show(getChildFragmentManager(), "dual_settings");
    }
}
```

#### 4.2 SharedPreferencesManager Additions (Additive Only)

```java
// Add to SharedPreferencesManager.java - NEW KEYS ONLY

// --- DUAL CAMERA CONSTANTS ---
private static final String PREF_DUAL_CAMERA_ENABLED = "dual_camera_enabled";
private static final String PREF_DUAL_CAMERA_PIP_POSITION = "dual_camera_pip_position";
private static final String PREF_DUAL_CAMERA_PIP_SIZE = "dual_camera_pip_size";
private static final String PREF_DUAL_CAMERA_PRIMARY = "dual_camera_primary";
private static final String PREF_DUAL_CAMERA_SHOW_BORDER = "dual_camera_show_border";
private static final String PREF_DUAL_CAMERA_ROUND_CORNERS = "dual_camera_round_corners";
// --- END DUAL CAMERA CONSTANTS ---

// --- DUAL CAMERA METHODS ---
public boolean isDualCameraModeEnabled() {
    return sharedPreferences.getBoolean(PREF_DUAL_CAMERA_ENABLED, false);
}

public void setDualCameraModeEnabled(boolean enabled) {
    sharedPreferences.edit().putBoolean(PREF_DUAL_CAMERA_ENABLED, enabled).apply();
}

public DualCameraConfig getDualCameraConfig() {
    return new DualCameraConfig.Builder()
        .pipPosition(DualCameraConfig.PipPosition.valueOf(
            sharedPreferences.getString(PREF_DUAL_CAMERA_PIP_POSITION, "BOTTOM_RIGHT")))
        .pipSize(DualCameraConfig.PipSize.valueOf(
            sharedPreferences.getString(PREF_DUAL_CAMERA_PIP_SIZE, "MEDIUM")))
        .primaryCamera(DualCameraConfig.PrimaryCamera.valueOf(
            sharedPreferences.getString(PREF_DUAL_CAMERA_PRIMARY, "BACK")))
        .showPipBorder(sharedPreferences.getBoolean(PREF_DUAL_CAMERA_SHOW_BORDER, true))
        .roundPipCorners(sharedPreferences.getBoolean(PREF_DUAL_CAMERA_ROUND_CORNERS, true))
        .build();
}

public void saveDualCameraConfig(DualCameraConfig config) {
    sharedPreferences.edit()
        .putString(PREF_DUAL_CAMERA_PIP_POSITION, config.getPipPosition().name())
        .putString(PREF_DUAL_CAMERA_PIP_SIZE, config.getPipSize().name())
        .putString(PREF_DUAL_CAMERA_PRIMARY, config.getPrimaryCamera().name())
        .putBoolean(PREF_DUAL_CAMERA_SHOW_BORDER, config.isShowPipBorder())
        .putBoolean(PREF_DUAL_CAMERA_ROUND_CORNERS, config.isRoundPipCorners())
        .apply();
}
// --- END DUAL CAMERA METHODS ---
```

---

### Phase 5: Recording Flow Integration (Week 5)
**Goal**: Seamless start/stop that routes to correct service

#### 5.1 RecordingControlIntents Update (Additive)

```java
// Add to Constants.java - NEW CONSTANTS ONLY

// --- DUAL CAMERA INTENT ACTIONS ---
public static final String INTENT_ACTION_START_DUAL_RECORDING = "com.fadcam.INTENT_ACTION_START_DUAL_RECORDING";
public static final String INTENT_ACTION_STOP_DUAL_RECORDING = "com.fadcam.INTENT_ACTION_STOP_DUAL_RECORDING";
public static final String INTENT_ACTION_SWAP_DUAL_CAMERAS = "com.fadcam.INTENT_ACTION_SWAP_DUAL_CAMERAS";
public static final String INTENT_ACTION_UPDATE_PIP_CONFIG = "com.fadcam.INTENT_ACTION_UPDATE_PIP_CONFIG";

public static final String BROADCAST_ON_DUAL_RECORDING_STARTED = "com.fadcam.BROADCAST_ON_DUAL_RECORDING_STARTED";
public static final String BROADCAST_ON_DUAL_RECORDING_STOPPED = "com.fadcam.BROADCAST_ON_DUAL_RECORDING_STOPPED";
// --- END DUAL CAMERA INTENT ACTIONS ---
```

#### 5.2 Recording Start Logic (HomeFragment Helper)

```java
// New helper method in HomeFragment - does NOT modify startRecording()

/**
 * Determines which service to use and starts recording.
 * Existing startRecording() code remains untouched.
 */
private void startRecordingWithModeCheck() {
    if (sharedPreferencesManager.isDualCameraModeEnabled() && isDualCameraSupported()) {
        startDualCameraRecording();
    } else {
        startRecording(); // Existing method, unchanged
    }
}

private void startDualCameraRecording() {
    Intent intent = new Intent(requireContext(), DualCameraRecordingService.class);
    intent.setAction(Constants.INTENT_ACTION_START_DUAL_RECORDING);
    intent.putExtra("CONFIG", sharedPreferencesManager.getDualCameraConfig());
    // Add surface, torch state, etc. similar to existing
    
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        requireContext().startForegroundService(intent);
    } else {
        requireContext().startService(intent);
    }
}
```

---

## 🔄 User Experience Flow

### Enabling Dual Camera Mode

```
┌─────────────────────────────────────────────────────────────────────┐
│ User taps "Dual Camera" toggle in settings/home screen             │
└───────────────────────────────┬─────────────────────────────────────┘
                                │
                                ▼
                    ┌───────────────────────┐
                    │ Check device support  │
                    │ DualCameraCapability  │
                    └───────────┬───────────┘
                                │
            ┌───────────────────┼───────────────────┐
            │ Supported         │                   │ Not Supported
            ▼                   │                   ▼
┌───────────────────┐           │       ┌───────────────────────────┐
│ Show settings     │           │       │ Show friendly message     │
│ bottom sheet:     │           │       │ "Your device doesn't      │
│ - PiP position    │           │       │  support dual camera"     │
│ - PiP size        │           │       └───────────────────────────┘
│ - Primary camera  │           │
└───────────────────┘           │
                                │
                                ▼
                    ┌───────────────────────┐
                    │ Update preview to     │
                    │ show dual camera view │
                    └───────────────────────┘
```

### Recording with Dual Camera

```
┌─────────────────────────────────────────────────────────────────────┐
│ User taps record button                                             │
└───────────────────────────────┬─────────────────────────────────────┘
                                │
                                ▼
                    ┌───────────────────────┐
                    │ isDualCameraModeEnabled│
                    │       check            │
                    └───────────┬───────────┘
                                │
            ┌───────────────────┼───────────────────┐
            │ TRUE              │                   │ FALSE
            ▼                   │                   ▼
┌───────────────────────────┐   │   ┌───────────────────────────┐
│ Start                     │   │   │ Start RecordingService    │
│ DualCameraRecordingService│   │   │ (EXISTING - unchanged)    │
└───────────────────────────┘   │   └───────────────────────────┘
                                │
                                ▼
                    ┌───────────────────────┐
                    │ Both services use     │
                    │ same notification     │
                    │ channel for UX        │
                    └───────────────────────┘
```

---

## 📱 UI Mockup

```
┌─────────────────────────────────────────┐
│ ┌─────────────────────────────────────┐ │
│ │                                     │ │
│ │                                     │ │
│ │         BACK CAMERA (MAIN)          │ │
│ │                                     │ │
│ │                                     │ │
│ │                                     │ │
│ │                                     │ │
│ │                     ┌─────────────┐ │ │
│ │                     │   FRONT     │ │ │
│ │                     │   CAMERA    │ │ │
│ │                     │    (PiP)    │ │ │
│ │                     └─────────────┘ │ │
│ └─────────────────────────────────────┘ │
│                                         │
│  [🔄 Swap]  [⏺️ Record]  [⚙️ Settings]  │
│                                         │
└─────────────────────────────────────────┘

Swap button: Exchanges main/PiP cameras instantly
Settings: Opens DualCameraSettingsBottomSheet
```

---

## ✅ Testing Checklist

### Device Compatibility
- [ ] Android 11+ device with dual camera support
- [ ] Android 11+ device WITHOUT dual camera support (graceful message)
- [ ] Android 10 and below (feature hidden)

### Recording Scenarios
- [ ] Single camera → Start recording → Works as before
- [ ] Dual camera → Start recording → Both cameras captured
- [ ] Swap cameras during recording → No glitch, smooth swap
- [ ] Stop recording → Valid MP4 with PiP visible
- [ ] Long recording (1 hour) → Memory stable

### Edge Cases
- [ ] Phone call interrupts dual recording → Graceful pause/resume
- [ ] Low memory → Fallback to single camera with warning
- [ ] One camera fails during recording → Continue with remaining camera

---

## 📅 Timeline Summary

| Week | Phase | Deliverables | Status |
|------|-------|--------------|--------|
| 0 | Research | Codebase architecture audit, research summary | ✅ Done |
| 1 | Foundation | DualCameraCapability, Config, State classes + Constants + SharedPrefs | ✅ Done |
| 2 | Service | DualCameraRecordingService skeleton | ✅ Done |
| 3 | OpenGL | DualCameraCompositor with PiP rendering | ✅ Done |
| 4 | UI | Toggle, settings bottom sheet, ViewModel | ✅ Done |
| 5 | Integration | Recording flow, AndroidManifest | ✅ Done |
| 6 | Polish | Edge cases, optimization, cleanup | ⬜ Not started |

---

## 🚨 Risk Mitigation

| Risk | Mitigation |
|------|------------|
| Device fragmentation | DualCameraCapability checks at runtime |
| Performance issues | Separate pipeline, can reduce resolution if needed |
| Existing code breakage | 100% new files, no modifications to RecordingService |
| User confusion | Clear toggle, helpful error messages |

---

## 📚 References

- [Android Concurrent Camera Docs](https://developer.android.com/training/camera2/concurrent)
- [Existing RecordingService.java](app/src/main/java/com/fadcam/services/RecordingService.java)
- [Existing GLRecordingPipeline.java](app/src/main/java/com/fadcam/opengl/GLRecordingPipeline.java)
