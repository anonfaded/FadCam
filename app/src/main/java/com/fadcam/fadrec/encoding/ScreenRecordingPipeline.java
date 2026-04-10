package com.fadcam.fadrec.encoding;

import com.fadcam.Log;
import com.fadcam.FLog;
import android.content.Context;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioPlaybackCaptureConfiguration;
import android.media.AudioRecord;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.media.MediaRecorder;
import android.media.projection.MediaProjection;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.view.Surface;
import com.fadcam.opengl.GLWatermarkRenderer;
import androidx.annotation.Nullable;

import com.fadcam.Constants;
import com.fadcam.VideoCodec;
import com.fadcam.media.FragmentedMp4MuxerWrapper;
import com.fadcam.opengl.WatermarkInfoProvider;

import java.io.FileDescriptor;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * ScreenRecordingPipeline manages screen recording with OpenGL watermarking via
 * {@link com.fadcam.opengl.GLWatermarkRenderer} and fragmented MP4 output.
 *
 * Architecture: MediaProjection → VirtualDisplay → GLWatermarkRenderer
 *               → MediaCodec encoder (watermarked recording) + preview TextureView
 *
 * Supports auto-splitting via SegmentCallback — when maxFileSizeBytes is set, the pipeline
 * will roll over to a new output file when the size limit is reached, recreating the muxer
 * and re-adding cached tracks without stopping the encoders.
 */
public class ScreenRecordingPipeline {

    /** Callback for segment rollover — provides the next output file/descriptor. */
    public interface SegmentCallback {
        void onSegmentRollover(int nextSegmentNumber);
    }

    private static final String TAG = "ScreenRecPipeline";
    private static final String VIDEO_MIME_TYPE = "video/avc";
    private static final int VIDEO_IFRAME_INTERVAL = 1;
    private static volatile boolean preferSoftwareAvcEncoder = false;

    public static boolean isPreferringSoftwareAvcEncoder() {
        return preferSoftwareAvcEncoder;
    }
    
    private final Context context;
    private final WatermarkInfoProvider watermarkInfoProvider;
    
    // Screen dimensions (actual device screen - used for VirtualDisplay)
    private final int displayWidth;
    private final int displayHeight;
    
    // Encoder dimensions (optimized for codec - may differ from display)
    private int screenWidth;
    private int screenHeight;
    private final int screenDensity;
    private final int videoFramerate;
    private final int videoBitrate;
    private final boolean enableAudio;
    private final String audioSource;
    private final String outputFilePath;
    private final FileDescriptor outputFd;

    // Auto-splitting (segment rollover) fields
    private long maxFileSizeBytes = Long.MAX_VALUE;
    private int segmentNumber = 1;
    private SegmentCallback segmentCallback;
    private long segmentBytesWritten = 0L;
    private long lastVideoSegmentBytes = 0L;
    private long lastAudioSegmentBytes = 0L;
    private static final double ROLLOVER_PREEMPT_THRESHOLD_RATIO = 0.95;
    private boolean pendingRollover = false;
    private boolean awaitingKeyframeForRollover = false;
    private boolean rolloverInProgress = false;
    private volatile boolean rolloverRequestedByDrain = false;

    // Cached formats for muxer recreation during segment rollover
    private MediaFormat cachedVideoFormat;
    private MediaFormat cachedAudioFormat;

    // Current output path/descriptor (mutable for rollover)
    private String currentOutputFilePath;
    private FileDescriptor currentOutputFd;

    // MediaProjection components
    private MediaProjection mediaProjection;
    private VirtualDisplay virtualDisplay;
    // Video encoding components
    private MediaCodec videoEncoder;
    private Surface encoderInputSurface;
    private Surface previewSurface;
    private int previewSurfaceWidth = -1;
    private int previewSurfaceHeight = -1;
    private FragmentedMp4MuxerWrapper mediaMuxer;
    private int videoTrackIndex = -1;
    
    // OpenGL watermarking pipeline: GLWatermarkRenderer handles watermarks + preview for all Android versions
    private GLWatermarkRenderer glWatermarkRenderer;
    private HandlerThread glRenderThread;
    private Handler glRenderHandler;
    private final AtomicBoolean renderRunnableQueued = new AtomicBoolean(false);
    private volatile boolean glInitialized = false;
    /** Surface that VirtualDisplay writes screen frames into (GLWatermarkRenderer's camera input). */
    private Surface glScreenInputSurface;

    /**
     * Render runnable posted to glRenderHandler whenever a new screen frame is available.
     * Calls GLWatermarkRenderer.renderFrame() which renders watermarks to the encoder surface
     * and to the preview surface if one is attached.
     */
    private final Runnable renderRunnable = new Runnable() {
        private int frameCount = 0;

        @Override
        public void run() {
            try {
                if (isStopped || !isRecording) {
                    return;
                }
                // Refresh watermark text periodically (every ~30 frames ≈ 1 s at 30 fps)
                if (++frameCount % 30 == 0 && watermarkInfoProvider != null && glWatermarkRenderer != null) {
                    try {
                        String text = watermarkInfoProvider.getWatermarkText();
                        if (text != null) {
                            glWatermarkRenderer.setWatermarkText(text);
                        }
                    } catch (Exception e) {
                        FLog.w(TAG, "Watermark text refresh failed", e);
                    }
                }
                if (glWatermarkRenderer != null) {
                    glWatermarkRenderer.renderFrame();
                }
            } catch (Exception e) {
                FLog.e(TAG, "Error in GL render loop", e);
            } finally {
                renderRunnableQueued.set(false);
            }
        }
    };

