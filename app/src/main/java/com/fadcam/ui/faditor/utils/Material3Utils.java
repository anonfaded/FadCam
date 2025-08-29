package com.fadcam.ui.faditor.utils;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.widget.TextView;

import androidx.annotation.ColorInt;
import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.ColorUtils;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.chip.Chip;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.progressindicator.CircularProgressIndicator;
import com.google.android.material.progressindicator.LinearProgressIndicator;

import com.fadcam.R;

/**
 * Utility class for applying Material 3 design system components and theming
 * to Faditor UI elements. Provides consistent Material You theming, motion patterns,
 * and accessibility features across all faditor components.
 */
public class Material3Utils {
    
    // Material 3 Motion Duration Constants
    public static final int MOTION_DURATION_SHORT_1 = 75;
    public static final int MOTION_DURATION_SHORT_2 = 150;
    public static final int MOTION_DURATION_MEDIUM_1 = 200;
    public static final int MOTION_DURATION_MEDIUM_2 = 250;
    public static final int MOTION_DURATION_LONG_1 = 300;
    public static final int MOTION_DURATION_LONG_2 = 350;
    
    // Material 3 Elevation Levels
    public static final float ELEVATION_LEVEL_0 = 0f;
    public static final float ELEVATION_LEVEL_1 = 1f;
    public static final float ELEVATION_LEVEL_2 = 3f;
    public static final float ELEVATION_LEVEL_3 = 6f;
    public static final float ELEVATION_LEVEL_4 = 8f;
    public static final float ELEVATION_LEVEL_5 = 12f;
    
    /**
     * Apply Material 3 button styling with proper state layers and motion
     */
    public static void applyMaterial3ButtonStyle(@NonNull MaterialButton button, ButtonStyle style) {
        Context context = button.getContext();
        
        switch (style) {
            case FILLED:
                button.setBackgroundTintList(getColorStateList(context, R.color.faditor_primary));
                button.setTextColor(getColorStateList(context, R.color.faditor_on_primary));
                button.setIconTint(getColorStateList(context, R.color.faditor_on_primary));
                break;
            case OUTLINED:
                button.setStrokeColor(getColorStateList(context, R.color.faditor_outline));
                button.setTextColor(getColorStateList(context, R.color.faditor_primary));
                button.setIconTint(getColorStateList(context, R.color.faditor_primary));
                break;
            case TEXT:
                button.setTextColor(getColorStateList(context, R.color.faditor_primary));
                button.setIconTint(getColorStateList(context, R.color.faditor_primary));
                break;
            case ICON:
                button.setIconTint(getColorStateList(context, R.color.faditor_on_surface_variant));
                break;
        }
        
        // Apply Material 3 motion
        applyButtonMotion(button);
        
        // Apply accessibility features
        applyAccessibilityFeatures(button);
    }
    
    /**
     * Apply Material 3 card styling with proper elevation and corners
     */
    public static void applyMaterial3CardStyle(@NonNull MaterialCardView card, CardStyle style) {
        Context context = card.getContext();
        
        switch (style) {
            case ELEVATED:
                card.setCardElevation(ELEVATION_LEVEL_1);
                card.setCardBackgroundColor(getColorStateList(context, R.color.faditor_surface));
                break;
            case FILLED:
                card.setCardElevation(ELEVATION_LEVEL_0);
                card.setCardBackgroundColor(getColorStateList(context, R.color.faditor_surface_variant));
                break;
            case OUTLINED:
                card.setCardElevation(ELEVATION_LEVEL_0);
                card.setStrokeColor(getColor(context, R.color.faditor_outline));
                card.setStrokeWidth(1);
                break;
        }
        
        card.setRadius(context.getResources().getDimension(R.dimen.material_corner_radius_medium));
        
        // Apply Material 3 motion
        applyCardMotion(card);
    }
    
    /**
     * Apply Material 3 FAB styling with proper theming and motion
     */
    public static void applyMaterial3FABStyle(@NonNull FloatingActionButton fab) {
        Context context = fab.getContext();
        
        fab.setBackgroundTintList(getColorStateList(context, R.color.faditor_primary_container));
        fab.setImageTintList(getColorStateList(context, R.color.faditor_on_primary_container));
        fab.setElevation(ELEVATION_LEVEL_3);
        
        // Apply Material 3 motion
        applyFABMotion(fab);
        
        // Apply accessibility features
        applyAccessibilityFeatures(fab);
    }
    
