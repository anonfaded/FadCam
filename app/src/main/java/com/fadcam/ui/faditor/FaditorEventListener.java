package com.fadcam.ui.faditor;

import android.net.Uri;

import com.fadcam.ui.faditor.models.EditOperation;
import com.fadcam.ui.faditor.processors.VideoExporter;

/**
 * Main event listener interface for Faditor Mini editor.
 * Defines communication contract between components and the main fragment.
 */
public interface FaditorEventListener {
    
    /**
     * Called when a video is selected for editing
     */
    void onVideoSelected(Uri videoUri);
    
    /**
     * Called when video loading is complete
     */
    void onVideoLoaded(long durationMs);
    
    /**
     * Called when video loading fails
     */
    void onVideoLoadError(String errorMessage);
    
    /**
     * Called when an edit operation is requested
     */
    void onEditOperationRequested(EditOperation operation);
    
    /**
     * Called when export is requested
     */
    void onExportRequested(VideoExporter.ExportSettings settings);
    
    /**
     * Called when playback state changes
     */
    void onPlaybackStateChanged(boolean isPlaying);
    
    /**
     * Called when seek position changes
     */
    void onSeekPositionChanged(long positionMs);
    
    /**
     * Called when trim range is modified
     */
    void onTrimRangeChanged(long startMs, long endMs);
    
    /**
     * Called when processing progress updates
     */
    void onProcessingProgress(int percentage);
    
    /**
     * Called when processing completes successfully
     */
    void onProcessingSuccess(String message);
    
    /**
     * Called when processing fails
     */
    void onProcessingError(String errorMessage);
}