package com.fadcam.ui.faditor.processors.opengl;

import android.content.Context;
import android.net.Uri;
import android.opengl.GLSurfaceView;
import com.fadcam.Log;
import com.fadcam.ui.faditor.models.VideoMetadata;

/**
 * Test class for OpenGLVideoController to verify frame-accurate seeking and <50ms response times.
 * This test demonstrates the integration of VideoDecoder, VideoRenderer, and PlaybackController.
 * Requirements: 13.2, 13.4, 14.1, 14.5
 */
public class OpenGLVideoControllerTest {
    private static final String TAG = "OpenGLVideoControllerTest";
    
    private OpenGLVideoController controller;
    private TestResults testResults;
    
    public static class TestResults {
        public boolean videoLoadedSuccessfully = false;
        public boolean frameRenderingWorking = false;
        public boolean seekingWorking = false;
        public boolean playbackControlWorking = false;
        public long averageSeekTime = 0;
        public int framesRendered = 0;
        public String lastError = null;
        
        public boolean allTestsPassed() {
            return videoLoadedSuccessfully && frameRenderingWorking && 
                   seekingWorking && playbackControlWorking && 
                   averageSeekTime < 50 && lastError == null;
        }
        
        @Override
        public String toString() {
            return "TestResults{" +
                   "videoLoaded=" + videoLoadedSuccessfully +
                   ", frameRendering=" + frameRenderingWorking +
                   ", seeking=" + seekingWorking +
                   ", playback=" + playbackControlWorking +
                   ", avgSeekTime=" + averageSeekTime + "ms" +
                   ", framesRendered=" + framesRendered +
                   ", error='" + lastError + '\'' +
                   ", allPassed=" + allTestsPassed() +
                   '}';
        }
    }
    
    public OpenGLVideoControllerTest() {
        testResults = new TestResults();
    }
    
    /**
     * Run comprehensive test of OpenGL video controller functionality
     */
    public TestResults runTests(Context context, GLSurfaceView surfaceView, Uri testVideoUri) {
        Log.d(TAG, "Starting OpenGL video controller tests");
        
        try {
            // Initialize controller
            controller = new OpenGLVideoController(context);
            setupTestListener();
            
            // Test 1: Initialization
            testInitialization(surfaceView);
            
            // Test 2: Video loading
            testVideoLoading(testVideoUri);
            
            // Wait for video to load
            waitForVideoLoad();
            
            // Test 3: Frame rendering
            testFrameRendering();
            
            // Test 4: Seeking performance
            testSeekingPerformance();
            
            // Test 5: Playback control
            testPlaybackControl();
            
            Log.d(TAG, "All tests completed: " + testResults.toString());
            
        } catch (Exception e) {
            Log.e(TAG, "Test failed with exception: " + e.getMessage(), e);
            testResults.lastError = e.getMessage();
        } finally {
            cleanup();
        }
        
        return testResults;
    }
    
    private void setupTestListener() {
        controller.setVideoControllerListener(new VideoControllerListener() {
            @Override
            public void onVideoLoaded(VideoMetadata metadata) {
                Log.d(TAG, "Test: Video loaded - " + metadata.getWidth() + "x" + metadata.getHeight());
                testResults.videoLoadedSuccessfully = true;
                
                // Validate metadata
                if (metadata.getWidth() > 0 && metadata.getHeight() > 0 && metadata.getDuration() > 0) {
                    Log.d(TAG, "Test: Video metadata validation passed");
                } else {
                    Log.e(TAG, "Test: Invalid video metadata");
                    testResults.lastError = "Invalid video metadata";
                }
            }
            
            @Override
            public void onFrameRendered(long frameNumber, long timestampUs) {
                testResults.framesRendered++;
                testResults.frameRenderingWorking = true;
                Log.v(TAG, "Test: Frame rendered #" + frameNumber + " at " + (timestampUs / 1000) + "ms");
            }
            
            @Override
            public void onPlaybackStateChanged(boolean isPlaying) {
                Log.d(TAG, "Test: Playback state changed to " + isPlaying);
                testResults.playbackControlWorking = true;
            }
            
            @Override
            public void onSeekCompleted(long positionMs) {
                long seekEndTime = System.currentTimeMillis();
                long seekDuration = seekEndTime - seekStartTime;
                
                Log.d(TAG, "Test: Seek completed to " + positionMs + "ms in " + seekDuration + "ms");
                
                // Update average seek time
                seekTimes[seekTestCount] = seekDuration;
                seekTestCount++;
                
                if (seekDuration < 50) {
                    testResults.seekingWorking = true;
                }
                
                // Calculate average
                long total = 0;
                for (int i = 0; i < seekTestCount; i++) {
                    total += seekTimes[i];
                }
                testResults.averageSeekTime = total / seekTestCount;
            }
            
            @Override
            public void onPositionChanged(long positionMs) {
                // Position updates working
            }
            
            @Override
            public void onError(String error) {
                Log.e(TAG, "Test: Controller error - " + error);
                testResults.lastError = error;
            }
            
            @Override
            public void onInitializationComplete() {
                Log.d(TAG, "Test: Initialization complete");
            }
        });
    }
    
