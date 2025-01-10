package com.fadcam.ui;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.widget.PopupMenu;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.fadcam.Constants;
import com.fadcam.R;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class RecordsAdapter extends RecyclerView.Adapter<RecordsAdapter.RecordViewHolder> {

    private final Context context;
    private List<File> records;
    private final OnVideoClickListener clickListener;
    private final OnVideoLongClickListener longClickListener;
    private final List<File> selectedVideos = new ArrayList<>();
    private List<File> videoFiles;

    public RecordsAdapter(Context context, List<File> records, OnVideoClickListener clickListener, OnVideoLongClickListener longClickListener) {
        this.context = context;
        this.records = records;
        this.clickListener = clickListener;
        this.longClickListener = longClickListener;
        this.videoFiles = new ArrayList<>(records); // Initialize videoFiles with a copy of records
    }

    public interface OnVideoClickListener {
        void onVideoClick(File video);
    }

    public interface OnVideoLongClickListener {
        void onVideoLongClick(File video, boolean isSelected);
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
        
        // Set serial number based on current position
        holder.textViewSerialNumber.setText(String.valueOf(position + 1));
        
        // Set video name
        holder.textViewRecord.setText(video.getName());
        
        // Set file size
        long fileSize = video.length();
        holder.textViewFileSize.setText(formatFileSize(fileSize));
        
        // Set video duration
        long duration = getVideoDuration(video);
        holder.textViewFileTime.setText(formatVideoDuration(duration));
        
        // Set thumbnail
        setThumbnail(holder, video);
        
        holder.itemView.setOnClickListener(v -> clickListener.onVideoClick(video));
        holder.itemView.setOnLongClickListener(v -> {
            boolean isSelected = !selectedVideos.contains(video);
            toggleSelection(holder, video, isSelected);
            longClickListener.onVideoLongClick(video, isSelected);
            return true;
        });

        setupPopupMenu(holder, video);
        updateSelectionState(holder, selectedVideos.contains(video));
    }

    @Override
    public int getItemCount() {
        return records.size();
    }

    private void setThumbnail(RecordViewHolder holder, File video) {
        Glide.with(context)
                .load(video.getAbsolutePath())
                .placeholder(R.drawable.ic_video_placeholder)
                .into(holder.imageViewThumbnail);
    }

    private void toggleSelection(RecordViewHolder holder, File video, boolean isSelected) {
        if (isSelected) {
            selectedVideos.add(video);
        } else {
            selectedVideos.remove(video);
        }
        int position = records.indexOf(video);
        notifyItemChanged(position);
    }

    private void updateSelectionState(RecordViewHolder holder, boolean isSelected) {
        holder.itemView.setActivated(isSelected);
        holder.checkIcon.setVisibility(isSelected ? View.VISIBLE : View.GONE);
    }

    private void setupPopupMenu(RecordViewHolder holder, File video) {
        holder.menuButton.setOnClickListener(v -> {
            PopupMenu popupMenu = new PopupMenu(context, holder.menuButton);
            popupMenu.getMenuInflater().inflate(R.menu.menu_video_options, popupMenu.getMenu());
            
            // Force show icons
            try {
                Field field = PopupMenu.class.getDeclaredField("mPopup");
                field.setAccessible(true);
                Object menuPopupHelper = field.get(popupMenu);
                Class<?> classPopupHelper = Class.forName(menuPopupHelper.getClass().getName());
                Method setForceIcons = classPopupHelper.getMethod("setForceShowIcon", boolean.class);
                setForceIcons.invoke(menuPopupHelper, true);
            } catch (Exception e) {
                Log.e("PopupMenu", "Error forcing icon display", e);
            }
            
            popupMenu.setOnMenuItemClickListener(item -> {
                int itemId = item.getItemId();
                
                if (itemId == R.id.action_rename) {
                    showRenameDialog(records.indexOf(video));
                    return true;
                } else if (itemId == R.id.action_delete) {
                    confirmDelete(context, video);
                    return true;
                } else if (itemId == R.id.action_save) {
                    saveToGallery(context, video);
                    return true;
                } else if (itemId == R.id.action_info) {
                    showVideoInfoDialog(video);
                    return true;
                }
                
                return false;
            });
            
            popupMenu.show();
        });
    }

    private void showVideoInfoDialog(File video) {
        // Create dialog
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(context);
        View dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_video_info, null);
        
        // Find views
        TextView tvFileName = dialogView.findViewById(R.id.tv_file_name);
        TextView tvFileSize = dialogView.findViewById(R.id.tv_file_size);
        TextView tvFilePath = dialogView.findViewById(R.id.tv_file_path);
        TextView tvLastModified = dialogView.findViewById(R.id.tv_last_modified);
        TextView tvDuration = dialogView.findViewById(R.id.tv_duration);
        TextView tvResolution = dialogView.findViewById(R.id.tv_resolution);

        ImageView ivCopyToClipboard = dialogView.findViewById(R.id.iv_copy_to_clipboard);
        
        // Gather video information
        String fileName = video.getName();
        long fileSize = video.length();
        String filePath = video.getAbsolutePath();
        long lastModified = video.lastModified();
        long duration = getVideoDuration(video);
        
        // Format information
        String formattedFileSize = formatFileSize(fileSize);
        String formattedLastModified = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                .format(new Date(lastModified));
        String formattedDuration = formatVideoDuration(duration);
        
        // Get video metadata
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(video.getAbsolutePath());
            
            // Resolution
            String width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH);
            String height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT);
            String resolution = (width != null && height != null) ? width + " x " + height : "N/A";
            
            // Prepare full video info text for clipboard
            String videoInfo = "File Name: " + fileName + "\n" +
                    "File Size: " + formattedFileSize + "\n" +
                    "File Path: " + filePath + "\n" +
                    "Last Modified: " + formattedLastModified + "\n" +
                    "Duration: " + formattedDuration;
            
            // Set views
            tvFileName.setText(fileName);
            tvFileSize.setText(formattedFileSize);
            tvFilePath.setText(filePath);
            tvLastModified.setText(formattedLastModified);
            tvDuration.setText(formattedDuration);
            tvResolution.setText(resolution);

            
            // Set up copy to clipboard
            ivCopyToClipboard.setOnClickListener(v -> {
                android.content.ClipboardManager clipboard = (android.content.ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
                android.content.ClipData clip = android.content.ClipData.newPlainText("Video Info", videoInfo);
                clipboard.setPrimaryClip(clip);
                
                // Show toast notification
                Toast.makeText(context, "Video info copied to clipboard", Toast.LENGTH_SHORT).show();
            });
            
        } catch (Exception e) {
            Log.e("VideoInfoDialog", "Error retrieving video metadata", e);
            
            // Set default/fallback values
            tvFileName.setText(fileName);
            tvFileSize.setText(formattedFileSize);
            tvFilePath.setText(filePath);
            tvLastModified.setText(formattedLastModified);
            tvDuration.setText(formattedDuration);
            tvResolution.setText("N/A");
          
        } finally {
            try {
                retriever.release();
            } catch (IOException e) {
                Log.e("VideoInfoDialog", "Error releasing MediaMetadataRetriever", e);
            }
        }
        
        // Build and show dialog
        builder.setTitle("Video Information")
               .setView(dialogView)
               .setPositiveButton("Close", (dialog, which) -> dialog.dismiss())
               .show();
    }

    private void confirmDelete(Context context, File video) {
        new MaterialAlertDialogBuilder(context)
                .setTitle(context.getString(R.string.dialog_del_title))
                .setMessage(context.getString(R.string.dialog_del_notice))
                .setPositiveButton(context.getString(R.string.dialog_del_confirm), (dialog, which) -> {
                    if (video.delete()) {
                        int position = records.indexOf(video);
                        records.remove(video);
                        notifyItemRemoved(position);
                    }
                })
                .setNegativeButton(context.getString(R.string.universal_cancel), null)
                .show();
    }

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
        values.put(MediaStore.MediaColumns.MIME_TYPE, "video/" + Constants.RECORDING_FILE_EXTENSION);
        values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + File.separator + Constants.RECORDING_DIRECTORY);

        ContentResolver resolver = context.getContentResolver();
        Uri collection = null;
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
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
                Toast.makeText(context, R.string.toast_video_saved, Toast.LENGTH_SHORT).show();
            } catch (IOException e) {
                Toast.makeText(context, R.string.toast_video_save_fail, Toast.LENGTH_SHORT).show();
                e.printStackTrace();
            }
        } else {
            Toast.makeText(context, R.string.toast_video_save_fail, Toast.LENGTH_SHORT).show();
        }
    }

    private void saveToGalleryLegacy(Context context, File video) {
        File destDir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), Constants.RECORDING_DIRECTORY);
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
            Toast.makeText(context, R.string.toast_video_saved, Toast.LENGTH_SHORT).show();
            MediaScannerConnection.scanFile(context, new String[]{destFile.getAbsolutePath()}, null, null);
        } catch (IOException e) {
            Toast.makeText(context, R.string.toast_video_save_fail, Toast.LENGTH_SHORT).show();
            e.printStackTrace();
        }
    }

    // Helper method to format file size
    private String formatFileSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        int exp = (int) (Math.log(bytes) / Math.log(1024));
        String pre = "KMGTPE".charAt(exp - 1) + "B";
        return String.format("%.1f %s", bytes / Math.pow(1024, exp), pre);
    }

    // Helper method to format video duration
    private String formatVideoDuration(long durationMs) {
        long totalSeconds = durationMs / 1000;
        long minutes = totalSeconds / 60;
        long seconds = totalSeconds % 60;
        
        if (minutes > 0) {
            return String.format(Locale.getDefault(), "%d min", minutes);
        } else {
            return String.format(Locale.getDefault(), "%d sec", seconds);
        }
    }

    // Helper method to get video duration
    private long getVideoDuration(File videoFile) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(videoFile.getAbsolutePath());
            String duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            return duration != null ? Long.parseLong(duration) : 0;
        } catch (Exception e) {
            Log.e("RecordsAdapter", "Error getting video duration", e);
            return 0;
        } finally {
            try {
                retriever.release();
            } catch (IOException e) {
                Log.e("RecordsAdapter", "Error releasing MediaMetadataRetriever", e);
            }
        }
    }

    private void showRenameDialog(final int position) {
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(context);
        builder.setTitle(R.string.rename_video_title);

        // Inflate and set up the input view
        View dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_rename, null);
        final TextInputEditText input = dialogView.findViewById(R.id.edit_text_name);
        builder.setView(dialogView);

        builder.setPositiveButton("OK", (dialog, which) -> {
            String newName = input.getText().toString();
            if (!newName.isEmpty()) {
                renameVideo(position, newName);
            } else {
                Toast.makeText(context, R.string.toast_rename_name_empty, Toast.LENGTH_SHORT).show();
            }
        });
        builder.setNegativeButton(R.string.universal_cancel, (dialog, which) -> dialog.cancel());

        builder.show();
    }

    private void renameVideo(int position, String newName) {
        // Replace spaces with underscores
        String formattedName = newName.trim().replace(" ", "_");

        File oldFile = videoFiles.get(position);
        File parentDir = oldFile.getParentFile();
        String fileExtension = Constants.RECORDING_FILE_EXTENSION;

        // Check for existing files with similar names
        File newFile = new File(parentDir, formattedName + "." + fileExtension);
        int copyNumber = 1;

        // Find a unique filename
        while (newFile.exists()) {
            newFile = new File(parentDir, formattedName + "_" + copyNumber + "." + fileExtension);
            copyNumber++;
        }

        if (oldFile.renameTo(newFile)) {
            // Update the list and notify the adapter
            videoFiles.set(position, newFile);
            records.set(position, newFile); // Also update the records list if necessary
            notifyDataSetChanged();

            // Show a toast with the new filename if a copy number was added
            String toastMessage = copyNumber > 1 ?
                context.getString(R.string.toast_rename_with_copy, newFile.getName()) :
                context.getString(R.string.toast_rename_success);

            Toast.makeText(context, toastMessage, Toast.LENGTH_LONG).show();
        } else {
            Toast.makeText(context, R.string.toast_rename_failed, Toast.LENGTH_LONG).show();
        }
    }

    public void updateRecords(List<File> newRecords) {
        if (newRecords != null) {
            records.clear();
            records.addAll(newRecords);
            notifyDataSetChanged();
        }
    }

    public void updateThumbnail(String videoFilePath) {
        notifyDataSetChanged();
    }

    static class RecordViewHolder extends RecyclerView.ViewHolder {
        ImageView imageViewThumbnail;
        TextView textViewRecord;
        TextView textViewFileSize;
        TextView textViewFileTime;
        TextView textViewSerialNumber;  // New serial number TextView
        ImageView checkIcon;
        ImageView menuButton;

        RecordViewHolder(View itemView) {
            super(itemView);
            imageViewThumbnail = itemView.findViewById(R.id.image_view_thumbnail);
            textViewRecord = itemView.findViewById(R.id.text_view_record);
            textViewFileSize = itemView.findViewById(R.id.text_view_file_size);
            textViewFileTime = itemView.findViewById(R.id.text_view_file_time);
            textViewSerialNumber = itemView.findViewById(R.id.text_view_serial_number);  // Initialize serial number TextView
            checkIcon = itemView.findViewById(R.id.check_icon);
            menuButton = itemView.findViewById(R.id.menu_button);
        }
    }
}
