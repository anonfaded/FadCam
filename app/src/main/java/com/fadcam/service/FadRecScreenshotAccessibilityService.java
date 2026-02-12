package com.fadcam.service;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.hardware.HardwareBuffer;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.Display;
import android.view.accessibility.AccessibilityEvent;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.Constants;
import com.fadcam.R;
import com.fadcam.utils.PhotoStorageHelper;

import java.util.concurrent.Executors;

public class FadRecScreenshotAccessibilityService extends AccessibilityService {
    private static final String TAG = "FadRecScreenshotSvc";
    private static final String PREFS_NAME = "fadrec_screenshot_shortcut";
    private static final String KEY_PENDING_CAPTURE = "pending_capture";

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final BroadcastReceiver triggerReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null || !Constants.ACTION_TRIGGER_FADREC_SCREENSHOT.equals(intent.getAction())) {
                return;
            }
            Log.d(TAG, "Trigger broadcast received in accessibility service.");
            captureNow();
        }
    };
    private boolean receiverRegistered = false;

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "Accessibility screenshot service created.");
        ensureReceiverRegistered();
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        ensureReceiverRegistered();
        if (consumePendingCapture()) {
            Log.d(TAG, "Pending screenshot request consumed on service connected.");
            captureNow();
        }
    }

    private void ensureReceiverRegistered() {
        if (receiverRegistered) {
            return;
        }
        try {
            IntentFilter filter = new IntentFilter(Constants.ACTION_TRIGGER_FADREC_SCREENSHOT);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(triggerReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                registerReceiver(triggerReceiver, filter);
            }
            receiverRegistered = true;
            Log.d(TAG, "Screenshot trigger receiver registered.");
        } catch (Exception e) {
            Log.w(TAG, "Failed to register screenshot trigger receiver", e);
        }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // No-op. This service is only used for screenshot automation.
    }

    @Override
    public void onInterrupt() {
        // No-op.
    }

    @Override
    public void onDestroy() {
        if (receiverRegistered) {
            try {
                unregisterReceiver(triggerReceiver);
            } catch (Exception ignored) {
            }
            receiverRegistered = false;
        }
        super.onDestroy();
        Log.d(TAG, "Accessibility screenshot service destroyed.");
    }

    private void captureNow() {
        Log.d(TAG, "captureNow invoked");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Log.d(TAG, "Using takeScreenshot API (R+).");
            takeScreenshot(Display.DEFAULT_DISPLAY, Executors.newSingleThreadExecutor(),
                    new TakeScreenshotCallback() {
                        @Override
                        public void onSuccess(@NonNull ScreenshotResult screenshotResult) {
                            Log.d(TAG, "takeScreenshot success callback.");
                            saveScreenshotResult(screenshotResult);
                        }

                        @Override
                        public void onFailure(int errorCode) {
                            Log.e(TAG, "takeScreenshot failed. errorCode=" + errorCode);
                            showToast(R.string.screenshot_capture_failed);
                        }
                    });
            return;
        }

        // API 28-29 fallback: system screenshot action without direct bitmap callback.
        boolean triggered = performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT);
        Log.d(TAG, "Using GLOBAL_ACTION_TAKE_SCREENSHOT fallback. triggered=" + triggered);
        showToast(triggered ? R.string.screenshot_capture_system_saved : R.string.screenshot_capture_failed);
    }

    private void saveScreenshotResult(@NonNull ScreenshotResult screenshotResult) {
        Bitmap hardwareBitmap = null;
        Bitmap copyBitmap = null;
        @Nullable HardwareBuffer hardwareBuffer = screenshotResult.getHardwareBuffer();
        try {
            if (hardwareBuffer == null) {
                showToast(R.string.screenshot_capture_failed);
                return;
            }
            hardwareBitmap = Bitmap.wrapHardwareBuffer(hardwareBuffer, screenshotResult.getColorSpace());
            if (hardwareBitmap == null) {
                showToast(R.string.screenshot_capture_failed);
                return;
            }
            copyBitmap = hardwareBitmap.copy(Bitmap.Config.ARGB_8888, false);
            if (copyBitmap == null) {
                showToast(R.string.screenshot_capture_failed);
                return;
            }
            Uri savedUri = PhotoStorageHelper.saveJpegBitmap(
                    getApplicationContext(),
                    copyBitmap,
                    false,
                    PhotoStorageHelper.ShotSource.FADREC);
            if (savedUri != null) {
                Log.i(TAG, "Screenshot saved successfully. uri=" + savedUri);
                clearPendingCapture();
                Intent updateIntent = new Intent(Constants.ACTION_RECORDING_COMPLETE);
                updateIntent.putExtra(Constants.EXTRA_RECORDING_SUCCESS, true);
                updateIntent.putExtra(Constants.EXTRA_RECORDING_URI_STRING, savedUri.toString());
                sendBroadcast(updateIntent);
                showToast(R.string.screenshot_capture_saved);
            } else {
                Log.e(TAG, "Screenshot save failed: PhotoStorageHelper returned null URI.");
                showToast(R.string.screenshot_capture_failed);
            }
        } catch (Exception e) {
            Log.e(TAG, "saveScreenshotResult exception", e);
            showToast(R.string.screenshot_capture_failed);
        } finally {
            if (copyBitmap != null && !copyBitmap.isRecycled()) {
                copyBitmap.recycle();
            }
            if (hardwareBitmap != null && !hardwareBitmap.isRecycled()) {
                hardwareBitmap.recycle();
            }
            if (hardwareBuffer != null) {
                hardwareBuffer.close();
            }
        }
    }

    private void showToast(int messageResId) {
        mainHandler.post(() -> android.widget.Toast.makeText(
                getApplicationContext(),
                messageResId,
                android.widget.Toast.LENGTH_SHORT
        ).show());
    }

    public static boolean isServiceEnabled(@NonNull Context context) {
        try {
            int enabled = Settings.Secure.getInt(
                    context.getContentResolver(),
                    Settings.Secure.ACCESSIBILITY_ENABLED
            );
            if (enabled != 1) {
                return false;
            }
            String enabledServices = Settings.Secure.getString(
                    context.getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            );
            if (enabledServices == null) {
                return false;
            }
            ComponentName componentName = new ComponentName(context, FadRecScreenshotAccessibilityService.class);
            boolean enabledForApp = enabledServices.contains(componentName.flattenToString());
            Log.d(TAG, "isServiceEnabled=" + enabledForApp + ", enabledServices=" + enabledServices);
            return enabledForApp;
        } catch (Exception e) {
            Log.w(TAG, "isServiceEnabled check failed", e);
            return false;
        }
    }

    private boolean consumePendingCapture() {
        try {
            android.content.SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
            boolean pending = prefs.getBoolean(KEY_PENDING_CAPTURE, false);
            if (pending) {
                prefs.edit().putBoolean(KEY_PENDING_CAPTURE, false).apply();
            }
            return pending;
        } catch (Exception ignored) {
            return false;
        }
    }

    private void clearPendingCapture() {
        try {
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                    .edit()
                    .putBoolean(KEY_PENDING_CAPTURE, false)
                    .apply();
        } catch (Exception ignored) {
        }
    }

    public static void markPendingCapture(@NonNull Context context) {
        try {
            context.getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                    .edit()
                    .putBoolean(KEY_PENDING_CAPTURE, true)
                    .apply();
        } catch (Exception ignored) {
        }
    }
}
