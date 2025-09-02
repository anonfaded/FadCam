package com.fadcam.ui.faditor.processors.opengl;

import com.fadcam.Log;
import com.fadcam.ui.faditor.models.VideoMetadata;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Test implementation of VideoControllerListener for validation testing.
 * Provides synchronization mechanisms and state tracking for test scenarios.
 */
public class TestVideoControllerListener implements VideoControllerListener {
    private static final String TAG = "TestVideoControllerListener";
    
    // Synchronization latches
    private CountDownLatch initializationLatch = new CountDownLatch(1);
    private CountDownLatch videoLoadLatch = new CountDownLatch(1);
    private CountDownLatch seekCompletionLatch = new CountDownLatch(1);
    
    // State tracking
    private final AtomicBoolean isInitialized = new AtomicBoolean(false);
    private final AtomicBoolean isVideoLoaded = new AtomicBoolean(false);
    private final AtomicBoolean isFrameRendered = new AtomicBoolean(false);
    private final AtomicBoolean isSeekCompleted = new AtomicBoolean(false);
    private final AtomicBoolean isPlaying = new AtomicBoolean(false);
    
    // Data tracking
    private final AtomicReference<VideoMetadata> videoMetadata = new AtomicReference<>();
    private final AtomicLong lastFrameNumber = new AtomicLong(-1);
    private final AtomicLong lastFrameTimestamp = new AtomicLong(-1);
    private final AtomicLong lastSeekPosition = new AtomicLong(-1);
    private final AtomicReference<String> lastError = new AtomicReference<>();
    
    // Performance tracking
    private long initializationStartTime = 0;
    private long videoLoadStartTime = 0;
    private long seekStartTime = 0;
    
    @Override
    public void onVideoLoaded(VideoMetadata metadata) {
        Log.d(TAG, "Video loaded: " + metadata.getWidth() + "x" + metadata.getHeight() + 
                  ", duration: " + metadata.getDuration() + "ms");
        
        this.videoMetadata.set(metadata);
        this.isVideoLoaded.set(true);
        this.videoLoadLatch.countDown();
    }
    
    @Override
    public void onFrameRendered(long frameNumber, long timestampUs) {
        Log.v(TAG, "Frame rendered: #" + frameNumber + " at " + (timestampUs / 1000) + "ms");
        
        this.lastFrameNumber.set(frameNumber);
        this.lastFrameTimestamp.set(timestampUs);
        this.isFrameRendered.set(true);
    }
    
    @Override
    public void onPlaybackStateChanged(boolean isPlaying) {
        Log.d(TAG, "Playback state changed: " + isPlaying);
        this.isPlaying.set(isPlaying);
    }
    
    @Override
    public void onSeekCompleted(long positionMs) {
        Log.d(TAG, "Seek completed to: " + positionMs + "ms");
        
        this.lastSeekPosition.set(positionMs);
        this.isSeekCompleted.set(true);
        this.seekCompletionLatch.countDown();
    }
    
    @Override
    public void onPositionChanged(long positionMs) {
        Log.v(TAG, "Position changed: " + positionMs + "ms");
    }
    
    @Override
    public void onError(String error) {
        Log.e(TAG, "Controller error: " + error);
        this.lastError.set(error);
        
        // Count down all latches to prevent test hanging
        initializationLatch.countDown();
        videoLoadLatch.countDown();
        seekCompletionLatch.countDown();
    }
    
    @Override
    public void onInitializationComplete() {
        Log.d(TAG, "Initialization complete");
        this.isInitialized.set(true);
        this.initializationLatch.countDown();
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
    
    // State query methods
    
    public boolean isInitialized() {
        return isInitialized.get();
    }
    
    public boolean isVideoLoaded() {
        return isVideoLoaded.get();
    }
    
    public boolean isFrameRendered() {
        return isFrameRendered.get();
    }
    
    public boolean isSeekCompleted() {
        return isSeekCompleted.get();
    }
    
    public boolean isPlaying() {
        return isPlaying.get();
    }
    
    public VideoMetadata getVideoMetadata() {
        return videoMetadata.get();
    }
    
    public long getLastFrameNumber() {
        return lastFrameNumber.get();
    }
    
    public long getLastFrameTimestamp() {
        return lastFrameTimestamp.get();
    }
    
    public long getLastSeekPosition() {
        return lastSeekPosition.get();
    }
    
    public String getLastError() {
        return lastError.get();
    }
    
    // Test control methods
    
    public void resetSeekTest() {
        seekCompletionLatch = new CountDownLatch(1);
        isSeekCompleted.set(false);
        seekStartTime = System.currentTimeMillis();
    }
    
    public void resetFrameTest() {
        isFrameRendered.set(false);
        lastFrameNumber.set(-1);
        lastFrameTimestamp.set(-1);
    }
    
    public void resetAll() {
        initializationLatch = new CountDownLatch(1);
        videoLoadLatch = new CountDownLatch(1);
        seekCompletionLatch = new CountDownLatch(1);
        
        isInitialized.set(false);
        isVideoLoaded.set(false);
        isFrameRendered.set(false);
        isSeekCompleted.set(false);
        isPlaying.set(false);
        
        videoMetadata.set(null);
        lastFrameNumber.set(-1);
        lastFrameTimestamp.set(-1);
        lastSeekPosition.set(-1);
        lastError.set(null);
    }
    
    // Performance measurement methods
    
    public long getInitializationTime() {
        return initializationStartTime > 0 ? 
               System.currentTimeMillis() - initializationStartTime : -1;
    }
    
    public long getVideoLoadTime() {
        return videoLoadStartTime > 0 ? 
               System.currentTimeMillis() - videoLoadStartTime : -1;
    }
    
    public long getSeekTime() {
        return seekStartTime > 0 ? 
               System.currentTimeMillis() - seekStartTime : -1;
    }
    
    public void startInitializationTimer() {
        initializationStartTime = System.currentTimeMillis();
    }
    
    public void startVideoLoadTimer() {
        videoLoadStartTime = System.currentTimeMillis();
    }
    
    public void startSeekTimer() {
        seekStartTime = System.currentTimeMillis();
    }
}