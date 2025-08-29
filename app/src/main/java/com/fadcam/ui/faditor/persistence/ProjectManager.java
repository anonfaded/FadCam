package com.fadcam.ui.faditor.persistence;

import android.content.Context;
import androidx.lifecycle.LiveData;

import com.fadcam.ui.faditor.models.VideoProject;
import com.fadcam.ui.faditor.models.EditorState;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Handles all project persistence and management operations
 */
public class ProjectManager {
    
    private static final String PROJECTS_DIR = "faditor_projects";
    private static final String PROJECT_FILE_NAME = "project.json";
    private static final String EDITOR_STATE_FILE_NAME = "editor_state.json";
    private static final String THUMBNAIL_FILE_NAME = "thumbnail.jpg";
    
    private final Context context;
    private final ProjectDatabase database;
    private final MediaReferenceManager mediaReferenceManager;
    private final ExecutorService executorService;
    private final File projectsDirectory;
    
    public interface ProjectCallback {
        void onProjectSaved(String projectId);
        void onProjectLoaded(VideoProject project);
        void onError(String errorMessage);
    }
    
    public interface EditorStateCallback {
        void onEditorStateSaved(String projectId);
        void onEditorStateLoaded(EditorState editorState);
        void onError(String errorMessage);
    }
    
    public ProjectManager(Context context) {
        this.context = context;
        this.database = ProjectDatabase.getInstance(context);
        this.mediaReferenceManager = new MediaReferenceManager(context);
        this.executorService = Executors.newFixedThreadPool(2);
        
        // Create projects directory
        this.projectsDirectory = new File(context.getFilesDir(), PROJECTS_DIR);
        if (!projectsDirectory.exists()) {
            projectsDirectory.mkdirs();
        }
    }
    
    /**
     * Creates a new project with a unique ID
     */
    public String createNewProject(String projectName) {
        String projectId = UUID.randomUUID().toString();
        
        // Create project directory
        File projectDir = new File(projectsDirectory, projectId);
        if (!projectDir.exists()) {
            projectDir.mkdirs();
        }
        
        return projectId;
    }
    
    /**
     * Saves a project to both JSON file and database
     */
    public void saveProject(VideoProject project, ProjectCallback callback) {
        executorService.execute(() -> {
            try {
                // Validate media references
                MediaReferenceManager.ValidationResult validation = 
                    mediaReferenceManager.validateMediaReferences(project);
                
                if (!validation.isValid() && !validation.hasRecoveredPaths()) {
                    callback.onError("Some media files are missing and cannot be recovered");
                    return;
                }
                
                // Apply recovered paths if any
                if (validation.hasRecoveredPaths()) {
                    mediaReferenceManager.updateMediaPaths(project, validation.getRecoveredPaths());
                }
                
                // Serialize project to JSON
                String projectJson = ProjectSerializer.serializeProject(project);
                
                // Save to file
                File projectDir = new File(projectsDirectory, project.getProjectId());
                if (!projectDir.exists()) {
                    projectDir.mkdirs();
                }
                
                File projectFile = new File(projectDir, PROJECT_FILE_NAME);
                writeStringToFile(projectFile, projectJson);
                
                // Update database metadata
                ProjectMetadata metadata = createMetadataFromProject(project);
                database.projectDao().insertProject(metadata);
                
                callback.onProjectSaved(project.getProjectId());
                
            } catch (Exception e) {
                callback.onError("Failed to save project: " + e.getMessage());
            }
        });
    }
    
    /**
     * Loads a project from JSON file
     */
    public void loadProject(String projectId, ProjectCallback callback) {
        executorService.execute(() -> {
            try {
                File projectDir = new File(projectsDirectory, projectId);
                File projectFile = new File(projectDir, PROJECT_FILE_NAME);
                
                if (!projectFile.exists()) {
                    callback.onError("Project file not found");
                    return;
                }
                
                String projectJson = readStringFromFile(projectFile);
                VideoProject project = ProjectSerializer.deserializeProject(projectJson);
                
                // Validate and recover media references
                MediaReferenceManager.ValidationResult validation = 
                    mediaReferenceManager.validateMediaReferences(project);
                
                if (validation.hasRecoveredPaths()) {
                    mediaReferenceManager.updateMediaPaths(project, validation.getRecoveredPaths());
                    // Save the updated project
                    saveProject(project, new ProjectCallback() {
                        @Override
                        public void onProjectSaved(String projectId) {
                            // Silent save after recovery
                        }
                        
                        @Override
                        public void onProjectLoaded(VideoProject project) {}
                        
                        @Override
                        public void onError(String errorMessage) {}
                    });
                }
                
                callback.onProjectLoaded(project);
                
            } catch (Exception e) {
                callback.onError("Failed to load project: " + e.getMessage());
            }
        });
    }
    
    /**
     * Saves editor state for a project
     */
    public void saveEditorState(String projectId, EditorState editorState, EditorStateCallback callback) {
        executorService.execute(() -> {
            try {
                String stateJson = ProjectSerializer.serializeEditorState(editorState);
                
                File projectDir = new File(projectsDirectory, projectId);
                if (!projectDir.exists()) {
                    projectDir.mkdirs();
                }
                
                File stateFile = new File(projectDir, EDITOR_STATE_FILE_NAME);
                writeStringToFile(stateFile, stateJson);
                
                // Mark editor state as saved
                editorState.clearUnsavedChanges();
                
                callback.onEditorStateSaved(projectId);
                
            } catch (Exception e) {
                callback.onError("Failed to save editor state: " + e.getMessage());
            }
        });
    }
    