    /** Serialises drainVideoEncoder() calls from the video-encoding poll thread and stop thread. */
    private final Object encoderDrainLock = new Object();

    // Audio recording components
    private AudioRecord audioRecord;
    private MediaCodec audioEncoder;
    private int audioTrackIndex = -1;
    private Thread audioRecordingThread;
    
    // Threading
    private HandlerThread videoEncodingThread;
    private Handler videoEncodingHandler;
    private HandlerThread audioEncodingThread;
    private Handler audioEncodingHandler;
    
    // State management
    private boolean isRecording = false;
    private boolean isPaused = false;
    private boolean isStopped = false;
    private boolean muxerStarted = false;
    private volatile boolean audioMuted = false;
    
    // Timestamp management
    private final Object timestampLock = new Object();
    private long recordingStartTimeNanos = -1;
    private long firstVideoTimestampNanos = -1;
    private long firstAudioTimestampNanos = -1;
    private long totalPausedTimeNanos = 0;
    private long pauseStartTimeNanos = -1;
    
    /**
     * Builder for ScreenRecordingPipeline
     */
    public static class Builder {
        private Context context;
        private WatermarkInfoProvider watermarkInfoProvider;
        private int screenWidth;
        private int screenHeight;
        private int screenDensity;
        private int videoFramerate = Constants.DEFAULT_SCREEN_RECORDING_FPS;
        private int videoBitrate;
        private boolean enableAudio = false;
        private String audioSource = Constants.AUDIO_SOURCE_MIC;
        private String outputFilePath;
        private FileDescriptor outputFd;
        private MediaProjection mediaProjection;
        private long maxFileSizeBytes = Long.MAX_VALUE;
        private SegmentCallback segmentCallback;

        public Builder(Context context) {
            this.context = context.getApplicationContext();
        }
        
        public Builder setWatermarkInfoProvider(WatermarkInfoProvider provider) {
            this.watermarkInfoProvider = provider;
            return this;
        }
        
        public Builder setScreenDimensions(int width, int height, int density) {
            this.screenWidth = width;
            this.screenHeight = height;
            this.screenDensity = density;
            return this;
        }
        
        public Builder setVideoConfig(int framerate, int bitrate) {
            this.videoFramerate = framerate;
            this.videoBitrate = bitrate;
            return this;
        }
        
        public Builder setEnableAudio(boolean enable) {
            this.enableAudio = enable;
            return this;
        }
        
        /**
         * Set the audio source type for recording.
         * @param source One of Constants.AUDIO_SOURCE_MIC, AUDIO_SOURCE_INTERNAL, or AUDIO_SOURCE_NONE
         */
        public Builder setAudioSource(String source) {
            this.audioSource = source;
            return this;
        }
        
        public Builder setOutputFile(String filePath) {
            this.outputFilePath = filePath;
            return this;
        }
        
        public Builder setOutputFileDescriptor(FileDescriptor fd) {
            this.outputFd = fd;
            return this;
        }
        
        public Builder setMediaProjection(MediaProjection projection) {
            this.mediaProjection = projection;
            return this;
        }

        /**
         * Enable auto-splitting when file size reaches this limit.
         * @param maxBytes Maximum bytes per segment. Set to 0 or negative to disable.
         * @param callback Called to provide the next output file when rollover is needed.
         */
        public Builder setMaxFileSize(long maxBytes, SegmentCallback callback) {
            this.maxFileSizeBytes = maxBytes;
            this.segmentCallback = callback;
            return this;
        }
        
        public ScreenRecordingPipeline build() throws IOException {
            if (screenWidth <= 0 || screenHeight <= 0) {
                throw new IllegalArgumentException("Screen dimensions must be positive");
            }
            if (mediaProjection == null) {
                throw new IllegalArgumentException("MediaProjection is required");
            }
            if (outputFilePath == null && outputFd == null) {
                throw new IllegalArgumentException("Output file path or descriptor required");
            }
            
            return new ScreenRecordingPipeline(this);
        }
    }
    
    private ScreenRecordingPipeline(Builder builder) throws IOException {
        this.context = builder.context;
        this.watermarkInfoProvider = builder.watermarkInfoProvider;
        
        // Store original screen dimensions for VirtualDisplay (to avoid black bars)
        this.displayWidth = builder.screenWidth;
        this.displayHeight = builder.screenHeight;
        
        // Start with a same-as-screen-aspect encoder size; actual size may be adjusted
        // during encoder initialization based on codec capabilities.
        int[] initialEncoder = computeScaledSizeMaintainingAspect(displayWidth, displayHeight, 1.0f);
        this.screenWidth = initialEncoder[0];
        this.screenHeight = initialEncoder[1];
        
        // FLog.d(TAG, "Display dimensions: " + this.displayWidth + "x" + this.displayHeight);
        // FLog.d(TAG, "Encoder dimensions: " + this.screenWidth + "x" + this.screenHeight);
        
        this.screenDensity = builder.screenDensity;
        this.videoFramerate = builder.videoFramerate;
        this.videoBitrate = builder.videoBitrate;
        this.enableAudio = builder.enableAudio;
        this.audioSource = builder.audioSource;
        this.outputFilePath = builder.outputFilePath;
        this.outputFd = builder.outputFd;
        this.mediaProjection = builder.mediaProjection;
        this.maxFileSizeBytes = builder.maxFileSizeBytes;
        this.segmentCallback = builder.segmentCallback;
        this.currentOutputFilePath = builder.outputFilePath;
        this.currentOutputFd = builder.outputFd;
        
        initialize();
    }
    
