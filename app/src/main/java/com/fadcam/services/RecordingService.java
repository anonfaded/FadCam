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

import android.content.Intent; // Add Intent import
import android.net.Uri;       // Add Uri import
import com.fadcam.Constants; // Import your Constants class
import java.util.Set; // Add if needed
import java.util.HashSet; // Add if needed

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
    private boolean isProcessingWatermark = false; // Flag for FFmpeg processing

    // Torch related (from previous implementations)
    private CameraManager torchManager;
    private String torchCameraId; // Might be used by torch logic
    private boolean isTorchOn = false;
    private boolean isRecordingTorchEnabled = false; // Tracks state of torch during recording

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
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "onStartCommand received: Action=" + (intent != null ? intent.getAction() : "null"));
        if (intent == null || intent.getAction() == null) { /* ... handle null ... */ return START_NOT_STICKY; }

        String action = intent.getAction();
        switch (action) {
            case Constants.INTENT_ACTION_START_RECORDING:
                // *** REMOVED isWorkingInProgress check, only check current RECORDING state ***
                if (recordingState != RecordingState.NONE) {
                    Log.w(TAG,"Start requested, but already RECORDING/PAUSED. State: " + recordingState + ". Ignoring.");
                    broadcastOnRecordingStateCallback(); // Notify UI of current state
                    return START_STICKY; // Remain active
                }
                // Check for processing flag is NO LONGER DONE HERE. Allow start attempt.
                Log.i(TAG,"Handling START_RECORDING intent. Service recording state is NONE.");
                setupSurfaceTexture(intent);
                setupRecordingInProgressNotification(); // Show notification immediately
                startRecording(); // Attempt to start hardware recording
                break;

            // ... other cases remain the same ...
            case Constants.INTENT_ACTION_PAUSE_RECORDING:      pauseRecording(); break;
            case Constants.INTENT_ACTION_RESUME_RECORDING:     setupSurfaceTexture(intent); resumeRecording(); break;
            case Constants.INTENT_ACTION_CHANGE_SURFACE:       setupSurfaceTexture(intent); if (isRecording() || isPaused()) { createCameraPreviewSession(); } break;
            case Constants.INTENT_ACTION_STOP_RECORDING:       stopRecording(); break;
            case Constants.BROADCAST_ON_RECORDING_STATE_REQUEST: Log.d(TAG,"Resp state request"); broadcastOnRecordingStateCallback(); if (!isWorkingInProgress()) { stopSelf(); } break;
            case Constants.INTENT_ACTION_TOGGLE_RECORDING_TORCH: toggleRecordingTorch(); break;
            default: Log.w(TAG, "Unknown action: " + action); break;
        }
        return START_STICKY;
    }

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
            stopSelf();
            return;
        }
        Log.d(TAG,"startRecording: Attempting to start.");
        try {
            setupMediaRecorder(); // Configure the recorder + storage path/URI
            if (mediaRecorder == null) { // Check if setup failed
                Log.e(TAG, "startRecording: MediaRecorder setup failed.");
                return; // setupMediaRecorder handles errors/stopping service
            }
            openCamera(); // Open camera to start the capture session
        } catch (Exception e) {
            Log.e(TAG, "Error starting recording process", e);
            releaseRecordingResources(); // Clean up on failure
            Toast.makeText(this,"Error starting recording", Toast.LENGTH_LONG).show();
            stopSelf();
        }
    }

    /**
     * Initiates the sequence to stop recording, release hardware resources,
     * notify the UI, and then potentially start background processing.
     */
    private void stopRecording() {
        Log.i(TAG, ">> stopRecording sequence initiated. Current state: " + recordingState);
        if (recordingState == RecordingState.NONE) { // Already stopped (or never started)
            Log.w(TAG, "stopRecording called but state is already NONE.");
            // Ensure pref is consistent and check if service should stop
            sharedPreferencesManager.setRecordingInProgress(false);
            if (!isWorkingInProgress()) stopSelf();
            return;
        }

        // --- Stage 1: Stop MediaRecorder & Update Internal State ---
        File tempFileToProcess = this.currentInternalTempFile; // Grab reference for processing later
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
        boolean tempFileIsValid = tempFileToProcess != null && tempFileToProcess.exists() && tempFileToProcess.length() > 0;
        boolean needsProcessing = (previousState != RecordingState.NONE) && stoppedCleanly && tempFileIsValid;

        if (needsProcessing) {
            Log.i(TAG, "Proceeding to background video processing for: " + tempFileToProcess.getName());
            isProcessingWatermark = true; // Set flag ONLY if processing starts
            this.currentInternalTempFile = tempFileToProcess; // Keep reference for processing
            processAndMoveVideo(tempFileToProcess); // Starts async FFmpeg
        } else {
            Log.w(TAG, "Skipping processing. PrevState:" + previousState + " CleanStop:" + stoppedCleanly + " TempValid:" + tempFileIsValid);
            cleanupTemporaryFile(); // Cleanup temp file if not processing
            sendRecordingCompleteBroadcast(stoppedCleanly, tempFileToProcess != null ? Uri.fromFile(tempFileToProcess) : null); // Signal immediate completion
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
        boolean isProcessing = isProcessingWatermark;
        boolean isRecordingActive = (recordingState != RecordingState.NONE);

        Log.d(TAG, "checkIfServiceCanStop: RecordingState=" + recordingState + ", isProcessing=" + isProcessing);

        // If NOT currently recording/paused AND NOT currently processing...
        if (!isRecordingActive && !isProcessing) {
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

        // Cleanup temp file only if processing isn't active
        if (!isProcessingWatermark) {
            cleanupTemporaryFile();
        } else {
            Log.d(TAG,"Release: Keeping temp file reference for ongoing processing.");
        }
        Log.d(TAG, "Finished releasing recording resources.");
    }
    // --- End Core Recording Logic ---

    private void processAndMoveVideo(@NonNull File internalTempFileToProcess) { // Arg is now in external cache
        Log.d(TAG,"processAndMoveVideo starting for (ext cache): " + internalTempFileToProcess.getName());
        if (!internalTempFileToProcess.exists() || internalTempFileToProcess.length() == 0) {
            Log.e(TAG,"Temp file invalid/empty: " + internalTempFileToProcess.getAbsolutePath());
            isProcessingWatermark = false; if(internalTempFileToProcess.exists()&&!internalTempFileToProcess.delete()) Log.w(TAG,"Failed del invalid temp"); return;
        }
        isProcessingWatermark = true;
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
            if (!cacheDir.exists() && !cacheDir.mkdirs()) { handleProcessingError("Cannot create processed cache dir", internalTempInputPath); return; }
            File internalProcessedOutputFile = new File(cacheDir, finalBaseName);
            String internalProcessedOutputPath = internalProcessedOutputFile.getAbsolutePath(); // Path is in ext cache now

            Log.d(TAG, "Intermediate processed path (ext cache): " + internalProcessedOutputPath);

            String ffmpegCommand = buildFFmpegCommand(internalTempInputPath, internalProcessedOutputPath);
            if (ffmpegCommand == null) { handleProcessingError("Failed build FFmpeg command", internalTempInputPath); return; }
            executeFFmpegAndMoveToSAF(ffmpegCommand, internalTempFileToProcess, internalProcessedOutputFile, customUriString); // Pass correct files

        } else {
            Log.d(TAG, "Target is Internal App Storage");
            // Process from external cache -> final internal directory
            File finalInternalDir = new File(getExternalFilesDir(null), Constants.RECORDING_DIRECTORY); // Standard final location
            if (!finalInternalDir.exists() && !finalInternalDir.mkdirs()) { handleProcessingError("Cannot create final internal dir", internalTempInputPath); return; }
            File finalInternalOutputFile = new File(finalInternalDir, finalBaseName);
            String finalInternalOutputPath = finalInternalOutputFile.getAbsolutePath();
            Log.d(TAG, "Final internal path: " + finalInternalOutputPath);

            String ffmpegCommand = buildFFmpegCommand(internalTempInputPath, finalInternalOutputPath);
            if (ffmpegCommand == null) { handleProcessingError("Failed build FFmpeg command", internalTempInputPath); return; }
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
                },
                () -> { // Failure Runnable
                    Log.e(TAG, "FFmpeg internal process failed for: " + internalTempInput.getName());
                    // Clean up the FAILED *output* file if it exists
                    if (finalInternalOutput.exists() && !finalInternalOutput.delete()) {
                        Log.w(TAG, "Failed to delete failed internal output: " + finalInternalOutput.getName());
                    }
                    // Do NOT delete the input temp file on failure
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

        isProcessingWatermark = true; // Set processing flag
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


            isProcessingWatermark = false; // Reset flag

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
    private void sendRecordingCompleteBroadcast(boolean success, @Nullable Uri resultUri) {
        Intent completeIntent = new Intent(Constants.ACTION_RECORDING_COMPLETE);
        completeIntent.putExtra(Constants.EXTRA_RECORDING_SUCCESS, success);
        if (resultUri != null) {
            completeIntent.putExtra(Constants.EXTRA_RECORDING_URI_STRING, resultUri.toString());
        } else {
            Log.w(TAG,"Sending RECORDING_COMPLETE broadcast without a result URI (Success="+success+").");
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
    private void executeFFmpegAndMoveToSAF(String command, File internalTempInput, File tempInternalProcessedOutput, String targetSafDirUriString) {
        Log.d(TAG, "executeFFmpegAndMoveToSAF: Processing " + internalTempInput.getName() + " -> " + tempInternalProcessedOutput.getName() + " -> SAF");

        // ** FIX: Add inputFile and finalOutputUriIfKnown (which is null here) arguments **
        executeFFmpegAsync(
                command,
                internalTempInput,              // Pass the original temp input file
                null,                          // Pass null for final output URI, as it's determined later
                () -> { // FFmpeg Success Runnable (FFmpeg wrote successfully to tempInternalProcessedOutput)
                    Log.d(TAG, "FFmpeg successful (intermediate cache file created): " + tempInternalProcessedOutput.getAbsolutePath());
                    // Now move the processed file from cache to SAF
                    boolean moveSuccess = moveInternalFileToSAF(tempInternalProcessedOutput, targetSafDirUriString);
                    if (moveSuccess) {
                        Log.d(TAG, "Successfully moved processed cache file to SAF: " + targetSafDirUriString);
                        // Delete BOTH internal temp files (original input and processed cache) on success
                        if (internalTempInput.exists() && !internalTempInput.delete()) { Log.w(TAG, "Failed to delete initial temp: " + internalTempInput.getName()); }
                        if (tempInternalProcessedOutput.exists() && !tempInternalProcessedOutput.delete()) { Log.w(TAG, "Failed to delete processed cache temp: " + tempInternalProcessedOutput.getName()); }
                        // Optional: Determine the final SAF URI here if needed for broadcasting success status of the *final* file
                        // Uri finalSafUri = getFinalSafUri(targetSafDirUriString, tempInternalProcessedOutput.getName()); // Helper needed
                        // sendRecordingCompleteBroadcast(true, finalSafUri); // Send specific success broadcast?
                    } else {
                        Log.e(TAG, "Failed to move processed cache file to SAF!");
                        Toast.makeText(this, "Failed to save to custom location", Toast.LENGTH_LONG).show();
                        // Leave the *processed* temp cache file as backup.
                        // Delete the *original* input temp file if FFmpeg succeeded but move failed.
                        if (internalTempInput.exists() && !internalTempInput.delete()) { Log.w(TAG, "Failed to delete initial temp after move failure: " + internalTempInput.getName()); }
                        // sendRecordingCompleteBroadcast(false, Uri.fromFile(internalTempInput)); // Send failure for original?
                    }
                },
                () -> { // FFmpeg Failure Runnable (FFmpeg failed to write to tempInternalProcessedOutput)
                    Log.e(TAG, "FFmpeg process failed (writing to cache) for: " + internalTempInput.getName());
                    // Clean up the FAILED *processed* cache output if it exists
                    if (tempInternalProcessedOutput.exists() && !tempInternalProcessedOutput.delete()) {
                        Log.w(TAG, "Failed to delete failed processed cache output: " + tempInternalProcessedOutput.getName());
                    }
                    // Do NOT delete the original input temp file on FFmpeg failure
                    // sendRecordingCompleteBroadcast(false, Uri.fromFile(internalTempInput)); // Send failure
                }
        ); // ** End of executeFFmpegAsync call **
    }

    // Helper to move an internal file to a SAF directory URI
    private boolean moveInternalFileToSAF(File internalSourceFile, String targetDirUriString) {
        if (!internalSourceFile.exists()) {
            Log.e(TAG,"moveInternalFileToSAF: Source file does not exist: " + internalSourceFile.getAbsolutePath());
            return false;
        }
        if (targetDirUriString == null) {
            Log.e(TAG,"moveInternalFileToSAF: Target SAF directory URI is null.");
            return false;
        }

        ContentResolver resolver = getContentResolver();
        Uri dirUri = Uri.parse(targetDirUriString);
        DocumentFile targetDir = DocumentFile.fromTreeUri(this, dirUri);

        if (targetDir == null || !targetDir.isDirectory() || !targetDir.canWrite()) {
            Log.e(TAG, "moveInternalFileToSAF: Target SAF directory is invalid or not writable: " + targetDirUriString);
            return false;
        }

        String mimeType = "video/" + Constants.RECORDING_FILE_EXTENSION; // Adjust if needed
        String displayName = internalSourceFile.getName(); // Use the final processed name

        // Create destination file (overwrite might need check/delete first if needed)
        DocumentFile existingFile = targetDir.findFile(displayName);
        if(existingFile != null){
            Log.w(TAG,"SAF Move: Destination file already exists. Deleting first.");
            if(!existingFile.delete()) { Log.e(TAG,"SAF Move: Failed to delete existing file."); return false; }
        }

        DocumentFile newSafFile = targetDir.createFile(mimeType, displayName);
        if (newSafFile == null || !newSafFile.exists()) {
            Log.e(TAG, "moveInternalFileToSAF: Failed to create destination file in SAF: " + displayName);
            return false;
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
            return true; // Copy successful

        } catch (IOException e) {
            Log.e(TAG, "moveInternalFileToSAF: Error during file copy to SAF", e);
            // Attempt to delete the partially created/failed SAF file
            if (newSafFile.exists() && !newSafFile.delete()) {
                Log.w(TAG, "Could not delete partially written SAF file: "+newSafFile.getName());
            }
            return false; // Copy failed
        } finally {
            // Close streams safely
            try { if (inputStream != null) inputStream.close(); } catch (IOException e) { /* Ignore */ }
            try { if (outputStream != null) outputStream.close(); } catch (IOException e) { /* Ignore */ }
        }
    }


    // --- MediaRecorder & Camera Session Setup ---
    // Updated setupMediaRecorder to target EXTERNAL cache first
    private void setupMediaRecorder() throws IOException { // Throw exception on failure
        Log.d(TAG, "setupMediaRecorder: Configuring recorder to save to EXTERNAL cache.");
        currentInternalTempFile = null; // Reset (though name is now slightly misleading)

        // 1. Define EXTERNAL Cache Output Path
        File cacheDir = getExternalCacheDir(); // <--- CHANGE HERE
        if (cacheDir == null) { // Fallback if external cache is somehow unavailable
            Log.w(TAG, "External cache dir unavailable, falling back to internal cache.");
            cacheDir = new File(getCacheDir(), "recording_temp"); // Use internal as fallback
        } else {
            cacheDir = new File(cacheDir, "recording_temp"); // Create subdir in external cache
        }

        if (!cacheDir.exists() && !cacheDir.mkdirs()) {
            throw new IOException("Cannot create temp cache directory: " + cacheDir.getAbsolutePath());
        }
        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
        String tempFilename = "temp_" + timestamp + "." + Constants.RECORDING_FILE_EXTENSION;
        // This variable now points to the external cache path
        currentInternalTempFile = new File(cacheDir, tempFilename);
        Log.d(TAG, "Temporary output path set to (external?) cache: " + currentInternalTempFile.getAbsolutePath());

        // 2. Create and Configure MediaRecorder instance
        mediaRecorder = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) ? new MediaRecorder(this) : new MediaRecorder();
        try {
            mediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            mediaRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
            mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            mediaRecorder.setOutputFile(currentInternalTempFile.getAbsolutePath()); // Use (external?) cache path

            // --- Apply standard configurations ---
            Size resolution = sharedPreferencesManager.getCameraResolution();
            int frameRate = sharedPreferencesManager.getVideoFrameRate();
            VideoCodec codec = sharedPreferencesManager.getVideoCodec();
            int bitRate = getVideoBitrate();

            mediaRecorder.setVideoSize(resolution.getWidth(), resolution.getHeight());
            mediaRecorder.setVideoEncodingBitRate(bitRate);
            mediaRecorder.setVideoFrameRate(frameRate);
            mediaRecorder.setAudioEncodingBitRate(384000);
            mediaRecorder.setAudioSamplingRate(48000);
            mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            mediaRecorder.setVideoEncoder(codec.getEncoder());
            if (sharedPreferencesManager.getCameraSelection() == CameraType.FRONT) mediaRecorder.setOrientationHint(270); else mediaRecorder.setOrientationHint(90);
            // --- End configurations ---

            mediaRecorder.prepare();
            Log.d(TAG, "setupMediaRecorder: MediaRecorder prepared successfully for cache file.");

        } catch (IOException | IllegalStateException e) {
            Log.e(TAG, "setupMediaRecorder: Failed", e);
            // Do NOT call full releaseRecordingResources here to keep camera open
            if(mediaRecorder != null) { try { mediaRecorder.release(); } catch (Exception ignored) {} mediaRecorder = null; }
            if(currentInternalTempFile != null && currentInternalTempFile.exists()) currentInternalTempFile.delete();
            currentInternalTempFile = null;
            throw new IOException("MediaRecorder setup failed: " + e.getMessage(), e); // Propagate
        }
    }

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
            // Possibly notify UI or attempt restart? For now, just stop.
            stopSelf();
        }
        @Override
        public void onError(@NonNull CameraDevice camera, int error) {
            Log.e(TAG, "Camera " + camera.getId() + " error: " + error);
            releaseRecordingResources(); // Cleanup on error
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
                // Apply recording torch state IF it was toggled before session configured
                captureRequestBuilder.set(CaptureRequest.FLASH_MODE, isRecordingTorchEnabled ? CaptureRequest.FLASH_MODE_TORCH : CaptureRequest.FLASH_MODE_OFF);

                session.setRepeatingRequest(captureRequestBuilder.build(), null, backgroundHandler);
                Log.d(TAG,"Repeating request started.");


                // Now that the session is configured and request running, handle recording state
                if (recordingState == RecordingState.NONE) { // This is the initial start
                    recordingStartTime = SystemClock.elapsedRealtime();
                    mediaRecorder.start(); // START RECORDING
                    recordingState = RecordingState.IN_PROGRESS;
                    sharedPreferencesManager.setRecordingInProgress(true);
                    Log.d(TAG, "MediaRecorder started! Recording IN_PROGRESS.");
                    setupRecordingInProgressNotification();
                    broadcastOnRecordingStarted();
                } else if (recordingState == RecordingState.PAUSED) { // This is resuming
                    // The mediaRecorder.resume() was likely called before session recreated
                    Log.w(TAG,"Session reconfigured while paused, now resuming state (MediaRecorder already resumed)");
                    recordingState = RecordingState.IN_PROGRESS;
                    sharedPreferencesManager.setRecordingInProgress(true);
                    setupRecordingInProgressNotification();
                    broadcastOnRecordingResumed(); // Notify UI resumed
                }

            } catch (CameraAccessException e) {
                Log.e(TAG, "onConfigured: Error starting repeating request", e);
                stopRecording(); // Stop if cannot set repeating request
            } catch (IllegalStateException e) {
                Log.e(TAG, "onConfigured: IllegalStateException (Session or Camera closed?)", e);
                stopRecording();
            }
        }

        @Override
        public void onConfigureFailed(@NonNull CameraCaptureSession session) {
            Log.e(TAG, "Capture session configuration failed: " + session);
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
    private void processLatestVideoFileWithWatermark() {
        Log.d(TAG,"processLatestVideoFileWithWatermark starting...");
        String inputUriOrPath;
        String outputUriOrPath;
        String tempFileName; // Needed for constructing output name
        Uri inputUri = null;
        File inputFile = null;
        Uri outputDirectoryUri = null; // Only for SAF output directory

        isProcessingWatermark = true; // Set flag immediately

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
            isProcessingWatermark = false; // Reset flag
            return;
        }
        if(tempFileName == null) {
            Log.e(TAG, "Could not determine temporary filename, aborting processing.");
            isProcessingWatermark = false; // Reset flag
            cleanupTemporaryFile(); // Attempt cleanup
            return;
        }


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
     * @param inputUriOrPathToDelete The URI (as String) or absolute path of the temporary input file
     *                               that should be cleaned up because its processing failed. Can be null.
     */
    // Helper to handle storage setup errors consistently
    private void handleProcessingError(String errorMessage, @Nullable String internalTempInputPath) {
        Log.e(TAG, "Processing Error: " + errorMessage);
        Toast.makeText(this, "Error processing video recording", Toast.LENGTH_LONG).show();

        isProcessingWatermark = false; // Reset the processing flag IMPORTANTLY

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

    private void executeFFmpegCommand(String ffmpegCommand, final String inputUriOrPath, final String outputUriOrPath) {
        Log.d(TAG, "Executing FFmpeg: " + ffmpegCommand);

        FFmpegKit.executeAsync(ffmpegCommand, session -> {
            boolean success = ReturnCode.isSuccess(session.getReturnCode());
            Log.d(TAG, "FFmpeg session finished - Success: " + success + ", RC: " + session.getReturnCode());

            if (success) {
                Log.d(TAG, "FFmpeg process successful. Output: " + outputUriOrPath);
                cleanupTemporaryFile(); // Delete the temp file (inputUriOrPath)

                // Optional: Update records UI / Media Scanner if needed (more complex for SAF)

            } else {
                Log.e(TAG, "FFmpeg failed! Logs:");
                Log.e(TAG, session.getAllLogsAsString());
                Log.e(TAG, "Command was: "+ffmpegCommand);
                Toast.makeText(this, "Error processing video", Toast.LENGTH_LONG).show();

                // Try to cleanup the FAILED output file if it was created via SAF
                if (outputUriOrPath != null && outputUriOrPath.startsWith("content://")) {
                    try {
                        DocumentFile failedOutput = DocumentFile.fromSingleUri(this, Uri.parse(outputUriOrPath));
                        if(failedOutput != null && failedOutput.exists() && failedOutput.delete()){
                            Log.d(TAG,"Deleted failed SAF output file: "+ outputUriOrPath);
                        } else {
                            Log.w(TAG, "Could not delete failed SAF output file: "+outputUriOrPath);
                        }
                    } catch (Exception e) {
                        Log.e(TAG,"Error deleting failed SAF output", e);
                    }
                }
                // IMPORTANT: DO NOT delete the input temp file if processing failed.
                currentRecordingFile = null; // Clear tracking refs so subsequent cleanup doesn't try again
                currentRecordingSafUri = null;
                currentRecordingDocFile = null;
            }

            isProcessingWatermark = false; // Reset flag *after* cleanup/logging
            // Check if service can stop now
            if (!isWorkingInProgress()) {
                Log.d(TAG,"FFmpeg finished and no other work pending, stopping service.");
                stopSelf();
            } else {
                Log.d(TAG,"FFmpeg finished, but service might still be recording/working.");
            }
        });
    }


    // Centralized Temp File Cleanup
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
        int videoBitrate = Utils.estimateBitrate(sharedPreferencesManager.getCameraResolution(), sharedPreferencesManager.getVideoFrameRate());
        Log.d(TAG, "Selected Video Bitrate: " + videoBitrate + " bps");
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
    private void toggleRecordingTorch() {
        if (captureRequestBuilder != null && captureSession != null && cameraDevice != null) {
            try {
                isRecordingTorchEnabled = !isRecordingTorchEnabled; // Toggle the state
                Log.d(TAG,"Toggling recording torch. New state: "+ isRecordingTorchEnabled);

                captureRequestBuilder.set(CaptureRequest.FLASH_MODE,
                        isRecordingTorchEnabled ? CaptureRequest.FLASH_MODE_TORCH : CaptureRequest.FLASH_MODE_OFF);

                // Apply the change by updating the repeating request
                captureSession.setRepeatingRequest(captureRequestBuilder.build(), null, backgroundHandler);
                Log.d(TAG, "Recording torch repeating request updated.");

                // Broadcast state change TO THE UI if needed (though UI might request it directly?)
                Intent intent = new Intent(Constants.BROADCAST_ON_TORCH_STATE_CHANGED);
                intent.putExtra(Constants.INTENT_EXTRA_TORCH_STATE, isRecordingTorchEnabled);
                sendBroadcast(intent);

            } catch (CameraAccessException e) {
                Log.e(TAG, "Could not toggle recording torch: " + e.getMessage());
                isRecordingTorchEnabled = !isRecordingTorchEnabled; // Revert state on error
            } catch(IllegalStateException e) {
                Log.e(TAG,"Could not toggle recording torch - session/camera closed?", e);
                isRecordingTorchEnabled = !isRecordingTorchEnabled; // Revert state on error
            }
        } else {
            Log.w(TAG, "Cannot toggle recording torch - session, request builder, or camera device is null.");
        }
    }
    // --- End Torch Logic ---

    // --- Status Check ---
    public boolean isRecording() {
        return recordingState == RecordingState.IN_PROGRESS;
    }

    public boolean isPaused() {
        return recordingState == RecordingState.PAUSED;
    }

    // Combined status check
    public boolean isWorkingInProgress() {
        return isProcessingWatermark || recordingState != RecordingState.NONE;
    }
    // --- End Status Check ---
}