package com.fadcam.ui.faditor.processors.opengl;

import com.fadcam.Log;
import com.fadcam.ui.faditor.models.VideoMetadata;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Specialized test listener for timeline scrubbing performance validation.
 * Tracks frame rendering rates and timing for 60fps scrubbing tests.
 */
public class ScrubTestListener implements VideoControllerListener {
    private static final String TAG = "ScrubTestListener";
    
    // Synchronization
    private CountDownLatch initializationLatch = new CountDownLatch(1);
    private CountDownLatch videoLoadLatch = new CountDownLatch(1);
    
    // State tracking
    private final AtomicBoolean isInitialized = new AtomicBoolean(false);
    private final AtomicBoolean isVideoLoaded = new AtomicBoolean(false);
    
    // Frame rendering tracking
    private final AtomicInteger renderedFrameCount = new AtomicInteger(0);
    private final AtomicLong firstFrameTime = new AtomicLong(-1);
    private final AtomicLong lastFrameTime = new AtomicLong(-1);
    private final AtomicLong totalRenderingTime = new AtomicLong(0);
    
    // Performance metrics
    private final AtomicInteger droppedFrames = new AtomicInteger(0);
    private final AtomicLong maxFrameInterval = new AtomicLong(0);
    private final AtomicLong minFrameInterval = new AtomicLong(Long.MAX_VALUE);
    private final AtomicLong totalFrameIntervals = new AtomicLong(0);
    
    // Frame timing tracking
    private long previousFrameTime = -1;
    private final Object frameTimingLock = new Object();
    
    @Override
    public void onVideoLoaded(VideoMetadata metadata) {
        Log.d(TAG, "Scrub test - Video loaded: " + metadata.getWidth() + "x" + metadata.getHeight());
        isVideoLoaded.set(true);
        videoLoadLatch.countDown();
    }
    
    @Override
    public void onFrameRendered(long frameNumber, long timestampUs) {
        long currentTime = System.nanoTime();
        
        synchronized (frameTimingLock) {
            int frameCount = renderedFrameCount.incrementAndGet();
            
            // Track first and last frame times
            if (firstFrameTime.get() == -1) {
                firstFrameTime.set(currentTime);
            }
            lastFrameTime.set(currentTime);
            
            // Calculate frame intervals for performance analysis
            if (previousFrameTime != -1) {
                long frameInterval = currentTime - previousFrameTime;
                long intervalMs = frameInterval / 1000000; // Convert to milliseconds
                
                // Update interval statistics
                if (intervalMs > maxFrameInterval.get()) {
                    maxFrameInterval.set(intervalMs);
                }
                if (intervalMs < minFrameInterval.get()) {
                    minFrameInterval.set(intervalMs);
                }
                totalFrameIntervals.addAndGet(intervalMs);
                
                // Detect dropped frames (intervals > 20ms indicate dropped frames at 60fps)
                if (intervalMs > 20) {
                    droppedFrames.incrementAndGet();
                    Log.w(TAG, "Potential dropped frame detected - interval: " + intervalMs + "ms");
                }
            }
            
            previousFrameTime = currentTime;
            
            Log.v(TAG, "Frame rendered #" + frameNumber + " (total: " + frameCount + ")");
        }
    }
    
    @Override
    public void onPlaybackStateChanged(boolean isPlaying) {
        Log.d(TAG, "Scrub test - Playback state: " + isPlaying);
    }
    
    @Override
    public void onSeekCompleted(long positionMs) {
        Log.v(TAG, "Scrub test - Seek completed: " + positionMs + "ms");
    }
    
    @Override
    public void onPositionChanged(long positionMs) {
        // Position updates during scrubbing
    }
    
    @Override
    public void onError(String error) {
        Log.e(TAG, "Scrub test error: " + error);
        initializationLatch.countDown();
        videoLoadLatch.countDown();
    }
    
    @Override
    public void onInitializationComplete() {
        Log.d(TAG, "Scrub test - Initialization complete");
        isInitialized.set(true);
        initializationLatch.countDown();
    }
    
    // Test synchronization methods
    
