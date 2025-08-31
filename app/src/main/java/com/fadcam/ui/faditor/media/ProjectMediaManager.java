package com.fadcam.ui.faditor.media;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import com.fadcam.Log;
import com.fadcam.ui.faditor.utils.VideoFileUtils;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Professional media management system for video editing projects.
 * Handles media import, organization, and proxy generation like professional video editors.
 * 
 * Features:
 * - Project-based media organization
 * - Media copying/linking to project location
 * - Proxy file generation for smooth editing
 * - Media cache management
 * - Relative path management for portability
 */
public class ProjectMediaManager {
    private static final String TAG = "ProjectMediaManager";
    
    // Directory structure like professional editors
    private static final String MEDIA_DIR = "media";
    private static final String ORIGINAL_DIR = "original";
    private static final String PROXY_DIR = "proxy";
    private static final String CACHE_DIR = "cache";
    private static final String THUMBNAILS_DIR = "thumbnails";
    
    public enum MediaImportStrategy {
        COPY_TO_PROJECT,    // Copy media files to project (like Final Cut Pro)
        LINK_ORIGINAL,      // Keep original location, create links (like Premiere Pro)
        HYBRID             // Copy small files, link large files
    }
    
    public enum ProxyQuality {
        QUARTER_RES,    // 25% resolution for smooth editing
        HALF_RES,       // 50% resolution
        FULL_RES        // Original resolution (no proxy)
    }
    
    public interface MediaImportListener {
        void onImportStarted(String mediaId, String filename);
        void onImportProgress(String mediaId, int progress);
        void onImportCompleted(String mediaId, ProjectMediaAsset asset);
        void onImportFailed(String mediaId, String error);
        void onProxyGenerationStarted(String mediaId);
        void onProxyGenerationCompleted(String mediaId, File proxyFile);
        void onProxyGenerationFailed(String mediaId, String error);
    }
    
    private final Context context;
    private final String projectId;
    private final File projectDirectory;
    private final File mediaDirectory;
    private final File originalDirectory;
    private final File proxyDirectory;
    private final File cacheDirectory;
    private final File thumbnailsDirectory;
    
    private final ExecutorService importExecutor;
    private final ExecutorService proxyExecutor;
    private final Handler mainHandler;
    
    private final Map<String, ProjectMediaAsset> mediaAssets;
    private MediaImportStrategy importStrategy;
    private ProxyQuality defaultProxyQuality;
    private long maxFileSizeForCopy; // Files larger than this will be linked instead of copied
    
    public ProjectMediaManager(Context context, String projectId, File projectDirectory) {
        this.context = context;
        this.projectId = projectId;
        this.projectDirectory = projectDirectory;
        
        // Create professional directory structure
        this.mediaDirectory = new File(projectDirectory, MEDIA_DIR);
        this.originalDirectory = new File(mediaDirectory, ORIGINAL_DIR);
        this.proxyDirectory = new File(mediaDirectory, PROXY_DIR);
        this.cacheDirectory = new File(mediaDirectory, CACHE_DIR);
        this.thumbnailsDirectory = new File(mediaDirectory, THUMBNAILS_DIR);
        
        // Create directories
        createDirectoryStructure();
        
        // Initialize executors
        this.importExecutor = Executors.newFixedThreadPool(2);
        this.proxyExecutor = Executors.newFixedThreadPool(1);
        this.mainHandler = new Handler(Looper.getMainLooper());
        
        // Initialize collections
        this.mediaAssets = new HashMap<>();
        
        // Default settings
        this.importStrategy = MediaImportStrategy.HYBRID;
        this.defaultProxyQuality = ProxyQuality.HALF_RES;
        this.maxFileSizeForCopy = 500 * 1024 * 1024; // 500MB threshold
        
        Log.d(TAG, "ProjectMediaManager initialized for project: " + projectId);
    }
    
