package com.fadcam.services;

import android.Manifest;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.media.MediaRecorder;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.PowerManager;
import android.os.PowerManager.WakeLock;
import android.os.SystemClock;
import android.preference.PreferenceManager;
import android.provider.DocumentsContract;
import android.util.Log; // Use standard Log
import android.util.Range;
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
import com.fadcam.ui.RecordsAdapter;
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
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import android.content.Intent; // Add Intent import
import android.net.Uri;       // Add Uri import
import com.fadcam.Constants; // Import your Constants class
import java.util.Set; // Add if needed
import java.util.HashSet; // Add if needed

// ----- Fix Start for this class (RecordingService_video_splitting_imports_and_fields) -----
import android.media.MediaRecorder.OnInfoListener;
// ----- Fix Ended for this class (RecordingService_video_splitting_imports_and_fields) -----

import org.osmdroid.util.GeoPoint;

import java.util.concurrent.ConcurrentLinkedQueue;

// Add to the beginning of the file
import android.media.MediaMetadataRetriever;

import android.hardware.camera2.CameraConstrainedHighSpeedCaptureSession;
import com.fadcam.utils.DeviceHelper;
import com.fadcam.utils.camera.FrameRateHelper;
import com.fadcam.utils.camera.HighSpeedCaptureHelper;
import com.fadcam.utils.camera.vendor.SamsungFrameRateHelper;
import com.fadcam.utils.camera.vendor.HuaweiFrameRateHelper;

// Add import
import com.fadcam.opengl.GLRecordingPipeline;
import com.fadcam.opengl.WatermarkInfoProvider;

// ----- Fix Start: Add isCameraResourceReleasing field -----
// ----- Fix End: Add isCameraResourceReleasing field -----

public class RecordingService extends Service {

    private static final int NOTIFICATION_ID = 1;
    private static final String CHANNEL_ID = "RecordingServiceChannel";
    private static final String TAG = "RecordingService"; // Use standard Log TAG
    private static volatile boolean isCameraResourceReleasing = false;

