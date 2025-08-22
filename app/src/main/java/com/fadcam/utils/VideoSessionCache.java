// -------------- Fix Start (VideoSessionCache Utility)-----------
package com.fadcam.utils;

import android.content.Context;
import android.util.Log;

import com.fadcam.ui.VideoItem;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
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
    
    // Disk cache file name
    private static final String CACHE_FILE_NAME = "video_cache.dat";
    
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
        if (sCacheInitialized) {
            Log.v(TAG, "Cache already initialized, skipping");
            return;
        }
        
        try {
            // Load persistent cache metadata
            sCachedVideoCount = sharedPrefs.sharedPreferences.getInt(PREF_CACHE_VIDEO_COUNT, 0);
            sSessionCacheTimestamp = sharedPrefs.sharedPreferences.getLong(PREF_CACHE_TIMESTAMP, 0);
            sForceRefreshOnNextAccess = sharedPrefs.sharedPreferences.getBoolean(PREF_CACHE_INVALIDATED, false);
            
            // Note: Disk cache loading will be done when first accessed via getSessionCachedVideos
            // to avoid needing context in initialization
            
            Log.d(TAG, "CACHE INIT: Loaded from persistent storage - count=" + sCachedVideoCount + 
                      ", timestamp=" + sSessionCacheTimestamp + ", invalidated=" + sForceRefreshOnNextAccess +
                      ", sessionVideos=" + (sSessionCachedVideos != null ? sSessionCachedVideos.size() : "null"));
            
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
        boolean valid = sSessionCachedVideos != null && !sForceRefreshOnNextAccess;
        Log.v(TAG, "CACHE CHECK: isSessionCacheValid=" + valid + 
                   " (videos=" + (sSessionCachedVideos != null ? sSessionCachedVideos.size() : "null") + 
                   ", forceRefresh=" + sForceRefreshOnNextAccess + ")");
        return valid;
    }
    
    /**
     * Checks if we have any cached data (in-memory or persistent)
     * @return true if we have cached video count > 0 and not invalidated
     */
    public static synchronized boolean hasCachedData(com.fadcam.SharedPreferencesManager sharedPrefs) {
        initializeCacheIfNeeded(sharedPrefs);
        boolean hasData = sCachedVideoCount > 0 && !sForceRefreshOnNextAccess;
        Log.v(TAG, "CACHE CHECK: hasCachedData=" + hasData + 
                   " (count=" + sCachedVideoCount + ", forceRefresh=" + sForceRefreshOnNextAccess + ")");
        return hasData;
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
     * Gets a copy of the cached video items, loading from disk if needed.
     * @return Copy of cached videos, or empty list if cache is invalid
     */
    public static synchronized List<VideoItem> getSessionCachedVideos() {
        // Try to load from disk if session cache is empty but we have cached count
        if (sSessionCachedVideos == null && !sForceRefreshOnNextAccess && sCachedVideoCount > 0) {
            Log.d(TAG, "Session cache empty but have cached count, attempting disk load");
            // We'll need context for this, so return empty for now and let caller handle
            return new ArrayList<>();
        }
        
        if (!isSessionCacheValid()) {
            Log.d(TAG, "Session cache invalid or needs refresh");
            return new ArrayList<>();
        }
        Log.d(TAG, "Using cached videos: " + sSessionCachedVideos.size() + " items");
        return new ArrayList<>(sSessionCachedVideos);
    }
    
    /**
     * Gets cached videos with context for disk loading
     */
    public static synchronized List<VideoItem> getSessionCachedVideos(Context context) {
        // Try to load from disk if session cache is empty but we have cached count
        if (sSessionCachedVideos == null && !sForceRefreshOnNextAccess && sCachedVideoCount > 0) {
            Log.d(TAG, "Session cache empty but have cached count, loading from disk");
            List<VideoItem> diskCache = loadCacheFromDisk(context);
            if (!diskCache.isEmpty()) {
                sSessionCachedVideos = diskCache;
                Log.d(TAG, "DISK CACHE HIT: Loaded " + diskCache.size() + " videos from disk");
            }
        }
        
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
     * Updates the session cache with new video data and saves to disk.
     * @param videos List of video items to cache
     * @param context Context for disk operations
     */
    public static synchronized void updateSessionCache(List<VideoItem> videos, Context context) {
        sSessionCachedVideos = new ArrayList<>(videos);
        sSessionCacheTimestamp = System.currentTimeMillis();
        sForceRefreshOnNextAccess = false; // Reset invalidation flag
        Log.d(TAG, "Session cache updated with " + videos.size() + " videos");
        
        // Save to disk asynchronously for persistence across app restarts
        saveCacheToDisk(videos, context);
    }
    
    /**
     * Saves video cache to disk for persistence across app restarts
     */
    private static void saveCacheToDisk(List<VideoItem> videos, Context context) {
        try {
            // Use a background thread to avoid blocking UI
            new Thread(() -> {
                try {
                    File cacheFile = new File(context.getCacheDir(), CACHE_FILE_NAME);
                    
                    // Create a serializable list (VideoItem needs to be Serializable)
                    List<SerializableVideoItem> serializableVideos = new ArrayList<>();
                    for (VideoItem video : videos) {
                        if (video != null && video.uri != null) {
                            serializableVideos.add(new SerializableVideoItem(video));
                        }
                    }
                    
                    try (FileOutputStream fos = new FileOutputStream(cacheFile);
                         ObjectOutputStream oos = new ObjectOutputStream(fos)) {
                        oos.writeObject(serializableVideos);
                        oos.flush();
                        Log.d(TAG, "Successfully saved " + serializableVideos.size() + " videos to disk cache");
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error saving cache to disk", e);
                }
            }).start();
        } catch (Exception e) {
            Log.e(TAG, "Error starting cache save thread", e);
        }
    }
    
    /**
     * Loads video cache from disk
     */
    @SuppressWarnings("unchecked")
    private static List<VideoItem> loadCacheFromDisk(Context context) {
        try {
            File cacheFile = new File(context.getCacheDir(), CACHE_FILE_NAME);
            if (!cacheFile.exists()) {
                Log.d(TAG, "No disk cache file found");
                return new ArrayList<>();
            }
            
            try (FileInputStream fis = new FileInputStream(cacheFile);
                 ObjectInputStream ois = new ObjectInputStream(fis)) {
                
                List<SerializableVideoItem> serializableVideos = (List<SerializableVideoItem>) ois.readObject();
                List<VideoItem> videos = new ArrayList<>();
                
                for (SerializableVideoItem item : serializableVideos) {
                    videos.add(item.toVideoItem());
                }
                
                Log.d(TAG, "Successfully loaded " + videos.size() + " videos from disk cache");
                return videos;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error loading cache from disk", e);
            return new ArrayList<>();
        }
    }
    
    /**
     * Serializable wrapper for VideoItem to enable disk caching
     */
    private static class SerializableVideoItem implements java.io.Serializable {
        private static final long serialVersionUID = 1L;
        
        public final String uriString;
        public final String displayName;
        public final long size;
        public final long lastModified;
        public final boolean isTemporary;
        public final boolean isNew;
        
        public SerializableVideoItem(VideoItem videoItem) {
            this.uriString = videoItem.uri.toString();
            this.displayName = videoItem.displayName;
            this.size = videoItem.size;
            this.lastModified = videoItem.lastModified;
            this.isTemporary = videoItem.isTemporary;
            this.isNew = videoItem.isNew;
        }
        
        public VideoItem toVideoItem() {
            VideoItem item = new VideoItem(
                android.net.Uri.parse(uriString),
                displayName,
                size,
                lastModified
            );
            item.isTemporary = isTemporary;
            item.isNew = isNew;
            return item;
        }
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