    private void createDirectoryStructure() {
        try {
            mediaDirectory.mkdirs();
            originalDirectory.mkdirs();
            proxyDirectory.mkdirs();
            cacheDirectory.mkdirs();
            thumbnailsDirectory.mkdirs();
            
            Log.d(TAG, "Created project media directory structure at: " + mediaDirectory.getAbsolutePath());
        } catch (Exception e) {
            Log.e(TAG, "Failed to create directory structure", e);
        }
    }
    
    /**
     * Import media file into the project using professional media management
     */
    public void importMedia(Uri sourceUri, String filename, MediaImportListener listener) {
        String mediaId = UUID.randomUUID().toString();
        
        Log.d(TAG, "Starting media import: " + filename + " with ID: " + mediaId);
        
        if (listener != null) {
            listener.onImportStarted(mediaId, filename);
        }
        
        importExecutor.execute(() -> {
            try {
                // Analyze source media
                MediaAnalysis analysis = analyzeMedia(sourceUri, filename);
                
                // Determine import strategy based on file size and type
                MediaImportStrategy strategy = determineImportStrategy(analysis);
                
                // Import the media file
                ProjectMediaAsset asset = importMediaFile(mediaId, sourceUri, filename, analysis, strategy, listener);
                
                // Store asset
                synchronized (mediaAssets) {
                    mediaAssets.put(mediaId, asset);
                }
                
                // Generate proxy if needed
                if (shouldGenerateProxy(analysis)) {
                    generateProxy(asset, listener);
                }
                
                // Generate thumbnail
                generateThumbnail(asset);
                
                // Notify completion
                if (listener != null) {
                    mainHandler.post(() -> listener.onImportCompleted(mediaId, asset));
                }
                
                Log.d(TAG, "Media import completed: " + mediaId);
                
            } catch (Exception e) {
                Log.e(TAG, "Media import failed: " + mediaId, e);
                if (listener != null) {
                    mainHandler.post(() -> listener.onImportFailed(mediaId, e.getMessage()));
                }
            }
        });
    }
    
    private MediaAnalysis analyzeMedia(Uri sourceUri, String filename) throws IOException {
        MediaAnalysis analysis = new MediaAnalysis();
        analysis.sourceUri = sourceUri;
        analysis.filename = filename;
        analysis.fileSize = VideoFileUtils.getFileSize(context, sourceUri);
        analysis.mimeType = VideoFileUtils.getMimeType(context, sourceUri);
        analysis.isVideo = analysis.mimeType != null && analysis.mimeType.startsWith("video/");
        
        if (analysis.isVideo) {
            // Get video metadata
            VideoFileUtils.VideoInfo videoInfo = VideoFileUtils.getVideoInfo(context, sourceUri);
            if (videoInfo != null && videoInfo.metadata != null) {
                analysis.width = videoInfo.metadata.getWidth();
                analysis.height = videoInfo.metadata.getHeight();
                analysis.duration = videoInfo.metadata.getDuration();
                analysis.bitrate = videoInfo.metadata.getBitrate();
                analysis.frameRate = videoInfo.metadata.getFrameRate();
                analysis.isHighResolution = analysis.width >= 1920 || analysis.height >= 1080;
                analysis.isLargeFile = analysis.fileSize > maxFileSizeForCopy;
            }
        }
        
        Log.d(TAG, "Media analysis completed: " + analysis.toString());
        return analysis;
    }
    
    private MediaImportStrategy determineImportStrategy(MediaAnalysis analysis) {
        switch (importStrategy) {
            case COPY_TO_PROJECT:
                return MediaImportStrategy.COPY_TO_PROJECT;
            case LINK_ORIGINAL:
                return MediaImportStrategy.LINK_ORIGINAL;
            case HYBRID:
            default:
                // Use hybrid strategy: copy small files, link large files
                return analysis.isLargeFile ? MediaImportStrategy.LINK_ORIGINAL : MediaImportStrategy.COPY_TO_PROJECT;
        }
    }
    
