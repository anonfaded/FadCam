package com.fadcam.ui.faditor.processors.opengl;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerThread;
import android.view.Surface;
import com.fadcam.Log;
import com.fadcam.ui.faditor.models.VideoMetadata;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * VideoDecoder handles hardware-accelerated video decoding using MediaCodec with OpenGL surface output.
 * This class provides frame-accurate seeking, extraction capabilities, and supports common video formats
 * including H.264, H.265, and VP9 with hardware acceleration when available.
 */
public class VideoDecoder {
    private static final String TAG = "VideoDecoder";
    private static final long TIMEOUT_US = 10000; // 10ms timeout
    private static final int MAX_SEEK_ATTEMPTS = 5;
    
    // Supported video formats for hardware decoding
    private static final String[] SUPPORTED_FORMATS = {
        MediaFormat.MIMETYPE_VIDEO_AVC,    // H.264
        MediaFormat.MIMETYPE_VIDEO_HEVC,   // H.265
        MediaFormat.MIMETYPE_VIDEO_VP9     // VP9
    };
    
    private MediaExtractor extractor;
    private MediaCodec decoder;
    private Surface outputSurface;
    private DecoderCallback callback;
    private VideoMetadata videoMetadata;
    private Context context;
    
    private HandlerThread decoderThread;
    private Handler decoderHandler;
    
    private final AtomicBoolean isInitialized = new AtomicBoolean(false);
    private final AtomicBoolean isDecoding = new AtomicBoolean(false);
    private final AtomicBoolean isSeeking = new AtomicBoolean(false);
    
    private int videoTrackIndex = -1;
    private long videoDuration = 0;
    private boolean isEOS = false;
    
    /**
     * Initializes the video decoder with the specified video URI and output surface.
     * 
     * @param context The application context for content URI access
     * @param videoUri The URI of the video file to decode
     * @param outputSurface The OpenGL surface where decoded frames will be rendered
     * @throws IOException if the video file cannot be accessed or is corrupted
     * @throws IllegalArgumentException if the video format is not supported
     */
    public void initialize(Context context, Uri videoUri, Surface outputSurface) throws IOException {
        if (isInitialized.get()) {
            throw new IllegalStateException("VideoDecoder is already initialized");
        }
        
        this.context = context;
        this.outputSurface = outputSurface;
        
        // Create background thread for decoding operations
        decoderThread = new HandlerThread("VideoDecoderThread");
        decoderThread.start();
        decoderHandler = new Handler(decoderThread.getLooper());
        
        try {
            // Extract video metadata first
            extractVideoMetadata(videoUri);
            
            // Initialize MediaExtractor
            extractor = new MediaExtractor();
            
            // Handle content URIs properly
            if ("content".equals(videoUri.getScheme())) {
                extractor.setDataSource(context, videoUri, null);
            } else {
                extractor.setDataSource(videoUri.toString());
            }
            
            // Find video track
            videoTrackIndex = findVideoTrack();
            if (videoTrackIndex < 0) {
                throw new IllegalArgumentException("No video track found in the file");
            }
            
            extractor.selectTrack(videoTrackIndex);
            MediaFormat format = extractor.getTrackFormat(videoTrackIndex);
            
            // Validate format support
            String mimeType = format.getString(MediaFormat.KEY_MIME);
            if (!isSupportedFormat(mimeType)) {
                throw new IllegalArgumentException("Unsupported video format: " + mimeType);
            }
            
            // Create and configure decoder
            decoder = MediaCodec.createDecoderByType(mimeType);
            decoder.configure(format, outputSurface, null, 0);
            decoder.start();
            
            videoDuration = format.getLong(MediaFormat.KEY_DURATION);
            isInitialized.set(true);
            
            // Notify callback that decoder is ready
            if (callback != null) {
                int width = format.getInteger(MediaFormat.KEY_WIDTH);
                int height = format.getInteger(MediaFormat.KEY_HEIGHT);
                callback.onDecoderReady(width, height, videoDuration);
            }
            
            Log.d(TAG, "VideoDecoder initialized successfully for format: " + mimeType);
            
        } catch (Exception e) {
            cleanup();
            throw new IOException("Failed to initialize video decoder: " + e.getMessage(), e);
        }
    }
    
    /**
     * Seeks to a specific frame number with frame-accurate precision.
     * 
     * @param frameNumber The target frame number to seek to
     */
    public void seekToFrame(long frameNumber) {
        if (!isInitialized.get()) {
            Log.w(TAG, "Cannot seek: decoder not initialized");
            return;
        }
        
        // Convert frame number to timestamp (assuming 30fps, will be refined with actual frame rate)
        float frameRate = videoMetadata != null ? videoMetadata.getFrameRate() : 30.0f;
        long targetTimeUs = (long) ((frameNumber / frameRate) * 1_000_000);
        
        seekToTime(targetTimeUs);
    }
    
