package com.fadcam.ui.faditor.processors.opengl;

import android.opengl.GLES20;
import android.util.Log;
import android.util.LruCache;

import com.fadcam.ui.faditor.utils.PerformanceMonitor;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Frame caching system for smooth timeline scrubbing at 60fps.
 * Implements intelligent frame caching with memory management for 4K video support.
 * Requirements: 13.4, 13.5, 14.4, 14.7
 */
public class FrameCache {
    
    private static final String TAG = "FrameCache";
    
    // Enhanced cache configuration for professional 60fps timeline scrubbing
    private static final int MAX_CACHED_FRAMES = 180; // 3 seconds at 60fps for smoother scrubbing
    private static final int MAX_4K_CACHED_FRAMES = 45; // Increased for better 4K timeline performance
    private static final long MAX_CACHE_MEMORY_MB = 300; // 300MB cache memory limit for professional editing
    private static final long CACHE_CLEANUP_INTERVAL_MS = 8000; // 8 seconds for more responsive cleanup
    
    // Professional timeline scrubbing optimizations
    private static final int PREFETCH_RADIUS_SCRUBBING = 15; // Larger prefetch radius during scrubbing
    private static final int PREFETCH_RADIUS_NORMAL = 5; // Smaller radius during normal playback
    private static final long FRAME_ACCESS_PRIORITY_MS = 2000; // Recently accessed frames get priority
    
    // Frame cache entry
    public static class CachedFrame {
        final int textureId;
        final long frameNumber;
        final long timestampUs;
        final int width;
        final int height;
        final long memorySize;
        final long cacheTime;
        volatile boolean isReady;
        int accessCount;
        long lastAccessTime;
        
        CachedFrame(int textureId, long frameNumber, long timestampUs, int width, int height) {
            this.textureId = textureId;
            this.frameNumber = frameNumber;
            this.timestampUs = timestampUs;
            this.width = width;
            this.height = height;
            this.memorySize = calculateFrameMemorySize(width, height);
            this.cacheTime = System.currentTimeMillis();
            this.isReady = false;
            this.accessCount = 0;
            this.lastAccessTime = System.currentTimeMillis();
        }
        
        private long calculateFrameMemorySize(int width, int height) {
            return (long) width * height * 4; // RGBA format
        }
        
        boolean is4K() {
            return width >= 3840 || height >= 2160;
        }
        
        void markAccessed() {
            accessCount++;
            lastAccessTime = System.currentTimeMillis();
        }
    }
    
    // Cache storage using LRU eviction
    private final LruCache<Long, CachedFrame> frameCache;
    private final ConcurrentHashMap<Long, CachedFrame> pendingFrames;
    private final TextureManager textureManager;
    private final PerformanceMonitor performanceMonitor;
    
    // Memory tracking
    private final AtomicLong totalCacheMemory = new AtomicLong(0);
    private final AtomicLong total4KCacheMemory = new AtomicLong(0);
    private long lastCleanupTime = 0;
    
    // Cache statistics
    private long cacheHits = 0;
    private long cacheMisses = 0;
    private long framesEvicted = 0;
    
    // Video properties for cache optimization
    private int videoWidth = 0;
    private int videoHeight = 0;
    private boolean is4KVideo = false;
    
    // Timeline scrubbing state for optimization
    private boolean isTimelineScrubbing = false;
    private long lastScrubTime = 0;
    private long currentScrubFrame = -1;
    
    public FrameCache(TextureManager textureManager) {
        this.textureManager = textureManager;
        this.performanceMonitor = PerformanceMonitor.getInstance();
        this.pendingFrames = new ConcurrentHashMap<>();
        this.lastCleanupTime = System.currentTimeMillis();
        
        // Initialize LRU cache with custom eviction logic
        int maxFrames = MAX_CACHED_FRAMES;
        this.frameCache = new LruCache<Long, CachedFrame>(maxFrames) {
            @Override
            protected int sizeOf(Long key, CachedFrame frame) {
                // Use memory size for more accurate cache sizing
                return (int) (frame.memorySize / (1024 * 1024)); // Convert to MB
            }
            
            @Override
            protected void entryRemoved(boolean evicted, Long key, CachedFrame oldFrame, CachedFrame newFrame) {
                if (evicted && oldFrame != null) {
                    evictFrame(oldFrame);
                }
            }
        };
        
        Log.d(TAG, "FrameCache initialized with capacity: " + maxFrames + " frames");
    }
    
