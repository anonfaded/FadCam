package com.fadcam.ui.faditor.processors.opengl;

import android.opengl.GLES20;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.fadcam.ui.faditor.utils.PerformanceMonitor;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * GPU memory optimizer for preventing texture memory leaks and managing resources efficiently.
 * Implements intelligent memory management for 4K video support and professional editing.
 * Requirements: 13.4, 13.5, 14.4, 14.7
 */
public class GpuMemoryOptimizer {
    
    private static final String TAG = "GpuMemoryOptimizer";
    
    // Enhanced memory management configuration for professional 4K editing
    private static final long MEMORY_CHECK_INTERVAL_MS = 3000; // 3 seconds for faster response
    private static final long CRITICAL_MEMORY_THRESHOLD_MB = 500; // 500MB critical threshold for 4K
    private static final long WARNING_MEMORY_THRESHOLD_MB = 350; // 350MB warning threshold
    private static final long EMERGENCY_CLEANUP_THRESHOLD_MB = 550; // 550MB emergency cleanup
    private static final int MAX_MEMORY_PRESSURE_EVENTS = 3; // Reduced for faster aggressive cleanup
    
    // 4K-specific memory management
    private static final long MEMORY_4K_CRITICAL_THRESHOLD_MB = 300; // 300MB for 4K textures alone
    private static final long MEMORY_4K_WARNING_THRESHOLD_MB = 200; // 200MB 4K warning
    private static final int MAX_4K_MEMORY_PRESSURE_EVENTS = 2; // Faster 4K cleanup
    
    // Memory tracking
    private final AtomicLong totalGpuMemory = new AtomicLong(0);
    private final AtomicLong peakGpuMemory = new AtomicLong(0);
    private final AtomicBoolean memoryPressureActive = new AtomicBoolean(false);
    private final AtomicBoolean emergencyCleanupActive = new AtomicBoolean(false);
    
    // Components
    private final TextureManager textureManager;
    private final FrameCache frameCache;
    private final PerformanceMonitor performanceMonitor;
    
    // Memory monitoring
    private final Handler memoryHandler;
    private final Runnable memoryCheckRunnable;
    private final ConcurrentHashMap<String, MemoryAllocation> activeAllocations = new ConcurrentHashMap<>();
    
    // Memory pressure tracking
    private int memoryPressureEvents = 0;
    private int memory4KPressureEvents = 0; // Separate tracking for 4K memory pressure
    private long lastMemoryPressureTime = 0;
    private long totalMemoryFreed = 0;
    private int emergencyCleanupCount = 0;
    
    // 4K memory optimization state
    private boolean is4KOptimizationActive = false;
    private long total4KMemoryUsage = 0;
    
    // Memory allocation tracking
    private static class MemoryAllocation {
        final String resourceType;
        final long memorySize;
        final long allocationTime;
        final String stackTrace;
        
        MemoryAllocation(String resourceType, long memorySize) {
            this.resourceType = resourceType;
            this.memorySize = memorySize;
            this.allocationTime = System.currentTimeMillis();
            this.stackTrace = Log.getStackTraceString(new Throwable());
        }
    }
    
    public interface MemoryOptimizerListener {
        void onMemoryWarning(long memoryUsageMB);
        void onMemoryPressure(long memoryUsageMB);
        void onEmergencyCleanup(long memoryFreedMB);
        void onMemoryOptimized(MemoryOptimizationStats stats);
    }
    
    public static class MemoryOptimizationStats {
        public final long totalGpuMemoryMB;
        public final long peakGpuMemoryMB;
        public final long totalMemoryFreedMB;
        public final int memoryPressureEvents;
        public final int emergencyCleanupCount;
        public final boolean memoryPressureActive;
        public final int activeAllocations;
        
        MemoryOptimizationStats(long totalGpuMemoryMB, long peakGpuMemoryMB, long totalMemoryFreedMB,
                               int memoryPressureEvents, int emergencyCleanupCount, 
                               boolean memoryPressureActive, int activeAllocations) {
            this.totalGpuMemoryMB = totalGpuMemoryMB;
            this.peakGpuMemoryMB = peakGpuMemoryMB;
            this.totalMemoryFreedMB = totalMemoryFreedMB;
            this.memoryPressureEvents = memoryPressureEvents;
            this.emergencyCleanupCount = emergencyCleanupCount;
            this.memoryPressureActive = memoryPressureActive;
            this.activeAllocations = activeAllocations;
        }
    }
    
    private MemoryOptimizerListener listener;
    
