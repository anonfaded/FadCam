package com.fadcam.services;

import com.fadcam.Log;
import com.fadcam.FLog;
import com.fadcam.DiagnosticsStore;
import android.Manifest;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
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
import com.fadcam.MaximumRecordingDuration;
import com.fadcam.R;
import com.fadcam.RecordingDurationLimitController;
import com.fadcam.RecordingState;
import com.fadcam.SharedPreferencesManager; // Use your manager
import com.fadcam.Utils;
import com.fadcam.VideoCodec;
import com.fadcam.ui.LocationHelper;
import com.fadcam.ui.GeotagHelper;
import com.fadcam.utils.PhotoStorageHelper;
import com.fadcam.utils.RecordingStoragePaths;
import com.fadcam.utils.RuntimeCompat;
import com.fadcam.utils.ServiceStartPolicy;
import com.fadcam.service.ForensicsRecorder;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.ByteArrayOutputStream;
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
import android.media.Image;
import android.media.ImageReader;

public class RecordingService extends Service {

    private static final int NOTIFICATION_ID = 1;
    private static final String CHANNEL_ID = "RecordingServiceChannel";
    private static final String TAG = "RecordingService"; // Use standard Log TAG
    /**
     * Cadence for forensic sample submission while a subject is present.
     *
     * <p>Matches {@code CaptureThrottlePolicy.MEDIA_MIN_INTERVAL_MS}: the recorder
     * cannot store evidence faster than that, so submitting more often only encodes
     * JPEGs that are thrown away. It used to be 1600 ms, which is 37 encodes per
     * minute for at most 15 stored snapshots — pure CPU, memory bandwidth and heat.
     * A genuinely new appearance still submits immediately so a fast pass-through is
     * not missed.
     */
    private static final long FORENSICS_HEARTBEAT_INTERVAL_MS =
            com.fadcam.util.CaptureThrottlePolicy.MEDIA_MIN_INTERVAL_MS;
    /** Delay before the motion backend warmup, so camera startup is never starved. */
    private static final long MOTION_WARMUP_START_DELAY_MS = 3000L;
    private static volatile boolean isCameraResourceReleasing = false;
    
    private long lastStartAttemptTime = 0;
    private static final long MIN_START_INTERVAL_MS = 2000; // 2 seconds minimum between starts

    private CameraDevice cameraDevice;
    private CameraCaptureSession captureSession;
    private CaptureRequest.Builder captureRequestBuilder;
    private CameraCharacteristics currentCameraCharacteristics;
    private Surface previewSurface; // Surface from UI if preview enabled
    private boolean previewSurfaceAdded = false; // Flag to track if preview surface was added to session
    /**
     * Crop window currently applied via SCALER_CROP_REGION, in sensor coordinates.
     * Null means no crop has been applied (full active array is visible). Used by
     * tap-to-focus so the AF/AE region lands inside the visible field of view.
     */
    private android.graphics.Rect lastCropRegion = null;

    private LocationHelper locationHelper;
    private LocationGeocoder locationGeocoder;
    private GeotagHelper geotagHelper;
    private com.fadcam.sensors.SensorDataProvider sensorDataProvider;
    private com.fadcam.audio.NoiseMonitor noiseMonitor;
    private com.fadcam.network.WeatherService weatherService;

    // Location watermark update tracking - separate from main watermark update
    private long lastLocationWatermarkUpdateMs = 0;
    private String cachedLocationWatermarkText = "";

    // Cached GPS provider state — refreshed every 5s to avoid unnecessary system calls
    private boolean cachedGpsProviderEnabled = false;
    private long lastGpsProviderCheckMs = 0;
    
    private static final long GPS_PROVIDER_CHECK_INTERVAL_MS = 5000;

    private RecordingState recordingState = RecordingState.NONE;
    private boolean previewOnlyActive = false;
    private boolean pendingPreviewOnlyStart = false;
    private volatile boolean previewSessionConfigInFlight = false;
    private AtomicInteger ffmpegProcessingTaskCount = new AtomicInteger(0);

    private boolean isRecordingTorchEnabled = false;

    private CameraManager cameraManager; // Primary camera manager
    private Handler backgroundHandler; // For camera operations
    private HandlerThread backgroundThread; // Background thread for camera operations

    private long recordingStartTime;
    private long pauseStartedAt;
    private long accumulatedPausedDurationMs;

    private SharedPreferencesManager sharedPreferencesManager; // Your settings manager

    private boolean isRolloverClosingOldSession = false; // Flag to manage state during segment rollover when the old
                                                         // session is closing

    private WakeLock recordingWakeLock;

    private boolean isCameraOpen = false;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private RecordingDurationLimitController durationLimitController;
    private SharedPreferences.OnSharedPreferenceChangeListener durationPreferenceListener;

    // Cached notification large icon — decoded once and reused to avoid BitmapFactory on every build.
    private android.graphics.Bitmap cachedNotificationIconBitmap = null;
    private int cachedNotificationIconResId = -1;

    private boolean pendingStartRecording = false;

    private volatile boolean isStopping = false;

    // Camera switch state management
    private volatile boolean isSwitchingCamera = false;
    private CameraType cameraSwitchPreviousType = null;
    private long cameraSwitchStartTimeNanos = -1L;

    // Gate first-start until preview surface is ready (to avoid first-run EGL race)
    private boolean waitForPreviewBeforeStart = false;
    private Runnable previewWaitTimeoutRunnable = null;

    // Runtime camera control values that override saved preferences during active
    // recording
    private Integer runtimeExposureCompensation = null;
    private Boolean runtimeAeLock = null;
    private Integer runtimeAfMode = null;
    private boolean pendingDeferredAeLockEnable = false;
    private long aeLockConvergenceToken = 0L;
    private boolean manualAeLockActive = false;
    // Locks per-session output folder so split segments don't jump folders during camera switches.
    private RecordingStoragePaths.CameraSource recordingSessionCameraSource = RecordingStoragePaths.CameraSource.BACK;
    // Motion Lab (advanced, opt-in): sidecar analysis path. No control-flow impact unless explicitly wired later.
    private boolean motionLabEnabledForSession = false;
    private ImageReader motionAnalysisReader;
    private volatile int motionAnalysisWidth = 0;
    private volatile int motionAnalysisHeight = 0;
    private long motionAnalysisIntervalMs = 333L; // ~3fps default
    private long lastMotionAnalysisTimestampMs = 0L;
    private volatile com.fadcam.motion.domain.detector.MotionDetector motionDetector;
    private volatile com.fadcam.motion.domain.detector.AiObjectDetector efficientDetDetector;
    private com.fadcam.motion.domain.policy.MotionPolicy motionPolicy =
            new com.fadcam.motion.domain.policy.MotionPolicy();
    private com.fadcam.motion.domain.state.MotionStateMachine motionStateMachine;
    private boolean motionAutoPaused = false;
    /**
     * True when the current PAUSED state was entered while the live stream stayed
     * active. The encoder keeps running and only the recording file is suspended.
     */
    private boolean pausedWithLiveStream = false;
    /** Set if any pause in this session used live-stream idle (for cleanup). */
    private boolean liveIdleUsedThisSession = false;
    /**
     * Local File-backed outputs created by this session. SAF / custom-folder
     * recordings are URI-backed and never appear here (they are cleaned by the
     * live-idle block using their URI, and only when they provably contain no
     * media). This list exists so a failed start cannot leave a 0-byte local
     * file behind; only files that are exactly 0 bytes are ever deleted.
     */
    private final java.util.List<File> sessionCreatedLocalFiles =
            java.util.Collections.synchronizedList(new java.util.ArrayList<>());
    /** Cached Motion Lab settings; listener-updated, read per frame without prefs I/O. */
    private volatile com.fadcam.motion.domain.model.MotionRuntimeSettings motionRuntime;
    private int motionConsecutivePersonHits = 0;
    private long motionFramesAnalyzed = 0L;
    private long motionTriggerActionCount = 0L;
    private long motionSuppressedSignalCount = 0L;
    private float motionScoreEma = Float.NaN;
    private long motionLastDebugBroadcastMs = 0L;
    private long motionLastAiDetectionMs = 0L;
    /** Wall time of the last completed inference, used to adapt the AI cadence. */
    private volatile long motionLastInferenceDurationMs = 0L;
    /** Upper bound for the adapted inference interval; keeps evidence flowing. */
    private static final long MAX_AI_INTERVAL_MS = 6000L;
    /** Longest edge of the JPEG encoded for a forensic snapshot. */
    private static final int FORENSICS_SNAPSHOT_MAX_EDGE = 1280;
    /** Interval actually in use, for the Motion Lab readout. */
    private volatile long motionEffectiveAiIntervalMs = 0L;
    private volatile java.util.List<com.fadcam.motion.domain.detector.AiObjectDetector.DetectionResult>
            motionLastDetections = java.util.Collections.emptyList();
    private long motionLastTelemetryLogMs = 0L;
    private long motionPersonLikelyUntilMs = 0L;
    private volatile boolean motionOpenCvActive = false;
    private volatile boolean motionDetectorWarmupScheduled = false;
    private volatile boolean motionDetectorWarmupCompleted = false;
    private java.util.concurrent.ExecutorService motionDetectorWarmupExecutor;
    /**
     * Inference runs here, never on the camera ImageReader thread. Holding a camera
     * buffer for 100 ms of GPU work starves the capture session and drops encoder
     * frames; the analyzer thread must only copy the frame and move on.
     */
    private java.util.concurrent.ExecutorService aiInferenceExecutor;
    /**
     * Motion analysis runs here, not on the camera callback thread. MOG2 at up to
     * 1920 px between capture requests starves the encoder.
     */
    private java.util.concurrent.ExecutorService motionAnalysisExecutor;
    /**
     * Latest frame waiting for analysis. Single slot, not a queue: analysis always
     * works on the newest frame, and the camera gets its buffer back immediately.
     * A queue would hold camera buffers hostage and backpressure the HAL.
     */
    private final java.util.concurrent.atomic.AtomicReference<android.media.Image>
            pendingAnalysisImage = new java.util.concurrent.atomic.AtomicReference<>();
    private final java.util.concurrent.atomic.AtomicBoolean analysisDrainScheduled =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    private final java.util.concurrent.atomic.AtomicBoolean aiInferenceInFlight =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    private com.fadcam.service.ForensicsRecorder digitalForensicsEventRecorder;
    private boolean motionLastPersonDetected = false;
    private float motionLastPersonConfidence = 0f;
    private float motionLastScore = 0f;
    private float motionLastChangedArea = 0f;
    private float motionLastStrongArea = 0f;
    private float motionLastCenterX = 0.5f;
    private float motionLastCenterY = 0.5f;
    private float motionLastBoxWidth = 0.16f;
    private float motionLastBoxHeight = 0.16f;
    private String motionLastEventType = null;
    private String motionLastClassName = null;
    private float motionLastDetectionConfidence = 0f;
    private boolean motionLastGlobalSuppressed = false;
    private long motionLastForensicsHeartbeatMs = 0L;
    /**
     * True when the previous motion sample had a subject worth collecting evidence
     * for. Used to capture a NEW appearance immediately: the periodic heartbeat alone
     * can miss someone who walks through in under one interval.
     */
    private boolean forensicsSubjectPresentLastSample = false;
    private String motionLastOverlayPayload = null;
    private long lastOverlayPayloadLogMs = 0L;
    private long lastUprightRotationLogMs = 0L;
    private long motionJpegAttemptCount = 0L;
    private long motionJpegSuccessCount = 0L;
    private long motionJpegSkipCount = 0L;
    private long motionJpegEncodeTotalMs = 0L;
    private long motionLastPerfLogMs = 0L;

    // --- Lifecycle Methods ---
    @Override
    public void onCreate() {
        super.onCreate();
        // Initialize essential components first
        sharedPreferencesManager = SharedPreferencesManager.getInstance(getApplicationContext());
        initializeDurationLimitController();

        // Initialize LocationHelper when ANY location-dependent feature is on —
        // not just the "location watermark" toggle. If speed/altitude/accuracy/
        // compass/weather/UTM are enabled but the location watermark is off, the
        // GPS feed would otherwise never start and those watermarks would be
        // stuck at 0 (the exact bug users reported).
        if (sharedPreferencesManager != null && needsLocationData()) {
            locationHelper = new LocationHelper(this); // For watermark text
            // Also initialize geocoder for reverse geocoding (non-blocking, cached)
            // Uses Nominatim (free, open-source) - no API keys needed
            locationGeocoder = new LocationGeocoder();
        } else {
            // no location-dependent feature enabled
        }

        // Initialize GeotagHelper only if location embedding is enabled
        if (sharedPreferencesManager != null && sharedPreferencesManager.isLocationEmbeddingEnabled()) {
            try {
                geotagHelper = new GeotagHelper(this); // For metadata embedding
                boolean started = geotagHelper.startUpdates();
            } catch (Exception e) {
                FLog.e(TAG, "Error initializing GeotagHelper", e);
            }
        } else {
            // location embedding disabled
        }

        // Sensor providers (NoiseMonitor, SensorDataProvider, WeatherService)
        // are now initialized lazily in the START_RECORDING intent handler
        // — NOT here in onCreate — to prevent mic/sensor memory leaks
        // during preview-only mode and while the service is idle.
        //
        // Kill any zombie NoiseMonitor from a previous session that may have
        // been leaked before this fix was applied.
        com.fadcam.audio.NoiseMonitor.resetInstance();

        // Self-healing (issue #332): if a previous recording session was abandoned
        // because HyperOS/OEM background management killed the process mid-recording,
        // the file is a valid fMP4 that never got its hybrid moov. Convert it now
        // from the file contents so the tester's videos always end up playable.
        finalizeStaleRecordingIfNeeded();

        createNotificationChannel(); // Setup notifications early

        cameraManager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        if (cameraManager == null) {
            FLog.e(TAG, "Failed to get CameraManager service.");
            stopSelf(); // Cannot function without CameraManager
            return;
        }

        backgroundThread = new HandlerThread("CameraBackground");
        backgroundThread.start();
        backgroundHandler = new Handler(backgroundThread.getLooper());
        motionDetectorWarmupExecutor = java.util.concurrent.Executors.newSingleThreadExecutor();
        aiInferenceExecutor = java.util.concurrent.Executors.newSingleThreadExecutor();
        motionAnalysisExecutor = java.util.concurrent.Executors.newSingleThreadExecutor();

        // Initialize PowerManager and WakeLock
        android.os.PowerManager powerManager = (android.os.PowerManager) getSystemService(Context.POWER_SERVICE);
        recordingWakeLock = powerManager.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "FadCam:RecordingService");
        try {
            digitalForensicsEventRecorder = com.fadcam.FeatureRegistry.features()
                    .createForensicsRecorder(getApplicationContext());
        } catch (Exception e) {
            FLog.w(TAG, "Digital forensics event recorder init failed", e);
            digitalForensicsEventRecorder = null;
        }

        FLog.i(TAG, "Motion detectors deferred: service startup uses lazy background warmup only when Motion Lab is enabled");

        // Broadcast initial camera resource availability
        broadcastCameraResourceAvailability(true);
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
                sharedPreferencesManager::getMaximumRecordingDurationMs,
                () -> {
                    FLog.i(TAG, "Maximum recording duration reached; stopping current recording");
                    stopRecording();
                });

        // Haptic tick for the final 10 seconds of the countdown (gated by the
        // master + "Buttons & controls" toggles inside Utils).
        durationLimitController.setRemainingTickListener(remainingSeconds -> {
            try {
                com.fadcam.Utils.vibrateCountdownTick(RecordingService.this, remainingSeconds);
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
                    sharedPreferencesManager.getMaximumRecordingDurationOption())) {
                return;
            }
            if (durationLimitController.onLimitChanged()) {
                long limitMs = sharedPreferencesManager.getMaximumRecordingDurationMs();
                if (limitMs == 0L) {
                    FLog.i(TAG, "Maximum recording duration disabled for current session");
                } else {
                    FLog.i(TAG, "Maximum recording duration updated for current session: "
                            + limitMs + " ms");
                }
            }
        };
        sharedPreferencesManager.sharedPreferences.registerOnSharedPreferenceChangeListener(
                durationPreferenceListener);
    }

    private void startDurationLimitSession() {
        if (durationLimitController == null) {
            return;
        }
        long limitMs = durationLimitController.startSession();
        if (limitMs == 0L) {
            FLog.d(TAG, "Maximum recording duration is disabled for this session");
        } else {
            FLog.i(TAG, "Maximum recording duration scheduled for current session: "
                    + limitMs + " ms");
        }
    }

    private void releaseDurationLimitController() {
        if (durationLimitController != null) {
            durationLimitController.stopSession();
        }
        if (sharedPreferencesManager != null && durationPreferenceListener != null) {
            sharedPreferencesManager.sharedPreferences.unregisterOnSharedPreferenceChangeListener(
                    durationPreferenceListener);
            durationPreferenceListener = null;
        }
    }

    private void scheduleMotionDetectorWarmupIfNeeded() {
        if (!motionLabEnabledForSession || motionDetectorWarmupCompleted || motionDetectorWarmupScheduled) {
            return;
        }
        if (motionDetectorWarmupExecutor == null) {
            FLog.w(TAG, "Motion detector warmup requested before executor was ready");
            return;
        }

        motionDetectorWarmupScheduled = true;
        // Defer past camera/session startup: initialising OpenCV at the same moment
        // the capture session comes up starves the camera and shows as a multi-second
        // first-frame delay. The capture path has priority.
        mainHandler.postDelayed(() -> motionDetectorWarmupExecutor.execute(() -> {
            long startMs = SystemClock.elapsedRealtime();
            FLog.i(TAG, "Motion detector warmup started off the recording hot path");

            // The object model is owned by ObjectDetectionRuntime and loads when the
            // feature is enabled, not when a recording starts. sync() is a no-op when
            // it is already loaded and releases it when nothing needs it.
            com.fadcam.motion.domain.detector.ObjectDetectionRuntime.sync(getApplicationContext());
            try {
                boolean openCvExpected =
                        com.fadcam.FeatureRegistry.features().hasOpenCvMotionBackend();
                if (openCvExpected) {
                    try {
                        com.fadcam.motion.domain.detector.MotionDetector backend =
                                com.fadcam.FeatureRegistry.features()
                                .createOpenCvMotionDetector(getApplicationContext());
                        if (backend == null) {
                            throw new IllegalStateException("OpenCV factory returned null");
                        }
                        motionDetector = backend;
                        motionOpenCvActive = true;
                        FLog.i(TAG, "Motion detector backend: OpenCV MOG2");
                    } catch (Throwable t) {
                        // No silent downgrade in Full. Motion is disabled for the
                        // session and the failure is reported to the user.
                        motionDetector = null;
                        motionOpenCvActive = false;
                        FLog.e(TAG, "MOTION BACKEND FAILURE: OpenCV MOG2 could not initialize; "
                                + "motion detection disabled for this session", t);
                        mainHandler.post(() -> {
                            try {
                                android.widget.Toast.makeText(RecordingService.this,
                                        "Motion detection unavailable: the OpenCV backend failed to initialize.",
                                        android.widget.Toast.LENGTH_LONG).show();
                            } catch (Throwable ignored) {
                            }
                        });
                        disableMotionProcessingForSession("opencv_unavailable");
                    }
                }
                // No else branch: motion only arms in Full (see configureMotionLabForSession)
                // and Full always provides the OpenCV backend. Lite never runs motion, so
                // there is no second detector to fall back to.
            } finally {
                motionDetectorWarmupCompleted = true;
                long elapsedMs = SystemClock.elapsedRealtime() - startMs;
                FLog.i(TAG, "Motion detector warmup finished in " + elapsedMs
                        + " ms, aiReady="
                        + com.fadcam.motion.domain.detector.ObjectDetectionRuntime.isReady()
                        + ", openCv=" + motionOpenCvActive);
            }
       }), MOTION_WARMUP_START_DELAY_MS);
    }

    // method(applyExposureCompensation)-----------
    /**
     * Apply exposure compensation index if supported by the camera.
     * This will update the existing captureRequestBuilder and call
     * setRepeatingRequest.
     */
    private void applyExposureCompensation(int evIndex) {
        int clamped = evIndex;
        if (currentCameraCharacteristics != null) {
            Range<Integer> range = currentCameraCharacteristics.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE);
            if (range != null) {
                // Persist the device's actual range so RemoteStreamManager can surface it
                try {
                    if (sharedPreferencesManager != null) {
                        sharedPreferencesManager.setExposureCompensationRange(range.getLower(), range.getUpper());
                    }
                } catch (Exception ignored) {}
                clamped = Math.max(range.getLower(), Math.min(range.getUpper(), evIndex));
            }
            // Get and persist the EV step factor for web dashboard
            try {
                android.util.Rational stepRational = currentCameraCharacteristics.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP);
                if (stepRational != null && sharedPreferencesManager != null) {
                    float stepFloat = stepRational.floatValue();
                    sharedPreferencesManager.setExposureCompensationStep(stepFloat);
                }
            } catch (Exception ignored) {}
        }
        // Persist immediately so the next recording session always starts from the latest value.
        // This also protects against stale saved EV when runtime overrides are cleared.
        try {
            if (sharedPreferencesManager != null) {
                sharedPreferencesManager.setSavedExposureCompensation(clamped);
            }
        } catch (Exception ignored) {
        }

        if (currentCameraCharacteristics == null || captureRequestBuilder == null || captureSession == null)
            return;
        final int clampedEv = clamped;

        // Track runtime exposure compensation to override saved preferences
        runtimeExposureCompensation = clampedEv;

        // CRITICAL: Apply exposure through GL pipeline for immediate visual effect
        // Convert EV index to actual EV stops for GL shader
        float evStops = 0.0f;
        try {
            android.util.Rational stepRational = currentCameraCharacteristics
                    .get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP);
            if (stepRational != null) {
                evStops = clampedEv * stepRational.floatValue();
            } else {
                // Fallback: assume 1/3 EV step (common default)
                evStops = clampedEv * 0.33f;
            }
        } catch (Exception e) {
            evStops = clampedEv * 0.33f; // Safe fallback
        }

        // Apply exposure through GL pipeline for immediate visual effect in preview and
        // recording
        if (glRecordingPipeline != null) {
            glRecordingPipeline.setExposureCompensation(evStops);
            FLog.d(TAG,
                    "Applied EV compensation through GL pipeline: index=" + clampedEv + " -> " + evStops + " EV stops");
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
                FLog.d(TAG, "applyExposureCompensation: AE lock was enabled; temporarily clearing to apply EV");
                captureRequestBuilder.set(CaptureRequest.CONTROL_AE_LOCK, false);
            }

            captureRequestBuilder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, clampedEv);
            FLog.d(TAG, "applyExposureCompensation: setting EV index=" + clampedEv + " (hadAeLock=" + hadAeLock
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
                                        FLog.d(TAG, "EV prime capture completed: Applied EV=" + appliedEv +
                                                ", AE Mode=" + aeMode + ", Target EV=" + clampedEv);
                                    }

                                    @Override
                                    public void onCaptureFailed(@NonNull CameraCaptureSession session,
                                            @NonNull CaptureRequest request,
                                            @NonNull android.hardware.camera2.CaptureFailure failure) {
                                        FLog.w(TAG, "EV prime capture failed: " + failure.getReason());
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
                                        FLog.d(TAG, "Updated high-speed repeating burst with EV=" + clampedEv);
                                    } else {
                                        captureSession.setRepeatingRequest(captureRequestBuilder.build(), null,
                                                backgroundHandler);
                                        FLog.d(TAG, "Updated standard repeating request with EV=" + clampedEv);
                                    }
                                } catch (Exception e) {
                                    FLog.d(TAG, "Delayed setRepeating failed: " + e.getMessage());
                                }
                            });
                        }

                        // If first attempt succeeded, break
                        break;
                    } catch (Exception attemptEx) {
                        FLog.d(TAG, "applyExposureCompensation: attempt " + (attempt + 1) + " failed: "
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
                    FLog.d(TAG, "Even fallback setRepeating failed: " + fallbackEx.getMessage());
                }
            }
            // Note: we intentionally do not re-enable AE lock here. Restoring it
            // immediately can prevent the
            // exposure compensation from taking effect on some devices. The AE lock tile
            // controls AE lock explicitly.
        } catch (Exception e) {
            // Catch any remaining exceptions to avoid crashing the service
            FLog.w(TAG, "applyExposureCompensation: Unexpected error: " + e.getMessage());
        }
    }
    // method(applyExposureCompensation)-----------

    /**
     * Toggle AE lock during a running session.
     */
    private void applyAeLock(boolean lock) {
        try {
            if (sharedPreferencesManager != null) {
                sharedPreferencesManager.setSavedAeLock(lock);
            }
        } catch (Exception ignored) {
        }

        if (currentCameraCharacteristics == null || captureRequestBuilder == null || captureSession == null)
            return;
        Boolean aeLockSupported = currentCameraCharacteristics.get(CameraCharacteristics.CONTROL_AE_LOCK_AVAILABLE);
        if (aeLockSupported == null || !aeLockSupported)
            return;

        // Track runtime AE lock to override saved preferences
        runtimeAeLock = lock;
        aeLockConvergenceToken++;
        final long lockToken = aeLockConvergenceToken;

        try {
            if (!lock) {
                if (manualAeLockActive) {
                    // Return to normal AE pipeline when manual lock is released.
                    captureRequestBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
                    captureRequestBuilder.set(CaptureRequest.CONTROL_AE_LOCK, false);
                    captureSession.setRepeatingRequest(captureRequestBuilder.build(), null, backgroundHandler);
                    manualAeLockActive = false;
                    FLog.d(TAG, "applyAeLock(false): released manual AE lock and restored AE_MODE_ON");
                } else {
                    captureRequestBuilder.set(CaptureRequest.CONTROL_AE_LOCK, false);
                    captureSession.setRepeatingRequest(captureRequestBuilder.build(), null, backgroundHandler);
                }
                return;
            }

            if (lock) {
                // Force EV convergence first, then lock AE. This avoids locking too early on
                // fresh starts where AE has not yet converged to the requested compensation.
                int evToUse = (runtimeExposureCompensation != null)
                        ? runtimeExposureCompensation
                        : (sharedPreferencesManager != null ? sharedPreferencesManager.getSavedExposureCompensation() : 0);
                if (currentCameraCharacteristics != null) {
                    Range<Integer> range = currentCameraCharacteristics
                            .get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE);
                    if (range != null) {
                        evToUse = Math.max(range.getLower(), Math.min(range.getUpper(), evToUse));
                    }
                }
                captureRequestBuilder.set(CaptureRequest.CONTROL_AE_LOCK, false);
                captureSession.setRepeatingRequest(captureRequestBuilder.build(), null, backgroundHandler);
                applyExposureCompensation(evToUse);
                FLog.d(TAG, "applyAeLock(true): refreshed EV while unlocked (index=" + evToUse
                        + "), waiting for AE convergence + exposure stability");
                attemptAeLockAfterConvergence(evToUse, 0, lockToken, -1L, -1, 0);
                return;
            }
        } catch (CameraAccessException | IllegalStateException e) {
            // ignore
        }
    }

    private void attemptAeLockAfterConvergence(final int targetEv, final int attempt, final long token,
            final long prevExposureNs, final int prevIso, final int stableCount) {
        if (backgroundHandler == null || captureSession == null || captureRequestBuilder == null) {
            return;
        }
        // Cancel stale convergence workflows.
        if (token != aeLockConvergenceToken) {
            return;
        }
        // Respect current user intent if lock was turned off mid-flight.
        boolean shouldStillLock = (runtimeAeLock != null)
                ? runtimeAeLock
                : (sharedPreferencesManager != null && sharedPreferencesManager.isAeLockedSaved());
        if (!shouldStillLock) {
            return;
        }

        final int maxAttempts = 22; // ~2.6s at 120ms intervals
        final int stableRequired = 4;
        try {
            captureSession.capture(captureRequestBuilder.build(), new CameraCaptureSession.CaptureCallback() {
                @Override
                public void onCaptureCompleted(@NonNull CameraCaptureSession session,
                        @NonNull CaptureRequest request,
                        @NonNull android.hardware.camera2.TotalCaptureResult result) {
                    if (token != aeLockConvergenceToken) {
                        return;
                    }
                    Integer aeState = result.get(android.hardware.camera2.CaptureResult.CONTROL_AE_STATE);
                    Integer appliedEv = result
                            .get(android.hardware.camera2.CaptureResult.CONTROL_AE_EXPOSURE_COMPENSATION);
                    Long exposureTimeNs = result.get(android.hardware.camera2.CaptureResult.SENSOR_EXPOSURE_TIME);
                    Integer iso = result.get(android.hardware.camera2.CaptureResult.SENSOR_SENSITIVITY);
                    boolean converged = aeState != null && (aeState == android.hardware.camera2.CaptureResult.CONTROL_AE_STATE_CONVERGED
                            || aeState == android.hardware.camera2.CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED
                            || aeState == android.hardware.camera2.CaptureResult.CONTROL_AE_STATE_LOCKED);
                    boolean evMatched = appliedEv != null && appliedEv == targetEv;
                    boolean frameStable = false;
                    if (exposureTimeNs != null && iso != null && prevExposureNs > 0 && prevIso > 0) {
                        double expDeltaRatio = Math.abs(exposureTimeNs - prevExposureNs)
                                / (double) Math.max(1L, prevExposureNs);
                        double isoDeltaRatio = Math.abs(iso - prevIso) / (double) Math.max(1, prevIso);
                        frameStable = expDeltaRatio < 0.08d && isoDeltaRatio < 0.10d;
                    }
                    int nextStableCount = frameStable ? (stableCount + 1) : 0;

                    if ((converged && evMatched && nextStableCount >= stableRequired) || attempt >= maxAttempts) {
                        try {
                            if (captureRequestBuilder == null || captureSession == null || token != aeLockConvergenceToken) {
                                return;
                            }
                            boolean manualLocked = tryApplyManualExposureLock(exposureTimeNs, iso);
                            if (!manualLocked) {
                                captureRequestBuilder.set(CaptureRequest.CONTROL_AE_LOCK, true);
                                captureSession.setRepeatingRequest(captureRequestBuilder.build(), null, backgroundHandler);
                            }
                            FLog.d(TAG, "applyAeLock(true): lock enabled after convergence check. mode="
                                    + (manualLocked ? "manual_ae_off" : "ae_lock")
                                    + ", attempt=" + attempt
                                    + ", aeState=" + aeState + ", appliedEv=" + appliedEv
                                    + ", stableCount=" + nextStableCount + ", exposureNs=" + exposureTimeNs
                                    + ", iso=" + iso);
                        } catch (Exception e) {
                            FLog.w(TAG, "applyAeLock(true): failed to enable lock after convergence check", e);
                        }
                        return;
                    }

                    backgroundHandler.postDelayed(
                            () -> attemptAeLockAfterConvergence(
                                    targetEv,
                                    attempt + 1,
                                    token,
                                    exposureTimeNs != null ? exposureTimeNs : prevExposureNs,
                                    iso != null ? iso : prevIso,
                                    nextStableCount),
                            120);
                }

                @Override
                public void onCaptureFailed(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request,
                        @NonNull android.hardware.camera2.CaptureFailure failure) {
                    if (token != aeLockConvergenceToken || backgroundHandler == null) {
                        return;
                    }
                    if (attempt >= maxAttempts) {
                        return;
                    }
                    backgroundHandler.postDelayed(
                            () -> attemptAeLockAfterConvergence(targetEv, attempt + 1, token, prevExposureNs, prevIso,
                                    stableCount),
                            220);
                }
            }, backgroundHandler);
        } catch (Exception e) {
            if (attempt >= maxAttempts || backgroundHandler == null || token != aeLockConvergenceToken) {
                return;
            }
            backgroundHandler.postDelayed(
                    () -> attemptAeLockAfterConvergence(targetEv, attempt + 1, token, prevExposureNs, prevIso,
                            stableCount),
                    250);
        }
    }

    /**
     * Change AF mode (e.g., continuous video, off, etc.) when supported.
     */
    private void applyAfMode(int afMode) {
        try {
            if (sharedPreferencesManager != null) {
                sharedPreferencesManager.setSavedAfMode(afMode);
            }
        } catch (Exception ignored) {
        }

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

    /**
     * Apply zoom ratio to the current capture session.
     * Reads saved pan offsets so zoom + pan are always applied together.
     * Also saves the zoom ratio to preferences for the current camera type.
     */
    private void applyZoomRatio(float zoomRatio) {
        CameraType currentCamera = sharedPreferencesManager.getCameraSelection();
        float panX = sharedPreferencesManager.getSpecificPanX(currentCamera);
        float panY = sharedPreferencesManager.getSpecificPanY(currentCamera);
        applyZoomAndPan(zoomRatio, panX, panY);
    }

    /**
     * Apply zoom + 2-D pan to the capture session via SCALER_CROP_REGION.
     * <p>
     * Computes a crop rectangle that achieves the requested zoom ratio and
     * offsets its centre by the normalised pan values (−1…+1 → full sensor
     * edge in each direction, 0 = centre).
     * <p>
     * On API ≥ 30 CONTROL_ZOOM_RATIO is reset to 1.0 so that the crop region
     * alone controls both zoom and pan without double-zooming.
     *
     * @param zoomRatio zoom magnification (≥ 1.0)
     * @param panX      horizontal pan, −1.0 (left) … +1.0 (right), 0 = centre
     * @param panY      vertical pan,   −1.0 (top)  … +1.0 (bottom), 0 = centre
     */
    private void applyZoomAndPan(float zoomRatio, float panX, float panY) {
        FLog.d(TAG, "applyZoomAndPan: ratio=" + zoomRatio + " panX=" + panX + " panY=" + panY);

        if (captureRequestBuilder == null || captureSession == null) {
            FLog.w(TAG, "Cannot apply zoom/pan - capture session not ready");
            return;
        }

        try {
            android.util.Range<Float> zoomRatioRange = null;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && currentCameraCharacteristics != null) {
                zoomRatioRange = currentCameraCharacteristics.get(
                        android.hardware.camera2.CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE);
            }

            float minSupportedZoom = zoomRatioRange != null ? zoomRatioRange.getLower() : 1.0f;
            float maxSupportedZoom = zoomRatioRange != null ? zoomRatioRange.getUpper() : Math.max(1.0f, zoomRatio);
            float clampedZoom = Math.max(minSupportedZoom, Math.min(maxSupportedZoom, zoomRatio));
            FLog.d(TAG, "applyZoomAndPan caps: requested=" + zoomRatio
                    + " applied=" + clampedZoom
                    + " min=" + minSupportedZoom
                    + " max=" + maxSupportedZoom
                    + " range=" + zoomRatioRange
                    + " displayToSensor=" + getDisplayToSensorRotation()
                    + " sensorOrientation=" + getCurrentSensorOrientationDegrees());
            float clampedPanX = Math.max(-1.0f, Math.min(1.0f, panX));
            float clampedPanY = Math.max(-1.0f, Math.min(1.0f, panY));

            // The crop region lives in SENSOR coordinates while the user drags on the
            // upright preview. The pan (display space) must be rotated by the sensor's
            // display orientation before offsetting the crop centre, otherwise panning
            // is transposed/flipped (e.g. dragging right moves the view vertically).
            float sensorPanX = clampedPanX;
            float sensorPanY = clampedPanY;
            try {
                switch (getDisplayToSensorRotation()) {
                    case 90:
                        // Display right/down ↔ sensor +Y/−X : cropShift = −R⁻¹(drag)
                        sensorPanX = clampedPanY;
                        sensorPanY = -clampedPanX;
                        break;
                    case 180:
                        sensorPanX = -clampedPanX;
                        sensorPanY = -clampedPanY;
                        break;
                    case 270:
                        sensorPanX = -clampedPanY;
                        sensorPanY = clampedPanX;
                        break;
                    default:
                        break; // 0°
                }
            } catch (Exception e) {
                sensorPanX = clampedPanX;
                sensorPanY = clampedPanY;
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && zoomRatioRange != null && clampedZoom < 1.0f) {
                if (currentCameraCharacteristics != null) {
                    android.graphics.Rect activeArray = currentCameraCharacteristics
                            .get(android.hardware.camera2.CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
                    if (activeArray != null) {
                        captureRequestBuilder.set(
                                android.hardware.camera2.CaptureRequest.SCALER_CROP_REGION,
                                activeArray);
                    }
                }
                // CONTROL_ZOOM_RATIO < 1 makes the active array represent the post-zoom FOV,
                // so tap-to-focus must keep using the full active array here.
                lastCropRegion = null;

                captureRequestBuilder.set(
                        android.hardware.camera2.CaptureRequest.CONTROL_ZOOM_RATIO,
                        clampedZoom);
                captureSession.setRepeatingRequest(
                        captureRequestBuilder.build(), null, backgroundHandler);

                CameraType currentCamera = sharedPreferencesManager.getCameraSelection();
                sharedPreferencesManager.setSpecificZoomRatio(currentCamera, clampedZoom);
                sharedPreferencesManager.setSpecificPan(currentCamera, 0.0f, 0.0f);
                FLog.d(TAG, "applyZoomAndPan: CONTROL_ZOOM_RATIO ultra-wide ratio=" + clampedZoom);
                return;
            }

            if (currentCameraCharacteristics != null) {
                android.graphics.Rect activeArray = currentCameraCharacteristics
                        .get(android.hardware.camera2.CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
                if (activeArray != null) {
                    int sensorW = activeArray.width();
                    int sensorH = activeArray.height();

                    // Crop size that achieves the requested zoom
                    int cropW = Math.round(sensorW / clampedZoom);
                    int cropH = Math.round(sensorH / clampedZoom);

                    // Maximum pan range from centre (so the crop stays inside the sensor)
                    int maxOffX = (sensorW - cropW) / 2;
                    int maxOffY = (sensorH - cropH) / 2;

                    // Crop centre with pan offset (pan already rotated into sensor space)
                    int cx = activeArray.left + sensorW / 2 + Math.round(sensorPanX * maxOffX);
                    int cy = activeArray.top  + sensorH / 2 + Math.round(sensorPanY * maxOffY);

                    int cropLeft  = Math.max(activeArray.left,  cx - cropW / 2);
                    int cropTop   = Math.max(activeArray.top,   cy - cropH / 2);
                    int cropRight = Math.min(activeArray.right,  cropLeft + cropW);
                    int cropBottom = Math.min(activeArray.bottom, cropTop  + cropH);

                    android.graphics.Rect cropRegion = new android.graphics.Rect(
                            cropLeft, cropTop, cropRight, cropBottom);
                    captureRequestBuilder.set(
                            android.hardware.camera2.CaptureRequest.SCALER_CROP_REGION, cropRegion);
                    lastCropRegion = new android.graphics.Rect(cropRegion);

                    // On API ≥ 30 reset zoom ratio to 1 so crop is the sole zoom/pan mechanism
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        captureRequestBuilder.set(
                                android.hardware.camera2.CaptureRequest.CONTROL_ZOOM_RATIO, 1.0f);
                    }

                    captureSession.setRepeatingRequest(
                            captureRequestBuilder.build(), null, backgroundHandler);

                    // Persist both zoom and pan
                    CameraType currentCamera = sharedPreferencesManager.getCameraSelection();
                    sharedPreferencesManager.setSpecificZoomRatio(currentCamera, clampedZoom);
                    sharedPreferencesManager.setSpecificPan(currentCamera, clampedPanX, clampedPanY);

                    FLog.d(TAG, "applyZoomAndPan OK – crop=" + cropRegion + " zoom=" + clampedZoom);
                    return;
                }
            }

            // Fallback: API ≥ 30 zoom-ratio only (no pan), or warn for older APIs
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                // With CONTROL_ZOOM_RATIO != 1 the active array is the post-zoom FOV,
                // so tap-to-focus must not apply the stale crop window.
                lastCropRegion = null;
                captureRequestBuilder.set(
                        android.hardware.camera2.CaptureRequest.CONTROL_ZOOM_RATIO, clampedZoom);
                captureSession.setRepeatingRequest(
                        captureRequestBuilder.build(), null, backgroundHandler);
                CameraType currentCamera = sharedPreferencesManager.getCameraSelection();
                sharedPreferencesManager.setSpecificZoomRatio(currentCamera, clampedZoom);
                FLog.d(TAG, "applyZoomAndPan: CONTROL_ZOOM_RATIO fallback (no sensor info) ratio=" + clampedZoom);
            } else {
                FLog.w(TAG, "applyZoomAndPan: no sensor characteristics available; zoom not applied");
            }

        } catch (android.hardware.camera2.CameraAccessException | IllegalStateException e) {
            FLog.e(TAG, "applyZoomAndPan failed: " + e.getMessage());
        }
    }

    private void applyFrontVideoMirror(boolean enabled) {
        if (glRecordingPipeline == null) {
            return;
        }
        glRecordingPipeline.setFrontVideoMirrorEnabled(enabled);
        FLog.d(TAG, "Applied front video mirror=" + enabled + " to GL pipeline");
    }

    /**
     * Perform a tap-to-focus at normalized preview coordinates (0..1).
     * This method maps preview coordinates into sensor region space and issues AF
     * regions + trigger.
     */
    private void performTapToFocus(float nx, float ny) {
        FLog.d(TAG, "performTapToFocus called with normalized coords: " + nx + ", " + ny);

        if (currentCameraCharacteristics == null || captureRequestBuilder == null || captureSession == null
                || cameraDevice == null) {
            FLog.w(TAG, "Cannot perform tap-to-focus: camera components not ready");
            return;
        }

        // Metering regions require sensor coordinates. The tap arrives in display
        // space (upright preview), so rotate it into the sensor's coordinate space
        // first — otherwise the focus/metering region lands at a transposed location
        // on rotated (portrait) sensors.
        Rect activeArray = currentCameraCharacteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
        if (activeArray == null) {
            FLog.w(TAG, "Cannot perform tap-to-focus: active array size not available");
            return;
        }

        // The visible field of view is the current zoom/pan crop window, further
        // cropped by the camera to the output stream's aspect ratio. Mapping straight
        // to the active array would put the AF region outside what the user sees
        // whenever zoom != 1 or the stream aspect differs from the sensor aspect.
        Rect visibleRect = new Rect(lastCropRegion != null ? lastCropRegion : activeArray);
        try {
            if (sharedPreferencesManager != null) {
                android.util.Size res = sharedPreferencesManager.getCameraResolution();
                float streamAR = Math.max(res.getWidth(), res.getHeight())
                        / (float) Math.max(1, Math.min(res.getWidth(), res.getHeight()));
                float cropAR = visibleRect.width() / (float) Math.max(1, visibleRect.height());
                if (cropAR > streamAR + 0.001f) {
                    int w = Math.round(visibleRect.height() * streamAR);
                    int left = visibleRect.left + (visibleRect.width() - w) / 2;
                    visibleRect.set(left, visibleRect.top, left + w, visibleRect.bottom);
                } else if (cropAR < streamAR - 0.001f) {
                    int h = Math.round(visibleRect.width() / streamAR);
                    int top = visibleRect.top + (visibleRect.height() - h) / 2;
                    visibleRect.set(visibleRect.left, top, visibleRect.right, top + h);
                }
            }
        } catch (Exception e) {
            FLog.w(TAG, "tap-to-focus: stream aspect mapping failed: " + e.getMessage());
        }

        // A mirrored front preview shows the scene flipped, so mirror the tap too.
        float displayNX = nx;
        try {
            if (sharedPreferencesManager != null
                    && sharedPreferencesManager.getCameraSelection() == CameraType.FRONT
                    && sharedPreferencesManager.isFrontVideoMirrorEnabled()) {
                displayNX = 1f - nx;
            }
        } catch (Exception ignored) {
        }

        float sensorNX = displayNX;
        float sensorNY = ny;
        try {
            switch (getDisplayToSensorRotation()) {
                case 90:
                    sensorNX = ny;
                    sensorNY = 1f - displayNX;
                    break;
                case 180:
                    sensorNX = 1f - displayNX;
                    sensorNY = 1f - ny;
                    break;
                case 270:
                    sensorNX = 1f - ny;
                    sensorNY = displayNX;
                    break;
                default:
                    break; // 0°
            }
        } catch (Exception e) {
            sensorNX = displayNX;
            sensorNY = ny;
        }

        int x = visibleRect.left + Math.round(sensorNX * visibleRect.width());
        int y = visibleRect.top + Math.round(sensorNY * visibleRect.height());

        // Clamp coordinates to the visible rect before creating the rectangle
        // This prevents IllegalArgumentException when normalized coords go out of bounds (>1.0)
        x = Math.max(visibleRect.left, Math.min(visibleRect.right, x));
        y = Math.max(visibleRect.top, Math.min(visibleRect.bottom, y));

        FLog.d(TAG, "Mapped to sensor coords: " + x + ", " + y
                + " (visible: " + visibleRect + ", active array: " + activeArray + ")");

        // Create a small region around the tap point, sized from the visible field
        // of view so it stays symmetric when zoomed in.
        int half = Math.max(10, Math.min(visibleRect.width(), visibleRect.height()) / 20);
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

            FLog.d(TAG, "Tap-to-focus triggered at normalized coords: " + nx + ", " + ny + " -> sensor coords: " + x
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
                            FLog.d(TAG, "Restored AF mode to: " + afModeToRestore + " after tap-to-focus");
                        }
                    } catch (Exception e) {
                        FLog.w(TAG, "Error restoring AF mode after tap-to-focus: " + e.getMessage());
                    }
                }, 1000); // 1 second delay to allow focus to complete
            }
        } catch (CameraAccessException | IllegalStateException e) {
            // ignore
        }
    }

    // --- onStartCommand (Ensure START action ignores processing state) ---
    // ═══ Self-healing hybrid-MP4 finalization (issue #332) ═══
    // A recording left as a valid fMP4 without the hybrid moov is unplayable in
    // consumer apps. All finalization state still exists in the FILE, so we
    // rebuild the moov from the file contents via
    // FragmentedMp4Muxer.finalizeAbandonedFile.
    //
    // DB-DRIVEN: the video index tracks per-file finalization state (0=pending,
    // 1=ok, 2=unrepairable). The scan ONLY touches rows still pending — confirmed
    // files are never re-examined, so repeated scans cost nothing. Live recordings
    // are excluded by the isRecordingInProgress guard and the is_temporary flag.

    private boolean staleFinalizeAttempted = false;

    private void finalizeStaleRecordingIfNeeded() {
        if (staleFinalizeAttempted) return;
        staleFinalizeAttempted = true;
        runSelfHealingScan(this, null);
    }

    /**
     * Scans pending (unfinalized) recordings and repairs them. Runs on a
     * background thread; safe to call from any component.
     *
     * @param context Application or activity context.
     * @param onDone  Optional UI-thread callback after the scan (banner refresh).
     */
    public static void runSelfHealingScan(final Context context, @Nullable final Runnable onDone) {
        try {
            final android.content.Context app = context.getApplicationContext();
            final SharedPreferencesManager prefs = SharedPreferencesManager.getInstance(app);
            // Never scan while a recording is active — its file may be mid-write.
            // FadCam's camera recorder owns isRecordingInProgress().
            if (prefs.isRecordingInProgress()) {
                if (onDone != null) onDone.run();
                return;
            }
            // Screen recording keeps its own state (it never sets isRecordingInProgress),
            // so it must be checked separately — otherwise the scan can finalize a file
            // that is still being written (issue #357).
            //
            // FadMic (coming soon) must register its own recording state and be added
            // to this guard for exactly the same reason.
            final String screenState = prefs.getScreenRecordingState();
            if ("IN_PROGRESS".equals(screenState) || "PAUSED".equals(screenState)) {
                FLog.d(TAG, "Self-healing scan skipped: screen recording is " + screenState);
                if (onDone != null) onDone.run();
                return;
            }
            // Single-flight: coalesce bursts (e.g. many DB rows indexed at once)
            // into one scan; if one is already running, request one follow-up.
            if (!scanRunning.compareAndSet(false, true)) {
                scanRerunRequested.set(true);
                if (onDone != null) onDone.run();
                return;
            }
            new Thread(() -> {
                try {
                    doSelfHealingScan(app);
                } catch (Exception e) {
                    FLog.w(TAG, "Self-healing scan failed", e);
                } finally {
                    scanRunning.set(false);
                    // Re-run once if new invalidation arrived while we were busy.
                    if (scanRerunRequested.compareAndSet(true, false)) {
                        runSelfHealingScan(app, null);
                    }
                    // A repair may have been recorded by an observer-triggered scan
                    // (onDone == null) — notify the Records UI so the banner appears
                    // without requiring a manual refresh.
                    try {
                        android.content.Intent repair =
                                new android.content.Intent(ACTION_SELF_HEAL_REPAIRED)
                                        .setPackage(app.getPackageName());
                        app.sendBroadcast(repair);
                    } catch (Exception ignored) {}
                    if (onDone != null) {
                        try {
                            new android.os.Handler(android.os.Looper.getMainLooper()).post(onDone);
                        } catch (Exception ignored) {}
                    }
                }
            }, "SelfHealingScan").start();
        } catch (Exception e) {
            FLog.w(TAG, "Self-healing scan failed to start", e);
            if (onDone != null) onDone.run();
        }
    }

    /**
     * URIs already attempted in this process. A second attempt is always refused:
     * the finalizer is idempotent, so one pass is enough, and refusing a second one
     * makes a repair loop impossible even if another path regresses (issue #357 —
     * the same file was repaired 4× in 5.7 s because each write reset the DB state).
     */
    private static final java.util.Set<String> REPAIR_ATTEMPTED_THIS_PROCESS =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    private static final java.util.concurrent.atomic.AtomicBoolean scanRunning =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    private static final java.util.concurrent.atomic.AtomicBoolean scanRerunRequested =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    /**
     * URIs whose files are currently being finalized by an in-flight
     * stopRecording. The self-healing scan must NEVER touch these: a file can
     * be mid-finalize while isRecordingInProgress() is already false (the flag
     * is cleared at the start of the stop sequence, finalization runs later on
     * the stop thread). Scanning it then would double-finalize and falsely
     * report a repair (banner) for a manually stopped recording.
     */
    private static final java.util.Set<String> IN_FLIGHT_FINALIZATIONS =
            java.util.Collections.synchronizedSet(new java.util.HashSet<String>());

    private static void addInFlightFinalization(String uriString) {
        if (uriString == null || uriString.isEmpty()) return;
        IN_FLIGHT_FINALIZATIONS.add(uriString);
    }

    private static void removeInFlightFinalization(String uriString) {
        if (uriString == null || uriString.isEmpty()) return;
        IN_FLIGHT_FINALIZATIONS.remove(uriString);
    }

    /** Broadcast after a self-healing scan finishes (may have repaired a file). */
    public static final String ACTION_SELF_HEAL_REPAIRED =
            "com.fadcam.action.SELF_HEAL_REPAIRED";

    /**
     * Delay before a failed (unrepairable) file is retried. Transient failures —
     * a finalizer bug fixed in a later build, a temporarily-locked file, a
     * mid-write snapshot — must not permanently abandon a recoverable recording.
     */
    public static final long REPAIR_RETRY_DELAY_MS = 24L * 60 * 60 * 1000; // 24h

    private static void doSelfHealingScan(final android.content.Context app) {
                    final SharedPreferencesManager prefs = SharedPreferencesManager.getInstance(app);
                    com.fadcam.data.VideoIndexRepository repo =
                            com.fadcam.data.VideoIndexRepository.getInstance(app);
                    // One-time cleanup: earlier builds marked SAF files "unrepairable"
                    // because the write-only channel crashed on read. Retry them once
                    // (guarded by a pref so genuinely broken files are never re-looped).
                    if (!prefs.isRepairV2ResetDone()) {
                        prefs.setRepairV2ResetDone(true);
                        int reset = repo.resetUnrepairable();
                        if (reset > 0) {
                            FLog.i(TAG, "Self-healing scan: retrying " + reset
                                    + " file(s) wrongly marked unrepairable");
                        }
                    }
                    java.util.List<String> pending = repo.getUnfinalizedUris();
                    if (pending.isEmpty()) {
                        FLog.d(TAG, "Self-healing scan: no pending files");
                        return;
                    }
                    FLog.i(TAG, "Self-healing scan: " + pending.size() + " pending file(s)");
                    final long now = System.currentTimeMillis();
                    final long retryAfter = now + REPAIR_RETRY_DELAY_MS;
                    for (String uriString : pending) {
                        try {
                            // Never touch a file the service is still finalizing
                            // (manual stop in progress) — scanning it would
                            // double-finalize and raise a false repair banner.
                            if (IN_FLIGHT_FINALIZATIONS.contains(uriString)) {
                                continue;
                            }
                            if (!REPAIR_ATTEMPTED_THIS_PROCESS.add(uriString)) {
                                // Normally unreachable now that the index preserves repair
                                // state; kept as a hard stop so a future regression cannot
                                // loop on the same file again.
                                FLog.e(TAG, "Self-healing: already attempted " + uriString
                                        + " in this session — refusing to touch it again");
                                continue;
                            }
                            android.net.Uri uri = android.net.Uri.parse(uriString);
                            String scheme = uri.getScheme();
                            long attemptStartMs = System.currentTimeMillis();
                            // Size before/after is the definitive write-loop evidence:
                            // a repair must never grow a file it did not convert.
                            long sizeBefore = -1L;
                            long sizeAfter = -1L;
                            int result;
                            if ("file".equals(scheme)) {
                                java.io.File f = new java.io.File(uri.getPath());
                                if (!f.exists() || f.length() == 0) {
                                    repo.markUnrepairableWithRetry(uriString, retryAfter);
                                    continue;
                                }
                                sizeBefore = f.length();
                                java.nio.channels.FileChannel ch =
                                        new java.io.RandomAccessFile(f, "rw").getChannel();
                                try {
                                    result = androidx.media3.muxer.FragmentedMp4Muxer
                                            .finalizeAbandonedFile(ch);
                                } finally {
                                    try { ch.close(); } catch (Exception ignored) {}
                                }
                                sizeAfter = f.length();
                            } else if ("content".equals(scheme)) {
                                android.os.ParcelFileDescriptor pfd =
                                        app.getContentResolver().openFileDescriptor(uri, "rw");
                                if (pfd == null) {
                                    repo.markUnrepairableWithRetry(uriString, retryAfter);
                                    continue;
                                }
                                try {
                                    try { sizeBefore = pfd.getStatSize(); }
                                    catch (Exception ignored) {}
                                    // Two channels over the same fd: FileOutputStream's
                                    // channel is write-only (reads throw
                                    // NonReadableChannelException), so read via
                                    // FileInputStream's channel, write via
                                    // FileOutputStream's — the kernel offset is shared.
                                    result = androidx.media3.muxer.FragmentedMp4Muxer
                                            .finalizeAbandonedFile(
                                                    new java.io.FileInputStream(
                                                            pfd.getFileDescriptor()).getChannel(),
                                                    new java.io.FileOutputStream(
                                                            pfd.getFileDescriptor()).getChannel());
                                    try { sizeAfter = pfd.getStatSize(); }
                                    catch (Exception ignored) {}
                                } finally {
                                    try { pfd.close(); } catch (Exception ignored) {}
                                }
                            } else {
                                repo.markUnrepairableWithRetry(uriString, retryAfter);
                                continue;
                            }
                            // Visible in the dossier so the next report shows exactly what
                            // the scanner did, and never more than once per file/process.
                            FLog.i(TAG, "Self-healing: " + fileNameFromUri(uriString)
                                    + " → result=" + result
                                    + " size=" + sizeBefore + "→" + sizeAfter
                                    + " in " + (System.currentTimeMillis() - attemptStartMs) + "ms");

                            if (result == 1) {
                                repo.markFinalized(uriString, 1);
                                recordRepairEventStatic(prefs,
                                        fileNameFromUri(uriString), now, "background_interrupted");
                            } else if (result == 0) {
                                // Already hybrid/plain MP4 — confirmed fine.
                                repo.markFinalized(uriString, 1);
                            } else {
                                // Fragmented but unrepairable NOW — schedule a retry so
                                // transient failures (e.g. a fixed finalizer bug) are
                                // not permanently abandoned.
                                repo.markUnrepairableWithRetry(uriString, retryAfter);
                            }
                        } catch (Exception e) {
                            FLog.w(TAG, "Self-healing scan failed on " + uriString, e);
                            try { repo.markUnrepairableWithRetry(uriString, retryAfter); } catch (Exception ignored) {}
                        }
                    }
                    // A stale in-progress session that produced no file is dead.
                    if (prefs.isRecordingInProgress()) {
                        prefs.setRecordingInProgress(false);
                    }
    }

    /** Extracts a clean file name from file:// or content:// URIs for user messages. */
    private static String fileNameFromUri(String uriString) {
        try {
            String s = uriString;
            int q = s.indexOf('?');
            if (q >= 0) s = s.substring(0, q);
            // content://.../document/primary%3ADownload%2FFadCam%2F...%2FFadCam_x.mp4
            if (s.contains("%2F")) {
                s = s.substring(s.lastIndexOf("%2F") + 3);
            } else {
                int slash = s.lastIndexOf('/');
                if (slash >= 0) s = s.substring(slash + 1);
            }
            if (s.contains("%20")) s = s.replace("%20", " ");
            return s.isEmpty() ? uriString : s;
        } catch (Exception e) {
            return uriString;
        }
    }

    private static void recordRepairEventStatic(SharedPreferencesManager prefs,
            String fileName, long timeMs, String reason) {
        try {
            prefs.addRecordingRepairEvent(fileName, timeMs, reason);
            FLog.i(TAG, "Repair event recorded for " + fileName + " (reason=" + reason + ")");
        } catch (Exception e) {
            FLog.w(TAG, "Failed to record repair event", e);
        }
    }

    /** Safety net after a clean stop: converts the file if finalization silently skipped it. */
    private void finalizeJustStoppedRecording() {
        try {
            String uriString = currentSegmentPath != null
                    ? android.net.Uri.fromFile(new java.io.File(currentSegmentPath)).toString()
                    : currentSegmentUriString;
            if (uriString == null) return;
            int result = finalizeFileChannelFor(uriString);
            if (result >= 0) {
                // Room forbids DB access on the main thread — dispatch the
                // markFinalized write to the background handler.
                final String finalUri = uriString;
                if (backgroundHandler != null) {
                    backgroundHandler.post(() -> {
                        try {
                            com.fadcam.data.VideoIndexRepository.getInstance(
                                    RecordingService.this).markFinalized(finalUri, 1);
                        } catch (Exception e) {
                            FLog.w(TAG, "Safety-net markFinalized failed", e);
                        }
                    });
                }
            }
            if (result == 1) {
                FLog.i(TAG, "Safety-net finalization converted "
                        + fileNameFromUri(uriString));
            }
        } catch (Exception e) {
            FLog.w(TAG, "Safety-net finalization failed", e);
        }
    }

    /** Finalizes a file/URI; returns 1 converted, 0 already-ok, -1 failed. */
    private int finalizeFileChannelFor(String uriString) {
        try {
            android.net.Uri uri = android.net.Uri.parse(uriString);
            int result;
            if ("content".equals(uri.getScheme())) {
                android.os.ParcelFileDescriptor pfd =
                        getContentResolver().openFileDescriptor(uri, "rw");
                if (pfd == null) return -1;
                try {
                    // Two channels over the same fd (FileOutputStream channel is
                    // write-only; reads would throw NonReadableChannelException).
                    result = androidx.media3.muxer.FragmentedMp4Muxer.finalizeAbandonedFile(
                            new java.io.FileInputStream(pfd.getFileDescriptor()).getChannel(),
                            new java.io.FileOutputStream(pfd.getFileDescriptor()).getChannel());
                } finally {
                    try { pfd.close(); } catch (Exception ignored) {}
                }
            } else {
                java.io.File f = new java.io.File(uri.getPath());
                if (!f.exists()) return -1;
                java.nio.channels.FileChannel ch = new java.io.RandomAccessFile(f, "rw").getChannel();
                try {
                    result = androidx.media3.muxer.FragmentedMp4Muxer.finalizeAbandonedFile(ch);
                } finally {
                    try { ch.close(); } catch (Exception ignored) {}
                }
            }
            FLog.i(TAG, "Self-healing finalize(" + uriString + ") → " + result);
            if (result == 1) {
                recordRepairEvent(fileNameFromUri(uriString), "finalize");
            }
            return result;
        } catch (Exception e) {
            FLog.w(TAG, "Self-healing finalize threw for " + uriString, e);
            return -1;
        }
    }

    /** Records a repair event so the Records tab can inform the user (banner). */
    private void recordRepairEvent(String name, String reason) {
        try {
            long id = sharedPreferencesManager.addRecordingRepairEvent(
                    name, System.currentTimeMillis(), reason);
            FLog.i(TAG, "Repair event recorded for " + name + " (id=" + id + ", reason=" + reason + ")");
        } catch (Exception e) {
            FLog.w(TAG, "Failed to record repair event", e);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {        if (intent == null) {
            FLog.d(TAG, "onStartCommand received null intent. Ensuring service stays alive.");
            return START_STICKY;
        }
        String action = intent.getAction();
        if (action == null) {
            FLog.w(TAG, "onStartCommand: Action is null.");
            return START_STICKY;
        }
        if ("ACTION_APP_BACKGROUND".equals(action)) {
            FLog.d(TAG, "Received ACTION_APP_BACKGROUND: releasing preview EGL/GL resources");
            if (glRecordingPipeline != null) {
                try {
                    glRecordingPipeline.releasePreviewResources(); // Only release preview EGL/GL
                } catch (Exception e) {
                    FLog.e(TAG, "Error releasing preview EGL/GL on app background", e);
                }
            }
            return START_STICKY;
        } else if ("ACTION_APP_FOREGROUND".equals(action)) {
            FLog.d(TAG, "Received ACTION_APP_FOREGROUND: re-initializing pipeline if recording in progress");
            if (sharedPreferencesManager != null && sharedPreferencesManager.isRecordingInProgress()) {
                // Defensive: only re-initialize if not already running
                if (glRecordingPipeline == null) {
                    // Recreate pipeline and surfaces (minimal, actual re-init logic may be more
                    // complex)
                    // You may want to trigger the same logic as when starting recording
                    // For now, just log and rely on UI/fragment to trigger full re-init
                    FLog.d(TAG, "App foregrounded and recording in progress, pipeline will be re-initialized by UI");
                }
            }
            // Preview-only recovery path after external camera contention/background return.
            if (previewOnlyActive && recordingState == RecordingState.NONE) {
                if (previewSurface != null && previewSurface.isValid()) {
                    FLog.d(TAG, "ACTION_APP_FOREGROUND: recovering preview-only camera session");
                    ensurePreviewOnlyGlPipeline();
                    if (cameraDevice == null) {
                        openCamera();
                    } else if (captureSession == null) {
                        createCameraPreviewSession();
                    }
                } else {
                    pendingPreviewOnlyStart = true;
                    FLog.d(TAG, "ACTION_APP_FOREGROUND: preview-only active but surface missing, waiting for ACTION_CHANGE_SURFACE");
                }
            }
            return START_STICKY;
        }
        if (Constants.INTENT_ACTION_REFRESH_PREVIEW_SHAPE.equals(action)) {
            // A shape-changing setting (video orientation) changed while the preview is up.
            // The pipeline froze its shape when it was built, so it has to be rebuilt for the
            // preview and the card to agree with the new setting.
            if (previewOnlyActive && recordingState == RecordingState.NONE) {
                FLog.d(TAG, "Preview shape refresh requested");
                restartPreviewOnlyForOrientationChange();
            } else if (!isWorkingInProgress()) {
                // Nothing is previewing or recording: this start would otherwise leave an idle
                // sticky service alive for no reason.
                stopSelf();
            }
            return START_STICKY;
        }
        if (Constants.INTENT_ACTION_START_PREVIEW_ONLY.equals(action)) {
            CameraType selectedType = sharedPreferencesManager.getCameraSelection();
            if (selectedType != null && selectedType.isDual()) {
                FLog.w(TAG, "Preview-only mode is not supported for Dual PiP");
                Toast.makeText(this, getString(R.string.toast_preview_only_front_back), Toast.LENGTH_SHORT).show();
                return START_STICKY;
            }
            if (isWorkingInProgress()) {
                FLog.d(TAG, "Ignoring preview-only start: recording already active");
                return START_STICKY;
            }
            if (previewOnlyActive) {
                FLog.d(TAG, "Preview already active — handling surface change only (fullscreen transition)");
                setupSurfaceTexture(intent);
                if (previewSurface != null && previewSurface.isValid()) {
                    ensurePreviewOnlyGlPipeline();
                }
                return START_STICKY;
            }
            setupSurfaceTexture(intent);
            if (previewSurface == null || !previewSurface.isValid()) {
                pendingPreviewOnlyStart = true;
                previewOnlyActive = false;
                pendingStartRecording = false;
                recordingState = RecordingState.NONE;
                FLog.d(TAG, "Preview-only start deferred: preview surface not ready yet");
                return START_STICKY;
            }
            pendingPreviewOnlyStart = false;
            previewOnlyActive = true;
            pendingStartRecording = false;
            recordingState = RecordingState.NONE;
            ensurePreviewOnlyGlPipeline();
            openCamera();
            broadcastOnPreviewOnlyStarted();
            return START_STICKY;
        } else if (Constants.INTENT_ACTION_STOP_PREVIEW_ONLY.equals(action)) {
            boolean wasPreviewOnlyActive = previewOnlyActive;
            pendingPreviewOnlyStart = false;
            stopPreviewOnlyMode();
            if (!wasPreviewOnlyActive) {
                // Idempotent stop: still notify UI to reconcile any stale local state.
                broadcastOnPreviewOnlyStopped();
                if (!isWorkingInProgress()) {
                    stopSelf();
                }
            }
            return START_STICKY;
        } else if (Constants.INTENT_ACTION_START_RECORDING.equals(action)) {
            pendingPreviewOnlyStart = false;
            if (previewOnlyActive) {
                FLog.d(TAG, "Transitioning from preview-only to recording mode");
                previewOnlyActive = false;
                previewSessionConfigInFlight = false;
                releasePreviewOnlyGlPipeline();
                broadcastOnPreviewOnlyStopped();
            }

            // Initialize extended sensor providers only when recording actually starts
            // (NOT during preview-only mode — prevents mic/sensor memory leaks)
            // setup log removed

            // Shared watermark manager (DRY — used by camera, dual camera and screen recording)
            ensureWatermarkManager();

            // Only start sensor/noise/weather providers if the master watermark
            // is enabled AND individual features are turned on.
            boolean watermarkActive = !"no_watermark".equals(
                sharedPreferencesManager != null ? sharedPreferencesManager.getWatermarkOption() : "");

            if (watermarkActive && sharedPreferencesManager != null && (sharedPreferencesManager.isSpeedEnabled()
                    || sharedPreferencesManager.isAltitudeEnabled()
                    || sharedPreferencesManager.isCompassEnabled())) {
                if (sensorDataProvider == null) {
                    sensorDataProvider = com.fadcam.sensors.SensorDataProvider.getInstance(getApplicationContext());
                }
                // Seed with the REAL last fix (carries speed/altitude), not a
                // fabricated coords-only Location. Null is fine — next fix updates it.
                android.location.Location androidLoc = locationHelper != null ? locationHelper.getRawLocation() : null;
                sensorDataProvider.start(androidLoc);
                // setup log removed
            }

            if (watermarkActive && sharedPreferencesManager != null && sharedPreferencesManager.isNoiseEnabled()) {
                if (noiseMonitor == null) {
                    noiseMonitor = com.fadcam.audio.NoiseMonitor.getInstance();
                }
                noiseMonitor.start(this);
                // setup log removed
            }

            if (sharedPreferencesManager != null && sharedPreferencesManager.isWeatherEnabled()) {
                if (weatherService == null) {
                    weatherService = com.fadcam.network.WeatherService.getInstance(getApplicationContext());
                }
                FLog.d(TAG, "WeatherService initialized for recording");
            }
            
            // STREAM ENFORCEMENT GATE: Validate and gate streaming mode
            boolean streamingEnabled = com.fadcam.FeatureRegistry.streaming().isStreamingEnabled();
            android.content.SharedPreferences fadcamPrefs = getSharedPreferences("FadCamPrefs", Context.MODE_PRIVATE);
            String presetName = fadcamPrefs.getString("quality_preset", "HIGH");
            int presetBitrate = fadcamPrefs.getInt("stream_bitrate", 5_000_000);
            int presetFps = fadcamPrefs.getInt("stream_fps_cap", 30);
            
            if (streamingEnabled) {
                FLog.i(TAG, "🔴 [STREAM MODE ENFORCED] Starting recording with streaming server ACTIVE");
                FLog.i(TAG, "   Preset: " + presetName + " | Bitrate: " + (presetBitrate/1_000_000) + " Mbps | FPS cap: " + presetFps + "fps");
                // Validate preset values were actually stored
                if (presetBitrate <= 0 || presetFps <= 0) {
                    FLog.e(TAG, "❌ INVALID STREAM PRESET - bitrate=" + presetBitrate + ", fps=" + presetFps);
                    mainHandler.post(() -> Toast.makeText(getApplicationContext(), 
                        "Stream preset invalid - check quality settings", Toast.LENGTH_SHORT).show());
                    return START_STICKY;
                }
            } else {
                FLog.i(TAG, "📺 [NORMAL RECORDING] Starting recording - streaming server OFF");
            }
            
            // Check for rapid start attempts
            long currentTime = System.currentTimeMillis();
            if (currentTime - lastStartAttemptTime < MIN_START_INTERVAL_MS) {
                FLog.w(TAG, "START_RECORDING rejected - too rapid. Last attempt was " + 
                      (currentTime - lastStartAttemptTime) + "ms ago");
                mainHandler.post(() -> {
                    Toast.makeText(getApplicationContext(),
                            "Please wait before starting recording again",
                            Toast.LENGTH_SHORT).show();
                });
                return START_STICKY;
            }
            lastStartAttemptTime = currentTime;
            
            // ----- Check for camera resource cooldown -----
            // Check if camera resources are still being released
            if (isCameraResourceReleasing) {
                FLog.w(TAG, "START_RECORDING rejected - camera resources still being released");
                // Show toast on UI thread
                mainHandler.post(() -> {
                    Toast.makeText(getApplicationContext(),
                            R.string.camera_resources_cooldown,
                            Toast.LENGTH_LONG).show();
                });
                // Don't stop the service yet as FFmpeg might still be running
                return START_STICKY;
            }

            // Reset recording state if it's somehow corrupted or inconsistent
            if (recordingState != RecordingState.NONE && cameraDevice == null) {
                FLog.w(TAG,
                        "Recording state inconsistency detected. Resetting state from " + recordingState + " to NONE.");
                recordingState = RecordingState.NONE;
                sharedPreferencesManager.setRecordingInProgress(false);
            }
            
            // Additional safety check: if we're in STARTING state but no camera setup is pending,
            // it means we got stuck in a previous rapid start attempt
            if (recordingState == RecordingState.STARTING && !pendingStartRecording && cameraDevice == null) {
                FLog.w(TAG, "Found stuck STARTING state with no pending operations. Resetting to NONE.");
                recordingState = RecordingState.NONE;
                sharedPreferencesManager.setRecordingInProgress(false);
            }

            // Only proceed if we're in NONE state
            if (recordingState == RecordingState.NONE) {
                if (durationLimitController != null) {
                    durationLimitController.stopSession();
                }
                // Update the UI and Service state atomically
                recordingState = RecordingState.STARTING;
                // Clear a stale stopping flag from a previous recording that
                // may still be cleaning up asynchronously on a background thread.
                isStopping = false;
                pausedWithLiveStream = false;
                liveIdleUsedThisSession = false;
                sessionCreatedLocalFiles.clear();
                clearRecordingTimelineState();
                sharedPreferencesManager.setRecordingInProgress(true);
                CameraType selectedType = sharedPreferencesManager.getCameraSelection();
                recordingSessionCameraSource = selectedType == CameraType.FRONT
                        ? RecordingStoragePaths.CameraSource.FRONT
                        : RecordingStoragePaths.CameraSource.BACK;
                configureMotionLabForSession();

                // Set initial torch state
                isRecordingTorchEnabled = intent.getBooleanExtra(Constants.INTENT_EXTRA_INITIAL_TORCH_STATE, false);

                // Set up preview surface if provided
                setupSurfaceTexture(intent);

                // run -----------
                try {
                    boolean previewEnabled = sharedPreferencesManager != null
                            && sharedPreferencesManager.isPreviewEnabled();
                    boolean hasValidPreview = (previewSurface != null && previewSurface.isValid());
                    waitForPreviewBeforeStart = previewEnabled && !hasValidPreview;

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
                                FLog.w(TAG,
                                        "Preview wait timeout reached; proceeding without preview to start recording safely");
                                waitForPreviewBeforeStart = false;
                                attemptStartRecordingIfReady();
                            }
                        };
                        // Give TextureView a moment to initialize on cold start
                        mainHandler.postDelayed(previewWaitTimeoutRunnable, 1500);
                    }
                } catch (Exception e) {
                    FLog.w(TAG, "Error evaluating preview-wait condition; proceeding without wait", e);
                    waitForPreviewBeforeStart = false;
                }
                // run -----------

                // Start foreground service
                setupRecordingInProgressNotification();

                // Begin camera/recording setup
                if (cameraDevice == null) {
                    pendingStartRecording = true;
                    openCamera();
                } else {
                    attemptStartRecordingIfReady();
                }

                // Notify UI that we're starting
                broadcastOnRecordingStarted();

                return START_STICKY;
            } else {
                // If we're not in NONE state, log a warning and notify the user
                FLog.w(TAG, "Cannot start recording, already in state: " + recordingState);
                Toast.makeText(this, getString(R.string.recording_already_active), Toast.LENGTH_SHORT).show();
                return START_STICKY;
            }
        } else if (Constants.INTENT_ACTION_STOP_RECORDING.equals(action)) {
            FLog.i(TAG, "dispatch stop_action_received via onStartCommand");
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
        } else if (Constants.INTENT_ACTION_SWITCH_CAMERA.equals(action)) {
            // Handle live camera switch during recording
            String newCameraTypeStr = intent.getStringExtra(Constants.INTENT_EXTRA_CAMERA_TYPE_SWITCH);
            if (newCameraTypeStr != null) {
                try {
                    CameraType newCameraType = CameraType.valueOf(newCameraTypeStr);
                    if (previewOnlyActive && recordingState == RecordingState.NONE) {
                        switchCameraPreviewOnly(newCameraType);
                    } else {
                        switchCameraLive(newCameraType);
                    }
                } catch (IllegalArgumentException e) {
                    FLog.e(TAG, "Invalid camera type in switch intent: " + newCameraTypeStr, e);
                    broadcastOnCameraSwitchFailed("Invalid camera type", null);
                }
            } else {
                FLog.e(TAG, "Camera switch intent missing " + Constants.INTENT_EXTRA_CAMERA_TYPE_SWITCH);
                broadcastOnCameraSwitchFailed("Missing camera type parameter", null);
            }
            return START_STICKY;
        } else if (Constants.INTENT_ACTION_CHANGE_SURFACE.equals(action)) {
            // Handle surface changes for preview
            setupSurfaceTexture(intent);
            if (pendingPreviewOnlyStart && previewSurface != null && previewSurface.isValid() && !isWorkingInProgress()) {
                FLog.d(TAG, "Starting deferred preview-only mode after valid surface arrived");
                pendingPreviewOnlyStart = false;
                previewOnlyActive = true;
                pendingStartRecording = false;
                recordingState = RecordingState.NONE;
                ensurePreviewOnlyGlPipeline();
                openCamera();
                broadcastOnPreviewOnlyStarted();
            }
            // NOTE: setupSurfaceTexture already calls glRecordingPipeline.setPreviewSurface()
            // Do NOT call it again here to avoid double-debounce churn
            // surface change when using GL path -----------
            // If we're still in STARTING and were waiting for preview, attempt to start now
            if (recordingState == RecordingState.STARTING && waitForPreviewBeforeStart && previewSurface != null
                    && previewSurface.isValid()) {
                attemptStartRecordingIfReady();
            }
            // Do NOT recreate camera session here for GL-based recording, as preview is
            // rendered via EGL in renderer
            // Reconfiguration during active recording may cause driver instability on first
            // run
            if (glRecordingPipeline == null && (isRecording() || isPaused() || previewOnlyActive)) {
                // Avoid session churn in preview-only mode: if we already have an active
                // capture session and a valid camera, keep the current repeating request.
                if (previewOnlyActive && (previewSessionConfigInFlight || (captureSession != null && cameraDevice != null))) {
                    FLog.d(TAG, "ACTION_CHANGE_SURFACE: preview-only session already active/in-flight; skipping session recreate");
                } else {
                    // Only reconfigure if we're not on GL path (legacy/fallback)
                    createCameraPreviewSession();
                }
            }
            // surface change when using GL path -----------
            FLog.d(TAG,
                    "ACTION_CHANGE_SURFACE handled: preview surface updated, camera session reconfigured if needed. No pipeline re-init.");
            return START_STICKY;
        } else if (Constants.BROADCAST_ON_RECORDING_STATE_REQUEST.equals(action)) {
            // Handle UI state sync requests
            broadcastOnRecordingStateCallback();
            if (!isWorkingInProgress()) {
                stopSelf();
            }
            return START_STICKY;
        } else if (Constants.INTENT_ACTION_TOGGLE_RECORDING_TORCH.equals(action)) {
            // Handle torch toggle requests
            // If service was started via startForegroundService(), we MUST call startForeground()
            // within 5 seconds to avoid crash
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !isWorkingInProgress()) {
                // Service is not recording, so start a minimal foreground notification
                try {
                    NotificationCompat.Builder minimalBuilder = new NotificationCompat.Builder(this, CHANNEL_ID)
                            .setContentTitle(getString(R.string.app_name))
                            .setContentText("Torch toggled")
                            .setSmallIcon(R.drawable.ic_notification_icon);
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        // Explicit subset: never claim the optional location type for a torch
                        // notification, so it works without location permission.
                        startForeground(NOTIFICATION_ID, minimalBuilder.build(),
                                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA);
                    } else {
                        startForeground(NOTIFICATION_ID, minimalBuilder.build());
                    }
                    // Stop the service immediately after torch toggle since we're not recording
                    new Handler(Looper.getMainLooper()).postDelayed(this::stopSelf, 100);
                } catch (Exception e) {
                    FLog.e(TAG, "Error starting foreground for torch toggle", e);
                }
            }
            toggleRecordingTorch();
            return START_STICKY;
        } else if (Constants.INTENT_ACTION_SET_EXPOSURE_COMPENSATION.equals(action)) {
            // Set exposure compensation index while recording
            if (intent.hasExtra(Constants.EXTRA_EXPOSURE_COMPENSATION)) {
                int ev = intent.getIntExtra(Constants.EXTRA_EXPOSURE_COMPENSATION, 0);
                applyExposureCompensation(ev);
            android.content.Intent exposureBroadcast = new android.content.Intent(
                Constants.BROADCAST_ON_EXPOSURE_CHANGED);
            exposureBroadcast.putExtra(
                Constants.EXTRA_BROADCAST_EXPOSURE_COMPENSATION,
                sharedPreferencesManager.getSavedExposureCompensation());
            sendBroadcast(exposureBroadcast.setPackage(getPackageName()));
            com.fadcam.FeatureRegistry.streaming().invalidateStatusCache();
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
            FLog.d(TAG, "Received TAP_TO_FOCUS intent");
            if (intent.hasExtra(Constants.EXTRA_FOCUS_X) && intent.hasExtra(Constants.EXTRA_FOCUS_Y)) {
                float nx = intent.getFloatExtra(Constants.EXTRA_FOCUS_X, 0.5f);
                float ny = intent.getFloatExtra(Constants.EXTRA_FOCUS_Y, 0.5f);
                FLog.d(TAG, "TAP_TO_FOCUS intent has coordinates: " + nx + ", " + ny);
                performTapToFocus(nx, ny);
            } else {
                FLog.w(TAG, "TAP_TO_FOCUS intent missing coordinates");
            }
            return START_STICKY;
        } else if (Constants.INTENT_ACTION_SET_AUDIO_MUTED.equals(action)) {
            // Realtime mute/unmute of the live recording's audio track.
            boolean muted = intent.getBooleanExtra(Constants.EXTRA_AUDIO_MUTED, false);
            if (glRecordingPipeline != null) {
                glRecordingPipeline.setAudioMuted(muted);
            } else {
                FLog.w(TAG, "Audio mute: no active GL pipeline");
            }
            return START_STICKY;
        } else if (Constants.INTENT_ACTION_SET_VIDEO_STABILIZATION.equals(action)) {
            // Toggle video stabilization (EIS/OIS) at runtime.
            boolean enable = intent.getBooleanExtra(
                    Constants.EXTRA_VIDEO_STABILIZATION_ENABLED, true);
            if (sharedPreferencesManager != null) {
                sharedPreferencesManager.setVideoStabilizationEnabled(enable);
            }
            reapplyVideoStabilizationToSession();
            return START_STICKY;
        } else if (Constants.INTENT_ACTION_SET_ZOOM_RATIO.equals(action)) {
            if (intent.hasExtra(Constants.EXTRA_ZOOM_RATIO)) {
                float zoomRatio = intent.getFloatExtra(Constants.EXTRA_ZOOM_RATIO, 1.0f);
                // Accept optional pan offsets; if absent, preserve the currently-saved pan
                if (intent.hasExtra(Constants.EXTRA_PAN_X) || intent.hasExtra(Constants.EXTRA_PAN_Y)) {
                    float panX = intent.getFloatExtra(Constants.EXTRA_PAN_X, 0.0f);
                    float panY = intent.getFloatExtra(Constants.EXTRA_PAN_Y, 0.0f);
                    applyZoomAndPan(zoomRatio, panX, panY);
                    // Notify HomeFragment so pan overlay updates in real time
                    android.content.Intent zoomBcast = new android.content.Intent(Constants.BROADCAST_ON_ZOOM_CHANGED);
                    CameraType cam = sharedPreferencesManager.getCameraSelection();
                    zoomBcast.putExtra(Constants.EXTRA_BROADCAST_ZOOM_RATIO, sharedPreferencesManager.getSpecificZoomRatio(cam));
                    zoomBcast.putExtra(Constants.EXTRA_BROADCAST_PAN_X, sharedPreferencesManager.getSpecificPanX(cam));
                    zoomBcast.putExtra(Constants.EXTRA_BROADCAST_PAN_Y, sharedPreferencesManager.getSpecificPanY(cam));
                    this.sendBroadcast(zoomBcast.setPackage(this.getPackageName()));
                } else {
                    applyZoomRatio(zoomRatio); // preserves saved pan internally
                    // Notify HomeFragment; read effective pan from SharedPreferences
                    CameraType cam = sharedPreferencesManager.getCameraSelection();
                    float broadcastPanX = sharedPreferencesManager.getSpecificPanX(cam);
                    float broadcastPanY = sharedPreferencesManager.getSpecificPanY(cam);
                    android.content.Intent zoomBcast = new android.content.Intent(Constants.BROADCAST_ON_ZOOM_CHANGED);
                    zoomBcast.putExtra(Constants.EXTRA_BROADCAST_ZOOM_RATIO, zoomRatio);
                    zoomBcast.putExtra(Constants.EXTRA_BROADCAST_PAN_X, broadcastPanX);
                    zoomBcast.putExtra(Constants.EXTRA_BROADCAST_PAN_Y, broadcastPanY);
                    this.sendBroadcast(zoomBcast.setPackage(this.getPackageName()));
                }
                com.fadcam.FeatureRegistry.streaming().invalidateStatusCache();
            }
            return START_STICKY;
        } else if (Constants.INTENT_ACTION_SET_FRONT_VIDEO_MIRROR.equals(action)) {
            boolean enabled = intent.getBooleanExtra(
                    Constants.EXTRA_FRONT_VIDEO_MIRROR_ENABLED,
                    sharedPreferencesManager.isFrontVideoMirrorEnabled());
            sharedPreferencesManager.setFrontVideoMirrorEnabled(enabled);
            applyFrontVideoMirror(enabled);
            // Notify HomeFragment so mirror button visual state updates
            android.content.Intent mirrorBcast = new android.content.Intent(Constants.BROADCAST_ON_MIRROR_CHANGED);
            mirrorBcast.putExtra(Constants.EXTRA_MIRROR_ENABLED, enabled);
            this.sendBroadcast(mirrorBcast.setPackage(this.getPackageName()));
            return START_STICKY;
        } else if (Constants.INTENT_ACTION_CAPTURE_PHOTO.equals(action)) {
            capturePhotoFromRecording();
            return START_STICKY;
        }

        else if (Constants.INTENT_ACTION_REINITIALIZE_LOCATION.equals(action)) {
            // Handle request to reinitialize location helpers after settings change
            FLog.d(TAG, "Handling REINITIALIZE_LOCATION intent");

            // Extract the embedding preference directly from intent if available
            boolean forceInit = intent.getBooleanExtra("force_init", false);
            boolean embedLocationFromIntent = intent.getBooleanExtra("embed_location", false);
            boolean hasLocationPermission = intent.getBooleanExtra("has_permission", false);

            // Log the values for debugging
            FLog.d(TAG, "Location intent extras:");
            FLog.d(TAG, "  - force_init: " + forceInit);
            FLog.d(TAG, "  - embed_location: " + embedLocationFromIntent);
            FLog.d(TAG, "  - has_permission: " + hasLocationPermission);

            // If embed_location is true but permission is not granted, log warning
            if (embedLocationFromIntent && !hasLocationPermission) {
                FLog.w(TAG, "Warning: Location embedding requested but permission is not granted");
                // Don't override preference in this case - let the UI control it
            }
            // If intent explicitly specifies the embed_location value, use it to force
            // override the preference
            else if (intent.hasExtra("embed_location")) {
                FLog.d(TAG, "Intent explicitly specifies embed_location=" + embedLocationFromIntent);

                // Force the preference to match what was sent in the intent
                if (sharedPreferencesManager.isLocationEmbeddingEnabled() != embedLocationFromIntent) {
                    FLog.d(TAG, "Updating preferences to match intent value");
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
            FLog.w(TAG, "Unknown action received: " + action);
            if (!isWorkingInProgress())
                stopSelf();
            return START_NOT_STICKY;
        }
    }

    private void capturePhotoFromRecording() {
        boolean canCaptureFromSession =
                recordingState == RecordingState.IN_PROGRESS
                        || recordingState == RecordingState.PAUSED
                        || (previewOnlyActive && recordingState == RecordingState.NONE);
        if (glRecordingPipeline == null || !canCaptureFromSession) {
            mainHandler.post(() -> Toast.makeText(getApplicationContext(),
                    R.string.photo_capture_preview_unavailable, Toast.LENGTH_SHORT).show());
            return;
        }
        glRecordingPipeline.capturePhotoFrame(bitmap -> {
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
                CameraType selected = sharedPreferencesManager != null
                        ? sharedPreferencesManager.getCameraSelection()
                        : CameraType.BACK;
                PhotoStorageHelper.ShotSource shotSource = selected == CameraType.FRONT
                        ? PhotoStorageHelper.ShotSource.SELFIE
                        : PhotoStorageHelper.ShotSource.BACK;
                Bitmap bitmapForSave = bitmap;
                if (previewOnlyActive && recordingState == RecordingState.NONE) {
                    Bitmap normalized = normalizePreviewOnlyShotBitmap(bitmapForSave);
                    if (normalized != null && normalized != bitmapForSave) {
                        bitmapForSave.recycle();
                        bitmapForSave = normalized;
                    }
                    Bitmap cropped = cropVerticalLetterbox(bitmapForSave);
                    if (cropped != null && cropped != bitmapForSave) {
                        bitmapForSave.recycle();
                        bitmapForSave = cropped;
                    }
                }
                // Build extra watermark data from live sensors/location
                String extraWatermark = buildPhotoWatermarkExtras();
                Uri savedUri = PhotoStorageHelper.saveJpegBitmap(
                        getApplicationContext(),
                        bitmapForSave,
                        true,
                        shotSource,
                        extraWatermark);
                bitmapForSave.recycle();
                if (savedUri != null) {
                    Intent updateIntent = new Intent(Constants.ACTION_RECORDING_COMPLETE);
                    updateIntent.putExtra(Constants.EXTRA_RECORDING_SUCCESS, true);
                    updateIntent.putExtra(Constants.EXTRA_RECORDING_URI_STRING, savedUri.toString());
                    updateIntent.setPackage(getPackageName());
                    sendBroadcast(updateIntent);
                    mainHandler.post(() -> Toast.makeText(getApplicationContext(),
                            R.string.photo_capture_saved, Toast.LENGTH_SHORT).show());
                } else {
                    mainHandler.post(() -> Toast.makeText(getApplicationContext(),
                            R.string.photo_capture_failed, Toast.LENGTH_SHORT).show());
                }
            });
        });
    }

    @Nullable
    private Bitmap normalizePreviewOnlyShotBitmap(@NonNull Bitmap source) {
        try {
            Size target = sharedPreferencesManager != null
                    ? sharedPreferencesManager.getCameraResolution()
                    : null;
            int targetW = target != null ? target.getWidth() : 1080;
            int targetH = target != null ? target.getHeight() : 1920;
            String orientation = sharedPreferencesManager != null
                    ? sharedPreferencesManager.getVideoOrientation()
                    : SharedPreferencesManager.ORIENTATION_PORTRAIT;
            boolean portrait = !SharedPreferencesManager.isLandscapeOrientation(orientation);
            if (portrait && targetW > targetH) {
                int tmp = targetW;
                targetW = targetH;
                targetH = tmp;
            } else if (!portrait && targetH > targetW) {
                int tmp = targetW;
                targetW = targetH;
                targetH = tmp;
            }
            int srcW = source.getWidth();
            int srcH = source.getHeight();
            if (srcW <= 0 || srcH <= 0 || targetW <= 0 || targetH <= 0) {
                return source;
            }
            float scale = Math.max(targetW / (float) srcW, targetH / (float) srcH);
            int scaledW = Math.max(1, Math.round(srcW * scale));
            int scaledH = Math.max(1, Math.round(srcH * scale));
            Bitmap scaled = (scaledW == srcW && scaledH == srcH)
                    ? source
                    : Bitmap.createScaledBitmap(source, scaledW, scaledH, true);
            int x = Math.max(0, (scaledW - targetW) / 2);
            int y = Math.max(0, (scaledH - targetH) / 2);
            int cropW = Math.min(targetW, scaledW);
            int cropH = Math.min(targetH, scaledH);
            Bitmap out = Bitmap.createBitmap(scaled, x, y, cropW, cropH);
            if (scaled != source && scaled != out) {
                scaled.recycle();
            }
            return out;
        } catch (Exception e) {
            FLog.w(TAG, "normalizePreviewOnlyShotBitmap failed", e);
            return source;
        }
    }

    @Nullable
    private Bitmap cropVerticalLetterbox(@NonNull Bitmap source) {
        try {
            int width = source.getWidth();
            int height = source.getHeight();
            if (width < 4 || height < 4) {
                return source;
            }
            int top = 0;
            int bottom = height - 1;
            while (top < height - 2 && isRowMostlyBlack(source, top, width)) {
                top++;
            }
            while (bottom > top + 1 && isRowMostlyBlack(source, bottom, width)) {
                bottom--;
            }
            int croppedHeight = bottom - top + 1;
            if (top <= 1 && bottom >= height - 2) {
                return source;
            }
            if (croppedHeight < height / 2) {
                // Avoid aggressive crop if detection is uncertain.
                return source;
            }
            return Bitmap.createBitmap(source, 0, top, width, croppedHeight);
        } catch (Exception e) {
            FLog.w(TAG, "cropVerticalLetterbox failed", e);
            return source;
        }
    }

    private boolean isRowMostlyBlack(@NonNull Bitmap bitmap, int y, int width) {
        int step = Math.max(1, width / 64);
        int samples = 0;
        int dark = 0;
        for (int x = 0; x < width; x += step) {
            int p = bitmap.getPixel(x, y);
            int r = (p >> 16) & 0xff;
            int g = (p >> 8) & 0xff;
            int b = p & 0xff;
            int luma = (r * 299 + g * 587 + b * 114) / 1000;
            if (luma < 14) {
                dark++;
            }
            samples++;
        }
        return samples > 0 && dark >= (samples * 0.94f);
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null; // Not a bound service
    }

    @Override
    public void onDestroy() {
        FLog.d(TAG, "onDestroy: Service being destroyed...");
        // Safety net: drop any in-flight finalization guard (the process is
        // going away; a stale entry would block future repairs of this file).
        IN_FLIGHT_FINALIZATIONS.clear();
        if (motionRuntime != null) {
            motionRuntime.release();
            motionRuntime = null;
        }
        releaseDurationLimitController();

        // Stop any active reconnection attempts
        stopReconnectionAttempts();

        // Make sure dummy surface is released
        releaseDummyBackgroundSurface();

        // Ensure all location services are properly stopped
        if (geotagHelper != null) {
            try {
                geotagHelper.stopUpdates();
                FLog.d(TAG, "GeotagHelper: Stopped location updates");
            } catch (Exception e) {
                FLog.e(TAG, "Error stopping GeotagHelper updates", e);
            }
        }

        if (locationHelper != null) {
            try {
                locationHelper.stopLocationUpdates();
                FLog.d(TAG, "LocationHelper: Stopped location updates for watermarking");
            } catch (Exception e) {
                FLog.e(TAG, "Error stopping LocationHelper updates", e);
            }
        }

        if (locationGeocoder != null) {
            try {
                locationGeocoder.shutdown();
                FLog.d(TAG, "LocationGeocoder: Shutdown geocoding service");
            } catch (Exception e) {
                FLog.e(TAG, "Error shutting down LocationGeocoder", e);
            }
        }

        // Force recording to stop if somehow it's still active
        if (recordingState != RecordingState.NONE) {
            FLog.w(TAG, "Service being destroyed while recording is active. Forcing stop.");
            try {
                stopRecording();
            } catch (Exception e) {
                FLog.e(TAG, "Error stopping recording during service destruction", e);
            }
        }

        // Clean up camera resources
        try {
            releaseRecordingResources();
        } catch (Exception e) {
            FLog.e(TAG, "Error releasing resources during service destruction", e);
        }

        // Release wake lock if still held
        if (recordingWakeLock != null && recordingWakeLock.isHeld()) {
            try {
                recordingWakeLock.release();
                FLog.d(TAG, "Recording WakeLock released during service destruction.");
            } catch (Exception e) {
                FLog.e(TAG, "Error releasing wake lock", e);
            }
        }

        // Stop background thread to prevent memory leak
        if (backgroundThread != null) {
            try {
                backgroundThread.quitSafely();
                backgroundThread.join(1000);
                FLog.d(TAG, "Background thread stopped successfully.");
            } catch (InterruptedException e) {
                FLog.e(TAG, "Error stopping background thread", e);
                Thread.currentThread().interrupt();
            }
        }

        if (motionDetectorWarmupExecutor != null) {
            try {
                motionDetectorWarmupExecutor.shutdownNow();
            } catch (Exception e) {
                FLog.w(TAG, "Error shutting down motion detector warmup executor", e);
            }
            motionDetectorWarmupExecutor = null;
        }

        if (aiInferenceExecutor != null) {
            try {
                aiInferenceExecutor.shutdownNow();
            } catch (Exception e) {
                FLog.w(TAG, "Error shutting down AI inference executor", e);
            }
            aiInferenceExecutor = null;
        }

        discardPendingAnalysisImage();
        if (motionAnalysisExecutor != null) {
            try {
                motionAnalysisExecutor.shutdownNow();
            } catch (Exception e) {
                FLog.w(TAG, "Error shutting down motion analysis executor", e);
            }
            motionAnalysisExecutor = null;
        }
        aiInferenceInFlight.set(false);

        FLog.d(TAG, "Service destroyed.");
        // Clean up GeotagHelper when service is destroyed
        super.onDestroy();
    }
    // --- End Lifecycle Methods ---

    // --- Core Recording Logic ---
    private void stopRecording() {
        if (durationLimitController != null && durationLimitController.stopSession()) {
            FLog.d(TAG, "Cancelled maximum recording duration for current session");
        }
        // The session file is about to be finalized asynchronously on the stop
        // thread — guard it from the self-healing scan until finalization is
        // done (see IN_FLIGHT_FINALIZATIONS).
        final String inFlightUri = getCurrentRecordingMediaUri();
        addInFlightFinalization(inFlightUri);
        if (isStopping) {
            FLog.w(TAG, "stopRecording: Already in stopping process, ignoring duplicate call");
            return;
        }

        isStopping = true;
        FLog.i(TAG, ">> stopRecording sequence initiated. Current state: " + recordingState);

        // Stop black frame rendering if active
        stopBlackFrameRendering();

        // Stop reconnection attempts if active
        stopReconnectionAttempts();

        if (recordingState == RecordingState.NONE) {
            FLog.d(TAG, "stopRecording called but state is already NONE, just cleaning up");
            sharedPreferencesManager.setRecordingInProgress(false);
            if (!isWorkingInProgress())
                stopSelf();
            if (recordingWakeLock != null && recordingWakeLock.isHeld())
                recordingWakeLock.release();
            if (safRecordingPfd != null) {
                try {
                    safRecordingPfd.close();
                    // log removed
                } catch (Exception e) {
                    FLog.e(TAG, "Error closing SAF ParcelFileDescriptor", e);
                }
                safRecordingPfd = null;
            }
            // ⚠️ Also close segment PFD if it exists
            if (currentSegmentParcelFileDescriptor != null) {
                try {
                    currentSegmentParcelFileDescriptor.close();
                    FLog.d(TAG, "Closed current segment ParcelFileDescriptor during early cleanup");
                } catch (Exception e) {
                    FLog.e(TAG, "Error closing segment PFD during early cleanup", e);
                }
                currentSegmentParcelFileDescriptor = null;
            }
            isStopping = false; // Reset stopping flag if we're already stopped
            return;
        }

        if (motionLabEnabledForSession) {
            FLog.i(TAG, "MotionLab summary: frames=" + motionFramesAnalyzed
                    + ", actions=" + motionTriggerActionCount
                    + ", suppressed=" + motionSuppressedSignalCount
                    + ", detectorAvailable=" + (efficientDetDetector != null && efficientDetDetector.isAvailable()));
        }
        if (digitalForensicsEventRecorder != null) {
            long timelineMs = getEffectiveTimelineMs();
            digitalForensicsEventRecorder.flush(timelineMs);
        }

        // First update the state to prevent any new operations
        recordingState = RecordingState.NONE;
        sharedPreferencesManager.setRecordingInProgress(false);
        com.fadcam.ActiveRecordingStats.clearActiveSegment();
        
        // Notify RemoteStreamManager that recording stopped
        try {
            com.fadcam.FeatureRegistry.streaming().stopRecording();
            com.fadcam.FeatureRegistry.streaming().setMotionState("off");
            FLog.i(TAG, "🛑 RemoteStreamManager notified: recording stopped");
            
        } catch (Exception e) {
            FLog.e(TAG, "Failed to notify RemoteStreamManager about recording stop", e);
        }

        recordingStartTime = 0L;
        pauseStartedAt = 0L;
        accumulatedPausedDurationMs = 0L;
        clearRecordingTimelineState();

        // Stop foreground service and cancel notification early to improve
        // responsiveness
        com.fadcam.Utils.stopForegroundCompat(this, true);
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
                        FLog.d(TAG, "Stopping repeating request and closing capture session");
                        captureSession.stopRepeating();
                        captureSession.close();
                    } catch (Exception e) {
                        FLog.e(TAG, "Error closing capture session", e);
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
                boolean liveIdleSession = liveIdleUsedThisSession;
                int fileFragmentsAfterStop = -1;
                if (glRecordingPipeline != null) {
                    try {
                        FLog.d(TAG, "Stopping GLRecordingPipeline");
                        glRecordingPipeline.stopRecording();
                        // Query AFTER stop: muxer.close() flushes pending samples
                        // into a final fragment, so the pre-stop count under-reports.
                        fileFragmentsAfterStop = glRecordingPipeline.getWrittenFileFragmentCount();
                    } catch (Exception e) {
                        FLog.e(TAG, "Error stopping GLRecordingPipeline", e);
                    } finally {
                        glRecordingPipeline = null;
                    }
                }
                pausedWithLiveStream = false;
                liveIdleUsedThisSession = false;

                // Remove only provably-empty outputs that THIS session created.
                // A 0-byte file contains no media data by definition, so deleting it
                // can never lose a recording. This covers both storage modes:
                //   - local/default storage: File objects tracked above,
                //   - custom folder / SD card: the content URI created this session.
                if (liveIdleSession && fileFragmentsAfterStop == 0
                        && currentSegmentFile != null && currentSegmentFile.exists()) {
                    long length = currentSegmentFile.length();
                    if (length <= 256L * 1024L) {
                        boolean deleted = currentSegmentFile.delete();
                        if (deleted) {
                            FLog.i(TAG, "Live-idle session produced no media; removed empty file: "
                                    + currentSegmentFile.getName() + " (" + length + "B)");
                        } else {
                            FLog.w(TAG, "Live-idle session empty file could not be removed: "
                                    + currentSegmentFile.getAbsolutePath());
                        }
                    } else {
                        FLog.w(TAG, "Live-idle session has 0 fragments but file is "
                                + length + "B — leaving it untouched");
                    }
                }

                java.util.List<File> createdSnapshot;
                synchronized (sessionCreatedLocalFiles) {
                    createdSnapshot = new java.util.ArrayList<>(sessionCreatedLocalFiles);
                }
                for (File created : createdSnapshot) {
                    if (created == null || !created.exists()) {
                        continue;
                    }
                    if (created.length() == 0L) {
                        boolean deleted = created.delete();
                        FLog.i(TAG, "Removed 0-byte session output: " + created.getName()
                                + " deleted=" + deleted);
                    }
                }
                sessionCreatedLocalFiles.clear();

                // Custom folder / SD card (SAF): same rule, applied to the content
                // URI this session created. Only when the muxer confirms zero media
                // fragments, and only when the provider reports a tiny/zero size.
                if (liveIdleSession && fileFragmentsAfterStop == 0
                        && currentSegmentFile == null && currentSegmentUriString != null) {
                    try {
                        Uri uri = Uri.parse(currentSegmentUriString);
                        if ("content".equals(uri.getScheme())) {
                            androidx.documentfile.provider.DocumentFile doc =
                                    androidx.documentfile.provider.DocumentFile
                                            .fromSingleUri(this, uri);
                            if (doc != null && doc.exists()) {
                                long length = doc.length();
                                if (length <= 256L * 1024L) {
                                    boolean deleted = doc.delete();
                                    FLog.i(TAG, "Live-idle session (SAF) produced no media; "
                                            + "delete=" + deleted + " len=" + length + "B uri=" + uri);
                                } else {
                                    FLog.w(TAG, "Live-idle session (SAF) has 0 fragments but "
                                            + "document is " + length + "B — leaving it untouched");
                                }
                            }
                        }
                    } catch (Exception e) {
                        FLog.w(TAG, "Live-idle SAF cleanup failed (left untouched)", e);
                    }
                }

                // Delete temporary file if in STREAM_ONLY mode
                if (currentSegmentFile != null && currentSegmentFile.exists()) {
                    com.fadcam.StreamingMode streamingMode = 
                        com.fadcam.FeatureRegistry.streaming().getStreamingMode();
                    if (streamingMode == com.fadcam.StreamingMode.STREAM_ONLY) {
                        boolean deleted = currentSegmentFile.delete();
                        if (deleted) {
                            FLog.i(TAG, "🗑️ STREAM_ONLY: Deleted temporary file: " + currentSegmentFile.getName());
                        } else {
                            FLog.w(TAG, "⚠️ Failed to delete temporary file: " + currentSegmentFile.getAbsolutePath());
                        }
                    }
                }

                // STREAM_ONLY: Clean up ALL tracked temporary files (internal storage)
                // Handles segment splits where multiple temp files were created
                if (!streamOnlyTempFiles.isEmpty()) {
                    FLog.i(TAG, "🗑️ STREAM_ONLY: Cleaning up " + streamOnlyTempFiles.size() + " tracked temp files");
                    for (File tempFile : streamOnlyTempFiles) {
                        if (tempFile != null && tempFile.exists()) {
                            boolean deleted = tempFile.delete();
                            FLog.i(TAG, "🗑️ STREAM_ONLY: " + (deleted ? "Deleted" : "FAILED to delete") + " temp: " + tempFile.getName());
                        }
                    }
                    streamOnlyTempFiles.clear();
                }

                // STREAM_ONLY (SAF): Clean up tracked SAF URIs (SD card storage)
                // These are 0-byte files created for the GL pipeline but never written to
                if (!streamOnlySafUris.isEmpty()) {
                    FLog.i(TAG, "🗑️ STREAM_ONLY (SAF): Cleaning up " + streamOnlySafUris.size() + " tracked SAF files");
                    for (String uriStr : streamOnlySafUris) {
                        try {
                            Uri safUri = Uri.parse(uriStr);
                            androidx.documentfile.provider.DocumentFile docFile =
                                    androidx.documentfile.provider.DocumentFile.fromSingleUri(RecordingService.this, safUri);
                            if (docFile != null && docFile.exists()) {
                                boolean deleted = docFile.delete();
                                FLog.i(TAG, "🗑️ STREAM_ONLY (SAF): " + (deleted ? "Deleted" : "FAILED to delete") + " URI: " + uriStr);
                            }
                        } catch (Exception e) {
                            FLog.w(TAG, "⚠️ STREAM_ONLY (SAF): Error deleting URI: " + uriStr + " - " + e.getMessage());
                        }
                    }
                    streamOnlySafUris.clear();
                }

                // Give some time for the GL pipeline to release resources
                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }

                // REMUX: Removed as per user request (writing directly to .mp4 now)
                // com.fadcam.media.VideoFileProcessor.CRASH_SAFE_EXTENSION cleanup check removed.

                // Close the camera device last
                if (cameraDevice != null) {
                    try {
                        cameraDevice.close();
                    } catch (Exception e) {
                        FLog.e(TAG, "Error closing camera device", e);
                    } finally {
                        cameraDevice = null;
                        isCameraOpen = false;
                    }
                }

                // Stop and reset NoiseMonitor singleton
                if (noiseMonitor != null) {
                    com.fadcam.audio.NoiseMonitor.resetInstance();
                    noiseMonitor = null;
                }

                // Reset sensor data provider singleton so next recording starts fresh
                com.fadcam.sensors.SensorDataProvider.resetInstance();
                sensorDataProvider = null;
                weatherService = null;
                FLog.d(TAG, "All extended sensor providers cleaned up");

                // Also destroy shared WatermarkManager
                if (watermarkManager != null) {
                    watermarkManager.destroy();
                    watermarkManager = null;
                }

                // Final cleanup on the main thread
                mainHandler.post(() -> {
                    // Release wake lock if held
                    if (recordingWakeLock != null && recordingWakeLock.isHeld()) {
                        try {
                            recordingWakeLock.release();
                            // log removed
                        } catch (Exception e) {
                            FLog.e(TAG, "Error releasing wake lock", e);
                        }
                    }

                    // -----
                    if (safRecordingPfd != null) {
                        try {
                            safRecordingPfd.close();
                            // log removed
                        } catch (Exception e) {
                            FLog.e(TAG, "Error closing SAF ParcelFileDescriptor (background thread)", e);
                        }
                        safRecordingPfd = null;
                    }
                    
                    // ⚠️ CRITICAL: Close current segment PFD for video splitting (SAF mode)
                    // This keeps the PFD alive during segment rollover to prevent EBADF errors
                    if (currentSegmentParcelFileDescriptor != null) {
                        try {
                            currentSegmentParcelFileDescriptor.close();
                            FLog.d(TAG, "Closed current segment ParcelFileDescriptor after recording");
                        } catch (Exception e) {
                            FLog.e(TAG, "Error closing current segment PFD", e);
                        }
                        currentSegmentParcelFileDescriptor = null;
                    }
                    // -----
                    // Check if service can stop — but ONLY if no new recording
                    // started during cleanup.  The GL pipeline finalisation
                    // (audio drain, muxer close) can take >1 s, and the user
                    // may have tapped "record" again in the meantime.
                    if (recordingState == RecordingState.NONE) {
                        checkIfServiceCanStop();
                    } else {
                        FLog.d(TAG, "Skipping service stop — new recording started during cleanup (state=" + recordingState + ")");
                    }

                    // Reset stopping flag
                    isStopping = false;

                    // Clear any pending recording start flag — only if no
                    // new recording is in progress
                    if (recordingState == RecordingState.NONE) {
                        pendingStartRecording = false;
                    }

                    // Send broadcast to notify that recording is complete so RecordsFragment can
                    // refresh
                    try {
                        Intent recordingCompleteIntent = new Intent(Constants.ACTION_RECORDING_COMPLETE);
                        recordingCompleteIntent.putExtra(Constants.EXTRA_RECORDING_SUCCESS, true);
                        // Note: We don't have the specific URI here, but RecordsFragment will do a full
                        // refresh
                        recordingCompleteIntent.setPackage(getPackageName());
                        sendBroadcast(recordingCompleteIntent);
                    } catch (Exception e) {
                        FLog.e(TAG, "Error broadcasting recording complete", e);
                    }

                    FLog.d(TAG, "stopRecording sequence completed successfully");
                    removeInFlightFinalization(inFlightUri);
                    finalizeJustStoppedRecording();
                });            } catch (Exception e) {
                FLog.e(TAG, "Error in stopRecording cleanup thread", e);
                removeInFlightFinalization(inFlightUri);
                mainHandler.post(() -> {
                    isStopping = false;
                    pendingStartRecording = false;

                    // Send broadcast even on error so RecordsFragment can refresh and clear any
                    // temp states
                    try {
                        Intent recordingCompleteIntent = new Intent(Constants.ACTION_RECORDING_COMPLETE);
                        recordingCompleteIntent.putExtra(Constants.EXTRA_RECORDING_SUCCESS, false);
                        recordingCompleteIntent.setPackage(getPackageName());
                        sendBroadcast(recordingCompleteIntent);
                        FLog.d(TAG, "Broadcasted ACTION_RECORDING_COMPLETE (error case) for list refresh");
                    } catch (Exception broadcastError) {
                        FLog.e(TAG, "Error broadcasting recording complete on error", broadcastError);
                    }
                });
            }
        }, "RecordingStopThread").start();
    }

    /**
     * True when pausing should keep the camera/encoder alive so the live view
     * continues, and only stop writing the recording file. This is the case when
     * the remote server is on. Never used for camera switches: that path tears the
     * camera session down and must use the real pause.
     */
    private boolean shouldKeepLiveStreamWhilePaused() {
        return glRecordingPipeline != null
                && glRecordingPipeline.isRecording()
                && !isSwitchingCamera
                && com.fadcam.FeatureRegistry.streaming().isStreamingEnabled();
    }

    private void pauseRecording() {
        if (recordingState != RecordingState.IN_PROGRESS)
            return;
        boolean keepLive = shouldKeepLiveStreamWhilePaused();
        if (glRecordingPipeline != null && keepLive) {
            // Keep encoding so the HLS stream never stops; only the file pauses.
            FLog.i(TAG, "Live stream active: suspending recording file only");
            keepLive = glRecordingPipeline.suspendFileRecording();
        }
        if (glRecordingPipeline != null && !keepLive) {
            glRecordingPipeline.pauseRecording(); // if supported
        }
        pausedWithLiveStream = keepLive;
        if (keepLive) {
            liveIdleUsedThisSession = true;
        }
        if (pauseStartedAt <= 0L) {
            pauseStartedAt = SystemClock.elapsedRealtime();
        }
        persistRecordingTimelineState();
        recordingState = RecordingState.PAUSED;
        // Realign the motion state machine: it kept running while the file was
        // paused, and its expired POST_ROLL timer would immediately pause the
        // recording again on the first frame after a manual resume.
        if (motionStateMachine != null) {
            motionStateMachine.syncToRecording(false);
        }
        motionConsecutivePersonHits = 0;
        motionAutoPaused = false;
        sharedPreferencesManager.setRecordingInProgress(false);
        if (durationLimitController != null && durationLimitController.pauseSession()) {
            FLog.d(TAG, "Paused maximum recording duration countdown");
        }
        // Notify RemoteStreamManager so status JSON reflects paused state
        com.fadcam.FeatureRegistry.streaming().pauseRecording();

        // Battery saving: release camera while paused (unless motion detection or
        // the live view needs it). The GL pipeline stays alive (paused) so
        // recording can resume instantly.
        if (!keepLive && !motionLabEnabledForSession && !isSwitchingCamera) {
            FLog.d(TAG, "Releasing camera during pause (motion detection disabled)");
            releaseDummyBackgroundSurface();
            releaseMotionAnalysisReader();
            closeCameraResourcesForSwitch();
        }

        // Pause noise monitor and sensors — no audio recorded, no watermark data needed
        if (noiseMonitor != null && noiseMonitor.isRunning()) {
            noiseMonitor.stop();
        }
        if (sensorDataProvider != null) {
            sensorDataProvider.stop();
        }
        if (watermarkManager != null) watermarkManager.pauseSensors();

        setupRecordingResumeNotification();
        showRecordingInPausedToast();
        broadcastOnRecordingPaused();
    }

    private void resumeRecording() {
        if (recordingState != RecordingState.PAUSED)
            return;

        boolean resumeLive = pausedWithLiveStream;
        pausedWithLiveStream = false;

        // Reopen camera if it was released during pause (motion detection disabled).
        // openCamera() is async — the pipeline handles frames arriving after resume.
        if (!resumeLive && !motionLabEnabledForSession && cameraDevice == null && !isSwitchingCamera) {
            FLog.d(TAG, "Reopening camera for resume (was released during pause)");
            openCamera();
        }

        if (glRecordingPipeline != null) {
            if (resumeLive) {
                FLog.i(TAG, "Live stream active: resuming recording file only");
                glRecordingPipeline.resumeFileRecording();
            } else {
                glRecordingPipeline.resumeRecording(); // if supported
            }
        }
        if (pauseStartedAt > 0L) {
            accumulatedPausedDurationMs += Math.max(0L, SystemClock.elapsedRealtime() - pauseStartedAt);
            pauseStartedAt = 0L;
        }
        persistRecordingTimelineState();
        recordingState = RecordingState.IN_PROGRESS;
        // Realign the motion state machine to the state the recorder is now in, so a
        // manual resume continues motion recording instead of being stopped by a
        // stale POST_ROLL expiry.
        if (motionStateMachine != null) {
            motionStateMachine.syncToRecording(true);
        }
        motionConsecutivePersonHits = 0;
        motionAutoPaused = false;
        sharedPreferencesManager.setRecordingInProgress(true);
        if (durationLimitController != null && durationLimitController.resumeSession()) {
            FLog.d(TAG, "Resumed maximum recording duration countdown");
        }
        // Notify RemoteStreamManager so status JSON reflects resumed (recording) state
        com.fadcam.FeatureRegistry.streaming().resumeRecording();

        // Resume noise monitor and sensors if they were enabled
        if (!resumeLive && sharedPreferencesManager != null && sharedPreferencesManager.isNoiseEnabled() && noiseMonitor != null) {
            noiseMonitor.start(this);
        }
        if (!resumeLive && sensorDataProvider != null) {
            // Seed with the REAL last fix (carries speed/altitude), not a
            // fabricated coords-only Location. Null is fine — the next live fix
            // updates the provider anyway.
            android.location.Location androidLoc = locationHelper != null ? locationHelper.getRawLocation() : null;
            sensorDataProvider.start(androidLoc);
        }
        if (!resumeLive && watermarkManager != null) watermarkManager.resumeSensors(locationHelper);

        // Coming back from PAUSED: update straight to the configured text instead
        // of flashing "Initializing..." and waiting on the camera handler.
        refreshRecordingNotificationAsync();
        showRecordingResumedToast();
        broadcastOnRecordingResumed();
    }

    /**
     * Switches cameras during active recording.
     * Performs a seamless switch from current camera to target camera while maintaining recording.
     * 
     * Architecture:
     * 1. Pause recording (stop frame capture)
     * 2. Drain encoder (flush pending frames)
     * 3. Close current camera/session
     * 4. Open new camera
     * 5. Resume recording (restart frame capture)
     * 
     * Expected duration: 150-350ms (typically ~200ms)
     * 
     * @param newCameraType Target camera: FRONT or BACK
     */
    private void switchCameraLive(@NonNull CameraType newCameraType) {
        // Validate preconditions
        if (isSwitchingCamera) {
            FLog.w(TAG, "Camera switch already in progress, ignoring request");
            broadcastOnCameraSwitchFailed("Switch already in progress", newCameraType);
            return;
        }

        if (recordingState != RecordingState.IN_PROGRESS) {
            FLog.w(TAG, "Cannot switch camera: recording state is " + recordingState + ", need IN_PROGRESS");
            broadcastOnCameraSwitchFailed("Recording not in progress (state: " + recordingState + ")", newCameraType);
            return;
        }

        if (glRecordingPipeline == null || captureSession == null) {
            FLog.e(TAG, "Cannot switch camera: pipeline or capture session is null");
            broadcastOnCameraSwitchFailed("Recording pipeline not initialized", newCameraType);
            return;
        }

        CameraType currentType = sharedPreferencesManager.getCameraSelection();
        if (currentType == newCameraType) {
            FLog.w(TAG, "Camera is already " + newCameraType + ", ignoring switch");
            broadcastOnCameraSwitchFailed("Already on " + newCameraType, newCameraType);
            return;
        }

        // Mark switch in progress
        isSwitchingCamera = true;
        cameraSwitchPreviousType = currentType;
        cameraSwitchStartTimeNanos = System.nanoTime();

        try {
            FLog.i(TAG, "========== CAMERA SWITCH START ==========");
            FLog.i(TAG, "Switching camera: " + currentType + " → " + newCameraType);
            broadcastOnCameraSwitchStarted(currentType, newCameraType);

            // PHASE 0: Prepare pipeline for camera switch (timestamp handling)
            FLog.d(TAG, "PHASE 0: Preparing pipeline for camera switch");
            if (glRecordingPipeline != null) {
                glRecordingPipeline.prepareCameraSwitch();
            }

            // PHASE 1: Pause recording
            pauseRecording();

            // PHASE 2: Drain encoder
            FLog.d(TAG, "PHASE 2: Draining encoder (timeout: 200ms)");
            drainEncoderBeforeCameraSwitch(200);

            // PHASE 3: Close resources
            FLog.d(TAG, "PHASE 3: Closing camera session and device");
            closeCameraResourcesForSwitch();

            // PHASE 4: Update preference
            FLog.d(TAG, "PHASE 4: Updating camera selection preference");
            sharedPreferencesManager.sharedPreferences
                .edit()
                .putString(Constants.PREF_CAMERA_SELECTION, newCameraType.toString())
                .apply();

            // PHASE 5: Open new camera
            FLog.d(TAG, "PHASE 5: Opening new camera");
            openCamera(); // Will use updated preference

            // PHASE 6: Resume recording
            resumeRecording();

            // Brief delay to allow first frame from new camera
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                FLog.w(TAG, "Interrupted waiting for new camera frame");
            }

            long durationMs = (System.nanoTime() - cameraSwitchStartTimeNanos) / 1_000_000L;
            FLog.i(TAG, "========== CAMERA SWITCH SUCCESS ==========");
            FLog.i(TAG, "Camera switched to " + newCameraType + " in " + durationMs + "ms");
            broadcastOnCameraSwitchComplete(currentType, newCameraType);

        } catch (Exception e) {
            FLog.e(TAG, "FATAL: Camera switch failed with exception", e);
            long durationMs = (System.nanoTime() - cameraSwitchStartTimeNanos) / 1_000_000L;
            FLog.i(TAG, "========== CAMERA SWITCH FAILED ==========");
            FLog.i(TAG, "Camera switch took " + durationMs + "ms before failure");

            // Attempt recovery: try to resume old camera
            FLog.w(TAG, "Attempting recovery: reopening " + cameraSwitchPreviousType + " camera");
            try {
                sharedPreferencesManager.sharedPreferences
                    .edit()
                    .putString(Constants.PREF_CAMERA_SELECTION, cameraSwitchPreviousType.toString())
                    .apply();
                openCamera();
                resumeRecording();
                FLog.i(TAG, "Recovery successful: recording resumed on " + cameraSwitchPreviousType);
                broadcastOnCameraSwitchFailed("Switch failed but recovered on " + cameraSwitchPreviousType, newCameraType);
            } catch (Exception recoveryError) {
                FLog.e(TAG, "CRITICAL: Recovery failed, recording is likely corrupted", recoveryError);
                // Stop recording to avoid further corruption
                try {
                    stopRecording();
                } catch (Exception stopError) {
                    FLog.e(TAG, "Error stopping recording during recovery failure", stopError);
                }
                broadcastOnCameraSwitchFailed("Switch failed AND recovery failed: " + e.getMessage(), newCameraType);
            }
        } finally {
            isSwitchingCamera = false;
            cameraSwitchPreviousType = null;
            cameraSwitchStartTimeNanos = -1L;
        }
    }

    private void switchCameraPreviewOnly(@NonNull CameraType newCameraType) {
        if (!previewOnlyActive) {
            FLog.w(TAG, "Preview-only camera switch ignored: preview-only mode is not active");
            return;
        }
        if (newCameraType.isDual()) {
            broadcastOnCameraSwitchFailed("Dual camera is not supported in preview-only mode", newCameraType);
            return;
        }

        CameraType currentType = sharedPreferencesManager.getCameraSelection();
        if (currentType == newCameraType) {
            FLog.d(TAG, "Preview-only camera switch ignored: already on " + newCameraType);
            return;
        }

        FLog.i(TAG, "Preview-only camera switch: " + currentType + " -> " + newCameraType);
        try {
            sharedPreferencesManager.sharedPreferences
                .edit()
                .putString(Constants.PREF_CAMERA_SELECTION, newCameraType.toString())
                .apply();

            previewSessionConfigInFlight = false;
            if (captureSession != null) {
                try {
                    captureSession.stopRepeating();
                } catch (Exception ignored) {
                }
                try {
                    captureSession.close();
                } catch (Exception ignored) {
                }
                captureSession = null;
            }
            if (cameraDevice != null) {
                try {
                    cameraDevice.close();
                } catch (Exception ignored) {
                }
                cameraDevice = null;
            }
            isCameraOpen = false;
            if (glRecordingPipeline != null && previewSurface != null && previewSurface.isValid()) {
                glRecordingPipeline.setPreviewSurfaceImmediate(previewSurface);
            }
            openCamera();
            broadcastOnCameraSwitchComplete(currentType, newCameraType);
        } catch (Exception e) {
            FLog.e(TAG, "Preview-only camera switch failed", e);
            try {
                sharedPreferencesManager.sharedPreferences
                    .edit()
                    .putString(Constants.PREF_CAMERA_SELECTION, currentType.toString())
                    .apply();
            } catch (Exception ignored) {
            }
            broadcastOnCameraSwitchFailed("Preview-only camera switch failed: " + e.getMessage(), newCameraType);
        }
    }

    /**
     * Drains the video encoder to ensure all buffered frames are written to muxer
     * before camera switch. This prevents mixing frames from old and new cameras.
     * 
     * @param timeoutMs Maximum time to wait for drain to complete
     */
    private void drainEncoderBeforeCameraSwitch(long timeoutMs) {
        if (glRecordingPipeline == null) {
            FLog.w(TAG, "Cannot drain encoder: pipeline is null");
            return;
        }

        long startTime = System.currentTimeMillis();
        long elapsedMs = 0;

        // Give the render loop time to drain naturally
        while (elapsedMs < timeoutMs) {
            try {
                Thread.sleep(10); // Check every 10ms
                elapsedMs = System.currentTimeMillis() - startTime;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                FLog.w(TAG, "Interrupted during encoder drain");
                break;
            }
        }

        FLog.d(TAG, "Encoder drain completed after " + elapsedMs + "ms (timeout: " + timeoutMs + "ms)");
    }

    /**
     * Closes the current camera session and device in preparation for switching to a new camera.
     * Must be called during a pause state to avoid frame loss.
     */
    private void closeCameraResourcesForSwitch() {
        try {
            // Close the capture session
            if (captureSession != null) {
                try {
                    captureSession.close();
                } catch (Exception e) {
                    FLog.w(TAG, "Error closing capture session", e);
                }
                captureSession = null;
            }

            // Close the camera device
            if (cameraDevice != null) {
                try {
                    cameraDevice.close();
                } catch (Exception e) {
                    FLog.w(TAG, "Error closing camera device", e);
                }
                cameraDevice = null;
            }

            isCameraOpen = false;
        } catch (Exception e) {
            FLog.e(TAG, "Unexpected error closing camera resources", e);
        }
    }

    /**
     * Broadcasts camera switch started event with source and target camera types.
     */
    private void broadcastOnCameraSwitchStarted(@NonNull CameraType fromType, @NonNull CameraType toType) {
        Intent broadcastIntent = new Intent(Constants.BROADCAST_ON_CAMERA_SWITCH_STARTED);
        broadcastIntent.putExtra(Constants.BROADCAST_EXTRA_CAMERA_TYPE_FROM, fromType.toString());
        broadcastIntent.putExtra(Constants.BROADCAST_EXTRA_CAMERA_TYPE_TO, toType.toString());
        this.sendBroadcast(broadcastIntent.setPackage(this.getPackageName()));
        FLog.d(TAG, "Broadcast: CAMERA_SWITCH_STARTED (" + fromType + " → " + toType + ")");
    }

    /**
     * Broadcasts camera switch complete event with source and target camera types.
     */
    private void broadcastOnCameraSwitchComplete(@NonNull CameraType fromType, @NonNull CameraType toType) {
        Intent broadcastIntent = new Intent(Constants.BROADCAST_ON_CAMERA_SWITCH_COMPLETE);
        broadcastIntent.putExtra(Constants.BROADCAST_EXTRA_CAMERA_TYPE_FROM, fromType.toString());
        broadcastIntent.putExtra(Constants.BROADCAST_EXTRA_CAMERA_TYPE_TO, toType.toString());
        this.sendBroadcast(broadcastIntent.setPackage(this.getPackageName()));
        FLog.d(TAG, "Broadcast: CAMERA_SWITCH_COMPLETE (" + fromType + " → " + toType + ")");
    }

    /**
     * Broadcasts camera switch failed event with error reason and attempted camera type.
     */
    private void broadcastOnCameraSwitchFailed(@NonNull String errorReason, @Nullable CameraType attemptedType) {
        Intent broadcastIntent = new Intent(Constants.BROADCAST_ON_CAMERA_SWITCH_FAILED);
        broadcastIntent.putExtra(Constants.BROADCAST_EXTRA_SWITCH_ERROR_REASON, errorReason);
        if (attemptedType != null) {
            broadcastIntent.putExtra(Constants.BROADCAST_EXTRA_CAMERA_TYPE_ATTEMPTED, attemptedType.toString());
        }
        this.sendBroadcast(broadcastIntent.setPackage(this.getPackageName()));
        FLog.d(TAG, "Broadcast: CAMERA_SWITCH_FAILED (reason: " + errorReason + ", attempted: " + attemptedType + ")");
    }

    private void releaseRecordingResources() {
        if (isStopping)
            return;

        // Release dummy background surface first
        releaseDummyBackgroundSurface();
        releaseMotionAnalysisReader();

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
        // -----
        if (safRecordingPfd != null) {
            try {
                safRecordingPfd.close();
                // log removed
            } catch (Exception e) {
                FLog.e(TAG, "Error closing SAF ParcelFileDescriptor (resource cleanup)", e);
            }
            safRecordingPfd = null;
        }
        
        // ⚠️ CRITICAL: Close segment PFD for video splitting (SAF mode)
        if (currentSegmentParcelFileDescriptor != null) {
            try {
                currentSegmentParcelFileDescriptor.close();
                FLog.d(TAG, "Closed current segment ParcelFileDescriptor (resource cleanup)");
            } catch (Exception e) {
                FLog.e(TAG, "Error closing segment PFD (resource cleanup)", e);
            }
            currentSegmentParcelFileDescriptor = null;
        }
        // -----
        recordingState = RecordingState.NONE;
        sharedPreferencesManager.setRecordingInProgress(false);
    }

    private void configureMotionLabForSession() {
        if (sharedPreferencesManager != null) {
            if (motionRuntime != null) {
                motionRuntime.release();
            }
            motionRuntime = new com.fadcam.motion.domain.model.MotionRuntimeSettings(sharedPreferencesManager);
        }
        // Lite has no Motion Lab: the preference is not exposed there, so arming on a
        // restored/leftover value would run detection with no way to see or change it.
        motionLabEnabledForSession = !com.fadcam.BuildConfig.LITE_EDITION
                && sharedPreferencesManager != null
                && sharedPreferencesManager.isMotionModeEnabled();
        com.fadcam.FeatureRegistry.streaming().setMotionState(motionLabEnabledForSession ? "armed" : "off");
        lastMotionAnalysisTimestampMs = 0L;
        motionAutoPaused = false;
        motionConsecutivePersonHits = 0;
        motionFramesAnalyzed = 0L;
        motionTriggerActionCount = 0L;
        motionSuppressedSignalCount = 0L;
        motionScoreEma = Float.NaN;
        motionLastDebugBroadcastMs = 0L;
        motionLastTelemetryLogMs = 0L;
        motionPersonLikelyUntilMs = 0L;
        motionLastForensicsHeartbeatMs = 0L;
        forensicsSubjectPresentLastSample = false;
        motionJpegAttemptCount = 0L;
        motionJpegSuccessCount = 0L;
        motionJpegSkipCount = 0L;
        motionJpegEncodeTotalMs = 0L;
        motionLastPerfLogMs = 0L;
        appliedAnalysisResolution = -1;
        appliedPreRollSeconds = -1;
        analysisSurfaceReconfigurePending = false;
        motionStateMachine = motionLabEnabledForSession
                ? new com.fadcam.motion.domain.state.MotionStateMachine(motionPolicy)
                : null;
        if (!motionLabEnabledForSession) {
            releaseMotionAnalysisReader();
        } else {
            scheduleMotionDetectorWarmupIfNeeded();
        }
        if (sharedPreferencesManager != null) {
            int sensitivity = sharedPreferencesManager.getMotionSensitivity();
            int fps = sharedPreferencesManager.getMotionAnalysisFps();
            int debounce = sharedPreferencesManager.getMotionDebounceMs();
            int postRoll = sharedPreferencesManager.getMotionPostRollMs();
            String scope = sharedPreferencesManager.getDfCaptureScope();
            float start = motionPolicy.startThresholdFromSensitivity(sensitivity);
            float stop = motionPolicy.stopThresholdFromSensitivity(sensitivity);
        }
    }

    private void maybeAttachMotionAnalysisSurface(List<Surface> surfaces, int targetFrameRate) {
        if (!motionLabEnabledForSession) {
            return;
        }
        Surface surface = getOrCreateMotionAnalysisSurface();
        if (surface != null) {
            surfaces.add(surface);
            FLog.d(TAG, "Motion analysis surface attached to camera session");
        } else {
            disableMotionLabForSession("analysis_surface_unavailable");
        }
    }

    private void disableMotionProcessingForSession(String reason) {
        motionLabEnabledForSession = false;
        motionStateMachine = null;
        motionAutoPaused = false;
        com.fadcam.FeatureRegistry.streaming().setMotionState("off");
        // Keep the ImageReader attached: the camera session is already live and
        // detaching a surface mid-session is unsafe. It is released on stop.
        FLog.w(TAG, "Motion processing disabled for this session. reason=" + reason);
    }

    private void disableMotionLabForSession(String reason) {
        if (!motionLabEnabledForSession) {
            return;
        }
        motionLabEnabledForSession = false;
        motionStateMachine = null;
        motionAutoPaused = false;
        com.fadcam.FeatureRegistry.streaming().setMotionState("off");
        releaseMotionAnalysisReader();
        FLog.w(TAG, "Motion Lab disabled for current session. reason=" + reason + "; continuing normal recording flow");
    }

    private Surface getOrCreateMotionAnalysisSurface() {
        try {
            // Backend capability, not warmup state: the analysis surface is created
            // before the detector is ready, and the size must match the backend.
            boolean highResBackend =
                    com.fadcam.FeatureRegistry.features().hasOpenCvMotionBackend();
            // The user's value is the value. No hidden caps: a lower setting is a
            // deliberate CPU choice, and silently running it faster would make the
            // setting a lie.
            int analysisFps = motionRuntime != null
                    ? motionRuntime.getAnalysisFps()
                    : (sharedPreferencesManager != null
                        ? sharedPreferencesManager.getMotionAnalysisFps() : 10);
            motionAnalysisIntervalMs = Math.max(100L, 1000L / Math.max(1, analysisFps));
            Size selected = sharedPreferencesManager != null
                    ? sharedPreferencesManager.getCameraResolution()
                    : Constants.DEFAULT_VIDEO_RESOLUTION;
            // Scale divisor with recording resolution: MOG2 and EfficientDet both work
            // fine at lower resolutions, but the camera ISP has to produce YUV frames
            // for the analysis surface, which can starve the encoder at high res+fps.
            // Analyzer frame size. The whole camera field of view is always
            // analysed (no cropping); only the sampling grid changes. OpenCV's
            // own MOG2 samples and production CCTV pipelines (CVEDIA ~300 px,
            // Intel reference 640 px) run well below sensor resolution, so this
            // is a quality/CPU trade-off, not a detail limit.
            int requestedHeight = motionRuntime != null
                    ? motionRuntime.getAnalysisResolution()
                    : (sharedPreferencesManager != null
                        ? sharedPreferencesManager.getMotionAnalysisResolution() : 720);
            // The selected analyzer resolution is used verbatim (never above the
            // camera's own output size). No hidden downgrade for low-RAM devices.
            int width;
            int height;
            height = Math.min(requestedHeight, Math.max(240, selected.getHeight()));
            width = Math.max(96, Math.round(
                    height * selected.getWidth() / (float) selected.getHeight()));
            if ((width & 1) != 0) width--;
            if ((height & 1) != 0) height--;
            boolean recreate = motionAnalysisReader == null
                    || motionAnalysisReader.getWidth() != width
                    || motionAnalysisReader.getHeight() != height;
            if (recreate) {
                releaseMotionAnalysisReader();
                motionAnalysisReader = ImageReader.newInstance(width, height, android.graphics.ImageFormat.YUV_420_888, 4);
                motionAnalysisReader.setOnImageAvailableListener(reader -> {
                    // This callback runs on the camera's own thread. Doing the analysis
                    // here meant MOG2 at up to 1920 px ran between capture requests,
                    // which starves the encoder and shows up as frame gaps and lag.
                    //
                    // The frame goes into a single latest-value slot and is closed
                    // immediately if a newer one replaces it. A work QUEUE here is
                    // wrong: each queued item keeps a camera buffer checked out, the
                    // HAL runs out of them, and the whole pipeline collapses to a few
                    // frames per second.
                    android.media.Image image;
                    try {
                        image = reader.acquireLatestImage();
                    } catch (Throwable t) {
                        FLog.w(TAG, "Motion analysis frame acquisition failed", t);
                        return;
                    }
                    if (image == null) {
                        return;
                    }
                    android.media.Image previous = pendingAnalysisImage.getAndSet(image);
                    if (previous != null) {
                        try {
                            previous.close();
                        } catch (Throwable ignored) {
                        }
                    }
                    scheduleAnalysisDrain();
                }, backgroundHandler);
                FLog.d(TAG, "Created motion analysis reader: " + width + "x" + height
                        + " @" + analysisFps + "fps");
                motionAnalysisWidth = width;
                motionAnalysisHeight = height;
                // Record what this reader was built for. Without this the first
                // analysed frame sees a "change" from the -1 sentinel and needlessly
                // rebuilds the camera session once per recording start.
                appliedAnalysisResolution = motionRuntime != null
                        ? motionRuntime.getAnalysisResolution()
                        : (sharedPreferencesManager != null
                            ? sharedPreferencesManager.getMotionAnalysisResolution() : 720);
            }
            return motionAnalysisReader.getSurface();
        } catch (Throwable t) {
            FLog.e(TAG, "Unable to create motion analysis surface", t);
            releaseMotionAnalysisReader();
            return null;
        }
    }

    /**
     * Hands the newest pending analysis frame to the analysis thread, one at a time.
     * If analysis is still busy the frame is simply left in the slot and replaced by
     * the next one; nothing queues up and no camera buffer is held.
     */
    private void scheduleAnalysisDrain() {
        if (motionAnalysisExecutor == null) {
            android.media.Image orphan = pendingAnalysisImage.getAndSet(null);
            if (orphan != null) {
                try {
                    orphan.close();
                } catch (Throwable ignored) {
                }
            }
            return;
        }
        if (!analysisDrainScheduled.compareAndSet(false, true)) {
            return;
        }
        motionAnalysisExecutor.execute(() -> {
            try {
                android.media.Image image = pendingAnalysisImage.getAndSet(null);
                if (image != null) {
                    processMotionAnalysisImage(image);
                }
            } catch (Throwable t) {
                FLog.w(TAG, "Motion analysis drain failed", t);
            } finally {
                analysisDrainScheduled.set(false);
                // A frame may have arrived while this one was being analysed.
                if (pendingAnalysisImage.get() != null) {
                    scheduleAnalysisDrain();
                }
            }
        });
    }

    /** Releases any frame still waiting for analysis. */
    private void discardPendingAnalysisImage() {
        android.media.Image image = pendingAnalysisImage.getAndSet(null);
        if (image != null) {
            try {
                image.close();
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * One analysis iteration, on the dedicated analysis thread.
     *
     * <p>Owns the {@link Image} it is given and always closes it. Frames arriving
     * while this is busy are dropped, which is correct: analysis works on the newest
     * frame, never on a backlog.
     */
    private void processMotionAnalysisImage(@NonNull android.media.Image image) {
        try {
                        long now = SystemClock.elapsedRealtime();
                        // Re-read the FPS every frame so a Motion Lab change applies
                        // immediately, without restarting the session.
                        com.fadcam.motion.domain.model.MotionRuntimeSettings runtime = motionRuntime;
                        int requestedFps = runtime != null ? runtime.getAnalysisFps() : 10;
                        boolean openCvBackend =
                                com.fadcam.FeatureRegistry.features().hasOpenCvMotionBackend();
                        // The configured rate is used as-is. The only bound is the
                        // shared 1..15 range that the preference itself enforces; there
                        // is no backend-specific or "low-RAM" override.
                        int cappedFps = Math.max(1, Math.min(15, requestedFps));
                        motionAnalysisIntervalMs = Math.max(50L, 1000L / Math.max(1, cappedFps));
                        long effectiveIntervalMs = motionAnalysisIntervalMs;
                        if (now - lastMotionAnalysisTimestampMs < effectiveIntervalMs) {
                            return;
                        }
                        lastMotionAnalysisTimestampMs = now;
                        com.fadcam.motion.domain.detector.MotionDetector activeDetector = motionDetector;
                        if (activeDetector == null) {
                            // Backend warmup not finished (or failed). Drop frames
                            // instead of silently using a different detector.
                            return;
                        }
                        activeDetector.setSensitivity(runtime != null ? runtime.getSensitivity() : 80);
                        float rawMotionScore = activeDetector.detectScore(image);

                        // Copying the full YUV frame at 10 fps and running the AI
                        // model on every frame starves the camera/encoder and makes
                        // the preview lag. The motion detector uses the Image
                        // directly; only copy when AI (2 fps) or the visual monitor
                        // (3 fps) actually needs a frame.
                        applyAnalysisResolutionIfChanged();

                        // The process-wide model may have finished loading after the
                        // session started, or been released when the feature was
                        // disabled. Re-read it every frame; never cache a dead handle.
                        // A GPU measured too slow is still used, but at a cadence the
                        // device can actually sustain. Disabling it entirely looked
                        // attractive until it turned out to disable forensic evidence too:
                        // without detections there are no events and nothing is stored.
                        // The adaptive interval below handles the cost instead.
                        efficientDetDetector =
                                com.fadcam.motion.domain.detector.ObjectDetectionRuntime.get();
                        // While the file is actually recording, the encoder shares the GPU
                        // with inference, so sample half as often; when idle there is no
                        // encoder to disturb and detection can run at the faster rate.
                        // Back off when the device cannot keep up. A GPU that needs
                        // ~1.5 s per inference (measured on a Tecno KG5k) cannot run at
                        // 1 Hz; without this it occupies the device continuously and
                        // makes the camera and UI worse. The cadence adapts to what the
                        // hardware actually achieves instead of assuming a fast GPU.
                        long baseAiIntervalMs = "IN_PROGRESS".equals(recordingState.name()) ? 1000L : 500L;
                        // Keep inference within roughly a third of wall time. A device
                        // needing 1.5 s per inference therefore analyses about once every
                        // 4.5 s instead of continuously occupying its GPU. That is slow
                        // but honest: evidence is still recorded, and the camera keeps
                        // running. The budget is a measured property of the hardware, not
                        // a hidden cap on what the feature can do.
                        // A user-chosen interval wins. Otherwise derive one from the
                        // measured cost, keeping inference to roughly a third of wall
                        // time so a slow GPU does not occupy the device continuously.
                        int userInterval = sharedPreferencesManager != null
                                ? sharedPreferencesManager.getMotionAiIntervalMs() : 0;
                        long aiIntervalMs;
                        if (userInterval > 0) {
                            aiIntervalMs = userInterval;
                        } else {
                            long observedAiMs = motionLastInferenceDurationMs;
                            aiIntervalMs = baseAiIntervalMs;
                            if (observedAiMs > 0L) {
                                aiIntervalMs = Math.max(baseAiIntervalMs, observedAiMs * 3L);
                                aiIntervalMs = Math.min(aiIntervalMs, MAX_AI_INTERVAL_MS);
                            }
                        }
                        if (aiIntervalMs != motionEffectiveAiIntervalMs) {
                            motionEffectiveAiIntervalMs = aiIntervalMs;
                            FLog.i(TAG, "Detection interval: " + aiIntervalMs + " ms ("
                                    + (userInterval > 0 ? "user setting" : "automatic")
                                    + ", measured inference " + motionLastInferenceDurationMs + " ms)");
                        }
                        // Only analyse frames that actually contain motion. Running the
                        // model on a static scene costs full GPU power for a result that
                        // cannot change, and on a weak GPU it occupies the device
                        // permanently (measured 1.5 s per inference on the Tecno).
                        boolean sceneActive = rawMotionScore > motionPolicy.stopThresholdFromSensitivity(
                                runtime != null ? runtime.getSensitivity()
                                        : (sharedPreferencesManager != null
                                            ? sharedPreferencesManager.getMotionSensitivity() : 80));
                        boolean aiDue = efficientDetDetector != null
                                && !aiInferenceInFlight.get()
                                && (sceneActive || recordingState == RecordingState.PAUSED)
                                && (now - motionLastAiDetectionMs) >= aiIntervalMs;
                        // The frame copy is needed by the detector and, separately, by
                        // forensic evidence capture. Evidence is decided later in the
                        // pipeline (it depends on the detections), so when forensics is
                        // armed the packet is captured up front; otherwise the once-a-
                        // second JPEG would have no pixels to crop from.
                        boolean forensicsArmed = sharedPreferencesManager != null
                                && sharedPreferencesManager.isDigitalForensicsEnabled()
                                && sharedPreferencesManager.isDfEvidenceCollectionEnabled();
                        com.fadcam.motion.domain.detector.AiObjectDetector.FramePacket framePacket = null;
                        if (aiDue || forensicsArmed) {
                            // The sensor delivers a sideways frame in portrait. The model
                            // was trained on upright photos, so hand it the rotation and
                            // let it sample the frame upright in one pass.
                            framePacket = com.fadcam.motion.domain.detector.AiObjectDetector
                                    .FramePacket.copyFrom(image, getUprightRotationDegrees(),
                                            isFrontCameraSelected());
                        }
                        image.close();
                        image = null;
                        // Run inference off this thread. The ImageReader callback holds
                        // a camera buffer until it returns; 100 ms of GPU work here
                        // backpressures the capture session and drops encoder frames.
                        if (aiDue && framePacket != null) {
                            submitAiInference(efficientDetDetector, framePacket);
                        }
                        processMotionFrame(rawMotionScore, framePacket, now);
        } catch (Throwable t) {
            FLog.w(TAG, "Motion analysis frame processing failed", t);
        } finally {
            try {
                image.close();
            } catch (Throwable ignored) {
            }
        }
    }

    /** Analyzer height the current ImageReader was built for. */
    private int appliedAnalysisResolution = -1;
    /** Set when the reader size changed and the camera session must be rebuilt. */
    private volatile boolean analysisSurfaceReconfigurePending = false;

    /**
     * Applies a changed analyzer resolution.
     *
     * <p>The analysis ImageReader is a camera output surface, so its size cannot be
     * changed in place: the reader has to be replaced and the capture session
     * rebuilt around it. That rebuild is requested here and performed on the camera
     * thread, so this method only detects the change and never touches the session.
     */
    private void applyAnalysisResolutionIfChanged() {
        com.fadcam.motion.domain.model.MotionRuntimeSettings runtime = motionRuntime;
        if (runtime == null) {
            return;
        }
        int requested = runtime.getAnalysisResolution();
        if (requested == appliedAnalysisResolution) {
            return;
        }
        appliedAnalysisResolution = requested;
        // Only meaningful once a reader exists (i.e. while a camera session is live).
        if (motionAnalysisReader == null) {
            return;
        }
        FLog.i(TAG, "Analyzer resolution changed to " + requested
                + "p — rebuilding analysis surface");
        analysisSurfaceReconfigurePending = true;
        if (backgroundHandler != null) {
            backgroundHandler.post(this::reconfigureAnalysisSurface);
        }
    }

    /**
     * Applies a changed analyzer resolution while a session is live.
     *
     * <p>Camera2 output surfaces are fixed for the lifetime of a capture session, so
     * a new size always means a new session — there is no partial reconfigure to
     * attempt and therefore no fallback path. The reader is rebuilt for the new size
     * and the session is recreated once. If the rebuild cannot start (a camera switch
     * is already running, or no camera device), the change is NOT applied silently:
     * the user is told the new resolution takes effect on the next recording.
     */
    private void reconfigureAnalysisSurface() {
        if (!analysisSurfaceReconfigurePending || isStopping) {
            return;
        }
        analysisSurfaceReconfigurePending = false;
        if (!motionLabEnabledForSession) {
            return;
        }
        FLog.i(TAG, "Recreating analysis reader for " + appliedAnalysisResolution + "p");
        releaseMotionAnalysisReader();
        recreateCameraSessionForAnalysisChange();
    }

    private void recreateCameraSessionForAnalysisChange() {
        if (isSwitchingCamera || cameraDevice == null) {
            // Do not fail silently: the setting is stored, it just cannot be applied
            // to the live session right now.
            FLog.w(TAG, "Analyzer resolution: session rebuild skipped (switching="
                    + isSwitchingCamera + ", camera=" + (cameraDevice != null) + ")");
            notifyAnalyzerResolutionDeferred();
            return;
        }
        isSwitchingCamera = true;
        cameraSwitchPreviousType = sharedPreferencesManager.getCameraSelection();
        cameraSwitchStartTimeNanos = System.nanoTime();
        // No catch-and-continue: openCamera() owns its own failure reporting (it
        // surfaces a recording error). Swallowing it here would leave the user with a
        // dead preview and no explanation.
        try {
            FLog.i(TAG, "Analyzer resolution: rebuilding camera session");
            closeCameraResourcesForSwitch();
            openCamera();
        } finally {
            isSwitchingCamera = false;
        }
    }

    /** Tells the user the analyzer change needs a fresh session. */
    private void notifyAnalyzerResolutionDeferred() {
        mainHandler.post(() -> {
            try {
                Utils.showQuickToast(getApplicationContext(),
                        "Analyzer resolution will apply on the next recording");
            } catch (Throwable ignored) {
            }
        });
    }

    /** Last pre-roll window pushed to the pipeline, so a change is applied once. */
    private int appliedPreRollSeconds = -1;

    /**
     * Pushes the current pre-roll setting into the pipeline. Called when the value
     * changes so a Motion Lab edit applies without restarting the session.
     */
    private void applyPreRollIfChanged() {
        if (glRecordingPipeline == null || motionRuntime == null) {
            return;
        }
        int seconds = motionRuntime.getPreRollSeconds();
        if (seconds == appliedPreRollSeconds) {
            return;
        }
        appliedPreRollSeconds = seconds;
        glRecordingPipeline.setPreRollSeconds(seconds);
        FLog.i(TAG, "Pre-roll applied live: " + seconds + "s");
    }

    /**
     * Runs one detection on the dedicated inference thread. Only one inference is
     * allowed in flight: a slow device must never build a queue of stale frames.
     */
    private void submitAiInference(
            @Nullable com.fadcam.motion.domain.detector.AiObjectDetector detector,
            @Nullable com.fadcam.motion.domain.detector.AiObjectDetector.FramePacket packet
    ) {
        if (detector == null || packet == null || aiInferenceExecutor == null) {
            return;
        }
        if (!aiInferenceInFlight.compareAndSet(false, true)) {
            return;
        }
        aiInferenceExecutor.execute(() -> {
            long inferenceStart = SystemClock.elapsedRealtime();
            try {
                java.util.List<com.fadcam.motion.domain.detector.AiObjectDetector.DetectionResult> results =
                        detector.detect(packet);
                motionLastDetections = results != null
                        ? results
                        : java.util.Collections.emptyList();
                motionLastAiDetectionMs = SystemClock.elapsedRealtime();
            } catch (Throwable t) {
                FLog.w(TAG, "Object inference failed; keeping previous detections", t);
            } finally {
                motionLastInferenceDurationMs = SystemClock.elapsedRealtime() - inferenceStart;
                aiInferenceInFlight.set(false);
            }
        });
    }

    private void processMotionFrame(
            float rawMotionScore,
            @Nullable com.fadcam.motion.domain.detector.AiObjectDetector.FramePacket framePacket,
            long nowMs
    ) {
        motionFramesAnalyzed++;
        applyPreRollIfChanged();
        // OpenCV MOG2 outputs are intentionally conservative; normalize to policy scale.
        if (motionOpenCvActive) {
            rawMotionScore = Math.min(1f, rawMotionScore * 1.35f);
        }
        if (Float.isNaN(motionScoreEma)) {
            motionScoreEma = rawMotionScore;
        } else {
            // Rise fast to avoid missed starts; decay slower to avoid threshold chatter.
            float riseAlpha = 0.72f;
            float fallAlpha = 0.34f;
            float alpha = rawMotionScore >= motionScoreEma ? riseAlpha : fallAlpha;
            motionScoreEma = (alpha * rawMotionScore) + ((1f - alpha) * motionScoreEma);
        }
        float motionScore = motionScoreEma;
        List<com.fadcam.motion.domain.detector.AiObjectDetector.DetectionResult> detections;
        if (efficientDetDetector != null) {
            // Detection runs on aiInferenceExecutor. This motion frame uses the most
            // recent completed result; a miss is at most one AI interval old.
            detections = motionLastDetections;
        } else {
            detections = java.util.Collections.emptyList();
        }
        com.fadcam.motion.domain.detector.AiObjectDetector.DetectionResult primaryDetection =
                efficientDetDetector != null ? efficientDetDetector.choosePrimary(detections) : null;
        float personConfidence = efficientDetDetector != null ? efficientDetDetector.bestPersonConfidence(detections) : 0f;
        boolean personDetectedRaw = efficientDetDetector != null && efficientDetDetector.hasPerson(detections);
        if (personDetectedRaw) {
            motionConsecutivePersonHits++;
        } else {
            motionConsecutivePersonHits = Math.max(0, motionConsecutivePersonHits - 1);
        }
        // Fixed confirmation depth: detection behaviour must not vary by device.
        int requiredHits = 2;
        boolean personDetected = personDetectedRaw && motionConsecutivePersonHits >= requiredHits;
        if (personDetectedRaw && personConfidence >= 0.62f) {
            motionPersonLikelyUntilMs = nowMs + 2500L;
        }
        // Keep person-confirmed signal stable across single-frame classifier misses.
        if (!personDetected && motionConsecutivePersonHits >= requiredHits && personConfidence >= 0.55f) {
            personDetected = true;
        }
        boolean personLikely = personDetected
                || (personDetectedRaw && personConfidence >= 0.70f)
                || nowMs <= motionPersonLikelyUntilMs;
        float debugChangedArea = 0f;
        float debugStrongArea = 0f;
        float debugMeanDelta = 0f;
        float debugBackgroundDelta = 0f;
        float debugMaxDelta = 0f;
        float debugCenterX = 0.5f;
        float debugCenterY = 0.5f;
        int debugBlobCount = 0;
        java.util.ArrayList<android.graphics.Rect> debugBlobRects = new java.util.ArrayList<>();
        float debugMinBlobRatio = 0f;
        int debugWorkingWidth = 0;
        int debugWorkingHeight = 0;
        boolean debugGlobalSuppressed = false;
        if (motionDetector instanceof com.fadcam.motion.domain.detector.MotionDebugInfoProvider) {
            com.fadcam.motion.domain.detector.MotionDebugInfoProvider detector =
                    (com.fadcam.motion.domain.detector.MotionDebugInfoProvider) motionDetector;
            debugChangedArea = detector.getLastChangedAreaRatio();
            debugStrongArea = detector.getLastStrongAreaRatio();
            debugMeanDelta = detector.getLastMeanDelta();
            debugBackgroundDelta = detector.getLastBackgroundDelta();
            debugMaxDelta = detector.getLastMaxDelta();
            debugCenterX = detector.getLastMotionCenterX();
            debugCenterY = detector.getLastMotionCenterY();
            debugBlobCount = detector.getLastBlobCount();
            debugBlobRects = new java.util.ArrayList<>(detector.getLastBlobRects());
            debugMinBlobRatio = detector.getMinBlobAreaRatio();
            debugWorkingWidth = detector.getWorkingWidth();
            debugWorkingHeight = detector.getWorkingHeight();
            debugGlobalSuppressed = detector.isLastGlobalMotionSuppressed();
        }
        com.fadcam.motion.domain.state.MotionSessionState stateBefore =
                motionStateMachine != null ? motionStateMachine.getState() : null;
        if (motionStateMachine != null && sharedPreferencesManager != null) {
            com.fadcam.motion.domain.model.MotionRuntimeSettings runtime = motionRuntime;
            com.fadcam.motion.domain.model.MotionSettings settings = new com.fadcam.motion.domain.model.MotionSettings(
                    runtime != null ? runtime.isEnabled() : sharedPreferencesManager.isMotionModeEnabled(),
                    com.fadcam.motion.domain.model.MotionTriggerMode.fromValue(
                            sharedPreferencesManager.getMotionTriggerMode()),
                    runtime != null ? runtime.getSensitivity() : sharedPreferencesManager.getMotionSensitivity(),
                    runtime != null ? runtime.getAnalysisFps() : sharedPreferencesManager.getMotionAnalysisFps(),
                    runtime != null ? runtime.getAnalysisResolution()
                            : sharedPreferencesManager.getMotionAnalysisResolution(),
                    runtime != null ? runtime.getDebounceMs() : sharedPreferencesManager.getMotionDebounceMs(),
                    runtime != null ? runtime.getPostRollMs() : sharedPreferencesManager.getMotionPostRollMs(),
                    runtime != null ? runtime.getPreRollSeconds() : sharedPreferencesManager.getMotionPreRollSeconds(),
                    runtime != null ? runtime.isAutoTorchEnabled() : sharedPreferencesManager.isMotionAutoTorchEnabled());
            float startThreshold = motionPolicy.startThresholdFromSensitivity(settings.getSensitivity());
            com.fadcam.motion.domain.state.MotionStateMachine.TransitionAction action =
                    motionStateMachine.onSignal(settings, new com.fadcam.motion.domain.model.MotionSignal(
                            nowMs,
                            motionScore,
                            personDetected));
            // Detection decision comes ONLY from the detector score and the state
            // machine above. The previous code contained four hand-tuned "assist
            // lanes" plus a confidence boost that fabricated a passing score from
            // hardcoded thresholds (changedArea/meanDelta/strongArea/personConf).
            // Those overrode the model, produced false triggers, and made results
            // impossible to reason about. Removed deliberately: the model decides.

            applyMotionTransitionAction(action);
            motionLastPersonDetected = personDetected;
            motionLastPersonConfidence = personConfidence;
            motionLastScore = motionScore;
            motionLastChangedArea = debugChangedArea;
            motionLastStrongArea = debugStrongArea;
            if (primaryDetection != null) {
                motionLastCenterX = primaryDetection.centerX;
                motionLastCenterY = primaryDetection.centerY;
                motionLastBoxWidth = primaryDetection.width;
                motionLastBoxHeight = primaryDetection.height;
                motionLastEventType = primaryDetection.coarseType;
                motionLastClassName = primaryDetection.className;
                motionLastDetectionConfidence = primaryDetection.confidence;
            } else {
                motionLastEventType = null;
                motionLastClassName = null;
                motionLastDetectionConfidence = 0f;
            }
            motionLastOverlayPayload = buildOverlayPayloadFromDetections(detections);
            motionLastGlobalSuppressed = debugGlobalSuppressed;
            if (action == com.fadcam.motion.domain.state.MotionStateMachine.TransitionAction.NONE
                    && motionScore > 0f
                    && stateBefore == com.fadcam.motion.domain.state.MotionSessionState.IDLE) {
                motionSuppressedSignalCount++;
            }
            if (stateBefore != motionStateMachine.getState() ||
                    action != com.fadcam.motion.domain.state.MotionStateMachine.TransitionAction.NONE) {
                FLog.d(TAG, "MotionLab: score=" + String.format(Locale.US, "%.3f", motionScore)
                        + ", raw=" + String.format(Locale.US, "%.3f", rawMotionScore)
                        + ", thresholds(start/stop)="
                        + String.format(Locale.US, "%.3f", startThreshold)
                        + "/"
                        + String.format(Locale.US, "%.3f", motionPolicy.stopThresholdFromSensitivity(settings.getSensitivity()))
                        + ", personRaw=" + personDetectedRaw
                        + ", personConf=" + String.format(Locale.US, "%.3f", personConfidence)
                        + ", area=" + String.format(Locale.US, "%.3f", debugChangedArea)
                        + ", strong=" + String.format(Locale.US, "%.3f", debugStrongArea)
                        + ", meanDelta=" + String.format(Locale.US, "%.3f", debugMeanDelta)
                        + ", bgDelta=" + String.format(Locale.US, "%.3f", debugBackgroundDelta)
                        + ", maxDelta=" + String.format(Locale.US, "%.3f", debugMaxDelta)
                        + ", globalSuppressed=" + debugGlobalSuppressed
                        + ", personHits=" + motionConsecutivePersonHits + "/" + requiredHits
                        + ", person=" + personDetected
                        + ", state=" + motionStateMachine.getState()
                        + ", action=" + action
                        + ", counters={frames=" + motionFramesAnalyzed
                        + ", actions=" + motionTriggerActionCount
                        + ", suppressed=" + motionSuppressedSignalCount + "}");
            }
            if ((nowMs - motionLastTelemetryLogMs) >= 10000L) {
                motionLastTelemetryLogMs = nowMs;
                FLog.d(TAG, "MotionLab Live: state=" + motionStateMachine.getState()
                        + ", action=" + action
                        + ", backend=" + (motionOpenCvActive ? "opencv_mog2" : "frame_diff")
                        + ", analysis=" + motionAnalysisWidth + "x" + motionAnalysisHeight
                        + ", raw=" + String.format(Locale.US, "%.3f", rawMotionScore)
                        + ", smoothed=" + String.format(Locale.US, "%.3f", motionScore)
                        + ", startThreshold=" + String.format(Locale.US, "%.3f", startThreshold)
                        + ", stopThreshold=" + String.format(Locale.US, "%.3f", motionPolicy.stopThresholdFromSensitivity(settings.getSensitivity()))
                        + ", person=" + personDetected
                        + ", personConf=" + String.format(Locale.US, "%.3f", personConfidence)
                        + ", motionCenter=" + String.format(Locale.US, "%.2f/%.2f", debugCenterX, debugCenterY)
                        + ", detCenter=" + (primaryDetection == null ? "-"
                            : String.format(Locale.US, "%.2f/%.2f",
                                primaryDetection.centerX, primaryDetection.centerY))
                        + ", expectUpright=" + describeUprightCentre(debugCenterX, debugCenterY)
                        + ", area=" + String.format(Locale.US, "%.3f", debugChangedArea)
                        + ", strong=" + String.format(Locale.US, "%.3f", debugStrongArea)
                        + ", meanDelta=" + String.format(Locale.US, "%.3f", debugMeanDelta)
                        + ", bgDelta=" + String.format(Locale.US, "%.3f", debugBackgroundDelta)
                        + ", maxDelta=" + String.format(Locale.US, "%.3f", debugMaxDelta)
                        + ", globalSuppressed=" + debugGlobalSuppressed);
            }
            boolean forensicsCaptureEnabled = sharedPreferencesManager != null
                    && sharedPreferencesManager.isDigitalForensicsEnabled()
                    && sharedPreferencesManager.isDfEvidenceCollectionEnabled();
            // A subject present now but not on the previous sample is a NEW appearance.
            // Capture it immediately so a fast walk-through is not missed between
            // heartbeats. Repeat captures are still bounded by the recorder's own
            // per-event floor, so this cannot flood the gallery.
            boolean subjectPresentNow = debugBlobCount > 0 || personDetected || personLikely;
            boolean newAppearance = subjectPresentNow && !forensicsSubjectPresentLastSample;
            forensicsSubjectPresentLastSample = subjectPresentNow;

            boolean shouldEmitForensicsSnapshot = digitalForensicsEventRecorder != null
                    && forensicsCaptureEnabled
                    && motionStateMachine.getState() == com.fadcam.motion.domain.state.MotionSessionState.RECORDING
                    && (newAppearance
                        || (nowMs - motionLastForensicsHeartbeatMs) >= FORENSICS_HEARTBEAT_INTERVAL_MS);
            // A JPEG is encoded only for forensic evidence. The Motion Lab monitor no
            // longer receives camera frames at all: it draws the onboarding animation,
            // so there is nothing to encode, broadcast, decode or composite.
            byte[] frameJpeg = null;
            if (shouldEmitForensicsSnapshot) {
                motionJpegAttemptCount++;
                long encodeStart = SystemClock.elapsedRealtime();
                frameJpeg = buildForensicsSnapshotJpeg(framePacket);
                motionJpegEncodeTotalMs += Math.max(0L, SystemClock.elapsedRealtime() - encodeStart);
                if (frameJpeg != null && frameJpeg.length > 0) {
                    motionJpegSuccessCount++;
                } else {
                    motionJpegSkipCount++;
                }
            }
            maybeBroadcastMotionDebug(rawMotionScore, motionScore, settings, motionStateMachine.getState(), action, personDetected, personConfidence, stateBefore, debugChangedArea, debugStrongArea, debugMeanDelta, debugBackgroundDelta, debugMaxDelta, debugGlobalSuppressed, debugCenterX, debugCenterY, debugBlobCount,
                    debugBlobRects, debugMinBlobRatio, debugWorkingWidth, debugWorkingHeight);

            if (shouldEmitForensicsSnapshot) {
                motionLastForensicsHeartbeatMs = nowMs;
                long timelineMs = getEffectiveTimelineMs();
                digitalForensicsEventRecorder.onDetections(
                        getCurrentRecordingMediaUri(),
                        timelineMs,
                        detections,
                        frameJpeg,
                        recordingSessionCameraSource == RecordingStoragePaths.CameraSource.FRONT,
                        getCurrentSensorOrientationDegrees(),
                        // The same angle the snapshot JPEG was rotated by.
                        framePacket != null ? framePacket.rotationDegrees : 0,
                        sharedPreferencesManager != null ? sharedPreferencesManager.getVideoOrientation() : "portrait",
                        shouldMirrorForensicsSnapshots()
                );
                if ((nowMs - motionLastTelemetryLogMs) >= 10000L) {
                    FLog.i(TAG, "Forensics heartbeat: detections=" + detections.size()
                            + ", personConf=" + String.format(Locale.US, "%.2f", personConfidence)
                            + ", frame=" + (framePacket != null ? framePacket.width + "x" + framePacket.height : "none")
                            + ", front=" + (recordingSessionCameraSource == RecordingStoragePaths.CameraSource.FRONT));
                }
            }
            maybeLogForensicsPerf(nowMs);
        }
    }

    private void applyMotionTransitionAction(com.fadcam.motion.domain.state.MotionStateMachine.TransitionAction action) {
        if (!motionLabEnabledForSession || action == null || action == com.fadcam.motion.domain.state.MotionStateMachine.TransitionAction.NONE) {
            return;
        }
        // Run control actions on main thread to stay consistent with service state updates.
        mainHandler.post(() -> {
            if (!motionLabEnabledForSession) {
                return;
            }
            if (action == com.fadcam.motion.domain.state.MotionStateMachine.TransitionAction.START_RECORDING) {
                reportForensicsMotionTransition(action);
                // Auto-resume on ANY pause (manual or auto) in motion recording mode.
                // The motionAutoPaused flag was removed from this guard because it
                // caused a permanent deadlock: if the user manually paused, motionAutoPaused
                // stayed false and motion detection could NEVER auto-resume again.
                if (recordingState == RecordingState.PAUSED) {
                    FLog.i(TAG, "MotionLab action START_RECORDING -> resuming recording");
                    com.fadcam.FeatureRegistry.streaming().setMotionState("triggered");
                    motionTriggerActionCount++;
                    motionAutoPaused = false;
                    Intent resumeIntent = new Intent(getApplicationContext(), RecordingService.class);
                    resumeIntent.setAction(Constants.INTENT_ACTION_RESUME_RECORDING);
                    ServiceStartPolicy.startRecordingAction(getApplicationContext(), resumeIntent);
                    com.fadcam.motion.domain.model.MotionRuntimeSettings rt = motionRuntime;
                    boolean autoTorch = rt != null ? rt.isAutoTorchEnabled()
                            : (sharedPreferencesManager != null
                                && sharedPreferencesManager.isMotionAutoTorchEnabled());
                    if (autoTorch) {
                        setMotionTorchState(true);
                    }
                }
                return;
            }
            if (action == com.fadcam.motion.domain.state.MotionStateMachine.TransitionAction.STOP_RECORDING) {
                reportForensicsMotionTransition(action);
                if (recordingState == RecordingState.IN_PROGRESS) {
                    FLog.i(TAG, "MotionLab action STOP_RECORDING -> pausing recording");
                    com.fadcam.FeatureRegistry.streaming().setMotionState("armed");
                    motionTriggerActionCount++;
                    motionAutoPaused = true;
                    Intent pauseIntent = new Intent(getApplicationContext(), RecordingService.class);
                    pauseIntent.setAction(Constants.INTENT_ACTION_PAUSE_RECORDING);
                    ServiceStartPolicy.startRecordingAction(getApplicationContext(), pauseIntent);
                    com.fadcam.motion.domain.model.MotionRuntimeSettings rt2 = motionRuntime;
                    boolean autoTorch2 = rt2 != null ? rt2.isAutoTorchEnabled()
                            : (sharedPreferencesManager != null
                                && sharedPreferencesManager.isMotionAutoTorchEnabled());
                    if (autoTorch2) {
                        setMotionTorchState(false);
                    }
                }
            }
        });
    }

    private void reportForensicsMotionTransition(com.fadcam.motion.domain.state.MotionStateMachine.TransitionAction action) {
        if (digitalForensicsEventRecorder == null || action == null) {
            return;
        }
        String activeMediaUri = getCurrentRecordingMediaUri();
        long timelineMs = getEffectiveTimelineMs();

        if (action == com.fadcam.motion.domain.state.MotionStateMachine.TransitionAction.START_RECORDING) {
            // Real-time recorder updates are now driven by per-frame detection heartbeats.
            return;
        } else if (action == com.fadcam.motion.domain.state.MotionStateMachine.TransitionAction.STOP_RECORDING) {
            digitalForensicsEventRecorder.onMotionStop(timelineMs);
        }
    }

    private String getCurrentRecordingMediaUri() {
        if (currentSegmentUriString != null && !currentSegmentUriString.isEmpty()) {
            return currentSegmentUriString;
        }
        if (currentSegmentPath != null && !currentSegmentPath.isEmpty()) {
            if (currentSegmentPath.startsWith("content://")) {
                return currentSegmentPath;
            }
            return Uri.fromFile(new File(currentSegmentPath)).toString();
        }
        if (currentSegmentFile != null) {
            return Uri.fromFile(currentSegmentFile).toString();
        }
        return null;
    }

    @Nullable
    private String buildForensicsOverlayPayload(@NonNull String watermarkText) {
        if (sharedPreferencesManager == null || !sharedPreferencesManager.isDfOverlayEnabled()) {
            return null;
        }
        if (!motionLabEnabledForSession) {
            return null;
        }
        String payload = motionLastOverlayPayload;
        if (payload == null || payload.isEmpty()) {
            return null;
        }

        // Only sanitize the separator token; preserve newlines for proper watermark layout
        watermarkText = watermarkText == null ? "" : watermarkText.replace("||wm||", " ");

        long nowLog = SystemClock.elapsedRealtime();
        if (nowLog - lastOverlayPayloadLogMs >= 5000L) {
            lastOverlayPayloadLogMs = nowLog;
            FLog.i(TAG, "DF overlay payload: boxes=" + (payload == null ? 0 : payload.split(";").length)
                    + " sample=" + (payload == null ? "-" : payload.substring(0, Math.min(120, payload.length()))));
        }

        return "__DF_OVERLAY__:" + payload
                + "||wm||" + watermarkText;
    }

    @Nullable
    private String buildOverlayPayloadFromDetections(
            @Nullable List<com.fadcam.motion.domain.detector.AiObjectDetector.DetectionResult> detections
    ) {
        if (detections == null || detections.isEmpty()) {
            return null;
        }
        StringBuilder out = new StringBuilder();
        for (com.fadcam.motion.domain.detector.AiObjectDetector.DetectionResult detection : detections) {
            if (detection == null || detection.confidence < 0.45f) {
                continue;
            }
            if (out.length() > 0) {
                out.append(';');
            }
            String label = detection.className == null ? "object" : detection.className.trim().toLowerCase(Locale.US);
            out.append(label).append('|')
                    .append(String.format(Locale.US, "%.4f", Math.max(0f, Math.min(1f, detection.confidence)))).append('|')
                    .append(String.format(Locale.US, "%.4f", clamp01(detection.centerX))).append('|')
                    .append(String.format(Locale.US, "%.4f", clamp01(detection.centerY))).append('|')
                    .append(String.format(Locale.US, "%.4f", clampBox(detection.width))).append('|')
                    .append(String.format(Locale.US, "%.4f", clampBox(detection.height))).append('|')
                    .append(detection.coarseType == null ? "OBJECT" : detection.coarseType.toUpperCase(Locale.US));
        }
        return out.length() == 0 ? null : out.toString();
    }

    /**
     * Single-camera provider: the shared {@link com.fadcam.watermark.WatermarkManager} owns the
     * text (identical to dual camera and FadRec), and the digital-forensics overlay is wrapped
     * around it when active.
     */
    private void ensureWatermarkInfoProvider() {
        if (watermarkInfoProvider != null) return;
        ensureWatermarkManager();
        watermarkInfoProvider = () -> {
            com.fadcam.watermark.WatermarkManager manager = watermarkManager;
            if (manager == null) return "";
            String watermarkText = manager.getWatermarkText();
            String dfOverlayPayload = buildForensicsOverlayPayload(watermarkText);
            return dfOverlayPayload != null ? dfOverlayPayload : watermarkText;
        };
    }

    /** Creates the shared manager once; later calls only adopt a refreshed LocationHelper. */
    private void ensureWatermarkManager() {
        if (watermarkManager == null) {
            watermarkManager = new com.fadcam.watermark.WatermarkManager(this, sharedPreferencesManager);
        }
        watermarkManager.initialize(locationHelper);
    }

    /**
     * Forward-rotates a sensor-space centre into upright content space, so the
     * detector's reported centre can be compared with the motion blob's centre.
     * Used only for the diagnostic line; it is the same transform the detector
     * applies to the frame.
     */
    private String describeUprightCentre(float sensorX, float sensorY) {
        int rotation = getUprightRotationDegrees();
        float cx;
        float cy;
        switch (rotation) {
            case 90:
                cx = 1f - sensorY;
                cy = sensorX;
                break;
            case 180:
                cx = 1f - sensorX;
                cy = 1f - sensorY;
                break;
            case 270:
                cx = sensorY;
                cy = 1f - sensorX;
                break;
            default:
                cx = sensorX;
                cy = sensorY;
                break;
        }
        return String.format(Locale.US, "%.2f/%.2f", cx, cy);
    }

    private float clamp01(float value) {
        return Math.max(0f, Math.min(1f, value));
    }

    private float clampBox(float value) {
        return Math.max(0.02f, Math.min(0.90f, value));
    }

    private void setMotionTorchState(boolean shouldBeOn) {
        if (recordingState != RecordingState.IN_PROGRESS && recordingState != RecordingState.PAUSED) {
            return;
        }
        if (isRecordingTorchEnabled == shouldBeOn) {
            return;
        }
        FLog.d(TAG, "MotionLab torch auto-control: target=" + shouldBeOn + ", current=" + isRecordingTorchEnabled);
        toggleRecordingTorch();
    }

    private void maybeBroadcastMotionDebug(
            float rawScore,
            float smoothedScore,
            com.fadcam.motion.domain.model.MotionSettings settings,
            com.fadcam.motion.domain.state.MotionSessionState currentState,
            com.fadcam.motion.domain.state.MotionStateMachine.TransitionAction action,
            boolean personDetected,
            float personConfidence,
            com.fadcam.motion.domain.state.MotionSessionState previousState,
            float changedAreaRatio,
            float strongAreaRatio,
            float meanDelta,
            float backgroundDelta,
            float maxDelta,
            boolean globalSuppressed,
            float centerX,
            float centerY,
            int blobCount,
            @Nullable java.util.ArrayList<android.graphics.Rect> blobRects,
            float minBlobRatio,
            int workingWidth,
            int workingHeight
    ) {
        long now = SystemClock.elapsedRealtime();
        boolean significant = previousState != currentState
                || (action != null && action != com.fadcam.motion.domain.state.MotionStateMachine.TransitionAction.NONE);
        boolean monitorOpen = motionRuntime != null && motionRuntime.isDebugUiActive();
        if (!monitorOpen && !significant && (now - motionLastDebugBroadcastMs) < 900L) {
            return;
        }
        if (monitorOpen && !significant && (now - motionLastDebugBroadcastMs) < 200L) {
            return;
        }
        motionLastDebugBroadcastMs = now;

        Intent debugIntent = new Intent(Constants.BROADCAST_MOTION_LAB_DEBUG);
        debugIntent.putExtra(Constants.EXTRA_MOTION_DEBUG_RAW_SCORE, rawScore);
        debugIntent.putExtra(Constants.EXTRA_MOTION_DEBUG_SCORE, smoothedScore);
        debugIntent.putExtra(
                Constants.EXTRA_MOTION_DEBUG_START_THRESHOLD,
                motionPolicy.startThresholdFromSensitivity(settings.getSensitivity())
        );
        debugIntent.putExtra(
                Constants.EXTRA_MOTION_DEBUG_STOP_THRESHOLD,
                motionPolicy.stopThresholdFromSensitivity(settings.getSensitivity())
        );
        debugIntent.putExtra(
                Constants.EXTRA_MOTION_DEBUG_STATE,
                currentState == null ? "UNKNOWN" : currentState.name()
        );
        debugIntent.putExtra(
                Constants.EXTRA_MOTION_DEBUG_ACTION,
                action == null ? "NONE" : action.name()
        );
        debugIntent.putExtra(Constants.EXTRA_MOTION_DEBUG_PERSON, personDetected);
        debugIntent.putExtra(Constants.EXTRA_MOTION_DEBUG_PERSON_CONF, personConfidence);
        debugIntent.putExtra(
                Constants.EXTRA_MOTION_DEBUG_CLASS_NAME,
                motionLastClassName == null ? "" : motionLastClassName
        );
        debugIntent.putExtra(Constants.EXTRA_MOTION_DEBUG_CLASS_CONF, motionLastDetectionConfidence);
        debugIntent.putExtra(
                Constants.EXTRA_MOTION_DEBUG_EVENT_TYPE,
                motionLastEventType == null ? "" : motionLastEventType
        );
        debugIntent.putExtra(Constants.EXTRA_MOTION_DEBUG_CHANGED_AREA, changedAreaRatio);
        debugIntent.putExtra(Constants.EXTRA_MOTION_DEBUG_STRONG_AREA, strongAreaRatio);
        debugIntent.putExtra(Constants.EXTRA_MOTION_DEBUG_MEAN_DELTA, meanDelta);
        debugIntent.putExtra(Constants.EXTRA_MOTION_DEBUG_BG_DELTA, backgroundDelta);
        debugIntent.putExtra(Constants.EXTRA_MOTION_DEBUG_MAX_DELTA, maxDelta);
        debugIntent.putExtra(Constants.EXTRA_MOTION_DEBUG_GLOBAL_SUPPRESSED, globalSuppressed);
        debugIntent.putExtra(Constants.EXTRA_MOTION_DEBUG_ANALYSIS_WIDTH, motionAnalysisWidth);
        debugIntent.putExtra(Constants.EXTRA_MOTION_DEBUG_ANALYSIS_HEIGHT, motionAnalysisHeight);
        debugIntent.putExtra(Constants.EXTRA_MOTION_DEBUG_BACKEND,
                motionOpenCvActive ? "MOG2" : "FRAME_DIFF");
        debugIntent.putExtra(Constants.EXTRA_MOTION_DEBUG_FPS,
                motionAnalysisIntervalMs > 0 ? (1000f / motionAnalysisIntervalMs) : 0f);
        debugIntent.putExtra(Constants.EXTRA_MOTION_DEBUG_BLOB_COUNT, blobCount);
        debugIntent.putExtra(Constants.EXTRA_MOTION_DEBUG_CENTER_X, centerX);
        debugIntent.putExtra(Constants.EXTRA_MOTION_DEBUG_CENTER_Y, centerY);
        if (blobRects != null) {
            // Flat int array: left, top, right, bottom per blob.
        }
        debugIntent.putExtra(Constants.EXTRA_MOTION_DEBUG_MIN_BLOB_RATIO, minBlobRatio);
        debugIntent.setPackage(getPackageName());
        sendBroadcast(debugIntent);
    }

    @Nullable
    private byte[] buildForensicsSnapshotJpeg(
            @Nullable com.fadcam.motion.domain.detector.AiObjectDetector.FramePacket framePacket
    ) {
        if (framePacket == null) {
            return null;
        }
        try {
            int width = framePacket.width;
            int height = framePacket.height;
            if (width <= 0 || height <= 0) {
                return null;
            }
            // The analysis reader buffer is in sensor orientation, but detection
            // boxes are reported upright. Rotate the snapshot so the crop taken
            // downstream uses the same orientation as the box it is cropping to.
            // Without this the evidence snapshot cropped the wrong region.
            int rotation = framePacket.rotationDegrees;
            byte[] nv21 = framePacketToNv21(framePacket);
            if (nv21 == null || nv21.length == 0) {
                return null;
            }
            // Encode the sensor-orientation frame, then rotate the decoded bitmap with
            // the platform transform. Rotating the YUV bytes by hand meant a
            // full-frame pixel loop for every snapshot on the capture path, which is
            // pure CPU for something the graphics layer already does natively.
            android.graphics.YuvImage yuvImage = new android.graphics.YuvImage(
                    nv21,
                    android.graphics.ImageFormat.NV21,
                    framePacket.width,
                    framePacket.height,
                    null
            );
            // Encode at a bounded size. The evidence crop is a small region of this
            // frame, so a full-resolution encode wastes 150 ms of CPU per snapshot
            // for detail the crop throws away. 720p keeps the object recognisable
            // while cutting that cost by roughly 3x on a 1080p analysis stream.
            int encodeW = framePacket.width;
            int encodeH = framePacket.height;
            int maxEdge = FORENSICS_SNAPSHOT_MAX_EDGE;
            if (Math.max(encodeW, encodeH) > maxEdge) {
                float scale = maxEdge / (float) Math.max(encodeW, encodeH);
                encodeW = Math.max(2, Math.round(encodeW * scale));
                encodeH = Math.max(2, Math.round(encodeH * scale));
                encodeW &= ~1;
                encodeH &= ~1;
            }
            ByteArrayOutputStream rawOut = new ByteArrayOutputStream();
            if (!yuvImage.compressToJpeg(new Rect(0, 0, encodeW, encodeH), 82, rawOut)) {
                return null;
            }
            byte[] rawJpeg = rawOut.toByteArray();
            if (rotation == 0) {
                return rawJpeg;
            }
            android.graphics.Bitmap decoded = android.graphics.BitmapFactory
                    .decodeByteArray(rawJpeg, 0, rawJpeg.length);
            if (decoded == null) {
                return rawJpeg;
            }
            android.graphics.Matrix matrix = new android.graphics.Matrix();
            matrix.postRotate(rotation);
            // The front camera's analysis buffer runs bottom-to-top relative to the
            // content orientation. That is a vertical flip of the image itself, not a
            // drawing detail: the evidence JPEG has to carry it too, otherwise saved
            // front-camera snapshots are upside down while the preview is correct.
            if (framePacket.frontFacing) {
                matrix.postScale(1f, -1f, framePacket.width / 2f, framePacket.height / 2f);
            }
            android.graphics.Bitmap upright = android.graphics.Bitmap.createBitmap(
                    decoded, 0, 0, decoded.getWidth(), decoded.getHeight(), matrix, true);
            if (upright != decoded) {
                decoded.recycle();
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            upright.compress(android.graphics.Bitmap.CompressFormat.JPEG, 84, out);
            upright.recycle();
            return out.toByteArray();
        } catch (IllegalStateException ignored) {
            return null;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Pushes the current sensor angle to the GL pipeline so the renderer's rotations
     * (including the detection overlay mapping) match the camera actually in use.
     */
    private void pushSensorOrientationToRenderer() {
        if (glRecordingPipeline == null) {
            return;
        }
        try {
            glRecordingPipeline.setSensorOrientation(getCurrentSensorOrientationDegrees());
            // Push the live lens facing as well: the overlay decides front/back from
            // this, and the saved preference lags behind a live camera switch.
            Integer facing = currentCameraCharacteristics == null ? null
                    : currentCameraCharacteristics.get(CameraCharacteristics.LENS_FACING);
            glRecordingPipeline.setLiveLensFacing(facing);
        } catch (Throwable t) {
            FLog.w(TAG, "Failed to push sensor orientation to renderer", t);
        }
    }

    private int getCurrentSensorOrientationDegrees() {
        if (currentCameraCharacteristics == null) {
            return 90;
        }
        Integer so = currentCameraCharacteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
        return so == null ? 90 : so;
    }

    /**
     * Rotation (0/90/180/270) that maps a display-space point/vector into the
     * sensor's coordinate space, accounting for the sensor's mounting rotation,
     * the current device rotation, and the front-camera flip. Used to translate
     * touch input (pan, tap-to-focus) into SCALER_CROP_REGION / metering region
     * coordinates.
     */
    /**
     * Clockwise rotation that turns the camera's sensor frame upright.
     *
     * <p>This mirrors {@code GLWatermarkRenderer#getRequiredRotation()} exactly: the
     * encoder and preview rotate the sensor image by
     * {@code sensorOrientation - displayRotation} (flipped for the front camera), so
     * the detector must be handed the same angle. Without it the model analyses a
     * sideways scene and mislabels objects — a face in the corner came back as
     * "skateboard" on a portrait phone.
     */
    /**
     * THE rotation of the app. Turns the sensor buffer into the upright orientation
     * used by the recorded video, the preview, the detector and evidence snapshots.
     *
     * <p><b>This must equal GLWatermarkRenderer#getRequiredRotation exactly.</b> That
     * is the angle the camera texture is rotated by to make the preview upright, so it
     * is the orientation of the pixels on screen. If the detector analyses a different
     * rotation, its boxes are wrong by the difference — and for the Pixel front camera
     * the difference is exactly 180 degrees, which put a subject at the bottom of the
     * frame at the top of the screen.
     *
     * <p>The front-camera inversion below is part of that transform. It looks like it
     * should not be there for the sensor data, but it is what the renderer does, and
     * the two must match.
     */
    private int getUprightRotationDegrees() {
        int sensor = getCurrentSensorOrientationDegrees();
        int display = getDisplayRotationDegrees();
        int rotation = ((sensor - display) % 360 + 360) % 360;
        if (currentCameraCharacteristics != null) {
            Integer facing = currentCameraCharacteristics.get(CameraCharacteristics.LENS_FACING);
            if (facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT) {
                rotation = (360 - rotation) % 360;
            }
        }
        long now = SystemClock.elapsedRealtime();
        if (now - lastUprightRotationLogMs >= 10000L) {
            lastUprightRotationLogMs = now;
            FLog.i(TAG, "Upright rotation: " + rotation + " (sensor=" + sensor
                    + ", display=" + display
                    + ", front=" + (currentCameraCharacteristics != null
                        && CameraCharacteristics.LENS_FACING_FRONT ==
                           currentCameraCharacteristics.get(CameraCharacteristics.LENS_FACING))
                    + ")");
        }
        return rotation;
    }

    private int getDisplayToSensorRotation() {
        int displayRotationDeg = getDisplayRotationDegrees();
        // Matches GLWatermarkRenderer#getRequiredRotation(): the preview rotates the
        // sensor image by (sensorOrientation - displayRotation) to look upright, so the
        // inverse mapping used here must use the same signed difference. Using a sum
        // flips the mapping by 180° when the device is held in landscape.
        int total = ((getCurrentSensorOrientationDegrees() - displayRotationDeg) % 360 + 360) % 360;
        if (currentCameraCharacteristics != null) {
            Integer facing = currentCameraCharacteristics.get(
                    CameraCharacteristics.LENS_FACING);
            if (facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT) {
                total = (360 - total) % 360;
            }
        }
        return total % 360;
    }

    /**
     * Rotation of the display the preview is shown on, in degrees.
     *
     * <p>{@link Context#getDisplay()} cannot be used here: a Service is not associated with a
     * display, so the call throws {@link UnsupportedOperationException} (and it does not exist
     * below API 30). The exception used to be swallowed, silently reporting 0° for every
     * device, which transposed pan/tap coordinates by 90° on portrait-mounted sensors.
     */
    /**
     * True when the camera currently delivering frames is the front one.
     *
     * <p>Read from the live camera characteristics rather than
     * {@code recordingSessionCameraSource}: that field is only assigned when a
     * recording starts, so after a live camera switch it still described the previous
     * camera. Evidence snapshots taken across a switch then applied the front-camera
     * flip to back-camera frames and came out upside down — which is exactly the
     * mixed-orientation gallery that was reported.
     */
    private boolean isFrontCameraSelected() {
        if (currentCameraCharacteristics != null) {
            try {
                Integer facing = currentCameraCharacteristics.get(
                        CameraCharacteristics.LENS_FACING);
                if (facing != null) {
                    return facing == CameraCharacteristics.LENS_FACING_FRONT;
                }
            } catch (Throwable ignored) {
            }
        }
        return recordingSessionCameraSource == RecordingStoragePaths.CameraSource.FRONT;
    }

    private int getDisplayRotationDegrees() {
        try {
            android.hardware.display.DisplayManager displayManager =
                    (android.hardware.display.DisplayManager) getSystemService(DISPLAY_SERVICE);
            if (displayManager != null) {
                android.view.Display display =
                        displayManager.getDisplay(android.view.Display.DEFAULT_DISPLAY);
                if (display != null) {
                    return 90 * display.getRotation();
                }
            }
        } catch (Exception e) {
            FLog.w(TAG, "getDisplayRotationDegrees failed: " + e.getMessage());
        }
        return 0;
    }

    private boolean shouldMirrorForensicsSnapshots() {
        // Keep snapshots aligned with recorded output, not selfie-preview mirror.
        return false;
    }

    private void maybeLogForensicsPerf(long nowMs) {
        if ((nowMs - motionLastPerfLogMs) < 10000L) {
            return;
        }
        motionLastPerfLogMs = nowMs;
        long avgEncodeMs = motionJpegSuccessCount <= 0
                ? 0L
                : (motionJpegEncodeTotalMs / Math.max(1L, motionJpegSuccessCount));
        FLog.i(TAG, "ForensicsPerf: frames=" + motionFramesAnalyzed
                + ", jpegAttempt=" + motionJpegAttemptCount
                + ", jpegOk=" + motionJpegSuccessCount
                + ", jpegSkip=" + motionJpegSkipCount
                + ", avgEncodeMs=" + avgEncodeMs
                + ", actions=" + motionTriggerActionCount
                + ", suppressed=" + motionSuppressedSignalCount);
    }

    @Nullable
    /**
     * Rotates an NV21 buffer clockwise into the upright orientation the detector and
     * the overlay work in, so evidence crops line up with the boxes.
     */
    private byte[] framePacketToNv21(
            @NonNull com.fadcam.motion.domain.detector.AiObjectDetector.FramePacket framePacket
    ) {
        int width = framePacket.width;
        int height = framePacket.height;
        if (width <= 0 || height <= 0) {
            return null;
        }
        int ySize = width * height;
        int uvSize = ySize / 2;
        byte[] out = new byte[ySize + uvSize];

        // Y plane
        for (int row = 0; row < height; row++) {
            int srcRow = row * framePacket.yRowStride;
            int dstRow = row * width;
            for (int col = 0; col < width; col++) {
                int srcIndex = srcRow + (col * framePacket.yPixelStride);
                out[dstRow + col] = framePacket.y[srcIndex];
            }
        }

        int uvHeight = height / 2;
        int uvWidth = width / 2;
        int dst = ySize;
        for (int row = 0; row < uvHeight; row++) {
            int uvRow = row * framePacket.uvRowStride;
            for (int col = 0; col < uvWidth; col++) {
                int uvIndex = uvRow + (col * framePacket.uvPixelStride);
                byte v = framePacket.v[uvIndex];
                byte u = framePacket.u[uvIndex];
                out[dst++] = v;
                out[dst++] = u;
            }
        }
        return out;
    }

    private void releaseMotionAnalysisReader() {
        discardPendingAnalysisImage();
        if (motionAnalysisReader != null) {
            try {
                motionAnalysisReader.setOnImageAvailableListener(null, null);
                motionAnalysisReader.close();
            } catch (Exception e) {
                FLog.w(TAG, "Failed releasing motion analysis reader", e);
            } finally {
                motionAnalysisReader = null;
            }
        }
    }

    /**
     * Checks if the service has any active work (recording, pausing, or processing)
     * and calls stopSelf() if it is completely idle.
     * This should be called whenever a task completes (recording stops, processing
     * finishes).
     */
    private void checkIfServiceCanStop() {
        // Read volatile flag and check state atomically as best as possible

        // log removed

        // Use the new shouldServiceStayAlive method to determine if service should
        // continue running
        if (!shouldServiceStayAlive()) {
            FLog.i(TAG, "No active recording or background processing detected. Stopping service.");
            // Remove from foreground first to avoid ANR if stopSelf takes time
            com.fadcam.Utils.stopForegroundCompat(this, true);
            stopSelf(); // Stop the service as its work is done.
        } else {
            FLog.d(TAG, "Service continues running (Tasks active).");
        }

    }

    private void openCamera() {
        if (cameraManager == null) {
            FLog.e(TAG, "openCamera: CameraManager (class field) is null.");
            if (recordingState == RecordingState.STARTING)
                recordingState = RecordingState.NONE;
            stopSelf();
            return;
        }
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            FLog.e(TAG, "openCamera: Camera permission denied.");
            if (recordingState == RecordingState.STARTING)
                recordingState = RecordingState.NONE;
            Toast.makeText(this, getString(R.string.toast_camera_permission_denied), Toast.LENGTH_LONG).show();
            stopSelf();
            return;
        }

        CameraType selectedType = sharedPreferencesManager.getCameraSelection();

        // DUAL_PIP is handled by DualCameraRecordingService — guard against accidental use here.
        if (selectedType != null && selectedType.isDual()) {
            FLog.w(TAG, "openCamera: DUAL_PIP reached RecordingService — falling back to BACK");
            selectedType = CameraType.BACK;
        }

        String cameraToOpenId = null;

        try {
            String[] basicCameraIds = cameraManager.getCameraIdList();
            
            // If no cameras found, cameraserver might be restarting - try once more
            if (basicCameraIds.length == 0) {
                FLog.w(TAG, "No cameras found on first attempt, waiting 1s and retrying...");
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                basicCameraIds = cameraManager.getCameraIdList();
                if (basicCameraIds.length == 0) {
                    FLog.e(TAG, "Still no cameras after retry. CameraServer may be crashed or unavailable.");
                    Toast.makeText(this, getString(R.string.toast_camera_not_responding), Toast.LENGTH_LONG).show();
                    if (recordingState == RecordingState.STARTING)
                        recordingState = RecordingState.NONE;
                    stopSelf();
                    return;
                }
            }
            
            Set<String> allAvailableCameraIds = new HashSet<>(Arrays.asList(basicCameraIds));

            // On Android P+, also include physical cameras from logical cameras
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                for (String id : basicCameraIds) {
                    try {
                        CameraCharacteristics chars = cameraManager.getCameraCharacteristics(id);
                        Set<String> physicalIds = chars.getPhysicalCameraIds();
                        if (physicalIds != null && !physicalIds.isEmpty()) {
                            allAvailableCameraIds.addAll(physicalIds);
                            // physical camera log removed  " + id + ": " + physicalIds);
                        }
                    } catch (Exception e) {
                        FLog.w(TAG, "Error checking physical IDs for camera " + id, e);
                    }
                }
            }

            String[] availableCameraIds = allAvailableCameraIds.toArray(new String[0]);

            if (selectedType == CameraType.FRONT) {
                for (String id : availableCameraIds) {
                    CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(id);
                    Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
                    if (facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT) {
                        cameraToOpenId = id;
                        break;
                    }
                }
            } else { // CameraType.BACK
                String preferredBackId = sharedPreferencesManager.getSelectedBackCameraId();
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
                                break;
                            } else {
                                // For physical cameras, check if this ID was part of a logical back camera
                                // If it's in our availableCameraIds, it means it was detected as a back camera
                                FLog.w(TAG, "Preferred back ID " + preferredBackId + " exists but LENS_FACING is: "
                                        + facing);

                                // Additional validation: check if this is a physical camera from a logical back
                                // camera
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                                    boolean isPhysicalBackCamera = isPhysicalBackCamera(preferredBackId, basicCameraIds,
                                            cameraManager);
                                    if (isPhysicalBackCamera) {
                                        isValidAndAvailable = true;
                                        FLog.d(TAG, "Preferred camera ID '" + preferredBackId
                                                + "' validated as physical back camera");
                                        break;
                                    }
                                }
                            }
                        } catch (CameraAccessException e) {
                            FLog.e(TAG, "Error getting characteristics for preferred camera " + preferredBackId, e);
                        }
                    }
                }

                if (isValidAndAvailable) {
                    cameraToOpenId = preferredBackId;
                } else {
                    FLog.w(TAG,
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
                        FLog.e(TAG, "Critical: Default back camera ID '" + cameraToOpenId
                                + "' not found or not back-facing! Cannot select default back camera.");
                        String fallbackBackId = null;
                        for (String id : availableCameraIds) {
                            CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(id);
                            Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
                            if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) {
                                FLog.w(TAG, "Using first available back camera as final fallback: " + id);
                                fallbackBackId = id;
                                break;
                            }
                        }
                        if (fallbackBackId != null) {
                            cameraToOpenId = fallbackBackId;
                        } else {
                            cameraToOpenId = null;
                            FLog.e(TAG, "Could not find any back-facing camera.");
                        }
                    }
                }
            }

            if (cameraToOpenId == null) {
                FLog.e(TAG, "Could not determine a valid camera ID to open for selected type: " + selectedType);
                Toast.makeText(this, getString(R.string.toast_camera_not_found), Toast.LENGTH_LONG).show();
                if (recordingState == RecordingState.STARTING)
                    recordingState = RecordingState.NONE;
                stopSelf();
                return;
            }

            if (cameraDevice != null) {
                FLog.w(TAG, "openCamera: Closing existing cameraDevice instance first.");
                try {
                    cameraDevice.close();
                } catch (Exception e) {
                    FLog.e(TAG, "Error explicitly closing existing cameraDevice prior to opening new one", e);
                }
                cameraDevice = null;
            }

            final int MAX_RETRIES = 3;
            final long RETRY_DELAY_MS = 2000;
            int attempt = 0;
            String finalCameraToOpenId = cameraToOpenId;

            while (attempt < MAX_RETRIES) {
                try {
                    cameraManager.openCamera(finalCameraToOpenId, cameraStateCallback, backgroundHandler);
                    return;
                } catch (CameraAccessException e) {
                    FLog.e(TAG, "openCamera: Camera Access Exception on attempt " + (attempt + 1) + " for ID "
                            + finalCameraToOpenId, e);
                    // Use direct integer values for error codes for broader compatibility
                    int reason = e.getReason();
                    if (reason == 1 /* ERROR_CAMERA_DISABLED */ ||
                            reason == 4 /* ERROR_CAMERA_IN_USE */) {
                        attempt++;
                        if (attempt < MAX_RETRIES) {
                            FLog.w(TAG, "Camera disabled (1) or in use (4), reason: " + reason + ". Retrying in "
                                    + RETRY_DELAY_MS + "ms... (" + attempt + "/" + MAX_RETRIES + ")");
                            try {
                                Thread.sleep(RETRY_DELAY_MS);
                            } catch (InterruptedException ie) {
                                FLog.w(TAG, "Camera open retry delay interrupted", ie);
                                Thread.currentThread().interrupt();
                                if (recordingState == RecordingState.STARTING)
                                    recordingState = RecordingState.NONE;
                                stopSelf();
                                return;
                            }
                        } else {
                            FLog.e(TAG, "Max retries reached for camera " + finalCameraToOpenId + ". Giving up. Reason: "
                                    + reason);
                            if (recordingState == RecordingState.STARTING)
                                recordingState = RecordingState.NONE;
                            Toast.makeText(this, getString(R.string.toast_camera_unavailable, reason),
                                    Toast.LENGTH_LONG).show();
                            stopSelf();
                            return;
                        }
                    } else {
                        FLog.e(TAG, "Unrecoverable CameraAccessException (Reason: " + reason + "). Not retrying.", e);
                        if (recordingState == RecordingState.STARTING)
                            recordingState = RecordingState.NONE;
                        Toast.makeText(this, getString(R.string.toast_camera_access_error, reason), Toast.LENGTH_LONG).show();
                        stopSelf();
                        return;
                    }
                } catch (IllegalArgumentException e) {
                    FLog.e(TAG, "openCamera: Illegal Argument Exception (likely invalid camera ID '"
                            + finalCameraToOpenId + "'). Attempt: " + (attempt + 1), e);
                    if (recordingState == RecordingState.STARTING)
                        recordingState = RecordingState.NONE;
                    Toast.makeText(this, getString(R.string.toast_invalid_camera_config), Toast.LENGTH_LONG).show();
                    stopSelf();
                    return;
                } catch (Exception e) {
                    FLog.e(TAG, "openCamera: Unexpected error on attempt " + (attempt + 1) + " for ID "
                            + finalCameraToOpenId, e);
                    if (recordingState == RecordingState.STARTING)
                        recordingState = RecordingState.NONE;
                    Toast.makeText(this, getString(R.string.toast_unexpected_camera_error), Toast.LENGTH_LONG).show();
                    stopSelf();
                    return;
                }
            }
        } catch (CameraAccessException e) {
            FLog.e(TAG, "openCamera: Initial Camera Access Exception (listing/characteristics)", e);
            if (recordingState == RecordingState.STARTING)
                recordingState = RecordingState.NONE;
            Toast.makeText(this, getString(R.string.toast_failed_camera_details), Toast.LENGTH_LONG).show();
            stopSelf();
        } catch (IllegalArgumentException e) {
            FLog.e(TAG, "openCamera: Outer Illegal Argument Exception (likely invalid camera ID '" + cameraToOpenId
                    + "' for characteristics)", e);
            if (recordingState == RecordingState.STARTING)
                recordingState = RecordingState.NONE;
            Toast.makeText(this, getString(R.string.toast_invalid_camera_setup), Toast.LENGTH_LONG).show();
            stopSelf();
        } catch (Exception e) {
            FLog.e(TAG, "openCamera: Unexpected outer error", e);
            if (recordingState == RecordingState.STARTING)
                recordingState = RecordingState.NONE;
            Toast.makeText(this, getString(R.string.toast_critical_camera_error), Toast.LENGTH_LONG).show();
            stopSelf();
        }
    }

    private final CameraDevice.StateCallback cameraStateCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(@NonNull CameraDevice camera) {
            cameraDevice = camera;
            isCameraOpen = true;

            // Check if we're waiting for camera to resume normal recording
            if (recordingState == RecordingState.WAITING_FOR_CAMERA && pendingCameraReconnect) {
                FLog.d(TAG, "Camera reconnected after interruption, resuming normal recording");
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
                            FLog.d(TAG, "Waiting for black frame renderer cleanup before reconnecting camera");

                            // Resume camera session after delay
                            mainHandler.postDelayed(() -> resumeCameraAfterReconnection(), 1500);
                        } else {
                            // If we weren't rendering black frames, proceed immediately
                            resumeCameraAfterReconnection();
                        }
                    } catch (Exception e) {
                        FLog.e(TAG, "Error during camera reconnection sequence", e);
                        // Fall back to black frame rendering
                        recordingState = RecordingState.WAITING_FOR_CAMERA;
                        startBlackFrameRendering();
                    }
                });
                return;
            }

            // CRITICAL FIX: Handle camera switch scenario
            // When camera opens during PAUSED state, we need to create a capture session
            // so frames will be available when resumeRecording() is called
            if (recordingState == RecordingState.PAUSED && isSwitchingCamera) {
                FLog.d(TAG, "Camera opened during camera switch (PAUSED state) - creating capture session");
                try {
                    createCameraPreviewSession();
                } catch (Exception e) {
                    FLog.e(TAG, "Error creating capture session during camera switch", e);
                    // Attempt recovery
                    try {
                        stopRecording();
                    } catch (Exception stopError) {
                        FLog.e(TAG, "Error stopping recording during camera switch error recovery", stopError);
                    }
                }
                return;
            }

            // Check if we have a pending recording start request
            if (pendingStartRecording) {
                pendingStartRecording = false; // Reset the flag
                mainHandler.post(RecordingService.this::attemptStartRecordingIfReady);
            } else {
                FLog.d(TAG, "Camera opened but no pending recording start");
                // If we're not starting recording, create a preview session
                if (previewSurface != null) {
                    try {
                        createCameraPreviewSession();
                    } catch (Exception e) {
                        FLog.e(TAG, "Error creating preview session", e);
                    }
                }
            }
        }

        @Override
        public void onDisconnected(@NonNull CameraDevice camera) {
            FLog.d(TAG, "Camera device disconnected");

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
                FLog.w(TAG, "Camera disconnected while pendingStartRecording=true, resetting flag");
                pendingStartRecording = false;
            }

            // If we were recording, switch to black frame mode
            if (wasRecording) {
                FLog.w(TAG, "Camera disconnected during recording, switching to black frame mode");

                // Use a small delay to avoid race conditions with camera state changes
                mainHandler.postDelayed(() -> {
                    handleCameraInterruption();
                }, 300);
            } else if (recordingState != RecordingState.NONE && recordingState != RecordingState.WAITING_FOR_CAMERA) {
                // For other states like PAUSED, just stop recording
                FLog.w(TAG, "Camera disconnected while in state " + recordingState + ", stopping recording");
                stopRecording();
            } else if (previewOnlyActive) {
                FLog.w(TAG, "Camera disconnected during preview-only, scheduling recovery");
                mainHandler.postDelayed(() -> {
                    if (!previewOnlyActive || recordingState != RecordingState.NONE) {
                        return;
                    }
                    if (previewSurface == null || !previewSurface.isValid()) {
                        pendingPreviewOnlyStart = true;
                        FLog.d(TAG, "Preview-only recovery deferred: waiting for valid surface");
                        return;
                    }
                    try {
                        ensurePreviewOnlyGlPipeline();
                        openCamera();
                    } catch (Exception e) {
                        FLog.e(TAG, "Preview-only recovery failed", e);
                    }
                }, 450);
            }
        }

        @Override
        public void onError(@NonNull CameraDevice camera, int error) {
            FLog.e(TAG, "Camera device error: " + error);

            // Store camera state before closing
            boolean wasRecording = (recordingState == RecordingState.IN_PROGRESS);

            if (cameraDevice != null) {
                cameraDevice.close();
                cameraDevice = null;
            }
            isCameraOpen = false;

            // Reset the pending flag if camera error
            if (pendingStartRecording) {
                FLog.w(TAG, "Camera error while pendingStartRecording=true, resetting flag");
                pendingStartRecording = false;
            }

            // If error is camera in use and we were recording, switch to black frame mode
            if ((error == CameraDevice.StateCallback.ERROR_CAMERA_IN_USE ||
                    error == CameraDevice.StateCallback.ERROR_MAX_CAMERAS_IN_USE) &&
                    wasRecording) {
                FLog.w(TAG, "Camera in use error during recording, switching to black frame mode");

                // Use a small delay to avoid race conditions with camera state changes
                mainHandler.postDelayed(() -> {
                    handleCameraInterruption();
                }, 300);
            }
            // For other errors or states, stop recording
            else if (recordingState != RecordingState.NONE && recordingState != RecordingState.WAITING_FOR_CAMERA) {
                FLog.w(TAG, "Camera error during recording, stopping recording");
                stopRecording();
            } else if (previewOnlyActive) {
                FLog.w(TAG, "Camera error during preview-only, scheduling recovery");
                mainHandler.postDelayed(() -> {
                    if (!previewOnlyActive || recordingState != RecordingState.NONE) {
                        return;
                    }
                    if (previewSurface == null || !previewSurface.isValid()) {
                        pendingPreviewOnlyStart = true;
                        FLog.d(TAG, "Preview-only recovery deferred after error: waiting for valid surface");
                        return;
                    }
                    try {
                        ensurePreviewOnlyGlPipeline();
                        openCamera();
                    } catch (Exception e) {
                        FLog.e(TAG, "Preview-only recovery after error failed", e);
                    }
                }, 550);
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
        }

        @Override
        public void onClosed(@NonNull CameraDevice camera) {
            isCameraOpen = false;

            // If we were in segment rollover, continue with the rollover process
            if (isRolloverClosingOldSession) {
                FLog.d(TAG, "Camera closed during segment rollover, proceeding with rollover");
                isRolloverClosingOldSession = false;
                proceedWithRolloverAfterOldSessionClosed();
            }

            // Reset the pending flag if camera closed
            if (pendingStartRecording) {
                FLog.w(TAG, "Camera closed while pendingStartRecording=true, resetting flag");
                pendingStartRecording = false;
            }
        }
    };

    // wait -----------
    private void attemptStartRecordingIfReady() {
        try {
            if (recordingState != RecordingState.STARTING) {
                FLog.d(TAG, "attemptStartRecordingIfReady: Not in STARTING state (" + recordingState + ")");
                return;
            }
            // Ensure camera is opened before starting
            if (cameraDevice == null) {
                pendingStartRecording = true;
                return;
            }
            if (waitForPreviewBeforeStart) {
                boolean ready = previewSurface != null && previewSurface.isValid();
                if (!ready) {
                    return;
                }
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
            // Dispatch to background handler — GLRecordingPipeline creation (MediaCodec + EGL + TFLite/OpenCV) is heavy
            // and must NOT run on the main thread to avoid ~800ms Choreographer frame skips.
            backgroundHandler.post(this::startRecording);
        } catch (Exception e) {
            FLog.e(TAG, "attemptStartRecordingIfReady: error starting", e);
            stopRecording();
        }
    }
    // wait -----------

    /**
     * Helper method to resume normal recording after camera reconnection.
     * This is called after any black frame rendering has been properly stopped.
     */
    private void resumeCameraAfterReconnection() {
        try {
            FLog.d(TAG, "Resuming normal camera recording after reconnection");

            // Create camera session first
            createCameraPreviewSession();

            // Wait a moment for the session to be fully configured
            mainHandler.postDelayed(() -> {
                try {
                    // Update recording state
                    recordingState = RecordingState.IN_PROGRESS;

                    // Broadcast to UI so HomeFragment can restore buttons/preview
                    broadcastOnRecordingResumed();

                    // Show notification about recording resumed
                    setupRecordingInProgressNotification();

                    // Show toast to user
                    // Toast.makeText(RecordingService.this,
                    // R.string.camera_reconnection_success,
                    // Toast.LENGTH_SHORT).show();

                    FLog.i(TAG, "Normal recording resumed after camera reconnection");
                } catch (Exception e) {
                    FLog.e(TAG, "Error finalizing camera reconnection", e);
                    // If we fail at this stage, fall back to black frames
                    if (recordingState == RecordingState.IN_PROGRESS) {
                        recordingState = RecordingState.WAITING_FOR_CAMERA;
                        startBlackFrameRendering();
                    }
                }
            }, 1000); // Longer delay to ensure session is ready
        } catch (Exception e) {
            FLog.e(TAG, "Error creating camera session after reconnection", e);
            // Continue with black frames if we can't resume normal recording
            recordingState = RecordingState.WAITING_FOR_CAMERA;
            startBlackFrameRendering();
        }
    }

    private void createCameraPreviewSession() {
        if (cameraDevice == null) {
            FLog.e(TAG, "createCameraPreviewSession: cameraDevice is null!");
            if (previewOnlyActive) {
                stopPreviewOnlyMode();
            } else if (recordingState == RecordingState.IN_PROGRESS) {
                // Camera disappeared during active recording — the recording
                // pipeline is likely already handling this.  Do NOT call
                // stopRecording() again as that spawns a duplicate cleanup
                // thread that fights with the existing one.
                FLog.w(TAG, "createCameraPreviewSession: cameraDevice null during IN_PROGRESS — skipping");
                return;
            } else if (recordingState == RecordingState.STARTING) {
                // Camera failed during startup — abort cleanly
                recordingState = RecordingState.NONE;
                sharedPreferencesManager.setRecordingInProgress(false);
                stopSelf();
                return;
            } else {
                stopRecording();
            }
            return;
        }
        // If using OpenGL pipeline for watermarking
        if (glRecordingPipeline != null) {
            Surface glSurface = glRecordingPipeline.getCameraInputSurface();
            List<Surface> surfaces = new ArrayList<>();
            if (glSurface != null) {
                surfaces.add(glSurface);
            } else {
                FLog.e(TAG, "GL pipeline camera input surface is null!");
            }
            // Do NOT add previewSurface to Camera2 session outputs
            // if (previewSurface != null) {
            // surfaces.add(previewSurface);
            // } else {
            // }
            if (surfaces.isEmpty()) {
                FLog.e(TAG, "No valid surfaces for camera session!");
                if (previewOnlyActive) {
                    FLog.w(TAG, "Preview-only active but no UI surface yet; waiting for ACTION_CHANGE_SURFACE");
                    return;
                }
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
                    FLog.e(TAG, "Error getting camera characteristics", e);
                }
            }
            // method(createCameraPreviewSession)-----------
            // Use per-camera FPS setting and only choose HSR if selected resolution
            // supports it
            int targetFrameRate = sharedPreferencesManager.getSpecificVideoFrameRate(cameraType);

            // If the encoder had to clamp the frame rate (4K60 on a 4K30 encoder), the camera
            // session must clamp too. Otherwise the encoder is configured for 30 while the
            // camera keeps delivering 60.
            try {
                int encoderFps = glRecordingPipeline.getEncoderFramerate();
                if (encoderFps > 0 && encoderFps != targetFrameRate) {
                    if (Log.isEnabled()) {
                        FLog.w(TAG, "[REC] camera FPS follows encoder clamp: " + targetFrameRate
                                + " -> " + encoderFps);
                    }
                    targetFrameRate = encoderFps;
                }
            } catch (Exception ignored) {
            }
            
            // Apply streaming FPS cap only when server is actually ON
            boolean isStreamingForFps1 = com.fadcam.FeatureRegistry.streaming().isStreamingEnabled();
            android.content.SharedPreferences fadcamPrefs = getSharedPreferences("FadCamPrefs", Context.MODE_PRIVATE);
            if (isStreamingForFps1) {
                int streamFpsCap = fadcamPrefs.getInt("stream_fps_cap", -1);
                if (streamFpsCap > 0 && targetFrameRate > streamFpsCap) {
                    FLog.i(TAG, "[STREAM PRESET] Capping camera FPS: " + targetFrameRate + " → " + streamFpsCap + "fps (server ON)");
                    targetFrameRate = streamFpsCap;
                }
            } else {
            }
            
            boolean isHighFrameRate = targetFrameRate >= 120;
            boolean useHighSpeedSession = false;
            Size selected = sharedPreferencesManager.getCameraResolution();
            maybeAttachMotionAnalysisSurface(surfaces, targetFrameRate);

            if (isHighFrameRate && characteristics != null) {
                showFrameRateToast(targetFrameRate);

                if (DeviceHelper.isSamsung()) {
                    // Unify Samsung path with Pixel: always use standard session (no HSR).
                    FLog.d(TAG, "Samsung device: forcing standard session for " + targetFrameRate
                            + "fps to match Pixel behavior");
                    useHighSpeedSession = false;
                } else if (HighSpeedCaptureHelper.isHighSpeedSupported(characteristics, targetFrameRate)) {
                    Size hs = HighSpeedCaptureHelper.getBestHighSpeedSize(characteristics, targetFrameRate,
                            selected.getWidth(), selected.getHeight());
                    if (hs != null && hs.getWidth() == selected.getWidth() && hs.getHeight() == selected.getHeight()) {
                        FLog.d(TAG, "HSR supported at selected size for " + targetFrameRate + "fps");
                        useHighSpeedSession = true;
                    } else {
                        FLog.d(TAG, "HSR supported but selected size incompatible; using standard session");
                        useHighSpeedSession = false;
                    }
                }
            }

            currentCameraCharacteristics = characteristics;
            // The renderer derives every rotation from the sensor angle. It is created
            // once per pipeline, so a live camera switch must push the new angle or the
            // overlay keeps rotating boxes for the previous sensor.
            pushSensorOrientationToRenderer();
            if (useHighSpeedSession) {
                createHighSpeedSession(surfaces, characteristics, targetFrameRate, cameraType);
            } else {
                createStandardSession(surfaces, targetFrameRate, characteristics, cameraType);
            }
            // method(createCameraPreviewSession)-----------
            return;
        }

        FLog.d(TAG, "createCameraPreviewSession: Creating session...");
        try {
            List<Surface> surfaces = new ArrayList<>();
            if (glRecordingPipeline != null) {
                surfaces.add(glRecordingPipeline.getCameraInputSurface());
            }
            if (previewSurface != null) {
                surfaces.add(previewSurface);
                previewSurfaceAdded = true;
                FLog.d(TAG, "Using valid preview surface from UI");
            } else if (dummyBackgroundSurface != null && dummyBackgroundSurface.isValid()) {
                // Use dummy surface when UI surface is gone (app backgrounded)
                // to prevent recording issues like green frames
                surfaces.add(dummyBackgroundSurface);
                FLog.d(TAG, "Using dummy surface (app backgrounded) to maintain stable recording");
                previewSurfaceAdded = true;
            } else {
                FLog.d(TAG, "No valid preview or dummy surface available");
            }

            // Get camera characteristics for frame rate handling
            CameraManager cameraManager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
            CameraType cameraType = sharedPreferencesManager.getCameraSelection();
            String cameraId = getCameraId(cameraManager, cameraType);
            CameraCharacteristics characteristics = null;

            if (cameraId != null) {
                characteristics = cameraManager.getCameraCharacteristics(cameraId);
            }

            // Get target frame rate from settings for specific camera
            int targetFrameRate = sharedPreferencesManager.getSpecificVideoFrameRate(cameraType);
            
            // Apply streaming FPS cap only when server is actually ON
            boolean isStreamingForFps2 = com.fadcam.FeatureRegistry.streaming().isStreamingEnabled();
            android.content.SharedPreferences fadcamPrefs = getSharedPreferences("FadCamPrefs", Context.MODE_PRIVATE);
            if (isStreamingForFps2) {
                int streamFpsCap = fadcamPrefs.getInt("stream_fps_cap", -1);
                if (streamFpsCap > 0 && targetFrameRate > streamFpsCap) {
                    FLog.i(TAG, "[STREAM PRESET] Capping camera FPS: " + targetFrameRate + " → " + streamFpsCap + "fps (server ON)");
                    targetFrameRate = streamFpsCap;
                }
            } else {
            }
            maybeAttachMotionAnalysisSurface(surfaces, targetFrameRate);

            // Log device info once for debugging
            DeviceHelper.logDeviceInfo();

            // Check if we need to use high-speed session for 60fps+ recording
            boolean isHighFrameRate = targetFrameRate >= 120;
            boolean useHighSpeedSession = false;

            // Continue with existing code...
            // Rest of the method stays the same

            // For high frame rates, evaluate if we should use high-speed session
            if (isHighFrameRate && characteristics != null) {
                // Unify Samsung path with Pixel: never force HSR on Samsung
                if (DeviceHelper.isSamsung()) {
                    FLog.d(TAG, "Samsung device: using standard session (no HSR) for " + targetFrameRate + "fps");
                    showFrameRateToast(targetFrameRate);
                    useHighSpeedSession = false;
                }
                // For other devices, check if high-speed is supported
                else if (HighSpeedCaptureHelper.isHighSpeedSupported(characteristics, targetFrameRate)) {
                    Size selected = sharedPreferencesManager.getCameraResolution();
                    Size hs = HighSpeedCaptureHelper.getBestHighSpeedSize(characteristics, targetFrameRate,
                            selected.getWidth(), selected.getHeight());
                    if (hs != null && hs.getWidth() == selected.getWidth() && hs.getHeight() == selected.getHeight()) {
                        FLog.d(TAG, "High-speed session supported at selected size for " + targetFrameRate + "fps");
                        useHighSpeedSession = true;
                    } else {
                        FLog.d(TAG, "HSR supported but not at selected size; using standard session");
                        useHighSpeedSession = false;
                    }

                    // Show toast informing the user about experimental 60fps
                    showFrameRateToast(targetFrameRate);
                } else {
                    FLog.d(TAG, "High-speed not supported for " + targetFrameRate +
                            "fps, using standard session");

                    // Show toast informing the user that high frame rate may not be fully supported
                    showFrameRateToast(targetFrameRate);
                }
            }

            // Create the appropriate type of session
            currentCameraCharacteristics = characteristics;
            pushSensorOrientationToRenderer();
            if (useHighSpeedSession) {
                // For high-speed sessions
                createHighSpeedSession(surfaces, characteristics, targetFrameRate, cameraType);
            } else {
                // Create a standard session with appropriate frame rate settings
                createStandardSession(surfaces, targetFrameRate, characteristics, cameraType);
            }
        } catch (CameraAccessException e) {
            FLog.e(TAG, "createCameraPreviewSession: Camera Access Exception", e);
            if (previewOnlyActive) {
                stopPreviewOnlyMode();
            } else {
                stopRecording();
            }
        } catch (IllegalStateException e) {
            FLog.e(TAG, "createCameraPreviewSession: IllegalStateException", e);
            if (previewOnlyActive) {
                stopPreviewOnlyMode();
            } else {
                stopRecording();
            }
        } catch (Exception e) {
            FLog.e(TAG, "createCameraPreviewSession: Exception", e);
            if (previewOnlyActive) {
                stopPreviewOnlyMode();
            } else {
                stopRecording();
            }
        }
    }

    private final CameraCaptureSession.StateCallback captureSessionCallback = new CameraCaptureSession.StateCallback() {
        @Override
        public void onConfigured(@NonNull CameraCaptureSession session) {
            previewSessionConfigInFlight = false;
            if (isStopping || cameraDevice == null || (recordingState == RecordingState.NONE && !previewOnlyActive)) {
                FLog.w(TAG,
                        "onConfigured: Service is stopping, camera device is null, or recording state is NONE. Aborting.");
                return;
            }
            captureSession = session;
            try {
                // Set auto control mode
                captureRequestBuilder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);

                // CRITICAL: Re-apply camera preferences in session callback to ensure they
                // stick
                // This ensures AE mode, exposure compensation, and other settings are not lost
                try {
                    applySavedCameraPrefsToBuilder(captureRequestBuilder);
                } catch (Exception e) {
                    FLog.w(TAG, "Failed to re-apply camera prefs in callback: " + e.getMessage());
                }

                // Set torch/flash mode
                captureRequestBuilder.set(CaptureRequest.FLASH_MODE,
                        isRecordingTorchEnabled ? CaptureRequest.FLASH_MODE_TORCH : CaptureRequest.FLASH_MODE_OFF);
                // Defensive: check session state before using
                if (captureSession == null || cameraDevice == null
                        || (recordingState == RecordingState.NONE && !previewOnlyActive)) {
                    FLog.w(TAG,
                            "onConfigured: Session or cameraDevice became null before setRepeatingRequest. Aborting.");
                    return;
                }
                // Start repeating request
                try {
                    session.setRepeatingRequest(captureRequestBuilder.build(), null, backgroundHandler);
                    maybeApplyDeferredAeLockAfterSessionStart("standard");
                } catch (IllegalStateException ise) {
                    FLog.e(TAG, "setRepeatingRequest failed: session likely closed", ise);
                    if (previewOnlyActive) {
                        stopPreviewOnlyMode();
                    } else {
                        stopRecording();
                    }
                    return;
                }
                // Start the GL pipeline only after session is configured and repeating request
                // is set
                if (glRecordingPipeline != null && !previewOnlyActive) {
                    if (motionLabEnabledForSession
                            && com.fadcam.FeatureRegistry.streaming().isStreamingEnabled()) {
                        // Motion-armed + live view: do not write the arm period to the
                        // file. The stream sink runs; the file starts on the first trigger.
                        int preRollSeconds = motionRuntime != null
                                ? motionRuntime.getPreRollSeconds()
                                : (sharedPreferencesManager != null
                                    ? sharedPreferencesManager.getMotionPreRollSeconds() : 0);
                        glRecordingPipeline.setPreRollSeconds(preRollSeconds);
                        glRecordingPipeline.suspendFileRecordingOnStart();
                    }
                    glRecordingPipeline.startRecording();
                }
                // Handle recording state
                handleSessionConfigured();
            } catch (CameraAccessException e) {
                FLog.e(TAG, "Error starting repeating request", e);
                if (previewOnlyActive) {
                    stopPreviewOnlyMode();
                } else {
                    stopRecording();
                }
            }
        }

        @Override
        public void onConfigureFailed(@NonNull CameraCaptureSession session) {
            previewSessionConfigInFlight = false;
            if (isStopping)
                return;
            FLog.e(TAG, "Standard capture session configuration failed");
            if (previewOnlyActive) {
                stopPreviewOnlyMode();
            } else {
                stopRecording();
            }
        }

        @Override
        public void onClosed(@NonNull CameraCaptureSession session) {
            previewSessionConfigInFlight = false;
            if (isStopping)
                return;
            FLog.d(TAG, "Capture session closed");

            if (session == captureSession) {
                captureSession = null;
            }

            if (isRolloverClosingOldSession) {
                FLog.d(TAG, "Capture session closed as part of rollover");
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
                FLog.e(TAG, "onConfigured: Service is stopping or camera closed before high-speed session configured");
                return;
            }

            try {
                FLog.d(TAG, "High-speed session configured successfully");
                captureSession = session;

                // For high-speed sessions, we need to create a list of requests for burst
                CameraConstrainedHighSpeedCaptureSession highSpeedSession = (CameraConstrainedHighSpeedCaptureSession) session;

                List<CaptureRequest> highSpeedRequests = highSpeedSession
                        .createHighSpeedRequestList(captureRequestBuilder.build());

                // Start repeating burst for high-speed recording
                highSpeedSession.setRepeatingBurst(highSpeedRequests, null, backgroundHandler);
                maybeApplyDeferredAeLockAfterSessionStart("high-speed");

                // Handle recording state
                handleSessionConfigured();
            } catch (CameraAccessException e) {
                FLog.e(TAG, "Error setting up high-speed repeating burst", e);
                stopRecording();
            } catch (IllegalArgumentException | IllegalStateException e) {
                FLog.e(TAG, "Error in high-speed session configuration", e);
                stopRecording();
            }
        }

        @Override
        public void onConfigureFailed(@NonNull CameraCaptureSession session) {
            if (isStopping)
                return;
            FLog.e(TAG, "High-speed session configuration failed");

            try {
                // Can't get surfaces from the failed session, need to recreate them
                CameraManager cameraManager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
                CameraType cameraType = sharedPreferencesManager.getCameraSelection();
                String cameraId = getCameraId(cameraManager, cameraType);
                CameraCharacteristics characteristics = null;

                if (cameraId != null) {
                    characteristics = cameraManager.getCameraCharacteristics(cameraId);
                }
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
                    FLog.e(TAG, "Failed to create surfaces for fallback session");
                    stopRecording();
                }
                // --------------
            } catch (Exception e) {
                FLog.e(TAG, "Failed to create fallback session after high-speed failure", e);
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
        FLog.e(TAG, "Processing Error: " + errorMessage);
        Toast.makeText(this, getString(R.string.toast_processing_error), Toast.LENGTH_LONG).show();

        // If an input path/URI was provided, log attempt to clean it
        if (internalTempInputPath != null) {
            FLog.d(TAG, "handleProcessingError: Original temp file to keep (maybe): " + internalTempInputPath);
            // We generally DO NOT delete the input temp file on processing errors,
            // as it might be the only copy left. We just log it.
            // CleanupTemporaryFile() call will be skipped if processing failed.
        } else {
            FLog.w(TAG, "handleProcessingError called without specific input path reference.");
        }

        // Check if the service should stop now that processing has failed/stopped
        if (!isWorkingInProgress()) {
            FLog.d(TAG, "handleProcessingError: No other work pending, stopping service.");
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
        FLog.e(TAG, message);
        Toast.makeText(this, message + ", Using Internal.", Toast.LENGTH_LONG).show();
        sharedPreferencesManager.setStorageMode(SharedPreferencesManager.STORAGE_MODE_INTERNAL);
        sharedPreferencesManager.setCustomStorageUri(null);
        // Note: Do not call stopSelf here, let setupMediaRecorder fail naturally
    }
    // --- End Watermarking & Processing ---

    // --- Helper Methods ---
    private void setupSurfaceTexture(Intent intent) {
        Surface oldPreviewSurface = previewSurface; // Store old surface to check for changes
        if (intent != null) {
            boolean hasSurfaceExtra = intent.hasExtra("SURFACE");
            if (hasSurfaceExtra) {
                previewSurface = com.fadcam.Utils.getParcelableExtraCompat(intent, "SURFACE", android.view.Surface.class);
            }
            boolean isFullscreenTransition = intent.getBooleanExtra("IS_FULLSCREEN_TRANSITION", false);
            boolean validOldSurface = oldPreviewSurface != null && oldPreviewSurface.isValid();
            boolean validNewSurface = previewSurface != null && previewSurface.isValid();
            
            if (glRecordingPipeline != null) {
                if (validNewSurface) {
                    // Use IMMEDIATE mode during active recording to avoid the 200ms
                    // debounce callback blocking the GL handler from processing
                    // onFrameAvailable (causing periodic ~120-200ms frame gaps).
                    boolean useImmediate = isFullscreenTransition || isRecording();
                    if (useImmediate) {
                        glRecordingPipeline.setPreviewSurfaceImmediate(previewSurface);
                    } else {
                        glRecordingPipeline.setPreviewSurface(previewSurface);
                    }
                } else if (hasSurfaceExtra && !isFullscreenTransition) {
                    // Never set null during fullscreen transition — keep old surface alive
                    // until the fullscreen surface arrives (avoids GL pipeline losing render target)
                    glRecordingPipeline.setPreviewSurface(null);
                } else if (hasSurfaceExtra && isFullscreenTransition) {
                    FLog.d(TAG, "Skipping null surface — fullscreen transition in progress, keeping old surface");
                }
            }
            // Only create dummy surface if truly backgrounding, not transitioning to fullscreen
            if (validOldSurface && !validNewSurface && isRecordingOrPaused() && !isFullscreenTransition) {
                createDummyBackgroundSurface();
            } else if (isFullscreenTransition) {
                FLog.d(TAG, "Surface null due to fullscreen transition - skipping dummy surface creation");
            }
            int width = intent.getIntExtra("SURFACE_WIDTH", -1);
            int height = intent.getIntExtra("SURFACE_HEIGHT", -1);
            // Update the GL pipeline with the new dimensions if available
            if (glRecordingPipeline != null && validNewSurface && width > 0 && height > 0) {
                glRecordingPipeline.updateSurfaceDimensions(width, height);
            }
        }
    }

    private int getVideoBitrate() {
        int videoBitrate;
        boolean isStreaming = com.fadcam.FeatureRegistry.streaming().isStreamingEnabled();
        
        if (isStreaming) {
            // Server is ON: enforce stream quality preset bitrate, ignore user settings
            android.content.SharedPreferences fadcamPrefs = getSharedPreferences("FadCamPrefs", android.content.Context.MODE_PRIVATE);
            int streamBitrate = fadcamPrefs.getInt("stream_bitrate", -1);
            String preset = fadcamPrefs.getString("quality_preset", "HIGH");
            if (streamBitrate > 0) {
                videoBitrate = streamBitrate;
                FLog.i(TAG, "[STREAM PRESET] Bitrate enforced: " + (videoBitrate / 1_000_000) + " Mbps (preset=" + preset + ") — user settings ignored");
            } else {
                // Preset not set yet, fall back to default HIGH
                videoBitrate = 5_000_000;
                FLog.w(TAG, "[STREAM PRESET] Server ON but no preset stored — using default HIGH (5 Mbps)");
            }
        } else if (sharedPreferencesManager.sharedPreferences.getBoolean("bitrate_mode_custom", false)) {
            videoBitrate = sharedPreferencesManager.sharedPreferences.getInt("bitrate_custom_value", 16000) * 1000;
        } else {
            videoBitrate = Utils.estimateBitrate(sharedPreferencesManager.getCameraResolution(),
                    sharedPreferencesManager.getVideoFrameRate());
            // bitrate log removed " + (videoBitrate / 1000) + " kbps");
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
        FLog.d(TAG, "Determined Font Size: " + fontSize + " for bitrate " + videoBitrate);
        return fontSize;
    }

    private String getLocationData() {
        if (locationHelper == null) {
            FLog.w(TAG, "❌ LocationHelper not initialized - location watermark DISABLED");
            return ""; // Return empty, not "Not available" to avoid user confusion
        }
        
        long currentTimeMs = System.currentTimeMillis();
        long locationUpdateIntervalMs = sharedPreferencesManager.getWatermarkUpdateInterval();
        long timeSinceLastUpdateMs = currentTimeMs - lastLocationWatermarkUpdateMs;
        
        // Location update check interval=" + locationUpdateIntervalMs + "ms, elapsed=" + timeSinceLastUpdateMs + "ms");
        
        // Only refresh location data if enough time has passed
        if (timeSinceLastUpdateMs >= locationUpdateIntervalMs) {
            // Fetching fresh location data (interval elapsed)");
            
            String locData = locationHelper.getLocationData();
            // Avoid logging precise coordinates; getLocationData() already logs a redacted version
            // Raw location from helper: " + (locData != null && locData.contains("Lat:") ? "[coords present]" : "[no coords]"));
            
            // Avoid adding "Location not available" to watermark, just add lat/lon if present
            if (locData == null || !locData.contains("Lat:")) {
                // Location data invalid or missing GPS coordinates");
                cachedLocationWatermarkText = "";
            } else {
                // Apply format preference
                String format = sharedPreferencesManager.getWatermarkLocationFormat();
                FLog.d(TAG, "📍 Location format preference: " + format);
                
                if ("address".equals(format)) {
                    if (locationGeocoder != null) {
                        // Get coordinates from LocationHelper's current location
                        org.osmdroid.util.GeoPoint geoPoint = locationHelper.getCurrentLocation();
                        if (geoPoint != null) {
                            // Log redacted coords (~1km precision) for privacy
                            FLog.d(TAG, "📍 Coordinates: ~" + String.format(java.util.Locale.US, "%.2f", geoPoint.getLatitude()) + ", ~" + String.format(java.util.Locale.US, "%.2f", geoPoint.getLongitude()));
                            
                            // Trigger async geocoding if not already cached
                            locationGeocoder.geocodeAsync(geoPoint.getLatitude(), geoPoint.getLongitude(), result -> {
                                FLog.d(TAG, "🌐 Geocode callback: result ready=" + !result.isEmpty());
                            });
                            
                            // Try to get cached result if available
                            LocationGeocoder.GeocodeResult result = locationGeocoder.getCurrentResult();
                            if (!result.isEmpty()) {
                                cachedLocationWatermarkText = "\nLat: " + geoPoint.getLatitude() + ", Long: " + geoPoint.getLongitude()
                                     + "\n" + result.formatted;
                                FLog.d(TAG, "📍 address format applied");
                            } else {
                                cachedLocationWatermarkText = locData;
                                FLog.d(TAG, "📍 Geocoding result not ready, using coordinates only");
                            }
                        } else {
                            cachedLocationWatermarkText = locData;
                            FLog.w(TAG, "❌ GeoPoint is null, using coordinates only");
                        }
                    } else {
                        cachedLocationWatermarkText = locData;
                        FLog.w(TAG, "❌ LocationGeocoder not initialized, using coordinates only");
                    }
                } else {
                    // Default: coordinates only
                    cachedLocationWatermarkText = locData;
                    FLog.d(TAG, "📍 Using coordinates-only format");
                }
            }
            
            // Append UTM coordinates if enabled and location is available
            if (sharedPreferencesManager.isUtmEnabled() && locationHelper != null) {
                org.osmdroid.util.GeoPoint utmPt = locationHelper.getCurrentLocation();
                if (utmPt != null) {
                    String utm = com.fadcam.utils.UTMConverter.latLonToUTM(utmPt.getLatitude(), utmPt.getLongitude());
                    if (utm != null && !utm.isEmpty()) {
                        cachedLocationWatermarkText += "\n" + utm;
                    }
                }
            }
            
            lastLocationWatermarkUpdateMs = currentTimeMs;
            // Location watermark cache updated");
        } else {
            // Using cached location data (too soon to update)");
        }
        
        // Show GPS-off message if provider is disabled — covers location + UTM independently
        if (!cachedGpsProviderEnabled) {
            StringBuilder gpsOffMsg = new StringBuilder("\nLocation: GPS is off");
            if (sharedPreferencesManager.isUtmEnabled()) {
                gpsOffMsg.append("\nUTM: GPS is off");
            }
            return gpsOffMsg.toString();
        }

        // GPS just turned on but cache is stale — force immediate refresh
        if (cachedLocationWatermarkText.isEmpty()) {
            lastLocationWatermarkUpdateMs = 0;
        }

        return cachedLocationWatermarkText;
    }

    private String buildPhotoWatermarkExtras() {
        StringBuilder sb = new StringBuilder();

        // Location data (same format as video watermark)
        if (sharedPreferencesManager != null && sharedPreferencesManager.isLocalisationEnabled()) {
            String loc = getLocationData();
            if (loc != null && !loc.isEmpty()) {
                sb.append(loc.trim());
            }
        }

        // Extended sensor data (same format as video watermark)
        String sensors = getExtendedSensorData();
        if (sensors != null && !sensors.isEmpty()) {
            if (sb.length() > 0) sb.append("\n");
            sb.append(sensors.trim());
        }

        return sb.toString();
    }

    private String getExtendedSensorData() {
        if (sharedPreferencesManager == null) {
            return "";
        }

        // Throttled GPS provider check — same API as HomeFragment banner, cached for 5s
        long now = System.currentTimeMillis();
        if (now - lastGpsProviderCheckMs >= GPS_PROVIDER_CHECK_INTERVAL_MS) {
            android.location.LocationManager lm = (android.location.LocationManager)
                    getSystemService(Context.LOCATION_SERVICE);
            cachedGpsProviderEnabled = lm != null
                    && lm.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER);
            lastGpsProviderCheckMs = now;
        }

        if (locationHelper != null && sensorDataProvider != null) {
            android.location.Location rawLoc = locationHelper.getRawLocation();
            if (rawLoc != null) {
                sensorDataProvider.updateLocation(rawLoc);
            }
        }

        StringBuilder sb = new StringBuilder();

        if (sharedPreferencesManager.isSpeedEnabled() && sensorDataProvider != null) {
            if (!cachedGpsProviderEnabled) {
                FLog.d(TAG, "Extended: speed=GPS is off");
                sb.append("\nSpeed: GPS is off");
            } else if (sensorDataProvider.hasFreshFix()) {
                float speed = sensorDataProvider.getSpeedKmh();
                FLog.d(TAG, "Extended: speed=" + speed + " km/h");
                sb.append("\nSpeed: ").append(String.format("%.0f", speed)).append(" km/h");
            } else {
                // No fresh fix: a held-over number would be a fabricated reading.
                FLog.d(TAG, "Extended: speed=no fresh fix");
                sb.append("\nSpeed: --");
            }
        }

        if (sharedPreferencesManager.isAltitudeEnabled() && sensorDataProvider != null) {
            if (!cachedGpsProviderEnabled) {
                FLog.d(TAG, "Extended: altitude=GPS is off");
                sb.append("\nAlt: GPS is off");
            } else if (sensorDataProvider.hasFreshFix()) {
                double alt = sensorDataProvider.getAltitude();
                FLog.d(TAG, "Extended: altitude=" + alt + " m");
                sb.append("\nAlt: ").append(String.format("%.0f", alt)).append("m");
            } else {
                FLog.d(TAG, "Extended: altitude=no fresh fix");
                sb.append("\nAlt: --");
            }
        }

        // Accuracy — gated behind its own toggle like other GPS features
        if (sharedPreferencesManager.isAccuracyEnabled() && sensorDataProvider != null) {
            if (!cachedGpsProviderEnabled) {
                FLog.d(TAG, "Extended: accuracy=GPS is off");
                sb.append("\nAccuracy: GPS is off");
            } else {
                float acc = sensorDataProvider.getAccuracy();
                FLog.d(TAG, "Extended: accuracy=" + acc + " m");
                sb.append("\nAccuracy: ").append(String.format("%.0f", acc)).append("m");
            }
        }

        if (sharedPreferencesManager.isCompassEnabled() && sensorDataProvider != null) {
            String compass = sensorDataProvider.getCompassDirection();
            // sensor log removed
            sb.append("\nCompass: ").append(compass);
        }

        if (sharedPreferencesManager.isNoiseEnabled() && noiseMonitor != null && noiseMonitor.isRunning()) {
            double db = noiseMonitor.getCurrentDb();
            // sensor log removed
            sb.append("\nNoise: ").append(noiseMonitor.getReadableDb());
        }

        if (sharedPreferencesManager.isWeatherEnabled() && weatherService != null && locationHelper != null) {
            org.osmdroid.util.GeoPoint geoPoint = locationHelper.getCurrentLocation();
            if (geoPoint != null) {
                weatherService.fetchWeather(geoPoint, (weather, wind) -> {
                    FLog.d(TAG, "Weather callback: " + weather + ", " + wind);
                });
                String weather = weatherService.getCurrentWeather();
                String wind = weatherService.getCurrentWind();
                if (weather != null && !weather.isEmpty()) {
                    sb.append("\n").append(weather);
                }
                if (wind != null && !wind.isEmpty()) {
                    sb.append("\nWind: ").append(wind);
                }
                FLog.d(TAG, "Extended: weather=" + weather + ", wind=" + wind);
            }
        }

        String result = sb.toString();
        if (!result.isEmpty()) {
            // sensor log removed
        }
        return result;
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
        // log removed
    }

    private long getEffectiveTimelineMs() {
        if (recordingStartTime <= 0L) {
            return 0L;
        }
        long anchor = (recordingState == RecordingState.PAUSED && pauseStartedAt > 0L)
                ? pauseStartedAt
                : SystemClock.elapsedRealtime();
        return Math.max(0L, anchor - recordingStartTime - accumulatedPausedDurationMs);
    }

    // --- End Helper Methods ---

    // --- Broadcasts ---
    private void broadcastOnRecordingStarted() {
        Intent broadcastIntent = new Intent(Constants.BROADCAST_ON_RECORDING_STARTED);
        broadcastIntent.putExtra(Constants.INTENT_EXTRA_RECORDING_START_TIME, recordingStartTime);
        broadcastIntent.putExtra(Constants.INTENT_EXTRA_RECORDING_STATE, recordingState);
        broadcastIntent.putExtra(Constants.INTENT_EXTRA_RECORDING_PAUSE_STARTED_AT, pauseStartedAt);
        broadcastIntent.putExtra(Constants.INTENT_EXTRA_RECORDING_ACCUMULATED_PAUSED_DURATION, accumulatedPausedDurationMs);
        broadcastIntent.setPackage(getPackageName());
        sendBroadcast(broadcastIntent);
        // Ask SystemUI to put the QS tile into listening state so its icon
        // flips to ACTIVE instantly (Android 13+; may be throttled by the
        // system — onStartListening refresh remains the guaranteed fallback).
        com.fadcam.services.RecordingTileService.requestTileRefresh(this);
    }

    private void broadcastOnRecordingResumed() {
        Intent broadcastIntent = new Intent(Constants.BROADCAST_ON_RECORDING_RESUMED);
        broadcastIntent.setPackage(getPackageName());
        broadcastIntent.putExtra(Constants.INTENT_EXTRA_RECORDING_START_TIME, recordingStartTime);
        broadcastIntent.putExtra(Constants.INTENT_EXTRA_RECORDING_PAUSE_STARTED_AT, pauseStartedAt);
        broadcastIntent.putExtra(Constants.INTENT_EXTRA_RECORDING_ACCUMULATED_PAUSED_DURATION, accumulatedPausedDurationMs);
        sendBroadcast(broadcastIntent);
    }

    private void broadcastOnRecordingPaused() {
        Intent broadcastIntent = new Intent(Constants.BROADCAST_ON_RECORDING_PAUSED);
        broadcastIntent.setPackage(getPackageName());
        broadcastIntent.putExtra(Constants.INTENT_EXTRA_RECORDING_START_TIME, recordingStartTime);
        broadcastIntent.putExtra(Constants.INTENT_EXTRA_RECORDING_PAUSE_STARTED_AT, pauseStartedAt);
        broadcastIntent.putExtra(Constants.INTENT_EXTRA_RECORDING_ACCUMULATED_PAUSED_DURATION, accumulatedPausedDurationMs);
        broadcastIntent.putExtra(Constants.INTENT_EXTRA_RECORDING_LIVE_VIEW_ACTIVE, pausedWithLiveStream);
        sendBroadcast(broadcastIntent);
    }

    private void broadcastOnRecordingStopped() {
        // Tactile confirmation that recording stopped — fired at the earliest
        // stop-confirmed point (before any heavy cleanup that could throw), so
        // it works for widgets, tiles, shortcuts and background stops too.
        com.fadcam.Utils.vibrateRecordingStop(this);
        Intent broadcastIntent = new Intent(Constants.BROADCAST_ON_RECORDING_STOPPED);
        broadcastIntent.setPackage(getPackageName());
        sendBroadcast(broadcastIntent);
        // QS tile: flip back to INACTIVE instantly (see broadcastOnRecordingStarted).
        com.fadcam.services.RecordingTileService.requestTileRefresh(this);
    }

    private void broadcastOnPreviewOnlyStarted() {
        Intent broadcastIntent = new Intent(Constants.BROADCAST_ON_PREVIEW_ONLY_STARTED);
        broadcastIntent.putExtra(Constants.EXTRA_PREVIEW_ONLY_ACTIVE, true);
        broadcastIntent.setPackage(getPackageName());
        sendBroadcast(broadcastIntent);
        FLog.d(TAG, "Broadcasted: BROADCAST_ON_PREVIEW_ONLY_STARTED");
    }

    private void broadcastOnPreviewOnlyStopped() {
        Intent broadcastIntent = new Intent(Constants.BROADCAST_ON_PREVIEW_ONLY_STOPPED);
        broadcastIntent.putExtra(Constants.EXTRA_PREVIEW_ONLY_ACTIVE, false);
        broadcastIntent.setPackage(getPackageName());
        sendBroadcast(broadcastIntent);
        FLog.d(TAG, "Broadcasted: BROADCAST_ON_PREVIEW_ONLY_STOPPED");
    }

    private void broadcastOnRecordingStateCallback() {
        Intent broadcastIntent = new Intent(Constants.BROADCAST_ON_RECORDING_STATE_CALLBACK);
        broadcastIntent.putExtra(Constants.INTENT_EXTRA_RECORDING_STATE, recordingState);
        broadcastIntent.putExtra(Constants.EXTRA_PREVIEW_ONLY_ACTIVE, previewOnlyActive);
        // Include start time so late joiners (e.g., fragment after orientation change) can restore elapsed timer
        if (recordingState == RecordingState.IN_PROGRESS || recordingState == RecordingState.PAUSED) {
            broadcastIntent.putExtra(Constants.INTENT_EXTRA_RECORDING_START_TIME, recordingStartTime);
        }
        broadcastIntent.putExtra(Constants.INTENT_EXTRA_RECORDING_PAUSE_STARTED_AT, pauseStartedAt);
        broadcastIntent.putExtra(Constants.INTENT_EXTRA_RECORDING_ACCUMULATED_PAUSED_DURATION, accumulatedPausedDurationMs);
        broadcastIntent.setPackage(getPackageName());
        sendBroadcast(broadcastIntent);
        FLog.d(TAG, "Broadcasted: BROADCAST_ON_RECORDING_STATE_CALLBACK with state: " + recordingState +
                ", startTime=" + (recordingState == RecordingState.IN_PROGRESS || recordingState == RecordingState.PAUSED ? recordingStartTime : -1));
    }
    /**
     * Announce that a split segment finished so Records and Home can pick it up while the
     * recording is still running. Segments land outside any directory the app can watch
     * (SAF / Downloads), and a FileObserver cannot see those, so this is the only signal.
     */
    private void broadcastSegmentComplete(int completedSegmentNumber) {
        try {
            Intent intent = new Intent(Constants.ACTION_RECORDING_SEGMENT_COMPLETE);
            if (currentSegmentUriString != null) {
                intent.putExtra(Constants.INTENT_EXTRA_FILE_URI, currentSegmentUriString);
            }
            if (currentSegmentPath != null) {
                intent.putExtra(Constants.INTENT_EXTRA_FILE_PATH, currentSegmentPath);
            }
            intent.putExtra(Constants.INTENT_EXTRA_SEGMENT_NUMBER, completedSegmentNumber);
            intent.setPackage(getPackageName());
            sendBroadcast(intent);
            FLog.i(TAG, "Broadcasted: ACTION_RECORDING_SEGMENT_COMPLETE for segment "
                    + completedSegmentNumber);
        } catch (Exception e) {
            FLog.w(TAG, "Failed to broadcast segment complete", e);
        }
    }

    // --- End Broadcasts ---

    // --- Notifications ---
    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
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

            NotificationManager notificationManager = getSystemService(NotificationManager.class);
            if (notificationManager != null) {
                notificationManager.createNotificationChannel(channel);
            } else {
                FLog.e(TAG, "NotificationManager service not found.");
            }
        }
    }

    private void setupRecordingInProgressNotification() {
        if (ActivityCompat.checkSelfPermission(this,
                android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            FLog.w(TAG, "POST_NOTIFICATIONS permission not granted, skipping notification setup.");
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                try {
                    // On Tiramisu+, we can start foreground without permission. Create a minimal
                    // notification.
                    NotificationCompat.Builder minimalBuilder = new NotificationCompat.Builder(this, CHANNEL_ID)
                            .setContentTitle(getString(R.string.app_name))
                            .setSmallIcon(R.drawable.ic_notification_icon);

                    // Check CAMERA and RECORD_AUDIO permissions before declaring service types
                    int foregroundServiceType = 0;
                    if (ActivityCompat.checkSelfPermission(this,
                            Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                        foregroundServiceType |= ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA;
                    } else {
                        FLog.w(TAG, "CAMERA permission not granted, starting foreground without CAMERA type");
                    }
                    if (ActivityCompat.checkSelfPermission(this,
                            Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                        foregroundServiceType |= ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
                    } else {
                        FLog.w(TAG, "RECORD_AUDIO permission not granted, starting foreground without MICROPHONE type");
                    }
                    // Optional location FGS type: only declare it when GPS data is actually
                    // needed and granted, so recording never fails on Android 14+ without it.
                    foregroundServiceType |= locationForegroundServiceType();

                    if (foregroundServiceType == 0) {
                        FLog.e(TAG, "Neither CAMERA nor RECORD_AUDIO permission granted, cannot start foreground service");
                        stopSelf();
                        return;
                    }

                    startForeground(NOTIFICATION_ID, minimalBuilder.build(), foregroundServiceType);
                    FLog.d(TAG, "Started foreground without notification permission (Tiramisu+).");
                } catch (Exception e) {
                    FLog.e(TAG, "Error starting foreground on Tiramisu+ without permission", e);
                    stopSelf();
                }
            } else {
                Toast.makeText(this, getString(R.string.toast_notification_permission), Toast.LENGTH_LONG).show();
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

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Check CAMERA and RECORD_AUDIO permissions before declaring service types (Android 14+ requirement)
            int foregroundServiceType = 0;
            if (ActivityCompat.checkSelfPermission(this,
                    Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                foregroundServiceType |= ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA;
            } else {
                FLog.w(TAG, "CAMERA permission not granted, starting foreground without CAMERA type");
            }
            if (ActivityCompat.checkSelfPermission(this,
                    Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                foregroundServiceType |= ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
            } else {
                FLog.w(TAG, "RECORD_AUDIO permission not granted, starting foreground without MICROPHONE type");
            }
            foregroundServiceType |= locationForegroundServiceType();

            if (foregroundServiceType == 0) {
                FLog.e(TAG, "Neither CAMERA nor RECORD_AUDIO permission granted, cannot start foreground service");
                stopSelf();
                return;
            }

            startForeground(NOTIFICATION_ID, immediateBuilder.build(), foregroundServiceType);
        } else {
            startForeground(NOTIFICATION_ID, immediateBuilder.build());
        }
        // STEP 2: Build the full notification with custom icon off the camera
        // handler. Posting this to backgroundHandler queued it behind camera
        // callbacks, so the notification could stay on "Initializing..." for a
        // long time. A dedicated short-lived thread also keeps bitmap decoding
        // off the main/GL thread (which caused Davey! frames).
        new Thread(() -> {
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
                                R.drawable.stop_rounded,
                                getString(R.string.button_stop),
                                createStopRecordingIntent()));
            }

            // STEP 3: Update the notification with the full content.
            try {
                NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, fullNotificationBuilder.build());
            } catch (Exception e) {
                FLog.w(TAG, "Failed to post full recording notification", e);
            }
        }, "recording-notification").start();
    }

    /**
     * Rebuilds the recording notification with the user's configured title/text
     * when returning from PAUSED. Runs off the camera handler so it applies
     * immediately instead of waiting behind camera callbacks.
     */
    private void refreshRecordingNotificationAsync() {
        if (ActivityCompat.checkSelfPermission(this,
                android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        new Thread(() -> {
            try {
                NotificationCompat.Builder builder = createBaseNotificationBuilder();
                String notificationText = sharedPreferencesManager.getNotificationText(false);
                builder.setContentText(notificationText != null ? notificationText
                        : getString(R.string.notification_video_recording_progress_description));
                if (!sharedPreferencesManager.isNotificationStopButtonHidden()) {
                    builder.clearActions().addAction(new NotificationCompat.Action(
                            R.drawable.stop_rounded,
                            getString(R.string.button_stop),
                            createStopRecordingIntent()));
                }
                NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, builder.build());
            } catch (Exception e) {
                FLog.w(TAG, "Failed to refresh recording notification", e);
            }
        }, "recording-notification-resume").start();
    }

    private void setupRecordingResumeNotification() { // Notification shown when PAUSED
        if (ActivityCompat.checkSelfPermission(this,
                android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            FLog.w(TAG, "POST_NOTIFICATIONS permission not granted, skipping notification update.");
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
                R.drawable.play_button_rounded, // Use Play icon for Resume action
                getString(R.string.button_resume),
                createResumeRecordingIntent()));

        // Add stop action only if not hidden
        if (!hideStopButton) {
            builder.addAction(new NotificationCompat.Action(
                    R.drawable.stop_rounded,
                    getString(R.string.button_stop),
                    createStopRecordingIntent()));
        }

        NotificationManagerCompat notificationManager = NotificationManagerCompat.from(this);
        notificationManager.notify(NOTIFICATION_ID, builder.build()); // Just update existing notification
    }

    private NotificationCompat.Builder createBaseNotificationBuilder() {
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

        // method(createBaseNotificationBuilder_large_icon)-----------
        // Set custom large icon based on currently selected app icon for discretion
        int largeIconResId = getCurrentAppIconResourceId();
        if (largeIconResId != cachedNotificationIconResId || cachedNotificationIconBitmap == null) {
            android.graphics.Bitmap fresh = loadNotificationLargeIconBitmap(largeIconResId);
            if (cachedNotificationIconBitmap != null) cachedNotificationIconBitmap.recycle();
            cachedNotificationIconBitmap = fresh;
            cachedNotificationIconResId = largeIconResId;
        }
        if (cachedNotificationIconBitmap != null) {
            builder.setLargeIcon(cachedNotificationIconBitmap);
        }
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
    }

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
        return sharedPreferencesManager.getAppIconResId(currentAppIcon);
    }

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
            FLog.w(TAG, "loadNotificationLargeIconBitmap: failed to render icon", t);
            return null;
        }
    }
    // method(loadNotificationLargeIconBitmap)-----------
    // method(getCurrentAppIconResourceId)-----------

    private void cancelNotification() {
        NotificationManagerCompat notificationManager = NotificationManagerCompat.from(this);
        notificationManager.cancel(NOTIFICATION_ID);
        // log removed
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
            if (recordingState == RecordingState.IN_PROGRESS
                    || recordingState == RecordingState.PAUSED
                    || previewOnlyActive) {
                try {
                    isRecordingTorchEnabled = !isRecordingTorchEnabled;
                    // Check if current camera supports flash; fall back to back camera if not
                    boolean currentHasFlash = currentCameraCharacteristics != null
                            && currentCameraCharacteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) != null
                            && currentCameraCharacteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);

                    if (currentHasFlash) {
                        captureRequestBuilder.set(CaptureRequest.FLASH_MODE,
                                isRecordingTorchEnabled ? CaptureRequest.FLASH_MODE_TORCH : CaptureRequest.FLASH_MODE_OFF);
                        captureSession.setRepeatingRequest(captureRequestBuilder.build(), null, backgroundHandler);
                    } else {
                        // Front camera without flash — use system-level torch on back camera
                        try {
                            String backId = getBackCameraId();
                            if (backId != null) {
                                cameraManager.setTorchMode(backId, isRecordingTorchEnabled);
                                FLog.d(TAG, "Front camera has no flash — using back camera torch: " + isRecordingTorchEnabled);
                            }
                        } catch (Exception camEx) {
                            FLog.e(TAG, "Failed to use back camera torch fallback", camEx);
                            isRecordingTorchEnabled = !isRecordingTorchEnabled;
                        }
                    }

                    // Broadcast state change TO THE UI so it can update its torch button
                    Intent intent = new Intent(Constants.BROADCAST_ON_TORCH_STATE_CHANGED);
                    intent.putExtra(Constants.INTENT_EXTRA_TORCH_STATE, isRecordingTorchEnabled);
                    intent.setPackage(getPackageName());
                    sendBroadcast(intent);
                    
                    // Update SharedPreferences so RemoteStreamManager can read current torch state
                    android.content.SharedPreferences prefs = getSharedPreferences(com.fadcam.Constants.PREFS_NAME, android.content.Context.MODE_PRIVATE);
                    prefs.edit()
                        .putBoolean(Constants.PREF_TORCH_STATE, isRecordingTorchEnabled)
                        .apply();

                    // Same heartbeat haptic as the torch shortcut intent, for consistency.
                    try {
                        com.fadcam.Utils.vibrateTorchShortcut(this, isRecordingTorchEnabled);
                    } catch (Exception ignored) {
                    }

                } catch (CameraAccessException e) {
                    FLog.e(TAG, "Could not toggle recording torch via CaptureRequest: " + e.getMessage());
                    isRecordingTorchEnabled = !isRecordingTorchEnabled; // Revert state on error
                } catch (IllegalStateException e) {
                    FLog.e(TAG, "Could not toggle recording torch via CaptureRequest - session/camera closed?", e);
                    isRecordingTorchEnabled = !isRecordingTorchEnabled; // Revert state on error
                }
            } else {
                FLog.w(TAG, "Cannot toggle recording torch via CaptureRequest - not IN_PROGRESS or PAUSED. State: "
                        + recordingState);
                // If not recording, HomeFragment should handle torch directly via
                // CameraManager.setTorchMode()
            }
        } else {
            FLog.w(TAG,
                    "Cannot toggle recording torch via CaptureRequest - session, request builder, or camera device is null.");
        }
    }

    /** Returns the first back-facing camera ID that supports flash, or any back camera. */
    @Nullable
    private String getBackCameraId() {
        try {
            if (cameraManager == null) return null;
            for (String id : cameraManager.getCameraIdList()) {
                CameraCharacteristics chars = cameraManager.getCameraCharacteristics(id);
                Integer facing = chars.get(CameraCharacteristics.LENS_FACING);
                if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) {
                    return id;
                }
            }
        } catch (Exception e) {
            FLog.e(TAG, "Error finding back camera", e);
        }
        return null;
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
        // CRITICAL: WAITING_FOR_CAMERA must be included - service should stay alive
        // while attempting camera reconnection after interruption (e.g., another app took camera)
        return recordingState == RecordingState.IN_PROGRESS ||
                recordingState == RecordingState.PAUSED ||
                recordingState == RecordingState.STARTING ||
                recordingState == RecordingState.WAITING_FOR_CAMERA ||
                previewOnlyActive;

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
        FLog.i(TAG,
                "handleSegmentRolloverInternal: NO-OP with setNextOutputFile. All rollover handled in OnInfoListener.");
    }

    private void proceedWithRolloverAfterOldSessionClosed() {
        FLog.i(TAG,
                "proceedWithRolloverAfterOldSessionClosed: NO-OP with setNextOutputFile. All rollover handled in OnInfoListener.");
    }

    @Nullable
    private File resolveInternalRecordingVideoDir(boolean isStreamAndSave) {
        if (isStreamAndSave) {
            return RecordingStoragePaths.getInternalCategoryDir(this, RecordingStoragePaths.Category.STREAM, true);
        }
        return RecordingStoragePaths.getInternalCameraSourceDir(this, recordingSessionCameraSource, true);
    }

    @Nullable
    private DocumentFile resolveSafRecordingVideoDir(@Nullable String customUriString, boolean isStreamAndSave) {
        if (customUriString == null || customUriString.isEmpty()) return null;
        if (isStreamAndSave) {
            return RecordingStoragePaths.getSafCategoryDir(
                    this,
                    customUriString,
                    RecordingStoragePaths.Category.STREAM,
                    true);
        }
        return RecordingStoragePaths.getSafCameraSourceDir(this, customUriString, recordingSessionCameraSource, true);
    }

    private File createNextSegmentOutputFile(int nextSegmentNumber) {
        String storageMode = sharedPreferencesManager.getStorageMode();
        
        // Use "Stream_" prefix only if streaming is actually enabled (server running)
        boolean isStreamingActive = com.fadcam.FeatureRegistry.streaming().isStreamingEnabled();
        com.fadcam.StreamingMode streamingMode = 
            com.fadcam.FeatureRegistry.streaming().getStreamingMode();
        boolean isStreamAndSave = isStreamingActive && (streamingMode == com.fadcam.StreamingMode.STREAM_AND_SAVE);
        String filenamePrefix = isStreamAndSave ? "Stream_" : Constants.RECORDING_DIRECTORY + "_";
        
        if (SharedPreferencesManager.STORAGE_MODE_CUSTOM.equals(storageMode)) {
            // SAF/DocumentFile mode
            String customUriString = sharedPreferencesManager.getCustomStorageUri();
            if (customUriString == null) {
                FLog.e(TAG, "createNextSegmentOutputFile: Custom storage selected but URI is null");
                return null;
            }
            DocumentFile pickedDir = resolveSafRecordingVideoDir(customUriString, isStreamAndSave);
            if (pickedDir == null || !pickedDir.canWrite()) {
                FLog.e(TAG, "createNextSegmentOutputFile: Cannot write to selected custom directory");
                return null;
            }
            String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
            String segmentSuffix = String.format(Locale.US, "_part%d", nextSegmentNumber);
            String baseFilename = filenamePrefix + timestamp + segmentSuffix + "."
                    + Constants.RECORDING_FILE_EXTENSION;
            DocumentFile nextDocFile = pickedDir.createFile("video/" + Constants.RECORDING_FILE_EXTENSION,
                    baseFilename);
            if (nextDocFile == null || !nextDocFile.exists()) {
                FLog.e(TAG, "createNextSegmentOutputFile: Failed to create DocumentFile in SAF: " + baseFilename);
                return null;
            }
            try {
                // Open a ParcelFileDescriptor for the next segment
                ParcelFileDescriptor pfd = getContentResolver().openFileDescriptor(nextDocFile.getUri(), "w");
                if (pfd == null) {
                    FLog.e(TAG, "createNextSegmentOutputFile: Failed to open ParcelFileDescriptor for next SAF URI: "
                            + nextDocFile.getUri());
                    return null;
                }
                pfd.close(); // Immediately close, as the pipeline will open as needed
            } catch (Exception e) {
                FLog.e(TAG, "createNextSegmentOutputFile: Exception opening PFD for next SAF URI", e);
                return null;
            }
            FLog.i(TAG, "Next segment SAF file created: " + nextDocFile.getUri());
            return null;
        } else {
            // Internal storage mode - Use same directory and naming as first segment
            String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
            String segmentSuffix = String.format(Locale.US, "_part%d", nextSegmentNumber);
            String baseFilename = filenamePrefix + timestamp + segmentSuffix + "."
                    + Constants.RECORDING_FILE_EXTENSION;

            // Use the same directory as the first segment (app's external files directory)
            File videoDir = resolveInternalRecordingVideoDir(isStreamAndSave);
            if (videoDir == null) {
                FLog.e(TAG, "Cannot create recording directory for split segment");
                Toast.makeText(this, getString(R.string.toast_create_recording_dir_error), Toast.LENGTH_LONG).show();
                return null;
            }

            // Create the file directly in the final directory
            File nextFile = new File(videoDir, baseFilename);
            FLog.i(TAG, "Next segment file created: " + nextFile.getAbsolutePath());
            return nextFile;
        }
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
        // log removed

        // Broadcast the current availability state
        broadcastCameraResourceAvailability(!releasing);

        // If we're releasing resources, schedule them to become available after a
        // cooldown
        if (releasing) {
            mainHandler.postDelayed(() -> {
                isCameraResourceReleasing = false;
                // log removed

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
        availabilityIntent.setPackage(getPackageName());
        sendBroadcast(availabilityIntent);
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
            FLog.e(TAG, "METADATA_CHECK: Video file path is null");
            return;
        }

        FLog.d(TAG, "METADATA_CHECK: Checking for location metadata in " + videoFilePath);

        try {
            MediaMetadataRetriever retriever = new MediaMetadataRetriever();
            retriever.setDataSource(videoFilePath);

            // Get location data
            String locationString = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_LOCATION);

            if (locationString != null && !locationString.isEmpty()) {
                FLog.d(TAG, "METADATA_CHECK: Found location data in video: " + locationString);
            } else {
                FLog.e(TAG, "METADATA_CHECK: No location metadata found in processed video!");
                FLog.e(TAG, "METADATA_CHECK: This suggests the metadata was lost during processing");
            }

            // Check a few other metadata fields to see if any metadata is preserved
            String duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            String width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH);
            String height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT);

            FLog.d(TAG, "METADATA_CHECK: Other metadata - Duration: " + duration +
                    ", Resolution: " + width + "x" + height);

            retriever.release();
        } catch (Exception e) {
            FLog.e(TAG, "METADATA_CHECK: Error checking metadata", e);
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
        FLog.d(TAG, "==== Reinitializing Location Helpers ====");
        FLog.d(TAG, "Current preferences: location=" + sharedPreferencesManager.isLocalisationEnabled() +
                ", embedding=" + sharedPreferencesManager.isLocationEmbeddingEnabled());

        // Handle LocationHelper for watermark
        // Driven by ANY location-dependent feature (speed/altitude/accuracy/
        // compass/weather/UTM/location/embedding), not just the location
        // watermark toggle — otherwise toggling "location watermark" off while
        // speed stays on but thiswould kill the GPS feed and zero the speed (user bug).
        boolean locationEnabled = needsLocationData();
        if (locationEnabled) {
            if (locationHelper == null || forceInit) {
                try {
                    // Clean up existing helper if needed
                    if (locationHelper != null) {
                        locationHelper.stopLocationUpdates();
                    }

                    locationHelper = new LocationHelper(this);
                    // Also initialize geocoder for reverse geocoding
                    // Uses Nominatim (free, open-source) - no API keys needed
                    if (locationGeocoder != null) {
                        locationGeocoder.shutdown();
                    }
                    locationGeocoder = new LocationGeocoder();
                    FLog.d(TAG, "Created new LocationHelper and LocationGeocoder for watermark");
                } catch (Exception e) {
                    FLog.e(TAG, "Error initializing LocationHelper/Geocoder", e);
                }
            } else {
                FLog.d(TAG, "LocationHelper already exists, no need to recreate");
            }
        } else {
            if (locationHelper != null) {
                try {
                    locationHelper.stopLocationUpdates();
                    FLog.d(TAG, "Stopped existing LocationHelper");
                    locationHelper = null;
                } catch (Exception e) {
                    FLog.e(TAG, "Error stopping LocationHelper", e);
                }
            }
            if (locationGeocoder != null) {
                try {
                    locationGeocoder.shutdown();
                    locationGeocoder = null;
                    FLog.d(TAG, "Shutdown LocationGeocoder");
                } catch (Exception e) {
                    FLog.e(TAG, "Error shutting down LocationGeocoder", e);
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
                    FLog.d(TAG, "Created new GeotagHelper for metadata embedding. Updates started: " + started);
                } catch (Exception e) {
                    FLog.e(TAG, "Error initializing GeotagHelper", e);
                }
            } else {
                FLog.d(TAG, "GeotagHelper already exists, ensuring updates are started");
                // Make sure updates are started even if helper exists
                try {
                    boolean started = geotagHelper.startUpdates();
                    FLog.d(TAG, "Ensured GeotagHelper updates are active: " + started);
                } catch (Exception e) {
                    FLog.e(TAG, "Error starting GeotagHelper updates", e);
                }
            }

        } else {
            if (geotagHelper != null) {
                try {
                    geotagHelper.stopUpdates();
                    FLog.d(TAG, "Stopped existing GeotagHelper");
                    geotagHelper = null;
                } catch (Exception e) {
                    FLog.e(TAG, "Error stopping GeotagHelper", e);
                }
            }
        }

        // Keep the shared watermark manager on the same helper (or release it when all
        // location features were switched off mid-session).
        if (watermarkManager != null) {
            watermarkManager.updateLocationHelper(locationHelper);
        }

        FLog.d(TAG, "==== Location Helpers Reinitialization Complete ====");
    }

    /**
     * True if any user-enabled feature needs a live GPS feed. See
     * {@link LocationFeedGate#needsLocationData}.
     */
    private boolean needsLocationData() {
        return LocationFeedGate.needsLocationData(sharedPreferencesManager);
    }

    /**
     * Adds the location foreground-service type only when GPS is actually needed and the
     * runtime permission exists. Declaring it unconditionally would fail the foreground
     * start on Android 14+ for users who keep location features disabled.
     */
    private int locationForegroundServiceType() {
        if (sharedPreferencesManager == null || !needsLocationData()) return 0;
        boolean fine = ActivityCompat.checkSelfPermission(this,
                Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        boolean coarse = ActivityCompat.checkSelfPermission(this,
                Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        return (fine || coarse) ? ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION : 0;
    }

    /**
     * Creates a high-speed constrained capture session for 60fps+ recording
     */
    @SuppressWarnings("deprecation") // createConstrainedHighSpeedCaptureSession has no equivalent SessionConfiguration path that preserves per-surface high-speed config on all API levels
    private void createHighSpeedSession(List<Surface> surfaces, CameraCharacteristics characteristics,
            int targetFrameRate, CameraType cameraType) {
        try {
            // For high-speed recording, we need a constrained high-speed capture session
            FLog.d(TAG, "Creating constrained high-speed session for " + targetFrameRate + "fps");

            // Get the best size for high-speed recording
            Size highSpeedSize = HighSpeedCaptureHelper.getBestHighSpeedSize(
                    characteristics, targetFrameRate, 0, 0);

            if (highSpeedSize == null) {
                FLog.d(TAG, "No suitable high-speed size found");
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
                FLog.w(TAG, "Failed to apply saved camera prefs to high-speed builder: " + e.getMessage());
            }

            if (captureRequestBuilder == null) {
                FLog.d(TAG, "Failed to create high-speed request builder");
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

            FLog.d(TAG, "Requested constrained high-speed capture session creation");
        } catch (Exception e) {
            FLog.e(TAG, "Failed to create high-speed session", e);
            // Fallback to standard session
            try {
                createStandardSession(surfaces, targetFrameRate, characteristics, cameraType);
            } catch (Exception e2) {
                FLog.e(TAG, "Failed to create fallback standard session", e2);
                stopRecording();
            }
        }
    }

    /**
     * Fallback to create a standard session with the best possible frame rate
     * settings
     */
    @SuppressWarnings("deprecation") // legacy createCaptureSession fallback for < API 28
    private void createStandardSession(List<Surface> surfaces, int targetFrameRate,
            CameraCharacteristics characteristics, CameraType cameraType) {
        try {
            // A fresh session starts from the full active array; drop any stale
            // crop window from a previous session so tap-to-focus stays aligned.
            lastCropRegion = null;
            // Create standard request builder
            captureRequestBuilder = cameraDevice.createCaptureRequest(
                    previewOnlyActive ? CameraDevice.TEMPLATE_PREVIEW : CameraDevice.TEMPLATE_RECORD);

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
            } catch (Exception e) {
                FLog.w(TAG, "Failed to apply saved camera prefs to standard builder: " + e.getMessage());
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
            if (previewOnlyActive) {
                previewSessionConfigInFlight = true;
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                java.util.List<android.hardware.camera2.params.OutputConfiguration> outputs =
                        new java.util.ArrayList<>();
                for (android.view.Surface s : surfaces) {
                    outputs.add(new android.hardware.camera2.params.OutputConfiguration(s));
                }
                android.hardware.camera2.params.SessionConfiguration sessionConfig =
                        new android.hardware.camera2.params.SessionConfiguration(
                                android.hardware.camera2.params.SessionConfiguration.SESSION_REGULAR,
                                outputs,
                                backgroundHandler::post,
                                captureSessionCallback);
                cameraDevice.createCaptureSession(sessionConfig);
            } else {
                cameraDevice.createCaptureSession(surfaces, captureSessionCallback, backgroundHandler);
            }
        } catch (Exception e) {
            FLog.e(TAG, "Failed to create standard session", e);
            previewSessionConfigInFlight = false;
            if (previewOnlyActive) {
                stopPreviewOnlyMode();
            } else {
                stopRecording();
            }
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

            int savedEv = sharedPreferencesManager.getSavedExposureCompensation();
            Boolean aeLock = sharedPreferencesManager.isAeLockedSaved();
            int afModePref = sharedPreferencesManager.getSavedAfMode();

            // camera pref log removed : savedEv=" + savedEv + ", aeLock=" + aeLock +

            if (currentCameraCharacteristics != null) {
                // Apply EV: use runtime value if available, otherwise saved value
                Range<Integer> range = currentCameraCharacteristics
                        .get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE);
                if (range != null) {
                    int evToUse = (runtimeExposureCompensation != null) ? runtimeExposureCompensation : savedEv;
                    int clamped = Math.max(range.getLower(), Math.min(range.getUpper(), evToUse));
                    builder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, clamped);

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
                    }
                } else {
                    FLog.w(TAG, "EV compensation range not available from camera characteristics");
                }

                // Apply AE lock: use runtime value if available, otherwise saved value
                Boolean aeLockSupported = currentCameraCharacteristics
                        .get(CameraCharacteristics.CONTROL_AE_LOCK_AVAILABLE);
                if (aeLockSupported != null && aeLockSupported) {
                    boolean lockToUse = (runtimeAeLock != null) ? runtimeAeLock : (aeLock != null && aeLock);
                    if (lockToUse && runtimeAeLock == null) {
                        // Fresh session + saved lock=true: let AE converge with EV first, then lock.
                        builder.set(CaptureRequest.CONTROL_AE_LOCK, false);
                        pendingDeferredAeLockEnable = true;
                        FLog.d(TAG, "Deferring saved AE lock enable until after repeating starts");
                    } else {
                        builder.set(CaptureRequest.CONTROL_AE_LOCK, lockToUse);
                        pendingDeferredAeLockEnable = false;
                    }
                } else {
                    FLog.w(TAG, "AE lock not supported by camera characteristics");
                    pendingDeferredAeLockEnable = false;
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
                    } else {
                        FLog.w(TAG, "AF mode " + afModeToUse + " not supported, available modes: "
                                + java.util.Arrays.toString(modes));
                    }
                } else {
                    FLog.w(TAG, "AF modes not available from camera characteristics");
                }

                // Video stabilization (EIS + OIS). Safe no-op when unsupported.
                applyVideoStabilization(builder);
            } else {
                FLog.e(TAG, "applySavedCameraPrefsToBuilder: currentCameraCharacteristics is null!");
            }
        } catch (Exception e) {
            FLog.w(TAG, "Error applying camera prefs: " + e.getMessage());
        }
    }

    /**
     * Applies video stabilization (EIS + OIS) to the capture request when the
     * user preference is enabled AND the hardware exposes the mode. Never
     * throws — every failure path falls back to stabilization OFF so a quirky
     * HAL can never take the camera down.
     *
     * Tagged "FadCamEIS" for logcat grepping to verify the feature is active.
     */
    private void applyVideoStabilization(CaptureRequest.Builder builder) {
        try {
            if (builder == null || currentCameraCharacteristics == null) {
                FLog.w("FadCamEIS", "applyVideoStabilization skipped (builder/chars null)");
                return;
            }
            boolean prefEnabled = sharedPreferencesManager != null
                    && sharedPreferencesManager.isVideoStabilizationEnabled();

            boolean eisSupported = false;
            int[] stabModes = currentCameraCharacteristics.get(
                    CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES);
            if (stabModes != null) {
                for (int m : stabModes) {
                    if (m == CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON) {
                        eisSupported = true;
                        break;
                    }
                }
            }
            int eisMode = (prefEnabled && eisSupported)
                    ? CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON
                    : CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF;
            builder.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, eisMode);

            boolean oisSupported = false;
            int[] oisModes = currentCameraCharacteristics.get(
                    CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION);
            if (oisModes != null) {
                for (int m : oisModes) {
                    if (m == CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON) {
                        oisSupported = true;
                        break;
                    }
                }
            }
            int oisMode = (prefEnabled && oisSupported)
                    ? CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON
                    : CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_OFF;
            builder.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, oisMode);

            FLog.i("FadCamEIS", "VideoStab applied -> pref=" + prefEnabled
                    + " eisSupported=" + eisSupported + " eis=" + eisMode
                    + " oisSupported=" + oisSupported + " ois=" + oisMode
                    + " cameraId=" + (cameraDevice != null ? cameraDevice.getId() : "null"));
        } catch (Throwable t) {
            // Catch Throwable: a stabilization bug must never crash the recorder.
            FLog.w("FadCamEIS", "applyVideoStabilization failed, falling back to OFF: " + t.getMessage());
        }
    }

    /**
     * Re-applies the current stabilization preference to an already-running
     * session (e.g. after the user toggles it in Settings mid-recording).
     * Fully guarded — never throws.
     */
    private void reapplyVideoStabilizationToSession() {
        try {
            if (captureSession == null || captureRequestBuilder == null
                    || cameraDevice == null || isStopping) {
                FLog.w("FadCamEIS", "reapply skipped (session/builder/device not ready)");
                return;
            }
            applyVideoStabilization(captureRequestBuilder);
            captureSession.setRepeatingRequest(
                    captureRequestBuilder.build(), null, backgroundHandler);
            FLog.i("FadCamEIS", "Repeating request re-issued with new stabilization state");
        } catch (Throwable t) {
            FLog.w("FadCamEIS", "reapplyVideoStabilizationToSession failed safely: " + t.getMessage());
        }
    }

    private void maybeApplyDeferredAeLockAfterSessionStart(String sessionType) {
        if (!pendingDeferredAeLockEnable || backgroundHandler == null) {
            return;
        }
        pendingDeferredAeLockEnable = false;
        backgroundHandler.postDelayed(() -> {
            try {
                if (captureSession == null || captureRequestBuilder == null || currentCameraCharacteristics == null) {
                    return;
                }
                boolean shouldLock = (runtimeAeLock != null)
                        ? runtimeAeLock
                        : (sharedPreferencesManager != null && sharedPreferencesManager.isAeLockedSaved());
                if (!shouldLock) {
                    return;
                }
                FLog.d(TAG, "Applying deferred AE lock after " + sessionType + " session start");
                applyAeLock(true);
            } catch (Exception e) {
                FLog.w(TAG, "Deferred AE lock apply failed", e);
            }
        }, 900);
    }

    private boolean tryApplyManualExposureLock(Long exposureTimeNs, Integer iso) {
        if (captureRequestBuilder == null || captureSession == null || currentCameraCharacteristics == null
                || exposureTimeNs == null || iso == null) {
            return false;
        }
        try {
            int[] aeModes = currentCameraCharacteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES);
            boolean aeOffSupported = false;
            if (aeModes != null) {
                for (int mode : aeModes) {
                    if (mode == CaptureRequest.CONTROL_AE_MODE_OFF) {
                        aeOffSupported = true;
                        break;
                    }
                }
            }
            Range<Long> expRange = currentCameraCharacteristics.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE);
            Range<Integer> isoRange = currentCameraCharacteristics.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE);
            if (!aeOffSupported || expRange == null || isoRange == null) {
                return false;
            }

            long clampedExp = Math.max(expRange.getLower(), Math.min(expRange.getUpper(), exposureTimeNs));
            int clampedIso = Math.max(isoRange.getLower(), Math.min(isoRange.getUpper(), iso));

            captureRequestBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF);
            captureRequestBuilder.set(CaptureRequest.CONTROL_AE_LOCK, false);
            captureRequestBuilder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, clampedExp);
            captureRequestBuilder.set(CaptureRequest.SENSOR_SENSITIVITY, clampedIso);
            captureSession.setRepeatingRequest(captureRequestBuilder.build(), null, backgroundHandler);
            manualAeLockActive = true;
            FLog.d(TAG, "Manual AE lock applied: exposureNs=" + clampedExp + ", iso=" + clampedIso);
            return true;
        } catch (Exception e) {
            FLog.w(TAG, "Manual AE lock unsupported/failed; falling back to AE lock", e);
            return false;
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
            FLog.w(TAG, "Failed to choose AE FPS range from characteristics", e);
        }
        if (chosen == null) {
            chosen = new android.util.Range<>(targetFrameRate, targetFrameRate);
        }
        builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, chosen);

        // Apply Samsung-specific keys if applicable
        // Do not apply Samsung vendor keys: unify behavior with standard devices
        // (Pixel-like)

        // Unified behavior: no Huawei/Samsung vendor keys; rely on AE range only.
    }

    /**
     * Apply zoom settings for the specified camera type
     */
    private void applyZoomSettings(CaptureRequest.Builder builder, CameraType cameraType) {
        // Get zoom ratio from settings for the specific camera type
        float zoomRatio = sharedPreferencesManager.getSpecificZoomRatio(cameraType);
        FLog.d(TAG, "applyZoomSettings: type=" + cameraType + " savedZoom=" + zoomRatio);

        // Apply zoom ratio to the capture request
        // CONTROL_ZOOM_RATIO was added in Android 11 (API 30)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            builder.set(CaptureRequest.CONTROL_ZOOM_RATIO, zoomRatio);
        }
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
            // Just log the high frame rate usage
            if (DeviceHelper.isSamsung()) {
            } else {
            }
        }
    }

    /**
     * Helper method to get the proper camera ID based on camera type.
     * Only supports FRONT and BACK — DUAL_PIP is handled by DualCameraRecordingService.
     */
    private String getCameraId(CameraManager cameraManager, CameraType cameraType) {
        if (cameraManager == null)
            return null;

        // DUAL_PIP should never reach RecordingService; route through DualCameraRecordingService instead.
        if (cameraType != null && cameraType.isDual()) {
            FLog.w(TAG, "getCameraId called with DUAL_PIP — falling back to BACK camera ID");
            cameraType = CameraType.BACK;
        }

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
            FLog.e(TAG, "Error finding camera ID", e);
        }

        return null;
    }

    /**
     * Handle common tasks after any session is configured
     */
    private void handleSessionConfigured() {
        if (previewOnlyActive && recordingState == RecordingState.NONE) {
            return;
        }
        // Handle recording states
        if (recordingState == RecordingState.STARTING) {
            try {
                // Start the MediaRecorder

                recordingState = RecordingState.IN_PROGRESS;

                // Use SystemClock.elapsedRealtime() instead of System.currentTimeMillis() for
                // consistency with HomeFragment
                recordingStartTime = SystemClock.elapsedRealtime();
                pauseStartedAt = 0L;
                accumulatedPausedDurationMs = 0L;

                persistRecordingTimelineState();
                startDurationLimitSession();

                // Tactile confirmation that recording actually started — fired
                // from the SERVICE so it works for widgets, tiles, shortcuts and
                // background starts too (gated by the Haptic feedback setting).
                com.fadcam.Utils.vibrateRecordingStart(this);

                // Setup notification
                setupRecordingInProgressNotification();

                // Broadcast that recording has started
                broadcastOnRecordingStarted();

                // Notify RemoteStreamManager about active recording file
                if (currentSegmentFile != null) {
                    try {
                        com.fadcam.FeatureRegistry.streaming().startRecording(currentSegmentFile);
                        FLog.i(TAG, "🎬 RemoteStreamManager notified: recording started");
                    } catch (Exception e) {
                        FLog.e(TAG, "Failed to notify RemoteStreamManager about recording start", e);
                    }
                }
                if (motionLabEnabledForSession) {
                    // In Motion Lab, arm in paused state first and let motion transitions resume recording.
                    FLog.i(TAG, "MotionLab armed: entering paused state until trigger");
                    motionAutoPaused = true;
                    pauseRecording();
                    if (sharedPreferencesManager != null && sharedPreferencesManager.isMotionAutoTorchEnabled()) {
                        setMotionTorchState(false);
                    }
                }
            } catch (Exception e) {
                FLog.e(TAG, "Failed to start recording", e);
                stopRecording();
            }
        } else if (recordingState == RecordingState.IN_PROGRESS) {
            // This typically means the session was reconfigured
            setupRecordingInProgressNotification();
        } else if (recordingState == RecordingState.PAUSED) {
            // Handle paused state
            FLog.d(TAG, "Session configured while in PAUSED state");
            setupRecordingResumeNotification();
        }
    }

    private void stopPreviewOnlyMode() {
        pendingPreviewOnlyStart = false;
        if (!previewOnlyActive) {
            return;
        }
        previewOnlyActive = false;
        releasePreviewOnlyGlPipeline();
        try {
            if (captureSession != null) {
                try {
                    captureSession.stopRepeating();
                } catch (Exception ignore) {
                }
                captureSession.close();
                captureSession = null;
            }
            if (cameraDevice != null) {
                cameraDevice.close();
                cameraDevice = null;
            }
            isCameraOpen = false;
        } catch (Exception e) {
            FLog.w(TAG, "Failed while stopping preview-only mode", e);
        }
        broadcastOnPreviewOnlyStopped();
        if (!isWorkingInProgress()) {
            stopSelf();
        }
    }

    private boolean samePreviewShape(String a, String b) {
        // Shape and rotation are what the pipeline bakes in; "auto" resolves to a concrete
        // shape before it reaches here, so this only has to compare the resolved values.
        return SharedPreferencesManager.isLandscapeOrientation(a)
                == SharedPreferencesManager.isLandscapeOrientation(b)
                && SharedPreferencesManager.isInvertedOrientation(a)
                == SharedPreferencesManager.isInvertedOrientation(b);
    }

    /**
     * Closes the camera session bound to the current preview pipeline so a rebuild can open a
     * new one. This deliberately does NOT go through {@link #stopPreviewOnlyMode()}: that path
     * calls {@code stopSelf()} once preview is marked inactive, which would tear the service
     * down while it is in the middle of restarting the preview.
     */
    private void closeCameraSessionForPreviewRestart() {
        try {
            if (captureSession != null) {
                try {
                    captureSession.stopRepeating();
                } catch (Exception ignore) {
                }
                captureSession.close();
                captureSession = null;
            }
            if (cameraDevice != null) {
                cameraDevice.close();
                cameraDevice = null;
            }
            isCameraOpen = false;
        } catch (Exception e) {
            FLog.w(TAG, "Failed to close camera session for preview restart", e);
        }
    }

    /**
     * Rebuilds the preview pipeline so it matches the orientation setting in force now.
     *
     * <p>The pipeline bakes its shape in when it is constructed (encoder dimensions, renderer
     * matrix), so a running preview cannot follow a changed setting on its own. Only call this
     * while nothing is being recorded: a recording keeps the shape its file started with,
     * because an encoder cannot change dimensions mid-file.</p>
     */
    private void restartPreviewOnlyForOrientationChange() {
        if (recordingState != RecordingState.NONE) {
            FLog.d(TAG, "Recording in progress; keeping the shape the file started with");
            return;
        }
        releasePreviewOnlyGlPipeline();
        closeCameraSessionForPreviewRestart();
        if (previewSurface != null && previewSurface.isValid()) {
            ensurePreviewOnlyGlPipeline();
            openCamera();
            broadcastOnPreviewOnlyStarted();
        } else {
            pendingPreviewOnlyStart = true;
        }
    }

    private void ensurePreviewOnlyGlPipeline() {
        try {
            ensureWatermarkInfoProvider();
            if (glRecordingPipeline != null) {
                // The pipeline freezes the shape it was built with, so a reused one keeps the
                // old aspect on screen even after the setting changed — the card and the
                // picture then disagree, which is what made the preview look stretched. Drop it
                // and fall through to build one that matches the setting in force now.
                String wanted = SharedPreferencesManager.resolveShape(
                        sharedPreferencesManager.getVideoOrientation(), this);
                if (!samePreviewShape(wanted, glRecordingPipeline.getOrientation())) {
                    FLog.d(TAG, "Preview orientation changed (" + glRecordingPipeline.getOrientation()
                            + " -> " + wanted + "); rebuilding preview pipeline");
                    releasePreviewOnlyGlPipeline();
                    closeCameraSessionForPreviewRestart();
                } else {
                    if (previewSurface != null && previewSurface.isValid()) {
                        glRecordingPipeline.setPreviewSurfaceImmediate(previewSurface);
                    }
                    glRecordingPipeline.startPreviewOnlyRendering();
                    glRecordingPipeline.updateWatermark();
                    return;
                }
            }

            Size resolution = sharedPreferencesManager.getCameraResolution();
            int videoWidth = resolution.getWidth();
            int videoHeight = resolution.getHeight();
            CameraType cameraType = sharedPreferencesManager.getCameraSelection();
            int videoFramerate = sharedPreferencesManager.getSpecificVideoFrameRate(cameraType);
            // Resolved once here and frozen for the session: Auto decides the shape at
            // capture start and the encoder cannot change shape mid-recording.
            String orientation = SharedPreferencesManager.resolveShape(
                    sharedPreferencesManager.getVideoOrientation(), this);
            VideoCodec selectedCodec = sharedPreferencesManager.getVideoCodec();

            int sensorOrientation = 0;
            try {
                CameraManager cameraManager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
                String cameraId = getCameraId(cameraManager, cameraType);
                if (cameraId != null) {
                    CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(cameraId);
                    Integer so = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
                    if (so != null) sensorOrientation = so;
                }
            } catch (Exception e) {
                FLog.w(TAG, "Preview-only: failed to read sensor orientation", e);
            }

            File previewTempFile = new File(getCacheDir(),
                    "preview_gl_temp_" + System.currentTimeMillis() + ".mp4");
            glRecordingPipeline = new com.fadcam.opengl.GLRecordingPipeline(
                    this,
                    watermarkInfoProvider,
                    videoWidth,
                    videoHeight,
                    videoFramerate,
                    previewTempFile.getAbsolutePath(),
                    0L,
                    1,
                    new GLSegmentCallback(),
                    previewSurface,
                    orientation,
                    sensorOrientation,
                    selectedCodec,
                    null,
                    null
            );
            glRecordingPipeline.prepareSurfaces();
            glRecordingPipeline.startPreviewOnlyRendering();
            glRecordingPipeline.updateWatermark();
            FLog.d(TAG, "Preview-only GL pipeline initialized");
        } catch (Exception e) {
            FLog.e(TAG, "Failed to initialize preview-only GL pipeline", e);
            releasePreviewOnlyGlPipeline();
        }
    }

    private void releasePreviewOnlyGlPipeline() {
        if (glRecordingPipeline == null) return;
        try {
            glRecordingPipeline.stopPreviewOnlyRendering();
        } catch (Exception e) {
            FLog.w(TAG, "Error stopping preview-only renderer", e);
        }
        try {
            glRecordingPipeline.stopRecording();
        } catch (Exception e) {
            FLog.w(TAG, "Error releasing preview-only GL pipeline", e);
        }
        glRecordingPipeline = null;
    }

    // Add these fields to RecordingService class
    private GLRecordingPipeline glRecordingPipeline;
    private WatermarkInfoProvider watermarkInfoProvider;
    private com.fadcam.watermark.WatermarkManager watermarkManager;
    
    // Track current segment file for streaming
    private File currentSegmentFile;
    private String currentSegmentPath;
    private String currentSegmentUriString;
    
    // ⚠️ CRITICAL: Track ParcelFileDescriptors for SAF segments to prevent premature closure
    // The try-with-resources was auto-closing the PFD while the raw FileDescriptor was still
    // being used by the GL pipeline, causing EBADF (Bad File Descriptor) errors on write.
    // Keep TWO references:
    // - currentSegmentParcelFileDescriptor: NEW PFD that GL pipeline will use
    // - previousSegmentParcelFileDescriptor: OLD PFD that old muxer is still finalizing
    // Only close previousSegmentParcelFileDescriptor AFTER old muxer is completely stopped
    private ParcelFileDescriptor currentSegmentParcelFileDescriptor;
    private ParcelFileDescriptor previousSegmentParcelFileDescriptor;
    
    // Track all temporary files created during STREAM_ONLY mode for cleanup
    // In STREAM_ONLY, 0-byte segment files are created (GL pipeline needs a handle)
    // but no data is written. All of them must be deleted when recording stops.
    private final java.util.List<File> streamOnlyTempFiles = new java.util.ArrayList<>();
    private final java.util.List<String> streamOnlySafUris = new java.util.ArrayList<>();
    
    // Add this helper method for OpenGL pipeline direct output
    private File getFinalOutputFile() {
        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
        String segmentSuffix = ""; // No segment number for the initial file
        
        // Use "Stream_" prefix only if streaming is actually enabled (server running)
        boolean isStreamingActive = com.fadcam.FeatureRegistry.streaming().isStreamingEnabled();
        com.fadcam.StreamingMode streamingMode = 
            com.fadcam.FeatureRegistry.streaming().getStreamingMode();
        boolean isStreamAndSave = isStreamingActive && (streamingMode == com.fadcam.StreamingMode.STREAM_AND_SAVE);
        String filenamePrefix = isStreamAndSave ? "Stream_" : Constants.RECORDING_DIRECTORY + "_";
        String baseFilename = filenamePrefix + timestamp + segmentSuffix + "."
                + Constants.RECORDING_FILE_EXTENSION;
        File videoDir = resolveInternalRecordingVideoDir(isStreamAndSave);
        if (videoDir == null) {
            FLog.e(TAG, "Cannot create internal recording directory for active session");
            Toast.makeText(this, getString(R.string.toast_create_storage_dir_error), Toast.LENGTH_LONG).show();
            return null;
        }
        return new File(videoDir, baseFilename);
    }

    // Inner class for OpenGL pipeline segment callback
    private class GLSegmentCallback implements com.fadcam.opengl.GLRecordingPipeline.SegmentCallback {
        @Override
        public void onSegmentRollover(int nextSegmentNumber) {
            FLog.d(TAG, "GLSegmentCallback onSegmentRollover called for segment "
                    + nextSegmentNumber);
            // The completed segment is still what currentSegment* points at: the pipeline
            // closes the old muxer before invoking this callback and only assigns the next
            // segment further down. Announce it now, because the file observers cannot:
            // RealtimeMediaInvalidationCoordinator only walks getExternalFilesDir(), so a
            // custom SAF or Downloads folder comes into play without any observer at all.
            broadcastSegmentComplete(nextSegmentNumber - 1);
            String storageMode = sharedPreferencesManager.getStorageMode();
            VideoCodec selectedCodec = sharedPreferencesManager.getVideoCodec();
            if (SharedPreferencesManager.STORAGE_MODE_CUSTOM.equals(storageMode)) {
                FLog.d(TAG, "Using custom storage mode (SAF) for segment rollover");
                // SAF: create new file and open new ParcelFileDescriptor
                String customUriString = sharedPreferencesManager.getCustomStorageUri();
                if (customUriString == null) {
                    FLog.e(TAG, "Segment rollover: Custom storage selected but URI is null");
                    stopRecording();
                    return;
                }
                boolean isStreamingActive = com.fadcam.FeatureRegistry.streaming().isStreamingEnabled();
                com.fadcam.StreamingMode streamingMode =
                        com.fadcam.FeatureRegistry.streaming().getStreamingMode();
                boolean isStreamAndSave = isStreamingActive
                        && (streamingMode == com.fadcam.StreamingMode.STREAM_AND_SAVE);
                androidx.documentfile.provider.DocumentFile pickedDir = resolveSafRecordingVideoDir(
                        customUriString, isStreamAndSave);
                if (pickedDir == null || !pickedDir.canWrite()) {
                    FLog.e(TAG, "Segment rollover: Cannot write to selected custom directory");
                    stopRecording();
                    return;
                }
                String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
                String segmentSuffix = String.format(Locale.US, "_part%d", nextSegmentNumber);
                String filenamePrefix = isStreamAndSave ? "Stream_" : Constants.RECORDING_DIRECTORY + "_";
                String baseFilename = filenamePrefix + timestamp + segmentSuffix + ".mp4";
                FLog.d(TAG, "Creating new segment file: " + baseFilename);
                androidx.documentfile.provider.DocumentFile videoFile = pickedDir.createFile("video/mp4", baseFilename);
                if (videoFile == null) {
                    FLog.e(TAG, "Segment rollover: Failed to create SAF file");
                    stopRecording();
                    return;
                }
                Uri safUri = videoFile.getUri();
                currentSegmentUriString = safUri.toString();
                currentSegmentPath = safUri.toString();
                currentSegmentFile = null;
                com.fadcam.ActiveRecordingStats.rolloverToNewSegment(null, safUri.toString(), RecordingService.this);
                // Track SAF URI for STREAM_ONLY cleanup during segment rollover
                boolean isStreamOnlyRollover = isStreamingActive
                        && (streamingMode == com.fadcam.StreamingMode.STREAM_ONLY);
                if (isStreamOnlyRollover) {
                    streamOnlySafUris.add(safUri.toString());
                    FLog.i(TAG, "📺 STREAM_ONLY (SAF rollover): Tracking temp URI: " + safUri);
                }
                
                // SAMSUNG SAF FIX: The GL pipeline now fully stops and releases the old muxer
                // BEFORE invoking this callback (see GLRecordingPipeline.rolloverSegment()).
                // That means muxer.close() has already returned synchronously — all
                // handleProcessedSegment() writes are done and the old fd is no longer in use.
                //
                // We can therefore close the old PFD immediately, right here, BEFORE opening
                // the new one.  This eliminates the concurrent-open window that causes Samsung's
                // SAF ExternalStorageProvider to invalidate the new PFD on first write (EBADF).
                //
                // On the FIRST rollover the initial segment's PFD lives in safRecordingPfd
                // (not in currentSegmentParcelFileDescriptor), so we must check both.
                ParcelFileDescriptor oldPfd;
                if (currentSegmentParcelFileDescriptor != null) {
                    oldPfd = currentSegmentParcelFileDescriptor;
                    currentSegmentParcelFileDescriptor = null;
                } else {
                    // First rollover: initial segment PFD was stored in safRecordingPfd
                    oldPfd = safRecordingPfd;
                    safRecordingPfd = null;
                }
                if (oldPfd != null) {
                    try {
                        oldPfd.close();
                        FLog.d(TAG, "Closed old segment PFD before opening new one (next segment=" + nextSegmentNumber + ")");
                    } catch (Exception e) {
                        FLog.w(TAG, "Error closing old segment PFD", e);
                    }
                }
                // previousSegmentParcelFileDescriptor is no longer used by this deferred-close
                // path (old muxer is done before we get here), but null it out for safety.
                previousSegmentParcelFileDescriptor = null;
                
                // Open the new PFD now that the old one is fully closed (no concurrent-open conflict)
                try {
                    ParcelFileDescriptor pfd = getContentResolver().openFileDescriptor(safUri, "w");
                    if (pfd == null) {
                        FLog.e(TAG, "Segment rollover: Failed to open ParcelFileDescriptor for SAF URI");
                        stopRecording();
                        return;
                    }
                    
                    // Store the NEW PFD as current
                    currentSegmentParcelFileDescriptor = pfd;
                    FLog.d(TAG, "Successfully created new segment file with SAF: " + videoFile.getName());
                    
                    if (glRecordingPipeline != null) {
                        // Pass the raw FileDescriptor to the pipeline
                        // The ParcelFileDescriptor wrapper stays alive in currentSegmentParcelFileDescriptor
                        glRecordingPipeline.setNextOutput(null, pfd.getFileDescriptor());
                        FLog.d(TAG, "Set next output to file descriptor for segment " + nextSegmentNumber);
                    } else {
                        FLog.e(TAG, "glRecordingPipeline is null, cannot set next output");
                        // Clean up the PFD if we can't use it
                        pfd.close();
                        currentSegmentParcelFileDescriptor = null;
                    }
                } catch (Exception e) {
                    FLog.e(TAG, "Segment rollover: Exception opening PFD for SAF URI", e);
                    currentSegmentParcelFileDescriptor = null;
                    stopRecording();
                }
            } else {
                FLog.d(TAG, "Using internal storage for segment rollover");
                
                // Notify RemoteStreamManager about COMPLETED segment (before creating next)
                // Fragments are delivered via FragmentedMp4MuxerWrapper callbacks (patched Media3)
                if (currentSegmentFile != null && currentSegmentFile.exists()) {
                    long fileSize = currentSegmentFile.length();
                    FLog.i(TAG, "📹 SEGMENT ROLLOVER: #" + (nextSegmentNumber - 1) + 
                        ", Size: " + (fileSize / 1024) + " KB, Path: " + currentSegmentFile.getName());
                } else {
                    FLog.w(TAG, "⚠️ Segment rollover but no current segment file");
                }
                
                // Internal: use createNextSegmentOutputFile()
                File nextFile = createNextSegmentOutputFile(nextSegmentNumber);
                if (nextFile == null) {
                    FLog.e(TAG, "Segment rollover: Failed to create next segment file");
                    stopRecording();
                    return;
                }
                FLog.d(TAG, "Successfully created new segment file: " + nextFile.getAbsolutePath());
                
                // Track this as current segment
                currentSegmentFile = nextFile;
                sessionCreatedLocalFiles.add(nextFile);
                currentSegmentPath = nextFile.getAbsolutePath();
                currentSegmentUriString = Uri.fromFile(nextFile).toString();
                com.fadcam.ActiveRecordingStats.rolloverToNewSegment(nextFile.getAbsolutePath(), null, RecordingService.this);
                
                if (glRecordingPipeline != null) {
                    glRecordingPipeline.setNextOutput(nextFile.getAbsolutePath(), null);
                    FLog.d(TAG, "Set next output to path: " + nextFile.getAbsolutePath() + " for segment "
                            + nextSegmentNumber);
                } else {
                    FLog.e(TAG, "glRecordingPipeline is null, cannot set next output");
                }
            }
        }
    }

    // Update startRecording to use new GLRecordingPipeline constructor
    private void startRecording() {
        if (recordingState != RecordingState.STARTING) {
            FLog.e(TAG, "startRecording was called but state is " + recordingState + ", expected STARTING");
            return;
        }

        // Durable, fsynced journal. The async HTML log loses its last 250 ms if the process dies
        // during recorder setup; this file is how we will read the next Huawei report.
        DiagnosticsStore.beginRecordingJournal(this, "recording-start-request state=" + recordingState
                + " camera=" + sharedPreferencesManager.getCameraSelection()
                + " codec=" + sharedPreferencesManager.getVideoCodec());
        FLog.i(TAG, "[REC] ===== startRecording() requested =====");


        // Clear STREAM_ONLY temp file tracking from any previous session
        streamOnlyTempFiles.clear();
        streamOnlySafUris.clear();

        // Clear runtime overrides so saved preferences take priority on fresh recording
        // session
        runtimeExposureCompensation = null;
        runtimeAeLock = null;
        runtimeAfMode = null;
        pendingDeferredAeLockEnable = false;

        createNotificationChannel();

        if (sharedPreferencesManager.isLocationEmbeddingEnabled()) {
            new Thread(() -> {
                try {
                    if (geotagHelper == null)
                        geotagHelper = new GeotagHelper(this);
                    geotagHelper.startUpdates();
                    Thread.sleep(800);
                } catch (Exception e) {
                    FLog.e(TAG, "Error initializing GeotagHelper", e);
                }
            }).start();
        }

        try {
            if (ActivityCompat.checkSelfPermission(this,
                    Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED ||
                    ActivityCompat.checkSelfPermission(this,
                            Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                FLog.e(TAG, "Permissions missing, cannot start recording.");
                Toast.makeText(this, getString(R.string.toast_permissions_required), Toast.LENGTH_LONG).show();
                recordingState = RecordingState.NONE;
                sharedPreferencesManager.setRecordingInProgress(false);
                stopSelf();
                return;
            }

            if (recordingWakeLock != null && !recordingWakeLock.isHeld())
                recordingWakeLock.acquire();

            ensureWatermarkInfoProvider();

            // Re-fetch streaming state and config at actual recording start
            boolean isStreamingActive = com.fadcam.FeatureRegistry.streaming().isStreamingEnabled();
            android.content.SharedPreferences fadcamPrefs = getSharedPreferences("FadCamPrefs", Context.MODE_PRIVATE);
            
            // Resolution always from Recording Settings — presets only control bitrate + FPS
            // Resolved once here and frozen for the session: Auto decides the shape at
            // capture start and the encoder cannot change shape mid-recording.
            String orientation = SharedPreferencesManager.resolveShape(
                    sharedPreferencesManager.getVideoOrientation(), this);
            Size resolution = sharedPreferencesManager.getCameraResolution();
            int videoWidth  = resolution.getWidth();
            int videoHeight = resolution.getHeight();
            
            int recordingBitrate = getVideoBitrate();  // Reads preset bitrate if streaming
            int recordingFps = sharedPreferencesManager.getSpecificVideoFrameRate(sharedPreferencesManager.getCameraSelection());
            if (isStreamingActive && recordingFps > 0) {
                int streamFpsCap = fadcamPrefs.getInt("stream_fps_cap", -1);
                if (streamFpsCap > 0 && recordingFps > streamFpsCap) {
                    recordingFps = streamFpsCap;  // Apply FPS cap
                }
            }
            
            FLog.i(TAG, "🎬 [RECORDING INITIALIZED] Res: " + videoWidth + "x" + videoHeight + " | FPS: " + recordingFps + " | Bitrate: " + (recordingBitrate/1_000_000) + "Mbps"
                + (isStreamingActive ? " [STREAM PRESET ACTIVE]" : " [Normal recording]"));

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
                    FLog.e(TAG, "Error getting sensor orientation", e);
                }
            }
            int videoBitrate = getVideoBitrate();
            
            // Get camera's target framerate and apply streaming FPS cap if server is ON
            int videoFramerate = sharedPreferencesManager.getSpecificVideoFrameRate(cameraType);
            if (isStreamingActive) {
                int streamFpsCap = fadcamPrefs.getInt("stream_fps_cap", -1);
                if (streamFpsCap > 0 && videoFramerate > streamFpsCap) {
                    FLog.i(TAG, "[STREAM PRESET] Applying FPS cap at MediaRecorder: " + videoFramerate + " → " + streamFpsCap + "fps");
                    videoFramerate = streamFpsCap;
                }
            }
            // Set splitSizeBytes to 0 if video splitting is disabled
            long splitSizeBytes = 0;
            if (sharedPreferencesManager.isVideoSplittingEnabled()) {
                splitSizeBytes = sharedPreferencesManager.getVideoSplitSizeBytes();
            } else {
            }
            int initialSegmentNumber = 1;
            GLSegmentCallback segmentCallback = new GLSegmentCallback();

            String storageMode = sharedPreferencesManager.getStorageMode();
            VideoCodec selectedCodec = sharedPreferencesManager.getVideoCodec();

            // One consolidated snapshot: every line a future report needs in order to identify
            // a codec/size/fps/bitrate mismatch without guessing.
            if (Log.isEnabled()) {
                String startSnapshot = "camera=" + cameraType
                        + " cameraId=" + cameraId
                        + " sensorOrientation=" + sensorOrientation
                        + " codec=" + selectedCodec
                        + " mime=" + selectedCodec.getMimeType()
                        + " res=" + videoWidth + "x" + videoHeight
                        + " fps=" + videoFramerate
                        + " bitrate=" + videoBitrate
                        + " orientation=" + orientation
                        + " streaming=" + isStreamingActive
                        + " storage=" + storageMode
                        + " splitBytes=" + splitSizeBytes
                        + " audio=" + sharedPreferencesManager.isRecordAudioEnabled()
                        + " previewSurface=" + (previewSurface != null && previewSurface.isValid());
                FLog.i(TAG, "[REC] snapshot " + startSnapshot);
                DiagnosticsStore.appendRecordingJournal(this, "[REC] snapshot " + startSnapshot);
            }
            if (SharedPreferencesManager.STORAGE_MODE_CUSTOM.equals(storageMode)) {
                String customUriString = sharedPreferencesManager.getCustomStorageUri();
                if (customUriString == null) {
                    FLog.e(TAG, "Failed to open ParcelFileDescriptor for SAF URI");
                    Toast.makeText(this, getString(R.string.toast_failed_open_file), Toast.LENGTH_LONG).show();
                    stopSelf();
                    return;
                }
                boolean isSafStreamingActive = com.fadcam.FeatureRegistry.streaming().isStreamingEnabled();
                com.fadcam.StreamingMode streamingMode = 
                    com.fadcam.FeatureRegistry.streaming().getStreamingMode();
                boolean isStreamAndSave = isSafStreamingActive
                        && (streamingMode == com.fadcam.StreamingMode.STREAM_AND_SAVE);
                androidx.documentfile.provider.DocumentFile pickedDir = resolveSafRecordingVideoDir(
                        customUriString, isStreamAndSave);
                if (pickedDir == null || !pickedDir.canWrite()) {
                    FLog.e(TAG, "Cannot write to selected custom directory");
                    Toast.makeText(this, getString(R.string.toast_cannot_write_custom_dir), Toast.LENGTH_LONG).show();
                    stopSelf();
                    return;
                }
                String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
                String filenamePrefix = isStreamAndSave ? "Stream_" : Constants.RECORDING_DIRECTORY + "_";
                String baseFilename = filenamePrefix + timestamp + ".mp4";
                androidx.documentfile.provider.DocumentFile videoFile = pickedDir.createFile("video/mp4", baseFilename);
                if (videoFile == null) {
                    FLog.e(TAG, "Failed to create SAF file");
                    Toast.makeText(this, getString(R.string.toast_failed_create_file), Toast.LENGTH_LONG).show();
                    stopSelf();
                    return;
                }
                Uri safUri = videoFile.getUri();
                currentSegmentUriString = safUri.toString();
                currentSegmentPath = safUri.toString();
                currentSegmentFile = null;
                com.fadcam.ActiveRecordingStats.setActiveSegment(null, safUri.toString());
                // Track SAF URI for STREAM_ONLY cleanup (0-byte files created but no data written)
                boolean isStreamOnlySaf = isStreamingActive
                        && (streamingMode == com.fadcam.StreamingMode.STREAM_ONLY);
                if (isStreamOnlySaf) {
                    streamOnlySafUris.add(safUri.toString());
                    FLog.i(TAG, "📺 STREAM_ONLY (SAF): Tracking temp URI for cleanup: " + safUri);
                }
                // -----
                safRecordingPfd = getContentResolver().openFileDescriptor(safUri, "w");
                if (safRecordingPfd == null) {
                    FLog.e(TAG, "Failed to open ParcelFileDescriptor for SAF URI");
                    Toast.makeText(this, getString(R.string.toast_failed_open_file), Toast.LENGTH_LONG).show();
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
                        FLog.d(TAG, "Using location for SAF recording: "
                                + com.fadcam.FLog.redactedCoords(latitude, longitude));
                    }
                } else {
                }
                
                glRecordingPipeline = new com.fadcam.opengl.GLRecordingPipeline(this, watermarkInfoProvider, videoWidth,
                        videoHeight, videoFramerate, safRecordingPfd.getFileDescriptor(), splitSizeBytes,
                        initialSegmentNumber, segmentCallback, previewSurface, orientation, sensorOrientation,
                        selectedCodec, latitude, longitude);
                // Stale-fd resilience (#332): register the SAF URI so hybrid finalization
                // can re-open the file with a fresh fd after process-death churn.
                try {
                    glRecordingPipeline.setOutputUri(safUri.toString());
                } catch (Exception ignored) {}
                FLog.i(TAG, "[STORAGE] SAF mode: uri=" + safUri);
            } else {
                // Get location data for metadata embedding
                Float latitude = null;
                Float longitude = null;
                if (geotagHelper != null && geotagHelper.hasLocation()) {
                    android.location.Location location = geotagHelper.getCurrentLocation();
                    if (location != null) {
                        latitude = (float) location.getLatitude();
                        longitude = (float) location.getLongitude();
                        FLog.d(TAG, "Using location for internal recording: "
                                + com.fadcam.FLog.redactedCoords(latitude, longitude));
                    }
                } else {
                    FLog.d(TAG, "No location available for internal recording metadata");
                }
                
                // Check streaming mode to determine output file handling
                com.fadcam.StreamingMode streamingMode = 
                    com.fadcam.FeatureRegistry.streaming().getStreamingMode();
                boolean isStreamOnly = (streamingMode == com.fadcam.StreamingMode.STREAM_ONLY);
                
                File outputFile;
                if (isStreamOnly) {
                    // STREAM_ONLY: Use temporary file that will be deleted after recording
                    outputFile = new File(getCacheDir(), "stream_temp_" + System.currentTimeMillis() + ".mp4");
                    streamOnlyTempFiles.add(outputFile); // Track for cleanup
                    FLog.i(TAG, "📺 STREAM_ONLY mode: Using temporary file: " + outputFile.getName());
                } else {
                    // STREAM_AND_SAVE: Write directly to final MP4 file (removed .tmp/remux logic)
                    outputFile = getFinalOutputFile();
                    FLog.d(TAG, "💾 STREAM_AND_SAVE mode: Writing directly to file: " + outputFile.getAbsolutePath());
                }
                
                // Track initial segment file for streaming
                currentSegmentFile = outputFile;
                sessionCreatedLocalFiles.add(outputFile);
                currentSegmentPath = outputFile.getAbsolutePath();
                currentSegmentUriString = Uri.fromFile(outputFile).toString();
                com.fadcam.ActiveRecordingStats.setActiveSegment(outputFile.getAbsolutePath(), null);
                FLog.i(TAG, "[STORAGE] Internal mode: path=" + outputFile.getAbsolutePath()
                        + " | streamOnly=" + isStreamOnly);
                
                glRecordingPipeline = new com.fadcam.opengl.GLRecordingPipeline(this, watermarkInfoProvider, videoWidth,
                        videoHeight, videoFramerate, outputFile.getAbsolutePath(), splitSizeBytes, initialSegmentNumber,
                        segmentCallback, previewSurface, orientation, sensorOrientation, selectedCodec, latitude, longitude);
            }

            glRecordingPipeline.prepareSurfaces();

            // Notify RemoteStreamManager that recording started
            try {
                if (currentSegmentFile != null) {
                    com.fadcam.FeatureRegistry.streaming().startRecording(currentSegmentFile);
                    FLog.i(TAG, "🎬 RemoteStreamManager notified: recording started for file: "
                            + currentSegmentFile.getAbsolutePath());
                } else {
                    // SAF mode: no File object available, use flag-based notification
                    com.fadcam.FeatureRegistry.streaming().startRecordingSaf();
                }
                
            } catch (Exception e) {
                FLog.e(TAG, "Failed to notify RemoteStreamManager about recording start", e);
            }

            createCameraPreviewSession();
            DiagnosticsStore.endRecordingJournal(this, "[REC] RESULT=OK");
            FLog.i(TAG, "[REC] ===== startRecording() complete =====");
        } catch (Exception e) {
            String failureDetail = e.getClass().getName()
                    + (e.getMessage() == null ? "" : ": " + e.getMessage());
            if (Log.isEnabled()) {
                FLog.e(TAG, "[REC] CRITICAL FAILURE IN startRecording | " + failureDetail, e);
            }
            DiagnosticsStore.appendRecordingJournal(this, "[REC] START FAILED | " + failureDetail);

            // Encoder attempts are the useful part of a recorder failure; keep them separate
            // from the Java stack so the dialog and the copied report are readable.
            String encoderDiagnostics = null;
            try {
                if (glRecordingPipeline != null) {
                    encoderDiagnostics = glRecordingPipeline.getEncoderDiagnostics();
                }
            } catch (Throwable ignored) {
            }
            if (encoderDiagnostics != null && !encoderDiagnostics.trim().isEmpty()) {
                if (Log.isEnabled()) {
                    FLog.e(TAG, "[REC] encoder diagnostics:\n" + encoderDiagnostics);
                }
                DiagnosticsStore.appendRecordingJournal(this,
                        "[REC] encoder diagnostics:\n" + encoderDiagnostics);
            }

            // Broadcast the failure to the UI with details
            java.io.StringWriter sw = new java.io.StringWriter();
            java.io.PrintWriter pw = new java.io.PrintWriter(sw);
            e.printStackTrace(pw);
            String stackTrace = sw.toString();

            Intent failureIntent = new Intent(Constants.ACTION_RECORDING_FAILED);
            failureIntent.putExtra(Constants.EXTRA_ERROR_MESSAGE, e.getMessage());
            failureIntent.putExtra(Constants.EXTRA_STACK_TRACE, stackTrace);
            if (encoderDiagnostics != null && !encoderDiagnostics.trim().isEmpty()) {
                failureIntent.putExtra(Constants.EXTRA_ENCODER_DIAGNOSTICS, encoderDiagnostics);
            }
            try {
                failureIntent.setPackage(getPackageName());
                sendBroadcast(failureIntent);
            } catch (Throwable t) {
                FLog.e(TAG, "Failed to broadcast recording failure", t);
            }
            DiagnosticsStore.endRecordingJournal(this, "[REC] RESULT=FAIL | " + failureDetail);
            Log.flushNow();

            // Clean up and stop the service
            recordingState = RecordingState.NONE;
            sharedPreferencesManager.setRecordingInProgress(false);
            stopRecording();
        }
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

        } catch (Exception e) {
            FLog.e(TAG, "Failed to create dummy background surface", e);
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
                FLog.e(TAG, "Error releasing dummyBackgroundSurface", e);
            } finally {
                dummyBackgroundSurface = null;
            }
        }

        if (dummySurfaceTexture != null) {
            try {
                dummySurfaceTexture.release();
            } catch (Exception e) {
                FLog.e(TAG, "Error releasing dummySurfaceTexture", e);
            } finally {
                dummySurfaceTexture = null;
            }
        }
    }

    // Flag to track if we need to automatically resume recording after camera
    // interruption
    private boolean pendingCameraReconnect = false;
    private static final long RECONNECT_RETRY_DELAY_MS = 2000; // 2 seconds between reconnection attempts
    private Handler reconnectHandler = new Handler(Looper.getMainLooper());
    private Runnable reconnectRunnable;

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
                    // log removed
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
        FLog.d(TAG, "Started camera reconnection attempts (infinite)");
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
                        FLog.d(TAG, "Camera ID '" + cameraId + "' is a physical camera from logical back camera '"
                                + logicalId + "'");
                        return true;
                    }
                }
            }
        } catch (CameraAccessException e) {
            FLog.e(TAG, "Error checking if camera " + cameraId + " is a physical back camera", e);
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
            FLog.d(TAG, "Attempting to reconnect camera: " + cameraId);
            cameraManager.openCamera(cameraId, cameraStateCallback, backgroundHandler);
        } catch (Exception e) {
            FLog.e(TAG, "Error reconnecting to camera", e);
        }
    }

    /**
     * Handles camera interruptions by continuing to record with black frames.
     * This is called when the camera is disconnected or encounters an error during
     * recording.
     */
    private void handleCameraInterruption() {
        if (recordingState != RecordingState.IN_PROGRESS) {
            FLog.w(TAG,
                    "handleCameraInterruption called but not in IN_PROGRESS state, current state: " + recordingState);
            return;
        }

        FLog.i(TAG, "Camera interrupted during recording - switching to black frame mode");

        // Save the current camera ID for reconnection
        String cameraToReconnect = null;
        try {
            CameraType cameraType = sharedPreferencesManager.getCameraSelection();
            cameraToReconnect = getCameraId(cameraManager, cameraType);
            if (cameraToReconnect == null) {
                FLog.e(TAG, "Could not determine camera ID for reconnection");
                cameraToReconnect = "0"; // Default to first camera
            }
        } catch (Exception e) {
            FLog.e(TAG, "Error determining camera ID for reconnection", e);
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

        FLog.i(TAG, "Recording continuing with black frames, attempting camera reconnection");
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
            FLog.d(TAG, "Already rendering black frames");
            return;
        }

        if (glRecordingPipeline == null) {
            FLog.e(TAG, "Cannot start black frame rendering - pipeline is null");
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
                    // log removed
                    stopBlackFrameRendering();
                    return;
                }

                // Render a black frame - don't try to recreate renderer if it fails
                if (glRecordingPipeline != null) {
                    try {
                        glRecordingPipeline.renderBlackFrame();
                    } catch (Exception e) {
                        // Expected during camera disconnection - log at debug level
                        FLog.d(TAG, "Expected exception rendering black frame during camera disconnection");
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
        FLog.d(TAG, "Started rendering black frames");
    }

    /**
     * Stops rendering black frames.
     */
    private void stopBlackFrameRendering() {
        // Set flag first to prevent new frames from being scheduled
        isRenderingBlackFrames = false;
        // log removed

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
                        FLog.w(TAG, "Interrupted while waiting for black frame thread to exit", e);
                    }
                }
            } catch (Exception e) {
                FLog.w(TAG, "Error cleaning up black frame thread", e);
            }
        }, "BlackFrameCleanupThread").start();

        // Clear handler reference
        blackFrameHandler = null;

        // log removed
    }

    /**
     * Sets up a notification to inform the user that recording is continuing with
     * black frames
     * due to camera interruption.
     */
    private void setupCameraInterruptionNotification() {
        if (ActivityCompat.checkSelfPermission(this,
                android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            FLog.w(TAG, "POST_NOTIFICATIONS permission not granted, skipping notification update.");
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
                    R.drawable.stop_rounded,
                    getString(R.string.button_stop),
                    createStopRecordingIntent()));
        }

        NotificationManagerCompat notificationManager = NotificationManagerCompat.from(this);
        notificationManager.notify(NOTIFICATION_ID, builder.build());
        FLog.d(TAG, "Camera interruption notification displayed");
    }

    /**
     * Stops any ongoing camera reconnection attempts.
     */
    private void stopReconnectionAttempts() {
        pendingCameraReconnect = false;
        if (reconnectHandler != null && reconnectRunnable != null) {
            reconnectHandler.removeCallbacks(reconnectRunnable);
        }
        // log removed
    }

    // Add this field to the class
    private ParcelFileDescriptor safRecordingPfd = null;
}
