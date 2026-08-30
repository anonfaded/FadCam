package com.fadcam.streaming;

import com.fadcam.FLog;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import com.fadcam.MainActivity;
import com.fadcam.R;
import com.fadcam.relay.HttpRelaySessionTransport;
import com.fadcam.relay.LocalDirectMediaTransport;
import com.fadcam.relay.RelaySessionController;
import com.fadcam.relay.ServerRoomRelayAgent;
import com.fadcam.relay.TransportController;
import java.io.IOException;
import java.net.ServerSocket;

/** Foreground owner for FadCam's local HTTP server and streaming runtime. */
public class RemoteStreamService extends Service {
    private static final String TAG = "RemoteStreamService";
    private static final String CHANNEL_ID = "remote_streaming_channel";
    private static final int NOTIFICATION_ID = 2001;
    private static final int DEFAULT_PORT = 8080;
    private static final int PORT_SCAN_RANGE = 10;
    private static final String PREFS = "FadCamPrefs";
    private static final String PREF_RELAY_ENDPOINT = "relay_session_endpoint";
    private static final String PREF_RELAY_AUTHENTICATION = "relay_authentication";
    private static final long RELAY_REQUEST_TIMEOUT_MS = 15000L;
    private static final long RELAY_MAX_REQUEST_AGE_MS = 30000L;
    private static final int RELAY_MAX_RECONNECT_ATTEMPTS = 3;

    private HardenedLiveM3U8Server httpServer;
    private int activePort = -1;
    private Handler notificationHandler;
    private Runnable notificationUpdateRunnable;
    private final IBinder binder = new LocalBinder();

    private TransportController transportController;
    private LocalDirectMediaTransport directMediaTransport;
    private RelaySessionController relaySessionController;
    private ServerRoomRelayAgent relayAgent;
    private boolean transportGraphCreated;

    public class LocalBinder extends Binder {
        public RemoteStreamService getService() { return RemoteStreamService.this; }
    }

