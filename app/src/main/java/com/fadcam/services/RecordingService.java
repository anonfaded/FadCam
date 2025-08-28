package com.fadcam.services;

import android.Manifest;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.media.MediaRecorder;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.PowerManager.WakeLock;
import android.os.SystemClock;
import android.util.Log; // Use standard Log
import android.util.Size;
import android.view.Surface;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.documentfile.provider.DocumentFile;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.pm.ServiceInfo;

import com.fadcam.CameraType;
import com.fadcam.Constants;
import com.fadcam.MainActivity;
import com.fadcam.R;
import com.fadcam.RecordingState;
import com.fadcam.SharedPreferencesManager; // Use your manager
import com.fadcam.Utils;
import com.fadcam.VideoCodec;
import com.fadcam.ui.LocationHelper;
import com.fadcam.ui.GeotagHelper;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

// Add Intent import

// Add Uri import
// Import your Constants class
// Add if needed
// Add if needed

import android.media.MediaRecorder.OnInfoListener;

import org.osmdroid.util.GeoPoint;

import java.util.concurrent.ConcurrentLinkedQueue;

// Add to the beginning of the file
import android.media.MediaMetadataRetriever;
import android.graphics.BitmapFactory;

import android.hardware.camera2.CameraConstrainedHighSpeedCaptureSession;
import com.fadcam.utils.DeviceHelper;
import com.fadcam.utils.camera.HighSpeedCaptureHelper;
import com.fadcam.utils.camera.vendor.SamsungFrameRateHelper;

// Add import
import com.fadcam.opengl.GLRecordingPipeline;
import com.fadcam.opengl.WatermarkInfoProvider;

import android.util.Range;
import android.graphics.Rect;
import android.hardware.camera2.params.MeteringRectangle;
import android.hardware.camera2.CameraMetadata;

public class RecordingService extends Service {

    private static final int NOTIFICATION_ID = 1;
    private static final String CHANNEL_ID = "RecordingServiceChannel";
    private static final String TAG = "RecordingService"; // Use standard Log TAG
    private static volatile boolean isCameraResourceReleasing = false;

    private CameraDevice cameraDevice;
    private CameraCaptureSession captureSession;
    private CaptureRequest.Builder captureRequestBuilder;
    private CameraCharacteristics currentCameraCharacteristics;
    private Surface previewSurface; // Surface from UI if preview enabled
    private boolean previewSurfaceAdded = false; // Flag to track if preview surface was added to session

    private LocationHelper locationHelper;
    private GeotagHelper geotagHelper;

    private RecordingState recordingState = RecordingState.NONE;
    private AtomicInteger ffmpegProcessingTaskCount = new AtomicInteger(0);

    private boolean isRecordingTorchEnabled = false;

    private CameraManager cameraManager; // Primary camera manager
    private Handler backgroundHandler; // For camera operations

    private long recordingStartTime;

    private SharedPreferencesManager sharedPreferencesManager; // Your settings manager

    private boolean isRolloverClosingOldSession = false; // Flag to manage state during segment rollover when the old
                                                         // session is closing

    private WakeLock recordingWakeLock;

    private boolean isCameraOpen = false;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private boolean pendingStartRecording = false;

    private volatile boolean isStopping = false;

    // -------------- Fix Start for this section(previewStartGateFields)-----------
    // Gate first-start until preview surface is ready (to avoid first-run EGL race)
    private boolean waitForPreviewBeforeStart = false;
    private Runnable previewWaitTimeoutRunnable = null;

    // Runtime camera control values that override saved preferences during active
    // recording
    private Integer runtimeExposureCompensation = null;
    private Boolean runtimeAeLock = null;
    private Integer runtimeAfMode = null;
    // -------------- Fix Ended for this section(previewStartGateFields)-----------

