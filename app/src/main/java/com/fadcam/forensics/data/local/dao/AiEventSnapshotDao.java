package com.fadcam.forensics.data.local.dao;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import com.fadcam.forensics.data.local.entity.AiEventSnapshotEntity;
import com.fadcam.forensics.data.local.model.ForensicsSnapshotWithMedia;

import java.util.List;

@Dao
public interface AiEventSnapshotDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void upsert(AiEventSnapshotEntity entity);

    @Query("DELETE FROM ai_event_snapshot WHERE snapshot_uid = :snapshotUid")
    void deleteBySnapshotUid(String snapshotUid);

    @Query("SELECT COUNT(*) FROM ai_event_snapshot")
    long countAllSnapshots();

    @Query("SELECT COUNT(*) FROM ai_event_snapshot WHERE media_uid = :mediaUid")
    long countByMediaUid(String mediaUid);

    @Query("SELECT * FROM ai_event_snapshot WHERE event_uid = :eventUid ORDER BY timeline_ms ASC LIMIT :limitCount")
    List<AiEventSnapshotEntity> getByEventUid(String eventUid, int limitCount);

    @Query("SELECT image_uri FROM ai_event_snapshot WHERE event_uid = :eventUid ORDER BY confidence DESC, captured_epoch_ms DESC LIMIT 1")
    String getBestImageUri(String eventUid);

    @Query("SELECT " +
            "s.snapshot_uid AS snapshotUid, s.event_uid AS eventUid, s.media_uid AS mediaUid, " +
            "s.captured_epoch_ms AS capturedEpochMs, s.timeline_ms AS timelineMs, s.event_type AS eventType, " +
            "s.class_name AS className, s.confidence AS confidence, s.bbox_norm AS bboxNorm, " +
            "s.image_uri AS imageUri, s.sha256 AS sha256, " +
            "m.current_uri AS mediaUri, m.display_name AS mediaDisplayName, m.link_status AS linkStatus, m.last_seen_at AS mediaLastSeenAt, " +
            "COALESCE(e.media_missing, 0) AS mediaMissing " +
            "FROM ai_event_snapshot s " +
            "LEFT JOIN media_asset m ON m.media_uid = s.media_uid " +
            "LEFT JOIN ai_event e ON e.event_uid = s.event_uid " +
            "WHERE (:eventType IS NULL OR s.event_type = :eventType) " +
            "AND s.confidence >= :minConfidence " +
            "AND (:mediaState IS NULL OR (:mediaState = 'MISSING' AND COALESCE(e.media_missing, 0) = 1) OR (:mediaState = 'AVAILABLE' AND COALESCE(e.media_missing, 0) = 0)) " +
            "AND s.captured_epoch_ms >= :sinceEpochMs " +
            "ORDER BY s.captured_epoch_ms DESC " +
            "LIMIT :limitCount")
    List<ForensicsSnapshotWithMedia> getGallerySnapshots(
            String eventType,
            float minConfidence,
            String mediaState,
            long sinceEpochMs,
            int limitCount
    );
}
