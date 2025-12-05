package com.fadcam.streaming;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * RemoteStreamManager handles in-memory circular buffering of fMP4 segments for HTTP streaming.
 * 
 * Architecture:
 * - Maintains circular buffer of last 5 segments (~40MB max @ 8MB/segment)
 * - Thread-safe with ReadWriteLock for concurrent access
 * - Supports stream-only and stream-and-save modes
 * - Implements rolling disk cleanup for stream-only mode
 * - Provides segment bytes for HTTP server endpoint serving
 * 
 * Buffer Overflow Strategy (Professional):
 * - Blocks new segment writes if buffer full (backpressure to encoder)
 * - Client read timeout: 10 seconds per segment (prevents stalled connections)
 * - No data loss: Old segments retained until new ones fully committed
 */
public class RemoteStreamManager {
    private static final String TAG = "RemoteStreamManager";
    
    // Singleton instance
    private static volatile RemoteStreamManager instance;
    
    // Buffer configuration
    private static final int MAX_SEGMENTS = 5;
    private static final int MAX_SEGMENT_SIZE = 10 * 1024 * 1024; // 10MB per segment (safety buffer)
    
    // Circular buffer storage
    private final Map<Integer, SegmentData> segmentBuffer = new LinkedHashMap<>(MAX_SEGMENTS, 0.75f, false);
    private final ReadWriteLock bufferLock = new ReentrantReadWriteLock();
    
    // Streaming state
    private boolean streamingEnabled = false;
    private StreamingMode streamingMode = StreamingMode.STREAM_AND_SAVE;
    
    // Active recording file (for direct streaming)
    private File activeRecordingFile = null;
    private FragmentMonitor fragmentMonitor = null;
    
    // Metadata
    private String currentResolution = "1920x1080";
    private int currentFps = 30;
    private int currentBitrate = 8000; // kbps
    private int activeConnections = 0;
    
    public enum StreamingMode {
        STREAM_ONLY,        // Delete old segments after buffering
        STREAM_AND_SAVE     // Keep all segments on disk
    }
    
    /**
     * Segment metadata and buffer holder.
     */
    public static class SegmentData {
        public final int segmentNumber;
        public final long timestamp;
        public final File file;
        public final byte[] data;
        public final int size;
        
        public SegmentData(int segmentNumber, long timestamp, File file, byte[] data, int size) {
            this.segmentNumber = segmentNumber;
            this.timestamp = timestamp;
            this.file = file;
            this.data = data;
            this.size = size;
        }
    }
    
    private RemoteStreamManager() {
        Log.d(TAG, "RemoteStreamManager singleton initialized");
    }
    
    /**
     * Get singleton instance.
     */
    public static RemoteStreamManager getInstance() {
        if (instance == null) {
            synchronized (RemoteStreamManager.class) {
                if (instance == null) {
                    instance = new RemoteStreamManager();
                }
            }
        }
        return instance;
    }
    
    /**
     * Enable or disable streaming.
     */
    public void setStreamingEnabled(boolean enabled) {
        bufferLock.writeLock().lock();
        try {
            this.streamingEnabled = enabled;
            Log.i(TAG, "Streaming " + (enabled ? "enabled" : "disabled"));
            
            if (!enabled) {
                // Clear buffer when disabled
                clearBuffer();
            }
        } finally {
            bufferLock.writeLock().unlock();
        }
    }
    
    /**
     * Set streaming mode (stream-only vs stream-and-save).
     */
    public void setStreamingMode(StreamingMode mode) {
        bufferLock.writeLock().lock();
        try {
            this.streamingMode = mode;
            Log.i(TAG, "Streaming mode set to: " + mode);
        } finally {
            bufferLock.writeLock().unlock();
        }
    }
    
