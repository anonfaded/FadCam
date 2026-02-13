package com.fadcam.forensics.data.local.dao;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import com.fadcam.forensics.data.local.entity.AiEventEntity;

import java.util.List;

@Dao
public interface AiEventDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void upsert(AiEventEntity entity);

    @Query("SELECT * FROM ai_event WHERE media_uid = :mediaUid ORDER BY start_ms ASC")
    List<AiEventEntity> getByMediaUid(String mediaUid);
}
