package com.fadcam.service;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Stores batch operation session snapshots to SharedPreferences.
 * Similar to RecordsDeletionSessionStore but for batch media operations.
 */
public class BatchOperationSessionStore {

    private static final String PREFS_NAME = "batch_operation_session_store";
    private static final String KEY_SNAPSHOT_JSON = "snapshot_json";

    @NonNull
    private final SharedPreferences prefs;

    public BatchOperationSessionStore(@NonNull Context context) {
        prefs = context.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    @Nullable
    public synchronized BatchOperationSessionSnapshot read() {
        return BatchOperationSessionSnapshot.fromJson(prefs.getString(KEY_SNAPSHOT_JSON, null));
    }

    @NonNull
    public synchronized BatchOperationSessionSnapshot readOrIdle() {
        BatchOperationSessionSnapshot snapshot = read();
        return snapshot == null ? new BatchOperationSessionSnapshot() : snapshot;
    }

    public synchronized void write(@NonNull BatchOperationSessionSnapshot snapshot) {
        prefs.edit().putString(KEY_SNAPSHOT_JSON, BatchOperationSessionSnapshot.toJson(snapshot)).apply();
    }

    public synchronized void clear() {
        prefs.edit().remove(KEY_SNAPSHOT_JSON).apply();
    }
}
