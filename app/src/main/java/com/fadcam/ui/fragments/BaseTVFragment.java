package com.fadcam.ui.fragments;

import android.view.KeyEvent;
import android.view.View;
import androidx.fragment.app.Fragment;
import com.fadcam.ui.utils.FormFactorHelper;

/**
 * Base fragment class providing common TV/D-pad navigation support.
 * 
 * Fragments extending this class automatically get:
 * - D-pad up/down navigation support (focuses next/previous focusable item)
 * - D-pad center/enter click support (performs click on focused item)
 * - Form-factor detection (easily check if running on TV/Wear)
 * - Standard logging for navigation events
 * 
 * Subclasses can override:
 * - onDpadUp() / onDpadDown() for custom up/down logic
 * - onDpadCenter() for custom center/enter logic
 * - onOtherKeyDown() for other key codes
 */
public abstract class BaseTVFragment extends Fragment {

    /**
     * Get FormFactorHelper instance for form-factor detection.
     */
    protected FormFactorHelper getFormFactorHelper() {
        return FormFactorHelper.getInstance(requireContext());
    }

    /**
     * Handle D-pad key events. Call this from onKeyDown() in subclasses.
     * 
     * Default behavior:
     * - DPAD_UP: Move focus up (return true to consume)
     * - DPAD_DOWN: Move focus down (return true to consume)
     * - DPAD_CENTER/ENTER: Click focused view (return true to consume)
     * - Other keys: Delegate to onOtherKeyDown()
     */
    public boolean handleDpadKeyDown(int keyCode, KeyEvent event) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP:
                return onDpadUp(event);
            
            case KeyEvent.KEYCODE_DPAD_DOWN:
                return onDpadDown(event);
            
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                return onDpadCenter(event);
            
            default:
                return onOtherKeyDown(keyCode, event);
        }
    }

    /**
     * Handle D-pad UP key. Default: navigate focus upward.
     * Override to provide custom behavior.
     */
    protected boolean onDpadUp(KeyEvent event) {
        View view = getView();
        if (view != null) {
            View currentFocus = view.findFocus();
            if (currentFocus != null) {
                return currentFocus.focusSearch(View.FOCUS_UP) != null &&
                       currentFocus.focusSearch(View.FOCUS_UP).requestFocus();
            }
        }
        return false;
    }

    /**
     * Handle D-pad DOWN key. Default: navigate focus downward.
     * Override to provide custom behavior.
     */
    protected boolean onDpadDown(KeyEvent event) {
        View view = getView();
        if (view != null) {
            View currentFocus = view.findFocus();
            if (currentFocus != null) {
                return currentFocus.focusSearch(View.FOCUS_DOWN) != null &&
                       currentFocus.focusSearch(View.FOCUS_DOWN).requestFocus();
            }
        }
        return false;
    }

    /**
     * Handle D-pad CENTER or ENTER key. Default: click focused view.
     * Override to provide custom behavior.
     */
    protected boolean onDpadCenter(KeyEvent event) {
        View view = getView();
        if (view != null) {
            View currentFocus = view.findFocus();
            if (currentFocus != null && currentFocus.isClickable()) {
                currentFocus.performClick();
                return true;
            }
        }
        return false;
    }

    /**
     * Handle other D-pad keys (left, right, etc.).
     * Default: return false to pass to parent.
     * Override to provide custom behavior.
     */
    protected boolean onOtherKeyDown(int keyCode, KeyEvent event) {
        return false;
    }

    /**
     * Utility: Request focus on first focusable child in view hierarchy.
     * Useful for ensuring focus is set when fragment appears.
     */
    protected boolean requestInitialFocus(View rootView) {
        if (rootView != null) {
            return rootView.requestFocus();
        }
        return false;
    }

    /**
     * Utility: Find first focusable view in hierarchy.
     */
    protected View findFirstFocusableView(View view) {
        if (view == null) return null;
        if (view.isFocusable() && view.getVisibility() == View.VISIBLE) {
            return view;
        }
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = findFirstFocusableView(group.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }
}
