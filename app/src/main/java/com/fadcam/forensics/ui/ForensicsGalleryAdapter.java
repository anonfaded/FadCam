package com.fadcam.forensics.ui;

import android.net.Uri;
import android.app.ActivityManager;
import android.text.format.Formatter;
import android.util.Log;
import android.view.MotionEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.fadcam.BuildConfig;
import com.fadcam.R;
import com.fadcam.SharedPreferencesManager;
import com.fadcam.Utils;
import com.fadcam.forensics.data.local.model.ForensicsSnapshotWithMedia;
import com.fadcam.forensics.ui.view.ForensicsCardContainerView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import androidx.recyclerview.widget.DiffUtil;

public class ForensicsGalleryAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
    private static final String PREF_TORN_THEME_ENABLED = "forensics_torn_card_theme_enabled";
    public static final String PREF_CLIP_STYLE = "forensics_gallery_clip_style";
    public static final String PREF_TAPE_STYLE = "forensics_gallery_tape_style";
    public static final String CLIP_STYLE_BLACK = "black";
    public static final String CLIP_STYLE_RED = "red";
    public static final String TAPE_STYLE_TORN = "torn";
    public static final String TAPE_STYLE_CLASSIC = "classic";
    private static final String TAG = "ForensicsGalleryAdapter";

    public interface Listener {
        void onSelectionChanged(int selectedCount);
        void onOpenViewer(@NonNull ForensicsSnapshotWithMedia row, boolean mediaMissing);
        void onMonthSelectionRequested(@NonNull String monthKey);
    }

    private static final int TYPE_HEADER = 0;
    private static final int TYPE_ITEM = 1;

    private abstract static class Entry {
        final int type;
        Entry(int type) { this.type = type; }
    }

    private static class HeaderEntry extends Entry {
        final String monthKey;
        HeaderEntry(String monthKey) {
            super(TYPE_HEADER);
            this.monthKey = monthKey;
        }
    }

    private static class ItemEntry extends Entry {
        final ForensicsSnapshotWithMedia row;
        final String monthKey;
        ItemEntry(ForensicsSnapshotWithMedia row, String monthKey) {
            super(TYPE_ITEM);
            this.row = row;
            this.monthKey = monthKey;
        }
    }

    private final List<Entry> entries = new ArrayList<>();
    private final List<ForensicsSnapshotWithMedia> rows = new ArrayList<>();
    private final Set<String> selectedSnapshotIds = new HashSet<>();
    /** Pre-built month → rows lookup. Rebuilt on every submit. */
    private final Map<String, List<ForensicsSnapshotWithMedia>> monthIndex = new HashMap<>();
    /** Pre-computed item numbers (1-based). Rebuilt on every rebuildEntries. */
    private int[] itemNumbers;
    /** Cached file sizes: imageUri → bytes. Survives across submits for perf. */
    private final Map<String, Long> fileSizeCache = new HashMap<>();
    /** Cached total bytes — computed in background, delivered to UI. */
    private long cachedTotalBytes = -1L;
    /** Reusable SimpleDateFormat for month headers. */
    private final SimpleDateFormat monthFormat = new SimpleDateFormat("MMMM yyyy", Locale.getDefault());
    private Listener listener;
    private boolean selectionMode;
    @Nullable
    private Boolean tornThemeEnabled;
    @Nullable
    private Boolean lowRamDevice;
    @NonNull
    private String clipStyle = CLIP_STYLE_BLACK;
    @NonNull
    private String tapeStyle = TAPE_STYLE_TORN;
    private int currentGridSpan = 2;
    private boolean hideThumbnails;

    public ForensicsGalleryAdapter() {
        setHasStableIds(false);
    }

    public void setListener(@Nullable Listener listener) {
        this.listener = listener;
    }

    public void setVisualStyles(@Nullable String clipStyle, @Nullable String tapeStyle) {
        String newClip = (clipStyle == null || clipStyle.trim().isEmpty()) ? CLIP_STYLE_BLACK : clipStyle;
        String newTape = (tapeStyle == null || tapeStyle.trim().isEmpty()) ? TAPE_STYLE_TORN : tapeStyle;
        boolean changed = !this.clipStyle.equals(newClip) || !this.tapeStyle.equals(newTape);
        this.clipStyle = newClip;
        this.tapeStyle = newTape;
        if (changed) {
            notifyDataSetChanged();
        }
    }

    /**
     * Enables or disables "Classified Mode": hides evidence thumbnails behind
     * a redacted placeholder with a forensic-flavored overlay.
     */
    public void setHideThumbnails(boolean hide) {
        if (hideThumbnails == hide) return;
        hideThumbnails = hide;
        notifyDataSetChanged();
    }

    /** Returns whether thumbnails are currently hidden (Classified Mode). */
    public boolean isHideThumbnails() {
        return hideThumbnails;
    }

    public void setGridSpan(int spanCount) {
        int normalized = Math.max(1, Math.min(5, spanCount));
        if (currentGridSpan == normalized) {
            return;
        }
        currentGridSpan = normalized;
        notifyDataSetChanged();
    }

    public void submit(@Nullable List<ForensicsSnapshotWithMedia> data) {
        List<Entry> oldEntries = new ArrayList<>(entries);
        rows.clear();
        if (data != null) {
            rows.addAll(data);
        }
        logIdentityIntegrityIfNeeded();
        selectedSnapshotIds.retainAll(currentIds());
        if (selectedSnapshotIds.isEmpty()) {
            selectionMode = false;
        }
        rebuildEntries();
        dispatchSelection();

        DiffUtil.DiffResult result = DiffUtil.calculateDiff(new DiffUtil.Callback() {
            @Override public int getOldListSize() { return oldEntries.size(); }
            @Override public int getNewListSize() { return entries.size(); }
            @Override
            public boolean areItemsTheSame(int oldPos, int newPos) {
                Entry oldE = oldEntries.get(oldPos);
                Entry newE = entries.get(newPos);
                if (oldE.type != newE.type) return false;
                if (oldE instanceof HeaderEntry) {
                    return ((HeaderEntry) oldE).monthKey.equals(((HeaderEntry) newE).monthKey);
                }
                return snapshotId(((ItemEntry) oldE).row).equals(snapshotId(((ItemEntry) newE).row));
            }
            @Override
            public boolean areContentsTheSame(int oldPos, int newPos) {
                Entry oldE = oldEntries.get(oldPos);
                Entry newE = entries.get(newPos);
                if (oldE instanceof HeaderEntry) {
                    return ((HeaderEntry) oldE).monthKey.equals(((HeaderEntry) newE).monthKey);
                }
                ItemEntry o = (ItemEntry) oldE;
                ItemEntry n = (ItemEntry) newE;
                boolean oldSel = selectedSnapshotIds.contains(snapshotId(o.row));
                boolean newSel = selectedSnapshotIds.contains(snapshotId(n.row));
                return oldSel == newSel
                        && safe(o.row.className).equals(safe(n.row.className))
                        && safe(o.row.eventType).equals(safe(n.row.eventType))
                        && safe(o.row.eventUid).equals(safe(n.row.eventUid))
                        && o.row.mediaMissing == n.row.mediaMissing
                        && o.row.timelineMs == n.row.timelineMs
                        && safe(o.row.imageUri).equals(safe(n.row.imageUri))
                        && safe(o.row.mediaUri).equals(safe(n.row.mediaUri))
                        && o.row.capturedEpochMs == n.row.capturedEpochMs
                        && o.row.confidence == n.row.confidence;
            }
        }, false);
        result.dispatchUpdatesTo(this);
    }

    public boolean isSelectionMode() {
        return selectionMode;
    }

    public int getSelectedCount() {
        return selectedSnapshotIds.size();
    }

    public int getSelectableCount() {
        return rows.size();
    }

    public boolean isAllSelected() {
        return !rows.isEmpty() && selectedSnapshotIds.size() == rows.size();
    }

    /**
     * Returns the cached total image bytes. Returns 0 if not yet computed.
     * Call {@link #computeTotalImageBytesAsync(Runnable)} from background to populate.
     */
    public long getTotalImageBytes() {
        return Math.max(0L, cachedTotalBytes);
    }

    /**
     * Computes total image bytes on the calling thread (call from background),
     * then runs the callback on the same thread to signal completion.
     * The result is cached and accessible via {@link #getTotalImageBytes()}.
     */
    public void computeTotalImageBytesAsync(@Nullable Runnable onComplete) {
        long total = 0L;
        for (ForensicsSnapshotWithMedia row : rows) {
            total += resolveImageSizeCached(row.imageUri);
        }
        cachedTotalBytes = total;
        if (onComplete != null) onComplete.run();
    }

    public void clearSelection() {
        selectedSnapshotIds.clear();
        selectionMode = false;
        dispatchSelection();
        notifyDataSetChanged();
    }

    public void selectAll() {
        selectedSnapshotIds.clear();
        for (ForensicsSnapshotWithMedia row : rows) {
            selectedSnapshotIds.add(snapshotId(row));
        }
        selectionMode = !selectedSnapshotIds.isEmpty();
        dispatchSelection();
        notifyDataSetChanged();
    }

    public void toggleMonthSelection(@NonNull String monthKey) {
        List<ForensicsSnapshotWithMedia> monthRows = rowsForMonth(monthKey);
        if (monthRows.isEmpty()) {
            return;
        }
        selectionMode = true;
        boolean allInMonthSelected = true;
        for (ForensicsSnapshotWithMedia row : monthRows) {
            if (!selectedSnapshotIds.contains(snapshotId(row))) {
                allInMonthSelected = false;
                break;
            }
        }
        for (ForensicsSnapshotWithMedia row : monthRows) {
            String id = snapshotId(row);
            if (allInMonthSelected) {
                selectedSnapshotIds.remove(id);
            } else {
                selectedSnapshotIds.add(id);
            }
        }
        if (selectedSnapshotIds.isEmpty()) {
            selectionMode = false;
        }
        dispatchSelection();
        notifyDataSetChanged();
    }

    @NonNull
    public List<ForensicsSnapshotWithMedia> getSelectedRows() {
        List<ForensicsSnapshotWithMedia> selected = new ArrayList<>();
        for (ForensicsSnapshotWithMedia row : rows) {
            if (selectedSnapshotIds.contains(snapshotId(row))) {
                selected.add(row);
            }
        }
        return selected;
    }

    @Override
    public int getItemViewType(int position) {
        return entries.get(position).type;
    }

    /**
     * Returns the section label (month header text) for the given adapter position.
     * Walks backwards from position to find the nearest HeaderEntry.
     * Used by the fast scroller to display a date bubble.
     *
     * @param position adapter position
     * @return section text like "June 2025", or empty string if none found
     */
    @NonNull
    public String getSectionText(int position) {
        if (position < 0 || position >= entries.size()) return "";
        for (int i = position; i >= 0; i--) {
            Entry e = entries.get(i);
            if (e instanceof HeaderEntry) {
                return ((HeaderEntry) e).monthKey;
            }
        }
        return "";
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        if (viewType == TYPE_HEADER) {
            View view = inflater.inflate(R.layout.item_forensics_month_header, parent, false);
            return new MonthHolder(view);
        }
        View view = inflater.inflate(R.layout.item_forensics_gallery, parent, false);
        return new ItemHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        Entry entry = entries.get(position);
        if (entry instanceof HeaderEntry) {
            bindMonthHeader((MonthHolder) holder, (HeaderEntry) entry);
            return;
        }
        bindItem((ItemHolder) holder, (ItemEntry) entry, position);
    }

    private void bindMonthHeader(@NonNull MonthHolder holder, @NonNull HeaderEntry header) {
        holder.title.setText(header.monthKey);
        holder.selectContainer.setVisibility(View.VISIBLE);
        boolean allInMonthSelected = true;
        List<ForensicsSnapshotWithMedia> monthRows = rowsForMonth(header.monthKey);
        for (ForensicsSnapshotWithMedia row : monthRows) {
            if (!selectedSnapshotIds.contains(snapshotId(row))) {
                allInMonthSelected = false;
                break;
            }
        }
        holder.selectBg.setVisibility(selectionMode ? View.VISIBLE : View.INVISIBLE);
        holder.selectCheck.setVisibility(selectionMode && allInMonthSelected ? View.VISIBLE : View.INVISIBLE);
        holder.selectContainer.setAlpha(selectionMode ? 1f : 0f);
        holder.selectContainer.setEnabled(selectionMode);
        holder.selectContainer.setOnClickListener(selectionMode ? v -> {
            if (listener != null) {
                listener.onMonthSelectionRequested(header.monthKey);
            }
        } : null);
    }

    private void bindItem(@NonNull ItemHolder holder, @NonNull ItemEntry entry, int position) {
        ForensicsSnapshotWithMedia row = entry.row;
        boolean mediaMissing = row.mediaMissing || isMediaMissing(row.mediaUri);
        boolean selected = selectionMode && selectedSnapshotIds.contains(snapshotId(row));
        String snapshotId = snapshotId(row);
        boolean tornTheme = isTornThemeEnabled(holder.itemView.getContext());

        String classLabel = (row.className == null || row.className.trim().isEmpty())
                ? (row.eventType == null ? "object" : row.eventType.toLowerCase(Locale.US))
                : row.className.toLowerCase(Locale.US);
        holder.title.setText(holder.itemView.getContext().getString(R.string.forensics_detected_label, classLabel));
        String sourceLabel = mediaMissing
                ? holder.itemView.getContext().getString(R.string.forensics_snapshot_only)
                : holder.itemView.getContext().getString(R.string.forensics_video_linked);
        if (currentGridSpan >= 3) {
            sourceLabel = mediaMissing ? "Snapshot" : "Video linked";
        }
        String sizeLabel = Formatter.formatShortFileSize(holder.itemView.getContext(), Math.max(0L, resolveImageSizeCached(row.imageUri)));
        String timeAgo = Utils.formatTimeAgo(row.capturedEpochMs);
        holder.metaTime.setText((timeAgo == null || timeAgo.trim().isEmpty()) ? "Just now" : timeAgo);
        holder.metaConfidence.setText(String.format(Locale.US, currentGridSpan >= 3 ? "%.1f" : "%.2f", row.confidence));
        holder.metaSource.setText(sourceLabel);
        holder.metaSize.setText(sizeLabel);
        int indexNumber = itemNumberForPosition(position);
        holder.index.setText(indexNumber > 0 ? ("#" + indexNumber) : "");
        holder.index.setTextColor(android.graphics.Color.parseColor("#E8D9B3"));

        holder.cardContainer.setTearSeed(snapshotId);
        holder.cardContainer.setTearStyle(6.6f, 1.85f);
        holder.cardContainer.setRealismMode(isLowRamDevice(holder.itemView.getContext()));
        holder.cardContainer.setCardFillStyle(ForensicsCardContainerView.CARD_FILL_DOSSIER);

        holder.selectionDimOverlay.setVisibility(selected ? View.VISIBLE : View.GONE);
        if (selectionMode) {
            holder.iconCheckContainer.setVisibility(View.VISIBLE);
            holder.iconCheck.setAlpha(selected ? 1f : 0f);
            holder.iconCheck.setScaleX(selected ? 1f : 0f);
            holder.iconCheck.setScaleY(selected ? 1f : 0f);
        } else {
            holder.iconCheckContainer.setVisibility(View.GONE);
            holder.iconCheck.setAlpha(0f);
            holder.iconCheck.setScaleX(0f);
            holder.iconCheck.setScaleY(0f);
        }

        int snapshotHash = Math.abs(snapshotId.hashCode());
        boolean showDecor = tornTheme;
        holder.paperClip.setVisibility(showDecor ? View.VISIBLE : View.GONE);
        holder.indexTape.setVisibility(showDecor ? View.VISIBLE : View.GONE);
        holder.paperClip.setImageResource(CLIP_STYLE_RED.equalsIgnoreCase(clipStyle)
                ? R.drawable.binder_clip_red_asset
                : R.drawable.binder_clip_glossy_black_asset);
        holder.indexTape.setBackgroundResource(TAPE_STYLE_CLASSIC.equalsIgnoreCase(tapeStyle)
                ? R.drawable.forensics_index_tape_alt
                : R.drawable.forensics_index_tape);
        holder.indexTape.setRotation((currentGridSpan >= 3 ? -2.5f : -4f) - (snapshotHash % 4));
        holder.cardMotionLayer.setRotation(0f);
        applyGridSizing(holder);

        // Classified Mode: hide evidence thumbnail behind redacted overlay
        if (hideThumbnails) {
            holder.image.setImageResource(R.drawable.ic_visibility_off);
            holder.image.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            holder.image.setBackgroundColor(0xFF1A1A2E);
            holder.image.setColorFilter(0x66FFFFFF, android.graphics.PorterDuff.Mode.SRC_IN);
        } else {
            holder.image.setColorFilter(null);
            holder.image.setBackgroundColor(android.graphics.Color.TRANSPARENT);
            holder.image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            Glide.with(holder.image)
                    .load(Uri.parse(row.imageUri))
                    .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
                    .centerCrop()
                    .placeholder(R.drawable.ic_photo)
                    .error(R.drawable.ic_photo)
                    .into(holder.image);
        }

        holder.cardMotionLayer.setScaleX(1f);
        holder.cardMotionLayer.setScaleY(1f);
        holder.cardMotionLayer.setAlpha(1f);

        holder.itemView.setOnClickListener(v -> {
            if (selectionMode) {
                toggleItem(row);
            } else if (listener != null) {
                listener.onOpenViewer(row, mediaMissing);
            }
        });
        holder.itemView.setOnTouchListener((v, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    holder.cardMotionLayer.animate()
                            .cancel();
                    holder.cardMotionLayer.animate()
                            .scaleX(0.985f)
                            .scaleY(0.985f)
                            .alpha(0.9f)
                            .setDuration(72L)
                            .start();
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    holder.cardMotionLayer.animate()
                            .cancel();
                    holder.cardMotionLayer.animate()
                            .scaleX(1f)
                            .scaleY(1f)
                            .alpha(1f)
                            .setDuration(115L)
                            .start();
                    break;
                default:
                    break;
            }
            return false;
        });
        holder.itemView.setOnLongClickListener(v -> {
            selectionMode = true;
            toggleItem(row);
            return true;
        });
    }

    private void applyGridSizing(@NonNull ItemHolder holder) {
        float titleSp;
        float metaSp;
        int tapeTopMarginDp;
        int tapeStartMarginDp;
        int tapePadH;
        int tapePadV;
        int indexTextSp;
        boolean showMetaSize;
        switch (currentGridSpan) {
            case 5:
                titleSp = 9.5f;
                metaSp = 7.5f;
                tapeTopMarginDp = 41;
                tapeStartMarginDp = 6;
                tapePadH = 1;
                tapePadV = 0;
                indexTextSp = 6;
                showMetaSize = false;
                break;
            case 4:
                titleSp = 10.5f;
                metaSp = 8.6f;
                tapeTopMarginDp = 41;
                tapeStartMarginDp = 9;
                tapePadH = 2;
                tapePadV = 0;
                indexTextSp = 7;
                showMetaSize = false;
                break;
            case 3:
                titleSp = 11f;
                metaSp = 9f;
                tapeTopMarginDp = 39;
                tapeStartMarginDp = 10;
                tapePadH = 2;
                tapePadV = 0;
                indexTextSp = 7;
                showMetaSize = true;
                break;
            case 1:
                titleSp = 14f;
                metaSp = 11f;
                tapeTopMarginDp = 18;
                tapeStartMarginDp = 14;
                tapePadH = 4;
                tapePadV = 2;
                indexTextSp = 9;
                showMetaSize = true;
                break;
            default:
                titleSp = 12f;
                metaSp = 10f;
                tapeTopMarginDp = 18;
                tapeStartMarginDp = 14;
                tapePadH = 3;
                tapePadV = 1;
                indexTextSp = 8;
                showMetaSize = true;
                break;
        }
        int width = holder.itemView.getWidth();
        if (width <= 0) {
            holder.itemView.post(() -> applyGridSizing(holder));
            return;
        }
        int targetHeight;
        if (currentGridSpan >= 5) {
            targetHeight = dp(holder.itemView, 80f);
        } else if (currentGridSpan == 4) {
            targetHeight = dp(holder.itemView, 96f);
        } else if (currentGridSpan == 3) {
            targetHeight = dp(holder.itemView, 112f);
        } else if (currentGridSpan == 1) {
            targetHeight = dp(holder.itemView, 180f);
        } else {
            targetHeight = dp(holder.itemView, 132f);
        }
        ViewGroup.LayoutParams photoLp = holder.photoContainer.getLayoutParams();
        if (photoLp.height != targetHeight) {
            photoLp.height = targetHeight;
            holder.photoContainer.setLayoutParams(photoLp);
        }
        holder.title.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, titleSp);
        holder.metaTime.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, metaSp);
        holder.metaConfidence.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, metaSp);
        holder.metaSource.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, metaSp);
        holder.metaSize.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, metaSp);
        holder.index.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, indexTextSp);
        holder.metaTime.setSingleLine(true);
        holder.metaConfidence.setSingleLine(true);
        holder.metaSource.setSingleLine(true);
        holder.metaSize.setSingleLine(true);

        ViewGroup.MarginLayoutParams tapeLp = (ViewGroup.MarginLayoutParams) holder.indexTape.getLayoutParams();
        int tapeTopPx = dp(holder.itemView, tapeTopMarginDp);
        int tapeStartPx = dp(holder.itemView, tapeStartMarginDp);
        if (tapeLp.topMargin != tapeTopPx || tapeLp.getMarginStart() != tapeStartPx) {
            tapeLp.topMargin = tapeTopPx;
            tapeLp.setMarginStart(tapeStartPx);
            holder.indexTape.setLayoutParams(tapeLp);
        }
        holder.indexTape.setPadding(dp(holder.itemView, tapePadH), dp(holder.itemView, tapePadV), dp(holder.itemView, tapePadH), dp(holder.itemView, tapePadV));
        if (currentGridSpan >= 5) {
            // At 5 columns: hide all meta except source (repurposed for size)
            holder.iconConfidence.setVisibility(View.GONE);
            holder.metaConfidence.setVisibility(View.GONE);
            holder.iconSource.setImageResource(R.drawable.database_24px);
            holder.metaSource.setText(holder.metaSize.getText());
            holder.iconSize.setVisibility(View.GONE);
            holder.metaSize.setVisibility(View.GONE);
            holder.title.setMaxLines(1);
        } else if (currentGridSpan >= 4) {
            holder.iconConfidence.setVisibility(View.GONE);
            holder.metaConfidence.setVisibility(View.GONE);
            holder.iconSource.setImageResource(R.drawable.database_24px);
            holder.metaSource.setText(holder.metaSize.getText());
            holder.iconSize.setVisibility(View.GONE);
            holder.metaSize.setVisibility(View.GONE);
            holder.title.setMaxLines(1);
        } else {
            holder.iconConfidence.setVisibility(View.VISIBLE);
            holder.metaConfidence.setVisibility(View.VISIBLE);
            holder.iconSource.setImageResource(R.drawable.ic_info);
            holder.iconSize.setVisibility(showMetaSize ? View.VISIBLE : View.GONE);
            holder.metaSize.setVisibility(showMetaSize ? View.VISIBLE : View.GONE);
            holder.title.setMaxLines(currentGridSpan == 3 ? 1 : 2);
        }
    }

    private static int dp(@NonNull View view, float value) {
        return Math.round(value * view.getResources().getDisplayMetrics().density);
    }

    public boolean isMonthHeaderPosition(int position) {
        return position >= 0 && position < entries.size() && entries.get(position) instanceof HeaderEntry;
    }

    public boolean isItemPosition(int position) {
        return position >= 0 && position < entries.size() && entries.get(position) instanceof ItemEntry;
    }

    @Nullable
    public String monthKeyAt(int position) {
        if (position < 0 || position >= entries.size()) {
            return null;
        }
        Entry entry = entries.get(position);
        if (entry instanceof HeaderEntry) {
            return ((HeaderEntry) entry).monthKey;
        }
        if (entry instanceof ItemEntry) {
            return ((ItemEntry) entry).monthKey;
        }
        return null;
    }

    private int itemNumberForPosition(int adapterPosition) {
        if (itemNumbers != null && adapterPosition >= 0 && adapterPosition < itemNumbers.length) {
            return itemNumbers[adapterPosition];
        }
        // Fallback — should not happen
        return 0;
    }

    private void toggleItem(@NonNull ForensicsSnapshotWithMedia row) {
        String id = snapshotId(row);
        if (selectedSnapshotIds.contains(id)) {
            selectedSnapshotIds.remove(id);
        } else {
            selectedSnapshotIds.add(id);
        }
        if (selectedSnapshotIds.isEmpty()) {
            selectionMode = false;
        }
        dispatchSelection();
        notifyDataSetChanged();
    }

    private void dispatchSelection() {
        if (listener != null) {
            listener.onSelectionChanged(selectedSnapshotIds.size());
        }
    }

    private void rebuildEntries() {
        entries.clear();
        monthIndex.clear();
        String lastMonth = null;
        for (ForensicsSnapshotWithMedia row : rows) {
            String month = monthKey(row.capturedEpochMs);
            if (!month.equals(lastMonth)) {
                entries.add(new HeaderEntry(month));
                lastMonth = month;
            }
            entries.add(new ItemEntry(row, month));

            // Build month → rows index
            List<ForensicsSnapshotWithMedia> monthList = monthIndex.get(month);
            if (monthList == null) {
                monthList = new ArrayList<>();
                monthIndex.put(month, monthList);
            }
            monthList.add(row);
        }

        // Pre-compute item numbers (1-based sequential for item entries, 0 for headers)
        itemNumbers = new int[entries.size()];
        int counter = 0;
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i) instanceof ItemEntry) {
                counter++;
                itemNumbers[i] = counter;
            }
        }
    }

    @NonNull
    private Set<String> currentIds() {
        Set<String> ids = new LinkedHashSet<>();
        for (ForensicsSnapshotWithMedia row : rows) {
            ids.add(snapshotId(row));
        }
        return ids;
    }

    @NonNull
    private List<ForensicsSnapshotWithMedia> rowsForMonth(@NonNull String monthKey) {
        List<ForensicsSnapshotWithMedia> cached = monthIndex.get(monthKey);
        return cached != null ? cached : new ArrayList<>();
    }

    @NonNull
    private String snapshotId(@NonNull ForensicsSnapshotWithMedia row) {
        if (row.snapshotUid != null && !row.snapshotUid.isEmpty()) {
            return row.snapshotUid;
        }
        return safe(row.imageUri)
                + "|" + safe(row.mediaUri)
                + "|" + safe(row.mediaUid)
                + "|" + safe(row.eventUid)
                + "|" + safe(row.className)
                + "|" + safe(row.eventType)
                + "|" + row.timelineMs
                + "|" + row.capturedEpochMs;
    }

    private boolean isMediaMissing(@Nullable String mediaUri) {
        if (mediaUri == null || mediaUri.isEmpty()) return true;
        try {
            Uri uri = Uri.parse(mediaUri);
            if ("file".equalsIgnoreCase(uri.getScheme())) {
                String path = uri.getPath();
                return path == null || !(new java.io.File(path).exists());
            }
            return false;
        } catch (Exception e) {
            return true;
        }
    }

    /**
     * File size with in-memory cache. Safe to call from any thread.
     */
    private long resolveImageSizeCached(@Nullable String imageUri) {
        if (imageUri == null || imageUri.isEmpty()) return 0L;
        Long cached = fileSizeCache.get(imageUri);
        if (cached != null) return cached;
        long size = resolveImageSize(imageUri);
        fileSizeCache.put(imageUri, size);
        return size;
    }

    private long resolveImageSize(@Nullable String imageUri) {
        if (imageUri == null || imageUri.isEmpty()) return 0L;
        try {
            Uri uri = Uri.parse(imageUri);
            if ("file".equalsIgnoreCase(uri.getScheme())) {
                String path = uri.getPath();
                if (path == null) return 0L;
                java.io.File file = new java.io.File(path);
                return file.exists() ? file.length() : 0L;
            }
        } catch (Exception ignored) {
        }
        return 0L;
    }

    private String monthKey(long epochMs) {
        if (epochMs <= 0L) return "Unknown";
        return monthFormat.format(new Date(epochMs));
    }

    @NonNull
    static String deriveDisplayName(@NonNull ForensicsSnapshotWithMedia row) {
        if (row.mediaDisplayName != null && !row.mediaDisplayName.isEmpty()) return row.mediaDisplayName;
        if (row.mediaUri == null || row.mediaUri.isEmpty()) return "Unknown media";
        String decoded = Uri.decode(row.mediaUri);
        int slash = decoded.lastIndexOf('/');
        return slash >= 0 ? decoded.substring(slash + 1) : decoded;
    }

    @NonNull
    static String safe(String value) {
        return value == null ? "" : value;
    }

    private boolean isTornThemeEnabled(@NonNull android.content.Context context) {
        if (tornThemeEnabled != null) {
            return tornThemeEnabled;
        }
        boolean defaultEnabled = BuildConfig.DEBUG || BuildConfig.APPLICATION_ID.contains("beta");
        try {
            tornThemeEnabled = SharedPreferencesManager.getInstance(context)
                    .getBoolean(PREF_TORN_THEME_ENABLED, defaultEnabled);
        } catch (Exception ignored) {
            tornThemeEnabled = defaultEnabled;
        }
        return tornThemeEnabled;
    }

    private boolean isLowRamDevice(@NonNull android.content.Context context) {
        if (lowRamDevice != null) {
            return lowRamDevice;
        }
        ActivityManager am = (ActivityManager) context.getSystemService(android.content.Context.ACTIVITY_SERVICE);
        lowRamDevice = am != null && am.isLowRamDevice();
        return lowRamDevice;
    }

    private void logIdentityIntegrityIfNeeded() {
        if (!BuildConfig.DEBUG) {
            return;
        }
        Set<String> ids = new HashSet<>();
        int collisionCount = 0;
        for (ForensicsSnapshotWithMedia row : rows) {
            String id = snapshotId(row);
            if (!ids.add(id)) {
                collisionCount++;
            }
        }
        if (collisionCount > 0) {
            Log.w(TAG, "submit integrity warning: duplicate snapshot identity count=" + collisionCount + ", rows=" + rows.size());
        }
    }

    @Override
    public int getItemCount() {
        return entries.size();
    }

    static class ItemHolder extends RecyclerView.ViewHolder {
        final View cardMotionLayer;
        final ForensicsCardContainerView cardContainer;
        final View photoContainer;
        final ImageView image;
        final View iconCheckContainer;
        final ImageView iconCheck;
        final View selectionDimOverlay;
        final TextView title;
        final TextView metaTime;
        final TextView metaConfidence;
        final TextView metaSource;
        final TextView metaSize;
        final ImageView iconConfidence;
        final ImageView iconSource;
        final ImageView iconSize;
        final TextView index;
        final View indexTape;
        final ImageView paperClip;

        ItemHolder(@NonNull View itemView) {
            super(itemView);
            cardMotionLayer = itemView.findViewById(R.id.card_motion_layer);
            cardContainer = itemView.findViewById(R.id.root_gallery_card);
            photoContainer = itemView.findViewById(R.id.section_gallery_photo_container);
            image = itemView.findViewById(R.id.image_gallery_snapshot);
            iconCheckContainer = itemView.findViewById(R.id.icon_check_container);
            iconCheck = itemView.findViewById(R.id.icon_check);
            selectionDimOverlay = itemView.findViewById(R.id.selection_dim_overlay);
            title = itemView.findViewById(R.id.text_gallery_title);
            metaTime = itemView.findViewById(R.id.text_gallery_meta_time);
            metaConfidence = itemView.findViewById(R.id.text_gallery_meta_confidence);
            metaSource = itemView.findViewById(R.id.text_gallery_meta_source);
            metaSize = itemView.findViewById(R.id.text_gallery_meta_size);
            iconConfidence = itemView.findViewById(R.id.icon_gallery_meta_confidence);
            iconSource = itemView.findViewById(R.id.icon_gallery_meta_source);
            iconSize = itemView.findViewById(R.id.icon_gallery_meta_size);
            index = itemView.findViewById(R.id.text_gallery_index);
            indexTape = itemView.findViewById(R.id.tape_index_container);
            paperClip = itemView.findViewById(R.id.view_paper_clip);
        }
    }

    static class MonthHolder extends RecyclerView.ViewHolder {
        final TextView title;
        final View selectContainer;
        final ImageView selectBg;
        final ImageView selectCheck;

        MonthHolder(@NonNull View itemView) {
            super(itemView);
            title = itemView.findViewById(R.id.text_month_title);
            selectContainer = itemView.findViewById(R.id.month_select_container);
            selectBg = itemView.findViewById(R.id.month_select_bg);
            selectCheck = itemView.findViewById(R.id.month_select_check);
        }
    }
}
