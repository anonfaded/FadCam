package com.fadcam.ui.faditor.exceptions;

import android.content.Context;
import android.content.DialogInterface;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;

import com.fadcam.Log;
import com.fadcam.R;
import com.fadcam.ui.faditor.utils.VideoFileUtils;

/**
 * Central error handler for Faditor Mini video editor.
 * Provides consistent error handling, user-friendly messages, and recovery options.
 */
public class ErrorHandler {
    
    private static final String TAG = "FaditorErrorHandler";
    
    public interface ErrorCallback {
        void onRetry();
        void onCancel();
        void onRecoveryAction(FaditorException.RecoveryAction action);
    }
    
    /**
     * Handle FaditorException with appropriate UI feedback
     */
    public static void handleError(Context context, FaditorException exception, ErrorCallback callback) {
        Log.e(TAG, "Handling error: " + exception.toString(), exception);
        
        if (exception.hasRecoveryAction()) {
            showErrorDialogWithRecovery(context, exception, callback);
        } else {
            showSimpleErrorDialog(context, exception, callback);
        }
    }
    
    /**
     * Handle generic exceptions by converting them to FaditorException
     */
    public static void handleError(Context context, Exception exception, ErrorCallback callback) {
        FaditorException faditorException = convertToFaditorException(exception);
        handleError(context, faditorException, callback);
    }
    
    /**
     * Show error toast for non-critical errors
     */
    public static void showErrorToast(Context context, FaditorException exception) {
        Toast.makeText(context, exception.getUserFriendlyMessage(), Toast.LENGTH_LONG).show();
        Log.w(TAG, "Error toast: " + exception.toString());
    }
    
    /**
     * Show error dialog with recovery options
     */
    private static void showErrorDialogWithRecovery(Context context, FaditorException exception, ErrorCallback callback) {
        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle(getErrorTitle(context, exception.getErrorCode()));
        builder.setMessage(exception.getUserFriendlyMessage());
        builder.setCancelable(false);
        
        // Add recovery action button
        String recoveryText = getRecoveryActionText(context, exception.getRecoveryAction());
        builder.setPositiveButton(recoveryText, (dialog, which) -> {
            if (callback != null) {
                callback.onRecoveryAction(exception.getRecoveryAction());
            }
        });
        
        // Add retry button for retryable errors
        if (isRetryableError(exception.getErrorCode())) {
            builder.setNeutralButton(context.getString(R.string.retry), (dialog, which) -> {
                if (callback != null) {
                    callback.onRetry();
                }
            });
        }
        
        // Add cancel button
        builder.setNegativeButton(context.getString(android.R.string.cancel), (dialog, which) -> {
            if (callback != null) {
                callback.onCancel();
            }
        });
        
        builder.show();
    }    

    /**
     * Show simple error dialog without recovery options
     */
    private static void showSimpleErrorDialog(Context context, FaditorException exception, ErrorCallback callback) {
        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle(getErrorTitle(context, exception.getErrorCode()));
        builder.setMessage(exception.getUserFriendlyMessage());
        
        // Add retry button for retryable errors
        if (isRetryableError(exception.getErrorCode())) {
            builder.setPositiveButton(context.getString(R.string.retry), (dialog, which) -> {
                if (callback != null) {
                    callback.onRetry();
                }
            });
            builder.setNegativeButton(context.getString(android.R.string.cancel), (dialog, which) -> {
                if (callback != null) {
                    callback.onCancel();
                }
            });
        } else {
            builder.setPositiveButton(context.getString(android.R.string.ok), (dialog, which) -> {
                if (callback != null) {
                    callback.onCancel();
                }
            });
        }
        
        builder.show();
    }
    
