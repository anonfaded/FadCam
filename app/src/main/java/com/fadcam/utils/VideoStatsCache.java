package com.fadcam.utils;

import android.content.Context;
import android.util.Log;
import com.fadcam.SharedPreferencesManager;

/**
 * Intelligent caching system for video statistics (count, total size)
 * that provides instant display and only updates when videos actually change.
 */
public class VideoStatsCache {
    private static final String TAG = "VideoStatsCache";
    
    // Cache keys
    private static final String PREF_STATS_VIDEO_COUNT = "cached_stats_video_count";
    private static final String PREF_STATS_TOTAL_SIZE_MB = "cached_stats_total_size_mb";
    private static final String PREF_STATS_LAST_UPDATE = "cached_stats_last_update";
    
    // Thread-safe access
    private static final Object cacheLock = new Object();
    
    public static class VideoStats {
        public final int videoCount;
        public final long totalSizeMB;
        public final long lastUpdateTime;
        
        public VideoStats(int videoCount, long totalSizeMB, long lastUpdateTime) {
            this.videoCount = videoCount;
            this.totalSizeMB = totalSizeMB;
            this.lastUpdateTime = lastUpdateTime;
        }
        
        public boolean isValid() {
            return videoCount >= 0 && totalSizeMB >= 0;
        }
    }
    
    /**
     * Gets cached video statistics for instant display
     * @return Cached stats or null if no valid cache exists
     */
    public static VideoStats getCachedStats(SharedPreferencesManager sharedPrefs) {
        synchronized (cacheLock) {
            try {
                int videoCount = sharedPrefs.sharedPreferences.getInt(PREF_STATS_VIDEO_COUNT, -1);
                long totalSizeMB = sharedPrefs.sharedPreferences.getLong(PREF_STATS_TOTAL_SIZE_MB, -1L);
                long lastUpdate = sharedPrefs.sharedPreferences.getLong(PREF_STATS_LAST_UPDATE, 0L);
                
                if (videoCount >= 0 && totalSizeMB >= 0) {
                    Log.d(TAG, "Retrieved cached stats: " + videoCount + " videos, " + totalSizeMB + "MB");
                    return new VideoStats(videoCount, totalSizeMB, lastUpdate);
                }
                
                Log.d(TAG, "No valid cached stats found");
                return null;
            } catch (Exception e) {
                Log.e(TAG, "Error retrieving cached stats", e);
                return null;
            }
        }
    }
    
    /**
     * Updates cached statistics (call when videos are added/removed)
     * @param videoCount Current number of videos
     * @param totalSizeMB Total size in MB
     */
    public static void updateStats(SharedPreferencesManager sharedPrefs, int videoCount, long totalSizeMB) {
        synchronized (cacheLock) {
            try {
                long currentTime = System.currentTimeMillis();
                
                sharedPrefs.sharedPreferences.edit()
                    .putInt(PREF_STATS_VIDEO_COUNT, videoCount)
                    .putLong(PREF_STATS_TOTAL_SIZE_MB, totalSizeMB)
                    .putLong(PREF_STATS_LAST_UPDATE, currentTime)
                    .apply();
                
                Log.d(TAG, "Updated cached stats: " + videoCount + " videos, " + totalSizeMB + "MB");
            } catch (Exception e) {
                Log.e(TAG, "Error updating cached stats", e);
            }
        }
    }
    
    /**
     * Invalidates cached statistics (call when manual refresh or uncertain state)
     */
    public static void invalidateStats(SharedPreferencesManager sharedPrefs) {
        synchronized (cacheLock) {
            try {
                sharedPrefs.sharedPreferences.edit()
                    .remove(PREF_STATS_VIDEO_COUNT)
                    .remove(PREF_STATS_TOTAL_SIZE_MB)
                    .remove(PREF_STATS_LAST_UPDATE)
                    .apply();
                
                Log.d(TAG, "Invalidated cached stats");
            } catch (Exception e) {
                Log.e(TAG, "Error invalidating cached stats", e);
            }
        }
    }
    
    /**
     * Quick check if stats cache exists
     */
    public static boolean hasValidCache(SharedPreferencesManager sharedPrefs) {
        return getCachedStats(sharedPrefs) != null;
    }
}
