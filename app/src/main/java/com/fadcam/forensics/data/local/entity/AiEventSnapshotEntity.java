package com.fadcam.forensics.data.local.entity;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.ForeignKey;
import androidx.room.Index;
import androidx.room.PrimaryKey;

@Entity(
    tableName = "ai_event_snapshot",
    foreignKeys = {
        @ForeignKey(
            entity = AiEventEntity.class,
            parentColumns = "event_uid",
            childColumns = "event_uid",
            onDelete = ForeignKey.CASCADE
        ),
        @ForeignKey(
            entity = MediaAssetEntity.class,
            parentColumns = "media_uid",
            childColumns = "media_uid",
            onDelete = ForeignKey.CASCADE
        )
    },
    indices = {
        @Index(value = {"event_uid"}),
        @Index(value = {"media_uid"}),
        @Index(value = {"captured_epoch_ms"})
    }
)
public class AiEventSnapshotEntity {

    @PrimaryKey
    @NonNull
    @ColumnInfo(name = "snapshot_uid")
    public String snapshotUid;

    @NonNull
    @ColumnInfo(name = "event_uid")
    public String eventUid;

    @NonNull
    @ColumnInfo(name = "media_uid")
    public String mediaUid;

    @ColumnInfo(name = "captured_epoch_ms")
    public long capturedEpochMs;

    @ColumnInfo(name = "timeline_ms")
    public long timelineMs;

    @ColumnInfo(name = "event_type")
    public String eventType;

    @ColumnInfo(name = "class_name")
    public String className;

    @ColumnInfo(name = "confidence")
    public float confidence;

    @ColumnInfo(name = "bbox_norm")
    public String bboxNorm;

    @ColumnInfo(name = "image_uri")
    public String imageUri;

    @ColumnInfo(name = "sha256")
    public String sha256;
}
