package com.fadcam;

import android.app.Activity;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.WindowManager;
import android.widget.Toast;

import com.fadcam.services.RecordingService;
import com.fadcam.services.TorchService;
import com.fadcam.SharedPreferencesManager;

import java.util.Random;

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
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        
        Log.d(TAG, "TorchToggleActivity onCreate called");
        Log.d(TAG, "Intent: " + getIntent());

        try {
            // Check if recording is in progress
            if (!isRecordingInProgress()) {
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
            } else {
                // Log and show toast that torch toggle is ignored during recording
                Log.w(TAG, "Torch toggle ignored: Recording in progress");
                showTorchErrorToast();
            }
        } catch (Exception e) {
            // Log any errors
            Log.e(TAG, "Error handling torch toggle", e);
        }

        // Always finish the activity immediately
        finish();
        
        // Prevent any animation
        overridePendingTransition(0, 0);
    }

    // Method to show humorous toast when torch toggle is ignored
    private void showTorchErrorToast() {
        // Use string resources for torch error messages
        String[] errorMessages = getResources().getStringArray(R.array.torch_error_messages);
        String humorousMessage = errorMessages[new Random().nextInt(errorMessages.length)];
        
        // Show toast on the main thread
        new Handler(Looper.getMainLooper()).post(() -> 
            Toast.makeText(this, humorousMessage, Toast.LENGTH_LONG).show()
        );
    }

    // Method to check if recording is currently active
    private boolean isRecordingInProgress() {
        try {
            // Retrieve the current recording service instance
            SharedPreferencesManager sharedPreferencesManager = SharedPreferencesManager.getInstance(this);
            return sharedPreferencesManager.isRecordingInProgress();
        } catch (Exception e) {
            Log.e(TAG, "Error checking recording status", e);
            return false;
        }
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
