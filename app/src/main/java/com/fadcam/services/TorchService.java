package com.fadcam.services;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.preference.PreferenceManager;
import android.util.Log;
import android.widget.Toast;

import androidx.core.app.NotificationCompat;

import com.fadcam.Constants;
import com.fadcam.R;
import com.fadcam.ui.HomeFragment;

import java.lang.ref.WeakReference;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.Random;

public class TorchService extends Service {
    private static final String TAG = "TorchService";
    private static final int NOTIFICATION_ID = 1002;
    private static final String CHANNEL_ID = "TorchServiceChannel";

    private SharedPreferences sharedPreferences;
    private CameraManager cameraManager;
    private NotificationManager notificationManager;
    private HandlerThread handlerThread;
    private Handler backgroundHandler;
    private AtomicBoolean isTorchOn = new AtomicBoolean(false);
    private static WeakReference<HomeFragment> homeFragmentRef;
    private String selectedTorchSource;

    // Static method to set the home fragment
    public static void setHomeFragment(HomeFragment fragment) {
        homeFragmentRef = new WeakReference<>(fragment);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        
        // Initialize system services
        cameraManager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this);

        // Create notification channel for Android Oreo and above
        createNotificationChannel();

        // Setup background thread for torch operations
        handlerThread = new HandlerThread("TorchServiceThread");
        handlerThread.start();
        backgroundHandler = new Handler(handlerThread.getLooper()) {
            @Override
            public void handleMessage(Message msg) {
                switch (msg.what) {
                    case 1: // Toggle Torch
                        toggleTorchInternal();
                        break;
                }
            }
        };

        Log.d(TAG, "TorchService created");
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, 
                "Torch Service", 
                NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Keeps torch service running");
            notificationManager.createNotificationChannel(channel);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            String action = intent.getAction();
            if (Constants.INTENT_ACTION_TOGGLE_TORCH.equals(action)) {
                // Send message to background thread to toggle torch
                backgroundHandler.sendEmptyMessage(1);
            }
        }
        
        // Ensure service stays alive
        return START_STICKY;
    }

    private void toggleTorchInternal() {
        try {
            // Toggle torch state atomically
            boolean newState = !isTorchOn.get();
            selectedTorchSource = sharedPreferences.getString(Constants.PREF_SELECTED_TORCH_SOURCE, null);

            if (selectedTorchSource != null) {
                // Safely toggle torch
                cameraManager.setTorchMode(selectedTorchSource, newState);
                isTorchOn.set(newState);

                // Update UI and broadcast state
                updateUIAndBroadcastState(newState);

                // Manage foreground service and notification
                manageServiceNotification(newState);

                Log.d(TAG, "Torch turned " + (newState ? "ON" : "OFF"));
            }
        } catch (CameraAccessException e) {
            Log.e(TAG, "Camera access error: " + e.getMessage());
            showTorchErrorToast(e.getMessage());
        }
    }

    private void updateUIAndBroadcastState(boolean state) {
        // Update UI callback if available
        if (homeFragmentRef != null && homeFragmentRef.get() != null) {
            new Handler(Looper.getMainLooper()).post(() -> 
                homeFragmentRef.get().updateTorchUI(state)
            );
        }

        // Broadcast state change
        Intent stateIntent = new Intent(Constants.BROADCAST_ON_TORCH_STATE_CHANGED);
        stateIntent.putExtra(Constants.INTENT_EXTRA_TORCH_STATE, state);
        sendBroadcast(stateIntent);
    }

    private void manageServiceNotification(boolean isTorchOn) {
        if (isTorchOn) {
            // Create an intent for turning off the torch
            Intent turnOffIntent = new Intent(this, TorchService.class);
            turnOffIntent.setAction(Constants.INTENT_ACTION_TOGGLE_TORCH);
            
            // Create a PendingIntent for the notification
            PendingIntent pendingIntent = PendingIntent.getService(
                this, 
                0, 
                turnOffIntent, 
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
            );

            // Create and show foreground notification with tap-to-turn-off functionality
            Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Torch is On")
                .setContentText("Tap to turn off")
                .setSmallIcon(R.drawable.ic_flashlight_on)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .setContentIntent(pendingIntent)  // Add tap action to turn off
                .build();

            // For Android 13 and above, explicitly start as a foreground service with a type
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA);
            } else {
                startForeground(NOTIFICATION_ID, notification);
            }
        } else {
            // Stop foreground service
            stopForeground(true);
        }
    }

    private void showTorchErrorToast(String errorMessage) {
        // Use string resources for torch error messages
        String[] errorMessages = getResources().getStringArray(R.array.torch_error_messages);
        String humorousMessage = errorMessages[new Random().nextInt(errorMessages.length)];
        
        Handler handler = new Handler(Looper.getMainLooper());
        handler.post(() -> {
            Toast.makeText(this, humorousMessage, Toast.LENGTH_LONG).show();
        });
    }

    @Override
    public void onDestroy() {
        // Turn off torch safely
        try {
            if (isTorchOn.get() && selectedTorchSource != null) {
                cameraManager.setTorchMode(selectedTorchSource, false);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error turning off torch: " + e.getMessage());
        }

        // Clean up background thread
        if (handlerThread != null) {
            handlerThread.quitSafely();
        }

        super.onDestroy();
        Log.d(TAG, "TorchService destroyed");
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}