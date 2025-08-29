package com.fadcam.ui.faditor.processors;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.media.MediaMuxer;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;

import com.fadcam.ui.faditor.models.EditOperation;
import com.fadcam.ui.faditor.models.VideoMetadata;
import com.fadcam.ui.faditor.processors.opengl.VideoRenderer;
import com.fadcam.ui.faditor.processors.opengl.VideoTexture;
import com.fadcam.ui.faditor.processors.opengl.MediaCodecIntegration;
import com.fadcam.ui.faditor.utils.PerformanceMonitor;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * OpenGL-based video processor for hardware-accelerated video operations.
 * Primary processing engine using OpenGL ES and MediaCodec integration.
 */
public class OpenGLVideoProcessor implements VideoProcessor {

    private static final String TAG = "OpenGLVideoProcessor";

    private final Context context;
    private VideoProcessor.ProcessingCallback currentCallback;
    private boolean isProcessing = false;
    private boolean isCancelled = false;

    private HandlerThread processingThread;
    private Handler processingHandler;

    private VideoRenderer videoRenderer;
    private VideoTexture videoTexture;
    private MediaCodecIntegration mediaCodecIntegration;
    
    // Performance monitoring
    private final PerformanceMonitor performanceMonitor;

    public OpenGLVideoProcessor(Context context) {
        this.context = context;
        this.performanceMonitor = PerformanceMonitor.getInstance();
        initializeProcessingThread();
        
        // Enable performance monitoring for video processing
        performanceMonitor.enableMonitoring();
    }

    private void initializeProcessingThread() {
        processingThread = new HandlerThread("OpenGLVideoProcessor");
        processingThread.start();
        processingHandler = new Handler(processingThread.getLooper());
    }

    @Override
    public void processVideo(File inputFile, EditOperation operation, File outputFile, VideoProcessor.ProcessingCallback callback) {
        if (isProcessing) {
            if (callback != null) {
                callback.onError("Another processing operation is already in progress");
            }
            return;
        }

        this.currentCallback = callback;
        this.isProcessing = true;
        this.isCancelled = false;

        processingHandler.post(() -> {
            try {
                switch (operation.getType()) {
                    case TRIM:
                        processTrimOperation(inputFile, operation, outputFile);
                        break;
                    default:
                        notifyError("Unsupported operation type: " + operation.getType());
                }
            } catch (Exception e) {
                Log.e(TAG, "Error processing video", e);
                notifyError("Processing failed: " + e.getMessage());
            }
        });
    }

    private void processTrimOperation(File inputFile, EditOperation operation, File outputFile) {
        performanceMonitor.startOperation("trim_operation");
        try {
            VideoMetadata metadata = extractVideoMetadata(inputFile);

            if (canProcessLossless(metadata, operation)) {
                Log.d(TAG, "Using lossless stream copying for trim operation");
                performanceMonitor.startOperation("lossless_trim");
                trimVideoLossless(inputFile, operation.getStartTime(), operation.getEndTime(), outputFile,
                        currentCallback);
                performanceMonitor.endOperation("lossless_trim");
            } else {
                Log.d(TAG, "Using hardware re-encoding for trim operation");
                performanceMonitor.startOperation("reencoding_trim");
                trimVideoWithReencoding(inputFile, operation.getStartTime(), operation.getEndTime(), outputFile,
                        currentCallback);
                performanceMonitor.endOperation("reencoding_trim");
            }
            performanceMonitor.endOperation("trim_operation");
        } catch (Exception e) {
            performanceMonitor.endOperation("trim_operation");
            Log.e(TAG, "Error in trim operation", e);
            notifyError("Trim operation failed: " + e.getMessage());
        }
    }

    @Override
    public boolean canProcessLossless(VideoMetadata metadata, EditOperation operation) {
        if (operation.getType() != EditOperation.Type.TRIM) {
            return false;
        }

        // Check if video format supports lossless trimming
        return metadata.isLosslessTrimCompatible() &&
                isKeyFrameAligned(operation.getStartTime()) &&
                isKeyFrameAligned(operation.getEndTime());
    }

