package com.fadcam.fadrec.ui;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ObjectAnimator;
import android.content.Context;
import android.graphics.PixelFormat;
import android.os.Build;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.fadcam.R;

/**
 * Reusable minimizable overlay button that snaps to screen edges.
 * Shows text label + arrow icon and handles edge snapping behavior.
 * Used for minimizing layer and page overlays.
 */
public class MinimizableOverlayButton {
    private static final String TAG = "MinimizableOverlayButton";
    private static final int SNAP_THRESHOLD_DP = 100;
    private static final long ARROW_ROTATION_DURATION_MS = 260L;

    /**
     * Edge position enum
     */
    public enum EdgePosition {
        LEFT, RIGHT, TOP, BOTTOM, CENTER
    }

    /**
     * Listener for button events
     */
    public interface OnButtonActionListener {
        void onExpandRequested();
        void onMinimizeRequested();
    }

    private final Context context;
    private final WindowManager windowManager;
    private final String labelText;
    private View buttonOverlay;
    private FrameLayout buttonContainer;
    private LinearLayout contentLayout;
    private TextView labelView;
    private TextView arrowIcon;
    private WindowManager.LayoutParams layoutParams;
    private EdgePosition currentEdge = EdgePosition.RIGHT;
    private boolean isExpanded = false;
    private OnButtonActionListener listener;

    // Drag tracking
    private int initialX, initialY;
    private float initialTouchX, initialTouchY;

    /**
     * Constructor
     *
     * @param context Context
     * @param labelText Label text to display (e.g., "Layers", "Pages")
     */
    public MinimizableOverlayButton(Context context, String labelText) {
        this.context = context;
        this.labelText = labelText;
        this.windowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
    }

    /**
     * Set listener for button actions
     */
    public void setOnButtonActionListener(OnButtonActionListener listener) {
        this.listener = listener;
    }

    /**
     * Show the button
     */
    public void show() {
        show(-1, -1);
    }

    /**
     * Show the button at specific position
     * @param x X position (use -1 for default)
     * @param y Y position (use -1 for default)
     */
    public void show(int x, int y) {
        if (buttonOverlay != null) {
            hide();
        }

        LayoutInflater inflater = LayoutInflater.from(context);
        buttonOverlay = inflater.inflate(R.layout.minimizable_overlay_button, null);
        buttonContainer = buttonOverlay.findViewById(R.id.minimizedButtonContainer);
        contentLayout = buttonOverlay.findViewById(R.id.minimizedButtonContent);
        labelView = buttonOverlay.findViewById(R.id.minimizedButtonLabel);
        arrowIcon = buttonOverlay.findViewById(R.id.minimizedButtonArrow);

        labelView.setText(labelText);

        // Setup window params
        int layoutType = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;

        layoutParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                layoutType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
        );

        layoutParams.gravity = Gravity.TOP | Gravity.START;
        
        // Use provided position or default
        if (x >= 0 && y >= 0) {
            layoutParams.x = x;
            layoutParams.y = y;
        } else {
            DisplayMetrics metrics = context.getResources().getDisplayMetrics();
            layoutParams.x = metrics.widthPixels - dpToPx(40); // Default to right side
            layoutParams.y = dpToPx(200);
        }

        // Add touch listener for dragging
        buttonContainer.setOnTouchListener(this::handleTouch);

        // Add to window
        windowManager.addView(buttonOverlay, layoutParams);

