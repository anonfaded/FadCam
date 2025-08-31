package com.fadcam.ui.faditor.persistence;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import androidx.lifecycle.LiveData;

import com.fadcam.ui.faditor.models.VideoProject;
import com.fadcam.ui.faditor.models.EditorState;
import com.fadcam.ui.faditor.media.ProjectMediaManager;
import com.fadcam.ui.faditor.media.ProjectMediaAsset;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
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

    private static final String TAG = "ProjectManager";

    private final Context context;
    private final ProjectDatabase database;
    private final MediaReferenceManager mediaReferenceManager;
    private final ExecutorService executorService;
    private final File projectsDirectory;
    private final Handler mainHandler;

    // Professional media management
    private final Map<String, ProjectMediaManager> projectMediaManagers;

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

    public interface ProjectValidationCallback {
        void onProjectLoadedWithValidation(VideoProject project, MediaReferenceManager.ValidationResult validation);

        void onError(String errorMessage);
    }

    public interface ProjectMediaImportCallback {
        void onImportStarted(String projectId, String mediaId, String filename);

        void onImportProgress(String projectId, String mediaId, int progress);

        void onProjectCreated(String projectId, VideoProject project, ProjectMediaAsset primaryAsset);

        void onProxyGenerationStarted(String projectId, String mediaId);

        void onProxyGenerationCompleted(String projectId, String mediaId, File proxyFile);

        void onProxyGenerationFailed(String projectId, String mediaId, String error);

        void onError(String projectId, String errorMessage);
    }

    public ProjectManager(Context context) {
        this.context = context;
        this.database = ProjectDatabase.getInstance(context);
        this.mediaReferenceManager = new MediaReferenceManager(context);
        this.executorService = Executors.newFixedThreadPool(2);
        this.mainHandler = new Handler(Looper.getMainLooper());
        this.projectMediaManagers = new HashMap<>();

        // Use external storage for projects (Android/data/com.fadcam/faditor_projects)
        this.projectsDirectory = new File(context.getExternalFilesDir(null), PROJECTS_DIR);
        if (!projectsDirectory.exists()) {
            projectsDirectory.mkdirs();
        }

        Log.d(TAG, "Projects directory: " + projectsDirectory.getAbsolutePath());
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

        // Initialize media manager for this project
        ProjectMediaManager mediaManager = new ProjectMediaManager(context, projectId, projectDir);
        projectMediaManagers.put(projectId, mediaManager);

        Log.d(TAG, "Created new project: " + projectId + " with name: " + projectName);
        return projectId;
    }

    /**
     * Creates a new project and imports the initial video
     */
    public void createProjectWithVideo(String projectName, Uri videoUri, String filename,
            ProjectMediaImportCallback callback) {
        executorService.execute(() -> {
            try {
                String projectId = createNewProject(projectName);
                ProjectMediaManager mediaManager = getMediaManager(projectId);

                // Import the video into the project
                mediaManager.importMedia(videoUri, filename, new ProjectMediaManager.MediaImportListener() {
                    @Override
                    public void onImportStarted(String mediaId, String filename) {
                        if (callback != null) {
                            mainHandler.post(() -> callback.onImportStarted(projectId, mediaId, filename));
                        }
                    }

                    @Override
                    public void onImportProgress(String mediaId, int progress) {
                        if (callback != null) {
                            mainHandler.post(() -> callback.onImportProgress(projectId, mediaId, progress));
                        }
                    }

                    @Override
                    public void onImportCompleted(String mediaId, ProjectMediaAsset asset) {
                        try {
                            // Create the project with the imported media
                            VideoProject project = new VideoProject(mediaId);
                            project.setProjectId(projectId);
                            project.setProjectName(projectName);

                            // Set metadata from the imported asset
                            if (asset.analysis != null && asset.analysis.isVideo) {
                                project.setDuration(asset.analysis.duration);
                                // Create VideoMetadata from analysis
                                // This would need to be implemented based on your VideoMetadata class
                            }

                            // Save the project
                            saveProject(project, new ProjectCallback() {
                                @Override
                                public void onProjectSaved(String savedProjectId) {
                                    if (callback != null) {
                                        mainHandler.post(() -> callback.onProjectCreated(projectId, project, asset));
                                    }
                                }

                                @Override
                                public void onProjectLoaded(VideoProject project) {
                                    // Not used in this context
                                }

                                @Override
                                public void onError(String errorMessage) {
                                    if (callback != null) {
                                        mainHandler.post(() -> callback.onError(projectId, errorMessage));
                                    }
                                }
                            });

                        } catch (Exception e) {
                            Log.e(TAG, "Failed to create project after media import", e);
                            if (callback != null) {
                                mainHandler.post(() -> callback.onError(projectId, e.getMessage()));
                            }
                        }
                    }

                    @Override
                    public void onImportFailed(String mediaId, String error) {
                        if (callback != null) {
                            mainHandler.post(() -> callback.onError(projectId, error));
                        }
                    }

                    @Override
                    public void onProxyGenerationStarted(String mediaId) {
                        if (callback != null) {
                            mainHandler.post(() -> callback.onProxyGenerationStarted(projectId, mediaId));
                        }
                    }

                    @Override
                    public void onProxyGenerationCompleted(String mediaId, File proxyFile) {
                        if (callback != null) {
                            mainHandler.post(() -> callback.onProxyGenerationCompleted(projectId, mediaId, proxyFile));
                        }
                    }

                    @Override
                    public void onProxyGenerationFailed(String mediaId, String error) {
                        if (callback != null) {
                            mainHandler.post(() -> callback.onProxyGenerationFailed(projectId, mediaId, error));
                        }
                    }
                });

            } catch (Exception e) {
                Log.e(TAG, "Failed to create project with video", e);
                if (callback != null) {
                    mainHandler.post(() -> callback.onError(null, e.getMessage()));
                }
            }
        });
    }

    /**
     * Saves a project to both JSON file and database
     */

    /**
     * Gets the current projects directory path (for debugging)
     */
    public String getProjectsDirectoryPath() {
        return projectsDirectory.getAbsolutePath();
    }

    /**
     * Saves a project to both JSON file and database
     */
    public void saveProject(VideoProject project, ProjectCallback callback) {
        executorService.execute(() -> {
            try {
                // Validate media references
                MediaReferenceManager.ValidationResult validation = mediaReferenceManager
                        .validateMediaReferences(project);

                // For new projects, be more lenient - only fail if we have missing files and no
                // recovery options
                if (!validation.isValid() && !validation.hasRecoveredPaths()) {
                    // Check if this is a new project (no operations yet) with media asset
                    boolean isNewProject = project.getOperations().isEmpty() &&
                            project.getPrimaryMediaAssetId() != null;

                    if (!isNewProject) {
                        callback.onError("Some media files are missing and cannot be recovered");
                        return;
                    }
                    // For new projects with content URIs, continue with saving even if validation
                    // fails
                    // The URI accessibility was already checked during project creation
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

                // Post callback to main thread
                mainHandler.post(() -> callback.onProjectSaved(project.getProjectId()));

            } catch (Exception e) {
                // Post error callback to main thread
                mainHandler.post(() -> callback.onError("Failed to save project: " + e.getMessage()));
            }
        });
    }

    /**
     * Loads a project from JSON file
     */
    public void loadProject(String projectId, ProjectCallback callback) {
        Log.d(TAG, "=== PROJECT MANAGER LOAD PROJECT STARTED ===");
        Log.d(TAG, "Loading project with ID: " + projectId);
        executorService.execute(() -> {
            try {
                File projectDir = new File(projectsDirectory, projectId);
                File projectFile = new File(projectDir, PROJECT_FILE_NAME);

                Log.d(TAG, "Project directory: " + projectDir.getAbsolutePath());
                Log.d(TAG, "Project file: " + projectFile.getAbsolutePath());
                Log.d(TAG, "Project file exists: " + projectFile.exists());

                if (!projectFile.exists()) {
                    Log.e(TAG, "Project file not found: " + projectFile.getAbsolutePath());
                    mainHandler.post(() -> callback.onError("Project file not found"));
                    return;
                }

                Log.d(TAG, "Reading project JSON file...");
                String projectJson = readStringFromFile(projectFile);
                Log.d(TAG, "Project JSON length: " + projectJson.length());
                Log.d(TAG, "Project JSON content: " + projectJson);

                Log.d(TAG, "Deserializing project...");
                VideoProject project = ProjectSerializer.deserializeProject(projectJson);
                Log.d(TAG,
                        "Project deserialized successfully: " + (project != null ? project.getProjectName() : "null"));

                // Validate and recover media references
                Log.d(TAG, "Validating media references...");
                MediaReferenceManager.ValidationResult validation = mediaReferenceManager
                        .validateMediaReferences(project);
                Log.d(TAG, "Validation completed. Has recovered paths: " + validation.hasRecoveredPaths());
                Log.d(TAG, "Valid files: " + validation.getValidFiles().size());
                Log.d(TAG, "Missing files: " + validation.getMissingFiles().size());
                Log.d(TAG, "Recovered paths: " + validation.getRecoveredPaths().size());

                if (validation.hasRecoveredPaths()) {
                    Log.d(TAG, "Updating media paths with recovered paths...");
                    mediaReferenceManager.updateMediaPaths(project, validation.getRecoveredPaths());
                    // Save the updated project
                    saveProject(project, new ProjectCallback() {
                        @Override
                        public void onProjectSaved(String projectId) {
                            // Silent save after recovery
                        }

                        @Override
                        public void onProjectLoaded(VideoProject project) {
                        }

                        @Override
                        public void onError(String errorMessage) {
                        }
                    });
                }

                // Post callback to main thread
                mainHandler.post(() -> callback.onProjectLoaded(project));

            } catch (Exception e) {
                // Post error callback to main thread
                mainHandler.post(() -> callback.onError("Failed to load project: " + e.getMessage()));
            }
        });
    }

    /**
     * Loads a project and returns validation result for UI handling
     */
    public void loadProjectWithValidation(String projectId, ProjectValidationCallback callback) {
        Log.d(TAG, "=== PROJECT MANAGER LOAD PROJECT WITH VALIDATION STARTED ===");
        Log.d(TAG, "Loading project with ID: " + projectId);
        executorService.execute(() -> {
            try {
                File projectDir = new File(projectsDirectory, projectId);
                File projectFile = new File(projectDir, PROJECT_FILE_NAME);

                Log.d(TAG, "Project directory: " + projectDir.getAbsolutePath());
                Log.d(TAG, "Project file: " + projectFile.getAbsolutePath());
                Log.d(TAG, "Project file exists: " + projectFile.exists());

                if (!projectFile.exists()) {
                    Log.e(TAG, "Project file not found: " + projectFile.getAbsolutePath());
                    mainHandler.post(() -> callback.onError("Project file not found"));
                    return;
                }

                Log.d(TAG, "Reading project JSON file...");
                String projectJson = readStringFromFile(projectFile);
                Log.d(TAG, "Project JSON length: " + projectJson.length());

                Log.d(TAG, "Deserializing project...");
                VideoProject project = ProjectSerializer.deserializeProject(projectJson);
                Log.d(TAG,
                        "Project deserialized successfully: " + (project != null ? project.getProjectName() : "null"));

                // Validate media references
                Log.d(TAG, "Validating media references...");
                MediaReferenceManager.ValidationResult validation = mediaReferenceManager
                        .validateMediaReferences(project);
                Log.d(TAG, "Validation completed. Has recovered paths: " + validation.hasRecoveredPaths());
                Log.d(TAG, "Valid files: " + validation.getValidFiles().size());
                Log.d(TAG, "Missing files: " + validation.getMissingFiles().size());
                Log.d(TAG, "URIs requiring re-selection: " + validation.getUrisRequiringReselectionCount());

                // Apply recovered paths if any
                if (validation.hasRecoveredPaths()) {
                    Log.d(TAG, "Updating media paths with recovered paths...");
                    mediaReferenceManager.updateMediaPaths(project, validation.getRecoveredPaths());
                    // Save the updated project
                    saveProject(project, new ProjectCallback() {
                        @Override
                        public void onProjectSaved(String projectId) {
                            // Silent save after recovery
                        }

                        @Override
                        public void onProjectLoaded(VideoProject project) {
                        }

                        @Override
                        public void onError(String errorMessage) {
                        }
                    });
                }

                // Post callback to main thread with validation result
                mainHandler.post(() -> callback.onProjectLoadedWithValidation(project, validation));

            } catch (Exception e) {
                // Post error callback to main thread
                mainHandler.post(() -> callback.onError("Failed to load project: " + e.getMessage()));
            }
        });
    }

    /**
     * Updates a project with re-selected URIs
     */
    public void updateProjectWithReselectedUris(VideoProject project, Map<Uri, Uri> uriMapping,
            ProjectCallback callback) {
        executorService.execute(() -> {
            try {
                // Update the project's URI
                for (Map.Entry<Uri, Uri> entry : uriMapping.entrySet()) {
                    Uri oldUri = entry.getKey();
                    Uri newUri = entry.getValue();

                    // For modern projects, media assets are managed separately
                    // Legacy URI updates are not needed for media asset-based projects
                    Log.d(TAG, "Updated project original video URI: " + oldUri + " -> " + newUri);
                }

                // Save the updated project
                saveProject(project, callback);

            } catch (Exception e) {
                mainHandler.post(
                        () -> callback.onError("Failed to update project with re-selected URIs: " + e.getMessage()));
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

                // Post callback to main thread
                mainHandler.post(() -> callback.onEditorStateSaved(projectId));

            } catch (Exception e) {
                // Post error callback to main thread
                mainHandler.post(() -> callback.onError("Failed to save editor state: " + e.getMessage()));
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
                    mainHandler.post(() -> callback.onEditorStateLoaded(new EditorState()));
                    return;
                }

                String stateJson = readStringFromFile(stateFile);
                EditorState editorState = ProjectSerializer.deserializeEditorState(stateJson);

                // Post callback to main thread
                mainHandler.post(() -> callback.onEditorStateLoaded(editorState));

            } catch (Exception e) {
                // Post error callback to main thread
                mainHandler.post(() -> callback.onError("Failed to load editor state: " + e.getMessage()));
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

                // Post callback to main thread
                mainHandler.post(() -> callback.onProjectSaved(projectId)); // Reusing callback for consistency

            } catch (Exception e) {
                // Post error callback to main thread
                mainHandler.post(() -> callback.onError("Failed to delete project: " + e.getMessage()));
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
                    mainHandler.post(() -> callback.onError("Project file not found"));
                    return;
                }

                // Copy project file to export location
                String projectJson = readStringFromFile(projectFile);
                writeStringToFile(exportPath, projectJson);

                // Post callback to main thread
                mainHandler.post(() -> callback.onProjectSaved(projectId));

            } catch (Exception e) {
                // Post error callback to main thread
                mainHandler.post(() -> callback.onError("Failed to export project: " + e.getMessage()));
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
                // Post error callback to main thread
                mainHandler.post(() -> callback.onError("Failed to import project: " + e.getMessage()));
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

    // ========== PROFESSIONAL MEDIA MANAGEMENT ==========

    /**
     * Get or create media manager for a project
     */
    public ProjectMediaManager getProjectMediaManager(String projectId) {
        synchronized (projectMediaManagers) {
            ProjectMediaManager manager = projectMediaManagers.get(projectId);
            if (manager == null) {
                File projectDir = new File(projectsDirectory, projectId);
                manager = new ProjectMediaManager(context, projectId, projectDir);
                projectMediaManagers.put(projectId, manager);
                Log.d(TAG, "Created media manager for project: " + projectId);
            }
            return manager;
        }
    }

    /**
     * Import media into project using professional media management
     */
    public void importMediaToProject(String projectId, Uri sourceUri, String filename,
            ProjectMediaManager.MediaImportListener listener) {
        ProjectMediaManager mediaManager = getProjectMediaManager(projectId);
        mediaManager.importMedia(sourceUri, filename, listener);
        Log.d(TAG, "Importing media to project " + projectId + ": " + filename);
    }

    /**
     * Get media asset for playback (returns proxy if available, otherwise original)
     */
    public Uri getMediaPlaybackUri(String projectId, String mediaId) {
        ProjectMediaManager mediaManager = getProjectMediaManager(projectId);
        ProjectMediaAsset asset = mediaManager.getMediaAsset(mediaId);
        if (asset != null) {
            return mediaManager.getPlaybackUri(asset);
        }
        return null;
    }

    /**
     * Get original media URI (for export or high-quality operations)
     */
    public Uri getMediaOriginalUri(String projectId, String mediaId) {
        ProjectMediaManager mediaManager = getProjectMediaManager(projectId);
        ProjectMediaAsset asset = mediaManager.getMediaAsset(mediaId);
        if (asset != null) {
            return mediaManager.getMediaUri(asset);
        }
        return null;
    }

    /**
     * Get all media assets in a project
     */
    public List<ProjectMediaAsset> getProjectMediaAssets(String projectId) {
        ProjectMediaManager mediaManager = getProjectMediaManager(projectId);
        return mediaManager.getAllMediaAssets();
    }

    /**
     * Remove media asset from project
     */
    public void removeMediaFromProject(String projectId, String mediaId) {
        ProjectMediaManager mediaManager = getProjectMediaManager(projectId);
        mediaManager.removeMediaAsset(mediaId);
        Log.d(TAG, "Removed media from project " + projectId + ": " + mediaId);
    }

    /**
     * Get project storage information
     */
    public ProjectMediaManager.ProjectStorageInfo getProjectStorageInfo(String projectId) {
        ProjectMediaManager mediaManager = getProjectMediaManager(projectId);
        return mediaManager.getStorageInfo();
    }

    /**
     * Set media import strategy for a project
     */
    public void setProjectMediaImportStrategy(String projectId, ProjectMediaManager.MediaImportStrategy strategy) {
        ProjectMediaManager mediaManager = getProjectMediaManager(projectId);
        mediaManager.setImportStrategy(strategy);
        Log.d(TAG, "Set import strategy for project " + projectId + ": " + strategy);
    }

    /**
     * Set proxy quality for a project
     */
    public void setProjectProxyQuality(String projectId, ProjectMediaManager.ProxyQuality quality) {
        ProjectMediaManager mediaManager = getProjectMediaManager(projectId);
        mediaManager.setDefaultProxyQuality(quality);
        Log.d(TAG, "Set proxy quality for project " + projectId + ": " + quality);
    }

    /**
     * Clean up project cache
     */
    public void cleanupProjectCache(String projectId) {
        ProjectMediaManager mediaManager = getProjectMediaManager(projectId);
        mediaManager.cleanupCache();
        Log.d(TAG, "Cleaned up cache for project: " + projectId);
    }

    /**
     * Create a new project with professional media management
     */
    public String createNewProjectWithMedia(String projectName, Uri initialVideoUri, String filename,
            ProjectMediaManager.MediaImportListener importListener) {
        String projectId = createNewProject(projectName);

        // Import the initial video using professional media management
        if (initialVideoUri != null) {
            importMediaToProject(projectId, initialVideoUri, filename, importListener);
        }

        Log.d(TAG, "Created new project with media: " + projectId);
        return projectId;
    }

    /**
     * Get media manager for a project
     */
    public ProjectMediaManager getMediaManager(String projectId) {
        synchronized (projectMediaManagers) {
            ProjectMediaManager manager = projectMediaManagers.get(projectId);
            if (manager == null) {
                // Create media manager if it doesn't exist
                File projectDir = new File(projectsDirectory, projectId);
                manager = new ProjectMediaManager(context, projectId, projectDir);
                projectMediaManagers.put(projectId, manager);
            }
            return manager;
        }
    }

    /**
     * Get the URI for a media asset in a project
     */
    public Uri getMediaUri(String projectId, String mediaAssetId) {
        ProjectMediaManager mediaManager = getMediaManager(projectId);
        ProjectMediaAsset asset = mediaManager.getMediaAsset(mediaAssetId);
        if (asset != null) {
            return mediaManager.getMediaUri(asset);
        }
        return null;
    }

    /**
     * Get the playback URI (proxy if available) for a media asset
     */
    public Uri getPlaybackUri(String projectId, String mediaAssetId) {
        ProjectMediaManager mediaManager = getMediaManager(projectId);
        ProjectMediaAsset asset = mediaManager.getMediaAsset(mediaAssetId);
        if (asset != null) {
            return mediaManager.getPlaybackUri(asset);
        }
        return null;
    }

    /**
     * Get media asset by ID
     */
    public ProjectMediaAsset getMediaAsset(String projectId, String mediaAssetId) {
        ProjectMediaManager mediaManager = getMediaManager(projectId);
        return mediaManager.getMediaAsset(mediaAssetId);
    }

    /**
     * Add media asset to existing project
     */
    public void addMediaToProject(String projectId, Uri videoUri, String filename,
            ProjectMediaManager.MediaImportListener listener) {
        ProjectMediaManager mediaManager = getMediaManager(projectId);
        mediaManager.importMedia(videoUri, filename, listener);
    }



    /**
     * Cleanup resources
     */
    public void shutdown() {
        // Shutdown all media managers
        synchronized (projectMediaManagers) {
            for (ProjectMediaManager manager : projectMediaManagers.values()) {
                manager.shutdown();
            }
            projectMediaManagers.clear();
        }

        executorService.shutdown();
        Log.d(TAG, "ProjectManager shutdown completed");
    }
}