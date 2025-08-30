package com.fadcam.ui.faditor.processors.opengl;

import android.content.Context;
import android.net.Uri;
import android.opengl.GLSurfaceView;
import android.view.Surface;
import com.fadcam.Log;
import com.fadcam.ui.faditor.models.VideoMetadata;

/**
 * Example class demonstrating how to use the VideoDecoder for various video decoding scenarios.
 * This class provides practical examples for integrating VideoDecoder into video editing workflows.
 */
public class VideoDecoderExample {
    private static final String TAG = "VideoDecoderExample";
    
    private VideoDecoder decoder;
    private Surface outputSurface;
    private ExampleDecoderCallback callback;
    
    /**
     * Example callback implementation with practical logging and error handling.
     */
    private static class ExampleDecoderCallback implements DecoderCallback {
        private final String tag;
        private long startTime;
        private int frameCount;
        
        public ExampleDecoderCallback(String tag) {
            this.tag = tag;
            this.startTime = System.currentTimeMillis();
        }
        
        @Override
        public void onFrameAvailable(long presentationTimeUs) {
            frameCount++;
            if (frameCount % 30 == 0) { // Log every 30 frames to avoid spam
                Log.d(tag, "Frame #" + frameCount + " at " + (presentationTimeUs / 1000) + "ms");
            }
        }
        
        @Override
        public void onDecodingComplete() {
            long elapsedTime = System.currentTimeMillis() - startTime;
            Log.d(tag, "Decoding completed - " + frameCount + " frames in " + elapsedTime + "ms");
        }
        
        @Override
        public void onError(String error) {
            Log.e(tag, "Decoder error: " + error);
        }
        
        @Override
        public void onDecoderReady(int videoWidth, int videoHeight, long durationUs) {
            Log.d(tag, "Decoder ready - " + videoWidth + "x" + videoHeight + 
                      ", " + (durationUs / 1000000) + "s duration");
        }
        
        @Override
        public void onSeekCompleted(long seekTimeUs) {
            Log.d(tag, "Seek completed to " + (seekTimeUs / 1000) + "ms");
        }
        
        @Override
        public void onProgressUpdate(long currentTimeUs, long totalDurationUs) {
            float progress = (float) currentTimeUs / totalDurationUs * 100;
            if (frameCount % 60 == 0) { // Log progress every 60 frames
                Log.d(tag, "Progress: " + String.format("%.1f%%", progress));
            }
        }
    }
    
