package com.fadcam.ui;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.res.ResourcesCompat;
import androidx.documentfile.provider.DocumentFile;

import com.fadcam.Constants;
import com.fadcam.R;
import com.fadcam.SharedPreferencesManager;
import com.fadcam.ui.faditor.FaditorEditorActivity;
import com.fadcam.ui.faditor.project.ProjectStorage;

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
 * Entry-point fragment for the Faditor Mini tab.
 *
 * <p>Shows a hero section with "New Project" button, recent projects list,
 * and feature capability cards. Launches {@link FaditorEditorActivity}
 * for full-screen editing.</p>
 */
public class FaditorMiniFragment extends BaseFragment {

    private static final String TAG = "FaditorMiniFragment";

    /** Launcher for the system video file picker. */
    private ActivityResultLauncher<Intent> videoPickerLauncher;

    /** Launcher for the editor Activity result. */
    private ActivityResultLauncher<Intent> editorLauncher;

    private ProjectStorage projectStorage;
    private SharedPreferencesManager prefsManager;
    private LinearLayout recentProjectsSection;
    private LinearLayout recentProjectsList;
    private LinearLayout recordingsSection;
    private LinearLayout recordingsList;
    private View recordingsEmptyState;

    /** Background thread for scanning video directories. */
    private final ExecutorService scanExecutor = Executors.newSingleThreadExecutor();

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        projectStorage = new ProjectStorage(requireContext());
        prefsManager = SharedPreferencesManager.getInstance(requireContext());

