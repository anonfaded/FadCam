package com.fadcam.ui;

import android.graphics.Outline;
import android.view.ViewOutlineProvider;
import android.app.AlertDialog;
import android.graphics.Bitmap;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import android.net.Uri;

import com.fadcam.Constants;
import com.fadcam.R;
import com.fadcam.model.TrashItem;
import com.fadcam.utils.TrashManager;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * WatchTrashFragment — Watch-optimized "Recently Deleted" screen.
 * Shows trash items with thumbnail, name, and date.
 * Each row has RESTORE and DELETE icons.
 * Bottom button deletes everything.
 */
public class WatchTrashFragment extends Fragment {

    private static final String TAG = "WatchTrashFragment";

    private RecyclerView recyclerView;
    private TextView tvEmpty;
    private TextView btnDeleteAll;

    private final List<TrashItem> items = new ArrayList<>();
    private WatchTrashAdapter adapter;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_watch_trash, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        recyclerView  = view.findViewById(R.id.rv_watch_trash);
        tvEmpty       = view.findViewById(R.id.tv_watch_trash_empty);
        btnDeleteAll  = view.findViewById(R.id.btn_watch_trash_delete_all);

        final View btnBack = view.findViewById(R.id.btn_watch_trash_back);
        if (btnBack != null) {
            btnBack.setOnClickListener(v -> OverlayNavUtil.popLevel(requireActivity()));
        }

