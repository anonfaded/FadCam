package com.fadcam.ui.faditor.components;

import android.content.Context;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.R;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.progressindicator.CircularProgressIndicator;

/**
 * Progress overlay component for showing processing progress with Material 3 design.
 * This is a placeholder implementation - full implementation will be in future tasks.
 */
public class ProgressComponent extends FrameLayout {
    
    public interface ProgressListener {
        void onCancelRequested();
    }
    
    private ProgressListener listener;
    private CircularProgressIndicator progressIndicator;
    private TextView progressText;
    private MaterialButton cancelButton;
    private boolean cancellable = false;
    
    public ProgressComponent(@NonNull Context context) {
        super(context);
        init();
    }
    
    public ProgressComponent(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }
    
    public ProgressComponent(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }
    
    private void init() {
        // Inflate the progress layout
        LayoutInflater.from(getContext()).inflate(R.layout.component_progress, this, true);
        
        // Find views
        progressIndicator = findViewById(R.id.progress_indicator);
        progressText = findViewById(R.id.progress_text);
        cancelButton = findViewById(R.id.cancel_button);
        
        // Set up cancel button
        if (cancelButton != null) {
            cancelButton.setOnClickListener(v -> {
                if (listener != null) {
                    listener.onCancelRequested();
                }
            });
        }
        
        // Initially hidden
        setVisibility(GONE);
    }
    
    public void setProgressListener(ProgressListener listener) {
        this.listener = listener;
    }
    
    public void showProgress(String message, boolean cancellable) {
        this.cancellable = cancellable;
        
        if (progressText != null) {
            progressText.setText(message);
        }
        
        if (cancelButton != null) {
            cancelButton.setVisibility(cancellable ? VISIBLE : GONE);
        }
        
        if (progressIndicator != null) {
            progressIndicator.setIndeterminate(true);
        }
        
        setVisibility(VISIBLE);
    }
    
    public void updateProgress(int percentage, String message) {
        if (progressIndicator != null) {
            progressIndicator.setIndeterminate(false);
            progressIndicator.setProgress(percentage);
        }
        
        if (progressText != null) {
            progressText.setText(message);
        }
    }
    
    public void hideProgress() {
        setVisibility(GONE);
    }
}