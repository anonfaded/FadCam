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
import androidx.media.app.NotificationCompat.MediaStyle;

import com.fadcam.R;
import com.fadcam.playback.PlayerHolder;
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
import java.util.List;
import java.util.Map;

/**
 * Foreground service to support background audio playback for VideoPlayer.
 */
public class BackgroundPlaybackService extends Service {

    private static final String TAG = "BackgroundPlaybackService";

    public static final String ACTION_START = "com.fadcam.action.START_BACKGROUND_PLAYBACK";
    public static final String ACTION_STOP = "com.fadcam.action.STOP_BACKGROUND_PLAYBACK";
    public static final String EXTRA_URI = "extra_uri";
    public static final String EXTRA_SPEED = "extra_speed";
    public static final String EXTRA_MUTED = "extra_muted";
    public static final String EXTRA_POSITION_MS = "extra_position_ms";
    public static final String EXTRA_PLAY_WHEN_READY = "extra_play_when_ready";
    public static final String EXTRA_FORCE_SHOW_NOTIFICATION = "extra_force_show_notification";
    private static final String ACTION_NOTIFICATION_STOP = "com.fadcam.action.NOTIF_STOP";
    private static final String ACTION_PLAY_PAUSE = "com.fadcam.action.PLAY_PAUSE";

    private static final int NOTIFICATION_ID = 6001;
    // -------------- Fix Start for this field(CHANNEL_ID)-----------
    // Bump channel ID to ensure a fresh notification channel with proper importance
    private static final String CHANNEL_ID = "playback_channel_v2";
    private static final String CUSTOM_ACTION_STOP = "com.fadcam.action.CUSTOM_STOP";
    // -------------- Fix Ended for this field(CHANNEL_ID)-----------

