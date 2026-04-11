package com.fadcam.ui.fragments;

import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import androidx.annotation.Nullable;
import androidx.core.widget.NestedScrollView;
import com.fadcam.FLog;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;

/**
 * Base class for TV-friendly bottom sheet fragments.
 * Provides:
 * - D-pad navigation (up/down) 
 * - Auto-scroll to bring focused items into view on TV/landscape
 * - Focus management
 */
public abstract class BaseTVBottomSheetFragment extends BottomSheetDialogFragment {

    private NestedScrollView scrollView;
    private View focusedView;

    @Override
    public void onViewCreated(View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        
        // Find NestedScrollView if present
        scrollView = findNestedScrollView(view);
        
        // Request focus on first element for D-pad support
        View firstFocusable = findFirstFocusableChild(view);
        if (firstFocusable != null) {
            firstFocusable.requestFocus();
        }
    }

    /**
     * Handle D-pad navigation with auto-scroll.
     * Call this from onKeyDown() in subclasses.
     */
    public boolean handleDpadKeyDown(int keyCode, KeyEvent event) {
        View view = getView();
        if (view == null) return false;
        
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP:
                return navigateUp(view);
                
            case KeyEvent.KEYCODE_DPAD_DOWN:
                return navigateDown(view);
                
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                return clickFocused(view);
                
            default:
                return false;
        }
    }

    private boolean navigateUp(View view) {
        View currentFocus = view.findFocus();
        if (currentFocus == null) {
            View first = findFirstFocusableChild(view);
            return first != null && first.requestFocus();
        }
        
        View next = currentFocus.focusSearch(View.FOCUS_UP);
        if (next != null && next != currentFocus) {
            boolean result = next.requestFocus();
            if (result) {
                autoScrollToView(next);
            }
            return result;
        }
        return false;
    }

    private boolean navigateDown(View view) {
        View currentFocus = view.findFocus();
        if (currentFocus == null) {
            View first = findFirstFocusableChild(view);
            return first != null && first.requestFocus();
        }
        
        View next = currentFocus.focusSearch(View.FOCUS_DOWN);
        if (next != null && next != currentFocus) {
            boolean result = next.requestFocus();
            if (result) {
                autoScrollToView(next);
            }
            return result;
        }
        return false;
    }

    private boolean clickFocused(View view) {
        View focused = view.findFocus();
        if (focused != null && focused.isClickable()) {
            focused.performClick();
            return true;
        }
        return false;
    }

    /**
     * Auto-scroll the bottom sheet to bring focused view into view.
     * Critical for landscape/TV where bottom sheet is clipped.
     */
    private void autoScrollToView(View target) {
        if (scrollView == null) return;
        
        // Get target position relative to scroll view
        int[] targetLoc = new int[2];
        target.getLocationOnScreen(targetLoc);
        
        int[] scrollLoc = new int[2];
        scrollView.getLocationOnScreen(scrollLoc);
        
        int targetY = targetLoc[1] - scrollLoc[1];
        int scrollHeight = scrollView.getHeight();
        int targetHeight = target.getHeight();
        
        // Check if target is below visible area
        if (targetY + targetHeight > scrollHeight) {
            int scroll = (targetY + targetHeight) - scrollHeight + 16; // 16dp padding
            scrollView.smoothScrollBy(0, scroll);
            FLog.d("BaseTVBottomSheet", "Auto-scrolling down to bring item into view");
        }
        // Check if target is above visible area
        else if (targetY < 0) {
            scrollView.smoothScrollBy(0, targetY - 16); // 16dp padding
            FLog.d("BaseTVBottomSheet", "Auto-scrolling up to bring item into view");
        }
    }

    private NestedScrollView findNestedScrollView(View view) {
        if (view instanceof NestedScrollView) {
            return (NestedScrollView) view;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                NestedScrollView found = findNestedScrollView(group.getChildAt(i));
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private View findFirstFocusableChild(View view) {
        if (view == null) return null;
        
        if (view.isFocusable() && view.getVisibility() == View.VISIBLE) {
            return view;
        }
        
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = findFirstFocusableChild(group.getChildAt(i));
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }
}
