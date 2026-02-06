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

import com.fadcam.R;
import com.fadcam.ui.faditor.FaditorEditorActivity;
import com.fadcam.ui.faditor.VideoSourceBottomSheet;
import com.fadcam.ui.faditor.project.ProjectStorage;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Entry-point fragment for the Faditor Mini tab.
 *
 * <p>Shows a hero section with "Start Project" button, recent projects list,
 * and feature capability cards. Launches {@link FaditorEditorActivity}
 * for full-screen editing via a video source bottom sheet.</p>
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

        recentProjectsSection = view.findViewById(R.id.recent_projects_section);
        recentProjectsList = view.findViewById(R.id.recent_projects_list);

        return view;
    }

    @Override
    public void onResume() {
        super.onResume();
        refreshRecentProjects();
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
     * Launch the full-screen editor with the selected video.
     */
    private void launchEditor(@NonNull Uri videoUri) {
        Intent intent = new Intent(requireContext(), FaditorEditorActivity.class);
        intent.setData(videoUri);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        editorLauncher.launch(intent);
    }
}
