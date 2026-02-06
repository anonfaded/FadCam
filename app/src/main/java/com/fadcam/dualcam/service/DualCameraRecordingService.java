package com.fadcam.dualcam.service;

import android.Manifest;
import android.app.Notification;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;
import android.util.Size;
import android.view.Surface;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.fadcam.Constants;
import com.fadcam.R;
import com.fadcam.SharedPreferencesManager;
import com.fadcam.dualcam.DualCameraCapability;
import com.fadcam.dualcam.DualCameraConfig;
import com.fadcam.dualcam.DualCameraState;
import com.fadcam.dualcam.pipeline.DualCameraPipeline;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;
import java.util.Collections;

/**
 * Foreground service for dual camera (PiP) recording.
 *
 * <p>Manages <b>two</b> {@link CameraDevice} instances (front + back) simultaneously
 * and delegates compositing/encoding to {@link DualCameraPipeline}.
 *
 * <h3>Intent Actions</h3>
 * <ul>
 *   <li>{@link Constants#INTENT_ACTION_START_DUAL_RECORDING}</li>
 *   <li>{@link Constants#INTENT_ACTION_STOP_DUAL_RECORDING}</li>
 *   <li>{@link Constants#INTENT_ACTION_PAUSE_DUAL_RECORDING}</li>
 *   <li>{@link Constants#INTENT_ACTION_RESUME_DUAL_RECORDING}</li>
 *   <li>{@link Constants#INTENT_ACTION_SWAP_DUAL_CAMERAS}</li>
 *   <li>{@link Constants#INTENT_ACTION_UPDATE_PIP_CONFIG}</li>
 * </ul>
 *
 * <h3>Broadcasts</h3>
 * <ul>
 *   <li>{@link Constants#BROADCAST_ON_DUAL_RECORDING_STARTED}</li>
 *   <li>{@link Constants#BROADCAST_ON_DUAL_RECORDING_STOPPED}</li>
 *   <li>{@link Constants#BROADCAST_ON_DUAL_RECORDING_PAUSED}</li>
 *   <li>{@link Constants#BROADCAST_ON_DUAL_RECORDING_RESUMED}</li>
 *   <li>{@link Constants#BROADCAST_ON_DUAL_CAMERA_ERROR}</li>
 *   <li>{@link Constants#BROADCAST_ON_DUAL_CAMERAS_SWAPPED}</li>
 * </ul>
 */
public class DualCameraRecordingService extends Service {

    private static final String TAG = "DualCamService";

    // Reuse RecordingService notification channel for consistent UX
    private static final String CHANNEL_ID = "RecordingServiceChannel";
    private static final int NOTIFICATION_ID = 2; // Different ID from RecordingService (1)

    // ── Camera fields ──────────────────────────────────────────────────

    private CameraManager cameraManager;

    /** Primary camera — fills the full frame. */
    private CameraDevice primaryCameraDevice;
    private CameraCaptureSession primarySession;
    private CaptureRequest.Builder primaryRequestBuilder;

    /** Secondary camera — rendered in PiP overlay. */
    private CameraDevice secondaryCameraDevice;
    private CameraCaptureSession secondarySession;
    private CaptureRequest.Builder secondaryRequestBuilder;

    /** Resolved camera IDs from {@link DualCameraCapability}. */
    private String frontCameraId;
    private String backCameraId;

    // ── Pipeline ───────────────────────────────────────────────────────

    private DualCameraPipeline dualPipeline;

    // ── State ──────────────────────────────────────────────────────────

    private volatile DualCameraState state = DualCameraState.DISABLED;
    private DualCameraConfig config;
    private long recordingStartTime;

    // ── Threading ──────────────────────────────────────────────────────

    private HandlerThread backgroundThread;
    private Handler backgroundHandler;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // ── System ─────────────────────────────────────────────────────────

    private SharedPreferencesManager prefs;
    private DualCameraCapability capability;
    private PowerManager.WakeLock wakeLock;

    // Guard against duplicate open/close races
    private volatile boolean isStopping = false;
    private int camerasOpened = 0; // Track how many cameras have opened successfully