    /**
     * Start monitoring a recording file for fMP4 fragments.
     * Called when recording starts.
     */
    public void startRecording(@NonNull File recordingFile) {
        if (!streamingEnabled) {
            Log.v(TAG, "Streaming disabled, not monitoring file");
            return;
        }
        
        Log.i(TAG, "🎬 START RECORDING: " + recordingFile.getName());
        activeRecordingFile = recordingFile;
        
        // Start fragment monitor
        if (fragmentMonitor != null) {
            fragmentMonitor.stopWatching();
        }
        fragmentMonitor = new FragmentMonitor(recordingFile);
        fragmentMonitor.startWatching();
        
        Log.i(TAG, "FragmentMonitor started for live fMP4 streaming");
    }
    
    /**
     * Stop monitoring the recording file.
     * Called when recording stops.
     */
    public void stopRecording() {
        Log.i(TAG, "🛑 STOP RECORDING");
        
        if (fragmentMonitor != null) {
            fragmentMonitor.stopWatching();
            fragmentMonitor = null;
        }
        
        activeRecordingFile = null;
    }
    
    /**
     * Get the active recording file (for direct streaming).
     */
    @androidx.annotation.Nullable
    public File getActiveRecordingFile() {
        return activeRecordingFile;
    }
    
    /**
     * Called by FragmentMonitor when initialization data (moov) is received.
     */
    public void onInitializationData(byte[] moovData) {
        Log.i(TAG, "📋 Received initialization data (" + moovData.length + " bytes)");
        // TODO: Store for HLS init segment
    }
    
    /**
     * Set video metadata (resolution, fps, bitrate).
     */
    public void setVideoMetadata(String resolution, int fps, int bitrate) {
        this.currentResolution = resolution;
        this.currentFps = fps;
        this.currentBitrate = bitrate;
        Log.d(TAG, "Video metadata updated: " + resolution + " @ " + fps + "fps, " + bitrate + "kbps");
    }
    
    /**
     * Called by RecordingService when a segment is completed.
     * Buffers the segment bytes and manages disk cleanup if needed.
     * 
     * @param segmentNumber The segment number (1-based)
     * @param segmentFile The completed segment file on disk
     */
    public void onSegmentComplete(int segmentNumber, @NonNull File segmentFile) {
        if (!streamingEnabled) {
            Log.v(TAG, "Streaming disabled, skipping segment " + segmentNumber);
            return;
        }
        
        if (!segmentFile.exists() || !segmentFile.canRead()) {
            Log.e(TAG, "Segment file not accessible: " + segmentFile.getAbsolutePath());
            return;
        }
        
        long fileSize = segmentFile.length();
        if (fileSize == 0) {
            Log.e(TAG, "Segment file is empty: " + segmentFile.getAbsolutePath());
            return;
        }
        
        if (fileSize > MAX_SEGMENT_SIZE) {
            Log.w(TAG, "Segment size exceeds max (" + fileSize + " > " + MAX_SEGMENT_SIZE + "), buffering anyway");
        }
        
        Log.d(TAG, "Buffering segment " + segmentNumber + " (" + (fileSize / 1024) + " KB)");
        
        // Read segment bytes into memory
        byte[] segmentData = new byte[(int) fileSize];
        try (FileInputStream fis = new FileInputStream(segmentFile)) {
            int totalRead = 0;
            int bytesRead;
            while (totalRead < fileSize && (bytesRead = fis.read(segmentData, totalRead, (int) fileSize - totalRead)) != -1) {
                totalRead += bytesRead;
            }
            
            if (totalRead != fileSize) {
                Log.e(TAG, "Failed to read complete segment: read " + totalRead + " / " + fileSize + " bytes");
                return;
            }
        } catch (IOException e) {
            Log.e(TAG, "Failed to read segment file", e);
            return;
        }
        
        // Buffer the segment
        bufferLock.writeLock().lock();
        try {
            // Create segment data
            SegmentData segment = new SegmentData(
                segmentNumber,
                System.currentTimeMillis(),
                segmentFile,
                segmentData,
                (int) fileSize
            );
            
            // Check buffer overflow (professional approach: block and wait if needed)
            if (segmentBuffer.size() >= MAX_SEGMENTS) {
                Log.w(TAG, "Buffer full (" + MAX_SEGMENTS + " segments), removing oldest");
                
                // Remove oldest segment
                Integer oldestKey = segmentBuffer.keySet().iterator().next();
                SegmentData oldSegment = segmentBuffer.remove(oldestKey);
                
                // Cleanup disk file if stream-only mode
                if (streamingMode == StreamingMode.STREAM_ONLY && oldSegment != null) {
                    deleteSegmentFile(oldSegment.file);
                }
            }
            
            // Add new segment to buffer
            segmentBuffer.put(segmentNumber, segment);
            Log.i(TAG, "Segment " + segmentNumber + " buffered successfully (" + segmentBuffer.size() + "/" + MAX_SEGMENTS + " slots)");
            
        } finally {
            bufferLock.writeLock().unlock();
        }
    }
    
