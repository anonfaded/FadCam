package com.fadcam.ui.faditor.utils;

import java.util.Locale;

/**
 * Utility class for timeline calculations and formatting.
 * Provides helper methods for time formatting, position calculations, and timeline operations.
 */
public class TimelineUtils {
    
    private static final String TAG = "TimelineUtils";
    
    /**
     * Format duration in milliseconds to MM:SS or HH:MM:SS format
     */
    public static String formatDuration(long durationMs) {
        if (durationMs < 0) {
            return "00:00";
        }
        
        long totalSeconds = durationMs / 1000;
        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;
        
        if (hours > 0) {
            return String.format(Locale.getDefault(), "%d:%02d:%02d", hours, minutes, seconds);
        } else {
            return String.format(Locale.getDefault(), "%d:%02d", minutes, seconds);
        }
    }
    
    /**
     * Format duration with millisecond precision for detailed display
     */
    public static String formatDurationWithMs(long durationMs) {
        if (durationMs < 0) {
            return "00:00.000";
        }
        
        long totalSeconds = durationMs / 1000;
        long milliseconds = durationMs % 1000;
        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;
        
        if (hours > 0) {
            return String.format(Locale.getDefault(), "%d:%02d:%02d.%03d", 
                               hours, minutes, seconds, milliseconds);
        } else {
            return String.format(Locale.getDefault(), "%d:%02d.%03d", 
                               minutes, seconds, milliseconds);
        }
    }
    
    /**
     * Parse duration string (MM:SS or HH:MM:SS) to milliseconds
     */
    public static long parseDuration(String durationStr) {
        if (durationStr == null || durationStr.trim().isEmpty()) {
            return 0;
        }
        
        try {
            String[] parts = durationStr.trim().split(":");
            
            if (parts.length == 2) {
                // MM:SS format
                int minutes = Integer.parseInt(parts[0]);
                int seconds = Integer.parseInt(parts[1]);
                return (minutes * 60L + seconds) * 1000L;
            } else if (parts.length == 3) {
                // HH:MM:SS format
                int hours = Integer.parseInt(parts[0]);
                int minutes = Integer.parseInt(parts[1]);
                int seconds = Integer.parseInt(parts[2]);
                return (hours * 3600L + minutes * 60L + seconds) * 1000L;
            }
        } catch (NumberFormatException e) {
            // Invalid format, return 0
        }
        
        return 0;
    }
    
    /**
     * Convert pixel position to time position based on timeline width and duration
     */
    public static long pixelToTime(float pixelX, float timelineStartX, float timelineWidth, long videoDuration) {
        if (timelineWidth <= 0 || videoDuration <= 0) {
            return 0;
        }
        
        float relativeX = pixelX - timelineStartX;
        float progress = Math.max(0, Math.min(1, relativeX / timelineWidth));
        
        return (long) (progress * videoDuration);
    }
    
    /**
     * Convert time position to pixel position based on timeline width and duration
     */
    public static float timeToPixel(long timeMs, float timelineStartX, float timelineWidth, long videoDuration) {
        if (videoDuration <= 0) {
            return timelineStartX;
        }
        
        float progress = Math.max(0, Math.min(1, (float) timeMs / videoDuration));
        return timelineStartX + progress * timelineWidth;
    }
    
    /**
     * Calculate optimal time marker intervals for timeline display
     */
    public static long[] calculateTimeMarkers(long videoDuration, int maxMarkers) {
        if (videoDuration <= 0 || maxMarkers <= 0) {
            return new long[0];
        }
        
        // Define possible intervals in milliseconds
        long[] intervals = {
            1000,      // 1 second
            5000,      // 5 seconds
            10000,     // 10 seconds
            30000,     // 30 seconds
            60000,     // 1 minute
            300000,    // 5 minutes
            600000,    // 10 minutes
            1800000,   // 30 minutes
            3600000    // 1 hour
        };
        
        // Find the best interval that gives us a reasonable number of markers
        long bestInterval = intervals[0];
        for (long interval : intervals) {
            int markerCount = (int) (videoDuration / interval) + 1;
            if (markerCount <= maxMarkers) {
                bestInterval = interval;
                break;
            }
        }
        
        // Generate marker positions
        int markerCount = (int) (videoDuration / bestInterval) + 1;
        long[] markers = new long[markerCount];
        
        for (int i = 0; i < markerCount; i++) {
            markers[i] = i * bestInterval;
            if (markers[i] > videoDuration) {
                markers[i] = videoDuration;
                break;
            }
        }
        
        return markers;
    }
    
