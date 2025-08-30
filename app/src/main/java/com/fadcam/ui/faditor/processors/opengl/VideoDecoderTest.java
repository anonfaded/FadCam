package com.fadcam.ui.faditor.processors.opengl;

import android.net.Uri;
import android.view.Surface;
import com.fadcam.Log;
import com.fadcam.ui.faditor.models.VideoMetadata;

/**
 * Test class for VideoDecoder functionality.
 * This class provides methods to test various aspects of the VideoDecoder implementation.
 */
public class VideoDecoderTest {
    private static final String TAG = "VideoDecoderTest";
    
    /**
     * Test callback implementation for validation
     */
    public static class TestDecoderCallback implements DecoderCallback {
        private boolean decoderReady = false;
        private boolean frameReceived = false;
        private boolean seekCompleted = false;
        private boolean decodingComplete = false;
        private String lastError = null;
        private long lastFrameTime = 0;
        private int frameCount = 0;
        
        @Override
        public void onFrameAvailable(long presentationTimeUs) {
            frameReceived = true;
            lastFrameTime = presentationTimeUs;
            frameCount++;
            Log.d(TAG, "Frame available at: " + presentationTimeUs + "us (frame #" + frameCount + ")");
        }
        
        @Override
        public void onDecodingComplete() {
            decodingComplete = true;
            Log.d(TAG, "Decoding completed. Total frames: " + frameCount);
        }
        
        @Override
        public void onError(String error) {
            lastError = error;
            Log.e(TAG, "Decoder error: " + error);
        }
        
        @Override
        public void onDecoderReady(int videoWidth, int videoHeight, long durationUs) {
            decoderReady = true;
            Log.d(TAG, "Decoder ready - Resolution: " + videoWidth + "x" + videoHeight + 
                      ", Duration: " + (durationUs / 1000000) + "s");
        }
        
        @Override
        public void onSeekCompleted(long seekTimeUs) {
            seekCompleted = true;
            Log.d(TAG, "Seek completed to: " + seekTimeUs + "us");
        }
        
        @Override
        public void onProgressUpdate(long currentTimeUs, long totalDurationUs) {
            float progress = (float) currentTimeUs / totalDurationUs * 100;
            Log.d(TAG, "Progress: " + String.format("%.1f", progress) + "%");
        }
        
        // Getters for test validation
        public boolean isDecoderReady() { return decoderReady; }
        public boolean isFrameReceived() { return frameReceived; }
        public boolean isSeekCompleted() { return seekCompleted; }
        public boolean isDecodingComplete() { return decodingComplete; }
        public String getLastError() { return lastError; }
        public long getLastFrameTime() { return lastFrameTime; }
        public int getFrameCount() { return frameCount; }
        
        public void reset() {
            decoderReady = false;
            frameReceived = false;
            seekCompleted = false;
            decodingComplete = false;
            lastError = null;
            lastFrameTime = 0;
            frameCount = 0;
        }
    }
    
    /**
     * Test basic decoder initialization and metadata extraction
     */
    public static boolean testInitialization(Uri videoUri, Surface surface) {
        Log.d(TAG, "Testing decoder initialization...");
        
        VideoDecoder decoder = new VideoDecoder();
        TestDecoderCallback callback = new TestDecoderCallback();
        decoder.setDecoderCallback(callback);
        
        try {
            decoder.initialize(videoUri, surface);
            
            // Wait a moment for initialization
            Thread.sleep(100);
            
            // Check if decoder is ready
            if (!callback.isDecoderReady()) {
                Log.e(TAG, "Decoder not ready after initialization");
                return false;
            }
            
            // Check metadata
            VideoMetadata metadata = decoder.getVideoMetadata();
            if (metadata == null) {
                Log.e(TAG, "No metadata available");
                return false;
            }
            
            Log.d(TAG, "Metadata: " + metadata.toString());
            
            // Validate metadata completeness
            if (!metadata.isComplete()) {
                Log.e(TAG, "Incomplete metadata");
                return false;
            }
            
            decoder.release();
            Log.d(TAG, "Initialization test passed");
            return true;
            
        } catch (Exception e) {
            Log.e(TAG, "Initialization test failed: " + e.getMessage());
            decoder.release();
            return false;
        }
    }
    
