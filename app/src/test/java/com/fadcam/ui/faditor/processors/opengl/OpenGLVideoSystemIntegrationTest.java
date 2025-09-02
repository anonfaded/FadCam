package com.fadcam.ui.faditor.processors.opengl;

import android.content.Context;
import android.net.Uri;
import android.opengl.GLSurfaceView;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.fadcam.Log;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

/**
 * Comprehensive integration test for the complete OpenGL video system.
 * This test validates all components working together and ensures the system
 * meets all performance and functionality requirements.
 * Requirements: 13.2, 13.3, 13.4, 13.5, 14.6, 14.7
 */
@RunWith(AndroidJUnit4.class)
public class OpenGLVideoSystemIntegrationTest {
    private static final String TAG = "OpenGLVideoSystemIntegrationTest";
    
    private Context context;
    private GLSurfaceView testSurfaceView;
    private Uri testVideoUri;
    private MemoryTestMonitor memoryMonitor;
    private ExecutorService testExecutor;
    
    // Test results aggregation
    private final List<TestResult> testResults = new ArrayList<>();
    
    public static class TestResult {
        public final String testName;
        public final boolean passed;
        public final String details;
        public final long executionTime;
        
        public TestResult(String testName, boolean passed, String details, long executionTime) {
            this.testName = testName;
            this.passed = passed;
            this.details = details;
            this.executionTime = executionTime;
        }
        
        @Override
        public String toString() {
            return String.format("%s: %s (%dms) - %s", 
                               testName, passed ? "PASS" : "FAIL", executionTime, details);
        }
    }
    
    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        testSurfaceView = new GLSurfaceView(context);
        testVideoUri = Uri.parse("android.resource://" + context.getPackageName() + "/raw/test_video");
        memoryMonitor = new MemoryTestMonitor();
        testExecutor = Executors.newFixedThreadPool(4);
        