    @Override public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        RemoteStreamManager manager = RemoteStreamManager.getInstance();
        manager.setContext(this);
        createTransportGraph(manager);
    }

    private synchronized void createTransportGraph(RemoteStreamManager manager) {
        if (transportGraphCreated) return;

        directMediaTransport = new LocalDirectMediaTransport();
        ServerRoomRelayAgent.RelayMediaSink inboundSink = payload -> { /* uplink graph has no inbound media consumer */ };

        String endpoint = getSharedPreferences(PREF_RELAY_ENDPOINT, MODE_PRIVATE)
                .getString(PREF_RELAY_ENDPOINT, null);
        String authentication = getSharedPreferences(PREF_RELAY_AUTHENTICATION, MODE_PRIVATE)
                .getString(PREF_RELAY_AUTHENTICATION, null);
        if (authentication == null || authentication.trim().isEmpty()) {
            authentication = CloudAuthManager.getInstance(this).getJwtToken();
        }

        if (endpoint != null && !endpoint.trim().isEmpty()
                && authentication != null && !authentication.trim().isEmpty()) {
            try {
                HttpRelaySessionTransport sessionTransport =
                        new HttpRelaySessionTransport(endpoint, 10000, 30000);
                String deviceId = CloudAuthManager.getInstance(this).getDeviceId();
                relaySessionController = new RelaySessionController(
                        deviceId, authentication, sessionTransport, System::currentTimeMillis,
                        RELAY_REQUEST_TIMEOUT_MS, RELAY_MAX_REQUEST_AGE_MS,
                        RELAY_MAX_RECONNECT_ATTEMPTS);
                relayAgent = new ServerRoomRelayAgent(relaySessionController, inboundSink);
            } catch (RuntimeException configurationError) {
                FLog.e(TAG, "Relay configuration is invalid; local transport remains available", configurationError);
                relaySessionController = null;
                relayAgent = null;
            }
        }

        if (relayAgent == null) {
            relayAgent = new ServerRoomRelayAgent(new ServerRoomRelayAgent.RelayTransport() {
                @Override public void connect() { throw new IllegalStateException("relay endpoint is not configured"); }
                @Override public void disconnect() { }
                @Override public boolean isConnected() { return false; }
                @Override public void sendInitializationSegment(byte[] payload) { throw new IllegalStateException("relay endpoint is not configured"); }
                @Override public void sendMedia(int sequenceNumber, byte[] payload, long durationMs) { throw new IllegalStateException("relay endpoint is not configured"); }
            }, inboundSink);
        }

        transportController = new TransportController(directMediaTransport, relayAgent);
        manager.setMediaTransport(transportController);
        transportGraphCreated = true;
        FLog.i(TAG, "Transport graph created exactly once for service lifecycle");
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && "com.fadcam.COPY_STREAM_URL".equals(intent.getAction())) {
            String streamUrl = intent.getStringExtra("stream_url");
            if (streamUrl != null) {
                android.content.ClipboardManager clipboard = (android.content.ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Stream URL", streamUrl));
            }
            return START_STICKY;
        }

        startForeground(NOTIFICATION_ID, buildNotification("Starting local server…", "http://..."));
        if (!startHttpServer()) {
            stopSelf();
            return START_NOT_STICKY;
        }
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putInt("stream_server_port", activePort).apply();

        try {
            if (transportController != null && !transportController.isConnected()) transportController.connect();
        } catch (Exception e) {
            FLog.e(TAG, "Failed to establish direct media transport", e);
            stopSelf();
            return START_NOT_STICKY;
        }

        RemoteStreamManager.getInstance().setStreamingEnabled(true);
        CloudStatusManager.getInstance(this).start();
        updateNotification();
        startNotificationUpdates();
        return START_STICKY;
    }

    @Override public void onDestroy() {
        RemoteStreamManager manager = RemoteStreamManager.getInstance();
        manager.setMediaTransport(null);
        manager.setStreamingEnabled(false);

        if (transportController != null) {
            transportController.stop();
            transportController = null;
        }
        if (relaySessionController != null) {
            try {
                relaySessionController.close();
            } catch (Exception e) {
                FLog.e(TAG, "Failed to close relay session cleanly", e);
            }
        }
        relayAgent = null;
        relaySessionController = null;
        directMediaTransport = null;
        transportGraphCreated = false;

        CloudStatusManager.getInstance(this).stop();
        stopNotificationUpdates();
        stopHttpServer();
        stopForeground(true);
        super.onDestroy();
    }

    public void updateStreamingMode() {
        if (!isServerRunning()) {
            if (startHttpServer()) {
                getSharedPreferences(PREFS, MODE_PRIVATE).edit().putInt("stream_server_port", activePort).apply();
                updateNotification();
            }
        }
    }

    public boolean isServerRunning() { return httpServer != null && httpServer.isAlive(); }

    private boolean startHttpServer() {
        int port = findFreePort(DEFAULT_PORT);
        if (port == -1) return false;
        try {
            httpServer = new HardenedLiveM3U8Server(this, port);
            httpServer.start();
            activePort = port;
            FLog.i(TAG, "HTTP streaming/control server started on port " + port + "; remote controls require authentication");
            return true;
        } catch (IOException e) {
            FLog.e(TAG, "Failed to start HTTP server", e);
            return false;
        }
    }

    private void stopHttpServer() {
        if (httpServer != null) {
            httpServer.stop();
            httpServer = null;
        }
        activePort = -1;
    }

    private int findFreePort(int startPort) {
        for (int port = startPort; port < startPort + PORT_SCAN_RANGE; port++) if (isPortAvailable(port)) return port;
        return -1;
    }

    private boolean isPortAvailable(int port) {
        try (ServerSocket socket = new ServerSocket(port)) {
            socket.setReuseAddress(true);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private String getLocalIpAddress() {
        try {
            java.util.Enumeration<java.net.NetworkInterface> interfaces = java.net.NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                java.net.NetworkInterface networkInterface = interfaces.nextElement();
                if (networkInterface.isLoopback() || !networkInterface.isUp()) continue;
                java.util.Enumeration<java.net.InetAddress> addresses = networkInterface.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    java.net.InetAddress address = addresses.nextElement();
                    if (!address.isLoopbackAddress() && address instanceof java.net.Inet4Address) return address.getHostAddress();
                }
            }
        } catch (Exception e) {
            FLog.e(TAG, "Failed to detect local IP", e);
        }
        return "N/A";
    }

    private Notification buildNotification(String contentText, String streamUrl) {
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Intent copyIntent = new Intent(this, RemoteStreamService.class).setAction("com.fadcam.COPY_STREAM_URL");
        copyIntent.putExtra("stream_url", streamUrl);
        PendingIntent copyPendingIntent = PendingIntent.getService(this, 1, copyIntent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Remote Streaming Active").setContentText(contentText)
                .setSmallIcon(R.drawable.ic_broadcast_on_personal_24).setContentIntent(pendingIntent)
                .addAction(android.R.drawable.ic_menu_view, "Copy Link", copyPendingIntent)
                .setOngoing(true).setShowWhen(false).setPriority(NotificationCompat.PRIORITY_LOW).build();
    }

    private void updateNotification() {
        if (activePort == -1) return;
        String dashboardUrl = "http://" + getLocalIpAddress() + ":" + activePort + "/";
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) manager.notify(NOTIFICATION_ID, buildNotification(dashboardUrl, dashboardUrl));
    }

    private void startNotificationUpdates() {
        notificationHandler = new Handler(Looper.getMainLooper());
        notificationUpdateRunnable = () -> {
            updateNotification();
            if (notificationHandler != null) notificationHandler.postDelayed(notificationUpdateRunnable, 30000);
        };
        notificationHandler.postDelayed(notificationUpdateRunnable, 30000);
    }

    private void stopNotificationUpdates() {
        if (notificationHandler != null && notificationUpdateRunnable != null) notificationHandler.removeCallbacks(notificationUpdateRunnable);
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "Remote Streaming", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Shows when remote streaming is active"); channel.setShowBadge(false);
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(channel);
        }
    }

    public String getStreamUrl() { return activePort == -1 ? null : "http://" + getLocalIpAddress() + ":" + activePort + "/"; }
    public String getDeviceIpWithPort() { return activePort == -1 ? null : getLocalIpAddress() + ":" + activePort; }
    public int getActivePort() { return activePort; }
    @Nullable @Override public IBinder onBind(Intent intent) { return binder; }
}
