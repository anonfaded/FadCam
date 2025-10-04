# FadRec (Screen Recording) Implementation Tasks

## 📋 Overview

This document tracks the implementation of FadRec, the screen recording feature for FadCam. FadRec will enable users to record their device screen with audio, following the same modular architecture as the existing camera recording feature.

**Key Design Principles:**

- ✅ Object-Oriented Programming with inheritance to reuse FadCam classes
- ✅ Modular folder structure (`fadrec` package)
- ✅ Extend existing classes instead of rewriting
- ✅ Keep it minimal and practical
- ✅ Use OpenGL for live watermarking (no FFmpeg post-processing)

---

## 🎯 Project Goals

### Core Functionality

- [ ] Screen recording using MediaProjection API (default FHD quality, 30fps)
- [ ] Audio recording (microphone support)
- [ ] Start/Stop/Pause/Resume controls
- [ ] Notification controls during recording
- [ ] Background recording support

### Integration Goals

- [ ] Mode switching between FadCam and FadRec in Home tab
- [ ] Settings tab context-based (show FadRec settings when in FadRec mode)
- [ ] Unified video management in Records tab (FadRec videos mixed with FadCam)
- [ ] Reuse existing video operations (rename, delete, share via inheritance)

---

## 📦 Phase 1: Project Setup & Core Infrastructure

### 1.1 Package Structure

**Create new package:** `app/src/main/java/com/fadcam/fadrec/`

- [x] Create folder structure: `fadrec/services/`, `fadrec/ui/`, `fadrec/settings/`

### 1.2 Constants & Configuration

- [x] Add FadRec-specific constants to `Constants.java`
  - [x] `RECORDING_DIRECTORY_FADREC` = "FadRec"
  - [x] `RECORDING_FILE_PREFIX_FADREC` = "FadRec\_"
  - [x] Screen recording intent actions and broadcast constants
  - [x] Default FHD quality settings (1920x1080)
  - [x] Default FPS (30fps)
  - [x] Audio source constants (mic only for now)

### 1.3 Permissions & Manifest Updates

- [x] Verify `FOREGROUND_SERVICE_MEDIA_PROJECTION` permission (already present)
- [x] Add `ScreenRecordingService` declaration with `mediaProjection` foreground service type
- [x] Ensure notification channel for screen recording

### 1.4 State Management

- [x] Create `fadrec/ScreenRecordingState.java` enum (reuse pattern from `RecordingState.java`)
  - States: `NONE`, `IN_PROGRESS`, `PAUSED`, `STOPPING`
- [x] Extend `SharedPreferencesManager.java` with screen recording methods
  - [x] Current screen recording state
  - [x] Audio source preference (mic/none)
  - [x] Watermark enabled state

---

## 🎬 Phase 2: Screen Recording Service

### 2.1 Create Base Service Structure

**File:** `app/src/main/java/com/fadcam/fadrec/services/ScreenRecordingService.java`

- [x] Extend `Service` class (reference `RecordingService` structure)
- [x] Initialize MediaProjection and MediaRecorder
- [x] Setup notification for foreground service (reuse notification channel)
- [x] Implement WakeLock for background recording
- [x] Create background handler for recording operations

### 2.2 MediaProjection Setup

- [x] Initialize MediaProjection from Intent result data
- [x] Setup VirtualDisplay for screen capture (default device resolution)
- [x] Use device's native screen resolution and density
- [x] Handle permission denial errors
- [x] Release resources properly in `onDestroy()`

### 2.3 MediaRecorder Configuration

- [x] Configure H.264 video encoder (default)
- [x] Setup microphone audio source
- [x] Set default FHD resolution (1920x1080) and 30fps
- [x] Configure bitrate (8Mbps for FHD)
- [x] Set output file path with FadRec prefix in FadRec folder
- [x] Handle codec unavailability gracefully

### 2.4 OpenGL Watermark Integration

- [ ] Extend existing `GLRecordingPipeline` for screen recording
- [ ] Create `ScreenRecordingWatermarkProvider` implementing `WatermarkInfoProvider`
- [ ] Apply live watermark to VirtualDisplay surface (similar to camera)
- [ ] Support timestamp, device info watermarks
- [ ] No post-processing needed (real-time watermarking)

