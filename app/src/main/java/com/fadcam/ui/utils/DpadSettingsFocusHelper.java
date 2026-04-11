package com.fadcam.ui.utils;

import android.view.View;
import android.view.ViewGroup;

import com.fadcam.R;

/**
 * DpadSettingsFocusHelper
 *
 * Automatically configures D-pad/remote-control focus traversal for settings sub-fragments.
 * Each settings fragment has a {@code content_scroll} ScrollView that—by default—intercepts
 * DPAD_UP / DPAD_DOWN events for scrolling before focusable row children ever see them.
 *
 * This helper:
 *   1. Disables focusability on the ScrollView so Android's FocusFinder traverses its
 *      children directly.
 *   2. Marks the {@code back_button} as focusable so it can receive D-pad center/select.
 *   3. Requests initial focus on the back button or the first focusable row.
 *
 * Call {@link #setup(View)} from a {@link androidx.fragment.app.FragmentManager.FragmentLifecycleCallbacks}
 * registered once in MainActivity — the helper silently no-ops on fragments that do not
 * have a {@code content_scroll} view (e.g. HomeFragment, RecordsFragment).
 */
public final class DpadSettingsFocusHelper {

    private DpadSettingsFocusHelper() {}

    /**
     * Configure D-pad focus for a settings fragment root view.
     *
     * @param root The fragment's root view from {@code onViewCreated}.
     */
    public static void setup(View root) {
        if (root == null) return;

        View scroll = root.findViewById(R.id.content_scroll);
        if (scroll == null) {
            // Not a settings sub-fragment — nothing to do.
            return;
        }

        // Disable focus on the scroll container so DPAD events pass directly to children.
        scroll.setFocusable(false);
        if (scroll instanceof ViewGroup) {
            ((ViewGroup) scroll).setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        }

        // Make the back button navigable via D-pad.
        View backButton = root.findViewById(R.id.back_button);
        if (backButton != null) {
            backButton.setFocusable(true);
            backButton.setClickable(true);
        }

        // Post to allow layout to complete before wiring focus chains and requesting focus.
        root.post(() -> {
            // Find the first focusable row inside the scroll content.
            View firstRow = findFirstFocusableDescendant(scroll);

            // Wire explicit nextFocusDown/Up between back button and first content row.
            // This avoids relying on FocusFinder spatial heuristics which can fail in
            // deeply-nested overlay containers.
            if (backButton != null && firstRow != null && firstRow.getId() != View.NO_ID) {
                backButton.setNextFocusDownId(firstRow.getId());
                firstRow.setNextFocusUpId(backButton.getId() != View.NO_ID ? backButton.getId() : View.NO_ID);
            }

            // Set initial focus on the back button.
            if (backButton != null && backButton.isAttachedToWindow()) {
                backButton.requestFocus();
            } else if (firstRow != null) {
                firstRow.requestFocus();
            }
        });
    }

    /**
     * Public helper: breadth-first search for the first focusable, visible, enabled descendant.
     * Used by MainActivity's onKeyDown generic fallback.
     */
    public static View findFirstFocusable(View root) {
        return findFirstFocusableDescendant(root);
    }

    /**
     * Breadth-first search for the first focusable, visible, enabled descendant.
     */
    private static View findFirstFocusableDescendant(View root) {
        if (!(root instanceof ViewGroup)) return null;
        java.util.ArrayDeque<View> queue = new java.util.ArrayDeque<>();
        queue.add(root);
        while (!queue.isEmpty()) {
            View v = queue.poll();
            if (v != root && v.isFocusable() && v.getVisibility() == View.VISIBLE && v.isEnabled()) {
                return v;
            }
            if (v instanceof ViewGroup) {
                ViewGroup vg = (ViewGroup) v;
                for (int i = 0; i < vg.getChildCount(); i++) {
                    queue.add(vg.getChildAt(i));
                }
            }
        }
        return null;
    }
}
