package com.fadcam.ui.faditor.utils;

import android.app.ActivityManager;
import android.content.Context;
import android.opengl.GLES20;
import android.util.Log;

import com.fadcam.opengl.grafika.GlUtil;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Memory optimization utility for managing GPU resources and large video file processing.
 * Implements requirements 7.3, 7.5 for smooth operation with large video files.
 */
public class MemoryOptimizer {
    
    private static final String TAG = "MemoryOptimizer";
    
    // Memory thresholds
    private static final long LOW_MEMORY_THRESHOLD_MB = 50L; // 50MB available memory warning
    private static final long CRITICAL_MEMORY_THRESHOLD_MB = 20L; // 20MB critical memory warning
    private static final int MAX_TEXTURE_CACHE_SIZE = 10; // Maximum cached textures
    private static final int MAX_FRAMEBUFFER_CACHE_SIZE = 5; // Maximum cached frame buffers
    
    // Singleton instance
    private static volatile MemoryOptimizer instance;
    
    private final Context context;
    private final ActivityManager activityManager;
    private final PerformanceMonitor performanceMonitor;
    
    // Resource caches for reuse
    private final ConcurrentHashMap<String, List<Integer>> textureCache;
    private final ConcurrentHashMap<String, List<Integer>> frameBufferCache;
    private final List<WeakReference<MemoryOptimizationListener>> listeners;
    
    // Memory monitoring
    private long lastMemoryCheck;
    private static final long MEMORY_CHECK_INTERVAL_MS = 5000; // Check every 5 seconds
    
    public interface MemoryOptimizationListener {
        void onLowMemoryWarning(long availableMemoryMB);
        void onCriticalMemoryWarning(long availableMemoryMB);
        void onMemoryOptimizationPerformed(String operation, long memoryFreedMB);
    }
    
    private MemoryOptimizer(Context context) {
        this.context = context.getApplicationContext();
        this.activityManager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        this.performanceMonitor = PerformanceMonitor.getInstance();
        this.textureCache = new ConcurrentHashMap<>();
        this.frameBufferCache = new ConcurrentHashMap<>();
        this.listeners = new ArrayList<>();
        this.lastMemoryCheck = 0;
    }
    
    public static MemoryOptimizer getInstance(Context context) {
        if (instance == null) {
            synchronized (MemoryOptimizer.class) {
                if (instance == null) {
                    instance = new MemoryOptimizer(context);
                }
            }
        }
        return instance;
    }
    
    /**
     * Add a memory optimization listener
     */
    public void addListener(MemoryOptimizationListener listener) {
        synchronized (listeners) {
            listeners.add(new WeakReference<>(listener));
        }
    }
    
    /**
     * Remove a memory optimization listener
     */
    public void removeListener(MemoryOptimizationListener listener) {
        synchronized (listeners) {
            Iterator<WeakReference<MemoryOptimizationListener>> iterator = listeners.iterator();
            while (iterator.hasNext()) {
                WeakReference<MemoryOptimizationListener> ref = iterator.next();
                MemoryOptimizationListener l = ref.get();
                if (l == null || l == listener) {
                    iterator.remove();
                }
            }
        }
    }
    
    /**
     * Check current memory status and perform optimization if needed
     */
    public void checkMemoryStatus() {
        long currentTime = System.currentTimeMillis();
        if (currentTime - lastMemoryCheck < MEMORY_CHECK_INTERVAL_MS) {
            return; // Don't check too frequently
        }
        lastMemoryCheck = currentTime;
        
        long availableMemoryMB = getAvailableMemoryMB();
        
        if (availableMemoryMB < CRITICAL_MEMORY_THRESHOLD_MB) {
            Log.w(TAG, "Critical memory warning: " + availableMemoryMB + "MB available");
            performCriticalMemoryOptimization();
            notifyCriticalMemoryWarning(availableMemoryMB);
        } else if (availableMemoryMB < LOW_MEMORY_THRESHOLD_MB) {
            Log.w(TAG, "Low memory warning: " + availableMemoryMB + "MB available");
            performLowMemoryOptimization();
            notifyLowMemoryWarning(availableMemoryMB);
        }
    }
    
    /**
     * Get available memory in MB
     */
    public long getAvailableMemoryMB() {
        ActivityManager.MemoryInfo memoryInfo = new ActivityManager.MemoryInfo();
        activityManager.getMemoryInfo(memoryInfo);
        return memoryInfo.availMem / (1024 * 1024);
    }
    
    /**
     * Get total memory in MB
     */
    public long getTotalMemoryMB() {
        ActivityManager.MemoryInfo memoryInfo = new ActivityManager.MemoryInfo();
        activityManager.getMemoryInfo(memoryInfo);
        return memoryInfo.totalMem / (1024 * 1024);
    }
    
    /**
     * Check if device is in low memory state
     */
    public boolean isLowMemory() {
        ActivityManager.MemoryInfo memoryInfo = new ActivityManager.MemoryInfo();
        activityManager.getMemoryInfo(memoryInfo);
        return memoryInfo.lowMemory;
    }
    
