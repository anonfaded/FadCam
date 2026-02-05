package com.fadcam.dualcam.pipeline;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.view.Surface;

import androidx.annotation.NonNull;

import com.fadcam.SharedPreferencesManager;
import com.fadcam.VideoCodec;
import com.fadcam.dualcam.DualCameraConfig;
import com.fadcam.media.FragmentedMp4MuxerWrapper;

import java.io.IOException;

/**
 * Dual-camera encoding pipeline: receives frames from two cameras via
 * {@link DualCameraCompositor}, composites them into a single PiP frame,
 * and encodes to a single MP4 file.
 *
 * <h3>Architecture</h3>
 * <pre>
 * Camera A → SurfaceTexture A ─┐
 *                                ├─→ DualCameraCompositor (GL) → encoderInputSurface → MediaCodec → Muxer → MP4
 * Camera B → SurfaceTexture B ─┘
 * </pre>
 *
 * <p>Audio is captured via a dedicated AudioRecord → AudioEncoder thread,
 * similar to {@link com.fadcam.opengl.GLRecordingPipeline}.
 */
public class DualCameraPipeline {

    private static final String TAG = "DualCamPipeline";
    private static final int VIDEO_IFRAME_INTERVAL = 1; // Keyframe every 1 second

    // ── Context ────────────────────────────────────────────────────────
    private final Context context;
    private final int videoWidth;
    private final int videoHeight;
    private final int videoFramerate;
    private final String outputFilePath;
    private final String orientation;
    private final int sensorOrientation;
    private final VideoCodec videoCodec;
    private final DualCameraConfig config;

    // ── Encoding ───────────────────────────────────────────────────────
    private MediaCodec videoEncoder;
    private Surface encoderInputSurface;
    private FragmentedMp4MuxerWrapper mediaMuxer;
    private int videoTrackIndex = -1;
    private boolean muxerStarted = false;

    // ── Audio ──────────────────────────────────────────────────────────
    private android.media.AudioRecord audioRecord;
    private MediaCodec audioEncoder;
    private Thread audioThread;
    private int audioTrackIndex = -1;
    private volatile boolean audioThreadRunning = false;
    private boolean audioRecordingEnabled = false;
    private int audioSource;
    private int audioSampleRate;
    private int audioBitrate;
    private int audioChannelCount;

    // ── Compositor ─────────────────────────────────────────────────────
    private DualCameraCompositor compositor;

    // ── Render thread ──────────────────────────────────────────────────
    private HandlerThread renderThread;
    private Handler renderHandler;
    private volatile boolean isRecording = false;
    private volatile boolean isPaused = false;
    private volatile boolean isStopped = false;

    // ── Timestamp tracking ─────────────────────────────────────────────
    private long recordingStartSystemTimeNanos = -1;
    private long pauseStartTimeNanos = -1;
    private long totalPauseDurationNanos = 0;
    private final Object timestampLock = new Object();

    // ── Debug counters ─────────────────────────────────────────────────
    private long videoSamplesWritten = 0;
    private long audioSamplesWritten = 0;

    // ════════════════════════════════════════════════════════════════════
    // CONSTRUCTION
    // ════════════════════════════════════════════════════════════════════

    /**
     * @param context           Application context.
     * @param videoWidth        Output video width.
     * @param videoHeight       Output video height.
     * @param videoFramerate    Target frame rate.
     * @param outputFilePath    Absolute path to the output MP4.
     * @param orientation       "portrait" or "landscape".
     * @param sensorOrientation Sensor orientation degrees.
     * @param videoCodec        AVC or HEVC.
     * @param config            PiP configuration.
     */
    public DualCameraPipeline(
            @NonNull Context context,
            int videoWidth, int videoHeight,
            int videoFramerate,
            @NonNull String outputFilePath,
            @NonNull String orientation,
            int sensorOrientation,
            @NonNull VideoCodec videoCodec,
            @NonNull DualCameraConfig config) {

        this.context = context.getApplicationContext();
        this.videoWidth = videoWidth;
        this.videoHeight = videoHeight;
        this.videoFramerate = videoFramerate;
        this.outputFilePath = outputFilePath;
        this.orientation = orientation;
        this.sensorOrientation = sensorOrientation;
        this.videoCodec = videoCodec;
        this.config = config;

        initAudioSettings();
    }

    // ════════════════════════════════════════════════════════════════════
    // PUBLIC API
    // ════════════════════════════════════════════════════════════════════