    /**
     * Set video dimensions for cache optimization
     */
    public void setVideoProperties(int width, int height) {
        this.videoWidth = width;
        this.videoHeight = height;
        this.is4KVideo = width >= 3840 || height >= 2160;
        
        // Adjust cache size based on video resolution
        int maxFrames = is4KVideo ? MAX_4K_CACHED_FRAMES : MAX_CACHED_FRAMES;
        frameCache.resize(maxFrames);
        
        Log.d(TAG, "Video properties set: " + width + "x" + height + 
                  (is4KVideo ? " (4K)" : " (HD/SD)") + ", cache size: " + maxFrames);
    }
    
    /**
     * Cache a frame for timeline scrubbing
     */
    public void cacheFrame(long frameNumber, long timestampUs, int textureId) {
        if (textureId == 0 || videoWidth == 0 || videoHeight == 0) {
            return;
        }
        
        performanceMonitor.startOperation("frame_cache");
        
        // Check if frame is already cached or pending
        if (frameCache.get(frameNumber) != null || pendingFrames.containsKey(frameNumber)) {
            performanceMonitor.endOperation("frame_cache");
            return;
        }
        
        // Check memory limits before caching
        if (!canCacheFrame()) {
            performanceMonitor.endOperation("frame_cache");
            return;
        }
        
        // Create cached frame entry
        CachedFrame cachedFrame = new CachedFrame(textureId, frameNumber, timestampUs, videoWidth, videoHeight);
        
        // Add to pending frames first
        pendingFrames.put(frameNumber, cachedFrame);
        
        // Copy texture data to cache texture (this should be done on GL thread)
        int cacheTextureId = textureManager.createTexture(videoWidth, videoHeight, GLES20.GL_RGBA);
        if (cacheTextureId != 0) {
            // Copy frame data to cache texture
            copyFrameToCache(textureId, cacheTextureId);
            
            cachedFrame.isReady = true;
            
            // Move from pending to cache
            pendingFrames.remove(frameNumber);
            frameCache.put(frameNumber, cachedFrame);
            
            // Update memory tracking
            totalCacheMemory.addAndGet(cachedFrame.memorySize);
            if (cachedFrame.is4K()) {
                total4KCacheMemory.addAndGet(cachedFrame.memorySize);
            }
            
            Log.d(TAG, "Frame cached: " + frameNumber + " (texture: " + cacheTextureId + ")");
        } else {
            // Failed to create cache texture
            pendingFrames.remove(frameNumber);
            Log.w(TAG, "Failed to create cache texture for frame: " + frameNumber);
        }
        
        performanceMonitor.endOperation("frame_cache");
        
        // Periodic cleanup
        checkAndPerformCleanup();
    }
    
    /**
     * Get cached frame for timeline scrubbing
     */
    public CachedFrame getCachedFrame(long frameNumber) {
        CachedFrame cachedFrame = frameCache.get(frameNumber);
        if (cachedFrame != null && cachedFrame.isReady) {
            cachedFrame.markAccessed();
            cacheHits++;
            return cachedFrame;
        }
        
        cacheMisses++;
        return null;
    }
    
    /**
     * Check if frame is cached and ready
     */
    public boolean isFrameCached(long frameNumber) {
        CachedFrame cachedFrame = frameCache.get(frameNumber);
        return cachedFrame != null && cachedFrame.isReady;
    }
    
