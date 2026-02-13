package com.fadcam.forensics.data.local.entity;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.ForeignKey;
import androidx.room.Index;
import androidx.room.PrimaryKey;

@Entity(
    tableName = "ai_event",
    foreignKeys = @ForeignKey(
        entity = MediaAssetEntity.class,
        parentColumns = "media_uid",
        childColumns = "media_uid",
        onDelete = ForeignKey.CASCADE
    ),
    indices = {
        @Index(value = {"media_uid"}),
        @Index(value = {"event_type"})
    }
)
public class AiEventEntity {

    @PrimaryKey
    @NonNull
    @ColumnInfo(name = "event_uid")
    public String eventUid;

    @NonNull
    @ColumnInfo(name = "media_uid")
    public String mediaUid;

    @ColumnInfo(name = "event_type")
    public String eventType;

    @ColumnInfo(name = "start_ms")
    public long startMs;

    @ColumnInfo(name = "end_ms")
    public long endMs;

    @ColumnInfo(name = "confidence")
    public float confidence;

    @ColumnInfo(name = "bbox_norm")
    public String bboxNorm;

    @ColumnInfo(name = "track_id")
    public String trackId;

    @ColumnInfo(name = "priority")
    public int priority;

    @ColumnInfo(name = "thumbnail_ref")
    public String thumbnailRef;
}
