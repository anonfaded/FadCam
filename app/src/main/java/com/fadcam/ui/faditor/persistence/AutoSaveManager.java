package com.fadcam.ui.faditor.persistence;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleEventObserver;
import androidx.lifecycle.LifecycleOwner;

import com.fadcam.ui.faditor.exceptions.ErrorHandler;
import com.fadcam.ui.faditor.exceptions.FaditorException;
import com.fadcam.ui.faditor.models.VideoProject;
import com.fadcam.ui.faditor.models.EditorState;
import com.fadcam.ui.faditor.recovery.RecoveryManager;
import com.fadcam.ui.faditor.utils.PerformanceMonitor;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Handles continuous auto-saving and state recovery for video editing projects.
 * Implements requirements 12.1-12.6 for seamless auto-save functionality.
 */
public class AutoSaveManager implements LifecycleEventObserver {
    
    private static final int AUTO_SAVE_DELAY_MS = 5000; // 5 seconds (Requirement 12.1)
    private static final String TAG = "AutoSaveManager";
    
    public interface AutoSaveListener {
        void onAutoSaveStarted();
        void onAutoSaveCompleted();
        void onAutoSaveError(String error);
        void onCrashRecoveryAvailable(String projectId);
    }
    
    private final Context context;
    private final ProjectManager projectManager;
    private final RecoveryManager recoveryManager;
    private final Handler mainHandler;
    private final Handler backgroundHandler;
    private final HandlerThread backgroundThread;
    private final AtomicBoolean isAutoSaveEnabled;
    private final AtomicBoolean isSaving;
    
    // Performance monitoring
    private final PerformanceMonitor performanceMonitor;
    
    private VideoProject currentProject;
    private EditorState currentEditorState;
    private AutoSaveListener listener;
    private Runnable autoSaveRunnable;
    private boolean hasUnsavedChanges;
    private long lastAutoSaveTime;
    private int consecutiveFailures;
    
    public AutoSaveManager(Context context, ProjectManager projectManager) {
        this.context = context;
        this.projectManager = projectManager;
        this.recoveryManager = new RecoveryManager(context);
        this.mainHandler = new Handler(Looper.getMainLooper());
        
        // Create background thread for auto-save operations to avoid UI blocking
        this.backgroundThread = new HandlerThread("AutoSaveThread");
        this.backgroundThread.start();
        this.backgroundHandler = new Handler(backgroundThread.getLooper());
        
        this.isAutoSaveEnabled = new AtomicBoolean(false);
        this.isSaving = new AtomicBoolean(false);
        this.hasUnsavedChanges = false;
        this.lastAutoSaveTime = 0;
        this.consecutiveFailures = 0;
        
        // Initialize performance monitoring
        this.performanceMonitor = PerformanceMonitor.getInstance();
        
        initializeAutoSaveRunnable();
    }
    
    /**
     * Starts auto-save for the given project and editor state
     * Requirement 12.1: Auto-save within 5 seconds of any edit
     */
    public void startAutoSave(VideoProject project, EditorState editorState) {
        if (project == null || editorState == null) {
            throw new IllegalArgumentException("Project and EditorState cannot be null");
        }
        
        this.currentProject = project;
        this.currentEditorState = editorState;
        this.isAutoSaveEnabled.set(true);
        this.hasUnsavedChanges = false;
        
        // Check for crash recovery
        checkForCrashRecovery(project.getProjectId());
        
        // Schedule initial auto-save
        scheduleAutoSave();
    }
    
    /**
     * Stops auto-save functionality
     */
    public void stopAutoSave() {
        isAutoSaveEnabled.set(false);
        cancelScheduledAutoSave();
    }
    
    /**
     * Performs immediate save operation
     * Requirement 12.2: Immediate save on navigation and app lifecycle events
     */
    public void saveImmediately() {
        if (!isAutoSaveEnabled.get() || currentProject == null || currentEditorState == null) {
            return;
        }
        
        // Cancel any scheduled auto-save
        cancelScheduledAutoSave();
        
        // Perform immediate save
        performAutoSave(true);
    }
    
