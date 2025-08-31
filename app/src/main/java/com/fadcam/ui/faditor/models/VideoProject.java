package com.fadcam.ui.faditor.models;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Represents a professional video editing project.
 * Uses modern media asset management with project-based file organization.
 */
public class VideoProject {
    
    private String projectId;
    private String projectName;
    private long createdAt;
    private long lastModified;
    private String primaryMediaAssetId; // Main video asset ID
    private List<String> mediaAssetIds; // All media assets in project
    private long duration;
    private VideoMetadata metadata;
    private List<EditOperation> operations;
    private TrimRange currentTrim;
    private ProcessingCapabilities capabilities;
    private Map<String, Object> customData;
    private boolean hasUnsavedChanges;
    
    public static class TrimRange {
        private final long startTime;
        private final long endTime;
        
        public TrimRange(long startTime, long endTime) {
            this.startTime = startTime;
            this.endTime = endTime;
        }
        
        public long getStartTime() {
            return startTime;
        }
        
        public long getEndTime() {
            return endTime;
        }
        
        // Legacy getters for backward compatibility
        public long getStartMs() {
            return startTime;
        }
        
        public long getEndMs() {
            return endTime;
        }
    }
    
    public static class ProcessingCapabilities {
        public final boolean supportsLosslessTrim;
        public final boolean supportsHardwareEncoding;
        public final boolean supportsOpenGLProcessing;
        
        public ProcessingCapabilities(boolean supportsLosslessTrim, 
                                    boolean supportsHardwareEncoding, 
                                    boolean supportsOpenGLProcessing) {
            this.supportsLosslessTrim = supportsLosslessTrim;
            this.supportsHardwareEncoding = supportsHardwareEncoding;
            this.supportsOpenGLProcessing = supportsOpenGLProcessing;
        }
    }
    
    public enum ProcessingMethod {
        LOSSLESS_STREAM_COPY,
        HARDWARE_REENCODING,
        SOFTWARE_REENCODING
    }
    
    public VideoProject() {
        this.operations = new ArrayList<>();
        this.mediaAssetIds = new ArrayList<>();
        this.customData = new HashMap<>();
        this.hasUnsavedChanges = false;
        this.createdAt = System.currentTimeMillis();
        this.lastModified = this.createdAt;
    }
    
    public VideoProject(String primaryMediaAssetId) {
        this();
        this.primaryMediaAssetId = primaryMediaAssetId;
        this.mediaAssetIds.add(primaryMediaAssetId);
    }
    
    // Getters and setters
    public String getProjectId() {
        return projectId;
    }
    
    public void setProjectId(String projectId) {
        this.projectId = projectId;
    }
    
    public String getProjectName() {
        return projectName;
    }
    
    public void setProjectName(String projectName) {
        this.projectName = projectName;
        updateLastModified();
    }
    
    public long getCreatedAt() {
        return createdAt;
    }
    
    public void setCreatedAt(long createdAt) {
        this.createdAt = createdAt;
    }
    
    public long getLastModified() {
        return lastModified;
    }
    
    public void setLastModified(long lastModified) {
        this.lastModified = lastModified;
    }
    
    /**
     * Get the primary media asset ID (main video)
     */
    public String getPrimaryMediaAssetId() {
        return primaryMediaAssetId;
    }
    
    /**
     * Set the primary media asset ID (main video)
     */
    public void setPrimaryMediaAssetId(String primaryMediaAssetId) {
        this.primaryMediaAssetId = primaryMediaAssetId;
        if (primaryMediaAssetId != null && !mediaAssetIds.contains(primaryMediaAssetId)) {
            mediaAssetIds.add(0, primaryMediaAssetId); // Add at beginning
        }
        updateLastModified();
    }
    
    /**
     * Get all media asset IDs in the project
     */
    public List<String> getMediaAssetIds() {
        return new ArrayList<>(mediaAssetIds);
    }
    
    /**
     * Set all media asset IDs in the project
     */
    public void setMediaAssetIds(List<String> mediaAssetIds) {
        this.mediaAssetIds = mediaAssetIds != null ? new ArrayList<>(mediaAssetIds) : new ArrayList<>();
        updateLastModified();
    }
    
    /**
     * Add a media asset to the project
     */
    public void addMediaAsset(String mediaAssetId) {
        if (mediaAssetId != null && !mediaAssetIds.contains(mediaAssetId)) {
            mediaAssetIds.add(mediaAssetId);
            updateLastModified();
        }
    }
    
    /**
     * Remove a media asset from the project
     */
    public void removeMediaAsset(String mediaAssetId) {
        if (mediaAssetIds.remove(mediaAssetId)) {
            // If removing primary asset, set new primary if available
            if (mediaAssetId.equals(primaryMediaAssetId)) {
                primaryMediaAssetId = mediaAssetIds.isEmpty() ? null : mediaAssetIds.get(0);
            }
            updateLastModified();
        }
    }
    
    public long getDuration() {
        return duration;
    }
    