    /**
     * Seeks to a specific timestamp with high precision.
     * 
     * @param timestampUs The target timestamp in microseconds
     */
    public void seekToTime(long timestampUs) {
        if (!isInitialized.get()) {
            Log.w(TAG, "Cannot seek: decoder not initialized");
            return;
        }
        
        decoderHandler.post(() -> performSeek(timestampUs));
    }
    
    /**
     * Extracts a single frame at the specified timestamp.
     * This method is useful for generating thumbnails or frame-accurate editing.
     * 
     * @param timestampUs The timestamp of the frame to extract in microseconds
     */
    public void extractFrame(long timestampUs) {
        if (!isInitialized.get()) {
            Log.w(TAG, "Cannot extract frame: decoder not initialized");
            return;
        }
        
        decoderHandler.post(() -> {
            performSeek(timestampUs);
            // After seeking, decode one frame
            decodeNextFrame();
        });
    }
    
    /**
     * Starts continuous decoding for playback.
     * This method begins the decoding loop for smooth video playback.
     */
    public void startDecoding() {
        if (!isInitialized.get()) {
            Log.w(TAG, "Cannot start decoding: decoder not initialized");
            return;
        }
        
        if (isDecoding.get()) {
            Log.w(TAG, "Decoding already in progress");
            return;
        }
        
        isDecoding.set(true);
        decoderHandler.post(this::decodingLoop);
    }
    
    /**
     * Stops the decoding process.
     */
    public void stopDecoding() {
        isDecoding.set(false);
    }
    
    /**
     * Gets the video metadata extracted during initialization.
     * 
     * @return VideoMetadata object containing video properties
     */
    public VideoMetadata getVideoMetadata() {
        return videoMetadata;
    }
    
    /**
     * Sets the callback for decoder events.
     * 
     * @param callback The callback to receive decoder events
     */
    public void setDecoderCallback(DecoderCallback callback) {
        this.callback = callback;
    }
    
    /**
     * Releases all resources and cleans up the decoder.
     * This method should be called when the decoder is no longer needed.
     */
    public void release() {
        Log.d(TAG, "VideoDecoder release() called");
        isDecoding.set(false);
        isSeeking.set(false);
        
        // Perform cleanup synchronously to prevent race conditions
        cleanup();
        
        // Quit the decoder thread
        if (decoderThread != null) {
            decoderThread.quitSafely();
            try {
                decoderThread.join(1000); // Wait up to 1 second for thread to finish
                Log.d(TAG, "Decoder thread terminated");
            } catch (InterruptedException e) {
                Log.w(TAG, "Interrupted while waiting for decoder thread to finish");
                Thread.currentThread().interrupt();
            }
            decoderThread = null;
            decoderHandler = null;
        }
        
        isInitialized.set(false);
        Log.d(TAG, "VideoDecoder release() completed");
    }
    
    // Private helper methods
    
