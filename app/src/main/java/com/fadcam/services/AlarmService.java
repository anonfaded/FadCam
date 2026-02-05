package com.fadcam.services;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.MediaPlayer;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import com.fadcam.Constants;
import com.fadcam.MainActivity;
import com.fadcam.R;
import com.fadcam.streaming.RemoteStreamManager;

import java.io.File;

/**
 * AlarmService: Manages security alarm (buzzer) playback.
 * 
 * Use Cases:
 * - Security buzzer alert: Alert when intrusion detected or security breach
 * - Lost device finder: Ring alarm at full volume to locate forgotten phone/server
 * - Remote security response: Use as emergency alert system for CCTV monitoring
 * 
 * Features:
 * - Loops audio playback continuously
 * - Supports configurable duration (milliseconds or infinite)
 * - Auto-stops when duration expires
 * - Shows foreground notification (required for continuous audio on Android 8+)
 * - Respects system audio settings
 * - Compatible with Android 8+ (API 26+)
 */
public class AlarmService extends Service {
    private static final String TAG = "AlarmService";
    private static final int NOTIFICATION_ID = 1003;
    private static final String CHANNEL_ID = "AlarmServiceChannel";
    
    private MediaPlayer mediaPlayer;
    private NotificationManager notificationManager;
    private Handler handler;
    private HandlerThread handlerThread;
    private RemoteStreamManager streamManager;
    