### 2.5 Recording Control Methods

- [x] `startScreenRecording()` - Initialize and start
  - [x] Create FadRec output file
  - [x] Configure MediaRecorder with default settings
  - [x] Start VirtualDisplay with OpenGL watermark pipeline
  - [x] Update notification
  - [x] Broadcast recording started event
- [x] `pauseScreenRecording()` - Pause recording
  - [x] Pause MediaRecorder
  - [x] Update notification state
  - [x] Broadcast pause event
- [x] `resumeScreenRecording()` - Resume recording
  - [x] Resume MediaRecorder
  - [x] Update notification
  - [x] Broadcast resume event
- [x] `stopScreenRecording()` - Stop and finalize
  - [x] Stop MediaRecorder
  - [x] Release MediaProjection and VirtualDisplay
  - [x] Release OpenGL resources
  - [x] Broadcast recording stopped event
  - [x] Clean up resources

### 2.6 Intent Handling

- [x] Handle `ACTION_START_SCREEN_RECORDING` intent
- [x] Handle `ACTION_PAUSE_SCREEN_RECORDING` intent
- [x] Handle `ACTION_RESUME_SCREEN_RECORDING` intent
- [x] Handle `ACTION_STOP_SCREEN_RECORDING` intent

### 2.7 Broadcast Senders

- [x] Send `BROADCAST_ON_SCREEN_RECORDING_STARTED`
- [x] Send `BROADCAST_ON_SCREEN_RECORDING_PAUSED`
- [x] Send `BROADCAST_ON_SCREEN_RECORDING_RESUMED`
- [x] Send `BROADCAST_ON_SCREEN_RECORDING_STOPPED`
- [x] Send state callback broadcasts for UI synchronization

### 2.8 Notification Management

- [x] Create persistent notification with recording status
- [x] Add action buttons: Stop, Pause/Resume
- [x] Show recording timer in notification
- [ ] Update notification with elapsed time
- [ ] Handle notification button clicks via PendingIntents

---

## 🎨 Phase 3: UI Components & Home Fragment Integration

### 3.1 Mode Switching Logic

**File:** `app/src/main/java/com/fadcam/ui/helpers/HomeFragmentHelper.java`

- [ ] Update `handleModeSelection()` to support FadRec mode
- [ ] Remove "Coming Soon" toast for FadRec
- [ ] Trigger UI context change when FadRec is selected
- [ ] Save current mode to SharedPreferences

### 3.2 Home Fragment Context Switching

**File:** `app/src/main/java/com/fadcam/ui/HomeFragment.java`

- [ ] Add field: `private String currentRecordingMode = Constants.MODE_FADCAM`
- [ ] Add method `switchToFadRecContext()`
  - [ ] Hide camera-specific controls (camera switch button, flash, zoom)
  - [ ] Hide TextureView preview
  - [ ] Update info cards (screen resolution, storage info)
  - [ ] Show simple "Ready to record screen" message
- [ ] Add method `switchToFadCamContext()`
  - [ ] Restore camera controls visibility
  - [ ] Re-enable TextureView preview
  - [ ] Update info cards back to camera info
- [ ] Update Start/Stop button handlers to call ScreenRecordingService when in FadRec mode

### 3.3 Info Cards Update

- [ ] Modify existing info cards in HomeFragment to show context-based data
  - [ ] When FadRec mode: Show screen resolution (device native)
  - [ ] When FadCam mode: Show camera info (existing behavior)
  - [ ] Storage card remains same for both modes

### 3.4 Layout Updates

**File:** `app/src/main/res/layout/fragment_home.xml`

- [ ] Make camera-specific controls visibility conditional
  - [ ] Camera switch button: `android:id="@+id/buttonCamSwitch"`
  - [ ] Flash button
  - [ ] Zoom controls
- [ ] TextureView can remain (just hide/show based on mode)
- [ ] Info cards reuse existing layout, update dynamically

---

