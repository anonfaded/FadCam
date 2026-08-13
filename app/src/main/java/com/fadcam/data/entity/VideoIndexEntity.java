package com.fadcam.data.entity;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/**
 * Room entity representing an indexed video/image file.
 * This is the persistent cache — once a file is indexed, its metadata
 * (duration, thumbnail path, category) is stored here and never recomputed
 * unless the file changes (detected via lastModified + size delta).
 */
@Entity(
    tableName = "video_index",
    indices = {
        @Index(value = "uri_string", unique = true),
        @Index(value = "category"),
        @Index(value = "last_modified")
    }
)
public class VideoIndexEntity {

    @PrimaryKey(autoGenerate = true)
    public long id;

    /** The content:// or file:// URI as a string — unique identifier. */
    @NonNull
    @ColumnInfo(name = "uri_string")
    public String uriString;

    /** Display name (filename) of the video. */
    @ColumnInfo(name = "display_name")
    public String displayName;

    /** File size in bytes. Used for delta detection. */
    @ColumnInfo(name = "file_size")
    public long fileSize;

    /** File lastModified timestamp. Used for delta detection. */
    @ColumnInfo(name = "last_modified")
    public long lastModified;

    /** Video duration in milliseconds. 0 if not yet computed or if image. */
    @ColumnInfo(name = "duration_ms")
    public long durationMs;

    /** Whether duration has been computed (distinguishes 0-duration from not-yet-computed). */
    @ColumnInfo(name = "duration_resolved")
    public boolean durationResolved;

    /** Category string: CAMERA, SCREEN, FADITOR, SHOT, STREAM, DUAL, UNKNOWN. */
    @ColumnInfo(name = "category")
    public String category;

    /** Media type: VIDEO or IMAGE. */
    @ColumnInfo(name = "media_type")
    public String mediaType;

    /** Shot subtype: BACK, SELFIE, FADREC, UNKNOWN. */
    @ColumnInfo(name = "shot_subtype")
    public String shotSubtype;

    /** Camera subtype: BACK, FRONT, DUAL, UNKNOWN. */
    @ColumnInfo(name = "camera_subtype")
    public String cameraSubtype;

    /** Faditor subtype: CONVERTED, MERGE, OTHER, UNKNOWN. */
    @ColumnInfo(name = "faditor_subtype")
    public String faditorSubtype;

    /** Absolute path to the cached thumbnail file on disk. Null if not yet generated. */
    @ColumnInfo(name = "thumbnail_path")
    public String thumbnailPath;

    /** Whether this file is temporary (temp_ prefix, still recording). */
    @ColumnInfo(name = "is_temporary")
    public boolean isTemporary;

    /**
     * Hybrid-MP4 finalization state (issue #332):
     * 0 = unknown/pending (never verified), 1 = finalized/playable, 2 = unrepairable.
     * Drives the self-healing scan — only rows with 0 are ever touched, so no
     * files are re-examined after they are confirmed good.
     */
    @ColumnInfo(name = "finalized", defaultValue = "0")
    public int finalized;

    /** Timestamp when this row was last indexed/updated. */
    @ColumnInfo(name = "indexed_at")
    public long indexedAt;
}
