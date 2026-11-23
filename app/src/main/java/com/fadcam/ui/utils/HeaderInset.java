package com.fadcam.ui.utils;

import android.view.View;

import androidx.annotation.NonNull;

/**
 * Applies the status bar / cutout band to a header, a scrolling screen or a screen root without
 * losing their own padding.
 *
 * <p>The band is pushed in directly from {@code MainActivity}, which is the view that actually
 * receives window insets. An {@code OnApplyWindowInsetsListener} on the header does not work
 * here because the fragments sit inside a ConstraintLayout that does not forward insets to its
 * children, so such a listener is never called.</p>
 */
public final class HeaderInset {

    private HeaderInset() {
    }

    /**
     * Applies the band to a scrolling screen. The padding lives on the scroll container and is
     * allowed to be overdrawn, so content starts below the safe area but keeps drawing through
     * it while the user scrolls instead of leaving a solid strip at the top.
     */
    public static void applyScrollBand(@NonNull View scroll, int bandPx) {
        if (scroll instanceof android.view.ViewGroup) {
            ((android.view.ViewGroup) scroll).setClipToPadding(false);
        }
        applyBand(scroll, bandPx);
    }

    /**
     * Adds {@code bandPx} on top of the target's original top padding. The original value is
     * remembered in a tag so repeated calls never accumulate.
     */
    public static void applyBand(@NonNull View target, int bandPx) {
        Object stored = target.getTag(com.fadcam.R.id.screen_header);
        int baseTop = stored instanceof Integer ? (Integer) stored : target.getPaddingTop();
        target.setTag(com.fadcam.R.id.screen_header, baseTop);
        int wanted = baseTop + Math.max(bandPx, 0);
        if (target.getPaddingTop() != wanted) {
            target.setPadding(target.getPaddingLeft(), wanted,
                    target.getPaddingRight(), target.getPaddingBottom());
        }
    }
}