## ⚙️ Phase 4: Settings & Preferences (Context-Based)

### 4.1 Settings Tab Context Switching

**File:** `app/src/main/java/com/fadcam/ui/SettingsHomeFragment.java` (or main settings entry)

- [ ] Add mode-aware state tracking (FadCam vs FadRec)
- [ ] Show different settings sections based on current mode:
  - **FadCam Mode:** Show Video Settings, Audio Settings, Camera Settings, Watermark, etc. (existing)
  - **FadRec Mode:** Show only FadRec-specific settings (Audio, Watermark)

### 4.2 Create FadRec Settings Fragment

**File:** `app/src/main/java/com/fadcam/fadrec/settings/FadRecAudioSettingsFragment.java`

- [ ] Extend `AudioSettingsFragment` (reuse existing audio settings)
- [ ] Override to show only microphone audio source option
- [ ] Remove camera-specific audio options

**File:** `app/src/main/java/com/fadcam/fadrec/settings/FadRecWatermarkSettingsFragment.java`

- [ ] Extend `WatermarkSettingsFragment` (reuse existing watermark logic)
- [ ] Support timestamp, device info, custom text watermarks
- [ ] Works with OpenGL pipeline for screen recording

### 4.3 SharedPreferencesManager Updates

**File:** `app/src/main/java/com/fadcam/SharedPreferencesManager.java`

- [ ] Add getter/setter for current mode (FadCam/FadRec)
- [ ] Add getter/setter for screen recording audio source (mic/none)
- [ ] Add getter/setter for screen recording watermark enabled
- [ ] Reuse existing watermark preference methods

### 4.4 Settings Integration

- [ ] Settings tab shows context-appropriate options based on mode
- [ ] When user switches mode in Home tab, Settings tab updates accordingly
- [ ] Follow existing Material Design theme and patterns

---

## 🎥 Phase 5: Recording Workflow Implementation

### 5.1 Permission Handling

**File:** `app/src/main/java/com/fadcam/ui/HomeFragment.java`

- [ ] Add method `requestScreenRecordingPermission()`
  - [ ] Get MediaProjectionManager
  - [ ] Call `createScreenCaptureIntent()`
  - [ ] Launch with `startActivityForResult()` (or new Activity Result API)
- [ ] Handle permission result in `onActivityResult()`
  - [ ] On success: Pass result data to ScreenRecordingService
  - [ ] On denial: Show toast "Screen recording permission required"

### 5.2 Start Recording Flow

- [ ] User clicks Start button (existing button, context-aware)
- [ ] Check current mode (FadRec vs FadCam)
- [ ] If FadRec mode and permission not granted: Request permission
- [ ] If permission granted: Start ScreenRecordingService with result data
- [ ] Service broadcasts recording started
- [ ] UI updates to show Stop/Pause buttons (reuse existing UI)
- [ ] Start recording timer (reuse existing timer)

### 5.3 Stop Recording Flow

- [ ] User clicks Stop button
- [ ] Send stop intent to ScreenRecordingService
- [ ] Service stops MediaRecorder and releases resources
- [ ] OpenGL watermark applied in real-time (no post-processing)
- [ ] File saved with FadRec prefix in FadRec folder
- [ ] Broadcast recording stopped
- [ ] UI resets to idle state
- [ ] Show completion message

### 5.4 Pause/Resume Flow

- [ ] User clicks Pause button (reuse existing button)
- [ ] Send pause intent to ScreenRecordingService
- [ ] Service pauses MediaRecorder
- [ ] UI shows Resume icon (reuse existing)
- [ ] Timer pauses
- [ ] User clicks Resume
- [ ] Service resumes recording
- [ ] UI updates accordingly

---

## 📁 Phase 6: File Management & Integration

### 6.1 Records Fragment Integration

**File:** `app/src/main/java/com/fadcam/ui/RecordsFragment.java`

- [ ] Update `getInternalRecordsList()` to scan both FadCam and FadRec directories
- [ ] Mix FadCam and FadRec videos in same list (sorted by date)
- [ ] FadRec videos will have "FadRec\_" prefix in filename
- [ ] **Later:** Add filter option (All, FadCam only, FadRec only)