    private CameraDevice cameraDevice;
    private CameraCaptureSession captureSession;
    private CaptureRequest.Builder captureRequestBuilder;
    private Surface previewSurface; // Surface from UI if preview enabled

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
    // ----- Fix Start for this method(onStartCommand_video_splitting) -----
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
                    openCamera();
                } else {
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
        // ----- Fix Start for this method(onStartCommand) -----
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
        // ----- Fix Ended for this method(onStartCommand) -----
        else {
            Log.w(TAG, "Unknown action received: " + action);
            if (!isWorkingInProgress()) stopSelf();
            return START_NOT_STICKY;
        }
    }
    // ----- Fix Ended for this method(onStartCommand_video_splitting) -----

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null; // Not a bound service
    }


    @Override
    public void onDestroy() {
        Log.d(TAG, "onDestroy: Service being destroyed...");
        
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
        if (isStopping) return;
        isStopping = true;
        Log.i(TAG, ">> stopRecording sequence initiated. Current state: " + recordingState);
        if (recordingState == RecordingState.NONE) {
            sharedPreferencesManager.setRecordingInProgress(false);
            if (!isWorkingInProgress()) stopSelf();
            if (recordingWakeLock != null && recordingWakeLock.isHeld()) recordingWakeLock.release();
            return;
        }
        recordingState = RecordingState.NONE;
        sharedPreferencesManager.setRecordingInProgress(false);

        if (glRecordingPipeline != null) {
            glRecordingPipeline.stopRecording();
            glRecordingPipeline = null;
            Log.d(TAG, "GLRecordingPipeline stopped and released.");
        }
        stopForeground(true);
        cancelNotification();
        try { if (captureSession != null) { captureSession.close(); } } catch (Exception e) { } finally { captureSession = null; }
        try { if (cameraDevice != null) { cameraDevice.close(); } } catch (Exception e) { } finally { cameraDevice = null; }
        setCameraResourcesReleasing(true);
        broadcastOnRecordingStopped();
        checkIfServiceCanStop();
        if (recordingWakeLock != null && recordingWakeLock.isHeld()) recordingWakeLock.release();
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
        // ----- Fix Start for this method(checkIfServiceCanStop)-----
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
        // ----- Fix Ended for this method(checkIfServiceCanStop)-----
    }

    // --- MediaRecorder & Camera Session Setup ---
    // Updated setupMediaRecorder to target EXTERNAL cache first

    // ----- Fix Start for this method(setupMediaRecorder_saf_pfd_support)-----
    // private void setupMediaRecorder() throws IOException {
    //     Log.d(TAG, "Setting up MediaRecorder for segment " + currentSegmentNumber + "...");
    //     releaseMediaRecorderSafely();
    //     try {
    //         Thread.sleep(500);
    //         Log.d(TAG, "Waited 500ms after media recorder release.");
    //     } catch (InterruptedException e) {
    //         Log.w(TAG, "Delay interrupted while waiting to create new MediaRecorder", e);
    //         Thread.currentThread().interrupt();
    //     }
    //     mediaRecorder = new MediaRecorder();
    //     Log.d(TAG, "New MediaRecorder instance created for segment " + currentSegmentNumber);
    //     mediaRecorder.setOnInfoListener(mediaRecorderInfoListener);
    //     mediaRecorder.setOnErrorListener((mr, what, extra) -> {
    //         Log.e(TAG, "MediaRecorder error: what=" + what + ", extra=" + extra + " for segment " + currentSegmentNumber);
    //         new Handler(Looper.getMainLooper()).post(() -> {
    //             Log.e(TAG, "Handling MediaRecorder error on main service thread for segment " + currentSegmentNumber);
    //             stopRecording();
    //             Toast.makeText(getApplicationContext(), "Recording error (segment " + currentSegmentNumber + ", code: " + what + ")", Toast.LENGTH_LONG).show();
    //         });
    //     });
    //     if (sharedPreferencesManager.isRecordAudioEnabled()) {
    //         int maxRetries = 3;
    //         int retryDelayMs = 500;
    //         boolean audioSourceSet = false;
    //         String audioInputSource = sharedPreferencesManager.getAudioInputSource();
    //         int requestedSource = MediaRecorder.AudioSource.MIC; // Default
    //         boolean wiredMicAvailable = isWiredMicConnected();
    //         if (audioInputSource.equals(SharedPreferencesManager.AUDIO_INPUT_SOURCE_WIRED)) {
    //             if (wiredMicAvailable) {
    //                 requestedSource = MediaRecorder.AudioSource.CAMCORDER; // Use CAMCORDER for wired
    //             } else {
    //                 Log.w(TAG, "User selected wired mic, but none detected. Falling back to phone mic.");
    //                 Toast.makeText(getApplicationContext(), getString(R.string.audio_input_source_wired_not_connected), Toast.LENGTH_SHORT).show();
    //                 requestedSource = MediaRecorder.AudioSource.MIC;
    //             }
    //         } else {
    //             requestedSource = MediaRecorder.AudioSource.MIC;
    //         }
    //         for (int i = 0; i < maxRetries; i++) {
    //             try {
    //                 mediaRecorder.setAudioSource(requestedSource);
    //                 audioSourceSet = true;
    //                 Log.d(TAG, "setAudioSource (" + requestedSource + ") successful on attempt " + (i + 1));
    //                 break;
    //             } catch (RuntimeException e) {
    //                 Log.e(TAG, "setAudioSource failed on attempt " + (i + 1) + "/" + maxRetries + ": " + e.getMessage());
    //                 if (i < maxRetries - 1) {
    //                     try {
    //                         Log.d(TAG, "Resetting MediaRecorder before retrying setAudioSource due to RuntimeException.");
    //                         mediaRecorder.reset();
    //                         Thread.sleep(retryDelayMs);
    //                     } catch (InterruptedException ie) {
    //                         Thread.currentThread().interrupt();
    //                         Log.w(TAG, "Audio setup retry delay interrupted", ie);
    //                         throw new IOException("Audio setup retry interrupted", ie);
    //                     } catch (Exception resetEx) {
    //                         Log.e(TAG, "Exception during MediaRecorder.reset() in audio setup retry: " + resetEx.getMessage());
    //                         throw new IOException("Failed to reset MediaRecorder during audio setup retry", e);
    //                     }
    //                 } else {
    //                     Log.e(TAG, "setAudioSource failed after all retries. Forcing fallback to phone mic.");
    //                     // Fallback to phone mic as last resort
    //                     try {
    //                         mediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
    //                         audioSourceSet = true;
    //                         Log.w(TAG, "Fallback to phone mic audio source succeeded.");
    //                     } catch (Exception fallbackEx) {
    //                         Log.e(TAG, "Fallback to phone mic audio source failed.", fallbackEx);
    //                     }
    //                 }
    //             }
    //         }
    //         if (!audioSourceSet) {
    //             Log.e(TAG, "Failed to set audio source after retries, using phone mic as last resort.");
    //             try {
    //                 mediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
    //             } catch (Exception fallbackEx) {
    //                 Log.e(TAG, "Final fallback to phone mic audio source failed.", fallbackEx);
    //             }
    //         }
    //     }
    //     mediaRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
    //     mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
    //     String storageMode = sharedPreferencesManager.getStorageMode();
    //     if (SharedPreferencesManager.STORAGE_MODE_CUSTOM.equals(storageMode)) {
    //         // SAF/DocumentFile mode
    //         String customUriString = sharedPreferencesManager.getCustomStorageUri();
    //         if (customUriString == null) {
    //             handleStorageError("Custom storage selected but URI is null");
    //             throw new IOException("Custom storage selected but URI is null");
    //         }
    //         Uri treeUri = Uri.parse(customUriString);
    //         DocumentFile pickedDir = DocumentFile.fromTreeUri(this, treeUri);
    //         if (pickedDir == null || !pickedDir.canWrite()) {
    //             handleStorageError("Cannot write to selected custom directory");
    //             throw new IOException("Cannot write to selected custom directory");
    //         }
    //         // Create a new DocumentFile for this segment
    //         String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
    //         String segmentSuffix = String.format(Locale.US, "_%03d", currentSegmentNumber);
    //         String tempBaseFilename = "temp_" + timestamp + segmentSuffix + "." + Constants.RECORDING_FILE_EXTENSION;
    //         currentRecordingDocFile = pickedDir.createFile("video/" + Constants.RECORDING_FILE_EXTENSION, tempBaseFilename);
    //         if (currentRecordingDocFile == null || !currentRecordingDocFile.exists()) {
    //             Log.e(TAG, "Failed to create DocumentFile in SAF: " + tempBaseFilename);
    //             handleStorageError("Failed to create file in custom directory");
    //             throw new IOException("Failed to create file in custom directory");
    //         }
    //         currentRecordingSafUri = currentRecordingDocFile.getUri();
    //         Log.i(TAG, "Output configured for SAF: " + currentRecordingSafUri.toString());
    //         // Open a ParcelFileDescriptor for this segment
    //         closeCurrentPfd();
    //         currentSegmentPfd = getContentResolver().openFileDescriptor(currentRecordingSafUri, "w");
    //         if (currentSegmentPfd == null) {
    //             Log.e(TAG, "Failed to open ParcelFileDescriptor for SAF URI: " + currentRecordingSafUri);
    //             throw new IOException("Failed to open ParcelFileDescriptor for SAF URI");
    //         }
    //         mediaRecorder.setOutputFile(currentSegmentPfd.getFileDescriptor());
    //     } else {
    //         // Internal storage mode
    //         File tempOutputFileForInternal = createOutputFile();
    //         if (currentRecordingSafUri != null) {
    //             throw new IOException("setNextOutputFile: SAF/DocumentFile not supported in this mode");
    //         } else if (tempOutputFileForInternal != null) {
    //         Log.d(TAG, "Configuring MediaRecorder output for Internal temp file: " + tempOutputFileForInternal.getAbsolutePath());
    //         mediaRecorder.setOutputFile(tempOutputFileForInternal.getAbsolutePath());
    //     } else {
    //         throw new IOException("createOutputFile() did not configure any valid output (neither SAF URI nor Internal temp file).");
    //     }
    //     }
    //     VideoCodec codec = sharedPreferencesManager.getVideoCodec();
    //     mediaRecorder.setVideoEncoder(codec.getEncoder());
    //     Size resolution = sharedPreferencesManager.getCameraResolution();
    //     boolean isLandscape = sharedPreferencesManager.isOrientationLandscape();
    //     Log.d(TAG, "Setting video size directly from resolution: " + resolution.getWidth() + "x" + resolution.getHeight() + " for isLandscape=" + isLandscape);
    //     mediaRecorder.setVideoSize(resolution.getWidth(), resolution.getHeight());
        
    //     // ----- Fix Start for this method(setupMediaRecorder)-----
    //     // Determine if this is a front camera
    //     CameraType cameraType = sharedPreferencesManager.getCameraSelection();
    //     boolean isFrontCamera = (cameraType == CameraType.FRONT);
        
    //     // Set orientation hint based on camera type and device orientation
    //     if (isFrontCamera) {
    //         // Front camera needs different orientation hint to avoid upside-down recording
    //     if (!isLandscape) {
    //             mediaRecorder.setOrientationHint(270); // 270 degrees for portrait front camera
    //     } else {
    //             mediaRecorder.setOrientationHint(180); // 180 degrees for landscape front camera
    //     }
    //         Log.d(TAG, "Front camera: Setting orientation hint to " + (isLandscape ? "180" : "270") + " degrees");
    //     } else {
    //         // Back camera uses normal orientation
    //         if (!isLandscape) {
    //             mediaRecorder.setOrientationHint(90); // 90 degrees for portrait back camera
    //         } else {
    //             mediaRecorder.setOrientationHint(0); // 0 degrees for landscape back camera
    //         }
    //         Log.d(TAG, "Back camera: Setting orientation hint to " + (isLandscape ? "0" : "90") + " degrees");
    //     }
    //     // ----- Fix End for this method(setupMediaRecorder)-----
        
    //     int bitRate = getVideoBitrate();
    //     int frameRate = sharedPreferencesManager.getVideoFrameRate();
    //     mediaRecorder.setVideoEncodingBitRate(bitRate);
    //     mediaRecorder.setVideoFrameRate(frameRate);
    //     if (sharedPreferencesManager.isRecordAudioEnabled()) {
    //         int audioBitrate = sharedPreferencesManager.getAudioBitrate();
    //         int audioSamplingRate = sharedPreferencesManager.getAudioSamplingRate();
    //         mediaRecorder.setAudioEncodingBitRate(audioBitrate);
    //         mediaRecorder.setAudioSamplingRate(audioSamplingRate);
    //         mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
    //     }
        
    //     // ----- Fix Start for this method(setupMediaRecorder_location_embedding) -----
    //     // Add location metadata to video if enabled
    //     if (sharedPreferencesManager.isLocationEmbeddingEnabled()) {
    //         boolean locationApplied = false;
            
    //         // First attempt: Use existing geotagHelper if available
    //         if (geotagHelper != null) {
    //             boolean success = geotagHelper.applyLocationToRecorder(mediaRecorder);
    //             if (success) {
    //                 Log.d(TAG, "GeotagHelper: Successfully applied location to MediaRecorder");
    //                 locationApplied = true;
    //             } else {
    //                 Log.w(TAG, "GeotagHelper: Failed to apply location to MediaRecorder on first attempt");
    //             }
    //         } else {
    //             Log.d(TAG, "GeotagHelper is null, will create new instance");
    //         }
            
    //         // Second attempt: If first attempt failed or geotagHelper was null, create and try again
    //         if (!locationApplied) {
    //             try {
    //                 // Force creation of a new GeotagHelper
    //                 Log.d(TAG, "GeotagHelper: Creating new instance and starting updates");
    //                 geotagHelper = new GeotagHelper(this);
    //                 boolean started = geotagHelper.startUpdates();
    //                 Log.d(TAG, "GeotagHelper: Updates started: " + started);
                    
    //                 // Try to apply location immediately
    //                 boolean success = geotagHelper.applyLocationToRecorder(mediaRecorder);
    //                 if (success) {
    //                     Log.d(TAG, "GeotagHelper: Successfully applied location on second attempt");
    //                     locationApplied = true;
    //                 } else {
    //                     Log.w(TAG, "GeotagHelper: Failed to apply location on second attempt");
    //                 }
    //             } catch (Exception e) {
    //                 Log.e(TAG, "GeotagHelper: Error during initialization", e);
    //             }
    //         }
            
    //         // Third attempt: Background thread with delay if previous attempts failed
    //         if (!locationApplied) {
    //             Log.d(TAG, "GeotagHelper: Scheduling delayed attempt to apply location");
    //             new Thread(() -> {
    //                 try {
    //                     // Give more time for location to become available
    //                     Thread.sleep(2000);
    //                     if (mediaRecorder != null && geotagHelper != null) {
    //                         boolean retrySuccess = geotagHelper.applyLocationToRecorder(mediaRecorder);
    //                         Log.d(TAG, "GeotagHelper: Delayed retry applying location was " + 
    //                             (retrySuccess ? "successful" : "unsuccessful"));
    //                     } else {
    //                         Log.w(TAG, "GeotagHelper: Delayed retry aborted - resources no longer available");
    //                     }
    //                 } catch (Exception e) {
    //                     Log.e(TAG, "Error during delayed location retry", e);
    //                 }
    //             }).start();
    //         }
            
    //         Log.d(TAG, "GeotagHelper: Location embedding setup completed, immediate success: " + locationApplied);
    //     } else {
    //         Log.d(TAG, "GeotagHelper: Location embedding disabled in preferences");
    //     }
    //     // ----- Fix Ended for this method(setupMediaRecorder_location_embedding) -----
        
    //     if (isVideoSplittingEnabled && videoSplitSizeBytes > 0) {
    //         Log.d(TAG, "Setting MediaRecorder max file size to: " + videoSplitSizeBytes + " bytes for segment " + currentSegmentNumber);
    //         mediaRecorder.setMaxFileSize(videoSplitSizeBytes);
    //     } else {
    //         mediaRecorder.setMaxFileSize(0);
    //         Log.d(TAG, "Video splitting not enabled or size invalid, setting max file size to 0 (no limit).");
    //     }
    //     try {
    //         mediaRecorder.prepare();
    //         Log.d(TAG, "MediaRecorder prepared successfully for segment " + currentSegmentNumber);
    //     } catch (IOException e) {
    //         Log.e(TAG, "IOException preparing MediaRecorder for segment " + currentSegmentNumber, e);
    //         releaseMediaRecorderSafely();
    //         closeCurrentPfd();
    //         if (currentRecordingSafUri != null && currentRecordingDocFile != null && currentRecordingDocFile.exists()) {
    //             currentRecordingDocFile.delete();
    //         } else if (currentInternalTempFile != null && currentInternalTempFile.exists()) {
    //             currentInternalTempFile.delete();
    //         }
    //         throw e;
    //     }
    // }
    // ----- Fix Ended for this method(setupMediaRecorder_saf_pfd_support)-----