    /**
     * Round dimension to nearest multiple of 16 (required for H.264 encoding)
     */
    private static int roundToMultipleOf16(int dimension) {
        return (dimension + 15) / 16 * 16;
    }
    
    /**
     * Returns an encoder size that maintains the screen aspect ratio.
     * This prevents letterboxing/pillarboxing caused by aspect mismatch.
     */
    private static int[] computeScaledSizeMaintainingAspect(int displayWidth, int displayHeight, float scale) {
        int width = roundToMultipleOf16(Math.max(16, Math.round(displayWidth * scale)));
        int height = roundToMultipleOf16(Math.max(16, Math.round(displayHeight * scale)));

        // Clamp to a minimum reasonable size.
        width = Math.max(320, width);
        height = Math.max(320, height);

        return new int[]{width, height};
    }

    /**
     * Candidate encoder sizes to try for hardware codec. We keep aspect ratio identical,
     * and scale down progressively for devices with strict HW limits.
     */
    private static int[][] buildEncoderSizeCandidates(int displayWidth, int displayHeight) {
        float[] scales = new float[]{1.0f, 0.9f, 0.8f, 0.7f, 0.6f, 0.5f};
        int[][] candidates = new int[scales.length][2];
        for (int i = 0; i < scales.length; i++) {
            candidates[i] = computeScaledSizeMaintainingAspect(displayWidth, displayHeight, scales[i]);
        }
        return candidates;
    }
    
    /**     * Initialize all encoding components
     */
    private void initialize() throws IOException {
        // FLog.d(TAG, "Initializing ScreenRecordingPipeline: " + screenWidth + "x" + screenHeight + 
        //     "@" + videoFramerate + "fps, bitrate=" + videoBitrate);
        
        // Create encoding threads
        videoEncodingThread = new HandlerThread("ScreenVideoEncoding");
        videoEncodingThread.start();
        videoEncodingHandler = new Handler(videoEncodingThread.getLooper());
        
        if (enableAudio) {
            audioEncodingThread = new HandlerThread("ScreenAudioEncoding");
            audioEncodingThread.start();
            audioEncodingHandler = new Handler(audioEncodingThread.getLooper());
        }
        
        // Initialize muxer
        initializeMuxer();
        
        // Initialize video encoder (must run before GL init — encoderInputSurface required)
        initializeVideoEncoder();

        // Initialize GL watermark renderer (uses encoderInputSurface as the GL output target)
        initializeGLRenderer();

        // Initialize audio encoder if enabled
        if (enableAudio) {
            initializeAudioEncoder();
        }
        
        // FLog.d(TAG, "ScreenRecordingPipeline initialized successfully");
    }
    
    /**
     * Initialize FragmentedMp4Muxer for crash-safe recording
     */
    private void initializeMuxer() throws IOException {
        if (currentOutputFd != null) {
            mediaMuxer = new FragmentedMp4MuxerWrapper(currentOutputFd);
        } else {
            mediaMuxer = new FragmentedMp4MuxerWrapper(currentOutputFilePath);
        }
        // FLog.d(TAG, "FragmentedMp4Muxer created");
    }
    
    /**
     * Initialize MediaCodec video encoder
     */
    private void initializeVideoEncoder() throws IOException {
        IOException lastException = null;

        int[][] candidates = buildEncoderSizeCandidates(displayWidth, displayHeight);
        FLog.i(TAG, "Encoder init start: display=" + displayWidth + "x" + displayHeight
                + " preferSoftware=" + preferSoftwareAvcEncoder);

        if (!preferSoftwareAvcEncoder) {
            // First-start churn was coming from retrying hardware across many resolutions.
            // Try hardware only once at the native-sized target. If it fails, prefer software
            // for the rest of this process lifetime.
            int candidateWidth = candidates[0][0];
            int candidateHeight = candidates[0][1];
            try {
                MediaCodec encoder = MediaCodec.createEncoderByType(VIDEO_MIME_TYPE);
                configureVideoEncoder(encoder, false, candidateWidth, candidateHeight);

                videoEncoder = encoder;
                screenWidth = candidateWidth;
                screenHeight = candidateHeight;

                FLog.i(TAG, "Hardware H.264 encoder initialized: " + screenWidth + "x" + screenHeight);
                return;
            } catch (Exception e) {
                lastException = new IOException("Hardware encoder failed for " + candidateWidth + "x" + candidateHeight, e);
                preferSoftwareAvcEncoder = true;
                FLog.w(TAG, "Hardware encoder failed for " + candidateWidth + "x" + candidateHeight + ": " + e.getMessage());
            }
        }

        // Fallback to software encoder (more flexible). Prefer the largest (scale 1.0) size.
        String softwareCodecName = findSoftwareAvcEncoderName();
        FLog.i(TAG, "Falling back to software AVC encoder: " + softwareCodecName);
        for (int i = 0; i < candidates.length; i++) {
            int candidateWidth = candidates[i][0];
            int candidateHeight = candidates[i][1];
            try {
                MediaCodec encoder = softwareCodecName != null
                        ? MediaCodec.createByCodecName(softwareCodecName)
                        : MediaCodec.createEncoderByType(VIDEO_MIME_TYPE);
                configureVideoEncoder(encoder, true, candidateWidth, candidateHeight);

                videoEncoder = encoder;
                screenWidth = candidateWidth;
                screenHeight = candidateHeight;

                FLog.i(TAG, "Software H.264 encoder initialized: " + screenWidth + "x" + screenHeight);
                return;
            } catch (Exception e) {
                lastException = new IOException("Software encoder failed for " + candidateWidth + "x" + candidateHeight, e);
                FLog.w(TAG, "Software encoder failed for " + candidateWidth + "x" + candidateHeight + ": " + e.getMessage());
            }
        }

        throw new IOException("Failed to initialize video encoder (tried hardware and software)", lastException);
    }

