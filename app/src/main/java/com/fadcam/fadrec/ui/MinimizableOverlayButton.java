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

    // Remember last position when hidden
    private int lastX = -1;
    private int lastY = -1;

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
        
        // Use provided position, or last remembered position, or default
        if (x >= 0 && y >= 0) {
            layoutParams.x = x;
            layoutParams.y = y;
            // Remember this position
            lastX = x;
            lastY = y;
        } else if (lastX >= 0 && lastY >= 0) {
            // Restore last position
            layoutParams.x = lastX;
            layoutParams.y = lastY;
        } else {
            // Default position - AT the right edge, not 40dp away
            DisplayMetrics metrics = context.getResources().getDisplayMetrics();
            layoutParams.x = metrics.widthPixels - dpToPx(16); // At right edge (button width is 16dp)
            layoutParams.y = dpToPx(200);
            lastX = layoutParams.x;
            lastY = layoutParams.y;
        }

        // Add touch listener for dragging
        buttonContainer.setOnTouchListener(this::handleTouch);

        // Add to window
        try {
            windowManager.addView(buttonOverlay, layoutParams);
        } catch (Exception e) {
            Log.e(TAG, "Error adding button to window", e);
            return;
        }

        // Determine edge appearance based on position (don't move button on initial show)
        buttonOverlay.post(() -> determineEdgeFromPosition());
    }

    /**
     * Hide the button
     */
    public void hide() {
        if (buttonOverlay != null && windowManager != null) {
            // Remember position before hiding
            if (layoutParams != null) {
                lastX = layoutParams.x;
                lastY = layoutParams.y;
            }
            try {
                windowManager.removeView(buttonOverlay);
            } catch (Exception e) {
                Log.e(TAG, "Error removing button from window", e);
            }
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
        if (layoutParams != null && layoutParams.width > 0) {
            return layoutParams.width;
        }
        if (buttonContainer != null) {
            int width = buttonContainer.getWidth();
            if (width > 0) return width;
            width = buttonContainer.getMeasuredWidth();
            if (width > 0) return width;
        }
        // Default fallback - compact 16dp
        return dpToPx(16);
    }

    /**
     * Get button height
     */
    public int getButtonHeight() {
        if (layoutParams != null && layoutParams.height > 0) {
            return layoutParams.height;
        }
        if (buttonContainer != null) {
            int height = buttonContainer.getHeight();
            if (height > 0) return height;
            height = buttonContainer.getMeasuredHeight();
            if (height > 0) return height;
        }
        // Default fallback - taller for rotated text
        return dpToPx(75);
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
                    // Drag - snap to nearest edge
                    snapToNearestEdge();
                }
                return true;

            case MotionEvent.ACTION_CANCEL:
                return true;

            default:
                return false;
        }
    }

    /**
     * Snap button to nearest edge and remember the edge position.
     */
    private void snapToNearestEdge() {
        if (layoutParams == null || buttonOverlay == null || buttonContainer == null) {
            return;
        }

        buttonOverlay.post(() -> {
            if (buttonOverlay == null || layoutParams == null) {
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

            // Snap to nearest edge and remember it
            if (minDistance == distanceToLeft) {
                currentEdge = EdgePosition.LEFT;
                layoutParams.x = 0;
                layoutParams.y = Math.max(0, Math.min(layoutParams.y, screenHeight - buttonHeight));
                adaptLayoutForEdge(EdgePosition.LEFT);
            } else if (minDistance == distanceToRight) {
                currentEdge = EdgePosition.RIGHT;
                layoutParams.x = screenWidth - buttonWidth;
                layoutParams.y = Math.max(0, Math.min(layoutParams.y, screenHeight - buttonHeight));
                adaptLayoutForEdge(EdgePosition.RIGHT);
            } else if (minDistance == distanceToTop) {
                currentEdge = EdgePosition.TOP;
                layoutParams.x = Math.max(0, Math.min(layoutParams.x, screenWidth - buttonWidth));
                // Add status bar height offset so button appears below status bar
                layoutParams.y = getStatusBarHeight();
                adaptLayoutForEdge(EdgePosition.TOP);
            } else {
                currentEdge = EdgePosition.BOTTOM;
                layoutParams.x = Math.max(0, Math.min(layoutParams.x, screenWidth - buttonWidth));
                layoutParams.y = screenHeight - buttonHeight;
                adaptLayoutForEdge(EdgePosition.BOTTOM);
            }

            // Remember this position
            lastX = layoutParams.x;
            lastY = layoutParams.y;

            if (windowManager != null) {
                windowManager.updateViewLayout(buttonOverlay, layoutParams);
                updateArrowDirection();
            }
        });
    }

    /**
     * Determine which edge the button is closest to and adapt layout accordingly.
     * Does NOT move the button - just updates appearance based on position.
     */
    private void determineEdgeFromPosition() {
        if (layoutParams == null || buttonOverlay == null || buttonContainer == null) {
            return;
        }

        buttonOverlay.post(() -> {
            if (buttonOverlay == null || layoutParams == null) {
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

            // Determine edge based on which is closest - DON'T move button
            if (minDistance == distanceToLeft) {
                currentEdge = EdgePosition.LEFT;
                adaptLayoutForEdge(EdgePosition.LEFT);
            } else if (minDistance == distanceToRight) {
                currentEdge = EdgePosition.RIGHT;
                adaptLayoutForEdge(EdgePosition.RIGHT);
            } else if (minDistance == distanceToTop) {
                currentEdge = EdgePosition.TOP;
                adaptLayoutForEdge(EdgePosition.TOP);
            } else {
                currentEdge = EdgePosition.BOTTOM;
                adaptLayoutForEdge(EdgePosition.BOTTOM);
            }

            if (windowManager != null) {
                updateArrowDirection();
            }
        });
    }

    /**
     * Adapt button layout based on edge position
     */
    private void adaptLayoutForEdge(EdgePosition edge) {
        if (buttonContainer == null || contentLayout == null || layoutParams == null || labelView == null) {
            return;
        }

        int orientation;
        int backgroundRes;
        float labelRotation;
        int targetWidth;
        int targetHeight;

        switch (edge) {
            case TOP:
                orientation = LinearLayout.HORIZONTAL;
                backgroundRes = R.drawable.compact_arrow_bg_top;
                labelRotation = 0f;
                targetWidth = dpToPx(70);  // Wider for horizontal
                targetHeight = dpToPx(16); // Thin for horizontal - more compact
                break;

            case BOTTOM:
                orientation = LinearLayout.HORIZONTAL;
                backgroundRes = R.drawable.compact_arrow_bg_bottom;
                labelRotation = 0f;
                targetWidth = dpToPx(70);  // Wider for horizontal
                targetHeight = dpToPx(16); // Thin for horizontal - more compact
                break;

            case LEFT:
                orientation = LinearLayout.VERTICAL;
                backgroundRes = R.drawable.compact_arrow_bg_left;
                labelRotation = -90f; // Rotate text vertically (top to bottom)
                targetWidth = dpToPx(16);  // Thin for vertical edges - more compact
                targetHeight = dpToPx(75); // Taller to fit full rotated text ("Layers", "Pages")
                break;

            case RIGHT:
            default:
                orientation = LinearLayout.VERTICAL;
                backgroundRes = R.drawable.compact_arrow_bg_right;
                labelRotation = -90f; // Rotate text vertically (top to bottom)
                targetWidth = dpToPx(16);  // Thin for vertical edges - more compact
                targetHeight = dpToPx(75); // Taller to fit full rotated text ("Layers", "Pages")
                break;
        }

        // Update WindowManager layout params with fixed sizes
        layoutParams.width = targetWidth;
        layoutParams.height = targetHeight;
        
        if (buttonOverlay != null && windowManager != null) {
            try {
                windowManager.updateViewLayout(buttonOverlay, layoutParams);
            } catch (Exception e) {
                Log.e(TAG, "Error updating view layout", e);
            }
        }

        // Update content orientation
        contentLayout.setOrientation(orientation);

        // Update label rotation for vertical text on sides
        labelView.setRotation(labelRotation);

        // Adjust text and arrow sizes based on edge
        if (edge == EdgePosition.LEFT || edge == EdgePosition.RIGHT) {
            // For rotated text on sides, we need to set explicit dimensions
            // When text rotates -90°, its width becomes height and vice versa
            labelView.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 9f);
            arrowIcon.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 11f);
            
            // Set explicit width/height on label TextView to accommodate rotated text
            LinearLayout.LayoutParams labelParams = (LinearLayout.LayoutParams) labelView.getLayoutParams();
            if (labelParams != null) {
                // For rotated text: give it enough height (becomes width after rotation)
                // Reduced from 60 to 50 for more compact fit
                labelParams.width = dpToPx(50); // Enough for "Layers"/"Pages" text
                labelParams.height = dpToPx(12); // Height of text (becomes width after rotation)
                labelView.setLayoutParams(labelParams);
                Log.d(TAG, "Set label dimensions for rotation: " + labelParams.width + "x" + labelParams.height);
            }
            
            // More spacing between label and arrow for better separation
            LinearLayout.LayoutParams arrowParams = (LinearLayout.LayoutParams) arrowIcon.getLayoutParams();
            if (arrowParams != null) {
                arrowParams.topMargin = dpToPx(4);
                arrowIcon.setLayoutParams(arrowParams);
            }
        } else {
            // Normal sizes for top/bottom - no rotation
            labelView.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 9f);
            arrowIcon.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f);
            
            // Reset label to wrap_content for horizontal text
            LinearLayout.LayoutParams labelParams = (LinearLayout.LayoutParams) labelView.getLayoutParams();
            if (labelParams != null) {
                labelParams.width = LinearLayout.LayoutParams.WRAP_CONTENT;
                labelParams.height = LinearLayout.LayoutParams.WRAP_CONTENT;
                labelView.setLayoutParams(labelParams);
            }
            
            // Small margin for top/bottom
            LinearLayout.LayoutParams arrowParams = (LinearLayout.LayoutParams) arrowIcon.getLayoutParams();
            if (arrowParams != null) {
                arrowParams.topMargin = dpToPx(1);
                arrowIcon.setLayoutParams(arrowParams);
            }
        }
        
        // Debug logging
        buttonOverlay.post(() -> {
            Log.d(TAG, "=== Button Layout Debug for " + labelText + " on " + edge + " ===");
            Log.d(TAG, "Button container: " + buttonContainer.getWidth() + "x" + buttonContainer.getHeight());
            Log.d(TAG, "Content layout: " + contentLayout.getWidth() + "x" + contentLayout.getHeight());
            Log.d(TAG, "Label view: " + labelView.getWidth() + "x" + labelView.getHeight() + 
                  " rotation=" + labelView.getRotation());
            Log.d(TAG, "Arrow view: " + arrowIcon.getWidth() + "x" + arrowIcon.getHeight());
        });

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

    /**
     * Get status bar height to account for safe area
     */
    private int getStatusBarHeight() {
        int statusBarHeight = 0;
        int resourceId = context.getResources().getIdentifier("status_bar_height", "dimen", "android");
        if (resourceId > 0) {
            statusBarHeight = context.getResources().getDimensionPixelSize(resourceId);
        }
        return statusBarHeight;
    }
}
