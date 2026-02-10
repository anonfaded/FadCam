package com.fadcam.ui;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.res.ResourcesCompat;

import com.fadcam.R;
import com.fadcam.ui.faditor.FaditorEditorActivity;
import com.fadcam.ui.faditor.FaditorInfoBottomSheet;
import com.fadcam.ui.faditor.VideoSourceBottomSheet;
import com.fadcam.ui.faditor.project.ProjectStorage;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Entry-point fragment for the Faditor Mini tab.
 *
 * <p>Shows a hero section with "Start Project" button, recent projects list
 * with video thumbnails and multi-select deletion, and feature capability
 * cards. Launches {@link FaditorEditorActivity} for full-screen editing
 * via a video source bottom sheet.</p>
 */
public class FaditorMiniFragment extends BaseFragment {

    private static final String TAG = "FaditorMiniFragment";

    /** Launcher for the system video file picker. */
    private ActivityResultLauncher<Intent> videoPickerLauncher;

    /** Launcher for the editor Activity result. */
    private ActivityResultLauncher<Intent> editorLauncher;

    private ProjectStorage projectStorage;
    private LinearLayout recentProjectsSection;
    private LinearLayout recentProjectsList;
    private View emptyStateContainer;

    // ── Selection mode ───────────────────────────────────────────────
    private boolean selectionMode = false;
    private final Set<String> selectedIds = new HashSet<>();
    private List<ProjectStorage.ProjectSummary> currentProjects = new ArrayList<>();

    private View selectionActionBar;
    private TextView tvSelectionCount;
    private CheckBox cbSelectAll;
    private View btnSelectMode;
    private View btnDeleteSelected;
    private View btnCancelSelection;

    /** Background thread for loading thumbnails. */
    private final ExecutorService thumbnailExecutor = Executors.newSingleThreadExecutor();

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        projectStorage = new ProjectStorage(requireContext());

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

        // Setup "Start Project" button → opens video source chooser
        View selectButton = view.findViewById(R.id.btn_select_video);
        if (selectButton != null) {
            selectButton.setOnClickListener(v -> showVideoSourceChooser());
        }

        // Info icon → opens Faditor info bottom sheet
        View btnInfo = view.findViewById(R.id.btn_info);
        if (btnInfo != null) {
            btnInfo.setOnClickListener(v -> {
                FaditorInfoBottomSheet.newInstance()
                        .show(getChildFragmentManager(), "faditor_info");
            });
        }

        recentProjectsSection = view.findViewById(R.id.recent_projects_section);
        recentProjectsList = view.findViewById(R.id.recent_projects_list);
        emptyStateContainer = view.findViewById(R.id.empty_state_container);

        // Selection UI
        selectionActionBar = view.findViewById(R.id.selection_action_bar);
        tvSelectionCount = view.findViewById(R.id.tv_selection_count);
        cbSelectAll = view.findViewById(R.id.cb_select_all);
        btnSelectMode = view.findViewById(R.id.btn_select_mode);
        btnDeleteSelected = view.findViewById(R.id.btn_delete_selected);
        btnCancelSelection = view.findViewById(R.id.btn_cancel_selection);

        // Toggle selection mode
        if (btnSelectMode != null) {
            btnSelectMode.setOnClickListener(v -> toggleSelectionMode());
        }
        if (btnCancelSelection != null) {
            btnCancelSelection.setOnClickListener(v -> exitSelectionMode());
        }
        if (btnDeleteSelected != null) {
            btnDeleteSelected.setOnClickListener(v -> confirmDeleteSelected());
        }
        if (cbSelectAll != null) {
            cbSelectAll.setOnCheckedChangeListener((btn, checked) -> {
                if (!btn.isPressed()) return; // Only respond to user clicks
                if (checked) {
                    for (ProjectStorage.ProjectSummary p : currentProjects) {
                        selectedIds.add(p.id);
                    }
                } else {
                    selectedIds.clear();
                }
                updateSelectionUI();
                refreshProjectRowCheckboxes();
            });
        }

