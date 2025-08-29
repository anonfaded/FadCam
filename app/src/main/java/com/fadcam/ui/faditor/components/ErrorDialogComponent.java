package com.fadcam.ui.faditor.components;

import android.content.Context;
import android.content.DialogInterface;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;

import com.fadcam.R;
import com.fadcam.ui.faditor.exceptions.FaditorException;

/**
 * Specialized error dialog component for Faditor Mini.
 * Provides consistent error presentation with recovery options.
 */
public class ErrorDialogComponent {
    
    public interface ErrorDialogCallback {
        void onRetry();
        void onCancel();
        void onRecoveryAction(FaditorException.RecoveryAction action);
        void onShowDetails();
    }
    
    private final Context context;
    private AlertDialog currentDialog;
    
    public ErrorDialogComponent(Context context) {
        this.context = context;
    }
    
    /**
     * Show error dialog with full options
     */
    public void showErrorDialog(FaditorException exception, ErrorDialogCallback callback) {
        dismissCurrentDialog();
        
        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        
        // Set title based on error type
        builder.setTitle(getErrorTitle(exception.getErrorCode()));
        
        // Set message
        builder.setMessage(exception.getUserFriendlyMessage());
        
        // Configure buttons based on error type and recovery options
        configureDialogButtons(builder, exception, callback);
        
        // Show technical details option if available
        if (exception.getTechnicalDetails() != null) {
            builder.setNeutralButton("Show Details", (dialog, which) -> {
                if (callback != null) {
                    callback.onShowDetails();
                }
                showTechnicalDetailsDialog(exception);
            });
        }
        
        currentDialog = builder.create();
        currentDialog.setCancelable(false);
        currentDialog.show();
    }
    
    /**
     * Show simple error toast-style dialog
     */
    public void showSimpleError(String title, String message, Runnable onDismiss) {
        dismissCurrentDialog();
        
        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle(title);
        builder.setMessage(message);
        builder.setPositiveButton(android.R.string.ok, (dialog, which) -> {
            if (onDismiss != null) {
                onDismiss.run();
            }
        });
        
        currentDialog = builder.create();
        currentDialog.show();
    }
    
    /**
     * Show technical details dialog
     */
    private void showTechnicalDetailsDialog(FaditorException exception) {
        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle("Technical Details");
        
        StringBuilder details = new StringBuilder();
        details.append("Error Code: ").append(exception.getErrorCode()).append("\n\n");
        
        if (exception.getTechnicalDetails() != null) {
            details.append("Details: ").append(exception.getTechnicalDetails()).append("\n\n");
        }
        
        if (exception.getCause() != null) {
            details.append("Cause: ").append(exception.getCause().getMessage()).append("\n\n");
        }
        
        details.append("Recovery Action: ").append(exception.getRecoveryAction());
        
        builder.setMessage(details.toString());
        builder.setPositiveButton(android.R.string.ok, null);
        builder.show();
    } 
   
    /**
     * Configure dialog buttons based on error type
     */
    private void configureDialogButtons(AlertDialog.Builder builder, FaditorException exception, ErrorDialogCallback callback) {
        // Primary action button (recovery or OK)
        if (exception.hasRecoveryAction()) {
            String recoveryText = getRecoveryActionText(exception.getRecoveryAction());
            builder.setPositiveButton(recoveryText, (dialog, which) -> {
                if (callback != null) {
                    callback.onRecoveryAction(exception.getRecoveryAction());
                }
            });
        } else {
            builder.setPositiveButton(android.R.string.ok, (dialog, which) -> {
                if (callback != null) {
                    callback.onCancel();
                }
            });
        }
        
        // Retry button for retryable errors
        if (isRetryableError(exception.getErrorCode())) {
            if (exception.hasRecoveryAction()) {
                // If we have a recovery action, retry becomes the negative button
                builder.setNegativeButton(context.getString(R.string.retry), (dialog, which) -> {
                    if (callback != null) {
                        callback.onRetry();
                    }
                });
            } else {
                // If no recovery action, retry becomes the positive button
                builder.setPositiveButton(context.getString(R.string.retry), (dialog, which) -> {
                    if (callback != null) {
                        callback.onRetry();
                    }
                });
                builder.setNegativeButton(android.R.string.cancel, (dialog, which) -> {
                    if (callback != null) {
                        callback.onCancel();
                    }
                });
            }
        } else if (exception.hasRecoveryAction()) {
            // Cancel button when we have recovery action but no retry
            builder.setNegativeButton(android.R.string.cancel, (dialog, which) -> {
                if (callback != null) {
                    callback.onCancel();
                }
            });
        }
    }
    