// Replace the ENTIRE existing openCamera method in RecordingService.java with this corrected version:

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
            Log.d(TAG, "Camera " + camera.getId() + " opened.");
            cameraDevice = camera;
            isCameraOpen = true;
            // Only start recording here, not createCameraPreviewSession
            if (pendingStartRecording) {
                pendingStartRecording = false;
                startRecording();
            }
        }
        @Override
        public void onDisconnected(@NonNull CameraDevice camera) {
            if (isStopping) return;
            Log.w(TAG, "Camera " + camera.getId() + " disconnected.");
            // ----- Fix Start for this method(onDisconnected_handle_rollover_flag) -----
            // isRolloverInProgressJustClosedDevice assignment removed as the flag is obsolete.
            // ----- Fix Ended for this method(onDisconnected_handle_rollover_flag) -----
            releaseRecordingResources(); // Cleanup everything if camera disconnects
            // ----- Fix Start for this method(onDisconnected)-----
            if (recordingState == RecordingState.STARTING) {
                recordingState = RecordingState.NONE;
            }
            // ----- Fix Ended for this method(onDisconnected)-----
            // Possibly notify UI or attempt restart? For now, just stop.
            stopSelf();
        }
        @Override
        public void onError(@NonNull CameraDevice camera, int error) {
            if (isStopping) return;
            Log.e(TAG, "Camera " + camera.getId() + " error: " + error);
            // ----- Fix Start for this method(onError_handle_rollover_flag) -----
            // isRolloverInProgressJustClosedDevice assignment removed as the flag is obsolete.
            // ----- Fix Ended for this method(onError_handle_rollover_flag) -----
            releaseRecordingResources(); // Cleanup on error
            // ----- Fix Start for this method(onError)-----
            if (recordingState == RecordingState.STARTING) {
                recordingState = RecordingState.NONE;
            }
            // ----- Fix Ended for this method(onError)-----
            Toast.makeText(RecordingService.this,"Camera error: "+error, Toast.LENGTH_LONG).show();
            stopSelf();
        }
        @Override
        public void onClosed(@NonNull CameraDevice camera) {
            if (isStopping) return;
            // ----- Fix Start for this method(onClosed_handle_rollover_callback) -----
            String closedCameraId = camera.getId();
            // Restore the logging of isRolloverClosingOldSession as the field will now be declared
            Log.d(TAG,"CameraDevice.onClosed for " + closedCameraId + ". Current recordingState: " + recordingState + ", isRolloverClosingOldSession: " + isRolloverClosingOldSession);

            if (RecordingService.this.cameraDevice == camera) {
                RecordingService.this.cameraDevice = null;
                Log.d(TAG, "RecordingService.cameraDevice field nulled for " + closedCameraId);
            } else if (RecordingService.this.cameraDevice != null) {
                Log.w(TAG, "CameraDevice.onClosed received for camera " + closedCameraId +
                           " but RecordingService.this.cameraDevice is " + RecordingService.this.cameraDevice.getId() +
                           ". Not nulling the field based on this specific event.");
                } else {
                 Log.d(TAG, "CameraDevice.onClosed received for camera " + closedCameraId +
                           " but RecordingService.this.cameraDevice was already null.");
            }

            if (isRolloverClosingOldSession) {
                 Log.w(TAG, "Camera " + closedCameraId + " closed, but isRolloverClosingOldSession was true. " +
                            "This is unexpected for the keep-open-rollover strategy. Attempting full stop.");
                 isRolloverClosingOldSession = false; 
                 new Handler(Looper.getMainLooper()).post(() -> {
                    Toast.makeText(RecordingService.this, "Camera unexpectedly closed during segment change. Stopping.", Toast.LENGTH_LONG).show();
                    stopRecording();
                 });
            } else if (recordingState != RecordingState.NONE) {
                Log.w(TAG, "Camera " + closedCameraId + " closed. RecordingState is " + recordingState +
                           " (not NONE) and not in active session rollover. This might be an unexpected closure or " +
                           "part of a stop sequence. Initiating stopRecording sequence to ensure cleanup.");
                new Handler(Looper.getMainLooper()).post(() -> {
                    // Avoid double toasts if another part of the system (e.g. onError) already showed one.
                    // Toast.makeText(RecordingService.this, "Camera " + closedCameraId + " disconnected. Stopping recording.", Toast.LENGTH_LONG).show();
                    stopRecording();
                });
            } else {
                Log.d(TAG, "Camera " + closedCameraId + " closed. RecordingState is NONE and not in session rollover. Normal closure observed.");
            }
            // ----- Fix Ended for this method(onClosed_handle_rollover_callback) -----
        }
    }; // End of cameraStateCallback


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
                
                // Add recorder surface
//                if (mediaRecorder != null) {
//                    Surface recorderSurface = mediaRecorder.getSurface();
//                    if (recorderSurface != null) {
//                        surfaces.add(recorderSurface);
//                    }
//                }
                
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

    // --- Watermarking & Processing ---
    // ----- Fix Start for this method(processLatestVideoFileWithWatermark_comment_out_isProcessingWatermark_references)-----
    // FIXME: This method (processLatestVideoFileWithWatermark) appears to be DEPRECATED/UNUSED.
    // It still references the old 'isProcessingWatermark' flag instead of the new 'ffmpegProcessingTaskCount'.
    // The primary video processing path for segments is now handleSegmentRollover -> processAndMoveVideo.
    // This method should be reviewed and likely REMOVED or REFACTORED if still needed for a different purpose.
