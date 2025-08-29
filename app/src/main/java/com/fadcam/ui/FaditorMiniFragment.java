package com.fadcam.ui;

import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.Log;
import com.fadcam.R;
import com.fadcam.ui.faditor.components.VideoPlayerComponent;
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
    private VideoPlayerComponent videoPlayerComponent;
    private Button playPauseButton;
    private TextView positionText;
    private SeekBar videoSeekBar;
    private com.fadcam.ui.faditor.components.TimelineComponent timelineComponent;
    
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
        videoPlayerComponent = view.findViewById(R.id.video_player_component);
        playPauseButton = view.findViewById(R.id.play_pause_button);
        positionText = view.findViewById(R.id.position_text);
        videoSeekBar = view.findViewById(R.id.video_seek_bar);
        timelineComponent = view.findViewById(R.id.timeline_component);
        
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
        
        if (playPauseButton != null) {
            playPauseButton.setOnClickListener(v -> {
                if (videoPlayerComponent != null && videoPlayerComponent.isVideoLoaded()) {
                    videoPlayerComponent.togglePlayPause();
                } else {
                    Toast.makeText(requireContext(), "No video loaded", Toast.LENGTH_SHORT).show();
                }
            });
        }
        
        // Set up seek bar listener
        if (videoSeekBar != null) {
            videoSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                private boolean userSeeking = false;
                
                @Override
                public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    if (fromUser && videoPlayerComponent != null && videoPlayerComponent.isVideoLoaded()) {
                        float seekProgress = progress / 1000.0f; // Convert to 0.0-1.0 range
                        videoPlayerComponent.seekToProgress(seekProgress);
                    }
                }
                
                @Override
                public void onStartTrackingTouch(SeekBar seekBar) {
                    userSeeking = true;
                }
                
                @Override
                public void onStopTrackingTouch(SeekBar seekBar) {
                    userSeeking = false;
                }
            });
        }
        
        // Set up video player listener
        if (videoPlayerComponent != null) {
            videoPlayerComponent.setVideoPlayerListener(new VideoPlayerComponent.VideoPlayerListener() {
                @Override
                public void onPositionChanged(long positionMs) {
                    // Update position display and timeline
                    updatePositionDisplay(positionMs, videoPlayerComponent.getDuration());
                    if (timelineComponent != null) {
                        timelineComponent.setCurrentPosition(positionMs);
                    }
                    Log.d(TAG, "Video position: " + positionMs + "ms");
                }
                
                @Override
                public void onDurationChanged(long durationMs) {
                    Log.d(TAG, "Video duration: " + durationMs + "ms");
                    if (currentProject != null) {
                        // Update project with actual duration
                        currentProject.getMetadata().setDuration(durationMs);
                        updateVideoInfo(currentProject.getOriginalVideoUri(), currentProject.getMetadata());
                    }
                    
                    // Update timeline with video duration
                    if (timelineComponent != null) {
                        timelineComponent.setVideoDuration(durationMs);
                    }
                }
                
                @Override
                public void onPlaybackStateChanged(boolean isPlaying) {
                    // Update play/pause button text
                    if (playPauseButton != null) {
                        playPauseButton.setText(isPlaying ? R.string.faditor_pause : R.string.faditor_play);
                    }
                    Log.d(TAG, "Playback state changed: " + (isPlaying ? "playing" : "paused"));
                }
                
                @Override
                public void onError(String errorMessage) {
                    Log.e(TAG, "Video player error: " + errorMessage);
                    Toast.makeText(requireContext(), "Video player error: " + errorMessage, Toast.LENGTH_LONG).show();
                }
                
                @Override
                public void onVideoSizeChanged(int width, int height) {
                    Log.d(TAG, "Video size changed: " + width + "x" + height);
                    if (currentProject != null) {
                        // Update project with actual video dimensions
                        currentProject.getMetadata().setWidth(width);
                        currentProject.getMetadata().setHeight(height);
                        updateVideoInfo(currentProject.getOriginalVideoUri(), currentProject.getMetadata());
                    }
                }
            });
        }
        
        // Set up timeline component listener
        if (timelineComponent != null) {
            timelineComponent.setTimelineListener(new com.fadcam.ui.faditor.components.TimelineComponent.TimelineListener() {
                @Override
                public void onTrimRangeChanged(long startMs, long endMs) {
                    Log.d(TAG, "Trim range changed: " + startMs + "ms - " + endMs + "ms");
                    
                    // Update current project with new trim range
                    if (currentProject != null) {
                        // This will be used when implementing trim functionality in subsequent tasks
                        Log.d(TAG, "Trim range updated in project: " + 
                              com.fadcam.ui.faditor.utils.TimelineUtils.formatDuration(startMs) + " - " + 
                              com.fadcam.ui.faditor.utils.TimelineUtils.formatDuration(endMs));
                    }
                    
                    // Enable trim button when valid range is selected
                    Button trimButton = view.findViewById(R.id.trim_video_button);
                    if (trimButton != null) {
                        long duration = endMs - startMs;
                        boolean validRange = duration >= 1000 && duration < (videoPlayerComponent != null ? videoPlayerComponent.getDuration() : 0);
                        trimButton.setEnabled(validRange);
                    }
                }
                
                @Override
                public void onPositionSeek(long positionMs) {
                    Log.d(TAG, "Timeline position seek: " + positionMs + "ms");
                    
                    // Seek video player to new position
                    if (videoPlayerComponent != null && videoPlayerComponent.isVideoLoaded()) {
                        videoPlayerComponent.seekTo(positionMs);
                    }
                }
                
                @Override
                public void onTrimHandleDragStart() {
                    Log.d(TAG, "Trim handle drag started");
                    
                    // Pause video during trim handle dragging for better UX
                    if (videoPlayerComponent != null && videoPlayerComponent.isPlaying()) {
                        videoPlayerComponent.pause();
                    }
                }
                
                @Override
                public void onTrimHandleDragEnd() {
                    Log.d(TAG, "Trim handle drag ended");
                    
                    // Optionally resume playback or provide visual feedback
                    // For now, just log the event
                }
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
        
        // Load video into player component
        if (videoPlayerComponent != null) {
            videoPlayerComponent.loadVideo(videoUri);
        }
        
        // Initialize timeline with video duration
        if (timelineComponent != null) {
            timelineComponent.setVideoDuration(metadata.getDuration());
            // Set initial trim range to full video
            timelineComponent.setTrimRange(0, metadata.getDuration());
        }
        
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
    
    private void updatePositionDisplay(long positionMs, long durationMs) {
        if (positionText == null) return;
        
        String positionStr = VideoFileUtils.formatDuration(positionMs);
        String durationStr = VideoFileUtils.formatDuration(durationMs);
        String displayText = positionStr + " / " + durationStr;
        
        positionText.setText(displayText);
        
        // Update seek bar progress
        if (videoSeekBar != null && durationMs > 0) {
            float progress = (float) positionMs / durationMs;
            int seekBarProgress = (int) (progress * 1000); // Convert to 0-1000 range
            videoSeekBar.setProgress(seekBarProgress);
        }
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
    
    @Override
    public void onPause() {
        super.onPause();
        
        // Pause video playback when fragment is paused
        if (videoPlayerComponent != null) {
            videoPlayerComponent.pause();
        }
    }
    
    @Override
    public void onDestroyView() {
        super.onDestroyView();
        
        // Release video player resources
        if (videoPlayerComponent != null) {
            videoPlayerComponent.release();
            videoPlayerComponent = null;
        }
        
        // Reset timeline component
        if (timelineComponent != null) {
            timelineComponent.reset();
            timelineComponent = null;
        }
        
        // Clear current project
        currentProject = null;
    }
}
