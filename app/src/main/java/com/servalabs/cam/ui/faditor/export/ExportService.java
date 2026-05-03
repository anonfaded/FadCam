package com.servalabs.cam.ui.faditor.export;

import com.servalabs.cam.Log;
import com.servalabs.cam.FLog;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.servalabs.cam.R;
import com.servalabs.cam.SharedPreferencesManager;
import com.servalabs.cam.ui.faditor.EditorMiniEditorActivity;
import com.servalabs.cam.ui.faditor.model.EditorMiniProject;

/**
 * Foreground service that runs video export in the background so it survives
 * Activity destruction (app minimised or closed).
 *
 * <p>The Activity starts this service, passes the project via a static bridge,
 * and binds to observe real-time progress. If the Activity is destroyed, the
 * service continues exporting and updates the system notification.</p>
 */
public class ExportService extends Service {

    private static final String TAG = "ExportService";
    private static final String CHANNEL_ID = "editor_mini_export_channel";
    private static final int NOTIFICATION_ID = 3001;

    /** Action to start an export. */
    public static final String ACTION_START_EXPORT = "com.servalabs.cam.EXPORT_START";
    /** Action to cancel an export. */
    public static final String ACTION_CANCEL_EXPORT = "com.servalabs.cam.EXPORT_CANCEL";
    
    // ── Broadcast actions for UI updates ──────────────────────────────
    public static final String ACTION_EXPORT_STARTED = "com.servalabs.cam.EXPORT_STARTED";
    public static final String ACTION_EXPORT_PROGRESS = "com.servalabs.cam.EXPORT_PROGRESS";
    public static final String ACTION_EXPORT_COMPLETED = "com.servalabs.cam.EXPORT_COMPLETED";
    public static final String ACTION_EXPORT_ERROR = "com.servalabs.cam.EXPORT_ERROR";
    public static final String ACTION_EXPORT_CANCELLED = "com.servalabs.cam.EXPORT_CANCELLED";
    
    public static final String EXTRA_OUTPUT_PATH = "output_path";
    public static final String EXTRA_PROGRESS = "progress";
    public static final String EXTRA_ERROR_MESSAGE = "error_message";

    // ── Static bridge for passing project data ───────────────────────
    @Nullable
    private static EditorMiniProject pendingProject;

    /**
     * Set the project to export. Must be called before starting the service.
     */
    public static void setPendingProject(@Nullable EditorMiniProject project) {
        pendingProject = project;
    }

    // ── Listener for Activity binding ────────────────────────────────

    /**
     * Callback interface for UI updates. Called on the main thread.
     */
    public interface ExportServiceListener {
        void onExportStarted(@NonNull String outputPath);
        void onExportProgress(float progress);
        void onExportCompleted(@NonNull String outputPath,
                               @NonNull androidx.media3.transformer.ExportResult result);
        void onExportError(@NonNull Exception error);
    }

    // ── Instance fields ──────────────────────────────────────────────

    private final IBinder binder = new ExportBinder();
    @Nullable
    private ExportServiceListener serviceListener;
    @Nullable
    private ExportManager exportManager;
    @Nullable
    private NotificationManager notificationManager;
    private boolean isExporting = false;
    private long exportStartTimeMs;

    // ── Binder ───────────────────────────────────────────────────────

    public class ExportBinder extends Binder {
        public ExportService getService() {
            return ExportService.this;
        }
    }

    public void setServiceListener(@Nullable ExportServiceListener listener) {
        this.serviceListener = listener;
    }

    public boolean isExporting() {
        return isExporting;
    }

    // ── Service lifecycle ────────────────────────────────────────────

    @Override
    public void onCreate() {
        super.onCreate();
        FLog.d(TAG, "Service created");
        notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            stopSelf();
            return START_NOT_STICKY;
        }

        String action = intent.getAction();

        if (ACTION_CANCEL_EXPORT.equals(action)) {
            cancelExport();
            return START_NOT_STICKY;
        }

        if (ACTION_START_EXPORT.equals(action)) {
            startExportInternal();
        }