    private ProjectMediaAsset importMediaFile(String mediaId, Uri sourceUri, String filename, 
                                            MediaAnalysis analysis, MediaImportStrategy strategy, 
                                            MediaImportListener listener) throws IOException {
        
        ProjectMediaAsset asset = new ProjectMediaAsset();
        asset.mediaId = mediaId;
        asset.originalFilename = filename;
        asset.sourceUri = sourceUri;
        asset.importStrategy = strategy;
        asset.analysis = analysis;
        asset.importTimestamp = System.currentTimeMillis();
        
        if (strategy == MediaImportStrategy.COPY_TO_PROJECT) {
            // Copy file to project directory
            File targetFile = new File(originalDirectory, mediaId + "_" + filename);
            copyMediaFile(sourceUri, targetFile, listener, mediaId);
            
            asset.projectFilePath = getRelativePath(targetFile);
            asset.absoluteFilePath = targetFile.getAbsolutePath();
            asset.isLinked = false;
            
            Log.d(TAG, "Media copied to project: " + targetFile.getAbsolutePath());
            
        } else {
            // Link to original file
            asset.projectFilePath = null; // No project copy
            asset.absoluteFilePath = null; // Will resolve from URI
            asset.isLinked = true;
            
            Log.d(TAG, "Media linked to original: " + sourceUri.toString());
        }
        
        return asset;
    }
    
    private void copyMediaFile(Uri sourceUri, File targetFile, MediaImportListener listener, String mediaId) throws IOException {
        try (InputStream input = context.getContentResolver().openInputStream(sourceUri);
             OutputStream output = new FileOutputStream(targetFile)) {
            
            if (input == null) {
                throw new IOException("Cannot open source file");
            }
            
            byte[] buffer = new byte[64 * 1024]; // 64KB buffer
            long totalBytes = VideoFileUtils.getFileSize(context, sourceUri);
            long copiedBytes = 0;
            int bytesRead;
            int lastProgress = 0;
            
            while ((bytesRead = input.read(buffer)) != -1) {
                output.write(buffer, 0, bytesRead);
                copiedBytes += bytesRead;
                
                // Report progress
                if (totalBytes > 0 && listener != null) {
                    int progress = (int) ((copiedBytes * 100) / totalBytes);
                    if (progress != lastProgress && progress % 5 == 0) { // Report every 5%
                        lastProgress = progress;
                        mainHandler.post(() -> listener.onImportProgress(mediaId, progress));
                    }
                }
            }
            
            Log.d(TAG, "File copied successfully: " + copiedBytes + " bytes");
        }
    }
    
    private boolean shouldGenerateProxy(MediaAnalysis analysis) {
        if (!analysis.isVideo) {
            return false;
        }
        
        // Generate proxy for high-resolution or large files
        return analysis.isHighResolution || analysis.isLargeFile || defaultProxyQuality != ProxyQuality.FULL_RES;
    }
    
    private void generateProxy(ProjectMediaAsset asset, MediaImportListener listener) {
        if (listener != null) {
            mainHandler.post(() -> listener.onProxyGenerationStarted(asset.mediaId));
        }
        
        proxyExecutor.execute(() -> {
            try {
                File proxyFile = new File(proxyDirectory, asset.mediaId + "_proxy.mp4");
                
                // Generate proxy using MediaCodec or FFmpeg
                generateProxyFile(asset, proxyFile);
                
                asset.proxyFilePath = getRelativePath(proxyFile);
                asset.hasProxy = true;
                
                if (listener != null) {
                    mainHandler.post(() -> listener.onProxyGenerationCompleted(asset.mediaId, proxyFile));
                }
                
                Log.d(TAG, "Proxy generated: " + proxyFile.getAbsolutePath());
                
            } catch (Exception e) {
                Log.e(TAG, "Proxy generation failed for: " + asset.mediaId, e);
                if (listener != null) {
                    mainHandler.post(() -> listener.onProxyGenerationFailed(asset.mediaId, e.getMessage()));
                }
            }
        });
    }
    
