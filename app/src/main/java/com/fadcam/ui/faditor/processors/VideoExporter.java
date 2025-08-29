package com.fadcam.ui.faditor.processors;

import android.content.Context;
import android.os.AsyncTask;
import android.os.Environment;
import android.util.Log;

import com.fadcam.ui.faditor.models.VideoMetadata;
import com.fadcam.ui.faditor.models.VideoProject;
import com.fadcam.ui.faditor.processors.opengl.MediaCodecIntegration;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Manages video export operations with MediaCodec integration.
 * Handles export settings and final video encoding with progress tracking.
 */
public class VideoExporter {
    
    private static final String TAG = "VideoExporter";
    
    // Export quality presets
    public static final int QUALITY_LOW = 30;
    public static final int QUALITY_MEDIUM = 60;
    public static final int QUALITY_HIGH = 80;
    public static final int QUALITY_ULTRA = 95;
    
    // Supported export formats
    public static final String FORMAT_MP4 = "mp4";
    public static final String FORMAT_MOV = "mov";
    
    public static class ExportSettings {
        public final int quality; // 0-100
        public final String format; // "mp4", "mov", etc.
        public final int bitrate; // bits per second (-1 for auto)
        public final boolean maintainOriginalQuality;
        public final int width; // -1 for original
        public final int height; // -1 for original
        public final String outputFileName; // null for auto-generated
        
        public ExportSettings(int quality, String format, int bitrate, boolean maintainOriginalQuality,
                             int width, int height, String outputFileName) {
            this.quality = quality;
            this.format = format;
            this.bitrate = bitrate;
            this.maintainOriginalQuality = maintainOriginalQuality;
            this.width = width;
            this.height = height;
            this.outputFileName = outputFileName;
        }
        
        public static ExportSettings createDefault() {
            return new ExportSettings(QUALITY_HIGH, FORMAT_MP4, -1, true, -1, -1, null);
        }
        
        public static ExportSettings createHighQuality() {
            return new ExportSettings(QUALITY_ULTRA, FORMAT_MP4, -1, true, -1, -1, null);
        }
        
        public static ExportSettings createMediumQuality() {
            return new ExportSettings(QUALITY_MEDIUM, FORMAT_MP4, -1, false, -1, -1, null);
        }
        
        public static ExportSettings createLowQuality() {
            return new ExportSettings(QUALITY_LOW, FORMAT_MP4, -1, false, -1, -1, null);
        }
        
        public static ExportSettings createCustom(int quality, String format, int bitrate) {
            return new ExportSettings(quality, format, bitrate, false, -1, -1, null);
        }
    }
    
    public interface ExportCallback {
        void onProgress(int percentage);
        void onSuccess(File exportedFile);
        void onError(String errorMessage);
        void onStarted();
        void onCancelled();
    }
    
    private final Context context;
    private final ExecutorService executorService;
    private ExportCallback currentCallback;
    private boolean isExporting = false;
    private boolean isCancelled = false;
    private ExportTask currentTask;
    
    public VideoExporter(Context context) {
        this.context = context.getApplicationContext();
        this.executorService = Executors.newSingleThreadExecutor();
    }
    
    /**
     * Export video with the specified settings
     */
    public void exportVideo(VideoProject project, ExportSettings settings, ExportCallback callback) {
        if (isExporting) {
            if (callback != null) {
                callback.onError("Export already in progress");
            }
            return;
        }
        
        this.currentCallback = callback;
        this.isExporting = true;
        this.isCancelled = false;
        
        // Generate output file
        File outputFile = generateOutputFile(project, settings);
        
        // Start export task
        currentTask = new ExportTask(project, outputFile, settings);
        executorService.execute(currentTask);
        
        if (callback != null) {
            callback.onStarted();
        }
        
        Log.d(TAG, "Export started for project: " + project.getProjectName());
    }
    
    /**
     * Cancel ongoing export operation
     */
    public void cancelExport() {
        if (!isExporting) {
            return;
        }
        
        isCancelled = true;
        isExporting = false;
        
        if (currentTask != null) {
            currentTask.cancel();
        }
        
        if (currentCallback != null) {
            currentCallback.onCancelled();
            currentCallback = null;
        }
        
        Log.d(TAG, "Export cancelled");
    }
    
    /**
     * Check if export is currently in progress
     */
    public boolean isExporting() {
        return isExporting;
    }
    
