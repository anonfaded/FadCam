package com.fadcam.services;

import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.media.app.NotificationCompat.MediaStyle;

import com.fadcam.R;
import com.fadcam.ui.VideoPlayerActivity;
import com.google.android.exoplayer2.ExoPlayer;
import com.google.android.exoplayer2.PlaybackParameters;
import com.google.android.exoplayer2.audio.AudioAttributes;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.ui.PlayerNotificationManager;
import com.google.android.exoplayer2.util.Util;
import com.google.android.exoplayer2.MediaMetadata;
import androidx.media.session.MediaButtonReceiver;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;

/**
 * Foreground service to support background audio playback for VideoPlayer.
 */
public class BackgroundPlaybackService extends Service {

    public static final String ACTION_START = "com.fadcam.action.START_BACKGROUND_PLAYBACK";
    public static final String ACTION_STOP = "com.fadcam.action.STOP_BACKGROUND_PLAYBACK";
    public static final String EXTRA_URI = "extra_uri";
    public static final String EXTRA_SPEED = "extra_speed";
    public static final String EXTRA_MUTED = "extra_muted";
    public static final String EXTRA_POSITION_MS = "extra_position_ms";
    public static final String EXTRA_PLAY_WHEN_READY = "extra_play_when_ready";
    public static final String EXTRA_FORCE_SHOW_NOTIFICATION = "extra_force_show_notification";
    private static final String ACTION_NOTIFICATION_STOP = "com.fadcam.action.NOTIF_STOP";

    private static final int NOTIFICATION_ID = 6001;
    // -------------- Fix Start for this field(CHANNEL_ID)-----------
    // Bump channel ID to ensure a fresh notification channel with proper importance
    private static final String CHANNEL_ID = "playback_channel_v2";
    // -------------- Fix Ended for this field(CHANNEL_ID)-----------

