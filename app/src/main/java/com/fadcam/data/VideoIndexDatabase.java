package com.fadcam.data;

import android.content.Context;

import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;

import com.fadcam.data.dao.VideoIndexDao;
import com.fadcam.data.entity.VideoIndexEntity;

/**
 * Room database for the video file index.
 * Separate from ForensicsDatabase to keep concerns isolated.
 * <p>
 * This DB stores the persistent cache of all discovered video/image files
 * with their metadata (duration, thumbnail path, category, etc.).
 * On subsequent app opens, the Records tab reads from this DB instantly
 * instead of re-scanning the file system.
 */
@Database(
    entities = {VideoIndexEntity.class},
    version = 3,
    exportSchema = false
)
public abstract class VideoIndexDatabase extends RoomDatabase {

    private static final String DB_NAME = "video_index.db";
    private static volatile VideoIndexDatabase instance;

    /** Migration 1→2: add the hybrid-finalization state column (issue #332). */
    public static final androidx.room.migration.Migration MIGRATION_1_2 = new androidx.room.migration.Migration(1, 2) {
        @Override
        public void migrate(androidx.sqlite.db.SupportSQLiteDatabase db) {
            db.execSQL("ALTER TABLE video_index ADD COLUMN finalized INTEGER NOT NULL DEFAULT 0");
        }
    };

    /** Migration 2→3: add retry_after so unrepairable files can be retried later. */
    public static final androidx.room.migration.Migration MIGRATION_2_3 = new androidx.room.migration.Migration(2, 3) {
        @Override
        public void migrate(androidx.sqlite.db.SupportSQLiteDatabase db) {
            db.execSQL("ALTER TABLE video_index ADD COLUMN retry_after INTEGER NOT NULL DEFAULT 0");
        }
    };

    public abstract VideoIndexDao videoIndexDao();

    /**
     * Thread-safe singleton accessor.
     *
     * @param context Application context
     * @return The singleton database instance
     */
    public static VideoIndexDatabase getInstance(Context context) {
        if (instance == null) {
            synchronized (VideoIndexDatabase.class) {
                if (instance == null) {
                    instance = Room.databaseBuilder(
                            context.getApplicationContext(),
                            VideoIndexDatabase.class,
                            DB_NAME
                        )
                        .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                        .fallbackToDestructiveMigration()
                        .build();
                }
            }
        }
        return instance;
    }
}
