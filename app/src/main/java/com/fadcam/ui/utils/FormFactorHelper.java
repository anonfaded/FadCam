package com.fadcam.ui.utils;

import android.content.Context;
import android.content.pm.PackageManager;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.WindowManager;
import androidx.annotation.NonNull;

/**
 * Helper class for detecting device form factors (TV, Wear, Phone) and providing
 * form-factor-specific utilities for adaptive UI and navigation.
 * 
 * Used for:
 * - Detecting TV vs Phone vs Wear OS devices
 * - D-pad navigation support checks
 * - Screen size and orientation detection
 * - Form-factor-specific UI adjustments
 */
public class FormFactorHelper {
    private static FormFactorHelper instance;
    private final Context context;
    private Boolean isTV;
    private Boolean isWear;
    private Boolean isPhone;
    private Integer screenDensityDpi;
    private Boolean isLandscape;

    private FormFactorHelper(@NonNull Context context) {
        this.context = context.getApplicationContext();
    }

    /**
     * Singleton instance of FormFactorHelper.
     */
    public static synchronized FormFactorHelper getInstance(@NonNull Context context) {
        if (instance == null) {
            instance = new FormFactorHelper(context);
        }
        return instance;
    }

    /**
     * Check if running on Android TV.
     * TV devices have FEATURE_TELEVISION or large screens with no touchscreen.
     */
    public boolean isTV() {
        if (isTV == null) {
            PackageManager pm = context.getPackageManager();
            isTV = pm.hasSystemFeature(PackageManager.FEATURE_TELEVISION) ||
                   (!pm.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN) &&
                    getScreenDensityDpi() < 300);
        }
        return isTV;
    }

    /**
     * Check if running on Wear OS smartwatch.
     * Wear OS devices have FEATURE_WATCH hardware type.
     */
    public boolean isWearOS() {
        if (isWear == null) {
            PackageManager pm = context.getPackageManager();
            isWear = pm.hasSystemFeature("android.hardware.type.watch");
        }
        return isWear;
    }

    /**
     * Check if running on conventional smartphone (not TV or Wear).
     */
    public boolean isPhone() {
        if (isPhone == null) {
            isPhone = !isTV() && !isWearOS();
        }
        return isPhone;
    }

    /**
     * Check if it's a TV-like form factor (TV or Wear OS).
     * TV-like devices typically use D-pad navigation and benefit from similar layout adjustments.
     */
    public boolean isTVLikeFormFactor() {
        return isTV() || isWearOS();
    }

    /**
     * Get current screen orientation.
     * @return true if landscape, false if portrait
     */
    public boolean isLandscape() {
        if (isLandscape == null) {
            Display display = ((WindowManager) context.getSystemService(Context.WINDOW_SERVICE))
                    .getDefaultDisplay();
            isLandscape = display.getWidth() > display.getHeight();
        }
        return isLandscape;
    }

    /**
     * Get screen density DPI.
     * Used to determine if device is likely a TV (low DPI for large screens).
     */
    public int getScreenDensityDpi() {
        if (screenDensityDpi == null) {
            DisplayMetrics metrics = context.getResources().getDisplayMetrics();
            screenDensityDpi = metrics.densityDpi;
        }
        return screenDensityDpi;
    }

    /**
     * Clear cached values (for testing purposes).
     */
    public void clearCache() {
        isTV = null;
        isWear = null;
        isPhone = null;
        screenDensityDpi = null;
        isLandscape = null;
    }

    @Override
    public String toString() {
        return "FormFactorHelper{" +
                "isTV=" + isTV() +
                ", isWear=" + isWearOS() +
                ", isPhone=" + isPhone() +
                ", isLandscape=" + isLandscape() +
                ", densityDpi=" + getScreenDensityDpi() +
                '}';
    }
}
