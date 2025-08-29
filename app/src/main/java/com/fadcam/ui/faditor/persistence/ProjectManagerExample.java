package com.fadcam.ui.faditor.persistence;

import android.content.Context;
import android.net.Uri;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.Observer;

import com.fadcam.ui.faditor.models.VideoProject;
import com.fadcam.ui.faditor.models.VideoMetadata;
import com.fadcam.ui.faditor.models.EditorState;
import com.fadcam.ui.faditor.models.EditOperation;

import java.util.List;

/**
 * Example usage of the ProjectManager system
 * This demonstrates how to use the project management system in the Faditor Mini editor
 */
public class ProjectManagerExample {
    
    private ProjectManager projectManager;
    private Context context;
    
    public ProjectManagerExample(Context context) {
        this.context = context;
        this.projectManager = new ProjectManager(context);
    }
    
    /**
     * Example: Creating and saving a new project
     */
    public void createNewProjectExample() {
        // Create a new project
        String projectId = projectManager.createNewProject("My Video Edit");
        
        // Create a VideoProject instance
        VideoProject project = new VideoProject();
        project.setProjectId(projectId);
        project.setProjectName("My Video Edit");
        project.setOriginalVideoPath("/storage/emulated/0/DCIM/Camera/video.mp4");
        project.setDuration(60000); // 1 minute
        
        // Set video metadata
        VideoMetadata metadata = new VideoMetadata();
        metadata.setCodec("h264");
        metadata.setWidth(1920);
        metadata.setHeight(1080);
        metadata.setDuration(60000);
        metadata.setBitrate(8000000);
        metadata.setFrameRate(30.0f);
        project.setMetadata(metadata);
        
        // Add a trim operation
        EditOperation trimOperation = new EditOperation(EditOperation.Type.TRIM, 5000, 55000);
        project.addOperation(trimOperation);
        
        // Save the project
        projectManager.saveProject(project, new ProjectManager.ProjectCallback() {
            @Override
            public void onProjectSaved(String projectId) {
                System.out.println("Project saved successfully: " + projectId);
            }
            
            @Override
            public void onProjectLoaded(VideoProject project) {
                // Not used in save operation
            }
            
            @Override
            public void onError(String errorMessage) {
                System.err.println("Failed to save project: " + errorMessage);
            }
        });
    }
    
    /**
     * Example: Loading an existing project
     */
    public void loadProjectExample(String projectId) {
        projectManager.loadProject(projectId, new ProjectManager.ProjectCallback() {
            @Override
            public void onProjectSaved(String projectId) {
                // Not used in load operation
            }
            
            @Override
            public void onProjectLoaded(VideoProject project) {
                System.out.println("Project loaded: " + project.getProjectName());
                System.out.println("Duration: " + project.getDuration() + "ms");
                System.out.println("Operations: " + project.getOperations().size());
                
                // Use the loaded project in the editor
                initializeEditorWithProject(project);
            }
            
            @Override
            public void onError(String errorMessage) {
                System.err.println("Failed to load project: " + errorMessage);
            }
        });
    }
    
    /**
     * Example: Saving and loading editor state for auto-save functionality
     */
    public void autoSaveExample(String projectId) {
        // Create editor state
        EditorState editorState = new EditorState();
        editorState.setSelectedTool("TRIM");
        editorState.setPlaying(false);
        editorState.setLastPlayPosition(25000);
        editorState.setToolSetting("zoomLevel", 2.0f);
        
        // Save editor state (this would be called every 5 seconds during editing)
        projectManager.saveEditorState(projectId, editorState, new ProjectManager.EditorStateCallback() {
            @Override
            public void onEditorStateSaved(String projectId) {
                System.out.println("Editor state auto-saved for project: " + projectId);
            }
            
            @Override
            public void onEditorStateLoaded(EditorState editorState) {
                // Not used in save operation
            }
            
            @Override
            public void onError(String errorMessage) {
                System.err.println("Failed to auto-save editor state: " + errorMessage);
            }
        });
        
        // Load editor state (this would be called when reopening a project)
        projectManager.loadEditorState(projectId, new ProjectManager.EditorStateCallback() {
            @Override
            public void onEditorStateSaved(String projectId) {
                // Not used in load operation
            }
            
            @Override
            public void onEditorStateLoaded(EditorState editorState) {
                System.out.println("Editor state restored:");
                System.out.println("Selected tool: " + editorState.getSelectedTool());
                System.out.println("Last position: " + editorState.getLastPlayPosition());
                
                // Restore the editor UI state
                restoreEditorState(editorState);
            }
            
            @Override
            public void onError(String errorMessage) {
                System.err.println("Failed to load editor state: " + errorMessage);
            }
        });
    }
    
