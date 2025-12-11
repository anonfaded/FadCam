package com.fadcam.streaming.model;

/**
 * Represents an authentication session token for Remote access.
 * Contains token string, creation time, and expiry information.
 */
public class SessionToken {
    private final String token;
    private final long createdAtMs;
    private final long expiresAtMs;
    private final String deviceInfo; // Optional: IP, user agent, etc.
    
    public SessionToken(String token, long createdAtMs, long expiresAtMs, String deviceInfo) {
        this.token = token;
        this.createdAtMs = createdAtMs;
        this.expiresAtMs = expiresAtMs;
        this.deviceInfo = deviceInfo;
    }
    
    public SessionToken(String token, long createdAtMs, long expiresAtMs) {
        this(token, createdAtMs, expiresAtMs, "unknown");
    }
    
    public String getToken() {
        return token;
    }
    
    public long getCreatedAtMs() {
        return createdAtMs;
    }
    
    public long getExpiresAtMs() {
        return expiresAtMs;
    }
    
    public String getDeviceInfo() {
        return deviceInfo;
    }
    
    public boolean isExpired() {
        return System.currentTimeMillis() > expiresAtMs;
    }
    
    public boolean isValid() {
        return !isExpired() && token != null && !token.isEmpty();
    }
    
    /**
     * Convert to JSON string for storage
     */
    public String toJson() {
        return String.format(
            "{\"token\":\"%s\",\"createdAt\":%d,\"expiresAt\":%d,\"deviceInfo\":\"%s\"}",
            token, createdAtMs, expiresAtMs, deviceInfo
        );
    }
    
    /**
     * Parse from JSON string
     */
    public static SessionToken fromJson(String json) {
        try {
            // Simple JSON parsing without external library
            String token = extractJsonValue(json, "token");
            long createdAt = Long.parseLong(extractJsonValue(json, "createdAt"));
            long expiresAt = Long.parseLong(extractJsonValue(json, "expiresAt"));
            String deviceInfo = extractJsonValue(json, "deviceInfo");
            return new SessionToken(token, createdAt, expiresAt, deviceInfo);
        } catch (Exception e) {
            return null;
        }
    }
    
    private static String extractJsonValue(String json, String key) {
        String searchKey = "\"" + key + "\":";
        int startIndex = json.indexOf(searchKey);
        if (startIndex == -1) return "";
        
        startIndex += searchKey.length();
        
        // Handle string values
        if (json.charAt(startIndex) == '"') {
            startIndex++;
            int endIndex = json.indexOf('"', startIndex);
            return json.substring(startIndex, endIndex);
        }
        
        // Handle numeric values
        int endIndex = json.indexOf(',', startIndex);
        if (endIndex == -1) endIndex = json.indexOf('}', startIndex);
        return json.substring(startIndex, endIndex).trim();
    }
}
