package com.fadcam.streaming;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Manages remote streaming state and fragment buffer.
 * 
 * This singleton coordinates between RecordingService and LiveM3U8Server,
 * maintaining a circular buffer of recent fMP4 fragments for HLS streaming.
 * 
 * Architecture:
 * - Circular buffer with 5 fragment slots (~10 seconds of video at 2s/fragment)
 * - Thread-safe read/write access via ReadWriteLock
 * - Supports both stream-only and stream-and-save modes
 * - Stores initialization segment (ftyp + moov) separately
 * - Each fragment is a complete moof+mdat pair
 */
public class RemoteStreamManager {
    private static final String TAG = "RemoteStreamManager";
    private static final int BUFFER_SIZE = 10; // Keep last 10 fragments (~20 seconds for smooth playback)
    
    private static RemoteStreamManager instance;
    
    private boolean streamingEnabled = false;
    private StreamingMode streamingMode = StreamingMode.STREAM_AND_SAVE;
    
    // Fragment buffer (circular)
    private final FragmentData[] fragmentBuffer = new FragmentData[BUFFER_SIZE];
    private int bufferHead = 0; // Next write position
    private int fragmentSequence = 0;
    private int oldestSequence = 0; // Track oldest buffered fragment
    
    // Initialization segment (ftyp + moov)
    private byte[] initializationSegment = null;
    
    // Thread safety
    private final ReadWriteLock bufferLock = new ReentrantReadWriteLock();
    
    // Active recording tracking
    private File activeRecordingFile = null;
    
    // Metadata
    private int activeConnections = 0;
    
    public enum StreamingMode {
        STREAM_ONLY,     // Don't save to disk after streaming
        STREAM_AND_SAVE  // Keep recording on disk
    }
    
    /**
     * Represents a single fMP4 fragment (moof + mdat).
     */
    public static class FragmentData {
        public final int sequenceNumber;
        public final byte[] data; // moof + mdat bytes
        public final long timestamp;
        public final int sizeBytes;
        
        public FragmentData(int sequenceNumber, byte[] data) {
            this.sequenceNumber = sequenceNumber;
            this.data = data;
            this.timestamp = System.currentTimeMillis();
            this.sizeBytes = data.length;
        }
        
        public double getDurationSeconds() {
            return 2.0; // Fragments are configured for 2 seconds
        }
    }
    
    private RemoteStreamManager() {
        Log.d(TAG, "RemoteStreamManager initialized");
    }
    
