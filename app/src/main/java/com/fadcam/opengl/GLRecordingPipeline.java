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

/**
 * GLRecordingPipeline manages the OpenGL pipeline for real-time watermarking and video encoding.
 * It connects Camera2 output to GLWatermarkRenderer and encodes the result to a file.
 */
public class GLRecordingPipeline {
    private static final String TAG = "GLRecordingPipeline";
    private static final String VIDEO_MIME_TYPE = "video/avc";
    private static final int VIDEO_IFRAME_INTERVAL = 1;

    private final Context context;
    private final WatermarkInfoProvider watermarkInfoProvider;
    private GLWatermarkRenderer glRenderer;
    private MediaCodec videoEncoder;
    private MediaMuxer mediaMuxer;
    private Surface encoderInputSurface;
    private Surface cameraInputSurface;
    private boolean isRecording = false;
    private HandlerThread renderThread;
    private Handler renderHandler;
    private final int videoWidth;
    private final int videoHeight;
    private final int videoBitrate;
    private final int videoFramerate;
    private final String outputFilePath;
    private final FileDescriptor outputFd;
    private Surface previewSurface;
    private final String orientation;
    private final int sensorOrientation;

    // Callback interface for segment rollover
    public interface SegmentCallback {
        /**
         * Called when a new segment is needed. Should provide a new file path or FileDescriptor.
         * @param nextSegmentNumber The next segment number (1-based)
         */
        void onSegmentRollover(int nextSegmentNumber);
    }

    private long maxFileSizeBytes = Long.MAX_VALUE;
    private int segmentNumber = 1;
    private SegmentCallback segmentCallback;
    private String currentOutputFilePath;
    private FileDescriptor currentOutputFd;
    private boolean muxerStarted = false;
    private boolean isStopped = false;

    // Updated constructor for file path (internal storage)
    public GLRecordingPipeline(Context context, WatermarkInfoProvider watermarkInfoProvider, int videoWidth, int videoHeight, int videoBitrate, int videoFramerate, String outputFilePath, long maxFileSizeBytes, int segmentNumber, SegmentCallback segmentCallback, Surface previewSurface, String orientation, int sensorOrientation) {
        this(context, watermarkInfoProvider, videoWidth, videoHeight, videoBitrate, videoFramerate, outputFilePath, maxFileSizeBytes, segmentNumber, segmentCallback, orientation, sensorOrientation);
        this.previewSurface = previewSurface;
    }

    // Updated constructor for FileDescriptor (SAF)
    public GLRecordingPipeline(Context context, WatermarkInfoProvider watermarkInfoProvider, int videoWidth, int videoHeight, int videoBitrate, int videoFramerate, FileDescriptor outputFd, long maxFileSizeBytes, int segmentNumber, SegmentCallback segmentCallback, Surface previewSurface, String orientation, int sensorOrientation) {
        this(context, watermarkInfoProvider, videoWidth, videoHeight, videoBitrate, videoFramerate, outputFd, maxFileSizeBytes, segmentNumber, segmentCallback, orientation, sensorOrientation);
        this.previewSurface = previewSurface;
    }

    // Updated constructor for file path (internal storage)
    public GLRecordingPipeline(Context context, WatermarkInfoProvider watermarkInfoProvider, int videoWidth, int videoHeight, int videoBitrate, int videoFramerate, String outputFilePath, long maxFileSizeBytes, int segmentNumber, SegmentCallback segmentCallback, String orientation, int sensorOrientation) {
        this.context = context;
        this.watermarkInfoProvider = watermarkInfoProvider;
        this.videoWidth = videoWidth;
        this.videoHeight = videoHeight;
        this.videoBitrate = videoBitrate;
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
    }

    // Updated constructor for FileDescriptor (SAF)
    public GLRecordingPipeline(Context context, WatermarkInfoProvider watermarkInfoProvider, int videoWidth, int videoHeight, int videoBitrate, int videoFramerate, FileDescriptor outputFd, long maxFileSizeBytes, int segmentNumber, SegmentCallback segmentCallback, String orientation, int sensorOrientation) {
        this.context = context;
        this.watermarkInfoProvider = watermarkInfoProvider;
        this.videoWidth = videoWidth;
        this.videoHeight = videoHeight;
        this.videoBitrate = videoBitrate;
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
    }

    /**
     * Prepares the GL renderer and camera input surface for the camera session.
     * Call this after constructing the pipeline, before creating the camera session.
     * Does NOT start encoding or the render loop.
     */
    public void prepareSurfaces() {
        try {
            if (encoderInputSurface == null) {
                setupEncoder();
            }
            if (glRenderer == null) {
                glRenderer = new GLWatermarkRenderer(context, encoderInputSurface, orientation, sensorOrientation, videoWidth, videoHeight);
                cameraInputSurface = glRenderer.getCameraInputSurface();
                glRenderer.setOnFrameAvailableListener(new GLWatermarkRenderer.OnFrameAvailableListener() {
                    @Override
                    public void onFrameAvailable() {
                        if (isRecording && renderHandler != null) {
                            renderHandler.post(renderRunnable);
                        }
                    }
                });
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to prepare GL surfaces", e);
        }
    }

    /**
     * Starts the recording pipeline.
     */
    public void startRecording() {
        try {
            if (!isRecording) {
                isRecording = true;
                startRenderLoop();
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to start recording pipeline", e);
            stopRecording();
        }
    }

    private void setupEncoder() throws IOException {
        MediaFormat format = MediaFormat.createVideoFormat(VIDEO_MIME_TYPE, videoWidth, videoHeight);
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        format.setInteger(MediaFormat.KEY_BIT_RATE, videoBitrate);
        format.setInteger(MediaFormat.KEY_FRAME_RATE, videoFramerate);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, VIDEO_IFRAME_INTERVAL);
        videoEncoder = MediaCodec.createEncoderByType(VIDEO_MIME_TYPE);
        videoEncoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        encoderInputSurface = videoEncoder.createInputSurface();
        videoEncoder.start();
        if (currentOutputFd != null) {
            mediaMuxer = new MediaMuxer(currentOutputFd, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        } else {
            mediaMuxer = new MediaMuxer(currentOutputFilePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        }
        muxerStarted = false;
    }

    private void startRenderLoop() {
        renderThread = new HandlerThread("GLRenderThread");
        renderThread.start();
        renderHandler = new Handler(renderThread.getLooper());
        renderHandler.post(renderRunnable);
    }

    // Only render when a new frame is available (signaled by renderer)
    private final Runnable renderRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isRecording || glRenderer == null || videoEncoder == null) return;
            glRenderer.renderFrame();
            drainEncoder();
            // The renderer's OnFrameAvailableListener will signal when to post again
            if (isRecording) {
                // No unconditional postDelayed; the renderer will notify when ready
            }
        }
    };

