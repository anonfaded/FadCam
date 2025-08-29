package com.fadcam.ui.faditor.utils;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.webkit.MimeTypeMap;

import com.fadcam.Log;
import com.fadcam.ui.faditor.exceptions.ErrorHandler;
import com.fadcam.ui.faditor.exceptions.FaditorException;
import com.fadcam.ui.faditor.models.VideoMetadata;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.Arrays;
import java.util.List;

/**
 * Utility class for video file operations and validation.
 * Handles file format validation and metadata extraction.
 */
public class VideoFileUtils {
    
    private static final String TAG = "VideoFileUtils";
    
    private static final List<String> SUPPORTED_FORMATS = Arrays.asList(
            "mp4", "mov", "avi", "mkv", "3gp", "webm"
    );
    
    private static final List<String> SUPPORTED_MIME_TYPES = Arrays.asList(
            "video/mp4", "video/quicktime", "video/x-msvideo", 
            "video/x-matroska", "video/3gpp", "video/webm"
    );
    
    /**
     * Check if the video format is supported
     */
    public static boolean isSupportedFormat(String fileName) {
        if (fileName == null) return false;
        
        String extension = getFileExtension(fileName).toLowerCase();
        return SUPPORTED_FORMATS.contains(extension);
    }
    
    /**
     * Check if the MIME type is supported
     */
    public static boolean isSupportedMimeType(String mimeType) {
        return mimeType != null && SUPPORTED_MIME_TYPES.contains(mimeType.toLowerCase());
    }
    
    /**
     * Get file extension from filename
     */
    public static String getFileExtension(String fileName) {
        if (fileName == null || !fileName.contains(".")) {
            return "";
        }
        return fileName.substring(fileName.lastIndexOf(".") + 1);
    }
    