    public GpuMemoryOptimizer(TextureManager textureManager, FrameCache frameCache) {
        this.textureManager = textureManager;
        this.frameCache = frameCache;
        this.performanceMonitor = PerformanceMonitor.getInstance();
        this.memoryHandler = new Handler(Looper.getMainLooper());
        
        // Initialize memory check runnable
        this.memoryCheckRunnable = this::performMemoryCheck;
        
        Log.d(TAG, "GpuMemoryOptimizer initialized");
        startMemoryMonitoring();
    }
    
    /**
     * Set memory optimizer listener
     */
    public void setListener(MemoryOptimizerListener listener) {
        this.listener = listener;
    }
    
    /**
     * Enable 4K optimization mode for enhanced memory management
     */
    public void enable4KOptimization(boolean enabled) {
        this.is4KOptimizationActive = enabled;
        if (enabled) {
            Log.d(TAG, "4K memory optimization enabled - enhanced monitoring active");
        } else {
            Log.d(TAG, "4K memory optimization disabled");
        }
    }
    
    /**
     * Track GPU memory allocation with 4K-specific handling
     */
    public void trackAllocation(String allocationId, String resourceType, long memorySize) {
        MemoryAllocation allocation = new MemoryAllocation(resourceType, memorySize);
        activeAllocations.put(allocationId, allocation);
        
        long newTotal = totalGpuMemory.addAndGet(memorySize);
        
        // Track 4K memory separately if this is a 4K resource
        if (is4KResource(resourceType, memorySize)) {
            total4KMemoryUsage += memorySize;
        }
        
        // Update peak memory
        long currentPeak = peakGpuMemory.get();
        if (newTotal > currentPeak) {
            peakGpuMemory.set(newTotal);
        }
        
        // Track with performance monitor
        performanceMonitor.trackGpuResourceAllocation(resourceType, memorySize);
        
        Log.v(TAG, String.format("GPU allocation tracked: %s (%s) = %d bytes, total = %d MB, 4K = %d MB", 
                allocationId, resourceType, memorySize, newTotal / (1024 * 1024), 
                total4KMemoryUsage / (1024 * 1024)));
        
        // Check for memory pressure (including 4K-specific checks)
        checkMemoryPressure(newTotal);
        if (is4KOptimizationActive) {
            check4KMemoryPressure();
        }
    }
    
    /**
     * Track GPU memory deallocation with 4K-specific handling
     */
    public void trackDeallocation(String allocationId) {
        MemoryAllocation allocation = activeAllocations.remove(allocationId);
        if (allocation != null) {
            long newTotal = totalGpuMemory.addAndGet(-allocation.memorySize);
            totalMemoryFreed += allocation.memorySize;
            
            // Track 4K memory separately if this was a 4K resource
            if (is4KResource(allocation.resourceType, allocation.memorySize)) {
                total4KMemoryUsage -= allocation.memorySize;
            }
            
            // Track with performance monitor
            performanceMonitor.trackGpuResourceDeallocation(allocation.resourceType, allocation.memorySize);
            
            Log.v(TAG, String.format("GPU deallocation tracked: %s (%s) = %d bytes freed, total = %d MB, 4K = %d MB", 
                    allocationId, allocation.resourceType, allocation.memorySize, newTotal / (1024 * 1024),
                    total4KMemoryUsage / (1024 * 1024)));
        } else {
            Log.w(TAG, "Attempted to deallocate unknown allocation: " + allocationId);
        }
    }
    
    /**
     * Force memory optimization
     */
    public void optimizeMemory() {
        Log.d(TAG, "Forcing memory optimization");
        
        long memoryBefore = totalGpuMemory.get();
        
        // Clean up texture manager
        textureManager.cleanupPool();
        
        // Clean up frame cache if memory pressure is high
        if (memoryPressureActive.get()) {
            FrameCache.CacheStats cacheStats = frameCache.getCacheStats();
            if (cacheStats.memoryUsageMB > 50) { // If cache uses more than 50MB
                frameCache.clearCache();
                Log.d(TAG, "Frame cache cleared due to memory pressure");
            }
        }
        
        long memoryAfter = totalGpuMemory.get();
        long memoryFreed = memoryBefore - memoryAfter;
        
        if (memoryFreed > 0) {
            Log.d(TAG, String.format("Memory optimization freed %d MB", memoryFreed / (1024 * 1024)));
        }
        
        // Notify listener
        if (listener != null) {
            listener.onMemoryOptimized(getMemoryStats());
        }
    }
    
