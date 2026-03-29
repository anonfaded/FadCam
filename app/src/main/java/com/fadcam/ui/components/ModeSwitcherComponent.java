package com.fadcam.ui.components;

import com.fadcam.Log;
import com.fadcam.FLog;
import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.view.View;
import android.view.Gravity;
import android.view.ViewPropertyAnimator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.interpolator.view.animation.FastOutSlowInInterpolator;

import com.fadcam.Constants;
import com.fadcam.R;
import com.fadcam.SharedPreferencesManager;

/**
 * Component for handling the mode switcher (FadCam, FadRec, FadMic) functionality
 * Follows modular design principles with clean separation of concerns
 * Features smooth animated transitions between modes
 */
public class ModeSwitcherComponent {
    private static final String TAG = "ModeSwitcherComponent";
    private static final int ANIMATION_DURATION = 180;
    
    private final Context context;
    private final SharedPreferencesManager sharedPreferencesManager;
    private String currentMode = Constants.MODE_FADCAM;
    private ModeSwitcherListener listener;
    
    // UI References
    private FrameLayout segmentFadCam;
    private FrameLayout segmentFadRec;
    private FrameLayout segmentFadMic;
    private FrameLayout switcherRoot;
    private View activeIndicator;
    private LinearLayout segmentsContainer;
    private ViewPropertyAnimator activeIndicatorAnimator;

    // Guard during initialization
    private boolean isInitializing = false;
    
    /**
     * Interface for mode switcher callbacks
     */
    public interface ModeSwitcherListener {
        void onModeSelected(String mode);
        void onComingSoonRequested(String modeName);
    }
    
    /**
     * Constructor
     * @param context Application context
     */
    public ModeSwitcherComponent(@NonNull Context context) {
        this.context = context;
        this.sharedPreferencesManager = SharedPreferencesManager.getInstance(context);
    }
    
