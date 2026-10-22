package com.fadcam.pro;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import com.fadcam.R;
import com.fadcam.ui.FadCamProActivity;

/** Builds and posts the one weekly Pro reminder notification. */
public final class ProReminderNotification {

    public static final int NOTIFICATION_ID = 4107;
    private static final String CHANNEL_ID = "pro_reminder";
    private static final int REQUEST_CODE = 4108;

    private ProReminderNotification() {
    }

    public static boolean post(Context context) {
        try {
            ensureChannel(context);
            ProReminderManager.NotificationCopy copy =
                    ProReminderManager.getNotificationCopy(
                            context, ProReminderManager.getNotificationCopyIndex(context));

            // Open Pro directly: no Home frame on the way in. Pro restores Home on Back when
            // it was the task root, so the after-Pro confirmation still lands correctly.
            Intent intent = new Intent(context, FadCamProActivity.class);
            intent.putExtra(ProReminderManager.EXTRA_FROM_NOTIFICATION, true);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION);
            PendingIntent pendingIntent = PendingIntent.getActivity(
                    context,
                    REQUEST_CODE,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

            Notification notification = new NotificationCompat.Builder(context, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_crown)
                    .setContentTitle(copy.title)
                    .setContentText(copy.body)
                    .setStyle(new NotificationCompat.BigTextStyle().bigText(copy.body))
                    .setContentIntent(pendingIntent)
                    .setAutoCancel(true)
                    .setShowWhen(false)
                    .setCategory(NotificationCompat.CATEGORY_REMINDER)
                    .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                    .setDefaults(NotificationCompat.DEFAULT_SOUND)
                    // FadCam can be a discreet app; never expose the reminder on a lock screen.
                    .setVisibility(NotificationCompat.VISIBILITY_SECRET)
                    .build();

            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public static void cancel(Context context) {
        try {
            NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID);
        } catch (Exception ignored) {
        }
    }

    private static void ensureChannel(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager manager =
                (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) return;
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return;
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.pro_reminder_notification_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT);
        channel.setDescription(
                context.getString(R.string.pro_reminder_notification_channel_description));
        channel.setShowBadge(true);
        manager.createNotificationChannel(channel);
    }
}