        return START_NOT_STICKY;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (exportManager != null && exportManager.isExporting()) {
            exportManager.cancel();
        }
        FLog.d(TAG, "Service destroyed");
    }

    // ── Export execution ─────────────────────────────────────────────

    private void startExportInternal() {
        EditorMiniProject project = pendingProject;
        pendingProject = null; // consume

        if (project == null) {
            FLog.e(TAG, "No pending project — cannot export");
            stopSelf();
            return;
        }

        if (isExporting) {
            FLog.w(TAG, "Export already in progress");
            return;
        }

        isExporting = true;
        exportStartTimeMs = System.currentTimeMillis();

        // Show initial foreground notification
        startForeground(NOTIFICATION_ID, buildProgressNotification(0, true));

        // Create ExportManager and run
        SharedPreferencesManager prefsManager = SharedPreferencesManager.getInstance(this);
        exportManager = new ExportManager(this, prefsManager);
        exportManager.setExportListener(new ExportManager.ExportListener() {
            @Override
            public void onExportStarted(@NonNull String outputPath) {
                FLog.d(TAG, "Export started → " + outputPath);
                if (serviceListener != null) {
                    serviceListener.onExportStarted(outputPath);
                }
                // Broadcast to UI (EditorMiniMiniFragment listens via LocalBroadcastManager)
                Intent broadcast = new Intent(ACTION_EXPORT_STARTED);
                broadcast.putExtra(EXTRA_OUTPUT_PATH, outputPath);
                FLog.d(TAG, "Broadcasting ACTION_EXPORT_STARTED");
                androidx.localbroadcastmanager.content.LocalBroadcastManager.getInstance(ExportService.this)
                        .sendBroadcast(broadcast);
                FLog.d(TAG, "ACTION_EXPORT_STARTED broadcast sent");
            }

            @Override
            public void onExportProgress(float progress) {
                int percent = (int) (progress * 100);
                updateNotification(percent);
                if (serviceListener != null) {
                    serviceListener.onExportProgress(progress);
                }
                // Broadcast to UI
                Intent broadcast = new Intent(ACTION_EXPORT_PROGRESS);
                broadcast.putExtra(EXTRA_PROGRESS, progress);
                FLog.d(TAG, "Broadcasting ACTION_EXPORT_PROGRESS: " + percent + "%");
                androidx.localbroadcastmanager.content.LocalBroadcastManager.getInstance(ExportService.this)
                        .sendBroadcast(broadcast);
            }

            @Override
            public void onExportCompleted(@NonNull String outputPath,
                                          @NonNull androidx.media3.transformer.ExportResult result) {
                FLog.d(TAG, "Export completed: " + outputPath);
                isExporting = false;
                showCompletionNotification();
                if (serviceListener != null) {
                    serviceListener.onExportCompleted(outputPath, result);
                }
                // Broadcast to UI
                Intent broadcast = new Intent(ACTION_EXPORT_COMPLETED);
                broadcast.putExtra(EXTRA_OUTPUT_PATH, outputPath);
                FLog.d(TAG, "Broadcasting ACTION_EXPORT_COMPLETED");
                androidx.localbroadcastmanager.content.LocalBroadcastManager.getInstance(ExportService.this)
                        .sendBroadcast(broadcast);
                FLog.d(TAG, "ACTION_EXPORT_COMPLETED broadcast sent");
                
                stopForeground(STOP_FOREGROUND_DETACH);
                stopSelf();
            }

            @Override
            public void onExportError(@NonNull Exception error) {
                FLog.e(TAG, "Export failed", error);
                isExporting = false;
                showErrorNotification(error.getMessage());
                if (serviceListener != null) {
                    serviceListener.onExportError(error);
                }
                // Broadcast to UI
                Intent broadcast = new Intent(ACTION_EXPORT_ERROR);
                broadcast.putExtra(EXTRA_ERROR_MESSAGE, error.getMessage());
                FLog.d(TAG, "Broadcasting ACTION_EXPORT_ERROR: " + error.getMessage());
                androidx.localbroadcastmanager.content.LocalBroadcastManager.getInstance(ExportService.this)
                        .sendBroadcast(broadcast);
                FLog.d(TAG, "ACTION_EXPORT_ERROR broadcast sent");
                
                stopForeground(STOP_FOREGROUND_DETACH);
                stopSelf();
            }
        });

        exportManager.export(project);
    }

    /**
     * Cancel the running export.
     */
    public void cancelExport() {
        if (exportManager != null && exportManager.isExporting()) {
            exportManager.cancel();
            isExporting = false;
            FLog.d(TAG, "Export cancelled via service");
            
            // Broadcast cancellation to UI
            Intent broadcast = new Intent(ACTION_EXPORT_CANCELLED);
            FLog.d(TAG, "Broadcasting ACTION_EXPORT_CANCELLED");
            androidx.localbroadcastmanager.content.LocalBroadcastManager.getInstance(this)
                    .sendBroadcast(broadcast);
            FLog.d(TAG, "ACTION_EXPORT_CANCELLED broadcast sent");
        }
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    // ── Notification helpers ─────────────────────────────────────────

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.editor_mini_export_notif_channel),
                    NotificationManager.IMPORTANCE_LOW);
            channel.setDescription(getString(R.string.editor_mini_export_notif_channel_desc));
            channel.setShowBadge(false);
            if (notificationManager != null) {
                notificationManager.createNotificationChannel(channel);
            }
        }
    }

    @NonNull
    private Notification buildProgressNotification(int percent, boolean indeterminate) {
        // Cancel action
        Intent cancelIntent = new Intent(this, ExportService.class);
        cancelIntent.setAction(ACTION_CANCEL_EXPORT);
        PendingIntent cancelPi = PendingIntent.getService(this, 0, cancelIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        // Tap to open editor
        Intent openIntent = new Intent(this, EditorMiniEditorActivity.class);
        openIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent openPi = PendingIntent.getActivity(this, 0, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        String title = getString(R.string.editor_mini_export_notif_title);
        String text = indeterminate
                ? getString(R.string.editor_mini_exporting)
                : getString(R.string.editor_mini_exporting_percent, percent);

        // ETA calculation
        if (!indeterminate && percent > 5) {
            long elapsed = System.currentTimeMillis() - exportStartTimeMs;
            float progress = percent / 100f;
            long totalEstimated = (long) (elapsed / progress);
            long remainingMs = totalEstimated - elapsed;
            text += " • " + formatEta(remainingMs);
        }

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_export_video)
                .setContentTitle(title)
                .setContentText(text)
                .setProgress(100, percent, indeterminate)
                .setOngoing(true)
                .setSilent(true)
                .setContentIntent(openPi)
                .addAction(0, getString(R.string.editor_mini_cancel), cancelPi)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .build();
    }

    private void updateNotification(int percent) {
        Notification notification = buildProgressNotification(percent, false);
        if (notificationManager != null) {
            notificationManager.notify(NOTIFICATION_ID, notification);
        }
    }

    private void showCompletionNotification() {
        // Tap to open editor
        Intent openIntent = new Intent(this, EditorMiniEditorActivity.class);
        openIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent openPi = PendingIntent.getActivity(this, 0, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_export_video)
                .setContentTitle(getString(R.string.editor_mini_export_complete_title))
                .setContentText(getString(R.string.editor_mini_export_complete_summary))
                .setOngoing(false)
                .setAutoCancel(true)
                .setContentIntent(openPi)
                .build();

        if (notificationManager != null) {
            notificationManager.notify(NOTIFICATION_ID, notification);
        }
    }

    private void showErrorNotification(@Nullable String errorMsg) {
        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_export_video)
                .setContentTitle(getString(R.string.editor_mini_export_notif_error_title))
                .setContentText(errorMsg != null ? errorMsg
                        : getString(R.string.editor_mini_export_error, "Unknown error"))
                .setOngoing(false)
                .setAutoCancel(true)
                .build();

        if (notificationManager != null) {
            notificationManager.notify(NOTIFICATION_ID, notification);
        }
    }

    @NonNull
    private String formatEta(long remainingMs) {
        long seconds = remainingMs / 1000;
        if (seconds < 60) return seconds + "s";
        long minutes = seconds / 60;
        seconds = seconds % 60;
        if (minutes < 60) return minutes + "m " + seconds + "s";
        long hours = minutes / 60;
        minutes = minutes % 60;
        return hours + "h " + minutes + "m";
    }
}