        // Register video picker result handler
        videoPickerLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == Activity.RESULT_OK
                            && result.getData() != null
                            && result.getData().getData() != null) {
                        Uri videoUri = result.getData().getData();
                        // Take persistable read permission so the editor can access the URI
                        try {
                            requireContext().getContentResolver().takePersistableUriPermission(
                                    videoUri,
                                    Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        } catch (SecurityException e) {
                            Log.w(TAG, "Could not take persistable URI permission", e);
                        }
                        launchEditor(videoUri);
                    }
                }
        );

        // Register editor result handler
        editorLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == Activity.RESULT_OK) {
                        Log.d(TAG, "Editor returned successfully");
                    }
                    // Refresh recent projects list when returning
                    refreshRecentProjects();
                }
        );
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_faditor_mini, container, false);

        // Setup "New Project" button
        View selectButton = view.findViewById(R.id.btn_select_video);
        if (selectButton != null) {
            selectButton.setOnClickListener(v -> openVideoPicker());
        }

        recentProjectsSection = view.findViewById(R.id.recent_projects_section);
        recentProjectsList = view.findViewById(R.id.recent_projects_list);
        recordingsSection = view.findViewById(R.id.fadcam_recordings_section);
        recordingsList = view.findViewById(R.id.recordings_list);
        recordingsEmptyState = view.findViewById(R.id.recordings_empty_state);

        return view;
    }

    @Override
    public void onResume() {
        super.onResume();
        refreshRecentProjects();
        loadFadCamRecordings();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        // Don't shutdown the executor — it can be reused
    }

    /**
     * Populate the recent projects list from saved projects.
     */
    private void refreshRecentProjects() {
        if (recentProjectsList == null || recentProjectsSection == null) return;

        List<ProjectStorage.ProjectSummary> projects = projectStorage.listProjects();
        recentProjectsList.removeAllViews();

        if (projects.isEmpty()) {
            recentProjectsSection.setVisibility(View.GONE);
            return;
        }

        recentProjectsSection.setVisibility(View.VISIBLE);

        // Show up to 5 most recent projects
        int limit = Math.min(projects.size(), 5);
        Typeface materialIcons = ResourcesCompat.getFont(requireContext(), R.font.materialicons);
        SimpleDateFormat dateFormat = new SimpleDateFormat("MMM d, h:mm a", Locale.getDefault());

        for (int i = 0; i < limit; i++) {
            ProjectStorage.ProjectSummary summary = projects.get(i);
            View row = createProjectRow(summary, materialIcons, dateFormat);
            recentProjectsList.addView(row);
        }
    }

    /**
     * Create a single project row view programmatically.
     */
    @NonNull
    private View createProjectRow(@NonNull ProjectStorage.ProjectSummary summary,
                                  @Nullable Typeface iconFont,
                                  @NonNull SimpleDateFormat dateFormat) {
        float density = getResources().getDisplayMetrics().density;

        // Outer row container
        LinearLayout row = new LinearLayout(requireContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int hPad = (int) (14 * density);
        int vPad = (int) (12 * density);
        row.setPadding(hPad, vPad, hPad, vPad);
        row.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        // Movie icon
        TextView icon = new TextView(requireContext());
        icon.setTypeface(iconFont);
        icon.setText("movie");
        icon.setTextColor(0xFF4CAF50);
        icon.setTextSize(22);
        icon.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(
                (int) (36 * density), (int) (36 * density));
        iconLp.setMarginEnd((int) (12 * density));
        icon.setLayoutParams(iconLp);
        row.addView(icon);

        // Text section
        LinearLayout textSection = new LinearLayout(requireContext());
        textSection.setOrientation(LinearLayout.VERTICAL);
        textSection.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        // Project name
        TextView name = new TextView(requireContext());
        name.setText(summary.name);
        name.setTextColor(0xFFFFFFFF);
        name.setTextSize(14);
        name.setTypeface(null, Typeface.BOLD);
        name.setMaxLines(1);
        textSection.addView(name);

        // Last modified
        TextView date = new TextView(requireContext());
        date.setText(dateFormat.format(new Date(summary.lastModified)));
        date.setTextColor(0xFF888888);
        date.setTextSize(12);
        date.setMaxLines(1);
        textSection.addView(date);

        row.addView(textSection);

        // Arrow icon
        TextView arrow = new TextView(requireContext());
        arrow.setTypeface(iconFont);
        arrow.setText("chevron_right");
        arrow.setTextColor(0xFF555555);
        arrow.setTextSize(20);
        arrow.setGravity(Gravity.CENTER);
        row.addView(arrow);

        // Click → open project's video in editor
        row.setOnClickListener(v -> {
            if (summary.videoUri != null) {
                launchEditor(Uri.parse(summary.videoUri));
            }
        });

        // Set clickable appearance
        row.setClickable(true);
        row.setFocusable(true);

        return row;
    }

    // ── FadCam Recordings ────────────────────────────────────────────

    /**
     * Scan FadCam recording directories on a background thread
     * and display videos in the "Your Recordings" section.
     */
    private void loadFadCamRecordings() {
        if (recordingsList == null || recordingsSection == null) return;

        scanExecutor.execute(() -> {
            List<RecordingItem> recordings = scanFadCamVideos();

            if (!isAdded()) return;
            requireActivity().runOnUiThread(() -> populateRecordingsList(recordings));
        });
    }

    /**
     * Scan both internal and SAF storage for FadCam/FadRec videos.
     *
     * @return sorted list of recording items (newest first)
     */
    @NonNull
    private List<RecordingItem> scanFadCamVideos() {
        List<RecordingItem> items = new ArrayList<>();

        // Check if custom SAF storage is being used
        String safUriString = prefsManager.getCustomStorageUri();
        if (safUriString != null) {
            try {
                Uri treeUri = Uri.parse(safUriString);
                if (hasSafPermission(treeUri)) {
                    items.addAll(scanSafDirectory(treeUri));
                }
            } catch (Exception e) {
                Log.e(TAG, "Error scanning SAF storage", e);
            }
        }

        // Scan internal storage (works alongside or as fallback)
        try {
            File externalDir = requireContext().getExternalFilesDir(null);
            if (externalDir != null) {
                items.addAll(scanInternalDirectory(
                        new File(externalDir, Constants.RECORDING_DIRECTORY),
                        getString(R.string.faditor_fadcam_source)));
                items.addAll(scanInternalDirectory(
                        new File(externalDir, Constants.RECORDING_DIRECTORY_FADREC),
                        getString(R.string.faditor_fadrec_source)));
            }
        } catch (Exception e) {
            Log.e(TAG, "Error scanning internal storage", e);
        }

        // Sort newest first
        Collections.sort(items, (a, b) -> Long.compare(b.lastModified, a.lastModified));
        return items;
    }

    /**
     * Scan an internal file directory for .mp4 video files.
     */
    @NonNull
    private List<RecordingItem> scanInternalDirectory(@NonNull File dir, @NonNull String source) {
        List<RecordingItem> items = new ArrayList<>();
        if (!dir.exists() || !dir.isDirectory()) return items;

        File[] files = dir.listFiles();
        if (files == null) return items;

        for (File file : files) {
            if (file.isFile()
                    && file.getName().endsWith("." + Constants.RECORDING_FILE_EXTENSION)
                    && !file.getName().startsWith("temp_")) {
                items.add(new RecordingItem(
                        Uri.fromFile(file),
                        file.getName(),
                        file.length(),
                        file.lastModified(),
                        source));
            }
        }
        return items;
    }

    /**
     * Scan a SAF tree directory for video files.
     */
    @NonNull
    private List<RecordingItem> scanSafDirectory(@NonNull Uri treeUri) {
        List<RecordingItem> items = new ArrayList<>();
        if (!isAdded()) return items;

        try {
            DocumentFile dir = DocumentFile.fromTreeUri(requireContext(), treeUri);
            if (dir == null || !dir.isDirectory() || !dir.canRead()) return items;

            DocumentFile[] files = dir.listFiles();
            for (DocumentFile docFile : files) {
                if (docFile == null || !docFile.isFile()) continue;
                String name = docFile.getName();
                String mime = docFile.getType();
                if (name != null && mime != null && mime.startsWith("video/")
                        && name.endsWith(Constants.RECORDING_FILE_EXTENSION)
                        && !name.startsWith("temp_")) {
                    items.add(new RecordingItem(
                            docFile.getUri(),
                            name,
                            docFile.length(),
                            docFile.lastModified(),
                            getString(R.string.faditor_fadcam_source)));
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error listing SAF files", e);
        }
        return items;
    }

    /**
     * Check if we have persistent SAF read permission for the given URI.
     */
    private boolean hasSafPermission(@NonNull Uri treeUri) {
        if (!isAdded()) return false;
        try {
            for (android.content.UriPermission perm :
                    requireContext().getContentResolver().getPersistedUriPermissions()) {
                if (perm.getUri().equals(treeUri) && perm.isReadPermission()) {
                    return true;
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error checking SAF permission", e);
        }
        return false;
    }

    /**
     * Populate the recordings list UI on the main thread.
     */
    private void populateRecordingsList(@NonNull List<RecordingItem> recordings) {
        if (recordingsList == null || recordingsEmptyState == null) return;

        recordingsList.removeAllViews();

        if (recordings.isEmpty()) {
            recordingsEmptyState.setVisibility(View.VISIBLE);
            recordingsList.setVisibility(View.GONE);
            return;
        }

        recordingsEmptyState.setVisibility(View.GONE);
        recordingsList.setVisibility(View.VISIBLE);

        Typeface materialIcons = ResourcesCompat.getFont(requireContext(), R.font.materialicons);
        SimpleDateFormat dateFormat = new SimpleDateFormat("MMM d, h:mm a", Locale.getDefault());

        // Show up to 10 most recent recordings
        int limit = Math.min(recordings.size(), 10);
        for (int i = 0; i < limit; i++) {
            RecordingItem item = recordings.get(i);
            View row = createRecordingRow(item, materialIcons, dateFormat);
            recordingsList.addView(row);
        }
    }

    /**
     * Create a single recording row view.
     */
    @NonNull
    private View createRecordingRow(@NonNull RecordingItem item,
                                    @Nullable Typeface iconFont,
                                    @NonNull SimpleDateFormat dateFormat) {
        float density = getResources().getDisplayMetrics().density;

        // ── Outer row ────────────────────────────────────────────
        LinearLayout row = new LinearLayout(requireContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int hPad = (int) (14 * density);
        int vPad = (int) (12 * density);
        row.setPadding(hPad, vPad, hPad, vPad);
        row.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        row.setBackgroundResource(R.drawable.settings_home_row_bg);

        // ── Video icon ───────────────────────────────────────────
        TextView icon = new TextView(requireContext());
        icon.setTypeface(iconFont);
        icon.setText("videocam");
        icon.setTextColor(0xFF4CAF50);
        icon.setTextSize(22);
        icon.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(
                (int) (36 * density), (int) (36 * density));
        iconLp.setMarginEnd((int) (12 * density));
        icon.setLayoutParams(iconLp);
        row.addView(icon);

        // ── Text section (name + date + size) ────────────────────
        LinearLayout textSection = new LinearLayout(requireContext());
        textSection.setOrientation(LinearLayout.VERTICAL);
        textSection.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        // File name (strip extension for cleaner display)
        TextView nameView = new TextView(requireContext());
        String displayName = item.name;
        if (displayName.endsWith(".mp4")) {
            displayName = displayName.substring(0, displayName.length() - 4);
        }
        nameView.setText(displayName);
        nameView.setTextColor(0xFFFFFFFF);
        nameView.setTextSize(14);
        nameView.setTypeface(null, Typeface.BOLD);
        nameView.setMaxLines(1);
        nameView.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        textSection.addView(nameView);

        // Date + size line
        TextView metaView = new TextView(requireContext());
        String dateStr = dateFormat.format(new Date(item.lastModified));
        String sizeStr = formatFileSize(item.size);
        metaView.setText(dateStr + " · " + sizeStr + " · " + item.source);
        metaView.setTextColor(0xFF888888);
        metaView.setTextSize(12);
        metaView.setMaxLines(1);
        textSection.addView(metaView);

        row.addView(textSection);

        // ── Arrow icon ───────────────────────────────────────────
        TextView arrow = new TextView(requireContext());
        arrow.setTypeface(iconFont);
        arrow.setText("edit");
        arrow.setTextColor(0xFF555555);
        arrow.setTextSize(18);
        arrow.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams arrowLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        arrowLp.setMarginStart((int) (8 * density));
        arrow.setLayoutParams(arrowLp);
        row.addView(arrow);

        // ── Click → launch editor ────────────────────────────────
        row.setOnClickListener(v -> launchEditor(item.uri));
        row.setClickable(true);
        row.setFocusable(true);

        return row;
    }

    /**
     * Format file size into a human-readable string.
     */
    @NonNull
    private static String formatFileSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(Locale.US, "%.1f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024));
        return String.format(Locale.US, "%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }

    /**
     * Lightweight model for a FadCam recording file discovered on disk.
     */
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

    /**
     * Open the system file picker to select a video file.
     */
    private void openVideoPicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("video/*");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);

        try {
            videoPickerLauncher.launch(intent);
        } catch (Exception e) {
            Log.e(TAG, "Failed to open video picker", e);
            Toast.makeText(requireContext(),
                    R.string.faditor_error_picker, Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Launch the full-screen editor with the selected video.
     */
    private void launchEditor(@NonNull Uri videoUri) {
        Intent intent = new Intent(requireContext(), FaditorEditorActivity.class);
        intent.setData(videoUri);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        editorLauncher.launch(intent);
    }
}
