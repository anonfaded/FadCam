package com.fadcam.utils;

import android.app.ActivityManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;

import androidx.annotation.NonNull;

/**
 * Runtime compatibility helpers for low-resource / legacy devices.
 */
public final class RuntimeCompat {
    private RuntimeCompat() {}

    public static boolean isWatchDevice(@NonNull Context context) {
        try {
            PackageManager pm = context.getPackageManager();
            return pm != null && pm.hasSystemFeature(PackageManager.FEATURE_WATCH);
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
}

