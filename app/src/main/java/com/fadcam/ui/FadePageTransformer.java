package com.fadcam.ui;

import android.view.View;
import androidx.annotation.NonNull;
import androidx.viewpager2.widget.ViewPager2;

/**
 * Custom PageTransformer for ViewPager2 that creates a smooth fade animation
 * when transitioning between fragments.
 */
public class FadePageTransformer implements ViewPager2.PageTransformer {
    private static final float MIN_ALPHA = 0.1f;
    private static final float MAX_ALPHA = 1.0f;
    private static final float MIN_SCALE = 0.9f;
    
    @Override
    public void transformPage(@NonNull View page, float position) {
        if (position < -1) { 
            // Page is far off-screen to the left
            page.setAlpha(0f);
        } else if (position <= 1) { 
            // Page is visible or partially visible
            
            // Fade effect: Create a smooth fade transition
            // Full opacity when centered (position=0), fading as it moves away
            float alphaFactor = Math.max(MIN_ALPHA, 1 - Math.abs(position));
            page.setAlpha(alphaFactor);
            
            // Scale effect: Create a subtle zoom transition
            // Full size when centered, slightly smaller when off-center
            float scaleFactor = Math.max(MIN_SCALE, 1 - Math.abs(position * 0.1f));
            page.setScaleX(scaleFactor);
            page.setScaleY(scaleFactor);
            
            // Add a very slight elevation change for a subtle 3D effect
            page.setTranslationZ(-Math.abs(position) * 5);
        } else { 
            // Page is far off-screen to the right
            page.setAlpha(0f);
        }
    }
} 