    private ExoPlayer player;
    private PlayerNotificationManager notificationManager;
    private MediaSessionCompat mediaSession;
    private boolean isForeground;
    // When rebuilding the PlayerNotificationManager, cancelling the old one fires onNotificationCancelled.
    // Suppress stopping the service during that window to avoid flicker.
    private volatile boolean suppressCancelStop = false;
    private final android.content.BroadcastReceiver noisyReceiver = new android.content.BroadcastReceiver() {
        @Override
        public void onReceive(android.content.Context context, Intent intent) {
            if (android.media.AudioManager.ACTION_AUDIO_BECOMING_NOISY.equals(intent.getAction())) {
                try { if (player != null && player.isPlaying()) player.pause(); } catch (Exception ignored) {}
            }
        }
    };

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;
        final String action = intent.getAction();
        if (ACTION_STOP.equals(action) || ACTION_NOTIFICATION_STOP.equals(action)) {
            suppressCancelStop = false; // explicit stop should not be suppressed
            stopForeground(true);
            stopSelf();
            releasePlayer();
            return START_NOT_STICKY;
        }
        if (ACTION_START.equals(action)) {
            Uri uri = intent.getParcelableExtra(EXTRA_URI);
            float speed = intent.getFloatExtra(EXTRA_SPEED, 1.0f);
            boolean muted = intent.getBooleanExtra(EXTRA_MUTED, false);
            long positionMs = intent.getLongExtra(EXTRA_POSITION_MS, 0L);
            boolean playWhenReady = intent.getBooleanExtra(EXTRA_PLAY_WHEN_READY, true);
            boolean forceShow = intent.getBooleanExtra(EXTRA_FORCE_SHOW_NOTIFICATION, false);
            ensureChannel();
            // -------------- Fix Start for this method(onStartCommand)-----------
            // Suppress cancellation side-effects while we rebuild the notification
            suppressCancelStop = true;
            if (!isForeground) {
                try {
                    startForeground(NOTIFICATION_ID, buildStubNotification());
                    isForeground = true;
                } catch (Exception ignored) {}
            }
            initPlayer(uri, speed, muted, positionMs, playWhenReady, forceShow);
            // Rebuild done; allow normal cancellation handling again
            suppressCancelStop = false;
            // -------------- Fix Ended for this method(onStartCommand)-----------
            return START_STICKY;
        }
        return START_NOT_STICKY;
    }

    // -------------- Fix Start for this method(initPlayer)-----------
    private void initPlayer(@Nullable Uri uri, float speed, boolean muted, long positionMs, boolean playWhenReady, boolean forceShow) {
        releasePlayer();
        // Use shared player instance so Activity and Service stay in sync
        com.fadcam.playback.PlayerHolder holder = com.fadcam.playback.PlayerHolder.getInstance();
        player = holder.getOrCreate(this);
        player.setAudioAttributes(new AudioAttributes.Builder()
                .setContentType(com.google.android.exoplayer2.C.AUDIO_CONTENT_TYPE_MOVIE)
                .setUsage(com.google.android.exoplayer2.C.USAGE_MEDIA)
                .build(), true);
        player.setPlayWhenReady(playWhenReady);
        if (uri != null) {
            holder.setMediaIfNeeded(uri);
        }
        player.setPlaybackParameters(new PlaybackParameters(speed));
        try { player.setVolume(muted ? 0f : 1f); } catch (Exception ignored) {}
        if (positionMs > 0) {
            try { player.seekTo(positionMs); } catch (Exception ignored) {}
        }

        // Create simple persistent notification instead of complex PlayerNotificationManager
        android.app.Notification notification = buildPlaybackNotification();
        startForeground(NOTIFICATION_ID, notification);
        isForeground = true;

        // Register noisy receiver to auto-pause when headset unplugged or audio becomes noisy
        try {
            registerReceiver(noisyReceiver, new android.content.IntentFilter(android.media.AudioManager.ACTION_AUDIO_BECOMING_NOISY));
        } catch (Exception ignored) {}
    }
    // -------------- Fix Ended for this method(initPlayer)-----------

    // -------------- Fix Start for this method(buildPlaybackNotification)-----------
    private android.app.Notification buildPlaybackNotification() {
        String title = getString(R.string.app_name) + " - Playing";
        String text = "Background playback active";
        
        // Intent to open the video player activity
        Intent openIntent = new Intent(this, VideoPlayerActivity.class);
        openIntent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent openPi = PendingIntent.getActivity(this, 0, openIntent, 
            PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0));
        
        // Intent to stop playback
        Intent stopIntent = new Intent(this, BackgroundPlaybackService.class).setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(this, 1, stopIntent, 
            PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0));

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_playback)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(openPi)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .addAction(R.drawable.ic_stop_red_24, "Stop", stopPi)
                .setStyle(new MediaStyle().setShowActionsInCompactView(0));

        return builder.build();
    }
    // -------------- Fix Ended for this method(buildPlaybackNotification)-----------

    // -------------- Fix Start for this method(buildStubNotification)-----------
    private android.app.Notification buildStubNotification() {
        NotificationCompat.Builder b = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_playback)
                .setContentTitle(getString(R.string.app_name))
                .setOnlyAlertOnce(true)
                .setOngoing(true)
                .setStyle(new MediaStyle());
        return b.build();
    }
    // -------------- Fix Ended for this method(buildStubNotification)-----------

    private void ensureChannel() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            android.app.NotificationManager nm = (android.app.NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null && nm.getNotificationChannel(CHANNEL_ID) == null) {
        android.app.NotificationChannel ch = new android.app.NotificationChannel(
                        CHANNEL_ID,
                        getString(R.string.app_name),
            android.app.NotificationManager.IMPORTANCE_LOW
                );
                nm.createNotificationChannel(ch);
            }
        }
    }

    // No manual buildNotification; PlayerNotificationManager drives the notification

    private void releasePlayer() {
        // No PlayerNotificationManager to release anymore
        try { unregisterReceiver(noisyReceiver); } catch (Exception ignored) {}
        if (mediaSession != null) {
            try { mediaSession.setActive(false); mediaSession.release(); } catch (Exception ignored) {}
            mediaSession = null;
        }
        // Do not release the shared player here; the activity may be using it
    }

    @Override
    public void onDestroy() {
        releasePlayer();
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) { return null; }
}
