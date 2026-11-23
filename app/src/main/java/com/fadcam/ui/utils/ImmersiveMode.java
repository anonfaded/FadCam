package com.fadcam.ui.utils;

import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.view.Window;

import androidx.annotation.NonNull;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

/**
 * Keeps the phone and tablet shell immersive: both system bars are hidden and a swipe pulls
 * them back as transient overlays, so the app keeps the space instead of losing it every time
 * the user glances at the clock.
 *
 * <p>Deliberately a no-op on TV and Wear. Those form factors have no status bar to reclaim,
 * their own navigation model, and the main layout already reports zero insets for them.</p>
 */
public final class ImmersiveMode {

    private ImmersiveMode() {
    }

    /** True when the current device should run the app edge to edge with hidden bars. */
    public static boolean isSupported(@NonNull Context context) {
        PackageManager pm = context.getPackageManager();
        boolean tv = pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
                || pm.hasSystemFeature(PackageManager.FEATURE_TELEVISION)
                || pm.hasSystemFeature("android.hardware.type.television");
        boolean wear = pm.hasSystemFeature("android.hardware.type.watch");
        return !tv && !wear;
    }

    /**
     * Hides the bars on a dialog window as well.
     *
     * <p>A dialog or side sheet is its own window, so the activity being immersive does not
     * apply to it. Material's dialog themes also decide their own edge-to-edge behaviour (see
     * {@code CustomBottomSheetDialogTheme}); with that enabled, the sheet reaches the bottom
     * of the screen instead of stopping at a navigation bar that is not even visible.</p>
     */
    public static void applyToWindow(@NonNull Window window) {
        WindowCompat.enableEdgeToEdge(window);
        WindowInsetsControllerCompat controller =
                WindowCompat.getInsetsController(window, window.getDecorView());
        controller.setSystemBarsBehavior(
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        controller.hide(WindowInsetsCompat.Type.systemBars());
    }

    /**
     * Applies the immersive state to the activity window.
     *
     * <p>Safe to call from {@code onResume}; it must not be called from
     * {@code onWindowFocusChanged}, because hiding the bars changes focus and re-applying from
     * there loops on the main thread and starves the first draw.</p>
     */
    public static void apply(@NonNull Activity activity) {
        if (!isSupported(activity)) {
            return;
        }
        Window window = activity.getWindow();
        // enableEdgeToEdge also sets LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES. Without that a
        // device with a notch refuses to draw into the cutout area and Android fills it with
        // black, which is the strip seen on phones with a camera cut-out but not on emulators.
        WindowCompat.enableEdgeToEdge(window);
        WindowCompat.setDecorFitsSystemWindows(window, false);

        WindowInsetsControllerCompat controller =
                WindowCompat.getInsetsController(window, window.getDecorView());
        controller.setSystemBarsBehavior(
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        controller.hide(WindowInsetsCompat.Type.systemBars());
    }
}
