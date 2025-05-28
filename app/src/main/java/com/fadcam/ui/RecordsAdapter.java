package com.fadcam.ui;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.graphics.Color;
import android.media.MediaMetadataRetriever;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.widget.PopupMenu;
import androidx.core.content.ContextCompat;
import androidx.documentfile.provider.DocumentFile;
import androidx.recyclerview.widget.RecyclerView;
import androidx.core.text.HtmlCompat;
import androidx.core.content.res.ResourcesCompat; // For getting drawables

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.fadcam.Constants;
import com.fadcam.R;
import com.fadcam.ui.VideoItem; // Ensure VideoItem import is correct
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import com.fadcam.Utils; // Import Utils for the new formatter

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
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import androidx.cardview.widget.CardView; // *** ADD Import ***
import com.fadcam.SharedPreferencesManager;
import android.content.Intent;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.Drawable;
import androidx.recyclerview.widget.DiffUtil;


public class RecordsAdapter extends RecyclerView.Adapter<RecordsAdapter.RecordViewHolder> {

    private Set<Uri> currentlyProcessingUris = new HashSet<>(); // Track processing URIs within adapter instance (passed from fragment)

    private static final String TAG = "RecordsAdapter";
    private final ExecutorService executorService; // Add ExecutorService
    private final SharedPreferencesManager sharedPreferencesManager;
    private final RecordActionListener actionListener; // Add the listener interface
    private final Context context;
    private List<VideoItem> records; // Now holds VideoItem objects
    private final OnVideoClickListener clickListener;
    private final OnVideoLongClickListener longClickListener;
    private final List<Uri> selectedVideosUris = new ArrayList<>(); // Track selection by URI
    private boolean isSelectionModeActive = false; // Track current mode within adapter
    private List<Uri> currentSelectedUris = new ArrayList<>(); // Keep track of selected items for binding
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
    // --- Complete Constructor ---
    public RecordsAdapter(Context context, List<VideoItem> records, ExecutorService executorService,
                          SharedPreferencesManager sharedPreferencesManager, // <<< Parameter Added
                          OnVideoClickListener clickListener, OnVideoLongClickListener longClickListener,
                          RecordActionListener actionListener) {

        this.context = Objects.requireNonNull(context, "Context cannot be null for RecordsAdapter");
        this.records = new ArrayList<>(records); // Use a mutable copy
        this.executorService = Objects.requireNonNull(executorService, "ExecutorService cannot be null");
        this.sharedPreferencesManager = Objects.requireNonNull(sharedPreferencesManager, "SharedPreferencesManager cannot be null"); // <<< STORE IT
        this.clickListener = clickListener; // Can be null if fragment doesn't implement/need it
        this.longClickListener = longClickListener; // Can be null
        this.actionListener = Objects.requireNonNull(actionListener, "RecordActionListener cannot be null"); // Assuming fragment always provides this


        // Initialize the cache directory path for checking temp files
        File cacheBaseDir = context.getExternalCacheDir();
        if (cacheBaseDir != null) {
            this.tempCacheDirectoryPath = new File(cacheBaseDir, "recording_temp").getAbsolutePath();
            Log.d(TAG, "Adapter Initialized. Temp cache path: " + this.tempCacheDirectoryPath);
        } else {
            Log.e(TAG, "Adapter Initialized. External cache dir is null! Cannot reliably identify temp files.");
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

    // *** Need a method to update the processing set from the Fragment ***
    public void updateProcessingUris(Set<Uri> processingUris) {
        boolean changed = !this.currentlyProcessingUris.equals(processingUris);
        if(changed){
            this.currentlyProcessingUris = new HashSet<>(processingUris); // Use a copy
            Log.d(TAG,"Adapter processing URIs updated: "+this.currentlyProcessingUris.size() +" items.");
            // TODO: Consider optimizing this? Maybe only notify items that changed state?
            // For simplicity now, refresh all potentially affected items (though maybe slow)
            // Find positions of items that *were* processing or *are now* processing
            // This requires iterating, might be simpler to just notifyDataSetChanged for now.
            notifyDataSetChanged(); // Simplest way to reflect changes across list
        }
    }

    // Inside RecordsAdapter.java

    @Override
    public void onBindViewHolder(@NonNull RecordViewHolder holder, int position) {
        // --- 1. Basic Checks & Get Data ---
        if (records == null || position < 0 || position >= records.size() || records.get(position) == null || records.get(position).uri == null) {
            Log.e(TAG,"onBindViewHolder: Invalid item/data at position " + position);
            // Optionally clear the views in the holder to avoid displaying stale data
            // holder.textViewRecord.setText(""); etc.
            return;
        }
        final VideoItem videoItem = records.get(position);
        final Uri videoUri = videoItem.uri;
        final String displayName = videoItem.displayName != null ? videoItem.displayName : "Unnamed Video";
        final String uriString = videoUri.toString();


        // --- 2. Determine Item States ---
        final boolean isCurrentlySelected = this.currentSelectedUris.contains(videoUri); // Selection state from Adapter
        final boolean isProcessing = this.currentlyProcessingUris.contains(videoUri);   // Processing state from Adapter
        final boolean isTemp = isTemporaryFile(videoItem);                               // Check if it's a temp file
        final boolean isOpened = sharedPreferencesManager.getOpenedVideoUris().contains(uriString); // Check if viewed before
        final boolean showNewBadge = !isTemp && !isOpened && !isProcessing;               // Logic for "NEW" badge visibility
        final boolean allowGeneralInteractions = !isProcessing;                             // Can user interact at all?
        final boolean allowMenuClick = allowGeneralInteractions && !this.isSelectionModeActive; // Can user open the item menu?

        Log.v(TAG,"onBindViewHolder Pos "+position+": Name="+displayName+ " Sel="+isCurrentlySelected+", Mode="+isSelectionModeActive+", Proc="+isProcessing+", Temp="+isTemp+", New="+showNewBadge+", AllowGen="+allowGeneralInteractions+", AllowMenu="+allowMenuClick);


        // --- 3. Bind Standard Data ---
        if (holder.textViewSerialNumber != null) holder.textViewSerialNumber.setText(String.valueOf(position + 1));
        if (holder.textViewRecord != null) holder.textViewRecord.setText(displayName);
        if (holder.textViewFileSize != null) holder.textViewFileSize.setText(formatFileSize(videoItem.size));
        if (holder.textViewFileTime != null) holder.textViewFileTime.setText(formatVideoDuration(getVideoDuration(videoUri)));
        if (holder.textViewTimeAgo != null) holder.textViewTimeAgo.setText(Utils.formatTimeAgo(videoItem.lastModified));
        if (holder.imageViewThumbnail != null) setThumbnail(holder, videoUri);


        // --- 4. Visibility Logic for Overlays/Badges ---

        // Warning Dot for TEMP files (only visible if not processing)
        if (holder.menuWarningDot != null) {
            holder.menuWarningDot.setVisibility(isTemp && allowGeneralInteractions ? View.VISIBLE : View.GONE);
        }

        // Processing Overlay (Scrim and Spinner)
        if (holder.processingScrim != null) holder.processingScrim.setVisibility(isProcessing ? View.VISIBLE : View.GONE);
        if (holder.processingSpinner != null) holder.processingSpinner.setVisibility(isProcessing ? View.VISIBLE : View.GONE);

        // *** RESTORED Status Badge Logic ***
        if (holder.textViewStatusBadge != null && context != null) {
            if (isProcessing) {
                holder.textViewStatusBadge.setVisibility(View.GONE); // Hide all badges during processing
            } else if (isTemp) {
                // Show TEMP badge
                holder.textViewStatusBadge.setText("TEMP");
                holder.textViewStatusBadge.setBackground(ContextCompat.getDrawable(context, R.drawable.temp_badge_background));
                holder.textViewStatusBadge.setTextColor(ContextCompat.getColor(context, R.color.black));
                holder.textViewStatusBadge.setVisibility(View.VISIBLE);
            } else if (showNewBadge) {
                // Show NEW badge (only if not Temp and not Opened)
                holder.textViewStatusBadge.setText("NEW");
                holder.textViewStatusBadge.setBackground(ContextCompat.getDrawable(context, R.drawable.new_badge_background));
                holder.textViewStatusBadge.setTextColor(ContextCompat.getColor(context, R.color.white));
                holder.textViewStatusBadge.setVisibility(View.VISIBLE);
            } else {
                // Hide badge if neither Temp nor New
                holder.textViewStatusBadge.setVisibility(View.GONE);
            }
        }
        // *** END RESTORED Status Badge Logic ***


        // --- 5. Handle Selection Mode Visuals (Checkbox & BACKGROUND/TEXT COLOR) ---
        if (holder.checkIcon != null) {
            if (this.isSelectionModeActive) {
                holder.checkIcon.setVisibility(View.VISIBLE);
                if (isCurrentlySelected) {
                    holder.checkIcon.setImageResource(R.drawable.placeholder_checkbox_checked); // Replace with actual drawable
                    holder.checkIcon.setAlpha(1.0f);
                    // Highlight background and adjust text color for contrast
                    if(holder.itemView instanceof CardView && context!=null) ((CardView)holder.itemView).setCardBackgroundColor(ContextCompat.getColor(context, R.color.colorPrimary));
                    if(holder.textViewRecord != null) holder.textViewRecord.setTextColor(Color.BLACK); // Use BLACK for contrast on primary color
                } else {
                    holder.checkIcon.setImageResource(R.drawable.placeholder_checkbox_outline); // Replace with actual drawable
                    holder.checkIcon.setAlpha(0.7f);
                    // Reset background and text color
                    if(holder.itemView instanceof CardView && context!=null) ((CardView)holder.itemView).setCardBackgroundColor(ContextCompat.getColor(context, R.color.gray));
                    if(holder.textViewRecord != null) holder.textViewRecord.setTextColor(holder.defaultTextColor); // Use stored default
                }
            } else { // Not in selection mode
                holder.checkIcon.setVisibility(View.GONE);
                // Ensure default background and text color are restored
                if(holder.itemView instanceof CardView && context!=null) ((CardView)holder.itemView).setCardBackgroundColor(ContextCompat.getColor(context, R.color.gray));
                if(holder.textViewRecord != null) holder.textViewRecord.setTextColor(holder.defaultTextColor);
            }
        } else { Log.w(TAG, "checkIcon is null in ViewHolder at pos "+position); }


        // --- 6. Set Enabled State and Listeners ---
        holder.itemView.setEnabled(allowGeneralInteractions); // Click/LongClick allowed if not processing
        if (holder.menuButtonContainer != null) {
            holder.menuButtonContainer.setEnabled(allowMenuClick); // Menu allowed if !processing AND !selectionMode
            holder.menuButtonContainer.setClickable(allowMenuClick);
            if(holder.menuButton != null) holder.menuButton.setAlpha(allowMenuClick ? 1.0f : 0.4f); // Dim if disabled
        } else { Log.w(TAG,"menuButtonContainer is null at pos "+position); }


        holder.itemView.setOnClickListener(v -> {
            if (allowGeneralInteractions && clickListener != null) { clickListener.onVideoClick(videoItem); }
        });
        holder.itemView.setOnLongClickListener(v -> {
            if (allowGeneralInteractions && longClickListener != null) { longClickListener.onVideoLongClick(videoItem, true); }
            return allowGeneralInteractions;
        });

        // INSTEAD, set a click listener on the menuButtonContainer
        if (holder.menuButtonContainer != null) {
            holder.menuButtonContainer.setOnClickListener(v -> {
                // Check allowMenuClick again inside the listener, as the state might have changed
                // (though less likely if onBindViewHolder is efficient)
                boolean isStillAllowMenuClick = !this.currentlyProcessingUris.contains(videoItem.uri) && !this.isSelectionModeActive;
                if (isStillAllowMenuClick) {
                    PopupMenu popup = setupPopupMenu(holder, videoItem);
                    if (popup != null) {
                        popup.show();
                    }
                }
            });
        }

    } // End onBindViewHolder

    @Override
    public int getItemCount() {
        return records == null ? 0 : records.size();
    }

    // --- Thumbnail Loading ---
    private void setThumbnail(RecordViewHolder holder, Uri videoUri) {
        if (videoUri == null || holder.imageViewThumbnail == null || context == null) {
            return;
        }

        // Use Glide with optimized settings
        Glide.with(context)
            .load(videoUri)
            .override(300, 300) // Limit image size in memory
            .centerCrop()
            .diskCacheStrategy(DiskCacheStrategy.ALL) // Cache thumbnails
            .dontAnimate() // Skip animations for smoother scrolling
            .thumbnail(0.1f) // Use a smaller thumbnail while loading
            .placeholder(R.drawable.ic_video_placeholder) // Show placeholder while loading
            .error(R.drawable.ic_error) // Show error image if loading fails
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




    // Helper to find item position by URI (important for notifyItemChanged)
    // Make this public so the Fragment can call it after marking an item opened
    public int findPositionByUri(Uri uri) { // <-- *** CHANGED to public ***
        if (uri == null || records == null) {
            Log.w(TAG, "findPositionByUri called with null uri or null records list.");
            return -1;
        }
        for (int i = 0; i < records.size(); i++) {
            VideoItem item = records.get(i);
            // Added null check for item as well for safety
            if (item != null && item.uri != null && uri.equals(item.uri)) { // Use equals for URI comparison
                return i;
            }
        }
        Log.v(TAG,"URI not found in adapter list: " + uri); // Use v for verbose logs
        return -1; // Not found
    }


    // --- Popup Menu and Actions (Major Updates Here) ---
    private PopupMenu setupPopupMenu(RecordViewHolder holder, VideoItem videoItem) {
        if (context == null) {
            Log.e(TAG, "Context is null in setupPopupMenu. Cannot show menu.");
            return null;
        }
        PopupMenu popup = new PopupMenu(context, holder.menuButtonContainer);
        popup.getMenuInflater().inflate(R.menu.video_item_menu, popup.getMenu());

        // Attempt to force icons to be visible
        try {
            Field[] fields = popup.getClass().getDeclaredFields();
            for (Field field : fields) {
                if ("mPopup".equals(field.getName())) {
                    field.setAccessible(true);
                    Object menuPopupHelper = field.get(popup);
                    Class<?> classPopupHelper = Class.forName(menuPopupHelper.getClass().getName());
                    Method setForceShowIcon = classPopupHelper.getMethod("setForceShowIcon", boolean.class);
                    setForceShowIcon.invoke(menuPopupHelper, true);
                    break;
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error forcing menu icons to show.", e);
        }

        // ----- Fix Start for this method(setupPopupMenu) (FaditorX styling) -----
        MenuItem faditorXItem = popup.getMenu().findItem(R.id.action_edit_faditorx);
        if (faditorXItem != null) {
            // String baseTitle = context.getString(R.string.video_menu_edit_faditorx); // Assuming R.string.video_menu_edit_faditorx exists
            // Let's get the title directly from the menu item, which might already have the base string from XML.
            String baseTitle = faditorXItem.getTitle().toString();
            if (baseTitle.endsWith(" ")) { // Remove trailing space if present from XML to avoid double spacing
                baseTitle = baseTitle.substring(0, baseTitle.length() - 1);
            }

            String comingSoonBadgeText = "(Coming Soon)";
            String fullTitleText = baseTitle + " " + comingSoonBadgeText; // Add a space here for separation

            SpannableString styledTitle = new SpannableString(fullTitleText);

            // Style "Edit with FaditorX" part as gray
            styledTitle.setSpan(new ForegroundColorSpan(Color.GRAY), 0, baseTitle.length(), SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE);

            // Style "(Coming Soon)" badge part
            int badgeStartIndex = baseTitle.length() + 1; // +1 for the space separator
            int badgeEndIndex = fullTitleText.length();

            styledTitle.setSpan(new BackgroundColorSpan(Color.RED), badgeStartIndex, badgeEndIndex, SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE);
            styledTitle.setSpan(new ForegroundColorSpan(Color.WHITE), badgeStartIndex, badgeEndIndex, SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE);
            // Optionally, make the badge text a bit smaller or bold
            // styledTitle.setSpan(new RelativeSizeSpan(0.8f), badgeStartIndex, badgeEndIndex, SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE);
            // styledTitle.setSpan(new StyleSpan(android.graphics.Typeface.BOLD), badgeStartIndex, badgeEndIndex, SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE);

            faditorXItem.setTitle(styledTitle);

            // Gray out the icon
            Drawable icon = faditorXItem.getIcon();
            if (icon != null) {
                Drawable mutedIcon = icon.mutate(); // Important to mutate before applying filter
                mutedIcon.setColorFilter(new PorterDuffColorFilter(Color.GRAY, PorterDuff.Mode.SRC_IN));
                faditorXItem.setIcon(mutedIcon);
            }
        }
        // ----- Fix Ended for this method(setupPopupMenu) (FaditorX styling) -----

        popup.setOnMenuItemClickListener(item -> {
            int itemId = item.getItemId();

            if (itemId == R.id.action_delete) {
                if (actionListener != null) {
                    actionListener.onMoveToTrashStarted(videoItem.displayName);
                    actionListener.onMoveToTrashRequested(videoItem);
                }
                    return true;
            } else if (itemId == R.id.action_info) {
                showVideoInfoDialog(videoItem);
                    return true;
            } else if (itemId == R.id.action_rename) {
                showRenameDialog(videoItem);
                    return true;
            } else if (itemId == R.id.action_save) {
                saveVideoToGalleryInternal(videoItem);
                    return true;
            } else if (itemId == R.id.action_edit_faditorx) {
                Toast.makeText(context, R.string.remote_toast_coming_soon, Toast.LENGTH_SHORT).show();
                    return true;
                }
                return false;
            });
        // ----- Fix Ended for this method(setupPopupMenu) -----
        return popup;
    }

    // --- Restored Rename Logic ---
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
        // Request focus and select all text for easy editing
        if (input != null) {
        input.requestFocus();
            // It's often better to post a delayed runnable for selectAll to ensure view is fully ready
            input.post(() -> input.selectAll());
        }


        builder.setView(dialogView);
        builder.setPositiveButton(R.string.universal_ok, (dialog, which) -> { // Using R.string.universal_ok if it exists, otherwise "OK"
            String newNameBase = "";
            if (input != null && input.getText() != null) {
                newNameBase = input.getText().toString().trim();
            }

            if (!newNameBase.isEmpty()) {
                String originalExtension = (dotIndex > 0 && dotIndex < currentName.length() - 1) ? currentName.substring(dotIndex) : ("." + Constants.RECORDING_FILE_EXTENSION);

                String sanitizedBaseName = newNameBase
                        .replaceAll("[^a-zA-Z0-9\\-_]", "_")
                        .replaceAll("\\s+", "_")
                        .replaceAll("_+", "_")
                        .replaceAll("^_|_$", "");

                if (sanitizedBaseName.isEmpty()) sanitizedBaseName = "renamed_video";

                String newFullName = sanitizedBaseName + originalExtension;

                if (newFullName.equals(videoItem.displayName)) {
                    Toast.makeText(context, "Name hasn't changed", Toast.LENGTH_SHORT).show();
                } else {
                    // Perform rename on a background thread
                    executorService.submit(() -> renameVideo(videoItem, newFullName));
                }
            } else {
                Toast.makeText(context, R.string.toast_rename_name_empty, Toast.LENGTH_SHORT).show();
            }
        });
        builder.setNegativeButton(R.string.universal_cancel, (dialog, which) -> dialog.cancel());
        builder.show();
    }

    private void renameVideo(VideoItem videoItem, String newFullName) {
        if (context == null) return;
        Uri videoUri = videoItem.uri;
        int position = findPositionByUri(videoUri);

        if (position == -1) {
            Log.e(TAG, "Cannot rename, item not found in adapter list: " + videoUri);
            if (context instanceof Activity) {
                ((Activity) context).runOnUiThread(() -> Toast.makeText(context, context.getString(R.string.toast_rename_failed) + " (Item not found)", Toast.LENGTH_SHORT).show());
            }
            return;
        }

        boolean renameSuccess = false;
        Uri newUri = null;

        try {
            Log.d(TAG, "Attempting rename. URI: " + videoUri + ", Scheme: " + videoUri.getScheme() + ", New Name: " + newFullName);

            if ("file".equals(videoUri.getScheme()) && videoUri.getPath() != null) {
                File oldFile = new File(videoUri.getPath());
                File parentDir = oldFile.getParentFile();
                if (parentDir == null) throw new IOException("Cannot get parent directory for file URI");
                File newFile = new File(parentDir, newFullName);

                if (oldFile.renameTo(newFile)) {
                    renameSuccess = true;
                    newUri = Uri.fromFile(newFile);
                    Log.i(TAG, "Renamed file system file successfully.");
                } else {
                    Log.e(TAG, "File.renameTo() failed for " + oldFile.getPath());
                }
            } else if ("content".equals(videoUri.getScheme())) {
                newUri = DocumentsContract.renameDocument(context.getContentResolver(), videoUri, newFullName);
                if (newUri != null) {
                    renameSuccess = true;
                    Log.i(TAG, "Renamed SAF document successfully. New URI: " + newUri);
                } else {
                     Log.w(TAG, "DocumentsContract.renameDocument returned null for: " + videoUri + " to '" + newFullName + "'.");
                    // Check if rename actually happened (some providers might return null on success if name didn't change or already exists)
                    DocumentFile checkDoc = DocumentFile.fromSingleUri(context, videoUri); // Check original URI, it might have been renamed in place
                    if (checkDoc != null && newFullName.equals(checkDoc.getName())) {
                        Log.w(TAG, "Rename check: File with new name exists under original URI. Assuming success.");
                        newUri = checkDoc.getUri(); 
                        renameSuccess = true;
                    } else { // Check if a new file with the new name exists in the parent
                        DocumentFile parent = checkDoc != null ? checkDoc.getParentFile() : null;
                        if (parent != null) {
                            DocumentFile renamedFile = parent.findFile(newFullName);
                            if (renamedFile != null && renamedFile.exists()) {
                                 Log.w(TAG, "Rename check: File with new name exists in parent. Assuming success.");
                                 newUri = renamedFile.getUri();
                                renameSuccess = true;
                            }
                        }
                    }
                }
            } else {
                Log.e(TAG, "Unsupported URI scheme for renaming: " + videoUri.getScheme());
            }

            if (renameSuccess && newUri != null) {
                final Uri finalNewUri = newUri; // Create an effectively final variable
                VideoItem updatedItem = new VideoItem(
                        finalNewUri,
                        newFullName,
                        videoItem.size, // Ideally, re-query size from newUri if possible
                        System.currentTimeMillis()
                );
                if (context instanceof Activity) {
                    ((Activity) context).runOnUiThread(() -> {
                        if (position >= 0 && position < records.size()) {
                           records.set(position, updatedItem);
                            if (selectedVideosUris.contains(videoItem.uri)) { // If old URI was selected
                                selectedVideosUris.remove(videoItem.uri);
                                selectedVideosUris.add(finalNewUri); // Replace with new URI
                            }
                           notifyItemChanged(position);
                Toast.makeText(context, R.string.toast_rename_success, Toast.LENGTH_SHORT).show();
                        } else {
                             Log.e(TAG, "Rename success but position " + position + " is invalid for records list size " + records.size());
                             Toast.makeText(context, "Rename successful, but list update failed.", Toast.LENGTH_LONG).show();
                             // Consider a full reload if this happens.
                        }
                    });
                }
            } else if (!renameSuccess) {
                if (context instanceof Activity) {
                    ((Activity) context).runOnUiThread(() -> Toast.makeText(context, R.string.toast_rename_failed, Toast.LENGTH_SHORT).show());
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Exception during rename for " + videoUri + " to " + newFullName, e);
            if (context instanceof Activity) {
                ((Activity) context).runOnUiThread(() -> Toast.makeText(context, context.getString(R.string.toast_rename_failed) + " (Error)", Toast.LENGTH_SHORT).show());
            }
        }
    }

    // --- Restored Save to Gallery Logic (using actionListener for progress) ---
    private void saveVideoToGalleryInternal(VideoItem videoItem) {
        if (context == null || videoItem == null || videoItem.uri == null || videoItem.displayName == null) {
            if (actionListener != null) {
                // Run on UI thread if context is available to show Toast
                if (context instanceof Activity) {
                     ((Activity)context).runOnUiThread(() -> actionListener.onSaveToGalleryFinished(false, "Invalid video data.", null));
                } else { // No activity context, just log
                     Log.e(TAG, "saveVideoToGalleryInternal: Invalid video data or context null before starting save.");
                }
            }
            return;
        }

        final Uri sourceUri = videoItem.uri;
        final String filename = videoItem.displayName;

        if (actionListener != null) {
            actionListener.onSaveToGalleryStarted(filename); // Notify fragment (UI thread)
        }

        executorService.submit(() -> {
            boolean success = false;
            Uri resultUri = null;
            String message;
            File downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            File fadCamDir = new File(downloadsDir, Constants.RECORDING_DIRECTORY); // Use Constant
            if (!fadCamDir.exists()) {
                if (!fadCamDir.mkdirs()) {
                    Log.e(TAG, "Failed to create FadCam directory in Downloads.");
                    message = "Save Failed: Cannot create directory.";
                    // ----- Fix Start for this method(saveVideoToGalleryInternal)-----
                    final String finalMessageForLambda = message;
                    // ----- Fix Ended for this method(saveVideoToGalleryInternal)-----
                    // Notify listener on UI thread
                    if (context instanceof Activity && actionListener != null) {
                        ((Activity)context).runOnUiThread(() -> actionListener.onSaveToGalleryFinished(false, finalMessageForLambda, null));
                    }
                    return;
                }
            }
            File destFile = new File(fadCamDir, filename);
            int counter = 0;
            // Handle potential name conflicts by appending (1), (2), etc.
            while (destFile.exists()) {
                counter++;
                String nameWithoutExt = filename;
                String extension = "";
                int dotIndex = filename.lastIndexOf('.');
                if (dotIndex > 0 && dotIndex < filename.length() - 1) {
                    nameWithoutExt = filename.substring(0, dotIndex);
                    extension = filename.substring(dotIndex);
                }
                destFile = new File(fadCamDir, nameWithoutExt + " (" + counter + ")" + extension);
            }


            try (InputStream in = context.getContentResolver().openInputStream(sourceUri);
                 OutputStream out = new FileOutputStream(destFile)) {

                if (in == null) throw new IOException("Failed to open input stream for " + sourceUri);

                byte[] buf = new byte[8192]; // Increased buffer size
                int len;
                while ((len = in.read(buf)) > 0) {
                    out.write(buf, 0, len);
                }
                out.flush(); 
                Utils.scanFileWithMediaStore(context, destFile.getAbsolutePath()); // Scan the new file
                resultUri = Uri.fromFile(destFile); // Not entirely correct for MediaStore, but good for logs
                success = true;
                message = "Video saved to Downloads/FadCam";
                Log.i(TAG, "Video saved successfully to: " + destFile.getAbsolutePath());

            } catch (Exception e) {
                Log.e(TAG, "Error saving video to gallery", e);
                message = "Save Failed: " + e.getMessage();
                if (destFile.exists()) { // Clean up partial file
                    destFile.delete();
                }
            }

            final boolean finalSuccess = success;
            final String finalMessage = message;
            final Uri finalResultUri = success ? Uri.fromFile(destFile) : null; // Use actual destFile URI if successful for listener

            if (context instanceof Activity && actionListener != null) {
                 ((Activity)context).runOnUiThread(() -> actionListener.onSaveToGalleryFinished(finalSuccess, finalMessage, finalResultUri));
            } else if (actionListener != null) { // Fallback if context not an activity (e.g. service context)
                // This case is less likely for UI-triggered actions but good for robustness
                // Directly call if Looper is available or handle differently
                if (Looper.myLooper() == Looper.getMainLooper()) {
                     actionListener.onSaveToGalleryFinished(finalSuccess, finalMessage, finalResultUri);
                } else {
                     new Handler(Looper.getMainLooper()).post(() -> actionListener.onSaveToGalleryFinished(finalSuccess, finalMessage, finalResultUri));
                }
            }
        });
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
    @SuppressLint("NotifyDataSetChanged") // Suppress only for fallback case
    public void updateRecords(List<VideoItem> newRecords) {
        if (newRecords == null) {
            Log.w(TAG, "updateRecords called with null list");
            return;
        }
        
        // Use DiffUtil to calculate the differences and dispatch updates efficiently
        DiffUtil.DiffResult diffResult = DiffUtil.calculateDiff(new DiffUtil.Callback() {
            @Override
            public int getOldListSize() {
                return records.size();
            }

            @Override
            public int getNewListSize() {
                return newRecords.size();
            }

            @Override
            public boolean areItemsTheSame(int oldPosition, int newPosition) {
                // Check if URIs match to determine if items are the same
                Uri oldUri = records.get(oldPosition).uri;
                Uri newUri = newRecords.get(newPosition).uri;
                return oldUri != null && newUri != null && oldUri.equals(newUri);
            }

            @Override
            public boolean areContentsTheSame(int oldPosition, int newPosition) {
                VideoItem oldItem = records.get(oldPosition);
                VideoItem newItem = newRecords.get(newPosition);
                
                // Compare fields that affect the display
                return oldItem.displayName.equals(newItem.displayName) &&
                       oldItem.size == newItem.size &&
                       oldItem.lastModified == newItem.lastModified &&
                       oldItem.isTemporary == newItem.isTemporary;
            }
        });
        
        // Update the records list with a copy of the new list
        this.records = new ArrayList<>(newRecords);
        
        // Dispatch updates to the adapter
        diffResult.dispatchUpdatesTo(this);
        
        Log.d(TAG, "Updated records using DiffUtil. New size: " + records.size());
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
        TextView textViewStatusBadge; // *** ADDED: Reference for the single status badge ***
        ImageView menuWarningDot;       // *** ADDED: Reference for the warning dot ***
        FrameLayout menuButtonContainer; // *** ADDED: Reference to the container holding the button and dot ***

        View processingScrim;
        ProgressBar processingSpinner;
        // *** ADD field for the new TextView ***
        TextView textViewTimeAgo;
        // *** ADD Field for Default Text Color ***
        int defaultTextColor; // Store the default color
        RecordViewHolder(View itemView) {
            super(itemView);
            imageViewThumbnail = itemView.findViewById(R.id.image_view_thumbnail);
            textViewRecord = itemView.findViewById(R.id.text_view_record);
            textViewFileSize = itemView.findViewById(R.id.text_view_file_size);
            textViewFileTime = itemView.findViewById(R.id.text_view_file_time);
            textViewSerialNumber = itemView.findViewById(R.id.text_view_serial_number);
            checkIcon = itemView.findViewById(R.id.check_icon);
            menuButton = itemView.findViewById(R.id.menu_button);

            menuWarningDot = itemView.findViewById(R.id.menu_warning_dot);             // *** Find the warning dot ***
            menuButtonContainer = itemView.findViewById(R.id.menu_button_container);   // *** Find the container ***
            textViewStatusBadge = itemView.findViewById(R.id.text_view_status_badge); // *** Find the new single badge ***

            processingScrim = itemView.findViewById(R.id.processing_scrim);
            processingSpinner = itemView.findViewById(R.id.processing_spinner);
            // *** Find the new TextView ***
            textViewTimeAgo = itemView.findViewById(R.id.text_view_time_ago);

            // *** Store the default text color ***
            if (textViewRecord != null) {
                defaultTextColor = textViewRecord.getCurrentTextColor();
            } else {
                Log.e(TAG,"ViewHolder: textViewRecord is NULL, cannot get default text color!");
                // Set a fallback default color?
                defaultTextColor = Color.WHITE; // Example fallback
            }

        }
    }

    /**
     * Method called by the Fragment to update the adapter's visual mode
     * and provide the current list of selected URIs.
     *
     * @param isActive         True if selection mode should be active, false otherwise.
     * @param currentSelection The list of URIs currently selected in the Fragment.
     */
    @SuppressLint("NotifyDataSetChanged")
    public void setSelectionModeActive(boolean isActive, @NonNull List<Uri> currentSelection) {
        boolean modeChanged = this.isSelectionModeActive != isActive;
        boolean selectionChanged = !this.currentSelectedUris.equals(currentSelection); // Check if selection list differs

        this.isSelectionModeActive = isActive;
        this.currentSelectedUris = new ArrayList<>(currentSelection); // Update internal copy

        // If mode changed OR selection changed, refresh visuals
        if (modeChanged || selectionChanged) {
            Log.d(TAG,"setSelectionModeActive: Mode=" + isActive + ", SelCount=" + currentSelectedUris.size() + ". Triggering notifyDataSetChanged.");
            notifyDataSetChanged(); // Full refresh easiest way to update all visuals
        } else {
            Log.d(TAG,"setSelectionModeActive: Mode and selection unchanged, no refresh needed.");
        }
    }


    // --- Delete Helper (Must be accessible or copied here) ---
    // You need the `deleteVideoUri` method from RecordsFragment here or accessible
    // For simplicity, I'll include a copy here, ensure it's kept in sync or refactored to a Util class
    /*
    private boolean deleteVideoUri(Uri uri) {
        if (context == null || uri == null) return false;
        Log.d(TAG, "Adapter attempting to delete URI: " + uri + " (This should be handled by Fragment now)");

        // The actual file deletion logic (file vs content URI) was complex and is now centralized
        // in RecordsFragment's moveToTrashVideoItem, which uses TrashManager.
        // This method in the adapter is now redundant and potentially problematic if called.

        // If for some reason this was called, it should delegate to the fragment or fail.
        // For now, let's just log and return false to indicate it didn't handle it.
        Log.e(TAG, "deleteVideoUri in adapter was called. This is deprecated. Deletion should be handled by the fragment.");
        return false;
    }
    */
}