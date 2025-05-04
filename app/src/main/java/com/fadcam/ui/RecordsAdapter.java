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

        // Always set up the menu logic, but its trigger (menuButtonContainer) is enabled/disabled above
        setupPopupMenu(holder, videoItem);

    } // End onBindViewHolder

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

                // Add badge to 'Edit with FaditorX' menu item
                MenuItem faditorXItem = popupMenu.getMenu().findItem(R.id.action_edit_faditorx);
                if (faditorXItem != null) {
                    String mainText = "Edit with FaditorX  ";
                    String badgeText = "COMING SOON";
                    SpannableString spannable = new SpannableString(mainText + badgeText);
                    int badgeStart = mainText.length();
                    int badgeEnd = badgeStart + badgeText.length();
                    // Red background, white bold text, slightly smaller, rounded corners effect
                    spannable.setSpan(new BackgroundColorSpan(0xFFE43C3C), badgeStart, badgeEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    spannable.setSpan(new ForegroundColorSpan(0xFFFFFFFF), badgeStart, badgeEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    spannable.setSpan(new StyleSpan(android.graphics.Typeface.BOLD), badgeStart, badgeEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    spannable.setSpan(new RelativeSizeSpan(0.85f), badgeStart, badgeEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    // Dull the main text and icon
                    spannable.setSpan(new ForegroundColorSpan(0xFFAAAAAA), 0, badgeStart, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    faditorXItem.setTitle(spannable);
                    faditorXItem.setIcon(R.drawable.ic_edit_cut); // Ensure icon is set
                    // Dull the icon by setting its tint to gray
                    faditorXItem.setIconTintList(android.content.res.ColorStateList.valueOf(0xFFAAAAAA));
                }

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

            popupMenu.setOnMenuItemClickListener(menuItem -> {
                int id = menuItem.getItemId();
                if (id == R.id.action_edit_faditorx) {
                    Toast.makeText(context, context.getString(R.string.remote_toast_coming_soon), Toast.LENGTH_SHORT).show();
                    return true;
                }
                if (id == R.id.action_rename) {
                    showRenameDialog(videoItem); // Rename still allowed
                    return true;
                } else if (id == R.id.action_delete) {
                    confirmDelete(videoItem);
                    return true;
                } else if (id == R.id.action_save) {
                    // *** START: Progress Handling for Save ***
                    final String filename = videoItem.displayName; // Get name for messages
                    final Uri uriToSave = videoItem.uri;

                    // Notify fragment that save is starting
                    actionListener.onSaveToGalleryStarted(filename);

                    // Run actual save operation in the background
                    executorService.submit(() -> {
                        Uri resultUri = null; // To store the result URI

                        if ("file".equals(uriToSave.getScheme())) {
                            resultUri = saveFileUriToGallery(uriToSave);
                        } else if ("content".equals(uriToSave.getScheme())) {
                            resultUri = saveContentUriToGallery(uriToSave);
                        } else {
                            Log.w(TAG, "Unsupported URI scheme for saving to gallery: " + uriToSave.getScheme());
                        }

                        final boolean success = resultUri != null;
                        final Uri finalResultUri = resultUri; // Effectively final for lambda

                        // Notify fragment of completion on the main thread
                        if(context instanceof Activity){ // Check context type
                            ((Activity) context).runOnUiThread(() -> {
                                String message = success ? context.getString(R.string.toast_video_saved) + " (Downloads/FadCam)"
                                        : context.getString(R.string.toast_video_save_fail);
                                actionListener.onSaveToGalleryFinished(success, message, finalResultUri);
                            });
                        } else {
                            Log.e(TAG, "Context is not an Activity, cannot run on UI thread for save completion.");
                            // Handle this case if necessary - maybe use a Handler with Looper.getMainLooper()
                            new Handler(Looper.getMainLooper()).post(() -> {
                                String message = success ? "Video Saved (Downloads/FadCam)" : "Save Failed";
                                actionListener.onSaveToGalleryFinished(success, message, finalResultUri);
                            });
                        }
                    });
                    // *** END: Progress Handling for Save ***
                    return true;
                } else if (id == R.id.action_info) {
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

    // Inside RecordsAdapter.java

    /**
     * Shows confirmation dialog and handles deletion for a single item.
     * @param videoItem The item to potentially delete.
     */
    private void confirmDelete(VideoItem videoItem) {
        if(context == null || videoItem == null || videoItem.uri == null) return;

        new MaterialAlertDialogBuilder(context)
                .setTitle(context.getString(R.string.dialog_del_title))
                .setMessage(context.getString(R.string.dialog_del_notice) + "\n(" + videoItem.displayName + ")")
                .setNegativeButton(context.getString(R.string.universal_cancel), null)
                .setPositiveButton(context.getString(R.string.dialog_del_confirm), (dialog, which) -> {
                    executorService.submit(() -> { // Perform deletion off the main thread
                        boolean deleted = deleteVideoUri(videoItem.uri);

                        // Update UI back on the main thread
                        new Handler(Looper.getMainLooper()).post(() -> {
                            if (deleted) {
                                int position = findPositionByUri(videoItem.uri); // Find position *before* removing
                                if (position != -1) {
                                    if (position < records.size()){ // Bounds check before removing from adapter's list
                                        Log.d(TAG, "confirmDelete: Removing item from adapter list at pos " + position);
                                        records.remove(position);
                                        notifyItemRemoved(position); // Notify adapter about the removal
                                        // Maybe notify range changed if serial numbers matter, but can cause flicker
                                        // notifyItemRangeChanged(position, getItemCount());
                                    } else {
                                        Log.e(TAG, "confirmDelete: Deletion OK, but pos "+position+" out of bounds. Size="+records.size());
                                        notifyDataSetChanged(); // Fallback full refresh
                                    }

                                    // Remove from the fragment's selection list IF it was selected
                                    selectedVideosUris.remove(videoItem.uri);
                                    // *** REMOVE THIS LINE - Adapter doesn't control Fragment's FAB ***
                                    // updateDeleteButtonVisibility();

                                    // Signal fragment to check if the list became empty
                                    if (actionListener != null) {
                                        actionListener.onDeletionFinishedCheckEmptyState();
                                        Log.d(TAG, "Called onDeletionFinishedCheckEmptyState listener.");
                                    }

                                } else {
                                    // Item deleted but not found in list? List might have changed. Force refresh.
                                    Log.w(TAG, "Item deleted from storage, but not found in adapter list (URI: " + videoItem.uri+"). Refreshing.");
                                    notifyDataSetChanged();
                                    if (actionListener != null) {
                                        actionListener.onDeletionFinishedCheckEmptyState();
                                    }
                                }
                            } else {
                                // Deletion failed
                                if(context!=null) Toast.makeText(context, "Failed to delete video.", Toast.LENGTH_SHORT).show();
                            }
                        }); // End runOnUiThread
                    }); // End submit
                })
                .show();
    } // End confirmDelete


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


    // Method for saving internal files (file:// URI) to gallery -> Downloads/FadCam
// Changed return type to Uri (or null on failure)
    private Uri saveFileUriToGallery(Uri fileUri) {
        // ... (checks for context, fileUri, videoFile existence) ...
        if (context == null || fileUri == null) return null;
        File videoFile = new File(fileUri.getPath());
        if (!videoFile.exists()){ return null; } // Return null if file doesn't exist

        ContentResolver resolver = context.getContentResolver();
        ContentValues values = new ContentValues();
        // ... (set values for DISPLAY_NAME, MIME_TYPE, RELATIVE_PATH (to Downloads/FadCam)) ...
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, videoFile.getName());
        values.put(MediaStore.MediaColumns.MIME_TYPE, "video/" + Constants.RECORDING_FILE_EXTENSION);

        Uri collection;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + File.separator + Constants.RECORDING_DIRECTORY);
            collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
        } else {
            // Handle legacy - NOTE: This section NEEDS WRITE_EXTERNAL_STORAGE
            File publicDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            if (publicDir == null) { Log.e(TAG, "Downloads directory null (pre-Q)"); return null; }
            File fadCamPublicDir = new File(publicDir, Constants.RECORDING_DIRECTORY);
            if (!fadCamPublicDir.exists() && !fadCamPublicDir.mkdirs()) { Log.e(TAG, "Failed create Downloads/FadCam (pre-Q)"); return null; }
            File destFile = new File(fadCamPublicDir, videoFile.getName());
            values.put(MediaStore.MediaColumns.DATA, destFile.getAbsolutePath()); // For MediaScanner
            collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI;

            try (InputStream in = new FileInputStream(videoFile); OutputStream out = new FileOutputStream(destFile)) {
                byte[] buf = new byte[8192]; int len;
                while ((len = in.read(buf)) > 0) { out.write(buf, 0, len); }
                MediaScannerConnection.scanFile(context, new String[]{destFile.getAbsolutePath()}, new String[]{values.getAsString(MediaStore.MediaColumns.MIME_TYPE)}, null);
                Log.i(TAG,"Saved legacy file to Downloads: " + destFile.getPath());
                // Attempt metadata insert, ignore failure if direct write succeeded
                try { resolver.insert(collection, values); } catch (Exception metaE){ Log.w(TAG,"Failed meta insert pre-Q", metaE);}
                return Uri.fromFile(destFile); // Return the file URI of the saved copy
            } catch (IOException e) {
                Log.e(TAG,"Error during legacy file copy to Downloads", e);
                if(destFile.exists()) destFile.delete(); // Clean up partial copy
                return null; // Return null on failure
            }
        }

        // --- Stream copy for API 29+ ---
        Uri itemUri = null;
        OutputStream out = null;
        InputStream in = null;
        try {
            itemUri = resolver.insert(collection, values);
            if (itemUri == null) throw new IOException("Failed to create MediaStore entry (Downloads API)");

            out = resolver.openOutputStream(itemUri);
            in = new FileInputStream(videoFile);

            if (out == null || in == null) throw new IOException("Failed to open streams for MediaStore Downloads");

            byte[] buf = new byte[8192];
            int len;
            while ((len = in.read(buf)) > 0) { out.write(buf, 0, len); }
            // REMOVED Toast from here
            Log.i(TAG, "Saved file:// URI to Downloads: " + itemUri);
            return itemUri; // Return the new content URI on success

        } catch (Exception e) {
            // REMOVED Toast from here
            Log.e(TAG, "Failed to save file URI to Downloads", e);
            if (itemUri != null) { try { resolver.delete(itemUri, null, null); } catch (Exception ignored) {} }
            return null; // Return null on failure
        } finally {
            // Close streams...
            try { if (in != null) in.close(); } catch (IOException ignored) {}
            try { if (out != null) out.close(); } catch (IOException ignored) {}
        }
    }


    // Method for saving SAF files (content:// URI) to gallery -> Downloads/FadCam
// Changed return type to Uri (or null on failure)
    private Uri saveContentUriToGallery(Uri sourceUri) {
        // ... (checks for context, sourceUri) ...
        if (context == null || sourceUri == null) return null;
        ContentResolver resolver = context.getContentResolver();
        String displayName = getFileName(sourceUri);
        // ... (fallback for displayName) ...
        if (displayName == null) displayName = "FadCam_Video_" + System.currentTimeMillis() + "." + Constants.RECORDING_FILE_EXTENSION;

        ContentValues values = new ContentValues();
        // ... (set DISPLAY_NAME, MIME_TYPE) ...
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, displayName);
        values.put(MediaStore.MediaColumns.MIME_TYPE, "video/" + Constants.RECORDING_FILE_EXTENSION);

        Uri collection;
        // API 29+ using Downloads collection
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + File.separator + Constants.RECORDING_DIRECTORY);
            collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
        }
        // Pre-Q Handling (temp file copy)
        else {
            Log.w(TAG, "Using temp file strategy for pre-Q Content URI -> Public Downloads");
            File tempInternalFile = new File(context.getCacheDir(), "temp_gallery_save_" + System.currentTimeMillis() + ".mp4");
            try (InputStream inStream = resolver.openInputStream(sourceUri);
                 OutputStream outStream = new FileOutputStream(tempInternalFile)) {
                if(inStream == null) throw new IOException("Could not open input stream from source URI");
                byte[] buf = new byte[8192]; int len;
                while ((len = inStream.read(buf)) > 0) { outStream.write(buf, 0, len); }

                // Call the file save method - its return value is what we need
                Uri savedFileUri = saveFileUriToGallery(Uri.fromFile(tempInternalFile));
                return savedFileUri; // Return result of the legacy save call

            } catch (IOException e) {
                Log.e(TAG,"Error copying content URI to temp file for legacy save", e);
                return null; // Indicate failure
            } finally {
                if(tempInternalFile.exists()) tempInternalFile.delete();
            }
            // End pre-Q handling here
        }

        // --- Stream copy for API 29+ using MediaStore.Downloads ---
        Uri itemUri = null;
        OutputStream out = null;
        InputStream in = null;
        try {
            itemUri = resolver.insert(collection, values);
            if (itemUri == null) throw new IOException("Failed to create MediaStore entry (Downloads API) for content URI");

            out = resolver.openOutputStream(itemUri);
            in = resolver.openInputStream(sourceUri);

            if (out == null || in == null) throw new IOException("Failed to open streams for content URI save to Downloads");

            byte[] buf = new byte[8192];
            int len;
            while ((len = in.read(buf)) > 0) { out.write(buf, 0, len); }
            // REMOVED Toast
            Log.i(TAG, "Saved content:// URI to Downloads: " + itemUri);
            return itemUri; // Return new URI on success

        } catch (Exception e) {
            // REMOVED Toast
            Log.e(TAG, "Failed to save content URI to Downloads. URI: " + sourceUri, e);
            if (itemUri != null) { try { resolver.delete(itemUri, null, null); } catch (Exception ignored) {} }
            return null; // Return null on failure
        } finally {
            // Close streams...
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