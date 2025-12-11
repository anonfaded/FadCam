package com.fadcam.streaming.model;

/**
 * Model for client connection event logging.
 * Tracks connect/disconnect events with timestamps.
 */
public class ClientEvent {
    public enum EventType {
        CONNECTED,
        DISCONNECTED,
        FIRST_REQUEST,
        DATA_MILESTONE  // Every 10MB served
    }
    
    private final String clientIP;
    private final EventType eventType;
    private final long timestamp;
    private final String details;
    
    public ClientEvent(String clientIP, EventType eventType, String details) {
        this.clientIP = clientIP;
        this.eventType = eventType;
        this.timestamp = System.currentTimeMillis();
        this.details = details;
    }
    
    public String getClientIP() {
        return clientIP;
    }
    
    public EventType getEventType() {
        return eventType;
    }
    
    public long getTimestamp() {
        return timestamp;
    }
    
    public String getDetails() {
        return details;
    }
    
    public String getEventTypeString() {
        return eventType.toString().toLowerCase();
    }
    
    /**
     * Convert to JSON string.
     */
    public String toJson() {
        return String.format(
            "{\"ip\": \"%s\", \"event\": \"%s\", \"timestamp\": %d, \"details\": \"%s\"}",
            clientIP,
            getEventTypeString(),
            timestamp,
            details != null ? details : ""
        );
    }
}
