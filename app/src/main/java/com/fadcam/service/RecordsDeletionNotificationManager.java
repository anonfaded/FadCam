package com.fadcam.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import com.fadcam.Constants;
import com.fadcam.MainActivity;
import com.fadcam.R;

public class RecordsDeletionNotificationManager {

    private static final String CHANNEL_ID = "records_deletion";
    private static final int NOTIFICATION_ID = 1308;

    @NonNull private final Context context;
    @NonNull private final NotificationManagerCompat manager;

    public RecordsDeletionNotificationManager(@NonNull Context context) {
        this.context = context.getApplicationContext();
        this.manager = NotificationManagerCompat.from(this.context);
        createChannel();
    }

    @NonNull
    public Notification buildForegroundNotification(@NonNull RecordsDeletionSessionSnapshot snapshot) {
        NotificationCompat.Builder builder = baseBuilder(snapshot)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .addAction(
                        0,
                        context.getString(R.string.records_delete_notification_cancel),
                        buildCancelPendingIntent(snapshot)
                );
        applyProgress(builder, snapshot);
        return builder.build();
    }

    public void updateProgress(@NonNull RecordsDeletionSessionSnapshot snapshot) {
        notifySafely(NOTIFICATION_ID, buildForegroundNotification(snapshot));
    }

    public void showCompletion(@NonNull RecordsDeletionSessionSnapshot snapshot) {
        String title;
        if (snapshot.state == RecordsDeletionSessionSnapshot.State.COMPLETED_SUCCESS) {
            title = getCompletionSuccessTitle(snapshot);
        } else if (snapshot.state == RecordsDeletionSessionSnapshot.State.CANCELLED) {
            title = getCompletionCancelledTitle(snapshot);
        } else if (snapshot.state == RecordsDeletionSessionSnapshot.State.COMPLETED_PARTIAL) {
            title = getCompletionPartialTitle(snapshot);
        } else {
            title = getCompletionFailedTitle(snapshot);
        }
        Notification notification = baseBuilder(snapshot)
                .setContentTitle(title)
                .setContentText(buildCompletionSummaryText(snapshot))
                .setSmallIcon(snapshot.state == RecordsDeletionSessionSnapshot.State.COMPLETED_SUCCESS
                        ? R.drawable.ic_check_circle
                        : R.drawable.ic_error)
                .setProgress(0, 0, false)
                .setOngoing(false)
                .setAutoCancel(true)
                .build();
        notifySafely(NOTIFICATION_ID + 1, notification);
    }

    public void cancelProgress() {
        manager.cancel(NOTIFICATION_ID);
    }

    public void cancelCompletion() {
        manager.cancel(NOTIFICATION_ID + 1);
    }

