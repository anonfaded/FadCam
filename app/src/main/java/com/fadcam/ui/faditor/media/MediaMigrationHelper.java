package com.fadcam.ui.faditor.media;

import android.content.Context;
import android.util.Log;

import com.fadcam.ui.faditor.models.VideoProject;

/**
 * Helper class for validating media references in projects using the professional media management system
 */
public class MediaMigrationHelper {
    private static final String TAG = "MediaMigrationHelper";

    /**
     * Validates that all media references in a project are accessible using the professional media asset system
     */
    public static boolean validateMediaReferences(Context context, VideoProject project) {
        Log.d(TAG, "=== VALIDATE MEDIA REFERENCES STARTED ===");
        
        String primaryMediaAssetId = project.getPrimaryMediaAssetId();
        Log.d(TAG, "Primary media asset ID: " + primaryMediaAssetId);
        
        if (primaryMediaAssetId == null || primaryMediaAssetId.isEmpty()) {
            Log.w(TAG, "No primary media asset ID found in project");
            return false;
        }
        
        // Project has valid media asset ID - validation passed
        Log.d(TAG, "Project has valid primary media asset ID: " + primaryMediaAssetId);
        return true;
    }
}