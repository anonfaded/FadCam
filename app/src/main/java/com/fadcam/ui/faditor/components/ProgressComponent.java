package com.fadcam.ui.faditor.components;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ObjectAnimator;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;

import com.fadcam.R;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.progressindicator.CircularProgressIndicator;
import com.google.android.material.progressindicator.LinearProgressIndicator;

/**
 * Enhanced progress overlay component for showing processing progress with Material 3 design.
 * 
 * Features:
 * - Percentage-based progress tracking for all operations (Requirement 4.3, 6.1)
 * - Operation cancellation support with user feedback (Requirement 6.2)
 * - Success/error feedback with appropriate messaging (Requirement 6.3, 6.4)
 * - Material 3 progress indicators and smooth animations (Requirement 11.5)
 * - Multiple progress types: indeterminate, determinate, success, error
 * - Retry functionality for failed operations
 * - Auto-dismiss for success messages
 * - Smooth fade in/out animations with Material 3 motion patterns
 * 
 * Usage Examples:
 * 
 * // Show indeterminate progress
 * progressComponent.showProgress("Processing...", true);
 * 
 * // Update with percentage
 * progressComponent.updateProgress(50, "Processing: 50%");
 * 
 * // Show success
 * progressComponent.showSuccess("Operation completed successfully");
 * 
 * // Show error with retry
 * progressComponent.showError("Operation failed", true);
 * 
 * // Convenience methods for common operations
 * progressComponent.showExportProgress(75);
 * progressComponent.showExportSuccess("video.mp4");
 * progressComponent.showExportError("Insufficient storage space");
 * 
 * Implements requirements 4.3, 6.1, 6.2, 6.3, 6.4, 11.5.
 */
public class ProgressComponent extends FrameLayout {
    
    public interface ProgressListener {
        void onCancelRequested();
        void onRetryRequested();
        void onDismissRequested();
    }
    
    public enum ProgressType {
        INDETERMINATE,
        DETERMINATE,
        SUCCESS,
        ERROR
    }
    
    private ProgressListener listener;
    private CircularProgressIndicator circularProgress;
    private LinearProgressIndicator linearProgress;
    private TextView progressText;
    private TextView percentageText;
    private MaterialButton cancelButton;
    private MaterialButton retryButton;
    private MaterialButton dismissButton;
    
    private boolean cancellable = false;
    private ProgressType currentType = ProgressType.INDETERMINATE;
    private Handler mainHandler;
    
    // Animation constants
    private static final int FADE_DURATION_MS = 300;
    private static final int SUCCESS_DISPLAY_DURATION_MS = 2000;
    
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
        mainHandler = new Handler(Looper.getMainLooper());
        
        // Inflate the enhanced progress layout
        LayoutInflater.from(getContext()).inflate(R.layout.component_progress, this, true);
        
        // Find views
        circularProgress = findViewById(R.id.circular_progress_indicator);
        linearProgress = findViewById(R.id.linear_progress_indicator);
        progressText = findViewById(R.id.progress_text);
        percentageText = findViewById(R.id.percentage_text);
        cancelButton = findViewById(R.id.cancel_button);
        retryButton = findViewById(R.id.retry_button);
        dismissButton = findViewById(R.id.dismiss_button);
        
        // Set up button listeners
        setupButtonListeners();
        
