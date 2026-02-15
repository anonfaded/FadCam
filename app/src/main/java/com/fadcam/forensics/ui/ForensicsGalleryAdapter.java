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
import java.util.HashSet;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class ForensicsGalleryAdapter extends RecyclerView.Adapter<ForensicsGalleryAdapter.Holder> {

    interface Listener {
        void onSelectionChanged(int selectedCount);
        void onOpenViewer(@NonNull ForensicsSnapshotWithMedia row, boolean mediaMissing);
    }

    private final List<ForensicsSnapshotWithMedia> rows = new ArrayList<>();
    private final Set<String> selectedSnapshotIds = new HashSet<>();
    private boolean selectionMode;
    private Listener listener;

    public ForensicsGalleryAdapter() {
        setHasStableIds(true);
    }

    public void setListener(@Nullable Listener listener) {
        this.listener = listener;
    }

    public void submit(List<ForensicsSnapshotWithMedia> data) {
        rows.clear();
        if (data != null) {
            rows.addAll(data);
        }
        selectedSnapshotIds.retainAll(getCurrentSnapshotIds());
        if (selectedSnapshotIds.isEmpty()) {
            selectionMode = false;
        }
        dispatchSelection();
        notifyDataSetChanged();
    }

    @Override
    public long getItemId(int position) {
        ForensicsSnapshotWithMedia row = rows.get(position);
        if (row.snapshotUid != null) {
            return row.snapshotUid.hashCode();
        }
        String fallback = (row.imageUri == null ? "" : row.imageUri) + "|" + row.timelineMs;
        return fallback.hashCode();
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_forensics_gallery, parent, false);
        return new Holder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position) {
        ForensicsSnapshotWithMedia row = rows.get(position);
        boolean mediaMissing = row.mediaMissing || isMediaMissing(row.mediaUri);
        String snapshotId = getSnapshotId(row);
        boolean selected = selectionMode && selectedSnapshotIds.contains(snapshotId);
        bindMonthHeader(holder, position, row);
        String classLabel = (row.className == null || row.className.trim().isEmpty())
                ? (row.eventType == null ? "object" : row.eventType.toLowerCase(Locale.US))
                : row.className.toLowerCase(Locale.US);
        holder.title.setText(classLabel + " • " + formatTimelineMs(row.timelineMs));
        String sourceLabel = mediaMissing ? "snapshot-only" : "video-linked";
        long imageSize = resolveImageSize(row.imageUri);
        String sizeLabel = imageSize > 0L ? Formatter.formatShortFileSize(holder.itemView.getContext(), imageSize) : "0B";
        holder.meta.setText(String.format(
                Locale.US,
                "%s • conf %.2f • %s • %s",
                formatDate(row.capturedEpochMs),
                row.confidence,
                sourceLabel,
                sizeLabel
        ));
        holder.index.setText(String.valueOf(position + 1));
        holder.selectionDimOverlay.setVisibility((selectionMode && selected) ? View.VISIBLE : View.GONE);
        if (selectionMode) {
            holder.iconCheckContainer.setVisibility(View.VISIBLE);
            if (selected) {
                holder.iconCheck.setVisibility(View.VISIBLE);
                holder.iconCheck.setAlpha(1f);
                holder.iconCheck.setScaleX(1f);
                holder.iconCheck.setScaleY(1f);
            } else {
                holder.iconCheck.setAlpha(0f);
                holder.iconCheck.setScaleX(0f);
                holder.iconCheck.setScaleY(0f);
            }
        } else {
            holder.iconCheckContainer.setVisibility(View.GONE);
            holder.iconCheck.setAlpha(0f);
            holder.iconCheck.setScaleX(0f);
            holder.iconCheck.setScaleY(0f);
        }
        holder.itemView.setAlpha(1f);

        Glide.with(holder.image)
                .load(Uri.parse(row.imageUri))
                .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
                .centerCrop()
                .placeholder(R.drawable.ic_photo)
                .error(R.drawable.ic_photo)
                .into(holder.image);

        holder.itemView.setOnClickListener(v -> {
            if (selectionMode) {
                toggleSelection(row);
                return;
            }
            if (listener != null) {
                listener.onOpenViewer(row, mediaMissing);
            }
        });

        holder.itemView.setOnLongClickListener(v -> {
            selectionMode = true;
            toggleSelection(row);
            return true;
        });
    }

    private void bindMonthHeader(@NonNull Holder holder, int position, @NonNull ForensicsSnapshotWithMedia row) {
        String current = monthKey(row.capturedEpochMs);
        String previous = null;
        if (position > 0) {
            previous = monthKey(rows.get(position - 1).capturedEpochMs);
        }
        boolean showHeader = position == 0 || !current.equals(previous);
        if (showHeader) {
            holder.monthHeader.setVisibility(View.VISIBLE);
            holder.monthHeader.setText(current);
        } else {
            holder.monthHeader.setVisibility(View.GONE);
            holder.monthHeader.setText("");
        }
    }

    @NonNull
    private String monthKey(long epochMs) {
        if (epochMs <= 0L) {
            return "Unknown";
        }
        return new SimpleDateFormat("MMMM yyyy", Locale.getDefault()).format(new Date(epochMs));
    }

    @Override
    public int getItemCount() {
        return rows.size();
    }

    public boolean isSelectionMode() {
        return selectionMode;
    }

    public int getSelectedCount() {
        return selectedSnapshotIds.size();
    }

    @NonNull
    public List<ForensicsSnapshotWithMedia> getSelectedRows() {
        if (selectedSnapshotIds.isEmpty()) {
            return new ArrayList<>();
        }
        List<ForensicsSnapshotWithMedia> selected = new ArrayList<>();
        for (ForensicsSnapshotWithMedia row : rows) {
            if (selectedSnapshotIds.contains(getSnapshotId(row))) {
                selected.add(row);
            }
        }
        return selected;
    }

    public void clearSelection() {
        if (selectedSnapshotIds.isEmpty() && !selectionMode) {
            return;
        }
        selectedSnapshotIds.clear();
        selectionMode = false;
        dispatchSelection();
        notifyDataSetChanged();
    }

    public void selectAll() {
        selectedSnapshotIds.clear();
        for (ForensicsSnapshotWithMedia row : rows) {
            selectedSnapshotIds.add(getSnapshotId(row));
        }
        selectionMode = !selectedSnapshotIds.isEmpty();
        dispatchSelection();
        notifyDataSetChanged();
    }

    private void toggleSelection(@NonNull ForensicsSnapshotWithMedia row) {
        String id = getSnapshotId(row);
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

    @NonNull
    private Set<String> getCurrentSnapshotIds() {
        Set<String> ids = new HashSet<>();
        for (ForensicsSnapshotWithMedia row : rows) {
            ids.add(getSnapshotId(row));
        }
        return ids;
    }

    @NonNull
    private String getSnapshotId(@NonNull ForensicsSnapshotWithMedia row) {
        if (row.snapshotUid != null && !row.snapshotUid.isEmpty()) {
            return row.snapshotUid;
        }
        return safe(row.imageUri) + "|" + row.timelineMs;
    }

    private long resolveImageSize(@Nullable String imageUri) {
        if (imageUri == null || imageUri.isEmpty()) {
            return 0L;
        }
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

    private String formatDate(long epochMs) {
        if (epochMs <= 0L) {
            return "Unknown";
        }
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
        if (row.mediaDisplayName != null && !row.mediaDisplayName.isEmpty()) {
            return row.mediaDisplayName;
        }
        if (row.mediaUri == null || row.mediaUri.isEmpty()) {
            return "Unknown media";
        }
        String decoded = Uri.decode(row.mediaUri);
        int slash = decoded.lastIndexOf('/');
        return slash >= 0 ? decoded.substring(slash + 1) : decoded;
    }

    @NonNull
    static String safe(String value) {
        return value == null ? "" : value;
    }

    private boolean isMediaMissing(String mediaUri) {
        if (mediaUri == null || mediaUri.isEmpty()) {
            return true;
        }
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

    static class Holder extends RecyclerView.ViewHolder {
        final ImageView image;
        final View iconCheckContainer;
        final ImageView iconCheck;
        final View selectionDimOverlay;
        final TextView title;
        final TextView meta;
        final TextView index;
        final TextView monthHeader;

        Holder(@NonNull View itemView) {
            super(itemView);
            image = itemView.findViewById(R.id.image_gallery_snapshot);
            iconCheckContainer = itemView.findViewById(R.id.icon_check_container);
            iconCheck = itemView.findViewById(R.id.icon_check);
            selectionDimOverlay = itemView.findViewById(R.id.selection_dim_overlay);
            title = itemView.findViewById(R.id.text_gallery_title);
            meta = itemView.findViewById(R.id.text_gallery_meta);
            index = itemView.findViewById(R.id.text_gallery_index);
            monthHeader = itemView.findViewById(R.id.text_gallery_month_header);
        }
    }
}
