package com.fadcam.forensics.ui;

import android.net.Uri;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.fadcam.R;
import com.fadcam.SharedPreferencesManager;
import com.fadcam.forensics.data.local.ForensicsDatabase;
import com.fadcam.forensics.data.local.entity.AiEventSnapshotEntity;
import com.fadcam.forensics.data.local.model.AiEventWithMedia;

import java.util.ArrayList;
import java.text.DateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import androidx.recyclerview.widget.DiffUtil;

public class ForensicsEventsAdapter extends RecyclerView.Adapter<ForensicsEventsAdapter.Holder> {

    public interface Listener {
        void onEventClicked(AiEventWithMedia row);
        void onEventFrameClicked(AiEventWithMedia row, long targetMs);
    }

    private final Listener listener;
    private final List<AiEventWithMedia> rows = new ArrayList<>();
    private final ExecutorService thumbExecutor = Executors.newSingleThreadExecutor();
    private volatile boolean isShutdown = false;

    /** In-memory cache: eventUid → best snapshot image URI. Survives scrolls, cleared on submit. */
    private final Map<String, String> proofImageCache = new HashMap<>();

    /** In-memory cache: eventUid → sampled snapshot list. Survives scrolls, cleared on submit. */
    private final Map<String, List<AiEventSnapshotEntity>> frameStripCache = new HashMap<>();

    public ForensicsEventsAdapter(Listener listener) {
        this.listener = listener;
    }

    public void submit(List<AiEventWithMedia> data) {
        List<AiEventWithMedia> oldList = new ArrayList<>(rows);
        rows.clear();
        proofImageCache.clear();
        frameStripCache.clear();
        if (data != null) {
            rows.addAll(data);
        }
        DiffUtil.DiffResult result = DiffUtil.calculateDiff(new DiffUtil.Callback() {
            @Override public int getOldListSize() { return oldList.size(); }
            @Override public int getNewListSize() { return rows.size(); }
            @Override
            public boolean areItemsTheSame(int oldPos, int newPos) {
                String oldId = oldList.get(oldPos).eventUid;
                String newId = rows.get(newPos).eventUid;
                if (oldId == null || newId == null) return oldPos == newPos;
                return oldId.equals(newId);
            }
            @Override
            public boolean areContentsTheSame(int oldPos, int newPos) {
                AiEventWithMedia o = oldList.get(oldPos);
                AiEventWithMedia n = rows.get(newPos);
                return o.endMs == n.endMs
                        && o.confidence == n.confidence
                        && o.mediaMissing == n.mediaMissing
                        && o.detectedAtEpochMs == n.detectedAtEpochMs;
            }
        }, false);
        result.dispatchUpdatesTo(this);
    }

    /**
     * Shuts down the background executor. Call from fragment onDestroyView.
     */
    public void shutdown() {
        isShutdown = true;
        thumbExecutor.shutdownNow();
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
        String detectedAt = formatDetectedAt(row.detectedAtEpochMs);

        holder.title.setText(title);
        holder.subtitle.setText(String.format(Locale.US,
                "%s • %s • %s-%s • conf %.2f • %s • %s",
                mediaName,
                row.eventType,
                formatTime(startSec),
                formatTime(endSec),
                row.confidence,
                detectedAt,
                row.mediaMissing ? "evidence-only" : "video-linked"));

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
        if (row == null) {
            return;
        }
        final String token = "proof:" + row.eventUid + ":" + row.endMs;
        holder.proof.setTag(token);

        // Check in-memory cache first — avoid DB hit on scroll
        String cachedUri = proofImageCache.get(row.eventUid);
        if (cachedUri != null) {
            if (!cachedUri.isEmpty()) {
                bindImageFromUri(holder.proof, cachedUri);
            }
            return;
        }

        if (isShutdown) return;
        thumbExecutor.execute(() -> {
            String bestSnapshotUri = ForensicsDatabase.getInstance(holder.itemView.getContext())
                    .aiEventSnapshotDao()
                    .getBestImageUri(row.eventUid);
            // Cache regardless of result (empty string means "no image found")
            proofImageCache.put(row.eventUid, bestSnapshotUri != null ? bestSnapshotUri : "");
            holder.itemView.post(() -> {
                Object activeToken = holder.proof.getTag();
                if (!(activeToken instanceof String) || !token.equals(activeToken)) {
                    return;
                }
                if (bestSnapshotUri != null && !bestSnapshotUri.isEmpty()) {
                    bindImageFromUri(holder.proof, bestSnapshotUri);
                }
            });
        });
    }

    private void bindImageFromUri(@NonNull ImageView imageView, @NonNull String uriString) {
        Glide.with(imageView)
                .load(Uri.parse(uriString))
                .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
                .centerCrop()
                .placeholder(R.drawable.ic_photo)
                .error(R.drawable.ic_photo)
                .into(imageView);
    }