    private void testInitialization(GLSurfaceView surfaceView) {
        Log.d(TAG, "Test: Testing initialization");
        
        try {
            controller.initialize(surfaceView);
            Log.d(TAG, "Test: Initialization successful");
        } catch (Exception e) {
            Log.e(TAG, "Test: Initialization failed", e);
            testResults.lastError = "Initialization failed: " + e.getMessage();
        }
    }
    
    private void testVideoLoading(Uri videoUri) {
        Log.d(TAG, "Test: Testing video loading");
        
        try {
            controller.loadVideo(videoUri);
            Log.d(TAG, "Test: Video loading initiated");
        } catch (Exception e) {
            Log.e(TAG, "Test: Video loading failed", e);
            testResults.lastError = "Video loading failed: " + e.getMessage();
        }
    }
    
    private void waitForVideoLoad() {
        Log.d(TAG, "Test: Waiting for video to load");
        
        int attempts = 0;
        while (!testResults.videoLoadedSuccessfully && attempts < 50) {
            try {
                Thread.sleep(100);
                attempts++;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        
        if (!testResults.videoLoadedSuccessfully) {
            testResults.lastError = "Video loading timeout";
        }
    }
    
    private void testFrameRendering() {
        Log.d(TAG, "Test: Testing frame rendering");
        
        if (!testResults.videoLoadedSuccessfully) {
            Log.w(TAG, "Test: Skipping frame rendering test - video not loaded");
            return;
        }
        
        // Start playback to trigger frame rendering
        controller.play();
        
        // Wait for frames to render
        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        
        controller.pause();
        
        if (testResults.framesRendered > 0) {
            Log.d(TAG, "Test: Frame rendering successful - " + testResults.framesRendered + " frames");
        } else {
            Log.e(TAG, "Test: No frames rendered");
            testResults.lastError = "No frames rendered";
        }
    }
    
    // Seek timing variables
    private long seekStartTime = 0;
    private long[] seekTimes = new long[10];
    private int seekTestCount = 0;
    
    private void testSeekingPerformance() {
        Log.d(TAG, "Test: Testing seeking performance (<50ms requirement)");
        
        if (!testResults.videoLoadedSuccessfully) {
            Log.w(TAG, "Test: Skipping seek test - video not loaded");
            return;
        }
        
        // Test multiple seeks to get average performance
        long[] testPositions = {1000, 5000, 10000, 3000, 7000};
        
        for (long position : testPositions) {
            if (seekTestCount >= seekTimes.length) break;
            
            Log.d(TAG, "Test: Seeking to " + position + "ms");
            seekStartTime = System.currentTimeMillis();
            controller.seekToTime(position);
            
            // Wait for seek to complete
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        
        // Test frame-accurate seeking
        Log.d(TAG, "Test: Testing frame-accurate seeking");
        seekStartTime = System.currentTimeMillis();
        controller.seekToFrame(150);
        
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
    
    private void testPlaybackControl() {
        Log.d(TAG, "Test: Testing playback control");
        
        if (!testResults.videoLoadedSuccessfully) {
            Log.w(TAG, "Test: Skipping playback test - video not loaded");
            return;
        }
        
        try {
            // Test play
            controller.play();
            Thread.sleep(500);
            
            // Test pause
            controller.pause();
            Thread.sleep(200);
            
            // Test position queries
            long position = controller.getCurrentPosition();
            long duration = controller.getDuration();
            long frame = controller.getCurrentFrame();
            
            Log.d(TAG, "Test: Position=" + position + "ms, Duration=" + duration + "ms, Frame=" + frame);
            
            if (duration > 0) {
                testResults.playbackControlWorking = true;
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Test: Playback control test failed", e);
            testResults.lastError = "Playback control failed: " + e.getMessage();
        }
    }
    
    private void cleanup() {
        Log.d(TAG, "Test: Cleaning up");
        
        if (controller != null) {
            controller.release();
            controller = null;
        }
    }
    
    /**
     * Quick validation test for basic functionality
     */
    public static boolean quickValidationTest(Context context, GLSurfaceView surfaceView, Uri videoUri) {
        Log.d(TAG, "Running quick validation test");
        
        OpenGLVideoControllerTest test = new OpenGLVideoControllerTest();
        TestResults results = test.runTests(context, surfaceView, videoUri);
        
        boolean passed = results.allTestsPassed();
        Log.d(TAG, "Quick validation test " + (passed ? "PASSED" : "FAILED"));
        Log.d(TAG, "Results: " + results.toString());
        
        return passed;
    }
}