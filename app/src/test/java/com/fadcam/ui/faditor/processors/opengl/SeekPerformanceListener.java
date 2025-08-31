package com.fadcam.ui.faditor.processors.opengl;

import com.fadcam.Log;
import com.fadcam.ui.faditor.models.VideoMetadata;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Specialized test listener for seek performance validation.
 * Tracks seek timing and accuracy for <50ms requirement validation.
 */
public class SeekPerformanceListener implements VideoControllerListener {
    private static final String TAG = "SeekPerformanceListener";
    
    // Synchronization
    private CountDownLatch initializationLatch = new CountDownLatch(1);
    private CountDownLatch videoLoadLatch = new CountDownLatch(1);
    private CountDownLatch seekCompletionLatch = new CountDownLatch(1);
    
    // State tracking
    private final AtomicBoolean isInitialized = new AtomicBoolean(false);
    private final AtomicBoolean isVideoLoaded = new AtomicBoolean(false);
    private final AtomicBoolean isSeekCompleted = new AtomicBoolean(false);
    
    // Seek performance tracking
    private final AtomicLong seekStartTime = new AtomicLong(-1);
    private final AtomicLong seekEndTime = new AtomicLong(-1);
    private final AtomicLong targetSeekPosition = new AtomicLong(-1);
    private final AtomicLong actualSeekPosition = new AtomicLong(-1);
    
    // Performance statistics
    private final List<Long> seekTimes = new ArrayList<>();
    private final List<Long> seekAccuracies = new ArrayList<>();
    private final AtomicLong totalSeeks = new AtomicLong(0);
    private final AtomicLong successfulSeeks = new AtomicLong(0);
    private final AtomicLong failedSeeks = new AtomicLong(0);
    
    // Accuracy tracking
    private final AtomicLong maxSeekError = new AtomicLong(0);
    private final AtomicLong totalSeekError = new AtomicLong(0);
    
    @Override
    public void onVideoLoaded(VideoMetadata metadata) {
        Log.d(TAG, "Seek test - Video loaded: " + metadata.getDuration() + "ms duration");
        isVideoLoaded.set(true);
        videoLoadLatch.countDown();
    }
    
    @Override
    public void onFrameRendered(long frameNumber, long timestampUs) {
        Log.v(TAG, "Seek test - Frame rendered: #" + frameNumber + " at " + (timestampUs / 1000) + "ms");
    }
    
    @Override
    public void onPlaybackStateChanged(boolean isPlaying) {
        Log.d(TAG, "Seek test - Playback state: " + isPlaying);
    }
    
    @Override
    public void onSeekCompleted(long positionMs) {
        long endTime = System.nanoTime();
        seekEndTime.set(endTime);
        actualSeekPosition.set(positionMs);
        isSeekCompleted.set(true);
        
        // Calculate seek performance metrics
        long startTime = seekStartTime.get();
        if (startTime != -1) {
            long seekDuration = (endTime - startTime) / 1000000; // Convert to milliseconds
            seekTimes.add(seekDuration);
            
            // Calculate seek accuracy
            long targetPos = targetSeekPosition.get();
            if (targetPos != -1) {
                long seekError = Math.abs(positionMs - targetPos);
                seekAccuracies.add(seekError);
                totalSeekError.addAndGet(seekError);
                
                if (seekError > maxSeekError.get()) {
                    maxSeekError.set(seekError);
                }
                
                Log.d(TAG, "Seek completed - Duration: " + seekDuration + "ms, " +
                          "Target: " + targetPos + "ms, Actual: " + positionMs + "ms, " +
                          "Error: " + seekError + "ms");
                
                if (seekDuration <= 50 && seekError <= 100) { // 100ms accuracy tolerance
                    successfulSeeks.incrementAndGet();
                } else {
                    failedSeeks.incrementAndGet();
                    Log.w(TAG, "Seek performance issue - Duration: " + seekDuration + 
                              "ms, Error: " + seekError + "ms");
                }
            }
            
            totalSeeks.incrementAndGet();
        }
        
        seekCompletionLatch.countDown();
    }
    
    @Override
    public void onPositionChanged(long positionMs) {
        // Position updates during seeking
    }
    
    @Override
    public void onError(String error) {
        Log.e(TAG, "Seek test error: " + error);
        failedSeeks.incrementAndGet();
        
        // Count down latches to prevent hanging
        initializationLatch.countDown();
        videoLoadLatch.countDown();
        seekCompletionLatch.countDown();
    }
    
