package com.fadcam.forensics.data.local.entity;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

@Entity(
    tableName = "integrity_link_log",
    indices = {
        @Index(value = {"media_uid"}),
        @Index(value = {"timestamp"})
    }
)
public class IntegrityLinkLogEntity {

    @PrimaryKey
    @NonNull
    @ColumnInfo(name = "log_uid")
    public String logUid;

    @ColumnInfo(name = "media_uid")
    public String mediaUid;

    @ColumnInfo(name = "action")
    public String action;

    @ColumnInfo(name = "score")
    public float score;

    @ColumnInfo(name = "timestamp")
    public long timestamp;
}
