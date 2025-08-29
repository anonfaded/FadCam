package com.fadcam.ui.faditor.utils;

import android.content.Context;
import android.opengl.GLES20;
import android.os.Debug;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import com.fadcam.opengl.grafika.GlUtil;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Performance monitoring system for tracking GPU operations, memory usage, and optimization metrics.
 * Implements requirements 7.3, 7.5, 7.6, 12.6 for smooth operation and performance optimization.
 */
public class PerformanceMonitor {
    
    private static final String TAG = "PerformanceMonitor";
    
    // Performance thresholds
    private static final long FRAME_TIME_WARNING_NS = 33_333_333L; // 30fps = 33.33ms
    private static final long SEEK_TIME_WARNING_MS = 100L; // Requirement 7.6: <100ms seeks
    private static final long MEMORY_WARNING_THRESHOLD_MB = 100L; // 100MB texture memory warning
    private static final int MAX_PERFORMANCE_SAMPLES = 1000; // Keep last 1000 samples
    
    // Singleton instance
    private static volatile PerformanceMonitor instance;
    
    // Performance tracking
    private final Map<String, PerformanceMetric> metrics;
    private final Map<String, Long> operationStartTimes;
    private final List<FramePerformanceData> frameHistory;
    private final AtomicLong totalTextureMemory;
    private final AtomicLong totalFrameBufferMemory;
    
    // GPU memory tracking
    private final Map<Integer, TextureInfo> activeTextures;
    private final Map<Integer, FrameBufferInfo> activeFrameBuffers;
    
    // Performance listeners
    private final List<PerformanceListener> listeners;
    
    // Monitoring state
    private boolean isMonitoringEnabled;
    private long lastFrameTime;
    private Handler mainHandler;
    
    public interface PerformanceListener {
        void onFrameDropDetected(long frameTimeMs);
        void onMemoryWarning(long memoryUsageMB);
        void onSlowSeekDetected(long seekTimeMs);
        void onPerformanceMetricUpdated(String metricName, PerformanceMetric metric);
    }
    
    public static class PerformanceMetric {
        private final String name;
        private long totalTime;
        private long minTime;
        private long maxTime;
        private long sampleCount;
        private double averageTime;
        
        public PerformanceMetric(String name) {
            this.name = name;
            this.minTime = Long.MAX_VALUE;
            this.maxTime = 0;
        }
        
        public synchronized void addSample(long timeNs) {
            totalTime += timeNs;
            sampleCount++;
            averageTime = (double) totalTime / sampleCount;
            
            if (timeNs < minTime) minTime = timeNs;
            if (timeNs > maxTime) maxTime = timeNs;
        }
        
        // Getters
        public String getName() { return name; }
        public long getTotalTime() { return totalTime; }
        public long getMinTime() { return minTime == Long.MAX_VALUE ? 0 : minTime; }
        public long getMaxTime() { return maxTime; }
        public long getSampleCount() { return sampleCount; }
        public double getAverageTime() { return averageTime; }
        public double getAverageTimeMs() { return averageTime / 1_000_000.0; }
    }
    
    private static class FramePerformanceData {
        final long timestamp;
        final long frameTimeNs;
        final long memoryUsageMB;
        final int activeTextureCount;
        
        FramePerformanceData(long timestamp, long frameTimeNs, long memoryUsageMB, int activeTextureCount) {
            this.timestamp = timestamp;
            this.frameTimeNs = frameTimeNs;
            this.memoryUsageMB = memoryUsageMB;
            this.activeTextureCount = activeTextureCount;
        }
    }
    
    private static class TextureInfo {
        final int textureId;
        final int width;
        final int height;
        final int format;
        final long memorySize;
        final long creationTime;
        
        TextureInfo(int textureId, int width, int height, int format, long memorySize) {
            this.textureId = textureId;
            this.width = width;
            this.height = height;
            this.format = format;
            this.memorySize = memorySize;
            this.creationTime = System.currentTimeMillis();
        }
    }
    
    private static class FrameBufferInfo {
        final int frameBufferId;
        final int width;
        final int height;
        final long memorySize;
        final long creationTime;
        
        FrameBufferInfo(int frameBufferId, int width, int height, long memorySize) {
            this.frameBufferId = frameBufferId;
            this.width = width;
            this.height = height;
            this.memorySize = memorySize;
            this.creationTime = System.currentTimeMillis();
        }
    }
    
