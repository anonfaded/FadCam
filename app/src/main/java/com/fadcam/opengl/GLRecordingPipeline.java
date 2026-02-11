package com.fadcam.opengl;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.view.Surface;

import java.io.File;
import java.io.FileDescriptor;
import java.io.IOException;
import java.io.RandomAccessFile;

import java.nio.ByteBuffer;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.VideoCodec;
import com.fadcam.dualcam.DualCameraConfig;
import com.fadcam.media.FragmentedMp4MuxerWrapper;

/**
 * GLRecordingPipeline manages the OpenGL pipeline for real-time watermarking
 * and video encoding.
 *
 * This pipeline always uses the fixed videoWidth/videoHeight for recording
 * output,
 * and ignores device rotation. Only the preview may use letterboxing for user
 * experience.
 */
public class GLRecordingPipeline {
    private static final String TAG = "GLRecordingPipeline";
    private static final String VIDEO_MIME_TYPE = "video/avc";
    private static final int VIDEO_IFRAME_INTERVAL = 1;
    private static final int PREVIEW_RENDER_INTERVAL_MS = 33; // Safer 30fps instead of 60fps
    private static final int RENDER_RETRY_DELAY_MS = 33; // Match with preview render interval

    private final Context context;
    private final WatermarkInfoProvider watermarkInfoProvider;
    private GLWatermarkRenderer glRenderer;
    private MediaCodec videoEncoder;
    // Use FragmentedMp4MuxerWrapper for fMP4 streaming
    private FragmentedMp4MuxerWrapper mediaMuxer;
    private Surface encoderInputSurface;
    private Surface cameraInputSurface;
    private boolean isRecording = false;
    private boolean isStopped = false;
    private HandlerThread renderThread;
    private Handler handler;
    private final int videoWidth;
    private final int videoHeight;
    private int videoBitrate;
    private final int videoFramerate;
    private final String outputFilePath;
    private final FileDescriptor outputFd;
    private Surface previewSurface;
    // Pending preview apply support to debounce rapid preview surface changes
    private Surface pendingPreviewToApply = null;
    private Runnable pendingPreviewApplyRunnable = null;
    private final Object previewApplyLock = new Object();
    private final Object timestampLock = new Object();  // Synchronization lock for timestamp fields
    private final String orientation;
    private final int sensorOrientation;

    // Surface dimensions for aspect ratio calculations
    private int mSurfaceWidth;
    private int mSurfaceHeight;

    // Target aspect ratio (width/height) for maintaining consistent dimensions
    private float targetAspectRatio;

    // Callback interface for segment rollover
    public interface SegmentCallback {
        /**
         * Called when a new segment is needed. Should provide a new file path or
         * FileDescriptor.
         * 
         * @param nextSegmentNumber The next segment number (1-based)
         */
        void onSegmentRollover(int nextSegmentNumber);
    }

    /**
     * Gets a synchronized timestamp for audio frames based on the video timeline.
     * This ensures audio and video timestamps are properly aligned.
     * Accounts for pause durations to maintain sync during camera switch.
     */
    private long getSynchronizedAudioTimestamp() {
        // Audio timestamp is calculated based on sample count, which naturally
        // excludes pause periods (no samples recorded during pause)
        // So we don't need to do anything special here
        return -1; // Indicates: use the PTS calculated in audio thread
    }

    private void initializeVideoTimestamp(long cameraTimestampNanos) {
        // Not needed anymore - PTS is calculated from frame counter
        // Keeping this method for compatibility with existing code
    }

    /**
     * Gets a synchronized timestamp for video frames that aligns with audio.
     * Returns -1 during pause to skip frames, preserving the existing timestamp logic.
     * 
     * CRITICAL FIX: Use SYSTEM CLOCK elapsed time (same as audio thread),
     * not camera timestamps. This ensures audio and video use the SAME timing base.
     */
    public long getSynchronizedVideoTimestamp(long cameraTimestampNanos) {
        // If paused, return -1 to signal frame skip in renderToEncoder
        if (isPaused) {
            return -1;  // Skip this frame
        }
        
        // Use System.nanoTime() reference (same as audio thread for sync)
        // Initialize recording start time on first frame
        if (recordingStartSystemTimeNanos == -1) {
            synchronized (timestampLock) {
                if (recordingStartSystemTimeNanos == -1) {
                    recordingStartSystemTimeNanos = System.nanoTime();
                    Log.d(TAG, "[VIDEO_TIMESTAMP] Recording system time reference initialized: " + 
                          recordingStartSystemTimeNanos + " nanos");
                }
            }
        }
        
        // Calculate relative timestamp from system time reference (same as audio!)
        // This ensures both audio and video use the exact same timing base
        long elapsedNanos = System.nanoTime() - recordingStartSystemTimeNanos - totalPauseDurationNanos;
        long ptsUs = Math.max(0, elapsedNanos / 1000L);  // Convert to microseconds, ensure non-negative
        
        return ptsUs;
    }

    private long maxFileSizeBytes = Long.MAX_VALUE;
    private int segmentNumber = 1;
    private SegmentCallback segmentCallback;
    private String currentOutputFilePath;
    private FileDescriptor currentOutputFd;
    private boolean muxerStarted = false;
    // --- Dashcam splitting additions ---
    private long segmentBytesWritten = 0L; // Bytes written in current segment (video+audio)
    private static final double ROLLOVER_PREEMPT_THRESHOLD_RATIO = 0.95; // Request keyframe early
    private boolean pendingRollover = false; // Threshold near, waiting for keyframe
    private boolean awaitingKeyframeForRollover = false; // Sync frame requested, waiting to hit size at keyframe
    private boolean rolloverInProgress = false; // Guard against concurrent rollovers
    private volatile boolean rolloverRequestedByDrain = false; // Set inside drain loop post keyframe write

    private int encoderWidth;
    private int encoderHeight;
    private int deviceOrientation = android.view.Surface.ROTATION_0;

    // Audio recording/encoding fields
    private android.media.AudioRecord audioRecord;
    private MediaCodec audioEncoder;
    private Thread audioThread;
    private int audioTrackIndex = -1;
    private int videoTrackIndex = -1; // Track index for video in the muxer
    private boolean audioEncoderStarted = false;
    private boolean audioRecordingEnabled = false;
    private boolean audioThreadRunning = false;
    private final Object audioLock = new Object();
    private android.media.AudioManager audioManager;
    private android.media.AudioManager.OnAudioFocusChangeListener audioFocusListener;
    private int originalAudioMode = -1; // Store original mode to restore on stop
    private boolean originalSpeakerphoneOn = false; // Store original speakerphone state to restore on stop
    private int consecutive512Count = 0; // Track consecutive 512-byte AAC frames (silence detection)
    
    // Debug counters for tracking sample writing
    private long audioSamplesWritten = 0;
    private long videoSamplesWritten = 0;
    private long lastAudioPts = 0;
    private long lastVideoPts = 0;
    
    // Cached formats so we can recreate muxer for segment rollover without
    // waiting for INFO_OUTPUT_FORMAT_CHANGED (only fired once per encoder start)
    private MediaFormat cachedVideoFormat;
    private MediaFormat cachedAudioFormat;
    // Audio settings (always set from preferences or app defaults)
    private int audioSource;
    private int audioSampleRate;
    private int audioBitrate;
    private int audioChannelCount;

    private boolean released = false;

    private final VideoCodec videoCodec;

    /** Optional dual camera config — when non-null, enables PiP rendering in the GL pipeline. */
    @Nullable
    private DualCameraConfig dualCameraConfig;

    /** Secondary camera input surface, lazily obtained from GLWatermarkRenderer after PiP init. */
    private Surface secondaryCameraInputSurface;

    // Location metadata fields
    private Float locationLatitude = null;
    private Float locationLongitude = null;

    // Timestamp synchronization fields
    private long recordingStartTimeNanos = -1;       // System.nanoTime() when recording starts (VIDEO reference)
    private long recordingStartSystemTimeNanos = -1; // System.nanoTime() for audio thread reference (same as video!)
    private volatile boolean isPaused = false;       // Track pause state for audio thread
    
    // Pause/resume tracking fields
    private long pauseStartTimeNanos = -1;           // System.nanoTime() when pause starts
    private long totalPauseDurationNanos = 0;        // Accumulated pause duration
    private long lastVideoPtsBeforePauseUs = 0;      // Last video PTS before pause
    private long lastAudioPtsBeforePauseUs = 0;      // Last audio PTS before pause
    private boolean isCameraSwitchPause = false;     // Flag to indicate if pause is due to camera switch

    // scheduler-----------
    // Update watermark on a low-frequency handler to avoid per-frame overhead and
    // sustain 60fps
    private final android.os.Handler watermarkUpdateHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private Runnable updateWatermarkRunnable;
    // Queue for GL-thread-safe renderer operations (processed in render loop)
    private final java.util.concurrent.ConcurrentLinkedQueue<Runnable> rendererActions = new java.util.concurrent.ConcurrentLinkedQueue<>();
    // scheduler-----------

    /**
     * Ensures periodic watermark updates are running.
     * Called on start/resume because the updater intentionally stops when paused.
     */
    private void ensureWatermarkUpdaterRunning() {
        if (updateWatermarkRunnable == null) {
            updateWatermarkRunnable = new Runnable() {
                @Override
                public void run() {
                    if (isRecording && !released) {
                        try {
                            updateWatermark();
                        } catch (Exception e) {
                            Log.w(TAG, "Watermark update failed", e);
                        }
                        watermarkUpdateHandler.postDelayed(this, 1000);
                    }
                }
            };
        }
        watermarkUpdateHandler.removeCallbacks(updateWatermarkRunnable);
        watermarkUpdateHandler.post(updateWatermarkRunnable);
    }

    // Updated constructor for file path (internal storage)
    public GLRecordingPipeline(Context context, WatermarkInfoProvider watermarkInfoProvider, int videoWidth,
            int videoHeight, int videoFramerate, String outputFilePath, long maxFileSizeBytes, int segmentNumber,
            SegmentCallback segmentCallback, Surface previewSurface, String orientation, int sensorOrientation,
            VideoCodec videoCodec, Float latitude, Float longitude) {
        this(context, watermarkInfoProvider, videoWidth, videoHeight, videoFramerate, outputFilePath, maxFileSizeBytes,
                segmentNumber, segmentCallback, orientation, sensorOrientation, videoCodec, latitude, longitude);
        this.previewSurface = previewSurface;
        initAudioSettings();
    }

    // Updated constructor for FileDescriptor (SAF)
    public GLRecordingPipeline(Context context, WatermarkInfoProvider watermarkInfoProvider, int videoWidth,
            int videoHeight, int videoFramerate, FileDescriptor outputFd, long maxFileSizeBytes, int segmentNumber,
            SegmentCallback segmentCallback, Surface previewSurface, String orientation, int sensorOrientation,
            VideoCodec videoCodec, Float latitude, Float longitude) {
        this(context, watermarkInfoProvider, videoWidth, videoHeight, videoFramerate, outputFd, maxFileSizeBytes,
                segmentNumber, segmentCallback, orientation, sensorOrientation, videoCodec, latitude, longitude);
        this.previewSurface = previewSurface;
        initAudioSettings();
    }

    // Updated constructor for file path (internal storage)
    public GLRecordingPipeline(Context context, WatermarkInfoProvider watermarkInfoProvider, int videoWidth,
            int videoHeight, int videoFramerate, String outputFilePath, long maxFileSizeBytes, int segmentNumber,
            SegmentCallback segmentCallback, String orientation, int sensorOrientation, VideoCodec videoCodec,
            Float latitude, Float longitude) {
        this.context = context;
        this.watermarkInfoProvider = watermarkInfoProvider;
        this.videoWidth = videoWidth;
        this.videoHeight = videoHeight;
        this.videoFramerate = videoFramerate;
        this.outputFilePath = outputFilePath;
        this.outputFd = null;
        this.maxFileSizeBytes = maxFileSizeBytes;
        this.segmentNumber = segmentNumber;
        this.segmentCallback = segmentCallback;
        this.currentOutputFilePath = outputFilePath;
        this.currentOutputFd = null;
        this.orientation = orientation;
        this.sensorOrientation = sensorOrientation;
        this.videoCodec = videoCodec;
        this.locationLatitude = latitude;
        this.locationLongitude = longitude;
        // Fetch video bitrate from SharedPreferencesManager
        this.videoBitrate = com.fadcam.SharedPreferencesManager.getInstance(context).getCurrentBitrate();
        // Initialize surface dimensions with video dimensions as default
        this.mSurfaceWidth = videoWidth;
        this.mSurfaceHeight = videoHeight;
        // Calculate target aspect ratio based on the fixed dimensions
        this.targetAspectRatio = (float) videoWidth / videoHeight;
        Log.d(TAG, "GLRecordingPipeline initialized with fixed dimensions: " +
                videoWidth + "x" + videoHeight + " in " + orientation +
                " orientation (sensor=" + sensorOrientation + "), aspect ratio: " + targetAspectRatio);
        initAudioSettings();
    }

