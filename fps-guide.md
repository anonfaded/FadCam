hey read these files as i hv done changes in them and then i will give u a guide from another ai about the issue and then u need to fix that. 
        modified:   app/src/main/java/com/fadcam/opengl/GLRecordingPipeline.java
        modified:   app/src/main/java/com/fadcam/services/RecordingService.java
        modified:   app/src/main/java/com/fadcam/ui/VideoSettingsFragment.java
        modified:   app/src/main/java/com/fadcam/utils/camera/vendor/SamsungFrameRateHelper.java

and the main issue is with the 60 fps not working even tho in legacy settingsfragment it was working fine in app and it was all fine tho , so chekc are we not migrated fully for framerate feature or what? and if we instead of migrating the framerate feature use that exisitng legacy settings just for this framerate so it works? like same way it was working in legacy tho. also before it was using a spinner for framerate options but in new design we have custom bottom sheet picker for it so if this or any issue is there please check and fix it. and the ai that gave me guide is this, in this first just work on the fps issue so i can test and make it work at least:
You've encountered two classic and challenging device-specific issues. The logs and your detailed report are extremely helpful. My previous analysis was on the right track regarding the high-speed session, but it was incomplete, which led to the crash on the Samsung device.

Let's break down the final verdict for both issues and provide the definitive solutions.

### **Issue 1: Samsung Crash & The `ForegroundServiceDidNotStartInTimeException`**

**Verdict:** The crash on the Samsung S23 is a direct result of a race condition. When you call `startForegroundService()`, Android gives your service a very short window (about 5-10 seconds) to call `Service.startForeground()`. Your `RecordingService` was trying to do a very slow operation—**opening the camera** (`openCamera()` and waiting for its callbacks)—*before* calling `startForeground()`. On some devices, especially under load, opening the camera can take too long, causing Android to terminate your app.

**Solution:** You must call `startForeground()` **immediately** when the `ACTION_START_RECORDING` intent is received, before any potentially long-running operations like opening the camera.

Here is the corrected logic for `RecordingService.java`:

