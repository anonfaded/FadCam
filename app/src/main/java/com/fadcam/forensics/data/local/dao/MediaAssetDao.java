package com.fadcam.forensics.data.local.dao;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Update;

import com.fadcam.forensics.data.local.entity.MediaAssetEntity;

import java.util.List;

@Dao
public interface MediaAssetDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    long insertIgnore(MediaAssetEntity entity);

    @Update
    int update(MediaAssetEntity entity);

    @Query("SELECT * FROM media_asset WHERE current_uri = :currentUri LIMIT 1")
    MediaAssetEntity findByCurrentUri(String currentUri);

    @Query("SELECT * FROM media_asset WHERE media_uid = :mediaUid LIMIT 1")
    MediaAssetEntity findByMediaUid(String mediaUid);

    @Query("SELECT * FROM media_asset WHERE size_bytes BETWEEN :minSize AND :maxSize AND duration_ms BETWEEN :minDurationMs AND :maxDurationMs ORDER BY last_seen_at DESC LIMIT 200")
    List<MediaAssetEntity> findProbableCandidates(long minSize, long maxSize, long minDurationMs, long maxDurationMs);

    @Query("UPDATE media_asset SET current_uri = :currentUri, display_name = :displayName, size_bytes = :sizeBytes, duration_ms = :durationMs, codec_info = :codecInfo, last_seen_at = :lastSeenAt, link_status = :linkStatus WHERE media_uid = :mediaUid")
    void updateLinkAndMetadata(
        String mediaUid,
        String currentUri,
        String displayName,
        long sizeBytes,
        long durationMs,
        String codecInfo,
        long lastSeenAt,
        String linkStatus
    );
}