    private PerformanceMonitor() {
        this.metrics = new ConcurrentHashMap<>();
        this.operationStartTimes = new ConcurrentHashMap<>();
        this.frameHistory = new ArrayList<>();
        this.totalTextureMemory = new AtomicLong(0);
        this.totalFrameBufferMemory = new AtomicLong(0);
        this.activeTextures = new ConcurrentHashMap<>();
        this.activeFrameBuffers = new ConcurrentHashMap<>();
        this.listeners = new ArrayList<>();
        this.isMonitoringEnabled = false;
        this.mainHandler = new Handler(Looper.getMainLooper());
    }
    
    public static PerformanceMonitor getInstance() {
        if (instance == null) {
            synchronized (PerformanceMonitor.class) {
                if (instance == null) {
                    instance = new PerformanceMonitor();
                }
            }
        }
        return instance;
    }
    
    /**
     * Enable performance monitoring
     * Requirement 7.3: Performance metrics logging for optimization
     */
    public void enableMonitoring() {
        isMonitoringEnabled = true;
        lastFrameTime = SystemClock.elapsedRealtimeNanos();
        Log.d(TAG, "Performance monitoring enabled");
    }
    
    /**
     * Disable performance monitoring
     */
    public void disableMonitoring() {
        isMonitoringEnabled = false;
        Log.d(TAG, "Performance monitoring disabled");
    }
    
    /**
     * Add a performance listener
     */
    public void addPerformanceListener(PerformanceListener listener) {
        synchronized (listeners) {
            listeners.add(listener);
        }
    }
    
    /**
     * Remove a performance listener
     */
    public void removePerformanceListener(PerformanceListener listener) {
        synchronized (listeners) {
            listeners.remove(listener);
        }
    }
    
    /**
     * Start timing an operation
     */
    public void startOperation(String operationName) {
        if (!isMonitoringEnabled) return;
        
        operationStartTimes.put(operationName, SystemClock.elapsedRealtimeNanos());
    }
    
    /**
     * End timing an operation and record the metric
     */
    public void endOperation(String operationName) {
        if (!isMonitoringEnabled) return;
        
        Long startTime = operationStartTimes.remove(operationName);
        if (startTime != null) {
            long duration = SystemClock.elapsedRealtimeNanos() - startTime;
            recordMetric(operationName, duration);
        }
    }
    
    /**
     * Record a performance metric
     */
    public void recordMetric(String metricName, long timeNs) {
        if (!isMonitoringEnabled) return;
        
        PerformanceMetric metric = metrics.computeIfAbsent(metricName, PerformanceMetric::new);
        metric.addSample(timeNs);
        
        // Notify listeners
        notifyMetricUpdated(metricName, metric);
        
        // Check for performance warnings
        checkPerformanceWarnings(metricName, timeNs);
    }
    
    /**
     * Record frame rendering performance
     * Requirement 7.5: Maintain smooth 30fps playback
     */
    public void recordFrameTime() {
        if (!isMonitoringEnabled) return;
        
        long currentTime = SystemClock.elapsedRealtimeNanos();
        if (lastFrameTime > 0) {
            long frameTime = currentTime - lastFrameTime;
            recordMetric("frame_render", frameTime);
            
            // Record frame performance data
            recordFramePerformanceData(frameTime);
            
            // Check for frame drops
            if (frameTime > FRAME_TIME_WARNING_NS) {
                notifyFrameDropDetected(frameTime / 1_000_000L);
            }
        }
        lastFrameTime = currentTime;
    }
    
    /**
     * Record seek operation performance
     * Requirement 7.6: Seek to positions within 100ms
     */
    public void recordSeekTime(long seekTimeMs) {
        if (!isMonitoringEnabled) return;
        
        recordMetric("video_seek", seekTimeMs * 1_000_000L); // Convert to nanoseconds
        
        if (seekTimeMs > SEEK_TIME_WARNING_MS) {
            notifySlowSeekDetected(seekTimeMs);
        }
    }
    