    /**
     * Get recommended export settings based on input metadata
     */
    public ExportSettings getRecommendedSettings(VideoMetadata metadata) {
        if (metadata == null) {
            return ExportSettings.createDefault();
        }
        
        // Determine quality based on resolution and bitrate
        int pixels = metadata.getWidth() * metadata.getHeight();
        int originalBitrate = metadata.getBitrate();
        
        if (pixels >= 3840 * 2160) { // 4K
            return ExportSettings.createHighQuality();
        } else if (pixels >= 1920 * 1080) { // 1080p
            if (originalBitrate > 15000000) { // High bitrate 1080p
                return ExportSettings.createHighQuality();
            } else {
                return ExportSettings.createMediumQuality();
            }
        } else if (pixels >= 1280 * 720) { // 720p
            return ExportSettings.createMediumQuality();
        } else {
            return ExportSettings.createLowQuality();
        }
    }
    
    /**
     * Get available export formats
     */
    public static String[] getAvailableFormats() {
        return new String[]{FORMAT_MP4, FORMAT_MOV};
    }
    
    /**
     * Get quality preset names
     */
    public static String[] getQualityPresetNames() {
        return new String[]{"Low", "Medium", "High", "Ultra"};
    }
    
    /**
     * Get quality preset values
     */
    public static int[] getQualityPresetValues() {
        return new int[]{QUALITY_LOW, QUALITY_MEDIUM, QUALITY_HIGH, QUALITY_ULTRA};
    }
    
    /**
     * Generate output file path
     */
    private File generateOutputFile(VideoProject project, ExportSettings settings) {
        // Use custom filename if provided
        String fileName = settings.outputFileName;
        
        if (fileName == null || fileName.trim().isEmpty()) {
            // Generate filename based on project name and timestamp
            String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
            fileName = project.getProjectName() + "_exported_" + timestamp;
        }
        
        // Ensure proper extension
        if (!fileName.toLowerCase().endsWith("." + settings.format)) {
            fileName += "." + settings.format;
        }
        
        // Create output directory
        File outputDir = new File(context.getExternalFilesDir(Environment.DIRECTORY_MOVIES), "FadCam/Exports");
        if (!outputDir.exists()) {
            outputDir.mkdirs();
        }
        
        return new File(outputDir, fileName);
    }
    
    /**
     * Calculate estimated file size for export
     */
    public long estimateFileSize(VideoMetadata metadata, ExportSettings settings) {
        if (metadata == null) {
            return 0;
        }
        
        long durationSeconds = metadata.getDuration() / 1000;
        int targetBitrate = calculateTargetBitrate(metadata, settings);
        
        // Estimate size: (bitrate * duration) / 8 (convert bits to bytes)
        // Add 10% overhead for container and metadata
        return (long) ((targetBitrate * durationSeconds / 8.0) * 1.1);
    }
    
    /**
     * Calculate target bitrate based on settings and metadata
     */
    private int calculateTargetBitrate(VideoMetadata metadata, ExportSettings settings) {
        if (settings.bitrate > 0) {
            return settings.bitrate;
        }
        
        int originalBitrate = metadata.getBitrate();
        int pixels = metadata.getWidth() * metadata.getHeight();
        float frameRate = metadata.getFrameRate() > 0 ? metadata.getFrameRate() : 30.0f;
        
        // Base bitrate calculation
        int baseBitrate;
        if (settings.maintainOriginalQuality && originalBitrate > 0) {
            baseBitrate = originalBitrate;
        } else {
            // Calculate based on quality setting and resolution
            float qualityFactor = settings.quality / 100.0f;
            
            if (pixels >= 3840 * 2160) { // 4K
                baseBitrate = (int) (25000000 * qualityFactor);
            } else if (pixels >= 1920 * 1080) { // 1080p
                baseBitrate = (int) (10000000 * qualityFactor);
            } else if (pixels >= 1280 * 720) { // 720p
                baseBitrate = (int) (6000000 * qualityFactor);
            } else {
                baseBitrate = (int) (3000000 * qualityFactor);
            }
            
            // Adjust for frame rate
            baseBitrate = (int) (baseBitrate * (frameRate / 30.0f));
        }
        
        // Ensure minimum bitrate
        return Math.max(baseBitrate, 500000); // 500 kbps minimum
    }
    
    /**
     * Export task that runs in background
     */
    private class ExportTask implements Runnable {
        private final VideoProject project;
        private final File outputFile;
        private final ExportSettings settings;
        private MediaCodecIntegration mediaCodec;
        private OpenGLVideoProcessor processor;
        private volatile boolean cancelled = false;
        
