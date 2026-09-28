package com.fadcam.streaming.model;

/**
 * Model class for network health monitoring.
 * Measures actual network performance based on speed tests.
 */
public class NetworkHealth {
    public enum Status {
        EXCELLENT,  // >= 1.0 Mbps (measured), low latency
        GOOD,       // >= 0.5 Mbps (measured), acceptable latency
        MODERATE,   // >= 0.2 Mbps (measured), high latency
        POOR,       // < 0.2 Mbps (measured), very slow
        UNKNOWN     // Not measured yet
    }
    
    private Status status;
    private double downloadSpeedMbps;
    private double uploadSpeedMbps;
    private long lastMeasurementTime;
    private int latencyMs;
    private int signalLevel; // 0-4 for WiFi/Signal strength, -1 for unknown
    
    public NetworkHealth() {
        this.status = Status.UNKNOWN;
        this.downloadSpeedMbps = 0;
        this.uploadSpeedMbps = 0;
        this.lastMeasurementTime = 0;
        this.latencyMs = -1;
        this.signalLevel = -1;
    }
    
    /**
     * Update network measurements and recalculate status.
     * Note: Small test files result in lower measured speeds than actual bandwidth.
     * Thresholds are adjusted accordingly.
     */
    public void updateMeasurements(double downloadMbps, double uploadMbps, int latency, int signalLevel) {
        this.downloadSpeedMbps = downloadMbps;
        this.uploadSpeedMbps = uploadMbps;
        this.latencyMs = latency;
        this.signalLevel = signalLevel;
        this.lastMeasurementTime = System.currentTimeMillis();
        
        // Calculate status based on upload speed (more critical for streaming server)
        // Note: Favicon downloads measure lower than actual speed, so use conservative thresholds
        double relevantSpeed = Math.max(uploadMbps, downloadMbps * 0.5);
        
        // IF we have excellent signal (>= 3/4), we should be more lenient with speed test
        // (speed test might be slow due to small file / SSL handshake, but signal says it's good)
        boolean hasGoodSignal = signalLevel >= 3;
        
        if ((relevantSpeed >= 1.0 && latency < 50) || (hasGoodSignal && relevantSpeed >= 0.5)) {
            status = Status.EXCELLENT;  // Good for 8Mbps streaming
        } else if ((relevantSpeed >= 0.5 && latency < 100) || (hasGoodSignal && relevantSpeed >= 0.2)) {
            status = Status.GOOD;        // Acceptable for streaming
        } else if (relevantSpeed >= 0.2 && latency < 200 || hasGoodSignal) {
            status = Status.MODERATE;    // Marginal for streaming
        } else if (relevantSpeed > 0 || signalLevel >= 0) {
            status = Status.POOR;        // Insufficient for streaming
        } else {
            status = Status.UNKNOWN;
        }
    }
    
    /**
     * Update network measurements without signal level.
     */
    public void updateMeasurements(double downloadMbps, double uploadMbps, int latency) {
        updateMeasurements(downloadMbps, uploadMbps, latency, -1);
    }
    
    /**
     * Check if measurement is stale (older than 5 minutes).
     */
    public boolean isMeasurementStale() {
        if (lastMeasurementTime == 0) return true;
        return (System.currentTimeMillis() - lastMeasurementTime) > 300000;
    }
    
    /**
     * Get status as lowercase string.
     */
    public String getStatusString() {
        return status.toString().toLowerCase();
    }
    
    /**
     * Get color code for status (hex string).
     */
    public String getStatusColorHex() {
        switch (status) {
            case EXCELLENT: return "#4CAF50"; // Green
            case GOOD: return "#2196F3";      // Blue
            case MODERATE: return "#FFA726";  // Orange
            case POOR: return "#FF5252";      // Red
            default: return "#9E9E9E";        // Gray
        }
    }
    
    /**
     * Get status color as Android color int.
     */
    public int getStatusColorInt() {
        switch (status) {
            case EXCELLENT: return 0xFF4CAF50;
            case GOOD: return 0xFF2196F3;
            case MODERATE: return 0xFFFFA726;
            case POOR: return 0xFFFF5252;
            default: return 0xFF9E9E9E;
        }
    }
    
    /**
     * Get formatted speed string for display.
     */
    public String getFormattedSpeed() {
        if (status == Status.UNKNOWN) {
            return "Testing...";
        }
        return String.format(java.util.Locale.US, "↑%.1f Mbps ↓%.1f Mbps", uploadSpeedMbps, downloadSpeedMbps);
    }
    
    // Getters
    public Status getStatus() {
        return status;
    }
    
    public double getDownloadSpeedMbps() {
        return downloadSpeedMbps;
    }
    
    public double getUploadSpeedMbps() {
        return uploadSpeedMbps;
    }
    
    public int getLatencyMs() {
        return latencyMs;
    }
    
    public long getLastMeasurementTime() {
        return lastMeasurementTime;
    }
    
    /**
     * Convert to JSON string.
     */
    public String toJson() {
        return String.format(java.util.Locale.US,
            "{\"status\": \"%s\", \"downloadMbps\": %.2f, \"uploadMbps\": %.2f, " +
            "\"latencyMs\": %d, \"signalLevel\": %d, \"lastMeasurementMs\": %d, \"isStale\": %s}",
            getStatusString(),
            downloadSpeedMbps,
            uploadSpeedMbps,
            latencyMs,
            signalLevel,
            lastMeasurementTime,
            isMeasurementStale()
        );
    }
}
