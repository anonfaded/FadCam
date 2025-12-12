package com.fadcam.utils;

import android.app.ActivityManager;
import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;

import java.util.List;

/**
 * Utility methods for working with Android services.
 */
public final class ServiceUtils {

    private static final String TAG = "ServiceUtils";

    private ServiceUtils() {
    }

    /**
     * Checks whether the given service class is currently running in this app process.
     *
     * <p>Note: On modern Android versions, {@link ActivityManager#getRunningServices(int)}
     * is limited but still provides information about the caller's own services.</p>
     *
     * @param context Context
     * @param serviceClass Service class to check
     * @return true if running, false otherwise
     */
    public static boolean isServiceRunning(@NonNull Context context, @NonNull Class<?> serviceClass) {
        try {
            ActivityManager activityManager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            if (activityManager == null) {
                return false;
            }

            List<ActivityManager.RunningServiceInfo> services = activityManager.getRunningServices(Integer.MAX_VALUE);
            if (services == null) {
                return false;
            }

            String targetName = serviceClass.getName();
            for (ActivityManager.RunningServiceInfo service : services) {
                if (service != null && service.service != null && targetName.equals(service.service.getClassName())) {
                    return true;
                }
            }

            return false;
        } catch (SecurityException e) {
            Log.w(TAG, "SecurityException checking running services", e);
            return false;
        } catch (Exception e) {
            Log.w(TAG, "Failed checking running services", e);
            return false;
        }
    }
}