    private void bindPersonFrameStrip(@NonNull Holder holder, AiEventWithMedia row) {
        holder.frameStrip.setVisibility(View.VISIBLE);
        holder.frameHint.setVisibility(View.VISIBLE);
        holder.framesContainer.removeAllViews();
        final String bindToken = row.eventUid + "|" + row.endMs;
        holder.framesContainer.setTag(bindToken);

        // Check in-memory cache first — avoid DB hit on scroll
        List<AiEventSnapshotEntity> cached = frameStripCache.get(row.eventUid);
        if (cached != null) {
            renderFrameStrip(holder, row, cached, bindToken);
            return;
        }

        if (isShutdown) return;
        thumbExecutor.execute(() -> {
            List<AiEventSnapshotEntity> snapshots = ForensicsDatabase.getInstance(holder.itemView.getContext())
                    .aiEventSnapshotDao()
                    .getByEventUid(row.eventUid, 20);
            List<AiEventSnapshotEntity> sampled = sampleSnapshots(snapshots, 12);
            // Cache the result (empty list means "no snapshots found")
            frameStripCache.put(row.eventUid, sampled != null ? sampled : new ArrayList<>());
            holder.itemView.post(() -> {
                Object currentToken = holder.framesContainer.getTag();
                if (!bindToken.equals(currentToken)) {
                    return;
                }
                renderFrameStrip(holder, row, sampled, bindToken);
            });
        });
    }

    /**
     * Renders frame strip thumbnails from pre-fetched (or cached) snapshot list.
     */
    private void renderFrameStrip(@NonNull Holder holder, AiEventWithMedia row,
                                  @androidx.annotation.Nullable List<AiEventSnapshotEntity> sampled,
                                  String bindToken) {
        holder.framesContainer.removeAllViews();
        if (sampled == null || sampled.isEmpty()) {
            holder.frameStrip.setVisibility(View.GONE);
            holder.frameHint.setVisibility(View.GONE);
            return;
        }
        for (AiEventSnapshotEntity snapshot : sampled) {
            addFrameThumb(holder, row, Math.max(0L, snapshot.timelineMs), snapshot.imageUri);
        }
        holder.frameHint.setText(R.string.forensics_frames_hint);
    }

    private List<AiEventSnapshotEntity> sampleSnapshots(List<AiEventSnapshotEntity> snapshots, int maxCount) {
        if (snapshots == null || snapshots.size() <= maxCount) {
            return snapshots;
        }
        List<AiEventSnapshotEntity> out = new ArrayList<>(maxCount);
        for (int i = 0; i < maxCount; i++) {
            int index = Math.min(snapshots.size() - 1, (i * (snapshots.size() - 1)) / Math.max(1, maxCount - 1));
            out.add(snapshots.get(index));
        }
        return out;
    }

    private void addFrameThumb(@NonNull Holder holder, AiEventWithMedia row, long frameMs, @androidx.annotation.Nullable String snapshotImageUri) {
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

        final String key = (snapshotImageUri != null ? snapshotImageUri : row.mediaUri) + "#p:" + frameMs;
        image.setTag(key);
        if (snapshotImageUri != null && !snapshotImageUri.isEmpty()) {
            bindImageFromUri(image, snapshotImageUri);
        }

        wrap.setOnClickListener(v -> {
            if (listener != null && !row.mediaMissing) {
                markEventSeen(v, row.eventUid);
                listener.onEventFrameClicked(row, frameMs);
            }
        });
        holder.framesContainer.addView(wrap);
    }

    private String formatTime(long seconds) {
        long mins = seconds / 60L;
        long secs = seconds % 60L;
        return String.format(Locale.US, "%d:%02d", mins, secs);
    }

    private String formatDetectedAt(long epochMs) {
        if (epochMs <= 0L) {
            return "Unknown time";
        }
        DateFormat formatter = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, Locale.getDefault());
        return formatter.format(new Date(epochMs));
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
        final TextView frameHint;
        final LinearLayout framesContainer;

        Holder(@NonNull View itemView) {
            super(itemView);
            title = itemView.findViewById(R.id.text_title);
            subtitle = itemView.findViewById(R.id.text_subtitle);
            badge = itemView.findViewById(R.id.text_badge);
            proof = itemView.findViewById(R.id.image_proof);
            frameStrip = itemView.findViewById(R.id.person_frames_strip);
            frameHint = itemView.findViewById(R.id.text_frame_hint);
            framesContainer = itemView.findViewById(R.id.person_frames_container);
            frameStrip.setOnTouchListener((v, event) -> {
                if (event.getActionMasked() == MotionEvent.ACTION_DOWN
                        || event.getActionMasked() == MotionEvent.ACTION_MOVE) {
                    v.getParent().requestDisallowInterceptTouchEvent(true);
                }
                return false;
            });
        }
    }
}