    // Updated constructor for FileDescriptor (SAF)
    public GLRecordingPipeline(Context context, WatermarkInfoProvider watermarkInfoProvider, int videoWidth,
            int videoHeight, int videoFramerate, FileDescriptor outputFd, long maxFileSizeBytes, int segmentNumber,
            SegmentCallback segmentCallback, String orientation, int sensorOrientation, VideoCodec videoCodec,
            Float latitude, Float longitude) {
        this.context = context;
        this.watermarkInfoProvider = watermarkInfoProvider;
        this.videoWidth = videoWidth;
        this.videoHeight = videoHeight;
        this.videoFramerate = videoFramerate;
        this.outputFilePath = null;
        this.outputFd = outputFd;
        this.maxFileSizeBytes = maxFileSizeBytes;
        this.segmentNumber = segmentNumber;
        this.segmentCallback = segmentCallback;
        this.currentOutputFilePath = null;
        this.currentOutputFd = outputFd;
        this.orientation = orientation;
        this.sensorOrientation = sensorOrientation;
        this.videoCodec = videoCodec;
        this.locationLatitude = latitude;
        this.locationLongitude = longitude;
        // Fetch video bitrate from SharedPreferencesManager
        this.videoBitrate = com.fadcam.SharedPreferencesManager.getInstance(context).getCurrentBitrate();
        // Initialize surface dimensions with video dimensions as default
        this.mSurfaceWidth = videoWidth;
        this.mSurfaceHeight = videoHeight;
        // Calculate target aspect ratio based on the fixed dimensions
        this.targetAspectRatio = (float) videoWidth / videoHeight;
        Log.d(TAG, "GLRecordingPipeline initialized with fixed dimensions: " +
                videoWidth + "x" + videoHeight + " in " + orientation +
                " orientation (sensor=" + sensorOrientation + "), aspect ratio: " + targetAspectRatio);
        initAudioSettings();
    }

    // ════════════════════════════════════════════════════════════════════
    // DUAL CAMERA CONSTRUCTORS
    // ════════════════════════════════════════════════════════════════════

    /**
     * Dual camera constructor for file path (internal storage).
     * Creates a pipeline with PiP support enabled via the DualCameraConfig.
     */
    public GLRecordingPipeline(Context context, WatermarkInfoProvider watermarkInfoProvider,
            int videoWidth, int videoHeight, int videoFramerate, String outputFilePath,
            long maxFileSizeBytes, int segmentNumber, SegmentCallback segmentCallback,
            Surface previewSurface, String orientation, int sensorOrientation,
            VideoCodec videoCodec, Float latitude, Float longitude,
            @NonNull DualCameraConfig dualCameraConfig) {
        this(context, watermarkInfoProvider, videoWidth, videoHeight, videoFramerate,
                outputFilePath, maxFileSizeBytes, segmentNumber, segmentCallback,
                previewSurface, orientation, sensorOrientation, videoCodec, latitude, longitude);
        this.dualCameraConfig = dualCameraConfig;
        Log.d(TAG, "Dual camera mode enabled: " + dualCameraConfig);
    }

    /**
     * Dual camera constructor for FileDescriptor (SAF storage).
     * Creates a pipeline with PiP support enabled via the DualCameraConfig.
     */
    public GLRecordingPipeline(Context context, WatermarkInfoProvider watermarkInfoProvider,
            int videoWidth, int videoHeight, int videoFramerate, FileDescriptor outputFd,
            long maxFileSizeBytes, int segmentNumber, SegmentCallback segmentCallback,
            Surface previewSurface, String orientation, int sensorOrientation,
            VideoCodec videoCodec, Float latitude, Float longitude,
            @NonNull DualCameraConfig dualCameraConfig) {
        this(context, watermarkInfoProvider, videoWidth, videoHeight, videoFramerate,
                outputFd, maxFileSizeBytes, segmentNumber, segmentCallback,
                previewSurface, orientation, sensorOrientation, videoCodec, latitude, longitude);
        this.dualCameraConfig = dualCameraConfig;
        Log.d(TAG, "Dual camera mode enabled (SAF): " + dualCameraConfig);
    }

    // ════════════════════════════════════════════════════════════════════
    // DUAL CAMERA PUBLIC API
    // ════════════════════════════════════════════════════════════════════

    /**
     * Returns the primary camera input surface (alias for getCameraInputSurface).
     * For consistency with the dual camera API nomenclature.
     */
    @NonNull
    public Surface getPrimaryCameraInputSurface() {
        Surface s = getCameraInputSurface();
        if (s == null) throw new IllegalStateException("Call prepareSurfaces() first");
        return s;
    }

    /**
     * Returns the secondary (PiP) camera input surface.
     * Only available after {@link #prepareSurfaces()} when dual camera mode is enabled.
     *
     * @return Secondary camera Surface, or null if not in dual camera mode.
     */
    @Nullable
    public Surface getSecondaryCameraInputSurface() {
        return secondaryCameraInputSurface;
    }

    /**
     * Swaps primary ↔ PiP camera rendering without changing camera sessions.
     * Thread-safe — can be called from any thread.
     */
    public void swapCameras() {
        if (glRenderer != null) {
            glRenderer.swapCameras();
            Log.d(TAG, "Camera rendering swapped");
        }
    }

    /**
     * Live-updates the PiP configuration (position, size, border, corners).
     * Thread-safe — can be called from any thread.
     *
     * @param newConfig Updated PiP configuration.
     */
    public void updateConfig(@NonNull DualCameraConfig newConfig) {
        this.dualCameraConfig = newConfig;
        if (glRenderer != null) {
            glRenderer.updatePipConfig(newConfig);
            Log.d(TAG, "PiP config updated: " + newConfig);
        }
    }

