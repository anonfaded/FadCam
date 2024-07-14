package com.fadcam.ui;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.PopupMenu;
import androidx.recyclerview.widget.RecyclerView;
import com.fadcam.R;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import android.media.MediaMetadataRetriever;
import android.graphics.Bitmap;

public class RecordsAdapter extends RecyclerView.Adapter<RecordsAdapter.RecordViewHolder> {

    private List<File> records;
    private OnVideoClickListener clickListener;
    private OnVideoLongClickListener longClickListener;
    private List<File> selectedVideos = new ArrayList<>();

    public interface OnVideoClickListener {
        void onVideoClick(File video);
    }

    public interface OnVideoLongClickListener {
        void onVideoLongClick(File video, boolean isSelected);
    }

    public RecordsAdapter(List<File> records, OnVideoClickListener clickListener, OnVideoLongClickListener longClickListener) {
        this.records = records;
        this.clickListener = clickListener;
        this.longClickListener = longClickListener;
    }

    @NonNull
    @Override
    public RecordViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_record, parent, false);
        return new RecordViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull RecordViewHolder holder, int position) {
        File video = records.get(position);
        holder.textViewRecord.setText(video.getName());

        holder.itemView.setOnClickListener(v -> clickListener.onVideoClick(video));

        holder.itemView.setOnLongClickListener(v -> {
            boolean isSelected = !selectedVideos.contains(video);
            toggleSelection(holder, video, isSelected);
            longClickListener.onVideoLongClick(video, isSelected);
            return true;
        });

        holder.menuButton.setOnClickListener(v -> showPopupMenu(v, video));

        // Set thumbnail
        setThumbnail(holder, video);

        // Update selection state
        updateSelectionState(holder, selectedVideos.contains(video));
    }

    @Override
    public int getItemCount() {
        return records.size();
    }

    private void setThumbnail(RecordViewHolder holder, File video) {
        if (video.exists()) {
            MediaMetadataRetriever retriever = new MediaMetadataRetriever();
            try {
                retriever.setDataSource(video.getAbsolutePath());
                Bitmap thumbnail = retriever.getFrameAtTime(1000000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
                holder.imageViewThumbnail.setImageBitmap(thumbnail);
            } catch (IllegalArgumentException e) {
                e.printStackTrace();
                // Set a placeholder image if thumbnail extraction fails
                holder.imageViewThumbnail.setImageResource(R.drawable.ic_video_placeholder);
            } finally {
                try {
                    retriever.release();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        } else {
            // Set a placeholder image if the file doesn't exist
            holder.imageViewThumbnail.setImageResource(R.drawable.ic_video_placeholder);
        }
    }

    private void toggleSelection(RecordViewHolder holder, File video, boolean isSelected) {
        if (isSelected) {
            selectedVideos.add(video);
        } else {
            selectedVideos.remove(video);
        }
        updateSelectionState(holder, isSelected);
    }

    private void updateSelectionState(RecordViewHolder holder, boolean isSelected) {
        holder.itemView.setActivated(isSelected);
        holder.checkIcon.setVisibility(isSelected ? View.VISIBLE : View.GONE);
    }

    private void showPopupMenu(View v, File video) {
        PopupMenu popup = new PopupMenu(v.getContext(), v);
        popup.getMenuInflater().inflate(R.menu.video_item_menu, popup.getMenu());
        popup.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == R.id.action_delete) {
                confirmDelete(v.getContext(), video);
                return true;
            }
            return false;
        });
        popup.show();
    }

    private void confirmDelete(Context context, File video) {
        new AlertDialog.Builder(context)
                .setTitle("Delete Forever? \uD83D\uDDD1\uFE0F")
                .setMessage("Are you sure you want to sweep this video away? \uD83E\uDDFC")
                .setPositiveButton("Sweep It! \uD83E\uDDF9", (dialog, which) -> {
                    if (video.delete()) {
                        int position = records.indexOf(video);
                        records.remove(video);
                        notifyItemRemoved(position);
                    }
                })
                .setNegativeButton("Nope! \uD83D\uDE05", null)
                .show();
    }

    public void updateRecords(List<File> newRecords) {
        this.records = newRecords;
        notifyDataSetChanged();
    }

    static class RecordViewHolder extends RecyclerView.ViewHolder {
        ImageView imageViewThumbnail;
        TextView textViewRecord;
        ImageView checkIcon;
        ImageView menuButton;

        RecordViewHolder(View itemView) {
            super(itemView);
            imageViewThumbnail = itemView.findViewById(R.id.image_view_thumbnail);
            textViewRecord = itemView.findViewById(R.id.text_view_record);
            checkIcon = itemView.findViewById(R.id.check_icon);
            menuButton = itemView.findViewById(R.id.menu_button);
        }
    }
}