        // Initialize position - snap to nearest edge from current position
        buttonOverlay.post(() -> snapToEdgeIfNeeded());
    }

    /**
     * Hide the button
     */
    public void hide() {
        if (buttonOverlay != null && windowManager != null) {
            windowManager.removeView(buttonOverlay);
            buttonOverlay = null;
            layoutParams = null;
        }
    }

    /**
     * Check if button is showing
     */
    public boolean isShowing() {
        return buttonOverlay != null;
    }

    /**
     * Set expanded state (updates arrow direction)
     */
    public void setExpanded(boolean expanded) {
        if (isExpanded == expanded) {
            return;
        }
        isExpanded = expanded;
        updateArrowDirection();
    }

    /**
     * Get current edge position
     */
    public EdgePosition getCurrentEdge() {
        return currentEdge;
    }

    /**
     * Get current X position
     */
    public int getCurrentX() {
        return layoutParams != null ? layoutParams.x : 0;
    }

    /**
     * Get current Y position
     */
    public int getCurrentY() {
        return layoutParams != null ? layoutParams.y : 0;
    }

    /**
     * Get button width
     */
    public int getButtonWidth() {
        if (buttonContainer != null) {
            int width = buttonContainer.getWidth();
            if (width > 0) return width;
            width = buttonContainer.getMeasuredWidth();
            if (width > 0) return width;
        }
        return layoutParams != null ? layoutParams.width : dpToPx(24);
    }

    /**
     * Get button height
     */
    public int getButtonHeight() {
        if (buttonContainer != null) {
            int height = buttonContainer.getHeight();
            if (height > 0) return height;
            height = buttonContainer.getMeasuredHeight();
            if (height > 0) return height;
        }
        return layoutParams != null ? layoutParams.height : dpToPx(100);
    }

    /**
     * Handle touch events for dragging
     */
    private boolean handleTouch(View view, MotionEvent event) {
        if (layoutParams == null || buttonOverlay == null) {
            return false;
        }

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                initialX = layoutParams.x;
                initialY = layoutParams.y;
                initialTouchX = event.getRawX();
                initialTouchY = event.getRawY();
                return true;

            case MotionEvent.ACTION_MOVE:
                int deltaX = Math.round(event.getRawX() - initialTouchX);
                int deltaY = Math.round(event.getRawY() - initialTouchY);

                layoutParams.x = initialX + deltaX; // Add for START gravity
                layoutParams.y = initialY + deltaY;

                windowManager.updateViewLayout(buttonOverlay, layoutParams);
                return true;

            case MotionEvent.ACTION_UP:
                // Check if it was a click or drag
                float distance = (float) Math.hypot(
                        event.getRawX() - initialTouchX,
                        event.getRawY() - initialTouchY
                );

                if (distance < dpToPx(10)) {
                    // Click - toggle expanded state
                    view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                    if (listener != null) {
                        if (isExpanded) {
                            listener.onMinimizeRequested();
                        } else {
                            listener.onExpandRequested();
                        }
                    }
                } else {
                    // Drag - snap to edge
                    snapToEdgeIfNeeded();
                }
                return true;

            case MotionEvent.ACTION_CANCEL:
                return true;

            default:
                return false;
        }
    }

    /**
     * Snap button to nearest edge if within threshold
     */
    private void snapToEdgeIfNeeded() {
        if (layoutParams == null || buttonOverlay == null || buttonContainer == null) {
            return;
        }

        DisplayMetrics metrics = context.getResources().getDisplayMetrics();
        int screenWidth = metrics.widthPixels;
        int screenHeight = metrics.heightPixels;
        
        int buttonWidth = getButtonWidth();
        int buttonHeight = getButtonHeight();
        
        // Calculate center position of button
        int centerX = layoutParams.x + buttonWidth / 2;
        int centerY = layoutParams.y + buttonHeight / 2;

        // Calculate distances to edges
        int distanceToLeft = centerX;
        int distanceToRight = screenWidth - centerX;
        int distanceToTop = centerY;
        int distanceToBottom = screenHeight - centerY;

        int minDistance = Math.min(Math.min(distanceToLeft, distanceToRight),
                Math.min(distanceToTop, distanceToBottom));

        int margin = dpToPx(8);
        
        // Snap to nearest edge
        if (minDistance == distanceToLeft) {
            // Snap to LEFT edge
            currentEdge = EdgePosition.LEFT;
            layoutParams.x = margin;
            layoutParams.y = Math.max(margin, Math.min(layoutParams.y, screenHeight - buttonHeight - margin));
            adaptLayoutForEdge(EdgePosition.LEFT);
        } else if (minDistance == distanceToRight) {
            // Snap to RIGHT edge
            currentEdge = EdgePosition.RIGHT;
            layoutParams.x = screenWidth - buttonWidth - margin;
            layoutParams.y = Math.max(margin, Math.min(layoutParams.y, screenHeight - buttonHeight - margin));
            adaptLayoutForEdge(EdgePosition.RIGHT);
        } else if (minDistance == distanceToTop) {
            // Snap to TOP edge
            currentEdge = EdgePosition.TOP;
            layoutParams.x = Math.max(margin, Math.min(layoutParams.x, screenWidth - buttonWidth - margin));
            layoutParams.y = margin;
            adaptLayoutForEdge(EdgePosition.TOP);
        } else {
            // Snap to BOTTOM edge
            currentEdge = EdgePosition.BOTTOM;
            layoutParams.x = Math.max(margin, Math.min(layoutParams.x, screenWidth - buttonWidth - margin));
            layoutParams.y = screenHeight - buttonHeight - margin;
            adaptLayoutForEdge(EdgePosition.BOTTOM);
        }

        windowManager.updateViewLayout(buttonOverlay, layoutParams);
        updateArrowDirection();
    }

    /**
     * Adapt button layout based on edge position
     */
    private void adaptLayoutForEdge(EdgePosition edge) {
        if (buttonContainer == null || contentLayout == null || layoutParams == null || labelView == null) {
            return;
        }

        int targetWidth;
        int targetHeight;
        int orientation;
        int backgroundRes;
        float labelRotation;

        switch (edge) {
            case TOP:
                targetWidth = dpToPx(100);
                targetHeight = dpToPx(24);
                orientation = LinearLayout.HORIZONTAL;
                backgroundRes = R.drawable.compact_arrow_bg_top;
                labelRotation = 0f;
                break;

            case BOTTOM:
                targetWidth = dpToPx(100);
                targetHeight = dpToPx(24);
                orientation = LinearLayout.HORIZONTAL;
                backgroundRes = R.drawable.compact_arrow_bg_bottom;
                labelRotation = 0f;
                break;

            case LEFT:
                targetWidth = dpToPx(24);
                targetHeight = dpToPx(100);
                orientation = LinearLayout.VERTICAL;
                backgroundRes = R.drawable.compact_arrow_bg_left;
                labelRotation = -90f; // Rotate text vertically (top to bottom)
                break;

            case RIGHT:
            default:
                targetWidth = dpToPx(24);
                targetHeight = dpToPx(100);
                orientation = LinearLayout.VERTICAL;
                backgroundRes = R.drawable.compact_arrow_bg_right;
                labelRotation = -90f; // Rotate text vertically (top to bottom)
                break;
        }

        // Update WindowManager layout params (since buttonContainer is the root view added to WindowManager)
        if (layoutParams.width != targetWidth || layoutParams.height != targetHeight) {
            layoutParams.width = targetWidth;
            layoutParams.height = targetHeight;
            if (buttonOverlay != null && windowManager != null) {
                windowManager.updateViewLayout(buttonOverlay, layoutParams);
            }
        }

        // Update content orientation
        contentLayout.setOrientation(orientation);

        // Update label rotation for vertical text on sides
        labelView.setRotation(labelRotation);

        // Update background
        buttonContainer.setBackgroundResource(backgroundRes);
    }

    /**
     * Update arrow icon direction based on edge and expanded state
     */
    private void updateArrowDirection() {
        if (arrowIcon == null) {
            return;
        }

        String iconText;
        switch (currentEdge) {
            case LEFT:
                iconText = isExpanded ? "chevron_left" : "chevron_right";
                break;
            case RIGHT:
                iconText = isExpanded ? "chevron_right" : "chevron_left";
                break;
            case TOP:
                iconText = isExpanded ? "expand_less" : "expand_more";
                break;
            case BOTTOM:
                iconText = isExpanded ? "expand_more" : "expand_less";
                break;
            case CENTER:
            default:
                iconText = isExpanded ? "chevron_right" : "chevron_left";
                break;
        }

        arrowIcon.setText(iconText);
    }

    /**
     * Convert dp to pixels
     */
    private int dpToPx(float dp) {
        return Math.round(dp * context.getResources().getDisplayMetrics().density);
    }
}
