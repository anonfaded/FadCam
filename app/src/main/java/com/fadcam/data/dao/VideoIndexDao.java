package com.fadcam.data.dao;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Update;

import com.fadcam.data.entity.VideoIndexEntity;

import java.util.List;

/**
 * Data Access Object for the video_index table.
 * Provides fast queries for the Records tab — all reads are O(1) from SQLite,
 * no file system scanning needed after initial index.
 */
@Dao
public interface VideoIndexDao {

    // ──────────────────────────────────────────────
    // Bulk operations
    // ──────────────────────────────────────────────

    /**
     * Get all indexed videos, ordered by last_modified descending (newest first).
     * This is the primary query for the Records tab.
     */
    @Query("SELECT * FROM video_index ORDER BY last_modified DESC")
    List<VideoIndexEntity> getAllNewestFirst();

    /**
     * Get all indexed videos ordered by last_modified ascending (oldest first).
     */
    @Query("SELECT * FROM video_index ORDER BY last_modified ASC")
    List<VideoIndexEntity> getAllOldestFirst();

    /**
     * Get all indexed videos ordered by file_size descending.
     */
    @Query("SELECT * FROM video_index ORDER BY file_size DESC")
    List<VideoIndexEntity> getAllLargestFirst();

    /**
     * Get all indexed videos ordered by file_size ascending.
     */
    @Query("SELECT * FROM video_index ORDER BY file_size ASC")
    List<VideoIndexEntity> getAllSmallestFirst();

    /**
     * Get all indexed videos ordered by display_name ascending.
     */
    @Query("SELECT * FROM video_index ORDER BY display_name ASC")
    List<VideoIndexEntity> getAllByNameAsc();

    /**
     * Get count of all indexed videos.
     */
    @Query("SELECT COUNT(*) FROM video_index")
    int getCount();

    /**
     * Get total size in bytes of all indexed videos. Returns 0 if no entries.
     */
    @Query("SELECT COALESCE(SUM(file_size), 0) FROM video_index")
    long getTotalSize();

    /**
     * Get all URIs currently in the index. Used for delta scan (detecting deleted files).
     */
    @Query("SELECT uri_string FROM video_index")
    List<String> getAllUriStrings();

    /**
     * Get lightweight list for delta detection: uri + lastModified + size.
     * This avoids loading full entities just to compare timestamps.
     */
    @Query("SELECT uri_string, last_modified, file_size FROM video_index")
    List<DeltaCheckRow> getDeltaCheckRows();

    /**
     * Lightweight projection for delta scanning.
     */
    class DeltaCheckRow {
        public String uri_string;
        public long last_modified;
        public long file_size;
    }

    // ──────────────────────────────────────────────
    // Single-item operations
    // ──────────────────────────────────────────────

    /**
     * Find a single video by its URI string.
     */
    @Query("SELECT * FROM video_index WHERE uri_string = :uriString LIMIT 1")
    VideoIndexEntity findByUri(String uriString);

    /**
     * Get the cached duration for a specific URI. Returns null if not found.
     */
    @Query("SELECT duration_ms FROM video_index WHERE uri_string = :uriString AND duration_resolved = 1 LIMIT 1")
    Long getDurationByUri(String uriString);

    /**
     * Get the cached thumbnail path for a specific URI.
     */
    @Query("SELECT thumbnail_path FROM video_index WHERE uri_string = :uriString LIMIT 1")
    String getThumbnailPathByUri(String uriString);

    // ──────────────────────────────────────────────
    // Write operations
    // ──────────────────────────────────────────────

    /**
     * Insert or replace a video index entry.
     * REPLACE strategy: if URI already exists, overwrite with new data.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertOrReplace(VideoIndexEntity entity);

    /**
     * Bulk insert or replace. Used during initial full scan.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertOrReplaceAll(List<VideoIndexEntity> entities);

    /**
     * Update an existing entity (e.g., after computing duration or thumbnail).
     */
    @Update
    void update(VideoIndexEntity entity);

    /**
     * Update only the duration fields for a specific URI.
     * Called after background FFprobe/MMR computation finishes.
     */
    @Query("UPDATE video_index SET duration_ms = :durationMs, duration_resolved = 1 WHERE uri_string = :uriString")
    void updateDuration(String uriString, long durationMs);

    /**
     * Update only the thumbnail path for a specific URI.
     * Called after background thumbnail extraction finishes.
     */
    @Query("UPDATE video_index SET thumbnail_path = :thumbnailPath WHERE uri_string = :uriString")
    void updateThumbnailPath(String uriString, String thumbnailPath);

    // ──────────────────────────────────────────────
    // Delete operations
    // ──────────────────────────────────────────────

    /**
     * Delete a specific video from the index by URI.
     */
    @Query("DELETE FROM video_index WHERE uri_string = :uriString")
    void deleteByUri(String uriString);

    /**
     * Delete multiple videos by their URIs. Used when files are deleted from disk.
     */
    @Query("DELETE FROM video_index WHERE uri_string IN (:uriStrings)")
    void deleteByUris(List<String> uriStrings);

    /**
     * Delete all entries. Used for full re-index.
     */
    @Query("DELETE FROM video_index")
    void deleteAll();
}