    /**
     * Schedules an auto-save operation
     * Requirement 12.1: Auto-save within 5 seconds
     */
    public void scheduleAutoSave() {
        if (!isAutoSaveEnabled.get()) {
            return;
        }
        
        // Cancel any existing scheduled save
        cancelScheduledAutoSave();
        
        // Schedule new auto-save
        mainHandler.postDelayed(autoSaveRunnable, AUTO_SAVE_DELAY_MS);
    }
    
    /**
     * Notifies that the project has been modified
     * Triggers auto-save scheduling
     */
    public void onProjectModified() {
        if (!isAutoSaveEnabled.get()) {
            return;
        }
        
        hasUnsavedChanges = true;
        if (currentProject != null) {
            currentProject.updateLastModified();
        }
        if (currentEditorState != null) {
            currentEditorState.markModified();
        }
        
        scheduleAutoSave();
    }
    
    /**
     * Notifies that the timeline state has changed
     */
    public void onTimelineChanged() {
        onProjectModified();
    }
    
    /**
     * Notifies that the selected tool has changed
     */
    public void onToolChanged() {
        onProjectModified();
    }
    
    /**
     * Sets the auto-save listener for feedback
     * Requirement 12.6: Subtle visual feedback
     */
    public void setAutoSaveListener(AutoSaveListener listener) {
        this.listener = listener;
    }
    
    /**
     * Checks if there are unsaved changes
     */
    public boolean hasUnsavedChanges() {
        return hasUnsavedChanges || 
               (currentProject != null && currentProject.hasUnsavedChanges()) ||
               (currentEditorState != null && currentEditorState.needsSaving());
    }
    
    /**
     * Gets the last auto-save time
     */
    public long getLastAutoSaveTime() {
        return lastAutoSaveTime;
    }
    
    /**
     * Lifecycle event handling
     * Requirement 12.2, 12.3: Handle app lifecycle events
     */
    @Override
    public void onStateChanged(LifecycleOwner source, Lifecycle.Event event) {
        switch (event) {
            case ON_PAUSE:
                // App is being backgrounded - save immediately
                saveImmediately();
                break;
                
            case ON_STOP:
                // App is being stopped - ensure everything is saved
                saveImmediately();
                break;
                
            case ON_DESTROY:
                // Clean up resources
                stopAutoSave();
                break;
                
            case ON_RESUME:
                // App is resuming - check for crash recovery
                if (currentProject != null) {
                    checkForCrashRecovery(currentProject.getProjectId());
                }
                break;
        }
    }
    
    // Private methods
    
    private void initializeAutoSaveRunnable() {
        autoSaveRunnable = () -> {
            if (isAutoSaveEnabled.get() && hasUnsavedChanges()) {
                performAutoSave(false);
            }
        };
    }
    
    private void performAutoSave(boolean isImmediate) {
        if (isSaving.get()) {
            // Already saving, skip this attempt
            return;
        }
        
        if (currentProject == null || currentEditorState == null) {
            return;
        }
        
        isSaving.set(true);
        
        // Start performance monitoring for auto-save operation
        performanceMonitor.startOperation("auto_save");
        
        // Notify listener that auto-save is starting (on main thread)
        mainHandler.post(() -> {
            if (listener != null) {
                listener.onAutoSaveStarted();
            }
        });
        
        // Perform save operations on background thread to avoid UI blocking
        backgroundHandler.post(() -> {
            // Save project first
            projectManager.saveProject(currentProject, new ProjectManager.ProjectCallback() {
                @Override
                public void onProjectSaved(String projectId) {
                    // Project saved successfully, now save editor state
                    projectManager.saveEditorState(projectId, currentEditorState, 
                        new ProjectManager.EditorStateCallback() {
                            @Override
                            public void onEditorStateSaved(String projectId) {
                                // Both saves completed successfully
                                performanceMonitor.endOperation("auto_save");
                                onAutoSaveCompleted(isImmediate);
                            }
                            
                            @Override
                            public void onEditorStateLoaded(EditorState editorState) {
                                // Not used in save operation
                            }
                            
                            @Override
                            public void onError(String errorMessage) {
                                performanceMonitor.endOperation("auto_save");
                                onAutoSaveError(errorMessage);
                            }
                        });
                }
                
                @Override
                public void onProjectLoaded(VideoProject project) {
                    // Not used in save operation
                }
                
                @Override
                public void onError(String errorMessage) {
                    performanceMonitor.endOperation("auto_save");
                    onAutoSaveError(errorMessage);
                }
            });
        });
    }
    