    public static synchronized RemoteStreamManager getInstance() {
        if (instance == null) {
            instance = new RemoteStreamManager();
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
     * Start recording session.
     * Called when RecordingService starts recording.
     * Fragments are received via FragmentedMp4MuxerWrapper callbacks.
     */
    public void startRecording(@NonNull File recordingFile) {
        Log.i(TAG, "🎬 START RECORDING called: enabled=" + streamingEnabled + ", file=" + recordingFile.getName());
        
        if (!streamingEnabled) {
            Log.w(TAG, "❌ Streaming NOT enabled - ignoring startRecording call");
            return;
        }
        
        Log.i(TAG, "✅ Streaming ENABLED - ready to receive fragments via callbacks");
        
        bufferLock.writeLock().lock();
        try {
            activeRecordingFile = recordingFile;
            clearBuffer(); // Reset buffer for new recording
        } finally {
            bufferLock.writeLock().unlock();
        }
        
        Log.i(TAG, "Remote streaming ready (callback-based)");
    }
    
    /**
     * Stop recording session.
     * Called when recording stops.
     */
    public void stopRecording() {
        Log.i(TAG, "🛑 STOP RECORDING");
        
        bufferLock.writeLock().lock();
        try {
            activeRecordingFile = null;
            
        } finally {
            bufferLock.writeLock().unlock();
        }
    }
    
    /**
     * Get the active recording file (for fallback direct streaming).
     */
    @Nullable
    public File getActiveRecordingFile() {
        return activeRecordingFile;
    }
    
    /**
     * Called by FragmentedMp4MuxerWrapper when initialization segment is received.
     * @param initData ftyp + moov boxes
     */
    public void onInitializationSegment(byte[] initData) {
        Log.i(TAG, "🔔 onInitializationSegment CALLED - data size: " + (initData != null ? initData.length : "NULL"));
        bufferLock.writeLock().lock();
        try {
            this.initializationSegment = initData;
            Log.i(TAG, "📋 Initialization segment STORED (" + (initData.length / 1024) + " KB)");
        } finally {
            bufferLock.writeLock().unlock();
        }
    }
    
    /**
     * Called by FragmentedMp4MuxerWrapper when a fragment is complete.
     * @param sequenceNumber Fragment sequence number (1-based)
     * @param fragmentData Complete moof+mdat bytes
     */
    public void onFragmentComplete(int sequenceNumber, byte[] fragmentData) {
        if (!streamingEnabled) {
            return;
        }
        
        bufferLock.writeLock().lock();
        try {
            // Create fragment object
            FragmentData fragment = new FragmentData(sequenceNumber, fragmentData);
            
            // Add to circular buffer
            fragmentBuffer[bufferHead] = fragment;
            fragmentSequence = sequenceNumber;
            
            // Track oldest sequence in buffer (always maintain sliding window)
            oldestSequence = Math.max(1, sequenceNumber - BUFFER_SIZE + 1);
            
            // Advance head pointer (circular)
            bufferHead = (bufferHead + 1) % BUFFER_SIZE;
            
            Log.i(TAG, "🎬 Fragment #" + sequenceNumber + " buffered (" + 
                (fragmentData.length / 1024) + " KB) [" + getBufferedCount() + "/" + BUFFER_SIZE + " slots]");
            
        } finally {
            bufferLock.writeLock().unlock();
        }
    }
    
    /**
     * Get initialization segment for HLS #EXT-X-MAP.
     */
    @Nullable
    public byte[] getInitializationSegment() {
        bufferLock.readLock().lock();
        try {
            Log.d(TAG, "📥 getInitializationSegment called - returning: " + (initializationSegment != null ? (initializationSegment.length + " bytes") : "NULL"));
            return initializationSegment;
        } finally {
            bufferLock.readLock().unlock();
        }
    }
    
    /**
     * Get a specific fragment by sequence number.
     */
    @Nullable
    public FragmentData getFragment(int sequenceNumber) {
        bufferLock.readLock().lock();
        try {
            for (FragmentData fragment : fragmentBuffer) {
                if (fragment != null && fragment.sequenceNumber == sequenceNumber) {
                    return fragment;
                }
            }
            return null;
        } finally {
            bufferLock.readLock().unlock();
        }
    }
    
    /**
     * Get list of buffered fragments sorted by sequence number.
     */
    @NonNull
    public List<FragmentData> getBufferedFragments() {
        bufferLock.readLock().lock();
        try {
            List<FragmentData> fragments = new ArrayList<>();
            
            // Only include fragments in the current valid sequence range
            // This prevents serving stale fragments when the circular buffer wraps
            int validRangeStart = Math.max(1, fragmentSequence - BUFFER_SIZE + 1);
            
            for (FragmentData fragment : fragmentBuffer) {
                if (fragment != null && fragment.sequenceNumber >= validRangeStart && fragment.sequenceNumber <= fragmentSequence) {
                    fragments.add(fragment);
                }
            }
            
            // Sort by sequence number
            fragments.sort((a, b) -> Integer.compare(a.sequenceNumber, b.sequenceNumber));
            
            return fragments;
        } finally {
            bufferLock.readLock().unlock();
        }
    }
    
    /**
     * Get the latest fragment sequence number.
     */
    public int getLatestSequenceNumber() {
        bufferLock.readLock().lock();
        try {
            return fragmentSequence;
        } finally {
            bufferLock.readLock().unlock();
        }
    }
    
    /**
     * Get the oldest buffered sequence number.
     */
    public int getOldestSequenceNumber() {
        bufferLock.readLock().lock();
        try {
            return oldestSequence;
        } finally {
            bufferLock.readLock().unlock();
        }
    }
    
    /**
     * Get number of buffered fragments.
     */
    public int getBufferedCount() {
        int count = 0;
        for (FragmentData fragment : fragmentBuffer) {
            if (fragment != null) {
                count++;
            }
        }
        return count;
    }
    
    /**
     * Get status JSON for HTTP /status endpoint.
     */
    public String getStatusJson() {
        bufferLock.readLock().lock();
        try {
            int bufferedCount = getBufferedCount();
            long totalBytes = 0;
            for (FragmentData fragment : fragmentBuffer) {
                if (fragment != null) {
                    totalBytes += fragment.sizeBytes;
                }
            }
            
            return String.format(
                "{\"streaming\": %s, \"mode\": \"%s\", \"fragments_buffered\": %d, " +
                "\"buffer_size_mb\": %.2f, \"latest_sequence\": %d, \"oldest_sequence\": %d, " +
                "\"active_connections\": %d, \"has_init_segment\": %s}",
                streamingEnabled,
                streamingMode.toString().toLowerCase(),
                bufferedCount,
                totalBytes / (1024.0 * 1024.0),
                fragmentSequence,
                oldestSequence,
                activeConnections,
                (initializationSegment != null)
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
     * Clear all buffered fragments.
     */
    private void clearBuffer() {
        for (int i = 0; i < BUFFER_SIZE; i++) {
            fragmentBuffer[i] = null;
        }
        bufferHead = 0;
        fragmentSequence = 0;
        oldestSequence = 0;
        initializationSegment = null;
        Log.d(TAG, "Fragment buffer cleared");
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
