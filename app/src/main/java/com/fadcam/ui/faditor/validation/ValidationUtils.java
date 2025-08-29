package com.fadcam.ui.faditor.validation;

import android.content.Context;
import android.net.Uri;

import com.fadcam.ui.faditor.exceptions.ErrorHandler;
import com.fadcam.ui.faditor.exceptions.FaditorException;
import com.fadcam.ui.faditor.models.VideoMetadata;
import com.fadcam.ui.faditor.models.VideoProject;
import com.fadcam.ui.faditor.utils.VideoFileUtils;

import java.io.File;

/**
 * Validation utilities for Faditor Mini video editor.
 * Provides comprehensive validation for video files, trim ranges, and export settings.
 */
public class ValidationUtils {
    
    private static final long MIN_TRIM_DURATION_MS = 100; // Minimum 100ms
    private static final long MAX_VIDEO_DURATION_MS = 3600000; // Maximum 1 hour
    private static final int MIN_VIDEO_WIDTH = 64;
    private static final int MIN_VIDEO_HEIGHT = 64;
    private static final int MAX_VIDEO_WIDTH = 7680; // 8K
    private static final int MAX_VIDEO_HEIGHT = 4320; // 8K
    private static final long MIN_STORAGE_REQUIRED_MB = 100; // 100MB minimum free space
    
    /**
     * Validate video file for editing
     */
    public static void validateVideoFile(Context context, Uri videoUri) throws FaditorException {
        if (videoUri == null) {
            throw new FaditorException(
                FaditorException.ErrorCode.VIDEO_FILE_NOT_FOUND,
                "No video file selected. Please select a video file to edit."
            );
        }
        
        // Check if file is accessible
        if (!VideoFileUtils.isValidVideoFile(context, videoUri)) {
            String fileName = VideoFileUtils.getFileName(context, videoUri);
            throw ErrorHandler.createUnsupportedFormatError(context, fileName);
        }
        
        // Extract and validate metadata
        VideoMetadata metadata = VideoFileUtils.extractMetadata(context, videoUri);
        validateVideoMetadata(context, metadata);
        
        // Check file size and available storage
        long fileSize = VideoFileUtils.getFileSize(context, videoUri);
        validateStorageSpace(context, fileSize);
    }
    
    /**
     * Validate video metadata
     */
    public static void validateVideoMetadata(Context context, VideoMetadata metadata) throws FaditorException {
        if (metadata == null) {
            throw new FaditorException(
                FaditorException.ErrorCode.CORRUPTED_VIDEO_FILE,
                "Unable to read video information. The file may be corrupted.",
                FaditorException.RecoveryAction.SELECT_DIFFERENT_FILE
            );
        }
        
        // Check duration
        if (metadata.getDuration() <= 0) {
            throw new FaditorException(
                FaditorException.ErrorCode.CORRUPTED_VIDEO_FILE,
                "Invalid video duration. The file may be corrupted.",
                FaditorException.RecoveryAction.SELECT_DIFFERENT_FILE
            );
        }
        
        if (metadata.getDuration() > MAX_VIDEO_DURATION_MS) {
            throw new FaditorException(
                FaditorException.ErrorCode.INVALID_PROJECT_DATA,
                "Video is too long. Maximum supported duration is 1 hour.",
                FaditorException.RecoveryAction.SELECT_DIFFERENT_FILE
            );
        }
        
        // Check resolution
        if (metadata.getWidth() < MIN_VIDEO_WIDTH || metadata.getHeight() < MIN_VIDEO_HEIGHT) {
            throw new FaditorException(
                FaditorException.ErrorCode.INVALID_PROJECT_DATA,
                String.format("Video resolution is too small. Minimum supported resolution is %dx%d.", 
                    MIN_VIDEO_WIDTH, MIN_VIDEO_HEIGHT),
                FaditorException.RecoveryAction.SELECT_DIFFERENT_FILE
            );
        }
        
        if (metadata.getWidth() > MAX_VIDEO_WIDTH || metadata.getHeight() > MAX_VIDEO_HEIGHT) {
            throw new FaditorException(
                FaditorException.ErrorCode.INVALID_PROJECT_DATA,
                String.format("Video resolution is too large. Maximum supported resolution is %dx%d.", 
                    MAX_VIDEO_WIDTH, MAX_VIDEO_HEIGHT),
                FaditorException.RecoveryAction.SELECT_DIFFERENT_FILE
            );
        }
    }
    
    /**
     * Validate trim range
     */
    public static void validateTrimRange(Context context, long startMs, long endMs, long videoDurationMs) throws FaditorException {
        // Check basic range validity
        if (startMs < 0) {
            throw ErrorHandler.createInvalidTrimRangeError(context, startMs, endMs, videoDurationMs);
        }
        
        if (endMs > videoDurationMs) {
            throw ErrorHandler.createInvalidTrimRangeError(context, startMs, endMs, videoDurationMs);
        }
        
        if (startMs >= endMs) {
            throw ErrorHandler.createInvalidTrimRangeError(context, startMs, endMs, videoDurationMs);
        }
        
        // Check minimum duration
        long trimDuration = endMs - startMs;
        if (trimDuration < MIN_TRIM_DURATION_MS) {
            throw new FaditorException(
                FaditorException.ErrorCode.INVALID_TRIM_RANGE,
                String.format("Trim duration is too short. Minimum duration is %dms.", MIN_TRIM_DURATION_MS),
                FaditorException.RecoveryAction.ADJUST_SETTINGS
            );
        }
    }    

