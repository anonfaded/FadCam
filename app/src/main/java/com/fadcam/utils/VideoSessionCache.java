// -------------- Fix Start (VideoSessionCache Utility)-----------
package com.fadcam.utils;

import android.util.Log;

import com.fadcam.ui.VideoItem;

import java.util.ArrayList;
import java.util.List;

/**
 * Intelligent session cache for video items that provides instant access and
 * persists across app launches. Only invalidates when videos actually change.
 * Optimized for maximum performance and scalability.
 */
public class VideoSessionCache {
    private static final String TAG = "VideoSessionCache";
    
    // Persistent cache keys
    private static final String PREF_CACHE_VIDEO_COUNT = "session_cache_video_count";
    private static final String PREF_CACHE_TIMESTAMP = "session_cache_timestamp";
    private static final String PREF_CACHE_INVALIDATED = "session_cache_invalidated";
    
    // Session-level cache shared across all fragments
    private static List<VideoItem> sSessionCachedVideos = null;
    private static long sSessionCacheTimestamp = 0;
    private static int sCachedVideoCount = 0; // For skeleton loading
    private static boolean sForceRefreshOnNextAccess = false; // Event-driven invalidation
    private static boolean sCacheInitialized = false;
    
    /**
     * Initialize cache from persistent storage on first access
     */
    private static synchronized void initializeCacheIfNeeded(com.fadcam.SharedPreferencesManager sharedPrefs) {
        if (sCacheInitialized) return;
        
        try {
            // Load persistent cache metadata
            sCachedVideoCount = sharedPrefs.sharedPreferences.getInt(PREF_CACHE_VIDEO_COUNT, 0);
            sSessionCacheTimestamp = sharedPrefs.sharedPreferences.getLong(PREF_CACHE_TIMESTAMP, 0);
            sForceRefreshOnNextAccess = sharedPrefs.sharedPreferences.getBoolean(PREF_CACHE_INVALIDATED, false);
            
            Log.d(TAG, "Cache initialized from persistent storage: count=" + sCachedVideoCount + 
                      ", timestamp=" + sSessionCacheTimestamp + ", invalidated=" + sForceRefreshOnNextAccess);
            
            sCacheInitialized = true;
        } catch (Exception e) {
            Log.e(TAG, "Error initializing cache from persistent storage", e);
            sCacheInitialized = true; // Prevent retry loops
        }
    }
    
    /**
     * Checks if the current session cache is valid and available.
     * Cache remains valid indefinitely until explicitly invalidated.
     * @return true if cache exists and should be used
     */
    public static synchronized boolean isSessionCacheValid() {
        return sSessionCachedVideos != null && !sForceRefreshOnNextAccess;
    }
    
    /**
     * Checks if we have any cached data (in-memory or persistent)
     * @return true if we have cached video count > 0 and not invalidated
     */
    public static synchronized boolean hasCachedData(com.fadcam.SharedPreferencesManager sharedPrefs) {
        initializeCacheIfNeeded(sharedPrefs);
        return sCachedVideoCount > 0 && !sForceRefreshOnNextAccess;
    }
    
    /**
     * Checks if we have cached video count for skeleton loading
     * @return true if we have a cached count > 0
     */
    public static synchronized boolean hasCachedVideoCount(com.fadcam.SharedPreferencesManager sharedPrefs) {
        initializeCacheIfNeeded(sharedPrefs);
        return sCachedVideoCount > 0;
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
     * Invalidates cache with persistent storage update
     */
    public static synchronized void invalidateOnNextAccess(com.fadcam.SharedPreferencesManager sharedPrefs) {
        sForceRefreshOnNextAccess = true;
        
        // Persist invalidation state
        try {
            sharedPrefs.sharedPreferences.edit()
                .putBoolean(PREF_CACHE_INVALIDATED, true)
                .apply();
            
            Log.d(TAG, "Session cache invalidated and persisted");
        } catch (Exception e) {
            Log.e(TAG, "Error persisting cache invalidation", e);
        }
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
     * Gets cached video count with persistent storage fallback
     */
    public static synchronized int getCachedVideoCount(com.fadcam.SharedPreferencesManager sharedPrefs) {
        initializeCacheIfNeeded(sharedPrefs);
        return sCachedVideoCount;
    }
    
    /**
     * Sets the cached video count for skeleton loading with persistence.
     * @param count Number of videos to cache for skeleton display
     */
    public static synchronized void setCachedVideoCount(int count) {
        sCachedVideoCount = count;
        Log.d(TAG, "Cached video count set to: " + count);
    }
    
    /**
     * Sets cached video count with persistent storage
     */
    public static synchronized void setCachedVideoCount(int count, com.fadcam.SharedPreferencesManager sharedPrefs) {
        sCachedVideoCount = count;
        
        // Persist to storage for app restart
        try {
            sharedPrefs.sharedPreferences.edit()
                .putInt(PREF_CACHE_VIDEO_COUNT, count)
                .putLong(PREF_CACHE_TIMESTAMP, System.currentTimeMillis())
                .putBoolean(PREF_CACHE_INVALIDATED, false)
                .apply();
            
            Log.d(TAG, "Cached video count persisted: " + count);
        } catch (Exception e) {
            Log.e(TAG, "Error persisting cached video count", e);
        }
    }
}
// -------------- Fix Ended (VideoSessionCache Utility)-----------
