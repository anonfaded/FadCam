package com.fadcam.ui.faditor;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.lifecycle.ViewModelProvider;

import com.fadcam.Log;
import com.fadcam.MainActivity;
import com.fadcam.R;
import com.fadcam.ui.BaseFragment;
import com.fadcam.ui.faditor.components.VideoPlayerComponent;
import com.fadcam.ui.faditor.components.TimelineComponent;
import com.fadcam.ui.faditor.components.ToolbarComponent;
import com.fadcam.ui.faditor.components.ControlsComponent;
import com.fadcam.ui.faditor.components.ProgressComponent;
import com.fadcam.ui.faditor.models.VideoProject;
import com.fadcam.ui.faditor.models.EditorState;
import com.fadcam.ui.faditor.persistence.AutoSaveManager;
import com.fadcam.ui.faditor.persistence.ProjectManager;
import com.fadcam.ui.faditor.utils.NavigationUtils;
import com.fadcam.ui.faditor.utils.PerformanceMonitor;
import com.fadcam.ui.faditor.utils.PerformanceOptimizer;
import com.fadcam.ui.faditor.utils.MemoryOptimizer;
import com.fadcam.ui.faditor.utils.Material3Utils;
import com.google.android.material.appbar.MaterialToolbar;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.SeekBar;
import com.google.android.material.button.MaterialButton;

/**
 * Dedicated full-screen editor fragment for professional video editing.
 * Provides maximum screen space by hiding bottom navigation and using overlay container.
 * Implements requirements 10.1-10.5 and 12.7 for professional editing experience.
 */
