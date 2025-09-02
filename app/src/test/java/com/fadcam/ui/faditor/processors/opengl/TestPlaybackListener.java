package com.fadcam.ui.faditor.processors.opengl;

import com.fadcam.Log;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Test implementation of PlaybackController.PlaybackListener for validation testing.
 * Provides state tracking for playback control testing scenarios.
 */
public class TestPlaybackListener implements PlaybackController.PlaybackListener {
    private static final String TAG = "TestPlaybackListener";
    
    // State tracking
    private final AtomicLong currentPosition = new AtomicLong(0);
    private final AtomicReference<Float> playbackSpeed = new AtomicReference<>(1.0f);
    private final AtomicBoolean isPlaying = new AtomicBoolean(false);
    
    // Event tracking
    private final AtomicLong positionChangeCount = new AtomicLong(0);
    private final AtomicLong speedChangeCount = new AtomicLong(0);
    private final AtomicLong lastPositionChangeTime = new AtomicLong(0);
    private final AtomicLong lastSpeedChangeTime = new AtomicLong(0);
    
    // Performance tracking
    private long firstPositionUpdate = -1;
    private long lastPositionUpdate = -1;
    
    @Override
    public void onPositionChanged(long positionMs) {
        long currentTime = System.currentTimeMillis();
        
        if (firstPositionUpdate == -1) {
            firstPositionUpdate = currentTime;
        }
        lastPositionUpdate = currentTime;
        
        currentPosition.set(positionMs);
        positionChangeCount.incrementAndGet();
        lastPositionChangeTime.set(currentTime);
        
        Log.v(TAG, "Position changed: " + positionMs + "ms (update #" + positionChangeCount.get() + ")");
    }
    
    @Override
    public void onPlaybackSpeedChanged(float speed) {
        long currentTime = System.currentTimeMillis();
        
        playbackSpeed.set(speed);
        speedChangeCount.incrementAndGet();
        lastSpeedChangeTime.set(currentTime);
        
        Log.d(TAG, "Playback speed changed: " + speed + "x (change #" + speedChangeCount.get() + ")");
    }
    
    // State query methods
    
    public long getCurrentPosition() {
        return currentPosition.get();
    }
    
    public float getPlaybackSpeed() {
        return playbackSpeed.get();
    }
    
    public boolean isPlaying() {
        return isPlaying.get();
    }
    
    public long getPositionChangeCount() {
        return positionChangeCount.get();
    }
    
    public long getSpeedChangeCount() {
        return speedChangeCount.get();
    }
    
    public long getLastPositionChangeTime() {
        return lastPositionChangeTime.get();
    }
    
    public long getLastSpeedChangeTime() {
        return lastSpeedChangeTime.get();
    }
    
    // Performance metrics
    
    public float getPositionUpdateRate() {
        if (firstPositionUpdate == -1 || lastPositionUpdate == -1 || positionChangeCount.get() <= 1) {
            return 0.0f;
        }
        
        long totalTime = lastPositionUpdate - firstPositionUpdate;
        if (totalTime == 0) {
            return 0.0f;
        }
        
        return (positionChangeCount.get() * 1000.0f) / totalTime;
    }
    
    public long getTotalTrackingTime() {
        if (firstPositionUpdate == -1 || lastPositionUpdate == -1) {
            return 0;
        }
        return lastPositionUpdate - firstPositionUpdate;
    }
    
    // Validation methods
    
    public boolean isPositionUpdateFrequent() {
        // Position should update at least 10 times per second during playback
        float updateRate = getPositionUpdateRate();
        return updateRate >= 10.0f;
    }
    
    public boolean isSpeedChangeResponsive() {
        // Speed changes should be reflected quickly
        long lastChange = getLastSpeedChangeTime();
        return lastChange > 0 && (System.currentTimeMillis() - lastChange) < 1000;
    }
    
    public boolean hasReceivedUpdates() {
        return positionChangeCount.get() > 0;
    }
    
    // Test control methods
    
    public void setPlayingState(boolean playing) {
        isPlaying.set(playing);
    }
    
    public void resetTracking() {
        currentPosition.set(0);
        playbackSpeed.set(1.0f);
        isPlaying.set(false);
        positionChangeCount.set(0);
        speedChangeCount.set(0);
        lastPositionChangeTime.set(0);
        lastSpeedChangeTime.set(0);
        firstPositionUpdate = -1;
        lastPositionUpdate = -1;
    }
    
    // Test report generation
    
    public String getTestReport() {
        StringBuilder report = new StringBuilder();
        report.append("=== Playback Listener Test Report ===\n");
        report.append("Current Position: ").append(getCurrentPosition()).append("ms\n");
        report.append("Playback Speed: ").append(getPlaybackSpeed()).append("x\n");
        report.append("Is Playing: ").append(isPlaying()).append("\n");
        report.append("Position Updates: ").append(getPositionChangeCount()).append("\n");
        report.append("Speed Changes: ").append(getSpeedChangeCount()).append("\n");
        report.append("Update Rate: ").append(String.format("%.2f", getPositionUpdateRate())).append(" Hz\n");
        report.append("Total Tracking Time: ").append(getTotalTrackingTime()).append("ms\n");
        report.append("Frequent Updates: ").append(isPositionUpdateFrequent()).append("\n");
        report.append("Speed Responsive: ").append(isSpeedChangeResponsive()).append("\n");
        report.append("Has Updates: ").append(hasReceivedUpdates()).append("\n");
        return report.toString();
    }
}