    @NonNull
    private NotificationCompat.Builder baseBuilder(@NonNull RecordsDeletionSessionSnapshot snapshot) {
        Intent openIntent = new Intent(context, MainActivity.class);
        openIntent.setAction(Constants.ACTION_SHOW_RECORDS);
        openIntent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                context,
                0,
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        return new NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_notification)
                .setContentTitle(getActiveTitle(snapshot))
                .setContentText(buildActiveSummaryText(snapshot))
                .setContentIntent(pendingIntent)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE);
    }

    @NonNull
    private PendingIntent buildCancelPendingIntent(@NonNull RecordsDeletionSessionSnapshot snapshot) {
        Intent cancelIntent = new Intent(context, RecordsDeletionService.class);
        cancelIntent.setAction(RecordsDeletionService.ACTION_CANCEL_DELETE_SESSION);
        cancelIntent.putExtra(RecordsDeletionService.EXTRA_SESSION_ID, snapshot.sessionId);
        return PendingIntent.getService(
                context,
                1309,
                cancelIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
    }

    private void applyProgress(
            @NonNull NotificationCompat.Builder builder,
            @NonNull RecordsDeletionSessionSnapshot snapshot
    ) {
        if (snapshot.totalBytes > 0L) {
            int max = (int) Math.min(Integer.MAX_VALUE, snapshot.totalBytes);
            int progress = (int) Math.min(Integer.MAX_VALUE, snapshot.processedBytes);
            builder.setProgress(Math.max(1, max), Math.max(0, Math.min(progress, max)), false);
            return;
        }
        if (snapshot.totalItemCount > 0) {
            int done = snapshot.completedItemCount + snapshot.failedItemCount;
            builder.setProgress(snapshot.totalItemCount, Math.max(0, Math.min(done, snapshot.totalItemCount)), false);
        } else {
            builder.setProgress(0, 0, true);
        }
    }

    @NonNull
    private String buildActiveSummaryText(@NonNull RecordsDeletionSessionSnapshot snapshot) {
        String currentItem = snapshot.currentItemName;
        if (currentItem != null && !currentItem.trim().isEmpty() && snapshot.isActive()) {
            return context.getString(
                    getProgressTextRes(snapshot),
                    Math.max(0, snapshot.completedItemCount + snapshot.failedItemCount),
                    Math.max(1, snapshot.totalItemCount),
                    currentItem
            );
        }
        return getOperationSummaryText(snapshot, snapshot.completedItemCount, snapshot.failedItemCount);
    }

    @NonNull
    private String buildCompletionSummaryText(@NonNull RecordsDeletionSessionSnapshot snapshot) {
        return getOperationSummaryText(snapshot, snapshot.completedItemCount, snapshot.failedItemCount);
    }

    private int getProgressTextRes(@NonNull RecordsDeletionSessionSnapshot snapshot) {
        switch (snapshot.operationKind) {
            case SAVE_COPY_TO_GALLERY:
            case SAVE_MOVE_TO_GALLERY:
            case DELETE:
            default:
                return R.string.records_delete_notification_progress_text;
        }
    }

    @NonNull
    private String getOperationSummaryText(
            @NonNull RecordsDeletionSessionSnapshot snapshot,
            int completedCount,
            int failedCount
    ) {
        switch (snapshot.operationKind) {
            case SAVE_COPY_TO_GALLERY:
                return context.getString(R.string.records_save_notification_summary_text_copy,
                        completedCount, failedCount);
            case SAVE_MOVE_TO_GALLERY:
                return context.getString(R.string.records_save_notification_summary_text_move,
                        completedCount, failedCount);
            case SAVE_EXPORT_TO_CUSTOM_TREE:
                return context.getString(R.string.records_save_notification_summary_text_export,
                        completedCount, failedCount);
            case DELETE:
            default:
                return context.getString(R.string.records_delete_notification_summary_text,
                        completedCount, failedCount);
        }
    }

    @NonNull
    private String getActiveTitle(@NonNull RecordsDeletionSessionSnapshot snapshot) {
        switch (snapshot.operationKind) {
            case SAVE_COPY_TO_GALLERY:
                return context.getString(R.string.records_save_notification_title_copy);
            case SAVE_MOVE_TO_GALLERY:
                return context.getString(R.string.records_save_notification_title_move);
            case SAVE_EXPORT_TO_CUSTOM_TREE:
                return context.getString(R.string.records_save_notification_title_export);
            case DELETE:
            default:
                return context.getString(R.string.records_delete_notification_title);
        }
    }

    @NonNull
    private String getCompletionSuccessTitle(@NonNull RecordsDeletionSessionSnapshot snapshot) {
        switch (snapshot.operationKind) {
            case SAVE_COPY_TO_GALLERY:
                return context.getString(R.string.records_save_notification_complete_success_copy);
            case SAVE_MOVE_TO_GALLERY:
                return context.getString(R.string.records_save_notification_complete_success_move);
            case SAVE_EXPORT_TO_CUSTOM_TREE:
                return context.getString(R.string.records_save_notification_complete_success_export);
            case DELETE:
            default:
                return context.getString(R.string.records_delete_notification_complete_success);
        }
    }

    @NonNull
    private String getCompletionPartialTitle(@NonNull RecordsDeletionSessionSnapshot snapshot) {
        switch (snapshot.operationKind) {
            case SAVE_COPY_TO_GALLERY:
            case SAVE_MOVE_TO_GALLERY:
            case SAVE_EXPORT_TO_CUSTOM_TREE:
                return context.getString(R.string.records_save_notification_complete_partial);
            case DELETE:
            default:
                return context.getString(R.string.records_delete_notification_complete_partial);
        }
    }

    @NonNull
    private String getCompletionFailedTitle(@NonNull RecordsDeletionSessionSnapshot snapshot) {
        switch (snapshot.operationKind) {
            case SAVE_COPY_TO_GALLERY:
            case SAVE_MOVE_TO_GALLERY:
            case SAVE_EXPORT_TO_CUSTOM_TREE:
                return context.getString(R.string.records_save_notification_complete_failed);
            case DELETE:
            default:
                return context.getString(R.string.records_delete_notification_complete_failed);
        }
    }

    @NonNull
    private String getCompletionCancelledTitle(@NonNull RecordsDeletionSessionSnapshot snapshot) {
        switch (snapshot.operationKind) {
            case SAVE_COPY_TO_GALLERY:
            case SAVE_MOVE_TO_GALLERY:
            case SAVE_EXPORT_TO_CUSTOM_TREE:
                return context.getString(R.string.records_save_notification_cancelled);
            case DELETE:
            default:
                return context.getString(R.string.records_delete_notification_cancelled);
        }
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.records_delete_notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
        );
        channel.setDescription(context.getString(R.string.records_delete_notification_channel_desc));
        channel.setShowBadge(false);
        NotificationManager notificationManager = context.getSystemService(NotificationManager.class);
        if (notificationManager != null) {
            notificationManager.createNotificationChannel(channel);
        }
    }

    private void notifySafely(int id, @NonNull Notification notification) {
        try {
            manager.notify(id, notification);
        } catch (SecurityException ignored) {
        }
    }
}
