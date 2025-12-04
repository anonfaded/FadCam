package com.fadcam.services;

import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.exoplayer.ExoPlayer;

import com.fadcam.R;
import com.fadcam.playback.PlayerHolder;

/**
 * Foreground service to support background audio playback for VideoPlayer.
 * Note: This service is deprecated in favor of PlaybackService.
 */
public class BackgroundPlaybackService extends Service {

    private static final String TAG = "BackgroundPlaybackService";

    public static final String ACTION_START = "com.fadcam.action.START_BACKGROUND_PLAYBACK";
    public static final String ACTION_STOP = "com.fadcam.action.STOP_BACKGROUND_PLAYBACK";
    public static final String EXTRA_URI = "extra_uri";

    private static final int NOTIFICATION_ID = 6002;
    private static final String CHANNEL_ID = "playback_channel_v2";

    private ExoPlayer player;
    private boolean isForeground;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;
        String action = intent.getAction();
        
        if (ACTION_STOP.equals(action)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        
        if (ACTION_START.equals(action)) {
            Uri uri = intent.getParcelableExtra(EXTRA_URI);
            initPlayer(uri);
            showNotification();
        }
        
        return START_STICKY;
    }

    private void initPlayer(@Nullable Uri uri) {
        PlayerHolder holder = PlayerHolder.getInstance();
        player = holder.getOrCreate(this);
        player.setAudioAttributes(new AudioAttributes.Builder()
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                .setUsage(C.USAGE_MEDIA)
                .build(), true);
        if (uri != null) {
            holder.setMediaIfNeeded(uri);
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            android.app.NotificationChannel channel = new android.app.NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.app_name),
                    android.app.NotificationManager.IMPORTANCE_LOW);
            android.app.NotificationManager nm = getSystemService(android.app.NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(channel);
        }
    }

    private void showNotification() {
        Intent stopIntent = new Intent(this, BackgroundPlaybackService.class);
        stopIntent.setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(this, 0, stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0));

        NotificationCompat.Builder nb = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_playback)
                .setContentTitle(getString(R.string.app_name))
                .setContentText("Playing video")
                .addAction(R.drawable.ic_stop_red_24, "Stop", stopPi)
                .setPriority(NotificationCompat.PRIORITY_LOW);

        try {
            startForeground(NOTIFICATION_ID, nb.build());
            isForeground = true;
        } catch (Exception e) {
            android.util.Log.e(TAG, "Failed to start foreground", e);
        }
    }

    @Override
    public void onDestroy() {
        if (isForeground) {
            stopForeground(true);
        }
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
