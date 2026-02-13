package com.fadcam.forensics.data.local;

import android.content.Context;

import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;

import com.fadcam.forensics.data.local.dao.AiEventDao;
import com.fadcam.forensics.data.local.dao.IntegrityLinkLogDao;
import com.fadcam.forensics.data.local.dao.MediaAssetDao;
import com.fadcam.forensics.data.local.dao.SyncQueueDao;
import com.fadcam.forensics.data.local.entity.AiEventEntity;
import com.fadcam.forensics.data.local.entity.IntegrityLinkLogEntity;
import com.fadcam.forensics.data.local.entity.MediaAssetEntity;
import com.fadcam.forensics.data.local.entity.SyncQueueEntity;

@Database(
    entities = {
        MediaAssetEntity.class,
        AiEventEntity.class,
        IntegrityLinkLogEntity.class,
        SyncQueueEntity.class
    },
    version = 2,
    exportSchema = false
)
public abstract class ForensicsDatabase extends RoomDatabase {

    private static final String DB_NAME = "digital_forensics.db";
    private static volatile ForensicsDatabase instance;
    private static final Migration MIGRATION_1_2 = new Migration(1, 2) {
        @Override
        public void migrate(SupportSQLiteDatabase database) {
            database.execSQL("ALTER TABLE ai_event ADD COLUMN detected_at_epoch_ms INTEGER NOT NULL DEFAULT 0");
        }
    };

    public abstract MediaAssetDao mediaAssetDao();

    public abstract AiEventDao aiEventDao();

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
                        .addMigrations(MIGRATION_1_2)
                        .build();
                }
            }
        }
        return instance;
    }
}