        adapter = new WatchTrashAdapter();
        recyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));
        recyclerView.setAdapter(adapter);

        btnDeleteAll.setOnClickListener(v -> confirmDeleteAll());

        // Always reload items when view is created to ensure fresh data
        loadItems();
        Log.d(TAG, "WatchTrashFragment onViewCreated - trash overlay shown");
    }

    @Override
    public void onResume() {
        super.onResume();
        // Reload items when returning to this fragment
        loadItems();
        Log.d(TAG, "WatchTrashFragment onResume - refreshing trash items");
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        executor.shutdownNow();
    }

    // -------------------------------------------------------------------------
    // Data loading
    // -------------------------------------------------------------------------

    private void loadItems() {
        Log.d(TAG, "Loading trash items...");
        executor.execute(() -> {
            final List<TrashItem> loaded = TrashManager.loadTrashMetadata(requireContext());
            Log.d(TAG, "Loaded " + (loaded != null ? loaded.size() : 0) + " trash items");
            if (loaded != null) {
                for (TrashItem item : loaded) {
                    Log.d(TAG, "Trash item: " + item.getOriginalDisplayName() + " -> " + item.getTrashFileName());
                }
            }
            mainHandler.post(() -> {
                items.clear();
                if (loaded != null) items.addAll(loaded);
                updateEmptyState();
                adapter.notifyDataSetChanged();
            });
        });
    }

    private void updateEmptyState() {
        final boolean empty = items.isEmpty();
        recyclerView.setVisibility(empty ? View.GONE : View.VISIBLE);
        tvEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
        btnDeleteAll.setVisibility(empty ? View.GONE : View.VISIBLE);
    }

    // -------------------------------------------------------------------------
    // Actions
    // -------------------------------------------------------------------------

    private void restoreItem(int position) {
        if (position < 0 || position >= items.size()) return;
        final TrashItem item = items.get(position);
        executor.execute(() -> {
            boolean ok = false;
            try {
                // Locate the trashed file
                final File trashDir = TrashManager.getTrashDirectory(requireContext());
                if (trashDir == null) throw new IllegalStateException("Trash dir unavailable");
                final File trashFile = new File(trashDir, item.getTrashFileName());

                // Determine original destination from the stored URI string.
                // For watch-recorded videos (file:// URI), restore to the exact original path.
                // For anything else (SAF, etc.) fall back to default TrashManager restore.
                final String originalUri = item.getOriginalUriString();
                File targetFile = null;
                if (originalUri != null && originalUri.startsWith("file://")) {
                    final String path = Uri.parse(originalUri).getPath();
                    if (path != null) targetFile = new File(path);
                }

                if (targetFile != null) {
                    // Ensure parent directory exists
                    final File parentDir = targetFile.getParentFile();
                    if (parentDir != null && !parentDir.exists()) parentDir.mkdirs();

                    // Try rename first (atomic move), then byte-copy fallback
                    if (trashFile.exists()) {
                        ok = trashFile.renameTo(targetFile);
                        if (!ok) {
                            try (java.io.FileInputStream fis = new java.io.FileInputStream(trashFile);
                                 java.io.FileOutputStream fos = new java.io.FileOutputStream(targetFile)) {
                                final byte[] buf = new byte[65536];
                                int len;
                                while ((len = fis.read(buf)) > 0) fos.write(buf, 0, len);
                                fos.flush();
                                ok = trashFile.delete();
                            }
                        }
                    }

                    if (ok) {
                        // Remove entry from trash metadata
                        final List<TrashItem> meta = TrashManager.loadTrashMetadata(requireContext());
                        final java.util.Iterator<TrashItem> it = meta.iterator();
                        while (it.hasNext()) {
                            if (it.next().getTrashFileName().equals(item.getTrashFileName())) {
                                it.remove();
                                break;
                            }
                        }
                        TrashManager.saveTrashMetadata(requireContext(), meta);
                    }
                } else {
                    // Fallback: use the generic TrashManager restore (goes to Downloads/FadCam)
                    final List<TrashItem> single = new ArrayList<>();
                    single.add(item);
                    ok = TrashManager.restoreItemsFromTrash(requireContext(), single);
                }

            } catch (Exception e) {
                Log.e(TAG, "restoreItem failed", e);
            }

            final boolean finalOk = ok;
            mainHandler.post(() -> {
                if (!isAdded()) return;
                if (finalOk) {
                    items.remove(position);
                    adapter.notifyItemRemoved(position);
                    updateEmptyState();
                    Toast.makeText(requireContext(),
                            getString(R.string.trash_restore_success_toast, 1),
                            Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(requireContext(),
                            R.string.trash_restore_fail_toast,
                            Toast.LENGTH_SHORT).show();
                }
            });
        });
    }

    private void confirmDeleteItem(int position) {
        if (position < 0 || position >= items.size()) return;
        final String name = items.get(position).getOriginalDisplayName();
        new AlertDialog.Builder(requireContext(), R.style.WatchDeleteConfirmDialog)
                .setTitle(R.string.watch_perm_delete_title)
                .setMessage(getString(R.string.watch_perm_delete_msg, name != null ? name : ""))
                .setPositiveButton(R.string.watch_perm_delete_action, (d, w) -> deleteItem(position))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void confirmDeleteAll() {
        if (items.isEmpty()) return;
        new AlertDialog.Builder(requireContext(), R.style.WatchDeleteConfirmDialog)
                .setTitle(R.string.watch_perm_delete_all_title)
                .setMessage(getString(R.string.watch_perm_delete_all_msg, items.size()))
                .setPositiveButton(R.string.watch_perm_delete_action, (d, w) -> deleteAll())
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void deleteItem(int position) {
        if (position < 0 || position >= items.size()) return;
        final TrashItem item = items.get(position);
        executor.execute(() -> {
            final List<TrashItem> single = new ArrayList<>();
            single.add(item);
            TrashManager.permanentlyDeleteItems(requireContext(), single);
            mainHandler.post(() -> {
                if (!isAdded()) return;
                items.remove(position);
                adapter.notifyItemRemoved(position);
                updateEmptyState();
                Toast.makeText(requireContext(),
                        getString(R.string.trash_items_deleted_toast, 1),
                        Toast.LENGTH_SHORT).show();
            });
        });
    }

    private void deleteAll() {
        if (items.isEmpty()) return;
        executor.execute(() -> {
            TrashManager.emptyAllTrash(requireContext());
            mainHandler.post(() -> {
                if (!isAdded()) return;
                final int count = items.size();
                items.clear();
                adapter.notifyDataSetChanged();
                updateEmptyState();
                Toast.makeText(requireContext(),
                        getString(R.string.trash_items_deleted_toast, count),
                        Toast.LENGTH_SHORT).show();
            });
        });
    }

    // -------------------------------------------------------------------------
    // Adapter
    // -------------------------------------------------------------------------

    private class WatchTrashAdapter extends RecyclerView.Adapter<WatchTrashAdapter.VH> {

        private final SimpleDateFormat dateFormat =
                new SimpleDateFormat("MMM d, HH:mm", Locale.getDefault());

        class VH extends RecyclerView.ViewHolder {
            final ImageView ivThumb;
            final TextView  tvName;
            final TextView  tvDate;
            final TextView  btnRestore;
            final TextView  btnDelete;

            VH(@NonNull View v) {
                super(v);
                ivThumb    = v.findViewById(R.id.iv_watch_trash_thumb);
                tvName     = v.findViewById(R.id.tv_watch_trash_name);
                tvDate     = v.findViewById(R.id.tv_watch_trash_date);
                btnRestore = v.findViewById(R.id.btn_watch_trash_restore);
                btnDelete  = v.findViewById(R.id.btn_watch_trash_delete);
            }
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            final View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_watch_trash, parent, false);
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
            final TrashItem item = items.get(position);

            // Name (strip extension)
            String displayName = item.getOriginalDisplayName();
            if (displayName == null) displayName = item.getTrashFileName();
            if (displayName == null) displayName = "Unknown";
            if (displayName.contains(".")) {
                displayName = displayName.substring(0, displayName.lastIndexOf('.'));
            }
            holder.tvName.setText(displayName);

            // Date
            final String dateStr = dateFormat.format(new Date(item.getDateTrashed()));
            holder.tvDate.setText(dateStr);

            // Thumbnail (async)
            holder.ivThumb.setImageBitmap(null);
            holder.ivThumb.setBackgroundColor(0xFF1A1A1A); // dark placeholder, clipped by ViewOutlineProvider
            final String trashFileName = item.getTrashFileName();
            if (trashFileName != null) {
                executor.execute(() -> {
                    Bitmap bmp = null;
                    android.media.MediaMetadataRetriever retriever = null;
                    try {
                        final File trashDir = TrashManager.getTrashDirectory(requireContext());
                        if (trashDir != null) {
                            final File videoFile = new File(trashDir, trashFileName);
                            Log.d(TAG, "Loading trash thumbnail: " + videoFile.getAbsolutePath());
                            Log.d(TAG, "File exists: " + videoFile.exists() + ", can read: " + videoFile.canRead());
                            
                            if (videoFile.exists()) {
                                retriever = new android.media.MediaMetadataRetriever();
                                retriever.setDataSource(videoFile.getAbsolutePath());
                                Bitmap frame = retriever.getFrameAtTime(1000000, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
                                if (frame != null) {
                                    Log.d(TAG, "Got trash frame: " + frame.getWidth() + "x" + frame.getHeight());
                                    bmp = Bitmap.createScaledBitmap(frame, 64, 64, true);
                                }
                            }
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Thumbnail failed for: " + trashFileName, e);
                    } finally {
                        if (retriever != null) {
                            try { retriever.release(); } catch (Exception ignored) {}
                        }
                    }
                    final Bitmap finalBmp = bmp;
                    mainHandler.post(() -> {
                        if (finalBmp != null && holder.getAdapterPosition() == position) {
                            holder.ivThumb.setImageBitmap(finalBmp);
                        }
                    });
                });
            }

            // Action buttons
            holder.btnRestore.setOnClickListener(v -> {
                final int pos = holder.getAdapterPosition();
                if (pos != RecyclerView.NO_ID) restoreItem(pos);
            });
            holder.btnDelete.setOnClickListener(v -> {
                final int pos = holder.getAdapterPosition();
                if (pos != RecyclerView.NO_ID) confirmDeleteItem(pos);
            });
        }

        @Override
        public int getItemCount() {
            return items.size();
        }
    }
}
