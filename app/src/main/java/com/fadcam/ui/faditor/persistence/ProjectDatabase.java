package com.fadcam.ui.faditor.persistence;

import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import android.content.Context;
import java.io.File;

/**
 * Room database for project indexing and metadata storage
 */
@Database(
    entities = {ProjectMetadata.class},
    version = 1,
    exportSchema = false
)
public abstract class ProjectDatabase extends RoomDatabase {
    
    private static final String DATABASE_NAME = "faditor_projects.db";
    private static volatile ProjectDatabase INSTANCE;
    
    public abstract ProjectDao projectDao();
    
    public static ProjectDatabase getInstance(Context context) {
        if (INSTANCE == null) {
            synchronized (ProjectDatabase.class) {
                if (INSTANCE == null) {
                    // Use external storage for database
                    File databaseDir = new File(context.getExternalFilesDir(null), "databases");
                    if (!databaseDir.exists()) {
                        databaseDir.mkdirs();
                    }
                    File databaseFile = new File(databaseDir, DATABASE_NAME);

                    INSTANCE = Room.databaseBuilder(
                        context.getApplicationContext(),
                        ProjectDatabase.class,
                        databaseFile.getAbsolutePath()
                    ).build();
                }
            }
        }
        return INSTANCE;
    }
    
    public static void destroyInstance() {
        INSTANCE = null;
    }
}