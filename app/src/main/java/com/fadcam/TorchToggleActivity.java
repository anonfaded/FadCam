package com.fadcam;

import com.fadcam.FLog;
import android.app.Activity;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.view.WindowManager;
import android.widget.Toast;

import com.fadcam.dualcam.service.DualCameraRecordingService;
import com.fadcam.services.RecordingService;
import com.fadcam.services.TorchService;
import com.fadcam.SharedPreferencesManager;
import com.fadcam.utils.ServiceUtils;

public class TorchToggleActivity extends Activity {
    private static final String TAG = "TorchToggleActivity";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        try {
            SharedPreferencesManager sharedPreferencesManager = SharedPreferencesManager.getInstance(this);
            Intent intent;

            // If streaming is active, route to the active recording service (controls camera torch)
            if (com.fadcam.FeatureRegistry.streaming().isStreamingEnabled()) {
                boolean dualRunning = ServiceUtils.isServiceRunning(this, DualCameraRecordingService.class)
                        || (sharedPreferencesManager.getCameraSelection() != null
                        && sharedPreferencesManager.getCameraSelection().isDual());
                intent = new Intent(this, dualRunning ? DualCameraRecordingService.class : RecordingService.class);
                intent.setAction(Constants.INTENT_ACTION_TOGGLE_RECORDING_TORCH);
            } else {
                // If not streaming, use TorchService
                intent = new Intent(this, TorchService.class);
                intent.setAction(Constants.INTENT_ACTION_TOGGLE_TORCH);
            }

            // Start the appropriate service
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent);
            } else {
                startService(intent);
            }

        } catch (Exception e) {
            FLog.e(TAG, "Error toggling torch", e);
            showTorchErrorToast();
        } finally {
            moveTaskToBack(true);
            finish();
        }
    }

    private void showTorchErrorToast() {
        Toast.makeText(this, R.string.torch_unavailable, Toast.LENGTH_LONG).show();
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