    private void generateProxyFile(ProjectMediaAsset asset, File proxyFile) throws IOException {
        // This would use MediaCodec or FFmpeg to generate a lower resolution proxy
        // For now, we'll create a placeholder implementation
        
        Uri sourceUri = getMediaUri(asset);
        
        // Determine proxy resolution based on quality setting
        int targetWidth, targetHeight;
        switch (defaultProxyQuality) {
            case QUARTER_RES:
                targetWidth = asset.analysis.width / 4;
                targetHeight = asset.analysis.height / 4;
                break;
            case HALF_RES:
                targetWidth = asset.analysis.width / 2;
                targetHeight = asset.analysis.height / 2;
                break;
            default:
                targetWidth = asset.analysis.width;
                targetHeight = asset.analysis.height;
                break;
        }
        
        // TODO: Implement actual proxy generation using MediaCodec
        // For now, we'll just copy the original file as a placeholder
        Log.d(TAG, "Generating proxy: " + targetWidth + "x" + targetHeight + " for " + asset.mediaId);
        
        // Placeholder: copy original file
        if (asset.isLinked) {
            copyMediaFile(sourceUri, proxyFile, null, asset.mediaId);
        } else {
            File originalFile = new File(projectDirectory, asset.projectFilePath);
            try (FileInputStream input = new FileInputStream(originalFile);
                 FileOutputStream output = new FileOutputStream(proxyFile)) {
                
                byte[] buffer = new byte[64 * 1024];
                int bytesRead;
                while ((bytesRead = input.read(buffer)) != -1) {
                    output.write(buffer, 0, bytesRead);
                }
            }
        }
    }
    
    private void generateThumbnail(ProjectMediaAsset asset) {
        // Generate thumbnail for video preview
        // This would extract a frame from the video
        Log.d(TAG, "Generating thumbnail for: " + asset.mediaId);
        
        // TODO: Implement thumbnail generation
        File thumbnailFile = new File(thumbnailsDirectory, asset.mediaId + "_thumb.jpg");
        asset.thumbnailPath = getRelativePath(thumbnailFile);
    }
    
    /**
     * Get the URI for a media asset, handling both copied and linked files
     */
    public Uri getMediaUri(ProjectMediaAsset asset) {
        if (asset.isLinked) {
            return asset.sourceUri;
        } else {
            File mediaFile = new File(projectDirectory, asset.projectFilePath);
            return Uri.fromFile(mediaFile);
        }
    }
    
    /**
     * Get the URI for proxy file if available, otherwise return original
     */
    public Uri getPlaybackUri(ProjectMediaAsset asset) {
        if (asset.hasProxy && asset.proxyFilePath != null) {
            File proxyFile = new File(projectDirectory, asset.proxyFilePath);
            return Uri.fromFile(proxyFile);
        } else {
            return getMediaUri(asset);
        }
    }
    
    /**
     * Get media asset by ID
     */
    public ProjectMediaAsset getMediaAsset(String mediaId) {
        synchronized (mediaAssets) {
            return mediaAssets.get(mediaId);
        }
    }
    
    /**
     * Get all media assets in the project
     */
    public List<ProjectMediaAsset> getAllMediaAssets() {
        synchronized (mediaAssets) {
            return new ArrayList<>(mediaAssets.values());
        }
    }
    
    /**
     * Remove media asset from project
     */
    public void removeMediaAsset(String mediaId) {
        ProjectMediaAsset asset;
        synchronized (mediaAssets) {
            asset = mediaAssets.remove(mediaId);
        }
        
        if (asset != null) {
            // Clean up files
            if (!asset.isLinked && asset.projectFilePath != null) {
                File mediaFile = new File(projectDirectory, asset.projectFilePath);
                if (mediaFile.exists()) {
                    mediaFile.delete();
                }
            }
            
            if (asset.hasProxy && asset.proxyFilePath != null) {
                File proxyFile = new File(projectDirectory, asset.proxyFilePath);
                if (proxyFile.exists()) {
                    proxyFile.delete();
                }
            }
            
            if (asset.thumbnailPath != null) {
                File thumbnailFile = new File(projectDirectory, asset.thumbnailPath);
                if (thumbnailFile.exists()) {
                    thumbnailFile.delete();
                }
            }
            
            Log.d(TAG, "Media asset removed: " + mediaId);
        }
    }
    