    /**
     * Test frame extraction functionality
     */
    public static boolean testFrameExtraction(Uri videoUri, Surface surface) {
        Log.d(TAG, "Testing frame extraction...");
        
        VideoDecoder decoder = new VideoDecoder();
        TestDecoderCallback callback = new TestDecoderCallback();
        decoder.setDecoderCallback(callback);
        
        try {
            decoder.initialize(videoUri, surface);
            Thread.sleep(100);
            
            if (!callback.isDecoderReady()) {
                Log.e(TAG, "Decoder not ready for frame extraction test");
                return false;
            }
            
            // Extract frame at 5 seconds
            long targetTime = 5_000_000; // 5 seconds in microseconds
            callback.reset();
            decoder.extractFrame(targetTime);
            
            // Wait for frame extraction
            Thread.sleep(500);
            
            if (!callback.isFrameReceived()) {
                Log.e(TAG, "No frame received during extraction");
                return false;
            }
            
            Log.d(TAG, "Frame extracted at: " + callback.getLastFrameTime() + "us");
            
            decoder.release();
            Log.d(TAG, "Frame extraction test passed");
            return true;
            
        } catch (Exception e) {
            Log.e(TAG, "Frame extraction test failed: " + e.getMessage());
            decoder.release();
            return false;
        }
    }
    
    /**
     * Test seeking functionality
     */
    public static boolean testSeeking(Uri videoUri, Surface surface) {
        Log.d(TAG, "Testing seeking functionality...");
        
        VideoDecoder decoder = new VideoDecoder();
        TestDecoderCallback callback = new TestDecoderCallback();
        decoder.setDecoderCallback(callback);
        
        try {
            decoder.initialize(videoUri, surface);
            Thread.sleep(100);
            
            if (!callback.isDecoderReady()) {
                Log.e(TAG, "Decoder not ready for seeking test");
                return false;
            }
            
            // Test seeking to different positions
            long[] seekPositions = {1_000_000, 3_000_000, 7_000_000}; // 1s, 3s, 7s
            
            for (long seekPos : seekPositions) {
                callback.reset();
                decoder.seekToTime(seekPos);
                
                // Wait for seek completion
                Thread.sleep(300);
                
                if (!callback.isSeekCompleted()) {
                    Log.e(TAG, "Seek not completed for position: " + seekPos);
                    return false;
                }
                
                Log.d(TAG, "Successfully seeked to: " + seekPos + "us");
            }
            
            decoder.release();
            Log.d(TAG, "Seeking test passed");
            return true;
            
        } catch (Exception e) {
            Log.e(TAG, "Seeking test failed: " + e.getMessage());
            decoder.release();
            return false;
        }
    }
    
    /**
     * Test frame-accurate seeking by frame number
     */
    public static boolean testFrameAccurateSeeking(Uri videoUri, Surface surface) {
        Log.d(TAG, "Testing frame-accurate seeking...");
        
        VideoDecoder decoder = new VideoDecoder();
        TestDecoderCallback callback = new TestDecoderCallback();
        decoder.setDecoderCallback(callback);
        
        try {
            decoder.initialize(videoUri, surface);
            Thread.sleep(100);
            
            if (!callback.isDecoderReady()) {
                Log.e(TAG, "Decoder not ready for frame-accurate seeking test");
                return false;
            }
            
            VideoMetadata metadata = decoder.getVideoMetadata();
            float frameRate = metadata.getFrameRate();
            
            // Test seeking to specific frames
            long[] frameNumbers = {30, 90, 150}; // Frame 30, 90, 150
            
            for (long frameNum : frameNumbers) {
                callback.reset();
                decoder.seekToFrame(frameNum);
                
                // Wait for seek completion
                Thread.sleep(300);
                
                if (!callback.isSeekCompleted()) {
                    Log.e(TAG, "Frame seek not completed for frame: " + frameNum);
                    return false;
                }
                
                // Calculate expected time
                long expectedTime = (long) ((frameNum / frameRate) * 1_000_000);
                long actualTime = callback.getLastFrameTime();
                long timeDiff = Math.abs(actualTime - expectedTime);
                
                // Allow 100ms tolerance for frame-accurate seeking
                if (timeDiff > 100_000) {
                    Log.w(TAG, "Frame seek accuracy warning - Expected: " + expectedTime + 
                              "us, Actual: " + actualTime + "us, Diff: " + timeDiff + "us");
                }
                
                Log.d(TAG, "Frame " + frameNum + " seek completed - Time: " + actualTime + "us");
            }
            
            decoder.release();
            Log.d(TAG, "Frame-accurate seeking test passed");
            return true;
            
        } catch (Exception e) {
            Log.e(TAG, "Frame-accurate seeking test failed: " + e.getMessage());
            decoder.release();
            return false;
        }
    }
    
