package com.fadcam.fadrec.encoding;

import android.content.Context;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaRecorder;
import android.media.projection.MediaProjection;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.view.Surface;

import com.fadcam.Constants;
import com.fadcam.VideoCodec;
import com.fadcam.media.FragmentedMp4MuxerWrapper;
import com.fadcam.opengl.GLWatermarkRenderer;
import com.fadcam.opengl.WatermarkInfoProvider;

import java.io.FileDescriptor;
import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * ScreenRecordingPipeline manages screen recording with MediaCodec, OpenGL watermarking,
 * and fragmented MP4 output for crash-safety and streaming support.
 * 
 * Architecture: MediaProjection → VirtualDisplay → GLWatermarkRenderer → MediaCodec → FragmentedMp4Muxer
 */
public class ScreenRecordingPipeline {
    
    private static final String TAG = "ScreenRecPipeline";
    private static final String VIDEO_MIME_TYPE = "video/avc";
    private static final int VIDEO_IFRAME_INTERVAL = 1;
    
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
    private final String outputFilePath;
    private final FileDescriptor outputFd;
    
    // MediaProjection components
    private MediaProjection mediaProjection;
    private VirtualDisplay virtualDisplay;
    
    // Video encoding components
    private MediaCodec videoEncoder;
    private Surface encoderInputSurface;
    private FragmentedMp4MuxerWrapper mediaMuxer;
    private int videoTrackIndex = -1;
    
    // OpenGL watermarking
    private GLWatermarkRenderer glWatermarkRenderer;
    private Surface watermarkInputSurface;
    
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
        private String outputFilePath;
        private FileDescriptor outputFd;
        private MediaProjection mediaProjection;
        
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
        
        // Log.d(TAG, "Display dimensions: " + this.displayWidth + "x" + this.displayHeight);
        // Log.d(TAG, "Encoder dimensions: " + this.screenWidth + "x" + this.screenHeight);
        
        this.screenDensity = builder.screenDensity;
        this.videoFramerate = builder.videoFramerate;
        this.videoBitrate = builder.videoBitrate;
        this.enableAudio = builder.enableAudio;
        this.outputFilePath = builder.outputFilePath;
        this.outputFd = builder.outputFd;
        this.mediaProjection = builder.mediaProjection;
        
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
        // Log.d(TAG, "Initializing ScreenRecordingPipeline: " + screenWidth + "x" + screenHeight + 
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
        
        // Initialize video encoder
        initializeVideoEncoder();
        
        // TODO: OpenGL watermark renderer for screen recording
        // Will be properly integrated with MediaProjection surface in future update
        // if (watermarkInfoProvider != null) {
        //     initializeWatermarkRenderer();
        // }
        
        // Initialize audio encoder if enabled
        if (enableAudio) {
            initializeAudioEncoder();
        }
        
