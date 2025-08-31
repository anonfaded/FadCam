package com.fadcam.ui.faditor.processors.opengl;

import com.fadcam.Log;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Test implementation of DecoderCallback for validation testing.
 * Provides synchronization and state tracking for decoder testing scenarios.
 */
public class TestDecoderCallback implements DecoderCallback {
    private static final String TAG = "TestDecoderCallback";
    
    // Synchronization latches
    private CountDownLatch decoderReadyLatch = new CountDownLatch(1);
    private CountDownLatch frameAvailableLatch = new CountDownLatch(1);
    private CountDownLatch seekCompletedLatch = new CountDownLatch(1);
    private CountDownLatch decodingCompleteLatch = new CountDownLatch(1);
    
    // State tracking
    private final AtomicBoolean isDecoderReady = new AtomicBoolean(false);
    private final AtomicBoolean isFrameAvailable = new AtomicBoolean(false);
    private final AtomicBoolean isSeekCompleted = new AtomicBoolean(false);
    private final AtomicBoolean isDecodingComplete = new AtomicBoolean(false);
    
    // Data tracking
    private final AtomicInteger videoWidth = new AtomicInteger(0);
    private final AtomicInteger videoHeight = new AtomicInteger(0);
    private final AtomicLong videoDuration = new AtomicLong(0);
    private final AtomicLong lastFrameTime = new AtomicLong(-1);
    private final AtomicLong lastSeekTime = new AtomicLong(-1);
    private final AtomicInteger frameCount = new AtomicInteger(0);
    private final AtomicReference<String> lastError = new AtomicReference<>();
    
    // Performance tracking
    private long firstFrameTime = -1;
    private long lastFrameTimestamp = -1;
    private final AtomicLong totalDecodingTime = new AtomicLong(0);
    
    @Override
    public void onFrameAvailable(long presentationTimeUs) {
        long currentTime = System.currentTimeMillis();
        
        if (firstFrameTime == -1) {
            firstFrameTime = currentTime;
        }
        lastFrameTimestamp = currentTime;
        
        lastFrameTime.set(presentationTimeUs);
        frameCount.incrementAndGet();
        isFrameAvailable.set(true);
        
        Log.v(TAG, "Frame available: " + presentationTimeUs + "us (frame #" + frameCount.get() + ")");
        
        frameAvailableLatch.countDown();
    }
    
    @Override
    public void onDecodingComplete() {
        long endTime = System.currentTimeMillis();
        if (firstFrameTime != -1) {
            totalDecodingTime.set(endTime - firstFrameTime);
        }
        
        isDecodingComplete.set(true);
        Log.d(TAG, "Decoding complete - Total frames: " + frameCount.get() + 
                  ", Duration: " + totalDecodingTime.get() + "ms");
        
        decodingCompleteLatch.countDown();
    }
    
    @Override
    public void onError(String error) {
        Log.e(TAG, "Decoder error: " + error);
        lastError.set(error);
        
        // Count down all latches to prevent test hanging
        decoderReadyLatch.countDown();
        frameAvailableLatch.countDown();
        seekCompletedLatch.countDown();
        decodingCompleteLatch.countDown();
    }
    
    @Override
    public void onDecoderReady(int width, int height, long durationUs) {
        Log.d(TAG, "Decoder ready - Resolution: " + width + "x" + height + 
                  ", Duration: " + (durationUs / 1000000) + "s");
        
        videoWidth.set(width);
        videoHeight.set(height);
        videoDuration.set(durationUs);
        isDecoderReady.set(true);
        
        decoderReadyLatch.countDown();
    }
    
    @Override
    public void onSeekCompleted(long seekTimeUs) {
        Log.d(TAG, "Seek completed to: " + seekTimeUs + "us");
        
        lastSeekTime.set(seekTimeUs);
        isSeekCompleted.set(true);
        
        seekCompletedLatch.countDown();
    }
    
    @Override
    public void onProgressUpdate(long currentTimeUs, long totalDurationUs) {
        float progress = (float) currentTimeUs / totalDurationUs * 100;
        Log.v(TAG, "Progress: " + String.format("%.1f", progress) + "%");
    }
    
    // Test synchronization methods
    