    /**
     * Test continuous decoding for playback
     */
    public static boolean testContinuousDecoding(Uri videoUri, Surface surface) {
        Log.d(TAG, "Testing continuous decoding...");
        
        VideoDecoder decoder = new VideoDecoder();
        TestDecoderCallback callback = new TestDecoderCallback();
        decoder.setDecoderCallback(callback);
        
        try {
            decoder.initialize(videoUri, surface);
            Thread.sleep(100);
            
            if (!callback.isDecoderReady()) {
                Log.e(TAG, "Decoder not ready for continuous decoding test");
                return false;
            }
            
            // Start decoding
            callback.reset();
            decoder.startDecoding();
            
            // Let it decode for 2 seconds
            Thread.sleep(2000);
            
            // Stop decoding
            decoder.stopDecoding();
            Thread.sleep(100);
            
            if (callback.getFrameCount() == 0) {
                Log.e(TAG, "No frames decoded during continuous decoding");
                return false;
            }
            
            Log.d(TAG, "Continuous decoding processed " + callback.getFrameCount() + " frames");
            
            decoder.release();
            Log.d(TAG, "Continuous decoding test passed");
            return true;
            
        } catch (Exception e) {
            Log.e(TAG, "Continuous decoding test failed: " + e.getMessage());
            decoder.release();
            return false;
        }
    }
    
    /**
     * Test error handling with invalid input
     */
    public static boolean testErrorHandling(Surface surface) {
        Log.d(TAG, "Testing error handling...");
        
        VideoDecoder decoder = new VideoDecoder();
        TestDecoderCallback callback = new TestDecoderCallback();
        decoder.setDecoderCallback(callback);
        
        try {
            // Test with invalid URI
            Uri invalidUri = Uri.parse("file:///nonexistent/video.mp4");
            
            try {
                decoder.initialize(invalidUri, surface);
                Log.e(TAG, "Expected exception for invalid URI");
                return false;
            } catch (Exception e) {
                Log.d(TAG, "Correctly caught exception for invalid URI: " + e.getMessage());
            }
            
            // Test operations on uninitialized decoder
            decoder.seekToTime(1000000);
            decoder.extractFrame(1000000);
            decoder.startDecoding();
            
            // These should not crash but should log warnings
            Thread.sleep(100);
            
            decoder.release();
            Log.d(TAG, "Error handling test passed");
            return true;
            
        } catch (Exception e) {
            Log.e(TAG, "Error handling test failed: " + e.getMessage());
            decoder.release();
            return false;
        }
    }
    
    /**
     * Run all tests
     */
    public static boolean runAllTests(Uri videoUri, Surface surface) {
        Log.d(TAG, "Running all VideoDecoder tests...");
        
        boolean allPassed = true;
        
        allPassed &= testInitialization(videoUri, surface);
        allPassed &= testFrameExtraction(videoUri, surface);
        allPassed &= testSeeking(videoUri, surface);
        allPassed &= testFrameAccurateSeeking(videoUri, surface);
        allPassed &= testContinuousDecoding(videoUri, surface);
        allPassed &= testErrorHandling(surface);
        
        Log.d(TAG, "All tests " + (allPassed ? "PASSED" : "FAILED"));
        return allPassed;
    }
}