    /**
     * Prepares the GL renderer and camera input surface for the camera session.
     * Call this after constructing the pipeline, before creating the camera
     * session.
     * Does NOT start encoding or the render loop.
     */
    public void prepareSurfaces() {
        try {
            try {
                com.fadcam.Log.d(TAG, "prepareSurfaces() called");
            } catch (Throwable ignore) {
            }
            // Make sure any previous resources are fully released
            if (glRenderer != null) {
                Log.d(TAG, "Releasing previous renderer before preparing new surfaces");
                try {
                    glRenderer.release();
                } catch (Exception e) {
                    Log.e(TAG, "Error releasing previous renderer", e);
                    try {
                        com.fadcam.Log.e(TAG, "Error releasing previous renderer", e);
                    } catch (Throwable ignore) {
                    }
                }
                glRenderer = null;
            }

            if (encoderInputSurface == null) {
                // Log.d(TAG, "Setting up video encoder");
                setupEncoder();
                if (encoderInputSurface == null) {
                    throw new RuntimeException("Failed to create encoder input surface");
                }
            }

            if (glRenderer == null) {
                // Log.d(TAG, "Creating GLWatermarkRenderer with dimensions " + videoWidth + "x" + videoHeight);
                glRenderer = new GLWatermarkRenderer(context, encoderInputSurface, orientation, sensorOrientation,
                        videoWidth, videoHeight);
                glRenderer.setUserOrientationSetting(orientation);
                glRenderer.setRecordingPipeline(this); // Set reference for timestamp synchronization
                // Propagate encoder dimensions that were computed during initializeEncoder().
                // initializeEncoder() runs BEFORE the renderer is created, so its
                // setEncoderDimensions() call hits null and is silently skipped.
                // We must forward them now so the renderer uses the correct
                // (post-orientation-swap) dimensions for viewport and aspect ratio.
                if (encoderWidth > 0 && encoderHeight > 0) {
                    glRenderer.setEncoderDimensions(encoderWidth, encoderHeight);
                }
                // Create EGL context and camera surface strictly on the GL render thread
                // Ensure render thread exists
                if (renderThread == null || handler == null) {
                    startRenderLoop(); // Only starts thread/handler; does not start frame loop
                }
                final java.util.concurrent.CountDownLatch initLatch = new java.util.concurrent.CountDownLatch(1);
                final Throwable[] initError = new Throwable[1];
                handler.post(() -> {
                    try {
                        Log.d(TAG, "Initializing renderer EGL context on GL thread");
                        glRenderer.initializeEGL();
                        // Request camera input surface now that GL is initialized
                        Log.d(TAG, "Requesting camera input surface from renderer (GL thread)");
                        Surface camSurf = glRenderer.getCameraInputSurface();
                        cameraInputSurface = camSurf;

                        // Initialize PiP if dual camera mode is enabled
                        if (dualCameraConfig != null) {
                            Log.d(TAG, "Initializing PiP on GL thread for dual camera mode");
                            glRenderer.initializePiP(dualCameraConfig);
                            secondaryCameraInputSurface = glRenderer.getSecondaryCameraInputSurface();
                            if (secondaryCameraInputSurface == null || !secondaryCameraInputSurface.isValid()) {
                                Log.e(TAG, "Secondary camera input surface is invalid after PiP init");
                            } else {
                                Log.d(TAG, "Secondary camera input surface obtained successfully");
                            }
                        }

                        if (previewSurface != null && previewSurface.isValid()) {
                            // Log.d(TAG, "Setting preview surface during prepareSurfaces (GL thread)");
                            glRenderer.setPreviewSurface(previewSurface);
                        } else {
                            Log.d(TAG, "No valid preview surface; optional preview warm-up");
                            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                                try {
                                    android.util.DisplayMetrics metrics = context.getResources().getDisplayMetrics();
                                    int w = Math.min(metrics.widthPixels, 320);
                                    int h = Math.min(metrics.heightPixels, 240);
                                    android.media.ImageReader ir = android.media.ImageReader.newInstance(
                                            w, h, android.graphics.PixelFormat.RGBA_8888, 1);
                                    Surface dummy = ir.getSurface();
                                    try {
                                        glRenderer.initializePreviewSurfaceOnly(dummy);
                                        Log.d(TAG, "Dummy preview surface warmed up (GL thread)");
                                    } finally {
                                        try {
                                            if (dummy != null)
                                                dummy.release();
                                        } catch (Exception ignore) {
                                        }
                                        try {
                                            ir.close();
                                        } catch (Exception ignore) {
                                        }
                                    }
                                } catch (Exception warmEx) {
                                    Log.w(TAG, "Preview warm-up failed; continuing without", warmEx);
                                }
                            }
                        }
                    } catch (Throwable t) {
                        initError[0] = t;
                    } finally {
                        initLatch.countDown();
                    }
                });
                // Wait for GL init to complete so we can return a valid camera surface for
                // Camera2 session
                try {
                    if (!initLatch.await(1500, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                        throw new RuntimeException("Timed out initializing GL renderer on GL thread");
                    }
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
                if (initError[0] != null) {
                    throw new RuntimeException("GL initialization failed", initError[0]);
                }
                // Validate camera surface
                if (cameraInputSurface == null || !cameraInputSurface.isValid()) {
                    Log.e(TAG, "Camera input surface is invalid after GL init");
                    throw new RuntimeException("Camera input surface invalid");
                }
                Log.d(TAG, "Successfully obtained valid camera input surface");

                // Set up the frame listener to trigger rendering when new frames arrive
                glRenderer.setOnFrameAvailableListener(new GLWatermarkRenderer.OnFrameAvailableListener() {
                    @Override
                    public void onFrameAvailable() {
                        if (isRecording && handler != null) {
                            handler.post(renderRunnable);
                        }
                    }
                });

                try {
                    if (watermarkInfoProvider != null) {
                        String initial = watermarkInfoProvider.getWatermarkText();
                        glRenderer.setWatermarkText(initial != null ? initial : "");
                        Log.d(TAG, "Applied initial watermark text during prepareSurfaces");
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Failed to apply initial watermark", e);
                }

                // Log.d(TAG, "GLWatermarkRenderer setup complete");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error preparing surfaces", e);
            // Clean up any resources we may have created
            if (glRenderer != null) {
                try {
                    glRenderer.release();
                } catch (Exception ex) {
                    Log.e(TAG, "Error releasing renderer during error cleanup", ex);
                }
                glRenderer = null;
            }
            throw new RuntimeException("Failed to prepare recording surfaces", e);
        }
    }

    /**
     * Starts the recording pipeline.
     */
    public void startRecording() {
        try {
            if (!isRecording) {
                Log.d(TAG, "Starting recording pipeline");
                try {
                    com.fadcam.Log.d(TAG, "Starting recording pipeline");
                } catch (Throwable ignore) {
                }
                
                // Reset timestamp tracking at recording start
                recordingStartTimeNanos = -1;  // Will be initialized on first VIDEO frame
                recordingStartSystemTimeNanos = System.nanoTime(); // Initialize for AUDIO thread reference NOW
                Log.i(TAG, "[RECORDING_START] Audio/Video timing reference initialized: " + recordingStartSystemTimeNanos);

                // Make sure we have a valid renderer and surfaces
                if (glRenderer == null || encoderInputSurface == null) {
                    Log.d(TAG, "Preparing surfaces before starting recording");
                    prepareSurfaces();
                }

                if (glRenderer == null) {
                    throw new RuntimeException("Failed to create renderer");
                }

                if (encoderInputSurface == null) {
                    throw new RuntimeException("Failed to create encoder input surface");
                }

                // Initialize audio settings
                initAudioSettings();

                // Mark as recording and set up audio first
                isRecording = true;
                setupAudio();
                if (audioRecordingEnabled) {
                    startAudioThread();
                    // Give audio encoder time to initialize and produce output format
                    // This is critical - the audio encoder needs data flowing through it
                    // before INFO_OUTPUT_FORMAT_CHANGED is signaled
                    try {
                        Thread.sleep(150); // Slightly longer initial wait for audio thread to start
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    
                    // Aggressively drain audio encoder to get format early
                    // The encoder needs some input before it produces output format
                    // Try for up to 2 seconds (40 iterations * 50ms)
                    for (int i = 0; i < 40 && audioTrackIndex == -1; i++) {
                        drainAudioEncoder();
                        if (audioTrackIndex != -1) {
                            Log.d(TAG, "Audio track added after " + (i + 1) + " drain attempts");
                            break;
                        }
                        try {
                            Thread.sleep(50);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                    
                    // If audio track still not added after 2 seconds, log warning
                    // The muxer will start when video format is ready and we'll continue
                    // to try adding audio in the drainAudioEncoder loop
                    if (audioTrackIndex == -1) {
                        Log.w(TAG, "Audio track not yet ready after initialization - will continue trying in render loop");
                        
                        // Add a delayed safety check: if muxer hasn't started after 5 seconds,
                        // start it without audio to prevent recording from hanging
                        handler.postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                synchronized (GLRecordingPipeline.this) {
                                    if (!muxerStarted && videoTrackIndex != -1 && isRecording) {
                                        Log.w(TAG, "SAFETY FALLBACK: Starting muxer without audio after 5 second timeout");
                                        try {
                                            if (mediaMuxer != null) {
                                                mediaMuxer.start();
                                                muxerStarted = true;
                                                Log.d(TAG, "Muxer started via safety fallback (video-only mode)");
                                            }
                                        } catch (Exception e) {
                                            Log.e(TAG, "Failed to start muxer via safety fallback", e);
                                        }
                                    }
                                }
                            }
                        }, 5000); // 5 second safety timeout
                    }
                }

                // Start/refresh low-frequency watermark updater (once per second).
                ensureWatermarkUpdaterRunning();

                // Start the render loop (which will trigger video encoder format change)
                startRenderLoop();

                Log.d(TAG, "Recording pipeline started successfully");
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to start recording pipeline", e);
            stopRecording();
        }
    }

    // Tracks whether video/audio EOS has been received from encoders
    private volatile boolean videoEosReceived = false;
    private volatile boolean audioEosReceived = false;

    /**
     * PRODUCTION-GRADE encoder drain that waits for actual EOS from encoders.
     * This is the ONLY correct way to finalize fMP4 files for VLC/FFmpeg compatibility.
     * 
     * CRITICAL: We drain UNTIL EOS is received, NOT until a timeout expires.
     * Timeout-based draining causes truncated files that only ExoPlayer can play.
     * 
     * IMPORTANT FIX: We must signal video EOS FIRST, drain video fully, THEN stop audio.
     * This ensures audio and video have the same duration.
     */
    private void emergencyDrainEncoders() {
        Log.d(TAG, "Draining encoders until EOS (production-grade finalization)");

        videoEosReceived = false;
        audioEosReceived = !audioRecordingEnabled; // If no audio, mark as done

        // Step 1: Signal EOS to video encoder FIRST
        // Video must signal EOS before we stop audio to ensure synchronized duration
        if (videoEncoder != null) {
            try {
                videoEncoder.signalEndOfInputStream();
                Log.d(TAG, "Signaled EOS to video encoder input surface");
            } catch (Exception e) {
                Log.e(TAG, "Error signaling video EOS", e);
                videoEosReceived = true; // Mark as done to prevent infinite loop
            }
        } else {
            videoEosReceived = true;
        }

        // Step 2: Drain video encoder FIRST until EOS
        // This ensures we know exactly when video ends
        long safetyTimeoutMs = 10000; // 10 seconds safety timeout
        long startTime = System.currentTimeMillis();
        
        while (!videoEosReceived && (System.currentTimeMillis() - startTime < safetyTimeoutMs)) {
            try {
                videoEosReceived = drainEncoderUntilEos();
                if (!videoEosReceived) {
                    Thread.sleep(1);
                }
            } catch (Exception e) {
                Log.e(TAG, "Error draining video encoder", e);
                videoEosReceived = true;
            }
        }
        
        if (videoEosReceived) {
            Log.d(TAG, "Video encoder EOS received - video fully drained");
        } else {
            Log.w(TAG, "Video drain timeout - proceeding with audio stop");
        }

        // Step 3: NOW stop audio recording (after video is done)
        // This ensures audio records for at least as long as video
        if (audioRecordingEnabled) {
            try {
                // Stop the audio thread - this will trigger EOS signaling
                audioThreadRunning = false;
                
                // Wait for audio thread to signal EOS and exit
                if (audioThread != null) {
                    try {
                        audioThread.join(3000); // Wait up to 3s for audio thread
                        Log.d(TAG, "Audio thread joined successfully");
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        Log.w(TAG, "Interrupted waiting for audio thread");
                    }
                }
                
                // Now stop AudioRecord if still running
                if (audioRecord != null
                        && audioRecord.getRecordingState() == android.media.AudioRecord.RECORDSTATE_RECORDING) {
                    audioRecord.stop();
                    Log.d(TAG, "AudioRecord stopped");
                }
            } catch (Exception e) {
                Log.e(TAG, "Error stopping audio input", e);
            }
        }

        // Step 4: Drain audio encoder until EOS
        startTime = System.currentTimeMillis();
        while (!audioEosReceived && (System.currentTimeMillis() - startTime < safetyTimeoutMs)) {
            try {
                audioEosReceived = drainAudioEncoderUntilEos();
                if (!audioEosReceived) {
                    Thread.sleep(1);
                }
            } catch (Exception e) {
                Log.e(TAG, "Error draining audio encoder", e);
                audioEosReceived = true;
            }
        }

        if (audioEosReceived) {
            Log.d(TAG, "Audio encoder EOS received - audio fully drained");
        } else {
            Log.w(TAG, "Audio drain timeout");
        }

        Log.d(TAG, "Encoder drain completed - videoEos=" + videoEosReceived + ", audioEos=" + audioEosReceived);
    }

    /**
     * Drains video encoder until EOS flag is received.
     * @return true if EOS was received, false if more draining needed
     */
    private boolean drainEncoderUntilEos() {
        if (videoEncoder == null || mediaMuxer == null) {
            return true; // Nothing to drain
        }

        MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
        
        // Process all available buffers
        while (true) {
            int outputBufferIndex = videoEncoder.dequeueOutputBuffer(bufferInfo, 10000); // 10ms timeout
            
            if (outputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                return false; // No buffer available, need to try again
            } else if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                // Handle format change (should have happened during recording)
                MediaFormat newFormat = videoEncoder.getOutputFormat();
                Log.d(TAG, "Video format changed during drain: " + newFormat);
                if (!muxerStarted && videoTrackIndex == -1) {
                    cachedVideoFormat = newFormat;
                    videoTrackIndex = mediaMuxer.addTrack(newFormat);
                    if (!audioRecordingEnabled || audioTrackIndex != -1) {
                        mediaMuxer.start();
                        muxerStarted = true;
                    }
                }
            } else if (outputBufferIndex >= 0) {
                ByteBuffer encodedData = videoEncoder.getOutputBuffer(outputBufferIndex);
                
                if (encodedData != null && bufferInfo.size > 0 && muxerStarted && videoTrackIndex != -1) {
                    // Skip codec config data
                    if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                        try {
                            encodedData.position(bufferInfo.offset);
                            encodedData.limit(bufferInfo.offset + bufferInfo.size);
                            mediaMuxer.writeSampleData(videoTrackIndex, encodedData, bufferInfo);
                            segmentBytesWritten += bufferInfo.size;
                        } catch (Exception e) {
                            Log.e(TAG, "Error writing final video frame", e);
                        }
                    }
                }

                videoEncoder.releaseOutputBuffer(outputBufferIndex, false);

                // Check for EOS flag
                if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    Log.d(TAG, "Video encoder EOS received - all frames drained");
                    return true;
                }
            } else {
                // Unexpected return value
                Log.w(TAG, "Unexpected dequeueOutputBuffer result: " + outputBufferIndex);
                return false;
            }
        }
    }

    /**
     * Drains audio encoder until EOS flag is received.
     * @return true if EOS was received, false if more draining needed
     */
    private boolean drainAudioEncoderUntilEos() {
        if (!audioRecordingEnabled || audioEncoder == null || !audioEncoderStarted) {
            return true; // Nothing to drain
        }
        if (mediaMuxer == null) {
            return true;
        }

        MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
        
        while (true) {
            int outputBufferIndex = audioEncoder.dequeueOutputBuffer(bufferInfo, 10000); // 10ms timeout
            
            if (outputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                return false; // No buffer available, need to try again
            } else if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                MediaFormat newFormat = audioEncoder.getOutputFormat();
                Log.d(TAG, "Audio format changed during drain: " + newFormat);
                if (!muxerStarted && audioTrackIndex == -1) {
                    cachedAudioFormat = newFormat;
                    audioTrackIndex = mediaMuxer.addTrack(newFormat);
                    if (videoTrackIndex != -1) {
                        mediaMuxer.start();
                        muxerStarted = true;
                    }
                }
            } else if (outputBufferIndex >= 0) {
                ByteBuffer encodedData = audioEncoder.getOutputBuffer(outputBufferIndex);
                
                if (encodedData != null && bufferInfo.size > 0 && muxerStarted && audioTrackIndex != -1) {
                    // Skip codec config data
                    if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                        try {
                            encodedData.position(bufferInfo.offset);
                            encodedData.limit(bufferInfo.offset + bufferInfo.size);
                            mediaMuxer.writeSampleData(audioTrackIndex, encodedData, bufferInfo);
                            
                            audioSamplesWritten++;
                            lastAudioPts = bufferInfo.presentationTimeUs;
                            Log.d(TAG, String.format("[AUDIO-FINAL] sample #%d, pts=%dus (%.2fs)",
                                audioSamplesWritten, bufferInfo.presentationTimeUs, 
                                bufferInfo.presentationTimeUs / 1000000.0));
                        } catch (Exception e) {
                            Log.e(TAG, "Error writing final audio frame", e);
                        }
                    }
                }

                audioEncoder.releaseOutputBuffer(outputBufferIndex, false);

                // Check for EOS flag
                if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    Log.d(TAG, "Audio encoder EOS received - all frames drained");
                    return true;
                }
            } else {
                Log.w(TAG, "Unexpected audio dequeueOutputBuffer result: " + outputBufferIndex);
                return false;
            }
        }
    }

    /**
     * Emergency finalization of MediaMuxer to ensure file is always playable.
     * Uses multiple fallback strategies if normal stop() fails.
     */
    private void emergencyFinalizeMuxer() {
        Log.d(TAG, "Emergency finalizing muxer to ensure file playability");

        boolean muxerStopped = false;

        // Strategy 1: Normal stop (preferred)
        try {
            Log.d(TAG, "Attempting normal muxer stop");
            Log.d(TAG, String.format("═══ FINAL RECORDING STATS ═══\n" +
                "  Video: %d samples, last pts=%.2fs\n" +
                "  Audio: %d samples, last pts=%.2fs\n" +
                "  Duration diff: %.2fs\n" +
                "═══════════════════════════════",
                videoSamplesWritten, lastVideoPts / 1000000.0,
                audioSamplesWritten, lastAudioPts / 1000000.0,
                Math.abs(lastVideoPts - lastAudioPts) / 1000000.0));
            
            mediaMuxer.stop();
            muxerStopped = true;
            Log.d(TAG, "Normal muxer stop successful");
        } catch (Exception e) {
            Log.w(TAG, "Normal muxer stop failed, trying emergency strategies", e);
        }

        // Strategy 2: Force stop with small delay (if normal failed)
        if (!muxerStopped) {
            try {
                Log.d(TAG, "Attempting force muxer stop with delay");
                Thread.sleep(50); // Small delay to let pending operations complete
                mediaMuxer.stop();
                muxerStopped = true;
                Log.d(TAG, "Force muxer stop successful");
            } catch (Exception e) {
                Log.w(TAG, "Force muxer stop failed, trying final strategy", e);
            }
        }

        // Strategy 3: Release without stop (last resort to prevent app crash)
        if (!muxerStopped) {
            Log.w(TAG, "All muxer stop strategies failed, releasing without stop (file may have issues)");
        }

        // Always release the muxer (even if stop failed)
        try {
            mediaMuxer.release();
            Log.d(TAG, "Muxer released successfully");
        } catch (Exception e) {
            Log.e(TAG, "Error releasing muxer", e);
        } finally {
            mediaMuxer = null;
            muxerStarted = false;
        }

        // BULLETPROOF: If file exists but may be corrupted, log for user awareness
        if (!muxerStopped) {
            Log.w(TAG, "WARNING: Video file may have playback issues due to muxer stop failure");
            // Note: With our progressive MP4 implementation, even failed stops often result
            // in playable files
        }
    }

    /**
     * Configures basic encoder settings for maximum device compatibility.
     * Only applies essential settings that all devices should support.
     */
    private void configureBasicEncoder(MediaFormat format) {
        // Essential settings that all encoders must support
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        format.setInteger(MediaFormat.KEY_BIT_RATE, videoBitrate);
        format.setInteger(MediaFormat.KEY_FRAME_RATE, videoFramerate);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, VIDEO_IFRAME_INTERVAL);

        // ESSENTIAL: Bitrate mode for consistent quality and proper duration (API 21+)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            try {
                format.setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR);
                Log.d(TAG, "Applied VBR bitrate mode for proper frame timing");
            } catch (Exception e) {
                Log.w(TAG, "VBR bitrate mode not supported, using default", e);
            }
        }

        // ESSENTIAL: For constant framerate recording, especially on Samsung devices
        // Force constant framerate mode to avoid variable framerate encoding
        try {
            // This helps ensure the encoder produces constant framerate output
            format.setFloat(MediaFormat.KEY_CAPTURE_RATE, (float) videoFramerate);
            // Log.d(TAG, "Set MediaFormat.KEY_CAPTURE_RATE to " + videoFramerate + " for constant framerate");
        } catch (Exception e) {
            // Log.d(TAG, "KEY_CAPTURE_RATE not supported on this device");
        }

        // ESSENTIAL: Real-time priority for smooth recording (API 23+)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            try {
                format.setInteger(MediaFormat.KEY_PRIORITY, 0); // Real-time priority
                Log.d(TAG, "Applied real-time encoding priority");
            } catch (Exception e) {
                Log.w(TAG, "Priority setting not supported", e);
            }
        }

        // Log.d(TAG, "Applied basic encoder configuration: " +
        //     "bitrate=" + videoBitrate + ", framerate=" + videoFramerate + ", vbr=enabled, priority=realtime");
    }

    /**
     * Configures MediaFormat with industry-standard settings for maximum
     * compatibility
     * and reliability across all Android devices. This prevents common issues like
     * incorrect duration, static frames, audio problems, and video corruption.
     * Uses user-configured settings from SharedPreferences.
     */
    private void configureIndustryStandardEncoder(MediaFormat format, int width, int height) {
        // Basic encoder settings using user-configured values
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        format.setInteger(MediaFormat.KEY_BIT_RATE, videoBitrate); // Already using user's bitrate setting
        format.setInteger(MediaFormat.KEY_FRAME_RATE, videoFramerate); // Already using user's framerate setting
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, VIDEO_IFRAME_INTERVAL);

        // ESSENTIAL: For constant framerate recording, especially on Samsung devices
        try {
            format.setFloat(MediaFormat.KEY_CAPTURE_RATE, (float) videoFramerate);
            // Log.d(TAG, "Set MediaFormat.KEY_CAPTURE_RATE to " + videoFramerate + " for constant framerate");
        } catch (Exception e) {
            // Log.d(TAG, "KEY_CAPTURE_RATE not supported on this device");
        }

        // Industry Standard: Enhanced encoder configuration for reliability
        // These settings improve compatibility while respecting user's codec choice

        // Set profile and level for better compatibility (API 21+)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            try {
                String mimeType = videoCodec.getMimeType(); // Using user's selected codec
                if (mimeType.equals(MediaFormat.MIMETYPE_VIDEO_AVC)) {
                    // H.264 Baseline Profile for maximum compatibility
                    format.setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline);
                    format.setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel31);
                    Log.d(TAG, "Applied H.264 Baseline profile for compatibility");
                } else if (mimeType.equals(MediaFormat.MIMETYPE_VIDEO_HEVC)) {
                    // HEVC Main Profile
                    format.setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.HEVCProfileMain);
                    Log.d(TAG, "Applied HEVC Main profile");
                }
            } catch (Exception e) {
                Log.w(TAG, "Profile/level settings not supported, using defaults", e);
            }
        }

        // Set bitrate mode for consistent quality (API 21+)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            try {
                format.setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR);
                Log.d(TAG, "Applied VBR bitrate mode");
            } catch (Exception e) {
                Log.w(TAG, "VBR mode not supported, using default bitrate mode", e);
            }
        }

        // OPTIONAL: Set priority for real-time encoding (API 23+)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            try {
                format.setInteger(MediaFormat.KEY_PRIORITY, 0); // Real-time priority
                Log.d(TAG, "Applied real-time encoding priority");
            } catch (Exception e) {
                Log.w(TAG, "Priority setting not supported", e);
            }
        }

        // Log.d(TAG, "Applied industry-standard encoder configuration with user settings: " +
        //     "codec=" + videoCodec + ", bitrate=" + videoBitrate + ", framerate=" + videoFramerate);
    }

    /**
     * Initializes the encoder and input surface once at the beginning.
     * This should be called only once during the recording session.
     */
    private void initializeEncoder() throws IOException {
        // Use app's orientation setting, not device rotation
        boolean appWantsPortrait = "portrait".equalsIgnoreCase(orientation);
        boolean isSensorPortrait = videoHeight > videoWidth;
        boolean needsSwap = appWantsPortrait != isSensorPortrait;

        // Store the original dimensions before any swapping
        int originalWidth = videoWidth;
        int originalHeight = videoHeight;

        // Calculate encoder dimensions based on orientation
        encoderWidth = needsSwap ? videoHeight : videoWidth;
        encoderHeight = needsSwap ? videoWidth : videoHeight;

        Log.d("FAD-ENCODER", "Orientation setting: " + orientation);
        Log.d("FAD-ENCODER", "Original resolution: " + originalWidth + "x" + originalHeight);
        Log.d("FAD-ENCODER", "Final encoder resolution: " + encoderWidth + "x" + encoderHeight);

        // Industry Standard: Try encoder configurations with progressive fallbacks
        boolean encoderConfigured = false;
        String originalMimeType = videoCodec.getMimeType();
        String currentMimeType = originalMimeType;

        // Strategy 1: Try user's preferred codec with minimal settings
        try {
            // Log.d(TAG, "Attempting " + currentMimeType + " encoder with minimal settings");
            MediaFormat format = MediaFormat.createVideoFormat(currentMimeType, encoderWidth, encoderHeight);
            configureBasicEncoder(format);

            videoEncoder = MediaCodec.createEncoderByType(currentMimeType);
            videoEncoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            encoderInputSurface = videoEncoder.createInputSurface();
            encoderConfigured = true;
            // Log.d(TAG, "Successfully configured " + currentMimeType + " encoder with basic settings");
        } catch (Exception e) {
            Log.w(TAG, "Failed to configure " + currentMimeType + " with minimal settings: " + e.getMessage());
            if (videoEncoder != null) {
                try {
                    videoEncoder.release();
                } catch (Exception ignored) {
                }
                videoEncoder = null;
            }
        }

        // Strategy 2: If HEVC failed, try H.264 as fallback
        if (!encoderConfigured && currentMimeType.equals(MediaFormat.MIMETYPE_VIDEO_HEVC)) {
            try {
                currentMimeType = MediaFormat.MIMETYPE_VIDEO_AVC;
                Log.d(TAG, "Falling back to H.264 encoder");
                MediaFormat format = MediaFormat.createVideoFormat(currentMimeType, encoderWidth, encoderHeight);
                configureBasicEncoder(format);

                videoEncoder = MediaCodec.createEncoderByType(currentMimeType);
                videoEncoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
                encoderInputSurface = videoEncoder.createInputSurface();
                encoderConfigured = true;
                // Log.d(TAG, "Successfully configured H.264 fallback encoder");
            } catch (Exception e) {
                Log.e(TAG, "Failed to configure H.264 fallback encoder: " + e.getMessage());
                if (videoEncoder != null) {
                    try {
                        videoEncoder.release();
                    } catch (Exception ignored) {
                    }
                    videoEncoder = null;
                }
            }
        }

        if (!encoderConfigured) {
            throw new IOException("Failed to configure any video encoder. Device may not support video recording.");
        }

        // Update the codec reference to reflect what was actually used
        if (!currentMimeType.equals(originalMimeType)) {
            Log.i(TAG, "Codec changed from " + originalMimeType + " to " + currentMimeType + " due to compatibility");
        }

        // DEBUG: Log MediaCodec surface dimensions
        try {
            Log.d("DEBUG_RECORDING", "MediaCodec configured for: " + originalWidth + "x" + originalHeight);
            Log.d("MEDIACODEC_VERIFY", "Encoder dimensions: " + encoderWidth + "x" + encoderHeight);
        } catch (Exception e) {
            Log.e(TAG, "Error logging MediaCodec dimensions", e);
        }

        videoEncoder.start();

        if (glRenderer != null) {
            glRenderer.setEncoderDimensions(encoderWidth, encoderHeight);
        }
    }

    /**
     * Sets up the media muxer for the current output file.
     * Uses FragmentedMp4MuxerWrapper for fMP4 streaming (Media3).
     * This is called for each segment.
     */
    private void setupMuxer() throws IOException {
        // Use FragmentedMp4MuxerWrapper for fMP4 streaming
        // Native Android MediaMuxer does NOT support fragmented MP4 output!
        // We must use Media3's FragmentedMp4Muxer
        if (currentOutputFd != null) {
            mediaMuxer = new FragmentedMp4MuxerWrapper(currentOutputFd);
            Log.d(TAG, "Created FragmentedMp4Muxer with file descriptor (fMP4 for streaming)");
        } else {
            mediaMuxer = new FragmentedMp4MuxerWrapper(currentOutputFilePath);
            // Log.d(TAG, "Created FragmentedMp4Muxer with path: " + currentOutputFilePath + " (fMP4 for streaming)");
        }

        // Set location metadata if available
        if (locationLatitude != null && locationLongitude != null) {
            mediaMuxer.setLocation(locationLatitude.floatValue(), locationLongitude.floatValue());
            Log.d(TAG, "Location metadata set: " + locationLatitude + ", " + locationLongitude);
        } else {
            Log.d(TAG, "No location metadata available");
        }

        // Reset track indices
        audioTrackIndex = -1;
        videoTrackIndex = -1;

        // Only reset timestamps for very first segment; maintain monotonic PTS across segments
        if (segmentNumber == 1) {
            synchronized (timestampLock) {
                recordingStartTimeNanos = -1;
                recordingStartSystemTimeNanos = -1;
            }
        }

        // DO NOT start muxer here - wait for encoder formats to be available
        // This prevents the format change issue that causes muxer restarts
    muxerStarted = false;
    Log.d(TAG, "FragmentedMp4Muxer created but not started - waiting for encoder formats");
    // Reset per-segment state
    segmentBytesWritten = 0L;
    pendingRollover = false;
    awaitingKeyframeForRollover = false;
    rolloverRequestedByDrain = false;
    }

    private void setupEncoder() throws IOException {
        // Initialize encoder if not already done
        if (videoEncoder == null || encoderInputSurface == null) {
            initializeEncoder();
        }

        // Set up the muxer
        setupMuxer();

        // Log encoder and muxer status
        Log.d(TAG, "Encoder setup complete. Encoder: " + (videoEncoder != null ? "valid" : "null") +
                ", EncoderSurface: " + (encoderInputSurface != null ? "valid" : "null") +
                ", Muxer: " + (mediaMuxer != null ? "valid" : "null") +
                ", MuxerStarted: " + muxerStarted);
    }

    private void startRenderLoop() {
        if (renderThread == null) {
            renderThread = new HandlerThread("GLRenderThread");
            renderThread.start();
            handler = new Handler(renderThread.getLooper());
        }
        // Do not kick off render loop immediately; wait for first camera frame
    }

    // Render when a new frame is available (signaled by renderer)
    private final Runnable renderRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isRecording || released) {
                return;
            }

            try {
                // CRITICAL: Drain audio encoder FIRST before rendering and draining video
                // This ensures audio samples are queued in Media3 BEFORE video creates fragments
                // Otherwise audio samples get dropped when video keyframes trigger fragment creation
                if (audioRecordingEnabled && audioEncoder != null) {
                    drainAudioEncoder();
                }

                // Do NOT update watermark every frame; a separate timer handles it to avoid FPS
                // drops
                // Drain any queued GL operations to ensure they run on the GL thread
                Runnable action;
                while ((action = rendererActions.poll()) != null) {
                    try {
                        action.run();
                    } catch (Throwable t) {
                        android.util.Log.w(TAG, "Renderer action failed", t);
                    }
                }

                if (glRenderer != null) {
                    glRenderer.renderFrame();
                }
                drainEncoder();

                // Check if we need to split the segment due to size
                if (shouldSplitSegment()) {
                    Log.d(TAG, "Size limit reached, rolling over segment");
                    rolloverSegment();
                }

                // Continue rendering loop when new frames arrive (onFrameAvailable posts this
                // runnable).
                // Avoid self-posting to reduce CPU load and sustain high FPS.
            } catch (Exception e) {
                Log.e(TAG, "Error in render loop", e);
            }
        }
    };

    private void drainEncoder() {
        if (videoEncoder == null) {
            Log.d(TAG, "Encoder is null, skipping drainEncoder");
            return;
        }
        if (mediaMuxer == null) {
            Log.d(TAG, "Muxer is null, skipping drainEncoder");
            return;
        }

        MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
        try {
            while (true) {
                int outputBufferIndex = videoEncoder.dequeueOutputBuffer(bufferInfo, 0);
                if (outputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    break;
                } else if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat newFormat = videoEncoder.getOutputFormat();
                    Log.d(TAG, "Encoder output format changed: " + newFormat);
                    
                    // Inject frame rate and bitrate into output format for proper metadata
                    // The encoder's output format often doesn't include these values
                    if (!newFormat.containsKey(MediaFormat.KEY_FRAME_RATE)) {
                        newFormat.setInteger(MediaFormat.KEY_FRAME_RATE, videoFramerate);
                        Log.d(TAG, "Injected frame rate: " + videoFramerate);
                    }
                    if (!newFormat.containsKey(MediaFormat.KEY_BIT_RATE)) {
                        newFormat.setInteger(MediaFormat.KEY_BIT_RATE, videoBitrate);
                        Log.d(TAG, "Injected bitrate: " + videoBitrate);
                    }

                    // Cache for future segment rollovers
                    cachedVideoFormat = newFormat;

                    if (muxerStarted) {
                        // This should NOT happen if we wait for format before starting muxer
                        Log.e(TAG, "CRITICAL: Format changed after muxer started - this indicates a timing issue!");
                        try {
                            com.fadcam.Log.e(TAG, "CRITICAL: Format changed after muxer started - timing issue");
                        } catch (Throwable ignore) {
                        }
                        // Don't restart muxer - this causes duration issues
                        // Instead, log the error and continue with existing muxer
                        Log.w(TAG, "Continuing with existing muxer to prevent duration corruption");
                    } else {
                        // Normal case - add video track first
                        videoTrackIndex = mediaMuxer.addTrack(newFormat);
                        Log.d(TAG, "Added video track with index " + videoTrackIndex + " to muxer");
                        try {
                            com.fadcam.Log.d(TAG,
                                    "Video track added: index=" + videoTrackIndex + ", fmt=" + newFormat.toString());
                        } catch (Throwable ignore) {
                        }

                        // Start muxer immediately if audio is disabled
                        if (!audioRecordingEnabled) {
                            mediaMuxer.start();
                            muxerStarted = true;
                            Log.d(TAG, "Started muxer for video-only recording");
                            try {
                                com.fadcam.Log.d(TAG, "Muxer started (video-only)");
                            } catch (Throwable ignore) {
                            }
                        } else {
                            // Audio is enabled - check if audio track is already added
                            Log.d(TAG, "Audio recording enabled, checking audio track status. AudioTrackIndex: " + audioTrackIndex);
                            if (audioTrackIndex != -1) {
                                // Both tracks are ready, start muxer
                                mediaMuxer.start();
                                muxerStarted = true;
                                Log.d(TAG, "Started muxer with both video and audio tracks");
                                try {
                                    com.fadcam.Log.d(TAG, "Muxer started (audio+video)");
                                } catch (Throwable ignore) {
                                }
                            } else {
                                // Wait for audio track to be added
                                Log.d(TAG, "Video track added, waiting for audio track before starting muxer");
                                Log.d(TAG, "DEBUG: audioRecordingEnabled=" + audioRecordingEnabled + ", audioEncoder=" + (audioEncoder != null) + ", audioTrackIndex=" + audioTrackIndex);
                                try {
                                    com.fadcam.Log.d(TAG, "Waiting for audio track before starting muxer");
                                } catch (Throwable ignore) {
                                }
                            }
                        }
                    }
                } else if (outputBufferIndex >= 0) {
                    ByteBuffer encodedData = videoEncoder.getOutputBuffer(outputBufferIndex);
                    if (encodedData == null) {
                        Log.e(TAG, "encoderOutputBuffer " + outputBufferIndex + " was null");
                        videoEncoder.releaseOutputBuffer(outputBufferIndex, false);
                        continue;
                    }

                    if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                        // Codec config data - we don't write this to the muxer
                        bufferInfo.size = 0;
                    }

                    // Check for valid data before writing to muxer
                    boolean isKeyframe = (bufferInfo.flags & MediaCodec.BUFFER_FLAG_SYNC_FRAME) != 0;

                    // Preemptive keyframe request when near threshold (only once)
                    if (!pendingRollover && maxFileSizeBytes > 0 && segmentBytesWritten >= (long) (maxFileSizeBytes * ROLLOVER_PREEMPT_THRESHOLD_RATIO)) {
                        try {
                            if (android.os.Build.VERSION.SDK_INT >= 19) {
                                android.os.Bundle params = new android.os.Bundle();
                                params.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0);
                                videoEncoder.setParameters(params);
                                Log.d(TAG, "Requested sync frame pre-rollover at ~95% threshold");
                            }
                        } catch (Exception ex) {
                            Log.w(TAG, "Failed requesting sync frame", ex);
                        }
                        pendingRollover = true;
                        awaitingKeyframeForRollover = true;
                    }

                    if (bufferInfo.size > 0 && muxerStarted) {
                        // Ensure we have valid data in the buffer
                        if (encodedData.remaining() < bufferInfo.size) {
                            Log.w(TAG, "Buffer size mismatch: remaining=" + encodedData.remaining() +
                                    ", bufferInfo.size=" + bufferInfo.size +
                                    " - adjusting size to prevent error");
                            bufferInfo.size = encodedData.remaining();
                        }

                        // Only write if we have valid data and muxer is still valid
                        if (bufferInfo.size > 0 && mediaMuxer != null && muxerStarted && videoTrackIndex != -1) {
                            try {
                                encodedData.position(bufferInfo.offset);
                                encodedData.limit(bufferInfo.offset + bufferInfo.size);
                                mediaMuxer.writeSampleData(videoTrackIndex, encodedData, bufferInfo);
                                
                                videoSamplesWritten++;
                                lastVideoPts = bufferInfo.presentationTimeUs;
                                // Log every frame for debugging (can reduce frequency later)
                                if (videoSamplesWritten % 30 == 0) {
                                    Log.i(TAG, String.format("[VIDEO_WRITE] Sample #%d, PTS=%.3fs (%dus), size=%db, keyframe=%s",
                                        videoSamplesWritten, bufferInfo.presentationTimeUs / 1000000.0, 
                                        bufferInfo.presentationTimeUs, bufferInfo.size, isKeyframe));
                                }
                                
                                segmentBytesWritten += bufferInfo.size;
                                if (!rolloverRequestedByDrain && awaitingKeyframeForRollover && isKeyframe && maxFileSizeBytes > 0 && segmentBytesWritten >= maxFileSizeBytes) {
                                    Log.i(TAG, "Keyframe boundary reached with size >= limit (" + segmentBytesWritten + "); scheduling rollover");
                                    awaitingKeyframeForRollover = false;
                                    pendingRollover = false;
                                    rolloverRequestedByDrain = true; // Will execute after draining
                                }
                            } catch (Exception e) {
                                Log.e(TAG, "Error writing video frame to muxer", e);
                                try {
                                    com.fadcam.Log.e(TAG, "Error writing video frame to muxer", e);
                                } catch (Throwable ignore) {
                                }
                                // BULLETPROOF: Continue processing but mark potential corruption
                                if (e.getMessage() != null && e.getMessage().contains("muxer")) {
                                    Log.w(TAG, "Muxer error detected - file may need emergency finalization");
                                    try {
                                        com.fadcam.Log.w(TAG,
                                                "Muxer error detected - will attempt emergency finalize if needed");
                                    } catch (Throwable ignore) {
                                    }
                                }
                            }
                        }
                    } else if (bufferInfo.size > 0 && !muxerStarted) {
                        Log.d(TAG, "Dropping encoded frame because muxer isn't started yet");
                        try {
                            com.fadcam.Log.w(TAG, "Dropping encoded frame (muxer not started yet)");
                        } catch (Throwable ignore) {
                        }
                    }

                    videoEncoder.releaseOutputBuffer(outputBufferIndex, false);
                    if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        Log.d(TAG, "End of stream reached in video encoder");
                        break;
                    }
                }
            }

            // NOTE: Audio draining is now done at the START of render loop (renderRunnable)
            // This ensures audio samples are queued BEFORE video creates fragments

            // Perform rollover after draining to ensure clean boundary
            if (rolloverRequestedByDrain && !rolloverInProgress) {
                rolloverRequestedByDrain = false;
                try {
                    rolloverSegment();
                } catch (Exception ex) {
                    Log.e(TAG, "Deferred rollover failed", ex);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error draining encoder", e);
        }
    }

    private boolean shouldSplitSegment() {
        if (maxFileSizeBytes <= 0) return false; // disabled
        // Avoid repeated triggers during pending states
        if (pendingRollover || awaitingKeyframeForRollover || rolloverInProgress || rolloverRequestedByDrain) return false;

        if (segmentBytesWritten >= maxFileSizeBytes) {
            // Force sync-frame rollover if we somehow exceeded without preemption
            Log.i(TAG, "Segment bytes exceeded limit before keyframe; requesting sync frame now");
            try {
                if (android.os.Build.VERSION.SDK_INT >= 19) {
                    android.os.Bundle params = new android.os.Bundle();
                    params.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0);
                    videoEncoder.setParameters(params);
                }
            } catch (Exception e) {
                Log.w(TAG, "Failed to request sync frame in force path", e);
            }
            pendingRollover = true;
            awaitingKeyframeForRollover = true;
            return false;
        }
        return false; // Actual rollover scheduled inside drainEncoder after keyframe
    }

    private void rolloverSegment() {
        try {
            Log.i(TAG, "Auto-splitting: segment size limit reached, rolling over to next segment");
            Log.d(TAG, "Current segment number: " + segmentNumber + ", rolling over to " + (segmentNumber + 1));
            if (rolloverInProgress) {
                Log.w(TAG, "Rollover already in progress; skipping");
                return;
            }
            rolloverInProgress = true;

            // First, request the next segment file/descriptor from callback
            // This is done first to ensure we have a valid output before stopping the
            // current muxer
            segmentNumber++;
            Log.d(TAG, "Increment segment number to: " + segmentNumber);

            if (segmentCallback != null) {
                Log.d(TAG, "Calling segment callback for next segment");
                segmentCallback.onSegmentRollover(segmentNumber);
            } else {
                Log.e(TAG, "Segment callback is null, cannot continue rollover");
                throw new IllegalStateException("Segment callback is null");
            }

            // Check if callback set the new output
            if (currentOutputFilePath == null && currentOutputFd == null) {
                Log.e(TAG, "Segment callback did not set new output path or descriptor");
                throw new IllegalStateException("No output path or descriptor set after callback");
            }

            // Now we can safely stop and release the current muxer
            if (mediaMuxer != null) {
                try {
                    if (muxerStarted) {
                        Log.d(TAG, "Stopping current muxer");
                        mediaMuxer.stop();
                    }
                    Log.d(TAG, "Releasing current muxer");
                    mediaMuxer.release();
                } catch (Exception e) {
                    Log.e(TAG, "Error releasing muxer during rollover", e);
                }
                mediaMuxer = null;
                muxerStarted = false;
            }

            // Reset track indices
            audioTrackIndex = -1;

            try {
                // Create new muxer for the next segment
                setupMuxer();

                // Re-add cached tracks because encoder will NOT emit format changed again
                if (cachedVideoFormat != null) {
                    try {
                        videoTrackIndex = mediaMuxer.addTrack(cachedVideoFormat);
                        Log.d(TAG, "Added cached video track to new muxer. index=" + videoTrackIndex);
                    } catch (Exception e) {
                        Log.e(TAG, "Failed adding cached video track to new muxer", e);
                        throw e;
                    }
                } else {
                    Log.w(TAG, "Video format not cached; new segment will wait for format change (unlikely)");
                }

                if (audioRecordingEnabled) {
                    if (cachedAudioFormat != null) {
                        try {
                            audioTrackIndex = mediaMuxer.addTrack(cachedAudioFormat);
                            Log.d(TAG, "Added cached audio track to new muxer. index=" + audioTrackIndex);
                        } catch (Exception e) {
                            Log.e(TAG, "Failed adding cached audio track to new muxer", e);
                            // Continue without audio for this segment
                        }
                    } else {
                        Log.w(TAG, "Audio format not cached; audio may be missing in new segment");
                    }
                }

                // Start muxer if conditions met (mirror initial logic)
                if (!audioRecordingEnabled) {
                    if (videoTrackIndex != -1 && !muxerStarted) {
                        mediaMuxer.start();
                        muxerStarted = true;
                        Log.d(TAG, "Started new muxer (video-only) for segment " + segmentNumber);
                    }
                } else {
                    if (videoTrackIndex != -1 && audioTrackIndex != -1 && !muxerStarted) {
                        mediaMuxer.start();
                        muxerStarted = true;
                        Log.d(TAG, "Started new muxer (audio+video) for segment " + segmentNumber);
                    } else {
                        Log.d(TAG, "Deferred starting new muxer: videoTrackIndex=" + videoTrackIndex +
                                ", audioTrackIndex=" + audioTrackIndex + ", muxerStarted=" + muxerStarted);
                    }
                }

                // Force a frame render to ensure the encoder has valid data for the new segment
                if (glRenderer != null) {
                    glRenderer.renderFrame();
                    Log.d(TAG, "Forced a frame render for the new segment");
                }

                Log.i(TAG, "Started new segment: " + segmentNumber +
                        (currentOutputFilePath != null ? " at path: " + currentOutputFilePath
                                : " with file descriptor"));
                rolloverInProgress = false;
            } catch (Exception e) {
                Log.e(TAG, "Error setting up muxer for new segment", e);
                throw e; // Re-throw to be caught by outer try-catch
            }
        } catch (Exception e) {
            Log.e(TAG, "Error during segment rollover", e);
            stopRecording();
            rolloverInProgress = false;
        }
    }

    /**
     * Stops recording and releases all resources with bulletproof error handling.
     * Ensures recorded files are always playable even when errors occur.
     * 
     * CRITICAL FIX: Proper stop order for VLC/FFmpeg compatibility:
     * 1. Stop render thread (no more video frames)
     * 2. Signal and drain encoders until EOS (NOT timeout-based!)
     * 3. THEN finalize muxer
     * 4. THEN release all resources
     */
    public void stopRecording() {
        Log.d(TAG, "stopRecording: Stopping recording and releasing resources");
        try {
            com.fadcam.Log.d(TAG, "stopRecording: Stopping recording and releasing resources");
        } catch (Throwable ignore) {
        }
        if (isStopped || released) {
            Log.d(TAG, "stopRecording: Already stopped or released, ignoring duplicate call");
            return;
        }
        isStopped = true;
        released = true;
        isRecording = false;

        if (handler != null) {
            handler.removeCallbacksAndMessages(null);
        }

        // Proactively release preview EGL on the GL thread to ensure native disconnect
        if (handler != null && glRenderer != null) {
            final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
            try {
                handler.post(() -> {
                    try {
                        glRenderer.releasePreviewEGL();
                    } catch (Throwable t) {
                        android.util.Log.w(TAG, "releasePreviewEGL on GL thread failed", t);
                    } finally {
                        latch.countDown();
                    }
                });
                // Wait briefly; don't block shutdown too long
                latch.await(200, java.util.concurrent.TimeUnit.MILLISECONDS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        }

        // Stop watermark updates
        try {
            watermarkUpdateHandler.removeCallbacksAndMessages(null);
            updateWatermarkRunnable = null;
        } catch (Exception e) {
            Log.w(TAG, "Error stopping watermark updater", e);
            try {
                com.fadcam.Log.w(TAG, "Error stopping watermark updater: " + e.getMessage());
            } catch (Throwable ignore) {
            }
        }

        // CRITICAL FIX: Drain encoders FIRST while GL thread is still alive
        // The video encoder's input surface uses the GL thread's SurfaceTexture
        // If we stop the render thread first, the SurfaceTexture handler dies
        // and we get "Handler sending message to a dead thread" errors
        // This is the production-grade EOS-based drain that waits for actual EOS flags
        emergencyDrainEncoders();

        // NOW stop render thread after encoders are fully drained
        // No more video frames will be encoded after this
        if (renderThread != null) {
            try {
                renderThread.quitSafely();
                renderThread.join(300); // Wait for render thread to exit
            } catch (InterruptedException e) {
                Log.w(TAG, "Interrupted while waiting for render thread to exit", e);
                Thread.currentThread().interrupt();
            }
            renderThread = null;
        }

        // Now stop and release the video encoder
        if (videoEncoder != null) {
            try {
                Log.d(TAG, "Stopping video encoder");
                videoEncoder.stop();
                videoEncoder.release();
            } catch (Exception e) {
                Log.e(TAG, "Error stopping video encoder", e);
                // BULLETPROOF: Force release even if stop() fails
                try {
                    videoEncoder.release();
                } catch (Exception releaseEx) {
                    Log.e(TAG, "Error force-releasing video encoder", releaseEx);
                }
            } finally {
                videoEncoder = null;
            }
        }

        // BULLETPROOF: Stop and release the media muxer with emergency finalization
        if (mediaMuxer != null && muxerStarted) {
            emergencyFinalizeMuxer();
        }

        // Release encoder input surface
        if (encoderInputSurface != null) {
            try {
                encoderInputSurface.release();
            } catch (Exception e) {
                Log.e(TAG, "Error releasing encoder input surface", e);
            } finally {
                encoderInputSurface = null;
            }
        }

        // Release GL renderer last to ensure proper cleanup of OpenGL resources
        if (glRenderer != null) {
            try {
                Log.d(TAG, "Releasing GL renderer");
                glRenderer.release();
            } catch (Exception e) {
                Log.e(TAG, "Error releasing renderer", e);
            } finally {
                glRenderer = null;
            }
        }

        // Clear references to other surfaces
        handler = null;
        previewSurface = null;
        cameraInputSurface = null;

        // Clean up audio encoder (already drained in emergencyDrainEncoders)
        if (audioEncoder != null) {
            try {
                audioEncoder.stop();
                audioEncoder.release();
            } catch (Exception e) {
                Log.e(TAG, "Error stopping audio encoder", e);
            } finally {
                audioEncoder = null;
            }
        }
        
        // Clean up audio record (stopped in emergencyDrainEncoders, but need to release)
        if (audioRecord != null) {
            try {
                // Make sure it's stopped first
                if (audioRecord.getRecordingState() == android.media.AudioRecord.RECORDSTATE_RECORDING) {
                    audioRecord.stop();
                }
                audioRecord.release();
            } catch (Exception ignored) {
            }
            audioRecord = null;
        }

        // Release audio focus to allow other apps to use audio
        if (audioManager != null) {
            try {
                // Restore original audio mode and speakerphone state
                if (originalAudioMode != -1) {
                    audioManager.setMode(originalAudioMode);
                    Log.i(TAG, "AudioManager mode restored to: " + originalAudioMode);
                }
                audioManager.setSpeakerphoneOn(originalSpeakerphoneOn);
                Log.i(TAG, "Speakerphone restored to: " + originalSpeakerphoneOn);
                
                // Release audio focus
                if (audioFocusListener != null) {
                    audioManager.abandonAudioFocus(audioFocusListener);
                }
            } catch (Exception e) {
                Log.e(TAG, "Error releasing audio resources", e);
            }
            audioManager = null;
            audioFocusListener = null;
            originalAudioMode = -1;
            originalSpeakerphoneOn = false;
        }
        
        // Join audio thread if still running (should have exited after EOS)
        if (audioThread != null) {
            try {
                audioThread.join(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            audioThread = null;
        }
        
        audioTrackIndex = -1;
        audioEncoderStarted = false;
        // Reset EOS tracking flags for next recording
        videoEosReceived = false;
        audioEosReceived = false;

        Log.d(TAG, "GLRecordingPipeline stopped and released.");
        // Reset flags so a new recording can be started cleanly
        isStopped = false;
        released = false;
        isRecording = false;
        // Clear retry/time bases
        recordingStartTimeNanos = -1;
        recordingStartSystemTimeNanos = -1;
    }

    /**
     * Updates the watermark text in real time.
     */
    public void updateWatermark() {
        if (glRenderer == null || watermarkInfoProvider == null)
            return;
        final String text = watermarkInfoProvider.getWatermarkText();
        // Ensure the GL texture update runs on the render thread with EGL context
        // current
        if (handler != null) {
            handler.post(() -> {
                try {
                    glRenderer.updateWatermarkTextOnGlThread(text != null ? text : "");
                } catch (Exception e) {
                    Log.w(TAG, "updateWatermark: GL thread update failed", e);
                }
            });
        }
    }

    /**
     * Returns the Surface to be used as the camera output target.
     */
    public Surface getCameraInputSurface() {
        return cameraInputSurface;
    }

    /**
     * Pauses the recording pipeline with proper timestamp tracking.
     * During pause, we record the last known PTS values to maintain
     * timeline continuity when resuming (especially important for camera switch).
     */
    public void pauseRecording() {
        if (!isRecording || isStopped) {
            Log.w(TAG, "Cannot pause recording - recording is not active");
            return;
        }

        Log.i(TAG, "========== PAUSE RECORDING ===========");

        synchronized (timestampLock) {
            // Record pause start time for duration tracking
            pauseStartTimeNanos = System.nanoTime();
            isPaused = true;
            
            // Save last known PTS values before pause
            lastVideoPtsBeforePauseUs = lastVideoPts;
            lastAudioPtsBeforePauseUs = lastAudioPts;
            
            Log.i(TAG, "[PAUSE] Pause started at " + (pauseStartTimeNanos / 1_000_000L) + "ms");
            Log.i(TAG, "[PAUSE] Last video PTS: " + lastVideoPtsBeforePauseUs + "us (" + (lastVideoPtsBeforePauseUs / 1000.0) + "ms)");
            Log.i(TAG, "[PAUSE] Last audio PTS: " + lastAudioPtsBeforePauseUs + "us (" + (lastAudioPtsBeforePauseUs / 1000.0) + "ms)");
            Log.i(TAG, "[PAUSE] Total pause duration so far: " + (totalPauseDurationNanos / 1_000_000L) + "ms");
            Log.i(TAG, "[PAUSE] Video samples written: " + videoSamplesWritten);
            Log.i(TAG, "[PAUSE] Audio samples written: " + audioSamplesWritten);
        }

        // Set recording flag to false to stop encoding new frames
        isRecording = false;
        if (updateWatermarkRunnable != null) {
            watermarkUpdateHandler.removeCallbacks(updateWatermarkRunnable);
        }

        // Pause audio recording if enabled
        if (audioRecordingEnabled && audioRecord != null) {
            try {
                if (audioRecord.getRecordingState() == android.media.AudioRecord.RECORDSTATE_RECORDING) {
                    audioRecord.stop();
                    Log.d(TAG, "Audio recording paused");
                }
            } catch (Exception e) {
                Log.e(TAG, "Error pausing audio recording", e);
            }
        }

        Log.d(TAG, "Recording paused successfully");
    }
    
    /**
     * Prepares for a camera switch by setting the appropriate flags.
     * Call this before pauseRecording() when switching cameras.
     */
    public void prepareCameraSwitch() {
        synchronized (timestampLock) {
            isCameraSwitchPause = true;
            Log.i(TAG, "========== PREPARE CAMERA SWITCH ===========");
            Log.i(TAG, "[CAMERA_SWITCH] Prepared - timestamps will be adjusted on resume");
            Log.i(TAG, "[CAMERA_SWITCH] Current video PTS: " + lastVideoPts + "us");
            Log.i(TAG, "[CAMERA_SWITCH] Current audio PTS: " + lastAudioPts + "us");
        }
    }

    /**
     * Resumes the recording pipeline (no-op, for API compatibility).
     */
    public void resumeRecording() {
        if (isRecording || isStopped) {
            Log.w(TAG, "Cannot resume recording - recording is either already active or has been stopped");
            return;
        }

        Log.d(TAG, "Resuming recording");

        Log.i(TAG, "========== RESUME RECORDING ===========");
        
        synchronized (timestampLock) {
            // Calculate pause duration for logging
            if (pauseStartTimeNanos > 0) {
                long pauseDuration = System.nanoTime() - pauseStartTimeNanos;
                totalPauseDurationNanos += pauseDuration;
                Log.i(TAG, "[RESUME] This pause duration: " + (pauseDuration / 1_000_000L) + "ms");
                Log.i(TAG, "[RESUME] Total pause duration: " + (totalPauseDurationNanos / 1_000_000L) + "ms");
            }
            
            isPaused = false;
            pauseStartTimeNanos = -1;
        }

        // Resume audio recording if enabled
        if (audioRecordingEnabled && audioRecord != null) {
            try {
                if (audioRecord.getRecordingState() != android.media.AudioRecord.RECORDSTATE_RECORDING) {
                    audioRecord.startRecording();
                    Log.d(TAG, "Audio recording resumed");
                }
            } catch (Exception e) {
                Log.e(TAG, "Error resuming audio recording", e);
            }
        }

        // Set recording flag to true to resume encoding frames
        isRecording = true;
        // Resume periodic watermark updates and force an immediate refresh.
        ensureWatermarkUpdaterRunning();
        updateWatermark();

        // Force a frame render to ensure we have a valid frame after resuming
        if (glRenderer != null && handler != null) {
            handler.post(() -> {
                try {
                    glRenderer.renderFrame();
                    Log.d(TAG, "Forced a frame render after resuming");
                } catch (Exception e) {
                    Log.e(TAG, "Error rendering frame after resume", e);
                }
            });
        }

        Log.d(TAG, "Recording resumed successfully");
    }

    /**
     * Sets the next output file or FileDescriptor for segment rollover.
     * Call this from the segment callback to update the output destination before
     * calling setupEncoder().
     * 
     * @param filePath The file path for the next segment (internal storage), or
     *                 null if using SAF
     * @param fd       The FileDescriptor for the next segment (SAF), or null if
     *                 using internal storage
     */
    public void setNextOutput(String filePath, FileDescriptor fd) {
        this.currentOutputFilePath = filePath;
        this.currentOutputFd = fd;
    }

    /**
     * Updates the device orientation for the renderer to adjust the preview.
     * 
     * @param deviceOrientation The current orientation of the device (e.g.,
     *                          Surface.ROTATION_0).
     */
    public void setDeviceOrientation(int deviceOrientation) {
        this.deviceOrientation = deviceOrientation;
        if (glRenderer != null) {
            glRenderer.setDeviceOrientation(deviceOrientation);
        }
    }

    /**
     * Sets the exposure compensation for the GL shader.
     * This applies exposure changes in the OpenGL pipeline, affecting both preview
     * and recording.
     * 
     * @param evStops Exposure compensation in EV stops (e.g., -2.0 to +2.0)
     */
    public void setExposureCompensation(float evStops) {
        Log.d(TAG, "GLRecordingPipeline: Setting exposure compensation to " + evStops + " EV stops");
        if (glRenderer != null) {
            glRenderer.setExposureCompensation(evStops);
        }
    }

    /**
     * Sets the preview surface with IMMEDIATE application (no debounce).
     * Use this for critical transitions like fullscreen where delay causes stuck preview.
     * 
     * @param surface The Surface to render the preview on.
     */
    public void setPreviewSurfaceImmediate(Surface surface) {
        this.previewSurface = surface;
        Log.d(TAG, "setPreviewSurfaceImmediate: " + (surface != null && surface.isValid() ? "VALID" : "NULL"));
        
        if (glRenderer != null) {
            synchronized (previewApplyLock) {
                // Cancel any pending debounced apply
                if (pendingPreviewApplyRunnable != null && handler != null) {
                    try {
                        handler.removeCallbacks(pendingPreviewApplyRunnable);
                    } catch (Throwable ignore) {}
                    pendingPreviewApplyRunnable = null;
                    pendingPreviewToApply = null;
                }
            }
            
            // Apply IMMEDIATELY via rendererActions queue
            final Surface s = surface;
            rendererActions.offer(() -> {
                try {
                    if (s == null || !s.isValid()) {
                        Log.d(TAG, "Releasing preview EGL (immediate)");
                        glRenderer.releasePreviewEGL();
                    } else {
                        Log.d(TAG, "Setting preview surface to glRenderer (immediate)");
                        glRenderer.setPreviewSurface(s);
                        // Force a re-render by requesting next frame
                        Log.d(TAG, "Preview surface applied immediately - forcing render");
                    }
                } catch (Throwable t) {
                    Log.e(TAG, "Immediate preview apply failed", t);
                }
            });
        } else {
            Log.w(TAG, "setPreviewSurfaceImmediate called but glRenderer is null");
        }
    }

    /**
     * Sets the preview surface for rendering the camera feed.
     * 
     * @param surface The Surface to render the preview on.
     */
    public void setPreviewSurface(Surface surface) {
        this.previewSurface = surface;
        // Debounce rapid preview surface swaps to avoid EGL create/destroy churn
        if (glRenderer != null) {
            // If we have a GL-thread handler, schedule a delayed apply so multiple
            // quick changes are coalesced into a single create/destroy operation.
            if (handler != null) {
                synchronized (previewApplyLock) {
                    // Cancel any previously scheduled apply
                    if (pendingPreviewApplyRunnable != null) {
                        try {
                            handler.removeCallbacks(pendingPreviewApplyRunnable);
                        } catch (Throwable ignore) {
                        }
                    }
                    // Store the latest surface to apply
                    pendingPreviewToApply = surface;
                    pendingPreviewApplyRunnable = () -> {
                        final Surface s = pendingPreviewToApply;
                        try {
                            if (s == null || !s.isValid()) {
                                glRenderer.releasePreviewEGL();
                            } else {
                                glRenderer.setPreviewSurface(s);
                            }
                        } catch (Throwable t) {
                            Log.w(TAG, "Deferred preview apply failed", t);
                        } finally {
                            synchronized (previewApplyLock) {
                                pendingPreviewApplyRunnable = null;
                                pendingPreviewToApply = null;
                            }
                        }
                    };
                    // Small debounce delay: 200ms (coalesce frequent toggles)
                    handler.postDelayed(pendingPreviewApplyRunnable, 200);
                }
            } else {
                // No handler yet - fall back to immediate apply via rendererActions
                final Surface s = surface;
                rendererActions.offer(() -> {
                    if (s == null || !s.isValid()) {
                        glRenderer.releasePreviewEGL();
                    } else {
                        glRenderer.setPreviewSurface(s);
                    }
                });
            }
        }
    }

    /**
     * Updates the surface dimensions and informs the renderer.
     * This should be called whenever the preview surface size changes.
     *
     * @param width  The width of the surface
     * @param height The height of the surface
     */
    public void updateSurfaceDimensions(int width, int height) {
        if (width > 0 && height > 0 && (mSurfaceWidth != width || mSurfaceHeight != height)) {
            Log.d(TAG, "Surface dimensions changed: " + width + "x" + height);
            mSurfaceWidth = width;
            mSurfaceHeight = height;

            if (glRenderer != null) {
                glRenderer.setSurfaceDimensions(width, height);
            }
        }
    }

    public String getOrientation() {
        return orientation;
    }

    public int getSensorOrientation() {
        return sensorOrientation;
    }

    /**
     * Forces a consistent aspect ratio and dimensions by setting the
     * camera's default buffer size to match our target dimensions.
     */
    private void forceFixedDimensions() {
        if (glRenderer != null) {
            // Update the camera screen nail size to match our fixed dimensions
            glRenderer.setSurfaceDimensions(videoWidth, videoHeight);

            // Debug aspect ratio information
            float recordingAspectRatio = (float) videoWidth / videoHeight;
            Log.d("DEBUG_ASPECT", "Recording dimensions: " + videoWidth + "x" + videoHeight);
            Log.d("DEBUG_ASPECT", "Recording aspect ratio: " + recordingAspectRatio);
            Log.d("DEBUG_ASPECT", "Forcing fixed dimensions: " + videoWidth + "x" + videoHeight +
                    " with aspect ratio " + targetAspectRatio);

            // Compare with the target aspect ratio
            if (Math.abs(recordingAspectRatio - targetAspectRatio) > 0.01f) {
                Log.w("DEBUG_ASPECT", "Recording aspect ratio doesn't match target aspect ratio!");
            }
        }
    }

    /**
     * Debug method to compare preview and recording dimensions and aspect ratios.
     */
    private void debugPreviewVsRecording() {
        Log.d("DEBUG_COMPARISON", "Preview surface: " + mSurfaceWidth + "x" + mSurfaceHeight);
        Log.d("DEBUG_COMPARISON", "Recording surface: " + videoWidth + "x" + videoHeight);

        float previewAspectRatio = (float) mSurfaceWidth / mSurfaceHeight;
        float recordingAspectRatio = (float) videoWidth / videoHeight;

        Log.d("DEBUG_COMPARISON", "Preview aspect ratio: " + previewAspectRatio);
        Log.d("DEBUG_COMPARISON", "Recording aspect ratio: " + recordingAspectRatio);

        if (Math.abs(previewAspectRatio - recordingAspectRatio) > 0.01f) {
            Log.w("DEBUG_COMPARISON", "Preview and recording aspect ratios don't match!");
        } else {
            Log.d("DEBUG_COMPARISON", "Preview and recording aspect ratios match.");
        }
    }

    /**
     * Initializes audio settings from SharedPreferencesManager.
     * This should be called before starting audio recording/encoding.
     */
    private void initAudioSettings() {
        com.fadcam.SharedPreferencesManager prefs = com.fadcam.SharedPreferencesManager.getInstance(context);
        this.audioRecordingEnabled = prefs.isRecordAudioEnabled();
        this.audioBitrate = prefs.getAudioBitrate();
        this.audioSampleRate = prefs.getAudioSamplingRate();
        // Always use stereo (2 channels) for best quality
        this.audioChannelCount = 2;
        // Audio source selection logic (default to MIC)
        String audioInputSource = null;
        // Use CAMCORDER for high-quality audio recording
        // This provides better audio quality than VOICE_COMMUNICATION
        // Background recording is enabled via foreground service with MICROPHONE type
        this.audioSource = android.media.MediaRecorder.AudioSource.CAMCORDER;
    }

    /**
     * Sets up the audio encoder and AudioRecord for AAC audio capture.
     * Call before starting audio thread.
     */
    private void setupAudio() {
        if (!audioRecordingEnabled) {
            Log.d(TAG, "DEBUG: Audio recording disabled, skipping audio setup");
            return;
        }
        
        Log.d(TAG, "DEBUG: Setting up audio encoder and recorder");
        try {
            // Configure MediaCodec for AAC
            MediaFormat audioFormat = MediaFormat.createAudioFormat(
                    android.media.MediaFormat.MIMETYPE_AUDIO_AAC,
                    audioSampleRate,
                    audioChannelCount);
            audioFormat.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
            audioFormat.setInteger(MediaFormat.KEY_BIT_RATE, audioBitrate);
            // Increase input size to avoid starvation and crackling under load
            // Empirically validated values from user report
            int desiredMaxInput = 262144; // 256 KiB
            try {
                audioFormat.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, desiredMaxInput);
            } catch (Exception e) {
                Log.w(TAG, "KEY_MAX_INPUT_SIZE not supported as integer?", e);
            }
            // Explicitly declare PCM encoding when available (API 24+)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                try {
                    audioFormat.setInteger(MediaFormat.KEY_PCM_ENCODING, android.media.AudioFormat.ENCODING_PCM_16BIT);
                } catch (Exception e) {
                    Log.w(TAG, "KEY_PCM_ENCODING not supported", e);
                }
            }
            
            // Disable SBR mode to avoid corruption on some devices (e.g., TECNO with Codec2)
            // SBR (Spectral Band Replication) mode 3 can cause "Reserved bit set" errors
            try {
                audioFormat.setInteger("aac-sbr-mode", 0); // Disable SBR
                Log.d(TAG, "Disabled AAC SBR mode");
            } catch (Exception e) {
                Log.w(TAG, "aac-sbr-mode not supported (harmless, Codec2 may override)", e);
            }
            
            audioEncoder = MediaCodec.createEncoderByType(android.media.MediaFormat.MIMETYPE_AUDIO_AAC);
            audioEncoder.configure(audioFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            audioEncoder.start();
            audioEncoderStarted = true;
            Log.d(TAG, "DEBUG: Audio encoder created and started successfully");

            // Setup AudioRecord
            int channelConfig = audioChannelCount == 2 ? android.media.AudioFormat.CHANNEL_IN_STEREO
                    : android.media.AudioFormat.CHANNEL_IN_MONO;
            int minBufferSize = android.media.AudioRecord.getMinBufferSize(
                    audioSampleRate,
                    channelConfig,
                    android.media.AudioFormat.ENCODING_PCM_16BIT);
            // Use 2x the minimum buffer size for best reliability
            int bufferSize = Math.max(minBufferSize * 2, audioSampleRate * audioChannelCount);
            Log.d(TAG, "DEBUG: Audio buffer size calculated: " + bufferSize + " (min: " + minBufferSize + ")");

            // Check for RECORD_AUDIO permission before creating AudioRecord
            if (androidx.core.content.ContextCompat.checkSelfPermission(context,
                    android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                throw new SecurityException("RECORD_AUDIO permission not granted");
            }

            // Use AudioRecord.Builder with AudioFormat to prevent Android from silencing
            // audio when app goes to background (Android 9+ privacy feature)
            android.media.AudioFormat recordFormat = new android.media.AudioFormat.Builder()
                    .setEncoding(android.media.AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(audioSampleRate)
                    .setChannelMask(channelConfig)
                    .build();

            // Builder approach works on API 23+ and helps prevent background audio silencing
            audioRecord = new android.media.AudioRecord.Builder()
                    .setAudioSource(audioSource)
                    .setAudioFormat(recordFormat)
                    .setBufferSizeInBytes(bufferSize)
                    .build();

            if (audioRecord.getState() != android.media.AudioRecord.STATE_INITIALIZED) {
                throw new RuntimeException("AudioRecord initialization failed");
            }
            Log.d(TAG, "DEBUG: AudioRecord created successfully with state: " + audioRecord.getState());
            boolean noiseSuppression = com.fadcam.SharedPreferencesManager.getInstance(context)
                    .isNoiseSuppressionEnabled();
            if (noiseSuppression && android.media.audiofx.NoiseSuppressor.isAvailable()) {
                android.media.audiofx.NoiseSuppressor ns = android.media.audiofx.NoiseSuppressor
                        .create(audioRecord.getAudioSessionId());
                if (ns != null) {
                    Log.i(TAG, "NoiseSuppressor enabled for AudioRecord");
                } else {
                    Log.w(TAG, "Failed to enable NoiseSuppressor (create returned null)");
                }
            } else if (noiseSuppression) {
                Log.w(TAG, "NoiseSuppressor requested but not available on this device");
            }

            // CRITICAL: Set AudioManager mode to MODE_NORMAL for camcorder recording
            // This allows normal audio routing and prevents earpiece-only behavior
            audioManager = (android.media.AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            if (audioManager != null) {
                // Save original mode and speakerphone state to restore later
                originalAudioMode = audioManager.getMode();
                originalSpeakerphoneOn = audioManager.isSpeakerphoneOn();
                
                // Set MODE_NORMAL for camcorder recording - allows normal speaker output
                audioManager.setMode(android.media.AudioManager.MODE_NORMAL);
                // Explicitly enable speakerphone for normal audio routing
                audioManager.setSpeakerphoneOn(true);
                Log.i(TAG, "AudioManager mode set to MODE_NORMAL for camcorder recording (was: " + originalAudioMode + "), speakerphone enabled (was: " + originalSpeakerphoneOn + ")");
                
                // Request audio focus with USAGE_MEDIA for camcorder recording
                audioFocusListener = focusChange -> {
                    if (focusChange == android.media.AudioManager.AUDIOFOCUS_LOSS || focusChange == android.media.AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                        Log.w(TAG, "⚠️ AUDIO FOCUS LOST: " + focusChange);
                    } else if (focusChange == android.media.AudioManager.AUDIOFOCUS_GAIN) {
                        Log.i(TAG, "Audio focus regained");
                    }
                };

                // Use modern AudioFocusRequest for Android O+ with MEDIA usage for camcorder
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    android.media.AudioAttributes audioAttrs = new android.media.AudioAttributes.Builder()
                            .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MOVIE)
                            .build();

                    android.media.AudioFocusRequest focusRequest = new android.media.AudioFocusRequest.Builder(android.media.AudioManager.AUDIOFOCUS_GAIN)
                            .setAudioAttributes(audioAttrs)
                            .setAcceptsDelayedFocusGain(false)
                            .setWillPauseWhenDucked(false)
                            .setOnAudioFocusChangeListener(audioFocusListener)
                            .build();

                    int result = audioManager.requestAudioFocus(focusRequest);
                    Log.w(TAG, "🎤 AudioFocusRequest result: " + (result == android.media.AudioManager.AUDIOFOCUS_REQUEST_GRANTED ? "GRANTED" : "DENIED"));
                } else {
                    int result = audioManager.requestAudioFocus(
                            audioFocusListener,
                            android.media.AudioManager.STREAM_MUSIC,
                            android.media.AudioManager.AUDIOFOCUS_GAIN);
                    Log.w(TAG, "🎤 AudioFocus (legacy) result: " + (result == android.media.AudioManager.AUDIOFOCUS_REQUEST_GRANTED ? "GRANTED" : "DENIED"));
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Audio setup failed", e);
            audioRecordingEnabled = false;
        }
    }

    /**
     * Starts the audio thread to read PCM and feed the encoder.
     * Handles pause/resume for camera switch scenarios.
     */
    private void startAudioThread() {
        if (!audioRecordingEnabled || audioThreadRunning)
            return;
        audioThreadRunning = true;
        audioThread = new Thread(() -> {
            try {
                audioRecord.startRecording();
                // Use a large byte[] buffer and feed encoder in sane chunks aligned to frames
                final int bytesPerFrame = audioChannelCount * 2; // 16-bit PCM
                final int aacFrameSize = 1024 * bytesPerFrame; // 1024 PCM frames per AAC frame
                final int readBufferSize = Math.max(aacFrameSize * 4, 131072); // >= 128 KiB
                byte[] readBuffer = new byte[readBufferSize];
                long lastPtsUs = 0L;
                
                while (audioThreadRunning) {
                    // Check if we're paused - wait for resume
                    if (isPaused) {
                        // During pause, don't read audio - just wait
                        try {
                            Thread.sleep(10);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        continue;
                    }
                    
                    // Check if audioRecord is actually recording
                    if (audioRecord.getRecordingState() != android.media.AudioRecord.RECORDSTATE_RECORDING) {
                        // AudioRecord is stopped (during pause), wait a bit
                        try {
                            Thread.sleep(10);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        continue;
                    }
                    
                    int read = audioRecord.read(readBuffer, 0, readBuffer.length);
                    if (read > 0) {
                        int offset = 0;
                        while (offset < read && audioThreadRunning && !isPaused) {
                            int inputBufferIndex = audioEncoder.dequeueInputBuffer(10000);
                            if (inputBufferIndex < 0) {
                                // Encoder busy; break and try next loop iteration
                                break;
                            }
                            ByteBuffer codecInput = audioEncoder.getInputBuffer(inputBufferIndex);
                            if (codecInput == null) {
                                break;
                            }
                            codecInput.clear();
                            int toCopy = Math.min(codecInput.remaining(), read - offset);
                            codecInput.put(readBuffer, offset, toCopy);
                            
                            // CRITICAL FIX: Audio PTS must sync with video PTS!
                            // Both use elapsed time from recordingStartSystemTimeNanos reference point
                            // This ensures they use the SAME timing base (system clock, not frame count)
                            synchronized (timestampLock) {
                                long elapsedNanos = System.nanoTime() - recordingStartSystemTimeNanos - totalPauseDurationNanos;
                                long ptsUs = Math.max(0, elapsedNanos / 1000L); // Convert to microseconds
                                
                                // Ensure monotonically increasing PTS
                                if (ptsUs <= lastPtsUs && lastPtsUs > 0) {
                                    ptsUs = lastPtsUs + 1; // Ensure at least 1us increment
                                }
                                
                                audioEncoder.queueInputBuffer(inputBufferIndex, 0, toCopy, ptsUs, 0);
                                lastPtsUs = ptsUs;
                            }
                            
                            // Advance counters
                            offset += toCopy;
                        }
                    } else if (read < 0) {
                        // Error or AudioRecord stopped
                        Log.w(TAG, "AudioRecord.read returned " + read + " - likely paused or error");
                        try {
                            Thread.sleep(10);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }
                }
                // CRITICAL: Signal EOS to audio encoder before exiting
                // This is mandatory for proper fMP4 finalization
                Log.d(TAG, "Audio thread exiting, signaling EOS to encoder");
                int eosRetries = 3;
                boolean eosQueued = false;
                while (eosRetries > 0 && !eosQueued) {
                    int inputBufferIndex = audioEncoder.dequeueInputBuffer(50000); // 50ms timeout per retry
                    if (inputBufferIndex >= 0) {
                        // Use system time for EOS PTS too, to match monotonic timeline
                        synchronized (timestampLock) {
                            long eosPtsUs = Math.max(0, (System.nanoTime() - recordingStartSystemTimeNanos - totalPauseDurationNanos) / 1000L);
                            if (eosPtsUs < lastPtsUs)
                                eosPtsUs = lastPtsUs; // monotonic safeguard
                            audioEncoder.queueInputBuffer(inputBufferIndex, 0, 0, eosPtsUs,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            Log.d(TAG, "Audio EOS queued at PTS=" + eosPtsUs + "us (" + (eosPtsUs / 1000000.0) + "s)");
                            eosQueued = true;
                        }
                    } else {
                        eosRetries--;
                        Log.w(TAG, "Failed to get input buffer for audio EOS, retries left: " + eosRetries);
                    }
                }
                if (!eosQueued) {
                    Log.e(TAG, "CRITICAL: Failed to queue audio EOS - file duration may be incorrect!");
                }
            } catch (Exception e) {
                Log.e(TAG, "Audio thread error", e);
            }
            // NOTE: Do NOT stop/release audioRecord here - let stopRecording() handle it
            // This prevents race conditions and ensures proper cleanup order
            Log.d(TAG, "Audio thread finished");
        }, "AudioThread");
        audioThread.start();
    }

    /**
     * Drains the audio encoder and writes samples to the muxer.
     * Call this regularly from the render loop or a timer.
     */
    private void drainAudioEncoder() {
        if (!audioRecordingEnabled || audioEncoder == null || !audioEncoderStarted) {
            return;
        }
        if (mediaMuxer == null) {
            return; // Skip if muxer is not available
        }

        MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
        try {
            while (true) {
                int outputBufferIndex = audioEncoder.dequeueOutputBuffer(bufferInfo, 0);
                if (outputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    break;
                } else if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat newFormat = audioEncoder.getOutputFormat();
                    Log.d(TAG, "Audio encoder output format changed: " + newFormat);
                    Log.d(TAG, "DEBUG Audio FORMAT: muxerStarted=" + muxerStarted + ", audioTrackIndex=" + audioTrackIndex + ", videoTrackIndex=" + videoTrackIndex);
                    
                    // Log CSD data for debugging audio issues
                    try {
                        if (newFormat.containsKey("csd-0")) {
                            java.nio.ByteBuffer csd0 = newFormat.getByteBuffer("csd-0");
                            if (csd0 != null) {
                                Log.d(TAG, "Audio CSD-0 present, size=" + csd0.remaining() + " bytes");
                            } else {
                                Log.w(TAG, "Audio CSD-0 key present but null!");
                            }
                        } else {
                            Log.w(TAG, "Audio format MISSING csd-0 - this may cause playback issues!");
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Error checking audio CSD", e);
                    }

                    // Cache audio format for future segment rollovers
                    cachedAudioFormat = newFormat;

                    if (audioTrackIndex != -1) {
                        Log.w(TAG, "Audio format changed after track was added - continuing with existing track");
                        // Don't restart or replace tracks - this causes duration issues
                    } else if (muxerStarted) {
                        Log.w(TAG, "Audio format changed after muxer started - cannot add track now");
                        // Don't try to add tracks after muxer has started
                    } else {
                        // Normal case - add audio track only if muxer hasn't started yet
                        try {
                            Log.d(TAG, "DEBUG: About to add audio track to muxer");
                            audioTrackIndex = mediaMuxer.addTrack(newFormat);
                            Log.d(TAG, "Added audio track with index " + audioTrackIndex + " to muxer");

                            // Check if we can start the muxer now (video track should already be added)
                            if (!muxerStarted && videoTrackIndex != -1) {
                                // Both tracks are ready - start muxer
                                Log.d(TAG, "DEBUG: Both tracks ready, starting muxer now");
                                mediaMuxer.start();
                                muxerStarted = true;
                                Log.d(TAG, "Started muxer after adding audio track - both tracks ready");
                            } else if (!muxerStarted) {
                                Log.d(TAG, "Audio track added, waiting for video track before starting muxer (videoTrackIndex=" + videoTrackIndex + ")");
                            }
                        } catch (Exception e) {
                            Log.e(TAG, "Failed to add audio track to muxer", e);
                        }
                    }
                } else if (outputBufferIndex >= 0) {
                    ByteBuffer encodedData = audioEncoder.getOutputBuffer(outputBufferIndex);
                    if (encodedData == null) {
                        Log.e(TAG, "audioEncoderOutputBuffer " + outputBufferIndex + " was null");
                        audioEncoder.releaseOutputBuffer(outputBufferIndex, false);
                        continue;
                    }

                    if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                        // Codec config data - we don't write this to the muxer
                        bufferInfo.size = 0;
                    }

                    // Check for valid data before writing to muxer
                    if (bufferInfo.size > 0 && muxerStarted && audioTrackIndex != -1) {
                        // Ensure we have valid data in the buffer
                        if (encodedData.remaining() < bufferInfo.size) {
                            Log.w(TAG, "Audio buffer size mismatch: remaining=" + encodedData.remaining() +
                                    ", bufferInfo.size=" + bufferInfo.size +
                                    " - adjusting size to prevent error");
                            bufferInfo.size = encodedData.remaining();
                        }

                        // Only write if we have valid data and muxer is still valid
                        if (bufferInfo.size > 0 && mediaMuxer != null && muxerStarted) {
                            try {
                                encodedData.position(bufferInfo.offset);
                                encodedData.limit(bufferInfo.offset + bufferInfo.size);
                                mediaMuxer.writeSampleData(audioTrackIndex, encodedData, bufferInfo);
                                
                                audioSamplesWritten++;
                                lastAudioPts = bufferInfo.presentationTimeUs;
                                // Log every 50 samples for debugging
                                if (audioSamplesWritten % 50 == 0) {
                                    Log.i(TAG, String.format("[AUDIO_WRITE] Sample #%d, PTS=%.3fs (%dus), size=%db",
                                        audioSamplesWritten, bufferInfo.presentationTimeUs / 1000000.0,
                                        bufferInfo.presentationTimeUs, bufferInfo.size));
                                }
                                // Detect silence pattern (constant 512-byte AAC frames)
                                if (bufferInfo.size == 512) {
                                    consecutive512Count++;
                                    if (consecutive512Count == 10) {
                                        Log.w(TAG, "⚠️ SILENCE DETECTED: 10 consecutive 512-byte frames at sample #" + audioSamplesWritten + ", " + (bufferInfo.presentationTimeUs / 1000000.0) + "s");
                                    }
                                } else {
                                    consecutive512Count = 0;
                                }
                            } catch (Exception e) {
                                Log.e(TAG, "Error writing audio frame to muxer", e);
                                // Continue processing other frames
                            }
                        }
                    } else if (bufferInfo.size > 0 && !muxerStarted) {
                        Log.d(TAG, "Dropping audio frame because muxer isn't started yet");
                    } else if (bufferInfo.size > 0 && audioTrackIndex == -1) {
                        Log.d(TAG, "Dropping audio frame because audio track is not added yet");
                    }

                    audioEncoder.releaseOutputBuffer(outputBufferIndex, false);
                    if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        Log.d(TAG, "End of stream reached in audio encoder");
                        break;
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error draining audio encoder", e);
        }
    }

    /**
     * Call this method when the app comes back to the foreground to ensure the
     * preview is properly set up.
     * This should be called from the activity's onResume or when the app becomes
     * visible again.
     * Recording should continue uninterrupted regardless of app
     * foreground/background state.
     */
    public void handleAppForeground() {
        if (!isRecording)
            return;

        Log.d(TAG, "App returned to foreground, updating preview surface");

        // If we have a valid preview surface, make sure it's set on the renderer
        if (previewSurface != null && previewSurface.isValid() && glRenderer != null) {
            // Enqueue on GL thread to avoid cross-thread GL calls
            final Surface s = previewSurface;
            rendererActions.offer(() -> {
                try {
                    glRenderer.setPreviewSurface(s);
                    if (mSurfaceWidth > 0 && mSurfaceHeight > 0) {
                        glRenderer.setSurfaceDimensions(mSurfaceWidth, mSurfaceHeight);
                    }
                    Log.d(TAG, "Preview surface updated successfully");
                } catch (Exception e) {
                    Log.e(TAG, "Failed to update preview surface", e);
                }
            });
        }
    }

    /**
     * Renders a black frame to keep the recording going when camera is unavailable.
     * This ensures the recording pipeline stays active even when camera input is
     * lost.
     * 
     * Note: We do NOT attempt to recreate the renderer or EGL context if it's lost.
     * We simply use the existing renderer if it's available, and rely on the
     * encoder
     * to handle gaps in frames gracefully.
     */
    public void renderBlackFrame() {
        if (!isRecording || released) {
            // Don't try to render if we're not recording or after release
            return;
        }

        // Only use the existing renderer, don't try to recreate it
        if (glRenderer != null && encoderInputSurface != null && encoderInputSurface.isValid()) {
            try {
                Log.d(TAG, "Rendering black frame to maintain recording pipeline");
                glRenderer.renderBlackFrame();
                try {
                    drainEncoder();
                } catch (Exception e) {
                    Log.d(TAG, "Error draining encoder after black frame - continuing");
                }
            } catch (Exception e) {
                Log.d(TAG, "Could not render black frame - this is expected during camera disconnection");
            }
        } else {
            Log.d(TAG, "Cannot render black frame - renderer or surface is null/invalid");
        }
    }

    /**
     * Releases only the preview EGL/GL resources (not the encoder or recording
     * pipeline).
     */
    public void releasePreviewResources() {
        if (glRenderer != null) {
            glRenderer.releasePreviewEGL();
        }
    }
}