    /**
     * Track texture creation for memory management
     */
    public void trackTextureCreation(int textureId, int width, int height, int format) {
        if (!isMonitoringEnabled) return;
        
        long memorySize = calculateTextureMemorySize(width, height, format);
        TextureInfo textureInfo = new TextureInfo(textureId, width, height, format, memorySize);
        
        activeTextures.put(textureId, textureInfo);
        totalTextureMemory.addAndGet(memorySize);
        
        Log.d(TAG, String.format("Texture created: ID=%d, Size=%dx%d, Memory=%d bytes", 
                textureId, width, height, memorySize));
        
        checkMemoryUsage();
    }
    
    /**
     * Track texture deletion for memory management
     */
    public void trackTextureDeletion(int textureId) {
        if (!isMonitoringEnabled) return;
        
        TextureInfo textureInfo = activeTextures.remove(textureId);
        if (textureInfo != null) {
            totalTextureMemory.addAndGet(-textureInfo.memorySize);
            Log.d(TAG, String.format("Texture deleted: ID=%d, Memory freed=%d bytes", 
                    textureId, textureInfo.memorySize));
        }
    }
    
    /**
     * Track frame buffer creation
     */
    public void trackFrameBufferCreation(int frameBufferId, int width, int height) {
        if (!isMonitoringEnabled) return;
        
        long memorySize = calculateFrameBufferMemorySize(width, height);
        FrameBufferInfo frameBufferInfo = new FrameBufferInfo(frameBufferId, width, height, memorySize);
        
        activeFrameBuffers.put(frameBufferId, frameBufferInfo);
        totalFrameBufferMemory.addAndGet(memorySize);
        
        Log.d(TAG, String.format("FrameBuffer created: ID=%d, Size=%dx%d, Memory=%d bytes", 
                frameBufferId, width, height, memorySize));
        
        checkMemoryUsage();
    }
    
    /**
     * Track frame buffer deletion
     */
    public void trackFrameBufferDeletion(int frameBufferId) {
        if (!isMonitoringEnabled) return;
        
        FrameBufferInfo frameBufferInfo = activeFrameBuffers.remove(frameBufferId);
        if (frameBufferInfo != null) {
            totalFrameBufferMemory.addAndGet(-frameBufferInfo.memorySize);
            Log.d(TAG, String.format("FrameBuffer deleted: ID=%d, Memory freed=%d bytes", 
                    frameBufferId, frameBufferInfo.memorySize));
        }
    }
    
    /**
     * Get current GPU memory usage in MB
     */
    public long getGpuMemoryUsageMB() {
        return (totalTextureMemory.get() + totalFrameBufferMemory.get()) / (1024 * 1024);
    }
    
    /**
     * Get current texture memory usage in bytes
     */
    public long getTextureMemoryUsage() {
        return totalTextureMemory.get();
    }
    
    /**
     * Get current frame buffer memory usage in bytes
     */
    public long getFrameBufferMemoryUsage() {
        return totalFrameBufferMemory.get();
    }
    
    /**
     * Get performance metric by name
     */
    public PerformanceMetric getMetric(String metricName) {
        return metrics.get(metricName);
    }
    
    /**
     * Get all performance metrics
     */
    public Map<String, PerformanceMetric> getAllMetrics() {
        return new HashMap<>(metrics);
    }
    
    /**
     * Get frame performance history
     */
    public List<FramePerformanceData> getFrameHistory() {
        synchronized (frameHistory) {
            return new ArrayList<>(frameHistory);
        }
    }
    
    /**
     * Clear all performance data
     */
    public void clearPerformanceData() {
        metrics.clear();
        operationStartTimes.clear();
        synchronized (frameHistory) {
            frameHistory.clear();
        }
        Log.d(TAG, "Performance data cleared");
    }
    
    /**
     * Log performance summary
     */
    public void logPerformanceSummary() {
        if (!isMonitoringEnabled) return;
        
        Log.i(TAG, "=== Performance Summary ===");
        Log.i(TAG, String.format("GPU Memory Usage: %d MB", getGpuMemoryUsageMB()));
        Log.i(TAG, String.format("Active Textures: %d", activeTextures.size()));
        Log.i(TAG, String.format("Active FrameBuffers: %d", activeFrameBuffers.size()));
        
        for (PerformanceMetric metric : metrics.values()) {
            Log.i(TAG, String.format("%s: avg=%.2fms, min=%.2fms, max=%.2fms, samples=%d",
                    metric.getName(),
                    metric.getAverageTimeMs(),
                    metric.getMinTime() / 1_000_000.0,
                    metric.getMaxTime() / 1_000_000.0,
                    metric.getSampleCount()));
        }
        Log.i(TAG, "========================");
    }
    
