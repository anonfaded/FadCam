package com.fadcam.forensics.ui;

import android.content.Intent;
import android.net.Uri;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.fadcam.R;
import com.fadcam.forensics.data.local.model.ForensicsSnapshotWithMedia;
import com.fadcam.ui.VideoPlayerActivity;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class ForensicsGalleryAdapter extends RecyclerView.Adapter<ForensicsGalleryAdapter.Holder> {

    private final List<ForensicsSnapshotWithMedia> rows = new ArrayList<>();

    public void submit(List<ForensicsSnapshotWithMedia> data) {
        rows.clear();
        if (data != null) {
            rows.addAll(data);
        }
        notifyDataSetChanged();
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
        String classLabel = (row.className == null || row.className.trim().isEmpty())
                ? (row.eventType == null ? "object" : row.eventType.toLowerCase(Locale.US))
                : row.className.toLowerCase(Locale.US);
        holder.title.setText(classLabel + " • " + formatTimelineMs(row.timelineMs));
        String sourceLabel = row.mediaMissing ? "snapshot-only" : "video-linked";
        holder.meta.setText(String.format(
                Locale.US,
                "%s • conf %.2f • %s",
                formatDate(row.capturedEpochMs),
                row.confidence,
                sourceLabel
        ));

        Glide.with(holder.image)
                .load(Uri.parse(row.imageUri))
                .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
                .centerCrop()
                .placeholder(R.drawable.ic_photo)
                .error(R.drawable.ic_photo)
                .into(holder.image);

        holder.itemView.setOnClickListener(v -> {
            if (row.mediaUri == null || row.mediaUri.isEmpty() || row.mediaMissing) {
                Toast.makeText(v.getContext(), R.string.forensics_gallery_source_missing, Toast.LENGTH_SHORT).show();
                return;
            }
            Intent intent = new Intent(v.getContext(), VideoPlayerActivity.class);
            intent.setData(Uri.parse(row.mediaUri));
            intent.putExtra(ForensicsEventsFragment.EXTRA_OPEN_AT_MS, Math.max(0L, row.timelineMs));
            intent.putExtra(ForensicsEventsFragment.EXTRA_OPEN_PAUSED, true);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            v.getContext().startActivity(intent);
        });
    }

    @Override
    public int getItemCount() {
        return rows.size();
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

    static class Holder extends RecyclerView.ViewHolder {
        final ImageView image;
        final TextView title;
        final TextView meta;

        Holder(@NonNull View itemView) {
            super(itemView);
            image = itemView.findViewById(R.id.image_gallery_snapshot);
            title = itemView.findViewById(R.id.text_gallery_title);
            meta = itemView.findViewById(R.id.text_gallery_meta);
        }
    }
}
