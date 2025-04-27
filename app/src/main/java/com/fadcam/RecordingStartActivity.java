package com.fadcam;

import android.app.Activity;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.widget.Toast;
import com.fadcam.services.RecordingService;

public class RecordingStartActivity extends Activity {
    private static final String TAG = "RecordingStartActivity";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        try {
            SharedPreferencesManager sharedPreferencesManager = SharedPreferencesManager.getInstance(this);
            // Check if recording is already in progress
            if (sharedPreferencesManager.isRecordingInProgress()) {
                // Toast.makeText(this, R.string.video_recording_started, Toast.LENGTH_SHORT).show();
                Utils.showQuickToast(this, R.string.video_recording_started);

                finish();
                return;
            }

            // Use the same intent as the main app's start recording
            Intent startIntent = new Intent(this, RecordingService.class);
            startIntent.setAction(Constants.INTENT_ACTION_START_RECORDING);
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(startIntent);
            } else {
                startService(startIntent);
            }

        } catch (Exception e) {
            Log.e(TAG, "Error starting recording via shortcut", e);
            Toast.makeText(this, "Failed to start recording", Toast.LENGTH_SHORT).show();
        } finally {
            // Prevent app from coming to foreground
            moveTaskToBack(true);
            finish();
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        finish();
    }

    @Override
    protected void onPause() {
        super.onPause();
        moveTaskToBack(true);
    }
}