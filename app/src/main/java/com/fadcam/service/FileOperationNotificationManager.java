package com.fadcam.service;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import com.fadcam.R;
import com.fadcam.MainActivity;

/**
 * Manages notifications for file operations
 */
public class FileOperationNotificationManager {
    
    private static final String CHANNEL_ID = "file_operations";
    private static final String CHANNEL_NAME = "File Operations";
    private static final int NOTIFICATION_ID = 1001;
    
    private final Context context;
    private final NotificationManagerCompat notificationManager;
    
    public FileOperationNotificationManager(Context context) {
        this.context = context;
        this.notificationManager = NotificationManagerCompat.from(context);
        createNotificationChannel();
    }
    
    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    CHANNEL_NAME,
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Shows progress of file operations like saving to gallery");
            channel.setShowBadge(false);
            
            NotificationManager manager = context.getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }
    
    public NotificationCompat.Builder createForegroundNotification(FileOperationTask task) {
        Intent intent = new Intent(context, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                context, 0, intent, 
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        
        return new NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_notification) // You may need to add this icon
                .setContentTitle("FadCam File Operations")
                .setContentText("Processing files...")
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setAutoCancel(false)
                .setPriority(NotificationCompat.PRIORITY_LOW);
    }
    
    public void updateProgress(FileOperationTask task, int queueSize) {
        String title = task.getOperationText();
        String content = task.displayName != null ? task.displayName : task.fileName;
        
        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_notification)
                .setContentTitle(title)
                .setContentText(content)
                .setOngoing(true)
                .setAutoCancel(false)
                .setPriority(NotificationCompat.PRIORITY_LOW);
                
        // Show progress if we have total bytes
        if (task.totalBytes > 0) {
            builder.setProgress((int) task.totalBytes, (int) task.progress, false);
            builder.setSubText(task.getProgressPercent() + "%");
        } else {
            builder.setProgress(0, 0, true); // Indeterminate progress
        }
        
        // Show queue info if multiple tasks
        if (queueSize > 1) {
            builder.setSubText("+" + (queueSize - 1) + " more in queue");
        }
        
        try {
            notificationManager.notify(NOTIFICATION_ID, builder.build());
        } catch (SecurityException e) {
            android.util.Log.w("FileOpNotification", "No notification permission", e);
        }
    }
    
    public void showCompleted(String message, boolean success) {
        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(success ? R.drawable.ic_check : R.drawable.ic_error)
                .setContentTitle("File Operation " + (success ? "Complete" : "Failed"))
                .setContentText(message)
                .setAutoCancel(true)
                .setOngoing(false)
                .setPriority(NotificationCompat.PRIORITY_LOW);
        
        try {
            notificationManager.notify(NOTIFICATION_ID + 1, builder.build());
        } catch (SecurityException e) {
            android.util.Log.w("FileOpNotification", "No notification permission", e);
        }
    }
    
    public void cancelNotification() {
        notificationManager.cancel(NOTIFICATION_ID);
    }
}