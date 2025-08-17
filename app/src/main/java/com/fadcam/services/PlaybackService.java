package com.fadcam.services;

import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.support.v4.media.session.PlaybackStateCompat;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.media.app.NotificationCompat.MediaStyle;

import com.fadcam.R;
import com.fadcam.playback.PlayerHolder;
import com.fadcam.ui.VideoPlayerActivity;
import com.google.android.exoplayer2.ExoPlayer;
import com.google.android.exoplayer2.PlaybackParameters;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.audio.AudioAttributes;

/**
 * Media-style foreground service: posts a media notification with playback controls and progress.
 * This provides a better user experience for background media playback.
 */
public class PlaybackService extends Service {

    public static final String ACTION_START = "com.fadcam.action.START_BACKGROUND_PLAYBACK";
    public static final String ACTION_STOP = "com.fadcam.action.STOP_BACKGROUND_PLAYBACK";
    public static final String ACTION_PLAY_PAUSE = "com.fadcam.action.PLAY_PAUSE";
    public static final String ACTION_REWIND = "com.fadcam.action.REWIND";
    public static final String ACTION_FAST_FORWARD = "com.fadcam.action.FAST_FORWARD";
    public static final String EXTRA_URI = "extra_uri";
    public static final String EXTRA_SPEED = "extra_speed";
    public static final String EXTRA_MUTED = "extra_muted";
    public static final String EXTRA_POSITION_MS = "extra_position_ms";
    public static final String EXTRA_PLAY_WHEN_READY = "extra_play_when_ready";
    public static final String EXTRA_FORCE_SHOW_NOTIFICATION = "extra_force_show_notification";

    private static final int NOTIFICATION_ID = 6001;
    private static final String CHANNEL_ID = "playback_channel_v2";
    private static final int PROGRESS_UPDATE_INTERVAL = 1000; // Update every second
    private static final int REWIND_MS = 10000; // 10 seconds
    private static final int FAST_FORWARD_MS = 30000; // 30 seconds

    private ExoPlayer player;
    private boolean isForeground = false;
    private Handler progressUpdateHandler;
    private final Runnable progressUpdateRunnable = this::updateNotification;
    // Auto-stop timer
    private Handler autoStopHandler;
    private final Runnable autoStopRunnable = () -> {
        // Stop playback using the same logic as ACTION_STOP
        try {
            if (player != null) {
                player.pause();
                player.seekTo(0);
            }
        } catch (Exception ignored) {}
        stopProgressUpdates();
        stopForeground(true);
        stopSelf();
    };
    // Millis timestamp when the auto-stop alarm is scheduled (0 when none)
    private long autoStopTriggerAtMs = 0L;

    private static final String AUTO_STOP_ALARM_ACTION = "com.fadcam.action.AUTO_STOP_ALARM";
    private String videoTitle = ""; // Store the video filename/title

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;
        String action = intent.getAction();
        if (ACTION_STOP.equals(action)) {
            // Make sure to stop playback first, then clean up
            if (player != null) {
                try {
                    player.pause(); // First pause playback
                    player.seekTo(0); // Rewind to start
                } catch (Exception ignored) {}
            }
            stopProgressUpdates();
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }

        if (ACTION_PLAY_PAUSE.equals(action)) {
            handlePlayPause();
            return START_STICKY;
        }

        if (ACTION_REWIND.equals(action)) {
            handleRewind();
            return START_STICKY;
        }

