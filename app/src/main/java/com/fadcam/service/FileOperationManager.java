package com.fadcam.service;

import android.content.Context;
import android.net.Uri;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import com.fadcam.Constants;
import com.fadcam.Utils;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Manages file operation queue and execution
 */
public class FileOperationManager {
    
    private static final String TAG = "FileOperationManager";
    private static final int BUFFER_SIZE = 8192;
    
    public interface OperationListener {
        void onTaskStarted(FileOperationTask task);
        void onTaskProgress(FileOperationTask task);
        void onTaskCompleted(FileOperationTask task);
        void onTaskFailed(FileOperationTask task, String error);
        void onAllTasksCompleted();
    }
    
    private final Context context;
    private final ExecutorService executorService;
    private final ConcurrentLinkedQueue<FileOperationTask> taskQueue;
    private final AtomicBoolean isProcessing;
    private final Handler mainHandler;
    private OperationListener listener;
    
    public FileOperationManager(Context context) {
        this.context = context;
        this.executorService = Executors.newSingleThreadExecutor();
        this.taskQueue = new ConcurrentLinkedQueue<>();
        this.isProcessing = new AtomicBoolean(false);
        this.mainHandler = new Handler(Looper.getMainLooper());
    }
    
    public void setOperationListener(OperationListener listener) {
        this.listener = listener;
    }
    
    public void enqueueTask(FileOperationTask task) {
        taskQueue.offer(task);
        Log.d(TAG, "Task enqueued: " + task);
        processNextTask();
    }
    
    public int getQueueSize() {
        return taskQueue.size();
    }
    
    public void shutdown() {
        executorService.shutdown();
    }
    
    private void processNextTask() {
        if (isProcessing.get()) {
            return; // Already processing
        }
        
        FileOperationTask task = taskQueue.poll();
        if (task == null) {
            notifyListener(listener -> listener.onAllTasksCompleted());
            return;
        }
        
        isProcessing.set(true);
        executorService.submit(() -> executeTask(task));
    }
    
    private void executeTask(FileOperationTask task) {
        Log.d(TAG, "Starting task: " + task);
        task.status = FileOperationTask.TaskStatus.IN_PROGRESS;
        notifyListener(listener -> listener.onTaskStarted(task));
        
        try {
            switch (task.type) {
                case COPY_TO_GALLERY:
                    executeCopyToGallery(task, false);
                    break;
                case MOVE_TO_GALLERY:
                    executeCopyToGallery(task, true);
                    break;
                case DELETE_FILE:
                    executeDelete(task);
                    break;
                case RESTORE_FILE:
                    executeRestore(task);
                    break;
            }
            
            task.status = FileOperationTask.TaskStatus.COMPLETED;
            task.endTime = System.currentTimeMillis();
            Log.d(TAG, "Task completed: " + task);
            notifyListener(listener -> listener.onTaskCompleted(task));
            
        } catch (Exception e) {
            Log.e(TAG, "Task failed: " + task, e);
            task.status = FileOperationTask.TaskStatus.FAILED;
            task.errorMessage = e.getMessage();
            task.endTime = System.currentTimeMillis();
            notifyListener(listener -> listener.onTaskFailed(task, e.getMessage()));
        } finally {
            isProcessing.set(false);
            processNextTask(); // Process next task in queue
        }
    }
    
