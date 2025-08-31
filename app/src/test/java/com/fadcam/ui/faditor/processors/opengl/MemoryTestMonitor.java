package com.fadcam.ui.faditor.processors.opengl;

import android.app.ActivityManager;
import android.content.Context;
import android.opengl.GLES20;
import com.fadcam.Log;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Memory monitoring utility for OpenGL video system testing.
 * Tracks GPU memory usage, texture allocation, and memory leaks during extended editing sessions.
 */
public class MemoryTestMonitor {
    private static final String TAG = "MemoryTestMonitor";
    
    // Memory tracking
    private final AtomicLong initialMemory = new AtomicLong(0);
    private final AtomicLong peakMemory = new AtomicLong(0);
    private final List<MemorySnapshot> memoryHistory = new ArrayList<>();
    
    // GPU memory tracking
    private final AtomicLong initialGpuMemory = new AtomicLong(0);
    private final AtomicLong currentGpuMemory = new AtomicLong(0);
    private final AtomicLong peakGpuMemory = new AtomicLong(0);
    
    // Texture tracking
    private final AtomicLong texturesCreated = new AtomicLong(0);
    private final AtomicLong texturesReleased = new AtomicLong(0);
    private final AtomicLong activeTextures = new AtomicLong(0);
    
    // Memory leak detection
    private final List<Long> memoryLeakCheckpoints = new ArrayList<>();
    private boolean leakDetectionEnabled = true;
    
    public static class MemorySnapshot {
        public final long timestamp;
        public final long totalMemory;
        public final long usedMemory;
        public final long freeMemory;
        public final long gpuMemory;
        public final long activeTextures;
        public final String operation;
        
        public MemorySnapshot(long totalMemory, long usedMemory, long freeMemory, 
                            long gpuMemory, long activeTextures, String operation) {
            this.timestamp = System.currentTimeMillis();
            this.totalMemory = totalMemory;
            this.usedMemory = usedMemory;
            this.freeMemory = freeMemory;
            this.gpuMemory = gpuMemory;
            this.activeTextures = activeTextures;
            this.operation = operation;
        }
        
        @Override
        public String toString() {
            return String.format("MemorySnapshot{time=%d, used=%dMB, gpu=%dMB, textures=%d, op='%s'}",
                               timestamp, usedMemory / 1024 / 1024, gpuMemory / 1024 / 1024, 
                               activeTextures, operation);
        }
    }
    
    public MemoryTestMonitor() {
        initialize();
    }
    
    private void initialize() {
        // Record initial memory state
        long currentMemory = getCurrentMemoryUsage();
        initialMemory.set(currentMemory);
        peakMemory.set(currentMemory);
        
        // Record initial GPU memory if available
        long gpuMemory = getGpuMemoryUsage();
        initialGpuMemory.set(gpuMemory);
        currentGpuMemory.set(gpuMemory);
        peakGpuMemory.set(gpuMemory);
        
        takeSnapshot("initialization");
        
        Log.d(TAG, "Memory monitor initialized - Initial memory: " + (currentMemory / 1024 / 1024) + "MB");
    }
    
    public long getCurrentMemoryUsage() {
        Runtime runtime = Runtime.getRuntime();
        return runtime.totalMemory() - runtime.freeMemory();
    }
    
    public long getTotalMemory() {
        return Runtime.getRuntime().totalMemory();
    }
    
    public long getFreeMemory() {
        return Runtime.getRuntime().freeMemory();
    }
    
    public long getGpuMemoryUsage() {
        // Attempt to get GPU memory information
        // This is device-dependent and may not be available on all devices
        try {
            // Try to get OpenGL memory info (this is a simplified approach)
            int[] memInfo = new int[1];
            GLES20.glGetIntegerv(GLES20.GL_MAX_TEXTURE_SIZE, memInfo, 0);
            
            // Estimate GPU memory usage based on texture allocations
            // This is an approximation since actual GPU memory usage is hard to measure
            long estimatedGpuMemory = activeTextures.get() * 4 * 1024 * 1024; // Assume 4MB per texture
            currentGpuMemory.set(estimatedGpuMemory);
            
            if (estimatedGpuMemory > peakGpuMemory.get()) {
                peakGpuMemory.set(estimatedGpuMemory);
            }
            
            return estimatedGpuMemory;
        } catch (Exception e) {
            Log.w(TAG, "Could not get GPU memory info: " + e.getMessage());
            return 0;
        }
    }
    
    public void takeSnapshot(String operation) {
        long totalMem = getTotalMemory();
        long usedMem = getCurrentMemoryUsage();
        long freeMem = getFreeMemory();
        long gpuMem = getGpuMemoryUsage();
        long textures = activeTextures.get();
        
        MemorySnapshot snapshot = new MemorySnapshot(totalMem, usedMem, freeMem, gpuMem, textures, operation);
        memoryHistory.add(snapshot);
        
        // Update peak memory
        if (usedMem > peakMemory.get()) {
            peakMemory.set(usedMem);
        }
        
        Log.v(TAG, "Memory snapshot: " + snapshot.toString());
    }
    
    public void onTextureCreated() {
        texturesCreated.incrementAndGet();
        activeTextures.incrementAndGet();
        takeSnapshot("texture_created");
    }
    
    public void onTextureReleased() {
        texturesReleased.incrementAndGet();
        activeTextures.decrementAndGet();
        takeSnapshot("texture_released");
    }
    
