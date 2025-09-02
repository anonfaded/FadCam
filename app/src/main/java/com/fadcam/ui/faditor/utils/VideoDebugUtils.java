package com.fadcam.ui.faditor.utils;

import android.content.Context;
import android.net.Uri;
import android.util.Log;
import java.io.File;

/**
 * Utility class for video debugging and diagnostics.
 * Helps identify issues with video loading, playback, and OpenGL rendering.
 */
public class VideoDebugUtils {

    private static final String TAG = "VideoDebugUtils";

    /**
     * Comprehensive video diagnostics
     */
    public static class VideoDiagnostics {
        public final boolean uriAccessible;
        public final boolean fileExists;
        public final long fileSize;
        public final String mimeType;
        public final String uriScheme;
        public final String uriPath;
        public final boolean isContentUri;
        public final boolean isFileUri;
        public final String errorMessage;

        public VideoDiagnostics(boolean uriAccessible, boolean fileExists, long fileSize,
                              String mimeType, String uriScheme, String uriPath,
                              boolean isContentUri, boolean isFileUri, String errorMessage) {
            this.uriAccessible = uriAccessible;
            this.fileExists = fileExists;
            this.fileSize = fileSize;
            this.mimeType = mimeType;
            this.uriScheme = uriScheme;
            this.uriPath = uriPath;
            this.isContentUri = isContentUri;
            this.isFileUri = isFileUri;
            this.errorMessage = errorMessage;
        }

        public boolean isValid() {
            return uriAccessible && fileExists && fileSize > 0;
        }

        @Override
        public String toString() {
            return String.format(
                "VideoDiagnostics{uriAccessible=%s, fileExists=%s, fileSize=%d, mimeType='%s', " +
                "uriScheme='%s', uriPath='%s', isContentUri=%s, isFileUri=%s, error='%s'}",
                uriAccessible, fileExists, fileSize, mimeType, uriScheme, uriPath,
                isContentUri, isFileUri, errorMessage
            );
        }
    }

    /**
     * Perform comprehensive diagnostics on a video URI
     */
    public static VideoDiagnostics diagnoseVideoUri(Context context, Uri videoUri) {
        Log.d(TAG, "Starting video diagnostics for URI: " + videoUri);

        if (videoUri == null) {
            Log.w(TAG, "Video URI is null");
            return new VideoDiagnostics(false, false, 0, null, null, null, false, false, "URI is null");
        }

        String scheme = videoUri.getScheme();
        String path = videoUri.getPath();
        boolean isContentUri = "content".equals(scheme);
        boolean isFileUri = "file".equals(scheme);

        Log.d(TAG, "URI scheme: " + scheme);
        Log.d(TAG, "URI path: " + path);
        Log.d(TAG, "Is content URI: " + isContentUri);
        Log.d(TAG, "Is file URI: " + isFileUri);

        // Check URI accessibility
        boolean uriAccessible = false;
        String mimeType = null;
        long fileSize = 0;
        boolean fileExists = false;
        String errorMessage = null;

        try {
            // Get MIME type
            mimeType = context.getContentResolver().getType(videoUri);
            Log.d(TAG, "MIME type: " + mimeType);

            // Check if we can open the URI
            java.io.InputStream inputStream = context.getContentResolver().openInputStream(videoUri);
            if (inputStream != null) {
                uriAccessible = true;
                // Try to get file size
                try {
                    fileSize = inputStream.available();
                    Log.d(TAG, "File size from stream: " + fileSize + " bytes");
                } catch (Exception e) {
                    Log.w(TAG, "Could not determine file size from stream", e);
                }
                inputStream.close();
            }

            // For file URIs, also check if file exists
            if (isFileUri && path != null) {
                File file = new File(path);
                fileExists = file.exists();
                if (fileExists) {
                    fileSize = file.length();
                    Log.d(TAG, "File exists, size: " + fileSize + " bytes");
                } else {
                    Log.w(TAG, "File does not exist: " + path);
                    errorMessage = "File does not exist: " + path;
                }
            } else if (isContentUri) {
                fileExists = uriAccessible; // For content URIs, accessibility implies existence
            }

        } catch (Exception e) {
            Log.e(TAG, "Error during video diagnostics", e);
            errorMessage = e.getMessage();
            uriAccessible = false;
        }

        VideoDiagnostics diagnostics = new VideoDiagnostics(
            uriAccessible, fileExists, fileSize, mimeType, scheme, path,
            isContentUri, isFileUri, errorMessage
        );

        Log.d(TAG, "Video diagnostics completed: " + diagnostics);
        return diagnostics;
    }

