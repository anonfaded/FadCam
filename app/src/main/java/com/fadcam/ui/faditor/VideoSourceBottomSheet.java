package com.fadcam.ui.faditor;

import android.content.Context;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.res.ResourcesCompat;
import androidx.documentfile.provider.DocumentFile;

import com.fadcam.Constants;
import com.fadcam.R;
import com.fadcam.SharedPreferencesManager;
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
 *   <li>"Browse Device" — opens system file picker</li>
 *   <li>"FadCam Recordings" — lists recordings from active storage</li>
 * </ul>
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

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        float density = getResources().getDisplayMetrics().density;
        Typeface materialIcons = ResourcesCompat.getFont(requireContext(), R.font.materialicons);

        // Root layout
        LinearLayout root = new LinearLayout(requireContext());
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(0, (int) (12 * density), 0, (int) (24 * density));

        // Title
        TextView title = new TextView(requireContext());
        title.setText(R.string.faditor_new_project);
        title.setTextColor(0xFFFFFFFF);
        title.setTextSize(18);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setPadding((int) (20 * density), (int) (12 * density),
                (int) (20 * density), (int) (16 * density));
        root.addView(title);

        // ── Option 1: Browse Device ─────────────────────────────
        root.addView(createOptionRow(
                materialIcons, "folder_open", 0xFF42A5F5,
                getString(R.string.faditor_browse_device),
                getString(R.string.faditor_browse_device_desc),
                v -> {
                    dismiss();
                    if (callback != null) callback.onBrowseDevice();
                }));

        // ── Divider ─────────────────────────────────────────────
        View divider = new View(requireContext());
        divider.setBackgroundColor(0xFF2A2A2A);
        divider.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, (int) (1 * density)));
        LinearLayout.LayoutParams divLp = (LinearLayout.LayoutParams) divider.getLayoutParams();
        divLp.setMargins((int) (20 * density), (int) (4 * density),
                (int) (20 * density), (int) (4 * density));
        root.addView(divider);

        // ── Section: FadCam Recordings ──────────────────────────
        TextView recordingsTitle = new TextView(requireContext());
        recordingsTitle.setText(R.string.faditor_your_recordings);
        recordingsTitle.setTextColor(0xFFBBBBBB);
        recordingsTitle.setTextSize(13);
        recordingsTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        recordingsTitle.setAllCaps(true);
        recordingsTitle.setLetterSpacing(0.08f);
        recordingsTitle.setPadding((int) (20 * density), (int) (16 * density),
                (int) (20 * density), (int) (8 * density));
        root.addView(recordingsTitle);

        // Loading indicator
        loadingIndicator = createLoadingView(density);
        root.addView(loadingIndicator);

        // Empty state
        emptyState = createEmptyState(materialIcons, density);
        emptyState.setVisibility(View.GONE);
        root.addView(emptyState);

        // Scrollable recordings container
        ScrollView scrollView = new ScrollView(requireContext());
        scrollView.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, (int) (280 * density)));

        recordingsContainer = new LinearLayout(requireContext());
        recordingsContainer.setOrientation(LinearLayout.VERTICAL);
        scrollView.addView(recordingsContainer);
        root.addView(scrollView);

        // Start scanning
        loadRecordings();

        return root;
    }

    // ── Option row builder ───────────────────────────────────────────

    @NonNull
    private View createOptionRow(@Nullable Typeface iconFont, @NonNull String iconText,
                                 int iconColor, @NonNull String label,
                                 @NonNull String description,
                                 @NonNull View.OnClickListener clickListener) {
        float density = getResources().getDisplayMetrics().density;

        LinearLayout row = new LinearLayout(requireContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding((int) (20 * density), (int) (14 * density),
                (int) (20 * density), (int) (14 * density));
        row.setBackgroundResource(R.drawable.settings_home_row_bg);
        row.setClickable(true);
        row.setFocusable(true);
        row.setOnClickListener(clickListener);

        // Icon
        TextView icon = new TextView(requireContext());
        icon.setTypeface(iconFont);
        icon.setText(iconText);
        icon.setTextColor(iconColor);
        icon.setTextSize(24);
        icon.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(
                (int) (40 * density), (int) (40 * density));
        iconLp.setMarginEnd((int) (14 * density));
        icon.setLayoutParams(iconLp);
        row.addView(icon);

        // Text
        LinearLayout textSection = new LinearLayout(requireContext());
        textSection.setOrientation(LinearLayout.VERTICAL);
        textSection.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView labelView = new TextView(requireContext());
        labelView.setText(label);
        labelView.setTextColor(0xFFFFFFFF);
        labelView.setTextSize(15);
        labelView.setTypeface(null, android.graphics.Typeface.BOLD);
        textSection.addView(labelView);

        TextView descView = new TextView(requireContext());
        descView.setText(description);
        descView.setTextColor(0xFF888888);
        descView.setTextSize(12);
        descView.setMaxLines(1);
        textSection.addView(descView);

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
     * Scan the currently active storage location (based on SharedPreferences).
     */
    @NonNull
    private List<RecordingItem> scanActiveStorage() {
        List<RecordingItem> items = new ArrayList<>();
        if (!isAdded()) return items;

        Context ctx = requireContext();
        SharedPreferencesManager prefs = SharedPreferencesManager.getInstance(ctx);
        String safUri = prefs.getCustomStorageUri();

        // If custom SAF storage is configured, use it
        if (safUri != null) {
            try {
                Uri treeUri = Uri.parse(safUri);
                if (hasSafPermission(ctx, treeUri)) {
                    items.addAll(scanSafDir(ctx, treeUri));
                    Log.d(TAG, "Scanned SAF storage: " + items.size() + " videos");
                }
            } catch (Exception e) {
                Log.e(TAG, "Error scanning SAF storage", e);
            }
        }

        // Also scan internal storage (default location)
        try {
            File externalDir = ctx.getExternalFilesDir(null);
            if (externalDir != null) {
                items.addAll(scanFileDir(
                        new File(externalDir, Constants.RECORDING_DIRECTORY), "FadCam"));
                items.addAll(scanFileDir(
                        new File(externalDir, Constants.RECORDING_DIRECTORY_FADREC), "FadRec"));
            }
        } catch (Exception e) {
            Log.e(TAG, "Error scanning internal storage", e);
        }

        // Deduplicate by filename (SAF and internal might overlap)
        List<RecordingItem> deduped = new ArrayList<>();
        List<String> seenNames = new ArrayList<>();
        for (RecordingItem item : items) {
            if (!seenNames.contains(item.name)) {
                seenNames.add(item.name);
                deduped.add(item);
            }
        }

        // Sort newest first
        Collections.sort(deduped, (a, b) -> Long.compare(b.lastModified, a.lastModified));
        Log.d(TAG, "Total recordings found: " + deduped.size());
        return deduped;
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

            for (DocumentFile doc : dir.listFiles()) {
                if (doc == null || !doc.isFile()) continue;
                String name = doc.getName();
                String mime = doc.getType();
                if (name != null && mime != null && mime.startsWith("video/")
                        && name.endsWith(Constants.RECORDING_FILE_EXTENSION)
                        && !name.startsWith("temp_")) {
                    items.add(new RecordingItem(
                            doc.getUri(), name, doc.length(), doc.lastModified(), "FadCam"));
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error listing SAF files", e);
        }
        return items;
    }

    private boolean hasSafPermission(@NonNull Context ctx, @NonNull Uri treeUri) {
        try {
            for (android.content.UriPermission perm : ctx.getContentResolver().getPersistedUriPermissions()) {
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

    @NonNull
    private View createRecordingRow(@NonNull RecordingItem item,
                                    @Nullable Typeface iconFont,
                                    @NonNull SimpleDateFormat dateFormat) {
        float density = getResources().getDisplayMetrics().density;

        LinearLayout row = new LinearLayout(requireContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding((int) (20 * density), (int) (10 * density),
                (int) (20 * density), (int) (10 * density));
        row.setBackgroundResource(R.drawable.settings_home_row_bg);
        row.setClickable(true);
        row.setFocusable(true);

        // Icon
        TextView icon = new TextView(requireContext());
        icon.setTypeface(iconFont);
        icon.setText("videocam");
        icon.setTextColor(0xFF4CAF50);
        icon.setTextSize(20);
        icon.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(
                (int) (32 * density), (int) (32 * density));
        iconLp.setMarginEnd((int) (12 * density));
        icon.setLayoutParams(iconLp);
        row.addView(icon);

        // Text section
        LinearLayout textSection = new LinearLayout(requireContext());
        textSection.setOrientation(LinearLayout.VERTICAL);
        textSection.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        // Filename (without extension)
        TextView nameView = new TextView(requireContext());
        String displayName = item.name;
        if (displayName.endsWith(".mp4")) {
            displayName = displayName.substring(0, displayName.length() - 4);
        }
        nameView.setText(displayName);
        nameView.setTextColor(0xFFFFFFFF);
        nameView.setTextSize(13);
        nameView.setTypeface(null, android.graphics.Typeface.BOLD);
        nameView.setMaxLines(1);
        nameView.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        textSection.addView(nameView);

        // Date + size
        TextView metaView = new TextView(requireContext());
        metaView.setText(dateFormat.format(new Date(item.lastModified))
                + " · " + formatSize(item.size)
                + " · " + item.source);
        metaView.setTextColor(0xFF777777);
        metaView.setTextSize(11);
        metaView.setMaxLines(1);
        textSection.addView(metaView);

        row.addView(textSection);

        // Edit icon
        TextView editIcon = new TextView(requireContext());
        editIcon.setTypeface(iconFont);
        editIcon.setText("edit");
        editIcon.setTextColor(0xFF4CAF50);
        editIcon.setTextSize(18);
        row.addView(editIcon);

        row.setOnClickListener(v -> {
            dismiss();
            if (callback != null) callback.onRecordingSelected(item.uri);
        });

        return row;
    }

    // ── Helper views ─────────────────────────────────────────────────

    @NonNull
    private View createLoadingView(float density) {
        TextView tv = new TextView(requireContext());
        tv.setText(R.string.faditor_scanning);
        tv.setTextColor(0xFF777777);
        tv.setTextSize(13);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(0, (int) (20 * density), 0, (int) (20 * density));
        return tv;
    }

    @NonNull
    private View createEmptyState(@Nullable Typeface iconFont, float density) {
        LinearLayout layout = new LinearLayout(requireContext());
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setGravity(Gravity.CENTER);
        layout.setPadding((int) (24 * density), (int) (20 * density),
                (int) (24 * density), (int) (20 * density));

        TextView icon = new TextView(requireContext());
        icon.setTypeface(iconFont);
        icon.setText("videocam_off");
        icon.setTextColor(0xFF555555);
        icon.setTextSize(28);
        icon.setGravity(Gravity.CENTER);
        layout.addView(icon);

        TextView msg = new TextView(requireContext());
        msg.setText(R.string.faditor_no_recordings);
        msg.setTextColor(0xFF777777);
        msg.setTextSize(13);
        msg.setGravity(Gravity.CENTER);
        msg.setPadding(0, (int) (8 * density), 0, 0);
        layout.addView(msg);

        return layout;
    }

    @NonNull
    private static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(Locale.US, "%.1f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024));
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