    private void onAutoSaveCompleted(boolean isImmediate) {
        isSaving.set(false);
        hasUnsavedChanges = false;
        lastAutoSaveTime = System.currentTimeMillis();
        
        // Clear unsaved changes flags
        if (currentProject != null) {
            currentProject.markSaved();
        }
        if (currentEditorState != null) {
            currentEditorState.clearUnsavedChanges();
        }
        
        // Notify listener on main thread
        mainHandler.post(() -> {
            if (listener != null) {
                listener.onAutoSaveCompleted();
            }
        });
        
        // Schedule next auto-save if not immediate and still enabled
        if (!isImmediate && isAutoSaveEnabled.get()) {
            scheduleAutoSave();
        }
    }
    
    private void onAutoSaveError(String errorMessage) {
        isSaving.set(false);
        
        // Notify listener on main thread
        mainHandler.post(() -> {
            if (listener != null) {
                listener.onAutoSaveError(errorMessage);
            }
        });
        
        // Retry auto-save after a delay if still enabled
        if (isAutoSaveEnabled.get()) {
            mainHandler.postDelayed(() -> {
                if (hasUnsavedChanges()) {
                    performAutoSave(false);
                }
            }, AUTO_SAVE_DELAY_MS);
        }
    }
    
    private void cancelScheduledAutoSave() {
        if (autoSaveRunnable != null) {
            mainHandler.removeCallbacks(autoSaveRunnable);
        }
    }
    
    /**
     * Checks for crash recovery data
     * Requirement 12.4: Recover last saved state when app crashes
     */
    private void checkForCrashRecovery(String projectId) {
        if (projectId == null) {
            return;
        }
        
        // Load the last saved editor state to check if recovery is needed
        projectManager.loadEditorState(projectId, new ProjectManager.EditorStateCallback() {
            @Override
            public void onEditorStateSaved(String projectId) {
                // Not used in load operation
            }
            
            @Override
            public void onEditorStateLoaded(EditorState editorState) {
                // Check if the loaded state indicates unsaved changes
                if (editorState != null && editorState.isHasUnsavedChanges()) {
                    // Notify listener that crash recovery is available
                    if (listener != null) {
                        listener.onCrashRecoveryAvailable(projectId);
                    }
                }
            }
            
            @Override
            public void onError(String errorMessage) {
                // Ignore errors during crash recovery check
            }
        });
    }
    
    /**
     * Restores the editor state from crash recovery
     * Requirement 12.5: Restore exact editing state
     */
    public void restoreFromCrashRecovery(String projectId, 
                                       ProjectManager.EditorStateCallback callback) {
        projectManager.loadEditorState(projectId, callback);
    }
    
    /**
     * Updates the current editor state reference
     * Should be called when editor state changes
     */
    public void updateEditorState(EditorState editorState) {
        this.currentEditorState = editorState;
    }
    
    /**
     * Updates the current project reference
     * Should be called when project changes
     */
    public void updateProject(VideoProject project) {
        this.currentProject = project;
    }
    
    /**
     * Cleanup resources when manager is no longer needed
     */
    public void cleanup() {
        stopAutoSave();
        
        // Clean up background thread
        if (backgroundThread != null) {
            backgroundThread.quitSafely();
            try {
                backgroundThread.join(1000); // Wait up to 1 second for thread to finish
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        
        currentProject = null;
        currentEditorState = null;
        listener = null;
    }
    
    /**
     * Get auto-save performance metrics
     */
    public PerformanceMonitor.PerformanceMetric getAutoSavePerformanceMetric() {
        return performanceMonitor.getMetric("auto_save");
    }
    
    /**
     * Check if auto-save performance is acceptable (not blocking UI)
     */
    public boolean isAutoSavePerformanceAcceptable() {
        PerformanceMonitor.PerformanceMetric metric = getAutoSavePerformanceMetric();
        if (metric != null) {
            // Auto-save should complete within 1 second to avoid perceived UI blocking
            return metric.getAverageTimeMs() < 1000.0;
        }
        return true; // No data available, assume acceptable
    }
}