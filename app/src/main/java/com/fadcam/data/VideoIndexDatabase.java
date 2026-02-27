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
    version = 1,
    exportSchema = false
)
public abstract class VideoIndexDatabase extends RoomDatabase {

    private static final String DB_NAME = "video_index.db";
    private static volatile VideoIndexDatabase instance;

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
                        .fallbackToDestructiveMigration()
                        .build();
                }
            }
        }
        return instance;
    }
}
