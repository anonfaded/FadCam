package com.fadcam.service;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Binder;
import android.os.IBinder;
import android.util.Log;
import androidx.annotation.Nullable;
import com.fadcam.R;

/**
 * Foreground service for handling file operations in the background
 */
public class FileOperationService extends Service implements FileOperationManager.OperationListener {
    
    private static final String TAG = "FileOperationService";
    private static final int FOREGROUND_ID = 1001;
    
    // Intent extras
    public static final String EXTRA_OPERATION_TYPE = "operation_type";
    public static final String EXTRA_SOURCE_URI = "source_uri";
    public static final String EXTRA_FILE_NAME = "file_name";
    public static final String EXTRA_DISPLAY_NAME = "display_name";
    
    // Operation types
    public static final String OP_COPY_TO_GALLERY = "copy_to_gallery";
    public static final String OP_MOVE_TO_GALLERY = "move_to_gallery";
    public static final String OP_DELETE_FILE = "delete_file";
    public static final String OP_RESTORE_FILE = "restore_file";
    
    public interface ServiceCallback {
        void onOperationStarted(FileOperationTask task);
        void onOperationProgress(FileOperationTask task);
        void onOperationCompleted(FileOperationTask task);
        void onOperationFailed(FileOperationTask task, String error);
    }
    
    private FileOperationManager operationManager;
    private FileOperationNotificationManager notificationManager;
    private ServiceCallback callback;
    private final IBinder binder = new FileOperationBinder();
    
