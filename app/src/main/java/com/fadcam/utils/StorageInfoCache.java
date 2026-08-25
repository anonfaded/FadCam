package com.fadcam.utils;

import com.fadcam.FLog;
import android.content.Context;
import android.os.Build;
import android.os.Environment;
import android.os.StatFs;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import androidx.documentfile.provider.DocumentFile;
import com.fadcam.SharedPreferencesManager;

import java.io.File;
import java.util.List;

/**
 * Utility class for caching storage information to improve performance
 * and provide instant display in the home storage card.
 */
public class StorageInfoCache {
    private static final String TAG = "StorageInfoCache";
    private static final long CACHE_VALIDITY_MS = 30_000;

    private static long lastCacheTime = 0;
    private static long cachedAvailableBytes = -1;
    private static long cachedTotalBytes = -1;
    private static boolean cachedUsingCustomStorage = false;
    private static boolean cachedCustomIsOnPrimary = true;

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

    public static StorageInfo getCachedStorageInfo() {
        synchronized (cacheLock) {
            if (isCacheValid()) {
                return new StorageInfo(cachedAvailableBytes, cachedTotalBytes,
                        cachedUsingCustomStorage, cachedCustomIsOnPrimary);
            }
            return null;
        }
    }

    public static StorageInfo calculateAndCacheStorageInfo(Context context,
                                                          SharedPreferencesManager prefsManager) {
        StatFs stat = new StatFs(Environment.getExternalStorageDirectory().getPath());
        long bytesAvailable = stat.getAvailableBytes();
        long bytesTotal = stat.getTotalBytes();

        String storageMode = prefsManager.getStorageMode();
        String customUriString = prefsManager.getCustomStorageUri();
        boolean usingCustomStorage = SharedPreferencesManager.STORAGE_MODE_CUSTOM.equals(storageMode)
                && customUriString != null;
        boolean customIsOnPrimary = true;

        if (usingCustomStorage) {
            try {
                android.net.Uri treeUri = android.net.Uri.parse(customUriString);
                String docId = null;
                try {
                    docId = android.provider.DocumentsContract.getTreeDocumentId(treeUri);
                } catch (Exception ignored) {
                }

                customIsOnPrimary = isPrimaryDocumentId(docId);

                File customPath = resolveStoragePath(context, docId);
                if (customPath != null && customPath.exists()) {
                    StatFs customStat = new StatFs(customPath.getAbsolutePath());
                    bytesAvailable = customStat.getAvailableBytes();
                    bytesTotal = customStat.getTotalBytes();
                } else if (!hasSafPermission(context, treeUri)) {
                    // A stale custom URI must never be reported as the active
                    // storage destination after its persisted grant disappears.
                    usingCustomStorage = false;
                    customIsOnPrimary = true;
                } else {
                    DocumentFile tree = DocumentFile.fromTreeUri(context, treeUri);
                    if (tree == null || !tree.exists() || !tree.canWrite()) {
                        usingCustomStorage = false;
                        customIsOnPrimary = true;
                    }
                }
            } catch (Exception e) {
                FLog.e(TAG, "Error probing custom storage", e);
                usingCustomStorage = false;
                customIsOnPrimary = true;
            }
        }

        synchronized (cacheLock) {
            cachedAvailableBytes = bytesAvailable;
            cachedTotalBytes = bytesTotal;
            cachedUsingCustomStorage = usingCustomStorage;
            cachedCustomIsOnPrimary = customIsOnPrimary;
            lastCacheTime = System.currentTimeMillis();

            FLog.d(TAG, "Storage info cached - Available: "
                    + (bytesAvailable / (1024.0 * 1024.0 * 1024.0))
                    + " GB, Total: "
                    + (bytesTotal / (1024.0 * 1024.0 * 1024.0)) + " GB");
        }

        return new StorageInfo(bytesAvailable, bytesTotal, usingCustomStorage, customIsOnPrimary);
    }

    public static boolean isCacheValid() {
        synchronized (cacheLock) {
            return (System.currentTimeMillis() - lastCacheTime) < CACHE_VALIDITY_MS
                    && cachedAvailableBytes >= 0;
        }
    }

    public static void clearCache() {
        synchronized (cacheLock) {
            lastCacheTime = 0;
            cachedAvailableBytes = -1;
            cachedTotalBytes = -1;
        }
    }

    private static boolean hasSafPermission(Context context, android.net.Uri treeUri) {
        try {
            for (android.content.UriPermission permission
                    : context.getContentResolver().getPersistedUriPermissions()) {
                if (permission.getUri().equals(treeUri) && permission.isWritePermission()) {
                    return true;
                }
            }
        } catch (Exception e) {
            FLog.e(TAG, "Error checking SAF permissions", e);
        }
        return false;
    }

    private static boolean isPrimaryDocumentId(String docId) {
        return docId != null && docId.toLowerCase(java.util.Locale.ROOT).startsWith("primary:");
    }

    /**
     * Resolve a SAF tree document ID to the backing volume when Android exposes
     * the volume directory. This makes the home storage card follow an
     * SD/USB destination instead of continuing to show phone capacity.
     */
    private static File resolveStoragePath(Context context, String docId) {
        if (docId == null || !docId.contains(":")) return null;
        String[] parts = docId.split(":", 2);
        if (parts.length < 2) return null;
        String volumeId = parts[0];
        String relative = parts[1];

        if ("primary".equalsIgnoreCase(volumeId)) {
            File root = Environment.getExternalStorageDirectory();
            return relative.isEmpty() ? root : new File(root, relative);
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            StorageManager manager = (StorageManager) context.getSystemService(Context.STORAGE_SERVICE);
            if (manager != null) {
                try {
                    List<StorageVolume> volumes = manager.getStorageVolumes();
                    for (StorageVolume volume : volumes) {
                        if (volume == null) continue;
                        String uuid = volume.getUuid();
                        if (uuid != null && uuid.equalsIgnoreCase(volumeId)) {
                            File root = volume.getDirectory();
                            if (root != null) {
                                return relative.isEmpty() ? root : new File(root, relative);
                            }
                        }
                    }
                } catch (Exception e) {
                    FLog.w(TAG, "Unable to resolve StorageVolume directory", e);
                }
            }
        }

        File[] externalDirs = context.getExternalFilesDirs(null);
        if (externalDirs == null) return null;
        for (File dir : externalDirs) {
            if (dir == null) continue;
            String path = dir.getAbsolutePath();
            String marker = "/storage/" + volumeId + "/";
            if (path.contains(marker)) {
                File root = new File("/storage/" + volumeId);
                return relative.isEmpty() ? root : new File(root, relative);
            }
        }
        return null;
    }
}
