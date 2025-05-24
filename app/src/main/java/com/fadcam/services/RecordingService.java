package com.fadcam.services;

import android.Manifest;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
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
import com.fadcam.ui.RecordsAdapter;

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

    // File / URI tracking
    private File currentRecordingFile; // Track file path if internal
    private Uri currentRecordingSafUri; // Track SAF URI if custom
    private DocumentFile currentRecordingDocFile; // Track DocumentFile if custom
    private ParcelFileDescriptor currentParcelFileDescriptor; // Track PFD if custom

    private RecordingState recordingState = RecordingState.NONE;
    private AtomicInteger ffmpegProcessingTaskCount = new AtomicInteger(0);

    // ----- Fix Start for this class (RecordingService_video_splitting_imports_and_fields) -----
    private boolean isVideoSplittingEnabled = false;
    private long videoSplitSizeBytes = -1L; // -1L means no limit by default, stored in bytes
    private int currentSegmentNumber = 1; // Start with segment 1
    // ----- Fix Ended for this class (RecordingService_video_splitting_imports_and_fields) -----

    // ----- Fix Start for this class (RecordingService) -----
    // Re-introduce isRecordingTorchEnabled to manage torch state *during an active recording session only*
    private boolean isRecordingTorchEnabled = false;
    // ----- Fix Ended for this class (RecordingService) -----

    private CameraManager cameraManager; // Primary camera manager
    private Handler backgroundHandler; // For camera operations

    private long recordingStartTime;

    private SharedPreferencesManager sharedPreferencesManager; // Your settings manager
    private File currentInternalTempFile;

    // --- Lifecycle Methods ---
    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "onCreate: Service creating...");
        // Initialize essential components first
        sharedPreferencesManager = SharedPreferencesManager.getInstance(getApplicationContext());
        locationHelper = new LocationHelper(getApplicationContext()); // Assuming needed
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
    }

    // --- onStartCommand (Ensure START action ignores processing state) ---
    // ----- Fix Start for this method(onStartCommand_video_splitting) -----
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "onStartCommand received: Action=" + (intent != null ? intent.getAction() : "null"));
        if (intent == null || intent.getAction() == null) { 
            Log.w(TAG, "onStartCommand: Intent or action is null.");
            if (!isWorkingInProgress()) stopSelf(); // Stop if idle and bad intent
            return START_NOT_STICKY; 
        }

        String action = intent.getAction();
        switch (action) {
            case Constants.INTENT_ACTION_START_RECORDING:
                if (recordingState != RecordingState.NONE) {
                    Log.w(TAG,"Start requested, but already RECORDING/PAUSED/STARTING. State: " + recordingState + ". Ignoring.");
                    broadcastOnRecordingStateCallback(); // Notify UI of current state
                    return START_STICKY; // Remain active
                }
                
                Log.i(TAG,"Handling START_RECORDING intent. Service recording state is NONE.");
                recordingState = RecordingState.STARTING; // Set state to STARTING

                // Load video splitting preferences
                if (sharedPreferencesManager != null) {
                    isVideoSplittingEnabled = sharedPreferencesManager.isVideoSplittingEnabled();
                    int splitSizeMb = sharedPreferencesManager.getVideoSplitSizeMb();
                    if (isVideoSplittingEnabled && splitSizeMb > 0) {
                        videoSplitSizeBytes = (long) splitSizeMb * 1024 * 1024; // Convert MB to Bytes
                        Log.d(TAG, "Video splitting enabled. Size: " + splitSizeMb + "MB (" + videoSplitSizeBytes + " Bytes)");
                    } else {
                        videoSplitSizeBytes = -1L; // Disable splitting if not enabled or invalid size
                        Log.d(TAG, "Video splitting disabled or size invalid.");
                    }
                } else {
                    Log.w(TAG, "SharedPreferencesManager is null in onStartCommand, cannot load splitting prefs. Defaults assumed.");
                    isVideoSplittingEnabled = false;
                    videoSplitSizeBytes = -1L;
                }
                currentSegmentNumber = 1; // Always reset segment number on new recording start

                isRecordingTorchEnabled = intent.getBooleanExtra(Constants.INTENT_EXTRA_INITIAL_TORCH_STATE, false);
                Log.d(TAG, "Initial torch state for recording session: " + isRecordingTorchEnabled);

                setupSurfaceTexture(intent);
                setupRecordingInProgressNotification(); // Show notification immediately
                startRecording(); // Attempt to start hardware recording
                break;

            case Constants.INTENT_ACTION_PAUSE_RECORDING:      
                pauseRecording(); 
                break;
            case Constants.INTENT_ACTION_RESUME_RECORDING:     
                setupSurfaceTexture(intent); 
                resumeRecording(); 
                break;
            case Constants.INTENT_ACTION_CHANGE_SURFACE:       
                setupSurfaceTexture(intent); 
                if (isRecording() || isPaused()) { 
                    createCameraPreviewSession(); 
                } 
                break;
            case Constants.INTENT_ACTION_STOP_RECORDING:       
                stopRecording(); 
                break;
            case Constants.BROADCAST_ON_RECORDING_STATE_REQUEST: 
                Log.d(TAG,"Resp state request"); 
                broadcastOnRecordingStateCallback(); 
                if (!isWorkingInProgress()) { 
                    stopSelf(); 
                } 
                break;
            case Constants.INTENT_ACTION_TOGGLE_RECORDING_TORCH: 
                toggleRecordingTorch(); 
                break;
            default: 
                Log.w(TAG, "Unknown action: " + action); 
                break;
        }
        return START_STICKY;
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
        Log.d(TAG, "onDestroy: Service destroying...");
        // Cleanup resources aggressively
        releaseRecordingResources(); // Centralized cleanup
        if (backgroundHandler != null) {
            backgroundHandler.getLooper().quitSafely(); // Stop background thread
            backgroundHandler = null;
        }
        cancelNotification(); // Ensure notification is removed
        Log.d(TAG, "Service destroyed.");
        super.onDestroy();
    }
    // --- End Lifecycle Methods ---


    // --- Core Recording Logic ---
    private void startRecording() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED ||
                ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG,"Permissions missing, cannot start recording.");
            Toast.makeText(this,"Permissions required for recording", Toast.LENGTH_LONG).show();
            // ----- Fix Start for this method(startRecording)-----
            if (recordingState == RecordingState.STARTING) {
                recordingState = RecordingState.NONE;
            }
            // ----- Fix Ended for this method(startRecording)-----
            stopSelf();
            return;
        }
        Log.d(TAG,"startRecording: Attempting to start.");
        try {
            setupMediaRecorder(); // Configure the recorder + storage path/URI
            if (mediaRecorder == null) { // Check if setup failed
                Log.e(TAG, "startRecording: MediaRecorder setup failed.");
                // ----- Fix Start for this method(startRecording)-----
                if (recordingState == RecordingState.STARTING) {
                    recordingState = RecordingState.NONE;
                }
                // ----- Fix Ended for this method(startRecording)-----
                return; // setupMediaRecorder handles errors/stopping service
            }
            openCamera(); // Open camera to start the capture session
        } catch (Exception e) {
            Log.e(TAG, "Error starting recording process", e);
            // ----- Fix Start for this method(startRecording)-----
            if (recordingState == RecordingState.STARTING) {
                recordingState = RecordingState.NONE;
            }
            // ----- Fix Ended for this method(startRecording)-----
            releaseRecordingResources(); // Clean up on failure
            // ----- Fix Start for this method(startRecording)-----
            Toast.makeText(this,"Error starting recording", Toast.LENGTH_LONG).show();
            // ----- Fix Ended for this method(startRecording)-----
            stopSelf();
        }
    }

    /**
     * Initiates the sequence to stop recording, release hardware resources,
     * notify the UI, and then potentially start background processing.
     */
    private void stopRecording() {
        Log.i(TAG, ">> stopRecording sequence initiated. Current state: " + recordingState);
        // ----- Fix Start for this method(stopRecording_check_ffmpeg_counter)-----
        if (recordingState == RecordingState.NONE && ffmpegProcessingTaskCount.get() == 0) { // Already stopped (or never started) AND not processing
            Log.w(TAG, "stopRecording called but state is already NONE and no ffmpeg tasks are active.");
        // ----- Fix Ended for this method(stopRecording_check_ffmpeg_counter)-----
        // ----- Fix Ended for this method(stopRecording)-----
            // Ensure pref is consistent and check if service should stop
            sharedPreferencesManager.setRecordingInProgress(false);
            if (!isWorkingInProgress()) stopSelf();
            return;
        }

        // --- Stage 1: Stop MediaRecorder & Update Internal State ---
        File tempFileToProcess = this.currentInternalTempFile; // Grab reference for processing later
        Uri safUriToProcess = this.currentRecordingSafUri; // Grab SAF URI if it was used
        DocumentFile docFileToProcess = this.currentRecordingDocFile; // And its DocumentFile

        final RecordingState previousState = recordingState;
        recordingState = RecordingState.NONE; // Set service state to NONE immediately
        sharedPreferencesManager.setRecordingInProgress(false); // Update persistent state

        boolean stoppedCleanly = false;
        if (mediaRecorder != null) {
            try {
                mediaRecorder.stop(); // Stop the recording engine
                stoppedCleanly = true;
                Log.d(TAG, "MediaRecorder stopped successfully.");
            } catch (Exception e) { // Catch RuntimeException or others
                Log.e(TAG, "Exception stopping MediaRecorder: " + e.getMessage());
                stoppedCleanly = true; // Treat as stopped even if unclean for resource release path
            }
            // DO NOT release mediaRecorder object yet.
        } else {
            Log.w(TAG, "stopRecording: mediaRecorder was already null.");
            stoppedCleanly = true; // Nothing to stop
        }

        // --- Stage 2: Stop Foreground Notification ---
        // Stop foreground state immediately to remove persistent notification sooner
        stopForeground(true);
        cancelNotification();
        Log.d(TAG,"Stopped foreground and cancelled notification.");

        // --- Stage 3: Release Camera HARDWARE Resources ---
        // This is crucial and must happen before signaling UI can become fully idle
        try { if (captureSession != null) { captureSession.close(); Log.d(TAG,"CaptureSession closed."); } } catch (Exception e) { Log.w(TAG,"Error closing captureSession",e); } finally { captureSession = null; }
        try { if (cameraDevice != null) { cameraDevice.close(); Log.d(TAG,"CameraDevice closed."); } } catch (Exception e) { Log.w(TAG,"Error closing cameraDevice",e); } finally { cameraDevice = null; }
        Log.i(TAG, "CameraDevice and CaptureSession close initiated.");

        // --- Stage 4: Send STOPPED broadcast (Signal to Fragment: Recording HW is done/releasing) ---
        broadcastOnRecordingStopped(); // <<< UI CAN NOW RESET TO IDLE <<<
        Log.d(TAG, "Sent BROADCAST_ON_RECORDING_STOPPED.");


        // --- Stage 5: Decide and Initiate Background Processing (if needed) ---
        boolean needsProcessing = (previousState != RecordingState.NONE) && stoppedCleanly;
        File actualFileToProcessForFFmpeg = null; // This will be the file in internal cache
        Uri originalSafUriForThisSegment = null; // This will be the original temp SAF URI if applicable

        if (needsProcessing) {
            if (tempFileToProcess != null && tempFileToProcess.exists() && tempFileToProcess.length() > 0) {
                Log.d(TAG, "stopRecording: Last segment was internal temp file: " + tempFileToProcess.getAbsolutePath());
                actualFileToProcessForFFmpeg = tempFileToProcess;
                // originalSafUriForThisSegment remains null
            } else if (safUriToProcess != null && docFileToProcess != null && docFileToProcess.exists() && docFileToProcess.length() > 0) {
                Log.d(TAG, "stopRecording: Last segment was SAF URI: " + safUriToProcess + ". Copying to cache for processing.");
                actualFileToProcessForFFmpeg = copySafUriToTempCacheForProcessing(getApplicationContext(), safUriToProcess, docFileToProcess.getName());
                if (actualFileToProcessForFFmpeg != null && actualFileToProcessForFFmpeg.exists()) {
                    originalSafUriForThisSegment = safUriToProcess; // This is the original temp SAF URI
                    // The original temp SAF DocumentFile (docFileToProcess) will be deleted by executeFFmpegAndMoveToSAF after successful processing and move.
                } else {
                    Log.e(TAG, "stopRecording: Failed to copy last SAF segment to cache. Cannot process: " + docFileToProcess.getName());
                    needsProcessing = false; // Can't process if copy failed
                }
            } else {
                Log.w(TAG, "stopRecording: No valid last segment file/URI found for processing.");
                needsProcessing = false;
            }
        }

        if (needsProcessing && actualFileToProcessForFFmpeg != null) {
            Log.i(TAG, "Proceeding to background video processing for: " + actualFileToProcessForFFmpeg.getName() + (originalSafUriForThisSegment != null ? " (Original SAF: "+originalSafUriForThisSegment+")" : ""));
            this.currentInternalTempFile = actualFileToProcessForFFmpeg; // For cleanup if internal, or for other refs (though primary processing uses arg)
            this.currentRecordingSafUri = null; // Clear these as they've been handled or copied
            this.currentRecordingDocFile = null;
            this.currentParcelFileDescriptor = null; // Ensure PFD is cleared
            
            processAndMoveVideo(actualFileToProcessForFFmpeg, originalSafUriForThisSegment); // Starts async FFmpeg
        } else {
            Log.w(TAG, "Skipping processing. PrevState:" + previousState + " CleanStop:" + stoppedCleanly + " (needsProcessing evaluated to: "+needsProcessing+")");
            cleanupTemporaryFile(); // Cleanup currentInternalTempFile if it was set and not processed
            // If SAF was used but copy failed or something went wrong, the temp SAF file might still be there.
            // The broadcast URI should reflect what was *attempted* or the original if nothing else.
            Uri broadcastUri = null;
            if (tempFileToProcess != null && tempFileToProcess.exists()) broadcastUri = Uri.fromFile(tempFileToProcess);
            else if (safUriToProcess != null) broadcastUri = safUriToProcess; // If internal temp was null, use the original SAF URI

            sendRecordingCompleteBroadcast(stoppedCleanly, broadcastUri, originalSafUriForThisSegment); // originalSafUriForThisSegment would be null if internal or if SAF copy failed before this branch
            checkIfServiceCanStop(); // Check if idle now
        }

        // --- Stage 6: Final MediaRecorder Object Release ---
        // Can be released now as its resources aren't needed for processing starting
        if (mediaRecorder != null) {
            releaseMediaRecorderSafely();
        }
        Log.i(TAG,"<< stopRecording sequence finished. >>");
    }

    // Inside RecordingService.java

    /**
     * Checks if the service has any active work (recording, pausing, or processing)
     * and calls stopSelf() if it is completely idle.
     * This should be called whenever a task completes (recording stops, processing finishes).
     */
    private void checkIfServiceCanStop() {
        // Read volatile flag and check state atomically as best as possible
        // ----- Fix Start for this method(checkIfServiceCanStop_use_ffmpeg_counter)-----
        // boolean isProcessing = isProcessingWatermark;
        int currentProcessingTasks = ffmpegProcessingTaskCount.get();
        // ----- Fix Ended for this method(checkIfServiceCanStop_use_ffmpeg_counter)-----
        // ----- Fix Start for this method(checkIfServiceCanStop)-----
        boolean isRecordingActiveOrStarting = (recordingState == RecordingState.IN_PROGRESS || recordingState == RecordingState.PAUSED || recordingState == RecordingState.STARTING);

        // ----- Fix Start for this method(checkIfServiceCanStop_use_ffmpeg_counter)-----
        Log.d(TAG, "checkIfServiceCanStop: RecordingState=" + recordingState + ", FfmpegTasks=" + currentProcessingTasks);

        // If NOT currently recording/paused/starting AND NOT currently processing...
        if (!isRecordingActiveOrStarting && currentProcessingTasks == 0) {
        // ----- Fix Ended for this method(checkIfServiceCanStop_use_ffmpeg_counter)-----
        // ----- Fix Ended for this method(checkIfServiceCanStop)-----
            Log.i(TAG, "No active recording or background processing detected. Stopping service.");
            // Add a slight delay before stopping? Optional, might help ensure broadcasts are fully handled.
            // new Handler(Looper.getMainLooper()).postDelayed(this::stopSelf, 100);
            stopSelf(); // Stop the service as its work is done.
        } else {
            Log.d(TAG, "Service continues running (Task active).");
        }
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

        if ("no_watermark".equals(watermarkOption)) {
            Log.d(TAG,"Building FFmpeg copy command (no watermark).");
            // When copying, no re-encoding happens, codec and pixel format don't matter.
            return String.format(Locale.US, "-i %s -codec copy -y %s", escapeFFmpegPath(inputPath), escapeFFmpegPath(outputPath));
        } else {
            Log.d(TAG,"Building FFmpeg watermark command.");

            // Always use H.264 hardware encoder for watermarking processing for compatibility
            String codecStringForProcessing = "h264_mediacodec";
            Log.i(TAG,"Watermark enabled. Using processing codec: " + codecStringForProcessing);

            try {
                // Existing logic to prepare watermark text, font, etc.
                String fontPath = getFilesDir().getAbsolutePath() + "/ubuntu_regular.ttf";
                File fontFile = new File(fontPath);
                if(!fontFile.exists()){ Log.e(TAG,"Font file missing at: "+fontPath); return null;}

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
                return String.format(Locale.US,
                        "-i %s -r %d -vf \"drawtext=text='%s':x=10:y=10:fontsize=%s:fontcolor=white:fontfile='%s'\" -q:v 0 -codec:v %s -b:v %d %s -codec:a copy -y %s",
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
                Log.e(TAG, "Error building watermark FFmpeg command",e);
                return null;
            }
        }
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
        ); // ** End of executeFFmpegAsync call **
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
    // Updated helper to run FFmpeg and broadcast completion
    // Updated helper to run FFmpeg and broadcast START/END processing states
    private void executeFFmpegAsync(String ffmpegCommand, File inputFile, Uri finalOutputUriIfKnown, Runnable onSuccess, Runnable onFailure) {
        // ** Get URI of the file ABOUT to be processed **
        Uri processingUri = Uri.fromFile(inputFile); // Temp file URI
        Log.d(TAG, "Preparing to process URI: " + processingUri.toString());

        // ** 1. Broadcast PROCESSING_STARTED **
        sendProcessingStateBroadcast(true, processingUri);

        // ----- Fix Start for this method(executeFFmpegAsync_remove_redundant_flag_set)-----
        // isProcessingWatermark = true; // Set processing flag // This is now managed by ffmpegProcessingTaskCount in caller
        // ----- Fix Ended for this method(executeFFmpegAsync_remove_redundant_flag_set)-----
        Log.d(TAG, "Executing FFmpeg Async for " + inputFile.getName() + ": " + ffmpegCommand);

        FFmpegKit.executeAsync(ffmpegCommand, session -> {
            // This lambda runs AFTER FFmpeg finishes
            boolean success = ReturnCode.isSuccess(session.getReturnCode());
            Uri resultUri = null; // The final resulting URI (temp or processed)
            String logTag = "FFmpegAsync Result";

            Log.d(logTag, "FFmpeg finished. Success: " + success + ", RC: " + session.getReturnCode());

            if (success) {
                // ... existing onSuccess logic ...
                try { if (onSuccess != null) onSuccess.run(); } catch (Exception e) { success = false; /*...*/ }
                resultUri = finalOutputUriIfKnown != null ? finalOutputUriIfKnown : determineSuccessUri(); // Try to get the final URI
                Log.i(logTag, "Processing SUCCESS. Result URI determined: " + resultUri);
            } else {
                // ... existing onFailure logic ...
                Log.e(logTag, "FFmpeg process FAILED! Logs: " + session.getAllLogsAsString());
                if (onFailure != null) { try { onFailure.run(); } catch (Exception e){/*...*/} }
                // On failure, the relevant file IS the input temp file
                resultUri = processingUri; // Use the original input URI for the broadcast
            }

            // ** 2. Broadcast PROCESSING_FINISHED **
            // Use the input URI here, as that's what the fragment currently shows and needs to update
            sendProcessingStateBroadcast(false, processingUri); // Signal end for the INPUT file URI
            // Optionally send the generic ACTION_RECORDING_COMPLETE as well, or consolidate logic
            // sendRecordingCompleteBroadcast(success, resultUri); // If this is still needed

            // ----- Fix Start for this method(executeFFmpegAsync_decrement_ffmpeg_counter)-----
            // isProcessingWatermark = false; // Reset flag
            int tasksLeft = ffmpegProcessingTaskCount.decrementAndGet();
            Log.d(TAG, "FFmpeg task finished. Count decremented to: " + tasksLeft);
            // ----- Fix Ended for this method(executeFFmpegAsync_decrement_ffmpeg_counter)-----

            // Check if service should stop
            if (!isWorkingInProgress()) {
                Log.d(TAG, "FFmpeg Async finished, no other work, stopping service.");
                stopSelf();
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
    private Uri determineSuccessUri() {
        // Option 1: If onSuccess writes to a known variable (like 'finalInternalOutputFile' or 'finalOutputDocFile's URI)
        // return finalOutputFileUri; // You need to capture this URI after the FFmpegKit.execute block completes

        // Option 2: Reconstruct it based on known inputs (less reliable if renaming occurs)
        String storageMode = sharedPreferencesManager.getStorageMode();
        if (SharedPreferencesManager.STORAGE_MODE_INTERNAL.equals(storageMode)) {
            File finalInternalDir = new File(getExternalFilesDir(null), Constants.RECORDING_DIRECTORY);
            // You NEED the final file name here - this might require passing it into executeFFmpegAsync or storing it
            String finalName = reconstructFinalNameFromTemp(); // Requires the original temp name was tracked
            if (finalName != null) {
                return Uri.fromFile(new File(finalInternalDir, finalName));
            }
        } else {
            String customUriString = sharedPreferencesManager.getCustomStorageUri();
            if (customUriString != null) {
                String finalName = reconstructFinalNameFromTemp();
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
    private String reconstructFinalNameFromTemp(){
        if(currentInternalTempFile != null){ // Assuming this holds the ORIGINAL temp file before processing
            return currentInternalTempFile.getName().replace("temp_", Constants.RECORDING_DIRECTORY + "_");
        }
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

    // ----- Fix Start for this method(setupMediaRecorder_video_splitting) -----
    private void setupMediaRecorder() throws IOException {
        Log.d(TAG, "Setting up MediaRecorder for segment " + currentSegmentNumber + "...");
        if (mediaRecorder == null) {
            mediaRecorder = new MediaRecorder();
        } else {
            mediaRecorder.reset(); // Reset existing recorder for reuse
        }

        // Set OnInfoListener to handle max filesize/duration
        mediaRecorder.setOnInfoListener(mediaRecorderInfoListener);

        // 1. Configure Sources
        if (sharedPreferencesManager.isRecordAudioEnabled()) {
            mediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
        }
        mediaRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE); // From camera preview

        // 2. Configure Format
        mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);

        // 3. Configure Output File/URI (Handled by createOutputFile and PFD logic below)
        File tempOutputFileForInternal = createOutputFile(); // This updates currentRecordingSafUri or currentInternalTempFile

        if (currentRecordingSafUri != null) { // SAF Storage selected
            Log.d(TAG, "Configuring MediaRecorder output for SAF URI: " + currentRecordingSafUri);
            currentParcelFileDescriptor = null; // Ensure it's null before try
            try {
                currentParcelFileDescriptor = getContentResolver().openFileDescriptor(currentRecordingSafUri, "rw");
                if (currentParcelFileDescriptor != null) {
                    mediaRecorder.setOutputFile(currentParcelFileDescriptor.getFileDescriptor());
                    Log.d(TAG, "MediaRecorder output successfully set to SAF URI: " + currentRecordingSafUri);
                } else {
                    throw new IOException("Failed to open ParcelFileDescriptor for SAF URI: " + currentRecordingSafUri);
                }
            } catch (IOException e) {
                Log.e(TAG, "IOException setting output for SAF URI: " + currentRecordingSafUri, e);
                closeCurrentPfd(); // Close PFD if open
                // Attempt to delete the failed SAF DocumentFile if it was created
                if (currentRecordingDocFile != null && currentRecordingDocFile.exists()) {
                    if (currentRecordingDocFile.delete()) {
                        Log.d(TAG, "Deleted partially created/failed SAF DocumentFile: " + currentRecordingDocFile.getName());
                    }
                }
                currentRecordingSafUri = null; // Nullify on error
                currentRecordingDocFile = null;
                throw e; // Re-throw to be caught by caller
            } 
            // Note: The PFD is NOT closed here. It's closed in closeCurrentPfd(), 
            // which is called when stopping, or when createOutputFile is called again for a new segment/recording.

        } else if (tempOutputFileForInternal != null) { // Internal Storage selected
            Log.d(TAG, "Configuring MediaRecorder output for Internal temp file: " + tempOutputFileForInternal.getAbsolutePath());
            mediaRecorder.setOutputFile(tempOutputFileForInternal.getAbsolutePath());
            // currentInternalTempFile is already set by createOutputFile()
        } else {
            // This case should ideally be prevented by createOutputFile returning null on critical errors
            throw new IOException("createOutputFile() did not configure any valid output (neither SAF URI nor Internal temp file).");
        }

        // 4. Configure Encoders & Parameters
        VideoCodec codec = sharedPreferencesManager.getVideoCodec();
        mediaRecorder.setVideoEncoder(codec.getEncoder());

        // ----- Fix Start for this method(setupMediaRecorder_direct_resolution_use)-----
        Size resolution = sharedPreferencesManager.getCameraResolution();
        boolean isLandscape = sharedPreferencesManager.isOrientationLandscape();

        // Directly use the resolution width and height, assuming SharedPreferencesManager.getCameraResolution()
        // returns dimensions appropriate for the selected orientation (e.g., 1080x1920 for portrait).
        Log.d(TAG, "Setting video size directly from resolution: " + resolution.getWidth() + "x" + resolution.getHeight() + " for isLandscape=" + isLandscape);
        mediaRecorder.setVideoSize(resolution.getWidth(), resolution.getHeight());

        if (!isLandscape) {
            mediaRecorder.setOrientationHint(90); // Portrait
        } else {
            mediaRecorder.setOrientationHint(0);  // Landscape (Default is 0, but explicit for clarity)
        }
        // ----- Fix Ended for this method(setupMediaRecorder_direct_resolution_use)-----

        int bitRate = getVideoBitrate(); // Uses your existing helper
        int frameRate = sharedPreferencesManager.getVideoFrameRate(); // Assuming getVideoFrameRate() is correct
        mediaRecorder.setVideoEncodingBitRate(bitRate);
        mediaRecorder.setVideoFrameRate(frameRate);

        if (sharedPreferencesManager.isRecordAudioEnabled()) {
            int audioBitrate = sharedPreferencesManager.getAudioBitrate();
            int audioSamplingRate = sharedPreferencesManager.getAudioSamplingRate();
            mediaRecorder.setAudioEncodingBitRate(audioBitrate);
            mediaRecorder.setAudioSamplingRate(audioSamplingRate);
            mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
        }

        // 5. Configure Max File Size for Splitting (if enabled)
        if (isVideoSplittingEnabled && videoSplitSizeBytes > 0) {
            Log.d(TAG, "Setting MediaRecorder max file size to: " + videoSplitSizeBytes + " bytes for segment " + currentSegmentNumber);
            mediaRecorder.setMaxFileSize(videoSplitSizeBytes);
        } else {
            mediaRecorder.setMaxFileSize(0); // 0 means no limit by size, rely on manual stop or other limits
            Log.d(TAG, "Video splitting not enabled or size invalid, setting max file size to 0 (no limit).");
        }

        // 6. Prepare MediaRecorder
        try {
            mediaRecorder.prepare();
            Log.d(TAG, "MediaRecorder prepared successfully for segment " + currentSegmentNumber);
        } catch (IOException e) {
            Log.e(TAG, "IOException preparing MediaRecorder for segment " + currentSegmentNumber, e);
            releaseMediaRecorderSafely(); // Release recorder on prepare failure
            closeCurrentPfd(); // Ensure PFD is closed if SAF was used
            // Cleanup the file that was being prepared for (temp internal or SAF doc)
            if (currentRecordingSafUri != null && currentRecordingDocFile != null && currentRecordingDocFile.exists()) {
                currentRecordingDocFile.delete();
            } else if (currentInternalTempFile != null && currentInternalTempFile.exists()) {
                currentInternalTempFile.delete();
            }
            throw e; // Re-throw to be handled by caller (startRecording)
        }
    }
    // ----- Fix Ended for this method(setupMediaRecorder_video_splitting) -----

// Replace the ENTIRE existing openCamera method in RecordingService.java with this corrected version:

    private void openCamera() {
        Log.d(TAG, "openCamera: Opening camera");
        // Use the class field 'cameraManager' consistently
        if (cameraManager == null) {
            Log.e(TAG,"openCamera: CameraManager (class field) is null.");
            stopSelf();
            return;
        }
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG,"openCamera: Camera permission denied.");
            // Consider sending a broadcast/showing toast if permission issue persists
            stopSelf();
            return;
        }

        CameraType selectedType = sharedPreferencesManager.getCameraSelection();
        String cameraToOpenId = null; // The ID we will eventually try to open

        try {
            // *** CORRECTION: Use 'cameraManager' field instead of undefined 'manager' ***
            String[] availableCameraIds = cameraManager.getCameraIdList();
            Log.d(TAG, "Available Camera IDs: " + Arrays.toString(availableCameraIds));

            if (selectedType == CameraType.FRONT) {
                // Find the first front-facing camera
                for (String id : availableCameraIds) {
                    // *** CORRECTION: Use 'cameraManager' field ***
                    CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(id);
                    Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
                    if (facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT) {
                        cameraToOpenId = id;
                        Log.d(TAG, "Found FRONT camera: ID=" + cameraToOpenId);
                        break;
                    }
                }
            } else { // CameraType.BACK
                // Get the specific preferred back camera ID from preferences
                String preferredBackId = sharedPreferencesManager.getSelectedBackCameraId();
                Log.d(TAG,"Preferred BACK camera ID from prefs: " + preferredBackId);

                // Validate if the preferred back ID is actually available and BACK facing
                boolean isValidAndAvailable = false;
                for(String id : availableCameraIds){
                    if(id.equals(preferredBackId)){
                        // *** CORRECTION: Use 'cameraManager' field ***
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
                    // Fallback: Preferred ID is invalid/unavailable. Use the default back camera ID ("0").
                    Log.w(TAG,"Preferred back camera ID '"+preferredBackId+"' is invalid or unavailable. Falling back to default ID '"+Constants.DEFAULT_BACK_CAMERA_ID+"'.");
                    cameraToOpenId = Constants.DEFAULT_BACK_CAMERA_ID;

                    // Optional: Verify the default ID "0" exists and is back-facing
                    boolean defaultExistsAndIsBack = false;
                    for(String id : availableCameraIds){
                        if(id.equals(cameraToOpenId)) {
                            // *** CORRECTION: Use 'cameraManager' field ***
                            CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(id);
                            Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
                            if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) {
                                defaultExistsAndIsBack = true;
                            }
                            break; // Found the default ID, check done
                        }
                    }

                    if(!defaultExistsAndIsBack){ // If default ID "0" is NOT found OR not back-facing
                        Log.e(TAG,"Critical: Default back camera ID '"+cameraToOpenId+"' not found or not back-facing! Cannot select default back camera.");
                        // Attempt to find *any* back camera as a last resort
                        String fallbackBackId = null;
                        for(String id: availableCameraIds){
                            // *** CORRECTION: Use 'cameraManager' field ***
                            CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(id);
                            Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
                            if(facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) {
                                Log.w(TAG,"Using first available back camera as final fallback: "+id);
                                fallbackBackId = id; // Store the first found back camera
                                break; // Use the first one found
                            }
                        }
                        if (fallbackBackId != null){
                            cameraToOpenId = fallbackBackId; // Assign the fallback ID
                        } else {
                            cameraToOpenId = null; // Set to null if no back camera was found at all
                            Log.e(TAG, "Could not find any back-facing camera.");
                        }
                    } // End check for default ID validity
                }
            } // End BACK camera logic

            if (cameraToOpenId == null) {
                Log.e(TAG, "Could not determine a valid camera ID to open for selected type: " + selectedType);
                Toast.makeText(this, "Failed to find selected camera", Toast.LENGTH_LONG).show();
                stopSelf();
                return;
            }
            Log.i(TAG,"Attempting to open final Camera ID: "+ cameraToOpenId); // Log the final chosen ID

            // Ensure previous camera is closed before opening a new one
            if(cameraDevice != null) {
                Log.w(TAG,"openCamera: Closing existing cameraDevice instance first.");
                cameraDevice.close();
                cameraDevice = null;
            }

            // *** Use the CORRECT 'cameraManager' field here to open the camera ***
            cameraManager.openCamera(cameraToOpenId, cameraStateCallback, backgroundHandler); // Use the final determined ID

        } catch (CameraAccessException e) {
            Log.e(TAG, "openCamera: Camera Access Exception", e);
            stopSelf();
        } catch (IllegalArgumentException e){
            Log.e(TAG, "openCamera: Illegal Argument Exception (likely invalid camera ID '"+cameraToOpenId+"' passed to getCharacteristics)", e);
            stopSelf();
        } catch (Exception e) {
            Log.e(TAG, "openCamera: Unexpected error", e);
            stopSelf();
        }
    } // End openCamera method
    private final CameraDevice.StateCallback cameraStateCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(@NonNull CameraDevice camera) {
            Log.d(TAG, "Camera " + camera.getId() + " opened.");
            cameraDevice = camera;
            // Start the capture session now that camera is open
            createCameraPreviewSession();
        }
        @Override
        public void onDisconnected(@NonNull CameraDevice camera) {
            Log.w(TAG, "Camera " + camera.getId() + " disconnected.");
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
            Log.d(TAG,"Camera " + camera.getId() + " closed.");
            // Optionally nullify cameraDevice here if managing state closely
            // cameraDevice = null;
        }
    };


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
            List<Surface> surfaces = new ArrayList<>();

            // Get the MediaRecorder surface
            Surface recorderSurface = mediaRecorder.getSurface();
            surfaces.add(recorderSurface);
            Log.d(TAG,"Added MediaRecorder surface.");

            // Add UI preview surface IF available and valid
            if (previewSurface != null && previewSurface.isValid()) {
                surfaces.add(previewSurface);
                Log.d(TAG,"Added Preview surface.");
            } else {
                Log.w(TAG,"Preview surface is null or invalid, not adding.");
            }

            // Create the CaptureRequest builder
            captureRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);

            // Add surfaces as targets
            captureRequestBuilder.addTarget(recorderSurface);
            if (previewSurface != null && previewSurface.isValid()) {
                captureRequestBuilder.addTarget(previewSurface);
            }

            // Set desired frame rate range for stability
            int targetFrameRate = sharedPreferencesManager.getVideoFrameRate();
            Range<Integer> fpsRange = new Range<>(targetFrameRate, targetFrameRate);
            captureRequestBuilder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fpsRange);
            Log.d(TAG,"Set target FPS range: "+fpsRange);


            // Create the session
            cameraDevice.createCaptureSession(surfaces, captureSessionCallback, backgroundHandler);
            Log.d(TAG, "Requested camera capture session creation.");

        } catch (CameraAccessException e) {
            Log.e(TAG, "createCameraPreviewSession: Camera Access Exception", e);
            stopRecording(); // Stop if session creation fails
        } catch (Exception e) {
            Log.e(TAG, "createCameraPreviewSession: Unexpected error", e);
            stopRecording(); // Stop on general errors
        }
    }


    private final CameraCaptureSession.StateCallback captureSessionCallback = new CameraCaptureSession.StateCallback() {
        @Override
        public void onConfigured(@NonNull CameraCaptureSession session) {
            Log.d(TAG, "Capture session configured: " + session);
            if (cameraDevice == null) {
                Log.e(TAG,"onConfigured: Camera closed before session configured!");
                session.close(); // Close the session if camera is gone
                return;
            }
            captureSession = session; // Assign the configured session

            try {
                // Start the repeating request (includes preview and feeds recorder)
                captureRequestBuilder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO); // Use auto mode
                // ----- Fix Start for this method(onConfigured)-----
                // Re-introduce setting FLASH_MODE based on the session's torch state
                captureRequestBuilder.set(CaptureRequest.FLASH_MODE, isRecordingTorchEnabled ? CaptureRequest.FLASH_MODE_TORCH : CaptureRequest.FLASH_MODE_OFF);
                // ----- Fix Ended for this method(onConfigured)-----

                session.setRepeatingRequest(captureRequestBuilder.build(), null, backgroundHandler);
                Log.d(TAG,"Repeating request started.");


                // ----- Fix Start for this method(onConfigured_prevent_restart_on_surface_change)-----
                // If we are in a state where recording should be active
                if (recordingState == RecordingState.STARTING) {
                    // This is the initial start of the very first segment
                    recordingStartTime = SystemClock.elapsedRealtime();
                    Log.d(TAG, "MediaRecorder starting for the first segment (state was STARTING).");
                    mediaRecorder.start(); // START RECORDING for the first segment
                    
                    recordingState = RecordingState.IN_PROGRESS; // Transition from STARTING to IN_PROGRESS
                    sharedPreferencesManager.setRecordingInProgress(true);
                    setupRecordingInProgressNotification();
                    broadcastOnRecordingStarted(); 
                    Log.d(TAG, "Initial MediaRecorder started! Recording now IN_PROGRESS.");

                } else if (recordingState == RecordingState.IN_PROGRESS) {
                    // This typically means the session was reconfigured (e.g., due to surface change)
                    // WHILE a recording (either initial or a subsequent segment) was already in progress.
                    // DO NOT call mediaRecorder.start() again if it's already started for the current segment.
                    // The existing MediaRecorder instance continues with the new session.
                    Log.d(TAG, "Capture session reconfigured while IN_PROGRESS (e.g. surface change). MediaRecorder for segment " + currentSegmentNumber + " should already be running. Ensuring notification is current.");
                    setupRecordingInProgressNotification(); // Ensure notification is correct
                
                } else if (recordingState == RecordingState.PAUSED) { 
                    Log.w(TAG,"Session reconfigured while PAUSED. MediaRecorder was resumed prior. State remains PAUSED until explicit resume.");
                    setupRecordingResumeNotification(); // Show PAUSED notification
                }
                // ----- Fix Ended for this method(onConfigured_prevent_restart_on_surface_change)-----

            } catch (CameraAccessException e) {
                Log.e(TAG, "onConfigured: Error starting repeating request or MediaRecorder", e);
                stopRecording(); // Stop if cannot set repeating request
            } catch (IllegalStateException e) {
                Log.e(TAG, "onConfigured: IllegalStateException (Session, Camera, or MediaRecorder closed/in wrong state?)", e);
                stopRecording();
            }
        }

        @Override
        public void onConfigureFailed(@NonNull CameraCaptureSession session) {
            Log.e(TAG, "Capture session configuration failed: " + session);
            // ----- Fix Start for this method(onConfigureFailed)-----
            if (recordingState == RecordingState.STARTING) {
                Log.w(TAG, "Configuration failed during STARTING state, resetting to NONE.");
                recordingState = RecordingState.NONE;
            }
            // ----- Fix Ended for this method(onConfigureFailed)-----
            stopRecording(); // Stop if session configuration fails
        }
        @Override
        public void onClosed(@NonNull CameraCaptureSession session) {
            Log.d(TAG,"Capture session closed: "+ session);
            // captureSession = null; // Nullify if needed elsewhere
        }
    };
    // --- End MediaRecorder & Camera Session Setup ---

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
            String watermarkText;
            boolean isLocationEnabled = sharedPreferencesManager.isLocalisationEnabled();
            String locationText = isLocationEnabled ? getLocationData() : "";
            switch (watermarkOption) {
                case "timestamp":
                    watermarkText = getCurrentTimestamp() + (isLocationEnabled ? locationText : "");
                    break;
                default: // timestamp_fadcam
                    watermarkText = "Captured by FadCam - " + getCurrentTimestamp() + (isLocationEnabled ? locationText : "");
                    break;
            }
            watermarkText = convertArabicNumeralsToEnglish(watermarkText);
            String escapedWatermarkText = escapeFFmpegString(watermarkText); // Escape needed characters

            int fontSize = getFontSizeBasedOnBitrate();
            String fontSizeStr = convertArabicNumeralsToEnglish(String.valueOf(fontSize));
            int frameRates = sharedPreferencesManager.getVideoFrameRate();
            int bitratesEstimated = Utils.estimateBitrate(sharedPreferencesManager.getCameraResolution(), frameRates); // Or getVideoBitrate() if always accurate
            String codec = sharedPreferencesManager.getVideoCodec().getFfmpeg();
            String escapedFontPath = escapeFFmpegString(fontPath);


            // Build FFmpeg command carefully
            ffmpegCommand = String.format(
                    Locale.US, // Use US Locale for number formatting consistency
                    "-i %s -r %d -vf \"drawtext=text='%s':x=10:y=10:fontsize=%s:fontcolor=white:fontfile='%s':escape_text=0\" -q:v 0 -codec:v %s -b:v %d -codec:a copy -y %s",
                    inputUriOrPath,
                    frameRates,
                    escapedWatermarkText,
                    fontSizeStr,
                    escapedFontPath,
                    codec,
                    bitratesEstimated,
                    outputUriOrPath
            );
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
        Log.d(TAG, "Executing FFmpeg: " + ffmpegCommand);
        // This method appears to be part of an older/alternative processing flow.
        // It is NOT the primary path for segment processing, which uses executeFFmpegAsync.
        // If this method were to be activated and manage an FFmpeg task,
        // it would need to increment ffmpegProcessingTaskCount before calling FFmpegKit.executeAsync,
        // and decrement it in the callback, similar to how executeFFmpegAsync works.

        // Example structure if it were to manage a task:
        // ffmpegProcessingTaskCount.incrementAndGet();
        // Log.d(TAG, "FFmpeg task count incremented via executeFFmpegCommand for: " + inputUriOrPath);

        FFmpegKit.executeAsync(ffmpegCommand, session -> {
            boolean success = ReturnCode.isSuccess(session.getReturnCode());
            Log.d(TAG, "FFmpeg session (via executeFFmpegCommand) finished - Success: " + success + ", RC: " + session.getReturnCode());

            if (success) {
                Log.d(TAG, "FFmpeg process successful (via executeFFmpegCommand). Output: " + outputUriOrPath);
                cleanupTemporaryFile(); // Delete the temp file (inputUriOrPath)

                // Optional: Update records UI / Media Scanner if needed
            } else {
                Log.e(TAG, "FFmpeg (via executeFFmpegCommand) failed! Logs:");
                Log.e(TAG, session.getAllLogsAsString());
                Log.e(TAG, "Command was: "+ffmpegCommand);
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
        if (currentParcelFileDescriptor != null) {
            try {
                currentParcelFileDescriptor.close();
                Log.d(TAG, "Closed ParcelFileDescriptor");
            } catch (IOException e) {
                Log.e(TAG, "Error closing ParcelFileDescriptor", e);
            } finally {
                currentParcelFileDescriptor = null;
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
        // ----- Fix Start for this method(isWorkingInProgress_use_ffmpeg_counter)-----
        return ffmpegProcessingTaskCount.get() > 0 || recordingState == RecordingState.IN_PROGRESS || recordingState == RecordingState.PAUSED || recordingState == RecordingState.STARTING;
        // ----- Fix Ended for this method(isWorkingInProgress_use_ffmpeg_counter)-----
        // ----- Fix Ended for this method(isWorkingInProgress)-----
    }
    // --- End Status Check ---

    // ----- Fix Start for this class (RecordingService_video_splitting_listener_and_rollover) -----
    private final MediaRecorder.OnInfoListener mediaRecorderInfoListener = new MediaRecorder.OnInfoListener() {
        @Override
        public void onInfo(MediaRecorder mr, int what, int extra) {
            Log.d(TAG, "MediaRecorder.onInfo: what=" + what + ", extra=" + extra);
            if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED) {
                Log.i(TAG, "Max filesize reached! Handling segment rollover.");
                if (isVideoSplittingEnabled && videoSplitSizeBytes > 0) {
                    handleSegmentRollover();
                } else {
                    Log.w(TAG, "Max filesize reached, but splitting not enabled/configured. Stopping recording.");
                    stopRecording(); // Fallback to normal stop if splitting is off
                }
            }
        }
    };

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

    private void handleSegmentRollover() {
        Log.i(TAG, "Handling segment rollover for segment " + currentSegmentNumber);

        // ----- Fix Start for this method(handleSegmentRollover_broadcast_correct_file)-----
        // 1. Temporarily store current file/URI for broadcast (use the TEMP file MediaRecorder just wrote)
        File completedTempFile = currentInternalTempFile; // if internal (this is what MR wrote to)
        Uri completedSafUri = currentRecordingSafUri;    // if SAF (this is what MR wrote to via PFD)
        DocumentFile completedDocFile = currentRecordingDocFile; // To get the name if SAF
        // ----- Fix Ended for this method(handleSegmentRollover_broadcast_correct_file)-----

        // 2. Stop the current MediaRecorder and release camera resources (partially)
        if (mediaRecorder != null) {
            try {
                mediaRecorder.stop();
                Log.d(TAG, "Rollover: MediaRecorder stopped for segment " + currentSegmentNumber);
            } catch (RuntimeException e) {
                Log.e(TAG, "Rollover: RuntimeException stopping MediaRecorder for segment " + currentSegmentNumber, e);
                // Critical error, attempt full stop
                stopRecording();
                return;
            }
            mediaRecorder.reset(); // Reset for reuse, don't release yet
        }
        // Do NOT fully close cameraDevice or captureSession here, as we need to restart them.
        // Only close the capture session if it's essential for creating a new one reliably.
        // For now, we assume createCameraPreviewSession can handle reconfiguring.

        // 3. Increment segment number
        currentSegmentNumber++;
        Log.d(TAG, "Rollover: New segment number: " + currentSegmentNumber);

        // ----- Fix Start for this method(handleSegmentRollover_broadcast_correct_file)-----
        // 4. Broadcast that a segment is complete using the URI/Path of the *actual recorded file*
        Intent segmentIntent = new Intent(Constants.ACTION_RECORDING_SEGMENT_COMPLETE);
        String completedFileUriString = null;
        String completedFilePath = null;

        if (completedSafUri != null && completedDocFile != null) { // Check completedDocFile for SAF
            completedFileUriString = completedSafUri.toString();
            // For SAF, a direct file path isn't usually available or reliable for other apps
            // The URI is the primary identifier. We can also pass the display name.
            // ----- Fix Start for this method(handleSegmentRollover_remove_filename_extra)-----
            // segmentIntent.putExtra(Constants.INTENT_EXTRA_FILE_NAME, completedDocFile.getName()); // This constant doesn't exist yet
            Log.d(TAG, "Rollover: Broadcasting segment complete for SAF URI: " + completedFileUriString + " (Name: " + (completedDocFile != null ? completedDocFile.getName() : "N/A") + ")");
            // ----- Fix Ended for this method(handleSegmentRollover_remove_filename_extra)-----
        } else if (completedTempFile != null) { // Internal storage, use the temp file path
            Uri tempFileUri = Uri.fromFile(completedTempFile);
            completedFileUriString = tempFileUri.toString();
            completedFilePath = completedTempFile.getAbsolutePath();
            // ----- Fix Start for this method(handleSegmentRollover_remove_filename_extra)-----
            // segmentIntent.putExtra(Constants.INTENT_EXTRA_FILE_NAME, completedTempFile.getName()); // This constant doesn't exist yet
            Log.d(TAG, "Rollover: Broadcasting segment complete for internal temp file: " + completedFilePath + " (Name: " + completedTempFile.getName() + ")");
            // ----- Fix Ended for this method(handleSegmentRollover_remove_filename_extra)-----
        } else {
            Log.w(TAG, "Rollover: No completed file URI or path to broadcast for segment completion.");
        }

        if (completedFileUriString != null) {
            segmentIntent.putExtra(Constants.INTENT_EXTRA_FILE_URI, completedFileUriString);
            if (completedFilePath != null) { // Only add if it's an actual file path
                segmentIntent.putExtra(Constants.INTENT_EXTRA_FILE_PATH, completedFilePath);
            }
            sendBroadcast(segmentIntent);
        }
        // ----- Fix Ended for this method(handleSegmentRollover_broadcast_correct_file)-----

        // ----- Fix Start for this method(handleSegmentRollover_trigger_ffmpeg_for_segment)-----
        // 4.b. Initiate FFmpeg processing for the completed segment
        if (completedTempFile != null && completedTempFile.exists()) {
            Log.d(TAG, "Rollover: Initiating FFmpeg for internal temp segment: " + completedTempFile.getAbsolutePath());
            processAndMoveVideo(completedTempFile, null); // Process the temp file from internal cache, no original SAF URI
        } else if (completedSafUri != null && completedDocFile != null && completedDocFile.exists()) {
            Log.d(TAG, "Rollover: SAF segment recorded. Copying to temp cache for FFmpeg processing: " + completedDocFile.getName());
            // Copy the SAF URI content to a new temp file in cache first
            File tempCacheFileForSafSegment = copySafUriToTempCacheForProcessing(getApplicationContext(), completedSafUri, completedDocFile.getName());
            if (tempCacheFileForSafSegment != null && tempCacheFileForSafSegment.exists()) {
                Log.d(TAG, "Rollover: Copied SAF segment to cache: " + tempCacheFileForSafSegment.getAbsolutePath() + ". Initiating FFmpeg.");
                processAndMoveVideo(tempCacheFileForSafSegment, completedSafUri); // Pass cached file and ORIGINAL temp SAF URI

                // DO NOT DELETE original temporary SAF segment here.
                // Deletion will be handled by executeFFmpegAndMoveToSAF after successful processing & move.
                // The PFD for completedSafUri was already closed by createOutputFile() when the new segment started.
                // if (completedDocFile.exists()) {
                //     if (completedDocFile.delete()) {
                //         Log.d(TAG, "Rollover: Deleted original temporary SAF segment after copying to cache: " + completedDocFile.getName());
                //     }
                // }
            } else {
                Log.e(TAG, "Rollover: Failed to copy SAF segment to temp cache. Cannot process: " + (completedDocFile != null ? completedDocFile.getName() : completedSafUri.toString()));
                // If copy fails, we might have an unprocessed segment in SAF. Handle as error or leave it?
                // For now, log and continue to next segment recording.
            }
        } else {
            Log.w(TAG, "Rollover: No valid completed segment file/URI found to process with FFmpeg.");
        }
        // ----- Fix Ended for this method(handleSegmentRollover_trigger_ffmpeg_for_segment)-----

        // 5. Re-setup MediaRecorder for the NEW segment (new file name)
        try {
            // The createOutputFile() call will now use the incremented currentSegmentNumber
            // And setupMediaRecorder will use the new file from createOutputFile()
            setupMediaRecorder(); // This will set up MediaRecorder with the new segment's file.
            Log.d(TAG, "Rollover: MediaRecorder re-setup for segment " + currentSegmentNumber);
        } catch (IOException e) {
            Log.e(TAG, "Rollover: IOException re-setting up MediaRecorder for segment " + currentSegmentNumber, e);
            stopRecording(); // Critical error, stop entirely
            return;
        }

        // 6. Restart camera capture session and MediaRecorder
        // The existing cameraDevice should still be open. We need a new session.
        if (cameraDevice != null) {
            Log.d(TAG, "Rollover: Re-creating camera preview session for new segment.");
            // ----- Fix Start for this method(handleSegmentRollover_set_state_for_restart)-----
            // Set state to STARTING so that onConfigured knows to start the new MediaRecorder instance
            recordingState = RecordingState.STARTING; 
            // ----- Fix Ended for this method(handleSegmentRollover_set_state_for_restart)-----
            createCameraPreviewSession(); // This should configure and start the new MediaRecorder
        } else {
            Log.e(TAG, "Rollover: CameraDevice is null, cannot restart session. Stopping.");
            stopRecording();
        }
        Log.i(TAG, "Segment rollover process complete for segment " + (currentSegmentNumber -1));
    }
    // ----- Fix Ended for this class (RecordingService_video_splitting_listener_and_rollover) -----

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
}