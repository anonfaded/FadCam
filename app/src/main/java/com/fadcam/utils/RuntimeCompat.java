package com.fadcam.utils;

import android.app.ActivityManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.content.res.Configuration;

import androidx.annotation.NonNull;

/**
 * Runtime compatibility helpers for low-resource / legacy devices.
 */
public final class RuntimeCompat {
    private static final int COMPACT_WATCH_UI_SMALLEST_WIDTH_DP_MAX = 280;
    private static final int COMPACT_WATCH_UI_WIDTH_DP_MAX = 300;
    private static final int COMPACT_WATCH_UI_HEIGHT_DP_MAX = 320;

    private RuntimeCompat() {}

    public static boolean isWatchDevice(@NonNull Context context) {
        try {
            PackageManager pm = context.getPackageManager();
            return pm != null && pm.hasSystemFeature(PackageManager.FEATURE_WATCH);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * Returns whether the app should present the watch UI.
     *
     * <p>This intentionally differs from {@link #isWatchDevice(Context)}. Some small round
     * Android watches do not advertise {@link PackageManager#FEATURE_WATCH}, but still need the
     * watch-optimized UI to avoid rendering the full phone dashboard on a tiny display.</p>
     */
    public static boolean shouldUseWatchUi(@NonNull Context context) {
        return isWatchDevice(context) || isWatchUiMode(context) || isCompactRoundDevice(context);
    }

    public static boolean isCompactRoundDevice(@NonNull Context context) {
        try {
            Configuration configuration = context.getResources().getConfiguration();
            if (configuration == null) {
                return false;
            }

            boolean isRound = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                    && configuration.isScreenRound();
            if (!isRound) {
                return false;
            }

            int smallestWidthDp = configuration.smallestScreenWidthDp;
            int widthDp = configuration.screenWidthDp;
            int heightDp = configuration.screenHeightDp;

            boolean compactSmallestWidth = smallestWidthDp != Configuration.SMALLEST_SCREEN_WIDTH_DP_UNDEFINED
                    && smallestWidthDp > 0
                    && smallestWidthDp <= COMPACT_WATCH_UI_SMALLEST_WIDTH_DP_MAX;
            boolean compactWidth = widthDp > 0 && widthDp <= COMPACT_WATCH_UI_WIDTH_DP_MAX;
            boolean compactHeight = heightDp > 0 && heightDp <= COMPACT_WATCH_UI_HEIGHT_DP_MAX;
            return compactSmallestWidth || compactWidth || compactHeight;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean isWatchUiMode(@NonNull Context context) {
        try {
            Configuration configuration = context.getResources().getConfiguration();
            if (configuration == null) {
                return false;
            }
            return (configuration.uiMode & Configuration.UI_MODE_TYPE_MASK)
                    == Configuration.UI_MODE_TYPE_WATCH;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static boolean isLowRamDevice(@NonNull Context context) {
        try {
            ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            return am != null && am.isLowRamDevice();
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static boolean shouldUseSafeMediaProbe(@NonNull Context context) {
        return isWatchDevice(context) || Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q || isLowRamDevice(context);
    }

    public static boolean shouldUseSafeMotionAnalysis(@NonNull Context context) {
        return isWatchDevice(context) || Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q || isLowRamDevice(context);
    }
}
