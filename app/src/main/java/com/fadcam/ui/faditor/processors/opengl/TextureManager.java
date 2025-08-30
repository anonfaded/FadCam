package com.fadcam.ui.faditor.processors.opengl;

import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.util.Log;
import android.util.SparseArray;

import com.fadcam.opengl.grafika.GlUtil;
import com.fadcam.ui.faditor.utils.PerformanceMonitor;

import java.util.ArrayList;
import java.util.List;

/**
 * Efficient GPU texture memory management and pooling for video rendering.
 * Handles texture creation, binding, pooling, and memory optimization.
 * Requirements: 13.5, 14.1, 14.4
 */
public class TextureManager {

    private static final String TAG = "TextureManager";

    // Enhanced texture pool configuration - optimized for professional 4K video
    // editing
    private static final int MAX_POOLED_TEXTURES = 30; // Increased for professional editing
    private static final int MAX_TEXTURE_MEMORY_MB = 600; // 600MB texture memory limit for 4K
    private static final int MAX_4K_TEXTURES = 12; // Increased 4K texture pool for timeline scrubbing
    private static final int TEXTURE_CLEANUP_INTERVAL_MS = 10000; // 10 seconds for faster cleanup
    private static final long TEXTURE_MAX_AGE_MS = 45000; // 45 seconds max age for better turnover

    // Professional editing optimizations
    private static final int TIMELINE_TEXTURE_POOL_SIZE = 15; // Dedicated pool for timeline scrubbing
    private static final int PREFETCH_TEXTURE_RESERVE = 5; // Reserve textures for prefetching

    // Enhanced texture information for 4K support and advanced pooling
    private static class TextureInfo {
        final int textureId;
        final int width;
        final int height;
        final int format;
        final long memorySize;
        final TextureType type;
        boolean inUse;
        long lastUsedTime;
        long creationTime;
        int usageCount;

        TextureInfo(int textureId, int width, int height, int format, TextureType type) {
            this.textureId = textureId;
            this.width = width;
            this.height = height;
            this.format = format;
            this.type = type;
            this.memorySize = calculateMemorySize(width, height, format);
            this.inUse = false;
            this.lastUsedTime = System.currentTimeMillis();
            this.creationTime = System.currentTimeMillis();
            this.usageCount = 0;
        }

        boolean is4K() {
            return width >= 3840 || height >= 2160;
        }

        boolean isHD() {
            return width >= 1920 && height >= 1080 && !is4K();
        }

        private long calculateMemorySize(int width, int height, int format) {
            int bytesPerPixel;
            switch (format) {
                case GLES20.GL_RGBA:
                    bytesPerPixel = 4;
                    break;
                case GLES20.GL_RGB:
                    bytesPerPixel = 3;
                    break;
                case GLES20.GL_LUMINANCE_ALPHA:
                    bytesPerPixel = 2;
                    break;
                case GLES20.GL_LUMINANCE:
                case GLES20.GL_ALPHA:
                    bytesPerPixel = 1;
                    break;
                default:
                    bytesPerPixel = 4; // Default to RGBA
            }
            return (long) width * height * bytesPerPixel;
        }
    }

    // Texture types for optimized pooling
    public enum TextureType {
        VIDEO_EXTERNAL, // External OES texture for video
        FRAME_BUFFER, // Regular 2D texture for frame buffers
        CACHE_TEXTURE, // Texture for frame caching
        TIMELINE_TEXTURE // Dedicated texture for timeline scrubbing optimization
    }

    // Active textures and enhanced texture pools
    private final SparseArray<TextureInfo> activeTextures;
    private final List<TextureInfo> texturePool;
    private final List<TextureInfo> frameBufferPool;
    private final List<TextureInfo> cacheTexturePool;
    private final List<TextureInfo> timelineTexturePool; // Dedicated pool for timeline scrubbing
    private final PerformanceMonitor performanceMonitor;

    // Memory optimization integration
    private GpuMemoryOptimizer memoryOptimizer;

    // Memory tracking and optimization
    private long totalTextureMemory = 0;
    private long total4KTextureMemory = 0;
    private int nextTextureId = 1;
    private long lastCleanupTime = 0;

    // Performance optimization flags
    private boolean aggressiveCleanupEnabled = false;
    private boolean memoryPressureDetected = false;