    /**
     * Example 1: Basic video loading and metadata extraction
     */
    public static void exampleBasicVideoLoading(Uri videoUri, Surface surface) {
        Log.d(TAG, "=== Example 1: Basic Video Loading ===");
        
        // First, validate the video format
        VideoFormatValidator.VideoFormatInfo formatInfo = VideoFormatValidator.validateVideoFile(videoUri);
        
        if (!formatInfo.isValid) {
            Log.e(TAG, "Video format validation failed: " + formatInfo.errorMessage);
            return;
        }
        
        Log.d(TAG, "Video format validation passed: " + formatInfo.toString());
        
        VideoDecoder decoder = new VideoDecoder();
        ExampleDecoderCallback callback = new ExampleDecoderCallback("BasicLoading");
        decoder.setDecoderCallback(callback);
        
        try {
            // Initialize decoder
            decoder.initialize(videoUri, surface);
            
            // Get metadata
            VideoMetadata metadata = decoder.getVideoMetadata();
            Log.d(TAG, "Video metadata: " + metadata.toString());
            
            // Check if lossless operations are supported
            if (metadata.isLosslessTrimCompatible()) {
                Log.d(TAG, "Video supports lossless trimming");
            } else {
                Log.d(TAG, "Video requires re-encoding for trimming");
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Failed to load video: " + e.getMessage());
        } finally {
            decoder.release();
        }
    }
    
    /**
     * Example 2: Frame-accurate seeking for thumbnail generation
     */
    public static void exampleThumbnailGeneration(Uri videoUri, Surface surface) {
        Log.d(TAG, "=== Example 2: Thumbnail Generation ===");
        
        VideoDecoder decoder = new VideoDecoder();
        ExampleDecoderCallback callback = new ExampleDecoderCallback("Thumbnails");
        decoder.setDecoderCallback(callback);
        
        try {
            decoder.initialize(videoUri, surface);
            
            VideoMetadata metadata = decoder.getVideoMetadata();
            long duration = metadata.getDuration();
            
            // Generate thumbnails at 10%, 25%, 50%, 75%, 90% of video duration
            float[] thumbnailPositions = {0.1f, 0.25f, 0.5f, 0.75f, 0.9f};
            
            for (float position : thumbnailPositions) {
                long timestampUs = (long) (duration * position);
                Log.d(TAG, "Generating thumbnail at " + (position * 100) + "% (" + 
                          (timestampUs / 1000) + "ms)");
                
                decoder.extractFrame(timestampUs);
                
                // Wait for frame extraction
                Thread.sleep(200);
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Thumbnail generation failed: " + e.getMessage());
        } finally {
            decoder.release();
        }
    }
    
    /**
     * Example 3: Timeline scrubbing simulation
     */
    public static void exampleTimelineScrubbing(Uri videoUri, Surface surface) {
        Log.d(TAG, "=== Example 3: Timeline Scrubbing ===");
        
        VideoDecoder decoder = new VideoDecoder();
        ExampleDecoderCallback callback = new ExampleDecoderCallback("Scrubbing");
        decoder.setDecoderCallback(callback);
        
        try {
            decoder.initialize(videoUri, surface);
            
            VideoMetadata metadata = decoder.getVideoMetadata();
            long duration = metadata.getDuration();
            
            // Simulate user scrubbing through timeline
            Log.d(TAG, "Simulating timeline scrubbing...");
            
            // Forward scrubbing
            for (int i = 0; i <= 10; i++) {
                long timestampUs = (duration * i) / 10;
                decoder.seekToTime(timestampUs);
                Thread.sleep(100); // Simulate scrubbing speed
            }
            
            // Backward scrubbing
            for (int i = 10; i >= 0; i--) {
                long timestampUs = (duration * i) / 10;
                decoder.seekToTime(timestampUs);
                Thread.sleep(100);
            }
            
            Log.d(TAG, "Timeline scrubbing simulation completed");
            
        } catch (Exception e) {
            Log.e(TAG, "Timeline scrubbing failed: " + e.getMessage());
        } finally {
            decoder.release();
        }
    }
    
    /**
     * Example 4: Frame-by-frame analysis
     */
    public static void exampleFrameByFrameAnalysis(Uri videoUri, Surface surface) {
        Log.d(TAG, "=== Example 4: Frame-by-Frame Analysis ===");
        
        VideoDecoder decoder = new VideoDecoder();
        ExampleDecoderCallback callback = new ExampleDecoderCallback("FrameAnalysis");
        decoder.setDecoderCallback(callback);
        
        try {
            decoder.initialize(videoUri, surface);
            
            VideoMetadata metadata = decoder.getVideoMetadata();
            float frameRate = metadata.getFrameRate();
            
            // Analyze first 5 seconds frame by frame
            int totalFrames = (int) (5 * frameRate); // 5 seconds worth of frames
            
            Log.d(TAG, "Analyzing " + totalFrames + " frames at " + frameRate + " fps");
            
            for (int frameNum = 0; frameNum < totalFrames; frameNum++) {
                decoder.seekToFrame(frameNum);
                
                if (frameNum % 30 == 0) { // Log every 30th frame
                    Log.d(TAG, "Analyzing frame " + frameNum + "/" + totalFrames);
                }
                
                Thread.sleep(10); // Small delay to prevent overwhelming the decoder
            }
            
            Log.d(TAG, "Frame-by-frame analysis completed");
            
        } catch (Exception e) {
            Log.e(TAG, "Frame analysis failed: " + e.getMessage());
        } finally {
            decoder.release();
        }
    }
    
    /**
     * Example 5: Continuous playback with seeking
     */
    public static void exampleContinuousPlayback(Uri videoUri, Surface surface) {
        Log.d(TAG, "=== Example 5: Continuous Playback ===");
        
        VideoDecoder decoder = new VideoDecoder();
        ExampleDecoderCallback callback = new ExampleDecoderCallback("Playback");
        decoder.setDecoderCallback(callback);
        
        try {
            decoder.initialize(videoUri, surface);
            
            // Start continuous decoding
            Log.d(TAG, "Starting continuous playback...");
            decoder.startDecoding();
            
            // Let it play for 3 seconds
            Thread.sleep(3000);
            
            // Seek to middle of video
            VideoMetadata metadata = decoder.getVideoMetadata();
            long middleTime = metadata.getDuration() / 2;
            Log.d(TAG, "Seeking to middle of video: " + (middleTime / 1000) + "ms");
            decoder.seekToTime(middleTime);
            
            // Continue playback for another 2 seconds
            Thread.sleep(2000);
            
            // Stop playback
            Log.d(TAG, "Stopping playback...");
            decoder.stopDecoding();
            
        } catch (Exception e) {
            Log.e(TAG, "Continuous playback failed: " + e.getMessage());
        } finally {
            decoder.release();
        }
    }
    
    /**
     * Example 6: Performance testing with different video formats
     */
    public static void examplePerformanceTesting(Uri[] videoUris, Surface surface) {
        Log.d(TAG, "=== Example 6: Performance Testing ===");
        
        for (int i = 0; i < videoUris.length; i++) {
            Uri videoUri = videoUris[i];
            Log.d(TAG, "Testing video " + (i + 1) + "/" + videoUris.length);
            
            // Validate format first
            VideoFormatValidator.VideoFormatInfo formatInfo = 
                VideoFormatValidator.validateVideoFile(videoUri);
            
            Log.d(TAG, "Format info: " + formatInfo.toString());
            
            if (!formatInfo.isValid) {
                Log.w(TAG, "Skipping invalid video format");
                continue;
            }
            
            VideoDecoder decoder = new VideoDecoder();
            ExampleDecoderCallback callback = new ExampleDecoderCallback("PerfTest" + i);
            decoder.setDecoderCallback(callback);
            
            long startTime = System.currentTimeMillis();
            
            try {
                // Test initialization time
                decoder.initialize(videoUri, surface);
                long initTime = System.currentTimeMillis() - startTime;
                
                // Test seeking performance
                long seekStartTime = System.currentTimeMillis();
                decoder.seekToTime(5_000_000); // Seek to 5 seconds
                Thread.sleep(100); // Wait for seek completion
                long seekTime = System.currentTimeMillis() - seekStartTime;
                
                // Test frame extraction performance
                long extractStartTime = System.currentTimeMillis();
                decoder.extractFrame(10_000_000); // Extract frame at 10 seconds
                Thread.sleep(100); // Wait for extraction
                long extractTime = System.currentTimeMillis() - extractStartTime;
                
                Log.d(TAG, "Performance results - Init: " + initTime + "ms, " +
                          "Seek: " + seekTime + "ms, Extract: " + extractTime + "ms");
                
            } catch (Exception e) {
                Log.e(TAG, "Performance test failed for video " + i + ": " + e.getMessage());
            } finally {
                decoder.release();
            }
        }
    }
    
    /**
     * Example 7: Error handling and recovery
     */
    public static void exampleErrorHandling(Surface surface) {
        Log.d(TAG, "=== Example 7: Error Handling ===");
        
        VideoDecoder decoder = new VideoDecoder();
        ExampleDecoderCallback callback = new ExampleDecoderCallback("ErrorHandling");
        decoder.setDecoderCallback(callback);
        
        // Test 1: Invalid URI
        try {
            Uri invalidUri = Uri.parse("file:///nonexistent/video.mp4");
            decoder.initialize(invalidUri, surface);
            Log.e(TAG, "Expected exception for invalid URI");
        } catch (Exception e) {
            Log.d(TAG, "Correctly handled invalid URI: " + e.getMessage());
        }
        
        // Test 2: Operations on uninitialized decoder
        Log.d(TAG, "Testing operations on uninitialized decoder...");
        decoder.seekToTime(1000000);
        decoder.extractFrame(1000000);
        decoder.startDecoding();
        
        // Test 3: Double initialization
        try {
            Uri validUri = Uri.parse("android.resource://com.fadcam/raw/sample_video");
            decoder.initialize(validUri, surface);
            decoder.initialize(validUri, surface); // Should throw exception
            Log.e(TAG, "Expected exception for double initialization");
        } catch (Exception e) {
            Log.d(TAG, "Correctly handled double initialization: " + e.getMessage());
        }
        
        decoder.release();
        Log.d(TAG, "Error handling tests completed");
    }
    
    /**
     * Run all examples with a sample video
     */
    public static void runAllExamples(Uri videoUri, Surface surface) {
        Log.d(TAG, "Running all VideoDecoder examples...");
        
        exampleBasicVideoLoading(videoUri, surface);
        exampleThumbnailGeneration(videoUri, surface);
        exampleTimelineScrubbing(videoUri, surface);
        exampleFrameByFrameAnalysis(videoUri, surface);
        exampleContinuousPlayback(videoUri, surface);
        exampleErrorHandling(surface);
        
        Log.d(TAG, "All examples completed");
    }
}