        return view;
    }

    @Override
    public void onResume() {
        super.onResume();
        refreshRecentProjects();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        thumbnailExecutor.shutdownNow();
    }

    // ── Selection Mode ───────────────────────────────────────────────

    private void toggleSelectionMode() {
        if (selectionMode) {
            exitSelectionMode();
        } else {
            enterSelectionMode();
        }
    }

    private void enterSelectionMode() {
        selectionMode = true;
        selectedIds.clear();
        if (selectionActionBar != null) selectionActionBar.setVisibility(View.VISIBLE);
        updateSelectionUI();
        refreshRecentProjects();
    }

    private void exitSelectionMode() {
        selectionMode = false;
        selectedIds.clear();
        if (selectionActionBar != null) selectionActionBar.setVisibility(View.GONE);
        if (cbSelectAll != null) cbSelectAll.setChecked(false);
        refreshRecentProjects();
    }

    private void updateSelectionUI() {
        int count = selectedIds.size();
        if (tvSelectionCount != null) {
            if (count == 0) {
                tvSelectionCount.setText(R.string.faditor_select_items);
            } else {
                tvSelectionCount.setText(getString(R.string.faditor_selected_count, count));
            }
        }
        if (btnDeleteSelected != null) {
            btnDeleteSelected.setAlpha(count > 0 ? 1.0f : 0.3f);
            btnDeleteSelected.setEnabled(count > 0);
        }
        // Update select-all checkbox without triggering listener
        if (cbSelectAll != null && !currentProjects.isEmpty()) {
            boolean allSelected = selectedIds.size() == currentProjects.size();
            if (cbSelectAll.isChecked() != allSelected) {
                cbSelectAll.setOnCheckedChangeListener(null);
                cbSelectAll.setChecked(allSelected);
                cbSelectAll.setOnCheckedChangeListener((btn, checked) -> {
                    if (!btn.isPressed()) return;
                    if (checked) {
                        for (ProjectStorage.ProjectSummary p : currentProjects) {
                            selectedIds.add(p.id);
                        }
                    } else {
                        selectedIds.clear();
                    }
                    updateSelectionUI();
                    refreshProjectRowCheckboxes();
                });
            }
        }
    }

    private void refreshProjectRowCheckboxes() {
        if (recentProjectsList == null) return;
        for (int i = 0; i < recentProjectsList.getChildCount(); i++) {
            View child = recentProjectsList.getChildAt(i);
            CheckBox cb = child.findViewWithTag("checkbox");
            String projectId = (String) child.getTag();
            if (cb != null && projectId != null) {
                cb.setChecked(selectedIds.contains(projectId));
            }
        }
    }

    private void confirmDeleteSelected() {
        int count = selectedIds.size();
        if (count == 0) return;

        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.faditor_delete_projects_title)
                .setMessage(getString(R.string.faditor_delete_projects_message, count))
                .setPositiveButton(R.string.faditor_delete_confirm, (dialog, which) -> {
                    for (String id : selectedIds) {
                        projectStorage.delete(id);
                    }
                    exitSelectionMode();
                    refreshRecentProjects();
                })
                .setNegativeButton(R.string.faditor_cancel, null)
                .show();
    }

    // ── Recent Projects ──────────────────────────────────────────────

    /**
     * Populate the recent projects list from saved projects.
     */
    private void refreshRecentProjects() {
        if (recentProjectsList == null || recentProjectsSection == null) return;

        currentProjects = projectStorage.listProjects();
        recentProjectsList.removeAllViews();

        if (currentProjects.isEmpty()) {
            recentProjectsSection.setVisibility(View.GONE);
            if (emptyStateContainer != null) emptyStateContainer.setVisibility(View.VISIBLE);
            return;
        }

        recentProjectsSection.setVisibility(View.VISIBLE);
        if (emptyStateContainer != null) emptyStateContainer.setVisibility(View.GONE);

        // Update project count badge in the section header
        TextView countBadge = recentProjectsSection.findViewById(R.id.project_count_badge);
        if (countBadge != null) {
            countBadge.setText(String.valueOf(currentProjects.size()));
            countBadge.setVisibility(View.VISIBLE);
        }

        // Show all projects
        Typeface materialIcons = ResourcesCompat.getFont(requireContext(), R.font.materialicons);
        SimpleDateFormat dateFormat = new SimpleDateFormat("MMM d, h:mm a", Locale.getDefault());

        for (int i = 0; i < currentProjects.size(); i++) {
            ProjectStorage.ProjectSummary summary = currentProjects.get(i);
            View row = createProjectRow(summary, materialIcons, dateFormat);
            recentProjectsList.addView(row);
        }
    }

    /**
     * Create a single project row view with thumbnail, extracted filename, and optional checkbox.
     */
    @NonNull
    private View createProjectRow(@NonNull ProjectStorage.ProjectSummary summary,
                                  @Nullable Typeface iconFont,
                                  @NonNull SimpleDateFormat dateFormat) {
        float dp = getResources().getDisplayMetrics().density;

        // Outer row container
        LinearLayout row = new LinearLayout(requireContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int hPad = (int) (14 * dp);
        int vPad = (int) (10 * dp);
        row.setPadding(hPad, vPad, hPad, vPad);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        rowLp.bottomMargin = (int) (6 * dp);
        row.setLayoutParams(rowLp);
        row.setBackgroundResource(R.drawable.settings_home_row_bg);
        row.setTag(summary.id);

        // ── Checkbox (only in selection mode) ────────────────────
        CheckBox cb = new CheckBox(requireContext());
        cb.setTag("checkbox");
        cb.setButtonTintList(android.content.res.ColorStateList.valueOf(0xFF4CAF50));
        cb.setMinWidth(0);
        cb.setMinHeight(0);
        cb.setPadding(0, 0, 0, 0);
        LinearLayout.LayoutParams cbLp = new LinearLayout.LayoutParams(
                (int) (36 * dp), (int) (36 * dp));
        cbLp.setMarginEnd((int) (8 * dp));
        cb.setLayoutParams(cbLp);
        cb.setChecked(selectedIds.contains(summary.id));
        cb.setVisibility(selectionMode ? View.VISIBLE : View.GONE);
        cb.setOnCheckedChangeListener((btn, checked) -> {
            if (checked) {
                selectedIds.add(summary.id);
            } else {
                selectedIds.remove(summary.id);
            }
            updateSelectionUI();
        });
        row.addView(cb);

        // ── Thumbnail ────────────────────────────────────────────
        int thumbSize = (int) (52 * dp);
        ImageView thumbnail = new ImageView(requireContext());
        thumbnail.setScaleType(ImageView.ScaleType.CENTER_CROP);
        thumbnail.setBackgroundResource(R.drawable.settings_group_card_bg);
        thumbnail.setClipToOutline(true);
        LinearLayout.LayoutParams thumbLp = new LinearLayout.LayoutParams(thumbSize, thumbSize);
        thumbLp.setMarginEnd((int) (12 * dp));
        thumbnail.setLayoutParams(thumbLp);

        // Set fallback icon first, then load real thumbnail async
        thumbnail.setImageResource(android.R.color.transparent);
        thumbnail.setBackgroundResource(R.drawable.settings_group_card_bg);

        // Load thumbnail on background thread
        if (summary.videoUri != null) {
            loadThumbnailAsync(thumbnail, summary.videoUri);
        }
        row.addView(thumbnail);

        // ── Text section ─────────────────────────────────────────
        LinearLayout textSection = new LinearLayout(requireContext());
        textSection.setOrientation(LinearLayout.VERTICAL);
        textSection.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        // Display name: extract filename from video URI
        String displayName = extractDisplayName(summary.videoUri);
        TextView name = new TextView(requireContext());
        name.setText(displayName);
        name.setTextColor(0xFFFFFFFF);
        name.setTextSize(14);
        name.setTypeface(null, Typeface.BOLD);
        name.setMaxLines(1);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        textSection.addView(name);

        // Last modified date
        TextView date = new TextView(requireContext());
        date.setText(dateFormat.format(new Date(summary.lastModified)));
        date.setTextColor(0xFF777777);
        date.setTextSize(11);
        date.setMaxLines(1);
        LinearLayout.LayoutParams dateLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        dateLp.topMargin = (int) (2 * dp);
        date.setLayoutParams(dateLp);
        textSection.addView(date);

        row.addView(textSection);

        // ── Arrow icon ───────────────────────────────────────────
        if (!selectionMode) {
            TextView arrow = new TextView(requireContext());
            arrow.setTypeface(iconFont);
            arrow.setText("chevron_right");
            arrow.setTextColor(0xFF444444);
            arrow.setTextSize(18);
            arrow.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams arrowLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            arrowLp.setMarginStart((int) (4 * dp));
            arrow.setLayoutParams(arrowLp);
            row.addView(arrow);
        }

        // Click behaviour
        row.setClickable(true);
        row.setFocusable(true);

        if (selectionMode) {
            // In selection mode, tap toggles checkbox
            row.setOnClickListener(v -> {
                cb.setChecked(!cb.isChecked());
            });
        } else {
            // Normal mode: tap opens editor, long-press enters selection
            row.setOnClickListener(v -> {
                if (summary.videoUri != null) {
                    launchEditor(Uri.parse(summary.videoUri), summary.id);
                }
            });
            row.setOnLongClickListener(v -> {
                enterSelectionMode();
                selectedIds.add(summary.id);
                updateSelectionUI();
                refreshRecentProjects();
                return true;
            });
        }

        return row;
    }

    // ── Helpers ──────────────────────────────────────────────────────

    /**
     * Extract a human-readable display name from a video URI.
     * Falls back to "Untitled" if extraction fails.
     */
    @NonNull
    private String extractDisplayName(@Nullable String uriString) {
        if (uriString == null) return "Untitled";

        try {
            Uri uri = Uri.parse(uriString);

            // Try ContentResolver query for content:// URIs
            if ("content".equals(uri.getScheme())) {
                ContentResolver cr = requireContext().getContentResolver();
                try (Cursor cursor = cr.query(uri, new String[]{OpenableColumns.DISPLAY_NAME},
                        null, null, null)) {
                    if (cursor != null && cursor.moveToFirst()) {
                        int idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                        if (idx >= 0) {
                            String name = cursor.getString(idx);
                            if (name != null && !name.isEmpty()) {
                                return stripExtension(name);
                            }
                        }
                    }
                } catch (Exception e) {
                    Log.w(TAG, "ContentResolver query failed for display name", e);
                }
            }

            // Try extracting from path
            String path = uri.getPath();
            if (path != null) {
                // Handle SAF-style paths like /tree/primary:Android/data/.../FadCam_xxx.mp4
                if (path.contains(":")) {
                    String afterColon = path.substring(path.lastIndexOf(':') + 1);
                    if (afterColon.contains("/")) {
                        afterColon = afterColon.substring(afterColon.lastIndexOf('/') + 1);
                    }
                    if (!afterColon.isEmpty()) {
                        return stripExtension(afterColon);
                    }
                }
                // Simple file path
                String lastSegment = uri.getLastPathSegment();
                if (lastSegment != null && !lastSegment.isEmpty()) {
                    return stripExtension(lastSegment);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to extract display name from: " + uriString, e);
        }

        return "Untitled";
    }

    /**
     * Remove .mp4 / .MP4 extension from a filename.
     */
    @NonNull
    private static String stripExtension(@NonNull String filename) {
        int dot = filename.lastIndexOf('.');
        return dot > 0 ? filename.substring(0, dot) : filename;
    }

    /**
     * Load a video thumbnail asynchronously and set it on the ImageView.
     */
    private void loadThumbnailAsync(@NonNull ImageView imageView, @NonNull String uriString) {
        thumbnailExecutor.execute(() -> {
            Bitmap bmp = null;
            MediaMetadataRetriever retriever = null;
            try {
                retriever = new MediaMetadataRetriever();
                Uri uri = Uri.parse(uriString);

                if ("file".equals(uri.getScheme()) && uri.getPath() != null) {
                    retriever.setDataSource(uri.getPath());
                } else {
                    // Try reconstructed file path first (more reliable for fMP4)
                    String path = uri.getPath();
                    boolean set = false;
                    if (path != null && path.contains(":")) {
                        int lastColon = path.lastIndexOf(':');
                        if (lastColon >= 0 && lastColon < path.length() - 1) {
                            String rel = path.substring(lastColon + 1);
                            File f = new File("/storage/emulated/0/" + rel);
                            if (f.exists() && f.canRead()) {
                                retriever.setDataSource(f.getAbsolutePath());
                                set = true;
                            }
                        }
                    }
                    if (!set) {
                        retriever.setDataSource(requireContext(), uri);
                    }
                }
                bmp = retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            } catch (Exception e) {
                Log.w(TAG, "Thumbnail extraction failed", e);
            } finally {
                if (retriever != null) {
                    try { retriever.release(); } catch (Exception ignored) {}
                }
            }

            final Bitmap finalBmp = bmp;
            if (finalBmp != null && isAdded()) {
                imageView.post(() -> imageView.setImageBitmap(finalBmp));
            }
        });
    }

    // ── Video Source Chooser ────────────────────────────────────────

    /**
     * Show the video source bottom sheet, offering "Browse Device" and
     * "FadCam Recordings" options.
     */
    private void showVideoSourceChooser() {
        VideoSourceBottomSheet sheet = new VideoSourceBottomSheet();
        sheet.setCallback(new VideoSourceBottomSheet.Callback() {
            @Override
            public void onBrowseDevice() {
                openVideoPicker();
            }

            @Override
            public void onRecordingSelected(@NonNull Uri videoUri) {
                launchEditor(videoUri);
            }
        });
        sheet.show(getChildFragmentManager(), "video_source");
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
     * Launch the full-screen editor with the selected video (new project).
     */
    private void launchEditor(@NonNull Uri videoUri) {
        launchEditor(videoUri, null);
    }

    /**
     * Launch the full-screen editor, optionally loading a saved project.
     *
     * @param videoUri  the video URI for playback
     * @param projectId the saved project ID to restore, or null for a new project
     */
    private void launchEditor(@NonNull Uri videoUri, @Nullable String projectId) {
        Intent intent = new Intent(requireContext(), FaditorEditorActivity.class);
        intent.setData(videoUri);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        if (projectId != null) {
            intent.putExtra(FaditorEditorActivity.EXTRA_PROJECT_ID, projectId);
        }
        editorLauncher.launch(intent);
    }
}
