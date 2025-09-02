package com.fadcam.ui.faditor.components;

import android.content.Context;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.widget.LinearLayout;

import androidx.annotation.Nullable;

import com.fadcam.R;
import com.google.android.material.button.MaterialButton;

/**
 * Professional playback controls component for video player overlay.
 * This is a placeholder implementation - full implementation will be in future tasks.
 */
public class ControlsComponent extends LinearLayout {
    
    public interface ControlsListener {
        void onPlayPauseClicked();
        void onSeekBackward();
        void onSeekForward();
    }
    
    private ControlsListener listener;
    private MaterialButton playPauseButton;
    private MaterialButton seekBackButton;
    private MaterialButton seekForwardButton;
    private boolean isPlaying = false;
    
    public ControlsComponent(Context context) {
        super(context);
        init();
    }
    
    public ControlsComponent(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }
    
    public ControlsComponent(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }
    
    private void init() {
        setOrientation(HORIZONTAL);
        
        // Inflate the controls layout
        LayoutInflater.from(getContext()).inflate(R.layout.component_controls, this, true);
        
        // Find buttons
        seekBackButton = findViewById(R.id.seek_back_button);
        playPauseButton = findViewById(R.id.play_pause_button);
        seekForwardButton = findViewById(R.id.seek_forward_button);
        
        // Set up click listeners
        setupClickListeners();
        
        // Update initial state
        updatePlayPauseButton();
    }
    
    private void setupClickListeners() {
        if (seekBackButton != null) {
            seekBackButton.setOnClickListener(v -> {
                if (listener != null) {
                    listener.onSeekBackward();
                }
            });
        }
        
        if (playPauseButton != null) {
            playPauseButton.setOnClickListener(v -> {
                if (listener != null) {
                    listener.onPlayPauseClicked();
                }
            });
        }
        
        if (seekForwardButton != null) {
            seekForwardButton.setOnClickListener(v -> {
                if (listener != null) {
                    listener.onSeekForward();
                }
            });
        }
    }
    
    public void setControlsListener(ControlsListener listener) {
        this.listener = listener;
    }
    
    public void setPlayingState(boolean playing) {
        this.isPlaying = playing;
        updatePlayPauseButton();
    }
    
    private void updatePlayPauseButton() {
        if (playPauseButton != null) {
            int iconRes = isPlaying ? R.drawable.ic_pause_24 : R.drawable.ic_play_arrow_24;
            playPauseButton.setIconResource(iconRes);
        }
    }
}