    /**
     * Optimize texture usage for large video files
     */
    public int optimizeTextureForVideo(int width, int height, int format) {
        String cacheKey = width + "x" + height + "_" + format;
        
        // Try to reuse existing texture from cache
        List<Integer> cachedTextures = textureCache.get(cacheKey);
        if (cachedTextures != null && !cachedTextures.isEmpty()) {
            Integer textureId = cachedTextures.remove(cachedTextures.size() - 1);
            Log.d(TAG, "Reusing cached texture: " + textureId + " for " + cacheKey);
            return textureId;
        }
        
        // Create new texture
        int[] textures = new int[1];
        GLES20.glGenTextures(1, textures, 0);
        int textureId = textures[0];
        
        // Configure texture for optimal memory usage
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        
        // Allocate texture memory
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, format, width, height, 0, format, GLES20.GL_UNSIGNED_BYTE, null);
        GlUtil.checkGlError("texture allocation");
        
        // Track texture creation
        performanceMonitor.trackTextureCreation(textureId, width, height, format);
        
        Log.d(TAG, "Created optimized texture: " + textureId + " for " + cacheKey);
        return textureId;
    }
    
    /**
     * Return texture to cache for reuse
     */
    public void recycleTexture(int textureId, int width, int height, int format) {
        String cacheKey = width + "x" + height + "_" + format;
        
        List<Integer> cachedTextures = textureCache.computeIfAbsent(cacheKey, k -> new ArrayList<>());
        
        // Limit cache size to prevent memory bloat
        if (cachedTextures.size() < MAX_TEXTURE_CACHE_SIZE) {
            cachedTextures.add(textureId);
            Log.d(TAG, "Recycled texture: " + textureId + " to cache " + cacheKey);
        } else {
            // Cache is full, delete the texture
            deleteTexture(textureId);
        }
    }
    
    /**
     * Create optimized frame buffer for large video processing
     */
    public int createOptimizedFrameBuffer(int width, int height) {
        String cacheKey = width + "x" + height;
        
        // Try to reuse existing frame buffer from cache
        List<Integer> cachedFrameBuffers = frameBufferCache.get(cacheKey);
        if (cachedFrameBuffers != null && !cachedFrameBuffers.isEmpty()) {
            Integer frameBufferId = cachedFrameBuffers.remove(cachedFrameBuffers.size() - 1);
            Log.d(TAG, "Reusing cached frame buffer: " + frameBufferId + " for " + cacheKey);
            return frameBufferId;
        }
        
        // Create new frame buffer
        int[] frameBuffers = new int[1];
        GLES20.glGenFramebuffers(1, frameBuffers, 0);
        int frameBufferId = frameBuffers[0];
        
        // Track frame buffer creation
        performanceMonitor.trackFrameBufferCreation(frameBufferId, width, height);
        
        Log.d(TAG, "Created optimized frame buffer: " + frameBufferId + " for " + cacheKey);
        return frameBufferId;
    }
    
    /**
     * Return frame buffer to cache for reuse
     */
    public void recycleFrameBuffer(int frameBufferId, int width, int height) {
        String cacheKey = width + "x" + height;
        
        List<Integer> cachedFrameBuffers = frameBufferCache.computeIfAbsent(cacheKey, k -> new ArrayList<>());
        
        // Limit cache size to prevent memory bloat
        if (cachedFrameBuffers.size() < MAX_FRAMEBUFFER_CACHE_SIZE) {
            cachedFrameBuffers.add(frameBufferId);
            Log.d(TAG, "Recycled frame buffer: " + frameBufferId + " to cache " + cacheKey);
        } else {
            // Cache is full, delete the frame buffer
            deleteFrameBuffer(frameBufferId);
        }
    }
    
    /**
     * Perform low memory optimization
     */
    private void performLowMemoryOptimization() {
        long memoryFreed = 0;
        
        // Clear half of the texture cache
        memoryFreed += clearTextureCache(0.5f);
        
        // Clear half of the frame buffer cache
        memoryFreed += clearFrameBufferCache(0.5f);
        
        // Force garbage collection
        System.gc();
        
        Log.i(TAG, "Low memory optimization completed, freed approximately " + memoryFreed + "MB");
        notifyMemoryOptimizationPerformed("low_memory_optimization", memoryFreed);
    }
    
    /**
     * Perform critical memory optimization
     */
    private void performCriticalMemoryOptimization() {
        long memoryFreed = 0;
        
        // Clear all texture cache
        memoryFreed += clearTextureCache(1.0f);
        
        // Clear all frame buffer cache
        memoryFreed += clearFrameBufferCache(1.0f);
        
        // Force garbage collection multiple times
        System.gc();
        System.runFinalization();
        System.gc();
        
        Log.w(TAG, "Critical memory optimization completed, freed approximately " + memoryFreed + "MB");
        notifyMemoryOptimizationPerformed("critical_memory_optimization", memoryFreed);
    }
    
    /**
     * Clear texture cache
     * @param percentage Percentage of cache to clear (0.0 to 1.0)
     * @return Estimated memory freed in MB
     */
    private long clearTextureCache(float percentage) {
        long memoryFreed = 0;
        
        for (List<Integer> textures : textureCache.values()) {
            int texturesToRemove = Math.max(1, (int) (textures.size() * percentage));
            
            for (int i = 0; i < texturesToRemove && !textures.isEmpty(); i++) {
                Integer textureId = textures.remove(textures.size() - 1);
                deleteTexture(textureId);
                memoryFreed += 4; // Estimate 4MB per texture (rough estimate)
            }
        }
        
        return memoryFreed;
    }
    
    /**
     * Clear frame buffer cache
     * @param percentage Percentage of cache to clear (0.0 to 1.0)
     * @return Estimated memory freed in MB
     */
    private long clearFrameBufferCache(float percentage) {
        long memoryFreed = 0;
        
        for (List<Integer> frameBuffers : frameBufferCache.values()) {
            int frameBuffersToRemove = Math.max(1, (int) (frameBuffers.size() * percentage));
            
            for (int i = 0; i < frameBuffersToRemove && !frameBuffers.isEmpty(); i++) {
                Integer frameBufferId = frameBuffers.remove(frameBuffers.size() - 1);
                deleteFrameBuffer(frameBufferId);
                memoryFreed += 8; // Estimate 8MB per frame buffer (rough estimate)
            }
        }
        
        return memoryFreed;
    }
    
    /**
     * Delete a texture and track the deletion
     */
    private void deleteTexture(int textureId) {
        performanceMonitor.trackTextureDeletion(textureId);
        int[] textures = new int[] { textureId };
        GLES20.glDeleteTextures(1, textures, 0);
    }
    
    /**
     * Delete a frame buffer and track the deletion
     */
    private void deleteFrameBuffer(int frameBufferId) {
        performanceMonitor.trackFrameBufferDeletion(frameBufferId);
        int[] frameBuffers = new int[] { frameBufferId };
        GLES20.glDeleteFramebuffers(1, frameBuffers, 0);
    }
    
    /**
     * Clean up all cached resources
     */
    public void cleanup() {
        Log.d(TAG, "Cleaning up MemoryOptimizer resources");
        
        // Clear all caches
        clearTextureCache(1.0f);
        clearFrameBufferCache(1.0f);
        
        textureCache.clear();
        frameBufferCache.clear();
        
        synchronized (listeners) {
            listeners.clear();
        }
    }
    
    /**
     * Get memory optimization statistics
     */
    public String getMemoryStats() {
        int totalCachedTextures = textureCache.values().stream().mapToInt(List::size).sum();
        int totalCachedFrameBuffers = frameBufferCache.values().stream().mapToInt(List::size).sum();
        
        return String.format("Memory Stats - Available: %dMB, GPU: %dMB, Cached Textures: %d, Cached FrameBuffers: %d",
                getAvailableMemoryMB(),
                performanceMonitor.getGpuMemoryUsageMB(),
                totalCachedTextures,
                totalCachedFrameBuffers);
    }
    
    // Notification methods
    
    private void notifyLowMemoryWarning(long availableMemoryMB) {
        synchronized (listeners) {
            Iterator<WeakReference<MemoryOptimizationListener>> iterator = listeners.iterator();
            while (iterator.hasNext()) {
                WeakReference<MemoryOptimizationListener> ref = iterator.next();
                MemoryOptimizationListener listener = ref.get();
                if (listener == null) {
                    iterator.remove();
                } else {
                    try {
                        listener.onLowMemoryWarning(availableMemoryMB);
                    } catch (Exception e) {
                        Log.e(TAG, "Error notifying low memory warning", e);
                    }
                }
            }
        }
    }
    
    private void notifyCriticalMemoryWarning(long availableMemoryMB) {
        synchronized (listeners) {
            Iterator<WeakReference<MemoryOptimizationListener>> iterator = listeners.iterator();
            while (iterator.hasNext()) {
                WeakReference<MemoryOptimizationListener> ref = iterator.next();
                MemoryOptimizationListener listener = ref.get();
                if (listener == null) {
                    iterator.remove();
                } else {
                    try {
                        listener.onCriticalMemoryWarning(availableMemoryMB);
                    } catch (Exception e) {
                        Log.e(TAG, "Error notifying critical memory warning", e);
                    }
                }
            }
        }
    }
    
    private void notifyMemoryOptimizationPerformed(String operation, long memoryFreedMB) {
        synchronized (listeners) {
            Iterator<WeakReference<MemoryOptimizationListener>> iterator = listeners.iterator();
            while (iterator.hasNext()) {
                WeakReference<MemoryOptimizationListener> ref = iterator.next();
                MemoryOptimizationListener listener = ref.get();
                if (listener == null) {
                    iterator.remove();
                } else {
                    try {
                        listener.onMemoryOptimizationPerformed(operation, memoryFreedMB);
                    } catch (Exception e) {
                        Log.e(TAG, "Error notifying memory optimization", e);
                    }
                }
            }
        }
    }
}