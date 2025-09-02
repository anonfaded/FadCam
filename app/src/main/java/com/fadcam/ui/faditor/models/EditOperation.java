package com.fadcam.ui.faditor.models;

import java.util.HashMap;
import java.util.Map;

/**
 * Represents a single editing operation on a video.
 * Currently supports trim operations with extensibility for future operations.
 */
public class EditOperation {
    
    public enum Type {
        TRIM
    }
    
    private Type type;
    private long startTime;
    private long endTime;
    private boolean requiresReencoding;
    private Map<String, Object> parameters;
    
    public EditOperation() {
        this.parameters = new HashMap<>();
        this.requiresReencoding = false;
    }
    
    public EditOperation(Type type, long startTime, long endTime) {
        this();
        this.type = type;
        this.startTime = startTime;
        this.endTime = endTime;
    }
    
    // Getters and setters
    public Type getType() {
        return type;
    }
    
    public void setType(Type type) {
        this.type = type;
    }
    
    public long getStartTime() {
        return startTime;
    }
    
    public void setStartTime(long startTime) {
        this.startTime = startTime;
    }
    
    public long getEndTime() {
        return endTime;
    }
    
    public void setEndTime(long endTime) {
        this.endTime = endTime;
    }
    
    public boolean requiresReencoding() {
        return requiresReencoding;
    }
    
    public boolean isRequiresReencoding() {
        return requiresReencoding;
    }
    
    public void setRequiresReencoding(boolean requiresReencoding) {
        this.requiresReencoding = requiresReencoding;
    }
    
    public Map<String, Object> getParameters() {
        return new HashMap<>(parameters);
    }
    
    public void setParameters(Map<String, Object> parameters) {
        this.parameters = parameters != null ? new HashMap<>(parameters) : new HashMap<>();
    }
    
    public void setParameter(String key, Object value) {
        parameters.put(key, value);
    }
    
    public Object getParameter(String key) {
        return parameters.get(key);
    }
    
    public boolean isLosslessCompatible() {
        return !requiresReencoding;
    }
    
    /**
     * Get the duration of this operation in milliseconds
     */
    public long getDuration() {
        return endTime - startTime;
    }
    
    /**
     * Check if this operation is valid
     */
    public boolean isValid() {
        return startTime >= 0 && endTime > startTime;
    }
    
    /**
     * Create a trim operation with validation
     */
    public static EditOperation createTrimOperation(long startTime, long endTime, long maxDuration) {
        if (startTime < 0 || endTime > maxDuration || startTime >= endTime) {
            throw new IllegalArgumentException("Invalid trim range: " + startTime + " to " + endTime + " (max: " + maxDuration + ")");
        }
        return new EditOperation(Type.TRIM, startTime, endTime);
    }
    
    /**
     * Check if this operation overlaps with another operation
     */
    public boolean overlapsWith(EditOperation other) {
        if (other == null || this.type != other.type) {
            return false;
        }
        return !(this.endTime <= other.startTime || this.startTime >= other.endTime);
    }
    
    /**
     * Get a human-readable description of this operation
     */
    public String getDescription() {
        switch (type) {
            case TRIM:
                return "Trim from " + formatTime(startTime) + " to " + formatTime(endTime);
            default:
                return "Unknown operation";
        }
    }
    
    private String formatTime(long timeMs) {
        long seconds = timeMs / 1000;
        long minutes = seconds / 60;
        seconds = seconds % 60;
        return String.format("%d:%02d", minutes, seconds);
    }
    
    @Override
    public String toString() {
        return "EditOperation{" +
                "type=" + type +
                ", startTime=" + startTime +
                ", endTime=" + endTime +
                ", requiresReencoding=" + requiresReencoding +
                '}';
    }
}