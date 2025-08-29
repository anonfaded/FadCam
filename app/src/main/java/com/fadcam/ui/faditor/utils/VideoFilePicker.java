package com.fadcam.ui.faditor.utils;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.fragment.app.Fragment;

import com.fadcam.Log;

/**
 * Utility class for handling video file selection using the system file picker.
 * Provides a simple interface for launching file picker and handling results.
 */
public class VideoFilePicker {
    
    private static final String TAG = "VideoFilePicker";
    
    /**
     * Interface for handling video selection results
     */
    public interface VideoSelectionCallback {
        void onVideoSelected(Uri videoUri);
        void onSelectionCancelled();
        void onError(String errorMessage);
    }
    
    private final ActivityResultLauncher<String[]> filePickerLauncher;
    private VideoSelectionCallback callback;
    
    /**
     * Constructor for use with Fragment
     */
    public VideoFilePicker(Fragment fragment) {
        this.filePickerLauncher = fragment.registerForActivityResult(
            new ActivityResultContracts.OpenDocument(),
            this::handleFilePickerResult
        );
    }
    
    /**
     * Launch the video file picker
     */
    public void pickVideo(VideoSelectionCallback callback) {
        this.callback = callback;
        
        try {
            // Launch file picker with video MIME types
            filePickerLauncher.launch(new String[]{
                "video/mp4",
                "video/quicktime", 
                "video/x-msvideo",
                "video/x-matroska",
                "video/3gpp",
                "video/webm",
                "video/*"
            });
        } catch (Exception e) {
            Log.e(TAG, "Error launching file picker: " + e.getMessage());
            if (callback != null) {
                callback.onError("Failed to open file picker: " + e.getMessage());
            }
        }
    }
    
    /**
     * Handle the result from the file picker
     */
    private void handleFilePickerResult(Uri uri) {
        if (callback == null) {
            Log.w(TAG, "No callback set for file picker result");
            return;
        }
        
        if (uri == null) {
            Log.d(TAG, "File picker cancelled by user");
            callback.onSelectionCancelled();
            return;
        }
        
        Log.d(TAG, "Video selected: " + uri.toString());
        callback.onVideoSelected(uri);
    }
    
    /**
     * Create an intent for video file selection (alternative method)
     */
    public static Intent createVideoPickerIntent() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("video/*");
        
        // Add supported MIME types
        String[] mimeTypes = {
            "video/mp4",
            "video/quicktime", 
            "video/x-msvideo",
            "video/x-matroska",
            "video/3gpp",
            "video/webm"
        };
        intent.putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes);
        
        return intent;
    }
    
    /**
     * Handle activity result for manual intent handling
     */
    public static Uri handleActivityResult(int requestCode, int resultCode, Intent data, int expectedRequestCode) {
        if (requestCode != expectedRequestCode) {
            return null;
        }
        
        if (resultCode != Activity.RESULT_OK || data == null) {
            return null;
        }
        
        return data.getData();
    }
}