package com.fadcam.forensics.ui;

import android.net.Uri;
import android.text.format.Formatter;
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
import com.fadcam.R;
import com.fadcam.forensics.data.local.model.ForensicsSnapshotWithMedia;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class ForensicsGalleryAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

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
    private Listener listener;
    private boolean selectionMode;

    public ForensicsGalleryAdapter() {
        setHasStableIds(true);
    }

    public void setListener(@Nullable Listener listener) {
        this.listener = listener;
    }

    public void submit(@Nullable List<ForensicsSnapshotWithMedia> data) {
        rows.clear();
        if (data != null) {
            rows.addAll(data);
        }
        selectedSnapshotIds.retainAll(currentIds());
        if (selectedSnapshotIds.isEmpty()) {
            selectionMode = false;
        }
        rebuildEntries();
        dispatchSelection();
        notifyDataSetChanged();
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

    public long getTotalImageBytes() {
        long total = 0L;
        for (ForensicsSnapshotWithMedia row : rows) {
            total += resolveImageSize(row.imageUri);
        }
        return total;
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

    @Override
    public long getItemId(int position) {
        Entry entry = entries.get(position);
        if (entry instanceof HeaderEntry) {
            return ("header_" + ((HeaderEntry) entry).monthKey).hashCode();
        }
        ForensicsSnapshotWithMedia row = ((ItemEntry) entry).row;
        return snapshotId(row).hashCode();
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
        if (!selectionMode) {
            holder.selectContainer.setVisibility(View.GONE);
            return;
        }
        holder.selectContainer.setVisibility(View.VISIBLE);
        boolean allInMonthSelected = true;
        List<ForensicsSnapshotWithMedia> monthRows = rowsForMonth(header.monthKey);
        for (ForensicsSnapshotWithMedia row : monthRows) {
            if (!selectedSnapshotIds.contains(snapshotId(row))) {
                allInMonthSelected = false;
                break;
            }
        }
        holder.selectBg.setVisibility(View.VISIBLE);
        holder.selectCheck.setVisibility(allInMonthSelected ? View.VISIBLE : View.INVISIBLE);
        holder.selectContainer.setOnClickListener(v -> {
            if (listener != null) {
                listener.onMonthSelectionRequested(header.monthKey);
            }
        });
    }

    private void bindItem(@NonNull ItemHolder holder, @NonNull ItemEntry entry, int position) {
        ForensicsSnapshotWithMedia row = entry.row;
        boolean mediaMissing = row.mediaMissing || isMediaMissing(row.mediaUri);
        boolean selected = selectionMode && selectedSnapshotIds.contains(snapshotId(row));

        String classLabel = (row.className == null || row.className.trim().isEmpty())
                ? (row.eventType == null ? "object" : row.eventType.toLowerCase(Locale.US))
                : row.className.toLowerCase(Locale.US);
        holder.title.setText(classLabel + " • " + formatTimelineMs(row.timelineMs));
        String sourceLabel = mediaMissing ? "snapshot-only" : "video-linked";
        String sizeLabel = Formatter.formatShortFileSize(holder.itemView.getContext(), Math.max(0L, resolveImageSize(row.imageUri)));
        holder.meta.setText(String.format(
                Locale.US,
                "%s • conf %.2f • %s • %s",
                formatDate(row.capturedEpochMs),
                row.confidence,
                sourceLabel,
                sizeLabel
        ));
        holder.index.setText(String.valueOf(itemNumberForPosition(position)));

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

        Glide.with(holder.image)
                .load(Uri.parse(row.imageUri))
                .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
                .centerCrop()
                .placeholder(R.drawable.ic_photo)
                .error(R.drawable.ic_photo)
                .into(holder.image);

        holder.itemView.setOnClickListener(v -> {
            if (selectionMode) {
                toggleItem(row);
            } else if (listener != null) {
                listener.onOpenViewer(row, mediaMissing);
            }
        });
        holder.itemView.setOnLongClickListener(v -> {
            selectionMode = true;
            toggleItem(row);
            return true;
        });
    }

    private int itemNumberForPosition(int adapterPosition) {
        int number = 0;
        for (int i = 0; i <= adapterPosition; i++) {
            if (entries.get(i) instanceof ItemEntry) {
                number++;
            }
        }
        return number;
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
        String lastMonth = null;
        for (ForensicsSnapshotWithMedia row : rows) {
            String month = monthKey(row.capturedEpochMs);
            if (!month.equals(lastMonth)) {
                entries.add(new HeaderEntry(month));
                lastMonth = month;
            }
            entries.add(new ItemEntry(row, month));
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
        List<ForensicsSnapshotWithMedia> out = new ArrayList<>();
        for (ForensicsSnapshotWithMedia row : rows) {
            if (monthKey(row.capturedEpochMs).equals(monthKey)) {
                out.add(row);
            }
        }
        return out;
    }

    @NonNull
    private String snapshotId(@NonNull ForensicsSnapshotWithMedia row) {
        if (row.snapshotUid != null && !row.snapshotUid.isEmpty()) {
            return row.snapshotUid;
        }
        return safe(row.imageUri) + "|" + row.timelineMs;
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
        return new SimpleDateFormat("MMMM yyyy", Locale.getDefault()).format(new Date(epochMs));
    }

    private String formatDate(long epochMs) {
        if (epochMs <= 0L) return "Unknown";
        DateFormat formatter = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, Locale.getDefault());
        return formatter.format(new Date(epochMs));
    }

    private String formatTimelineMs(long ms) {
        long totalSec = Math.max(0L, ms / 1000L);
        long mins = totalSec / 60L;
        long secs = totalSec % 60L;
        return String.format(Locale.US, "%d:%02d", mins, secs);
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

    @Override
    public int getItemCount() {
        return entries.size();
    }

    static class ItemHolder extends RecyclerView.ViewHolder {
        final ImageView image;
        final View iconCheckContainer;
        final ImageView iconCheck;
        final View selectionDimOverlay;
        final TextView title;
        final TextView meta;
        final TextView index;

        ItemHolder(@NonNull View itemView) {
            super(itemView);
            image = itemView.findViewById(R.id.image_gallery_snapshot);
            iconCheckContainer = itemView.findViewById(R.id.icon_check_container);
            iconCheck = itemView.findViewById(R.id.icon_check);
            selectionDimOverlay = itemView.findViewById(R.id.selection_dim_overlay);
            title = itemView.findViewById(R.id.text_gallery_title);
            meta = itemView.findViewById(R.id.text_gallery_meta);
            index = itemView.findViewById(R.id.text_gallery_index);
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
