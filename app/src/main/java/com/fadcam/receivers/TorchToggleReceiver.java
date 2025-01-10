package com.fadcam.receivers;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

import com.fadcam.Constants;
import com.fadcam.services.TorchService;

public class TorchToggleReceiver extends BroadcastReceiver {
    private static final String TAG = "TorchToggleReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        Log.d(TAG, "onReceive called with intent: " + intent);
        Log.d(TAG, "Intent action: " + (intent != null ? intent.getAction() : "null"));
        Log.d(TAG, "Intent categories: " + (intent != null ? intent.getCategories() : "null"));

        // Create an intent for TorchService
        Intent torchIntent = new Intent(context, TorchService.class);
        torchIntent.setAction(Constants.INTENT_ACTION_TOGGLE_TORCH);

        try {
            // Use a foreground service start to prevent app from opening
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Log.d(TAG, "Starting foreground service for torch toggle");
                context.startForegroundService(torchIntent);
            } else {
                Log.d(TAG, "Starting service for torch toggle");
                context.startService(torchIntent);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error starting TorchService", e);
        }

        // Prevent the shortcut from opening the app
        if (intent != null && intent.hasCategory(Intent.CATEGORY_LAUNCHER)) {
            Log.d(TAG, "Aborting broadcast to prevent app launch");
            abortBroadcast();
        }
    }
}
