package com.fadcam.ui.faditor.media;

import android.net.Uri;

/**
 * Represents a media asset within a video editing project.
 * Handles both copied and linked media files with professional metadata management.
 */
public class ProjectMediaAsset {
    
    // Core identification
    public String mediaId;
    public String originalFilename;
    public Uri sourceUri; // Original source URI
    
    // Project file management
    public String projectFilePath; // Relative path within project (if copied)
    public String absoluteFilePath; // Absolute path (if copied)
    public boolean isLinked; // true if linked to original, false if copied to project
    public ProjectMediaManager.MediaImportStrategy importStrategy;
    
    // Proxy and optimization
    public String proxyFilePath; // Relative path to proxy file
    public boolean hasProxy;
    public String thumbnailPath; // Relative path to thumbnail
    
    // Media analysis
    public ProjectMediaManager.MediaAnalysis analysis;
    
    // Timestamps
    public long importTimestamp;
    public long lastAccessTimestamp;
    
    // Usage tracking
    public int usageCount; // How many times this asset is used in timeline
    public boolean isInUse; // Currently being used in editing
    
    public ProjectMediaAsset() {
        this.importTimestamp = System.currentTimeMillis();
        this.lastAccessTimestamp = this.importTimestamp;
        this.usageCount = 0;
        this.isInUse = false;
        this.hasProxy = false;
        this.isLinked = false;
    }
    
    /**
     * Mark this asset as accessed (for cache management)
     */
    public void markAccessed() {
        this.lastAccessTimestamp = System.currentTimeMillis();
    }
    
    /**
     * Increment usage count when asset is added to timeline
     */
    public void incrementUsage() {
        this.usageCount++;
        this.isInUse = true;
        markAccessed();
    }
    
    /**
     * Decrement usage count when asset is removed from timeline
     */
    public void decrementUsage() {
        if (this.usageCount > 0) {
            this.usageCount--;
        }
        this.isInUse = this.usageCount > 0;
    }
    
    /**
     * Get display name for UI
     */
    public String getDisplayName() {
        return originalFilename != null ? originalFilename : "Unknown Media";
    }
    
    /**
     * Get file size in bytes
     */
    public long getFileSize() {
        return analysis != null ? analysis.fileSize : 0;
    }
    
    /**
     * Get formatted file size for display
     */
    public String getFormattedFileSize() {
        long bytes = getFileSize();
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return (bytes / 1024) + " KB";
        if (bytes < 1024 * 1024 * 1024) return (bytes / 1024 / 1024) + " MB";
        return (bytes / 1024 / 1024 / 1024) + " GB";
    }
    
    /**
     * Get video resolution string
     */
    public String getResolutionString() {
        if (analysis != null && analysis.isVideo) {
            return analysis.width + "x" + analysis.height;
        }
        return "Unknown";
    }
    
    /**
     * Get video duration in milliseconds
     */
    public long getDurationMs() {
        return analysis != null ? analysis.duration : 0;
    }
    
    /**
     * Get formatted duration for display
     */
    public String getFormattedDuration() {
        long durationMs = getDurationMs();
        if (durationMs <= 0) return "00:00";
        
        long seconds = durationMs / 1000;
        long minutes = seconds / 60;
        long hours = minutes / 60;
        
        seconds %= 60;
        minutes %= 60;
        
        if (hours > 0) {
            return String.format("%02d:%02d:%02d", hours, minutes, seconds);
        } else {
            return String.format("%02d:%02d", minutes, seconds);
        }
    }
    
    /**
     * Check if this is a video asset
     */
    public boolean isVideo() {
        return analysis != null && analysis.isVideo;
    }
    
    /**
     * Check if this is a high resolution video
     */
    public boolean isHighResolution() {
        return analysis != null && analysis.isHighResolution;
    }
    
    /**
     * Check if this is a large file
     */
    public boolean isLargeFile() {
        return analysis != null && analysis.isLargeFile;
    }
    
    /**
     * Get the MIME type
     */
    public String getMimeType() {
        return analysis != null ? analysis.mimeType : "unknown";
    }
    
    /**
     * Get the frame rate for video assets
     */
    public float getFrameRate() {
        return analysis != null ? analysis.frameRate : 0f;
    }
    
    /**
     * Get the bitrate for video assets
     */
    public int getBitrate() {
        return analysis != null ? analysis.bitrate : 0;
    }
    
    /**
     * Check if asset needs proxy generation
     */
    public boolean needsProxy() {
        return isVideo() && (isHighResolution() || isLargeFile()) && !hasProxy;
    }
    
    /**
     * Get import strategy description
     */
    public String getImportStrategyDescription() {
        if (importStrategy == null) return "Unknown";
        
        switch (importStrategy) {
            case COPY_TO_PROJECT:
                return "Copied to project";
            case LINK_ORIGINAL:
                return "Linked to original";
            case HYBRID:
                return "Hybrid strategy";
            default:
                return "Unknown";
        }
    }
    
    @Override
    public String toString() {
        return String.format("ProjectMediaAsset{id='%s', filename='%s', linked=%s, hasProxy=%s, usage=%d}", 
                           mediaId, originalFilename, isLinked, hasProxy, usageCount);
    }
    
    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        
        ProjectMediaAsset that = (ProjectMediaAsset) obj;
        return mediaId != null ? mediaId.equals(that.mediaId) : that.mediaId == null;
    }
    
    @Override
    public int hashCode() {
        return mediaId != null ? mediaId.hashCode() : 0;
    }
}