    /**
     * Example: Getting recent projects for the project browser
     */
    public void getRecentProjectsExample() {
        LiveData<List<ProjectMetadata>> recentProjects = projectManager.getRecentProjects(10);
        
        // Observe the LiveData (this would be done in a Fragment or Activity)
        recentProjects.observeForever(new Observer<List<ProjectMetadata>>() {
            @Override
            public void onChanged(List<ProjectMetadata> projects) {
                System.out.println("Recent projects updated: " + projects.size() + " projects");
                
                for (ProjectMetadata project : projects) {
                    System.out.println("- " + project.getProjectName() + 
                                     " (Duration: " + formatDuration(project.getDuration()) + ")");
                }
                
                // Update the project browser UI
                updateProjectBrowserUI(projects);
            }
        });
    }
    
    /**
     * Example: Searching projects
     */
    public void searchProjectsExample(String searchQuery) {
        LiveData<List<ProjectMetadata>> searchResults = projectManager.searchProjects(searchQuery);
        
        searchResults.observeForever(new Observer<List<ProjectMetadata>>() {
            @Override
            public void onChanged(List<ProjectMetadata> projects) {
                System.out.println("Search results for '" + searchQuery + "': " + projects.size() + " projects");
                
                // Update search results UI
                updateSearchResultsUI(projects);
            }
        });
    }
    
    /**
     * Example: Validating media references
     */
    public void validateMediaExample(VideoProject project) {
        MediaReferenceManager mediaManager = new MediaReferenceManager(context);
        MediaReferenceManager.ValidationResult result = mediaManager.validateMediaReferences(project);
        
        if (result.isValid()) {
            System.out.println("All media files are accessible");
        } else {
            System.out.println("Missing files: " + result.getMissingFileCount());
            
            if (result.hasRecoveredPaths()) {
                System.out.println("Recovered paths: " + result.getRecoveredPaths().size());
                // Update project with recovered paths
                mediaManager.updateMediaPaths(project, result.getRecoveredPaths());
            }
        }
    }
    
    // Helper methods that would be implemented in the actual UI components
    
    private void initializeEditorWithProject(VideoProject project) {
        // This would initialize the video player, timeline, and other editor components
        System.out.println("Initializing editor with project: " + project.getProjectName());
    }
    
    private void restoreEditorState(EditorState editorState) {
        // This would restore the editor UI to the saved state
        System.out.println("Restoring editor state...");
    }
    
    private void updateProjectBrowserUI(List<ProjectMetadata> projects) {
        // This would update the project browser RecyclerView
        System.out.println("Updating project browser with " + projects.size() + " projects");
    }
    
    private void updateSearchResultsUI(List<ProjectMetadata> projects) {
        // This would update the search results UI
        System.out.println("Updating search results with " + projects.size() + " projects");
    }
    
    private String formatDuration(long durationMs) {
        long seconds = durationMs / 1000;
        long minutes = seconds / 60;
        seconds = seconds % 60;
        return String.format("%d:%02d", minutes, seconds);
    }
    
    /**
     * Cleanup when done
     */
    public void cleanup() {
        projectManager.shutdown();
    }
}