    @Override
    public void onInitializationComplete() {
        Log.d(TAG, "Seek test - Initialization complete");
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
    
    public boolean waitForSeekCompletion(long timeoutMs) {
        try {
            return seekCompletionLatch.await(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
    
    // Seek test control methods
    
    public void startSeekTest(long targetPositionMs) {
        seekStartTime.set(System.nanoTime());
        targetSeekPosition.set(targetPositionMs);
        actualSeekPosition.set(-1);
        isSeekCompleted.set(false);
        seekCompletionLatch = new CountDownLatch(1);
    }
    
    public void resetSeekTest() {
        seekStartTime.set(-1);
        seekEndTime.set(-1);
        targetSeekPosition.set(-1);
        actualSeekPosition.set(-1);
        isSeekCompleted.set(false);
        seekCompletionLatch = new CountDownLatch(1);
    }
    
    // Performance metrics methods
    
    public long getLastSeekTime() {
        long start = seekStartTime.get();
        long end = seekEndTime.get();
        if (start != -1 && end != -1) {
            return (end - start) / 1000000; // Convert to milliseconds
        }
        return -1;
    }
    
    public long getLastSeekAccuracy() {
        long target = targetSeekPosition.get();
        long actual = actualSeekPosition.get();
        if (target != -1 && actual != -1) {
            return Math.abs(actual - target);
        }
        return -1;
    }
    
    public double getAverageSeekTime() {
        if (seekTimes.isEmpty()) {
            return 0.0;
        }
        return seekTimes.stream().mapToLong(Long::longValue).average().orElse(0.0);
    }
    
    public long getMaxSeekTime() {
        if (seekTimes.isEmpty()) {
            return 0;
        }
        return seekTimes.stream().mapToLong(Long::longValue).max().orElse(0);
    }
    
    public long getMinSeekTime() {
        if (seekTimes.isEmpty()) {
            return 0;
        }
        return seekTimes.stream().mapToLong(Long::longValue).min().orElse(0);
    }
    
    public double getAverageSeekAccuracy() {
        if (seekAccuracies.isEmpty()) {
            return 0.0;
        }
        return seekAccuracies.stream().mapToLong(Long::longValue).average().orElse(0.0);
    }
    
    public long getMaxSeekError() {
        return maxSeekError.get();
    }
    
    public float getSeekSuccessRate() {
        long total = totalSeeks.get();
        if (total == 0) {
            return 0.0f;
        }
        return (float) successfulSeeks.get() / total;
    }
    
    public boolean meetsPerformanceRequirements() {
        // Check if all seeks meet the <50ms requirement
        double avgSeekTime = getAverageSeekTime();
        long maxSeekTime = getMaxSeekTime();
        float successRate = getSeekSuccessRate();
        
        return avgSeekTime < 50.0 && maxSeekTime < 50 && successRate >= 0.95f;
    }
    
    public boolean meetsAccuracyRequirements() {
        // Check if seek accuracy is within acceptable bounds
        double avgAccuracy = getAverageSeekAccuracy();
        long maxError = getMaxSeekError();
        
        // Allow 100ms average accuracy and 500ms max error
        return avgAccuracy < 100.0 && maxError < 500;
    }
    
    // Statistics and reporting
    
    public int getTotalSeekCount() {
        return (int) totalSeeks.get();
    }
    
    public int getSuccessfulSeekCount() {
        return (int) successfulSeeks.get();
    }
    
    public int getFailedSeekCount() {
        return (int) failedSeeks.get();
    }
    
    public List<Long> getAllSeekTimes() {
        return new ArrayList<>(seekTimes);
    }
    
    public List<Long> getAllSeekAccuracies() {
        return new ArrayList<>(seekAccuracies);
    }
    
    // Reset methods
    
    public void resetStatistics() {
        seekTimes.clear();
        seekAccuracies.clear();
        totalSeeks.set(0);
        successfulSeeks.set(0);
        failedSeeks.set(0);
        maxSeekError.set(0);
        totalSeekError.set(0);
    }
    
    public void resetAll() {
        initializationLatch = new CountDownLatch(1);
        videoLoadLatch = new CountDownLatch(1);
        seekCompletionLatch = new CountDownLatch(1);
        
        isInitialized.set(false);
        isVideoLoaded.set(false);
        isSeekCompleted.set(false);
        
        resetSeekTest();
        resetStatistics();
    }
    
    // Detailed performance report
    
    public String getPerformanceReport() {
        StringBuilder report = new StringBuilder();
        report.append("=== Seek Performance Report ===\n");
        report.append("Total Seeks: ").append(getTotalSeekCount()).append("\n");
        report.append("Successful Seeks: ").append(getSuccessfulSeekCount()).append("\n");
        report.append("Failed Seeks: ").append(getFailedSeekCount()).append("\n");
        report.append("Success Rate: ").append(String.format("%.1f%%", getSeekSuccessRate() * 100)).append("\n");
        report.append("Average Seek Time: ").append(String.format("%.2f", getAverageSeekTime())).append("ms\n");
        report.append("Max Seek Time: ").append(getMaxSeekTime()).append("ms\n");
        report.append("Min Seek Time: ").append(getMinSeekTime()).append("ms\n");
        report.append("Average Accuracy: ").append(String.format("%.2f", getAverageSeekAccuracy())).append("ms\n");
        report.append("Max Seek Error: ").append(getMaxSeekError()).append("ms\n");
        report.append("Meets Performance Req: ").append(meetsPerformanceRequirements() ? "YES" : "NO").append("\n");
        report.append("Meets Accuracy Req: ").append(meetsAccuracyRequirements() ? "YES" : "NO").append("\n");
        return report.toString();
    }
}