```java
// -------------- Fix Start for this method(onStartCommand)-----------
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            Log.d(TAG, "onStartCommand received null intent. Ensuring service stays alive.");
            return START_STICKY;
        }
        Log.d(TAG, "onStartCommand received: Action=" + intent.getAction());
        String action = intent.getAction();
        if (action == null) {
            Log.w(TAG, "onStartCommand: Action is null.");
            return START_STICKY;
        }

        if (Constants.INTENT_ACTION_START_RECORDING.equals(action)) {
            // ===============================================================
            //  CRITICAL FIX: Call startForeground() IMMEDIATELY.
            // ===============================================================
            setupRecordingInProgressNotification();

            if (isCameraResourceReleasing) {
                Log.w(TAG, "START_RECORDING rejected - camera resources still being released");
                mainHandler.post(() -> Toast.makeText(getApplicationContext(), R.string.camera_resources_cooldown, Toast.LENGTH_LONG).show());
                return START_STICKY;
            }

            Log.i(TAG, "Handling START_RECORDING intent. Service recording state is " + recordingState);

            if (recordingState != RecordingState.NONE) {
                 Log.w(TAG, "Cannot start recording, already in state: " + recordingState);
                Toast.makeText(this, getString(R.string.recording_already_active), Toast.LENGTH_SHORT).show();
                return START_STICKY;
            }
            
            recordingState = RecordingState.STARTING;
            sharedPreferencesManager.setRecordingInProgress(true);
            isRecordingTorchEnabled = intent.getBooleanExtra(Constants.INTENT_EXTRA_INITIAL_TORCH_STATE, false);
            Log.d(TAG, "Initial torch state for recording session: " + isRecordingTorchEnabled);
            
            setupSurfaceTexture(intent);
            
            // Notification is now setup, proceed with camera and recording
            if (cameraDevice == null) {
                pendingStartRecording = true;
                Log.d(TAG, "Setting pendingStartRecording=true, will start recording after camera opens");
                openCamera();
            } else {
                Log.d(TAG, "Camera already open, starting recording directly");
                startRecording();
            }
            broadcastOnRecordingStarted();

            return START_STICKY;
        }
        // ... (rest of your onStartCommand method remains the same)
        else if ("ACTION_APP_BACKGROUND".equals(action)) {
            Log.d(TAG, "Received ACTION_APP_BACKGROUND: releasing preview EGL/GL resources");
            if (glRecordingPipeline != null) {
                try {
                    glRecordingPipeline.releasePreviewResources(); // Only release preview EGL/GL
                } catch (Exception e) {
                    Log.e(TAG, "Error releasing preview EGL/GL on app background", e);
                }
            }
            return START_STICKY;
        } else if ("ACTION_APP_FOREGROUND".equals(action)) {
            Log.d(TAG, "Received ACTION_APP_FOREGROUND: re-initializing pipeline if recording in progress");
            if (sharedPreferencesManager != null && sharedPreferencesManager.isRecordingInProgress()) {
                // Defensive: only re-initialize if not already running
                if (glRecordingPipeline == null) {
                    // Recreate pipeline and surfaces (minimal, actual re-init logic may be more complex)
                    // You may want to trigger the same logic as when starting recording
                    // For now, just log and rely on UI/fragment to trigger full re-init
                    Log.d(TAG, "App foregrounded and recording in progress, pipeline will be re-initialized by UI");
                }
            }
            return START_STICKY;
        }
        if (Constants.INTENT_ACTION_STOP_RECORDING.equals(action)) {
            stopRecording();
            return START_STICKY;
        } else if (Constants.INTENT_ACTION_PAUSE_RECORDING.equals(action)) {
            pauseRecording();
            return START_STICKY;
        } else if (Constants.INTENT_ACTION_RESUME_RECORDING.equals(action)) {
            // Set up preview surface if provided (important when resuming)
            setupSurfaceTexture(intent);
            resumeRecording();
            return START_STICKY;
        } else if (Constants.INTENT_ACTION_CHANGE_SURFACE.equals(action)) {
            // Handle surface changes for preview
            setupSurfaceTexture(intent);
            if (glRecordingPipeline != null) {
                // Only update the preview surface, never re-initialize or re-prepare the pipeline
                glRecordingPipeline.setPreviewSurface(previewSurface);
            }
            // Only reconfigure the camera session if recording or paused
            if (isRecording() || isPaused()) {
                createCameraPreviewSession();
            }
            Log.d(TAG, "ACTION_CHANGE_SURFACE handled: preview surface updated, camera session reconfigured if needed. No pipeline re-init.");
            return START_STICKY;
        } else if (Constants.BROADCAST_ON_RECORDING_STATE_REQUEST.equals(action)) {
            // Handle UI state sync requests
            Log.d(TAG, "Responding to state request");
            broadcastOnRecordingStateCallback();
            if (!isWorkingInProgress()) {
                stopSelf();
            }
            return START_STICKY;
        } else if (Constants.INTENT_ACTION_TOGGLE_RECORDING_TORCH.equals(action)) {
            // Handle torch toggle requests
            toggleRecordingTorch();
            return START_STICKY;
        } 

        else if (Constants.INTENT_ACTION_REINITIALIZE_LOCATION.equals(action)) {
            // Handle request to reinitialize location helpers after settings change
            Log.d(TAG, "Handling REINITIALIZE_LOCATION intent");
            
            // Extract the embedding preference directly from intent if available
            boolean forceInit = intent.getBooleanExtra("force_init", false);
            boolean embedLocationFromIntent = intent.getBooleanExtra("embed_location", false);
            boolean hasLocationPermission = intent.getBooleanExtra("has_permission", false);
            
            // Log the values for debugging
            Log.d(TAG, "Location intent extras:");
            Log.d(TAG, "  - force_init: " + forceInit);
            Log.d(TAG, "  - embed_location: " + embedLocationFromIntent);
            Log.d(TAG, "  - has_permission: " + hasLocationPermission);
            
            // If embed_location is true but permission is not granted, log warning
            if (embedLocationFromIntent && !hasLocationPermission) {
                Log.w(TAG, "Warning: Location embedding requested but permission is not granted");
                // Don't override preference in this case - let the UI control it
            }
            // If intent explicitly specifies the embed_location value, use it to force override the preference
            else if (intent.hasExtra("embed_location")) {
                Log.d(TAG, "Intent explicitly specifies embed_location=" + embedLocationFromIntent);
                
                // Force the preference to match what was sent in the intent
                if (sharedPreferencesManager.isLocationEmbeddingEnabled() != embedLocationFromIntent) {
                    Log.d(TAG, "Updating preferences to match intent value");
                    sharedPreferencesManager.sharedPreferences.edit()
                        .putBoolean(Constants.PREF_EMBED_LOCATION_DATA, embedLocationFromIntent)
                        .apply();
                }
            }
            
            // Now reinitialize with potential updated preferences
            reinitializeLocationHelpers(forceInit);
            return START_STICKY;
        } 

        else {
            Log.w(TAG, "Unknown action received: " + action);
            if (!isWorkingInProgress()) stopSelf();
            return START_NOT_STICKY;
        }
    }
// -------------- Fix Ended for this method(onStartCommand)-----------
```

