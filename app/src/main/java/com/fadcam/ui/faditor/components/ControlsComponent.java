package com.fadcam.ui.faditor.components;

import android.content.Context;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.widget.ImageButton;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Controls component for video editing operations.
 * Provides play/pause/export buttons and other editing controls.
 */
public class ControlsComponent extends LinearLayout {
    
    private ImageButton playPauseButton;
    private ImageButton exportButton;
    private ImageButton trimButton;
    
    private ControlsListener listener;
    private boolean isPlaying = false;
    
    public interface ControlsListener {
        void onPlayPauseClicked(boolean shouldPlay);
        void onExportClicked();
        void onTrimClicked();
    }
    
    public ControlsComponent(@NonNull Context context) {
        super(context);
        init();
    }
    
    public ControlsComponent(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }
    
    public ControlsComponent(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }
    
    private void init() {
        setOrientation(HORIZONTAL);
        // Initialize buttons and layout
        // Implementation will be added in subsequent tasks
    }
    
    /**
     * Update the play/pause button state
     */
    public void setPlayingState(boolean playing) {
        this.isPlaying = playing;
        updatePlayPauseButton();
    }
    
    /**
     * Enable or disable the export button
     */
    public void setExportEnabled(boolean enabled) {
        if (exportButton != null) {
            exportButton.setEnabled(enabled);
        }
    }
    
    /**
     * Enable or disable the trim button
     */
    public void setTrimEnabled(boolean enabled) {
        if (trimButton != null) {
            trimButton.setEnabled(enabled);
        }
    }
    
    /**
     * Set listener for control events
     */
    public void setControlsListener(ControlsListener listener) {
        this.listener = listener;
    }
    
    private void updatePlayPauseButton() {
        // Update button icon based on playing state
        // Implementation will be added in subsequent tasks
    }
}