### 6.2 Video Item Enhancement

**File:** `app/src/main/java/com/fadcam/ui/VideoItem.java`

- [ ] Add field `recordingType` enum (FADCAM or FADREC)
- [ ] Update constructors to detect type from filename prefix
- [ ] Add method `isFadRecVideo()` for type checking

### 6.3 Reuse Existing Video Operations

- [ ] FadRec videos use same operations as FadCam:
  - [ ] Rename (existing `InputActionBottomSheetFragment`)
  - [ ] Delete (existing delete logic with trash)
  - [ ] Share (existing share logic)
  - [ ] Save to Gallery (existing save logic)
  - [ ] Video playback (existing `VideoPlayerActivity`)

### 6.4 File Storage Structure

- [ ] Create `FadRec/` subdirectory in app storage (same level as FadCam)
- [ ] Filename pattern: `FadRec_YYYYMMDD_HHMMSS.mp4`
- [ ] Use same file permissions as FadCam videos

---

## 🎨 Phase 7: OpenGL Watermarking (Real-Time)

### 7.1 Watermark Support for Screen Recordings

**File:** `app/src/main/java/com/fadcam/fadrec/services/ScreenRecordingService.java`

- [ ] Extend existing `GLRecordingPipeline` for screen recording use
- [ ] Create `ScreenRecordingWatermarkProvider` implementing `WatermarkInfoProvider`
- [ ] Apply watermark in real-time during recording (no post-processing)
- [ ] Support same watermark options as FadCam:
  - [ ] Timestamp
  - [ ] Device info
  - [ ] Custom text
  - [ ] Location data (if enabled)
- [ ] Watermark applied via OpenGL to VirtualDisplay surface before encoding
- [ ] No FFmpeg, no post-processing delays

---

## 🎨 Phase 8: Error Handling & Edge Cases

### 8.1 Error Handling

- [ ] Handle MediaProjection permission denial gracefully
- [ ] Handle storage full scenarios
- [ ] Handle codec unavailability
- [ ] Handle app killed during recording (auto-recovery on restart)
- [ ] Handle incoming calls during recording
- [ ] Show user-friendly error messages

### 8.2 Edge Cases

- [ ] Low storage warning before starting
- [ ] Rapid start/stop clicks prevention (debounce)
- [ ] Device rotation during recording (maintain recording)
- [ ] Battery optimization bypass prompt if needed

---

## 💡 Suggestions & Enhancements (Future Considerations)

### Advanced Features

- [ ] **Picture-in-Picture mode** - Continue recording while using other apps
- [ ] **Draw on screen** - Ability to annotate while recording
- [ ] **Trim recordings** - Built-in video trimming tool
- [ ] **GIF conversion** - Convert screen recordings to GIFs
- [ ] **Scheduled recordings** - Start recording at specific time
- [ ] **Game mode** - Optimized settings for gaming
- [ ] **Streaming integration** - Direct streaming to platforms
- [ ] **Voice commentary** - Add voice overlay separately from system audio
- [ ] **Face cam overlay** - Record front camera in corner while screen recording
- [ ] **Custom recording areas** - Select specific region of screen to record

### Quality of Life Improvements

- [ ] **Quick settings tile** - Android Quick Settings tile for instant recording
- [ ] **Widget support** - Home screen widget for one-tap recording
- [ ] **Shake to start/stop** - Use device shake gesture to control recording
- [ ] **Auto-upload** - Upload recordings to cloud storage automatically
- [ ] **Recording templates** - Save and load recording presets
- [ ] **Recording history** - Track recording statistics and history

### Technical Improvements

- [ ] **Hardware acceleration** - Utilize GPU for encoding
- [ ] **Adaptive bitrate** - Adjust quality based on device performance
- [ ] **Multi-format export** - Support WebM, AVI, etc.
- [ ] **Codec selection** - Let users choose H.264 vs H.265 vs VP9
- [ ] **Variable frame rate** - Optimize file size with VFR
- [ ] **Internal audio capture** - Record system/app audio (Android 10+ with restrictions)
- [ ] **Resolution options** - Add 720p, 1440p, 4K options later
- [ ] **FPS options** - Add 60fps, 120fps options later
- [ ] **Orientation lock** - Lock to portrait/landscape if needed
- [ ] **Show touches/taps** - Visual feedback for tutorials

