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

import com.fadcam.VideoCodec;

/**
 * GLRecordingPipeline manages the OpenGL pipeline for real-time watermarking and video encoding.
 *
 * This pipeline always uses the fixed videoWidth/videoHeight for recording output,
 * and ignores device rotation. Only the preview may use letterboxing for user experience.
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
    private MediaMuxer mediaMuxer;
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
         * Called when a new segment is needed. Should provide a new file path or FileDescriptor.
         * @param nextSegmentNumber The next segment number (1-based)
         */
        void onSegmentRollover(int nextSegmentNumber);
    }

    /**
     * Gets a synchronized timestamp for audio frames based on the video timeline.
     * This ensures audio and video timestamps are properly aligned.
     */
    private long getSynchronizedAudioTimestamp() {
        synchronized (timestampLock) {
            if (recordingStartTimeNanos == -1) {
                // First call - initialize the recording start time
                recordingStartTimeNanos = System.nanoTime();
                return 0; // First audio frame starts at 0
            }
            
            // Calculate elapsed time since recording started
            long elapsedNanos = System.nanoTime() - recordingStartTimeNanos;
            return elapsedNanos / 1000L; // Convert to microseconds
        }
    }


    private void initializeVideoTimestamp(long cameraTimestampNanos) {
        synchronized (timestampLock) {
            if (firstVideoTimestampNanos == -1) {
                firstVideoTimestampNanos = cameraTimestampNanos;
                if (recordingStartTimeNanos == -1) {
                    recordingStartTimeNanos = System.nanoTime();
                }
                Log.d(TAG, "Video timestamp initialized: camera=" + cameraTimestampNanos + 
                      ", recording_start=" + recordingStartTimeNanos);
            }
        }
    }

    /**
     * Gets a synchronized timestamp for video frames that aligns with audio.
     * This converts camera timestamps to recording timeline.
     */
    public long getSynchronizedVideoTimestamp(long cameraTimestampNanos) {
        synchronized (timestampLock) {
            if (firstVideoTimestampNanos == -1 || recordingStartTimeNanos == -1) {
                // Initialize if not done yet
                initializeVideoTimestamp(cameraTimestampNanos);
                return 0; // First video frame starts at 0
            }
            
            // Calculate offset from first video frame
            long videoOffsetNanos = cameraTimestampNanos - firstVideoTimestampNanos;
            return videoOffsetNanos / 1000L; // Convert to microseconds
        }
    }

    private long maxFileSizeBytes = Long.MAX_VALUE;
    private int segmentNumber = 1;
    private SegmentCallback segmentCallback;
    private String currentOutputFilePath;
    private FileDescriptor currentOutputFd;
    private boolean muxerStarted = false;

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
    // Audio settings (always set from preferences or app defaults)
    private int audioSource;
    private int audioSampleRate;
    private int audioBitrate;
    private int audioChannelCount;

    private boolean released = false;

    private final VideoCodec videoCodec;
    
    // Timestamp synchronization fields
    private long recordingStartTimeNanos = -1;
    private long firstVideoTimestampNanos = -1;
    private final Object timestampLock = new Object();

    // -------------- Fix Start for GLRecordingPipeline watermark scheduler-----------
    // Update watermark on a low-frequency handler to avoid per-frame overhead and sustain 60fps
    private final android.os.Handler watermarkUpdateHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private Runnable updateWatermarkRunnable;
    // Queue for GL-thread-safe renderer operations (processed in render loop)
    private final java.util.concurrent.ConcurrentLinkedQueue<Runnable> rendererActions = new java.util.concurrent.ConcurrentLinkedQueue<>();
    // -------------- Fix Ended for GLRecordingPipeline watermark scheduler-----------

    // Updated constructor for file path (internal storage)
    public GLRecordingPipeline(Context context, WatermarkInfoProvider watermarkInfoProvider, int videoWidth, int videoHeight, int videoFramerate, String outputFilePath, long maxFileSizeBytes, int segmentNumber, SegmentCallback segmentCallback, Surface previewSurface, String orientation, int sensorOrientation, VideoCodec videoCodec) {
        this(context, watermarkInfoProvider, videoWidth, videoHeight, videoFramerate, outputFilePath, maxFileSizeBytes, segmentNumber, segmentCallback, orientation, sensorOrientation, videoCodec);
        this.previewSurface = previewSurface;
        initAudioSettings();
    }

    // Updated constructor for FileDescriptor (SAF)
    public GLRecordingPipeline(Context context, WatermarkInfoProvider watermarkInfoProvider, int videoWidth, int videoHeight, int videoFramerate, FileDescriptor outputFd, long maxFileSizeBytes, int segmentNumber, SegmentCallback segmentCallback, Surface previewSurface, String orientation, int sensorOrientation, VideoCodec videoCodec) {
        this(context, watermarkInfoProvider, videoWidth, videoHeight, videoFramerate, outputFd, maxFileSizeBytes, segmentNumber, segmentCallback, orientation, sensorOrientation, videoCodec);
        this.previewSurface = previewSurface;
        initAudioSettings();
    }

    // Updated constructor for file path (internal storage)
    public GLRecordingPipeline(Context context, WatermarkInfoProvider watermarkInfoProvider, int videoWidth, int videoHeight, int videoFramerate, String outputFilePath, long maxFileSizeBytes, int segmentNumber, SegmentCallback segmentCallback, String orientation, int sensorOrientation, VideoCodec videoCodec) {
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
    public GLRecordingPipeline(Context context, WatermarkInfoProvider watermarkInfoProvider, int videoWidth, int videoHeight, int videoFramerate, FileDescriptor outputFd, long maxFileSizeBytes, int segmentNumber, SegmentCallback segmentCallback, String orientation, int sensorOrientation, VideoCodec videoCodec) {
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

    /**
     * Prepares the GL renderer and camera input surface for the camera session.
     * Call this after constructing the pipeline, before creating the camera session.
     * Does NOT start encoding or the render loop.
     */
    public void prepareSurfaces() {
        try {
        // -------------- Fix Start for this method(prepareSurfaces)-----------
    // -------------- Fix Start for this method(prepareSurfaces)-----------
    try { com.fadcam.Log.d(TAG, "prepareSurfaces() called"); } catch (Throwable ignore){}
    // -------------- Fix Ended for this method(prepareSurfaces)-----------
        // -------------- Fix Ended for this method(prepareSurfaces)-----------
            // Make sure any previous resources are fully released
            if (glRenderer != null) {
                Log.d(TAG, "Releasing previous renderer before preparing new surfaces");
                try {
                    glRenderer.release();
                } catch (Exception e) {
                    Log.e(TAG, "Error releasing previous renderer", e);
                    try { com.fadcam.Log.e(TAG, "Error releasing previous renderer", e); } catch (Throwable ignore){}
                }
                glRenderer = null;
            }
            
            if (encoderInputSurface == null) {
                Log.d(TAG, "Setting up video encoder");
                setupEncoder();
                if (encoderInputSurface == null) {
                    throw new RuntimeException("Failed to create encoder input surface");
                }
            }
            
            if (glRenderer == null) {
                Log.d(TAG, "Creating GLWatermarkRenderer with dimensions " + videoWidth + "x" + videoHeight);
                glRenderer = new GLWatermarkRenderer(context, encoderInputSurface, orientation, sensorOrientation, videoWidth, videoHeight);
                glRenderer.setUserOrientationSetting(orientation);
                glRenderer.setRecordingPipeline(this); // Set reference for timestamp synchronization
                // -------------- Fix Start for this method(prepareSurfaces)-----------
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
                        if (previewSurface != null && previewSurface.isValid()) {
                            Log.d(TAG, "Setting preview surface during prepareSurfaces (GL thread)");
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
                                        try { if (dummy != null) dummy.release(); } catch (Exception ignore) {}
                                        try { ir.close(); } catch (Exception ignore) {}
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
                // Wait for GL init to complete so we can return a valid camera surface for Camera2 session
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
                // -------------- Fix Ended for this method(prepareSurfaces)-----------
                
                // Set up the frame listener to trigger rendering when new frames arrive
                glRenderer.setOnFrameAvailableListener(new GLWatermarkRenderer.OnFrameAvailableListener() {
                    @Override
                    public void onFrameAvailable() {
                        if (isRecording && handler != null) {
                            handler.post(renderRunnable);
                        }
                    }
                });
                
                // -------------- Fix Start: initialize watermark immediately --------------
                try {
                    if (watermarkInfoProvider != null) {
                        String initial = watermarkInfoProvider.getWatermarkText();
                        glRenderer.setWatermarkText(initial != null ? initial : "");
                        Log.d(TAG, "Applied initial watermark text during prepareSurfaces");
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Failed to apply initial watermark", e);
                }
                // -------------- Fix End: initialize watermark immediately --------------

                Log.d(TAG, "GLWatermarkRenderer setup complete");
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
                try { com.fadcam.Log.d(TAG, "Starting recording pipeline"); } catch (Throwable ignore){}
                
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
                    // Give audio encoder time to initialize
                    try {
                        Thread.sleep(50);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }

                // -------------- Fix Start for this method(startRecording)-----------
                // Start a separate, low-frequency watermark updater (once per second)
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
                watermarkUpdateHandler.post(updateWatermarkRunnable);
                // -------------- Fix Ended for this method(startRecording)-----------

                // Start the render loop (which will trigger video encoder format change)
                startRenderLoop();
                
                Log.d(TAG, "Recording pipeline started successfully");
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to start recording pipeline", e);
            stopRecording();
        }
    }

    // -------------- Fix Start for this method(emergencyDrainEncoders)-----------
    /**
     * Emergency drain of all encoders to ensure no frames are lost before stopping.
     * This is bulletproof - continues even if individual operations fail.
     */
    private void emergencyDrainEncoders() {
        Log.d(TAG, "Emergency draining encoders to prevent data loss");
        
        // Emergency drain video encoder
        if (videoEncoder != null) {
            try {
                // Signal end of stream to video encoder
                videoEncoder.signalEndOfInputStream();
                
                // Drain remaining frames with timeout
                long drainStartTime = System.currentTimeMillis();
                while (System.currentTimeMillis() - drainStartTime < 1000) { // 1 second timeout
                    try {
                        drainEncoder();
                        Thread.sleep(10); // Small delay between drain attempts
                    } catch (Exception e) {
                        Log.w(TAG, "Error during emergency video drain", e);
                        break; // Exit loop on error
                    }
                }
                Log.d(TAG, "Emergency video encoder drain completed");
            } catch (Exception e) {
                Log.e(TAG, "Error during emergency video encoder drain", e);
            }
        }
        
        // Emergency drain audio encoder
        if (audioRecordingEnabled && audioEncoder != null) {
            try {
                // Stop audio recording first
                if (audioRecord != null && audioRecord.getRecordingState() == android.media.AudioRecord.RECORDSTATE_RECORDING) {
                    audioRecord.stop();
                }
                
                // Stop audio thread to prevent new data being fed to encoder
                audioThreadRunning = false;
                if (audioThread != null) {
                    try {
                        audioThread.join(200); // Wait up to 200ms for thread to stop
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                
                // Check if audio encoder is in a valid state before signaling end of stream
                try {
                    // Only signal end of stream if encoder is in started state and we haven't already signaled EOS
                    if (audioEncoderStarted) {
                        audioEncoder.signalEndOfInputStream();
                        Log.d(TAG, "Signaled end of stream to audio encoder");
                    } else {
                        Log.d(TAG, "Audio encoder not started, skipping end of stream signal");
                    }
                } catch (IllegalStateException e) {
                    Log.w(TAG, "Audio encoder not in correct state for end of stream signal: " + e.getMessage());
                    // Continue with drain attempt anyway
                }
                
                // Drain remaining audio frames with timeout
                long drainStartTime = System.currentTimeMillis();
                while (System.currentTimeMillis() - drainStartTime < 500) { // 500ms timeout for audio
                    try {
                        drainAudioEncoder();
                        Thread.sleep(5); // Small delay between drain attempts
                    } catch (Exception e) {
                        Log.w(TAG, "Error during emergency audio drain", e);
                        break; // Exit loop on error
                    }
                }
                Log.d(TAG, "Emergency audio encoder drain completed");
            } catch (Exception e) {
                Log.e(TAG, "Error during emergency audio encoder drain", e);
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
            // Note: With our progressive MP4 implementation, even failed stops often result in playable files
        }
    }
    // -------------- Fix Ended for this method(emergencyDrainEncoders)-----------

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
            Log.d(TAG, "Set MediaFormat.KEY_CAPTURE_RATE to " + videoFramerate + " for constant framerate");
        } catch (Exception e) {
            Log.d(TAG, "KEY_CAPTURE_RATE not supported on this device");
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
        
        Log.d(TAG, "Applied basic encoder configuration: " +
              "bitrate=" + videoBitrate + ", framerate=" + videoFramerate + ", vbr=enabled, priority=realtime");
    }

    /**
     * Configures MediaFormat with industry-standard settings for maximum compatibility
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
            Log.d(TAG, "Set MediaFormat.KEY_CAPTURE_RATE to " + videoFramerate + " for constant framerate");
        } catch (Exception e) {
            Log.d(TAG, "KEY_CAPTURE_RATE not supported on this device");
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
        
        Log.d(TAG, "Applied industry-standard encoder configuration with user settings: " +
              "codec=" + videoCodec + ", bitrate=" + videoBitrate + ", framerate=" + videoFramerate);
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
            Log.d(TAG, "Attempting " + currentMimeType + " encoder with minimal settings");
            MediaFormat format = MediaFormat.createVideoFormat(currentMimeType, encoderWidth, encoderHeight);
            configureBasicEncoder(format);
            
            videoEncoder = MediaCodec.createEncoderByType(currentMimeType);
            videoEncoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            encoderInputSurface = videoEncoder.createInputSurface();
            encoderConfigured = true;
            Log.d(TAG, "Successfully configured " + currentMimeType + " encoder with basic settings");
        } catch (Exception e) {
            Log.w(TAG, "Failed to configure " + currentMimeType + " with minimal settings: " + e.getMessage());
            if (videoEncoder != null) {
                try { videoEncoder.release(); } catch (Exception ignored) {}
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
                Log.d(TAG, "Successfully configured H.264 fallback encoder");
            } catch (Exception e) {
                Log.e(TAG, "Failed to configure H.264 fallback encoder: " + e.getMessage());
                if (videoEncoder != null) {
                    try { videoEncoder.release(); } catch (Exception ignored) {}
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
     * This is called for each segment.
     */
    private void setupMuxer() throws IOException {
        if (currentOutputFd != null) {
            mediaMuxer = new MediaMuxer(currentOutputFd, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            Log.d(TAG, "Created MediaMuxer with file descriptor");
        } else {
            mediaMuxer = new MediaMuxer(currentOutputFilePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            Log.d(TAG, "Created MediaMuxer with path: " + currentOutputFilePath);
        }
        
        // Reset track indices
        audioTrackIndex = -1;
        videoTrackIndex = -1;
        
        // Reset timestamp synchronization
        synchronized (timestampLock) {
            recordingStartTimeNanos = -1;
            firstVideoTimestampNanos = -1;
        }
        
        // DO NOT start muxer here - wait for encoder formats to be available
        // This prevents the format change issue that causes muxer restarts
        muxerStarted = false;
        Log.d(TAG, "Muxer created but not started - waiting for encoder formats");
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
        // ----- Fix Start for this method(startRenderLoop)-----
        if (renderThread == null) {
            renderThread = new HandlerThread("GLRenderThread");
            renderThread.start();
            handler = new Handler(renderThread.getLooper());
        }
    // Do not kick off render loop immediately; wait for first camera frame
        // ----- Fix Ended for this method(startRenderLoop)-----
    }
    
    // Render when a new frame is available (signaled by renderer)
    private final Runnable renderRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isRecording || released) {
                return;
            }
            
            try {
                // -------------- Fix Start for this method(renderRunnable)-----------
                // Do NOT update watermark every frame; a separate timer handles it to avoid FPS drops
                // Drain any queued GL operations to ensure they run on the GL thread
                Runnable action;
                while ((action = rendererActions.poll()) != null) {
                    try {
                        action.run();
                    } catch (Throwable t) {
                        android.util.Log.w(TAG, "Renderer action failed", t);
                    }
                }
                // -------------- Fix Ended for this method(renderRunnable)-----------
                
                if (glRenderer != null) {
                    glRenderer.renderFrame();
                }
                drainEncoder();
                

                // Check if we need to split the segment due to size
                if (shouldSplitSegment()) {
                    Log.d(TAG, "Size limit reached, rolling over segment");
                    rolloverSegment();
                }

                
                // Continue rendering loop when new frames arrive (onFrameAvailable posts this runnable).
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
                    
                    if (muxerStarted) {
                        // This should NOT happen if we wait for format before starting muxer
                        Log.e(TAG, "CRITICAL: Format changed after muxer started - this indicates a timing issue!");
                        try { com.fadcam.Log.e(TAG, "CRITICAL: Format changed after muxer started - timing issue"); } catch (Throwable ignore){}
                        // Don't restart muxer - this causes duration issues
                        // Instead, log the error and continue with existing muxer
                        Log.w(TAG, "Continuing with existing muxer to prevent duration corruption");
                    } else {
                        // Normal case - add video track first
                        videoTrackIndex = mediaMuxer.addTrack(newFormat);
                        Log.d(TAG, "Added video track with index " + videoTrackIndex + " to muxer");
                        try { com.fadcam.Log.d(TAG, "Video track added: index="+videoTrackIndex+", fmt="+newFormat.toString()); } catch (Throwable ignore){}
                        
                        // Start muxer immediately if audio is disabled
                        if (!audioRecordingEnabled) {
                            mediaMuxer.start();
                            muxerStarted = true;
                            Log.d(TAG, "Started muxer for video-only recording");
                            try { com.fadcam.Log.d(TAG, "Muxer started (video-only)"); } catch (Throwable ignore){}
                        } else {
                            // Audio is enabled - check if audio track is already added
                            if (audioTrackIndex != -1) {
                                // Both tracks are ready, start muxer
                                mediaMuxer.start();
                                muxerStarted = true;
                                Log.d(TAG, "Started muxer with both video and audio tracks");
                                try { com.fadcam.Log.d(TAG, "Muxer started (audio+video)"); } catch (Throwable ignore){}
                            } else {
                                // Wait for audio track to be added
                                Log.d(TAG, "Video track added, waiting for audio track before starting muxer");
                                try { com.fadcam.Log.d(TAG, "Waiting for audio track before starting muxer"); } catch (Throwable ignore){}
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
                            } catch (Exception e) {
                                Log.e(TAG, "Error writing video frame to muxer", e);
                                try { com.fadcam.Log.e(TAG, "Error writing video frame to muxer", e); } catch (Throwable ignore){}
                                // -------------- Fix Start for this method(drainEncoder)-----------
                                // BULLETPROOF: Continue processing but mark potential corruption
                                if (e.getMessage() != null && e.getMessage().contains("muxer")) {
                                    Log.w(TAG, "Muxer error detected - file may need emergency finalization");
                                    try { com.fadcam.Log.w(TAG, "Muxer error detected - will attempt emergency finalize if needed"); } catch (Throwable ignore){}
                                }
                                // -------------- Fix Ended for this method(drainEncoder)-----------
                    }
                        }
                    } else if (bufferInfo.size > 0 && !muxerStarted) {
                        Log.d(TAG, "Dropping encoded frame because muxer isn't started yet");
                        try { com.fadcam.Log.w(TAG, "Dropping encoded frame (muxer not started yet)"); } catch (Throwable ignore){}
                    }
                    
                    videoEncoder.releaseOutputBuffer(outputBufferIndex, false);
                    if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        Log.d(TAG, "End of stream reached in video encoder");
                        break;
                    }
                }
            }
            
            // Drain audio encoder regularly
            if (audioRecordingEnabled && audioEncoder != null) {
                drainAudioEncoder();
            }
        } catch (Exception e) {
            Log.e(TAG, "Error draining encoder", e);
        }
    }

    private boolean shouldSplitSegment() {
        // First check if video splitting is enabled (maxFileSizeBytes > 0)
        if (maxFileSizeBytes <= 0) {
            // No need to log every time as this is called frequently
            return false; // Video splitting is disabled
        }
        
        try {
            if (currentOutputFilePath != null) {
                File f = new File(currentOutputFilePath);
                if (f.exists()) {
                    long currentSize = f.length();
                    // Log periodically (e.g., every 10MB) to avoid log spam
                    if (currentSize % (10 * 1024 * 1024) < 100 * 1024) { // Log every ~10MB
                        Log.d(TAG, String.format("Current file size: %.2f MB / %.2f MB (%.1f%%)", 
                                currentSize / (1024.0 * 1024.0), 
                                maxFileSizeBytes / (1024.0 * 1024.0),
                                (currentSize * 100.0) / maxFileSizeBytes));
                    }
                    
                    if (currentSize >= maxFileSizeBytes) {
                        Log.i(TAG, String.format("File size limit reached: %.2f MB >= %.2f MB, triggering segment rollover", 
                                currentSize / (1024.0 * 1024.0), 
                                maxFileSizeBytes / (1024.0 * 1024.0)));
                        return true;
                    }
                }
                return false;
            } else if (currentOutputFd != null) {
                // For SAF, try to get file size via FileDescriptor (not always possible)
                // This is a best-effort; may not work on all devices/URIs
                try {
                    RandomAccessFile raf = new RandomAccessFile("/proc/self/fd/" + currentOutputFd, "r");
                    long currentSize = raf.length();
                    raf.close();
                    
                    // Log periodically to avoid log spam
                    if (currentSize % (10 * 1024 * 1024) < 100 * 1024) { // Log every ~10MB
                        Log.d(TAG, String.format("Current SAF file size: %.2f MB / %.2f MB (%.1f%%)", 
                                currentSize / (1024.0 * 1024.0), 
                                maxFileSizeBytes / (1024.0 * 1024.0),
                                (currentSize * 100.0) / maxFileSizeBytes));
                    }
                    
                    if (currentSize >= maxFileSizeBytes) {
                        Log.i(TAG, String.format("SAF file size limit reached: %.2f MB >= %.2f MB, triggering segment rollover", 
                                currentSize / (1024.0 * 1024.0), 
                                maxFileSizeBytes / (1024.0 * 1024.0)));
                        return true;
                    }
                    return false;
                } catch (Exception e) {
                    // Fallback: skip splitting if we can't get size
                    Log.w(TAG, "Unable to check SAF file size for splitting", e);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Error checking file size for segment split", e);
        }
        return false;
    }

    private void rolloverSegment() {
        try {
            Log.i(TAG, "Auto-splitting: segment size limit reached, rolling over to next segment");
            Log.d(TAG, "Current segment number: " + segmentNumber + ", rolling over to " + (segmentNumber + 1));
            
            // First, request the next segment file/descriptor from callback
            // This is done first to ensure we have a valid output before stopping the current muxer
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
                
                // Force a frame render to ensure the encoder has valid data for the new segment
                if (glRenderer != null) {
                    glRenderer.renderFrame();
                    Log.d(TAG, "Forced a frame render for the new segment");
            }
                
                Log.i(TAG, "Started new segment: " + segmentNumber + 
                      (currentOutputFilePath != null ? " at path: " + currentOutputFilePath : " with file descriptor"));
            } catch (Exception e) {
                Log.e(TAG, "Error setting up muxer for new segment", e);
                throw e; // Re-throw to be caught by outer try-catch
            }
        } catch (Exception e) {
            Log.e(TAG, "Error during segment rollover", e);
            stopRecording();
        }
    }

    /**
     * Stops recording and releases all resources with bulletproof error handling.
     * Ensures recorded files are always playable even when errors occur.
     */
    public void stopRecording() {
    Log.d(TAG, "stopRecording: Stopping recording and releasing resources");
    try { com.fadcam.Log.d(TAG, "stopRecording: Stopping recording and releasing resources"); } catch (Throwable ignore){}
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

        // -------------- Fix Start for this method(stopRecording)-----------
        // Stop watermark updates
        try {
            watermarkUpdateHandler.removeCallbacksAndMessages(null);
            updateWatermarkRunnable = null;
        } catch (Exception e) {
            Log.w(TAG, "Error stopping watermark updater", e);
            try { com.fadcam.Log.w(TAG, "Error stopping watermark updater: " + e.getMessage()); } catch (Throwable ignore){}
        }
        // -------------- Fix Ended for this method(stopRecording)-----------
        
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
        
        // CRITICAL: Stop audio recording FIRST to prevent duration mismatch
        // Audio must stop at the same time as video to ensure synchronized duration
        audioThreadRunning = false;
        if (audioThread != null) {
            try { 
                audioThread.join(100); // Quick join to stop audio immediately
                Log.d(TAG, "Audio thread stopped synchronously with video");
            } catch (Exception e) {
                Log.w(TAG, "Audio thread join timeout, forcing stop", e);
            }
            audioThread = null;
        }
        
        // Stop audio recording immediately
        if (audioRecord != null && audioRecord.getRecordingState() == android.media.AudioRecord.RECORDSTATE_RECORDING) {
            try {
                audioRecord.stop();
                Log.d(TAG, "Audio recording stopped immediately");
            } catch (Exception e) {
                Log.e(TAG, "Error stopping audio recording", e);
            }
        }
        
        // -------------- Fix Start for this method(stopRecording)-----------
        // BULLETPROOF: Emergency drain encoders before stopping
        emergencyDrainEncoders();
        
        // Stop and release the video encoder with bulletproof handling
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
        // -------------- Fix Ended for this method(stopRecording)-----------
        
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
        
        // Clean up remaining audio resources (audio recording already stopped above)
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
        if (audioRecord != null) {
            try { audioRecord.release(); } catch (Exception ignored) {}
            audioRecord = null;
        }
        audioTrackIndex = -1;
        audioEncoderStarted = false;
        
        Log.d(TAG, "GLRecordingPipeline stopped and released.");
    // Reset flags so a new recording can be started cleanly
    isStopped = false;
    released = false;
    isRecording = false;
    // Clear retry/time bases
    recordingStartTimeNanos = -1;
    firstVideoTimestampNanos = -1;
    }

    /**
     * Updates the watermark text in real time.
     */
    public void updateWatermark() {
        if (glRenderer == null || watermarkInfoProvider == null) return;
        final String text = watermarkInfoProvider.getWatermarkText();
        // Ensure the GL texture update runs on the render thread with EGL context current
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
     * Pauses the recording pipeline (no-op, for API compatibility).
     */
    public void pauseRecording() {
        if (!isRecording || isStopped) {
            Log.w(TAG, "Cannot pause recording - recording is not active");
            return;
        }
        
        Log.d(TAG, "Pausing recording");
        
        // Simply set the recording flag to false to stop encoding new frames
        isRecording = false;
        
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
     * Resumes the recording pipeline (no-op, for API compatibility).
     */
    public void resumeRecording() {
        if (isRecording || isStopped) {
            Log.w(TAG, "Cannot resume recording - recording is either already active or has been stopped");
            return;
        }
        
        Log.d(TAG, "Resuming recording");
        
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
     * Call this from the segment callback to update the output destination before calling setupEncoder().
     * @param filePath The file path for the next segment (internal storage), or null if using SAF
     * @param fd The FileDescriptor for the next segment (SAF), or null if using internal storage
     */
    public void setNextOutput(String filePath, FileDescriptor fd) {
        this.currentOutputFilePath = filePath;
        this.currentOutputFd = fd;
    }

    /**
     * Updates the device orientation for the renderer to adjust the preview.
     * @param deviceOrientation The current orientation of the device (e.g., Surface.ROTATION_0).
     */
    public void setDeviceOrientation(int deviceOrientation) {
        this.deviceOrientation = deviceOrientation;
        if (glRenderer != null) {
            glRenderer.setDeviceOrientation(deviceOrientation);
        }
    }

    /**
     * Sets the preview surface for rendering the camera feed.
     * @param surface The Surface to render the preview on.
     */
    public void setPreviewSurface(Surface surface) {
        this.previewSurface = surface;
        if (glRenderer != null) {
            final Surface s = surface;
            rendererActions.offer(() -> {
                // -------------- Fix Start for this method(setPreviewSurface)-----------
                if (s == null || !s.isValid()) {
                    glRenderer.releasePreviewEGL();
                } else {
                    glRenderer.setPreviewSurface(s);
                }
                // -------------- Fix Ended for this method(setPreviewSurface)-----------
            });
        }
    }

    /**
     * Updates the surface dimensions and informs the renderer.
     * This should be called whenever the preview surface size changes.
     *
     * @param width The width of the surface
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
            float recordingAspectRatio = (float)videoWidth / videoHeight;
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
        
        float previewAspectRatio = (float)mSurfaceWidth / mSurfaceHeight;
        float recordingAspectRatio = (float)videoWidth / videoHeight;
        
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
        try {
            audioInputSource = prefs.getAudioInputSource();
        } catch (Exception ignored) {}
        if (audioInputSource != null && audioInputSource.equals(com.fadcam.SharedPreferencesManager.AUDIO_INPUT_SOURCE_WIRED)) {
            // Wired mic: use CAMCORDER if available, else fallback to MIC
            this.audioSource = android.media.MediaRecorder.AudioSource.CAMCORDER;
        } else {
            this.audioSource = android.media.MediaRecorder.AudioSource.MIC;
        }
    }

    /**
     * Sets up the audio encoder and AudioRecord for AAC audio capture.
     * Call before starting audio thread.
     */
    private void setupAudio() {
        if (!audioRecordingEnabled) return;
        try {
            // Configure MediaCodec for AAC
            MediaFormat audioFormat = MediaFormat.createAudioFormat(
                    android.media.MediaFormat.MIMETYPE_AUDIO_AAC,
                    audioSampleRate,
                    audioChannelCount);
            audioFormat.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
            audioFormat.setInteger(MediaFormat.KEY_BIT_RATE, audioBitrate);
            // -------------- Fix Start for this method(setupAudio)-----------
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
            // -------------- Fix Ended for this method(setupAudio)-----------
            audioEncoder = MediaCodec.createEncoderByType(android.media.MediaFormat.MIMETYPE_AUDIO_AAC);
            audioEncoder.configure(audioFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            audioEncoder.start();
            audioEncoderStarted = true;

            // Setup AudioRecord
            int channelConfig = audioChannelCount == 2 ? android.media.AudioFormat.CHANNEL_IN_STEREO : android.media.AudioFormat.CHANNEL_IN_MONO;
            int minBufferSize = android.media.AudioRecord.getMinBufferSize(
                    audioSampleRate,
                    channelConfig,
                    android.media.AudioFormat.ENCODING_PCM_16BIT);
            // Use 2x the minimum buffer size for best reliability
            int bufferSize = Math.max(minBufferSize * 2, audioSampleRate * audioChannelCount);
            
            // Check for RECORD_AUDIO permission before creating AudioRecord
            if (androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) 
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                throw new SecurityException("RECORD_AUDIO permission not granted");
            }
            
            audioRecord = new android.media.AudioRecord(
                    audioSource,
                    audioSampleRate,
                    channelConfig,
                    android.media.AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize);
            if (audioRecord.getState() != android.media.AudioRecord.STATE_INITIALIZED) {
                throw new RuntimeException("AudioRecord initialization failed");
            }
            // ----- Fix Start for this method(setupAudio)-----
            boolean noiseSuppression = com.fadcam.SharedPreferencesManager.getInstance(context).isNoiseSuppressionEnabled();
            if (noiseSuppression && android.media.audiofx.NoiseSuppressor.isAvailable()) {
                android.media.audiofx.NoiseSuppressor ns = android.media.audiofx.NoiseSuppressor.create(audioRecord.getAudioSessionId());
                if (ns != null) {
                    Log.i(TAG, "NoiseSuppressor enabled for AudioRecord");
                } else {
                    Log.w(TAG, "Failed to enable NoiseSuppressor (create returned null)");
                }
            } else if (noiseSuppression) {
                Log.w(TAG, "NoiseSuppressor requested but not available on this device");
            }
            // ----- Fix Ended for this method(setupAudio)-----
        } catch (Exception e) {
            Log.e(TAG, "Audio setup failed", e);
            audioRecordingEnabled = false;
        }
    }

    /**
     * Starts the audio thread to read PCM and feed the encoder.
     */
    private void startAudioThread() {
        if (!audioRecordingEnabled || audioThreadRunning) return;
        audioThreadRunning = true;
        audioThread = new Thread(() -> {
            try {
                audioRecord.startRecording();
                // -------------- Fix Start for this method(startAudioThread)-----------
                // Use a large byte[] buffer and feed encoder in sane chunks aligned to frames
                final int bytesPerFrame = audioChannelCount * 2; // 16-bit PCM
                final int aacFrameSize = 1024 * bytesPerFrame;   // 1024 PCM frames per AAC frame
                final int readBufferSize = Math.max(aacFrameSize * 4, 131072); // >= 128 KiB
                byte[] readBuffer = new byte[readBufferSize];
                long audioFramesWritten = 0L; // PCM frames (not bytes)
                long lastPtsUs = 0L;
                // -------------- Fix Ended for this method(startAudioThread)-----------
                while (audioThreadRunning) {
                    int read = audioRecord.read(readBuffer, 0, readBuffer.length);
                    if (read > 0) {
                        int offset = 0;
                        while (offset < read && audioThreadRunning) {
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
                            // PTS based on frames written so far (stable, drift-free)
                long ptsUs = (audioFramesWritten * 1_000_000L) / audioSampleRate;
                audioEncoder.queueInputBuffer(inputBufferIndex, 0, toCopy, ptsUs, 0);
                lastPtsUs = ptsUs;
                            // Advance counters
                            offset += toCopy;
                            audioFramesWritten += (toCopy / bytesPerFrame);
                        }
                    }
                }
                // Signal EOS
                int inputBufferIndex = audioEncoder.dequeueInputBuffer(10000);
                if (inputBufferIndex >= 0) {
            // Use lastPtsUs from our audio timeline to avoid duration jumps
            long eosPtsUs = (audioFramesWritten * 1_000_000L) / audioSampleRate;
            if (eosPtsUs < lastPtsUs) eosPtsUs = lastPtsUs; // monotonic safeguard
            audioEncoder.queueInputBuffer(inputBufferIndex, 0, 0, eosPtsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                }
            } catch (Exception e) {
                Log.e(TAG, "Audio thread error", e);
            } finally {
                try { audioRecord.stop(); } catch (Exception ignored) {}
                try { audioRecord.release(); } catch (Exception ignored) {}
            }
        }, "AudioThread");
        audioThread.start();
    }

    /**
     * Drains the audio encoder and writes samples to the muxer.
     * Call this regularly from the render loop or a timer.
     */
    private void drainAudioEncoder() {
        if (!audioRecordingEnabled || audioEncoder == null) {
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
                    
                    if (audioTrackIndex != -1) {
                        Log.w(TAG, "Audio format changed after track was added - continuing with existing track");
                        // Don't restart or replace tracks - this causes duration issues
                    } else if (muxerStarted) {
                        Log.w(TAG, "Audio format changed after muxer started - cannot add track now");
                        // Don't try to add tracks after muxer has started
                    } else {
                        // Normal case - add audio track only if muxer hasn't started yet
                        try {
                            audioTrackIndex = mediaMuxer.addTrack(newFormat);
                            Log.d(TAG, "Added audio track with index " + audioTrackIndex + " to muxer");
                            
                            // Check if we can start the muxer now (video track should already be added)
                            if (!muxerStarted && videoTrackIndex != -1) {
                                // Both tracks are ready - start muxer
                                mediaMuxer.start();
                                muxerStarted = true;
                                Log.d(TAG, "Started muxer after adding audio track - both tracks ready");
                            } else if (!muxerStarted) {
                                Log.d(TAG, "Audio track added, waiting for video track before starting muxer");
                            }
                        } catch (Exception e) {
                            Log.e(TAG, "Error adding audio track to muxer", e);
                            // Continue without audio track for video-only recording
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
     * Call this method when the app comes back to the foreground to ensure the preview is properly set up.
     * This should be called from the activity's onResume or when the app becomes visible again.
     * Recording should continue uninterrupted regardless of app foreground/background state.
     */
    public void handleAppForeground() {
        if (!isRecording) return;
        
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
     * This ensures the recording pipeline stays active even when camera input is lost.
     * 
     * Note: We do NOT attempt to recreate the renderer or EGL context if it's lost.
     * We simply use the existing renderer if it's available, and rely on the encoder
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
     * Releases only the preview EGL/GL resources (not the encoder or recording pipeline).
     */
    public void releasePreviewResources() {
        if (glRenderer != null) {
            glRenderer.releasePreviewEGL();
        }
    }
}