package com.fadcam.ui;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.widget.PopupMenu;
import androidx.recyclerview.widget.DiffUtil;
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
import java.util.ArrayList;
import java.util.List;

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
        setThumbnail(holder, video);
        holder.textViewRecord.setText(video.getName());

        holder.itemView.setOnClickListener(v -> clickListener.onVideoClick(video));
        holder.itemView.setOnLongClickListener(v -> {
            boolean isSelected = !selectedVideos.contains(video);
            toggleSelection(holder, video, isSelected);
            longClickListener.onVideoLongClick(video, isSelected);
            return true;
        });

        holder.menuButton.setOnClickListener(v -> showPopupMenu(v, video));
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

    private void showPopupMenu(View v, File video) {
        int position = records.indexOf(video); // Find position from video
        if (position == -1) {
            Toast.makeText(context, R.string.toast_video_not_found, Toast.LENGTH_SHORT).show();
            return;
        }

        PopupMenu popup = new PopupMenu(v.getContext(), v);
        popup.getMenuInflater().inflate(R.menu.video_item_menu, popup.getMenu());

        popup.getMenu().findItem(R.id.action_delete).setIcon(R.drawable.ic_delete);
        popup.getMenu().findItem(R.id.action_save_to_gallery).setIcon(R.drawable.ic_save);
        popup.getMenu().findItem(R.id.action_rename).setIcon(R.drawable.ic_rename);

        popup.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == R.id.action_delete) {
                confirmDelete(v.getContext(), records.get(position));
                return true;
            }
            if (item.getItemId() == R.id.action_save_to_gallery) {
                saveToGallery(v.getContext(), records.get(position));
                return true;
            }
            if (item.getItemId() == R.id.action_rename) {
                showRenameDialog(position);
                return true;
            }
            return false;
        });

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
        if (videoFiles == null || position < 0 || position >= videoFiles.size()) {
            Toast.makeText(context, R.string.toast_rename_bad_video, Toast.LENGTH_SHORT).show();
            return;
        }

        // Replace spaces with underscores
        String formattedName = newName.trim().replace(" ", "_");

        File oldFile = videoFiles.get(position);
        File newFile = new File(oldFile.getParent(), formattedName + "." + Constants.RECORDING_FILE_EXTENSION);

        if (oldFile.renameTo(newFile)) {
            // Update the list and notify the adapter
            videoFiles.set(position, newFile);
            records.set(position, newFile); // Also update the records list if necessary
            notifyDataSetChanged();
            Toast.makeText(context, R.string.toast_rename_success, Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(context, R.string.toast_rename_failed, Toast.LENGTH_SHORT).show();
        }
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

    public void updateRecords(List<File> newRecords) {
        DiffUtil.Callback diffCallback = new DiffUtil.Callback() {
            @Override
            public int getOldListSize() {
                return records.size();
            }

            @Override
            public int getNewListSize() {
                return newRecords.size();
            }

            @Override
            public boolean areItemsTheSame(int oldItemPosition, int newItemPosition) {
                return records.get(oldItemPosition).equals(newRecords.get(newItemPosition));
            }

            @Override
            public boolean areContentsTheSame(int oldItemPosition, int newItemPosition) {
                return records.get(oldItemPosition).equals(newRecords.get(newItemPosition));
            }
        };

        DiffUtil.DiffResult diffResult = DiffUtil.calculateDiff(diffCallback);
        this.records = newRecords;
        this.videoFiles = new ArrayList<>(newRecords); // Update videoFiles to match new records
        diffResult.dispatchUpdatesTo(this);
    }

    public void updateThumbnail(String videoFilePath) {
        notifyDataSetChanged();
    }
}