        // Initially hidden
        setVisibility(GONE);
        setAlpha(0f);
    }
    
    private void setupButtonListeners() {
        if (cancelButton != null) {
            cancelButton.setOnClickListener(v -> {
                if (listener != null) {
                    listener.onCancelRequested();
                }
            });
        }
        
        if (retryButton != null) {
            retryButton.setOnClickListener(v -> {
                if (listener != null) {
                    listener.onRetryRequested();
                }
            });
        }
        
        if (dismissButton != null) {
            dismissButton.setOnClickListener(v -> {
                if (listener != null) {
                    listener.onDismissRequested();
                } else {
                    hideProgress();
                }
            });
        }
    }
    
    public void setProgressListener(ProgressListener listener) {
        this.listener = listener;
    }
    
    /**
     * Show indeterminate progress with message
     */
    public void showProgress(String message, boolean cancellable) {
        showProgress(ProgressType.INDETERMINATE, message, -1, cancellable);
    }
    
    /**
     * Show progress with specific type and percentage
     */
    public void showProgress(ProgressType type, String message, int percentage, boolean cancellable) {
        this.currentType = type;
        this.cancellable = cancellable;
        
        // Update UI on main thread
        mainHandler.post(() -> {
            updateProgressUI(type, message, percentage, cancellable);
            
            if (getVisibility() != VISIBLE) {
                // Animate in with Material 3 motion
                setVisibility(VISIBLE);
                ObjectAnimator fadeIn = ObjectAnimator.ofFloat(this, "alpha", 0f, 1f);
                fadeIn.setDuration(FADE_DURATION_MS);
                fadeIn.setInterpolator(new AccelerateDecelerateInterpolator());
                fadeIn.start();
            }
        });
    }
    
    /**
     * Update progress percentage and message
     */
    public void updateProgress(int percentage, String message) {
        mainHandler.post(() -> {
            if (currentType == ProgressType.INDETERMINATE) {
                // Switch to determinate progress
                currentType = ProgressType.DETERMINATE;
                updateProgressUI(currentType, message, percentage, cancellable);
            } else {
                // Update existing determinate progress
                updateProgressValues(percentage, message);
            }
        });
    }
    
    /**
     * Show success message with auto-dismiss
     */
    public void showSuccess(String message) {
        showSuccess(message, SUCCESS_DISPLAY_DURATION_MS);
    }
    
    /**
     * Show success message with custom duration
     */
    public void showSuccess(String message, int displayDurationMs) {
        mainHandler.post(() -> {
            currentType = ProgressType.SUCCESS;
            updateProgressUI(currentType, message, 100, false);
            
            if (getVisibility() != VISIBLE) {
                setVisibility(VISIBLE);
                ObjectAnimator fadeIn = ObjectAnimator.ofFloat(this, "alpha", 0f, 1f);
                fadeIn.setDuration(FADE_DURATION_MS);
                fadeIn.setInterpolator(new AccelerateDecelerateInterpolator());
                fadeIn.start();
            }
            
            // Auto-dismiss after delay
            if (displayDurationMs > 0) {
                mainHandler.postDelayed(this::hideProgress, displayDurationMs);
            }
        });
    }
    
    /**
     * Show error message with retry option
     */
    public void showError(String errorMessage, boolean showRetry) {
        mainHandler.post(() -> {
            currentType = ProgressType.ERROR;
            updateProgressUI(currentType, errorMessage, -1, false);
            
            // Show retry button if requested
            if (retryButton != null) {
                retryButton.setVisibility(showRetry ? VISIBLE : GONE);
            }
            
            if (getVisibility() != VISIBLE) {
                setVisibility(VISIBLE);
                ObjectAnimator fadeIn = ObjectAnimator.ofFloat(this, "alpha", 0f, 1f);
                fadeIn.setDuration(FADE_DURATION_MS);
                fadeIn.setInterpolator(new AccelerateDecelerateInterpolator());
                fadeIn.start();
            }
        });
    }
    
    /**
     * Hide progress with animation
     */
    public void hideProgress() {
        mainHandler.post(() -> {
            if (getVisibility() == VISIBLE) {
                ObjectAnimator fadeOut = ObjectAnimator.ofFloat(this, "alpha", 1f, 0f);
                fadeOut.setDuration(FADE_DURATION_MS);
                fadeOut.setInterpolator(new AccelerateDecelerateInterpolator());
                fadeOut.addListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator animation) {
                        setVisibility(GONE);
                        resetProgressState();
                    }
                });
                fadeOut.start();
            }
        });
    }
    
    /**
     * Update progress UI based on type
     */
    private void updateProgressUI(ProgressType type, String message, int percentage, boolean cancellable) {
        // Update message
        if (progressText != null) {
            progressText.setText(message);
        }
        
        // Update progress indicators based on type
        switch (type) {
            case INDETERMINATE:
                showIndeterminateProgress();
                break;
            case DETERMINATE:
                showDeterminateProgress(percentage);
                break;
            case SUCCESS:
                showSuccessState();
                break;
            case ERROR:
                showErrorState();
                break;
        }
        
        // Update buttons
        updateButtonVisibility(type, cancellable);
    }
    
    private void showIndeterminateProgress() {
        if (circularProgress != null) {
            circularProgress.setVisibility(VISIBLE);
            circularProgress.setIndeterminate(true);
        }
        if (linearProgress != null) {
            linearProgress.setVisibility(GONE);
        }
        if (percentageText != null) {
            percentageText.setVisibility(GONE);
        }
    }
    
    private void showDeterminateProgress(int percentage) {
        if (circularProgress != null) {
            circularProgress.setVisibility(VISIBLE);
            circularProgress.setIndeterminate(false);
            circularProgress.setProgress(percentage);
        }
        if (linearProgress != null) {
            linearProgress.setVisibility(VISIBLE);
            linearProgress.setIndeterminate(false);
            linearProgress.setProgress(percentage);
        }
        updatePercentageText(percentage);
    }
    
    private void showSuccessState() {
        if (circularProgress != null) {
            circularProgress.setVisibility(GONE);
        }
        if (linearProgress != null) {
            linearProgress.setVisibility(VISIBLE);
            linearProgress.setIndeterminate(false);
            linearProgress.setProgress(100);
        }
        updatePercentageText(100);
    }
    
    private void showErrorState() {
        if (circularProgress != null) {
            circularProgress.setVisibility(GONE);
        }
        if (linearProgress != null) {
            linearProgress.setVisibility(GONE);
        }
        if (percentageText != null) {
            percentageText.setVisibility(GONE);
        }
    }
    
    private void updateButtonVisibility(ProgressType type, boolean cancellable) {
        // Cancel button - only show for in-progress operations
        if (cancelButton != null) {
            boolean showCancel = (type == ProgressType.INDETERMINATE || type == ProgressType.DETERMINATE) && cancellable;
            cancelButton.setVisibility(showCancel ? VISIBLE : GONE);
        }
        
        // Retry button - only show for errors when explicitly enabled
        if (retryButton != null && type != ProgressType.ERROR) {
            retryButton.setVisibility(GONE);
        }
        
        // Dismiss button - show for success and error states
        if (dismissButton != null) {
            boolean showDismiss = (type == ProgressType.SUCCESS || type == ProgressType.ERROR);
            dismissButton.setVisibility(showDismiss ? VISIBLE : GONE);
        }
    }
    
    private void updateProgressValues(int percentage, String message) {
        if (progressText != null && message != null) {
            progressText.setText(message);
        }
        
        if (circularProgress != null) {
            circularProgress.setProgress(percentage);
        }
        if (linearProgress != null) {
            linearProgress.setProgress(percentage);
        }
        
        updatePercentageText(percentage);
    }
    
    private void updatePercentageText(int percentage) {
        if (percentageText != null) {
            percentageText.setVisibility(VISIBLE);
            percentageText.setText(getContext().getString(R.string.faditor_export_progress, percentage));
        }
    }
    
    private void resetProgressState() {
        currentType = ProgressType.INDETERMINATE;
        cancellable = false;
        
        if (circularProgress != null) {
            circularProgress.setProgress(0);
        }
        if (linearProgress != null) {
            linearProgress.setProgress(0);
        }
    }
    
    /**
     * Convenience methods for common operations
     */
    
    public void showExportProgress(int percentage) {
        String message = getContext().getString(R.string.faditor_export_progress, percentage);
        updateProgress(percentage, message);
    }
    
    public void showProcessingProgress(String operation) {
        String message = getContext().getString(R.string.faditor_processing) + " " + operation;
        showProgress(message, true);
    }
    
    public void showExportSuccess(String filename) {
        String message = getContext().getString(R.string.faditor_export_success_message, filename);
        showSuccess(message);
    }
    
    public void showExportError(String errorMessage) {
        String message = getContext().getString(R.string.faditor_export_error_message, errorMessage);
        showError(message, true);
    }
    
    /**
     * Check if progress is currently visible
     */
    public boolean isProgressVisible() {
        return getVisibility() == VISIBLE && getAlpha() > 0f;
    }
    
    /**
     * Get current progress type
     */
    public ProgressType getCurrentType() {
        return currentType;
    }
    
    /**
     * Check if operation is cancellable
     */
    public boolean isCancellable() {
        return cancellable;
    }
}