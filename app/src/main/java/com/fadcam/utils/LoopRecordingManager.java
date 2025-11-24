package com.fadcam.utils;

import android.content.Context;
import android.net.Uri;
import androidx.documentfile.provider.DocumentFile;

import com.fadcam.Log;
import com.fadcam.SharedPreferencesManager;
import com.fadcam.Utils;
import com.fadcam.Constants; // Added import

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public class LoopRecordingManager {
    private Context context;
    private long maxBytes;
    private SharedPreferencesManager prefs;

    public LoopRecordingManager(Context context, long maxBytes) {
        this.context = context;
        this.maxBytes = maxBytes;
        this.prefs = SharedPreferencesManager.getInstance(context);
    }

    public void enforceStorageLimit() {
        if (maxBytes <= 0) {
            Log.d("LoopManager", "Loop recording limit not set or invalid (<= 0), skipping enforcement.");
            return;
        }
        List<DocumentFile> videoFiles = getVideoFiles();
        if (videoFiles.isEmpty()) {
            Log.d("LoopManager", "No video files found to enforce storage limit.");
            return;
        }

        long totalSize = calculateTotalSize(videoFiles);
        Log.d("LoopManager", "Current total size: " + totalSize + " bytes, Max bytes: " + maxBytes);

        if (totalSize <= maxBytes) {
            Log.d("LoopManager", "Total size is within limit, no deletion needed.");
            return;
        }

        // Sort by modification date (oldest first)
        Collections.sort(videoFiles, Comparator.comparingLong(DocumentFile::lastModified));

        long freedBytes = 0;
        long initialTotalSize = totalSize;

        for (DocumentFile file : videoFiles) {
            if (totalSize <= maxBytes) {
                break; // We are now within the limit
            }
            long fileSize = file.length();
            String fileName = file.getName(); // Get name before deleting
            if (file.delete()) {
                freedBytes += fileSize;
                totalSize -= fileSize;
                Log.i("LoopManager", "Deleted old recording: " + fileName + " (freed " + fileSize + " bytes)");
            } else {
                Log.w("LoopManager", "Failed to delete old recording: " + fileName);
            }
        }

        if (freedBytes > 0) {
            String message = context.getString(R.string.loop_recording_deleted_message, (freedBytes / (1024 * 1024)));
            Log.i("LoopManager", message);
            Utils.showQuickToast(context, message);
        } else if (initialTotalSize > maxBytes) {
            // This case means we tried to delete but couldn't free up enough space (e.g. delete failed)
            Log.w("LoopManager", "Could not free enough space. Current usage: " + totalSize + ", Limit: " + maxBytes);
        }
    }

    private List<DocumentFile> getVideoFiles() {
        List<DocumentFile> files = new ArrayList<>();
        DocumentFile rootDir = getRecordingsDir();
        if (rootDir == null) {
            Log.w("LoopManager", "Root recording directory is null, cannot list files.");
            return files;
        }

        if (!rootDir.exists() || !rootDir.isDirectory()) {
            Log.w("LoopManager", "Root recording directory does not exist or is not a directory: " + rootDir.getUri());
            return files;
        }

        DocumentFile[] dirFiles = rootDir.listFiles();
        if (dirFiles == null) {
            Log.w("LoopManager", "listFiles() returned null for directory: " + rootDir.getUri());
            return files;
        }

        for (DocumentFile file : dirFiles) {
            // Check if it's a file, name ends with .mp4 and has a positive length
            if (file.isFile() && file.getName() != null && file.getName().toLowerCase().endsWith(".mp4") && file.length() > 0) {
                files.add(file);
            }
        }
        Log.d("LoopManager", "Found " + files.size() + " video files in " + rootDir.getUri());
        return files;
    }

    private DocumentFile getRecordingsDir() {
        String mode = prefs.getStorageMode();
        if (SharedPreferencesManager.STORAGE_MODE_INTERNAL.equals(mode)) {
            File internalAppSpecificDir = context.getExternalFilesDir(null);
            if (internalAppSpecificDir == null) {
                Log.w("LoopManager", "External files dir is null for internal storage mode.");
                return null;
            }
            // Recordings are in a sub-directory defined by Constants.RECORDING_DIRECTORY
            File recordingDirFile = new File(internalAppSpecificDir, Constants.RECORDING_DIRECTORY);
            
            if (!recordingDirFile.exists()) {
                Log.i("LoopManager", "Internal recording directory does not exist: " + recordingDirFile.getAbsolutePath());
                // If the directory doesn't exist, there are no files to manage.
                return null; 
            }
            if (!recordingDirFile.isDirectory()) {
                Log.w("LoopManager", "Internal recording path is not a directory: " + recordingDirFile.getAbsolutePath());
                return null;
            }
            return DocumentFile.fromFile(recordingDirFile);
        } else if (SharedPreferencesManager.STORAGE_MODE_CUSTOM.equals(mode)) {
            String uriStr = prefs.getCustomStorageUri();
            if (uriStr != null) {
                try {
                    Uri treeUri = Uri.parse(uriStr);
                    DocumentFile rootDir = DocumentFile.fromTreeUri(context, treeUri);
                    // In SAF mode, files are created directly under the picked directory.
                    // Constants.RECORDING_DIRECTORY is part of the filename prefix, not a sub-directory here.
                    if (rootDir != null && rootDir.canRead()) {
                        return rootDir;
                    } else {
                        Log.w("LoopManager", "Cannot read from custom storage URI: " + uriStr);
                        return null;
                    }
                } catch (Exception e) {
                    Log.e("LoopManager", "Error parsing custom storage URI: " + uriStr, e);
                    return null;
                }
            }
        }
        Log.w("LoopManager", "No valid storage dir found for mode: " + mode + ". Custom URI was: " + prefs.getCustomStorageUri());
        return null;
    }

    private long calculateTotalSize(List<DocumentFile> files) {
        long size = 0;
        for (DocumentFile file : files) {
            size += file.length();
        }
        return size;
    }

    public long getCurrentUsageBytes() {
        return calculateTotalSize(getVideoFiles());
    }
}