    public void startMemoryLeakDetection() {
        leakDetectionEnabled = true;
        memoryLeakCheckpoints.clear();
        memoryLeakCheckpoints.add(getCurrentMemoryUsage());
        Log.d(TAG, "Memory leak detection started");
    }
    
    public void addMemoryLeakCheckpoint() {
        if (leakDetectionEnabled) {
            long currentMemory = getCurrentMemoryUsage();
            memoryLeakCheckpoints.add(currentMemory);
            Log.d(TAG, "Memory leak checkpoint: " + (currentMemory / 1024 / 1024) + "MB");
        }
    }
    
    public boolean detectMemoryLeak() {
        if (!leakDetectionEnabled || memoryLeakCheckpoints.size() < 2) {
            return false;
        }
        
        // Check if memory usage is consistently increasing
        long firstCheckpoint = memoryLeakCheckpoints.get(0);
        long lastCheckpoint = memoryLeakCheckpoints.get(memoryLeakCheckpoints.size() - 1);
        
        long memoryIncrease = lastCheckpoint - firstCheckpoint;
        long leakThreshold = 50 * 1024 * 1024; // 50MB threshold
        
        boolean hasLeak = memoryIncrease > leakThreshold;
        
        if (hasLeak) {
            Log.w(TAG, "Potential memory leak detected - Increase: " + (memoryIncrease / 1024 / 1024) + "MB");
        }
        
        return hasLeak;
    }
    
    public void stopMemoryLeakDetection() {
        leakDetectionEnabled = false;
        Log.d(TAG, "Memory leak detection stopped");
    }
    
    // Performance validation methods
    
    public boolean isMemoryUsageAcceptable(long maxMemoryMB) {
        long currentMemoryMB = getCurrentMemoryUsage() / 1024 / 1024;
        return currentMemoryMB <= maxMemoryMB;
    }
    
    public boolean isGpuMemoryUsageAcceptable(long maxGpuMemoryMB) {
        long currentGpuMemoryMB = getGpuMemoryUsage() / 1024 / 1024;
        return currentGpuMemoryMB <= maxGpuMemoryMB;
    }
    
    public boolean areTexturesProperlyManaged() {
        // Check if textures are being properly released
        long created = texturesCreated.get();
        long released = texturesReleased.get();
        long active = activeTextures.get();
        
        // Allow some active textures, but not excessive accumulation
        boolean properlyManaged = (created - released == active) && (active < 100);
        
        if (!properlyManaged) {
            Log.w(TAG, "Texture management issue - Created: " + created + 
                      ", Released: " + released + ", Active: " + active);
        }
        
        return properlyManaged;
    }
    
    // Statistics and reporting
    
    public long getInitialMemory() {
        return initialMemory.get();
    }
    
    public long getPeakMemory() {
        return peakMemory.get();
    }
    
    public long getMemoryIncrease() {
        return getCurrentMemoryUsage() - initialMemory.get();
    }
    
    public long getPeakGpuMemory() {
        return peakGpuMemory.get();
    }
    
    public long getTexturesCreated() {
        return texturesCreated.get();
    }
    
    public long getTexturesReleased() {
        return texturesReleased.get();
    }
    
    public long getActiveTextures() {
        return activeTextures.get();
    }
    
    public List<MemorySnapshot> getMemoryHistory() {
        return new ArrayList<>(memoryHistory);
    }
    
    public String getMemoryReport() {
        StringBuilder report = new StringBuilder();
        report.append("=== Memory Usage Report ===\n");
        report.append("Initial Memory: ").append(initialMemory.get() / 1024 / 1024).append("MB\n");
        report.append("Current Memory: ").append(getCurrentMemoryUsage() / 1024 / 1024).append("MB\n");
        report.append("Peak Memory: ").append(peakMemory.get() / 1024 / 1024).append("MB\n");
        report.append("Memory Increase: ").append(getMemoryIncrease() / 1024 / 1024).append("MB\n");
        report.append("GPU Memory: ").append(getGpuMemoryUsage() / 1024 / 1024).append("MB\n");
        report.append("Peak GPU Memory: ").append(peakGpuMemory.get() / 1024 / 1024).append("MB\n");
        report.append("Textures Created: ").append(texturesCreated.get()).append("\n");
        report.append("Textures Released: ").append(texturesReleased.get()).append("\n");
        report.append("Active Textures: ").append(activeTextures.get()).append("\n");
        report.append("Memory Leak Detected: ").append(detectMemoryLeak() ? "YES" : "NO").append("\n");
        report.append("Textures Properly Managed: ").append(areTexturesProperlyManaged() ? "YES" : "NO").append("\n");
        
        if (!memoryHistory.isEmpty()) {
            report.append("\n=== Memory History (Last 5 snapshots) ===\n");
            int start = Math.max(0, memoryHistory.size() - 5);
            for (int i = start; i < memoryHistory.size(); i++) {
                report.append(memoryHistory.get(i).toString()).append("\n");
            }
        }
        
        return report.toString();
    }
    
    // Cleanup
    
    public void reset() {
        memoryHistory.clear();
        memoryLeakCheckpoints.clear();
        texturesCreated.set(0);
        texturesReleased.set(0);
        activeTextures.set(0);
        initialize();
    }
    
    public void cleanup() {
        memoryHistory.clear();
        memoryLeakCheckpoints.clear();
        leakDetectionEnabled = false;
        Log.d(TAG, "Memory monitor cleaned up");
    }
}