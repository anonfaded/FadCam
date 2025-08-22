// -------------- Fix Start (VideoSessionCache Utility)-----------
package com.fadcam.utils;

import android.util.Log;

import com.fadcam.ui.VideoItem;

import java.util.ArrayList;
import java.util.List;

/**
 * Intelligent session cache for video items that provides instant access and
 * only invalidates when videos actually change (record/delete/manual refresh).
 * No time-based expiry for maximum performance and scalability.
 */
public class VideoSessionCache {
    private static final String TAG = "VideoSessionCache";
    
    // Session-level cache shared across all fragments
    private static List<VideoItem> sSessionCachedVideos = null;
    private static long sSessionCacheTimestamp = 0;
    private static int sCachedVideoCount = 0; // For skeleton loading
    private static boolean sForceRefreshOnNextAccess = false; // Event-driven invalidation
    
    /**
     * Checks if the current session cache is valid and available.
     * Cache remains valid indefinitely until explicitly invalidated.
     * @return true if cache exists and should be used
     */
    public static synchronized boolean isSessionCacheValid() {
        return sSessionCachedVideos != null && !sForceRefreshOnNextAccess;
    }
    
    /**
     * Gets a copy of the cached video items.
     * @return Copy of cached videos, or empty list if cache is invalid
     */
    public static synchronized List<VideoItem> getSessionCachedVideos() {
        if (!isSessionCacheValid()) {
            Log.d(TAG, "Session cache invalid or needs refresh");
            return new ArrayList<>();
        }
        Log.d(TAG, "Using cached videos: " + sSessionCachedVideos.size() + " items");
        return new ArrayList<>(sSessionCachedVideos);
    }
    
    /**
     * Updates the session cache with new video data.
     * @param videos List of video items to cache
     */
    public static synchronized void updateSessionCache(List<VideoItem> videos) {
        sSessionCachedVideos = new ArrayList<>(videos);
        sSessionCacheTimestamp = System.currentTimeMillis();
        sForceRefreshOnNextAccess = false; // Reset invalidation flag
        Log.d(TAG, "Session cache updated with " + videos.size() + " videos");
    }
    
    /**
     * Clears the session cache, forcing next access to reload from storage.
     */
    public static synchronized void clearSessionCache() {
        sSessionCachedVideos = null;
        sSessionCacheTimestamp = 0;
        sForceRefreshOnNextAccess = false;
        Log.d(TAG, "Session cache cleared");
    }
    
    /**
     * Invalidates cache for next access (call when videos might have changed)
     * Used when recording, deleting, or manual refresh is triggered.
     */
    public static synchronized void invalidateOnNextAccess() {
        sForceRefreshOnNextAccess = true;
        Log.d(TAG, "Session cache marked for refresh on next access");
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
