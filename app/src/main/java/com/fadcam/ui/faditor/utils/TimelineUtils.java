package com.fadcam.ui.faditor.utils;

/**
 * Utility class for timeline calculations and formatting.
 * Handles time conversions and timeline position calculations.
 */
public class TimelineUtils {
    
    /**
     * Convert pixel position to time position on timeline
     */
    public static long pixelToTime(float pixelX, int timelineWidth, long videoDuration) {
        if (timelineWidth <= 0 || videoDuration <= 0) {
            return 0;
        }
        
        float ratio = Math.max(0, Math.min(1, pixelX / timelineWidth));
        return (long) (ratio * videoDuration);
    }
    
    /**
     * Convert time position to pixel position on timeline
     */
    public static float timeToPixel(long timeMs, int timelineWidth, long videoDuration) {
        if (videoDuration <= 0) {
            return 0;
        }
        
        float ratio = (float) timeMs / videoDuration;
        return ratio * timelineWidth;
    }
    
    /**
     * Format time in milliseconds to MM:SS format
     */
    public static String formatTime(long timeMs) {
        long totalSeconds = timeMs / 1000;
        long minutes = totalSeconds / 60;
        long seconds = totalSeconds % 60;
        
        return String.format("%02d:%02d", minutes, seconds);
    }
    
    /**
     * Format time in milliseconds to HH:MM:SS format
     */
    public static String formatTimeLong(long timeMs) {
        long totalSeconds = timeMs / 1000;
        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;
        
        if (hours > 0) {
            return String.format("%02d:%02d:%02d", hours, minutes, seconds);
        } else {
            return String.format("%02d:%02d", minutes, seconds);
        }
    }
    
    /**
     * Parse time string (MM:SS or HH:MM:SS) to milliseconds
     */
    public static long parseTime(String timeString) {
        if (timeString == null || timeString.trim().isEmpty()) {
            return 0;
        }
        
        try {
            String[] parts = timeString.split(":");
            long totalSeconds = 0;
            
            if (parts.length == 2) {
                // MM:SS format
                totalSeconds = Integer.parseInt(parts[0]) * 60 + Integer.parseInt(parts[1]);
            } else if (parts.length == 3) {
                // HH:MM:SS format
                totalSeconds = Integer.parseInt(parts[0]) * 3600 + 
                              Integer.parseInt(parts[1]) * 60 + 
                              Integer.parseInt(parts[2]);
            }
            
            return totalSeconds * 1000;
        } catch (NumberFormatException e) {
            return 0;
        }
    }
    
    /**
     * Calculate the optimal number of time markers for a timeline
     */
    public static int calculateTimeMarkers(long videoDuration, int timelineWidth) {
        // Aim for markers every 60-100 pixels
        int optimalMarkers = timelineWidth / 80;
        return Math.max(2, Math.min(10, optimalMarkers));
    }
    
    /**
     * Get time intervals for timeline markers
     */
    public static long[] getTimeMarkers(long videoDuration, int markerCount) {
        if (markerCount <= 1) {
            return new long[]{0, videoDuration};
        }
        
        long[] markers = new long[markerCount];
        long interval = videoDuration / (markerCount - 1);
        
        for (int i = 0; i < markerCount; i++) {
            markers[i] = i * interval;
        }
        
        // Ensure last marker is exactly at the end
        markers[markerCount - 1] = videoDuration;
        
        return markers;
    }
    
    /**
     * Snap time to nearest frame boundary
     */
    public static long snapToFrame(long timeMs, float frameRate) {
        if (frameRate <= 0) {
            return timeMs;
        }
        
        long frameDurationMs = (long) (1000.0f / frameRate);
        long frameNumber = timeMs / frameDurationMs;
        return frameNumber * frameDurationMs;
    }
}