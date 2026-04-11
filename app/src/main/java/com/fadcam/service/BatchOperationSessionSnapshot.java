package com.fadcam.service;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.google.gson.Gson;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Represents a snapshot of a batch operation session (merge, export MP4, etc.)
 * Similar to RecordsDeletionSessionSnapshot but for general batch media operations.
 */
public class BatchOperationSessionSnapshot {

    private static final Gson GSON = new Gson();

    public enum State {
        IDLE,
        QUEUED,
        RUNNING,
        CANCELLED,
        COMPLETED_SUCCESS,
        COMPLETED_PARTIAL,
        COMPLETED_FAILED
    }

    public enum OperationType {
        MERGE_VIDEOS,
        EXPORT_STANDARD_MP4,
        EXPORT_CUSTOM,
        OTHER
    }

    @NonNull public String sessionId = UUID.randomUUID().toString();
    @NonNull public State state = State.IDLE;
    @NonNull public OperationType operationType = OperationType.OTHER;
    public int totalItemCount;
    public int completedItemCount;
    public int failedItemCount;
    public int skippedItemCount;
    public long totalBytes;
    public long processedBytes;
    @Nullable public String currentItemName;
    public int currentItemIndex;
    public long startedAtMs;
    public long lastUpdatedAtMs;
    public long finishedAtMs;
    public boolean completionAcknowledged;
    @NonNull public List<String> completedItemUris = new ArrayList<>();
    @NonNull public List<String> failedItemUris = new ArrayList<>();
    @NonNull public List<String> skippedItemUris = new ArrayList<>();
    @NonNull public List<String> errorSummaries = new ArrayList<>();

    public boolean isActive() {
        return state == State.QUEUED || state == State.RUNNING;
    }

    public boolean isFinished() {
        return state == State.CANCELLED
                || state == State.COMPLETED_SUCCESS
                || state == State.COMPLETED_PARTIAL
                || state == State.COMPLETED_FAILED;
    }

    public int getProgressPercent() {
        int itemPercent = 0;
        if (totalItemCount > 0) {
            itemPercent = Math.max(0, Math.min(100, ((completedItemCount + failedItemCount + skippedItemCount) * 100) / totalItemCount));
        }
        if (totalBytes > 0L) {
            int bytePercent = (int) Math.max(0L, Math.min(100L, (processedBytes * 100L) / totalBytes));
            return Math.max(bytePercent, itemPercent);
        }
        return itemPercent;
    }

    @NonNull
    public static BatchOperationSessionSnapshot create(@NonNull OperationType operationType, int itemCount, long totalBytes) {
        BatchOperationSessionSnapshot snapshot = new BatchOperationSessionSnapshot();
        snapshot.operationType = operationType;
        snapshot.totalItemCount = itemCount;
        snapshot.totalBytes = Math.max(0L, totalBytes);
        long now = System.currentTimeMillis();
        snapshot.startedAtMs = now;
        snapshot.lastUpdatedAtMs = now;
        snapshot.state = itemCount == 0 ? State.COMPLETED_SUCCESS : State.QUEUED;
        return snapshot;
    }

    @Nullable
    public static BatchOperationSessionSnapshot fromJson(@Nullable String json) {
        if (json == null || json.trim().isEmpty()) {
            return null;
        }
        try {
            return GSON.fromJson(json, BatchOperationSessionSnapshot.class);
        } catch (Exception ignored) {
            return null;
        }
    }

    @NonNull
    public static String toJson(@NonNull BatchOperationSessionSnapshot snapshot) {
        return GSON.toJson(snapshot);
    }

    // Getters
    @NonNull
    public String getSessionId() {
        return sessionId;
    }

    @NonNull
    public State getState() {
        return state;
    }

    @NonNull
    public OperationType getOperationType() {
        return operationType;
    }

    public int getTotalItemCount() {
        return totalItemCount;
    }

    public int getCompletedItemCount() {
        return completedItemCount;
    }

    public int getFailedItemCount() {
        return failedItemCount;
    }

    public int getSkippedItemCount() {
        return skippedItemCount;
    }

    public long getTotalBytes() {
        return totalBytes;
    }

    public long getProcessedBytes() {
        return processedBytes;
    }

    @Nullable
    public String getCurrentItemName() {
        return currentItemName;
    }

    public int getCurrentItemIndex() {
        return currentItemIndex;
    }

    public long getStartedAtMs() {
        return startedAtMs;
    }

    public long getLastUpdatedAtMs() {
        return lastUpdatedAtMs;
    }

    public long getFinishedAtMs() {
        return finishedAtMs;
    }

    @NonNull
    public List<String> getCompletedItemUris() {
        return completedItemUris;
    }

    @NonNull
    public List<String> getFailedItemUris() {
        return failedItemUris;
    }

    @NonNull
    public List<String> getSkippedItemUris() {
        return skippedItemUris;
    }

    @NonNull
    public List<String> getErrorSummaries() {
        return errorSummaries;
    }

    // Setters
    public void setState(@NonNull State newState) {
        this.state = newState;
        this.lastUpdatedAtMs = System.currentTimeMillis();
        if (newState.equals(State.COMPLETED_SUCCESS) || newState.equals(State.COMPLETED_FAILED) || newState.equals(State.COMPLETED_PARTIAL)) {
            this.finishedAtMs = this.lastUpdatedAtMs;
        }
    }

    public void setCompletedItemCount(int count) {
        this.completedItemCount = Math.max(0, count);
        this.lastUpdatedAtMs = System.currentTimeMillis();
    }

    public void setFailedItemCount(int count) {
        this.failedItemCount = Math.max(0, count);
        this.lastUpdatedAtMs = System.currentTimeMillis();
    }

    public void setSkippedItemCount(int count) {
        this.skippedItemCount = Math.max(0, count);
        this.lastUpdatedAtMs = System.currentTimeMillis();
    }

    public void setCurrentItemName(@Nullable String name) {
        this.currentItemName = name;
        this.lastUpdatedAtMs = System.currentTimeMillis();
    }

    public void setCurrentItemIndex(int index) {
        this.currentItemIndex = Math.max(0, index);
        this.lastUpdatedAtMs = System.currentTimeMillis();
    }

    public void setProcessedBytes(long bytes) {
        this.processedBytes = Math.max(0L, bytes);
        this.lastUpdatedAtMs = System.currentTimeMillis();
    }

    // Helper methods for updating URI lists
    public void addCompletedItemUri(@NonNull String uri) {
        completedItemUris.add(uri);
        this.lastUpdatedAtMs = System.currentTimeMillis();
    }

    public void addFailedItemUri(@NonNull String uri) {
        failedItemUris.add(uri);
        this.lastUpdatedAtMs = System.currentTimeMillis();
    }

    public void addSkippedItemUri(@NonNull String uri) {
        skippedItemUris.add(uri);
        this.lastUpdatedAtMs = System.currentTimeMillis();
    }

    public void addErrorSummary(@NonNull String error) {
        errorSummaries.add(error);
        this.lastUpdatedAtMs = System.currentTimeMillis();
    }

    @Override
    public String toString() {
        return "BatchOperationSessionSnapshot{" +
                "sessionId='" + sessionId + '\'' +
                ", state=" + state +
                ", operationType=" + operationType +
                ", totalItemCount=" + totalItemCount +
                ", completedItemCount=" + completedItemCount +
                ", failedItemCount=" + failedItemCount +
                ", skippedItemCount=" + skippedItemCount +
                ", currentItemName='" + currentItemName + '\'' +
                '}';
    }
}
