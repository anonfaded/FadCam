package com.fadcam.ui.faditor.utils;

import android.content.Context;
import android.widget.Toast;

import androidx.fragment.app.Fragment;

import com.fadcam.Log;
import com.fadcam.ui.faditor.components.ErrorDialogComponent;
import com.fadcam.ui.faditor.exceptions.ErrorHandler;
import com.fadcam.ui.faditor.exceptions.FaditorException;

/**
 * Utility class for consistent error handling across Faditor Mini components.
 * Provides centralized error handling with appropriate UI feedback.
 */
public class ErrorHandlingUtils {
    
    private static final String TAG = "ErrorHandlingUtils";
    
    /**
     * Handle error with appropriate UI feedback based on severity
     */
    public static void handleError(Context context, Exception exception, ErrorDialogComponent.ErrorDialogCallback callback) {
        if (exception instanceof FaditorException) {
            FaditorException faditorException = (FaditorException) exception;
            
            // Log the error
            Log.e(TAG, "Handling FaditorException: " + faditorException.toString(), faditorException);
            
            // Show appropriate UI based on error severity
            if (isCriticalError(faditorException.getErrorCode())) {
                showErrorDialog(context, faditorException, callback);
            } else if (isWarningError(faditorException.getErrorCode())) {
                showErrorToast(context, faditorException);
                if (callback != null) {
                    callback.onCancel(); // Continue with default behavior
                }
            } else {
                showErrorDialog(context, faditorException, callback);
            }
        } else {
            // Convert generic exception to FaditorException
            FaditorException faditorException = convertToFaditorException(exception);
            Log.e(TAG, "Handling generic exception as FaditorException: " + faditorException.toString(), exception);
            showErrorDialog(context, faditorException, callback);
        }
    }
    
    /**
     * Handle error with simple toast for non-critical errors
     */
    public static void handleErrorWithToast(Context context, Exception exception) {
        if (exception instanceof FaditorException) {
            FaditorException faditorException = (FaditorException) exception;
            showErrorToast(context, faditorException);
        } else {
            FaditorException faditorException = convertToFaditorException(exception);
            showErrorToast(context, faditorException);
        }
        
        Log.w(TAG, "Handled error with toast: " + exception.getMessage());
    }
    
    /**
     * Show error dialog using ErrorDialogComponent
     */
    private static void showErrorDialog(Context context, FaditorException exception, ErrorDialogComponent.ErrorDialogCallback callback) {
        ErrorDialogComponent errorDialog = new ErrorDialogComponent(context);
        errorDialog.showErrorDialog(exception, callback);
    }
    
    /**
     * Show error toast for minor errors
     */
    private static void showErrorToast(Context context, FaditorException exception) {
        Toast.makeText(context, exception.getUserFriendlyMessage(), Toast.LENGTH_LONG).show();
    }
    
    /**
     * Check if error is critical and requires dialog
     */
    private static boolean isCriticalError(FaditorException.ErrorCode errorCode) {
        switch (errorCode) {
            case UNSUPPORTED_VIDEO_FORMAT:
            case CORRUPTED_VIDEO_FILE:
            case VIDEO_FILE_NOT_FOUND:
            case VIDEO_FILE_ACCESS_DENIED:
            case PROCESSING_FAILED:
            case ENCODING_FAILED:
            case DECODING_FAILED:
            case INVALID_TRIM_RANGE:
            case INVALID_EXPORT_SETTINGS:
            case INSUFFICIENT_STORAGE:
            case PROJECT_LOAD_FAILED:
            case PROJECT_SAVE_FAILED:
            case PROJECT_CORRUPTED:
            case PERMISSION_DENIED:
            case DEVICE_NOT_SUPPORTED:
                return true;
            default:
                return false;
        }
    }
    
    /**
     * Check if error is a warning that can be shown as toast
     */
    private static boolean isWarningError(FaditorException.ErrorCode errorCode) {
        switch (errorCode) {
            case AUTO_SAVE_FAILED:
            case TEMP_FILE_CREATION_FAILED:
            case MEDIA_REFERENCE_BROKEN:
                return true;
            default:
                return false;
        }
    }    

