package com.fadcam.service;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.gson.Gson;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class RecordsDeletionSessionSnapshot {

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

    public enum OperationKind {
        DELETE,
        SAVE_COPY_TO_GALLERY,
        SAVE_MOVE_TO_GALLERY,
        SAVE_EXPORT_TO_CUSTOM_TREE
    }

    @NonNull public String sessionId = UUID.randomUUID().toString();
    @NonNull public State state = State.IDLE;
    @NonNull public OperationKind operationKind = OperationKind.DELETE;
    public int totalItemCount;
    public int completedItemCount;
    public int failedItemCount;
    public long totalBytes;
    public long processedBytes;
    @Nullable public String currentItemName;
    public int currentItemIndex;
    public long startedAtMs;
    public long lastUpdatedAtMs;
    public long finishedAtMs;
    public boolean completionAcknowledged;
    @NonNull public List<RecordsDeletionRequestItem> pendingItems = new ArrayList<>();
    @NonNull public List<String> completedUriStrings = new ArrayList<>();
    @NonNull public List<String> failedUriStrings = new ArrayList<>();
    @NonNull public List<String> errorSummaries = new ArrayList<>();
    @Nullable public String customTreeUri;  // For SAVE_EXPORT_TO_CUSTOM_TREE operations

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
            itemPercent = Math.max(0, Math.min(100, ((completedItemCount + failedItemCount) * 100) / totalItemCount));
        }
        if (totalBytes > 0L) {
            int bytePercent = (int) Math.max(0L, Math.min(100L, (processedBytes * 100L) / totalBytes));
            return Math.max(bytePercent, itemPercent);
        }
        return itemPercent;
    }

    @NonNull
    public static RecordsDeletionSessionSnapshot create(@NonNull List<RecordsDeletionRequestItem> items) {
        return create(items, OperationKind.DELETE);
    }

    @NonNull
    public static RecordsDeletionSessionSnapshot create(
            @NonNull List<RecordsDeletionRequestItem> items,
            @NonNull OperationKind operationKind
    ) {
        RecordsDeletionSessionSnapshot snapshot = new RecordsDeletionSessionSnapshot();
        snapshot.pendingItems = new ArrayList<>(items);
        snapshot.operationKind = operationKind;
        snapshot.totalItemCount = items.size();
        snapshot.totalBytes = sumBytes(items);
        long now = System.currentTimeMillis();
        snapshot.startedAtMs = now;
        snapshot.lastUpdatedAtMs = now;
        snapshot.state = items.isEmpty() ? State.COMPLETED_SUCCESS : State.QUEUED;
        return snapshot;
    }

    public void appendItems(@NonNull List<RecordsDeletionRequestItem> items) {
        if (items.isEmpty()) {
            return;
        }
        pendingItems.addAll(items);
        totalItemCount += items.size();
        totalBytes += sumBytes(items);
        lastUpdatedAtMs = System.currentTimeMillis();
        if (!isFinished()) {
            state = State.QUEUED;
        }
    }

    @NonNull
    public RecordsDeletionSessionSnapshot copy() {
        RecordsDeletionSessionSnapshot copy = fromJson(toJson(this));
        return copy == null ? new RecordsDeletionSessionSnapshot() : copy;
    }

    @NonNull
    public static String toJson(@NonNull RecordsDeletionSessionSnapshot snapshot) {
        return GSON.toJson(snapshot);
    }

    @Nullable
    public static RecordsDeletionSessionSnapshot fromJson(@Nullable String json) {
        if (json == null || json.trim().isEmpty()) {
            return null;
        }
        try {
            return GSON.fromJson(json, RecordsDeletionSessionSnapshot.class);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static long sumBytes(@NonNull List<RecordsDeletionRequestItem> items) {
        long total = 0L;
        for (RecordsDeletionRequestItem item : items) {
            total += Math.max(0L, item.sizeBytes);
        }
        return total;
    }
}