    /**
     * Enable timeline scrubbing mode for optimized caching
     */
    public void enableTimelineScrubbing(boolean enabled) {
        this.isTimelineScrubbing = enabled;
        this.lastScrubTime = System.currentTimeMillis();
        
        if (enabled) {
            // Adjust cache size for scrubbing
            int maxFrames = is4KVideo ? MAX_4K_CACHED_FRAMES : MAX_CACHED_FRAMES;
            frameCache.resize(maxFrames);
            Log.d(TAG, "Timeline scrubbing mode enabled - cache size: " + maxFrames);
        } else {
            Log.d(TAG, "Timeline scrubbing mode disabled");
        }
    }
    
    /**
     * Optimized frame caching for timeline scrubbing with priority management
     */
    public void cacheFrameForScrubbing(long frameNumber, long timestampUs, int textureId) {
        this.currentScrubFrame = frameNumber;
        this.lastScrubTime = System.currentTimeMillis();
        
        // Use high-priority caching for scrubbing
        cacheFrameWithPriority(frameNumber, timestampUs, textureId, true);
    }
    
    /**
     * Cache frame with priority management
     */
    private void cacheFrameWithPriority(long frameNumber, long timestampUs, int textureId, boolean highPriority) {
        if (textureId == 0 || videoWidth == 0 || videoHeight == 0) {
            return;
        }
        
        performanceMonitor.startOperation("priority_frame_cache");
        
        // Check if frame is already cached or pending
        if (frameCache.get(frameNumber) != null || pendingFrames.containsKey(frameNumber)) {
            performanceMonitor.endOperation("priority_frame_cache");
            return;
        }
        
        // For high priority frames, make room if needed
        if (highPriority && !canCacheFrame()) {
            evictLeastRecentlyUsedFrame();
        }
        
        // Check memory limits after potential eviction
        if (!canCacheFrame()) {
            performanceMonitor.endOperation("priority_frame_cache");
            return;
        }
        
        // Create cached frame entry with priority marking
        CachedFrame cachedFrame = new CachedFrame(textureId, frameNumber, timestampUs, videoWidth, videoHeight);
        if (highPriority) {
            cachedFrame.accessCount = 10; // Give high priority frames higher access count
        }
        
        // Add to pending frames first
        pendingFrames.put(frameNumber, cachedFrame);
        
        // Copy texture data to cache texture (this should be done on GL thread)
        int cacheTextureId = textureManager.createCacheTexture(videoWidth, videoHeight);
        if (cacheTextureId != 0) {
            // Copy frame data to cache texture
            copyFrameToCache(textureId, cacheTextureId);
            
            cachedFrame.isReady = true;
            
            // Move from pending to cache
            pendingFrames.remove(frameNumber);
            frameCache.put(frameNumber, cachedFrame);
            
            // Update memory tracking
            totalCacheMemory.addAndGet(cachedFrame.memorySize);
            if (cachedFrame.is4K()) {
                total4KCacheMemory.addAndGet(cachedFrame.memorySize);
            }
            
            Log.d(TAG, "Frame cached with " + (highPriority ? "HIGH" : "normal") + " priority: " + frameNumber);
        } else {
            // Failed to create cache texture
            pendingFrames.remove(frameNumber);
            Log.w(TAG, "Failed to create cache texture for frame: " + frameNumber);
        }
        
        performanceMonitor.endOperation("priority_frame_cache");
        
        // Periodic cleanup
        checkAndPerformCleanup();
    }
    
    /**
     * Evict least recently used frame to make room for high priority frames
     */
    private void evictLeastRecentlyUsedFrame() {
        CachedFrame oldestFrame = null;
        long oldestTime = Long.MAX_VALUE;
        Long oldestKey = null;
        
        // Find the least recently used frame that's not recently accessed
        for (Map.Entry<Long, CachedFrame> entry : frameCache.snapshot().entrySet()) {
            CachedFrame frame = entry.getValue();
            long timeSinceAccess = System.currentTimeMillis() - frame.lastAccessTime;
            
            // Don't evict recently accessed frames during scrubbing
            if (isTimelineScrubbing && timeSinceAccess < FRAME_ACCESS_PRIORITY_MS) {
                continue;
            }
            
            if (frame.lastAccessTime < oldestTime) {
                oldestTime = frame.lastAccessTime;
                oldestFrame = frame;
                oldestKey = entry.getKey();
            }
        }
        
        if (oldestFrame != null && oldestKey != null) {
            frameCache.remove(oldestKey);
            evictFrame(oldestFrame);
            Log.d(TAG, "Evicted LRU frame " + oldestKey + " to make room for high priority frame");
        }
    }
    
