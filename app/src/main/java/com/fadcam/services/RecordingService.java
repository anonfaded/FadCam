/**
 * RecordingService.java
 *
 * Refactored to use RecordingPipeline for live OpenGL watermarking.
 * Removes FFmpeg post-processing.
 *
 * Key Changes:
 * - Integrated RecordingPipeline to handle GL rendering thread.
 * - Camera output directed to RecordingPipeline's SurfaceTexture.
 * - RecordingPipeline renders camera frames + watermark onto MediaRecorder's Surface.
 * - FFmpeg logic removed; saved temp file is now the final (watermarked) file.
 * - Error handling and logging added/updated.
 */
package com.fadcam.services;

import android.Manifest;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.UriPermission;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper; // Import Looper
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.preference.PreferenceManager;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.util.Log; // Standard Android Log
import android.util.Range;
import android.util.Size;
import android.view.Surface;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.documentfile.provider.DocumentFile; // Needed for SAF operations

import com.fadcam.CameraType;
import com.fadcam.Constants;
import com.fadcam.MainActivity;
import com.fadcam.R;
import com.fadcam.RecordingState;
import com.fadcam.SharedPreferencesManager;
import com.fadcam.Utils;
import com.fadcam.VideoCodec;
import com.fadcam.opengl.RecordingPipeline; // Import the pipeline

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

// Make RecordingService implement the pipeline callbacks
public class RecordingService extends Service implements RecordingPipeline.RecordingPipelineCallbacks {

    private static final int NOTIFICATION_ID = 1; // Keep existing notification ID
    private static final String CHANNEL_ID = "RecordingServiceChannel";
    private static final String TAG = "RecService"; // Concise TAG

    // Core components
    private MediaRecorder mediaRecorder;
    private CameraDevice cameraDevice;
    private CameraCaptureSession captureSession;
    private CaptureRequest.Builder captureRequestBuilder;
    private CameraManager cameraManager;
    private SharedPreferencesManager sharedPreferencesManager;
    private RecordingPipeline recordingPipeline; // *** ADDED RecordingPipeline instance ***

    // State and Timing
    private RecordingState recordingState = RecordingState.NONE;
    private long recordingStartTime;

    // Surfaces
    private Surface previewSurface; // Surface from UI for optional preview
    private SurfaceTexture pipelineInputSurfaceTexture; // SurfaceTexture from the pipeline (Camera -> Pipeline)
    private Surface pipelineInputSurface; // Surface wrapper for pipelineInputSurfaceTexture
    private Surface recorderSurface; // Surface for MediaRecorder output (Pipeline -> MediaRecorder)

    // File Management
    private File currentInternalTempFile; // Cache file path when saving internally/before moving to SAF

    // Threading
    private HandlerThread backgroundThread;
    private Handler backgroundHandler;

    // Synchronization - crucial for coordinating pipeline readiness and camera setup
    private final CountDownLatch pipelineSurfaceReadyLatch = new CountDownLatch(1);
    private volatile boolean pipelineSetupError = false;

    // Torch State (During Recording)
    private boolean isRecordingTorchEnabled = false;
    private Size compatibleSurfaceTextureSize = null;
    // Service Lifecycle Semaphores/Locks
    private final Semaphore cameraCloseSemaphore = new Semaphore(1); // Ensure camera closes gracefully

    // File Management

    private Uri currentRecordingSafUri; // Track SAF URI if custom // << This one exists
    private DocumentFile currentRecordingDocFile; // *** ADD THIS LINE *** Track DocumentFile if custom
    private ParcelFileDescriptor currentParcelFileDescriptor; // Track PFD if custom // << This one exists