    private ExoPlayer player;
    private PlayerNotificationManager notificationManager;
    private MediaSessionCompat mediaSession;
    private boolean isForeground;
    // -------------- Fix Start for this field(playerListener)-----------
    private Player.Listener playerListener;
    // -------------- Fix Ended for this field(playerListener)-----------
    // When rebuilding the PlayerNotificationManager, cancelling the old one fires onNotificationCancelled.
    // We keep the implementation simple and let the PNM manage lifecycle.
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
        if (ACTION_PLAY_PAUSE.equals(action)) {
            try {
                if (player != null) {
                    if (player.isPlaying()) {
                        player.pause();
                    } else {
                        player.play();
                    }
                    // Refresh PNM
                    updateNotification();
                }
            } catch (Exception ignored) {}
            return START_STICKY;
        }
        if (ACTION_STOP.equals(action) || ACTION_NOTIFICATION_STOP.equals(action)) {
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
            // Don't start foreground twice - let initPlayer handle the real notification
            initPlayer(uri, speed, muted, positionMs, playWhenReady, forceShow);
            // -------------- Fix Ended for this method(onStartCommand)-----------
            return START_STICKY;
        }
        return START_NOT_STICKY;
    }

    // -------------- Fix Start for this method(initPlayer)-----------
    private void initPlayer(@Nullable Uri uri, float speed, boolean muted, long positionMs, boolean playWhenReady, boolean forceShow) {
        android.util.Log.d(TAG, "initPlayer called: uri=" + uri + ", playWhenReady=" + playWhenReady + ", speed=" + speed);
        // Do not fully release existing notification/MediaSession here; reuse to avoid races that cancel the notification.
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

    // Use MediaSession + PlayerNotificationManager
    createMediaSession();
    setupPlayerNotificationManager();

        // If caller explicitly requests a forced minimal notification, show one immediately to ensure
        // the service is running in foreground and avoid OEM races that cancel notifications.
        if (forceShow && !isForeground) {
            try {
                NotificationCompat.Builder b = new NotificationCompat.Builder(this, CHANNEL_ID)
                        .setContentTitle(getString(R.string.app_name))
                        .setContentText(getString(R.string.app_name))
                        .setSmallIcon(R.drawable.ic_stat_playback)
                        .setOngoing(true)
                        .setPriority(NotificationCompat.PRIORITY_HIGH);
                startForeground(NOTIFICATION_ID, b.build());
                isForeground = true;
                android.util.Log.d(TAG, "Started forced minimal foreground notification for debugging");
            } catch (Exception e) {
                android.util.Log.e(TAG, "Failed to start forced minimal foreground notification", e);
            }
        }

        // Register noisy receiver to auto-pause when headset unplugged or audio becomes noisy
        try {
            registerReceiver(noisyReceiver, new android.content.IntentFilter(android.media.AudioManager.ACTION_AUDIO_BECOMING_NOISY));
        } catch (Exception ignored) {}

        // -------------- Fix Start for this block(attachPlayerListener)-----------
        // Keep notification and lockscreen state in sync with actual player events
        if (playerListener != null) {
            try { player.removeListener(playerListener); } catch (Exception ignored) {}
        }
        playerListener = new Player.Listener() {
            @Override
            public void onIsPlayingChanged(boolean isPlaying) {
                android.util.Log.d(TAG, "onIsPlayingChanged: " + isPlaying);
                updateNotification();
            }

            @Override
            public void onPlayWhenReadyChanged(boolean playWhenReady, int reason) {
                android.util.Log.d(TAG, "onPlayWhenReadyChanged: " + playWhenReady + ", reason=" + reason);
                updateNotification();
            }

            @Override
            public void onPlaybackStateChanged(int state) {
                android.util.Log.d(TAG, "onPlaybackStateChanged: " + state);
                updateNotification();
            }
        };
        try { player.addListener(playerListener); } catch (Exception ignored) {}
        // -------------- Fix Ended for this block(attachPlayerListener)-----------
    }
    // -------------- Fix Ended for this method(initPlayer)-----------

    // PlayerNotificationManager builds notifications; no manual builder needed

    // -------------- Fix Start for this method(createMaterialIconDrawable)-----------
    private int createMaterialIconDrawable(String ligature) {
        try {
            // Load Material Icons font
            android.graphics.Typeface materialIcons = androidx.core.content.res.ResourcesCompat.getFont(this, R.font.materialicons);
            if (materialIcons == null) {
                android.util.Log.w(TAG, "Material Icons font not found, using fallback");
                return android.R.drawable.ic_media_play; // fallback
            }
            
            // Create bitmap with the icon
            int size = 48; // dp to px
            int sizePx = (int) (size * getResources().getDisplayMetrics().density);
            android.graphics.Bitmap bitmap = android.graphics.Bitmap.createBitmap(sizePx, sizePx, android.graphics.Bitmap.Config.ARGB_8888);
            android.graphics.Canvas canvas = new android.graphics.Canvas(bitmap);
            
            // Set up paint
            android.graphics.Paint paint = new android.graphics.Paint();
            paint.setTypeface(materialIcons);
            paint.setTextSize(sizePx * 0.8f); // 80% of size
            paint.setColor(android.graphics.Color.WHITE);
            paint.setAntiAlias(true);
            paint.setTextAlign(android.graphics.Paint.Align.CENTER);
            
            // Draw the ligature
            android.graphics.Rect textBounds = new android.graphics.Rect();
            paint.getTextBounds(ligature, 0, ligature.length(), textBounds);
            float x = sizePx / 2f;
            float y = sizePx / 2f + textBounds.height() / 2f;
            canvas.drawText(ligature, x, y, paint);
            
            // Create drawable
            android.graphics.drawable.BitmapDrawable drawable = new android.graphics.drawable.BitmapDrawable(getResources(), bitmap);
            
            // Cache it temporarily in a static map for reuse
            String fileName = "material_icon_" + ligature.hashCode();
            
            // For now, return a static resource ID as fallback
            if ("play_arrow".equals(ligature)) {
                return R.drawable.ic_play_arrow_24;
            } else if ("pause".equals(ligature)) {
                return R.drawable.ic_pause_24;
            } else if ("stop".equals(ligature)) {
                return R.drawable.ic_stop_red_24;
            }
            
            return android.R.drawable.ic_media_play;
            
        } catch (Exception e) {
            android.util.Log.e(TAG, "Failed to create material icon drawable for: " + ligature, e);
            return android.R.drawable.ic_media_play;
        }
    }
    // -------------- Fix Ended for this method(createMaterialIconDrawable)-----------

    // -------------- Fix Start for this method(createMediaSession)-----------
    private void createMediaSession() {
        if (mediaSession != null) {
            try { mediaSession.release(); } catch (Exception ignored) {}
            mediaSession = null;
        }
        android.content.ComponentName mbr = new android.content.ComponentName(getApplicationContext(), com.fadcam.services.MediaButtonReceiver.class);
        mediaSession = new MediaSessionCompat(this, "FadCamPlayback", mbr, null);
        
        // Set callback to handle media button events
        mediaSession.setCallback(new MediaSessionCompat.Callback() {
            @Override
            public void onPlay() {
                if (player != null && !player.isPlaying()) {
                    player.play();
                    updateNotification();
                }
            }

            @Override
            public void onPause() {
                if (player != null && player.isPlaying()) {
                    player.pause();
                    updateNotification();
                }
            }

            @Override
            public void onStop() {
                stopForeground(true);
                stopSelf();
            }
        });
        
        mediaSession.setActive(true);
        
        // Set playback state for lock screen controls
        updateMediaSessionPlaybackState();
    }
    // -------------- Fix Ended for this method(createMediaSession)-----------

    // -------------- Fix Start for this method(updateMediaSessionPlaybackState)-----------
    private void updateMediaSessionPlaybackState() {
        if (mediaSession == null || player == null) return;
        
        boolean isPlaying = false;
        long position = 0;
        try {
            isPlaying = player.isPlaying();
            position = player.getCurrentPosition();
        } catch (Exception ignored) {}
        
        int state = isPlaying ? PlaybackStateCompat.STATE_PLAYING : PlaybackStateCompat.STATE_PAUSED;
        PlaybackStateCompat.Builder stateBuilder = new PlaybackStateCompat.Builder()
                .setActions(PlaybackStateCompat.ACTION_PLAY | PlaybackStateCompat.ACTION_PAUSE | PlaybackStateCompat.ACTION_STOP)
                .setState(state, position, 1.0f);
        
        mediaSession.setPlaybackState(stateBuilder.build());
    }
    // -------------- Fix Ended for this method(updateMediaSessionPlaybackState)-----------

    // -------------- Fix Start for this method(updateNotification)-----------
    private void updateNotification() {
        updateMediaSessionPlaybackState();
        if (notificationManager != null) {
            notificationManager.invalidate();
        }
    }
    // -------------- Fix Ended for this method(updateNotification)-----------

    // Stub no longer needed; PNM handles notification lifecycle

    // No bootstrap; PNM will manage the notification lifecycle

    private void ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            android.app.NotificationManager nm = (android.app.NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) {
                if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                    android.app.NotificationChannel ch = new android.app.NotificationChannel(
                            CHANNEL_ID,
                            "FadCam Playback",
                            android.app.NotificationManager.IMPORTANCE_HIGH
                    );
                    ch.setDescription("Background media playback controls");
                    ch.setShowBadge(false);
                    ch.setLockscreenVisibility(android.app.Notification.VISIBILITY_PUBLIC);
                    try {
                        nm.createNotificationChannel(ch);
                        android.util.Log.d(TAG, "Created notification channel: " + CHANNEL_ID);
                    } catch (Exception e) {
                        android.util.Log.e(TAG, "Failed to create notification channel", e);
                    }
                } else {
                    android.util.Log.d(TAG, "Notification channel already exists: " + CHANNEL_ID);
                }
            } else {
                android.util.Log.e(TAG, "NotificationManager is null");
            }
        } else {
            android.util.Log.d(TAG, "API < O, no notification channel needed");
        }
    }    // No manual buildNotification; PlayerNotificationManager drives the notification

    // -------------- Fix Start for this method(setupPlayerNotificationManager)-----------
    private void setupPlayerNotificationManager() {
    // Create a fresh PlayerNotificationManager with default behavior.

        final android.content.Context ctx = this;
        final String channelId = CHANNEL_ID;

        PlayerNotificationManager.MediaDescriptionAdapter descriptionAdapter = new PlayerNotificationManager.MediaDescriptionAdapter() {
            @Nullable
            @Override
            public CharSequence getCurrentContentTitle(Player player) {
                return getString(R.string.app_name);
            }

            @Nullable
            @Override
            public CharSequence getCurrentContentText(Player player) {
                try {
                    Uri u = com.fadcam.playback.PlayerHolder.getInstance().getCurrentUri();
                    String txt = u != null ? (u.getLastPathSegment() != null ? u.getLastPathSegment() : u.toString()) : "";
                    android.util.Log.d(TAG, "PNM getCurrentContentText -> " + txt);
                    return txt;
                } catch (Exception ignored) { return ""; }
            }

            @Nullable
            @Override
            public android.graphics.Bitmap getCurrentLargeIcon(Player player, PlayerNotificationManager.BitmapCallback callback) {
                return null; // No artwork for now
            }

            @Override
            public PendingIntent createCurrentContentIntent(Player player) {
                Intent openIntent = new Intent(ctx, VideoPlayerActivity.class);
                openIntent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
                return PendingIntent.getActivity(ctx, 0, openIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0));
            }
        };

        PlayerNotificationManager.NotificationListener listener = new PlayerNotificationManager.NotificationListener() {
            @Override
            public void onNotificationPosted(int notificationId, android.app.Notification notification, boolean ongoing) {
                android.util.Log.d(TAG, "PNM onNotificationPosted id=" + notificationId + " ongoing=" + ongoing + " isForeground=" + isForeground);
                if (!isForeground) {
                    try {
                        startForeground(NOTIFICATION_ID, notification);
                        isForeground = true;
                        android.util.Log.d(TAG, "Started foreground from PNM");
                    } catch (Exception e) {
                        android.util.Log.e(TAG, "Failed to startForeground from PNM", e);
                    }
                } else {
                    android.app.NotificationManager nm = (android.app.NotificationManager) getSystemService(NOTIFICATION_SERVICE);
                    if (nm != null) nm.notify(NOTIFICATION_ID, notification);
                }
            }

            @Override
            public void onNotificationCancelled(int notificationId, boolean dismissedByUser) {
                android.util.Log.d(TAG, "PNM onNotificationCancelled id=" + notificationId + " dismissedByUser=" + dismissedByUser);
                isForeground = false;
                try { stopForeground(true); } catch (Exception ignored) {}
                if (dismissedByUser) {
                    stopSelf();
                }
            }
        };

    // No custom actions; rely on default PNM actions (play/pause/stop handled by PNM)

    PlayerNotificationManager.Builder pnmBuilder = new PlayerNotificationManager.Builder(ctx, NOTIFICATION_ID, channelId)
        .setMediaDescriptionAdapter(descriptionAdapter)
        .setNotificationListener(listener)
        .setSmallIconResourceId(R.drawable.ic_stat_playback);

    notificationManager = pnmBuilder.build();

    // Tie to MediaSession for lock screen controls
    if (mediaSession != null) {
        notificationManager.setMediaSessionToken(mediaSession.getSessionToken());
    }

        // Attach player last to trigger notification, but only when player has media and is in a stable state.
        try {
            boolean hasMedia = false;
            try { hasMedia = player != null && player.getMediaItemCount() > 0; } catch (Exception ignored) {}
            boolean isReadyOrPlaying = false;
            try { isReadyOrPlaying = player != null && (player.isPlaying() || player.getPlaybackState() == Player.STATE_READY); } catch (Exception ignored) {}

            if (hasMedia && isReadyOrPlaying) {
                notificationManager.setPlayer(player);
                android.util.Log.d(TAG, "PNM attached immediately (hasMedia=" + hasMedia + ", isReadyOrPlaying=" + isReadyOrPlaying + ")");
            } else {
                // Attach when the player becomes ready or starts playing to avoid showing a notification for transient states
                Player.Listener attachOnce = new Player.Listener() {
                    @Override
                    public void onIsPlayingChanged(boolean isPlaying) {
                        if (isPlaying) {
                            try {
                                notificationManager.setPlayer(player);
                                android.util.Log.d(TAG, "PNM attached onIsPlayingChanged");
                            } catch (Exception e) { android.util.Log.e(TAG, "Failed to attach PNM onIsPlayingChanged", e); }
                            try { player.removeListener(this); } catch (Exception ignored) {}
                        }
                    }

                    @Override
                    public void onPlaybackStateChanged(int state) {
                        if (state == Player.STATE_READY) {
                            try {
                                notificationManager.setPlayer(player);
                                android.util.Log.d(TAG, "PNM attached onPlaybackStateChanged READY");
                            } catch (Exception e) { android.util.Log.e(TAG, "Failed to attach PNM onPlaybackStateChanged", e); }
                            try { player.removeListener(this); } catch (Exception ignored) {}
                        }
                    }
                };
                try { if (player != null) player.addListener(attachOnce); } catch (Exception ignored) {}
                android.util.Log.d(TAG, "PNM attach deferred (hasMedia=" + hasMedia + ", isReadyOrPlaying=" + isReadyOrPlaying + ")");
            }
        } catch (Exception e) {
            android.util.Log.e(TAG, "Error while deciding when to attach PNM", e);
            try { notificationManager.setPlayer(player); } catch (Exception ignored) {}
        }
    }
    // -------------- Fix Ended for this method(setupPlayerNotificationManager)-----------

    private void releasePlayer() {
        // No PlayerNotificationManager to release anymore
        try { unregisterReceiver(noisyReceiver); } catch (Exception ignored) {}
        if (mediaSession != null) {
            try { mediaSession.setActive(false); mediaSession.release(); } catch (Exception ignored) {}
            mediaSession = null;
        }
        if (notificationManager != null) {
            try { notificationManager.setPlayer(null); } catch (Exception ignored) {}
            notificationManager = null;
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