    /**
     * Clean up cache and temporary files
     */
    public void cleanupCache() {
        // Clean up cache directory
        File[] cacheFiles = cacheDirectory.listFiles();
        if (cacheFiles != null) {
            for (File file : cacheFiles) {
                file.delete();
            }
        }
        
        Log.d(TAG, "Cache cleaned up");
    }
    
    /**
     * Get project storage usage
     */
    public ProjectStorageInfo getStorageInfo() {
        ProjectStorageInfo info = new ProjectStorageInfo();
        
        info.originalFilesSize = calculateDirectorySize(originalDirectory);
        info.proxyFilesSize = calculateDirectorySize(proxyDirectory);
        info.cacheSize = calculateDirectorySize(cacheDirectory);
        info.thumbnailsSize = calculateDirectorySize(thumbnailsDirectory);
        info.totalSize = info.originalFilesSize + info.proxyFilesSize + info.cacheSize + info.thumbnailsSize;
        
        synchronized (mediaAssets) {
            info.mediaCount = mediaAssets.size();
            info.linkedCount = (int) mediaAssets.values().stream().filter(a -> a.isLinked).count();
            info.copiedCount = info.mediaCount - info.linkedCount;
        }
        
        return info;
    }
    
    private long calculateDirectorySize(File directory) {
        long size = 0;
        File[] files = directory.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isFile()) {
                    size += file.length();
                } else if (file.isDirectory()) {
                    size += calculateDirectorySize(file);
                }
            }
        }
        return size;
    }
    
    private String getRelativePath(File file) {
        return projectDirectory.toURI().relativize(file.toURI()).getPath();
    }
    
    /**
     * Set import strategy for new media
     */
    public void setImportStrategy(MediaImportStrategy strategy) {
        this.importStrategy = strategy;
        Log.d(TAG, "Import strategy set to: " + strategy);
    }
    
    /**
     * Set default proxy quality
     */
    public void setDefaultProxyQuality(ProxyQuality quality) {
        this.defaultProxyQuality = quality;
        Log.d(TAG, "Default proxy quality set to: " + quality);
    }
    
    /**
     * Set maximum file size for copying (larger files will be linked)
     */
    public void setMaxFileSizeForCopy(long maxSize) {
        this.maxFileSizeForCopy = maxSize;
        Log.d(TAG, "Max file size for copy set to: " + (maxSize / 1024 / 1024) + "MB");
    }
    
    /**
     * Shutdown the media manager
     */
    public void shutdown() {
        importExecutor.shutdown();
        proxyExecutor.shutdown();
        Log.d(TAG, "ProjectMediaManager shutdown");
    }
    
    // Data classes
    
    public static class MediaAnalysis {
        public Uri sourceUri;
        public String filename;
        public long fileSize;
        public String mimeType;
        public boolean isVideo;
        public int width;
        public int height;
        public long duration;
        public int bitrate;
        public float frameRate;
        public boolean isHighResolution;
        public boolean isLargeFile;
        
        @Override
        public String toString() {
            return String.format("MediaAnalysis{filename='%s', size=%dMB, %dx%d, duration=%ds, isLarge=%s}", 
                               filename, fileSize / 1024 / 1024, width, height, duration / 1000, isLargeFile);
        }
    }
    
    public static class ProjectStorageInfo {
        public long originalFilesSize;
        public long proxyFilesSize;
        public long cacheSize;
        public long thumbnailsSize;
        public long totalSize;
        public int mediaCount;
        public int copiedCount;
        public int linkedCount;
        
        public String getFormattedSize(long bytes) {
            if (bytes < 1024) return bytes + " B";
            if (bytes < 1024 * 1024) return (bytes / 1024) + " KB";
            if (bytes < 1024 * 1024 * 1024) return (bytes / 1024 / 1024) + " MB";
            return (bytes / 1024 / 1024 / 1024) + " GB";
        }
    }
}