        public ExportTask(VideoProject project, File outputFile, ExportSettings settings) {
            this.project = project;
            this.outputFile = outputFile;
            this.settings = settings;
        }
        
        public void cancel() {
            cancelled = true;
            if (mediaCodec != null) {
                mediaCodec.release();
            }
            if (processor != null) {
                processor.cancelProcessing();
            }
        }
        
        @Override
        public void run() {
            try {
                // Check if cancelled before starting
                if (cancelled || isCancelled) {
                    return;
                }
                
                Log.d(TAG, "Starting export process for: " + project.getProjectName());
                
                // Initialize MediaCodec integration
                VideoMetadata metadata = project.getMetadata();
                mediaCodec = new MediaCodecIntegration();
                
                // Setup encoder with target settings
                VideoMetadata targetMetadata = createTargetMetadata(metadata, settings);
                mediaCodec.setupEncoder(targetMetadata, outputFile);
                
                // Initialize OpenGL processor
                processor = new OpenGLVideoProcessor(context);
                
                // Process video with progress tracking
                processor.processVideoForExport(
                    project,
                    mediaCodec.getInputSurface(),
                    new OpenGLVideoProcessor.ProcessingCallback() {
                        @Override
                        public void onProgress(int percentage) {
                            if (!cancelled && !isCancelled && currentCallback != null) {
                                currentCallback.onProgress(percentage);
                            }
                        }
                        
                        @Override
                        public void onFrameProcessed(long presentationTimeUs) {
                            if (!cancelled && !isCancelled) {
                                mediaCodec.encodeFrame(presentationTimeUs);
                            }
                        }
                        
                        @Override
                        public void onSuccess(File outputFile) {
                            finishExport(true, null);
                        }
                        
                        @Override
                        public void onError(String errorMessage) {
                            finishExport(false, errorMessage);
                        }
                    }
                );
                
            } catch (IOException e) {
                Log.e(TAG, "Export failed with IOException", e);
                finishExport(false, "Export failed: " + e.getMessage());
            } catch (Exception e) {
                Log.e(TAG, "Export failed with unexpected error", e);
                finishExport(false, "Unexpected error during export: " + e.getMessage());
            }
        }
        
        private VideoMetadata createTargetMetadata(VideoMetadata original, ExportSettings settings) {
            VideoMetadata target = new VideoMetadata();
            
            // Resolution
            int targetWidth = settings.width > 0 ? settings.width : original.getWidth();
            int targetHeight = settings.height > 0 ? settings.height : original.getHeight();
            target.setWidth(targetWidth);
            target.setHeight(targetHeight);
            
            // Bitrate
            int targetBitrate = calculateTargetBitrate(original, settings);
            target.setBitrate(targetBitrate);
            
            // Other properties
            target.setFrameRate(original.getFrameRate());
            target.setDuration(original.getDuration());
            target.setCodec(MediaCodecIntegration.getOptimalEncoderName(target));
            
            return target;
        }
        
        private void finishExport(boolean success, String errorMessage) {
            try {
                // Finish MediaCodec encoding
                if (mediaCodec != null) {
                    mediaCodec.finishEncoding();
                    mediaCodec.release();
                }
                
                // Clean up processor
                if (processor != null) {
                    processor.release();
                }
                
                isExporting = false;
                
                if (cancelled || isCancelled) {
                    // Clean up output file if cancelled
                    if (outputFile.exists()) {
                        outputFile.delete();
                    }
                    return;
                }
                
                // Notify callback
                if (currentCallback != null) {
                    if (success) {
                        Log.d(TAG, "Export completed successfully: " + outputFile.getAbsolutePath());
                        currentCallback.onSuccess(outputFile);
                    } else {
                        Log.e(TAG, "Export failed: " + errorMessage);
                        currentCallback.onError(errorMessage != null ? errorMessage : "Unknown export error");
                        
                        // Clean up failed output file
                        if (outputFile.exists()) {
                            outputFile.delete();
                        }
                    }
                    currentCallback = null;
                }
                
            } catch (Exception e) {
                Log.e(TAG, "Error finishing export", e);
                if (currentCallback != null) {
                    currentCallback.onError("Error finishing export: " + e.getMessage());
                    currentCallback = null;
                }
            }
        }
    }
    
    /**
     * Release resources
     */
    public void release() {
        cancelExport();
        if (executorService != null && !executorService.isShutdown()) {
            executorService.shutdown();
        }
    }
}