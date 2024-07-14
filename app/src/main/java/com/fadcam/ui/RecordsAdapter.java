package com.fadcam.ui;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import com.fadcam.R;
import java.io.File;
import java.io.IOException;
import java.util.List;
import android.media.MediaMetadataRetriever;
import android.graphics.Bitmap;

public class RecordsAdapter extends RecyclerView.Adapter<RecordsAdapter.RecordViewHolder> {

    private List<File> records;
    private OnVideoClickListener listener;

    public interface OnVideoClickListener {
        void onVideoClick(File video);
    }

    public RecordsAdapter(List<File> records, OnVideoClickListener listener) {
        this.records = records;
        this.listener = listener;
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
        holder.itemView.setOnClickListener(v -> listener.onVideoClick(video));

        // Set thumbnail
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        retriever.setDataSource(video.getAbsolutePath());
        Bitmap thumbnail = retriever.getFrameAtTime(1000000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
        holder.imageViewThumbnail.setImageBitmap(thumbnail);
        try {
            retriever.release();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public int getItemCount() {
        return records.size();
    }

    static class RecordViewHolder extends RecyclerView.ViewHolder {
        ImageView imageViewThumbnail;
        TextView textViewRecord;

        RecordViewHolder(View itemView) {
            super(itemView);
            imageViewThumbnail = itemView.findViewById(R.id.image_view_thumbnail);
            textViewRecord = itemView.findViewById(R.id.text_view_record);
        }
    }
}