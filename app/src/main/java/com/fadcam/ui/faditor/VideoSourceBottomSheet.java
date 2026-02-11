package com.fadcam.ui.faditor;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Typeface;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.res.ResourcesCompat;
import androidx.documentfile.provider.DocumentFile;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.request.RequestOptions;
import com.fadcam.Constants;
import com.fadcam.R;
import com.fadcam.SharedPreferencesManager;
import com.fadcam.utils.RecordingStoragePaths;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Bottom sheet that presents video source options for Faditor Mini:
 * <ul>
 *   <li>"Browse Device" — opens system file picker for any video</li>
 *   <li>"FadCam Recordings" — lists recordings from the active storage location</li>
 * </ul>
 *
 * <p>Uses the same dark gradient styling as other FadCam bottom sheets
 * ({@code CustomBottomSheetDialogTheme} + {@code gradient_background}).</p>
 */
public class VideoSourceBottomSheet extends BottomSheetDialogFragment {

    private static final String TAG = "VideoSourceBottomSheet";

    /** Callback interface for video source selection. */
    public interface Callback {
        /** User chose to browse device via system picker. */
        void onBrowseDevice();

        /** User selected a FadCam recording directly. */
        void onRecordingSelected(@NonNull Uri videoUri);
    }

    @Nullable
    private Callback callback;

    private final ExecutorService scanExecutor = Executors.newSingleThreadExecutor();
    private LinearLayout recordingsContainer;
    private View loadingIndicator;
    private View emptyState;

    /**
     * Set the callback for video source selection events.
     */
    public void setCallback(@Nullable Callback callback) {
        this.callback = callback;
    }

    // ── Theme & dark styling ─────────────────────────────────────────

    @Override
    public int getTheme() {
        return R.style.CustomBottomSheetDialogTheme;
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(Bundle savedInstanceState) {
        Dialog dialog = super.onCreateDialog(savedInstanceState);
        dialog.setOnShowListener(d -> {
            View bottomSheet = ((BottomSheetDialog) dialog)
                    .findViewById(com.google.android.material.R.id.design_bottom_sheet);
            if (bottomSheet != null) {
                bottomSheet.setBackgroundResource(R.drawable.picker_bottom_sheet_dark_gradient_bg);
            }
        });
        return dialog;
    }

    // ── View creation ────────────────────────────────────────────────

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        float dp = getResources().getDisplayMetrics().density;
        Typeface materialIcons = ResourcesCompat.getFont(requireContext(), R.font.materialicons);

        // Root layout
        LinearLayout root = new LinearLayout(requireContext());
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(0, (int) (12 * dp), 0, (int) (24 * dp));

        // ── Title ───────────────────────────────────────────────
        TextView title = new TextView(requireContext());
        title.setText(R.string.faditor_start_project);
        title.setTextColor(0xFFFFFFFF);
        title.setTextSize(18);
        title.setTypeface(null, Typeface.BOLD);
        title.setPadding((int) (20 * dp), (int) (12 * dp),
                (int) (20 * dp), (int) (4 * dp));
        root.addView(title);

        // Subtitle / helper text
        TextView subtitle = new TextView(requireContext());
        subtitle.setText(R.string.faditor_source_chooser_desc);
        subtitle.setTextColor(0xFF888888);
        subtitle.setTextSize(13);
        subtitle.setPadding((int) (20 * dp), 0, (int) (20 * dp), (int) (16 * dp));
        root.addView(subtitle);

        // ── Option 1: Browse Device ─────────────────────────────
        View browseRow = createBrowseRow(materialIcons, dp);
        LinearLayout.LayoutParams browseLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        browseLp.setMargins((int) (12 * dp), 0, (int) (12 * dp), 0);
        browseRow.setLayoutParams(browseLp);
        root.addView(browseRow);

        // ── Divider ─────────────────────────────────────────────
        View divider = new View(requireContext());
        divider.setBackgroundColor(0xFF2A2A2A);
        LinearLayout.LayoutParams divLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, (int) (1 * dp));
        divLp.setMargins((int) (20 * dp), (int) (8 * dp),
                (int) (20 * dp), (int) (8 * dp));
        divider.setLayoutParams(divLp);
        root.addView(divider);

