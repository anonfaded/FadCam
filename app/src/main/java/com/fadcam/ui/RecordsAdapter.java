package com.fadcam.ui;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.res.ColorStateList;
import android.database.Cursor;
import android.graphics.Color;

import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.text.SpannableString;
import android.text.style.ForegroundColorSpan;
import android.util.Log;
import android.util.SparseArray;
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
// For getting drawables

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.request.RequestOptions;
import com.fadcam.Constants;
import com.fadcam.R;
// Ensure VideoItem import is correct
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import com.fadcam.Utils; // Import Utils for the new formatter

import java.io.File;
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

import android.graphics.PorterDuff;
import android.graphics.drawable.Drawable;
import androidx.recyclerview.widget.DiffUtil;
import android.graphics.drawable.GradientDrawable;

// Modify the class declaration to remove the ListPreloader implementation
public class RecordsAdapter extends RecyclerView.Adapter<RecordsAdapter.RecordViewHolder> {

    // Keep the cache for thumbnails but optimize it
    private final SparseArray<String> loadedThumbnailCache = new SparseArray<>();
    private static final int THUMBNAIL_SIZE = 200; // Standard size for all thumbnails
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
    // Add field to track scrolling state
    private boolean isScrolling = false;
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

    // ----- Fix Start: Add isSnowVeilTheme flag and method to set it -----
    private boolean isSnowVeilTheme = false;

    /**
     * Set whether we're currently using the Snow Veil theme
     * This allows special styling for cards in the Snow Veil theme
     */
    public void setSnowVeilTheme(boolean isSnowVeilTheme) {
        if (this.isSnowVeilTheme != isSnowVeilTheme) {
            this.isSnowVeilTheme = isSnowVeilTheme;
            // Need to refresh all items when theme changes
            notifyDataSetChanged();
        }
    }
    // ----- Fix End: Add isSnowVeilTheme flag and method to set it -----

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