    private boolean isKeyFrameAligned(long timeMs) {
        // For simplicity, assume key frames are at regular intervals (e.g., every 2
        // seconds)
        // In a real implementation, you would analyze the video stream for actual key
        // frame positions
        return (timeMs % 2000) == 0;
    }

    /**
     * Trim video using lossless stream copying when possible
     */
    public void trimVideoLossless(File inputFile, long startMs, long endMs,
            File outputFile, VideoProcessor.ProcessingCallback callback) {
        MediaExtractor extractor = null;
        MediaMuxer muxer = null;

        try {
            extractor = new MediaExtractor();
            extractor.setDataSource(inputFile.getAbsolutePath());

            muxer = new MediaMuxer(outputFile.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);

            int trackCount = extractor.getTrackCount();
            int[] trackIndexMap = new int[trackCount];

            // Add tracks to muxer
            for (int i = 0; i < trackCount; i++) {
                MediaFormat format = extractor.getTrackFormat(i);
                String mime = format.getString(MediaFormat.KEY_MIME);

                if (mime.startsWith("video/") || mime.startsWith("audio/")) {
                    trackIndexMap[i] = muxer.addTrack(format);
                    Log.d(TAG, "Added track " + i + " with mime: " + mime);
                } else {
                    trackIndexMap[i] = -1;
                }
            }

            muxer.start();

            // Process each track
            for (int i = 0; i < trackCount; i++) {
                if (trackIndexMap[i] == -1)
                    continue;

                extractor.selectTrack(i);
                extractor.seekTo(startMs * 1000, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);

                ByteBuffer buffer = ByteBuffer.allocate(1024 * 1024); // 1MB buffer
                MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();

                long endTimeUs = endMs * 1000;
                int progress = 0;
                long totalDuration = endMs - startMs;

                while (!isCancelled) {
                    int sampleSize = extractor.readSampleData(buffer, 0);
                    if (sampleSize < 0)
                        break;

                    long sampleTime = extractor.getSampleTime();
                    if (sampleTime > endTimeUs)
                        break;

                    bufferInfo.offset = 0;
                    bufferInfo.size = sampleSize;
                    bufferInfo.presentationTimeUs = sampleTime - (startMs * 1000);
                    bufferInfo.flags = extractor.getSampleFlags();

                    muxer.writeSampleData(trackIndexMap[i], buffer, bufferInfo);
                    extractor.advance();

                    // Update progress
                    if (totalDuration > 0) {
                        int newProgress = (int) ((sampleTime - startMs * 1000) * 100 / (totalDuration * 1000));
                        if (newProgress > progress) {
                            progress = newProgress;
                            notifyProgress(Math.min(progress, 100));
                        }
                    }
                }

                extractor.unselectTrack(i);
            }

            if (!isCancelled) {
                notifySuccess(outputFile);
            }

        } catch (Exception e) {
            Log.e(TAG, "Error in lossless trimming", e);
            notifyError("Lossless trimming failed: " + e.getMessage());
        } finally {
            try {
                if (muxer != null) {
                    muxer.stop();
                    muxer.release();
                }
                if (extractor != null) {
                    extractor.release();
                }
            } catch (Exception e) {
                Log.e(TAG, "Error releasing resources", e);
            }
        }
    }

