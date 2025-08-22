package com.fadcam.utils;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

/**
 * Utility class for caching storage information to avoid recalculating on every app launch
 */
public class StorageCache {
    private static final String TAG = "StorageCache";
    private static final String PREFS_NAME = "storage_cache";
    private static final String KEY_TOTAL_BYTES = "total_bytes";
    private static final String KEY_AVAILABLE_BYTES = "available_bytes";
    private static final String KEY_LAST_UPDATE = "last_update";
    private static final String KEY_VIDEO_COUNT = "video_count";
    private static final String KEY_VIDEOS_USED_SIZE = "videos_used_size";
    
    // Cache validity: 5 minutes for storage stats, longer for video stats since they change less frequently
    private static final long STORAGE_CACHE_VALIDITY_MS = 5 * 60 * 1000; // 5 minutes
    private static final long VIDEO_CACHE_VALIDITY_MS = 30 * 60 * 1000; // 30 minutes
    
    /**
     * Caches storage information
     */
    public static void cacheStorageInfo(Context context, long totalBytes, long availableBytes) {
        try {
            SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            prefs.edit()
                .putLong(KEY_TOTAL_BYTES, totalBytes)
                .putLong(KEY_AVAILABLE_BYTES, availableBytes)
                .putLong(KEY_LAST_UPDATE, System.currentTimeMillis())
                .apply();
            Log.d(TAG, "Storage info cached: total=" + totalBytes + ", available=" + availableBytes);
        } catch (Exception e) {
            Log.e(TAG, "Error caching storage info", e);
        }
    }
    
    /**
     * Caches video usage information
     */
    public static void cacheVideoUsageInfo(Context context, int videoCount, long usedSizeBytes) {
        try {
            SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            prefs.edit()
                .putInt(KEY_VIDEO_COUNT, videoCount)
                .putLong(KEY_VIDEOS_USED_SIZE, usedSizeBytes)
                .apply();
            Log.d(TAG, "Video usage info cached: count=" + videoCount + ", usedSize=" + usedSizeBytes);
        } catch (Exception e) {
            Log.e(TAG, "Error caching video usage info", e);
        }
    }
    
    /**
     * Gets cached storage information if valid
     * @return array [totalBytes, availableBytes] or null if cache invalid/missing
     */
    public static long[] getCachedStorageInfo(Context context) {
        try {
            SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            long lastUpdate = prefs.getLong(KEY_LAST_UPDATE, 0);
            
            if (System.currentTimeMillis() - lastUpdate < STORAGE_CACHE_VALIDITY_MS) {
                long totalBytes = prefs.getLong(KEY_TOTAL_BYTES, 0);
                long availableBytes = prefs.getLong(KEY_AVAILABLE_BYTES, 0);
                
                if (totalBytes > 0 && availableBytes > 0) {
                    Log.d(TAG, "Using cached storage info: total=" + totalBytes + ", available=" + availableBytes);
                    return new long[]{totalBytes, availableBytes};
                }
            } else {
                Log.d(TAG, "Storage cache expired, age=" + (System.currentTimeMillis() - lastUpdate) + "ms");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error getting cached storage info", e);
        }
        return null;
    }
    
    /**
     * Gets cached video usage information if valid
     * @return array [videoCount, usedSizeBytes] or null if cache invalid/missing
     */
    public static long[] getCachedVideoUsageInfo(Context context) {
        try {
            SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            long lastUpdate = prefs.getLong(KEY_LAST_UPDATE, 0);
            
            // Use video session cache timestamp if available, otherwise use storage cache timestamp
            long videoSessionAge = com.fadcam.utils.VideoSessionCache.getCacheAgeMs();
            if (videoSessionAge < VIDEO_CACHE_VALIDITY_MS) {
                int videoCount = prefs.getInt(KEY_VIDEO_COUNT, -1);
                long usedSize = prefs.getLong(KEY_VIDEOS_USED_SIZE, 0);
                
                if (videoCount >= 0 && usedSize >= 0) {
                    Log.d(TAG, "Using cached video usage info: count=" + videoCount + ", usedSize=" + usedSize);
                    return new long[]{videoCount, usedSize};
                }
            } else {
                Log.d(TAG, "Video usage cache expired or unavailable");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error getting cached video usage info", e);
        }
        return null;
    }
    
    /**
     * Checks if storage cache is valid
     */
    public static boolean isStorageCacheValid(Context context) {
        return getCachedStorageInfo(context) != null;
    }
    
    /**
     * Clears all cached storage information
     */
    public static void clearCache(Context context) {
        try {
            SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            prefs.edit().clear().apply();
            Log.d(TAG, "Storage cache cleared");
        } catch (Exception e) {
            Log.e(TAG, "Error clearing storage cache", e);
        }
    }
}
