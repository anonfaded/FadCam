package com.fadcam.service;

import android.net.Uri;
import java.util.UUID;

/**
 * Represents a file operation task (copy, move, delete)
 */
public class FileOperationTask {
    
    public enum OperationType {
        COPY_TO_GALLERY,
        MOVE_TO_GALLERY,
        DELETE_FILE,
        RESTORE_FILE
    }
    
    public enum TaskStatus {
        PENDING,
        IN_PROGRESS,
        COMPLETED,
        FAILED,
        CANCELLED
    }
    
    public final String taskId;
    public final OperationType type;
    public final Uri sourceUri;
    public final String fileName;
    public final String displayName;
    public TaskStatus status;
    public String errorMessage;
    public long progress;
    public long totalBytes;
    public long startTime;
    public long endTime;
    
    public FileOperationTask(OperationType type, Uri sourceUri, String fileName, String displayName) {
        this.taskId = UUID.randomUUID().toString();
        this.type = type;
        this.sourceUri = sourceUri;
        this.fileName = fileName;
        this.displayName = displayName;
        this.status = TaskStatus.PENDING;
        this.progress = 0;
        this.totalBytes = 0;
        this.startTime = System.currentTimeMillis();
    }
    
    public int getProgressPercent() {
        if (totalBytes <= 0) return 0;
        return (int) ((progress * 100) / totalBytes);
    }
    
    public String getOperationText() {
        switch (type) {
            case COPY_TO_GALLERY:
                return "Copying to Gallery";
            case MOVE_TO_GALLERY:
                return "Moving to Gallery";
            case DELETE_FILE:
                return "Deleting file";
            case RESTORE_FILE:
                return "Restoring file";
            default:
                return "Processing";
        }
    }
    
    @Override
    public String toString() {
        return "FileOperationTask{" +
                "taskId='" + taskId + '\'' +
                ", type=" + type +
                ", fileName='" + fileName + '\'' +
                ", status=" + status +
                ", progress=" + getProgressPercent() + "%" +
                '}';
    }
}