package com.fadcam.data;

import android.content.Context;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.SharedPreferencesManager;
import com.fadcam.Utils;
import com.fadcam.data.dao.VideoIndexDao;
import com.fadcam.data.entity.VideoIndexEntity;
import com.fadcam.ui.VideoItem;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Repository that orchestrates the video index lifecycle:
 * <ol>
 *   <li>On first call: read DB → if non-empty, return instantly → delta scan in background</li>
 *   <li>On cold start (empty DB): full scan → bulk insert → return</li>
 *   <li>Background enrichment: compute duration + thumbnail for un-resolved items</li>
 * </ol>
 * <p>
 * All public methods are designed to be called from a background thread.
 * The fragment should call these from its own executor and post results to UI.
 */
public class VideoIndexRepository {

    private static final String TAG = "VideoIndexRepository";

    /** Singleton instance. */
    private static volatile VideoIndexRepository instance;

    private final Context appContext;
    private final VideoIndexDao dao;
    private final FastFileScanner scanner;
    private final ExecutorService enrichmentExecutor;
    private final AtomicBoolean isEnriching = new AtomicBoolean(false);

    /**
     * Flag-based invalidation: set from any thread, consumed by next getVideos() call.
     * Avoids calling dao.deleteAll() from the main thread.
     */
    private final AtomicBoolean indexInvalidated = new AtomicBoolean(false);

    /**
     * In-memory count cache, updated after each DB-modifying operation.
     * Safe to read from any thread (main thread included).
     */
    private final AtomicInteger cachedCount = new AtomicInteger(0);

    /**
     * In-memory duration cache: URI → durationMs.
     * Populated after getVideos/deltaScan/enrichment so that the adapter can call
     * getCachedDuration() from the main thread without touching the DB.
     */
    private final ConcurrentHashMap<String, Long> durationCache = new ConcurrentHashMap<>();

    /** Callback for metadata enrichment progress (duration resolved for an item). */
    public interface EnrichmentCallback {
        /**
         * Called on the enrichment thread when a single item's metadata is updated.
         *
         * @param uriString The URI of the item that was enriched
         * @param durationMs The computed duration in ms
         */
        void onItemEnriched(String uriString, long durationMs);
    }

    private VideoIndexRepository(@NonNull Context context) {
        this.appContext = context.getApplicationContext();
        this.dao = VideoIndexDatabase.getInstance(appContext).videoIndexDao();
        this.scanner = new FastFileScanner(appContext);
        this.enrichmentExecutor = Executors.newFixedThreadPool(2);
    }

    /**
     * Thread-safe singleton accessor.
     */
    public static VideoIndexRepository getInstance(@NonNull Context context) {
        if (instance == null) {
            synchronized (VideoIndexRepository.class) {
                if (instance == null) {
                    instance = new VideoIndexRepository(context);
                }
            }
        }
        return instance;
    }

    // ════════════════════════════════════════════════════════════════
    // Primary API — called from RecordsFragment background thread
    // ════════════════════════════════════════════════════════════════

    /**
     * Get all indexed videos. If the DB has data, returns instantly from DB.
     * If DB is empty (first launch or after wipe), performs a full scan.
     * <p>
     * Must be called from a background thread.
     *
     * @param prefs SharedPreferencesManager for storage config
     * @return List of VideoItem ready for the adapter
     */
    @NonNull
    public List<VideoItem> getVideos(@NonNull SharedPreferencesManager prefs) {
        long start = System.currentTimeMillis();

        // If index was invalidated (from main thread), force a delta scan to sync
        // with disk. This preserves enrichment data (durations, thumbnails) for
        // unchanged files, unlike the old deleteAll() approach.
        if (indexInvalidated.compareAndSet(true, false)) {
            Log.i(TAG, "Index invalidated — running delta scan to re-sync");
            return deltaScan(prefs);
        }

        int dbCount = dao.getCount();
        if (dbCount > 0) {
            // Fast path: read from DB
            List<VideoIndexEntity> entities = dao.getAllNewestFirst();
            List<VideoItem> items = entitiesToVideoItems(entities);
            updateInMemoryCaches(entities, items.size());
            long elapsed = System.currentTimeMillis() - start;
            Log.i(TAG, "DB fast path: " + items.size() + " items in " + elapsed + "ms");
            return items;
        }

        // Cold start: full scan + insert
        Log.i(TAG, "Cold start: no DB data, performing full scan");
        List<VideoIndexEntity> scanned = scanner.scanAll(prefs);
        if (!scanned.isEmpty()) {
            dao.insertOrReplaceAll(scanned);
            Log.i(TAG, "Inserted " + scanned.size() + " items into DB");
        }

        List<VideoItem> items = entitiesToVideoItems(scanned);
        updateInMemoryCaches(scanned, items.size());
        long elapsed = System.currentTimeMillis() - start;
        Log.i(TAG, "Cold start complete: " + items.size() + " items in " + elapsed + "ms");
        return items;
    }