    @Nullable
    private String findSoftwareAvcEncoderName() {
        try {
            MediaCodecList codecList = new MediaCodecList(MediaCodecList.ALL_CODECS);
            for (MediaCodecInfo info : codecList.getCodecInfos()) {
                if (!info.isEncoder()) {
                    continue;
                }
                for (String type : info.getSupportedTypes()) {
                    if (VIDEO_MIME_TYPE.equalsIgnoreCase(type) && info.isSoftwareOnly()) {
                        return info.getName();
                    }
                }
            }
        } catch (Exception e) {
            FLog.w(TAG, "Failed to enumerate software AVC encoders: " + e.getMessage());
        }
        return "OMX.google.h264.encoder";
    }
    
    /**
     * Configure video encoder with proper settings
     */
    private void configureVideoEncoder(MediaCodec encoder, boolean isSoftware, int width, int height) throws IOException {
        long displayPixels = (long) displayWidth * (long) displayHeight;
        long encoderPixels = (long) width * (long) height;
        int effectiveBitrate = videoBitrate;
        if (displayPixels > 0 && encoderPixels > 0) {
            effectiveBitrate = (int) Math.max(500_000L, (videoBitrate * encoderPixels) / displayPixels);
        }

        MediaFormat format = MediaFormat.createVideoFormat(VIDEO_MIME_TYPE, width, height);
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        format.setInteger(MediaFormat.KEY_BIT_RATE, effectiveBitrate);
        format.setInteger(MediaFormat.KEY_FRAME_RATE, videoFramerate);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, VIDEO_IFRAME_INTERVAL);

