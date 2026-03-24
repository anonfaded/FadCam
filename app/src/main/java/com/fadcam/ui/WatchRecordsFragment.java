package com.fadcam.ui;

import com.fadcam.Log;
import com.fadcam.FLog;
import android.graphics.Outline;
import android.view.ViewOutlineProvider;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.FileProvider;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.fadcam.Constants;
import com.fadcam.R;
import com.fadcam.ui.picker.OptionItem;
import com.fadcam.ui.picker.PickerBottomSheetFragment;
import com.fadcam.utils.TrashManager;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Watch-optimised recordings list.
 *
 * <p>Shows thumbnails, relative dates, and file sizes. Tapping a row opens
 * {@link WatchVideoPlayerActivity} with the selected video.</p>
 */
public class WatchRecordsFragment extends Fragment {

    private static final String TAG = "WatchRecordsFragment";
    private static final int MAX_ITEMS = 20;

    // ── UI ────────────────────────────────────────────────────────────────────

    private RecyclerView rvRecords;
    private TextView tvEmpty;

    // ── Adapter ───────────────────────────────────────────────────────────────

    private WatchRecordingAdapter adapter;
    private final List<RecordingEntry> entries = new ArrayList<>();

    // ── Async ─────────────────────────────────────────────────────────────────

    private final ExecutorService executor = Executors.newFixedThreadPool(2);
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_watch_records, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        rvRecords = view.findViewById(R.id.rv_watch_records);
        tvEmpty   = view.findViewById(R.id.tv_watch_records_empty);

        adapter = new WatchRecordingAdapter(entries, this::onItemClicked, this::onItemLongClicked, executor, mainHandler);
        rvRecords.setLayoutManager(new LinearLayoutManager(requireContext()));
        rvRecords.setAdapter(adapter);