### **Issue 2: Google Pixel FPS Drop & Renderer Timeout**

**Verdict:** The inconsistent FPS on the Google Pixel (e.g., getting 39 FPS when 60 is selected) and the `renderToEncoder: frame wait timed out` logs are classic signs of a **performance bottleneck**. Your OpenGL rendering pipeline cannot keep up with the rate at which the camera is delivering frames. When the camera provides a frame every 16.6ms (for 60fps), your `renderRunnable` is taking longer than that to process a single frame, causing the next frame to be missed.

The main culprit is `updateWatermarkTexture()`. You are redrawing the entire watermark bitmap from scratch on **every single frame**. This is incredibly inefficient. The text only needs to be updated once per second.

**Solution:** Separate the watermark texture creation from the per-frame rendering. Create a separate, slower timer (`updateWatermarkRunnable`) that updates the bitmap only once per second. The high-frequency `renderRunnable` will simply draw the latest available bitmap.

Here are the required changes for `GLRecordingPipeline.java`:

```java
// -------------- Fix Start for GLRecordingPipeline.java (add new fields) -----------
    // Add these fields to your GLRecordingPipeline class
    private final Handler watermarkUpdateHandler = new Handler(Looper.getMainLooper());
    private Runnable updateWatermarkRunnable;
// -------------- Fix Ended for GLRecordingPipeline.java (add new fields) -----------
```

```java
// -------------- Fix Start for this method(startRecording in GLRecordingPipeline.java)-----------
    public void startRecording() {
        try {
            if (!isRecording) {
                Log.d(TAG, "Starting recording pipeline");

                // ... (existing setup code for prepareSurfaces, audio, etc.) ...
                if (glRenderer == null) { /* ... */ }

                isRecording = true;
                setupAudio();
                if (audioRecordingEnabled) {
                    startAudioThread();
                    try { Thread.sleep(50); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                }

                // ===============================================================
                //  CRITICAL FIX: Start a separate, low-frequency watermark updater.
                // ===============================================================
                if (updateWatermarkRunnable == null) {
                    updateWatermarkRunnable = new Runnable() {
                        @Override
                        public void run() {
                            if (isRecording) {
                                updateWatermark(); // This re-draws the bitmap
                                watermarkUpdateHandler.postDelayed(this, 1000); // Update only once per second
                            }
                        }
                    };
                    watermarkUpdateHandler.post(updateWatermarkRunnable);
                }

                startRenderLoop();
                Log.d(TAG, "Recording pipeline started successfully");
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to start recording pipeline", e);
            stopRecording();
        }
    }
// -------------- Fix Ended for this method(startRecording in GLRecordingPipeline.java)-----------
```

```java
// -------------- Fix Start for this method(renderRunnable in GLRecordingPipeline.java)-----------
    private final Runnable renderRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isRecording || released) {
                return;
            }

            try {
                // ===============================================================
                //  CRITICAL FIX: DO NOT update the watermark texture on every frame.
                //  The separate timer now handles that.
                // ===============================================================
                // updateWatermark(); // <-- REMOVE THIS LINE

                if (glRenderer != null) {
                    glRenderer.renderFrame();
                }
                drainEncoder();

                if (shouldSplitSegment()) {
                    Log.d(TAG, "Size limit reached, rolling over segment");
                    rolloverSegment();
                }

                // Continue rendering loop
                if (isRecording && handler != null) {
                    // Don't post here anymore, let onFrameAvailable trigger it
                    // handler.post(this); 
                }
            } catch (Exception e) {
                Log.e(TAG, "Error in render loop", e);
            }
        }
    };
// -------------- Fix Ended for this method(renderRunnable in GLRecordingPipeline.java)-----------
```

```java
// -------------- Fix Start for this method(stopRecording in GLRecordingPipeline.java)-----------
    public void stopRecording() {
        // ... (existing stop logic at the beginning) ...
        isRecording = false; // Set this flag early
        
        // ===============================================================
        //  CRITICAL FIX: Stop the watermark update handler.
        // ===============================================================
        watermarkUpdateHandler.removeCallbacks(updateWatermarkRunnable);
        updateWatermarkRunnable = null;

        // ... (the rest of your stopRecording logic remains the same) ...
    }
// -------------- Fix Ended for this method(stopRecording in GLRecordingPipeline.java)-----------
```