        // Log.d(TAG, "ScreenRecordingPipeline initialized successfully");
    }
    
    /**
     * Initialize FragmentedMp4Muxer for crash-safe recording
     */
    private void initializeMuxer() throws IOException {
        if (outputFd != null) {
            mediaMuxer = new FragmentedMp4MuxerWrapper(outputFd);
        } else {
            mediaMuxer = new FragmentedMp4MuxerWrapper(outputFilePath);
        }
        // Log.d(TAG, "FragmentedMp4Muxer created");
    }
    
    /**
     * Initialize MediaCodec video encoder
     */
    private void initializeVideoEncoder() throws IOException {
        IOException lastException = null;

        int[][] candidates = buildEncoderSizeCandidates(displayWidth, displayHeight);

        // Try hardware encoder first with multiple same-aspect resolutions.
        for (int[] candidate : candidates) {
            int candidateWidth = candidate[0];
            int candidateHeight = candidate[1];
            try {
                MediaCodec encoder = MediaCodec.createEncoderByType(VIDEO_MIME_TYPE);
                configureVideoEncoder(encoder, false, candidateWidth, candidateHeight);

                // Success: adopt this encoder instance + dimensions.
                videoEncoder = encoder;
                screenWidth = candidateWidth;
                screenHeight = candidateHeight;

                Log.i(TAG, "Hardware H.264 encoder initialized: " + screenWidth + "x" + screenHeight);
                return;
            } catch (Exception e) {
                lastException = new IOException("Hardware encoder failed for " + candidateWidth + "x" + candidateHeight, e);
                Log.w(TAG, "Hardware encoder failed for " + candidateWidth + "x" + candidateHeight + ": " + e.getMessage());
            }
        }

        // Fallback to software encoder (more flexible). Prefer the largest (scale 1.0) size.
        for (int i = 0; i < candidates.length; i++) {
            int candidateWidth = candidates[i][0];
            int candidateHeight = candidates[i][1];
            try {
                MediaCodec encoder = MediaCodec.createByCodecName("OMX.google.h264.encoder");
                configureVideoEncoder(encoder, true, candidateWidth, candidateHeight);

                videoEncoder = encoder;
                screenWidth = candidateWidth;
                screenHeight = candidateHeight;

                Log.i(TAG, "Software H.264 encoder initialized: " + screenWidth + "x" + screenHeight);
                return;
            } catch (Exception e) {
                lastException = new IOException("Software encoder failed for " + candidateWidth + "x" + candidateHeight, e);
                Log.w(TAG, "Software encoder failed for " + candidateWidth + "x" + candidateHeight + ": " + e.getMessage());
            }
        }

        throw new IOException("Failed to initialize video encoder (tried hardware and software)", lastException);
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
        
        // Software encoder may need lower complexity
        if (isSoftware) {
            format.setInteger(MediaFormat.KEY_COMPLEXITY, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR);
        }
        
        // Log.d(TAG, String.format("Configuring %s encoder: %dx%d @%dfps, bitrate=%d",
        //     isSoftware ? "software" : "hardware", width, height, videoFramerate, effectiveBitrate));
        
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        encoderInputSurface = encoder.createInputSurface();
    }
    
    /**
     * Initialize OpenGL watermark renderer
     * TODO: Implement proper watermarking for screen recording
     */
    // private void initializeWatermarkRenderer() {
    //     // Will be properly integrated with MediaProjection surface
    //     Log.d(TAG, "GLWatermarkRenderer initialization deferred");
    // }
    
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
            
            // Create AudioRecord
            audioRecord = new AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                channelConfig,
                audioFormat,
                bufferSize
            );
            
            if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
                throw new IOException("AudioRecord not initialized");
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
            
            // Log.d(TAG, "Audio encoder initialized: AAC, " + sampleRate + "Hz");
            
        } catch (IOException e) {
            Log.e(TAG, "Failed to initialize audio encoder", e);
            throw e;
        }
    }
    
    /**
     * Start recording
     */
    public void startRecording() throws IOException {
        if (isRecording) {
            Log.w(TAG, "Already recording");
            return;
        }
        
        // Log.d(TAG, "Starting screen recording");
        
        // Start encoders
        videoEncoder.start();
        if (enableAudio && audioEncoder != null) {
            audioEncoder.start();
            audioRecord.startRecording();
        }
        
        // Create VirtualDisplay
        createVirtualDisplay();
        
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
        
        // Log.d(TAG, "Screen recording started");
    }
    
    /**
     * Create VirtualDisplay for screen capture
     */
    private void createVirtualDisplay() {
        // For now, render directly to encoder surface
        // Watermarking will be added later with proper GL pipeline
        Surface targetSurface = encoderInputSurface;
        
        virtualDisplay = mediaProjection.createVirtualDisplay(
            "ScreenRecording",
            screenWidth,
            screenHeight,
            screenDensity,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            targetSurface,
            null,
            null
        );
        
        // Log.d(TAG, "VirtualDisplay created: " + screenWidth + "x" + screenHeight);
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
     * Drain video encoder output
     */
    private void drainVideoEncoder(boolean endOfStream) {
        if (endOfStream) {
            videoEncoder.signalEndOfInputStream();
        }
        
        MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
        
        while (true) {
            int outputBufferIndex = videoEncoder.dequeueOutputBuffer(bufferInfo, 0);
            
            if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                // Muxer setup
                MediaFormat newFormat = videoEncoder.getOutputFormat();
                videoTrackIndex = mediaMuxer.addTrack(newFormat);
                // Log.d(TAG, "Video track added: " + videoTrackIndex);
                
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
                }
                
                videoEncoder.releaseOutputBuffer(outputBufferIndex, false);
                
                if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    break;
                }
            } else {
                break;
            }
        }
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
                    audioBuffer.limit(bytesRead);
                    queueAudioData(audioBuffer, bytesRead);
                }
            }
        });
        audioRecordingThread.start();
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
                audioTrackIndex = mediaMuxer.addTrack(newFormat);
                // Log.d(TAG, "Audio track added: " + audioTrackIndex);
                
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
     * Try to start muxer when all tracks are ready
     */
    private void tryStartMuxer() {
        boolean videoReady = videoTrackIndex >= 0;
        boolean audioReady = !enableAudio || audioTrackIndex >= 0;
        
        if (!muxerStarted && videoReady && audioReady) {
            mediaMuxer.start();
            muxerStarted = true;
            // Log.d(TAG, "Muxer started");
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
        // Log.d(TAG, "Recording paused");
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
        Log.d(TAG, "Recording resumed");
    }
    
    /**
     * Stop recording and release resources
     */
    public void stopRecording() {
        if (isStopped) {
            return;
        }
        
        Log.d(TAG, "Stopping screen recording");
        isStopped = true;
        isRecording = false;
        
        // Stop audio recording
        if (audioRecord != null) {
            try {
                audioRecord.stop();
            } catch (Exception e) {
                Log.e(TAG, "Error stopping AudioRecord", e);
            }
        }
        
        // Drain encoders with EOS
        drainVideoEncoder(true);
        if (enableAudio && audioEncoder != null) {
            drainAudioEncoder(true);
        }
        
        // Release resources
        release();
        
        Log.d(TAG, "Screen recording stopped");
    }
    
    /**
     * Release all resources
     */
    public void release() {
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
                Log.e(TAG, "Error releasing video encoder", e);
            }
            videoEncoder = null;
        }
        
        // Release audio encoder
        if (audioEncoder != null) {
            try {
                audioEncoder.stop();
                audioEncoder.release();
            } catch (Exception e) {
                Log.e(TAG, "Error releasing audio encoder", e);
            }
            audioEncoder = null;
        }
        
        // Release AudioRecord
        if (audioRecord != null) {
            try {
                audioRecord.release();
            } catch (Exception e) {
                Log.e(TAG, "Error releasing AudioRecord", e);
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
                Log.e(TAG, "Error releasing muxer", e);
            }
            mediaMuxer = null;
        }
        
        // Release watermark renderer
        if (glWatermarkRenderer != null) {
            glWatermarkRenderer.release();
            glWatermarkRenderer = null;
        }
        
        // Stop threads
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