    /**
     * Loads editor state for a project
     */
    public void loadEditorState(String projectId, EditorStateCallback callback) {
        executorService.execute(() -> {
            try {
                File projectDir = new File(projectsDirectory, projectId);
                File stateFile = new File(projectDir, EDITOR_STATE_FILE_NAME);
                
                if (!stateFile.exists()) {
                    // Return default editor state if no saved state exists
                    callback.onEditorStateLoaded(new EditorState());
                    return;
                }
                
                String stateJson = readStringFromFile(stateFile);
                EditorState editorState = ProjectSerializer.deserializeEditorState(stateJson);
                
                callback.onEditorStateLoaded(editorState);
                
            } catch (Exception e) {
                callback.onError("Failed to load editor state: " + e.getMessage());
            }
        });
    }
    
    /**
     * Deletes a project and all its files
     */
    public void deleteProject(String projectId, ProjectCallback callback) {
        executorService.execute(() -> {
            try {
                // Delete from database
                database.projectDao().deleteProjectById(projectId);
                
                // Delete project directory and all files
                File projectDir = new File(projectsDirectory, projectId);
                if (projectDir.exists()) {
                    deleteDirectory(projectDir);
                }
                
                callback.onProjectSaved(projectId); // Reusing callback for consistency
                
            } catch (Exception e) {
                callback.onError("Failed to delete project: " + e.getMessage());
            }
        });
    }
    
    /**
     * Exports a project to a .fadproj file
     */
    public void exportProject(String projectId, File exportPath, ProjectCallback callback) {
        executorService.execute(() -> {
            try {
                File projectDir = new File(projectsDirectory, projectId);
                File projectFile = new File(projectDir, PROJECT_FILE_NAME);
                
                if (!projectFile.exists()) {
                    callback.onError("Project file not found");
                    return;
                }
                
                // Copy project file to export location
                copyFile(projectFile, exportPath);
                
                callback.onProjectSaved(projectId);
                
            } catch (Exception e) {
                callback.onError("Failed to export project: " + e.getMessage());
            }
        });
    }
    
    /**
     * Imports a project from a .fadproj file
     */
    public void importProject(File projectFile, ProjectCallback callback) {
        executorService.execute(() -> {
            try {
                String projectJson = readStringFromFile(projectFile);
                VideoProject project = ProjectSerializer.deserializeProject(projectJson);
                
                // Generate new project ID to avoid conflicts
                String newProjectId = UUID.randomUUID().toString();
                project.setProjectId(newProjectId);
                project.updateLastModified();
                
                // Save the imported project
                saveProject(project, callback);
                
            } catch (Exception e) {
                callback.onError("Failed to import project: " + e.getMessage());
            }
        });
    }
    
    /**
     * Gets recent projects from database
     */
    public LiveData<List<ProjectMetadata>> getRecentProjects() {
        return database.projectDao().getAllProjects();
    }
    
    /**
     * Gets recent projects with limit
     */
    public LiveData<List<ProjectMetadata>> getRecentProjects(int limit) {
        return database.projectDao().getRecentProjects(limit);
    }
    
    /**
     * Searches projects by name
     */
    public LiveData<List<ProjectMetadata>> searchProjects(String query) {
        return database.projectDao().searchProjects("%" + query + "%");
    }
    
    /**
     * Gets the project directory for a specific project
     */
    public File getProjectDirectory(String projectId) {
        return new File(projectsDirectory, projectId);
    }
    
    /**
     * Gets the thumbnail file for a project
     */
    public File getThumbnailFile(String projectId) {
        return new File(getProjectDirectory(projectId), THUMBNAIL_FILE_NAME);
    }
    
    // Helper methods
    
    private ProjectMetadata createMetadataFromProject(VideoProject project) {
        ProjectMetadata metadata = new ProjectMetadata();
        metadata.setProjectId(project.getProjectId());
        metadata.setProjectName(project.getProjectName());
        metadata.setCreatedAt(project.getCreatedAt());
        metadata.setLastModified(project.getLastModified());
        metadata.setDuration(project.getDuration());
        metadata.setFileSize(mediaReferenceManager.getFileSize(project.getOriginalVideoPath()));
        metadata.setOriginalVideoName(mediaReferenceManager.getDisplayName(project.getOriginalVideoPath()));
        metadata.setThumbnailPath(getThumbnailFile(project.getProjectId()).getAbsolutePath());
        metadata.setHasUnsavedChanges(project.hasUnsavedChanges());
        return metadata;
    }
    
    private void writeStringToFile(File file, String content) throws IOException {
        try (FileOutputStream fos = new FileOutputStream(file)) {
            fos.write(content.getBytes(StandardCharsets.UTF_8));
        }
    }
    
    private String readStringFromFile(File file) throws IOException {
        byte[] bytes = new byte[(int) file.length()];
        try (FileInputStream fis = new FileInputStream(file)) {
            fis.read(bytes);
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }
    
    private void copyFile(File source, File destination) throws IOException {
        try (FileInputStream fis = new FileInputStream(source);
             FileOutputStream fos = new FileOutputStream(destination)) {
            
            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = fis.read(buffer)) != -1) {
                fos.write(buffer, 0, bytesRead);
            }
        }
    }
    
    private void deleteDirectory(File directory) {
        if (directory.isDirectory()) {
            File[] files = directory.listFiles();
            if (files != null) {
                for (File file : files) {
                    deleteDirectory(file);
                }
            }
        }
        directory.delete();
    }
    
    /**
     * Cleanup resources
     */
    public void shutdown() {
        executorService.shutdown();
    }
}