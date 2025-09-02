package com.fadcam.ui.faditor.processors.opengl;

import android.content.Context;
import android.util.Log;

import com.fadcam.ui.faditor.utils.PerformanceMonitor;

/**
 * Comprehensive OpenGL performance manager that integrates all optimization components.
 * Manages texture pooling, frame caching, memory optimization, and timeline performance
 * for professional 4K video editing with 60fps timeline scrubbing.
 * Requirements: 13.4, 13.5, 14.4, 14.7
 */
public class OpenGLPerformanceManager implements 
        GpuMemoryOptimizer.MemoryOptimizerListener,
        TimelineOptimizer.TimelineOptimizerListener {
    
    private static final String TAG = "OpenGLPerformanceManager";
    
    // Performance components
    private final TextureManager textureManager;
    private final FrameCache frameCache;
    private final GpuMemoryOptimizer memoryOptimizer;
    private final TimelineOptimizer timelineOptimizer;
    private final VideoRenderer videoRenderer;
    private final PerformanceMonitor performanceMonitor;
    
    // Performance state
    private boolean performanceOptimizationEnabled = true;
    private boolean is4KVideo = false;
    private long totalFrames = 0;
    
    // Performance statistics
    private long totalOptimizationEvents = 0;
    private long memoryOptimizationEvents = 0;
    private long timelineOptimizationEvents = 0;
    
    public interface PerformanceManagerListener {
        void onPerformanceOptimized(PerformanceStats stats);
        void onMemoryPressureDetected(long memoryUsageMB);
        void onTimelinePerformanceUpdate(double averageScrubTime, double cacheHitRate);
        void onEmergencyCleanupPerformed(long memoryFreedMB);
    }
    
    public static class PerformanceStats {
        public final TextureManager.TextureManagerStats textureStats;
        public final FrameCache.CacheStats cacheStats;
        public final GpuMemoryOptimizer.MemoryOptimizationStats memoryStats;
        public final TimelineOptimizer.OptimizationStats timelineStats;
        public final long totalOptimizationEvents;
        public final boolean performanceOptimal;
        
        PerformanceStats(TextureManager.TextureManagerStats textureStats,
                        FrameCache.CacheStats cacheStats,
                        GpuMemoryOptimizer.MemoryOptimizationStats memoryStats,
                        TimelineOptimizer.OptimizationStats timelineStats,
                        long totalOptimizationEvents,
                        boolean performanceOptimal) {
            this.textureStats = textureStats;
            this.cacheStats = cacheStats;
            this.memoryStats = memoryStats;
            this.timelineStats = timelineStats;
            this.totalOptimizationEvents = totalOptimizationEvents;
            this.performanceOptimal = performanceOptimal;
        }
    }
    
    private PerformanceManagerListener listener;
    
    public OpenGLPerformanceManager(Context context, VideoDecoder videoDecoder) {
        // Initialize core components
        this.textureManager = new TextureManager();
        this.frameCache = new FrameCache(textureManager);
        this.memoryOptimizer = new GpuMemoryOptimizer(textureManager, frameCache);
        this.videoRenderer = new VideoRenderer(context);
        this.timelineOptimizer = new TimelineOptimizer(frameCache, videoDecoder, videoRenderer);
        this.performanceMonitor = PerformanceMonitor.getInstance();
        
        // Set up component integration
        textureManager.setMemoryOptimizer(memoryOptimizer);
        memoryOptimizer.setListener(this);
        timelineOptimizer.setListener(this);
        
        // Enable performance monitoring
        performanceMonitor.enableMonitoring();
        
        Log.d(TAG, "OpenGLPerformanceManager initialized with all optimization components");
    }
    
    /**
     * Set performance manager listener
     */
    public void setListener(PerformanceManagerListener listener) {
        this.listener = listener;
    }
    
    /**
     * Configure for video properties
     */
    public void configureForVideo(int width, int height, long totalFrames) {
        this.is4KVideo = width >= 3840 || height >= 2160;
        this.totalFrames = totalFrames;
        
        // Configure components for video
        frameCache.setVideoProperties(width, height);
        videoRenderer.setVideoSize(width, height);
        timelineOptimizer.setVideoProperties(totalFrames);
        
        Log.d(TAG, String.format("Configured for %s video: %dx%d, %d frames", 
                is4KVideo ? "4K" : "HD/SD", width, height, totalFrames));
    }
    
    /**
     * Enable or disable performance optimization
     */
    public void setPerformanceOptimizationEnabled(boolean enabled) {
        this.performanceOptimizationEnabled = enabled;
        
        if (enabled) {
            performanceMonitor.enableMonitoring();
            Log.d(TAG, "Performance optimization enabled");
        } else {
            performanceMonitor.disableMonitoring();
            Log.d(TAG, "Performance optimization disabled");
        }
    }
    
    /**
     * Optimize timeline scrubbing to frame
     */
    public void optimizedSeekToFrame(long frameNumber) {
        if (!performanceOptimizationEnabled) {
            return;
        }
        
        performanceMonitor.startOperation("optimized_seek");
        timelineOptimizer.seekToFrame(frameNumber);
        performanceMonitor.endOperation("optimized_seek");
        
        timelineOptimizationEvents++;
    }
    
    /**
     * Start timeline scrubbing mode
     */
    public void startTimelineScrubbing() {
        if (!performanceOptimizationEnabled) {
            return;
        }
        
        timelineOptimizer.startScrubbing();
        Log.d(TAG, "Timeline scrubbing optimization started");
    }
    
    /**
     * Stop timeline scrubbing mode
     */
    public void stopTimelineScrubbing() {
        timelineOptimizer.stopScrubbing();
        Log.d(TAG, "Timeline scrubbing optimization stopped");
    }
    
    /**
     * Perform comprehensive performance optimization
     */
    public void optimizePerformance() {
        if (!performanceOptimizationEnabled) {
            return;
        }
        
        Log.d(TAG, "Performing comprehensive performance optimization");
        
        // Optimize memory
        memoryOptimizer.optimizeMemory();
        
        // Clean up texture pools
        textureManager.cleanupPool();
        
        // Update statistics
        totalOptimizationEvents++;
        memoryOptimizationEvents++;
        
        // Notify listener with current stats
        if (listener != null) {
            listener.onPerformanceOptimized(getPerformanceStats());
        }
    }
    
    /**
     * Get comprehensive performance statistics
     */
    public PerformanceStats getPerformanceStats() {
        TextureManager.TextureManagerStats textureStats = textureManager.getStats();
        FrameCache.CacheStats cacheStats = frameCache.getCacheStats();
        GpuMemoryOptimizer.MemoryOptimizationStats memoryStats = memoryOptimizer.getMemoryStats();
        TimelineOptimizer.OptimizationStats timelineStats = timelineOptimizer.getOptimizationStats();
        
        // Determine if performance is optimal
        boolean performanceOptimal = isPerformanceOptimal(textureStats, cacheStats, memoryStats, timelineStats);
        
        return new PerformanceStats(
            textureStats,
            cacheStats,
            memoryStats,
            timelineStats,
            totalOptimizationEvents,
            performanceOptimal
        );
    }
    
    /**
     * Log comprehensive performance summary
     */
    public void logPerformanceSummary() {
        Log.i(TAG, "=== OpenGL Performance Summary ===");
        
        PerformanceStats stats = getPerformanceStats();
        
        // Texture management
        Log.i(TAG, String.format("Textures: %d active, %d pooled, %d MB total, %d MB 4K", 
                stats.textureStats.activeTextures,
                stats.textureStats.pooledTextures,
                stats.textureStats.totalMemoryMB,
                stats.textureStats.texture4KMemoryMB));
        
        // Frame cache
        Log.i(TAG, String.format("Frame Cache: %d cached, %d MB, %.1f%% hit rate", 
                stats.cacheStats.cachedFrames,
                stats.cacheStats.memoryUsageMB,
                stats.cacheStats.hitRate * 100));
        
        // Memory optimization
        Log.i(TAG, String.format("Memory: %d MB total, %d MB peak, %d pressure events", 
                stats.memoryStats.totalGpuMemoryMB,
                stats.memoryStats.peakGpuMemoryMB,
                stats.memoryStats.memoryPressureEvents));
        
        // Timeline optimization
        Log.i(TAG, String.format("Timeline: %d operations, %.1f%% fast, %.1f%% cached, %.1fms avg", 
                stats.timelineStats.totalScrubOperations,
                stats.timelineStats.fastScrubPercentage,
                stats.timelineStats.cacheHitPercentage,
                stats.timelineStats.averageScrubTime));
        
        Log.i(TAG, String.format("Overall Performance: %s (%d optimization events)", 
                stats.performanceOptimal ? "OPTIMAL" : "NEEDS OPTIMIZATION",
                stats.totalOptimizationEvents));
        
        Log.i(TAG, "================================");
        
        // Also log performance monitor summary
        performanceMonitor.logPerformanceSummary();
    }
    
    /**
     * Reset all performance statistics
     */
    public void resetPerformanceStats() {
        totalOptimizationEvents = 0;
        memoryOptimizationEvents = 0;
        timelineOptimizationEvents = 0;
        
        memoryOptimizer.resetStats();
        performanceMonitor.clearPerformanceData();
        
        Log.d(TAG, "Performance statistics reset");
    }
    
    /**
     * Clear all caches and optimization data for new video
     */
    public void clearOptimizationData() {
        frameCache.clearCache();
        timelineOptimizer.clearOptimizationData();
        videoRenderer.clearFrameCache();
        
        Log.d(TAG, "Optimization data cleared for new video");
    }
    
    // GpuMemoryOptimizer.MemoryOptimizerListener implementation
    
    @Override
    public void onMemoryWarning(long memoryUsageMB) {
        Log.w(TAG, "Memory warning: " + memoryUsageMB + " MB");
        
        // Perform light optimization
        textureManager.cleanupPool();
    }
    
    @Override
    public void onMemoryPressure(long memoryUsageMB) {
        Log.w(TAG, "Memory pressure detected: " + memoryUsageMB + " MB");
        
        if (listener != null) {
            listener.onMemoryPressureDetected(memoryUsageMB);
        }
        
        // Perform aggressive optimization
        optimizePerformance();
    }
    
    @Override
    public void onEmergencyCleanup(long memoryFreedMB) {
        Log.w(TAG, "Emergency cleanup performed: " + memoryFreedMB + " MB freed");
        
        if (listener != null) {
            listener.onEmergencyCleanupPerformed(memoryFreedMB);
        }
    }
    
    @Override
    public void onMemoryOptimized(GpuMemoryOptimizer.MemoryOptimizationStats stats) {
        Log.d(TAG, "Memory optimization completed");
        memoryOptimizationEvents++;
    }
    
    // TimelineOptimizer.TimelineOptimizerListener implementation
    
    @Override
    public void onFrameReady(long frameNumber, int textureId) {
        // Frame is ready for rendering
        Log.v(TAG, "Frame ready: " + frameNumber);
    }
    
    @Override
    public void onPrefetchCompleted(long frameNumber) {
        // Prefetch completed
        Log.v(TAG, "Prefetch completed: " + frameNumber);
    }
    
    @Override
    public void onOptimizationStats(TimelineOptimizer.OptimizationStats stats) {
        if (listener != null) {
            listener.onTimelinePerformanceUpdate(stats.averageScrubTime, stats.cacheHitPercentage / 100.0);
        }
    }
    
    // Private helper methods
    
    private boolean isPerformanceOptimal(TextureManager.TextureManagerStats textureStats,
                                       FrameCache.CacheStats cacheStats,
                                       GpuMemoryOptimizer.MemoryOptimizationStats memoryStats,
                                       TimelineOptimizer.OptimizationStats timelineStats) {
        
        // Check memory usage
        if (memoryStats.memoryPressureActive || memoryStats.totalGpuMemoryMB > 400) {
            return false;
        }
        
        // Check timeline performance
        if (timelineStats.averageScrubTime > 20) { // Should be under 16.67ms for 60fps
            return false;
        }
        
        // Check cache hit rate
        if (cacheStats.hitRate < 0.7) { // Should have at least 70% cache hit rate
            return false;
        }
        
        // Check if performance monitor indicates acceptable performance
        return performanceMonitor.isPerformanceAcceptable();
    }
    
    /**
     * Get individual component references for advanced usage
     */
    public TextureManager getTextureManager() { return textureManager; }
    public FrameCache getFrameCache() { return frameCache; }
    public GpuMemoryOptimizer getMemoryOptimizer() { return memoryOptimizer; }
    public TimelineOptimizer getTimelineOptimizer() { return timelineOptimizer; }
    public VideoRenderer getVideoRenderer() { return videoRenderer; }
    
    /**
     * Release all resources
     */
    public void release() {
        Log.d(TAG, "Releasing OpenGLPerformanceManager resources");
        
        // Stop performance monitoring
        performanceMonitor.disableMonitoring();
        
        // Release all components
        timelineOptimizer.release();
        memoryOptimizer.release();
        frameCache.release();
        videoRenderer.release();
        textureManager.release();
        
        listener = null;
        
        Log.d(TAG, "All OpenGL performance resources released");
    }
}