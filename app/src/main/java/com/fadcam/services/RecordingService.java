package com.fadcam.services;

import android.Manifest;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
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

import com.arthenica.ffmpegkit.FFmpegKit;
import com.arthenica.ffmpegkit.ReturnCode;
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

// ----- Fix Start for this class (RecordingService_video_splitting_imports_and_fields) -----
// ----- Fix Ended for this class (RecordingService_video_splitting_imports_and_fields) -----

import java.util.concurrent.ConcurrentLinkedQueue;

// Add to the beginning of the file
import android.media.MediaMetadataRetriever;

import android.hardware.camera2.CameraConstrainedHighSpeedCaptureSession;
import com.fadcam.utils.DeviceHelper;
import com.fadcam.utils.camera.HighSpeedCaptureHelper;
import com.fadcam.utils.camera.vendor.SamsungFrameRateHelper;
import com.fadcam.utils.camera.vendor.HuaweiFrameRateHelper;

public class RecordingService extends Service {

    private static final int NOTIFICATION_ID = 1;
    private static final String CHANNEL_ID = "RecordingServiceChannel";
    private static final String TAG = "RecordingService"; // Use standard Log TAG

    private MediaRecorder mediaRecorder;
    private CameraDevice cameraDevice;
    private CameraCaptureSession captureSession;
    private CaptureRequest.Builder captureRequestBuilder;
    private Surface previewSurface; // Surface from UI if preview enabled

    private LocationHelper locationHelper;
    private GeotagHelper geotagHelper;

    // File / URI tracking
    private File currentRecordingFile; // Track file path if internal
    private Uri currentRecordingSafUri; // Track SAF URI if custom
    private DocumentFile currentRecordingDocFile; // Track DocumentFile if custom
    private ParcelFileDescriptor currentParcelFileDescriptor; // Track PFD if custom

    // ----- Fix Start for this class (RecordingService_saf_next_docfile_tracking_declaration) -----
    private DocumentFile nextRecordingDocFile;
    // ----- Fix Ended for this class (RecordingService_saf_next_docfile_tracking_declaration) -----

    private RecordingState recordingState = RecordingState.NONE;
    private AtomicInteger ffmpegProcessingTaskCount = new AtomicInteger(0);

    // ----- Fix Start for this class (RecordingService_video_splitting_imports_and_fields) -----
    private boolean isVideoSplittingEnabled = false;
    private long videoSplitSizeBytes = -1L; // -1L means no limit by default, stored in bytes
    private int currentSegmentNumber = 1; // Start with segment 1
    // ----- Fix Ended for this class (RecordingService_video_splitting_imports_and_fields) -----
    // ----- Fix Start for this class (RecordingService_rollover_callback_flag) -----
    // Field isRolloverInProgressJustClosedDevice removed as it's obsolete.
    // ----- Fix Ended for this class (RecordingService_rollover_callback_flag) -----

    // ----- Fix Start for this class (RecordingService) -----
    // Re-introduce isRecordingTorchEnabled to manage torch state *during an active recording session only*
    private boolean isRecordingTorchEnabled = false;
    // ----- Fix Ended for this class (RecordingService) -----

    private CameraManager cameraManager; // Primary camera manager
    private Handler backgroundHandler; // For camera operations

    private long recordingStartTime;

    private SharedPreferencesManager sharedPreferencesManager; // Your settings manager
    private File currentInternalTempFile;

    private boolean isRolloverClosingOldSession = false; // Flag to manage state during segment rollover when the old session is closing

    // Fields to temporarily store information about the completed segment during rollover
    private File completedSegmentTempFile;
    private Uri completedSegmentSafUri;
    private DocumentFile completedSegmentDocFile;

    private WakeLock recordingWakeLock;

    // ----- Fix Start for this class (RecordingService_setNextOutputFile_fields) -----
    // For setNextOutputFile segment rollover
    private File nextSegmentTempFile;
    // ----- Fix Ended for this class (RecordingService_setNextOutputFile_fields) -----

    // ----- Fix Start for this class (RecordingService_setNextOutputFile_flag) -----
    // Track if setNextOutputFile has been called and not yet started
    private boolean isNextOutputFileSet = false;
    // ----- Fix Ended for this class (RecordingService_setNextOutputFile_flag) -----

    // ----- Fix Start for this class (RecordingService_setNextOutputFile_flag_robust) -----
    // Track if a next output file is already pending (per Android docs)
    private boolean nextOutputFilePending = false;
    // ----- Fix Ended for this class (RecordingService_setNextOutputFile_flag_robust) -----

    // ----- Fix Start for this class (RecordingService_saf_pfd_fields) -----
    // Track open ParcelFileDescriptors for SAF segments
    private ParcelFileDescriptor currentSegmentPfd = null;
    private ParcelFileDescriptor nextSegmentPfd = null;
    // ----- Fix Ended for this class (RecordingService_saf_pfd_fields) -----

    // ----- Fix Start for camera resource availability -----
    // A flag to track if camera resources are being released
    private static volatile boolean isCameraResourceReleasing = false;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    // ----- Fix End for camera resource availability -----

    // ----- Fix Start for this class (RecordingService_isCameraOpen_field)-----
    private boolean isCameraOpen = false;
    // ----- Fix Ended for this class (RecordingService_isCameraOpen_field)-----

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
            if (recordingState != RecordingState.NONE && mediaRecorder == null && cameraDevice == null) {
                Log.w(TAG, "Recording state inconsistency detected. Resetting state from " + recordingState + " to NONE.");
                recordingState = RecordingState.NONE;
                sharedPreferencesManager.setRecordingInProgress(false);
            }
            
