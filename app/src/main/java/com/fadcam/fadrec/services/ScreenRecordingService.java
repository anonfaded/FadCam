package com.fadcam.fadrec.services;

import com.fadcam.Log;
import com.fadcam.FLog;
import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.content.pm.PackageManager;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
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
import android.view.Surface;
import android.view.WindowManager;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import android.provider.DocumentsContract;

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
    private VirtualDisplay previewOnlyVirtualDisplay;
    
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
    private boolean forceNoAudioForThisStart = false;
    private boolean enableAudioForSession = false;
    private boolean previewOnlyActive = false;
    /**
     * True when the current session was started while the remote server was active AND the
     * user had selected STREAM_ONLY mode.  Captured once at {@link #startScreenRecording()} so
     * the stop path never deletes a recording that was started without an active server.
     */
    private boolean isStreamOnlySession = false;
    private Surface currentPreviewSurface;
    private int currentPreviewSurfaceWidth = -1;
    private int currentPreviewSurfaceHeight = -1;
    
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
        FLog.d(TAG, "onCreate: ScreenRecordingService creating...");
        
        // Initialize SharedPreferencesManager
        sharedPreferencesManager = SharedPreferencesManager.getInstance(getApplicationContext());
        
        // Initialize MediaProjectionManager
        mediaProjectionManager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        if (mediaProjectionManager == null) {
            FLog.e(TAG, "Failed to get MediaProjectionManager");
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
            FLog.d(TAG, String.format("Screen metrics: %dx%d @%ddpi", screenWidth, screenHeight, screenDensity));
        }
        
        // Create notification channel
        createNotificationChannel();
        
        FLog.d(TAG, "ScreenRecordingService created successfully");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            FLog.w(TAG, "onStartCommand: null intent, stopping service");
            stopSelf();
            return START_NOT_STICKY;
        }

        String action = intent.getAction();
        // FLog.d(TAG, "onStartCommand: action=" + action);

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

            case Constants.INTENT_ACTION_START_SCREEN_PREVIEW_ONLY:
                handleStartPreviewOnly(intent);
                break;

            case Constants.INTENT_ACTION_STOP_SCREEN_PREVIEW_ONLY:
                handleStopPreviewOnly();
                break;

            case Constants.INTENT_ACTION_CHANGE_SCREEN_PREVIEW_SURFACE:
                handleChangePreviewSurface(intent);
                break;
                
            default:
                FLog.w(TAG, "Unknown action: " + action);
                break;
        }

        return START_STICKY;
    }

    /**
     * Sends a state callback broadcast with the current state.
     * Used by UI to reconcile stale persisted state after app updates/crashes.
     */
    private void handleQueryRecordingState() {
        FLog.d(TAG, "handleQueryRecordingState: state=" + recordingState);

        // Broadcast current state
        Intent stateIntent = new Intent(Constants.BROADCAST_ON_SCREEN_RECORDING_STATE_CALLBACK);
        stateIntent.putExtra("recordingState", recordingState.name());
        stateIntent.putExtra(Constants.EXTRA_SCREEN_PREVIEW_ONLY_ACTIVE, previewOnlyActive);
        stateIntent.putExtra(
            Constants.EXTRA_SCREEN_PREVIEW_ENABLED,
            currentPreviewSurface != null && currentPreviewSurface.isValid()
        );
        LocalBroadcastManager.getInstance(this).sendBroadcast(stateIntent);
    }

    private void handleStartPreviewOnly(Intent intent) {
        FLog.d(TAG, "handleStartPreviewOnly: Starting screen preview-only");

        updatePreviewSurfaceFromIntent(intent);

        if (currentPreviewSurface == null || !currentPreviewSurface.isValid()) {
            FLog.w(TAG, "handleStartPreviewOnly: No valid preview surface");
            return;
        }

        Notification notification = createNotification();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }

        if (!ensureMediaProjection(intent)) {
            return;
        }

        previewOnlyActive = true;
        refreshPreviewOnlyVirtualDisplay();
        handleQueryRecordingState();
    }

    private void handleStopPreviewOnly() {
        FLog.d(TAG, "handleStopPreviewOnly");
        previewOnlyActive = false;
        releasePreviewOnlyVirtualDisplay();
        if (recordingState == ScreenRecordingState.NONE) {
            releaseProjectionIfIdle();
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
        } else {
            handleQueryRecordingState();
        }
    }

    private void handleChangePreviewSurface(Intent intent) {
        updatePreviewSurfaceFromIntent(intent);

        boolean surfaceValid = currentPreviewSurface != null && currentPreviewSurface.isValid();
        FLog.d(TAG, "handleChangePreviewSurface: surface=" + (surfaceValid ? "valid " + currentPreviewSurfaceWidth + "x" + currentPreviewSurfaceHeight : "null/invalid"));

        if (recordingPipeline != null) {
            recordingPipeline.setPreviewSurface(
                currentPreviewSurface,
                currentPreviewSurfaceWidth,
                currentPreviewSurfaceHeight
            );
        } else if (previewOnlyActive) {
            refreshPreviewOnlyVirtualDisplay();
        }
        // NOTE: do NOT call handleQueryRecordingState() here — the recording state has not changed,
        // only the preview surface. Broadcasting state on every surface-size change (animation
        // frames) floods logcat and causes redundant SharedPrefs writes.
    }

    /**
     * Handles the start recording intent.
     * Initializes MediaProjection and starts screen recording.
     */
    private void handleStartRecording(Intent intent) {
        FLog.d(TAG, "handleStartRecording: Starting screen recording");
        
        if (recordingState != ScreenRecordingState.NONE) {
            FLog.w(TAG, "Recording already in progress, ignoring start request");
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
        
        forceNoAudioForThisStart = intent.getBooleanExtra(
            Constants.EXTRA_SCREEN_RECORDING_FORCE_NO_AUDIO,
            false
        );
        updatePreviewSurfaceFromIntent(intent);
        if (!ensureMediaProjection(intent)) {
            return;
        }
        previewOnlyActive = false;
        releasePreviewOnlyVirtualDisplay();
        
        // Start recording on background thread
        backgroundHandler.post(() -> {
            try {
                startScreenRecording();
            } catch (IOException e) {
                String errorMsg = e.getMessage() != null ? e.getMessage() : "I/O error";
                FLog.e(TAG, "IOException starting screen recording: " + errorMsg, e);
                mainHandler.post(() -> {
                    Toast.makeText(this, "Recording failed: " + errorMsg, Toast.LENGTH_SHORT).show();
                    stopSelf();
                });
            } catch (Exception e) {
                String errorMsg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                FLog.e(TAG, "Error starting screen recording: " + errorMsg, e);
                mainHandler.post(() -> {
                    Toast.makeText(this, "Recording failed: " + errorMsg, Toast.LENGTH_SHORT).show();
                    stopSelf();
                });
            }
        });
    }

    private boolean ensureMediaProjection(Intent intent) {
        if (mediaProjection != null) {
            return true;
        }

        int resultCode = intent.getIntExtra("resultCode", Activity.RESULT_CANCELED);
        if (resultCode != Activity.RESULT_OK) {
            FLog.e(TAG, "MediaProjection permission denied - resultCode: " + resultCode);
            Toast.makeText(this, "Screen recording permission required", Toast.LENGTH_SHORT).show();
            stopSelf();
            return false;
        }

        FLog.d(TAG, "Got resultCode: " + resultCode + " (RESULT_OK), creating MediaProjection");

        try {
            Intent permissionIntent = intent.getParcelableExtra("permissionData");
            if (permissionIntent == null) {
                FLog.d(TAG, "permissionData not found, using intent extras directly");
                permissionIntent = intent;
            }

            mediaProjection = mediaProjectionManager.getMediaProjection(resultCode, permissionIntent);
            if (mediaProjection == null) {
                throw new RuntimeException("MediaProjectionManager.getMediaProjection() returned null");
            }

            mediaProjection.registerCallback(new MediaProjection.Callback() {
                @Override
                public void onStop() {
                    FLog.d(TAG, "MediaProjection stopped externally");
                    previewOnlyActive = false;
                    releasePreviewOnlyVirtualDisplay();
                    if (recordingState != ScreenRecordingState.NONE) {
                        handleStopRecording();
                    } else {
                        releaseProjectionIfIdle();
                        stopForeground(STOP_FOREGROUND_REMOVE);
                        stopSelf();
                    }
                }
            }, backgroundHandler);
            return true;
        } catch (SecurityException e) {
            FLog.e(TAG, "SecurityException creating MediaProjection - permission may have been revoked", e);
            Toast.makeText(this, "Permission error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            String errorMsg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            FLog.e(TAG, "Error creating MediaProjection: " + errorMsg, e);
            Toast.makeText(this, "MediaProjection error: " + errorMsg, Toast.LENGTH_SHORT).show();
        }

        stopSelf();
        return false;
    }

    private void updatePreviewSurfaceFromIntent(@Nullable Intent intent) {
        if (intent == null || !intent.hasExtra("SURFACE")) {
            return;
        }
        currentPreviewSurface = intent.getParcelableExtra("SURFACE");
        currentPreviewSurfaceWidth = intent.getIntExtra("SURFACE_WIDTH", -1);
        currentPreviewSurfaceHeight = intent.getIntExtra("SURFACE_HEIGHT", -1);
    }

    private void refreshPreviewOnlyVirtualDisplay() {
        releasePreviewOnlyVirtualDisplay();
        if (!previewOnlyActive || mediaProjection == null || currentPreviewSurface == null || !currentPreviewSurface.isValid()) {
            return;
        }
        int width = currentPreviewSurfaceWidth > 0 ? currentPreviewSurfaceWidth : screenWidth;
        int height = currentPreviewSurfaceHeight > 0 ? currentPreviewSurfaceHeight : screenHeight;
        previewOnlyVirtualDisplay = mediaProjection.createVirtualDisplay(
            "ScreenPreviewOnly",
            width,
            height,
            screenDensity,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            currentPreviewSurface,
            null,
            null
        );
    }

    private void releasePreviewOnlyVirtualDisplay() {
        if (previewOnlyVirtualDisplay != null) {
            try {
                previewOnlyVirtualDisplay.release();
            } catch (Exception e) {
                FLog.e(TAG, "Error releasing preview-only virtual display", e);
            }
            previewOnlyVirtualDisplay = null;
        }
    }

    private void releaseProjectionIfIdle() {
        if (recordingState != ScreenRecordingState.NONE || previewOnlyActive) {
            return;
        }
        if (mediaProjection != null) {
            try {
                mediaProjection.stop();
            } catch (Exception e) {
                FLog.e(TAG, "Error stopping MediaProjection", e);
            }
            mediaProjection = null;
        }
    }

    /**
     * Starts the actual screen recording.
     * Initializes ScreenRecordingPipeline using MediaCodec + FragmentedMp4Muxer for crash-safe fMP4 output.
     */
    private void startScreenRecording() throws IOException {
        FLog.d(TAG, "startScreenRecording: Initializing ScreenRecordingPipeline");
        FLog.i(
            TAG,
            "Diagnostics: brand=" + Build.BRAND
                + " manufacturer=" + Build.MANUFACTURER
                + " model=" + Build.MODEL
                + " sdk=" + Build.VERSION.SDK_INT
                + " preferSoftwareAvc=" + ScreenRecordingPipeline.isPreferringSoftwareAvcEncoder()
        );
        
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
        boolean prefersAudio = Constants.AUDIO_SOURCE_MIC.equals(audioSource)
            || Constants.AUDIO_SOURCE_INTERNAL.equals(audioSource);
        boolean needsMicPermission = Constants.AUDIO_SOURCE_MIC.equals(audioSource);
        boolean hasMicPermission = ContextCompat.checkSelfPermission(
            this,
            android.Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED;
        // Internal audio only requires RECORD_AUDIO permission too (for AudioRecord)
        boolean enableAudio = prefersAudio && !forceNoAudioForThisStart && hasMicPermission;
        enableAudioForSession = enableAudio;
        boolean initialMuted = sharedPreferencesManager.isScreenRecordingMuted();
        if (prefersAudio && !hasMicPermission) {
            FLog.w(TAG, "Audio permission missing - forcing muted screen recording (source: " + audioSource + ")");
            sharedPreferencesManager.setScreenRecordingMuted(true);
        }
        if (forceNoAudioForThisStart) {
            FLog.d(TAG, "Force-no-audio flag set for this recording session");
        }
        
        // Calculate bitrate based on resolution
        int calculatedBitrate = calculateBitrate(screenWidth, screenHeight);
        
        FLog.d(TAG, String.format("Video config: %dx%d @%dfps, bitrate=%d, audio=%s (source=%s)",
            screenWidth, screenHeight,
            Constants.DEFAULT_SCREEN_RECORDING_FPS,
            calculatedBitrate,
            enableAudio ? "enabled" : "disabled",
            audioSource));
        
        // Build recording pipeline
        try {
            ScreenRecordingPipeline.Builder pipelineBuilder = new ScreenRecordingPipeline.Builder(this)
                .setScreenDimensions(screenWidth, screenHeight, screenDensity)
                .setVideoConfig(Constants.DEFAULT_SCREEN_RECORDING_FPS, calculatedBitrate)
                .setEnableAudio(enableAudio)
                .setAudioSource(audioSource)
                .setMediaProjection(mediaProjection)
                .setWatermarkInfoProvider(createWatermarkInfoProvider());
            
            // Use FileDescriptor for SAF mode, file path for internal storage
            if (safRecordingPfd != null) {
                FLog.d(TAG, "Using SAF FileDescriptor for output");
                pipelineBuilder.setOutputFileDescriptor(safRecordingPfd.getFileDescriptor());
            } else {
                FLog.d(TAG, "Using internal storage file path for output");
                pipelineBuilder.setOutputFile(outputFile.getAbsolutePath());
            }
            
            recordingPipeline = pipelineBuilder.build();
            recordingPipeline.setAudioMuted(initialMuted);
            recordingPipeline.setPreviewSurface(
                currentPreviewSurface,
                currentPreviewSurfaceWidth,
                currentPreviewSurfaceHeight
            );
            
            FLog.d(TAG, "ScreenRecordingPipeline built successfully");
            
        } catch (IOException e) {
            FLog.e(
                TAG,
                "Failed to build ScreenRecordingPipeline"
                    + " display=" + screenWidth + "x" + screenHeight
                    + " density=" + screenDensity
                    + " audio=" + enableAudio,
                e
            );
            throw new IOException("Pipeline build error: " + e.getMessage(), e);
        }
        
        // Start recording pipeline
        try {
            // ===== FIX: Set state to IN_PROGRESS BEFORE starting pipeline =====
            // CRITICAL: If queries arrive during pipeline init (which they do), they must see
            // IN_PROGRESS state, not NONE. Otherwise MediaCodec fails with CodecException.
            recordingState = ScreenRecordingState.IN_PROGRESS;
            sharedPreferencesManager.setScreenRecordingState(ScreenRecordingState.IN_PROGRESS.name());
            
            // NOW start recording pipeline with correct internal state
            recordingPipeline.startRecording();
            recordingStartTime = SystemClock.elapsedRealtime();
            
            // Reset pause tracking for new recording
            pauseStartTime = 0;
            totalPausedTime = 0;

            // Capture stream-only intent at session start.  We only go stream-only when the
            // remote server is *currently active* AND the user chose STREAM_ONLY mode.  Storing
            // this once prevents a stale SharedPreference from deleting a recording that was
            // made without an active server.
            try {
                boolean serverOn = com.fadcam.streaming.RemoteStreamManager.getInstance().isStreamingEnabled();
                com.fadcam.streaming.RemoteStreamManager.StreamingMode sessionMode =
                    sharedPreferencesManager.getStreamingMode();
                isStreamOnlySession = serverOn &&
                    (sessionMode == com.fadcam.streaming.RemoteStreamManager.StreamingMode.STREAM_ONLY);
                FLog.d(TAG, "Session stream-only flag: " + isStreamOnlySession
                    + " (serverOn=" + serverOn + ", mode=" + sessionMode + ")");
            } catch (Exception e) {
                FLog.e(TAG, "Failed to determine streaming mode at session start", e);
                isStreamOnlySession = false;
            }
            
            // Save recording start time to SharedPreferences for UI timer updates
            sharedPreferencesManager.sharedPreferences.edit()
                .putLong("screen_recording_start_time", recordingStartTime)
                .apply();
            
            // FLog.i(TAG, "Screen recording started successfully");
            
            // Update UI on main thread
            mainHandler.post(() -> {
                // Acquire WakeLock
                acquireWakeLock();
                
                // Start foreground service with notification
                int fgTypes = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION;
                if (enableAudioForSession) {
                    fgTypes |= ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
                }
                startForeground(NOTIFICATION_ID, createNotification(), fgTypes);
                
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
                    FLog.i(TAG, "🎬 RemoteStreamManager notified: screen recording started");
                } catch (Exception e) {
                    FLog.e(TAG, "Failed to notify RemoteStreamManager", e);
                }
            }
            
        } catch (IllegalStateException e) {
            FLog.e(
                TAG,
                "IllegalStateException - Pipeline in wrong state"
                    + " recordingState=" + recordingState
                    + " previewOnly=" + previewOnlyActive
                    + " audioSession=" + enableAudioForSession,
                e
            );
            cleanupPipeline();
            recordingState = ScreenRecordingState.NONE;
            sharedPreferencesManager.setScreenRecordingState(ScreenRecordingState.NONE.name());
            throw new IOException("Pipeline error: " + e.getMessage(), e);
        } catch (Exception e) {
            FLog.e(
                TAG,
                "Unexpected error starting pipeline"
                    + " recordingState=" + recordingState
                    + " previewOnly=" + previewOnlyActive
                    + " audioSession=" + enableAudioForSession,
                e
            );
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
        FLog.d(TAG, "Screen recording mute updated: " + muted);
    }
    
    /**
     * Creates watermark info provider for screen recordings.
     * Respects the same watermark settings as FadCam mode.
     */
    private WatermarkInfoProvider createWatermarkInfoProvider() {
        return () -> {
            String watermarkOption = sharedPreferencesManager.getWatermarkOption();
            if ("no_watermark".equals(watermarkOption)) {
                return "";
            }
            String timestamp = getRecordingTimestamp();
            String customText = sharedPreferencesManager.getWatermarkCustomText();
            String customLine = (customText != null && !customText.isEmpty()) ? "\n" + customText : "";
            switch (watermarkOption) {
                case "timestamp":
                    return timestamp + customLine;
                case "badge_fadcam":
                    return "Recorded by <ICON>" + customLine;
                case "timestamp_fadcam":
                default:
                    return "Recorded by <ICON> - " + timestamp + customLine;
            }
        };
    }

    /**
     * Returns the current date/time as a formatted timestamp string for the watermark.
     */
    private String getRecordingTimestamp() {
        SimpleDateFormat sdf = new SimpleDateFormat("dd/MMM/yyyy hh:mm:ss a", Locale.ENGLISH);
        return sdf.format(new Date());
    }
    
    /**
     * Clean up recording pipeline
     */
    private void cleanupPipeline() {
        releasePreviewOnlyVirtualDisplay();
        if (recordingPipeline != null) {
            try {
                recordingPipeline.release();
            } catch (Exception e) {
                FLog.e(TAG, "Error releasing pipeline", e);
            }
            recordingPipeline = null;
        }
        
        if (mediaProjection != null) {
            try {
                releaseProjectionIfIdle();
            } catch (Exception e) {
                FLog.e(TAG, "Error stopping MediaProjection", e);
            }
        }
        
        // Close SAF ParcelFileDescriptor if open
        if (safRecordingPfd != null) {
            try {
                safRecordingPfd.close();
                FLog.d(TAG, "SAF ParcelFileDescriptor closed");
            } catch (Exception e) {
                FLog.e(TAG, "Error closing SAF ParcelFileDescriptor", e);
            }
            safRecordingPfd = null;
        }
    }

    /**
     * Handles stop recording request.
     */
    private void handleStopRecording() {
        FLog.d(TAG, "handleStopRecording: Stopping screen recording");
        
        if (recordingState == ScreenRecordingState.NONE) {
            FLog.w(TAG, "No recording in progress");
            return;
        }
        
        recordingState = ScreenRecordingState.STOPPING;
        
        backgroundHandler.post(this::stopScreenRecording);
    }

    /**
     * Stops the screen recording and releases resources.
     */
    private void stopScreenRecording() {
        FLog.d(TAG, "stopScreenRecording: Finalizing recording");
        
        try {
            // Stop recording pipeline
            if (recordingPipeline != null) {
                try {
                    recordingPipeline.stopRecording();
                    FLog.d(TAG, "Recording pipeline stopped");
                } catch (RuntimeException e) {
                    FLog.w(TAG, "Pipeline stop failed: " + e.getMessage());
                }
            }
            
            // Cleanup resources
            cleanupPipeline();
            forceNoAudioForThisStart = false;
            enableAudioForSession = false;
            previewOnlyActive = false;

            // Persist state on background thread before any UI callbacks.
            sharedPreferencesManager.setScreenRecordingState(ScreenRecordingState.NONE.name());
            
            // Calculate duration
            long duration = SystemClock.elapsedRealtime() - recordingStartTime;
            FLog.i(TAG, String.format("Recording stopped. Duration: %.1f seconds", duration / 1000.0));

            // STREAM_ONLY: delete the temporary recording file so it is never kept on disk.
            // Only do this when he session was started with an active server in STREAM_ONLY mode
            // (isStreamOnlySession is captured at recording-start so a stale pref never deletes
            // a recording that was made without an active server).
            boolean isStreamOnly = isStreamOnlySession;
            isStreamOnlySession = false; // Reset for next session
            if (isStreamOnly) {
                if (outputFile != null && outputFile.exists()) {
                    boolean deleted = outputFile.delete();
                    FLog.i(TAG, "🗑️ STREAM_ONLY: " + (deleted ? "Deleted" : "FAILED to delete")
                        + " temp file: " + outputFile.getName());
                    outputFile = null;
                }
                if (safRecordingUri != null) {
                    try {
                        // Use the correct SAF API — DocumentsContract.deleteDocument() respects
                        // FLAG_SUPPORTS_DELETE and works reliably on all API levels.
                        boolean deleted = DocumentsContract.deleteDocument(
                            getContentResolver(), safRecordingUri);
                        FLog.i(TAG, "🗑️ STREAM_ONLY: SAF delete " + (deleted ? "succeeded" : "returned false"));
                    } catch (Exception e) {
                        FLog.e(TAG, "STREAM_ONLY: Failed to delete SAF recording", e);
                    }
                    safRecordingUri = null;
                }
                // Invalidate storage cache so the UI immediately reflects reclaimed bytes.
                com.fadcam.utils.StorageInfoCache.clearCache();
            }

            final boolean streamOnlySkipNotify = isStreamOnly;

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

                if (streamOnlySkipNotify) {
                    // STREAM_ONLY: file was deleted above — no library entry, no notification.
                    FLog.i(TAG, "STREAM_ONLY: skipping recording-complete broadcast and notification");
                } else {
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
                            FLog.d(TAG, "Broadcasted ACTION_RECORDING_COMPLETE for list refresh");
                        } catch (Exception e) {
                            FLog.e(TAG, "Error broadcasting recording complete", e);
                        }

                        // Show completion notification with action to view recording
                        // For SAF mode, pass null (notification still opens Records tab)
                        showCompletionNotification(outputFile);
                    }
                }

                // Clear SAF URI
                safRecordingUri = null;
                releaseProjectionIfIdle();
                
                // Stop service
                stopForeground(true);
                stopSelf();
            });
            
        } catch (Exception e) {
            FLog.e(TAG, "Error stopping screen recording", e);
            sharedPreferencesManager.setScreenRecordingState(ScreenRecordingState.NONE.name());
            recordingState = ScreenRecordingState.NONE;
            releaseProjectionIfIdle();
            mainHandler.post(() -> stopSelf());
        }
    }

    /**
     * Handles pause recording request.
     */
    private void handlePauseRecording() {
        FLog.d(TAG, "handlePauseRecording: Pausing screen recording");
        
        if (recordingState != ScreenRecordingState.IN_PROGRESS) {
            FLog.w(TAG, "Cannot pause: not in progress");
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
                FLog.d(TAG, "Pause started at: " + pauseStartTime);
                
                // Stop notification updates while paused
                stopNotificationUpdates();
                
                // Update notification
                updateNotification();
                
                // Broadcast paused
                broadcastRecordingPaused();
                
                FLog.i(TAG, "Screen recording paused");
            } catch (Exception e) {
                FLog.e(TAG, "Error pausing recording", e);
            }
        } else {
            FLog.w(TAG, "Pause not supported on Android < N");
        }
    }

    /**
     * Handles resume recording request.
     */
    private void handleResumeRecording() {
        FLog.d(TAG, "handleResumeRecording: Resuming screen recording");
        
        if (recordingState != ScreenRecordingState.PAUSED) {
            FLog.w(TAG, "Cannot resume: not paused");
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
                    
                    FLog.d(TAG, "Pause duration: " + pauseDuration + "ms, total paused: " + totalPausedTime + "ms");
                    FLog.d(TAG, "Adjusted start time to: " + recordingStartTime);
                    
                    pauseStartTime = 0; // Reset
                }
                
                // Update notification
                updateNotification();
                
                // Restart notification updates
                startNotificationUpdates();
                
                // Broadcast resumed
                broadcastRecordingResumed();
                
                FLog.i(TAG, "Screen recording resumed");
            } catch (Exception e) {
                FLog.e(TAG, "Error resuming recording", e);
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
                    FLog.e(TAG, "Custom storage selected but URI is null, falling back to internal");
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
                        FLog.e(TAG, "Cannot write to custom directory, falling back to internal");
                        Toast.makeText(this, "Cannot write to custom directory", Toast.LENGTH_LONG).show();
                        // Fall through to internal storage
                    } else {
                        // Create file in custom location using SAF
                        androidx.documentfile.provider.DocumentFile videoFile = 
                            pickedDir.createFile("video/" + Constants.RECORDING_FILE_EXTENSION, baseFilename);
                        
                        if (videoFile == null) {
                            FLog.e(TAG, "Failed to create SAF file");
                            Toast.makeText(this, "Failed to create file in custom location", Toast.LENGTH_LONG).show();
                            // Fall through to internal storage
                        } else {
                            // Open ParcelFileDescriptor for the SAF file
                            safRecordingPfd = getContentResolver().openFileDescriptor(videoFile.getUri(), "w");
                            if (safRecordingPfd == null) {
                                FLog.e(TAG, "Failed to open ParcelFileDescriptor for SAF URI");
                                Toast.makeText(this, "Failed to open file for writing", Toast.LENGTH_LONG).show();
                                // Fall through to internal storage
                            } else {
                                // Store SAF URI for completion notification/broadcasting
                                safRecordingUri = videoFile.getUri();
                                FLog.d(TAG, "SAF output file created: " + safRecordingUri);
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
                FLog.e(TAG, "Cannot create Screen directory in recording root");
                Toast.makeText(this, "Error creating recording directory", Toast.LENGTH_LONG).show();
                return null;
            }
            
            File file = new File(videoDir, baseFilename);
            FLog.d(TAG, "Output file (internal): " + file.getAbsolutePath());
            return file;
        } catch (Exception e) {
            FLog.e(TAG, "Error creating output file", e);
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
                FLog.d(TAG, "Notification channel created");
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
            FLog.e(TAG, "NotificationManager is null, cannot show completion notification");
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
        FLog.d(TAG, "Completion notification shown");
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
                FLog.d(TAG, "WakeLock acquired");
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
            FLog.d(TAG, "WakeLock released");
        }
    }

    // Broadcast methods (using LocalBroadcastManager for guaranteed delivery on Android 12+)
    
    private void broadcastRecordingStarted() {
        Intent intent = new Intent(Constants.BROADCAST_ON_SCREEN_RECORDING_STARTED);
        intent.putExtra(Constants.INTENT_EXTRA_RECORDING_START_TIME, recordingStartTime);
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
        handleQueryRecordingState();
    }

    private void broadcastRecordingStopped() {
        Intent intent = new Intent(Constants.BROADCAST_ON_SCREEN_RECORDING_STOPPED);
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
        handleQueryRecordingState();
    }

    private void broadcastRecordingPaused() {
        Intent intent = new Intent(Constants.BROADCAST_ON_SCREEN_RECORDING_PAUSED);
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
        handleQueryRecordingState();
    }

    private void broadcastRecordingResumed() {
        Intent intent = new Intent(Constants.BROADCAST_ON_SCREEN_RECORDING_RESUMED);
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
        handleQueryRecordingState();
    }

    @Override
    public void onDestroy() {
        FLog.d(TAG, "onDestroy: Cleaning up");
        
        // Stop recording if still in progress
        if (recordingState != ScreenRecordingState.NONE) {
            stopScreenRecording();
        }
        
        // Release resources
        releaseWakeLock();
        stopNotificationUpdates();
        releasePreviewOnlyVirtualDisplay();
        releaseProjectionIfIdle();
        
        // Quit background thread
        if (backgroundThread != null) {
            backgroundThread.quitSafely();
            try {
                backgroundThread.join();
            } catch (InterruptedException e) {
                FLog.e(TAG, "Error joining background thread", e);
            }
            backgroundThread = null;
            backgroundHandler = null;
        }
        
        FLog.d(TAG, "ScreenRecordingService destroyed");
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
