package com.fadcam.streaming.model;

/**
 * Model class representing metrics for a connected client.
 * Follows OOP principles with encapsulation and immutability.
 */
public class ClientMetrics {
    private final String ipAddress;
    private long totalBytesServed;
    private long sessionStartTime;
    private int fragmentsServed;
    private long lastActivityTime;
    private int getRequestsCount = 0;      // Track GET requests (API calls, not fragments)
    private int postRequestsCount = 0;     // Track POST requests (API calls)
    
    public ClientMetrics(String ipAddress) {
        this.ipAddress = ipAddress;
        this.totalBytesServed = 0;
        this.sessionStartTime = System.currentTimeMillis();
        this.fragmentsServed = 0;
        this.lastActivityTime = System.currentTimeMillis();
        this.getRequestsCount = 0;
        this.postRequestsCount = 0;
    }
    
    /**
     * Add bytes served to this client.
     */
    public void addBytesServed(long bytes) {
        this.totalBytesServed += bytes;
        this.fragmentsServed++;
        this.lastActivityTime = System.currentTimeMillis();
    }
    
    /**
     * Increment GET request count (for API calls like /status, /audio/volume).
     */
    public void incrementGetRequests() {
        this.getRequestsCount++;
        this.lastActivityTime = System.currentTimeMillis();
    }
    
    /**
     * Increment POST request count (for API calls like /torch/toggle, /audio/volume).
     */
    public void incrementPostRequests() {
        this.postRequestsCount++;
        this.lastActivityTime = System.currentTimeMillis();
    }
    
    /**
     * Get session duration in seconds.
     */
    public long getSessionDurationSeconds() {
        return (System.currentTimeMillis() - sessionStartTime) / 1000;
    }
    
    /**
     * Get average bitrate in Mbps.
     */
    public double getAverageBitrateMbps() {
        long durationSeconds = getSessionDurationSeconds();
        if (durationSeconds == 0) return 0;
        
        double bitsPerSecond = (totalBytesServed * 8.0) / durationSeconds;
        return bitsPerSecond / (1024 * 1024); // Convert to Mbps
    }
    
    /**
     * Check if client is considered active (activity in last 30 seconds).
     */
    public boolean isActive() {
        return (System.currentTimeMillis() - lastActivityTime) < 30000;
    }
    
    // Getters
    public String getIpAddress() {
        return ipAddress;
    }
    
    public long getTotalBytesServed() {
        return totalBytesServed;
    }
    
    public long getTotalMBServed() {
        return totalBytesServed / (1024 * 1024);
    }
    
    public int getFragmentsServed() {
        return fragmentsServed;
    }
    
    public int getGetRequestsCount() {
        return getRequestsCount;
    }
    
    public int getPostRequestsCount() {
        return postRequestsCount;
    }
    
    public long getLastActivityTime() {
        return lastActivityTime;
    }
    
    public long getSessionStartTime() {
        return sessionStartTime;
    }
    
    /**
     * Convert to JSON object string.
     */
    public String toJson() {
        return String.format(
            "{\"ip\": \"%s\", \"bytes_served\": %d, \"mb_served\": %d, " +
            "\"fragments_served\": %d, \"get_requests\": %d, \"post_requests\": %d, \"total_api_calls\": %d, " +
            "\"session_duration_seconds\": %d, \"average_bitrate_mbps\": %.2f, \"is_active\": %s, \"last_activity_ms\": %d}",
            ipAddress,
            totalBytesServed,
            getTotalMBServed(),
            fragmentsServed,
            getRequestsCount,
            postRequestsCount,
            getRequestsCount + postRequestsCount,
            getSessionDurationSeconds(),
            getAverageBitrateMbps(),
            isActive(),
            lastActivityTime
        );
    }
}
