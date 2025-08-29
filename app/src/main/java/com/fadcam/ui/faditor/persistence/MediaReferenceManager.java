package com.fadcam.ui.faditor.persistence;

import android.content.Context;
import android.net.Uri;
import android.provider.MediaStore;
import android.database.Cursor;

import com.fadcam.ui.faditor.models.VideoProject;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

/**
 * Manages media file references and handles path validation and recovery
 */
public class MediaReferenceManager {
    
    private final Context context;
    
    public MediaReferenceManager(Context context) {
        this.context = context;
    }
    
    /**
     * Validates that all media references in a project are still accessible
     */
    public ValidationResult validateMediaReferences(VideoProject project) {
        ValidationResult result = new ValidationResult();
        
        // Check original video path/URI
        String originalPath = project.getOriginalVideoPath();
        Uri originalUri = project.getOriginalVideoUri();
        
        if (originalPath != null) {
            // Check if it's a content URI (starts with content://)
            if (originalPath.startsWith("content://")) {
                // For content URIs, validate using the URI directly
                if (originalUri != null) {
                    if (isContentUriAccessible(originalUri)) {
                        result.addValidFile(originalPath);
                    } else {
                        result.addMissingFile(originalPath);
                    }
                } else {
                    // Try to parse the path as URI
                    try {
                        Uri parsedUri = Uri.parse(originalPath);
                        if (isContentUriAccessible(parsedUri)) {
                            result.addValidFile(originalPath);
                        } else {
                            result.addMissingFile(originalPath);
                        }
                    } catch (Exception e) {
                        result.addMissingFile(originalPath);
                    }
                }
            } else {
                // For file paths, use the existing file validation
                File file = new File(originalPath);
                if (!file.exists() || !file.canRead()) {
                    result.addMissingFile(originalPath);
                    
                    // Try to find the file by URI if available
                    if (originalUri != null) {
                        String recoveredPath = tryRecoverPathFromUri(originalUri);
                        if (recoveredPath != null) {
                            result.addRecoveredPath(originalPath, recoveredPath);
                        }
                    }
                } else {
                    result.addValidFile(originalPath);
                }
            }
        }
        
        // Check working file if exists
        if (project.getWorkingFile() != null) {
            File workingFile = project.getWorkingFile();
            if (!workingFile.exists() || !workingFile.canRead()) {
                result.addMissingFile(workingFile.getAbsolutePath());
            } else {
                result.addValidFile(workingFile.getAbsolutePath());
            }
        }
        
        return result;
    }
    
    /**
     * Checks if a content URI is still accessible
     */
    private boolean isContentUriAccessible(Uri uri) {
        if (uri == null) return false;
        
        try {
            // Try to open an input stream to check if the URI is accessible
            context.getContentResolver().openInputStream(uri).close();
            return true;
        } catch (Exception e) {
            // URI is not accessible
            return false;
        }
    }
    
    /**
     * Attempts to recover a file path from a content URI
     */
    private String tryRecoverPathFromUri(Uri uri) {
        if (uri == null) return null;
        
        try {
            // Try to get the real path from MediaStore
            String[] projection = {MediaStore.Video.Media.DATA};
            Cursor cursor = context.getContentResolver().query(uri, projection, null, null, null);
            
            if (cursor != null) {
                try {
                    if (cursor.moveToFirst()) {
                        int columnIndex = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATA);
                        String path = cursor.getString(columnIndex);
                        
                        // Verify the recovered path exists
                        if (path != null) {
                            File file = new File(path);
                            if (file.exists() && file.canRead()) {
                                return path;
                            }
                        }
                    }
                } finally {
                    cursor.close();
                }
            }
        } catch (Exception e) {
            // Failed to recover path
        }
        
        return null;
    }
    
    /**
     * Updates media paths in a project based on a path mapping
     */
    public void updateMediaPaths(VideoProject project, Map<String, String> pathMapping) {
        // Update original video path
        String originalPath = project.getOriginalVideoPath();
        if (originalPath != null && pathMapping.containsKey(originalPath)) {
            project.setOriginalVideoPath(pathMapping.get(originalPath));
        }
        
        // Update working file path
        if (project.getWorkingFile() != null) {
            String workingPath = project.getWorkingFile().getAbsolutePath();
            if (pathMapping.containsKey(workingPath)) {
                project.setWorkingFile(new File(pathMapping.get(workingPath)));
            }
        }
        
        // Mark project as modified
        project.updateLastModified();
    }
    
    /**
     * Creates a URI from a file path for storage in projects
     */
    public Uri createUriFromPath(String filePath) {
        if (filePath == null) return null;
        
        File file = new File(filePath);
        if (!file.exists()) return null;
        
        return Uri.fromFile(file);
    }
    
    /**
     * Gets the display name for a media file
     */
    public String getDisplayName(String filePath) {
        if (filePath == null) return "Unknown";
        
        File file = new File(filePath);
        return file.getName();
    }
    
    /**
     * Gets the file size for a media file
     */
    public long getFileSize(String filePath) {
        if (filePath == null) return 0;
        
        File file = new File(filePath);
        return file.exists() ? file.length() : 0;
    }
    
    /**
     * Result of media reference validation
     */
    public static class ValidationResult {
        private final Map<String, String> recoveredPaths = new HashMap<>();
        private final java.util.List<String> missingFiles = new java.util.ArrayList<>();
        private final java.util.List<String> validFiles = new java.util.ArrayList<>();
        
        public void addRecoveredPath(String originalPath, String recoveredPath) {
            recoveredPaths.put(originalPath, recoveredPath);
        }
        
        public void addMissingFile(String filePath) {
            missingFiles.add(filePath);
        }
        
        public void addValidFile(String filePath) {
            validFiles.add(filePath);
        }
        
        public boolean isValid() {
            return missingFiles.isEmpty();
        }
        
        public boolean hasRecoveredPaths() {
            return !recoveredPaths.isEmpty();
        }
        
        public Map<String, String> getRecoveredPaths() {
            return recoveredPaths;
        }
        
        public java.util.List<String> getMissingFiles() {
            return missingFiles;
        }
        
        public java.util.List<String> getValidFiles() {
            return validFiles;
        }
        
        public int getTotalFiles() {
            return validFiles.size() + missingFiles.size();
        }
        
        public int getValidFileCount() {
            return validFiles.size();
        }
        
        public int getMissingFileCount() {
            return missingFiles.size();
        }
    }
}