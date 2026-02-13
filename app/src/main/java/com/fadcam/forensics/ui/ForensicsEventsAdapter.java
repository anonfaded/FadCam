package com.fadcam.forensics.ui;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.fadcam.R;
import com.fadcam.forensics.data.local.model.AiEventWithMedia;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class ForensicsEventsAdapter extends RecyclerView.Adapter<ForensicsEventsAdapter.Holder> {

    public interface Listener {
        void onEventClicked(AiEventWithMedia row);
    }

    private final Listener listener;
    private final List<AiEventWithMedia> rows = new ArrayList<>();

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
                "%s • %ds-%ds • conf %.2f",
                mediaName,
                startSec,
                endSec,
                row.confidence));

        String badgeText = "EXACT";
        if (row.linkStatus != null && !row.linkStatus.isEmpty()) {
            badgeText = row.linkStatus;
        }
        holder.badge.setText(badgeText);

        holder.itemView.setOnClickListener(v -> {
            if (listener != null) {
                listener.onEventClicked(row);
            }
        });
    }

    @Override
    public int getItemCount() {
        return rows.size();
    }

    static class Holder extends RecyclerView.ViewHolder {
        final TextView title;
        final TextView subtitle;
        final TextView badge;

        Holder(@NonNull View itemView) {
            super(itemView);
            title = itemView.findViewById(R.id.text_title);
            subtitle = itemView.findViewById(R.id.text_subtitle);
            badge = itemView.findViewById(R.id.text_badge);
        }
    }
}
