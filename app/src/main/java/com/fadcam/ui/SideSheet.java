package com.fadcam.ui;

import android.app.Dialog;
import android.content.Context;
import android.content.res.Configuration;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.FrameLayout;

import androidx.annotation.Nullable;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;

import com.fadcam.R;

/**
 * Turns any Material bottom sheet into a full-height sheet anchored to the
 * trailing edge when the app is in landscape.
 *
 * <p>This is deliberately a window-level adapter, not a new screen: every
 * {@link com.google.android.material.bottomsheet.BottomSheetDialogFragment} in
 * the app keeps its own layout and content, and only the dialog window is
 * re-positioned. {@code MainActivity} installs it once through a fragment
 * lifecycle callback, so new sheets get the behaviour automatically.</p>
 *
 * <p>Portrait is untouched.</p>
 */
public final class SideSheet {

    /** Fraction of the screen width the side sheet may take. */
    private static final float WIDTH_FRACTION = 0.46f;
    private static final int MAX_WIDTH_DP = 440;
    private static final int MIN_WIDTH_DP = 320;

    private SideSheet() {
    }

    public static boolean isLandscape(Context context) {
        return context != null
                && context.getResources().getConfiguration().orientation
                == Configuration.ORIENTATION_LANDSCAPE;
    }

    /**
     * Applies the side-sheet window to a dialog when the device is in landscape.
     * Safe to call for any dialog; only {@link BottomSheetDialog} instances are
     * changed. The work is posted to the decor view so Material has finished its
     * own window layout first.
     */
    public static void applyIfLandscape(@Nullable Dialog dialog) {
        if (!(dialog instanceof BottomSheetDialog)) return;
        if (dialog.getWindow() == null) return;
        if (!isLandscape(dialog.getContext())) return;
        if (!dialog.isShowing()) {
            // Before show(): the window exists but is not attached yet. Configuring
            // it here is what prevents the sheet from flashing across the screen.
            apply((BottomSheetDialog) dialog, false);
            return;
        }
        final View decor = dialog.getWindow().getDecorView();
        if (decor == null) return;
        decor.post(() -> apply((BottomSheetDialog) dialog, true));
    }

    private static void apply(BottomSheetDialog dialog, boolean animateIn) {
        Window window = dialog.getWindow();
        if (window == null) return;
        Context context = dialog.getContext();
        if (!isLandscape(context)) return;

        DisplayMetrics dm = context.getResources().getDisplayMetrics();
        int desired = Math.round(dm.widthPixels * WIDTH_FRACTION);
        int width = Math.max(dp(context, MIN_WIDTH_DP),
                Math.min(desired, dp(context, MAX_WIDTH_DP)));

        window.setGravity(Gravity.END);
        window.setLayout(width, ViewGroup.LayoutParams.MATCH_PARENT);
        window.setDimAmount(0.45f);
        // A no-op window animation. Setting 0 here falls back to the dialog theme's
        // default scale animation, which is what produced the diagonal motion.
        window.setWindowAnimations(R.style.FadCamSideSheetAnimation);

        View sheet = dialog.findViewById(
                com.google.android.material.R.id.design_bottom_sheet);
        if (sheet == null) return;

        ViewGroup.LayoutParams lp = sheet.getLayoutParams();
        lp.width = ViewGroup.LayoutParams.MATCH_PARENT;
        lp.height = ViewGroup.LayoutParams.MATCH_PARENT;
        if (lp instanceof FrameLayout.LayoutParams) {
            ((FrameLayout.LayoutParams) lp).gravity = Gravity.END;
        }
        sheet.setLayoutParams(lp);

        BottomSheetBehavior<View> behavior = BottomSheetBehavior.from(sheet);
        // Full height, never half-expanded, and no drag: this reads as a panel,
        // not as a sheet that still needs to be pulled up.
        behavior.setFitToContents(false);
        behavior.setSkipCollapsed(true);
        behavior.setExpandedOffset(0);
        // COLLAPSED at a full-height peek is the same top position as EXPANDED, so
        // Material has no vertical distance to animate: the sheet cannot slide up
        // from the bottom while the window slides in from the side.
        behavior.setPeekHeight(dm.heightPixels, true);
        behavior.setDraggable(false);
        behavior.setHideable(false);
        behavior.setState(BottomSheetBehavior.STATE_EXPANDED);

        // Keep the last row above the gesture bar / cutout.
        ViewCompat.setOnApplyWindowInsetsListener(sheet, (v, insets) -> {
            Insets bars = insets.getInsets(
                    WindowInsetsCompat.Type.systemBars()
                            | WindowInsetsCompat.Type.displayCutout());
            v.setPadding(v.getPaddingLeft(), v.getPaddingTop(), 0, bars.bottom);
            return insets;
        });
        ViewCompat.requestApplyInsets(sheet);

        final float hiddenOffset = width
                * (ViewCompat.getLayoutDirection(sheet) == ViewCompat.LAYOUT_DIRECTION_RTL
                ? -1f : 1f);
        if (!animateIn) {
            // Off the trailing edge and invisible until the window is attached;
            // Material resets translation during its first layout, so alpha is what
            // guarantees the panel never flashes at its final position.
            sheet.setTranslationX(hiddenOffset);
            sheet.setAlpha(0f);
        } else {
            sheet.setTranslationX(hiddenOffset);
            sheet.setAlpha(1f);
            sheet.animate()
                    .translationX(0f)
                    .setDuration(240L)
                    .setInterpolator(new android.view.animation.DecelerateInterpolator(1.6f))
                    .start();
        }
        com.fadcam.FLog.d("SideSheet", "landscape side sheet width=" + width
                + " height=match_parent animateIn=" + animateIn);
    }

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
