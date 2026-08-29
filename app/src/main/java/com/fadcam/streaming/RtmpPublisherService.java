package com.fadcam.streaming;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.fadcam.R;

/** Foreground host for an RTMP publication. */
public class RtmpPublisherService extends Service implements RtmpPublisher.Listener {

    public static final String ACTION_START = "com.fadcam.streaming.START_RTMP";
    public static final String ACTION_STOP = "com.fadcam.streaming.STOP_RTMP";
    public static final String EXTRA_ENDPOINT = "com.fadcam.streaming.EXTRA_ENDPOINT";

    private static final String CHANNEL_ID = "fadcam_rtmp_streaming";
    private static final int NOTIFICATION_ID = 7401;

    @Nullable private RtmpPublisher publisher;

    @Override public void onCreate() {
        super.onCreate();
        createChannel();
        publisher = new RtmpPublisher(getApplicationContext(), this);
    }

    @Override public int onStartCommand(@Nullable Intent intent, int flags, int startId) {
        if (intent == null || ACTION_STOP.equals(intent.getAction())) {
            stopPublishing();
            return START_NOT_STICKY;
        }
        if (ACTION_START.equals(intent.getAction())) {
            String endpoint = intent.getStringExtra(EXTRA_ENDPOINT);
            if (endpoint == null || endpoint.trim().isEmpty()) {
                stopSelf(startId);
                return START_NOT_STICKY;
            }
            startForeground(NOTIFICATION_ID, buildNotification("Connecting to live stream…"));
            if (publisher == null || !publisher.prepare()) {
                stopPublishing();
                return START_NOT_STICKY;
            }
            try {
                publisher.start(endpoint);
            } catch (RuntimeException error) {
                stopPublishing();
            }
        }
        return START_STICKY;
    }

    private void stopPublishing() {
        if (publisher != null) publisher.stop();
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "FadCam live streaming", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Keeps FadCam RTMP publishing active in the background");
            getSystemService(NotificationManager.class).createNotificationChannel(channel);
        }
    }

    private Notification buildNotification(String text) {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("FadCam live stream")
                .setContentText(text)
                .setOngoing(true)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .build();
    }

    @Override public void onDestroy() {
        if (publisher != null) {
            publisher.release();
            publisher = null;
        }
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public void onConnecting(String endpoint) { updateNotification("Connecting to live stream…"); }
    @Override public void onConnected() { updateNotification("Live — publishing"); }
    @Override public void onBitrateChanged(long bitrate) { updateNotification("Live — " + Math.round(bitrate / 1000f) + " kbps"); }
    @Override public void onFailed(String reason) { updateNotification("Stream failed — retry required"); }
    @Override public void onDisconnected() { updateNotification("Stream disconnected"); }
    @Override public void onAuthError() { updateNotification("Stream authentication failed"); }
    @Override public void onAuthSuccess() { updateNotification("Authenticated — publishing"); }

    private void updateNotification(String text) {
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.notify(NOTIFICATION_ID, buildNotification(text));
    }
}