            // Only proceed if we're in NONE state 
            if (recordingState == RecordingState.NONE) {
                // Update the UI and Service state atomically
                recordingState = RecordingState.STARTING;
                sharedPreferencesManager.setRecordingInProgress(true);
                
                // Load video splitting preferences
                isVideoSplittingEnabled = sharedPreferencesManager.isVideoSplittingEnabled();
                int splitSizeMb = sharedPreferencesManager.getVideoSplitSizeMb();
                if (isVideoSplittingEnabled && splitSizeMb > 0) {
                    videoSplitSizeBytes = (long) splitSizeMb * 1024 * 1024; // Convert MB to Bytes
                    Log.i(TAG, "FFMPEG SPLIT: Video splitting enabled. Size: " + splitSizeMb + "MB (" + videoSplitSizeBytes + " Bytes)");
                } else {
                    videoSplitSizeBytes = -1L; // Disable splitting if not enabled or invalid size
                    Log.i(TAG, "FFMPEG SPLIT: Video splitting disabled or size invalid.");
                }
                currentSegmentNumber = 1; // Always reset segment number on new recording start
                
                // Set initial torch state
                isRecordingTorchEnabled = intent.getBooleanExtra(Constants.INTENT_EXTRA_INITIAL_TORCH_STATE, false);
                Log.d(TAG, "Initial torch state for recording session: " + isRecordingTorchEnabled);
                
                // Set up preview surface if provided
                setupSurfaceTexture(intent);
                
                // Start foreground service
                setupRecordingInProgressNotification();
                
                // Begin camera/recording setup
                startRecording();
                
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

    /** Helper method to fully release MediaRecorder instance safely */
    private void releaseMediaRecorderSafely() {
        if (mediaRecorder != null) {
            Log.d(TAG, "Releasing MediaRecorder instance safely...");
            try { mediaRecorder.reset(); mediaRecorder.release(); Log.i(TAG, "MediaRecorder released successfully."); }
            catch (Exception e) { Log.e(TAG, "Exception during MediaRecorder reset/release", e); }
            finally { mediaRecorder = null; }
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
    private void startRecording() {
        Log.d(TAG, "startRecording: beginning recording setup");
        
        // Add additional state checking at the top of the method
        if (recordingState != RecordingState.STARTING) {
            Log.e(TAG, "startRecording was called but state is " + recordingState + ", expected STARTING");
            return;
        }
        
        // Create notification channel early to ensure notifications work properly
        createNotificationChannel();

        // Reset segment number (first segment = 1)
        currentSegmentNumber = 1;
        
        // Initialize GeotagHelper early if location embedding is enabled, but do it asynchronously
        if (sharedPreferencesManager.isLocationEmbeddingEnabled()) {
            // Create in background thread to avoid ANR
            new Thread(() -> {
                try {
                    if (geotagHelper == null) {
                        Log.d(TAG, "GeotagHelper: Creating GeotagHelper at recording start");
                        geotagHelper = new GeotagHelper(this);
                    }
                    
                    // Start updates in background
                    boolean started = geotagHelper.startUpdates();
                    Log.d(TAG, "GeotagHelper: Location updates started: " + started);
                    
                    // Small delay before continuing, but in background thread
                    Thread.sleep(800);
                } catch (Exception e) {
                    Log.e(TAG, "Error initializing GeotagHelper", e);
                }
            }).start();
        }
        
        // Use try/catch for entire recording setup to ensure we reset state on failure
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
            
            Log.d(TAG,"startRecording: Attempting to start.");
            
            // Acquire wake lock if needed
            if (recordingWakeLock != null && !recordingWakeLock.isHeld()) {
                recordingWakeLock.acquire();
                Log.d(TAG, "Recording WakeLock acquired.");
            }
            
            try {
                setupMediaRecorder();
                if (mediaRecorder == null) {
                    Log.e(TAG, "startRecording: MediaRecorder setup failed.");
                    recordingState = RecordingState.NONE;
                    sharedPreferencesManager.setRecordingInProgress(false);
                    return;
                }
                openCamera();
            } catch (Exception e) {
                Log.e(TAG, "Error starting recording process", e);
                recordingState = RecordingState.NONE;
                sharedPreferencesManager.setRecordingInProgress(false);
                releaseRecordingResources();
                Toast.makeText(this,"Error starting recording", Toast.LENGTH_LONG).show();
                stopSelf();
            }
        } catch (Exception e) {
            Log.e(TAG, "Exception in startRecording", e);
            recordingState = RecordingState.NONE;
            sharedPreferencesManager.setRecordingInProgress(false);
            stopSelf();
        }
    }

    /**
     * Initiates the sequence to stop recording, release hardware resources,
     * notify the UI, and then potentially start background processing.
     */
    private void stopRecording() {
        Log.i(TAG, ">> stopRecording sequence initiated. Current state: " + recordingState);
        if (recordingState == RecordingState.NONE && ffmpegProcessingTaskCount.get() == 0) {
            Log.w(TAG, "stopRecording called but state is already NONE and no ffmpeg tasks are active.");
            sharedPreferencesManager.setRecordingInProgress(false);
            if (!isWorkingInProgress()) stopSelf();
            // ----- Fix Start for this method(stopRecording_release_wakelock)-----
            if (recordingWakeLock != null && recordingWakeLock.isHeld()) {
                recordingWakeLock.release();
                Log.d(TAG, "Recording WakeLock released.");
            }
            // ----- Fix Ended for this method(stopRecording_release_wakelock)-----
            return;
        }

        File tempFileToProcess = this.currentInternalTempFile;
        Uri safUriToProcess = this.currentRecordingSafUri;
        DocumentFile docFileToProcess = this.currentRecordingDocFile;

        final RecordingState previousState = recordingState;
        recordingState = RecordingState.NONE;
        sharedPreferencesManager.setRecordingInProgress(false);

        boolean stoppedCleanly = false;
        if (mediaRecorder != null) {
            try {
                mediaRecorder.stop();
                stoppedCleanly = true;
                Log.d(TAG, "MediaRecorder stopped successfully.");
            } catch (Exception e) {
                Log.e(TAG, "Exception stopping MediaRecorder: " + e.getMessage());
                stoppedCleanly = true; // Treat as stopped for resource release path
            }
            releaseMediaRecorderSafely();
            Log.i(TAG, "MediaRecorder instance fully released after stop.");
        } else {
            Log.w(TAG, "stopRecording: mediaRecorder was already null.");
            stoppedCleanly = true;
        }

        stopForeground(true);
        cancelNotification();
        Log.d(TAG,"Stopped foreground and cancelled notification.");

        try { if (captureSession != null) { captureSession.close(); Log.d(TAG,"CaptureSession closed."); } } catch (Exception e) { Log.w(TAG,"Error closing captureSession",e); } finally { captureSession = null; }
        try { if (cameraDevice != null) { cameraDevice.close(); Log.d(TAG,"CameraDevice closed."); } } catch (Exception e) { Log.w(TAG,"Error closing cameraDevice",e); } finally { cameraDevice = null; }
        Log.i(TAG, "CameraDevice and CaptureSession close initiated.");

        // ----- Fix Start for camera resource cooldown -----
        // Set the flag to block new recordings for a short period
        setCameraResourcesReleasing(true);
        // ----- Fix End for camera resource cooldown -----

        broadcastOnRecordingStopped();
        Log.d(TAG, "Sent BROADCAST_ON_RECORDING_STOPPED.");

        // ----- Fix Start for this method(stopRecording_restore_ffmpeg_processing)-----
        boolean needsProcessing = (previousState != RecordingState.NONE) && stoppedCleanly;
        File actualFileToProcessForFFmpeg = null;
        Uri originalSafUriForThisSegment = null;

        if (needsProcessing) {
            if (tempFileToProcess != null && tempFileToProcess.exists() && tempFileToProcess.length() > 0) {
                Log.d(TAG, "stopRecording: Last segment was internal temp file: " + tempFileToProcess.getAbsolutePath());
                actualFileToProcessForFFmpeg = tempFileToProcess;
            } else if (safUriToProcess != null && docFileToProcess != null && docFileToProcess.exists() && docFileToProcess.length() > 0) {
                Log.d(TAG, "stopRecording: Last segment was SAF URI: " + safUriToProcess + ". Copying to cache for processing.");
                actualFileToProcessForFFmpeg = copySafUriToTempCacheForProcessing(getApplicationContext(), safUriToProcess, docFileToProcess.getName());
                if (actualFileToProcessForFFmpeg != null && actualFileToProcessForFFmpeg.exists()) {
                    originalSafUriForThisSegment = safUriToProcess;
                } else {
                    Log.e(TAG, "stopRecording: Failed to copy last SAF segment to cache. Cannot process: " + docFileToProcess.getName());
                    needsProcessing = false;
                }
            } else {
                Log.w(TAG, "stopRecording: No valid last segment file/URI found for processing.");
                needsProcessing = false;
            }
        }

        if (needsProcessing && actualFileToProcessForFFmpeg != null) {
            Log.i(TAG, "Proceeding to background video processing for: " + actualFileToProcessForFFmpeg.getName() + (originalSafUriForThisSegment != null ? " (Original SAF: "+originalSafUriForThisSegment+")" : ""));
            this.currentInternalTempFile = actualFileToProcessForFFmpeg;
            this.currentRecordingSafUri = null;
            this.currentRecordingDocFile = null;
            this.currentParcelFileDescriptor = null;
            
            processAndMoveVideo(actualFileToProcessForFFmpeg, originalSafUriForThisSegment);
        } else {
            Log.w(TAG, "Skipping processing. PrevState:" + previousState + " CleanStop:" + stoppedCleanly + " (needsProcessing evaluated to: "+needsProcessing+")");
            cleanupTemporaryFile();
            Uri broadcastUri = null;
            if (tempFileToProcess != null && tempFileToProcess.exists()) broadcastUri = Uri.fromFile(tempFileToProcess);
            else if (safUriToProcess != null) broadcastUri = safUriToProcess;

            sendRecordingCompleteBroadcast(stoppedCleanly, broadcastUri, originalSafUriForThisSegment);
            checkIfServiceCanStop();
        }
        // ----- Fix Ended for this method(stopRecording_restore_ffmpeg_processing)-----

        // MediaRecorder is already released by releaseMediaRecorderSafely() earlier if it was not null.
        Log.i(TAG,"<< stopRecording sequence finished. >>");
        // ----- Fix Start for this method(stopRecording_release_wakelock)-----
        if (recordingWakeLock != null && recordingWakeLock.isHeld()) {
            recordingWakeLock.release();
            Log.d(TAG, "Recording WakeLock released.");
        }
        // ----- Fix Ended for this method(stopRecording_release_wakelock)-----

        // After releasing MediaRecorder and before processing, queue the last segment if needed
        if (SharedPreferencesManager.STORAGE_MODE_CUSTOM.equals(sharedPreferencesManager.getStorageMode())) {
            if (currentRecordingDocFile != null && currentRecordingDocFile.exists() && currentRecordingDocFile.length() > 0) {
                Log.d(TAG, "stopRecording: Queuing last SAF segment for FFmpeg: " + currentRecordingDocFile.getUri());
                segmentProcessingQueue.add(currentRecordingDocFile);
                processNextSegmentInQueue();
                currentRecordingDocFile = null;
            }
        } else {
            if (currentInternalTempFile != null && currentInternalTempFile.exists() && currentInternalTempFile.length() > 0) {
                Log.d(TAG, "stopRecording: Queuing last segment for FFmpeg: " + currentInternalTempFile.getAbsolutePath());
                segmentProcessingQueue.add(currentInternalTempFile);
                processNextSegmentInQueue();
                currentInternalTempFile = null; // Prevent double-processing
            }
        }
        // SAF: Close any open PFDs
        closeCurrentPfd();
    }

    // Inside RecordingService.java

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

    private void pauseRecording() {
        if (recordingState != RecordingState.IN_PROGRESS) {
            Log.w(TAG,"pauseRecording requested but not IN_PROGRESS. State: " + recordingState);
            return;
        }
        Log.d(TAG, "pauseRecording: Pausing.");
        try {
            if (mediaRecorder != null) {
                mediaRecorder.pause();
                recordingState = RecordingState.PAUSED;
                sharedPreferencesManager.setRecordingInProgress(false); // Consider if 'paused' is 'in progress'
                setupRecordingResumeNotification(); // Update notification for resume action
                showRecordingInPausedToast();
                broadcastOnRecordingPaused();
                Log.d(TAG, "Recording paused.");
            } else {
                Log.e(TAG,"pauseRecording: mediaRecorder is null!");
            }
        } catch (IllegalStateException e) {
            Log.e(TAG, "Error pausing recording (IllegalStateException)", e);
            // This might mean recording was already stopped or in error state
            stopRecording(); // Attempt graceful stop if pause fails
        } catch (Exception e) {
            Log.e(TAG,"General error pausing recording",e);
            stopRecording(); // Attempt graceful stop on error
        }
    }

    private void resumeRecording() {
        if (recordingState != RecordingState.PAUSED) {
            Log.w(TAG,"resumeRecording requested but not PAUSED. State: " + recordingState);
            // If state is NONE, might mean app was killed and restarted, try starting?
            // If state is IN_PROGRESS, ignore.
            return;
        }
        Log.d(TAG,"resumeRecording: Resuming...");
        try {
            if (mediaRecorder != null) {
                // Surface setup should happen before resume if needed
                if(previewSurface == null || !previewSurface.isValid()) {
                    Log.w(TAG,"resumeRecording: Preview surface invalid/null, may not show preview.");
                    // Consider recreating capture session if surface is critical and missing?
                }

                mediaRecorder.resume();
                recordingState = RecordingState.IN_PROGRESS;
                sharedPreferencesManager.setRecordingInProgress(true);
                setupRecordingInProgressNotification(); // Update notification
                showRecordingResumedToast();
                broadcastOnRecordingResumed();
                Log.d(TAG,"Recording resumed.");
            } else {
                Log.e(TAG,"resumeRecording: mediaRecorder is null! Cannot resume.");
                // If recorder is null, likely something went wrong. Stop? Start new?
                stopRecording(); // Attempt safe stop
            }
        } catch (IllegalStateException e) {
            Log.e(TAG, "Error resuming recording (IllegalStateException)", e);
            stopRecording(); // Attempt stop if resume fails
        } catch (Exception e) {
            Log.e(TAG,"General error resuming recording",e);
            stopRecording(); // Attempt stop on error
        }
    }


    /** Ensures ALL hardware resources related to recording are released. */
    private void releaseRecordingResources() {
        Log.d(TAG, "Releasing ALL recording resources (Camera, Session, Recorder)...");
        // Order: Session -> Device -> Recorder
        try { if (captureSession != null) { captureSession.close(); Log.d(TAG,"Rel: CaptureSession closed."); } } catch (Exception e) { Log.w(TAG,"Err close CaptureSession",e); } finally { captureSession = null; }
        try { if (cameraDevice != null) { cameraDevice.close(); Log.d(TAG,"Rel: CameraDevice closed."); } } catch (Exception e) { Log.w(TAG,"Err close CameraDevice",e); } finally { cameraDevice = null; }
        releaseMediaRecorderSafely(); // Release recorder object

        // Reset state if not already done
        recordingState = RecordingState.NONE;
        if (sharedPreferencesManager.isRecordingInProgress()){ // Check pref mismatch
            Log.w(TAG,"Release: Pref indicated recording, resetting it.");
            sharedPreferencesManager.setRecordingInProgress(false);
        }

        // ----- Fix Start for this method(releaseRecordingResources_check_ffmpeg_counter)-----
        // Cleanup temp file only if processing isn't active
        if (ffmpegProcessingTaskCount.get() == 0) {
            cleanupTemporaryFile();
        } else {
            Log.d(TAG,"Release: Keeping temp file reference for ongoing processing (ffmpeg tasks > 0).");
        }
        // ----- Fix Ended for this method(releaseRecordingResources_check_ffmpeg_counter)-----
        Log.d(TAG, "Finished releasing recording resources.");
    }
    // --- End Core Recording Logic ---

    private void processAndMoveVideo(@NonNull File internalTempFileToProcess, @Nullable Uri originalSafTempUri) { // Arg is now in external cache
        Log.d(TAG,"processAndMoveVideo starting for (ext cache): " + internalTempFileToProcess.getName() + (originalSafTempUri != null ? ", Original SAF Temp: " + originalSafTempUri : ""));
        if (!internalTempFileToProcess.exists() || internalTempFileToProcess.length() == 0) {
            Log.e(TAG,"Temp file invalid/empty: " + internalTempFileToProcess.getAbsolutePath());
            // ----- Fix Start for this method(processAndMoveVideo_ensure_ffmpeg_counter_not_incremented_on_early_exit)-----
            // isProcessingWatermark = false; // No longer used directly here for this check
            // ----- Fix Ended for this method(processAndMoveVideo_ensure_ffmpeg_counter_not_incremented_on_early_exit)-----
            if(internalTempFileToProcess.exists()&&!internalTempFileToProcess.delete()) Log.w(TAG,"Failed del invalid temp"); 
            // ----- Fix Start for this method(processAndMoveVideo_decrement_on_early_error)-----
            // If we return here, the task was never really started, so no need to adjust counter if it wasn't incremented.
            // However, the increment is now at the beginning. So if we exit here, we MUST decrement.
            // This scenario (invalid input file) should ideally not increment the counter.
            // Let's move the increment after this initial check.
            // ----- Fix Ended for this method(processAndMoveVideo_decrement_on_early_error)-----
            return;
        }
        // ----- Fix Start for this method(processAndMoveVideo_increment_ffmpeg_counter)-----
        // isProcessingWatermark = true;
        ffmpegProcessingTaskCount.incrementAndGet();
        Log.d(TAG, "FFmpeg task count incremented to: " + ffmpegProcessingTaskCount.get() + " for file: " + internalTempFileToProcess.getName());
        // ----- Fix Ended for this method(processAndMoveVideo_increment_ffmpeg_counter)-----
        String storageMode = sharedPreferencesManager.getStorageMode();
        String customUriString = sharedPreferencesManager.getCustomStorageUri();
        String tempFileName = internalTempFileToProcess.getName();
        String finalBaseName = tempFileName.replace("temp_", Constants.RECORDING_DIRECTORY + "_");
        String internalTempInputPath = internalTempFileToProcess.getAbsolutePath(); // Path is in ext cache now

        if (SharedPreferencesManager.STORAGE_MODE_CUSTOM.equals(storageMode) && customUriString != null) {
            Log.d(TAG, "Target is Custom SAF Location: " + customUriString);

            // Define intermediate output path also in EXTERNAL CACHE
            File cacheDir = getExternalCacheDir(); // <--- CHANGE HERE
            if (cacheDir == null) { cacheDir = new File(getCacheDir(), "processed_temp"); Log.w(TAG,"Ext Cache null, using int cache for processed temp"); } else { cacheDir = new File(cacheDir, "processed_temp"); } // Create subdir in external cache
            // ----- Fix Start for this method(processAndMoveVideo_handle_error_decrement_counter)-----
            if (!cacheDir.exists() && !cacheDir.mkdirs()) { 
                handleProcessingError("Cannot create processed cache dir", internalTempInputPath);
                ffmpegProcessingTaskCount.decrementAndGet(); // Decrement because FFmpeg task won't start
                Log.d(TAG, "FFmpeg task count decremented due to processed cache dir error. Count: " + ffmpegProcessingTaskCount.get());
                return; 
            }
            File internalProcessedOutputFile = new File(cacheDir, finalBaseName);
            String internalProcessedOutputPath = internalProcessedOutputFile.getAbsolutePath(); // Path is in ext cache now

            Log.d(TAG, "Intermediate processed path (ext cache): " + internalProcessedOutputPath);

            String ffmpegCommand = buildFFmpegCommand(internalTempInputPath, internalProcessedOutputPath);
            if (ffmpegCommand == null) { 
                handleProcessingError("Failed build FFmpeg command", internalTempInputPath);
                ffmpegProcessingTaskCount.decrementAndGet(); // Decrement because FFmpeg task won't start
                Log.d(TAG, "FFmpeg task count decremented due to command build error. Count: " + ffmpegProcessingTaskCount.get());
                return; 
            }
            // ----- Fix Ended for this method(processAndMoveVideo_handle_error_decrement_counter)-----
            executeFFmpegAndMoveToSAF(ffmpegCommand, internalTempFileToProcess, internalProcessedOutputFile, customUriString, originalSafTempUri); // Pass correct files and original SAF temp URI

        } else {
            Log.d(TAG, "Target is Internal App Storage");
            // Process from external cache -> final internal directory
            File finalInternalDir = new File(getExternalFilesDir(null), Constants.RECORDING_DIRECTORY); // Standard final location
            // ----- Fix Start for this method(processAndMoveVideo_handle_error_decrement_counter)-----
            if (!finalInternalDir.exists() && !finalInternalDir.mkdirs()) { 
                handleProcessingError("Cannot create final internal dir", internalTempInputPath);
                ffmpegProcessingTaskCount.decrementAndGet(); // Decrement because FFmpeg task won't start
                Log.d(TAG, "FFmpeg task count decremented due to final internal dir error. Count: " + ffmpegProcessingTaskCount.get());
                return; 
            }
            File finalInternalOutputFile = new File(finalInternalDir, finalBaseName);
            String finalInternalOutputPath = finalInternalOutputFile.getAbsolutePath();
            Log.d(TAG, "Final internal path: " + finalInternalOutputPath);

            String ffmpegCommand = buildFFmpegCommand(internalTempInputPath, finalInternalOutputPath);
            if (ffmpegCommand == null) { 
                handleProcessingError("Failed build FFmpeg command", internalTempInputPath); 
                ffmpegProcessingTaskCount.decrementAndGet(); // Decrement because FFmpeg task won't start
                Log.d(TAG, "FFmpeg task count decremented due to command build error. Count: " + ffmpegProcessingTaskCount.get());
                return; 
            }
            // ----- Fix Ended for this method(processAndMoveVideo_handle_error_decrement_counter)-----
            executeFFmpegInternalOnly(ffmpegCommand, internalTempFileToProcess, finalInternalOutputFile); // Pass correct temp input file
        }
    }

    // Builds the appropriate FFmpeg command
    // Replace the existing buildFFmpegCommand method in RecordingService.java

    // Replace the existing buildFFmpegCommand method in RecordingService.java

    @Nullable
    private String buildFFmpegCommand(String inputPath, String outputPath) {
        String watermarkOption = sharedPreferencesManager.getWatermarkOption();
        String ffmpegCommand = null;

        if ("no_watermark".equals(watermarkOption)) {
            Log.d(TAG,"Building FFmpeg copy command (no watermark).");
            // When copying, no re-encoding happens, codec and pixel format don't matter.
            // Add -map_metadata 0 to preserve all metadata from the input file
            ffmpegCommand = String.format(Locale.US, "-i %s -codec copy -map_metadata 0 -y %s", 
                escapeFFmpegPath(inputPath), escapeFFmpegPath(outputPath));
        } else {
            Log.d(TAG,"Building FFmpeg watermark command.");

            // Always use H.264 hardware encoder for watermarking processing for compatibility
            String codecStringForProcessing = "h264_mediacodec";
            Log.i(TAG,"Watermark enabled. Using processing codec: " + codecStringForProcessing);

            try {
                            // Existing logic to prepare watermark text, font, etc.
            String fontPath = getFilesDir().getAbsolutePath() + "/ubuntu_regular.ttf";
            File fontFile = new File(fontPath);
            // Enhanced logging: Check and log font file details
            if(!fontFile.exists()){ 
                Log.e(TAG,"Font file missing at: "+fontPath); 
                return null;
            } else {
                Log.i(TAG, "FFMPEG FONT: Found font file at " + fontPath + " (size: " + fontFile.length() + " bytes)");
            }

                String watermarkText;
                boolean isLocationEnabled = sharedPreferencesManager.isLocalisationEnabled();
                String locationText = isLocationEnabled ? getLocationData() : "";
                switch (watermarkOption) {
                    case "timestamp": watermarkText = getCurrentTimestamp() + locationText; break;
                    default: watermarkText = "Captured by FadCam - " + getCurrentTimestamp() + locationText; break;
                }
                watermarkText = convertArabicNumeralsToEnglish(watermarkText);
                String escapedWatermarkText = escapeFFmpegString(watermarkText);
                String escapedFontPath = escapeFFmpegPath(fontPath);

                int fontSize = getFontSizeBasedOnBitrate();
                String fontSizeStr = convertArabicNumeralsToEnglish(String.valueOf(fontSize));
                int frameRates = sharedPreferencesManager.getSpecificVideoFrameRate(sharedPreferencesManager.getCameraSelection());
                int bitratesEstimated = getVideoBitrate();

                // *** ADDED: Force pixel format to NV12 before encoding ***
                String pixelFormatOption = "-pix_fmt nv12";
                Log.d(TAG, "Adding pixel format option: " + pixelFormatOption);

                // Build the command using the forced H.264 codec AND forced pixel format
                // Order: Input -> Filters -> Output Codec -> Output Bitrate -> Pixel Format -> Audio Codec -> Output Path
                // Added -map_metadata 0 to preserve all metadata from the input file
                ffmpegCommand = String.format(Locale.US,
                        "-i %s -r %d -vf \"drawtext=text='%s':x=10:y=10:fontsize=%s:fontcolor=white:fontfile='%s'\" -q:v 0 -codec:v %s -b:v %d %s -map_metadata 0 -codec:a copy -y %s",
                        escapeFFmpegPath(inputPath),
                        frameRates,
                        escapedWatermarkText,
                        fontSizeStr,
                        escapedFontPath,
                        codecStringForProcessing, // Force h264_mediacodec
                        bitratesEstimated,
                        pixelFormatOption,        // *** ADDED -pix_fmt nv12 here ***
                        escapeFFmpegPath(outputPath)
                );
            } catch (Exception e){
                Log.e(TAG, "Error building watermark FFmpeg command", e);
                return null;
            }
        }
        
        // Enhanced logging: Log the full FFmpeg command for debugging
        Log.i(TAG, "FFMPEG COMMAND: " + ffmpegCommand);
        
        return ffmpegCommand;
    }

    // Execute FFmpeg, saving result to final internal directory
    private void executeFFmpegInternalOnly(String command, File internalTempInput, File finalInternalOutput) {
        Log.d(TAG, "executeFFmpegInternalOnly: Processing " + internalTempInput.getName() + " -> " + finalInternalOutput.getName());

        // ** FIX: Add inputFile and finalOutputUriIfKnown arguments to the call **
        executeFFmpegAsync(
                command,
                internalTempInput,                          // Pass the input file object
                Uri.fromFile(finalInternalOutput),          // Pass the known final output URI
                () -> { // Success Runnable
                    Log.d(TAG, "FFmpeg internal success. Output: " + finalInternalOutput.getAbsolutePath());
                    // Delete the *input* temp file on success
                    if (internalTempInput.exists() && !internalTempInput.delete()) {
                        Log.w(TAG, "Failed to delete internal temp after successful processing: " + internalTempInput.getName());
                    }
                    // Optional: Media Scan if needed for finalInternalOutput
                    sendRecordingCompleteBroadcast(true, Uri.fromFile(finalInternalOutput), null); // originalTempSafUri is null for internal
                },
                () -> { // Failure Runnable
                    Log.e(TAG, "FFmpeg internal process failed for: " + internalTempInput.getName());
                    // Clean up the FAILED *output* file if it exists
                    if (finalInternalOutput.exists() && !finalInternalOutput.delete()) {
                        Log.w(TAG, "Failed to delete failed internal output: " + finalInternalOutput.getName());
                    }
                    // Do NOT delete the input temp file on failure
                    sendRecordingCompleteBroadcast(false, Uri.fromFile(internalTempInput), null); // originalTempSafUri is null for internal
                }
        );
    }

    /**
     * Helper method to execute FFmpeg commands asynchronously and handle callbacks.
     * Sets/resets the processing flag and checks if the service should stop itself.
     *
     * @param ffmpegCommand The FFmpeg command string to execute.
     * @param onSuccess Runnable to execute if FFmpeg finishes successfully.
     * @param onFailure Runnable to execute if FFmpeg fails.
     */
    // Centralized Async FFmpeg Execution
    // Updated helper to run FFmpeg and broadcast START/END processing states
    private void executeFFmpegAsync(String ffmpegCommand, File inputFile, Uri finalOutputUriIfKnown, Runnable onSuccess, Runnable onFailure) {
        Log.d(TAG, "executeFFmpegAsync: Starting FFmpeg processing for: " + inputFile.getName());
        // Enhanced logging: Log the full FFmpeg command for debugging
        Log.i(TAG, "FFMPEG COMMAND: " + ffmpegCommand);
        
        // Make the service foreground again if it's not already in the foreground

        // ----- Fix Start for fixing FFmpeg foreground service crash -----
        // Show processing notification to keep the service in foreground state during FFmpeg tasks
        NotificationCompat.Builder builder = createBaseNotificationBuilder()
                .setContentTitle("Processing Video")
                .setContentText("Processing " + inputFile.getName())
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setProgress(0, 0, true); // Indeterminate progress bar
                
        // Start or update foreground service notification
        startForeground(NOTIFICATION_ID, builder.build());
        Log.d(TAG, "Started foreground service for FFmpeg processing");
        // ----- Fix End for fixing FFmpeg foreground service crash -----
        
        // Send broadcast to UI that processing started
        if (finalOutputUriIfKnown != null) {
            sendProcessingStateBroadcast(true, finalOutputUriIfKnown);
            } else {
            sendProcessingStateBroadcast(true, Uri.fromFile(inputFile));
        }

        String tempInputPath = inputFile.getAbsolutePath();
        if (!inputFile.exists()) {
            Log.e(TAG, "executeFFmpegAsync: Input file doesn't exist: " + tempInputPath);
            if (onFailure != null) onFailure.run();
            return;
        }

        // Remove Config.enableLogCallback and Config.enableStatisticsCallback calls
        // as they don't exist in this version of the FFmpegKit library

        FFmpegKit.executeAsync(ffmpegCommand, session -> {
            ReturnCode returnCode = session.getReturnCode();
            try {
                // ----- Fix Start for fixing FFmpeg foreground service crash -----
                // Update notification to show completion
                NotificationCompat.Builder completeBuilder = createBaseNotificationBuilder()
                        .setContentTitle("Processing Complete")
                        .setContentText(ReturnCode.isSuccess(returnCode) ? "Video processing completed" : "Video processing failed")
                        .setPriority(NotificationCompat.PRIORITY_LOW);
                        
                // Keep showing notification until we check if service can stop
                startForeground(NOTIFICATION_ID, completeBuilder.build());
                // ----- Fix End for fixing FFmpeg foreground service crash -----
                
                if (ReturnCode.isSuccess(returnCode)) {
                    Log.i(TAG, "FFmpeg execution completed successfully for " + inputFile.getName());
                    
                    // Check if the output file has location metadata (only for files, not SAF URIs)
                    if (finalOutputUriIfKnown != null && finalOutputUriIfKnown.getScheme() != null && 
                        finalOutputUriIfKnown.getScheme().equals("file") && 
                        sharedPreferencesManager.isLocationEmbeddingEnabled()) {
                        String outputPath = finalOutputUriIfKnown.getPath();
                        if (outputPath != null) {
                            verifyLocationMetadata(outputPath);
                        }
                    }
                    
                    if (onSuccess != null) onSuccess.run();
                    isSegmentProcessingActive = false;
                    // Process next in queue
                    processNextSegmentInQueue();
                    
                    // Update notification to show completion and remove foreground state
                    if (finalOutputUriIfKnown != null) {
                        sendProcessingStateBroadcast(false, finalOutputUriIfKnown);
                    } else {
                        sendProcessingStateBroadcast(false, Uri.fromFile(inputFile));
                    }
                    
                } else if (ReturnCode.isCancel(returnCode)) {
                    Log.w(TAG, "FFmpeg execution canceled for " + inputFile.getName());
                    // Enhanced logging: Log the full stderr for debugging
                    Log.w(TAG, "FFMPEG STDERR: " + session.getAllLogsAsString());
                    if (onFailure != null) onFailure.run();
                    isSegmentProcessingActive = false;
                    processNextSegmentInQueue();
                } else {
                    Log.e(TAG, "FFmpeg execution failed for " + inputFile.getName() + " with state: " + session.getState() + " and return code: " + returnCode);
                    // Enhanced logging: Log the full stderr for debugging
                    Log.e(TAG, "FFMPEG STDERR OUTPUT: \n" + session.getAllLogsAsString());
                    Log.e(TAG, "FFMPEG FAILED COMMAND: " + ffmpegCommand);
                    if (onFailure != null) onFailure.run();
                    isSegmentProcessingActive = false;
                    processNextSegmentInQueue();
                }
            } catch (Exception e) {
                Log.e(TAG, "Exception in FFmpeg completion callback", e);
                // Enhanced logging: Log the full stderr for debugging even in case of exception
                Log.e(TAG, "FFMPEG STDERR (exception occurred): \n" + session.getAllLogsAsString());
                if (onFailure != null) onFailure.run();
                isSegmentProcessingActive = false;
                processNextSegmentInQueue();
            } finally {
                // ----- Fix Start for FFmpeg counter decrement -----
                int remainingTasks = ffmpegProcessingTaskCount.decrementAndGet();
                Log.d(TAG, "FFmpeg task count decremented to: " + remainingTasks);
                
                // Only check if service can stop once the remaining tasks counter reaches 0 
                if (remainingTasks == 0) {
                    // Only consider stopping the service when all FFmpeg tasks are done
                    checkIfServiceCanStop();
                }
                // ----- Fix End for FFmpeg counter decrement -----
            }
        }, log -> {
            // Log handler - just log the message
            if (log != null && log.getMessage() != null) {
                String message = log.getMessage();
                // Enhanced logging: Don't truncate logs for better debugging
                Log.d(TAG, "[FFMPEG LOG] " + message);
            }
        }, statistics -> {
            // Statistics handler - log periodically
            if (statistics != null && statistics.getTime() % 5000 < 500) { // Every 5s approx
                try {
                    Log.d(TAG, String.format(Locale.US, 
                        "[FFMPEG STATS] Time: %d ms, size: %d KB, speed: %.2f, bitrate: %.2f kbits/s",
                        statistics.getTime(), 
                        statistics.getSize() / 1024, 
                        statistics.getSpeed(), 
                        statistics.getBitrate()));
                } catch (Exception e) {
                    Log.w(TAG, "Error formatting FFmpeg statistics: " + e.getMessage());
                }
            }
        });
    }

    // --- NEW: Helper to send the PROCESSING start/end broadcast ---
    private void sendProcessingStateBroadcast(boolean started, @NonNull Uri fileUri) {
        if (fileUri == null) {
            Log.e(TAG, "Cannot send processing state broadcast, file URI is null.");
            return;
        }
        Intent stateIntent = new Intent(started ? Constants.ACTION_PROCESSING_STARTED : Constants.ACTION_PROCESSING_FINISHED);
        stateIntent.putExtra(Constants.EXTRA_PROCESSING_URI_STRING, fileUri.toString());
        sendBroadcast(stateIntent);
        Log.i(TAG, "Broadcast sent: " + stateIntent.getAction() + " for URI: " + fileUri.toString());
    }

    // --- NEW: Helper to send the completion broadcast ---
    private void sendRecordingCompleteBroadcast(boolean success, @Nullable Uri resultUri, @Nullable Uri originalTempSafUriForReplacement) {
        Intent completeIntent = new Intent(Constants.ACTION_RECORDING_COMPLETE);
        completeIntent.putExtra(Constants.EXTRA_RECORDING_SUCCESS, success);
        if (resultUri != null) {
            completeIntent.putExtra(Constants.EXTRA_RECORDING_URI_STRING, resultUri.toString());
        } else {
            Log.w(TAG,"Sending RECORDING_COMPLETE broadcast without a result URI (Success="+success+").");
        }
        if (originalTempSafUriForReplacement != null) {
            completeIntent.putExtra(Constants.EXTRA_ORIGINAL_TEMP_SAF_URI_STRING, originalTempSafUriForReplacement.toString());
            Log.d(TAG, "Included original temp SAF URI for replacement in complete broadcast: " + originalTempSafUriForReplacement.toString());
        }
        // Optional: Include package name if you want to restrict the broadcast
        // completeIntent.setPackage(getPackageName());
        sendBroadcast(completeIntent);
        Log.i(TAG, "Broadcast sent: " + Constants.ACTION_RECORDING_COMPLETE + " (Success=" + success + ")");
    }

    // --- NEW / Placeholder: Helper to determine the success URI ---
    // This needs to be adapted based on HOW/WHERE your onSuccess runnable saves the final file
    // ----- Fix Start for this method(determineSuccessUri_use_input_filename_parameter)-----
    private Uri determineSuccessUri(String originalTempInputFileName) {
    // ----- Fix Ended for this method(determineSuccessUri_use_input_filename_parameter)-----
        // Option 1: If onSuccess writes to a known variable (like 'finalInternalOutputFile' or 'finalOutputDocFile's URI)
        // return finalOutputFileUri; // You need to capture this URI after the FFmpegKit.execute block completes

        // Option 2: Reconstruct it based on known inputs (less reliable if renaming occurs)
        String storageMode = sharedPreferencesManager.getStorageMode();
        if (SharedPreferencesManager.STORAGE_MODE_INTERNAL.equals(storageMode)) {
            File finalInternalDir = new File(getExternalFilesDir(null), Constants.RECORDING_DIRECTORY);
            // You NEED the final file name here - this might require passing it into executeFFmpegAsync or storing it
            // ----- Fix Start for this method(determineSuccessUri_use_input_filename_parameter)-----
            String finalName = reconstructFinalNameFromTemp(originalTempInputFileName); // Use passed parameter
            // ----- Fix Ended for this method(determineSuccessUri_use_input_filename_parameter)-----
            if (finalName != null) {
                return Uri.fromFile(new File(finalInternalDir, finalName));
            }
        } else {
            String customUriString = sharedPreferencesManager.getCustomStorageUri();
            if (customUriString != null) {
                // ----- Fix Start for this method(determineSuccessUri_use_input_filename_parameter)-----
                String finalName = reconstructFinalNameFromTemp(originalTempInputFileName); // Use passed parameter
                // ----- Fix Ended for this method(determineSuccessUri_use_input_filename_parameter)-----
                if(finalName != null){
                    // To get the final SAF URI, you need to list the directory or know the exact URI created
                    // This is harder without passing the final DocumentFile URI back from the success callback
                    Log.w(TAG,"Cannot reliably determine final SAF URI here without more context.");
                    // Could potentially pass null or try a best guess
                }
            }
        }
        return null; // Return null if URI cannot be determined
    }

    // Helper - Needs access to the *original* temp filename when FFmpeg started
    // ----- Fix Start for this method(reconstructFinalNameFromTemp_use_parameter)-----
    private String reconstructFinalNameFromTemp(String originalTempInputFileName){
        if(originalTempInputFileName != null){ // Use the passed parameter
            return originalTempInputFileName.replace("temp_", Constants.RECORDING_DIRECTORY + "_");
        }
    // ----- Fix Ended for this method(reconstructFinalNameFromTemp_use_parameter)-----
        return null;
    }

    // In RecordingService.java

    // Execute FFmpeg saving to temp internal cache, then move result to SAF
    private void executeFFmpegAndMoveToSAF(String command, File internalTempInput, File tempInternalProcessedOutput, String targetSafDirUriString, @Nullable Uri originalSafTempUri) {
        Log.d(TAG, "executeFFmpegAndMoveToSAF: Processing " + internalTempInput.getName() + " -> " + tempInternalProcessedOutput.getName() + " -> SAF" + (originalSafTempUri != null ? ", OriginalSAF: " + originalSafTempUri : ""));

        // ** FIX: Add inputFile and finalOutputUriIfKnown (which is null here) arguments **
        executeFFmpegAsync(
                command,
                internalTempInput,              // Pass the original temp input file
                null,                          // Pass null for final output URI, as it's determined later
                () -> { // FFmpeg Success Runnable (FFmpeg wrote successfully to tempInternalProcessedOutput)
                    Log.d(TAG, "FFmpeg successful (intermediate cache file created): " + tempInternalProcessedOutput.getAbsolutePath());
                    // Now move the processed file from cache to SAF
                    Uri finalSafUri = moveInternalFileToSAF(tempInternalProcessedOutput, targetSafDirUriString);
                    if (finalSafUri != null) {
                        Log.d(TAG, "Successfully moved processed cache file to SAF: " + finalSafUri);
                        // Delete BOTH internal temp files (original input and processed cache) on success
                        if (internalTempInput.exists() && !internalTempInput.delete()) { Log.w(TAG, "Failed to delete initial temp: " + internalTempInput.getName()); }
                        if (tempInternalProcessedOutput.exists() && !tempInternalProcessedOutput.delete()) { Log.w(TAG, "Failed to delete processed cache temp: " + tempInternalProcessedOutput.getName()); }

                        // If there was an original temporary SAF URI, delete it now
                        if (originalSafTempUri != null) {
                            try {
                                DocumentFile originalTempSafDoc = DocumentFile.fromSingleUri(this, originalSafTempUri);
                                if (originalTempSafDoc != null && originalTempSafDoc.exists()) {
                                    if (originalTempSafDoc.delete()) {
                                        Log.d(TAG, "Successfully deleted original temporary SAF file: " + originalSafTempUri);
                                    } else {
                                        Log.w(TAG, "Failed to delete original temporary SAF file: " + originalSafTempUri);
                                    }
                                }
                            } catch (Exception e) {
                                Log.e(TAG, "Error deleting original temporary SAF file: " + originalSafTempUri, e);
                            }
                        }
                        sendRecordingCompleteBroadcast(true, finalSafUri, originalSafTempUri);
                    } else {
                        Log.e(TAG, "Failed to move processed cache file to SAF!");
                        Toast.makeText(this, "Failed to save to custom location", Toast.LENGTH_LONG).show();
                        // Leave the *processed* temp cache file as backup.
                        // Delete the *original* input temp file if FFmpeg succeeded but move failed.
                        if (internalTempInput.exists() && !internalTempInput.delete()) { Log.w(TAG, "Failed to delete initial temp after move failure: " + internalTempInput.getName()); }
                        sendRecordingCompleteBroadcast(false, Uri.fromFile(internalTempInput), originalSafTempUri); // Send failure for original, include original SAF temp
                    }
                },
                () -> { // FFmpeg Failure Runnable (FFmpeg failed to write to tempInternalProcessedOutput)
                    Log.e(TAG, "FFmpeg process failed (writing to cache) for: " + internalTempInput.getName());
                    // Clean up the FAILED *processed* cache output if it exists
                    if (tempInternalProcessedOutput.exists() && !tempInternalProcessedOutput.delete()) {
                        Log.w(TAG, "Failed to delete failed processed cache output: " + tempInternalProcessedOutput.getName());
                    }
                    // Do NOT delete the original input temp file on FFmpeg failure
                    sendRecordingCompleteBroadcast(false, Uri.fromFile(internalTempInput), originalSafTempUri); // Send failure, include original SAF temp
                }
        ); // ** End of executeFFmpegAsync call **
    }

    // Helper to move an internal file to a SAF directory URI
    @Nullable
    private Uri moveInternalFileToSAF(File internalSourceFile, String targetDirUriString) {
        if (!internalSourceFile.exists()) {
            Log.e(TAG,"moveInternalFileToSAF: Source file does not exist: " + internalSourceFile.getAbsolutePath());
            return null;
        }
        if (targetDirUriString == null) {
            Log.e(TAG,"moveInternalFileToSAF: Target SAF directory URI is null.");
            return null;
        }

        // Verify location metadata in the source file before copying (for debugging)
        if (sharedPreferencesManager.isLocationEmbeddingEnabled()) {
            verifyLocationMetadata(internalSourceFile.getAbsolutePath());
        }

        ContentResolver resolver = getContentResolver();
        Uri dirUri = Uri.parse(targetDirUriString);
        DocumentFile targetDir = DocumentFile.fromTreeUri(this, dirUri);

        if (targetDir == null || !targetDir.isDirectory() || !targetDir.canWrite()) {
            Log.e(TAG, "moveInternalFileToSAF: Target SAF directory is invalid or not writable: " + targetDirUriString);
            return null;
        }

        String mimeType = "video/" + Constants.RECORDING_FILE_EXTENSION; // Adjust if needed
        String displayName = internalSourceFile.getName(); // Use the final processed name

        // Create destination file (overwrite might need check/delete first if needed)
        DocumentFile existingFile = targetDir.findFile(displayName);
        if(existingFile != null){
            Log.w(TAG,"SAF Move: Destination file already exists. Deleting first.");
            if(!existingFile.delete()) { Log.e(TAG,"SAF Move: Failed to delete existing file."); return null; }
        }

        DocumentFile newSafFile = targetDir.createFile(mimeType, displayName);
        if (newSafFile == null || !newSafFile.exists()) {
            Log.e(TAG, "moveInternalFileToSAF: Failed to create destination file in SAF: " + displayName);
            return null;
        }
        Log.d(TAG,"moveInternalFileToSAF: Created destination SAF file: " + newSafFile.getUri());


        // Copy content using streams
        InputStream inputStream = null;
        OutputStream outputStream = null;
        try {
            inputStream = new FileInputStream(internalSourceFile);
            outputStream = resolver.openOutputStream(newSafFile.getUri());
            if (outputStream == null) {
                throw new IOException("Failed to open output stream for SAF URI: " + newSafFile.getUri());
            }

            byte[] buffer = new byte[8192]; // 8K buffer
            int bytesRead;
            long totalBytesCopied = 0;
            while ((bytesRead = inputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, bytesRead);
                totalBytesCopied += bytesRead;
            }
            Log.d(TAG,"moveInternalFileToSAF: Copied " + totalBytesCopied + " bytes to " + newSafFile.getName());
            return newSafFile.getUri(); // Copy successful, return the URI of the new file

        } catch (IOException e) {
            Log.e(TAG, "moveInternalFileToSAF: Error during file copy to SAF", e);
            // Attempt to delete the partially created/failed SAF file
            if (newSafFile.exists() && !newSafFile.delete()) {
                Log.w(TAG, "Could not delete partially written SAF file: "+newSafFile.getName());
            }
            return null; // Copy failed
        } finally {
            // Close streams safely
            try { if (inputStream != null) inputStream.close(); } catch (IOException e) { /* Ignore */ }
            try { if (outputStream != null) outputStream.close(); } catch (IOException e) { /* Ignore */ }
        }
    }


    // --- MediaRecorder & Camera Session Setup ---
    // Updated setupMediaRecorder to target EXTERNAL cache first

    // ----- Fix Start for this method(setupMediaRecorder_saf_pfd_support)-----
    private void setupMediaRecorder() throws IOException {
        Log.d(TAG, "Setting up MediaRecorder for segment " + currentSegmentNumber + "...");
        releaseMediaRecorderSafely();
        try {
            Thread.sleep(500);
            Log.d(TAG, "Waited 500ms after media recorder release.");
        } catch (InterruptedException e) {
            Log.w(TAG, "Delay interrupted while waiting to create new MediaRecorder", e);
            Thread.currentThread().interrupt();
        }
        mediaRecorder = new MediaRecorder();
        Log.d(TAG, "New MediaRecorder instance created for segment " + currentSegmentNumber);
        mediaRecorder.setOnInfoListener(mediaRecorderInfoListener);
        mediaRecorder.setOnErrorListener((mr, what, extra) -> {
            Log.e(TAG, "MediaRecorder error: what=" + what + ", extra=" + extra + " for segment " + currentSegmentNumber);
            new Handler(Looper.getMainLooper()).post(() -> {
                Log.e(TAG, "Handling MediaRecorder error on main service thread for segment " + currentSegmentNumber);
                stopRecording();
                Toast.makeText(getApplicationContext(), "Recording error (segment " + currentSegmentNumber + ", code: " + what + ")", Toast.LENGTH_LONG).show();
            });
        });
        if (sharedPreferencesManager.isRecordAudioEnabled()) {
            int maxRetries = 3;
            int retryDelayMs = 500;
            boolean audioSourceSet = false;
            String audioInputSource = sharedPreferencesManager.getAudioInputSource();
            int requestedSource = MediaRecorder.AudioSource.MIC; // Default
            boolean wiredMicAvailable = isWiredMicConnected();
            if (audioInputSource.equals(SharedPreferencesManager.AUDIO_INPUT_SOURCE_WIRED)) {
                if (wiredMicAvailable) {
                    requestedSource = MediaRecorder.AudioSource.CAMCORDER; // Use CAMCORDER for wired
                } else {
                    Log.w(TAG, "User selected wired mic, but none detected. Falling back to phone mic.");
                    Toast.makeText(getApplicationContext(), getString(R.string.audio_input_source_wired_not_connected), Toast.LENGTH_SHORT).show();
                    requestedSource = MediaRecorder.AudioSource.MIC;
                }
            } else {
                requestedSource = MediaRecorder.AudioSource.MIC;
            }
            for (int i = 0; i < maxRetries; i++) {
                try {
                    mediaRecorder.setAudioSource(requestedSource);
                    audioSourceSet = true;
                    Log.d(TAG, "setAudioSource (" + requestedSource + ") successful on attempt " + (i + 1));
                    break;
                } catch (RuntimeException e) {
                    Log.e(TAG, "setAudioSource failed on attempt " + (i + 1) + "/" + maxRetries + ": " + e.getMessage());
                    if (i < maxRetries - 1) {
                        try {
                            Log.d(TAG, "Resetting MediaRecorder before retrying setAudioSource due to RuntimeException.");
                            mediaRecorder.reset();
                            Thread.sleep(retryDelayMs);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            Log.w(TAG, "Audio setup retry delay interrupted", ie);
                            throw new IOException("Audio setup retry interrupted", ie);
                        } catch (Exception resetEx) {
                            Log.e(TAG, "Exception during MediaRecorder.reset() in audio setup retry: " + resetEx.getMessage());
                            throw new IOException("Failed to reset MediaRecorder during audio setup retry", e);
                        }
                    } else {
                        Log.e(TAG, "setAudioSource failed after all retries. Forcing fallback to phone mic.");
                        // Fallback to phone mic as last resort
                        try {
                            mediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
                            audioSourceSet = true;
                            Log.w(TAG, "Fallback to phone mic audio source succeeded.");
                        } catch (Exception fallbackEx) {
                            Log.e(TAG, "Fallback to phone mic audio source failed.", fallbackEx);
                        }
                    }
                }
            }
            if (!audioSourceSet) {
                Log.e(TAG, "Failed to set audio source after retries, using phone mic as last resort.");
                try {
                    mediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
                } catch (Exception fallbackEx) {
                    Log.e(TAG, "Final fallback to phone mic audio source failed.", fallbackEx);
                }
            }
        }
        mediaRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
        mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
        String storageMode = sharedPreferencesManager.getStorageMode();
        if (SharedPreferencesManager.STORAGE_MODE_CUSTOM.equals(storageMode)) {
            // SAF/DocumentFile mode
            String customUriString = sharedPreferencesManager.getCustomStorageUri();
            if (customUriString == null) {
                handleStorageError("Custom storage selected but URI is null");
                throw new IOException("Custom storage selected but URI is null");
            }
            Uri treeUri = Uri.parse(customUriString);
            DocumentFile pickedDir = DocumentFile.fromTreeUri(this, treeUri);
            if (pickedDir == null || !pickedDir.canWrite()) {
                handleStorageError("Cannot write to selected custom directory");
                throw new IOException("Cannot write to selected custom directory");
            }
            // Create a new DocumentFile for this segment
            String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
            String segmentSuffix = String.format(Locale.US, "_%03d", currentSegmentNumber);
            String tempBaseFilename = "temp_" + timestamp + segmentSuffix + "." + Constants.RECORDING_FILE_EXTENSION;
            currentRecordingDocFile = pickedDir.createFile("video/" + Constants.RECORDING_FILE_EXTENSION, tempBaseFilename);
            if (currentRecordingDocFile == null || !currentRecordingDocFile.exists()) {
                Log.e(TAG, "Failed to create DocumentFile in SAF: " + tempBaseFilename);
                handleStorageError("Failed to create file in custom directory");
                throw new IOException("Failed to create file in custom directory");
            }
            currentRecordingSafUri = currentRecordingDocFile.getUri();
            Log.i(TAG, "Output configured for SAF: " + currentRecordingSafUri.toString());
            // Open a ParcelFileDescriptor for this segment
            closeCurrentPfd();
            currentSegmentPfd = getContentResolver().openFileDescriptor(currentRecordingSafUri, "w");
            if (currentSegmentPfd == null) {
                Log.e(TAG, "Failed to open ParcelFileDescriptor for SAF URI: " + currentRecordingSafUri);
                throw new IOException("Failed to open ParcelFileDescriptor for SAF URI");
            }
            mediaRecorder.setOutputFile(currentSegmentPfd.getFileDescriptor());
        } else {
            // Internal storage mode
            File tempOutputFileForInternal = createOutputFile();
            if (currentRecordingSafUri != null) {
                throw new IOException("setNextOutputFile: SAF/DocumentFile not supported in this mode");
            } else if (tempOutputFileForInternal != null) {
            Log.d(TAG, "Configuring MediaRecorder output for Internal temp file: " + tempOutputFileForInternal.getAbsolutePath());
            mediaRecorder.setOutputFile(tempOutputFileForInternal.getAbsolutePath());
        } else {
            throw new IOException("createOutputFile() did not configure any valid output (neither SAF URI nor Internal temp file).");
        }
        }
        VideoCodec codec = sharedPreferencesManager.getVideoCodec();
        mediaRecorder.setVideoEncoder(codec.getEncoder());
        Size resolution = sharedPreferencesManager.getCameraResolution();
        boolean isLandscape = sharedPreferencesManager.isOrientationLandscape();
        Log.d(TAG, "Setting video size directly from resolution: " + resolution.getWidth() + "x" + resolution.getHeight() + " for isLandscape=" + isLandscape);
        mediaRecorder.setVideoSize(resolution.getWidth(), resolution.getHeight());
        
        // ----- Fix Start for this method(setupMediaRecorder)-----
        // Determine if this is a front camera
        CameraType cameraType = sharedPreferencesManager.getCameraSelection();
        boolean isFrontCamera = (cameraType == CameraType.FRONT);
        
        // Set orientation hint based on camera type and device orientation
        if (isFrontCamera) {
            // Front camera needs different orientation hint to avoid upside-down recording
        if (!isLandscape) {
                mediaRecorder.setOrientationHint(270); // 270 degrees for portrait front camera
        } else {
                mediaRecorder.setOrientationHint(180); // 180 degrees for landscape front camera
        }
            Log.d(TAG, "Front camera: Setting orientation hint to " + (isLandscape ? "180" : "270") + " degrees");
        } else {
            // Back camera uses normal orientation
            if (!isLandscape) {
                mediaRecorder.setOrientationHint(90); // 90 degrees for portrait back camera
            } else {
                mediaRecorder.setOrientationHint(0); // 0 degrees for landscape back camera
            }
            Log.d(TAG, "Back camera: Setting orientation hint to " + (isLandscape ? "0" : "90") + " degrees");
        }
        // ----- Fix End for this method(setupMediaRecorder)-----
        
        int bitRate = getVideoBitrate();
        int frameRate = sharedPreferencesManager.getVideoFrameRate();
        mediaRecorder.setVideoEncodingBitRate(bitRate);
        mediaRecorder.setVideoFrameRate(frameRate);
        if (sharedPreferencesManager.isRecordAudioEnabled()) {
            int audioBitrate = sharedPreferencesManager.getAudioBitrate();
            int audioSamplingRate = sharedPreferencesManager.getAudioSamplingRate();
            mediaRecorder.setAudioEncodingBitRate(audioBitrate);
            mediaRecorder.setAudioSamplingRate(audioSamplingRate);
            mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
        }
        
        // ----- Fix Start for this method(setupMediaRecorder_location_embedding) -----
        // Add location metadata to video if enabled
        if (sharedPreferencesManager.isLocationEmbeddingEnabled()) {
            boolean locationApplied = false;
            
            // First attempt: Use existing geotagHelper if available
            if (geotagHelper != null) {
                boolean success = geotagHelper.applyLocationToRecorder(mediaRecorder);
                if (success) {
                    Log.d(TAG, "GeotagHelper: Successfully applied location to MediaRecorder");
                    locationApplied = true;
                } else {
                    Log.w(TAG, "GeotagHelper: Failed to apply location to MediaRecorder on first attempt");
                }
            } else {
                Log.d(TAG, "GeotagHelper is null, will create new instance");
            }
            
            // Second attempt: If first attempt failed or geotagHelper was null, create and try again
            if (!locationApplied) {
                try {
                    // Force creation of a new GeotagHelper
                    Log.d(TAG, "GeotagHelper: Creating new instance and starting updates");
                    geotagHelper = new GeotagHelper(this);
                    boolean started = geotagHelper.startUpdates();
                    Log.d(TAG, "GeotagHelper: Updates started: " + started);
                    
                    // Try to apply location immediately
                    boolean success = geotagHelper.applyLocationToRecorder(mediaRecorder);
                    if (success) {
                        Log.d(TAG, "GeotagHelper: Successfully applied location on second attempt");
                        locationApplied = true;
                    } else {
                        Log.w(TAG, "GeotagHelper: Failed to apply location on second attempt");
                    }
                } catch (Exception e) {
                    Log.e(TAG, "GeotagHelper: Error during initialization", e);
                }
            }
            
            // Third attempt: Background thread with delay if previous attempts failed
            if (!locationApplied) {
                Log.d(TAG, "GeotagHelper: Scheduling delayed attempt to apply location");
                new Thread(() -> {
                    try {
                        // Give more time for location to become available
                        Thread.sleep(2000);
                        if (mediaRecorder != null && geotagHelper != null) {
                            boolean retrySuccess = geotagHelper.applyLocationToRecorder(mediaRecorder);
                            Log.d(TAG, "GeotagHelper: Delayed retry applying location was " + 
                                (retrySuccess ? "successful" : "unsuccessful"));
                        } else {
                            Log.w(TAG, "GeotagHelper: Delayed retry aborted - resources no longer available");
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Error during delayed location retry", e);
                    }
                }).start();
            }
            
            Log.d(TAG, "GeotagHelper: Location embedding setup completed, immediate success: " + locationApplied);
        } else {
            Log.d(TAG, "GeotagHelper: Location embedding disabled in preferences");
        }
        // ----- Fix Ended for this method(setupMediaRecorder_location_embedding) -----
        
        if (isVideoSplittingEnabled && videoSplitSizeBytes > 0) {
            Log.d(TAG, "Setting MediaRecorder max file size to: " + videoSplitSizeBytes + " bytes for segment " + currentSegmentNumber);
            mediaRecorder.setMaxFileSize(videoSplitSizeBytes);
        } else {
            mediaRecorder.setMaxFileSize(0);
            Log.d(TAG, "Video splitting not enabled or size invalid, setting max file size to 0 (no limit).");
        }
        try {
            mediaRecorder.prepare();
            Log.d(TAG, "MediaRecorder prepared successfully for segment " + currentSegmentNumber);
        } catch (IOException e) {
            Log.e(TAG, "IOException preparing MediaRecorder for segment " + currentSegmentNumber, e);
            releaseMediaRecorderSafely();
            closeCurrentPfd();
            if (currentRecordingSafUri != null && currentRecordingDocFile != null && currentRecordingDocFile.exists()) {
                currentRecordingDocFile.delete();
            } else if (currentInternalTempFile != null && currentInternalTempFile.exists()) {
                currentInternalTempFile.delete();
            }
            throw e;
        }
    }
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

            // ----- Fix Start for this method(onOpened_setup_recorder_before_session)-----
            // MediaRecorder setup is now handled by startRecording() for the initial start,
            // and by proceedWithRolloverAfterOldSessionClosed() for rollovers.
            // Removing the setupMediaRecorder() call from here to prevent double setup and delays.
            // If mediaRecorder is null when createCameraPreviewSession is called, it will be caught there.
            Log.d(TAG, "onOpened: Camera device opened. Proceeding to createCameraPreviewSession. MediaRecorder should be ready.");
            // ----- Fix Ended for this method(onOpened_setup_recorder_before_session)-----

            // Start the capture session now that camera is open
            createCameraPreviewSession();
        }
        @Override
        public void onDisconnected(@NonNull CameraDevice camera) {
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
        if (mediaRecorder == null) {
            Log.e(TAG,"createCameraPreviewSession: mediaRecorder is null!");
            stopRecording(); // Cannot create session without recorder
            return;
        }
        Log.d(TAG,"createCameraPreviewSession: Creating session...");
        try {
            Surface recorderSurface = mediaRecorder.getSurface();
            
            // Setup a list of surfaces for the capture session
            List<Surface> surfaces = new ArrayList<>();
            surfaces.add(recorderSurface);
            
            // Add preview surface if available
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
            Log.d(TAG, "Standard capture session configured");
            if (cameraDevice == null) {
                Log.e(TAG, "Camera closed before session configured");
                session.close();
                return;
            }
            
            captureSession = session;
            
            try {
                // Set auto control mode
                captureRequestBuilder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);
                
                // Set torch/flash mode
                captureRequestBuilder.set(CaptureRequest.FLASH_MODE, 
                    isRecordingTorchEnabled ? 
                        CaptureRequest.FLASH_MODE_TORCH : 
                        CaptureRequest.FLASH_MODE_OFF);
                
                // Start repeating request
                session.setRepeatingRequest(captureRequestBuilder.build(), null, backgroundHandler);
                Log.d(TAG, "Started repeating request for standard session");
                
                // Handle recording state
                handleSessionConfigured();
            } catch (CameraAccessException e) {
                Log.e(TAG, "Error starting repeating request", e);
                stopRecording();
            }
        }
        
        @Override
        public void onConfigureFailed(@NonNull CameraCaptureSession session) {
            Log.e(TAG, "Standard capture session configuration failed");
            stopRecording();
        }
        
        @Override
        public void onClosed(@NonNull CameraCaptureSession session) {
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
            if (cameraDevice == null) {
                Log.e(TAG, "Camera closed before high-speed session configured");
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
                if (mediaRecorder != null) {
                    Surface recorderSurface = mediaRecorder.getSurface();
                    if (recorderSurface != null) {
                        surfaces.add(recorderSurface);
                    }
                }
                
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
    private void processLatestVideoFileWithWatermark() {
        Log.d(TAG,"processLatestVideoFileWithWatermark starting...");
        String inputUriOrPath;
        String outputUriOrPath;
        String tempFileName; // Needed for constructing output name
        Uri inputUri = null;
        File inputFile = null;
        Uri outputDirectoryUri = null; // Only for SAF output directory

        // isProcessingWatermark = true; // OLD FLAG - DO NOT USE - Handled by ffmpegProcessingTaskCount in other methods

        // Identify the source file/URI that was just recorded
        if (currentRecordingFile != null) {
            inputFile = currentRecordingFile;
            inputUriOrPath = inputFile.getAbsolutePath();
            tempFileName = inputFile.getName();
            Log.d(TAG,"Processing Internal File: " + inputUriOrPath);
        } else if (currentRecordingSafUri != null) {
            inputUri = currentRecordingSafUri;
            inputUriOrPath = inputUri.toString();
            if (currentRecordingDocFile != null) {
                tempFileName = currentRecordingDocFile.getName();
            } else { // Fallback to deriving from URI if doc file lost
                tempFileName = DocumentFile.fromSingleUri(this,inputUri).getName();
            }
            // Get parent directory for SAF output
            DocumentFile parentDir = DocumentFile.fromSingleUri(this,inputUri).getParentFile();
            if(parentDir != null) outputDirectoryUri = parentDir.getUri();

            Log.d(TAG,"Processing SAF URI: " + inputUriOrPath +", Temp Name: "+tempFileName);
        } else {
            Log.e(TAG, "processLatest: No valid recording file/URI tracked!");
            // isProcessingWatermark = false; // OLD FLAG - DO NOT USE at line ~1410
            return;
        }
        if(tempFileName == null) {
            Log.e(TAG, "Could not determine temporary filename, aborting processing.");
            // isProcessingWatermark = false; // OLD FLAG - DO NOT USE
            cleanupTemporaryFile(); // Attempt cleanup
            return;
        }
    // ----- Fix Ended for this method(processLatestVideoFileWithWatermark_comment_out_isProcessingWatermark_references)-----


        // Construct final output filename (always prefixed)
        String finalOutputName = tempFileName.replace("temp_", Constants.RECORDING_DIRECTORY + "_");

        // Determine and Create Output Target (Path or SAF URI)
        DocumentFile finalOutputDocFile = null; // For SAF cleanup on failure

        if (outputDirectoryUri != null) { // Custom SAF Location
            DocumentFile parentDir = DocumentFile.fromTreeUri(this, outputDirectoryUri);
            if (parentDir == null || !parentDir.canWrite()) {
                handleProcessingError("Cannot write to SAF output directory: " + outputDirectoryUri, inputUriOrPath);
                return;
            }
            // Check if final file already exists (shouldn't normally, but handle anyway)
            if(parentDir.findFile(finalOutputName) != null){
                Log.w(TAG, "Final output file already exists in SAF dir: "+finalOutputName+". Overwriting might fail or take time.");
                // FFmpeg might handle overwrite depending on version/options
            }

            finalOutputDocFile = parentDir.createFile("video/" + Constants.RECORDING_FILE_EXTENSION, finalOutputName);
            if (finalOutputDocFile == null || !finalOutputDocFile.exists()) {
                handleProcessingError("Failed to create output SAF file: " + finalOutputName + " in " + outputDirectoryUri, inputUriOrPath);
                return;
            }
            outputUriOrPath = finalOutputDocFile.getUri().toString();
            Log.d(TAG,"Output target: SAF URI = "+ outputUriOrPath);

        } else { // Internal Storage Location
            File videoDir = new File(getExternalFilesDir(null), Constants.RECORDING_DIRECTORY);
            File outputFile = new File(videoDir, finalOutputName);
            outputUriOrPath = outputFile.getAbsolutePath();
            Log.d(TAG,"Output target: Internal Path = " + outputUriOrPath);
        }

        // Prepare and Execute FFmpeg Command
        String watermarkOption = sharedPreferencesManager.getWatermarkOption();
        String ffmpegCommand;

        if ("no_watermark".equals(watermarkOption)) {
            Log.d(TAG,"No watermark selected, using copy codec.");
            // Use -movflags +faststart potentially?
            ffmpegCommand = String.format("-i %s -codec copy -y %s", inputUriOrPath, outputUriOrPath); // -y overwrites
        } else {
            Log.d(TAG,"Applying watermark: " + watermarkOption);
            // Fetch watermark text and format it
            String fontPath = getFilesDir().getAbsolutePath() + "/ubuntu_regular.ttf"; // Ensure font exists
            // Enhanced logging: Check and log font file details
            File fontFile = new File(fontPath);
            if(!fontFile.exists()){ 
                Log.e(TAG,"FFMPEG ERROR: Font file missing at: "+fontPath); 
            } else {
                Log.i(TAG, "FFMPEG FONT: Found font file at " + fontPath + " (size: " + fontFile.length() + " bytes)");
            }
            String watermarkText;
            boolean isLocationEnabled = sharedPreferencesManager.isLocalisationEnabled();
            String locationText = isLocationEnabled ? getLocationData() : "";
            switch (watermarkOption) {
                case "timestamp": watermarkText = getCurrentTimestamp() + locationText; break;
                default: watermarkText = "Captured by FadCam - " + getCurrentTimestamp() + locationText; break;
            }
            watermarkText = convertArabicNumeralsToEnglish(watermarkText);
            String escapedWatermarkText = escapeFFmpegString(watermarkText);
            String escapedFontPath = escapeFFmpegPath(fontPath);

            int fontSize = getFontSizeBasedOnBitrate();
            String fontSizeStr = convertArabicNumeralsToEnglish(String.valueOf(fontSize));
            int frameRates = sharedPreferencesManager.getVideoFrameRate();
            int bitratesEstimated = getVideoBitrate();

            // ----- Fix Start for this method(processLatestVideoFileWithWatermark_codec_fix)-----
            // Retrieve the codec string for ffmpeg from the selected VideoCodec
            VideoCodec videoCodec = sharedPreferencesManager.getVideoCodec();
            String ffmpegCodec = videoCodec.getFfmpeg();
            // Build FFmpeg command carefully
            ffmpegCommand = String.format(
                    Locale.US, // Use US Locale for number formatting consistency
                    "-i %s -r %d -vf \"drawtext=text='%s':x=10:y=10:fontsize=%s:fontcolor=white:fontfile='%s':escape_text=0\" -q:v 0 -codec:v %s -b:v %d -codec:a copy -y %s",
                    inputUriOrPath,
                    frameRates,
                    escapedWatermarkText,
                    fontSizeStr,
                    escapedFontPath,
                    ffmpegCodec,
                    bitratesEstimated,
                    outputUriOrPath
            );
            // Enhanced logging: Log the full FFmpeg command for debugging
            Log.i(TAG, "FFMPEG COMMAND (legacy watermark): " + ffmpegCommand);
            // ----- Fix Ended for this method(processLatestVideoFileWithWatermark_codec_fix)-----
        }

        executeFFmpegCommand(ffmpegCommand, inputUriOrPath, outputUriOrPath); // Pass both input and potential failed SAF output path
    }

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
        currentInternalTempFile = null;
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
        // Enhanced logging: Log the full FFmpeg command for debugging
        Log.i(TAG, "FFMPEG COMMAND (legacy flow): " + ffmpegCommand);
        // This method appears to be part of an older/alternative processing flow.
        // It is NOT the primary path for segment processing, which uses executeFFmpegAsync.
        // If this method were to be activated and manage an FFmpeg task,
        // it would need to increment ffmpegProcessingTaskCount before calling FFmpegKit.executeAsync,
        // and decrement it in the callback, similar to how executeFFmpegAsync works.

        // Example structure if it were to manage a task:
        // ffmpegProcessingTaskCount.incrementAndGet();
        // Log.d(TAG, "FFmpeg task count incremented via executeFFmpegCommand for: " + inputUriOrPath);

        FFmpegKit.executeAsync(ffmpegCommand, session -> {
            ReturnCode returnCode = session.getReturnCode();
            boolean success = ReturnCode.isSuccess(returnCode);
            Log.d(TAG, "FFmpeg session (via executeFFmpegCommand) finished - Success: " + success + ", RC: " + returnCode);

            if (success) {
                Log.i(TAG, "FFmpeg process successful (via executeFFmpegCommand). Output: " + outputUriOrPath);
                cleanupTemporaryFile(); // Delete the temp file (inputUriOrPath)

                // Optional: Update records UI / Media Scanner if needed
            } else {
                Log.e(TAG, "FFmpeg (via executeFFmpegCommand) failed! State: " + session.getState() + ", Return Code: " + returnCode);
                Log.e(TAG, "FFMPEG STDERR OUTPUT (legacy flow): \n" + session.getAllLogsAsString());
                Log.e(TAG, "FFMPEG FAILED COMMAND (legacy flow): " + ffmpegCommand);
                Toast.makeText(this, "Error processing video (via executeFFmpegCommand)", Toast.LENGTH_LONG).show();

                // Try to cleanup the FAILED output file if it was created via SAF
                if (outputUriOrPath != null && outputUriOrPath.startsWith("content://")) {
                    try {
                        DocumentFile failedOutput = DocumentFile.fromSingleUri(this, Uri.parse(outputUriOrPath));
                        if(failedOutput != null && failedOutput.exists() && failedOutput.delete()){
                            Log.d(TAG,"Deleted failed SAF output file (via executeFFmpegCommand): "+ outputUriOrPath);
                        } else {
                            Log.w(TAG, "Could not delete failed SAF output file (via executeFFmpegCommand): "+outputUriOrPath);
                        }
                    } catch (Exception e) {
                        // ----- Fix Start for this method(executeFFmpegCommand_fix_string_literal)-----
                        Log.e(TAG,"Error deleting failed SAF output (via executeFFmpegCommand)", e);
                        // ----- Fix Ended for this method(executeFFmpegCommand_fix_string_literal)-----
                    }
                }
                // IMPORTANT: DO NOT delete the input temp file if processing failed.
                currentRecordingFile = null;
                currentRecordingSafUri = null;
                currentRecordingDocFile = null;
            }

            // If this method (executeFFmpegCommand) were managing an FFmpeg task lifecycle:
            // int tasksLeft = ffmpegProcessingTaskCount.decrementAndGet();
            // Log.d(TAG, "FFmpeg task (via executeFFmpegCommand) finished. Count decremented to: " + tasksLeft);

            // Check if service can stop now
            if (!isWorkingInProgress()) {
                Log.d(TAG,"FFmpeg (via executeFFmpegCommand) finished and no other work pending, stopping service.");
                stopSelf();
            } else {
                Log.d(TAG,"FFmpeg (via executeFFmpegCommand) finished, but service might still be recording/working.");
            }
        });
    }
    // ----- Fix Ended for this method(executeFFmpegCommand_full_rewrite)-----


    // Centralized Temp File Cleanup
    private void cleanupTemporaryFile() {
        Log.d(TAG,"cleanupTemporaryFile: Attempting cleanup of temporary recording file...");
        File tempToClean = currentInternalTempFile; // Use the instance variable

        if (tempToClean != null) {
            Log.d(TAG, "Cleaning up internal file: " + tempToClean.getAbsolutePath());
            if (tempToClean.exists()) {
                if (tempToClean.delete()) {
                    Log.d(TAG, "Deleted internal temp file: " + tempToClean.getName());
                } else {
                    Log.w(TAG, "Failed to delete internal temp file: " + tempToClean.getName());
                }
            } else {
                Log.w(TAG, "Internal temp file reference exists but file not found for cleanup.");
            }
            currentInternalTempFile = null; // Clear reference after attempt
        } else {
            Log.d(TAG,"No temporary file currently tracked for cleanup.");
        }
        // Removed logic for cleaning SAF temps as they are handled differently
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
        if(intent != null) {
            previewSurface = intent.getParcelableExtra("SURFACE");
            Log.d(TAG, "Preview surface updated: " + (previewSurface != null && previewSurface.isValid()));
        }
    }

    private void closeCurrentPfd() {
        if (currentSegmentPfd != null) {
            try { currentSegmentPfd.close(); } catch (Exception e) { Log.w(TAG, "Error closing currentSegmentPfd", e); }
            currentSegmentPfd = null;
        }
        if (nextSegmentPfd != null) {
            try { nextSegmentPfd.close(); } catch (Exception e) { Log.w(TAG, "Error closing nextSegmentPfd", e); }
            nextSegmentPfd = null;
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
    private final MediaRecorder.OnInfoListener mediaRecorderInfoListener = new MediaRecorder.OnInfoListener() {
        @Override
        public void onInfo(MediaRecorder mr, int what, int extra) {
            Log.d(TAG, "[OnInfoListener] what=" + what + ", extra=" + extra);
            try {
                if (what == 802 /* MEDIA_RECORDER_INFO_MAX_FILESIZE_APPROACHING */) {
                    Log.i(TAG, "[OnInfoListener] Max filesize approaching! Preparing next output file for seamless rollover.");
                    if (isVideoSplittingEnabled && videoSplitSizeBytes > 0 && !nextOutputFilePending) {
                        nextSegmentTempFile = createNextSegmentOutputFile();
                        if (SharedPreferencesManager.STORAGE_MODE_CUSTOM.equals(sharedPreferencesManager.getStorageMode())) {
                            // SAF mode: nextSegmentPfd is set in createNextSegmentOutputFile
                            if (nextSegmentPfd != null) {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                    mediaRecorder.setNextOutputFile(nextSegmentPfd.getFileDescriptor());
                                    nextOutputFilePending = true;
                                    Log.d(TAG, "[OnInfoListener] setNextOutputFile (APPROACHING) called for SAF PFD.");
                                }
                } else {
                                Log.e(TAG, "[OnInfoListener] Failed to open nextSegmentPfd for SAF. Stopping recording.");
                                new Handler(Looper.getMainLooper()).post(() -> stopRecording());
                            }
                        } else {
                            // Internal storage mode
                            if (nextSegmentTempFile != null) {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                    mediaRecorder.setNextOutputFile(nextSegmentTempFile);
                                    nextOutputFilePending = true;
                                    Log.d(TAG, "[OnInfoListener] setNextOutputFile (APPROACHING) called for internal file.");
                                }
                            } else {
                                Log.e(TAG, "[OnInfoListener] Failed to create next segment file for rollover (APPROACHING). Stopping recording.");
                                new Handler(Looper.getMainLooper()).post(() -> stopRecording());
                            }
                        }
                    }
                } else if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED) {
                    Log.i(TAG, "[OnInfoListener] Max filesize reached! Attempting setNextOutputFile rollover.");
                    if (isVideoSplittingEnabled && videoSplitSizeBytes > 0 && !nextOutputFilePending) {
                        nextSegmentTempFile = createNextSegmentOutputFile();
                        if (SharedPreferencesManager.STORAGE_MODE_CUSTOM.equals(sharedPreferencesManager.getStorageMode())) {
                            if (nextSegmentPfd != null) {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                    mediaRecorder.setNextOutputFile(nextSegmentPfd.getFileDescriptor());
                                    nextOutputFilePending = true;
                                    Log.d(TAG, "[OnInfoListener] setNextOutputFile (REACHED) called for SAF PFD.");
                                }
                            } else {
                                Log.e(TAG, "[OnInfoListener] Failed to open nextSegmentPfd for SAF. Stopping recording.");
                                new Handler(Looper.getMainLooper()).post(() -> stopRecording());
                            }
                        } else {
                            if (nextSegmentTempFile != null) {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                    mediaRecorder.setNextOutputFile(nextSegmentTempFile);
                                    nextOutputFilePending = true;
                                    Log.d(TAG, "[OnInfoListener] setNextOutputFile (REACHED) called for internal file.");
                                }
                            } else {
                                Log.e(TAG, "[OnInfoListener] Failed to create next segment file for rollover (REACHED). Stopping recording.");
                                new Handler(Looper.getMainLooper()).post(() -> stopRecording());
                            }
                        }
                    } else if (!isVideoSplittingEnabled || videoSplitSizeBytes <= 0) {
                        Log.w(TAG, "[OnInfoListener] Max filesize reached, but splitting not enabled/configured. Stopping recording.");
                        new Handler(Looper.getMainLooper()).post(() -> stopRecording());
                    }
                } else if (what == 803 /* MEDIA_RECORDER_INFO_NEXT_OUTPUT_FILE_STARTED */) {
                    Log.i(TAG, "[OnInfoListener] Next output file started. Advancing segment number and updating file tracking.");
                    // SAF: Close previous segment PFD
                    if (SharedPreferencesManager.STORAGE_MODE_CUSTOM.equals(sharedPreferencesManager.getStorageMode())) {
                        if (currentSegmentPfd != null) {
                            try { currentSegmentPfd.close(); } catch (Exception e) { Log.w(TAG, "Error closing previous segment PFD", e); }
                            currentSegmentPfd = nextSegmentPfd;
                            nextSegmentPfd = null;
                        }
                        // Queue the completed SAF segment for FFmpeg processing
                        if (currentRecordingDocFile != null && currentRecordingDocFile.exists() && currentRecordingDocFile.length() > 0) {
                            Log.d(TAG, "[OnInfoListener] Queuing completed SAF segment for FFmpeg: " + currentRecordingDocFile.getUri());
                            segmentProcessingQueue.add(currentRecordingDocFile);
                            processNextSegmentInQueue();
                        } else {
                            Log.w(TAG, "[OnInfoListener] Previous SAF segment missing or empty, skipping FFmpeg queue.");
                        }
                        // ----- Fix Start for this method(OnInfoListener_update_current_docfile_on_rollover) -----
                        // Update currentRecordingDocFile and currentRecordingSafUri to the new segment
                        currentRecordingDocFile = nextRecordingDocFile;
                        currentRecordingSafUri = (currentRecordingDocFile != null) ? currentRecordingDocFile.getUri() : null;
                        nextRecordingDocFile = null;
                        // ----- Fix Ended for this method(OnInfoListener_update_current_docfile_on_rollover) -----
                    } else {
                        // Internal storage mode
                        if (currentInternalTempFile != null && currentInternalTempFile.exists() && currentInternalTempFile.length() > 0) {
                            Log.i(TAG, "FFMPEG SEGMENT: Queuing completed segment for processing: " + 
                                 currentInternalTempFile.getAbsolutePath() + 
                                 " (Size: " + (currentInternalTempFile.length() / 1024 / 1024) + " MB)");
                            segmentProcessingQueue.add(currentInternalTempFile);
                            processNextSegmentInQueue();
                        } else {
                            Log.e(TAG, "FFMPEG ERROR: Previous segment file missing or empty, skipping FFmpeg queue.");
                        }
                    }
                    currentSegmentNumber++;
                    currentInternalTempFile = nextSegmentTempFile;
                    nextSegmentTempFile = null;
                    nextOutputFilePending = false; // Now it's safe to queue another next output file
                    Intent segmentIntent = new Intent(Constants.ACTION_RECORDING_SEGMENT_COMPLETE);
                    segmentIntent.putExtra(Constants.INTENT_EXTRA_FILE_URI, currentRecordingSafUri != null ? currentRecordingSafUri.toString() : (currentInternalTempFile != null ? Uri.fromFile(currentInternalTempFile).toString() : ""));
                    sendBroadcast(segmentIntent);
                    Log.d(TAG, "[OnInfoListener] Broadcasted segment complete for segment " + (currentSegmentNumber - 1));
                } else {
                    Log.d(TAG, "[OnInfoListener] Unhandled info event: what=" + what);
                }
            } catch (Exception e) {
                Log.e(TAG, "[OnInfoListener] Exception in OnInfoListener", e);
                new Handler(Looper.getMainLooper()).post(() -> stopRecording());
            }
        }
    };

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
    private File createOutputFile() {
        String storageMode = sharedPreferencesManager.getStorageMode();
        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
        // Format segment number with leading zeros (e.g., _001, _002)
        String segmentSuffix = String.format(Locale.US, "_%03d", currentSegmentNumber);

        String baseFilename = Constants.RECORDING_DIRECTORY + "_" + timestamp + segmentSuffix + "." + Constants.RECORDING_FILE_EXTENSION;
        String tempBaseFilename = "temp_" + timestamp + segmentSuffix + "." + Constants.RECORDING_FILE_EXTENSION;

        Log.d(TAG, "createOutputFile called for segment " + currentSegmentNumber + ". Base: " + baseFilename + ", Temp: " + tempBaseFilename);

        // Reset previous PFD and URIs
        closeCurrentPfd();
        currentRecordingFile = null;
        currentRecordingSafUri = null;
        currentRecordingDocFile = null;
        currentInternalTempFile = null; // Important to reset this too

        if (SharedPreferencesManager.STORAGE_MODE_CUSTOM.equals(storageMode)) {
            String customUriString = sharedPreferencesManager.getCustomStorageUri();
            if (customUriString == null) {
                handleStorageError("Custom storage selected but URI is null");
                return null; // Critical error, cannot proceed with SAF
            }
            Uri treeUri = Uri.parse(customUriString);
            DocumentFile pickedDir = DocumentFile.fromTreeUri(this, treeUri);
            if (pickedDir == null || !pickedDir.canWrite()) {
                handleStorageError("Cannot write to selected custom directory");
                return null; // Critical error
            }

            // Use the TEMP filename for SAF creation as well initially
            currentRecordingDocFile = pickedDir.createFile("video/" + Constants.RECORDING_FILE_EXTENSION, tempBaseFilename);
            if (currentRecordingDocFile == null || !currentRecordingDocFile.exists()) {
                Log.e(TAG, "Failed to create DocumentFile in SAF: " + tempBaseFilename);
                handleStorageError("Failed to create file in custom directory");
                return null; // Critical error
            }
            currentRecordingSafUri = currentRecordingDocFile.getUri();
            Log.i(TAG, "Output configured for SAF: " + currentRecordingSafUri.toString());
            // No internal temp file needed if directly writing to SAF PFD
            return null; // Signify SAF is being used

        } else { // Internal Storage
            File videoDir = new File(getExternalFilesDir(null), Constants.RECORDING_DIRECTORY);
            if (!videoDir.exists() && !videoDir.mkdirs()) {
                Log.e(TAG, "Cannot create internal recording directory: " + videoDir.getAbsolutePath());
                Toast.makeText(this, "Error creating internal storage directory", Toast.LENGTH_LONG).show();
                return null; // Critical error
            }
            // For internal storage, we record to a temp file first, then process to final name.
            // The temp file should also be in a cache location, not the final directory.
            File cacheDir = getExternalCacheDir();
            if (cacheDir == null) {
                Log.w(TAG, "External cache dir null, using internal cache for temp file.");
                cacheDir = new File(getCacheDir(), "recording_temp"); // Fallback to internal app cache
            } else {
                cacheDir = new File(cacheDir, "recording_temp"); // Subdir in external cache
            }
            if (!cacheDir.exists() && !cacheDir.mkdirs()) {
                Log.e(TAG, "Cannot create temp cache directory: " + cacheDir.getAbsolutePath());
                Toast.makeText(this, "Error creating temp cache directory", Toast.LENGTH_LONG).show();
                return null; // Critical error
            }

            currentInternalTempFile = new File(cacheDir, tempBaseFilename);
            Log.i(TAG, "Output (temp) configured for Internal: " + currentInternalTempFile.getAbsolutePath());
            currentRecordingFile = new File(videoDir, baseFilename); // This is the *final* intended path after processing
            return currentInternalTempFile; // Return the temp file to be written to by MediaRecorder
        }
    }
    // ----- Fix Ended for this method(createOutputFile_segmented_naming) -----

