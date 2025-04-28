package com.fadcam.ui;

import android.annotation.SuppressLint;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.widget.PopupMenu;
import androidx.core.content.ContextCompat;
import androidx.documentfile.provider.DocumentFile;
import androidx.recyclerview.widget.RecyclerView;
import androidx.core.text.HtmlCompat;

import com.bumptech.glide.Glide;
import com.fadcam.Constants;
import com.fadcam.R;
import com.fadcam.ui.VideoItem; // Ensure VideoItem import is correct
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

public class RecordsAdapter extends RecyclerView.Adapter<RecordsAdapter.RecordViewHolder> {

    private static final String TAG = "RecordsAdapter";

    private final Context context;
    private List<VideoItem> records; // Now holds VideoItem objects
    private final OnVideoClickListener clickListener;
    private final OnVideoLongClickListener longClickListener;
    private final List<Uri> selectedVideosUris = new ArrayList<>(); // Track selection by URI

    // *** NEW: Store the path to the specific cache directory ***
    private final String tempCacheDirectoryPath;
    // --- Interfaces Updated ---
    public interface OnVideoClickListener {
        void onVideoClick(VideoItem videoItem); // Pass VideoItem
    }

    public interface OnVideoLongClickListener {
        void onVideoLongClick(VideoItem videoItem, boolean isSelected); // Pass VideoItem
    }

    // --- Constructor Updated ---
    public RecordsAdapter(Context context, List<VideoItem> records, OnVideoClickListener clickListener, OnVideoLongClickListener longClickListener) {
        this.context = Objects.requireNonNull(context, "Context cannot be null for RecordsAdapter"); // Use Objects.requireNonNull
        this.records = new ArrayList<>(records);
        this.clickListener = clickListener;
        this.longClickListener = longClickListener;

        // *** Initialize the cache directory path ***
        File cacheBaseDir = context.getExternalCacheDir();
        if (cacheBaseDir != null) {
            // Use the correct sub-directory where temp files are initially saved by MediaRecorder
            this.tempCacheDirectoryPath = new File(cacheBaseDir, "recording_temp").getAbsolutePath();
            Log.d(TAG, "Temp cache directory path set to: " + this.tempCacheDirectoryPath);
        } else {
            Log.e(TAG, "External cache dir is null! Cannot reliably identify temp files by path.");
            // Set to null or an invalid path to ensure the check always fails safely
            this.tempCacheDirectoryPath = null;
        }
    }

    // *** NEW: Helper method to check if a VideoItem is in the cache directory ***
    private boolean isTemporaryFile(VideoItem item) {
        if (item == null || item.uri == null || tempCacheDirectoryPath == null) {
            return false; // Cannot determine if data is invalid or cache path unknown
        }
        // Temp files should *always* have a file:// scheme as they are created directly by MediaRecorder in cache
        if ("file".equals(item.uri.getScheme())) {
            String path = item.uri.getPath();
            if (path != null) {
                File file = new File(path);
                File parentDir = file.getParentFile();
                // Check if the file's parent directory matches the designated cache directory
                boolean isInCache = parentDir != null && tempCacheDirectoryPath.equals(parentDir.getAbsolutePath());
                // Optional: Add logging for debugging
                // Log.v(TAG, "isTemporaryFile check for " + item.displayName + ": Path=" + path + ", Parent="+(parentDir != null ? parentDir.getAbsolutePath() : "null")+ ", IsInCache=" + isInCache);
                return isInCache;
            }
        }
        // Not a file URI, so not a temp file from cache
        return false;
    }