    /**
     * Get count of indexed videos (for skeleton estimation).
     * Uses in-memory cache — safe to call from any thread including main.
     */
    public int getIndexedCount() {
        return cachedCount.get();
    }

    /**
     * Quick stats from Room DB: count + total size in bytes.
     * Uses direct DB queries (fast, sub-millisecond) WITHOUT loading full entities.
     * <p>
     * Must be called from a background thread (Room DB access).
     *
     * @return long[2] where [0] = count, [1] = totalSizeBytes. Returns {0, 0} on error.
     */
    @NonNull
    public long[] getQuickStats() {
        try {
            int count = dao.getCount();
            long totalSize = dao.getTotalSize();
            Log.d(TAG, "getQuickStats: count=" + count + ", totalSize=" + totalSize);
            // Also update in-memory cache while we're at it
            cachedCount.set(count);
            return new long[]{count, totalSize};
        } catch (Exception e) {
            Log.e(TAG, "getQuickStats: Error querying Room DB", e);
            return new long[]{0, 0};
        }
    }

    /**
     * Perform a delta scan: compare current files on disk with the DB index.
     * Adds new files, removes deleted files, updates changed files.
     * <p>
     * Must be called from a background thread.
     *
     * @param prefs SharedPreferencesManager for storage config
     * @return Updated list of VideoItem after delta sync
     */
    @NonNull
    public List<VideoItem> deltaScan(@NonNull SharedPreferencesManager prefs) {
        long start = System.currentTimeMillis();

        // Scan current files on disk
        List<VideoIndexEntity> currentFiles = scanner.scanAll(prefs);

        // Build lookup maps
        Map<String, VideoIndexEntity> diskMap = new HashMap<>(currentFiles.size());
        for (VideoIndexEntity entity : currentFiles) {
            diskMap.put(entity.uriString, entity);
        }

        // Get existing DB state (lightweight)
        List<VideoIndexDao.DeltaCheckRow> dbRows = dao.getDeltaCheckRows();
        Set<String> dbUris = new HashSet<>(dbRows.size());
        Map<String, VideoIndexDao.DeltaCheckRow> dbMap = new HashMap<>(dbRows.size());
        for (VideoIndexDao.DeltaCheckRow row : dbRows) {
            dbUris.add(row.uri_string);
            dbMap.put(row.uri_string, row);
        }

        // Determine what changed
        List<VideoIndexEntity> toInsert = new ArrayList<>();
        List<String> toDelete = new ArrayList<>();

        // New or changed files
        for (VideoIndexEntity diskEntity : currentFiles) {
            VideoIndexDao.DeltaCheckRow dbRow = dbMap.get(diskEntity.uriString);
            if (dbRow == null) {
                // New file — not in DB
                toInsert.add(diskEntity);
            } else if (diskEntity.lastModified != dbRow.last_modified || diskEntity.fileSize != dbRow.file_size) {
                // Changed file — re-index (will replace via REPLACE strategy)
                toInsert.add(diskEntity);
            }
            // Else: unchanged — skip
        }

        // Deleted files — in DB but not on disk
        for (String dbUri : dbUris) {
            if (!diskMap.containsKey(dbUri)) {
                toDelete.add(dbUri);
            }
        }

        // Apply changes
        if (!toDelete.isEmpty()) {
            dao.deleteByUris(toDelete);
            Log.i(TAG, "Delta: removed " + toDelete.size() + " deleted files from index");
        }
        if (!toInsert.isEmpty()) {
            dao.insertOrReplaceAll(toInsert);
            Log.i(TAG, "Delta: added/updated " + toInsert.size() + " files in index");
        }

        // Return fresh list from DB
        List<VideoIndexEntity> allEntities = dao.getAllNewestFirst();
        List<VideoItem> items = entitiesToVideoItems(allEntities);
        updateInMemoryCaches(allEntities, items.size());

        long elapsed = System.currentTimeMillis() - start;
        Log.i(TAG, "Delta scan complete: " + items.size() + " items, " +
                toInsert.size() + " added, " + toDelete.size() + " removed, " + elapsed + "ms");

        return items;
    }

