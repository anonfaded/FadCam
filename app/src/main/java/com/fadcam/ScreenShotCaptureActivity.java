package com.fadcam;

import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.view.WindowManager;
import android.widget.Toast;

import androidx.activity.ComponentActivity;

import com.fadcam.service.FadRecScreenshotAccessibilityService;

public class ScreenShotCaptureActivity extends ComponentActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        try {
            overridePendingTransition(0, 0);
            if (getWindow() != null) {
                getWindow().addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE);
                getWindow().addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
                getWindow().setDimAmount(0f);
                getWindow().setLayout(1, 1);
            }
        } catch (Exception ignored) {
        }

        if (!FadRecScreenshotAccessibilityService.isServiceEnabled(this)) {
            Toast.makeText(this, R.string.screenshot_accessibility_enable_needed, Toast.LENGTH_LONG).show();
            Intent settingsIntent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
            settingsIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(settingsIntent);
            finishNow();
            return;
        }

        Intent triggerIntent = new Intent(Constants.ACTION_TRIGGER_FADREC_SCREENSHOT);
        sendBroadcast(triggerIntent);
        finishNow();
    }

    private void finishNow() {
        try {
            moveTaskToBack(true);
            overridePendingTransition(0, 0);
        } catch (Exception ignored) {
        }
        finish();
    }
}
