package com.servalabs.cam.ui;

import com.servalabs.cam.Log;
import com.servalabs.cam.FLog;
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

import com.servalabs.cam.R;
import com.servalabs.cam.ui.faditor.FaditorEditorActivity;
import com.servalabs.cam.ui.faditor.FaditorInfoBottomSheet;
import com.servalabs.cam.ui.faditor.VideoSourceBottomSheet;
import com.servalabs.cam.ui.faditor.model.AudioClip;
import com.servalabs.cam.ui.faditor.model.Clip;
import com.servalabs.cam.ui.faditor.model.FaditorProject;
import com.servalabs.cam.ui.faditor.model.Timeline;
import com.servalabs.cam.ui.faditor.project.ProjectStorage;
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

    // ── Export dock ──────────────────────────────────────────────────
    private View exportDock;
    private ImageView exportDockIcon;
    private com.servalabs.cam.ui.utils.AnimatedTextView exportDockTitle;
    private com.google.android.material.progressindicator.LinearProgressIndicator exportDockProgress;
    private TextView exportDockCurrent;
    private TextView exportDockEta;
    private TextView exportDockSummary;
    private com.servalabs.cam.ui.utils.AnimatedTextView exportDockOk;
    private long exportStartTimeMs = 0;
    private float lastReportedProgress = 0f;
    
    // ── Export progress broadcast receiver ────────────────────────────
    private android.content.BroadcastReceiver exportProgressReceiver;
    private boolean isExportReceiverRegistered = false;
    private boolean isExportActive = false; // Track if export is currently running

    /** Background thread for loading thumbnails. */
    private final ExecutorService thumbnailExecutor = Executors.newSingleThreadExecutor();

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        FLog.d("FaditorMiniFragment", "✅ onCreate called!");
        projectStorage = new ProjectStorage(requireContext());

        // Initialize the broadcast receiver early
        initializeExportProgressReceiver();
        
        // Register receiver immediately in onCreate so it persists across fragment lifecycle
        if (exportProgressReceiver != null && !isExportReceiverRegistered) {
            try {
                FLog.d("FaditorMiniFragment", "Registering receiver in onCreate");
                android.content.IntentFilter filter = new android.content.IntentFilter();
                filter.addAction(com.servalabs.cam.ui.faditor.export.ExportService.ACTION_EXPORT_STARTED);
                filter.addAction(com.servalabs.cam.ui.faditor.export.ExportService.ACTION_EXPORT_PROGRESS);
                filter.addAction(com.servalabs.cam.ui.faditor.export.ExportService.ACTION_EXPORT_COMPLETED);
                filter.addAction(com.servalabs.cam.ui.faditor.export.ExportService.ACTION_EXPORT_ERROR);
                filter.addAction(com.servalabs.cam.ui.faditor.export.ExportService.ACTION_EXPORT_CANCELLED);
                androidx.localbroadcastmanager.content.LocalBroadcastManager.getInstance(requireContext())
                    .registerReceiver(exportProgressReceiver, filter);
                isExportReceiverRegistered = true;
                FLog.d("FaditorMiniFragment", "✅ Export receiver registered in onCreate");
            } catch (Exception e) {
                FLog.e("FaditorMiniFragment", "Error registering export receiver in onCreate: " + e.getMessage(), e);
            }
        }

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
                            FLog.w(TAG, "Could not take persistable URI permission", e);
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
                        FLog.d(TAG, "Editor returned successfully");
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
        FLog.d("FaditorMiniFragment", "✅ onCreateView called!");
        View view = inflater.inflate(R.layout.fragment_faditor_mini, container, false);

        // Request focus on first focusable element for D-pad/TV remote support.
        view.post(() -> {
            View first = com.servalabs.cam.ui.utils.DpadSettingsFocusHelper.findFirstFocusable(view);
            if (first != null) first.requestFocus();
        });

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

        // ── Export dock initialization ────────────────────────────────
        exportDock = view.findViewById(R.id.faditor_export_dock);
        exportDockIcon = view.findViewById(R.id.faditor_export_dock_icon);
        exportDockTitle = view.findViewById(R.id.faditor_export_dock_title);
        exportDockProgress = view.findViewById(R.id.faditor_export_dock_progress);
        exportDockCurrent = view.findViewById(R.id.faditor_export_dock_current);
        exportDockEta = view.findViewById(R.id.faditor_export_dock_eta);
        exportDockSummary = view.findViewById(R.id.faditor_export_dock_summary);
        exportDockOk = view.findViewById(R.id.faditor_export_dock_ok);

        // OK/Cancel button: Cancel during export, dismiss after completion
        if (exportDockOk != null) {
            exportDockOk.setOnClickListener(v -> {
                if (isExportActive) {
                    // Send cancel intent to ExportService
                    Intent cancelIntent = new Intent(requireContext(), 
                        com.servalabs.cam.ui.faditor.export.ExportService.class);
                    cancelIntent.setAction(com.servalabs.cam.ui.faditor.export.ExportService.ACTION_CANCEL_EXPORT);
                    requireContext().startService(cancelIntent);
                    FLog.d(TAG, "Sent cancel intent to ExportService");
                } else {
                    // After completion/error/cancel, dismiss the dock
                    hideDockWithAnimation();
                }
            });
        }

        return view;
    }

    /**
     * Initialize the broadcast receiver for export progress updates.
     * Called from onCreateView to set up the receiver instance.
     */
    private void initializeExportProgressReceiver() {
        if (exportProgressReceiver != null) return; // Already initialized
        
        FLog.d("FaditorMiniFragment", "Initializing export progress broadcast receiver...");

        exportProgressReceiver = new android.content.BroadcastReceiver() {
            @Override
            public void onReceive(android.content.Context context, android.content.Intent intent) {
                if (!isAdded()) {
                    FLog.d("FaditorMiniFragment", "Received broadcast but fragment not added, ignoring");
                    return; // Fragment detached
                }
                
                String action = intent.getAction();
                if (action == null) {
                    FLog.d("FaditorMiniFragment", "Received broadcast with null action");
                    return;
                }
                
                FLog.d("FaditorMiniFragment", "Export broadcast received: action=" + action);

                if (com.servalabs.cam.ui.faditor.export.ExportService.ACTION_EXPORT_STARTED.equals(action)) {
                    String outputPath = intent.getStringExtra(
                        com.servalabs.cam.ui.faditor.export.ExportService.EXTRA_OUTPUT_PATH);
                    FLog.d("FaditorMiniFragment", "Export started: " + outputPath);
                    // Post to main thread
                    new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> 
                        onExportStarted(outputPath));

                } else if (com.servalabs.cam.ui.faditor.export.ExportService.ACTION_EXPORT_PROGRESS.equals(action)) {
                    float progress = intent.getFloatExtra(
                        com.servalabs.cam.ui.faditor.export.ExportService.EXTRA_PROGRESS, 0f);
                    FLog.d("FaditorMiniFragment", "Export progress: " + progress);
                    // Post to main thread
                    new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> 
                        onExportProgress(progress));

                } else if (com.servalabs.cam.ui.faditor.export.ExportService.ACTION_EXPORT_COMPLETED.equals(action)) {
                    String outputPath = intent.getStringExtra(
                        com.servalabs.cam.ui.faditor.export.ExportService.EXTRA_OUTPUT_PATH);
                    FLog.d("FaditorMiniFragment", "Export completed: " + outputPath);
                    // Post to main thread
                    new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> 
                        onExportCompleted(outputPath));

                } else if (com.servalabs.cam.ui.faditor.export.ExportService.ACTION_EXPORT_ERROR.equals(action)) {
                    String errorMsg = intent.getStringExtra(
                        com.servalabs.cam.ui.faditor.export.ExportService.EXTRA_ERROR_MESSAGE);
                    FLog.d("FaditorMiniFragment", "Export error: " + errorMsg);
                    // Post to main thread
                    new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> 
                        onExportError(errorMsg != null ? errorMsg : "Unknown error"));

                } else if (com.servalabs.cam.ui.faditor.export.ExportService.ACTION_EXPORT_CANCELLED.equals(action)) {
                    FLog.d("FaditorMiniFragment", "Export cancelled");
                    // Post to main thread
                    new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> 
                        onExportCancelled());
                }
            }
        };
        FLog.d("FaditorMiniFragment", "Broadcast receiver initialized successfully");
    }

    @Override
    public void onResume() {
        super.onResume();
        FLog.d("FaditorMiniFragment", "onResume called");
        refreshRecentProjects();
    }

    @Override
    public void onPause() {
        super.onPause();
        FLog.d("FaditorMiniFragment", "onPause called");
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        
        // Ensure broadcast receiver is unregistered
        if (exportProgressReceiver != null && isExportReceiverRegistered) {
            try {
                androidx.localbroadcastmanager.content.LocalBroadcastManager.getInstance(requireContext())
                    .unregisterReceiver(exportProgressReceiver);
            } catch (Exception e) {
                FLog.w("FaditorMiniFragment", "Error unregistering export receiver: " + e.getMessage());
            }
            isExportReceiverRegistered = false;
        }
        
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

        // Last modified date with icon
        LinearLayout dateRow = new LinearLayout(requireContext());
        dateRow.setOrientation(LinearLayout.HORIZONTAL);
        dateRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams dateRowLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        dateRowLp.topMargin = (int) (3 * dp);
        dateRow.setLayoutParams(dateRowLp);

        // Date icon (Material icon)
        TextView dateIcon = new TextView(requireContext());
        dateIcon.setTypeface(iconFont);
        dateIcon.setText("event");
        dateIcon.setTextColor(0xFF777777);
        dateIcon.setTextSize(14);
        dateIcon.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams dateIconLp = new LinearLayout.LayoutParams(
                (int) (12 * dp), (int) (12 * dp));
        dateIconLp.setMarginEnd((int) (4 * dp));
        dateIcon.setLayoutParams(dateIconLp);
        dateRow.addView(dateIcon);

        // Date text
        TextView date = new TextView(requireContext());
        date.setText(dateFormat.format(new Date(summary.lastModified)));
        date.setTextColor(0xFF999999);
        date.setTextSize(12);
        date.setTypeface(null, Typeface.BOLD);
        date.setMaxLines(1);
        dateRow.addView(date);

        textSection.addView(dateRow);

        // Cache size (remuxed files for this project's clips) with icon
        LinearLayout cacheRow = new LinearLayout(requireContext());
        cacheRow.setOrientation(LinearLayout.HORIZONTAL);
        cacheRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams cacheRowLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        cacheRowLp.topMargin = (int) (3 * dp);
        cacheRow.setLayoutParams(cacheRowLp);

        // Cache icon (drawable matching Records tab)
        ImageView cacheIcon = new ImageView(requireContext());
        cacheIcon.setImageResource(R.drawable.database_24px);
        cacheIcon.setColorFilter(0xFF666666, android.graphics.PorterDuff.Mode.SRC_IN);
        LinearLayout.LayoutParams cacheIconLp = new LinearLayout.LayoutParams(
                (int) (12 * dp), (int) (12 * dp));
        cacheIconLp.setMarginEnd((int) (4 * dp));
        cacheIcon.setLayoutParams(cacheIconLp);
        cacheRow.addView(cacheIcon);

        // Cache size text
        TextView cacheSize = new TextView(requireContext());
        String cacheSizeText = getCacheSizeText(summary);
        cacheSize.setText(cacheSizeText);
        cacheSize.setTextColor(0xFF666666);
        cacheSize.setTextSize(12);
        cacheSize.setTypeface(null, Typeface.BOLD);
        cacheSize.setMaxLines(1);
        cacheRow.addView(cacheSize);

        textSection.addView(cacheRow);

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
                    FLog.w(TAG, "ContentResolver query failed for display name", e);
                }
            }

            // Try extracting from path
            String path = uri.getPath();
            if (path != null) {
                // Handle SAF-style paths like /tree/primary:Android/data/.../ServaCam_xxx.mp4
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
            FLog.w(TAG, "Failed to extract display name from: " + uriString, e);
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
                FLog.w(TAG, "Thumbnail extraction failed", e);
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
     * "ServaCam Recordings" options.
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
            FLog.e(TAG, "Failed to open video picker", e);
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

    /**
     * Calculate cache size for a project and format it as a human-readable string.
     * Cache includes remuxed video files created for seeking support.
     */
    @NonNull
    private String getCacheSizeText(@NonNull ProjectStorage.ProjectSummary summary) {
        try {
            // Load project to get all clip source URIs
            FaditorProject project = projectStorage.load(summary.id);
            if (project == null || project.getTimeline() == null) {
                return "Cache: 0 KB";
            }

            long totalCacheSize = 0;
            com.servalabs.cam.playback.FragmentedMp4Remuxer remuxer =
                    new com.servalabs.cam.playback.FragmentedMp4Remuxer(requireContext());

            // Sum cache for all video clips
            Timeline timeline = project.getTimeline();
            for (int i = 0; i < timeline.getClipCount(); i++) {
                Clip clip = timeline.getClip(i);
                if (clip != null) {
                    android.net.Uri sourceUri = clip.getSourceUri();
                    if (sourceUri != null) {
                        String filename = extractFilenameFromUri(sourceUri.toString());
                        if (filename != null) {
                            String baseName = filename.substring(0,
                                    Math.max(filename.lastIndexOf('.'), filename.length()));
                            totalCacheSize += remuxer.getCacheSizeForPrefix(baseName);
                        }
                    }
                }
            }

            // Sum cache for all audio clips
            List<AudioClip> audioClips = timeline.getAudioClips();
            if (audioClips != null) {
                for (AudioClip audioClip : audioClips) {
                    if (audioClip != null && audioClip.getSourceUri() != null) {
                        String filename = extractFilenameFromUri(audioClip.getSourceUri().toString());
                        if (filename != null) {
                            String baseName = filename.substring(0,
                                    Math.max(filename.lastIndexOf('.'), filename.length()));
                            totalCacheSize += remuxer.getCacheSizeForPrefix(baseName);
                        }
                    }
                }
            }

            // Format size as KB or MB
            if (totalCacheSize == 0) {
                return "Cache: 0 KB";
            } else if (totalCacheSize < 1024_000) {
                long kb = totalCacheSize / 1024;
                return "Cache: " + kb + " KB";
            } else {
                double mb = totalCacheSize / 1024.0 / 1024.0;
                return String.format("Cache: %.1f MB", mb);
            }
        } catch (Exception e) {
            FLog.w(TAG, "Could not calculate cache size: " + e.getMessage());
            return "Cache: unknown";
        }
    }

    /**
     * Extract filename from a URI (file path or content URI).
     */
    @Nullable
    private String extractFilenameFromUri(@NonNull String uri) {
        try {
            if (uri.startsWith("file://")) {
                uri = uri.substring(7);  // Remove "file://" prefix
            } else if (uri.startsWith("content://")) {
                int lastSlash = uri.lastIndexOf('/');
                if (lastSlash >= 0) {
                    return uri.substring(lastSlash + 1);
                }
                return null;
            }

            int lastSlash = uri.lastIndexOf('/');
            if (lastSlash >= 0) {
                return uri.substring(lastSlash + 1);
            }
            return uri;
        } catch (Exception e) {
            FLog.w(TAG, "Could not extract filename from URI: " + uri, e);
            return null;
        }
    }

    // ── Export Dock Management ───────────────────────────────────────

    /**
     * Show the export dock with a smooth animation.
     * Mirrors the animation style from Records tab deletion dock.
     */
    private void showDockWithAnimation() {
        if (exportDock == null) return;
        
        if (exportDock.getVisibility() == View.VISIBLE) {
            return; // Already visible
        }

        exportDock.setVisibility(View.VISIBLE);
        exportDock.setAlpha(0f);
        exportDock.setTranslationY(-16f);
        
        exportDock.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(300)
                .setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator())
                .start();
        
        FLog.d(TAG, "Export dock shown with animation");
    }

    /**
     * Hide the export dock with a smooth animation.
     */
    private void hideDockWithAnimation() {
        if (exportDock == null || exportDock.getVisibility() != View.VISIBLE) return;

        exportDock.animate()
                .alpha(0f)
                .translationY(-16f)
                .setDuration(300)
                .setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator())
                .withEndAction(() -> {
                    if (exportDock != null) {
                        exportDock.setVisibility(View.GONE);
                    }
                })
                .start();
        
        FLog.d(TAG, "Export dock hidden with animation");
    }

    /**
     * Update the export dock with current progress snapshot.
     * Calculates ETA based on progress rate and elapsed time.
     */
    private void updateExportDockWithProgress(float progress) {
        if (exportDock == null || exportDock.getVisibility() != View.VISIBLE) return;

        // Update progress bar
        if (exportDockProgress != null) {
            int progressPercent = Math.round(progress * 100f);
            exportDockProgress.setProgress(progressPercent);
        }

        // Calculate and update ETA
        long elapsedMs = System.currentTimeMillis() - exportStartTimeMs;
        if (progress > 0 && elapsedMs > 500) {
            // Estimate total time based on progress rate
            long totalEstimatedMs = (long) (elapsedMs / progress);
            long remainingMs = totalEstimatedMs - elapsedMs;

            if (remainingMs > 0) {
                String etaText = formatEta(remainingMs);
                if (exportDockEta != null) {
                    exportDockEta.setText(etaText);
                }
            }
        }

        // Store last progress for completion detection
        lastReportedProgress = progress;
    }

    /**
     * Format remaining milliseconds into human-readable ETA string.
     */
    @NonNull
    private String formatEta(long remainingMs) {
        long seconds = remainingMs / 1000;
        if (seconds < 60) {
            return getString(R.string.export_eta_seconds, seconds);
        }
        long minutes = seconds / 60;
        if (minutes < 60) {
            return getString(R.string.export_eta_minutes, minutes);
        }
        long hours = minutes / 60;
        return getString(R.string.export_eta_hours, hours);
    }

    /**
     * Handle export completion: show checkmark, hide OK button initially, then show after delay.
     */
    private void handleExportCompleted(String outputPath) {
        if (exportDock == null) return;

        FLog.d(TAG, "Export completed: " + outputPath);
        isExportActive = false;

        // Update icon to checkmark
        if (exportDockIcon != null) {
            exportDockIcon.setImageResource(android.R.drawable.ic_input_add);  // Use system icon as fallback
            // Try to set to check_circle if available
            try {
                exportDockIcon.setImageResource(R.drawable.ic_check_circle);
            } catch (Exception ignore) {}
        }

        // Update title
        if (exportDockTitle != null) {
            exportDockTitle.animateSlot(getString(R.string.faditor_export_completed), 300);
        }

        // Ensure progress is at 100%
        if (exportDockProgress != null) {
            exportDockProgress.setProgress(100);
        }

        // Show dismiss hint (matching Records tab style)
        if (exportDockEta != null) {
            exportDockEta.setText(getString(R.string.records_delete_header_dismiss_hint));
        }

        // Show file size summary
        if (exportDockSummary != null && outputPath != null) {
            long fileSizeBytes = new java.io.File(outputPath).length();
            String sizeStr = android.text.format.Formatter.formatFileSize(requireContext(), fileSizeBytes);
            exportDockSummary.setText(getString(R.string.export_file_size, sizeStr));
        }

        // Show OK button after a brief delay
        if (exportDockOk != null) {
            exportDockOk.postDelayed(() -> {
                if (exportDockOk != null) {
                    exportDockOk.setVisibility(View.VISIBLE);
                    exportDockOk.animateSlot(getString(R.string.records_delete_header_action_ok), 300);
                    exportDockOk.setAlpha(0f);
                    exportDockOk.animate()
                            .alpha(1f)
                            .setDuration(200)
                            .start();
                }
            }, 600);
        }
    }

    /**
     * Handle export error: show error icon and message.
     */
    private void handleExportError(String errorMessage) {
        if (exportDock == null) return;

        FLog.d(TAG, "Export error: " + errorMessage);
        isExportActive = false;

        // Update icon to error
        if (exportDockIcon != null) {
            exportDockIcon.setImageResource(R.drawable.ic_error);
        }

        // Update title
        if (exportDockTitle != null) {
            exportDockTitle.animateSlot(getString(R.string.faditor_export_failed), 300);
        }

        // Show error message
        if (exportDockCurrent != null) {
            exportDockCurrent.setText(errorMessage);
        }

        // Show ETA as error hint
        if (exportDockEta != null) {
            exportDockEta.setText(R.string.export_error_hint);
        }

        // Show OK button to dismiss
        if (exportDockOk != null) {
            exportDockOk.setVisibility(View.VISIBLE);
            exportDockOk.animateSlot(getString(R.string.records_delete_header_action_ok), 300);
        }
    }

    /**
     * Check if there's an active export and bind to ExportService to receive updates.
     * Called from onResume() to restore dock state after configuration change.
     */
    private void checkAndBindExportService() {
        try {
            // Create a temporary intent to check if service is running
            // For now, just schedule a check after a delay
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                if (!isAdded()) return;
                
                // This would be called from ExportService callbacks in a real implementation
                // For now, the Faditor editor will notify us when it launches the service
            }, 100);
        } catch (Exception e) {
            FLog.e(TAG, "Error checking export service", e);
        }
    }

    /**
     * Global notification receiver for export notifications.
     * Should be called by the ExportService or from the Faditor editor.
     */
    public void onExportStarted(String outputPath) {
        if (!isAdded()) return;

        FLog.d(TAG, "Export started: " + outputPath);
        exportStartTimeMs = System.currentTimeMillis();
        lastReportedProgress = 0f;
        isExportActive = true;

        // Update dock content
        if (exportDockTitle != null) {
            exportDockTitle.setText(getString(R.string.faditor_export_progress));
        }
        if (exportDockCurrent != null) {
            // Extract just the filename from the full path
            String filename = new java.io.File(outputPath).getName();
            exportDockCurrent.setText(filename);
        }
        if (exportDockProgress != null) {
            exportDockProgress.setProgress(0);
        }
        if (exportDockIcon != null) {
            // Use a Material icon or drawable for export/download state
            // For now, use a generic info/download icon; production code can use a dedicated icon
            exportDockIcon.setImageResource(android.R.drawable.ic_menu_save);
        }
        if (exportDockOk != null) {
            // Show Cancel button during export
            exportDockOk.setVisibility(View.VISIBLE);
            exportDockOk.animateSlot(getString(R.string.records_delete_header_action_cancel), 300);
        }

        showDockWithAnimation();
    }

    /**
     * Called when export progress updates (0.0 - 1.0).
     */
    public void onExportProgress(float progress) {
        if (!isAdded() || exportDock == null) return;
        updateExportDockWithProgress(progress);
    }

    /**
     * Called when export completes successfully.
     */
    public void onExportCompleted(String outputPath) {
        if (!isAdded() || exportDock == null) return;
        handleExportCompleted(outputPath);
    }

    /**
     * Called when export fails.
     */
    public void onExportError(String errorMessage) {
        if (!isAdded() || exportDock == null) return;
        handleExportError(errorMessage);
    }

    /**
     * Called when export is cancelled by user.
     */
    public void onExportCancelled() {
        if (!isAdded() || exportDock == null) return;
        
        FLog.d(TAG, "Export cancelled");
        isExportActive = false;

        // Update icon to info/cancelled state
        if (exportDockIcon != null) {
            exportDockIcon.setImageResource(android.R.drawable.ic_dialog_info);
        }

        // Update title
        if (exportDockTitle != null) {
            exportDockTitle.animateSlot(getString(R.string.faditor_export_cancelled), 300);
        }

        // Show progress at current state (don't reset)
        if (exportDockProgress != null) {
            // Keep current progress value
        }

        // Clear ETA
        if (exportDockEta != null) {
            exportDockEta.setText(getString(R.string.records_delete_header_dismiss_hint));
        }

        // Show OK button to dismiss
        if (exportDockOk != null) {
            exportDockOk.setVisibility(View.VISIBLE);
            exportDockOk.animateSlot(getString(R.string.records_delete_header_action_ok), 300);
        }
    }
}