    /**
     * Get error title based on error code
     */
    private String getErrorTitle(FaditorException.ErrorCode errorCode) {
        switch (errorCode) {
            case UNSUPPORTED_VIDEO_FORMAT:
            case CORRUPTED_VIDEO_FILE:
            case VIDEO_FILE_NOT_FOUND:
            case VIDEO_FILE_ACCESS_DENIED:
                return context.getString(R.string.faditor_error_video_file_title);
                
            case PROCESSING_FAILED:
            case ENCODING_FAILED:
            case DECODING_FAILED:
            case OPENGL_ERROR:
            case MEDIACODEC_ERROR:
                return context.getString(R.string.faditor_error_processing_title);
                
            case INVALID_TRIM_RANGE:
            case INVALID_EXPORT_SETTINGS:
            case INVALID_PROJECT_DATA:
                return context.getString(R.string.faditor_error_validation_title);
                
            case INSUFFICIENT_STORAGE:
            case STORAGE_ACCESS_DENIED:
            case TEMP_FILE_CREATION_FAILED:
                return context.getString(R.string.faditor_error_storage_title);
                
            case PROJECT_LOAD_FAILED:
            case PROJECT_SAVE_FAILED:
            case PROJECT_CORRUPTED:
            case MEDIA_REFERENCE_BROKEN:
                return context.getString(R.string.faditor_error_project_title);
                
            case AUTO_SAVE_FAILED:
            case STATE_RECOVERY_FAILED:
            case BACKUP_CREATION_FAILED:
                return context.getString(R.string.faditor_error_autosave_title);
                
            case PERMISSION_DENIED:
                return context.getString(R.string.faditor_error_permission_title);
                
            case DEVICE_NOT_SUPPORTED:
                return context.getString(R.string.faditor_error_device_title);
                
            default:
                return context.getString(R.string.faditor_error_general_title);
        }
    }
    
    /**
     * Get recovery action text
     */
    private String getRecoveryActionText(FaditorException.RecoveryAction action) {
        switch (action) {
            case RETRY:
                return context.getString(R.string.retry);
            case SELECT_DIFFERENT_FILE:
                return context.getString(R.string.faditor_recovery_select_different_file);
            case ADJUST_SETTINGS:
                return context.getString(R.string.faditor_recovery_adjust_settings);
            case FREE_STORAGE_SPACE:
                return context.getString(R.string.faditor_recovery_free_storage);
            case GRANT_PERMISSIONS:
                return context.getString(R.string.faditor_recovery_grant_permissions);
            case RESTART_APP:
                return context.getString(R.string.faditor_recovery_restart_app);
            case CONTACT_SUPPORT:
                return context.getString(R.string.faditor_recovery_contact_support);
            default:
                return context.getString(android.R.string.ok);
        }
    }
    
    /**
     * Check if error is retryable
     */
    private boolean isRetryableError(FaditorException.ErrorCode errorCode) {
        switch (errorCode) {
            case PROCESSING_FAILED:
            case ENCODING_FAILED:
            case DECODING_FAILED:
            case PROJECT_LOAD_FAILED:
            case PROJECT_SAVE_FAILED:
            case AUTO_SAVE_FAILED:
            case TEMP_FILE_CREATION_FAILED:
            case UNKNOWN_ERROR:
                return true;
            default:
                return false;
        }
    }
    
    /**
     * Dismiss current dialog if showing
     */
    public void dismissCurrentDialog() {
        if (currentDialog != null && currentDialog.isShowing()) {
            currentDialog.dismiss();
            currentDialog = null;
        }
    }
    
    /**
     * Check if dialog is currently showing
     */
    public boolean isDialogShowing() {
        return currentDialog != null && currentDialog.isShowing();
    }
}