    /**
     * Prepares the video encoder, compositor, and camera input surfaces.
     * Call before starting camera capture sessions.
     */
    public void prepareSurfaces() {
        Log.d(TAG, "prepareSurfaces()");

        // 1. Create video encoder → get encoderInputSurface
        setupVideoEncoder();

        // 2. Create compositor (takes encoderInputSurface, creates 2 camera surfaces)
        compositor = new DualCameraCompositor(
                encoderInputSurface, videoWidth, videoHeight, config);

        // 3. Start render thread
        renderThread = new HandlerThread("DualCamRender");
        renderThread.start();
        renderHandler = new Handler(renderThread.getLooper());

        Log.d(TAG, "Surfaces prepared — primary and secondary camera surfaces ready");
    }

    /** Surface the primary camera should target. */
    @NonNull
    public Surface getPrimaryCameraInputSurface() {
        if (compositor == null) throw new IllegalStateException("Call prepareSurfaces() first");
        return compositor.getPrimaryInputSurface();
    }

    /** Surface the secondary camera should target. */
    @NonNull
    public Surface getSecondaryCameraInputSurface() {
        if (compositor == null) throw new IllegalStateException("Call prepareSurfaces() first");
        return compositor.getSecondaryInputSurface();
    }

    /**
     * Start encoding and the render loop.
     * Call after both camera capture sessions are configured.
     */
    public void startRecording() {
        if (isRecording || isStopped) {
            Log.w(TAG, "Cannot start recording — isRecording=" + isRecording + ", isStopped=" + isStopped);
            return;
        }

        Log.d(TAG, "Starting dual camera recording");

        isRecording = true;
        recordingStartSystemTimeNanos = System.nanoTime();

        // Set up audio
        initAudioSettings();
        setupAudio();
        if (audioRecordingEnabled) {
            startAudioThread();
        }

        // Set up muxer
        setupMuxer();

        // Start render loop
        renderHandler.post(this::renderLoop);

        Log.i(TAG, "✅ Dual camera pipeline recording started");
    }

    /** Stop recording, drain encoders, finalise muxer, release resources. */
    public void stopRecording() {
        if (isStopped) {
            Log.d(TAG, "Already stopped");
            return;
        }
        Log.d(TAG, "Stopping dual camera pipeline");

        isStopped = true;
        isRecording = false;

        // Stop render loop
        if (renderHandler != null) {
            renderHandler.removeCallbacksAndMessages(null);
        }

        // Stop audio
        stopAudioThread();

        // Drain and stop video encoder
        drainVideoEncoder(true /* endOfStream */);

        if (videoEncoder != null) {
            try {
                videoEncoder.stop();
                videoEncoder.release();
            } catch (Exception e) {
                Log.e(TAG, "Error stopping video encoder", e);
            }
            videoEncoder = null;
        }

        // Stop audio encoder
        if (audioEncoder != null) {
            try {
                audioEncoder.stop();
                audioEncoder.release();
            } catch (Exception e) {
                Log.e(TAG, "Error stopping audio encoder", e);
            }
            audioEncoder = null;
        }

        // Stop muxer
        if (mediaMuxer != null && muxerStarted) {
            try {
                mediaMuxer.stop();
                mediaMuxer.release();
            } catch (Exception e) {
                Log.e(TAG, "Error stopping muxer", e);
            }
        }
        mediaMuxer = null;
        muxerStarted = false;

        // Release compositor
        if (compositor != null) {
            try {
                // Must release on GL thread (render thread)
                compositor.release();
            } catch (Exception e) {
                Log.e(TAG, "Error releasing compositor", e);
            }
            compositor = null;
        }

        // Release encoder surface
        if (encoderInputSurface != null) {
            try { encoderInputSurface.release(); } catch (Exception ignored) { }
            encoderInputSurface = null;
        }

        // Stop render thread
        if (renderThread != null) {
            renderThread.quitSafely();
            try { renderThread.join(2000); } catch (InterruptedException ignored) { }
            renderThread = null;
            renderHandler = null;
        }

        // Release audio record
        if (audioRecord != null) {
            try {
                if (audioRecord.getRecordingState() == android.media.AudioRecord.RECORDSTATE_RECORDING) {
                    audioRecord.stop();
                }
                audioRecord.release();
            } catch (Exception ignored) { }
            audioRecord = null;
        }

        Log.i(TAG, "Dual camera pipeline stopped. Video samples=" + videoSamplesWritten
                + ", Audio samples=" + audioSamplesWritten);
    }

