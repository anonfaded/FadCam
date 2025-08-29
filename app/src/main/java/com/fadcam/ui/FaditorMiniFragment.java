package com.fadcam.ui;

import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.Log;
import com.fadcam.R;
import com.fadcam.ui.faditor.models.VideoMetadata;
import com.fadcam.ui.faditor.models.VideoProject;
import com.fadcam.ui.faditor.utils.VideoFilePicker;
import com.fadcam.ui.faditor.utils.VideoFileUtils;

/**
 * Main fragment for the Faditor Mini video editor.
 * Handles video selection, editing operations, and user interface.
 */
public class FaditorMiniFragment extends BaseFragment implements VideoFilePicker.VideoSelectionCallback {
    
    private static final String TAG = "FaditorMiniFragment";
    
    // UI Components
    private Button selectVideoButton;
    private TextView videoInfoText;
    private View editorContainer;
    private View placeholderContainer;
    
    // Video editing components
    private VideoFilePicker filePicker;
    private VideoProject currentProject;
    
    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_faditor_mini, container, false);
        
        initializeViews(view);
        setupFilePicker();
        
        return view;
    }
    
    private void initializeViews(View view) {
        // Find UI components
        selectVideoButton = view.findViewById(R.id.select_video_button);
        videoInfoText = view.findViewById(R.id.video_info_text);
        editorContainer = view.findViewById(R.id.editor_container);
        placeholderContainer = view.findViewById(R.id.placeholder_container);
        
        // Find editor buttons
        Button selectNewVideoButton = view.findViewById(R.id.select_new_video_button);
        Button trimVideoButton = view.findViewById(R.id.trim_video_button);
        
        // Set up click listeners
        if (selectVideoButton != null) {
            selectVideoButton.setOnClickListener(v -> pickVideo());
        }
        
        if (selectNewVideoButton != null) {
            selectNewVideoButton.setOnClickListener(v -> pickVideo());
        }
        
        if (trimVideoButton != null) {
            trimVideoButton.setOnClickListener(v -> {
                // Trim functionality will be implemented in subsequent tasks
                Toast.makeText(requireContext(), "Trim functionality coming in next task", Toast.LENGTH_SHORT).show();
            });
        }
        
        // Initially show placeholder, hide editor
        showPlaceholder();
    }
    
    private void setupFilePicker() {
        filePicker = new VideoFilePicker(this);
    }
    
    private void pickVideo() {
        if (filePicker != null) {
            filePicker.pickVideo(this);
        } else {
            Log.e(TAG, "FilePicker not initialized");
            Toast.makeText(requireContext(), R.string.faditor_error_processing_failed, Toast.LENGTH_SHORT).show();
        }
    }
    
    private void showPlaceholder() {
        if (placeholderContainer != null) {
            placeholderContainer.setVisibility(View.VISIBLE);
        }
        if (editorContainer != null) {
            editorContainer.setVisibility(View.GONE);
        }
    }
    
    private void showEditor() {
        if (placeholderContainer != null) {
            placeholderContainer.setVisibility(View.GONE);
        }
        if (editorContainer != null) {
            editorContainer.setVisibility(View.VISIBLE);
        }
    }
    
    // VideoFilePicker.VideoSelectionCallback implementation
    
    @Override
    public void onVideoSelected(Uri videoUri) {
        Log.d(TAG, "Video selected: " + videoUri.toString());
        
        // Validate the selected video
        if (!VideoFileUtils.isValidVideoFile(requireContext(), videoUri)) {
            String supportedFormats = VideoFileUtils.getSupportedFormatsString();
            String errorMessage = getString(R.string.faditor_supported_formats, supportedFormats);
            Toast.makeText(requireContext(), errorMessage, Toast.LENGTH_LONG).show();
            return;
        }
        
        // Extract metadata
        VideoMetadata metadata = VideoFileUtils.extractMetadata(requireContext(), videoUri);
        
        if (metadata.getDuration() <= 0) {
            Toast.makeText(requireContext(), R.string.faditor_error_processing_failed, Toast.LENGTH_SHORT).show();
            return;
        }
        
        // Create new project
        currentProject = new VideoProject();
        currentProject.setOriginalVideoUri(videoUri);
        currentProject.setMetadata(metadata);
        
        // Update UI with video information
        updateVideoInfo(videoUri, metadata);
        
        // Show editor interface
        showEditor();
        
        Log.d(TAG, "Video loaded successfully: " + metadata.toString());
    }
    
    @Override
    public void onSelectionCancelled() {
        Log.d(TAG, "Video selection cancelled");
        // No action needed - user cancelled
    }
    
    @Override
    public void onError(String errorMessage) {
        Log.e(TAG, "Video selection error: " + errorMessage);
        Toast.makeText(requireContext(), R.string.faditor_error_processing_failed, Toast.LENGTH_SHORT).show();
    }
    
    private void updateVideoInfo(Uri videoUri, VideoMetadata metadata) {
        if (videoInfoText == null) return;
        
        StringBuilder info = new StringBuilder();
        
        // Get filename
        String fileName = VideoFileUtils.getFileName(requireContext(), videoUri);
        if (fileName != null) {
            info.append("File: ").append(fileName).append("\n");
        }
        
        // Add video properties
        info.append("Duration: ").append(VideoFileUtils.formatDuration(metadata.getDuration())).append("\n");
        info.append("Resolution: ").append(metadata.getResolutionString()).append("\n");
        
        // Add file size if available
        long fileSize = VideoFileUtils.getFileSize(requireContext(), videoUri);
        if (fileSize > 0) {
            info.append("Size: ").append(VideoFileUtils.formatFileSize(fileSize)).append("\n");
        }
        
        // Add codec info if available
        if (metadata.getCodec() != null) {
            info.append("Codec: ").append(metadata.getCodec().toUpperCase()).append("\n");
        }
        
        videoInfoText.setText(info.toString().trim());
    }
    
    @Override
    public void onResume() {
        super.onResume();
        
        // Only show toast if no video is loaded (placeholder is showing)
        if (currentProject == null && placeholderContainer != null && 
            placeholderContainer.getVisibility() == View.VISIBLE) {
            Toast.makeText(requireContext(), R.string.faditor_mini_toast, Toast.LENGTH_SHORT).show();
        }
    }
}