    /**
     * Force a complete re-index: scan all files and sync with DB, preserving
     * existing enrichment data (durations, thumbnails) for unchanged files.
     * <p>
     * This is equivalent to a delta scan but starts from a full disk scan.
     *
     * @param prefs SharedPreferencesManager for storage config
     * @return Fresh list of VideoItem
     */
    @NonNull
    public List<VideoItem> forceFullReindex(@NonNull SharedPreferencesManager prefs) {
        long start = System.currentTimeMillis();

        // Save existing enrichment data before modifying the DB
        Map<String, long[]> enrichmentData = new HashMap<>();
        try {
            List<VideoIndexEntity> existing = dao.getAllNewestFirst();
            for (VideoIndexEntity e : existing) {
                if (e.durationResolved) {
                    enrichmentData.put(e.uriString, new long[]{e.durationMs, 1});
                }
            }
            Log.d(TAG, "Preserved enrichment data for " + enrichmentData.size() + " items");
        } catch (Exception e) {
            Log.w(TAG, "Failed to read existing enrichment data (non-fatal)", e);
        }

        // Wipe and re-scan
        dao.deleteAll();
        List<VideoIndexEntity> scanned = scanner.scanAll(prefs);

        // Apply preserved enrichment data to matching URIs
        if (!enrichmentData.isEmpty()) {
            for (VideoIndexEntity entity : scanned) {
                long[] saved = enrichmentData.get(entity.uriString);
                if (saved != null) {
                    entity.durationMs = saved[0];
                    entity.durationResolved = true;
                }
            }
        }

        if (!scanned.isEmpty()) {
            dao.insertOrReplaceAll(scanned);
        }
        List<VideoItem> items = entitiesToVideoItems(scanned);
        updateInMemoryCaches(scanned, items.size());
        long elapsed = System.currentTimeMillis() - start;
        Log.i(TAG, "Full reindex: " + items.size() + " items in " + elapsed + "ms"
                + " (preserved " + enrichmentData.size() + " durations)");
        return items;
    }

    // ════════════════════════════════════════════════════════════════
    // Background metadata enrichment (duration)
    // ════════════════════════════════════════════════════════════════

    /**
     * Start background enrichment of un-resolved durations.
     * Processes items that have durationResolved=false, computes duration,
     * and updates the DB. Calls back for each item resolved.
     * <p>
     * Safe to call multiple times — will no-op if already running.
     *
     * @param callback Optional callback for per-item updates (called on background thread)
     */
    public void startBackgroundEnrichment(@Nullable EnrichmentCallback callback) {
        if (!isEnriching.compareAndSet(false, true)) {
            Log.d(TAG, "Enrichment already running, skipping");
            return;
        }

        enrichmentExecutor.submit(() -> {
            try {
                List<VideoIndexEntity> unresolved = getUnresolvedDurationItems();
                Log.i(TAG, "Enrichment: " + unresolved.size() + " items need duration resolution");

                for (VideoIndexEntity entity : unresolved) {
                    try {
                        if ("IMAGE".equals(entity.mediaType)) {
                            // Images don't have duration
                            dao.updateDuration(entity.uriString, 0);
                            continue;
                        }

                        long duration = computeDuration(Uri.parse(entity.uriString));
                        if (duration > 0) {
                            // Only mark as resolved if we actually got a valid duration.
                            // If MMR returns 0, leave durationResolved=false so the adapter
                            // can try FFprobe and write the result back via persistDurationToDb().
                            dao.updateDuration(entity.uriString, duration);
                            durationCache.put(entity.uriString, duration);
                        } else {
                            Log.d(TAG, "Enrichment: MMR returned 0 for " + entity.displayName + ", leaving unresolved for FFprobe fallback");
                        }

                        if (callback != null) {
                            callback.onItemEnriched(entity.uriString, duration);
                        }
                    } catch (Exception e) {
                        Log.w(TAG, "Failed to enrich: " + entity.displayName);
                        // Don't mark as resolved — leave for FFprobe fallback
                        Log.d(TAG, "Enrichment: leaving " + entity.displayName + " unresolved for FFprobe fallback");
                    }
                }

                Log.i(TAG, "Enrichment complete");
            } catch (Exception e) {
                Log.e(TAG, "Enrichment error", e);
            } finally {
                isEnriching.set(false);
            }
        });
    }

