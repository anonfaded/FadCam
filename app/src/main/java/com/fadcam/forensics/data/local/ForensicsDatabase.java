package com.fadcam.forensics.data.local;

import android.content.Context;

import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;

import com.fadcam.forensics.data.local.dao.AiEventDao;
import com.fadcam.forensics.data.local.dao.AiEventSnapshotDao;
import com.fadcam.forensics.data.local.dao.IntegrityLinkLogDao;
import com.fadcam.forensics.data.local.dao.MediaAssetDao;
import com.fadcam.forensics.data.local.dao.SyncQueueDao;
import com.fadcam.forensics.data.local.entity.AiEventEntity;
import com.fadcam.forensics.data.local.entity.AiEventSnapshotEntity;
import com.fadcam.forensics.data.local.entity.IntegrityLinkLogEntity;
import com.fadcam.forensics.data.local.entity.MediaAssetEntity;
import com.fadcam.forensics.data.local.entity.SyncQueueEntity;

@Database(
    entities = {
        MediaAssetEntity.class,
        AiEventEntity.class,
        AiEventSnapshotEntity.class,
        IntegrityLinkLogEntity.class,
        SyncQueueEntity.class
    },
    version = 5,
    exportSchema = false
)
public abstract class ForensicsDatabase extends RoomDatabase {

    private static final String DB_NAME = "digital_forensics.db";
    private static volatile ForensicsDatabase instance;

    public abstract MediaAssetDao mediaAssetDao();

    public abstract AiEventDao aiEventDao();

    public abstract AiEventSnapshotDao aiEventSnapshotDao();

    public abstract IntegrityLinkLogDao integrityLinkLogDao();

    public abstract SyncQueueDao syncQueueDao();

    public static ForensicsDatabase getInstance(Context context) {
        if (instance == null) {
            synchronized (ForensicsDatabase.class) {
                if (instance == null) {
                    instance = Room.databaseBuilder(
                            context.getApplicationContext(),
                            ForensicsDatabase.class,
                            DB_NAME
                        )
                        .fallbackToDestructiveMigration()
                        .build();
                }
            }
        }
        return instance;
    }
}
