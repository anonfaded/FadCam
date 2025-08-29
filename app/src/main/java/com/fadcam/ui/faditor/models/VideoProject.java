package com.fadcam.ui.faditor.models;

import android.net.Uri;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Represents the current video editing session state.
 * Manages video metadata, operations, and processing capabilities.
 */
public class VideoProject {
    
    private String projectId;
    private String projectName;
    private long createdAt;
    private long lastModified;
    private Uri originalVideoUri;
    private String originalVideoPath; // For JSON serialization
    private File workingFile;
    private long duration;
    private VideoMetadata metadata;
    private List<EditOperation> operations;
    private TrimRange currentTrim;
    private ProcessingCapabilities capabilities;
    private Map<String, Object> customData; // For future extensions
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
        this.customData = new HashMap<>();
        this.hasUnsavedChanges = false;
        this.createdAt = System.currentTimeMillis();
        this.lastModified = this.createdAt;
    }
    
    public VideoProject(Uri originalVideoUri) {
        this();
        this.originalVideoUri = originalVideoUri;
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
    
    public Uri getOriginalVideoUri() {
        return originalVideoUri;
    }
    
    public void setOriginalVideoUri(Uri originalVideoUri) {
        this.originalVideoUri = originalVideoUri;
        updateLastModified();
    }
    
    public String getOriginalVideoPath() {
        return originalVideoPath;
    }
    
    public void setOriginalVideoPath(String originalVideoPath) {
        this.originalVideoPath = originalVideoPath;
        updateLastModified();
    }
    
    public File getWorkingFile() {
        return workingFile;
    }
    
    public void setWorkingFile(File workingFile) {
        this.workingFile = workingFile;
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
        return originalVideoUri != null && 
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
}