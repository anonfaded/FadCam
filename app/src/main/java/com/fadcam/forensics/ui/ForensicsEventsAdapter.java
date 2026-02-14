package com.fadcam.forensics.ui;

import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.util.LruCache;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.fadcam.R;
import com.fadcam.SharedPreferencesManager;
import com.fadcam.forensics.data.local.model.AiEventWithMedia;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ForensicsEventsAdapter extends RecyclerView.Adapter<ForensicsEventsAdapter.Holder> {

    public interface Listener {
        void onEventClicked(AiEventWithMedia row);
        void onEventFrameClicked(AiEventWithMedia row, long targetMs);
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
        String classLabel = (row.className != null && !row.className.trim().isEmpty())
                ? row.className.trim().toUpperCase(Locale.US)
                : row.eventType;
        String title = classLabel + " event";
        String mediaName = deriveDisplayName(row);
        long startSec = Math.max(0L, row.startMs / 1000L);
        long endSec = Math.max(startSec, row.endMs / 1000L);

        holder.title.setText(title);
        holder.subtitle.setText(String.format(Locale.US,
                "%s • %s • %s-%s • conf %.2f",
                mediaName,
                row.eventType,
                formatTime(startSec),
                formatTime(endSec),
                row.confidence));

        boolean seen = isEventSeen(holder.itemView, row.eventUid);
        if (!seen && isRecent(row)) {
            holder.badge.setVisibility(View.VISIBLE);
            holder.badge.setText("NEW");
        } else if (row.linkStatus != null && !row.linkStatus.isEmpty()) {
            holder.badge.setVisibility(View.VISIBLE);
            holder.badge.setText(row.linkStatus);
        } else {
            holder.badge.setVisibility(View.GONE);
        }
        bindProofImage(holder, row, row.startMs);
        bindPersonFrameStrip(holder, row);

        holder.itemView.setOnClickListener(v -> {
            if (listener != null) {
                markEventSeen(v, row.eventUid);
                notifyItemChanged(holder.getBindingAdapterPosition());
                listener.onEventClicked(row);
            }
        });
    }

    private void bindProofImage(@NonNull Holder holder, AiEventWithMedia row, long startMs) {
        holder.proof.setImageResource(R.drawable.ic_photo);
        if (row == null || row.mediaUri == null || row.mediaUri.isEmpty()) {
            return;
        }
        final String key = row.mediaUri + "#" + startMs;
        holder.proof.setTag(key);
        Bitmap cached = thumbCache.get(key);
        if (cached != null) {
            holder.proof.setImageBitmap(cached);
            return;
        }
        thumbExecutor.execute(() -> {
            Bitmap frame = extractFrame(holder.itemView.getContext(), row.mediaUri, startMs * 1_000L);
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

    private void bindPersonFrameStrip(@NonNull Holder holder, AiEventWithMedia row) {
        if (row.mediaUri == null || row.mediaUri.isEmpty()) {
            holder.frameStrip.setVisibility(View.GONE);
            holder.framesContainer.removeAllViews();
            return;
        }
        holder.frameStrip.setVisibility(View.VISIBLE);
        holder.framesContainer.removeAllViews();

        long start = Math.max(0L, row.startMs);
        long mid = Math.max(start, (row.startMs + row.endMs) / 2L);
        long end = Math.max(start, row.endMs - 250L);
        addFrameThumb(holder, row, start);
        addFrameThumb(holder, row, mid);
        if (end != mid) {
            addFrameThumb(holder, row, end);
        }
    }

    private void addFrameThumb(@NonNull Holder holder, AiEventWithMedia row, long frameMs) {
        android.content.Context context = holder.itemView.getContext();
        LinearLayout wrap = new LinearLayout(context);
        wrap.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams wrapLp = new LinearLayout.LayoutParams(
                dp(context, 64),
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        wrapLp.setMarginEnd(dp(context, 8));
        wrap.setLayoutParams(wrapLp);
        wrap.setGravity(Gravity.CENTER_HORIZONTAL);

        ImageView image = new ImageView(context);
        LinearLayout.LayoutParams imageLp = new LinearLayout.LayoutParams(dp(context, 64), dp(context, 44));
        image.setLayoutParams(imageLp);
        image.setBackgroundResource(R.drawable.motion_lab_frame_bg);
        image.setClipToOutline(true);
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        image.setImageResource(R.drawable.ic_photo);
        wrap.addView(image);

        TextView time = new TextView(context);
        time.setTextColor(0xFF9E9E9E);
        time.setTextSize(10f);
        time.setText(formatTime(Math.max(0L, frameMs / 1000L)));
        wrap.addView(time);

        final String key = row.mediaUri + "#p:" + frameMs;
        image.setTag(key);
        Bitmap cached = thumbCache.get(key);
        if (cached != null) {
            image.setImageBitmap(cached);
        } else {
            thumbExecutor.execute(() -> {
                Bitmap frame = extractFrame(context, row.mediaUri, frameMs * 1_000L);
                if (frame != null) {
                    thumbCache.put(key, frame);
                    holder.itemView.post(() -> {
                        Object currentTag = image.getTag();
                        if (key.equals(currentTag)) {
                            image.setImageBitmap(frame);
                        }
                    });
                }
            });
        }

        wrap.setOnClickListener(v -> {
            if (listener != null) {
                markEventSeen(v, row.eventUid);
                listener.onEventFrameClicked(row, frameMs);
            }
        });
        holder.framesContainer.addView(wrap);
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

    private String deriveDisplayName(@NonNull AiEventWithMedia row) {
        String candidate = row.mediaDisplayName;
        if (candidate != null && !candidate.isEmpty()
                && !candidate.startsWith("content://")
                && !candidate.contains("/")
                && !candidate.contains("%2F")) {
            return candidate;
        }
        if (row.mediaUri == null || row.mediaUri.isEmpty()) {
            return "Unknown media";
        }
        String decoded = Uri.decode(row.mediaUri);
        int slash = decoded.lastIndexOf('/');
        String tail = slash >= 0 ? decoded.substring(slash + 1) : decoded;
        int colon = tail.lastIndexOf(':');
        if (colon >= 0 && colon < tail.length() - 1) {
            tail = tail.substring(colon + 1);
        }
        return tail.isEmpty() ? "Unknown media" : tail;
    }

    private boolean isRecent(@NonNull AiEventWithMedia row) {
        long ts = row.detectedAtEpochMs > 0L ? row.detectedAtEpochMs : System.currentTimeMillis();
        return (System.currentTimeMillis() - ts) < (24L * 60L * 60L * 1000L);
    }

    private boolean isEventSeen(@NonNull View view, String eventUid) {
        if (eventUid == null || eventUid.isEmpty()) {
            return false;
        }
        SharedPreferencesManager prefs = SharedPreferencesManager.getInstance(view.getContext());
        return prefs.getBoolean("forensics_event_seen_" + eventUid, false);
    }

    private void markEventSeen(@NonNull View view, String eventUid) {
        if (eventUid == null || eventUid.isEmpty()) {
            return;
        }
        SharedPreferencesManager prefs = SharedPreferencesManager.getInstance(view.getContext());
        prefs.putBoolean("forensics_event_seen_" + eventUid, true);
    }

    private int dp(@NonNull android.content.Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
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
        final View frameStrip;
        final LinearLayout framesContainer;

        Holder(@NonNull View itemView) {
            super(itemView);
            title = itemView.findViewById(R.id.text_title);
            subtitle = itemView.findViewById(R.id.text_subtitle);
            badge = itemView.findViewById(R.id.text_badge);
            proof = itemView.findViewById(R.id.image_proof);
            frameStrip = itemView.findViewById(R.id.person_frames_strip);
            framesContainer = itemView.findViewById(R.id.person_frames_container);
        }
    }
}
