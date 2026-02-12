package com.fadcam;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.widget.Toast;
import com.fadcam.services.RecordingService;

public class RecordingStopActivity extends Activity {
    private static final String TAG = "RecordingStopActivity";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        try {
            // Use the same intent as the main app's stop recording
            Intent stopIntent = new Intent(this, RecordingService.class);
            stopIntent.setAction(Constants.INTENT_ACTION_STOP_RECORDING);
            
            // Start from foreground shortcut activity context to avoid FGS timeout edge cases.
            startService(stopIntent);

        } catch (Exception e) {
            Log.e(TAG, "Error stopping recording via shortcut", e);
            Toast.makeText(this, "Failed to stop recording", Toast.LENGTH_SHORT).show();
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
