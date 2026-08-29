package com.fadcam.streaming;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.fadcam.R;

/** Foreground lifecycle host for a resilient camera/microphone RTMP publication. */
public final class RtmpPublisherService extends Service implements RtmpPublisher.Listener {
    public static final String ACTION_START = "com.fadcam.streaming.START_RTMP";
    public static final String ACTION_STOP = "com.fadcam.streaming.STOP_RTMP";
    public static final String EXTRA_CREDENTIAL_ALIAS = "com.fadcam.streaming.EXTRA_CREDENTIAL_ALIAS";

    private static final String CHANNEL_ID = "fadcam_rtmp_streaming";
    private static final int NOTIFICATION_ID = 7401;

    @Nullable private RtmpPublisher publisher;
    @Nullable private RtmpCredentialVault vault;
    @Nullable private ConnectivityManager connectivityManager;
    @Nullable private ConnectivityManager.NetworkCallback networkCallback;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final RtmpReconnectPolicy reconnectPolicy = new RtmpReconnectPolicy();
    private String credentialAlias;
    private String endpoint;
    private boolean sessionActive;
    private boolean reconnectPending;
    private boolean authenticationFailed;
    private long lifecycleGeneration;

    @Override public void onCreate() {
        super.onCreate();
        createChannel();
        vault = new RtmpCredentialVault(getApplicationContext());
        publisher = new RtmpPublisher(getApplicationContext(), this);
        registerNetworkCallback();
    }

    @Override public int onStartCommand(@Nullable Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopPublishing();
            return START_NOT_STICKY;
        }
        if (intent != null && ACTION_START.equals(intent.getAction())) {
            String alias = intent.getStringExtra(EXTRA_CREDENTIAL_ALIAS);
            if (alias == null || alias.trim().isEmpty()) {
                stopPublishing();
                return START_NOT_STICKY;
            }
            credentialAlias = alias.trim();
            if (vault != null) vault.setActiveAlias(credentialAlias);
            sessionActive = true;
            authenticationFailed = false;
            reconnectPolicy.reset();
            cancelReconnect();
            startForegroundCompat(buildNotification("Preparing live camera…"));
            startOrReconnect();
            return START_STICKY;
        }

        // START_STICKY restart: recover only the non-secret profile alias.
        if (intent == null && vault != null) {
            credentialAlias = vault.getActiveAlias();
            if (credentialAlias != null && vault.contains(credentialAlias)) {
                sessionActive = true;
                authenticationFailed = false;
                startForegroundCompat(buildNotification("Restoring live stream…"));
                startOrReconnect();
                return START_STICKY;
            }
        }
        return START_NOT_STICKY;
    }

    private void startOrReconnect() {
        if (!sessionActive || authenticationFailed || publisher == null) return;
        if (!hasUsableNetwork()) {
            updateNotification("Waiting for network…");
            return;
        }
        RtmpCredentialVault.RtmpCredential credential = null;
        if (credentialAlias != null && vault != null) credential = vault.get(credentialAlias);
        if (credential == null) {
            updateNotification("RTMP credentials unavailable");
            stopPublishing();
            return;
        }

        // Construct the endpoint only inside the process. It never crosses an Intent boundary.
        endpoint = credential.getDestination().buildEndpoint(
                credential.getServerUrl(), credential.getStreamKey());
        try {
            if (!publisher.prepare()) {
                scheduleReconnect();
                return;
            }
            if (publisher.isStreaming()) return;
            publisher.start(endpoint);
        } catch (RuntimeException error) {
            scheduleReconnect();
        }
    }

    private void scheduleReconnect() {
        if (!sessionActive || authenticationFailed || reconnectPending) return;
        long delay = reconnectPolicy.nextDelayMs();
        if (delay < 0) {
            updateNotification("Stream stopped after repeated failures");
            stopPublishing();
            return;
        }
        reconnectPending = true;
        final long generation = lifecycleGeneration;
        updateNotification("Reconnecting in " + Math.max(1, Math.round(delay / 1000f)) + "s…");
        handler.postDelayed(() -> {
            if (generation != lifecycleGeneration) return;
            reconnectPending = false;
            startOrReconnect();
        }, delay);
    }

    private void cancelReconnect() {
        lifecycleGeneration++;
        reconnectPending = false;
        handler.removeCallbacksAndMessages(null);
    }

    private void registerNetworkCallback() {
        connectivityManager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (connectivityManager == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return;
        networkCallback = new ConnectivityManager.NetworkCallback() {
            @Override public void onLost(Network network) {
                if (!sessionActive) return;
                handler.post(() -> {
                    if (publisher != null) publisher.stop();
                    updateNotification("Network lost — waiting for connection…");
                });
            }

            @Override public void onAvailable(Network network) {
                if (!sessionActive) return;
                handler.post(() -> {
                    if (publisher != null && publisher.isStreaming()) return;
                    scheduleReconnect();
                });
            }

            @Override public void onCapabilitiesChanged(Network network, NetworkCapabilities capabilities) {
                if (!sessionActive || !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) return;
                handler.post(() -> {
                    if (publisher != null && publisher.isStreaming()) {
                        publisher.stop();
                        scheduleReconnect();
                    }
                });
            }
        };
        connectivityManager.registerDefaultNetworkCallback(networkCallback);
    }

    private boolean hasUsableNetwork() {
        if (connectivityManager == null) return true;
        Network network = connectivityManager.getActiveNetwork();
        if (network == null) return false;
        NetworkCapabilities capabilities = connectivityManager.getNetworkCapabilities(network);
        return capabilities != null && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
    }

    private void stopPublishing() {
        sessionActive = false;
        authenticationFailed = false;
        cancelReconnect();
        if (vault != null) vault.clearActiveAlias();
        if (publisher != null) publisher.stop();
        endpoint = null;
        credentialAlias = null;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(STOP_FOREGROUND_REMOVE);
        else stopForeground(true);
        stopSelf();
    }

    private void startForegroundCompat(Notification notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA | ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
        } else startForeground(NOTIFICATION_ID, notification);
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
        sessionActive = false;
        cancelReconnect();
        if (connectivityManager != null && networkCallback != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try { connectivityManager.unregisterNetworkCallback(networkCallback); }
            catch (IllegalArgumentException ignored) { }
        }
        networkCallback = null;
        if (publisher != null) {
            publisher.release();
            publisher = null;
        }
        endpoint = null;
        credentialAlias = null;
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public void onConnecting() { updateNotification("Connecting to live stream…"); }
    @Override public void onConnected() { reconnectPolicy.reset(); cancelReconnect(); updateNotification("Live — publishing camera + audio"); }
    @Override public void onBitrateChanged(long bitrate) { updateNotification("Live — " + Math.round(bitrate / 1000f) + " kbps"); }
    @Override public void onFailed(String reason) { scheduleReconnect(); }
    @Override public void onDisconnected() { scheduleReconnect(); }
    @Override public void onAuthError() { authenticationFailed = true; cancelReconnect(); updateNotification("Stream authentication failed"); }
    @Override public void onAuthSuccess() { authenticationFailed = false; reconnectPolicy.reset(); updateNotification("Authenticated — publishing"); }
}
