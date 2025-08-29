package com.fadcam.ui.faditor.persistence;

import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import android.content.Context;

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
                    INSTANCE = Room.databaseBuilder(
                        context.getApplicationContext(),
                        ProjectDatabase.class,
                        DATABASE_NAME
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