    public TextureManager() {
        this.activeTextures = new SparseArray<>();
        this.texturePool = new ArrayList<>();
        this.frameBufferPool = new ArrayList<>();
        this.cacheTexturePool = new ArrayList<>();
        this.timelineTexturePool = new ArrayList<>(); // Initialize timeline pool
        this.performanceMonitor = PerformanceMonitor.getInstance();
        this.lastCleanupTime = System.currentTimeMillis();

        Log.d(TAG, "Enhanced TextureManager initialized with professional 4K editing support");
    }

    /**
     * Set GPU memory optimizer for advanced memory management
     */
    public void setMemoryOptimizer(GpuMemoryOptimizer memoryOptimizer) {
        this.memoryOptimizer = memoryOptimizer;
        Log.d(TAG, "GPU memory optimizer integrated");
    }

    /**
     * Create a new video texture for external OES rendering
     */
    public int createVideoTexture() {
        return createVideoTexture(1920, 1080); // Default HD size
    }

    /**
     * Create a video texture with specific dimensions - optimized for 4K support
     */
    public int createVideoTexture(int width, int height) {
        performanceMonitor.startOperation("texture_creation");

        // Check for memory pressure and cleanup if needed
        checkAndPerformCleanup();

        // Try to reuse from pool first
        TextureInfo pooledTexture = findPooledTexture(width, height, GLES20.GL_RGBA, TextureType.VIDEO_EXTERNAL);
        if (pooledTexture != null) {
            texturePool.remove(pooledTexture);
            pooledTexture.inUse = true;
            pooledTexture.lastUsedTime = System.currentTimeMillis();
            pooledTexture.usageCount++;
            activeTextures.put(pooledTexture.textureId, pooledTexture);

            performanceMonitor.endOperation("texture_creation");
            Log.d(TAG, "Reused pooled video texture: " + pooledTexture.textureId + " (" + width + "x" + height + ")");
            return pooledTexture.textureId;
        }

        // Check 4K memory limits before creating new texture
        if (width >= 3840 || height >= 2160) {
            if (!canCreate4KTexture()) {
                Log.w(TAG, "Cannot create 4K texture - memory limit reached");
                performanceMonitor.endOperation("texture_creation");
                return 0;
            }
        }

        // Create new external texture
        int[] textures = new int[1];
        GLES20.glGenTextures(1, textures, 0);
        int textureId = textures[0];

        if (textureId == 0) {
            Log.e(TAG, "Failed to generate texture");
            performanceMonitor.endOperation("texture_creation");
            return 0;
        }

        // Configure external texture
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId);
        GlUtil.checkGlError("glBindTexture");

        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        GlUtil.checkGlError("texture parameter setup");

        // Track texture with enhanced information
        TextureInfo textureInfo = new TextureInfo(textureId, width, height, GLES20.GL_RGBA, TextureType.VIDEO_EXTERNAL);
        textureInfo.inUse = true;
        textureInfo.usageCount = 1;
        activeTextures.put(textureId, textureInfo);
        totalTextureMemory += textureInfo.memorySize;

        // Track 4K memory separately
        if (textureInfo.is4K()) {
            total4KTextureMemory += textureInfo.memorySize;
        }

        // Monitor memory usage
        performanceMonitor.trackTextureCreation(textureId, width, height, GLES20.GL_RGBA);

        // Track with memory optimizer
        if (memoryOptimizer != null) {
            memoryOptimizer.trackAllocation("texture_" + textureId, "video_texture", textureInfo.memorySize);
        }

        checkMemoryUsage();

