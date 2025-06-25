package com.fadcam.services;

import android.Manifest;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.media.MediaRecorder;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.PowerManager.WakeLock;
import android.os.SystemClock;
import android.util.Log; // Use standard Log
import android.util.Size;
import android.view.Surface;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.documentfile.provider.DocumentFile;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.pm.ServiceInfo;

import com.fadcam.CameraType;
import com.fadcam.Constants;
import com.fadcam.MainActivity;
import com.fadcam.R;
import com.fadcam.RecordingState;
import com.fadcam.SharedPreferencesManager; // Use your manager
import com.fadcam.Utils;
import com.fadcam.VideoCodec;
import com.fadcam.ui.LocationHelper;
import com.fadcam.ui.GeotagHelper;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

// Add Intent import
// Add Uri import
// Import your Constants class
// Add if needed
// Add if needed


import android.media.MediaRecorder.OnInfoListener;


import org.osmdroid.util.GeoPoint;

import java.util.concurrent.ConcurrentLinkedQueue;

// Add to the beginning of the file
import android.media.MediaMetadataRetriever;

import android.hardware.camera2.CameraConstrainedHighSpeedCaptureSession;
import com.fadcam.utils.DeviceHelper;
import com.fadcam.utils.camera.HighSpeedCaptureHelper;
import com.fadcam.utils.camera.vendor.SamsungFrameRateHelper;
import com.fadcam.utils.camera.vendor.HuaweiFrameRateHelper;

// Add import
import com.fadcam.opengl.GLRecordingPipeline;
import com.fadcam.opengl.WatermarkInfoProvider;



public class RecordingService extends Service {

    private static final int NOTIFICATION_ID = 1;
    private static final String CHANNEL_ID = "RecordingServiceChannel";
    private static final String TAG = "RecordingService"; // Use standard Log TAG
    private static volatile boolean isCameraResourceReleasing = false;

    private CameraDevice cameraDevice;
    private CameraCaptureSession captureSession;
    private CaptureRequest.Builder captureRequestBuilder;
    private Surface previewSurface; // Surface from UI if preview enabled
    private boolean previewSurfaceAdded = false; // Flag to track if preview surface was added to session

    private LocationHelper locationHelper;
    private GeotagHelper geotagHelper;

    private RecordingState recordingState = RecordingState.NONE;
    private AtomicInteger ffmpegProcessingTaskCount = new AtomicInteger(0);

    private boolean isRecordingTorchEnabled = false;

    private CameraManager cameraManager; // Primary camera manager
    private Handler backgroundHandler; // For camera operations

    private long recordingStartTime;

    private SharedPreferencesManager sharedPreferencesManager; // Your settings manager

    private boolean isRolloverClosingOldSession = false; // Flag to manage state during segment rollover when the old session is closing

    private WakeLock recordingWakeLock;

    private boolean isCameraOpen = false;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private boolean pendingStartRecording = false;

    private volatile boolean isStopping = false;

