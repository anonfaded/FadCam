package com.fadcam.ui.faditor.media;

import android.content.Context;
import android.net.Uri;
import com.fadcam.Log;
import com.fadcam.ui.faditor.models.VideoProject;
import com.fadcam.ui.faditor.persistence.ProjectManager;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Helper class to migrate existing projects from URI-based media references
 * to professional media management system.
 */
public class MediaMigrationHelper {
    private static final String TAG = "MediaMigrationHelper";
    
    /**
     * Migrate an existing project to use professional media management
     */
    public static boolean migrateProjectToMediaManagement(Context context, VideoProject project, 
                                                         ProjectManager projectManager) {
        Log.d(TAG, "Migrating project to professional media management: " + project.getProjectId());
        
        try {
            String originalVideoPath = project.getOriginalVideoPath();
            if (originalVideoPath == null || originalVideoPath.isEmpty()) {
                Log.w(TAG, "No original video path to migrate");
                return true; // Nothing to migrate
            }
            
            Uri originalUri = Uri.parse(originalVideoPath);
            String filename = extractFilenameFromUri(originalUri);
            
            // Import the video using professional media management
            CountDownLatch importLatch = new CountDownLatch(1);
            AtomicReference<String> importedMediaId = new AtomicReference<>();
            AtomicReference<String> importError = new AtomicReference<>();
            
            ProjectMediaManager.MediaImportListener importListener = new ProjectMediaManager.MediaImportListener() {
                @Override
                public void onImportStarted(String mediaId, String filename) {
                    Log.d(TAG, "Migration import started: " + mediaId);
                }
                
                @Override
                public void onImportProgress(String mediaId, int progress) {
                    Log.d(TAG, "Migration import progress: " + progress + "%");
                }
                
                @Override
                public void onImportCompleted(String mediaId, ProjectMediaAsset asset) {
                    Log.d(TAG, "Migration import completed: " + mediaId);
                    importedMediaId.set(mediaId);
                    importLatch.countDown();
                }
                
                @Override
                public void onImportFailed(String mediaId, String error) {
                    Log.e(TAG, "Migration import failed: " + error);
                    importError.set(error);
                    importLatch.countDown();
                }
                
                @Override
                public void onProxyGenerationStarted(String mediaId) {
                    Log.d(TAG, "Migration proxy generation started: " + mediaId);
                }
                
                @Override
                public void onProxyGenerationCompleted(String mediaId, java.io.File proxyFile) {
                    Log.d(TAG, "Migration proxy generation completed: " + mediaId);
                }
                
                @Override
                public void onProxyGenerationFailed(String mediaId, String error) {
                    Log.w(TAG, "Migration proxy generation failed: " + error);
                    // Don't fail the migration for proxy generation failures
                }
            };
            
            // Start the import
            projectManager.importMediaToProject(project.getProjectId(), originalUri, filename, importListener);
            
            // Wait for import to complete (with timeout)
            boolean completed = importLatch.await(30, TimeUnit.SECONDS);
            
            if (!completed) {
                Log.e(TAG, "Migration import timed out");
                return false;
            }
            
            if (importError.get() != null) {
                Log.e(TAG, "Migration failed: " + importError.get());
                return false;
            }
            
            String mediaId = importedMediaId.get();
            if (mediaId == null) {
                Log.e(TAG, "Migration completed but no media ID returned");
                return false;
            }
            
            // Update the project to reference the new media asset
            project.setMediaAssetId(mediaId);
            project.setOriginalVideoPath(null); // Clear old URI reference
            
            Log.d(TAG, "Project migration completed successfully. Media ID: " + mediaId);
            return true;
            
        } catch (Exception e) {
            Log.e(TAG, "Migration failed with exception", e);
            return false;
        }
    }
    
    /**
     * Check if a project needs migration to professional media management
     */
    public static boolean needsMigration(VideoProject project) {
        // Project needs migration if it has an original video path but no media asset ID
        return project.getOriginalVideoPath() != null && 
               !project.getOriginalVideoPath().isEmpty() &&
               (project.getMediaAssetId() == null || project.getMediaAssetId().isEmpty());
    }
    
    /**
     * Get the appropriate URI for a project (handles both old and new systems)
     */
    public static Uri getProjectVideoUri(VideoProject project, ProjectManager projectManager) {
        // If project has been migrated to professional media management
        if (project.getMediaAssetId() != null && !project.getMediaAssetId().isEmpty()) {
            Uri mediaUri = projectManager.getMediaPlaybackUri(project.getProjectId(), project.getMediaAssetId());
            if (mediaUri != null) {
                Log.d(TAG, "Using professional media management URI: " + mediaUri);
                return mediaUri;
            }
        }
        
        // Fall back to old system
        if (project.getOriginalVideoPath() != null && !project.getOriginalVideoPath().isEmpty()) {
            Uri fallbackUri = Uri.parse(project.getOriginalVideoPath());
            Log.d(TAG, "Using fallback URI: " + fallbackUri);
            return fallbackUri;
        }
        
        Log.w(TAG, "No video URI available for project: " + project.getProjectId());
        return null;
    }
    
    /**
     * Get the original quality URI for a project (for export)
     */
    public static Uri getProjectVideoOriginalUri(VideoProject project, ProjectManager projectManager) {
        // If project has been migrated to professional media management
        if (project.getMediaAssetId() != null && !project.getMediaAssetId().isEmpty()) {
            Uri originalUri = projectManager.getMediaOriginalUri(project.getProjectId(), project.getMediaAssetId());
            if (originalUri != null) {
                Log.d(TAG, "Using professional media management original URI: " + originalUri);
                return originalUri;
            }
        }
        
        // Fall back to old system
        if (project.getOriginalVideoPath() != null && !project.getOriginalVideoPath().isEmpty()) {
            Uri fallbackUri = Uri.parse(project.getOriginalVideoPath());
            Log.d(TAG, "Using fallback original URI: " + fallbackUri);
            return fallbackUri;
        }
        
        Log.w(TAG, "No original video URI available for project: " + project.getProjectId());
        return null;
    }
    
    /**
     * Migrate all existing projects in the background
     */
    public static void migrateAllProjects(Context context, ProjectManager projectManager, 
                                        MigrationProgressListener listener) {
        Log.d(TAG, "Starting migration of all existing projects");
        
        // This would be implemented to scan all projects and migrate them
        // For now, we'll just notify completion
        if (listener != null) {
            listener.onMigrationCompleted(0, 0);
        }
    }
    
    private static String extractFilenameFromUri(Uri uri) {
        String path = uri.getPath();
        if (path != null) {
            int lastSlash = path.lastIndexOf('/');
            if (lastSlash >= 0 && lastSlash < path.length() - 1) {
                return path.substring(lastSlash + 1);
            }
        }
        
        // Fallback to a generic name
        return "video_" + System.currentTimeMillis() + ".mp4";
    }
    
    public interface MigrationProgressListener {
        void onMigrationStarted(int totalProjects);
        void onProjectMigrated(String projectId, boolean success);
        void onMigrationCompleted(int successCount, int failureCount);
    }
}