    /**
     * Prefetch frames around current position for smooth scrubbing
     */
    public void prefetchFrames(long currentFrame, int radius) {
        performanceMonitor.startOperation("frame_prefetch");
        
        // Use adaptive radius based on scrubbing state
        int actualRadius = isTimelineScrubbing ? PREFETCH_RADIUS_SCRUBBING : 
                          (radius > 0 ? radius : PREFETCH_RADIUS_NORMAL);
        
        // Prefetch frames in both directions with priority for closer frames
        for (int i = -actualRadius; i <= actualRadius; i++) {
            long frameNumber = currentFrame + i;
            if (frameNumber >= 0 && !isFrameCached(frameNumber)) {
                // Request frame from decoder (this would be called by VideoDecoder)
                // Closer frames get higher priority
                boolean highPriority = Math.abs(i) <= 3; // Within 3 frames gets high priority
                Log.v(TAG, "Frame " + frameNumber + " needs prefetch (priority: " + 
                      (highPriority ? "HIGH" : "normal") + ")");
            }
        }
        
        performanceMonitor.endOperation("frame_prefetch");
    }
    
    /**
     * Intelligent prefetch for timeline scrubbing based on scrub direction and speed
     */
    public void intelligentPrefetch(long currentFrame, long previousFrame) {
        if (!isTimelineScrubbing) {
            return;
        }
        
        performanceMonitor.startOperation("intelligent_prefetch");
        
        // Determine scrub direction and speed
        long frameDelta = currentFrame - previousFrame;
        int direction = frameDelta > 0 ? 1 : -1;
        int speed = Math.min(Math.abs((int) frameDelta), 10); // Cap at 10 frames
        
        // Prefetch more frames in the direction of scrubbing
        int forwardRadius = direction > 0 ? PREFETCH_RADIUS_SCRUBBING + speed : PREFETCH_RADIUS_SCRUBBING / 2;
        int backwardRadius = direction < 0 ? PREFETCH_RADIUS_SCRUBBING + speed : PREFETCH_RADIUS_SCRUBBING / 2;
        
        // Prefetch frames with directional bias
        for (int i = -backwardRadius; i <= forwardRadius; i++) {
            long frameNumber = currentFrame + i;
            if (frameNumber >= 0 && !isFrameCached(frameNumber)) {
                // Frames in scrub direction get higher priority
                boolean highPriority = (direction > 0 && i > 0) || (direction < 0 && i < 0);
                Log.v(TAG, "Intelligent prefetch: frame " + frameNumber + 
                      " (direction: " + direction + ", priority: " + (highPriority ? "HIGH" : "normal") + ")");
            }
        }
        
        performanceMonitor.endOperation("intelligent_prefetch");
    }
    
    /**
     * Clear cache for new video
     */
    public void clearCache() {
        Log.d(TAG, "Clearing frame cache");
        
        // Release all cached textures
        for (CachedFrame frame : frameCache.snapshot().values()) {
            if (frame.textureId != 0) {
                textureManager.releaseTexture(frame.textureId);
            }
        }
        
        // Clear pending frames
        for (CachedFrame frame : pendingFrames.values()) {
            if (frame.textureId != 0) {
                textureManager.releaseTexture(frame.textureId);
            }
        }
        
        frameCache.evictAll();
        pendingFrames.clear();
        totalCacheMemory.set(0);
        total4KCacheMemory.set(0);
        
        // Reset statistics
        cacheHits = 0;
        cacheMisses = 0;
        framesEvicted = 0;
        
        Log.d(TAG, "Frame cache cleared");
    }
    
