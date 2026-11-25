package com.fadcam.forensics.data.local.entity;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

@Entity(
    tableName = "sync_queue",
    indices = {
        @Index(value = {"status"}),
        @Index(value = {"entity_type", "entity_id"})
    }
)
public class SyncQueueEntity {

    @PrimaryKey
    @NonNull
    @ColumnInfo(name = "op_uid")
    public String opUid;

    @ColumnInfo(name = "entity_type")
    public String entityType;

    @ColumnInfo(name = "entity_id")
    public String entityId;

    @ColumnInfo(name = "operation")
    public String operation;

    @ColumnInfo(name = "payload_json")
    public String payloadJson;

    @ColumnInfo(name = "status")
    public String status;

    @ColumnInfo(name = "retry_count")
    public int retryCount;
}