    // Optimize onBindViewHolder to reduce work on the UI thread
    @Override
    public void onBindViewHolder(@NonNull RecordViewHolder holder, int position) {
        // --- 1. Basic Checks & Get Data ---
        if (records == null || position < 0 || position >= records.size() || records.get(position) == null || records.get(position).uri == null) {
            Log.e(TAG,"onBindViewHolder: Invalid item/data at position " + position);
            // Optionally clear the views in the holder to avoid displaying stale data
            return;
        }
        final VideoItem videoItem = records.get(position);
        final Uri videoUri = videoItem.uri;
        final String displayName = videoItem.displayName != null ? videoItem.displayName : "Unnamed Video";
        final String uriString = videoUri.toString();

        // --- 2. Determine Item States ---
        final boolean isCurrentlySelected = this.currentSelectedUris.contains(videoUri);
        final boolean isProcessing = this.currentlyProcessingUris.contains(videoUri);
        final boolean isTemp = isTemporaryFile(videoItem);
        final boolean isOpened = sharedPreferencesManager.getOpenedVideoUris().contains(uriString);
        final boolean showNewBadge = !isTemp && !isOpened && !isProcessing;
        final boolean allowGeneralInteractions = !isProcessing;
        final boolean allowMenuClick = allowGeneralInteractions && !this.isSelectionModeActive;

        // Only log for debugging specific positions to reduce spam
        if (position < 3 || position % 20 == 0) {
            Log.v(TAG,"onBindViewHolder Pos "+position+": Name="+displayName);
        }

        // --- 3. Bind Standard Data, optimized for fewer UI operations ---
        if (holder.textViewSerialNumber != null) holder.textViewSerialNumber.setText(String.valueOf(position + 1));
        if (holder.textViewRecord != null) holder.textViewRecord.setText(displayName);
        if (holder.textViewFileSize != null) holder.textViewFileSize.setText(formatFileSize(videoItem.size));
        
        // ----- Fix Start: Apply Snow Veil theme card colors -----
        // Apply proper background and text colors based on theme
        if (isSnowVeilTheme) {
            // For Snow Veil theme, ALWAYS use white card background with black text for maximum contrast
            if (holder.itemView instanceof CardView) {
                CardView cardView = (CardView) holder.itemView;
                // Force pure white background for all cards in Snow Veil theme
                cardView.setCardBackgroundColor(Color.WHITE);
                
                // Log for debugging
                Log.d(TAG, "Setting WHITE card background for Snow Veil theme at position " + position);
            }
            
            // Apply black tint to the three-dot menu icon for better contrast on white background
            if (holder.menuButton != null) {
                holder.menuButton.setColorFilter(Color.BLACK, PorterDuff.Mode.SRC_IN);
            }
            
            // Ensure file time and size in the overlay have good contrast
            if (holder.textViewFileSize != null) {
                holder.textViewFileSize.setTextColor(Color.WHITE);
            }
            if (holder.textViewFileTime != null) {
                holder.textViewFileTime.setTextColor(Color.WHITE);
            }
        } else {
            // For other themes, use the default background color
            if (holder.itemView instanceof CardView && context != null) {
                CardView cardView = (CardView) holder.itemView;
                cardView.setCardBackgroundColor(ContextCompat.getColor(context, R.color.gray));
                
                // Log for debugging
                Log.d(TAG, "Setting GRAY card background for other theme at position " + position);
            }
            
            // Clear any tint for other themes
            if (holder.menuButton != null) {
                holder.menuButton.clearColorFilter();
            }
            
            // Leave text colors as default for other themes
            if (holder.textViewRecord != null) {
                holder.textViewRecord.setTextColor(holder.defaultTextColor);
            }
            if (holder.textViewTimeAgo != null) {
                holder.textViewTimeAgo.setTextColor(holder.defaultTextColor);
            }
        }
        // ----- Fix End: Apply Snow Veil theme card colors -----
        
        // Optimize time-consuming operations using lightweight caching
        if (holder.textViewFileTime != null) {
            // Check if we already have the duration cached
            String cachedDuration = loadedThumbnailCache.get(position);
            if (cachedDuration != null) {
                holder.textViewFileTime.setText(cachedDuration);
            } else {
                // Show a placeholder while loading
                holder.textViewFileTime.setText("--:--");
                
                // Calculate duration on background thread - this is one of the main causes of lag
                executorService.execute(() -> {
                    long duration = getVideoDuration(videoUri);
                    String formattedDuration = formatVideoDuration(duration);
                    loadedThumbnailCache.put(position, formattedDuration);
                    
                    // Update UI on main thread
                    new Handler(Looper.getMainLooper()).post(() -> {
                        // Make sure the view holder is still showing the same item before updating
                        if (holder.getAdapterPosition() == position && holder.textViewFileTime != null) {
                            holder.textViewFileTime.setText(formattedDuration);
                        }
                    });
                });
            }
        }
        
        if (holder.textViewTimeAgo != null) holder.textViewTimeAgo.setText(Utils.formatTimeAgo(videoItem.lastModified));

        // Only set the thumbnail if holder view is visible (important optimization)
        if (holder.imageViewThumbnail != null && holder.itemView.getVisibility() == View.VISIBLE) {
            setThumbnail(holder, videoUri);
        }

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
                    if(holder.itemView instanceof CardView && context!=null) {
                        if (isSnowVeilTheme) {
                            // For Snow Veil, use a light blue highlight with black text
                            ((CardView)holder.itemView).setCardBackgroundColor(ContextCompat.getColor(context, R.color.snowveil_theme_accent));
                            if(holder.textViewRecord != null) holder.textViewRecord.setTextColor(Color.BLACK);
                        } else {
                            // For other themes, use normal selection color with white text
                            ((CardView)holder.itemView).setCardBackgroundColor(resolveThemeColor(context, R.attr.colorButton));
                            if(holder.textViewRecord != null) holder.textViewRecord.setTextColor(Color.WHITE);
                        }
                    }
                } else {
                    holder.checkIcon.setImageResource(R.drawable.placeholder_checkbox_outline); // Replace with actual drawable
                    holder.checkIcon.setAlpha(0.7f);
                    // Reset background and text color
                    if(holder.itemView instanceof CardView && context!=null) {
                        if (isSnowVeilTheme) {
                            ((CardView)holder.itemView).setCardBackgroundColor(Color.WHITE);
                            if(holder.textViewRecord != null) holder.textViewRecord.setTextColor(Color.BLACK);
                        } else {
                            ((CardView)holder.itemView).setCardBackgroundColor(ContextCompat.getColor(context, R.color.gray));
                            if(holder.textViewRecord != null) holder.textViewRecord.setTextColor(holder.defaultTextColor);
                        }
                    }
                }
            } else { // Not in selection mode
                holder.checkIcon.setVisibility(View.GONE);
                // Ensure default background and text color are restored
                if(holder.itemView instanceof CardView && context!=null) {
                    if (isSnowVeilTheme) {
                        ((CardView)holder.itemView).setCardBackgroundColor(Color.WHITE);
                        if(holder.textViewRecord != null) holder.textViewRecord.setTextColor(Color.BLACK);
                    } else {
                        ((CardView)holder.itemView).setCardBackgroundColor(ContextCompat.getColor(context, R.color.gray));
                        if(holder.textViewRecord != null) holder.textViewRecord.setTextColor(holder.defaultTextColor);
                    }
                }
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
                        // --- Fix Start: Robustly gray out FaditorX menu item and show badge ---
                        // Wait for the popup to be fully shown, then update the view
                        new Handler(Looper.getMainLooper()).postDelayed(() -> {
                            try {
                                Field listViewField = popup.getClass().getDeclaredField("mPopup");
                                listViewField.setAccessible(true);
                                Object menuPopupHelper = listViewField.get(popup);
                                Method getListViewMethod = menuPopupHelper.getClass().getDeclaredMethod("getListView");
                                getListViewMethod.setAccessible(true);
                                android.widget.ListView listView = (android.widget.ListView) getListViewMethod.invoke(menuPopupHelper);
                                if (listView != null) {
                                    for (int i = 0; i < listView.getChildCount(); i++) {
                                        View row = listView.getChildAt(i);
                                        TextView label = row.findViewById(R.id.menu_edit_label);
                                        TextView badge = row.findViewById(R.id.menu_badge_coming_soon);
                                        ImageView icon = row.findViewById(android.R.id.icon);
                                        if (label != null && badge != null) {
                                            label.setTextColor(Color.parseColor("#888888"));
                                            badge.setVisibility(View.VISIBLE);
                                            badge.setText("Coming Soon");
                                            badge.setBackgroundResource(R.drawable.badge_background_red);
                                            if (icon != null) {
                                                icon.setColorFilter(Color.parseColor("#888888"), android.graphics.PorterDuff.Mode.SRC_IN);
                                            }
                                            break; // Found the correct row
                                        }
                                    }
                                }
                            } catch (Exception e) {
                                Log.w(TAG, "Could not update FaditorX menu item badge: " + e.getMessage());
                            }
                        }, 50); // Delay to ensure popup is rendered
                        // --- Fix End: Robustly gray out FaditorX menu item and show badge ---
                    }
                }
            });
        }

    } // End onBindViewHolder

    @Override
    public int getItemCount() {
        return records == null ? 0 : records.size();
    }

    // Update the setThumbnail method to consider scrolling state
    private void setThumbnail(RecordViewHolder holder, Uri videoUri) {
        if (holder.imageViewThumbnail == null || context == null) return;
        
        // Lower resolution during scrolling for performance
        int thumbnailSize = isScrolling ? 100 : THUMBNAIL_SIZE;
        
        // Create optimized request options with different strategies based on scrolling
        RequestOptions options = new RequestOptions()
            .diskCacheStrategy(DiskCacheStrategy.ALL)
            .skipMemoryCache(false)
            .centerCrop()
            .override(thumbnailSize, thumbnailSize)
            .placeholder(R.drawable.ic_video_placeholder);
        
        if (isScrolling) {
            // During scrolling, use low-quality thumbnails for speed
            options = options.dontAnimate();
        }
        
        // Implement loading with scroll-aware options
        Glide.with(context)
                .load(videoUri)
            .apply(options)
            .thumbnail(0.1f) // Use a small thumbnail first for faster initial loading
                .into(holder.imageViewThumbnail);
    }

    // Override onViewRecycled to cancel thumbnail loading for recycled views
    @Override
    public void onViewRecycled(@NonNull RecordViewHolder holder) {
        super.onViewRecycled(holder);
        
        // Cancel any pending image loads when view is recycled
        if (holder.imageViewThumbnail != null && context != null) {
            Glide.with(context).clear(holder.imageViewThumbnail);
        }
    }

    // Override onBindViewHolder to handle payload for quality changes
    @Override
    public void onBindViewHolder(@NonNull RecordViewHolder holder, int position, @NonNull List<Object> payloads) {
        if (!payloads.isEmpty()) {
            if (payloads.contains("QUALITY_CHANGE")) {
                // Only update thumbnail when scrolling stops
                if (holder.imageViewThumbnail != null && position < records.size()) {
                    VideoItem videoItem = records.get(position);
                    if (videoItem != null && videoItem.uri != null) {
                        setThumbnail(holder, videoItem.uri);
                    }
                }
                return;
            }
        }
        // If no specific payload, do a full bind
        onBindViewHolder(holder, position);
        
        // EMERGENCY FIX: Force apply Snow Veil card color after normal binding
        if (isSnowVeilTheme && holder.itemView instanceof CardView) {
            ((CardView)holder.itemView).setCardBackgroundColor(Color.WHITE);
            Log.d(TAG, "🔴 EMERGENCY: Forced WHITE card for Snow Veil at position " + position);
            
            // Force black text on all text elements
            if (holder.textViewRecord != null) holder.textViewRecord.setTextColor(Color.BLACK);
            if (holder.textViewTimeAgo != null) holder.textViewTimeAgo.setTextColor(Color.BLACK);
        }
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
    private int resolveThemeColor(Context context, int attr) {
        android.util.TypedValue typedValue = new android.util.TypedValue();
        context.getTheme().resolveAttribute(attr, typedValue, true);
        return typedValue.data;
    }

    private PopupMenu setupPopupMenu(RecordViewHolder holder, VideoItem videoItem) {
        Context context = holder.itemView.getContext();
        int popupMenuStyle = 0;
        // Dynamically select the correct style for the current theme
        SharedPreferencesManager spm = SharedPreferencesManager.getInstance(context);
        String currentTheme = spm.sharedPreferences.getString(Constants.PREF_APP_THEME, Constants.DEFAULT_APP_THEME);
        if ("Crimson Bloom".equals(currentTheme)) {
            popupMenuStyle = R.style.Widget_FadCam_Red_PopupMenu; // Use underscore, not dot
        } else if ("Faded Night".equals(currentTheme)) {
            // If you have a custom style for AMOLED, set it here
            // popupMenuStyle = R.style.Widget_FadCam_Amoled_PopupMenu;
            popupMenuStyle = 0; // fallback to default
        }
        PopupMenu popup = (popupMenuStyle != 0)
                ? new PopupMenu(context, holder.menuButtonContainer, 0, 0, popupMenuStyle)
                : new PopupMenu(context, holder.menuButtonContainer);
        popup.getMenuInflater().inflate(R.menu.video_item_menu, popup.getMenu());

        // Set text color for all menu items
        int colorMenuText;
        if ("Crimson Bloom".equals(currentTheme)) {
            colorMenuText = ContextCompat.getColor(context, R.color.white);
        } else if ("Faded Night".equals(currentTheme)) {
            colorMenuText = ContextCompat.getColor(context, R.color.amoled_text_primary);
        } else {
            colorMenuText = resolveThemeColor(context, R.attr.colorHeading);
        }
        for (int i = 0; i < popup.getMenu().size(); i++) {
            MenuItem item = popup.getMenu().getItem(i);
            SpannableString spanString = new SpannableString(item.getTitle());
            spanString.setSpan(new ForegroundColorSpan(colorMenuText), 0, spanString.length(), 0);
            item.setTitle(spanString);
        }
        // Optionally force show icons (reflection, but safe fallback)
        try {
            java.lang.reflect.Field mPopupField = popup.getClass().getDeclaredField("mPopup");
            mPopupField.setAccessible(true);
            Object menuPopupHelper = mPopupField.get(popup);
            java.lang.reflect.Method setForceShowIcon = menuPopupHelper.getClass().getMethod("setForceShowIcon", boolean.class);
            setForceShowIcon.invoke(menuPopupHelper, true);
        } catch (Exception e) {
            Log.w(TAG, "Could not force show popup menu icons: " + e.getMessage());
        }
        // Handle all menu actions
        popup.setOnMenuItemClickListener(item -> {
            int id = item.getItemId();
            if (id == R.id.action_edit_faditorx) {
                Toast.makeText(context, R.string.remote_toast_coming_soon, Toast.LENGTH_SHORT).show();
                return true;
            } else if (id == R.id.action_save) {
                saveVideoToGalleryInternal(videoItem);
                return true;
            } else if (id == R.id.action_rename) {
                showRenameDialog(videoItem);
                return true;
            } else if (id == R.id.action_info) {
                showVideoInfoDialog(videoItem);
                return true;
            } else if (id == R.id.action_delete) {
                if (actionListener != null) actionListener.onDeleteVideo(videoItem);
                return true;
            }
            return false;
        });

        // After inflating the menu, update the 'Edit with FaditorX' item to show 'Coming Soon' and gray out
        MenuItem faditorxItem = popup.getMenu().findItem(R.id.action_edit_faditorx);
        if (faditorxItem != null) {
            // Append badge text
            String baseTitle = context.getString(R.string.edit_with_faditorx);
            String badge = "  [Coming Soon]";
            SpannableString spanString = new SpannableString(baseTitle + badge);
            // Gray out the whole text
            spanString.setSpan(new ForegroundColorSpan(Color.GRAY), 0, spanString.length(), 0);
            faditorxItem.setTitle(spanString);
            // Gray out the icon
            Drawable icon = faditorxItem.getIcon();
            if (icon != null) {
                icon.mutate();
                icon.setColorFilter(Color.GRAY, PorterDuff.Mode.SRC_IN);
                faditorxItem.setIcon(icon);
            }
            // Do NOT disable the item, so it can show the toast
        }

        return popup;
    }

    // --- Restored Rename Logic ---
    private void showRenameDialog(VideoItem videoItem) {
        if (context == null) return;
        MaterialAlertDialogBuilder builder = themedDialogBuilder(context);
        builder.setTitle(R.string.rename_video_title);

        View dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_rename, null);
        final TextInputEditText input = dialogView.findViewById(R.id.edit_text_name);
        
        // Find TextInputLayout (parent of the EditText)
        com.google.android.material.textfield.TextInputLayout inputLayout = null;
        try {
            if (input != null && input.getParent() != null && input.getParent().getParent() instanceof com.google.android.material.textfield.TextInputLayout) {
                inputLayout = (com.google.android.material.textfield.TextInputLayout) input.getParent().getParent();
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to get TextInputLayout parent", e);
        }

        String currentName = videoItem.displayName;
        int dotIndex = currentName.lastIndexOf(".");
        String baseName = (dotIndex > 0) ? currentName.substring(0, dotIndex) : currentName;
        input.setText(baseName);
        
        // Get current theme for custom styling
        String currentTheme = sharedPreferencesManager.sharedPreferences.getString(Constants.PREF_APP_THEME, Constants.DEFAULT_APP_THEME);
        boolean isFadedNightTheme = "Faded Night".equals(currentTheme);
        
        // For Faded Night theme: set white hint text color and cursor
        if (isFadedNightTheme) {
            // Set white hint text color in both TextInputLayout and EditText
            if (inputLayout != null) {
                // If using TextInputLayout, set its hint color
                inputLayout.setHintTextColor(ColorStateList.valueOf(Color.WHITE));
                inputLayout.setDefaultHintTextColor(ColorStateList.valueOf(Color.WHITE));
                
                // Also customize the box stroke color if using outlined style
                inputLayout.setBoxStrokeColor(Color.WHITE);
            }
            
            if (input != null) {
                // Set text and hint colors directly on EditText
                input.setTextColor(Color.WHITE);
                input.setHintTextColor(Color.WHITE);
                
                // Force set hint directly
                input.setHint(R.string.rename_video_hint);
                
                // Try to set the cursor color using tinting (API 29+)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    input.setTextCursorDrawable(R.drawable.white_cursor);
                } else {
                    // Try reflection approach for older devices
                    try {
                        // First try mCursorDrawableRes field
                        Field fCursorDrawableRes = TextView.class.getDeclaredField("mCursorDrawableRes");
                        fCursorDrawableRes.setAccessible(true);
                        
                        // Create a drawable shape for cursor
                        GradientDrawable cursorDrawable = new GradientDrawable();
                        cursorDrawable.setColor(Color.WHITE);
                        cursorDrawable.setSize(2, input.getLineHeight());
                        
                        // Different approaches based on Android version
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                            // For Android 9+
                            fCursorDrawableRes.set(input, 0); // Clear existing cursor
                            
                            Field fEditor = TextView.class.getDeclaredField("mEditor");
                            fEditor.setAccessible(true);
                            Object editor = fEditor.get(input);
                            
                            Field fCursorDrawable = editor.getClass().getDeclaredField("mDrawableForCursor");
                            fCursorDrawable.setAccessible(true);
                            fCursorDrawable.set(editor, cursorDrawable);
                        } else {
                            // For older Android versions
                            fCursorDrawableRes.set(input, 0); // Clear existing cursor
                            
                            Field fEditor = TextView.class.getDeclaredField("mEditor");
                            fEditor.setAccessible(true);
                            Object editor = fEditor.get(input);
                            
                            Field fCursorDrawable = editor.getClass().getDeclaredField("mCursorDrawable");
                            fCursorDrawable.setAccessible(true);
                            Drawable[] drawables = new Drawable[2];
                            drawables[0] = cursorDrawable;
                            drawables[1] = cursorDrawable;
                            fCursorDrawable.set(editor, drawables);
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Failed to set cursor color", e);
                    }
                }
                
                // Additional method to try forcing the cursor color
                try {
                    // Use a blue-ish highlight color instead of white for better contrast
                    // when text is selected (since text is white)
                    input.setHighlightColor(Color.parseColor("#335588"));
                } catch (Exception e) {
                    Log.e(TAG, "Failed to set highlight color", e);
                }
                
                // Force white background tint as well
                input.setBackgroundTintList(ColorStateList.valueOf(Color.WHITE));
            }
        }

        builder.setView(dialogView);
        builder.setPositiveButton(R.string.universal_ok, (dialog, which) -> { 
            // First hide the keyboard and clear focus
            try {
                if (input != null) {
                    input.clearFocus();
                    
                    android.view.inputmethod.InputMethodManager imm = 
                        (android.view.inputmethod.InputMethodManager) context.getSystemService(Context.INPUT_METHOD_SERVICE);
                    if (imm != null) {
                        imm.hideSoftInputFromWindow(input.getWindowToken(), 0);
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Error clearing focus on OK press", e);
            }
            
            // Then proceed with the rename operation
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
        
        // Create the dialog and show it
        androidx.appcompat.app.AlertDialog dialog = builder.create();
        
        // Force show keyboard when dialog appears
        dialog.setOnShowListener(dialogInterface -> {
            if (input != null) {
                input.requestFocus();
                input.selectAll();
                
                // Use a slight delay to ensure the view is ready
                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    try {
                        android.view.inputmethod.InputMethodManager imm = 
                            (android.view.inputmethod.InputMethodManager) context.getSystemService(Context.INPUT_METHOD_SERVICE);
                        if (imm != null) {
                            // Try to force show the keyboard with SHOW_FORCED flag
                            imm.toggleSoftInput(android.view.inputmethod.InputMethodManager.SHOW_FORCED, 0);
                            // Also try the showSoftInput method with SHOW_FORCED
                            imm.showSoftInput(input, android.view.inputmethod.InputMethodManager.SHOW_FORCED);
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Failed to show keyboard", e);
                    }
                }, 200); // 200ms delay
            }
        });
        
        // Clear focus and hide keyboard when dialog is dismissed
        dialog.setOnDismissListener(dialogInterface -> {
            try {
                // Clear focus
                if (input != null) {
                    input.clearFocus();
                }
                
                // Hide keyboard
                android.view.inputmethod.InputMethodManager imm = 
                    (android.view.inputmethod.InputMethodManager) context.getSystemService(Context.INPUT_METHOD_SERVICE);
                if (imm != null && input != null) {
                    imm.hideSoftInputFromWindow(input.getWindowToken(), 0);
                }
            } catch (Exception e) {
                Log.e(TAG, "Error clearing focus or hiding keyboard", e);
            }
        });
        
        dialog.show();
        
        // Apply theme-specific button colors after dialog is shown
        setSnowVeilButtonColors(dialog);
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
        MaterialAlertDialogBuilder builder = themedDialogBuilder(context);
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
                .setPositiveButton("Close", (dialog, which) -> dialog.dismiss());
                
        // Create and show the dialog
        androidx.appcompat.app.AlertDialog dialog = builder.create();
        dialog.show();
        
        // Apply Snow Veil button colors after dialog is shown
        setSnowVeilButtonColors(dialog);
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

    /**
     * Sets the scrolling state to optimize thumbnail loading
     * @param scrolling true if the list is currently scrolling, false otherwise
     */
    public void setScrolling(boolean scrolling) {
        this.isScrolling = scrolling;
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

    /**
     * Clears all internal caches to reduce memory footprint
     * Should be called on low memory conditions
     */
    public void clearCaches() {
        loadedThumbnailCache.clear();
    }

    // For dialogs, use themed MaterialAlertDialogBuilder as in SettingsFragment
    private MaterialAlertDialogBuilder themedDialogBuilder(Context context) {
        int dialogTheme = R.style.ThemeOverlay_FadCam_Dialog;
        SharedPreferencesManager spm = SharedPreferencesManager.getInstance(context);
        String currentTheme = spm.sharedPreferences.getString(Constants.PREF_APP_THEME, Constants.DEFAULT_APP_THEME);
        if ("Crimson Bloom".equals(currentTheme)) dialogTheme = R.style.ThemeOverlay_FadCam_Red_Dialog;
        else if ("Faded Night".equals(currentTheme)) dialogTheme = R.style.ThemeOverlay_FadCam_Amoled_MaterialAlertDialog;
        else if ("Snow Veil".equals(currentTheme)) dialogTheme = R.style.ThemeOverlay_FadCam_SnowVeil_Dialog;
        return new MaterialAlertDialogBuilder(context, dialogTheme);
    }
    
    /**
     * Sets dialog button colors based on theme
     * @param dialog The dialog whose buttons need color adjustment
     */
    private void setSnowVeilButtonColors(androidx.appcompat.app.AlertDialog dialog) {
        if (dialog == null) return;
        
        // Check current theme
        String currentTheme = sharedPreferencesManager.sharedPreferences.getString(Constants.PREF_APP_THEME, Constants.DEFAULT_APP_THEME);
        boolean isSnowVeilTheme = "Snow Veil".equals(currentTheme);
        boolean isFadedNightTheme = "Faded Night".equals(currentTheme);
        
        if (isSnowVeilTheme) {
            // Set black text color for both positive and negative buttons
            if (dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE) != null) {
                dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setTextColor(Color.BLACK);
            }
            if (dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEGATIVE) != null) {
                dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEGATIVE).setTextColor(Color.BLACK);
            }
            if (dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEUTRAL) != null) {
                dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEUTRAL).setTextColor(Color.BLACK);
            }
        } else if (isFadedNightTheme) {
            // Set white text color for Faded Night theme buttons
            if (dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE) != null) {
                dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setTextColor(Color.WHITE);
            }
            if (dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEGATIVE) != null) {
                dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEGATIVE).setTextColor(Color.WHITE);
            }
            if (dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEUTRAL) != null) {
                dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEUTRAL).setTextColor(Color.WHITE);
            }
        }
    }
}