    /**
     * Get cache statistics
     */
    public CacheStats getCacheStats() {
        return new CacheStats(
            frameCache.size(),
            pendingFrames.size(),
            totalCacheMemory.get() / (1024 * 1024), // MB
            cacheHits,
            cacheMisses,
            framesEvicted,
            calculateHitRate()
        );
    }
    
    /**
     * Cache statistics data class
     */
    public static class CacheStats {
        public final int cachedFrames;
        public final int pendingFrames;
        public final long memoryUsageMB;
        public final long cacheHits;
        public final long cacheMisses;
        public final long framesEvicted;
        public final double hitRate;
        
        CacheStats(int cachedFrames, int pendingFrames, long memoryUsageMB, 
                  long cacheHits, long cacheMisses, long framesEvicted, double hitRate) {
            this.cachedFrames = cachedFrames;
            this.pendingFrames = pendingFrames;
            this.memoryUsageMB = memoryUsageMB;
            this.cacheHits = cacheHits;
            this.cacheMisses = cacheMisses;
            this.framesEvicted = framesEvicted;
            this.hitRate = hitRate;
        }
    }
    
    // Private helper methods
    
    private boolean canCacheFrame() {
        long currentMemoryMB = totalCacheMemory.get() / (1024 * 1024);
        
        // Check overall memory limit
        if (currentMemoryMB >= MAX_CACHE_MEMORY_MB) {
            return false;
        }
        
        // Check 4K specific limits
        if (is4KVideo) {
            long current4KMemoryMB = total4KCacheMemory.get() / (1024 * 1024);
            return current4KMemoryMB < (MAX_CACHE_MEMORY_MB / 2); // 50% for 4K
        }
        
        return true;
    }
    
    private void copyFrameToCache(int sourceTextureId, int cacheTextureId) {
        // This would copy the frame data from source texture to cache texture
        // Implementation depends on OpenGL context and would typically involve
        // rendering source texture to framebuffer attached to cache texture
        
        // For now, just bind the cache texture
        textureManager.bindTexture(cacheTextureId);
        
        // TODO: Implement actual frame copying using framebuffer operations
        Log.v(TAG, "Frame copied to cache (placeholder implementation)");
    }
    
    private void evictFrame(CachedFrame frame) {
        if (frame.textureId != 0) {
            textureManager.releaseTexture(frame.textureId);
        }
        
        totalCacheMemory.addAndGet(-frame.memorySize);
        if (frame.is4K()) {
            total4KCacheMemory.addAndGet(-frame.memorySize);
        }
        
        framesEvicted++;
        Log.v(TAG, "Frame evicted from cache: " + frame.frameNumber);
    }
    
    private void checkAndPerformCleanup() {
        long currentTime = System.currentTimeMillis();
        if (currentTime - lastCleanupTime > CACHE_CLEANUP_INTERVAL_MS) {
            performCleanup();
            lastCleanupTime = currentTime;
        }
    }
    
    private void performCleanup() {
        // Remove old pending frames
        long maxAge = 30000; // 30 seconds
        long currentTime = System.currentTimeMillis();
        
        pendingFrames.entrySet().removeIf(entry -> {
            CachedFrame frame = entry.getValue();
            if (currentTime - frame.cacheTime > maxAge) {
                if (frame.textureId != 0) {
                    textureManager.releaseTexture(frame.textureId);
                }
                Log.v(TAG, "Removed stale pending frame: " + frame.frameNumber);
                return true;
            }
            return false;
        });
    }
    
    private double calculateHitRate() {
        long totalRequests = cacheHits + cacheMisses;
        return totalRequests > 0 ? (double) cacheHits / totalRequests : 0.0;
    }
    
    /**
     * Release all resources
     */
    public void release() {
        Log.d(TAG, "Releasing FrameCache resources");
        clearCache();
    }
}