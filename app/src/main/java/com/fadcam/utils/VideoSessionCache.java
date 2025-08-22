// -------------- Fix Start (VideoSessionCache Utility)-----------
package com.fadcam.utils;

import android.util.Log;

import com.fadcam.ui.VideoItem;

import java.util.ArrayList;
import java.util.List;

/**
 * Shared session cache for video items to eliminate duplicate SAF directory scans
 * between HomeFragment and RecordsFragment. Provides thread-safe caching with
 * automatic expiration to ensure data freshness.
 */
public class VideoSessionCache {
    private static final String TAG = "VideoSessionCache";
    
    // Session-level cache shared across all fragments
    private static List<VideoItem> sSessionCachedVideos = null;
    private static long sSessionCacheTimestamp = 0;
    private static int sCachedVideoCount = 0; // For skeleton loading
    private static final long SESSION_CACHE_VALIDITY_MS = 30 * 1000; // 30 seconds
    
    /**
     * Checks if the current session cache is still valid.
     * @return true if cache exists and is within validity period
     */
    public static synchronized boolean isSessionCacheValid() {
        return sSessionCachedVideos != null && 
               (System.currentTimeMillis() - sSessionCacheTimestamp) < SESSION_CACHE_VALIDITY_MS;
    }
    
    /**
     * Gets a copy of the cached video items.
     * @return Copy of cached videos, or empty list if cache is invalid
     */
    public static synchronized List<VideoItem> getSessionCachedVideos() {
        if (!isSessionCacheValid()) {
            return new ArrayList<>();
        }
        return new ArrayList<>(sSessionCachedVideos);
    }
    
    /**
     * Updates the session cache with new video data.
     * @param videos List of video items to cache
     */
    public static synchronized void updateSessionCache(List<VideoItem> videos) {
        sSessionCachedVideos = new ArrayList<>(videos);
        sSessionCacheTimestamp = System.currentTimeMillis();
        Log.d(TAG, "Session cache updated with " + videos.size() + " videos");
    }
    
    /**
     * Clears the session cache, forcing next access to reload from storage.
     */
    public static synchronized void clearSessionCache() {
        sSessionCachedVideos = null;
        sSessionCacheTimestamp = 0;
        Log.d(TAG, "Session cache cleared");
    }
    
    /**
     * Gets the age of the current cache in milliseconds.
     * @return Cache age in ms, or -1 if cache is invalid
     */
    public static synchronized long getCacheAgeMs() {
        if (sSessionCachedVideos == null) {
            return -1;
        }
        return System.currentTimeMillis() - sSessionCacheTimestamp;
    }
    
    /**
     * Gets the cached video count for immediate skeleton display.
     * @return Last known video count, or 0 if no count cached
     */
    public static synchronized int getCachedVideoCount() {
        return sCachedVideoCount;
    }
    
    /**
     * Sets the cached video count for skeleton loading.
     * @param count Number of videos to cache for skeleton display
     */
    public static synchronized void setCachedVideoCount(int count) {
        sCachedVideoCount = count;
        Log.d(TAG, "Cached video count set to: " + count);
    }
}
// -------------- Fix Ended (VideoSessionCache Utility)-----------
