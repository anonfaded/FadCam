package com.fadcam.ui.faditor.utils;

import android.content.Context;
import androidx.lifecycle.LifecycleOwner;

import com.fadcam.ui.faditor.components.AutoSaveIndicator;
import com.fadcam.ui.faditor.components.CrashRecoveryDialog;
import com.fadcam.ui.faditor.models.EditorState;
import com.fadcam.ui.faditor.models.VideoProject;
import com.fadcam.ui.faditor.persistence.AutoSaveManager;
import com.fadcam.ui.faditor.persistence.ProjectManager;

/**
 * Helper class that integrates AutoSaveManager with UI components
 * Provides a simplified interface for editor fragments to use auto-save functionality
 */
public class AutoSaveHelper implements AutoSaveManager.AutoSaveListener, 
                                      CrashRecoveryDialog.CrashRecoveryListener {
    
    public interface AutoSaveHelperListener {
        void onCrashRecoveryCompleted(EditorState recoveredState);
        void onCrashRecoveryDiscarded();
        void onAutoSaveStatusChanged(boolean isSaving);
    }
    
    private final Context context;
    private final AutoSaveManager autoSaveManager;
    private final AutoSaveIndicator autoSaveIndicator;
    private final CrashRecoveryDialog crashRecoveryDialog;
    private AutoSaveHelperListener listener;
    
    public AutoSaveHelper(Context context, ProjectManager projectManager, 
                         AutoSaveIndicator autoSaveIndicator) {
        this.context = context;
        this.autoSaveManager = new AutoSaveManager(context, projectManager);
        this.autoSaveIndicator = autoSaveIndicator;
        this.crashRecoveryDialog = new CrashRecoveryDialog(context);
        
        // Set up listeners
        this.autoSaveManager.setAutoSaveListener(this);
        this.crashRecoveryDialog.setListener(this);
    }
    
    /**
     * Sets up auto-save for a project and editor state
     * Also handles lifecycle events for proper auto-save behavior
     */
    public void setupAutoSave(VideoProject project, EditorState editorState, 
                             LifecycleOwner lifecycleOwner) {
        if (project == null || editorState == null) {
            throw new IllegalArgumentException("Project and EditorState cannot be null");
        }
        
        // Register lifecycle observer for automatic save on app lifecycle events
        lifecycleOwner.getLifecycle().addObserver(autoSaveManager);
        
        // Start auto-save
        autoSaveManager.startAutoSave(project, editorState);
    }
    
    /**
     * Notifies that the project has been modified
     */
    public void onProjectModified() {
        autoSaveManager.onProjectModified();
    }
    
    /**
     * Notifies that the timeline has changed
     */
    public void onTimelineChanged() {
        autoSaveManager.onTimelineChanged();
    }
    
    /**
     * Notifies that the selected tool has changed
     */
    public void onToolChanged() {
        autoSaveManager.onToolChanged();
    }
    
    /**
     * Performs immediate save (e.g., before navigation)
     */
    public void saveImmediately() {
        autoSaveManager.saveImmediately();
    }
    
    /**
     * Updates the current project reference
     */
    public void updateProject(VideoProject project) {
        autoSaveManager.updateProject(project);
    }
    
    /**
     * Updates the current editor state reference
     */
    public void updateEditorState(EditorState editorState) {
        autoSaveManager.updateEditorState(editorState);
    }
    
    /**
     * Checks if there are unsaved changes
     */
    public boolean hasUnsavedChanges() {
        return autoSaveManager.hasUnsavedChanges();
    }
    
    /**
     * Gets the last auto-save time
     */
    public long getLastAutoSaveTime() {
        return autoSaveManager.getLastAutoSaveTime();
    }
    
    /**
     * Sets the listener for auto-save events
     */
    public void setListener(AutoSaveHelperListener listener) {
        this.listener = listener;
    }
    
    /**
     * Stops auto-save and cleans up resources
     */
    public void cleanup() {
        autoSaveManager.cleanup();
    }
    
    // AutoSaveManager.AutoSaveListener implementation
    
    @Override
    public void onAutoSaveStarted() {
        if (autoSaveIndicator != null) {
            autoSaveIndicator.showSaveState(AutoSaveIndicator.SaveState.SAVING);
        }
        if (listener != null) {
            listener.onAutoSaveStatusChanged(true);
        }
    }
    
    @Override
    public void onAutoSaveCompleted() {
        if (autoSaveIndicator != null) {
            autoSaveIndicator.showSaveState(AutoSaveIndicator.SaveState.SAVED);
        }
        if (listener != null) {
            listener.onAutoSaveStatusChanged(false);
        }
    }
    
    @Override
    public void onAutoSaveError(String error) {
        if (autoSaveIndicator != null) {
            autoSaveIndicator.showSaveState(AutoSaveIndicator.SaveState.ERROR);
        }
        if (listener != null) {
            listener.onAutoSaveStatusChanged(false);
        }
    }
    
    @Override
    public void onCrashRecoveryAvailable(String projectId) {
        // Show crash recovery dialog
        crashRecoveryDialog.showRecoveryDialog(projectId, null);
    }
    
    // CrashRecoveryDialog.CrashRecoveryListener implementation
    
    @Override
    public void onRecoverRequested(String projectId) {
        // Load the recovered editor state
        autoSaveManager.restoreFromCrashRecovery(projectId, 
            new ProjectManager.EditorStateCallback() {
                @Override
                public void onEditorStateSaved(String projectId) {
                    // Not used in recovery
                }
                
                @Override
                public void onEditorStateLoaded(EditorState editorState) {
                    if (listener != null) {
                        listener.onCrashRecoveryCompleted(editorState);
                    }
                }
                
                @Override
                public void onError(String errorMessage) {
                    // If recovery fails, treat as discard
                    if (listener != null) {
                        listener.onCrashRecoveryDiscarded();
                    }
                }
            });
    }
    
    @Override
    public void onDiscardRequested(String projectId) {
        if (listener != null) {
            listener.onCrashRecoveryDiscarded();
        }
    }
}