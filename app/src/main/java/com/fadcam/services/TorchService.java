package com.fadcam.services;

import android.app.ActivityManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.preference.PreferenceManager;
import android.util.Log;

import com.fadcam.Constants;
import com.fadcam.RecordingService;
import com.fadcam.ui.HomeFragment;

import java.lang.ref.WeakReference;

public class TorchService extends Service {
    private static final String TAG = "TorchService";
    private boolean isTorchOn = false;
    private HandlerThread handlerThread;
    private Handler handler;
    private SharedPreferences sharedPreferences;
    private static WeakReference<HomeFragment> homeFragmentRef;

    public static void setHomeFragment(HomeFragment fragment) {
        homeFragmentRef = new WeakReference<>(fragment);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        handlerThread = new HandlerThread("TorchThread", android.os.Process.THREAD_PRIORITY_BACKGROUND);
        handlerThread.start();
        handler = new Handler(handlerThread.getLooper());
        sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && Constants.INTENT_ACTION_TOGGLE_TORCH.equals(intent.getAction())) {
            // Check if recording is in progress
            if (isRecordingInProgress()) {
                // Delegate to RecordingService
                Intent recordingIntent = new Intent(this, RecordingService.class);
                recordingIntent.setAction(Constants.BROADCAST_ON_TORCH_STATE_REQUEST);
                startService(recordingIntent);
            } else {
                handler.post(this::toggleTorch);
            }
        }
        return START_STICKY;
    }

    private boolean isRecordingInProgress() {
        // You can check this by either:
        // 1. Using SharedPreferences to track recording state
        // 2. Checking if RecordingService is running
        ActivityManager manager = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        for (ActivityManager.RunningServiceInfo service : manager.getRunningServices(Integer.MAX_VALUE)) {
            if (RecordingService.class.getName().equals(service.service.getClassName())) {
                return true;
            }
        }
        return false;
    }

    private void toggleTorch() {
        try {
            CameraManager cameraManager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
            boolean useBothTorches = sharedPreferences.getBoolean(Constants.PREF_BOTH_TORCHES_ENABLED, false);

            if (useBothTorches) {
                // Toggle both available torches
                for (String id : cameraManager.getCameraIdList()) {
                    CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(id);
                    Boolean hasFlash = characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
                    if (hasFlash != null && hasFlash) {
                        cameraManager.setTorchMode(id, !isTorchOn);
                    }
                }
                isTorchOn = !isTorchOn;
            } else {
                // Use selected single torch
                String selectedTorchSource = sharedPreferences.getString(Constants.PREF_SELECTED_TORCH_SOURCE, null);

                if (selectedTorchSource != null) {
                    isTorchOn = !isTorchOn;
                    cameraManager.setTorchMode(selectedTorchSource, isTorchOn);
                    Log.d(TAG, "Torch turned " + (isTorchOn ? "ON" : "OFF") + " using source: " + selectedTorchSource);
                    
                    // Update UI through callback
                    if (homeFragmentRef != null && homeFragmentRef.get() != null) {
                        homeFragmentRef.get().updateTorchUI(isTorchOn);
                    }
                }
            }

            // Broadcast state change
            Intent intent = new Intent(Constants.BROADCAST_ON_TORCH_STATE_CHANGED);
            intent.putExtra(Constants.INTENT_EXTRA_TORCH_STATE, isTorchOn);
            sendBroadcast(intent);

            Log.d(TAG, "Torch(es) turned " + (isTorchOn ? "ON" : "OFF"));
        } catch (Exception e) {
            Log.e(TAG, "Error toggling torch: " + e.getMessage());
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        if (handlerThread != null) {
            handlerThread.quitSafely();
        }
        super.onDestroy();
    }
} 