        // ── Section: FadCam Recordings ──────────────────────────
        TextView recordingsHeader = new TextView(requireContext());
        recordingsHeader.setText(R.string.faditor_your_recordings);
        recordingsHeader.setTextColor(0xFFBBBBBB);
        recordingsHeader.setTextSize(12);
        recordingsHeader.setTypeface(null, Typeface.BOLD);
        recordingsHeader.setAllCaps(true);
        recordingsHeader.setLetterSpacing(0.08f);
        recordingsHeader.setPadding((int) (20 * dp), (int) (12 * dp),
                (int) (20 * dp), (int) (4 * dp));
        root.addView(recordingsHeader);

        // Helper subtitle for recordings
        TextView recordingsHelp = new TextView(requireContext());
        recordingsHelp.setText(R.string.faditor_recordings_helper);
        recordingsHelp.setTextColor(0xFF666666);
        recordingsHelp.setTextSize(12);
        recordingsHelp.setPadding((int) (20 * dp), 0, (int) (20 * dp), (int) (10 * dp));
        root.addView(recordingsHelp);

        // Loading indicator
        loadingIndicator = createLoadingView(dp);
        root.addView(loadingIndicator);

        // Empty state
        emptyState = createEmptyState(materialIcons, dp);
        emptyState.setVisibility(View.GONE);
        root.addView(emptyState);