    @NonNull
    @Override
    public RecordViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_record, parent, false);
        return new RecordViewHolder(view);
    }

    // --- Updated onBindViewHolder ---
    // In RecordsAdapter.java

    // --- Updated onBindViewHolder ---
    @Override
    public void onBindViewHolder(@NonNull RecordViewHolder holder, int position) {
        // ... (Existing null checks for records, item, uri, name) ...
        if (records == null || position < 0 || position >= records.size() || records.get(position) == null || records.get(position).uri == null) {
            // Log error and hide/collapse view
            Log.e(TAG,"Invalid item or data at position: " + position);
            holder.itemView.setVisibility(View.GONE);
            holder.itemView.setLayoutParams(new RecyclerView.LayoutParams(0, 0));
            return;
        }
        holder.itemView.setVisibility(View.VISIBLE);
        holder.itemView.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));


        final VideoItem videoItem = records.get(position);
        final Uri videoUri = videoItem.uri;
        final String displayName = videoItem.displayName != null ? videoItem.displayName : "No Name";

        // Bind basic data
        holder.textViewSerialNumber.setText(String.valueOf(position + 1));
        holder.textViewRecord.setText(displayName);
        holder.textViewFileSize.setText(formatFileSize(videoItem.size));
        long duration = getVideoDuration(videoUri);
        holder.textViewFileTime.setText(formatVideoDuration(duration));
        setThumbnail(holder, videoUri);

        // Determine if temp based on path
        boolean isTempFile = isTemporaryFile(videoItem);

        // Set visibility for TEMP badge on thumbnail
        if (holder.textViewTempBadge != null) {
            holder.textViewTempBadge.setVisibility(isTempFile ? View.VISIBLE : View.GONE);
        }

        // *** START: Set visibility for the WARNING dot on the menu button ***
        if (holder.menuWarningDot != null) {
            holder.menuWarningDot.setVisibility(isTempFile ? View.VISIBLE : View.GONE);
        } else {
            Log.w(TAG,"menuWarningDot view is null in ViewHolder.");
        }
        // *** END: Set visibility for the WARNING dot on the menu button ***

        // Set click listeners (pass VideoItem)
        holder.itemView.setOnClickListener(v -> { if (clickListener != null) clickListener.onVideoClick(videoItem); });
        holder.itemView.setOnLongClickListener(v -> {
            boolean isSelected = !selectedVideosUris.contains(videoUri);
            toggleSelection(videoUri, isSelected);
            if(longClickListener != null) longClickListener.onVideoLongClick(videoItem, isSelected);
            return true;
        });

        // Setup popup menu - the modification to the menu item text happens inside here now
        setupPopupMenu(holder, videoItem); // Pass VideoItem

        // Update selection state visuals
        updateSelectionState(holder, selectedVideosUris.contains(videoUri));
    }

    @Override
    public int getItemCount() {
        return records == null ? 0 : records.size();
    }

    // --- Thumbnail Loading ---
    private void setThumbnail(RecordViewHolder holder, Uri videoUri) {
        // Use default drawable if context is somehow null
        int placeholder = (context != null) ? R.drawable.ic_video_placeholder : android.R.drawable.ic_menu_gallery;
        int errorPlaceholder = (context != null) ? R.drawable.ic_error : android.R.drawable.ic_menu_close_clear_cancel;
        if(context == null) { Log.e(TAG,"Context is null during Glide load!"); return; }
        Glide.with(context)
                .load(videoUri)
                .placeholder(placeholder)
                .error(errorPlaceholder) // Show if loading fails
                .centerCrop() // Or fitCenter depending on desired look
                .into(holder.imageViewThumbnail);
    }

    // --- Selection Handling ---
    private void toggleSelection(Uri videoUri, boolean isSelected) {
        if (videoUri == null) return;
        if (isSelected) {
            if (!selectedVideosUris.contains(videoUri)) {
                selectedVideosUris.add(videoUri);
            }
        } else {
            selectedVideosUris.remove(videoUri);
        }
        // Find item's position and update only that item for efficiency
        int position = findPositionByUri(videoUri);
        if (position != -1) {
            notifyItemChanged(position); // Update specific item
        } else {
            Log.w(TAG,"Could not find position for URI: "+ videoUri + " during toggle. List size: " + records.size());
            // Maybe list was updated concurrently? Do a full refresh as fallback.
            // notifyDataSetChanged(); // Use this cautiously
        }
    }

    private void updateSelectionState(RecordViewHolder holder, boolean isSelected) {
        holder.checkIcon.setVisibility(isSelected ? View.VISIBLE : View.GONE);
        // Optional: Add background highlight for selected items
        holder.itemView.setBackgroundColor(isSelected ? ContextCompat.getColor(context, R.color.material_on_surface_stroke) : // Use a theme color or define one
                ContextCompat.getColor(context, R.color.gray)); // Default card color
    }

    // Helper to find item position by URI (important for notifyItemChanged)
    private int findPositionByUri(Uri uri) {
        if (uri == null || records == null) return -1;
        for (int i = 0; i < records.size(); i++) {
            VideoItem item = records.get(i);
            if (item != null && uri.equals(item.uri)) { // Use equals for URI comparison
                return i;
            }
        }
        return -1; // Not found
    }


    // --- Popup Menu and Actions (Major Updates Here) ---
    private void setupPopupMenu(RecordViewHolder holder, VideoItem videoItem) {
        if (context == null || videoItem == null || videoItem.uri == null || holder.menuButtonContainer == null) {
            // Disable click if invalid state
            if (holder.menuButtonContainer != null) holder.menuButtonContainer.setOnClickListener(null);
            if(holder.menuButton != null) holder.menuButton.setEnabled(false);
            return;
        }
        if (holder.menuButton != null) holder.menuButton.setEnabled(true);

        // Use the container to handle clicks now, as it covers the button and dot
        holder.menuButtonContainer.setOnClickListener(v -> {
            PopupMenu popupMenu = new PopupMenu(context, holder.menuButtonContainer); // Anchor to container
            MenuItem infoItem = null; // To store reference
            String defaultInfoTitle = "Video Info"; // Store default

            try {
                popupMenu.getMenuInflater().inflate(R.menu.menu_video_options, popupMenu.getMenu());
                infoItem = popupMenu.getMenu().findItem(R.id.action_info); // Find the item
                defaultInfoTitle = infoItem != null ? infoItem.getTitle().toString() : "Video Info"; // Get default title

                // Force icons (keep existing try-catch)
                Field field = PopupMenu.class.getDeclaredField("mPopup");
                field.setAccessible(true);
                Object menuPopupHelper = field.get(popupMenu);
                Class<?> classPopupHelper = Class.forName(menuPopupHelper.getClass().getName());
                Method setForceIcons = classPopupHelper.getMethod("setForceShowIcon", boolean.class);
                setForceIcons.invoke(menuPopupHelper, true);

                // *** Modify Info item title for temp files ***
                boolean isTempFile = isTemporaryFile(videoItem);
                if (infoItem != null) {
                    infoItem.setTitle(isTempFile ? "⚠️ Video Info (Unprocessed)" : defaultInfoTitle);
                } else {
                    Log.w(TAG,"Could not find R.id.action_info menu item.");
                }

            } catch (Exception e) {
                Log.e(TAG, "Error setting up popup menu (inflation or reflection)", e);
                // Fallback: Inflate without trying to modify/force icons
                try { popupMenu.getMenuInflater().inflate(R.menu.menu_video_options, popupMenu.getMenu()); } catch (Exception e2) { return; }
            }

            popupMenu.setOnMenuItemClickListener(item -> {
                // ... (Existing delete, save, rename logic remains the same) ...
                int itemId = item.getItemId();
                if (itemId == R.id.action_rename) {
                    showRenameDialog(videoItem); // Rename still allowed
                    return true;
                } else if (itemId == R.id.action_delete) {
                    confirmDelete(videoItem);
                    return true;
                } else if (itemId == R.id.action_save) {
                    saveToGallery(videoItem.uri);
                    return true;
                } else if (itemId == R.id.action_info) {
                    showVideoInfoDialog(videoItem);
                    return true;
                }
                return false;
            });

            // Make sure to reset the title if the menu is dismissed without action,
            // especially important if the viewholder might be recycled.
            // (Though with unique menu instances per click, less critical here than in onBindViewHolder)
            // popupMenu.setOnDismissListener(menu -> { /* Optional: Reset title if needed */ });

            popupMenu.show();
        });
    }

    // --- Action Implementations (Need URI Handling) ---

    private void confirmDelete(VideoItem videoItem) {
        if(context == null) return;
        new MaterialAlertDialogBuilder(context)
                .setTitle(context.getString(R.string.dialog_del_title))
                .setMessage(context.getString(R.string.dialog_del_notice) + "\n(" + videoItem.displayName + ")")
                .setPositiveButton(context.getString(R.string.dialog_del_confirm), (dialog, which) -> {
                    if (deleteVideoUri(videoItem.uri)) { // Use central delete helper
                        int position = findPositionByUri(videoItem.uri);
                        if (position != -1) {
                            // Remove from both data list and selection list
                            if (position < records.size()) records.remove(position); // Check bounds
                            selectedVideosUris.remove(videoItem.uri);
                            notifyItemRemoved(position);
                            notifyItemRangeChanged(position, records.size()); // Update subsequent positions
                        } else {
                            Log.w(TAG, "Item not found in adapter for deletion, list possibly changed. Refreshing all.");
                            notifyDataSetChanged(); // Less efficient fallback
                        }
                    } else {
                        Toast.makeText(context, "Failed to delete video.", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton(context.getString(R.string.universal_cancel), null)
                .show();
    }


    private void saveToGallery(Uri videoUri) {
        if (context == null) return;
        Log.d(TAG, "Save to Gallery requested for URI: " + videoUri);

        // Delegate based on URI scheme
        if ("file".equals(videoUri.getScheme())) {
            saveFileUriToGallery(videoUri);
        } else if ("content".equals(videoUri.getScheme())) {
            saveContentUriToGallery(videoUri);
        } else {
            Log.w(TAG, "Unsupported URI scheme for saving to gallery: " + videoUri.getScheme());
            Toast.makeText(context, "Cannot save this type of file to gallery.", Toast.LENGTH_SHORT).show();
        }
    }

    // Method for saving internal files (file:// URI) to gallery
    private void saveFileUriToGallery(Uri fileUri) {
        File videoFile = new File(fileUri.getPath());
        if (!videoFile.exists()) {
            Toast.makeText(context, R.string.toast_video_not_found, Toast.LENGTH_SHORT).show();
            return;
        }

        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, videoFile.getName());
        values.put(MediaStore.MediaColumns.MIME_TYPE, "video/" + Constants.RECORDING_FILE_EXTENSION);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // *** FIX: Change Movies to Downloads ***
            values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + File.separator + Constants.RECORDING_DIRECTORY);
            Log.d(TAG, "Setting Relative Path (Q+): " + values.getAsString(MediaStore.MediaColumns.RELATIVE_PATH));
        } else {
            // Handle legacy storage if needed (ensure it also targets Downloads)
            File publicDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS); // <-- FIX: Target Downloads here too
            if (publicDir == null) {
                Toast.makeText(context, "Downloads directory unavailable", Toast.LENGTH_SHORT).show();
                return;
            }
            File fadCamPublicDir = new File(publicDir, Constants.RECORDING_DIRECTORY);
            if (!fadCamPublicDir.exists() && !fadCamPublicDir.mkdirs()) {
                Log.e(TAG, "Failed to create public Downloads dir: " + fadCamPublicDir.getPath());
                Toast.makeText(context, "Failed to create gallery directory", Toast.LENGTH_SHORT).show();
                return;
            }
            File destFile = new File(fadCamPublicDir, videoFile.getName());
            // This might still be needed for some pre-Q MediaScanner interaction, though relying on RELATIVE_PATH+insert is better.
            // Keeping it for potential compatibility, but the RELATIVE_PATH method above should ideally handle Q+.
            // If using MediaStore.Images.Media.insertImage, DATA is used differently. Stick to direct stream copying.
            // values.put(MediaStore.MediaColumns.DATA, destFile.getAbsolutePath());
            // Log.d(TAG, "Setting DATA Path (< Q): " + values.getAsString(MediaStore.MediaColumns.DATA));

            // For pre-Q, MediaStore might not respect RELATIVE_PATH well. Direct file copy and MediaScanner scan might be needed.
            // However, let's try the insert method first, as it's cleaner if it works.
        }

        ContentResolver resolver = context.getContentResolver();
        // Use Video collection even for older APIs
        Uri collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
        Uri itemUri = null;
        OutputStream out = null;
        InputStream in = null;

        try {
            itemUri = resolver.insert(collection, values);
            if (itemUri == null) throw new IOException("Failed to create MediaStore entry for file URI");

            out = resolver.openOutputStream(itemUri);
            in = new FileInputStream(videoFile);

            if (out == null) throw new IOException("Failed to open output stream for MediaStore (file URI)");

            byte[] buf = new byte[8192];
            int len;
            while ((len = in.read(buf)) > 0) {
                out.write(buf, 0, len);
            }
            Toast.makeText(context, context.getString(R.string.toast_video_saved) + " (Downloads/FadCam)", Toast.LENGTH_SHORT).show(); // Updated toast text
            Log.i(TAG, "Saved file:// URI to gallery (Downloads): " + itemUri);

        } catch (Exception e) { // Catch general exceptions too
            Toast.makeText(context, context.getString(R.string.toast_video_save_fail), Toast.LENGTH_SHORT).show();
            Log.e(TAG, "Failed to save file URI to gallery", e);
            if (itemUri != null) { try { resolver.delete(itemUri, null, null); } catch (Exception ignored) {} }
        } finally {
            try { if (in != null) in.close(); } catch (IOException ignored) {}
            try { if (out != null) out.close(); } catch (IOException ignored) {}
        }
    }


    // Method for saving SAF files (content:// URI) to gallery
    private void saveContentUriToGallery(Uri sourceUri) {
        if (context == null || sourceUri == null) return;
        ContentResolver resolver = context.getContentResolver();
        String displayName = getFileName(sourceUri);
        if (displayName == null) {
            displayName = "FadCam_Video_" + System.currentTimeMillis() + "." + Constants.RECORDING_FILE_EXTENSION;
        }

        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, displayName);
        values.put(MediaStore.MediaColumns.MIME_TYPE, "video/" + Constants.RECORDING_FILE_EXTENSION);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // *** FIX: Change Movies to Downloads ***
            values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + File.separator + Constants.RECORDING_DIRECTORY);
            Log.d(TAG, "Setting Relative Path (Q+): " + values.getAsString(MediaStore.MediaColumns.RELATIVE_PATH));
        } else {
            // Pre-Q handling for content URIs saving to public storage is complex
            // and less common. You'd typically copy to a temp file in cache
            // then use the saveFileUriToGallery logic with MediaScanner.
            // For simplicity, we'll show an error for now on pre-Q for content URIs.
            Log.e(TAG, "Save Content URI to gallery on pre-Q not supported directly. Use internal storage or newer Android.");
            Toast.makeText(context, "Saving from custom location to Gallery not supported on this Android version.", Toast.LENGTH_LONG).show();
            return;
        }

        Uri collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
        Uri itemUri = null;
        OutputStream out = null;
        InputStream in = null;

        try {
            itemUri = resolver.insert(collection, values);
            if (itemUri == null) throw new IOException("Failed to create MediaStore entry for content URI");

            out = resolver.openOutputStream(itemUri);
            in = resolver.openInputStream(sourceUri); // *** Read from the source content URI ***

            if (out == null || in == null) throw new IOException("Failed to open streams (In:" + (in != null) + " Out:" + (out != null) + ") for content URI save");

            byte[] buf = new byte[8192];
            int len;
            while ((len = in.read(buf)) > 0) {
                out.write(buf, 0, len);
            }
            Toast.makeText(context, context.getString(R.string.toast_video_saved) + " (Downloads/FadCam)", Toast.LENGTH_SHORT).show(); // Updated toast text
            Log.i(TAG, "Saved content:// URI to gallery (Downloads): " + itemUri);

        } catch (Exception e) { // Catch general exceptions too
            Toast.makeText(context, context.getString(R.string.toast_video_save_fail), Toast.LENGTH_SHORT).show();
            Log.e(TAG, "Failed to save content URI to gallery. URI: " + sourceUri, e);
            if (itemUri != null) { try { resolver.delete(itemUri, null, null); } catch (Exception ignored) {} }
        } finally {
            try { if (in != null) in.close(); } catch (IOException ignored) {}
            try { if (out != null) out.close(); } catch (IOException ignored) {}
        }
    }