    /**
     * Convert generic exception to FaditorException
     */
    private static FaditorException convertToFaditorException(Exception exception) {
        if (exception instanceof FaditorException) {
            return (FaditorException) exception;
        }
        
        String message = exception.getMessage();
        if (message == null) message = exception.getClass().getSimpleName();
        
        // Analyze exception type to determine appropriate error code
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
        
        if (message.contains("OutOfMemoryError") || message.contains("out of memory")) {
            return new FaditorException(
                FaditorException.ErrorCode.DEVICE_NOT_SUPPORTED,
                "Not enough memory available. Try with a smaller video file.",
                FaditorException.RecoveryAction.SELECT_DIFFERENT_FILE
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
     * Create validation error for trim range
     */
    public static FaditorException createTrimValidationError(Context context, long startMs, long endMs, long durationMs) {
        return ErrorHandler.createInvalidTrimRangeError(context, startMs, endMs, durationMs);
    }
    
    /**
     * Create validation error for export settings
     */
    public static FaditorException createExportValidationError(Context context, String reason) {
        return ErrorHandler.createInvalidExportSettingsError(context, reason);
    }
    
    /**
     * Create processing error
     */
    public static FaditorException createProcessingError(Context context, String operation, Throwable cause) {
        return ErrorHandler.createProcessingError(context, operation, cause);
    }
    
    /**
     * Create auto-save error
     */
    public static FaditorException createAutoSaveError(Context context, Throwable cause) {
        return ErrorHandler.createAutoSaveError(context, cause);
    }
    
    /**
     * Create unsupported format error
     */
    public static FaditorException createUnsupportedFormatError(Context context, String fileName) {
        return ErrorHandler.createUnsupportedFormatError(context, fileName);
    }
    
    /**
     * Wrap operation with error handling
     */
    public static void executeWithErrorHandling(Context context, RiskyOperation operation, ErrorDialogComponent.ErrorDialogCallback callback) {
        try {
            operation.execute();
        } catch (Exception e) {
            handleError(context, e, callback);
        }
    }
    
    /**
     * Wrap operation with toast error handling
     */
    public static void executeWithToastErrorHandling(Context context, RiskyOperation operation) {
        try {
            operation.execute();
        } catch (Exception e) {
            handleErrorWithToast(context, e);
        }
    }
    
    /**
     * Interface for risky operations
     */
    public interface RiskyOperation {
        void execute() throws Exception;
    }
    
    /**
     * Log error for debugging purposes
     */
    public static void logError(String tag, String message, Exception exception) {
        if (exception instanceof FaditorException) {
            FaditorException faditorException = (FaditorException) exception;
            Log.e(tag, message + " - " + faditorException.toString(), faditorException);
        } else {
            Log.e(tag, message, exception);
        }
    }
    
    /**
     * Check if device has sufficient resources for video processing
     */
    public static void validateDeviceCapabilities(Context context) throws FaditorException {
        // Check available memory
        Runtime runtime = Runtime.getRuntime();
        long maxMemory = runtime.maxMemory();
        long usedMemory = runtime.totalMemory() - runtime.freeMemory();
        long availableMemory = maxMemory - usedMemory;
        
        // Require at least 100MB of available memory
        if (availableMemory < 100 * 1024 * 1024) {
            throw new FaditorException(
                FaditorException.ErrorCode.DEVICE_NOT_SUPPORTED,
                "Insufficient memory available for video processing. Please close other apps and try again.",
                FaditorException.RecoveryAction.RESTART_APP
            );
        }
        
        // Check storage space
        long availableStorage = context.getCacheDir().getFreeSpace();
        if (availableStorage < 500 * 1024 * 1024) { // 500MB minimum
            throw new FaditorException(
                FaditorException.ErrorCode.INSUFFICIENT_STORAGE,
                "Insufficient storage space available. Please free up at least 500MB and try again.",
                FaditorException.RecoveryAction.FREE_STORAGE_SPACE
            );
        }
    }
}