        // Scrollable recordings list
        ScrollView scrollView = new ScrollView(requireContext());
        scrollView.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, (int) (320 * dp)));

        recordingsContainer = new LinearLayout(requireContext());
        recordingsContainer.setOrientation(LinearLayout.VERTICAL);
        scrollView.addView(recordingsContainer);
        root.addView(scrollView);

        // Start background scan
        loadRecordings();

        return root;
    }

    // ── "Browse Device" row ──────────────────────────────────────────

    @NonNull
    private View createBrowseRow(@Nullable Typeface iconFont, float dp) {
        LinearLayout row = new LinearLayout(requireContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding((int) (20 * dp), (int) (14 * dp),
                (int) (20 * dp), (int) (14 * dp));
        row.setBackgroundResource(R.drawable.settings_home_row_bg);
        row.setClickable(true);
        row.setFocusable(true);
        row.setOnClickListener(v -> {
            dismiss();
            if (callback != null) callback.onBrowseDevice();
        });

        // Icon
        TextView icon = new TextView(requireContext());
        icon.setTypeface(iconFont);
        icon.setText("folder_open");
        icon.setTextColor(0xFF42A5F5);
        icon.setTextSize(24);
        icon.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(
                (int) (40 * dp), (int) (40 * dp));
        iconLp.setMarginEnd((int) (14 * dp));
        icon.setLayoutParams(iconLp);
        row.addView(icon);

        // Text
        LinearLayout textSection = new LinearLayout(requireContext());
        textSection.setOrientation(LinearLayout.VERTICAL);
        textSection.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView label = new TextView(requireContext());
        label.setText(R.string.faditor_browse_device);
        label.setTextColor(0xFFFFFFFF);
        label.setTextSize(15);
        label.setTypeface(null, Typeface.BOLD);
        textSection.addView(label);

        TextView desc = new TextView(requireContext());
        desc.setText(R.string.faditor_browse_device_desc);
        desc.setTextColor(0xFF888888);
        desc.setTextSize(12);
        textSection.addView(desc);

        row.addView(textSection);

        // Arrow
        TextView arrow = new TextView(requireContext());
        arrow.setTypeface(iconFont);
        arrow.setText("chevron_right");
        arrow.setTextColor(0xFF555555);
        arrow.setTextSize(20);
        row.addView(arrow);

        return row;
    }

    // ── Recording scanning ───────────────────────────────────────────

    private void loadRecordings() {
        scanExecutor.execute(() -> {
            List<RecordingItem> items = scanActiveStorage();
            if (!isAdded()) return;
            requireActivity().runOnUiThread(() -> displayRecordings(items));
        });
    }

    /**
     * Scan <b>only</b> the currently active storage location.
     *
     * <p>If the user has "custom" storage mode selected, scans the SAF tree.
     * Otherwise, scans the internal FadCam/FadRec directories.</p>
     */
    @NonNull
    private List<RecordingItem> scanActiveStorage() {
        List<RecordingItem> items = new ArrayList<>();
        if (!isAdded()) return items;

        Context ctx = requireContext();
        SharedPreferencesManager prefs = SharedPreferencesManager.getInstance(ctx);
        String storageMode = prefs.getStorageMode();

        Log.d(TAG, "Storage mode: " + storageMode);

        if (SharedPreferencesManager.STORAGE_MODE_CUSTOM.equals(storageMode)) {
            // ── Custom SAF storage ──────────────────────────────
            String safUri = prefs.getCustomStorageUri();
            if (safUri != null) {
                try {
                    Uri treeUri = Uri.parse(safUri);
                    if (hasSafPermission(ctx, treeUri)) {
                        items.addAll(scanSafDir(ctx, treeUri));
                        Log.d(TAG, "Scanned custom (SAF) storage: " + items.size() + " videos");
                    } else {
                        Log.w(TAG, "No SAF permission for: " + safUri);
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error scanning SAF storage", e);
                }
            } else {
                Log.w(TAG, "Custom storage mode but no URI set");
            }
        } else {
            // ── Internal storage (default) ──────────────────────
            try {
                File externalDir = ctx.getExternalFilesDir(null);
                if (externalDir != null) {
                    File base = new File(externalDir, Constants.RECORDING_DIRECTORY);
                    items.addAll(scanFileDir(
                            new File(base, Constants.RECORDING_SUBDIR_CAMERA),
                            getString(R.string.faditor_fadcam_source)));
                    items.addAll(scanFileDir(
                            new File(base, Constants.RECORDING_SUBDIR_DUAL),
                            getString(R.string.faditor_fadcam_source)));
                    items.addAll(scanFileDir(
                            new File(base, Constants.RECORDING_SUBDIR_SCREEN),
                            getString(R.string.faditor_fadrec_source)));
                    items.addAll(scanFileDir(
                            new File(base, Constants.RECORDING_SUBDIR_FADITOR),
                            getString(R.string.faditor_fadcam_source)));
                    items.addAll(scanFileDir(
                            new File(base, Constants.RECORDING_SUBDIR_STREAM),
                            getString(R.string.faditor_fadrec_source)));
                    Log.d(TAG, "Scanned internal storage: " + items.size() + " videos");
                }
            } catch (Exception e) {
                Log.e(TAG, "Error scanning internal storage", e);
            }
        }

        // Sort newest first
        Collections.sort(items, (a, b) -> Long.compare(b.lastModified, a.lastModified));
        return items;
    }

    @NonNull
    private List<RecordingItem> scanFileDir(@NonNull File dir, @NonNull String source) {
        List<RecordingItem> items = new ArrayList<>();
        if (!dir.exists() || !dir.isDirectory()) return items;

        File[] files = dir.listFiles();
        if (files == null) return items;

        for (File f : files) {
            if (f.isFile()
                    && f.getName().endsWith("." + Constants.RECORDING_FILE_EXTENSION)
                    && !f.getName().startsWith("temp_")) {
                items.add(new RecordingItem(
                        Uri.fromFile(f), f.getName(), f.length(), f.lastModified(), source));
            }
        }
        return items;
    }

    @NonNull
    private List<RecordingItem> scanSafDir(@NonNull Context ctx, @NonNull Uri treeUri) {
        List<RecordingItem> items = new ArrayList<>();
        try {
            DocumentFile dir = DocumentFile.fromTreeUri(ctx, treeUri);
            if (dir == null || !dir.isDirectory() || !dir.canRead()) return items;
            addSafDirectoryItems(items, dir, Constants.RECORDING_SUBDIR_CAMERA, getString(R.string.faditor_fadcam_source));
            addSafDirectoryItems(items, dir, Constants.RECORDING_SUBDIR_DUAL, getString(R.string.faditor_fadcam_source));
            addSafDirectoryItems(items, dir, Constants.RECORDING_SUBDIR_SCREEN, getString(R.string.faditor_fadrec_source));
            addSafDirectoryItems(items, dir, Constants.RECORDING_SUBDIR_FADITOR, getString(R.string.faditor_fadcam_source));
            addSafDirectoryItems(items, dir, Constants.RECORDING_SUBDIR_STREAM, getString(R.string.faditor_fadrec_source));
        } catch (Exception e) {
            Log.e(TAG, "Error listing SAF files", e);
        }
        return items;
    }

    private void addSafDirectoryItems(
            @NonNull List<RecordingItem> items,
            @NonNull DocumentFile baseDir,
            @NonNull String directoryName,
            @NonNull String source
    ) {
        DocumentFile child = RecordingStoragePaths.findOrCreateChildDirectory(baseDir, directoryName, false);
        if (child == null || !child.isDirectory() || !child.canRead()) return;
        for (DocumentFile doc : child.listFiles()) {
            if (doc == null || !doc.isFile()) continue;
            String name = doc.getName();
            String mime = doc.getType();
            if (name != null && mime != null && mime.startsWith("video/")
                    && name.endsWith(Constants.RECORDING_FILE_EXTENSION)
                    && !name.startsWith("temp_")) {
                items.add(new RecordingItem(
                        doc.getUri(), name, doc.length(), doc.lastModified(), source));
            }
        }
    }

    private boolean hasSafPermission(@NonNull Context ctx, @NonNull Uri treeUri) {
        try {
            for (android.content.UriPermission perm :
                    ctx.getContentResolver().getPersistedUriPermissions()) {
                if (perm.getUri().equals(treeUri) && perm.isReadPermission()) return true;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error checking SAF permission", e);
        }
        return false;
    }

    // ── Display recordings ───────────────────────────────────────────

    private void displayRecordings(@NonNull List<RecordingItem> items) {
        if (recordingsContainer == null) return;

        loadingIndicator.setVisibility(View.GONE);
        recordingsContainer.removeAllViews();

        if (items.isEmpty()) {
            emptyState.setVisibility(View.VISIBLE);
            return;
        }

        emptyState.setVisibility(View.GONE);
        Typeface materialIcons = ResourcesCompat.getFont(requireContext(), R.font.materialicons);
        SimpleDateFormat dateFormat = new SimpleDateFormat("MMM d, h:mm a", Locale.getDefault());

        for (RecordingItem item : items) {
            View row = createRecordingRow(item, materialIcons, dateFormat);
            recordingsContainer.addView(row);
        }
    }

    /**
     * Create a single recording row with thumbnail, name, duration, and size.
     * Matches the visual pattern of the Records tab.
     */
    @NonNull
    private View createRecordingRow(@NonNull RecordingItem item,
                                    @Nullable Typeface iconFont,
                                    @NonNull SimpleDateFormat dateFormat) {
        float dp = getResources().getDisplayMetrics().density;
        Context ctx = requireContext();

        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding((int) (16 * dp), (int) (8 * dp),
                (int) (16 * dp), (int) (8 * dp));
        row.setBackgroundResource(R.drawable.settings_home_row_bg);
        row.setClickable(true);
        row.setFocusable(true);

        // ── Thumbnail (rounded corners via CardView-like clipping) ────
        androidx.cardview.widget.CardView thumbCard = new androidx.cardview.widget.CardView(ctx);
        thumbCard.setCardElevation(0);
        thumbCard.setCardBackgroundColor(0xFF222222);
        thumbCard.setRadius(8 * dp);
        LinearLayout.LayoutParams thumbCardLp = new LinearLayout.LayoutParams(
                (int) (80 * dp), (int) (50 * dp));
        thumbCardLp.setMarginEnd((int) (12 * dp));
        thumbCard.setLayoutParams(thumbCardLp);

        ImageView thumbView = new ImageView(ctx);
        thumbView.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        thumbView.setScaleType(ImageView.ScaleType.CENTER_CROP);
        thumbView.setImageResource(R.drawable.ic_video_placeholder);
        thumbCard.addView(thumbView);

        // Load thumbnail with Glide
        try {
            Glide.with(ctx)
                    .asBitmap()
                    .load(item.uri)
                    .apply(new RequestOptions()
                            .diskCacheStrategy(DiskCacheStrategy.ALL)
                            .override(200, 125)
                            .centerCrop()
                            .placeholder(R.drawable.ic_video_placeholder)
                            .error(R.drawable.ic_video_placeholder))
                    .thumbnail(0.1f)
                    .into(thumbView);
        } catch (Exception e) {
            Log.w(TAG, "Error loading thumbnail for: " + item.name, e);
        }

        row.addView(thumbCard);

        // ── Text section ─────────────────────────────────────────
        LinearLayout textSection = new LinearLayout(ctx);
        textSection.setOrientation(LinearLayout.VERTICAL);
        textSection.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        // Filename (without extension)
        TextView nameView = new TextView(ctx);
        String displayName = item.name;
        if (displayName.endsWith(".mp4")) {
            displayName = displayName.substring(0, displayName.length() - 4);
        }
        nameView.setText(displayName);
        nameView.setTextColor(0xFFFFFFFF);
        nameView.setTextSize(13);
        nameView.setTypeface(null, Typeface.BOLD);
        nameView.setMaxLines(1);
        nameView.setEllipsize(TextUtils.TruncateAt.END);
        textSection.addView(nameView);

        // Meta line: size · duration · source
        TextView metaView = new TextView(ctx);
        String meta = formatSize(item.size) + " · " + item.source;
        metaView.setText(meta);
        metaView.setTextColor(0xFF777777);
        metaView.setTextSize(11);
        metaView.setMaxLines(1);
        textSection.addView(metaView);

        // Load duration on background thread and update
        final TextView durationMeta = metaView;
        scanExecutor.execute(() -> {
            long durationMs = getVideoDuration(ctx, item.uri);
            if (!isAdded()) return;
            String durationStr = formatDuration(durationMs);
            String fullMeta = formatSize(item.size) + " · " + durationStr + " · " + item.source;
            requireActivity().runOnUiThread(() -> durationMeta.setText(fullMeta));
        });

        row.addView(textSection);

        // ── Edit icon ────────────────────────────────────────────
        TextView editIcon = new TextView(ctx);
        editIcon.setTypeface(iconFont);
        editIcon.setText("edit");
        editIcon.setTextColor(0xFF4CAF50);
        editIcon.setTextSize(18);
        editIcon.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams editLp = new LinearLayout.LayoutParams(
                (int) (32 * dp), (int) (32 * dp));
        editLp.setMarginStart((int) (8 * dp));
        editIcon.setLayoutParams(editLp);
        row.addView(editIcon);

        row.setOnClickListener(v -> {
            dismiss();
            if (callback != null) callback.onRecordingSelected(item.uri);
        });

        return row;
    }

    // ── Helper views ─────────────────────────────────────────────────

    @NonNull
    private View createLoadingView(float dp) {
        TextView tv = new TextView(requireContext());
        tv.setText(R.string.faditor_scanning);
        tv.setTextColor(0xFF777777);
        tv.setTextSize(13);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(0, (int) (24 * dp), 0, (int) (24 * dp));
        return tv;
    }

    @NonNull
    private View createEmptyState(@Nullable Typeface iconFont, float dp) {
        LinearLayout layout = new LinearLayout(requireContext());
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setGravity(Gravity.CENTER);
        layout.setPadding((int) (24 * dp), (int) (24 * dp),
                (int) (24 * dp), (int) (24 * dp));

        TextView icon = new TextView(requireContext());
        icon.setTypeface(iconFont);
        icon.setText("videocam_off");
        icon.setTextColor(0xFF555555);
        icon.setTextSize(32);
        icon.setGravity(Gravity.CENTER);
        layout.addView(icon);

        TextView msg = new TextView(requireContext());
        msg.setText(R.string.faditor_no_recordings);
        msg.setTextColor(0xFF777777);
        msg.setTextSize(13);
        msg.setGravity(Gravity.CENTER);
        msg.setPadding(0, (int) (8 * dp), 0, 0);
        layout.addView(msg);

        return layout;
    }

    // ── Utility methods ──────────────────────────────────────────────

    /**
     * Retrieve video duration using FFprobeKit (primary) with MediaMetadataRetriever fallback.
     * Mirrors the approach used by RecordsAdapter for reliable duration on all URI schemes.
     *
     * @return duration in milliseconds, or 0 on error
     */
    private static long getVideoDuration(@NonNull Context ctx, @NonNull Uri uri) {
        // ── FFprobeKit (primary – matches RecordsAdapter) ────────
        try {
            String filePath = getFFprobePathForUri(uri);
            com.arthenica.ffmpegkit.MediaInformationSession session =
                    com.arthenica.ffmpegkit.FFprobeKit.getMediaInformation(filePath);
            com.arthenica.ffmpegkit.MediaInformation info = session.getMediaInformation();

            if (info != null) {
                String durationStr = info.getDuration();
                if (durationStr != null) {
                    double durationSec = Double.parseDouble(durationStr);
                    long durationMs = (long) (durationSec * 1000);
                    Log.d(TAG, "Duration from FFprobe: " + durationMs + "ms for " + uri.getLastPathSegment());
                    return durationMs;
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "FFprobe duration failed for: " + uri, e);
        }

        // ── MediaMetadataRetriever fallback ──────────────────────
        MediaMetadataRetriever retriever = null;
        try {
            retriever = new MediaMetadataRetriever();

            if ("file".equals(uri.getScheme()) && uri.getPath() != null) {
                retriever.setDataSource(uri.getPath());
            } else {
                retriever.setDataSource(ctx, uri);
            }

            String durationStr = retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_DURATION);
            if (durationStr != null) {
                long durationMs = Long.parseLong(durationStr);
                Log.d(TAG, "Duration from MMR fallback: " + durationMs + "ms for " + uri.getLastPathSegment());
                return durationMs;
            }
        } catch (Exception e) {
            Log.w(TAG, "MMR fallback failed for: " + uri, e);
        } finally {
            if (retriever != null) {
                try {
                    retriever.release();
                } catch (Exception ignored) { }
            }
        }

        Log.w(TAG, "Could not determine duration for: " + uri);
        return 0;
    }

    /**
     * Build a file path suitable for FFprobeKit.
     * For content:// URIs (SAF), reconstructs a /storage/emulated/0 path when possible,
     * otherwise falls back to saf: protocol.
     */
    private static String getFFprobePathForUri(@NonNull Uri uri) {
        if ("file".equals(uri.getScheme()) && uri.getPath() != null) {
            return uri.getPath();
        }

        // For content:// URIs, try to reconstruct actual file path
        String path = uri.getPath();
        if (path != null && path.contains(":")) {
            int lastColonIndex = path.lastIndexOf(':');
            if (lastColonIndex >= 0 && lastColonIndex < path.length() - 1) {
                String relativePath = path.substring(lastColonIndex + 1);
                String reconstructedPath = "/storage/emulated/0/" + relativePath;
                java.io.File file = new java.io.File(reconstructedPath);
                if (file.exists() && file.canRead()) {
                    Log.d(TAG, "FFprobe using reconstructed path: " + reconstructedPath);
                    return reconstructedPath;
                }
            }
        }

        // Fall back to SAF protocol
        Log.d(TAG, "FFprobe using SAF protocol: saf:" + uri);
        return "saf:" + uri.toString();
    }

    @NonNull
    private static String formatDuration(long durationMs) {
        if (durationMs <= 0) return "0s";
        long totalSeconds = durationMs / 1000;
        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;
        if (hours > 0) {
            return String.format(Locale.US, "%dh %02dm %02ds", hours, minutes, seconds);
        } else if (minutes > 0) {
            return String.format(Locale.US, "%dm %02ds", minutes, seconds);
        } else {
            return String.format(Locale.US, "%ds", seconds);
        }
    }

    @NonNull
    private static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(Locale.US, "%.1f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024)
            return String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024));
        return String.format(Locale.US, "%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }

    // ── Model ────────────────────────────────────────────────────────

    private static class RecordingItem {
        @NonNull final Uri uri;
        @NonNull final String name;
        final long size;
        final long lastModified;
        @NonNull final String source;

        RecordingItem(@NonNull Uri uri, @NonNull String name,
                      long size, long lastModified, @NonNull String source) {
            this.uri = uri;
            this.name = name;
            this.size = size;
            this.lastModified = lastModified;
            this.source = source;
        }
    }
}
