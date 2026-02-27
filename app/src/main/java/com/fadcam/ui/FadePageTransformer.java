package com.fadcam.ui;

import android.view.View;
import androidx.annotation.NonNull;
import androidx.viewpager2.widget.ViewPager2;

/**
 * Custom PageTransformer for ViewPager2 that creates a teleport-style fade animation.
 * Pages fade in/out without any sliding - pure alpha transition for modern look.
 * 
 * Eliminates ViewPager2's default horizontal translation for a "teleport" effect.
 * Prevents click-through by disabling touch events on non-active pages.
 */
public class FadePageTransformer implements ViewPager2.PageTransformer {
    
    @Override
    public void transformPage(@NonNull View page, float position) {
        // Eliminate the default horizontal paging translation
        // This makes pages fade in place instead of sliding
        int pageWidth = page.getWidth();
        page.setTranslationX(-position * pageWidth);
        
        // Calculate alpha based on position
        float alpha = 1 - Math.abs(position);
        
        // Prevent click-through: disable touch events when page is not fully visible
        if (Math.abs(position) > 0.0001f) {
            // Page is transitioning or off-screen - disable touch
            page.setClickable(false);
            page.setFocusable(false);
        } else {
            // Page is centered and fully visible - enable touch
            page.setClickable(true);
            page.setFocusable(true);
        }
        
        // Set alpha (fully transparent if far off-screen)
        if (Math.abs(position) > 1) {
            page.setAlpha(0f);
        } else {
            page.setAlpha(Math.max(0f, alpha));
        }
        
        // Reset any scale/translation that might interfere
        page.setScaleX(1f);
        page.setScaleY(1f);
        page.setTranslationZ(0f);
    }
} 