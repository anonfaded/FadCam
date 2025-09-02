package com.fadcam.ui.faditor;

import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.lifecycle.ViewModelProvider;
import com.fadcam.Log;
import com.fadcam.MainActivity;
import com.fadcam.R;
import com.fadcam.ui.BaseFragment;
import com.fadcam.ui.faditor.components.ControlsComponent;
import com.fadcam.ui.faditor.components.ProgressComponent;
import com.fadcam.ui.faditor.components.TimelineComponent;
import com.fadcam.ui.faditor.components.ToolbarComponent;
import com.fadcam.ui.faditor.components.VideoPlayerComponent;
import com.fadcam.ui.faditor.models.EditorState;
import com.fadcam.ui.faditor.models.VideoProject;
import com.fadcam.ui.faditor.persistence.AutoSaveManager;
import com.fadcam.ui.faditor.persistence.MediaReferenceManager;
import com.fadcam.ui.faditor.persistence.ProjectManager;
import com.fadcam.ui.faditor.utils.Material3Utils;
import com.fadcam.ui.faditor.utils.MemoryOptimizer;
import com.fadcam.ui.faditor.utils.NavigationUtils;
import com.fadcam.ui.faditor.utils.PerformanceMonitor;
import com.fadcam.ui.faditor.utils.PerformanceOptimizer;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Dedicated full-screen editor fragment for professional video editing.
 * Provides maximum screen space by hiding bottom navigation and using overlay
 * container.
 * Implements requirements 10.1-10.5 and 12.7 for professional editing
 * experience.
 */