    /**
     * Convert generic exception to FaditorException
     */
    private static FaditorException convertToFaditorException(Exception exception) {
        if (exception instanceof FaditorException) {
            return (FaditorException) exception;
        }
        
        // Analyze exception type and message to determine appropriate error code
        String message = exception.getMessage();
        if (message == null) message = "";
        
        if (exception instanceof SecurityException) {
            return new FaditorException(
                FaditorException.ErrorCode.PERMISSION_DENIED,
                "Permission denied. Please grant the required permissions.",
                FaditorException.RecoveryAction.GRANT_PERMISSIONS
            );
        }
        
        if (exception instanceof java.io.IOException) {
            if (message.contains("No space left") || message.contains("insufficient")) {
                return new FaditorException(
                    FaditorException.ErrorCode.INSUFFICIENT_STORAGE,
                    "Not enough storage space available. Please free up some space and try again.",
                    FaditorException.RecoveryAction.FREE_STORAGE_SPACE
                );
            }
            return new FaditorException(
                FaditorException.ErrorCode.STORAGE_ACCESS_DENIED,
                "Unable to access storage. Please check file permissions.",
                FaditorException.RecoveryAction.GRANT_PERMISSIONS
            );
        }
        
        if (exception instanceof IllegalArgumentException) {
            return new FaditorException(
                FaditorException.ErrorCode.INVALID_PROJECT_DATA,
                "Invalid data provided. Please check your input and try again.",
                FaditorException.RecoveryAction.ADJUST_SETTINGS
            );
        }
        
        // Default unknown error
        return new FaditorException(
            FaditorException.ErrorCode.UNKNOWN_ERROR,
            "An unexpected error occurred: " + message,
            FaditorException.RecoveryAction.RETRY
        );
    }
    
    /**
     * Get localized error title based on error code
     */
    private static String getErrorTitle(Context context, FaditorException.ErrorCode errorCode) {
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
     * Get localized recovery action text
     */
    private static String getRecoveryActionText(Context context, FaditorException.RecoveryAction action) {
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
    private static boolean isRetryableError(FaditorException.ErrorCode errorCode) {
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
     * Create video format error with supported formats list
     */
    public static FaditorException createUnsupportedFormatError(Context context, String fileName) {
        String supportedFormats = VideoFileUtils.getSupportedFormatsString();
        String message = context.getString(R.string.faditor_error_unsupported_format, supportedFormats);
        
        return new FaditorException(
            FaditorException.ErrorCode.UNSUPPORTED_VIDEO_FORMAT,
            message,
            "File: " + fileName,
            FaditorException.RecoveryAction.SELECT_DIFFERENT_FILE
        );
    }
    
    /**
     * Create trim range validation error
     */
    public static FaditorException createInvalidTrimRangeError(Context context, long startMs, long endMs, long durationMs) {
        String message = context.getString(R.string.faditor_error_invalid_trim_range);
        String details = String.format("Start: %dms, End: %dms, Duration: %dms", startMs, endMs, durationMs);
        
        return new FaditorException(
            FaditorException.ErrorCode.INVALID_TRIM_RANGE,
            message,
            details,
            FaditorException.RecoveryAction.ADJUST_SETTINGS
        );
    }
    
    /**
     * Create export settings validation error
     */
    public static FaditorException createInvalidExportSettingsError(Context context, String reason) {
        String message = context.getString(R.string.faditor_error_invalid_export_settings, reason);
        
        return new FaditorException(
            FaditorException.ErrorCode.INVALID_EXPORT_SETTINGS,
            message,
            FaditorException.RecoveryAction.ADJUST_SETTINGS
        );
    }
    
    /**
     * Create processing failure error
     */
    public static FaditorException createProcessingError(Context context, String operation, Throwable cause) {
        String message = context.getString(R.string.faditor_error_processing_failed, operation);
        String details = cause != null ? cause.getMessage() : "Unknown cause";
        
        return new FaditorException(
            FaditorException.ErrorCode.PROCESSING_FAILED,
            message,
            details,
            FaditorException.RecoveryAction.RETRY,
            cause
        );
    }
    
    /**
     * Create auto-save failure error
     */
    public static FaditorException createAutoSaveError(Context context, Throwable cause) {
        String message = context.getString(R.string.faditor_error_autosave_failed);
        String details = cause != null ? cause.getMessage() : "Unknown cause";
        
        return new FaditorException(
            FaditorException.ErrorCode.AUTO_SAVE_FAILED,
            message,
            details,
            FaditorException.RecoveryAction.RETRY,
            cause
        );
    }
}