    public class FileOperationBinder extends Binder {
        public FileOperationService getService() {
            return FileOperationService.this;
        }
    }
    
    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "Service created");
        
        operationManager = new FileOperationManager(this);
        operationManager.setOperationListener(this);
        
        notificationManager = new FileOperationNotificationManager(this);
    }
    
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "Service started with intent: " + intent);
        
        if (intent != null) {
            handleIntent(intent);
        }
        
        // Return START_STICKY so the service restarts if killed
        return START_STICKY;
    }
    
    private void handleIntent(Intent intent) {
        String operationType = intent.getStringExtra(EXTRA_OPERATION_TYPE);
        String sourceUriString = intent.getStringExtra(EXTRA_SOURCE_URI);
        String fileName = intent.getStringExtra(EXTRA_FILE_NAME);
        String displayName = intent.getStringExtra(EXTRA_DISPLAY_NAME);
        
        if (operationType == null || sourceUriString == null || fileName == null) {
            Log.e(TAG, "Missing required intent extras");
            return;
        }
        
        Uri sourceUri = Uri.parse(sourceUriString);
        FileOperationTask.OperationType type;
        
        switch (operationType) {
            case OP_COPY_TO_GALLERY:
                type = FileOperationTask.OperationType.COPY_TO_GALLERY;
                break;
            case OP_MOVE_TO_GALLERY:
                type = FileOperationTask.OperationType.MOVE_TO_GALLERY;
                break;
            case OP_DELETE_FILE:
                type = FileOperationTask.OperationType.DELETE_FILE;
                break;
            case OP_RESTORE_FILE:
                type = FileOperationTask.OperationType.RESTORE_FILE;
                break;
            default:
                Log.e(TAG, "Unknown operation type: " + operationType);
                return;
        }
        
        FileOperationTask task = new FileOperationTask(type, sourceUri, fileName, displayName);
        operationManager.enqueueTask(task);
    }
    
    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }
    
    @Override
    public void onDestroy() {
        Log.d(TAG, "Service destroyed");
        if (operationManager != null) {
            operationManager.shutdown();
        }
        if (notificationManager != null) {
            notificationManager.cancelNotification();
        }
        super.onDestroy();
    }
    
    public void setCallback(ServiceCallback callback) {
        this.callback = callback;
    }
    
    // FileOperationManager.OperationListener implementation
    
    @Override
    public void onTaskStarted(FileOperationTask task) {
        Log.d(TAG, "Task started: " + task);
        
        // Start foreground service with notification
        startForeground(FOREGROUND_ID, notificationManager.createForegroundNotification(task).build());
        
        if (callback != null) {
            callback.onOperationStarted(task);
        }
    }
    
    @Override
    public void onTaskProgress(FileOperationTask task) {
        notificationManager.updateProgress(task, operationManager.getQueueSize());
        
        if (callback != null) {
            callback.onOperationProgress(task);
        }
    }
    
    @Override
    public void onTaskCompleted(FileOperationTask task) {
        Log.d(TAG, "Task completed: " + task);
        
        String message = getCompletionMessage(task);
        notificationManager.showCompleted(message, true);
        
        if (callback != null) {
            callback.onOperationCompleted(task);
        }
    }
    
    @Override
    public void onTaskFailed(FileOperationTask task, String error) {
        Log.e(TAG, "Task failed: " + task + ", error: " + error);
        
        String message = "Failed to " + task.getOperationText().toLowerCase() + ": " + 
                        (task.displayName != null ? task.displayName : task.fileName);
        notificationManager.showCompleted(message, false);
        
        if (callback != null) {
            callback.onOperationFailed(task, error);
        }
    }
    
    @Override
    public void onAllTasksCompleted() {
        Log.d(TAG, "All tasks completed");
        
        // Stop foreground service when no more tasks
        stopForeground(false);
        
        // Stop the service itself if no more work
        if (operationManager.getQueueSize() == 0) {
            stopSelf();
        }
    }
    
    private String getCompletionMessage(FileOperationTask task) {
        String fileName = task.displayName != null ? task.displayName : task.fileName;
        switch (task.type) {
            case COPY_TO_GALLERY:
                return getString(R.string.file_operation_copied_to_gallery, fileName);
            case MOVE_TO_GALLERY:
                return getString(R.string.file_operation_moved_to_gallery, fileName);
            case DELETE_FILE:
                return getString(R.string.file_operation_deleted, fileName);
            case RESTORE_FILE:
                return getString(R.string.file_operation_restored, fileName);
            default:
                return getString(R.string.file_operation_processed, fileName);
        }
    }
    
    // Static helper methods for starting operations
    
    public static void startCopyToGallery(Context context, Uri sourceUri, String fileName, String displayName) {
        Intent intent = new Intent(context, FileOperationService.class);
        intent.putExtra(EXTRA_OPERATION_TYPE, OP_COPY_TO_GALLERY);
        intent.putExtra(EXTRA_SOURCE_URI, sourceUri.toString());
        intent.putExtra(EXTRA_FILE_NAME, fileName);
        intent.putExtra(EXTRA_DISPLAY_NAME, displayName);
        context.startForegroundService(intent);
    }
    
    public static void startMoveToGallery(Context context, Uri sourceUri, String fileName, String displayName) {
        Intent intent = new Intent(context, FileOperationService.class);
        intent.putExtra(EXTRA_OPERATION_TYPE, OP_MOVE_TO_GALLERY);
        intent.putExtra(EXTRA_SOURCE_URI, sourceUri.toString());
        intent.putExtra(EXTRA_FILE_NAME, fileName);
        intent.putExtra(EXTRA_DISPLAY_NAME, displayName);
        context.startForegroundService(intent);
    }
    
    public static void startDeleteFile(Context context, Uri sourceUri, String fileName, String displayName) {
        Intent intent = new Intent(context, FileOperationService.class);
        intent.putExtra(EXTRA_OPERATION_TYPE, OP_DELETE_FILE);
        intent.putExtra(EXTRA_SOURCE_URI, sourceUri.toString());
        intent.putExtra(EXTRA_FILE_NAME, fileName);
        intent.putExtra(EXTRA_DISPLAY_NAME, displayName);
        context.startForegroundService(intent);
    }
    
    public static void startRestoreFile(Context context, Uri sourceUri, String fileName, String displayName) {
        Intent intent = new Intent(context, FileOperationService.class);
        intent.putExtra(EXTRA_OPERATION_TYPE, OP_RESTORE_FILE);
        intent.putExtra(EXTRA_SOURCE_URI, sourceUri.toString());
        intent.putExtra(EXTRA_FILE_NAME, fileName);
        intent.putExtra(EXTRA_DISPLAY_NAME, displayName);
        context.startForegroundService(intent);
    }
}