    /**
     * Validate trim range and return corrected values
     */
    public static TrimRangeResult validateTrimRange(long start, long end, long videoDuration, long minDuration) {
        // Clamp to video bounds
        start = Math.max(0, Math.min(start, videoDuration));
        end = Math.max(0, Math.min(end, videoDuration));
        
        // Ensure start < end
        if (start >= end) {
            end = Math.min(videoDuration, start + minDuration);
        }
        
        // Ensure minimum duration
        if (end - start < minDuration) {
            if (start + minDuration <= videoDuration) {
                end = start + minDuration;
            } else {
                start = Math.max(0, end - minDuration);
            }
        }
        
        boolean wasModified = false;
        return new TrimRangeResult(start, end, wasModified);
    }
    
    /**
     * Calculate trim duration as a percentage of total video duration
     */
    public static float calculateTrimPercentage(long trimStart, long trimEnd, long videoDuration) {
        if (videoDuration <= 0) {
            return 0f;
        }
        
        long trimDuration = trimEnd - trimStart;
        return Math.max(0f, Math.min(1f, (float) trimDuration / videoDuration));
    }
    
    /**
     * Format file size in human-readable format
     */
    public static String formatFileSize(long sizeBytes) {
        if (sizeBytes < 0) {
            return "0 B";
        }
        
        String[] units = {"B", "KB", "MB", "GB", "TB"};
        int unitIndex = 0;
        double size = sizeBytes;
        
        while (size >= 1024 && unitIndex < units.length - 1) {
            size /= 1024;
            unitIndex++;
        }
        
        if (unitIndex == 0) {
            return String.format(Locale.getDefault(), "%.0f %s", size, units[unitIndex]);
        } else {
            return String.format(Locale.getDefault(), "%.1f %s", size, units[unitIndex]);
        }
    }
    
    /**
     * Calculate estimated file size after trimming
     */
    public static long estimateTrimmedFileSize(long originalSize, long originalDuration, long trimDuration) {
        if (originalDuration <= 0 || trimDuration <= 0) {
            return 0;
        }
        
        // Simple linear estimation (actual size may vary due to keyframes, etc.)
        double ratio = (double) trimDuration / originalDuration;
        return (long) (originalSize * ratio);
    }
    
    /**
     * Check if two time values are approximately equal (within tolerance)
     */
    public static boolean isTimeApproximatelyEqual(long time1, long time2, long toleranceMs) {
        return Math.abs(time1 - time2) <= toleranceMs;
    }
    
    /**
     * Snap time to nearest second boundary
     */
    public static long snapToSecond(long timeMs) {
        return (timeMs / 1000) * 1000;
    }
    
    /**
     * Snap time to nearest frame boundary (assuming 30fps)
     */
    public static long snapToFrame(long timeMs, float frameRate) {
        if (frameRate <= 0) {
            frameRate = 30f; // Default to 30fps
        }
        
        long frameIntervalMs = (long) (1000f / frameRate);
        return (timeMs / frameIntervalMs) * frameIntervalMs;
    }
    
    /**
     * Result class for trim range validation
     */
    public static class TrimRangeResult {
        private final long start;
        private final long end;
        private final boolean wasModified;
        
        public TrimRangeResult(long start, long end, boolean wasModified) {
            this.start = start;
            this.end = end;
            this.wasModified = wasModified;
        }
        
        public long getStart() {
            return start;
        }
        
        public long getEnd() {
            return end;
        }
        
        public boolean wasModified() {
            return wasModified;
        }
        
        public long getDuration() {
            return end - start;
        }
    }
}