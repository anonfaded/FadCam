package com.fadcam;

import com.fadcam.FLog;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.view.WindowManager;
import android.widget.Toast;

import androidx.activity.ComponentActivity;

import com.fadcam.service.FadRecScreenshotAccessibilityService;

public class ScreenShotCaptureActivity extends ComponentActivity {
    private static final String TAG = "ScreenShotCaptureAct";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        FLog.d(TAG, "onCreate: screenshot shortcut invoked");

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
            FLog.w(TAG, "Accessibility service disabled. Redirecting to settings.");
            Toast.makeText(this, R.string.screenshot_accessibility_enable_needed, Toast.LENGTH_LONG).show();
            Intent settingsIntent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
            settingsIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(settingsIntent);
            finishNow();
            return;
        }

        FadRecScreenshotAccessibilityService.markPendingCapture(this);
        FLog.d(TAG, "Marked pending screenshot capture. Dispatching trigger broadcast.");
        dispatchScreenshotTrigger();
        finishNow();
    }

    private void dispatchScreenshotTrigger() {
        Intent triggerIntent = new Intent(Constants.ACTION_TRIGGER_FADREC_SCREENSHOT);
        triggerIntent.setPackage(getPackageName());
        sendBroadcast(triggerIntent);
        FLog.d(TAG, "Sent screenshot trigger broadcast (initial).");
        // Retry once for reliability when accessibility service reconnects slightly later.
        getWindow().getDecorView().postDelayed(() -> {
            try {
                Intent retryIntent = new Intent(Constants.ACTION_TRIGGER_FADREC_SCREENSHOT);
                retryIntent.setPackage(getPackageName());
                sendBroadcast(retryIntent);
                FLog.d(TAG, "Sent screenshot trigger broadcast (retry).");
            } catch (Exception e) {
                FLog.w(TAG, "Retry screenshot broadcast failed", e);
            }
        }, 200);
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
