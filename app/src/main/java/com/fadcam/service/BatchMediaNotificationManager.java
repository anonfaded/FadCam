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

import com.fadcam.MainActivity;
import com.fadcam.R;

public class BatchMediaNotificationManager {

    private static final String CHANNEL_ID = "batch_media_actions";
    private static final int NOTIFICATION_ID = 1204;

    private final Context context;
    private final NotificationManagerCompat manager;

    public BatchMediaNotificationManager(@NonNull Context context) {
        this.context = context;
        this.manager = NotificationManagerCompat.from(context);
        createChannel();
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.records_batch_notif_channel_name),
                NotificationManager.IMPORTANCE_LOW
        );
        channel.setDescription(context.getString(R.string.records_batch_notif_channel_desc));
        channel.setShowBadge(false);
        NotificationManager nm = context.getSystemService(NotificationManager.class);
        if (nm != null) nm.createNotificationChannel(channel);
    }

    @NonNull
    public Notification createForegroundNotification(@NonNull String title, @NonNull String text) {
        return baseBuilder(title, text)
                .setProgress(0, 0, true)
                .setOngoing(true)
                .build();
    }

    public void updateProgress(
            @NonNull String title,
            @NonNull String text,
            int done,
            int total
    ) {
        NotificationCompat.Builder b = baseBuilder(title, text)
                .setOngoing(true);
        if (total > 0) {
            b.setProgress(total, Math.max(0, Math.min(done, total)), false);
        } else {
            b.setProgress(0, 0, true);
        }
        notifySafely(NOTIFICATION_ID, b.build());
    }

    public void showCompletion(@NonNull String title, @NonNull String text, boolean success) {
        Notification notification = baseBuilder(title, text)
                .setSmallIcon(success ? R.drawable.ic_check_circle : R.drawable.ic_error)
                .setProgress(0, 0, false)
                .setOngoing(false)
                .setAutoCancel(true)
                .build();
        notifySafely(NOTIFICATION_ID + 1, notification);
    }

    public void cancelProgress() {
        manager.cancel(NOTIFICATION_ID);
    }

    private void notifySafely(int id, @NonNull Notification notification) {
        try {
            manager.notify(id, notification);
        } catch (SecurityException ignored) {
        }
    }

    @NonNull
    private NotificationCompat.Builder baseBuilder(@NonNull String title, @NonNull String text) {
        Intent openIntent = new Intent(context, MainActivity.class);
        openIntent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent openPending = PendingIntent.getActivity(
                context,
                0,
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        return new NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_notification)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(openPending)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE);
    }
}
