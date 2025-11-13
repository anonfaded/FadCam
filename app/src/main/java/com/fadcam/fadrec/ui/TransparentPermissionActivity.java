package com.fadcam.fadrec.ui;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import androidx.activity.ComponentActivity;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.fadcam.Constants;
import com.fadcam.fadrec.MediaProjectionHelper;

/**
 * Transparent activity to request screen capture permission without showing the main app.
 * This allows starting recording from the overlay without bringing the app to foreground.
 * Uses ComponentActivity (not AppCompatActivity) to support transparent theme.
 */
public class TransparentPermissionActivity extends ComponentActivity {
    private static final String TAG = "TransparentPermission";
    
    private MediaProjectionHelper mediaProjectionHelper;
    private ActivityResultLauncher<Intent> screenCapturePermissionLauncher;
    private boolean permissionRequested = false;
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        Log.d(TAG, "TransparentPermissionActivity created");
        
        // Check if we already requested permission (activity recreated)
        if (savedInstanceState != null) {
            permissionRequested = savedInstanceState.getBoolean("permissionRequested", false);
            Log.d(TAG, "Activity recreated, permissionRequested: " + permissionRequested);
            if (permissionRequested) {
                // Permission already requested, activity was recreated
                // Don't request again, just wait for result or finish
                return;
            }
        }
        
        // Initialize MediaProjectionHelper
        mediaProjectionHelper = new MediaProjectionHelper(this);
        
        // Register activity result launcher for screen capture permission
        screenCapturePermissionLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                Log.d(TAG, "Screen capture permission result: " + result.getResultCode());
                Log.d(TAG, "Result data is null: " + (result.getData() == null));
                
                if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                    // Permission granted, start recording
                    Intent data = result.getData();
                    
                    Log.d(TAG, "Permission GRANTED - resultCode: " + result.getResultCode());
                    
                    // Send broadcast with permission result to start recording
                    // Use LocalBroadcastManager to guarantee delivery on Android 12+
                    Intent broadcastIntent = new Intent(Constants.ACTION_SCREEN_RECORDING_PERMISSION_GRANTED);
                    broadcastIntent.putExtra("resultCode", result.getResultCode());
                    // Copy the entire data intent into the broadcast (including all extras)
                    if (data.getExtras() != null) {
                        broadcastIntent.putExtras(data.getExtras());
                    }
                    // Also store the data intent itself as a parcelable
                    broadcastIntent.putExtra("mediaProjectionData", data);
                    
                    LocalBroadcastManager.getInstance(this).sendBroadcast(broadcastIntent);
                    
                    Log.d(TAG, "[BROADCAST-SEND] Permission granted broadcast sent via LocalBroadcastManager with data extras");
                } else {
                    // Permission denied or data is null
                    Log.w(TAG, "Screen capture permission DENIED or data null - resultCode: " + result.getResultCode());
                    
                    Intent broadcastIntent = new Intent(Constants.ACTION_SCREEN_RECORDING_PERMISSION_DENIED);
                    LocalBroadcastManager.getInstance(this).sendBroadcast(broadcastIntent);
                }
                
                // Close this transparent activity
                finish();
            }
        );
        
        // Request permission immediately (only if not already requested)
        requestScreenCapturePermission();
    }
    
    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putBoolean("permissionRequested", permissionRequested);
        Log.d(TAG, "Saving state - permissionRequested: " + permissionRequested);
    }
    
    private void requestScreenCapturePermission() {
        if (permissionRequested) {
            Log.d(TAG, "Permission already requested, skipping");
            return;
        }
        
        try {
            Intent permissionIntent = mediaProjectionHelper.createScreenCaptureIntent();
            if (permissionIntent != null) {
                permissionRequested = true;
                Log.d(TAG, "Launching permission dialog");
                screenCapturePermissionLauncher.launch(permissionIntent);
            } else {
                Log.e(TAG, "Failed to create screen capture intent");
                finish();
            }
        } catch (Exception e) {
            Log.e(TAG, "Error requesting screen capture permission", e);
            finish();
        }
    }
}
