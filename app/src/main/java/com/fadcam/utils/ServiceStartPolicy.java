package com.fadcam.utils;

import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

import androidx.annotation.NonNull;

import com.fadcam.Constants;

/**
 * Central policy for recording-action service start mode.
 * Start actions use foreground start; control/query actions use normal startService.
 */
public final class ServiceStartPolicy {
    private static final String TAG = "ServiceStartPolicy";

    private ServiceStartPolicy() {}

    public static void startRecordingAction(@NonNull Context context, @NonNull Intent intent) {
        final String action = intent.getAction();
        final boolean foreground = isForegroundStartAction(action);
        Log.d(TAG, "dispatch action=" + action + ", mode=" + (foreground ? "foreground" : "service"));
        if (foreground && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    private static boolean isForegroundStartAction(String action) {
        if (action == null) return false;
        return Constants.INTENT_ACTION_START_RECORDING.equals(action)
                || Constants.INTENT_ACTION_START_DUAL_RECORDING.equals(action)
                || Constants.INTENT_ACTION_START_SCREEN_RECORDING.equals(action)
                || Constants.ACTION_START_SCREEN_RECORDING_FROM_OVERLAY.equals(action);
    }
}

