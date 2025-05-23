package com.fadcam.utils;

import android.content.Context;
import android.net.Uri;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.util.Log;

import com.fadcam.Constants;
import com.fadcam.model.TrashItem;
import com.fadcam.Utils;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class TrashManager {

    private static final String TAG = "TrashManager";

    /**
     * Gets the File object for the trash directory within the app's external files directory.
     * Creates the directory if it doesn't exist.
     * Path: Android/data/com.fadcam/files/Trash/
     *
     * @param context The application context.
     * @return The File object for the trash directory, or null if it cannot be created/accessed.
     */
    public static File getTrashDirectory(Context context) {
        if (context == null) {
            Log.e(TAG, "Context is null, cannot get trash directory.");
            return null;
        }
        File externalFilesDir = context.getExternalFilesDir(null);
        if (externalFilesDir == null) {
            Log.e(TAG, "External files directory is null, cannot get trash directory.");
            return null;
        }
        File trashDir = new File(externalFilesDir, Constants.TRASH_DIRECTORY_NAME);
        if (!trashDir.exists()) {
            if (trashDir.mkdirs()) {
                Log.i(TAG, "Trash directory created at: " + trashDir.getAbsolutePath());
            } else {
                Log.e(TAG, "Failed to create trash directory at: " + trashDir.getAbsolutePath());
                return null; // Failed to create
            }
        }
        return trashDir;
    }

    /**
     * Loads the list of TrashItem objects from the metadata JSON file.
     *
     * @param context The application context.
     * @return A List of TrashItem objects. Returns an empty list if the file doesn't exist or an error occurs.
     */
    public static List<TrashItem> loadTrashMetadata(Context context) {
        if (context == null) {
            Log.e(TAG, "Context is null, cannot load trash metadata.");
            return new ArrayList<>();
        }
        File metadataFile = new File(context.getFilesDir(), Constants.TRASH_METADATA_FILENAME);
        if (!metadataFile.exists()) {
            Log.i(TAG, "Trash metadata file does not exist. Returning empty list.");
            return new ArrayList<>();
        }

        Gson gson = new Gson();
        Type listType = new TypeToken<ArrayList<TrashItem>>() {}.getType();

        try (FileInputStream fis = new FileInputStream(metadataFile);
             InputStreamReader isr = new InputStreamReader(fis, StandardCharsets.UTF_8);
             BufferedReader reader = new BufferedReader(isr)) {
            List<TrashItem> items = gson.fromJson(reader, listType);
            if (items == null) {
                Log.w(TAG, "Parsed trash metadata is null. Returning empty list.");
                return new ArrayList<>();
            }
            Log.i(TAG, "Loaded " + items.size() + " items from trash metadata.");
            return items;
        } catch (IOException e) {
            Log.e(TAG, "IOException loading trash metadata from " + metadataFile.getAbsolutePath(), e);
        } catch (com.google.gson.JsonSyntaxException e) {
            Log.e(TAG, "JsonSyntaxException parsing trash metadata. File might be corrupt: " + metadataFile.getAbsolutePath(), e);
            // Optionally, attempt to delete the corrupt file here so it can be recreated cleanly next time.
            // metadataFile.delete();
        }
        return new ArrayList<>(); // Return empty list on error
    }

    /**
     * Saves the list of TrashItem objects to the metadata JSON file.
     *
     * @param context The application context.
     * @param items   The list of TrashItem objects to save.
     * @return true if saving was successful, false otherwise.
     */
    public static boolean saveTrashMetadata(Context context, List<TrashItem> items) {
        if (context == null) {
            Log.e(TAG, "Context is null, cannot save trash metadata.");
            return false;
        }
        if (items == null) {
            Log.w(TAG, "Trash items list is null. Saving an empty list instead.");
            items = new ArrayList<>(); // Avoid NullPointerException by saving an empty list
        }

        File metadataFile = new File(context.getFilesDir(), Constants.TRASH_METADATA_FILENAME);
        Gson gson = new Gson();

        try (FileOutputStream fos = new FileOutputStream(metadataFile); // Overwrites the file
             OutputStreamWriter osw = new OutputStreamWriter(fos, StandardCharsets.UTF_8)) {
            gson.toJson(items, osw);
            Log.i(TAG, "Successfully saved " + items.size() + " items to trash metadata at " + metadataFile.getAbsolutePath());
            return true;
        } catch (IOException e) {
            Log.e(TAG, "IOException saving trash metadata to " + metadataFile.getAbsolutePath(), e);
            return false;
        }
    }

    /**
     * Moves a video file to the trash directory and updates the trash metadata.
     *
     * @param context             The application context.
     * @param videoUri            The URI of the video to be trashed.
     * @param originalDisplayName The original display name of the video.
     * @param isSafSource         True if the videoUri is from SAF, false if it's a direct file path.
     * @return True if the video was successfully moved to trash, false otherwise.
     */
    public static boolean moveToTrash(Context context, Uri videoUri, String originalDisplayName, boolean isSafSource) {
        if (context == null || videoUri == null || originalDisplayName == null) {
            Log.e(TAG, "moveToTrash: Invalid arguments (context, URI, or displayName is null).");
            return false;
        }

        File trashDir = getTrashDirectory(context);
        if (trashDir == null) {
            Log.e(TAG, "moveToTrash: Failed to get or create trash directory.");
            return false;
        }

        String targetTrashFileName = getUniqueTrashFileName(trashDir, originalDisplayName);
        File targetTrashFile = new File(trashDir, targetTrashFileName);

        boolean success = false;
        Log.i(TAG, "Attempting to move to trash: '" + originalDisplayName + "' -> '" + targetTrashFile.getAbsolutePath() + "'");

        if (isSafSource) {
            // Handle SAF URI (content://)
            try (InputStream in = context.getContentResolver().openInputStream(videoUri);
                 OutputStream out = new FileOutputStream(targetTrashFile)) {
                if (in == null) {
                    Log.e(TAG, "moveToTrash: Failed to open InputStream for SAF URI: " + videoUri);
                    return false;
                }
                byte[] buf = new byte[8192];
                int len;
                while ((len = in.read(buf)) > 0) {
                    out.write(buf, 0, len);
                }
                success = true;
                Log.d(TAG, "moveToTrash: Successfully COPIED SAF URI " + videoUri + " to " + targetTrashFile.getAbsolutePath());
                // Now delete the original SAF document
                if (DocumentsContract.deleteDocument(context.getContentResolver(), videoUri)) {
                    Log.d(TAG, "moveToTrash: Successfully DELETED original SAF document: " + videoUri);
                } else {
                    Log.w(TAG, "moveToTrash: Failed to delete original SAF document: " + videoUri + " (but copy to trash succeeded).");
                    // Proceed with trashing metadata, as copy was successful.
                }
            } catch (Exception e) {
                Log.e(TAG, "moveToTrash: Error copying/deleting SAF URI " + videoUri, e);
                if (targetTrashFile.exists()) targetTrashFile.delete(); // Clean up partial copy
                success = false;
            }
        } else {
            // Handle direct file URI (file://)
            File sourceFile = new File(videoUri.getPath());
            if (sourceFile.exists()) {
                if (sourceFile.renameTo(targetTrashFile)) {
                    success = true;
                    Log.d(TAG, "moveToTrash: Successfully MOVED internal file " + sourceFile.getAbsolutePath() + " to " + targetTrashFile.getAbsolutePath());
                } else {
                    Log.e(TAG, "moveToTrash: Failed to move internal file " + sourceFile.getAbsolutePath());
                    // As a fallback, try copy-delete for internal files if move fails (e.g., across different filesystems on some rooted devices)
                    try (InputStream in = new FileInputStream(sourceFile);
                         OutputStream out = new FileOutputStream(targetTrashFile)) {
                        byte[] buf = new byte[8192];
                        int len;
                        while ((len = in.read(buf)) > 0) {
                            out.write(buf, 0, len);
                        }
                        Log.d(TAG, "moveToTrash: Fallback - Successfully COPIED internal file " + sourceFile.getAbsolutePath());
                        if (sourceFile.delete()) {
                            Log.d(TAG, "moveToTrash: Fallback - Successfully DELETED original internal file.");
                            success = true;
                        } else {
                            Log.w(TAG, "moveToTrash: Fallback - Failed to delete original internal file after copy. Manual cleanup of trash item might be needed if metadata is added.");
                            if(targetTrashFile.exists()) targetTrashFile.delete(); // remove the copy from trash
                            success = false;
                        }
                    } catch (IOException ioe) {
                        Log.e(TAG, "moveToTrash: Fallback - IOException during copy for internal file " + sourceFile.getAbsolutePath(), ioe);
                        if (targetTrashFile.exists()) targetTrashFile.delete(); // Clean up partial copy
                        success = false;
                    }
                }
            } else {
                Log.e(TAG, "moveToTrash: Source file does not exist for URI: " + videoUri);
                success = false;
            }
        }

        if (success) {
            List<TrashItem> trashItems = loadTrashMetadata(context);
            trashItems.add(new TrashItem(videoUri.toString(), originalDisplayName, targetTrashFileName, System.currentTimeMillis(), isSafSource));
            if (saveTrashMetadata(context, trashItems)) {
                Log.i(TAG, "moveToTrash: Successfully moved and updated metadata for: " + originalDisplayName);
                return true;
            } else {
                Log.e(TAG, "moveToTrash: File moved/copied, but FAILED to save trash metadata for: " + originalDisplayName);
                // Critical error: file is in trash, but not tracked. Attempt to revert by deleting from trash.
                if (targetTrashFile.exists() && targetTrashFile.delete()) {
                    Log.w(TAG, "moveToTrash: Reverted by deleting untracked file from trash: " + targetTrashFileName);
                } else {
                    Log.e(TAG, "moveToTrash: CRITICAL - Failed to revert untracked file in trash: " + targetTrashFileName + ". Manual cleanup needed.");
                }
                return false;
            }
        } else {
            Log.e(TAG, "moveToTrash: File operation failed for: " + originalDisplayName);
            return false;
        }
    }

    /**
     * Generates a unique filename for the trash directory, appending a counter if needed.
     * e.g., video.mp4, video (1).mp4, video (2).mp4
     */
    private static String getUniqueTrashFileName(File trashDir, String originalDisplayName) {
        File potentialFile = new File(trashDir, originalDisplayName);
        if (!potentialFile.exists()) {
            return originalDisplayName;
        }

        String nameWithoutExtension;
        String extension = "";
        int dotIndex = originalDisplayName.lastIndexOf('.');
        if (dotIndex > 0 && dotIndex < originalDisplayName.length() - 1) {
            nameWithoutExtension = originalDisplayName.substring(0, dotIndex);
            extension = originalDisplayName.substring(dotIndex);
        } else {
            nameWithoutExtension = originalDisplayName; // No extension or leading/trailing dot
        }

        int count = 1;
        while (true) {
            String newName = nameWithoutExtension + " (" + count + ")" + extension;
            potentialFile = new File(trashDir, newName);
            if (!potentialFile.exists()) {
                Log.d(TAG, "getUniqueTrashFileName: Generated unique name '" + newName + "' for original '" + originalDisplayName + "'");
                return newName;
            }
            count++;
            if (count > 1000) { // Safety break to prevent infinite loop with extreme cases
                Log.e(TAG, "getUniqueTrashFileName: Exceeded 1000 attempts to find unique name for " + originalDisplayName + ". Using UUID based name.");
                return java.util.UUID.randomUUID().toString() + extension;
            }
        }
    }

    /**
     * Permanently deletes a list of trash items from the file system and metadata.
     *
     * @param context       The application context.
     * @param itemsToDelete The list of TrashItem objects to permanently delete.
     * @return true if all specified items were successfully deleted (or didn't exist), false otherwise.
     */
    public static boolean permanentlyDeleteItems(Context context, List<TrashItem> itemsToDelete) {
        if (context == null || itemsToDelete == null || itemsToDelete.isEmpty()) {
            Log.w(TAG, "permanentlyDeleteItems: Context is null, or no items specified for deletion.");
            return true; // Nothing to do, so technically successful.
        }

        File trashDir = getTrashDirectory(context);
        if (trashDir == null) {
            Log.e(TAG, "permanentlyDeleteItems: Failed to get trash directory. Cannot delete files.");
            return false; // Cannot proceed without trash directory
        }

        List<TrashItem> currentMetadata = loadTrashMetadata(context);
        List<TrashItem> updatedMetadata = new ArrayList<>(currentMetadata);
        boolean allSucceeded = true;

        for (TrashItem item : itemsToDelete) {
            if (item == null || item.getTrashFileName() == null) {
                Log.w(TAG, "permanentlyDeleteItems: Skipping null item or item with null trashFileName.");
                continue;
            }
            File fileToDelete = new File(trashDir, item.getTrashFileName());
            boolean fileDeleted = true;
            if (fileToDelete.exists()) {
                if (fileToDelete.delete()) {
                    Log.i(TAG, "permanentlyDeleteItems: Successfully deleted file: " + fileToDelete.getAbsolutePath());
                } else {
                    Log.e(TAG, "permanentlyDeleteItems: Failed to delete file: " + fileToDelete.getAbsolutePath());
                    fileDeleted = false;
                    allSucceeded = false; // Mark failure if any file deletion fails
                }
            } else {
                Log.w(TAG, "permanentlyDeleteItems: File not found in trash, skipping deletion: " + item.getTrashFileName());
            }

            // Remove from metadata regardless of file deletion success,
            // as the intent is to remove it from the trash list.
            // If file deletion failed, it's an orphaned file, but metadata should be clean.
            boolean removedFromMeta = updatedMetadata.remove(item);
            if (!removedFromMeta) {
                Log.w(TAG, "permanentlyDeleteItems: Item " + item.getOriginalDisplayName() + " not found in current metadata list during deletion process.");
            } else {
                 Log.d(TAG, "permanentlyDeleteItems: Item " + item.getOriginalDisplayName() + " removed from metadata list.");
            }
        }

        if (!saveTrashMetadata(context, updatedMetadata)) {
            Log.e(TAG, "permanentlyDeleteItems: Failed to save updated trash metadata after attempting deletions.");
            allSucceeded = false; // Saving metadata is crucial
        }

        return allSucceeded;
    }

    /**
     * Empties the entire trash: deletes all files in the trash directory and clears the metadata.
     *
     * @param context The application context.
     * @return true if the trash was successfully emptied, false otherwise.
     */
    public static boolean emptyAllTrash(Context context) {
        if (context == null) {
            Log.e(TAG, "emptyAllTrash: Context is null.");
            return false;
        }

        File trashDir = getTrashDirectory(context);
        if (trashDir == null || !trashDir.exists()) {
            Log.w(TAG, "emptyAllTrash: Trash directory doesn't exist or cannot be accessed. Nothing to empty.");
            // Clear metadata anyway, in case it's out of sync
            saveTrashMetadata(context, new ArrayList<>());
            return true;
        }

        boolean allFilesDeleted = true;
        File[] files = trashDir.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isFile()) { // Only delete files, not subdirectories (though none are expected)
                    if (file.delete()) {
                        Log.i(TAG, "emptyAllTrash: Deleted file: " + file.getName());
                    } else {
                        Log.e(TAG, "emptyAllTrash: Failed to delete file: " + file.getName());
                        allFilesDeleted = false; // Mark failure if any file isn't deleted
                    }
                }
            }
        }

        // After attempting to delete all files, clear the metadata
        if (!saveTrashMetadata(context, new ArrayList<>())) {
            Log.e(TAG, "emptyAllTrash: Failed to clear trash metadata after deleting files.");
            return false; // This is a more critical failure
        }

        if (!allFilesDeleted) {
            Log.w(TAG, "emptyAllTrash: Not all files in the trash directory could be deleted, but metadata was cleared.");
            // Return true because metadata is clear, but log a warning.
            // The user expectation of an "empty" trash (UI-wise) is met.
        } else {
            Log.i(TAG, "emptyAllTrash: Successfully deleted all files and cleared metadata.");
        }
        return true; // Return true if metadata cleared, even if some files failed to delete (they are now orphaned)
    }

    /**
     * Restores a list of trash items from the trash directory back to a public
     * visible location (Downloads/FadCam/) and removes them from metadata.
     *
     * @param context        The application context.
     * @param itemsToRestore The list of TrashItem objects to restore.
     * @return true if all specified items were successfully restored and metadata updated, false otherwise.
     */
    public static boolean restoreItemsFromTrash(Context context, List<TrashItem> itemsToRestore) {
        if (context == null || itemsToRestore == null || itemsToRestore.isEmpty()) {
            Log.w(TAG, "restoreItemsFromTrash: Context is null, or no items specified for restoration.");
            return true; // Nothing to do.
        }

        File trashDir = getTrashDirectory(context);
        if (trashDir == null) {
            Log.e(TAG, "restoreItemsFromTrash: Failed to get trash directory.");
            return false;
        }

        // Get target public directory: Downloads/FadCam/
        // This requires SAF access if we are writing to it directly without user picking each time.
        // For simplicity, we'll construct the File path. Actual writing will need SAF if target is outside app-specific storage.
        File publicDownloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        File targetRestoreDir = new File(publicDownloadsDir, Constants.RECORDING_DIRECTORY); // Use existing constant

        if (!targetRestoreDir.exists()) {
            if (!targetRestoreDir.mkdirs()) {
                Log.e(TAG, "restoreItemsFromTrash: Failed to create target restore directory: " + targetRestoreDir.getAbsolutePath());
                // Depending on strategy, could fallback to app-specific dir or fail
                // For now, we will attempt to restore and let file operations fail if dir is not writable.
            }
        }

        List<TrashItem> currentMetadata = loadTrashMetadata(context);
        List<TrashItem> updatedMetadata = new ArrayList<>(currentMetadata);
        boolean allSucceeded = true;

        for (TrashItem item : itemsToRestore) {
            if (item == null || item.getTrashFileName() == null) {
                Log.w(TAG, "restoreItemsFromTrash: Skipping null item or item with null trashFileName.");
                continue;
            }

            File fileInTrash = new File(trashDir, item.getTrashFileName());
            if (!fileInTrash.exists()) {
                Log.w(TAG, "restoreItemsFromTrash: File " + item.getTrashFileName() + " not found in trash. Removing from metadata if present.");
                updatedMetadata.remove(item);
                continue; // Cannot restore a non-existent file
            }

            // Ensure unique name in target directory (using original display name as base)
            String restoredFileName = getUniqueFileNameInDirectory(targetRestoreDir, item.getOriginalDisplayName());
            File restoredFile = new File(targetRestoreDir, restoredFileName);

            boolean restoredSuccessfully = false;
            try {
                // Attempt to move (rename) the file
                if (fileInTrash.renameTo(restoredFile)) {
                    Log.i(TAG, "restoreItemsFromTrash: Successfully MOVED '" + fileInTrash.getName() + "' to '" + restoredFile.getAbsolutePath() + "'");
                    restoredSuccessfully = true;
                } else {
                    // Fallback to copy-delete if move fails (e.g. different filesystems)
                    Log.w(TAG, "restoreItemsFromTrash: Failed to move file directly. Attempting copy-delete for " + fileInTrash.getName());
                    try (InputStream in = new FileInputStream(fileInTrash);
                         OutputStream out = new FileOutputStream(restoredFile)) {
                        byte[] buf = new byte[8192];
                        int len;
                        while ((len = in.read(buf)) > 0) {
                            out.write(buf, 0, len);
                        }
                        Log.i(TAG, "restoreItemsFromTrash: Successfully COPIED '" + fileInTrash.getName() + "' to '" + restoredFile.getAbsolutePath() + "'");
                        if (fileInTrash.delete()) {
                            Log.d(TAG, "restoreItemsFromTrash: Successfully deleted original from trash after copy.");
                            restoredSuccessfully = true;
                        } else {
                            Log.e(TAG, "restoreItemsFromTrash: Copied file but FAILED to delete original from trash: " + fileInTrash.getName());
                            // File is restored, but also still in trash. Metadata will remove it from trash list.
                            // This is acceptable, a manual cleanup of the trash dir might be needed later for this file.
                            restoredSuccessfully = true; // Consider it restored for metadata purposes
                        }
                    } catch (IOException copyEx) {
                        Log.e(TAG, "restoreItemsFromTrash: IOException during copy-delete for " + fileInTrash.getName(), copyEx);
                        if (restoredFile.exists()) restoredFile.delete(); // Clean up partial copy
                        allSucceeded = false;
                    }
                }
            } catch (SecurityException se) {
                Log.e(TAG, "restoreItemsFromTrash: SecurityException during file operation for " + fileInTrash.getName() + ". Check permissions for target dir: " + targetRestoreDir.getAbsolutePath(), se);
                allSucceeded = false;
            }

            if (restoredSuccessfully) {
                // Add to MediaStore so it appears in Gallery apps
                Utils.scanFileWithMediaStore(context, restoredFile.getAbsolutePath());
                boolean removedFromMeta = updatedMetadata.remove(item);
                if (!removedFromMeta) {
                    Log.w(TAG, "restoreItemsFromTrash: Item " + item.getOriginalDisplayName() + " not found in metadata during restoration.");
                } else {
                    Log.d(TAG, "restoreItemsFromTrash: Item " + item.getOriginalDisplayName() + " removed from trash metadata after restoration.");
                }
            } else {
                allSucceeded = false; // Mark as failed if this item couldn't be restored
            }
        }

        if (!saveTrashMetadata(context, updatedMetadata)) {
            Log.e(TAG, "restoreItemsFromTrash: Failed to save updated trash metadata after restoration attempts.");
            allSucceeded = false; // This is a critical failure for consistency
        }

        return allSucceeded;
    }

    /**
     * Helper to get a unique filename in a target directory, similar to getUniqueTrashFileName.
     */
    private static String getUniqueFileNameInDirectory(File directory, String originalName) {
        if (!directory.exists() && !directory.mkdirs()) {
            Log.w(TAG, "getUniqueFileNameInDirectory: Target directory doesn't exist and couldn't be created: " + directory.getAbsolutePath());
            // Fallback to original name, hoping for the best or that caller handles it
            return originalName;
        }
        File file = new File(directory, originalName);
        if (!file.exists()) {
            return originalName;
        }

        String namePart = originalName;
        String extPart = "";
        int dotIndex = originalName.lastIndexOf('.');
        if (dotIndex > 0 && dotIndex < originalName.length() - 1) {
            namePart = originalName.substring(0, dotIndex);
            extPart = originalName.substring(dotIndex);
        }

        int count = 1;
        while (true) {
            String newName = namePart + " (" + count + ")" + extPart;
            file = new File(directory, newName);
            if (!file.exists()) {
                return newName;
            }
            count++;
        }
    }

    /**
     * Automatically deletes items from the trash that are older than a specified duration (e.g., 30 days).
     *
     * @param context The application context.
     * @return The number of items that were auto-deleted.
     */
    public static int autoDeleteOldTrashItems(Context context) {
        if (context == null) {
            Log.e(TAG, "autoDeleteOldTrashItems: Context is null.");
            return 0;
        }

        List<TrashItem> currentMetadata = loadTrashMetadata(context);
        if (currentMetadata.isEmpty()) {
            Log.d(TAG, "autoDeleteOldTrashItems: No trash metadata found. Nothing to auto-delete.");
            return 0;
        }

        List<TrashItem> itemsToKeep = new ArrayList<>();
        List<TrashItem> itemsActuallyDeleted = new ArrayList<>();
        long thirtyDaysInMillis = 30L * 24 * 60 * 60 * 1000; // 30 days in milliseconds
        long currentTime = System.currentTimeMillis();

        File trashDir = getTrashDirectory(context);
        if (trashDir == null) {
            Log.e(TAG, "autoDeleteOldTrashItems: Failed to get trash directory. Cannot delete files.");
            // We should still clean up metadata for expired items even if files can't be confirmed deleted.
        }

        for (TrashItem item : currentMetadata) {
            if (item == null || item.getDateTrashed() <= 0) { // Skip invalid items
                itemsToKeep.add(item); // Keep invalid items for now, or decide to filter them here.
                Log.w(TAG, "autoDeleteOldTrashItems: Skipping invalid trash item: " + item);
                continue;
            }

            if ((currentTime - item.getDateTrashed()) > thirtyDaysInMillis) {
                Log.i(TAG, "autoDeleteOldTrashItems: Item '" + item.getOriginalDisplayName() + "' is older than 30 days. Marking for deletion.");
                if (trashDir != null && item.getTrashFileName() != null) {
                    File fileToDelete = new File(trashDir, item.getTrashFileName());
                    if (fileToDelete.exists()) {
                        if (fileToDelete.delete()) {
                            Log.d(TAG, "autoDeleteOldTrashItems: Successfully deleted file: " + fileToDelete.getAbsolutePath());
                            itemsActuallyDeleted.add(item);
                        } else {
                            Log.e(TAG, "autoDeleteOldTrashItems: Failed to delete old file: " + fileToDelete.getAbsolutePath() + ". It will be removed from metadata.");
                            // Even if file deletion fails, we remove it from metadata to prevent re-attempts / UI display.
                            itemsActuallyDeleted.add(item); // Count as deleted for metadata purposes
                        }
                    } else {
                        Log.w(TAG, "autoDeleteOldTrashItems: Old file not found in trash, removing from metadata: " + item.getTrashFileName());
                        itemsActuallyDeleted.add(item); // File doesn't exist, so effectively deleted from trash system
                    }
                } else {
                     // Trash directory is null or trashFileName is null, but item expired. Remove from metadata.
                    Log.w(TAG, "autoDeleteOldTrashItems: Trash directory inaccessible or item has no filename, but item '" + item.getOriginalDisplayName() + "' expired. Removing from metadata.");
                    itemsActuallyDeleted.add(item); // Add to deleted list to ensure it's removed from metadata
                }
                // Do not add to itemsToKeep, it's being deleted
            } else {
                itemsToKeep.add(item); // Item is not old enough, keep it
            }
        }

        if (itemsActuallyDeleted.isEmpty()) {
            Log.d(TAG, "autoDeleteOldTrashItems: No items were old enough to be auto-deleted.");
            return 0;
        }

        // Save the updated metadata (only itemsToKeep)
        if (saveTrashMetadata(context, itemsToKeep)) {
            Log.i(TAG, "autoDeleteOldTrashItems: Successfully auto-deleted " + itemsActuallyDeleted.size() + " old items and updated metadata.");
            return itemsActuallyDeleted.size();
        } else {
            Log.e(TAG, "autoDeleteOldTrashItems: Deleted " + itemsActuallyDeleted.size() + " old files (or marked for metadata removal), but FAILED to save updated trash metadata.");
            // This is a problematic state. Files might be deleted but still in metadata if app restarts before next save.
            // Or metadata reflects deletions that weren't persisted.
            return 0; // Indicate failure or incomplete operation by returning 0 deletions persisted.
        }
    }

    // More methods will be added here for restore, deletePermanently, autoDeleteOldItems etc.

} 