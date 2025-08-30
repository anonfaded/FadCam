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
    
    // Texture pool configuration
    private static final int MAX_POOLED_TEXTURES = 10;
    private static final int MAX_TEXTURE_MEMORY_MB = 200; // 200MB texture memory limit
    
    // Texture information
    private static class TextureInfo {
        final int textureId;
        final int width;
        final int height;
        final int format;
        final long memorySize;
        boolean inUse;
        long lastUsedTime;
        
        TextureInfo(int textureId, int width, int height, int format) {
            this.textureId = textureId;
            this.width = width;
            this.height = height;
            this.format = format;
            this.memorySize = calculateMemorySize(width, height, format);
            this.inUse = false;
            this.lastUsedTime = System.currentTimeMillis();
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
    
    // Active textures and texture pool
    private final SparseArray<TextureInfo> activeTextures;
    private final List<TextureInfo> texturePool;
    private final PerformanceMonitor performanceMonitor;
    
    // Memory tracking
    private long totalTextureMemory = 0;
    private int nextTextureId = 1;
    
    public TextureManager() {
        this.activeTextures = new SparseArray<>();
        this.texturePool = new ArrayList<>();
        this.performanceMonitor = PerformanceMonitor.getInstance();
        
        Log.d(TAG, "TextureManager initialized");
    }
    
    /**
     * Create a new video texture for external OES rendering
     */
    public int createVideoTexture() {
        return createVideoTexture(1920, 1080); // Default HD size
    }
    
    /**
     * Create a video texture with specific dimensions
     */
    public int createVideoTexture(int width, int height) {
        performanceMonitor.startOperation("texture_creation");
        
        // Try to reuse from pool first
        TextureInfo pooledTexture = findPooledTexture(width, height, GLES20.GL_RGBA);
        if (pooledTexture != null) {
            texturePool.remove(pooledTexture);
            pooledTexture.inUse = true;
            pooledTexture.lastUsedTime = System.currentTimeMillis();
            activeTextures.put(pooledTexture.textureId, pooledTexture);
            
            performanceMonitor.endOperation("texture_creation");
            Log.d(TAG, "Reused pooled texture: " + pooledTexture.textureId);
            return pooledTexture.textureId;
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
        
        // Track texture
        TextureInfo textureInfo = new TextureInfo(textureId, width, height, GLES20.GL_RGBA);
        textureInfo.inUse = true;
        activeTextures.put(textureId, textureInfo);
        totalTextureMemory += textureInfo.memorySize;
        
        // Monitor memory usage
        performanceMonitor.trackTextureCreation(textureId, width, height, GLES20.GL_RGBA);
        checkMemoryUsage();
        
        performanceMonitor.endOperation("texture_creation");
        Log.d(TAG, "Created new video texture: " + textureId + " (" + width + "x" + height + ")");
        return textureId;
    }
    
    /**
     * Create a regular 2D texture
     */
    public int createTexture(int width, int height, int format) {
        performanceMonitor.startOperation("texture_creation");
        
        // Try to reuse from pool
        TextureInfo pooledTexture = findPooledTexture(width, height, format);
        if (pooledTexture != null) {
            texturePool.remove(pooledTexture);
            pooledTexture.inUse = true;
            pooledTexture.lastUsedTime = System.currentTimeMillis();
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
        TextureInfo textureInfo = new TextureInfo(textureId, width, height, format);
        textureInfo.inUse = true;
        activeTextures.put(textureId, textureInfo);
        totalTextureMemory += textureInfo.memorySize;
        
        // Monitor memory usage
        performanceMonitor.trackTextureCreation(textureId, width, height, format);
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
        int target = (textureInfo.format == GLES20.GL_RGBA && textureInfo.width == 1920) ? 
                     GLES11Ext.GL_TEXTURE_EXTERNAL_OES : GLES20.GL_TEXTURE_2D;
        
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
     * Release texture back to pool or delete it
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
        
        // Add to pool if there's space, otherwise delete
        if (texturePool.size() < MAX_POOLED_TEXTURES) {
            texturePool.add(textureInfo);
            Log.d(TAG, "Texture " + textureId + " returned to pool");
        } else {
            deleteTexture(textureInfo);
        }
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
        
        // Delete all pooled textures
        for (TextureInfo textureInfo : texturePool) {
            deleteTexture(textureInfo);
        }
        texturePool.clear();
        
        totalTextureMemory = 0;
        Log.d(TAG, "All texture resources released");
    }
    
    // Private helper methods
    
    private TextureInfo findPooledTexture(int width, int height, int format) {
        for (TextureInfo textureInfo : texturePool) {
            if (textureInfo.width == width && textureInfo.height == height && textureInfo.format == format) {
                return textureInfo;
            }
        }
        return null;
    }
    
    private void deleteTexture(TextureInfo textureInfo) {
        int[] textures = new int[] { textureInfo.textureId };
        GLES20.glDeleteTextures(1, textures, 0);
        totalTextureMemory -= textureInfo.memorySize;
        
        performanceMonitor.trackTextureDeletion(textureInfo.textureId);
        Log.d(TAG, "Deleted texture: " + textureInfo.textureId);
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