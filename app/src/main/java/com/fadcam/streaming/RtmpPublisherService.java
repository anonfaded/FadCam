package com.fadcam.streaming;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.fadcam.R;

/**
 * Foreground host for the live camera/audio RTMP pipeline.
 *
 * <p>RootEncoder performs the H.264/AAC encoding and camera/audio capture.
 * This service owns the Android lifecycle so publication can continue while
 * the FadCam activity is backgrounded. Credentials are supplied only for the
 * current publication and are never persisted here.</p>
 */
public final class RtmpPublisherService extends Service implements RtmpPublisher.Listener {
    public static final String ACTION_START = "com.fadcam.streaming.START_RTMP";
    public static final String ACTION_STOP = "com.fadcam.streaming.STOP_RTMP";
    public static final String EXTRA_DESTINATION = "com.fadcam.streaming.EXTRA_DESTINATION";
    public static final String EXTRA_SERVER_URL = "com.fadcam.streaming.EXTRA_SERVER_URL";
    public static final String EXTRA_STREAM_KEY = "com.fadcam.streaming.EXTRA_STREAM_KEY";
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
        if (!ACTION_START.equals(intent.getAction())) return START_NOT_STICKY;

        startForegroundCompat(buildNotification("Preparing live camera…"));
        try {
            if (publisher == null) {
                stopPublishing();
                return START_NOT_STICKY;
            }

            String endpoint = resolveEndpoint(intent);
            if (endpoint == null) {
                updateNotification("Invalid RTMP destination");
                stopPublishing();
                return START_NOT_STICKY;
            }

            if (!publisher.prepare()) {
                stopPublishing();
                return START_NOT_STICKY;
            }
            publisher.start(endpoint);
            return START_STICKY;
        } catch (RuntimeException error) {
            updateNotification("Unable to start live stream");
            stopPublishing();
            return START_NOT_STICKY;
        }
    }

    @Nullable
    private String resolveEndpoint(Intent intent) {
        String direct = intent.getStringExtra(EXTRA_ENDPOINT);
        if (direct != null && !direct.trim().isEmpty()) return direct.trim();

        String serverUrl = intent.getStringExtra(EXTRA_SERVER_URL);
        String streamKey = intent.getStringExtra(EXTRA_STREAM_KEY);
        if (serverUrl == null || streamKey == null) return null;

        String destinationName = intent.getStringExtra(EXTRA_DESTINATION);
        RtmpDestination destination = RtmpDestination.CUSTOM;
        if (destinationName != null) {
            try {
                destination = RtmpDestination.valueOf(destinationName.toUpperCase(java.util.Locale.US));
            } catch (IllegalArgumentException ignored) {
                return null;
            }
        }
        return destination.buildEndpoint(serverUrl, streamKey);
    }

    private void stopPublishing() {
        if (publisher != null) publisher.stop();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE);
        } else {
            stopForeground(true);
        }
        stopSelf();
    }

    private void startForegroundCompat(Notification notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
                            | ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "FadCam live streaming", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Keeps FadCam camera and microphone publishing active");
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

    private void updateNotification(String text) {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) manager.notify(NOTIFICATION_ID, buildNotification(text));
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
    @Override public void onConnected() { updateNotification("Live — publishing camera + audio"); }
    @Override public void onBitrateChanged(long bitrate) {
        updateNotification("Live — " + Math.round(bitrate / 1000f) + " kbps");
    }
    @Override public void onFailed(String reason) { updateNotification("Stream failed"); }
    @Override public void onDisconnected() { updateNotification("Stream disconnected"); }
    @Override public void onAuthError() { updateNotification("Stream authentication failed"); }
    @Override public void onAuthSuccess() { updateNotification("Authenticated — publishing"); }
}