    /**
     * Get cached duration for a URI from the in-memory cache.
     * Returns -1 if not found or not yet resolved.
     * Safe to call from the main thread (no DB access).
     */
    public long getCachedDuration(@NonNull String uriString) {
        Long duration = durationCache.get(uriString);
        return duration != null ? duration : -1;
    }

    /**
     * Persist a duration discovered by the adapter (via FFprobe) back into the Room DB
     * and in-memory cache. This prevents re-probing on every fragment open.
     * Safe to call from any thread — DB write is dispatched to the enrichment executor.
     */
    public void persistDurationToDb(@NonNull String uriString, long durationMs) {
        if (durationMs <= 0) return;
        durationCache.put(uriString, durationMs);
        enrichmentExecutor.submit(() -> {
            try {
                dao.updateDuration(uriString, durationMs);
                Log.d(TAG, "Persisted FFprobe duration to DB: " + durationMs + "ms for " + uriString);
            } catch (Exception e) {
                Log.w(TAG, "Failed to persist duration to DB: " + e.getMessage());
            }
        });
    }

    // ════════════════════════════════════════════════════════════════
    // Single-item operations
    // ════════════════════════════════════════════════════════════════

    /**
     * Remove a single video from the index (after delete/move).
     * Runs DB deletion async; updates in-memory caches immediately.
     */
    public void removeFromIndex(@NonNull String uriString) {
        durationCache.remove(uriString);
        cachedCount.updateAndGet(c -> Math.max(0, c - 1));
        enrichmentExecutor.submit(() -> {
            try {
                dao.deleteByUri(uriString);
            } catch (Exception e) {
                Log.w(TAG, "Failed to remove from index: " + uriString, e);
            }
        });
    }

    /**
     * Remove multiple videos from the index.
     * Runs DB deletion async; updates in-memory caches immediately.
     */
    public void removeFromIndex(@NonNull List<String> uriStrings) {
        for (String uri : uriStrings) {
            durationCache.remove(uri);
        }
        cachedCount.updateAndGet(c -> Math.max(0, c - uriStrings.size()));
        enrichmentExecutor.submit(() -> {
            try {
                dao.deleteByUris(uriStrings);
            } catch (Exception e) {
                Log.w(TAG, "Failed to remove from index", e);
            }
        });
    }

    /**
     * Invalidate the entire index. Next getVideos() call will do a full re-scan.
     * Safe to call from the main thread (no DB access — uses a flag).
     */
    public void invalidateIndex() {
        indexInvalidated.set(true);
        cachedCount.set(0);
        durationCache.clear();
        Log.i(TAG, "Index marked for invalidation (flag set)");
    }

    /**
     * Check whether the index has been marked for invalidation.
     * Does NOT consume the flag — use getVideos() for that.
     * Safe to call from any thread.
     */
    public boolean isIndexInvalidated() {
        return indexInvalidated.get();
    }

    // ════════════════════════════════════════════════════════════════
    // Conversion helpers
    // ════════════════════════════════════════════════════════════════

    /**
     * Convert DB entities to VideoItem objects for the adapter.
     */
    @NonNull
    private List<VideoItem> entitiesToVideoItems(@NonNull List<VideoIndexEntity> entities) {
        List<VideoItem> items = new ArrayList<>(entities.size());
        for (VideoIndexEntity e : entities) {
            VideoItem item = entityToVideoItem(e);
            if (item != null) items.add(item);
        }
        return items;
    }