    private void executeCopyToGallery(FileOperationTask task, boolean moveFile) throws Exception {
        // Create destination directory
        File downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        File fadCamDir = new File(downloadsDir, Constants.RECORDING_DIRECTORY);
        if (!fadCamDir.exists() && !fadCamDir.mkdirs()) {
            throw new Exception("Cannot create directory: " + fadCamDir.getAbsolutePath());
        }
        
        // Handle filename conflicts
        File destFile = new File(fadCamDir, task.fileName);
        int counter = 0;
        while (destFile.exists()) {
            counter++;
            String nameWithoutExt = task.fileName;
            String extension = "";
            int dotIndex = task.fileName.lastIndexOf('.');
            if (dotIndex > 0 && dotIndex < task.fileName.length() - 1) {
                nameWithoutExt = task.fileName.substring(0, dotIndex);
                extension = task.fileName.substring(dotIndex);
            }
            destFile = new File(fadCamDir, nameWithoutExt + " (" + counter + ")" + extension);
        }
        
        // Get file size for progress tracking
        try {
            task.totalBytes = getFileSize(task.sourceUri);
        } catch (Exception e) {
            Log.w(TAG, "Could not get file size for progress tracking", e);
        }
        
        // Copy file with progress tracking
        try (InputStream in = context.getContentResolver().openInputStream(task.sourceUri);
             OutputStream out = new FileOutputStream(destFile)) {
            
            if (in == null) {
                throw new Exception("Failed to open input stream for " + task.sourceUri);
            }
            
            byte[] buffer = new byte[BUFFER_SIZE];
            int bytesRead;
            while ((bytesRead = in.read(buffer)) != -1) {
                out.write(buffer, 0, bytesRead);
                task.progress += bytesRead;
                
                // Update progress periodically (every 100KB)
                if (task.progress % (100 * 1024) == 0 || task.progress >= task.totalBytes) {
                    notifyListener(listener -> listener.onTaskProgress(task));
                }
            }
            out.flush();
        }
        
        // Scan file for media store
        Utils.scanFileWithMediaStore(context, destFile.getAbsolutePath());
        Log.i(TAG, "File copied to: " + destFile.getAbsolutePath());
        
        // Handle move operation (delete original)
        if (moveFile) {
            boolean deleted = false;
            if ("file".equals(task.sourceUri.getScheme())) {
                File originalFile = new File(task.sourceUri.getPath());
                if (originalFile.exists()) {
                    deleted = originalFile.delete();
                }
            } else {
                deleted = context.getContentResolver().delete(task.sourceUri, null, null) > 0;
            }
            
            if (!deleted) {
                Log.w(TAG, "Could not delete original file: " + task.sourceUri);
                throw new Exception("File copied but original could not be deleted");
            }
            
            Log.i(TAG, "Original file deleted: " + task.sourceUri);
        }
    }
    
    private void executeDelete(FileOperationTask task) throws Exception {
        boolean deleted = false;
        
        if ("file".equals(task.sourceUri.getScheme())) {
            File file = new File(task.sourceUri.getPath());
            if (file.exists()) {
                deleted = file.delete();
            }
        } else {
            deleted = context.getContentResolver().delete(task.sourceUri, null, null) > 0;
        }
        
        if (!deleted) {
            throw new Exception("Could not delete file: " + task.sourceUri);
        }
        
        Log.i(TAG, "File deleted: " + task.sourceUri);
    }
    
    private long getFileSize(Uri uri) throws Exception {
        try (InputStream in = context.getContentResolver().openInputStream(uri)) {
            if (in == null) return 0;
            return in.available(); // Approximate size
        }
    }
    
    /**
     * Execute restore operation (placeholder - actual implementation depends on specific requirements)
     */
    private void executeRestore(FileOperationTask task) throws Exception {
        Log.d(TAG, "executeRestore: Starting restore for " + task.fileName);
        
        // Since restore operations are currently handled by TrashFragment directly,
        // this method serves as a placeholder for service notification purposes.
        // The actual restore logic remains in TrashManager.restoreItemsFromTrash()
        
        // Simulate progress for notification
        task.progress = 50;
        task.totalBytes = 100;
        notifyListener(listener -> listener.onTaskProgress(task));
        
        // Small delay to show progress
        Thread.sleep(500);
        
        task.progress = 100;
        notifyListener(listener -> listener.onTaskProgress(task));
        
        Log.d(TAG, "executeRestore: Completed restore placeholder for " + task.fileName);
    }
    
    private void notifyListener(ListenerCallback callback) {
        if (listener != null) {
            mainHandler.post(() -> {
                try {
                    callback.call(listener);
                } catch (Exception e) {
                    Log.e(TAG, "Error in listener callback", e);
                }
            });
        }
    }
    
    private interface ListenerCallback {
        void call(OperationListener listener);
    }
}