    /**
     * Perform emergency cleanup when memory is critically low
     */
    public void performEmergencyCleanup() {
        if (emergencyCleanupActive.getAndSet(true)) {
            return; // Already performing emergency cleanup
        }
        
        Log.w(TAG, "Performing emergency GPU memory cleanup");
        emergencyCleanupCount++;
        
        long memoryBefore = totalGpuMemory.get();
        
        // Clear frame cache completely
        frameCache.clearCache();
        
        // Force aggressive texture cleanup
        textureManager.cleanupPool();
        
        // Force garbage collection (last resort)
        System.gc();
        
        long memoryAfter = totalGpuMemory.get();
        long memoryFreed = memoryBefore - memoryAfter;
        
        Log.w(TAG, String.format("Emergency cleanup freed %d MB", memoryFreed / (1024 * 1024)));
        
        // Notify listener
        if (listener != null) {
            listener.onEmergencyCleanup(memoryFreed / (1024 * 1024));
        }
        
        emergencyCleanupActive.set(false);
    }
    
    /**
     * Check for memory leaks by analyzing long-lived allocations
     */
    public void checkForMemoryLeaks() {
        long currentTime = System.currentTimeMillis();
        long leakThreshold = 300000; // 5 minutes
        
        int potentialLeaks = 0;
        long leakedMemory = 0;
        
        for (MemoryAllocation allocation : activeAllocations.values()) {
            long age = currentTime - allocation.allocationTime;
            if (age > leakThreshold) {
                potentialLeaks++;
                leakedMemory += allocation.memorySize;
                
                Log.w(TAG, String.format("Potential memory leak detected: %s (%s) - age: %d minutes, size: %d MB",
                        allocation.resourceType, 
                        allocation.stackTrace.split("\n")[0], // First line of stack trace
                        age / 60000, 
                        allocation.memorySize / (1024 * 1024)));
            }
        }
        
        if (potentialLeaks > 0) {
            Log.w(TAG, String.format("Found %d potential memory leaks totaling %d MB", 
                    potentialLeaks, leakedMemory / (1024 * 1024)));
        }
    }
    
    /**
     * Get current memory optimization statistics
     */
    public MemoryOptimizationStats getMemoryStats() {
        return new MemoryOptimizationStats(
            totalGpuMemory.get() / (1024 * 1024),
            peakGpuMemory.get() / (1024 * 1024),
            totalMemoryFreed / (1024 * 1024),
            memoryPressureEvents,
            emergencyCleanupCount,
            memoryPressureActive.get(),
            activeAllocations.size()
        );
    }
    
    /**
     * Reset memory statistics
     */
    public void resetStats() {
        peakGpuMemory.set(totalGpuMemory.get());
        totalMemoryFreed = 0;
        memoryPressureEvents = 0;
        emergencyCleanupCount = 0;
        lastMemoryPressureTime = 0;
        
        Log.d(TAG, "Memory statistics reset");
    }
    
    /**
     * Start memory monitoring
     */
    private void startMemoryMonitoring() {
        memoryHandler.post(memoryCheckRunnable);
        Log.d(TAG, "Memory monitoring started");
    }
    
    /**
     * Stop memory monitoring
     */
    public void stopMemoryMonitoring() {
        memoryHandler.removeCallbacks(memoryCheckRunnable);
        Log.d(TAG, "Memory monitoring stopped");
    }
    
    // Private helper methods
    
    private boolean is4KResource(String resourceType, long memorySize) {
        // Heuristic: if it's a texture and memory size suggests 4K resolution
        if (resourceType.contains("texture") || resourceType.contains("video")) {
            // 4K RGBA texture is approximately 64MB (3840 * 2160 * 4 bytes)
            return memorySize > 50 * 1024 * 1024; // 50MB threshold for 4K detection
        }
        return false;
    }
    
    private void check4KMemoryPressure() {
        long memory4KMB = total4KMemoryUsage / (1024 * 1024);
        
        if (memory4KMB > MEMORY_4K_CRITICAL_THRESHOLD_MB) {
            handle4KMemoryPressure(memory4KMB);
        } else if (memory4KMB > MEMORY_4K_WARNING_THRESHOLD_MB) {
            handle4KMemoryWarning(memory4KMB);
        }
    }
    
    private void handle4KMemoryWarning(long memory4KMB) {
        Log.w(TAG, "4K GPU memory warning: " + memory4KMB + " MB");
        
        // Perform light 4K-specific cleanup
        if (textureManager != null) {
            // This would trigger cleanup of 4K textures specifically
            textureManager.cleanupPool();
        }
    }
    
    private void handle4KMemoryPressure(long memory4KMB) {
        long currentTime = System.currentTimeMillis();
        
        // Avoid too frequent 4K memory pressure events
        if (currentTime - lastMemoryPressureTime < 5000) { // 5 seconds
            return;
        }
        
        memory4KPressureEvents++;
        lastMemoryPressureTime = currentTime;
        
        Log.w(TAG, "4K GPU memory pressure detected: " + memory4KMB + " MB (event #" + memory4KPressureEvents + ")");
        
        if (listener != null) {
            listener.onMemoryPressure(memory4KMB);
        }
        
        // Perform aggressive 4K cleanup if too many pressure events
        if (memory4KPressureEvents >= MAX_4K_MEMORY_PRESSURE_EVENTS) {
            perform4KOptimizedCleanup();
            memory4KPressureEvents = 0; // Reset counter after cleanup
        }
    }
    