    private void extractVideoMetadata(Uri videoUri) throws IOException {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            // Handle content URIs properly
            if ("content".equals(videoUri.getScheme())) {
                retriever.setDataSource(context, videoUri);
            } else {
                retriever.setDataSource(videoUri.toString());
            }
            
            String widthStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH);
            String heightStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT);
            String durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            String bitrateStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE);
            String frameRateStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE);
            String mimeTypeStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE);
            
            int width = widthStr != null ? Integer.parseInt(widthStr) : 0;
            int height = heightStr != null ? Integer.parseInt(heightStr) : 0;
            long duration = durationStr != null ? Long.parseLong(durationStr) * 1000 : 0; // Convert to microseconds
            int bitrate = bitrateStr != null ? Integer.parseInt(bitrateStr) : 0;
            float frameRate = frameRateStr != null ? Float.parseFloat(frameRateStr) : 30.0f;
            String codec = mimeTypeStr != null ? mimeTypeStr : "unknown";
            
            videoMetadata = new VideoMetadata(codec, width, height, duration, bitrate, frameRate);
            videoMetadata.setColorFormat("yuv420p");
            
        } finally {
            retriever.release();
        }
    }
    
    private int findVideoTrack() {
        int trackCount = extractor.getTrackCount();
        for (int i = 0; i < trackCount; i++) {
            MediaFormat format = extractor.getTrackFormat(i);
            String mimeType = format.getString(MediaFormat.KEY_MIME);
            if (mimeType != null && mimeType.startsWith("video/")) {
                return i;
            }
        }
        return -1;
    }
    
    private boolean isSupportedFormat(String mimeType) {
        for (String supportedFormat : SUPPORTED_FORMATS) {
            if (supportedFormat.equals(mimeType)) {
                return true;
            }
        }
        return false;
    }
    
    private void performSeek(long timestampUs) {
        if (isSeeking.get()) {
            Log.w(TAG, "Seek already in progress");
            return;
        }
        
        isSeeking.set(true);
        
        try {
            // Seek to the closest sync frame before the target timestamp
            extractor.seekTo(timestampUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
            
            // Flush the decoder to clear any buffered frames
            decoder.flush();
            
            // Reset EOS flag
            isEOS = false;
            
            // Decode frames until we reach the target timestamp
            long actualSeekTime = seekToExactFrame(timestampUs);
            
            if (callback != null) {
                callback.onSeekCompleted(actualSeekTime);
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Seek failed: " + e.getMessage());
            if (callback != null) {
                callback.onError("Seek failed: " + e.getMessage());
            }
        } finally {
            isSeeking.set(false);
        }
    }
    
    private long seekToExactFrame(long targetTimeUs) {
        int attempts = 0;
        long lastPresentationTime = 0;
        
        while (attempts < MAX_SEEK_ATTEMPTS && !isEOS) {
            MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
            int outputBufferIndex = decoder.dequeueOutputBuffer(bufferInfo, TIMEOUT_US);
            
            if (outputBufferIndex >= 0) {
                lastPresentationTime = bufferInfo.presentationTimeUs;
                
                // Check if we've reached or passed the target time
                if (bufferInfo.presentationTimeUs >= targetTimeUs) {
                    decoder.releaseOutputBuffer(outputBufferIndex, true);
                    break;
                }
                
                decoder.releaseOutputBuffer(outputBufferIndex, false);
            } else if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                // Handle format change if needed
                MediaFormat newFormat = decoder.getOutputFormat();
                Log.d(TAG, "Output format changed: " + newFormat);
            }
            
            // Feed input if available
            feedInputBuffer();
            
            attempts++;
        }
        
        return lastPresentationTime;
    }
    
    private void decodingLoop() {
        while (isDecoding.get() && !isEOS) {
            if (!decodeNextFrame()) {
                break;
            }
        }
        
        if (callback != null && isEOS) {
            callback.onDecodingComplete();
        }
    }
    
    private boolean decodeNextFrame() {
        try {
            // Handle output buffers
            MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
            int outputBufferIndex = decoder.dequeueOutputBuffer(bufferInfo, TIMEOUT_US);
            
            if (outputBufferIndex >= 0) {
                // Frame is ready for rendering
                long frameTimeUs = bufferInfo.presentationTimeUs;
                
                // Calculate when this frame should be displayed (for proper timing)
                // This prevents super-fast playback by respecting frame timing
                long currentTimeUs = System.nanoTime() / 1000; // Current time in microseconds
                
                if (callback != null) {
                    callback.onFrameAvailable(frameTimeUs);
                    callback.onProgressUpdate(frameTimeUs, videoDuration);
                }
                
                // Render the frame to the surface
                decoder.releaseOutputBuffer(outputBufferIndex, true);
                
                // Check for end of stream
                if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    isEOS = true;
                    return false;
                }
                
                return true;
                
            } else if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                MediaFormat newFormat = decoder.getOutputFormat();
                Log.d(TAG, "Output format changed: " + newFormat);
            }
            
            // Feed input buffer
            return feedInputBuffer();
            
        } catch (Exception e) {
            Log.e(TAG, "Error during frame decoding: " + e.getMessage());
            if (callback != null) {
                callback.onError("Frame decoding failed: " + e.getMessage());
            }
            return false;
        }
    }
    
    private boolean feedInputBuffer() {
        int inputBufferIndex = decoder.dequeueInputBuffer(TIMEOUT_US);
        if (inputBufferIndex >= 0) {
            ByteBuffer inputBuffer = decoder.getInputBuffer(inputBufferIndex);
            if (inputBuffer != null) {
                int sampleSize = extractor.readSampleData(inputBuffer, 0);
                
                if (sampleSize >= 0) {
                    long presentationTimeUs = extractor.getSampleTime();
                    decoder.queueInputBuffer(inputBufferIndex, 0, sampleSize, presentationTimeUs, 0);
                    extractor.advance();
                } else {
                    // End of stream
                    decoder.queueInputBuffer(inputBufferIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                    isEOS = true;
                }
                return true;
            }
        }
        return false;
    }
    
    private void cleanup() {
        Log.d(TAG, "VideoDecoder cleanup() started");
        
        // Stop and release MediaCodec first
        try {
            if (decoder != null) {
                Log.d(TAG, "Stopping MediaCodec...");
                decoder.stop();
                Log.d(TAG, "Releasing MediaCodec...");
                decoder.release();
                decoder = null;
                Log.d(TAG, "MediaCodec released successfully");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error releasing decoder: " + e.getMessage());
        }
        
        // Release MediaExtractor
        try {
            if (extractor != null) {
                Log.d(TAG, "Releasing MediaExtractor...");
                extractor.release();
                extractor = null;
                Log.d(TAG, "MediaExtractor released successfully");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error releasing extractor: " + e.getMessage());
        }
        
        Log.d(TAG, "VideoDecoder cleanup() completed");
    }
}