    /**
     * Check if performance is within acceptable thresholds
     */
    public boolean isPerformanceAcceptable() {
        PerformanceMetric frameMetric = metrics.get("frame_render");
        if (frameMetric != null && frameMetric.getAverageTime() > FRAME_TIME_WARNING_NS) {
            return false;
        }
        
        PerformanceMetric seekMetric = metrics.get("video_seek");
        if (seekMetric != null && seekMetric.getAverageTimeMs() > SEEK_TIME_WARNING_MS) {
            return false;
        }
        
        return getGpuMemoryUsageMB() < MEMORY_WARNING_THRESHOLD_MB;
    }
    
    // Private helper methods
    
    private void recordFramePerformanceData(long frameTimeNs) {
        synchronized (frameHistory) {
            FramePerformanceData data = new FramePerformanceData(
                    System.currentTimeMillis(),
                    frameTimeNs,
                    getGpuMemoryUsageMB(),
                    activeTextures.size()
            );
            
            frameHistory.add(data);
            
            // Keep only the last MAX_PERFORMANCE_SAMPLES
            if (frameHistory.size() > MAX_PERFORMANCE_SAMPLES) {
                frameHistory.remove(0);
            }
        }
    }
    
    private long calculateTextureMemorySize(int width, int height, int format) {
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
                bytesPerPixel = 4; // Assume RGBA for unknown formats
        }
        return (long) width * height * bytesPerPixel;
    }
    
    private long calculateFrameBufferMemorySize(int width, int height) {
        // Assume RGBA format for frame buffers
        return (long) width * height * 4;
    }
    
    private void checkMemoryUsage() {
        long memoryUsageMB = getGpuMemoryUsageMB();
        if (memoryUsageMB > MEMORY_WARNING_THRESHOLD_MB) {
            notifyMemoryWarning(memoryUsageMB);
        }
    }
    
    private void checkPerformanceWarnings(String metricName, long timeNs) {
        if ("frame_render".equals(metricName) && timeNs > FRAME_TIME_WARNING_NS) {
            notifyFrameDropDetected(timeNs / 1_000_000L);
        } else if ("video_seek".equals(metricName) && timeNs > SEEK_TIME_WARNING_MS * 1_000_000L) {
            notifySlowSeekDetected(timeNs / 1_000_000L);
        }
    }
    
    private void notifyFrameDropDetected(long frameTimeMs) {
        mainHandler.post(() -> {
            synchronized (listeners) {
                for (PerformanceListener listener : listeners) {
                    try {
                        listener.onFrameDropDetected(frameTimeMs);
                    } catch (Exception e) {
                        Log.e(TAG, "Error notifying frame drop listener", e);
                    }
                }
            }
        });
    }
    
    private void notifyMemoryWarning(long memoryUsageMB) {
        mainHandler.post(() -> {
            synchronized (listeners) {
                for (PerformanceListener listener : listeners) {
                    try {
                        listener.onMemoryWarning(memoryUsageMB);
                    } catch (Exception e) {
                        Log.e(TAG, "Error notifying memory warning listener", e);
                    }
                }
            }
        });
    }
    
    private void notifySlowSeekDetected(long seekTimeMs) {
        mainHandler.post(() -> {
            synchronized (listeners) {
                for (PerformanceListener listener : listeners) {
                    try {
                        listener.onSlowSeekDetected(seekTimeMs);
                    } catch (Exception e) {
                        Log.e(TAG, "Error notifying slow seek listener", e);
                    }
                }
            }
        });
    }
    
    private void notifyMetricUpdated(String metricName, PerformanceMetric metric) {
        mainHandler.post(() -> {
            synchronized (listeners) {
                for (PerformanceListener listener : listeners) {
                    try {
                        listener.onPerformanceMetricUpdated(metricName, metric);
                    } catch (Exception e) {
                        Log.e(TAG, "Error notifying metric update listener", e);
                    }
                }
            }
        });
    }
}