    public void setDuration(long duration) {
        this.duration = duration;
    }
    
    public VideoMetadata getMetadata() {
        return metadata;
    }
    
    public void setMetadata(VideoMetadata metadata) {
        this.metadata = metadata;
    }
    
    public List<EditOperation> getOperations() {
        return new ArrayList<>(operations);
    }
    
    public void setOperations(List<EditOperation> operations) {
        this.operations = operations != null ? new ArrayList<>(operations) : new ArrayList<>();
        updateLastModified();
    }
    
    public TrimRange getCurrentTrim() {
        return currentTrim;
    }
    
    public void setCurrentTrim(TrimRange currentTrim) {
        this.currentTrim = currentTrim;
        this.hasUnsavedChanges = true;
    }
    
    public ProcessingCapabilities getCapabilities() {
        return capabilities;
    }
    
    public void setCapabilities(ProcessingCapabilities capabilities) {
        this.capabilities = capabilities;
    }
    
    public Map<String, Object> getCustomData() {
        return customData;
    }
    
    public void setCustomData(Map<String, Object> customData) {
        this.customData = customData != null ? new HashMap<>(customData) : new HashMap<>();
        updateLastModified();
    }
    
    public boolean hasUnsavedChanges() {
        return hasUnsavedChanges;
    }
    
    public void addOperation(EditOperation operation) {
        operations.add(operation);
        hasUnsavedChanges = true;
        updateLastModified();
    }
    
    public void updateLastModified() {
        this.lastModified = System.currentTimeMillis();
        this.hasUnsavedChanges = true;
    }
    
    public boolean canProcessLossless() {
        return capabilities != null && capabilities.supportsLosslessTrim;
    }
    
    public ProcessingMethod getOptimalProcessingMethod() {
        if (canProcessLossless()) {
            return ProcessingMethod.LOSSLESS_STREAM_COPY;
        } else if (capabilities != null && capabilities.supportsHardwareEncoding) {
            return ProcessingMethod.HARDWARE_REENCODING;
        } else {
            return ProcessingMethod.SOFTWARE_REENCODING;
        }
    }
    
    public void markSaved() {
        hasUnsavedChanges = false;
    }
    
    /**
     * Validate the current project state
     */
    public boolean isValid() {
        return primaryMediaAssetId != null && 
               metadata != null && 
               duration > 0;
    }
    
    /**
     * Check if the current trim range is valid
     */
    public boolean isCurrentTrimValid() {
        if (currentTrim == null) {
            return true; // No trim is valid
        }
        return currentTrim.getStartMs() >= 0 && 
               currentTrim.getEndMs() <= duration && 
               currentTrim.getStartMs() < currentTrim.getEndMs();
    }
    
    /**
     * Get the effective duration after applying current trim
     */
    public long getEffectiveDuration() {
        if (currentTrim == null) {
            return duration;
        }
        return currentTrim.getEndMs() - currentTrim.getStartMs();
    }
    
    /**
     * Reset the project to its initial state
     */
    public void reset() {
        operations.clear();
        currentTrim = null;
        hasUnsavedChanges = false;
    }
    
    /**
     * Check if any operations have been applied
     */
    public boolean hasOperations() {
        return !operations.isEmpty() || currentTrim != null;
    }
    
    // ========== BACKWARD COMPATIBILITY METHODS ==========
    // These methods provide compatibility with legacy code that expects URI/path-based access
    
    /**
     * @deprecated Use getPrimaryMediaAssetId() instead
     */
    @Deprecated
    public String getMediaAssetId() {
        return primaryMediaAssetId;
    }
    
    /**
     * @deprecated Use setPrimaryMediaAssetId() instead
     */
    @Deprecated
    public void setMediaAssetId(String mediaAssetId) {
        setPrimaryMediaAssetId(mediaAssetId);
    }
    
    /**
     * @deprecated Legacy method - returns null as projects now use media assets
     */
    @Deprecated
    public android.net.Uri getOriginalVideoUri() {
        return null;
    }
    
    /**
     * @deprecated Legacy method - no-op as projects now use media assets
     */
    @Deprecated
    public void setOriginalVideoUri(android.net.Uri uri) {
        // No-op for backward compatibility
    }
    
    /**
     * @deprecated Legacy method - returns null as projects now use media assets
     */
    @Deprecated
    public String getOriginalVideoPath() {
        return null;
    }
    
    /**
     * @deprecated Legacy method - no-op as projects now use media assets
     */
    @Deprecated
    public void setOriginalVideoPath(String path) {
        // No-op for backward compatibility
    }
    
    /**
     * @deprecated Legacy method - returns null as projects now use media assets
     */
    @Deprecated
    public java.io.File getWorkingFile() {
        return null;
    }
    
    /**
     * @deprecated Legacy method - no-op as projects now use media assets
     */
    @Deprecated
    public void setWorkingFile(java.io.File file) {
        // No-op for backward compatibility
    }
}