    /**
     * Apply Material 3 chip styling
     */
    public static void applyMaterial3ChipStyle(@NonNull Chip chip, ChipStyle style) {
        Context context = chip.getContext();
        
        switch (style) {
            case ASSIST:
                chip.setChipBackgroundColor(getColorStateList(context, R.color.faditor_surface_variant));
                chip.setTextColor(getColorStateList(context, R.color.faditor_on_surface_variant));
                break;
            case FILTER:
                chip.setChipBackgroundColor(getColorStateList(context, R.color.faditor_chip_filter_background));
                chip.setTextColor(getColorStateList(context, R.color.faditor_chip_filter_text));
                chip.setCheckable(true);
                break;
        }
        
        chip.setChipCornerRadius(context.getResources().getDimension(R.dimen.material_corner_radius_small));
    }
    
    /**
     * Apply Material 3 progress indicator styling
     */
    public static void applyMaterial3ProgressStyle(@NonNull View progressIndicator) {
        Context context = progressIndicator.getContext();
        
        if (progressIndicator instanceof CircularProgressIndicator) {
            CircularProgressIndicator circular = (CircularProgressIndicator) progressIndicator;
            circular.setTrackColor(getColor(context, R.color.faditor_surface_variant));
            circular.setIndicatorColor(getColor(context, R.color.faditor_primary));
        } else if (progressIndicator instanceof LinearProgressIndicator) {
            LinearProgressIndicator linear = (LinearProgressIndicator) progressIndicator;
            linear.setTrackColor(getColor(context, R.color.faditor_surface_variant));
            linear.setIndicatorColor(getColor(context, R.color.faditor_primary));
        }
    }
    