public class FaditorEditorFragment
    extends BaseFragment
    implements
        AutoSaveManager.AutoSaveListener,
        ToolbarComponent.ToolbarListener,
        VideoPlayerComponent.VideoPlayerListener,
        TimelineComponent.TimelineListener {

    private static final String TAG = "FaditorEditorFragment";
    private static final String ARG_PROJECT_ID = "project_id";

    // UI Components
    private MaterialToolbar editorToolbar;
    private VideoPlayerComponent videoPlayer;
    private TimelineComponent timeline;
    private ToolbarComponent toolbar;
    private ControlsComponent controls;
    private ProgressComponent progressOverlay;
    private MaterialButton saveButton;
    private MaterialButton exportButton;

    // Core components
    private AutoSaveManager autoSaveManager;
    private ProjectManager projectManager;
    private VideoProject currentProject;
    private EditorState editorState;

    // Performance monitoring components
    private PerformanceMonitor performanceMonitor;

    // Flag to prevent feedback loop between video player and timeline
    private boolean isUpdatingFromVideoPlayer = false;

    // -------------- Fix Start (position update throttling) --------------
    // Position update throttling to prevent stale updates
    private long lastPositionUpdateTime = 0;
    private long lastValidPosition = 0;
    private static final long POSITION_UPDATE_THROTTLE_MS = 100; // Ignore updates older than 100ms
    private static final long MAX_POSITION_JUMP_MS = 500; // Ignore jumps larger than 500ms
    // -------------- Fix Ended (position update throttling) --------------

    private PerformanceOptimizer performanceOptimizer;
    private MemoryOptimizer memoryOptimizer;

    // State management
    private String projectId;
    private boolean isInitialized = false;
    private boolean hasUnsavedChanges = false;

    /**
     * Creates a new instance of FaditorEditorFragment for the specified project
     */
    public static FaditorEditorFragment newInstance(String projectId) {
        Log.d(TAG, "=== FADITOR EDITOR FRAGMENT NEW INSTANCE ===");
        Log.d(TAG, "Creating new instance for projectId: " + projectId);
        FaditorEditorFragment fragment = new FaditorEditorFragment();
        Bundle args = new Bundle();
        args.putString(ARG_PROJECT_ID, projectId);
        fragment.setArguments(args);
        Log.d(TAG, "FaditorEditorFragment instance created with args");
        return fragment;
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        Log.d(TAG, "=== FADITOR EDITOR FRAGMENT ON CREATE ===");
        super.onCreate(savedInstanceState);

        // Get project ID from arguments
        if (getArguments() != null) {
            projectId = getArguments().getString(ARG_PROJECT_ID);
            Log.d(TAG, "Project ID from arguments: " + projectId);
        } else {
            Log.e(TAG, "No arguments provided to FaditorEditorFragment!");
        }

        if (projectId == null) {
            Log.e(TAG, "No project ID provided to editor");
            // Return to browser if no project ID
            NavigationUtils.returnToBrowser(this);
            return;
        }

        Log.d(TAG, "Setting up managers...");
        // Initialize core components
        setupProjectManager();
        setupAutoSaveManager();
        setupPerformanceMonitoring();
        Log.d(TAG, "=== FADITOR EDITOR FRAGMENT ON CREATE COMPLETED ===");
    }

    @Nullable
    @Override
    public View onCreateView(
        @NonNull LayoutInflater inflater,
        @Nullable ViewGroup container,
        @Nullable Bundle savedInstanceState
    ) {
        Log.d(TAG, "=== FADITOR EDITOR FRAGMENT ON CREATE VIEW ===");
        View view = inflater.inflate(
            R.layout.fragment_faditor_editor,
            container,
            false
        );

        Log.d(TAG, "Initializing views...");
        initializeViews(view);
        Log.d(TAG, "Calling loadProject()...");
        loadProject();
        Log.d(TAG, "=== FADITOR EDITOR FRAGMENT ON CREATE VIEW COMPLETED ===");

        return view;
    }

    @Override
    public void onViewCreated(
        @NonNull View view,
        @Nullable Bundle savedInstanceState
    ) {
        super.onViewCreated(view, savedInstanceState);

        // Hide bottom navigation for full-screen editing (Requirement 10.3)
        hideBottomNavigation();
    }

    @Override
    public void onResume() {
        super.onResume();

        // Start auto-save when editor becomes active (Requirement 12.7)
        if (isInitialized && currentProject != null && editorState != null) {
            autoSaveManager.startAutoSave(currentProject, editorState);
        }
    }

    @Override
    public void onPause() {
        super.onPause();

        // Immediate save when leaving editor (Requirement 12.2)
        if (autoSaveManager != null) {
            autoSaveManager.saveImmediately();
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();

        // Cancel any ongoing export operations
        if (currentExporter != null && currentExporter.isExporting()) {
            currentExporter.cancelExport();
            currentExporter = null;
        }

        // Clean up auto-save and show bottom navigation
        if (autoSaveManager != null) {
            autoSaveManager.stopAutoSave();
        }

        // Clean up performance monitoring
        if (performanceOptimizer != null) {
            performanceOptimizer.stopOptimization();
            performanceOptimizer.cleanup();
        }

        showBottomNavigation();

        // Clean up component listeners
        if (toolbar != null) {
            toolbar.setToolbarListener(null);
        }
        if (videoPlayer != null) {
            videoPlayer.setVideoPlayerListener(null);
        }
        if (timeline != null) {
            timeline.setTimelineListener(null);
        }
    }

    @Override
    protected boolean onBackPressed() {
        // Use enhanced NavigationUtils for back press handling (Requirement 10.4, 10.5)
        Log.d(TAG, "Back pressed in editor");

        return NavigationUtils.handleBackPress(this);
    }

    // Initialization methods

    private void setupProjectManager() {
        projectManager = new ProjectManager(requireContext());
    }

    private void setupAutoSaveManager() {
        autoSaveManager = new AutoSaveManager(requireContext(), projectManager);
        autoSaveManager.setAutoSaveListener(this);

        // Add lifecycle observer for auto-save (Requirement 12.3)
        getLifecycle().addObserver(autoSaveManager);

        // Integrate with NavigationUtils for seamless navigation (Requirement 12.2,
        // 12.7)
        NavigationUtils.integrateAutoSave(this, autoSaveManager);
    }

    private void setupPerformanceMonitoring() {
        // Initialize performance monitoring components
        performanceMonitor = PerformanceMonitor.getInstance();
        memoryOptimizer = MemoryOptimizer.getInstance(requireContext());
        performanceOptimizer = PerformanceOptimizer.getInstance(
            requireContext()
        );

        // Set up performance optimization listener
        performanceOptimizer.setPerformanceOptimizationListener(
            new PerformanceOptimizer.PerformanceOptimizationListener() {
                @Override
                public void onPerformanceOptimizationStarted(String reason) {
                    Log.d(TAG, "Performance optimization started: " + reason);
                    // Show subtle feedback to user using existing progress methods
                    if (progressOverlay != null) {
                        progressOverlay.showProgress(
                            "Optimizing performance...",
                            false
                        );
                    }
                }

                @Override
                public void onPerformanceOptimizationCompleted(String summary) {
                    Log.d(
                        TAG,
                        "Performance optimization completed: " + summary
                    );
                    // Hide optimization feedback
                    if (progressOverlay != null) {
                        progressOverlay.hideProgress();
                    }
                }

                @Override
                public void onPerformanceWarning(
                    String warning,
                    String recommendation
                ) {
                    Log.w(
                        TAG,
                        "Performance warning: " +
                        warning +
                        " - " +
                        recommendation
                    );
                    // Show performance warning to user (non-intrusive)
                    showPerformanceWarning(warning, recommendation);
                }

                @Override
                public void onAutoSavePerformanceIssue(long autoSaveTimeMs) {
                    Log.w(
                        TAG,
                        "Auto-save performance issue: " + autoSaveTimeMs + "ms"
                    );
                    // Notify user about slow auto-save
                    showAutoSavePerformanceWarning(autoSaveTimeMs);
                }
            }
        );

        // Start performance optimization
        performanceOptimizer.startOptimization();

        Log.d(TAG, "Performance monitoring initialized");
    }

    private void initializeViews(View view) {
        // Find UI components
        editorToolbar = view.findViewById(R.id.editor_toolbar);
        videoPlayer = view.findViewById(R.id.video_player_component);
        timeline = view.findViewById(R.id.timeline_component);
        toolbar = view.findViewById(R.id.toolbar_component);
        controls = view.findViewById(R.id.controls_component);
        progressOverlay = view.findViewById(R.id.progress_overlay);
        saveButton = view.findViewById(R.id.save_button);
        exportButton = view.findViewById(R.id.export_button);

        // Debug logging for component initialization
        Log.d(TAG, "VideoPlayer component found: " + (videoPlayer != null));
        if (videoPlayer != null) {
            Log.d(
                TAG,
                "VideoPlayer dimensions: " +
                videoPlayer.getWidth() +
                "x" +
                videoPlayer.getHeight()
            );
            Log.d(
                TAG,
                "VideoPlayer visibility: " + videoPlayer.getVisibility()
            );
        }

        // Find new UI components
        ImageButton backButton = view.findViewById(R.id.back_button);
        TextView projectNameText = view.findViewById(R.id.project_name_text);
        TextView qualityIndicator = view.findViewById(R.id.quality_indicator);
        ImageButton playPauseButton = view.findViewById(R.id.play_pause_button);
        TextView currentTimeText = view.findViewById(R.id.current_time_text);
        SeekBar seekBar = view.findViewById(R.id.seek_bar);
        TextView totalTimeText = view.findViewById(R.id.total_time_text);
        ImageButton fullscreenButton = view.findViewById(
            R.id.fullscreen_button
        );
        TextView timelineTimeDisplay = view.findViewById(
            R.id.timeline_time_display
        );

        // Professional timeline controls
        LinearLayout muteClip = view.findViewById(R.id.mute_clip);
        LinearLayout coverButton = view.findViewById(R.id.cover_button);

        // Tool buttons
        View splitTool = view.findViewById(R.id.split_tool);
        View speedTool = view.findViewById(R.id.speed_tool);
        View effectsTool = view.findViewById(R.id.effects_tool);
        View deleteTool = view.findViewById(R.id.delete_tool);
        View addMediaButton = view.findViewById(R.id.add_media_button);

        // Set up click listeners
        if (backButton != null) {
            backButton.setOnClickListener(v -> onBackPressed());
        }

        if (exportButton != null) {
            exportButton.setOnClickListener(v -> exportProject());
        }

        if (playPauseButton != null) {
            playPauseButton.setOnClickListener(v -> togglePlayPause());
        }

        if (fullscreenButton != null) {
            fullscreenButton.setOnClickListener(v -> toggleFullscreen());
        }

        // Professional timeline control listeners
        if (muteClip != null) {
            muteClip.setOnClickListener(v -> toggleMuteClip());
        }

        if (coverButton != null) {
            coverButton.setOnClickListener(v -> selectVideoCover());
        }

        // Tool button listeners
        if (splitTool != null) {
            splitTool.setOnClickListener(v -> onSplitToolSelected());
        }

        if (speedTool != null) {
            speedTool.setOnClickListener(v -> onSpeedToolSelected());
        }

        if (effectsTool != null) {
            effectsTool.setOnClickListener(v -> onEffectsToolSelected());
        }

        if (deleteTool != null) {
            deleteTool.setOnClickListener(v -> onDeleteToolSelected());
        }

        if (addMediaButton != null) {
            addMediaButton.setOnClickListener(v -> onAddMediaSelected());
        }

        // Set up component listeners
        if (toolbar != null) {
            toolbar.setToolbarListener(this);
        }
        if (videoPlayer != null) {
            videoPlayer.setVideoPlayerListener(this);
        }
        if (timeline != null) {
            timeline.setTimelineListener(this);
        }

        // Set up seek bar listener
        if (seekBar != null) {
            SeekBar.OnSeekBarChangeListener seekBarListener =
                new SeekBar.OnSeekBarChangeListener() {
                    @Override
                    public void onProgressChanged(
                        SeekBar seekBar,
                        int progress,
                        boolean fromUser
                    ) {
                        if (fromUser && videoPlayer != null) {
                            long position = (long) progress * 1000; // Convert to milliseconds
                            videoPlayer.seekTo(position);
                            updateTimeDisplay(position);
                        }
                    }

                    @Override
                    public void onStartTrackingTouch(SeekBar seekBar) {}

                    @Override
                    public void onStopTrackingTouch(SeekBar seekBar) {}
                };
            seekBar.setOnSeekBarChangeListener(seekBarListener);
            // Store listener in tag for temporary removal during updates
            seekBar.setTag(seekBarListener);
        }

        // Initially hide progress overlay
        if (progressOverlay != null) {
            progressOverlay.setVisibility(View.GONE);
        }

        // Apply Material 3 theming and accessibility
        applyMaterial3Theming();
        setupAccessibility();
    }

    private void loadProject() {
        Log.d(TAG, "=== LOAD PROJECT STARTED ===");
        Log.d(TAG, "Project ID: " + projectId);

        if (projectManager == null || projectId == null) {
            Log.e(
                TAG,
                "Cannot load project - projectManager: " +
                projectManager +
                ", projectId: " +
                projectId
            );
            return;
        }

        // Show loading state with proper message
        showProgress(getString(R.string.faditor_loading_project), false);

        Log.d(TAG, "Calling projectManager.loadProjectWithValidation...");
        projectManager.loadProjectWithValidation(
            projectId,
            new ProjectManager.ProjectValidationCallback() {
                @Override
                public void onProjectLoadedWithValidation(
                    VideoProject project,
                    MediaReferenceManager.ValidationResult validation
                ) {
                    Log.d(
                        TAG,
                        "=== PROJECT LOADED WITH VALIDATION CALLBACK ==="
                    );
                    currentProject = project;
                    Log.d(
                        TAG,
                        "Project loaded successfully: " +
                        project.getProjectName()
                    );
                    Log.d(
                        TAG,
                        "Project primary media asset ID: " +
                        project.getPrimaryMediaAssetId()
                    );

                    // Check if URIs require re-selection
                    if (validation.hasUrisRequiringReselection()) {
                        Log.w(
                            TAG,
                            "Project has URIs requiring re-selection: " +
                            validation.getUrisRequiringReselectionCount()
                        );
                        showUriReselectionDialog(validation);
                    } else {
                        // No re-selection needed, proceed normally
                        Log.d(TAG, "Calling loadEditorState...");
                        loadEditorState();
                    }
                    Log.d(
                        TAG,
                        "=== PROJECT LOADED WITH VALIDATION CALLBACK COMPLETED ==="
                    );
                }

                @Override
                public void onError(String errorMessage) {
                    Log.e(TAG, "Failed to load project: " + errorMessage);
                    hideProgress();

                    Toast.makeText(
                        requireContext(),
                        "Failed to load project: " + errorMessage,
                        Toast.LENGTH_LONG
                    ).show();

                    // Return to browser on error
                    NavigationUtils.returnToBrowser(FaditorEditorFragment.this);
                }
            }
        );
    }

    private void showUriReselectionDialog(
        MediaReferenceManager.ValidationResult validation
    ) {
        if (!validation.hasUrisRequiringReselection()) {
            return;
        }

        hideProgress(); // Hide loading progress before showing dialog

        List<Uri> urisToReselect = new ArrayList<>(
            validation.getUrisRequiringReselection().values()
        );

        UriReselectionDialog dialog = UriReselectionDialog.newInstance(
            urisToReselect
        );
        dialog.setCallback(
            new UriReselectionDialog.UriReselectionCallback() {
                @Override
                public void onUriReselected(Uri oldUri, Uri newUri) {
                    Log.d(TAG, "URI re-selected: " + oldUri + " -> " + newUri);

                    // Update project with new URI
                    Map<Uri, Uri> uriMapping = new HashMap<>();
                    uriMapping.put(oldUri, newUri);

                    projectManager.updateProjectWithReselectedUris(
                        currentProject,
                        uriMapping,
                        new ProjectManager.ProjectCallback() {
                            @Override
                            public void onProjectSaved(String projectId) {
                                Log.d(
                                    TAG,
                                    "Project updated with re-selected URI"
                                );
                                Toast.makeText(
                                    requireContext(),
                                    "File selected successfully",
                                    Toast.LENGTH_SHORT
                                ).show();
                            }

                            @Override
                            public void onProjectLoaded(VideoProject project) {}

                            @Override
                            public void onError(String errorMessage) {
                                Log.e(
                                    TAG,
                                    "Failed to update project with re-selected URI: " +
                                    errorMessage
                                );
                                Toast.makeText(
                                    requireContext(),
                                    "Failed to update project: " + errorMessage,
                                    Toast.LENGTH_LONG
                                ).show();
                            }
                        }
                    );
                }

                @Override
                public void onUriSkipped(Uri uri) {
                    Log.d(TAG, "URI re-selection skipped: " + uri);
                    Toast.makeText(
                        requireContext(),
                        "File skipped",
                        Toast.LENGTH_SHORT
                    ).show();
                }

                @Override
                public void onReselectionCompleted() {
                    Log.d(
                        TAG,
                        "URI re-selection completed, proceeding with editor initialization"
                    );
                    Toast.makeText(
                        requireContext(),
                        "All files have been processed",
                        Toast.LENGTH_SHORT
                    ).show();

                    // Now proceed with loading editor state
                    loadEditorState();
                }

                @Override
                public void onReselectionCancelled() {
                    Log.d(TAG, "URI re-selection cancelled");
                    Toast.makeText(
                        requireContext(),
                        "Re-selection cancelled",
                        Toast.LENGTH_SHORT
                    ).show();

                    // Return to browser if user cancels
                    NavigationUtils.returnToBrowser(FaditorEditorFragment.this);
                }
            }
        );

        dialog.show(getParentFragmentManager());
    }

    private void loadEditorState() {
        if (projectManager == null || projectId == null) {
            return;
        }

        projectManager.loadEditorState(
            projectId,
            new ProjectManager.EditorStateCallback() {
                @Override
                public void onEditorStateLoaded(EditorState state) {
                    editorState = state != null ? state : new EditorState();

                    // Initialize editor with loaded state
                    initializeEditor();
                }

                @Override
                public void onEditorStateSaved(String projectId) {
                    // Not used in load operation
                }

                @Override
                public void onError(String errorMessage) {
                    Log.w(
                        TAG,
                        "Failed to load editor state, using default: " +
                        errorMessage
                    );

                    // Use default editor state
                    editorState = new EditorState();
                    initializeEditor();
                }
            }
        );
    }

    private void initializeEditor() {
        Log.d(TAG, "Initializing editor...");
        Log.d(
            TAG,
            "Current project: " +
            (currentProject != null ? currentProject.getProjectName() : "null")
        );
        Log.d(
            TAG,
            "Editor state: " + (editorState != null ? "loaded" : "null")
        );
        Log.d(
            TAG,
            "Video player: " + (videoPlayer != null ? "initialized" : "null")
        );

        if (currentProject == null || editorState == null) {
            Log.w(TAG, "Cannot initialize editor - project or state is null");
            return;
        }

        // Update project name and quality indicator
        View view = getView();
        if (view != null && currentProject.getProjectName() != null) {
            TextView projectNameText = view.findViewById(
                R.id.project_name_text
            );
            TextView qualityIndicator = view.findViewById(
                R.id.quality_indicator
            );

            if (projectNameText != null) {
                projectNameText.setText(currentProject.getProjectName());
            }

            if (
                qualityIndicator != null && currentProject.getMetadata() != null
            ) {
                int height = currentProject.getMetadata().getHeight();
                String quality = height >= 1080
                    ? "1080P"
                    : height >= 720 ? "720P" : "480P";
                qualityIndicator.setText(quality);
            }
        }

        // Initialize video player with professional media management
        Log.d(TAG, "About to check video loading conditions...");
        Log.d(TAG, "Video player instance: " + videoPlayer);

        if (videoPlayer != null && currentProject != null) {
            // Use professional media management if available
            if (
                currentProject.getPrimaryMediaAssetId() != null &&
                !currentProject.getPrimaryMediaAssetId().isEmpty()
            ) {
                Log.d(
                    TAG,
                    "Loading video using professional media management - Asset ID: " +
                    currentProject.getPrimaryMediaAssetId()
                );
                videoPlayer.loadProjectMedia(
                    currentProject.getProjectId(),
                    currentProject.getPrimaryMediaAssetId(),
                    projectManager
                );
            }
            // Project must use professional media management system
            else {
                Log.w(
                    TAG,
                    "Project does not have primary media asset ID - cannot load video: " +
                    currentProject.getProjectId()
                );
            }
        } else {
            Log.w(
                TAG,
                "Cannot load video - videoPlayer: " +
                (videoPlayer != null) +
                ", project: " +
                (currentProject != null)
            );
        }

        // Initialize timeline
        if (timeline != null) {
            timeline.setAutoSaveManager(autoSaveManager);
            // Timeline will get video URI through the professional media management system
            timeline.setVideoDuration(currentProject.getDuration());
            if (currentProject.getCurrentTrim() != null) {
                timeline.setTrimRange(
                    currentProject.getCurrentTrim().getStartMs(),
                    currentProject.getCurrentTrim().getEndMs()
                );
            }

            // Restore timeline state
            if (editorState.getTimelineState() != null) {
                timeline.restoreState(editorState.getTimelineState());
            }
        }

        // Initialize seek bar
        if (view != null) {
            SeekBar seekBar = view.findViewById(R.id.seek_bar);
            if (seekBar != null && currentProject.getDuration() > 0) {
                seekBar.setMax((int) (currentProject.getDuration() / 1000)); // Convert to seconds
            }

            // Initialize time displays
            updateTimeDisplay(0);
        }

        // Initialize toolbar
        if (toolbar != null && editorState.getSelectedTool() != null) {
            try {
                ToolbarComponent.Tool tool = ToolbarComponent.Tool.valueOf(
                    editorState.getSelectedTool()
                );
                toolbar.setSelectedTool(tool);
            } catch (IllegalArgumentException e) {
                Log.w(
                    TAG,
                    "Invalid tool in editor state: " +
                    editorState.getSelectedTool()
                );
            }
        }

        // Start auto-save
        autoSaveManager.startAutoSave(currentProject, editorState);

        // Start periodic performance monitoring
        startPerformanceMonitoring();

        hideProgress();
        isInitialized = true;

        Log.d(
            TAG,
            "Editor initialized successfully for project: " +
            currentProject.getProjectName()
        );
    }

    // Navigation methods

    private void hideBottomNavigation() {
        // Hide bottom navigation for maximum screen space (Requirement 10.3)
        if (getActivity() instanceof MainActivity) {
            View bottomNav = getActivity().findViewById(R.id.bottom_navigation);
            if (bottomNav != null) {
                bottomNav.setVisibility(View.GONE);
            }
        }
    }

    private void showBottomNavigation() {
        // Show bottom navigation when leaving editor
        if (getActivity() instanceof MainActivity) {
            View bottomNav = getActivity().findViewById(R.id.bottom_navigation);
            if (bottomNav != null) {
                bottomNav.setVisibility(View.VISIBLE);
            }
        }
    }

    // Auto-save methods

    public boolean hasUnsavedChanges() {
        return (
            hasUnsavedChanges ||
            (currentProject != null && currentProject.hasUnsavedChanges()) ||
            (editorState != null && editorState.needsSaving()) ||
            (autoSaveManager != null && autoSaveManager.hasUnsavedChanges())
        );
    }

    private void markModified() {
        hasUnsavedChanges = true;
        if (autoSaveManager != null) {
            autoSaveManager.onProjectModified();
        }
    }

    // Project operations

    private void saveProject() {
        if (currentProject == null || projectManager == null) {
            return;
        }

        showProgress(getString(R.string.faditor_saving_project), false);

        projectManager.saveProject(
            currentProject,
            new ProjectManager.ProjectCallback() {
                @Override
                public void onProjectSaved(String projectId) {
                    hideProgress();
                    hasUnsavedChanges = false;

                    Toast.makeText(
                        requireContext(),
                        "Project saved successfully",
                        Toast.LENGTH_SHORT
                    ).show();
                }

                @Override
                public void onProjectLoaded(VideoProject project) {
                    // Not used in save operation
                }

                @Override
                public void onError(String errorMessage) {
                    hideProgress();

                    Toast.makeText(
                        requireContext(),
                        "Failed to save project: " + errorMessage,
                        Toast.LENGTH_LONG
                    ).show();
                }
            }
        );
    }

    private void exportProject() {
        if (currentProject == null || currentProject.getMetadata() == null) {
            Toast.makeText(
                requireContext(),
                "No project loaded for export",
                Toast.LENGTH_SHORT
            ).show();
            return;
        }

        // Show export options dialog (Requirement 4.1)
        com.fadcam.ui.faditor.components.ExportOptionsDialog dialog =
            com.fadcam.ui.faditor.components.ExportOptionsDialog.newInstance(
                currentProject.getMetadata(),
                currentProject.getProjectName()
            );

        dialog.setExportOptionsListener(
            new com.fadcam.ui.faditor.components.ExportOptionsDialog.ExportOptionsListener() {
                @Override
                public void onExportConfirmed(
                    com.fadcam.ui.faditor.processors.VideoExporter.ExportSettings settings
                ) {
                    startVideoExport(settings);
                }

                @Override
                public void onExportCancelled() {
                    Log.d(TAG, "Export cancelled by user");
                }
            }
        );

        dialog.show(getParentFragmentManager(), "export_options");
    }

    private void startVideoExport(
        com.fadcam.ui.faditor.processors.VideoExporter.ExportSettings settings
    ) {
        if (currentProject == null) {
            return;
        }

        // Store settings for retry functionality
        lastExportSettings = settings;

        // Show progress overlay (Requirement 4.3)
        showProgress(getString(R.string.faditor_preparing_export), true);

        // Initialize video exporter
        currentExporter = new com.fadcam.ui.faditor.processors.VideoExporter(
            requireContext()
        );

        // Start export with progress tracking (Requirement 4.2, 4.3)
        currentExporter.exportVideo(
            currentProject,
            settings,
            new com.fadcam.ui.faditor.processors.VideoExporter.ExportCallback() {
                @Override
                public void onStarted() {
                    requireActivity().runOnUiThread(() -> {
                            showProgress(
                                getString(R.string.faditor_export_starting),
                                true
                            );
                            Log.d(TAG, "Export started");
                        });
                }

                @Override
                public void onProgress(int percentage) {
                    requireActivity().runOnUiThread(() -> {
                            // Use enhanced progress tracking with percentage display
                            String message = getString(
                                R.string.faditor_export_progress,
                                percentage
                            );
                            updateProgress(percentage, message);
                            Log.d(TAG, "Export progress: " + percentage + "%");
                        });
                }

                @Override
                public void onSuccess(java.io.File exportedFile) {
                    requireActivity().runOnUiThread(() -> {
                            currentExporter = null; // Clear reference

                            // Show success message with filename
                            String successMessage = getString(
                                R.string.faditor_export_success_message,
                                exportedFile.getName()
                            );
                            showSuccessMessage(successMessage);

                            // Show export success dialog after a brief delay
                            new Handler(Looper.getMainLooper()).postDelayed(
                                    () -> {
                                        showExportSuccessDialog(exportedFile);
                                    },
                                    2500
                                ); // Allow success message to be seen

                            Log.d(
                                TAG,
                                "Export completed: " +
                                exportedFile.getAbsolutePath()
                            );
                        });
                }

                @Override
                public void onError(String errorMessage) {
                    requireActivity().runOnUiThread(() -> {
                            currentExporter = null; // Clear reference

                            // Show error message with retry option
                            String formattedError = getString(
                                R.string.faditor_export_error_message,
                                errorMessage
                            );
                            showErrorMessage(formattedError, true);

                            Log.e(TAG, "Export failed: " + errorMessage);
                        });
                }

                @Override
                public void onCancelled() {
                    requireActivity().runOnUiThread(() -> {
                            currentExporter = null; // Clear reference
                            hideProgress();
                            Toast.makeText(
                                requireContext(),
                                R.string.faditor_export_cancelled,
                                Toast.LENGTH_SHORT
                            ).show();
                            Log.d(TAG, "Export cancelled");
                        });
                }
            }
        );
    }

    private void showExportSuccessDialog(java.io.File exportedFile) {
        // Show success message with options to share or view (Requirement 4.4, 4.5)
        new AlertDialog.Builder(requireContext())
            .setTitle(R.string.faditor_export_completed)
            .setMessage(
                getString(
                    R.string.faditor_export_success_message,
                    exportedFile.getName()
                )
            )
            .setPositiveButton(
                R.string.faditor_share_video,
                (dialog, which) -> {
                    shareExportedVideo(exportedFile);
                }
            )
            .setNeutralButton(R.string.faditor_view_video, (dialog, which) -> {
                viewExportedVideo(exportedFile);
            })
            .setNegativeButton(R.string.faditor_close, (dialog, which) -> {
                dialog.dismiss();
            })
            .show();
    }

    private void showExportErrorDialog(String errorMessage) {
        // Show error message with retry option (Requirement 4.6)
        new AlertDialog.Builder(requireContext())
            .setTitle(R.string.faditor_export_failed)
            .setMessage(
                getString(R.string.faditor_export_error_message, errorMessage)
            )
            .setPositiveButton(R.string.faditor_retry, (dialog, which) -> {
                exportProject(); // Retry export
            })
            .setNegativeButton(R.string.faditor_close, (dialog, which) -> {
                dialog.dismiss();
            })
            .show();
    }

    private void shareExportedVideo(java.io.File videoFile) {
        try {
            android.content.Intent shareIntent = new android.content.Intent(
                android.content.Intent.ACTION_SEND
            );
            shareIntent.setType("video/*");

            // Use FileProvider for secure file sharing
            android.net.Uri videoUri =
                androidx.core.content.FileProvider.getUriForFile(
                    requireContext(),
                    requireContext().getPackageName() + ".fileprovider",
                    videoFile
                );

            shareIntent.putExtra(android.content.Intent.EXTRA_STREAM, videoUri);
            shareIntent.addFlags(
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
            );

            startActivity(
                android.content.Intent.createChooser(
                    shareIntent,
                    getString(R.string.faditor_share_video)
                )
            );
        } catch (Exception e) {
            Log.e(TAG, "Error sharing video", e);
            Toast.makeText(
                requireContext(),
                "Failed to share video: " + e.getMessage(),
                Toast.LENGTH_LONG
            ).show();
        }
    }

    private void viewExportedVideo(java.io.File videoFile) {
        try {
            android.content.Intent viewIntent = new android.content.Intent(
                android.content.Intent.ACTION_VIEW
            );

            // Use FileProvider for secure file access
            android.net.Uri videoUri =
                androidx.core.content.FileProvider.getUriForFile(
                    requireContext(),
                    requireContext().getPackageName() + ".fileprovider",
                    videoFile
                );

            viewIntent.setDataAndType(videoUri, "video/*");
            viewIntent.addFlags(
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
            );

            startActivity(viewIntent);
        } catch (Exception e) {
            Log.e(TAG, "Error viewing video", e);
            Toast.makeText(
                requireContext(),
                "Failed to open video: " + e.getMessage(),
                Toast.LENGTH_LONG
            ).show();
        }
    }

    public void saveAndExit() {
        // Immediate save before leaving (Requirement 12.2)
        if (autoSaveManager != null) {
            autoSaveManager.saveImmediately();
        }

        // Return to project browser (Requirement 10.4, 10.5)
        NavigationUtils.returnToBrowser(this);
    }

    public void showUnsavedChangesDialog() {
        new AlertDialog.Builder(requireContext())
            .setTitle(R.string.faditor_unsaved_changes_title)
            .setMessage(R.string.faditor_unsaved_changes_message)
            .setPositiveButton(
                R.string.faditor_save_and_exit,
                (dialog, which) -> {
                    saveProject();
                    saveAndExit();
                }
            )
            .setNegativeButton(
                R.string.faditor_discard_and_exit,
                (dialog, which) -> {
                    // Stop auto-save to prevent saving discarded changes
                    if (autoSaveManager != null) {
                        autoSaveManager.stopAutoSave();
                    }
                    NavigationUtils.returnToBrowser(this);
                }
            )
            .setNeutralButton(R.string.faditor_cancel, (dialog, which) -> {
                dialog.dismiss();
            })
            .show();
    }

    // Progress methods and export state

    private com.fadcam.ui.faditor.processors.VideoExporter currentExporter;
    private com.fadcam.ui.faditor.processors.VideoExporter.ExportSettings lastExportSettings;

    private void showProgress(String message, boolean cancellable) {
        if (progressOverlay != null) {
            progressOverlay.showProgress(message, cancellable);
            setupProgressListener();
        }
    }

    private void showProgressWithPercentage(
        String message,
        int percentage,
        boolean cancellable
    ) {
        if (progressOverlay != null) {
            progressOverlay.showProgress(
                ProgressComponent.ProgressType.DETERMINATE,
                message,
                percentage,
                cancellable
            );
            setupProgressListener();
        }
    }

    private void updateProgress(int percentage, String message) {
        if (progressOverlay != null) {
            progressOverlay.updateProgress(percentage, message);
        }
    }

    private void showSuccessMessage(String message) {
        if (progressOverlay != null) {
            progressOverlay.showSuccess(message);
            setupProgressListener();
        }
    }

    private void showErrorMessage(String errorMessage, boolean showRetry) {
        if (progressOverlay != null) {
            progressOverlay.showError(errorMessage, showRetry);
            setupProgressListener();
        }
    }

    private void showPerformanceWarning(String warning, String recommendation) {
        // Show non-intrusive performance warning
        if (getActivity() != null) {
            new Handler(Looper.getMainLooper()).post(() -> {
                    Toast.makeText(
                        getActivity(),
                        warning,
                        Toast.LENGTH_SHORT
                    ).show();
                });
        }
        Log.w(
            TAG,
            "Performance warning: " +
            warning +
            " - Recommendation: " +
            recommendation
        );
    }

    // New UI interaction methods

    private void togglePlayPause() {
        if (videoPlayer != null) {
            // -------------- Fix Start (togglePlayPause) --------------
            boolean currentlyPlaying = videoPlayer.isPlaying();
            Log.d(
                TAG,
                "Toggle play/pause - current state: " + currentlyPlaying
            );

            if (currentlyPlaying) {
                videoPlayer.pause();
            } else {
                videoPlayer.play();
            }

            // Update button state immediately for better UX, callback will confirm
            updatePlayPauseButton(!currentlyPlaying);
            // -------------- Fix Ended (togglePlayPause) --------------
        }
    }

    private void updatePlayPauseButton(boolean isPlaying) {
        View view = getView();
        if (view != null) {
            ImageButton playPauseButton = view.findViewById(
                R.id.play_pause_button
            );
            if (playPauseButton != null) {
                Log.d(
                    TAG,
                    "Updating play/pause button - isPlaying: " + isPlaying
                );
                playPauseButton.setImageResource(
                    isPlaying ? R.drawable.ic_pause : R.drawable.ic_play_arrow
                );
            }
        }
    }

    private void toggleFullscreen() {
        // TODO: Implement fullscreen toggle
        Toast.makeText(
            requireContext(),
            "Fullscreen mode coming soon",
            Toast.LENGTH_SHORT
        ).show();
    }

    private void zoomOutTimeline() {
        if (timeline != null) {
            timeline.zoomOut();
        }
    }

    private void zoomInTimeline() {
        if (timeline != null) {
            timeline.zoomIn();
        }
    }

    // -------------- Fix Start (Professional Timeline Controls) --------------
    private void toggleMuteClip() {
        if (timeline != null) {
            // Toggle mute state for the current video clip
            Log.d(TAG, "Toggle mute clip");
            // TODO: Implement mute functionality
            showSuccessMessage("Clip mute toggled");
        }
    }

    private void selectVideoCover() {
        if (timeline != null) {
            Log.d(TAG, "Select video cover");
            // TODO: Implement cover selection functionality
            showSuccessMessage("Cover selection feature coming soon");
        }
    }

    // -------------- Fix Ended (Professional Timeline Controls) --------------

    // -------------- Fix Start (updateSeekBar) --------------
    /**
     * Update seek bar position without triggering change events
     */
    private void updateSeekBar(long positionMs) {
        View view = getView();
        if (view != null) {
            SeekBar seekBar = view.findViewById(R.id.seek_bar);
            if (
                seekBar != null &&
                currentProject != null &&
                currentProject.getDuration() > 0
            ) {
                int progress = (int) (positionMs / 1000); // Convert to seconds
                seekBar.setProgress(progress);
            }
        }
    }

    // -------------- Fix Ended (updateSeekBar) --------------

    private void onSplitToolSelected() {
        // TODO: Implement split tool
        Toast.makeText(
            requireContext(),
            "Split tool selected",
            Toast.LENGTH_SHORT
        ).show();
    }

    private void onSpeedToolSelected() {
        // TODO: Implement speed tool
        Toast.makeText(
            requireContext(),
            "Speed tool selected",
            Toast.LENGTH_SHORT
        ).show();
    }

    private void onEffectsToolSelected() {
        // TODO: Implement effects tool
        Toast.makeText(
            requireContext(),
            "Effects tool selected",
            Toast.LENGTH_SHORT
        ).show();
    }

    private void onDeleteToolSelected() {
        // TODO: Implement delete tool
        Toast.makeText(
            requireContext(),
            "Delete tool selected",
            Toast.LENGTH_SHORT
        ).show();
    }

    private void onAddMediaSelected() {
        // TODO: Implement add media
        Toast.makeText(
            requireContext(),
            "Add media selected",
            Toast.LENGTH_SHORT
        ).show();
    }

    private void updateTimeDisplay(long currentPosition) {
        View view = getView();
        if (view != null && currentProject != null) {
            TextView currentTimeText = view.findViewById(
                R.id.current_time_text
            );
            TextView totalTimeText = view.findViewById(R.id.total_time_text);
            TextView timelineTimeDisplay = view.findViewById(
                R.id.timeline_time_display
            );
            SeekBar seekBar = view.findViewById(R.id.seek_bar);

            String currentTime = formatTime(currentPosition);
            String totalTime = formatTime(currentProject.getDuration());

            if (currentTimeText != null) {
                currentTimeText.setText(currentTime);
            }

            if (totalTimeText != null) {
                totalTimeText.setText(totalTime);
            }

            if (timelineTimeDisplay != null) {
                // CapCut style: show only current time in center display
                timelineTimeDisplay.setText(currentTime);
            }

            // -------------- Fix Start (prevent seekbar feedback loop) --------------
            if (seekBar != null && currentProject.getDuration() > 0) {
                // Temporarily disable listener to prevent feedback loop
                SeekBar.OnSeekBarChangeListener listener = null;
                Object tag = seekBar.getTag();
                if (tag instanceof SeekBar.OnSeekBarChangeListener) {
                    listener = (SeekBar.OnSeekBarChangeListener) tag;
                    seekBar.setOnSeekBarChangeListener(null);
                }

                int progress = (int) ((currentPosition * seekBar.getMax()) /
                    currentProject.getDuration());
                seekBar.setProgress(progress);

                // Restore listener
                if (listener != null) {
                    seekBar.setOnSeekBarChangeListener(listener);
                }
            }
            // -------------- Fix Ended (prevent seekbar feedback loop) --------------
        }
    }

    private String formatTime(long timeMs) {
        long seconds = timeMs / 1000;
        long minutes = seconds / 60;
        seconds = seconds % 60;
        return String.format("%02d:%02d", minutes, seconds);
    }

    /**
     * Apply Material 3 theming to editor components
     */
    private void applyMaterial3Theming() {
        // Apply Material 3 button styles
        if (saveButton != null) {
            Material3Utils.applyMaterial3ButtonStyle(
                saveButton,
                Material3Utils.ButtonStyle.TEXT
            );
        }

        if (exportButton != null) {
            Material3Utils.applyMaterial3ButtonStyle(
                exportButton,
                Material3Utils.ButtonStyle.FILLED
            );
        }

        // Apply Material 3 motion to toolbar
        if (editorToolbar != null) {
            applyToolbarMotion();
        }

        // Apply Material 3 theming to progress overlay
        if (progressOverlay != null) {
            applyProgressOverlayTheming();
        }
    }

    /**
     * Apply Material 3 motion patterns to toolbar
     */
    private void applyToolbarMotion() {
        if (editorToolbar != null) {
            editorToolbar.setNavigationOnClickListener(v -> {
                // Add subtle animation to back button
                v
                    .animate()
                    .scaleX(0.9f)
                    .scaleY(0.9f)
                    .setDuration(Material3Utils.MOTION_DURATION_SHORT_1)
                    .withEndAction(() -> {
                        v
                            .animate()
                            .scaleX(1.0f)
                            .scaleY(1.0f)
                            .setDuration(Material3Utils.MOTION_DURATION_SHORT_2)
                            .start();
                        onBackPressed();
                    })
                    .start();
            });
        }
    }

    /**
     * Apply Material 3 theming to progress overlay
     */
    private void applyProgressOverlayTheming() {
        if (progressOverlay != null) {
            // Apply Material 3 elevation and background
            progressOverlay.setElevation(Material3Utils.ELEVATION_LEVEL_5);

            // Add fade in/out animations
            progressOverlay.setAlpha(0f);
        }
    }

    /**
     * Setup accessibility features for Material 3 compliance
     */
    private void setupAccessibility() {
        // Enhanced content descriptions for editor components
        if (editorToolbar != null) {
            editorToolbar.setNavigationContentDescription(
                getString(R.string.faditor_back_to_projects)
            );
        }

        if (saveButton != null) {
            saveButton.setContentDescription(getString(R.string.faditor_save));
        }

        if (exportButton != null) {
            exportButton.setContentDescription(
                getString(R.string.faditor_export)
            );
        }

        // Set up accessibility for timeline components
        setupTimelineAccessibility();

        // Set up accessibility for video player
        setupVideoPlayerAccessibility();
    }

    /**
     * Setup accessibility for timeline components
     */
    private void setupTimelineAccessibility() {
        if (timeline != null) {
            timeline.setContentDescription(
                getString(R.string.faditor_timeline)
            );
            timeline.setImportantForAccessibility(
                View.IMPORTANT_FOR_ACCESSIBILITY_YES
            );
        }

        // Add accessibility delegate for timeline interactions
        if (timeline != null) {
            timeline.setAccessibilityDelegate(
                new View.AccessibilityDelegate() {
                    @Override
                    public void onInitializeAccessibilityNodeInfo(
                        View host,
                        android.view.accessibility.AccessibilityNodeInfo info
                    ) {
                        super.onInitializeAccessibilityNodeInfo(host, info);
                        info.setContentDescription(
                            getString(R.string.faditor_timeline) +
                            ". " +
                            "Use to trim and edit video timeline."
                        );

                        // Add custom actions for timeline manipulation
                        info.addAction(
                            new android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction(
                                android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD,
                                "Seek forward"
                            )
                        );
                        info.addAction(
                            new android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction(
                                android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD,
                                "Seek backward"
                            )
                        );
                    }
                }
            );
        }
    }

    /**
     * Setup accessibility for video player
     */
    private void setupVideoPlayerAccessibility() {
        if (videoPlayer != null) {
            videoPlayer.setContentDescription(
                getString(R.string.faditor_video_position)
            );
            videoPlayer.setImportantForAccessibility(
                View.IMPORTANT_FOR_ACCESSIBILITY_YES
            );
        }

        if (controls != null) {
            controls.setImportantForAccessibility(
                View.IMPORTANT_FOR_ACCESSIBILITY_YES
            );
        }
    }

    /**
     * Show progress overlay with Material 3 animation
     */
    private void showProgressWithAnimation(
        String message,
        boolean cancellable
    ) {
        if (progressOverlay != null) {
            progressOverlay.setVisibility(View.VISIBLE);
            Material3Utils.animateFadeIn(progressOverlay);

            // Update progress component with message
            progressOverlay.showProgress(message, cancellable);
            setupProgressListener();
        }
    }

    /**
     * Hide progress overlay with Material 3 animation
     */
    private void hideProgressWithAnimation() {
        if (
            progressOverlay != null &&
            progressOverlay.getVisibility() == View.VISIBLE
        ) {
            Material3Utils.animateFadeOut(progressOverlay, () -> {
                progressOverlay.setVisibility(View.GONE);
            });
        }
    }

    private void showAutoSavePerformanceWarning(long autoSaveTimeMs) {
        // Show subtle warning about slow auto-save
        if (getActivity() != null) {
            new Handler(Looper.getMainLooper()).post(() -> {
                    String message =
                        "Auto-save is taking longer than usual (" +
                        autoSaveTimeMs +
                        "ms)";
                    Toast.makeText(
                        getActivity(),
                        message,
                        Toast.LENGTH_SHORT
                    ).show();
                });
        }
    }

    private void startPerformanceMonitoring() {
        // Start periodic performance checks
        Handler mainHandler = new Handler(Looper.getMainLooper());

        // Check performance every 10 seconds
        Runnable performanceCheckRunnable = new Runnable() {
            @Override
            public void run() {
                if (isInitialized && performanceOptimizer != null) {
                    // Check memory status
                    if (memoryOptimizer != null) {
                        memoryOptimizer.checkMemoryStatus();
                    }

                    // Check auto-save performance
                    if (autoSaveManager != null) {
                        performanceOptimizer.checkAutoSavePerformance(
                            autoSaveManager
                        );
                    }

                    // Log performance summary periodically (every minute)
                    if (
                        performanceMonitor != null &&
                        System.currentTimeMillis() % 60000 < 10000
                    ) {
                        performanceMonitor.logPerformanceSummary();
                    }

                    // Schedule next check
                    mainHandler.postDelayed(this, 10000); // 10 seconds
                }
            }
        };

        // Start the periodic checks
        mainHandler.postDelayed(performanceCheckRunnable, 10000);

        Log.d(TAG, "Performance monitoring started");
    }

    private void hideProgress() {
        if (progressOverlay != null) {
            progressOverlay.hideProgress();
        }
    }

    private void setupProgressListener() {
        if (progressOverlay != null) {
            progressOverlay.setProgressListener(
                new ProgressComponent.ProgressListener() {
                    @Override
                    public void onCancelRequested() {
                        cancelCurrentOperation();
                    }

                    @Override
                    public void onRetryRequested() {
                        retryCurrentOperation();
                    }

                    @Override
                    public void onDismissRequested() {
                        hideProgress();
                    }
                }
            );
        }
    }

    private void cancelCurrentOperation() {
        if (currentExporter != null && currentExporter.isExporting()) {
            currentExporter.cancelExport();
            currentExporter = null;
        }
        hideProgress();
    }

    private void retryCurrentOperation() {
        if (lastExportSettings != null && currentProject != null) {
            // Retry the last export operation
            hideProgress();
            startVideoExport(lastExportSettings);
        } else {
            // No operation to retry, just hide progress
            hideProgress();
            Toast.makeText(
                requireContext(),
                "No operation to retry",
                Toast.LENGTH_SHORT
            ).show();
        }
    }

    /**
     * Example method showing how to use progress tracking for video processing
     * operations
     * This demonstrates the enhanced progress feedback system for trim operations
     */
    private void performTrimOperation(long startMs, long endMs) {
        if (currentProject == null) {
            return;
        }

        // Show indeterminate progress initially
        showProgress(getString(R.string.faditor_processing_video), true);

        // Simulate processing with progress updates
        Handler handler = new Handler(Looper.getMainLooper());

        // Simulate progress updates
        for (int i = 0; i <= 100; i += 10) {
            final int progress = i;
            handler.postDelayed(
                () -> {
                    if (
                        progressOverlay != null &&
                        progressOverlay.isProgressVisible()
                    ) {
                        String message =
                            getString(R.string.faditor_processing_video) +
                            " Trimming...";
                        updateProgress(progress, message);

                        // Simulate completion
                        if (progress == 100) {
                            handler.postDelayed(
                                () -> {
                                    showSuccessMessage(
                                        "Trim operation completed successfully"
                                    );
                                },
                                500
                            );
                        }
                    }
                },
                i * 100
            ); // Update every 100ms
        }
    }

    /**
     * Example method showing error handling with retry option
     */
    private void simulateProcessingError() {
        showErrorMessage("Processing failed due to insufficient memory", true);
    }

    /**
     * Example method showing success feedback
     */
    private void simulateProcessingSuccess() {
        showSuccessMessage("Video processing completed successfully");
    }

    // AutoSaveManager.AutoSaveListener implementation

    @Override
    public void onAutoSaveStarted() {
        // Subtle visual feedback (Requirement 12.6)
        Log.d(TAG, "Auto-save started");
        // TODO: Add subtle visual indicator
    }

    @Override
    public void onAutoSaveCompleted() {
        // Clear unsaved changes flag
        hasUnsavedChanges = false;
        Log.d(TAG, "Auto-save completed");
        // TODO: Add subtle visual feedback
    }

    @Override
    public void onAutoSaveError(String error) {
        Log.e(TAG, "Auto-save error: " + error);
        // TODO: Show subtle error indicator
    }

    @Override
    public void onCrashRecoveryAvailable(String projectId) {
        // Show crash recovery dialog (Requirement 12.4)
        new AlertDialog.Builder(requireContext())
            .setTitle(R.string.faditor_crash_recovery_title)
            .setMessage(R.string.faditor_crash_recovery_message)
            .setPositiveButton(R.string.faditor_recover, (dialog, which) -> {
                // Restore from crash recovery
                autoSaveManager.restoreFromCrashRecovery(
                    projectId,
                    new ProjectManager.EditorStateCallback() {
                        @Override
                        public void onEditorStateLoaded(EditorState state) {
                            if (state != null) {
                                editorState = state;
                                // Re-initialize editor with recovered state
                                initializeEditor();
                                Toast.makeText(
                                    requireContext(),
                                    "Project recovered successfully",
                                    Toast.LENGTH_SHORT
                                ).show();
                            }
                        }

                        @Override
                        public void onEditorStateSaved(String projectId) {
                            // Not used in recovery
                        }

                        @Override
                        public void onError(String errorMessage) {
                            Toast.makeText(
                                requireContext(),
                                "Failed to recover project: " + errorMessage,
                                Toast.LENGTH_LONG
                            ).show();
                        }
                    }
                );
            })
            .setNegativeButton(
                R.string.faditor_start_fresh,
                (dialog, which) -> {
                    // Continue with current state
                    dialog.dismiss();
                }
            )
            .show();
    }

    // ToolbarComponent.ToolbarListener implementation

    @Override
    public void onToolSelected(ToolbarComponent.Tool tool) {
        Log.d(TAG, "Tool selected: " + tool.name());

        // Update editor state
        if (editorState != null) {
            editorState.setSelectedTool(tool.name());
        }

        markModified();
    }

    @Override
    public void onToolAction(ToolbarComponent.Tool tool, Bundle parameters) {
        Log.d(TAG, "Tool action: " + tool.name());

        // Handle tool-specific actions
        // TODO: Implement tool actions in future tasks

        markModified();
    }

    // VideoPlayerComponent.VideoPlayerListener implementation

    @Override
    public void onPlaybackStateChanged(boolean isPlaying) {
        Log.d(TAG, "Fragment received playback state changed: " + isPlaying);

        // Update play/pause button state
        updatePlayPauseButton(isPlaying);

        // Update editor state
        if (editorState != null) {
            editorState.setPlaying(isPlaying);
        }

        markModified();
    }

    @Override
    public void onPositionChanged(long positionMs) {
        // -------------- Fix Start (throttle stale position updates) --------------
        long currentTime = System.currentTimeMillis();

        // Ignore stale position updates (older than throttle threshold)
        if (
            currentTime - lastPositionUpdateTime >
                POSITION_UPDATE_THROTTLE_MS &&
            lastPositionUpdateTime > 0
        ) {
            // This position update is too old, likely stale - ignore it
            return;
        }

        // Ignore position updates that jump too far (likely from cache/previous state)
        if (
            lastValidPosition > 0 &&
            Math.abs(positionMs - lastValidPosition) > MAX_POSITION_JUMP_MS
        ) {
            // This position jump is too large and probably stale - ignore it
            return;
        }

        lastPositionUpdateTime = currentTime;
        lastValidPosition = positionMs;
        // -------------- Fix Ended (throttle stale position updates) --------------

        // Update time displays and seek bar
        updateTimeDisplay(positionMs);

        // Update timeline position with OpenGL frame rendering (prevent feedback loop)
        if (timeline != null) {
            // Further reduced logging to prevent spam - only log every 10 seconds
            if (positionMs % 10000 == 0) {
                Log.d(TAG, "Timeline silent update: " + positionMs + "ms");
            }
            isUpdatingFromVideoPlayer = true;
            timeline.setCurrentPositionSilent(positionMs);
            isUpdatingFromVideoPlayer = false;
        }

        // Update editor state
        if (editorState != null) {
            editorState.setLastPlayPosition(positionMs);
        }
    }

    @Override
    public void onTimelinePositionChanged(long positionMs) {
        // Prevent feedback loop - ignore timeline changes that come from video player
        // updates
        if (isUpdatingFromVideoPlayer) {
            // Silent return - no logging to reduce spam
            return;
        }

        // Record seek performance for monitoring
        long seekStartTime = System.currentTimeMillis();

        // Seek video player to new position
        if (videoPlayer != null) {
            videoPlayer.seekTo(positionMs);
        }

        // -------------- Fix Start (sync video controls) --------------
        // Update video controls (seekbar, time displays) to sync with timeline
        updateTimeDisplay(positionMs);
        // -------------- Fix Ended (sync video controls) --------------

        // Record seek time for performance monitoring
        long seekTime = System.currentTimeMillis() - seekStartTime;
        if (performanceMonitor != null) {
            performanceMonitor.recordSeekTime(seekTime);
        }

        if (editorState != null) {
            editorState.setLastPlayPosition(positionMs);
        }

        markModified();
    }

    @Override
    public void onVideoLoaded(long durationMs) {
        Log.d(TAG, "Video loaded with duration: " + durationMs + "ms");

        // Update project duration if not set
        if (currentProject != null && currentProject.getDuration() <= 0) {
            currentProject.setDuration(durationMs);
        }

        // -------------- Fix Start (proper project state restoration) --------------
        // Update timeline with video duration and URI for OpenGL rendering
        if (timeline != null) {
            timeline.setVideoDuration(durationMs);

            // Set hasVideo flag by setting a dummy URI to make timeline visible
            // TODO: Get actual video URI from media asset system
            if (currentProject != null && durationMs > 0) {
                // Create a dummy URI to indicate video is present
                android.net.Uri dummyUri = android.net.Uri.parse(
                    "file://dummy"
                );
                timeline.setVideoUri(dummyUri);
            }

            // Set trim range if available, otherwise default to full video
            if (currentProject.getCurrentTrim() != null) {
                timeline.setTrimRange(
                    currentProject.getCurrentTrim().getStartMs(),
                    currentProject.getCurrentTrim().getEndMs()
                );
            } else {
                timeline.setTrimRange(0, durationMs);
            }
        }

        // Restore saved project position or start at 0 for new projects
        long restoredPosition = 0;
        if (currentProject != null && editorState != null) {
            restoredPosition = editorState.getLastPlayPosition();
        }

        // Set timeline position (use user seek method to allow position jumps during restoration)
        if (timeline != null) {
            timeline.setCurrentPositionUserSeek(restoredPosition);
        }

        // Ensure video player matches timeline position and is paused
        if (videoPlayer != null) {
            videoPlayer.seekTo(restoredPosition);
            videoPlayer.pause();
        }

        // Update all UI elements to show restored position
        updateTimeDisplay(restoredPosition);

        // Update play/pause button state
        updatePlayPauseButton(false);
        // -------------- Fix Ended (proper project state restoration) --------------

        // Update seek bar max value
        View view = getView();
        if (view != null) {
            SeekBar seekBar = view.findViewById(R.id.seek_bar);
            if (seekBar != null && durationMs > 0) {
                seekBar.setMax((int) (durationMs / 1000)); // Convert to seconds
            }
        }

        // Update time displays
        updateTimeDisplay(0);

        markModified();
    }

    @Override
    public void onVideoError(String error) {
        Log.e(TAG, "Video player error: " + error);

        // Show error message to user
        if (getActivity() != null) {
            getActivity().runOnUiThread(() -> {
                    showErrorMessage("Video playback error: " + error, false);
                });
        }
    }

    // TimelineComponent.TimelineListener implementation

    @Override
    public void onTrimRangeChanged(long startMs, long endMs) {
        Log.d(TAG, "Trim range changed: " + startMs + " - " + endMs);

        // Update project trim range
        if (currentProject != null) {
            VideoProject.TrimRange trimRange = new VideoProject.TrimRange(
                startMs,
                endMs
            );
            currentProject.setCurrentTrim(trimRange);
        }

        markModified();
    }

    @Override
    public void onTimelineStateChanged() {
        // Update editor state with timeline state
        if (editorState != null && timeline != null) {
            editorState.setTimelineState(timeline.getState());
        }

        markModified();
    }

    @Override
    public void onScrubbing(boolean isScrubbing, long positionMs) {
        Log.d(
            TAG,
            "Scrubbing " +
            (isScrubbing ? "started" : "ended") +
            " at " +
            positionMs +
            "ms"
        );

        if (isScrubbing) {
            // -------------- Fix Start (professional cached frame scrubbing) --------------
            // PROFESSIONAL EDITOR APPROACH: Show cached frames during scrubbing
            // Like DaVinci Resolve, Premiere Pro - no video seeking, only cached frame display
            if (videoPlayer != null) {
                videoPlayer.pause();
                videoPlayer.setScrubbing(true); // Enable frame cache and disable position updates

                // Show cached frame for smooth scrubbing preview (like professional editors)
                videoPlayer.showCachedFrameForScrubbing(positionMs);
            }

            // Update editor state but don't trigger expensive video operations
            if (editorState != null) {
                editorState.setLastPlayPosition(positionMs);
            }

            Log.d(
                TAG,
                "Professional scrubbing - showing cached frame for: " +
                positionMs +
                "ms"
            );
            // -------------- Fix Ended (professional cached frame scrubbing) --------------
        } else {
            // -------------- Fix Start (professional scrubbing end - single final seek) --------------
            // SCRUBBING ENDED: Now perform single high-quality seek to exact frame
            // This is the ONLY time video seeking happens during scrubbing workflow
            if (videoPlayer != null) {
                Log.d(
                    TAG,
                    "Professional scrubbing ended - final seek to: " +
                    positionMs +
                    "ms"
                );

                videoPlayer.setScrubbing(false); // Re-enable position updates

                long seekStartTime = System.currentTimeMillis();
                videoPlayer.seekTo(positionMs); // Single final seek for exact frame

                // Record final seek performance
                long seekTime = System.currentTimeMillis() - seekStartTime;
                if (performanceMonitor != null) {
                    performanceMonitor.recordSeekTime(seekTime);
                }
            }

            if (editorState != null) {
                editorState.setLastPlayPosition(positionMs);
            }

            markModified();
            Log.d(TAG, "Professional scrubbing workflow completed");
            // -------------- Fix Ended (professional scrubbing end - single final seek) --------------
        }
    }

    @Override
    public void onZoomChanged(float zoomLevel) {
        Log.d(TAG, "Timeline zoom changed: " + zoomLevel);

        // Update editor state with new zoom level
        if (editorState != null && timeline != null) {
            editorState.setTimelineState(timeline.getState());
        }

        markModified();
    }

    @Override
    public void onFrameSnapped(long framePositionMs) {
        Log.d(TAG, "Frame snapped to position: " + framePositionMs);

        // Update timeline display to show current center line position
        View view = getView();
        if (view != null) {
            TextView timelineTimeDisplay = view.findViewById(
                R.id.timeline_time_display
            );
            if (timelineTimeDisplay != null) {
                String timeText = formatTime(framePositionMs);
                timelineTimeDisplay.setText(timeText);
            }

            // Provide subtle haptic feedback for frame snapping
            view.performHapticFeedback(
                android.view.HapticFeedbackConstants.CLOCK_TICK
            );
        }

        // Only update video position if not actively scrolling/scrubbing
        // This prevents the timeline from jumping back to video position
        if (timeline != null && !timeline.isScrubbing()) {
            if (videoPlayer != null) {
                videoPlayer.seekTo(framePositionMs);
            }

            if (editorState != null) {
                editorState.setLastPlayPosition(framePositionMs);
            }
        }

        markModified();
    }

    // Public getter methods for NavigationUtils integration

    /**
     * Gets the AutoSaveManager instance for navigation integration
     * Requirements: 12.2, 12.7
     */
    public AutoSaveManager getAutoSaveManager() {
        return autoSaveManager;
    }
}
