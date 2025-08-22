package com.fadcam.utils;

import android.content.Context;
import android.os.Environment;
import android.os.StatFs;
import android.util.Log;
import androidx.documentfile.provider.DocumentFile;
import com.fadcam.SharedPreferencesManager;
import com.fadcam.Utils;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Utility class for caching storage information to improve performance
 * and provide instant display in the home storage card.
 */
public class StorageInfoCache {
    private static final String TAG = "StorageInfoCache";
    private static final long CACHE_VALIDITY_MS = 30_000; // 30 seconds
    
    // Cache storage data
    private static long lastCacheTime = 0;
    private static long cachedAvailableBytes = -1;
    private static long cachedTotalBytes = -1;
    private static boolean cachedUsingCustomStorage = false;
    private static boolean cachedCustomIsOnPrimary = true;
    
    // Thread-safe access
    private static final Object cacheLock = new Object();
    
    public static class StorageInfo {
        public final long availableBytes;
        public final long totalBytes;
        public final boolean usingCustomStorage;
        public final boolean customIsOnPrimary;
        
        public StorageInfo(long availableBytes, long totalBytes, 
                          boolean usingCustomStorage, boolean customIsOnPrimary) {
            this.availableBytes = availableBytes;
            this.totalBytes = totalBytes;
            this.usingCustomStorage = usingCustomStorage;
            this.customIsOnPrimary = customIsOnPrimary;
        }
        
        public double getAvailableGB() {
            return availableBytes / (1024.0 * 1024.0 * 1024.0);
        }
        
        public double getTotalGB() {
            return totalBytes / (1024.0 * 1024.0 * 1024.0);
        }
    }
    
    /**
     * Get cached storage info if valid, otherwise null
     */
    public static StorageInfo getCachedStorageInfo() {
        synchronized (cacheLock) {
            if (isCacheValid()) {
                return new StorageInfo(cachedAvailableBytes, cachedTotalBytes, 
                                     cachedUsingCustomStorage, cachedCustomIsOnPrimary);
            }
            return null;
        }
    }
    
    /**
     * Calculate and cache storage information
     */
    public static StorageInfo calculateAndCacheStorageInfo(Context context, 
                                                          SharedPreferencesManager prefsManager) {
        Log.d(TAG, "Calculating fresh storage information");
        
        // Default to internal external storage stats
        StatFs stat = new StatFs(Environment.getExternalStorageDirectory().getPath());
        long bytesAvailable = stat.getAvailableBytes();
        long bytesTotal = stat.getTotalBytes();
        
        // Check if using custom storage
        String storageMode = prefsManager.getStorageMode();
        String customUriString = prefsManager.getCustomStorageUri();
        boolean usingCustomStorage = SharedPreferencesManager.STORAGE_MODE_CUSTOM.equals(storageMode) 
                                   && customUriString != null;
        boolean customIsOnPrimary = true;
        
        if (usingCustomStorage) {
            try {
                android.net.Uri treeUri = android.net.Uri.parse(customUriString);
                
                // Heuristics for determining if custom storage is on primary volume
                try {
                    String docId = android.provider.DocumentsContract.getTreeDocumentId(treeUri);
                    if (docId != null) {
                        if (docId.startsWith("primary:")) {
                            customIsOnPrimary = true;
                        } else if (docId.startsWith("raw:")) {
                            String rawPath = docId.substring("raw:".length());
                            customIsOnPrimary = rawPath.startsWith(
                                Environment.getExternalStorageDirectory().getAbsolutePath());
                        } else if (docId.contains(":")) {
                            String volumeId = docId.split(":", 2)[0];
                            customIsOnPrimary = "primary".equalsIgnoreCase(volumeId);
                        }
                    }
                } catch (Exception ignore) {
                    // best-effort only
                }
                
                // Try to get actual storage stats for custom storage
                if (hasSafPermission(context, treeUri)) {
                    java.io.File probe = Utils.getFileFromSafUriIfPossible(context, treeUri);
                    if (probe != null && probe.exists()) {
                        StatFs customStat = new StatFs(probe.getAbsolutePath());
                        bytesAvailable = customStat.getAvailableBytes();
                        bytesTotal = customStat.getTotalBytes();
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Error probing custom storage", e);
            }
        }
        
        // Cache the results
        synchronized (cacheLock) {
            cachedAvailableBytes = bytesAvailable;
            cachedTotalBytes = bytesTotal;
            cachedUsingCustomStorage = usingCustomStorage;
            cachedCustomIsOnPrimary = customIsOnPrimary;
            lastCacheTime = System.currentTimeMillis();
            
            Log.d(TAG, "Storage info cached - Available: " + (bytesAvailable / (1024.0 * 1024.0 * 1024.0)) 
                      + " GB, Total: " + (bytesTotal / (1024.0 * 1024.0 * 1024.0)) + " GB");
        }
        
        return new StorageInfo(bytesAvailable, bytesTotal, usingCustomStorage, customIsOnPrimary);
    }
    
    /**
     * Check if current cache is still valid
     */
    public static boolean isCacheValid() {
        synchronized (cacheLock) {
            return (System.currentTimeMillis() - lastCacheTime) < CACHE_VALIDITY_MS 
                   && cachedAvailableBytes >= 0;
        }
    }
    
    /**
     * Clear the cache to force recalculation
     */
    public static void clearCache() {
        synchronized (cacheLock) {
            lastCacheTime = 0;
            cachedAvailableBytes = -1;
            cachedTotalBytes = -1;
            Log.d(TAG, "Storage info cache cleared");
        }
    }
    
    /**
     * Check if we have SAF permission for the given URI
     */
    private static boolean hasSafPermission(Context context, android.net.Uri treeUri) {
        try {
            for (android.content.UriPermission permission : context.getContentResolver().getPersistedUriPermissions()) {
                if (permission.getUri().equals(treeUri) && permission.isWritePermission()) {
                    return true;
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error checking SAF permissions", e);
        }
        return false;
    }
}
