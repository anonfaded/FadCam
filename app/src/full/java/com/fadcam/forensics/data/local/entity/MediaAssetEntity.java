package com.fadcam.forensics.data.local.entity;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

@Entity(
    tableName = "media_asset",
    indices = {
        @Index(value = {"current_uri"}),
        @Index(value = {"exact_fingerprint"}),
        @Index(value = {"visual_fingerprint"})
    }
)
public class MediaAssetEntity {

    @PrimaryKey
    @NonNull
    @ColumnInfo(name = "media_uid")
    public String mediaUid;

    @NonNull
    @ColumnInfo(name = "current_uri")
    public String currentUri;

    @ColumnInfo(name = "display_name")
    public String displayName;

    @ColumnInfo(name = "category_subtype")
    public String categorySubtype;

    @ColumnInfo(name = "size_bytes")
    public long sizeBytes;

    @ColumnInfo(name = "duration_ms")
    public long durationMs;

    @ColumnInfo(name = "codec_info")
    public String codecInfo;

    @ColumnInfo(name = "exact_fingerprint")
    public String exactFingerprint;

    @ColumnInfo(name = "visual_fingerprint")
    public String visualFingerprint;

    @ColumnInfo(name = "first_seen_at")
    public long firstSeenAt;

    @ColumnInfo(name = "last_seen_at")
    public long lastSeenAt;

    @ColumnInfo(name = "link_status")
    public String linkStatus;
}
