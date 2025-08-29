package com.fadcam.ui.faditor.exceptions;

/**
 * Base exception class for Faditor Mini video editor errors.
 * Provides structured error handling with error codes and recovery options.
 */
public class FaditorException extends Exception {
    
    public enum ErrorCode {
        // Video format errors
        UNSUPPORTED_VIDEO_FORMAT,
        CORRUPTED_VIDEO_FILE,
        VIDEO_FILE_NOT_FOUND,
        VIDEO_FILE_ACCESS_DENIED,
        
        // Processing errors
        PROCESSING_FAILED,
        ENCODING_FAILED,
        DECODING_FAILED,
        OPENGL_ERROR,
        MEDIACODEC_ERROR,
        
        // Validation errors
        INVALID_TRIM_RANGE,
        INVALID_EXPORT_SETTINGS,
        INVALID_PROJECT_DATA,
        
        // Storage errors
        INSUFFICIENT_STORAGE,
        STORAGE_ACCESS_DENIED,
        TEMP_FILE_CREATION_FAILED,
        
        // Project errors
        PROJECT_LOAD_FAILED,
        PROJECT_SAVE_FAILED,
        PROJECT_CORRUPTED,
        MEDIA_REFERENCE_BROKEN,
        
        // Auto-save errors
        AUTO_SAVE_FAILED,
        STATE_RECOVERY_FAILED,
        BACKUP_CREATION_FAILED,
        
        // Network/Permission errors
        PERMISSION_DENIED,
        DEVICE_NOT_SUPPORTED,
        
        // Unknown errors
        UNKNOWN_ERROR
    }
    
    public enum RecoveryAction {
        NONE,
        RETRY,
        SELECT_DIFFERENT_FILE,
        ADJUST_SETTINGS,
        FREE_STORAGE_SPACE,
        GRANT_PERMISSIONS,
        RESTART_APP,
        CONTACT_SUPPORT
    }
    
    private final ErrorCode errorCode;
    private final RecoveryAction recoveryAction;
    private final String userFriendlyMessage;
    private final String technicalDetails;
    
    public FaditorException(ErrorCode errorCode, String userFriendlyMessage) {
        this(errorCode, userFriendlyMessage, null, RecoveryAction.NONE, null);
    }
    
    public FaditorException(ErrorCode errorCode, String userFriendlyMessage, RecoveryAction recoveryAction) {
        this(errorCode, userFriendlyMessage, null, recoveryAction, null);
    }
    
    public FaditorException(ErrorCode errorCode, String userFriendlyMessage, String technicalDetails, RecoveryAction recoveryAction) {
        this(errorCode, userFriendlyMessage, technicalDetails, recoveryAction, null);
    }
    
    public FaditorException(ErrorCode errorCode, String userFriendlyMessage, String technicalDetails, RecoveryAction recoveryAction, Throwable cause) {
        super(userFriendlyMessage, cause);
        this.errorCode = errorCode;
        this.userFriendlyMessage = userFriendlyMessage;
        this.technicalDetails = technicalDetails;
        this.recoveryAction = recoveryAction;
    }
    
    public ErrorCode getErrorCode() {
        return errorCode;
    }
    
    public RecoveryAction getRecoveryAction() {
        return recoveryAction;
    }
    
    public String getUserFriendlyMessage() {
        return userFriendlyMessage;
    }
    
    public String getTechnicalDetails() {
        return technicalDetails;
    }
    
    public boolean hasRecoveryAction() {
        return recoveryAction != RecoveryAction.NONE;
    }
    
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("FaditorException{");
        sb.append("errorCode=").append(errorCode);
        sb.append(", userMessage='").append(userFriendlyMessage).append('\'');
        if (technicalDetails != null) {
            sb.append(", technicalDetails='").append(technicalDetails).append('\'');
        }
        sb.append(", recoveryAction=").append(recoveryAction);
        sb.append('}');
        return sb.toString();
    }
}