    private void drainEncoder() {
        if (videoEncoder == null || (mediaMuxer == null && !muxerStarted)) return;
        MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
        try {
            while (true) {
                int outputBufferIndex = videoEncoder.dequeueOutputBuffer(bufferInfo, 0);
                if (outputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    break;
                } else if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    if (muxerStarted) {
                        throw new IllegalStateException("Format changed after muxer started");
                    }
                    MediaFormat newFormat = videoEncoder.getOutputFormat();
                    int videoTrackIndex = mediaMuxer.addTrack(newFormat);
                    mediaMuxer.start();
                    muxerStarted = true;
                } else if (outputBufferIndex >= 0) {
                    ByteBuffer encodedData = videoEncoder.getOutputBuffer(outputBufferIndex);
                    if (encodedData == null) {
                        throw new RuntimeException("encoderOutputBuffer " + outputBufferIndex + " was null");
                    }
                    if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                        bufferInfo.size = 0;
                    }
                    if (bufferInfo.size != 0 && muxerStarted) {
                        encodedData.position(bufferInfo.offset);
                        encodedData.limit(bufferInfo.offset + bufferInfo.size);
                        mediaMuxer.writeSampleData(0, encodedData, bufferInfo);
                    }
                    videoEncoder.releaseOutputBuffer(outputBufferIndex, false);
                    if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        break;
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error draining encoder", e);
        }
    }

    private boolean shouldSplitSegment() {
        try {
            if (currentOutputFilePath != null) {
                File f = new File(currentOutputFilePath);
                return f.exists() && f.length() >= maxFileSizeBytes;
            } else if (currentOutputFd != null) {
                // For SAF, try to get file size via FileDescriptor (not always possible)
                // This is a best-effort; may not work on all devices/URIs
                try {
                    RandomAccessFile raf = new RandomAccessFile("/proc/self/fd/" + currentOutputFd, "r");
                    long size = raf.length();
                    raf.close();
                    return size >= maxFileSizeBytes;
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
            // Stop and release current muxer
            if (mediaMuxer != null) {
                try {
                    if (muxerStarted) {
                        mediaMuxer.stop();
                    }
                    mediaMuxer.release();
                } catch (Exception e) {
                    Log.e(TAG, "Error releasing muxer during rollover", e);
                }
                mediaMuxer = null;
            }
            segmentNumber++;
            // Request next file/descriptor from callback
            if (segmentCallback != null) {
                segmentCallback.onSegmentRollover(segmentNumber);
            }
            // The callback should set currentOutputFilePath/currentOutputFd to the new output
            setupEncoder();
            Log.i(TAG, "Started new segment: " + segmentNumber);
        } catch (Exception e) {
            Log.e(TAG, "Error during segment rollover", e);
            stopRecording();
        }
    }

    /**
     * Stops and releases all resources for the recording pipeline.
     */
    public void stopRecording() {
        if (isStopped) return;
        isStopped = true;
        isRecording = false;
        if (renderHandler != null) {
            renderHandler.removeCallbacksAndMessages(null);
        }
        if (renderThread != null) {
            renderThread.quitSafely();
            renderThread = null;
        }
        if (glRenderer != null) {
            glRenderer.release();
            glRenderer = null;
        }
        if (videoEncoder != null) {
            try {
                videoEncoder.signalEndOfInputStream();
            } catch (Exception e) {
                Log.e(TAG, "Error signaling end of input stream", e);
            }
            try {
                videoEncoder.stop();
            } catch (Exception e) {
                Log.e(TAG, "Error stopping video encoder", e);
            }
            try {
                videoEncoder.release();
            } catch (Exception e) {
                Log.e(TAG, "Error releasing video encoder", e);
            }
            videoEncoder = null;
        }
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
            muxerStarted = false;
        }
    }

    /**
     * Updates the watermark text in real time.
     */
    public void updateWatermark() {
        if (glRenderer != null) {
            glRenderer.setWatermarkText(watermarkInfoProvider.getWatermarkText());
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
        // No-op: Not implemented. Add logic if you want to support pause/resume.
    }

    /**
     * Resumes the recording pipeline (no-op, for API compatibility).
     */
    public void resumeRecording() {
        // No-op: Not implemented. Add logic if you want to support pause/resume.
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

    // Allow updating preview surface at runtime
    public void setPreviewSurface(Surface previewSurface) {
        this.previewSurface = previewSurface;
        if (glRenderer != null) {
            glRenderer.setPreviewSurface(previewSurface);
        }
    }

    public String getOrientation() {
        return orientation;
    }

    public int getSensorOrientation() {
        return sensorOrientation;
    }
} 