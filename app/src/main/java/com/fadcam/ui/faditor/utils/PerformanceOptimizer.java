package com.fadcam.ui.faditor.utils;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.fadcam.ui.faditor.persistence.AutoSaveManager;

/**
 * Central performance optimization coordinator that integrates all performance monitoring
 * and optimization components for smooth video editing experience.
 * Implements requirements 7.3, 7.5, 7.6, 12.6 for comprehensive performance management.
 */
public class PerformanceOptimizer implements 
        PerformanceMonitor.PerformanceListener,
        MemoryOptimizer.MemoryOptimizationListener {
    
    private static final String TAG = "PerformanceOptimizer";
    
    // Performance thresholds for optimization triggers
    private static final long FRAME_DROP_THRESHOLD_COUNT = 5; // Trigger optimization after 5 frame drops
    private static final long SLOW_SEEK_THRESHOLD_COUNT = 3; // Trigger optimization after 3 slow seeks
    private static final long AUTO_SAVE_SLOW_THRESHOLD_MS = 2000; // 2 seconds for auto-save warning
    
    // Singleton instance
    private static volatile PerformanceOptimizer instance;
    
    private final Context context;
    private final PerformanceMonitor performanceMonitor;
    private final MemoryOptimizer memoryOptimizer;
    private final Handler mainHandler;
    
    // Performance tracking
    private long frameDropCount = 0;
    private long slowSeekCount = 0;
    private boolean isOptimizationInProgress = false;
    private long lastOptimizationTime = 0;
    private static final long OPTIMIZATION_COOLDOWN_MS = 30000; // 30 seconds between optimizations
    
    // Listeners
    private PerformanceOptimizationListener listener;
    
    public interface PerformanceOptimizationListener {
        void onPerformanceOptimizationStarted(String reason);
        void onPerformanceOptimizationCompleted(String summary);
        void onPerformanceWarning(String warning, String recommendation);
        void onAutoSavePerformanceIssue(long autoSaveTimeMs);
    }
    
    private PerformanceOptimizer(Context context) {
        this.context = context.getApplicationContext();
        this.performanceMonitor = PerformanceMonitor.getInstance();
        this.memoryOptimizer = MemoryOptimizer.getInstance(context);
        this.mainHandler = new Handler(Looper.getMainLooper());
        
        // Register as listeners
        performanceMonitor.addPerformanceListener(this);
        memoryOptimizer.addListener(this);
        
        Log.d(TAG, "PerformanceOptimizer initialized");
    }
    
    public static PerformanceOptimizer getInstance(Context context) {
        if (instance == null) {
            synchronized (PerformanceOptimizer.class) {
                if (instance == null) {
                    instance = new PerformanceOptimizer(context);
                }
            }
        }
        return instance;
    }
    
    /**
     * Start performance optimization monitoring
     */
    public void startOptimization() {
        performanceMonitor.enableMonitoring();
        Log.i(TAG, "Performance optimization monitoring started");
    }
    
    /**
     * Stop performance optimization monitoring
     */
    public void stopOptimization() {
        performanceMonitor.disableMonitoring();
        Log.i(TAG, "Performance optimization monitoring stopped");
    }
    
    /**
     * Set performance optimization listener
     */
    public void setPerformanceOptimizationListener(PerformanceOptimizationListener listener) {
        this.listener = listener;
    }
    
    /**
     * Perform comprehensive performance check and optimization
     */
    public void performComprehensiveOptimization(String reason) {
        if (isOptimizationInProgress) {
            Log.d(TAG, "Optimization already in progress, skipping");
            return;
        }
        
        long currentTime = System.currentTimeMillis();
        if (currentTime - lastOptimizationTime < OPTIMIZATION_COOLDOWN_MS) {
            Log.d(TAG, "Optimization cooldown active, skipping");
            return;
        }
        
        isOptimizationInProgress = true;
        lastOptimizationTime = currentTime;
        
        Log.i(TAG, "Starting comprehensive performance optimization: " + reason);
        
        if (listener != null) {
            listener.onPerformanceOptimizationStarted(reason);
        }
        
        // Perform optimization steps
        StringBuilder summary = new StringBuilder();
        
        // 1. Check and optimize memory
        memoryOptimizer.checkMemoryStatus();
        summary.append("Memory check completed. ");
        
        // 2. Check GPU memory usage
        long gpuMemoryMB = performanceMonitor.getGpuMemoryUsageMB();
        if (gpuMemoryMB > 100) { // More than 100MB GPU memory
            summary.append("High GPU memory usage detected (").append(gpuMemoryMB).append("MB). ");
            
            if (listener != null) {
                listener.onPerformanceWarning(
                    "High GPU memory usage: " + gpuMemoryMB + "MB",
                    "Consider reducing video resolution or closing other GPU-intensive apps"
                );
            }
        }
        
        // 3. Check frame performance
        PerformanceMonitor.PerformanceMetric frameMetric = performanceMonitor.getMetric("frame_render");
        if (frameMetric != null && frameMetric.getAverageTimeMs() > 33.33) { // Slower than 30fps
            summary.append("Frame rendering performance below 30fps (")
                   .append(String.format("%.1f", frameMetric.getAverageTimeMs()))
                   .append("ms avg). ");
            
            if (listener != null) {
                listener.onPerformanceWarning(
                    "Frame rendering slower than 30fps",
                    "Consider reducing video quality or enabling hardware acceleration"
                );
            }
        }
        
        // 4. Check seek performance
        PerformanceMonitor.PerformanceMetric seekMetric = performanceMonitor.getMetric("video_seek");
        if (seekMetric != null && seekMetric.getAverageTimeMs() > 100) { // Slower than 100ms
            summary.append("Video seeking slower than 100ms (")
                   .append(String.format("%.1f", seekMetric.getAverageTimeMs()))
                   .append("ms avg). ");
            
            if (listener != null) {
                listener.onPerformanceWarning(
                    "Video seeking slower than target",
                    "Video file may be too large or codec not optimized for seeking"
                );
            }
        }
        
        // 5. Reset counters
        frameDropCount = 0;
        slowSeekCount = 0;
        
        isOptimizationInProgress = false;
        
        String finalSummary = summary.toString();
        Log.i(TAG, "Performance optimization completed: " + finalSummary);
        
        if (listener != null) {
            listener.onPerformanceOptimizationCompleted(finalSummary);
        }
    }
    
    /**
     * Check auto-save performance and optimize if needed
     */
    public void checkAutoSavePerformance(AutoSaveManager autoSaveManager) {
        if (autoSaveManager == null) {
            return;
        }
        
        PerformanceMonitor.PerformanceMetric autoSaveMetric = autoSaveManager.getAutoSavePerformanceMetric();
        if (autoSaveMetric != null) {
            double avgTimeMs = autoSaveMetric.getAverageTimeMs();
            
            if (avgTimeMs > AUTO_SAVE_SLOW_THRESHOLD_MS) {
                Log.w(TAG, "Auto-save performance issue detected: " + avgTimeMs + "ms average");
                
                if (listener != null) {
                    listener.onAutoSavePerformanceIssue((long) avgTimeMs);
                    listener.onPerformanceWarning(
                        "Auto-save taking too long: " + String.format("%.1f", avgTimeMs) + "ms",
                        "Consider reducing project complexity or freeing up storage space"
                    );
                }
                
                // Trigger memory optimization to help with auto-save performance
                memoryOptimizer.checkMemoryStatus();
            }
        }
    }
    
    /**
     * Get comprehensive performance report
     */
    public String getPerformanceReport() {
        StringBuilder report = new StringBuilder();
        report.append("=== Performance Report ===\n");
        
        // Memory status
        report.append("Available Memory: ").append(memoryOptimizer.getAvailableMemoryMB()).append("MB\n");
        report.append("GPU Memory Usage: ").append(performanceMonitor.getGpuMemoryUsageMB()).append("MB\n");
        report.append("Low Memory State: ").append(memoryOptimizer.isLowMemory()).append("\n");
        
        // Performance metrics
        PerformanceMonitor.PerformanceMetric frameMetric = performanceMonitor.getMetric("frame_render");
        if (frameMetric != null) {
            report.append("Frame Render: avg=").append(String.format("%.1f", frameMetric.getAverageTimeMs()))
                  .append("ms, samples=").append(frameMetric.getSampleCount()).append("\n");
        }
        
        PerformanceMonitor.PerformanceMetric seekMetric = performanceMonitor.getMetric("video_seek");
        if (seekMetric != null) {
            report.append("Video Seek: avg=").append(String.format("%.1f", seekMetric.getAverageTimeMs()))
                  .append("ms, samples=").append(seekMetric.getSampleCount()).append("\n");
        }
        
        PerformanceMonitor.PerformanceMetric trimMetric = performanceMonitor.getMetric("trim_operation");
        if (trimMetric != null) {
            report.append("Trim Operation: avg=").append(String.format("%.1f", trimMetric.getAverageTimeMs()))
                  .append("ms, samples=").append(trimMetric.getSampleCount()).append("\n");
        }
        
        // Memory optimizer stats
        report.append(memoryOptimizer.getMemoryStats()).append("\n");
        
        // Overall performance assessment
        boolean isPerformanceGood = performanceMonitor.isPerformanceAcceptable();
        report.append("Overall Performance: ").append(isPerformanceGood ? "GOOD" : "NEEDS OPTIMIZATION").append("\n");
        
        report.append("========================");
        
        return report.toString();
    }
    
    /**
     * Cleanup resources
     */
    public void cleanup() {
        performanceMonitor.removePerformanceListener(this);
        memoryOptimizer.removeListener(this);
        memoryOptimizer.cleanup();
        listener = null;
        
        Log.d(TAG, "PerformanceOptimizer cleaned up");
    }
    
    // PerformanceMonitor.PerformanceListener implementation
    
    @Override
    public void onFrameDropDetected(long frameTimeMs) {
        frameDropCount++;
        Log.w(TAG, "Frame drop detected: " + frameTimeMs + "ms (count: " + frameDropCount + ")");
        
        if (frameDropCount >= FRAME_DROP_THRESHOLD_COUNT) {
            performComprehensiveOptimization("Multiple frame drops detected");
        }
    }
    
    @Override
    public void onMemoryWarning(long memoryUsageMB) {
        Log.w(TAG, "GPU memory warning: " + memoryUsageMB + "MB");
        
        if (listener != null) {
            listener.onPerformanceWarning(
                "High GPU memory usage: " + memoryUsageMB + "MB",
                "Consider reducing video resolution or restarting the editor"
            );
        }
        
        // Trigger immediate optimization for memory issues
        performComprehensiveOptimization("High GPU memory usage");
    }
    
    @Override
    public void onSlowSeekDetected(long seekTimeMs) {
        slowSeekCount++;
        Log.w(TAG, "Slow seek detected: " + seekTimeMs + "ms (count: " + slowSeekCount + ")");
        
        if (slowSeekCount >= SLOW_SEEK_THRESHOLD_COUNT) {
            performComprehensiveOptimization("Multiple slow seeks detected");
        }
    }
    
    @Override
    public void onPerformanceMetricUpdated(String metricName, PerformanceMonitor.PerformanceMetric metric) {
        // Log significant performance changes
        if ("frame_render".equals(metricName) && metric.getSampleCount() % 100 == 0) {
            Log.d(TAG, "Frame render performance: avg=" + String.format("%.1f", metric.getAverageTimeMs()) + "ms");
        }
    }
    
    // MemoryOptimizer.MemoryOptimizationListener implementation
    
    @Override
    public void onLowMemoryWarning(long availableMemoryMB) {
        Log.w(TAG, "Low memory warning: " + availableMemoryMB + "MB available");
        
        if (listener != null) {
            listener.onPerformanceWarning(
                "Low device memory: " + availableMemoryMB + "MB available",
                "Close other apps or reduce video quality to improve performance"
            );
        }
    }
    
    @Override
    public void onCriticalMemoryWarning(long availableMemoryMB) {
        Log.e(TAG, "Critical memory warning: " + availableMemoryMB + "MB available");
        
        if (listener != null) {
            listener.onPerformanceWarning(
                "Critical memory situation: " + availableMemoryMB + "MB available",
                "Immediately close other apps or the editor may become unstable"
            );
        }
        
        // Force immediate optimization for critical memory
        performComprehensiveOptimization("Critical memory situation");
    }
    
    @Override
    public void onMemoryOptimizationPerformed(String operation, long memoryFreedMB) {
        Log.i(TAG, "Memory optimization performed: " + operation + ", freed " + memoryFreedMB + "MB");
        
        if (listener != null) {
            listener.onPerformanceOptimizationCompleted(
                "Memory optimization: " + operation + " freed " + memoryFreedMB + "MB"
            );
        }
    }
}