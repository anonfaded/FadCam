package com.fadcam.ui.faditor;

import android.os.Bundle;
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
import com.google.android.material.appbar.MaterialToolbar;
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
        
        // Clean up auto-save and show bottom navigation
        if (autoSaveManager != null) {
            autoSaveManager.stopAutoSave();
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
        // Handle back press with auto-save (Requirement 10.4, 10.5)
        Log.d(TAG, "Back pressed in editor");
        
        if (hasUnsavedChanges()) {
            showUnsavedChangesDialog();
            return true; // Handled
        } else {
            saveAndExit();
            return true; // Handled
        }
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
        
        // Set up toolbar
        if (editorToolbar != null) {
            editorToolbar.setNavigationOnClickListener(v -> onBackPressed());
            editorToolbar.setTitle(R.string.faditor_editor_title);
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
        
        // Set up button listeners
        if (saveButton != null) {
            saveButton.setOnClickListener(v -> saveProject());
        }
        if (exportButton != null) {
            exportButton.setOnClickListener(v -> exportProject());
        }
        
        // Initially hide progress overlay
        if (progressOverlay != null) {
            progressOverlay.setVisibility(View.GONE);
        }
    }
    
    private void loadProject() {
        if (projectManager == null || projectId == null) {
            return;
        }
        
        // Show loading state
        showProgress("Loading project...", false);
        
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
        
        // Update toolbar title with project name
        if (editorToolbar != null && currentProject.getProjectName() != null) {
            editorToolbar.setTitle(currentProject.getProjectName());
        }
        
        // Initialize video player
        if (videoPlayer != null && currentProject.getOriginalVideoUri() != null) {
            videoPlayer.loadVideo(currentProject.getOriginalVideoUri());
        }
        
        // Initialize timeline
        if (timeline != null) {
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
    
    private boolean hasUnsavedChanges() {
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
        
        showProgress("Saving project...", false);
        
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
        // TODO: Implement export functionality in future task
        Toast.makeText(requireContext(), 
            "Export functionality will be implemented in a future task", 
            Toast.LENGTH_SHORT).show();
    }
    
    public void saveAndExit() {
        // Immediate save before leaving (Requirement 12.2)
        if (autoSaveManager != null) {
            autoSaveManager.saveImmediately();
        }
        
        // Return to project browser (Requirement 10.4, 10.5)
        NavigationUtils.returnToBrowser(this);
    }
    
    private void showUnsavedChangesDialog() {
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
    
    // Progress methods
    
    private void showProgress(String message, boolean cancellable) {
        if (progressOverlay != null) {
            progressOverlay.showProgress(message, cancellable);
        }
    }
    
    private void hideProgress() {
        if (progressOverlay != null) {
            progressOverlay.hideProgress();
        }
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
        // Seek video player to new position
        if (videoPlayer != null) {
            videoPlayer.seekTo(positionMs);
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
}