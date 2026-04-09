package com.fadcam.service;

import android.app.Service;
import android.content.Intent;
import android.net.Uri;
import android.os.IBinder;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.fadcam.Constants;
import com.fadcam.FLog;
import com.fadcam.data.VideoIndexRepository;
import com.fadcam.utils.TrashManager;
import com.fadcam.utils.VideoStatsCache;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class RecordsDeletionService extends Service {

    private static final String TAG = "RecordsDeletionService";
    private static final int FOREGROUND_ID = 1308;

    public static final String ACTION_START_DELETE_SESSION = "com.fadcam.action.START_DELETE_SESSION";
    public static final String ACTION_APPEND_TO_DELETE_SESSION = "com.fadcam.action.APPEND_TO_DELETE_SESSION";
    public static final String ACTION_ACKNOWLEDGE_DELETE_COMPLETION = "com.fadcam.action.ACKNOWLEDGE_DELETE_COMPLETION";
    public static final String EXTRA_DELETE_ITEMS_JSON = "delete_items_json";
    public static final String EXTRA_SESSION_ID = "delete_session_id";

    private final AtomicBoolean processing = new AtomicBoolean(false);
    private ExecutorService executor;
    private RecordsDeletionSessionStore sessionStore;
    private RecordsDeletionNotificationManager notificationManager;

    @Override
    public void onCreate() {
        super.onCreate();
        executor = Executors.newSingleThreadExecutor();
        sessionStore = new RecordsDeletionSessionStore(this);
        notificationManager = new RecordsDeletionNotificationManager(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            maybeResumeProcessing(startId);
            return START_REDELIVER_INTENT;
        }

        String action = intent.getAction();
        if (ACTION_ACKNOWLEDGE_DELETE_COMPLETION.equals(action)) {
            acknowledgeCompletion(intent.getStringExtra(EXTRA_SESSION_ID));
            stopSelfResult(startId);
            return START_NOT_STICKY;
        }

        List<RecordsDeletionRequestItem> items = RecordsDeletionRequestItem.fromJson(
                intent.getStringExtra(EXTRA_DELETE_ITEMS_JSON));

        if (ACTION_APPEND_TO_DELETE_SESSION.equals(action)) {
            appendItems(items);
        } else {
            startOrReplaceSession(items);
        }

        maybeResumeProcessing(startId);
        return START_REDELIVER_INTENT;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        if (executor != null) {
            executor.shutdownNow();
        }
        super.onDestroy();
    }

    public static void startDeleteSession(
            @NonNull android.content.Context context,
            @NonNull List<RecordsDeletionRequestItem> items
    ) {
        String action = ACTION_START_DELETE_SESSION;
        RecordsDeletionSessionSnapshot snapshot = new RecordsDeletionSessionStore(context).read();
        if (snapshot != null && snapshot.isActive()) {
            action = ACTION_APPEND_TO_DELETE_SESSION;
        }
        Intent intent = new Intent(context, RecordsDeletionService.class);
        intent.setAction(action);
        intent.putExtra(EXTRA_DELETE_ITEMS_JSON, RecordsDeletionRequestItem.toJson(items));
        ContextCompat.startForegroundService(context, intent);
    }

    public static void acknowledgeCompletion(
            @NonNull android.content.Context context,
            @NonNull String sessionId
    ) {
        Intent intent = new Intent(context, RecordsDeletionService.class);
        intent.setAction(ACTION_ACKNOWLEDGE_DELETE_COMPLETION);
        intent.putExtra(EXTRA_SESSION_ID, sessionId);
        context.startService(intent);
    }

    private void startOrReplaceSession(@NonNull List<RecordsDeletionRequestItem> items) {
        if (items.isEmpty()) {
            return;
        }
        RecordsDeletionSessionSnapshot existing = sessionStore.read();
        if (existing != null && existing.isActive()) {
            existing.appendItems(items);
            sessionStore.write(existing);
            publishSnapshot(existing, false, false, false);
            return;
        }
        RecordsDeletionSessionSnapshot snapshot = RecordsDeletionSessionSnapshot.create(items);
        sessionStore.write(snapshot);
        publishSnapshot(snapshot, false, false, false);
    }

    private void appendItems(@NonNull List<RecordsDeletionRequestItem> items) {
        if (items.isEmpty()) {
            return;
        }
        RecordsDeletionSessionSnapshot snapshot = sessionStore.read();
        if (snapshot == null || snapshot.isFinished()) {
            snapshot = RecordsDeletionSessionSnapshot.create(items);
        } else {
            snapshot.appendItems(items);
        }
        sessionStore.write(snapshot);
        publishSnapshot(snapshot, false, false, false);
    }

    private void maybeResumeProcessing(int startId) {
        if (processing.getAndSet(true)) {
            return;
        }
        executor.submit(() -> {
            try {
                RecordsDeletionSessionSnapshot snapshot = sessionStore.read();
                if (snapshot == null || snapshot.completionAcknowledged || snapshot.pendingItems.isEmpty() && !snapshot.isActive()) {
                    return;
                }
                if (snapshot.isFinished()) {
                    startForeground(FOREGROUND_ID, notificationManager.buildForegroundNotification(snapshot));
                    notificationManager.showCompletion(snapshot);
                    notificationManager.cancelProgress();
                    return;
                }

                snapshot.state = RecordsDeletionSessionSnapshot.State.RUNNING;
                snapshot.lastUpdatedAtMs = System.currentTimeMillis();
                startForeground(FOREGROUND_ID, notificationManager.buildForegroundNotification(snapshot));
                sessionStore.write(snapshot);
                publishSnapshot(snapshot, false, false, false);

                while (true) {
                    RecordsDeletionSessionSnapshot latest = sessionStore.read();
                    if (latest == null || latest.pendingItems.isEmpty()) {
                        finalizeSession(latest);
                        break;
                    }
                    RecordsDeletionRequestItem item = latest.pendingItems.remove(0);
                    latest.currentItemName = item.displayName;
                    latest.currentItemIndex = latest.completedItemCount + latest.failedItemCount + 1;
                    latest.state = RecordsDeletionSessionSnapshot.State.RUNNING;
                    latest.lastUpdatedAtMs = System.currentTimeMillis();
                    sessionStore.write(latest);
                    publishSnapshot(latest, false, false, false);

                    processItem(latest, item);
                }
            } finally {
                processing.set(false);
                stopForeground(STOP_FOREGROUND_REMOVE);
                notificationManager.cancelProgress();
                stopSelfResult(startId);
            }
        });
    }

    private void processItem(
            @NonNull RecordsDeletionSessionSnapshot snapshot,
            @NonNull RecordsDeletionRequestItem item
    ) {
        Uri uri = item.toUri();
        if (uri == null) {
            markFailed(snapshot, item, getString(com.fadcam.R.string.records_delete_error_invalid_item));
            return;
        }
        final long baselineProcessed = snapshot.processedBytes;
        final long itemTotalBytes = Math.max(0L, item.sizeBytes);
        boolean success = TrashManager.moveToTrash(
                this,
                uri,
                item.displayName,
                item.safSource,
                progress -> {
                    RecordsDeletionSessionSnapshot live = sessionStore.read();
                    if (live == null) {
                        return;
                    }
                    long boundedProgress = Math.max(0L, Math.min(progress.bytesCopied, itemTotalBytes));
                    live.processedBytes = baselineProcessed + boundedProgress;
                    live.currentItemName = item.displayName;
                    live.currentItemIndex = live.completedItemCount + live.failedItemCount + 1;
                    live.lastUpdatedAtMs = System.currentTimeMillis();
                    sessionStore.write(live);
                    publishSnapshot(live, false, false, false);
                }
        );

        RecordsDeletionSessionSnapshot latest = sessionStore.read();
        if (latest == null) {
            return;
        }

        if (success) {
            latest.completedItemCount++;
            latest.processedBytes = baselineProcessed + itemTotalBytes;
            latest.completedUriStrings.add(item.uriString);
            latest.lastUpdatedAtMs = System.currentTimeMillis();
            sessionStore.write(latest);
            try {
                VideoIndexRepository.getInstance(this).removeFromIndex(item.uriString);
            } catch (Exception e) {
                FLog.w(TAG, "Failed to remove deleted item from index: " + item.uriString, e);
            }
            VideoStatsCache.invalidateStats(com.fadcam.SharedPreferencesManager.getInstance(this));
            publishSnapshot(latest, true, false, false);
            return;
        }

        markFailed(latest, item, getString(com.fadcam.R.string.records_delete_error_failed_item, item.displayName));
    }

    private void markFailed(
            @NonNull RecordsDeletionSessionSnapshot snapshot,
            @NonNull RecordsDeletionRequestItem item,
            @NonNull String error
    ) {
        snapshot.failedItemCount++;
        snapshot.failedUriStrings.add(item.uriString);
        snapshot.errorSummaries.add(error);
        snapshot.lastUpdatedAtMs = System.currentTimeMillis();
        sessionStore.write(snapshot);
        publishSnapshot(snapshot, false, false, false);
    }

    private void finalizeSession(@Nullable RecordsDeletionSessionSnapshot snapshot) {
        if (snapshot == null) {
            return;
        }
        snapshot.currentItemName = null;
        snapshot.currentItemIndex = Math.max(0, snapshot.completedItemCount + snapshot.failedItemCount);
        snapshot.finishedAtMs = System.currentTimeMillis();
        snapshot.lastUpdatedAtMs = snapshot.finishedAtMs;
        if (snapshot.failedItemCount == 0 && snapshot.completedItemCount > 0) {
            snapshot.state = RecordsDeletionSessionSnapshot.State.COMPLETED_SUCCESS;
        } else if (snapshot.completedItemCount > 0 && snapshot.failedItemCount > 0) {
            snapshot.state = RecordsDeletionSessionSnapshot.State.COMPLETED_PARTIAL;
        } else if (snapshot.failedItemCount > 0) {
            snapshot.state = RecordsDeletionSessionSnapshot.State.COMPLETED_FAILED;
        } else {
            snapshot.state = RecordsDeletionSessionSnapshot.State.COMPLETED_SUCCESS;
        }
        sessionStore.write(snapshot);
        publishSnapshot(snapshot, false, true, true);
        notificationManager.showCompletion(snapshot);
    }

    private void acknowledgeCompletion(@Nullable String sessionId) {
        RecordsDeletionSessionSnapshot snapshot = sessionStore.read();
        if (snapshot == null) {
            return;
        }
        if (sessionId != null && !sessionId.equals(snapshot.sessionId)) {
            return;
        }
        snapshot.completionAcknowledged = true;
        sessionStore.clear();
        publishSnapshot(snapshot, false, false, true);
    }

    private void publishSnapshot(
            @NonNull RecordsDeletionSessionSnapshot snapshot,
            boolean itemCompleted,
            boolean sessionFinished,
            boolean includeCompletionIntent
    ) {
        notificationManager.updateProgress(snapshot);

        Intent updateIntent = new Intent(Constants.ACTION_RECORDS_DELETE_SESSION_UPDATED);
        updateIntent.putExtra(Constants.EXTRA_RECORDS_DELETE_SESSION_JSON,
                RecordsDeletionSessionSnapshot.toJson(snapshot));
        sendBroadcast(updateIntent);

        if (itemCompleted && !snapshot.completedUriStrings.isEmpty()) {
            List<String> completed = Collections.singletonList(
                    snapshot.completedUriStrings.get(snapshot.completedUriStrings.size() - 1));
            Intent itemIntent = new Intent(Constants.ACTION_RECORDS_DELETE_ITEM_COMPLETED);
            itemIntent.putStringArrayListExtra(Constants.EXTRA_RECORDS_DELETE_COMPLETED_URIS,
                    new ArrayList<>(completed));
            itemIntent.putExtra(Constants.EXTRA_RECORDS_DELETE_SESSION_ID, snapshot.sessionId);
            sendBroadcast(itemIntent);
        }

        if (sessionFinished || includeCompletionIntent) {
            Intent finishedIntent = new Intent(Constants.ACTION_RECORDS_DELETE_SESSION_FINISHED);
            finishedIntent.putExtra(Constants.EXTRA_RECORDS_DELETE_SESSION_JSON,
                    RecordsDeletionSessionSnapshot.toJson(snapshot));
            sendBroadcast(finishedIntent);
        }
    }
}