---

## 📊 Progress Tracking

### Overall Progress

- **Phase 1:** ✅ 100% Complete (11/11 tasks)
- **Phase 2:** ⬜ 0% Complete (0/27 tasks)
- **Phase 3:** ⬜ 0% Complete (0/11 tasks)
- **Phase 4:** ⬜ 0% Complete (0/10 tasks)
- **Phase 5:** ⬜ 0% Complete (0/14 tasks)
- **Phase 6:** ⬜ 0% Complete (0/9 tasks)
- **Phase 7:** ⬜ 0% Complete (0/6 tasks)
- **Phase 8:** ⬜ 0% Complete (0/6 tasks)

**Total Core Tasks:** 11/94 (12%)

---

## 🏗️ Architecture Overview

### Component Hierarchy

```
FadRec Feature
│
├── fadrec/services/
│   └── ScreenRecordingService.java (main service)
│
├── fadrec/ui/
│   └── (future UI components if needed)
│
├── fadrec/settings/
│   ├── FadRecAudioSettingsFragment.java (extends AudioSettingsFragment)
│   └── FadRecWatermarkSettingsFragment.java (extends WatermarkSettingsFragment)
│
├── UI Layer (Modified)
│   ├── HomeFragment.java (mode switcher integration, context switching)
│   ├── SettingsHomeFragment.java (context-based settings display)
│   └── RecordsFragment.java (unified video display)
│
├── Data Layer (Extended)
│   ├── SharedPreferencesManager.java (screen recording prefs)
│   ├── Constants.java (FadRec constants)
│   ├── ScreenRecordingState.java (state enum)
│   └── VideoItem.java (recordingType field)
│
└── Integration Layer
    ├── OpenGL Pipeline (GLRecordingPipeline extended for screen)
    └── Notification system (reused from FadCam)
```

### Key Design Decisions

1. **Separate Service**: ScreenRecordingService in `fadrec/services/` package, completely separate from RecordingService
2. **Mode-based UI**: HomeFragment dynamically switches context, Settings tab shows context-appropriate options
3. **Unified Records**: FadCam and FadRec videos mixed together, distinguishable by filename prefix
4. **Inheritance & Reuse**: Extend existing settings fragments, reuse video operations (rename, delete, share)
5. **OpenGL Watermarking**: Real-time watermarking using existing GLRecordingPipeline (no FFmpeg post-processing)
6. **Minimal & Practical**: Default FHD quality, no complex configuration, focus on core functionality
7. **Modular Package**: `com.fadcam.fadrec/` folder structure for better organization

---

## 🎓 Implementation Guidelines

### Coding Standards (From copilot.instructions.md)

1. ✅ Follow Google/Oracle Java style guides
2. ✅ Single Responsibility Principle - one class, one purpose
3. ✅ Methods should be ≤ 50 lines
4. ✅ Clear naming with action verbs
5. ✅ Never silently swallow exceptions
6. ✅ Use structured logging (SLF4J/Log4j, not System.out.println)
7. ✅ Avoid code duplication (DRY principle)
8. ✅ Add Javadoc for public classes/methods
9. ✅ Use inheritance and OOP patterns to extend FadCam classes
10. ✅ Build and install after changes: `./gradlew.bat compileDebugJavaWithJavac installDebug`

### Hardware Considerations

- Assume hardware/OEM differences across devices
- MediaProjection API behavior varies by manufacturer
- Test on real devices (Samsung, Pixel, Xiaomi, OnePlus)
- Handle codec availability differences gracefully

---

## 🔨 Build & Test Command

After making changes, build and install:

```bash
./gradlew.bat compileDebugJavaWithJavac installDebug
```

---

**Last Updated:** October 4, 2025
**Version:** 2.0 (Minimal & Practical)
**Status:** Ready for Implementation 🚀