        if (ACTION_FAST_FORWARD.equals(action)) {
            handleFastForward();
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
            startProgressUpdates();
            // Restore any persisted auto-stop trigger (in case service restarted)
            try{
                long persisted = com.fadcam.SharedPreferencesManager.getInstance(this).getLong("auto_stop_trigger_at_ms", 0L);
                if(persisted>System.currentTimeMillis()){ autoStopTriggerAtMs = persisted; }
            }catch(Exception ignored){}
            scheduleAutoStopIfNeeded();
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
            if (uri != null) {
                holder.setMediaIfNeeded(uri);
                // Extract and store the filename from the URI
                extractVideoTitle(uri);
            }
            player.setPlaybackParameters(new PlaybackParameters(speed));
            try { player.setVolume(muted ? 0f : 1f); } catch (Exception ignored) {}
            if (positionMs > 0) try { player.seekTo(positionMs); } catch (Exception ignored) {}
        } catch (Exception e) {
            android.util.Log.e("PlaybackService", "initPlayer failed", e);
        }
    }
    
    /**
     * Extracts a user-friendly title from the video URI
     */
    private void extractVideoTitle(Uri uri) {
        if (uri == null) {
            videoTitle = getString(R.string.app_name);
            return;
        }
        
        try {
            // Get just the filename without any path but WITH extension
            String filename = null;
            
            // First try to use the last path segment as that's usually the filename
            String lastSegment = uri.getLastPathSegment();
            if (lastSegment != null && !lastSegment.isEmpty()) {
                // Check if the last segment contains path separators (common in content:// URIs)
                if (lastSegment.contains("/")) {
                    // Extract only the part after the last path separator
                    filename = lastSegment.substring(lastSegment.lastIndexOf('/') + 1);
                } else {
                    filename = lastSegment;
                }
                
                // Keep the extension - don't remove it
                
                // Keep filename exactly as it is, including underscores and hyphens
                
                if (!filename.isEmpty()) {
                    videoTitle = filename;
                    return;
                }
            }
            
            // If that didn't work, try to get metadata from the player
            if (player != null && player.getCurrentMediaItem() != null && 
                player.getCurrentMediaItem().mediaMetadata != null && 
                player.getCurrentMediaItem().mediaMetadata.title != null) {
                
                String mediaTitle = player.getCurrentMediaItem().mediaMetadata.title.toString();
                
                // If the media title is actually a path, extract just the filename
                if (mediaTitle.contains("/")) {
                    videoTitle = mediaTitle.substring(mediaTitle.lastIndexOf('/') + 1);
                    // Keep the extension - don't remove it
                } else {
                    videoTitle = mediaTitle;
                }
                // Keep filename exactly as it is, don't replace any characters
                return;
            }
        } catch (Exception e) {
            android.util.Log.e("PlaybackService", "Error extracting video title", e);
        }
        
        // Fallback to app name if we couldn't get anything useful
        videoTitle = getString(R.string.app_name);
    }

    private void handlePlayPause() {
        try {
            if (player == null) {
                player = PlayerHolder.getInstance().getOrCreate(this);
            }
            if (player.isPlaying()) {
                player.pause();
                stopProgressUpdates();
                cancelAutoStop();
            } else {
                player.play();
                startProgressUpdates();
                scheduleAutoStopIfNeeded();
            }
            buildAndShowNotification();
        } catch (Exception e) { android.util.Log.e("PlaybackService", "play/pause failed", e); }
    }
    
    private void handleRewind() {
        try {
            if (player != null) {
                long newPosition = Math.max(0, player.getCurrentPosition() - REWIND_MS);
                player.seekTo(newPosition);
                buildAndShowNotification();
            }
        } catch (Exception e) { android.util.Log.e("PlaybackService", "rewind failed", e); }
    }
    
    private void handleFastForward() {
        try {
            if (player != null) {
                long duration = player.getDuration();
                long newPosition = Math.min(duration, player.getCurrentPosition() + FAST_FORWARD_MS);
                player.seekTo(newPosition);
                buildAndShowNotification();
            }
        } catch (Exception e) { android.util.Log.e("PlaybackService", "fast forward failed", e); }
    }
    
    private void startProgressUpdates() {
        if (progressUpdateHandler == null) {
            progressUpdateHandler = new Handler(Looper.getMainLooper());
        }
        // Remove any existing callbacks to avoid duplicates
        stopProgressUpdates();
        // Start new update cycle
        progressUpdateHandler.postDelayed(progressUpdateRunnable, PROGRESS_UPDATE_INTERVAL);
    }
    
    private void stopProgressUpdates() {
        if (progressUpdateHandler != null) {
            progressUpdateHandler.removeCallbacks(progressUpdateRunnable);
        }
    }

    private void scheduleAutoStopIfNeeded(){
        try{
            int seconds = com.fadcam.SharedPreferencesManager.getInstance(this).getBackgroundPlaybackTimerSeconds();
            cancelAutoStop();
            if(seconds>0){
                // Schedule an AlarmManager alarm that fires even if the process is idle
                long triggerAt = System.currentTimeMillis() + seconds * 1000L;
                android.app.AlarmManager am = (android.app.AlarmManager) getSystemService(ALARM_SERVICE);
                Intent alarmIntent = new Intent(this, AutoStopReceiver.class).setAction(AUTO_STOP_ALARM_ACTION);
                int flags = android.os.Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT : PendingIntent.FLAG_UPDATE_CURRENT;
                PendingIntent pi = PendingIntent.getBroadcast(this, 12345, alarmIntent, flags);
                if (am != null) {
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                        am.setExactAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, triggerAt, pi);
                    } else {
                        am.setExact(android.app.AlarmManager.RTC_WAKEUP, triggerAt, pi);
                    }
                }
                // Remember the trigger time for notification countdown
                autoStopTriggerAtMs = triggerAt;
                try{ com.fadcam.SharedPreferencesManager.getInstance(this).putLong("auto_stop_trigger_at_ms", autoStopTriggerAtMs); }catch(Exception ignored){}
                // Also schedule in-process handler as a fast fallback when service keeps running
                if(autoStopHandler==null) autoStopHandler = new Handler(Looper.getMainLooper());
                autoStopHandler.postDelayed(autoStopRunnable, seconds * 1000L);
            }
        }catch(Exception e){ android.util.Log.w("PlaybackService", "scheduleAutoStop failed", e); }
    }

    private void cancelAutoStop(){
        try{ if(autoStopHandler!=null) autoStopHandler.removeCallbacks(autoStopRunnable); } catch(Exception ignored){}
        try{
            android.app.AlarmManager am = (android.app.AlarmManager) getSystemService(ALARM_SERVICE);
            Intent alarmIntent = new Intent(this, AutoStopReceiver.class).setAction(AUTO_STOP_ALARM_ACTION);
            int flags = android.os.Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT : PendingIntent.FLAG_UPDATE_CURRENT;
            PendingIntent pi = PendingIntent.getBroadcast(this, 12345, alarmIntent, flags);
            if(am!=null){ am.cancel(pi); }
        }catch(Exception ignored){}
    // Clear remembered trigger
    autoStopTriggerAtMs = 0L;
    try{ com.fadcam.SharedPreferencesManager.getInstance(this).putLong("auto_stop_trigger_at_ms", 0L); }catch(Exception ignored){}
    }
    
    private void updateNotification() {
        if (player != null && player.isPlaying()) {
            // For updates, just silently update the notification
            buildAndShowNotification();
            // Schedule the next update
            if (progressUpdateHandler != null) {
                progressUpdateHandler.postDelayed(progressUpdateRunnable, PROGRESS_UPDATE_INTERVAL);
            }
        }
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
        
        // Get media duration and position information
        long duration = 0;
        long position = 0;
        try {
            if (player != null) {
                duration = player.getDuration();
                position = player.getCurrentPosition();
            }
        } catch (Exception ignored) {}
        
        // Format time for display (only if we have valid duration)
        String timeInfo = "";
        if (duration > 0) {
            String posStr = formatTime(position);
            String durStr = formatTime(duration);
            timeInfo = posStr + " / " + durStr;
        }

        // If we have an auto-stop scheduled, compute remaining time and append to subtext
        String remainingInfo = null;
        try{
            long now = System.currentTimeMillis();
            if(autoStopTriggerAtMs > now){
                long rem = autoStopTriggerAtMs - now;
                remainingInfo = formatTime(rem);
            }
        }catch(Exception ignored){}

        // Create PendingIntents for media control actions
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        
        Intent pp = new Intent(this, PlaybackService.class).setAction(ACTION_PLAY_PAUSE);
        PendingIntent ppIntent = PendingIntent.getService(this, 0, pp, flags);
        
        Intent rewind = new Intent(this, PlaybackService.class).setAction(ACTION_REWIND);
        PendingIntent rewindIntent = PendingIntent.getService(this, 3, rewind, flags);
        
        Intent ff = new Intent(this, PlaybackService.class).setAction(ACTION_FAST_FORWARD);
        PendingIntent ffIntent = PendingIntent.getService(this, 4, ff, flags);
        
        Intent stop = new Intent(this, PlaybackService.class).setAction(ACTION_STOP);
        PendingIntent stopIntent = PendingIntent.getService(this, 1, stop, flags);
        
        // Setup action icons
        int ppIcon = playing ? R.drawable.ic_pause_24 : R.drawable.ic_play_arrow_24;
        String ppText = playing ? getString(R.string.universal_pause) : getString(R.string.universal_play);

        // Create media style notification
        NotificationCompat.Builder nb = new NotificationCompat.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_playback)
                .setContentTitle(videoTitle != null && !videoTitle.isEmpty() ? videoTitle : getString(R.string.app_name))
                .setContentText(playing ? getString(R.string.universal_playing) : getString(R.string.universal_paused))
                .setSubText((timeInfo != null && !timeInfo.isEmpty()) ? (timeInfo + (remainingInfo!=null? " • "+remainingInfo+" left":"")) : (remainingInfo!=null? remainingInfo+" left":"")) // Show time info and optional remaining auto-stop countdown
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setOngoing(playing)
                .setOnlyAlertOnce(true) // Prevent sounds on updates
                .setSilent(true) // Ensure updates are silent
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC);
                
        // Add media control actions
        nb.addAction(R.drawable.ic_fast_rewind_24, getString(R.string.universal_rewind), rewindIntent); // Rewind button
        nb.addAction(ppIcon, ppText, ppIntent); // Play/Pause button
        nb.addAction(R.drawable.ic_fast_forward_24, getString(R.string.universal_forward), ffIntent); // Fast Forward button
        
        // Simple stop button action
        nb.addAction(R.drawable.ic_stop_red_24, getString(R.string.universal_stop), stopIntent); // Stop button
        
        // Apply MediaStyle to show transport controls in compact view
        MediaStyle style = new MediaStyle()
                .setShowActionsInCompactView(0, 1, 2); // Show rewind, play/pause, fast-forward in compact view
        nb.setStyle(style);
        
        // Add progress if we have valid duration (>0 and not UNKNOWN)
        if (duration > 0 && duration != com.google.android.exoplayer2.C.TIME_UNSET) {
            nb.setProgress((int)duration, (int)position, false);
            nb.setShowWhen(false); // Hide timestamp since we're showing our own progress
        }

        Intent open = new Intent(this, VideoPlayerActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent openPi = PendingIntent.getActivity(this, 2, open, flags);
        nb.setContentIntent(openPi);

        android.app.Notification notification = nb.build();

        try {
            if (!isForeground) {
                // First time showing notification, start as foreground
                startForeground(NOTIFICATION_ID, notification);
                isForeground = true;
            } else {
                // Just update the existing notification without sound/visual indicators
                android.app.NotificationManager nm = (android.app.NotificationManager) getSystemService(NOTIFICATION_SERVICE);
                if (nm != null) {
                    // Use FLAG_UPDATE_CURRENT to prevent creating multiple notifications
                    nm.notify(NOTIFICATION_ID, notification);
                }
            }
        } catch (Exception e) {
            android.util.Log.e("PlaybackService", "show notification failed", e);
        }
    }

    private void ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            android.app.NotificationManager nm = (android.app.NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null && nm.getNotificationChannel(CHANNEL_ID) == null) {
                android.app.NotificationChannel ch = new android.app.NotificationChannel(CHANNEL_ID, "FadCam Playback", android.app.NotificationManager.IMPORTANCE_LOW); // Use LOW importance to prevent sounds
                ch.setDescription("Background media playback controls");
                ch.setShowBadge(false);
                ch.setSound(null, null); // No sound for the channel
                ch.enableVibration(false); // No vibration
                ch.enableLights(false); // No notification light
                ch.setLockscreenVisibility(android.app.Notification.VISIBILITY_PUBLIC);
                try { nm.createNotificationChannel(ch); } catch (Exception ignored) {}
            }
        }
    }

    @Override
    public void onDestroy() {
        stopProgressUpdates();
        // Make sure playback is stopped when service is destroyed
        if (player != null) {
            try {
                player.pause(); // Ensure playback is stopped
            } catch (Exception ignored) {}
        }
        try { stopForeground(true); } catch (Exception ignored) {}
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) { return null; }
    
    /**
     * Format milliseconds to a readable time string (MM:SS or HH:MM:SS)
     */
    private String formatTime(long millis) {
        long totalSeconds = millis / 1000;
        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;
        
        if (hours > 0) {
            return String.format("%d:%02d:%02d", hours, minutes, seconds);
        } else {
            return String.format("%02d:%02d", minutes, seconds);
        }
    }
}