    /** Pause recording (freeze encoding, keep cameras open). */
    public void pauseRecording() {
        if (!isRecording || isStopped) return;

        synchronized (timestampLock) {
            pauseStartTimeNanos = System.nanoTime();
            isPaused = true;
        }
        isRecording = false;

        // Pause audio
        if (audioRecordingEnabled && audioRecord != null) {
            try {
                if (audioRecord.getRecordingState() == android.media.AudioRecord.RECORDSTATE_RECORDING) {
                    audioRecord.stop();
                }
            } catch (Exception e) {
                Log.w(TAG, "Error pausing audio", e);
            }
        }

        Log.i(TAG, "Pipeline paused");
    }

    /** Resume recording. */
    public void resumeRecording() {
        if (isRecording || isStopped) return;

        synchronized (timestampLock) {
            if (pauseStartTimeNanos > 0) {
                totalPauseDurationNanos += (System.nanoTime() - pauseStartTimeNanos);
                pauseStartTimeNanos = -1;
            }
            isPaused = false;
        }
        isRecording = true;

        // Resume audio
        if (audioRecordingEnabled && audioRecord != null) {
            try {
                audioRecord.startRecording();
            } catch (Exception e) {
                Log.w(TAG, "Error resuming audio", e);
            }
        }

        // Restart render loop
        if (renderHandler != null) {
            renderHandler.post(this::renderLoop);
        }

        Log.i(TAG, "Pipeline resumed");
    }

    /** Swap primary ↔ secondary rendering in the compositor. */
    public void swapCameras() {
        if (compositor != null) {
            compositor.swapCameras();
        }
    }

