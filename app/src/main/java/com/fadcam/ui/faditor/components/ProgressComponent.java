package com.fadcam.ui.faditor.components;

import android.content.Context;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Progress component for displaying video processing progress.
 * Shows progress indicators and status messages during operations.
 */
public class ProgressComponent extends FrameLayout {
    
    private ProgressBar progressBar;
    private TextView statusText;
    private TextView percentageText;
    private View backgroundOverlay;
    
    private ProgressListener listener;
    
    public interface ProgressListener {
        void onCancelRequested();
    }
    
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
        // Initialize progress UI components
        // Implementation will be added in subsequent tasks
        setVisibility(GONE);
    }
    
    /**
     * Show progress with a status message
     */
    public void showProgress(String statusMessage) {
        if (statusText != null) {
            statusText.setText(statusMessage);
        }
        setVisibility(VISIBLE);
    }
    
    /**
     * Update progress percentage
     */
    public void updateProgress(int percentage) {
        if (progressBar != null) {
            progressBar.setProgress(percentage);
        }
        if (percentageText != null) {
            percentageText.setText(percentage + "%");
        }
    }
    
    /**
     * Hide progress overlay
     */
    public void hideProgress() {
        setVisibility(GONE);
    }
    
    /**
     * Show success message
     */
    public void showSuccess(String message) {
        if (statusText != null) {
            statusText.setText(message);
        }
        if (progressBar != null) {
            progressBar.setVisibility(GONE);
        }
        if (percentageText != null) {
            percentageText.setVisibility(GONE);
        }
    }
    
    /**
     * Show error message
     */
    public void showError(String errorMessage) {
        if (statusText != null) {
            statusText.setText(errorMessage);
        }
        if (progressBar != null) {
            progressBar.setVisibility(GONE);
        }
        if (percentageText != null) {
            percentageText.setVisibility(GONE);
        }
    }
    
    /**
     * Set listener for progress events
     */
    public void setProgressListener(ProgressListener listener) {
        this.listener = listener;
    }
}