    // --- Lifecycle Methods ---
    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "onCreate: Service creating...");
        // Initialize essential components first
        sharedPreferencesManager = SharedPreferencesManager.getInstance(getApplicationContext());

        // Only initialize LocationHelper if location is explicitly enabled
        if (sharedPreferencesManager != null && sharedPreferencesManager.isLocalisationEnabled()) {
            locationHelper = new LocationHelper(this); // For watermark text
        } else {
            Log.d(TAG, "Location feature disabled, skipping LocationHelper initialization");
        }

        // Initialize GeotagHelper only if location embedding is enabled
        if (sharedPreferencesManager != null && sharedPreferencesManager.isLocationEmbeddingEnabled()) {
            try {
                geotagHelper = new GeotagHelper(this); // For metadata embedding
                boolean started = geotagHelper.startUpdates();
                Log.d(TAG, "Started geotagging updates for metadata embedding: " + started);
            } catch (Exception e) {
                Log.e(TAG, "Error initializing GeotagHelper", e);
            }
        } else {
            Log.d(TAG, "Location embedding disabled, skipping GeotagHelper initialization");
        }

        createNotificationChannel(); // Setup notifications early

        cameraManager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        if (cameraManager == null) {
            Log.e(TAG, "Failed to get CameraManager service.");
            stopSelf(); // Cannot function without CameraManager
            return;
        }

        HandlerThread backgroundThread = new HandlerThread("CameraBackground");
        backgroundThread.start();
        backgroundHandler = new Handler(backgroundThread.getLooper());

        Log.d(TAG, "Service created successfully.");

        // Broadcast initial camera resource availability
        broadcastCameraResourceAvailability(true);
        Log.d(TAG, "Broadcasting initial camera resource availability: true");
    }

    // -------------- Fix Start for this
    // method(applyExposureCompensation)-----------
    /**
     * Apply exposure compensation index if supported by the camera.
     * This will update the existing captureRequestBuilder and call
     * setRepeatingRequest.
     */
    private void applyExposureCompensation(int evIndex) {
        if (currentCameraCharacteristics == null || captureRequestBuilder == null || captureSession == null)
            return;
        Range<Integer> range = currentCameraCharacteristics.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE);
        if (range == null)
            return;
        int clamped = Math.max(range.getLower(), Math.min(range.getUpper(), evIndex));

        // Track runtime exposure compensation to override saved preferences
        runtimeExposureCompensation = clamped;

        // CRITICAL: Apply exposure through GL pipeline for immediate visual effect
        // Convert EV index to actual EV stops for GL shader
        float evStops = 0.0f;
        try {
            android.util.Rational stepRational = currentCameraCharacteristics
                    .get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP);
            if (stepRational != null) {
                evStops = clamped * stepRational.floatValue();
            } else {
                // Fallback: assume 1/3 EV step (common default)
                evStops = clamped * 0.33f;
            }
        } catch (Exception e) {
            evStops = clamped * 0.33f; // Safe fallback
        }

        // Apply exposure through GL pipeline for immediate visual effect in preview and
        // recording
        if (glRecordingPipeline != null) {
            glRecordingPipeline.setExposureCompensation(evStops);
            Log.d(TAG,
                    "Applied EV compensation through GL pipeline: index=" + clamped + " -> " + evStops + " EV stops");
        }

        try {
            // CRITICAL: Ensure AE mode is ON for exposure compensation to work
            // Many camera drivers ignore exposure compensation if AE mode is not explicitly
            // set
            captureRequestBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);

            // If AE lock was enabled, changing exposure compensation may be ignored by the
            // driver.
            // Detect and temporarily clear AE lock so the AE algorithm can apply the
            // compensation.
            Boolean aeLockNow = captureRequestBuilder.get(CaptureRequest.CONTROL_AE_LOCK);
            boolean hadAeLock = aeLockNow != null && aeLockNow;
            if (hadAeLock) {
                Log.d(TAG, "applyExposureCompensation: AE lock was enabled; temporarily clearing to apply EV");
                captureRequestBuilder.set(CaptureRequest.CONTROL_AE_LOCK, false);
            }

            captureRequestBuilder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, clamped);
            Log.d(TAG, "applyExposureCompensation: setting EV index=" + clamped + " (hadAeLock=" + hadAeLock
                    + ") with AE_MODE_ON");
            // If we're in a constrained high-speed session, we must use setRepeatingBurst
            try {
                // AGGRESSIVE: Multiple attempts for stubborn camera drivers
                // Some drivers need multiple capture/setRepeating calls to apply exposure
                // compensation
                for (int attempt = 0; attempt < 2; attempt++) {
                    try {
                        // Do a capture first to prime the driver with logging
                        captureSession.capture(captureRequestBuilder.build(),
                                new CameraCaptureSession.CaptureCallback() {
                                    @Override
                                    public void onCaptureCompleted(@NonNull CameraCaptureSession session,
                                            @NonNull CaptureRequest request,
                                            @NonNull android.hardware.camera2.TotalCaptureResult result) {
                                        Integer appliedEv = result.get(
                                                android.hardware.camera2.CaptureResult.CONTROL_AE_EXPOSURE_COMPENSATION);
                                        Integer aeMode = result
                                                .get(android.hardware.camera2.CaptureResult.CONTROL_AE_MODE);
                                        Log.d(TAG, "EV prime capture completed: Applied EV=" + appliedEv +
                                                ", AE Mode=" + aeMode + ", Target EV=" + clamped);
                                    }

                                    @Override
                                    public void onCaptureFailed(@NonNull CameraCaptureSession session,
                                            @NonNull CaptureRequest request,
                                            @NonNull android.hardware.camera2.CaptureFailure failure) {
                                        Log.w(TAG, "EV prime capture failed: " + failure.getReason());
                                    }
                                }, backgroundHandler);

                        // Small delay to let the driver process the capture
                        if (backgroundHandler != null) {
                            backgroundHandler.post(() -> {
                                try {
                                    // Then update the repeating request
                                    if (captureSession instanceof android.hardware.camera2.CameraConstrainedHighSpeedCaptureSession) {
                                        android.hardware.camera2.CameraConstrainedHighSpeedCaptureSession hs = (android.hardware.camera2.CameraConstrainedHighSpeedCaptureSession) captureSession;
                                        java.util.List<android.hardware.camera2.CaptureRequest> highSpeedRequests = hs
                                                .createHighSpeedRequestList(captureRequestBuilder.build());
                                        hs.setRepeatingBurst(highSpeedRequests, null, backgroundHandler);
                                        Log.d(TAG, "Updated high-speed repeating burst with EV=" + clamped);
                                    } else {
                                        captureSession.setRepeatingRequest(captureRequestBuilder.build(), null,
                                                backgroundHandler);
                                        Log.d(TAG, "Updated standard repeating request with EV=" + clamped);
                                    }
                                } catch (Exception e) {
                                    Log.d(TAG, "Delayed setRepeating failed: " + e.getMessage());
                                }
                            });
                        }

                        // If first attempt succeeded, break
                        break;
                    } catch (Exception attemptEx) {
                        Log.d(TAG, "applyExposureCompensation: attempt " + (attempt + 1) + " failed: "
                                + attemptEx.getMessage());
                        if (attempt == 0) {
                            // Wait a bit before retry
                            try {
                                Thread.sleep(50);
                            } catch (InterruptedException ie) {
                                Thread.currentThread().interrupt();
                            }
                        }
                    }
                }
            } catch (Exception outerEx) {
                // Final fallback: simple setRepeatingRequest
                try {
                    captureSession.setRepeatingRequest(captureRequestBuilder.build(), null, backgroundHandler);
                } catch (Exception fallbackEx) {
                    Log.d(TAG, "Even fallback setRepeating failed: " + fallbackEx.getMessage());
                }
            }
            // Note: we intentionally do not re-enable AE lock here. Restoring it
            // immediately can prevent the
            // exposure compensation from taking effect on some devices. The AE lock tile
            // controls AE lock explicitly.
        } catch (Exception e) {
            // Catch any remaining exceptions to avoid crashing the service
            Log.w(TAG, "applyExposureCompensation: Unexpected error: " + e.getMessage());
        }
    }
    // -------------- Fix Ended for this
    // method(applyExposureCompensation)-----------

    // -------------- Fix Start for this method(applyAeLock)-----------
    /**
     * Toggle AE lock during a running session.
     */
    private void applyAeLock(boolean lock) {
        if (currentCameraCharacteristics == null || captureRequestBuilder == null || captureSession == null)
            return;
        Boolean aeLockSupported = currentCameraCharacteristics.get(CameraCharacteristics.CONTROL_AE_LOCK_AVAILABLE);
        if (aeLockSupported == null || !aeLockSupported)
            return;

        // Track runtime AE lock to override saved preferences
        runtimeAeLock = lock;

        try {
            captureRequestBuilder.set(CaptureRequest.CONTROL_AE_LOCK, lock);
            captureSession.setRepeatingRequest(captureRequestBuilder.build(), null, backgroundHandler);
        } catch (CameraAccessException | IllegalStateException e) {
            // ignore
        }
    }
    // -------------- Fix Ended for this method(applyAeLock)-----------

    // -------------- Fix Start for this method(applyAfMode)-----------
    /**
     * Change AF mode (e.g., continuous video, off, etc.) when supported.
     */
    private void applyAfMode(int afMode) {
        if (currentCameraCharacteristics == null || captureRequestBuilder == null || captureSession == null)
            return;
        int[] modes = currentCameraCharacteristics.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES);
        if (modes == null)
            return;
        boolean supported = false;
        for (int m : modes)
            if (m == afMode) {
                supported = true;
                break;
            }
        if (!supported)
            return;

        // Track runtime AF mode to override saved preferences
        runtimeAfMode = afMode;

        try {
            captureRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, afMode);
            // If switching to auto or continuous, ensure the AF trigger is reset
            captureRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_IDLE);
            captureSession.setRepeatingRequest(captureRequestBuilder.build(), null, backgroundHandler);
        } catch (CameraAccessException | IllegalStateException e) {
            // ignore
        }
    }
    // -------------- Fix Ended for this method(applyAfMode)-----------

    // -------------- Fix Start for this method(applyZoomRatio)-----------
    /**
     * Apply zoom ratio to the current capture session.
     * Also saves the zoom ratio to preferences for the current camera type.
     */
    private void applyZoomRatio(float zoomRatio) {
        Log.d(TAG, "Applying zoom ratio: " + zoomRatio);

        if (captureRequestBuilder == null || captureSession == null) {
            Log.w(TAG, "Cannot apply zoom - captureRequestBuilder or captureSession is null");
            return;
        }

        try {
            // Apply zoom ratio to capture request
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                captureRequestBuilder.set(CaptureRequest.CONTROL_ZOOM_RATIO, zoomRatio);
            } else {
                // For older API levels, fall back to digital zoom via crop region
                Log.d(TAG, "Using crop region for zoom on API < 30");
            }

            // Update the repeating request
            captureSession.setRepeatingRequest(captureRequestBuilder.build(), null, backgroundHandler);

            // Save to preferences for persistence
            CameraType currentCamera = sharedPreferencesManager.getCameraSelection();
            sharedPreferencesManager.setSpecificZoomRatio(currentCamera, zoomRatio);

            Log.d(TAG, "Successfully applied zoom ratio " + zoomRatio + " for " + currentCamera + " camera");
        } catch (CameraAccessException | IllegalStateException e) {
            Log.e(TAG, "Failed to apply zoom ratio: " + e.getMessage());
        }
    }
    // -------------- Fix Ended for this method(applyZoomRatio)-----------

    // -------------- Fix Start for this method(performTapToFocus)-----------
    /**
     * Perform a tap-to-focus at normalized preview coordinates (0..1).
     * This method maps preview coordinates into sensor region space and issues AF
     * regions + trigger.
     */
    private void performTapToFocus(float nx, float ny) {
        Log.d(TAG, "performTapToFocus called with normalized coords: " + nx + ", " + ny);

        if (currentCameraCharacteristics == null || captureRequestBuilder == null || captureSession == null
                || cameraDevice == null) {
            Log.w(TAG, "Cannot perform tap-to-focus: camera components not ready");
            return;
        }

        // Metering regions require sensor coordinates. We'll map normalized preview
        // coords to - if available - active array size.
        Rect activeArray = currentCameraCharacteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
        if (activeArray == null) {
            Log.w(TAG, "Cannot perform tap-to-focus: active array size not available");
            return;
        }

        int x = activeArray.left + (int) (nx * activeArray.width());
        int y = activeArray.top + (int) (ny * activeArray.height());

        Log.d(TAG, "Mapped to sensor coords: " + x + ", " + y + " (active array: " + activeArray + ")");

        // Create a small region around the tap point
        int half = Math.max(10, Math.min(activeArray.width(), activeArray.height()) / 20);
        Rect area = new Rect(
                Math.max(activeArray.left, x - half),
                Math.max(activeArray.top, y - half),
                Math.min(activeArray.right, x + half),
                Math.min(activeArray.bottom, y + half));

        MeteringRectangle mr = new MeteringRectangle(area, MeteringRectangle.METERING_WEIGHT_MAX - 1);
        try {
            // Store the current AF mode to restore it later
            Integer currentAfMode = captureRequestBuilder.get(CaptureRequest.CONTROL_AF_MODE);

            // Set focus and metering regions
            captureRequestBuilder.set(CaptureRequest.CONTROL_AF_REGIONS, new MeteringRectangle[] { mr });
            captureRequestBuilder.set(CaptureRequest.CONTROL_AE_REGIONS, new MeteringRectangle[] { mr });

            // Always switch to AUTO mode for tap-to-focus, regardless of current mode
            captureRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO);
            captureRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START);

            Log.d(TAG, "Tap-to-focus triggered at normalized coords: " + nx + ", " + ny + " -> sensor coords: " + x
                    + ", " + y);

            captureSession.capture(captureRequestBuilder.build(), null, backgroundHandler);

            // After a short delay, restore the previous AF mode (or use runtime/saved
            // preferences)
            if (backgroundHandler != null) {
                backgroundHandler.postDelayed(() -> {
                    try {
                        // Restore AF mode: use runtime value if available, otherwise saved preference,
                        // otherwise continuous
                        int afModeToRestore = (runtimeAfMode != null) ? runtimeAfMode
                                : (currentAfMode != null) ? currentAfMode
                                        : CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO;

                        // Verify the mode is supported
                        int[] supportedModes = currentCameraCharacteristics
                                .get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES);
                        boolean isSupported = false;
                        if (supportedModes != null) {
                            for (int mode : supportedModes) {
                                if (mode == afModeToRestore) {
                                    isSupported = true;
                                    break;
                                }
                            }
                        }

                        if (isSupported) {
                            captureRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, afModeToRestore);
                            captureRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER,
                                    CameraMetadata.CONTROL_AF_TRIGGER_IDLE);
                            captureSession.setRepeatingRequest(captureRequestBuilder.build(), null, backgroundHandler);
                            Log.d(TAG, "Restored AF mode to: " + afModeToRestore + " after tap-to-focus");
                        }
                    } catch (Exception e) {
                        Log.w(TAG, "Error restoring AF mode after tap-to-focus: " + e.getMessage());
                    }
                }, 1000); // 1 second delay to allow focus to complete
            }
        } catch (CameraAccessException | IllegalStateException e) {
            // ignore
        }
    }
    // -------------- Fix Ended for this method(performTapToFocus)-----------

    // --- onStartCommand (Ensure START action ignores processing state) ---

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
        // ----- Fix Start: Handle global app background/foreground actions -----
        if ("ACTION_APP_BACKGROUND".equals(action)) {
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
                    // Recreate pipeline and surfaces (minimal, actual re-init logic may be more
                    // complex)
                    // You may want to trigger the same logic as when starting recording
                    // For now, just log and rely on UI/fragment to trigger full re-init
                    Log.d(TAG, "App foregrounded and recording in progress, pipeline will be re-initialized by UI");
                }
            }
            return START_STICKY;
        }
        // ----- Fix End: Handle global app background/foreground actions -----
        if (Constants.INTENT_ACTION_START_RECORDING.equals(action)) {
            // ----- Check for camera resource cooldown -----
            // Check if camera resources are still being released
            if (isCameraResourceReleasing) {
                Log.w(TAG, "START_RECORDING rejected - camera resources still being released");
                // Show toast on UI thread
                mainHandler.post(() -> {
                    Toast.makeText(getApplicationContext(),
                            R.string.camera_resources_cooldown,
                            Toast.LENGTH_LONG).show();
                });
                // Don't stop the service yet as FFmpeg might still be running
                return START_STICKY;
            }

            Log.i(TAG, "Handling START_RECORDING intent. Service recording state is " + recordingState);

            // Reset recording state if it's somehow corrupted
            if (recordingState != RecordingState.NONE && cameraDevice == null) {
                Log.w(TAG,
                        "Recording state inconsistency detected. Resetting state from " + recordingState + " to NONE.");
                recordingState = RecordingState.NONE;
                sharedPreferencesManager.setRecordingInProgress(false);
            }

            // Only proceed if we're in NONE state
            if (recordingState == RecordingState.NONE) {
                // Update the UI and Service state atomically
                recordingState = RecordingState.STARTING;
                sharedPreferencesManager.setRecordingInProgress(true);

                // Set initial torch state
                isRecordingTorchEnabled = intent.getBooleanExtra(Constants.INTENT_EXTRA_INITIAL_TORCH_STATE, false);
                Log.d(TAG, "Initial torch state for recording session: " + isRecordingTorchEnabled);

                // Set up preview surface if provided
                setupSurfaceTexture(intent);

                // -------------- Fix Start: Defer start until preview surface ready on first
                // run -----------
                try {
                    boolean previewEnabled = sharedPreferencesManager != null
                            && sharedPreferencesManager.isPreviewEnabled();
                    boolean hasValidPreview = (previewSurface != null && previewSurface.isValid());
                    waitForPreviewBeforeStart = previewEnabled && !hasValidPreview;
                    Log.d(TAG, "Preview enabled=" + previewEnabled + ", validSurface=" + hasValidPreview +
                            ", waitForPreviewBeforeStart=" + waitForPreviewBeforeStart);

                    if (waitForPreviewBeforeStart) {
                        // Install a short timeout to avoid getting stuck if preview never arrives
                        if (previewWaitTimeoutRunnable != null) {
                            try {
                                mainHandler.removeCallbacks(previewWaitTimeoutRunnable);
                            } catch (Throwable ignore) {
                            }
                        }
                        previewWaitTimeoutRunnable = () -> {
                            if (recordingState == RecordingState.STARTING) {
                                Log.w(TAG,
                                        "Preview wait timeout reached; proceeding without preview to start recording safely");
                                waitForPreviewBeforeStart = false;
                                attemptStartRecordingIfReady();
                            }
                        };
                        // Give TextureView a moment to initialize on cold start
                        mainHandler.postDelayed(previewWaitTimeoutRunnable, 1500);
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Error evaluating preview-wait condition; proceeding without wait", e);
                    waitForPreviewBeforeStart = false;
                }
                // -------------- Fix Ended: Defer start until preview surface ready on first
                // run -----------

                // Start foreground service
                setupRecordingInProgressNotification();

                // Begin camera/recording setup
                if (cameraDevice == null) {
                    pendingStartRecording = true;
                    Log.d(TAG, "Setting pendingStartRecording=true, will start recording after camera opens");
                    openCamera();
                } else {
                    Log.d(TAG, "Camera already open, attempting gated start (may wait for preview)");
                    attemptStartRecordingIfReady();
                }

                // Notify UI that we're starting
                broadcastOnRecordingStarted();

                return START_STICKY;
            } else {
                // If we're not in NONE state, log a warning and notify the user
                Log.w(TAG, "Cannot start recording, already in state: " + recordingState);
                Toast.makeText(this, getString(R.string.recording_already_active), Toast.LENGTH_SHORT).show();
                return START_STICKY;
            }
        } else if (Constants.INTENT_ACTION_STOP_RECORDING.equals(action)) {
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
                // Only update the preview surface, never re-initialize or re-prepare the
                // pipeline
                glRecordingPipeline.setPreviewSurface(previewSurface);
            }
            // -------------- Fix Start: Avoid reconfiguring camera session on preview
            // surface change when using GL path -----------
            // If we're still in STARTING and were waiting for preview, attempt to start now
            if (recordingState == RecordingState.STARTING && waitForPreviewBeforeStart && previewSurface != null
                    && previewSurface.isValid()) {
                Log.d(TAG, "Preview surface became ready during STARTING; attempting gated start now");
                attemptStartRecordingIfReady();
            }
            // Do NOT recreate camera session here for GL-based recording, as preview is
            // rendered via EGL in renderer
            // Reconfiguration during active recording may cause driver instability on first
            // run
            if (glRecordingPipeline == null && (isRecording() || isPaused())) {
                // Only reconfigure if we're not on GL path (legacy/fallback)
                createCameraPreviewSession();
            }
            // -------------- Fix Ended: Avoid reconfiguring camera session on preview
            // surface change when using GL path -----------
            Log.d(TAG,
                    "ACTION_CHANGE_SURFACE handled: preview surface updated, camera session reconfigured if needed. No pipeline re-init.");
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
        } else if (Constants.INTENT_ACTION_SET_EXPOSURE_COMPENSATION.equals(action)) {
            // Set exposure compensation index while recording
            if (intent.hasExtra(Constants.EXTRA_EXPOSURE_COMPENSATION)) {
                int ev = intent.getIntExtra(Constants.EXTRA_EXPOSURE_COMPENSATION, 0);
                applyExposureCompensation(ev);
            }
            return START_STICKY;
        } else if (Constants.INTENT_ACTION_TOGGLE_AE_LOCK.equals(action)) {
            boolean lock = intent.getBooleanExtra(Constants.EXTRA_AE_LOCK, false);
            applyAeLock(lock);
            return START_STICKY;
        } else if (Constants.INTENT_ACTION_SET_AF_MODE.equals(action)) {
            if (intent.hasExtra(Constants.EXTRA_AF_MODE)) {
                int afMode = intent.getIntExtra(Constants.EXTRA_AF_MODE,
                        CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO);
                applyAfMode(afMode);
            }
            return START_STICKY;
        } else if (Constants.INTENT_ACTION_TAP_TO_FOCUS.equals(action)) {
            Log.d(TAG, "Received TAP_TO_FOCUS intent");
            if (intent.hasExtra(Constants.EXTRA_FOCUS_X) && intent.hasExtra(Constants.EXTRA_FOCUS_Y)) {
                float nx = intent.getFloatExtra(Constants.EXTRA_FOCUS_X, 0.5f);
                float ny = intent.getFloatExtra(Constants.EXTRA_FOCUS_Y, 0.5f);
                Log.d(TAG, "TAP_TO_FOCUS intent has coordinates: " + nx + ", " + ny);
                performTapToFocus(nx, ny);
            } else {
                Log.w(TAG, "TAP_TO_FOCUS intent missing coordinates");
            }
            return START_STICKY;
        } else if (Constants.INTENT_ACTION_SET_ZOOM_RATIO.equals(action)) {
            if (intent.hasExtra(Constants.EXTRA_ZOOM_RATIO)) {
                float zoomRatio = intent.getFloatExtra(Constants.EXTRA_ZOOM_RATIO, 1.0f);
                applyZoomRatio(zoomRatio);
            }
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
            // If intent explicitly specifies the embed_location value, use it to force
            // override the preference
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
            if (!isWorkingInProgress())
                stopSelf();
            return START_NOT_STICKY;
        }
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null; // Not a bound service
    }

    @Override
    public void onDestroy() {
        Log.d(TAG, "onDestroy: Service being destroyed...");

        // Stop any active reconnection attempts
        stopReconnectionAttempts();

        // Make sure dummy surface is released
        releaseDummyBackgroundSurface();

        // Ensure all location services are properly stopped
        if (geotagHelper != null) {
            try {
                geotagHelper.stopUpdates();
                Log.d(TAG, "GeotagHelper: Stopped location updates");
            } catch (Exception e) {
                Log.e(TAG, "Error stopping GeotagHelper updates", e);
            }
        }

        if (locationHelper != null) {
            try {
                locationHelper.stopLocationUpdates();
                Log.d(TAG, "LocationHelper: Stopped location updates for watermarking");
            } catch (Exception e) {
                Log.e(TAG, "Error stopping LocationHelper updates", e);
            }
        }

        // Force recording to stop if somehow it's still active
        if (recordingState != RecordingState.NONE) {
            Log.w(TAG, "Service being destroyed while recording is active. Forcing stop.");
            try {
                stopRecording();
            } catch (Exception e) {
                Log.e(TAG, "Error stopping recording during service destruction", e);
            }
        }

        // Clean up camera resources
        try {
            releaseRecordingResources();
        } catch (Exception e) {
            Log.e(TAG, "Error releasing resources during service destruction", e);
        }

        // Release wake lock if still held
        if (recordingWakeLock != null && recordingWakeLock.isHeld()) {
            try {
                recordingWakeLock.release();
                Log.d(TAG, "Recording WakeLock released during service destruction.");
            } catch (Exception e) {
                Log.e(TAG, "Error releasing wake lock", e);
            }
        }

        Log.d(TAG, "Service destroyed.");
        // Clean up GeotagHelper when service is destroyed
        super.onDestroy();
    }
    // --- End Lifecycle Methods ---

    // --- Core Recording Logic ---
    private void stopRecording() {
        if (isStopping) {
            Log.w(TAG, "stopRecording: Already in stopping process, ignoring duplicate call");
            return;
        }

        isStopping = true;
        Log.i(TAG, ">> stopRecording sequence initiated. Current state: " + recordingState);

        // ----- Fix Start for this method(stopRecording)-----
        // Stop black frame rendering if active
        stopBlackFrameRendering();

        // Stop reconnection attempts if active
        stopReconnectionAttempts();
        // ----- Fix Ended for this method(stopRecording)-----

        if (recordingState == RecordingState.NONE) {
            Log.d(TAG, "stopRecording called but state is already NONE, just cleaning up");
            sharedPreferencesManager.setRecordingInProgress(false);
            if (!isWorkingInProgress())
                stopSelf();
            if (recordingWakeLock != null && recordingWakeLock.isHeld())
                recordingWakeLock.release();
            // ----- Fix Start: Close SAF ParcelFileDescriptor if open -----
            if (safRecordingPfd != null) {
                try {
                    safRecordingPfd.close();
                    Log.d(TAG, "Closed SAF ParcelFileDescriptor after recording");
                } catch (Exception e) {
                    Log.e(TAG, "Error closing SAF ParcelFileDescriptor", e);
                }
                safRecordingPfd = null;
            }
            // ----- Fix End: Close SAF ParcelFileDescriptor if open -----
            isStopping = false; // Reset stopping flag if we're already stopped
            return;
        }

        // First update the state to prevent any new operations
        recordingState = RecordingState.NONE;
        sharedPreferencesManager.setRecordingInProgress(false);

        // Stop foreground service and cancel notification early to improve
        // responsiveness
        stopForeground(true);
        cancelNotification();

        // Use a background thread for resource cleanup to avoid blocking the main
        // thread
        new Thread(() -> {
            try {
                // Set camera resources as releasing and broadcast early
                setCameraResourcesReleasing(true);
                broadcastOnRecordingStopped();

                // First stop the capture session
                if (captureSession != null) {
                    try {
                        Log.d(TAG, "Stopping repeating request and closing capture session");
                        captureSession.stopRepeating();
                        captureSession.close();
                    } catch (Exception e) {
                        Log.e(TAG, "Error closing capture session", e);
                    } finally {
                        captureSession = null;
                    }
                }

                // Give some time for the session to close
                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }

                // Stop and release the GL pipeline
                if (glRecordingPipeline != null) {
                    try {
                        Log.d(TAG, "Stopping GLRecordingPipeline");
                        glRecordingPipeline.stopRecording();
                    } catch (Exception e) {
                        Log.e(TAG, "Error stopping GLRecordingPipeline", e);
                    } finally {
                        glRecordingPipeline = null;
                    }
                }

                // Give some time for the GL pipeline to release resources
                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }

                // Close the camera device last
                if (cameraDevice != null) {
                    try {
                        Log.d(TAG, "Closing camera device");
                        cameraDevice.close();
                    } catch (Exception e) {
                        Log.e(TAG, "Error closing camera device", e);
                    } finally {
                        cameraDevice = null;
                        isCameraOpen = false;
                    }
                }

                // Final cleanup on the main thread
                mainHandler.post(() -> {
                    // Release wake lock if held
                    if (recordingWakeLock != null && recordingWakeLock.isHeld()) {
                        try {
                            recordingWakeLock.release();
                            Log.d(TAG, "Recording wake lock released");
                        } catch (Exception e) {
                            Log.e(TAG, "Error releasing wake lock", e);
                        }
                    }

                    // ----- Fix Start: Close SAF ParcelFileDescriptor if open (background thread)
                    // -----
                    if (safRecordingPfd != null) {
                        try {
                            safRecordingPfd.close();
                            Log.d(TAG, "Closed SAF ParcelFileDescriptor after recording (background thread)");
                        } catch (Exception e) {
                            Log.e(TAG, "Error closing SAF ParcelFileDescriptor (background thread)", e);
                        }
                        safRecordingPfd = null;
                    }
                    // ----- Fix End: Close SAF ParcelFileDescriptor if open (background thread)
                    // -----
                    // Check if service can stop
                    checkIfServiceCanStop();

                    // Reset stopping flag
                    isStopping = false;

                    // Clear any pending recording start flag
                    pendingStartRecording = false;

                    // -------------- Fix Start (broadcast recording complete) -----------
                    // Send broadcast to notify that recording is complete so RecordsFragment can
                    // refresh
                    try {
                        Intent recordingCompleteIntent = new Intent(Constants.ACTION_RECORDING_COMPLETE);
                        recordingCompleteIntent.putExtra(Constants.EXTRA_RECORDING_SUCCESS, true);
                        // Note: We don't have the specific URI here, but RecordsFragment will do a full
                        // refresh
                        sendBroadcast(recordingCompleteIntent);
                        Log.d(TAG, "Broadcasted ACTION_RECORDING_COMPLETE for list refresh");
                    } catch (Exception e) {
                        Log.e(TAG, "Error broadcasting recording complete", e);
                    }
                    // -------------- Fix End (broadcast recording complete) -----------

                    Log.d(TAG, "stopRecording sequence completed successfully");
                });
            } catch (Exception e) {
                Log.e(TAG, "Error in stopRecording cleanup thread", e);
                mainHandler.post(() -> {
                    isStopping = false;
                    pendingStartRecording = false;

                    // -------------- Fix Start (broadcast recording complete on error) -----------
                    // Send broadcast even on error so RecordsFragment can refresh and clear any
                    // temp states
                    try {
                        Intent recordingCompleteIntent = new Intent(Constants.ACTION_RECORDING_COMPLETE);
                        recordingCompleteIntent.putExtra(Constants.EXTRA_RECORDING_SUCCESS, false);
                        sendBroadcast(recordingCompleteIntent);
                        Log.d(TAG, "Broadcasted ACTION_RECORDING_COMPLETE (error case) for list refresh");
                    } catch (Exception broadcastError) {
                        Log.e(TAG, "Error broadcasting recording complete on error", broadcastError);
                    }
                    // -------------- Fix End (broadcast recording complete on error) -----------
                });
            }
        }, "RecordingStopThread").start();
    }

    private void pauseRecording() {
        if (recordingState != RecordingState.IN_PROGRESS)
            return;
        if (glRecordingPipeline != null)
            glRecordingPipeline.pauseRecording(); // if supported
        recordingState = RecordingState.PAUSED;
        sharedPreferencesManager.setRecordingInProgress(false);
        setupRecordingResumeNotification();
        showRecordingInPausedToast();
        broadcastOnRecordingPaused();
    }

    private void resumeRecording() {
        if (recordingState != RecordingState.PAUSED)
            return;
        if (glRecordingPipeline != null)
            glRecordingPipeline.resumeRecording(); // if supported
        recordingState = RecordingState.IN_PROGRESS;
        sharedPreferencesManager.setRecordingInProgress(true);
        setupRecordingInProgressNotification();
        showRecordingResumedToast();
        broadcastOnRecordingResumed();
    }

    private void releaseRecordingResources() {
        if (isStopping)
            return;

        // Release dummy background surface first
        releaseDummyBackgroundSurface();

        isStopping = true;
        try {
            if (captureSession != null) {
                captureSession.close();
            }
        } catch (Exception e) {
        } finally {
            captureSession = null;
        }
        try {
            if (cameraDevice != null) {
                cameraDevice.close();
            }
        } catch (Exception e) {
        } finally {
            cameraDevice = null;
        }
        if (glRecordingPipeline != null) {
            glRecordingPipeline.stopRecording();
            glRecordingPipeline = null;
        }
        // ----- Fix Start: Close SAF ParcelFileDescriptor if open (resource cleanup)
        // -----
        if (safRecordingPfd != null) {
            try {
                safRecordingPfd.close();
                Log.d(TAG, "Closed SAF ParcelFileDescriptor after resource cleanup");
            } catch (Exception e) {
                Log.e(TAG, "Error closing SAF ParcelFileDescriptor (resource cleanup)", e);
            }
            safRecordingPfd = null;
        }
        // ----- Fix End: Close SAF ParcelFileDescriptor if open (resource cleanup)
        // -----
        recordingState = RecordingState.NONE;
        sharedPreferencesManager.setRecordingInProgress(false);
    }

    /**
     * Checks if the service has any active work (recording, pausing, or processing)
     * and calls stopSelf() if it is completely idle.
     * This should be called whenever a task completes (recording stops, processing
     * finishes).
     */
    private void checkIfServiceCanStop() {
        // Read volatile flag and check state atomically as best as possible

        Log.d(TAG, "checkIfServiceCanStop: RecordingState=" + recordingState + ", FfmpegTasks="
                + ffmpegProcessingTaskCount.get());

        // Use the new shouldServiceStayAlive method to determine if service should
        // continue running
        if (!shouldServiceStayAlive()) {
            Log.i(TAG, "No active recording or background processing detected. Stopping service.");
            // Remove from foreground first to avoid ANR if stopSelf takes time
            stopForeground(true);
            stopSelf(); // Stop the service as its work is done.
        } else {
            Log.d(TAG, "Service continues running (Tasks active).");
        }

    }

    private void openCamera() {
        Log.d(TAG, "openCamera: Opening camera");
        if (cameraManager == null) {
            Log.e(TAG, "openCamera: CameraManager (class field) is null.");
            if (recordingState == RecordingState.STARTING)
                recordingState = RecordingState.NONE;
            stopSelf();
            return;
        }
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "openCamera: Camera permission denied.");
            if (recordingState == RecordingState.STARTING)
                recordingState = RecordingState.NONE;
            Toast.makeText(this, "Camera permission denied for service", Toast.LENGTH_LONG).show();
            stopSelf();
            return;
        }

        CameraType selectedType = sharedPreferencesManager.getCameraSelection();
        String cameraToOpenId = null;

        try {
            String[] basicCameraIds = cameraManager.getCameraIdList();
            Set<String> allAvailableCameraIds = new HashSet<>(Arrays.asList(basicCameraIds));

            // On Android P+, also include physical cameras from logical cameras
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                for (String id : basicCameraIds) {
                    try {
                        CameraCharacteristics chars = cameraManager.getCameraCharacteristics(id);
                        Set<String> physicalIds = chars.getPhysicalCameraIds();
                        if (physicalIds != null && !physicalIds.isEmpty()) {
                            allAvailableCameraIds.addAll(physicalIds);
                            Log.d(TAG, "Added physical camera IDs from logical camera " + id + ": " + physicalIds);
                        }
                    } catch (Exception e) {
                        Log.w(TAG, "Error checking physical IDs for camera " + id, e);
                    }
                }
            }

            String[] availableCameraIds = allAvailableCameraIds.toArray(new String[0]);
            Log.d(TAG, "Available Camera IDs (including physical): " + Arrays.toString(availableCameraIds));

            if (selectedType == CameraType.FRONT) {
                for (String id : availableCameraIds) {
                    CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(id);
                    Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
                    if (facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT) {
                        cameraToOpenId = id;
                        Log.d(TAG, "Found FRONT camera: ID=" + cameraToOpenId);
                        break;
                    }
                }
            } else { // CameraType.BACK
                String preferredBackId = sharedPreferencesManager.getSelectedBackCameraId();
                Log.d(TAG, "Preferred BACK camera ID from prefs: " + preferredBackId);
                boolean isValidAndAvailable = false;

                // First, check if the preferred camera ID exists in our available cameras
                for (String id : availableCameraIds) {
                    if (id.equals(preferredBackId)) {
                        try {
                            CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(id);
                            Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);

                            // For physical cameras, they might not have LENS_FACING_BACK set
                            // but if they're in our availableCameraIds and were detected as back cameras
                            // in SettingsFragment, we should trust that they're valid back cameras
                            if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) {
                                isValidAndAvailable = true;
                                Log.d(TAG, "Preferred back camera ID '" + preferredBackId
                                        + "' validated with LENS_FACING_BACK");
                                break;
                            } else {
                                // For physical cameras, check if this ID was part of a logical back camera
                                // If it's in our availableCameraIds, it means it was detected as a back camera
                                Log.w(TAG, "Preferred back ID " + preferredBackId + " exists but LENS_FACING is: "
                                        + facing);

                                // Additional validation: check if this is a physical camera from a logical back
                                // camera
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                                    boolean isPhysicalBackCamera = isPhysicalBackCamera(preferredBackId, basicCameraIds,
                                            cameraManager);
                                    if (isPhysicalBackCamera) {
                                        isValidAndAvailable = true;
                                        Log.d(TAG, "Preferred camera ID '" + preferredBackId
                                                + "' validated as physical back camera");
                                        break;
                                    }
                                }
                            }
                        } catch (CameraAccessException e) {
                            Log.e(TAG, "Error getting characteristics for preferred camera " + preferredBackId, e);
                        }
                    }
                }

                if (isValidAndAvailable) {
                    cameraToOpenId = preferredBackId;
                    Log.d(TAG, "Using preferred BACK camera ID: " + cameraToOpenId);
                } else {
                    Log.w(TAG,
                            "Preferred back camera ID '" + preferredBackId
                                    + "' is invalid or unavailable. Falling back to default ID '"
                                    + Constants.DEFAULT_BACK_CAMERA_ID + "'.");
                    cameraToOpenId = Constants.DEFAULT_BACK_CAMERA_ID;
                    boolean defaultExistsAndIsBack = false;
                    for (String id : availableCameraIds) {
                        if (id.equals(cameraToOpenId)) {
                            CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(id);
                            Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
                            if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) {
                                defaultExistsAndIsBack = true;
                            }
                            break;
                        }
                    }
                    if (!defaultExistsAndIsBack) {
                        Log.e(TAG, "Critical: Default back camera ID '" + cameraToOpenId
                                + "' not found or not back-facing! Cannot select default back camera.");
                        String fallbackBackId = null;
                        for (String id : availableCameraIds) {
                            CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(id);
                            Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
                            if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) {
                                Log.w(TAG, "Using first available back camera as final fallback: " + id);
                                fallbackBackId = id;
                                break;
                            }
                        }
                        if (fallbackBackId != null) {
                            cameraToOpenId = fallbackBackId;
                        } else {
                            cameraToOpenId = null;
                            Log.e(TAG, "Could not find any back-facing camera.");
                        }
                    }
                }
            }

            if (cameraToOpenId == null) {
                Log.e(TAG, "Could not determine a valid camera ID to open for selected type: " + selectedType);
                Toast.makeText(this, "Failed to find selected camera", Toast.LENGTH_LONG).show();
                if (recordingState == RecordingState.STARTING)
                    recordingState = RecordingState.NONE;
                stopSelf();
                return;
            }
            Log.i(TAG, "Attempting to open final Camera ID: " + cameraToOpenId);

            if (cameraDevice != null) {
                Log.w(TAG, "openCamera: Closing existing cameraDevice instance first.");
                try {
                    cameraDevice.close();
                } catch (Exception e) {
                    Log.e(TAG, "Error explicitly closing existing cameraDevice prior to opening new one", e);
                }
                cameraDevice = null;
            }

            final int MAX_RETRIES = 3;
            final long RETRY_DELAY_MS = 2000;
            int attempt = 0;
            String finalCameraToOpenId = cameraToOpenId;

            while (attempt < MAX_RETRIES) {
                try {
                    Log.i(TAG, "Opening camera " + finalCameraToOpenId + ", attempt " + (attempt + 1));
                    cameraManager.openCamera(finalCameraToOpenId, cameraStateCallback, backgroundHandler);
                    return;
                } catch (CameraAccessException e) {
                    Log.e(TAG, "openCamera: Camera Access Exception on attempt " + (attempt + 1) + " for ID "
                            + finalCameraToOpenId, e);
                    // Use direct integer values for error codes for broader compatibility
                    int reason = e.getReason();
                    if (reason == 1 /* ERROR_CAMERA_DISABLED */ ||
                            reason == 4 /* ERROR_CAMERA_IN_USE */) {
                        attempt++;
                        if (attempt < MAX_RETRIES) {
                            Log.w(TAG, "Camera disabled (1) or in use (4), reason: " + reason + ". Retrying in "
                                    + RETRY_DELAY_MS + "ms... (" + attempt + "/" + MAX_RETRIES + ")");
                            try {
                                Thread.sleep(RETRY_DELAY_MS);
                            } catch (InterruptedException ie) {
                                Log.w(TAG, "Camera open retry delay interrupted", ie);
                                Thread.currentThread().interrupt();
                                if (recordingState == RecordingState.STARTING)
                                    recordingState = RecordingState.NONE;
                                stopSelf();
                                return;
                            }
                        } else {
                            Log.e(TAG, "Max retries reached for camera " + finalCameraToOpenId + ". Giving up. Reason: "
                                    + reason);
                            if (recordingState == RecordingState.STARTING)
                                recordingState = RecordingState.NONE;
                            Toast.makeText(this, "Camera repeatedly unavailable (Reason: " + reason + "). Stopping.",
                                    Toast.LENGTH_LONG).show();
                            stopSelf();
                            return;
                        }
                    } else {
                        Log.e(TAG, "Unrecoverable CameraAccessException (Reason: " + reason + "). Not retrying.", e);
                        if (recordingState == RecordingState.STARTING)
                            recordingState = RecordingState.NONE;
                        Toast.makeText(this, "Camera access error: " + reason, Toast.LENGTH_LONG).show();
                        stopSelf();
                        return;
                    }
                } catch (IllegalArgumentException e) {
                    Log.e(TAG, "openCamera: Illegal Argument Exception (likely invalid camera ID '"
                            + finalCameraToOpenId + "'). Attempt: " + (attempt + 1), e);
                    if (recordingState == RecordingState.STARTING)
                        recordingState = RecordingState.NONE;
                    Toast.makeText(this, "Invalid camera configuration.", Toast.LENGTH_LONG).show();
                    stopSelf();
                    return;
                } catch (Exception e) {
                    Log.e(TAG, "openCamera: Unexpected error on attempt " + (attempt + 1) + " for ID "
                            + finalCameraToOpenId, e);
                    if (recordingState == RecordingState.STARTING)
                        recordingState = RecordingState.NONE;
                    Toast.makeText(this, "Unexpected camera error.", Toast.LENGTH_LONG).show();
                    stopSelf();
                    return;
                }
            }
        } catch (CameraAccessException e) {
            Log.e(TAG, "openCamera: Initial Camera Access Exception (listing/characteristics)", e);
            if (recordingState == RecordingState.STARTING)
                recordingState = RecordingState.NONE;
            Toast.makeText(this, "Failed to access camera details.", Toast.LENGTH_LONG).show();
            stopSelf();
        } catch (IllegalArgumentException e) {
            Log.e(TAG, "openCamera: Outer Illegal Argument Exception (likely invalid camera ID '" + cameraToOpenId
                    + "' for characteristics)", e);
            if (recordingState == RecordingState.STARTING)
                recordingState = RecordingState.NONE;
            Toast.makeText(this, "Invalid camera setup.", Toast.LENGTH_LONG).show();
            stopSelf();
        } catch (Exception e) {
            Log.e(TAG, "openCamera: Unexpected outer error", e);
            if (recordingState == RecordingState.STARTING)
                recordingState = RecordingState.NONE;
            Toast.makeText(this, "Critical camera system error.", Toast.LENGTH_LONG).show();
            stopSelf();
        }
    }

    private final CameraDevice.StateCallback cameraStateCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(@NonNull CameraDevice camera) {
            Log.d(TAG, "Camera device opened successfully");
            cameraDevice = camera;
            isCameraOpen = true;

            // Check if we're waiting for camera to resume normal recording
            if (recordingState == RecordingState.WAITING_FOR_CAMERA && pendingCameraReconnect) {
                Log.d(TAG, "Camera reconnected after interruption, resuming normal recording");
                pendingCameraReconnect = false;
                stopReconnectionAttempts();

                // First pause any ongoing work with black frames
                final boolean wasRenderingBlackFrames = isRenderingBlackFrames;

                // Use a longer delay between stopping black frames and starting camera session
                // to ensure all GL resources are properly cleaned up
                mainHandler.post(() -> {
                    try {
                        // First, stop black frame rendering to free up GL resources
                        if (wasRenderingBlackFrames) {
                            stopBlackFrameRendering();

                            // Add a delay to ensure cleanup is complete
                            Log.d(TAG, "Waiting for black frame renderer cleanup before reconnecting camera");

                            // Resume camera session after delay
                            mainHandler.postDelayed(() -> resumeCameraAfterReconnection(), 1500);
                        } else {
                            // If we weren't rendering black frames, proceed immediately
                            resumeCameraAfterReconnection();
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Error during camera reconnection sequence", e);
                        // Fall back to black frame rendering
                        recordingState = RecordingState.WAITING_FOR_CAMERA;
                        startBlackFrameRendering();
                    }
                });
                return;
            }

            // Check if we have a pending recording start request
            if (pendingStartRecording) {
                Log.d(TAG, "Found pendingStartRecording=true; attempting gated start (may wait for preview)");
                pendingStartRecording = false; // Reset the flag
                mainHandler.post(RecordingService.this::attemptStartRecordingIfReady);
            } else {
                Log.d(TAG, "Camera opened but no pending recording start");
                // If we're not starting recording, create a preview session
                if (previewSurface != null) {
                    try {
                        createCameraPreviewSession();
                    } catch (Exception e) {
                        Log.e(TAG, "Error creating preview session", e);
                    }
                }
            }
            // ----- Fix Ended for this method(onOpened)-----
        }

        @Override
        public void onDisconnected(@NonNull CameraDevice camera) {
            // ----- Fix Start for this method(onDisconnected)-----
            Log.d(TAG, "Camera device disconnected");

            // Store camera state before closing
            boolean wasRecording = (recordingState == RecordingState.IN_PROGRESS);

            // Close camera device
            if (cameraDevice != null) {
                cameraDevice.close();
                cameraDevice = null;
            }
            isCameraOpen = false;

            // Reset the pending flag if camera disconnected
            if (pendingStartRecording) {
                Log.w(TAG, "Camera disconnected while pendingStartRecording=true, resetting flag");
                pendingStartRecording = false;
            }

            // If we were recording, switch to black frame mode
            if (wasRecording) {
                Log.w(TAG, "Camera disconnected during recording, switching to black frame mode");

                // Use a small delay to avoid race conditions with camera state changes
                mainHandler.postDelayed(() -> {
                    handleCameraInterruption();
                }, 300);
            } else if (recordingState != RecordingState.NONE && recordingState != RecordingState.WAITING_FOR_CAMERA) {
                // For other states like PAUSED, just stop recording
                Log.w(TAG, "Camera disconnected while in state " + recordingState + ", stopping recording");
                stopRecording();
            }
            // ----- Fix Ended for this method(onDisconnected)-----
        }

        @Override
        public void onError(@NonNull CameraDevice camera, int error) {
            // ----- Fix Start for this method(onError)-----
            Log.e(TAG, "Camera device error: " + error);

            // Store camera state before closing
            boolean wasRecording = (recordingState == RecordingState.IN_PROGRESS);

            if (cameraDevice != null) {
                cameraDevice.close();
                cameraDevice = null;
            }
            isCameraOpen = false;

            // Reset the pending flag if camera error
            if (pendingStartRecording) {
                Log.w(TAG, "Camera error while pendingStartRecording=true, resetting flag");
                pendingStartRecording = false;
            }

            // If error is camera in use and we were recording, switch to black frame mode
            if ((error == CameraDevice.StateCallback.ERROR_CAMERA_IN_USE ||
                    error == CameraDevice.StateCallback.ERROR_MAX_CAMERAS_IN_USE) &&
                    wasRecording) {
                Log.w(TAG, "Camera in use error during recording, switching to black frame mode");

                // Use a small delay to avoid race conditions with camera state changes
                mainHandler.postDelayed(() -> {
                    handleCameraInterruption();
                }, 300);
            }
            // For other errors or states, stop recording
            else if (recordingState != RecordingState.NONE && recordingState != RecordingState.WAITING_FOR_CAMERA) {
                Log.w(TAG, "Camera error during recording, stopping recording");
                stopRecording();
            }

            // Show error to user
            String errorMsg;
            switch (error) {
                case CameraDevice.StateCallback.ERROR_CAMERA_IN_USE:
                    errorMsg = getString(R.string.camera_error_in_use);
                    break;
                case CameraDevice.StateCallback.ERROR_MAX_CAMERAS_IN_USE:
                    errorMsg = getString(R.string.camera_error_max_cameras);
                    break;
                case CameraDevice.StateCallback.ERROR_CAMERA_DISABLED:
                    errorMsg = getString(R.string.camera_error_disabled);
                    break;
                case CameraDevice.StateCallback.ERROR_CAMERA_DEVICE:
                    errorMsg = getString(R.string.camera_error_device);
                    break;
                case CameraDevice.StateCallback.ERROR_CAMERA_SERVICE:
                    errorMsg = getString(R.string.camera_error_service);
                    break;
                default:
                    errorMsg = getString(R.string.camera_error_unknown) + " (" + error + ")";
            }

            final String finalErrorMsg = errorMsg;
            // mainHandler.post(() -> Toast.makeText(getApplicationContext(), finalErrorMsg,
            // Toast.LENGTH_LONG).show());
            // ----- Fix Ended for this method(onError)-----
        }

        @Override
        public void onClosed(@NonNull CameraDevice camera) {
            Log.d(TAG, "Camera device closed");
            isCameraOpen = false;

            // If we were in segment rollover, continue with the rollover process
            if (isRolloverClosingOldSession) {
                Log.d(TAG, "Camera closed during segment rollover, proceeding with rollover");
                isRolloverClosingOldSession = false;
                proceedWithRolloverAfterOldSessionClosed();
            }

            // Reset the pending flag if camera closed
            if (pendingStartRecording) {
                Log.w(TAG, "Camera closed while pendingStartRecording=true, resetting flag");
                pendingStartRecording = false;
            }
        }
    };

    // -------------- Fix Start: Helper to attempt gated start respecting preview
    // wait -----------
    private void attemptStartRecordingIfReady() {
        try {
            if (recordingState != RecordingState.STARTING) {
                Log.d(TAG, "attemptStartRecordingIfReady: Not in STARTING state (" + recordingState + ")");
                return;
            }
            // Ensure camera is opened before starting
            if (cameraDevice == null) {
                Log.d(TAG, "attemptStartRecordingIfReady: Camera not opened yet; deferring until onOpened");
                pendingStartRecording = true;
                return;
            }
            if (waitForPreviewBeforeStart) {
                boolean ready = previewSurface != null && previewSurface.isValid();
                if (!ready) {
                    Log.d(TAG, "attemptStartRecordingIfReady: Waiting for preview surface to become valid...");
                    return;
                }
                Log.d(TAG, "attemptStartRecordingIfReady: Preview is now ready; proceeding");
                waitForPreviewBeforeStart = false;
            }
            // Clear timeout if set
            if (previewWaitTimeoutRunnable != null) {
                try {
                    mainHandler.removeCallbacks(previewWaitTimeoutRunnable);
                } catch (Throwable ignore) {
                }
                previewWaitTimeoutRunnable = null;
            }
            // Finally, start recording
            startRecording();
        } catch (Exception e) {
            Log.e(TAG, "attemptStartRecordingIfReady: error starting", e);
            stopRecording();
        }
    }
    // -------------- Fix Ended: Helper to attempt gated start respecting preview
    // wait -----------

    /**
     * Helper method to resume normal recording after camera reconnection.
     * This is called after any black frame rendering has been properly stopped.
     */
    private void resumeCameraAfterReconnection() {
        try {
            Log.d(TAG, "Resuming normal camera recording after reconnection");

            // Create camera session first
            createCameraPreviewSession();

            // Wait a moment for the session to be fully configured
            mainHandler.postDelayed(() -> {
                try {
                    // Update recording state
                    recordingState = RecordingState.IN_PROGRESS;

                    // Show notification about recording resumed
                    setupRecordingInProgressNotification();

                    // Show toast to user
                    // Toast.makeText(RecordingService.this,
                    // R.string.camera_reconnection_success,
                    // Toast.LENGTH_SHORT).show();

                    Log.i(TAG, "Normal recording resumed after camera reconnection");
                } catch (Exception e) {
                    Log.e(TAG, "Error finalizing camera reconnection", e);
                    // If we fail at this stage, fall back to black frames
                    if (recordingState == RecordingState.IN_PROGRESS) {
                        recordingState = RecordingState.WAITING_FOR_CAMERA;
                        startBlackFrameRendering();
                    }
                }
            }, 1000); // Longer delay to ensure session is ready
        } catch (Exception e) {
            Log.e(TAG, "Error creating camera session after reconnection", e);
            // Continue with black frames if we can't resume normal recording
            recordingState = RecordingState.WAITING_FOR_CAMERA;
            startBlackFrameRendering();
        }
    }

    private void createCameraPreviewSession() {
        if (cameraDevice == null) {
            Log.e(TAG, "createCameraPreviewSession: cameraDevice is null!");
            stopRecording(); // Cannot create session without camera
            return;
        }
        // If using OpenGL pipeline for watermarking
        if (glRecordingPipeline != null) {
            Log.d(TAG, "createCameraPreviewSession: Using GL pipeline for watermarking");
            Surface glSurface = glRecordingPipeline.getCameraInputSurface();
            List<Surface> surfaces = new ArrayList<>();
            if (glSurface != null) {
                surfaces.add(glSurface);
            } else {
                Log.e(TAG, "GL pipeline camera input surface is null!");
            }
            // Do NOT add previewSurface to Camera2 session outputs
            // if (previewSurface != null) {
            // surfaces.add(previewSurface);
            // } else {
            // Log.w(TAG, "Preview surface is null; preview will be disabled.");
            // }
            if (surfaces.isEmpty()) {
                Log.e(TAG, "No valid surfaces for camera session!");
                stopRecording();
                return;
            }
            // Get camera characteristics for frame rate handling
            CameraManager cameraManager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
            CameraType cameraType = sharedPreferencesManager.getCameraSelection();
            String cameraId = getCameraId(cameraManager, cameraType);
            CameraCharacteristics characteristics = null;
            if (cameraId != null) {
                try {
                    characteristics = cameraManager.getCameraCharacteristics(cameraId);
                } catch (Exception e) {
                    Log.e(TAG, "Error getting camera characteristics", e);
                }
            }
            // -------------- Fix Start for this
            // method(createCameraPreviewSession)-----------
            // Use per-camera FPS setting and only choose HSR if selected resolution
            // supports it
            int targetFrameRate = sharedPreferencesManager.getSpecificVideoFrameRate(cameraType);
            boolean isHighFrameRate = targetFrameRate >= 60;
            boolean useHighSpeedSession = false;
            Size selected = sharedPreferencesManager.getCameraResolution();

            if (isHighFrameRate && characteristics != null) {
                showFrameRateToast(targetFrameRate);

                if (DeviceHelper.isSamsung()) {
                    // Unify Samsung path with Pixel: always use standard session (no HSR).
                    Log.d(TAG, "Samsung device: forcing standard session for " + targetFrameRate
                            + "fps to match Pixel behavior");
                    useHighSpeedSession = false;
                } else if (HighSpeedCaptureHelper.isHighSpeedSupported(characteristics, targetFrameRate)) {
                    Size hs = HighSpeedCaptureHelper.getBestHighSpeedSize(characteristics, targetFrameRate,
                            selected.getWidth(), selected.getHeight());
                    if (hs != null && hs.getWidth() == selected.getWidth() && hs.getHeight() == selected.getHeight()) {
                        Log.d(TAG, "HSR supported at selected size for " + targetFrameRate + "fps");
                        useHighSpeedSession = true;
                    } else {
                        Log.d(TAG, "HSR supported but selected size incompatible; using standard session");
                        useHighSpeedSession = false;
                    }
                }
            }

            if (useHighSpeedSession) {
                currentCameraCharacteristics = characteristics;
                createHighSpeedSession(surfaces, characteristics, targetFrameRate, cameraType);
            } else {
                currentCameraCharacteristics = characteristics;
                createStandardSession(surfaces, targetFrameRate, characteristics, cameraType);
            }
            // -------------- Fix Ended for this
            // method(createCameraPreviewSession)-----------
            return;
        }

        Log.d(TAG, "createCameraPreviewSession: Creating session...");
        try {
            List<Surface> surfaces = new ArrayList<>();
            if (glRecordingPipeline != null) {
                surfaces.add(glRecordingPipeline.getCameraInputSurface());
            }
            if (previewSurface != null) {
                surfaces.add(previewSurface);
                previewSurfaceAdded = true;
                Log.d(TAG, "Using valid preview surface from UI");
            } else if (dummyBackgroundSurface != null && dummyBackgroundSurface.isValid()) {
                // Use dummy surface when UI surface is gone (app backgrounded)
                // to prevent recording issues like green frames
                surfaces.add(dummyBackgroundSurface);
                Log.d(TAG, "Using dummy surface (app backgrounded) to maintain stable recording");
                previewSurfaceAdded = true;
            } else {
                Log.d(TAG, "No valid preview or dummy surface available");
            }

            // Get camera characteristics for frame rate handling
            CameraManager cameraManager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
            CameraType cameraType = sharedPreferencesManager.getCameraSelection();
            String cameraId = getCameraId(cameraManager, cameraType);
            CameraCharacteristics characteristics = null;

            if (cameraId != null) {
                characteristics = cameraManager.getCameraCharacteristics(cameraId);
            }

            // -------------- Fix Start: Use per-camera FPS --------------
            // Get target frame rate from settings for specific camera
            int targetFrameRate = sharedPreferencesManager.getSpecificVideoFrameRate(cameraType);
            // -------------- Fix End: Use per-camera FPS --------------

            // Log device info once for debugging
            DeviceHelper.logDeviceInfo();

            // Check if we need to use high-speed session for 60fps+ recording
            boolean isHighFrameRate = targetFrameRate >= 60;
            boolean useHighSpeedSession = false;

            // Continue with existing code...
            // Rest of the method stays the same

            // For high frame rates, evaluate if we should use high-speed session
            if (isHighFrameRate && characteristics != null) {
                // Unify Samsung path with Pixel: never force HSR on Samsung
                if (DeviceHelper.isSamsung()) {
                    Log.d(TAG, "Samsung device: using standard session (no HSR) for " + targetFrameRate + "fps");
                    showFrameRateToast(targetFrameRate);
                    useHighSpeedSession = false;
                }
                // For other devices, check if high-speed is supported
                else if (HighSpeedCaptureHelper.isHighSpeedSupported(characteristics, targetFrameRate)) {
                    Size selected = sharedPreferencesManager.getCameraResolution();
                    Size hs = HighSpeedCaptureHelper.getBestHighSpeedSize(characteristics, targetFrameRate,
                            selected.getWidth(), selected.getHeight());
                    if (hs != null && hs.getWidth() == selected.getWidth() && hs.getHeight() == selected.getHeight()) {
                        Log.d(TAG, "High-speed session supported at selected size for " + targetFrameRate + "fps");
                        useHighSpeedSession = true;
                    } else {
                        Log.d(TAG, "HSR supported but not at selected size; using standard session");
                        useHighSpeedSession = false;
                    }

                    // Show toast informing the user about experimental 60fps
                    showFrameRateToast(targetFrameRate);
                } else {
                    Log.d(TAG, "High-speed not supported for " + targetFrameRate +
                            "fps, using standard session");

                    // Show toast informing the user that high frame rate may not be fully supported
                    showFrameRateToast(targetFrameRate);
                }
            }

            // Create the appropriate type of session
            if (useHighSpeedSession) {
                // For high-speed sessions
                currentCameraCharacteristics = characteristics;
                createHighSpeedSession(surfaces, characteristics, targetFrameRate, cameraType);
            } else {
                // Create a standard session with appropriate frame rate settings
                currentCameraCharacteristics = characteristics;
                createStandardSession(surfaces, targetFrameRate, characteristics, cameraType);
            }
        } catch (CameraAccessException e) {
            Log.e(TAG, "createCameraPreviewSession: Camera Access Exception", e);
            stopRecording();
        } catch (IllegalStateException e) {
            Log.e(TAG, "createCameraPreviewSession: IllegalStateException", e);
            stopRecording();
        } catch (Exception e) {
            Log.e(TAG, "createCameraPreviewSession: Exception", e);
            stopRecording();
        }
    }

    private final CameraCaptureSession.StateCallback captureSessionCallback = new CameraCaptureSession.StateCallback() {
        @Override
        public void onConfigured(@NonNull CameraCaptureSession session) {
            if (isStopping || cameraDevice == null || recordingState == RecordingState.NONE) {
                Log.w(TAG,
                        "onConfigured: Service is stopping, camera device is null, or recording state is NONE. Aborting.");
                return;
            }
            Log.d(TAG, "Standard capture session configured");
            captureSession = session;
            try {
                // Set auto control mode
                captureRequestBuilder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);

                // CRITICAL: Re-apply camera preferences in session callback to ensure they
                // stick
                // This ensures AE mode, exposure compensation, and other settings are not lost
                try {
                    applySavedCameraPrefsToBuilder(captureRequestBuilder);
                    Log.d(TAG, "Re-applied camera prefs in session callback");
                } catch (Exception e) {
                    Log.w(TAG, "Failed to re-apply camera prefs in callback: " + e.getMessage());
                }

                // Set torch/flash mode
                captureRequestBuilder.set(CaptureRequest.FLASH_MODE,
                        isRecordingTorchEnabled ? CaptureRequest.FLASH_MODE_TORCH : CaptureRequest.FLASH_MODE_OFF);
                // Defensive: check session state before using
                if (captureSession == null || cameraDevice == null || recordingState == RecordingState.NONE) {
                    Log.w(TAG,
                            "onConfigured: Session or cameraDevice became null before setRepeatingRequest. Aborting.");
                    return;
                }
                // Start repeating request
                try {
                    session.setRepeatingRequest(captureRequestBuilder.build(), null, backgroundHandler);
                    Log.d(TAG, "Started repeating request for standard session");
                } catch (IllegalStateException ise) {
                    Log.e(TAG, "setRepeatingRequest failed: session likely closed", ise);
                    stopRecording();
                    return;
                }
                // Start the GL pipeline only after session is configured and repeating request
                // is set
                if (glRecordingPipeline != null) {
                    glRecordingPipeline.startRecording();
                }
                // Handle recording state
                handleSessionConfigured();
            } catch (CameraAccessException e) {
                Log.e(TAG, "Error starting repeating request", e);
                stopRecording();
            }
        }

        @Override
        public void onConfigureFailed(@NonNull CameraCaptureSession session) {
            if (isStopping)
                return;
            Log.e(TAG, "Standard capture session configuration failed");
            stopRecording();
        }

        @Override
        public void onClosed(@NonNull CameraCaptureSession session) {
            if (isStopping)
                return;
            Log.d(TAG, "Capture session closed");

            if (session == captureSession) {
                captureSession = null;
            }

            if (isRolloverClosingOldSession) {
                Log.d(TAG, "Capture session closed as part of rollover");
                isRolloverClosingOldSession = false;
                proceedWithRolloverAfterOldSessionClosed();
            }
        }
    };

    /**
     * Callback for high-speed session state changes
     */
    private final CameraCaptureSession.StateCallback highSpeedSessionCallback = new CameraCaptureSession.StateCallback() {

        @Override
        public void onConfigured(@NonNull CameraCaptureSession session) {
            if (isStopping || cameraDevice == null) {
                Log.e(TAG, "onConfigured: Service is stopping or camera closed before high-speed session configured");
                return;
            }

            try {
                Log.d(TAG, "High-speed session configured successfully");
                captureSession = session;

                // For high-speed sessions, we need to create a list of requests for burst
                CameraConstrainedHighSpeedCaptureSession highSpeedSession = (CameraConstrainedHighSpeedCaptureSession) session;

                List<CaptureRequest> highSpeedRequests = highSpeedSession
                        .createHighSpeedRequestList(captureRequestBuilder.build());

                // Start repeating burst for high-speed recording
                highSpeedSession.setRepeatingBurst(highSpeedRequests, null, backgroundHandler);

                // Handle recording state
                handleSessionConfigured();
            } catch (CameraAccessException e) {
                Log.e(TAG, "Error setting up high-speed repeating burst", e);
                stopRecording();
            } catch (IllegalArgumentException | IllegalStateException e) {
                Log.e(TAG, "Error in high-speed session configuration", e);
                stopRecording();
            }
        }

        @Override
        public void onConfigureFailed(@NonNull CameraCaptureSession session) {
            if (isStopping)
                return;
            Log.e(TAG, "High-speed session configuration failed");

            try {
                // Can't get surfaces from the failed session, need to recreate them
                CameraManager cameraManager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
                CameraType cameraType = sharedPreferencesManager.getCameraSelection();
                String cameraId = getCameraId(cameraManager, cameraType);
                CameraCharacteristics characteristics = null;

                if (cameraId != null) {
                    characteristics = cameraManager.getCameraCharacteristics(cameraId);
                }
                // -------------- Fix Start: Use per-camera FPS and include GL surface in
                // fallback --------------
                int targetFrameRate = sharedPreferencesManager.getSpecificVideoFrameRate(cameraType);

                // Recreate surfaces for standard session
                List<Surface> surfaces = new ArrayList<>();
                if (glRecordingPipeline != null && glRecordingPipeline.getCameraInputSurface() != null) {
                    surfaces.add(glRecordingPipeline.getCameraInputSurface());
                }

                // Add preview surface if available
                if (previewSurface != null) {
                    surfaces.add(previewSurface);
                }

                // Create standard session as fallback
                if (!surfaces.isEmpty()) {
                    createStandardSession(surfaces, targetFrameRate, characteristics, cameraType);
                } else {
                    Log.e(TAG, "Failed to create surfaces for fallback session");
                    stopRecording();
                }
                // -------------- Fix End: Use per-camera FPS and include GL surface in fallback
                // --------------
            } catch (Exception e) {
                Log.e(TAG, "Failed to create fallback session after high-speed failure", e);
                stopRecording();
            }
        }

        @Override
        public void onClosed(@NonNull CameraCaptureSession session) {
            if (isStopping)
                return;
            // Handle session closure same as standard session
            if (captureSessionCallback != null) {
                captureSessionCallback.onClosed(session);
            }
        }
    };

    /**
     * Handles errors that occur during the preparation or execution of video
     * processing.
     * Logs the error, shows a Toast, resets the processing flag, and attempts to
     * clean up
     * the temporary input file associated with the failed process.
     *
     * @param errorMessage          A description of the error that occurred.
     * @param internalTempInputPath The URI (as String) or absolute path of the
     *                              temporary input file
     *                              that should be cleaned up because its processing
     *                              failed. Can be null.
     */
    // Helper to handle storage setup errors consistently
    private void handleProcessingError(String errorMessage, @Nullable String internalTempInputPath) {
        Log.e(TAG, "Processing Error: " + errorMessage);
        Toast.makeText(this, "Error processing video recording", Toast.LENGTH_LONG).show();

        // If an input path/URI was provided, log attempt to clean it
        if (internalTempInputPath != null) {
            Log.d(TAG, "handleProcessingError: Original temp file to keep (maybe): " + internalTempInputPath);
            // We generally DO NOT delete the input temp file on processing errors,
            // as it might be the only copy left. We just log it.
            // CleanupTemporaryFile() call will be skipped if processing failed.
        } else {
            Log.w(TAG, "handleProcessingError called without specific input path reference.");
        }

        // Check if the service should stop now that processing has failed/stopped
        if (!isWorkingInProgress()) {
            Log.d(TAG, "handleProcessingError: No other work pending, stopping service.");
            stopSelf();
        }
    }

    // Use this primarily for file paths, maybe less critical for content:// URIs
    private String escapeFFmpegPath(String path) {
        if (path == null)
            return "";
        // For content URIs, usually best not to quote/escape unless specifically needed
        if (path.startsWith("content://")) {
            return path;
        } else {
            // Escape single quotes for shell safety when wrapping path in single quotes
            return "'" + path.replace("'", "'\\''") + "'";
        }
    }

    // Helper to handle storage setup errors consistently
    private void handleStorageError(String message) {
        Log.e(TAG, message);
        Toast.makeText(this, message + ", Using Internal.", Toast.LENGTH_LONG).show();
        sharedPreferencesManager.setStorageMode(SharedPreferencesManager.STORAGE_MODE_INTERNAL);
        sharedPreferencesManager.setCustomStorageUri(null);
        // Note: Do not call stopSelf here, let setupMediaRecorder fail naturally
    }
    // --- End Watermarking & Processing ---

    // --- Helper Methods ---
    private void setupSurfaceTexture(Intent intent) {
        // ----- Fix Start for this method(setupSurfaceTexture)-----
        Surface oldPreviewSurface = previewSurface; // Store old surface to check for changes
        if (intent != null) {
            previewSurface = intent.getParcelableExtra("SURFACE");
            boolean validOldSurface = oldPreviewSurface != null && oldPreviewSurface.isValid();
            boolean validNewSurface = previewSurface != null && previewSurface.isValid();
            if (glRecordingPipeline != null) {
                if (validNewSurface) {
                    glRecordingPipeline.setPreviewSurface(previewSurface);
                } else {
                    glRecordingPipeline.setPreviewSurface(null);
                }
            }
            if (validOldSurface && !validNewSurface && isRecordingOrPaused()) {
                Log.d(TAG, "Surface lost while recording - creating dummy surface to prevent recording issues");
                createDummyBackgroundSurface();
            }
            Log.d(TAG, "Preview surface updated: " + validNewSurface);
            int width = intent.getIntExtra("SURFACE_WIDTH", -1);
            int height = intent.getIntExtra("SURFACE_HEIGHT", -1);
            // Update the GL pipeline with the new dimensions if available
            if (glRecordingPipeline != null && validNewSurface && width > 0 && height > 0) {
                glRecordingPipeline.updateSurfaceDimensions(width, height);
            }
        }
        // ----- Fix Ended for this method(setupSurfaceTexture)-----
    }

    private int getVideoBitrate() {
        int videoBitrate;
        if (sharedPreferencesManager.sharedPreferences.getBoolean("bitrate_mode_custom", false)) {
            videoBitrate = sharedPreferencesManager.sharedPreferences.getInt("bitrate_custom_value", 16000) * 1000; // stored
                                                                                                                    // as
                                                                                                                    // kbps,
                                                                                                                    // use
                                                                                                                    // bps
            Log.d(TAG, "[DEBUG] Using custom video bitrate: " + videoBitrate + " bps");
        } else {
            videoBitrate = Utils.estimateBitrate(sharedPreferencesManager.getCameraResolution(),
                    sharedPreferencesManager.getVideoFrameRate());
            Log.d(TAG, "[DEBUG] Using default video bitrate: " + videoBitrate + " bps");
        }
        return videoBitrate;
    }

    private int getFontSizeBasedOnBitrate() {
        int fontSize;
        long videoBitrate = getVideoBitrate();
        if (videoBitrate <= 1_000_000)
            fontSize = 12; // <= 1 Mbps (Approx SD)
        else if (videoBitrate <= 5_000_000)
            fontSize = 16; // <= 5 Mbps (Approx HD)
        else if (videoBitrate <= 15_000_000)
            fontSize = 24; // <= 15 Mbps (Approx FHD)
        else
            fontSize = 30; // Higher bitrates (e.g., 4K)
        Log.d(TAG, "Determined Font Size: " + fontSize + " for bitrate " + videoBitrate);
        return fontSize;
    }

    private String getCurrentTimestamp() {
        SimpleDateFormat sdf = new SimpleDateFormat("dd/MMM/yyyy hh:mm:ss a", Locale.ENGLISH); // 12-hour format with
                                                                                               // AM/PM
        return convertArabicNumeralsToEnglish(sdf.format(new Date()));
    }

    private String convertArabicNumeralsToEnglish(String text) {
        if (text == null)
            return null;
        return text.replaceAll("٠", "0")
                .replaceAll("١", "1")
                .replaceAll("٢", "2")
                .replaceAll("٣", "3")
                .replaceAll("٤", "4")
                .replaceAll("٥", "5")
                .replaceAll("٦", "6")
                .replaceAll("٧", "7")
                .replaceAll("٨", "8")
                .replaceAll("٩", "9");
    }

    private String getLocationData() {
        if (locationHelper == null) {
            Log.w(TAG, "LocationHelper not initialized, cannot get location data.");
            return ""; // Return empty, not "Not available" to avoid user confusion
        }
        String locData = locationHelper.getLocationData();
        // Avoid adding "Location not available" to watermark, just add lat/lon if
        // present
        return (locData != null && locData.contains("Lat=")) ? locData : "";
    }

    private String escapeFFmpegString(String text) {
        if (text == null)
            return "";
        return text
                .replace("\\", "\\\\") // Escape backslashes
                .replace(":", "\\:") // Escape colons
                .replace("'", "") // Remove single quotes entirely (safer than escaping)
                .replace("\"", "") // Remove double quotes
                .replace("%", "%%"); // Escape percent signs
    }

    // --- End Helper Methods ---

    // --- Broadcasts ---
    private void broadcastOnRecordingStarted() {
        Intent broadcastIntent = new Intent(Constants.BROADCAST_ON_RECORDING_STARTED);
        broadcastIntent.putExtra(Constants.INTENT_EXTRA_RECORDING_START_TIME, recordingStartTime);
        broadcastIntent.putExtra(Constants.INTENT_EXTRA_RECORDING_STATE, recordingState);
        sendBroadcast(broadcastIntent);
        Log.d(TAG, "Broadcasted: BROADCAST_ON_RECORDING_STARTED");
    }

    private void broadcastOnRecordingResumed() {
        Intent broadcastIntent = new Intent(Constants.BROADCAST_ON_RECORDING_RESUMED);
        sendBroadcast(broadcastIntent);
        Log.d(TAG, "Broadcasted: BROADCAST_ON_RECORDING_RESUMED");
    }

    private void broadcastOnRecordingPaused() {
        Intent broadcastIntent = new Intent(Constants.BROADCAST_ON_RECORDING_PAUSED);
        sendBroadcast(broadcastIntent);
        Log.d(TAG, "Broadcasted: BROADCAST_ON_RECORDING_PAUSED");
    }

    private void broadcastOnRecordingStopped() {
        Intent broadcastIntent = new Intent(Constants.BROADCAST_ON_RECORDING_STOPPED);
        sendBroadcast(broadcastIntent);
        Log.d(TAG, "Broadcasted: BROADCAST_ON_RECORDING_STOPPED");
    }

    private void broadcastOnRecordingStateCallback() {
        Intent broadcastIntent = new Intent(Constants.BROADCAST_ON_RECORDING_STATE_CALLBACK);
        broadcastIntent.putExtra(Constants.INTENT_EXTRA_RECORDING_STATE, recordingState);
        sendBroadcast(broadcastIntent);
        Log.d(TAG, "Broadcasted: BROADCAST_ON_RECORDING_STATE_CALLBACK with state: " + recordingState);
    }
    // --- End Broadcasts ---

    // --- Notifications ---
    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // ----- Fix Start for this method(createNotificationChannel) -----
            // Get the custom channel name or use a generic name
            String channelName = sharedPreferencesManager.getNotificationChannelName();
            CharSequence name = (channelName != null) ? channelName
                    : getString(R.string.notification_channel_recording, getString(R.string.app_name));

            // Use a generic description that doesn't reveal the app's purpose
            String description = getString(R.string.notification_channel_description);
            int importance = NotificationManager.IMPORTANCE_LOW; // Low importance to be less intrusive
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, name, importance);
            channel.setDescription(description);
            channel.setSound(null, null); // No sound
            channel.enableVibration(false); // No vibration
            // ----- Fix Ended for this method(createNotificationChannel) -----

            NotificationManager notificationManager = getSystemService(NotificationManager.class);
            if (notificationManager != null) {
                notificationManager.createNotificationChannel(channel);
                Log.d(TAG, "Notification channel created or updated.");
            } else {
                Log.e(TAG, "NotificationManager service not found.");
            }
        }
    }

    private void setupRecordingInProgressNotification() {
        if (ActivityCompat.checkSelfPermission(this,
                android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "POST_NOTIFICATIONS permission not granted, skipping notification setup.");
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                try {
                    // On Tiramisu+, we can start foreground without permission. Create a minimal
                    // notification.
                    NotificationCompat.Builder minimalBuilder = new NotificationCompat.Builder(this, CHANNEL_ID)
                            .setContentTitle(getString(R.string.app_name))
                            .setSmallIcon(R.drawable.ic_notification_icon);

                    startForeground(NOTIFICATION_ID, minimalBuilder.build(),
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
                                    | ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
                    Log.d(TAG, "Started foreground without notification permission (Tiramisu+).");
                } catch (Exception e) {
                    Log.e(TAG, "Error starting foreground on Tiramisu+ without permission", e);
                    stopSelf();
                }
            } else {
                Toast.makeText(this, "Notification permission needed", Toast.LENGTH_LONG).show();
                stopSelf();
            }
            return;
        }

        // STEP 1: Show a simple, fast notification immediately to satisfy the OS
        // requirement.
        NotificationCompat.Builder immediateBuilder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification_icon)
                .setContentTitle(getString(R.string.notification_video_recording))
                .setContentText("Initializing...");

        startForeground(NOTIFICATION_ID, immediateBuilder.build());
        Log.d(TAG, "Foreground service started immediately with a placeholder notification.");

        // STEP 2: Now, prepare the full notification with the custom icon in the
        // background.
        new Handler(Looper.getMainLooper()).post(() -> {
            NotificationCompat.Builder fullNotificationBuilder = createBaseNotificationBuilder(); // This method
                                                                                                  // contains the slow
                                                                                                  // bitmap operations
            String notificationText = sharedPreferencesManager.getNotificationText(false);
            boolean hideStopButton = sharedPreferencesManager.isNotificationStopButtonHidden();

            fullNotificationBuilder.setContentText(notificationText != null ? notificationText
                    : getString(R.string.notification_video_recording_progress_description));

            if (!hideStopButton) {
                fullNotificationBuilder.clearActions()
                        .addAction(new NotificationCompat.Action(
                                R.drawable.ic_stop,
                                getString(R.string.button_stop),
                                createStopRecordingIntent()));
            }

            // STEP 3: Update the notification with the full content.
            NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, fullNotificationBuilder.build());
            Log.d(TAG, "Foreground notification updated with full content (including custom icon).");
        });
    }

    private void setupRecordingResumeNotification() { // Notification shown when PAUSED
        // ----- Fix Start for this method(setupRecordingResumeNotification) -----
        if (ActivityCompat.checkSelfPermission(this,
                android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "POST_NOTIFICATIONS permission not granted, skipping notification update.");
            return; // Don't crash if user denied permission after start
        }

        // Get custom notification text if set
        String notificationText = sharedPreferencesManager.getNotificationText(true);
        boolean hideStopButton = sharedPreferencesManager.isNotificationStopButtonHidden();

        NotificationCompat.Builder builder = createBaseNotificationBuilder()
                .setContentText(notificationText != null ? notificationText
                        : getString(R.string.notification_video_recording_paused_description))
                .clearActions(); // Remove previous actions

        // Add resume action
        builder.addAction(new NotificationCompat.Action(
                R.drawable.ic_play, // Use Play icon for Resume action
                getString(R.string.button_resume),
                createResumeRecordingIntent()));

        // Add stop action only if not hidden
        if (!hideStopButton) {
            builder.addAction(new NotificationCompat.Action(
                    R.drawable.ic_stop,
                    getString(R.string.button_stop),
                    createStopRecordingIntent()));
        }

        NotificationManagerCompat notificationManager = NotificationManagerCompat.from(this);
        notificationManager.notify(NOTIFICATION_ID, builder.build()); // Just update existing notification
        Log.d(TAG, "Foreground notification updated for PAUSED.");
        // ----- Fix Ended for this method(setupRecordingResumeNotification) -----
    }

    private NotificationCompat.Builder createBaseNotificationBuilder() {
        // ----- Fix Start for this method(createBaseNotificationBuilder) -----
        // Get custom notification title if set
        String notificationTitle = sharedPreferencesManager.getNotificationTitle();
        String preset = sharedPreferencesManager.getNotificationPreset();

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(notificationTitle != null ? notificationTitle
                        : getString(R.string.notification_video_recording))
                .setOngoing(true) // Makes it non-dismissible
                .setSilent(true) // Suppress sound/vibration defaults
                .setPriority(NotificationCompat.PRIORITY_LOW);

        // Choose appropriate icon based on notification preset
        int smallIconResId;
        switch (preset) {
            case SharedPreferencesManager.NOTIFICATION_PRESET_SYSTEM_UPDATE:
                smallIconResId = android.R.drawable.stat_sys_download;
                break;
            case SharedPreferencesManager.NOTIFICATION_PRESET_DOWNLOADING:
                smallIconResId = android.R.drawable.stat_sys_download;
                break;
            case SharedPreferencesManager.NOTIFICATION_PRESET_SYNCING:
                smallIconResId = android.R.drawable.stat_notify_sync;
                break;
            default:
                smallIconResId = R.drawable.ic_notification_icon;
                break;
        }

        builder.setSmallIcon(smallIconResId);

        // -------------- Fix Start for this
        // method(createBaseNotificationBuilder_large_icon)-----------
        // Set custom large icon based on currently selected app icon for discretion
        int largeIconResId = getCurrentAppIconResourceId();
        Log.d(TAG, "createBaseNotificationBuilder: Using large icon resource ID = " + largeIconResId);
        android.graphics.Bitmap largeIconBitmap = loadNotificationLargeIconBitmap(largeIconResId);
        if (largeIconBitmap != null) {
            builder.setLargeIcon(largeIconBitmap);
            Log.d(TAG, "createBaseNotificationBuilder: Successfully set large icon bitmap");
        } else {
            Log.w(TAG, "createBaseNotificationBuilder: Failed to render large icon, proceeding without it");
        }
        // -------------- Fix Ended for this
        // method(createBaseNotificationBuilder_large_icon)-----------

        // Set a generic content intent that doesn't reveal the app
        if (!SharedPreferencesManager.NOTIFICATION_PRESET_DEFAULT.equals(preset)) {
            // For non-default presets, use a blank PendingIntent that does nothing
            Intent emptyIntent = new Intent();
            PendingIntent emptyPendingIntent = PendingIntent.getActivity(this, 0, emptyIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            builder.setContentIntent(emptyPendingIntent);
        } else {
            // For default preset, use normal app opening intent
            builder.setContentIntent(createOpenAppIntent());
        }

        return builder;
        // ----- Fix Ended for this method(createBaseNotificationBuilder) -----
    }

    // -------------- Fix Start for this
    // method(getCurrentAppIconResourceId)-----------
    /**
     * Gets the resource ID for the currently selected app icon to use as
     * notification large icon.
     * This ensures the notification uses the same icon as the user has selected for
     * the app launcher,
     * maintaining discretion and consistency.
     * 
     * @return Resource ID of the current app icon, or 0 if not found
     */
    private int getCurrentAppIconResourceId() {
        String currentAppIcon = sharedPreferencesManager.getCurrentAppIcon();
        Log.d(TAG, "getCurrentAppIconResourceId: Current app icon preference = " + currentAppIcon);
        // Map icon key to its resource id via SharedPreferencesManager
        int resId = sharedPreferencesManager.getAppIconResId(currentAppIcon);
        Log.d(TAG, "getCurrentAppIconResourceId: Resolved to resId=" + resId);
        return resId;
    }

    // -------------- Fix Start for this
    // method(loadNotificationLargeIconBitmap)-----------
    /**
     * Renders the given icon resource (mipmap/drawable; vector/adaptive/webp/png)
     * into
     * a Bitmap sized for notification large icons. This ensures we always provide a
     * rasterized PNG-like bitmap even when the source is vector or adaptive.
     */
    private @Nullable android.graphics.Bitmap loadNotificationLargeIconBitmap(int resId) {
        if (resId == 0)
            return null;
        try {
            // First try decoding directly (works for PNG/WEBP bitmaps)
            android.graphics.Bitmap direct = android.graphics.BitmapFactory.decodeResource(getResources(), resId);
            int targetW = getResources().getDimensionPixelSize(android.R.dimen.notification_large_icon_width);
            int targetH = getResources().getDimensionPixelSize(android.R.dimen.notification_large_icon_height);
            int target = Math.max(1, Math.min(targetW, targetH));
            if (direct != null) {
                if (direct.getWidth() == target && direct.getHeight() == target)
                    return direct;
                // Scale to target square for consistency
                return android.graphics.Bitmap.createScaledBitmap(direct, target, target, true);
            }

            // Fallback: render any Drawable (vector/adaptive) to bitmap
            android.graphics.drawable.Drawable d = androidx.appcompat.content.res.AppCompatResources.getDrawable(this,
                    resId);
            if (d == null)
                return null;
            android.graphics.Bitmap bmp = android.graphics.Bitmap.createBitmap(target, target,
                    android.graphics.Bitmap.Config.ARGB_8888);
            android.graphics.Canvas canvas = new android.graphics.Canvas(bmp);
            d.setBounds(0, 0, target, target);
            d.draw(canvas);
            return bmp;
        } catch (Throwable t) {
            Log.w(TAG, "loadNotificationLargeIconBitmap: failed to render icon", t);
            return null;
        }
    }
    // -------------- Fix Ended for this
    // method(loadNotificationLargeIconBitmap)-----------
    // -------------- Fix Ended for this
    // method(getCurrentAppIconResourceId)-----------

    private void cancelNotification() {
        NotificationManagerCompat notificationManager = NotificationManagerCompat.from(this);
        notificationManager.cancel(NOTIFICATION_ID);
        Log.d(TAG, "Cancelled notification.");
    }

    private PendingIntent createOpenAppIntent() {
        Intent openAppIntent = new Intent(this, MainActivity.class);
        // Standard flags to bring existing task to front or start new
        openAppIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        // Use FLAG_IMMUTABLE for security best practices on newer Androids
        int flags = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                ? PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
                : PendingIntent.FLAG_UPDATE_CURRENT;
        return PendingIntent.getActivity(this, 0, openAppIntent, flags);
    }

    private PendingIntent createStopRecordingIntent() {
        Intent stopIntent = new Intent(this, RecordingService.class);
        stopIntent.setAction(Constants.INTENT_ACTION_STOP_RECORDING);
        int flags = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                ? PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
                : PendingIntent.FLAG_UPDATE_CURRENT;
        return PendingIntent.getService(this, 1, stopIntent, flags); // Use unique request code (1)
    }

    private PendingIntent createResumeRecordingIntent() {
        Intent resumeIntent = new Intent(this, RecordingService.class);
        resumeIntent.setAction(Constants.INTENT_ACTION_RESUME_RECORDING);
        // Make sure surface data isn't needed here - pass null if required or handle
        // differently
        int flags = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                ? PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
                : PendingIntent.FLAG_UPDATE_CURRENT;
        return PendingIntent.getService(this, 2, resumeIntent, flags); // Use unique request code (2)
    }

    // --- Toast Helpers ---
    private void showRecordingResumedToast() {
        Utils.showQuickToast(this, R.string.video_recording_resumed);
    }

    private void showRecordingInPausedToast() {
        Utils.showQuickToast(this, R.string.video_recording_paused);
    }
    // --- End Toast Helpers ---

    // --- Torch Logic ---

    private void toggleRecordingTorch() {
        if (captureRequestBuilder != null && captureSession != null && cameraDevice != null) {
            if (recordingState == RecordingState.IN_PROGRESS || recordingState == RecordingState.PAUSED) {
                try {
                    isRecordingTorchEnabled = !isRecordingTorchEnabled; // Toggle the state for the session
                    Log.d(TAG, "Toggling recording torch via CaptureRequest. New state: " + isRecordingTorchEnabled);

                    captureRequestBuilder.set(CaptureRequest.FLASH_MODE,
                            isRecordingTorchEnabled ? CaptureRequest.FLASH_MODE_TORCH : CaptureRequest.FLASH_MODE_OFF);

                    // Apply the change by updating the repeating request
                    captureSession.setRepeatingRequest(captureRequestBuilder.build(), null, backgroundHandler);
                    Log.d(TAG, "Recording torch repeating request updated.");

                    // Broadcast state change TO THE UI so it can update its torch button
                    Intent intent = new Intent(Constants.BROADCAST_ON_TORCH_STATE_CHANGED);
                    intent.putExtra(Constants.INTENT_EXTRA_TORCH_STATE, isRecordingTorchEnabled);
                    sendBroadcast(intent);

                } catch (CameraAccessException e) {
                    Log.e(TAG, "Could not toggle recording torch via CaptureRequest: " + e.getMessage());
                    isRecordingTorchEnabled = !isRecordingTorchEnabled; // Revert state on error
                } catch (IllegalStateException e) {
                    Log.e(TAG, "Could not toggle recording torch via CaptureRequest - session/camera closed?", e);
                    isRecordingTorchEnabled = !isRecordingTorchEnabled; // Revert state on error
                }
            } else {
                Log.w(TAG, "Cannot toggle recording torch via CaptureRequest - not IN_PROGRESS or PAUSED. State: "
                        + recordingState);
                // If not recording, HomeFragment should handle torch directly via
                // CameraManager.setTorchMode()
            }
        } else {
            Log.w(TAG,
                    "Cannot toggle recording torch via CaptureRequest - session, request builder, or camera device is null.");
        }
    }

    // --- Status Check ---
    public boolean isRecording() {
        return recordingState == RecordingState.IN_PROGRESS;
    }

    public boolean isPaused() {
        return recordingState == RecordingState.PAUSED;
    }

    /**
     * Combined helper to check if recording is active or paused
     */
    public boolean isRecordingOrPaused() {
        return isRecording() || isPaused() || recordingState == RecordingState.WAITING_FOR_CAMERA;
    }

    // Combined status check
    public boolean isWorkingInProgress() {

        // Only consider recording states (not FFmpeg processing) for determining if
        // camera is busy
        // This allows new recordings to start while FFmpeg is still processing in the
        // background
        return recordingState == RecordingState.IN_PROGRESS ||
                recordingState == RecordingState.PAUSED ||
                recordingState == RecordingState.STARTING;

    }

    /**
     * Check if the service should stay alive (either recording is in progress or
     * FFmpeg is processing)
     * This is different from isWorkingInProgress() which only checks if recording
     * is active
     */
    private boolean shouldServiceStayAlive() {
        return ffmpegProcessingTaskCount.get() > 0 ||
                recordingState == RecordingState.IN_PROGRESS ||
                recordingState == RecordingState.PAUSED ||
                recordingState == RecordingState.STARTING ||
                recordingState == RecordingState.WAITING_FOR_CAMERA;
    }

    private void handleSegmentRolloverInternal() {
        Log.i(TAG,
                "handleSegmentRolloverInternal: NO-OP with setNextOutputFile. All rollover handled in OnInfoListener.");
    }

    private void proceedWithRolloverAfterOldSessionClosed() {
        Log.i(TAG,
                "proceedWithRolloverAfterOldSessionClosed: NO-OP with setNextOutputFile. All rollover handled in OnInfoListener.");
    }

    private File createNextSegmentOutputFile(int nextSegmentNumber) {
        // ----- Fix Start for this method(createNextSegmentOutputFile)-----
        String storageMode = sharedPreferencesManager.getStorageMode();
        if (SharedPreferencesManager.STORAGE_MODE_CUSTOM.equals(storageMode)) {
            // SAF/DocumentFile mode
            String customUriString = sharedPreferencesManager.getCustomStorageUri();
            if (customUriString == null) {
                Log.e(TAG, "createNextSegmentOutputFile: Custom storage selected but URI is null");
                return null;
            }
            Uri treeUri = Uri.parse(customUriString);
            DocumentFile pickedDir = DocumentFile.fromTreeUri(this, treeUri);
            if (pickedDir == null || !pickedDir.canWrite()) {
                Log.e(TAG, "createNextSegmentOutputFile: Cannot write to selected custom directory");
                return null;
            }
            String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
            String segmentSuffix = String.format(Locale.US, "_%03d", nextSegmentNumber);
            // Use FadCam prefix for consistent naming
            String baseFilename = Constants.RECORDING_DIRECTORY + "_" + timestamp + segmentSuffix + "."
                    + Constants.RECORDING_FILE_EXTENSION;
            DocumentFile nextDocFile = pickedDir.createFile("video/" + Constants.RECORDING_FILE_EXTENSION,
                    baseFilename);
            if (nextDocFile == null || !nextDocFile.exists()) {
                Log.e(TAG, "createNextSegmentOutputFile: Failed to create DocumentFile in SAF: " + baseFilename);
                return null;
            }
            try {
                // Open a ParcelFileDescriptor for the next segment
                ParcelFileDescriptor pfd = getContentResolver().openFileDescriptor(nextDocFile.getUri(), "w");
                if (pfd == null) {
                    Log.e(TAG, "createNextSegmentOutputFile: Failed to open ParcelFileDescriptor for next SAF URI: "
                            + nextDocFile.getUri());
                    return null;
                }
                pfd.close(); // Immediately close, as the pipeline will open as needed
            } catch (Exception e) {
                Log.e(TAG, "createNextSegmentOutputFile: Exception opening PFD for next SAF URI", e);
                return null;
            }
            Log.i(TAG, "Next segment SAF file created: " + nextDocFile.getUri());
            return null;
        } else {
            // Internal storage mode - Use same directory and naming as first segment
            String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
            String segmentSuffix = String.format(Locale.US, "_%03d", nextSegmentNumber);
            String baseFilename = Constants.RECORDING_DIRECTORY + "_" + timestamp + segmentSuffix + "."
                    + Constants.RECORDING_FILE_EXTENSION;

            // Use the same directory as the first segment (app's external files directory)
            File videoDir = new File(getExternalFilesDir(null), Constants.RECORDING_DIRECTORY);
            if (!videoDir.exists() && !videoDir.mkdirs()) {
                Log.e(TAG, "Cannot create recording directory: " + videoDir.getAbsolutePath());
                Toast.makeText(this, "Error creating recording directory", Toast.LENGTH_LONG).show();
                return null;
            }

            // Create the file directly in the final directory
            File nextFile = new File(videoDir, baseFilename);
            Log.i(TAG, "Next segment file created: " + nextFile.getAbsolutePath());
            return nextFile;
        }
        // ----- Fix Ended for this method(createNextSegmentOutputFile)-----
    }

    // Helper to check if a wired mic is connected
    private boolean isWiredMicConnected() {
        AudioManager audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            AudioDeviceInfo[] devices = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS);
            for (AudioDeviceInfo device : devices) {
                if (device.getType() == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                        device.getType() == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                        device.getType() == AudioDeviceInfo.TYPE_USB_DEVICE ||
                        device.getType() == AudioDeviceInfo.TYPE_USB_HEADSET) {
                    return true;
                }
            }
        } else {
            Intent intent = registerReceiver(null, new IntentFilter(Intent.ACTION_HEADSET_PLUG));
            return intent != null && intent.getIntExtra("state", 0) == 1;
        }
        return false;
    }

    /**
     * Sets the camera resource releasing state and schedules it to be available
     * again after a cooldown period.
     * Also broadcasts the availability state change to the UI components.
     * 
     * @param releasing True if camera resources are being released, false otherwise
     */
    private void setCameraResourcesReleasing(boolean releasing) {
        isCameraResourceReleasing = releasing;
        Log.d(TAG, "Camera resources releasing state set to: " + releasing);

        // Broadcast the current availability state
        broadcastCameraResourceAvailability(!releasing);

        // If we're releasing resources, schedule them to become available after a
        // cooldown
        if (releasing) {
            mainHandler.postDelayed(() -> {
                isCameraResourceReleasing = false;
                Log.d(TAG, "Camera resource cooldown ended, resources available now");

                // Broadcast that camera resources are available again
                broadcastCameraResourceAvailability(true);
            }, Constants.CAMERA_RESOURCE_COOLDOWN_MS);
        }
    }

    /**
     * Broadcasts the camera resource availability state to UI components
     * 
     * @param available True if camera resources are available for a new recording
     */
    private void broadcastCameraResourceAvailability(boolean available) {
        Intent availabilityIntent = new Intent(Constants.ACTION_CAMERA_RESOURCE_AVAILABILITY);
        availabilityIntent.putExtra(Constants.EXTRA_CAMERA_RESOURCES_AVAILABLE, available);
        sendBroadcast(availabilityIntent);
        Log.d(TAG, "Broadcasted camera resource availability: " + available);
    }

    /**
     * Helper method to verify if location metadata was successfully embedded in a
     * video file
     * This method will add logs that you can filter for "METADATA_CHECK" to trace
     * the issue
     * 
     * @param videoFilePath Path to the final processed video file
     */
    private void verifyLocationMetadata(String videoFilePath) {
        if (videoFilePath == null) {
            Log.e(TAG, "METADATA_CHECK: Video file path is null");
            return;
        }

        Log.d(TAG, "METADATA_CHECK: Checking for location metadata in " + videoFilePath);

        try {
            MediaMetadataRetriever retriever = new MediaMetadataRetriever();
            retriever.setDataSource(videoFilePath);

            // Get location data
            String locationString = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_LOCATION);

            if (locationString != null && !locationString.isEmpty()) {
                Log.d(TAG, "METADATA_CHECK: Found location data in video: " + locationString);
            } else {
                Log.e(TAG, "METADATA_CHECK: No location metadata found in processed video!");
                Log.e(TAG, "METADATA_CHECK: This suggests the metadata was lost during processing");
            }

            // Check a few other metadata fields to see if any metadata is preserved
            String duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            String width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH);
            String height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT);

            Log.d(TAG, "METADATA_CHECK: Other metadata - Duration: " + duration +
                    ", Resolution: " + width + "x" + height);

            retriever.release();
        } catch (Exception e) {
            Log.e(TAG, "METADATA_CHECK: Error checking metadata", e);
        }
    }

    /**
     * Reinitializes location helpers based on current preference settings
     * Called when location settings are changed while the service is running
     */
    private void reinitializeLocationHelpers() {
        reinitializeLocationHelpers(false);
    }

    /**
     * Reinitializes location helpers based on current preference settings
     * 
     * @param forceInit Force reinitialization even if settings haven't changed
     */
    private void reinitializeLocationHelpers(boolean forceInit) {
        Log.d(TAG, "==== Reinitializing Location Helpers ====");
        Log.d(TAG, "Current preferences: location=" + sharedPreferencesManager.isLocalisationEnabled() +
                ", embedding=" + sharedPreferencesManager.isLocationEmbeddingEnabled());

        // Handle LocationHelper for watermark
        boolean locationEnabled = sharedPreferencesManager.isLocalisationEnabled();
        if (locationEnabled) {
            if (locationHelper == null || forceInit) {
                try {
                    // Clean up existing helper if needed
                    if (locationHelper != null) {
                        locationHelper.stopLocationUpdates();
                    }

                    locationHelper = new LocationHelper(this);
                    Log.d(TAG, "Created new LocationHelper for watermark");
                } catch (Exception e) {
                    Log.e(TAG, "Error initializing LocationHelper", e);
                }
            } else {
                Log.d(TAG, "LocationHelper already exists, no need to recreate");
            }
        } else {
            if (locationHelper != null) {
                try {
                    locationHelper.stopLocationUpdates();
                    Log.d(TAG, "Stopped existing LocationHelper");
                    locationHelper = null;
                } catch (Exception e) {
                    Log.e(TAG, "Error stopping LocationHelper", e);
                }
            }
        }

        // Handle GeotagHelper for metadata embedding
        boolean embeddingEnabled = sharedPreferencesManager.isLocationEmbeddingEnabled();
        if (embeddingEnabled) {
            boolean needsNewHelper = geotagHelper == null || forceInit;

            if (needsNewHelper) {
                try {
                    // Clean up existing helper if needed
                    if (geotagHelper != null) {
                        geotagHelper.stopUpdates();
                    }

                    // Create new helper
                    geotagHelper = new GeotagHelper(this);
                    boolean started = geotagHelper.startUpdates();
                    Log.d(TAG, "Created new GeotagHelper for metadata embedding. Updates started: " + started);
                } catch (Exception e) {
                    Log.e(TAG, "Error initializing GeotagHelper", e);
                }
            } else {
                Log.d(TAG, "GeotagHelper already exists, ensuring updates are started");
                // Make sure updates are started even if helper exists
                try {
                    boolean started = geotagHelper.startUpdates();
                    Log.d(TAG, "Ensured GeotagHelper updates are active: " + started);
                } catch (Exception e) {
                    Log.e(TAG, "Error starting GeotagHelper updates", e);
                }
            }

        } else {
            if (geotagHelper != null) {
                try {
                    geotagHelper.stopUpdates();
                    Log.d(TAG, "Stopped existing GeotagHelper");
                    geotagHelper = null;
                } catch (Exception e) {
                    Log.e(TAG, "Error stopping GeotagHelper", e);
                }
            }
        }

        Log.d(TAG, "==== Location Helpers Reinitialization Complete ====");
    }

    /**
     * Creates a high-speed constrained capture session for 60fps+ recording
     */
    private void createHighSpeedSession(List<Surface> surfaces, CameraCharacteristics characteristics,
            int targetFrameRate, CameraType cameraType) {
        try {
            // For high-speed recording, we need a constrained high-speed capture session
            Log.d(TAG, "Creating constrained high-speed session for " + targetFrameRate + "fps");

            // Get the best size for high-speed recording
            Size highSpeedSize = HighSpeedCaptureHelper.getBestHighSpeedSize(
                    characteristics, targetFrameRate, 0, 0);

            if (highSpeedSize == null) {
                Log.d(TAG, "No suitable high-speed size found");
                // Fallback to standard session
                createStandardSession(surfaces, targetFrameRate, characteristics, cameraType);
                return;
            }

            // Configure a builder for high-speed recording
            captureRequestBuilder = HighSpeedCaptureHelper.configureHighSpeedRequestBuilder(
                    cameraDevice, null, targetFrameRate, characteristics);

            // Apply saved user camera prefs (if any) so recording respects user choices on
            // start
            try {
                applySavedCameraPrefsToBuilder(captureRequestBuilder);
            } catch (Exception e) {
                Log.w(TAG, "Failed to apply saved camera prefs to high-speed builder: " + e.getMessage());
            }

            if (captureRequestBuilder == null) {
                Log.d(TAG, "Failed to create high-speed request builder");
                // Fallback to standard session
                createStandardSession(surfaces, targetFrameRate, characteristics, cameraType);
                return;
            }

            // Add surfaces as targets
            for (Surface surface : surfaces) {
                captureRequestBuilder.addTarget(surface);
            }

            // Apply zoom settings for back camera
            applyZoomSettings(captureRequestBuilder, cameraType);

            // Set torch mode if enabled
            if (isRecordingTorchEnabled) {
                captureRequestBuilder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_TORCH);
            } else {
                captureRequestBuilder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF);
            }

            // Create the constrained high-speed session
            cameraDevice.createConstrainedHighSpeedCaptureSession(
                    surfaces,
                    highSpeedSessionCallback,
                    backgroundHandler);

            Log.d(TAG, "Requested constrained high-speed capture session creation");
        } catch (Exception e) {
            Log.e(TAG, "Failed to create high-speed session", e);
            // Fallback to standard session
            try {
                createStandardSession(surfaces, targetFrameRate, characteristics, cameraType);
            } catch (Exception e2) {
                Log.e(TAG, "Failed to create fallback standard session", e2);
                stopRecording();
            }
        }
    }

    /**
     * Fallback to create a standard session with the best possible frame rate
     * settings
     */
    private void createStandardSession(List<Surface> surfaces, int targetFrameRate,
            CameraCharacteristics characteristics, CameraType cameraType) {
        try {
            Log.d(TAG, "Creating standard session with optimized frame rate settings");

            // Create standard request builder
            captureRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);

            // Add surfaces as targets
            for (Surface surface : surfaces) {
                captureRequestBuilder.addTarget(surface);
            }

            // Apply frame rate settings
            applyFrameRateSettings(captureRequestBuilder, targetFrameRate, characteristics);

            // CRITICAL: Apply saved camera preferences for standard session too!
            // This was missing and causing exposure/AE/AF controls to not work
            try {
                applySavedCameraPrefsToBuilder(captureRequestBuilder);
                Log.d(TAG, "Applied saved camera prefs to standard session");
            } catch (Exception e) {
                Log.w(TAG, "Failed to apply saved camera prefs to standard builder: " + e.getMessage());
            }

            // Apply zoom settings for back camera
            applyZoomSettings(captureRequestBuilder, cameraType);

            // Set torch mode if enabled
            if (isRecordingTorchEnabled) {
                captureRequestBuilder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_TORCH);
            } else {
                captureRequestBuilder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF);
            }

            // Create the session
            cameraDevice.createCaptureSession(surfaces, captureSessionCallback, backgroundHandler);
        } catch (Exception e) {
            Log.e(TAG, "Failed to create standard session", e);
            stopRecording();
        }
    }

    /**
     * Apply saved AF/AE/EV preferences into the provided builder where supported.
     */
    private void applySavedCameraPrefsToBuilder(CaptureRequest.Builder builder) {
        if (builder == null || sharedPreferencesManager == null)
            return;
        try {
            // CRITICAL: Always enable AE mode for exposure compensation to work
            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
            Log.d(TAG, "Set AE_MODE_ON for exposure compensation support");

            int savedEv = sharedPreferencesManager.getSavedExposureCompensation();
            Boolean aeLock = sharedPreferencesManager.isAeLockedSaved();
            int afModePref = sharedPreferencesManager.getSavedAfMode();

            // -------------- Fix Start (debug_saved_prefs)-----------
            Log.d(TAG, "applySavedCameraPrefsToBuilder: savedEv=" + savedEv + ", aeLock=" + aeLock +
                    ", afMode=" + afModePref);
            Log.d(TAG, "Runtime overrides: runtimeEv=" + runtimeExposureCompensation +
                    ", runtimeAeLock=" + runtimeAeLock + ", runtimeAfMode=" + runtimeAfMode);
            Log.d(TAG, "currentCameraCharacteristics available: " + (currentCameraCharacteristics != null));
            // -------------- Fix Ended (debug_saved_prefs)-----------

            if (currentCameraCharacteristics != null) {
                // Apply EV: use runtime value if available, otherwise saved value
                Range<Integer> range = currentCameraCharacteristics
                        .get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE);
                if (range != null) {
                    int evToUse = (runtimeExposureCompensation != null) ? runtimeExposureCompensation : savedEv;
                    int clamped = Math.max(range.getLower(), Math.min(range.getUpper(), evToUse));
                    builder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, clamped);
                    Log.d(TAG, "Applied " + (runtimeExposureCompensation != null ? "runtime" : "saved") +
                            " EV=" + clamped + " to request builder (range: " + range + ")");

                    // CRITICAL: Also apply EV to GL pipeline for visual effect
                    // Convert EV index to actual EV stops for GL shader
                    float evStops = 0.0f;
                    try {
                        android.util.Rational stepRational = currentCameraCharacteristics
                                .get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP);
                        if (stepRational != null) {
                            evStops = clamped * stepRational.floatValue();
                        } else {
                            // Fallback: assume 1/3 EV step (common default)
                            evStops = clamped * 0.33f;
                        }
                    } catch (Exception e) {
                        evStops = clamped * 0.33f; // Safe fallback
                    }

                    // Apply exposure through GL pipeline for immediate visual effect in preview and
                    // recording
                    if (glRecordingPipeline != null) {
                        glRecordingPipeline.setExposureCompensation(evStops);
                        Log.d(TAG, "Applied " + (runtimeExposureCompensation != null ? "runtime" : "saved") +
                                " EV to GL pipeline: index=" + clamped + " -> " + evStops + " EV stops");
                    }
                } else {
                    Log.w(TAG, "EV compensation range not available from camera characteristics");
                }

                // Apply AE lock: use runtime value if available, otherwise saved value
                Boolean aeLockSupported = currentCameraCharacteristics
                        .get(CameraCharacteristics.CONTROL_AE_LOCK_AVAILABLE);
                if (aeLockSupported != null && aeLockSupported) {
                    boolean lockToUse = (runtimeAeLock != null) ? runtimeAeLock : (aeLock != null && aeLock);
                    builder.set(CaptureRequest.CONTROL_AE_LOCK, lockToUse);
                    Log.d(TAG, "Applied " + (runtimeAeLock != null ? "runtime" : "saved") +
                            " AE lock=" + lockToUse + " to request builder");
                } else {
                    Log.w(TAG, "AE lock not supported by camera characteristics");
                }

                // Apply AF mode: use runtime value if available, otherwise saved value
                int[] modes = currentCameraCharacteristics.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES);
                if (modes != null && modes.length > 0) {
                    int afModeToUse = (runtimeAfMode != null) ? runtimeAfMode : afModePref;
                    boolean supported = false;
                    for (int m : modes)
                        if (m == afModeToUse) {
                            supported = true;
                            break;
                        }
                    if (supported) {
                        builder.set(CaptureRequest.CONTROL_AF_MODE, afModeToUse);
                        Log.d(TAG, "Applied " + (runtimeAfMode != null ? "runtime" : "saved") +
                                " AF mode=" + afModeToUse + " to request builder");
                    } else {
                        Log.w(TAG, "AF mode " + afModeToUse + " not supported, available modes: "
                                + java.util.Arrays.toString(modes));
                    }
                } else {
                    Log.w(TAG, "AF modes not available from camera characteristics");
                }
            } else {
                Log.e(TAG, "applySavedCameraPrefsToBuilder: currentCameraCharacteristics is null!");
            }
        } catch (Exception e) {
            Log.w(TAG, "Error applying camera prefs: " + e.getMessage());
        }
    }

    /**
     * Apply appropriate frame rate settings based on device type
     */
    private void applyFrameRateSettings(CaptureRequest.Builder builder, int targetFrameRate,
            CameraCharacteristics characteristics) {
        // Apply a safe AE target FPS range. Prefer exact [X,X], otherwise pick a
        // supported range including X
        android.util.Range<Integer> chosen = null;
        try {
            if (characteristics != null) {
                android.util.Range<Integer>[] ranges = characteristics
                        .get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
                if (ranges != null && ranges.length > 0) {
                    // Prefer exact constant range first
                    for (android.util.Range<Integer> r : ranges) {
                        if (r.getLower() == targetFrameRate && r.getUpper() == targetFrameRate) {
                            chosen = r;
                            break;
                        }
                    }
                    // Then any range that contains the target
                    if (chosen == null) {
                        for (android.util.Range<Integer> r : ranges) {
                            if (r.getLower() <= targetFrameRate && r.getUpper() >= targetFrameRate) {
                                chosen = r;
                                break;
                            }
                        }
                    }
                    // Finally, pick the closest upper bound
                    if (chosen == null) {
                        android.util.Range<Integer> best = ranges[0];
                        int bestDiff = Math.abs(best.getUpper() - targetFrameRate);
                        for (android.util.Range<Integer> r : ranges) {
                            int diff = Math.abs(r.getUpper() - targetFrameRate);
                            if (diff < bestDiff) {
                                best = r;
                                bestDiff = diff;
                            }
                        }
                        chosen = best;
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to choose AE FPS range from characteristics", e);
        }
        if (chosen == null) {
            chosen = new android.util.Range<>(targetFrameRate, targetFrameRate);
        }
        builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, chosen);
        Log.d(TAG, "Set CONTROL_AE_TARGET_FPS_RANGE to " + chosen);

        // Apply Samsung-specific keys if applicable
        // Do not apply Samsung vendor keys: unify behavior with standard devices
        // (Pixel-like)

        // Unified behavior: no Huawei/Samsung vendor keys; rely on AE range only.
    }

    /**
     * Apply zoom settings for the specified camera type
     */
    private void applyZoomSettings(CaptureRequest.Builder builder, CameraType cameraType) {
        // ----- Fix Start for this method(applyZoomSettings)-----
        // Get zoom ratio from settings for the specific camera type
        float zoomRatio = sharedPreferencesManager.getSpecificZoomRatio(cameraType);

        // Apply zoom ratio to the capture request
        builder.set(CaptureRequest.CONTROL_ZOOM_RATIO, zoomRatio);
        Log.d(TAG, "Applied zoom ratio " + zoomRatio + " for " + cameraType + " camera");
        // ----- Fix Ended for this method(applyZoomSettings)-----
    }

    /**
     * Previously showed toast messages for high frame rates
     * Now just logs the message as warnings are shown permanently in the settings
     * UI
     */
    private void showFrameRateToast(int frameRate) {
        // Frame rate warnings are now shown permanently in the settings UI
        // This method is kept to avoid refactoring all callers

        if (frameRate >= 60) {
            // -------------- Fix Start for this method(showFrameRateToast)-----------
            // Just log the high frame rate usage
            if (DeviceHelper.isSamsung()) {
                Log.d(TAG, "Using experimental " + frameRate + "fps mode for Samsung");
            } else {
                Log.d(TAG, "Using experimental " + frameRate + "fps mode");
            }
            // -------------- Fix Ended for this method(showFrameRateToast)-----------
        }
    }

    /**
     * Helper method to get the proper camera ID based on camera type
     */
    private String getCameraId(CameraManager cameraManager, CameraType cameraType) {
        if (cameraManager == null)
            return null;

        try {
            if (cameraType == CameraType.BACK) {
                // For back camera, use the selected back camera ID
                return sharedPreferencesManager.getSelectedBackCameraId();
            } else {
                // For front camera, find the first front-facing camera
                String[] cameraIds = cameraManager.getCameraIdList();
                for (String id : cameraIds) {
                    CameraCharacteristics chars = cameraManager.getCameraCharacteristics(id);
                    Integer facing = chars.get(CameraCharacteristics.LENS_FACING);
                    if (facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT) {
                        return id;
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error finding camera ID", e);
        }

        return null;
    }

    /**
     * Handle common tasks after any session is configured
     */
    private void handleSessionConfigured() {
        // Handle recording states
        if (recordingState == RecordingState.STARTING) {
            try {
                // Start the MediaRecorder

                recordingState = RecordingState.IN_PROGRESS;

                // Use SystemClock.elapsedRealtime() instead of System.currentTimeMillis() for
                // consistency with HomeFragment
                recordingStartTime = SystemClock.elapsedRealtime();
                Log.d(TAG, "Recording started with recordingStartTime=" + recordingStartTime);

                // Setup notification
                setupRecordingInProgressNotification();

                // Broadcast that recording has started
                broadcastOnRecordingStarted();

                Log.d(TAG, "Recording started successfully");
            } catch (Exception e) {
                Log.e(TAG, "Failed to start recording", e);
                stopRecording();
            }
        } else if (recordingState == RecordingState.IN_PROGRESS) {
            // This typically means the session was reconfigured
            Log.d(TAG, "Session reconfigured while recording is in progress");
            setupRecordingInProgressNotification();
        } else if (recordingState == RecordingState.PAUSED) {
            // Handle paused state
            Log.d(TAG, "Session configured while in PAUSED state");
            setupRecordingResumeNotification();
        }
    }

    // Add these fields to RecordingService class
    private GLRecordingPipeline glRecordingPipeline;
    private WatermarkInfoProvider watermarkInfoProvider;

    // Add this helper method for OpenGL pipeline direct output
    private File getFinalOutputFile() {
        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
        String segmentSuffix = ""; // No segment number for the initial file
        String baseFilename = Constants.RECORDING_DIRECTORY + "_" + timestamp + segmentSuffix + "."
                + Constants.RECORDING_FILE_EXTENSION;
        File videoDir = new File(getExternalFilesDir(null), Constants.RECORDING_DIRECTORY);
        if (!videoDir.exists() && !videoDir.mkdirs()) {
            Log.e(TAG, "Cannot create internal recording directory: " + videoDir.getAbsolutePath());
            Toast.makeText(this, "Error creating internal storage directory", Toast.LENGTH_LONG).show();
            return null;
        }
        return new File(videoDir, baseFilename);
    }

    // Inner class for OpenGL pipeline segment callback
    private class GLSegmentCallback implements com.fadcam.opengl.GLRecordingPipeline.SegmentCallback {
        @Override
        public void onSegmentRollover(int nextSegmentNumber) {
            // ----- Fix Start for this method(onSegmentRollover)-----
            Log.d(TAG, "GLSegmentCallback.onSegmentRollover called for segment " + nextSegmentNumber);
            String storageMode = sharedPreferencesManager.getStorageMode();
            VideoCodec selectedCodec = sharedPreferencesManager.getVideoCodec();
            if (SharedPreferencesManager.STORAGE_MODE_CUSTOM.equals(storageMode)) {
                Log.d(TAG, "Using custom storage mode (SAF) for segment rollover");
                // SAF: create new file and open new ParcelFileDescriptor
                String customUriString = sharedPreferencesManager.getCustomStorageUri();
                if (customUriString == null) {
                    Log.e(TAG, "Segment rollover: Custom storage selected but URI is null");
                    stopRecording();
                    return;
                }
                Uri treeUri = Uri.parse(customUriString);
                androidx.documentfile.provider.DocumentFile pickedDir = androidx.documentfile.provider.DocumentFile
                        .fromTreeUri(RecordingService.this, treeUri);
                if (pickedDir == null || !pickedDir.canWrite()) {
                    Log.e(TAG, "Segment rollover: Cannot write to selected custom directory");
                    stopRecording();
                    return;
                }
                String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
                String segmentSuffix = String.format(Locale.US, "_%03d", nextSegmentNumber);
                String baseFilename = Constants.RECORDING_DIRECTORY + "_" + timestamp + segmentSuffix + ".mp4";
                Log.d(TAG, "Creating new segment file: " + baseFilename);
                androidx.documentfile.provider.DocumentFile videoFile = pickedDir.createFile("video/mp4", baseFilename);
                if (videoFile == null) {
                    Log.e(TAG, "Segment rollover: Failed to create SAF file");
                    stopRecording();
                    return;
                }
                Uri safUri = videoFile.getUri();
                try (ParcelFileDescriptor pfd = getContentResolver().openFileDescriptor(safUri, "w")) {
                    if (pfd == null) {
                        Log.e(TAG, "Segment rollover: Failed to open ParcelFileDescriptor for SAF URI");
                        stopRecording();
                        return;
                    }
                    Log.d(TAG, "Successfully created new segment file with SAF: " + videoFile.getName());
                    if (glRecordingPipeline != null) {
                        glRecordingPipeline.setNextOutput(null, pfd.getFileDescriptor());
                        Log.d(TAG, "Set next output to file descriptor for segment " + nextSegmentNumber);
                    } else {
                        Log.e(TAG, "glRecordingPipeline is null, cannot set next output");
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Segment rollover: Exception opening PFD for SAF URI", e);
                    stopRecording();
                }
            } else {
                Log.d(TAG, "Using internal storage for segment rollover");
                // Internal: use createNextSegmentOutputFile()
                File nextFile = createNextSegmentOutputFile(nextSegmentNumber);
                if (nextFile == null) {
                    Log.e(TAG, "Segment rollover: Failed to create next segment file");
                    stopRecording();
                    return;
                }
                Log.d(TAG, "Successfully created new segment file: " + nextFile.getAbsolutePath());
                if (glRecordingPipeline != null) {
                    glRecordingPipeline.setNextOutput(nextFile.getAbsolutePath(), null);
                    Log.d(TAG, "Set next output to path: " + nextFile.getAbsolutePath() + " for segment "
                            + nextSegmentNumber);
                } else {
                    Log.e(TAG, "glRecordingPipeline is null, cannot set next output");
                }
            }
            // ----- Fix Ended for this method(onSegmentRollover)-----
        }
    }

    // Update startRecording to use new GLRecordingPipeline constructor
    private void startRecording() {
        // -------------- Fix Start for this method(startRecording)-----------
        Log.d(TAG, "startRecording: beginning recording setup");
        if (recordingState != RecordingState.STARTING) {
            Log.e(TAG, "startRecording was called but state is " + recordingState + ", expected STARTING");
            return;
        }

        // -------------- Fix Start (clear_runtime_overrides_on_startup)-----------
        // Clear runtime overrides so saved preferences take priority on fresh recording
        // session
        runtimeExposureCompensation = null;
        runtimeAeLock = null;
        runtimeAfMode = null;
        Log.d(TAG, "Cleared runtime camera overrides for fresh recording session");
        // -------------- Fix Ended (clear_runtime_overrides_on_startup)-----------

        createNotificationChannel();

        if (sharedPreferencesManager.isLocationEmbeddingEnabled()) {
            new Thread(() -> {
                try {
                    if (geotagHelper == null)
                        geotagHelper = new GeotagHelper(this);
                    geotagHelper.startUpdates();
                    Thread.sleep(800);
                } catch (Exception e) {
                    Log.e(TAG, "Error initializing GeotagHelper", e);
                }
            }).start();
        }

        try {
            if (ActivityCompat.checkSelfPermission(this,
                    Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED ||
                    ActivityCompat.checkSelfPermission(this,
                            Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                Log.e(TAG, "Permissions missing, cannot start recording.");
                Toast.makeText(this, "Permissions required for recording", Toast.LENGTH_LONG).show();
                recordingState = RecordingState.NONE;
                sharedPreferencesManager.setRecordingInProgress(false);
                stopSelf();
                return;
            }

            if (recordingWakeLock != null && !recordingWakeLock.isHeld())
                recordingWakeLock.acquire();

            watermarkInfoProvider = new WatermarkInfoProvider() {
                @Override
                public String getWatermarkText() {
                    String watermarkOption = sharedPreferencesManager.getWatermarkOption();
                    String locationText = sharedPreferencesManager.isLocalisationEnabled() ? getLocationData() : "";
                    switch (watermarkOption) {
                        case "timestamp_fadcam":
                            return "Captured by FadCam - " + getCurrentTimestamp() + locationText;
                        case "timestamp":
                            return getCurrentTimestamp() + locationText;
                        case "no_watermark":
                            return "";
                        default:
                            return "Captured by FadCam - " + getCurrentTimestamp() + locationText;
                    }
                }
            };

            Size resolution = sharedPreferencesManager.getCameraResolution();
            String orientation = sharedPreferencesManager.getVideoOrientation();
            int videoWidth = resolution.getWidth();
            int videoHeight = resolution.getHeight();

            // Get sensor orientation
            CameraManager cameraManager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
            CameraType cameraType = sharedPreferencesManager.getCameraSelection();
            String cameraId = getCameraId(cameraManager, cameraType);
            int sensorOrientation = 0;
            if (cameraId != null) {
                try {
                    CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(cameraId);
                    Integer so = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
                    if (so != null)
                        sensorOrientation = so;
                } catch (Exception e) {
                    Log.e(TAG, "Error getting sensor orientation", e);
                }
            }
            int videoBitrate = getVideoBitrate();
            // -------------- Fix Start: Use per-camera FPS for encoder --------------
            int videoFramerate = sharedPreferencesManager.getSpecificVideoFrameRate(cameraType);
            // -------------- Fix End: Use per-camera FPS for encoder --------------
            // ----- Fix Start for video splitting -----
            // Set splitSizeBytes to 0 if video splitting is disabled
            long splitSizeBytes = 0;
            if (sharedPreferencesManager.isVideoSplittingEnabled()) {
                splitSizeBytes = sharedPreferencesManager.getVideoSplitSizeBytes();
                Log.d(TAG, "Video splitting enabled with size: " + splitSizeBytes + " bytes");
            } else {
                Log.d(TAG, "Video splitting disabled");
            }
            // ----- Fix Ended for video splitting -----
            int initialSegmentNumber = 1;
            GLSegmentCallback segmentCallback = new GLSegmentCallback();

            Log.d(TAG, "Creating GLRecordingPipeline with: " +
                    "width=" + videoWidth + ", height=" + videoHeight +
                    ", bitrate=" + videoBitrate + ", framerate=" + videoFramerate +
                    ", orientation=" + orientation + ", sensorOrientation=" + sensorOrientation);

            String storageMode = sharedPreferencesManager.getStorageMode();
            VideoCodec selectedCodec = sharedPreferencesManager.getVideoCodec();
            if (SharedPreferencesManager.STORAGE_MODE_CUSTOM.equals(storageMode)) {
                String customUriString = sharedPreferencesManager.getCustomStorageUri();
                if (customUriString == null) {
                    Log.e(TAG, "Failed to open ParcelFileDescriptor for SAF URI");
                    Toast.makeText(this, "Failed to open file for writing", Toast.LENGTH_LONG).show();
                    stopSelf();
                    return;
                }
                Uri treeUri = Uri.parse(customUriString);
                androidx.documentfile.provider.DocumentFile pickedDir = androidx.documentfile.provider.DocumentFile
                        .fromTreeUri(this, treeUri);
                if (pickedDir == null || !pickedDir.canWrite()) {
                    Log.e(TAG, "Cannot write to selected custom directory");
                    Toast.makeText(this, "Cannot write to selected custom directory", Toast.LENGTH_LONG).show();
                    stopSelf();
                    return;
                }
                String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
                String baseFilename = Constants.RECORDING_DIRECTORY + "_" + timestamp + ".mp4";
                androidx.documentfile.provider.DocumentFile videoFile = pickedDir.createFile("video/mp4", baseFilename);
                if (videoFile == null) {
                    Log.e(TAG, "Failed to create SAF file");
                    Toast.makeText(this, "Failed to create file for writing", Toast.LENGTH_LONG).show();
                    stopSelf();
                    return;
                }
                Uri safUri = videoFile.getUri();
                // ----- Fix Start: Open PFD and keep it open for the duration of recording
                // -----
                safRecordingPfd = getContentResolver().openFileDescriptor(safUri, "w");
                if (safRecordingPfd == null) {
                    Log.e(TAG, "Failed to open ParcelFileDescriptor for SAF URI");
                    Toast.makeText(this, "Failed to open file for writing", Toast.LENGTH_LONG).show();
                    stopSelf();
                    return;
                }
                // Get location data for metadata embedding
                Float latitude = null;
                Float longitude = null;
                if (geotagHelper != null && geotagHelper.hasLocation()) {
                    android.location.Location location = geotagHelper.getCurrentLocation();
                    if (location != null) {
                        latitude = (float) location.getLatitude();
                        longitude = (float) location.getLongitude();
                        Log.d(TAG, "Using location for SAF recording: " + latitude + ", " + longitude);
                    }
                } else {
                    Log.d(TAG, "No location available for SAF recording metadata");
                }
                
                Log.d(TAG, "Creating GLRecordingPipeline with SAF file descriptor");
                glRecordingPipeline = new com.fadcam.opengl.GLRecordingPipeline(this, watermarkInfoProvider, videoWidth,
                        videoHeight, videoFramerate, safRecordingPfd.getFileDescriptor(), splitSizeBytes,
                        initialSegmentNumber, segmentCallback, previewSurface, orientation, sensorOrientation,
                        selectedCodec, latitude, longitude);
                // ----- Fix End: Open PFD and keep it open for the duration of recording -----
            } else {
                // Get location data for metadata embedding
                Float latitude = null;
                Float longitude = null;
                if (geotagHelper != null && geotagHelper.hasLocation()) {
                    android.location.Location location = geotagHelper.getCurrentLocation();
                    if (location != null) {
                        latitude = (float) location.getLatitude();
                        longitude = (float) location.getLongitude();
                        Log.d(TAG, "Using location for internal recording: " + latitude + ", " + longitude);
                    }
                } else {
                    Log.d(TAG, "No location available for internal recording metadata");
                }
                
                File outputFile = getFinalOutputFile();
                Log.d(TAG, "Creating GLRecordingPipeline with internal file: " + outputFile.getAbsolutePath());
                glRecordingPipeline = new com.fadcam.opengl.GLRecordingPipeline(this, watermarkInfoProvider, videoWidth,
                        videoHeight, videoFramerate, outputFile.getAbsolutePath(), splitSizeBytes, initialSegmentNumber,
                        segmentCallback, previewSurface, orientation, sensorOrientation, selectedCodec, latitude, longitude);
            }

            Log.d(TAG, "Preparing GLRecordingPipeline surfaces");
            glRecordingPipeline.prepareSurfaces();

            Log.d(TAG, "Creating camera preview session");
            createCameraPreviewSession();

            Log.d(TAG, "Recording setup complete");
        } catch (Exception e) {
            Log.e(TAG, "CRITICAL FAILURE IN startRecording", e);

            // Broadcast the failure to the UI with details
            java.io.StringWriter sw = new java.io.StringWriter();
            java.io.PrintWriter pw = new java.io.PrintWriter(sw);
            e.printStackTrace(pw);
            String stackTrace = sw.toString();

            Intent failureIntent = new Intent(Constants.ACTION_RECORDING_FAILED);
            failureIntent.putExtra(Constants.EXTRA_ERROR_MESSAGE, e.getMessage());
            failureIntent.putExtra(Constants.EXTRA_STACK_TRACE, stackTrace);
            try {
                sendBroadcast(failureIntent);
            } catch (Throwable t) {
                Log.e(TAG, "Failed to broadcast recording failure", t);
            }

            // Clean up and stop the service
            recordingState = RecordingState.NONE;
            sharedPreferencesManager.setRecordingInProgress(false);
            stopRecording();
        }
        // -------------- Fix Ended for this method(startRecording) -----------
    }

    // Add this field to the class
    private Surface dummyBackgroundSurface = null; // Used as fallback when app is backgrounded
    private SurfaceTexture dummySurfaceTexture = null; // Used to create dummy surface

    // Add this method to create a dummy surface
    private void createDummyBackgroundSurface() {
        // Release any existing dummy resources
        releaseDummyBackgroundSurface();

        try {
            // Create a 1x1 SurfaceTexture (minimal size/resources)
            dummySurfaceTexture = new SurfaceTexture(0);
            dummySurfaceTexture.setDefaultBufferSize(1, 1);
            dummyBackgroundSurface = new Surface(dummySurfaceTexture);

            Log.d(TAG, "Created dummy background surface to prevent green screen on Samsung");
        } catch (Exception e) {
            Log.e(TAG, "Failed to create dummy background surface", e);
            dummySurfaceTexture = null;
            dummyBackgroundSurface = null;
        }
    }

    // Add this method to release the dummy surface
    private void releaseDummyBackgroundSurface() {
        if (dummyBackgroundSurface != null) {
            try {
                dummyBackgroundSurface.release();
            } catch (Exception e) {
                Log.e(TAG, "Error releasing dummyBackgroundSurface", e);
            } finally {
                dummyBackgroundSurface = null;
            }
        }

        if (dummySurfaceTexture != null) {
            try {
                dummySurfaceTexture.release();
            } catch (Exception e) {
                Log.e(TAG, "Error releasing dummySurfaceTexture", e);
            } finally {
                dummySurfaceTexture = null;
            }
        }
    }

    // ----- Fix Start for camera interruption handling -----
    // Flag to track if we need to automatically resume recording after camera
    // interruption
    private boolean pendingCameraReconnect = false;
    private static final long RECONNECT_RETRY_DELAY_MS = 2000; // 2 seconds between reconnection attempts
    private Handler reconnectHandler = new Handler(Looper.getMainLooper());
    private Runnable reconnectRunnable;
    // ----- Fix End for camera interruption handling -----

    // ----- Fix Start for camera interruption handling -----
    /**
     * Starts periodic attempts to reconnect to the camera
     * 
     * @param cameraId The ID of the camera to reconnect to
     */
    private void startCameraReconnectionAttempts(String cameraId) {
        if (reconnectRunnable != null) {
            reconnectHandler.removeCallbacks(reconnectRunnable);
        }
        reconnectRunnable = new Runnable() {
            @Override
            public void run() {
                if (!pendingCameraReconnect) {
                    Log.d(TAG, "Camera reconnection attempts stopped by flag");
                    return;
                }
                tryReconnectCamera(cameraId);
                // Always schedule the next attempt as long as pendingCameraReconnect is true
                if (pendingCameraReconnect) {
                    reconnectHandler.postDelayed(this, RECONNECT_RETRY_DELAY_MS);
                }
            }
        };
        reconnectHandler.post(reconnectRunnable);
        Log.d(TAG, "Started camera reconnection attempts (infinite)");
    }

    /**
     * Helper method to check if a camera ID is a physical camera from a logical
     * back camera
     * This is needed because physical cameras might not have LENS_FACING_BACK set
     * properly
     * 
     * @param cameraId       The camera ID to check
     * @param basicCameraIds Array of basic camera IDs from getCameraIdList()
     * @param cameraManager  The camera manager instance
     * @return true if this is a physical camera from a logical back camera
     */
    @RequiresApi(api = Build.VERSION_CODES.P)
    private boolean isPhysicalBackCamera(String cameraId, String[] basicCameraIds, CameraManager cameraManager) {
        try {
            // Check if this camera ID is a physical camera from any logical back camera
            for (String logicalId : basicCameraIds) {
                CameraCharacteristics logicalChars = cameraManager.getCameraCharacteristics(logicalId);
                Integer logicalFacing = logicalChars.get(CameraCharacteristics.LENS_FACING);

                // Only check logical cameras that are back-facing
                if (logicalFacing != null && logicalFacing == CameraCharacteristics.LENS_FACING_BACK) {
                    Set<String> physicalIds = logicalChars.getPhysicalCameraIds();
                    if (physicalIds != null && physicalIds.contains(cameraId)) {
                        Log.d(TAG, "Camera ID '" + cameraId + "' is a physical camera from logical back camera '"
                                + logicalId + "'");
                        return true;
                    }
                }
            }
        } catch (CameraAccessException e) {
            Log.e(TAG, "Error checking if camera " + cameraId + " is a physical back camera", e);
        }
        return false;
    }

    /**
     * Attempts to reconnect to the camera
     * 
     * @param cameraId The ID of the camera to reconnect to
     */
    private void tryReconnectCamera(String cameraId) {
        try {
            Log.d(TAG, "Attempting to reconnect camera: " + cameraId);
            cameraManager.openCamera(cameraId, cameraStateCallback, backgroundHandler);
        } catch (Exception e) {
            Log.e(TAG, "Error reconnecting to camera", e);
        }
    }
    // ----- Fix End for camera interruption handling -----

    /**
     * Handles camera interruptions by continuing to record with black frames.
     * This is called when the camera is disconnected or encounters an error during
     * recording.
     */
    private void handleCameraInterruption() {
        // ----- Fix Start for this method(handleCameraInterruption)-----
        if (recordingState != RecordingState.IN_PROGRESS) {
            Log.w(TAG,
                    "handleCameraInterruption called but not in IN_PROGRESS state, current state: " + recordingState);
            return;
        }

        Log.i(TAG, "Camera interrupted during recording - switching to black frame mode");

        // Save the current camera ID for reconnection
        String cameraToReconnect = null;
        try {
            CameraType cameraType = sharedPreferencesManager.getCameraSelection();
            cameraToReconnect = getCameraId(cameraManager, cameraType);
            if (cameraToReconnect == null) {
                Log.e(TAG, "Could not determine camera ID for reconnection");
                cameraToReconnect = "0"; // Default to first camera
            }
        } catch (Exception e) {
            Log.e(TAG, "Error determining camera ID for reconnection", e);
            cameraToReconnect = "0"; // Default to first camera
        }

        // Update recording state to a special state
        recordingState = RecordingState.WAITING_FOR_CAMERA;

        // Show notification about camera interruption
        setupCameraInterruptionNotification();

        // Start camera reconnection attempts
        pendingCameraReconnect = true;

        // Start a thread to render black frames while the camera is unavailable
        startBlackFrameRendering();

        // Start camera reconnection attempts
        startCameraReconnectionAttempts(cameraToReconnect);

        Log.i(TAG, "Recording continuing with black frames, attempting camera reconnection");
        // ----- Fix Ended for this method(handleCameraInterruption)-----
    }

    private Handler blackFrameHandler;
    private HandlerThread blackFrameThread;
    private boolean isRenderingBlackFrames = false;
    private static final int BLACK_FRAME_INTERVAL_MS = 33; // ~30fps

    /**
     * Starts rendering black frames to keep the recording going when camera is
     * unavailable.
     */
    private void startBlackFrameRendering() {
        if (isRenderingBlackFrames) {
            Log.d(TAG, "Already rendering black frames");
            return;
        }

        if (glRecordingPipeline == null) {
            Log.e(TAG, "Cannot start black frame rendering - pipeline is null");
            return;
        }

        // Create a dedicated thread for rendering black frames
        blackFrameThread = new HandlerThread("BlackFrameRenderer");
        blackFrameThread.start();
        blackFrameHandler = new Handler(blackFrameThread.getLooper());

        isRenderingBlackFrames = true;

        // Create a runnable that renders black frames at regular intervals
        Runnable blackFrameRunnable = new Runnable() {
            @Override
            public void run() {
                if (!isRenderingBlackFrames || recordingState != RecordingState.WAITING_FOR_CAMERA) {
                    Log.d(TAG, "Stopping black frame rendering");
                    stopBlackFrameRendering();
                    return;
                }

                // Render a black frame - don't try to recreate renderer if it fails
                if (glRecordingPipeline != null) {
                    try {
                        glRecordingPipeline.renderBlackFrame();
                    } catch (Exception e) {
                        // Expected during camera disconnection - log at debug level
                        Log.d(TAG, "Expected exception rendering black frame during camera disconnection");
                    }
                }

                // Schedule the next frame - even if this one failed
                // The video will just have some dropped frames, which is better than stopping
                if (isRenderingBlackFrames && blackFrameHandler != null) {
                    blackFrameHandler.postDelayed(this, BLACK_FRAME_INTERVAL_MS);
                }
            }
        };

        // Start rendering black frames
        blackFrameHandler.post(blackFrameRunnable);
        Log.d(TAG, "Started rendering black frames");
    }

    /**
     * Stops rendering black frames.
     */
    private void stopBlackFrameRendering() {
        // Set flag first to prevent new frames from being scheduled
        isRenderingBlackFrames = false;
        Log.d(TAG, "Stopping black frame rendering");

        // Cancel all pending messages in handler
        if (blackFrameHandler != null) {
            blackFrameHandler.removeCallbacksAndMessages(null);
        }

        // Stop the thread safely
        final HandlerThread threadToCleanup = blackFrameThread; // Local reference for cleanup
        blackFrameThread = null; // Clear reference immediately to prevent new usage

        // Clean up the thread on a background thread to avoid blocking
        new Thread(() -> {
            try {
                if (threadToCleanup != null) {
                    threadToCleanup.quitSafely();
                    try {
                        // Wait with timeout for thread to exit
                        threadToCleanup.join(1000);
                    } catch (InterruptedException e) {
                        Log.w(TAG, "Interrupted while waiting for black frame thread to exit", e);
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "Error cleaning up black frame thread", e);
            }
        }, "BlackFrameCleanupThread").start();

        // Clear handler reference
        blackFrameHandler = null;

        Log.d(TAG, "Stopped rendering black frames");
    }

    /**
     * Sets up a notification to inform the user that recording is continuing with
     * black frames
     * due to camera interruption.
     */
    private void setupCameraInterruptionNotification() {
        // ----- Fix Start for this method(setupCameraInterruptionNotification)-----
        if (ActivityCompat.checkSelfPermission(this,
                android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "POST_NOTIFICATIONS permission not granted, skipping notification update.");
            return;
        }

        NotificationCompat.Builder builder = createBaseNotificationBuilder()
                .setContentTitle(getString(R.string.camera_interrupted_title))
                .setContentText(getString(R.string.camera_interrupted_description))
                .setStyle(new NotificationCompat.BigTextStyle()
                        .bigText(getString(R.string.camera_interrupted_description)))
                .clearActions();

        // Add stop action
        boolean hideStopButton = sharedPreferencesManager.isNotificationStopButtonHidden();
        if (!hideStopButton) {
            builder.addAction(new NotificationCompat.Action(
                    R.drawable.ic_stop,
                    getString(R.string.button_stop),
                    createStopRecordingIntent()));
        }

        NotificationManagerCompat notificationManager = NotificationManagerCompat.from(this);
        notificationManager.notify(NOTIFICATION_ID, builder.build());
        Log.d(TAG, "Camera interruption notification displayed");
        // ----- Fix Ended for this method(setupCameraInterruptionNotification)-----
    }

    /**
     * Stops any ongoing camera reconnection attempts.
     */
    private void stopReconnectionAttempts() {
        pendingCameraReconnect = false;
        if (reconnectHandler != null && reconnectRunnable != null) {
            reconnectHandler.removeCallbacks(reconnectRunnable);
        }
        Log.d(TAG, "Camera reconnection attempts stopped");
    }

    // Add this field to the class
    private ParcelFileDescriptor safRecordingPfd = null;
}