    public boolean waitForInitialization(long timeoutMs) {
        try {
            return initializationLatch.await(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
    
    public boolean waitForVideoLoad(long timeoutMs) {
        try {
            return videoLoadLatch.await(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
    
    // Performance metrics methods
    
    public int getRenderedFrameCount() {
        return renderedFrameCount.get();
    }
    
    public int getDroppedFrameCount() {
        return droppedFrames.get();
    }
    
    public float getAverageFrameRate() {
        long firstFrame = firstFrameTime.get();
        long lastFrame = lastFrameTime.get();
        int frameCount = renderedFrameCount.get();
        
        if (firstFrame == -1 || lastFrame == -1 || frameCount <= 1) {
            return 0.0f;
        }
        
        long totalTimeNs = lastFrame - firstFrame;
        long totalTimeMs = totalTimeNs / 1000000;
        
        if (totalTimeMs == 0) {
            return 0.0f;
        }
        
        return (frameCount * 1000.0f) / totalTimeMs;
    }
    
    public float getFrameRenderingEfficiency() {
        int totalFrames = renderedFrameCount.get();
        int droppedFrameCount = droppedFrames.get();
        
        if (totalFrames == 0) {
            return 0.0f;
        }
        
        return (float) (totalFrames - droppedFrameCount) / totalFrames;
    }
    
    public long getAverageFrameInterval() {
        int frameCount = renderedFrameCount.get();
        if (frameCount <= 1) {
            return 0;
        }
        
        return totalFrameIntervals.get() / (frameCount - 1);
    }
    
    public long getMaxFrameInterval() {
        return maxFrameInterval.get();
    }
    
    public long getMinFrameInterval() {
        return minFrameInterval.get();
    }
    
    public boolean isTargetFrameRateAchieved(float targetFps) {
        float actualFps = getAverageFrameRate();
        return actualFps >= targetFps;
    }
    
    public boolean isSmoothScrubbing() {
        // Consider scrubbing smooth if:
        // 1. Frame rate is at least 50fps
        // 2. Less than 5% dropped frames
        // 3. Max frame interval is less than 25ms
        
        float fps = getAverageFrameRate();
        float efficiency = getFrameRenderingEfficiency();
        long maxInterval = getMaxFrameInterval();
        
        return fps >= 50.0f && efficiency >= 0.95f && maxInterval <= 25;
    }
    
    // Reset methods for multiple test runs
    
    public void resetMetrics() {
        synchronized (frameTimingLock) {
            renderedFrameCount.set(0);
            droppedFrames.set(0);
            firstFrameTime.set(-1);
            lastFrameTime.set(-1);
            maxFrameInterval.set(0);
            minFrameInterval.set(Long.MAX_VALUE);
            totalFrameIntervals.set(0);
            previousFrameTime = -1;
        }
    }
    
    public void resetAll() {
        initializationLatch = new CountDownLatch(1);
        videoLoadLatch = new CountDownLatch(1);
        isInitialized.set(false);
        isVideoLoaded.set(false);
        resetMetrics();
    }
    
    // Detailed performance report
    
    public String getPerformanceReport() {
        StringBuilder report = new StringBuilder();
        report.append("=== Scrubbing Performance Report ===\n");
        report.append("Rendered Frames: ").append(getRenderedFrameCount()).append("\n");
        report.append("Dropped Frames: ").append(getDroppedFrameCount()).append("\n");
        report.append("Average FPS: ").append(String.format("%.2f", getAverageFrameRate())).append("\n");
        report.append("Rendering Efficiency: ").append(String.format("%.1f%%", getFrameRenderingEfficiency() * 100)).append("\n");
        report.append("Average Frame Interval: ").append(getAverageFrameInterval()).append("ms\n");
        report.append("Max Frame Interval: ").append(getMaxFrameInterval()).append("ms\n");
        report.append("Min Frame Interval: ").append(getMinFrameInterval()).append("ms\n");
        report.append("Smooth Scrubbing: ").append(isSmoothScrubbing() ? "YES" : "NO").append("\n");
        return report.toString();
    }
}