        // Baseline profile for maximum compatibility
        format.setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline);

        // Bitrate mode: VBR for quality, CBR for consistent bitrate
        format.setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR);

        // FLog.d(TAG, String.format("Configuring %s encoder: %dx%d @%dfps, bitrate=%d, mode=VBR",
        //     isSoftware ? "software" : "hardware", width, height, videoFramerate, effectiveBitrate));

        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        encoderInputSurface = encoder.createInputSurface();
    }
    
    /**
     * Initialize OpenGL watermark renderer pipeline.
     * <p>
     * Creates a GL thread, initialises {@link GLWatermarkRenderer} with the encoder input
     * surface, and obtains the surface that {@link VirtualDisplay} should write screen frames
     * into ({@link #glScreenInputSurface}).  The renderer then applies watermarks on every
     * frame and outputs the composited result to the MediaCodec encoder surface.
     * <p>
     * Falls back gracefully to direct encoding (without watermarks) if GL setup fails.
     */
    private void initializeGLRenderer() {
        glRenderThread = new HandlerThread("ScreenGLRender");
        glRenderThread.start();
        glRenderHandler = new Handler(glRenderThread.getLooper());

        final CountDownLatch initLatch = new CountDownLatch(1);
        final Throwable[] initError = new Throwable[1];

        glRenderHandler.post(() -> {
            try {
                // orientation="portrait", sensorOrientation=0:
                // For screen capture the VirtualDisplay SurfaceTexture already provides the
                // correct coordinate transform via getTransformMatrix().  The "portrait" path
                // in GLWatermarkRenderer uses that texMatrix as-is, which is exactly what we
                // need — no extra rotation or flip should be applied to screen content.
                glWatermarkRenderer = new GLWatermarkRenderer(
                        context, encoderInputSurface, "portrait", 0, screenWidth, screenHeight, true);

                glWatermarkRenderer.initializeEGL();

                // The surface returned here is backed by GLWatermarkRenderer's internal
                // SurfaceTexture (cameraSurfaceTexture). VirtualDisplay will write compressed
                // screen frames into it, and the GL render loop reads them back via
                // updateTexImage(), applies watermarks, and pushes the result to the encoder.
                glScreenInputSurface = glWatermarkRenderer.getCameraInputSurface();
                if (glScreenInputSurface == null || !glScreenInputSurface.isValid()) {
                    throw new RuntimeException("GLWatermarkRenderer returned an invalid camera input surface");
                }

                // Apply initial watermark text so the first frame is already watermarked.
                if (watermarkInfoProvider != null) {
                    try {
                        String text = watermarkInfoProvider.getWatermarkText();
                        glWatermarkRenderer.setWatermarkText(text != null ? text : "");
                    } catch (Exception e) {
                        FLog.w(TAG, "Failed to apply initial watermark text", e);
                    }
                }

                // Frame-available callback is invoked on the GL handler thread (same Looper
                // that GLWatermarkRenderer's SurfaceTexture listener uses).  We post
                // renderRunnable via the same handler to keep all GL operations serial.
                glWatermarkRenderer.setOnFrameAvailableListener(() -> {
                    if (!isStopped && isRecording
                            && renderRunnableQueued.compareAndSet(false, true)) {
                        glRenderHandler.post(renderRunnable);
                    }
                });

                glInitialized = true;
                FLog.d(TAG, "GL watermark renderer ready: " + screenWidth + "x" + screenHeight);
            } catch (Throwable t) {
                initError[0] = t;
                FLog.e(TAG, "GL renderer init failed — falling back to direct encoding", t);
            } finally {
                initLatch.countDown();
            }
        });

        try {
            if (!initLatch.await(3, TimeUnit.SECONDS)) {
                FLog.e(TAG, "GL renderer init timed out — falling back to direct encoding");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            FLog.w(TAG, "GL renderer init interrupted");
        }

        if (!glInitialized) {
            cleanupGLRenderer();
        }
    }

    /**
     * Release GL renderer resources. Safe to call even if GL was never fully initialised.
     */
    private void cleanupGLRenderer() {
        glInitialized = false;
        glScreenInputSurface = null;
        if (glWatermarkRenderer != null) {
            try {
                glWatermarkRenderer.release();
            } catch (Exception e) {
                FLog.w(TAG, "Error releasing GL renderer", e);
            }
            glWatermarkRenderer = null;
        }
        if (glRenderThread != null) {
            glRenderThread.quitSafely();
            glRenderThread = null;
        }
        glRenderHandler = null;
    }

    /**
     * Initialize audio recording and encoding
     */
    private void initializeAudioEncoder() throws IOException {
        try {
            // Audio format configuration
            int sampleRate = Constants.DEFAULT_AUDIO_SAMPLING_RATE;
            int channelConfig = AudioFormat.CHANNEL_IN_MONO;
            int audioFormat = AudioFormat.ENCODING_PCM_16BIT;
            
            // Calculate buffer size
            int minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat);
            int bufferSize = minBufferSize * 2; // Double buffer for safety
            
            // Create AudioRecord based on audio source type
            if (Constants.AUDIO_SOURCE_INTERNAL.equals(audioSource)
                    && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Internal/device audio via AudioPlaybackCapture (API 29+)
                AudioPlaybackCaptureConfiguration playbackConfig =
                    new AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
                        .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                        .addMatchingUsage(AudioAttributes.USAGE_GAME)
                        .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                        .build();
                
                audioRecord = new AudioRecord.Builder()
                    .setAudioPlaybackCaptureConfig(playbackConfig)
                    .setAudioFormat(new AudioFormat.Builder()
                        .setEncoding(audioFormat)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .build())
                    .setBufferSizeInBytes(bufferSize)
                    .build();
                
                FLog.d(TAG, "AudioRecord initialized with AudioPlaybackCapture (internal audio)");
            } else {
                // Microphone audio (default)
                audioRecord = new AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    sampleRate,
                    channelConfig,
                    audioFormat,
                    bufferSize
                );
                FLog.d(TAG, "AudioRecord initialized with microphone source");
            }
            
            if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
                throw new IOException("AudioRecord not initialized - source: " + audioSource);
            }
            
            // Create audio encoder (AAC)
            MediaFormat audioFormat_encoder = MediaFormat.createAudioFormat(
                MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, 1);
            audioFormat_encoder.setInteger(MediaFormat.KEY_AAC_PROFILE, 
                MediaCodecInfo.CodecProfileLevel.AACObjectLC);
            audioFormat_encoder.setInteger(MediaFormat.KEY_BIT_RATE, Constants.DEFAULT_AUDIO_BITRATE);
            audioFormat_encoder.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384);
            
            audioEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
            audioEncoder.configure(audioFormat_encoder, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            
            FLog.d(TAG, "Audio encoder initialized: AAC, " + sampleRate + "Hz, source=" + audioSource);
            
        } catch (IOException e) {
            FLog.e(TAG, "Failed to initialize audio encoder (source: " + audioSource + ")", e);
            throw e;
        }
    }
    
    
    /**
     * Start recording
     */
    public void startRecording() throws IOException {
        if (isRecording) {
            FLog.w(TAG, "Already recording");
            return;
        }
        
        // FLog.d(TAG, "Starting screen recording");
        
        // Start encoders
        videoEncoder.start();
        if (enableAudio && audioEncoder != null) {
            audioEncoder.start();
            audioRecord.startRecording();
        }
        
        // Create VirtualDisplay
        createVirtualDisplay();
        refreshPreviewVirtualDisplay();
        
        // Start timestamp tracking
        synchronized (timestampLock) {
            recordingStartTimeNanos = System.nanoTime();
            firstVideoTimestampNanos = -1;
            firstAudioTimestampNanos = -1;
        }
        
        isRecording = true;
        isStopped = false;
        
        // Start encoding loops
        startVideoEncodingLoop();
        if (enableAudio) {
            startAudioRecordingLoop();
            startAudioEncodingLoop();
        }
        
        // FLog.d(TAG, "Screen recording started");
    }
    
    /**
     * Create VirtualDisplay for screen capture.
     * <p>
     * When the GL watermark renderer is active, frames are sent to
     * {@link #glScreenInputSurface} (the renderer's internal SurfaceTexture).  The renderer
     * then composites watermarks and pushes the result to the MediaCodec encoder surface.
     * If GL initialisation failed we fall back to writing directly to the encoder surface.
     */
    private void createVirtualDisplay() {
        // Route screen frames through the GL watermark renderer when available.
        Surface targetSurface = (glInitialized && glScreenInputSurface != null
                && glScreenInputSurface.isValid())
                ? glScreenInputSurface
                : encoderInputSurface;

        virtualDisplay = mediaProjection.createVirtualDisplay(
                "ScreenRecording",
                screenWidth,
                screenHeight,
                screenDensity,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                targetSurface,
                null,
                null);

        FLog.d(TAG, "VirtualDisplay created: " + screenWidth + "x" + screenHeight
                + " (GL=" + glInitialized + ")");
    }

    /**
     * Attach or detach the live-preview surface.
     * <p>
     * Delegates to {@link GLWatermarkRenderer#setPreviewSurface(Surface)} on the GL thread
     * so every watermarked frame is written to both the encoder surface and the preview
     * TextureView.
     *
     * @param surface preview surface, or {@code null} to stop preview
     * @param width   surface width (informational)
     * @param height  surface height (informational)
     */
    public synchronized void setPreviewSurface(@Nullable Surface surface, int width, int height) {
        previewSurface = surface;
        previewSurfaceWidth = width;
        previewSurfaceHeight = height;

        if (glRenderHandler != null) {
            final Surface surf = surface;
            glRenderHandler.post(() -> {
                if (glWatermarkRenderer != null) {
                    glWatermarkRenderer.setPreviewSurface(surf);
                }
            });
        }
    }

    /**
     * No-op: the VirtualDisplay always writes to {@link #glScreenInputSurface}.
     * Preview is rendered as a side-output by {@link GLWatermarkRenderer}.
     */
    private void refreshPreviewVirtualDisplay() {
        // GL pipeline: nothing to switch.
    }
    
    /**
     * Start video encoding loop
     */
    private void startVideoEncodingLoop() {
        videoEncodingHandler.post(new Runnable() {
            @Override
            public void run() {
                if (isStopped || !isRecording) {
                    return;
                }
                
                drainVideoEncoder(false);
                
                // Continue loop
                if (isRecording && !isStopped) {
                    videoEncodingHandler.postDelayed(this, 10);
                }
            }
        });
    }
    
    /**
     * Drain video encoder output.
     * <p>
     * Synchronised on {@link #encoderDrainLock} to prevent concurrent access from the
     * video-encoding poll thread and the stop / release thread.
     */
    private void drainVideoEncoder(boolean endOfStream) {
        synchronized (encoderDrainLock) {
        if (endOfStream) {
            videoEncoder.signalEndOfInputStream();
        }

        MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();

        while (true) {
            int outputBufferIndex = videoEncoder.dequeueOutputBuffer(bufferInfo, 0);

            if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                // Muxer setup
                MediaFormat newFormat = videoEncoder.getOutputFormat();
                cachedVideoFormat = newFormat; // Cache for rollover
                videoTrackIndex = mediaMuxer.addTrack(newFormat);
                // FLog.d(TAG, "Video track added: " + videoTrackIndex);

                tryStartMuxer();

            } else if (outputBufferIndex >= 0) {
                ByteBuffer outputBuffer = videoEncoder.getOutputBuffer(outputBufferIndex);

                if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                    bufferInfo.size = 0;
                }

                if (bufferInfo.size > 0 && muxerStarted && !isPaused) {
                    // Normalize timestamp
                    long presentationTimeUs = getSynchronizedVideoTimestamp(bufferInfo.presentationTimeUs);
                    bufferInfo.presentationTimeUs = presentationTimeUs;

                    outputBuffer.position(bufferInfo.offset);
                    outputBuffer.limit(bufferInfo.offset + bufferInfo.size);

                    mediaMuxer.writeSampleData(videoTrackIndex, outputBuffer, bufferInfo);

                    // Track segment bytes for auto-splitting
                    segmentBytesWritten += bufferInfo.size;
                    lastVideoSegmentBytes += bufferInfo.size;

                    // Check if we need to split the segment due to size
                    if (shouldSplitSegment()) {
                        FLog.d(TAG, "Size limit reached, rolling over segment");
                        rolloverSegment();
                    }
                }

                videoEncoder.releaseOutputBuffer(outputBufferIndex, false);

                if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    break;
                }
            } else {
                break;
            }
        }
        } // end synchronized (encoderDrainLock)
    }
    
    /**
     * Start audio recording loop
     */
    private void startAudioRecordingLoop() {
        audioRecordingThread = new Thread(() -> {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO);
            
            ByteBuffer audioBuffer = ByteBuffer.allocateDirect(16384);
            
            while (isRecording && !isStopped) {
                if (isPaused) {
                    try {
                        Thread.sleep(10);
                    } catch (InterruptedException e) {
                        break;
                    }
                    continue;
                }
                
                audioBuffer.clear();
                int bytesRead = audioRecord.read(audioBuffer, audioBuffer.capacity());
                
                if (bytesRead > 0) {
                    if (audioMuted) {
                        zeroAudioBuffer(audioBuffer, bytesRead);
                    }
                    audioBuffer.limit(bytesRead);
                    queueAudioData(audioBuffer, bytesRead);
                }
            }
        });
        audioRecordingThread.start();
    }

    private void zeroAudioBuffer(ByteBuffer buffer, int size) {
        for (int i = 0; i < size; i++) {
            buffer.put(i, (byte) 0);
        }
    }
    
    /**
     * Queue audio data to encoder
     */
    private void queueAudioData(ByteBuffer audioData, int size) {
        int inputBufferIndex = audioEncoder.dequeueInputBuffer(10000);
        if (inputBufferIndex >= 0) {
            ByteBuffer inputBuffer = audioEncoder.getInputBuffer(inputBufferIndex);
            inputBuffer.clear();
            inputBuffer.put(audioData);
            
            long presentationTimeUs = getSynchronizedAudioTimestamp();
            audioEncoder.queueInputBuffer(inputBufferIndex, 0, size, presentationTimeUs, 0);
        }
    }
    
    /**
     * Start audio encoding loop
     */
    private void startAudioEncodingLoop() {
        audioEncodingHandler.post(new Runnable() {
            @Override
            public void run() {
                if (isStopped || !isRecording) {
                    return;
                }
                
                drainAudioEncoder(false);
                
                if (isRecording && !isStopped) {
                    audioEncodingHandler.postDelayed(this, 10);
                }
            }
        });
    }
    
    /**
     * Drain audio encoder output
     */
    private void drainAudioEncoder(boolean endOfStream) {
        if (endOfStream) {
            int inputBufferIndex = audioEncoder.dequeueInputBuffer(10000);
            if (inputBufferIndex >= 0) {
                audioEncoder.queueInputBuffer(inputBufferIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
            }
        }
        
        MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
        
        while (true) {
            int outputBufferIndex = audioEncoder.dequeueOutputBuffer(bufferInfo, 0);
            
            if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                MediaFormat newFormat = audioEncoder.getOutputFormat();
                cachedAudioFormat = newFormat; // Cache for rollover
                audioTrackIndex = mediaMuxer.addTrack(newFormat);
                // FLog.d(TAG, "Audio track added: " + audioTrackIndex);

                tryStartMuxer();
                
            } else if (outputBufferIndex >= 0) {
                ByteBuffer outputBuffer = audioEncoder.getOutputBuffer(outputBufferIndex);
                
                if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                    bufferInfo.size = 0;
                }
                
                if (bufferInfo.size > 0 && muxerStarted && !isPaused) {
                    long presentationTimeUs = getSynchronizedAudioTimestamp();
                    bufferInfo.presentationTimeUs = presentationTimeUs;
                    
                    outputBuffer.position(bufferInfo.offset);
                    outputBuffer.limit(bufferInfo.offset + bufferInfo.size);

                    mediaMuxer.writeSampleData(audioTrackIndex, outputBuffer, bufferInfo);

                    segmentBytesWritten += bufferInfo.size;
                    lastAudioSegmentBytes += bufferInfo.size;
                }
                
                audioEncoder.releaseOutputBuffer(outputBufferIndex, false);
                
                if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    break;
                }
            } else {
                break;
            }
        }
    }
    
    /**
     * Sets the next output file or FileDescriptor for segment rollover.
     * Call this from the segment callback to update the output destination before
     * the new muxer is created.
     */
    public void setNextOutputPath(String filePath, FileDescriptor fd) {
        this.currentOutputFilePath = filePath;
        this.currentOutputFd = fd;
    }

    /**
     * Try to start muxer when all tracks are ready
     */
    private void tryStartMuxer() {
        boolean videoReady = videoTrackIndex >= 0;
        boolean audioReady = !enableAudio || audioTrackIndex >= 0;

        if (!muxerStarted && videoReady && audioReady) {
            mediaMuxer.start();
            muxerStarted = true;
            // FLog.d(TAG, "Muxer started");
        }
    }

    // ── Segment rollover (auto-splitting) ──

    private boolean shouldSplitSegment() {
        if (maxFileSizeBytes <= 0) return false;
        if (pendingRollover || awaitingKeyframeForRollover || rolloverInProgress || rolloverRequestedByDrain) return false;
        return segmentBytesWritten >= maxFileSizeBytes;
    }

    /**
     * Rolls over to a new output file segment. Stops the current muxer, requests
     * the next file from the callback, creates a new muxer, re-adds cached tracks,
     * and starts writing to the new file. Encoders keep running without interruption.
     */
    private void rolloverSegment() {
        try {
            FLog.i(TAG, "Auto-splitting: segment size limit reached, rolling over to next segment");
            FLog.d(TAG, "Current segment number: " + segmentNumber + ", rolling over to " + (segmentNumber + 1));
            if (rolloverInProgress) {
                FLog.w(TAG, "Rollover already in progress; skipping");
                return;
            }
            rolloverInProgress = true;

            // Request the next segment file/descriptor from callback
            segmentNumber++;
            if (segmentCallback != null) {
                segmentCallback.onSegmentRollover(segmentNumber);
            } else {
                FLog.e(TAG, "Segment callback is null, cannot continue rollover");
                rolloverInProgress = false;
                return;
            }

            // Check if callback set the new output
            if (currentOutputFilePath == null && currentOutputFd == null) {
                FLog.e(TAG, "Segment callback did not set new output path or descriptor");
                rolloverInProgress = false;
                return;
            }

            // Validate output path exists and is writable
            if (currentOutputFilePath != null) {
                try {
                    java.io.File segFile = new java.io.File(currentOutputFilePath);
                    java.io.File parentDir = segFile.getParentFile();
                    if (parentDir != null && !parentDir.exists()) {
                        if (!parentDir.mkdirs()) {
                            FLog.e(TAG, "Failed to create segment directory: " + parentDir.getAbsolutePath());
                            rolloverInProgress = false;
                            return;
                        }
                    }
                } catch (Exception e) {
                    FLog.e(TAG, "Invalid segment path: " + currentOutputFilePath, e);
                    rolloverInProgress = false;
                    return;
                }
            }

            // Stop and release the current muxer
            if (mediaMuxer != null) {
                try {
                    if (muxerStarted) {
                        mediaMuxer.stop();
                    }
                    mediaMuxer.release();
                } catch (Exception e) {
                    FLog.e(TAG, "Error releasing muxer during rollover", e);
                }
                mediaMuxer = null;
                muxerStarted = false;
            }

            // Reset track indices
            audioTrackIndex = -1;
            videoTrackIndex = -1;

            // Reset byte counters for new segment
            segmentBytesWritten = 0L;
            lastVideoSegmentBytes = 0L;
            lastAudioSegmentBytes = 0L;

            // Create new muxer for the next segment
            initializeMuxer();

            // Re-add cached tracks because encoder will NOT emit format changed again
            if (cachedVideoFormat != null) {
                videoTrackIndex = mediaMuxer.addTrack(cachedVideoFormat);
                FLog.d(TAG, "Added cached video track to new muxer. index=" + videoTrackIndex);
            } else {
                FLog.w(TAG, "Video format not cached; new segment will wait for format change");
            }

            if (enableAudio && cachedAudioFormat != null) {
                try {
                    audioTrackIndex = mediaMuxer.addTrack(cachedAudioFormat);
                    FLog.d(TAG, "Added cached audio track to new muxer. index=" + audioTrackIndex);
                } catch (Exception e) {
                    FLog.e(TAG, "Failed adding cached audio track to new muxer", e);
                }
            }

            // Start muxer if conditions met
            tryStartMuxer();

            FLog.i(TAG, "Started new segment: " + segmentNumber +
                    (currentOutputFilePath != null ? " at path: " + currentOutputFilePath
                            : " with file descriptor"));
            rolloverInProgress = false;
        } catch (Exception e) {
            FLog.e(TAG, "Error during segment rollover — stopping recording", e);
            rolloverInProgress = false;
        }
    }
    
    /**
     * Pause recording
     */
    public void pauseRecording() {
        if (!isRecording || isPaused) {
            return;
        }
        
        synchronized (timestampLock) {
            pauseStartTimeNanos = System.nanoTime();
        }
        
        isPaused = true;
        // FLog.d(TAG, "Recording paused");
    }
    
    /**
     * Resume recording
     */
    public void resumeRecording() {
        if (!isRecording || !isPaused) {
            return;
        }
        
        synchronized (timestampLock) {
            if (pauseStartTimeNanos > 0) {
                totalPausedTimeNanos += (System.nanoTime() - pauseStartTimeNanos);
                pauseStartTimeNanos = -1;
            }
        }
        
        isPaused = false;
        FLog.d(TAG, "Recording resumed");
    }

    /**
     * Runtime mute toggle for screen-recording audio.
     * Keeps encoder timestamps continuous by feeding silent PCM while muted.
     */
    public void setAudioMuted(boolean muted) {
        this.audioMuted = muted;
    }
    
    /**
     * Stop recording and release resources
     */
    public void stopRecording() {
        if (isStopped) {
            return;
        }
        
        FLog.d(TAG, "Stopping screen recording");
        isStopped = true;
        isRecording = false;
        
        // Stop audio recording
        if (audioRecord != null) {
            try {
                audioRecord.stop();
            } catch (Exception e) {
                FLog.e(TAG, "Error stopping AudioRecord", e);
            }
        }
        
        // Drain encoders with EOS
        drainVideoEncoder(true);
        if (enableAudio && audioEncoder != null) {
            drainAudioEncoder(true);
        }
        
        // Release resources
        release();
        
        FLog.d(TAG, "Screen recording stopped");
    }
    
    /**
     * Release all resources
     */
    public void release() {
        // Release GL renderer first — this stops frame callbacks and clears the GL thread.
        // Must happen before releasing the encoder surface (GLWatermarkRenderer holds a
        // reference to encoderInputSurface via its EGL window surface).
        cleanupGLRenderer();

        // Release VirtualDisplay
        if (virtualDisplay != null) {
            virtualDisplay.release();
            virtualDisplay = null;
        }
        
        // Release video encoder
        if (videoEncoder != null) {
            try {
                videoEncoder.stop();
                videoEncoder.release();
            } catch (Exception e) {
                FLog.e(TAG, "Error releasing video encoder", e);
            }
            videoEncoder = null;
        }
        
        // Release audio encoder
        if (audioEncoder != null) {
            try {
                audioEncoder.stop();
                audioEncoder.release();
            } catch (Exception e) {
                FLog.e(TAG, "Error releasing audio encoder", e);
            }
            audioEncoder = null;
        }
        
        // Release AudioRecord
        if (audioRecord != null) {
            try {
                audioRecord.release();
            } catch (Exception e) {
                FLog.e(TAG, "Error releasing AudioRecord", e);
            }
            audioRecord = null;
        }
        
        // Release muxer
        if (mediaMuxer != null) {
            try {
                if (muxerStarted) {
                    mediaMuxer.stop();
                }
                mediaMuxer.release();
            } catch (Exception e) {
                FLog.e(TAG, "Error releasing muxer", e);
            }
            mediaMuxer = null;
        }
        
        // Stop encoding threads
        if (videoEncodingThread != null) {
            videoEncodingThread.quitSafely();
            videoEncodingThread = null;
        }
        
        if (audioEncodingThread != null) {
            audioEncodingThread.quitSafely();
            audioEncodingThread = null;
        }
    }
    
    /**
     * Get synchronized audio timestamp
     */
    private long getSynchronizedAudioTimestamp() {
        synchronized (timestampLock) {
            if (recordingStartTimeNanos == -1) {
                recordingStartTimeNanos = System.nanoTime();
                return 0;
            }
            
            long elapsedNanos = System.nanoTime() - recordingStartTimeNanos - totalPausedTimeNanos;
            return elapsedNanos / 1000L; // Convert to microseconds
        }
    }
    
    /**
     * Get synchronized video timestamp
     */
    private long getSynchronizedVideoTimestamp(long codecTimestampUs) {
        synchronized (timestampLock) {
            if (firstVideoTimestampNanos == -1) {
                firstVideoTimestampNanos = codecTimestampUs * 1000L;
                if (recordingStartTimeNanos == -1) {
                    recordingStartTimeNanos = System.nanoTime();
                }
                return 0;
            }
            
            long videoOffsetNanos = (codecTimestampUs * 1000L) - firstVideoTimestampNanos;
            return videoOffsetNanos / 1000L - (totalPausedTimeNanos / 1000L);
        }
    }
}