    private void perform4KOptimizedCleanup() {
        Log.w(TAG, "Performing 4K-optimized memory cleanup");
        
        long memoryBefore = totalGpuMemory.get();
        long memory4KBefore = total4KMemoryUsage;
        
        // Clear frame cache if it contains 4K frames
        if (frameCache != null) {
            FrameCache.CacheStats cacheStats = frameCache.getCacheStats();
            if (cacheStats.memoryUsageMB > 100) { // If cache uses significant memory
                frameCache.clearCache();
                Log.d(TAG, "Frame cache cleared due to 4K memory pressure");
            }
        }
        
        // Force aggressive texture cleanup with 4K priority
        if (textureManager != null) {
            textureManager.cleanupPool();
        }
        
        long memoryAfter = totalGpuMemory.get();
        long memory4KAfter = total4KMemoryUsage;
        long memoryFreed = memoryBefore - memoryAfter;
        long memory4KFreed = memory4KBefore - memory4KAfter;
        
        Log.w(TAG, String.format("4K-optimized cleanup freed %d MB total (%d MB 4K)", 
                memoryFreed / (1024 * 1024), memory4KFreed / (1024 * 1024)));
    }
    
    private void performMemoryCheck() {
        long currentMemoryMB = totalGpuMemory.get() / (1024 * 1024);
        
        // Check for emergency cleanup threshold
        if (currentMemoryMB > EMERGENCY_CLEANUP_THRESHOLD_MB) {
            performEmergencyCleanup();
        }
        // Check for critical memory threshold
        else if (currentMemoryMB > CRITICAL_MEMORY_THRESHOLD_MB) {
            handleMemoryPressure(currentMemoryMB);
        }
        // Check for warning threshold
        else if (currentMemoryMB > WARNING_MEMORY_THRESHOLD_MB) {
            handleMemoryWarning(currentMemoryMB);
        }
        // Normal memory usage
        else {
            if (memoryPressureActive.get()) {
                memoryPressureActive.set(false);
                Log.d(TAG, "Memory pressure resolved");
            }
        }
        
        // Check for memory leaks periodically
        if (System.currentTimeMillis() % 60000 < MEMORY_CHECK_INTERVAL_MS) { // Every minute
            checkForMemoryLeaks();
        }
        
        // Schedule next check
        memoryHandler.postDelayed(memoryCheckRunnable, MEMORY_CHECK_INTERVAL_MS);
    }
    
    private void checkMemoryPressure(long totalMemoryBytes) {
        long memoryMB = totalMemoryBytes / (1024 * 1024);
        
        if (memoryMB > CRITICAL_MEMORY_THRESHOLD_MB) {
            handleMemoryPressure(memoryMB);
        } else if (memoryMB > WARNING_MEMORY_THRESHOLD_MB) {
            handleMemoryWarning(memoryMB);
        }
    }
    
    private void handleMemoryWarning(long memoryMB) {
        Log.w(TAG, "GPU memory warning: " + memoryMB + " MB");
        
        if (listener != null) {
            listener.onMemoryWarning(memoryMB);
        }
        
        // Perform light cleanup
        textureManager.cleanupPool();
    }
    
    private void handleMemoryPressure(long memoryMB) {
        long currentTime = System.currentTimeMillis();
        
        // Avoid too frequent memory pressure events
        if (currentTime - lastMemoryPressureTime < 10000) { // 10 seconds
            return;
        }
        
        memoryPressureActive.set(true);
        memoryPressureEvents++;
        lastMemoryPressureTime = currentTime;
        
        Log.w(TAG, "GPU memory pressure detected: " + memoryMB + " MB (event #" + memoryPressureEvents + ")");
        
        if (listener != null) {
            listener.onMemoryPressure(memoryMB);
        }
        
        // Perform aggressive cleanup if too many pressure events
        if (memoryPressureEvents >= MAX_MEMORY_PRESSURE_EVENTS) {
            optimizeMemory();
            memoryPressureEvents = 0; // Reset counter after cleanup
        }
    }
    
    /**
     * Release all resources
     */
    public void release() {
        Log.d(TAG, "Releasing GpuMemoryOptimizer resources");
        
        stopMemoryMonitoring();
        activeAllocations.clear();
        totalGpuMemory.set(0);
        peakGpuMemory.set(0);
        memoryPressureActive.set(false);
        emergencyCleanupActive.set(false);
        listener = null;
    }
}