package com.fadcam.ui.faditor.persistence;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Update;

import java.util.List;

/**
 * Data Access Object for project metadata operations
 */
@Dao
public interface ProjectDao {
    
    @Query("SELECT * FROM projects ORDER BY lastModified DESC")
    LiveData<List<ProjectMetadata>> getAllProjects();
    
    @Query("SELECT * FROM projects WHERE projectId = :projectId")
    ProjectMetadata getProject(String projectId);
    
    @Query("SELECT * FROM projects WHERE projectName LIKE :searchQuery ORDER BY lastModified DESC")
    LiveData<List<ProjectMetadata>> searchProjects(String searchQuery);
    
    @Query("SELECT * FROM projects ORDER BY lastModified DESC LIMIT :limit")
    LiveData<List<ProjectMetadata>> getRecentProjects(int limit);
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertProject(ProjectMetadata project);
    
    @Update
    void updateProject(ProjectMetadata project);
    
    @Delete
    void deleteProject(ProjectMetadata project);
    
    @Query("DELETE FROM projects WHERE projectId = :projectId")
    void deleteProjectById(String projectId);
    
    @Query("UPDATE projects SET hasUnsavedChanges = :hasChanges WHERE projectId = :projectId")
    void updateUnsavedChanges(String projectId, boolean hasChanges);
    
    @Query("UPDATE projects SET lastModified = :timestamp WHERE projectId = :projectId")
    void updateLastModified(String projectId, long timestamp);
    
    @Query("SELECT COUNT(*) FROM projects")
    int getProjectCount();
}