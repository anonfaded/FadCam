package com.fadcam.forensics.ui;

import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.util.LruCache;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.fadcam.R;
import com.fadcam.forensics.data.local.model.AiEventWithMedia;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ForensicsEventsAdapter extends RecyclerView.Adapter<ForensicsEventsAdapter.Holder> {

    public interface Listener {
        void onEventClicked(AiEventWithMedia row);
    }

    private final Listener listener;
    private final List<AiEventWithMedia> rows = new ArrayList<>();
    private final ExecutorService thumbExecutor = Executors.newSingleThreadExecutor();
    private final LruCache<String, Bitmap> thumbCache = new LruCache<>(48);

    public ForensicsEventsAdapter(Listener listener) {
        this.listener = listener;
    }

    public void submit(List<AiEventWithMedia> data) {
        rows.clear();
        if (data != null) {
            rows.addAll(data);
        }
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_forensics_event, parent, false);
        return new Holder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position) {
        AiEventWithMedia row = rows.get(position);
        String title = row.eventType + " event";
        String mediaName = row.mediaDisplayName != null ? row.mediaDisplayName : "Unknown media";
        long startSec = Math.max(0L, row.startMs / 1000L);
        long endSec = Math.max(startSec, row.endMs / 1000L);

        holder.title.setText(title);
        holder.subtitle.setText(String.format(Locale.US,
                "%s • %s-%s • conf %.2f",
                mediaName,
                formatTime(startSec),
                formatTime(endSec),
                row.confidence));

        String badgeText = "EXACT";
        if (row.linkStatus != null && !row.linkStatus.isEmpty()) {
            badgeText = row.linkStatus;
        }
        holder.badge.setText(badgeText);
        bindProofImage(holder, row, startSec);

        holder.itemView.setOnClickListener(v -> {
            if (listener != null) {
                listener.onEventClicked(row);
            }
        });
    }

    private void bindProofImage(@NonNull Holder holder, AiEventWithMedia row, long startSec) {
        holder.proof.setImageResource(R.drawable.ic_photo);
        if (row == null || row.mediaUri == null || row.mediaUri.isEmpty()) {
            return;
        }
        final String key = row.mediaUri + "#" + startSec;
        holder.proof.setTag(key);
        Bitmap cached = thumbCache.get(key);
        if (cached != null) {
            holder.proof.setImageBitmap(cached);
            return;
        }
        thumbExecutor.execute(() -> {
            Bitmap frame = extractFrame(holder.itemView.getContext(), row.mediaUri, startSec * 1_000_000L);
            if (frame != null) {
                thumbCache.put(key, frame);
                holder.itemView.post(() -> {
                    Object currentTag = holder.proof.getTag();
                    if (key.equals(currentTag)) {
                        holder.proof.setImageBitmap(frame);
                    }
                });
            }
        });
    }

    private Bitmap extractFrame(android.content.Context context, String uriString, long timeUs) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(context, Uri.parse(uriString));
            return retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
        } catch (Exception ignored) {
            return null;
        } finally {
            try {
                retriever.release();
            } catch (Exception ignored) {
            }
        }
    }

    private String formatTime(long seconds) {
        long mins = seconds / 60L;
        long secs = seconds % 60L;
        return String.format(Locale.US, "%d:%02d", mins, secs);
    }

    @Override
    public int getItemCount() {
        return rows.size();
    }

    static class Holder extends RecyclerView.ViewHolder {
        final TextView title;
        final TextView subtitle;
        final TextView badge;
        final ImageView proof;

        Holder(@NonNull View itemView) {
            super(itemView);
            title = itemView.findViewById(R.id.text_title);
            subtitle = itemView.findViewById(R.id.text_subtitle);
            badge = itemView.findViewById(R.id.text_badge);
            proof = itemView.findViewById(R.id.image_proof);
        }
    }
}
