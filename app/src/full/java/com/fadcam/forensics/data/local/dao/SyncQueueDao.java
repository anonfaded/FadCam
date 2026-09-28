package com.fadcam.forensics.data.local.dao;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;

import com.fadcam.forensics.data.local.entity.SyncQueueEntity;

@Dao
public interface SyncQueueDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void upsert(SyncQueueEntity entity);
}
