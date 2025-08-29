package com.fadcam.ui.faditor.examples;

import android.content.Context;
import android.net.Uri;

import com.fadcam.ui.faditor.components.ErrorDialogComponent;
import com.fadcam.ui.faditor.exceptions.FaditorException;
import com.fadcam.ui.faditor.recovery.RecoveryManager;
import com.fadcam.ui.faditor.utils.ErrorHandlingUtils;
import com.fadcam.ui.faditor.utils.VideoFileUtils;
import com.fadcam.ui.faditor.validation.ValidationUtils;

/**
 * Example demonstrating comprehensive error handling in Faditor Mini.
 * Shows how to use the error handling system for various scenarios.
 */
public class ErrorHandlingExample {
    
    private final Context context;
    private final ErrorDialogComponent errorDialog;
    private final RecoveryManager recoveryManager;
    
    public ErrorHandlingExample(Context context) {
        this.context = context;
        this.errorDialog = new ErrorDialogComponent(context);
        this.recoveryManager = new RecoveryManager(context);
    }
    
    /**
     * Example: Video file validation with error handling
     */
    public void validateVideoFileExample(Uri videoUri) {
        ErrorHandlingUtils.executeWithErrorHandling(context, () -> {
            // This will throw FaditorException if validation fails
            VideoFileUtils.validateVideoFileWithErrors(context, videoUri);
            
            // If we get here, validation passed
            onVideoValidationSuccess(videoUri);
            
        }, new ErrorDialogComponent.ErrorDialogCallback() {
            @Override
            public void onRetry() {
                // User chose to retry - could re-attempt validation
                validateVideoFileExample(videoUri);
            }
            
            @Override
            public void onCancel() {
                // User cancelled - return to previous screen
                onValidationCancelled();
            }
            
            @Override
            public void onRecoveryAction(FaditorException.RecoveryAction action) {
                handleRecoveryAction(action);
            }
            
            @Override
            public void onShowDetails() {
                // Details are handled automatically by ErrorDialogComponent
            }
        });
    }
    
    /**
     * Example: Trim range validation with error handling
     */
    public void validateTrimRangeExample(long startMs, long endMs, long durationMs) {
        try {
            ValidationUtils.validateTrimRange(context, startMs, endMs, durationMs);
            onTrimRangeValid(startMs, endMs);
            
        } catch (FaditorException e) {
            // Show error dialog with recovery options
            errorDialog.showErrorDialog(e, new ErrorDialogComponent.ErrorDialogCallback() {
                @Override
                public void onRetry() {
                    // Could show trim adjustment UI
                    showTrimAdjustmentUI();
                }
                
                @Override
                public void onCancel() {
                    // Reset to original trim range
                    resetTrimRange();
                }
                
                @Override
                public void onRecoveryAction(FaditorException.RecoveryAction action) {
                    if (action == FaditorException.RecoveryAction.ADJUST_SETTINGS) {
                        showTrimAdjustmentUI();
                    }
                }
                
                @Override
                public void onShowDetails() {
                    // Handled automatically
                }
            });
        }
    }
    
    /**
     * Example: Auto-save failure handling
     */
    public void handleAutoSaveFailureExample(String projectId, Exception cause) {
        FaditorException autoSaveError = ErrorHandlingUtils.createAutoSaveError(context, cause);
        
        // For auto-save failures, we typically show a toast rather than blocking dialog
        ErrorHandlingUtils.handleErrorWithToast(context, autoSaveError);
        
        // Also attempt recovery
        recoveryManager.handleAutoSaveFailure(projectId, null, null, cause);
    }
    