//    private void processLatestVideoFileWithWatermark() {
//        Log.d(TAG,"processLatestVideoFileWithWatermark starting...");
//        String inputUriOrPath;
//        String outputUriOrPath;
//        String tempFileName; // Needed for constructing output name
//        Uri inputUri = null;
//        File inputFile = null;
//        Uri outputDirectoryUri = null; // Only for SAF output directory
//
//        // isProcessingWatermark = true; // OLD FLAG - DO NOT USE - Handled by ffmpegProcessingTaskCount in other methods
//
//
//    // ----- Fix Ended for this method(processLatestVideoFileWithWatermark_comment_out_isProcessingWatermark_references)-----
//
//
//        // Construct final output filename (always prefixed)
//        String finalOutputName = tempFileName.replace("temp_", Constants.RECORDING_DIRECTORY + "_");
//
//        // Determine and Create Output Target (Path or SAF URI)
//        DocumentFile finalOutputDocFile = null; // For SAF cleanup on failure
//
//        if (outputDirectoryUri != null) { // Custom SAF Location
//            DocumentFile parentDir = DocumentFile.fromTreeUri(this, outputDirectoryUri);
//            if (parentDir == null || !parentDir.canWrite()) {
//                handleProcessingError("Cannot write to SAF output directory: " + outputDirectoryUri, inputUriOrPath);
//                return;
//            }
//            // Check if final file already exists (shouldn't normally, but handle anyway)
//            if(parentDir.findFile(finalOutputName) != null){
//                Log.w(TAG, "Final output file already exists in SAF dir: "+finalOutputName+". Overwriting might fail or take time.");
//                // FFmpeg might handle overwrite depending on version/options
//            }
//
//            finalOutputDocFile = parentDir.createFile("video/" + Constants.RECORDING_FILE_EXTENSION, finalOutputName);
//            if (finalOutputDocFile == null || !finalOutputDocFile.exists()) {
//                handleProcessingError("Failed to create output SAF file: " + finalOutputName + " in " + outputDirectoryUri, inputUriOrPath);
//                return;
//            }
//            outputUriOrPath = finalOutputDocFile.getUri().toString();
//            Log.d(TAG,"Output target: SAF URI = "+ outputUriOrPath);
//
//        } else { // Internal Storage Location
//            File videoDir = new File(getExternalFilesDir(null), Constants.RECORDING_DIRECTORY);
//            File outputFile = new File(videoDir, finalOutputName);
//            outputUriOrPath = outputFile.getAbsolutePath();
//            Log.d(TAG,"Output target: Internal Path = " + outputUriOrPath);
//        }
//
//        // Prepare and Execute FFmpeg Command
//        String watermarkOption = sharedPreferencesManager.getWatermarkOption();
//        String ffmpegCommand;
//
//        if ("no_watermark".equals(watermarkOption)) {
//            Log.d(TAG,"No watermark selected, using copy codec.");
//            // Use -movflags +faststart potentially?
//            ffmpegCommand = String.format("-i %s -codec copy -y %s", inputUriOrPath, outputUriOrPath); // -y overwrites
//        } else {
//            Log.d(TAG,"Applying watermark: " + watermarkOption);
//            // Fetch watermark text and format it
//            String fontPath = getFilesDir().getAbsolutePath() + "/ubuntu_regular.ttf"; // Ensure font exists
//            String watermarkText;
//            boolean isLocationEnabled = sharedPreferencesManager.isLocalisationEnabled();
//            String locationText = isLocationEnabled ? getLocationData() : "";
//            switch (watermarkOption) {
//                case "timestamp": watermarkText = getCurrentTimestamp() + locationText; break;
//                default: watermarkText = "Captured by FadCam - " + getCurrentTimestamp() + locationText; break;
//            }
//            watermarkText = convertArabicNumeralsToEnglish(watermarkText);
//            String escapedWatermarkText = escapeFFmpegString(watermarkText);
//            String escapedFontPath = escapeFFmpegPath(fontPath);
//
//            int fontSize = getFontSizeBasedOnBitrate();
//            String fontSizeStr = convertArabicNumeralsToEnglish(String.valueOf(fontSize));
//            int frameRates = sharedPreferencesManager.getVideoFrameRate();
//            int bitratesEstimated = getVideoBitrate();
//
//            // ----- Fix Start for this method(processLatestVideoFileWithWatermark_codec_fix)-----
//            // Retrieve the codec string for ffmpeg from the selected VideoCodec
//            VideoCodec videoCodec = sharedPreferencesManager.getVideoCodec();
//            String ffmpegCodec = videoCodec.getFfmpeg();
//            // Build FFmpeg command carefully
//            ffmpegCommand = String.format(
//                    Locale.US, // Use US Locale for number formatting consistency
//                    "-i %s -r %d -vf \"drawtext=text='%s':x=10:y=10:fontsize=%s:fontcolor=white:fontfile='%s':escape_text=0\" -q:v 0 -codec:v %s -b:v %d -codec:a copy -y %s",
//                    inputUriOrPath,
//                    frameRates,
//                    escapedWatermarkText,
//                    fontSizeStr,
//                    escapedFontPath,
//                    ffmpegCodec,
//                    bitratesEstimated,
//                    outputUriOrPath
//            );
//            // ----- Fix Ended for this method(processLatestVideoFileWithWatermark_codec_fix)-----
//        }
//
//        executeFFmpegCommand(ffmpegCommand, inputUriOrPath, outputUriOrPath); // Pass both input and potential failed SAF output path
//    }

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

        // ----- Fix Start for this method(handleProcessingError_decrement_ffmpeg_counter)-----
        // isProcessingWatermark = false; // Reset the processing flag IMPORTANTLY
        // This error handler is called if FFmpeg command build fails OR if executeFFmpegAndMoveToSAF/InternalOnly
        // itself has an issue *before* FFmpegKit.executeAsync is successfully launched.
        // If ffmpegProcessingTaskCount was incremented in processAndMoveVideo, it needs to be decremented here
        // if the async task won't run to decrement it.
        // This is tricky, as this method might be called from places that didn't increment.
        // A safer approach is for the caller (e.g., processAndMoveVideo) to handle decrementing
        // if it calls handleProcessingError *after* incrementing but *before* executeFFmpegAsync.
        // For now, we assume that if this is called, an async task that would decrement won't run.
        // However, the increment happens in processAndMoveVideo. If that method calls this, then returns,
        // the decrement in executeFFmpegAsync's callback won't happen for that task.
        // Let's ensure processAndMoveVideo decrements if it calls handleProcessingError *after* incrementing.
        // For now, to avoid double decrement, do not decrement here directly.
        // The primary decrement point is in executeFFmpegAsync's callback.
        // If processAndMoveVideo increments then fails *before* executeFFmpegAsync, it should decrement.

        // If processAndMoveVideo calls this method and returns, the task count might be off.
        // The increment is at the start of processAndMoveVideo.
        // If it errors out and calls handleProcessingError, and then returns, the count is too high.
        // So, processAndMoveVideo should indeed decrement if it errors out after incrementing.

        // The responsibility for decrementing on PRE-FFMPEG failure lies with the method that INCREMENTED.
        // processAndMoveVideo increments. If it fails before executeFFmpegAsync is called, it should decrement.
        // The executeFFmpegAsync callback handles decrementing for actual FFmpeg execution completion/failure.
        // Let's assume processAndMoveVideo will be modified to handle this.
        // No direct decrement here to avoid race conditions or double decrements.
        // ----- Fix Start for this method(handleProcessingError_remove_direct_decrement)-----
        // The caller (processAndMoveVideo) is now responsible for decrementing the counter 
        // if it calls handleProcessingError after incrementing but before FFmpegKit.executeAsync is launched.
        // So, no decrement here.
        // ----- Fix Ended for this method(handleProcessingError_remove_direct_decrement)-----
        // ----- Fix Ended for this method(handleProcessingError_decrement_ffmpeg_counter)-----

        // If an input path/URI was provided, log attempt to clean it
        if (internalTempInputPath != null) {
            Log.d(TAG, "handleProcessingError: Original temp file to keep (maybe): " + internalTempInputPath);
            // We generally DO NOT delete the input temp file on processing errors,
            // as it might be the only copy left. We just log it.
            // CleanupTemporaryFile() call will be skipped if processing failed.
        } else {
            Log.w(TAG, "handleProcessingError called without specific input path reference.");
        }

        // Reset the global tracking variable since the process tied to it failed