    /** Live-update PiP layout. */
    public void updateConfig(@NonNull DualCameraConfig newConfig) {
        if (compositor != null) {
            compositor.updateConfig(newConfig);
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // VIDEO ENCODER
    // ════════════════════════════════════════════════════════════════════

    private void setupVideoEncoder() {
        String mimeType = (videoCodec == VideoCodec.HEVC)
                ? MediaFormat.MIMETYPE_VIDEO_HEVC
                : MediaFormat.MIMETYPE_VIDEO_AVC;

        int bitrate = SharedPreferencesManager.getInstance(context).getCurrentBitrate();

        MediaFormat format = MediaFormat.createVideoFormat(mimeType, videoWidth, videoHeight);
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        format.setInteger(MediaFormat.KEY_BIT_RATE, bitrate);
        format.setInteger(MediaFormat.KEY_FRAME_RATE, videoFramerate);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, VIDEO_IFRAME_INTERVAL);

        try {
            videoEncoder = MediaCodec.createEncoderByType(mimeType);
            videoEncoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            encoderInputSurface = videoEncoder.createInputSurface();
            videoEncoder.start();
            Log.d(TAG, "Video encoder started: " + mimeType + " " + videoWidth + "x" + videoHeight
                    + " @" + bitrate + "bps, " + videoFramerate + "fps");
        } catch (IOException e) {
            throw new RuntimeException("Failed to create video encoder", e);
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // MUXER
    // ════════════════════════════════════════════════════════════════════

    private void setupMuxer() {
        try {
            mediaMuxer = new FragmentedMp4MuxerWrapper(outputFilePath);
            Log.d(TAG, "Muxer created: " + outputFilePath);
        } catch (Exception e) {
            throw new RuntimeException("Failed to create muxer", e);
        }
    }

    private synchronized void startMuxerIfReady() {
        if (muxerStarted) return;
        if (mediaMuxer == null) return;
        if (videoTrackIndex == -1) return;
        // Start with or without audio (audio may join later)

        try {
            mediaMuxer.start();
            muxerStarted = true;
            Log.d(TAG, "Muxer started. Video track=" + videoTrackIndex
                    + ", Audio track=" + audioTrackIndex);
        } catch (Exception e) {
            Log.e(TAG, "Failed to start muxer", e);
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // RENDER LOOP
    // ════════════════════════════════════════════════════════════════════

    /**
     * Render loop running on the dedicated render thread.
     * Composites camera frames → encoder → drain → muxer.
     */
    private void renderLoop() {
        if (!isRecording || isStopped) return;

        try {
            // Render composited frame
            if (compositor != null) {
                compositor.renderFrame();
            }

            // Drain video encoder
            drainVideoEncoder(false);

        } catch (Exception e) {
            Log.e(TAG, "Render loop error", e);
        }

        // Schedule next frame
        if (isRecording && !isStopped && renderHandler != null) {
            long frameIntervalMs = Math.max(1, 1000 / videoFramerate);
            renderHandler.postDelayed(this::renderLoop, frameIntervalMs);
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // ENCODER DRAIN
    // ════════════════════════════════════════════════════════════════════

    private void drainVideoEncoder(boolean endOfStream) {
        if (videoEncoder == null) return;

        if (endOfStream) {
            try {
                videoEncoder.signalEndOfInputStream();
            } catch (Exception e) {
                Log.w(TAG, "Error signaling EOS to video encoder", e);
            }
        }

        MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
        int maxDrainIterations = endOfStream ? 100 : 10;

        for (int i = 0; i < maxDrainIterations; i++) {
            int outputIndex = videoEncoder.dequeueOutputBuffer(bufferInfo, endOfStream ? 10000 : 0);

            if (outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (endOfStream) continue;
                break;
            } else if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                if (mediaMuxer != null && videoTrackIndex == -1) {
                    videoTrackIndex = mediaMuxer.addTrack(videoEncoder.getOutputFormat());
                    Log.d(TAG, "Video track added: " + videoTrackIndex);
                    startMuxerIfReady();
                }
            } else if (outputIndex >= 0) {
                java.nio.ByteBuffer outputBuffer = videoEncoder.getOutputBuffer(outputIndex);
                if (outputBuffer == null) {
                    videoEncoder.releaseOutputBuffer(outputIndex, false);
                    continue;
                }

                if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                    bufferInfo.size = 0;
                }

                if (bufferInfo.size > 0 && muxerStarted && videoTrackIndex >= 0) {
                    // Calculate PTS
                    long ptsUs = getSynchronizedVideoTimestamp();
                    if (ptsUs >= 0) {
                        bufferInfo.presentationTimeUs = ptsUs;
                        mediaMuxer.writeSampleData(videoTrackIndex, outputBuffer, bufferInfo);
                        videoSamplesWritten++;
                    }
                }

                videoEncoder.releaseOutputBuffer(outputIndex, false);

                if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    Log.d(TAG, "Video encoder EOS received");
                    break;
                }
            }
        }
    }

    private long getSynchronizedVideoTimestamp() {
        if (isPaused) return -1;

        synchronized (timestampLock) {
            if (recordingStartSystemTimeNanos == -1) {
                recordingStartSystemTimeNanos = System.nanoTime();
            }
            long elapsedNanos = System.nanoTime() - recordingStartSystemTimeNanos - totalPauseDurationNanos;
            return Math.max(0, elapsedNanos / 1000L); // Convert to microseconds
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // AUDIO
    // ════════════════════════════════════════════════════════════════════

    private void initAudioSettings() {
        SharedPreferencesManager prefs = SharedPreferencesManager.getInstance(context);
        audioRecordingEnabled = prefs.isRecordAudioEnabled();
        audioSource = android.media.MediaRecorder.AudioSource.CAMCORDER;
        audioSampleRate = 44100;
        audioBitrate = 128000;
        audioChannelCount = 1;
    }

    private void setupAudio() {
        if (!audioRecordingEnabled) {
            Log.d(TAG, "Audio recording disabled");
            return;
        }

        try {
            int channelConfig = (audioChannelCount == 2)
                    ? android.media.AudioFormat.CHANNEL_IN_STEREO
                    : android.media.AudioFormat.CHANNEL_IN_MONO;

            int minBufferSize = android.media.AudioRecord.getMinBufferSize(
                    audioSampleRate, channelConfig, android.media.AudioFormat.ENCODING_PCM_16BIT);
            int bufferSize = Math.max(minBufferSize * 4, 8192);

            audioRecord = new android.media.AudioRecord(
                    audioSource, audioSampleRate, channelConfig,
                    android.media.AudioFormat.ENCODING_PCM_16BIT, bufferSize);

            if (audioRecord.getState() != android.media.AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord failed to initialise");
                audioRecordingEnabled = false;
                audioRecord = null;
                return;
            }

            // Set up audio encoder
            MediaFormat audioFormat = MediaFormat.createAudioFormat(
                    MediaFormat.MIMETYPE_AUDIO_AAC, audioSampleRate, audioChannelCount);
            audioFormat.setInteger(MediaFormat.KEY_AAC_PROFILE,
                    MediaCodecInfo.CodecProfileLevel.AACObjectLC);
            audioFormat.setInteger(MediaFormat.KEY_BIT_RATE, audioBitrate);
            audioFormat.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, bufferSize);

            audioEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
            audioEncoder.configure(audioFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            audioEncoder.start();

            audioRecord.startRecording();
            Log.d(TAG, "Audio recording initialised: " + audioSampleRate + "Hz, "
                    + audioChannelCount + "ch, " + audioBitrate + "bps");

        } catch (Exception e) {
            Log.e(TAG, "Failed to set up audio", e);
            audioRecordingEnabled = false;
            if (audioRecord != null) {
                try { audioRecord.release(); } catch (Exception ignored) { }
                audioRecord = null;
            }
            if (audioEncoder != null) {
                try { audioEncoder.release(); } catch (Exception ignored) { }
                audioEncoder = null;
            }
        }
    }

    private void startAudioThread() {
        if (!audioRecordingEnabled || audioRecord == null || audioEncoder == null) return;

        audioThreadRunning = true;
        audioThread = new Thread(() -> {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO);
            byte[] buffer = new byte[4096];

            while (audioThreadRunning && !isStopped) {
                if (isPaused) {
                    try { Thread.sleep(50); } catch (InterruptedException ignored) { }
                    continue;
                }

                int bytesRead = audioRecord.read(buffer, 0, buffer.length);
                if (bytesRead > 0) {
                    feedAudioEncoder(buffer, bytesRead);
                    drainAudioEncoder();
                }
            }

            // Signal EOS
            feedAudioEncoderEOS();
            drainAudioEncoder(); // Final drain

            Log.d(TAG, "Audio thread exited. Samples written=" + audioSamplesWritten);
        }, "DualCamAudio");
        audioThread.start();
    }

    private void stopAudioThread() {
        audioThreadRunning = false;
        if (audioThread != null) {
            try { audioThread.join(2000); } catch (InterruptedException ignored) { }
            audioThread = null;
        }
    }

    private void feedAudioEncoder(byte[] data, int length) {
        if (audioEncoder == null) return;
        try {
            int inputIndex = audioEncoder.dequeueInputBuffer(5000);
            if (inputIndex >= 0) {
                java.nio.ByteBuffer inputBuffer = audioEncoder.getInputBuffer(inputIndex);
                if (inputBuffer != null) {
                    inputBuffer.clear();
                    inputBuffer.put(data, 0, length);

                    long ptsUs = getSynchronizedVideoTimestamp();
                    if (ptsUs < 0) ptsUs = 0;

                    audioEncoder.queueInputBuffer(inputIndex, 0, length, ptsUs, 0);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Error feeding audio encoder", e);
        }
    }

    private void feedAudioEncoderEOS() {
        if (audioEncoder == null) return;
        try {
            int inputIndex = audioEncoder.dequeueInputBuffer(5000);
            if (inputIndex >= 0) {
                audioEncoder.queueInputBuffer(inputIndex, 0, 0, 0,
                        MediaCodec.BUFFER_FLAG_END_OF_STREAM);
            }
        } catch (Exception e) {
            Log.w(TAG, "Error signaling audio EOS", e);
        }
    }

    private void drainAudioEncoder() {
        if (audioEncoder == null) return;

        MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();

        while (true) {
            int outputIndex = audioEncoder.dequeueOutputBuffer(bufferInfo, 0);

            if (outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                break;
            } else if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                if (mediaMuxer != null && audioTrackIndex == -1) {
                    audioTrackIndex = mediaMuxer.addTrack(audioEncoder.getOutputFormat());
                    Log.d(TAG, "Audio track added: " + audioTrackIndex);
                    startMuxerIfReady();
                }
            } else if (outputIndex >= 0) {
                java.nio.ByteBuffer outputBuffer = audioEncoder.getOutputBuffer(outputIndex);

                if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                    bufferInfo.size = 0;
                }

                if (bufferInfo.size > 0 && muxerStarted && audioTrackIndex >= 0 && outputBuffer != null) {
                    mediaMuxer.writeSampleData(audioTrackIndex, outputBuffer, bufferInfo);
                    audioSamplesWritten++;
                }

                audioEncoder.releaseOutputBuffer(outputIndex, false);

                if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    Log.d(TAG, "Audio encoder EOS received");
                    break;
                }
            }
        }
    }
}
