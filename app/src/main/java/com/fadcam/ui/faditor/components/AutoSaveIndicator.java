package com.fadcam.ui.faditor.components;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ObjectAnimator;
import android.content.Context;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.core.content.ContextCompat;

import com.fadcam.R;

/**
 * Subtle visual feedback component for auto-save operations
 * Requirement 12.6: Provide subtle visual feedback without interrupting workflow
 */
public class AutoSaveIndicator extends LinearLayout {
    
    private static final int ANIMATION_DURATION_MS = 300;
    private static final int DISPLAY_DURATION_MS = 2000;
    private static final int FADE_OUT_DELAY_MS = 1500;
    
    private ImageView iconView;
    private TextView statusText;
    private ObjectAnimator fadeInAnimator;
    private ObjectAnimator fadeOutAnimator;
    private Runnable hideRunnable;
    
    public enum SaveState {
        SAVING,
        SAVED,
        ERROR
    }
    
    public AutoSaveIndicator(Context context) {
        super(context);
        init();
    }
    
    public AutoSaveIndicator(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }
    
    public AutoSaveIndicator(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }
    
    private void init() {
        setOrientation(HORIZONTAL);
        setVisibility(GONE);
        setAlpha(0f);
        
        // Create icon view
        iconView = new ImageView(getContext());
        LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(
            dpToPx(16), dpToPx(16));
        iconParams.setMarginEnd(dpToPx(4));
        iconView.setLayoutParams(iconParams);
        addView(iconView);
        
        // Create status text
        statusText = new TextView(getContext());
        statusText.setTextSize(12);
        statusText.setLayoutParams(new LinearLayout.LayoutParams(
            LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
        addView(statusText);
        
        // Initialize animators
        initializeAnimators();
        
        // Initialize hide runnable
        hideRunnable = this::hideWithAnimation;
    }
    
    private void initializeAnimators() {
        // Fade in animator
        fadeInAnimator = ObjectAnimator.ofFloat(this, "alpha", 0f, 1f);
        fadeInAnimator.setDuration(ANIMATION_DURATION_MS);
        fadeInAnimator.setInterpolator(new AccelerateDecelerateInterpolator());
        fadeInAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationStart(Animator animation) {
                setVisibility(VISIBLE);
            }
        });
        
        // Fade out animator
        fadeOutAnimator = ObjectAnimator.ofFloat(this, "alpha", 1f, 0f);
        fadeOutAnimator.setDuration(ANIMATION_DURATION_MS);
        fadeOutAnimator.setInterpolator(new AccelerateDecelerateInterpolator());
        fadeOutAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                setVisibility(GONE);
            }
        });
    }
    
    /**
     * Shows the auto-save indicator with the specified state
     */
    public void showSaveState(SaveState state) {
        // Cancel any pending hide operation
        removeCallbacks(hideRunnable);
        
        // Cancel any running animations
        if (fadeOutAnimator.isRunning()) {
            fadeOutAnimator.cancel();
        }
        
        // Update UI based on state
        updateUIForState(state);
        
        // Show with animation if not already visible
        if (getVisibility() != VISIBLE || getAlpha() < 1f) {
            if (fadeInAnimator.isRunning()) {
                fadeInAnimator.cancel();
            }
            fadeInAnimator.start();
        }
        
        // Schedule hide for saved and error states
        if (state == SaveState.SAVED || state == SaveState.ERROR) {
            postDelayed(hideRunnable, FADE_OUT_DELAY_MS);
        }
    }
    
    /**
     * Hides the indicator immediately
     */
    public void hide() {
        removeCallbacks(hideRunnable);
        if (fadeInAnimator.isRunning()) {
            fadeInAnimator.cancel();
        }
        if (fadeOutAnimator.isRunning()) {
            fadeOutAnimator.cancel();
        }
        setVisibility(GONE);
        setAlpha(0f);
    }
    
    /**
     * Hides the indicator with animation
     */
    private void hideWithAnimation() {
        if (getVisibility() == VISIBLE && getAlpha() > 0f) {
            if (fadeInAnimator.isRunning()) {
                fadeInAnimator.cancel();
            }
            fadeOutAnimator.start();
        }
    }
    
    private void updateUIForState(SaveState state) {
        Context context = getContext();
        
        switch (state) {
            case SAVING:
                // Use a subtle saving icon (could be a small spinner or dots)
                iconView.setImageDrawable(getSavingDrawable());
                statusText.setText(context.getString(R.string.faditor_auto_saving));
                statusText.setTextColor(ContextCompat.getColor(context, R.color.colorTextSecondary));
                break;
                
            case SAVED:
                // Use a checkmark icon
                iconView.setImageDrawable(getSavedDrawable());
                statusText.setText(context.getString(R.string.faditor_auto_saved));
                statusText.setTextColor(ContextCompat.getColor(context, R.color.colorSuccess));
                break;
                
            case ERROR:
                // Use a warning icon
                iconView.setImageDrawable(getErrorDrawable());
                statusText.setText(context.getString(R.string.faditor_auto_save_error));
                statusText.setTextColor(ContextCompat.getColor(context, R.color.colorError));
                break;
        }
    }
    
    private Drawable getSavingDrawable() {
        // For now, use a simple drawable. In a real implementation, 
        // this could be an animated drawable or vector drawable
        return ContextCompat.getDrawable(getContext(), android.R.drawable.ic_popup_sync);
    }
    
    private Drawable getSavedDrawable() {
        return ContextCompat.getDrawable(getContext(), android.R.drawable.ic_menu_save);
    }
    
    private Drawable getErrorDrawable() {
        return ContextCompat.getDrawable(getContext(), android.R.drawable.ic_dialog_alert);
    }
    
    private int dpToPx(int dp) {
        float density = getContext().getResources().getDisplayMetrics().density;
        return Math.round(dp * density);
    }
    
    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        // Clean up animations and callbacks
        removeCallbacks(hideRunnable);
        if (fadeInAnimator != null) {
            fadeInAnimator.cancel();
        }
        if (fadeOutAnimator != null) {
            fadeOutAnimator.cancel();
        }
    }
}