// In RecordsAdapter.java

    private void showRenameDialog(VideoItem videoItem) {
        if (context == null) return;
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(context);
        builder.setTitle(R.string.rename_video_title);

        View dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_rename, null);
        final TextInputEditText input = dialogView.findViewById(R.id.edit_text_name);

        String currentName = videoItem.displayName;
        int dotIndex = currentName.lastIndexOf(".");
        String baseName = (dotIndex > 0) ? currentName.substring(0, dotIndex) : currentName;
        input.setText(baseName);
        input.requestFocus();
        input.selectAll();

        builder.setView(dialogView);
        builder.setPositiveButton("OK", (dialog, which) -> {
            String newNameBase = input.getText().toString().trim();
            if (!newNameBase.isEmpty()) {
                String originalExtension = (dotIndex > 0) ? currentName.substring(dotIndex) : ("." + Constants.RECORDING_FILE_EXTENSION);

                // Sanitize: Allow letters, numbers, hyphen, underscore. Replace others (including space).
                String sanitizedBaseName = newNameBase
                        .replaceAll("[^a-zA-Z0-9\\-_]", "_") // Replace disallowed chars with _
                        .replaceAll("\\s+", "_")          // *** Replace one or more spaces with _ ***
                        .replaceAll("_+", "_")            // Collapse multiple underscores
                        .replaceAll("^_|_$", "");         // Trim leading/trailing underscores

                // Ensure it's not empty after sanitizing and trimming
                if (sanitizedBaseName.isEmpty()) sanitizedBaseName = "renamed_video";

                String newFullName = sanitizedBaseName + originalExtension;

                if (newFullName.equals(videoItem.displayName)) {
                    Toast.makeText(context, "Name hasn't changed", Toast.LENGTH_SHORT).show();
                } else {
                    renameVideo(videoItem, newFullName);
                }
            } else {
                Toast.makeText(context, R.string.toast_rename_name_empty, Toast.LENGTH_SHORT).show();
            }
        });
        builder.setNegativeButton(R.string.universal_cancel, (dialog, which) -> dialog.cancel());
        builder.show();
    }


    private void renameVideo(VideoItem videoItem, String newFullName) {
        if(context == null) return;
        Uri videoUri = videoItem.uri;
        int position = findPositionByUri(videoUri); // Find current position

        if (position == -1) {
            Log.e(TAG, "Cannot rename, item not found in adapter list: " + videoUri);
            Toast.makeText(context, R.string.toast_rename_failed + " (Item not found)", Toast.LENGTH_SHORT).show();
            return;
        }

        boolean renameSuccess = false;
        Uri newUri = null; // Store the new URI after successful rename

        try {
            Log.d(TAG, "Attempting rename. URI: " + videoUri + ", Scheme: " + videoUri.getScheme() + ", New Name: " + newFullName);

            if ("file".equals(videoUri.getScheme())) {
                File oldFile = new File(videoUri.getPath());
                File parentDir = oldFile.getParentFile();
                if (parentDir == null) throw new IOException("Cannot get parent directory for file URI");
                File newFile = new File(parentDir, newFullName);

                // Check if target file already exists (optional, renameTo might overwrite or fail)
                if (newFile.exists() && !newFile.getAbsolutePath().equals(oldFile.getAbsolutePath())) {
                    Log.w(TAG,"Rename target file exists: "+ newFile.getPath());
                    // Decide: fail, allow overwrite, or auto-rename (e.g., add _1)? Let renameTo handle for now.
                }

                if (oldFile.renameTo(newFile)) {
                    renameSuccess = true;
                    newUri = Uri.fromFile(newFile); // Get the new file URI
                    Log.i(TAG,"Renamed file system file successfully.");
                } else {
                    Log.e(TAG,"File.renameTo() failed for "+oldFile.getPath());
                }
            } else if ("content".equals(videoUri.getScheme())) {
                newUri = DocumentsContract.renameDocument(context.getContentResolver(), videoUri, newFullName);
                if (newUri != null) {
                    renameSuccess = true; // renameDocument returns the *new* URI on success
                    Log.i(TAG,"Renamed SAF document successfully. New URI: "+newUri);
                } else {
                    Log.w(TAG, "DocumentsContract.renameDocument returned null for: " + videoUri + " to '" + newFullName + "'. This might happen if the name already exists or other provider issues.");
                    // Verify if rename actually happened (check if new name exists)
                    DocumentFile checkDoc = DocumentFile.fromSingleUri(context, videoUri);
                    if (checkDoc != null) {
                        DocumentFile parent = checkDoc.getParentFile();
                        if (parent != null) {
                            DocumentFile renamedFile = parent.findFile(newFullName);
                            if (renamedFile != null && renamedFile.exists()) {
                                Log.w(TAG, "Rename check: File with new name exists. Assuming success.");
                                newUri = renamedFile.getUri(); // Get the URI of the (potentially pre-existing) file
                                renameSuccess = true;
                            }
                        }
                    }
                }
            } else {
                Log.e(TAG,"Unsupported URI scheme for renaming: " + videoUri.getScheme());
            }

            // --- Update Adapter Data if successful ---
            if (renameSuccess && newUri != null) {
                // Create a *new* VideoItem with updated URI and name
                // Size and LastModified might technically change slightly on some filesystems
                // For simplicity, we'll update lastModified to now, keep size same unless re-queried.
                VideoItem updatedItem = new VideoItem(
                        newUri,
                        newFullName,
                        videoItem.size, // Re-query size for accuracy if needed: DocumentFile.fromSingleUri(context, newUri).length()
                        System.currentTimeMillis() // Update timestamp to reflect rename time
                );

                records.set(position, updatedItem); // Update item in the list
                selectedVideosUris.remove(videoUri); // Remove old URI from selection if present
                notifyItemChanged(position); // Notify adapter
                Toast.makeText(context, R.string.toast_rename_success, Toast.LENGTH_SHORT).show();
            } else if (!renameSuccess){ // Only show failure toast if rename attempt actually failed
                Log.e(TAG, "Failed to rename video: " + videoUri);
                Toast.makeText(context, R.string.toast_rename_failed, Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            Log.e(TAG, "Exception during rename for " + videoUri + " to " + newFullName, e);
            Toast.makeText(context, R.string.toast_rename_failed + " (Error)", Toast.LENGTH_SHORT).show();
        }
    }


    // --- Updated showVideoInfoDialog ---

    /**
     * Displays a dialog with detailed information about the selected video item.
     * Shows a specific warning if the item is identified as a temporary file
     * residing in the cache directory.
     *
     * @param videoItem The VideoItem representing the selected video.
     */
    private void showVideoInfoDialog(VideoItem videoItem) {
        // 1. Pre-checks
        if (context == null) {
            Log.e(TAG,"Cannot show info dialog, context is null.");
            return;
        }
        if (videoItem == null || videoItem.uri == null) {
            Log.e(TAG,"Cannot show info dialog, videoItem or its URI is null.");
            Toast.makeText(context, "Cannot get video information.", Toast.LENGTH_SHORT).show();
            return;
        }

        // 2. Inflate layout and find views
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(context);
        View dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_video_info, null);
        if (dialogView == null) {
            Log.e(TAG, "Failed to inflate dialog_video_info layout.");
            return;
        }

        TextView tvFileName = dialogView.findViewById(R.id.tv_file_name);
        TextView tvFileSize = dialogView.findViewById(R.id.tv_file_size);
        TextView tvFilePath = dialogView.findViewById(R.id.tv_file_path);
        TextView tvLastModified = dialogView.findViewById(R.id.tv_last_modified);
        TextView tvDuration = dialogView.findViewById(R.id.tv_duration);
        TextView tvResolution = dialogView.findViewById(R.id.tv_resolution);
        ImageView ivCopyToClipboard = dialogView.findViewById(R.id.iv_copy_to_clipboard);
        TextView tvTempWarning = dialogView.findViewById(R.id.tv_temp_file_warning); // Find warning TextView

        // Check if all essential views were found
        if (tvFileName == null || tvFileSize == null || tvFilePath == null || tvLastModified == null ||
                tvDuration == null || tvResolution == null || ivCopyToClipboard == null || tvTempWarning == null) {
            Log.e(TAG,"One or more views were not found in dialog_video_info.xml. Check IDs.");
            Toast.makeText(context, "Error displaying video info.", Toast.LENGTH_SHORT).show();
            return;
        }

        // 3. Prepare Data
        Uri videoUri = videoItem.uri;
        String fileName = videoItem.displayName != null ? videoItem.displayName : "Unknown Name"; // Handle null display name

        String formattedFileSize = formatFileSize(videoItem.size);
        String formattedLastModified = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                .format(new Date(videoItem.lastModified));

        // Determine display path (URI string or actual file path)
        String filePathDisplay = videoUri.toString(); // Default to URI
        if ("file".equals(videoUri.getScheme()) && videoUri.getPath() != null) {
            filePathDisplay = videoUri.getPath();
        }

        // Fetch metadata (using helpers that handle URI)
        long durationMs = getVideoDuration(videoUri);
        String resolution = getVideoResolution(videoUri);
        String formattedDuration = formatVideoDuration(durationMs);

        // 4. Populate UI Text Views
        tvFileName.setText(fileName);
        tvFileSize.setText(formattedFileSize);
        tvFilePath.setText(filePathDisplay);
        tvLastModified.setText(formattedLastModified);
        tvDuration.setText(formattedDuration);
        tvResolution.setText(resolution);

        // 5. Determine if it's a temp file and show/hide warning
        boolean isTempFile = isTemporaryFile(videoItem); // Use the path-checking helper
        // Show/Hide and SET the processed HTML text
        if (tvTempWarning != null) {
            if (isTempFile) {
                // *** FIX: Get the string, process HTML, and set it ***
                String warningHtmlString = context.getString(R.string.warning_temp_file_detail);
                tvTempWarning.setText(HtmlCompat.fromHtml(warningHtmlString, HtmlCompat.FROM_HTML_MODE_LEGACY));
                tvTempWarning.setVisibility(View.VISIBLE);
            } else {
                tvTempWarning.setVisibility(View.GONE);
            }
            Log.d(TAG, "Info Dialog: Temp warning visibility/text set. IsTemp: " + isTempFile);
        } else {
            Log.w(TAG, "tv_temp_file_warning view not found in dialog layout.");
        }
        // ... (Prepare clipboard string - NOTE: HTML tags won't be in clipboard text) ...
        String clipboardText;
        if(isTempFile) {
            // For clipboard, use a plain text version maybe? Or include raw HTML markers?
            // Simple version without HTML:
            clipboardText = "IMPORTANT NOTE: This is an unprocessed temporary file... (See dialog for details)";
        } else {
            clipboardText = ""; // Or just don't add extra note for normal files
        }
        String videoInfo = String.format(Locale.US,
                "File Name: %s\nFile Size: %s\nFile Path: %s\nLast Modified: %s\nDuration: %s\nResolution: %s\n%s",
                fileName, formattedFileSize, filePathDisplay, formattedLastModified, formattedDuration, resolution, clipboardText.trim());

        // The actual warning text is set via R.string.warning_temp_file_detail in the XML layout



        // 7. Set up Copy-to-Clipboard Action
        ivCopyToClipboard.setOnClickListener(v -> {
            ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
            ClipData clip = ClipData.newPlainText("Video Info", videoInfo); // Use the constructed string
            if (clipboard != null) {
                clipboard.setPrimaryClip(clip);
                Toast.makeText(context, "Video info copied to clipboard", Toast.LENGTH_SHORT).show();
            } else {
                Log.e(TAG, "ClipboardManager service is null.");
                Toast.makeText(context, "Could not access clipboard", Toast.LENGTH_SHORT).show();
            }
        });

        // 8. Build and Show Dialog
        builder.setTitle("Video Information")
                .setView(dialogView)
                .setPositiveButton("Close", (dialog, which) -> dialog.dismiss())
                .show();
    } // End of showVideoInfoDialog

    // --- NEW Helper to get resolution (refactored from info dialog) ---
    private String getVideoResolution(Uri videoUri) {
        if (context == null || videoUri == null) return "N/A";
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        String resolution = "N/A";
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && "content".equals(videoUri.getScheme())) {
                try (ParcelFileDescriptor pfd = context.getContentResolver().openFileDescriptor(videoUri, "r")) {
                    if(pfd != null) retriever.setDataSource(pfd.getFileDescriptor());
                    else throw new IOException("PFD was null for " + videoUri);
                }
            } else {
                retriever.setDataSource(context, videoUri);
            }
            String width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH);
            String height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT);
            if (width != null && height != null) {
                resolution = width + " x " + height;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error retrieving video resolution for URI: " + videoUri, e);
        } finally {
            try { retriever.release(); } catch (IOException e) { Log.e(TAG, "Error releasing MMD retriever for resolution", e); }
        }
        return resolution;
    }

    // --- Update and Utility Methods ---

    // Updates the adapter's list
    @SuppressLint("NotifyDataSetChanged") // Use this if DiffUtil isn't implemented yet
    public void updateRecords(List<VideoItem> newRecords) {
        Log.d(TAG,"Updating adapter records. Old size: " + (this.records != null ? this.records.size() : 0) + ", New size: " + (newRecords != null ? newRecords.size() : 0));
        this.records.clear();
        if (newRecords != null) {
            this.records.addAll(newRecords);
        }
        this.selectedVideosUris.clear(); // Clear selection when the list changes
        notifyDataSetChanged(); // Consider DiffUtil for performance later
    }

    // Format file size helper
    private String formatFileSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        int exp = (int) (Math.log(bytes) / Math.log(1024));
        char pre = "KMGTPE".charAt(exp - 1);
        return String.format(Locale.US, "%.1f %sB", bytes / Math.pow(1024, exp), pre);
    }

    // Format video duration helper
    private String formatVideoDuration(long durationMs) {
        if (durationMs <= 0) return "0s";
        long totalSeconds = durationMs / 1000;
        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;

        if (hours > 0) {
            return String.format(Locale.getDefault(), "%dh %02dm %02ds", hours, minutes, seconds);
        } else if (minutes > 0) {
            return String.format(Locale.getDefault(), "%dm %02ds", minutes, seconds);
        } else {
            return String.format(Locale.getDefault(), "%ds", seconds);
        }
    }

    // Get video duration from URI (Helper)
    private long getVideoDuration(Uri videoUri) {
        if(context == null || videoUri == null) return 0;
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        long durationMs = 0;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && "content".equals(videoUri.getScheme())) {
                try (ParcelFileDescriptor pfd = context.getContentResolver().openFileDescriptor(videoUri, "r")) {
                    if (pfd != null) retriever.setDataSource(pfd.getFileDescriptor());
                    else throw new IOException("PFD was null for "+ videoUri);
                }
            } else {
                retriever.setDataSource(context, videoUri); // Works for file:// and older content:// access
            }
            String durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            if (durationStr != null) { durationMs = Long.parseLong(durationStr); }
        } catch (Exception e) {
            Log.e(TAG, "Error getting video duration for URI: " + videoUri, e);
        } finally {
            try { retriever.release(); } catch (IOException e) { Log.e(TAG,"Error releasing MMD retriever", e); }
        }
        return durationMs;
    }

    // Get file name from URI (Helper) - Crucial for Save to Gallery/Rename default
    @SuppressLint("Range") // Suppress lint check for getColumnIndexOrThrow
    private String getFileName(Uri uri) {
        if (context == null || uri == null) return null;
        String result = null;
        if ("content".equals(uri.getScheme())) {
            try (Cursor cursor = context.getContentResolver().query(uri, null, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    // Use getColumnIndex to avoid crashing if column doesn't exist
                    int nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    if(nameIndex != -1) {
                        result = cursor.getString(nameIndex);
                    } else {
                        Log.w(TAG, OpenableColumns.DISPLAY_NAME + " column not found for URI: " + uri);
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "Could not query display name for content URI: " + uri, e);
            }
        }
        // Fallback to path if name query fails or scheme is 'file'
        if (result == null) {
            result = uri.getPath();
            if (result != null) {
                int cut = result.lastIndexOf('/');
                if (cut != -1) {
                    result = result.substring(cut + 1);
                }
            }
        }
        return result;
    }

    // --- ViewHolder ---
    // --- Updated ViewHolder ---
    static class RecordViewHolder extends RecyclerView.ViewHolder {
        ImageView imageViewThumbnail;
        TextView textViewRecord;
        TextView textViewFileSize;
        TextView textViewFileTime;
        TextView textViewSerialNumber;
        ImageView checkIcon;
        ImageView menuButton;           // Reference to the 3-dot icon itself
        TextView textViewTempBadge;
        ImageView menuWarningDot;       // *** ADDED: Reference for the warning dot ***
        FrameLayout menuButtonContainer; // *** ADDED: Reference to the container holding the button and dot ***


        RecordViewHolder(View itemView) {
            super(itemView);
            imageViewThumbnail = itemView.findViewById(R.id.image_view_thumbnail);
            textViewRecord = itemView.findViewById(R.id.text_view_record);
            textViewFileSize = itemView.findViewById(R.id.text_view_file_size);
            textViewFileTime = itemView.findViewById(R.id.text_view_file_time);
            textViewSerialNumber = itemView.findViewById(R.id.text_view_serial_number);
            checkIcon = itemView.findViewById(R.id.check_icon);
            menuButton = itemView.findViewById(R.id.menu_button);
            textViewTempBadge = itemView.findViewById(R.id.text_view_temp_badge);
            menuWarningDot = itemView.findViewById(R.id.menu_warning_dot);             // *** Find the warning dot ***
            menuButtonContainer = itemView.findViewById(R.id.menu_button_container);   // *** Find the container ***
        }
    }


    // --- Delete Helper (Must be accessible or copied here) ---
    // You need the `deleteVideoUri` method from RecordsFragment here or accessible
    // For simplicity, I'll include a copy here, ensure it's kept in sync or refactored to a Util class
    private boolean deleteVideoUri(Uri uri) {
        if(context == null || uri == null) return false;
        Log.d(TAG, "Adapter attempting to delete URI: " + uri);
        try {
            if ("file".equals(uri.getScheme())) {
                File file = new File(uri.getPath());
                if (file.exists() && file.delete()) {
                    Log.d(TAG, "Adapter deleted internal file: " + file.getName());
                    return true;
                } else { Log.e(TAG, "Adapter failed to delete internal file: " + uri.getPath()); return false; }
            } else if ("content".equals(uri.getScheme())) {
                if (DocumentsContract.deleteDocument(context.getContentResolver(), uri)) {
                    Log.d(TAG, "Adapter deleted SAF document: " + uri); return true;
                } else {
                    DocumentFile doc = DocumentFile.fromSingleUri(context, uri);
                    if(doc == null || !doc.exists()){
                        Log.w(TAG,"Adapter: deleteDocument returned false, but file doesn't exist. Success. URI: "+ uri); return true;
                    } else { Log.e(TAG, "Adapter failed to delete SAF document (deleteDocument returned false): " + uri); return false; }
                }
            } else { Log.w(TAG, "Adapter cannot delete URI with unknown scheme: " + uri.getScheme()); return false; }
        } catch (SecurityException e) {
            Log.e(TAG, "Adapter SecurityException deleting URI: " + uri, e);
            Toast.makeText(context, "Permission denied deleting file.", Toast.LENGTH_SHORT).show(); return false;
        } catch (Exception e) { Log.e(TAG, "Adapter Exception deleting URI: " + uri, e); return false; }
    }
}