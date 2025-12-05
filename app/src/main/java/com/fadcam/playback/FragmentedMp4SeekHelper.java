package com.fadcam.playback;

import android.content.Context;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import android.util.Log;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Helper class that uses Android's native MediaExtractor to find sync sample
 * positions in fragmented MP4 files. This is needed because ExoPlayer's
 * FragmentedMp4Extractor cannot seek in fMP4 files without sidx boxes.
 * 
 * Android's MediaExtractor handles fMP4 correctly by scanning the file structure.
 * We use it to build a seek map that ExoPlayer can use.
 */
public class FragmentedMp4SeekHelper {
    private static final String TAG = "FMp4SeekHelper";
    
    /**
     * Represents a sync sample (keyframe) position in the media file.
     */
    public static class SyncPoint {
        public final long timeUs;  // Presentation time in microseconds
        public final long position; // Byte position in file (not used, for reference)
        public final boolean isKeyframe;
        
        public SyncPoint(long timeUs, long position, boolean isKeyframe) {
            this.timeUs = timeUs;
            this.position = position;
            this.isKeyframe = isKeyframe;
        }
        
        public long getTimeMs() {
            return timeUs / 1000;
        }
    }
    
    private final Context context;
    private MediaExtractor extractor;
    private boolean isInitialized = false;
    private int videoTrackIndex = -1;
    private long durationUs = -1;
    
    // Cached sync points for fast seeking
    private List<SyncPoint> syncPoints;
    
    public FragmentedMp4SeekHelper(Context context) {
        this.context = context.getApplicationContext();
    }
    
    /**
     * Initializes the helper with a media file URI.
     * This must be called before using other methods.
     *
     * @param uri The URI of the fragmented MP4 file.
     * @return true if initialization succeeded, false otherwise.
     */
    public boolean initialize(Uri uri) {
        release();
        
        try {
            extractor = new MediaExtractor();
            
            String scheme = uri.getScheme();
            if ("file".equals(scheme)) {
                extractor.setDataSource(uri.getPath());
            } else if ("content".equals(scheme)) {
                extractor.setDataSource(context, uri, null);
            } else {
                extractor.setDataSource(uri.toString());
            }
            
            // Find video track
            int numTracks = extractor.getTrackCount();
            for (int i = 0; i < numTracks; i++) {
                MediaFormat format = extractor.getTrackFormat(i);
                String mime = format.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("video/")) {
                    videoTrackIndex = i;
                    if (format.containsKey(MediaFormat.KEY_DURATION)) {
                        durationUs = format.getLong(MediaFormat.KEY_DURATION);
                    }
                    break;
                }
            }
            
            if (videoTrackIndex < 0) {
                Log.e(TAG, "No video track found in: " + uri);
                release();
                return false;
            }
            
            extractor.selectTrack(videoTrackIndex);
            isInitialized = true;
            
            Log.d(TAG, "Initialized for: " + uri.getLastPathSegment() + 
                       ", videoTrack=" + videoTrackIndex + 
                       ", duration=" + (durationUs / 1000) + "ms");
            
            return true;
        } catch (IOException e) {
            Log.e(TAG, "Failed to initialize MediaExtractor for: " + uri, e);
            release();
            return false;
        }
    }
    
    /**
     * Builds a list of sync points (keyframes) in the media file.
     * This scans the entire file which may take some time for large files.
     *
     * @return List of sync points, or null if failed.
     */
    public List<SyncPoint> buildSyncPointList() {
        if (!isInitialized || extractor == null) {
            Log.e(TAG, "Not initialized");
            return null;
        }
        
        if (syncPoints != null) {
            return syncPoints;
        }
        
        syncPoints = new ArrayList<>();
        
        try {
            // Seek to beginning
            extractor.seekTo(0, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
            
            long lastSyncTime = -1;
            int sampleCount = 0;
            
            while (true) {
                long sampleTime = extractor.getSampleTime();
                if (sampleTime < 0) {
                    break; // End of stream
                }
                
                int flags = extractor.getSampleFlags();
                boolean isKeyframe = (flags & MediaExtractor.SAMPLE_FLAG_SYNC) != 0;
                
                if (isKeyframe && sampleTime != lastSyncTime) {
                    syncPoints.add(new SyncPoint(sampleTime, 0, true));
                    lastSyncTime = sampleTime;
                }
                
                sampleCount++;
                if (!extractor.advance()) {
                    break;
                }
            }
            
            Log.d(TAG, "Built sync point list: " + syncPoints.size() + 
                       " keyframes from " + sampleCount + " samples");
            
            // Seek back to beginning after scanning
            extractor.seekTo(0, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
            
            return syncPoints;
        } catch (Exception e) {
            Log.e(TAG, "Failed to build sync point list", e);
            return null;
        }
    }
    
    /**
     * Finds the nearest sync point for a given time.
     *
     * @param timeUs Target time in microseconds.
     * @param mode SEEK_TO_PREVIOUS_SYNC, SEEK_TO_NEXT_SYNC, or SEEK_TO_CLOSEST_SYNC
     * @return The nearest sync point, or null if not found.
     */
    public SyncPoint findNearestSyncPoint(long timeUs, int mode) {
        if (!isInitialized || extractor == null) {
            return null;
        }
        
        try {
            extractor.seekTo(timeUs, mode);
            long resultTime = extractor.getSampleTime();
            
            if (resultTime >= 0) {
                return new SyncPoint(resultTime, 0, true);
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to find sync point at " + timeUs, e);
        }
        
        return null;
    }
    
    /**
     * Gets the actual position after seeking to a target time.
     * Uses Android's MediaExtractor which handles fMP4 correctly.
     *
     * @param targetTimeMs Target time in milliseconds.
     * @return Actual sync point time in milliseconds after seeking, or -1 if failed.
     */
    public long getSeekResultTimeMs(long targetTimeMs) {
        if (!isInitialized || extractor == null) {
            return -1;
        }
        
        try {
            long targetTimeUs = targetTimeMs * 1000;
            extractor.seekTo(targetTimeUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC);
            long resultTimeUs = extractor.getSampleTime();
            
            if (resultTimeUs >= 0) {
                long resultTimeMs = resultTimeUs / 1000;
                Log.d(TAG, "Seek target=" + targetTimeMs + "ms, result=" + resultTimeMs + "ms");
                return resultTimeMs;
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to seek to " + targetTimeMs + "ms", e);
        }
        
        return -1;
    }
    
    /**
     * Gets the duration of the video in milliseconds.
     *
     * @return Duration in milliseconds, or -1 if unknown.
     */
    public long getDurationMs() {
        if (durationUs > 0) {
            return durationUs / 1000;
        }
        return -1;
    }
    
    /**
     * Releases resources.
     */
    public void release() {
        isInitialized = false;
        videoTrackIndex = -1;
        durationUs = -1;
        syncPoints = null;
        
        if (extractor != null) {
            try {
                extractor.release();
            } catch (Exception e) {
                Log.w(TAG, "Error releasing MediaExtractor", e);
            }
            extractor = null;
        }
    }
}
