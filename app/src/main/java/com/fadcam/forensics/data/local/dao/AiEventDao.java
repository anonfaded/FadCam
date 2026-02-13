package com.fadcam.forensics.data.local.dao;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import com.fadcam.forensics.data.local.entity.AiEventEntity;
import com.fadcam.forensics.data.local.model.AiEventWithMedia;

import java.util.List;

@Dao
public interface AiEventDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void upsert(AiEventEntity entity);

    @Query("SELECT * FROM ai_event WHERE media_uid = :mediaUid ORDER BY start_ms ASC")
    List<AiEventEntity> getByMediaUid(String mediaUid);

    @Query("SELECT " +
            "e.event_uid AS eventUid, e.media_uid AS mediaUid, e.event_type AS eventType, " +
            "e.start_ms AS startMs, e.end_ms AS endMs, e.confidence AS confidence, " +
            "e.bbox_norm AS bboxNorm, e.priority AS priority, e.thumbnail_ref AS thumbnailRef, " +
            "e.detected_at_epoch_ms AS detectedAtEpochMs, " +
            "m.current_uri AS mediaUri, m.display_name AS mediaDisplayName, m.link_status AS linkStatus, m.last_seen_at AS mediaLastSeenAt " +
            "FROM ai_event e " +
            "INNER JOIN media_asset m ON m.media_uid = e.media_uid " +
            "WHERE (:eventType IS NULL OR e.event_type = :eventType) " +
            "AND e.confidence >= :minConfidence " +
            "AND m.last_seen_at >= :sinceEpochMs " +
            "ORDER BY e.detected_at_epoch_ms DESC " +
            "LIMIT :limitCount")
    List<AiEventWithMedia> getTimeline(String eventType, float minConfidence, long sinceEpochMs, int limitCount);

    @Query("SELECT COUNT(*) FROM ai_event WHERE detected_at_epoch_ms >= :sinceEpochMs")
    int countSince(long sinceEpochMs);

    @Query("SELECT COUNT(*) FROM ai_event WHERE event_type = :eventType AND detected_at_epoch_ms >= :sinceEpochMs")
    int countByTypeSince(String eventType, long sinceEpochMs);

    @Query("SELECT * FROM ai_event WHERE detected_at_epoch_ms >= :sinceEpochMs ORDER BY detected_at_epoch_ms DESC LIMIT :limitCount")
    List<AiEventEntity> getRecentForHeatmap(long sinceEpochMs, int limitCount);
}
