package com.fadcam.fadrec.services;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.WindowManager;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.fadcam.Constants;
import com.fadcam.MainActivity;
import com.fadcam.R;
import com.fadcam.SharedPreferencesManager;
import com.fadcam.fadrec.ScreenRecordingState;
import com.fadcam.fadrec.encoding.ScreenRecordingPipeline;
import com.fadcam.opengl.WatermarkInfoProvider;
import com.fadcam.utils.RecordingStoragePaths;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Service for screen recording using MediaProjection API.
 * Handles screen capture, audio recording, and real-time watermarking.
 * Follows the architecture pattern of RecordingService.
 */
public class ScreenRecordingService extends Service {

    private static final String TAG = "ScreenRecordingService";
    private static final int NOTIFICATION_ID = 2; // Different from RecordingService (ID=1)
    private static final String CHANNEL_ID = "ScreenRecordingChannel";

    // MediaProjection components
    private MediaProjectionManager mediaProjectionManager;
    private MediaProjection mediaProjection;
    
    // Recording pipeline (MediaCodec + FragmentedMp4)
    private ScreenRecordingPipeline recordingPipeline;
    private File outputFile;
    // SAF storage support (for custom storage location)
    private android.os.ParcelFileDescriptor safRecordingPfd;
    private android.net.Uri safRecordingUri; // Track SAF URI for notification/broadcasting
    
    // State management
    private ScreenRecordingState recordingState = ScreenRecordingState.NONE;
    private long recordingStartTime;
    private long pauseStartTime; // Track when pause started
    private long totalPausedTime; // Accumulate total paused duration
    
    // Configuration
    private SharedPreferencesManager sharedPreferencesManager;
    private PowerManager.WakeLock recordingWakeLock;
    
    // Threading
    private Handler backgroundHandler;
    private HandlerThread backgroundThread;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    
    // Display metrics
    private int screenWidth;
    private int screenHeight;
    private int screenDensity;
    
