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

import com.fadcam.R;
import com.fadcam.playback.PlayerHolder;
import com.fadcam.ui.VideoPlayerActivity;
import com.google.android.exoplayer2.ExoPlayer;
import com.google.android.exoplayer2.PlaybackParameters;
import com.google.android.exoplayer2.audio.AudioAttributes;

/**
 * Minimal foreground service: posts a simple notification with Play/Pause and Stop actions.
 * This intentionally avoids MediaSession/PNM complexity and directly controls the shared Player.
 */
public class PlaybackService extends Service {

    public static final String ACTION_START = "com.fadcam.action.START_BACKGROUND_PLAYBACK";
    public static final String ACTION_STOP = "com.fadcam.action.STOP_BACKGROUND_PLAYBACK";
    public static final String ACTION_PLAY_PAUSE = "com.fadcam.action.PLAY_PAUSE";
    public static final String EXTRA_URI = "extra_uri";
    public static final String EXTRA_SPEED = "extra_speed";
    public static final String EXTRA_MUTED = "extra_muted";
    public static final String EXTRA_POSITION_MS = "extra_position_ms";
    public static final String EXTRA_PLAY_WHEN_READY = "extra_play_when_ready";
    public static final String EXTRA_FORCE_SHOW_NOTIFICATION = "extra_force_show_notification";

    private static final int NOTIFICATION_ID = 6001;
    private static final String CHANNEL_ID = "playback_channel_v2";

    private ExoPlayer player;
    private boolean isForeground = false;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;
        String action = intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }

        if (ACTION_PLAY_PAUSE.equals(action)) {
            handlePlayPause();
            return START_STICKY;
        }

        if (ACTION_START.equals(action)) {
            Uri uri = intent.getParcelableExtra(EXTRA_URI);
            float speed = intent.getFloatExtra(EXTRA_SPEED, 1.0f);
            boolean muted = intent.getBooleanExtra(EXTRA_MUTED, false);
            long pos = intent.getLongExtra(EXTRA_POSITION_MS, 0L);
            boolean playWhenReady = intent.getBooleanExtra(EXTRA_PLAY_WHEN_READY, true);
            ensureChannel();
            initPlayer(uri, speed, muted, pos, playWhenReady);
            buildAndShowNotification();
            return START_STICKY;
        }

        return START_NOT_STICKY;
    }

    private void initPlayer(@Nullable Uri uri, float speed, boolean muted, long positionMs, boolean playWhenReady) {
        try {
            PlayerHolder holder = PlayerHolder.getInstance();
            player = holder.getOrCreate(this);
            player.setAudioAttributes(new AudioAttributes.Builder()
                    .setContentType(com.google.android.exoplayer2.C.AUDIO_CONTENT_TYPE_MOVIE)
                    .setUsage(com.google.android.exoplayer2.C.USAGE_MEDIA)
                    .build(), true);
            player.setPlayWhenReady(playWhenReady);
            if (uri != null) holder.setMediaIfNeeded(uri);
            player.setPlaybackParameters(new PlaybackParameters(speed));
            try { player.setVolume(muted ? 0f : 1f); } catch (Exception ignored) {}
            if (positionMs > 0) try { player.seekTo(positionMs); } catch (Exception ignored) {}
        } catch (Exception e) {
            android.util.Log.e("PlaybackService", "initPlayer failed", e);
        }
    }

    private void handlePlayPause() {
        try {
            if (player == null) {
                player = PlayerHolder.getInstance().getOrCreate(this);
            }
            if (player.isPlaying()) {
                player.pause();
            } else {
                player.play();
            }
            buildAndShowNotification();
        } catch (Exception e) { android.util.Log.e("PlaybackService", "play/pause failed", e); }
    }

    private void buildAndShowNotification() {
        Context ctx = this;
        boolean playing = false;
        try { 
            // Enhance player state detection by checking both isPlaying() and getPlayWhenReady()
            // This fixes the notification showing "Paused" while audio is still playing
            playing = player != null && (player.isPlaying() || 
                     (player.getPlaybackState() != ExoPlayer.STATE_ENDED && 
                      player.getPlaybackState() != ExoPlayer.STATE_IDLE && 
                      player.getPlayWhenReady()));
        } catch (Exception ignored) {}

        Intent pp = new Intent(this, PlaybackService.class).setAction(ACTION_PLAY_PAUSE);
        PendingIntent ppIntent = PendingIntent.getService(this, 0, pp,
                PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0));

        int ppIcon = playing ? R.drawable.ic_pause_24 : R.drawable.ic_play_arrow_24;
        String ppText = playing ? getString(R.string.universal_pause) : getString(R.string.universal_play);

        Intent stop = new Intent(this, PlaybackService.class).setAction(ACTION_STOP);
        PendingIntent stopIntent = PendingIntent.getService(this, 1, stop,
                PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0));

        NotificationCompat.Builder nb = new NotificationCompat.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_playback)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(playing ? getString(R.string.universal_playing) : getString(R.string.universal_paused))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setOngoing(playing)
                .addAction(ppIcon, ppText, ppIntent)
                .addAction(R.drawable.ic_stop_red_24, getString(R.string.universal_stop), stopIntent);

        Intent open = new Intent(this, VideoPlayerActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent openPi = PendingIntent.getActivity(this, 2, open,
                PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0));
        nb.setContentIntent(openPi);

        android.app.Notification notification = nb.build();

        try {
            if (!isForeground) {
                startForeground(NOTIFICATION_ID, notification);
                isForeground = true;
            } else {
                android.app.NotificationManager nm = (android.app.NotificationManager) getSystemService(NOTIFICATION_SERVICE);
                if (nm != null) nm.notify(NOTIFICATION_ID, notification);
            }
        } catch (Exception e) {
            android.util.Log.e("PlaybackService", "show notification failed", e);
        }
    }

    private void ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            android.app.NotificationManager nm = (android.app.NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null && nm.getNotificationChannel(CHANNEL_ID) == null) {
                android.app.NotificationChannel ch = new android.app.NotificationChannel(CHANNEL_ID, "FadCam Playback", android.app.NotificationManager.IMPORTANCE_HIGH);
                ch.setDescription("Background media playback controls");
                ch.setShowBadge(false);
                ch.setLockscreenVisibility(android.app.Notification.VISIBILITY_PUBLIC);
                try { nm.createNotificationChannel(ch); } catch (Exception ignored) {}
            }
        }
    }

    @Override
    public void onDestroy() {
        try { stopForeground(true); } catch (Exception ignored) {}
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) { return null; }
}