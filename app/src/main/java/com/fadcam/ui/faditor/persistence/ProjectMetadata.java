package com.fadcam.ui.faditor.persistence;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.Ignore;
import androidx.room.PrimaryKey;

/**
 * Lightweight project info for browser and indexing
 */
@Entity(tableName = "projects")
public class ProjectMetadata {
    @PrimaryKey
    @NonNull
    private String projectId;
    private String projectName;
    private String thumbnailPath;
    private long createdAt;
    private long lastModified;
    private long duration;
    private String originalVideoName;
    private long fileSize;
    private boolean hasUnsavedChanges;

    // Constructors
    public ProjectMetadata() {}

    @Ignore
    public ProjectMetadata(String projectId, String projectName, String thumbnailPath, 
                          long createdAt, long lastModified, long duration, 
                          String originalVideoName, long fileSize, boolean hasUnsavedChanges) {
        this.projectId = projectId;
        this.projectName = projectName;
        this.thumbnailPath = thumbnailPath;
        this.createdAt = createdAt;
        this.lastModified = lastModified;
        this.duration = duration;
        this.originalVideoName = originalVideoName;
        this.fileSize = fileSize;
        this.hasUnsavedChanges = hasUnsavedChanges;
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
    }

    public String getThumbnailPath() {
        return thumbnailPath;
    }

    public void setThumbnailPath(String thumbnailPath) {
        this.thumbnailPath = thumbnailPath;
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

    public long getDuration() {
        return duration;
    }

    public void setDuration(long duration) {
        this.duration = duration;
    }

    public String getOriginalVideoName() {
        return originalVideoName;
    }

    public void setOriginalVideoName(String originalVideoName) {
        this.originalVideoName = originalVideoName;
    }

    public long getFileSize() {
        return fileSize;
    }

    public void setFileSize(long fileSize) {
        this.fileSize = fileSize;
    }

    public boolean isHasUnsavedChanges() {
        return hasUnsavedChanges;
    }

    public void setHasUnsavedChanges(boolean hasUnsavedChanges) {
        this.hasUnsavedChanges = hasUnsavedChanges;
    }
}