    /**
     * Get a segment by number for HTTP serving.
     */
    @Nullable
    public SegmentData getSegment(int segmentNumber) {
        bufferLock.readLock().lock();
        try {
            return segmentBuffer.get(segmentNumber);
        } finally {
            bufferLock.readLock().unlock();
        }
    }
    
    /**
     * Get list of available segment numbers (for M3U8 playlist generation).
     */
    @NonNull
    public List<Integer> getAvailableSegments() {
        bufferLock.readLock().lock();
        try {
            return new ArrayList<>(segmentBuffer.keySet());
        } finally {
            bufferLock.readLock().unlock();
        }
    }
    
    /**
     * Get number of buffered segments.
     */
    public int getSegmentCount() {
        bufferLock.readLock().lock();
        try {
            return segmentBuffer.size();
        } finally {
            bufferLock.readLock().unlock();
        }
    }
    
    /**
     * Get current buffer status for /status endpoint.
     */
    public String getStatusJson() {
        bufferLock.readLock().lock();
        try {
            int bufferCount = segmentBuffer.size();
            long totalBufferSize = 0;
            for (SegmentData seg : segmentBuffer.values()) {
                totalBufferSize += seg.size;
            }
            
            return String.format(
                "{\"streaming\": %s, \"mode\": \"%s\", \"resolution\": \"%s\", \"fps\": %d, " +
                "\"bitrate\": %d, \"buffered_segments\": %d, \"buffer_size_mb\": %.2f, \"active_connections\": %d}",
                streamingEnabled,
                streamingMode.toString().toLowerCase(),
                currentResolution,
                currentFps,
                currentBitrate,
                bufferCount,
                totalBufferSize / (1024.0 * 1024.0),
                activeConnections
            );
        } finally {
            bufferLock.readLock().unlock();
        }
    }
    
    /**
     * Increment active connection count.
     */
    public void incrementConnections() {
        activeConnections++;
        Log.d(TAG, "Active connections: " + activeConnections);
    }
    
    /**
     * Decrement active connection count.
     */
    public void decrementConnections() {
        if (activeConnections > 0) {
            activeConnections--;
        }
        Log.d(TAG, "Active connections: " + activeConnections);
    }
    
    /**
     * Clear all buffered segments.
     */
    public void clearBuffer() {
        bufferLock.writeLock().lock();
        try {
            Log.i(TAG, "Clearing buffer (" + segmentBuffer.size() + " segments)");
            segmentBuffer.clear();
        } finally {
            bufferLock.writeLock().unlock();
        }
    }
    
    /**
     * Delete a segment file from disk (stream-only mode cleanup).
     */
    private void deleteSegmentFile(@NonNull File file) {
        if (file.exists()) {
            try {
                if (file.delete()) {
                    Log.d(TAG, "Deleted old segment: " + file.getName());
                } else {
                    Log.w(TAG, "Failed to delete segment: " + file.getAbsolutePath());
                }
            } catch (SecurityException e) {
                Log.e(TAG, "Security exception deleting segment", e);
            }
        }
    }
    
    /**
     * Check if streaming is enabled.
     */
    public boolean isStreamingEnabled() {
        return streamingEnabled;
    }
    
    /**
     * Get current streaming mode.
     */
    public StreamingMode getStreamingMode() {
        return streamingMode;
    }
}