    @Nullable
    private VideoItem entityToVideoItem(@NonNull VideoIndexEntity entity) {
        try {
            Uri uri = Uri.parse(entity.uriString);

            VideoItem.Category category = safeParseEnum(VideoItem.Category.class,
                    entity.category, VideoItem.Category.UNKNOWN);
            VideoItem.MediaType mediaType = safeParseEnum(VideoItem.MediaType.class,
                    entity.mediaType, VideoItem.MediaType.VIDEO);
            VideoItem.ShotSubtype shotSubtype = safeParseEnum(VideoItem.ShotSubtype.class,
                    entity.shotSubtype, VideoItem.ShotSubtype.UNKNOWN);
            VideoItem.CameraSubtype cameraSubtype = safeParseEnum(VideoItem.CameraSubtype.class,
                    entity.cameraSubtype, VideoItem.CameraSubtype.UNKNOWN);
            VideoItem.FaditorSubtype faditorSubtype = safeParseEnum(VideoItem.FaditorSubtype.class,
                    entity.faditorSubtype, VideoItem.FaditorSubtype.UNKNOWN);

            VideoItem item = new VideoItem(
                    uri,
                    entity.displayName,
                    entity.fileSize,
                    entity.lastModified,
                    category,
                    mediaType,
                    shotSubtype,
                    cameraSubtype,
                    faditorSubtype
            );
            item.isNew = Utils.isVideoConsideredNew(entity.lastModified);
            return item;
        } catch (Exception e) {
            Log.w(TAG, "Failed to convert entity: " + entity.displayName, e);
            return null;
        }
    }

    private <T extends Enum<T>> T safeParseEnum(Class<T> enumClass, String value, T defaultValue) {
        if (value == null) return defaultValue;
        try {
            return Enum.valueOf(enumClass, value);
        } catch (IllegalArgumentException e) {
            return defaultValue;
        }
    }

    // ════════════════════════════════════════════════════════════════
    // In-memory cache helpers
    // ════════════════════════════════════════════════════════════════

    /**
     * Populate the in-memory caches (count + duration map) from a set of entities.
     * Called after every DB operation that returns a fresh list of entities.
     */
    private void updateInMemoryCaches(@NonNull List<VideoIndexEntity> entities, int itemCount) {
        cachedCount.set(itemCount);
        durationCache.clear();
        for (VideoIndexEntity entity : entities) {
            if (entity.durationResolved && entity.durationMs > 0) {
                durationCache.put(entity.uriString, entity.durationMs);
            }
        }
    }

    // ════════════════════════════════════════════════════════════════
    // Duration computation
    // ════════════════════════════════════════════════════════════════

    @NonNull
    private List<VideoIndexEntity> getUnresolvedDurationItems() {
        // Query unresolved items directly
        try {
            // Use a raw query approach via the DAO
            List<VideoIndexEntity> all = dao.getAllNewestFirst();
            List<VideoIndexEntity> unresolved = new ArrayList<>();
            for (VideoIndexEntity e : all) {
                if (!e.durationResolved) {
                    unresolved.add(e);
                }
            }
            return unresolved;
        } catch (Exception e) {
            Log.w(TAG, "Failed to get unresolved items", e);
            return new ArrayList<>();
        }
    }

    /**
     * Compute video duration using MediaMetadataRetriever.
     * This is the safest cross-device method (no FFprobe dependency).
     */
    private long computeDuration(@NonNull Uri videoUri) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            String scheme = videoUri.getScheme();
            if ("content".equals(scheme)) {
                retriever.setDataSource(appContext, videoUri);
            } else if ("file".equals(scheme)) {
                String path = videoUri.getPath();
                if (path != null) {
                    retriever.setDataSource(path);
                } else {
                    return 0;
                }
            } else {
                return 0;
            }

            String durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            if (durationStr != null) {
                return Long.parseLong(durationStr);
            }
        } catch (Exception e) {
            Log.w(TAG, "Duration extraction failed for " + videoUri, e);
        } finally {
            try {
                retriever.release();
            } catch (Exception ignored) {
            }
        }
        return 0;
    }
}