    // --- Lifecycle Methods ---
    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "onCreate: Service creating...");
        // Initialize essential components first
        sharedPreferencesManager = SharedPreferencesManager.getInstance(getApplicationContext());
        
        // Only initialize LocationHelper if location is explicitly enabled
        if (sharedPreferencesManager != null && sharedPreferencesManager.isLocalisationEnabled()) {
            locationHelper = new LocationHelper(this); // For watermark text
        } else {
            Log.d(TAG, "Location feature disabled, skipping LocationHelper initialization");
        }
        
        // Initialize GeotagHelper only if location embedding is enabled
        if (sharedPreferencesManager != null && sharedPreferencesManager.isLocationEmbeddingEnabled()) {
            try {
                geotagHelper = new GeotagHelper(this); // For metadata embedding
                boolean started = geotagHelper.startUpdates();
                Log.d(TAG, "Started geotagging updates for metadata embedding: " + started);
            } catch (Exception e) {
                Log.e(TAG, "Error initializing GeotagHelper", e);
            }
        } else {
            Log.d(TAG, "Location embedding disabled, skipping GeotagHelper initialization");
        }
        
        createNotificationChannel(); // Setup notifications early

        cameraManager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        if(cameraManager == null) {
            Log.e(TAG, "Failed to get CameraManager service.");
            stopSelf(); // Cannot function without CameraManager
            return;
        }

        HandlerThread backgroundThread = new HandlerThread("CameraBackground");
        backgroundThread.start();
        backgroundHandler = new Handler(backgroundThread.getLooper());

        Log.d(TAG, "Service created successfully.");

        // Broadcast initial camera resource availability
        broadcastCameraResourceAvailability(true);
        Log.d(TAG, "Broadcasting initial camera resource availability: true");
    }

    // --- onStartCommand (Ensure START action ignores processing state) ---

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            Log.d(TAG, "onStartCommand received null intent. Ensuring service stays alive.");
            return START_STICKY;
        }
        Log.d(TAG, "onStartCommand received: Action=" + intent.getAction());
        
        String action = intent.getAction();
        if (action == null) {
            Log.w(TAG, "onStartCommand: Action is null.");
            if (!isWorkingInProgress()) stopSelf();
            return START_NOT_STICKY;
        }

        if (Constants.INTENT_ACTION_START_RECORDING.equals(action)) {
            // ----- Check for camera resource cooldown -----
            // Check if camera resources are still being released
            if (isCameraResourceReleasing) {
                Log.w(TAG, "START_RECORDING rejected - camera resources still being released");
                // Show toast on UI thread
                mainHandler.post(() -> {
                    Toast.makeText(getApplicationContext(), 
                        R.string.camera_resources_cooldown, 
                        Toast.LENGTH_LONG).show();
                });
                // Don't stop the service yet as FFmpeg might still be running
                return START_STICKY;
            }
            
            Log.i(TAG, "Handling START_RECORDING intent. Service recording state is " + recordingState);
            
            // Reset recording state if it's somehow corrupted
            if (recordingState != RecordingState.NONE && cameraDevice == null) {
                Log.w(TAG, "Recording state inconsistency detected. Resetting state from " + recordingState + " to NONE.");
                recordingState = RecordingState.NONE;
                sharedPreferencesManager.setRecordingInProgress(false);
            }
            
            // Only proceed if we're in NONE state 
            if (recordingState == RecordingState.NONE) {
                // Update the UI and Service state atomically
                recordingState = RecordingState.STARTING;
                sharedPreferencesManager.setRecordingInProgress(true);
                
                // Set initial torch state
                isRecordingTorchEnabled = intent.getBooleanExtra(Constants.INTENT_EXTRA_INITIAL_TORCH_STATE, false);
                Log.d(TAG, "Initial torch state for recording session: " + isRecordingTorchEnabled);
                
                // Set up preview surface if provided
                setupSurfaceTexture(intent);
                
                // Start foreground service
                setupRecordingInProgressNotification();
                
                // Begin camera/recording setup
                if (cameraDevice == null) {
                    pendingStartRecording = true;
                    Log.d(TAG, "Setting pendingStartRecording=true, will start recording after camera opens");
                    openCamera();
                } else {
                    Log.d(TAG, "Camera already open, starting recording directly");
                    startRecording();
                }
                
                // Notify UI that we're starting
                broadcastOnRecordingStarted();
                
                return START_STICKY;
            } else {
                // If we're not in NONE state, log a warning and notify the user
                Log.w(TAG, "Cannot start recording, already in state: " + recordingState);
                Toast.makeText(this, getString(R.string.recording_already_active), Toast.LENGTH_SHORT).show();
                return START_STICKY;
            }
        } else if (Constants.INTENT_ACTION_STOP_RECORDING.equals(action)) {
            stopRecording();
            return START_STICKY;
        } else if (Constants.INTENT_ACTION_PAUSE_RECORDING.equals(action)) {
            pauseRecording();
            return START_STICKY;
        } else if (Constants.INTENT_ACTION_RESUME_RECORDING.equals(action)) {
            // Set up preview surface if provided (important when resuming)
            setupSurfaceTexture(intent);
            resumeRecording();
            return START_STICKY;
        } else if (Constants.INTENT_ACTION_CHANGE_SURFACE.equals(action)) {
            // Handle surface changes for preview
            setupSurfaceTexture(intent);
            if (glRecordingPipeline != null) {
                glRecordingPipeline.setPreviewSurface(previewSurface);
            }
            if (isRecording() || isPaused()) {
                createCameraPreviewSession();
            }
            return START_STICKY;
        } else if (Constants.BROADCAST_ON_RECORDING_STATE_REQUEST.equals(action)) {
            // Handle UI state sync requests
            Log.d(TAG, "Responding to state request");
            broadcastOnRecordingStateCallback();
            if (!isWorkingInProgress()) {
                stopSelf();
            }
            return START_STICKY;
        } else if (Constants.INTENT_ACTION_TOGGLE_RECORDING_TORCH.equals(action)) {
            // Handle torch toggle requests
            toggleRecordingTorch();
            return START_STICKY;
        } 

        else if (Constants.INTENT_ACTION_REINITIALIZE_LOCATION.equals(action)) {
            // Handle request to reinitialize location helpers after settings change
            Log.d(TAG, "Handling REINITIALIZE_LOCATION intent");
            
            // Extract the embedding preference directly from intent if available
            boolean forceInit = intent.getBooleanExtra("force_init", false);
            boolean embedLocationFromIntent = intent.getBooleanExtra("embed_location", false);
            boolean hasLocationPermission = intent.getBooleanExtra("has_permission", false);
            
            // Log the values for debugging
            Log.d(TAG, "Location intent extras:");
            Log.d(TAG, "  - force_init: " + forceInit);
            Log.d(TAG, "  - embed_location: " + embedLocationFromIntent);
            Log.d(TAG, "  - has_permission: " + hasLocationPermission);
            
            // If embed_location is true but permission is not granted, log warning
            if (embedLocationFromIntent && !hasLocationPermission) {
                Log.w(TAG, "Warning: Location embedding requested but permission is not granted");
                // Don't override preference in this case - let the UI control it
            }
            // If intent explicitly specifies the embed_location value, use it to force override the preference
            else if (intent.hasExtra("embed_location")) {
                Log.d(TAG, "Intent explicitly specifies embed_location=" + embedLocationFromIntent);
                
                // Force the preference to match what was sent in the intent
                if (sharedPreferencesManager.isLocationEmbeddingEnabled() != embedLocationFromIntent) {
                    Log.d(TAG, "Updating preferences to match intent value");
                    sharedPreferencesManager.sharedPreferences.edit()
                        .putBoolean(Constants.PREF_EMBED_LOCATION_DATA, embedLocationFromIntent)
                        .apply();
                }
            }
            
            // Now reinitialize with potential updated preferences
            reinitializeLocationHelpers(forceInit);
            return START_STICKY;
        } 

        else {
            Log.w(TAG, "Unknown action received: " + action);
            if (!isWorkingInProgress()) stopSelf();
            return START_NOT_STICKY;
        }
    }


    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null; // Not a bound service
    }


    @Override
    public void onDestroy() {
        Log.d(TAG, "onDestroy: Service being destroyed...");
        
        // Stop any active reconnection attempts
        stopReconnectionAttempts();
        
        // Make sure dummy surface is released
        releaseDummyBackgroundSurface();
        
        // Ensure all location services are properly stopped
        if (geotagHelper != null) {
            try {
                geotagHelper.stopUpdates();
                Log.d(TAG, "GeotagHelper: Stopped location updates");
            } catch (Exception e) {
                Log.e(TAG, "Error stopping GeotagHelper updates", e);
            }
        }
        
        if (locationHelper != null) {
            try {
                locationHelper.stopLocationUpdates();
                Log.d(TAG, "LocationHelper: Stopped location updates for watermarking");
            } catch (Exception e) {
                Log.e(TAG, "Error stopping LocationHelper updates", e);
            }
        }
        
        // Force recording to stop if somehow it's still active
        if (recordingState != RecordingState.NONE) {
            Log.w(TAG, "Service being destroyed while recording is active. Forcing stop.");
            try {
                stopRecording();
            } catch (Exception e) {
                Log.e(TAG, "Error stopping recording during service destruction", e);
            }
        }
        
        // Clean up camera resources
        try {
            releaseRecordingResources();
        } catch (Exception e) {
            Log.e(TAG, "Error releasing resources during service destruction", e);
        }
        
        // Release wake lock if still held
        if (recordingWakeLock != null && recordingWakeLock.isHeld()) {
            try {
                recordingWakeLock.release();
                Log.d(TAG, "Recording WakeLock released during service destruction.");
            } catch (Exception e) {
                Log.e(TAG, "Error releasing wake lock", e);
            }
        }
        
        Log.d(TAG, "Service destroyed.");
        // Clean up GeotagHelper when service is destroyed
        super.onDestroy();
    }
    // --- End Lifecycle Methods ---


    // --- Core Recording Logic ---
    private void stopRecording() {
        if (isStopping) {
            Log.w(TAG, "stopRecording: Already in stopping process, ignoring duplicate call");
            return;
        }
        
        isStopping = true;
        Log.i(TAG, ">> stopRecording sequence initiated. Current state: " + recordingState);
        
        if (recordingState == RecordingState.NONE) {
            Log.d(TAG, "stopRecording called but state is already NONE, just cleaning up");
            sharedPreferencesManager.setRecordingInProgress(false);
            if (!isWorkingInProgress()) stopSelf();
            if (recordingWakeLock != null && recordingWakeLock.isHeld()) recordingWakeLock.release();
            isStopping = false; // Reset stopping flag if we're already stopped
            return;
        }
        
        // First update the state to prevent any new operations
        recordingState = RecordingState.NONE;
        sharedPreferencesManager.setRecordingInProgress(false);

        // Stop foreground service and cancel notification early to improve responsiveness
        stopForeground(true);
        cancelNotification();
        
        // Use a background thread for resource cleanup to avoid blocking the main thread
        new Thread(() -> {
            try {
                // Set camera resources as releasing and broadcast early
                setCameraResourcesReleasing(true);
                broadcastOnRecordingStopped();
                
                // First stop the capture session
                if (captureSession != null) {
                    try {
                        Log.d(TAG, "Stopping repeating request and closing capture session");
                        captureSession.stopRepeating();
                        captureSession.close();
                    } catch (Exception e) {
                        Log.e(TAG, "Error closing capture session", e);
                    } finally {
                        captureSession = null;
                    }
                }
                
                // Give some time for the session to close
                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                
                // Stop and release the GL pipeline
                if (glRecordingPipeline != null) {
                    try {
                        Log.d(TAG, "Stopping GLRecordingPipeline");
                        glRecordingPipeline.stopRecording();
                    } catch (Exception e) {
                        Log.e(TAG, "Error stopping GLRecordingPipeline", e);
                    } finally {
                        glRecordingPipeline = null;
                    }
                }
                
                // Give some time for the GL pipeline to release resources
                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                
                // Close the camera device last
                if (cameraDevice != null) {
                    try {
                        Log.d(TAG, "Closing camera device");
                        cameraDevice.close();
                    } catch (Exception e) {
                        Log.e(TAG, "Error closing camera device", e);
                    } finally {
                        cameraDevice = null;
                        isCameraOpen = false;
                    }
                }
                
                // Final cleanup on the main thread
                mainHandler.post(() -> {
                    // Release wake lock if held
                    if (recordingWakeLock != null && recordingWakeLock.isHeld()) {
                        try {
                            recordingWakeLock.release();
                            Log.d(TAG, "Recording wake lock released");
                        } catch (Exception e) {
                            Log.e(TAG, "Error releasing wake lock", e);
                        }
                    }
                    
                    // Check if service can stop
                    checkIfServiceCanStop();
                    
                    // Reset stopping flag
                    isStopping = false;
                    
                    // Clear any pending recording start flag
                    pendingStartRecording = false;
                    
                    Log.d(TAG, "stopRecording sequence completed successfully");
                });
            } catch (Exception e) {
                Log.e(TAG, "Error in stopRecording cleanup thread", e);
                mainHandler.post(() -> {
                    isStopping = false;
                    pendingStartRecording = false;
                });
            }
        }, "RecordingStopThread").start();
    }

    private void pauseRecording() {
        if (recordingState != RecordingState.IN_PROGRESS) return;
        if (glRecordingPipeline != null) glRecordingPipeline.pauseRecording(); // if supported
        recordingState = RecordingState.PAUSED;
        sharedPreferencesManager.setRecordingInProgress(false);
        setupRecordingResumeNotification();
        showRecordingInPausedToast();
        broadcastOnRecordingPaused();
    }

    private void resumeRecording() {
        if (recordingState != RecordingState.PAUSED) return;
        if (glRecordingPipeline != null) glRecordingPipeline.resumeRecording(); // if supported
        recordingState = RecordingState.IN_PROGRESS;
        sharedPreferencesManager.setRecordingInProgress(true);
        setupRecordingInProgressNotification();
        showRecordingResumedToast();
        broadcastOnRecordingResumed();
    }

    private void releaseRecordingResources() {
        if (isStopping) return;
        
        // Release dummy background surface first
        releaseDummyBackgroundSurface();
        
        isStopping = true;
        try { if (captureSession != null) { captureSession.close(); } } catch (Exception e) { } finally { captureSession = null; }
        try { if (cameraDevice != null) { cameraDevice.close(); } } catch (Exception e) { } finally { cameraDevice = null; }
        if (glRecordingPipeline != null) { glRecordingPipeline.stopRecording(); glRecordingPipeline = null; }
        recordingState = RecordingState.NONE;
        sharedPreferencesManager.setRecordingInProgress(false);
    }

    /**
     * Checks if the service has any active work (recording, pausing, or processing)
     * and calls stopSelf() if it is completely idle.
     * This should be called whenever a task completes (recording stops, processing finishes).
     */
    private void checkIfServiceCanStop() {
        // Read volatile flag and check state atomically as best as possible

        Log.d(TAG, "checkIfServiceCanStop: RecordingState=" + recordingState + ", FfmpegTasks=" + ffmpegProcessingTaskCount.get());

        // Use the new shouldServiceStayAlive method to determine if service should continue running
        if (!shouldServiceStayAlive()) {
            Log.i(TAG, "No active recording or background processing detected. Stopping service.");
            // Remove from foreground first to avoid ANR if stopSelf takes time
            stopForeground(true);
            stopSelf(); // Stop the service as its work is done.
        } else {
            Log.d(TAG, "Service continues running (Tasks active).");
        }

    }


    private void openCamera() {
        Log.d(TAG, "openCamera: Opening camera");
        if (cameraManager == null) {
            Log.e(TAG,"openCamera: CameraManager (class field) is null.");
            if (recordingState == RecordingState.STARTING) recordingState = RecordingState.NONE;
            stopSelf();
            return;
        }
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG,"openCamera: Camera permission denied.");
            if (recordingState == RecordingState.STARTING) recordingState = RecordingState.NONE;
            Toast.makeText(this, "Camera permission denied for service", Toast.LENGTH_LONG).show();
            stopSelf();
            return;
        }

        CameraType selectedType = sharedPreferencesManager.getCameraSelection();
        String cameraToOpenId = null;

        try {
            String[] availableCameraIds = cameraManager.getCameraIdList();
            Log.d(TAG, "Available Camera IDs: " + Arrays.toString(availableCameraIds));

            if (selectedType == CameraType.FRONT) {
                for (String id : availableCameraIds) {
                    CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(id);
                    Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
                    if (facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT) {
                        cameraToOpenId = id;
                        Log.d(TAG, "Found FRONT camera: ID=" + cameraToOpenId);
                        break;
                    }
                }
            } else { // CameraType.BACK
                String preferredBackId = sharedPreferencesManager.getSelectedBackCameraId();
                Log.d(TAG,"Preferred BACK camera ID from prefs: " + preferredBackId);
                boolean isValidAndAvailable = false;
                for(String id : availableCameraIds){
                    if(id.equals(preferredBackId)){
                        CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(id);
                        Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
                        if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) {
                            isValidAndAvailable = true;
                            break;
            } else {
                            Log.w(TAG,"Preferred back ID "+preferredBackId+" exists but is not LENS_FACING_BACK!");
                        }
                    }
                }
                if (isValidAndAvailable) {
                    cameraToOpenId = preferredBackId;
                    Log.d(TAG, "Using preferred BACK camera ID: " + cameraToOpenId);
                } else {
                    Log.w(TAG,"Preferred back camera ID '"+preferredBackId+"' is invalid or unavailable. Falling back to default ID '"+Constants.DEFAULT_BACK_CAMERA_ID+"'.");
                    cameraToOpenId = Constants.DEFAULT_BACK_CAMERA_ID;
                    boolean defaultExistsAndIsBack = false;
                    for(String id : availableCameraIds){
                        if(id.equals(cameraToOpenId)) {
                            CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(id);
                            Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
                            if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) {
                                defaultExistsAndIsBack = true;
                            }
                            break;
                        }
                    }
                    if(!defaultExistsAndIsBack){
                        Log.e(TAG,"Critical: Default back camera ID '"+cameraToOpenId+"' not found or not back-facing! Cannot select default back camera.");
                        String fallbackBackId = null;
                        for(String id: availableCameraIds){
                            CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(id);
                            Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
                            if(facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) {
                                Log.w(TAG,"Using first available back camera as final fallback: "+id);
                                fallbackBackId = id;
                                break;
                            }
                        }
                        if (fallbackBackId != null){
                            cameraToOpenId = fallbackBackId;
                        } else {
                            cameraToOpenId = null;
                            Log.e(TAG, "Could not find any back-facing camera.");
                        }
                }
                }
            }

            if (cameraToOpenId == null) {
                Log.e(TAG, "Could not determine a valid camera ID to open for selected type: " + selectedType);
                Toast.makeText(this, "Failed to find selected camera", Toast.LENGTH_LONG).show();
                if (recordingState == RecordingState.STARTING) recordingState = RecordingState.NONE;
                stopSelf();
                return;
            }
            Log.i(TAG,"Attempting to open final Camera ID: "+ cameraToOpenId);

            if(cameraDevice != null) {
                Log.w(TAG,"openCamera: Closing existing cameraDevice instance first.");
                try {
                cameraDevice.close();
                } catch (Exception e) {
                    Log.e(TAG, "Error explicitly closing existing cameraDevice prior to opening new one", e);
                }
                cameraDevice = null;
            }

            final int MAX_RETRIES = 3;
            final long RETRY_DELAY_MS = 2000;
            int attempt = 0;
            String finalCameraToOpenId = cameraToOpenId;

            while (attempt < MAX_RETRIES) {
                try {
                    Log.i(TAG, "Opening camera " + finalCameraToOpenId + ", attempt " + (attempt + 1));
                    cameraManager.openCamera(finalCameraToOpenId, cameraStateCallback, backgroundHandler);
                    return;
        } catch (CameraAccessException e) {
                    Log.e(TAG, "openCamera: Camera Access Exception on attempt " + (attempt + 1) + " for ID " + finalCameraToOpenId, e);
                    // Use direct integer values for error codes for broader compatibility
                    int reason = e.getReason();
                    if (reason == 1 /* ERROR_CAMERA_DISABLED */ || 
                        reason == 4 /* ERROR_CAMERA_IN_USE */) { 
                        attempt++;
                        if (attempt < MAX_RETRIES) {
                            Log.w(TAG, "Camera disabled (1) or in use (4), reason: " + reason + ". Retrying in " + RETRY_DELAY_MS + "ms... (" + attempt + "/" + MAX_RETRIES + ")");
                            try {
                                Thread.sleep(RETRY_DELAY_MS);
                            } catch (InterruptedException ie) {
                                Log.w(TAG, "Camera open retry delay interrupted", ie);
                                Thread.currentThread().interrupt();
                                if (recordingState == RecordingState.STARTING) recordingState = RecordingState.NONE;
                                stopSelf();
                                return;
                            }
                        } else {
                            Log.e(TAG, "Max retries reached for camera " + finalCameraToOpenId + ". Giving up. Reason: " + reason);
                            if (recordingState == RecordingState.STARTING) recordingState = RecordingState.NONE;
                            Toast.makeText(this, "Camera repeatedly unavailable (Reason: " + reason + "). Stopping.", Toast.LENGTH_LONG).show();
                            stopSelf();
                            return;
                        }
                    } else {
                        Log.e(TAG, "Unrecoverable CameraAccessException (Reason: " + reason + "). Not retrying.", e);
                        if (recordingState == RecordingState.STARTING) recordingState = RecordingState.NONE;
                        Toast.makeText(this, "Camera access error: " + reason, Toast.LENGTH_LONG).show();
                        stopSelf();
                        return;
                    }
                } catch (IllegalArgumentException e) {
                    Log.e(TAG, "openCamera: Illegal Argument Exception (likely invalid camera ID '"+finalCameraToOpenId+"'). Attempt: " + (attempt+1), e);
                    if (recordingState == RecordingState.STARTING) recordingState = RecordingState.NONE;
                    Toast.makeText(this, "Invalid camera configuration.", Toast.LENGTH_LONG).show();
                    stopSelf();
                    return;
                } catch (Exception e) {
                    Log.e(TAG, "openCamera: Unexpected error on attempt " + (attempt + 1) + " for ID " + finalCameraToOpenId, e);
                    if (recordingState == RecordingState.STARTING) recordingState = RecordingState.NONE;
                    Toast.makeText(this, "Unexpected camera error.", Toast.LENGTH_LONG).show();
                    stopSelf();
                    return;
                }
            }
        } catch (CameraAccessException e) {
            Log.e(TAG, "openCamera: Initial Camera Access Exception (listing/characteristics)", e);
            if (recordingState == RecordingState.STARTING) recordingState = RecordingState.NONE;
            Toast.makeText(this, "Failed to access camera details.", Toast.LENGTH_LONG).show();
            stopSelf();
        } catch (IllegalArgumentException e){
            Log.e(TAG, "openCamera: Outer Illegal Argument Exception (likely invalid camera ID '"+cameraToOpenId+"' for characteristics)", e);
            if (recordingState == RecordingState.STARTING) recordingState = RecordingState.NONE;
            Toast.makeText(this, "Invalid camera setup.", Toast.LENGTH_LONG).show();
            stopSelf();
        } catch (Exception e) {
            Log.e(TAG, "openCamera: Unexpected outer error", e);
            if (recordingState == RecordingState.STARTING) recordingState = RecordingState.NONE;
            Toast.makeText(this, "Critical camera system error.", Toast.LENGTH_LONG).show();
            stopSelf();
        }
    }

    private final CameraDevice.StateCallback cameraStateCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(@NonNull CameraDevice camera) {
            Log.d(TAG, "Camera device opened successfully");
            cameraDevice = camera;
            isCameraOpen = true;
            
            // Check if we have a pending recording start request
            if (pendingStartRecording) {
                Log.d(TAG, "Found pendingStartRecording=true, starting recording now");
                pendingStartRecording = false; // Reset the flag
                
                // Start recording on main thread to avoid threading issues
                mainHandler.post(() -> {
                    try {
                        if (recordingState == RecordingState.STARTING) {
                            Log.d(TAG, "Starting recording from camera onOpened callback");
                            startRecording();
                        } else {
                            Log.w(TAG, "Camera opened but recording state changed to " + recordingState);
                            // If state changed while camera was opening, handle accordingly
                            if (recordingState == RecordingState.NONE) {
                                Log.d(TAG, "Recording state is NONE, releasing camera");
                                if (cameraDevice != null) {
                                    cameraDevice.close();
                                    cameraDevice = null;
                                }
                            }
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Error starting recording from camera callback", e);
                        stopRecording();
                    }
                });
            } else {
                Log.d(TAG, "Camera opened but no pending recording start");
                // If we're not starting recording, create a preview session
                if (previewSurface != null) {
                    try {
                        createCameraPreviewSession();
                    } catch (Exception e) {
                        Log.e(TAG, "Error creating preview session", e);
                    }
                }
            }
        }

        @Override
        public void onDisconnected(@NonNull CameraDevice camera) {
            Log.d(TAG, "Camera device disconnected");
            if (cameraDevice != null) {
                cameraDevice.close();
                cameraDevice = null;
            }
            isCameraOpen = false;
            
            // Reset the pending flag if camera disconnected
            if (pendingStartRecording) {
                Log.w(TAG, "Camera disconnected while pendingStartRecording=true, resetting flag");
                pendingStartRecording = false;
            }
            
            // If we were recording, stop it
            if (recordingState != RecordingState.NONE) {
                Log.w(TAG, "Camera disconnected during recording, stopping recording");
                stopRecording();
            }
        }

        @Override
        public void onError(@NonNull CameraDevice camera, int error) {
            Log.e(TAG, "Camera device error: " + error);
            if (cameraDevice != null) {
                cameraDevice.close();
                cameraDevice = null;
            }
            isCameraOpen = false;
            
            // Reset the pending flag if camera error
            if (pendingStartRecording) {
                Log.w(TAG, "Camera error while pendingStartRecording=true, resetting flag");
                pendingStartRecording = false;
            }
            
            // If we were recording, stop it
            if (recordingState != RecordingState.NONE) {
                Log.w(TAG, "Camera error during recording, stopping recording");
                stopRecording();
            }
            
            // Show error to user
            String errorMsg;
            switch (error) {
                case CameraDevice.StateCallback.ERROR_CAMERA_IN_USE:
                    errorMsg = getString(R.string.camera_error_in_use);
                    break;
                case CameraDevice.StateCallback.ERROR_MAX_CAMERAS_IN_USE:
                    errorMsg = getString(R.string.camera_error_max_cameras);
                    break;
                case CameraDevice.StateCallback.ERROR_CAMERA_DISABLED:
                    errorMsg = getString(R.string.camera_error_disabled);
                    break;
                case CameraDevice.StateCallback.ERROR_CAMERA_DEVICE:
                    errorMsg = getString(R.string.camera_error_device);
                    break;
                case CameraDevice.StateCallback.ERROR_CAMERA_SERVICE:
                    errorMsg = getString(R.string.camera_error_service);
                    break;
                default:
                    errorMsg = getString(R.string.camera_error_unknown) + " (" + error + ")";
            }
            
            final String finalErrorMsg = errorMsg;
            mainHandler.post(() -> Toast.makeText(getApplicationContext(), finalErrorMsg, Toast.LENGTH_LONG).show());
        }

        @Override
        public void onClosed(@NonNull CameraDevice camera) {
            Log.d(TAG, "Camera device closed");
            isCameraOpen = false;
            
            // If we were in segment rollover, continue with the rollover process
            if (isRolloverClosingOldSession) {
                Log.d(TAG, "Camera closed during segment rollover, proceeding with rollover");
                isRolloverClosingOldSession = false;
                proceedWithRolloverAfterOldSessionClosed();
            }
            
            // Reset the pending flag if camera closed
            if (pendingStartRecording) {
                Log.w(TAG, "Camera closed while pendingStartRecording=true, resetting flag");
                pendingStartRecording = false;
            }
        }
    };

    private void createCameraPreviewSession() {
        if (cameraDevice == null) {
            Log.e(TAG, "createCameraPreviewSession: cameraDevice is null!");
            stopRecording(); // Cannot create session without camera
            return;
        }
        // If using OpenGL pipeline for watermarking
        if (glRecordingPipeline != null) {
            Log.d(TAG, "createCameraPreviewSession: Using GL pipeline for watermarking");
            Surface glSurface = glRecordingPipeline.getCameraInputSurface();
            List<Surface> surfaces = new ArrayList<>();
            if (glSurface != null) {
                surfaces.add(glSurface);
            } else {
                Log.e(TAG, "GL pipeline camera input surface is null!");
            }
            // Do NOT add previewSurface to Camera2 session outputs
            // if (previewSurface != null) {
            //     surfaces.add(previewSurface);
            // } else {
            //     Log.w(TAG, "Preview surface is null; preview will be disabled.");
            // }
            if (surfaces.isEmpty()) {
                Log.e(TAG, "No valid surfaces for camera session!");
                stopRecording();
                return;
            }
            // Get camera characteristics for frame rate handling
            CameraManager cameraManager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
            CameraType cameraType = sharedPreferencesManager.getCameraSelection();
            String cameraId = getCameraId(cameraManager, cameraType);
            CameraCharacteristics characteristics = null;
            if (cameraId != null) {
                try {
                    characteristics = cameraManager.getCameraCharacteristics(cameraId);
                } catch (Exception e) {
                    Log.e(TAG, "Error getting camera characteristics", e);
                }
            }
            int targetFrameRate = sharedPreferencesManager.getVideoFrameRate();
            boolean isHighFrameRate = targetFrameRate >= 60;
            boolean useHighSpeedSession = false;
            if (isHighFrameRate && characteristics != null) {
                // ... (same Samsung/Huawei/high-speed logic as before)
            }
            if (useHighSpeedSession) {
                createHighSpeedSession(surfaces, characteristics, targetFrameRate);
            } else {
                createStandardSession(surfaces, targetFrameRate, characteristics);
            }
            return;
        }

        Log.d(TAG,"createCameraPreviewSession: Creating session...");
        try {
            List<Surface> surfaces = new ArrayList<>();
            if (glRecordingPipeline != null) {
                surfaces.add(glRecordingPipeline.getCameraInputSurface());
            }
            if (previewSurface != null) {
                surfaces.add(previewSurface);
                previewSurfaceAdded = true;
                Log.d(TAG, "Using valid preview surface from UI");
            }             else if (dummyBackgroundSurface != null && dummyBackgroundSurface.isValid()) {
                // Use dummy surface when UI surface is gone (app backgrounded)
                // to prevent recording issues like green frames
                surfaces.add(dummyBackgroundSurface);
                Log.d(TAG, "Using dummy surface (app backgrounded) to maintain stable recording");
                previewSurfaceAdded = true;
            } else {
                Log.d(TAG, "No valid preview or dummy surface available");
            }
            
            // Get camera characteristics for frame rate handling
            CameraManager cameraManager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
            CameraType cameraType = sharedPreferencesManager.getCameraSelection();
            String cameraId = getCameraId(cameraManager, cameraType);
            CameraCharacteristics characteristics = null;
            
            if (cameraId != null) {
                characteristics = cameraManager.getCameraCharacteristics(cameraId);
            }
            
            // Get target frame rate from settings
            int targetFrameRate = sharedPreferencesManager.getVideoFrameRate();
            
            // Log device info once for debugging
            DeviceHelper.logDeviceInfo();
            
            // Check if we need to use high-speed session for 60fps+ recording
            boolean isHighFrameRate = targetFrameRate >= 60;
            boolean useHighSpeedSession = false;
            
            // Continue with existing code...
            // Rest of the method stays the same

            // For high frame rates, evaluate if we should use high-speed session
            if (isHighFrameRate && characteristics != null) {
                // For Samsung devices, we ALWAYS use vendor keys over high-speed sessions for 60fps
                if (DeviceHelper.isSamsung()) {
                    Log.d(TAG, "Samsung device detected. Handling " + targetFrameRate + "fps for this device.");
                    showFrameRateToast(targetFrameRate);
                    
                    // Determine Samsung FPS compatibility status
                    SamsungFrameRateHelper.SamsungFpsStatus fpsStatus = SamsungFrameRateHelper.getDeviceFpsStatus();

                    if (fpsStatus == SamsungFrameRateHelper.SamsungFpsStatus.HIGH_SPEED_COMPATIBLE) {
                        // For SM-G990E (S21 FE Exynos) specifically, or other devices known to work with high-speed sessions
                        Log.d(TAG, "Device status is HIGH_SPEED_COMPATIBLE. Attempting constrained high-speed session for " + targetFrameRate + "fps.");
                        useHighSpeedSession = true;
                        createHighSpeedSession(surfaces, characteristics, targetFrameRate);
                    } else if (fpsStatus == SamsungFrameRateHelper.SamsungFpsStatus.REQUIRES_VENDOR_KEYS || fpsStatus == SamsungFrameRateHelper.SamsungFpsStatus.FULLY_COMPATIBLE || fpsStatus == SamsungFrameRateHelper.SamsungFpsStatus.UNKNOWN) {
                        // For other Samsung devices that use vendor keys in standard session, or unknown devices
                        Log.d(TAG, "Device status is REQUIRES_VENDOR_KEYS or FULLY_COMPATIBLE or UNKNOWN. Attempting standard session with Samsung vendor keys for " + targetFrameRate + "fps.");
                        useHighSpeedSession = false; // Ensure it's a standard session
                    createStandardSession(surfaces, targetFrameRate, characteristics);
                    } else if (fpsStatus == SamsungFrameRateHelper.SamsungFpsStatus.KNOWN_INCOMPATIBLE) {
                        // For known incompatible Samsung devices, do not attempt 60fps+
                        Log.e(TAG, "Device is KNOWN_INCOMPATIBLE with 60fps+. Blocking request.");
                        Toast.makeText(this, "60fps not supported on this device model.", Toast.LENGTH_LONG).show();
                        RecordingService.this.recordingState = RecordingState.NONE; // Reset state to NONE by direct assignment from outer class
                        return; // Do not proceed with recording
                    }
                    return; // Return after handling Samsung-specific logic
                } 
                // For Huawei devices, also prefer vendor keys
                else if (DeviceHelper.isHuawei()) {
                    Log.d(TAG, "Using Huawei-specific approach for high frame rates");
                    
                    // Show toast informing the user about experimental 60fps
                    showFrameRateToast(targetFrameRate);
                    
                    // Use standard session with Huawei vendor keys
                    useHighSpeedSession = false;
                }
                // For other devices, check if high-speed is supported
                else if (HighSpeedCaptureHelper.isHighSpeedSupported(characteristics, targetFrameRate)) {
                    Log.d(TAG, "High-speed session is supported for " + targetFrameRate + "fps");
                    useHighSpeedSession = true;
                    
                    // Show toast informing the user about experimental 60fps
                    showFrameRateToast(targetFrameRate);
                } else {
                    Log.d(TAG, "High-speed not supported for " + targetFrameRate + 
                           "fps, using standard session");
                           
                    // Show toast informing the user that high frame rate may not be fully supported
                    showFrameRateToast(targetFrameRate);
                }
            }
            
            // Create the appropriate type of session
            if (useHighSpeedSession) {
                // For high-speed sessions
                createHighSpeedSession(surfaces, characteristics, targetFrameRate);
            } else {
                // Create a standard session with appropriate frame rate settings
                createStandardSession(surfaces, targetFrameRate, characteristics);
            }
        } catch (CameraAccessException e) {
            Log.e(TAG, "createCameraPreviewSession: Camera Access Exception", e);
            stopRecording();
        } catch (IllegalStateException e) {
            Log.e(TAG, "createCameraPreviewSession: IllegalStateException", e);
            stopRecording();
        } catch (Exception e) {
            Log.e(TAG, "createCameraPreviewSession: Exception", e);
            stopRecording();
        }
    }


    private final CameraCaptureSession.StateCallback captureSessionCallback = new CameraCaptureSession.StateCallback() {
        @Override
        public void onConfigured(@NonNull CameraCaptureSession session) {
            if (isStopping || cameraDevice == null || recordingState == RecordingState.NONE) {
                Log.w(TAG, "onConfigured: Service is stopping, camera device is null, or recording state is NONE. Aborting.");
                return;
            }
            Log.d(TAG, "Standard capture session configured");
            captureSession = session;
            try {
                // Set auto control mode
                captureRequestBuilder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);
                // Set torch/flash mode
                captureRequestBuilder.set(CaptureRequest.FLASH_MODE, 
                    isRecordingTorchEnabled ? 
                        CaptureRequest.FLASH_MODE_TORCH : 
                        CaptureRequest.FLASH_MODE_OFF);
                // Defensive: check session state before using
                if (captureSession == null || cameraDevice == null || recordingState == RecordingState.NONE) {
                    Log.w(TAG, "onConfigured: Session or cameraDevice became null before setRepeatingRequest. Aborting.");
                    return;
                }
                // Start repeating request
                try {
                    session.setRepeatingRequest(captureRequestBuilder.build(), null, backgroundHandler);
                    Log.d(TAG, "Started repeating request for standard session");
                } catch (IllegalStateException ise) {
                    Log.e(TAG, "setRepeatingRequest failed: session likely closed", ise);
                    stopRecording();
                    return;
                }
                // Start the GL pipeline only after session is configured and repeating request is set
                if (glRecordingPipeline != null) {
                    glRecordingPipeline.startRecording();
                }
                // Handle recording state
                handleSessionConfigured();
            } catch (CameraAccessException e) {
                Log.e(TAG, "Error starting repeating request", e);
                stopRecording();
            }
        }
        
        @Override
        public void onConfigureFailed(@NonNull CameraCaptureSession session) {
            if (isStopping) return;
            Log.e(TAG, "Standard capture session configuration failed");
            stopRecording();
        }
        
        @Override
        public void onClosed(@NonNull CameraCaptureSession session) {
            if (isStopping) return;
            Log.d(TAG, "Capture session closed");
            
            if (session == captureSession) {
                captureSession = null;
            }
            
            if (isRolloverClosingOldSession) {
                Log.d(TAG, "Capture session closed as part of rollover");
                isRolloverClosingOldSession = false;
                proceedWithRolloverAfterOldSessionClosed();
            }
        }
    };

    /**
     * Callback for high-speed session state changes
     */
    private final CameraCaptureSession.StateCallback highSpeedSessionCallback = 
            new CameraCaptureSession.StateCallback() {
        
        @Override
        public void onConfigured(@NonNull CameraCaptureSession session) {
            if (isStopping || cameraDevice == null) {
                Log.e(TAG, "onConfigured: Service is stopping or camera closed before high-speed session configured");
                return;
            }
            
            try {
                Log.d(TAG, "High-speed session configured successfully");
                captureSession = session;
                
                // For high-speed sessions, we need to create a list of requests for burst
                CameraConstrainedHighSpeedCaptureSession highSpeedSession = 
                        (CameraConstrainedHighSpeedCaptureSession) session;
                        
                List<CaptureRequest> highSpeedRequests = 
                        highSpeedSession.createHighSpeedRequestList(captureRequestBuilder.build());
                        
                // Start repeating burst for high-speed recording
                highSpeedSession.setRepeatingBurst(highSpeedRequests, null, backgroundHandler);
                
                // Handle recording state
                handleSessionConfigured();
            } catch (CameraAccessException e) {
                Log.e(TAG, "Error setting up high-speed repeating burst", e);
                stopRecording();
            } catch (IllegalArgumentException | IllegalStateException e) {
                Log.e(TAG, "Error in high-speed session configuration", e);
                stopRecording();
            }
        }
        
        @Override
        public void onConfigureFailed(@NonNull CameraCaptureSession session) {
            if (isStopping) return;
            Log.e(TAG, "High-speed session configuration failed");
            
            try {
                // Can't get surfaces from the failed session, need to recreate them
                CameraManager cameraManager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
                CameraType cameraType = sharedPreferencesManager.getCameraSelection();
                String cameraId = getCameraId(cameraManager, cameraType);
                CameraCharacteristics characteristics = null;
                
                if (cameraId != null) {
                    characteristics = cameraManager.getCameraCharacteristics(cameraId);
                }
                
                int targetFrameRate = sharedPreferencesManager.getVideoFrameRate();
                
                // Recreate surfaces for standard session
                List<Surface> surfaces = new ArrayList<>();
                

                
                // Add preview surface if available
                if (previewSurface != null) {
                    surfaces.add(previewSurface);
                }
                
                // Create standard session as fallback
                if (!surfaces.isEmpty()) {
                    createStandardSession(surfaces, targetFrameRate, characteristics);
                } else {
                    Log.e(TAG, "Failed to create surfaces for fallback session");
                    stopRecording();
                }
            } catch (Exception e) {
                Log.e(TAG, "Failed to create fallback session after high-speed failure", e);
                stopRecording();
            }
        }
        
        @Override
        public void onClosed(@NonNull CameraCaptureSession session) {
            if (isStopping) return;
            // Handle session closure same as standard session
            if (captureSessionCallback != null) {
                captureSessionCallback.onClosed(session);
            }
        }
    };



    /**
     * Handles errors that occur during the preparation or execution of video processing.
     * Logs the error, shows a Toast, resets the processing flag, and attempts to clean up
     * the temporary input file associated with the failed process.
     *
     * @param errorMessage A description of the error that occurred.
     * @param internalTempInputPath The URI (as String) or absolute path of the temporary input file
     *                               that should be cleaned up because its processing failed. Can be null.
     */
    // Helper to handle storage setup errors consistently
    private void handleProcessingError(String errorMessage, @Nullable String internalTempInputPath) {
        Log.e(TAG, "Processing Error: " + errorMessage);
        Toast.makeText(this, "Error processing video recording", Toast.LENGTH_LONG).show();



        // If an input path/URI was provided, log attempt to clean it
        if (internalTempInputPath != null) {
            Log.d(TAG, "handleProcessingError: Original temp file to keep (maybe): " + internalTempInputPath);
            // We generally DO NOT delete the input temp file on processing errors,
            // as it might be the only copy left. We just log it.
            // CleanupTemporaryFile() call will be skipped if processing failed.
        } else {
            Log.w(TAG, "handleProcessingError called without specific input path reference.");
        }



        // Check if the service should stop now that processing has failed/stopped
        if (!isWorkingInProgress()) {
            Log.d(TAG,"handleProcessingError: No other work pending, stopping service.");
            stopSelf();
        }
    }

    // Use this primarily for file paths, maybe less critical for content:// URIs
    private String escapeFFmpegPath(String path) {
        if (path == null) return "";
        // For content URIs, usually best not to quote/escape unless specifically needed
        if (path.startsWith("content://")) {
            return path;
        } else {
            // Escape single quotes for shell safety when wrapping path in single quotes
            return "'" + path.replace("'", "'\\''") + "'";
        }
    }





    // Helper to handle storage setup errors consistently
    private void handleStorageError(String message) {
        Log.e(TAG, message);
        Toast.makeText(this, message + ", Using Internal.", Toast.LENGTH_LONG).show();
        sharedPreferencesManager.setStorageMode(SharedPreferencesManager.STORAGE_MODE_INTERNAL);
        sharedPreferencesManager.setCustomStorageUri(null);
        // Note: Do not call stopSelf here, let setupMediaRecorder fail naturally
    }
    // --- End Watermarking & Processing ---


    // --- Helper Methods ---
    private void setupSurfaceTexture(Intent intent) {
        Surface oldPreviewSurface = previewSurface; // Store old surface to check for changes
        
        if(intent != null) {
            previewSurface = intent.getParcelableExtra("SURFACE");
            
            // Check if we've lost a valid preview surface (app going to background)
            boolean validOldSurface = oldPreviewSurface != null && oldPreviewSurface.isValid();
            boolean validNewSurface = previewSurface != null && previewSurface.isValid();
            
            if (validOldSurface && !validNewSurface && isRecordingOrPaused()) {
                // We had a valid surface but now we don't
                // This is likely the app being backgrounded - create dummy surface to prevent green screen
                Log.d(TAG, "Surface lost while recording - creating dummy surface to prevent recording issues");
                createDummyBackgroundSurface();
            }
            
            Log.d(TAG, "Preview surface updated: " + validNewSurface);
            
            // Check if we have surface dimensions
            int width = intent.getIntExtra("SURFACE_WIDTH", -1);
            int height = intent.getIntExtra("SURFACE_HEIGHT", -1);
            
            // Update the GL pipeline with the new dimensions if available
            if (width > 0 && height > 0 && glRecordingPipeline != null) {
                Log.d(TAG, "Updating GL pipeline with surface dimensions: " + width + "x" + height);
                glRecordingPipeline.updateSurfaceDimensions(width, height);
            }
        }
    }






    private int getVideoBitrate() {
        int videoBitrate;
        if (sharedPreferencesManager.sharedPreferences.getBoolean("bitrate_mode_custom", false)) {
            videoBitrate = sharedPreferencesManager.sharedPreferences.getInt("bitrate_custom_value", 16000) * 1000; // stored as kbps, use bps
            Log.d(TAG, "[DEBUG] Using custom video bitrate: " + videoBitrate + " bps");
        } else {
            videoBitrate = Utils.estimateBitrate(sharedPreferencesManager.getCameraResolution(), sharedPreferencesManager.getVideoFrameRate());
            Log.d(TAG, "[DEBUG] Using default video bitrate: " + videoBitrate + " bps");
        }
        return videoBitrate;
    }


    private int getFontSizeBasedOnBitrate() {
        int fontSize;
        long videoBitrate = getVideoBitrate();
        if (videoBitrate <= 1_000_000) fontSize = 12; // <= 1 Mbps (Approx SD)
        else if (videoBitrate <= 5_000_000) fontSize = 16; // <= 5 Mbps (Approx HD)
        else if (videoBitrate <= 15_000_000) fontSize = 24; // <= 15 Mbps (Approx FHD)
        else fontSize = 30; // Higher bitrates (e.g., 4K)
        Log.d(TAG, "Determined Font Size: " + fontSize + " for bitrate " + videoBitrate);
        return fontSize;
    }


    private String getCurrentTimestamp() {
        SimpleDateFormat sdf = new SimpleDateFormat("dd/MMM/yyyy hh:mm:ss a", Locale.ENGLISH); // 12-hour format with AM/PM
        return convertArabicNumeralsToEnglish(sdf.format(new Date()));
    }


    private String convertArabicNumeralsToEnglish(String text) {
        if (text == null) return null;
        return text.replaceAll("٠", "0")
                .replaceAll("١", "1")
                .replaceAll("٢", "2")
                .replaceAll("٣", "3")
                .replaceAll("٤", "4")
                .replaceAll("٥", "5")
                .replaceAll("٦", "6")
                .replaceAll("٧", "7")
                .replaceAll("٨", "8")
                .replaceAll("٩", "9");
    }

    private String getLocationData() {
        if(locationHelper == null) {
            Log.w(TAG, "LocationHelper not initialized, cannot get location data.");
            return ""; // Return empty, not "Not available" to avoid user confusion
        }
        String locData = locationHelper.getLocationData();
        // Avoid adding "Location not available" to watermark, just add lat/lon if present
        return (locData != null && locData.contains("Lat=")) ? locData : "";
    }


    private String escapeFFmpegString(String text) {
        if (text == null) return "";
        return text
                .replace("\\", "\\\\") // Escape backslashes
                .replace(":", "\\:")  // Escape colons
                .replace("'", "")      // Remove single quotes entirely (safer than escaping)
                .replace("\"", "")     // Remove double quotes
                .replace("%", "%%"); // Escape percent signs
    }


    // --- End Helper Methods ---


    // --- Broadcasts ---
    private void broadcastOnRecordingStarted() {
        Intent broadcastIntent = new Intent(Constants.BROADCAST_ON_RECORDING_STARTED);
        broadcastIntent.putExtra(Constants.INTENT_EXTRA_RECORDING_START_TIME, recordingStartTime);
        broadcastIntent.putExtra(Constants.INTENT_EXTRA_RECORDING_STATE, recordingState);
        sendBroadcast(broadcastIntent);
        Log.d(TAG,"Broadcasted: BROADCAST_ON_RECORDING_STARTED");
    }
    private void broadcastOnRecordingResumed() {
        Intent broadcastIntent = new Intent(Constants.BROADCAST_ON_RECORDING_RESUMED);
        sendBroadcast(broadcastIntent);
        Log.d(TAG,"Broadcasted: BROADCAST_ON_RECORDING_RESUMED");
    }
    private void broadcastOnRecordingPaused() {
        Intent broadcastIntent = new Intent(Constants.BROADCAST_ON_RECORDING_PAUSED);
        sendBroadcast(broadcastIntent);
        Log.d(TAG,"Broadcasted: BROADCAST_ON_RECORDING_PAUSED");
    }
    private void broadcastOnRecordingStopped() {
        Intent broadcastIntent = new Intent(Constants.BROADCAST_ON_RECORDING_STOPPED);
        sendBroadcast(broadcastIntent);
        Log.d(TAG,"Broadcasted: BROADCAST_ON_RECORDING_STOPPED");
    }
    private void broadcastOnRecordingStateCallback() {
        Intent broadcastIntent = new Intent(Constants.BROADCAST_ON_RECORDING_STATE_CALLBACK);
        broadcastIntent.putExtra(Constants.INTENT_EXTRA_RECORDING_STATE, recordingState);
        sendBroadcast(broadcastIntent);
        Log.d(TAG,"Broadcasted: BROADCAST_ON_RECORDING_STATE_CALLBACK with state: "+recordingState);
    }
    // --- End Broadcasts ---


    // --- Notifications ---
    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // ----- Fix Start for this method(createNotificationChannel) -----
            // Get the custom channel name or use a generic name
            String channelName = sharedPreferencesManager.getNotificationChannelName();
            CharSequence name = (channelName != null) ? 
                channelName : 
                getString(R.string.notification_channel_recording, getString(R.string.app_name));
            
            // Use a generic description that doesn't reveal the app's purpose
            String description = getString(R.string.notification_channel_description);
            int importance = NotificationManager.IMPORTANCE_LOW; // Low importance to be less intrusive
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, name, importance);
            channel.setDescription(description);
            channel.setSound(null, null); // No sound
            channel.enableVibration(false); // No vibration
            // ----- Fix Ended for this method(createNotificationChannel) -----

            NotificationManager notificationManager = getSystemService(NotificationManager.class);
            if (notificationManager != null) {
                notificationManager.createNotificationChannel(channel);
                Log.d(TAG, "Notification channel created or updated.");
            } else {
                Log.e(TAG, "NotificationManager service not found.");
            }
        }
    }

    private void setupRecordingInProgressNotification() {
        // ----- Fix Start for this method(setupRecordingInProgressNotification) -----
        if (ActivityCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG,"POST_NOTIFICATIONS permission not granted, skipping notification setup.");
            // If Android Tiramisu or higher, START_FOREGROUND without notification IS allowed if user denies permission
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                try {
                    startForeground(NOTIFICATION_ID, createBaseNotificationBuilder().build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA | ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
                    Log.d(TAG,"Started foreground without notification permission (Tiramisu+).");
                    return;
                } catch (Exception e){
                    Log.e(TAG,"Error starting foreground on Tiramisu+ without permission",e);
                    stopSelf(); // Critical error if foreground fails
                    return;
                }
            } else {
                // On older versions, foreground service needs a notification, permission IS required
                Toast.makeText(this,"Notification permission needed", Toast.LENGTH_LONG).show();
                stopSelf(); // Cannot run foreground service properly
                return;
            }
        }

        // Get custom notification text if set
        String notificationText = sharedPreferencesManager.getNotificationText(false);
        boolean hideStopButton = sharedPreferencesManager.isNotificationStopButtonHidden();
        
        NotificationCompat.Builder builder = createBaseNotificationBuilder()
                .setContentText(notificationText != null ? notificationText : getString(R.string.notification_video_recording_progress_description));
        
        // Add stop action only if not hidden
        if (!hideStopButton) {
            builder.clearActions() // Remove previous actions
                  .addAction(new NotificationCompat.Action(
                        R.drawable.ic_stop,
                        getString(R.string.button_stop),
                        createStopRecordingIntent()));
        }

        startForeground(NOTIFICATION_ID, builder.build());
        Log.d(TAG, "Foreground notification updated for IN_PROGRESS.");
        // ----- Fix Ended for this method(setupRecordingInProgressNotification) -----
    }


    private void setupRecordingResumeNotification() { // Notification shown when PAUSED
        // ----- Fix Start for this method(setupRecordingResumeNotification) -----
        if (ActivityCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG,"POST_NOTIFICATIONS permission not granted, skipping notification update.");
            return; // Don't crash if user denied permission after start
        }

        // Get custom notification text if set
        String notificationText = sharedPreferencesManager.getNotificationText(true);
        boolean hideStopButton = sharedPreferencesManager.isNotificationStopButtonHidden();
        
        NotificationCompat.Builder builder = createBaseNotificationBuilder()
                .setContentText(notificationText != null ? notificationText : getString(R.string.notification_video_recording_paused_description))
                .clearActions(); // Remove previous actions
        
        // Add resume action
        builder.addAction(new NotificationCompat.Action(
                R.drawable.ic_play, // Use Play icon for Resume action
                getString(R.string.button_resume),
                createResumeRecordingIntent()));
                
        // Add stop action only if not hidden
        if (!hideStopButton) {
            builder.addAction(new NotificationCompat.Action(
                    R.drawable.ic_stop,
                    getString(R.string.button_stop),
                    createStopRecordingIntent()));
        }

        NotificationManagerCompat notificationManager = NotificationManagerCompat.from(this);
        notificationManager.notify(NOTIFICATION_ID, builder.build()); // Just update existing notification
        Log.d(TAG, "Foreground notification updated for PAUSED.");
        // ----- Fix Ended for this method(setupRecordingResumeNotification) -----
    }


    private NotificationCompat.Builder createBaseNotificationBuilder() {
        // ----- Fix Start for this method(createBaseNotificationBuilder) -----
        // Get custom notification title if set
        String notificationTitle = sharedPreferencesManager.getNotificationTitle();
        String preset = sharedPreferencesManager.getNotificationPreset();
        
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(notificationTitle != null ? notificationTitle : getString(R.string.notification_video_recording))
                .setOngoing(true) // Makes it non-dismissible
                .setSilent(true) // Suppress sound/vibration defaults
                .setPriority(NotificationCompat.PRIORITY_LOW);
        
        // Choose appropriate icon based on notification preset
        int smallIconResId;
        switch (preset) {
            case SharedPreferencesManager.NOTIFICATION_PRESET_SYSTEM_UPDATE:
                smallIconResId = android.R.drawable.stat_sys_download;
                break;
            case SharedPreferencesManager.NOTIFICATION_PRESET_DOWNLOADING:
                smallIconResId = android.R.drawable.stat_sys_download;
                break;
            case SharedPreferencesManager.NOTIFICATION_PRESET_SYNCING:
                smallIconResId = android.R.drawable.stat_notify_sync;
                break;
            default:
                smallIconResId = R.drawable.ic_notification_icon;
                break;
        }
        
        builder.setSmallIcon(smallIconResId);
        
        // Set a generic content intent that doesn't reveal the app
        if (!SharedPreferencesManager.NOTIFICATION_PRESET_DEFAULT.equals(preset)) {
            // For non-default presets, use a blank PendingIntent that does nothing
            Intent emptyIntent = new Intent();
            PendingIntent emptyPendingIntent = PendingIntent.getActivity(this, 0, emptyIntent, 
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            builder.setContentIntent(emptyPendingIntent);
        } else {
            // For default preset, use normal app opening intent
            builder.setContentIntent(createOpenAppIntent());
        }
                
        return builder;
        // ----- Fix Ended for this method(createBaseNotificationBuilder) -----
    }

    private void cancelNotification() {
        NotificationManagerCompat notificationManager = NotificationManagerCompat.from(this);
        notificationManager.cancel(NOTIFICATION_ID);
        Log.d(TAG,"Cancelled notification.");
    }


    private PendingIntent createOpenAppIntent() {
        Intent openAppIntent = new Intent(this, MainActivity.class);
        // Standard flags to bring existing task to front or start new
        openAppIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        // Use FLAG_IMMUTABLE for security best practices on newer Androids
        int flags = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) ? PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE : PendingIntent.FLAG_UPDATE_CURRENT;
        return PendingIntent.getActivity(this, 0, openAppIntent, flags);
    }

    private PendingIntent createStopRecordingIntent() {
        Intent stopIntent = new Intent(this, RecordingService.class);
        stopIntent.setAction(Constants.INTENT_ACTION_STOP_RECORDING);
        int flags = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) ? PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE : PendingIntent.FLAG_UPDATE_CURRENT;
        return PendingIntent.getService(this, 1, stopIntent, flags); // Use unique request code (1)
    }

    private PendingIntent createResumeRecordingIntent() {
        Intent resumeIntent = new Intent(this, RecordingService.class);
        resumeIntent.setAction(Constants.INTENT_ACTION_RESUME_RECORDING);
        // Make sure surface data isn't needed here - pass null if required or handle differently
        int flags = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) ? PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE : PendingIntent.FLAG_UPDATE_CURRENT;
        return PendingIntent.getService(this, 2, resumeIntent, flags); // Use unique request code (2)
    }



    // --- Toast Helpers ---
    private void showRecordingResumedToast() {
        Utils.showQuickToast(this, R.string.video_recording_resumed);
    }
    private void showRecordingInPausedToast() {
        Utils.showQuickToast(this, R.string.video_recording_paused);
    }
    // --- End Toast Helpers ---


    // --- Torch Logic ---


    private void toggleRecordingTorch() {
        if (captureRequestBuilder != null && captureSession != null && cameraDevice != null) {
            if (recordingState == RecordingState.IN_PROGRESS || recordingState == RecordingState.PAUSED) {
                try {
                    isRecordingTorchEnabled = !isRecordingTorchEnabled; // Toggle the state for the session
                    Log.d(TAG,"Toggling recording torch via CaptureRequest. New state: "+ isRecordingTorchEnabled);

                    captureRequestBuilder.set(CaptureRequest.FLASH_MODE,
                            isRecordingTorchEnabled ? CaptureRequest.FLASH_MODE_TORCH : CaptureRequest.FLASH_MODE_OFF);

                    // Apply the change by updating the repeating request
                    captureSession.setRepeatingRequest(captureRequestBuilder.build(), null, backgroundHandler);
                    Log.d(TAG, "Recording torch repeating request updated.");

                    // Broadcast state change TO THE UI so it can update its torch button
                    Intent intent = new Intent(Constants.BROADCAST_ON_TORCH_STATE_CHANGED);
                    intent.putExtra(Constants.INTENT_EXTRA_TORCH_STATE, isRecordingTorchEnabled);
                    sendBroadcast(intent);

                } catch (CameraAccessException e) {
                    Log.e(TAG, "Could not toggle recording torch via CaptureRequest: " + e.getMessage());
                    isRecordingTorchEnabled = !isRecordingTorchEnabled; // Revert state on error
                } catch(IllegalStateException e) {
                    Log.e(TAG,"Could not toggle recording torch via CaptureRequest - session/camera closed?", e);
                    isRecordingTorchEnabled = !isRecordingTorchEnabled; // Revert state on error
                }
            } else {
                Log.w(TAG, "Cannot toggle recording torch via CaptureRequest - not IN_PROGRESS or PAUSED. State: " + recordingState);
                // If not recording, HomeFragment should handle torch directly via CameraManager.setTorchMode()
            }
        } else {
            Log.w(TAG, "Cannot toggle recording torch via CaptureRequest - session, request builder, or camera device is null.");
        }
    }


    // --- Status Check ---
    public boolean isRecording() {
        return recordingState == RecordingState.IN_PROGRESS;
    }

    public boolean isPaused() {
        return recordingState == RecordingState.PAUSED;
    }

    /**
     * Combined helper to check if recording is active or paused
     */
    public boolean isRecordingOrPaused() {
        return isRecording() || isPaused() || recordingState == RecordingState.WAITING_FOR_CAMERA;
    }

    // Combined status check
    public boolean isWorkingInProgress() {

        // Only consider recording states (not FFmpeg processing) for determining if camera is busy
        // This allows new recordings to start while FFmpeg is still processing in the background
        return recordingState == RecordingState.IN_PROGRESS || 
               recordingState == RecordingState.PAUSED || 
               recordingState == RecordingState.STARTING;

    }
    
    /**
     * Check if the service should stay alive (either recording is in progress or FFmpeg is processing)
     * This is different from isWorkingInProgress() which only checks if recording is active
     */
    private boolean shouldServiceStayAlive() {
        return ffmpegProcessingTaskCount.get() > 0 || 
               recordingState == RecordingState.IN_PROGRESS || 
               recordingState == RecordingState.PAUSED || 
               recordingState == RecordingState.STARTING ||
               recordingState == RecordingState.WAITING_FOR_CAMERA;
    }


    private void handleSegmentRolloverInternal() {
        Log.i(TAG, "handleSegmentRolloverInternal: NO-OP with setNextOutputFile. All rollover handled in OnInfoListener.");
    }

    private void proceedWithRolloverAfterOldSessionClosed() {
        Log.i(TAG, "proceedWithRolloverAfterOldSessionClosed: NO-OP with setNextOutputFile. All rollover handled in OnInfoListener.");
    }




    private File createNextSegmentOutputFile(int nextSegmentNumber) {
        // ----- Fix Start for this method(createNextSegmentOutputFile)-----
        String storageMode = sharedPreferencesManager.getStorageMode();
        if (SharedPreferencesManager.STORAGE_MODE_CUSTOM.equals(storageMode)) {
            // SAF/DocumentFile mode
            String customUriString = sharedPreferencesManager.getCustomStorageUri();
            if (customUriString == null) {
                Log.e(TAG, "createNextSegmentOutputFile: Custom storage selected but URI is null");
                return null;
            }
            Uri treeUri = Uri.parse(customUriString);
            DocumentFile pickedDir = DocumentFile.fromTreeUri(this, treeUri);
            if (pickedDir == null || !pickedDir.canWrite()) {
                Log.e(TAG, "createNextSegmentOutputFile: Cannot write to selected custom directory");
                return null;
            }
            String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
            String segmentSuffix = String.format(Locale.US, "_%03d", nextSegmentNumber);
            // Use FadCam prefix for consistent naming
            String baseFilename = Constants.RECORDING_DIRECTORY + "_" + timestamp + segmentSuffix + "." + Constants.RECORDING_FILE_EXTENSION;
            DocumentFile nextDocFile = pickedDir.createFile("video/" + Constants.RECORDING_FILE_EXTENSION, baseFilename);
            if (nextDocFile == null || !nextDocFile.exists()) {
                Log.e(TAG, "createNextSegmentOutputFile: Failed to create DocumentFile in SAF: " + baseFilename);
                return null;
            }
            try {
                // Open a ParcelFileDescriptor for the next segment
                ParcelFileDescriptor pfd = getContentResolver().openFileDescriptor(nextDocFile.getUri(), "w");
                if (pfd == null) {
                    Log.e(TAG, "createNextSegmentOutputFile: Failed to open ParcelFileDescriptor for next SAF URI: " + nextDocFile.getUri());
                    return null;
                }
                pfd.close(); // Immediately close, as the pipeline will open as needed
            } catch (Exception e) {
                Log.e(TAG, "createNextSegmentOutputFile: Exception opening PFD for next SAF URI", e);
                return null;
            }
            Log.i(TAG, "Next segment SAF file created: " + nextDocFile.getUri());
            return null;
        } else {
            // Internal storage mode - Use same directory and naming as first segment
            String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
            String segmentSuffix = String.format(Locale.US, "_%03d", nextSegmentNumber);
            String baseFilename = Constants.RECORDING_DIRECTORY + "_" + timestamp + segmentSuffix + "." + Constants.RECORDING_FILE_EXTENSION;
            
            // Use the same directory as the first segment (app's external files directory)
            File videoDir = new File(getExternalFilesDir(null), Constants.RECORDING_DIRECTORY);
            if (!videoDir.exists() && !videoDir.mkdirs()) {
                Log.e(TAG, "Cannot create recording directory: " + videoDir.getAbsolutePath());
                Toast.makeText(this, "Error creating recording directory", Toast.LENGTH_LONG).show();
                return null;
            }
            
            // Create the file directly in the final directory
            File nextFile = new File(videoDir, baseFilename);
            Log.i(TAG, "Next segment file created: " + nextFile.getAbsolutePath());
            return nextFile;
        }
        // ----- Fix Ended for this method(createNextSegmentOutputFile)-----
    }




    // Helper to check if a wired mic is connected
    private boolean isWiredMicConnected() {
        AudioManager audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            AudioDeviceInfo[] devices = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS);
            for (AudioDeviceInfo device : devices) {
                if (device.getType() == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                    device.getType() == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                    device.getType() == AudioDeviceInfo.TYPE_USB_DEVICE ||
                    device.getType() == AudioDeviceInfo.TYPE_USB_HEADSET) {
                    return true;
                }
            }
        } else {
            Intent intent = registerReceiver(null, new IntentFilter(Intent.ACTION_HEADSET_PLUG));
            return intent != null && intent.getIntExtra("state", 0) == 1;
        }
        return false;
    }


    /**
     * Sets the camera resource releasing state and schedules it to be available again after a cooldown period.
     * Also broadcasts the availability state change to the UI components.
     * 
     * @param releasing True if camera resources are being released, false otherwise
     */
    private void setCameraResourcesReleasing(boolean releasing) {
        isCameraResourceReleasing = releasing;
        Log.d(TAG, "Camera resources releasing state set to: " + releasing);
        
        // Broadcast the current availability state
        broadcastCameraResourceAvailability(!releasing);
        
        // If we're releasing resources, schedule them to become available after a cooldown
        if (releasing) {
            mainHandler.postDelayed(() -> {
                isCameraResourceReleasing = false;
                Log.d(TAG, "Camera resource cooldown ended, resources available now");
                
                // Broadcast that camera resources are available again
                broadcastCameraResourceAvailability(true);
            }, Constants.CAMERA_RESOURCE_COOLDOWN_MS);
        }
    }
    
    /**
     * Broadcasts the camera resource availability state to UI components
     * 
     * @param available True if camera resources are available for a new recording
     */
    private void broadcastCameraResourceAvailability(boolean available) {
        Intent availabilityIntent = new Intent(Constants.ACTION_CAMERA_RESOURCE_AVAILABILITY);
        availabilityIntent.putExtra(Constants.EXTRA_CAMERA_RESOURCES_AVAILABLE, available);
        sendBroadcast(availabilityIntent);
        Log.d(TAG, "Broadcasted camera resource availability: " + available);
    }


    /**
     * Helper method to verify if location metadata was successfully embedded in a video file
     * This method will add logs that you can filter for "METADATA_CHECK" to trace the issue
     * @param videoFilePath Path to the final processed video file
     */
    private void verifyLocationMetadata(String videoFilePath) {
        if (videoFilePath == null) {
            Log.e(TAG, "METADATA_CHECK: Video file path is null");
            return;
        }
        
        Log.d(TAG, "METADATA_CHECK: Checking for location metadata in " + videoFilePath);
        
        try {
            MediaMetadataRetriever retriever = new MediaMetadataRetriever();
            retriever.setDataSource(videoFilePath);
            
            // Get location data
            String locationString = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_LOCATION);
            
            if (locationString != null && !locationString.isEmpty()) {
                Log.d(TAG, "METADATA_CHECK: Found location data in video: " + locationString);
            } else {
                Log.e(TAG, "METADATA_CHECK: No location metadata found in processed video!");
                Log.e(TAG, "METADATA_CHECK: This suggests the metadata was lost during processing");
            }
            
            // Check a few other metadata fields to see if any metadata is preserved
            String duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            String width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH);
            String height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT);
            
            Log.d(TAG, "METADATA_CHECK: Other metadata - Duration: " + duration + 
                  ", Resolution: " + width + "x" + height);
            
            retriever.release();
        } catch (Exception e) {
            Log.e(TAG, "METADATA_CHECK: Error checking metadata", e);
        }
    }


    /**
     * Reinitializes location helpers based on current preference settings
     * Called when location settings are changed while the service is running
     */
    private void reinitializeLocationHelpers() {
        reinitializeLocationHelpers(false);
    }

    /**
     * Reinitializes location helpers based on current preference settings
     * @param forceInit Force reinitialization even if settings haven't changed
     */
    private void reinitializeLocationHelpers(boolean forceInit) {
        Log.d(TAG, "==== Reinitializing Location Helpers ====");
        Log.d(TAG, "Current preferences: location=" + sharedPreferencesManager.isLocalisationEnabled() + 
               ", embedding=" + sharedPreferencesManager.isLocationEmbeddingEnabled());
        
        // Handle LocationHelper for watermark
        boolean locationEnabled = sharedPreferencesManager.isLocalisationEnabled();
        if (locationEnabled) {
            if (locationHelper == null || forceInit) {
                try {
                    // Clean up existing helper if needed
                    if (locationHelper != null) {
                        locationHelper.stopLocationUpdates();
                    }
                    
                    locationHelper = new LocationHelper(this);
                    Log.d(TAG, "Created new LocationHelper for watermark");
                } catch (Exception e) {
                    Log.e(TAG, "Error initializing LocationHelper", e);
                }
            } else {
                Log.d(TAG, "LocationHelper already exists, no need to recreate");
            }
        } else {
            if (locationHelper != null) {
                try {
                    locationHelper.stopLocationUpdates();
                    Log.d(TAG, "Stopped existing LocationHelper");
                    locationHelper = null;
                } catch (Exception e) {
                    Log.e(TAG, "Error stopping LocationHelper", e);
                }
            }
        }
        
        // Handle GeotagHelper for metadata embedding
        boolean embeddingEnabled = sharedPreferencesManager.isLocationEmbeddingEnabled();
        if (embeddingEnabled) {
            boolean needsNewHelper = geotagHelper == null || forceInit;
            
            if (needsNewHelper) {
                try {
                    // Clean up existing helper if needed
                    if (geotagHelper != null) {
                        geotagHelper.stopUpdates();
                    }
                    
                    // Create new helper
                    geotagHelper = new GeotagHelper(this);
                    boolean started = geotagHelper.startUpdates();
                    Log.d(TAG, "Created new GeotagHelper for metadata embedding. Updates started: " + started);
                } catch (Exception e) {
                    Log.e(TAG, "Error initializing GeotagHelper", e);
                }
            } else {
                Log.d(TAG, "GeotagHelper already exists, ensuring updates are started");
                // Make sure updates are started even if helper exists
                try {
                    boolean started = geotagHelper.startUpdates();
                    Log.d(TAG, "Ensured GeotagHelper updates are active: " + started);
                } catch (Exception e) {
                    Log.e(TAG, "Error starting GeotagHelper updates", e);
                }
            }
            

        } else {
            if (geotagHelper != null) {
                try {
                    geotagHelper.stopUpdates();
                    Log.d(TAG, "Stopped existing GeotagHelper");
                    geotagHelper = null;
                } catch (Exception e) {
                    Log.e(TAG, "Error stopping GeotagHelper", e);
                }
            }
        }
        
        Log.d(TAG, "==== Location Helpers Reinitialization Complete ====");
    }


    /**
     * Creates a high-speed constrained capture session for 60fps+ recording
     */
    private void createHighSpeedSession(List<Surface> surfaces, CameraCharacteristics characteristics, 
                                       int targetFrameRate) {
        try {
            // For high-speed recording, we need a constrained high-speed capture session
            Log.d(TAG, "Creating constrained high-speed session for " + targetFrameRate + "fps");
            
            // Get the best size for high-speed recording
            Size highSpeedSize = HighSpeedCaptureHelper.getBestHighSpeedSize(
                    characteristics, targetFrameRate, 0, 0);
                    
            if (highSpeedSize == null) {
                Log.d(TAG, "No suitable high-speed size found");
                // Fallback to standard session
                createStandardSession(surfaces, targetFrameRate, characteristics);
                return;
            }
            
            // Configure a builder for high-speed recording
            captureRequestBuilder = HighSpeedCaptureHelper.configureHighSpeedRequestBuilder(
                    cameraDevice, null, targetFrameRate, characteristics);
                    
            if (captureRequestBuilder == null) {
                Log.d(TAG, "Failed to create high-speed request builder");
                // Fallback to standard session
                createStandardSession(surfaces, targetFrameRate, characteristics);
                return;
            }
            
            // Add surfaces as targets
            for (Surface surface : surfaces) {
                captureRequestBuilder.addTarget(surface);
            }
            
            // Set torch mode if enabled
            if (isRecordingTorchEnabled) {
                captureRequestBuilder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_TORCH);
            } else {
                captureRequestBuilder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF);
            }
            
            // Create the constrained high-speed session
            cameraDevice.createConstrainedHighSpeedCaptureSession(
                    surfaces, 
                    highSpeedSessionCallback, 
                    backgroundHandler);
                    
            Log.d(TAG, "Requested constrained high-speed capture session creation");
        } catch (Exception e) {
            Log.e(TAG, "Failed to create high-speed session", e);
            // Fallback to standard session
            try {
                createStandardSession(surfaces, targetFrameRate, characteristics);
            } catch (Exception e2) {
                Log.e(TAG, "Failed to create fallback standard session", e2);
                stopRecording();
            }
        }
    }

    /**
     * Fallback to create a standard session with the best possible frame rate settings
     */
    private void createStandardSession(List<Surface> surfaces, int targetFrameRate, 
                                     CameraCharacteristics characteristics) {
        try {
            Log.d(TAG, "Creating standard session with optimized frame rate settings");
            
            // Create standard request builder
            captureRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
            
            // Add surfaces as targets
            for (Surface surface : surfaces) {
                captureRequestBuilder.addTarget(surface);
            }
            
            // Apply frame rate settings
            applyFrameRateSettings(captureRequestBuilder, targetFrameRate, characteristics);
            
            // Set torch mode if enabled
            if (isRecordingTorchEnabled) {
                captureRequestBuilder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_TORCH);
            } else {
                captureRequestBuilder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF);
            }
            
            // Create the session
            cameraDevice.createCaptureSession(surfaces, captureSessionCallback, backgroundHandler);
        } catch (Exception e) {
            Log.e(TAG, "Failed to create standard session", e);
            stopRecording();
        }
    }

    /**
     * Apply appropriate frame rate settings based on device type
     */
    private void applyFrameRateSettings(CaptureRequest.Builder builder, int targetFrameRate, 
                                      CameraCharacteristics characteristics) {
        // Apply standard AE target FPS range
        builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, new android.util.Range<>(targetFrameRate, targetFrameRate));
        Log.d(TAG, "Set CONTROL_AE_TARGET_FPS_RANGE to [" + targetFrameRate + "," + targetFrameRate + "]");

        // Apply Samsung-specific keys if applicable
        if (DeviceHelper.isSamsung()) {
            SamsungFrameRateHelper.applyFrameRateSettings(builder, targetFrameRate);
        }

        // Apply Huawei-specific keys if applicable
        if (DeviceHelper.isHuawei() && targetFrameRate >= 60) {
            HuaweiFrameRateHelper.applyFrameRateSettings(builder, targetFrameRate);
        }
    }

    /**
     * Previously showed toast messages for high frame rates
     * Now just logs the message as warnings are shown permanently in the settings UI
     */
    private void showFrameRateToast(int frameRate) {
        // Frame rate warnings are now shown permanently in the settings UI
        // This method is kept to avoid refactoring all callers
        
        if (frameRate >= 60) {
            // Just log the high frame rate usage
            if (DeviceHelper.isSamsung()) {
                Log.d(TAG, "Using experimental " + frameRate + "fps mode for Samsung");
            } else if (DeviceHelper.isHuawei()) {
                Log.d(TAG, "Using experimental " + frameRate + "fps mode for Huawei");
            } else {
                Log.d(TAG, "Using experimental " + frameRate + "fps mode");
            }
        }
    }

    /**
     * Helper method to get the proper camera ID based on camera type
     */
    private String getCameraId(CameraManager cameraManager, CameraType cameraType) {
        if (cameraManager == null) return null;
        
        try {
            if (cameraType == CameraType.BACK) {
                // For back camera, use the selected back camera ID
                return sharedPreferencesManager.getSelectedBackCameraId();
            } else {
                // For front camera, find the first front-facing camera
                String[] cameraIds = cameraManager.getCameraIdList();
                for (String id : cameraIds) {
                    CameraCharacteristics chars = cameraManager.getCameraCharacteristics(id);
                    Integer facing = chars.get(CameraCharacteristics.LENS_FACING);
                    if (facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT) {
                        return id;
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error finding camera ID", e);
        }
        
        return null;
    }

    /**
     * Handle common tasks after any session is configured
     */
    private void handleSessionConfigured() {
        // Handle recording states
        if (recordingState == RecordingState.STARTING) {
            try {
                // Start the MediaRecorder

                recordingState = RecordingState.IN_PROGRESS;
                

                // Use SystemClock.elapsedRealtime() instead of System.currentTimeMillis() for consistency with HomeFragment
                recordingStartTime = SystemClock.elapsedRealtime();
                Log.d(TAG, "Recording started with recordingStartTime=" + recordingStartTime);

                
                // Setup notification
                setupRecordingInProgressNotification();
                
                // Broadcast that recording has started
                broadcastOnRecordingStarted();
                
                Log.d(TAG, "Recording started successfully");
            } catch (Exception e) {
                Log.e(TAG, "Failed to start recording", e);
                stopRecording();
            }
        } else if (recordingState == RecordingState.IN_PROGRESS) {
            // This typically means the session was reconfigured
            Log.d(TAG, "Session reconfigured while recording is in progress");
            setupRecordingInProgressNotification();
        } else if (recordingState == RecordingState.PAUSED) {
            // Handle paused state
            Log.d(TAG, "Session configured while in PAUSED state");
            setupRecordingResumeNotification();
        }
    }

    // Add these fields to RecordingService class
    private GLRecordingPipeline glRecordingPipeline;
    private WatermarkInfoProvider watermarkInfoProvider;

    // Add this helper method for OpenGL pipeline direct output
    private File getFinalOutputFile() {
        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
        String segmentSuffix = ""; // No segment number for the initial file
        String baseFilename = Constants.RECORDING_DIRECTORY + "_" + timestamp + segmentSuffix + "." + Constants.RECORDING_FILE_EXTENSION;
        File videoDir = new File(getExternalFilesDir(null), Constants.RECORDING_DIRECTORY);
        if (!videoDir.exists() && !videoDir.mkdirs()) {
            Log.e(TAG, "Cannot create internal recording directory: " + videoDir.getAbsolutePath());
            Toast.makeText(this, "Error creating internal storage directory", Toast.LENGTH_LONG).show();
            return null;
        }
        return new File(videoDir, baseFilename);
    }


    // Inner class for OpenGL pipeline segment callback
    private class GLSegmentCallback implements com.fadcam.opengl.GLRecordingPipeline.SegmentCallback {
        @Override
        public void onSegmentRollover(int nextSegmentNumber) {
            // ----- Fix Start for this method(onSegmentRollover)-----
            Log.d(TAG, "GLSegmentCallback.onSegmentRollover called for segment " + nextSegmentNumber);
            String storageMode = sharedPreferencesManager.getStorageMode();
            if (SharedPreferencesManager.STORAGE_MODE_CUSTOM.equals(storageMode)) {
                Log.d(TAG, "Using custom storage mode (SAF) for segment rollover");
                // SAF: create new file and open new ParcelFileDescriptor
                String customUriString = sharedPreferencesManager.getCustomStorageUri();
                if (customUriString == null) {
                    Log.e(TAG, "Segment rollover: Custom storage selected but URI is null");
                    stopRecording();
                    return;
                }
                Uri treeUri = Uri.parse(customUriString);
                androidx.documentfile.provider.DocumentFile pickedDir = androidx.documentfile.provider.DocumentFile.fromTreeUri(RecordingService.this, treeUri);
                if (pickedDir == null || !pickedDir.canWrite()) {
                    Log.e(TAG, "Segment rollover: Cannot write to selected custom directory");
                    stopRecording();
                    return;
                }
                String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
                String segmentSuffix = String.format(Locale.US, "_%03d", nextSegmentNumber);
                String baseFilename = Constants.RECORDING_DIRECTORY + "_" + timestamp + segmentSuffix + ".mp4";
                Log.d(TAG, "Creating new segment file: " + baseFilename);
                androidx.documentfile.provider.DocumentFile videoFile = pickedDir.createFile("video/mp4", baseFilename);
                if (videoFile == null) {
                    Log.e(TAG, "Segment rollover: Failed to create SAF file");
                    stopRecording();
                    return;
                }
                Uri safUri = videoFile.getUri();
                try (ParcelFileDescriptor pfd = getContentResolver().openFileDescriptor(safUri, "w")) {
                    if (pfd == null) {
                        Log.e(TAG, "Segment rollover: Failed to open ParcelFileDescriptor for SAF URI");
                        stopRecording();
                        return;
                    }
                    Log.d(TAG, "Successfully created new segment file with SAF: " + videoFile.getName());
                    if (glRecordingPipeline != null) {
                        glRecordingPipeline.setNextOutput(null, pfd.getFileDescriptor());
                        Log.d(TAG, "Set next output to file descriptor for segment " + nextSegmentNumber);
                    } else {
                        Log.e(TAG, "glRecordingPipeline is null, cannot set next output");
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Segment rollover: Exception opening PFD for SAF URI", e);
                    stopRecording();
                }
            } else {
                Log.d(TAG, "Using internal storage for segment rollover");
                // Internal: use createNextSegmentOutputFile()
                File nextFile = createNextSegmentOutputFile(nextSegmentNumber);
                if (nextFile == null) {
                    Log.e(TAG, "Segment rollover: Failed to create next segment file");
                    stopRecording();
                    return;
                }
                Log.d(TAG, "Successfully created new segment file: " + nextFile.getAbsolutePath());
                if (glRecordingPipeline != null) {
                    glRecordingPipeline.setNextOutput(nextFile.getAbsolutePath(), null);
                    Log.d(TAG, "Set next output to path: " + nextFile.getAbsolutePath() + " for segment " + nextSegmentNumber);
                } else {
                    Log.e(TAG, "glRecordingPipeline is null, cannot set next output");
                }
            }
            // ----- Fix Ended for this method(onSegmentRollover)-----
        }
    }

    // Update startRecording to use new GLRecordingPipeline constructor
    private void startRecording() {
        Log.d(TAG, "startRecording: beginning recording setup");
        if (recordingState != RecordingState.STARTING) {
            Log.e(TAG, "startRecording was called but state is " + recordingState + ", expected STARTING");
            return;
        }
        createNotificationChannel();

        if (sharedPreferencesManager.isLocationEmbeddingEnabled()) {
            new Thread(() -> {
                try {
                    if (geotagHelper == null) geotagHelper = new GeotagHelper(this);
                    geotagHelper.startUpdates();
                    Thread.sleep(800);
                } catch (Exception e) { Log.e(TAG, "Error initializing GeotagHelper", e); }
            }).start();
        }

        try {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED ||
                ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                Log.e(TAG,"Permissions missing, cannot start recording.");
                Toast.makeText(this,"Permissions required for recording", Toast.LENGTH_LONG).show();
                recordingState = RecordingState.NONE;
                sharedPreferencesManager.setRecordingInProgress(false);
                stopSelf();
                return;
            }

            if (recordingWakeLock != null && !recordingWakeLock.isHeld()) recordingWakeLock.acquire();

            watermarkInfoProvider = new WatermarkInfoProvider() {
                @Override
                public String getWatermarkText() {
                    String watermarkOption = sharedPreferencesManager.getWatermarkOption();
                    String locationText = sharedPreferencesManager.isLocalisationEnabled() ? getLocationData() : "";
                    switch (watermarkOption) {
                        case "timestamp_fadcam":
                            return "Captured by FadCam - " + getCurrentTimestamp() + locationText;
                        case "timestamp":
                            return getCurrentTimestamp() + locationText;
                        case "no_watermark":
                            return "";
                        default:
                            return "Captured by FadCam - " + getCurrentTimestamp() + locationText;
                    }
                }
            };

            Size resolution = sharedPreferencesManager.getCameraResolution();
            String orientation = sharedPreferencesManager.getVideoOrientation();
            int videoWidth = resolution.getWidth();
            int videoHeight = resolution.getHeight();

            // Get sensor orientation
            CameraManager cameraManager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
            CameraType cameraType = sharedPreferencesManager.getCameraSelection();
            String cameraId = getCameraId(cameraManager, cameraType);
            int sensorOrientation = 0;
            if (cameraId != null) {
                try {
                    CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(cameraId);
                    Integer so = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
                    if (so != null) sensorOrientation = so;
                } catch (Exception e) {
                    Log.e(TAG, "Error getting sensor orientation", e);
                }
            }
            int videoBitrate = getVideoBitrate();
            int videoFramerate = sharedPreferencesManager.getVideoFrameRate();
            // ----- Fix Start for video splitting -----
            // Set splitSizeBytes to 0 if video splitting is disabled
            long splitSizeBytes = 0;
            if (sharedPreferencesManager.isVideoSplittingEnabled()) {
                splitSizeBytes = sharedPreferencesManager.getVideoSplitSizeBytes();
                Log.d(TAG, "Video splitting enabled with size: " + splitSizeBytes + " bytes");
            } else {
                Log.d(TAG, "Video splitting disabled");
            }
            // ----- Fix Ended for video splitting -----
            int initialSegmentNumber = 1;
            GLSegmentCallback segmentCallback = new GLSegmentCallback();
            
            Log.d(TAG, "Creating GLRecordingPipeline with: " +
                  "width=" + videoWidth + ", height=" + videoHeight + 
                  ", bitrate=" + videoBitrate + ", framerate=" + videoFramerate +
                  ", orientation=" + orientation + ", sensorOrientation=" + sensorOrientation);
            
            String storageMode = sharedPreferencesManager.getStorageMode();
            if (SharedPreferencesManager.STORAGE_MODE_CUSTOM.equals(storageMode)) {
                String customUriString = sharedPreferencesManager.getCustomStorageUri();
                if (customUriString == null) {
                    Log.e(TAG, "Failed to open ParcelFileDescriptor for SAF URI");
                    Toast.makeText(this, "Failed to open file for writing", Toast.LENGTH_LONG).show();
                    stopSelf();
                    return;
                }
                Uri treeUri = Uri.parse(customUriString);
                androidx.documentfile.provider.DocumentFile pickedDir = androidx.documentfile.provider.DocumentFile.fromTreeUri(this, treeUri);
                if (pickedDir == null || !pickedDir.canWrite()) {
                    Log.e(TAG, "Cannot write to selected custom directory");
                    Toast.makeText(this, "Cannot write to selected custom directory", Toast.LENGTH_LONG).show();
                    stopSelf();
                    return;
                }
                String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
                String baseFilename = Constants.RECORDING_DIRECTORY + "_" + timestamp + ".mp4";
                androidx.documentfile.provider.DocumentFile videoFile = pickedDir.createFile("video/mp4", baseFilename);
                if (videoFile == null) {
                    Log.e(TAG, "Failed to create SAF file");
                    Toast.makeText(this, "Failed to create file for writing", Toast.LENGTH_LONG).show();
                    stopSelf();
                    return;
                }
                Uri safUri = videoFile.getUri();
                try (ParcelFileDescriptor pfd = getContentResolver().openFileDescriptor(safUri, "w")) {
                    if (pfd == null) {
                        Log.e(TAG, "Failed to open ParcelFileDescriptor for SAF URI");
                        Toast.makeText(this, "Failed to open file for writing", Toast.LENGTH_LONG).show();
                        stopSelf();
                        return;
                    }
                    Log.d(TAG, "Creating GLRecordingPipeline with SAF file descriptor");
                    glRecordingPipeline = new com.fadcam.opengl.GLRecordingPipeline(this, watermarkInfoProvider, videoWidth, videoHeight, videoFramerate, pfd.getFileDescriptor(), splitSizeBytes, initialSegmentNumber, segmentCallback, previewSurface, orientation, sensorOrientation);
                } catch (Exception e) {
                    Log.e(TAG, "Exception opening PFD for SAF URI", e);
                    Toast.makeText(this, "Failed to open file for writing", Toast.LENGTH_LONG).show();
                    stopSelf();
                    return;
                }
            } else {
                File outputFile = getFinalOutputFile();
                Log.d(TAG, "Creating GLRecordingPipeline with internal file: " + outputFile.getAbsolutePath());
                glRecordingPipeline = new com.fadcam.opengl.GLRecordingPipeline(this, watermarkInfoProvider, videoWidth, videoHeight, videoFramerate, outputFile.getAbsolutePath(), splitSizeBytes, initialSegmentNumber, segmentCallback, previewSurface, orientation, sensorOrientation);
            }
            
            Log.d(TAG, "Preparing GLRecordingPipeline surfaces");
            glRecordingPipeline.prepareSurfaces();
            
            Log.d(TAG, "Creating camera preview session");
            createCameraPreviewSession();
            
            Log.d(TAG, "Recording setup complete");
        } catch (Exception e) {
            Log.e(TAG, "Exception in startRecording", e);
            recordingState = RecordingState.NONE;
            sharedPreferencesManager.setRecordingInProgress(false);
            stopSelf();
        }
    }

    // Add this field to the class
    private Surface dummyBackgroundSurface = null; // Used as fallback when app is backgrounded
    private SurfaceTexture dummySurfaceTexture = null; // Used to create dummy surface

    // Add this method to create a dummy surface
    private void createDummyBackgroundSurface() {
        // Release any existing dummy resources
        releaseDummyBackgroundSurface();
        
        try {
            // Create a 1x1 SurfaceTexture (minimal size/resources)
            dummySurfaceTexture = new SurfaceTexture(0);
            dummySurfaceTexture.setDefaultBufferSize(1, 1);
            dummyBackgroundSurface = new Surface(dummySurfaceTexture);
            
            Log.d(TAG, "Created dummy background surface to prevent green screen on Samsung");
        } catch (Exception e) {
            Log.e(TAG, "Failed to create dummy background surface", e);
            dummySurfaceTexture = null;
            dummyBackgroundSurface = null;
        }
    }

    // Add this method to release the dummy surface
    private void releaseDummyBackgroundSurface() {
        if (dummyBackgroundSurface != null) {
            try {
                dummyBackgroundSurface.release();
            } catch (Exception e) {
                Log.e(TAG, "Error releasing dummyBackgroundSurface", e);
            } finally {
                dummyBackgroundSurface = null;
            }
        }
        
        if (dummySurfaceTexture != null) {
            try {
                dummySurfaceTexture.release();
            } catch (Exception e) {
                Log.e(TAG, "Error releasing dummySurfaceTexture", e);
            } finally {
                dummySurfaceTexture = null;
            }
        }
    }

    // ----- Fix Start for camera interruption handling -----
    // Flag to track if we need to automatically resume recording after camera interruption
    private boolean pendingCameraReconnect = false;
    private static final int MAX_RECONNECT_ATTEMPTS = 15; // Maximum number of reconnection attempts
    private static final long RECONNECT_RETRY_DELAY_MS = 2000; // 2 seconds between reconnection attempts
    private Handler reconnectHandler = new Handler(Looper.getMainLooper());
    private int reconnectAttempts = 0;
    private Runnable reconnectRunnable;
// ----- Fix End for camera interruption handling -----

    // ----- Fix Start for camera interruption handling -----
    /**
     * Starts periodic attempts to reconnect to the camera
     * 
     * @param cameraId The ID of the camera to reconnect to
     */
    private void startCameraReconnectionAttempts(String cameraId) {
        Log.d(TAG, "Starting camera reconnection attempts");
        reconnectAttempts = 0;
        
        // Stop any existing reconnection attempts
        stopReconnectionAttempts();
        
        // Create new reconnection runnable
        reconnectRunnable = new Runnable() {
            @Override
            public void run() {
                if (recordingState == RecordingState.WAITING_FOR_CAMERA && 
                    pendingCameraReconnect && 
                    reconnectAttempts < MAX_RECONNECT_ATTEMPTS) {
                    
                    reconnectAttempts++;
                    Log.d(TAG, "Attempting to reconnect to camera (attempt " + reconnectAttempts + "/" + MAX_RECONNECT_ATTEMPTS + ")");
                    
                    // Update notification to show reconnection attempt
                    NotificationCompat.Builder builder = createBaseNotificationBuilder()
                        .setContentTitle(getString(R.string.camera_interrupted_title))
                        .setContentText(getString(R.string.camera_reconnection_attempts));
                    NotificationManagerCompat.from(RecordingService.this).notify(NOTIFICATION_ID, builder.build());
                    
                    // Try to open the camera
                    tryReconnectCamera(cameraId);
                    
                    // Schedule next attempt if we haven't reached the limit
                    if (recordingState == RecordingState.WAITING_FOR_CAMERA && 
                        pendingCameraReconnect && 
                        reconnectAttempts < MAX_RECONNECT_ATTEMPTS) {
                        reconnectHandler.postDelayed(this, RECONNECT_RETRY_DELAY_MS);
                    } else if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
                        // We've reached the maximum number of attempts
                        Log.w(TAG, "Maximum reconnection attempts reached. Stopping recording.");
                        pendingCameraReconnect = false;
                        recordingState = RecordingState.NONE;
                        stopRecording(); // Give up and stop recording
                    }
                }
            }
        };
        
        // Start the first attempt immediately
        reconnectHandler.post(reconnectRunnable);
    }
    
    /**
     * Attempts to reconnect to the camera
     * 
     * @param cameraId The ID of the camera to reconnect to
     */
    private void tryReconnectCamera(String cameraId) {
        if (cameraManager == null) {
            Log.e(TAG, "Cannot reconnect to camera - cameraManager is null");
            return;
        }
        
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "Cannot reconnect to camera - permission denied");
            return;
        }
        

        
        // Ensure existing camera resources are properly cleaned up
        if (cameraDevice != null) {
            try {
                cameraDevice.close();
            } catch (Exception e) {
                Log.w(TAG, "Error closing existing camera device during reconnection", e);
            }
            cameraDevice = null;
        }
        
        if (captureSession != null) {
            try {
                captureSession.close();
            } catch (Exception e) {
                Log.w(TAG, "Error closing existing capture session during reconnection", e);
            }
            captureSession = null;
        }
        
        try {
            // Check if the camera is available by attempting to open it
            cameraManager.openCamera(cameraId, cameraStateCallback, backgroundHandler);
        } catch (CameraAccessException e) {
            // Camera is still not available
            int reason = e.getReason();
            if (reason == CameraAccessException.CAMERA_DISABLED || 
                reason == 1) { // 1 is CAMERA_IN_USE
                Log.d(TAG, "Camera still not available (reason: " + reason + ")");
            } else {
                Log.e(TAG, "Error reconnecting to camera (reason: " + reason + ")", e);
            }
        } catch (Exception e) {
            Log.e(TAG, "Unexpected error reconnecting to camera", e);
        }
    }
    
    /**
     * Stops any ongoing camera reconnection attempts
     */
    private void stopReconnectionAttempts() {
        if (reconnectRunnable != null) {
            reconnectHandler.removeCallbacks(reconnectRunnable);
            Log.d(TAG, "Stopped camera reconnection attempts");
        }
        
        // Reset related flags
        pendingCameraReconnect = false;
        reconnectAttempts = 0;
    }
    // ----- Fix End for camera interruption handling -----
}