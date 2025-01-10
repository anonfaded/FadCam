package com.fadcam;

import android.app.Activity;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.WindowManager;

import com.fadcam.services.TorchService;

public class TorchToggleActivity extends Activity {
    private static final String TAG = "TorchToggleActivity";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        // Prevent the activity from being visible or interactive
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION);

        // Prevent app from coming to foreground
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        
        Log.d(TAG, "TorchToggleActivity onCreate called");
        Log.d(TAG, "Intent: " + getIntent());

        try {
            // Create an intent for TorchService
            Intent torchIntent = new Intent(this, TorchService.class);
            torchIntent.setAction(Constants.INTENT_ACTION_TOGGLE_TORCH);

            // Log service start attempt
            Log.d(TAG, "Attempting to start TorchService");
            
            // Start service based on Android version
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Log.d(TAG, "Starting foreground service");
                startForegroundService(torchIntent);
            } else {
                Log.d(TAG, "Starting service");
                startService(torchIntent);
            }

            // Log success
            Log.d(TAG, "TorchService started successfully");
        } catch (Exception e) {
            // Log any errors
            Log.e(TAG, "Error starting TorchService", e);
        }

        // Always finish the activity immediately
        finish();
        
        // Prevent any animation
        overridePendingTransition(0, 0);
    }

    @Override
    protected void onStop() {
        super.onStop();
        // Ensure the activity is completely hidden
        finish();
    }

    @Override
    protected void onPause() {
        super.onPause();
        // Prevent app from coming to foreground
        moveTaskToBack(true);
    }
}
