package com.servalabs.cam.receivers;

import com.servalabs.cam.Log;
import com.servalabs.cam.FLog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import com.servalabs.cam.Constants;
import com.servalabs.cam.services.TorchService;

public class TorchToggleReceiver extends BroadcastReceiver {
    private static final String TAG = "TorchToggleReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        FLog.d(TAG, "onReceive called with intent: " + intent);
        FLog.d(TAG, "Intent action: " + (intent != null ? intent.getAction() : "null"));
        FLog.d(TAG, "Intent categories: " + (intent != null ? intent.getCategories() : "null"));

        // Create an intent for TorchService
        Intent torchIntent = new Intent(context, TorchService.class);
        torchIntent.setAction(Constants.INTENT_ACTION_TOGGLE_TORCH);

        try {
            // Use a foreground service start to prevent app from opening
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                FLog.d(TAG, "Starting foreground service for torch toggle");
                context.startForegroundService(torchIntent);
            } else {
                FLog.d(TAG, "Starting service for torch toggle");
                context.startService(torchIntent);
            }
        } catch (Exception e) {
            FLog.e(TAG, "Error starting TorchService", e);
        }

        // Prevent the shortcut from opening the app
        if (intent != null && intent.hasCategory(Intent.CATEGORY_LAUNCHER)) {
            FLog.d(TAG, "Aborting broadcast to prevent app launch");
            abortBroadcast();
        }
    }
}
