package com.fadcam.forensics.data.local.dao;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Update;

import com.fadcam.forensics.data.local.entity.AiEventEntity;
import com.fadcam.forensics.data.local.model.AiEventWithMedia;

import java.util.List;

@Dao
public interface AiEventDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    long insertIgnore(AiEventEntity entity);

    @Update
    int update(AiEventEntity entity);

    @Query("SELECT * FROM ai_event WHERE media_uid = :mediaUid ORDER BY start_ms ASC")
    List<AiEventEntity> getByMediaUid(String mediaUid);

    @Query("SELECT " +
            "e.event_uid AS eventUid, e.media_uid AS mediaUid, e.event_type AS eventType, e.class_name AS className, " +
            "e.start_ms AS startMs, e.end_ms AS endMs, e.confidence AS confidence, " +
            "e.bbox_norm AS bboxNorm, e.priority AS priority, e.thumbnail_ref AS thumbnailRef, " +
            "e.detected_at_epoch_ms AS detectedAtEpochMs, " +
            "e.status AS status, e.first_seen_epoch_ms AS firstSeenEpochMs, e.last_seen_epoch_ms AS lastSeenEpochMs, " +
            "e.sample_count AS sampleCount, e.peak_confidence AS peakConfidence, e.media_missing AS mediaMissing, " +
            "e.alert_state AS alertState, e.alert_channel AS alertChannel, " +
            "m.current_uri AS mediaUri, m.display_name AS mediaDisplayName, m.link_status AS linkStatus, m.last_seen_at AS mediaLastSeenAt " +
            "FROM ai_event e " +
            "INNER JOIN media_asset m ON m.media_uid = e.media_uid " +
            "WHERE (:eventType IS NULL OR e.event_type = :eventType) " +
            "AND (:className IS NULL OR e.class_name = :className) " +
            "AND e.confidence >= :minConfidence " +
            "AND (:mediaState IS NULL OR (:mediaState = 'MISSING' AND e.media_missing = 1) OR (:mediaState = 'AVAILABLE' AND e.media_missing = 0)) " +
            "AND e.detected_at_epoch_ms >= :sinceEpochMs " +
            "ORDER BY " +
            "CASE WHEN :sortOrder = 'confidence' THEN e.peak_confidence " +
            "WHEN :sortOrder = 'evidence' THEN e.sample_count " +
            "ELSE e.detected_at_epoch_ms END DESC, " +
            "e.detected_at_epoch_ms DESC " +
            "LIMIT :limitCount")
    List<AiEventWithMedia> getTimeline(
            String eventType,
            String className,
            float minConfidence,
            long sinceEpochMs,
            String mediaState,
            String sortOrder,
            int limitCount
    );

    @Query("SELECT COUNT(*) FROM ai_event WHERE detected_at_epoch_ms >= :sinceEpochMs")
    int countSince(long sinceEpochMs);

    @Query("SELECT COUNT(*) FROM ai_event WHERE event_type = :eventType AND detected_at_epoch_ms >= :sinceEpochMs")
    int countByTypeSince(String eventType, long sinceEpochMs);

    @Query("SELECT * FROM ai_event WHERE detected_at_epoch_ms >= :sinceEpochMs ORDER BY detected_at_epoch_ms DESC LIMIT :limitCount")
    List<AiEventEntity> getRecentForHeatmap(long sinceEpochMs, int limitCount);

    @Query("SELECT class_name FROM ai_event " +
            "WHERE detected_at_epoch_ms >= :sinceEpochMs " +
            "AND (:eventType IS NULL OR event_type = :eventType) " +
            "AND class_name IS NOT NULL AND class_name != '' " +
            "GROUP BY class_name " +
            "ORDER BY COUNT(*) DESC, MAX(detected_at_epoch_ms) DESC " +
            "LIMIT :limitCount")
    List<String> getTopClassNames(long sinceEpochMs, String eventType, int limitCount);

    @Query("UPDATE ai_event SET media_missing = :missing WHERE media_uid = :mediaUid")
    void updateMediaMissingByMediaUid(String mediaUid, boolean missing);
}