    /**
     * Trim video with re-encoding using hardware acceleration
     */
    public void trimVideoWithReencoding(File inputFile, long startMs, long endMs,
            File outputFile, VideoProcessor.ProcessingCallback callback) {
        try {
            // Initialize OpenGL components
            videoRenderer = new VideoRenderer(context);
            videoTexture = new VideoTexture();
            mediaCodecIntegration = new MediaCodecIntegration();

            VideoMetadata metadata = extractVideoMetadata(inputFile);

            // Setup MediaCodec integration for hardware encoding
            mediaCodecIntegration.setupEncoder(metadata, outputFile);

            // Initialize OpenGL renderer
            videoRenderer.initialize(mediaCodecIntegration.getInputSurface());

            // Process video frames with OpenGL
            processVideoFrames(inputFile, startMs, endMs, metadata);

            // Finalize encoding
            mediaCodecIntegration.finishEncoding();

            if (!isCancelled) {
                notifySuccess(outputFile);
            }

        } catch (Exception e) {
            Log.e(TAG, "Error in hardware re-encoding", e);
            notifyError("Hardware re-encoding failed: " + e.getMessage());
        } finally {
            releaseOpenGLResources();
        }
    }

    private void processVideoFrames(File inputFile, long startMs, long endMs, VideoMetadata metadata)
            throws IOException {
        MediaExtractor extractor = new MediaExtractor();
        MediaCodec decoder = null;

        try {
            extractor.setDataSource(inputFile.getAbsolutePath());

            // Find video track
            int videoTrackIndex = -1;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat format = extractor.getTrackFormat(i);
                String mime = format.getString(MediaFormat.KEY_MIME);
                if (mime.startsWith("video/")) {
                    videoTrackIndex = i;
                    break;
                }
            }

            if (videoTrackIndex == -1) {
                throw new IOException("No video track found");
            }

            MediaFormat format = extractor.getTrackFormat(videoTrackIndex);
            extractor.selectTrack(videoTrackIndex);
            extractor.seekTo(startMs * 1000, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);

            // Create decoder
            String mime = format.getString(MediaFormat.KEY_MIME);
            decoder = MediaCodec.createDecoderByType(mime);
            decoder.configure(format, videoTexture.getSurface(), null, 0);
            decoder.start();

            // Process frames
            boolean inputDone = false;
            boolean outputDone = false;
            long endTimeUs = endMs * 1000;
            long totalDuration = endMs - startMs;

            while (!outputDone && !isCancelled) {
                // Feed input to decoder
                if (!inputDone) {
                    int inputBufferIndex = decoder.dequeueInputBuffer(0);
                    if (inputBufferIndex >= 0) {
                        ByteBuffer inputBuffer = decoder.getInputBuffer(inputBufferIndex);
                        int sampleSize = extractor.readSampleData(inputBuffer, 0);

                        if (sampleSize < 0 || extractor.getSampleTime() > endTimeUs) {
                            decoder.queueInputBuffer(inputBufferIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            long presentationTime = extractor.getSampleTime();
                            decoder.queueInputBuffer(inputBufferIndex, 0, sampleSize, presentationTime, 0);
                            extractor.advance();
                        }
                    }
                }

                // Get output from decoder
                MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
                int outputBufferIndex = decoder.dequeueOutputBuffer(bufferInfo, 0);

                if (outputBufferIndex >= 0) {
                    if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        outputDone = true;
                    } else {
                        // Render frame with OpenGL
                        videoTexture.updateTexImage();
                        videoRenderer.renderFrame(bufferInfo.presentationTimeUs);

                        // Encode frame
                        mediaCodecIntegration.encodeFrame(bufferInfo.presentationTimeUs);

                        // Update progress
                        if (totalDuration > 0) {
                            long currentTime = bufferInfo.presentationTimeUs / 1000;
                            int progress = (int) ((currentTime - startMs) * 100 / totalDuration);
                            notifyProgress(Math.min(Math.max(progress, 0), 100));
                        }
                    }

                    decoder.releaseOutputBuffer(outputBufferIndex, true);
                }
            }

        } finally {
            if (decoder != null) {
                try {
                    decoder.stop();
                    decoder.release();
                } catch (Exception e) {
                    Log.e(TAG, "Error releasing decoder", e);
                }
            }
            extractor.release();
        }
    }

    private VideoMetadata extractVideoMetadata(File videoFile) throws IOException {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(videoFile.getAbsolutePath());

            VideoMetadata metadata = new VideoMetadata();

            String width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH);
            String height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT);
            String duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            String bitrate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE);
            String frameRate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE);
            String mimeType = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE);

            if (width != null)
                metadata.setWidth(Integer.parseInt(width));
            if (height != null)
                metadata.setHeight(Integer.parseInt(height));
            if (duration != null)
                metadata.setDuration(Long.parseLong(duration));
            if (bitrate != null)
                metadata.setBitrate(Integer.parseInt(bitrate));
            if (frameRate != null)
                metadata.setFrameRate(Float.parseFloat(frameRate));
            if (mimeType != null) {
                metadata.setCodec(mimeType);
                metadata.setContainerFormat("mp4"); // Assume MP4 for now
            }

            return metadata;

        } finally {
            retriever.release();
        }
    }

    private void releaseOpenGLResources() {
        if (videoRenderer != null) {
            videoRenderer.release();
            videoRenderer = null;
        }
        if (videoTexture != null) {
            videoTexture.release();
            videoTexture = null;
        }
        if (mediaCodecIntegration != null) {
            mediaCodecIntegration.release();
            mediaCodecIntegration = null;
        }
    }

    @Override
    public void cancelProcessing() {
        isCancelled = true;
        isProcessing = false;
        if (currentCallback != null) {
            currentCallback.onError("Processing cancelled");
            currentCallback = null;
        }
    }

    @Override
    public void release() {
        cancelProcessing();
        releaseOpenGLResources();

        if (processingThread != null) {
            processingThread.quitSafely();
            try {
                processingThread.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            processingThread = null;
            processingHandler = null;
        }
    }

    private void notifyProgress(int percentage) {
        if (currentCallback != null && !isCancelled) {
            currentCallback.onProgress(percentage);
        }
    }

    private void notifySuccess(File outputFile) {
        isProcessing = false;
        if (currentCallback != null && !isCancelled) {
            currentCallback.onSuccess(outputFile);
        }
        currentCallback = null;
    }

    private void notifyError(String errorMessage) {
        isProcessing = false;
        if (currentCallback != null) {
            currentCallback.onError(errorMessage);
        }
        currentCallback = null;
    }
    
    /**
     * Process video for export with custom surface output
     */
    public void processVideoForExport(com.fadcam.ui.faditor.models.VideoProject project, 
                                     android.view.Surface outputSurface, 
                                     ProcessingCallback callback) {
        if (isProcessing) {
            if (callback != null) {
                callback.onError("Another processing operation is already in progress");
            }
            return;
        }

        this.currentCallback = callback;
        this.isProcessing = true;
        this.isCancelled = false;

        processingHandler.post(() -> {
            try {
                // Get the working file from the project
                File inputFile = project.getWorkingFile();
                if (inputFile == null || !inputFile.exists()) {
                    notifyError("Input video file not found");
                    return;
                }

                VideoMetadata metadata = project.getMetadata();
                
                // Initialize OpenGL components for export
                videoRenderer = new VideoRenderer(context);
                videoTexture = new VideoTexture();

                // Initialize renderer with the provided output surface
                videoRenderer.initialize(outputSurface);

                // Process video frames for export
                processVideoFramesForExport(inputFile, project, metadata, callback);

                if (!isCancelled) {
                    // Export completed successfully
                    if (callback != null) {
                        callback.onSuccess(inputFile); // Return the processed file
                    }
                }

            } catch (Exception e) {
                Log.e(TAG, "Error processing video for export", e);
                notifyError("Export processing failed: " + e.getMessage());
            } finally {
                releaseOpenGLResources();
                isProcessing = false;
            }
        });
    }

    private void processVideoFramesForExport(File inputFile, 
                                           com.fadcam.ui.faditor.models.VideoProject project,
                                           VideoMetadata metadata, 
                                           ProcessingCallback callback) throws IOException {
        MediaExtractor extractor = new MediaExtractor();
        MediaCodec decoder = null;

        try {
            extractor.setDataSource(inputFile.getAbsolutePath());

            // Find video track
            int videoTrackIndex = -1;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat format = extractor.getTrackFormat(i);
                String mime = format.getString(MediaFormat.KEY_MIME);
                if (mime.startsWith("video/")) {
                    videoTrackIndex = i;
                    break;
                }
            }

            if (videoTrackIndex == -1) {
                throw new IOException("No video track found");
            }

            MediaFormat format = extractor.getTrackFormat(videoTrackIndex);
            extractor.selectTrack(videoTrackIndex);

            // Apply project operations (trim, etc.)
            long startTimeUs = 0;
            long endTimeUs = metadata.getDuration() * 1000;
            
            // Check if project has trim operations
            if (project.getCurrentTrim() != null) {
                startTimeUs = project.getCurrentTrim().getStartTime() * 1000;
                endTimeUs = project.getCurrentTrim().getEndTime() * 1000;
            }

            extractor.seekTo(startTimeUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);

            // Create decoder
            String mime = format.getString(MediaFormat.KEY_MIME);
            decoder = MediaCodec.createDecoderByType(mime);
            decoder.configure(format, videoTexture.getSurface(), null, 0);
            decoder.start();

            // Process frames
            boolean inputDone = false;
            boolean outputDone = false;
            long totalDuration = endTimeUs - startTimeUs;
            int frameCount = 0;

            while (!outputDone && !isCancelled) {
                // Feed input to decoder
                if (!inputDone) {
                    int inputBufferIndex = decoder.dequeueInputBuffer(0);
                    if (inputBufferIndex >= 0) {
                        ByteBuffer inputBuffer = decoder.getInputBuffer(inputBufferIndex);
                        int sampleSize = extractor.readSampleData(inputBuffer, 0);

                        if (sampleSize < 0 || extractor.getSampleTime() > endTimeUs) {
                            decoder.queueInputBuffer(inputBufferIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            long presentationTime = extractor.getSampleTime();
                            decoder.queueInputBuffer(inputBufferIndex, 0, sampleSize, presentationTime, 0);
                            extractor.advance();
                        }
                    }
                }

                // Get output from decoder
                MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
                int outputBufferIndex = decoder.dequeueOutputBuffer(bufferInfo, 0);

                if (outputBufferIndex >= 0) {
                    if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        outputDone = true;
                    } else {
                        // Render frame with OpenGL
                        videoTexture.updateTexImage();
                        
                        // Adjust presentation time for trimmed video
                        long adjustedPresentationTime = bufferInfo.presentationTimeUs - startTimeUs;
                        
                        videoRenderer.renderFrame(adjustedPresentationTime);

                        // Notify callback about frame processing
                        if (callback != null) {
                            callback.onFrameProcessed(adjustedPresentationTime);
                        }

                        frameCount++;

                        // Update progress
                        if (totalDuration > 0) {
                            long currentTime = bufferInfo.presentationTimeUs - startTimeUs;
                            int progress = (int) (currentTime * 100 / totalDuration);
                            notifyProgress(Math.min(Math.max(progress, 0), 100));
                        }
                    }

                    decoder.releaseOutputBuffer(outputBufferIndex, true);
                }
            }

            Log.d(TAG, "Processed " + frameCount + " frames for export");

        } finally {
            if (decoder != null) {
                try {
                    decoder.stop();
                    decoder.release();
                } catch (Exception e) {
                    Log.e(TAG, "Error releasing decoder", e);
                }
            }
            extractor.release();
        }
    }
    
    /**
     * Extended ProcessingCallback interface for export operations
     */
    public interface ProcessingCallback extends VideoProcessor.ProcessingCallback {
        /**
         * Called when a frame has been processed and is ready for encoding
         * @param presentationTimeUs The presentation time of the processed frame
         */
        default void onFrameProcessed(long presentationTimeUs) {
            // Default implementation - can be overridden
        }
    }
}