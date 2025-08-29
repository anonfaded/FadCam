package com.fadcam.ui.faditor.models;

import android.net.Uri;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Represents the current video editing session state.
 * Manages video metadata, operations, and processing capabilities.
 */
public class VideoProject {
    
    private Uri originalVideoUri;
    private File workingFile;
    private long duration;
    private VideoMetadata metadata;
    private List<EditOperation> operations;
    private TrimRange currentTrim;
    private ProcessingCapabilities capabilities;
    private boolean hasUnsavedChanges;
    
    public static class TrimRange {
        public final long startMs;
        public final long endMs;
        
        public TrimRange(long startMs, long endMs) {
            this.startMs = startMs;
            this.endMs = endMs;
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
        this.hasUnsavedChanges = false;
    }
    
    public VideoProject(Uri originalVideoUri) {
        this();
        this.originalVideoUri = originalVideoUri;
    }
    
    // Getters and setters
    public Uri getOriginalVideoUri() {
        return originalVideoUri;
    }
    
    public void setOriginalVideoUri(Uri originalVideoUri) {
        this.originalVideoUri = originalVideoUri;
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
    
    public boolean hasUnsavedChanges() {
        return hasUnsavedChanges;
    }
    
    public void addOperation(EditOperation operation) {
        operations.add(operation);
        hasUnsavedChanges = true;
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
        return currentTrim.startMs >= 0 && 
               currentTrim.endMs <= duration && 
               currentTrim.startMs < currentTrim.endMs;
    }
    
    /**
     * Get the effective duration after applying current trim
     */
    public long getEffectiveDuration() {
        if (currentTrim == null) {
            return duration;
        }
        return currentTrim.endMs - currentTrim.startMs;
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