### **Issue 3: Correcting High-Speed Session Configuration**

The crash on the Samsung phone occurred because while we correctly identified the *need* for a high-speed session, we didn't check which **resolutions** the device supports for that mode. High-speed recording is often limited to specific resolutions (e.g., only 720p or 1080p, but not 4K).

**Solution:** Before creating a high-speed session, we must query the device for the list of supported high-speed resolutions for our target framerate and use one of those. If the user's selected resolution isn't supported for high-speed, we should fall back to a standard session and log a warning.

Here is the final, robust logic for `RecordingService.java`:

```java
// -------------- Fix Start for this method(createCameraPreviewSession - Final Version)-----------
    private void createCameraPreviewSession() {
        if (cameraDevice == null) {
            Log.e(TAG, "createCameraPreviewSession: cameraDevice is null!");
            stopRecording();
            return;
        }
        Log.d(TAG, "createCameraPreviewSession: Creating session...");
        try {
            List<Surface> surfaces = new ArrayList<>();
            if (glRecordingPipeline != null) {
                surfaces.add(glRecordingPipeline.getCameraInputSurface());
            }

            if (previewSurface != null && previewSurface.isValid()) {
                surfaces.add(previewSurface);
            } else if (dummyBackgroundSurface != null && dummyBackgroundSurface.isValid()) {
                surfaces.add(dummyBackgroundSurface);
            }
            
            if (surfaces.isEmpty()) {
                Log.e(TAG, "No valid surfaces to create a capture session.");
                stopRecording();
                return;
            }

            CameraManager cameraManager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
            CameraType cameraType = sharedPreferencesManager.getCameraSelection();
            String cameraId = getCameraId(cameraManager, cameraType);
            if (cameraId == null) {
                Log.e(TAG, "Could not get a valid camera ID.");
                stopRecording();
                return;
            }
            
            CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(cameraId);
            int targetFrameRate = sharedPreferencesManager.getVideoFrameRate();
            boolean isHighFrameRate = targetFrameRate >= 60;
            boolean useHighSpeedSession = false;

            if (isHighFrameRate) {
                // Check for high-speed support regardless of vendor first
                if (HighSpeedCaptureHelper.isHighSpeedSupported(characteristics, targetFrameRate)) {
                    // Now, get the BEST supported high-speed size. This is the critical step.
                    Size highSpeedSize = HighSpeedCaptureHelper.getBestHighSpeedSize(characteristics, targetFrameRate, videoWidth, videoHeight);
                    
                    if (highSpeedSize != null) {
                        Log.i(TAG, "Device supports high-speed session at " + highSpeedSize.toString() + " for " + targetFrameRate + "fps.");
                        // Check if the user's selected resolution matches a supported high-speed size
                        if (videoWidth == highSpeedSize.getWidth() && videoHeight == highSpeedSize.getHeight()) {
                            useHighSpeedSession = true;
                            Log.i(TAG, "Selected resolution matches supported high-speed size. Using High-Speed Session.");
                        } else {
                            Log.w(TAG, "Selected resolution " + videoWidth + "x" + videoHeight + " is NOT supported for high-speed capture. The device supports " + highSpeedSize.toString() + ". Falling back to standard session.");
                        }
                    } else {
                        Log.w(TAG, "isHighSpeedSupported was true, but getBestHighSpeedSize returned null. Inconsistency detected. Falling back to standard session.");
                    }
                } else {
                     Log.d(TAG, "High-speed not officially supported for " + targetFrameRate + "fps, using standard session with vendor keys (if any).");
                }
            }

            if (useHighSpeedSession) {
                createHighSpeedSession(surfaces, characteristics, targetFrameRate, cameraType);
            } else {
                createStandardSession(surfaces, targetFrameRate, characteristics, cameraType);
            }

        } catch (Exception e) {
            Log.e(TAG, "createCameraPreviewSession: Unhandled Exception", e);
            stopRecording();
        }
    }
// -------------- Fix Ended for this method(createCameraPreviewSession - Final Version)-----------
```

These combined fixes address the immediate crash, the performance bottleneck causing FPS drops, and the configuration error on Samsung devices, making the high framerate feature more robust and reliable across different hardware.