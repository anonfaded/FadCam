package com.fadcam.ui;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.content.ContentResolver;
import android.provider.Settings;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

/**
 * Handles the launch reveal animation for the bottom navigation dock.
 *
 * On fresh app launch the dock is hidden, then expands from a small circle
 * in the center outward to its full pill/bar shape, giving a high-quality
 * "materialising" entrance effect.
 *
 * Edge cases handled:
 *  - Config change restores (savedInstanceState != null) → no animation.
 *  - Reduced-motion accessibility setting → no animation, dock shown instantly.
 *  - View not yet laid out (width = 0) → animation is deferred via post().
 *  - Null view safety throughout.
 *  - Cancelled mid-animation (e.g. activity stops) → dock remains fully visible.
 */
public class DockRevealAnimator {

    // Animation timings (ms)
    private static final long START_DELAY_MS    = 50;   // small pause after launch
    private static final long EXPAND_DURATION   = 450;  // circle → pill expansion
    private static final long FADE_IN_DURATION  = 220;  // container alpha 0→1
    private static final long ICONS_DELAY       = 220;  // icons appear after expansion starts
    private static final long ICONS_DURATION    = 260;  // icons alpha 0→1

    // Collapsed scale: at 8 % of full width the card (52 dp tall, 24 dp radius)
    // appears almost circular giving the "pill-circle" start shape.
    private static final float SCALE_START = 0.05f;
    private static final float SCALE_END   = 1.0f;

    // Downward offset the dock travels upward during reveal (dp)
    private static final float SLIDE_DP = 18f;

    private DockRevealAnimator() {}

    /**
     * Plays the dock reveal animation once, immediately after the view has been
     * laid out.
     *
     * @param dockContainer The outer FrameLayout (id: nav_container).
     * @param navView       The BottomNavigationView whose icons fade in separately.
     */
    public static void reveal(final View dockContainer, final View navView) {
        if (dockContainer == null) return;

        // Respect the user's system-wide animation scale (accessibility / battery saving)
        if (isAnimationDisabled(dockContainer)) {
            showInstantly(dockContainer, navView);
            return;
        }

        // Pre-hide immediately (even before layout) to avoid a 1-frame flash
        dockContainer.setScaleX(SCALE_START);
        dockContainer.setAlpha(0f);
        if (navView != null) navView.setAlpha(0f);

        // Ensure layout is done before we read width/height for pivot calculation
        if (dockContainer.getWidth() == 0) {
            dockContainer.post(() -> revealInternal(dockContainer, navView));
        } else {
            revealInternal(dockContainer, navView);
        }
    }

    // ------------------------------------------------------------------
    // Private helpers
    // ------------------------------------------------------------------

    private static void revealInternal(View dockContainer, View navView) {
        final float density = dockContainer.getContext()
                .getResources().getDisplayMetrics().density;
        final float slideY = SLIDE_DP * density;

        // Pivot at horizontal center so expansion is symmetrical
        dockContainer.setPivotX(dockContainer.getWidth() / 2f);
        dockContainer.setPivotY(dockContainer.getHeight() / 2f);

        // ── Initial hidden state ──────────────────────────────────────────
        dockContainer.setScaleX(SCALE_START);
        dockContainer.setAlpha(0f);
        dockContainer.setTranslationY(slideY);
        if (navView != null) navView.setAlpha(0f);

        // ── Animators ─────────────────────────────────────────────────────

        // Custom smooth cubic bezier curve for a premium, expressive feel
        // (0.2, 1, 0.3, 1) starts fast and has a long, elegant tail
        final android.view.animation.Interpolator premiumCurve = 
                new android.view.animation.PathInterpolator(0.2f, 1.0f, 0.3f, 1.0f);

        // 1. Horizontal expansion (circle → pill)
        ObjectAnimator expandX = ObjectAnimator.ofFloat(
                dockContainer, View.SCALE_X, SCALE_START, SCALE_END);
        expandX.setDuration(EXPAND_DURATION);
        expandX.setInterpolator(premiumCurve);

        // 2. Container fade-in (very quick – just to avoid a hard pop)
        ObjectAnimator alphaIn = ObjectAnimator.ofFloat(
                dockContainer, View.ALPHA, 0f, 1f);
        alphaIn.setDuration(FADE_IN_DURATION);

        // 3. Slide up from slightly below
        ObjectAnimator slideUp = ObjectAnimator.ofFloat(
                dockContainer, View.TRANSLATION_Y, slideY, 0f);
        slideUp.setDuration(EXPAND_DURATION);
        slideUp.setInterpolator(premiumCurve);

        // 4. Icons / labels fade in after the pill is mostly expanded
        ObjectAnimator iconsFade = navView != null
                ? ObjectAnimator.ofFloat(navView, View.ALPHA, 0f, 1f)
                : null;
        if (iconsFade != null) {
            iconsFade.setDuration(ICONS_DURATION);
        }

        // ── Animator set ──────────────────────────────────────────────────
        AnimatorSet set = new AnimatorSet();

        if (iconsFade != null) {
            set.play(expandX).with(alphaIn).with(slideUp);
            set.play(iconsFade).after(ICONS_DELAY);
        } else {
            set.playTogether(expandX, alphaIn, slideUp);
        }

        set.setStartDelay(START_DELAY_MS);

        // Safety: on cancel (e.g. activity stopped) make dock fully visible
        set.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationCancel(Animator animation) {
                showInstantly(dockContainer, navView);
            }
            @Override
            public void onAnimationEnd(Animator animation) {
                // Ensure exact final state (avoids floating-point drift)
                dockContainer.setScaleX(SCALE_END);
                dockContainer.setAlpha(1f);
                dockContainer.setTranslationY(0f);
                if (navView != null) navView.setAlpha(1f);
            }
        });

        set.start();
    }

    /** Immediately shows the dock in its final state (no animation). */
    private static void showInstantly(View dockContainer, View navView) {
        if (dockContainer == null) return;
        dockContainer.setScaleX(SCALE_END);
        dockContainer.setAlpha(1f);
        dockContainer.setTranslationY(0f);
        if (navView != null) navView.setAlpha(1f);
    }

    /**
     * Returns true if the user has disabled animations via accessibility or
     * developer options (global animation scale = 0).
     */
    private static boolean isAnimationDisabled(View view) {
        try {
            ContentResolver cr = view.getContext().getContentResolver();
            float scale = Settings.Global.getFloat(cr,
                    Settings.Global.ANIMATOR_DURATION_SCALE, 1f);
            return scale == 0f;
        } catch (Exception ignored) {
            return false;
        }
    }
}