    // ----- Fix Start for this method(createNextSegmentOutputFile_saf_pfd_support)-----
    private File createNextSegmentOutputFile() {
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
            String segmentSuffix = String.format(Locale.US, "_%03d", currentSegmentNumber + 1);
            String tempBaseFilename = "temp_" + timestamp + segmentSuffix + "." + Constants.RECORDING_FILE_EXTENSION;
            DocumentFile nextDocFile = pickedDir.createFile("video/" + Constants.RECORDING_FILE_EXTENSION, tempBaseFilename);
            if (nextDocFile == null || !nextDocFile.exists()) {
                Log.e(TAG, "createNextSegmentOutputFile: Failed to create DocumentFile in SAF: " + tempBaseFilename);
                return null;
            }
            try {
                // Open a ParcelFileDescriptor for the next segment
                if (nextSegmentPfd != null) {
                    try { nextSegmentPfd.close(); } catch (Exception e) { Log.w(TAG, "Error closing previous nextSegmentPfd", e); }
                }
                nextSegmentPfd = getContentResolver().openFileDescriptor(nextDocFile.getUri(), "w");
                if (nextSegmentPfd == null) {
                    Log.e(TAG, "createNextSegmentOutputFile: Failed to open ParcelFileDescriptor for next SAF URI: " + nextDocFile.getUri());
                    return null;
                }
            } catch (Exception e) {
                Log.e(TAG, "createNextSegmentOutputFile: Exception opening PFD for next SAF URI", e);
                return null;
            }
            Log.i(TAG, "Next segment SAF file created: " + nextDocFile.getUri());
            // ----- Fix Start for this method(createNextSegmentOutputFile_track_next_docfile) -----
            nextRecordingDocFile = nextDocFile;
            // ----- Fix Ended for this method(createNextSegmentOutputFile_track_next_docfile) -----
            // For SAF, we return null here, but the PFD is set for use in setNextOutputFile
            return null;
        } else {
            // Internal storage mode
            String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
            String segmentSuffix = String.format(Locale.US, "_%03d", currentSegmentNumber + 1);
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
            processAndMoveVideo((File) nextSegment, null);
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
            processAndMoveVideo(tempCacheFile, safDocFile.getUri());
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
            if (mediaRecorder != null && geotagHelper != null && 
                (recordingState == RecordingState.IN_PROGRESS || recordingState == RecordingState.PAUSED)) {
                try {
                    // Try immediately
                    boolean immediateSuccess = geotagHelper.applyLocationToRecorder(mediaRecorder);
                    Log.d(TAG, "Applied location to active MediaRecorder: " + immediateSuccess);
                    
                    // Also schedule a delayed attempt for higher chance of success
                    new Thread(() -> {
                        try {
                            Thread.sleep(1500);
                            if (mediaRecorder != null && geotagHelper != null &&
                                (recordingState == RecordingState.IN_PROGRESS || recordingState == RecordingState.PAUSED)) {
                                boolean success = geotagHelper.applyLocationToRecorder(mediaRecorder);
                                Log.d(TAG, "Applied location to active MediaRecorder (delayed): " + success);
                            }
                        } catch (Exception e) {
                            Log.e(TAG, "Error in delayed location application", e);
                        }
                    }).start();
                } catch (Exception e) {
                    Log.e(TAG, "Error applying location after reinitialization", e);
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
                mediaRecorder.start();
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

}