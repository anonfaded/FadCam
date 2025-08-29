package com.fadcam.ui.faditor;

/**
 * Constants used throughout the Faditor Mini editor.
 * Defines configuration values and shared constants.
 */
public class FaditorConstants {
    
    // File and format constants
    public static final String[] SUPPORTED_VIDEO_EXTENSIONS = {
            "mp4", "mov", "avi", "mkv", "3gp", "webm"
    };
    
    public static final String[] SUPPORTED_MIME_TYPES = {
            "video/mp4", "video/quicktime", "video/x-msvideo", 
            "video/x-matroska", "video/3gpp", "video/webm"
    };
    
    public static final String DEFAULT_EXPORT_FORMAT = "mp4";
    public static final int DEFAULT_EXPORT_QUALITY = 80;
    
    // Processing constants
    public static final int MAX_PROCESSING_THREADS = 2;
    public static final long MIN_TRIM_DURATION_MS = 1000; // 1 second
    public static final long SEEK_RESPONSE_TARGET_MS = 100; // Target seek response time
    
    // UI constants
    public static final int TIMELINE_HEIGHT_DP = 60;
    public static final int TRIM_HANDLE_SIZE_DP = 20;
    public static final int PROGRESS_UPDATE_INTERVAL_MS = 100;
    
    // OpenGL constants
    public static final int TEXTURE_POOL_SIZE = 4;
    public static final int MAX_TEXTURE_SIZE = 2048;
    
    // Performance thresholds
    public static final long LARGE_FILE_THRESHOLD_BYTES = 100 * 1024 * 1024; // 100MB
    public static final int TARGET_FPS = 30;
    public static final long MAX_PROCESSING_TIME_MS = 30 * 1000; // 30 seconds for typical operations
    
    // Error codes
    public static final int ERROR_UNSUPPORTED_FORMAT = 1001;
    public static final int ERROR_FILE_NOT_FOUND = 1002;
    public static final int ERROR_INSUFFICIENT_SPACE = 1003;
    public static final int ERROR_PROCESSING_FAILED = 1004;
    public static final int ERROR_OPENGL_INIT_FAILED = 1005;
    public static final int ERROR_MEDIACODEC_FAILED = 1006;
    
    // Preferences keys
    public static final String PREF_LAST_EXPORT_QUALITY = "faditor_last_export_quality";
    public static final String PREF_LAST_EXPORT_FORMAT = "faditor_last_export_format";
    public static final String PREF_ENABLE_HARDWARE_ACCELERATION = "faditor_enable_hw_accel";
    
    private FaditorConstants() {
        // Utility class - no instantiation
    }
}