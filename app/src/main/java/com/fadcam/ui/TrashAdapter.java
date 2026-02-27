package com.fadcam.ui;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.graphics.drawable.Drawable;
import androidx.vectordrawable.graphics.drawable.AnimatedVectorDrawableCompat;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import com.fadcam.R;
import com.fadcam.model.TrashItem;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import com.bumptech.glide.Glide;
import android.net.Uri;
import java.io.File;
import com.fadcam.utils.TrashManager;
import android.util.Log;
import android.widget.Toast;
import com.fadcam.SharedPreferencesManager;
import java.util.concurrent.TimeUnit;
import com.fadcam.Constants;

public class TrashAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    private static final int VIEW_TYPE_HEADER = 0;
    private static final int VIEW_TYPE_ITEM = 1;
    private static final SimpleDateFormat MONTH_FORMAT = new SimpleDateFormat("MMMM yyyy", Locale.getDefault());

    /** Represents a month header row. */
    static class MonthHeaderEntry { final String monthKey; MonthHeaderEntry(String mk) { this.monthKey = mk; } }
    /** Wraps a TrashItem with its month key. */
    static class TrashItemEntry { final TrashItem item; final String monthKey; TrashItemEntry(TrashItem i, String mk) { this.item = i; this.monthKey = mk; } }

    /** Callback for month-level selection actions. */
    public interface OnMonthActionListener {
        void onMonthSelectAll(String monthKey, List<TrashItem> items);
    }

    private final Context context;
    private final List<TrashItem> trashItems;
    private final List<Object> entries = new ArrayList<>();
    private final List<TrashItem> selectedItems = new ArrayList<>();
    private final OnTrashItemInteractionListener interactionListener;
    private final OnTrashItemLongClickListener longClickListener; // Optional
    private final SharedPreferencesManager sharedPreferencesManager;
    private boolean isSnowVeilTheme = false;
    private OnMonthActionListener monthActionListener;

    public interface OnTrashItemInteractionListener {
        void onItemCheckChanged(TrashItem item, boolean isChecked);
        void onItemSelectedStateChanged(boolean anySelected);
        void onRestoreStarted(int itemCount);
        void onRestoreFinished(boolean success, String message);
        void onPlayVideoRequested(TrashItem item);
    }

    public interface OnTrashItemLongClickListener {
        void onItemLongClicked(TrashItem item);
    }

    public TrashAdapter(Context context, List<TrashItem> trashItems,
                          OnTrashItemInteractionListener interactionListener,
                          OnTrashItemLongClickListener longClickListener) {
        this.context = context;
        this.trashItems = trashItems;
        this.interactionListener = interactionListener;
        this.longClickListener = longClickListener;
        
        // Get current theme for theming
        sharedPreferencesManager = SharedPreferencesManager.getInstance(context);
        String currentTheme = sharedPreferencesManager.sharedPreferences.getString(com.fadcam.Constants.PREF_APP_THEME, Constants.DEFAULT_APP_THEME);
        this.isSnowVeilTheme = "Snow Veil".equals(currentTheme);
        buildEntries();
    }

    public void setOnMonthActionListener(OnMonthActionListener listener) {
        this.monthActionListener = listener;
    }

    @Override
    public int getItemViewType(int position) {
        return entries.get(position) instanceof MonthHeaderEntry ? VIEW_TYPE_HEADER : VIEW_TYPE_ITEM;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        if (viewType == VIEW_TYPE_HEADER) {
            View view = LayoutInflater.from(context).inflate(R.layout.item_forensics_month_header, parent, false);
            return new MonthHeaderViewHolder(view);
        }
        View view = LayoutInflater.from(context).inflate(R.layout.item_trash, parent, false);
        return new TrashViewHolder(view);
    }
    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder vh, int position) {
        Object entry = entries.get(position);
        if (entry instanceof MonthHeaderEntry) {
            bindMonthHeader((MonthHeaderViewHolder) vh, (MonthHeaderEntry) entry);
            return;
        }
        TrashViewHolder holder = (TrashViewHolder) vh;
        TrashItem item = ((TrashItemEntry) entry).item;
        holder.bind(item);
        // Apply Snow Veil theme if needed (apply after normal binding)
        if (isSnowVeilTheme && holder.itemView instanceof androidx.cardview.widget.CardView) {
            ((androidx.cardview.widget.CardView) holder.itemView).setCardBackgroundColor(android.graphics.Color.WHITE);
            if (holder.tvOriginalName != null) holder.tvOriginalName.setTextColor(android.graphics.Color.BLACK);
            if (holder.tvDateTrashed != null) holder.tvDateTrashed.setTextColor(android.graphics.Color.BLACK);
            if (holder.tvFileSize != null) holder.tvFileSize.setTextColor(android.graphics.Color.BLACK);
        }
    }

    // Payload-aware binding so we can play an animation only when selection toggles occur.
    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder vh, int position, @NonNull List<Object> payloads) {
        Object entry = entries.get(position);
        if (entry instanceof MonthHeaderEntry) {
            bindMonthHeader((MonthHeaderViewHolder) vh, (MonthHeaderEntry) entry);
            return;
        }
        if (payloads == null || payloads.isEmpty()) {
            // Full bind
            onBindViewHolder(vh, position);
            return;
        }

        TrashViewHolder holder = (TrashViewHolder) vh;
        // Handle selection toggle payload specifically
        TrashItem item = ((TrashItemEntry) entry).item;
        boolean selected = selectedItems.contains(item);

        if (payloads.contains("SELECTION_TOGGLE")) {
            if (holder.iconCheckContainer != null && holder.iconCheck != null) {
                boolean inSelectionMode = !selectedItems.isEmpty();
                // Show/hide check container based on selection mode
                holder.iconCheckContainer.setVisibility(inSelectionMode ? View.VISIBLE : View.GONE);
                // Show/hide dim overlay
                if (holder.selectionDimOverlay != null) {
                    holder.selectionDimOverlay.setVisibility(selected ? View.VISIBLE : View.GONE);
                }
                if (selected) {
                    holder.iconCheck.setScaleX(1f);
                    holder.iconCheck.setScaleY(1f);
                    holder.iconCheck.setAlpha(1f);
                    AnimatedVectorDrawableCompat avd = AnimatedVectorDrawableCompat.create(context, R.drawable.avd_check_draw);
                    if (avd != null) {
                        holder.iconCheck.setImageDrawable(avd);
                        avd.start();
                    }
                } else {
                    holder.iconCheck.animate().alpha(0f).scaleX(0f).scaleY(0f).setDuration(180).withEndAction(() -> {
                        holder.iconCheck.setImageDrawable(null);
                    }).start();
                }
            }
        } else {
            // Other payloads fall back to full bind
            onBindViewHolder(holder, position);
        }
    }

    @Override
    public int getItemCount() {
        return entries.size();
    }

    /** Returns the count of actual trash items (excluding headers). */
    public int getTrashItemCount() {
        return trashItems.size();
    }

    public List<TrashItem> getSelectedItems() {
        return new ArrayList<>(selectedItems); // Return a copy
    }

    public int getSelectedItemsCount() {
        return selectedItems.size();
    }

    /**
     * Selects all items in the list
     */
    public void selectAll() {
        selectedItems.clear();
        selectedItems.addAll(trashItems);
    // Notify all items to ensure offscreen holders bind with a tick when they appear
    notifyDataSetChanged();
        if (interactionListener != null) {
            interactionListener.onItemSelectedStateChanged(!selectedItems.isEmpty());
        }
    }

    /**
     * Clears all selected items and updates the UI accordingly
     */
    public void clearSelections() {
        if (!selectedItems.isEmpty()) {
            int oldCount = selectedItems.size();
            selectedItems.clear();
            notifyDataSetChanged();
            if (interactionListener != null) {
                interactionListener.onItemSelectedStateChanged(false);
            }
        }
    }

    /**
     * Checks if all items are currently selected
     * @return true if all items are selected, false otherwise
     */
    public boolean isAllSelected() {
        return !trashItems.isEmpty() && selectedItems.size() == trashItems.size();
    }

    class TrashViewHolder extends RecyclerView.ViewHolder {
        TextView tvOriginalName;
        TextView tvDateTrashed;
        TextView tvFileSize;
        View iconCheckContainer;
        ImageView iconCheck;
        ImageView imageViewThumbnail;
        TextView tvRemainingTime;
        View selectionDimOverlay;

        TrashViewHolder(@NonNull View itemView) {
            super(itemView);
            imageViewThumbnail = itemView.findViewById(R.id.image_view_trash_thumbnail);
            tvOriginalName = itemView.findViewById(R.id.tv_trash_item_original_name);
            tvDateTrashed = itemView.findViewById(R.id.tv_trash_item_date_trashed);
            tvFileSize = itemView.findViewById(R.id.tv_trash_item_file_size);
            iconCheckContainer = itemView.findViewById(R.id.icon_check_container);
            iconCheck = itemView.findViewById(R.id.icon_check);
            tvRemainingTime = itemView.findViewById(R.id.tv_trash_item_remaining_time);
            selectionDimOverlay = itemView.findViewById(R.id.trash_selection_dim_overlay);
        }

        void bind(final TrashItem item) {
            tvOriginalName.setText(item.getOriginalDisplayName());
            // Use relative time (e.g., "2 days ago") matching Records style
            CharSequence relativeDate = android.text.format.DateUtils.getRelativeTimeSpanString(
                    item.getDateTrashed(),
                    System.currentTimeMillis(),
                    android.text.format.DateUtils.MINUTE_IN_MILLIS,
                    android.text.format.DateUtils.FORMAT_ABBREV_RELATIVE);
            tvDateTrashed.setText(relativeDate);

            // Compute file size from disk
            if (tvFileSize != null && context != null) {
                File trashDirectory = TrashManager.getTrashDirectory(context);
                if (trashDirectory != null && item.getTrashFileName() != null) {
                    File trashedFile = new File(trashDirectory, item.getTrashFileName());
                    if (trashedFile.exists()) {
                        tvFileSize.setText(android.text.format.Formatter.formatShortFileSize(context, trashedFile.length()));
                    } else {
                        tvFileSize.setText("—");
                    }
                } else {
                    tvFileSize.setText("—");
                }
            }

            if (tvRemainingTime != null && sharedPreferencesManager != null && context != null) {
                int autoDeleteMinutes = sharedPreferencesManager.getTrashAutoDeleteMinutes();
                if (autoDeleteMinutes == SharedPreferencesManager.TRASH_AUTO_DELETE_NEVER) {
                    tvRemainingTime.setText(context.getString(R.string.trash_auto_delete_info_manual));
                    tvRemainingTime.setTextColor(context.getResources().getColor(R.color.gray_text_very_light));
                } else {
                    long timeSinceTrashedMillis = System.currentTimeMillis() - item.getDateTrashed();
                    long autoDeleteTotalMillis = TimeUnit.MINUTES.toMillis(autoDeleteMinutes);
                    long remainingMillis = autoDeleteTotalMillis - timeSinceTrashedMillis;

                    if (remainingMillis <= 0) {
                        tvRemainingTime.setText(context.getString(R.string.trash_item_remaining_soon));
                        tvRemainingTime.setTextColor(context.getResources().getColor(R.color.colorError));
                    } else {
                        long remainingDays = TimeUnit.MILLISECONDS.toDays(remainingMillis);
                        if (remainingDays > 0) {
                            tvRemainingTime.setText(context.getResources().getQuantityString(R.plurals.trash_item_remaining_days, (int)remainingDays, (int)remainingDays));
                        } else {
                            long remainingHours = TimeUnit.MILLISECONDS.toHours(remainingMillis);
                            if (remainingHours > 0) {
                                tvRemainingTime.setText(context.getResources().getQuantityString(R.plurals.trash_item_remaining_hours, (int)remainingHours, (int)remainingHours));
                            } else {
                                tvRemainingTime.setText(context.getString(R.string.trash_item_remaining_soon));
                            }
                        }
                        
                        if (remainingDays == 0 && TimeUnit.MILLISECONDS.toHours(remainingMillis) < 1) {
                            tvRemainingTime.setTextColor(context.getResources().getColor(R.color.colorError));
                        } else if (remainingDays == 0 && TimeUnit.MILLISECONDS.toHours(remainingMillis) < 12) {
                            tvRemainingTime.setTextColor(context.getResources().getColor(R.color.colorWarning));
                        } else if (remainingDays < 3) {
                            tvRemainingTime.setTextColor(context.getResources().getColor(R.color.colorWarning));
                        } else {
                            tvRemainingTime.setTextColor(context.getResources().getColor(R.color.gray_text_light));
                        }
                    }
                }
            } else {
                if(tvRemainingTime != null) tvRemainingTime.setVisibility(View.GONE);
            }

            if (imageViewThumbnail != null && context != null) {
                File trashDirectory = TrashManager.getTrashDirectory(context);
                if (trashDirectory != null && item.getTrashFileName() != null) {
                    File trashedVideoFile = new File(trashDirectory, item.getTrashFileName());
                    if (trashedVideoFile.exists()) {
                        Uri videoUri = Uri.fromFile(trashedVideoFile);
                        Glide.with(context)
                            .load(videoUri)
                            .placeholder(R.drawable.ic_video_placeholder)
                            .error(R.drawable.ic_error)
                            .centerCrop()
                            .into(imageViewThumbnail);
                    } else {
                        Log.w("TrashAdapter", "Trashed video file does not exist: " + trashedVideoFile.getAbsolutePath());
                        Glide.with(context).load(R.drawable.ic_error).into(imageViewThumbnail); // Show error icon
                    }
                } else {
                    Log.e("TrashAdapter", "Trash directory or trash file name is null.");
                    Glide.with(context).load(R.drawable.ic_error).into(imageViewThumbnail); // Show error icon
                }
            }

            // --- Selection state (matching Records behavior) ---
            boolean inSelectionMode = !selectedItems.isEmpty();
            boolean isSelected = selectedItems.contains(item);

            // Selection dim overlay — darken thumbnail when selected
            if (selectionDimOverlay != null) {
                selectionDimOverlay.setVisibility(isSelected ? View.VISIBLE : View.GONE);
            }

            // Check container — only visible in selection mode
            if (iconCheckContainer != null) {
                iconCheckContainer.setVisibility(inSelectionMode ? View.VISIBLE : View.GONE);
                if (iconCheck != null) {
                    if (isSelected) {
                        AnimatedVectorDrawableCompat avd = AnimatedVectorDrawableCompat.create(context, R.drawable.avd_check_draw);
                        if (avd != null) {
                            iconCheck.setImageDrawable(avd);
                            avd.start();
                        }
                        iconCheck.setScaleX(1f);
                        iconCheck.setScaleY(1f);
                        iconCheck.setAlpha(1f);
                    } else {
                        iconCheck.setImageDrawable(null);
                        iconCheck.setScaleX(0f);
                        iconCheck.setScaleY(0f);
                        iconCheck.setAlpha(0f);
                    }
                }
            }
            
            // Set up click listeners
            itemView.setOnClickListener(v -> {
                if (selectedItems.isEmpty()) {
                    if (interactionListener != null) {
                        File trashDirectory = TrashManager.getTrashDirectory(context);
                        if (trashDirectory != null && item.getTrashFileName() != null) {
                            File trashedFile = new File(trashDirectory, item.getTrashFileName());
                            if (trashedFile.exists()) {
                                interactionListener.onPlayVideoRequested(item);
                            } else {
                                Toast.makeText(context, "File not found in trash.", Toast.LENGTH_SHORT).show();
                            }
                        } else {
                            Toast.makeText(context, "Cannot locate file.", Toast.LENGTH_SHORT).show();
                        }
                    }
                } else {
                    toggleSelection(item);
                }
            });

            itemView.setOnLongClickListener(v -> {
                if (longClickListener != null) {
                    longClickListener.onItemLongClicked(item);
                    return true;
                }
                toggleSelection(item);
                return true;
            });

            // Clicking directly on the check container should toggle selection and
            // should not open playback. Consume the click event to prevent
            // propagation to the itemView click listener.
            if (iconCheckContainer != null) {
                iconCheckContainer.setOnTouchListener((v, event) -> {
                    // Consume touch and handle click via OnClick to avoid propagation
                    return false; // allow click to proceed
                });
                iconCheckContainer.setOnClickListener(v -> {
                    toggleSelection(item);
                    v.setPressed(false);
                });
            }

            // We keep selection driven by clicks/long clicks. When toggling programmatically,
            // simply show/hide the container and notify listener.
        }
    }
    
    /**
     * Toggle selection state of an item
     */
    private void toggleSelection(TrashItem item) {
        toggleSelectionInternal(item);
    }

    /**
     * Public method for external callers (e.g., month header selection) to toggle an item.
     */
    public void toggleSelectionExternal(TrashItem item) {
        toggleSelectionInternal(item);
    }

    private void toggleSelectionInternal(TrashItem item) {
        boolean newState = !selectedItems.contains(item);
        if (newState) {
            selectedItems.add(item);
        } else {
            selectedItems.remove(item);
        }
        
        // Find the position of the item in entries and notify the adapter
        int position = findEntryPosition(item);
        if (position != -1) {
            notifyItemChanged(position, "SELECTION_TOGGLE");
        }
        // Also update the month header for this item's month
        notifyMonthHeaderForItem(item);
        
        if (interactionListener != null) {
            interactionListener.onItemCheckChanged(item, newState);
            interactionListener.onItemSelectedStateChanged(!selectedItems.isEmpty());
        }
    }

    /** Find position of a TrashItem in the entries list. */
    private int findEntryPosition(TrashItem item) {
        for (int i = 0; i < entries.size(); i++) {
            Object e = entries.get(i);
            if (e instanceof TrashItemEntry && ((TrashItemEntry) e).item == item) {
                return i;
            }
        }
        return -1;
    }

    /** Notify the month header for a given item to update its checkbox state. */
    private void notifyMonthHeaderForItem(TrashItem item) {
        String monthKey = MONTH_FORMAT.format(new Date(item.getDateTrashed()));
        for (int i = 0; i < entries.size(); i++) {
            Object e = entries.get(i);
            if (e instanceof MonthHeaderEntry && ((MonthHeaderEntry) e).monthKey.equals(monthKey)) {
                notifyItemChanged(i);
                break;
            }
        }
    }

    /**
     * Returns true if currently in selection mode (i.e., any items selected)
     */
    public boolean isInSelectionMode() {
        return !selectedItems.isEmpty();
    }

    // ────────────────────── Month Grouping Helpers ──────────────────────

    /** Build the interleaved entries list from the flat trashItems list. */
    private void buildEntries() {
        entries.clear();
        if (trashItems == null || trashItems.isEmpty()) return;

        // Group by month, preserving insertion order (items are already sorted newest-first)
        LinkedHashMap<String, List<TrashItem>> grouped = new LinkedHashMap<>();
        for (TrashItem item : trashItems) {
            String monthKey = MONTH_FORMAT.format(new Date(item.getDateTrashed()));
            grouped.computeIfAbsent(monthKey, k -> new ArrayList<>()).add(item);
        }
        for (Map.Entry<String, List<TrashItem>> e : grouped.entrySet()) {
            entries.add(new MonthHeaderEntry(e.getKey()));
            for (TrashItem item : e.getValue()) {
                entries.add(new TrashItemEntry(item, e.getKey()));
            }
        }
    }

    /** Rebuild entries and refresh the whole list (called after external data changes). */
    public void rebuildAndNotify() {
        buildEntries();
        notifyDataSetChanged();
    }

    /** Bind a month header ViewHolder. */
    private void bindMonthHeader(MonthHeaderViewHolder holder, MonthHeaderEntry entry) {
        holder.title.setText(entry.monthKey);

        // Match Lab tab style: container is always VISIBLE, alpha controls visibility
        boolean inSelectionMode = !selectedItems.isEmpty();
        holder.selectContainer.setVisibility(View.VISIBLE);
        holder.selectContainer.setAlpha(inSelectionMode ? 1f : 0f);
        holder.selectContainer.setEnabled(inSelectionMode);

        List<TrashItem> monthItems = getItemsForMonth(entry.monthKey);
        boolean allInMonthSelected = !monthItems.isEmpty();
        for (TrashItem item : monthItems) {
            if (!selectedItems.contains(item)) {
                allInMonthSelected = false;
                break;
            }
        }
        holder.selectBg.setVisibility(inSelectionMode ? View.VISIBLE : View.INVISIBLE);
        holder.selectCheck.setVisibility(inSelectionMode && allInMonthSelected ? View.VISIBLE : View.INVISIBLE);
        holder.selectContainer.setOnClickListener(inSelectionMode ? v -> {
            if (monthActionListener != null) {
                monthActionListener.onMonthSelectAll(entry.monthKey, monthItems);
            }
        } : null);
    }

    /** Get all trash items for a given month key. */
    public List<TrashItem> getItemsForMonth(String monthKey) {
        List<TrashItem> result = new ArrayList<>();
        for (Object e : entries) {
            if (e instanceof TrashItemEntry && ((TrashItemEntry) e).monthKey.equals(monthKey)) {
                result.add(((TrashItemEntry) e).item);
            }
        }
        return result;
    }

    /**
     * Returns the section text (month key) for the given adapter position.
     * Used by GalleryFastScroller bubble.
     */
    public String getSectionText(int position) {
        if (position < 0 || position >= entries.size()) return "";
        // Walk backward to find nearest month header
        for (int i = position; i >= 0; i--) {
            Object e = entries.get(i);
            if (e instanceof MonthHeaderEntry) return ((MonthHeaderEntry) e).monthKey;
            if (e instanceof TrashItemEntry) return ((TrashItemEntry) e).monthKey;
        }
        return "";
    }

    // ────────────────────── Month Header ViewHolder ──────────────────────

    static class MonthHeaderViewHolder extends RecyclerView.ViewHolder {
        final TextView title;
        final View selectContainer;
        final ImageView selectBg;
        final ImageView selectCheck;

        MonthHeaderViewHolder(@NonNull View itemView) {
            super(itemView);
            title = itemView.findViewById(R.id.text_month_title);
            selectContainer = itemView.findViewById(R.id.month_select_container);
            selectBg = itemView.findViewById(R.id.month_select_bg);
            selectCheck = itemView.findViewById(R.id.month_select_check);
        }
    }
} 