    /**
     * Example: Processing error with retry logic
     */
    public void handleProcessingErrorExample(String operation, Exception cause) {
        FaditorException processingError = ErrorHandlingUtils.createProcessingError(context, operation, cause);
        
        errorDialog.showErrorDialog(processingError, new ErrorDialogComponent.ErrorDialogCallback() {
            @Override
            public void onRetry() {
                // Retry the processing operation
                retryProcessingOperation(operation);
            }
            
            @Override
            public void onCancel() {
                // Cancel processing and return to editor
                cancelProcessing();
            }
            
            @Override
            public void onRecoveryAction(FaditorException.RecoveryAction action) {
                // Processing errors typically have RETRY as recovery action
                if (action == FaditorException.RecoveryAction.RETRY) {
                    retryProcessingOperation(operation);
                }
            }
            
            @Override
            public void onShowDetails() {
                // Show technical details for debugging
            }
        });
    }
    
    /**
     * Example: Device capability validation
     */
    public void validateDeviceCapabilitiesExample() {
        try {
            ErrorHandlingUtils.validateDeviceCapabilities(context);
            onDeviceCapabilitiesValid();
            
        } catch (FaditorException e) {
            errorDialog.showErrorDialog(e, new ErrorDialogComponent.ErrorDialogCallback() {
                @Override
                public void onRetry() {
                    // Could try again after user frees memory/storage
                    validateDeviceCapabilitiesExample();
                }
                
                @Override
                public void onCancel() {
                    // Exit editor or show alternative options
                    exitEditor();
                }
                
                @Override
                public void onRecoveryAction(FaditorException.RecoveryAction action) {
                    handleRecoveryAction(action);
                }
                
                @Override
                public void onShowDetails() {
                    // Show device info and requirements
                }
            });
        }
    }
    
    /**
     * Example: Crash recovery on app startup
     */
    public void handleCrashRecoveryExample() {
        if (recoveryManager.isCrashRecoveryNeeded()) {
            recoveryManager.attemptCrashRecovery(new RecoveryManager.RecoveryCallback() {
                @Override
                public void onRecoverySuccess(com.fadcam.ui.faditor.models.VideoProject project, 
                                            com.fadcam.ui.faditor.models.EditorState editorState) {
                    // Show recovery dialog and restore project
                    showCrashRecoveryDialog(project, editorState);
                }
                
                @Override
                public void onRecoveryFailed(FaditorException exception) {
                    // Show error and start fresh
                    ErrorHandlingUtils.handleErrorWithToast(context, exception);
                    startFresh();
                }
                
                @Override
                public void onNoRecoveryNeeded() {
                    // Normal startup
                    normalStartup();
                }
            });
        }
    }
    
    // Helper methods for demonstration
    private void onVideoValidationSuccess(Uri videoUri) {
        // Proceed with video loading
    }
    
    private void onValidationCancelled() {
        // Return to file selection
    }
    
    private void onTrimRangeValid(long startMs, long endMs) {
        // Apply trim range
    }
    
    private void showTrimAdjustmentUI() {
        // Show UI to adjust trim handles
    }
    
    private void resetTrimRange() {
        // Reset to full video duration
    }
    
    private void retryProcessingOperation(String operation) {
        // Retry the failed operation
    }
    
    private void cancelProcessing() {
        // Cancel and return to editor
    }
    
    private void onDeviceCapabilitiesValid() {
        // Proceed with editor initialization
    }
    
    private void exitEditor() {
        // Exit to main app
    }
    
    private void showCrashRecoveryDialog(com.fadcam.ui.faditor.models.VideoProject project, 
                                       com.fadcam.ui.faditor.models.EditorState editorState) {
        // Show dialog asking user if they want to recover
    }
    
    private void startFresh() {
        // Start with clean state
    }
    
    private void normalStartup() {
        // Normal app startup
    }
    
    private void handleRecoveryAction(FaditorException.RecoveryAction action) {
        switch (action) {
            case SELECT_DIFFERENT_FILE:
                // Open file picker
                break;
            case ADJUST_SETTINGS:
                // Open settings dialog
                break;
            case FREE_STORAGE_SPACE:
                // Show storage management options
                break;
            case GRANT_PERMISSIONS:
                // Request permissions
                break;
            case RESTART_APP:
                // Restart application
                break;
            case CONTACT_SUPPORT:
                // Open support contact
                break;
        }
    }
}