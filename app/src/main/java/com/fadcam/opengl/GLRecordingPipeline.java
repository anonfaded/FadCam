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
    private HandlerThread renderThread;
    private Handler handler;
    private final int videoWidth;
    private final int videoHeight;
    private final int videoBitrate;
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

    private long maxFileSizeBytes = Long.MAX_VALUE;
    private int segmentNumber = 1;
    private SegmentCallback segmentCallback;
    private String currentOutputFilePath;
    private FileDescriptor currentOutputFd;
    private boolean muxerStarted = false;
    private boolean isStopped = false;

    private HandlerThread previewRenderThread;
    private Handler previewRenderHandler;
    private volatile boolean isPreviewActive = false;
    private final Object previewStateLock = new Object();

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
        
        // Initialize surface dimensions with video dimensions as default
        this.mSurfaceWidth = videoWidth;
        this.mSurfaceHeight = videoHeight;
        
        // Calculate target aspect ratio based on the fixed dimensions
        this.targetAspectRatio = (float) videoWidth / videoHeight;
        
        Log.d(TAG, "GLRecordingPipeline initialized with fixed dimensions: " + 
              videoWidth + "x" + videoHeight + " in " + orientation + 
              " orientation (sensor=" + sensorOrientation + "), aspect ratio: " + targetAspectRatio);
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
        
        // Initialize surface dimensions with video dimensions as default
        this.mSurfaceWidth = videoWidth;
        this.mSurfaceHeight = videoHeight;
        
        // Calculate target aspect ratio based on the fixed dimensions
        this.targetAspectRatio = (float) videoWidth / videoHeight;
        
        Log.d(TAG, "GLRecordingPipeline initialized with fixed dimensions: " + 
              videoWidth + "x" + videoHeight + " in " + orientation + 
              " orientation (sensor=" + sensorOrientation + "), aspect ratio: " + targetAspectRatio);
    }

    /**
     * Prepares the GL renderer and camera input surface for the camera session.
     * Call this after constructing the pipeline, before creating the camera session.
     * Does NOT start encoding or the render loop.
     */
    public void prepareSurfaces() {
        try {
            // Make sure any previous resources are fully released
            if (glRenderer != null) {
                Log.d(TAG, "Releasing previous renderer before preparing new surfaces");
                try {
                    glRenderer.release();
                } catch (Exception e) {
                    Log.e(TAG, "Error releasing previous renderer", e);
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
                
                // Initialize the renderer's EGL context
                Log.d(TAG, "Initializing renderer EGL context");
                glRenderer.initializeEGL();
                
                // Allow time for the renderer to initialize completely before getting camera surface
                try {
                    Log.d(TAG, "Waiting for GL resources to initialize");
                    Thread.sleep(300); // Increased delay to ensure texture is initialized
                } catch (InterruptedException e) {
                    // Ignore
                }
                
                // Get the camera input surface
                Log.d(TAG, "Requesting camera input surface from renderer");
                cameraInputSurface = glRenderer.getCameraInputSurface();
                
                if (cameraInputSurface == null) {
                    Log.e(TAG, "Failed to get camera input surface - texture may not be initialized");
                    // Try to check GL errors from renderer if possible
                    throw new RuntimeException("Failed to get camera input surface");
                }
                
                if (!cameraInputSurface.isValid()) {
                    Log.e(TAG, "Camera input surface is not valid");
                    throw new RuntimeException("Camera input surface is not valid");
                }
                
                Log.d(TAG, "Successfully obtained valid camera input surface");
                
                // If we have a preview surface, set it on the renderer
                if (previewSurface != null && previewSurface.isValid()) {
                    Log.d(TAG, "Setting preview surface during prepareSurfaces");
                    glRenderer.setPreviewSurface(previewSurface);
                    
                    // Wait a bit longer to ensure the preview surface is fully initialized
                    try {
                        Thread.sleep(200);
                    } catch (InterruptedException e) {
                        // Ignore
                    }
                }
                
                // Set up the frame listener to trigger rendering when new frames arrive
                glRenderer.setOnFrameAvailableListener(new GLWatermarkRenderer.OnFrameAvailableListener() {
                    @Override
                    public void onFrameAvailable() {
                        if (isRecording && handler != null) {
                            handler.post(renderRunnable);
                        }
                    }
                });
                
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
                isRecording = true;
                startRenderLoop();
                startPreviewRenderLoop();
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
        
        // DEBUG: Log MediaCodec surface dimensions
        try {
            Log.d("DEBUG_RECORDING", "MediaCodec configured for: " + videoWidth + "x" + videoHeight);
            
            // Note: We can't directly query Surface dimensions, but we can verify
            // through the MediaFormat that was actually configured
            MediaFormat configuredFormat = format;
            int configuredWidth = configuredFormat.getInteger(MediaFormat.KEY_WIDTH);
            int configuredHeight = configuredFormat.getInteger(MediaFormat.KEY_HEIGHT);
            
            Log.d("MEDIACODEC_VERIFY", "Requested: " + videoWidth + "x" + videoHeight);
            Log.d("MEDIACODEC_VERIFY", "Configured: " + configuredWidth + "x" + configuredHeight);
            
            if (configuredWidth != videoWidth || configuredHeight != videoHeight) {
                Log.e("MEDIACODEC_ERROR", "MediaCodec dimensions don't match request!");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error verifying MediaCodec dimensions", e);
        }
        
        videoEncoder.start();
        if (currentOutputFd != null) {
            mediaMuxer = new MediaMuxer(currentOutputFd, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        } else {
            mediaMuxer = new MediaMuxer(currentOutputFilePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        }
        // --- Set orientation hint for correct playback ---
        int orientationHint = 0;
        if ("portrait".equalsIgnoreCase(orientation)) {
            if (sensorOrientation == 90) orientationHint = 90;
            else if (sensorOrientation == 270) orientationHint = 270;
        }
        mediaMuxer.setOrientationHint(orientationHint);
        // --- End orientation hint ---
        muxerStarted = false;
    }

    private void startRenderLoop() {
        if (renderThread == null) {
        renderThread = new HandlerThread("GLRenderThread");
        renderThread.start();
            handler = new Handler(renderThread.getLooper());
            
            // Give some time for resources to initialize
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (glRenderer != null && handler != null && isRecording) {
                        Log.d(TAG, "Starting render loop");
                        handler.post(renderRunnable);
                    }
                }
            }, 500); // Delay first render to ensure texture is ready
        }
    }

    // Only render when a new frame is available (signaled by renderer)
    private final Runnable renderRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isRecording || glRenderer == null || videoEncoder == null) return;
            try {
                // Render to encoder
                glRenderer.renderFrame();
                
                // We'll handle preview rendering in a dedicated thread
                // to avoid conflicts with renderFrame - don't do it here anymore
                
                // Drain encoded frame data
                drainEncoder();
                
                // Check if we need to split the segment due to size
                if (shouldSplitSegment()) {
                    rolloverSegment();
                }
                
                // No need to post another renderRunnable, as onFrameAvailable listener
                // will schedule this again when a new frame is ready
                
            } catch (Exception e) {
                Log.e(TAG, "Exception in render loop", e);
                if (isRecording && handler != null && renderThread != null && renderThread.isAlive()) {
                    handler.postDelayed(this, RENDER_RETRY_DELAY_MS);
                }
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
        if (isRecording) {
            Log.d(TAG, ">> stopRecording sequence initiated. Current state: " + 
                (isRecording ? "IN_PROGRESS" : "NONE"));
            
        isRecording = false;
            
            // First stop all rendering threads to avoid accessing released resources
            stopPreviewRenderLoop();
            
            if (handler != null) {
                handler.removeCallbacksAndMessages(null);
        }
            
        if (renderThread != null) {
                try {
            renderThread.quitSafely();
                    renderThread.join(500); // Wait up to 500ms for thread to finish
                } catch (InterruptedException e) {
                    Log.w(TAG, "Interrupted while stopping render thread", e);
                }
            }
            
            // Stop encoder first before releasing renderer
            try {
        if (videoEncoder != null) {
                videoEncoder.stop();
                    videoEncoder.release();
                    videoEncoder = null;
                }
            } catch (Exception e) {
                Log.e(TAG, "Error stopping encoder", e);
            }
            
            // Now release the renderer
            if (glRenderer != null) {
            try {
                    // Final release of GL resources
                    glRenderer.release();
                    glRenderer = null;
            } catch (Exception e) {
                    Log.e(TAG, "Error releasing renderer", e);
            }
            }
            
            renderThread = null;
            handler = null;
            previewSurface = null;
            encoderInputSurface = null;
            cameraInputSurface = null;
            
            Log.d(TAG, "GLRecordingPipeline stopped and released.");
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

    /**
     * Sets the preview surface for rendering the camera feed.
     * @param surface The Surface to render the preview on.
     */
    public void setPreviewSurface(Surface surface) {
        if (this.previewSurface != surface) {
            Log.d(TAG, "Setting preview surface: " + (surface != null));
            
            // Stop the preview render thread before changing surfaces
            stopPreviewRenderLoop();
            
            // Wait for main rendering to complete a frame to avoid conflicts
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                // Ignore
            }
            
            this.previewSurface = surface;
            
            // If there's a valid renderer, update its preview surface
            if (glRenderer != null) {
                try {
                    glRenderer.setPreviewSurface(surface);
                    
                    // Force fixed dimensions to ensure consistent aspect ratio
                    forceFixedDimensions();
                    
                    // Debug preview vs recording dimensions
                    debugPreviewVsRecording();
                    
                    // Wait for preview surface to be fully set up
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException e) {
                        // Ignore
                    }
                    
                    // If we're recording, restart the preview loop with the new surface
                    if (isRecording && surface != null && surface.isValid()) {
                        startPreviewRenderLoop();
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error setting preview surface", e);
                }
            }
        }
    }

    private void startPreviewRenderLoop() {
        if (previewRenderThread == null && glRenderer != null && previewSurface != null && previewSurface.isValid()) {
            // Use normal thread priority instead of MAX_PRIORITY
            previewRenderThread = new HandlerThread("PreviewRenderThread");
            previewRenderThread.start();
            previewRenderHandler = new Handler(previewRenderThread.getLooper());
            
            synchronized (previewStateLock) {
                isPreviewActive = true;
            }
            
            // Add a slight delay before starting the preview rendering to ensure setup completes
            previewRenderHandler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    synchronized (previewStateLock) {
                        if (!isPreviewActive || glRenderer == null) return;
                        
                        try {
                            if (previewSurface != null && previewSurface.isValid()) {
                                // Ensure camera buffer size is correct before rendering
                                forceFixedDimensions();
                                glRenderer.renderToPreview();
                            }
                        } catch (Exception e) {
                            Log.e(TAG, "Error in preview render loop", e);
                        }
                        
                        // Check if we're still active before scheduling next frame
                        if (isPreviewActive && previewRenderHandler != null && glRenderer != null) {
                            previewRenderHandler.postDelayed(this, PREVIEW_RENDER_INTERVAL_MS);
                        }
                    }
                }
            }, 500); // Longer initial delay to ensure everything is set up
        }
    }
    
    private void stopPreviewRenderLoop() {
        // Stop the rendering loop first
        synchronized (previewStateLock) {
            isPreviewActive = false;
        }
        
        // Then clean up resources
        if (previewRenderHandler != null) {
            previewRenderHandler.removeCallbacksAndMessages(null);
            previewRenderHandler = null;
        }
        
        // Give thread a bit more time to finish any processing
        if (previewRenderThread != null) {
            try {
                previewRenderThread.quitSafely();
                previewRenderThread.join(200); // Slightly longer timeout for cleanup
            } catch (InterruptedException e) {
                Log.w(TAG, "Interrupted while stopping preview render thread", e);
            }
            previewRenderThread = null;
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
} 