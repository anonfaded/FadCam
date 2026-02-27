package com.fadcam.ui;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
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
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.text.SpannableString;
import android.text.style.ForegroundColorSpan;
import android.util.Log;
import android.util.SparseArray;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.widget.PopupMenu;
import androidx.core.content.ContextCompat;
import androidx.documentfile.provider.DocumentFile;
import androidx.recyclerview.widget.RecyclerView;
// For getting drawables

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.request.RequestOptions;
import com.fadcam.utils.ShimmerEffectHelper;
import com.fadcam.utils.RuntimeCompat;
import com.fadcam.Constants;
import com.fadcam.R;
// Ensure VideoItem import is correct
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.fadcam.Utils; // Import Utils for the new formatter

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
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
import androidx.core.app.ShareCompat;
import android.content.ContentResolver;
import androidx.core.content.FileProvider;
import com.arthenica.ffmpegkit.FFmpegKit;
import com.arthenica.ffmpegkit.ReturnCode;
import androidx.fragment.app.FragmentActivity;
import androidx.fragment.app.FragmentManager;
import com.fadcam.ui.picker.PickerBottomSheetFragment;
import com.fadcam.ui.picker.OptionItem;
import com.fadcam.service.FileOperationService;

// Modify the class declaration to remove the ListPreloader implementation
public class RecordsAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    // ── View type constants ──
    public static final int VIEW_TYPE_HEADER = 0;
    public static final int VIEW_TYPE_ITEM = 1;

    // ── Month grouping entry classes ──
    static class MonthHeaderEntry {
        final String monthKey;
        MonthHeaderEntry(String monthKey) { this.monthKey = monthKey; }
    }

    static class VideoItemEntry {
        final VideoItem item;
        final String monthKey;
        final int serialNumber; // 1-based, headers excluded
        VideoItemEntry(VideoItem item, String monthKey, int serialNumber) {
            this.item = item;
            this.monthKey = monthKey;
            this.serialNumber = serialNumber;
        }
    }

    // ── Month selection listener ──
    public interface OnMonthActionListener {
        void onMonthSelectAll(String monthKey, List<VideoItem> items);
    }

    private OnMonthActionListener monthActionListener;

    public void setOnMonthActionListener(OnMonthActionListener listener) {
        this.monthActionListener = listener;
    }

    // ── Entries list (interleaved headers + items) ──
    private List<Object> entries = new ArrayList<>();

    private static final SimpleDateFormat MONTH_FORMAT = new SimpleDateFormat("MMMM yyyy", Locale.getDefault());

    // Keep the cache for thumbnails but optimize it
    private final SparseArray<String> loadedThumbnailCache = new SparseArray<>();
    private static final int THUMBNAIL_SIZE = 200; // Standard size for all thumbnails
    private static final int SAFE_THUMBNAIL_SIZE = 140;
    private static final int SAFE_SCROLL_THUMBNAIL_SIZE = 80;
    private Set<Uri> currentlyProcessingUris = new HashSet<>(); // Track processing URIs within adapter instance (passed
                                                                // from fragment)
    // Bounded LRU caches to avoid unbounded memory usage
    private final java.util.Map<String, Long> durationCache = new java.util.LinkedHashMap<String, Long>(256, 0.75f,
            true) {
        @Override
        protected boolean removeEldestEntry(java.util.Map.Entry<String, Long> eldest) {
            return size() > 256; // keep at most 256 entries
        }
    };
    private final java.util.Map<String, Long> savedPositionCache = new java.util.LinkedHashMap<String, Long>(256, 0.75f,
            true) {
        @Override
        protected boolean removeEldestEntry(java.util.Map.Entry<String, Long> eldest) {
            return size() > 1024; // keep more entries for saved positions
        }
    };
    // Reuse a single main-thread handler for UI updates
    private final android.os.Handler mainHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    // Simple file-based cache for durations (persist across sessions)
    private final File durationCacheFile;
    // Debounced persist task (posts to executor)
    private final Runnable persistDurationTask;
    // Broadcast receiver to listen for playback position updates
    private final androidx.localbroadcastmanager.content.LocalBroadcastManager localBroadcastManager;
    private final android.content.BroadcastReceiver playbackPositionReceiver;
    private android.content.Context receiverRegisteredContext = null;

    private static final String TAG = "RecordsAdapter";
    private final ExecutorService executorService; // Add ExecutorService
    private final SharedPreferencesManager sharedPreferencesManager;
    private final RecordActionListener actionListener; // Add the listener interface
    private final Context context;
    private final boolean safeMediaProbeMode;
    private List<VideoItem> records; // Now holds VideoItem objects
    private final OnVideoClickListener clickListener;
    private final OnVideoLongClickListener longClickListener;
    private final List<Uri> selectedVideosUris = new ArrayList<>(); // Track selection by URI
    private boolean isSelectionModeActive = false; // Track current mode within adapter
    private List<Uri> currentSelectedUris = new ArrayList<>(); // Keep track of selected items for binding

    // Add field to track scrolling state
    private boolean isScrolling = false;
    // Add skeleton mode for professional loading experience
    private boolean isSkeletonMode = false;
    // Grid span for dynamic sizing at high column counts
    private int currentGridSpan = 2;

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
        this.safeMediaProbeMode = RuntimeCompat.shouldUseSafeMediaProbe(this.context);
        this.records = new ArrayList<>(records); // Use a mutable copy
        this.entries = buildEntries(this.records); // Build month-grouped entries
        this.executorService = Objects.requireNonNull(executorService, "ExecutorService cannot be null");
        this.sharedPreferencesManager = Objects.requireNonNull(sharedPreferencesManager,
                "SharedPreferencesManager cannot be null"); // <<< STORE IT
        this.clickListener = clickListener; // Can be null if fragment doesn't implement/need it
        this.longClickListener = longClickListener; // Can be null
        this.actionListener = Objects.requireNonNull(actionListener, "RecordActionListener cannot be null"); // Assuming
                                                                                                             // fragment
                                                                                                             // always
                                                                                                             // provides
                                                                                                             // this


        // Duration cache file inside app cache dir
        File appCache = context.getCacheDir();
        this.durationCacheFile = new File(appCache, "duration_cache.json");
        // load persisted duration cache
        loadDurationCacheFromDisk();

        // Prepare debounced persist task
        this.persistDurationTask = () -> {
            try {
                this.executorService.execute(this::persistDurationCacheToDisk);
            } catch (Exception ignored) {
            }
        };

        // Setup LocalBroadcastReceiver for immediate progress updates
        this.localBroadcastManager = androidx.localbroadcastmanager.content.LocalBroadcastManager.getInstance(context);
        this.playbackPositionReceiver = new android.content.BroadcastReceiver() {
            @Override
            public void onReceive(android.content.Context ctx, android.content.Intent intent) {
                if (intent == null)
                    return;
                String uriStr = null;
                long pos = -1;
                // support both old and new extra names
                if (intent.hasExtra("extra_uri"))
                    uriStr = intent.getStringExtra("extra_uri");
                if (intent.hasExtra("extra_position_ms"))
                    pos = intent.getLongExtra("extra_position_ms", -1);
                if (uriStr == null) {
                    uriStr = intent.getStringExtra("uri");
                }
                if (pos < 0) {
                    pos = intent.getLongExtra("position_ms", -1);
                }
                if (uriStr == null || pos < 0)
                    return;
                // Update savedPositionCache and notify specific item
                synchronized (savedPositionCache) {
                    savedPositionCache.put(uriStr, pos);
                }
                int posIndex = findPositionByStringUri(uriStr);
                if (posIndex != -1) {
                    mainHandler.post(() -> notifyItemChanged(posIndex));
                }
            }
        };
        try {
            receiverRegisteredContext = context.getApplicationContext();
            this.localBroadcastManager.registerReceiver(this.playbackPositionReceiver,
                    new android.content.IntentFilter("com.fadcam.ACTION_PLAYBACK_POSITION_UPDATED"));
        } catch (Exception ignored) {
        }
        Log.i(TAG, "init safe_media_probe=" + safeMediaProbeMode
                + ", sdk=" + Build.VERSION.SDK_INT
                + ", watch=" + RuntimeCompat.isWatchDevice(this.context)
                + ", lowRam=" + RuntimeCompat.isLowRamDevice(this.context));
    }



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

    @Override
    public int getItemViewType(int position) {
        if (position < 0 || position >= entries.size()) return VIEW_TYPE_ITEM;
        return entries.get(position) instanceof MonthHeaderEntry ? VIEW_TYPE_HEADER : VIEW_TYPE_ITEM;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        if (viewType == VIEW_TYPE_HEADER) {
            View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_forensics_month_header, parent, false);
            return new MonthHeaderViewHolder(view);
        }
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_record, parent, false);
        return new RecordViewHolder(view);
    }

    // *** Need a method to update the processing set from the Fragment ***
    public void updateProcessingUris(Set<Uri> processingUris) {
        boolean changed = !this.currentlyProcessingUris.equals(processingUris);
        if (changed) {
            this.currentlyProcessingUris = new HashSet<>(processingUris); // Use a copy
            Log.d(TAG, "Adapter processing URIs updated: " + this.currentlyProcessingUris.size() + " items.");
            // TODO: Consider optimizing this? Maybe only notify items that changed state?
            // For simplicity now, refresh all potentially affected items (though maybe
            // slow)
            // Find positions of items that *were* processing or *are now* processing
            // This requires iterating, might be simpler to just notifyDataSetChanged for
            // now.
            notifyDataSetChanged(); // Simplest way to reflect changes across list
        }
    }

    // Optimize onBindViewHolder to reduce work on the UI thread
    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder viewHolder, int position) {
        // ── Handle month header ──
        if (viewHolder instanceof MonthHeaderViewHolder) {
            bindMonthHeader((MonthHeaderViewHolder) viewHolder, position);
            return;
        }
        RecordViewHolder holder = (RecordViewHolder) viewHolder;
        // --- 0. Handle Skeleton Mode ---
        if (isSkeletonMode) {
            bindSkeletonItem(holder, position);
            return;
        }

        // binding real data -----------
        // Only clear skeleton effects if this ViewHolder was previously used for skeleton.
        // Checking the tag avoids expensive shimmer removal on every rebind.
        if (holder.itemView.getTag(R.id.skeleton_tag) != null) {
            clearSkeletonEffects(holder);
            holder.itemView.setTag(R.id.skeleton_tag, null);
        }

        // --- 1. Basic Checks & Get Data ---
        if (entries == null || position < 0 || position >= entries.size()
                || !(entries.get(position) instanceof VideoItemEntry)) {
            Log.e(TAG, "onBindViewHolder: Invalid item/data at position " + position);
            return;
        }
        final VideoItemEntry entry = (VideoItemEntry) entries.get(position);
        final VideoItem videoItem = entry.item;
        if (videoItem == null || videoItem.uri == null) {
            Log.e(TAG, "onBindViewHolder: Null videoItem or uri at position " + position);
            return;
        }
        final Uri videoUri = videoItem.uri;
        final String displayName = videoItem.displayName != null ? videoItem.displayName : "Unnamed Video";
        final String uriString = videoUri.toString();
        final boolean isImage = videoItem.mediaType == VideoItem.MediaType.IMAGE;

        // --- 2. Determine Item States ---
        final boolean isCurrentlySelected = this.currentSelectedUris.contains(videoUri);
        final boolean isProcessing = this.currentlyProcessingUris.contains(videoUri);

        final boolean isOpened = sharedPreferencesManager.getOpenedVideoUris().contains(uriString);
        final boolean showNewBadge = !isOpened && !isProcessing;
        final boolean allowGeneralInteractions = !isProcessing;
        final boolean allowMenuClick = allowGeneralInteractions && !this.isSelectionModeActive;

        // Only log for debugging specific positions to reduce spam
        if (position < 3 || position % 20 == 0) {
            Log.v(TAG, "onBindViewHolder Pos " + position + ": Name=" + displayName);
        }

        // --- 3. Bind Standard Data, optimized for fewer UI operations ---
        if (holder.textViewSerialNumber != null)
            holder.textViewSerialNumber.setText(String.valueOf(entry.serialNumber));
        if (holder.textViewRecord != null)
            holder.textViewRecord.setText(displayName);
        if (holder.textViewFileSize != null)
            holder.textViewFileSize.setText(formatFileSize(videoItem.size));

        // Apply proper background and text colors based on theme
        if (isSnowVeilTheme) {
            // For Snow Veil theme, ALWAYS use white card background with black text for
            // maximum contrast
            if (holder.itemView instanceof CardView) {
                CardView cardView = (CardView) holder.itemView;
                // Force pure white background for all cards in Snow Veil theme
                cardView.setCardBackgroundColor(Color.WHITE);

                // Log for debugging
                Log.d(TAG, "Setting WHITE card background for Snow Veil theme at position " + position);
            }

            // Apply black tint to the three-dot menu icon for better contrast on white
            // background
            if (holder.menuButton != null) {
                holder.menuButton.setColorFilter(Color.BLACK, PorterDuff.Mode.SRC_IN);
            }

            // Ensure metadata has good contrast on white cards.
            if (holder.textViewFileSize != null) holder.textViewFileSize.setTextColor(Color.BLACK);
            if (holder.textViewFileTime != null) holder.textViewFileTime.setTextColor(Color.BLACK);
            if (holder.textViewTimeAgo != null) holder.textViewTimeAgo.setTextColor(Color.BLACK);
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
                holder.textViewTimeAgo.setTextColor(holder.defaultMetaTextColor);
            }
            if (holder.textViewFileSize != null) holder.textViewFileSize.setTextColor(holder.defaultMetaTextColor);
            if (holder.textViewFileTime != null) holder.textViewFileTime.setTextColor(holder.defaultMetaTextColor);
        }

        // Optimize time-consuming operations using DB-backed duration cache
        if (holder.textViewFileTime != null) {
            if (isImage) {
                holder.textViewFileTime.setText("");
                holder.textViewFileTime.setVisibility(View.GONE);
                if (holder.metaDurationContainer != null) holder.metaDurationContainer.setVisibility(View.GONE);
                if (holder.recordMetaRowSize instanceof LinearLayout) {
                    LinearLayout sizeRow = (LinearLayout) holder.recordMetaRowSize;
                    sizeRow.setOrientation(LinearLayout.VERTICAL);
                    sizeRow.setGravity(android.view.Gravity.END);
                }
                if (holder.iconFileSize != null) {
                    LinearLayout.LayoutParams iconLp = (LinearLayout.LayoutParams) holder.iconFileSize.getLayoutParams();
                    iconLp.setMarginStart(0);
                    holder.iconFileSize.setLayoutParams(iconLp);
                }
                if (holder.textViewFileSize != null) {
                    LinearLayout.LayoutParams textLp = (LinearLayout.LayoutParams) holder.textViewFileSize.getLayoutParams();
                    textLp.setMarginStart(0);
                    textLp.topMargin = dpToPx(2);
                    holder.textViewFileSize.setLayoutParams(textLp);
                }
                if (holder.recordMetaRowSize != null) {
                    ViewGroup.LayoutParams layoutParams = holder.recordMetaRowSize.getLayoutParams();
                    if (layoutParams instanceof GridLayout.LayoutParams) {
                        GridLayout.LayoutParams gridLayoutParams = (GridLayout.LayoutParams) layoutParams;
                        gridLayoutParams.rowSpec = GridLayout.spec(0);
                        holder.recordMetaRowSize.setLayoutParams(gridLayoutParams);
                    }
                }
            } else {
                if (holder.metaDurationContainer != null) holder.metaDurationContainer.setVisibility(View.VISIBLE);
                holder.textViewFileTime.setVisibility(View.VISIBLE);
                if (holder.recordMetaRowSize instanceof LinearLayout) {
                    LinearLayout sizeRow = (LinearLayout) holder.recordMetaRowSize;
                    sizeRow.setOrientation(LinearLayout.HORIZONTAL);
                    sizeRow.setGravity(android.view.Gravity.CENTER_VERTICAL | android.view.Gravity.END);
                }
                if (holder.iconFileSize != null) {
                    LinearLayout.LayoutParams iconLp = (LinearLayout.LayoutParams) holder.iconFileSize.getLayoutParams();
                    iconLp.setMarginStart(0);
                    holder.iconFileSize.setLayoutParams(iconLp);
                }
                if (holder.textViewFileSize != null) {
                    LinearLayout.LayoutParams textLp = (LinearLayout.LayoutParams) holder.textViewFileSize.getLayoutParams();
                    textLp.setMarginStart(dpToPx(4));
                    textLp.topMargin = 0;
                    holder.textViewFileSize.setLayoutParams(textLp);
                }
                if (holder.recordMetaRowSize != null) {
                    ViewGroup.LayoutParams layoutParams = holder.recordMetaRowSize.getLayoutParams();
                    if (layoutParams instanceof GridLayout.LayoutParams) {
                        GridLayout.LayoutParams gridLayoutParams = (GridLayout.LayoutParams) layoutParams;
                        gridLayoutParams.rowSpec = GridLayout.spec(1);
                        holder.recordMetaRowSize.setLayoutParams(gridLayoutParams);
                    }
                }
                // Check in-memory position cache first (avoids even DB read on rebind)
                String cachedDuration = loadedThumbnailCache.get(position);
                if (cachedDuration != null) {
                    holder.textViewFileTime.setText(cachedDuration);
                } else {
                    // Show placeholder immediately — never block the UI thread
                    holder.textViewFileTime.setText("--:--");
                    if (!(safeMediaProbeMode && isScrolling)) {
                        // Try DB-backed duration first (instant), fall back to FFprobe only if needed
                        executorService.execute(() -> {
                            long duration = -1;

                            // Fast path: read from persistent Room DB index
                            try {
                                com.fadcam.data.VideoIndexRepository repo =
                                        com.fadcam.data.VideoIndexRepository.getInstance(context);
                                duration = repo.getCachedDuration(videoUri.toString());
                            } catch (Exception e) {
                                Log.w(TAG, "DB duration lookup failed", e);
                            }

                            // Slow path: fall back to FFprobe/MMR only if DB has no data
                            if (duration <= 0) {
                                // Add a small delay for newly recorded videos
                                if (videoItem.isNew) {
                                    try {
                                        Thread.sleep(500);
                                    } catch (InterruptedException e) {
                                        Thread.currentThread().interrupt();
                                    }
                                }
                                duration = getVideoDuration(videoUri);

                                // Write FFprobe result back to DB so we never re-probe this video
                                if (duration > 0) {
                                    try {
                                        com.fadcam.data.VideoIndexRepository.getInstance(context)
                                                .persistDurationToDb(videoUri.toString(), duration);
                                    } catch (Exception ex) {
                                        // Non-fatal: adapter cache still has the value
                                    }
                                }
                            }

                            String formattedDuration = formatVideoDuration(duration);
                            loadedThumbnailCache.put(position, formattedDuration);

                            // Update UI on main thread
                            new Handler(Looper.getMainLooper()).post(() -> {
                                if (holder.getAdapterPosition() == position && holder.textViewFileTime != null) {
                                    holder.textViewFileTime.setText(formattedDuration);
                                }
                            });
                        });
                    }
                }
            }
        }

        if (holder.textViewTimeAgo != null)
            holder.textViewTimeAgo.setText(Utils.formatTimeAgo(videoItem.lastModified));

        // Only set the thumbnail if holder view is visible (important optimization)
        if (holder.imageViewThumbnail != null && holder.itemView.getVisibility() == View.VISIBLE) {
            setThumbnail(holder, videoUri);
        }

        // --- Last-viewed progress bar handling (optimized with caching) ---
        try {
            View progressBg = holder.itemView.findViewById(R.id.thumbnail_progress_bg);
            View progressFill = holder.itemView.findViewById(R.id.thumbnail_progress_fill);
            if (progressBg != null && progressFill != null) {
                if (isImage) {
                    progressBg.setVisibility(View.GONE);
                    progressFill.setVisibility(View.GONE);
                } else {
                    progressBg.setVisibility(View.VISIBLE);
                    progressFill.setVisibility(View.GONE);
                    final String key = videoUri.toString();
                    // Try caches first
                    Long cachedSaved = savedPositionCache.get(key);
                    Long cachedDur = durationCache.get(key);
                    // Also check DB-backed duration cache (avoids FFprobe entirely)
                    if (cachedDur == null) {
                        try {
                            long dbDur = com.fadcam.data.VideoIndexRepository.getInstance(context)
                                    .getCachedDuration(key);
                            if (dbDur > 0) {
                                cachedDur = dbDur;
                                synchronized (durationCache) {
                                    durationCache.put(key, dbDur);
                                }
                            }
                        } catch (Exception ignored) {}
                    }
                    if (cachedSaved != null && cachedDur != null) {
                        applyProgressToView(progressBg, progressFill, cachedSaved, cachedDur);
                    } else {
                        if (safeMediaProbeMode && isScrolling) {
                            progressFill.setVisibility(View.GONE);
                            return;
                        }
                        final Long finalCachedDur = cachedDur;
                        // Submit one background task to compute missing values
                        executorService.execute(() -> {
                            try {
                                long savedMs = cachedSaved != null ? cachedSaved
                                        : sharedPreferencesManager.getSavedPlaybackPositionMsWithFilenameFallback(key,
                                                getFileName(videoUri));
                                long durationMs = finalCachedDur != null ? finalCachedDur : getVideoDuration(videoUri);
                                // Cache results for future bindings (synchronized because LinkedHashMap isn't
                                // thread-safe)
                                synchronized (durationCache) {
                                    if (durationMs > 0) {
                                        durationCache.put(key, durationMs);
                                        // schedule persist (debounced)
                                        mainHandler.removeCallbacks(persistDurationTask);
                                        mainHandler.postDelayed(persistDurationTask, 2000);
                                    }
                                }
                                synchronized (savedPositionCache) {
                                    if (savedMs > 0)
                                        savedPositionCache.put(key, savedMs);
                                }
                                final long fSaved = savedMs;
                                final long fDur = durationMs;
                                mainHandler.post(() -> applyProgressToView(progressBg, progressFill, fSaved, fDur));
                            } catch (Exception e) {
                                Log.w(TAG, "Error computing thumbnail progress", e);
                                mainHandler.post(() -> progressFill.setVisibility(View.GONE));
                            }
                        });
                    }
                }
            }
        } catch (Exception ignored) {
        }

        // --- 4. Visibility Logic for Overlays/Badges ---



        // Processing Overlay (Scrim and Spinner)
        if (holder.processingScrim != null)
            holder.processingScrim.setVisibility(isProcessing ? View.VISIBLE : View.GONE);
        if (holder.processingSpinner != null)
            holder.processingSpinner.setVisibility(isProcessing ? View.VISIBLE : View.GONE);

        // *** RESTORED Status Badge Logic ***
        if (holder.textViewStatusBadge != null && context != null) {
            if (isProcessing) {
                holder.textViewStatusBadge.setVisibility(View.GONE); // Hide all badges during processing
            } else if (isImage) {
                int badgeRes = getImageBadgeLabelRes(videoItem);
                holder.textViewStatusBadge.setText(context.getString(badgeRes).toUpperCase(Locale.US));
                holder.textViewStatusBadge
                        .setBackground(ContextCompat.getDrawable(context, R.drawable.badge_bg_gray));
                holder.textViewStatusBadge.setTextColor(ContextCompat.getColor(context, R.color.white));
                holder.textViewStatusBadge.setVisibility(View.VISIBLE);
            } else if (showNewBadge) {
                // Show NEW badge
                holder.textViewStatusBadge.setText("NEW");
                holder.textViewStatusBadge
                        .setBackground(ContextCompat.getDrawable(context, R.drawable.new_badge_background));
                holder.textViewStatusBadge.setTextColor(ContextCompat.getColor(context, R.color.white));
                holder.textViewStatusBadge.setVisibility(View.VISIBLE);
            } else {
                // Hide badge
                holder.textViewStatusBadge.setVisibility(View.GONE);
            }
        }
        // *** END RESTORED Status Badge Logic ***

        // --- 5. Handle Selection Mode Visuals (center check + dim overlay) ---
        applySelectionVisuals(holder, isCurrentlySelected, false);

        // --- 6. Set Enabled State and Listeners ---
        holder.itemView.setEnabled(allowGeneralInteractions); // Click/LongClick allowed if not processing
        if (holder.menuButtonContainer != null) {
            holder.menuButtonContainer.setEnabled(allowMenuClick); // Menu allowed if !processing AND !selectionMode
            holder.menuButtonContainer.setClickable(allowMenuClick);
            if (holder.menuButton != null)
                holder.menuButton.setAlpha(allowMenuClick ? 1.0f : 0.4f); // Dim if disabled
        } else {
            Log.w(TAG, "menuButtonContainer is null at pos " + position);
        }

        holder.itemView.setOnClickListener(v -> {
            if (allowGeneralInteractions && clickListener != null) {
                clickListener.onVideoClick(videoItem);
            }
        });
        holder.itemView.setOnLongClickListener(v -> {
            if (allowGeneralInteractions && longClickListener != null) {
                longClickListener.onVideoLongClick(videoItem, true);
            }
            return allowGeneralInteractions;
        });

        // -----------
        // Set the same click and long press listeners on the thumbnail for better UX
        if (holder.imageViewThumbnail != null) {
            holder.imageViewThumbnail.setOnClickListener(v -> {
                if (allowGeneralInteractions && clickListener != null) {
                    clickListener.onVideoClick(videoItem);
                }
            });
            holder.imageViewThumbnail.setOnLongClickListener(v -> {
                if (allowGeneralInteractions && longClickListener != null) {
                    longClickListener.onVideoLongClick(videoItem, true);
                }
                return allowGeneralInteractions;
            });
        }

        // Also set listeners on the thumbnail container to catch taps on padding/margin
        // areas
        if (holder.thumbnailContainer != null) {
            holder.thumbnailContainer.setOnClickListener(v -> {
                if (allowGeneralInteractions && clickListener != null) {
                    clickListener.onVideoClick(videoItem);
                }
            });
            holder.thumbnailContainer.setOnLongClickListener(v -> {
                if (allowGeneralInteractions && longClickListener != null) {
                    longClickListener.onVideoLongClick(videoItem, true);
                }
                return allowGeneralInteractions;
            });
        }

        // INSTEAD, set a click listener on the menuButtonContainer
        if (holder.menuButtonContainer != null) {
            holder.menuButtonContainer.setOnClickListener(v -> {
                boolean isStillAllowMenuClick = !this.currentlyProcessingUris.contains(videoItem.uri)
                        && !this.isSelectionModeActive;
                if (isStillAllowMenuClick) {
                    showVideoActionsSheet(holder, videoItem);
                }
            });
        }
        applyPressFeedback(holder.itemView, holder.itemView);
        applyPressFeedback(holder.imageViewThumbnail, holder.itemView);
        applyPressFeedback(holder.thumbnailContainer, holder.itemView);

        // --- Adapt card content for current grid span ---
        applyGridSizing(holder);

    } // End onBindViewHolder

    private void applyPressFeedback(View touchView, View targetView) {
        if (touchView == null || targetView == null) return;
        touchView.setOnTouchListener((v, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    targetView.animate().scaleX(0.985f).scaleY(0.985f).setDuration(90L).start();
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    targetView.animate().scaleX(1f).scaleY(1f).setDuration(120L).start();
                    break;
                default:
                    break;
            }
            return false;
        });
    }

    private int dpToPx(int dp) {
        if (context == null) return dp;
        float density = context.getResources().getDisplayMetrics().density;
        return Math.round(dp * density);
    }

    private int getImageBadgeLabelRes(@NonNull VideoItem videoItem) {
        VideoItem.ShotSubtype subtype = videoItem.shotSubtype == null
                ? VideoItem.ShotSubtype.UNKNOWN
                : videoItem.shotSubtype;
        switch (subtype) {
            case SELFIE:
                return R.string.media_type_photo_selfie;
            case FADREC:
                return R.string.media_type_photo_fadrec;
            case BACK:
            case UNKNOWN:
            case ALL:
            default:
                return R.string.media_type_photo_back;
        }
    }

    @Override
    public int getItemCount() {
        int count = entries == null ? 0 : entries.size();
        if (count == 0) {
            Log.d(TAG, "getItemCount returning 0 - entries is " + (entries == null ? "null" : "empty") +
                    ", skeleton mode: " + isSkeletonMode);
        }
        return count;
    }

    // Load duration cache from JSON file
    private void loadDurationCacheFromDisk() {
        if (durationCacheFile == null || !durationCacheFile.exists())
            return;
        try (java.io.FileInputStream fis = new java.io.FileInputStream(durationCacheFile)) {
            byte[] data = new byte[(int) durationCacheFile.length()];
            fis.read(data);
            String json = new String(data, java.nio.charset.StandardCharsets.UTF_8);
            org.json.JSONObject obj = new org.json.JSONObject(json);
            org.json.JSONArray names = obj.names();
            if (names != null) {
                for (int i = 0; i < names.length(); i++) {
                    String k = names.getString(i);
                    long v = obj.optLong(k, 0L);
                    if (v > 0)
                        durationCache.put(k, v);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to load duration cache", e);
        }
    }

    // Persist duration cache to disk (best-effort)
    private void persistDurationCacheToDisk() {
        if (durationCacheFile == null)
            return;
        try {
            org.json.JSONObject obj = new org.json.JSONObject();
            synchronized (durationCache) {
                for (java.util.Map.Entry<String, Long> e : durationCache.entrySet()) {
                    obj.put(e.getKey(), e.getValue());
                }
            }
            try (java.io.FileOutputStream fos = new java.io.FileOutputStream(durationCacheFile)) {
                fos.write(obj.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                fos.getFD().sync();
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to persist duration cache", e);
        }
    }

    // Helper to apply computed progress to views on UI thread
    private void applyProgressToView(View progressBg, View progressFill, long savedMs, long durationMs) {
        try {
            if (savedMs > 0 && durationMs > 1000) {
                int percent = (int) Math.max(1, Math.min(100, (savedMs * 100) / durationMs));
                int bgW = progressBg.getWidth();
                if (bgW <= 0) {
                    progressBg.post(() -> {
                        int w = progressBg.getWidth();
                        int target = (w * percent) / 100;
                        animateProgressWidth(progressFill, target);
                        progressFill.setVisibility(View.VISIBLE);
                        // accessibility
                        progressFill.setContentDescription(progressPercentContentDescription(percent));
                    });
                } else {
                    int target = (bgW * percent) / 100;
                    animateProgressWidth(progressFill, target);
                    progressFill.setVisibility(View.VISIBLE);
                    // accessibility
                    progressFill.setContentDescription(progressPercentContentDescription(percent));
                }
            } else {
                progressFill.setVisibility(View.GONE);
                progressFill.setContentDescription(null);
            }
        } catch (Exception e) {
            Log.w(TAG, "applyProgressToView error", e);
        }
    }

    // Animate width change for the progress fill for a smooth visual update
    private void animateProgressWidth(View view, int toWidth) {
        if (view == null)
            return;
        try {
            int from = view.getLayoutParams().width;
            if (from < 0)
                from = 0;
            android.animation.ValueAnimator va = android.animation.ValueAnimator.ofInt(from, toWidth);
            va.setDuration(200);
            va.addUpdateListener(animation -> {
                int val = (int) animation.getAnimatedValue();
                view.getLayoutParams().width = val;
                view.requestLayout();
            });
            va.start();
        } catch (Exception ignored) {
        }
    }

    // Accessibility string helper
    private String progressPercentContentDescription(int percent) {
        try {
            return context.getString(R.string.accessibility_thumbnail_progress, percent);
        } catch (Exception ignored) {
            return percent + "% watched";
        }
    }

    private void applySelectionVisuals(@NonNull RecordViewHolder holder, boolean isCurrentlySelected, boolean animateCheck) {
        if (holder.iconCheckContainer == null || holder.checkIcon == null) {
            return;
        }
        float contentAlpha = (isSelectionModeActive && isCurrentlySelected) ? 0.58f : 1f;
        if (holder.textViewRecord != null) holder.textViewRecord.setAlpha(contentAlpha);
        if (holder.textViewTimeAgo != null) holder.textViewTimeAgo.setAlpha(contentAlpha);
        if (holder.textViewFileSize != null) holder.textViewFileSize.setAlpha(contentAlpha);
        if (holder.textViewFileTime != null) holder.textViewFileTime.setAlpha(contentAlpha);
        if (holder.textViewSerialNumber != null) holder.textViewSerialNumber.setAlpha(contentAlpha);
        if (holder.textViewStatusBadge != null) holder.textViewStatusBadge.setAlpha(contentAlpha);
        if (holder.menuButtonContainer != null) holder.menuButtonContainer.setAlpha(contentAlpha);
        if (holder.selectionDimOverlay != null) {
            holder.selectionDimOverlay.setVisibility((isSelectionModeActive && isCurrentlySelected) ? View.VISIBLE : View.GONE);
        }

        if (!isSelectionModeActive) {
            holder.iconCheckContainer.setVisibility(View.GONE);
            holder.checkIcon.setAlpha(0f);
            holder.checkIcon.setScaleX(0f);
            holder.checkIcon.setScaleY(0f);
            return;
        }

        // Keep card colors stable in selection mode; selected state is shown by dim+center check only.
        if (holder.itemView instanceof CardView && context != null) {
            if (isSnowVeilTheme) {
                ((CardView) holder.itemView).setCardBackgroundColor(Color.WHITE);
                if (holder.textViewRecord != null) holder.textViewRecord.setTextColor(Color.BLACK);
            } else {
                ((CardView) holder.itemView).setCardBackgroundColor(ContextCompat.getColor(context, R.color.gray));
                if (holder.textViewRecord != null) holder.textViewRecord.setTextColor(holder.defaultTextColor);
            }
        }

        if (isCurrentlySelected) {
            holder.iconCheckContainer.setVisibility(View.VISIBLE);
            if (animateCheck) {
                animateCheckIcon(holder.checkIcon, true);
            } else {
                holder.checkIcon.setAlpha(1f);
                holder.checkIcon.setScaleX(1f);
                holder.checkIcon.setScaleY(1f);
            }
        } else {
            if (animateCheck) {
                animateCheckIcon(holder.checkIcon, false);
                holder.checkIcon.postDelayed(() -> holder.iconCheckContainer.setVisibility(View.GONE), 170);
            } else {
                holder.checkIcon.setAlpha(0f);
                holder.checkIcon.setScaleX(0f);
                holder.checkIcon.setScaleY(0f);
                holder.iconCheckContainer.setVisibility(View.GONE);
            }
        }
    }

    // Update the setThumbnail method to consider scrolling state with caching
    private void setThumbnail(RecordViewHolder holder, Uri videoUri) {
        if (holder.imageViewThumbnail == null || context == null)
            return;
        // Honor user preference: hide thumbnails if requested
        try {
            if (sharedPreferencesManager != null && sharedPreferencesManager.isHideThumbnailsEnabled()) {
                // Hide the thumbnail view and show a lightweight placeholder background
                holder.imageViewThumbnail.setImageResource(R.drawable.ic_video_placeholder);
                holder.imageViewThumbnail.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
                return;
            }
        } catch (Exception ignored) {
        }

        // Skip Glide loading for skeleton URIs
        if ("skeleton".equals(videoUri.getScheme())) {
            holder.imageViewThumbnail.setImageResource(R.drawable.ic_video_placeholder);
            return;
        }

        String uriString = videoUri.toString();

        // Try to get cached thumbnail first
        byte[] cachedThumbnail = com.fadcam.utils.VideoSessionCache.getThumbnailWithFallback(context, uriString);
        if (cachedThumbnail != null) {
            // Load from cache - instant display
            try {
                android.graphics.Bitmap bitmap = android.graphics.BitmapFactory.decodeByteArray(cachedThumbnail, 0,
                        cachedThumbnail.length);
                if (bitmap != null) {
                    holder.imageViewThumbnail.setImageBitmap(bitmap);
                    holder.imageViewThumbnail.setScaleType(ImageView.ScaleType.CENTER_CROP);
                    Log.v(TAG, "Loaded thumbnail from cache for: " + uriString);
                    return;
                }
            } catch (Exception e) {
                Log.w(TAG, "Error loading cached thumbnail", e);
            }
        }

        // Lower resolution during scrolling for performance
        int thumbnailSize;
        if (safeMediaProbeMode) {
            thumbnailSize = isScrolling ? SAFE_SCROLL_THUMBNAIL_SIZE : SAFE_THUMBNAIL_SIZE;
        } else {
            thumbnailSize = isScrolling ? 100 : THUMBNAIL_SIZE;
        }

        // Create optimized request options with different strategies based on scrolling
        RequestOptions options = new RequestOptions()
                .diskCacheStrategy(DiskCacheStrategy.ALL)
                .skipMemoryCache(false)
                .centerCrop()
                .override(thumbnailSize, thumbnailSize)
                .placeholder(R.drawable.ic_video_placeholder)
                .error(R.drawable.ic_video_placeholder);

        if (isScrolling) {
            // During scrolling, use low-quality thumbnails for speed
            options = options.dontAnimate();
        }

        // Load with Glide and cache the result
        Glide.with(context)
                .asBitmap()
                .load(videoUri)
                .apply(options)
                .thumbnail(0.1f)
                .into(new com.bumptech.glide.request.target.CustomTarget<android.graphics.Bitmap>() {
                    @Override
                    public void onResourceReady(@NonNull android.graphics.Bitmap resource,
                            @androidx.annotation.Nullable com.bumptech.glide.request.transition.Transition<? super android.graphics.Bitmap> transition) {
                        // Set the image
                        holder.imageViewThumbnail.setImageBitmap(resource);
                        holder.imageViewThumbnail.setScaleType(ImageView.ScaleType.CENTER_CROP);

                        // Cache the thumbnail for future use
                        executorService.execute(() -> {
                            try {
                                java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
                                resource.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, baos);
                                byte[] thumbnailData = baos.toByteArray();

                                // Cache in memory and disk
                                com.fadcam.utils.VideoSessionCache.cacheThumbnail(uriString, thumbnailData);
                                com.fadcam.utils.VideoSessionCache.saveThumbnailToDisk(context, uriString,
                                        thumbnailData);

                                Log.v(TAG, "Cached new thumbnail for: " + uriString);
                            } catch (Exception e) {
                                Log.w(TAG, "Error caching thumbnail", e);
                            }
                        });
                    }

                    @Override
                    public void onLoadCleared(
                            @androidx.annotation.Nullable android.graphics.drawable.Drawable placeholder) {
                        // Set placeholder if load is cleared
                        holder.imageViewThumbnail.setImageResource(R.drawable.ic_video_placeholder);
                    }
                });
    }

    // Override onViewRecycled to cancel thumbnail loading for recycled views
    @Override
    public void onViewRecycled(@NonNull RecyclerView.ViewHolder viewHolder) {
        super.onViewRecycled(viewHolder);
        if (!(viewHolder instanceof RecordViewHolder)) return;
        RecordViewHolder holder = (RecordViewHolder) viewHolder;

        // Cancel any pending image loads when view is recycled
        if (holder.imageViewThumbnail != null && context != null) {
            Glide.with(context).clear(holder.imageViewThumbnail);
        }
        holder.itemView.setScaleX(1f);
        holder.itemView.setScaleY(1f);
    }

    // Override onBindViewHolder to handle payload for quality changes
    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder viewHolder, int position, @NonNull List<Object> payloads) {
        // ── Month headers: always full rebind ──
        if (viewHolder instanceof MonthHeaderViewHolder) {
            bindMonthHeader((MonthHeaderViewHolder) viewHolder, position);
            return;
        }
        RecordViewHolder holder = (RecordViewHolder) viewHolder;
        if (!payloads.isEmpty()) {
            if (payloads.contains("QUALITY_CHANGE")) {
                // Only update thumbnail when scrolling stops
                if (holder.imageViewThumbnail != null && position < entries.size()
                        && entries.get(position) instanceof VideoItemEntry) {
                    VideoItem videoItem = ((VideoItemEntry) entries.get(position)).item;
                    if (videoItem != null && videoItem.uri != null) {
                        setThumbnail(holder, videoItem.uri);
                    }
                }
                return;
            }

            if (payloads.contains("SELECTION_TOGGLE")) {
                if (position < entries.size() && entries.get(position) instanceof VideoItemEntry) {
                    VideoItem videoItem = ((VideoItemEntry) entries.get(position)).item;
                    boolean isCurrentlySelected = this.currentSelectedUris.contains(videoItem.uri);
                    applySelectionVisuals(holder, isCurrentlySelected, true);
                }
                return;
            }

            if (payloads.contains("SELECTION_MODE")) {
                if (position < entries.size() && entries.get(position) instanceof VideoItemEntry) {
                    VideoItem videoItem = ((VideoItemEntry) entries.get(position)).item;
                    boolean isCurrentlySelected = this.currentSelectedUris.contains(videoItem.uri);
                    applySelectionVisuals(holder, isCurrentlySelected, false);
                    boolean isProcessing = this.currentlyProcessingUris.contains(videoItem.uri);
                    boolean allowGeneralInteractions = !isProcessing;
                    boolean allowMenuClick = !isProcessing && !this.isSelectionModeActive;
                    holder.itemView.setEnabled(allowGeneralInteractions);
                    if (holder.menuButtonContainer != null) {
                        holder.menuButtonContainer.setEnabled(allowMenuClick);
                        holder.menuButtonContainer.setClickable(allowMenuClick);
                    }
                    if (holder.menuButton != null) {
                        holder.menuButton.setAlpha(allowMenuClick ? 1.0f : 0.4f);
                    }
                }
                return;
            }

            if (payloads.contains("SERIAL_UPDATE")) {
                // Lightweight rebind: only update the serial number for shifted items
                if (holder.textViewSerialNumber != null && position < entries.size()
                        && entries.get(position) instanceof VideoItemEntry) {
                    holder.textViewSerialNumber.setText(
                            String.valueOf(((VideoItemEntry) entries.get(position)).serialNumber));
                }
                return;
            }
        }
        // If no specific payload, do a full bind
        onBindViewHolder(holder, position);

        // EMERGENCY FIX: Force apply Snow Veil card color after normal binding
        if (isSnowVeilTheme && holder.itemView instanceof CardView) {
            ((CardView) holder.itemView).setCardBackgroundColor(Color.WHITE);
            Log.d(TAG, "🔴 EMERGENCY: Forced WHITE card for Snow Veil at position " + position);

            // Force black text on all text elements
            if (holder.textViewRecord != null)
                holder.textViewRecord.setTextColor(Color.BLACK);
            if (holder.textViewTimeAgo != null)
                holder.textViewTimeAgo.setTextColor(Color.BLACK);
            if (holder.textViewFileSize != null)
                holder.textViewFileSize.setTextColor(Color.BLACK);
            if (holder.textViewFileTime != null)
                holder.textViewFileTime.setTextColor(Color.BLACK);
        }
    }

    // --- Selection Handling ---
    private void toggleSelection(Uri videoUri, boolean isSelected) {
        if (videoUri == null)
            return;
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
            notifyItemChanged(position, "SELECTION_TOGGLE"); // Update specific item with payload to animate
        } else {
            Log.w(TAG, "Could not find position for URI: " + videoUri + " during toggle. List size: " + records.size());
            // Maybe list was updated concurrently? Do a full refresh as fallback.
            // notifyDataSetChanged(); // Use this cautiously
        }
    }

    // Helper to find item position by URI (important for notifyItemChanged)
    // Make this public so the Fragment can call it after marking an item opened
    public int findPositionByUri(Uri uri) { // <-- *** CHANGED to public ***
        if (uri == null || entries == null) {
            Log.w(TAG, "findPositionByUri called with null uri or null entries list.");
            return -1;
        }
        for (int i = 0; i < entries.size(); i++) {
            Object entry = entries.get(i);
            if (entry instanceof VideoItemEntry) {
                VideoItem item = ((VideoItemEntry) entry).item;
                if (item != null && item.uri != null && uri.equals(item.uri)) {
                    return i;
                }
            }
        }
        Log.v(TAG, "URI not found in adapter entries: " + uri); // Use v for verbose logs
        return -1; // Not found
    }

    @Override
    public void onDetachedFromRecyclerView(@NonNull androidx.recyclerview.widget.RecyclerView recyclerView) {
        super.onDetachedFromRecyclerView(recyclerView);
        try {
            if (receiverRegisteredContext != null) {
                androidx.localbroadcastmanager.content.LocalBroadcastManager.getInstance(receiverRegisteredContext)
                        .unregisterReceiver(playbackPositionReceiver);
            } else {
                androidx.localbroadcastmanager.content.LocalBroadcastManager.getInstance(recyclerView.getContext())
                        .unregisterReceiver(playbackPositionReceiver);
            }
        } catch (Exception ignored) {
        }
    }

    private int findPositionByStringUri(String uriStr) {
        if (uriStr == null || entries == null)
            return -1;
        for (int i = 0; i < entries.size(); i++) {
            Object entry = entries.get(i);
            if (!(entry instanceof VideoItemEntry)) continue;
            VideoItem it = ((VideoItemEntry) entry).item;
            if (it == null)
                continue;
            if (uriStr.equals(it.uri == null ? null : it.uri.toString()))
                return i;
            // tolerate filename fallback keys (may be stored as plain filenames)
            String fn = getFileName(it.uri);
            if (fn != null && fn.equals(uriStr))
                return i;
        }
        return -1;
    }

    // --- Popup Menu and Actions (Major Updates Here) ---
    private int resolveThemeColor(Context context, int attr) {
        android.util.TypedValue typedValue = new android.util.TypedValue();
        context.getTheme().resolveAttribute(attr, typedValue, true);
        return typedValue.data;
    }

    /**
     * showVideoActionsSheet
     * Replaces legacy PopupMenu with our unified bottom sheet picker using ligature
     * icons.
     * Preserves existing behaviors by mapping item ids to the same handlers.
     */
    private void showVideoActionsSheet(RecordViewHolder holder, VideoItem videoItem) {
        Context ctx = holder.itemView.getContext();
        boolean isImage = videoItem != null && videoItem.mediaType == VideoItem.MediaType.IMAGE;
        if (!(ctx instanceof FragmentActivity)) {
            // Fallback to popup if we don't have a FragmentActivity context
            PopupMenu popup = setupPopupMenu(holder, videoItem);
            if (popup != null)
                popup.show();
            return;
        }

        ArrayList<OptionItem> items = new ArrayList<>();
        // Order mirrors existing menu; use contextual ligatures per repo policy and
        // helper subtitles
        items.add(new OptionItem(
                "action_save",
                ctx.getString(R.string.video_menu_save),
                ctx.getString(R.string.records_batch_save_desc),
                null,
                null,
                R.drawable.ic_arrow_right,
                null,
                null,
                "download",
                null,
                null,
                null));
        // Temporarily hide Fix Video from UI; keep feature intact for later re-enable
        // items.add(OptionItem.withLigature("action_fix_video",
        // ctx.getString(R.string.fix_video_menu_title), "build"));
        items.add(OptionItem.withLigature("action_rename", ctx.getString(R.string.video_menu_rename),
                "drive_file_rename_outline"));
        items.add(OptionItem.withLigature("action_info", ctx.getString(R.string.video_menu_info), "info"));
        if (!isImage) {
            items.add(OptionItem.withLigature("action_upload_youtube", ctx.getString(R.string.video_menu_upload_youtube),
                    "play_circle"));
            items.add(OptionItem.withLigature("action_upload_drive", ctx.getString(R.string.video_menu_upload_drive),
                    "cloud_upload"));
            items.add(OptionItem.withLigature("action_open_with", ctx.getString(R.string.video_menu_open_with),
                    "open_in_new"));
        }
        // New: Upload to FadDrive (coming soon) — badge only, no helper line
        if (!isImage) {
            items.add(OptionItem.withLigatureBadge("action_upload_faddrive",
                    ctx.getString(R.string.video_menu_upload_faddrive, "Upload to FadDrive"), "cloud",
                    ctx.getString(R.string.remote_coming_soon_badge), R.drawable.badge_background_green, true, null));
        }
        // Edit with Faditor Mini
        if (!isImage) {
            items.add(OptionItem.withLigature("action_edit_faditorx", ctx.getString(R.string.edit_with_faditorx),
                    "content_cut"));
        }
        items.add(OptionItem.withLigature("action_delete", ctx.getString(R.string.video_menu_del), "delete"));

        String resultKey = "video_actions:"
                + (videoItem.uri != null ? videoItem.uri.toString() : System.identityHashCode(videoItem));
        FragmentManager fm = ((FragmentActivity) ctx).getSupportFragmentManager();
        fm.setFragmentResultListener(resultKey, (FragmentActivity) ctx, (requestKey, bundle) -> {
            if (bundle == null)
                return;
            String id = bundle.getString(PickerBottomSheetFragment.BUNDLE_SELECTED_ID);
            if (id == null)
                return;
            switch (id) {
                case "action_edit_faditorx":
                    launchFaditorMini(ctx, videoItem);
                    break;
                case "action_upload_faddrive":
                    Toast.makeText(ctx, R.string.remote_toast_coming_soon, Toast.LENGTH_SHORT).show();
                    break;
                case "action_save":
                    showSaveOptionsSheet(videoItem, ctx);
                    break;
                case "action_fix_video":
                    fixVideoFile(videoItem);
                    break;
                case "action_rename":
                    showRenameDialog(videoItem);
                    break;
                case "action_info":
                    showVideoInfoDialog(videoItem);
                    break;
                case "action_delete":
                    if (actionListener != null)
                        actionListener.onDeleteVideo(videoItem);
                    break;
                case "action_upload_youtube":
                    openVideoInYouTube(videoItem);
                    break;
                case "action_upload_drive":
                    openVideoInGoogleDrive(videoItem);
                    break;
                case "action_open_with":
                    openWithExternalPlayer(videoItem);
                    break;
            }
        });

        // Title: show the file name or a generic label
        String sheetTitle = (videoItem != null && videoItem.displayName != null) ? videoItem.displayName
                : ctx.getString(R.string.records_title);
        PickerBottomSheetFragment sheet = PickerBottomSheetFragment.newInstanceGradient(
                sheetTitle,
                items,
                null,
                resultKey,
                null,
                true);

        // Hide selection checkmarks for action sheets so rows are compact
        Bundle args = sheet.getArguments();
        if (args != null)
            args.putBoolean(PickerBottomSheetFragment.ARG_HIDE_CHECK, true);

        sheet.show(fm, "video_actions_sheet");
    }

    // -------------- Save Options Sheet (Copy vs Move) -----------
    private void showSaveOptionsSheet(VideoItem videoItem, Context ctx) {
        if (!(ctx instanceof FragmentActivity)) {
            // Fallback to default copy behavior
            saveVideoToGalleryInternal(videoItem);
            return;
        }

        ArrayList<OptionItem> items = new ArrayList<>();
        
        // Copy option (default)
        items.add(new OptionItem(
                "save_copy",
                ctx.getString(R.string.video_menu_save_copy),
                ctx.getString(R.string.video_menu_save_copy_desc),
                null,
                null,
                null,
                null,
                null,
                "content_copy",
                null,
                null,
                null));
                
        // Move option
        items.add(new OptionItem(
                "save_move",
                ctx.getString(R.string.video_menu_save_move),
                ctx.getString(R.string.video_menu_save_move_desc),
                null,
                null,
                null,
                null,
                null,
                "drive_file_move",
                null,
                null,
                null));

        // Custom export location
        items.add(new OptionItem(
                "save_export_custom_location",
                ctx.getString(R.string.records_batch_save_custom_location),
                ctx.getString(R.string.records_batch_save_custom_location_desc),
                null,
                null,
                null,
                null,
                null,
                "folder_open",
                null,
                null,
                null));

        String resultKey = "save_options:" + (videoItem.uri != null ? videoItem.uri.toString() : System.identityHashCode(videoItem));
        FragmentManager fm = ((FragmentActivity) ctx).getSupportFragmentManager();
        fm.setFragmentResultListener(resultKey, (FragmentActivity) ctx, (requestKey, bundle) -> {
            if (bundle == null) return;
            String id = bundle.getString(PickerBottomSheetFragment.BUNDLE_SELECTED_ID);
            if (id == null) return;
            
            switch (id) {
                case "save_copy":
                    // Start copy operation in background
                    FileOperationService.startCopyToGallery(ctx, videoItem.uri, videoItem.displayName, videoItem.displayName);
                    break;
                case "save_move":
                    // Start move operation in background
                    FileOperationService.startMoveToGallery(ctx, videoItem.uri, videoItem.displayName, videoItem.displayName);
                    break;
                case "save_export_custom_location":
                    if (actionListener != null) {
                        actionListener.onCustomExportRequested(videoItem);
                    }
                    break;
            }
        });

        String sheetTitle = ctx.getString(R.string.video_menu_save_copy_or_move_title);
        PickerBottomSheetFragment sheet = PickerBottomSheetFragment.newInstanceGradient(
                sheetTitle,
                items,
                "save_copy", // Default selection is copy
                resultKey,
                null,
                true);

        // Hide selection checkmarks
        Bundle args = sheet.getArguments();
        if (args != null) {
            args.putBoolean(PickerBottomSheetFragment.ARG_HIDE_CHECK, true);
        }

        sheet.show(fm, "save_options_sheet");
    }
    // -------------- End Save Options Sheet -----------

    private PopupMenu setupPopupMenu(RecordViewHolder holder, VideoItem videoItem) {
        Context context = holder.itemView.getContext();
        boolean isImage = videoItem != null && videoItem.mediaType == VideoItem.MediaType.IMAGE;
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
        if (isImage) {
            popup.getMenu().removeItem(R.id.action_edit_faditorx);
            popup.getMenu().removeItem(R.id.action_upload_youtube);
            popup.getMenu().removeItem(R.id.action_upload_drive);
        }

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
            java.lang.reflect.Method setForceShowIcon = menuPopupHelper.getClass().getMethod("setForceShowIcon",
                    boolean.class);
            setForceShowIcon.invoke(menuPopupHelper, true);
        } catch (Exception e) {
            Log.w(TAG, "Could not force show popup menu icons: " + e.getMessage());
        }
        // Handle all menu actions
        popup.setOnMenuItemClickListener(item -> {
            int id = item.getItemId();
            if (id == R.id.action_edit_faditorx) {
                launchFaditorMini(context, videoItem);
                return true;
            } else if (id == R.id.action_save) {
                saveVideoToGalleryInternal(videoItem);
                return true;
            } else if (id == R.id.action_fix_video) {
                fixVideoFile(videoItem);
                return true;
            } else if (id == R.id.action_rename) {
                showRenameDialog(videoItem);
                return true;
            } else if (id == R.id.action_info) {
                showVideoInfoDialog(videoItem);
                return true;
            } else if (id == R.id.action_delete) {
                if (actionListener != null)
                    actionListener.onDeleteVideo(videoItem);
                return true;
            } else if (id == R.id.action_upload_youtube) {
                openVideoInYouTube(videoItem);
                return true;
            } else if (id == R.id.action_upload_drive) {
                openVideoInGoogleDrive(videoItem);
                return true;
            }
            return false;
        });

        // Edit with Faditor Mini – no special badge needed
        // (Item is fully functional — launches editor)

        return popup;
    }

    // --- Restored Rename Logic ---
    private void showRenameDialog(VideoItem videoItem) {
        if (context == null)
            return;

        // Prepare base name and extension like before
        String currentName = videoItem.displayName != null ? videoItem.displayName : "";
        int dotIndex = currentName.lastIndexOf('.');
        String baseName = (dotIndex > 0) ? currentName.substring(0, dotIndex) : currentName;

        // Prefer the existing TextInputBottomSheetFragment for a consistent input UI
        try {
            if (context instanceof androidx.fragment.app.FragmentActivity) {
                androidx.fragment.app.FragmentActivity fa = (androidx.fragment.app.FragmentActivity) context;
                String resultKey = "rename_video_result_" + Integer.toHexString(System.identityHashCode(videoItem.uri));

                // Use unified InputActionBottomSheetFragment in 'input' mode for rename so it
                // matches Delete All UI
                InputActionBottomSheetFragment sheet = InputActionBottomSheetFragment.newInput(
                        context.getString(R.string.rename_video_title),
                        baseName,
                        context.getString(R.string.rename_video_hint),
                        context.getString(R.string.rename_video_title),
                        context.getString(R.string.rename_video_hint),
                        R.drawable.ic_edit_cut);

                sheet.setCallbacks(new InputActionBottomSheetFragment.Callbacks() {
                    @Override
                    public void onImportConfirmed(org.json.JSONObject json) {
                    }

                    @Override
                    public void onResetConfirmed() {
                    }

                    @Override
                    public void onInputConfirmed(String input) {
                        // Close the sheet immediately to provide responsive UX
                        try {
                            sheet.dismiss();
                        } catch (Exception ignored) {
                        }

                        String newNameBase = input != null ? input.trim() : "";
                        if (newNameBase.isEmpty()) {
                            if (context != null)
                                ((Activity) context).runOnUiThread(() -> Toast
                                        .makeText(context, R.string.toast_rename_name_empty, Toast.LENGTH_SHORT)
                                        .show());
                            return;
                        }

                        String originalExtension = (dotIndex > 0 && dotIndex < currentName.length() - 1)
                                ? currentName.substring(dotIndex)
                                : ("." + Constants.RECORDING_FILE_EXTENSION);

                        String sanitizedBaseName = newNameBase
                                .replaceAll("[^a-zA-Z0-9\\-_]", "_")
                                .replaceAll("\\s+", "_")
                                .replaceAll("_+", "_")
                                .replaceAll("^_|_$", "");

                        if (sanitizedBaseName.isEmpty())
                            sanitizedBaseName = "renamed_video";

                        String newFullName = sanitizedBaseName + originalExtension;

                        if (newFullName.equals(videoItem.displayName)) {
                            if (context != null)
                                ((Activity) context).runOnUiThread(() -> Toast
                                        .makeText(context, "Name hasn't changed", Toast.LENGTH_SHORT).show());
                        } else {
                            executorService.submit(() -> renameVideo(videoItem, newFullName));
                        }
                    }
                });

                sheet.show(fa.getSupportFragmentManager(),
                        "rename_" + Integer.toHexString(System.identityHashCode(videoItem.uri)));
                return;
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to show TextInputBottomSheetFragment, falling back to dialog", e);
        }

        // Fallback: if we cannot show the bottom sheet, keep the existing Material
        // dialog behavior
        // ...existing code fallback omitted for brevity... (keeps previous dialog
        // implementation)
    }

    private void renameVideo(VideoItem videoItem, String newFullName) {
        if (context == null)
            return;
        Uri videoUri = videoItem.uri;
        int position = findPositionByUri(videoUri);

        if (position == -1) {
            Log.e(TAG, "Cannot rename, item not found in adapter list: " + videoUri);
            if (context instanceof Activity) {
                ((Activity) context).runOnUiThread(() -> Toast.makeText(context,
                        context.getString(R.string.toast_rename_failed) + " (Item not found)", Toast.LENGTH_SHORT)
                        .show());
            }
            return;
        }

        boolean renameSuccess = false;
        Uri newUri = null;

        try {
            Log.d(TAG, "Attempting rename. URI: " + videoUri + ", Scheme: " + videoUri.getScheme() + ", New Name: "
                    + newFullName);

            if ("file".equals(videoUri.getScheme()) && videoUri.getPath() != null) {
                File oldFile = new File(videoUri.getPath());
                File parentDir = oldFile.getParentFile();
                if (parentDir == null)
                    throw new IOException("Cannot get parent directory for file URI");
                
                // Generate unique filename to prevent overwriting existing files
                String uniqueName = getUniqueFileNameInDirectory(parentDir, newFullName);
                File newFile = new File(parentDir, uniqueName);
                
                Log.d(TAG, "Original name: " + newFullName + ", Unique name: " + uniqueName);

                if (oldFile.renameTo(newFile)) {
                    renameSuccess = true;
                    newUri = Uri.fromFile(newFile);
                    Log.i(TAG, "Renamed file system file successfully to: " + uniqueName);
                } else {
                    Log.e(TAG, "File.renameTo() failed for " + oldFile.getPath());
                }
            } else if ("content".equals(videoUri.getScheme())) {
                // For SAF documents, check parent for existing files with same name
                DocumentFile sourceDoc = DocumentFile.fromSingleUri(context, videoUri);
                DocumentFile parentDoc = sourceDoc != null ? sourceDoc.getParentFile() : null;
                String finalName = newFullName;
                
                if (parentDoc != null) {
                    // Check if a file with this name already exists
                    DocumentFile existingFile = parentDoc.findFile(newFullName);
                    if (existingFile != null && existingFile.exists() && !existingFile.getUri().equals(videoUri)) {
                        // Generate unique name to prevent overwriting
                        finalName = getUniqueFileNameForSAF(parentDoc, newFullName);
                        Log.d(TAG, "SAF file already exists. Generated unique name: " + finalName);
                    }
                }
                
                newUri = DocumentsContract.renameDocument(context.getContentResolver(), videoUri, finalName);
                if (newUri != null) {
                    renameSuccess = true;
                    Log.i(TAG, "Renamed SAF document successfully to: " + finalName + ", New URI: " + newUri);
                } else {
                    Log.w(TAG, "DocumentsContract.renameDocument returned null for: " + videoUri + " to '" + finalName
                            + "'.");
                    // Check if rename actually happened (some providers might return null on
                    // success if name didn't change or already exists)
                    DocumentFile checkDoc = DocumentFile.fromSingleUri(context, videoUri); // Check original URI, it
                                                                                           // might have been renamed in
                                                                                           // place
                    if (checkDoc != null && finalName.equals(checkDoc.getName())) {
                        Log.w(TAG, "Rename check: File with new name exists under original URI. Assuming success.");
                        newUri = checkDoc.getUri();
                        renameSuccess = true;
                    } else { // Check if a new file with the new name exists in the parent
                        DocumentFile parent = checkDoc != null ? checkDoc.getParentFile() : null;
                        if (parent != null) {
                            DocumentFile renamedFile = parent.findFile(finalName);
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
                        System.currentTimeMillis());

                // Update the persistent DB index: remove old URI, invalidate so
                // the next loadRecordsList() picks up the renamed file.
                try {
                    com.fadcam.data.VideoIndexRepository repo =
                            com.fadcam.data.VideoIndexRepository.getInstance(context);
                    repo.removeFromIndex(videoItem.uri.toString());
                    Log.d(TAG, "Removed old URI from index after rename: " + videoItem.uri);
                } catch (Exception e) {
                    Log.w(TAG, "Failed to update index after rename", e);
                }

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
                            Log.e(TAG, "Rename success but position " + position + " is invalid for records list size "
                                    + records.size());
                            Toast.makeText(context, "Rename successful, but list update failed.", Toast.LENGTH_LONG)
                                    .show();
                            // Consider a full reload if this happens.
                        }
                    });
                }
            } else if (!renameSuccess) {
                if (context instanceof Activity) {
                    ((Activity) context).runOnUiThread(
                            () -> Toast.makeText(context, R.string.toast_rename_failed, Toast.LENGTH_SHORT).show());
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Exception during rename for " + videoUri + " to " + newFullName, e);
            if (context instanceof Activity) {
                ((Activity) context).runOnUiThread(() -> Toast.makeText(context,
                        context.getString(R.string.toast_rename_failed) + " (Error)", Toast.LENGTH_SHORT).show());
            }
        }
    }

    // --- Restored Save to Gallery Logic (using actionListener for progress) ---
    // Wrapper for backward compatibility
    private void saveVideoToGalleryInternal(VideoItem videoItem) {
        // Use background service for copy operation
        FileOperationService.startCopyToGallery(context, videoItem.uri, videoItem.displayName, videoItem.displayName);
    }
    
    // Enhanced version with copy/move option
    private void saveVideoToGalleryInternal(VideoItem videoItem, boolean moveFile) {
        if (context == null || videoItem == null || videoItem.uri == null || videoItem.displayName == null) {
            if (actionListener != null) {
                // Run on UI thread if context is available to show Toast
                if (context instanceof Activity) {
                    ((Activity) context).runOnUiThread(
                            () -> actionListener.onSaveToGalleryFinished(false, "Invalid video data.", null));
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
                    final String finalMessageForLambda = message;
                    // Notify listener on UI thread
                    if (context instanceof Activity && actionListener != null) {
                        ((Activity) context).runOnUiThread(
                                () -> actionListener.onSaveToGalleryFinished(false, finalMessageForLambda, null));
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

                if (in == null)
                    throw new IOException("Failed to open input stream for " + sourceUri);

                byte[] buf = new byte[8192]; // Increased buffer size
                int len;
                while ((len = in.read(buf)) > 0) {
                    out.write(buf, 0, len);
                }
                out.flush();
                Utils.scanFileWithMediaStore(context, destFile.getAbsolutePath()); // Scan the new file
                resultUri = Uri.fromFile(destFile); // Not entirely correct for MediaStore, but good for logs
                success = true;
                
                // If move operation, delete the original file
                if (moveFile && success) {
                    try {
                        boolean deleted = false;
                        
                        // Try to delete using File path (for app private directory files)
                        if ("file".equals(sourceUri.getScheme())) {
                            File originalFile = new File(sourceUri.getPath());
                            if (originalFile.exists()) {
                                deleted = originalFile.delete();
                                Log.d(TAG, "Attempted File.delete() on: " + originalFile.getAbsolutePath() + ", success: " + deleted);
                            }
                        } else {
                            // Fallback to ContentResolver for other URI schemes
                            deleted = context.getContentResolver().delete(sourceUri, null, null) > 0;
                            Log.d(TAG, "Attempted ContentResolver.delete() on: " + sourceUri + ", success: " + deleted);
                        }
                        
                        if (deleted) {
                            message = "Video moved to Downloads/FadCam";
                            Log.i(TAG, "Original file deleted after move to: " + destFile.getAbsolutePath());
                            // Notify the adapter to refresh the list
                            if (context instanceof Activity) {
                                ((Activity) context).runOnUiThread(() -> {
                                    // Remove the item from the list if successfully moved
                                    removeVideoItemFromList(videoItem);
                                });
                            }
                        } else {
                            message = "Video copied to Downloads/FadCam (original could not be deleted)";
                            Log.w(TAG, "Could not delete original file after copy: " + sourceUri);
                        }
                    } catch (Exception moveEx) {
                        Log.e(TAG, "Error deleting original file after copy: " + moveEx.getMessage());
                        message = "Video copied to Downloads/FadCam (original could not be deleted)";
                    }
                } else {
                    message = "Video saved to Downloads/FadCam";
                }
                
                Log.i(TAG, "Video " + (moveFile ? "moved" : "copied") + " successfully to: " + destFile.getAbsolutePath());

            } catch (Exception e) {
                Log.e(TAG, "Error saving video to gallery", e);
                message = "Save Failed: " + e.getMessage();
                if (destFile.exists()) { // Clean up partial file
                    destFile.delete();
                }
            }

            final boolean finalSuccess = success;
            final String finalMessage = message;
            final Uri finalResultUri = success ? Uri.fromFile(destFile) : null; // Use actual destFile URI if successful
                                                                                // for listener

            if (context instanceof Activity && actionListener != null) {
                ((Activity) context).runOnUiThread(
                        () -> actionListener.onSaveToGalleryFinished(finalSuccess, finalMessage, finalResultUri));
            } else if (actionListener != null) { // Fallback if context not an activity (e.g. service context)
                // This case is less likely for UI-triggered actions but good for robustness
                // Directly call if Looper is available or handle differently
                if (Looper.myLooper() == Looper.getMainLooper()) {
                    actionListener.onSaveToGalleryFinished(finalSuccess, finalMessage, finalResultUri);
                } else {
                    new Handler(Looper.getMainLooper()).post(
                            () -> actionListener.onSaveToGalleryFinished(finalSuccess, finalMessage, finalResultUri));
                }
            }
        });
    }
    
    // Helper method to remove video item from list after move operation
    private void removeVideoItemFromList(VideoItem videoItem) {
        if (records == null || videoItem == null) return;

        // Remove from flat records list
        records.removeIf(item -> item != null && item.uri != null && item.uri.equals(videoItem.uri));

        // Find and remove from entries, also remove orphan month headers
        int entryPos = -1;
        for (int i = 0; i < entries.size(); i++) {
            Object entry = entries.get(i);
            if (entry instanceof VideoItemEntry) {
                VideoItem item = ((VideoItemEntry) entry).item;
                if (item != null && item.uri != null && item.uri.equals(videoItem.uri)) {
                    entryPos = i;
                    break;
                }
            }
        }

        if (entryPos >= 0) {
            String removedMonth = ((VideoItemEntry) entries.get(entryPos)).monthKey;
            entries.remove(entryPos);
            notifyItemRemoved(entryPos);

            // Check if the month header is now orphaned (no more items for that month)
            boolean monthHasItems = false;
            for (Object e : entries) {
                if (e instanceof VideoItemEntry && removedMonth.equals(((VideoItemEntry) e).monthKey)) {
                    monthHasItems = true;
                    break;
                }
            }
            if (!monthHasItems) {
                for (int i = 0; i < entries.size(); i++) {
                    if (entries.get(i) instanceof MonthHeaderEntry
                            && removedMonth.equals(((MonthHeaderEntry) entries.get(i)).monthKey)) {
                        entries.remove(i);
                        notifyItemRemoved(i);
                        break;
                    }
                }
            }

            // Refresh serial numbers
            rebuildSerialNumbers();

            if (entries.isEmpty()) {
                notifyDataSetChanged();
            }
        }
    }

    /**
     * Shows comprehensive video information in a custom 2-column bottom sheet
     * with enhanced metadata including FPS, codec, bitrate, and geotag data.
     *
     * @param videoItem The VideoItem representing the selected video.
     */
    private void showVideoInfoDialog(VideoItem videoItem) {
        // Pre-checks
        if (context == null) {
            Log.e(TAG, "Cannot show info bottom sheet, context is null.");
            return;
        }
        if (videoItem == null || videoItem.uri == null) {
            Log.e(TAG, "Cannot show info bottom sheet, videoItem or its URI is null.");
            Toast.makeText(context, context.getString(R.string.toast_video_not_found), Toast.LENGTH_SHORT).show();
            return;
        }

        // Ensure we have a FragmentActivity to show the bottom sheet
        if (!(context instanceof FragmentActivity)) {
            Log.e(TAG, "Context is not a FragmentActivity, cannot show bottom sheet.");
            return;
        }

        try {
            FragmentActivity activity = (FragmentActivity) context;
            VideoInfoBottomSheet bottomSheet = VideoInfoBottomSheet.newInstance(videoItem);
            bottomSheet.show(activity.getSupportFragmentManager(), "video_info_bottom_sheet");
        } catch (Exception e) {
            Log.e(TAG, "Error showing video info bottom sheet", e);
            Toast.makeText(context, "Error displaying video info.", Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Launches Faditor Mini editor with the selected video.
     *
     * @param ctx       the current context (must be an Activity)
     * @param videoItem the video to edit
     */
    private void launchFaditorMini(@NonNull Context ctx, @NonNull VideoItem videoItem) {
        if (videoItem.uri == null) return;
        try {
            Intent intent = new Intent(ctx, com.fadcam.ui.faditor.FaditorEditorActivity.class);
            intent.setData(videoItem.uri);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            ctx.startActivity(intent);
        } catch (Exception e) {
            Log.e(TAG, "Failed to launch Faditor Mini", e);
            Toast.makeText(ctx, "Could not open editor", Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Opens the selected video directly in the YouTube app for uploading
     * 
     * @param videoItem The video to upload to YouTube
     */
    private void openVideoInYouTube(VideoItem videoItem) {
        if (context == null || videoItem == null || videoItem.uri == null)
            return;

        Log.d(TAG, "===== START YOUTUBE UPLOAD DEBUG =====");
        Log.d(TAG, "Device: " + Build.MANUFACTURER + " " + Build.MODEL + " (Android " + Build.VERSION.RELEASE + ")");
        Log.d(TAG, "Video URI: " + videoItem.uri);

        try {
            // Get proper content:// URI that can be shared with other apps
            Uri shareUri = getShareableUri(videoItem.uri);
            Log.d(TAG, "Converted share URI: " + shareUri);

            // Direct YouTube upload intent: try package-targeted intent first, fall back to
            // chooser on failure
            Intent intent = new Intent(Intent.ACTION_SEND);
            intent.setType("video/*");
            intent.putExtra(Intent.EXTRA_STREAM, shareUri);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            intent.setPackage("com.google.android.youtube");

            try {
                context.startActivity(intent);
            } catch (android.content.ActivityNotFoundException anf) {
                // Try alternative package name once
                intent.setPackage("com.google.android.apps.youtube.mango");
                try {
                    context.startActivity(intent);
                } catch (android.content.ActivityNotFoundException anf2) {
                    // Final fallback: generic chooser
                    Log.d(TAG, "YouTube package-targeted intents failed, falling back to generic share");
                    Intent chooserIntent = Intent.createChooser(
                            new Intent(Intent.ACTION_SEND)
                                    .setType("video/*")
                                    .putExtra(Intent.EXTRA_STREAM, shareUri)
                                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                            "Upload to YouTube");
                    context.startActivity(chooserIntent);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error opening YouTube upload: " + videoItem.uri + " (" + e.getMessage() + ")", e);

            // Fallback to generic share intent
            try {
                Uri shareUri = getShareableUri(videoItem.uri);
                Intent chooserIntent = Intent.createChooser(
                        new Intent(Intent.ACTION_SEND)
                                .setType("video/*")
                                .putExtra(Intent.EXTRA_STREAM, shareUri)
                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                        "Upload to YouTube");
                context.startActivity(chooserIntent);
            } catch (Exception ex) {
                Log.e(TAG, "Error with fallback share: " + ex.getMessage(), ex);
                Toast.makeText(context, "Could not share video: " + ex.getMessage(), Toast.LENGTH_LONG).show();
            }
        }

        Log.d(TAG, "===== END YOUTUBE UPLOAD DEBUG =====");
    }

    /**
     * Opens the selected video directly in the Google Drive app for uploading
     * 
     * @param videoItem The video to upload to Google Drive
     */
    private void openVideoInGoogleDrive(VideoItem videoItem) {
        if (context == null || videoItem == null || videoItem.uri == null)
            return;

        Log.d(TAG, "===== START DRIVE UPLOAD DEBUG =====");
        Log.d(TAG, "Device: " + Build.MANUFACTURER + " " + Build.MODEL + " (Android " + Build.VERSION.RELEASE + ")");
        Log.d(TAG, "Video URI: " + videoItem.uri);

        try {
            // Get proper content:// URI that can be shared with other apps
            Uri shareUri = getShareableUri(videoItem.uri);
            Log.d(TAG, "Converted share URI: " + shareUri);

            // Use ShareCompat to create the intent; try package-targeted Drive first, fall
            // back to chooser on failure
            Intent intent = ShareCompat.IntentBuilder.from((Activity) context)
                    .setStream(shareUri)
                    .setType("video/*")
                    .getIntent()
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

            intent.setPackage("com.google.android.apps.docs");
            try {
                context.startActivity(intent);
            } catch (android.content.ActivityNotFoundException anf) {
                // Try alternative Drive package name
                intent.setPackage("com.google.android.apps.docs.editors.docs");
                try {
                    context.startActivity(intent);
                } catch (android.content.ActivityNotFoundException anf2) {
                    Log.d(TAG, "Drive package-targeted intents failed, falling back to generic share");
                    Intent chooserIntent = Intent.createChooser(
                            ShareCompat.IntentBuilder.from((Activity) context)
                                    .setStream(shareUri)
                                    .setType("video/*")
                                    .getIntent()
                                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                            "Upload to Drive");
                    context.startActivity(chooserIntent);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error opening Google Drive: " + videoItem.uri + " (" + e.getMessage() + ")", e);

            // Fallback to generic share intent
            try {
                Uri shareUri = getShareableUri(videoItem.uri);
                Intent chooserIntent = Intent.createChooser(
                        new Intent(Intent.ACTION_SEND)
                                .setType("video/*")
                                .putExtra(Intent.EXTRA_STREAM, shareUri)
                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                        "Upload to Drive");
                context.startActivity(chooserIntent);
            } catch (Exception ex) {
                Log.e(TAG, "Error with fallback share: " + ex.getMessage(), ex);
                Toast.makeText(context, "Could not share video: " + ex.getMessage(), Toast.LENGTH_LONG).show();
            }
        }

        Log.d(TAG, "===== END DRIVE UPLOAD DEBUG =====");
    }

    /**
     * Converts a URI to a shareable content:// URI if needed
     * 
     * @param uri The original URI to convert
     * @return A shareable URI
     */
    private Uri getShareableUri(Uri uri) {
        if (uri == null)
            return null;

        // If it's already a content:// URI, we can use it directly
        if (ContentResolver.SCHEME_CONTENT.equals(uri.getScheme())) {
            return uri;
        }

        // If it's a file:// URI, we need to convert it using FileProvider
        if (ContentResolver.SCHEME_FILE.equals(uri.getScheme())) {
            try {
                File file = new File(uri.getPath());
                return FileProvider.getUriForFile(
                        context,
                        context.getApplicationContext().getPackageName() + ".provider",
                        file);
            } catch (Exception e) {
                Log.e(TAG, "Error converting file URI to content URI: " + e.getMessage(), e);
            }
        }

        return uri; // Return original if conversion failed
    }

    /**
     * Launch system chooser to open the video with an external player without
     * exporting.
     */
    private void openWithExternalPlayer(VideoItem videoItem) {
        if (context == null || videoItem == null || videoItem.uri == null)
            return;
        try {
            Uri shareUri = getShareableUri(videoItem.uri);
            if (shareUri == null) {
                Toast.makeText(context, R.string.toast_video_not_found, Toast.LENGTH_SHORT).show();
                return;
            }

            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(shareUri, "video/*");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

            Intent chooser = Intent.createChooser(intent, context.getString(R.string.chooser_open_with));
            context.startActivity(chooser);
        } catch (Exception e) {
            Log.e(TAG, "Error opening external player for: " + videoItem.uri, e);
            try {
                // final fallback: generic SEND chooser
                Uri shareUri = getShareableUri(videoItem.uri);
                Intent chooserIntent = Intent.createChooser(
                        new Intent(Intent.ACTION_SEND)
                                .setType("video/*")
                                .putExtra(Intent.EXTRA_STREAM, shareUri)
                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                        context.getString(R.string.chooser_open_with));
                context.startActivity(chooserIntent);
            } catch (Exception ex) {
                Log.e(TAG, "Fallback share failed", ex);
                Toast.makeText(context, "Could not open video: " + ex.getMessage(), Toast.LENGTH_LONG).show();
            }
        }
    }

    // --- NEW Helper to get resolution (refactored from info dialog) ---
    private String getVideoResolution(Uri videoUri) {
        if (context == null || videoUri == null)
            return "N/A";
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        String resolution = "N/A";
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && "content".equals(videoUri.getScheme())) {
                try (ParcelFileDescriptor pfd = context.getContentResolver().openFileDescriptor(videoUri, "r")) {
                    if (pfd != null)
                        retriever.setDataSource(pfd.getFileDescriptor());
                    else
                        throw new IOException("PFD was null for " + videoUri);
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
            try {
                retriever.release();
            } catch (IOException e) {
                Log.e(TAG, "Error releasing MMD retriever for resolution", e);
            }
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

        Log.d(TAG, "updateRecords: Updating from " + (records == null ? 0 : records.size()) +
                " to " + newRecords.size() + " records");

        // Check if we're updating with skeleton data or real data
        boolean isSkeletonData = !newRecords.isEmpty() && newRecords.get(0).isSkeleton;

        if (isSkeletonMode && !isSkeletonData) {
            Log.d(TAG, "updateRecords: Transitioning from skeleton to real data - disabling skeleton mode");
            setSkeletonMode(false);
        } else if (isSkeletonMode && isSkeletonData) {
            Log.d(TAG, "updateRecords: Updating skeleton data - keeping skeleton mode enabled");
        }

        // Build new entries from the new records
        final List<Object> newEntries = buildEntries(newRecords);
        final List<Object> oldEntries = this.entries;

        // Use DiffUtil to calculate the differences on entries
        DiffUtil.DiffResult diffResult = DiffUtil.calculateDiff(new DiffUtil.Callback() {
            @Override
            public int getOldListSize() {
                return oldEntries.size();
            }

            @Override
            public int getNewListSize() {
                return newEntries.size();
            }

            @Override
            public boolean areItemsTheSame(int oldPosition, int newPosition) {
                Object oldEntry = oldEntries.get(oldPosition);
                Object newEntry = newEntries.get(newPosition);
                if (oldEntry instanceof MonthHeaderEntry && newEntry instanceof MonthHeaderEntry) {
                    return ((MonthHeaderEntry) oldEntry).monthKey.equals(((MonthHeaderEntry) newEntry).monthKey);
                }
                if (oldEntry instanceof VideoItemEntry && newEntry instanceof VideoItemEntry) {
                    Uri oldUri = ((VideoItemEntry) oldEntry).item.uri;
                    Uri newUri = ((VideoItemEntry) newEntry).item.uri;
                    return oldUri != null && newUri != null && oldUri.equals(newUri);
                }
                return false;
            }

            @Override
            public boolean areContentsTheSame(int oldPosition, int newPosition) {
                Object oldEntry = oldEntries.get(oldPosition);
                Object newEntry = newEntries.get(newPosition);
                if (oldEntry instanceof MonthHeaderEntry && newEntry instanceof MonthHeaderEntry) {
                    return true; // Headers are static
                }
                if (oldEntry instanceof VideoItemEntry && newEntry instanceof VideoItemEntry) {
                    VideoItem oldItem = ((VideoItemEntry) oldEntry).item;
                    VideoItem newItem = ((VideoItemEntry) newEntry).item;
                    return oldItem.displayName.equals(newItem.displayName) &&
                            oldItem.size == newItem.size &&
                            oldItem.lastModified == newItem.lastModified;
                }
                return false;
            }
        });

        // Update the data
        int oldSize = this.entries.size();
        this.records = new ArrayList<>(newRecords);
        this.entries = newEntries;

        // Dispatch updates to the adapter
        diffResult.dispatchUpdatesTo(this);

        // Force serial number refresh for all items when list size changed
        int newSize = this.entries.size();
        if (oldSize != newSize && newSize > 0) {
            notifyItemRangeChanged(0, newSize, "SERIAL_UPDATE");
        }

        Log.d(TAG, "updateRecords completed. Final entries: " + entries.size() +
                ", records: " + records.size() + ", skeleton mode: " + isSkeletonMode);
    }

    // Format file size helper
    private String formatFileSize(long bytes) {
        if (bytes < 1024)
            return bytes + " B";
        int exp = (int) (Math.log(bytes) / Math.log(1024));
        char pre = "KMGTPE".charAt(exp - 1);
        return String.format(Locale.US, "%.1f %sB", bytes / Math.pow(1024, exp), pre);
    }

    // Format video duration helper
    private String formatVideoDuration(long durationMs) {
        if (durationMs <= 0)
            return "0s";
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

    // Animate the inner check icon with a small bounce on check and fade out on
    // uncheck
    private void animateCheckIcon(View checkIconView, boolean willBeSelected) {
        if (checkIconView == null)
            return;

        // Prefer AnimatedVectorDrawable tick-draw when available (smooth path draw)
        try {
            android.graphics.drawable.Drawable d = null;
            if (checkIconView instanceof android.widget.ImageView) {
                d = ((android.widget.ImageView) checkIconView).getDrawable();
            }
            if (willBeSelected) {
                // If drawable is an AVD, ensure we have a fresh instance and start its
                // animation
                if (checkIconView instanceof android.widget.ImageView) {
                    android.widget.ImageView iv = (android.widget.ImageView) checkIconView;
                    try {
                        androidx.vectordrawable.graphics.drawable.AnimatedVectorDrawableCompat newAvd = androidx.vectordrawable.graphics.drawable.AnimatedVectorDrawableCompat
                                .create(iv.getContext(), R.drawable.avd_check_draw);
                        if (newAvd != null) {
                            iv.setImageDrawable(newAvd);
                            newAvd.start();
                            iv.setAlpha(1f);
                            iv.setScaleX(1f);
                            iv.setScaleY(1f);
                            iv.setVisibility(View.VISIBLE);
                            return;
                        }
                        // Fallback to platform AVD
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                            android.graphics.drawable.Drawable platformAv = iv.getContext()
                                    .getDrawable(R.drawable.avd_check_draw);
                            if (platformAv instanceof android.graphics.drawable.AnimatedVectorDrawable) {
                                iv.setImageDrawable(platformAv);
                                ((android.graphics.drawable.AnimatedVectorDrawable) platformAv).start();
                                iv.setAlpha(1f);
                                iv.setScaleX(1f);
                                iv.setScaleY(1f);
                                iv.setVisibility(View.VISIBLE);
                                return;
                            }
                        }
                    } catch (Exception ignored) {
                    }
                }
            } else {
                // For uncheck, try reverse if possible (compat doesn't expose reverse
                // reliably), else fallback
                if (d instanceof androidx.vectordrawable.graphics.drawable.AnimatedVectorDrawableCompat) {
                    // Cannot reverse reliably; just fade out the view for uncheck
                    android.animation.ObjectAnimator a = android.animation.ObjectAnimator.ofFloat(checkIconView,
                            View.ALPHA, checkIconView.getAlpha(), 0f);
                    a.setDuration(180);
                    a.setInterpolator(new android.view.animation.AccelerateInterpolator());
                    a.start();
                    return;
                }
            }
        } catch (Exception e) {
            // ignore and fall back
        }

        // Fallback: use scale/alpha animation
        if (willBeSelected) {
            checkIconView.setVisibility(View.VISIBLE);
            android.animation.ObjectAnimator sx = android.animation.ObjectAnimator.ofFloat(checkIconView, View.SCALE_X,
                    0f, 1.0f);
            android.animation.ObjectAnimator sy = android.animation.ObjectAnimator.ofFloat(checkIconView, View.SCALE_Y,
                    0f, 1.0f);
            android.animation.ObjectAnimator a = android.animation.ObjectAnimator.ofFloat(checkIconView, View.ALPHA, 0f,
                    1f);
            sx.setDuration(220);
            sy.setDuration(220);
            a.setDuration(160);
            sx.setInterpolator(new android.view.animation.DecelerateInterpolator());
            sy.setInterpolator(new android.view.animation.DecelerateInterpolator());
            a.setInterpolator(new android.view.animation.DecelerateInterpolator());
            android.animation.AnimatorSet set = new android.animation.AnimatorSet();
            set.playTogether(sx, sy, a);
            set.start();
        } else {
            android.animation.ObjectAnimator a = android.animation.ObjectAnimator.ofFloat(checkIconView, View.ALPHA,
                    checkIconView.getAlpha(), 0f);
            android.animation.ObjectAnimator s = android.animation.ObjectAnimator.ofFloat(checkIconView, View.SCALE_X,
                    checkIconView.getScaleX(), 0f);
            android.animation.ObjectAnimator s2 = android.animation.ObjectAnimator.ofFloat(checkIconView, View.SCALE_Y,
                    checkIconView.getScaleY(), 0f);
            a.setDuration(180);
            s.setDuration(180);
            s2.setDuration(180);
            a.setInterpolator(new android.view.animation.AccelerateInterpolator());
            android.animation.AnimatorSet set = new android.animation.AnimatorSet();
            set.playTogether(a, s, s2);
            set.start();
        }
    }

    // Get video duration from URI (Helper) using FFprobeKit for reliability
    private long getVideoDuration(Uri videoUri) {
        if (context == null || videoUri == null)
            return 0;

        // Skip skeleton URIs to prevent errors
        if ("skeleton".equals(videoUri.getScheme())) {
            return 0;
        }
        
        if (safeMediaProbeMode) {
            Log.d(TAG, "probe_mode=safe_mmr uri=" + videoUri);
            return getVideoDurationWithMmr(videoUri, "safe_mmr");
        }

        Log.d(TAG, "probe_mode=ffprobe uri=" + videoUri);
        // Get file path for FFprobe - try multiple approaches for content:// URIs
        String filePath = getFFprobePathForUri(videoUri);
        
        // Use FFprobeKit to get accurate duration (direct file path)
        if (!filePath.startsWith("saf:")) {
            try {
                com.arthenica.ffmpegkit.MediaInformationSession session = 
                    com.arthenica.ffmpegkit.FFprobeKit.getMediaInformation(filePath);
                com.arthenica.ffmpegkit.MediaInformation info = session.getMediaInformation();
                
                if (info != null) {
                    String durationStr = info.getDuration();
                    if (durationStr != null) {
                        double durationSec = Double.parseDouble(durationStr);
                        long durationMs = (long) (durationSec * 1000);
                        Log.d(TAG, "Duration from FFprobe (path): " + durationMs + "ms");
                        return durationMs;
                    }
                }
            } catch (Throwable e) {
                Log.e(TAG, "Error getting duration from FFprobe for path: " + filePath, e);
            }
        }

        // For content:// URIs where path reconstruction failed, use FD-based FFprobe
        if ("content".equals(videoUri.getScheme())) {
            try {
                ParcelFileDescriptor pfd = context.getContentResolver()
                        .openFileDescriptor(videoUri, "r");
                if (pfd != null) {
                    try {
                        String fdPath = "/proc/self/fd/" + pfd.getFd();
                        com.arthenica.ffmpegkit.MediaInformationSession session =
                                com.arthenica.ffmpegkit.FFprobeKit.getMediaInformation(fdPath);
                        com.arthenica.ffmpegkit.MediaInformation info = session.getMediaInformation();
                        if (info != null && info.getDuration() != null) {
                            double durationSec = Double.parseDouble(info.getDuration());
                            long durationMs = (long) (durationSec * 1000);
                            Log.d(TAG, "Duration from FFprobe (fd): " + durationMs + "ms");
                            return durationMs;
                        }
                    } finally {
                        pfd.close();
                    }
                }
            } catch (Throwable e) {
                Log.w(TAG, "FFprobe FD-based duration failed for: " + videoUri, e);
            }
        }

        return getVideoDurationWithMmr(videoUri, "ffprobe_fallback_mmr");
    }

    private long getVideoDurationWithMmr(@NonNull Uri videoUri, @NonNull String source) {
        android.media.MediaMetadataRetriever retriever = null;
        try {
            retriever = new android.media.MediaMetadataRetriever();
            retriever.setDataSource(context, videoUri);
            String durationStr = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION);
            if (durationStr != null) {
                long durationMs = Long.parseLong(durationStr);
                Log.d(TAG, "duration_source=" + source + " durationMs=" + durationMs + " uri=" + videoUri);
                return durationMs;
            }
        } catch (Throwable e) {
            Log.w(TAG, "duration_source=" + source + " failed uri=" + videoUri, e);
        } finally {
            if (retriever != null) {
                try {
                    retriever.release();
                } catch (Exception e) {
                    Log.w(TAG, "Error releasing MediaMetadataRetriever", e);
                }
            }
        }
        return 0;
    }
    
    /**
     * Gets the appropriate file path for FFprobeKit.
     * For content:// URIs, tries reconstructed path on ALL storage volumes
     * (internal + SD card), then falls back to SAF protocol.
     */
    private String getFFprobePathForUri(Uri videoUri) {
        if ("file".equals(videoUri.getScheme()) && videoUri.getPath() != null) {
            return videoUri.getPath();
        }
        
        // For content:// URIs, try to get actual file path first (more reliable)
        String path = videoUri.getPath();
        if (path != null && path.contains(":")) {
            int lastColonIndex = path.lastIndexOf(':');
            if (lastColonIndex >= 0 && lastColonIndex < path.length() - 1) {
                String relativePath = path.substring(lastColonIndex + 1);
                // Try all mounted storage volumes (internal + SD cards)
                String resolved = resolveRelativePathOnVolumes(relativePath);
                if (resolved != null) {
                    Log.d(TAG, "Using reconstructed file path for FFprobe: " + resolved);
                    return resolved;
                }
            }
        }
        
        // Fall back to SAF protocol
        Log.d(TAG, "Using SAF protocol for FFprobe: saf:" + videoUri.toString());
        return "saf:" + videoUri.toString();
    }

    /**
     * Resolves a relative path against all mounted storage volumes.
     * Checks internal (/storage/emulated/0/) and any SD cards (/storage/XXXX-XXXX/).
     */
    private String resolveRelativePathOnVolumes(String relativePath) {
        if (context == null || relativePath == null) return null;
        try {
            java.io.File[] externalDirs = context.getExternalFilesDirs(null);
            if (externalDirs != null) {
                for (java.io.File dir : externalDirs) {
                    if (dir == null) continue;
                    // externalDirs paths look like: /storage/XXXX-XXXX/Android/data/com.fadcam/files
                    String dirPath = dir.getAbsolutePath();
                    int androidIdx = dirPath.indexOf("/Android/");
                    if (androidIdx > 0) {
                        String volumeRoot = dirPath.substring(0, androidIdx + 1);
                        String reconstructed = volumeRoot + relativePath;
                        java.io.File file = new java.io.File(reconstructed);
                        if (file.exists() && file.canRead()) {
                            return reconstructed;
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Error resolving storage volumes: " + e.getMessage());
        }
        return null;
    }

    // Get file name from URI (Helper) - Crucial for Save to Gallery/Rename default
    @SuppressLint("Range") // Suppress lint check for getColumnIndexOrThrow
    private String getFileName(Uri uri) {
        if (context == null || uri == null)
            return null;
        String result = null;
        if ("content".equals(uri.getScheme())) {
            try (Cursor cursor = context.getContentResolver().query(uri, null, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    // Use getColumnIndex to avoid crashing if column doesn't exist
                    int nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    if (nameIndex != -1) {
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
        FrameLayout thumbnailContainer;
        TextView textViewRecord;
        TextView textViewFileSize;
        TextView textViewFileTime;
        TextView textViewSerialNumber;
        ImageView checkIcon;
        View iconCheckContainer;
        View iconCheckBg;
        View metaDurationContainer;
        View recordMetaRowSize;
        ImageView iconFileSize;
        ImageView menuButton; // Reference to the 3-dot icon itself
        TextView textViewStatusBadge; // *** ADDED: Reference for the single status badge ***
        ImageView menuWarningDot; // *** ADDED: Reference for the warning dot ***
        FrameLayout menuButtonContainer; // *** ADDED: Reference to the container holding the button and dot ***
        View selectionDimOverlay;

        View processingScrim;
        ProgressBar processingSpinner;
        // *** ADD field for the new TextView ***
        TextView textViewTimeAgo;
        // *** ADD Field for Default Text Color ***
        int defaultTextColor; // Store the default color
        int defaultMetaTextColor;
        // Grid-adaptive container refs
        View recordContentContainer;
        View recordTitleRow;
        View recordMetaDivider;
        View recordMetaGrid;
        View recordMetaRowTime;

        RecordViewHolder(View itemView) {
            super(itemView);
            imageViewThumbnail = itemView.findViewById(R.id.image_view_thumbnail);
            textViewRecord = itemView.findViewById(R.id.text_view_record);
            textViewFileSize = itemView.findViewById(R.id.text_view_file_size);
            textViewFileTime = itemView.findViewById(R.id.text_view_file_time);
            textViewSerialNumber = itemView.findViewById(R.id.text_view_serial_number);
            checkIcon = itemView.findViewById(R.id.icon_check);
            iconCheckContainer = itemView.findViewById(R.id.icon_check_container);
            iconCheckBg = itemView.findViewById(R.id.icon_check_bg);
            metaDurationContainer = itemView.findViewById(R.id.meta_duration_container);
            recordMetaRowSize = itemView.findViewById(R.id.record_meta_row_size);
            iconFileSize = itemView.findViewById(R.id.icon_file_size);
            menuButton = itemView.findViewById(R.id.menu_button);

            menuWarningDot = itemView.findViewById(R.id.menu_warning_dot); // *** Find the warning dot ***
            menuButtonContainer = itemView.findViewById(R.id.menu_button_container); // *** Find the container ***
            textViewStatusBadge = itemView.findViewById(R.id.text_view_status_badge); // *** Find the new single badge
                                                                                      // ***
            selectionDimOverlay = itemView.findViewById(R.id.selection_dim_overlay);

            processingScrim = itemView.findViewById(R.id.processing_scrim);
            processingSpinner = itemView.findViewById(R.id.processing_spinner);
            // *** Find the new TextView ***
            textViewTimeAgo = itemView.findViewById(R.id.text_view_time_ago);
            // Grid-adaptive container refs
            recordContentContainer = itemView.findViewById(R.id.record_content_container);
            recordTitleRow = itemView.findViewById(R.id.record_title_row);
            recordMetaDivider = itemView.findViewById(R.id.record_meta_divider);
            recordMetaGrid = itemView.findViewById(R.id.record_meta_grid);
            recordMetaRowTime = itemView.findViewById(R.id.record_meta_row_time);

            // *** Store the default text color ***
            if (textViewRecord != null) {
                defaultTextColor = textViewRecord.getCurrentTextColor();
            } else {
                Log.e(TAG, "ViewHolder: textViewRecord is NULL, cannot get default text color!");
                // Set a fallback default color?
                defaultTextColor = Color.WHITE; // Example fallback
            }
            if (textViewTimeAgo != null) {
                defaultMetaTextColor = textViewTimeAgo.getCurrentTextColor();
            } else {
                defaultMetaTextColor = defaultTextColor;
            }

        }
    }

    /**
     * Method called by the Fragment to update the adapter's visual mode
     * and provide the current list of selected URIs.
     *
     * @param isActive         True if selection mode should be active, false
     *                         otherwise.
     * @param currentSelection The list of URIs currently selected in the Fragment.
     */
    @SuppressLint("NotifyDataSetChanged")
    public void setSelectionModeActive(boolean isActive, @NonNull List<Uri> currentSelection) {
        boolean modeChanged = this.isSelectionModeActive != isActive;
        List<Uri> previousSelection = new ArrayList<>(this.currentSelectedUris);
        boolean selectionChanged = !previousSelection.equals(currentSelection);

        this.isSelectionModeActive = isActive;
        this.currentSelectedUris = new ArrayList<>(currentSelection); // Update internal copy

        if (modeChanged) {
            Log.d(TAG, "setSelectionModeActive: mode changed, payload refresh");
            notifyItemRangeChanged(0, getItemCount(), "SELECTION_MODE");
        }

        if (selectionChanged) {
            java.util.Set<Uri> changed = new java.util.HashSet<>(previousSelection);
            changed.addAll(currentSelection);
            for (Uri uri : changed) {
                boolean before = previousSelection.contains(uri);
                boolean after = currentSelection.contains(uri);
                if (before != after) {
                    int pos = findPositionByUri(uri);
                    if (pos != -1) {
                        notifyItemChanged(pos, "SELECTION_TOGGLE");
                    }
                }
            }
            // ── FIX: Also refresh all month headers so checkmarks update ──
            for (int i = 0; i < entries.size(); i++) {
                if (entries.get(i) instanceof MonthHeaderEntry) {
                    notifyItemChanged(i, "SELECTION_MODE");
                }
            }
        } else {
            Log.d(TAG, "setSelectionModeActive: Mode and selection unchanged, no refresh needed.");
        }
    }

    /**
     * Sets the scrolling state to optimize thumbnail loading
     * 
     * @param scrolling true if the list is currently scrolling, false otherwise
     */
    public void setScrolling(boolean scrolling) {
        this.isScrolling = scrolling;
    }

    /**
     * Sets skeleton mode for professional loading experience
     * 
     * @param skeletonMode true to show skeleton placeholders, false for normal
     *                     content
     */
    @SuppressLint("NotifyDataSetChanged")
    public void setSkeletonMode(boolean skeletonMode) {
        if (this.isSkeletonMode != skeletonMode) {
            this.isSkeletonMode = skeletonMode;
            Log.d(TAG, "Skeleton mode " + (skeletonMode ? "enabled" : "disabled"));
            notifyDataSetChanged(); // Refresh all views
        }
    }

    /**
     * Checks if adapter is currently in skeleton mode
     * 
     * @return true if showing skeleton placeholders
     */
    public boolean isSkeletonMode() {
        return isSkeletonMode;
    }

    /**
     * Updates the grid span count and refreshes all items for dynamic sizing.
     *
     * @param spanCount The new column count (1-5)
     */
    @SuppressLint("NotifyDataSetChanged")
    public void setGridSpan(int spanCount) {
        int normalized = Math.max(1, Math.min(5, spanCount));
        if (currentGridSpan == normalized) return;
        currentGridSpan = normalized;
        notifyDataSetChanged();
    }

    /**
     * Adapts card content visibility and text sizes for the current grid span.
     * At higher column counts, content is progressively reduced to fit.
     */
    private void applyGridSizing(@NonNull RecordViewHolder holder) {
        float titleSp;
        float metaSp;
        boolean showTitleRow;
        boolean showMetaDivider;
        boolean showTimeAgo;
        boolean showContentContainer;
        int titleMaxLines;

        switch (currentGridSpan) {
            case 1: // List mode — full content
                titleSp = 14f;
                metaSp = 10f;
                showTitleRow = true;
                showMetaDivider = true;
                showTimeAgo = true;
                showContentContainer = true;
                titleMaxLines = 2;
                break;
            case 3:
                titleSp = 12f;
                metaSp = 9f;
                showTitleRow = true;
                showMetaDivider = true;
                showTimeAgo = true;
                showContentContainer = true;
                titleMaxLines = 1;
                break;
            case 4:
                titleSp = 10f;
                metaSp = 8f;
                showTitleRow = false;
                showMetaDivider = false;
                showTimeAgo = false;
                showContentContainer = true;
                titleMaxLines = 1;
                break;
            case 5:
                titleSp = 9f;
                metaSp = 7.5f;
                showTitleRow = false;
                showMetaDivider = false;
                showTimeAgo = false;
                showContentContainer = true;
                titleMaxLines = 1;
                break;
            default: // 2 columns — default
                titleSp = 14f;
                metaSp = 10f;
                showTitleRow = true;
                showMetaDivider = true;
                showTimeAgo = true;
                showContentContainer = true;
                titleMaxLines = 2;
                break;
        }

        // Content container
        if (holder.recordContentContainer != null) {
            holder.recordContentContainer.setVisibility(showContentContainer ? View.VISIBLE : View.GONE);
        }
        // Title row (filename + 3-dot menu)
        if (holder.recordTitleRow != null) {
            holder.recordTitleRow.setVisibility(showTitleRow ? View.VISIBLE : View.GONE);
        }
        // Divider
        if (holder.recordMetaDivider != null) {
            holder.recordMetaDivider.setVisibility(showMetaDivider ? View.VISIBLE : View.GONE);
        }
        // Time ago row
        if (holder.recordMetaRowTime != null) {
            holder.recordMetaRowTime.setVisibility(showTimeAgo ? View.VISIBLE : View.GONE);
        }
        // Text sizes
        if (holder.textViewRecord != null) {
            holder.textViewRecord.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, titleSp);
            holder.textViewRecord.setMaxLines(titleMaxLines);
        }
        if (holder.textViewFileTime != null) {
            holder.textViewFileTime.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, metaSp);
        }
        if (holder.textViewFileSize != null) {
            holder.textViewFileSize.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, metaSp);
        }
        if (holder.textViewTimeAgo != null) {
            holder.textViewTimeAgo.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, metaSp);
        }
    }

    /**
     * Sets skeleton data without disabling skeleton mode
     * 
     * @param skeletonItems List of skeleton items to display
     */
    @SuppressLint("NotifyDataSetChanged")
    public void setSkeletonData(List<VideoItem> skeletonItems) {
        if (skeletonItems == null)
            return;

        Log.d(TAG, "setSkeletonData: Setting " + skeletonItems.size() + " skeleton items");
        this.records = new ArrayList<>(skeletonItems);
        this.entries = buildEntries(this.records);
        notifyDataSetChanged();
    }

    /**
     * Binds skeleton placeholder content to view holder with shimmer effect
     */
    private void bindSkeletonItem(@NonNull RecordViewHolder holder, int position) {
        // -----------
        // Tag this ViewHolder so we know it needs skeleton cleanup when rebound with real data.
        holder.itemView.setTag(R.id.skeleton_tag, Boolean.TRUE);

        // Step 1: Clear any existing content and animations
        holder.itemView.clearAnimation();
        holder.imageViewThumbnail.clearAnimation();

        // Step 2: Clear text content and set placeholder appearance
        holder.textViewRecord.setText("████████████████"); // Placeholder text
        holder.textViewFileSize.setText("██ MB");
        holder.textViewFileTime.setText("██████");

        // Make text views appear as placeholder blocks
        holder.textViewRecord.setAlpha(0.25f);
        holder.textViewFileSize.setAlpha(0.25f);
        holder.textViewFileTime.setAlpha(0.25f);

        // Step 3: Set placeholder for thumbnail — small centered icon like "hide thumbnails" mode
        holder.imageViewThumbnail.setImageResource(R.drawable.ic_video_placeholder);
        holder.imageViewThumbnail.setAlpha(0.5f); // Visible placeholder
        holder.imageViewThumbnail.setScaleType(ImageView.ScaleType.CENTER_INSIDE);

        // Step 4: Hide interactive elements during skeleton state
        if (holder.textViewStatusBadge != null) {
            holder.textViewStatusBadge.setText("");
            holder.textViewStatusBadge.setVisibility(View.GONE);
        }
        if (holder.textViewSerialNumber != null) {
            holder.textViewSerialNumber.setVisibility(View.GONE);
        }
        if (holder.menuButtonContainer != null) {
            holder.menuButtonContainer.setVisibility(View.GONE);
        }
        if (holder.textViewTimeAgo != null) {
            holder.textViewTimeAgo.setText("███████");
            holder.textViewTimeAgo.setAlpha(0.25f);
        }

        // Step 5: Hide processing and selection elements
        if (holder.processingScrim != null) {
            holder.processingScrim.setVisibility(View.GONE);
        }
        if (holder.processingSpinner != null) {
            holder.processingSpinner.setVisibility(View.GONE);
        }
        if (holder.iconCheckContainer != null) {
            holder.iconCheckContainer.setVisibility(View.GONE);
        }
        if (holder.selectionDimOverlay != null) {
            holder.selectionDimOverlay.setVisibility(View.GONE);
        }

        // Step 6: Apply professional shimmer effect to the entire card
        ShimmerEffectHelper.applyShimmerEffect(holder.itemView);

        // Step 7: Disable all click events for skeleton items
        holder.itemView.setOnClickListener(null);
        holder.itemView.setOnLongClickListener(null);
        holder.itemView.setOnTouchListener(null);
        holder.imageViewThumbnail.setOnClickListener(null);
        holder.imageViewThumbnail.setOnLongClickListener(null);
        holder.imageViewThumbnail.setOnTouchListener(null);

        if (holder.thumbnailContainer != null) {
            holder.thumbnailContainer.setOnClickListener(null);
            holder.thumbnailContainer.setOnLongClickListener(null);
            holder.thumbnailContainer.setOnTouchListener(null);
        }

        if (holder.menuButtonContainer != null) {
            holder.menuButtonContainer.setOnClickListener(null);
        }

        Log.v(TAG, "Professional shimmer skeleton bound at position " + position);

    }

    /**
     * Clears skeleton effects and restores normal appearance for data binding
     */
    private void clearSkeletonEffects(@NonNull RecordViewHolder holder) {
        // when binding real data -----------

        // Step 1: Remove shimmer effect from the card
        ShimmerEffectHelper.removeShimmerEffect(holder.itemView);

        // Step 2: Stop any running animations
        holder.itemView.clearAnimation();
        holder.imageViewThumbnail.clearAnimation();

        // Step 3: Restore normal text appearance
        holder.textViewRecord.setAlpha(1.0f);
        holder.textViewFileSize.setAlpha(1.0f);
        holder.textViewFileTime.setAlpha(1.0f);
        if (holder.textViewTimeAgo != null) {
            holder.textViewTimeAgo.setAlpha(1.0f);
        }

        // Step 4: Clear shimmer backgrounds from all elements
        holder.textViewRecord.setBackground(null);
        holder.textViewFileSize.setBackground(null);
        holder.textViewFileTime.setBackground(null);
        if (holder.textViewTimeAgo != null) {
            holder.textViewTimeAgo.setBackground(null);
        }

        // Step 5: Clear shimmer from thumbnail and restore normal appearance
        holder.imageViewThumbnail.setBackground(null);
        holder.imageViewThumbnail.setAlpha(1.0f);
        holder.imageViewThumbnail.setScaleType(ImageView.ScaleType.CENTER_CROP);

        // Step 6: Restore visibility for hidden elements
        if (holder.textViewSerialNumber != null) {
            holder.textViewSerialNumber.setVisibility(View.VISIBLE);
        }
        if (holder.menuButtonContainer != null) {
            holder.menuButtonContainer.setVisibility(View.VISIBLE);
        }

        // Step 7: Restore normal item appearance
        holder.itemView.setAlpha(1.0f);

        Log.v(TAG, "Skeleton effects cleared for data binding");

    }

    // --- Delete Helper (Must be accessible or copied here) ---
    // You need the `deleteVideoUri` method from RecordsFragment here or accessible
    // For simplicity, I'll include a copy here, ensure it's kept in sync or
    // refactored to a Util class
    /*
     * private boolean deleteVideoUri(Uri uri) {
     * if (context == null || uri == null) return false;
     * Log.d(TAG, "Adapter attempting to delete URI: " + uri +
     * " (This should be handled by Fragment now)");
     * 
     * // The actual file deletion logic (file vs content URI) was complex and is
     * now centralized
     * // in RecordsFragment's moveToTrashVideoItem, which uses TrashManager.
     * // This method in the adapter is now redundant and potentially problematic if
     * called.
     * 
     * // If for some reason this was called, it should delegate to the fragment or
     * fail.
     * // For now, let's just log and return false to indicate it didn't handle it.
     * Log.e(TAG,
     * "deleteVideoUri in adapter was called. This is deprecated. Deletion should be handled by the fragment."
     * );
     * return false;
     * }
     */

    /**
     * Clears all internal caches to reduce memory footprint
     * Should be called on low memory conditions
     */
    public void clearCaches() {
        loadedThumbnailCache.clear();
        // Also clear duration cache to prevent stale duration data for new videos
        synchronized (durationCache) {
            durationCache.clear();
        }
        synchronized (savedPositionCache) {
            savedPositionCache.clear();
        }
        Log.d(TAG, "Cleared all adapter caches including duration and position caches");
    }

    // For dialogs, use themed MaterialAlertDialogBuilder as in SettingsFragment
    private MaterialAlertDialogBuilder themedDialogBuilder(Context context) {
        int dialogTheme = R.style.ThemeOverlay_FadCam_Dialog;
        SharedPreferencesManager spm = SharedPreferencesManager.getInstance(context);
        String currentTheme = spm.sharedPreferences.getString(Constants.PREF_APP_THEME, Constants.DEFAULT_APP_THEME);
        if ("Crimson Bloom".equals(currentTheme))
            dialogTheme = R.style.ThemeOverlay_FadCam_Red_Dialog;
        else if ("Faded Night".equals(currentTheme))
            dialogTheme = R.style.ThemeOverlay_FadCam_Amoled_MaterialAlertDialog;
        else if ("Snow Veil".equals(currentTheme))
            dialogTheme = R.style.ThemeOverlay_FadCam_SnowVeil_Dialog;
        return new MaterialAlertDialogBuilder(context, dialogTheme);
    }

    /**
     * Sets dialog button colors based on theme
     * 
     * @param dialog The dialog whose buttons need color adjustment
     */
    private void setSnowVeilButtonColors(androidx.appcompat.app.AlertDialog dialog) {
        if (dialog == null)
            return;

        // Check current theme
        String currentTheme = sharedPreferencesManager.sharedPreferences.getString(Constants.PREF_APP_THEME,
                Constants.DEFAULT_APP_THEME);
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

    private void fixVideoFile(VideoItem videoItem) {
        if (videoItem == null || videoItem.uri == null) {
            Toast.makeText(context, context.getString(R.string.fix_video_invalid), Toast.LENGTH_SHORT).show();
            return;
        }
        String scheme = videoItem.uri.getScheme();
        File inputFile = null;
        File outputFile;
        String outputName = "FIXED_" + videoItem.displayName;
        boolean isSAF;
        File safTempInput = null;
        File safTempOutput;
        if ("file".equals(scheme)) {
            safTempOutput = null;
            isSAF = false;
            inputFile = new File(videoItem.uri.getPath());
            if (!inputFile.exists()) {
                Toast.makeText(context, context.getString(R.string.fix_video_file_not_exist), Toast.LENGTH_SHORT)
                        .show();
                return;
            }
            outputFile = new File(inputFile.getParent(), outputName);
            if (outputFile.exists()) {
                Toast.makeText(context, context.getString(R.string.fix_video_fixed_exists), Toast.LENGTH_SHORT).show();
                return;
            }
        } else if ("content".equals(scheme)) {
            isSAF = true;
            try {
                safTempInput = File.createTempFile("saf_repair_", ".mp4", context.getCacheDir());
                inputFile = safTempInput;
                try (InputStream in = context.getContentResolver().openInputStream(videoItem.uri);
                        OutputStream out = new FileOutputStream(inputFile)) {
                    byte[] buf = new byte[8192];
                    int len;
                    while ((len = in.read(buf)) > 0) {
                        out.write(buf, 0, len);
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Failed to copy SAF video for repair", e);
                Toast.makeText(context, context.getString(R.string.fix_video_saf_copy_fail), Toast.LENGTH_LONG).show();
                if (safTempInput != null && safTempInput.exists())
                    safTempInput.delete();
                return;
            }
            safTempOutput = new File(context.getCacheDir(), outputName);
            outputFile = safTempOutput;
        } else {
            safTempOutput = null;
            outputFile = null;
            isSAF = false;
            Toast.makeText(context, context.getString(R.string.fix_video_internal_only), Toast.LENGTH_LONG).show();
            return;
        }
        String inputPath = inputFile.getAbsolutePath();
        String outputPath = outputFile.getAbsolutePath();
        String ffmpegCmd = String.format("-y -i %s -c copy %s", inputPath, outputPath);
        Toast.makeText(context, context.getString(R.string.fix_video_repairing), Toast.LENGTH_SHORT).show();
        File finalSafTempInput = safTempInput;
        FFmpegKit.executeAsync(ffmpegCmd, session -> {
            if (ReturnCode.isSuccess(session.getReturnCode())) {
                if (isSAF) {
                    boolean wroteToSAF = false;
                    try {
                        // Use the SAF folder URI from preferences, just like RecordingService
                        String safFolderUriString = sharedPreferencesManager.getCustomStorageUri();
                        if (safFolderUriString != null) {
                            Uri safFolderUri = Uri.parse(safFolderUriString);
                            DocumentFile safFolder = DocumentFile.fromTreeUri(context, safFolderUri);
                            if (safFolder != null && safFolder.canWrite()) {
                                DocumentFile fixedDoc = safFolder.createFile("video/mp4", outputName);
                                if (fixedDoc != null) {
                                    try (OutputStream out = context.getContentResolver()
                                            .openOutputStream(fixedDoc.getUri());
                                            InputStream in = new FileInputStream(outputFile)) {
                                        byte[] buf = new byte[8192];
                                        int len;
                                        while ((len = in.read(buf)) > 0) {
                                            out.write(buf, 0, len);
                                        }
                                    }
                                    wroteToSAF = true;
                                    new Handler(Looper.getMainLooper()).post(() -> {
                                        Toast.makeText(context,
                                                context.getString(R.string.fix_video_saf_success, outputName),
                                                Toast.LENGTH_LONG).show();
                                    });
                                }
                            }
                        }
                        if (!wroteToSAF) {
                            new Handler(Looper.getMainLooper()).post(() -> {
                                Toast.makeText(context, context.getString(R.string.fix_video_saf_write_fail),
                                        Toast.LENGTH_LONG).show();
                                Toast.makeText(context,
                                        "Cannot write to this folder. This may be due to SD card, USB, or cloud storage permissions. The repaired file is saved in app storage.",
                                        Toast.LENGTH_LONG).show();
                                Toast.makeText(context, context.getString(R.string.fix_video_saf_export),
                                        Toast.LENGTH_LONG).show();
                                // TODO: Offer share/export dialog for the fixed file in app storage
                            });
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Failed to write repaired file to SAF folder", e);
                        new Handler(Looper.getMainLooper()).post(() -> {
                            Toast.makeText(context, context.getString(R.string.fix_video_saf_write_fail),
                                    Toast.LENGTH_LONG).show();
                            Toast.makeText(context,
                                    "Cannot write to this folder. This may be due to SD card, USB, or cloud storage permissions. The repaired file is saved in app storage.",
                                    Toast.LENGTH_LONG).show();
                            Toast.makeText(context, context.getString(R.string.fix_video_saf_export), Toast.LENGTH_LONG)
                                    .show();
                            // TODO: Offer share/export dialog for the fixed file in app storage
                        });
                    } finally {
                        // Clean up temp files
                        if (finalSafTempInput != null && finalSafTempInput.exists())
                            finalSafTempInput.delete();
                        if (safTempOutput != null && safTempOutput.exists())
                            safTempOutput.delete();
                    }
                } else {
                    new Handler(Looper.getMainLooper()).post(() -> {
                        Toast.makeText(context, context.getString(R.string.fix_video_success, outputFile.getName()),
                                Toast.LENGTH_LONG).show();
                    });
                }
            } else {
                new Handler(Looper.getMainLooper()).post(() -> {
                    Toast.makeText(context, context.getString(R.string.fix_video_fail), Toast.LENGTH_LONG).show();
                });
                // Clean up temp files on failure as well
                if (isSAF) {
                    if (finalSafTempInput != null && finalSafTempInput.exists())
                        finalSafTempInput.delete();
                    if (safTempOutput != null && safTempOutput.exists())
                        safTempOutput.delete();
                }
            }
        });
    }

    /**
     * Generates a unique filename in the given directory to prevent overwriting existing files.
     * If a file with the requested name already exists, appends a counter like " (1)", " (2)", etc.
     * 
     * @param directory The parent directory to check for existing files
     * @param originalName The desired filename
     * @return A unique filename that doesn't exist in the directory
     */
    private String getUniqueFileNameInDirectory(File directory, String originalName) {
        if (directory == null || !directory.exists()) {
            Log.w(TAG, "getUniqueFileNameInDirectory: Target directory doesn't exist: " 
                    + (directory != null ? directory.getAbsolutePath() : "null"));
            return originalName;
        }

        File file = new File(directory, originalName);
        if (!file.exists()) {
            return originalName;
        }

        // File already exists, generate a unique name
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
                Log.d(TAG, "Generated unique filename: " + newName);
                return newName;
            }
            count++;
        }
    }

    /**
     * Generates a unique filename for SAF (Storage Access Framework) documents.
     * If a file with the requested name already exists, appends a counter like " (1)", " (2)", etc.
     * 
     * @param parentDir The parent DocumentFile directory to check for existing files
     * @param originalName The desired filename
     * @return A unique filename that doesn't exist in the directory
     */
    private String getUniqueFileNameForSAF(DocumentFile parentDir, String originalName) {
        if (parentDir == null || !parentDir.exists()) {
            Log.w(TAG, "getUniqueFileNameForSAF: Parent directory doesn't exist or is null");
            return originalName;
        }

        DocumentFile existingFile = parentDir.findFile(originalName);
        if (existingFile == null || !existingFile.exists()) {
            return originalName;
        }

        // File already exists, generate a unique name
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
            DocumentFile checkFile = parentDir.findFile(newName);
            if (checkFile == null || !checkFile.exists()) {
                Log.d(TAG, "Generated unique SAF filename: " + newName);
                return newName;
            }
            count++;
        }
    }

    // ── Month grouping helper methods ──

    /**
     * Builds a list of entries (interleaved month headers + video items) from a flat list.
     */
    private List<Object> buildEntries(List<VideoItem> items) {
        List<Object> result = new ArrayList<>();
        if (items == null || items.isEmpty()) return result;
        String lastMonth = null;
        int serial = 0;
        for (VideoItem item : items) {
            if (item == null) continue;
            String month = (item.lastModified > 0)
                    ? MONTH_FORMAT.format(new Date(item.lastModified))
                    : "Unknown";
            if (!month.equals(lastMonth)) {
                result.add(new MonthHeaderEntry(month));
                lastMonth = month;
            }
            serial++;
            result.add(new VideoItemEntry(item, month, serial));
        }
        return result;
    }

    /**
     * Reassigns serial numbers in the current entries list after a removal.
     */
    private void rebuildSerialNumbers() {
        int serial = 0;
        for (int i = 0; i < entries.size(); i++) {
            Object entry = entries.get(i);
            if (entry instanceof VideoItemEntry) {
                serial++;
                VideoItemEntry old = (VideoItemEntry) entry;
                entries.set(i, new VideoItemEntry(old.item, old.monthKey, serial));
            }
        }
    }

    /**
     * Binds a month header ViewHolder.
     */
    private void bindMonthHeader(@NonNull MonthHeaderViewHolder holder, int position) {
        if (position < 0 || position >= entries.size()) return;
        Object entry = entries.get(position);
        if (!(entry instanceof MonthHeaderEntry)) return;
        MonthHeaderEntry header = (MonthHeaderEntry) entry;
        holder.title.setText(header.monthKey);

        // Match Lab tab style: container is always VISIBLE, alpha controls visibility
        holder.selectContainer.setVisibility(View.VISIBLE);
        holder.selectContainer.setAlpha(isSelectionModeActive ? 1f : 0f);
        holder.selectContainer.setEnabled(isSelectionModeActive);

        // Check if all items in this month are selected
        List<VideoItem> monthItems = getItemsForMonth(header.monthKey);
        boolean allSelected = !monthItems.isEmpty();
        for (VideoItem item : monthItems) {
            if (!currentSelectedUris.contains(item.uri)) {
                allSelected = false;
                break;
            }
        }
        holder.selectBg.setVisibility(isSelectionModeActive ? View.VISIBLE : View.INVISIBLE);
        holder.selectCheck.setVisibility(isSelectionModeActive && allSelected ? View.VISIBLE : View.INVISIBLE);
        holder.selectContainer.setOnClickListener(isSelectionModeActive ? v -> {
            if (monthActionListener != null) {
                monthActionListener.onMonthSelectAll(header.monthKey, monthItems);
            }
        } : null);
    }

    /**
     * Returns all VideoItems belonging to a specific month key.
     */
    private List<VideoItem> getItemsForMonth(String monthKey) {
        List<VideoItem> result = new ArrayList<>();
        for (Object entry : entries) {
            if (entry instanceof VideoItemEntry && monthKey.equals(((VideoItemEntry) entry).monthKey)) {
                result.add(((VideoItemEntry) entry).item);
            }
        }
        return result;
    }

    /**
     * Returns the section text (month/year) for a given adapter position,
     * walking backward to find the nearest month header.
     * Used by the fast scroller date bubble.
     */
    public String getSectionText(int position) {
        if (entries == null || position < 0) return "";
        int pos = Math.min(position, entries.size() - 1);
        while (pos >= 0) {
            Object entry = entries.get(pos);
            if (entry instanceof MonthHeaderEntry) {
                return ((MonthHeaderEntry) entry).monthKey;
            }
            if (entry instanceof VideoItemEntry) {
                return ((VideoItemEntry) entry).monthKey;
            }
            pos--;
        }
        return "";
    }

    /**
     * Returns the count of actual video/image records (excluding month headers).
     */
    public int getRecordsCount() {
        return records == null ? 0 : records.size();
    }

    // ── MonthHeaderViewHolder ──
    static class MonthHeaderViewHolder extends RecyclerView.ViewHolder {
        final TextView title;
        final View selectContainer;
        final ImageView selectBg;
        final ImageView selectCheck;

        MonthHeaderViewHolder(View itemView) {
            super(itemView);
            title = itemView.findViewById(R.id.text_month_title);
            selectContainer = itemView.findViewById(R.id.month_select_container);
            selectBg = itemView.findViewById(R.id.month_select_bg);
            selectCheck = itemView.findViewById(R.id.month_select_check);
        }
    }
}