public class FaditorEditorFragment extends BaseFragment implements 
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
        FaditorEditorFragment fragment = new FaditorEditorFragment();
        Bundle args = new Bundle();
        args.putString(ARG_PROJECT_ID, projectId);
        fragment.setArguments(args);
        return fragment;
    }
    
    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        // Get project ID from arguments
        if (getArguments() != null) {
            projectId = getArguments().getString(ARG_PROJECT_ID);
        }
        
        if (projectId == null) {
            Log.e(TAG, "No project ID provided to editor");
            // Return to browser if no project ID
            NavigationUtils.returnToBrowser(this);
            return;
        }
        
        // Initialize core components
        setupProjectManager();
        setupAutoSaveManager();
        setupPerformanceMonitoring();
    }
    
    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_faditor_editor, container, false);
        
        initializeViews(view);
        loadProject();
        
        return view;
    }
    
    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
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
        
        // Integrate with NavigationUtils for seamless navigation (Requirement 12.2, 12.7)
        NavigationUtils.integrateAutoSave(this, autoSaveManager);
    }
    
    private void setupPerformanceMonitoring() {
        // Initialize performance monitoring components
        performanceMonitor = PerformanceMonitor.getInstance();
        memoryOptimizer = MemoryOptimizer.getInstance(requireContext());
        performanceOptimizer = PerformanceOptimizer.getInstance(requireContext());
        
        // Set up performance optimization listener
        performanceOptimizer.setPerformanceOptimizationListener(new PerformanceOptimizer.PerformanceOptimizationListener() {
            @Override
            public void onPerformanceOptimizationStarted(String reason) {
                Log.d(TAG, "Performance optimization started: " + reason);
                // Show subtle feedback to user using existing progress methods
                if (progressOverlay != null) {
                    progressOverlay.showProgress("Optimizing performance...", false);
                }
            }
            
            @Override
            public void onPerformanceOptimizationCompleted(String summary) {
                Log.d(TAG, "Performance optimization completed: " + summary);
                // Hide optimization feedback
                if (progressOverlay != null) {
                    progressOverlay.hideProgress();
                }
            }
            
            @Override
            public void onPerformanceWarning(String warning, String recommendation) {
                Log.w(TAG, "Performance warning: " + warning + " - " + recommendation);
                // Show performance warning to user (non-intrusive)
                showPerformanceWarning(warning, recommendation);
            }
            
            @Override
            public void onAutoSavePerformanceIssue(long autoSaveTimeMs) {
                Log.w(TAG, "Auto-save performance issue: " + autoSaveTimeMs + "ms");
                // Notify user about slow auto-save
                showAutoSavePerformanceWarning(autoSaveTimeMs);
            }
        });
        
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
        
        // Find new UI components
        ImageButton backButton = view.findViewById(R.id.back_button);
        TextView projectNameText = view.findViewById(R.id.project_name_text);
        TextView qualityIndicator = view.findViewById(R.id.quality_indicator);
        ImageButton playPauseButton = view.findViewById(R.id.play_pause_button);
        TextView currentTimeText = view.findViewById(R.id.current_time_text);
        SeekBar seekBar = view.findViewById(R.id.seek_bar);
        TextView totalTimeText = view.findViewById(R.id.total_time_text);
        ImageButton fullscreenButton = view.findViewById(R.id.fullscreen_button);
        TextView timelineTimeDisplay = view.findViewById(R.id.timeline_time_display);
        ImageButton zoomOutButton = view.findViewById(R.id.zoom_out_button);
        ImageButton zoomInButton = view.findViewById(R.id.zoom_in_button);
        
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
        
        if (zoomOutButton != null) {
            zoomOutButton.setOnClickListener(v -> zoomOutTimeline());
        }
        
        if (zoomInButton != null) {
            zoomInButton.setOnClickListener(v -> zoomInTimeline());
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
            seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
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
            });
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
        if (projectManager == null || projectId == null) {
            return;
        }
        
        // Show loading state with proper message
        showProgress(getString(R.string.faditor_loading_project), false);
        
        projectManager.loadProject(projectId, new ProjectManager.ProjectCallback() {
            @Override
            public void onProjectLoaded(VideoProject project) {
                currentProject = project;
                
                // Load editor state
                loadEditorState();
            }
            
            @Override
            public void onProjectSaved(String projectId) {
                // Not used in load operation
            }
            
            @Override
            public void onError(String errorMessage) {
                Log.e(TAG, "Failed to load project: " + errorMessage);
                hideProgress();
                
                Toast.makeText(requireContext(), 
                    "Failed to load project: " + errorMessage, 
                    Toast.LENGTH_LONG).show();
                
                // Return to browser on error
                NavigationUtils.returnToBrowser(FaditorEditorFragment.this);
            }
        });
    }
    
    private void loadEditorState() {
        if (projectManager == null || projectId == null) {
            return;
        }
        
        projectManager.loadEditorState(projectId, new ProjectManager.EditorStateCallback() {
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
                Log.w(TAG, "Failed to load editor state, using default: " + errorMessage);
                
                // Use default editor state
                editorState = new EditorState();
                initializeEditor();
            }
        });
    }
    
    private void initializeEditor() {
        if (currentProject == null || editorState == null) {
            return;
        }
        
        // Update project name and quality indicator
        View view = getView();
        if (view != null && currentProject.getProjectName() != null) {
            TextView projectNameText = view.findViewById(R.id.project_name_text);
            TextView qualityIndicator = view.findViewById(R.id.quality_indicator);
            
            if (projectNameText != null) {
                projectNameText.setText(currentProject.getProjectName());
            }
            
            if (qualityIndicator != null && currentProject.getMetadata() != null) {
                int height = currentProject.getMetadata().getHeight();
                String quality = height >= 1080 ? "1080P" : height >= 720 ? "720P" : "480P";
                qualityIndicator.setText(quality);
            }
        }
        
        // Initialize video player
        if (videoPlayer != null && currentProject.getOriginalVideoUri() != null) {
            videoPlayer.loadVideo(currentProject.getOriginalVideoUri());
        }
        
        // Initialize timeline
        if (timeline != null) {
            timeline.setAutoSaveManager(autoSaveManager);
            timeline.setVideoUri(currentProject.getOriginalVideoUri());
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
                ToolbarComponent.Tool tool = ToolbarComponent.Tool.valueOf(editorState.getSelectedTool());
                toolbar.setSelectedTool(tool);
            } catch (IllegalArgumentException e) {
                Log.w(TAG, "Invalid tool in editor state: " + editorState.getSelectedTool());
            }
        }
        
        // Start auto-save
        autoSaveManager.startAutoSave(currentProject, editorState);
        
        // Start periodic performance monitoring
        startPerformanceMonitoring();
        
        hideProgress();
        isInitialized = true;
        
        Log.d(TAG, "Editor initialized successfully for project: " + currentProject.getProjectName());
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
        return hasUnsavedChanges || 
               (currentProject != null && currentProject.hasUnsavedChanges()) ||
               (editorState != null && editorState.needsSaving()) ||
               (autoSaveManager != null && autoSaveManager.hasUnsavedChanges());
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
        
        projectManager.saveProject(currentProject, new ProjectManager.ProjectCallback() {
            @Override
            public void onProjectSaved(String projectId) {
                hideProgress();
                hasUnsavedChanges = false;
                
                Toast.makeText(requireContext(), 
                    "Project saved successfully", 
                    Toast.LENGTH_SHORT).show();
            }
            
            @Override
            public void onProjectLoaded(VideoProject project) {
                // Not used in save operation
            }
            
            @Override
            public void onError(String errorMessage) {
                hideProgress();
                
                Toast.makeText(requireContext(), 
                    "Failed to save project: " + errorMessage, 
                    Toast.LENGTH_LONG).show();
            }
        });
    }
    
    private void exportProject() {
        if (currentProject == null || currentProject.getMetadata() == null) {
            Toast.makeText(requireContext(), 
                "No project loaded for export", 
                Toast.LENGTH_SHORT).show();
            return;
        }
        
        // Show export options dialog (Requirement 4.1)
        com.fadcam.ui.faditor.components.ExportOptionsDialog dialog = 
            com.fadcam.ui.faditor.components.ExportOptionsDialog.newInstance(
                currentProject.getMetadata(), 
                currentProject.getProjectName()
            );
        
        dialog.setExportOptionsListener(new com.fadcam.ui.faditor.components.ExportOptionsDialog.ExportOptionsListener() {
            @Override
            public void onExportConfirmed(com.fadcam.ui.faditor.processors.VideoExporter.ExportSettings settings) {
                startVideoExport(settings);
            }
            
            @Override
            public void onExportCancelled() {
                Log.d(TAG, "Export cancelled by user");
            }
        });
        
        dialog.show(getParentFragmentManager(), "export_options");
    }
    
    private void startVideoExport(com.fadcam.ui.faditor.processors.VideoExporter.ExportSettings settings) {
        if (currentProject == null) {
            return;
        }
        
        // Store settings for retry functionality
        lastExportSettings = settings;
        
        // Show progress overlay (Requirement 4.3)
        showProgress(getString(R.string.faditor_preparing_export), true);
        
        // Initialize video exporter
        currentExporter = new com.fadcam.ui.faditor.processors.VideoExporter(requireContext());
        
        // Start export with progress tracking (Requirement 4.2, 4.3)
        currentExporter.exportVideo(currentProject, settings, new com.fadcam.ui.faditor.processors.VideoExporter.ExportCallback() {
            @Override
            public void onStarted() {
                requireActivity().runOnUiThread(() -> {
                    showProgress(getString(R.string.faditor_export_starting), true);
                    Log.d(TAG, "Export started");
                });
            }
            
            @Override
            public void onProgress(int percentage) {
                requireActivity().runOnUiThread(() -> {
                    // Use enhanced progress tracking with percentage display
                    String message = getString(R.string.faditor_export_progress, percentage);
                    updateProgress(percentage, message);
                    Log.d(TAG, "Export progress: " + percentage + "%");
                });
            }
            
            @Override
            public void onSuccess(java.io.File exportedFile) {
                requireActivity().runOnUiThread(() -> {
                    currentExporter = null; // Clear reference
                    
                    // Show success message with filename
                    String successMessage = getString(R.string.faditor_export_success_message, exportedFile.getName());
                    showSuccessMessage(successMessage);
                    
                    // Show export success dialog after a brief delay
                    new Handler(Looper.getMainLooper()).postDelayed(() -> {
                        showExportSuccessDialog(exportedFile);
                    }, 2500); // Allow success message to be seen
                    
                    Log.d(TAG, "Export completed: " + exportedFile.getAbsolutePath());
                });
            }
            
            @Override
            public void onError(String errorMessage) {
                requireActivity().runOnUiThread(() -> {
                    currentExporter = null; // Clear reference
                    
                    // Show error message with retry option
                    String formattedError = getString(R.string.faditor_export_error_message, errorMessage);
                    showErrorMessage(formattedError, true);
                    
                    Log.e(TAG, "Export failed: " + errorMessage);
                });
            }
            
            @Override
            public void onCancelled() {
                requireActivity().runOnUiThread(() -> {
                    currentExporter = null; // Clear reference
                    hideProgress();
                    Toast.makeText(requireContext(), 
                        R.string.faditor_export_cancelled, 
                        Toast.LENGTH_SHORT).show();
                    Log.d(TAG, "Export cancelled");
                });
            }
        });
    }
    
    private void showExportSuccessDialog(java.io.File exportedFile) {
        // Show success message with options to share or view (Requirement 4.4, 4.5)
        new AlertDialog.Builder(requireContext())
            .setTitle(R.string.faditor_export_completed)
            .setMessage(getString(R.string.faditor_export_success_message, exportedFile.getName()))
            .setPositiveButton(R.string.faditor_share_video, (dialog, which) -> {
                shareExportedVideo(exportedFile);
            })
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
            .setMessage(getString(R.string.faditor_export_error_message, errorMessage))
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
            android.content.Intent shareIntent = new android.content.Intent(android.content.Intent.ACTION_SEND);
            shareIntent.setType("video/*");
            
            // Use FileProvider for secure file sharing
            android.net.Uri videoUri = androidx.core.content.FileProvider.getUriForFile(
                requireContext(),
                requireContext().getPackageName() + ".fileprovider",
                videoFile
            );
            
            shareIntent.putExtra(android.content.Intent.EXTRA_STREAM, videoUri);
            shareIntent.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
            
            startActivity(android.content.Intent.createChooser(shareIntent, getString(R.string.faditor_share_video)));
            
        } catch (Exception e) {
            Log.e(TAG, "Error sharing video", e);
            Toast.makeText(requireContext(), 
                "Failed to share video: " + e.getMessage(), 
                Toast.LENGTH_LONG).show();
        }
    }
    
    private void viewExportedVideo(java.io.File videoFile) {
        try {
            android.content.Intent viewIntent = new android.content.Intent(android.content.Intent.ACTION_VIEW);
            
            // Use FileProvider for secure file access
            android.net.Uri videoUri = androidx.core.content.FileProvider.getUriForFile(
                requireContext(),
                requireContext().getPackageName() + ".fileprovider",
                videoFile
            );
            
            viewIntent.setDataAndType(videoUri, "video/*");
            viewIntent.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
            
            startActivity(viewIntent);
            
        } catch (Exception e) {
            Log.e(TAG, "Error viewing video", e);
            Toast.makeText(requireContext(), 
                "Failed to open video: " + e.getMessage(), 
                Toast.LENGTH_LONG).show();
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
            .setPositiveButton(R.string.faditor_save_and_exit, (dialog, which) -> {
                saveProject();
                saveAndExit();
            })
            .setNegativeButton(R.string.faditor_discard_and_exit, (dialog, which) -> {
                // Stop auto-save to prevent saving discarded changes
                if (autoSaveManager != null) {
                    autoSaveManager.stopAutoSave();
                }
                NavigationUtils.returnToBrowser(this);
            })
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
    
    private void showProgressWithPercentage(String message, int percentage, boolean cancellable) {
        if (progressOverlay != null) {
            progressOverlay.showProgress(ProgressComponent.ProgressType.DETERMINATE, message, percentage, cancellable);
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
                Toast.makeText(getActivity(), warning, Toast.LENGTH_SHORT).show();
            });
        }
        Log.w(TAG, "Performance warning: " + warning + " - Recommendation: " + recommendation);
    }
    
    // New UI interaction methods
    
    private void togglePlayPause() {
        if (videoPlayer != null) {
            if (videoPlayer.isPlaying()) {
                videoPlayer.pause();
                updatePlayPauseButton(false);
            } else {
                videoPlayer.play();
                updatePlayPauseButton(true);
            }
        }
    }
    
    private void updatePlayPauseButton(boolean isPlaying) {
        View view = getView();
        if (view != null) {
            ImageButton playPauseButton = view.findViewById(R.id.play_pause_button);
            if (playPauseButton != null) {
                playPauseButton.setImageResource(isPlaying ? R.drawable.ic_pause : R.drawable.ic_play_arrow);
            }
        }
    }
    
    private void toggleFullscreen() {
        // TODO: Implement fullscreen toggle
        Toast.makeText(requireContext(), "Fullscreen mode coming soon", Toast.LENGTH_SHORT).show();
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
    
    private void onSplitToolSelected() {
        // TODO: Implement split tool
        Toast.makeText(requireContext(), "Split tool selected", Toast.LENGTH_SHORT).show();
    }
    
    private void onSpeedToolSelected() {
        // TODO: Implement speed tool
        Toast.makeText(requireContext(), "Speed tool selected", Toast.LENGTH_SHORT).show();
    }
    
    private void onEffectsToolSelected() {
        // TODO: Implement effects tool
        Toast.makeText(requireContext(), "Effects tool selected", Toast.LENGTH_SHORT).show();
    }
    
    private void onDeleteToolSelected() {
        // TODO: Implement delete tool
        Toast.makeText(requireContext(), "Delete tool selected", Toast.LENGTH_SHORT).show();
    }
    
    private void onAddMediaSelected() {
        // TODO: Implement add media
        Toast.makeText(requireContext(), "Add media selected", Toast.LENGTH_SHORT).show();
    }
    
    private void updateTimeDisplay(long currentPosition) {
        View view = getView();
        if (view != null && currentProject != null) {
            TextView currentTimeText = view.findViewById(R.id.current_time_text);
            TextView totalTimeText = view.findViewById(R.id.total_time_text);
            TextView timelineTimeDisplay = view.findViewById(R.id.timeline_time_display);
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
                timelineTimeDisplay.setText(currentTime + " / " + totalTime);
            }
            
            if (seekBar != null && currentProject.getDuration() > 0) {
                int progress = (int) (currentPosition * seekBar.getMax() / currentProject.getDuration());
                seekBar.setProgress(progress);
            }
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
            Material3Utils.applyMaterial3ButtonStyle(saveButton, Material3Utils.ButtonStyle.TEXT);
        }
        
        if (exportButton != null) {
            Material3Utils.applyMaterial3ButtonStyle(exportButton, Material3Utils.ButtonStyle.FILLED);
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
                v.animate()
                    .scaleX(0.9f)
                    .scaleY(0.9f)
                    .setDuration(Material3Utils.MOTION_DURATION_SHORT_1)
                    .withEndAction(() -> {
                        v.animate()
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
            editorToolbar.setNavigationContentDescription(getString(R.string.faditor_back_to_projects));
        }
        
        if (saveButton != null) {
            saveButton.setContentDescription(getString(R.string.faditor_save));
        }
        
        if (exportButton != null) {
            exportButton.setContentDescription(getString(R.string.faditor_export));
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
            timeline.setContentDescription(getString(R.string.faditor_timeline));
            timeline.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        }
        
        // Add accessibility delegate for timeline interactions
        if (timeline != null) {
            timeline.setAccessibilityDelegate(new View.AccessibilityDelegate() {
                @Override
                public void onInitializeAccessibilityNodeInfo(View host, 
                        android.view.accessibility.AccessibilityNodeInfo info) {
                    super.onInitializeAccessibilityNodeInfo(host, info);
                    info.setContentDescription(getString(R.string.faditor_timeline) + ". " +
                                             "Use to trim and edit video timeline.");
                    
                    // Add custom actions for timeline manipulation
                    info.addAction(new android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction(
                        android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD,
                        "Seek forward"));
                    info.addAction(new android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction(
                        android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD,
                        "Seek backward"));
                }
            });
        }
    }
    
    /**
     * Setup accessibility for video player
     */
    private void setupVideoPlayerAccessibility() {
        if (videoPlayer != null) {
            videoPlayer.setContentDescription(getString(R.string.faditor_video_position));
            videoPlayer.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        }
        
        if (controls != null) {
            controls.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        }
    }
    
    /**
     * Show progress overlay with Material 3 animation
     */
    private void showProgressWithAnimation(String message, boolean cancellable) {
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
        if (progressOverlay != null && progressOverlay.getVisibility() == View.VISIBLE) {
            Material3Utils.animateFadeOut(progressOverlay, () -> {
                progressOverlay.setVisibility(View.GONE);
            });
        }
    }
    
    private void showAutoSavePerformanceWarning(long autoSaveTimeMs) {
        // Show subtle warning about slow auto-save
        if (getActivity() != null) {
            new Handler(Looper.getMainLooper()).post(() -> {
                String message = "Auto-save is taking longer than usual (" + autoSaveTimeMs + "ms)";
                Toast.makeText(getActivity(), message, Toast.LENGTH_SHORT).show();
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
                        performanceOptimizer.checkAutoSavePerformance(autoSaveManager);
                    }
                    
                    // Log performance summary periodically (every minute)
                    if (performanceMonitor != null && System.currentTimeMillis() % 60000 < 10000) {
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
            progressOverlay.setProgressListener(new ProgressComponent.ProgressListener() {
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
            });
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
            Toast.makeText(requireContext(), "No operation to retry", Toast.LENGTH_SHORT).show();
        }
    }
    
    /**
     * Example method showing how to use progress tracking for video processing operations
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
            handler.postDelayed(() -> {
                if (progressOverlay != null && progressOverlay.isProgressVisible()) {
                    String message = getString(R.string.faditor_processing_video) + " Trimming...";
                    updateProgress(progress, message);
                    
                    // Simulate completion
                    if (progress == 100) {
                        handler.postDelayed(() -> {
                            showSuccessMessage("Trim operation completed successfully");
                        }, 500);
                    }
                }
            }, i * 100); // Update every 100ms
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
                autoSaveManager.restoreFromCrashRecovery(projectId, 
                    new ProjectManager.EditorStateCallback() {
                        @Override
                        public void onEditorStateLoaded(EditorState state) {
                            if (state != null) {
                                editorState = state;
                                // Re-initialize editor with recovered state
                                initializeEditor();
                                Toast.makeText(requireContext(), 
                                    "Project recovered successfully", 
                                    Toast.LENGTH_SHORT).show();
                            }
                        }
                        
                        @Override
                        public void onEditorStateSaved(String projectId) {
                            // Not used in recovery
                        }
                        
                        @Override
                        public void onError(String errorMessage) {
                            Toast.makeText(requireContext(), 
                                "Failed to recover project: " + errorMessage, 
                                Toast.LENGTH_LONG).show();
                        }
                    });
            })
            .setNegativeButton(R.string.faditor_start_fresh, (dialog, which) -> {
                // Continue with current state
                dialog.dismiss();
            })
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
        if (editorState != null) {
            editorState.setPlaying(isPlaying);
        }
        
        markModified();
    }
    
    @Override
    public void onPositionChanged(long positionMs) {
        if (editorState != null) {
            editorState.setLastPlayPosition(positionMs);
        }
        
        // Update timeline position
        if (timeline != null) {
            timeline.setCurrentPosition(positionMs);
        }
        
        markModified();
    }
    
    @Override
    public void onVideoLoaded(long durationMs) {
        Log.d(TAG, "Video loaded, duration: " + durationMs + "ms");
        
        // Update project duration if needed
        if (currentProject != null && currentProject.getDuration() != durationMs) {
            currentProject.setDuration(durationMs);
            markModified();
        }
    }
    
    @Override
    public void onVideoError(String error) {
        Log.e(TAG, "Video player error: " + error);
        
        Toast.makeText(requireContext(), 
            "Video playback error: " + error, 
            Toast.LENGTH_LONG).show();
    }
    
    // TimelineComponent.TimelineListener implementation
    
    @Override
    public void onTrimRangeChanged(long startMs, long endMs) {
        Log.d(TAG, "Trim range changed: " + startMs + " - " + endMs);
        
        // Update project trim range
        if (currentProject != null) {
            VideoProject.TrimRange trimRange = new VideoProject.TrimRange(startMs, endMs);
            currentProject.setCurrentTrim(trimRange);
        }
        
        markModified();
    }
    
    @Override
    public void onTimelinePositionChanged(long positionMs) {
        // Record seek performance for monitoring
        long seekStartTime = System.currentTimeMillis();
        
        // Seek video player to new position
        if (videoPlayer != null) {
            videoPlayer.seekTo(positionMs);
        }
        
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
    public void onTimelineStateChanged() {
        // Update editor state with timeline state
        if (editorState != null && timeline != null) {
            editorState.setTimelineState(timeline.getState());
        }
        
        markModified();
    }
    
    @Override
    public void onScrubbing(boolean isScrubbing, long positionMs) {
        Log.d(TAG, "Scrubbing: " + isScrubbing + " at position: " + positionMs);
        
        if (isScrubbing) {
            // Pause video during scrubbing for smooth preview
            if (videoPlayer != null) {
                videoPlayer.pause();
            }
        }
        
        // Update video position during scrubbing
        if (videoPlayer != null) {
            long seekStartTime = System.currentTimeMillis();
            videoPlayer.seekTo(positionMs);
            
            // Record scrubbing seek performance
            long seekTime = System.currentTimeMillis() - seekStartTime;
            if (performanceMonitor != null) {
                performanceMonitor.recordSeekTime(seekTime);
            }
        }
        
        if (editorState != null) {
            editorState.setLastPlayPosition(positionMs);
        }
        
        markModified();
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
        
        // Provide haptic feedback for frame snapping
        if (getView() != null) {
            getView().performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK);
        }
        
        // Update video position to snapped frame
        if (videoPlayer != null) {
            videoPlayer.seekTo(framePositionMs);
        }
        
        if (editorState != null) {
            editorState.setLastPlayPosition(framePositionMs);
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