    /**
     * Fallback mode flag: when the device cannot open both cameras simultaneously,
     * we record with only the primary camera streaming continuously, and periodically
     * open the secondary camera to capture a single frame for the PiP overlay.
     */
    private volatile boolean fallbackMode = false;

    /** The resolved secondary camera ID — stored for use in fallback periodic snapshots. */
    private String resolvedSecondaryId;

    // ════════════════════════════════════════════════════════════════════
    // LIFECYCLE
    // ════════════════════════════════════════════════════════════════════

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "onCreate");

        prefs = SharedPreferencesManager.getInstance(getApplicationContext());
        capability = new DualCameraCapability(this);
        cameraManager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);

        if (cameraManager == null) {
            Log.e(TAG, "CameraManager unavailable — cannot start dual camera service");
            stopSelf();
            return;
        }

        startBackgroundThread();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || intent.getAction() == null) {
            Log.w(TAG, "onStartCommand: null intent/action");
            return START_STICKY;
        }

        String action = intent.getAction();
        Log.d(TAG, "onStartCommand: action=" + action);

        switch (action) {
            case Constants.INTENT_ACTION_START_DUAL_RECORDING:
                handleStartDualRecording();
                break;

            case Constants.INTENT_ACTION_STOP_DUAL_RECORDING:
                handleStopDualRecording();
                break;

            case Constants.INTENT_ACTION_PAUSE_DUAL_RECORDING:
                handlePauseDualRecording();
                break;

            case Constants.INTENT_ACTION_RESUME_DUAL_RECORDING:
                handleResumeDualRecording();
                break;

            case Constants.INTENT_ACTION_SWAP_DUAL_CAMERAS:
                handleSwapCameras();
                break;

            case Constants.INTENT_ACTION_UPDATE_PIP_CONFIG:
                handleUpdatePipConfig();
                break;

            default:
                Log.w(TAG, "Unknown action: " + action);
                break;
        }

        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        Log.d(TAG, "onDestroy");
        releaseAllResources();
        stopBackgroundThread();
        releaseWakeLock();
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null; // Not a bound service
    }

    // ════════════════════════════════════════════════════════════════════
    // ACTION HANDLERS
    // ════════════════════════════════════════════════════════════════════

    /**
     * Start dual camera recording.
     * 1. Validate permissions + capability
     * 2. Show foreground notification
     * 3. Open both cameras sequentially
     * 4. Create pipeline + start encoding
     */
    private void handleStartDualRecording() {
        if (state != DualCameraState.DISABLED) {
            Log.w(TAG, "Cannot start dual recording — state=" + state);
            return;
        }

        // ── Permission check ──────────────────────────────────────────
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED
                || ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "Camera or audio permission not granted");
            broadcastError("Permissions required for dual camera recording");
            stopSelf();
            return;
        }

        // ── Capability check ──────────────────────────────────────────
        if (!capability.isSupported()) {
            Log.e(TAG, "Dual camera not supported: " + capability.getUnsupportedReason());
            broadcastError(capability.getUnsupportedReason());
            stopSelf();
            return;
        }

        frontCameraId = capability.getConcurrentFrontCameraId();
        backCameraId = capability.getConcurrentBackCameraId();

        if (frontCameraId == null || backCameraId == null) {
            Log.e(TAG, "Could not resolve front/back camera IDs");
            broadcastError("Could not identify concurrent cameras");
            stopSelf();
            return;
        }

        // ── Load config ───────────────────────────────────────────────
        config = prefs.getDualCameraConfig();
        state = DualCameraState.INITIALIZING;
        isStopping = false;
        camerasOpened = 0;

        // ── Foreground notification ───────────────────────────────────
        startForegroundNotification();

        // ── Acquire wake lock ─────────────────────────────────────────
        acquireWakeLock();

        // ── Open cameras ──────────────────────────────────────────────
        openBothCameras();
    }

    /**
     * Stop dual camera recording and clean up all resources.
     */
    private void handleStopDualRecording() {
        if (state == DualCameraState.DISABLED) {
            Log.w(TAG, "Already stopped / disabled");
            stopSelf();
            return;
        }

        Log.i(TAG, "Stopping dual camera recording");
        isStopping = true;
        state = DualCameraState.DISABLED;
        fallbackMode = false;
        isCapturingSnapshot = false;

        // Stop pipeline first (drains encoders, finalises muxer)
        if (dualPipeline != null) {
            try {
                dualPipeline.stopRecording();
            } catch (Exception e) {
                Log.e(TAG, "Error stopping pipeline", e);
            }
            dualPipeline = null;
        }

        // Close cameras
        closeCamera(primarySession, primaryCameraDevice, "primary");
        closeCamera(secondarySession, secondaryCameraDevice, "secondary");
        primarySession = null;
        primaryCameraDevice = null;
        secondarySession = null;
        secondaryCameraDevice = null;

        prefs.setRecordingInProgress(false);
        releaseWakeLock();

        broadcastAction(Constants.BROADCAST_ON_DUAL_RECORDING_STOPPED);
        stopSelf();
    }

    private void handlePauseDualRecording() {
        if (state != DualCameraState.RECORDING) {
            Log.w(TAG, "Cannot pause — state=" + state);
            return;
        }

        if (dualPipeline != null) {
            dualPipeline.pauseRecording();
        }
        state = DualCameraState.PAUSED;
        broadcastAction(Constants.BROADCAST_ON_DUAL_RECORDING_PAUSED);
        Log.i(TAG, "Dual recording paused");
    }

    private void handleResumeDualRecording() {
        if (state != DualCameraState.PAUSED) {
            Log.w(TAG, "Cannot resume — state=" + state);
            return;
        }

        if (dualPipeline != null) {
            dualPipeline.resumeRecording();
        }
        state = DualCameraState.RECORDING;
        broadcastAction(Constants.BROADCAST_ON_DUAL_RECORDING_RESUMED);
        Log.i(TAG, "Dual recording resumed");
    }

    /**
     * Swap primary ↔ secondary cameras without stopping recording.
     * The pipeline swaps which texture is rendered full-screen vs PiP.
     */
    private void handleSwapCameras() {
        if (state != DualCameraState.RECORDING && state != DualCameraState.PAUSED) {
            Log.w(TAG, "Cannot swap cameras — state=" + state);
            return;
        }

        // Toggle primary in config
        DualCameraConfig.PrimaryCamera newPrimary =
                (config.getPrimaryCamera() == DualCameraConfig.PrimaryCamera.BACK)
                        ? DualCameraConfig.PrimaryCamera.FRONT
                        : DualCameraConfig.PrimaryCamera.BACK;

        config = new DualCameraConfig.Builder(config)
                .primaryCamera(newPrimary)
                .build();
        prefs.saveDualCameraConfig(config);

        // Tell pipeline to swap rendering order
        if (dualPipeline != null) {
            dualPipeline.swapCameras();
        }

        broadcastAction(Constants.BROADCAST_ON_DUAL_CAMERAS_SWAPPED);
        Log.i(TAG, "Cameras swapped — new primary: " + newPrimary);
    }

    /**
     * Hot-update PiP configuration (position, size, border) without restarting recording.
     */
    private void handleUpdatePipConfig() {
        config = prefs.getDualCameraConfig();
        if (dualPipeline != null) {
            dualPipeline.updateConfig(config);
        }
        Log.d(TAG, "PiP config updated live");
    }

    // ════════════════════════════════════════════════════════════════════
    // CAMERA OPENING
    // ════════════════════════════════════════════════════════════════════

    /**
     * Opens both cameras <b>sequentially</b> — primary first, then secondary
     * only after the primary is confirmed open. This improves compatibility
     * on devices that do not officially support concurrent camera streams.
     */
    private void openBothCameras() {
        // Determine which physical camera is primary based on config
        String primaryId = (config.getPrimaryCamera() == DualCameraConfig.PrimaryCamera.BACK)
                ? backCameraId : frontCameraId;
        String secondaryId = (config.getPrimaryCamera() == DualCameraConfig.PrimaryCamera.BACK)
                ? frontCameraId : backCameraId;

        Log.d(TAG, "Opening primary camera: " + primaryId + ", secondary: " + secondaryId);
        resolvedSecondaryId = secondaryId;

        try {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                    != PackageManager.PERMISSION_GRANTED) {
                Log.e(TAG, "Camera permission denied");
                transitionToError("Camera permission denied");
                return;
            }

            // Step 1: Open primary camera first
            cameraManager.openCamera(primaryId, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(@NonNull CameraDevice camera) {
                    if (isStopping) {
                        camera.close();
                        return;
                    }
                    Log.d(TAG, "Primary camera opened: " + camera.getId());
                    primaryCameraDevice = camera;

                    // Step 2: Open secondary camera AFTER primary is confirmed open
                    // Small delay helps on devices with shared camera hardware pipelines
                    backgroundHandler.postDelayed(() -> openSecondaryCamera(secondaryId), 300);
                }

                @Override
                public void onDisconnected(@NonNull CameraDevice camera) {
                    Log.w(TAG, "Primary camera disconnected: " + camera.getId());
                    camera.close();
                    primaryCameraDevice = null;
                    transitionToError("Primary camera disconnected");
                }

                @Override
                public void onError(@NonNull CameraDevice camera, int error) {
                    Log.e(TAG, "Primary camera error: " + error);
                    camera.close();
                    primaryCameraDevice = null;
                    transitionToError("Primary camera error (code " + error + ")");
                }
            }, backgroundHandler);

        } catch (CameraAccessException | SecurityException e) {
            Log.e(TAG, "Error opening primary camera", e);
            transitionToError("Failed to open camera: " + e.getMessage());
        }
    }

    /**
     * Opens the secondary (PiP) camera after the primary is already open.
     */
    private void openSecondaryCamera(@NonNull String secondaryId) {
        if (isStopping || primaryCameraDevice == null) {
            Log.w(TAG, "openSecondaryCamera: Aborting — stopping=" + isStopping);
            return;
        }

        try {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                    != PackageManager.PERMISSION_GRANTED) {
                transitionToError("Camera permission denied");
                return;
            }

            cameraManager.openCamera(secondaryId, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(@NonNull CameraDevice camera) {
                    if (isStopping) {
                        camera.close();
                        return;
                    }
                    Log.d(TAG, "Secondary camera opened: " + camera.getId());
                    secondaryCameraDevice = camera;
                    // Both cameras now open — proceed to pipeline setup
                    camerasOpened = 2;
                    onCameraOpened();
                }

                @Override
                public void onDisconnected(@NonNull CameraDevice camera) {
                    Log.w(TAG, "Secondary camera disconnected: " + camera.getId());
                    camera.close();
                    secondaryCameraDevice = null;
                    transitionToError("Secondary camera disconnected");
                }

                @Override
                public void onError(@NonNull CameraDevice camera, int error) {
                    Log.w(TAG, "Secondary camera error: " + error
                            + " — falling back to periodic snapshot mode");
                    camera.close();
                    secondaryCameraDevice = null;

                    // ── FALLBACK: device cannot open both cameras simultaneously ──
                    // Record with primary camera only; periodically snapshot the
                    // secondary camera for the PiP overlay.
                    fallbackMode = true;
                    onPrimaryCameraReadyForFallback();
                }
            }, backgroundHandler);

        } catch (CameraAccessException | SecurityException e) {
            Log.e(TAG, "Error opening secondary camera", e);
            transitionToError("Failed to open secondary camera: " + e.getMessage());
        }
    }

    /**
     * Called when both cameras are confirmed open. Validates state and
     * proceeds to create the recording pipeline.
     */
    private synchronized void onCameraOpened() {
        Log.d(TAG, "onCameraOpened — both cameras ready, camerasOpened=" + camerasOpened);

        if (primaryCameraDevice == null || secondaryCameraDevice == null) {
            Log.e(TAG, "One of the cameras is null despite both reported open");
            transitionToError("Camera initialization failed");
            return;
        }

        // Both cameras ready — build pipeline and start recording
        startDualRecording();
    }

    /**
     * Called when the secondary camera cannot be opened concurrently.
     * Starts recording with only the primary camera and schedules periodic
     * snapshots from the secondary camera to keep the PiP overlay updating.
     */
    private void onPrimaryCameraReadyForFallback() {
        Log.i(TAG, "⚡ Entering fallback mode — primary-only recording with periodic PiP snapshots");

        if (primaryCameraDevice == null) {
            Log.e(TAG, "Primary camera is null in fallback mode");
            transitionToError("Camera initialization failed");
            return;
        }

        // Proceed to set up pipeline with primary camera only.
        // The pipeline still creates both SurfaceTextures (primary + secondary),
        // but only the primary receives a continuous camera stream.
        startDualRecording();

        // Schedule periodic secondary camera snapshots after pipeline is ready.
        // Delay the first snapshot to let the pipeline stabilise.
        backgroundHandler.postDelayed(this::captureSecondarySnapshot, 2000);
    }

    // ── Fallback: periodic secondary camera snapshot ──────────────────

    /** Interval between PiP snapshot updates in fallback mode (ms). */
    private static final long FALLBACK_SNAPSHOT_INTERVAL_MS = 3000;

    /** Flag to prevent overlapping snapshot attempts. */
    private volatile boolean isCapturingSnapshot = false;

    /**
     * Opens the secondary camera, captures a single frame to the pipeline's
     * secondary SurfaceTexture, then closes it. Reschedules itself.
     */
    private void captureSecondarySnapshot() {
        if (isStopping || state == DualCameraState.DISABLED || state == DualCameraState.ERROR) {
            return;
        }
        if (isCapturingSnapshot) {
            // Previous snapshot still in progress — skip and retry later
            backgroundHandler.postDelayed(this::captureSecondarySnapshot, FALLBACK_SNAPSHOT_INTERVAL_MS);
            return;
        }

        isCapturingSnapshot = true;
        String secId = resolvedSecondaryId;
        if (secId == null || dualPipeline == null) {
            isCapturingSnapshot = false;
            return;
        }

        Log.d(TAG, "Fallback: capturing PiP snapshot from camera " + secId);

        try {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                    != PackageManager.PERMISSION_GRANTED) {
                isCapturingSnapshot = false;
                return;
            }

            cameraManager.openCamera(secId, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(@NonNull CameraDevice camera) {
                    if (isStopping) {
                        camera.close();
                        isCapturingSnapshot = false;
                        return;
                    }
                    Log.d(TAG, "Fallback: secondary camera opened for snapshot");
                    captureOneFrameAndClose(camera);
                }

                @Override
                public void onDisconnected(@NonNull CameraDevice camera) {
                    camera.close();
                    isCapturingSnapshot = false;
                    scheduleNextSnapshot();
                }

                @Override
                public void onError(@NonNull CameraDevice camera, int error) {
                    Log.w(TAG, "Fallback: secondary camera snapshot error: " + error);
                    camera.close();
                    isCapturingSnapshot = false;
                    scheduleNextSnapshot();
                }
            }, backgroundHandler);

        } catch (CameraAccessException | SecurityException e) {
            Log.w(TAG, "Fallback: cannot open secondary camera for snapshot", e);
            isCapturingSnapshot = false;
            scheduleNextSnapshot();
        }
    }

    /**
     * Captures a single frame from the given camera device targeting the
     * pipeline's secondary SurfaceTexture, then closes the camera.
     */
    private void captureOneFrameAndClose(@NonNull CameraDevice camera) {
        try {
            Surface secondarySurface = dualPipeline.getSecondaryCameraInputSurface();

            CaptureRequest.Builder builder =
                    camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            builder.addTarget(secondarySurface);
            builder.set(CaptureRequest.CONTROL_AF_MODE,
                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO);
            builder.set(CaptureRequest.CONTROL_AE_MODE,
                    CaptureRequest.CONTROL_AE_MODE_ON);

            camera.createCaptureSession(
                    Collections.singletonList(secondarySurface),
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(@NonNull CameraCaptureSession session) {
                            if (isStopping) {
                                session.close();
                                camera.close();
                                isCapturingSnapshot = false;
                                return;
                            }

                            try {
                                // Capture a few frames to let AE/AF settle, then close
                                builder.set(CaptureRequest.CONTROL_AF_TRIGGER,
                                        CaptureRequest.CONTROL_AF_TRIGGER_START);
                                session.capture(builder.build(),
                                        new CameraCaptureSession.CaptureCallback() {
                                            private int framesReceived = 0;

                                            @Override
                                            public void onCaptureCompleted(
                                                    @NonNull CameraCaptureSession s,
                                                    @NonNull CaptureRequest request,
                                                    @NonNull android.hardware.camera2.TotalCaptureResult result) {
                                                framesReceived++;
                                                if (framesReceived >= 1) {
                                                    // Got our frame — close and schedule next
                                                    Log.d(TAG, "Fallback: PiP snapshot captured");
                                                    session.close();
                                                    camera.close();
                                                    isCapturingSnapshot = false;
                                                    scheduleNextSnapshot();
                                                }
                                            }
                                        }, backgroundHandler);

                            } catch (CameraAccessException e) {
                                Log.w(TAG, "Fallback: capture request failed", e);
                                session.close();
                                camera.close();
                                isCapturingSnapshot = false;
                                scheduleNextSnapshot();
                            }
                        }

                        @Override
                        public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                            Log.w(TAG, "Fallback: snapshot session config failed");
                            session.close();
                            camera.close();
                            isCapturingSnapshot = false;
                            scheduleNextSnapshot();
                        }
                    },
                    backgroundHandler);

        } catch (CameraAccessException e) {
            Log.w(TAG, "Fallback: error setting up snapshot session", e);
            camera.close();
            isCapturingSnapshot = false;
            scheduleNextSnapshot();
        }
    }

    /** Schedules the next PiP snapshot in fallback mode. */
    private void scheduleNextSnapshot() {
        if (!isStopping && fallbackMode && state != DualCameraState.DISABLED) {
            backgroundHandler.postDelayed(this::captureSecondarySnapshot,
                    FALLBACK_SNAPSHOT_INTERVAL_MS);
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // RECORDING SETUP
    // ════════════════════════════════════════════════════════════════════

    /**
     * Builds the {@link DualCameraPipeline}, prepares surfaces, creates
     * Camera2 capture sessions, and starts encoding.
     */
    private void startDualRecording() {
        Log.d(TAG, "startDualRecording: setting up pipeline");

        try {
            // ── Resolution + codec ────────────────────────────────────
            Size resolution = prefs.getCameraResolution();
            int videoWidth = resolution.getWidth();
            int videoHeight = resolution.getHeight();
            int fps = prefs.getSpecificVideoFrameRate(
                    config.getPrimaryCamera() == DualCameraConfig.PrimaryCamera.BACK
                            ? com.fadcam.CameraType.BACK : com.fadcam.CameraType.FRONT);
            com.fadcam.VideoCodec codec = prefs.getVideoCodec();
            String orientation = prefs.getVideoOrientation();

            // ── Sensor orientation for primary camera ─────────────────
            String primaryId = (config.getPrimaryCamera() == DualCameraConfig.PrimaryCamera.BACK)
                    ? backCameraId : frontCameraId;
            int sensorOrientation = 0;
            try {
                CameraCharacteristics chars = cameraManager.getCameraCharacteristics(primaryId);
                Integer so = chars.get(CameraCharacteristics.SENSOR_ORIENTATION);
                if (so != null) sensorOrientation = so;
            } catch (CameraAccessException e) {
                Log.e(TAG, "Error reading sensor orientation", e);
            }

            // ── Output file ───────────────────────────────────────────
            File outputFile = createOutputFile();
            if (outputFile == null) {
                transitionToError("Cannot create output file");
                return;
            }

            // ── Build pipeline ────────────────────────────────────────
            dualPipeline = new DualCameraPipeline(
                    this,
                    videoWidth, videoHeight,
                    fps,
                    outputFile.getAbsolutePath(),
                    orientation,
                    sensorOrientation,
                    codec,
                    config);

            dualPipeline.prepareSurfaces();

            // ── Create capture sessions ───────────────────────────────
            createCaptureSession(
                    primaryCameraDevice,
                    dualPipeline.getPrimaryCameraInputSurface(),
                    true /* isPrimary */);

            if (!fallbackMode && secondaryCameraDevice != null) {
                // Normal mode: both cameras stream concurrently
                createCaptureSession(
                        secondaryCameraDevice,
                        dualPipeline.getSecondaryCameraInputSurface(),
                        false /* isPrimary */);
            } else {
                // Fallback mode: only primary camera streams; secondary gets
                // periodic snapshots. Mark secondary session as "configured"
                // immediately so the pipeline can start.
                Log.i(TAG, "Fallback mode: skipping secondary capture session (periodic snapshots)");
                onSessionConfigured(false);
            }

        } catch (Exception e) {
            Log.e(TAG, "Failed to start dual recording", e);
            transitionToError("Recording setup failed: " + e.getMessage());
        }
    }

    /**
     * Creates a Camera2 capture session that targets the given surface.
     */
    private void createCaptureSession(
            @NonNull CameraDevice camera,
            @NonNull Surface targetSurface,
            boolean isPrimary) {

        try {
            CaptureRequest.Builder builder =
                    camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
            builder.addTarget(targetSurface);

            // AF + AE auto
            builder.set(CaptureRequest.CONTROL_AF_MODE,
                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO);
            builder.set(CaptureRequest.CONTROL_AE_MODE,
                    CaptureRequest.CONTROL_AE_MODE_ON);

            camera.createCaptureSession(
                    Collections.singletonList(targetSurface),
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(@NonNull CameraCaptureSession session) {
                            if (isStopping) return;

                            try {
                                session.setRepeatingRequest(builder.build(), null, backgroundHandler);
                            } catch (CameraAccessException e) {
                                Log.e(TAG, "Failed to start repeating request", e);
                                transitionToError("Capture request failed");
                                return;
                            }

                            if (isPrimary) {
                                primarySession = session;
                                primaryRequestBuilder = builder;
                            } else {
                                secondarySession = session;
                                secondaryRequestBuilder = builder;
                            }

                            Log.d(TAG, (isPrimary ? "Primary" : "Secondary")
                                    + " capture session configured");

                            onSessionConfigured(isPrimary);
                        }

                        @Override
                        public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                            Log.e(TAG, (isPrimary ? "Primary" : "Secondary")
                                    + " capture session configuration failed");
                            transitionToError("Camera session failed");
                        }
                    },
                    backgroundHandler);

        } catch (CameraAccessException e) {
            Log.e(TAG, "Error creating capture session", e);
            transitionToError("Camera session creation error");
        }
    }

    /** Tracks configured sessions; starts pipeline when both are ready. */
    private int sessionsConfigured = 0;

    private synchronized void onSessionConfigured(boolean isPrimary) {
        sessionsConfigured++;
        Log.d(TAG, "Session configured (" + (isPrimary ? "primary" : "secondary")
                + ") — " + sessionsConfigured + "/2");

        if (sessionsConfigured < 2) return;

        // Both sessions ready — start encoding
        try {
            dualPipeline.startRecording();
            state = DualCameraState.RECORDING;
            recordingStartTime = SystemClock.elapsedRealtime();
            prefs.setRecordingInProgress(true);

            // Save start time for timer recovery
            getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putLong(Constants.PREF_RECORDING_START_TIME, recordingStartTime)
                    .commit();

            broadcastAction(Constants.BROADCAST_ON_DUAL_RECORDING_STARTED);
            Log.i(TAG, "✅ Dual camera recording started");
        } catch (Exception e) {
            Log.e(TAG, "Failed to start pipeline encoding", e);
            transitionToError("Encoder start failed");
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // HELPERS
    // ════════════════════════════════════════════════════════════════════

    /** Creates output MP4 file in the app's recording directory. */
    @Nullable
    private File createOutputFile() {
        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
        String filename = "DualCam_" + timestamp + "." + Constants.RECORDING_FILE_EXTENSION;
        File videoDir = new File(getExternalFilesDir(null), Constants.RECORDING_DIRECTORY);
        if (!videoDir.exists() && !videoDir.mkdirs()) {
            Log.e(TAG, "Cannot create recording directory: " + videoDir.getAbsolutePath());
            return null;
        }
        return new File(videoDir, filename);
    }

    /** Starts the foreground notification (same channel as RecordingService). */
    private void startForegroundNotification() {
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification_icon)
                .setContentTitle(getString(R.string.notification_video_recording))
                .setContentText("Dual camera recording…")
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW);

        Notification notification = builder.build();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
                            | ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    // ── Thread management ─────────────────────────────────────────────

    private void startBackgroundThread() {
        backgroundThread = new HandlerThread("DualCamBg");
        backgroundThread.start();
        backgroundHandler = new Handler(backgroundThread.getLooper());
    }

    private void stopBackgroundThread() {
        if (backgroundThread != null) {
            backgroundThread.quitSafely();
            try {
                backgroundThread.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            backgroundThread = null;
            backgroundHandler = null;
        }
    }

    // ── Wake lock ─────────────────────────────────────────────────────

    private void acquireWakeLock() {
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm != null) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "FadCam:DualCamRecording");
            wakeLock.acquire(4 * 60 * 60 * 1000L); // Max 4 hours
        }
    }

    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) {
            try {
                wakeLock.release();
            } catch (Exception e) {
                Log.w(TAG, "Error releasing wake lock", e);
            }
            wakeLock = null;
        }
    }

    // ── Camera close helper ───────────────────────────────────────────

    private void closeCamera(@Nullable CameraCaptureSession session,
                             @Nullable CameraDevice device,
                             @NonNull String label) {
        if (session != null) {
            try {
                session.close();
            } catch (Exception e) {
                Log.w(TAG, "Error closing " + label + " session", e);
            }
        }
        if (device != null) {
            try {
                device.close();
            } catch (Exception e) {
                Log.w(TAG, "Error closing " + label + " camera", e);
            }
        }
    }

    /** Release everything in case of crash/destroy. */
    private void releaseAllResources() {
        // Stop fallback snapshot loop
        fallbackMode = false;
        isCapturingSnapshot = false;

        if (dualPipeline != null) {
            try {
                dualPipeline.stopRecording();
            } catch (Exception e) {
                Log.e(TAG, "Error stopping pipeline on destroy", e);
            }
            dualPipeline = null;
        }

        closeCamera(primarySession, primaryCameraDevice, "primary");
        closeCamera(secondarySession, secondaryCameraDevice, "secondary");
        primarySession = null;
        primaryCameraDevice = null;
        secondarySession = null;
        secondaryCameraDevice = null;

        state = DualCameraState.DISABLED;
        sessionsConfigured = 0;
        camerasOpened = 0;
    }

    // ── Error handling ────────────────────────────────────────────────

    private void transitionToError(@NonNull String reason) {
        Log.e(TAG, "Dual camera error: " + reason);
        state = DualCameraState.ERROR;
        broadcastError(reason);

        mainHandler.post(() ->
                Toast.makeText(getApplicationContext(), reason, Toast.LENGTH_LONG).show());

        // Clean up and stop
        releaseAllResources();
        prefs.setRecordingInProgress(false);
        releaseWakeLock();
        stopSelf();
    }

    // ── Broadcasting ──────────────────────────────────────────────────

    /**
     * Sends a regular broadcast (not LocalBroadcastManager) so that receivers
     * registered with {@code context.registerReceiver()} in HomeFragment
     * can receive the events — matching the pattern used by RecordingService.
     */
    private void broadcastAction(@NonNull String action) {
        sendBroadcast(new Intent(action));
    }

    private void broadcastError(@NonNull String reason) {
        Intent intent = new Intent(Constants.BROADCAST_ON_DUAL_CAMERA_ERROR);
        intent.putExtra("error_reason", reason);
        sendBroadcast(intent);
    }
}