    public boolean waitForDecoderReady(long timeoutMs) {
        try {
            return decoderReadyLatch.await(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
    
    public boolean waitForFrameAvailable(long timeoutMs) {
        try {
            return frameAvailableLatch.await(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
    
    public boolean waitForSeekCompleted(long timeoutMs) {
        try {
            return seekCompletedLatch.await(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
    
    public boolean waitForDecodingComplete(long timeoutMs) {
        try {
            return decodingCompleteLatch.await(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
    
    // State query methods
    
    public boolean isDecoderReady() {
        return isDecoderReady.get();
    }
    
    public boolean isFrameAvailable() {
        return isFrameAvailable.get();
    }
    
    public boolean isSeekCompleted() {
        return isSeekCompleted.get();
    }
    
    public boolean isDecodingComplete() {
        return isDecodingComplete.get();
    }
    
    public int getVideoWidth() {
        return videoWidth.get();
    }
    
    public int getVideoHeight() {
        return videoHeight.get();
    }
    
    public long getVideoDuration() {
        return videoDuration.get();
    }
    
    public long getLastFrameTime() {
        return lastFrameTime.get();
    }
    
    public long getLastSeekTime() {
        return lastSeekTime.get();
    }
    
    public int getFrameCount() {
        return frameCount.get();
    }
    
    public String getLastError() {
        return lastError.get();
    }
    
    public long getTotalDecodingTime() {
        return totalDecodingTime.get();
    }
    
    // Performance metrics
    
    public float getAverageFrameRate() {
        if (firstFrameTime == -1 || lastFrameTimestamp == -1 || frameCount.get() <= 1) {
            return 0.0f;
        }
        
        long totalTime = lastFrameTimestamp - firstFrameTime;
        if (totalTime == 0) {
            return 0.0f;
        }
        
        return (frameCount.get() * 1000.0f) / totalTime;
    }
    
    public boolean hasError() {
        return lastError.get() != null;
    }
    
    public boolean isValidResolution() {
        return videoWidth.get() > 0 && videoHeight.get() > 0;
    }
    
    public boolean isValidDuration() {
        return videoDuration.get() > 0;
    }
    
    // Test control methods
    
    public void resetFrameTest() {
        frameAvailableLatch = new CountDownLatch(1);
        isFrameAvailable.set(false);
        lastFrameTime.set(-1);
    }
    
    public void resetSeekTest() {
        seekCompletedLatch = new CountDownLatch(1);
        isSeekCompleted.set(false);
        lastSeekTime.set(-1);
    }
    
    public void resetDecodingTest() {
        decodingCompleteLatch = new CountDownLatch(1);
        isDecodingComplete.set(false);
        frameCount.set(0);
        firstFrameTime = -1;
        lastFrameTimestamp = -1;
        totalDecodingTime.set(0);
    }
    
    public void resetAll() {
        decoderReadyLatch = new CountDownLatch(1);
        frameAvailableLatch = new CountDownLatch(1);
        seekCompletedLatch = new CountDownLatch(1);
        decodingCompleteLatch = new CountDownLatch(1);
        
        isDecoderReady.set(false);
        isFrameAvailable.set(false);
        isSeekCompleted.set(false);
        isDecodingComplete.set(false);
        
        videoWidth.set(0);
        videoHeight.set(0);
        videoDuration.set(0);
        lastFrameTime.set(-1);
        lastSeekTime.set(-1);
        frameCount.set(0);
        lastError.set(null);
        
        firstFrameTime = -1;
        lastFrameTimestamp = -1;
        totalDecodingTime.set(0);
    }
    
    // Validation methods
    
    public boolean validateDecoderState() {
        return isDecoderReady() && isValidResolution() && isValidDuration() && !hasError();
    }
    
    public boolean validateFrameDecoding() {
        return isFrameAvailable() && getFrameCount() > 0 && getLastFrameTime() >= 0;
    }
    
    public boolean validateSeekOperation() {
        return isSeekCompleted() && getLastSeekTime() >= 0;
    }
    
    // Test report generation
    
    public String getTestReport() {
        StringBuilder report = new StringBuilder();
        report.append("=== Decoder Test Report ===\n");
        report.append("Decoder Ready: ").append(isDecoderReady()).append("\n");
        report.append("Video Resolution: ").append(getVideoWidth()).append("x").append(getVideoHeight()).append("\n");
        report.append("Video Duration: ").append(getVideoDuration() / 1000000).append("s\n");
        report.append("Frames Decoded: ").append(getFrameCount()).append("\n");
        report.append("Average Frame Rate: ").append(String.format("%.2f", getAverageFrameRate())).append(" fps\n");
        report.append("Total Decoding Time: ").append(getTotalDecodingTime()).append("ms\n");
        report.append("Last Frame Time: ").append(getLastFrameTime()).append("us\n");
        report.append("Last Seek Time: ").append(getLastSeekTime()).append("us\n");
        report.append("Has Error: ").append(hasError()).append("\n");
        if (hasError()) {
            report.append("Last Error: ").append(getLastError()).append("\n");
        }
        report.append("State Valid: ").append(validateDecoderState()).append("\n");
        return report.toString();
    }
}