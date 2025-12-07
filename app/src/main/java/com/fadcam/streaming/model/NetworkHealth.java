package com.fadcam.streaming.model;

/**
 * Model class for network health monitoring.
 * Measures actual network performance based on speed tests.
 */
public class NetworkHealth {
    public enum Status {
        EXCELLENT,  // > 50 Mbps
        GOOD,       // 10-50 Mbps
        MODERATE,   // 2-10 Mbps
        POOR,       // < 2 Mbps
        UNKNOWN     // Not measured yet
    }
    
    private Status status;
    private double downloadSpeedMbps;
    private double uploadSpeedMbps;
    private long lastMeasurementTime;
    private int latencyMs;
    
    public NetworkHealth() {
        this.status = Status.UNKNOWN;
        this.downloadSpeedMbps = 0;
        this.uploadSpeedMbps = 0;
        this.lastMeasurementTime = 0;
        this.latencyMs = -1;
    }
    
    /**
     * Update network measurements and recalculate status.
     */
    public void updateMeasurements(double downloadMbps, double uploadMbps, int latency) {
        this.downloadSpeedMbps = downloadMbps;
        this.uploadSpeedMbps = uploadMbps;
        this.latencyMs = latency;
        this.lastMeasurementTime = System.currentTimeMillis();
        
        // Calculate status based on upload speed (more critical for streaming server)
        double relevantSpeed = Math.max(uploadMbps, downloadMbps * 0.5);
        
        if (relevantSpeed >= 50) {
            status = Status.EXCELLENT;
        } else if (relevantSpeed >= 10) {
            status = Status.GOOD;
        } else if (relevantSpeed >= 2) {
            status = Status.MODERATE;
        } else if (relevantSpeed > 0) {
            status = Status.POOR;
        } else {
            status = Status.UNKNOWN;
        }
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
        return String.format("↑%.1f Mbps ↓%.1f Mbps", uploadSpeedMbps, downloadSpeedMbps);
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
        return String.format(
            "{\"status\": \"%s\", \"download_mbps\": %.2f, \"upload_mbps\": %.2f, " +
            "\"latency_ms\": %d, \"last_measurement_ms\": %d, \"is_stale\": %s}",
            getStatusString(),
            downloadSpeedMbps,
            uploadSpeedMbps,
            latencyMs,
            lastMeasurementTime,
            isMeasurementStale()
        );
    }
}