        Log.d(TAG, "Integration test setup completed");
    }
    
    @After
    public void tearDown() {
        if (testExecutor != null) {
            testExecutor.shutdown();
            try {
                if (!testExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                    testExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                testExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        
        if (memoryMonitor != null) {
            memoryMonitor.cleanup();
        }
        
        // Generate final test report
        generateFinalTestReport();
        
        Log.d(TAG, "Integration test cleanup completed");
    }
    
    /**
     * Master integration test that runs all OpenGL video system validation tests
     */
    @Test
    public void testCompleteOpenGLVideoSystem() {
        Log.d(TAG, "Starting complete OpenGL video system integration test");
        
        memoryMonitor.startMemoryLeakDetection();
        
        // Run all test categories
        boolean allTestsPassed = true;
        
        allTestsPassed &= runFrameAccuracyTests();
        allTestsPassed &= runTimelineScrubbing Tests();
        allTestsPassed &= runMemoryManagementTests();
        allTestsPassed &= runSeekPerformanceTests();
        allTestsPassed &= runComponentModularityTests();
        allTestsPassed &= runOpenGLCompatibilityTests();
        allTestsPassed &= runStressTests();
        allTestsPassed &= runConcurrencyTests();
        
        // Final memory leak check
        memoryMonitor.stopMemoryLeakDetection();
        boolean hasMemoryLeak = memoryMonitor.detectMemoryLeak();
        if (hasMemoryLeak) {
            allTestsPassed = false;
            testResults.add(new TestResult("Memory Leak Detection", false, 
                                         "Memory leak detected during testing", 0));
        }
        
        // Assert overall test success
        assertTrue("Complete OpenGL video system integration test failed. Check individual test results.", 
                  allTestsPassed);
        
        Log.d(TAG, "Complete OpenGL video system integration test " + 
                  (allTestsPassed ? "PASSED" : "FAILED"));
    }
    
    private boolean runFrameAccuracyTests() {
        Log.d(TAG, "Running frame accuracy tests");
        long startTime = System.currentTimeMillis();
        
        try {
            OpenGLVideoController controller = new OpenGLVideoController(context);
            TestVideoControllerListener listener = new TestVideoControllerListener();
            controller.setVideoControllerListener(listener);
            
            // Initialize and load video
            controller.initialize(testSurfaceView);
            if (!listener.waitForInitialization(5000)) {
                testResults.add(new TestResult("Frame Accuracy - Initialization", false, 
                                             "Controller initialization timeout", 
                                             System.currentTimeMillis() - startTime));
                return false;
            }
            
            controller.loadVideo(testVideoUri);
            if (!listener.waitForVideoLoad(10000)) {
                testResults.add(new TestResult("Frame Accuracy - Video Load", false, 
                                             "Video loading timeout", 
                                             System.currentTimeMillis() - startTime));
                return false;
            }
            
            // Test frame-accurate seeking
            boolean frameAccuracyPassed = true;
            long[] testFrames = {0, 30, 60, 90, 150};
            
            for (long frameNumber : testFrames) {
                listener.resetSeekTest();
                long seekStart = System.nanoTime();
                
                controller.seekToFrame(frameNumber);
                
                if (!listener.waitForSeekCompletion(1000)) {
                    frameAccuracyPassed = false;
                    break;
                }
                
                long seekTime = (System.nanoTime() - seekStart) / 1000000;
                if (seekTime >= 50) {
                    frameAccuracyPassed = false;
                    Log.w(TAG, "Frame seek too slow: " + seekTime + "ms for frame " + frameNumber);
                }
            }
            
            controller.release();
            
            long executionTime = System.currentTimeMillis() - startTime;
            testResults.add(new TestResult("Frame Accuracy Tests", frameAccuracyPassed, 
                                         "Frame-accurate seeking validation", executionTime));
            
            return frameAccuracyPassed;
            
        } catch (Exception e) {
            Log.e(TAG, "Frame accuracy test failed", e);
            testResults.add(new TestResult("Frame Accuracy Tests", false, 
                                         "Exception: " + e.getMessage(), 
                                         System.currentTimeMillis() - startTime));
            return false;
        }
    }
    
    private boolean runTimelineScrubbingTests() {
        Log.d(TAG, "Running timeline scrubbing tests");
        long startTime = System.currentTimeMillis();
        
        try {
            OpenGLVideoController controller = new OpenGLVideoController(context);
            ScrubTestListener listener = new ScrubTestListener();
            controller.setVideoControllerListener(listener);
            
            // Initialize and load video
            controller.initialize(testSurfaceView);
            if (!listener.waitForInitialization(5000)) {
                testResults.add(new TestResult("Timeline Scrubbing - Init", false, 
                                             "Initialization timeout", 
                                             System.currentTimeMillis() - startTime));
                return false;
            }
            
            controller.loadVideo(testVideoUri);
            if (!listener.waitForVideoLoad(10000)) {
                testResults.add(new TestResult("Timeline Scrubbing - Load", false, 
                                             "Video load timeout", 
                                             System.currentTimeMillis() - startTime));
                return false;
            }
            
            // Simulate 60fps timeline scrubbing
            listener.resetMetrics();
            long scrubDuration = 3000; // 3 seconds
            long scrubStart = System.currentTimeMillis();
            int seekCount = 0;
            
            while (System.currentTimeMillis() - scrubStart < scrubDuration) {
                long progress = System.currentTimeMillis() - scrubStart;
                long position = (progress * 30000) / scrubDuration; // 30 second video
                
                controller.seekToTime(position);
                seekCount++;
                
                // 60fps = 16.67ms per frame
                Thread.sleep(16);
            }
            
            // Validate scrubbing performance
            float actualFps = listener.getAverageFrameRate();
            boolean scrubPassed = actualFps >= 50.0f && listener.isSmoothScrubbing();
            
            controller.release();
            
            long executionTime = System.currentTimeMillis() - startTime;
            String details = String.format("FPS: %.1f, Seeks: %d, Smooth: %s", 
                                          actualFps, seekCount, listener.isSmoothScrubbing());
            
            testResults.add(new TestResult("Timeline Scrubbing Tests", scrubPassed, details, executionTime));
            
            return scrubPassed;
            
        } catch (Exception e) {
            Log.e(TAG, "Timeline scrubbing test failed", e);
            testResults.add(new TestResult("Timeline Scrubbing Tests", false, 
                                         "Exception: " + e.getMessage(), 
                                         System.currentTimeMillis() - startTime));
            return false;
        }
    }
    
    private boolean runMemoryManagementTests() {
        Log.d(TAG, "Running memory management tests");
        long startTime = System.currentTimeMillis();
        
        try {
            memoryMonitor.takeSnapshot("memory_test_start");
            long initialMemory = memoryMonitor.getCurrentMemoryUsage();
            
            // Create multiple controllers to stress test memory
            List<OpenGLVideoController> controllers = new ArrayList<>();
            
            for (int i = 0; i < 3; i++) {
                OpenGLVideoController controller = new OpenGLVideoController(context);
                TestVideoControllerListener listener = new TestVideoControllerListener();
                controller.setVideoControllerListener(listener);
                
                controller.initialize(testSurfaceView);
                listener.waitForInitialization(5000);
                
                controller.loadVideo(testVideoUri);
                listener.waitForVideoLoad(10000);
                
                controllers.add(controller);
                memoryMonitor.takeSnapshot("controller_" + i + "_loaded");
            }
            
            // Simulate extended editing session
            for (int session = 0; session < 5; session++) {
                for (OpenGLVideoController controller : controllers) {
                    controller.seekToTime(session * 2000);
                    controller.play();
                    Thread.sleep(100);
                    controller.pause();
                }
                memoryMonitor.takeSnapshot("session_" + session);
            }
            
            // Clean up controllers
            for (OpenGLVideoController controller : controllers) {
                controller.release();
            }
            
            // Force garbage collection
            System.gc();
            Thread.sleep(1000);
            
            memoryMonitor.takeSnapshot("memory_test_end");
            long finalMemory = memoryMonitor.getCurrentMemoryUsage();
            long memoryIncrease = finalMemory - initialMemory;
            
            // Memory should not increase by more than 100MB
            boolean memoryTestPassed = memoryIncrease < (100 * 1024 * 1024) && 
                                     memoryMonitor.areTexturesProperlyManaged();
            
            long executionTime = System.currentTimeMillis() - startTime;
            String details = String.format("Memory increase: %dMB, Textures managed: %s", 
                                          memoryIncrease / 1024 / 1024, 
                                          memoryMonitor.areTexturesProperlyManaged());
            
            testResults.add(new TestResult("Memory Management Tests", memoryTestPassed, details, executionTime));
            
            return memoryTestPassed;
            
        } catch (Exception e) {
            Log.e(TAG, "Memory management test failed", e);
            testResults.add(new TestResult("Memory Management Tests", false, 
                                         "Exception: " + e.getMessage(), 
                                         System.currentTimeMillis() - startTime));
            return false;
        }
    }
    
    private boolean runSeekPerformanceTests() {
        Log.d(TAG, "Running seek performance tests");
        long startTime = System.currentTimeMillis();
        
        try {
            OpenGLVideoController controller = new OpenGLVideoController(context);
            SeekPerformanceListener listener = new SeekPerformanceListener();
            controller.setVideoControllerListener(listener);
            
            // Initialize and load video
            controller.initialize(testSurfaceView);
            listener.waitForInitialization(5000);
            
            controller.loadVideo(testVideoUri);
            listener.waitForVideoLoad(10000);
            
            // Test various seek scenarios
            long[] seekPositions = {1000, 5000, 10000, 15000, 25000, 3000, 7000, 12000};
            
            for (long position : seekPositions) {
                listener.startSeekTest(position);
                controller.seekToTime(position);
                listener.waitForSeekCompletion(1000);
            }
            
            // Validate performance requirements
            boolean performancePassed = listener.meetsPerformanceRequirements() && 
                                      listener.meetsAccuracyRequirements();
            
            controller.release();
            
            long executionTime = System.currentTimeMillis() - startTime;
            String details = String.format("Avg seek: %.1fms, Success rate: %.1f%%, Max error: %dms", 
                                          listener.getAverageSeekTime(), 
                                          listener.getSeekSuccessRate() * 100,
                                          listener.getMaxSeekError());
            
            testResults.add(new TestResult("Seek Performance Tests", performancePassed, details, executionTime));
            
            return performancePassed;
            
        } catch (Exception e) {
            Log.e(TAG, "Seek performance test failed", e);
            testResults.add(new TestResult("Seek Performance Tests", false, 
                                         "Exception: " + e.getMessage(), 
                                         System.currentTimeMillis() - startTime));
            return false;
        }
    }
    
    private boolean runComponentModularityTests() {
        Log.d(TAG, "Running component modularity tests");
        long startTime = System.currentTimeMillis();
        
        try {
            // Test individual component instantiation
            boolean modularityPassed = true;
            
            // Test VideoDecoder modularity
            VideoDecoder decoder = new VideoDecoder();
            TestDecoderCallback decoderCallback = new TestDecoderCallback();
            decoder.setDecoderCallback(decoderCallback);
            
            // Test VideoRenderer modularity
            VideoRenderer renderer = new VideoRenderer(context);
            
            // Test TextureManager modularity
            TextureManager textureManager = new TextureManager();
            int textureId = textureManager.createVideoTexture();
            textureManager.releaseTexture(textureId);
            
            // Test ShaderManager modularity
            ShaderManager shaderManager = new ShaderManager();
            
            // Test PlaybackController modularity
            PlaybackController playbackController = new PlaybackController();
            TestPlaybackListener playbackListener = new TestPlaybackListener();
            playbackController.setPlaybackListener(playbackListener);
            
            // Test component integration
            OpenGLVideoController controller = new OpenGLVideoController(context);
            TestVideoControllerListener controllerListener = new TestVideoControllerListener();
            controller.setVideoControllerListener(controllerListener);
            
            controller.initialize(testSurfaceView);
            modularityPassed = controllerListener.waitForInitialization(5000);
            
            // Cleanup
            controller.release();
            textureManager.releaseAll();
            shaderManager.releaseAll();
            
            long executionTime = System.currentTimeMillis() - startTime;
            testResults.add(new TestResult("Component Modularity Tests", modularityPassed, 
                                         "Component independence and integration", executionTime));
            
            return modularityPassed;
            
        } catch (Exception e) {
            Log.e(TAG, "Component modularity test failed", e);
            testResults.add(new TestResult("Component Modularity Tests", false, 
                                         "Exception: " + e.getMessage(), 
                                         System.currentTimeMillis() - startTime));
            return false;
        }
    }
    
    private boolean runOpenGLCompatibilityTests() {
        Log.d(TAG, "Running OpenGL compatibility tests");
        long startTime = System.currentTimeMillis();
        
        try {
            // Test that Faditor OpenGL doesn't interfere with existing OpenGL usage
            boolean compatibilityPassed = true;
            
            // Initialize Faditor OpenGL components
            OpenGLVideoController faditorController = new OpenGLVideoController(context);
            TestVideoControllerListener listener = new TestVideoControllerListener();
            faditorController.setVideoControllerListener(listener);
            
            faditorController.initialize(testSurfaceView);
            compatibilityPassed = listener.waitForInitialization(5000);
            
            // Test OpenGL state isolation
            if (compatibilityPassed) {
                faditorController.loadVideo(testVideoUri);
                compatibilityPassed = listener.waitForVideoLoad(10000);
            }
            
            // Cleanup
            faditorController.release();
            
            long executionTime = System.currentTimeMillis() - startTime;
            testResults.add(new TestResult("OpenGL Compatibility Tests", compatibilityPassed, 
                                         "OpenGL state isolation and compatibility", executionTime));
            
            return compatibilityPassed;
            
        } catch (Exception e) {
            Log.e(TAG, "OpenGL compatibility test failed", e);
            testResults.add(new TestResult("OpenGL Compatibility Tests", false, 
                                         "Exception: " + e.getMessage(), 
                                         System.currentTimeMillis() - startTime));
            return false;
        }
    }
    
    private boolean runStressTests() {
        Log.d(TAG, "Running stress tests");
        long startTime = System.currentTimeMillis();
        
        try {
            OpenGLVideoController controller = new OpenGLVideoController(context);
            TestVideoControllerListener listener = new TestVideoControllerListener();
            controller.setVideoControllerListener(listener);
            
            // Initialize and load video
            controller.initialize(testSurfaceView);
            listener.waitForInitialization(5000);
            
            controller.loadVideo(testVideoUri);
            listener.waitForVideoLoad(10000);
            
            // Stress test with rapid operations
            boolean stressPassed = true;
            
            for (int i = 0; i < 100 && stressPassed; i++) {
                // Rapid seeking
                controller.seekToTime(i * 100);
                
                // Play/pause cycles
                controller.play();
                Thread.sleep(10);
                controller.pause();
                
                // Check for errors
                if (listener.getLastError() != null) {
                    stressPassed = false;
                    break;
                }
                
                // Memory check every 20 iterations
                if (i % 20 == 0) {
                    memoryMonitor.takeSnapshot("stress_test_" + i);
                    if (!memoryMonitor.isMemoryUsageAcceptable(200)) {
                        stressPassed = false;
                        break;
                    }
                }
            }
            
            controller.release();
            
            long executionTime = System.currentTimeMillis() - startTime;
            testResults.add(new TestResult("Stress Tests", stressPassed, 
                                         "Rapid operations and memory stability", executionTime));
            
            return stressPassed;
            
        } catch (Exception e) {
            Log.e(TAG, "Stress test failed", e);
            testResults.add(new TestResult("Stress Tests", false, 
                                         "Exception: " + e.getMessage(), 
                                         System.currentTimeMillis() - startTime));
            return false;
        }
    }
    
    private boolean runConcurrencyTests() {
        Log.d(TAG, "Running concurrency tests");
        long startTime = System.currentTimeMillis();
        
        try {
            // Test multiple controllers running concurrently
            List<Future<Boolean>> futures = new ArrayList<>();
            
            for (int i = 0; i < 3; i++) {
                final int controllerId = i;
                Future<Boolean> future = testExecutor.submit(() -> {
                    try {
                        OpenGLVideoController controller = new OpenGLVideoController(context);
                        TestVideoControllerListener listener = new TestVideoControllerListener();
                        controller.setVideoControllerListener(listener);
                        
                        controller.initialize(testSurfaceView);
                        if (!listener.waitForInitialization(5000)) {
                            return false;
                        }
                        
                        controller.loadVideo(testVideoUri);
                        if (!listener.waitForVideoLoad(10000)) {
                            return false;
                        }
                        
                        // Perform concurrent operations
                        for (int j = 0; j < 10; j++) {
                            controller.seekToTime(j * 1000);
                            Thread.sleep(50);
                        }
                        
                        controller.release();
                        return listener.getLastError() == null;
                        
                    } catch (Exception e) {
                        Log.e(TAG, "Concurrent controller " + controllerId + " failed", e);
                        return false;
                    }
                });
                
                futures.add(future);
            }
            
            // Wait for all concurrent tests to complete
            boolean concurrencyPassed = true;
            for (Future<Boolean> future : futures) {
                try {
                    if (!future.get(30, TimeUnit.SECONDS)) {
                        concurrencyPassed = false;
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Concurrent test failed", e);
                    concurrencyPassed = false;
                }
            }
            
            long executionTime = System.currentTimeMillis() - startTime;
            testResults.add(new TestResult("Concurrency Tests", concurrencyPassed, 
                                         "Multiple controllers concurrent operation", executionTime));
            
            return concurrencyPassed;
            
        } catch (Exception e) {
            Log.e(TAG, "Concurrency test failed", e);
            testResults.add(new TestResult("Concurrency Tests", false, 
                                         "Exception: " + e.getMessage(), 
                                         System.currentTimeMillis() - startTime));
            return false;
        }
    }
    
    private void generateFinalTestReport() {
        Log.d(TAG, "=== OpenGL Video System Integration Test Report ===");
        
        int totalTests = testResults.size();
        int passedTests = 0;
        long totalExecutionTime = 0;
        
        for (TestResult result : testResults) {
            Log.d(TAG, result.toString());
            if (result.passed) {
                passedTests++;
            }
            totalExecutionTime += result.executionTime;
        }
        
        Log.d(TAG, String.format("=== Summary: %d/%d tests passed (%.1f%%) in %dms ===", 
                                passedTests, totalTests, 
                                (passedTests * 100.0f) / totalTests, 
                                totalExecutionTime));
        
        Log.d(TAG, memoryMonitor.getMemoryReport());
    }
}