//        currentInternalTempFile = null;
        // SAF/PFD related vars aren't directly used here anymore in error case
        // closeCurrentPfd(); // Method removed

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

    // ----- Fix Start for this method(executeFFmpegCommand_full_rewrite)-----
    private void executeFFmpegCommand(String ffmpegCommand, final String inputUriOrPath, final String outputUriOrPath) {
        Log.d(TAG, "Executing FFmpeg: " + ffmpegCommand);
        // This method appears to be part of an older/alternative processing flow.
        // It is NOT the primary path for segment processing, which uses executeFFmpegAsync.
        // If this method were to be activated and manage an FFmpeg task,
        // it would need to increment ffmpegProcessingTaskCount before calling FFmpegKit.executeAsync,
        // and decrement it in the callback, similar to how executeFFmpegAsync works.

        // Example structure if it were to manage a task:
        // ffmpegProcessingTaskCount.incrementAndGet();
        // Log.d(TAG, "FFmpeg task count incremented via executeFFmpegCommand for: " + inputUriOrPath);

//        FFmpegKit.executeAsync(ffmpegCommand, session -> {
//            boolean success = ReturnCode.isSuccess(session.getReturnCode());
//            Log.d(TAG, "FFmpeg session (via executeFFmpegCommand) finished - Success: " + success + ", RC: " + session.getReturnCode());
//
//            if (success) {
//                Log.d(TAG, "FFmpeg process successful (via executeFFmpegCommand). Output: " + outputUriOrPath);
//                cleanupTemporaryFile(); // Delete the temp file (inputUriOrPath)
//
//                // Optional: Update records UI / Media Scanner if needed
//            } else {
//                Log.e(TAG, "FFmpeg (via executeFFmpegCommand) failed! Logs:");
//                Log.e(TAG, session.getAllLogsAsString());
//                Log.e(TAG, "Command was: "+ffmpegCommand);
//                Toast.makeText(this, "Error processing video (via executeFFmpegCommand)", Toast.LENGTH_LONG).show();
//
//                // Try to cleanup the FAILED output file if it was created via SAF
//                if (outputUriOrPath != null && outputUriOrPath.startsWith("content://")) {
//                    try {
//                        DocumentFile failedOutput = DocumentFile.fromSingleUri(this, Uri.parse(outputUriOrPath));
//                        if(failedOutput != null && failedOutput.exists() && failedOutput.delete()){
//                            Log.d(TAG,"Deleted failed SAF output file (via executeFFmpegCommand): "+ outputUriOrPath);
//                        } else {
//                            Log.w(TAG, "Could not delete failed SAF output file (via executeFFmpegCommand): "+outputUriOrPath);
//                        }
//                    } catch (Exception e) {
//                        // ----- Fix Start for this method(executeFFmpegCommand_fix_string_literal)-----
//                        Log.e(TAG,"Error deleting failed SAF output (via executeFFmpegCommand)", e);
//                        // ----- Fix Ended for this method(executeFFmpegCommand_fix_string_literal)-----
//                    }
//                }
//                // IMPORTANT: DO NOT delete the input temp file if processing failed.
//                currentRecordingFile = null;
//                currentRecordingSafUri = null;
//                currentRecordingDocFile = null;
//            }
//
//            // If this method (executeFFmpegCommand) were managing an FFmpeg task lifecycle:
//            // int tasksLeft = ffmpegProcessingTaskCount.decrementAndGet();
//            // Log.d(TAG, "FFmpeg task (via executeFFmpegCommand) finished. Count decremented to: " + tasksLeft);
//
//            // Check if service can stop now
//            if (!isWorkingInProgress()) {
//                Log.d(TAG,"FFmpeg (via executeFFmpegCommand) finished and no other work pending, stopping service.");
//                stopSelf();
//            } else {
//                Log.d(TAG,"FFmpeg (via executeFFmpegCommand) finished, but service might still be recording/working.");
//            }
//        });
    }
    // ----- Fix Ended for this method(executeFFmpegCommand_full_rewrite)-----


    // Centralized Temp File Cleanup
//    private void cleanupTemporaryFile() {
//        Log.d(TAG,"cleanupTemporaryFile: Attempting cleanup of temporary recording file...");
////        File tempToClean = currentInternalTempFile; // Use the instance variable
//
//        if (tempToClean != null) {
//            Log.d(TAG, "Cleaning up internal file: " + tempToClean.getAbsolutePath());
//            if (tempToClean.exists()) {
//                if (tempToClean.delete()) {
//                    Log.d(TAG, "Deleted internal temp file: " + tempToClean.getName());
//                } else {
//                    Log.w(TAG, "Failed to delete internal temp file: " + tempToClean.getName());
//                }
//            } else {
//                Log.w(TAG, "Internal temp file reference exists but file not found for cleanup.");
//            }
//            currentInternalTempFile = null; // Clear reference after attempt
//        } else {
//            Log.d(TAG,"No temporary file currently tracked for cleanup.");
//        }
//        // Removed logic for cleaning SAF temps as they are handled differently
//    }

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
        if(intent != null) {
            previewSurface = intent.getParcelableExtra("SURFACE");
            Log.d(TAG, "Preview surface updated: " + (previewSurface != null && previewSurface.isValid()));
        }
    }

//    private void closeCurrentPfd() {
//        if (currentSegmentPfd != null) {
//            try { currentSegmentPfd.close(); } catch (Exception e) { Log.w(TAG, "Error closing currentSegmentPfd", e); }
//            currentSegmentPfd = null;
//        }
//        if (nextSegmentPfd != null) {
//            try { nextSegmentPfd.close(); } catch (Exception e) { Log.w(TAG, "Error closing nextSegmentPfd", e); }
//            nextSegmentPfd = null;
//        }
//    }


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
        SimpleDateFormat sdf = new SimpleDateFormat("dd/MMM/yyyy HH:mm:ss", Locale.ENGLISH); // Include seconds
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
            CharSequence name = getString(R.string.app_name) + " Recording";
            String description = "Notifications for FadCam recording service";
            int importance = NotificationManager.IMPORTANCE_LOW; // Low importance to be less intrusive
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, name, importance);
            channel.setDescription(description);
            channel.setSound(null, null); // No sound
            channel.enableVibration(false); // No vibration

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

        NotificationCompat.Builder builder = createBaseNotificationBuilder()
                .setContentText(getString(R.string.notification_video_recording_progress_description))
                // Use STOP action
                .clearActions() // Remove previous actions
                .addAction(new NotificationCompat.Action(
                        R.drawable.ic_stop,
                        getString(R.string.button_stop),
                        createStopRecordingIntent()));

        startForeground(NOTIFICATION_ID, builder.build());
        Log.d(TAG, "Foreground notification updated for IN_PROGRESS.");
    }


    private void setupRecordingResumeNotification() { // Notification shown when PAUSED
        if (ActivityCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG,"POST_NOTIFICATIONS permission not granted, skipping notification update.");
            return; // Don't crash if user denied permission after start
        }

        NotificationCompat.Builder builder = createBaseNotificationBuilder()
                .setContentText(getString(R.string.notification_video_recording_paused_description))
                // Use RESUME action
                .clearActions() // Remove previous actions
                .addAction(new NotificationCompat.Action(
                        R.drawable.ic_play, // Use Play icon for Resume action
                        getString(R.string.button_resume),
                        createResumeRecordingIntent()))
                .addAction(new NotificationCompat.Action( // Keep STOP action available
                        R.drawable.ic_stop,
                        getString(R.string.button_stop),
                        createStopRecordingIntent()));


        NotificationManagerCompat notificationManager = NotificationManagerCompat.from(this);
        notificationManager.notify(NOTIFICATION_ID, builder.build()); // Just update existing notification
        Log.d(TAG, "Foreground notification updated for PAUSED.");
    }


    private NotificationCompat.Builder createBaseNotificationBuilder() {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.notification_video_recording))
                .setSmallIcon(R.drawable.ic_notification_icon) // Replace with actual suitable small icon
                .setContentIntent(createOpenAppIntent()) // Tap notification -> open app
                .setOngoing(true) // Makes it non-dismissible
                .setSilent(true) // Suppress sound/vibration defaults
                .setPriority(NotificationCompat.PRIORITY_LOW);
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

    // --- End Notifications ---

    // --- Toast Helpers ---
    private void showRecordingResumedToast() {
        Utils.showQuickToast(this, R.string.video_recording_resumed);
    }
    private void showRecordingInPausedToast() {
        Utils.showQuickToast(this, R.string.video_recording_paused);
    }
    // --- End Toast Helpers ---


    // --- Torch Logic ---
    // ----- Fix Start for this class (RecordingService) -----
    // Re-introduce toggleRecordingTorch method
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
    // ----- Fix Ended for this class (RecordingService) -----

    // --- Status Check ---
    public boolean isRecording() {
        return recordingState == RecordingState.IN_PROGRESS;
    }

    public boolean isPaused() {
        return recordingState == RecordingState.PAUSED;
    }

    // Combined status check
    public boolean isWorkingInProgress() {
        // ----- Fix Start for this method(isWorkingInProgress)-----
        // Only consider recording states (not FFmpeg processing) for determining if camera is busy
        // This allows new recordings to start while FFmpeg is still processing in the background
        return recordingState == RecordingState.IN_PROGRESS || 
               recordingState == RecordingState.PAUSED || 
               recordingState == RecordingState.STARTING;
        // ----- Fix Ended for this method(isWorkingInProgress)-----
    }
    
    /**
     * Check if the service should stay alive (either recording is in progress or FFmpeg is processing)
     * This is different from isWorkingInProgress() which only checks if recording is active
     */
    private boolean shouldServiceStayAlive() {
        return ffmpegProcessingTaskCount.get() > 0 || 
               recordingState == RecordingState.IN_PROGRESS || 
               recordingState == RecordingState.PAUSED || 
               recordingState == RecordingState.STARTING;
    }
    // --- End Status Check ---

    // ----- Fix Start for this class (RecordingService_video_splitting_listener_and_rollover) -----