    private Size targetOutputSize = null;
    // --- Add New Member Variables ---
    private ImageReader imageReader;
    private final ImageReader.OnImageAvailableListener onImageAvailableListener =
            reader -> {
                Image image = null;
                try {
                    image = reader.acquireLatestImage();
                    if (image != null) {
                        // *** Important: Need to pass this Image to the RecordingPipeline ***
                        // RecordingPipeline needs a new method like 'processNewImage(Image image)'
                        // For now, we just log and close it. Pipeline integration comes next.
                        if (recordingPipeline != null) {
                            // TODO: Implement queueing/passing logic if GL thread is busy
                            // For now, assume pipeline can handle it directly (simplification)
                            recordingPipeline.queueNewFrame(image); // Assuming a queuing method
                            // Image will be closed by the pipeline after processing
                        } else {
                            Log.w(TAG,"ImageReader CB: Pipeline null, closing image.");
                            image.close(); // Close immediately if pipeline isn't ready
                        }
                    }
                } catch (Exception e) {
                    Log.e(TAG, "ImageReader Listener Error acquiring/processing image", e);
                    if (image != null) image.close(); // Ensure closed on error
                }
            };
// -------------------------------
    // --- Service Lifecycle Methods ---

    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, ">>> Service Creating... <<<");

        sharedPreferencesManager = SharedPreferencesManager.getInstance(getApplicationContext());
        cameraManager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);

        if (cameraManager == null) {
            Log.e(TAG, "CameraManager service unavailable. Cannot function.");
            broadcastCameraError("Camera system service not found.");
            stopSelf(); return;
        }

        // Setup background thread for camera and potentially pipeline operations
        backgroundThread = new HandlerThread("RecordingServiceBG");
        backgroundThread.start();
        backgroundHandler = new Handler(backgroundThread.getLooper());

        createNotificationChannel(); // Setup notifications

        // *** Initialize RecordingPipeline ***
        int videoWidth = sharedPreferencesManager.getCameraResolution().getWidth();
        int videoHeight = sharedPreferencesManager.getCameraResolution().getHeight();
        try {
            recordingPipeline = new RecordingPipeline(getApplicationContext(), videoWidth, videoHeight, this); // 'this' is the callback listener
            recordingPipeline.start(); // Start its GL thread
            Log.d(TAG, "RecordingPipeline thread started. Waiting for surface...");
        } catch (Exception e) {
            Log.e(TAG, "Failed to initialize or start RecordingPipeline", e);
            pipelineSetupError = true; // Mark pipeline setup as failed
            broadcastCameraError("Failed to initialize rendering pipeline."); // Notify UI
            stopSelf(); return; // Stop service if pipeline fails
        }

        Log.i(TAG, ">>> Service Created Successfully <<<");
    }


    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "onStartCommand received: Action=" + (intent != null ? intent.getAction() : "null"));
        if (intent == null || intent.getAction() == null) {
            Log.w(TAG, "Service received null intent or action, stopping potentially idle service.");
            checkIfServiceCanStop(); // Check if service should stop
            return START_NOT_STICKY; // Don't restart if killed with null intent
        }

        String action = intent.getAction();
        Log.i(TAG, "Handling action: " + action + " | Current State: " + recordingState);

        switch (action) {
            case Constants.INTENT_ACTION_START_RECORDING:
                // --- Handle START request ---
                if (recordingState != RecordingState.NONE) {
                    Log.w(TAG, "Start Recording requested, but already active/paused (State: " + recordingState + "). Ignoring.");
                    broadcastOnRecordingStateCallback(); // Send current state back
                } else {
                    Log.d(TAG,"Processing START_RECORDING intent...");
                    setupPreviewSurface(intent); // Get optional preview surface from intent
                    // Show foreground notification immediately for user awareness
                    setupRecordingNotification(false); // false = not paused
                    // Initiate the full recording start sequence
                    handleStartRecording();
                }
                break;

            case Constants.INTENT_ACTION_PAUSE_RECORDING:
                if (recordingState == RecordingState.IN_PROGRESS) {
                    handlePauseRecording();
                } else { Log.w(TAG, "Pause requested, but not IN_PROGRESS (State: "+recordingState+")."); }
                break;

            case Constants.INTENT_ACTION_RESUME_RECORDING:
                if (recordingState == RecordingState.PAUSED) {
                    setupPreviewSurface(intent); // Re-acquire preview surface if needed
                    handleResumeRecording();
                } else { Log.w(TAG, "Resume requested, but not PAUSED (State: "+recordingState+")."); }
                break;

            case Constants.INTENT_ACTION_CHANGE_SURFACE:
                setupPreviewSurface(intent);
                // Re-attach surface to pipeline if recording/paused
                if (recordingPipeline != null && (isRecording() || isPaused())) {
                    recordingPipeline.setPreviewSurface(previewSurface);
                }
                break;

            case Constants.INTENT_ACTION_STOP_RECORDING:
                if (recordingState != RecordingState.NONE) {
                    handleStopRecording();
                } else { Log.w(TAG,"Stop requested, but already stopped/idle."); }
                break;

            case Constants.BROADCAST_ON_RECORDING_STATE_REQUEST:
                Log.d(TAG, "Responding to state request with: " + recordingState);
                broadcastOnRecordingStateCallback();
                break;

            case Constants.INTENT_ACTION_TOGGLE_RECORDING_TORCH:
                if (isRecording() || isPaused()) { // Only toggle if camera session is active
                    toggleRecordingTorch();
                } else { Log.w(TAG,"Cannot toggle torch, not recording/paused."); }
                break;

            default:
                Log.w(TAG, "Received unhandled action: " + action);
                break;
        }

        // Keep service running while recording/paused
        return START_STICKY;
    }


    @Override
    public void onDestroy() {
        Log.w(TAG, ">>> Service Destroying... <<<");
        // Ensure all resources are cleaned up properly
        releaseRecordingResources(); // Release Camera, Recorder, Pipeline

        // Stop background thread
        if (backgroundThread != null) {
            backgroundThread.quitSafely();
            backgroundThread = null;
            backgroundHandler = null;
        }

        cancelNotification(); // Make sure notification is gone

        Log.w(TAG, ">>> Service Destroyed <<<");
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) { return null; } // Not a bound service

    // --- End Service Lifecycle Methods ---


    // Inside RecordingService.java class

    /**
     * Handles the overall sequence to start video recording.
     * Checks permissions, updates state, ensures pipeline size is set,
     * and posts the main setup work (MediaRecorder, Camera) to the background thread.
     */
    private void handleStartRecording() {
        Log.i(TAG, "handleStartRecording: Initiating start sequence.");

        // 1. Permission Check
        if (!hasRequiredPermissions()) {
            stopSelfWithMessage("Permissions missing."); return;
        }

        // 2. Update Service State (Optimistic)
        recordingState = RecordingState.IN_PROGRESS;
        sharedPreferencesManager.setRecordingInProgress(true);
        Log.d(TAG, "Service state set to IN_PROGRESS.");

        // 3. *** FIX: Determine and Set Pipeline Size BEFORE Background Post ***
        try {
            queryAndSetPipelineTargetSize(); // Call the helper here
        } catch (Exception e) {
            Log.e(TAG, "Failed to determine or set pipeline target size", e);
            cleanupFailedStart("Camera configuration error (size): " + e.getMessage());
            return; // Don't proceed if size setting fails
        }
        // Size is now set on the pipeline instance

        // 4. Post Setup Tasks to Background Thread
        if (backgroundHandler == null) { cleanupFailedStart("Background thread not available."); return; }
        backgroundHandler.post(() -> {
            Log.d(TAG,"handleStartRecording (BG): Starting background setup...");
            try {
                // a. Wait for pipeline's input surface (with timeout)
                if (!waitForPipelineSurface()) {
                    throw new RuntimeException("Pipeline surface timed out or failed.");
                }
                Log.d(TAG,"handleStartRecording (BG): Pipeline input surface ready.");

                // Optional delay
                try { Thread.sleep(50); } catch (InterruptedException ignore) {}

                // b. Setup MediaRecorder
                setupMediaRecorder();
                Log.d(TAG,"handleStartRecording (BG): MediaRecorder setup complete.");

                // c. Link Pipeline Output -> MediaRecorder Input
                Surface recSurface = getRecorderSurface();
                if (recSurface != null) {
                    Log.d(TAG,"handleStartRecording (BG): Giving MediaRecorder surface to pipeline.");
                    recordingPipeline.startRecording(recSurface);
                } else {
                    throw new IOException("Failed to get a valid surface from MediaRecorder.");
                }

                // d. Open Camera
                openCamera(); // No need to query size again inside here now
                Log.d(TAG,"handleStartRecording (BG): openCamera call initiated.");

            } catch (Exception e) {
                Log.e(TAG, "Error during background recording setup sequence", e);
                cleanupFailedStart("Setup failed: " + e.getMessage());
            }
        });
        Log.d(TAG,"handleStartRecording: Background setup posted.");
    }



    /**
     * Helper method to log an error, show a Toast, and stop the service.
     * Ensures Toast is shown on the main thread.
     * @param message The error message to log and show.
     */
    private void stopSelfWithMessage(String message) {
        Log.e(TAG, "Stopping service due to error: " + message);
        // Ensure Toast runs on the main thread
        new Handler(Looper.getMainLooper()).post(() ->
                Toast.makeText(getApplicationContext(), message, Toast.LENGTH_LONG).show()
        );
        // Release resources before stopping if applicable (e.g., if some were partially acquired)
        releaseRecordingResources(); // Add a release call here just in case
        stopSelf(); // Stop the service
    }


    /** Waits for the RecordingPipeline to provide its input SurfaceTexture. */
    private boolean waitForPipelineSurface() {
        if (pipelineInputSurfaceTexture != null) { return true; } // Already available
        if (pipelineSetupError) { return false; } // Pipeline failed during init
        Log.d(TAG,"Waiting for pipeline surface texture...");
        try {
            // Wait for a reasonable time (e.g., 5 seconds)
            if (!pipelineSurfaceReadyLatch.await(5, TimeUnit.SECONDS)) {
                Log.e(TAG, "Timeout waiting for pipeline surface texture.");
                pipelineSetupError = true; // Mark as error if timeout occurs
                return false;
            }
            if (pipelineSetupError) { // Check again after wait, callback might have set error
                Log.e(TAG,"Pipeline signaled an error during surface wait.");
                return false;
            }
            Log.d(TAG,"Pipeline surface texture acquired successfully.");
            return true;
        } catch (InterruptedException e) {
            Log.e(TAG, "Interrupted while waiting for pipeline surface.");
            Thread.currentThread().interrupt();
            pipelineSetupError = true;
            return false;
        }
    }

    private void handleStopRecording() {
        Log.i(TAG, "handleStopRecording: Stopping sequence initiated. Current state: " + recordingState);
        if (recordingState == RecordingState.NONE) { return; } // Already stopped

        final RecordingState stateBeforeStop = recordingState;
        recordingState = RecordingState.NONE; // Update state FIRST
        sharedPreferencesManager.setRecordingInProgress(false); // Update prefs

        // Order: Stop GL rendering -> Stop MediaRecorder -> Close Session -> Close Camera
        if (recordingPipeline != null) {
            Log.d(TAG, "Signaling pipeline to stop rendering.");
            recordingPipeline.stopRecording();
            // We don't wait for pipeline stopped callback here, proceed to MR stop.
        }

        // Use a volatile variable to track MediaRecorder state
        final boolean[] mrStoppedCleanly = {false};

        if (mediaRecorder != null) {
            Log.d(TAG, "Stopping MediaRecorder...");
            try {
                // Needs to run potentially on background or carefully timed
                // Let's risk stopping it on the current (likely background or main if shortcut) thread for simplicity *now*,
                // but ideally should be posted to backgroundHandler or sync'd if complex operations follow.
                mediaRecorder.stop(); // *** Potential ANR/block risk here if not careful ***
                mrStoppedCleanly[0] = true;
                Log.d(TAG, "MediaRecorder stopped.");
            } catch (RuntimeException e) { // MediaRecorder.stop can throw RuntimeException
                Log.e(TAG, "MediaRecorder stop() failed (RuntimeException): " + e.getMessage() + ". Treating as stopped for cleanup.", e);
                mrStoppedCleanly[0] = true; // Allow cleanup to proceed even on error
            } catch (Exception e){
                Log.e(TAG, "MediaRecorder stop() failed (Exception): " + e.getMessage() + ". Treating as stopped for cleanup.", e);
                mrStoppedCleanly[0] = true; // Allow cleanup to proceed even on error
            }
        } else { mrStoppedCleanly[0] = true; } // No recorder to stop

        // Proceed with cleanup and final file handling on background thread
        backgroundHandler.post(() -> {
            Log.d(TAG,"handleStopRecording (BG): Starting background cleanup...");
            File processedFile = currentInternalTempFile; // The file saved by MediaRecorder is the watermarked one

            // Close camera session and device
            closeCameraSession();
            closeCameraDevice();

            // Release MediaRecorder instance *after* camera/session closed
            if (mediaRecorder != null) {
                releaseMediaRecorderSafely();
            }

            // Handle the final (watermarked) temp file
            if (processedFile != null && processedFile.exists() && processedFile.length() > 0) {
                Log.d(TAG,"handleStopRecording (BG): Temp file exists and has size. Proceeding to finalize.");
                finalizeRecordedFile(processedFile); // Move temp file to final destination
            } else {
                Log.w(TAG,"handleStopRecording (BG): Temp file missing, empty, or null after recording stopped.");
                broadcastRecordingComplete(null, false); // Notify failure if file is bad
            }

            // Update UI and stop service if idle
            new Handler(Looper.getMainLooper()).post(() -> {
                Log.d(TAG,"handleStopRecording (MainThread): Broadcasting STOPPED and checking service state.");
                broadcastOnRecordingStopped(); // Inform UI recording hardware stopped
                checkIfServiceCanStop();       // Stop service if truly idle
            });
            Log.d(TAG,"handleStopRecording (BG): Background cleanup finished.");
        });

        // --- Perform immediate UI related tasks on the calling thread ---
        // This ensures responsiveness even if background cleanup takes time
        stopForeground(true); // Remove from foreground state immediately
        cancelNotification();   // Remove the notification immediately
        Log.d(TAG, "Foreground/Notification immediately removed.");
    }

    private void handlePauseRecording() {
        if (recordingState != RecordingState.IN_PROGRESS) return;
        Log.i(TAG, "Pausing Recording...");
        try {
            if (mediaRecorder != null) {
                mediaRecorder.pause(); // Pause the encoder
                recordingState = RecordingState.PAUSED;
                sharedPreferencesManager.setRecordingInProgress(false); // Update prefs to indicate not actively recording
                // Don't update foreground here if MediaRecorder requires it to stay active during pause
                // setupRecordingNotification(true); // true = is paused
                showRecordingInPausedToast();
                broadcastOnRecordingPaused();
                Log.d(TAG, "Recording paused (MediaRecorder paused).");
            }
        } catch (IllegalStateException e) {
            Log.e(TAG, "Error pausing MediaRecorder", e);
            handleStopRecording(); // Treat as stop on error
        } catch(Exception e){
            Log.e(TAG, "Unexpected error pausing", e);
            handleStopRecording();
        }
    }

    private void handleResumeRecording() {
        if (recordingState != RecordingState.PAUSED) return;
        Log.i(TAG, "Resuming Recording...");
        try {
            if (mediaRecorder != null) {
                mediaRecorder.resume(); // Resume the encoder
                recordingState = RecordingState.IN_PROGRESS;
                sharedPreferencesManager.setRecordingInProgress(true); // Update prefs
                setupRecordingNotification(false); // false = not paused
                showRecordingResumedToast();
                broadcastOnRecordingResumed();
                Log.d(TAG, "Recording resumed (MediaRecorder resumed).");
            }
        } catch (IllegalStateException e) {
            Log.e(TAG, "Error resuming MediaRecorder", e);
            handleStopRecording(); // Treat as stop on error
        } catch(Exception e){
            Log.e(TAG, "Unexpected error resuming", e);
            handleStopRecording();
        }
    }

    // Add helper to release ImageReader
    private void releaseImageReader() {
        if (imageReader != null) {
            try { imageReader.close(); Log.d(TAG, "ImageReader closed."); }
            catch (Exception e) { Log.e(TAG,"Error closing ImageReader", e); }
            finally { imageReader = null; }
        }
    }
    // --- Resource Cleanup & Management ---

    private void releaseRecordingResources() {
        Log.w(TAG, ">>> Releasing ALL Recording Resources <<<");

        // Release in reverse order of creation/dependency
        // 1. Close Camera Session and Device (ensure blocking if needed)
        closeCameraSession(); // Close session first
        closeCameraDevice(); // Then close device

        // 2. Release MediaRecorder
        releaseMediaRecorderSafely(); // Handles null checks and exceptions
        releaseImageReader();
        // 3. Release Recording Pipeline (GL resources)
        if (recordingPipeline != null) {
            recordingPipeline.release();
            recordingPipeline = null;
            Log.d(TAG, "RecordingPipeline released.");
        }

        // 4. Release Surfaces (input texture handled by pipeline, recorder surface implicitly by MediaRecorder.release)
        if (previewSurface != null) {
            // Don't release previewSurface here if it belongs to the UI TextureView!
            // It should be managed by the TextureView's lifecycle.
            // If it was created internally, release it. Check ownership.
            // For now, assume UI owns it.
            // previewSurface.release(); // Typically AVOID releasing UI surfaces here
            previewSurface = null;
            Log.d(TAG, "Preview surface reference cleared (UI owns release).");
        }

        // 5. Clear File/URI tracking
        closeParcelFileDescriptor(); // Close SAF PFD if open
        currentInternalTempFile = null;
        currentRecordingDocFile = null;
        currentRecordingSafUri = null;

        // 6. Reset State
        recordingState = RecordingState.NONE;
        if (sharedPreferencesManager != null && sharedPreferencesManager.isRecordingInProgress()) {
            Log.w(TAG, "Force resetting recording_in_progress pref during resource release.");
            sharedPreferencesManager.setRecordingInProgress(false);
        }
        Log.w(TAG, ">>> Finished Releasing ALL Recording Resources <<<");
    }


    /** Safely closes the camera device, waiting if necessary */
    private void closeCameraDevice() {
        try {
            // Ensure operations finish before closing. Increase timeout if needed.
            if (!cameraCloseSemaphore.tryAcquire(2500, TimeUnit.MILLISECONDS)) {
                Log.e(TAG, "Timeout waiting to close camera device!");
            }
            if (cameraDevice != null) {
                Log.d(TAG,"Closing camera device...");
                cameraDevice.close();
                cameraDevice = null;
                Log.d(TAG,"Camera device closed.");
            }
        } catch (InterruptedException e) {
            Log.e(TAG, "Interrupted waiting to close camera.", e);
            Thread.currentThread().interrupt();
        } catch (Exception e){
            Log.e(TAG, "Exception closing camera device.", e);
        } finally {
            cameraCloseSemaphore.release(); // Always release semaphore
        }
    }


    /** Safely closes the capture session */
    private void closeCameraSession() {
        if (captureSession != null) {
            try {
                Log.d(TAG,"Closing capture session...");
                captureSession.close();
                captureSession = null;
                Log.d(TAG,"Capture session closed.");
            } catch (Exception e) {
                Log.w(TAG, "Error closing capture session", e);
                captureSession = null; // Still nullify on error
            }
        }
    }


    /** Helper method to fully release MediaRecorder instance safely */
    private void releaseMediaRecorderSafely() {
        if (mediaRecorder != null) {
            Log.d(TAG, "Releasing MediaRecorder instance...");
            try { mediaRecorder.reset(); Log.v(TAG, "MediaRecorder reset."); }
            catch (IllegalStateException e) { Log.w(TAG, "Ignoring IllegalStateException on MR reset."); }
            catch (Exception e) { Log.w(TAG, "Exception during MediaRecorder reset", e); }
            try{ mediaRecorder.release(); Log.d(TAG, "MediaRecorder released.");}
            catch(Exception e){ Log.w(TAG, "Exception during MediaRecorder release", e);}
            finally { mediaRecorder = null; }
        }
        if(recorderSurface != null) { // Release the recorder surface ref we held
            // recorderSurface.release(); // Usually owned by MediaRecorder, releasing MR should handle it.
            recorderSurface = null;
            Log.d(TAG, "MediaRecorder surface reference cleared.");
        }
    }


    // Modify openCamera to setup ImageReader size
    private void openCamera() {
        // ... (permission checks, getTargetCameraId as before) ...
        String cameraToOpenId = getTargetCameraId();
        if (cameraToOpenId == null) { cleanupFailedStart("Could not determine Cam ID"); return; }

        try {
            CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(cameraToOpenId);
            StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if (map == null) { cleanupFailedStart("No StreamMap"); return; }

            Size desiredResolution = sharedPreferencesManager.getCameraResolution();

            // *** Find size for ImageReader (Use PRIVATE if possible, fallback YUV) ***
            // Using PRIVATE format is often more efficient if HardwareBuffers (API 26+) are used later for zero-copy GPU upload
            // Requires careful OpenGL implementation. Let's start with YUV_420_888 for wider compatibility first.
            int imageFormat = ImageFormat.YUV_420_888; // Start with YUV
            Size imageReaderSize = findBestSupportedSize(map.getOutputSizes(imageFormat), desiredResolution);
            if(imageReaderSize == null){
                Log.w(TAG,"No suitable YUV size found, checking PRIVATE");
                imageFormat = ImageFormat.PRIVATE; // Fallback check
                imageReaderSize = findBestSupportedSize(map.getOutputSizes(imageFormat), desiredResolution);
            }

            // If STILL null, get ANY YUV size
            if(imageReaderSize == null) {
                Size[] yuvSizes = map.getOutputSizes(ImageFormat.YUV_420_888);
                if(yuvSizes != null && yuvSizes.length > 0) imageReaderSize = yuvSizes[0];
                else imageReaderSize = new Size(1280,720); // Last resort default
                imageFormat = ImageFormat.YUV_420_888; // Ensure format matches fallback size query
                Log.w(TAG,"Using fallback ImageReader Size/Format: " + imageReaderSize + " / " + imageFormat);
            }
            Log.i(TAG,"Selected ImageReader Size/Format: " + imageReaderSize + " / " + imageFormat);

            // --- Create ImageReader ---
            releaseImageReader(); // Release previous if exists
            imageReader = ImageReader.newInstance(
                    imageReaderSize.getWidth(),
                    imageReaderSize.getHeight(),
                    imageFormat, // Use the determined format
                    2 // maxImages buffer
            );
            imageReader.setOnImageAvailableListener(onImageAvailableListener, backgroundHandler); // Listener on background thread
            Log.d(TAG, "ImageReader created for size " + imageReaderSize + " format " + imageFormat);
            // Note: compatibleSurfaceTextureSize is NO LONGER NEEDED as camera won't target pipeline directly
            compatibleSurfaceTextureSize = null; // Clear this potentially confusing variable

            // --- Inform Pipeline about the expected Frame Size (if needed by pipeline init) ---
            // This step depends on whether RecordingPipeline needs the size BEFORE receiving the first image
            if (recordingPipeline != null) {
                // We no longer set TARGET size, pipeline gets size from Image format now
                // recordingPipeline.setTargetOutputSize(imageReaderSize); // OBSOLETE - remove call if exists
                Log.d(TAG, "ImageReader created, pipeline will get size from image format.");
            }


            // ... (rest of openCamera: semaphore, open camera call) ...
            cameraManager.openCamera(cameraToOpenId, cameraStateCallback, backgroundHandler);

        } catch (Exception e) {
            Log.e(TAG, "openCamera: Error setting up ImageReader or opening camera", e);
            cleanupFailedStart("Camera open/setup failed: "+e.getMessage());
        } // Add appropriate finally for semaphore release
    }

    // Helper function to find best supported size (add this to RecordingService)
    private Size findBestSupportedSize(Size[] supportedSizes, Size desiredSize) {
        if (supportedSizes == null || supportedSizes.length == 0) return null;

        Size bestSize = null;
        long desiredArea = (long)desiredSize.getWidth() * desiredSize.getHeight();
        long minDiff = Long.MAX_VALUE;

        for (Size size : supportedSizes) {
            // Exact match? Perfect.
            if (size.equals(desiredSize)) {
                return size;
            }
            // Check aspect ratio match (within tolerance)
            double desiredRatio = (double)desiredSize.getWidth() / desiredSize.getHeight();
            double supportedRatio = (double)size.getWidth() / size.getHeight();
            if (Math.abs(desiredRatio - supportedRatio) < 0.05) { // Allow 5% tolerance
                long supportedArea = (long)size.getWidth() * size.getHeight();
                long diff = Math.abs(supportedArea - desiredArea);
                // Prefer sizes >= desired area, but take closest smaller if no larger ones match aspect ratio
                if(supportedArea >= desiredArea){
                    if (diff < minDiff) {
                        minDiff = diff; bestSize = size;
                    }
                } else if (bestSize == null || diff < minDiff) { // Only consider smaller if no >= match found yet
                    // Be more strict with smaller? Optional. Keep simple for now.
                    minDiff = diff; bestSize = size;
                }
            }
        }
        // If no aspect ratio match, just return the first one? Or one closest in area?
        if (bestSize == null) {
            Log.w(TAG,"No good aspect ratio match found for "+desiredSize+". Finding closest area.");
            minDiff = Long.MAX_VALUE;
            for(Size size : supportedSizes) {
                long supportedArea = (long)size.getWidth() * size.getHeight();
                long diff = Math.abs(supportedArea - desiredArea);
                if (diff < minDiff) { minDiff = diff; bestSize = size; }
            }
        }

        return bestSize; // Can still be null if supportedSizes was empty
    }



    /**
     * StateCallback for CameraDevice lifecycle events.
     */
    private final CameraDevice.StateCallback cameraStateCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(@NonNull CameraDevice camera) {
            cameraCloseSemaphore.release(); // Allow semaphore for subsequent closes
            Log.i(TAG, "Camera " + camera.getId() + " opened successfully.");
            cameraDevice = camera;
            // Now that camera is open, create the session using pipeline surface
            createCameraCaptureSession();
        }
        @Override
        public void onDisconnected(@NonNull CameraDevice camera) {
            cameraCloseSemaphore.release();
            Log.w(TAG, "Camera " + camera.getId() + " disconnected.");
            handleCameraErrorOrDisconnect(camera, "Camera disconnected.");
            cleanupFailedStart("Camera disconnected.");
        }
        @Override
        public void onError(@NonNull CameraDevice camera, int error) {
            cameraCloseSemaphore.release();
            Log.e(TAG, "Camera " + camera.getId() + " error: " + errorDesc(error));
            broadcastCameraError("Camera error: "+errorDesc(error) + " ("+error+")"); // Notify UI
            handleCameraErrorOrDisconnect(camera, "Camera error: " + error);
            cleanupFailedStart("Camera error state.");
        }
        @Override
        public void onClosed(@NonNull CameraDevice camera) {
            cameraCloseSemaphore.release(); // Ensure released even on closed callback
            Log.d(TAG,"Camera " + camera.getId() + " state callback: CLOSED.");
            cameraDevice = null; // Clear reference on close
        }
    };

    // --- Error Handling ---

    /**
     * Centralized handling for fatal errors during startup.
     * Releases resources, updates state, shows toast, and stops service.
     */
    private void cleanupFailedStart(String reason) {
        Log.e(TAG, "cleanupFailedStart: Reason: " + reason);
        // Run on Main thread for Toast/UI updates, then proceed with cleanup
        new Handler(Looper.getMainLooper()).post(() -> {
            Toast.makeText(getApplicationContext(), "Recording failed: " + reason, Toast.LENGTH_LONG).show();
            broadcastOnRecordingStopped(); // Tell UI recording stopped (even if it didn't fully start)
        });
        releaseRecordingResources(); // Release any acquired resources
        stopSelf(); // Stop the service
    }


    /** Generic handling for camera disconnects or non-startup errors */
    private void handleCameraErrorOrDisconnect(CameraDevice camera, String reason) {
        Log.e(TAG, "handleCameraErrorOrDisconnect: Camera ID: " + (camera != null ? camera.getId() : "null") + ", Reason: " + reason);
        if(camera != null) camera.close(); // Ensure camera device is closed
        // If currently recording/paused, initiate a stop and cleanup
        if (recordingState != RecordingState.NONE) {
            Log.w(TAG,"Camera error/disconnect occurred while recording/paused. Forcing stop.");
            new Handler(Looper.getMainLooper()).post(() -> {
                Toast.makeText(this, "Recording stopped due to camera issue.", Toast.LENGTH_LONG).show();
            });
            handleStopRecording(); // Trigger the standard stop sequence
        } else {
            Log.d(TAG,"Camera error/disconnect occurred while idle. Ensuring resources are released.");
            releaseRecordingResources(); // Ensure cleanup if occurred when idle
            checkIfServiceCanStop(); // Stop if now truly idle
        }
    }


    /** Provides descriptive string for CameraDevice.StateCallback error codes */
    private String errorDesc(int error) {
        switch (error) {
            case CameraDevice.StateCallback.ERROR_CAMERA_IN_USE: return "Camera In Use";
            case CameraDevice.StateCallback.ERROR_MAX_CAMERAS_IN_USE: return "Max Cameras In Use";
            case CameraDevice.StateCallback.ERROR_CAMERA_DISABLED: return "Camera Disabled";
            case CameraDevice.StateCallback.ERROR_CAMERA_DEVICE: return "Camera Device Error";
            case CameraDevice.StateCallback.ERROR_CAMERA_SERVICE: return "Camera Service Error";
            default: return "Unknown Error";
        }
    }

    /** Broadcasts a camera-related error message to the UI */
    private void broadcastCameraError(String message) {
        Log.e(TAG,"Broadcasting Camera Error: " + message);
        Intent errorIntent = new Intent(Constants.BROADCAST_CAMERA_ERROR);
        errorIntent.putExtra("error_message", message);
        sendBroadcast(errorIntent);
        // Consider stopping service depending on severity? For now, just notify.
    }


    // Modify createCameraCaptureSession to target ImageReader
    private void createCameraCaptureSession() {
        if (cameraDevice == null || backgroundHandler == null) { /* ... handle error ...*/ return; }
        if (imageReader == null || imageReader.getSurface() == null || !imageReader.getSurface().isValid()) { // Check ImageReader surface
            cleanupFailedStart("ImageReader surface invalid!"); return;
        }
        if (captureSession != null) closeCameraSession();

        Log.d(TAG, "createCameraCaptureSession (using ImageReader): Creating session...");
        try {
            List<Surface> surfaces = new ArrayList<>();
            surfaces.add(imageReader.getSurface()); // *** Camera OUTPUTS to ImageReader ***
            Log.d(TAG, "Session: Added ImageReader Surface.");

            // Preview surface can still be targeted directly by camera *in addition* to ImageReader
            if (previewSurface != null && previewSurface.isValid()) {
                surfaces.add(previewSurface);
                Log.d(TAG, "Session: Added UI Preview Surface.");
            }

            // Use TEMPLATE_PREVIEW or TEMPLATE_RECORD - PREVIEW often more compatible with ImageReader
            captureRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            captureRequestBuilder.addTarget(imageReader.getSurface()); // ** Target ImageReader **
            if (previewSurface != null && previewSurface.isValid()) {
                captureRequestBuilder.addTarget(previewSurface); // ** Also target Preview **
            }

            configureOptimalFpsRange(captureRequestBuilder, cameraDevice.getId());
            captureRequestBuilder.set(CaptureRequest.FLASH_MODE, isRecordingTorchEnabled ? CaptureRequest.FLASH_MODE_TORCH : CaptureRequest.FLASH_MODE_OFF);

            Log.d(TAG, "Session: Requesting session creation with " + surfaces.size() + " surface(s).");
            cameraDevice.createCaptureSession(surfaces, captureSessionStateCallback, backgroundHandler);

        } catch (Exception e) {
            Log.e(TAG,"Session: Error creating session with ImageReader", e);
            cleanupFailedStart("Session create fail(IR): "+e.getMessage());
        }
    }

    /**
     * Tries to set the optimal FPS range for the capture request based on user preference
     * and hardware capabilities. Includes fallbacks.
     */
    private void configureOptimalFpsRange(@NonNull CaptureRequest.Builder builder, @NonNull String cameraId) {
        if(cameraManager == null) return; // Safety check
        int targetFps = sharedPreferencesManager.getVideoFrameRate(); // User's preferred rate
        Range<Integer> selectedRange = null;

        try {
            CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(cameraId);
            Range<Integer>[] availableRanges = characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);

            if (availableRanges != null && availableRanges.length > 0) {
                Log.d(TAG, "Available FPS ranges for camera " + cameraId + ": " + Arrays.toString(availableRanges));
                // Find the best range: Prefer fixed targetFps, fallback to widest containing targetFps
                Range<Integer> bestMatch = null;
                Range<Integer> bestContaining = null;

                for (Range<Integer> range : availableRanges) {
                    if(range == null) continue;
                    // Perfect fixed match?
                    if (range.getLower().equals(targetFps) && range.getUpper().equals(targetFps)) {
                        bestMatch = range; break; // Found exact match
                    }
                    // Does the range contain the target FPS?
                    if (range.getLower() <= targetFps && range.getUpper() >= targetFps) {
                        if(bestContaining == null // First containing range
                                || (range.getUpper() - range.getLower()) < (bestContaining.getUpper() - bestContaining.getLower())) // Or narrower containing range
                        {
                            bestContaining = range;
                        }
                    }
                }
                selectedRange = (bestMatch != null) ? bestMatch : bestContaining; // Prioritize fixed match
            } else { Log.w(TAG,"No FPS ranges reported by hardware for camera " + cameraId +".");}

        } catch (CameraAccessException | IllegalArgumentException e) { Log.e(TAG, "Error getting FPS ranges for "+cameraId, e);}

        // If no suitable range found, try creating a basic fixed range
        if(selectedRange == null){
            selectedRange = new Range<>(targetFps, targetFps);
            Log.w(TAG,"No suitable hardware range found. Attempting fixed range: "+selectedRange);
        }

        Log.i(TAG, "Setting FPS range for "+cameraId+": " + selectedRange + " (Target was: " + targetFps +")");
        builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, selectedRange);
    }



    // Modify StateCallback for session configuration
    private final CameraCaptureSession.StateCallback captureSessionStateCallback = new CameraCaptureSession.StateCallback() {
        @Override
        public void onConfigured(@NonNull CameraCaptureSession session) {
            Log.i(TAG, "Capture session CONFIGURED: " + session);
            if (cameraDevice == null || imageReader == null) { // Check ImageReader too
                Log.e(TAG,"onConfigured: CameraDevice or ImageReader became null!");
                session.close(); cleanupFailedStart("Session configured but comps lost."); return;
            }
            captureSession = session;
            try {
                captureRequestBuilder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);
                captureRequestBuilder.set(CaptureRequest.FLASH_MODE, isRecordingTorchEnabled ? CaptureRequest.FLASH_MODE_TORCH : CaptureRequest.FLASH_MODE_OFF);
                Log.d(TAG,"Setting repeating request (using ImageReader)...");
                session.setRepeatingRequest(captureRequestBuilder.build(), null, backgroundHandler);
                Log.d(TAG,"Repeating request set.");

                // Start MediaRecorder AFTER session is confirmed running
                if (recordingState == RecordingState.IN_PROGRESS && mediaRecorder != null) {
                    Log.d(TAG, "Session Configured: State is IN_PROGRESS. Starting MediaRecorder.");
                    try {
                        mediaRecorder.start();
                        Log.i(TAG, "MediaRecorder explicitly started in onConfigured.");
                        recordingStartTime = SystemClock.elapsedRealtime();
                        broadcastOnRecordingStarted(); // Broadcast AFTER successful start
                    } catch (IllegalStateException ise) {
                        Log.w(TAG, "MediaRecorder start() ISE (already started/wrong state?): "+ise.getMessage());
                        // Might still be OK if state was recovered, broadcast start
                        broadcastOnRecordingStarted();
                    } catch (Exception e_mr_start){
                        Log.e(TAG, "onConfigured: Error starting MediaRecorder.", e_mr_start);
                        cleanupFailedStart("Failed start recorder.");
                        return;
                    }
                } else if (recordingState == RecordingState.PAUSED){
                    Log.i(TAG,"Session (re)configured while PAUSED. MediaRecorder remains paused.");
                }

            } catch (Exception e) { // Catch errors setting request
                Log.e(TAG, "onConfigured: Error setting repeating request/starting MR.", e);
                cleanupFailedStart("Session conf/start fail: "+e.getMessage());
            }
        }
        @Override public void onConfigureFailed(@NonNull CameraCaptureSession s) { /*...*/ cleanupFailedStart("Cam conf fail");}
        @Override public void onClosed(@NonNull CameraCaptureSession s) { /*...*/ }
    };


    // --- MediaRecorder Setup ---

    /** Configures MediaRecorder to save to EXTERNAL CACHE temp file. */
    private void setupMediaRecorder() throws IOException {
        releaseMediaRecorderSafely(); // Release any previous instance first
        Log.d(TAG, "setupMediaRecorder: Configuring MediaRecorder...");
        currentInternalTempFile = createTemporaryFile("RecSvc_"); // Create temp file in external cache

        try {
            if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                mediaRecorder = new MediaRecorder(this); // Use context constructor on API 31+
            } else {
                mediaRecorder = new MediaRecorder();
            }
            Log.d(TAG,"MR Sources: Audio=MIC, Video=SURFACE");
            mediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            mediaRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
            Log.d(TAG,"MR Output: Format=MPEG4, File=" + currentInternalTempFile.getAbsolutePath());
            mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            mediaRecorder.setOutputFile(currentInternalTempFile.getAbsolutePath());

            // Apply User Settings
            Size resolution = sharedPreferencesManager.getCameraResolution();
            // Correct way to get specific FPS pref:
            int frameRate = sharedPreferencesManager.getSpecificVideoFrameRate(sharedPreferencesManager.getCameraSelection());
            VideoCodec codec = sharedPreferencesManager.getVideoCodec();
            int bitRate = getVideoBitrate(); // Your bitrate calculation

            Log.d(TAG,"MR Settings: Res="+resolution.getWidth()+"x"+resolution.getHeight()+", FPS="+frameRate+
                    ", Codec="+codec.getName()+", Bitrate="+bitRate+", AudioRate=48k, AudioBit=384k");
            mediaRecorder.setVideoSize(resolution.getWidth(), resolution.getHeight());
            mediaRecorder.setVideoEncodingBitRate(bitRate);
            mediaRecorder.setVideoFrameRate(frameRate);

            mediaRecorder.setAudioEncodingBitRate(384000);
            mediaRecorder.setAudioChannels(1); // Use mono for wider compatibility/smaller size
            mediaRecorder.setAudioSamplingRate(48000);
            mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);

            mediaRecorder.setVideoEncoder(codec.getEncoder());

            // Orientation Hint
            int orientationHint = calculateOrientationHint();
            Log.d(TAG,"MR OrientationHint: "+orientationHint);
            mediaRecorder.setOrientationHint(orientationHint);

            mediaRecorder.prepare();
            Log.i(TAG, "MediaRecorder prepared successfully for temp file: " + currentInternalTempFile.getName());

            // ** Crucial for pipeline: Get the input surface for OpenGL to render onto **
            recorderSurface = mediaRecorder.getSurface();
            if(recorderSurface == null || !recorderSurface.isValid()){
                throw new IOException("MediaRecorder provided an invalid surface.");
            }
            Log.d(TAG,"Acquired valid MediaRecorder Surface for pipeline output.");

        } catch (IOException | IllegalStateException | NullPointerException e) { // Catch potential NPE from getSurface
            Log.e(TAG, "setupMediaRecorder Failed", e);
            releaseMediaRecorderSafely(); // Clean up recorder on failure
            if(currentInternalTempFile != null && currentInternalTempFile.exists()) {
                currentInternalTempFile.delete(); // Clean up temp file on failure
                currentInternalTempFile = null;
            }
            throw new IOException("MediaRecorder setup failed: " + e.getMessage(), e); // Re-throw for calling method
        }
    }

    private int calculateOrientationHint() {
        if(cameraManager == null) return 90; // Default if no manager
        try{
            String cameraId = getTargetCameraId(); // Get ID based on prefs
            if(cameraId == null) return 90;
            CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(cameraId);
            Integer sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
            Integer lensFacing = characteristics.get(CameraCharacteristics.LENS_FACING);

            if (sensorOrientation == null || lensFacing == null) return 90; // Default if data missing

            // Basic logic (assuming portrait device orientation is standard)
            // Might need adjustment based on actual device rotation if service knows about it
            if (lensFacing == CameraCharacteristics.LENS_FACING_FRONT) {
                // Front camera: often needs 270 for portrait capture to look correct
                return (sensorOrientation + 270) % 360; // Compensate for front cam mirroring/rotation
            } else {
                // Back camera: usually needs 90 for portrait capture
                return (sensorOrientation + 90) % 360;
            }
        } catch (CameraAccessException | IllegalArgumentException e){
            Log.e(TAG, "Error getting orientation data, using default hint", e);
            return 90;
        }
    }


    /** Returns the Surface provided by MediaRecorder AFTER prepare() */
    private Surface getRecorderSurface() {
        if (mediaRecorder == null){
            Log.e(TAG,"getRecorderSurface: MediaRecorder is null!");
            return null;
        }
        // On newer APIs, getSurface() should work reliably after prepare.
        // On older APIs, there might be timing issues. This was handled by storing reference in `setupMediaRecorder`.
        if (recorderSurface == null || !recorderSurface.isValid()) {
            Log.w(TAG,"getRecorderSurface: Surface became null or invalid. Trying to re-acquire.");
            try { recorderSurface = mediaRecorder.getSurface();}
            catch(Exception e) { Log.e(TAG, "Failed to re-acquire MediaRecorder surface", e); recorderSurface = null;}
            if(recorderSurface == null || !recorderSurface.isValid()) Log.e(TAG, "Failed to get a valid surface from MediaRecorder!");
        }
        return recorderSurface;
    }



    // --- File Handling & Finalization ---

    /** Creates a unique temporary file in the app's EXTERNAL cache directory. */
    private File createTemporaryFile(String prefix) throws IOException {
        File cacheDir = getExternalCacheDir();
        if (cacheDir == null) { // Fallback to internal if external fails
            Log.w(TAG, "External cache unavailable, using internal cache for temp file.");
            cacheDir = getCacheDir();
        }
        File tempStorageDir = new File(cacheDir, Constants.RECORDING_TEMP_DIRECTORY_NAME);
        if (!tempStorageDir.exists() && !tempStorageDir.mkdirs()) {
            throw new IOException("Failed to create temp directory: " + tempStorageDir.getAbsolutePath());
        }
        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
        String filename = prefix + timestamp + "." + Constants.RECORDING_FILE_EXTENSION;
        File tempFile = new File(tempStorageDir, filename);
        Log.d(TAG, "Created temporary file path: " + tempFile.getAbsolutePath());
        return tempFile;
    }

    /**
     * Renames or copies the temporary file (already watermarked by pipeline/MR)
     * to the final destination determined by storage preferences.
     */
    private void finalizeRecordedFile(File sourceTempFile) {
        Log.i(TAG, "Finalizing recorded file: " + sourceTempFile.getName());
        // Replace 'context' with 'this' or 'getApplicationContext()'
        final Context serviceContext = getApplicationContext(); // Get the context instance

        if (serviceContext == null || sourceTempFile == null || !sourceTempFile.exists()) { // Added null check for sourceTempFile
            Log.e(TAG, "Cannot finalize: Context null or source file doesn't exist or is null: " + (sourceTempFile != null ? sourceTempFile.getPath() : "null"));
            broadcastRecordingComplete(null, false);
            // Maybe add checkIfServiceCanStop() here too if finalization fails critically?
            checkIfServiceCanStop();
            return;
        }

        String storageMode = sharedPreferencesManager.getStorageMode();
        String finalFileName = generateFinalFilename();
        Uri finalUri = null;
        boolean success = false;

        try {
            if (SharedPreferencesManager.STORAGE_MODE_CUSTOM.equals(storageMode)) {
                // --- Save to Custom SAF Location ---
                String treeUriString = sharedPreferencesManager.getCustomStorageUri();
                if (treeUriString != null) {
                    Uri treeUri = Uri.parse(treeUriString);
                    if (hasSafPermission(treeUri)) { // Pass 'this' or 'serviceContext' if needed
                        DocumentFile destDir = DocumentFile.fromTreeUri(serviceContext, treeUri); // Use serviceContext
                        if (destDir != null && destDir.canWrite()) {
                            // Use MimeType from VideoCodec or hardcoded Constants
                            String mimeType = "video/" + Constants.RECORDING_FILE_EXTENSION;
                            DocumentFile finalDoc = destDir.createFile(mimeType, finalFileName); // Use MimeType
                            if (finalDoc != null) {
                                // Pass serviceContext to copyFileToDocumentFile if needed
                                if (copyFileToDocumentFile(sourceTempFile, finalDoc)) {
                                    finalUri = finalDoc.getUri();
                                    success = true;
                                    Log.i(TAG, "Successfully copied temp file to SAF destination: " + finalUri);
                                } else {
                                    Log.e(TAG, "Failed to copy temp file content to SAF DocumentFile: " + finalDoc.getUri());
                                    try { if(finalDoc.exists()) finalDoc.delete(); } catch (Exception ignored){}
                                }
                            } else {
                                Log.e(TAG, "Failed to create DocumentFile in SAF directory: " + destDir.getUri());
                            }
                        } else {
                            Log.e(TAG, "Cannot write to SAF directory: " + treeUri);
                            // Show Toast on Main Thread
                            new Handler(Looper.getMainLooper()).post(() ->
                                    Toast.makeText(serviceContext, "Cannot write to custom location", Toast.LENGTH_LONG).show()
                            );
                        }
                    } else {
                        Log.e(TAG, "Lost permission or invalid SAF URI: " + treeUri);
                        new Handler(Looper.getMainLooper()).post(() ->
                                Toast.makeText(serviceContext, "Permission issue with custom location", Toast.LENGTH_LONG).show()
                        );
                    }
                } else {
                    Log.e(TAG, "Custom storage mode selected, but no URI saved!");
                    new Handler(Looper.getMainLooper()).post(() ->
                            Toast.makeText(serviceContext, "Custom storage location not configured", Toast.LENGTH_SHORT).show()
                    );
                }

            } else { // Default: Internal Storage
                // --- Move to Internal App-Specific Storage ---
                File recordsDir = serviceContext.getExternalFilesDir(null); // Use serviceContext
                if (recordsDir == null) { Log.e(TAG, "Internal files dir (getExternalFilesDir) is null."); throw new IOException("Cannot access internal storage"); }
                File fadCamInternalDir = new File(recordsDir, Constants.RECORDING_DIRECTORY);
                if (!fadCamInternalDir.exists() && !fadCamInternalDir.mkdirs()) {
                    throw new IOException("Cannot create internal FadCam directory: " + fadCamInternalDir.getAbsolutePath());
                }
                File finalInternalFile = new File(fadCamInternalDir, finalFileName);

                if (sourceTempFile.renameTo(finalInternalFile)) { // Attempt direct rename
                    finalUri = Uri.fromFile(finalInternalFile); // Use file URI for internal storage
                    success = true;
                    Log.i(TAG, "Successfully moved temp file to internal storage: " + finalInternalFile.getAbsolutePath());
                } else {
                    Log.w(TAG, "Rename failed, attempting copy to internal storage...");
                    if(copyFileUsingStreams(sourceTempFile, finalInternalFile)){
                        finalUri = Uri.fromFile(finalInternalFile);
                        success = true;
                        Log.i(TAG, "Successfully COPIED temp file to internal storage: " + finalInternalFile.getAbsolutePath());
                    } else {
                        Log.e(TAG, "Failed to move OR copy temp file to internal storage: " + finalInternalFile.getAbsolutePath());
                    }
                }
            } // End Internal Storage block

        } catch (Exception e) {
            Log.e(TAG, "Error during finalizeRecordedFile", e);
            success = false; finalUri = null;
        } finally {
            // --- Delete source temp file regardless of success IF it still exists ---
            if (sourceTempFile.exists()) {
                if (sourceTempFile.delete()) {
                    Log.d(TAG, "Deleted source temp file: " + sourceTempFile.getName());
                } else {
                    Log.w(TAG, "Failed to delete source temp file after finalize attempt: " + sourceTempFile.getName());
                }
            }
            currentInternalTempFile = null; // Clear the reference

            // Broadcast completion status
            broadcastRecordingComplete(finalUri, success);
            checkIfServiceCanStop(); // Check if service can stop AFTER finalizing
        }
    }

    /** Generates the final filename (without path). */
    private String generateFinalFilename() {
        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
        return Constants.RECORDING_DIRECTORY + "_" + timestamp + "." + Constants.RECORDING_FILE_EXTENSION;
    }


    // Make sure copyFileToDocumentFile also uses a Context parameter if needed
    private boolean copyFileToDocumentFile(File sourceFile, DocumentFile destinationDoc) {
        // Get context inside this method
        final Context serviceContext = getApplicationContext();
        if (serviceContext == null || !sourceFile.exists() || destinationDoc == null || !destinationDoc.canWrite()) {
            Log.e(TAG, "copyFileToDoc: Invalid input..."); // Shortened log
            return false;
        }
        InputStream in = null;
        OutputStream out = null;
        try {
            in = new FileInputStream(sourceFile);
            // Use the local context variable
            out = serviceContext.getContentResolver().openOutputStream(destinationDoc.getUri());
            if (out == null) throw new IOException("Failed to open OutputStream for DocumentFile");

            byte[] buffer = new byte[8192]; // 8KB buffer
            int len;
            while ((len = in.read(buffer)) > 0) {
                out.write(buffer, 0, len);
            }
            out.flush(); // Ensure data is written
            Log.d(TAG, "copyFileToDoc: Copy finished for " + sourceFile.getName() + " to " + destinationDoc.getName());
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Error copying file to DocumentFile: " + destinationDoc.getUri(), e);
            return false;
        } finally {
            // Close streams... (same as before)
            try { if (in != null) in.close(); } catch (IOException ignored) {}
            try { if (out != null) out.close(); } catch (IOException ignored) {}
        }
    }
    /** Copies data from a source File to a destination File using streams. */
    private boolean copyFileUsingStreams(File sourceFile, File destinationFile) {
        if (sourceFile == null || !sourceFile.exists() || destinationFile == null) return false;
        InputStream in = null; OutputStream out = null;
        try {
            in = new FileInputStream(sourceFile);
            out = new FileOutputStream(destinationFile);
            byte[] buffer = new byte[8192]; int len;
            while ((len = in.read(buffer)) > 0) { out.write(buffer, 0, len); }
            out.flush();
            return true;
        } catch (IOException e) { Log.e(TAG,"Error copying stream from "+sourceFile.getName()+" to "+destinationFile.getName(), e); return false;}
        finally {
            try { if (in != null) in.close(); } catch (IOException ignored) {}
            try { if (out != null) out.close(); } catch (IOException ignored) {}
        }
    }

    // --- Notifications & UI Feedback ---

    // Uses Foreground Service Notification + Actions
    private void setupRecordingNotification(boolean isPaused) {
        // Permission Check
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) { // Need to handle Q differently potentially
                Log.w(TAG, "Notification permission missing on Tiramisu+, attempting foreground without visible notification.");
                try {
                    int types = ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA | ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
                    startForeground(NOTIFICATION_ID, createBaseNotificationBuilder().build(), types);
                    Log.d(TAG,"Started foreground (Tiramisu+) without notification permission.");
                } catch (Exception e) {
                    Log.e(TAG, "Failed startForeground on Tiramisu+ without notification perm", e);
                    cleanupFailedStart("Foreground service failed"); // Stop service if required start fails
                }
                return; // Exit here if foreground started without notification
            } else { // Below Tiramisu, no specific perm needed but good practice to request POST_NOTIFICATIONS
                Log.w(TAG,"Notification display potentially blocked (check app settings for channel/app).");
                // On older devices, startForeground might still work without the permission grant,
                // but the notification might not show depending on system/OEM settings.
            }
        }

        // Build notification content
        NotificationCompat.Builder builder = createBaseNotificationBuilder();
        if (isPaused) {
            builder.setContentText(getString(R.string.notification_video_recording_paused_description))
                    .clearActions() // Remove previous actions
                    .addAction(R.drawable.ic_play, getString(R.string.button_resume), createPendingIntent(Constants.INTENT_ACTION_RESUME_RECORDING, 2))
                    .addAction(R.drawable.ic_stop, getString(R.string.button_stop), createPendingIntent(Constants.INTENT_ACTION_STOP_RECORDING, 1));
            Log.d(TAG, "Notification builder updated for PAUSED state.");
        } else { // In Progress
            builder.setContentText(getString(R.string.notification_video_recording_progress_description))
                    .clearActions() // Remove previous actions
                    .addAction(R.drawable.ic_pause, getString(R.string.button_pause), createPendingIntent(Constants.INTENT_ACTION_PAUSE_RECORDING, 3))
                    .addAction(R.drawable.ic_stop, getString(R.string.button_stop), createPendingIntent(Constants.INTENT_ACTION_STOP_RECORDING, 1));
            Log.d(TAG, "Notification builder updated for IN_PROGRESS state.");
        }

        // Start foreground service with the notification
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            int types = ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA | ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
            startForeground(NOTIFICATION_ID, builder.build(), types);
            Log.d(TAG, "Started/Updated foreground service (Q+) with notification.");
        } else {
            startForeground(NOTIFICATION_ID, builder.build());
            Log.d(TAG, "Started/Updated foreground service (pre-Q) with notification.");
        }
    }


    private NotificationCompat.Builder createBaseNotificationBuilder() {
        // Create intent to open MainActivity
        Intent mainActivityIntent = new Intent(this, MainActivity.class);
        mainActivityIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        PendingIntent contentPendingIntent = PendingIntent.getActivity(this, 0, mainActivityIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE); // Use IMMUTABLE

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.notification_video_recording))
                .setSmallIcon(R.drawable.ic_notification_icon)
                .setContentIntent(contentPendingIntent) // Set intent to open app on tap
                .setOngoing(true)  // Makes it non-dismissable
                .setSilent(true)   // No sound/vibration
                .setPriority(NotificationCompat.PRIORITY_LOW); // Less intrusive
    }


    private PendingIntent createPendingIntent(String action, int requestCode) {
        Intent intent = new Intent(this, RecordingService.class);
        intent.setAction(action);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE; // Always use IMMUTABLE
        return PendingIntent.getService(this, requestCode, intent, flags);
    }

    // Utility Methods (moved from within notification methods)

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            CharSequence name = getString(R.string.app_name) + " Recording";
            String description = "Notifications for recording status and actions";
            int importance = NotificationManager.IMPORTANCE_LOW; // Less intrusive
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, name, importance);
            channel.setDescription(description);
            channel.setSound(null, null); // No sound
            channel.enableVibration(false); // No vibration

            NotificationManager notificationManager = getSystemService(NotificationManager.class);
            if (notificationManager != null) {
                notificationManager.createNotificationChannel(channel);
                Log.d(TAG,"Notification channel created/verified.");
            } else { Log.e(TAG,"Failed to get NotificationManager service for channel creation."); }
        }
    }


    private void cancelNotification() {
        try{
            NotificationManagerCompat notificationManager = NotificationManagerCompat.from(this);
            notificationManager.cancel(NOTIFICATION_ID);
            Log.d(TAG,"Notification cancelled.");
        } catch(Exception e){ Log.e(TAG, "Error cancelling notification", e);}
    }


    // Toasts - Simple wrappers for main thread toast display
    private void showRecordingInPausedToast() {
        new Handler(Looper.getMainLooper()).post(() ->
                Utils.showQuickToast(getApplicationContext(), R.string.video_recording_paused)
        );
    }
    private void showRecordingResumedToast() {
        new Handler(Looper.getMainLooper()).post(() ->
                Utils.showQuickToast(getApplicationContext(), R.string.video_recording_resumed)
        );
    }

    // --- State Checkers & Permission Utils ---

    private boolean isRecording() { return recordingState == RecordingState.IN_PROGRESS; }
    private boolean isPaused() { return recordingState == RecordingState.PAUSED; }
    private boolean isIdle() { return recordingState == RecordingState.NONE; }

    private boolean hasRequiredPermissions() {
        boolean cameraOk = ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
        boolean audioOk = ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
        // Storage checks depend on Android version, handled within finalize/save methods.
        if(!cameraOk) Log.w(TAG, "Camera permission MISSING.");
        if(!audioOk) Log.w(TAG, "Record Audio permission MISSING.");
        return cameraOk && audioOk;
    }


    /** Checks if the service is actively recording/paused. */
    private boolean isWorkingInProgress() {
        return recordingState != RecordingState.NONE;
    }

    /** Checks if service can stop (only if completely idle). */
    private void checkIfServiceCanStop() {
        if (!isWorkingInProgress()) {
            Log.i(TAG, "Service is IDLE. Stopping self.");
            stopSelf();
        } else {
            Log.d(TAG,"Service continues running. State: " + recordingState);
        }
    }

    /** Get target camera ID based on shared preferences. Includes fallback logic. */
    private String getTargetCameraId() {
        if (cameraManager == null) { Log.e(TAG,"getTargetCameraId: CameraManager is null."); return null; }

        CameraType selectedType = sharedPreferencesManager.getCameraSelection();
        String preferredId = null;
        String fallbackId = null;
        int targetFacing = (selectedType == CameraType.FRONT) ? CameraCharacteristics.LENS_FACING_FRONT : CameraCharacteristics.LENS_FACING_BACK;

        try {
            String[] availableIds = cameraManager.getCameraIdList();
            Log.d(TAG,"Available IDs for facing "+targetFacing+": "+ Arrays.toString(availableIds));

            if (targetFacing == CameraCharacteristics.LENS_FACING_BACK) {
                preferredId = sharedPreferencesManager.getSelectedBackCameraId();
                Log.d(TAG,"Targeting BACK camera. Preferred ID: " + preferredId);
            } else { // FRONT
                Log.d(TAG,"Targeting FRONT camera.");
            }

            // Iterate through available IDs to find the preferred OR a fallback
            for (String id : availableIds) {
                CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(id);
                Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
                if (facing != null && facing == targetFacing) {
                    // Check if this ID matches the preferred ID (if one exists)
                    if (preferredId != null && id.equals(preferredId)) {
                        Log.i(TAG,"Found preferred Camera ID: " + id);
                        return id; // Found the preferred one
                    }
                    // If no preferred ID OR preferred ID not found yet, store this as fallback
                    if (fallbackId == null) {
                        fallbackId = id;
                        Log.d(TAG,"Storing first camera with correct facing as fallback: " + id);
                    }
                }
            }

            // If preferred ID was specified but NOT found among available cameras with correct facing:
            if (preferredId != null && (fallbackId == null || !fallbackId.equals(preferredId))) {
                Log.w(TAG,"Preferred camera ID '"+preferredId+"' for facing "+targetFacing+" not found or invalid! Using fallback ID: "+fallbackId);
            }

            // Return the fallback if preferred wasn't found, or if it was the only one found
            if (fallbackId != null) {
                Log.i(TAG, "Using Fallback Camera ID for facing "+targetFacing+": " + fallbackId);
                return fallbackId;
            } else {
                Log.e(TAG,"CRITICAL: No camera found for the required facing: " + targetFacing);
                return null; // No suitable camera found at all
            }

        } catch (CameraAccessException | IllegalArgumentException e) {
            Log.e(TAG,"Error getting target Camera ID", e);
            return null;
        }
    }

    // --- Helper to check SAF Permission ---
    private boolean hasSafPermission(Uri treeUri) {
        // Get context inside the method
        final Context serviceContext = getApplicationContext();
        if (serviceContext == null || treeUri == null) {
            Log.w(TAG,"hasSafPermission: Context or treeUri is null.");
            return false;
        }

        try {
            // Check if we still have persistent permission
            List<UriPermission> persistedUris = serviceContext.getContentResolver().getPersistedUriPermissions(); // Use serviceContext
            boolean permissionFound = false;
            for (UriPermission uriPermission : persistedUris) {
                if (uriPermission.getUri().equals(treeUri) && uriPermission.isReadPermission() && uriPermission.isWritePermission()) {
                    permissionFound = true;
                    break;
                }
            }

            if (!permissionFound) {
                Log.w(TAG,"No persisted R/W permission found for SAF URI: " + treeUri);
                return false;
            }

            // Additionally, try a quick read/write check via DocumentFile
            DocumentFile docDir = DocumentFile.fromTreeUri(serviceContext, treeUri); // Use serviceContext
            if (docDir != null && docDir.canRead() && docDir.canWrite()) { // Check R/W
                return true; // Both permission entry exists and basic checks pass
            } else {
                Log.w(TAG, "Persisted permission found, but DocumentFile check failed (cannot read/write or null). URI: "+ treeUri);
                return false;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error checking SAF permission for URI: "+ treeUri, e);
            return false;
        }
    }

    // --- SAF File descriptor cleanup ---
    private void closeParcelFileDescriptor() {
        if (currentParcelFileDescriptor != null) {
            try { currentParcelFileDescriptor.close(); Log.d(TAG,"Closed SAF PFD."); }
            catch (IOException e) { Log.w(TAG,"Error closing SAF PFD: " + e.getMessage());}
            finally{ currentParcelFileDescriptor = null; }
        }
    }
    // --- END SAF FILE DESCRIPTOR ---


    // --- Torch Control During Recording ---
    /** Toggle torch ONLY if recording is active and camera session exists */
    private void toggleRecordingTorch() {
        if (captureSession == null || captureRequestBuilder == null || cameraDevice == null) {
            Log.w(TAG, "Cannot toggle torch: Camera session/request builder not ready.");
            return;
        }
        if (backgroundHandler == null) {
            Log.e(TAG,"Cannot toggle torch: backgroundHandler is null."); return;
        }

        isRecordingTorchEnabled = !isRecordingTorchEnabled; // Toggle the state
        Log.i(TAG, "Toggling recording torch. New state: " + isRecordingTorchEnabled);

        try {
            // Update the flash mode in the persistent request builder
            captureRequestBuilder.set(CaptureRequest.FLASH_MODE,
                    isRecordingTorchEnabled ? CaptureRequest.FLASH_MODE_TORCH : CaptureRequest.FLASH_MODE_OFF);

            // Re-apply the repeating request with the updated setting
            captureSession.setRepeatingRequest(captureRequestBuilder.build(), null, backgroundHandler);
            Log.d(TAG, "Torch repeating request updated successfully.");

            // Notify UI about the state change
            Intent intent = new Intent(Constants.BROADCAST_ON_TORCH_STATE_CHANGED);
            intent.putExtra(Constants.INTENT_EXTRA_TORCH_STATE, isRecordingTorchEnabled);
            sendBroadcast(intent);

        } catch (CameraAccessException e) {
            Log.e(TAG, "Could not toggle recording torch (CameraAccessException)", e);
            // Revert state if applying failed
            isRecordingTorchEnabled = !isRecordingTorchEnabled;
        } catch (IllegalStateException e){
            Log.e(TAG,"Could not toggle recording torch (IllegalStateException - session closed?)", e);
            isRecordingTorchEnabled = !isRecordingTorchEnabled;
            // Consider stopping recording if session is bad
            // handleStopRecording();
        } catch (Exception e) {
            Log.e(TAG,"Could not toggle recording torch (Unknown Exception)", e);
            isRecordingTorchEnabled = !isRecordingTorchEnabled;
        }
    }


    // --- Broadcasts (Ensure Sending from Service) ---

    /** Sends broadcast confirming recording has physically started. */
    private void broadcastOnRecordingStarted() {
        Intent intent = new Intent(Constants.BROADCAST_ON_RECORDING_STARTED);
        intent.putExtra(Constants.INTENT_EXTRA_RECORDING_START_TIME, recordingStartTime);
        // Optionally add file path/uri if known at this point (maybe temp file path)
        sendBroadcast(intent);
        Log.i(TAG, "Broadcast Sent: RECORDING_STARTED");
    }


    private void broadcastOnRecordingPaused() { sendBroadcast(new Intent(Constants.BROADCAST_ON_RECORDING_PAUSED)); Log.i(TAG,"Broadcast Sent: RECORDING_PAUSED");}
    private void broadcastOnRecordingResumed() { sendBroadcast(new Intent(Constants.BROADCAST_ON_RECORDING_RESUMED)); Log.i(TAG,"Broadcast Sent: RECORDING_RESUMED");}

    /** Sends broadcast confirming recording hardware/session stopped. Does NOT mean file processing is done. */
    private void broadcastOnRecordingStopped() { sendBroadcast(new Intent(Constants.BROADCAST_ON_RECORDING_STOPPED)); Log.i(TAG,"Broadcast Sent: RECORDING_STOPPED"); }


    /** Sends current service state back to UI when requested */
    private void broadcastOnRecordingStateCallback() {
        Intent intent = new Intent(Constants.BROADCAST_ON_RECORDING_STATE_CALLBACK);
        intent.putExtra(Constants.INTENT_EXTRA_RECORDING_STATE, recordingState);
        sendBroadcast(intent);
        Log.d(TAG,"Broadcast Sent: RECORDING_STATE_CALLBACK (" + recordingState + ")");
    }


    /** Broadcasts when final file is ready (or if finalization failed) */
    private void broadcastRecordingComplete(Uri finalUri, boolean success) {
        Intent intent = new Intent(Constants.ACTION_RECORDING_COMPLETE);
        intent.putExtra(Constants.EXTRA_RECORDING_SUCCESS, success);
        if(finalUri != null) intent.putExtra(Constants.EXTRA_RECORDING_URI_STRING, finalUri.toString());
        sendBroadcast(intent);
        Log.i(TAG, "Broadcast Sent: RECORDING_COMPLETE. Success: " + success + ", Final URI: " + (finalUri != null ? finalUri.toString() : "null"));
    }


    // --- RecordingPipelineCallbacks Implementation (INSIDE RecordingService.java)---


    @Override
    public void onRecordingStarted() {
        // Callback *from* the pipeline confirming GL rendering to recorder surface started
        Log.i(TAG, "Callback: onRecordingStarted received from pipeline (GL rendering started).");
        // Currently no specific action needed here in the service based on this callback.
    }

    @Override
    public void onRecordingStopped() {
        // Callback *from* the pipeline confirming GL rendering to recorder surface stopped
        Log.i(TAG, "Callback: onRecordingStopped received from pipeline (GL rendering stopped).");
        // Indicates the GL side cleanup is done. Service stop logic driven by user action/errors.
    }

    @Override
    public void onError(String message) {
        // Callback *from* the pipeline signaling an error on its thread
        Log.e(TAG, "Callback: onError received from pipeline: " + message);
        this.pipelineSetupError = true; // Mark the pipeline as having an issue
        if (pipelineSurfaceReadyLatch != null && pipelineSurfaceReadyLatch.getCount() > 0) {
            pipelineSurfaceReadyLatch.countDown(); // Unblock any waiter
        }

        // Handle the error based on current service state
        if (isRecording() || isPaused()) {
            Log.e(TAG, "Pipeline error during active recording/pause. Forcing stop.");
            new Handler(Looper.getMainLooper()).post(() ->
                    Toast.makeText(getApplicationContext(), "Render Error: " + message, Toast.LENGTH_LONG).show()
            );
            handleStopRecording(); // Trigger service stop sequence
        } else {
            Log.w(TAG,"Pipeline error occurred while service was idle or stopping.");
            broadcastCameraError("Pipeline error: "+message);
            cleanupFailedStart("Pipeline error: "+message); // Cleanup and stop service
        }
    }

    @Override
    public void onInputSurfaceReady() {
        // No action needed for now, but required by RecordingPipelineCallbacks interface
    }
    // --- End RecordingPipelineCallbacks Implementation ---


    // --- Utility Method to Get Bitrate ---
    private int getVideoBitrate() {
        int width = sharedPreferencesManager.getCameraResolution().getWidth();
        int height = sharedPreferencesManager.getCameraResolution().getHeight();
        int frameRate = sharedPreferencesManager.getSpecificVideoFrameRate(sharedPreferencesManager.getCameraSelection());

        // Simple bitrate estimation - ADJUST AS NEEDED
        // Factors: Motion complexity, codec efficiency (HEVC more efficient than AVC)
        long pixelsPerSecond = (long)width * height * frameRate;
        double bitsPerPixel = 0.1; // Starting point (Lower = higher compression) - 0.05 to 0.2 is common range

        if(sharedPreferencesManager.getVideoCodec() == VideoCodec.HEVC){
            bitsPerPixel *= 0.7; // Assume HEVC is ~30% more efficient
        }

        int estimatedBitrate = (int) (pixelsPerSecond * bitsPerPixel);

        // Apply Caps/Floors
        estimatedBitrate = Math.max(500_000, estimatedBitrate); // Min 0.5 Mbps
        estimatedBitrate = Math.min(50_000_000, estimatedBitrate); // Max 50 Mbps (Adjust based on expected quality/device caps)

        Log.d(TAG, String.format("Estimated Video Bitrate: %.2f Mbps (W:%d H:%d FPS:%d BPP:%.3f)",
                estimatedBitrate / 1_000_000.0, width, height, frameRate, bitsPerPixel));
        return estimatedBitrate;
    }


    // Inside RecordingService.java class

    /**
     * Queries camera capabilities for the target camera, finds a compatible SurfaceTexture size,
     * stores it in compatibleSurfaceTextureSize, and sets this size on the RecordingPipeline instance.
     * Throws exceptions on critical failures.
     */
    private void queryAndSetPipelineTargetSize() throws CameraAccessException, IOException, IllegalStateException {
        // 1. Determine Target Camera ID
        String cameraToOpenId = getTargetCameraId();
        if (cameraToOpenId == null) {
            throw new IOException("Could not determine target camera ID for size query.");
        }

        // 2. Get Camera Characteristics & Stream Map
        CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(cameraToOpenId);
        StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        if (map == null) {
            throw new IOException("Could not get StreamConfigurationMap for camera " + cameraToOpenId);
        }

        // 3. Find Compatible Size
        Size desiredResolution = sharedPreferencesManager.getCameraResolution();
        compatibleSurfaceTextureSize = findBestSupportedSize(map.getOutputSizes(SurfaceTexture.class), desiredResolution);
        if (compatibleSurfaceTextureSize == null) {
            Log.w(TAG, "No suitable SurfaceTexture output size found, falling back.");
            Size[] sizes = map.getOutputSizes(SurfaceTexture.class);
            compatibleSurfaceTextureSize = (sizes != null && sizes.length > 0) ? sizes[0] : new Size(1280, 720); // Last resort default
        }
        Log.i(TAG, "QueryAndSetSize: Determined compatible SurfaceTexture Size: " + compatibleSurfaceTextureSize);

        // 4. Set Size on the RecordingPipeline Instance
        if (recordingPipeline != null) {
            // *** Ensure this call points to the method in RecordingPipeline ***
            recordingPipeline.setTargetOutputSize(compatibleSurfaceTextureSize);
            Log.d(TAG, "QueryAndSetSize: Target output size passed to RecordingPipeline.");
        } else {
            Log.e(TAG, "QueryAndSetSize: RecordingPipeline was null when trying to set size!");
            throw new IllegalStateException("Pipeline not initialized when setting target size.");
        }
    }
    /** Updates the preview surface used by the pipeline. */
    private void setupPreviewSurface(Intent intent) {
        if (intent != null && intent.hasExtra("SURFACE")) {
            previewSurface = intent.getParcelableExtra("SURFACE");
            Log.d(TAG,"setupPreviewSurface: Received new preview surface. Is valid: " + (previewSurface != null && previewSurface.isValid()));
        } else {
            // Explicitly nullify if intent doesn't contain a surface, or if intent is null
            previewSurface = null;
            Log.d(TAG,"setupPreviewSurface: No preview surface in intent or intent was null.");
        }
        // Pass the updated surface (or null) to the pipeline IF pipeline exists
        if (recordingPipeline != null) {
            recordingPipeline.setPreviewSurface(previewSurface);
            Log.d(TAG,"Passed preview surface (or null) to RecordingPipeline.");
        }
    }
} // END of RecordingService class