    /**
     * Initialize the mode switcher with the provided view
     * @param rootView The root view containing the mode switcher
     */
    public void initialize(@NonNull View rootView) {
        try {
            isInitializing = true;

            segmentFadCam = rootView.findViewById(R.id.segment_fadcam);
            segmentFadRec = rootView.findViewById(R.id.segment_fadrec);
            segmentFadMic = rootView.findViewById(R.id.segment_fadmic);
            switcherRoot = rootView.findViewById(R.id.mode_switcher_root);
            activeIndicator = rootView.findViewById(R.id.segment_active_indicator);
            segmentsContainer = rootView.findViewById(R.id.mode_switcher_segments);
            if (switcherRoot == null && activeIndicator != null && activeIndicator.getParent() instanceof FrameLayout) {
                switcherRoot = (FrameLayout) activeIndicator.getParent();
                FLog.d(TAG, "initialize: recovered switcherRoot from activeIndicator parent");
            }
            if (switcherRoot == null && segmentsContainer != null && segmentsContainer.getParent() instanceof FrameLayout) {
                switcherRoot = (FrameLayout) segmentsContainer.getParent();
                FLog.d(TAG, "initialize: recovered switcherRoot from segmentsContainer parent");
            }
            FLog.d(TAG, "initialize: switcherRoot=" + (switcherRoot != null)
                    + " activeIndicator=" + (activeIndicator != null)
                    + " segmentsContainer=" + (segmentsContainer != null));

            if (segmentFadCam == null || segmentFadRec == null || segmentFadMic == null) {
                FLog.e(TAG, "Segment views missing");
                return;
            }

            // Show/hide FadRec NEW badge based on NewFeatureManager
            View badgeFadRec = rootView.findViewById(R.id.badge_fadrec);
            if (badgeFadRec != null) {
                try {
                    boolean shouldShowFadRecBadge = com.fadcam.ui.utils.NewFeatureManager.shouldShowBadge(context, "fadrec");
                    badgeFadRec.setVisibility(shouldShowFadRecBadge ? View.VISIBLE : View.GONE);
                    FLog.d(TAG, "FadRec badge visibility: " + (shouldShowFadRecBadge ? "VISIBLE" : "GONE"));
                } catch (Exception e) {
                    FLog.e(TAG, "Error managing FadRec badge visibility", e);
                    badgeFadRec.setVisibility(View.GONE);
                }
            }

            // Resolve persisted mode (fallback if invalid / coming soon)
            String persisted = sharedPreferencesManager.getCurrentRecordingMode();
            if (Constants.MODE_FADMIC.equals(persisted)) persisted = Constants.MODE_FADCAM;
            currentMode = persisted;

            segmentFadCam.setBackground(null);
            segmentFadRec.setBackground(null);
            segmentFadMic.setBackground(null);

            // Set text colors immediately (before view is shown) to prevent flicker
            // This must happen synchronously, not in post()
            android.widget.TextView tvFadCam = (android.widget.TextView) segmentFadCam.getChildAt(0);
            android.widget.TextView tvFadRec = (android.widget.TextView) segmentFadRec.getChildAt(0);
            android.widget.TextView tvFadMic = (android.widget.TextView) segmentFadMic.getChildAt(0);
            
            int whiteColor = ContextCompat.getColor(context, android.R.color.white);
            int grayColor = ContextCompat.getColor(context, R.color.amoled_text_secondary);
            
            if (tvFadCam != null) {
                tvFadCam.setTextColor(Constants.MODE_FADCAM.equals(currentMode) ? whiteColor : grayColor);
                tvFadCam.setTypeface(null, Constants.MODE_FADCAM.equals(currentMode) ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
            }
            if (tvFadRec != null) {
                tvFadRec.setTextColor(Constants.MODE_FADREC.equals(currentMode) ? whiteColor : grayColor);
                tvFadRec.setTypeface(null, Constants.MODE_FADREC.equals(currentMode) ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
            }
            if (tvFadMic != null) {
                tvFadMic.setTextColor(Constants.MODE_FADMIC.equals(currentMode) ? whiteColor : grayColor);
                tvFadMic.setTypeface(null, Constants.MODE_FADMIC.equals(currentMode) ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
            }

            // Set backgrounds after text is set
            // Use post() to ensure view is fully laid out
            // Keep isInitializing true until post() completes to prevent animation
            rootView.post(() -> {
                setExclusiveSelected(currentMode, null);
                isInitializing = false; // Now allow animations on user clicks
            });

            setupClickListeners();
            FLog.d(TAG, "ModeSwitcher initialized (active=" + currentMode + ")");
        } catch (Exception e) {
            FLog.e(TAG, "Error initializing ModeSwitcher", e);
            isInitializing = false; // Reset on error
        }
    }

    /**
     * Set one mode as active, others as inactive.
     * The moving indicator is the only visual active-pill source.
     */
    private void setExclusiveSelected(String mode, Runnable onTransitionComplete) {
        String previousMode = currentMode;
        boolean shouldAnimateTransition = !isInitializing && previousMode != null && !previousMode.equals(mode);

        if (shouldAnimateTransition) {
            animatePillFlow(previousMode, mode, onTransitionComplete);
        } else {
            updateActiveIndicator(mode, false, null);
            if (onTransitionComplete != null) {
                onTransitionComplete.run();
            }
        }

        if (isInitializing) {
            FLog.d(TAG, "setExclusiveSelected - isInitializing=true, only backgrounds applied, skipping text");
        } else {
            FLog.d(TAG, "setExclusiveSelected - user click, current=" + previousMode + ", new=" + mode);

            if (Constants.MODE_FADCAM.equals(previousMode) && !Constants.MODE_FADCAM.equals(mode)) {
                styleText(segmentFadCam, false, false);
                FLog.d(TAG, "Deactivating FadCam (was active)");
            } else if (Constants.MODE_FADREC.equals(previousMode) && !Constants.MODE_FADREC.equals(mode)) {
                styleText(segmentFadRec, false, false);
                FLog.d(TAG, "Deactivating FadRec (was active)");
            } else if (Constants.MODE_FADMIC.equals(previousMode) && !Constants.MODE_FADMIC.equals(mode)) {
                styleText(segmentFadMic, false, false);
                FLog.d(TAG, "Deactivating FadMic (was active)");
            }

            if (Constants.MODE_FADCAM.equals(mode)) {
                styleText(segmentFadCam, true, true);
                FLog.d(TAG, "Activating FadCam (animating)");
            } else if (Constants.MODE_FADREC.equals(mode)) {
                styleText(segmentFadRec, true, true);
                FLog.d(TAG, "Activating FadRec (animating)");
            } else if (Constants.MODE_FADMIC.equals(mode)) {
                styleText(segmentFadMic, true, true);
                FLog.d(TAG, "Activating FadMic (animating)");
            }
        }

        FLog.d(TAG, String.format("setExclusiveSelected(%s): FadCam=%b FadRec=%b FadMic=%b",
            mode, Constants.MODE_FADCAM.equals(mode),
            Constants.MODE_FADREC.equals(mode),
            Constants.MODE_FADMIC.equals(mode)));
    }

    private void applyTextStatesInstant(String mode) {
        styleText(segmentFadCam, Constants.MODE_FADCAM.equals(mode), false);
        styleText(segmentFadRec, Constants.MODE_FADREC.equals(mode), false);
        styleText(segmentFadMic, Constants.MODE_FADMIC.equals(mode), false);
    }
    
    /**
     * Apply background programmatically - no drawable files needed
     * Creates a simple rounded rectangle with solid color
     */
    private void applyBackground(FrameLayout segment, boolean active, boolean animate) {
        if (segment == null) return;
        segment.setSelected(active);
        segment.setBackground(null);
        FLog.d(TAG, "applyBackground: " + segment.getId() + " active=" + active + " animate=" + animate);
    }

    private void updateActiveIndicator(String mode, boolean animate) {
        updateActiveIndicator(mode, animate, null);
    }

    private void updateActiveIndicator(String mode, boolean animate, Runnable onAnimationComplete) {
        if (switcherRoot == null || activeIndicator == null) return;
        FrameLayout target = getSegmentForMode(mode);
        if (target == null) return;

        Runnable apply = () -> {
            int targetLeft = switcherRoot.getPaddingLeft() + target.getLeft();
            int targetTop = 0;
            int targetWidth = target.getWidth();
            int resolvedTargetHeight = switcherRoot.getHeight();
            if (resolvedTargetHeight <= 0) {
                resolvedTargetHeight = target.getHeight();
            }
            final int targetHeight = resolvedTargetHeight;
            if (targetWidth <= 0 || targetHeight <= 0) {
                FLog.w(TAG, "updateActiveIndicator: invalid target size mode=" + mode
                        + " width=" + targetWidth + " height=" + targetHeight);
                return;
            }

            cancelActiveIndicatorAnimation();
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) activeIndicator.getLayoutParams();
            lp.gravity = Gravity.TOP | Gravity.START;
            lp.leftMargin = 0;
            lp.topMargin = 0;
            lp.width = targetWidth;
            lp.height = targetHeight;
            activeIndicator.setBackgroundResource(R.drawable.segment_active_background);
            activeIndicator.setVisibility(View.VISIBLE);
            activeIndicator.setLayoutParams(lp);
            activeIndicator.setY(targetTop);

            if (!animate) {
                activeIndicator.setX(targetLeft);
                FLog.d(TAG, "updateActiveIndicator: positioned mode=" + mode + " left=" + targetLeft);
                if (onAnimationComplete != null) {
                    onAnimationComplete.run();
                }
                return;
            }

            float startLeft = activeIndicator.getX();
            activeIndicatorAnimator = activeIndicator.animate()
                    .x(targetLeft)
                    .setDuration(ANIMATION_DURATION + 70L)
                    .setInterpolator(new FastOutSlowInInterpolator())
                    .withEndAction(() -> {
                        activeIndicatorAnimator = null;
                        activeIndicator.setX(targetLeft);
                        FLog.d(TAG, "updateActiveIndicator: animation end mode=" + mode + " left=" + targetLeft);
                        if (onAnimationComplete != null) {
                            onAnimationComplete.run();
                        }
                    });
            FLog.d(TAG, "updateActiveIndicator: animating mode=" + mode + " fromLeft=" + Math.round(startLeft) + " toLeft=" + targetLeft);
        };

        if (target.getWidth() > 0 && target.getHeight() > 0) {
            apply.run();
        } else {
            switcherRoot.post(apply);
        }
    }

    private void cancelActiveIndicatorAnimation() {
        if (activeIndicatorAnimator != null) {
            activeIndicator.animate().cancel();
            activeIndicatorAnimator = null;
        }
    }

    private void animatePillFlow(String fromMode, String toMode, Runnable onTransitionComplete) {
        applyBackground(segmentFadCam, false, false);
        applyBackground(segmentFadRec, false, false);
        applyBackground(segmentFadMic, false, false);

        FrameLayout fromSegment = getSegmentForMode(fromMode);
        FrameLayout toSegment = getSegmentForMode(toMode);
        int fromLeft = fromSegment != null ? switcherRoot.getPaddingLeft() + fromSegment.getLeft() : -1;
        int toLeft = toSegment != null ? switcherRoot.getPaddingLeft() + toSegment.getLeft() : -1;
        int fromWidth = fromSegment != null ? fromSegment.getWidth() : -1;
        int toWidth = toSegment != null ? toSegment.getWidth() : -1;
        FLog.d(TAG, "animatePillFlow: from=" + fromMode + " to=" + toMode
                + " fromLeft=" + fromLeft + " toLeft=" + toLeft
                + " fromWidth=" + fromWidth + " toWidth=" + toWidth);

        updateActiveIndicator(toMode, true, () -> {
            if (onTransitionComplete != null) {
                onTransitionComplete.run();
            }
        });
    }

    /**
     * Style text (color and weight) with optional animation
     * @param segment The segment to style
     * @param active Whether this segment is active
     * @param animate Whether to animate the color change
     */
    private void styleText(FrameLayout segment, boolean active, boolean animate) {
        if (segment == null) return;
        android.widget.TextView tv = (android.widget.TextView) segment.getChildAt(0);
        if (tv == null) return;
        
        int currentColor = tv.getCurrentTextColor();
        int targetColor = ContextCompat.getColor(context, active ? android.R.color.white : R.color.amoled_text_secondary);
        tv.setTypeface(null, active ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
        
        // Only change color if it's actually different
        if (currentColor == targetColor) {
            FLog.d(TAG, "styleText - segment=" + segment.getId() + " already has correct color, skipping");
            return;
        }
        
        FLog.d(TAG, "styleText - segment=" + segment.getId() + ", active=" + active + ", animate=" + animate);
        
        if (animate) {
            ValueAnimator anim = ValueAnimator.ofObject(new ArgbEvaluator(), currentColor, targetColor);
            anim.setDuration(ANIMATION_DURATION);
            anim.addUpdateListener(a -> tv.setTextColor((Integer) a.getAnimatedValue()));
            anim.start();
            FLog.d(TAG, "Text color animation STARTED for segment " + segment.getId());
        } else {
            // No animation - instant color change
            tv.setTextColor(targetColor);
            FLog.d(TAG, "Text color set INSTANTLY for segment " + segment.getId());
        }
    }
    
    /**
     * Setup click listeners for all segments
     */
    private void setupClickListeners() {
        if (segmentFadCam != null) {
            segmentFadCam.setOnClickListener(v -> handleModeClick(Constants.MODE_FADCAM));
        }
        
        if (segmentFadRec != null) {
            segmentFadRec.setOnClickListener(v -> handleModeClick(Constants.MODE_FADREC));
        }
        
        if (segmentFadMic != null) {
            segmentFadMic.setOnClickListener(v -> handleModeClick(Constants.MODE_FADMIC));
        }
    }
    
    /**
     * Handle mode selection clicks
     * @param mode The selected mode
     */
    private void handleModeClick(String mode) {
        FLog.d(TAG, "Mode clicked: " + mode);
        
        if (isInitializing) return;

        if (mode.equals(currentMode)) return; // no-op

        if (Constants.MODE_FADMIC.equals(mode)) { // blocked mode
            showComingSoonToast("FadMic (Mic Recording)");
            if (listener != null) listener.onComingSoonRequested("FadMic");
            return;
        }

        Runnable modeSelectionAction = () -> {
            if (listener == null) return;
            switch (mode) {
                case Constants.MODE_FADCAM:
                    listener.onModeSelected(mode);
                    break;
                case Constants.MODE_FADREC:
                    try {
                        com.fadcam.ui.utils.NewFeatureManager.markFeatureAsSeen(context, "fadrec");
                        View badgeFadRec = segmentFadRec.getRootView().findViewById(R.id.badge_fadrec);
                        if (badgeFadRec != null) {
                            badgeFadRec.setVisibility(View.GONE);
                        }
                    } catch (Exception e) {
                        FLog.e(TAG, "Error marking FadRec badge as seen", e);
                    }
                    listener.onModeSelected(mode);
                    break;
                default:
                    break;
            }
        };

        setExclusiveSelected(mode, modeSelectionAction);
        currentMode = mode;
        sharedPreferencesManager.setCurrentRecordingMode(currentMode);
        FLog.d(TAG, "Mode switched -> " + currentMode);
    }
    
    /**
     * Update the active state visual indicator
     * @param activeMode The currently active mode
     */
    private void updateActiveState(String activeMode) { setExclusiveSelected(activeMode, null); }

    private void syncActiveStateInstant(String activeMode) {
        if (activeMode == null) return;
        currentMode = activeMode;
        boolean previousInitializing = isInitializing;
        isInitializing = true;
        setExclusiveSelected(activeMode, null);
        applyTextStatesInstant(activeMode);
        isInitializing = previousInitializing;
    }
    
    /**
     * Reset segment to inactive state with smooth animation
     * @param segment The segment to reset
     */
    private void resetSegmentState(FrameLayout segment) { }
    
    /**
     * Set segment to active state with smooth animation
     * @param segment The segment to activate
     */
    private void setSegmentActive(FrameLayout segment) { }
    
    /**
     * Animate background drawable change with smooth transition
     * @param segment The segment to animate
     * @param toActive Whether animating to active or inactive state
     */
    private void animateBackgroundChange(FrameLayout segment, boolean toActive) { }
    
    /**
     * Animate text color change
     * @param textView The TextView to animate
     * @param fromColor Starting color
     * @param toColor Ending color
     */
    private void animateTextColor(android.widget.TextView textView, int fromColor, int toColor) {
        ValueAnimator colorAnimator = ValueAnimator.ofObject(new ArgbEvaluator(), fromColor, toColor);
        colorAnimator.setDuration(ANIMATION_DURATION);
        colorAnimator.addUpdateListener(animator -> 
            textView.setTextColor((Integer) animator.getAnimatedValue()));
        colorAnimator.start();
    }
    
    /**
     * Get the segment view for a specific mode
     * @param mode The mode to get segment for
     * @return The corresponding FrameLayout segment
     */
    private FrameLayout getSegmentForMode(String mode) {
        switch (mode) {
            case Constants.MODE_FADCAM: return segmentFadCam;
            case Constants.MODE_FADREC: return segmentFadRec;
            case Constants.MODE_FADMIC: return segmentFadMic;
            default: return null;
        }
    }
    
    /**
     * Show coming soon toast message
     * @param modeName The name of the mode
     */
    private void showComingSoonToast(String modeName) {
        String message = modeName + " coming soon! 🚀";
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show();
        FLog.d(TAG, "Showed coming soon toast for: " + modeName);
    }
    
    /**
     * Set the mode switcher listener
     * @param listener The listener to set
     */
    public void setListener(ModeSwitcherListener listener) {
        this.listener = listener;
    }
    
    /**
     * Get the current active mode
     * @return The current mode
     */
    public String getCurrentMode() {
        return currentMode;
    }
    
    /**
     * Programmatically set the active mode
     * @param mode The mode to set as active
     */
    public void setActiveMode(String mode) {
        syncActiveStateInstant(mode);
    }
    
    /**
     * Check if the component is properly initialized
     * @return true if initialized, false otherwise
     */
    public boolean isInitialized() {
        return segmentFadCam != null && segmentFadRec != null && segmentFadMic != null;
    }
}