//    private final MediaRecorder.OnInfoListener mediaRecorderInfoListener = new MediaRecorder.OnInfoListener() {
//        @Override
//        public void onInfo(MediaRecorder mr, int what, int extra) {
//            Log.d(TAG, "[OnInfoListener] what=" + what + ", extra=" + extra);
//            try {
//                if (what == 802 /* MEDIA_RECORDER_INFO_MAX_FILESIZE_APPROACHING */) {
//                    Log.i(TAG, "[OnInfoListener] Max filesize approaching! Preparing next output file for seamless rollover.");
//                    if (isVideoSplittingEnabled && videoSplitSizeBytes > 0 && !nextOutputFilePending) {
//                        nextSegmentTempFile = createNextSegmentOutputFile();
//                        if (SharedPreferencesManager.STORAGE_MODE_CUSTOM.equals(sharedPreferencesManager.getStorageMode())) {
//                            // SAF mode: nextSegmentPfd is set in createNextSegmentOutputFile
//                            if (nextSegmentPfd != null) {
//                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
//                                    mediaRecorder.setNextOutputFile(nextSegmentPfd.getFileDescriptor());
//                                    nextOutputFilePending = true;
//                                    Log.d(TAG, "[OnInfoListener] setNextOutputFile (APPROACHING) called for SAF PFD.");
//                                }
//                } else {
//                                Log.e(TAG, "[OnInfoListener] Failed to open nextSegmentPfd for SAF. Stopping recording.");
//                                new Handler(Looper.getMainLooper()).post(() -> stopRecording());
//                            }
//                        } else {
//                            // Internal storage mode
//                            if (nextSegmentTempFile != null) {
//                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
//                                    mediaRecorder.setNextOutputFile(nextSegmentTempFile);
//                                    nextOutputFilePending = true;
//                                    Log.d(TAG, "[OnInfoListener] setNextOutputFile (APPROACHING) called for internal file.");
//                                }
//                            } else {
//                                Log.e(TAG, "[OnInfoListener] Failed to create next segment file for rollover (APPROACHING). Stopping recording.");
//                                new Handler(Looper.getMainLooper()).post(() -> stopRecording());
//                            }
//                        }
//                    }
//                } else if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED) {
//                    Log.i(TAG, "[OnInfoListener] Max filesize reached! Attempting setNextOutputFile rollover.");
//                    if (isVideoSplittingEnabled && videoSplitSizeBytes > 0 && !nextOutputFilePending) {
//                        nextSegmentTempFile = createNextSegmentOutputFile();
//                        if (SharedPreferencesManager.STORAGE_MODE_CUSTOM.equals(sharedPreferencesManager.getStorageMode())) {
//                            if (nextSegmentPfd != null) {
//                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
//                                    mediaRecorder.setNextOutputFile(nextSegmentPfd.getFileDescriptor());
//                                    nextOutputFilePending = true;
//                                    Log.d(TAG, "[OnInfoListener] setNextOutputFile (REACHED) called for SAF PFD.");
//                                }
//                            } else {
//                                Log.e(TAG, "[OnInfoListener] Failed to open nextSegmentPfd for SAF. Stopping recording.");
//                                new Handler(Looper.getMainLooper()).post(() -> stopRecording());
//                            }
//                        } else {
//                            if (nextSegmentTempFile != null) {
//                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
//                                    mediaRecorder.setNextOutputFile(nextSegmentTempFile);
//                                    nextOutputFilePending = true;
//                                    Log.d(TAG, "[OnInfoListener] setNextOutputFile (REACHED) called for internal file.");
//                                }
//                            } else {
//                                Log.e(TAG, "[OnInfoListener] Failed to create next segment file for rollover (REACHED). Stopping recording.");
//                                new Handler(Looper.getMainLooper()).post(() -> stopRecording());
//                            }
//                        }
//                    } else if (!isVideoSplittingEnabled || videoSplitSizeBytes <= 0) {
//                        Log.w(TAG, "[OnInfoListener] Max filesize reached, but splitting not enabled/configured. Stopping recording.");
//                        new Handler(Looper.getMainLooper()).post(() -> stopRecording());
//                    }
//                } else if (what == 803 /* MEDIA_RECORDER_INFO_NEXT_OUTPUT_FILE_STARTED */) {
//                    Log.i(TAG, "[OnInfoListener] Next output file started. Advancing segment number and updating file tracking.");
//                    // SAF: Close previous segment PFD
//                    if (SharedPreferencesManager.STORAGE_MODE_CUSTOM.equals(sharedPreferencesManager.getStorageMode())) {
//                        if (currentSegmentPfd != null) {
//                            try { currentSegmentPfd.close(); } catch (Exception e) { Log.w(TAG, "Error closing previous segment PFD", e); }
//                            currentSegmentPfd = nextSegmentPfd;
//                            nextSegmentPfd = null;
//                        }
//                        // Queue the completed SAF segment for FFmpeg processing
//                        if (currentRecordingDocFile != null && currentRecordingDocFile.exists() && currentRecordingDocFile.length() > 0) {
//                            Log.d(TAG, "[OnInfoListener] Queuing completed SAF segment for FFmpeg: " + currentRecordingDocFile.getUri());
//                            segmentProcessingQueue.add(currentRecordingDocFile);
//                            processNextSegmentInQueue();
//                        } else {
//                            Log.w(TAG, "[OnInfoListener] Previous SAF segment missing or empty, skipping FFmpeg queue.");
//                        }
//                        // ----- Fix Start for this method(OnInfoListener_update_current_docfile_on_rollover) -----
//                        // Update currentRecordingDocFile and currentRecordingSafUri to the new segment
//                        currentRecordingDocFile = nextRecordingDocFile;
//                        currentRecordingSafUri = (currentRecordingDocFile != null) ? currentRecordingDocFile.getUri() : null;
//                        nextRecordingDocFile = null;
//                        // ----- Fix Ended for this method(OnInfoListener_update_current_docfile_on_rollover) -----
//                    } else {
//                        // Internal storage mode
//                        if (currentInternalTempFile != null && currentInternalTempFile.exists() && currentInternalTempFile.length() > 0) {
//                            Log.d(TAG, "[OnInfoListener] Queuing completed segment for FFmpeg: " + currentInternalTempFile.getAbsolutePath());
//                            segmentProcessingQueue.add(currentInternalTempFile);
//                            processNextSegmentInQueue();
//                        } else {
//                            Log.w(TAG, "[OnInfoListener] Previous segment file missing or empty, skipping FFmpeg queue.");
//                        }
//                    }
//                    currentSegmentNumber++;
//                    currentInternalTempFile = nextSegmentTempFile;
//                    nextSegmentTempFile = null;
//                    nextOutputFilePending = false; // Now it's safe to queue another next output file
//                    Intent segmentIntent = new Intent(Constants.ACTION_RECORDING_SEGMENT_COMPLETE);
//                    segmentIntent.putExtra(Constants.INTENT_EXTRA_FILE_URI, currentRecordingSafUri != null ? currentRecordingSafUri.toString() : (currentInternalTempFile != null ? Uri.fromFile(currentInternalTempFile).toString() : ""));
//                    sendBroadcast(segmentIntent);
//                    Log.d(TAG, "[OnInfoListener] Broadcasted segment complete for segment " + (currentSegmentNumber - 1));
//                } else {
//                    Log.d(TAG, "[OnInfoListener] Unhandled info event: what=" + what);
//                }
//            } catch (Exception e) {
//                Log.e(TAG, "[OnInfoListener] Exception in OnInfoListener", e);
//                new Handler(Looper.getMainLooper()).post(() -> stopRecording());
//            }
//        }
//    };

    private void handleSegmentRolloverInternal() {
        Log.i(TAG, "handleSegmentRolloverInternal: NO-OP with setNextOutputFile. All rollover handled in OnInfoListener.");
    }

    private void proceedWithRolloverAfterOldSessionClosed() {
        Log.i(TAG, "proceedWithRolloverAfterOldSessionClosed: NO-OP with setNextOutputFile. All rollover handled in OnInfoListener.");
    }

    // The entire block from "In CameraDevice.StateCallback:" (approx. line 2408)
    // down to "// --- End Torch Logic ---" (approx. line 2440) is removed as it contained
    // a misplaced onClosed method with an invalid @Override annotation.

    // ----- Fix Start for this method(RecordingService_add_copySafToCache_helper)-----
    /**
     * Copies the content of a SAF URI to a new temporary file in the app's external cache.
     * This is useful when FFmpeg processing needs a direct file path for an input that was originally a SAF URI.
     *
     * @param context The application context.
     * @param safUri The SAF URI of the file to copy.
     * @param originalFilename The desired filename for the temporary cached file (e.g., "temp_segment_001.mp4").
     * @return The File object of the created temporary cache file, or null if copying failed.
     */
    @Nullable
    private File copySafUriToTempCacheForProcessing(@NonNull Context context, @NonNull Uri safUri, @NonNull String originalFilename) {
        Log.d(TAG, "copySafUriToTempCacheForProcessing: Attempting to copy SAF URI " + safUri + " to cache with name " + originalFilename);
        File cacheDir = context.getExternalCacheDir();
        if (cacheDir == null) {
            Log.w(TAG, "copySafUriToTempCacheForProcessing: External cache dir null, using internal cache.");
            cacheDir = new File(context.getCacheDir(), "ffmpeg_saf_temp");
        } else {
            cacheDir = new File(cacheDir, "ffmpeg_saf_temp"); // Subdir in external cache
        }

        if (!cacheDir.exists() && !cacheDir.mkdirs()) {
            Log.e(TAG, "copySafUriToTempCacheForProcessing: Cannot create temp cache directory: " + cacheDir.getAbsolutePath());
            return null;
        }

        File tempCachedFile = new File(cacheDir, originalFilename);

        try (InputStream inputStream = context.getContentResolver().openInputStream(safUri);
             OutputStream outputStream = new FileOutputStream(tempCachedFile)) {

            if (inputStream == null) {
                Log.e(TAG, "copySafUriToTempCacheForProcessing: Failed to open InputStream for SAF URI: " + safUri);
                return null;
            }

            byte[] buffer = new byte[8192]; // 8KB buffer
            int bytesRead;
            long totalBytesCopied = 0;
            while ((bytesRead = inputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, bytesRead);
                totalBytesCopied += bytesRead;
            }
            Log.i(TAG, "copySafUriToTempCacheForProcessing: Successfully copied " + totalBytesCopied + " bytes from SAF URI to temp cache file: " + tempCachedFile.getAbsolutePath());
            return tempCachedFile;

        } catch (IOException e) {
            Log.e(TAG, "copySafUriToTempCacheForProcessing: IOException while copying SAF URI to temp cache", e);
            if (tempCachedFile.exists() && !tempCachedFile.delete()) {
                Log.w(TAG, "copySafUriToTempCacheForProcessing: Failed to delete partially copied temp file: " + tempCachedFile.getName());
            }
            return null;
        } catch (SecurityException se) {
            Log.e(TAG, "copySafUriToTempCacheForProcessing: SecurityException, likely no permission for SAF URI: " + safUri, se);
            return null;
        }
    }
    // ----- Fix Ended for this method(RecordingService_add_copySafToCache_helper)-----

    // ----- Fix Start for this method(handleSegmentRollover_release_camera_resources_for_rollover)-----
    // This is the old version of handleSegmentRollover that was modified.
    // The content has been refactored into the new handleSegmentRollover and continueRolloverAfterDeviceClosed.
    // This section can be considered as replaced by the new structure.
    // ----- Fix Ended for this method(handleSegmentRollover_release_camera_resources_for_rollover)-----

    // ----- Fix Start for this method(createOutputFile_segmented_naming) -----
    /**
     * Creates and returns the output file or configures the SAF URI for MediaRecorder.
     * Handles segmented naming based on currentSegmentNumber.
     * Updates currentRecordingFile or currentRecordingSafUri and currentRecordingDocFile.
     *
     * @return The File object for internal storage, or null if using SAF (currentRecordingSafUri will be set).
     *         Returns null on critical failure to create any output.
     */
