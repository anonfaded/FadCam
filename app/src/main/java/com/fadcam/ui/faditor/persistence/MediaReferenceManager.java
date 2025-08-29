package com.fadcam.ui.faditor.persistence;

import android.content.Context;
import android.net.Uri;
import android.provider.MediaStore;
import android.database.Cursor;
import android.util.Log;

import com.fadcam.ui.faditor.models.VideoProject;
import com.fadcam.ui.faditor.UriReselectionDialog;

import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.ArrayList;

import androidx.fragment.app.FragmentManager;

/**
 * Manages media file references and handles path validation and recovery
 */
public class MediaReferenceManager {
    
    private static final String TAG = "MediaReferenceManager";
    
    private final Context context;
    
    public MediaReferenceManager(Context context) {
        this.context = context;
    }
    
    /**
     * Validates that all media references in a project are still accessible
     */
    public ValidationResult validateMediaReferences(VideoProject project) {
        Log.d(TAG, "=== VALIDATE MEDIA REFERENCES STARTED ===");
        ValidationResult result = new ValidationResult();
        
        // Check original video path/URI
        String originalPath = project.getOriginalVideoPath();
        Uri originalUri = project.getOriginalVideoUri();
        
        Log.d(TAG, "Original path: " + originalPath);
        Log.d(TAG, "Original URI: " + originalUri);
        
        if (originalPath != null) {
            // Check if it's a content URI (starts with content://)
            if (originalPath.startsWith("content://")) {
                Log.d(TAG, "Path is content URI, checking URI validity");
                // For content URIs, validate using the URI directly
                if (originalUri != null) {
                    if (isUriAccessibleToMediaPlayer(originalUri)) {
                        result.addValidFile(originalPath);
                    } else {
                        // Check if it's a permission issue that requires re-selection
                        if (requiresUriReselection(originalUri)) {
                            result.addUriRequiringReselection(originalPath, originalUri);
                            Log.w(TAG, "URI requires re-selection due to permission: " + originalUri);
                        } else {
                            result.addMissingFile(originalPath);
                        }
                    }
                } else {
                    Log.d(TAG, "URI is null, attempting to reconstruct from path");
                    // Try to parse the path as URI
                    try {
                        Uri parsedUri = Uri.parse(originalPath);
                        if (isUriAccessibleToMediaPlayer(parsedUri)) {
                            result.addValidFile(originalPath);
                            // Reconstruct the URI if it's null
                            if (originalUri == null) {
                                project.setOriginalVideoUri(parsedUri);
                                Log.d(TAG, "Reconstructed URI from path: " + parsedUri);
                            }
                        } else {
                            // Check if it's a permission issue
                            if (requiresUriReselection(parsedUri)) {
                                result.addUriRequiringReselection(originalPath, parsedUri);
                                Log.w(TAG, "Parsed URI requires re-selection due to permission: " + parsedUri);
                            } else {
                                result.addMissingFile(originalPath);
                            }
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Failed to parse URI from path: " + originalPath, e);
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
        }        // Check working file if exists
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
     * Checks if a content URI is accessible to MediaPlayer
     * This is more thorough than basic URI accessibility check
     */
    private boolean isUriAccessibleToMediaPlayer(Uri uri) {
        if (uri == null) return false;

        try {
            // First check basic accessibility
            if (!isContentUriAccessible(uri)) {
                return false;
            }

            // Additional MediaPlayer-specific checks
            String scheme = uri.getScheme();
            if (scheme == null) {
                Log.w(TAG, "URI has no scheme: " + uri);
                return false;
            }

            // For content URIs, verify the authority
            if ("content".equals(scheme)) {
                String authority = uri.getAuthority();
                if (authority == null) {
                    Log.w(TAG, "Content URI has no authority: " + uri);
                    return false;
                }

                // Check if it's a known media provider
                if (!authority.contains("media") && !authority.contains("externalstorage")) {
                    Log.w(TAG, "Unknown content provider authority: " + authority);
                    // Still allow it but log the warning
                }
            }

            // Try to get basic metadata to ensure MediaPlayer can access it
            android.media.MediaMetadataRetriever retriever = new android.media.MediaMetadataRetriever();
            try {
                retriever.setDataSource(context, uri);
                String duration = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION);
                if (duration == null) {
                    Log.w(TAG, "Could not extract duration from URI: " + uri);
                    return false;
                }
                Log.d(TAG, "MediaPlayer validation successful for URI: " + uri + ", duration: " + duration + "ms");
                return true;
            } catch (Exception e) {
                Log.w(TAG, "MediaMetadataRetriever failed for URI: " + uri, e);
                return false;
            } finally {
                try {
                    retriever.release();
                } catch (Exception e) {
                    // Ignore
                }
            }

        } catch (Exception e) {
            Log.w(TAG, "Error validating URI for MediaPlayer: " + uri, e);
            return false;
        }
    }

    /**
     * Checks if a URI requires re-selection due to permission issues
     */
    public boolean requiresUriReselection(Uri uri) {
        if (uri == null) return false;

        try {
            // Try to access the URI - if we get a SecurityException, it needs re-selection
            context.getContentResolver().openInputStream(uri).close();
            return false; // URI is accessible
        } catch (SecurityException e) {
            Log.w(TAG, "URI requires re-selection due to permission: " + uri, e);
            return true;
        } catch (Exception e) {
            Log.w(TAG, "URI access failed (non-permission issue): " + uri, e);
            return false; // Different error, not permission-related
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
            String newPath = pathMapping.get(originalPath);
            project.setOriginalVideoPath(newPath);
            
            // If the new path is a content URI and original URI is null, reconstruct it
            if (newPath.startsWith("content://") && project.getOriginalVideoUri() == null) {
                try {
                    Uri reconstructedUri = Uri.parse(newPath);
                    project.setOriginalVideoUri(reconstructedUri);
                    Log.d(TAG, "Reconstructed URI from updated path: " + reconstructedUri);
                } catch (Exception e) {
                    Log.w(TAG, "Failed to reconstruct URI from path: " + newPath);
                }
            }
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
     * Shows a dialog to re-select URIs that have expired permissions
     */
    public void showUriReselectionDialog(FragmentManager fragmentManager, ValidationResult validationResult,
            UriReselectionCallback callback) {
        if (!validationResult.hasUrisRequiringReselection()) {
            Log.d(TAG, "No URIs require re-selection");
            return;
        }

        List<Uri> urisToReselect = new ArrayList<>(validationResult.getUrisRequiringReselection().values());

        UriReselectionDialog dialog = UriReselectionDialog.newInstance(urisToReselect);
        dialog.setCallback(new UriReselectionDialog.UriReselectionCallback() {
            @Override
            public void onUriReselected(Uri oldUri, Uri newUri) {
                Log.d(TAG, "URI re-selected: " + oldUri + " -> " + newUri);
                if (callback != null) {
                    callback.onUriReselected(oldUri, newUri);
                }
            }

            @Override
            public void onUriSkipped(Uri uri) {
                Log.d(TAG, "URI re-selection skipped: " + uri);
                if (callback != null) {
                    callback.onUriSkipped(uri);
                }
            }

            @Override
            public void onReselectionCompleted() {
                Log.d(TAG, "URI re-selection completed");
                if (callback != null) {
                    callback.onReselectionCompleted();
                }
            }

            @Override
            public void onReselectionCancelled() {
                Log.d(TAG, "URI re-selection cancelled");
                if (callback != null) {
                    callback.onReselectionCancelled();
                }
            }
        });

        dialog.show(fragmentManager);
    }

    /**
     * Callback interface for URI re-selection operations
     */
    public interface UriReselectionCallback {
        void onUriReselected(Uri oldUri, Uri newUri);
        void onUriSkipped(Uri uri);
        void onReselectionCompleted();
        void onReselectionCancelled();
    }
    
    /**
     * Result of media reference validation
     */
    public static class ValidationResult {
        private final Map<String, String> recoveredPaths = new HashMap<>();
        private final java.util.List<String> missingFiles = new java.util.ArrayList<>();
        private final java.util.List<String> validFiles = new java.util.ArrayList<>();
        private final Map<String, Uri> urisRequiringReselection = new HashMap<>();
        
        public void addRecoveredPath(String originalPath, String recoveredPath) {
            recoveredPaths.put(originalPath, recoveredPath);
        }
        
        public void addMissingFile(String filePath) {
            missingFiles.add(filePath);
        }
        
        public void addValidFile(String filePath) {
            validFiles.add(filePath);
        }
        
        public void addUriRequiringReselection(String filePath, Uri uri) {
            urisRequiringReselection.put(filePath, uri);
        }
        
        public boolean isValid() {
            return missingFiles.isEmpty() && urisRequiringReselection.isEmpty();
        }
        
        public boolean hasRecoveredPaths() {
            return !recoveredPaths.isEmpty();
        }
        
        public boolean hasUrisRequiringReselection() {
            return !urisRequiringReselection.isEmpty();
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
        
        public Map<String, Uri> getUrisRequiringReselection() {
            return urisRequiringReselection;
        }
        
        public int getTotalFiles() {
            return validFiles.size() + missingFiles.size() + urisRequiringReselection.size();
        }
        
        public int getValidFileCount() {
            return validFiles.size();
        }
        
        public int getMissingFileCount() {
            return missingFiles.size();
        }
        
        public int getUrisRequiringReselectionCount() {
            return urisRequiringReselection.size();
        }
    }
}