    private boolean isAlarmRinging = false;
    private String currentAlarmSound = "office_phone.mp3";
    private long alarmDurationMs = -1; // -1 = infinite
    private long alarmStartTime = 0;
    
    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "AlarmService created");
        
        streamManager = RemoteStreamManager.getInstance();
        notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        
        // Create notification channel (required for Android 8+)
        createNotificationChannel();
        
        // Setup background handler
        handlerThread = new HandlerThread("AlarmServiceHandler");
        handlerThread.start();
        handler = new Handler(handlerThread.getLooper());
    }
    
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            Log.w(TAG, "Intent is null, stopping service");
            stopSelf();
            return START_NOT_STICKY;
        }
        
        String action = intent.getAction();
        Log.i(TAG, "AlarmService onStartCommand: " + action);
        
        if ("com.fadcam.action.RING_ALARM".equals(action)) {
            String soundFile = intent.getStringExtra("sound");
            long durationMs = intent.getLongExtra("duration_ms", -1);
            
            if (soundFile != null && !soundFile.isEmpty()) {
                // CRITICAL: Show notification immediately on Android 8+ to prevent crash
                // startForegroundService() requires startForeground() to be called within 5 seconds
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForeground(NOTIFICATION_ID, createNotification(soundFile, durationMs), 
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
                } else {
                    startForeground(NOTIFICATION_ID, createNotification(soundFile, durationMs));
                }
                
                // Now start audio playback in background to avoid blocking
                handler.post(() -> startAlarmPlayback(soundFile, durationMs));
            } else {
                Log.e(TAG, "Sound file not specified");
                stopSelf();
            }
            return START_STICKY; // Restart if killed
            
        } else if ("com.fadcam.action.STOP_ALARM".equals(action)) {
            stopAlarmPlayback();
            stopSelf();
            return START_NOT_STICKY;
        }
        
        return START_STICKY;
    }
    
    /**
     * Start alarm playback with specified sound and duration.
     */
    private void startAlarmPlayback(String soundFile, long durationMs) {
        try {
            Log.i(TAG, "🚨 Starting alarm playback: " + soundFile + " (Duration: " + (durationMs == -1 ? "infinite" : durationMs + "ms") + ")");
            
            // Stop previous playback if any
            if (mediaPlayer != null) {
                try {
                    mediaPlayer.stop();
                    mediaPlayer.release();
                } catch (Exception e) {
                    Log.e(TAG, "Error releasing previous MediaPlayer", e);
                }
                mediaPlayer = null;
            }
            
            // Create new MediaPlayer and load from assets
            mediaPlayer = new MediaPlayer();
            
            try {
                // Load audio file from assets: web/assets/alarms/filename
                String assetPath = "web/assets/alarms/" + soundFile;
                Log.d(TAG, "Loading audio from assets: " + assetPath);
                
                android.content.res.AssetFileDescriptor afd = getAssets().openFd(assetPath);
                mediaPlayer.setDataSource(afd.getFileDescriptor(), afd.getStartOffset(), afd.getLength());
                afd.close();
                
                mediaPlayer.setLooping(true); // Loop indefinitely
                mediaPlayer.prepare();
                mediaPlayer.start();
                
                Log.i(TAG, "✅ Alarm playback started successfully");
                
            } catch (java.io.IOException e) {
                Log.e(TAG, "❌ Error loading alarm sound from assets: " + soundFile, e);
                throw e;
            }
            
            isAlarmRinging = true;
            currentAlarmSound = soundFile;
            alarmDurationMs = durationMs;
            alarmStartTime = System.currentTimeMillis();
            
            // Update notification with current state
            updateNotification(soundFile, durationMs);
            
            // Schedule auto-stop if duration is not infinite
            if (durationMs > 0) {
                scheduleAutoStop(durationMs);
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Error starting alarm playback", e);
            stopAlarmPlayback();
            stopSelf();
        }
    }
    
    /**
     * Stop alarm playback.
     */
    private void stopAlarmPlayback() {
        try {
            Log.i(TAG, "🔇 Stopping alarm playback");
            
            if (mediaPlayer != null && mediaPlayer.isPlaying()) {
                mediaPlayer.stop();
                mediaPlayer.release();
                mediaPlayer = null;
            }
            
            isAlarmRinging = false;
            
            // CRITICAL: Update RemoteStreamManager so status JSON reflects alarm stopped
            // Without this, dashboard shows stale "RINGING" state after alarm auto-stops
            if (streamManager != null) {
                streamManager.setAlarmRinging(false);
                Log.i(TAG, "✅ RemoteStreamManager alarm state updated to false");
            }
            
            stopForeground(true); // Remove notification
            
        } catch (Exception e) {
            Log.e(TAG, "Error stopping alarm playback", e);
        }
    }
    
    /**
     * Schedule automatic stop when duration expires.
     */
    private void scheduleAutoStop(long durationMs) {
        handler.postDelayed(() -> {
            Log.i(TAG, "⏰ Alarm duration expired, stopping");
            stopAlarmPlayback();
            stopSelf();
        }, durationMs);
    }
    
    /**
     * Create notification for foreground service.
     */
    private Notification createNotification(String soundFile, long durationMs) {
        String durationStr = durationMs == -1 ? "Infinite" : formatDuration(durationMs);
        String contentText = soundFile + " • " + durationStr;
        
        Intent stopIntent = new Intent(this, AlarmService.class);
        stopIntent.setAction("com.fadcam.action.STOP_ALARM");
        PendingIntent stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent, 
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.M 
                ? PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
                : PendingIntent.FLAG_UPDATE_CURRENT
        );
        
        return new NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("🚨 Security Alarm Active")
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_notification_icon)
            .setContentIntent(PendingIntent.getActivity(this, 0, 
                new Intent(this, MainActivity.class), 
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.M 
                    ? PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
                    : PendingIntent.FLAG_UPDATE_CURRENT))
            .addAction(android.R.drawable.ic_media_pause, "Stop", stopPendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build();
    }
    
    /**
     * Update notification with current alarm state.
     */
    private void updateNotification(String soundFile, long durationMs) {
        notificationManager.notify(NOTIFICATION_ID, createNotification(soundFile, durationMs));
    }
    
    /**
     * Create notification channel (required for Android 8+).
     */
    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "Security Alarm",
                NotificationManager.IMPORTANCE_HIGH
            );
            channel.setDescription("Notifications for security alarm system");
            channel.enableVibration(true);
            notificationManager.createNotificationChannel(channel);
        }
    }
    
    /**
     * Format duration in milliseconds to readable string.
     */
    private String formatDuration(long durationMs) {
        if (durationMs <= 0) return "0s";
        
        long totalSeconds = durationMs / 1000;
        if (totalSeconds < 60) {
            return totalSeconds + "s";
        }
        
        long minutes = totalSeconds / 60;
        long seconds = totalSeconds % 60;
        
        if (seconds == 0) {
            return minutes + "m";
        }
        return minutes + "m " + seconds + "s";
    }
    
    @Override
    public void onDestroy() {
        Log.d(TAG, "AlarmService destroyed");
        stopAlarmPlayback();
        
        if (handlerThread != null) {
            handlerThread.quitSafely();
        }
        
        super.onDestroy();
    }
    
    @Override
    public IBinder onBind(Intent intent) {
        return null; // Not a bound service
    }
}