    /**
     * Validate export settings
     */
    public static void validateExportSettings(Context context, ExportSettings settings, VideoMetadata sourceMetadata) throws FaditorException {
        if (settings == null) {
            throw ErrorHandler.createInvalidExportSettingsError(context, "Export settings are null");
        }
        
        // Validate output format
        if (settings.getOutputFormat() == null || settings.getOutputFormat().isEmpty()) {
            throw ErrorHandler.createInvalidExportSettingsError(context, "Output format not specified");
        }
        
        if (!VideoFileUtils.isSupportedFormat("test." + settings.getOutputFormat())) {
            throw ErrorHandler.createInvalidExportSettingsError(context, 
                "Unsupported output format: " + settings.getOutputFormat());
        }
        
        // Validate resolution
        if (settings.getOutputWidth() <= 0 || settings.getOutputHeight() <= 0) {
            throw ErrorHandler.createInvalidExportSettingsError(context, "Invalid output resolution");
        }
        
        // Validate bitrate
        if (settings.getBitrate() <= 0) {
            throw ErrorHandler.createInvalidExportSettingsError(context, "Invalid bitrate");
        }
        
        // Validate frame rate
        if (settings.getFrameRate() <= 0 || settings.getFrameRate() > 120) {
            throw ErrorHandler.createInvalidExportSettingsError(context, "Invalid frame rate");
        }
        
        // Check if upscaling is reasonable
        if (sourceMetadata != null) {
            int sourceWidth = sourceMetadata.getWidth();
            int sourceHeight = sourceMetadata.getHeight();
            
            if (settings.getOutputWidth() > sourceWidth * 2 || settings.getOutputHeight() > sourceHeight * 2) {
                throw ErrorHandler.createInvalidExportSettingsError(context, 
                    "Output resolution is too large compared to source video");
            }
        }
    }
    
    /**
     * Validate project data
     */
    public static void validateProject(Context context, VideoProject project) throws FaditorException {
        if (project == null) {
            throw new FaditorException(
                FaditorException.ErrorCode.INVALID_PROJECT_DATA,
                "Project data is null",
                FaditorException.RecoveryAction.SELECT_DIFFERENT_FILE
            );
        }
        
        if (project.getProjectId() == null || project.getProjectId().isEmpty()) {
            throw new FaditorException(
                FaditorException.ErrorCode.INVALID_PROJECT_DATA,
                "Project ID is missing",
                FaditorException.RecoveryAction.CONTACT_SUPPORT
            );
        }
        
        if (project.getProjectName() == null || project.getProjectName().trim().isEmpty()) {
            throw new FaditorException(
                FaditorException.ErrorCode.INVALID_PROJECT_DATA,
                "Project name is missing",
                FaditorException.RecoveryAction.ADJUST_SETTINGS
            );
        }
        
        // Validate media references
        if (project.getOriginalVideoUri() == null && project.getOriginalVideoPath() == null) {
            throw new FaditorException(
                FaditorException.ErrorCode.MEDIA_REFERENCE_BROKEN,
                "Original video reference is missing",
                FaditorException.RecoveryAction.SELECT_DIFFERENT_FILE
            );
        }
        
        // Validate metadata
        if (project.getMetadata() != null) {
            validateVideoMetadata(context, project.getMetadata());
        }
    }
    
    /**
     * Validate storage space
     */
    public static void validateStorageSpace(Context context, long requiredBytes) throws FaditorException {
        File cacheDir = context.getCacheDir();
        long availableBytes = cacheDir.getFreeSpace();
        
        // Add buffer for processing (2x the file size + minimum required)
        long totalRequired = requiredBytes * 2 + (MIN_STORAGE_REQUIRED_MB * 1024 * 1024);
        
        if (availableBytes < totalRequired) {
            long requiredMB = totalRequired / (1024 * 1024);
            long availableMB = availableBytes / (1024 * 1024);
            
            throw new FaditorException(
                FaditorException.ErrorCode.INSUFFICIENT_STORAGE,
                String.format("Insufficient storage space. Required: %dMB, Available: %dMB", 
                    requiredMB, availableMB),
                FaditorException.RecoveryAction.FREE_STORAGE_SPACE
            );
        }
    }
    
    /**
     * Validate file access permissions
     */
    public static void validateFileAccess(Context context, File file) throws FaditorException {
        if (file == null) {
            throw new FaditorException(
                FaditorException.ErrorCode.VIDEO_FILE_NOT_FOUND,
                "File path is null"
            );
        }
        
        if (!file.exists()) {
            throw new FaditorException(
                FaditorException.ErrorCode.VIDEO_FILE_NOT_FOUND,
                "File does not exist: " + file.getName(),
                FaditorException.RecoveryAction.SELECT_DIFFERENT_FILE
            );
        }
        
        if (!file.canRead()) {
            throw new FaditorException(
                FaditorException.ErrorCode.VIDEO_FILE_ACCESS_DENIED,
                "Cannot read file: " + file.getName(),
                FaditorException.RecoveryAction.GRANT_PERMISSIONS
            );
        }
    }
    
    /**
     * Export settings class for validation
     */
    public static class ExportSettings {
        private String outputFormat;
        private int outputWidth;
        private int outputHeight;
        private int bitrate;
        private float frameRate;
        
        // Getters and setters
        public String getOutputFormat() { return outputFormat; }
        public void setOutputFormat(String outputFormat) { this.outputFormat = outputFormat; }
        
        public int getOutputWidth() { return outputWidth; }
        public void setOutputWidth(int outputWidth) { this.outputWidth = outputWidth; }
        
        public int getOutputHeight() { return outputHeight; }
        public void setOutputHeight(int outputHeight) { this.outputHeight = outputHeight; }
        
        public int getBitrate() { return bitrate; }
        public void setBitrate(int bitrate) { this.bitrate = bitrate; }
        
        public float getFrameRate() { return frameRate; }
        public void setFrameRate(float frameRate) { this.frameRate = frameRate; }
    }
}