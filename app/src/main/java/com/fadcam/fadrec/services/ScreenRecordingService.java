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
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.MediaRecorder;
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
    private VirtualDisplay virtualDisplay;
    
    // Recording components
    private MediaRecorder mediaRecorder;
    private File outputFile;
    
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
        Log.d(TAG, "onStartCommand: action=" + action);

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
                
            default:
                Log.w(TAG, "Unknown action: " + action);
                break;
        }

        return START_STICKY;
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
     * Configures MediaRecorder, creates VirtualDisplay, and begins recording.
     */
    private void startScreenRecording() throws IOException {
        Log.d(TAG, "startScreenRecording: Configuring MediaRecorder");
        
        // Validate MediaProjection
        if (mediaProjection == null) {
            throw new IOException("MediaProjection is null - permission may have been revoked or not granted");
        }
        
        // Create output file
        outputFile = createOutputFile();
        if (outputFile == null) {
            throw new IOException("Failed to create output file - check storage permissions and available space");
        }
        
        // Configure MediaRecorder
        mediaRecorder = new MediaRecorder();
        
        try {
            // Audio source (microphone)
            String audioSource = sharedPreferencesManager.getScreenRecordingAudioSource();
            if (Constants.AUDIO_SOURCE_MIC.equals(audioSource)) {
                mediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
                Log.d(TAG, "Audio source: Microphone");
            } else {
                Log.d(TAG, "Audio source: None");
            }
            
            // Video source
            mediaRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
            
            // Output format
            mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            
            // Video encoder
            mediaRecorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
            Log.d(TAG, "Video encoder: H.264");
            
            // Audio encoder (if audio enabled)
            if (Constants.AUDIO_SOURCE_MIC.equals(audioSource)) {
                mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
                mediaRecorder.setAudioEncodingBitRate(Constants.DEFAULT_AUDIO_BITRATE);
                mediaRecorder.setAudioSamplingRate(Constants.DEFAULT_AUDIO_SAMPLING_RATE);
            }
            
            // Video configuration - Use actual screen resolution
            mediaRecorder.setVideoSize(screenWidth, screenHeight);
            mediaRecorder.setVideoFrameRate(Constants.DEFAULT_SCREEN_RECORDING_FPS);
            
            // Calculate bitrate based on resolution (higher res = higher bitrate)
            int calculatedBitrate = calculateBitrate(screenWidth, screenHeight);
            mediaRecorder.setVideoEncodingBitRate(calculatedBitrate);
            
            Log.d(TAG, String.format("Video config: %dx%d @%dfps, bitrate=%d",
                screenWidth, screenHeight,
                Constants.DEFAULT_SCREEN_RECORDING_FPS,
                calculatedBitrate));
            
            // Set output file
            mediaRecorder.setOutputFile(outputFile.getAbsolutePath());
            Log.d(TAG, "Output file set: " + outputFile.getAbsolutePath());
            
            // Prepare MediaRecorder
            mediaRecorder.prepare();
            Log.d(TAG, "MediaRecorder prepared successfully");
            
        } catch (IllegalStateException e) {
            Log.e(TAG, "IllegalStateException - MediaRecorder in wrong state: " + e.getMessage(), e);
            mediaRecorder.release();
            mediaRecorder = null;
            throw new IOException("MediaRecorder state error: " + e.getMessage(), e);
        } catch (IOException e) {
            Log.e(TAG, "IOException preparing MediaRecorder: " + e.getMessage(), e);
            mediaRecorder.release();
            mediaRecorder = null;
            throw new IOException("Failed to prepare MediaRecorder: " + e.getMessage(), e);
        } catch (Exception e) {
            Log.e(TAG, "Unexpected error preparing MediaRecorder: " + e.getMessage(), e);
            mediaRecorder.release();
            mediaRecorder = null;
            throw new IOException("Unexpected error: " + e.getMessage(), e);
        }
        
        // Create VirtualDisplay - MUST use same resolution as MediaRecorder
        try {
            if (mediaRecorder.getSurface() == null) {
                throw new IOException("MediaRecorder surface is null - cannot create VirtualDisplay");
            }
            
            virtualDisplay = mediaProjection.createVirtualDisplay(
                "FadRecDisplay",
                screenWidth,  // Use actual screen width
                screenHeight, // Use actual screen height
                screenDensity,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                mediaRecorder.getSurface(),
                null,
                backgroundHandler
            );
            
            if (virtualDisplay == null) {
                throw new IOException("VirtualDisplay creation returned null - device may not support this resolution");
            }
            
            Log.d(TAG, String.format("VirtualDisplay created: %dx%d @%ddpi", 
                screenWidth, screenHeight, screenDensity));
            
        } catch (IllegalArgumentException e) {
            Log.e(TAG, "IllegalArgumentException - Invalid VirtualDisplay parameters: " + e.getMessage(), e);
            if (mediaRecorder != null) {
                mediaRecorder.release();
                mediaRecorder = null;
            }
            throw new IOException("Invalid display parameters: " + e.getMessage(), e);
        } catch (Exception e) {
            Log.e(TAG, "Error creating VirtualDisplay: " + e.getMessage(), e);
            if (mediaRecorder != null) {
                mediaRecorder.release();
                mediaRecorder = null;
            }
            throw new IOException("Failed to create VirtualDisplay: " + e.getMessage(), e);
        }
        
        // Start recording
        try {
            mediaRecorder.start();
            recordingStartTime = SystemClock.elapsedRealtime();
            recordingState = ScreenRecordingState.IN_PROGRESS;
            
            // Reset pause tracking for new recording
            pauseStartTime = 0;
            totalPausedTime = 0;
            
            // Save recording start time to SharedPreferences for UI timer updates
            sharedPreferencesManager.sharedPreferences.edit()
                .putLong("screen_recording_start_time", recordingStartTime)
                .apply();
            
            Log.i(TAG, "Screen recording started successfully");
            
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
                
                // Broadcast recording started
                broadcastRecordingStarted();
                
                // Save state to preferences
                sharedPreferencesManager.setScreenRecordingInProgress(true);
                
                Toast.makeText(ScreenRecordingService.this, "Screen recording started", Toast.LENGTH_SHORT).show();
            });
            
        } catch (IllegalStateException e) {
            Log.e(TAG, "IllegalStateException - MediaRecorder in wrong state: " + e.getMessage(), e);
            if (mediaRecorder != null) {
                mediaRecorder.release();
                mediaRecorder = null;
            }
            if (virtualDisplay != null) {
                virtualDisplay.release();
                virtualDisplay = null;
            }
            if (mediaProjection != null) {
                mediaProjection.stop();
                mediaProjection = null;
            }
            recordingState = ScreenRecordingState.NONE;
            throw new IOException("MediaRecorder error: " + e.getMessage(), e);
        } catch (Exception e) {
            Log.e(TAG, "Unexpected error starting MediaRecorder: " + e.getMessage(), e);
            if (mediaRecorder != null) {
                mediaRecorder.release();
                mediaRecorder = null;
            }
            if (virtualDisplay != null) {
                virtualDisplay.release();
                virtualDisplay = null;
            }
            if (mediaProjection != null) {
                mediaProjection.stop();
                mediaProjection = null;
            }
            recordingState = ScreenRecordingState.NONE;
            throw new IOException("Failed to start MediaRecorder: " + e.getMessage(), e);
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
            // Stop MediaRecorder
            if (mediaRecorder != null) {
                try {
                    mediaRecorder.stop();
                    Log.d(TAG, "MediaRecorder stopped");
                } catch (RuntimeException e) {
                    Log.w(TAG, "MediaRecorder stop failed: " + e.getMessage());
                }
                
                mediaRecorder.reset();
                mediaRecorder.release();
                mediaRecorder = null;
            }
            
            // Release VirtualDisplay
            if (virtualDisplay != null) {
                virtualDisplay.release();
                virtualDisplay = null;
                Log.d(TAG, "VirtualDisplay released");
            }
            
            // Stop MediaProjection
            if (mediaProjection != null) {
                mediaProjection.stop();
                mediaProjection = null;
                Log.d(TAG, "MediaProjection stopped");
            }
            
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
                
                // Clear recording start time
                sharedPreferencesManager.sharedPreferences.edit()
                    .remove("screen_recording_start_time")
                    .apply();
                
                // Show completion message
                if (outputFile != null && outputFile.exists()) {
                    Toast.makeText(this, 
                        "Screen recording saved: " + outputFile.getName(), 
                        Toast.LENGTH_LONG).show();
                    
                    // Broadcast recording complete for RecordsFragment to refresh
                    try {
                        Intent recordingCompleteIntent = new Intent(Constants.ACTION_RECORDING_COMPLETE);
                        recordingCompleteIntent.putExtra("videoPath", outputFile.getAbsolutePath());
                        sendBroadcast(recordingCompleteIntent);
                        Log.d(TAG, "Broadcasted ACTION_RECORDING_COMPLETE for list refresh");
                    } catch (Exception e) {
                        Log.e(TAG, "Error broadcasting recording complete", e);
                    }
                    
                    // Show completion notification with action to view recording
                    showCompletionNotification(outputFile);
                }
                
                // Stop service
                stopForeground(true);
                stopSelf();
            });
            
        } catch (Exception e) {
            Log.e(TAG, "Error stopping screen recording", e);
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
                mediaRecorder.pause();
                recordingState = ScreenRecordingState.PAUSED;
                
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
                mediaRecorder.resume();
                recordingState = ScreenRecordingState.IN_PROGRESS;
                
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
     * Follows same logic as RecordingService - uses internal storage by default.
     * Directory changed from "FadCam" to "FadRec" to keep files organized.
     */
    @Nullable
    private File createOutputFile() {
        try {
            String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
            String baseFilename = Constants.RECORDING_FILE_PREFIX_FADREC + timestamp + "." 
                + Constants.RECORDING_FILE_EXTENSION;
            
            // Use internal storage (same as RecordingService), but FadRec directory
            File videoDir = new File(getExternalFilesDir(null), Constants.RECORDING_DIRECTORY_FADREC);
            if (!videoDir.exists() && !videoDir.mkdirs()) {
                Log.e(TAG, "Cannot create FadRec directory: " + videoDir.getAbsolutePath());
                Toast.makeText(this, "Error creating recording directory", Toast.LENGTH_LONG).show();
                return null;
            }
            
            File file = new File(videoDir, baseFilename);
            Log.d(TAG, "Output file: " + file.getAbsolutePath());
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
        
        // Also send state callback
        Intent stateIntent = new Intent(Constants.BROADCAST_ON_SCREEN_RECORDING_STATE_CALLBACK);
        stateIntent.putExtra("recordingState", ScreenRecordingState.IN_PROGRESS.name());
        LocalBroadcastManager.getInstance(this).sendBroadcast(stateIntent);
        Log.d(TAG, "Broadcasted recording started via LocalBroadcastManager");
    }

    private void broadcastRecordingStopped() {
        Intent intent = new Intent(Constants.BROADCAST_ON_SCREEN_RECORDING_STOPPED);
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
        
        // Also send state callback
        Intent stateIntent = new Intent(Constants.BROADCAST_ON_SCREEN_RECORDING_STATE_CALLBACK);
        stateIntent.putExtra("recordingState", ScreenRecordingState.NONE.name());
        LocalBroadcastManager.getInstance(this).sendBroadcast(stateIntent);
        Log.d(TAG, "Broadcasted recording stopped via LocalBroadcastManager");
    }

    private void broadcastRecordingPaused() {
        Intent intent = new Intent(Constants.BROADCAST_ON_SCREEN_RECORDING_PAUSED);
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
        
        // Also send state callback
        Intent stateIntent = new Intent(Constants.BROADCAST_ON_SCREEN_RECORDING_STATE_CALLBACK);
        stateIntent.putExtra("recordingState", ScreenRecordingState.PAUSED.name());
        LocalBroadcastManager.getInstance(this).sendBroadcast(stateIntent);
        Log.d(TAG, "Broadcasted recording paused via LocalBroadcastManager");
    }

    private void broadcastRecordingResumed() {
        Intent intent = new Intent(Constants.BROADCAST_ON_SCREEN_RECORDING_RESUMED);
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
        
        // Also send state callback
        Intent stateIntent = new Intent(Constants.BROADCAST_ON_SCREEN_RECORDING_STATE_CALLBACK);
        stateIntent.putExtra("recordingState", ScreenRecordingState.IN_PROGRESS.name());
        LocalBroadcastManager.getInstance(this).sendBroadcast(stateIntent);
        Log.d(TAG, "Broadcasted recording resumed via LocalBroadcastManager");
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