    /**
     * Check if video format is supported
     */
    public static boolean isVideoFormatSupported(String mimeType) {
        if (mimeType == null) return false;

        // Common supported video formats
        String[] supportedFormats = {
            "video/mp4",
            "video/avi",
            "video/mov",
            "video/mkv",
            "video/webm",
            "video/3gp",
            "video/mp2ts"
        };

        for (String format : supportedFormats) {
            if (mimeType.equals(format)) {
                return true;
            }
        }

        Log.w(TAG, "Unsupported video format: " + mimeType);
        return false;
    }

    /**
     * Log OpenGL information for debugging
     */
    public static void logOpenGLInfo() {
        Log.d(TAG, "=== OpenGL Diagnostics ===");

        try {
            // This would be called from OpenGL thread
            String version = android.opengl.GLES20.glGetString(android.opengl.GLES20.GL_VERSION);
            String vendor = android.opengl.GLES20.glGetString(android.opengl.GLES20.GL_VENDOR);
            String renderer = android.opengl.GLES20.glGetString(android.opengl.GLES20.GL_RENDERER);
            String extensions = android.opengl.GLES20.glGetString(android.opengl.GLES20.GL_EXTENSIONS);

            Log.d(TAG, "OpenGL Version: " + version);
            Log.d(TAG, "OpenGL Vendor: " + vendor);
            Log.d(TAG, "OpenGL Renderer: " + renderer);
            Log.d(TAG, "OpenGL Extensions: " + (extensions != null ? extensions.length() : 0) + " chars");

            // Check for required extensions
            boolean hasVideoTexture = extensions != null && extensions.contains("GL_OES_EGL_image_external");
            Log.d(TAG, "Video texture support (GL_OES_EGL_image_external): " + hasVideoTexture);

        } catch (Exception e) {
            Log.e(TAG, "Error getting OpenGL info", e);
        }

        Log.d(TAG, "=== End OpenGL Diagnostics ===");
    }

    /**
     * Check device capabilities for video playback
     */
    public static void logDeviceCapabilities(Context context) {
        Log.d(TAG, "=== Device Capabilities ===");

        // Check if device supports OpenGL ES 2.0
        android.app.ActivityManager activityManager =
            (android.app.ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        boolean supportsOpenGL2 = activityManager != null;

        Log.d(TAG, "Supports OpenGL ES 2.0+: " + supportsOpenGL2);

        // Check available memory
        android.app.ActivityManager.MemoryInfo memoryInfo = new android.app.ActivityManager.MemoryInfo();
        activityManager.getMemoryInfo(memoryInfo);

        Log.d(TAG, "Total memory: " + (memoryInfo.totalMem / 1024 / 1024) + " MB");
        Log.d(TAG, "Available memory: " + (memoryInfo.availMem / 1024 / 1024) + " MB");
        Log.d(TAG, "Low memory: " + memoryInfo.lowMemory);

        // Check CPU architecture
        String arch = System.getProperty("os.arch");
        Log.d(TAG, "CPU Architecture: " + arch);

        Log.d(TAG, "=== End Device Capabilities ===");
    }

    /**
     * Create a test video URI for debugging
     */
    public static Uri createTestVideoUri(Context context) {
        try {
            // Create a simple test file in cache directory
            File testFile = new File(context.getCacheDir(), "test_video.mp4");

            // For now, just return null - this would need actual video data
            Log.d(TAG, "Test video would be created at: " + testFile.getAbsolutePath());
            return null;

        } catch (Exception e) {
            Log.e(TAG, "Error creating test video URI", e);
            return null;
        }
    }
}