        performanceMonitor.endOperation("texture_creation");
        Log.d(TAG, "Created new video texture: " + textureId + " (" + width + "x" + height + ") - " +
                (textureInfo.is4K() ? "4K" : textureInfo.isHD() ? "HD" : "SD"));
        return textureId;
    }

    /**
     * Create a regular 2D texture
     */
    public int createTexture(int width, int height, int format) {
        performanceMonitor.startOperation("texture_creation");

        // Try to reuse from pool
        TextureInfo pooledTexture = findPooledTexture(width, height, format, TextureType.FRAME_BUFFER);
        if (pooledTexture != null) {
            List<TextureInfo> pool = getPoolForType(pooledTexture.type);
            pool.remove(pooledTexture);
            pooledTexture.inUse = true;
            pooledTexture.lastUsedTime = System.currentTimeMillis();
            pooledTexture.usageCount++;
            activeTextures.put(pooledTexture.textureId, pooledTexture);

            performanceMonitor.endOperation("texture_creation");
            Log.d(TAG, "Reused pooled 2D texture: " + pooledTexture.textureId);
            return pooledTexture.textureId;
        }

        // Create new 2D texture
        int[] textures = new int[1];
        GLES20.glGenTextures(1, textures, 0);
        int textureId = textures[0];

        if (textureId == 0) {
            Log.e(TAG, "Failed to generate 2D texture");
            performanceMonitor.endOperation("texture_creation");
            return 0;
        }

        // Configure 2D texture
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId);
        GlUtil.checkGlError("glBindTexture");

        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);

        // Allocate texture memory
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, format, width, height, 0, format, GLES20.GL_UNSIGNED_BYTE, null);
        GlUtil.checkGlError("glTexImage2D");

        // Track texture
        TextureInfo textureInfo = new TextureInfo(textureId, width, height, format, TextureType.FRAME_BUFFER);
        textureInfo.inUse = true;
        textureInfo.usageCount = 1;
        activeTextures.put(textureId, textureInfo);
        totalTextureMemory += textureInfo.memorySize;

        // Track 4K memory separately
        if (textureInfo.is4K()) {
            total4KTextureMemory += textureInfo.memorySize;
        }

        // Monitor memory usage
        performanceMonitor.trackTextureCreation(textureId, width, height, format);

        // Track with memory optimizer
        if (memoryOptimizer != null) {
            memoryOptimizer.trackAllocation("texture_" + textureId, "frame_buffer", textureInfo.memorySize);
        }

        checkMemoryUsage();

        performanceMonitor.endOperation("texture_creation");
        Log.d(TAG, "Created new 2D texture: " + textureId + " (" + width + "x" + height + ")");
        return textureId;
    }

    /**
     * Bind texture for rendering
     */
    public void bindTexture(int textureId) {
        TextureInfo textureInfo = activeTextures.get(textureId);
        if (textureInfo == null) {
            Log.w(TAG, "Attempting to bind unknown texture: " + textureId);
            return;
        }

        // Determine texture target based on format
        int target = (textureInfo.format == GLES20.GL_RGBA && textureInfo.width == 1920)
                ? GLES11Ext.GL_TEXTURE_EXTERNAL_OES
                : GLES20.GL_TEXTURE_2D;

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(target, textureId);
        GlUtil.checkGlError("bindTexture");

        textureInfo.lastUsedTime = System.currentTimeMillis();
    }

    /**
     * Update texture data
     */
    public void updateTexture(int textureId, byte[] data, int width, int height, int format) {
        TextureInfo textureInfo = activeTextures.get(textureId);
        if (textureInfo == null) {
            Log.w(TAG, "Attempting to update unknown texture: " + textureId);
            return;
        }

        performanceMonitor.startOperation("texture_update");

        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId);
        GLES20.glTexSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, width, height, format, GLES20.GL_UNSIGNED_BYTE,
                java.nio.ByteBuffer.wrap(data));
        GlUtil.checkGlError("glTexSubImage2D");

        textureInfo.lastUsedTime = System.currentTimeMillis();
        performanceMonitor.endOperation("texture_update");
    }

    /**
     * Release texture back to appropriate pool or delete it
     */
    public void releaseTexture(int textureId) {
        TextureInfo textureInfo = activeTextures.get(textureId);
        if (textureInfo == null) {
            Log.w(TAG, "Attempting to release unknown texture: " + textureId);
            return;
        }

        activeTextures.remove(textureId);
        textureInfo.inUse = false;
        textureInfo.lastUsedTime = System.currentTimeMillis();

        // Get appropriate pool for texture type
        List<TextureInfo> pool = getPoolForType(textureInfo.type);
        int maxPoolSize = getMaxPoolSizeForType(textureInfo.type);

        // Add to pool if there's space and texture is worth keeping
        if (pool.size() < maxPoolSize && isTextureWorthKeeping(textureInfo)) {
            pool.add(textureInfo);
            Log.d(TAG, "Texture " + textureId + " returned to " + textureInfo.type + " pool");
        } else {
            deleteTexture(textureInfo);
        }
    }

    private int getMaxPoolSizeForType(TextureType type) {
        switch (type) {
            case TIMELINE_TEXTURE:
                return TIMELINE_TEXTURE_POOL_SIZE; // 15 timeline textures for 60fps scrubbing
            case FRAME_BUFFER:
                return MAX_POOLED_TEXTURES / 3; // 10 frame buffer textures
            case CACHE_TEXTURE:
                return MAX_POOLED_TEXTURES / 3; // 10 cache textures
            case VIDEO_EXTERNAL:
            default:
                return MAX_POOLED_TEXTURES / 2; // 15 video textures
        }
    }

    private boolean isTextureWorthKeeping(TextureInfo textureInfo) {
        // Keep frequently used textures
        if (textureInfo.usageCount > 3) {
            return true;
        }

        // Keep recent textures
        long age = System.currentTimeMillis() - textureInfo.creationTime;
        if (age < 30000) { // 30 seconds
            return true;
        }

        // Don't keep 4K textures if memory pressure is high
        if (textureInfo.is4K() && memoryPressureDetected) {
            return false;
        }

        return true;
    }

    /**
     * Create a timeline texture optimized for 60fps scrubbing
     */
    public int createTimelineTexture(int width, int height) {
        performanceMonitor.startOperation("timeline_texture_creation");

        // Check for memory pressure and cleanup if needed
        checkAndPerformCleanup();

        // Try to reuse from timeline pool first (highest priority for performance)
        TextureInfo pooledTexture = findPooledTexture(width, height, GLES20.GL_RGBA, TextureType.TIMELINE_TEXTURE);
        if (pooledTexture != null) {
            timelineTexturePool.remove(pooledTexture);
            pooledTexture.inUse = true;
            pooledTexture.lastUsedTime = System.currentTimeMillis();
            pooledTexture.usageCount++;
            activeTextures.put(pooledTexture.textureId, pooledTexture);

            performanceMonitor.endOperation("timeline_texture_creation");
            Log.d(TAG, "Reused pooled timeline texture: " + pooledTexture.textureId + " (optimized for 60fps)");
            return pooledTexture.textureId;
        }

        // Check 4K memory limits for timeline textures
        if (width >= 3840 || height >= 2160) {
            if (!canCreate4KTexture()) {
                Log.w(TAG, "Cannot create 4K timeline texture - memory limit reached");
                performanceMonitor.endOperation("timeline_texture_creation");
                return 0;
            }
        }

        // Create new timeline texture with optimized parameters
        int[] textures = new int[1];
        GLES20.glGenTextures(1, textures, 0);
        int textureId = textures[0];

        if (textureId == 0) {
            Log.e(TAG, "Failed to generate timeline texture");
            performanceMonitor.endOperation("timeline_texture_creation");
            return 0;
        }

        // Configure timeline texture with performance optimizations
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId);
        GlUtil.checkGlError("glBindTexture");

        // Use optimized filtering for timeline scrubbing
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);

        // Allocate texture memory with optimized format
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, width, height, 0,
                GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null);
        GlUtil.checkGlError("glTexImage2D");

        // Track timeline texture
        TextureInfo textureInfo = new TextureInfo(textureId, width, height, GLES20.GL_RGBA,
                TextureType.TIMELINE_TEXTURE);
        textureInfo.inUse = true;
        textureInfo.usageCount = 1;
        activeTextures.put(textureId, textureInfo);
        totalTextureMemory += textureInfo.memorySize;

        // Track 4K memory separately
        if (textureInfo.is4K()) {
            total4KTextureMemory += textureInfo.memorySize;
        }

        // Monitor memory usage
        performanceMonitor.trackTextureCreation(textureId, width, height, GLES20.GL_RGBA);

        // Track with memory optimizer
        if (memoryOptimizer != null) {
            memoryOptimizer.trackAllocation("texture_" + textureId, "timeline_texture", textureInfo.memorySize);
        }

        checkMemoryUsage();

        performanceMonitor.endOperation("timeline_texture_creation");
        Log.d(TAG, "Created new timeline texture: " + textureId + " (" + width + "x" + height
                + ") - optimized for 60fps scrubbing");
        return textureId;
    }

    /**
     * Create a cache texture for frame caching
     */
    public int createCacheTexture(int width, int height) {
        performanceMonitor.startOperation("cache_texture_creation");

        // Check for memory pressure and cleanup if needed
        checkAndPerformCleanup();

        // Try to reuse from cache pool first
        TextureInfo pooledTexture = findPooledTexture(width, height, GLES20.GL_RGBA, TextureType.CACHE_TEXTURE);
        if (pooledTexture != null) {
            cacheTexturePool.remove(pooledTexture);
            pooledTexture.inUse = true;
            pooledTexture.lastUsedTime = System.currentTimeMillis();
            pooledTexture.usageCount++;
            activeTextures.put(pooledTexture.textureId, pooledTexture);

            performanceMonitor.endOperation("cache_texture_creation");
            Log.d(TAG, "Reused pooled cache texture: " + pooledTexture.textureId);
            return pooledTexture.textureId;
        }

        // Check 4K memory limits for cache textures
        if (width >= 3840 || height >= 2160) {
            if (!canCreate4KTexture()) {
                Log.w(TAG, "Cannot create 4K cache texture - memory limit reached");
                performanceMonitor.endOperation("cache_texture_creation");
                return 0;
            }
        }

        // Create new cache texture
        int[] textures = new int[1];
        GLES20.glGenTextures(1, textures, 0);
        int textureId = textures[0];

        if (textureId == 0) {
            Log.e(TAG, "Failed to generate cache texture");
            performanceMonitor.endOperation("cache_texture_creation");
            return 0;
        }

        // Configure cache texture
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId);
        GlUtil.checkGlError("glBindTexture");

        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);

        // Allocate texture memory
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, width, height, 0,
                GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null);
        GlUtil.checkGlError("glTexImage2D");

        // Track cache texture
        TextureInfo textureInfo = new TextureInfo(textureId, width, height, GLES20.GL_RGBA, TextureType.CACHE_TEXTURE);
        textureInfo.inUse = true;
        textureInfo.usageCount = 1;
        activeTextures.put(textureId, textureInfo);
        totalTextureMemory += textureInfo.memorySize;

        // Track 4K memory separately
        if (textureInfo.is4K()) {
            total4KTextureMemory += textureInfo.memorySize;
        }

        // Monitor memory usage
        performanceMonitor.trackTextureCreation(textureId, width, height, GLES20.GL_RGBA);

        // Track with memory optimizer
        if (memoryOptimizer != null) {
            memoryOptimizer.trackAllocation("texture_" + textureId, "cache_texture", textureInfo.memorySize);
        }

        checkMemoryUsage();

        performanceMonitor.endOperation("cache_texture_creation");
        Log.d(TAG, "Created new cache texture: " + textureId + " (" + width + "x" + height + ")");
        return textureId;
    }

    /**
     * Get available texture memory in bytes
     */
    public long getAvailableTextureMemory() {
        long maxMemory = MAX_TEXTURE_MEMORY_MB * 1024 * 1024;
        return Math.max(0, maxMemory - totalTextureMemory);
    }

    /**
     * Get total texture memory usage in bytes
     */
    public long getTotalTextureMemory() {
        return totalTextureMemory;
    }

    /**
     * Get number of active textures
     */
    public int getActiveTextureCount() {
        return activeTextures.size();
    }

    /**
     * Get number of pooled textures
     */
    public int getPooledTextureCount() {
        return texturePool.size();
    }

    /**
     * Get total number of pooled textures across all pools
     */
    public int getTotalPooledTextureCount() {
        return texturePool.size() + frameBufferPool.size() + cacheTexturePool.size() + timelineTexturePool.size();
    }

    /**
     * Get 4K texture memory usage in bytes
     */
    public long get4KTextureMemory() {
        return total4KTextureMemory;
    }

    /**
     * Get number of active 4K textures
     */
    public int getActive4KTextureCount() {
        return count4KTextures();
    }

    /**
     * Check if memory pressure is detected
     */
    public boolean isMemoryPressureDetected() {
        return memoryPressureDetected;
    }

    /**
     * Get texture manager statistics
     */
    public TextureManagerStats getStats() {
        return new TextureManagerStats(
                getActiveTextureCount(),
                getTotalPooledTextureCount(),
                getTotalTextureMemory() / (1024 * 1024), // MB
                get4KTextureMemory() / (1024 * 1024), // MB
                getActive4KTextureCount(),
                memoryPressureDetected,
                aggressiveCleanupEnabled);
    }

    /**
     * Texture manager statistics data class
     */
    public static class TextureManagerStats {
        public final int activeTextures;
        public final int pooledTextures;
        public final long totalMemoryMB;
        public final long texture4KMemoryMB;
        public final int active4KTextures;
        public final boolean memoryPressure;
        public final boolean aggressiveCleanup;

        TextureManagerStats(int activeTextures, int pooledTextures, long totalMemoryMB,
                long texture4KMemoryMB, int active4KTextures,
                boolean memoryPressure, boolean aggressiveCleanup) {
            this.activeTextures = activeTextures;
            this.pooledTextures = pooledTextures;
            this.totalMemoryMB = totalMemoryMB;
            this.texture4KMemoryMB = texture4KMemoryMB;
            this.active4KTextures = active4KTextures;
            this.memoryPressure = memoryPressure;
            this.aggressiveCleanup = aggressiveCleanup;
        }
    }

    /**
     * Clean up unused textures from pool
     */
    public void cleanupPool() {
        long currentTime = System.currentTimeMillis();
        long maxAge = 30000; // 30 seconds

        List<TextureInfo> toRemove = new ArrayList<>();
        for (TextureInfo textureInfo : texturePool) {
            if (currentTime - textureInfo.lastUsedTime > maxAge) {
                toRemove.add(textureInfo);
            }
        }

        for (TextureInfo textureInfo : toRemove) {
            texturePool.remove(textureInfo);
            deleteTexture(textureInfo);
        }

        if (!toRemove.isEmpty()) {
            Log.d(TAG, "Cleaned up " + toRemove.size() + " old textures from pool");
        }
    }

    /**
     * Release all resources
     */
    public void release() {
        Log.d(TAG, "Releasing all texture resources");

        // Delete all active textures
        for (int i = 0; i < activeTextures.size(); i++) {
            TextureInfo textureInfo = activeTextures.valueAt(i);
            deleteTexture(textureInfo);
        }
        activeTextures.clear();

        // Delete all pooled textures from all pools
        for (TextureInfo textureInfo : texturePool) {
            deleteTexture(textureInfo);
        }
        texturePool.clear();

        for (TextureInfo textureInfo : frameBufferPool) {
            deleteTexture(textureInfo);
        }
        frameBufferPool.clear();

        for (TextureInfo textureInfo : cacheTexturePool) {
            deleteTexture(textureInfo);
        }
        cacheTexturePool.clear();

        for (TextureInfo textureInfo : timelineTexturePool) {
            deleteTexture(textureInfo);
        }
        timelineTexturePool.clear();

        totalTextureMemory = 0;
        total4KTextureMemory = 0;
        memoryPressureDetected = false;
        aggressiveCleanupEnabled = false;

        Log.d(TAG, "All texture resources released");
    }

    // Private helper methods

    private TextureInfo findPooledTexture(int width, int height, int format, TextureType type) {
        List<TextureInfo> pool = getPoolForType(type);
        for (TextureInfo textureInfo : pool) {
            if (textureInfo.width == width && textureInfo.height == height &&
                    textureInfo.format == format && textureInfo.type == type) {
                return textureInfo;
            }
        }
        return null;
    }

    private List<TextureInfo> getPoolForType(TextureType type) {
        switch (type) {
            case FRAME_BUFFER:
                return frameBufferPool;
            case CACHE_TEXTURE:
                return cacheTexturePool;
            case TIMELINE_TEXTURE:
                return timelineTexturePool;
            case VIDEO_EXTERNAL:
            default:
                return texturePool;
        }
    }

    private boolean canCreate4KTexture() {
        // Check if we can create another 4K texture
        long current4KMemoryMB = total4KTextureMemory / (1024 * 1024);
        int current4KCount = count4KTextures();

        return current4KCount < MAX_4K_TEXTURES &&
                current4KMemoryMB < (MAX_TEXTURE_MEMORY_MB / 2); // 50% for 4K
    }

    private int count4KTextures() {
        int count = 0;
        for (int i = 0; i < activeTextures.size(); i++) {
            if (activeTextures.valueAt(i).is4K()) {
                count++;
            }
        }
        return count;
    }

    private void checkAndPerformCleanup() {
        long currentTime = System.currentTimeMillis();
        if (currentTime - lastCleanupTime > TEXTURE_CLEANUP_INTERVAL_MS) {
            performAdvancedCleanup();
            lastCleanupTime = currentTime;
        }

        // Check for memory pressure
        long memoryUsageMB = getTotalTextureMemory() / (1024 * 1024);
        if (memoryUsageMB > MAX_TEXTURE_MEMORY_MB * 0.8) { // 80% threshold
            memoryPressureDetected = true;
            aggressiveCleanupEnabled = true;
            performAggressiveCleanup();
        } else if (memoryUsageMB < MAX_TEXTURE_MEMORY_MB * 0.6) { // 60% threshold
            memoryPressureDetected = false;
            aggressiveCleanupEnabled = false;
        }
    }

    private void performAdvancedCleanup() {
        long currentTime = System.currentTimeMillis();

        // Clean up all pools (timeline pool has priority to maintain 60fps performance)
        cleanupPool(texturePool, "video");
        cleanupPool(frameBufferPool, "framebuffer");
        cleanupPool(cacheTexturePool, "cache");

        // Clean timeline pool less aggressively to maintain scrubbing performance
        if (!aggressiveCleanupEnabled) {
            cleanupPool(timelineTexturePool, "timeline");
        }

        Log.d(TAG, "Advanced cleanup completed - Memory: " + (getTotalTextureMemory() / 1024 / 1024) + "MB");
    }

    private void cleanupPool(List<TextureInfo> pool, String poolName) {
        long currentTime = System.currentTimeMillis();
        long maxAge = aggressiveCleanupEnabled ? TEXTURE_MAX_AGE_MS / 2 : TEXTURE_MAX_AGE_MS;

        pool.removeIf(textureInfo -> {
            if (currentTime - textureInfo.lastUsedTime > maxAge) {
                deleteTexture(textureInfo);
                Log.v(TAG, "Cleaned up old " + poolName + " texture: " + textureInfo.textureId);
                return true;
            }
            return false;
        });
    }

    private void performAggressiveCleanup() {
        Log.w(TAG, "Performing aggressive cleanup due to memory pressure");

        // Remove least recently used textures from pools
        texturePool.sort((a, b) -> Long.compare(a.lastUsedTime, b.lastUsedTime));
        frameBufferPool.sort((a, b) -> Long.compare(a.lastUsedTime, b.lastUsedTime));
        cacheTexturePool.sort((a, b) -> Long.compare(a.lastUsedTime, b.lastUsedTime));
        timelineTexturePool.sort((a, b) -> Long.compare(a.lastUsedTime, b.lastUsedTime));

        // Remove oldest textures from each pool (timeline pool gets less aggressive
        // cleanup)
        removeOldestTextures(texturePool, 0.6f); // 60% removal
        removeOldestTextures(frameBufferPool, 0.5f); // 50% removal
        removeOldestTextures(cacheTexturePool, 0.5f); // 50% removal
        removeOldestTextures(timelineTexturePool, 0.3f); // Only 30% removal to maintain scrubbing performance
    }

    private void removeOldestTextures(List<TextureInfo> pool, float percentage) {
        int toRemove = (int) (pool.size() * percentage);
        for (int i = 0; i < toRemove && !pool.isEmpty(); i++) {
            TextureInfo textureInfo = pool.remove(0);
            deleteTexture(textureInfo);
        }
    }

    private void deleteTexture(TextureInfo textureInfo) {
        int[] textures = new int[] { textureInfo.textureId };
        GLES20.glDeleteTextures(1, textures, 0);
        totalTextureMemory -= textureInfo.memorySize;

        // Update 4K memory tracking
        if (textureInfo.is4K()) {
            total4KTextureMemory -= textureInfo.memorySize;
        }

        performanceMonitor.trackTextureDeletion(textureInfo.textureId);

        // Track deallocation with memory optimizer
        if (memoryOptimizer != null) {
            memoryOptimizer.trackDeallocation("texture_" + textureInfo.textureId);
        }

        Log.d(TAG, "Deleted " + textureInfo.type + " texture: " + textureInfo.textureId +
                " (" + textureInfo.width + "x" + textureInfo.height + ")");
    }

    private void checkMemoryUsage() {
        long maxMemory = MAX_TEXTURE_MEMORY_MB * 1024 * 1024;
        if (totalTextureMemory > maxMemory * 0.8) { // 80% threshold
            Log.w(TAG, "High texture memory usage: " + (totalTextureMemory / 1024 / 1024) + "MB");

            // Try to free some memory by cleaning up pool
            cleanupPool();

            // Log memory warning
            Log.w(TAG, "Texture memory usage exceeds 80% threshold");
        }
    }
}