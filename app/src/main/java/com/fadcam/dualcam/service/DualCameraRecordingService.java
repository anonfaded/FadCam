package com.fadcam.dualcam.service;

import com.fadcam.Log;
import com.fadcam.FLog;
import android.Manifest;
import android.app.PendingIntent;
import android.app.Notification;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Size;
import android.view.Surface;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;
import androidx.documentfile.provider.DocumentFile;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.fadcam.Constants;
import com.fadcam.MaximumRecordingDuration;
import com.fadcam.R;
import com.fadcam.RecordingDurationLimitController;
import com.fadcam.SharedPreferencesManager;
import com.fadcam.dualcam.DualCameraCapability;
import com.fadcam.dualcam.DualCameraConfig;
import com.fadcam.dualcam.DualCameraState;
import com.fadcam.opengl.GLRecordingPipeline;
import com.fadcam.opengl.WatermarkInfoProvider;
import com.fadcam.utils.PhotoStorageHelper;
import com.fadcam.utils.RecordingStoragePaths;

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
 * and delegates compositing/encoding to {@link GLRecordingPipeline} with PiP support.
 *
 * <h3>Intent Actions</h3>
 * <ul>
 *   <li>{@link Constants#INTENT_ACTION_START_DUAL_RECORDING}</li>
 *   <li>{@link Constants#INTENT_ACTION_STOP_DUAL_RECORDING}</li>
 *   <li>{@link Constants#INTENT_ACTION_PAUSE_DUAL_RECORDING}</li>
 *   <li>{@link Constants#INTENT_ACTION_RESUME_DUAL_RECORDING}</li>
 *   <li>{@link Constants#INTENT_ACTION_SWAP_DUAL_CAMERAS}</li>
 *   <li>{@link Constants#INTENT_ACTION_UPDATE_PIP_CONFIG}</li>
 *   <li>{@link Constants#INTENT_ACTION_ADD_BOOKMARK}</li>
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

    /** Current torch state for the primary camera. */
    private boolean isTorchOn = false;

    // ── Pipeline ───────────────────────────────────────────────────────

    /** Unified recording pipeline — same as single-camera mode, but with PiP enabled. */
    private GLRecordingPipeline recordingPipeline;

    // ── State ──────────────────────────────────────────────────────────

    private volatile DualCameraState state = DualCameraState.DISABLED;
    private DualCameraConfig config;
    private long recordingStartTime;
    private long pauseStartedAt;
    private long accumulatedPausedDurationMs;

    // ── Threading ──────────────────────────────────────────────────────

    private HandlerThread backgroundThread;
    private Handler backgroundHandler;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private RecordingDurationLimitController durationLimitController;
    private SharedPreferences.OnSharedPreferenceChangeListener durationPreferenceListener;

    // ── System ─────────────────────────────────────────────────────────

    private SharedPreferencesManager prefs;
    private com.fadcam.watermark.WatermarkManager watermarkManager;
    private DualCameraCapability capability;
    private PowerManager.WakeLock wakeLock;

    // Storage location support
    private android.os.ParcelFileDescriptor safRecordingPfd;  // ParcelFileDescriptor for SAF mode
    private Uri safRecordingUri;  // SAF URI
    private String safOutputFileName;   // Filename for SAF
    @Nullable
    private String lastRecordingUriString;

    // Guard against duplicate open/close races
    private volatile boolean isStopping = false;
    private int camerasOpened = 0; // Track how many cameras have opened successfully

    /**
     * Fallback mode flag: when the device cannot open both cameras simultaneously,
     * we record with only the primary camera streaming continuously, and periodically
     * open the secondary camera to capture a single frame for the PiP overlay.
     */
    private volatile boolean fallbackMode = false;

    /**
     * Black frame fallback mode: when dual camera is not supported at all (capability
     * check fails), we use only the primary camera and leave secondary as black.
     * This allows testing dual camera UI/settings on any device.
     */
    private volatile boolean useBlackFrameFallback = false;

    /** The resolved secondary camera ID — stored for use in fallback periodic snapshots. */
    private String resolvedSecondaryId;

    // ════════════════════════════════════════════════════════════════════
    // LIFECYCLE
    // ════════════════════════════════════════════════════════════════════

    @Override
    public void onCreate() {
        super.onCreate();
        FLog.d(TAG, "onCreate");

        prefs = SharedPreferencesManager.getInstance(getApplicationContext());
        initializeDurationLimitController();
        capability = new DualCameraCapability(this);
        cameraManager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);

        if (cameraManager == null) {
            FLog.e(TAG, "CameraManager unavailable — cannot start dual camera service");
            stopSelf();
            return;
        }

        startBackgroundThread();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || intent.getAction() == null) {
            FLog.w(TAG, "onStartCommand: null intent/action");
            return START_STICKY;
        }

        String action = intent.getAction();
        FLog.d(TAG, "onStartCommand: action=" + action);

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

            case Constants.INTENT_ACTION_CHANGE_SURFACE:
                handleChangeSurface(intent);
                break;

            case Constants.INTENT_ACTION_TOGGLE_RECORDING_TORCH:
                handleToggleTorch();
                break;

            case Constants.INTENT_ACTION_SET_FRONT_VIDEO_MIRROR:
                handleSetFrontVideoMirror(intent);
                break;

            case Constants.INTENT_ACTION_SET_EXPOSURE_COMPENSATION:
                handleSetExposureCompensation(intent);
                break;

            case Constants.INTENT_ACTION_TOGGLE_AE_LOCK:
                handleToggleAeLock(intent);
                break;

            case Constants.INTENT_ACTION_SET_AF_MODE:
                handleSetAfMode(intent);
                break;

            case Constants.INTENT_ACTION_TAP_TO_FOCUS:
                handleTapToFocus();
                break;

            case Constants.INTENT_ACTION_SET_ZOOM_RATIO:
                handleSetZoomRatio(intent);
                break;

            case Constants.INTENT_ACTION_CAPTURE_PHOTO:
                handleCapturePhoto();
                break;

            case Constants.INTENT_ACTION_ADD_BOOKMARK:
                addBookmarkAtCurrentPosition();
                break;

            default:
                FLog.w(TAG, "Unknown action: " + action);
                break;
        }

        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        FLog.d(TAG, "onDestroy");
        releaseDurationLimitController();
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
            FLog.w(TAG, "Cannot start dual recording — state=" + state);
            return;
        }
        if (durationLimitController != null) {
            durationLimitController.stopSession();
        }
        lastRecordingUriString = null;

        // ── Permission check ──────────────────────────────────────────
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED
                || ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            FLog.e(TAG, "Camera or audio permission not granted");
            broadcastError("Permissions required for dual camera recording");
            stopSelf();
            return;
        }

        // ── Capability check ──────────────────────────────────────────
        if (!capability.isSupported()) {
            FLog.w(TAG, "Dual camera not supported: " + capability.getUnsupportedReason());
            FLog.i(TAG, "⚡ Enabling black frame fallback mode for testing");
            useBlackFrameFallback = true;
            
            // For testing: use any available camera for both feeds
            // Try back camera first, fall back to front
            try {
                String[] cameraIds = cameraManager.getCameraIdList();
                for (String id : cameraIds) {
                    CameraCharacteristics chars = cameraManager.getCameraCharacteristics(id);
                    Integer facing = chars.get(CameraCharacteristics.LENS_FACING);
                    if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) {
                        frontCameraId = id; // Use back camera for both
                        backCameraId = id;
                        break;
                    }
                }
                // If no back camera, use first available
                if (frontCameraId == null && cameraIds.length > 0) {
                    frontCameraId = cameraIds[0];
                    backCameraId = cameraIds[0];
                }
            } catch (CameraAccessException e) {
                FLog.e(TAG, "Failed to enumerate cameras for fallback", e);
                broadcastError("No cameras available");
                stopSelf();
                return;
            }
            
            if (frontCameraId == null) {
                broadcastError("No cameras available for testing");
                stopSelf();
                return;
            }
        } else {
            frontCameraId = capability.getConcurrentFrontCameraId();
            backCameraId = capability.getConcurrentBackCameraId();

            if (frontCameraId == null || backCameraId == null) {
                FLog.e(TAG, "Could not resolve front/back camera IDs");
                broadcastError("Could not identify concurrent cameras");
                stopSelf();
                return;
            }
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
        if (durationLimitController != null && durationLimitController.stopSession()) {
            FLog.d(TAG, "Cancelled maximum recording duration for current dual session");
        }
        if (state == DualCameraState.DISABLED) {
            FLog.w(TAG, "Already stopped / disabled");
            stopSelf();
            return;
        }

        FLog.i(TAG, "Stopping dual camera recording");
        isStopping = true;
        state = DualCameraState.DISABLED;
        // Tactile confirmation that dual recording stopped (service-side,
        // gated by the Haptic feedback setting).
        com.fadcam.Utils.vibrateRecordingStop(this);
        fallbackMode = false;
        useBlackFrameFallback = false;
        isCapturingSnapshot = false;

        // Stop pipeline first (drains encoders, finalises muxer)
        if (recordingPipeline != null) {
            try {
                recordingPipeline.stopRecording();
            } catch (Exception e) {
                FLog.e(TAG, "Error stopping pipeline", e);
            }
            recordingPipeline = null;
        }

        // Destroy watermark sensor providers
        if (watermarkManager != null) {
            watermarkManager.destroy();
            watermarkManager = null;
        }

        // Close cameras
        closeCamera(primarySession, primaryCameraDevice, "primary");
        closeCamera(secondarySession, secondaryCameraDevice, "secondary");
        primarySession = null;
        primaryCameraDevice = null;
        secondarySession = null;
        secondaryCameraDevice = null;

        // Close SAF file descriptor if used
        if (safRecordingPfd != null) {
            try {
                safRecordingPfd.close();
                FLog.d(TAG, "Closed SAF ParcelFileDescriptor");
            } catch (Exception e) {
                FLog.e(TAG, "Error closing ParcelFileDescriptor", e);
            }
            safRecordingPfd = null;
            safRecordingUri = null;
            safOutputFileName = null;
        }

        prefs.setRecordingInProgress(false);
        releaseWakeLock();
        clearRecordingTimelineState();
        pauseStartedAt = 0;
        accumulatedPausedDurationMs = 0;

        broadcastRecordingComplete(true);
        lastRecordingUriString = null;
        broadcastAction(Constants.BROADCAST_ON_DUAL_RECORDING_STOPPED);
        stopSelf();
    }

    private void handlePauseDualRecording() {
        if (state != DualCameraState.RECORDING) {
            FLog.w(TAG, "Cannot pause — state=" + state);
            return;
        }

        if (recordingPipeline != null) {
            recordingPipeline.pauseRecording();
        }
        if (watermarkManager != null) watermarkManager.pauseSensors();
        state = DualCameraState.PAUSED;
        pauseStartedAt = SystemClock.elapsedRealtime();
        if (durationLimitController != null && durationLimitController.pauseSession()) {
            FLog.d(TAG, "Paused maximum recording duration countdown");
        }
        persistRecordingTimelineState();
        broadcastActionWithTiming(Constants.BROADCAST_ON_DUAL_RECORDING_PAUSED);
        updatePausedNotification();
        FLog.i(TAG, "Dual recording paused");
    }

    private void handleResumeDualRecording() {
        if (state != DualCameraState.PAUSED) {
            FLog.w(TAG, "Cannot resume — state=" + state);
            return;
        }

        if (recordingPipeline != null) {
            recordingPipeline.resumeRecording();
        }
        if (watermarkManager != null) watermarkManager.resumeSensors(null);
        state = DualCameraState.RECORDING;
        if (pauseStartedAt > 0L) {
            accumulatedPausedDurationMs += Math.max(0L, SystemClock.elapsedRealtime() - pauseStartedAt);
            pauseStartedAt = 0L;
        }
        if (durationLimitController != null && durationLimitController.resumeSession()) {
            FLog.d(TAG, "Resumed maximum recording duration countdown");
        }
        persistRecordingTimelineState();
        broadcastActionWithTiming(Constants.BROADCAST_ON_DUAL_RECORDING_RESUMED);
        updateResumedNotification();
        FLog.i(TAG, "Dual recording resumed");
    }

    /**
     * Swap primary ↔ secondary cameras by closing and recreating capture sessions.
     * Cameras are reassigned to surfaces so the GL renderer needs no swap logic.
     */
    private void handleSwapCameras() {
        if (state != DualCameraState.RECORDING && state != DualCameraState.PAUSED) {
            FLog.w(TAG, "Cannot swap cameras — state=" + state);
            return;
        }

        DualCameraConfig.PrimaryCamera newPrimary =
                (config.getPrimaryCamera() == DualCameraConfig.PrimaryCamera.BACK)
                        ? DualCameraConfig.PrimaryCamera.FRONT
                        : DualCameraConfig.PrimaryCamera.BACK;

        config = new DualCameraConfig.Builder(config)
                .primaryCamera(newPrimary)
                .build();
        prefs.saveDualCameraConfig(config);

        // Notify renderer which camera is now front (for rotation/flip logic)
        if (recordingPipeline != null) {
            recordingPipeline.setFullscreenCameraIsFront(
                newPrimary == DualCameraConfig.PrimaryCamera.FRONT);
        }

        // Close existing sessions and stop repeating requests
        if (primarySession != null) {
            try { primarySession.stopRepeating(); primarySession.close(); } catch (Exception ignored) {}
            primarySession = null;
        }
        if (secondarySession != null) {
            try { secondarySession.stopRepeating(); secondarySession.close(); } catch (Exception ignored) {}
            secondarySession = null;
        }

        // Swap camera device references
        CameraDevice tmp = primaryCameraDevice;
        primaryCameraDevice = secondaryCameraDevice;
        secondaryCameraDevice = tmp;

        // Recreate sessions: each camera targets the SAME surface as before
        // (primaryCameraDevice → cameraInputSurface, secondaryCameraDevice → pipCameraInputSurface)
        if (recordingPipeline != null) {
            Surface ps = recordingPipeline.getPrimaryCameraInputSurface();
            Surface ss = recordingPipeline.getSecondaryCameraInputSurface();
            if (ps != null && primaryCameraDevice != null) {
                createCaptureSession(primaryCameraDevice, ps, true);
            }
            if (ss != null && secondaryCameraDevice != null) {
                createCaptureSession(secondaryCameraDevice, ss, false);
            }
        }

        broadcastAction(Constants.BROADCAST_ON_DUAL_CAMERAS_SWAPPED);
        FLog.i(TAG, "Cameras swapped — new primary: " + newPrimary);
    }

    /**
     * Hot-update PiP configuration (position, size, border) without restarting recording.
     */
    private void handleUpdatePipConfig() {
        config = prefs.getDualCameraConfig();
        if (recordingPipeline != null) {
            recordingPipeline.updateConfig(config);
        }
        FLog.d(TAG, "PiP config updated live");
    }

    /**
     * Handles a preview surface change sent from the UI (HomeFragment).
     * Forwards the surface to the recording pipeline for live preview rendering.
     *
     * @param intent Intent containing "SURFACE" extra and optional dimensions.
     */
    private void handleChangeSurface(@NonNull Intent intent) {
        Surface surface = intent.getParcelableExtra("SURFACE");
        int surfaceW = intent.getIntExtra("SURFACE_WIDTH", 0);
        int surfaceH = intent.getIntExtra("SURFACE_HEIGHT", 0);
        boolean isFullscreenTransition = intent.getBooleanExtra("IS_FULLSCREEN_TRANSITION", false);

        if (recordingPipeline != null) {
            // Use IMMEDIATE mode for fullscreen to bypass debounce
            if (isFullscreenTransition && surface != null && surface.isValid()) {
                FLog.d(TAG, "Setting preview surface IMMEDIATE (fullscreen transition)");
                recordingPipeline.setPreviewSurfaceImmediate(surface);
            } else {
                recordingPipeline.setPreviewSurface(surface);
            }
            if (surfaceW > 0 && surfaceH > 0) {
                recordingPipeline.updateSurfaceDimensions(surfaceW, surfaceH);
            }
            FLog.d(TAG, "Preview surface updated: " +
                    (surface != null && surface.isValid() ? surfaceW + "x" + surfaceH : "null") +
                    " (immediate=" + isFullscreenTransition + ")");
        } else {
            FLog.w(TAG, "handleChangeSurface: pipeline not ready, surface change ignored");
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // RUNTIME CAMERA CONTROLS (torch, zoom, exposure, AF)
    // ════════════════════════════════════════════════════════════════════

    /**
     * Toggles the torch on whichever running camera supports flash.
     * When the primary camera (e.g. front) lacks hardware flash, the secondary
     * (back) camera's session is used instead so torch always works.
     */
    private void handleToggleTorch() {
        if (primaryRequestBuilder == null || primarySession == null) {
            FLog.w(TAG, "handleToggleTorch: no active primary session");
            return;
        }
        isTorchOn = !isTorchOn;

        // Determine which camera session supports torch
        CameraCaptureSession torchSession = primarySession;
        CaptureRequest.Builder torchBuilder = primaryRequestBuilder;
        boolean primaryHasFlash = false;
        try {
            String primaryId = (config.getPrimaryCamera() == DualCameraConfig.PrimaryCamera.BACK)
                    ? backCameraId : frontCameraId;
            CameraCharacteristics chars = cameraManager.getCameraCharacteristics(primaryId);
            Boolean flashAvail = chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
            primaryHasFlash = (flashAvail != null && flashAvail);
        } catch (Exception e) {
            FLog.w(TAG, "Could not check flash availability for primary camera", e);
        }

        if (!primaryHasFlash && secondarySession != null && secondaryRequestBuilder != null) {
            torchSession = secondarySession;
            torchBuilder = secondaryRequestBuilder;
            FLog.d(TAG, "Primary camera has no flash — using secondary camera torch");
        }

        torchBuilder.set(CaptureRequest.FLASH_MODE,
                isTorchOn ? CaptureRequest.FLASH_MODE_TORCH
                          : CaptureRequest.FLASH_MODE_OFF);

        try {
            torchSession.setRepeatingRequest(torchBuilder.build(), null, backgroundHandler);
            FLog.d(TAG, "Torch toggled: " + (isTorchOn ? "ON" : "OFF")
                    + " (using " + (torchSession == primarySession ? "primary" : "secondary") + " session)");
        } catch (CameraAccessException e) {
            FLog.e(TAG, "Failed to toggle torch", e);
            isTorchOn = !isTorchOn;
            return;
        }

        prefs.sharedPreferences.edit()
                .putBoolean(Constants.PREF_TORCH_STATE, isTorchOn).apply();
        Intent broadcast = new Intent(Constants.BROADCAST_ON_TORCH_STATE_CHANGED);
        broadcast.putExtra(Constants.INTENT_EXTRA_TORCH_STATE, isTorchOn);
        sendBroadcast(broadcast);
    }

    private void handleSetFrontVideoMirror(Intent intent) {
        boolean enabled = intent.getBooleanExtra(
                Constants.EXTRA_FRONT_VIDEO_MIRROR_ENABLED,
                prefs.isFrontVideoMirrorEnabled());
        prefs.setFrontVideoMirrorEnabled(enabled);
        if (recordingPipeline != null) {
            recordingPipeline.setFrontVideoMirrorEnabled(enabled);
        }
        Intent mirrorBcast = new Intent(Constants.BROADCAST_ON_MIRROR_CHANGED);
        mirrorBcast.putExtra(Constants.EXTRA_MIRROR_ENABLED, enabled);
        LocalBroadcastManager.getInstance(this).sendBroadcast(mirrorBcast);
        FLog.i(TAG, "Front video mirror set: " + enabled);
    }

    /**
     * Sets the exposure compensation value on the primary camera.
     */
    private void handleSetExposureCompensation(@NonNull Intent intent) {
        int ev = intent.getIntExtra(Constants.EXTRA_EXPOSURE_COMPENSATION, 0);
        try {
            prefs.setSavedExposureCompensation(ev);
        } catch (Exception ignored) {
        }
        if (primaryRequestBuilder == null || primarySession == null) return;
        primaryRequestBuilder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, ev);
        if (applyPrimaryRepeating()) {
            FLog.d(TAG, "Exposure compensation set to: " + ev);
        }
    }

    /**
     * Toggles the AE lock on the primary camera.
     */
    private void handleToggleAeLock(@NonNull Intent intent) {
        boolean lock = intent.getBooleanExtra(Constants.EXTRA_AE_LOCK, false);
        try {
            prefs.setSavedAeLock(lock);
        } catch (Exception ignored) {
        }
        if (primaryRequestBuilder == null || primarySession == null) return;
        primaryRequestBuilder.set(CaptureRequest.CONTROL_AE_LOCK, lock);
        if (applyPrimaryRepeating()) {
            FLog.d(TAG, "AE lock set to: " + lock);
        }
    }

    /**
     * Sets the autofocus mode on the primary camera.
     */
    private void handleSetAfMode(@NonNull Intent intent) {
        int mode = intent.getIntExtra(Constants.EXTRA_AF_MODE,
                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO);
        try {
            prefs.setSavedAfMode(mode);
        } catch (Exception ignored) {
        }
        if (primaryRequestBuilder == null || primarySession == null) return;
        primaryRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, mode);
        if (applyPrimaryRepeating()) {
            FLog.d(TAG, "AF mode set to: " + mode);
        }
    }

    /**
     * Triggers an AF scan on the primary camera.
     */
    private void handleTapToFocus() {
        if (primaryRequestBuilder == null || primarySession == null) return;
        try {
            primaryRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER,
                    CaptureRequest.CONTROL_AF_TRIGGER_START);
            primarySession.capture(primaryRequestBuilder.build(),
                    null, backgroundHandler);
            // Reset trigger for subsequent repeating requests
            primaryRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER,
                    CaptureRequest.CONTROL_AF_TRIGGER_IDLE);
            FLog.d(TAG, "Tap-to-focus triggered");
        } catch (CameraAccessException e) {
            FLog.e(TAG, "Tap-to-focus failed", e);
        }
    }

    private void handleCapturePhoto() {
        if (recordingPipeline == null || (state != DualCameraState.RECORDING && state != DualCameraState.PAUSED)) {
            mainHandler.post(() -> Toast.makeText(getApplicationContext(),
                    R.string.photo_capture_preview_unavailable, Toast.LENGTH_SHORT).show());
            return;
        }
        recordingPipeline.capturePhotoFrame(bitmap -> {
            if (bitmap == null) {
                mainHandler.post(() -> Toast.makeText(getApplicationContext(),
                        R.string.photo_capture_failed, Toast.LENGTH_SHORT).show());
                return;
            }
            if (backgroundHandler == null) {
                bitmap.recycle();
                return;
            }
            backgroundHandler.post(() -> {
                Uri savedUri = PhotoStorageHelper.saveJpegBitmap(
                        getApplicationContext(),
                        bitmap,
                        false,
                        PhotoStorageHelper.ShotSource.BACK);
                bitmap.recycle();
                if (savedUri != null) {
                    Intent recordingCompleteIntent = new Intent(Constants.ACTION_RECORDING_COMPLETE);
                    recordingCompleteIntent.putExtra(Constants.EXTRA_RECORDING_SUCCESS, true);
                    recordingCompleteIntent.putExtra(Constants.EXTRA_RECORDING_URI_STRING, savedUri.toString());
                    sendBroadcast(recordingCompleteIntent);
                    mainHandler.post(() -> Toast.makeText(getApplicationContext(),
                            R.string.photo_capture_saved, Toast.LENGTH_SHORT).show());
                } else {
                    mainHandler.post(() -> Toast.makeText(getApplicationContext(),
                            R.string.photo_capture_failed, Toast.LENGTH_SHORT).show());
                }
            });
        });
    }

    /**
     * Sets the zoom ratio on the primary camera (API 30+).
     */
    private void handleSetZoomRatio(@NonNull Intent intent) {
        if (primaryRequestBuilder == null || primarySession == null) return;
        float zoom = intent.getFloatExtra(Constants.EXTRA_ZOOM_RATIO, 1.0f);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            primaryRequestBuilder.set(CaptureRequest.CONTROL_ZOOM_RATIO, zoom);
        }
        if (applyPrimaryRepeating()) {
            FLog.d(TAG, "Zoom ratio set to: " + zoom);
        }
    }

    /**
     * Applies the current primary request builder as a repeating request.
     *
     * @return {@code true} if the request was applied successfully.
     */
    private boolean applyPrimaryRepeating() {
        try {
            primarySession.setRepeatingRequest(
                    primaryRequestBuilder.build(), null, backgroundHandler);
            return true;
        } catch (CameraAccessException e) {
            FLog.e(TAG, "Failed to apply primary repeating request", e);
            return false;
        } catch (IllegalStateException e) {
            FLog.w(TAG, "Primary session already closed", e);
            return false;
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // CAMERA OPENING
    // ════════════════════════════════════════════════════════════════════

    /**
     * Opens both cameras <b>sequentially</b> — primary first, then secondary
     * only after the primary is confirmed open. This improves compatibility
     * on devices that do not officially support concurrent camera streams.
     * <p>
     * If {@link #useBlackFrameFallback} is true, opens only the primary camera
     * for testing UI/settings on unsupported devices.
     */
    private void openBothCameras() {
        // ── BLACK FRAME FALLBACK (TEST MODE) ─────────────────────────────
        if (useBlackFrameFallback) {
            FLog.i(TAG, "Black frame fallback: opening only primary camera for testing");
            fallbackMode = true; // Treat as fallback mode (secondary won't stream)
            // Continue to open primary camera only (secondary will remain null)
        }

        // ── REAL CAMERA MODE ──────────────────────────────────────────
        // Determine which physical camera is primary based on config
        String primaryId = (config.getPrimaryCamera() == DualCameraConfig.PrimaryCamera.BACK)
                ? backCameraId : frontCameraId;
        String secondaryId = (config.getPrimaryCamera() == DualCameraConfig.PrimaryCamera.BACK)
                ? frontCameraId : backCameraId;

        FLog.d(TAG, "Opening primary camera: " + primaryId + ", secondary: " + secondaryId);
        resolvedSecondaryId = secondaryId;

        try {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                    != PackageManager.PERMISSION_GRANTED) {
                FLog.e(TAG, "Camera permission denied");
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
                    FLog.d(TAG, "Primary camera opened: " + camera.getId());
                    primaryCameraDevice = camera;

                    if (useBlackFrameFallback) {
                        // Black frame test mode: skip secondary camera entirely
                        FLog.i(TAG, "Black frame test mode: skipping secondary camera");
                        camerasOpened = 1;
                        onPrimaryCameraReadyForFallback();
                    } else {
                        // Step 2: Open secondary camera AFTER primary is confirmed open
                        // Small delay helps on devices with shared camera hardware pipelines
                        backgroundHandler.postDelayed(() -> openSecondaryCamera(secondaryId), 300);
                    }
                }

                @Override
                public void onDisconnected(@NonNull CameraDevice camera) {
                    FLog.w(TAG, "Primary camera disconnected: " + camera.getId());
                    camera.close();
                    primaryCameraDevice = null;
                    transitionToError("Primary camera disconnected");
                }

                @Override
                public void onError(@NonNull CameraDevice camera, int error) {
                    FLog.e(TAG, "Primary camera error: " + error);
                    camera.close();
                    primaryCameraDevice = null;
                    transitionToError("Primary camera error (code " + error + ")");
                }
            }, backgroundHandler);

        } catch (CameraAccessException | SecurityException e) {
            FLog.e(TAG, "Error opening primary camera", e);
            transitionToError("Failed to open camera: " + e.getMessage());
        }
    }

    /**
     * Opens the secondary (PiP) camera after the primary is already open.
     */
    private void openSecondaryCamera(@NonNull String secondaryId) {
        if (isStopping || primaryCameraDevice == null) {
            FLog.w(TAG, "openSecondaryCamera: Aborting — stopping=" + isStopping);
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
                    FLog.d(TAG, "Secondary camera opened: " + camera.getId());
                    secondaryCameraDevice = camera;
                    // Both cameras now open — proceed to pipeline setup
                    camerasOpened = 2;
                    onCameraOpened();
                }

                @Override
                public void onDisconnected(@NonNull CameraDevice camera) {
                    FLog.w(TAG, "Secondary camera disconnected: " + camera.getId());
                    camera.close();
                    secondaryCameraDevice = null;
                    transitionToError("Secondary camera disconnected");
                }

                @Override
                public void onError(@NonNull CameraDevice camera, int error) {
                    FLog.w(TAG, "Secondary camera error: " + error
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
            FLog.e(TAG, "Error opening secondary camera", e);
            transitionToError("Failed to open secondary camera: " + e.getMessage());
        }
    }

    /**
     * Called when both cameras are confirmed open. Validates state and
     * proceeds to create the recording pipeline.
     */
    private synchronized void onCameraOpened() {
        FLog.d(TAG, "onCameraOpened — both cameras ready, camerasOpened=" + camerasOpened);

        if (primaryCameraDevice == null || secondaryCameraDevice == null) {
            FLog.e(TAG, "One of the cameras is null despite both reported open");
            transitionToError("Camera initialization failed");
            return;
        }

        // Both cameras ready — build pipeline and start recording
        startDualRecording();
    }

    /**
     * Called when the secondary camera cannot be opened concurrently.
     * Starts recording with only the primary camera and schedules periodic
     * snapshots from the secondary camera to keep the PiP overlay updating
     * (unless in black frame test mode).
     */
    private void onPrimaryCameraReadyForFallback() {
        FLog.i(TAG, "⚡ Entering fallback mode — primary-only recording");

        if (primaryCameraDevice == null) {
            FLog.e(TAG, "Primary camera is null in fallback mode");
            transitionToError("Camera initialization failed");
            return;
        }

        // Proceed to set up pipeline with primary camera only.
        // The pipeline still creates both SurfaceTextures (primary + secondary),
        // but only the primary receives a continuous camera stream.
        startDualRecording();

        // Schedule periodic secondary camera snapshots ONLY if not in black frame test mode
        if (!useBlackFrameFallback) {
            // Delay the first snapshot to let the pipeline stabilise.
            backgroundHandler.postDelayed(this::captureSecondarySnapshot, 2000);
        } else {
            FLog.d(TAG, "Black frame test mode: skipping periodic snapshots (secondary will remain black)");
        }
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
        if (secId == null || recordingPipeline == null) {
            isCapturingSnapshot = false;
            return;
        }

        FLog.d(TAG, "Fallback: capturing PiP snapshot from camera " + secId);

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
                    FLog.d(TAG, "Fallback: secondary camera opened for snapshot");
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
                    FLog.w(TAG, "Fallback: secondary camera snapshot error: " + error);
                    camera.close();
                    isCapturingSnapshot = false;
                    scheduleNextSnapshot();
                }
            }, backgroundHandler);

        } catch (CameraAccessException | SecurityException e) {
            FLog.w(TAG, "Fallback: cannot open secondary camera for snapshot", e);
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
            Surface secondarySurface = recordingPipeline.getSecondaryCameraInputSurface();

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
                                                    FLog.d(TAG, "Fallback: PiP snapshot captured");
                                                    session.close();
                                                    camera.close();
                                                    isCapturingSnapshot = false;
                                                    scheduleNextSnapshot();
                                                }
                                            }
                                        }, backgroundHandler);

                            } catch (CameraAccessException e) {
                                FLog.w(TAG, "Fallback: capture request failed", e);
                                session.close();
                                camera.close();
                                isCapturingSnapshot = false;
                                scheduleNextSnapshot();
                            }
                        }

                        @Override
                        public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                            FLog.w(TAG, "Fallback: snapshot session config failed");
                            session.close();
                            camera.close();
                            isCapturingSnapshot = false;
                            scheduleNextSnapshot();
                        }
                    },
                    backgroundHandler);

        } catch (CameraAccessException e) {
            FLog.w(TAG, "Fallback: error setting up snapshot session", e);
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
     * Builds the {@link GLRecordingPipeline} with PiP support, prepares surfaces,
     * creates Camera2 capture sessions, and starts encoding.
     *
     * <p>Uses the same proven pipeline as single-camera recording, but with
     * {@link DualCameraConfig} to enable PiP compositing in the GL renderer.
     */
    private void startDualRecording() {
        FLog.d(TAG, "startDualRecording: setting up unified pipeline");

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
                FLog.e(TAG, "Error reading sensor orientation", e);
            }

            // ── Output file ───────────────────────────────────────────
            File outputFile = createOutputFile();
            if (safRecordingPfd != null && safRecordingUri != null) {
                lastRecordingUriString = safRecordingUri.toString();
            } else if (outputFile != null) {
                lastRecordingUriString = Uri.fromFile(outputFile).toString();
            } else {
                lastRecordingUriString = null;
            }

            // ── Watermark provider (shared WatermarkManager) ──────────
            watermarkManager = new com.fadcam.watermark.WatermarkManager(this, prefs);
            // DualCameraRecordingService doesn't have its own LocationHelper,
            // so WatermarkManager creates one internally.
            watermarkManager.initialize(null);
            WatermarkInfoProvider watermarkProvider = watermarkManager;
            
            // ── Build unified pipeline with DualCameraConfig ──────────
            if (safRecordingPfd != null) {
                // SAF mode: use FileDescriptor constructor
                FLog.d(TAG, "Building unified pipeline with FileDescriptor (SAF mode)");
                recordingPipeline = new GLRecordingPipeline(
                        this,
                        watermarkProvider,
                        videoWidth, videoHeight,
                        fps,
                        safRecordingPfd.getFileDescriptor(),
                        Long.MAX_VALUE,     // No segment splitting for dual cam
                        1,                  // Segment number
                        null,               // No segment callback
                        null,               // Preview surface (set later if available)
                        orientation,
                        sensorOrientation,
                        codec,
                        null, null,         // No location metadata for now
                        config);            // DualCameraConfig enables PiP
            } else {
                // Internal storage mode: use file path constructor
                if (outputFile == null) {
                    transitionToError("Cannot create output file");
                    return;
                }
                FLog.d(TAG, "Building unified pipeline with file path (internal mode)");
                recordingPipeline = new GLRecordingPipeline(
                        this,
                        watermarkProvider,
                        videoWidth, videoHeight,
                        fps,
                        outputFile.getAbsolutePath(),
                        Long.MAX_VALUE,     // No segment splitting for dual cam
                        1,                  // Segment number
                        null,               // No segment callback
                        null,               // Preview surface (set later if available)
                        orientation,
                        sensorOrientation,
                        codec,
                        null, null,         // No location metadata for now
                        config);            // DualCameraConfig enables PiP
            }

            recordingPipeline.prepareSurfaces();

            // ── Create capture sessions ───────────────────────────────
            createCaptureSession(
                    primaryCameraDevice,
                    recordingPipeline.getPrimaryCameraInputSurface(),
                    true /* isPrimary */);

            if (!fallbackMode && secondaryCameraDevice != null) {
                // Normal mode: both cameras stream concurrently
                Surface secondarySurface = recordingPipeline.getSecondaryCameraInputSurface();
                if (secondarySurface != null && secondarySurface.isValid()) {
                    createCaptureSession(
                            secondaryCameraDevice,
                            secondarySurface,
                            false /* isPrimary */);
                } else {
                    FLog.w(TAG, "Secondary camera surface not available, entering fallback mode");
                    fallbackMode = true;
                    onSessionConfigured(false);
                }
            } else {
                // Fallback mode: only primary camera streams; secondary gets
                // periodic snapshots. Mark secondary session as "configured"
                // immediately so the pipeline can start.
                FLog.i(TAG, "Fallback mode: skipping secondary capture session (periodic snapshots)");
                onSessionConfigured(false);
            }

        } catch (Exception e) {
            FLog.e(TAG, "Failed to start dual recording", e);
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
                                FLog.e(TAG, "Failed to start repeating request", e);
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

                            FLog.d(TAG, (isPrimary ? "Primary" : "Secondary")
                                    + " capture session configured");

                            onSessionConfigured(isPrimary);
                        }

                        @Override
                        public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                            FLog.e(TAG, (isPrimary ? "Primary" : "Secondary")
                                    + " capture session configuration failed");
                            transitionToError("Camera session failed");
                        }
                    },
                    backgroundHandler);

        } catch (CameraAccessException e) {
            FLog.e(TAG, "Error creating capture session", e);
            transitionToError("Camera session creation error");
        }
    }

    /** Tracks configured sessions; starts pipeline when both are ready. */
    private int sessionsConfigured = 0;

    private synchronized void onSessionConfigured(boolean isPrimary) {
        sessionsConfigured++;
        FLog.d(TAG, "Session configured (" + (isPrimary ? "primary" : "secondary")
                + ") — " + sessionsConfigured + "/2");

        if (sessionsConfigured < 2) return;

        // Both sessions ready — start encoding
        try {
            recordingPipeline.startRecording();
            state = DualCameraState.RECORDING;
            recordingStartTime = SystemClock.elapsedRealtime();
            prefs.setRecordingInProgress(true);
            // Tactile confirmation that dual recording started (service-side,
            // gated by the Haptic feedback setting).
            com.fadcam.Utils.vibrateRecordingStart(this);

            // Save start time for timer recovery
            getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putLong(Constants.PREF_RECORDING_START_TIME, recordingStartTime)
                    .commit();

            persistRecordingTimelineState();
            startDurationLimitSession();
            broadcastActionWithTiming(Constants.BROADCAST_ON_DUAL_RECORDING_STARTED);
            FLog.i(TAG, "✅ Dual camera recording started");
        } catch (Exception e) {
            FLog.e(TAG, "Failed to start pipeline encoding", e);
            transitionToError("Encoder start failed");
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // HELPERS
    // ════════════════════════════════════════════════════════════════════

    /**
     * Returns the current timestamp formatted for watermark display.
     * Uses the same format as RecordingService for consistency.
     *
     * @return Formatted timestamp string.
     */
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
                FLog.w(TAG, "Error releasing wake lock", e);
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
                FLog.w(TAG, "Error closing " + label + " session", e);
            }
        }
        if (device != null) {
            try {
                device.close();
            } catch (Exception e) {
                FLog.w(TAG, "Error closing " + label + " camera", e);
            }
        }
    }

    /** Release everything in case of crash/destroy. */
    private void releaseAllResources() {
        if (durationLimitController != null) {
            durationLimitController.stopSession();
        }
        // Stop fallback snapshot loop
        fallbackMode = false;
        isCapturingSnapshot = false;

        if (recordingPipeline != null) {
            try {
                recordingPipeline.stopRecording();
            } catch (Exception e) {
                FLog.e(TAG, "Error stopping pipeline on destroy", e);
            }
            recordingPipeline = null;
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

    private void initializeDurationLimitController() {
        durationLimitController = new RecordingDurationLimitController(
                new RecordingDurationLimitController.Scheduler() {
                    @Override
                    public long elapsedRealtime() {
                        return SystemClock.elapsedRealtime();
                    }

                    @Override
                    public void postDelayed(Runnable runnable, long delayMs) {
                        mainHandler.postDelayed(runnable, delayMs);
                    }

                    @Override
                    public void removeCallbacks(Runnable runnable) {
                        mainHandler.removeCallbacks(runnable);
                    }
                },
                prefs::getMaximumRecordingDurationMs,
                () -> {
                    FLog.i(TAG, "Maximum recording duration reached; stopping current dual recording");
                    handleStopDualRecording();
                });

        // Haptic tick for the final 10 seconds of the countdown (gated by the
        // master + "Buttons & controls" toggles inside Utils).
        durationLimitController.setRemainingTickListener(remainingSeconds -> {
            try {
                com.fadcam.Utils.vibrateCountdownTick(DualCameraRecordingService.this, remainingSeconds);
            } catch (Exception ignored) {
            }
        });

        durationPreferenceListener = (preferences, key) -> {
            boolean customValueKey =
                    SharedPreferencesManager.PREF_MAX_RECORDING_DURATION_CUSTOM_MINUTES.equals(key)
                            || SharedPreferencesManager.PREF_MAX_RECORDING_DURATION_CUSTOM_SECONDS.equals(key);
            if (!SharedPreferencesManager.PREF_MAX_RECORDING_DURATION_OPTION.equals(key)
                    && !customValueKey) {
                return;
            }
            if (customValueKey
                    && !MaximumRecordingDuration.OPTION_CUSTOM.equals(
                    prefs.getMaximumRecordingDurationOption())) {
                return;
            }
            if (durationLimitController.onLimitChanged()) {
                long limitMs = prefs.getMaximumRecordingDurationMs();
                if (limitMs == 0L) {
                    FLog.i(TAG, "Maximum recording duration disabled for current dual session");
                } else {
                    FLog.i(TAG, "Maximum recording duration updated for current dual session: "
                            + limitMs + " ms");
                }
            }
        };
        prefs.sharedPreferences.registerOnSharedPreferenceChangeListener(durationPreferenceListener);
    }

    private void startDurationLimitSession() {
        if (durationLimitController == null) {
            return;
        }
        long limitMs = durationLimitController.startSession();
        if (limitMs == 0L) {
            FLog.d(TAG, "Maximum recording duration is disabled for this dual session");
        } else {
            FLog.i(TAG, "Maximum recording duration scheduled for current dual session: "
                    + limitMs + " ms");
        }
    }

    private void releaseDurationLimitController() {
        if (durationLimitController != null) {
            durationLimitController.stopSession();
        }
        if (prefs != null && durationPreferenceListener != null) {
            prefs.sharedPreferences.unregisterOnSharedPreferenceChangeListener(
                    durationPreferenceListener);
            durationPreferenceListener = null;
        }
    }

    // ── Error handling ────────────────────────────────────────────────

    private void transitionToError(@NonNull String reason) {
        DualCameraState previousState = state;
        FLog.e(TAG, "Dual camera error: " + reason);
        state = DualCameraState.ERROR;
        broadcastError(reason);
        if (previousState == DualCameraState.RECORDING || previousState == DualCameraState.PAUSED) {
            broadcastRecordingComplete(false);
        }

        mainHandler.post(() ->
                Toast.makeText(getApplicationContext(), reason, Toast.LENGTH_LONG).show());

        // Clean up and stop
        releaseAllResources();
        prefs.setRecordingInProgress(false);
        releaseWakeLock();
        stopSelf();
    }

    // ── Output file creation ────────────────────────────────────────────

    @Nullable
    private File createOutputFile() {
        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
        String filename = "DualCam_" + timestamp + "." + Constants.RECORDING_FILE_EXTENSION;
        
        String storageMode = prefs.getStorageMode();
        
        if (SharedPreferencesManager.STORAGE_MODE_CUSTOM.equals(storageMode)) {
            try {
                String treeUriString = prefs.getCustomStorageUri();
                if (treeUriString == null || treeUriString.isEmpty()) {
                    FLog.e(TAG, "No custom storage location configured");
                    return null;
                }
                DocumentFile treeDoc = com.fadcam.utils.RecordingStoragePaths.getSafCameraSourceDir(
                        this, treeUriString,
                        com.fadcam.utils.RecordingStoragePaths.CameraSource.DUAL, true);
                if (treeDoc == null || !treeDoc.exists() || !treeDoc.canWrite()) {
                    FLog.e(TAG, "Cannot write to custom storage location");
                    return null;
                }
                DocumentFile videoFile = treeDoc.createFile("video/mp4", filename);
                if (videoFile == null) {
                    FLog.e(TAG, "Failed to create SAF file: " + filename);
                    return null;
                }
                safRecordingPfd = getContentResolver().openFileDescriptor(videoFile.getUri(), "w");
                safRecordingUri = videoFile.getUri();
                safOutputFileName = filename;
                FLog.d(TAG, "SAF mode: created file descriptor for " + filename);
                return null;
            } catch (Exception e) {
                FLog.e(TAG, "Error creating SAF file", e);
                return null;
            }
        } else {
            File videoDir = com.fadcam.utils.RecordingStoragePaths.getInternalCameraSourceDir(
                    this, com.fadcam.utils.RecordingStoragePaths.CameraSource.DUAL, true);
            if (videoDir == null) {
                FLog.e(TAG, "Cannot create recording directory for dual camera");
                return null;
            }
            safRecordingPfd = null;
            safRecordingUri = null;
            safOutputFileName = null;
            return new File(videoDir, filename);
        }
    }

    // ── Notification ───────────────────────────────────────────────────

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        try {
            String channelName = prefs.getNotificationChannelName();
            android.app.NotificationChannel channel = new android.app.NotificationChannel(
                    CHANNEL_ID, channelName, android.app.NotificationManager.IMPORTANCE_LOW);
            channel.setSound(null, null);
            channel.enableVibration(false);
            android.app.NotificationManager nm = getSystemService(android.app.NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(channel);
        } catch (IllegalArgumentException e) {
            FLog.w(TAG, "Notification channel already exists, reusing: " + e.getMessage());
        }
    }

    private NotificationCompat.Builder buildDualCameraNotification() {
        String title = prefs.getNotificationTitle();
        if (title == null || title.isEmpty()) {
            title = getString(R.string.notification_video_recording);
        }
        String preset = prefs.getNotificationPreset();
        String text = prefs.getNotificationText(false);
        if (text == null || text.isEmpty()) {
            text = "Dual camera recording…";
        }

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(text)
                .setOngoing(true)
                .setSilent(true)
                .setPriority(NotificationCompat.PRIORITY_LOW);

        int smallIconResId;
        switch (preset) {
            case SharedPreferencesManager.NOTIFICATION_PRESET_SYSTEM_UPDATE:
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

        if (!SharedPreferencesManager.NOTIFICATION_PRESET_DEFAULT.equals(preset)) {
            Intent emptyIntent = new Intent();
            PendingIntent emptyPi = PendingIntent.getActivity(this, 0, emptyIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            builder.setContentIntent(emptyPi);
        }

        if (!prefs.isNotificationStopButtonHidden()) {
            Intent stopIntent = new Intent(this, DualCameraRecordingService.class);
            stopIntent.setAction(Constants.INTENT_ACTION_STOP_DUAL_RECORDING);
            PendingIntent stopPi = PendingIntent.getService(this, 2020, stopIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            builder.addAction(R.drawable.stop_rounded, getString(R.string.stop_recording), stopPi);
        }

        return builder;
    }

    private void startForegroundNotification() {
        createNotificationChannel();
        Notification notification = buildDualCameraNotification().build();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
                            | ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private void updatePausedNotification() {
        String text = prefs.getNotificationText(true);
        if (text != null && !text.isEmpty()) {
            NotificationCompat.Builder builder = buildDualCameraNotification()
                    .setContentText(text);
            android.app.NotificationManager nm = getSystemService(android.app.NotificationManager.class);
            if (nm != null) nm.notify(NOTIFICATION_ID, builder.build());
        }
    }

    private void updateResumedNotification() {
        startForegroundNotification();
    }

    // ── Timing persistence ────────────────────────────────────────────

    private void persistRecordingTimelineState() {
        getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putLong(Constants.PREF_RECORDING_START_TIME, recordingStartTime)
            .putLong(Constants.PREF_RECORDING_PAUSE_STARTED_AT, pauseStartedAt)
            .putLong(Constants.PREF_RECORDING_ACCUMULATED_PAUSED_DURATION, accumulatedPausedDurationMs)
            .commit();
    }

    private void clearRecordingTimelineState() {
        getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(Constants.PREF_RECORDING_START_TIME)
            .remove(Constants.PREF_RECORDING_PAUSE_STARTED_AT)
            .remove(Constants.PREF_RECORDING_ACCUMULATED_PAUSED_DURATION)
            .apply();
        FLog.d(TAG, "Cleared dual recording timeline state");
    }

    // ── Bookmarks ─────────────────────────────────────────────────────

    /** Milliseconds recorded so far, with paused stretches taken out. */
    private long getEffectiveTimelineMs() {
        if (recordingStartTime <= 0L) {
            return 0L;
        }
        long anchor = (state == DualCameraState.PAUSED && pauseStartedAt > 0L)
                ? pauseStartedAt
                : SystemClock.elapsedRealtime();
        return Math.max(0L, anchor - recordingStartTime - accumulatedPausedDurationMs);
    }

    /**
     * Stores a bookmark for the moment currently being recorded.
     *
     * <p>Dual recording never splits into segments, so the session timeline is
     * already the offset inside the output file. Ignored when nothing is being
     * recorded, because there would be no file to attach the mark to.</p>
     */
    private void addBookmarkAtCurrentPosition() {
        if (state != DualCameraState.RECORDING && state != DualCameraState.PAUSED) {
            FLog.w(TAG, "addBookmarkAtCurrentPosition: ignored — no active dual recording");
            return;
        }
        if (lastRecordingUriString == null || lastRecordingUriString.isEmpty()) {
            FLog.w(TAG, "addBookmarkAtCurrentPosition: ignored — no output file yet");
            return;
        }
        try {
            String mediaName = com.fadcam.bookmarks.BookmarkRepository
                    .resolveMediaName(this, Uri.parse(lastRecordingUriString));
            if (mediaName == null) {
                FLog.w(TAG, "addBookmarkAtCurrentPosition: could not resolve a name for "
                        + lastRecordingUriString);
                return;
            }
            long positionMs = getEffectiveTimelineMs();
            int total = com.fadcam.bookmarks.BookmarkRepository.getInstance(this)
                    .add(mediaName, positionMs);
            broadcastOnBookmarkAdded(positionMs, total);
        } catch (Exception e) {
            FLog.e(TAG, "Failed to add bookmark for " + lastRecordingUriString, e);
        }
    }

    /** Tells the UI that a bookmark landed, so it can confirm it to the user. */
    private void broadcastOnBookmarkAdded(long positionMs, int total) {
        Intent intent = new Intent(Constants.BROADCAST_ON_BOOKMARK_ADDED);
        intent.putExtra(Constants.EXTRA_BOOKMARK_POSITION_MS, positionMs);
        intent.putExtra(Constants.EXTRA_BOOKMARK_COUNT, total);
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
    }

    // ── Broadcasting ──────────────────────────────────────────────────

    /**
     * Sends a regular broadcast (not LocalBroadcastManager) so that receivers
     * registered with {@code context.registerReceiver()} in HomeFragment
     * can receive the events — matching the pattern used by RecordingService.
     */
    private void broadcastAction(@NonNull String action) {
        Intent intent = new Intent(action);
        intent.setPackage(getPackageName());
        sendBroadcast(intent);
    }

    /** Sends a broadcast with recording timing extras attached. */
    private void broadcastActionWithTiming(@NonNull String action) {
        Intent intent = new Intent(action);
        intent.setPackage(getPackageName());
        intent.putExtra(Constants.INTENT_EXTRA_RECORDING_START_TIME, recordingStartTime);
        intent.putExtra(Constants.INTENT_EXTRA_RECORDING_PAUSE_STARTED_AT, pauseStartedAt);
        intent.putExtra(Constants.INTENT_EXTRA_RECORDING_ACCUMULATED_PAUSED_DURATION, accumulatedPausedDurationMs);
        sendBroadcast(intent);
    }

    private void broadcastError(@NonNull String reason) {
        Intent intent = new Intent(Constants.BROADCAST_ON_DUAL_CAMERA_ERROR);
        intent.putExtra("error_reason", reason);
        sendBroadcast(intent);
    }

    private void broadcastRecordingComplete(boolean success) {
        try {
            Intent recordingCompleteIntent = new Intent(Constants.ACTION_RECORDING_COMPLETE);
            recordingCompleteIntent.putExtra(Constants.EXTRA_RECORDING_SUCCESS, success);
            if (lastRecordingUriString != null && !lastRecordingUriString.isEmpty()) {
                recordingCompleteIntent.putExtra(Constants.EXTRA_RECORDING_URI_STRING, lastRecordingUriString);
            }
            sendBroadcast(recordingCompleteIntent);
            FLog.d(TAG, "Broadcasted ACTION_RECORDING_COMPLETE for dual recording. success=" + success);
        } catch (Exception e) {
            FLog.e(TAG, "Error broadcasting ACTION_RECORDING_COMPLETE for dual recording", e);
        }
    }
}