    // Notification update
    private final Handler notificationHandler = new Handler(Looper.getMainLooper());
    private Runnable notificationUpdateRunnable;

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "onCreate: ScreenRecordingService creating...");
        
        // Initialize SharedPreferencesManager
        sharedPreferencesManager = SharedPreferencesManager.getInstance(getApplicationContext());
        
        // Initialize MediaProjectionManager
        mediaProjectionManager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        if (mediaProjectionManager == null) {
            Log.e(TAG, "Failed to get MediaProjectionManager");
            stopSelf();
            return;
        }
        
        // Setup background thread for recording operations
        backgroundThread = new HandlerThread("ScreenRecordingBackground");
        backgroundThread.start();
        backgroundHandler = new Handler(backgroundThread.getLooper());
        
        // Get screen metrics
        WindowManager windowManager = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        if (windowManager != null) {
            DisplayMetrics metrics = new DisplayMetrics();
            windowManager.getDefaultDisplay().getRealMetrics(metrics);
            screenWidth = metrics.widthPixels;
            screenHeight = metrics.heightPixels;
            screenDensity = metrics.densityDpi;
            Log.d(TAG, String.format("Screen metrics: %dx%d @%ddpi", screenWidth, screenHeight, screenDensity));
        }
        
        // Create notification channel
        createNotificationChannel();
        
        Log.d(TAG, "ScreenRecordingService created successfully");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            Log.w(TAG, "onStartCommand: null intent, stopping service");
            stopSelf();
            return START_NOT_STICKY;
        }

        String action = intent.getAction();
        // Log.d(TAG, "onStartCommand: action=" + action);

        if (action == null) {
            stopSelf();
            return START_NOT_STICKY;
        }

        switch (action) {
            case Constants.INTENT_ACTION_START_SCREEN_RECORDING:
                handleStartRecording(intent);
                break;
                
            case Constants.INTENT_ACTION_STOP_SCREEN_RECORDING:
                handleStopRecording();
                break;
                
            case Constants.INTENT_ACTION_PAUSE_SCREEN_RECORDING:
                handlePauseRecording();
                break;
                
            case Constants.INTENT_ACTION_RESUME_SCREEN_RECORDING:
                handleResumeRecording();
                break;

            case Constants.INTENT_ACTION_SET_SCREEN_RECORDING_MUTE:
                handleSetMute(intent);
                break;

            case Constants.INTENT_ACTION_QUERY_SCREEN_RECORDING_STATE:
                handleQueryRecordingState();
                break;
                
            default:
                Log.w(TAG, "Unknown action: " + action);
                break;
        }

        return START_STICKY;
    }

    /**
     * Sends a state callback broadcast with the current state.
     * Used by UI to reconcile stale persisted state after app updates/crashes.
     */
    private void handleQueryRecordingState() {
        Log.d(TAG, "handleQueryRecordingState: state=" + recordingState);

        // Broadcast current state
        Intent stateIntent = new Intent(Constants.BROADCAST_ON_SCREEN_RECORDING_STATE_CALLBACK);
        stateIntent.putExtra("recordingState", recordingState.name());
        LocalBroadcastManager.getInstance(this).sendBroadcast(stateIntent);
    }

    /**
     * Handles the start recording intent.
     * Initializes MediaProjection and starts screen recording.
     */
    private void handleStartRecording(Intent intent) {
        Log.d(TAG, "handleStartRecording: Starting screen recording");
        
        if (recordingState != ScreenRecordingState.NONE) {
            Log.w(TAG, "Recording already in progress, ignoring start request");
            return;
        }
        
        // Start foreground immediately to avoid crash
        recordingState = ScreenRecordingState.NONE;
        Notification notification = createNotification();
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
        
        // Get MediaProjection result data from intent
        int resultCode = intent.getIntExtra("resultCode", Activity.RESULT_CANCELED);
        
        // RESULT_OK is -1, RESULT_CANCELED is 0
        if (resultCode != Activity.RESULT_OK) {
            Log.e(TAG, "MediaProjection permission denied - resultCode: " + resultCode);
            Toast.makeText(this, "Screen recording permission required", Toast.LENGTH_SHORT).show();
            stopSelf();
            return;
        }
        
        Log.d(TAG, "Got resultCode: " + resultCode + " (RESULT_OK), creating MediaProjection");
        
        // Initialize MediaProjection using the intent data
        try {
            // Try to get permissionData parcelable first (preferred method)
            Intent permissionIntent = intent.getParcelableExtra("permissionData");
            
            if (permissionIntent == null) {
                // Fallback: Use the original intent (if extras were copied correctly)
                Log.d(TAG, "permissionData not found, using intent extras directly");
                permissionIntent = intent;
            }
            
            Log.d(TAG, "Creating MediaProjection with intent: " + (permissionIntent != null ? "valid" : "null"));
            
            mediaProjection = mediaProjectionManager.getMediaProjection(resultCode, permissionIntent);
            if (mediaProjection == null) {
                String error = "MediaProjectionManager.getMediaProjection() returned null (permission may have been revoked)";
                Log.e(TAG, error);
                throw new RuntimeException(error);
            }
            
            Log.d(TAG, "MediaProjection created successfully");
            
            // Set up callback for when MediaProjection stops
            mediaProjection.registerCallback(new MediaProjection.Callback() {
                @Override
                public void onStop() {
                    Log.d(TAG, "MediaProjection stopped externally");
                    handleStopRecording();
                }
            }, backgroundHandler);
            
        } catch (SecurityException e) {
            Log.e(TAG, "SecurityException creating MediaProjection - permission may have been revoked", e);
            Toast.makeText(this, "Permission error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            stopSelf();
            return;
        } catch (Exception e) {
            String errorMsg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            Log.e(TAG, "Error creating MediaProjection: " + errorMsg, e);
            Toast.makeText(this, "MediaProjection error: " + errorMsg, Toast.LENGTH_SHORT).show();
            stopSelf();
            return;
        }
        
        // Start recording on background thread
        backgroundHandler.post(() -> {
            try {
                startScreenRecording();
            } catch (IOException e) {
                String errorMsg = e.getMessage() != null ? e.getMessage() : "I/O error";
                Log.e(TAG, "IOException starting screen recording: " + errorMsg, e);
                mainHandler.post(() -> {
                    Toast.makeText(this, "Recording failed: " + errorMsg, Toast.LENGTH_SHORT).show();
                    stopSelf();
                });
            } catch (Exception e) {
                String errorMsg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                Log.e(TAG, "Error starting screen recording: " + errorMsg, e);
                mainHandler.post(() -> {
                    Toast.makeText(this, "Recording failed: " + errorMsg, Toast.LENGTH_SHORT).show();
                    stopSelf();
                });
            }
        });
    }

    /**
     * Starts the actual screen recording.
     * Initializes ScreenRecordingPipeline using MediaCodec + FragmentedMp4Muxer for crash-safe fMP4 output.
     */
    private void startScreenRecording() throws IOException {
        Log.d(TAG, "startScreenRecording: Initializing ScreenRecordingPipeline");
        
        // Validate MediaProjection
        if (mediaProjection == null) {
            throw new IOException("MediaProjection is null - permission may have been revoked or not granted");
        }
        
        // Create output file (may set safRecordingPfd for SAF mode, or return File for internal)
        outputFile = createOutputFile();
        // outputFile == null is valid for SAF mode; check both
        if (outputFile == null && safRecordingPfd == null) {
            throw new IOException("Failed to create output file - check storage permissions and available space");
        }
        
        // Get audio config
        String audioSource = sharedPreferencesManager.getScreenRecordingAudioSource();
        boolean enableAudio = Constants.AUDIO_SOURCE_MIC.equals(audioSource);
        boolean initialMuted = sharedPreferencesManager.isScreenRecordingMuted();
        
        // Calculate bitrate based on resolution
        int calculatedBitrate = calculateBitrate(screenWidth, screenHeight);
        
        Log.d(TAG, String.format("Video config: %dx%d @%dfps, bitrate=%d, audio=%s",
            screenWidth, screenHeight,
            Constants.DEFAULT_SCREEN_RECORDING_FPS,
            calculatedBitrate,
            enableAudio ? "enabled" : "disabled"));
        
        // Build recording pipeline
        try {
            ScreenRecordingPipeline.Builder pipelineBuilder = new ScreenRecordingPipeline.Builder(this)
                .setScreenDimensions(screenWidth, screenHeight, screenDensity)
                .setVideoConfig(Constants.DEFAULT_SCREEN_RECORDING_FPS, calculatedBitrate)
                .setEnableAudio(enableAudio)
                .setMediaProjection(mediaProjection)
                .setWatermarkInfoProvider(createWatermarkInfoProvider());
            
            // Use FileDescriptor for SAF mode, file path for internal storage
            if (safRecordingPfd != null) {
                Log.d(TAG, "Using SAF FileDescriptor for output");
                pipelineBuilder.setOutputFileDescriptor(safRecordingPfd.getFileDescriptor());
            } else {
                Log.d(TAG, "Using internal storage file path for output");
                pipelineBuilder.setOutputFile(outputFile.getAbsolutePath());
            }
            
            recordingPipeline = pipelineBuilder.build();
            recordingPipeline.setAudioMuted(initialMuted);
            
            Log.d(TAG, "ScreenRecordingPipeline built successfully");
            
        } catch (IOException e) {
            Log.e(TAG, "Failed to build ScreenRecordingPipeline", e);
            throw new IOException("Pipeline build error: " + e.getMessage(), e);
        }
        
        // Start recording pipeline
        try {
            recordingPipeline.startRecording();
            recordingStartTime = SystemClock.elapsedRealtime();
            recordingState = ScreenRecordingState.IN_PROGRESS;

            // Persist state immediately so UI can restore reliably across tab switches.
            sharedPreferencesManager.setScreenRecordingState(ScreenRecordingState.IN_PROGRESS.name());
            
            // Reset pause tracking for new recording
            pauseStartTime = 0;
            totalPausedTime = 0;
            
            // Save recording start time to SharedPreferences for UI timer updates
            sharedPreferencesManager.sharedPreferences.edit()
                .putLong("screen_recording_start_time", recordingStartTime)
                .apply();
            
            // Log.i(TAG, "Screen recording started successfully");
            
            // Update UI on main thread
            mainHandler.post(() -> {
                // Acquire WakeLock
                acquireWakeLock();
                
                // Start foreground service with notification
                startForeground(NOTIFICATION_ID, createNotification(), 
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION | 
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
                
                // Start notification updates
                startNotificationUpdates();
                
                // Broadcast recording started (FadRecHomeFragment will show toast)
                broadcastRecordingStarted();
                
                // Save state to preferences
                sharedPreferencesManager.setScreenRecordingInProgress(true);
                sharedPreferencesManager.setScreenRecordingState(ScreenRecordingState.IN_PROGRESS.name());
            });
            
            // Notify RemoteStreamManager for potential streaming
            if (outputFile != null) {
                try {
                    com.fadcam.streaming.RemoteStreamManager.getInstance().startRecording(outputFile);
                    Log.i(TAG, "🎬 RemoteStreamManager notified: screen recording started");
                } catch (Exception e) {
                    Log.e(TAG, "Failed to notify RemoteStreamManager", e);
                }
            }
            
        } catch (IllegalStateException e) {
            Log.e(TAG, "IllegalStateException - Pipeline in wrong state: " + e.getMessage(), e);
            cleanupPipeline();
            recordingState = ScreenRecordingState.NONE;
            sharedPreferencesManager.setScreenRecordingState(ScreenRecordingState.NONE.name());
            throw new IOException("Pipeline error: " + e.getMessage(), e);
        } catch (Exception e) {
            Log.e(TAG, "Unexpected error starting pipeline: " + e.getMessage(), e);
            cleanupPipeline();
            recordingState = ScreenRecordingState.NONE;
            sharedPreferencesManager.setScreenRecordingState(ScreenRecordingState.NONE.name());
            throw new IOException("Failed to start pipeline: " + e.getMessage(), e);
        }
    }

    private void handleSetMute(Intent intent) {
        boolean muted = intent.getBooleanExtra(Constants.EXTRA_SCREEN_RECORDING_MUTED, false);
        sharedPreferencesManager.setScreenRecordingMuted(muted);
        if (recordingPipeline != null) {
            recordingPipeline.setAudioMuted(muted);
        }
        Intent broadcast = new Intent(Constants.BROADCAST_ON_SCREEN_RECORDING_MUTE_CHANGED);
        broadcast.putExtra(Constants.EXTRA_SCREEN_RECORDING_MUTED, muted);
        LocalBroadcastManager.getInstance(this).sendBroadcast(broadcast);
        Log.d(TAG, "Screen recording mute updated: " + muted);
    }
    
    /**
     * Creates watermark info provider for screen recordings
     */
    private WatermarkInfoProvider createWatermarkInfoProvider() {
        return () -> {
            // For now, return simple "FadRec" text
            // Will be enhanced with timestamp and user customization later
            return "FadRec";
        };
    }
    
    /**
     * Clean up recording pipeline
     */
    private void cleanupPipeline() {
        if (recordingPipeline != null) {
            try {
                recordingPipeline.release();
            } catch (Exception e) {
                Log.e(TAG, "Error releasing pipeline", e);
            }
            recordingPipeline = null;
        }
        
        if (mediaProjection != null) {
            try {
                mediaProjection.stop();
            } catch (Exception e) {
                Log.e(TAG, "Error stopping MediaProjection", e);
            }
            mediaProjection = null;
        }
        
        // Close SAF ParcelFileDescriptor if open
        if (safRecordingPfd != null) {
            try {
                safRecordingPfd.close();
                Log.d(TAG, "SAF ParcelFileDescriptor closed");
            } catch (Exception e) {
                Log.e(TAG, "Error closing SAF ParcelFileDescriptor", e);
            }
            safRecordingPfd = null;
        }
    }

    /**
     * Handles stop recording request.
     */
    private void handleStopRecording() {
        Log.d(TAG, "handleStopRecording: Stopping screen recording");
        
        if (recordingState == ScreenRecordingState.NONE) {
            Log.w(TAG, "No recording in progress");
            return;
        }
        
        recordingState = ScreenRecordingState.STOPPING;
        
        backgroundHandler.post(this::stopScreenRecording);
    }

    /**
     * Stops the screen recording and releases resources.
     */
    private void stopScreenRecording() {
        Log.d(TAG, "stopScreenRecording: Finalizing recording");
        
        try {
            // Stop recording pipeline
            if (recordingPipeline != null) {
                try {
                    recordingPipeline.stopRecording();
                    Log.d(TAG, "Recording pipeline stopped");
                } catch (RuntimeException e) {
                    Log.w(TAG, "Pipeline stop failed: " + e.getMessage());
                }
            }
            
            // Cleanup resources
            cleanupPipeline();

            // Persist state on background thread before any UI callbacks.
            sharedPreferencesManager.setScreenRecordingState(ScreenRecordingState.NONE.name());
            
            // Calculate duration
            long duration = SystemClock.elapsedRealtime() - recordingStartTime;
            Log.i(TAG, String.format("Recording stopped. Duration: %.1f seconds", duration / 1000.0));
            
            // Update UI on main thread
            mainHandler.post(() -> {
                // Stop notification updates
                stopNotificationUpdates();
                
                // Release WakeLock
                releaseWakeLock();
                
                // Broadcast recording stopped
                broadcastRecordingStopped();
                
                // Save state
                sharedPreferencesManager.setScreenRecordingInProgress(false);
                recordingState = ScreenRecordingState.NONE;
                sharedPreferencesManager.setScreenRecordingState(ScreenRecordingState.NONE.name());
                
                // Clear recording start time
                sharedPreferencesManager.sharedPreferences.edit()
                    .remove("screen_recording_start_time")
                    .apply();
                
                // Show completion notification and broadcast (for both internal and SAF storage)
                boolean recordingSuccessful = (outputFile != null && outputFile.exists()) || (safRecordingUri != null);
                
                if (recordingSuccessful) {
                    // Broadcast recording complete for RecordsFragment to refresh
                    try {
                        Intent recordingCompleteIntent = new Intent(Constants.ACTION_RECORDING_COMPLETE);
                        if (outputFile != null) {
                            recordingCompleteIntent.putExtra("videoPath", outputFile.getAbsolutePath());
                        } else if (safRecordingUri != null) {
                            recordingCompleteIntent.putExtra("videoUri", safRecordingUri.toString());
                        }
                        sendBroadcast(recordingCompleteIntent);
                        Log.d(TAG, "Broadcasted ACTION_RECORDING_COMPLETE for list refresh");
                    } catch (Exception e) {
                        Log.e(TAG, "Error broadcasting recording complete", e);
                    }
                    
                    // Show completion notification with action to view recording
                    // For SAF mode, pass null (notification still opens Records tab)
                    showCompletionNotification(outputFile);
                }
                
                // Clear SAF URI
                safRecordingUri = null;
                
                // Stop service
                stopForeground(true);
                stopSelf();
            });
            
        } catch (Exception e) {
            Log.e(TAG, "Error stopping screen recording", e);
            sharedPreferencesManager.setScreenRecordingState(ScreenRecordingState.NONE.name());
            mainHandler.post(() -> stopSelf());
        }
    }

    /**
     * Handles pause recording request.
     */
    private void handlePauseRecording() {
        Log.d(TAG, "handlePauseRecording: Pausing screen recording");
        
        if (recordingState != ScreenRecordingState.IN_PROGRESS) {
            Log.w(TAG, "Cannot pause: not in progress");
            return;
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                if (recordingPipeline != null) {
                    recordingPipeline.pauseRecording();
                }
                recordingState = ScreenRecordingState.PAUSED;
                sharedPreferencesManager.setScreenRecordingState(ScreenRecordingState.PAUSED.name());
                
                // Record when pause started
                pauseStartTime = SystemClock.elapsedRealtime();
                Log.d(TAG, "Pause started at: " + pauseStartTime);
                
                // Stop notification updates while paused
                stopNotificationUpdates();
                
                // Update notification
                updateNotification();
                
                // Broadcast paused
                broadcastRecordingPaused();
                
                Log.i(TAG, "Screen recording paused");
            } catch (Exception e) {
                Log.e(TAG, "Error pausing recording", e);
            }
        } else {
            Log.w(TAG, "Pause not supported on Android < N");
        }
    }

    /**
     * Handles resume recording request.
     */
    private void handleResumeRecording() {
        Log.d(TAG, "handleResumeRecording: Resuming screen recording");
        
        if (recordingState != ScreenRecordingState.PAUSED) {
            Log.w(TAG, "Cannot resume: not paused");
            return;
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                if (recordingPipeline != null) {
                    recordingPipeline.resumeRecording();
                }
                recordingState = ScreenRecordingState.IN_PROGRESS;
                sharedPreferencesManager.setScreenRecordingState(ScreenRecordingState.IN_PROGRESS.name());
                
                // Calculate pause duration and adjust start time
                if (pauseStartTime > 0) {
                    long pauseDuration = SystemClock.elapsedRealtime() - pauseStartTime;
                    totalPausedTime += pauseDuration;
                    
                    // Adjust the recording start time to skip the paused duration
                    recordingStartTime += pauseDuration;
                    
                    // Update SharedPreferences with adjusted start time
                    sharedPreferencesManager.sharedPreferences
                        .edit()
                        .putLong("screen_recording_start_time", recordingStartTime)
                        .apply();
                    
                    Log.d(TAG, "Pause duration: " + pauseDuration + "ms, total paused: " + totalPausedTime + "ms");
                    Log.d(TAG, "Adjusted start time to: " + recordingStartTime);
                    
                    pauseStartTime = 0; // Reset
                }
                
                // Update notification
                updateNotification();
                
                // Restart notification updates
                startNotificationUpdates();
                
                // Broadcast resumed
                broadcastRecordingResumed();
                
                Log.i(TAG, "Screen recording resumed");
            } catch (Exception e) {
                Log.e(TAG, "Error resuming recording", e);
            }
        }
    }

    /**
     * Calculate appropriate bitrate based on resolution.
     * Formula: pixels * fps * bpp (bits per pixel)
     */
    private int calculateBitrate(int width, int height) {
        int pixels = width * height;
        int fps = Constants.DEFAULT_SCREEN_RECORDING_FPS;
        
        // Use 0.07 bits per pixel for good quality
        double bitsPerPixel = 0.07;
        int bitrate = (int) (pixels * fps * bitsPerPixel);
        
        // Clamp between 2Mbps and 16Mbps
        int minBitrate = 2_000_000;  // 2Mbps
        int maxBitrate = 16_000_000; // 16Mbps
        
        return Math.max(minBitrate, Math.min(maxBitrate, bitrate));
    }

    /**
     * Creates output file with FadRec prefix.
     * Supports both internal storage and custom storage location (SAF).
     * For custom storage, sets safRecordingPfd instead of returning a File.
     * @return File object for internal storage, or null for SAF mode (check safRecordingPfd)
     */
    @Nullable
    private File createOutputFile() {
        try {
            String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
            String baseFilename = Constants.RECORDING_FILE_PREFIX_FADREC + timestamp + "." 
                + Constants.RECORDING_FILE_EXTENSION;
            
            String storageMode = sharedPreferencesManager.getStorageMode();
            
            // Check if custom storage mode is selected
            if (SharedPreferencesManager.STORAGE_MODE_CUSTOM.equals(storageMode)) {
                String customUriString = sharedPreferencesManager.getCustomStorageUri();
                if (customUriString == null) {
                    Log.e(TAG, "Custom storage selected but URI is null, falling back to internal");
                    // Fall through to internal storage
                } else {
                    androidx.documentfile.provider.DocumentFile pickedDir =
                        RecordingStoragePaths.getSafCategoryDir(
                            this,
                            customUriString,
                            RecordingStoragePaths.Category.SCREEN,
                            true
                        );
                    
                    if (pickedDir == null || !pickedDir.canWrite()) {
                        Log.e(TAG, "Cannot write to custom directory, falling back to internal");
                        Toast.makeText(this, "Cannot write to custom directory", Toast.LENGTH_LONG).show();
                        // Fall through to internal storage
                    } else {
                        // Create file in custom location using SAF
                        androidx.documentfile.provider.DocumentFile videoFile = 
                            pickedDir.createFile("video/" + Constants.RECORDING_FILE_EXTENSION, baseFilename);
                        
                        if (videoFile == null) {
                            Log.e(TAG, "Failed to create SAF file");
                            Toast.makeText(this, "Failed to create file in custom location", Toast.LENGTH_LONG).show();
                            // Fall through to internal storage
                        } else {
                            // Open ParcelFileDescriptor for the SAF file
                            safRecordingPfd = getContentResolver().openFileDescriptor(videoFile.getUri(), "w");
                            if (safRecordingPfd == null) {
                                Log.e(TAG, "Failed to open ParcelFileDescriptor for SAF URI");
                                Toast.makeText(this, "Failed to open file for writing", Toast.LENGTH_LONG).show();
                                // Fall through to internal storage
                            } else {
                                // Store SAF URI for completion notification/broadcasting
                                safRecordingUri = videoFile.getUri();
                                Log.d(TAG, "SAF output file created: " + safRecordingUri);
                                // Return null to indicate SAF mode (use safRecordingPfd instead)
                                return null;
                            }
                        }
                    }
                }
            }
            
            // Internal storage (default or fallback)
            File videoDir = RecordingStoragePaths.getInternalCategoryDir(
                this,
                RecordingStoragePaths.Category.SCREEN,
                true
            );
            if (videoDir == null) {
                Log.e(TAG, "Cannot create Screen directory in recording root");
                Toast.makeText(this, "Error creating recording directory", Toast.LENGTH_LONG).show();
                return null;
            }
            
            File file = new File(videoDir, baseFilename);
            Log.d(TAG, "Output file (internal): " + file.getAbsolutePath());
            return file;
        } catch (Exception e) {
            Log.e(TAG, "Error creating output file", e);
            return null;
        }
    }

    /**
     * Creates notification channel for screen recording.
     */
    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "Screen Recording",
                NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Shows screen recording status");
            channel.setShowBadge(false);
            
            NotificationManager notificationManager = getSystemService(NotificationManager.class);
            if (notificationManager != null) {
                notificationManager.createNotificationChannel(channel);
                Log.d(TAG, "Notification channel created");
            }
        }
    }

    /**
     * Creates notification for foreground service.
     */
    private Notification createNotification() {
        // Create intent to open app
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent, 
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
        );
        
        // Create stop action
        Intent stopIntent = new Intent(this, ScreenRecordingService.class);
        stopIntent.setAction(Constants.INTENT_ACTION_STOP_SCREEN_RECORDING);
        PendingIntent stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent, 
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
        );
        
        // Build notification
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("FadRec - Screen Recording")
            .setContentText(getNotificationText())
            .setSmallIcon(R.drawable.screen_recorder)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(R.drawable.ic_notification_stop, "Stop", stopPendingIntent);
        
        // Add pause/resume action if supported
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            if (recordingState == ScreenRecordingState.PAUSED) {
                Intent resumeIntent = new Intent(this, ScreenRecordingService.class);
                resumeIntent.setAction(Constants.INTENT_ACTION_RESUME_SCREEN_RECORDING);
                PendingIntent resumePendingIntent = PendingIntent.getService(
                    this, 1, resumeIntent, 
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
                );
                builder.addAction(R.drawable.ic_notification_play, "Resume", resumePendingIntent);
            } else if (recordingState == ScreenRecordingState.IN_PROGRESS) {
                Intent pauseIntent = new Intent(this, ScreenRecordingService.class);
                pauseIntent.setAction(Constants.INTENT_ACTION_PAUSE_SCREEN_RECORDING);
                PendingIntent pausePendingIntent = PendingIntent.getService(
                    this, 1, pauseIntent, 
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
                );
                builder.addAction(R.drawable.ic_notification_pause, "Pause", pausePendingIntent);
            }
        }
        
        return builder.build();
    }
    
    /**
     * Shows completion notification after recording is saved.
     * Notification opens the app to Records tab when clicked.
     */
    private void showCompletionNotification(File videoFile) {
        NotificationManager notificationManager = 
            (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        
        if (notificationManager == null) {
            Log.e(TAG, "NotificationManager is null, cannot show completion notification");
            return;
        }
        
        // Create intent to open MainActivity with Records tab selected
        Intent openAppIntent = new Intent(this, MainActivity.class);
        openAppIntent.setAction(Intent.ACTION_MAIN);
        openAppIntent.addCategory(Intent.CATEGORY_LAUNCHER);
        openAppIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        // Add extra to navigate to Records tab (index 1: Home=0, Records=1, Remote=2, etc.)
        openAppIntent.putExtra("navigate_to_tab", 1);
        
        PendingIntent pendingIntent = PendingIntent.getActivity(
            this, 
            0, 
            openAppIntent, 
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
        );
        
        // Build completion notification
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Screen Recording Saved")
            .setContentText("Tap to view your recording")
            .setSmallIcon(R.drawable.screen_recorder)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)  // Dismiss when clicked
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_STATUS);
        
        // Show notification with unique ID (not the ongoing recording notification ID)
        notificationManager.notify(NOTIFICATION_ID + 1, builder.build());
        Log.d(TAG, "Completion notification shown");
    }

    /**
     * Gets notification text with recording duration.
     */
    private String getNotificationText() {
        if (recordingState == ScreenRecordingState.PAUSED) {
            return "Recording paused";
        }
        
        long elapsed = SystemClock.elapsedRealtime() - recordingStartTime;
        long seconds = elapsed / 1000;
        long minutes = seconds / 60;
        long hours = minutes / 60;
        
        if (hours > 0) {
            return String.format(Locale.US, "Recording: %02d:%02d:%02d", 
                hours, minutes % 60, seconds % 60);
        } else {
            return String.format(Locale.US, "Recording: %02d:%02d", 
                minutes, seconds % 60);
        }
    }

    /**
     * Updates the notification (called periodically).
     */
    private void updateNotification() {
        NotificationManager notificationManager = getSystemService(NotificationManager.class);
        if (notificationManager != null) {
            notificationManager.notify(NOTIFICATION_ID, createNotification());
        }
    }

    /**
     * Starts periodic notification updates.
     */
    private void startNotificationUpdates() {
        notificationUpdateRunnable = new Runnable() {
            @Override
            public void run() {
                if (recordingState == ScreenRecordingState.IN_PROGRESS) {
                    updateNotification();
                    notificationHandler.postDelayed(this, 1000); // Update every second
                }
            }
        };
        notificationHandler.post(notificationUpdateRunnable);
    }

    /**
     * Stops periodic notification updates.
     */
    private void stopNotificationUpdates() {
        if (notificationUpdateRunnable != null) {
            notificationHandler.removeCallbacks(notificationUpdateRunnable);
            notificationUpdateRunnable = null;
        }
    }

    /**
     * Acquires WakeLock to keep device awake during recording.
     */
    private void acquireWakeLock() {
        if (recordingWakeLock == null || !recordingWakeLock.isHeld()) {
            PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (powerManager != null) {
                recordingWakeLock = powerManager.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "FadCam:ScreenRecordingWakeLock"
                );
                recordingWakeLock.acquire(10 * 60 * 60 * 1000L); // 10 hours max
                Log.d(TAG, "WakeLock acquired");
            }
        }
    }

    /**
     * Releases WakeLock.
     */
    private void releaseWakeLock() {
        if (recordingWakeLock != null && recordingWakeLock.isHeld()) {
            recordingWakeLock.release();
            recordingWakeLock = null;
            Log.d(TAG, "WakeLock released");
        }
    }

    // Broadcast methods (using LocalBroadcastManager for guaranteed delivery on Android 12+)
    
    private void broadcastRecordingStarted() {
        Intent intent = new Intent(Constants.BROADCAST_ON_SCREEN_RECORDING_STARTED);
        intent.putExtra(Constants.INTENT_EXTRA_RECORDING_START_TIME, recordingStartTime);
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
        
        // Also send STATE_CALLBACK for FloatingControlsService to update button state
        Intent stateIntent = new Intent(Constants.BROADCAST_ON_SCREEN_RECORDING_STATE_CALLBACK);
        stateIntent.putExtra("recordingState", recordingState.name());
        LocalBroadcastManager.getInstance(this).sendBroadcast(stateIntent);
    }

    private void broadcastRecordingStopped() {
        Intent intent = new Intent(Constants.BROADCAST_ON_SCREEN_RECORDING_STOPPED);
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
        
        // Also send STATE_CALLBACK for FloatingControlsService to update button state
        Intent stateIntent = new Intent(Constants.BROADCAST_ON_SCREEN_RECORDING_STATE_CALLBACK);
        stateIntent.putExtra("recordingState", recordingState.name());
        LocalBroadcastManager.getInstance(this).sendBroadcast(stateIntent);
    }

    private void broadcastRecordingPaused() {
        Intent intent = new Intent(Constants.BROADCAST_ON_SCREEN_RECORDING_PAUSED);
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
        
        // Also send STATE_CALLBACK for FloatingControlsService to update button state
        Intent stateIntent = new Intent(Constants.BROADCAST_ON_SCREEN_RECORDING_STATE_CALLBACK);
        stateIntent.putExtra("recordingState", recordingState.name());
        LocalBroadcastManager.getInstance(this).sendBroadcast(stateIntent);
    }

    private void broadcastRecordingResumed() {
        Intent intent = new Intent(Constants.BROADCAST_ON_SCREEN_RECORDING_RESUMED);
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
        
        // Also send STATE_CALLBACK for FloatingControlsService to update button state
        Intent stateIntent = new Intent(Constants.BROADCAST_ON_SCREEN_RECORDING_STATE_CALLBACK);
        stateIntent.putExtra("recordingState", recordingState.name());
        LocalBroadcastManager.getInstance(this).sendBroadcast(stateIntent);
    }

    @Override
    public void onDestroy() {
        Log.d(TAG, "onDestroy: Cleaning up");
        
        // Stop recording if still in progress
        if (recordingState != ScreenRecordingState.NONE) {
            stopScreenRecording();
        }
        
        // Release resources
        releaseWakeLock();
        stopNotificationUpdates();
        
        // Quit background thread
        if (backgroundThread != null) {
            backgroundThread.quitSafely();
            try {
                backgroundThread.join();
            } catch (InterruptedException e) {
                Log.e(TAG, "Error joining background thread", e);
            }
            backgroundThread = null;
            backgroundHandler = null;
        }
        
        Log.d(TAG, "ScreenRecordingService destroyed");
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