    /**
     * Apply Material 3 motion patterns to buttons
     */
    private static void applyButtonMotion(@NonNull View button) {
        button.setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    v.animate()
                        .scaleX(0.95f)
                        .scaleY(0.95f)
                        .setDuration(MOTION_DURATION_SHORT_1)
                        .setInterpolator(new DecelerateInterpolator())
                        .start();
                    break;
                case android.view.MotionEvent.ACTION_UP:
                case android.view.MotionEvent.ACTION_CANCEL:
                    v.animate()
                        .scaleX(1.0f)
                        .scaleY(1.0f)
                        .setDuration(MOTION_DURATION_SHORT_2)
                        .setInterpolator(new AccelerateDecelerateInterpolator())
                        .start();
                    break;
            }
            return false; // Allow click to proceed
        });
    }
    
    /**
     * Apply Material 3 motion patterns to cards
     */
    private static void applyCardMotion(@NonNull MaterialCardView card) {
        card.setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    v.animate()
                        .scaleX(0.98f)
                        .scaleY(0.98f)
                        .setDuration(MOTION_DURATION_SHORT_1)
                        .setInterpolator(new DecelerateInterpolator())
                        .start();
                    break;
                case android.view.MotionEvent.ACTION_UP:
                case android.view.MotionEvent.ACTION_CANCEL:
                    v.animate()
                        .scaleX(1.0f)
                        .scaleY(1.0f)
                        .setDuration(MOTION_DURATION_MEDIUM_1)
                        .setInterpolator(new AccelerateDecelerateInterpolator())
                        .start();
                    break;
            }
            return false; // Allow click to proceed
        });
    }
    
    /**
     * Apply Material 3 motion patterns to FABs
     */
    private static void applyFABMotion(@NonNull FloatingActionButton fab) {
        fab.setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    v.animate()
                        .scaleX(0.92f)
                        .scaleY(0.92f)
                        .setDuration(MOTION_DURATION_SHORT_1)
                        .setInterpolator(new DecelerateInterpolator())
                        .start();
                    break;
                case android.view.MotionEvent.ACTION_UP:
                case android.view.MotionEvent.ACTION_CANCEL:
                    v.animate()
                        .scaleX(1.0f)
                        .scaleY(1.0f)
                        .setDuration(MOTION_DURATION_SHORT_2)
                        .setInterpolator(new AccelerateDecelerateInterpolator())
                        .start();
                    break;
            }
            return false; // Allow click to proceed
        });
    }
    
    /**
     * Apply accessibility features for Material 3 compliance
     */
    private static void applyAccessibilityFeatures(@NonNull View view) {
        // Ensure minimum touch target size (48dp)
        int minTouchTarget = view.getContext().getResources().getDimensionPixelSize(
            androidx.appcompat.R.dimen.abc_action_button_min_height_material);
        
        if (view.getLayoutParams() != null) {
            ViewGroup.LayoutParams params = view.getLayoutParams();
            if (params.width < minTouchTarget) {
                params.width = minTouchTarget;
            }
            if (params.height < minTouchTarget) {
                params.height = minTouchTarget;
            }
            view.setLayoutParams(params);
        }
        
        // Ensure focusable and clickable for accessibility
        view.setFocusable(true);
        view.setClickable(true);
        
        // Add state description for screen readers
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            view.setStateDescription(getStateDescription(view));
        }
    }
    
    /**
     * Get state description for accessibility
     */
    private static String getStateDescription(@NonNull View view) {
        if (view instanceof MaterialButton) {
            MaterialButton button = (MaterialButton) view;
            return button.isEnabled() ? "Button" : "Button, disabled";
        } else if (view instanceof FloatingActionButton) {
            return "Floating action button";
        } else if (view instanceof Chip) {
            Chip chip = (Chip) view;
            if (chip.isCheckable()) {
                return chip.isChecked() ? "Filter chip, selected" : "Filter chip, not selected";
            } else {
                return "Assist chip";
            }
        }
        return "Interactive element";
    }
    
    /**
     * Animate view with Material 3 fade transition
     */
    public static void animateFadeIn(@NonNull View view) {
        view.setAlpha(0f);
        view.setVisibility(View.VISIBLE);
        view.animate()
            .alpha(1f)
            .setDuration(MOTION_DURATION_MEDIUM_1)
            .setInterpolator(new DecelerateInterpolator())
            .start();
    }
    
    /**
     * Animate view with Material 3 fade transition
     */
    public static void animateFadeOut(@NonNull View view, @NonNull Runnable onComplete) {
        view.animate()
            .alpha(0f)
            .setDuration(MOTION_DURATION_MEDIUM_1)
            .setInterpolator(new AccelerateDecelerateInterpolator())
            .setListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    view.setVisibility(View.GONE);
                    onComplete.run();
                }
            })
            .start();
    }
    
    /**
     * Animate view with Material 3 slide transition
     */
    public static void animateSlideIn(@NonNull View view, SlideDirection direction) {
        float startX = 0f, startY = 0f;
        
        switch (direction) {
            case LEFT:
                startX = -view.getWidth();
                break;
            case RIGHT:
                startX = view.getWidth();
                break;
            case UP:
                startY = -view.getHeight();
                break;
            case DOWN:
                startY = view.getHeight();
                break;
        }
        
        view.setTranslationX(startX);
        view.setTranslationY(startY);
        view.setAlpha(0f);
        view.setVisibility(View.VISIBLE);
        
        view.animate()
            .translationX(0f)
            .translationY(0f)
            .alpha(1f)
            .setDuration(MOTION_DURATION_LONG_1)
            .setInterpolator(new DecelerateInterpolator())
            .start();
    }
    
    // Helper methods for color resources
    private static ColorStateList getColorStateList(@NonNull Context context, int colorRes) {
        return ContextCompat.getColorStateList(context, colorRes);
    }
    
    @ColorInt
    private static int getColor(@NonNull Context context, int colorRes) {
        return ContextCompat.getColor(context, colorRes);
    }
    
    // Enums for styling options
    public enum ButtonStyle {
        FILLED, OUTLINED, TEXT, ICON
    }
    
    public enum CardStyle {
        ELEVATED, FILLED, OUTLINED
    }
    
    public enum ChipStyle {
        ASSIST, FILTER
    }
    
    public enum SlideDirection {
        LEFT, RIGHT, UP, DOWN
    }
}