    /**
     * Extract video metadata from URI using MediaMetadataRetriever
     */
    public static VideoMetadata extractMetadata(Context context, Uri videoUri) {
        MediaMetadataRetriever retriever = null;
        try {
            retriever = new MediaMetadataRetriever();
            retriever.setDataSource(context, videoUri);
            
            VideoMetadata metadata = new VideoMetadata();
            
            // Extract basic video properties
            String durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            String widthStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH);
            String heightStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT);
            String bitrateStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE);
            String frameRateStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE);
            
            // Video codec and format information
            String mimeType = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE);
            
            // Audio information
            String hasAudioStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO);
            
            // Set metadata values with safe parsing
            if (durationStr != null) {
                metadata.setDuration(Long.parseLong(durationStr));
            }
            
            if (widthStr != null && heightStr != null) {
                metadata.setWidth(Integer.parseInt(widthStr));
                metadata.setHeight(Integer.parseInt(heightStr));
            }
            
            if (bitrateStr != null) {
                metadata.setBitrate(Integer.parseInt(bitrateStr));
            }
            
            if (frameRateStr != null) {
                metadata.setFrameRate(Float.parseFloat(frameRateStr));
            }
            
            if (mimeType != null) {
                metadata.setContainerFormat(getContainerFromMimeType(mimeType));
                metadata.setCodec(getCodecFromMimeType(mimeType));
            }
            
            metadata.setHasAudio(hasAudioStr != null && hasAudioStr.equals("yes"));
            
            // Get filename for additional format detection
            String fileName = getFileName(context, videoUri);
            if (fileName != null && metadata.getContainerFormat() == null) {
                String extension = getFileExtension(fileName);
                metadata.setContainerFormat(extension.toLowerCase());
            }
            
            Log.d(TAG, "Extracted metadata: " + metadata.toString());
            return metadata;
            
        } catch (Exception e) {
            Log.e(TAG, "Error extracting metadata from URI: " + e.getMessage());
            return new VideoMetadata(); // Return empty metadata on error
        } finally {
            if (retriever != null) {
                try {
                    retriever.release();
                } catch (Exception e) {
                    Log.e(TAG, "Error releasing MediaMetadataRetriever: " + e.getMessage());
                }
            }
        }
    }
    
    /**
     * Extract video metadata from file path
     */
    public static VideoMetadata extractMetadata(File videoFile) {
        if (videoFile == null || !videoFile.exists()) {
            return new VideoMetadata();
        }
        
        MediaMetadataRetriever retriever = null;
        try {
            retriever = new MediaMetadataRetriever();
            retriever.setDataSource(videoFile.getAbsolutePath());
            
            VideoMetadata metadata = new VideoMetadata();
            
            // Extract basic video properties
            String durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            String widthStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH);
            String heightStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT);
            String bitrateStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE);
            String frameRateStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE);
            String mimeType = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE);
            String hasAudioStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO);
            
            // Set metadata values with safe parsing
            if (durationStr != null) {
                metadata.setDuration(Long.parseLong(durationStr));
            }
            
            if (widthStr != null && heightStr != null) {
                metadata.setWidth(Integer.parseInt(widthStr));
                metadata.setHeight(Integer.parseInt(heightStr));
            }
            
            if (bitrateStr != null) {
                metadata.setBitrate(Integer.parseInt(bitrateStr));
            }
            
            if (frameRateStr != null) {
                metadata.setFrameRate(Float.parseFloat(frameRateStr));
            }
            
            if (mimeType != null) {
                metadata.setContainerFormat(getContainerFromMimeType(mimeType));
                metadata.setCodec(getCodecFromMimeType(mimeType));
            }
            
            metadata.setHasAudio(hasAudioStr != null && hasAudioStr.equals("yes"));
            
            // Fallback to file extension if container format not detected
            if (metadata.getContainerFormat() == null) {
                String extension = getFileExtension(videoFile.getName());
                metadata.setContainerFormat(extension.toLowerCase());
            }
            
            Log.d(TAG, "Extracted metadata from file: " + metadata.toString());
            return metadata;
            
        } catch (Exception e) {
            Log.e(TAG, "Error extracting metadata from file: " + e.getMessage());
            return new VideoMetadata();
        } finally {
            if (retriever != null) {
                try {
                    retriever.release();
                } catch (Exception e) {
                    Log.e(TAG, "Error releasing MediaMetadataRetriever: " + e.getMessage());
                }
            }
        }
    }
    
    /**
     * Get a temporary file for video processing
     */
    public static File getTempVideoFile(Context context, String suffix) {
        try {
            File cacheDir = context.getCacheDir();
            File faditorDir = new File(cacheDir, "faditor");
            if (!faditorDir.exists()) {
                faditorDir.mkdirs();
            }
            return File.createTempFile("faditor_temp_", "." + suffix, faditorDir);
        } catch (Exception e) {
            Log.e(TAG, "Error creating temp file: " + e.getMessage());
            return null;
        }
    }
    
    /**
     * Copy video file from URI to local file
     */
    public static boolean copyVideoToFile(Context context, Uri sourceUri, File destFile) {
        InputStream inputStream = null;
        FileOutputStream outputStream = null;
        
        try {
            ContentResolver resolver = context.getContentResolver();
            inputStream = resolver.openInputStream(sourceUri);
            
            if (inputStream == null) {
                Log.e(TAG, "Could not open input stream for URI: " + sourceUri);
                return false;
            }
            
            outputStream = new FileOutputStream(destFile);
            
            byte[] buffer = new byte[8192];
            int bytesRead;
            
            while ((bytesRead = inputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, bytesRead);
            }
            
            outputStream.flush();
            Log.d(TAG, "Successfully copied video to: " + destFile.getAbsolutePath());
            return true;
            
        } catch (Exception e) {
            Log.e(TAG, "Error copying video file: " + e.getMessage());
            return false;
        } finally {
            try {
                if (inputStream != null) inputStream.close();
                if (outputStream != null) outputStream.close();
            } catch (Exception e) {
                Log.e(TAG, "Error closing streams: " + e.getMessage());
            }
        }
    }
    
    /**
     * Get filename from URI
     */
    public static String getFileName(Context context, Uri uri) {
        String fileName = null;
        
        if (uri.getScheme() != null && uri.getScheme().equals("content")) {
            try (Cursor cursor = context.getContentResolver().query(uri, null, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    int nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    if (nameIndex >= 0) {
                        fileName = cursor.getString(nameIndex);
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Error getting filename from content URI: " + e.getMessage());
            }
        }
        
        if (fileName == null && uri.getPath() != null) {
            fileName = new File(uri.getPath()).getName();
        }
        
        return fileName;
    }
    
    /**
     * Get real file path from URI if possible
     * Returns null for content URIs that don't have a real file path
     */
    public static String getRealPathFromUri(Context context, Uri uri) {
        if (uri == null) return null;
        
        // If it's a file URI, return the path directly
        if ("file".equals(uri.getScheme())) {
            return uri.getPath();
        }
        
        // For content URIs, try to get the real path from MediaStore
        if ("content".equals(uri.getScheme())) {
            try {
                String[] projection = {MediaStore.Video.Media.DATA};
                Cursor cursor = context.getContentResolver().query(uri, projection, null, null, null);
                
                if (cursor != null) {
                    try {
                        if (cursor.moveToFirst()) {
                            int columnIndex = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATA);
                            String path = cursor.getString(columnIndex);
                            
                            // Verify the path exists and is accessible
                            if (path != null) {
                                File file = new File(path);
                                if (file.exists() && file.canRead()) {
                                    return path;
                                }
                            }
                        }
                    } finally {
                        cursor.close();
                    }
                }
            } catch (Exception e) {
                Log.d(TAG, "Could not get real path from content URI: " + e.getMessage());
            }
        }
        
        return null; // No real file path available
    }
    
    /**
     * Get file size from URI
     */
    public static long getFileSize(Context context, Uri uri) {
        try (Cursor cursor = context.getContentResolver().query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE);
                if (sizeIndex >= 0) {
                    return cursor.getLong(sizeIndex);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error getting file size: " + e.getMessage());
        }
        return 0;
    }
    
    /**
     * Get container format from MIME type
     */
    private static String getContainerFromMimeType(String mimeType) {
        if (mimeType == null) return null;
        
        switch (mimeType.toLowerCase()) {
            case "video/mp4":
                return "mp4";
            case "video/quicktime":
                return "mov";
            case "video/x-msvideo":
                return "avi";
            case "video/x-matroska":
                return "mkv";
            case "video/3gpp":
                return "3gp";
            case "video/webm":
                return "webm";
            default:
                return null;
        }
    }
    
    /**
     * Get codec information from MIME type (basic detection)
     */
    private static String getCodecFromMimeType(String mimeType) {
        if (mimeType == null) return null;
        
        // This is a basic implementation - more detailed codec detection
        // would require parsing the actual video stream
        switch (mimeType.toLowerCase()) {
            case "video/mp4":
                return "avc"; // Most MP4 files use H.264
            case "video/quicktime":
                return "avc";
            case "video/webm":
                return "vp8"; // WebM typically uses VP8/VP9
            default:
                return "unknown";
        }
    }
    
    /**
     * Get human-readable file size
     */
    public static String formatFileSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        int exp = (int) (Math.log(bytes) / Math.log(1024));
        String pre = "KMGTPE".charAt(exp - 1) + "";
        return String.format("%.1f %sB", bytes / Math.pow(1024, exp), pre);
    }
    
    /**
     * Format duration in milliseconds to readable string
     */
    public static String formatDuration(long durationMs) {
        long seconds = durationMs / 1000;
        long minutes = seconds / 60;
        long hours = minutes / 60;
        
        seconds = seconds % 60;
        minutes = minutes % 60;
        
        if (hours > 0) {
            return String.format("%d:%02d:%02d", hours, minutes, seconds);
        } else {
            return String.format("%d:%02d", minutes, seconds);
        }
    }
    
    /**
     * Get supported formats as a formatted string
     */
    public static String getSupportedFormatsString() {
        return String.join(", ", SUPPORTED_FORMATS).toUpperCase();
    }
    
    /**
     * Validate video file before processing
     */
    public static boolean isValidVideoFile(Context context, Uri videoUri) {
        try {
            // Check if we can access the file
            InputStream inputStream = context.getContentResolver().openInputStream(videoUri);
            if (inputStream == null) {
                return false;
            }
            inputStream.close();
            
            // Check MIME type
            String mimeType = context.getContentResolver().getType(videoUri);
            if (!isSupportedMimeType(mimeType)) {
                // Fallback to filename extension check
                String fileName = getFileName(context, videoUri);
                if (fileName == null || !isSupportedFormat(fileName)) {
                    return false;
                }
            }
            
            // Try to extract basic metadata to ensure it's a valid video
            VideoMetadata metadata = extractMetadata(context, videoUri);
            return metadata.getDuration() > 0 && metadata.getWidth() > 0 && metadata.getHeight() > 0;
            
        } catch (Exception e) {
            Log.e(TAG, "Error validating video file: " + e.getMessage());
            return false;
        }
    }
    
    /**
     * Validate video file with detailed error reporting
     */
    public static void validateVideoFileWithErrors(Context context, Uri videoUri) throws FaditorException {
        if (videoUri == null) {
            throw new FaditorException(
                FaditorException.ErrorCode.VIDEO_FILE_NOT_FOUND,
                "No video file selected. Please select a video file to edit."
            );
        }
        
        try {
            // Check if we can access the file
            InputStream inputStream = context.getContentResolver().openInputStream(videoUri);
            if (inputStream == null) {
                throw new FaditorException(
                    FaditorException.ErrorCode.VIDEO_FILE_ACCESS_DENIED,
                    "Cannot access the selected video file. Please check file permissions.",
                    FaditorException.RecoveryAction.GRANT_PERMISSIONS
                );
            }
            inputStream.close();
            
            // Check MIME type and format
            String mimeType = context.getContentResolver().getType(videoUri);
            String fileName = getFileName(context, videoUri);
            
            if (!isSupportedMimeType(mimeType) && (fileName == null || !isSupportedFormat(fileName))) {
                throw ErrorHandler.createUnsupportedFormatError(context, fileName);
            }
            
            // Try to extract metadata to ensure it's a valid video
            VideoMetadata metadata = extractMetadata(context, videoUri);
            if (metadata.getDuration() <= 0 || metadata.getWidth() <= 0 || metadata.getHeight() <= 0) {
                throw new FaditorException(
                    FaditorException.ErrorCode.CORRUPTED_VIDEO_FILE,
                    "The selected video file appears to be corrupted or invalid.",
                    FaditorException.RecoveryAction.SELECT_DIFFERENT_FILE
                );
            }
            
        } catch (FaditorException e) {
            throw e; // Re-throw FaditorException as-is
        } catch (SecurityException e) {
            throw new FaditorException(
                FaditorException.ErrorCode.VIDEO_FILE_ACCESS_DENIED,
                "Permission denied. Please grant storage access permissions.",
                "SecurityException: " + e.getMessage(),
                FaditorException.RecoveryAction.GRANT_PERMISSIONS,
                e
            );
        } catch (Exception e) {
            Log.e(TAG, "Error validating video file: " + e.getMessage(), e);
            throw new FaditorException(
                FaditorException.ErrorCode.UNKNOWN_ERROR,
                "An unexpected error occurred while validating the video file: " + e.getMessage(),
                "Exception: " + e.getClass().getSimpleName(),
                FaditorException.RecoveryAction.RETRY,
                e
            );
        }
    }
    
    /**
     * Clean up temporary files in the faditor cache directory
     */
    public static void cleanupTempFiles(Context context) {
        try {
            File cacheDir = context.getCacheDir();
            File faditorDir = new File(cacheDir, "faditor");
            
            if (faditorDir.exists() && faditorDir.isDirectory()) {
                File[] files = faditorDir.listFiles();
                if (files != null) {
                    for (File file : files) {
                        if (file.isFile() && file.getName().startsWith("faditor_temp_")) {
                            boolean deleted = file.delete();
                            Log.d(TAG, "Cleaned up temp file: " + file.getName() + " (deleted: " + deleted + ")");
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error cleaning up temp files: " + e.getMessage());
        }
    }
}