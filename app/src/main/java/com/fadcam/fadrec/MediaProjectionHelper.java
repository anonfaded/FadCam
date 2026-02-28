package com.fadcam.fadrec;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.util.Log;

import androidx.activity.result.ActivityResultLauncher;
import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;

import com.fadcam.Constants;
import com.fadcam.fadrec.services.ScreenRecordingService;

/**
 * Helper class for handling MediaProjection permission requests.
 * Manages screen capture permission flow for FadRec screen recording.
 * 
 * Usage:
 * 1. Create instance in Fragment/Activity
 * 2. Register with ActivityResultLauncher
 * 3. Call requestPermission() to start screen recording
 */
public class MediaProjectionHelper {
    
    private static final String TAG = "MediaProjectionHelper";
    
    private final Context context;
    private MediaProjectionManager mediaProjectionManager;
    private PermissionCallback callback;
    
    /**
     * Callback interface for permission result
     */
    public interface PermissionCallback {
        void onPermissionGranted(int resultCode, Intent data);
        void onPermissionDenied();
    }
    
    /**
     * Constructor
     * @param context Application or Activity context
     */
    public MediaProjectionHelper(@NonNull Context context) {
        this.context = context;
        this.mediaProjectionManager = (MediaProjectionManager) 
            context.getSystemService(Context.MEDIA_PROJECTION_SERVICE);
    }
    
    /**
     * Set callback for permission results
     * @param callback Callback to receive permission results
     */
    public void setCallback(PermissionCallback callback) {
        this.callback = callback;
    }
    
    /**
     * Create screen capture intent for permission request.
     * Use this with ActivityResultLauncher to request permission.
     * 
     * @return Intent for screen capture permission
     */
    public Intent createScreenCaptureIntent() {
        if (mediaProjectionManager == null) {
            Log.e(TAG, "MediaProjectionManager is null");
            return null;
        }
        
        return mediaProjectionManager.createScreenCaptureIntent();
    }
    
    /**
     * Handle the result from screen capture permission request.
     * Call this from your ActivityResultLauncher callback.
     * 
     * @param resultCode Result code from permission request
     * @param data Intent data from permission request
     */
    public void handlePermissionResult(int resultCode, Intent data) {
        if (resultCode == Activity.RESULT_OK && data != null) {
            Log.d(TAG, "Screen recording permission granted");
            if (callback != null) {
                callback.onPermissionGranted(resultCode, data);
            }
        } else {
            Log.w(TAG, "Screen recording permission denied");
            if (callback != null) {
                callback.onPermissionDenied();
            }
        }
    }
    
    /**
     * Start screen recording service with permission result.
     * This method should be called after permission is granted.
     * CRITICAL: The Intent data from permission is passed as-is to service.
     * 
     * @param resultCode Result code from permission request
     * @param data Intent data from permission request (MUST be the original permission intent)
     */
    public void startScreenRecording(int resultCode, Intent data) {
        Log.d(TAG, "Starting ScreenRecordingService with resultCode=" + resultCode);
        
        if (data == null) {
            Log.e(TAG, "ERROR: data Intent is null - cannot start screen recording");
            return;
        }
        
        try {
            Intent serviceIntent = new Intent(context, ScreenRecordingService.class);
            serviceIntent.setAction(Constants.INTENT_ACTION_START_SCREEN_RECORDING);
            serviceIntent.putExtra("resultCode", resultCode);
            
            // CRITICAL: Copy the exact data from permission request
            // MediaProjectionManager.getMediaProjection() needs this exact intent structure
            if (data.getExtras() != null) {
                serviceIntent.putExtras(data.getExtras());
            }
            
            // Also store the entire intent data as parcelable for safety
            serviceIntent.putExtra("permissionData", data);
            
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent);
            } else {
                context.startService(serviceIntent);
            }
            
            Log.i(TAG, "ScreenRecordingService started successfully");
        } catch (Exception e) {
            Log.e(TAG, "Error starting ScreenRecordingService", e);
        }
    }
    
    /**
     * Stop screen recording service.
     */
    public void stopScreenRecording() {
        Log.d(TAG, "Stopping ScreenRecordingService");
        
        try {
            Intent serviceIntent = new Intent(context, ScreenRecordingService.class);
            serviceIntent.setAction(Constants.INTENT_ACTION_STOP_SCREEN_RECORDING);
            context.startService(serviceIntent);
            Log.i(TAG, "Stop command sent to ScreenRecordingService");
        } catch (Exception e) {
            Log.e(TAG, "Error stopping ScreenRecordingService", e);
        }
    }
    
    /**
     * Pause screen recording.
     */
    public void pauseScreenRecording() {
        Log.d(TAG, "Pausing ScreenRecordingService");
        
        try {
            Intent serviceIntent = new Intent(context, ScreenRecordingService.class);
            serviceIntent.setAction(Constants.INTENT_ACTION_PAUSE_SCREEN_RECORDING);
            context.startService(serviceIntent);
            Log.i(TAG, "Pause command sent to ScreenRecordingService");
        } catch (Exception e) {
            Log.e(TAG, "Error pausing ScreenRecordingService", e);
        }
    }
    
    /**
     * Resume screen recording.
     */
    public void resumeScreenRecording() {
        Log.d(TAG, "Resuming ScreenRecordingService");
        
        try {
            Intent serviceIntent = new Intent(context, ScreenRecordingService.class);
            serviceIntent.setAction(Constants.INTENT_ACTION_RESUME_SCREEN_RECORDING);
            context.startService(serviceIntent);
            Log.i(TAG, "Resume command sent to ScreenRecordingService");
        } catch (Exception e) {
            Log.e(TAG, "Error resuming ScreenRecordingService", e);
        }
    }

    /**
     * Toggle live mute state for FadRec while recording.
     */
    public void setScreenRecordingMuted(boolean muted) {
        try {
            Intent serviceIntent = new Intent(context, ScreenRecordingService.class);
            serviceIntent.setAction(Constants.INTENT_ACTION_SET_SCREEN_RECORDING_MUTE);
            serviceIntent.putExtra(Constants.EXTRA_SCREEN_RECORDING_MUTED, muted);
            context.startService(serviceIntent);
            Log.i(TAG, "Mute toggle sent to ScreenRecordingService: " + muted);
        } catch (Exception e) {
            Log.e(TAG, "Error toggling ScreenRecordingService mute", e);
        }
    }
    
    /**
     * Check if MediaProjectionManager is available.
     * @return true if available
     */
    public boolean isAvailable() {
        return mediaProjectionManager != null;
    }
}