        loadRecordings();
    }

    @Override
    public void onResume() {
        super.onResume();
        loadRecordings();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        executor.shutdownNow();
    }

    // ── Data loading ──────────────────────────────────────────────────────────

    private void loadRecordings() {
        executor.execute(() -> {
            final List<RecordingEntry> loaded = scanRecordings();
            mainHandler.post(() -> {
                entries.clear();
                entries.addAll(loaded);
                adapter.notifyDataSetChanged();

                tvEmpty.setVisibility(entries.isEmpty() ? View.VISIBLE : View.GONE);
                rvRecords.setVisibility(entries.isEmpty() ? View.GONE : View.VISIBLE);
            });
        });
    }

    @NonNull
    private List<RecordingEntry> scanRecordings() {
        final Context ctx = getContext();
        if (ctx == null) return Collections.emptyList();

        final File externalDir = ctx.getExternalFilesDir(null);
        FLog.d(TAG, "=== SCAN RECORDINGS DEBUG ===");
        FLog.d(TAG, "External dir: " + externalDir);
        
        if (externalDir == null) {
            FLog.e(TAG, "getExternalFilesDir() returned null!");
            return Collections.emptyList();
        }
        
        FLog.d(TAG, "External dir exists: " + externalDir.exists());
        FLog.d(TAG, "External dir can read: " + externalDir.canRead());
        FLog.d(TAG, "External dir can write: " + externalDir.canWrite());

        final File cameraDir = new File(externalDir,
                Constants.RECORDING_DIRECTORY + File.separator + Constants.RECORDING_SUBDIR_CAMERA);
        
        FLog.d(TAG, "Camera dir: " + cameraDir.getAbsolutePath());
        FLog.d(TAG, "Camera dir exists: " + cameraDir.exists());
        FLog.d(TAG, "Camera dir can read: " + cameraDir.canRead());
        
        if (cameraDir.exists()) {
            File[] files = cameraDir.listFiles();
            FLog.d(TAG, "Files in camera dir: " + (files != null ? files.length : "null"));
            if (files != null) {
                for (File f : files) {
                    FLog.d(TAG, "  - " + f.getName() + " (exists: " + f.exists() + ", size: " + f.length() + ")");
                }
            }
        }

        final List<File> videoFiles = new ArrayList<>();
        collectVideoFiles(cameraDir, videoFiles);
        Collections.sort(videoFiles, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        
        FLog.d(TAG, "Total video files found: " + videoFiles.size());

        final List<RecordingEntry> result = new ArrayList<>();
        final int limit = Math.min(videoFiles.size(), MAX_ITEMS);
        for (int i = 0; i < limit; i++) {
            result.add(RecordingEntry.from(videoFiles.get(i)));
        }
        return result;
    }

    private void collectVideoFiles(@NonNull File dir, @NonNull List<File> out) {
        if (!dir.exists() || !dir.isDirectory()) return;
        final File[] children = dir.listFiles();
        if (children == null) return;
        for (final File f : children) {
            if (f.isDirectory()) collectVideoFiles(f, out);
            else if (isSupportedVideo(f.getName())) out.add(f);
        }
    }

    private static boolean isSupportedVideo(@Nullable String name) {
        if (name == null) return false;
        final String lc = name.toLowerCase(Locale.US);
        return lc.endsWith(".mp4") || lc.endsWith(".mkv") || lc.endsWith(".3gp");
    }

    // ── Click ─────────────────────────────────────────────────────────────────

    private void onItemClicked(@NonNull RecordingEntry entry) {
        FLog.d(TAG, "=== VIDEO PLAYBACK DEBUG ===");
        FLog.d(TAG, "File path: " + entry.file.getAbsolutePath());
        FLog.d(TAG, "File exists: " + entry.file.exists());

        try {
            // Use FileProvider content URI — file:// EACCES on Wear OS API 30 emulator.
            final Uri contentUri = FileProvider.getUriForFile(
                    requireContext(),
                    requireContext().getPackageName() + ".provider",
                    entry.file);
            final Intent intent = new Intent(requireContext(), WatchVideoPlayerActivity.class);
            intent.putExtra(WatchVideoPlayerActivity.EXTRA_URI, contentUri.toString());
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            FLog.d(TAG, "Starting player with content URI: " + contentUri);
            startActivity(intent);
        } catch (Exception e) {
            FLog.e(TAG, "Failed to start player", e);
            Toast.makeText(requireContext(), "Cannot play: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    // ── Long-press options sheet ───────────────────────────────────────────────

    private void onItemLongClicked(@NonNull RecordingEntry entry) {
        if (!isAdded() || getActivity() == null) return;
        if (!(getActivity() instanceof FragmentActivity)) return;

        final ArrayList<OptionItem> options = new ArrayList<>();
        options.add(OptionItem.withLigature("action_share",  getString(R.string.video_menu_open_with),  "open_in_new"));
        options.add(OptionItem.withLigature("action_save",   getString(R.string.video_menu_save),        "save_alt"));
        options.add(OptionItem.withLigature("action_rename", getString(R.string.video_menu_rename),      "drive_file_rename_outline"));
        options.add(OptionItem.withLigature("action_delete", getString(R.string.video_menu_del),         "delete"));

        final String resultKey = "watch_video:" + Math.abs(entry.file.getAbsolutePath().hashCode());
        final FragmentActivity activity = (FragmentActivity) getActivity();
        activity.getSupportFragmentManager().setFragmentResultListener(resultKey, activity,
                (key, result) -> {
                    final String id = result.getString(PickerBottomSheetFragment.BUNDLE_SELECTED_ID);
                    if (id == null) return;
                    switch (id) {
                        case "action_share":  shareVideo(entry);  break;
                        case "action_save":   saveVideo(entry);   break;
                        case "action_rename": renameVideo(entry); break;
                        case "action_delete": confirmDeleteVideo(entry); break;
                    }
                });

        final PickerBottomSheetFragment sheet = PickerBottomSheetFragment.newInstanceGradient(
                entry.displayName, options, null, resultKey, null, true);
        final Bundle args = sheet.getArguments();
        if (args != null) args.putBoolean(PickerBottomSheetFragment.ARG_HIDE_CHECK, true);
        sheet.show(activity.getSupportFragmentManager(), "watch_video_actions_sheet");
    }

    private void shareVideo(@NonNull RecordingEntry entry) {
        try {
            final Uri uri = FileProvider.getUriForFile(
                    requireContext(),
                    requireContext().getPackageName() + ".fileprovider",
                    entry.file);
            final Intent intent = new Intent(Intent.ACTION_SEND);
            intent.setType("video/*");
            intent.putExtra(Intent.EXTRA_STREAM, uri);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(intent, entry.displayName));
        } catch (Exception e) {
            FLog.e(TAG, "Share failed", e);
        }
    }
    private void saveVideo(@NonNull RecordingEntry entry) {
        executor.execute(() -> {
            try {
                final File downloadsDir = new File(
                        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                        "FadCam");
                if (!downloadsDir.exists()) downloadsDir.mkdirs();
                final File destFile = new File(downloadsDir, entry.file.getName());

                // Use ParcelFileDescriptor for reading — handles app-owned external files correctly.
                try (android.os.ParcelFileDescriptor pfd = android.os.ParcelFileDescriptor.open(
                             entry.file, android.os.ParcelFileDescriptor.MODE_READ_ONLY);
                     java.io.FileInputStream fis = new java.io.FileInputStream(pfd.getFileDescriptor());
                     java.io.FileOutputStream fos = new java.io.FileOutputStream(destFile)) {
                    final byte[] buf = new byte[65536];
                    int len;
                    while ((len = fis.read(buf)) > 0) fos.write(buf, 0, len);
                    fos.flush();
                }

                // Notify gallery / Files app that a new video is available.
                MediaScannerConnection.scanFile(
                        requireContext(),
                        new String[]{destFile.getAbsolutePath()},
                        new String[]{"video/mp4"},
                        null);

                mainHandler.post(() -> {
                    if (isAdded()) Toast.makeText(requireContext(),
                            R.string.watch_video_saved_toast, Toast.LENGTH_SHORT).show();
                });
            } catch (Exception e) {
                FLog.e(TAG, "Save failed", e);
                mainHandler.post(() -> {
                    if (isAdded()) Toast.makeText(requireContext(),
                            "Save failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        });
    }
    private void renameVideo(@NonNull RecordingEntry entry) {
        if (!isAdded()) return;
        final InputActionBottomSheetFragment sheet = InputActionBottomSheetFragment.newInput(
                getString(R.string.video_menu_rename),
                entry.displayName,
                entry.displayName,
                getString(R.string.video_menu_rename),
                null,
                0);
        sheet.setCallbacks(new InputActionBottomSheetFragment.Callbacks() {
            @Override public void onImportConfirmed(org.json.JSONObject json) { /* unused */ }
            @Override public void onResetConfirmed() { /* unused */ }
            @Override public void onInputConfirmed(String input) {
                if (input == null || input.trim().isEmpty()) return;
                final String sanitized = input.trim().replaceAll("[\\\\/:*?\"<>|]", "_");
                final String fn = entry.file.getName();
                final int dot = fn.lastIndexOf('.');
                final String ext = dot >= 0 ? fn.substring(dot) : "";
                final File newFile = new File(entry.file.getParentFile(), sanitized + ext);
                executor.execute(() -> {
                    final boolean ok = entry.file.renameTo(newFile);
                    mainHandler.post(() -> {
                        if (!isAdded()) return;
                        if (!ok) Toast.makeText(requireContext(),
                                R.string.toast_rename_failed, Toast.LENGTH_SHORT).show();
                        loadRecordings();
                    });
                });
            }
        });
        sheet.show(getParentFragmentManager(), "watch_rename_sheet");
    }

    private void confirmDeleteVideo(@NonNull RecordingEntry entry) {
        new AlertDialog.Builder(requireContext(), R.style.WatchDeleteConfirmDialog)
                .setTitle(R.string.watch_delete_confirm_title)
                .setMessage(getString(R.string.watch_delete_confirm_msg, entry.displayName))
                .setPositiveButton(R.string.watch_delete_confirm_action, (d, w) -> deleteVideo(entry))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void deleteVideo(@NonNull RecordingEntry entry) {
        FLog.d(TAG, "=== DELETE DEBUG ===");
        FLog.d(TAG, "Deleting video: " + entry.file.getAbsolutePath());

        executor.execute(() -> {
            // Delegate to TrashManager so metadata is written in the same format
            // that WatchTrashFragment reads via TrashManager.loadTrashMetadata().
            final Uri fileUri = Uri.fromFile(entry.file);
            final boolean success = TrashManager.moveToTrash(
                    requireContext(), fileUri, entry.displayName, false);
            FLog.d(TAG, "moveToTrash result: " + success);

            mainHandler.post(() -> {
                if (isAdded()) {
                    loadRecordings();
                    Toast.makeText(requireContext(),
                            success ? R.string.video_moved_to_trash : R.string.universal_delete_failed,
                            Toast.LENGTH_SHORT).show();
                }
            });
        });
    }

    // ── Data model ─────────────────────────────────────────────────────────────

    static final class RecordingEntry {
        @NonNull final File   file;
        @NonNull final String displayName;
        @NonNull final String sizeLabel;
        @NonNull final String relativeDate;
        final long            lastModified;

        private RecordingEntry(@NonNull File file,
                               @NonNull String displayName,
                               @NonNull String sizeLabel,
                               @NonNull String relativeDate,
                               long lastModified) {
            this.file         = file;
            this.displayName  = displayName;
            this.sizeLabel    = sizeLabel;
            this.relativeDate = relativeDate;
            this.lastModified = lastModified;
        }

        @NonNull
        static RecordingEntry from(@NonNull File f) {
            final String name   = f.getName();
            final int dotPos = name.lastIndexOf('.');
            final String display = dotPos > 0 ? name.substring(0, dotPos) : name;
            return new RecordingEntry(
                    f,
                    display,
                    formatSize(f.length()),
                    formatRelativeDate(f.lastModified()),
                    f.lastModified());
        }

        @NonNull
        private static String formatSize(long bytes) {
            if (bytes < 1024L) return bytes + " B";
            if (bytes < 1024L * 1024L) return String.format(Locale.US, "%.1f KB", bytes / 1024.0);
            if (bytes < 1024L * 1024L * 1024L)
                return String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0));
            return String.format(Locale.US, "%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0));
        }

        /**
         * Returns a relative-time string like "just now", "5m ago", "2h ago", "3d ago", etc.
         */
        @NonNull
        static String formatRelativeDate(long timestampMs) {
            final long diff = System.currentTimeMillis() - timestampMs;
            if (diff < 0)             return "just now";
            final long secs  = diff / 1_000L;
            final long mins  = secs  / 60L;
            final long hours = mins  / 60L;
            final long days  = hours / 24L;
            final long weeks = days  / 7L;
            final long months = days / 30L;

            if (secs  < 60)   return "just now";
            if (mins  < 60)   return mins  + "m ago";
            if (hours < 24)   return hours + "h ago";
            if (days  < 7)    return days  + "d ago";
            if (weeks < 5)    return weeks + "w ago";
            if (months < 12)  return months + "mo ago";
            return (months / 12) + "y ago";
        }
    }

    // ── Adapter ────────────────────────────────────────────────────────────────

    interface OnItemClickListener {
        void onClick(@NonNull RecordingEntry entry);
    }

    interface OnItemLongClickListener {
        void onLongClick(@NonNull RecordingEntry entry);
    }

    static final class WatchRecordingAdapter
            extends RecyclerView.Adapter<WatchRecordingAdapter.VH> {

        @NonNull private final List<RecordingEntry> items;
        @NonNull private final OnItemClickListener  listener;
        @Nullable private final OnItemLongClickListener longClickListener;
        @NonNull private final ExecutorService      executor;
        @NonNull private final Handler              mainHandler;

        WatchRecordingAdapter(@NonNull List<RecordingEntry> items,
                              @NonNull OnItemClickListener listener,
                              @Nullable OnItemLongClickListener longClickListener,
                              @NonNull ExecutorService executor,
                              @NonNull Handler mainHandler) {
            this.items              = items;
            this.listener           = listener;
            this.longClickListener  = longClickListener;
            this.executor           = executor;
            this.mainHandler        = mainHandler;
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            final View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_watch_recording, parent, false);
            final VH holder = new VH(v);
            // Hardware-clip thumbnail to rounded rect — covers background placeholder + image bitmap.
            holder.ivThumb.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    final float r = 12f * view.getResources().getDisplayMetrics().density;
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), r);
                }
            });
            holder.ivThumb.setClipToOutline(true);
            return holder;
        }

        @Override
        public void onBindViewHolder(@NonNull VH holder, int position) {
            final RecordingEntry entry = items.get(position);
            holder.tvName.setText(entry.displayName);
            holder.tvDate.setText(entry.relativeDate);
            holder.tvInfo.setText(entry.sizeLabel);
            holder.ivThumb.setImageBitmap(null);
            holder.ivThumb.setBackgroundColor(0xFF1A1A1A);
            holder.itemView.setOnClickListener(v -> listener.onClick(entry));
            holder.itemView.setOnLongClickListener(v -> {
                if (longClickListener != null) { longClickListener.onLongClick(entry); return true; }
                return false;
            });
            // 3-dot menu button opens the same options sheet as long-press
            holder.btnMenu.setOnClickListener(v -> {
                if (longClickListener != null) { longClickListener.onLongClick(entry); }
            });

            // Load thumbnail on background thread — tag guards against recycled holder mismatch
            final File fileCopy = entry.file;
            holder.ivThumb.setTag(fileCopy.getAbsolutePath());
            executor.execute(() -> {
                Bitmap thumb = loadThumbnail(fileCopy);
                mainHandler.post(() -> {
                    if (thumb == null) return;
                    if (!fileCopy.getAbsolutePath().equals(holder.ivThumb.getTag())) return;
                    holder.ivThumb.setImageBitmap(thumb);
                });
            });
        }

        @Override
        public int getItemCount() { return items.size(); }

        @Nullable
        private Bitmap loadThumbnail(@NonNull File file) {
            android.media.MediaMetadataRetriever retriever = null;
            android.os.ParcelFileDescriptor pfd = null;
            try {
                FLog.d(TAG, "Loading thumbnail for: " + file.getAbsolutePath());
                // Use ParcelFileDescriptor so EACCES on Wear OS emulator (canRead=false) is bypassed.
                pfd = android.os.ParcelFileDescriptor.open(
                        file, android.os.ParcelFileDescriptor.MODE_READ_ONLY);
                retriever = new android.media.MediaMetadataRetriever();
                retriever.setDataSource(pfd.getFileDescriptor());
                Bitmap frame = retriever.getFrameAtTime(1_000_000L,
                        android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
                if (frame != null) {
                    FLog.d(TAG, "Got frame: " + frame.getWidth() + "x" + frame.getHeight());
                    return Bitmap.createScaledBitmap(frame, 64, 64, true);
                }
                FLog.w(TAG, "No frame extracted from video");
                return null;
            } catch (Exception e) {
                FLog.e(TAG, "Thumbnail load failed for " + file.getName() + ": " + e.getMessage(), e);
                return null;
            } finally {
                if (retriever != null) {
                    try { retriever.release(); } catch (Exception ignored) {}
                }
                if (pfd != null) {
                    try { pfd.close(); } catch (Exception ignored) {}
                }
            }
        }

        static final class VH extends RecyclerView.ViewHolder {
            final ImageView ivThumb;
            final TextView  tvName;
            final TextView  tvDate;
            final TextView  tvInfo;
            final ImageView btnMenu;

            VH(@NonNull View itemView) {
                super(itemView);
                ivThumb = itemView.findViewById(R.id.iv_watch_rec_thumb);
                tvName  = itemView.findViewById(R.id.tv_watch_rec_name);
                tvDate  = itemView.findViewById(R.id.tv_watch_rec_date);
                tvInfo  = itemView.findViewById(R.id.tv_watch_rec_info);
                btnMenu = itemView.findViewById(R.id.btn_watch_rec_menu);
            }
        }
    }
}
