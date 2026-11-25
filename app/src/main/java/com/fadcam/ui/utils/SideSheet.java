package com.fadcam.ui.utils;

import android.app.Dialog;
import android.content.res.Configuration;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;

import androidx.annotation.NonNull;

/**
 * Turns a bottom sheet into a side panel in landscape.
 *
 * <p>Portrait keeps the standard bottom sheet. In landscape a sheet rising from the
 * bottom covers most of the short screen and leaves the content behind it unusable, so
 * the same sheet docks to the trailing edge at full height instead — the layout phone
 * editors use for inspector panels. Because it is full height, the whole sheet scrolls
 * rather than only its list.</p>
 */
public final class SideSheet {

    /** Sheet width in landscape, in dp. */
    private static final int LANDSCAPE_WIDTH_DP = 380;

    private SideSheet() {
    }

    /** True when the device is currently laid out in landscape. */
    public static boolean isLandscape(@NonNull View view) {
        return view.getResources().getConfiguration().orientation
                == Configuration.ORIENTATION_LANDSCAPE;
    }

    /**
     * Applies the landscape side-panel geometry to a dialog window. No-op in portrait.
     *
     * @param dialog the sheet's dialog (call from {@code onStart()})
     * @param view   any view belonging to the sheet, used for resources
     */
    public static void applyIfLandscape(@NonNull Dialog dialog, @NonNull View view) {
        if (!isLandscape(view)) return;
        Window window = dialog.getWindow();
        if (window == null) return;

        DisplayMetrics dm = view.getResources().getDisplayMetrics();
        int width = Math.min((int) (LANDSCAPE_WIDTH_DP * dm.density),
                dm.widthPixels - (int) (48 * dm.density));

        WindowManager.LayoutParams lp = window.getAttributes();
        lp.gravity = Gravity.END | Gravity.CENTER_VERTICAL;
        lp.width = width;
        lp.height = WindowManager.LayoutParams.MATCH_PARENT;
        window.setAttributes(lp);

        // Full height, so the sheet's own scroll container is the only scrolling needed.
        window.setLayout(width, WindowManager.LayoutParams.MATCH_PARENT);
    }
}
