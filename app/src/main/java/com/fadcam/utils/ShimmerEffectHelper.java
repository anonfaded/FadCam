package com.fadcam.utils;

import android.animation.ValueAnimator;
import android.content.res.Resources;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.view.animation.LinearInterpolator;
import android.util.Log;

/**
 * Professional shimmer effect utility for skeleton loading states
 * Following industry standards used by YouTube, Instagram, etc.
 */
public class ShimmerEffectHelper {
    private static final String TAG = "ShimmerEffectHelper";
    private static final long SHIMMER_DURATION = 1500; // 1.5 seconds per cycle
    private static final float SHIMMER_WIDTH_FACTOR = 0.3f; // Shimmer width as fraction of view width
    
    /**
     * Apply shimmer effect to a view with automatic cleanup
     */
    public static void applyShimmerEffect(View view) {
        if (view == null) return;
        
        try {
            // Create shimmer drawable
            Drawable shimmerDrawable = createShimmerDrawable(view.getResources());
            
            // Apply as foreground (overlay)
            view.setForeground(shimmerDrawable);
            
            // Create and start animation
            ValueAnimator shimmerAnimator = createShimmerAnimator(view);
            view.setTag(com.fadcam.R.id.shimmer_animator_tag, shimmerAnimator);
            shimmerAnimator.start();
            
            Log.d(TAG, "Shimmer effect applied to view: " + view.getClass().getSimpleName());
            
        } catch (Exception e) {
            Log.e(TAG, "Error applying shimmer effect", e);
        }
    }
    
    /**
     * Remove shimmer effect from a view
     */
    public static void removeShimmerEffect(View view) {
        if (view == null) return;
        
        try {
            // Stop and clear animator
            ValueAnimator animator = (ValueAnimator) view.getTag(com.fadcam.R.id.shimmer_animator_tag);
            if (animator != null) {
                animator.cancel();
                view.setTag(com.fadcam.R.id.shimmer_animator_tag, null);
            }
            
            // Clear foreground
            view.setForeground(null);
            
            Log.d(TAG, "Shimmer effect removed from view: " + view.getClass().getSimpleName());
            
        } catch (Exception e) {
            Log.e(TAG, "Error removing shimmer effect", e);
        }
    }
    
    /**
     * Create professional shimmer drawable with gradient sweep
     */
    private static Drawable createShimmerDrawable(Resources resources) {
        // Base color - darker gray overlay for better visibility
        GradientDrawable baseDrawable = new GradientDrawable();
        baseDrawable.setColor(0x25000000); // Darker subtle overlay
        baseDrawable.setCornerRadius(8); // Match card corner radius
        
        // Shimmer gradient - sweeping highlight with more contrast
        GradientDrawable shimmerGradient = new GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT,
            new int[]{
                0x00FFFFFF, // Transparent
                0x35FFFFFF, // More pronounced light highlight
                0x65FFFFFF, // Brighter highlight for better visibility
                0x35FFFFFF, // More pronounced light highlight  
                0x00FFFFFF  // Transparent
            }
        );
        shimmerGradient.setCornerRadius(8);
        
        // Layer them for proper shimmer effect with enhanced contrast
        return new LayerDrawable(new Drawable[]{baseDrawable, shimmerGradient});
    }
    
    /**
     * Create shimmer animation that sweeps across the view
     */
    private static ValueAnimator createShimmerAnimator(View view) {
        ValueAnimator animator = ValueAnimator.ofFloat(-1f, 1f);
        animator.setDuration(SHIMMER_DURATION);
        animator.setRepeatCount(ValueAnimator.INFINITE);
        animator.setRepeatMode(ValueAnimator.RESTART);
        animator.setInterpolator(new LinearInterpolator());
        
        animator.addUpdateListener(animation -> {
            if (view.getForeground() instanceof LayerDrawable) {
                LayerDrawable layerDrawable = (LayerDrawable) view.getForeground();
                Drawable shimmerLayer = layerDrawable.getDrawable(1);
                
                if (shimmerLayer instanceof GradientDrawable) {
                    float animatedValue = (Float) animation.getAnimatedValue();
                    
                    // Calculate shimmer position
                    int viewWidth = view.getWidth();
                    if (viewWidth > 0) {
                        int shimmerWidth = (int) (viewWidth * SHIMMER_WIDTH_FACTOR);
                        int shimmerPosition = (int) (animatedValue * (viewWidth + shimmerWidth)) - shimmerWidth;
                        
                        // Update gradient bounds to create moving shimmer
                        shimmerLayer.setBounds(shimmerPosition, 0, 
                                             shimmerPosition + shimmerWidth, view.getHeight());
                    }
                }
            }
        });
        
        return animator;
    }
    
    /**
     * Apply shimmer to multiple views at once
     */
    public static void applyShimmerToViews(View... views) {
        for (View view : views) {
            if (view != null) {
                applyShimmerEffect(view);
            }
        }
    }
    
    /**
     * Remove shimmer from multiple views at once
     */
    public static void removeShimmerFromViews(View... views) {
        for (View view : views) {
            if (view != null) {
                removeShimmerEffect(view);
            }
        }
    }
}