//    private File createOutputFile() {
//        String storageMode = sharedPreferencesManager.getStorageMode();
//        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
//        // Format segment number with leading zeros (e.g., _001, _002)
//        String segmentSuffix = String.format(Locale.US, "_%03d", currentSegmentNumber);
//
//        String baseFilename = Constants.RECORDING_DIRECTORY + "_" + timestamp + segmentSuffix + "." + Constants.RECORDING_FILE_EXTENSION;
//        String tempBaseFilename = "temp_" + timestamp + segmentSuffix + "." + Constants.RECORDING_FILE_EXTENSION;
//
//        Log.d(TAG, "createOutputFile called for segment " + currentSegmentNumber + ". Base: " + baseFilename + ", Temp: " + tempBaseFilename);
//
//        // Reset previous PFD and URIs
//        closeCurrentPfd();
//        currentRecordingFile = null;
//        currentRecordingSafUri = null;
//        currentRecordingDocFile = null;
//        currentInternalTempFile = null; // Important to reset this too
//
//        if (SharedPreferencesManager.STORAGE_MODE_CUSTOM.equals(storageMode)) {
//            String customUriString = sharedPreferencesManager.getCustomStorageUri();
//            if (customUriString == null) {
//                handleStorageError("Custom storage selected but URI is null");
//                return null; // Critical error, cannot proceed with SAF
//            }
//            Uri treeUri = Uri.parse(customUriString);
//            DocumentFile pickedDir = DocumentFile.fromTreeUri(this, treeUri);
//            if (pickedDir == null || !pickedDir.canWrite()) {
//                handleStorageError("Cannot write to selected custom directory");
//                return null; // Critical error
//            }
//
//            // Use the TEMP filename for SAF creation as well initially
//            currentRecordingDocFile = pickedDir.createFile("video/" + Constants.RECORDING_FILE_EXTENSION, tempBaseFilename);
//            if (currentRecordingDocFile == null || !currentRecordingDocFile.exists()) {
//                Log.e(TAG, "Failed to create DocumentFile in SAF: " + tempBaseFilename);
//                handleStorageError("Failed to create file in custom directory");
//                return null; // Critical error
//            }
//            currentRecordingSafUri = currentRecordingDocFile.getUri();
//            Log.i(TAG, "Output configured for SAF: " + currentRecordingSafUri.toString());
//            // No internal temp file needed if directly writing to SAF PFD
//            return null; // Signify SAF is being used
//
//        } else { // Internal Storage
//            File videoDir = new File(getExternalFilesDir(null), Constants.RECORDING_DIRECTORY);
//            if (!videoDir.exists() && !videoDir.mkdirs()) {
//                Log.e(TAG, "Cannot create internal recording directory: " + videoDir.getAbsolutePath());
//                Toast.makeText(this, "Error creating internal storage directory", Toast.LENGTH_LONG).show();
//                return null; // Critical error
//            }
//            // For internal storage, we record to a temp file first, then process to final name.
//            // The temp file should also be in a cache location, not the final directory.
//            File cacheDir = getExternalCacheDir();
//            if (cacheDir == null) {
//                Log.w(TAG, "External cache dir null, using internal cache for temp file.");
//                cacheDir = new File(getCacheDir(), "recording_temp"); // Fallback to internal app cache
//            } else {
//                cacheDir = new File(cacheDir, "recording_temp"); // Subdir in external cache
//            }
//            if (!cacheDir.exists() && !cacheDir.mkdirs()) {
//                Log.e(TAG, "Cannot create temp cache directory: " + cacheDir.getAbsolutePath());
//                Toast.makeText(this, "Error creating temp cache directory", Toast.LENGTH_LONG).show();
//                return null; // Critical error
//            }
//
//            currentInternalTempFile = new File(cacheDir, tempBaseFilename);
//            Log.i(TAG, "Output (temp) configured for Internal: " + currentInternalTempFile.getAbsolutePath());
//            currentRecordingFile = new File(videoDir, baseFilename); // This is the *final* intended path after processing
//            return currentInternalTempFile; // Return the temp file to be written to by MediaRecorder
//        }
//    }
    // ----- Fix Ended for this method(createOutputFile_segmented_naming) -----

    // ----- Fix Start for this method(createNextSegmentOutputFile_saf_pfd_support)-----
    private File createNextSegmentOutputFile(int nextSegmentNumber) {
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
            String tempBaseFilename = "temp_" + timestamp + segmentSuffix + "." + Constants.RECORDING_FILE_EXTENSION;
            DocumentFile nextDocFile = pickedDir.createFile("video/" + Constants.RECORDING_FILE_EXTENSION, tempBaseFilename);
            if (nextDocFile == null || !nextDocFile.exists()) {
                Log.e(TAG, "createNextSegmentOutputFile: Failed to create DocumentFile in SAF: " + tempBaseFilename);
                return null;
            }
            try {
                // Open a ParcelFileDescriptor for the next segment (no longer tracked by nextSegmentPfd)
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
//            nextRecordingDocFile = nextDocFile;
            return null;
        } else {
            // Internal storage mode
            String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
            String segmentSuffix = String.format(Locale.US, "_%03d", nextSegmentNumber);
            String tempBaseFilename = "temp_" + timestamp + segmentSuffix + "." + Constants.RECORDING_FILE_EXTENSION;
            File cacheDir = getExternalCacheDir();
            if (cacheDir == null) {
                Log.w(TAG, "External cache dir null, using internal cache for temp file.");
                cacheDir = new File(getCacheDir(), "recording_temp");
            } else {
                cacheDir = new File(cacheDir, "recording_temp");
            }
            if (!cacheDir.exists() && !cacheDir.mkdirs()) {
                Log.e(TAG, "Cannot create temp cache directory: " + cacheDir.getAbsolutePath());
                Toast.makeText(this, "Error creating temp cache directory", Toast.LENGTH_LONG).show();
                return null;
            }
            File nextTempFile = new File(cacheDir, tempBaseFilename);
            Log.i(TAG, "Next segment temp file created: " + nextTempFile.getAbsolutePath());
            return nextTempFile;
        }
    }
    // ----- Fix Ended for this method(createNextSegmentOutputFile_saf_pfd_support)-----

    // ----- Fix Start for this class (RecordingService_segment_processing_queue) -----
    // Queue to hold completed segment files for FFmpeg processing
    private final ConcurrentLinkedQueue<Object> segmentProcessingQueue = new ConcurrentLinkedQueue<>();
    private boolean isSegmentProcessingActive = false;
    // ----- Fix Ended for this class (RecordingService_segment_processing_queue) -----

    // ----- Fix Start for this method(processNextSegmentInQueue)-----
    /**
     * Processes the next segment in the queue through FFmpeg, if not already processing.
     */
    private synchronized void processNextSegmentInQueue() {
        if (isSegmentProcessingActive) {
            Log.d(TAG, "processNextSegmentInQueue: Already processing a segment, will process next after current.");
            return;
        }
        Object nextSegment = segmentProcessingQueue.poll();
        if (nextSegment == null) {
            Log.d(TAG, "processNextSegmentInQueue: No segment in queue to process.");
            return;
        }
        isSegmentProcessingActive = true;
        if (nextSegment instanceof File) {
            Log.i(TAG, "processNextSegmentInQueue: Starting FFmpeg processing for: " + ((File) nextSegment).getAbsolutePath());
//            processAndMoveVideo((File) nextSegment, null);
        } else if (nextSegment instanceof DocumentFile) {
            Log.i(TAG, "processNextSegmentInQueue: Starting FFmpeg processing for SAF: " + ((DocumentFile) nextSegment).getUri());
            processAndMoveSafVideo((DocumentFile) nextSegment);
        } else {
            Log.e(TAG, "processNextSegmentInQueue: Unknown segment type: " + nextSegment.getClass().getName());
            isSegmentProcessingActive = false;
            processNextSegmentInQueue();
        }
    }
    // ----- Fix Ended for this method(processNextSegmentInQueue)-----

    private void processAndMoveSafVideo(@NonNull DocumentFile safDocFile) {
        // Copy SAF file to temp cache, then process as normal
        File tempCacheFile = copySafUriToTempCacheForProcessing(getApplicationContext(), safDocFile.getUri(), safDocFile.getName());
        if (tempCacheFile != null && tempCacheFile.exists()) {
//            processAndMoveVideo(tempCacheFile, safDocFile.getUri());
        } else {
            Log.e(TAG, "processAndMoveSafVideo: Failed to copy SAF file to temp cache for processing: " + safDocFile.getUri());
            isSegmentProcessingActive = false;
            processNextSegmentInQueue();
        }
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

    // ----- Fix Start for camera resource management methods -----
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
    // ----- Fix End for camera resource management methods -----

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

    // ----- Fix Start for this class(RecordingService) -----
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
            
            // If currently recording, try to apply location to current mediaRecorder
//            if (mediaRecorder != null && geotagHelper != null &&
//                (recordingState == RecordingState.IN_PROGRESS || recordingState == RecordingState.PAUSED)) {
//                try {
//                    // Try immediately
//                    boolean immediateSuccess = geotagHelper.applyLocationToRecorder(mediaRecorder);
//                    Log.d(TAG, "Applied location to active MediaRecorder: " + immediateSuccess);
//
//                    // Also schedule a delayed attempt for higher chance of success
//                    new Thread(() -> {
//                        try {
//                            Thread.sleep(1500);
//                            if (mediaRecorder != null && geotagHelper != null &&
//                                (recordingState == RecordingState.IN_PROGRESS || recordingState == RecordingState.PAUSED)) {
//                                boolean success = geotagHelper.applyLocationToRecorder(mediaRecorder);
//                                Log.d(TAG, "Applied location to active MediaRecorder (delayed): " + success);
//                            }
//                        } catch (Exception e) {
//                            Log.e(TAG, "Error in delayed location application", e);
//                        }
//                    }).start();
//                } catch (Exception e) {
//                    Log.e(TAG, "Error applying location after reinitialization", e);
//                }
//            }
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
    // ----- Fix Ended for this class(RecordingService) -----

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
//                mediaRecorder.start();
                recordingState = RecordingState.IN_PROGRESS;
                
                // ----- Fix Start for this method(handleSessionConfigured) -----
                // Use SystemClock.elapsedRealtime() instead of System.currentTimeMillis() for consistency with HomeFragment
                recordingStartTime = SystemClock.elapsedRealtime();
                Log.d(TAG, "Recording started with recordingStartTime=" + recordingStartTime);
                // ----- Fix End for this method(handleSessionConfigured) -----
                
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

    // ----- Fix Start: Add OpenGL pipeline segment rollover support -----
    // Inner class for OpenGL pipeline segment callback
    private class GLSegmentCallback implements com.fadcam.opengl.GLRecordingPipeline.SegmentCallback {
        @Override
        public void onSegmentRollover(int nextSegmentNumber) {
            String storageMode = sharedPreferencesManager.getStorageMode();
            if (SharedPreferencesManager.STORAGE_MODE_CUSTOM.equals(storageMode)) {
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
                    if (glRecordingPipeline != null) {
                        glRecordingPipeline.setNextOutput(null, pfd.getFileDescriptor());
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Segment rollover: Exception opening PFD for SAF URI", e);
                    stopRecording();
                }
            } else {
                // Internal: use createNextSegmentOutputFile()
                File nextFile = createNextSegmentOutputFile(nextSegmentNumber);
                if (nextFile == null) {
                    Log.e(TAG, "Segment rollover: Failed to create next segment file");
                    stopRecording();
                    return;
                }
                if (glRecordingPipeline != null) {
                    glRecordingPipeline.setNextOutput(nextFile.getAbsolutePath(), null);
                }
            }
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
                        case "timestamp": return getCurrentTimestamp() + locationText;
                        default: return "Captured by FadCam - " + getCurrentTimestamp() + locationText;
                    }
                }
            };

            Size resolution = sharedPreferencesManager.getCameraResolution();
            String orientation = sharedPreferencesManager.getVideoOrientation();
            int videoWidth = resolution.getWidth();
            int videoHeight = resolution.getHeight();
            // ----- Fix Start: Remove width/height swap for portrait -----
            // Do NOT swap width/height for portrait. Always use the selected resolution as-is.
            // ----- Fix End: Remove width/height swap for portrait -----
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
            long splitSizeBytes = sharedPreferencesManager.getVideoSplitSizeBytes();
            int initialSegmentNumber = 1;
            GLSegmentCallback segmentCallback = new GLSegmentCallback();
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
                    glRecordingPipeline = new com.fadcam.opengl.GLRecordingPipeline(this, watermarkInfoProvider, videoWidth, videoHeight, videoBitrate, videoFramerate, pfd.getFileDescriptor(), splitSizeBytes, initialSegmentNumber, segmentCallback, previewSurface, orientation, sensorOrientation);
                } catch (Exception e) {
                    Log.e(TAG, "Exception opening PFD for SAF URI", e);
                    Toast.makeText(this, "Failed to open file for writing", Toast.LENGTH_LONG).show();
                    stopSelf();
                    return;
                }
            } else {
                File outputFile = getFinalOutputFile();
                glRecordingPipeline = new com.fadcam.opengl.GLRecordingPipeline(this, watermarkInfoProvider, videoWidth, videoHeight, videoBitrate, videoFramerate, outputFile.getAbsolutePath(), splitSizeBytes, initialSegmentNumber, segmentCallback, previewSurface, orientation, sensorOrientation);
            }
            glRecordingPipeline.prepareSurfaces();
            createCameraPreviewSession();
        } catch (Exception e) {
            Log.e(TAG, "Exception in startRecording", e);
            recordingState = RecordingState.NONE;
            sharedPreferencesManager.setRecordingInProgress(false);
            stopSelf();
        }
    }
    // ----- Fix End: Add OpenGL pipeline segment rollover support -----
}