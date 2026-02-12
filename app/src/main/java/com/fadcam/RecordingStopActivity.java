package com.fadcam;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.widget.Toast;
import com.fadcam.dualcam.service.DualCameraRecordingService;
import com.fadcam.services.RecordingService;
import com.fadcam.utils.ServiceUtils;

public class RecordingStopActivity extends Activity {
    private static final String TAG = "RecordingStopActivity";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        try {
            SharedPreferencesManager sp = SharedPreferencesManager.getInstance(this);
            boolean dualRunning = ServiceUtils.isServiceRunning(this, DualCameraRecordingService.class)
                    || (sp.getCameraSelection() != null && sp.getCameraSelection().isDual() && sp.isRecordingInProgress());
            Intent stopIntent = dualRunning
                    ? new Intent(this, DualCameraRecordingService.class).setAction(Constants.INTENT_ACTION_STOP_DUAL_RECORDING)
                    : new Intent(this, RecordingService.class).setAction(Constants.INTENT_ACTION_STOP_RECORDING);
            
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
