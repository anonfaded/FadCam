package com.fadcam.ui;

import android.content.Context;
import android.media.MediaScannerConnection;
import android.os.Build;
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
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import android.media.MediaMetadataRetriever;
import android.graphics.Bitmap;
import android.widget.Toast;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;


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



    // Method to save the video to the gallery
    private void saveToGallery(Context context, File video) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveToGalleryAndroid10Plus(context, video);
        } else {
            saveToGalleryLegacy(context, video);
        }
    }

    private void saveToGalleryAndroid10Plus(Context context, File video) {
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, video.getName());
        values.put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4");
        values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + File.separator + "FadCam");

        ContentResolver resolver = context.getContentResolver();
        Uri collection;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
        } else {
            collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
        }
        Uri itemUri = resolver.insert(collection, values);

        if (itemUri != null) {
            try (InputStream in = new FileInputStream(video);
                 OutputStream out = resolver.openOutputStream(itemUri)) {
                byte[] buf = new byte[8192];
                int len;
                while ((len = in.read(buf)) > 0) {
                    out.write(buf, 0, len);
                }
                Toast.makeText(context, "Video saved to FadCam folder in Downloads", Toast.LENGTH_SHORT).show();
            } catch (IOException e) {
                Toast.makeText(context, "Failed to save video", Toast.LENGTH_SHORT).show();
                e.printStackTrace();
            }
        } else {
            Toast.makeText(context, "Failed to save video", Toast.LENGTH_SHORT).show();
        }
    }

    private void saveToGalleryLegacy(Context context, File video) {
        File destDir = new File(Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS), "FadCam");
        if (!destDir.exists()) {
            destDir.mkdirs();
        }

        File destFile = new File(destDir, video.getName());
        try (FileInputStream in = new FileInputStream(video);
             FileOutputStream out = new FileOutputStream(destFile)) {
            byte[] buf = new byte[8192];
            int len;
            while ((len = in.read(buf)) > 0) {
                out.write(buf, 0, len);
            }
            Toast.makeText(context, "Video saved to FadCam folder in Downloads", Toast.LENGTH_SHORT).show();

            // Make the file visible in the gallery
            MediaScannerConnection.scanFile(context,
                    new String[]{destFile.getAbsolutePath()},
                    null,
                    null);
        } catch (IOException e) {
            Toast.makeText(context, "Failed to save video", Toast.LENGTH_SHORT).show();
            e.printStackTrace();
        }
    }



    private void showPopupMenu(View v, File video) {
        PopupMenu popup = new PopupMenu(v.getContext(), v);
        popup.getMenuInflater().inflate(R.menu.video_item_menu, popup.getMenu());

        // Set icons for menu items
        popup.getMenu().findItem(R.id.action_delete).setIcon(R.drawable.ic_delete);
        popup.getMenu().findItem(R.id.action_save_to_gallery).setIcon(R.drawable.ic_save);

        popup.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == R.id.action_delete) {
                confirmDelete(v.getContext(), video);
                return true;
            }
            if (item.getItemId() == R.id.action_save_to_gallery) {
                saveToGallery(v.getContext(), video);
                return true;
            }
            return false;
        });

        // Force icons to show
        try {
            Field field = popup.getClass().getDeclaredField("mPopup");
            field.setAccessible(true);
            Object menuPopupHelper = field.get(popup);
            Class<?> classPopupHelper = Class.forName(menuPopupHelper.getClass().getName());
            Method setForceIcons = classPopupHelper.getMethod("setForceShowIcon", boolean.class);
            setForceIcons.invoke(menuPopupHelper, true);
        } catch (Exception e) {
            e.printStackTrace();
        }

        popup.show();
    }


    private void confirmDelete(Context context, File video) {
        new MaterialAlertDialogBuilder(context)
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